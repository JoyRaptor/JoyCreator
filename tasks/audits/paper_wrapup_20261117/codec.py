"""Measure smaller encodings of every paper map. Writes codec.json. Nothing in the repo is touched."""
import io, json, os, sys
import numpy as np
from PIL import Image, features

D = sys.argv[1]
OUT = sys.argv[2]
ONLY = sys.argv[3] if len(sys.argv) > 3 else ''
assert features.check('webp'), 'no webp'
c = json.load(open(os.path.join(D, 'catalogue.json'), encoding='utf8'))


def enc(im, fmt, **kw):
    b = io.BytesIO()
    im.save(b, fmt, **kw)
    return b.getvalue()


def dec(b):
    return Image.open(io.BytesIO(b))


def grey_back(b):
    im = dec(b)
    if im.mode != 'L':
        im = im.convert('RGB')
        return np.asarray(im)[:, :, 1].astype(np.float32)
    return np.asarray(im).astype(np.float32)


def slope_rms(a):
    gx = np.roll(a, -1, 1) - a
    gy = np.roll(a, -1, 0) - a
    return float(np.sqrt((gx * gx + gy * gy).mean()))


def height_variants(path):
    im = Image.open(path)
    full = im.convert('L')
    a0 = np.asarray(full).astype(np.float32)
    n = full.size[0]
    res = {'src_bytes': os.path.getsize(path), 'n': n, 'std': float(a0.std()), 'slope': slope_rms(a0)}
    v = {}
    sizes = [n] if n <= 1024 else [n // 2]
    for s in sizes:
        g = full if s == n else full.resize((s, s), Image.BOX)
        gs = np.asarray(g).astype(np.float32)
        cands = {
            'png': enc(g, 'PNG', optimize=True),
            'webpll': enc(g, 'WEBP', lossless=True, quality=100, method=4),
        }
        for q in (90, 80, 65):
            cands['webp%d' % q] = enc(g.convert('RGB'), 'WEBP', quality=q, method=4)
        for k, b in cands.items():
            back = grey_back(b)
            # error against the same-size reference (what the format itself loses)
            e = float(np.sqrt(((back - gs) ** 2).mean()))
            v['%d_%s' % (s, k)] = {'bytes': len(b), 'rmse': round(e, 3), 'std': round(float(back.std()), 3),
                                   'slope': round(slope_rms(back), 3), 'mean_shift': round(float(back.mean() - gs.mean()), 3)}
        if s != n:
            v['%d_ref' % s] = {'std': round(float(gs.std()), 3), 'slope': round(slope_rms(gs), 3)}
    res['v'] = v
    return res


def look_variants(path):
    im = Image.open(path).convert('RGB')
    n = im.size[0]
    a0 = np.asarray(im).astype(np.float32)
    res = {'src_bytes': os.path.getsize(path), 'n': n, 'std': float(a0.mean(2).std())}
    v = {}
    sizes = [n] if n <= 1024 else [n // 2]
    for s in sizes:
        g = im if s == n else im.resize((s, s), Image.LANCZOS)
        gs = np.asarray(g).astype(np.float32)
        cands = {}
        for q in (80, 70):
            cands['webp%d' % q] = enc(g, 'WEBP', quality=q, method=4)
        for q in (80,):
            cands['jpg%d' % q] = enc(g, 'JPEG', quality=q, optimize=True, progressive=True)
        for k, b in cands.items():
            back = np.asarray(dec(b).convert('RGB')).astype(np.float32)
            mse = ((back - gs) ** 2).mean()
            v['%d_%s' % (s, k)] = {'bytes': len(b), 'psnr': round(float(10 * np.log10(255 * 255 / max(mse, 1e-9))), 2),
                                   'std': round(float(back.mean(2).std()), 3)}
    res['v'] = v
    return res


def fluid_variants(path):
    im = Image.open(path)
    n = im.size[0]
    a0 = np.asarray(im).astype(np.float32)
    res = {'src_bytes': os.path.getsize(path), 'n': n, 'mode': im.mode,
           'chan_std': [round(float(a0[:, :, i].std()), 2) for i in range(a0.shape[2])],
           'chan_mean': [round(float(a0[:, :, i].mean()), 2) for i in range(a0.shape[2])]}
    # channel correlation: can one channel be derived from another?
    flat = a0.reshape(-1, a0.shape[2])
    if flat.std(0).min() > 0:
        res['corr'] = np.round(np.corrcoef(flat.T), 3).tolist()
    v = {}
    sizes = [n] if n <= 256 else [n // 2]
    for s in sizes:
        g = im if s == n else im.resize((s, s), Image.BOX)
        gs = np.asarray(g).astype(np.float32)
        cands = {'png': enc(g, 'PNG', optimize=True),
                 'webpll': enc(g, 'WEBP', lossless=True, quality=100, method=4, exact=True)}
        for k, b in cands.items():
            back = np.asarray(dec(b).convert(im.mode)).astype(np.float32)
            v['%d_%s' % (s, k)] = {'bytes': len(b), 'rmse': round(float(np.sqrt(((back - gs) ** 2).mean())), 3)}
        # each channel as its own lossy grey picture (two files of two... reported as a sum)
        for q in (90, 75):
            tot = 0; err = 0
            for i in range(gs.shape[2]):
                ch = Image.fromarray(gs[:, :, i].astype(np.uint8), 'L')
                b = enc(ch.convert('RGB'), 'WEBP', quality=q, method=4)
                tot += len(b); err += ((grey_back(b) - gs[:, :, i]) ** 2).mean()
            v['%d_chanwebp%d' % (s, q)] = {'bytes': tot, 'rmse': round(float(np.sqrt(err / gs.shape[2])), 3)}
    res['v'] = v
    return res


out = {'surfaces': {}, 'looks': {}}
for s in c['surfaces']:
    if ONLY and ONLY not in s['id']:
        continue
    r = {'packed': s.get('packed', True)}
    if not r['packed']:
        r['height'] = height_variants(os.path.join(D, s['file']))
    else:
        r['height'] = {'src_bytes': os.path.getsize(os.path.join(D, s['file']))}
    if s.get('fluid'):
        r['fluid'] = fluid_variants(os.path.join(D, s['fluid']))
    out['surfaces'][s['id']] = r
    print('s', s['id'], flush=True)
for l in c['looks']:
    if ONLY and ONLY not in l['id']:
        continue
    if l.get('file'):
        out['looks'][l['id']] = look_variants(os.path.join(D, l['file']))
        print('l', l['id'], flush=True)
json.dump(out, open(OUT, 'w'), indent=1)
