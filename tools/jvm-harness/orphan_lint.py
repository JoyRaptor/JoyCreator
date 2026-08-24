"""
Orphan lint: an engine class that nothing constructs is not a feature.

WHY THIS EXISTS. The most expensive bug of the night was not a wrong algorithm. It was
ResamplingAudioProcessor: correct, harness-proven, committed, shipped -- and constructed
by absolutely nothing. A6 read BUILT for hours, logged nothing, and changed no exported
file, because the class had no caller. The harnesses all passed the whole time, because a
harness constructs the class itself and therefore cannot notice that the app never does.

Every other bug that cost real time had the same shape:
  - preSoloMuted() was written once and never read, so clearing a solo restored silence
  - masterTrackId scanned a list the master was never in, so it stayed null forever
  - Clip.VolumeKeyframe re-declared its inherited fields, so writes went one place and
    reads came from another
  - the master row offered a Solo menu entry that toggleTrackSolo ignored

All of them are one question: does the thing that exists actually connect to anything?
Algorithm tests cannot see it. This can.

A class may be listed in EXPECTED_ORPHANS only with a reason. "It will be used later" is
a reason, and it should name the row that will use it.

Usage: python tools/jvm-harness/orphan_lint.py
Exits 1 if any engine class is constructed nowhere.
"""
import io
import os
import re
import sys

SRC = "app/src/main/java"

# Directories whose classes are ENGINE code -- they exist to be called by something else.
WATCHED = [
    "com/fadcam/ui/faditor/audio",
    "com/fadcam/ui/faditor/audio/fx",
    "com/fadcam/ui/faditor/export",
]

# class -> why it has no constructor call in app source.
EXPECTED_ORPHANS = {
    "AudioReactiveLinker": "D8 engine, static-only; its door (choosing which band drives "
                           "which property) is explicitly owed and tracked on the D8 row.",
    "Ducker": "C5.E engine, static-only entry points; reached via DuckApplier and the "
              "'Duck under...' menu row, neither of which constructs it.",
}

# Comment stripper. A class named only inside a comment is not wired -- that is exactly
# how a stale reference outlives the code that used it.
COMMENT_RE = re.compile(r"//[^\n]*|/\*.*?\*/", re.S)


def strip_comments(text):
    return COMMENT_RE.sub(" ", text)


def norm(path):
    """One spelling for a path.

    os.walk yields backslash-separated paths on Windows while the WATCHED entries are
    written with forward slashes, so `os.path.join(SRC, rel)` produces a MIXED spelling
    that never equals the walked one. The self-exclusion `p != path` then failed for
    every class, each matched its OWN file, and the lint reported ALL WIRED while
    DeHumProcessor sat referenced nowhere. A false green in the very tool built to catch
    false greens -- normalise before comparing.
    """
    return path.replace("\\", "/")


def classes_in(rel_dir):
    d = os.path.join(SRC, rel_dir)
    if not os.path.isdir(d):
        return []
    out = []
    for name in sorted(os.listdir(d)):
        if name.endswith(".java"):
            out.append((name[:-5], norm(os.path.join(d, name))))
    return out


def main():
    # Read every app source file once.
    all_src = {}
    for root, _dirs, files in os.walk(SRC):
        for f in files:
            if f.endswith(".java"):
                p = os.path.join(root, f)
                all_src[norm(p)] = io.open(p, encoding="utf-8", errors="replace").read()

    # Strip comments once. A mention inside a comment does not wire anything.
    code = {p: strip_comments(t) for p, t in all_src.items()}

    orphans, checked, exempt = [], 0, 0
    for rel in WATCHED:
        for cls, path in classes_in(rel):
            if cls in EXPECTED_ORPHANS:
                exempt += 1
                continue
            checked += 1

            # ANY reference in another file counts: `new X(`, a static call `X.foo()`, a
            # type in a signature, or `X.class` in an Intent. Narrowing this to `new X(`
            # was wrong -- it reported six false orphans (AudioFxChainFactory, DuckApplier,
            # LoudnessAnalyzer, BakedAudioCache, VoiceoverRecorder, ImageOverlayDraw), all
            # of them static-only helpers with 2 to 13 real callers, plus ExportService
            # which Android constructs from an Intent. A lint with a 6-in-8 false-positive
            # rate is one people learn to skip, which makes it worse than nothing.
            pattern = re.compile(r"\b" + re.escape(cls) + r"\b")
            used = any(p != path and pattern.search(t) for p, t in code.items())
            if not used:
                orphans.append((cls, "reference", path))

    print("checked %d engine classes, %d exempt" % (checked, exempt))

    if orphans:
        print()
        for cls, kind, path in orphans:
            print("FAIL  %s is REFERENCED NOWHERE in app source (%s)" % (cls, kind))
            print("      %s" % path.replace("\\", "/"))
        print()
        print("An engine class nothing calls is not a feature -- it is a file. This is the")
        print("exact shape of A6: correct, harness-proven, shipped, and constructed by")
        print("nothing, so it changed no exported file for hours while reading BUILT.")
        print("Wire it, or list it in EXPECTED_ORPHANS with the row that will.")
        print("%d ORPHANED" % len(orphans))
        sys.exit(1)

    # Positive control: the search must be able to FAIL. A name that cannot exist must
    # come back unused, or the matcher is saying yes to everything.
    ghost = re.compile(r"\bnew\s+NoSuchProcessorXYZ\s*\(")
    if any(ghost.search(t) for t in all_src.values()):
        print("FAIL  positive control matched an invented class -- the matcher is broken")
        sys.exit(1)
    print("PASS  positive control: an invented class is correctly reported unused")
    print("ALL WIRED")


main()
