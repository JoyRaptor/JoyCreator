"""Make a Joy pack (job PACK-1) from a folder.

    python -I tools/joypack/make_pack.py <folder> [<out.joypack>]

The folder holds joypack.json at its top and the files it lists. This checks what the app will check (an id, the
format, every listed file present and inside the folder, sockets the app knows today) and zips the folder into
<pack name>.joypack. Anyone can make a pack this way; nothing in it runs as program code.
"""
import json
import os
import re
import sys
import zipfile

SOCKETS = {"text-look", "vis-look", "vis-part", "brush", "paper", "palette", "font", "grade", "caption-style"}


def main():
    src = sys.argv[1]
    man_path = os.path.join(src, "joypack.json")
    if not os.path.isfile(man_path):
        sys.exit("no joypack.json at the top of " + src)
    m = json.load(open(man_path, encoding="utf-8"))
    problems, notes = [], []
    if not m.get("id"):
        problems.append("joypack.json has no id (use author.name, e.g. \"someone.neon-nights\")")
    if m.get("format") != "joy.pack/1":
        notes.append("format is not \"joy.pack/1\"")
    listed = set()
    for e in m.get("contents", []):
        f, s = e.get("file", ""), e.get("socket", "")
        p = f.replace("\\", "/")
        if p.startswith("/") or ":" in p or ".." in p.split("/"):
            problems.append("file outside the pack: " + f)
            continue
        if not os.path.isfile(os.path.join(src, p)):
            problems.append("listed but missing: " + f)
        if s.lower() not in SOCKETS:
            notes.append("socket \"%s\" is not known to this version of Joy Creator; the app keeps it for later" % s)
        if s.lower() == "text-look":
            try:
                look = json.load(open(os.path.join(src, p), encoding="utf-8"))
                if look.get("format") != "joy.text/1":
                    notes.append(f + ": format is not joy.text/1")
            except Exception as ex:  # noqa: BLE001 - report any unreadable look plainly
                problems.append(f + " is not readable JSON: %s" % ex)
        listed.add(p)
    if problems:
        sys.exit("not made:\n  " + "\n  ".join(problems))
    name = re.sub(r'[\\/:*?"<>|]+', " ", m.get("name") or m["id"]).strip()
    out = sys.argv[2] if len(sys.argv) > 2 else name + ".joypack"
    n = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(src):
            for fn in files:
                if fn.startswith("."):
                    continue
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, src).replace("\\", "/")
                z.write(full, rel)
                n += 1
    for x in notes:
        print("note: " + x)
    unlisted = n - 1 - len(listed)
    print("made %s: %d files, %d listed in joypack.json%s, %d bytes" % (
        out, n, len(listed), " (%d more carried along)" % unlisted if unlisted > 0 else "", os.path.getsize(out)))


if __name__ == "__main__":
    main()
