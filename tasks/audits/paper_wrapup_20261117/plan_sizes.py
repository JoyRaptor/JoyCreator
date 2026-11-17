"""Sizes of the practical recipes (formats every reader in the project already decodes) for all eval papers."""
import io, json, os, sys
import numpy as np
from PIL import Image

D = sys.argv[1]; OUT = sys.argv[2]
c = json.load(open(os.path.join(D, 'catalogue.json'), encoding='utf8'))
surf = {s['id']: s for s in c['surfaces']}


def enc(im, fmt, **kw):
    b = io.BytesIO(); im.save(b, fmt, **kw); return b.getvalue()


def back(b):
    return np.asarray(Image.open(io.BytesIO(b))).astype(np.float32)


out = {}
for l in c['looks']:
    if not l['id'].startswith('eval_') or '_p1k' in l['id'] or '_p512' in l['id']:
        continue
    s = surf[l['defaultSurface']]
    look = Image.open(os.path.join(D, l['file'])).convert('RGB')
    h = Image.open(os.path.join(D, s['file'])).convert('L')
    fl = Image.open(os.path.join(D, s['fluid']))
    r = {'name': l['name'], 'now': {'look': os.path.getsize(os.path.join(D, l['file'])), 'height': os.path.getsize(os.path.join(D, s['file'])),
                                    'fluid': os.path.getsize(os.path.join(D, s['fluid']))}}
    v = {}
    for n in (1024, 512):
        lk = look.resize((n, n), Image.LANCZOS)
        for q in (88, 80, 70):
            v['look%d_jpg%d' % (n, q)] = len(enc(lk, 'JPEG', quality=q, optimize=True, progressive=True))
        hh = h.resize((n, n), Image.BOX)
        ref = np.asarray(hh).astype(np.float32)
        v['height%d_png' % n] = len(enc(hh, 'PNG', optimize=True))
        for q in (90, 80, 65, 50):
            b = enc(hh, 'JPEG', quality=q, optimize=True)
            d = back(b)
            v['height%d_jpg%d' % (n, q)] = len(b)
            v['height%d_jpg%d_err' % (n, q)] = [round(float(np.sqrt(((d - ref) ** 2).mean())), 2), round(float(d.std() / max(ref.std(), 1e-6)), 4),
                                               round(float(d.mean() - ref.mean()), 3)]
    f256 = fl.resize((256, 256), Image.BOX)
    v['fluid256_png'] = len(enc(f256, 'PNG', optimize=True))
    v['fluid512_R_jpg90'] = len(enc(fl.getchannel(0), 'JPEG', quality=90, optimize=True))
    v['fluid256_R_jpg90'] = len(enc(f256.getchannel(0), 'JPEG', quality=90, optimize=True))
    v['fluid256_R_png'] = len(enc(f256.getchannel(0), 'PNG', optimize=True))
    r['v'] = v
    out[l['id']] = r
    print(l['id'], flush=True)
json.dump(out, open(OUT, 'w'), indent=1)
keys = list(next(iter(out.values()))['v'])
n = len(out)
print('papers', n)
for part in ('look', 'height', 'fluid'):
    print('now', part, round(sum(r['now'][part] for r in out.values()) / 1e6, 2))
for k in keys:
    if k.endswith('_err'):
        e = np.array([r['v'][k] for r in out.values()])
        print('   ', k, 'rmse avg %.2f max %.2f  std ratio min %.4f  mean shift max %.3f' % (e[:, 0].mean(), e[:, 0].max(), e[:, 1].min(), np.abs(e[:, 2]).max()))
    else:
        print(k, 'total %.2f MB  avg %.0f KB' % (sum(r['v'][k] for r in out.values()) / 1e6, sum(r['v'][k] for r in out.values()) / n / 1e3))
