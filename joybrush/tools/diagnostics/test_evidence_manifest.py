#!/usr/bin/env python3
"""Tests for evidence_manifest.py: reproducible evidence-bundle manifests.

Covers: empty file with its known SHA256, multi-chunk binary content,
bounded streaming reads via an injected open seam, duplicate basenames
kept distinct by full path, missing and directory inputs rejected without
any recursive search, file-change detection via deterministic injected
filesystem seams (mtime drift and short read), symlink inputs listed but
never followed (seam plus a real-link case where the platform allows it),
verbatim input recording without redaction guesses, metadata echo, usage
errors, read-only behaviour (input bytes bit-identical afterwards), and
the collection-only rule that output never claims results were checked
merely because digests exist. Standard library only.
"""

import hashlib
import io
import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock

import evidence_manifest
from evidence_manifest import collect_manifest, collect_one

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "evidence_manifest.py")

EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()


def _write(path, data):
    with open(path, "wb") as handle:
        handle.write(data)


def _pattern(n, seed=11):
    return bytes((i * 37 + seed) & 0xFF for i in range(n))


def _run_cli(*args):
    return subprocess.run(
        [sys.executable, SCRIPT] + list(args),
        capture_output=True,
        text=True,
        timeout=120,
    )


def _run_ok(*args):
    proc = _run_cli(*args)
    assert proc.returncode == 0, "CLI failed: rc=%d stderr=%s stdout=%s" % (
        proc.returncode,
        proc.stderr[-2000:],
        proc.stdout[-2000:],
    )
    return json.loads(proc.stdout)


class _RecordingReader(io.BytesIO):
    """BytesIO that records every requested read size."""

    def __init__(self, data):
        super().__init__(data)
        self.sizes = []

    def read(self, size=-1):
        self.sizes.append(size)
        return super().read(size)


class EvidenceManifestTest(unittest.TestCase):
    def test_empty_file_known_digest(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "empty.bin")
            _write(path, b"")
            report = _run_ok("--job", "JB-NOW-29", "--base", "abc123", path)
            self.assertTrue(report["manifest_complete"])
            self.assertEqual(report["problem_count"], 0)
            (entry,) = report["artifacts"]
            self.assertEqual(entry["input"], path)
            self.assertEqual(entry["status"], "collected")
            self.assertEqual(entry["byte_size"], 0)
            self.assertEqual(entry["sha256"], EMPTY_SHA256)
            self.assertTrue(entry["observed_at_utc"].endswith("Z"))

    def test_binary_content_exact_digest_across_chunks(self):
        size = 3 * evidence_manifest._CHUNK + 997
        data = _pattern(size)
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "binary.apk")
            _write(path, data)
            report = _run_ok("--job", "JB-NOW-29", "--base", "abc123", path)
            (entry,) = report["artifacts"]
            self.assertEqual(entry["status"], "collected")
            self.assertEqual(entry["byte_size"], size)
            self.assertEqual(entry["sha256"], hashlib.sha256(data).hexdigest())

    def test_reads_use_bounded_chunks_via_open_seam(self):
        size = 2 * evidence_manifest._CHUNK + 13
        fake = _pattern(size, seed=77)
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "big.log")
            _write(path, b"R" * size)  # real stat says size; content comes from the seam
            reader = _RecordingReader(fake)
            with mock.patch.object(evidence_manifest, "_open_binary", return_value=reader):
                entry = collect_one(path)
            self.assertEqual(entry["status"], "collected")
            self.assertEqual(entry["sha256"], hashlib.sha256(fake).hexdigest())
            self.assertEqual(entry["byte_size"], size)
            self.assertTrue(reader.sizes, "no reads were issued")
            self.assertLessEqual(max(reader.sizes), evidence_manifest._CHUNK)

    def test_chunk_bound_is_sane(self):
        self.assertGreater(evidence_manifest._CHUNK, 0)
        self.assertLessEqual(evidence_manifest._CHUNK, 1024 * 1024)

    def test_duplicate_basenames_kept_by_full_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            first_dir = os.path.join(tmp, "run-a")
            second_dir = os.path.join(tmp, "run-b")
            os.mkdir(first_dir)
            os.mkdir(second_dir)
            first = os.path.join(first_dir, "log.txt")
            second = os.path.join(second_dir, "log.txt")
            _write(first, b"first run")
            _write(second, b"second run, different bytes")
            report = _run_ok("--job", "JB-NOW-29", "--base", "abc123", first, second)
            self.assertEqual([a["input"] for a in report["artifacts"]], [first, second])
            self.assertEqual([a["status"] for a in report["artifacts"]],
                             ["collected", "collected"])
            self.assertNotEqual(report["artifacts"][0]["sha256"],
                                report["artifacts"][1]["sha256"])
            # The same path twice is kept as two entries, never deduped.
            again = _run_ok("--job", "JB-NOW-29", "--base", "abc123", first, first)
            self.assertEqual(len(again["artifacts"]), 2)
            self.assertEqual(again["artifacts"][0]["sha256"],
                             again["artifacts"][1]["sha256"])

    def test_missing_path_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            missing = os.path.join(tmp, "nope.bin")
            proc = _run_cli("--job", "JB-NOW-29", "--base", "abc123", missing)
            self.assertNotEqual(proc.returncode, 0)
            report = json.loads(proc.stdout)
            self.assertFalse(report["manifest_complete"])
            self.assertEqual(report["problem_count"], 1)
            (entry,) = report["artifacts"]
            self.assertEqual(entry["status"], "missing")
            self.assertIsNone(entry["sha256"])
            self.assertIsNone(entry["byte_size"])
            self.assertIn("no file", entry["detail"])

    def test_directory_rejected_and_never_searched(self):
        with tempfile.TemporaryDirectory() as tmp:
            sub = os.path.join(tmp, "logs")
            os.mkdir(sub)
            _write(os.path.join(sub, "inner.txt"), b"must not be listed")
            _write(os.path.join(sub, "other.txt"), b"must not be listed either")
            proc = _run_cli("--job", "JB-NOW-29", "--base", "abc123", sub)
            self.assertNotEqual(proc.returncode, 0)
            report = json.loads(proc.stdout)
            self.assertEqual(len(report["artifacts"]), 1)
            self.assertEqual(report["artifacts"][0]["status"], "is_directory")
            self.assertIsNone(report["artifacts"][0]["sha256"])
            blob = json.dumps(report)
            self.assertNotIn("inner.txt", blob)
            self.assertNotIn("other.txt", blob)

    def test_mixed_good_and_bad_inputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            good = os.path.join(tmp, "good.bin")
            _write(good, b"fine")
            missing = os.path.join(tmp, "gone.bin")
            proc = _run_cli("--job", "JB-NOW-29", "--base", "abc123", good, missing)
            self.assertNotEqual(proc.returncode, 0)
            report = json.loads(proc.stdout)
            self.assertFalse(report["manifest_complete"])
            self.assertEqual(report["problem_count"], 1)
            self.assertEqual(report["artifacts"][0]["status"], "collected")
            self.assertEqual(report["artifacts"][0]["sha256"],
                             hashlib.sha256(b"fine").hexdigest())
            self.assertEqual(report["artifacts"][1]["status"], "missing")

    def test_changed_file_detected_via_stat_seam(self):
        # Deterministic mtime drift: pre-read stat is real, post-read stat
        # is the same file with the clock moved one nanosecond forward.
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "race.bin")
            data = b"0123456789abcdef" * 64
            _write(path, data)
            pre = os.stat(path)
            post = SimpleNamespace(st_mode=pre.st_mode,
                                   st_size=pre.st_size,
                                   st_mtime_ns=pre.st_mtime_ns + 1)
            with mock.patch.object(evidence_manifest, "_stat_path",
                                   side_effect=[pre, post]):
                entry = collect_one(path)
            self.assertEqual(entry["status"], "changed_during_read")
            self.assertEqual(entry["sha256"], hashlib.sha256(data).hexdigest())
            self.assertEqual(entry["byte_size"], len(data))
            self.assertIn("mtime moved: yes", entry["detail"])

    def test_short_read_detected_via_open_seam(self):
        # Deterministic truncation: stat sees the full file, the seam
        # hands back fewer bytes than the pre-read size.
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "cut.bin")
            _write(path, b"F" * 4096)
            with mock.patch.object(evidence_manifest, "_open_binary",
                                   return_value=io.BytesIO(b"short")):
                entry = collect_one(path)
            self.assertEqual(entry["status"], "changed_during_read")
            self.assertEqual(entry["byte_size"], 5)
            self.assertEqual(entry["sha256"], hashlib.sha256(b"short").hexdigest())

    def test_symlink_listed_never_followed_via_seam(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "link.bin")
            _write(path, b"real bytes stay unopened")
            fake_lstat = SimpleNamespace(st_mode=stat.S_IFLNK | 0o777)
            opener = mock.Mock(side_effect=AssertionError("links must never be opened"))
            with mock.patch.object(evidence_manifest, "_lstat_path",
                                   return_value=fake_lstat), \
                 mock.patch.object(evidence_manifest, "_read_link",
                                   return_value="elsewhere/target.bin"), \
                 mock.patch.object(evidence_manifest, "_open_binary", opener):
                entry = collect_one(path)
            self.assertEqual(entry["status"], "symlink")
            self.assertIsNone(entry["sha256"])
            self.assertIsNone(entry["byte_size"])
            self.assertEqual(entry["link_target"], "elsewhere/target.bin")
            self.assertEqual(opener.call_count, 0)

    def test_real_symlink_listed_when_supported(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = os.path.join(tmp, "target.bin")
            _write(target, b"payload")
            link = os.path.join(tmp, "link.bin")
            try:
                os.symlink("target.bin", link)
            except (OSError, NotImplementedError, AttributeError) as exc:
                self.skipTest("symlinks unavailable: %s" % exc)
            proc = _run_cli("--job", "JB-NOW-29", "--base", "abc123", link)
            self.assertNotEqual(proc.returncode, 0)
            report = json.loads(proc.stdout)
            (entry,) = report["artifacts"]
            self.assertEqual(entry["status"], "symlink")
            self.assertIsNone(entry["sha256"])
            with open(target, "rb") as handle:
                self.assertEqual(handle.read(), b"payload")

    def test_output_claims_collection_not_verification(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "a.bin")
            _write(path, b"bytes")
            report = _run_ok("--job", "JB-NOW-29", "--base", "abc123", path)
            self.assertNotIn("verif", json.dumps(report).lower())
            self.assertTrue(report["collection_only"])
            self.assertIn("collection", report["note"].lower())
            # Error manifests stay collection-only too.
            proc = _run_cli("--job", "JB-NOW-29", "--base", "abc123",
                            os.path.join(tmp, "absent.bin"))
            self.assertNotIn("verif", proc.stdout.lower())
            problem = json.loads(proc.stdout)
            self.assertTrue(problem["collection_only"])

    def test_metadata_echoed_optionals_default_null(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "a.bin")
            _write(path, b"x")
            report = _run_ok("--job", "JB-NOW-29", "--base", "deadbee", path)
            self.assertEqual(report["job"], "JB-NOW-29")
            self.assertEqual(report["base"], "deadbee")
            self.assertIsNone(report["device_serial"])
            self.assertIsNone(report["command"])
            full = _run_ok("--job", "JB-NOW-29", "--commit", "c0ffee",
                           "--device-serial", "S123",
                           "--command", "make check", path)
            self.assertEqual(full["base"], "c0ffee")
            self.assertEqual(full["device_serial"], "S123")
            self.assertEqual(full["command"], "make check")

    def test_inputs_recorded_verbatim(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "odd name.bin")
            _write(path, b"data")
            report = _run_ok("--job", "JB-NOW-29", "--base", "abc123", path)
            self.assertEqual(report["artifacts"][0]["input"], path)

    def test_usage_errors(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "a.bin")
            _write(path, b"x")
            self.assertEqual(
                _run_cli("--job", "JB-NOW-29", "--base", "b").returncode, 2)
            self.assertEqual(_run_cli(path).returncode, 2)

    def test_collect_manifest_api_exit_codes(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "a.bin")
            _write(path, b"x")
            report, code = collect_manifest("JB-NOW-29", "abc123", [path])
            self.assertEqual(code, 0)
            self.assertTrue(report["manifest_complete"])
            bad_report, bad_code = collect_manifest(
                "JB-NOW-29", "abc123", [os.path.join(tmp, "gone.bin")])
            self.assertEqual(bad_code, 1)
            self.assertFalse(bad_report["manifest_complete"])

    def test_input_bytes_unchanged_after_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "pristine.bin")
            data = _pattern(4096)
            _write(path, data)
            listed_before = sorted(os.listdir(tmp))
            report = _run_ok("--job", "JB-NOW-29", "--base", "abc123", path)
            self.assertEqual(report["artifact_count"], 1)
            with open(path, "rb") as handle:
                self.assertEqual(handle.read(), data)
            # Nothing written beside the input: no sidecars, no walking.
            self.assertEqual(sorted(os.listdir(tmp)), listed_before)


if __name__ == "__main__":
    unittest.main()
