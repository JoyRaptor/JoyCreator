#!/usr/bin/env python3
"""Runs the JoyBrush device matrix (MatrixBench) on the Galaxy Note 9 (model SM-N960U) and writes one merged JSON file.

    python tools/device-matrix/run_matrix.py OUT.json [--apk PATH] [--no-install] [--boards canvas,tile] [--brushes all|id,id]

What it does, in order: picks the one device whose model is SM-N960U (by serial, never printed; any other phone is left
alone); presses HOME if com.fadcam.beta is the resumed screen (an install kills an unsaved drawing); `adb install -r` of the
arm64 debug APK (never uninstall, never clear data); starts `JoyBrushBenchActivity` with `jb_bench=matrix` (am start, no
monkey); follows logcat tag JoyBrushPerf until the `done` line; if the app dies mid-matrix it records the cell it died in as
a crash (with the crash buffer's first lines) and restarts from the next cell; presses HOME at the end.
"""
import argparse, hashlib, json, os, re, subprocess, sys, time

PACKAGE = "com.fadcam.beta"
ACTIVITY = PACKAGE + "/com.fadcam.joybrush.JoyBrushBenchActivity"
MODEL = "SM-N960U"
ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
APK = os.path.join(os.path.dirname(__file__), "..", "..", "app", "build", "outputs", "apk", "default", "debug",
                   "app-default-arm64-v8a-debug.apk")


def run(args, serial=None, check=True, timeout=600):
    cmd = [ADB] + (["-s", serial] if serial else []) + args
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError("adb %s failed: %s" % (" ".join(args[:2]), r.stderr.strip()[:300]))
    return r.stdout.replace("\r", "")


def find_note9():
    found = None
    for line in run(["devices"]).splitlines()[1:]:
        parts = line.split()
        if len(parts) == 2 and parts[1] == "device":
            if run(["shell", "getprop", "ro.product.model"], parts[0]).strip() == MODEL:
                found = parts[0]
    if not found:
        sys.exit("no connected device with model " + MODEL)
    return found


def resumed_is_ours(serial):
    out = run(["shell", "dumpsys", "activity", "activities"], serial, check=False)
    return any(PACKAGE in l for l in out.splitlines() if "mResumedActivity" in l or "ResumedActivity" in l)


def keyguard_showing(serial):
    out = run(["shell", "dumpsys", "window"], serial, check=False)
    m = re.search(r"isKeyguardShowing=(\w+)|mShowingLockscreen=(\w+)|KeyguardShowing=(\w+)", out)
    return bool(m) and "true" in m.group(0)


def pid(serial):
    return run(["shell", "pidof", PACKAGE], serial, check=False).strip()


def parse(text):
    rows = []
    for line in text.splitlines():
        i = line.find("{")
        if i < 0 or '"case":"matrix"' not in line:
            continue
        try:
            rows.append(json.loads(line[i:]))
        except ValueError:
            pass
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--apk", default=APK)
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--boards", default="")
    ap.add_argument("--brushes", default="")
    ap.add_argument("--limit-minutes", type=int, default=75)
    a = ap.parse_args()

    serial = find_note9()
    meta = {"device": MODEL, "date": time.strftime("%Y-%m-%d %H:%M:%S")}
    if os.path.exists(a.apk):
        meta["apkSha256"] = hashlib.sha256(open(a.apk, "rb").read()).hexdigest()
    meta["androidRelease"] = run(["shell", "getprop", "ro.build.version.release"], serial).strip()
    meta["batteryTempBefore"] = re.findall(r"temperature: (\d+)", run(["shell", "dumpsys", "battery"], serial))[:1]

    if not a.no_install:
        if resumed_is_ours(serial):
            run(["shell", "input", "keyevent", "KEYCODE_HOME"], serial)
            time.sleep(5)
        print("install:", run(["install", "-r", a.apk], serial).strip().splitlines()[-1])
    run(["shell", "input", "keyevent", "KEYCODE_WAKEUP"], serial)
    if keyguard_showing(serial):
        sys.exit("the phone is locked; unlock it and run again")

    run(["logcat", "-G", "16M"], serial, check=False)
    run(["logcat", "-b", "all", "-c"], serial, check=False)
    rows, plan, start_from, t_end, crashes = [], [], 0, time.time() + a.limit_minutes * 60, 0
    while True:
        cmd = ["shell", "am", "start", "-n", ACTIVITY, "--es", "jb_bench", "matrix", "--ei", "jb_matrix_from", str(start_from)]
        if a.boards:
            cmd += ["--es", "jb_matrix_boards", a.boards]
        if a.brushes:
            cmd += ["--es", "jb_bench_brushes", a.brushes]
        run(["logcat", "-c"], serial, check=False)
        run(cmd, serial)
        seen_pid, done, last = "", False, ""
        while time.time() < t_end:
            time.sleep(6)
            text = run(["logcat", "-d", "-v", "raw", "-s", "JoyBrushPerf:I"], serial, check=False)
            got = parse(text)
            last = text
            if any(r.get("event") == "done" for r in got):
                done = True
                break
            now = pid(serial)
            if not seen_pid and now and any(r.get("event") == "start" for r in got):
                seen_pid = now
            if seen_pid and now != seen_pid:
                break  # the process is gone (or restarted): a crash
        got = parse(last)
        plan = plan or [r for r in got if r.get("event") == "plan"][:1]
        results = {r["idx"]: r for r in got if "idx" in r and r.get("event") != "start" and "verdict" in r}
        starts = {r["idx"]: r for r in got if r.get("event") == "start"}
        for idx in sorted(results):
            if idx >= start_from and idx not in [r["idx"] for r in rows]:
                rows.append(results[idx])
        if done:
            break
        if time.time() >= t_end:
            print("time limit reached")
            break
        # the process died: the highest started cell without a result is the one that did it
        pending = [i for i in sorted(starts) if i not in results and i >= start_from]
        crash = run(["logcat", "-b", "crash", "-d", "-v", "brief"], serial, check=False).strip().splitlines()
        head = " / ".join(l.split(": ", 1)[-1] for l in crash[:6])[:700]
        if not pending:
            print("app died before any cell started; stopping")
            break
        victim = pending[-1]
        s = starts[victim]
        rows.append({"case": "matrix", "idx": victim, "brush": s["brush"], "board": s["board"], "verdict": "crash",
                     "error": "process died: " + head, "refused": None, "changedPx": None, "undoExact": None, "p50": None, "p95": None})
        crashes += 1
        print("cell %d (%s on %s) crashed; resuming at %d" % (victim, s["brush"], s["board"], victim + 1))
        start_from = victim + 1
        run(["logcat", "-b", "crash", "-c"], serial, check=False)
        if crashes > 40:
            break
    run(["shell", "input", "keyevent", "KEYCODE_HOME"], serial)
    meta["batteryTempAfter"] = re.findall(r"temperature: (\d+)", run(["shell", "dumpsys", "battery"], serial))[:1]
    rows.sort(key=lambda r: r["idx"])
    json.dump({"meta": meta, "plan": plan, "cells": rows}, open(a.out, "w"), indent=1)
    verdicts = {}
    for r in rows:
        verdicts[r["verdict"]] = verdicts.get(r["verdict"], 0) + 1
    print("cells: %d  %s  -> %s" % (len(rows), verdicts, a.out))


if __name__ == "__main__":
    main()
