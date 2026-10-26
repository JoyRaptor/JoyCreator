import hashlib
import json
import os
import re
import struct
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest.mock import patch

import run_select_bench as runner


def png_bytes(width=2, height=2):
    def chunk(kind, data):
        return (struct.pack(">I", len(data)) + kind + data +
                struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))

    ihdr = chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    rows = b"".join(b"\x00" + bytes(width * 3) for _ in range(height))
    return b"\x89PNG\r\n\x1a\n" + ihdr + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


class FakeAdb:
    def __init__(self, installed_hash, log_rows=(), log_error=None, foreground=True):
        self.hashes = list(installed_hash) if isinstance(installed_hash, list) else [installed_hash]
        self.log_rows = list(log_rows)
        self.log_error = log_error
        self.foreground = foreground
        self.calls = []
        self.actions = []
        self.acks = []
        self.audit_path = None
        self.audit_present_when_acked = None
        self.launches = 0
        self.screencap = png_bytes()

    def ready(self):
        return "SM-N960U", 29

    def installed_hash(self):
        if len(self.hashes) > 1:
            return self.hashes.pop(0)
        return self.hashes[0]

    def temperature(self):
        return 31.2

    def call(self, *args, binary=False, timeout=12):
        self.calls.append(args)
        if args[:3] == ("shell", "am", "start"):
            self.launches += 1
            return "Starting: Intent"
        if args and args[0] == "exec-out":
            self.actions.append("capture")
            return self.screencap if binary else self.screencap.decode()
        if args == ("shell", "dumpsys", "activity", "activities"):
            self.actions.append("foreground")
            return ("mResumedActivity: ActivityRecord{abc com.fadcam.beta/com.fadcam.joybrush.JoyBrushActivity}" if self.foreground
                    else "mResumedActivity: ActivityRecord{abc com.fadcam.beta/com.fadcam.joybrush.OtherActivity}")
        if args[:4] == ("shell", "run-as", runner.PACKAGE, "touch"):
            if len(args) != 5 or not re.fullmatch(r"cache/select-bench-[0-9a-f]{32}-\d+\.ack", args[4]):
                raise AssertionError(f"Unexpected fake screenshot ACK call: {args!r}")
            self.actions.append("ack")
            self.acks.append(args)
            self.audit_present_when_acked = self.audit_path.exists() if self.audit_path is not None else None
            return ""
        if args and args[0] == "logcat":
            if self.log_error is not None:
                raise self.log_error
            return "\n".join(json.dumps(row) for row in self.log_rows)
        raise AssertionError(f"Unexpected fake ADB call: {args!r}")


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.appdata = self.root / "appdata"
        self.lock = self.appdata / "Temp" / "jb-phone.lock"
        self.apk = self.root / "app.apk"
        self.apk.write_bytes(b"frozen apk bytes")
        self.digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.receipt = self.root / "build-receipt.json"
        self.write_receipt()
        self.manifest = self.root / "select_bench_manifest.json"
        self.steps = ["setup", "pick"]
        self.shots = ["01_setup.png"]
        self.write_manifest()

    def tearDown(self):
        self.tmp.cleanup()

    def write_receipt(self, successful=True, digest=None):
        self.receipt.write_text(json.dumps({
            "source40hex": "a" * 40,
            "buildSuccessful": successful,
            "apkSha256": digest or self.digest,
            "apkPath": str(self.apk),
        }), encoding="utf-8")

    def write_manifest(self):
        self.manifest.write_text(json.dumps({
            "version": 1,
            "steps": self.steps,
            "screenshots": self.shots,
        }), encoding="utf-8")

    def patches(self, adb, monotonic=None):
        active = [
            patch.object(runner, "Adb", return_value=adb),
            patch.object(runner, "HERE", self.root),
            patch.dict("os.environ", {"LOCALAPPDATA": str(self.appdata), "SANDBOX_SERIAL": "FAKE"}),
            patch.object(runner.time, "sleep", return_value=None),
        ]
        if monotonic is not None:
            active.append(patch.object(runner.time, "monotonic", side_effect=monotonic))
        return active

    def run_patched(self, out, adb, monotonic=None, case="select"):
        patches = self.patches(adb, monotonic)
        try:
            for item in patches:
                item.start()
            return runner.run(out, "test lane", self.receipt, case)
        finally:
            for item in reversed(patches):
                item.stop()

    def test_phone_claim_refuses_existing_claim_and_release_preserves_replacement(self):
        self.lock.parent.mkdir(parents=True)
        self.lock.write_bytes(b"owner's existing claim\n")
        with self.assertRaisesRegex(runner.RunError, "Phone is claimed"):
            runner.PhoneClaim(self.lock, "lane", "run-a").acquire()
        self.assertEqual(b"owner's existing claim\n", self.lock.read_bytes())

        self.lock.unlink()
        claim = runner.PhoneClaim(self.lock, "lane", "run-b")
        claim.acquire()
        self.lock.write_bytes(b"replacement foreign claim\n")
        claim.release()
        self.assertEqual(b"replacement foreign claim\n", self.lock.read_bytes())

    def test_unknown_case_refuses_before_claim_or_adb(self):
        adb = FakeAdb(self.digest)
        with self.assertRaisesRegex(runner.RunError, "Unsupported Select bench case"):
            self.run_patched(self.root / "unknown-case", adb, case="../arbitrary")
        self.assertFalse(self.lock.exists())
        self.assertEqual([], adb.calls)

    def a10_rows(self, run_id):
        manifest = json.loads(self.manifest.read_text())
        manifest["benchCase"] = "selectA10"
        (self.root / "select_a10_bench_manifest.json").write_text(json.dumps(manifest))
        own = [
            {"selectBench": "setup", "pass": True},
            {"selectBenchShot": "01_setup.png", "shotProtocol": 1},
            {"selectBench": "pick", "pass": True},
            {"selectBench": "summary", "passed": 2, "failed": 0},
            {"selectBenchDone": True},
        ]
        return [dict(row, runId=run_id, event=i, benchCase="selectA10") for i, row in enumerate(own)]

    def test_a10_launch_uses_separate_case_and_manifest(self):
        run_id = "0123456789abcdef0123456789abcdef"
        adb = FakeAdb(self.digest, self.a10_rows(run_id))
        out = self.root / "a10-out"
        with patch.object(runner.uuid, "uuid4", return_value=type("UUID", (), {"hex": run_id})()):
            self.run_patched(out, adb, case="selectA10")
        launch = next(call for call in adb.calls if call[:3] == ("shell", "am", "start"))
        self.assertEqual("selectA10", launch[launch.index("jb_bench") + 1])
        self.assertEqual("selectA10", json.loads((out / "build.json").read_text())["benchCase"])
        self.assertEqual(1, len(adb.acks))
        self.assertFalse(self.lock.exists())

    def test_a10_wrong_case_event_is_rejected_before_capture_or_ack(self):
        run_id = "0123456789abcdef0123456789abcdef"
        rows = self.a10_rows(run_id)
        rows[1]["benchCase"] = "select"
        adb = FakeAdb(self.digest, rows)
        with patch.object(runner.uuid, "uuid4", return_value=type("UUID", (), {"hex": run_id})()):
            with self.assertRaisesRegex(runner.RunError, "requested A10 case"):
                self.run_patched(self.root / "wrong-case", adb, case="selectA10")
        self.assertEqual([], adb.acks)
        self.assertEqual(["capture"], adb.actions)  # Home baseline only.
        self.assertTrue(self.lock.exists())  # Launched state is unresolved.

    def test_artifact_rejects_unsuccessful_build_and_apk_hash_mismatch(self):
        self.write_receipt(successful=False)
        with self.assertRaisesRegex(runner.RunError, "buildSuccessful:true"):
            runner.artifact(self.receipt)

        self.write_receipt(digest="0" * 64)
        with self.assertRaisesRegex(runner.RunError, "does not match"):
            runner.artifact(self.receipt)

    def test_replaced_file_with_identical_token_is_not_deleted(self):
        claim = runner.PhoneClaim(self.lock, "lane", "original")
        claim.acquire()
        replacement = self.root / "foreign-claim"
        replacement.write_bytes(claim.content)
        os.replace(replacement, self.lock)
        self.assertFalse(claim.release())
        self.assertEqual(claim.content, self.lock.read_bytes())

    @unittest.skipUnless(os.name == "nt", "Windows handle-pinned cleanup")
    def test_cleanup_retains_claim_when_delete_access_cannot_be_pinned(self):
        claim = runner.PhoneClaim(self.lock, "lane", "pinned")
        claim.acquire()
        with self.lock.open("rb"):
            self.assertFalse(claim.release())
            self.assertTrue(self.lock.exists())
        self.assertTrue(claim.release())
        self.assertFalse(self.lock.exists())

    def test_wrong_installed_hash_refuses_launch_and_releases_own_claim(self):
        adb = FakeAdb("0" * 64)
        out = self.root / "wrong-hash-out"
        with self.assertRaisesRegex(runner.RunError, "Installed APK differs"):
            self.run_patched(out, adb)

        self.assertEqual(0, adb.launches)
        self.assertFalse(self.lock.exists())
        self.assertFalse(any(call[:3] == ("shell", "am", "start") for call in adb.calls))

    def test_timeout_after_launch_retains_claim(self):
        adb = FakeAdb(self.digest)
        out = self.root / "timeout-out"
        with self.assertRaisesRegex(runner.RunError, "timed out"):
            self.run_patched(out, adb, monotonic=[0.0, 241.0])

        self.assertEqual(1, adb.launches)
        self.assertTrue(self.lock.exists())

    def test_adb_failure_after_launch_retains_claim(self):
        adb = FakeAdb(self.digest, log_error=runner.RunError("simulated logcat failure"))
        out = self.root / "adb-failure-out"
        with self.assertRaisesRegex(runner.RunError, "simulated logcat failure"):
            self.run_patched(out, adb)

        self.assertEqual(1, adb.launches)
        self.assertTrue(self.lock.exists())

    def run_terminal_log(self, out, rows):
        run_id = "0123456789abcdef0123456789abcdef"
        adb = FakeAdb(self.digest, rows(run_id))
        with patch.object(runner.uuid, "uuid4", return_value=type("UUID", (), {"hex": run_id})()):
            self.run_patched(out, adb)
        return adb, run_id

    @staticmethod
    def terminal_rows(run_id, reports, summary):
        rows = [
            {"selectBench": "setup", "runId": "foreign-run", "event": 0, "pass": True},
            {"selectBenchShot": "01_setup.png", "runId": "foreign-run", "event": 1},
        ]
        own = [*reports, summary, {"selectBenchDone": True, "runId": run_id}]
        rows.extend(dict(row, runId=run_id, event=index) for index, row in enumerate(own))
        return rows

    def test_only_exact_matching_run_is_written_and_foreign_events_are_ignored(self):
        rows = lambda run_id: self.terminal_rows(run_id, [
            {"selectBench": "setup", "runId": run_id, "pass": True},
            {"selectBenchShot": "01_setup.png", "shotProtocol": 1, "runId": run_id},
            {"selectBench": "pick", "runId": run_id, "pass": True},
        ], {"selectBench": "summary", "runId": run_id, "passed": 2, "failed": 0})
        out = self.root / "matching-out"
        adb = FakeAdb(self.digest, rows("0123456789abcdef0123456789abcdef"))
        adb.audit_path = out / "shots.jsonl"
        original_validate_png = runner.validate_png
        def tracked_validate(path):
            adb.actions.append("png-validated")
            return original_validate_png(path)
        with patch.object(runner.uuid, "uuid4", return_value=type("UUID", (), {"hex": "0123456789abcdef0123456789abcdef"})()):
            with patch.object(runner, "validate_png", side_effect=tracked_validate):
                self.run_patched(out, adb)
        run_id = "0123456789abcdef0123456789abcdef"

        saved = [json.loads(line) for line in (out / "results.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual(["setup", "pick", "summary"], [row["selectBench"] for row in saved])
        self.assertTrue(all(row["runId"] == run_id for row in saved))
        self.assertEqual(2, sum(call[0] == "exec-out" for call in adb.calls))  # Baseline plus matching shot.
        self.assertEqual(["cache/select-bench-" + run_id + "-1.ack"], [call[4] for call in adb.acks])
        self.assertEqual(["capture", "foreground", "capture", "png-validated", "ack"], adb.actions)
        self.assertFalse(adb.audit_present_when_acked)
        audit_rows = [json.loads(line) for line in (out / "shots.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual([{
            "runId": run_id,
            "event": 1,
            "name": "01_setup.png",
            "shotProtocol": 1,
            "sha256": hashlib.sha256((out / "01_setup.png").read_bytes()).hexdigest(),
            "foregroundConfirmed": True,
        }], audit_rows)
        self.assertTrue((out / "01_setup.png").is_file())
        self.assertFalse(self.lock.exists())

    def test_manifest_missing_step_is_rejected(self):
        rows = lambda run_id: self.terminal_rows(run_id, [
            {"selectBench": "setup", "runId": run_id, "pass": True},
        ], {"selectBench": "summary", "runId": run_id, "passed": 1, "failed": 0})
        with self.assertRaisesRegex(runner.EvidenceError, "missing reports"):
            self.run_terminal_log(self.root / "missing-step-out", rows)

    def test_nonpassing_summary_is_rejected(self):
        rows = lambda run_id: self.terminal_rows(run_id, [
            {"selectBench": "setup", "runId": run_id, "pass": False},
            {"selectBench": "pick", "runId": run_id, "pass": True},
        ], {"selectBench": "summary", "runId": run_id, "passed": 1, "failed": 1})
        with self.assertRaisesRegex(runner.EvidenceError, r"report\(s\) failed"):
            self.run_terminal_log(self.root / "failed-summary-out", rows)

    def test_matching_event_sequence_gap_rejects_and_retains_claim(self):
        run_id = "0123456789abcdef0123456789abcdef"
        adb = FakeAdb(self.digest, [
            {"runId": run_id, "event": 0, "selectBench": "setup", "pass": True},
            {"runId": run_id, "event": 2, "selectBench": "pick", "pass": True},
        ])
        with patch.object(runner.uuid, "uuid4", return_value=type("UUID", (), {"hex": run_id})()):
            with self.assertRaisesRegex(runner.RunError, "incomplete or out of order"):
                self.run_patched(self.root / "event-gap-out", adb)
        self.assertTrue(self.lock.exists())

    def test_shot_protocol_is_mandatory_and_requires_integer_version_one(self):
        run_id = "0123456789abcdef0123456789abcdef"
        bad_protocols = [{}, {"shotProtocol": True}, {"shotProtocol": 2}]
        for index, protocol in enumerate(bad_protocols):
            with self.subTest(protocol=protocol):
                shot = {"runId": run_id, "event": 1, "selectBenchShot": "01_setup.png", **protocol}
                adb = FakeAdb(self.digest, [
                    {"runId": run_id, "event": 0, "selectBench": "setup", "pass": True},
                    shot,
                ])
                out = self.root / f"bad-shot-protocol-{index}"
                with patch.object(runner.uuid, "uuid4", return_value=type("UUID", (), {"hex": run_id})()):
                    with self.assertRaisesRegex(runner.RunError, "unsupported protocol"):
                        self.run_patched(out, adb)
                self.assertEqual([], adb.acks)
                self.assertEqual(1, sum(call[0] == "exec-out" for call in adb.calls))  # Baseline only.
                self.assertTrue(self.lock.exists())
                self.lock.unlink()

    def test_corrupt_png_is_not_acknowledged(self):
        run_id = "0123456789abcdef0123456789abcdef"
        adb = FakeAdb(self.digest, [
            {"runId": run_id, "event": 0, "selectBench": "setup", "pass": True},
            {"runId": run_id, "event": 1, "selectBenchShot": "01_setup.png", "shotProtocol": 1},
        ])
        adb.screencap = b"corrupt png bytes"
        out = self.root / "corrupt-png-out"
        original_validate_png = runner.validate_png
        def tracked_validate(path):
            adb.actions.append("png-validated")
            return original_validate_png(path)
        with patch.object(runner.uuid, "uuid4", return_value=type("UUID", (), {"hex": run_id})()):
            with patch.object(runner, "validate_png", side_effect=tracked_validate):
                with self.assertRaisesRegex(runner.EvidenceError, "invalid PNG signature"):
                    self.run_patched(out, adb)

        self.assertTrue((out / "01_setup.png").is_file())
        self.assertEqual([], adb.acks)
        self.assertEqual(["capture", "foreground", "capture", "png-validated"], adb.actions)
        self.assertTrue(self.lock.exists())

    def test_nonforeground_bench_is_not_captured_or_acknowledged(self):
        run_id = "0123456789abcdef0123456789abcdef"
        adb = FakeAdb(self.digest, [
            {"runId": run_id, "event": 0, "selectBench": "setup", "pass": True},
            {"runId": run_id, "event": 1, "selectBenchShot": "01_setup.png", "shotProtocol": 1},
        ], foreground=False)
        out = self.root / "wrong-foreground-out"
        with patch.object(runner.uuid, "uuid4", return_value=type("UUID", (), {"hex": run_id})()):
            with self.assertRaisesRegex(runner.RunError, "left the foreground"):
                self.run_patched(out, adb)

        self.assertEqual(1, sum(call[0] == "exec-out" for call in adb.calls))  # Baseline only.
        self.assertFalse((out / "01_setup.png").exists())
        self.assertEqual([], adb.acks)
        self.assertEqual(["capture", "foreground"], adb.actions)
        self.assertFalse((out / "shots.jsonl").exists())
        self.assertTrue(self.lock.exists())

    def test_new_report_after_done_is_rejected_without_writing_it(self):
        run_id = "0123456789abcdef0123456789abcdef"
        own = [
            {"selectBench": "setup", "runId": run_id, "pass": True},
            {"selectBenchShot": "01_setup.png", "shotProtocol": 1, "runId": run_id},
            {"selectBench": "pick", "runId": run_id, "pass": True},
            {"selectBench": "summary", "runId": run_id, "passed": 2, "failed": 0},
            {"selectBenchDone": True, "runId": run_id},
            {"selectBench": "late-report", "runId": run_id, "pass": True},
        ]
        rows = [
            {"selectBench": "foreign", "runId": "foreign-run", "event": 0},
            *[dict(row, event=index) for index, row in enumerate(own)],
        ]
        adb = FakeAdb(self.digest, rows)
        out = self.root / "post-done-report-out"
        with patch.object(runner.uuid, "uuid4", return_value=type("UUID", (), {"hex": run_id})()):
            with self.assertRaisesRegex(runner.RunError, "after terminal completion"):
                self.run_patched(out, adb)

        saved = [json.loads(line) for line in (out / "results.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual(["setup", "pick", "summary"], [row["selectBench"] for row in saved])
        self.assertFalse(self.lock.exists())  # A terminal event was observed, so the claim is releasable.

    def test_new_screenshot_after_done_is_rejected_before_capture(self):
        self.shots.append("02_late.png")
        self.write_manifest()
        run_id = "0123456789abcdef0123456789abcdef"
        own = [
            {"selectBench": "setup", "runId": run_id, "pass": True},
            {"selectBenchShot": "01_setup.png", "shotProtocol": 1, "runId": run_id},
            {"selectBench": "pick", "runId": run_id, "pass": True},
            {"selectBench": "summary", "runId": run_id, "passed": 2, "failed": 0},
            {"selectBenchDone": True, "runId": run_id},
            {"selectBenchShot": "02_late.png", "runId": run_id},
        ]
        rows = [
            {"selectBench": "foreign", "runId": "foreign-run", "event": 0},
            *[dict(row, event=index) for index, row in enumerate(own)],
        ]
        adb = FakeAdb(self.digest, rows)
        out = self.root / "post-done-shot-out"
        with patch.object(runner.uuid, "uuid4", return_value=type("UUID", (), {"hex": run_id})()):
            with self.assertRaisesRegex(runner.RunError, "after terminal completion"):
                self.run_patched(out, adb)

        self.assertTrue((out / "01_setup.png").is_file())
        self.assertFalse((out / "02_late.png").exists())
        self.assertEqual(2, sum(call[0] == "exec-out" for call in adb.calls))  # Baseline plus pre-done shot.
        self.assertEqual(["cache/select-bench-" + run_id + "-1.ack"], [call[4] for call in adb.acks])
        self.assertFalse(self.lock.exists())

    def test_exact_duplicate_after_done_is_deduplicated(self):
        run_id = "0123456789abcdef0123456789abcdef"
        own = [
            {"selectBench": "setup", "runId": run_id, "pass": True},
            {"selectBenchShot": "01_setup.png", "shotProtocol": 1, "runId": run_id},
            {"selectBench": "pick", "runId": run_id, "pass": True},
            {"selectBench": "summary", "runId": run_id, "passed": 2, "failed": 0},
            {"selectBenchDone": True, "runId": run_id},
        ]
        rows = [
            {"selectBench": "foreign", "runId": "foreign-run", "event": 0},
            *[dict(row, event=index) for index, row in enumerate(own)],
            dict(own[-1], event=len(own) - 1),
        ]
        adb = FakeAdb(self.digest, rows)
        with patch.object(runner.uuid, "uuid4", return_value=type("UUID", (), {"hex": run_id})()):
            self.run_patched(self.root / "duplicate-done-out", adb)
        self.assertFalse(self.lock.exists())


if __name__ == "__main__":
    unittest.main()
