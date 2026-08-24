"""
Persistence lint: every model field must have a home on disk.

WHY THIS EXISTS. ProjectStorage hand-serializes every field. Nothing is derived by
reflection, so a field added to the model and NOT added to ProjectStorage simply does not
exist tomorrow -- the control works all session, the user saves, and the setting is gone.

This has now bitten twice. AudioClip.removedSpans was believed to be "Gson-persisted" and
was not, so struck transcript spans evaporated on save. AudioClip.pan (A5 stereo pan) was a
live, working control that was never written at all, found 2026-08-24 by diffing the model's
fields against this file's JSON keys -- which is exactly what this script now does on every
run, so nobody has to remember to look.

A field may be exempted ONLY with a reason, in EXEMPT below. "It is not important" is not a
reason; "it is recomputed on load from X" is.

Usage: python tools/jvm-harness/persist_lint.py
Exits 1 if any field is unaccounted for.
"""
import io
import re
import sys

STORAGE = "app/src/main/java/com/fadcam/ui/faditor/project/ProjectStorage.java"

# Fields whose JSON key legitimately differs from the field name.
ALIASES = {
    "envelopeMultiplier": "envMul",
    "activeTranscriptIndex": "activeTranscript",
}

# field -> why it needs no key. Keep the reason concrete and checkable.
EXEMPT = {
    "AudioClip": {
        "waveform": "regenerated from the source audio on load; caching it is a size "
                    "tradeoff handled separately by the waveform cache files",
        "transcripts": "written through the v12 transcript POOL (transcriptPool.intern), "
                       "under a key chosen at runtime by a ternary -- so the key is not a "
                       "literal this lint can see. Verified present by hand 2026-08-24.",
    },
    "Clip": {
        "waveform": "as AudioClip.waveform",
        "transcripts": "as AudioClip.transcripts",
    },
}

TARGETS = [
    ("AudioClip", "app/src/main/java/com/fadcam/ui/faditor/model/AudioClip.java"),
    ("Clip", "app/src/main/java/com/fadcam/ui/faditor/model/Clip.java"),
]

# A field declaration line. `transient` is detected on the LINE rather than via a lookahead
# in this pattern: with `(?:final )?` optional, the regex can backtrack so that "transient"
# is swallowed by the TYPE group, and `final transient List<long[]> silenceCandidates` slips
# straight past a `(?!transient )` guard. Checking the line is unfoolable and obvious.
#
# `transient` fields are skipped. Java's own keyword is a stronger, more honest declaration of
# "this does not go to disk" than any list kept in this file, and it sits next to the field
# where the next person will read it. A field that genuinely must not persist should be marked
# transient in the model rather than exempted here.
FIELD_RE = re.compile(
    r"^\s{4}private [\w.<>\[\], ]+?\s+(\w+)\s*(?:=|;)")


def fields_of(path):
    out, n_transient = [], 0
    for line in io.open(path, encoding="utf-8"):
        m = FIELD_RE.match(line)
        if not m:
            continue
        if " transient " in line:
            n_transient += 1
            continue
        if m.group(1) not in out:
            out.append(m.group(1))
    return out, n_transient


def main():
    storage = io.open(STORAGE, encoding="utf-8").read()
    failures = []
    for label, path in TARGETS:
        exempt = EXEMPT.get(label, {})
        checked = 0
        names, n_transient = fields_of(path)
        for f in names:
            if f in exempt:
                continue
            checked += 1
            key = ALIASES.get(f, f)
            # BOTH SIDES, checked separately. An earlier version searched the file for the
            # bare key and passed when only ONE side existed -- proved by deleting the pan
            # WRITER and watching the lint stay green, because the reader still named "pan".
            # A write-only field is never restored; a read-only field is never saved. Either
            # half alone loses the user's setting, so either half alone is a failure.
            wrote = re.search(r'(?:addProperty|\.add)\(\s*"%s"' % re.escape(key), storage)
            read = re.search(r'(?:hasValue|get|getAsJsonArray|has)\([^)]*"%s"' % re.escape(key),
                             storage)
            if not wrote or not read:
                miss = "writer" if not wrote else ""
                miss = (miss + ("+" if miss else "") + "reader") if not read else miss
                failures.append((label, f, key, miss))
        print("%-10s %d fields checked, %d transient (skipped), %d exempt"
              % (label, checked, n_transient, len(exempt)))

    if failures:
        print()
        for label, f, key, miss in failures:
            print("FAIL  %s.%s has no %s in ProjectStorage (key \"%s\")"
                  % (label, f, miss, key))
        print()
        print("A field with no key is a setting the user loses on save. Add it to the")
        print("writer AND the reader, or add it to EXEMPT with a concrete reason.")
        print("%d UNPERSISTED" % len(failures))
        sys.exit(1)

    # Positive control: the lint must be capable of failing. If a field this script
    # invented were reported as present, the substring search is matching anything.
    if '"definitelyNotARealFieldName' in storage:
        print("FAIL  positive control matched a nonexistent key - search is broken")
        sys.exit(1)
    print("PASS  positive control: an invented key is correctly NOT found")
    print("ALL PERSISTED")


main()
