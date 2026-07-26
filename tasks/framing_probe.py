"""Measure the horizontal extent of picture content at given timestamps of an export.

Why this exists: for the "preset crop ignored during a transition blend" bug, the
question is a GEOMETRY one within a SINGLE export — does the framing during the blend
match the framing after the cut? The cut's framing is produced by the Crop effect, which
is the known-good authority, so "blend width == post-cut width" is a real acceptance
check. (tasks/export_ab_diff.py answers a different question: A vs B pixel equality.)

Method: sample a horizontal band of rows in the vertical middle of the frame (avoiding
top/bottom overlays), and report the leftmost/rightmost columns whose band-mean luminance
exceeds a threshold. A cropped-to-9:16 portrait source fit-centres into a narrow vertical
strip; an uncropped one fills the frame.

Usage:
    python framing_probe.py export.mp4 t1 t2 t3 ...      # timestamps in seconds
"""
import os
import subprocess
import sys
import tempfile

from PIL import Image

THRESH = 24          # luminance above this counts as picture, not letterbox black
BAND_LO, BAND_HI = 0.40, 0.60   # rows sampled, as a fraction of height


def grab(video, t, outpng):
    subprocess.run(['ffmpeg', '-v', 'error', '-ss', str(t), '-i', video,
                    '-frames:v', '1', '-y', outpng], check=True)


def extent(png):
    im = Image.open(png).convert('L')
    w, h = im.size
    px = im.load()
    lo, hi = int(h * BAND_LO), int(h * BAND_HI)
    cols = []
    for x in range(w):
        s = sum(px[x, y] for y in range(lo, hi))
        cols.append(s / max(1, hi - lo))
    on = [x for x, v in enumerate(cols) if v > THRESH]
    if not on:
        return w, h, None, None, 0.0
    left, right = on[0], on[-1]
    return w, h, left, right, (right - left + 1) / w


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    video = sys.argv[1]
    times = [float(t) for t in sys.argv[2:]]
    tmp = tempfile.mkdtemp(prefix='framing_')
    print(f'video           : {video}')
    print(f'{"t(s)":>8}  {"size":>10}  {"left":>5} {"right":>5}  {"width frac":>10}')
    for t in times:
        p = os.path.join(tmp, f'f{t:.2f}.png')
        grab(video, t, p)
        w, h, l, r, frac = extent(p)
        ls = '-' if l is None else str(l)
        rs = '-' if r is None else str(r)
        print(f'{t:8.2f}  {w}x{h:<5}  {ls:>5} {rs:>5}  {frac:10.3f}')
    print(f'\nframes kept in   : {tmp}')


if __name__ == '__main__':
    main()
