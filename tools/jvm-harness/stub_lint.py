"""
Stub lint: an interface method that one implementer silently does nothing for.

WHY THIS EXISTS. AudioParams is the shared contract that lets a VIDEO clip's audio use
the same drawer, the same export chain and the same envelope machinery an AUDIO clip
uses. Whenever one side implements a method for real and the other returns a constant or
does nothing, the feature exists for one clip type and silently does not exist for the
other -- and every layer above sees one interface and cannot tell them apart.

That is not hypothetical. Clip.setPan() is a no-op and Clip.getPan() returns 0, so the
pan slider shown for a video clip moved, displayed a value, and threw it away. Nothing
above the model could have known: it was talking to AudioParams.

A no-op is sometimes the honest answer -- a video clip has no lane of its own, so
setLayerId doing nothing is correct. What is not acceptable is a no-op nobody wrote down,
because the layer above will offer the user a control for it. So each one must be listed
in EXPECTED with a reason, and that list is the inventory of "what a video clip cannot
do" that no other document holds.

Usage: python tools/jvm-harness/stub_lint.py
Exits 1 if any implementer stubs a contract method without a recorded reason.
"""
import io
import re
import sys

INTERFACE = "app/src/main/java/com/fadcam/ui/faditor/model/AudioParams.java"
IMPLS = [
    ("AudioClip", "app/src/main/java/com/fadcam/ui/faditor/model/AudioClip.java"),
    ("Clip", "app/src/main/java/com/fadcam/ui/faditor/model/Clip.java"),
]

# "Class.method" -> why doing nothing is correct here.
EXPECTED = {
    "Clip.getPan": "a video clip has no stereo pan of its own; pan lives on AudioClip. "
                   "The drawer must therefore NOT offer a pan row for a video clip — see "
                   "AudioDrawerTabs.levelTab(allowPan) and the bug it was written for.",
    "Clip.setPan": "as Clip.getPan.",
    "Clip.isBakedSource": "baking is an audio-clip operation; a video clip is never a "
                          "baked audio source.",
    "Clip.getBakedFromUri": "as Clip.isBakedSource.",
    "Clip.getBakedFromFile": "as Clip.isBakedSource.",
    "Clip.setBakedFrom": "as Clip.isBakedSource.",
    "Clip.getOffsetMs": "master-clip position is DERIVED by summing prior clip durations "
                        "(Timeline.getMasterTrack), so there is no stored offset to return. "
                        "The field was deleted as dead state in ff793784.",
    "Clip.setOffsetMs": "as Clip.getOffsetMs.",
}

DECL_RE = re.compile(r"^\s+(?:@\w+\s+)*[\w<>?,.\[\] ]+?\s+(\w+)\s*\([^)]*\)\s*;\s*$", re.M)
COMMENT_RE = re.compile(r"//[^\n]*|/\*.*?\*/", re.S)

# A body that does nothing meaningful: empty, or a single `return <constant>;`.
STUB_BODY = re.compile(
    r"^\s*(?:return\s+(?:0|0f|0L|0\.0f?|null|false|true|\"\")\s*;)?\s*$")


def contract():
    text = COMMENT_RE.sub(" ", io.open(INTERFACE, encoding="utf-8").read())
    names = []
    for m in DECL_RE.finditer(text):
        if m.group(1) not in names:
            names.append(m.group(1))
    return names


def body_of(text, method):
    """Body of `method` in `text`, or None if not declared there."""
    pat = re.compile(r"\b(?:public|protected|private)\s+[\w<>?,.\[\] ]+?\s+"
                     + re.escape(method) + r"\s*\([^)]*\)\s*\{")
    m = pat.search(text)
    if not m:
        return None
    depth, start = 0, m.end() - 1
    for j in range(start, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:j]
    return None


def main():
    methods = contract()
    print("contract: %d methods" % len(methods))
    stubs, checked, exempt = [], 0, 0

    for label, path in IMPLS:
        text = COMMENT_RE.sub(" ", io.open(path, encoding="utf-8").read())
        n = 0
        for meth in methods:
            body = body_of(text, meth)
            if body is None:
                continue          # inherited or declared elsewhere; not this file's claim
            key = label + "." + meth
            if key in EXPECTED:
                exempt += 1
                continue
            n += 1
            checked += 1
            if STUB_BODY.match(body):
                stubs.append((key, body.strip() or "<empty>"))
        print("%-10s %d contract methods implemented and checked" % (label, n))

    if stubs:
        print()
        for key, body in stubs:
            print("FAIL  %s does NOTHING: %s" % (key, body))
        print()
        print("An interface method one implementer stubs is a feature that exists for one")
        print("clip type and silently does not for the other. Every layer above sees one")
        print("interface and cannot tell them apart, so it will offer the user a control")
        print("that quietly discards their input — exactly what Clip.setPan did.")
        print("Implement it, or record it in EXPECTED with the reason it cannot exist.")
        print("%d SILENT STUBS" % len(stubs))
        sys.exit(1)

    # Positive control: the stub matcher must be able to say yes. If it cannot recognise
    # an obvious stub, "no silent stubs" means only that the matcher is broken.
    for probe in ["return 0f;", "", "  return null; "]:
        if not STUB_BODY.match(probe):
            print("FAIL  positive control: %r not recognised as a stub" % probe)
            sys.exit(1)
    if STUB_BODY.match("return computeSomething();"):
        print("FAIL  positive control: a real body was called a stub")
        sys.exit(1)
    print("PASS  positive control: stubs recognised, real bodies not")
    print("%d checked, %d recorded as intentional" % (checked, exempt))
    print("NO SILENT STUBS")


main()
