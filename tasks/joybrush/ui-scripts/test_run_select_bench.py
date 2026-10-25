import hashlib
import json
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
    def __init__(self, installed_hash, log_rows=(), log_error=None):
        self.hashes = list(installed_hash) if isinstance(installed_hash, list) else [installed_hash]
        self.log_rows = list(log_rows)
        self.log_error = log_error
        self.calls = []
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
            return self.screencap if binary else self.screencap.decode()
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

    def run_patched(self, out, adb, monotonic=None):
        patches = self.patches(adb, monotonic)
        try:
            for item in patches:
                item.start()
            return runner.run(out, "test lane", self.receipt)
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

    def test_artifact_rejects_unsuccessful_build_and_apk_hash_mismatch(self):
        self.write_receipt(successful=False)
        with self.assertRaisesRegex(runner.RunError, "buildSuccessful:true"):
            runner.artifact(self.receipt)

        self.write_receipt(digest="0" * 64)
        with self.assertRaisesRegex(runner.RunError, "does not match"):
            runner.artifact(self.receipt)

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
            {"selectBenchShot": "01_setup.png", "runId": run_id},
            {"selectBench": "pick", "runId": run_id, "pass": True},
        ], {"selectBench": "summary", "runId": run_id, "passed": 2, "failed": 0})
        out = self.root / "matching-out"
        adb, run_id = self.run_terminal_log(out, rows)

        saved = [json.loads(line) for line in (out / "results.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual(["setup", "pick", "summary"], [row["selectBench"] for row in saved])
        self.assertTrue(all(row["runId"] == run_id for row in saved))
        self.assertEqual(2, sum(call[0] == "exec-out" for call in adb.calls))  # Baseline plus matching shot.
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


if __name__ == "__main__":
    unittest.main()
