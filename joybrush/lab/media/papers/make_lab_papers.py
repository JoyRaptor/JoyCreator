"""LAB-ONLY test surfaces for tuning the media engine until the paper session's photo papers land.
These are NOT shipped papers (the paper catalogue belongs to the paper session). Same RGBA surface format as
joybrush/tools/paper/pack.py (slopes R,G; height B; height^2 A), so the engine reads them exactly as it will
read the real ones.

  python make_lab_papers.py      -> lab_drawing.png, lab_coldpress.png, lab_catalogue.json
"""
import json, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', '..', '..', 'tools', 'paper'))
from pack import pack  # noqa: E402

N = 1024


def band(rng, wavelength, width=0.5):
    """Periodic band-pass noise around a wavelength (texels); log-gaussian in frequency."""
    w = rng.standard_normal((N, N))
    f = np.sqrt(np.fft.fftfreq(N)[:, None] ** 2 + np.fft.fftfreq(N)[None, :] ** 2)
    fc = 1.0 / wavelength
    mask = np.exp(-(np.log(np.maximum(f, 1e-9) / fc) ** 2) / (2 * width ** 2))
    mask[0, 0] = 0
    out = np.real(np.fft.ifft2(np.fft.fft2(w) * mask))
    return out / out.std()


def fibres(rng, count, length, width, bias_angle=0.0, bias=0.0):
    """Short curved ridges (cellulose fibres), drawn on a torus."""
    img = np.zeros((N, N))
    for _ in range(count):
        L = rng.uniform(0.5, 1.5) * length
        a = rng.uniform(0, np.pi) if rng.random() > bias else bias_angle + rng.normal(0, 0.3)
        curv = rng.normal(0, 0.6 / L)
        x, y = rng.uniform(0, N, 2)
        steps = int(L)
        amp = rng.uniform(0.4, 1.0)
        wd = width * rng.uniform(0.7, 1.4)
        r = int(np.ceil(wd * 2.5))
        for s in range(steps):
            a += curv
            x += np.cos(a); y += np.sin(a)
            xi, yi = int(x), int(y)
            for dy in range(-r, r + 1):
                for dx in range(-r, r + 1):
                    d2 = (xi + dx - x) ** 2 + (yi + dy - y) ** 2
                    v = amp * np.exp(-d2 / (2 * wd * wd))
                    yy, xx = (yi + dy) % N, (xi + dx) % N
                    if v > img[yy, xx]:
                        img[yy, xx] = v
    return img


def pebbles(rng, cell, jitter=0.9):
    # Felt-pressed watercolour paper: densely packed rounded hills with a connected network of valleys.
    # Distance to the nearest scattered centre (Worley F1) on a torus, inverted and softened.
    n = max(2, int(N / cell))
    gy, gx = np.mgrid[0:n, 0:n]
    cx = (gx + 0.5 + jitter * (rng.random((n, n)) - 0.5)) * (N / n)
    cy = (gy + 0.5 + jitter * (rng.random((n, n)) - 0.5)) * (N / n)
    size = rng.uniform(0.7, 1.3, (n, n))
    yy, xx = np.mgrid[0:N, 0:N].astype(np.float32)
    best = np.full((N, N), 1e9, np.float32)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            for j in range(n):
                for i in range(n):
                    px, py = cx[j, i] + dx * N, cy[j, i] + dy * N
                    if abs(px - N / 2) > N / 2 + 2 * cell or abs(py - N / 2) > N / 2 + 2 * cell:
                        continue
                    x0, x1 = int(max(0, px - 2 * cell)), int(min(N, px + 2 * cell))
                    y0, y1 = int(max(0, py - 2 * cell)), int(min(N, py + 2 * cell))
                    if x1 <= x0 or y1 <= y0:
                        continue
                    d = np.hypot(xx[y0:y1, x0:x1] - px, yy[y0:y1, x0:x1] - py) / size[j, i]
                    best[y0:y1, x0:x1] = np.minimum(best[y0:y1, x0:x1], d)
    t = np.clip(best / cell, 0, 1)
    return 1 - t * t * (3 - 2 * t)            # smooth domes, valleys where the domes meet


def to_uniform(h):
    ranks = np.argsort(np.argsort(h.ravel()))
    return (ranks / (h.size - 1)).reshape(h.shape)


def write(name, h, texel_px, hex_texels, rotatable, catalogue, physical):
    hb = np.rint(np.clip(h, 0, 1) * 255).astype(np.uint8)
    out, sr = pack(hb)          # only for its slopeRange: the lab packs slopes itself from the height picture
    Image.fromarray(hb, 'L').save(os.path.join(HERE, name + '_height.png'), optimize=True)
    catalogue.append({'id': name, 'name': name.replace('_', ' '), 'file': name + '_height.png', 'heightFile': name + '_height.png',
                      'packed': False, 'size': N,
                      'texelPx': texel_px, 'slopeRange': sr, 'hexTexels': hex_texels, 'rotatable': rotatable,
                      'relief': 1.0, 'heightMean': float(hb.mean() / 255), **physical})
    print(name, 'slopeRange', sr)


def main():
    rng = np.random.default_rng(20261006)
    cat = []
    # Drawing paper (medium tooth): fine rounded grains ~0.12 mm, sparse surface fibres, faint formation.
    tooth = band(rng, 4.0, 0.45)
    tooth = np.sign(tooth) * np.abs(tooth) ** 0.8          # rounder tops, sharper pits
    fib = fibres(rng, 700, 60, 1.0, bias_angle=0.15, bias=0.35)
    form = band(rng, 90, 0.6)
    h = to_uniform(tooth + 0.16 * (fib - fib.mean()) / (fib.std() + 1e-9) + 0.22 * form)
    write('lab_drawing', h, 0.5, 300, True, cat, {'toothDepthMm': 0.07, 'compliance': 0.55})
    # Cold-press watercolour paper: felt-pressed soft bumps ~1 mm, fine fibre tooth on top.
    # Felt-pressed hills: a BROAD band of sizes (a narrow band reads as worms, cells read as foam).
    peb = band(rng, 22, 0.95)
    peb = np.tanh(peb * 1.1)                     # tops pressed a little flat by the felt
    swell = band(rng, 120, 0.5)                 # the sheet's gentle unevenness
    fine = band(rng, 4, 0.5)
    fib2 = fibres(rng, 500, 70, 1.1, bias=0.0)
    h2 = to_uniform(peb + 0.25 * swell + 0.18 * fine + 0.08 * (fib2 - fib2.mean()) / (fib2.std() + 1e-9))
    write('lab_coldpress', h2, 1.0, 300, True, cat, {'toothDepthMm': 0.12, 'compliance': 0.35, 'sizing': 0.8,
                                                       'absorbency': 0.4, 'capacity': 0.6, 'wickSpeed': 0.4, 'anisotropy': 0.1})
    with open(os.path.join(HERE, 'lab_catalogue.json'), 'w') as f:
        json.dump({'format': 'joybrush-papers', 'version': 1, 'note': 'LAB ONLY - not shipped', 'surfaces': cat, 'looks': []}, f, indent=1)


if __name__ == '__main__':
    main()
