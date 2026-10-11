"""Synthetic-fixture tests for media_trace_report.py (JB-NOW-07).

All fixtures below are synthetic hand-written CSV strings for parser checks
only; they are NOT physical device evidence and make no phone/GPU claim.
"""

import csv
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPORT = Path(__file__).resolve().parent / "media_trace_report.py"

import importlib.util

_spec = importlib.util.spec_from_file_location("media_trace_report", str(REPORT))
assert _spec is not None and _spec.loader is not None
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

build_report = _mod.build_report
TraceReportError = _mod.TraceReportError


def _write_csv(text):
    tmp = tempfile.NamedTemporaryFile(
        mode="w", suffix=".csv", delete=False, encoding="utf-8", newline=""
    )
    try:
        tmp.write(text)
        tmp.flush()
        return Path(tmp.name)
    finally:
        tmp.close()


def _csv_text(header, rows):
    buf = io.StringIO()
    writer = csv.writer(buf, quoting=csv.QUOTE_MINIMAL)
    writer.writerow(header)
    for row in rows:
        writer.writerow(row)
    return buf.getvalue()


HEADER = ["name", "ts", "dur", "track_id"]


class OddMedianP95Test(unittest.TestCase):
    def test_odd_median_and_p95_hand_computed(self):
        # Synthetic: 3 slices of 1ms, 2ms, 3ms on one track.
        # Sorted ms: [1,2,3]; median=2; p95 rank=ceil(.95*3)=3 -> 3.
        text = _csv_text(
            HEADER,
            [
                ["JB.media.dry.apply", 0, 1_000_000, "7"],
                ["JB.media.dry.apply", 2_000_000, 3_000_000, "7"],
                ["JB.media.dry.apply", 6_000_000, 2_000_000, "7"],
            ],
        )
        path = _write_csv(text)
        try:
            report = build_report(path)
        finally:
            path.unlink()
        self.assertEqual(1, len(report["groups"]))
        g = report["groups"][0]
        self.assertEqual(("JB.media.dry.apply", "7"), (g["name"], g["track_id"]))
        self.assertEqual(3, g["count"])
        self.assertAlmostEqual(6.0, g["sum_ms"])
        self.assertAlmostEqual(1.0, g["min_ms"])
        self.assertAlmostEqual(2.0, g["median_ms"])
        self.assertAlmostEqual(3.0, g["p95_ms"])
        self.assertAlmostEqual(3.0, g["max_ms"])
        self.assertEqual(0, g["zero_duration_rows"])


class EvenMedianP95Test(unittest.TestCase):
    def test_even_median_and_p95_hand_computed(self):
        # Synthetic: 4 slices 1,2,3,4 ms. Median=(2+3)/2=2.5.
        # p95 rank=ceil(.95*4)=ceil(3.8)=4 -> 4ms. Sum=10ms.
        text = _csv_text(
            HEADER,
            [
                ["JB.media.dry.delta", 0, 4_000_000, "3"],
                ["JB.media.dry.delta", 5_000_000, 1_000_000, "3"],
                ["JB.media.dry.delta", 7_000_000, 3_000_000, "3"],
                ["JB.media.dry.delta", 11_000_000, 2_000_000, "3"],
            ],
        )
        path = _write_csv(text)
        try:
            report = build_report(path)
        finally:
            path.unlink()
        g = report["groups"][0]
        self.assertEqual(4, g["count"])
        self.assertAlmostEqual(10.0, g["sum_ms"])
        self.assertAlmostEqual(1.0, g["min_ms"])
        self.assertAlmostEqual(2.5, g["median_ms"])
        self.assertAlmostEqual(4.0, g["p95_ms"])
        self.assertAlmostEqual(4.0, g["max_ms"])

    def test_single_row_p95_is_max(self):
        text = _csv_text(HEADER, [["JB.media.look.render", 100, 2_500_000, "9"]])
        path = _write_csv(text)
        try:
            report = build_report(path)
        finally:
            path.unlink()
        g = report["groups"][0]
        self.assertEqual(1, g["count"])
        self.assertAlmostEqual(2.5, g["median_ms"])
        self.assertAlmostEqual(2.5, g["p95_ms"])


class MultiTrackGroupingTest(unittest.TestCase):
    def test_same_name_different_tracks_not_merged(self):
        text = _csv_text(
            HEADER,
            [
                ["JB.media.dry.upload", 0, 1_000_000, "1"],
                ["JB.media.dry.upload", 0, 5_000_000, "2"],
                ["JB.media.dry.upload", 2_000_000, 3_000_000, "1"],
            ],
        )
        path = _write_csv(text)
        try:
            report = build_report(path)
        finally:
            path.unlink()
        self.assertEqual(2, len(report["groups"]))
        by_track = {(g["name"], g["track_id"]): g for g in report["groups"]}
        self.assertEqual(2, by_track[("JB.media.dry.upload", "1")]["count"])
        self.assertAlmostEqual(4.0, by_track[("JB.media.dry.upload", "1")]["sum_ms"])
        self.assertEqual(1, by_track[("JB.media.dry.upload", "2")]["count"])
        self.assertAlmostEqual(5.0, by_track[("JB.media.dry.upload", "2")]["sum_ms"])


class RealLabelsTest(unittest.TestCase):
    def test_all_five_producer_labels_grouped(self):
        # Synthetic rows using the actual MediaLayerEngine.kt label strings.
        rows = [
            ["JB.media.dry.upload", 0, 1_000_000, "1"],
            ["JB.media.dry.delta", 2_000_000, 2_000_000, "1"],
            ["JB.media.dry.apply", 4_000_000, 3_000_000, "1"],
            ["JB.media.dry.copy", 8_000_000, 4_000_000, "1"],
            ["JB.media.look.render", 13_000_000, 5_000_000, "1"],
        ]
        path = _write_csv(_csv_text(HEADER, rows))
        try:
            report = build_report(path)
        finally:
            path.unlink()
        self.assertEqual(5, len(report["groups"]))
        self.assertEqual([], report["unknown_media_labels"])
        self.assertTrue(report["has_media_evidence"])


class OverlappingSlicesTest(unittest.TestCase):
    def test_overlapping_slices_counted_without_frame_total(self):
        # Synthetic overlapping slices on the same track: [0,10ms] and [5ms,8ms].
        text = _csv_text(
            HEADER,
            [
                ["JB.media.dry.apply", 0, 10_000_000, "1"],
                ["JB.media.dry.apply", 5_000_000, 3_000_000, "1"],
            ],
        )
        path = _write_csv(text)
        try:
            report = build_report(path)
        finally:
            path.unlink()
        g = report["groups"][0]
        self.assertEqual(2, g["count"])
        self.assertAlmostEqual(13.0, g["sum_ms"])  # per-slice sum, not span
        self.assertIsNone(report["frame_total_ms"])
        self.assertIsNone(report["input_to_visible_latency_ms"])
        self.assertTrue(any("never" in w and "frame total" in w for w in report["warnings"]))


class ZeroNegativeTest(unittest.TestCase):
    def test_zero_included_negative_excluded(self):
        text = _csv_text(
            HEADER,
            [
                ["JB.media.dry.copy", 0, 0, "1"],  # zero: valid
                ["JB.media.dry.copy", 1_000_000, 2_000_000, "1"],
                ["JB.media.dry.copy", 4_000_000, -1, "1"],  # unfinished
                ["JB.media.dry.copy", 5_000_000, -500, "1"],  # negative
            ],
        )
        path = _write_csv(text)
        try:
            report = build_report(path)
        finally:
            path.unlink()
        g = report["groups"][0]
        self.assertEqual(2, g["count"])
        self.assertEqual(1, g["zero_duration_rows"])
        self.assertAlmostEqual(0.0, g["min_ms"])
        self.assertAlmostEqual(2.0, g["max_ms"])
        self.assertEqual(1, report["totals"]["zero_duration_rows"])
        self.assertEqual(2, report["totals"]["negative_duration_rows"])
        self.assertEqual(2, len(report["negative_duration_rows"]))
        self.assertIn(-1, [r["dur_ns"] for r in report["negative_duration_rows"]])


class InvalidMalformedTest(unittest.TestCase):
    def test_invalid_fields_excluded(self):
        text = _csv_text(
            HEADER,
            [
                ["JB.media.dry.apply", 0, 1_000_000, "1"],  # valid
                ["JB.media.dry.apply", "abc", 1_000_000, "1"],  # bad ts
                ["JB.media.dry.apply", 2_000_000, "1.5", "1"],  # float dur
                ["JB.media.dry.apply", 3_000_000, "", "1"],  # empty dur
                ["JB.media.dry.apply", -5, 1_000_000, "1"],  # negative ts
                ["", 4_000_000, 1_000_000, "1"],  # empty name
                ["JB.media.dry.apply", 5_000_000, 1_000_000, ""],  # empty track
            ],
        )
        path = _write_csv(text)
        try:
            report = build_report(path)
        finally:
            path.unlink()
        self.assertEqual(1, report["totals"]["valid_media_rows"])
        self.assertEqual(6, report["totals"]["invalid_rows"])
        self.assertEqual(6, len(report["invalid_rows"]))
        self.assertEqual(1, len(report["groups"]))

    def test_malformed_short_and_long_rows(self):
        text = (
            "name,ts,dur,track_id\n"
            "JB.media.dry.apply,0,1000000,1\n"
            "JB.media.dry.apply,2000000\n"  # short row
            "JB.media.dry.apply,3000000,1000000,1,EXTRA\n"  # long row
        )
        path = _write_csv(text)
        try:
            report = build_report(path)
        finally:
            path.unlink()
        self.assertEqual(1, report["totals"]["valid_media_rows"])
        self.assertEqual(2, report["totals"]["malformed_rows"])


class MissingColumnsTest(unittest.TestCase):
    def test_missing_dur_column_raises(self):
        path = _write_csv(_csv_text(["name", "ts", "track_id"], [["a", 0, "1"]]))
        try:
            with self.assertRaises(TraceReportError):
                build_report(path)
        finally:
            path.unlink()

    def test_empty_file_raises(self):
        path = _write_csv("")
        try:
            with self.assertRaises(TraceReportError):
                build_report(path)
        finally:
            path.unlink()

    def test_header_only_is_empty_capture_not_error(self):
        path = _write_csv("name,ts,dur,track_id\n")
        try:
            report = build_report(path)
        finally:
            path.unlink()
        self.assertEqual([], report["groups"])
        self.assertFalse(report["has_media_evidence"])
        self.assertIn("lacks evidence", report["evidence"])


class QuotedCsvTest(unittest.TestCase):
    def test_quoted_names_and_extra_columns(self):
        buf = io.StringIO()
        writer = csv.writer(buf, quoting=csv.QUOTE_ALL)
        writer.writerow(["name", "ts", "dur", "track_id", "extra note"])
        writer.writerow(["JB.media.dry.upload", "0", "2000000", "4", "hello, world"])
        writer.writerow(["JB.media.dry.upload", "3000000", "1000000", "4", "quoted"])
        text = buf.getvalue()
        path = _write_csv(text)
        try:
            report = build_report(path)
        finally:
            path.unlink()
        self.assertEqual(1, len(report["groups"]))
        g = report["groups"][0]
        self.assertEqual(2, g["count"])
        self.assertAlmostEqual(3.0, g["sum_ms"])


class UnknownAndUnrelatedTest(unittest.TestCase):
    def test_unknown_future_label_preserved(self):
        text = _csv_text(
            HEADER,
            [
                ["JB.media.dry.apply", 0, 1_000_000, "1"],
                ["JB.media.wet.future_stage", 2_000_000, 2_000_000, "1"],
            ],
        )
        path = _write_csv(text)
        try:
            report = build_report(path)
        finally:
            path.unlink()
        self.assertEqual(2, len(report["groups"]))
        self.assertEqual(["JB.media.wet.future_stage"], report["unknown_media_labels"])

    def test_unrelated_slices_excluded(self):
        text = _csv_text(
            HEADER,
            [
                ["JB.media.dry.apply", 0, 1_000_000, "1"],
                ["Choreographer#doFrame", 0, 8_000_000, "1"],
                ["JB.other.notmedia", 1_000_000, 1_000_000, "1"],
                ["jb.media.dry.apply", 2_000_000, 1_000_000, "1"],  # case differs
            ],
        )
        path = _write_csv(text)
        try:
            report = build_report(path)
        finally:
            path.unlink()
        self.assertEqual(1, len(report["groups"]))
        self.assertEqual(3, report["totals"]["unrelated_rows"])
        self.assertEqual(1, report["totals"]["valid_media_rows"])


class ExtentProvenanceTest(unittest.TestCase):
    def test_extent_and_provenance(self):
        text = _csv_text(
            HEADER,
            [
                ["JB.media.dry.apply", 10_000_000, 2_000_000, "1"],
                ["JB.media.dry.copy", 20_000_000, 5_000_000, "2"],
            ],
        )
        path = _write_csv(text)
        try:
            report = build_report(path)
        finally:
            path.unlink()
        self.assertEqual(str(path), report["input"]["path"])
        self.assertEqual(2, report["input"]["data_rows"])
        self.assertGreater(report["input"]["bytes"], 0)
        media_extent = report["media_extent_ns"]
        assert media_extent is not None
        self.assertEqual(10_000_000, media_extent["min_ts_ns"])
        self.assertEqual(25_000_000, media_extent["max_end_ns"])
        self.assertEqual(15_000_000, media_extent["extent_ns"])


class CliTest(unittest.TestCase):
    def test_cli_emits_valid_json_with_disclaimers(self):
        # Synthetic smoke fixture, not device evidence.
        text = _csv_text(HEADER, [["JB.media.look.render", 0, 2_000_000, "1"]])
        path = _write_csv(text)
        try:
            completed = subprocess.run(
                [sys.executable, str(REPORT), str(path)],
                capture_output=True,
                text=True,
            )
        finally:
            path.unlink()
        self.assertEqual(0, completed.returncode, msg=completed.stderr)
        report = json.loads(completed.stdout)  # must be valid JSON
        self.assertEqual(str(path), report["input"]["path"])
        self.assertIn("CPU slice durations", report["timing_units"])
        self.assertIn("GPU", report["timing_scope"])
        self.assertIn("unmeasured", report["gpu_note"])
        self.assertIsNone(report["gpu_execution_ms"])
        self.assertIsNone(report["frame_total_ms"])

    def test_cli_bad_path_fails(self):
        completed = subprocess.run(
            [sys.executable, str(REPORT), str(Path(tempfile.gettempdir()) / "no-such-trace-xyz.csv")],
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(0, completed.returncode)

    def test_cli_missing_columns_fails(self):
        path = _write_csv(_csv_text(["name", "ts"], [["x", 0]]))
        try:
            completed = subprocess.run(
                [sys.executable, str(REPORT), str(path)],
                capture_output=True,
                text=True,
            )
        finally:
            path.unlink()
        self.assertNotEqual(0, completed.returncode)


class EmptyCaptureTest(unittest.TestCase):
    def test_unrelated_only_lacks_evidence(self):
        text = _csv_text(HEADER, [["Choreographer#doFrame", 0, 5_000_000, "1"]])
        path = _write_csv(text)
        try:
            report = build_report(path)
        finally:
            path.unlink()
        self.assertEqual([], report["groups"])
        self.assertFalse(report["has_media_evidence"])
        self.assertIn("lacks evidence", report["evidence"])
        self.assertIsNone(report["media_extent_ns"])
        # Input extent still reflects the capture window from valid ts rows.
        assert report["input_extent_ns"] is not None


class StrictQuotingTest(unittest.TestCase):
    def test_unclosed_quote_excluded_prior_valid_retained(self):
        # Synthetic adversarial probe: optional note field has an unclosed
        # quote. Required fields look valid, so lenient parsing would accept
        # it as timing evidence. Strict parsing must exclude it as malformed
        # while retaining the prior valid row. Not device evidence.
        text = (
            "name,ts,dur,track_id,note\n"
            "JB.media.dry.apply,0,1000000,1,ok\n"
            'JB.media.dry.apply,2000000,1000000,1,"unterminated\n'
        )
        path = _write_csv(text)
        try:
            report = build_report(path)
        finally:
            path.unlink()
        self.assertEqual(1, report["totals"]["valid_media_rows"])
        self.assertEqual(1, report["totals"]["malformed_rows"])
        self.assertEqual(1, len(report["groups"]))
        self.assertAlmostEqual(1.0, report["groups"][0]["sum_ms"])
        self.assertTrue(report["has_media_evidence"])
        self.assertEqual(1, len(report["malformed_rows"]))
        entry = report["malformed_rows"][0]
        self.assertEqual(3, entry["line"])
        self.assertIn("csv parse error", entry["detail"])
        self.assertTrue(any("malformed" in w for w in report["warnings"]))

    def test_invalid_quoted_syntax_excluded(self):
        # Synthetic: closing quote followed by trailing chars without a
        # delimiter is invalid quoted syntax. Must be malformed, not timing
        # evidence; prior valid row retained. Not device evidence.
        text = (
            "name,ts,dur,track_id,note\n"
            "JB.media.dry.apply,0,1000000,1,ok\n"
            'JB.media.dry.apply,2000000,1000000,1,"closed"trailing\n'
        )
        path = _write_csv(text)
        try:
            report = build_report(path)
        finally:
            path.unlink()
        self.assertEqual(1, report["totals"]["valid_media_rows"])
        self.assertEqual(1, report["totals"]["malformed_rows"])
        self.assertEqual(1, len(report["groups"]))
        entry = report["malformed_rows"][0]
        self.assertEqual(3, entry["line"])
        self.assertIn("csv parse error", entry["detail"])


class MultilineQuotedFieldTest(unittest.TestCase):
    def test_valid_multiline_quoted_field_and_line_numbers_after(self):
        # Synthetic: optional note field legitimately contains an embedded
        # newline inside quotes (physical lines 3-4 form one logical record).
        # The following invalid row must report physical line 5 (not logical
        # record 4) and the final valid row physical line 6 is reflected via
        # the next invalid/malformed provenance check. Not device evidence.
        text = (
            "name,ts,dur,track_id,note\n"
            "JB.media.dry.apply,0,1000000,1,ok\n"
            'JB.media.dry.apply,2000000,2000000,1,"hello\nworld"\n'
            "JB.media.dry.apply,abc,1000000,1,after\n"
            "JB.media.dry.apply,5000000,3000000,1,after2\n"
        )
        path = _write_csv(text)
        try:
            report = build_report(path)
        finally:
            path.unlink()
        # Three valid media rows: line 2, lines 3-4 (one record), line 6.
        self.assertEqual(3, report["totals"]["valid_media_rows"])
        self.assertEqual(0, report["totals"]["malformed_rows"])
        self.assertEqual(1, report["totals"]["invalid_rows"])
        self.assertEqual(4, report["input"]["data_rows"])
        self.assertEqual(1, len(report["groups"]))
        g = report["groups"][0]
        self.assertEqual(3, g["count"])
        self.assertAlmostEqual(6.0, g["sum_ms"])
        # Accurate physical provenance after the multiline record.
        self.assertEqual(1, len(report["invalid_rows"]))
        self.assertEqual(5, report["invalid_rows"][0]["line"])


if __name__ == "__main__":
    unittest.main()
