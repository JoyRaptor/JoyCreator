#!/usr/bin/env python3
"""One press, one undo step (the owner's rule, swept app-wide 11-24): a new control that changes a
project, a look or a sheet must record an undo step.

The check is a heuristic over methods. A method "changes the work" when it saves or tells its host
something changed (scheduleAutoSave, saveProjectNow, onChanged, edited, markDirty, knob, ...). It
"records" when it calls anything undo-shaped (record*, amend*, beginEdit, beginChange, Undo...,
vizUndoable, noteChange, gestureStep, markDirty in SpriteLab, which IS its undo step). A method that
changes and does not record is counted. Many counted methods are fine (the step is recorded by the
caller, it is housekeeping, or it is the undo itself), so this is a RATCHET, not a rule: each file
keeps its 11-24 count in tools/undo_steps_baseline.json and may only go down. A new file starts at 0.

When it fails: the method it names changes the work with no undo step in sight. Record one (or, if
a caller records it, say so in a comment that names the caller and add the word "undo" there).

    python -B tools/check_undo_steps.py            # fails if a file has more than its baseline
    python -B tools/check_undo_steps.py --list     # every counted method, file:line
    python -B tools/check_undo_steps.py --update   # after you FIXED some: lower the baseline
"""
import json
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
BASE = os.path.join(ROOT, "tools", "undo_steps_baseline.json")
ROOMS = [
    "app/src/main/java/com/fadcam/ui/faditor",          # Studio, its drawers, SpriteLab
    "app/src/main/java/com/fadcam/vislab/room",         # VisLab
    "app/src/main/java/cc/joycreator/textlab",          # TextLab
]
CHANGES = re.compile(r"\b(scheduleAutoSave|saveProjectNow|onChanged|edited|markDirty|markDirtyQuiet|labDirty\s*=\s*true)\b")
RECORDS = re.compile(r"\b(record[A-Z]\w*|recordAction|amend\w*|mergeNextIntoTop|beginEdit|endEdit|beginChange"
                     r"|roomWillEdit|remember|vizUndoable|noteChange|gestureStep|markDirty)\b|[Uu]ndo")
DECL = re.compile(r"^\s{4,8}(?:@\w+\s+)*(?:(?:public|private|protected|static|final|synchronized|default)\s+)*"
                  r"[\w<>\[\],.? ]+\s+(\w+)\s*\([^;]*$")
SKIP_NAMES = {"if", "for", "while", "switch", "catch", "return", "new", "else", "synchronized"}


def methods(path):
    lines = open(path, encoding="utf-8", errors="replace").read().split("\n")
    i = 0
    while i < len(lines):
        m = DECL.match(lines[i])
        if m and m.group(1) not in SKIP_NAMES and not lines[i].strip().startswith(("return", "new ", "//", "*")):
            depth, started, j = 0, False, i
            while j < len(lines):
                code = lines[j].split("//")[0]
                depth += code.count("{") - code.count("}")
                if "{" in code:
                    started = True
                if started and depth <= 0:
                    break
                j += 1
            yield m.group(1), i + 1, "\n".join(lines[i:j + 1])
            i = j + 1
        else:
            i += 1


def counted():
    out = {}
    for room in ROOMS:
        for dp, _, files in os.walk(os.path.join(ROOT, room)):
            for f in files:
                if not f.endswith(".java"):
                    continue
                p = os.path.join(dp, f)
                rel = os.path.relpath(p, ROOT).replace(os.sep, "/")
                for name, ln, body in methods(p):
                    # the changing call must be a call, not this method's own declaration
                    calls = [c for c in CHANGES.finditer(body) if body[c.end():c.end() + 2].lstrip().startswith(("(", "="))
                             or "labDirty" in c.group(0)]
                    if name in ("scheduleAutoSave", "saveProjectNow", "onChanged", "edited", "markDirty",
                                "markDirtyQuiet", "notifyChanged"):
                        continue
                    if calls and not RECORDS.search(body):
                        out.setdefault(rel, []).append((ln, name))
    return out


def main():
    now = counted()
    if "--list" in sys.argv:
        for f in sorted(now):
            for ln, name in now[f]:
                print("%s:%d %s" % (f, ln, name))
        return 0
    counts = {f: len(v) for f, v in now.items()}
    if "--update" in sys.argv:
        with open(BASE, "w", encoding="utf-8") as fh:
            json.dump(dict(sorted(counts.items())), fh, indent=1)
            fh.write("\n")
        print("baseline written: %d files, %d methods" % (len(counts), sum(counts.values())))
        return 0
    base = json.load(open(BASE, encoding="utf-8")) if os.path.exists(BASE) else {}
    bad = [(f, n, base.get(f, 0)) for f, n in sorted(counts.items()) if n > base.get(f, 0)]
    if bad:
        print("FAIL: a method changes the work with no undo step in sight (one press, one step):")
        for f, n, b in bad:
            print("  %s: %d (baseline %d)" % (f, n, b))
            for ln, name in now[f]:
                print("      line %d %s" % (ln, name))
        print("Record a step, or note the caller that records it (with the word 'undo') in that method.")
        return 1
    print("ok: %d methods change the work without an undo step in sight, in %d files (baseline)"
          % (sum(counts.values()), len(counts)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
