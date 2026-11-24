#!/usr/bin/env python3
"""No technical errors shown (JoyBrush's three bars, applied app-wide 11-24): an exception's own text
(e.getMessage()) must not go straight onto the screen. A line that puts it in a Toast, a dialog or a
formatted string must pass it through PlainWords.forPerson, which keeps the plain front of the message
and drops the technical tail.

Ratchet: files that still do it are listed in tools/plain_errors_baseline.json with their count, and
the count may only fall. A new file starts at 0.

    python -B tools/check_plain_errors.py            # fails if a file has more than its baseline
    python -B tools/check_plain_errors.py --update   # after you FIXED some: lower the baseline
"""
import json
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
BASE = os.path.join(ROOT, "tools", "plain_errors_baseline.json")
SHOWN = re.compile(r"(Toast\.makeText|setMessage\(|setText\(|getString\(|Snackbar)")
RAW = re.compile(r"\.(getMessage|getLocalizedMessage)\(\)")
LOG = re.compile(r"(FLog\.|Log\.[dweiv]\(|android\.util\.Log)")


def count():
    out = {}
    for dp, _, files in os.walk(os.path.join(ROOT, "app/src/main/java")):
        for f in files:
            if not f.endswith(".java"):
                continue
            p = os.path.join(dp, f)
            rel = os.path.relpath(p, ROOT).replace(os.sep, "/")
            n = 0
            with open(p, encoding="utf-8", errors="replace") as fh:
                for line in fh:
                    s = line.strip()
                    if s.startswith(("//", "*", "/*")):
                        continue
                    if RAW.search(line) and SHOWN.search(line) and not LOG.search(line) and "PlainWords" not in line:
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
        print("FAIL: an exception's own text is put on screen without PlainWords.forPerson:")
        for f, n, b in bad:
            print("  %s: %d (baseline %d)" % (f, n, b))
        return 1
    print("ok: %d raw-error lines left in %d files (baseline)" % (sum(now.values()), len(now)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
