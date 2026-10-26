#!/usr/bin/env python3
"""Exact-artifact Select acceptance. Does not install, unlock, or clear app/log data."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

from validate_select_bench import EvidenceError, read_json, validate, validate_png

PACKAGE = "com.fadcam.beta"
ACTIVITY = PACKAGE + "/com.fadcam.joybrush.JoyBrushBenchActivity"
HERE = Path(__file__).resolve().parent
HEX40 = re.compile(r"[0-9a-f]{40}")
HEX64 = re.compile(r"[0-9a-f]{64}")


class RunError(Exception):
    pass


class PhoneClaim:
    def __init__(self, path: Path, lane: str, run_id: str):
        self.path = path
        self.content = f"{lane}\n{time.strftime('%Y-%m-%d %H:%M:%S')}\nSelect bench {run_id}\n".encode()
        self.identity = None

    def acquire(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        try:
            with self.path.open("xb") as stream:
                stream.write(self.content)
                stat = os.fstat(stream.fileno())
                self.identity = stat.st_dev, stat.st_ino
        except FileExistsError:
            raise RunError("Phone is claimed; coordinate with its owner.") from None

    def release(self):
        # This runner targets Windows. Delete the verified open file, not a pathname which could be replaced
        # between read and unlink. Failure to pin/identify/delete the same file retains the claim.
        return self.identity is not None and release_windows_claim(self.path, self.identity, self.content)


def release_windows_claim(path: Path, identity: tuple[int, int], content: bytes) -> bool:
    if os.name != "nt":
        return False
    import ctypes
    from ctypes import wintypes
    import msvcrt

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    create = kernel.CreateFileW
    create.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, wintypes.LPVOID,
                       wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
    create.restype = wintypes.HANDLE
    dispose = kernel.SetFileInformationByHandle
    dispose.argtypes = [wintypes.HANDLE, ctypes.c_int, wintypes.LPVOID, wintypes.DWORD]
    dispose.restype = wintypes.BOOL
    close = kernel.CloseHandle
    close.argtypes = [wintypes.HANDLE]
    close.restype = wintypes.BOOL
    # READ|DELETE access; share READ only pins identity against replacement/writes until close.
    # OPEN_REPARSE_POINT also prevents following a replacement symlink to a different target.
    handle = create(str(path), 0x80000000 | 0x10000, 1, None, 3, 0x00200000, None)
    if handle == ctypes.c_void_p(-1).value:
        return False
    try:
        descriptor = msvcrt.open_osfhandle(handle, os.O_RDONLY | os.O_BINARY)
    except OSError:
        close(handle)
        return False
    try:
        with os.fdopen(descriptor, "rb") as stream:
            stat = os.fstat(stream.fileno())
            if (stat.st_dev, stat.st_ino) != identity or stream.read(len(content) + 1) != content:
                return False
            # FileDispositionInfo acts on this verified handle. Closing deletes only this object.
            delete_on_close = ctypes.c_ubyte(1)
            return bool(dispose(handle, 4, ctypes.byref(delete_on_close), ctypes.sizeof(delete_on_close)))
    except OSError:
        return False


def artifact(receipt: Path):
    data = read_json(receipt, 65536)
    if not isinstance(data, dict) or not isinstance(data.get("source40hex"), str) or not HEX40.fullmatch(data["source40hex"]):
        raise RunError("Build receipt needs source40hex from the frozen successful build.")
    if data.get("buildSuccessful") is not True or not isinstance(data.get("apkSha256"), str) or not HEX64.fullmatch(data["apkSha256"]):
        raise RunError("Build receipt needs buildSuccessful:true and apkSha256.")
    apk = Path(data.get("apkPath", ""))
    if not apk.is_absolute() or not apk.is_file():
        raise RunError("Receipt apkPath must name the preserved absolute APK file.")
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest != data["apkSha256"]:
        raise RunError("Preserved APK does not match its successful-build receipt.")
    return data["source40hex"], digest


class Adb:
    def __init__(self, serial: str):
        if not serial or not re.fullmatch(r"[A-Za-z0-9_.:-]+", serial):
            raise RunError("Missing or invalid private SANDBOX_SERIAL.")
        self.serial = serial

    def call(self, *args, binary=False, timeout=12):
        try:
            result = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, timeout=timeout)
        except (OSError, subprocess.TimeoutExpired):
            raise RunError("ADB unavailable or timed out (device identity withheld).") from None
        if result.returncode:
            raise RunError("ADB operation failed (device identity withheld).")
        return result.stdout if binary else result.stdout.decode("utf-8").replace("\r", "").strip()

    def installed_hash(self):
        paths = self.call("shell", "pm", "path", PACKAGE).splitlines()
        base = [p[8:] for p in paths if p.startswith("package:") and p.endswith("/base.apk")]
        if len(base) != 1 or not re.fullmatch(r"/[A-Za-z0-9_./+=-]+", base[0]):
            raise RunError("Cannot establish installed base APK path.")
        value = self.call("shell", "sha256sum", base[0]).split()[0]
        if not HEX64.fullmatch(value):
            raise RunError("Cannot establish installed APK digest.")
        return value

    def temperature(self):
        found = re.search(r"^\s*temperature:\s*(\d+)\s*$", self.call("shell", "dumpsys", "battery"), re.M)
        if not found:
            raise RunError("Cannot establish battery temperature.")
        return int(found[1]) / 10

    def ready(self):
        model = self.call("shell", "getprop", "ro.product.model")
        api = self.call("shell", "getprop", "ro.build.version.sdk")
        if model != "SM-N960U" or api != "29":
            raise RunError("Acceptance requires the SM-N960U Note 9 on API 29.")
        power = self.call("shell", "dumpsys", "power")
        policy = self.call("shell", "dumpsys", "window", "policy")
        if "mWakefulness=Awake" not in power or not re.search(r"\bshowing=false\b", policy):
            raise RunError("Phone must already be awake and unlocked.")
        home = self.call("shell", "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").splitlines()[-1]
        resumed = self.call("shell", "dumpsys", "activity", "activities")
        if "/" not in home or not any(home in line for line in resumed.splitlines() if "mResumedActivity" in line):
            raise RunError("Leave the Note 9 on Home before starting acceptance.")
        return model, int(api)


def run(out: Path, lane: str, receipt: Path):
    if out.exists() and any(out.iterdir()):
        raise RunError("Use a fresh empty output directory.")
    out.mkdir(parents=True, exist_ok=True)
    source, expected = artifact(receipt)
    run_id = uuid.uuid4().hex
    manifest = HERE / "select_bench_manifest.json"
    expected_shots = set(read_json(manifest, 262144)["screenshots"])
    adb = Adb(os.environ.get("SANDBOX_SERIAL", ""))
    claim = PhoneClaim(Path(os.environ["LOCALAPPDATA"]) / "Temp" / "jb-phone.lock", lane, run_id)
    launched = False
    completed = False
    claim.acquire()
    try:
        model, api = adb.ready()
        installed = adb.installed_hash()
        if installed != expected:
            raise RunError("Installed APK differs from preserved build; no bench launched.")
        # Kept outside manifested step PNGs; this baseline is for manual Home/readiness review.
        (out / "before.png.evidence").write_bytes(adb.call("exec-out", "screencap", "-p", binary=True))
        (out / "temps.txt").write_text(f"temp_before_C {adb.temperature()}\n", encoding="utf-8")
        build = dict(source40hex=source, expectedApkSha256=expected, installedApkSha256=installed,
                     runId=run_id, deviceModel=model, deviceApi=api)
        (out / "build.json").write_text(json.dumps(build, indent=2) + "\n", encoding="utf-8")
        # Interrupted am start may still launch: retain the claim until terminal evidence exists.
        launched = True
        launch = adb.call("shell", "am", "start", "-n", ACTIVITY, "--es", "jb_bench", "select", "--es", "jb_select_run_id", run_id)
        if "Error:" in launch or "Exception" in launch:
            raise RunError("Bench launch not confirmed; claim retained for inspection.")
        seen = {}
        shots = set()
        deadline = time.monotonic() + 240
        with (out / "results.jsonl").open("x", encoding="utf-8") as results:
            while not completed and time.monotonic() < deadline:
                log = adb.call("logcat", "-d", "-v", "raw", "-s", "JoyBrushPerf:I", timeout=8)
                for line in log.splitlines():
                    if not line.startswith("{"):
                        continue
                    try:
                        row = json.loads(line)
                    except ValueError:
                        continue
                    if not isinstance(row, dict) or row.get("runId") != run_id:
                        continue
                    event = row.get("event")
                    if type(event) is not int or event < 0:
                        raise RunError("Bench event lacks a valid sequence identity.")
                    if event in seen:
                        if seen[event] != line:
                            raise RunError("Bench reused an event identity for different content.")
                        continue
                    if completed:
                        raise RunError("Bench emitted a new event after terminal completion.")
                    if event != len(seen):
                        raise RunError("Bench log is incomplete or out of order.")
                    seen[event] = line
                    if "selectBench" in row:
                        results.write(line + "\n"); results.flush()
                        print(row["selectBench"], row.get("pass", "summary"), flush=True)
                    elif "selectBenchShot" in row:
                        if type(row.get("shotProtocol")) is not int or row["shotProtocol"] != 1:
                            raise RunError("Screenshot request has an unsupported protocol.")
                        name = row["selectBenchShot"]
                        if name not in expected_shots or name in shots:
                            raise RunError("Unexpected or repeated screenshot request.")
                        resumed = adb.call("shell", "dumpsys", "activity", "activities")
                        if not any("mResumedActivity" in line and f"{PACKAGE}/" in line and "JoyBrushActivity" in line
                                   for line in resumed.splitlines()):
                            raise RunError("Select bench left the foreground; screenshot refused.")
                        path = out / name
                        with path.open("wb") as image:
                            image.write(adb.call("exec-out", "screencap", "-p", binary=True))
                            image.flush()
                            os.fsync(image.fileno())
                        validate_png(path)
                        adb.call("shell", "run-as", PACKAGE, "touch", f"cache/select-bench-{run_id}-{event}.ack")
                        shots.add(name)
                        audit = dict(runId=run_id, event=event, name=name, shotProtocol=1,
                                     sha256=hashlib.sha256(path.read_bytes()).hexdigest(), foregroundConfirmed=True)
                        with (out / "shots.jsonl").open("a", encoding="utf-8") as journal:
                            journal.write(json.dumps(audit, separators=(",", ":")) + "\n")
                            journal.flush()
                            os.fsync(journal.fileno())
                    elif row.get("selectBenchDone") is True:
                        completed = True
                if not completed:
                    time.sleep(.2)
        if not completed:
            raise RunError("Bench timed out; claim retained until running state is resolved.")
        after_hash = adb.installed_hash()
        build["installedApkSha256After"] = after_hash
        (out / "build.json").write_text(json.dumps(build, indent=2) + "\n", encoding="utf-8")
        with (out / "temps.txt").open("a", encoding="utf-8") as temps:
            temps.write(f"temp_after_C {adb.temperature()}\n")
        if after_hash != expected:
            raise RunError("Installed APK changed during acceptance; evidence rejected.")
        validate(out, manifest, run_id)
        print(f"PASS: complete exact-artifact evidence in {out}")
    finally:
        if not launched or completed:
            if not claim.release():
                print("Phone claim was not safely released; its identity/content or access changed. Inspect before cleanup.", file=sys.stderr)
        else:
            print("Phone claim retained: unresolved launched bench; coordinate cleanup with this lane.", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out", type=Path)
    parser.add_argument("lane", nargs="?", default="Select lines specialist")
    args = parser.parse_args()
    try:
        receipt = os.environ.get("SELECT_BENCH_BUILD_RECEIPT")
        if not receipt:
            raise RunError("Set SELECT_BENCH_BUILD_RECEIPT to the successful exact-source APK receipt.")
        run(args.out, args.lane, Path(receipt))
    except (RunError, EvidenceError, OSError, ValueError, KeyError, IndexError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
