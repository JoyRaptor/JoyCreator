#!/usr/bin/env python3
"""Tests for jb_archive_report.py: offline archive inspection (read-only).

Builds small synthetic ZIPs in temp dirs covering: the current recognizable
structure, an unknown version, duplicate names, suspicious paths, malformed
JSON, metadata over the 8MiB cap, missing metadata, declared tile-size
mismatches, and a corrupt ZIP. Verifies the tool never extracts or rewrites
anything (input bytes bit-identical afterwards) and smoke-tests valid JSON
CLI output. Standard library only.
"""

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

from jb_archive_report import suspicious_reason

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "jb_archive_report.py")

JB_MIMETYPE = b"application/x-joybrush"  # JbArchive.kt:24
TILE_BYTES = 256 * 256 * 4  # 262144, JbArchive.kt:32
MEDIA_TILE_BYTES = 256 * 256 * 8  # 524288, MediaStores.kt:28-31
DOCUMENT_CAP = 8 * 1024 * 1024


def _tile_bytes(seed, size=TILE_BYTES):
    return bytes((i * 31 + seed * 7) & 0xFF for i in range(size))


def _write_zip(path, entries):
    """Write entries in order: (name, data, compress_type or 'stored')."""
    with zipfile.ZipFile(path, "w") as zf:
        for name, data, method in entries:
            if method == "stored":
                info = zipfile.ZipInfo(filename=name)
                info.compress_type = zipfile.ZIP_STORED
                zf.writestr(info, data)
            else:
                zf.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)


def _doc(version=8, boards=1, layers=("L1", "L2"), cels=(("c1", "c2"), ("c3",))):
    return json.dumps(
        {
            "format": "joybrush.document",
            "version": version,
            "id": "doc-1",
            "name": "Synthetic",
            "boards": [{"id": "b%d" % i} for i in range(boards)],
            "layers": [
                {"id": lid, "cels": [{"id": c} for c in clist]}
                for lid, clist in zip(layers, cels)
            ],
        }
    ).encode("utf-8")


def _good_entries(doc_bytes=None):
    return [
        ("mimetype", JB_MIMETYPE, "stored"),
        (DOCUMENT_NAME_PLACEHOLDER, doc_bytes or _doc(), "deflated"),
        ("layers/L1/c1/0_0.rgba", _tile_bytes(1), "deflated"),
        ("layers/L1/c1/0_0.paper.f16", _tile_bytes(5, MEDIA_TILE_BYTES), "deflated"),
        ("layers/L2/c2/strokes.jbs", b"\x00" * 32, "deflated"),
        ("thumbnail.png", b"\x89PNG" + b"\x00" * 64, "deflated"),
        ("future/whatever.bin", b"future payload", "deflated"),
    ]


DOCUMENT_NAME_PLACEHOLDER = "document.json"


def _run_cli(archive_path):
    return subprocess.run(
        [sys.executable, SCRIPT, archive_path],
        capture_output=True,
        text=True,
        timeout=120,
    )


def _run_ok(archive_path):
    proc = _run_cli(archive_path)
    assert proc.returncode == 0, "CLI failed: rc=%d stderr=%s stdout=%s" % (
        proc.returncode,
        proc.stderr[-2000:],
        proc.stdout[-2000:],
    )
    return json.loads(proc.stdout)


class ArchiveReportTest(unittest.TestCase):
    def test_recognizable_structure_reports_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "good.joybrush")
            _write_zip(path, _good_entries())
            report = _run_ok(path)
            self.assertTrue(report["inspection_only"])
            self.assertIn("inspection", report["note"].lower())
            doc = report["document"]
            self.assertEqual(doc["status"], "parsed")
            self.assertEqual(doc["version"], 8)
            self.assertEqual(doc["version_status"], "current")
            self.assertEqual(doc["board_count"], 1)
            self.assertEqual(doc["layer_count"], 2)
            self.assertEqual(doc["cel_count"], 3)
            rec = report["recognized"]
            self.assertTrue(rec["mimetype"]["present"])
            self.assertTrue(rec["mimetype"]["first"])
            self.assertTrue(rec["mimetype"]["matches_expected"])
            self.assertEqual(rec["paint_tiles"], 1)
            self.assertEqual(rec["media_tiles"], 1)
            self.assertEqual(rec["strokes_files"], 1)
            self.assertEqual(report["zip"]["unknown_extensions"], {".bin": 1})
            self.assertEqual(report["zip"]["duplicate_names"], [])
            self.assertEqual(report["zip"]["suspicious_names"], [])
            self.assertEqual(report["warnings"], [])
            kinds = report["zip"]["counts_by_kind"]
            self.assertGreaterEqual(kinds.get("unknown", 0), 1)  # future payload

    def test_unknown_version_stays_unknown_not_invalid(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "future.joybrush")
            _write_zip(path, _good_entries(doc_bytes=_doc(version=999)))
            report = _run_ok(path)
            doc = report["document"]
            self.assertEqual(doc["status"], "unknown_version")
            self.assertEqual(doc["version"], 999)
            self.assertEqual(doc["version_status"], "unknown")
            self.assertIsNone(doc["board_count"])
            self.assertIsNone(doc["layer_count"])
            self.assertIsNone(doc["cel_count"])
            blob = json.dumps(report).lower()
            self.assertNotIn("invalid", blob)
            self.assertTrue(any("999" in w for w in report["warnings"]))

    def test_duplicate_names_flagged(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "dup.joybrush")
            tile = _tile_bytes(1)
            _write_zip(
                path,
                [
                    ("mimetype", JB_MIMETYPE, "stored"),
                    ("document.json", _doc(), "deflated"),
                    ("layers/L1/c1/0_0.rgba", tile, "deflated"),
                    ("layers/L1/c1/0_0.rgba", tile, "deflated"),
                ],
            )
            report = _run_ok(path)
            self.assertEqual(
                report["zip"]["duplicate_names"],
                [{"name": "layers/L1/c1/0_0.rgba", "count": 2}],
            )
            self.assertTrue(any("twice" in w or "2 times" in w for w in report["warnings"]))

    def test_suspicious_paths_flagged_but_reported(self):
        # NOTE: CPython zipfile normalizes backslash to '/' on write, so a
        # backslash entry cannot survive a ZIP roundtrip to reach the tool.
        # Backslash detection is covered directly via suspicious_reason below.
        self.assertIsNotNone(suspicious_reason("a\\b"))
        bad = [
            "../escape",
            "/absolute/escape",
            "a:b",
            "a//b",
            "a\x01b",
            "layers/.. /escape",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "sneaky.joybrush")
            _write_zip(
                path,
                [("mimetype", JB_MIMETYPE, "stored"), ("document.json", _doc(), "deflated")]
                + [(name, b"payload", "deflated") for name in bad],
            )
            report = _run_ok(path)  # bounded suspicious metadata -> report, exit 0
            flagged = {item["name"] for item in report["zip"]["suspicious_names"]}
            for name in bad:
                self.assertIn(name, flagged, "not flagged: %r" % name)
            self.assertGreaterEqual(len(report["warnings"]), len(bad))

    def test_malformed_document_json_yields_warning_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "badjson.joybrush")
            _write_zip(
                path,
                [
                    ("mimetype", JB_MIMETYPE, "stored"),
                    ("document.json", b"{not json{{{", "deflated"),
                ],
            )
            report = _run_ok(path)
            self.assertEqual(report["document"]["status"], "parse_error")
            self.assertIsNone(report["document"]["board_count"])
            self.assertTrue(report["warnings"])

    def test_document_over_cap_left_unread(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "big.joybrush")
            pad = b"a" * (DOCUMENT_CAP + 16)  # deflates tiny on disk, declares > 8MiB
            doc = b'{"format": "joybrush.document", "version": 8, "pad": "' + pad + b'"}'
            self.assertGreater(len(doc), DOCUMENT_CAP)
            _write_zip(
                path,
                [("mimetype", JB_MIMETYPE, "stored"), ("document.json", doc, "deflated")],
            )
            report = _run_ok(path)
            self.assertEqual(report["document"]["status"], "over_cap")
            self.assertIsNone(report["document"]["board_count"])
            self.assertTrue(any("8MiB" in w for w in report["warnings"]))

    def test_missing_document_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "nodoc.joybrush")
            _write_zip(path, [("mimetype", JB_MIMETYPE, "stored")])
            report = _run_ok(path)
            self.assertEqual(report["document"]["status"], "missing")
            self.assertTrue(any("document.json" in w for w in report["warnings"]))

    def test_tile_size_mismatch_warns_without_claiming_refusal(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "sizes.joybrush")
            _write_zip(
                path,
                [
                    ("mimetype", JB_MIMETYPE, "stored"),
                    ("document.json", _doc(), "deflated"),
                    ("layers/L1/c1/0_0.rgba", b"x" * 100, "deflated"),
                    ("layers/L1/c1/0_0.p0.f16", b"y" * 10, "deflated"),
                ],
            )
            report = _run_ok(path)
            size_warnings = [w for w in report["warnings"] if "disagreement" in w]
            self.assertEqual(len(size_warnings), 2)
            self.assertTrue(all("not a claim" in w for w in size_warnings))
            self.assertTrue(all("inspection warning only" in w for w in size_warnings))
            blob = json.dumps(report).lower()
            # The disclaimer itself contains "refuses" ("not a claim the app
            # loader refuses it"); ensure no positive refusal claim remains
            # once the explicit disclaimer is removed.
            stripped = blob.replace("not a claim the app loader refuses it", "")
            self.assertNotIn("refuse", stripped)

    def test_corrupt_zip_is_actionable_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "corrupt.joybrush")
            with open(path, "wb") as handle:
                handle.write(os.urandom(256))
            proc = _run_cli(path)
            self.assertNotEqual(proc.returncode, 0)
            combined = (proc.stdout + proc.stderr).lower()
            self.assertTrue(
                "zip" in combined or "truncat" in combined or "copy" in combined,
                "no actionable hint: %r" % combined[-500:],
            )
            # Error output is still JSON with an actionable hint.
            parsed = json.loads(proc.stdout)
            self.assertIn("error", parsed)
            self.assertIn("hint", parsed)

    def test_missing_path_is_actionable_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = _run_cli(os.path.join(tmp, "nothing.joybrush"))
            self.assertNotEqual(proc.returncode, 0)
            parsed = json.loads(proc.stdout)
            self.assertIn("no file at", parsed["error"])
            self.assertIn("hint", parsed)

    def test_no_extraction_and_bytes_unchanged(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "pristine.joybrush")
            _write_zip(path, _good_entries())
            with open(path, "rb") as handle:
                before = handle.read()
            digest_before = hashlib.sha256(before).hexdigest()
            stat_before = os.stat(path)
            listed_before = sorted(os.listdir(tmp))
            report = _run_ok(path)
            self.assertEqual(report["zip"]["entry_count"], 7)
            with open(path, "rb") as handle:
                after = handle.read()
            self.assertEqual(hashlib.sha256(after).hexdigest(), digest_before)
            self.assertEqual(len(after), len(before))
            stat_after = os.stat(path)
            self.assertEqual(stat_after.st_size, stat_before.st_size)
            # Nothing extracted beside the archive: no new files, no sidecars.
            self.assertEqual(sorted(os.listdir(tmp)), listed_before)

    def test_valid_json_cli_smoke(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "smoke.joybrush")
            _write_zip(path, _good_entries())
            proc = _run_cli(path)
            self.assertEqual(proc.returncode, 0)
            report = json.loads(proc.stdout)  # raises if stdout is not valid JSON
            for key in ("tool", "inspection_only", "note", "zip", "recognized",
                        "document", "warnings"):
                self.assertIn(key, report)
            self.assertEqual(report["tool"], "jb_archive_report")
            self.assertTrue(report["inspection_only"])
            self.assertIn("total_declared_file_bytes", report["zip"])
            self.assertIn("counts_by_suffix", report["zip"])

    def test_windows_dotspace_trick_flagged(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "trick.joybrush")
            _write_zip(
                path,
                [
                    ("mimetype", JB_MIMETYPE, "stored"),
                    ("document.json", _doc(), "deflated"),
                    ("layers/.../escape", b"x", "deflated"),
                    ("layers/ .. /escape", b"x", "deflated"),
                ],
            )
            report = _run_ok(path)
            flagged = {item["name"] for item in report["zip"]["suspicious_names"]}
            self.assertIn("layers/.../escape", flagged)
            self.assertIn("layers/ .. /escape", flagged)


if __name__ == "__main__":
    unittest.main()
