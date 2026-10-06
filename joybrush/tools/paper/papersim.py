"""Paper simulator (PC): the background as the phone draws it, and a dry stroke biting the paper's surface.

For judging papers before they ship. Mirrors jb_paper_bg.frag's hex read (3 vertices, cubed weights, per-vertex offset and
rotation) and jb_grain.glsl's height threshold. `--blend vp` uses the variance-preserving hex blend; `linear` the old one.

python papersim.py <look.png> <surface.png> <out.png> [--texel 2.5] [--hex 300] [--blend vp|linear] [--w 900] [--h 520]
"""
import argparse

import numpy as np
from PIL import Image

SQRT3 = 3 ** 0.5


def hash3(i, j, k):
    x = (i.astype(np.uint32) * np.uint32(73856093)) ^ (j.astype(np.uint32) * np.uint32(19349663)) ^ np.uint32(k * 83492791 & 0xFFFFFFFF)
    x ^= x >> np.uint32(16); x = x * np.uint32(0x7feb352d); x ^= x >> np.uint32(15)
    x = x * np.uint32(0x846ca68b); x ^= x >> np.uint32(16)
    return (x >> np.uint32(8)).astype(np.float64) / 16777216.0


def bilinear(tex, u, v):
    h, w = tex.shape[:2]
    u = np.mod(u, w); v = np.mod(v, h)
    x0 = np.floor(u).astype(int); y0 = np.floor(v).astype(int)
    fx = (u - x0)[..., None]; fy = (v - y0)[..., None]
    x1 = (x0 + 1) % w; y1 = (y0 + 1) % h
    a = tex[y0, x0]; b = tex[y0, x1]; c = tex[y1, x0]; d = tex[y1, x1]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def hex_read(tex, X, Y, texel, cell, rotatable, mean, blend):
    p = np.stack([X / texel, Y / texel], -1)
    q = p / cell
    a = q[..., 0] - q[..., 1] / SQRT3
    b = 2 * q[..., 1] / SQRT3
    bi = np.floor(a).astype(int); bj = np.floor(b).astype(int)
    fa = a - bi; fb = b - bj
    up = fa + fb > 1
    verts = [
        (np.where(up, bi + 1, bi), np.where(up, bj + 1, bj), np.where(up, fa + fb - 1, 1 - fa - fb)),
        (bi + 1, bj, np.where(up, 1 - fb, fa)),
        (bi, bj + 1, np.where(up, 1 - fa, fb)),
    ]
    ws = np.stack([v[2] ** 3 for v in verts], -1)
    ws /= ws.sum(-1, keepdims=True)
    size = tex.shape[0]
    acc = 0
    for n, (vi, vj, _) in enumerate(verts):
        cx = cell * (vi + vj / 2.0); cy = cell * (vj * SQRT3 / 2)
        ox = hash3(vi, vj, 1) * size; oy = hash3(vi, vj, 2) * size
        th = hash3(vi, vj, 3) * 2 * np.pi if rotatable else 0 * vi
        c = np.cos(th); s = np.sin(th)
        dx = p[..., 0] - cx; dy = p[..., 1] - cy
        tu = c * dx - s * dy + cx + ox; tv = s * dx + c * dy + cy + oy
        val = bilinear(tex, tu, tv)
        w = ws[..., n:n + 1]
        acc = acc + w * ((val - mean) if blend == 'vp' else val)
    if blend == 'vp':
        return mean + acc / np.sqrt((ws ** 2).sum(-1, keepdims=True))
    return acc


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('look'); ap.add_argument('surface'); ap.add_argument('out')
    ap.add_argument('--texel', type=float, default=2.5); ap.add_argument('--stexel', type=float, default=1.0); ap.add_argument('--hex', type=float, default=300)
    ap.add_argument('--blend', default='vp'); ap.add_argument('--w', type=int, default=900); ap.add_argument('--h', type=int, default=520)
    ap.add_argument('--edge', type=float, default=0.25, help='threshold softness (pencil ~0.25)')
    ap.add_argument('--density', type=float, default=0.7, help='how dark one grain of the medium is')
    a = ap.parse_args()
    look = np.asarray(Image.open(a.look).convert('RGB'), float) / 255
    surf = np.asarray(Image.open(a.surface).convert('RGBA'), float) / 255
    hmap = surf[..., 2:3]
    Y, X = np.mgrid[0:a.h, 0:a.w].astype(float)
    X += 5000; Y += 3000
    lmean = look.reshape(-1, 3).mean(0)
    bg = np.clip(hex_read(look, X, Y, a.texel, a.hex, True, lmean, a.blend), 0, 1)
    h = np.clip(hex_read(hmap, X, Y, a.stexel, a.hex, True, np.array([0.5]), a.blend), 0, 1)[..., 0]
    Y -= 3000; X -= 5000
    # Dry strokes: rows of horizontal pressure ramps, light → heavy, with soft round tips (radius 7 doc px).
    cov = np.zeros_like(h)
    for row, (y0, rad) in enumerate([(70, 7.0), (150, 12.0), (240, 20.0)]):
        p = np.clip(X / a.w, 0, 1)          # pressure ramps across
        tip = np.clip(1 - np.abs(Y - y0) / rad, 0, 1)
        level = p * np.sqrt(tip)
        g = np.clip((h - (1 - level)) / a.edge + 0.5, 0, 1)
        cov = np.maximum(cov, g * (tip > 0))
    # A hatch block at mid pressure, many overlapping diagonal strokes.
    for k in range(-40, 40):
        d = np.abs((X - 80) - (Y - 330) - k * 9) / np.sqrt(2)
        inside = (X > 60) & (X < 420) & (Y > 330) & (Y < 500)
        tip = np.clip(1 - d / 4.5, 0, 1) * inside
        g = np.clip((h - (1 - 0.55 * np.sqrt(tip))) / a.edge + 0.5, 0, 1)
        cov = np.maximum(cov, g * (tip > 0))
    # A soft shading patch: side of the lead, light and wide.
    inside = (X > 470) & (X < 860) & (Y > 330) & (Y < 500)
    g = np.clip((h - (1 - 0.35)) / a.edge + 0.5, 0, 1) * inside
    cov = np.maximum(cov, g)
    graphite = np.array([0.18, 0.18, 0.2])
    out = bg * (1 - cov[..., None] * a.density)
    Image.fromarray(np.round(np.clip(out, 0, 1) * 255).astype(np.uint8)).save(a.out)


if __name__ == '__main__':
    main()
