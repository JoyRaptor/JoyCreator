"""Build the true-size comparison tiles and the options page. argv: eval paper dir, plan_sizes.json, output dir (page + tiles)."""
import io, json, os, sys
import numpy as np
import cv2
from PIL import Image

D, PLAN, OUT = sys.argv[1:4]
TILES = os.path.join(OUT, 'paper-size-options')
os.makedirs(TILES, exist_ok=True)
c = json.load(open(os.path.join(D, 'catalogue.json'), encoding='utf8'))
looks = {l['id']: l for l in c['looks']}
surfs = {s['id']: s for s in c['surfaces']}
plan = json.load(open(PLAN))
N = 240
PAPERS = [('eval_linen_1', 'Fine linen canvas 1'), ('eval_jute_3', 'Rough jute canvas 3'), ('eval_papyrus_1', 'Papyrus 1'),
          ('eval_silk_1', 'Silk 1'), ('eval_expanded_firm', 'Firmly crumpled paper')]
ZOOMS = [1, 3]
LOOK_OPTS = [('L0', 'Today: full size', None), ('L1', 'Half size, fine', ('look1024_jpg88', 1024, 88)),
             ('L2', 'Half size, tighter', ('look1024_jpg80', 1024, 80)), ('L3', 'Quarter size', ('look512_jpg88', 512, 88))]
TOOTH_OPTS = [('H0', 'Today: full size', None), ('H1', 'Half size, exact', ('height1024_png', 1024, 0)),
              ('H2', 'Half size, fine', ('height1024_jpg90', 1024, 90)), ('H3', 'Half size, tighter', ('height1024_jpg80', 1024, 80)),
              ('H4', 'Half size, tightest', ('height1024_jpg65', 1024, 65)), ('H5', 'Quarter size', ('height512_jpg90', 512, 90))]
FLUID_OPTS = [('F0', 'Today: full size, all four maps', None), ('F1', 'Half size, all four maps', 'fluid256_png'),
              ('F2', 'Half size, only the map the paint reads', 'fluid256_R_jpg90')]


def rt(im, fmt, **kw):
    b = io.BytesIO(); im.save(b, fmt, **kw); return Image.open(io.BytesIO(b.getvalue()))


def view(tex, texel_px, zoom, origin=(300.0, 300.0)):
    """What the canvas shows: N screen pixels of the document at this zoom, texture repeating, smooth sampling."""
    tex = np.asarray(tex).astype(np.float32)
    n = tex.shape[0]
    span_doc = N / zoom
    span_tex = span_doc / texel_px
    x0 = origin[0] / texel_px; y0 = origin[1] / texel_px
    k = int(np.ceil(span_tex)) + 4
    ix = (int(np.floor(x0)) + np.arange(k)) % n
    iy = (int(np.floor(y0)) + np.arange(k)) % n
    patch = tex[np.ix_(iy, ix)]
    fx = x0 - np.floor(x0); fy = y0 - np.floor(y0)
    scale = N / span_tex
    m = np.float32([[scale, 0, -fx * scale], [0, scale, -fy * scale]])
    interp = cv2.INTER_AREA if scale < 1 else cv2.INTER_LINEAR
    if scale < 1:
        # shrink first with area averaging (what the GPU's smaller copies do), then place
        patch = cv2.resize(patch, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
        m = np.float32([[1, 0, -fx * scale], [0, 1, -fy * scale]])
        interp = cv2.INTER_LINEAR
    return cv2.warpAffine(patch, m, (N, N), flags=interp, borderMode=cv2.BORDER_REFLECT)


def tooth_picture(h):
    """Left: the bare surface under a low lamp. Right: a soft pencil rubbed over it (graphite catches the high spots)."""
    h = h / 255.0
    lo, hi = np.percentile(h, 2), np.percentile(h, 98)
    hn = np.clip((h - lo) / max(hi - lo, 0.05), 0, 1)
    gx = cv2.Sobel(h, cv2.CV_32F, 1, 0, ksize=3); gy = cv2.Sobel(h, cv2.CV_32F, 0, 1, ksize=3)
    lit = np.clip(1.0 - (gx + gy) * 1.6, 0.6, 1.4)
    paper = 232.0 * lit
    cover = np.clip((hn - 0.42) / 0.28, 0, 1)
    pencil = paper * (1 - 0.82 * cover)
    out = paper.copy()
    out[:, N // 2:] = pencil[:, N // 2:]
    out[:, N // 2 - 1:N // 2 + 1] = 40
    return np.clip(out, 0, 255).astype(np.uint8)


def save(arr, name):
    Image.fromarray(arr.astype(np.uint8)).save(os.path.join(TILES, name), 'JPEG', quality=94, subsampling=0, optimize=True)


for pid, label in PAPERS:
    l = looks[pid]; s = surfs[l['defaultSurface']]
    look = Image.open(os.path.join(D, l['file'])).convert('RGB')
    hgt = Image.open(os.path.join(D, s['file'])).convert('L')
    for code, _, spec in LOOK_OPTS:
        if spec is None:
            im, tp = look, l['texelPx']
        else:
            _, n, q = spec
            im = rt(look.resize((n, n), Image.LANCZOS), 'JPEG', quality=q, optimize=True, progressive=True).convert('RGB')
            tp = l['texelPx'] * look.size[0] / n
        for z in ZOOMS:
            save(np.clip(view(im, tp, z), 0, 255), '%s_%s_z%d.jpg' % (pid, code, z))
    for code, _, spec in TOOTH_OPTS:
        if spec is None:
            im, tp = hgt, s['texelPx']
        else:
            _, n, q = spec
            small = hgt.resize((n, n), Image.BOX)
            im = small if q == 0 else rt(small, 'JPEG', quality=q, optimize=True).convert('L')
            tp = s['texelPx'] * hgt.size[0] / n
        for z in ZOOMS:
            save(tooth_picture(view(im, tp, z)), '%s_%s_z%d.jpg' % (pid, code, z))
    print(pid, flush=True)

tot = lambda k: round(sum(r['v'][k] for r in plan.values()) / 1e6, 1)
now = {p: round(sum(r['now'][p] for r in plan.values()) / 1e6, 1) for p in ('look', 'height', 'fluid')}
data = {
    'papers': [{'id': p, 'name': n} for p, n in PAPERS],
    'look': [{'code': c_, 'name': n, 'mb': now['look'] if s is None else tot(s[0])} for c_, n, s in LOOK_OPTS],
    'tooth': [{'code': c_, 'name': n, 'mb': now['height'] if s is None else tot(s[0])} for c_, n, s in TOOTH_OPTS],
    'fluid': [{'code': c_, 'name': n, 'mb': now['fluid'] if s is None else tot(s)} for c_, n, s in FLUID_OPTS],
    'count': len(plan), 'tile': N,
}
json.dump(data, open(os.path.join(OUT, 'page_data.json'), 'w'), indent=1)
print(json.dumps(data)[:1200])
print('tiles MB', round(sum(os.path.getsize(os.path.join(TILES, f)) for f in os.listdir(TILES)) / 1e6, 2), len(os.listdir(TILES)))
