#!/usr/bin/env python3
"""Pack Joybot's big sprite sheet into what the app ships.

The big sheet (17 x 17 cells of 240 px) would cost about 66 MB of memory if the app opened it whole.
So each animation is cut out into its own small picture, opened only while he plays it:

    app/src/main/assets/joybot/acts.json      the list: name, mood, speed, frames in / loop / out
    app/src/main/assets/joybot/<name>.webp    that animation's frames; frame 0 is always the home pose

Input is the folder the alignment work wrote (it holds sheet/joybot.png and joybot.animations.json).
Edit joybot.animations.json (order, speed, "general", which frames loop), run this again, rebuild.

    python tools/joybot/pack_joybot.py "art/Joybot-sprite-assets/aligned_v2_20261113"

Needs Pillow and numpy.
"""
import json
import os
import sys

import numpy as np
from PIL import Image

STRIP_COLS = 8          # frames per row inside one animation's picture
QUALITY = 90


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    src = sys.argv[1]
    repo = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    out = os.path.join(repo, "app", "src", "main", "assets", "joybot")
    os.makedirs(out, exist_ok=True)
    guide = json.load(open(os.path.join(src, "joybot.animations.json"), encoding="utf-8"))
    sheet = Image.open(os.path.join(src, guide["sheet"])).convert("RGBA")
    cols = guide["cols"]
    cell = sheet.width // cols

    def cut(i):
        r, c = divmod(i, cols)
        return sheet.crop((c * cell, r * cell, (c + 1) * cell, (r + 1) * cell))

    home = cut(guide.get("home", 0))
    a = np.array(home)[..., 3] > 128
    ys, xs = np.where(a)
    focus = [round(float(v), 4) for v in (xs.min() / cell, ys.min() / cell, (xs.max() + 1) / cell, (ys.max() + 1) / cell)]

    for old in os.listdir(out):
        if old.endswith(".webp"):
            os.remove(os.path.join(out, old))

    acts = []
    total = 0
    for an in guide["animations"]:
        cells = [guide.get("home", 0)]
        for i in an.get("in", []) + an["loop"] + an.get("out", []):
            if i not in cells:
                cells.append(i)
        at = {c: k for k, c in enumerate(cells)}
        sc = min(STRIP_COLS, len(cells))
        sr = (len(cells) + sc - 1) // sc
        strip = Image.new("RGBA", (sc * cell, sr * cell), (0, 0, 0, 0))
        for k, c in enumerate(cells):
            strip.paste(cut(c), ((k % sc) * cell, (k // sc) * cell))
        name = an["name"]
        path = os.path.join(out, name + ".webp")
        strip.save(path, "WEBP", quality=QUALITY, method=6)
        total += os.path.getsize(path)
        acts.append({
            "name": name, "mood": an["mood"], "general": bool(an.get("general", False)), "fps": an["fps"],
            "file": name + ".webp", "cols": sc, "rows": sr, "count": len(cells),
            "in": [at[i] for i in an.get("in", [])], "loop": [at[i] for i in an["loop"]],
            "out": [at[i] for i in an.get("out", [])],
        })
    doc = {"about": "Made by tools/joybot/pack_joybot.py. Do not edit by hand: edit joybot.animations.json and pack again.",
           "cell": cell, "focus": focus, "idleAfterSeconds": guide.get("suggestedBehaviour", {}).get("afterSecondsIdle", {}),
           "acts": acts}
    with open(os.path.join(out, "acts.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(doc, fh, indent=1)
        fh.write("\n")
    print(f"{len(acts)} animations, cell {cell}px, focus {focus}, {total // 1024} KB of pictures -> {out}")


if __name__ == "__main__":
    main()
