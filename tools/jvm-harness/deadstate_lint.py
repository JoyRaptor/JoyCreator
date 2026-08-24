"""
Dead-state lint: cross-layer state that is written and never read.

WHY THIS EXISTS. The orphan lint asks "does this class connect to anything?". This asks
the dual, and it is the shape of every dead CONTROL JoyRaptor hit on the device:

  - C7's A/B bypass set AudioDrawerTabs.fxChainBypassed, and nothing read it
  - preSoloMuted() captured the pre-solo mutes, and nothing read them, so clearing a
    solo restored the silence instead of undoing it
  - C6's gain-reduction bar had reportGainReductionDb(), and nothing called it
  - "Clean Audio" was a checkbox whose value reached no engine

Every one of them looked finished. The control was there, the tap did something, a field
changed -- and the far end was missing. A user cannot tell that apart from a bug, and
neither can a compiler: a written-but-never-read field is perfectly legal Java.

This targets PUBLIC STATIC MUTABLE fields specifically, because that is how this codebase
passes state across the UI/engine boundary, and a boundary is where a wire goes missing.

A field may be listed in EXPECTED with a reason naming what will read it.

Usage: python tools/jvm-harness/deadstate_lint.py
Exits 1 if any such field is written but never read elsewhere.
"""
import io
import os
import re
import sys

SRC = "app/src/main/java"

WATCHED_DIRS = [
    "com/fadcam/ui/faditor/tools",
    "com/fadcam/ui/faditor/audio",
    "com/fadcam/ui/faditor/audio/fx",
    "com/fadcam/ui/faditor/layers",
    "com/fadcam/ui/faditor/export",
]

# field -> why nothing reads it yet. Name the row that will.
EXPECTED = {}

COMMENT_RE = re.compile(r"//[^\n]*|/\*.*?\*/", re.S)

# public static, not final, with a simple type. Excludes constants, which are read-only
# by construction and not a wiring risk.
FIELD_RE = re.compile(
    r"^\s*public\s+static\s+(?!final\b)(?:volatile\s+)?"
    r"(boolean|int|long|float|double|String)\s+(\w+)\s*(?:=|;)", re.M)


def norm(p):
    return p.replace("\\", "/")


def strip_comments(t):
    return COMMENT_RE.sub(" ", t)


def main():
    code = {}
    for root, _d, files in os.walk(SRC):
        for f in files:
            if f.endswith(".java"):
                p = norm(os.path.join(root, f))
                code[p] = strip_comments(
                    io.open(p, encoding="utf-8", errors="replace").read())

    watched_files = []
    for rel in WATCHED_DIRS:
        d = os.path.join(SRC, rel)
        if not os.path.isdir(d):
            continue
        for name in sorted(os.listdir(d)):
            if name.endswith(".java"):
                watched_files.append(norm(os.path.join(d, name)))

    dead, checked, exempt = [], 0, 0
    for path in watched_files:
        own = code[path]
        cls = os.path.basename(path)[:-5]
        for _type, field in FIELD_RE.findall(own):
            if field in EXPECTED:
                exempt += 1
                continue
            checked += 1

            # A READ is any mention that is not an assignment to the field. Qualified
            # (Cls.field) or bare inside the declaring class.
            write_re = re.compile(
                r"(?:\b" + re.escape(cls) + r"\s*\.\s*)?\b" + re.escape(field)
                + r"\s*(?:=[^=]|\+\+|--|\+=|-=)")
            any_re = re.compile(
                r"\b" + re.escape(cls) + r"\s*\.\s*" + re.escape(field) + r"\b")

            # ONE pattern for every file, no branching on which file we are in.
            #
            # The previous version used a different regex for the declaring file than for
            # the rest, and the declaring-file branch silently matched nothing — so
            # reportedGainReductionDb, which IS read at AudioDrawerTabs:685, was reported
            # dead. A lint whose two code paths disagree is worse than a blunt one: this
            # matches the bare name anywhere, qualified or not, and simply excludes
            # assignments. It can over-count (a local variable of the same name would look
            # like a read), and over-counting is the safe direction — it risks missing a
            # dead field, never inventing one.
            read_re = re.compile(
                r"(?<![\w.])(?:" + re.escape(cls) + r"\s*\.\s*)?"
                + re.escape(field) + r"(?![\w])")
            reads = 0
            for p, t in code.items():
                for m in read_re.finditer(t):
                    if re.match(r"\s*(?:=[^=]|\+\+|--|\+=|-=)", t[m.end():m.end() + 3]):
                        continue          # an assignment, not a read
                    reads += 1
            if reads == 0:
                # Written somewhere outside? Then it is genuinely dead state, not merely
                # an unused field.
                written_elsewhere = any(
                    p != path and write_re.search(t) for p, t in code.items())
                dead.append((cls, field, "written elsewhere, read nowhere"
                             if written_elsewhere else "read nowhere at all"))

    print("checked %d public-static-mutable fields, %d exempt" % (checked, exempt))

    if dead:
        print()
        for cls, field, why in dead:
            print("FAIL  %s.%s is %s" % (cls, field, why))
        print()
        print("State written and never read is a control whose far end is missing. The user")
        print("taps it, something changes, and nothing happens -- indistinguishable from a")
        print("bug, and perfectly legal Java. Wire the reader, or list it in EXPECTED with")
        print("the row that will read it.")
        print("%d DEAD" % len(dead))
        sys.exit(1)

    print("PASS  every cross-layer flag has a reader")
    print("ALL READ")


main()
