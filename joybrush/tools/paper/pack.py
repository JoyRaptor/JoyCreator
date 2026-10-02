"""Pack a height map into a Joy Brush SURFACE texture (JB-9.01 contract; the Kotlin SurfaceMaps is its twin).
RGBA8: R = encode(dh/dx), G = encode(dh/dy), B = height, A = height^2.
Slopes: Scharr on a torus, in height units per texel, divided by 32 so a ramp h = k*x gives exactly k.
encode(s) = round(127 + 127 * clamp(s / slopeRange, -1, 1)).
Usage: python pack.py <height.png> <out.png> [slopeRange] [--height-span 0.2]
Reads 8/16-bit greyscale heights. Smaller spans flatten physical height around 0.5, without
min/max normalization. Compare flattened/full surfaces with the SAME explicit slopeRange;
automatic range estimation would otherwise normalize away their directional strength difference.
Prints the slopeRange used."""
import argparse
import numpy as np
from PIL import Image

def height_bytes(image, height_span=1.0):
    """Preserve 16-bit height units; PIL convert('L') clips integer images instead of scaling them."""
    if not np.isfinite(height_span) or not 0 <= height_span <= 1:
        raise ValueError("height span must be finite and between 0 and 1")
    values = np.asarray(image)
    if values.ndim != 2 or values.dtype.kind not in 'ui':
        raise ValueError("a physical height map must be 8/16-bit integer greyscale")
    sixteen = values.dtype.itemsize > 1 or image.mode.startswith('I;16') or image.mode == 'I'
    limit = 65535 if sixteen else 255
    if np.any(values < 0) or np.any(values > limit):
        raise ValueError("height values are outside the source bit depth")
    h = values.astype(np.float64) / limit
    h = 0.5 + height_span * (h - 0.5)
    return np.rint(h * 255).astype(np.uint8)
def slopes(h):
    r = lambda dy, dx: np.roll(np.roll(h, -dy, 0), -dx, 1)   # r(dy,dx)[y,x] = h[y+dy, x+dx] (wraps)
    dx = (3 * (r(-1, 1) - r(-1, -1)) + 10 * (r(0, 1) - r(0, -1)) + 3 * (r(1, 1) - r(1, -1))) / 32
    dy = (3 * (r(1, -1) - r(-1, -1)) + 10 * (r(1, 0) - r(-1, 0)) + 3 * (r(1, 1) - r(-1, 1))) / 32
    return dx, dy
def pack(hbytes, slope_range=None):
    h = hbytes.astype(np.float64) / 255
    dx, dy = slopes(h)
    if slope_range is None:
        slope_range = max(0.001, float(np.ceil(np.percentile(np.abs(np.concatenate([dx.ravel(), dy.ravel()])), 99.9) * 1000) / 1000))
    if not np.isfinite(slope_range) or slope_range <= 0:
        raise ValueError("slope range must be positive and finite")
    enc = lambda s: np.round(127 + 127 * np.clip(s / slope_range, -1, 1))
    out = np.dstack([enc(dx), enc(dy), hbytes.astype(np.float64), np.round(255 * h * h)]).astype(np.uint8)
    return out, slope_range
if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('height')
    parser.add_argument('output')
    parser.add_argument('slope_range', type=float, nargs='?')
    parser.add_argument('--height-span', type=float, default=1.0)
    args = parser.parse_args()
    with Image.open(args.height) as image:
        hb = height_bytes(image, args.height_span)
    out, sr = pack(hb, args.slope_range)
    Image.fromarray(out).save(args.output, optimize=True)
    print(sr)
