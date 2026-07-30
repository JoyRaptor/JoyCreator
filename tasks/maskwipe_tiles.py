"""Measure the preset tiles in a burst of framebuffer grabs.

The question MASK_WIPE has to answer on a phone is not "does the tile move" — several
presets move. It is "is the ink being UNCOVERED, or is it being faded?", because a
renderer could have "supported" the new channel by quietly mapping it onto alpha, and
that is precisely the divergence the code is written to prevent.

Two measurements separate those, on the same pixels:

  extent  the width of the tile's INK, i.e. how many columns carry any ink at all.
          A wipe grows this. A fade cannot: a fading glyph occupies the same columns
          the whole time, it just gets dimmer.

  peak    the brightest ink in the tile. A wipe keeps this pinned near full for the
          whole entrance, because the part that IS shown is shown at full strength.
          A fade drags it down to nothing.

So MASK_WIPE should read as HIGH extent variation with LOW peak variation, and FADE as
the exact opposite. NONE is the built-in control: it is deliberately static, so anything
non-zero there means the instrument is reading the clock, the screen or noise.
"""
import sys, glob
import numpy as np
from PIL import Image

# Tile centres in ORIGINAL framebuffer pixels, read off the picker screenshot.
TILES = [
    ("None", 441, 685), ("Type", 572, 685), ("Fade", 704, 685),
    ("Rise", 441, 818), ("Ghost", 572, 818), ("Beam", 704, 818),
    ("Matrix", 441, 950), ("Unscramble", 572, 950), ("MaskWipe", 704, 950),
]
HALF_W, TOP, BOT = 56, 46, 18   # glyph area only: above the label, inside the tile

frames = sorted(glob.glob(sys.argv[1] + "/*.png"))
print("frames:", len(frames))

# Tile background is a flat dark grey; ink is much lighter. Threshold well above it.
BG_MAX = None
data = {n: {"extent": [], "peak": [], "mean": []} for n, _, _ in TILES}

for f in frames:
    im = np.asarray(Image.open(f).convert("L")).astype(float)
    for name, cx, cy in TILES:
        crop = im[cy - TOP:cy + BOT, cx - HALF_W:cx + HALF_W]
        bg = np.median(crop)                 # the flat tile fill
        ink = crop - bg
        ink[ink < 25] = 0                    # ink only, not the fill or its dither
        cols = ink.sum(axis=0)
        data[name]["extent"].append(int((cols > 0).sum()))
        data[name]["peak"].append(float(ink.max()))
        data[name]["mean"].append(float(ink.mean()))

print(f"\n{'tile':<11}{'extent sd':>10}{'extent min-max':>18}{'peak sd':>10}"
      f"{'peak min-max':>16}{'mean sd':>10}")
for name, _, _ in TILES:
    e = np.array(data[name]["extent"], float)
    p = np.array(data[name]["peak"], float)
    m = np.array(data[name]["mean"], float)
    print(f"{name:<11}{e.std():>10.2f}{f'{int(e.min())}-{int(e.max())}':>18}"
          f"{p.std():>10.2f}{f'{p.min():.0f}-{p.max():.0f}':>16}{m.std():>10.2f}")

print("\nper-frame ink extent (columns carrying ink):")
for name, _, _ in TILES:
    print(f"  {name:<11}{data[name]['extent']}")
print("\nper-frame peak ink:")
for name, _, _ in TILES:
    print(f"  {name:<11}{[round(v) for v in data[name]['peak']]}")
