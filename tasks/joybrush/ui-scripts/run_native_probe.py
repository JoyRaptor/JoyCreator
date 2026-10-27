#!/usr/bin/env python3
"""Exact-artifact native probe on the Note 9 sandbox: frame duplicate or ordinary watercolor.

One run = one claim, one fresh log fence, one bench launch, saved-data manifests before and after, and a receipt.
Installs only with --install (adb install -r; never uninstall or clear data). Does not unlock the phone.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import sys
import time
import uuid

from run_select_bench import ACTIVITY, PACKAGE, Adb, PhoneClaim, RunError

CASES = {
    "nativeFrameDuplicateProbe": "joybrush.ink",
    "recordedWatercolourOrdinaryProbe": "joybrush.watercolour",
}
# Every place a person's drawings or projects live on the sandbox. A probe must leave each file byte-identical.
SAVED_ROOTS = (
    "/sdcard/Android/data/com.fadcam.beta/files/joybrush",
    "/sdcard/Joy Creator",
    "/sdcard/Joy Creator (first test)",
)
WORKING = ("/sdcard/Android/data/com.fadcam.beta/files/joybrush/current.joybrush",
           "/sdcard/Android/data/com.fadcam.beta/files/joybrush/current.joybrush.bak")


def manifest(adb: Adb) -> dict:
    """Relative-to-device path -> {size, sha256} for every file under every saved root."""
    out = {}
    for root in SAVED_ROOTS:
        q = shlex.quote(root)
        listing = adb.call("shell", f"if [ -d {q} ]; then find {q} -type f -exec stat -c '%s %n' {{}} +; fi", timeout=60)
        sizes = {}
        for line in listing.splitlines():
            size, _, path = line.partition(" ")
            if path:
                sizes[path] = int(size)
        if not sizes:
            continue
        hashes = adb.call("shell", f"find {q} -type f -exec sha256sum {{}} +", timeout=240)
        for line in hashes.splitlines():
            digest, _, path = line.partition("  ")
            if path in sizes:
                out[path] = {"size": sizes[path], "sha256": digest}
        if set(sizes) - set(out):
            raise RunError(f"Could not hash every saved file under {root}.")
    return dict(sorted(out.items()))


def apk_receipt(path: Path):
    data = json.loads(path.read_text(encoding="utf-8"))
    source, apk, digest = data.get("Source"), Path(data.get("Path", "")), data.get("Sha256", "")
    with apk.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual != digest or data.get("exitCode") != 0:
        raise RunError("Preserved APK does not match its successful-build receipt.")
    return source, apk, digest


def run(out: Path, case: str, receipt: Path, install: bool, lane: str, timeout_s: int):
    if case not in CASES:
        raise RunError(f"Unsupported case; use one of {sorted(CASES)}.")
    if out.exists() and any(out.iterdir()):
        raise RunError("Use a fresh empty output directory.")
    out.mkdir(parents=True, exist_ok=True)
    source, apk, expected = apk_receipt(receipt)
    run_id = uuid.uuid4().hex
    adb = Adb(os.environ.get("SANDBOX_SERIAL", ""))
    adb.trace_path = out / "adb-calls.jsonl"
    claim = PhoneClaim(Path(os.environ["LOCALAPPDATA"]) / "Temp" / "jb-phone.lock", lane, run_id)
    launched = completed = False
    claim.acquire()
    try:
        model, api = adb.ready()
        temp_before = adb.temperature()
        saved_before = manifest(adb)
        for w in WORKING:
            if w not in saved_before:
                raise RunError(f"Working file missing before the run: {w}")
        installed_before = adb.installed_hash()
        if installed_before != expected:
            if not install:
                raise RunError("Installed APK differs from the preserved build; rerun with --install.")
            result = adb.call("install", "-r", str(apk), timeout=300)
            if "Success" not in result:
                raise RunError("adb install -r did not report Success.")
            installed_before = adb.installed_hash()
            if installed_before != expected:
                raise RunError("Installed APK still differs after install.")
        saved_after_install = manifest(adb)
        if saved_after_install != saved_before:
            raise RunError("Saved files changed during install; stopping before any probe.")
        # Fence: a line only this run writes, so the capture holds this launch's reports and nothing older.
        fence = f"jb-probe-fence {run_id}"
        adb.call("shell", "log", "-p", "i", "-t", "JoyBrushPerf", shlex.quote(fence))
        launched = True
        launch = adb.call("shell", "am", "start", "-W", "-n", ACTIVITY, "--es", "jb_bench", case,
                          "--es", "jb_bench_brushes", CASES[case], "--ei", "jb_bench_runs", "1", timeout=60)
        if "Error" in launch or "Exception" in launch:
            raise RunError("Bench launch not confirmed; inspect the phone before cleanup.")
        deadline = time.monotonic() + timeout_s
        captured = None
        while time.monotonic() < deadline:
            log = adb.call("logcat", "-b", "main", "-d", "-v", "raw", "-s", "JoyBrushPerf:I", timeout=20)
            if fence in log:
                after = log[log.rindex(fence) + len(fence):]
                if any(line.strip() == "done" for line in after.splitlines()):
                    captured = after
                    break
            time.sleep(1.0)
        if captured is None:
            raise RunError("Probe did not finish in time; claim retained until the phone state is resolved.")
        completed = True
        (out / "fenced-log.txt").write_text(captured.strip() + "\n", encoding="utf-8")
        adb.call("shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(3)
        installed_after = adb.installed_hash()
        saved_after = manifest(adb)
        temp_after = adb.temperature()
        receipt_out = dict(
            sourceRevision=source, apkSha256=expected, installedBeforeSha256=installed_before,
            installedAfterSha256=installed_after, model=model, api=api, runId=run_id, benchCase=case,
            brush=CASES[case], fence=fence, tempBeforeC=temp_before, tempAfterC=temp_after,
            savedBefore=saved_before, savedAfter=saved_after,
            requiredSavedPaths=list(WORKING), savedFiles=len(saved_before),
            savedUnchanged=saved_before == saved_after)
        (out / "host-receipt.json").write_text(json.dumps(receipt_out, indent=2) + "\n", encoding="utf-8")
        if installed_after != expected:
            raise RunError("Installed APK changed during the probe; evidence rejected.")
        if saved_before != saved_after:
            changed = sorted(p for p in set(saved_before) | set(saved_after) if saved_before.get(p) != saved_after.get(p))
            (out / "saved-changes.json").write_text(json.dumps(changed, indent=2), encoding="utf-8")
            raise RunError(f"Saved files changed during the probe ({len(changed)}: see saved-changes.json); evidence rejected.")
        print(f"CAPTURED: {case} run {run_id}; {len(saved_before)} saved files unchanged; receipt {out / 'host-receipt.json'}")
    finally:
        if not launched or completed:
            if not claim.release():
                print("Phone claim was not safely released; inspect before cleanup.", file=sys.stderr)
        else:
            print("Phone claim retained: launched probe unresolved.", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out", type=Path)
    parser.add_argument("case")
    parser.add_argument("--receipt", type=Path, required=True, help="preserved APK receipt (NATIVE_APK_*.json)")
    parser.add_argument("--install", action="store_true", help="adb install -r the preserved APK if the phone differs")
    parser.add_argument("--lane", default="Claude JoyBrush Lead")
    parser.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args()
    try:
        run(args.out, args.case, args.receipt, args.install, args.lane, args.timeout)
    except (RunError, OSError, ValueError, KeyError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
