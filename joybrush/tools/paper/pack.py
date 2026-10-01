"""Pack a height map into a Joy Brush SURFACE texture (JB-9.01 contract; the Kotlin SurfaceMaps is its twin).
RGBA8: R = encode(dh/dx), G = encode(dh/dy), B = height, A = height^2.
Slopes: Scharr on a torus, in height units per texel, divided by 32 so a ramp h = k*x gives exactly k.
encode(s) = round(127 + 127 * clamp(s / slopeRange, -1, 1)).
Usage: python pack.py <height.png> <out.png> [slopeRange]   (prints the slopeRange used)"""
import numpy as np, sys
from PIL import Image
def slopes(h):
    r = lambda dy, dx: np.roll(np.roll(h, -dy, 0), -dx, 1)   # r(dy,dx)[y,x] = h[y+dy, x+dx] (wraps)
    dx = (3 * (r(-1, 1) - r(-1, -1)) + 10 * (r(0, 1) - r(0, -1)) + 3 * (r(1, 1) - r(1, -1))) / 32
    dy = (3 * (r(1, -1) - r(-1, -1)) + 10 * (r(1, 0) - r(-1, 0)) + 3 * (r(1, 1) - r(-1, 1))) / 32
    return dx, dy
def pack(hbytes, slope_range=None):
    h = hbytes.astype(np.float64) / 255
    dx, dy = slopes(h)
    if slope_range is None:
        slope_range = float(np.ceil(np.percentile(np.abs(np.concatenate([dx.ravel(), dy.ravel()])), 99.9) * 1000) / 1000)
    enc = lambda s: np.round(127 + 127 * np.clip(s / slope_range, -1, 1))
    out = np.dstack([enc(dx), enc(dy), hbytes.astype(np.float64), np.round(255 * h * h)]).astype(np.uint8)
    return out, slope_range
if __name__ == "__main__":
    hb = np.asarray(Image.open(sys.argv[1]).convert("L"))
    out, sr = pack(hb, float(sys.argv[3]) if len(sys.argv) > 3 else None)
    Image.fromarray(out, "RGBA").save(sys.argv[2], optimize=True)
    print(sr)
