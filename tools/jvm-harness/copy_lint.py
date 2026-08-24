"""
Copy lint: every field must travel with the clip when it is copied.

WHY THIS EXISTS. This is the fourth question in the same family, and the fourth to find
something. The others ask: is this class ever constructed (orphan_lint), is this flag ever
read (deadstate_lint), does this field reach the disk (persist_lint). This one asks whether
a field survives being COPIED.

It matters because the copy constructor is what SPLIT uses. FaditorEditorActivity builds
both halves of a split with `new AudioClip(ac)`, so a field missing from that constructor
is a setting the user loses the moment they cut a clip in two -- silently, with no error
and nothing on screen to say it happened.

It found `pan` on its first run, which had ALSO been missing from ProjectStorage until the
day before: the same setting was being lost on save and on split, by two unrelated
mechanisms, and neither one was visible to any behavioural test. That is the argument for
asking the question structurally instead of hoping someone notices.

A field may be listed in EXPECTED with a reason. "A copy should not inherit it" is a real
reason and should say why.

Usage: python tools/jvm-harness/copy_lint.py
Exits 1 if any field is dropped by a copy constructor.
"""
import io
import re
import sys

# (label, path, copy-constructor signature fragment)
TARGETS = [
    ("AudioClip", "app/src/main/java/com/fadcam/ui/faditor/model/AudioClip.java",
     "public AudioClip(@NonNull AudioClip other)"),
    ("Clip", "app/src/main/java/com/fadcam/ui/faditor/model/Clip.java",
     "public Clip(@NonNull Clip other)"),
]

# label -> {field: reason}
EXPECTED = {
    "AudioClip": {
        "id": "a copy gets a fresh id by design; inheriting it would duplicate identity.",
        "locked": "a lock is a workspace state, not clip content -- deliberately not "
                  "inherited by a copy. Flagged rather than silently allowed: if splitting "
                  "a locked clip should keep both halves locked, this is the line to change.",
    },
    "Clip": {
        "locked": "as AudioClip.locked",
        "id": "a copy MUST get a fresh id -- Clip(other, newId) assigns one by design. "
              "Inheriting it would give two clips the same identity.",
        # ── RULED, not silently allowed ───────────────────────────────────────────────
        # Ruled 2026-08-24 from the code, both directions traced. Kept here so the
        # exemption stays visible and re-litigable; do not delete without re-reading
        # Timeline.linkClips/findLinkedClip and splitLinkedPartnerAndRecord.
        "linkedClipId": "DUAL-STREAM partner pointer (not G9 membership -- link groups live "
                        "in Timeline.linkGroups keyed by item id, so a fresh-id split half "
                        "is simply not a member and pruneLinkGroups handles the rest; no "
                        "(item, axis) duplicate can arise from this field). Copying it "
                        "would make BOTH halves point at one partner while the partner "
                        "still points at the dead original id: findLinkedClip becomes "
                        "one-way and ambiguous (two clips resolve to the same partner). "
                        "The design is 'fresh-id child is independent until the operation "
                        "re-links it' (Clip.linkedClipId javadoc), and the linked-pair "
                        "split path does exactly that: splitLinkedPartnerAndRecord builds "
                        "unlinked halves then calls Timeline.linkClips pairwise "
                        "(masterA<->left, masterB<->right). Both halves stay out of the "
                        "pair until the operation re-links them -- by design, keep exempt.",
    },
}

FIELD_RE = re.compile(r"^\s{4}private [\w.<>\[\], ]+?\s+(\w+)\s*(?:=|;)")
COMMENT_RE = re.compile(r"//[^\n]*|/\*.*?\*/", re.S)


def fields_of(text):
    """Declared instance fields, excluding transient (not state worth carrying)."""
    out = []
    for line in text.split("\n"):
        m = FIELD_RE.match(line)
        if not m or " transient " in line:
            continue
        if m.group(1) not in out:
            out.append(m.group(1))
    return out


def copies_in(text):
    """Every field this class copies FROM another instance, anywhere in the file.

    Deliberately not scoped to one constructor's body. Scoping it meant following
    delegation — Clip(Clip other) is three lines that call Clip(Clip other, String newId)
    — and two attempts at that produced false alarms of 55 fields on a class that copies
    nearly all of them. A false alarm that large is worse than no lint: nobody reads the
    real finding inside it.

    The question that actually matters is "is this field ever carried from one instance to
    another", and that is answerable without knowing which constructor did it. It can
    over-count — a copy in some unrelated method counts — and over-counting is the safe
    direction here: it risks missing a dropped field, never inventing one.
    """
    copied = set()
    for m in re.finditer(r"(?:this\.)?(\w+)\s*=\s*other\.(\w+)", text):
        copied.add(m.group(1))
        copied.add(m.group(2))
    # Collections are filled by looping over other.field rather than assigned.
    for m in re.finditer(r"other\.(\w+)", text):
        copied.add(m.group(1))
    return copied


def main():
    missing, checked, exempt = [], 0, 0
    for label, path, sig in TARGETS:
        raw = io.open(path, encoding="utf-8", errors="replace").read()
        text = COMMENT_RE.sub(" ", raw)
        if sig not in text:
            print("FAIL  %s has no copy constructor matching %r" % (label, sig))
            sys.exit(1)
        copied = copies_in(text)
        allowed = EXPECTED.get(label, {})
        for f in fields_of(text):
            if f in allowed:
                exempt += 1
                continue
            checked += 1
            if f not in copied:
                missing.append((label, f))
        print("%-10s %d fields checked, %d exempt" % (label, checked, len(allowed)))

    if missing:
        print()
        for label, f in missing:
            print("FAIL  %s.%s is DROPPED by the copy constructor" % (label, f))
        print()
        print("The copy constructor is what SPLIT uses. A field missing from it is a")
        print("setting the user loses the moment they cut a clip in two -- silently.")
        print("Copy it, or list it in EXPECTED with a reason a copy should not inherit it.")
        print("%d DROPPED" % len(missing))
        sys.exit(1)

    # Positive control: the matcher must be able to say no. Without it, a matcher that
    # returned every name would report ALL COPIED forever.
    probe = copies_in(io.open(TARGETS[0][1], encoding="utf-8").read())
    if "definitelyNotAFieldName" in probe:
        print("FAIL  positive control matched an invented field -- matcher is broken")
        sys.exit(1)
    print("PASS  positive control: an invented field is correctly reported missing")
    print("ALL COPIED")


main()
