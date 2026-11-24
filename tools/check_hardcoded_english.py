#!/usr/bin/env python3
"""L18 ratchet: user-visible English typed straight into Java/Kotlin must not GROW.

Counts, per file, the lines that put a quoted literal with a letter in it straight on screen:
  Toast.makeText(x, "literal" ...), .setText("literal"), .setTitle/.setMessage/.setHint("literal")
  and setContentDescription("literal"). A literal with no letter in it (a symbol, a number) is ignored.

The sweep that moves the existing ones into strings.xml (and their 11 translations) is a big job
(885 at the 11-13 audit); this stops it getting bigger while it waits. Run:

    python -B tools/check_hardcoded_english.py            # fails if a file has more than its baseline
    python -B tools/check_hardcoded_english.py --update   # after you REMOVED some: lower the baseline

The baseline is tools/hardcoded_english_baseline.json. A new file starts at 0: put its words in
res/values/strings_<area>.xml instead.
"""
import json
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
BASE = os.path.join(ROOT, "tools", "hardcoded_english_baseline.json")
DIRS = ["app/src/main/java", "joybrush-android/src/main"]
LIT = '"[^"\\\\]*[A-Za-z][^"\\\\]*"'
PATS = [
    re.compile('Toast\\.makeText\\([^,]+,\\s*' + LIT),
    re.compile('\\.setText\\(\\s*' + LIT + '\\s*\\)'),
    re.compile('\\.(setTitle|setMessage|setHint|setContentDescription)\\(\\s*' + LIT + '\\s*\\)'),
]


def count():
    out = {}
    for d in DIRS:
        for dp, _, files in os.walk(os.path.join(ROOT, d)):
            for f in files:
                if not f.endswith((".java", ".kt")):
                    continue
                p = os.path.join(dp, f)
                rel = os.path.relpath(p, ROOT).replace(os.sep, "/")
                n = 0
                with open(p, encoding="utf-8", errors="replace") as fh:
                    for line in fh:
                        s = line.strip()
                        if s.startswith(("//", "*", "/*")):
                            continue
                        if any(r.search(line) for r in PATS):
                            n += 1
                if n:
                    out[rel] = n
    return out


def main():
    now = count()
    if "--update" in sys.argv:
        with open(BASE, "w", encoding="utf-8") as fh:
            json.dump(dict(sorted(now.items())), fh, indent=1)
            fh.write("\n")
        print("baseline written: %d files, %d lines" % (len(now), sum(now.values())))
        return 0
    base = json.load(open(BASE, encoding="utf-8")) if os.path.exists(BASE) else {}
    bad = [(f, n, base.get(f, 0)) for f, n in sorted(now.items()) if n > base.get(f, 0)]
    if bad:
        print("FAIL: more hard-coded English than the baseline (put the words in res/values/strings_*.xml):")
        for f, n, b in bad:
            print("  %s: %d (baseline %d)" % (f, n, b))
        return 1
    shrunk = sum(base.get(f, 0) - now.get(f, 0) for f in base if now.get(f, 0) < base[f])
    print("ok: %d lines in %d files%s" % (sum(now.values()), len(now),
          "; %d fewer than the baseline: run with --update to lock that in" % shrunk if shrunk else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
