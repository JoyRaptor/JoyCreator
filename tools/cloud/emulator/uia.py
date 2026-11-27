"""Find nodes in a uiautomator dump by text, content-desc or resource id, case-insensitively.

  uia.py find  ui.xml needle        list matches (index, text, desc, id, bounds)
  uia.py count ui.xml needle        print how many match
  uia.py tap   ui.xml needle [n]    print the centre "x y" of the n-th match (default 0)
  uia.py list  ui.xml               every node that has text, a description or is clickable
"""
import re
import sys
import xml.etree.ElementTree as ET


def nodes(path):
    try:
        root = ET.parse(path).getroot()
    except Exception:
        return []
    out = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) != 4:
            continue
        x0, y0, x1, y1 = map(int, b)
        out.append(dict(text=n.get("text", ""), desc=n.get("content-desc", ""), rid=n.get("resource-id", ""),
                        click=n.get("clickable") == "true", cx=(x0 + x1) // 2, cy=(y0 + y1) // 2, b=(x0, y0, x1, y1)))
    return out


def match(ns, needle):
    if needle.startswith("="):   # "=Round": the whole text or description must be exactly this
        k = needle[1:].lower()
        return [n for n in ns if k == n["text"].lower() or k == n["desc"].lower()]
    k = needle.lower()
    return [n for n in ns if k in n["text"].lower() or k in n["desc"].lower() or k in n["rid"].lower()]


def main():
    cmd, path = sys.argv[1], sys.argv[2]
    ns = nodes(path)
    if cmd == "list":
        for n in ns:
            if n["text"] or n["desc"] or n["click"]:
                print(n["text"][:40], "|", n["desc"][:40], "|", n["rid"].split("/")[-1], n["click"], n["b"])
        return
    ms = match(ns, sys.argv[3])
    if cmd == "count":
        print(len(ms))
    elif cmd == "find":
        for i, n in enumerate(ms):
            print(i, n["text"][:40], "|", n["desc"][:40], "|", n["rid"].split("/")[-1], n["b"])
    elif cmd == "tap":
        k = int(sys.argv[4]) if len(sys.argv) > 4 else 0
        if k < len(ms):
            print(ms[k]["cx"], ms[k]["cy"])


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
