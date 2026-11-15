#!/usr/bin/env python3
"""Turn the owner's hand-drawn flight paths into data Joybot can fly.

The owner draws paths in Concepts from the Joybot button (top right) to his landing spot (lower left),
one stroke per path, and exports an SVG. Every path is stored STRETCHED: its first point becomes (0, 0)
and its last point (1, 1), each axis on its own. So one path fits any start and end on any screen: the
app maps u, v back with  x = start.x + u * (end.x - start.x),  y = start.y + v * (end.y - start.y).
(Owner, 11-14: "they can be just scaled from point A to point B, whether it be a large horizontal or
vertical alignment".)

    python tools/joybot/pack_paths.py "<drawing>.svg"   ->  app/src/main/assets/joybot/paths.json

A path drawn the other way round (landing to button) is turned round. Strokes with fewer than two
points are skipped. Pure standard library.
"""
import json
import math
import os
import re
import sys
import xml.etree.ElementTree as ET

SAMPLES = 96          # points kept per path, evenly spaced along its length


def parse_d(d):
    """M / L / Q / C (absolute or relative) into a list of points, curves sampled."""
    toks = re.findall(r"[MLQCZmlqcz]|-?\d*\.?\d+(?:e-?\d+)?", d)
    pts, i, cmd, cur = [], 0, None, (0.0, 0.0)

    def num():
        nonlocal i
        v = float(toks[i]); i += 1
        return v
    while i < len(toks):
        t = toks[i]
        if re.match(r"[A-Za-z]", t):
            cmd = t; i += 1
            if cmd in "Zz":
                continue
        rel = cmd.islower()
        c = cmd.upper()
        if c in "ML":
            x, y = num(), num()
            if rel: x, y = cur[0] + x, cur[1] + y
            cur = (x, y); pts.append(cur)
            if cmd == "M": cmd = "L"
            if cmd == "m": cmd = "l"
        elif c == "Q":
            x1, y1, x, y = num(), num(), num(), num()
            if rel: x1, y1, x, y = cur[0] + x1, cur[1] + y1, cur[0] + x, cur[1] + y
            p0 = cur
            for k in range(1, 7):
                s = k / 6
                pts.append(((1-s)**2*p0[0] + 2*(1-s)*s*x1 + s*s*x, (1-s)**2*p0[1] + 2*(1-s)*s*y1 + s*s*y))
            cur = (x, y)
        elif c == "C":
            x1, y1, x2, y2, x, y = num(), num(), num(), num(), num(), num()
            if rel: x1, y1, x2, y2, x, y = (cur[0]+x1, cur[1]+y1, cur[0]+x2, cur[1]+y2, cur[0]+x, cur[1]+y)
            p0 = cur
            for k in range(1, 9):
                s = k / 8
                pts.append(((1-s)**3*p0[0] + 3*(1-s)**2*s*x1 + 3*(1-s)*s*s*x2 + s**3*x,
                            (1-s)**3*p0[1] + 3*(1-s)**2*s*y1 + 3*(1-s)*s*s*y2 + s**3*y))
            cur = (x, y)
        else:
            i += 1
    return pts


def resample(pts, n):
    L = [0.0]
    for a, b in zip(pts, pts[1:]):
        L.append(L[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))
    tot = L[-1] or 1.0
    out, j = [], 0
    for k in range(n):
        d = tot * k / (n - 1)
        while j < len(L) - 2 and L[j + 1] < d:
            j += 1
        f = (d - L[j]) / ((L[j + 1] - L[j]) or 1.0)
        out.append((pts[j][0] + (pts[j + 1][0] - pts[j][0]) * f, pts[j][1] + (pts[j + 1][1] - pts[j][1]) * f))
    return out, tot


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    svg = sys.argv[1]
    root = ET.parse(svg).getroot()
    ns = "{http://www.w3.org/2000/svg}"
    paths = []
    for el in root.iter(ns + "path"):
        pts = parse_d(el.get("d", ""))
        if len(pts) < 2:
            continue
        # top right (the button) is the start: a stroke drawn from the landing is turned round
        if pts[0][1] > pts[-1][1]:
            pts = pts[::-1]
        pts, length = resample(pts, SAMPLES)
        (sx, sy), (ex, ey) = pts[0], pts[-1]
        dx, dy = ex - sx, ey - sy
        if abs(dx) < 1e-6 or abs(dy) < 1e-6:
            continue
        uv = [[round((x - sx) / dx, 4), round((y - sy) / dy, 4)] for x, y in pts]
        paths.append({"colour": el.get("stroke", ""), "uv": uv,
                      "drawnFrom": [round(sx, 1), round(sy, 1)], "drawnTo": [round(ex, 1), round(ey, 1)],
                      "lengthOverDirect": round(length / math.hypot(dx, dy), 2)})
    repo = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    out = os.path.join(repo, "app", "src", "main", "assets", "joybot", "paths.json")
    doc = {"about": "Joybot's flight paths, drawn by the owner (" + os.path.basename(svg) + "), packed by tools/joybot/pack_paths.py. "
                    "Each path is stretched so its start is (0,0) and its end (1,1); map it to any start and end. Do not edit by hand.",
           "paths": paths}
    with open(out, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(doc, fh, separators=(",", ":"))
        fh.write("\n")
    for p in paths:
        print(p["colour"], "from", p["drawnFrom"], "to", p["drawnTo"], "x", p["lengthOverDirect"])
    print(len(paths), "paths ->", out, os.path.getsize(out), "bytes")


if __name__ == "__main__":
    main()
