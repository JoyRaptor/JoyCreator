#!/usr/bin/env python3
"""
Did a test session change project SETTINGS it had no business changing?

WHY THIS EXISTS. On 2026-08-26 a preview-rendering change made JoyRaptor's slide clip render
square, and the reasonable fear was that it had written a square canvas into the project
file — where reverting the code would NOT put it back. It had not (every canvasPreset on
the device still read "original"), but proving that took a hunt, and "did my testing damage
my project?" should be one command, before and after, on any device.

WHAT IT WATCHES. Settings a rendering or playback change must never touch: the canvas
preset, and each clip's crop / rotation / flip / speed / zoom. Deliberate edits move these
too, so this is not a correctness oracle — it is a DIFF you read. It tells you what moved;
you decide whether you moved it.

WHAT IT DELIBERATELY IGNORES. Clip count, trim points, overlays, undo history. Editing
changes those constantly and flagging them would bury the signal.

    snapshot <serial> baseline.json     before a test session
    check    <serial> baseline.json     after — exit 1 if any watched setting moved
    show     baseline.json              print what was captured

Reads through `run-as`, so it needs a debuggable build and no root.
"""

import argparse
import json
import subprocess
import sys

PKG = "com.fadcam.beta"
ROOT = "files/faditor/projects"

# Per-clip settings that a preview/playback change has no business writing.
CLIP_KEYS = ["cropPreset", "cropLeft", "cropTop", "cropRight", "cropBottom",
             "rotationDegrees", "flipHorizontal", "flipVertical",
             "speedMultiplier", "zoomLevel", "zoomCenterX", "zoomCenterY"]
PROJECT_KEYS = ["canvasPreset"]


def _adb():
    """adb is often not on PATH on this machine; ADB env var wins, then the usual SDK spot."""
    import os
    cand = [os.environ.get("ADB")] if os.environ.get("ADB") else []
    cand.append(os.path.expanduser("~/AppData/Local/Android/Sdk/platform-tools/adb.exe"))
    cand.append(os.path.expanduser("~/Android/Sdk/platform-tools/adb"))
    cand.append("adb")
    for c in cand:
        if c and (os.path.isfile(c) or c == "adb"):
            return c
    return "adb"


def _sh(serial, cmd):
    return subprocess.run([_adb(), "-s", serial, "exec-out", cmd],
                          check=True, stdout=subprocess.PIPE).stdout


def _projects(serial):
    out = _sh(serial, f"run-as {PKG} ls {ROOT}").decode("utf-8", "replace")
    return [p.strip() for p in out.splitlines() if p.strip()]


def snapshot(serial):
    snap = {}
    for pid in _projects(serial):
        try:
            raw = _sh(serial, f"run-as {PKG} cat {ROOT}/{pid}/project.json")
            doc = json.loads(raw.decode("utf-8", "replace"))
        except Exception as e:
            snap[pid] = {"error": f"unreadable: {type(e).__name__}"}
            continue
        tl = doc.get("timeline") or doc
        entry = {k: doc.get(k) for k in PROJECT_KEYS}
        entry["clips"] = [
            {k: c.get(k) for k in CLIP_KEYS} for c in (tl.get("clips") or [])
        ]
        entry["name"] = doc.get("name") or doc.get("title")
        snap[pid] = entry
    return snap


def diff(base, now):
    """Return a list of human-readable changes to WATCHED settings only."""
    out = []
    for pid, b in base.items():
        n = now.get(pid)
        if n is None:
            out.append(f"{pid}: PROJECT MISSING (was present at baseline)")
            continue
        if "error" in b or "error" in n:
            continue
        for k in PROJECT_KEYS:
            if b.get(k) != n.get(k):
                out.append(f"{pid} [{b.get('name')}]: {k} {b.get(k)!r} -> {n.get(k)!r}")
        bc, nc = b.get("clips") or [], n.get("clips") or []
        # A different clip count is editing, not damage — compare the overlap only, and
        # say so, rather than reporting every subsequent clip as changed.
        if len(bc) != len(nc):
            out.append(f"{pid} [{b.get('name')}]: clip count {len(bc)} -> {len(nc)} "
                       f"(editing; comparing the first {min(len(bc), len(nc))})")
        for i in range(min(len(bc), len(nc))):
            for k in CLIP_KEYS:
                if bc[i].get(k) != nc[i].get(k):
                    out.append(f"{pid} [{b.get('name')}] clip {i}: "
                               f"{k} {bc[i].get(k)!r} -> {nc[i].get(k)!r}")
    for pid in now:
        if pid not in base:
            out.append(f"{pid}: new project since baseline (ignored)")
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("snapshot"); s.add_argument("serial"); s.add_argument("out")
    c = sub.add_parser("check"); c.add_argument("serial"); c.add_argument("baseline")
    w = sub.add_parser("show"); w.add_argument("baseline")
    a = ap.parse_args()

    if a.cmd == "snapshot":
        snap = snapshot(a.serial)
        with open(a.out, "w", encoding="utf-8") as f:
            json.dump(snap, f, indent=1)
        clips = sum(len(v.get("clips") or []) for v in snap.values())
        print(f"baseline: {len(snap)} projects, {clips} clips -> {a.out}")
        return 0

    if a.cmd == "show":
        base = json.load(open(a.baseline, encoding="utf-8"))
        for pid, v in base.items():
            print(f"{pid} [{v.get('name')}] canvasPreset={v.get('canvasPreset')!r} "
                  f"clips={len(v.get('clips') or [])}")
        return 0

    base = json.load(open(a.baseline, encoding="utf-8"))
    changes = diff(base, snapshot(a.serial))
    if not changes:
        print(f"UNCHANGED  no watched setting moved across {len(base)} projects")
        return 0
    print(f"CHANGED  {len(changes)} watched setting(s) moved:")
    for line in changes:
        print("   " + line)
    print("\nThis is a diff, not a verdict: an edit you made on purpose looks the same as")
    print("damage. Read it and decide.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
