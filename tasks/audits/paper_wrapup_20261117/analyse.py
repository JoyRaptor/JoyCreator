"""Per paper: colour, contrast, wrap seam, large-scale unevenness (repeat risk), nearest neighbour. Read-only."""
import json, os, sys
import numpy as np
from PIL import Image

D = sys.argv[1]; OUT = sys.argv[2]
c = json.load(open(os.path.join(D, 'catalogue.json'), encoding='utf8'))
surf = {s['id']: s for s in c['surfaces']}


def seam(a):
    """edge-to-edge step across the wrap, against ordinary neighbour steps (1 = invisible)."""
    a = a.astype(np.float32)
    inner = np.abs(np.diff(a, axis=1)).mean()
    wrapx = np.abs(a[:, 0] - a[:, -1]).mean()
    innery = np.abs(np.diff(a, axis=0)).mean()
    wrapy = np.abs(a[0] - a[-1]).mean()
    return float(max(wrapx / max(inner, 1e-6), wrapy / max(innery, 1e-6)))


def blotch(a, cells=4):
    """spread of the means of a cells x cells grid, in grey levels: what the eye sees repeat when tiled."""
    n = a.shape[0] // cells
    m = a[:n * cells, :n * cells].astype(np.float32).reshape(cells, n, cells, n).mean((1, 3))
    return float(m.max() - m.min()), float(m.std())


def lab(rgb):
    import cv2
    px = np.uint8([[rgb]])
    return cv2.cvtColor(px, cv2.COLOR_RGB2LAB)[0, 0].astype(np.float32) * [100 / 255, 1, 1]


rows = {}
feat = {}
for l in c['looks']:
    if not l.get('file'):
        continue
    im = Image.open(os.path.join(D, l['file'])).convert('RGB')
    a = np.asarray(im)
    g = a.mean(2)
    r = {'name': l['name'], 'surface': l.get('defaultSurface'), 'look_n': im.size[0],
         'mean': [int(x) for x in a.reshape(-1, 3).mean(0)], 'look_std': round(float(g.std()), 2),
         'look_seam': round(seam(g), 2), 'look_blotch': round(blotch(g)[0], 2), 'light': l.get('lightByDefault', True),
         'look_texel': l.get('texelPx')}
    # fine contrast only (what survives in a small swatch): remove anything larger than 32 px
    import cv2
    fine = g - cv2.blur(g, (33, 33))
    r['look_fine_std'] = round(float(fine.std()), 2)
    s = surf.get(l.get('defaultSurface'))
    if s and not s.get('packed', True):
        h = np.asarray(Image.open(os.path.join(D, s['file'])).convert('L'))
        r.update(h_n=h.shape[0], h_std=round(float(h.std()), 2), h_seam=round(seam(h), 2), h_blotch=round(blotch(h)[0], 2),
                 relief=s.get('relief'), texel=s.get('texelPx'), has_fluid=bool(s.get('fluid')),
                 phys=[s.get(k) for k in ('toothDepthMm', 'compliance', 'sizing', 'absorbency', 'capacity', 'wickSpeed')])
        # spectrum signature of the tooth: 16 radial bands, for "is this the same texture"
        f = np.abs(np.fft.fft2((h - h.mean()) / max(h.std(), 1e-6)))
        f = np.fft.fftshift(f)
        n = h.shape[0]; yy, xx = np.mgrid[-n // 2:n // 2, -n // 2:n // 2]
        rad = np.sqrt(xx * xx + yy * yy) * (1.0 / (s.get('texelPx', 1) * n))  # cycles per document pixel
        bands = np.logspace(np.log10(1 / 512), np.log10(0.5), 13)
        sig = [float((f[(rad >= bands[i]) & (rad < bands[i + 1])] ** 2).sum()) for i in range(12)]
        tot = sum(sig) or 1
        r['h_sig'] = [round(x / tot, 3) for x in sig]
        # directionality: horizontal vs vertical energy
        r['h_dir'] = round(float(np.abs(np.diff(h.astype(np.float32), axis=1)).mean() / max(np.abs(np.diff(h.astype(np.float32), axis=0)).mean(), 1e-6)), 2)
    elif s:
        r['packed_surface'] = True
    rows[l['id']] = r
    feat[l['id']] = lab(r['mean'])
    print(l['id'], flush=True)

ids = list(rows)
for i in ids:
    best = None
    for j in ids:
        if i == j:
            continue
        de = float(np.linalg.norm(feat[i] - feat[j]))
        ds = 0.0
        if 'h_sig' in rows[i] and 'h_sig' in rows[j]:
            ds = float(np.abs(np.array(rows[i]['h_sig']) - np.array(rows[j]['h_sig'])).sum())
        else:
            ds = 1.0
        score = de / 10 + ds * 2 + abs(rows[i]['look_fine_std'] - rows[j]['look_fine_std']) / 5
        if best is None or score < best[0]:
            best = (score, j, de, ds)
    rows[i]['nearest'] = {'id': best[1], 'score': round(best[0], 2), 'dE': round(best[2], 1), 'tooth_diff': round(best[3], 2)}
json.dump(rows, open(OUT, 'w'), indent=1)
