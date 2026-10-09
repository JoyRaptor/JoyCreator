#!/usr/bin/env python3
"""JB-NOW-05 unittest for media_memory_report.py.

Every log line below is a SYNTHETIC fixture written to temp files at test
time. Values are invented examples shaped like the real producer strings;
they are NOT measurements from a physical device and must never be cited
as device evidence. The coordinator runs this suite (no device, network,
adb, or Gradle involved)::

    python -m unittest discover -s joybrush/tools/diagnostics -p test_media_memory_report.py
"""

import json
import os
import subprocess
import sys
import tempfile
import unittest

import media_memory_report as mmr

MMR_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "media_memory_report.py")

# SYNTHETIC realistic logcat prefixes (shape only, not device output).
SYNTHETIC_THREADTIME = "10-09 12:34:56.789  1234  5678 W JoyBrushMediaMemory: "
SYNTHETIC_BRIEF = "W/JoyBrushMediaMemory( 1234): "
SYNTHETIC_BARE = "JoyBrushMediaMemory: "

# SYNTHETIC bodies shaped like the three real producer formats
# (JbCanvasView.kt ui gate; MediaCanvas.kt gl-cold / gl-water gates).
SYNTHETIC_UI = "refused stage=ui wet=true available=524288000 threshold=209715200 held=0"
SYNTHETIC_GL_COLD = (
    "refused stage=gl-cold wet=false available=603979776 "
    "threshold=201326592 asleep=false"
)
SYNTHETIC_GL_COLD_NULL = (
    "refused stage=gl-cold wet=true available=603979776 "
    "threshold=201326592 asleep=null"
)
SYNTHETIC_GL_WATER = (
    "refused stage=gl-water available=603979776 threshold=201326592 held=134217728"
)


def run_cli(*argv):
    return subprocess.run(
        [sys.executable, MMR_PATH] + list(argv),
        capture_output=True,
        text=True,
        timeout=60,
    )


class MediaMemoryReportTest(unittest.TestCase):
    def make_synthetic_log(self, lines, newline="\n"):
        """Write SYNTHETIC lines to a temp file; returns its path (auto-removed)."""
        fd, path = tempfile.mkstemp(prefix="synthetic_memlog_", suffix=".txt")
        with os.fdopen(fd, "w", encoding="utf-8", newline=newline) as handle:
            for line in lines:
                handle.write(line + "\n")
        self.addCleanup(os.unlink, path)
        return path

    def load_synthetic(self, lines):
        return mmr.load_report(self.make_synthetic_log(lines))

    # -- the three real producer formats --------------------------------

    def test_ui_producer_format(self):
        report = self.load_synthetic([SYNTHETIC_THREADTIME + SYNTHETIC_UI])
        self.assertEqual(report["events_observed"], 1)
        event = report["events"][0]
        self.assertEqual(event["line"], 1)
        self.assertEqual(event["text"], SYNTHETIC_THREADTIME + SYNTHETIC_UI)
        self.assertEqual(event["stage"], "ui")
        self.assertIs(event["wet"], True)
        self.assertEqual(event["available"], 524288000)
        self.assertEqual(event["threshold"], 209715200)
        self.assertEqual(event["held"], 0)
        self.assertIsNone(event["asleep"])
        self.assertEqual(event["extras"], {})
        self.assertEqual(report["by_stage"], {"ui": 1})
        self.assertEqual(report["warnings"], [])

    def test_gl_cold_producer_format(self):
        report = self.load_synthetic([SYNTHETIC_THREADTIME + SYNTHETIC_GL_COLD])
        event = report["events"][0]
        self.assertEqual(event["stage"], "gl-cold")
        self.assertIs(event["wet"], False)
        self.assertEqual(event["available"], 603979776)
        self.assertEqual(event["threshold"], 201326592)
        # gl-cold carries no held token: unknown, never inferred, no warning.
        self.assertIsNone(event["held"])
        self.assertIs(event["asleep"], False)
        self.assertEqual(report["by_stage"], {"gl-cold": 1})
        self.assertEqual(report["warnings"], [])

    def test_gl_cold_null_asleep_keeps_null_distinct(self):
        # asleep=null is the real MediaCanvas output when no engine exists
        # yet (Kotlin nullable); null stays distinct from true/false.
        report = self.load_synthetic([SYNTHETIC_THREADTIME + SYNTHETIC_GL_COLD_NULL])
        event = report["events"][0]
        self.assertIs(event["wet"], True)
        self.assertIsNone(event["asleep"])
        self.assertIn("asleep=null", event["text"])
        self.assertEqual(report["warnings"], [])

    def test_gl_water_producer_format(self):
        report = self.load_synthetic([SYNTHETIC_THREADTIME + SYNTHETIC_GL_WATER])
        event = report["events"][0]
        self.assertEqual(event["stage"], "gl-water")
        # gl-water carries neither wet nor asleep by design.
        self.assertIsNone(event["wet"])
        self.assertIsNone(event["asleep"])
        self.assertEqual(event["held"], 134217728)
        self.assertEqual(report["by_stage"], {"gl-water": 1})
        self.assertEqual(report["warnings"], [])

    # -- prefixes, filtering --------------------------------------------

    def test_realistic_logcat_prefixes(self):
        lines = [
            SYNTHETIC_THREADTIME + SYNTHETIC_UI,
            SYNTHETIC_BRIEF + SYNTHETIC_GL_COLD,
            SYNTHETIC_BARE + SYNTHETIC_GL_WATER,
            SYNTHETIC_BARE + SYNTHETIC_GL_COLD,
        ]
        report = self.load_synthetic(lines)
        self.assertEqual(report["events_observed"], 4)
        self.assertEqual(report["by_stage"], {"ui": 1, "gl-cold": 2, "gl-water": 1})
        self.assertEqual([e["line"] for e in report["events"]], [1, 2, 3, 4])
        self.assertEqual(report["warnings"], [])

    def test_unrelated_tags_ignored(self):
        lines = [
            # SYNTHETIC non-matching lines: other tag, missing marker, noise.
            "10-09 12:34:56.789  1234  5678 W SomeOtherTag: refused stage=ui "
            "wet=true available=1 threshold=2 held=3",
            "10-09 12:34:56.789  1234  5678 W JoyBrushMedia: refused stage=ui "
            "wet=true available=1 threshold=2 held=3",
            SYNTHETIC_THREADTIME + "window placed over pen",
            "system boot completed",
            "",
        ]
        report = self.load_synthetic(lines)
        self.assertEqual(report["events_observed"], 0)
        self.assertEqual(report["events"], [])
        self.assertEqual(report["by_stage"], {})
        self.assertEqual(report["warnings"], [])
        self.assertEqual(report["input"]["lines_total"], 5)
        self.assertEqual(report["input"]["lines_consumed"], 0)

    def test_tag_token_boundary(self):
        # SYNTHETIC lookalike tag must not match the whole-token tag rule.
        report = self.load_synthetic(
            ["10-09 12:34:56.789  1234  5678 W JoyBrushMediaMemoryX: refused stage=ui "
             "wet=true available=1 threshold=2 held=3"]
        )
        self.assertEqual(report["events_observed"], 0)

    def test_message_mention_bare_other_tag_rejected(self):
        # SYNTHETIC: another tag's message merely mentions our tag token.
        # Contract consumes ONLY lines TAGGED JoyBrushMediaMemory.
        lines = [
            "W OtherTag: JoyBrushMediaMemory refused stage=ui "
            "wet=true available=1 threshold=2 held=3",
            "SomeOtherTag: refused stage=ui wet=true available=1 threshold=2 "
            "held=3 JoyBrushMediaMemory",
        ]
        for line in lines:
            with self.subTest(line=line):
                report = self.load_synthetic([line])
                self.assertEqual(report["events_observed"], 0)
                self.assertEqual(report["events"], [])
                self.assertEqual(report["input"]["lines_consumed"], 0)

    def test_threadtime_other_tag_message_mention_rejected(self):
        # SYNTHETIC threadtime lines tagged OtherTag whose message mentions
        # JoyBrushMediaMemory (before and after the refused payload).
        lines = [
            "10-09 12:34:56.789  1234  5678 W OtherTag: JoyBrushMediaMemory "
            "refused stage=ui wet=true available=1 threshold=2 held=3",
            "10-09 12:34:56.789  1234  5678 W OtherTag: refused stage=ui "
            "wet=true available=1 threshold=2 held=3 JoyBrushMediaMemory",
        ]
        for line in lines:
            with self.subTest(line=line):
                report = self.load_synthetic([line])
                self.assertEqual(report["events_observed"], 0)
                self.assertEqual(report["input"]["lines_consumed"], 0)

    def test_brief_other_tag_message_mention_rejected(self):
        # SYNTHETIC brief lines tagged OtherTag whose message mentions our tag.
        lines = [
            "W/OtherTag( 1234): JoyBrushMediaMemory refused stage=ui "
            "wet=true available=1 threshold=2 held=3",
            "W/OtherTag( 1234): refused stage=ui wet=true available=1 "
            "threshold=2 held=3 JoyBrushMediaMemory",
        ]
        for line in lines:
            with self.subTest(line=line):
                report = self.load_synthetic([line])
                self.assertEqual(report["events_observed"], 0)
                self.assertEqual(report["input"]["lines_consumed"], 0)

    def test_lookalike_tags_all_positions_rejected(self):
        # SYNTHETIC lookalike tags at each real position must not match.
        lines = [
            "JoyBrushMediaMemoryX: refused stage=ui wet=true available=1 "
            "threshold=2 held=3",
            "W/JoyBrushMediaMemoryX( 1234): refused stage=ui wet=true "
            "available=1 threshold=2 held=3",
            "10-09 12:34:56.789  1234  5678 W JoyBrushMediaMemory2: refused "
            "stage=ui wet=true available=1 threshold=2 held=3",
            "10-09 12:34:56.789  1234  5678 W MyJoyBrushMediaMemory: refused "
            "stage=ui wet=true available=1 threshold=2 held=3",
        ]
        for line in lines:
            with self.subTest(line=line):
                report = self.load_synthetic([line])
                self.assertEqual(report["events_observed"], 0)
                self.assertEqual(report["input"]["lines_consumed"], 0)

    def test_malformed_prefixes_rejected(self):
        # SYNTHETIC malformed prefixes are not real tag positions and must
        # not be attributed to JoyBrushMediaMemory.
        lines = [
            # Bare without colon.
            "JoyBrushMediaMemory refused stage=ui wet=true available=1 "
            "threshold=2 held=3",
            # Brief without (PID).
            "W/JoyBrushMediaMemory: refused stage=ui wet=true available=1 "
            "threshold=2 held=3",
            # Brief with non-numeric PID.
            "W/JoyBrushMediaMemory(abc): refused stage=ui wet=true "
            "available=1 threshold=2 held=3",
            # Threadtime without colon after tag.
            "10-09 12:34:56.789  1234  5678 W JoyBrushMediaMemory refused "
            "stage=ui wet=true available=1 threshold=2 held=3",
            # Truncated threadtime (missing seconds/millis/TID).
            "10-09 12:34 W JoyBrushMediaMemory: refused stage=ui wet=true "
            "available=1 threshold=2 held=3",
        ]
        for line in lines:
            with self.subTest(line=line):
                report = self.load_synthetic([line])
                self.assertEqual(report["events_observed"], 0)
                self.assertEqual(report["input"]["lines_consumed"], 0)

    def test_producer_prefix_variations_accepted(self):
        # SYNTHETIC legitimate threadtime/brief/bare variations carrying the
        # real producer bodies (shapes from JbCanvasView.kt / MediaCanvas.kt).
        lines = [
            "01-02 03:04:05.006  999  888 W JoyBrushMediaMemory: " + SYNTHETIC_UI,
            "12-31 23:59:59.999 1 2 W JoyBrushMediaMemory: " + SYNTHETIC_GL_COLD,
            "W/JoyBrushMediaMemory(999): " + SYNTHETIC_GL_WATER,
            "W/JoyBrushMediaMemory(  42): " + SYNTHETIC_GL_COLD_NULL,
            SYNTHETIC_BARE + SYNTHETIC_UI,
        ]
        report = self.load_synthetic(lines)
        self.assertEqual(report["events_observed"], 5)
        self.assertEqual(
            [e["stage"] for e in report["events"]],
            ["ui", "gl-cold", "gl-water", "gl-cold", "ui"],
        )
        self.assertEqual([e["line"] for e in report["events"]], [1, 2, 3, 4, 5])
        self.assertEqual(
            report["by_stage"], {"ui": 2, "gl-cold": 2, "gl-water": 1}
        )
        self.assertEqual(report["warnings"], [])
        # Prefix must not leak into parsing; original text preserved.
        for original, event in zip(lines, report["events"]):
            self.assertEqual(event["text"], original)

    # -- missing / malformed fields -------------------------------------

    def test_missing_numeric_fields_warn_as_unknown(self):
        report = self.load_synthetic([SYNTHETIC_BARE + "refused stage=ui wet=true"])
        event = report["events"][0]
        self.assertIsNone(event["available"])
        self.assertIsNone(event["threshold"])
        self.assertIsNone(event["held"])
        fields = sorted(w["field"] for w in report["warnings"])
        self.assertEqual(fields, ["available", "threshold"])

    def test_missing_stage_groups_unknown(self):
        report = self.load_synthetic(
            [SYNTHETIC_BARE + "refused wet=true available=100 threshold=200 held=0"]
        )
        event = report["events"][0]
        self.assertIsNone(event["stage"])
        self.assertEqual(report["by_stage"], {"unknown": 1})
        self.assertEqual([w["field"] for w in report["warnings"]], ["stage"])

    def test_malformed_numbers_warn_as_unknown(self):
        report = self.load_synthetic(
            [SYNTHETIC_BARE + "refused stage=ui wet=true available=abc threshold=12.5 held="]
        )
        event = report["events"][0]
        # Unknown is None, never zero.
        self.assertIsNone(event["available"])
        self.assertIsNone(event["threshold"])
        self.assertIsNone(event["held"])
        self.assertNotEqual(event["available"], 0)
        fields = sorted(w["field"] for w in report["warnings"])
        self.assertEqual(fields, ["available", "held", "threshold"])

    def test_negative_numbers_rejected_not_zero(self):
        report = self.load_synthetic(
            [SYNTHETIC_BARE + "refused stage=gl-water available=-1 threshold=-9223372036854775808 held=-0"]
        )
        event = report["events"][0]
        self.assertIsNone(event["available"])
        self.assertIsNone(event["threshold"])
        self.assertIsNone(event["held"])
        fields = sorted(w["field"] for w in report["warnings"])
        self.assertEqual(fields, ["available", "held", "threshold"])

    def test_zero_bytes_are_valid(self):
        report = self.load_synthetic(
            [SYNTHETIC_BARE + "refused stage=ui wet=false available=0 threshold=0 held=0"]
        )
        event = report["events"][0]
        self.assertEqual(event["available"], 0)
        self.assertEqual(event["threshold"], 0)
        self.assertEqual(event["held"], 0)
        self.assertEqual(report["warnings"], [])

    def test_invalid_booleans_warn(self):
        report = self.load_synthetic(
            [SYNTHETIC_BARE + "refused stage=ui wet=maybe available=100 threshold=200 held=0"]
        )
        self.assertIsNone(report["events"][0]["wet"])
        self.assertEqual([w["field"] for w in report["warnings"]], ["wet"])

        report = self.load_synthetic(
            [SYNTHETIC_BARE + "refused stage=gl-cold wet=true available=100 threshold=200 asleep=1"]
        )
        self.assertIsNone(report["events"][0]["asleep"])
        self.assertEqual([w["field"] for w in report["warnings"]], ["asleep"])

    def test_explicit_null_boolean_needs_no_warning(self):
        report = self.load_synthetic(
            [SYNTHETIC_BARE + "refused stage=gl-cold wet=null available=100 threshold=200 asleep=null"]
        )
        event = report["events"][0]
        self.assertIsNone(event["wet"])
        self.assertIsNone(event["asleep"])
        self.assertEqual(report["warnings"], [])

    # -- future-proofing, duplicates, line fidelity ----------------------

    def test_unknown_stage_and_tokens_preserved(self):
        line = (
            "refused stage=gl-future-9 wet=true available=100 threshold=200 "
            "held=0 budget=999 foo=bar"
        )
        report = self.load_synthetic([SYNTHETIC_THREADTIME + line])
        event = report["events"][0]
        self.assertEqual(event["stage"], "gl-future-9")
        self.assertEqual(event["extras"], {"budget": "999", "foo": "bar"})
        self.assertEqual(report["by_stage"], {"gl-future-9": 1})
        self.assertEqual(report["warnings"], [])

    def test_duplicate_events_preserved_with_line_numbers(self):
        line = SYNTHETIC_THREADTIME + SYNTHETIC_UI
        report = self.load_synthetic([line, "noise without marker", line])
        self.assertEqual(report["events_observed"], 2)
        self.assertEqual([e["line"] for e in report["events"]], [1, 3])
        self.assertEqual(report["events"][0]["text"], line)
        self.assertEqual(report["events"][1]["text"], line)
        self.assertEqual(report["by_stage"], {"ui": 2})

    def test_line_numbers_and_original_text_preserved(self):
        good_ui = SYNTHETIC_BRIEF + SYNTHETIC_UI
        good_water = SYNTHETIC_THREADTIME + SYNTHETIC_GL_WATER
        lines = [
            "10-09 12:34:56.789  1234  5678 W SomeOtherTag: refused stage=ui "
            "wet=true available=1 threshold=2 held=3",
            good_ui,
            SYNTHETIC_THREADTIME + "running water frame",
            good_water,
        ]
        report = self.load_synthetic(lines)
        self.assertEqual([e["line"] for e in report["events"]], [2, 4])
        self.assertEqual(report["events"][0]["text"], good_ui)
        self.assertEqual(report["events"][1]["text"], good_water)

    # -- empty input, bad path, CLI --------------------------------------

    def test_empty_input_reports_zero_not_proof(self):
        path = self.make_synthetic_log([])
        report = mmr.load_report(path)
        self.assertEqual(report["events_observed"], 0)
        self.assertEqual(report["events"], [])
        self.assertEqual(report["by_stage"], {})
        self.assertEqual(report["input"]["lines_total"], 0)
        self.assertEqual(report["input"]["lines_consumed"], 0)
        self.assertTrue(
            any("not proof" in note for note in report["notes"]),
            "empty input must disclaim proof of no memory problem",
        )

    def test_bad_input_path_raises(self):
        missing = os.path.join(tempfile.gettempdir(), "synthetic_memlog_missing_404.txt")
        if os.path.exists(missing):
            self.skipTest("synthetic missing-path fixture unexpectedly exists")
        with self.assertRaises(FileNotFoundError):
            mmr.load_report(missing)

    def test_cli_bad_path_fails(self):
        missing = os.path.join(tempfile.gettempdir(), "synthetic_memlog_missing_404.txt")
        proc = run_cli(missing)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("error", proc.stderr.lower())

    def test_cli_valid_json_provenance_and_deterministic(self):
        lines = [
            SYNTHETIC_THREADTIME + SYNTHETIC_UI,
            "unrelated line",
            SYNTHETIC_BRIEF + SYNTHETIC_GL_WATER,
        ]
        path = self.make_synthetic_log(lines)
        first = run_cli(path)
        second = run_cli(path)
        self.assertEqual(first.returncode, 0, msg=first.stderr)
        self.assertEqual(second.returncode, 0, msg=second.stderr)
        # Deterministic: byte-identical stdout across runs.
        self.assertEqual(first.stdout, second.stdout)
        report = json.loads(first.stdout)
        self.assertEqual(report["tool"], "media_memory_report")
        self.assertEqual(report["input"]["path"], path)
        self.assertEqual(report["input"]["encoding"], "utf-8")
        self.assertEqual(report["input"]["lines_total"], 3)
        self.assertEqual(report["input"]["lines_consumed"], 2)
        self.assertEqual(report["events_observed"], 2)
        self.assertEqual(report["by_stage"], {"gl-water": 1, "ui": 1})


if __name__ == "__main__":
    unittest.main()
