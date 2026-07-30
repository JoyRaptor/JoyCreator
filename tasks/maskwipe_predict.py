"""Falsifiable check of MASK_WIPE on a TEXT BOX, predicted per frame from its own clock.

PICKERTEST has no startMs/endMs, so animSpanMs resolves to the whole 30s project, and
textAnimInPct is 0.25 -> a 7500ms entrance. With one unit (BLOCK) the reveal is
    p = local / 7500,  revealFrac = 1 - (1-p)^2
and it is a fraction of the box's own width. So the visible fraction of the text at
media time t is predictable in closed form, and each screencap carries the playhead chip
in the SAME framebuffer grab as the text.

Detecting the ink is the awkward part: the video behind the box is sometimes a white cat,
so a plain luminance threshold would count fur. The text is pure white with a drop
shadow, so a column counts as ink only if it holds a RUN of >= 10 consecutive rows at
>= 250. Glyph stems are tall and saturated; fur is neither.

Normalised against the widest extent the app itself produced, not against an assumed
glyph metric, so the instrument cannot be wrong about what "fully revealed" looks like.
"""
import sys, glob
import numpy as np
from PIL import Image

S = sys.argv[1]
SPAN_MS, IN_PCT = 30000, 0.25
ZONE = min(round(SPAN_MS * IN_PCT), SPAN_MS // 2)
BAND = (230, 555, 850, 675)

# Chip times read off box_contact.png, in capture order.
TIMES = [9, 305, 1113, 1929, 2722, 3560, 4374, 5077, 5846, 6627, 7520, 8406]


def predict(ms):
    p = max(0.0, min(1.0, min(ms, SPAN_MS) / ZONE))
    return 1.0 - (1.0 - p) ** 2


def ink_right_edge(im):
    a = np.asarray(im.crop(BAND).convert("L")).astype(int)
    hot = a >= 250
    h, w = hot.shape
    # longest run of hot rows per column
    best = np.zeros(w, int)
    run = np.zeros(w, int)
    for r in range(h):
        run = np.where(hot[r], run + 1, 0)
        best = np.maximum(best, run)
    cols = np.nonzero(best >= 10)[0]
    if len(cols) == 0:
        return None, None
    return int(cols.min()), int(cols.max())


files = sorted(glob.glob(f"{S}/box/*.png"))
rows = []
for f, ms in zip(files, TIMES):
    l, r = ink_right_edge(Image.open(f))
    rows.append((f.replace("\\", "/").split("/")[-1], ms, l, r))

lefts = [l for _, _, l, _ in rows if l is not None]
left0 = min(lefts)
full = max(r for _, _, _, r in rows if r is not None) - left0 + 1

print(f"entrance zone = {ZONE}ms   left edge = {left0}px   full extent = {full}px\n")
print(f"{'chip':>9}{'predicted':>11}{'measured':>10}{'delta':>8}   visible")
worst = 0.0
for name, ms, l, r in rows:
    pred = predict(ms)
    meas = 0.0 if r is None else (r - left0 + 1) / full
    d = meas - pred
    worst = max(worst, abs(d))
    print(f"{ms/1000:>8.3f}s{pred:>11.3f}{meas:>10.3f}{d:>+8.3f}   {'#' * round(meas * 40)}")
print(f"\nworst absolute deviation from prediction: {worst:.3f}")
