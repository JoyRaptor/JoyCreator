#!/usr/bin/env python3
"""check_joybrush_tokens.py -- D.01 drift guard for Joy Brush's colour tokens.

joybrush-android is its own Android library module, so `jb_tokens.xml` cannot use
`@color/s_anything`: a library's resources cannot see the consuming app's resources. The
mirrored tokens are therefore COPIES of app/src/main/res/values/studio_tokens.xml, and a
copy is only as good as the check that watches it. This is that check.

Three kinds of token are legal in jb_tokens.xml, and nothing else is:

  * the owner's room pair    jb_room_start / jb_room_end -- never mirrored, because the
                             owner edits those two lines to recolour Joy Brush
  * a same-file reference    @color/<a token in jb_tokens.xml>
  * a mirror                 a hex copied from a studio token, carrying a trailing
                             `<!-- mirror: s_token -->` (or `<!-- mirror: Studio.X -->` for
                             the one token that only exists in Java)

Usage:  python tools/check_joybrush_tokens.py
Prints "tokens in sync" and exits 0, or prints every drift it found and exits 1.

Stdlib only. No network, no build tool, no device.
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JB = ROOT / "joybrush-android" / "src" / "main" / "res" / "values" / "jb_tokens.xml"
STUDIO = ROOT / "app" / "src" / "main" / "res" / "values" / "studio_tokens.xml"
STUDIO_JAVA = ROOT / "studiokit" / "src" / "main" / "java" / "com" / "fadcam" / "ui" / "faditor" / "Studio.java"

# The two lines the owner owns. Editing them must never fail this check: that is the
# whole point of the file.
ROOM_PAIR = ("jb_room_start", "jb_room_end")

# Must be a same-file @color/ reference, and to this token.
REFERENCES = {
    "jb_board_canvas_start": "jb_room_start",
    "jb_board_canvas_end": "jb_room_end",
    "jb_board_character_start": "jb_board_puppet_start",
    "jb_board_character_end": "jb_board_puppet_end",
}

# Every mirrored token and the studio token it copies. This table is the contract; the
# `mirror:` comment in the XML must agree with it, so neither file can drift alone.
# A bare name is a token in studio_tokens.xml; a "Studio.NAME" is a static int in
# Studio.java (s_sunk has no XML twin).
MIRRORS = {
    "jb_board_animation_start": "s_room_studio",
    "jb_board_animation_end": "s_go_end",
    "jb_board_sprite_start": "s_room_sprite",
    "jb_board_sprite_end": "s_room_avatar",
    "jb_board_puppet_start": "s_room_avatar",
    "jb_board_puppet_end": "s_room_avatar_deep",
    "jb_state_selected": "s_armed",
    "jb_state_live": "s_live",
    "jb_state_careful": "s_careful",
    "jb_state_destroy": "s_danger",
    "jb_ground": "s_ground",
    "jb_surface": "s_surface",
    "jb_panel": "s_panel",
    "jb_sunk": "Studio.SUNK",
    "jb_raised": "s_raised",
    "jb_line": "s_line",
    "jb_ink": "s_ink",
    "jb_ink_dim": "s_ink_dim",
    "jb_ink_faint": "s_ink_faint",
    "jb_ink_off": "s_ink_off",
    "jb_label": "s_label",
    "jb_drawer_ink": "s_drawer_ink",
    "jb_drawer_dim": "s_drawer_dim",
    "jb_drawer_label": "s_drawer_label",
}

EXPECTED = set(ROOM_PAIR) | set(REFERENCES) | set(MIRRORS)

COLOR_LINE = re.compile(r'<color\s+name="([^"]+)"\s*>(.*?)</color>(.*)$')
MIRROR_COMMENT = re.compile(r"mirror:\s*([A-Za-z0-9_.]+)")


def java_const_pattern(name):
    """Matches Studio.java's `public static final int <name> = 0x........;`."""
    return re.compile(r"static\s+final\s+int\s+" + re.escape(name) + r"\s*=\s*0x([0-9A-Fa-f]{8})")


def normalise(value):
    """'#5C43FD' / '#FF5C43FD' / '#F00C' -> 'FF5C43FD'. None if it is not a colour."""
    text = value.strip()
    if not text.startswith("#"):
        return None
    digits = text[1:].upper()
    if len(digits) in (3, 4):
        digits = "".join(c * 2 for c in digits)
    if len(digits) == 6:
        digits = "FF" + digits
    if len(digits) != 8 or any(c not in "0123456789ABCDEF" for c in digits):
        return None
    return digits


def read_colours(path, problems):
    """name -> (raw value, trailing `mirror:` source or None, 1-based line number)."""
    if not path.exists():
        problems.append("{}: file is missing".format(path))
        return {}
    found = {}
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        match = COLOR_LINE.search(line)
        if not match:
            continue
        name, raw, tail = match.group(1), match.group(2), match.group(3)
        if name in found:
            problems.append("{}:{}: {} is defined twice".format(path.name, number, name))
            continue
        comment = MIRROR_COMMENT.search(tail)
        found[name] = (raw.strip(), comment.group(1) if comment else None, number)
    if not found:
        problems.append("{}: no <color> entries found".format(path.name))
    return found


def read_studio_java(path, problems):
    """'SUNK' -> 'FF16161B' for the tokens that only exist in Studio.java."""
    if not path.exists():
        problems.append("{}: file is missing".format(path))
        return {}
    text = path.read_text(encoding="utf-8", errors="replace")
    java = {}
    for name in ("SUNK",):
        match = java_const_pattern(name).search(text)
        if match:
            java[name] = match.group(1).upper()
        else:
            problems.append(
                "{}: could not find `static final int {} = 0x........` -- if it moved, "
                "jb_sunk has no source to mirror".format(path.name, name)
            )
    return java


def main():
    problems = []

    # Well-formedness comes before everything else. An XML comment may not contain "--", and
    # this file is full of them on purpose (they draw the section rules), so a stray one takes
    # the module's whole resource merge down with it rather than failing anything readable.
    for path in (JB, STUDIO):
        if not path.exists():
            continue
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            problems.append(
                "{}: not well-formed XML ({}) -- a double hyphen inside a comment is the "
                "usual cause".format(path.name, exc)
            )

    jb = read_colours(JB, problems)
    studio = read_colours(STUDIO, problems)
    java = read_studio_java(STUDIO_JAVA, problems)

    missing = sorted(EXPECTED - set(jb))
    for name in missing:
        problems.append("{}: expected token {} is missing".format(JB.name, name))
    extra = sorted(set(jb) - EXPECTED)
    for name in extra:
        problems.append(
            "{}: {} is not a known token. Add it to MIRRORS (with a mirror: comment) or to "
            "REFERENCES in tools/check_joybrush_tokens.py, or delete it".format(JB.name, name)
        )

    from_xml = 0
    from_java = 0
    for name in sorted(MIRRORS):
        source = MIRRORS[name]
        if name not in jb:
            continue
        raw, comment, number = jb[name]
        if comment != source:
            if comment is None:
                problems.append(
                    "{}:{}: {} has no 'mirror:' comment, so nothing would notice it drifting "
                    "(it must mirror {})".format(JB.name, number, name, source)
                )
            else:
                problems.append(
                    "{}:{}: {} says 'mirror: {}' but the check expects 'mirror: {}'".format(
                        JB.name, number, name, comment, source
                    )
                )
        mine = normalise(raw)
        if mine is None:
            problems.append(
                "{}:{}: {} is '{}', which is not a colour".format(JB.name, number, name, raw)
            )
            continue
        if source.startswith("Studio."):
            theirs = java.get(source.split(".", 1)[1])
            from_java += 1
        else:
            theirs = normalise(studio[source][0]) if source in studio else None
            from_xml += 1
            if source not in studio:
                problems.append(
                    "{}: {} names {} as its source, and that token is not in studio_tokens.xml".format(
                        JB.name, name, source
                    )
                )
        if theirs is not None and mine != theirs:
            problems.append(
                "{}:{}: {} is #{} but {} is #{} -- re-copy the source value".format(
                    JB.name, number, name, mine, source, theirs
                )
            )

    for name in sorted(REFERENCES):
        if name not in jb:
            continue
        raw, comment, number = jb[name]
        want = "@color/" + REFERENCES[name]
        if raw != want:
            problems.append(
                "{}:{}: {} is '{}' but must be the reference '{}'".format(
                    JB.name, number, name, raw, want
                )
            )
        if comment is not None:
            problems.append(
                "{}:{}: {} is a reference and must not carry a mirror: comment".format(
                    JB.name, number, name
                )
            )

    for name in ROOM_PAIR:
        if name not in jb:
            continue
        raw, comment, number = jb[name]
        if normalise(raw) is None:
            problems.append(
                "{}:{}: {} is '{}', which is not a colour".format(JB.name, number, name, raw)
            )
        if comment is not None:
            problems.append(
                "{}:{}: the owner's room token {} must not carry 'mirror: {}' -- the owner "
                "edits this line".format(JB.name, number, name, comment)
            )

    if problems:
        print("joybrush tokens OUT OF SYNC ({} problem(s))".format(len(problems)), file=sys.stderr)
        for line in problems:
            print("  " + line, file=sys.stderr)
        return 1

    print("tokens in sync")
    print(
        "  {}: {} tokens -- {} owner room lines, {} same-file references, {} mirrors".format(
            JB.name, len(jb), len(ROOM_PAIR), len(REFERENCES), len(MIRRORS)
        )
    )
    print(
        "  mirrors verified: {} against {}, {} against {}".format(
            from_xml, STUDIO.name, from_java, STUDIO_JAVA.name
        )
    )
    print("  change the Joy Brush gradient by editing {} only".format(JB.name))
    return 0


if __name__ == "__main__":
    sys.exit(main())
