import json, os, sys
from PIL import Image
Image.MAX_IMAGE_PIXELS = None

def info(d, f):
    if not f:
        return None
    p = os.path.join(d, f)
    if not os.path.exists(p):
        return dict(file=f, missing=True)
    im = Image.open(p)
    return dict(file=f, bytes=os.path.getsize(p), w=im.size[0], h=im.size[1], mode=im.mode)

def run(d, tag):
    c = json.load(open(os.path.join(d, 'catalogue.json'), encoding='utf8'))
    rows = []
    tot = dict(look=0, height=0, fluid=0)
    seen = set()
    for s in c['surfaces']:
        h = info(d, s.get('file')); fl = info(d, s.get('fluid'))
        rows.append(('surface', s['id'], s['name'], h, fl, s))
        for k, x in (('height', h), ('fluid', fl)):
            if x and not x.get('missing') and x['file'] not in seen:
                seen.add(x['file']); tot[k] += x['bytes']
    for l in c['looks']:
        x = info(d, l.get('file'))
        rows.append(('look', l['id'], l['name'], x, None, l))
        if x and not x.get('missing') and x['file'] not in seen:
            seen.add(x['file']); tot['look'] += x['bytes']
    onDisk = {f for f in os.listdir(d) if f.lower().endswith(('.png', '.jpg', '.webp'))}
    print('==', tag, 'surfaces', len(c['surfaces']), 'looks', len(c['looks']))
    print('totals MB', {k: round(v / 1e6, 2) for k, v in tot.items()}, 'sum', round(sum(tot.values()) / 1e6, 2))
    print('unreferenced files', sorted(onDisk - seen))
    def f(x):
        return '-' if not x else ('MISSING ' + x['file'] if x.get('missing') else '%s %dx%d %s %.2fMB' % (x['file'], x['w'], x['h'], x['mode'], x['bytes'] / 1e6))
    for kind, id_, name, a, b, raw in rows:
        extra = ''
        if kind == 'surface':
            extra = ' texel=%s hex=%s relief=%s packed=%s depth=%s abs=%s' % (raw.get('texelPx'), raw.get('hexTexels'), raw.get('relief'), raw.get('packed', True), raw.get('toothDepthMm'), raw.get('absorbency'))
        else:
            extra = ' base=%s surf=%s texel=%s' % (raw.get('base'), raw.get('defaultSurface'), raw.get('texelPx'))
        print(kind[0], id_, '|', name, '|', f(a), '|', f(b), '|', extra)
    return c, rows

for d, tag in zip(sys.argv[1::2], sys.argv[2::2]):
    run(d, tag)
