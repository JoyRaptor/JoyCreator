"""Make phone-sized copies of full-size paper masters. The masters are never changed.

A master paper is three pictures (look_*.jpg, height_*.png, fluid_*.png) plus its rows in catalogue.json.
This writes smaller copies and matching catalogue rows into an output folder; nothing is installed into
assets/paper/ by this script. Measured results and the owner's options: tasks/audits/paper_wrapup_20261117.md
and tasks/design/PAPER-size-options.html.

Usage:
  python shrink_papers.py <masters folder> <out folder> [--size 1024] [--look-q 80] [--height-q 80]
                          [--fluid half|same] [--only eval_linen_1,eval_jute_3] [--strip-prefix eval_]

--height-q 0 keeps the height an exact PNG (bigger). A JPEG height needs the catalogue rule that lets a
`packed: false` surface end in .jpg (PaperCatalogues.problems, 2026-11-17).

What changes with size, so the paper keeps its physical scale on the page (the rule Codex's reduced profiles
used, tasks/jobs/JB-PAPER-RES): halving the picture doubles texelPx and slopeRange and halves hexTexels.
The fluid map keeps all four channels: only its first is read by today's shaders, but the others are the
fibre direction and pore capacity a later engine may want.
"""
import argparse
import io
import json
import os

import numpy as np
from PIL import Image


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('masters'); ap.add_argument('out')
    ap.add_argument('--size', type=int, default=1024)
    ap.add_argument('--look-q', type=int, default=80)
    ap.add_argument('--height-q', type=int, default=80)
    ap.add_argument('--fluid', choices=('half', 'same'), default='half')
    ap.add_argument('--only', default='')
    ap.add_argument('--strip-prefix', default='')
    a = ap.parse_args()
    cat = json.load(open(os.path.join(a.masters, 'catalogue.json'), encoding='utf8'))
    surfaces = {s['id']: s for s in cat['surfaces']}
    only = set(x for x in a.only.split(',') if x)
    os.makedirs(a.out, exist_ok=True)
    out_s, out_l, before, after = [], [], 0, 0

    def new_id(i):
        return i[len(a.strip_prefix):] if a.strip_prefix and i.startswith(a.strip_prefix) else i

    def write(name, data):
        nonlocal after
        with open(os.path.join(a.out, name), 'wb') as f:
            f.write(data)
        after += len(data)

    def enc(im, fmt, **kw):
        b = io.BytesIO(); im.save(b, fmt, **kw); return b.getvalue()

    for look in cat['looks']:
        s = surfaces.get(look.get('defaultSurface') or '')
        if not look.get('file') or s is None or s.get('packed', True) or (only and look['id'] not in only):
            continue
        lim = Image.open(os.path.join(a.masters, look['file'])).convert('RGB')
        him = Image.open(os.path.join(a.masters, s['file'])).convert('L')
        if lim.size[0] <= a.size or him.size[0] <= a.size or any(x['id'] == new_id(s['id']) for x in out_s):
            continue  # already this small (or a surface two looks share, already done): leave it alone
        before += os.path.getsize(os.path.join(a.masters, look['file'])) + os.path.getsize(os.path.join(a.masters, s['file']))
        lid, sid = new_id(look['id']), new_id(s['id'])
        kl, kh = lim.size[0] / a.size, him.size[0] / a.size
        write('look_%s.jpg' % lid, enc(lim.resize((a.size, a.size), Image.LANCZOS), 'JPEG', quality=a.look_q, optimize=True, progressive=True))
        small = him.resize((a.size, a.size), Image.BOX)
        if a.height_q:
            data = enc(small, 'JPEG', quality=a.height_q, optimize=True); hname = 'height_%s.jpg' % sid
            small = Image.open(io.BytesIO(data)).convert('L')  # the mean recorded is the mean the app will read
        else:
            data = enc(small, 'PNG', optimize=True); hname = 'height_%s.png' % sid
        write(hname, data)
        ns = dict(s, id=sid, file=hname, size=a.size, texelPx=round(s['texelPx'] * kh, 6), slopeRange=round(s['slopeRange'] * kh, 4),
                  hexTexels=s['hexTexels'] / kh, heightMean=float(np.asarray(small).mean() / 255.0))
        if s.get('fluid'):
            fpath = os.path.join(a.masters, s['fluid'])
            before += os.path.getsize(fpath)
            fim = Image.open(fpath)
            kf = 2 if a.fluid == 'half' else 1
            if kf > 1:
                fim = fim.resize((fim.size[0] // kf, fim.size[1] // kf), Image.BOX)
            write('fluid_%s.png' % sid, enc(fim, 'PNG', optimize=True))
            ns.update(fluid='fluid_%s.png' % sid, fluidTexelPx=round(s['fluidTexelPx'] * kf, 6), fluidHexTexels=s['fluidHexTexels'] / kf)
        out_s.append(ns)
        out_l.append(dict(look, id=lid, file='look_%s.jpg' % lid, defaultSurface=sid, texelPx=round(look['texelPx'] * kl, 6),
                          hexTexels=look['hexTexels'] / kl))
        print('%-34s -> %s' % (look['id'], lid))
    json.dump({'format': cat['format'], 'version': cat['version'], 'surfaces': out_s, 'looks': out_l},
              open(os.path.join(a.out, 'catalogue.json'), 'w', encoding='utf8'), indent=1)
    if before:
        print('%d papers: %.1f MB -> %.1f MB (%.1f times smaller)' % (len(out_l), before / 1e6, after / 1e6, before / after))


if __name__ == '__main__':
    main()
