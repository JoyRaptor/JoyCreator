#!/usr/bin/env python3
"""Check the app's translated string resources against English.

    python tools/check_strings.py            # human report, exit 1 on any hard problem
    python tools/check_strings.py --lang de  # one language only
    python tools/check_strings.py -v         # also list every individual problem

Looks at app/src/main/res/values (English) and every values-<lang> folder that has
string files, and reports per language:

  BRAND    a translation says FadCam / FadRec / FadShot / Faditor / FadSec / FadDrive /
           FadMic that the English string at the same key does not say (hard problem).
           Where English itself still says it (a real folder name, a credit line) the
           translation may too; those are counted as "kept like English", not flagged.
  PLACEHOLDER  the %1$s / %d / %% pieces differ between English and the translation (hard).
  ESCAPE   an unescaped apostrophe, or a different number of \\n escapes (hard: aapt fails
           or the layout changes).
  DUPLICATE  the same key defined twice in one language folder (hard: the build fails).
  EXTRA    a translation key that English does not have (hard: lint-visible leftovers).
  MISSING  an English key (not translatable="false") the language lacks. Android falls
           back to English, so this is reported as a number, not a failure, unless
           --strict-missing is given.
  PLURAL   a <plurals> missing a quantity the language needs (hard).

Only needs the Python standard library.
"""
import argparse
import collections
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")

BRAND_RE = re.compile(r"fad(cam|rec|shot|itor|sec|drive|mic)", re.I)
PLACEHOLDER_RE = re.compile(r"%(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?[a-zA-Z%]")
# quantities each language needs (Android/CLDR); "in" and "zh" use "other" only
PLURAL_NEEDS = {
    "ar": {"zero", "one", "two", "few", "many", "other"},
    "ru": {"one", "few", "many", "other"},
}
PLURAL_DEFAULT = {"one", "other"}
PLURAL_OTHER_ONLY = {"in", "zh-rCN", "zh"}

STRING_TAGS = ("string", "plurals", "string-array")


def inner(e):
    """Decoded text of an element (entities resolved, Android backslash escapes kept)."""
    return "".join(e.itertext())


def parts(e):
    """Every translatable text piece of an entry, as a list of strings."""
    if e.tag == "string":
        return [inner(e)]
    if e.tag == "plurals":
        return [inner(i) for i in e]
    return [inner(i) for i in e]  # string-array items


def load_dir(folder):
    """Return (entries, duplicates): {key: element}, [keys defined twice]."""
    entries, dups = {}, []
    files = sorted(f for f in glob.glob(os.path.join(folder, "*.xml"))
                   if re.search(r"strings.*\.xml$", os.path.basename(f)))
    for f in files:
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError as ex:
            dups.append("XML ERROR in %s: %s" % (os.path.basename(f), ex))
            continue
        if root.tag != "resources":
            continue
        for e in root:
            if e.tag in STRING_TAGS and e.get("name"):
                k = e.get("name")
                if k in entries:
                    dups.append(k)
                entries[k] = e
    return entries, dups


def placeholders(text):
    toks = PLACEHOLDER_RE.findall(text)
    return collections.Counter(re.sub(r"^%\d+\$", "%", t) for t in toks)


def bad_apostrophe(text):
    """True when the text has an apostrophe not preceded by a backslash and is not fully quoted."""
    s = text.strip()
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"':
        return False
    return re.search(r"(?<!\\)'", text) is not None


def plural_need(lang):
    if lang in PLURAL_OTHER_ONLY:
        return {"other"}
    return PLURAL_NEEDS.get(lang, PLURAL_DEFAULT)


def check_lang(lang, en, tr, dups, verbose, strict_missing):
    hard = []
    kept = 0
    for k in dups:
        hard.append(("DUPLICATE", k, "defined twice"))
    for k, e in tr.items():
        ee = en.get(k)
        if ee is None:
            hard.append(("EXTRA", k, "not in English"))
            continue
        if ee.get("translatable") == "false":
            continue  # English says never translate this key; a stale translation is ignored
        tp, ep = parts(e), parts(ee)
        ttxt = " ".join(tp)
        etxt = " ".join(ep)
        if BRAND_RE.search(ttxt):
            if BRAND_RE.search(etxt):
                kept += 1
            else:
                hard.append(("BRAND", k, BRAND_RE.search(ttxt).group(0)))
        if e.tag != ee.tag:
            hard.append(("PLACEHOLDER", k, "different kind of entry"))
            continue
        if e.tag == "plurals":
            have = {i.get("quantity") for i in e}
            need = plural_need(lang)
            if need - have:
                hard.append(("PLURAL", k, "missing " + ",".join(sorted(need - have))))
            # compare placeholders of the "other" item
            to = next((inner(i) for i in e if i.get("quantity") == "other"), "")
            eo = next((inner(i) for i in ee if i.get("quantity") == "other"), "")
            if placeholders(to) != placeholders(eo):
                hard.append(("PLACEHOLDER", k, "plural other: %s vs %s" % (dict(placeholders(eo)), dict(placeholders(to)))))
            for i in e:
                if bad_apostrophe(inner(i)):
                    hard.append(("ESCAPE", k, "unescaped apostrophe"))
            continue
        if len(tp) != len(ep):
            hard.append(("PLACEHOLDER", k, "array length %d vs %d" % (len(ep), len(tp))))
            continue
        for a, b in zip(ep, tp):
            if placeholders(a) != placeholders(b):
                hard.append(("PLACEHOLDER", k, "%s vs %s" % (dict(placeholders(a)), dict(placeholders(b)))))
                break
        for a, b in zip(ep, tp):
            if bad_apostrophe(b):
                hard.append(("ESCAPE", k, "unescaped apostrophe"))
                break
            if a.count("\\n") != b.count("\\n"):
                hard.append(("ESCAPE", k, "different number of \\n"))
                break
    missing = [k for k, e in en.items() if k not in tr and e.get("translatable") != "false"]
    return hard, kept, missing


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lang", help="only this language folder suffix, e.g. de or zh-rCN")
    ap.add_argument("-v", "--verbose", action="store_true", help="list each problem")
    ap.add_argument("--strict-missing", action="store_true", help="treat missing keys as failures")
    a = ap.parse_args()

    en, en_dups = load_dir(os.path.join(RES, "values"))
    if en_dups:
        print("English has duplicate keys:", ", ".join(en_dups[:10]))
    langs = []
    for d in sorted(os.listdir(RES)):
        m = re.match(r"^values-([a-z]{2,3}(?:-r[A-Z]{2})?)$", d)
        if m and glob.glob(os.path.join(RES, d, "strings*.xml")):
            langs.append(m.group(1))
    if a.lang:
        langs = [l for l in langs if l == a.lang]
    translatable = sum(1 for e in en.values() if e.get("translatable") != "false")
    print("English: %d translatable strings, %d languages checked\n" % (translatable, len(langs)))
    print("%-7s %7s %7s %7s %7s %7s %7s %7s" % ("lang", "brand", "placeh", "escape", "dup/ex", "plural", "missing", "kept=EN"))
    failed = False
    detail = []
    for lang in langs:
        tr, dups = load_dir(os.path.join(RES, "values-" + lang))
        hard, kept, missing = check_lang(lang, en, tr, dups, a.verbose, a.strict_missing)
        c = collections.Counter(h[0] for h in hard)
        print("%-7s %7d %7d %7d %7d %7d %7d %7d" % (
            lang, c["BRAND"], c["PLACEHOLDER"], c["ESCAPE"], c["DUPLICATE"] + c["EXTRA"], c["PLURAL"], len(missing), kept))
        if hard or (a.strict_missing and missing):
            failed = True
        if a.verbose:
            for h in hard:
                detail.append("  %s %s %s: %s" % (lang, h[0], h[1], h[2]))
            for k in missing:
                detail.append("  %s MISSING %s" % (lang, k))
    if detail:
        print()
        print("\n".join(detail))
    print()
    print("FAIL" if failed else "OK", "(missing keys fall back to English; use --strict-missing to fail on them)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
