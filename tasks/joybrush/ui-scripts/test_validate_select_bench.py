import json
import subprocess
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

from validate_select_bench import validate, EvidenceError


def png_bytes(width=2, height=3, include_idat=True, duplicate_ihdr=False):
    def chunk(kind, data):
        return (struct.pack(">I", len(data)) + kind + data +
                struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))
    ihdr = chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    scanlines = b"".join(b"\x00" + bytes(width * 3) for _ in range(height))
    data = b"\x89PNG\r\n\x1a\n" + ihdr
    if duplicate_ihdr:
        data += ihdr
    if include_idat:
        data += chunk(b"IDAT", zlib.compress(scanlines))
    return data + chunk(b"IEND", b"")


class ValidatorTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.out = self.root / "out"
        self.out.mkdir()
        self.manifest = self.root / "manifest.json"
        self.run_id = "bench-20261024-a"
        self.steps = ["setup", "pick", "summary-check"]
        self.shots = ["01_setup.png", "02_pick.png"]
        self.write_manifest()
        self.write_valid_evidence()

    def tearDown(self):
        self.tmp.cleanup()

    def write_manifest(self):
        self.manifest.write_text(json.dumps({"version": 1, "steps": self.steps,
                                             "screenshots": self.shots}), encoding="utf-8")

    def write_valid_evidence(self):
        rows = [{"selectBench": n, "runId": self.run_id, "pass": True} for n in self.steps]
        rows.append({"selectBench": "summary", "runId": self.run_id,
                     "passed": len(self.steps), "failed": 0})
        (self.out / "results.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
        (self.out / "build.json").write_text(json.dumps({
            "source40hex": "a" * 40, "expectedApkSha256": "b" * 64,
            "installedApkSha256": "B" * 64, "runId": self.run_id,
            "deviceModel": "SM-N960U", "deviceApi": 29}), encoding="utf-8")
        (self.out / "temps.txt").write_text("temp_before_C 31.2\ntemp_after_C 32.1\n", encoding="utf-8")
        for name in self.shots:
            (self.out / name).write_bytes(png_bytes())

    def assert_invalid(self, fragment):
        with self.assertRaisesRegex(EvidenceError, fragment):
            validate(self.out, self.manifest, self.run_id)

    def mutate_results(self, rows):
        (self.out / "results.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")

    def valid_rows(self):
        return [json.loads(line) for line in (self.out / "results.jsonl").read_text().splitlines()]

    def test_accepts_complete_consistent_baseline(self):
        validate(self.out, self.manifest, self.run_id)

    def test_rejects_missing_step_report(self):
        rows = self.valid_rows()
        self.mutate_results(rows[:1] + rows[2:])
        self.assert_invalid("missing reports")

    def test_rejects_duplicate_report(self):
        rows = self.valid_rows()
        rows.insert(1, rows[0].copy())
        self.mutate_results(rows)
        self.assert_invalid("duplicate report")

    def test_rejects_unknown_report(self):
        rows = self.valid_rows()
        rows.insert(0, {"selectBench": "not-in-manifest", "runId": self.run_id, "pass": True})
        self.mutate_results(rows)
        self.assert_invalid("unknown report")

    def test_rejects_wrong_run_id(self):
        rows = self.valid_rows()
        rows[0]["runId"] = "another-run"
        self.mutate_results(rows)
        self.assert_invalid("runId mismatch")

    def test_rejects_malformed_jsonl(self):
        with (self.out / "results.jsonl").open("a", encoding="utf-8") as f:
            f.write("{truncated\n")
        self.assert_invalid("invalid JSON")

    def test_rejects_missing_summary(self):
        self.mutate_results(self.valid_rows()[:-1])
        self.assert_invalid("missing summary")

    def test_rejects_duplicate_summary(self):
        rows = self.valid_rows()
        rows.append(rows[-1].copy())
        self.mutate_results(rows)
        self.assert_invalid("exactly one summary")

    def test_rejects_failed_step_and_count_mismatch(self):
        rows = self.valid_rows()
        rows[0]["pass"] = False
        rows[-1]["passed"] = 2
        rows[-1]["failed"] = 0
        self.mutate_results(rows)
        self.assert_invalid("summary failed count")

    def test_rejects_failed_step_even_with_correct_summary_counts(self):
        rows = self.valid_rows()
        rows[0]["pass"] = False
        rows[-1]["passed"] = 2
        rows[-1]["failed"] = 1
        self.mutate_results(rows)
        self.assert_invalid(r"report\(s\) failed")

    def test_rejects_nonboolean_pass(self):
        rows = self.valid_rows()
        rows[0]["pass"] = 1
        self.mutate_results(rows)
        self.assert_invalid("pass must be boolean")

    def test_rejects_duplicate_json_pass_key(self):
        text = (self.out / "results.jsonl").read_text(encoding="utf-8")
        text = text.replace('"pass": true', '"pass": false, "pass": true', 1)
        (self.out / "results.jsonl").write_text(text, encoding="utf-8")
        self.assert_invalid("duplicate JSON object key")

    def test_rejects_nonfinite_json_constant(self):
        text = (self.out / "results.jsonl").read_text(encoding="utf-8")
        text = text.replace('"pass": true', '"pass": true, "extra": NaN', 1)
        (self.out / "results.jsonl").write_text(text, encoding="utf-8")
        self.assert_invalid("non-standard JSON constant")

    def test_rejects_summary_before_later_report(self):
        rows = self.valid_rows()
        self.mutate_results(rows[:-1] + [rows[-1], rows[0]])
        self.assert_invalid("summary must be the last")

    def test_rejects_apk_hash_mismatch(self):
        data = json.loads((self.out / "build.json").read_text())
        data["installedApkSha256"] = "c" * 64
        (self.out / "build.json").write_text(json.dumps(data), encoding="utf-8")
        self.assert_invalid("do not match")

    def test_rejects_wrong_device_identity(self):
        data = json.loads((self.out / "build.json").read_text())
        data["deviceModel"] = "SM-N986U"
        (self.out / "build.json").write_text(json.dumps(data), encoding="utf-8")
        self.assert_invalid("deviceModel")

    def test_rejects_missing_temperature(self):
        (self.out / "temps.txt").write_text("temp_before_C 31.2\n", encoding="utf-8")
        self.assert_invalid("missing temp_after_C")

    def test_rejects_nonfinite_temperature(self):
        (self.out / "temps.txt").write_text("temp_before_C NaN\ntemp_after_C 32\n", encoding="utf-8")
        self.assert_invalid("finite")

    def test_rejects_truncated_png(self):
        (self.out / self.shots[0]).write_bytes(png_bytes()[:-5])
        self.assert_invalid("truncated PNG chunk")

    def test_rejects_png_without_idat(self):
        (self.out / self.shots[0]).write_bytes(png_bytes(include_idat=False))
        self.assert_invalid("nonempty IDAT")

    def test_rejects_duplicate_png_ihdr(self):
        (self.out / self.shots[0]).write_bytes(png_bytes(duplicate_ihdr=True))
        self.assert_invalid("duplicate IHDR")

    def test_rejects_invalid_png_signature(self):
        (self.out / self.shots[0]).write_bytes(b"not a png")
        self.assert_invalid("invalid PNG signature")

    def test_rejects_screenshot_set_mismatch(self):
        (self.out / self.shots[1]).unlink()
        (self.out / "extra.png").write_bytes(png_bytes())
        self.assert_invalid("missing screenshot")

    def test_rejects_duplicate_manifest_names(self):
        self.steps.append("pick")
        self.write_manifest()
        self.assert_invalid("duplicate steps")

    def test_rejects_empty_steps_manifest(self):
        self.steps = []
        self.write_manifest()
        self.assert_invalid("steps must not be empty")

    def test_rejects_empty_screenshots_manifest(self):
        self.shots = []
        self.write_manifest()
        self.assert_invalid("screenshots must not be empty")

    def test_rejects_reserved_summary_step(self):
        self.steps = ["setup", "summary"]
        self.write_manifest()
        self.assert_invalid("reserved")

    def test_cli_returns_zero_for_valid_evidence(self):
        script = Path(__file__).with_name("validate_select_bench.py")
        result = subprocess.run([sys.executable, str(script), str(self.out), "--manifest",
                                 str(self.manifest), "--run-id", self.run_id],
                                capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_cli_returns_nonzero_for_invalid_evidence(self):
        (self.out / "results.jsonl").write_text("not json\n", encoding="utf-8")
        script = Path(__file__).with_name("validate_select_bench.py")
        result = subprocess.run([sys.executable, str(script), str(self.out), "--manifest",
                                 str(self.manifest), "--run-id", self.run_id],
                                capture_output=True, text=True, check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("INVALID:", result.stderr)


if __name__ == "__main__":
    unittest.main()
