"""Regression proof for the neutral-substrate getLayers() rewrite.

Simulates the OLD (per-type, pre-d420272) and NEW (layerId-first) emission
algorithms against real project.json files and diffs the emitted row sequence
(id, kind, name, ordered item ids). They must be IDENTICAL for every project
that has no LAYER def and no cross-type layerId — i.e. every project that
exists today. Pre-sort order is compared: sortBandByZIndex is unchanged and
stable, so equal input order => equal output order.
"""
import json, sys, glob, os

def group(items, default_id, key='layerId'):
    out = {}
    for it in items:
        lid = it.get(key) or default_id
        out.setdefault(lid, []).append(it)
    return out

def ident(it):
    return it.get('id') or it.get('sheetId') or repr(sorted(it.items()))[:40]

def old_algo(tl):
    defs = tl['layers'].get('trackDefs', [])
    texts = group(tl.get('textOverlays', []), 'text')
    sprites = group(tl.get('spriteOverlays', []), 'sprite')
    # overlay clips: layerId is never null in practice; default "video"
    videos = group(tl.get('overlayClips', []), 'video')
    rows = []
    b = dict(texts)
    d = b.pop('text', None)
    if d: rows.append(('text', 'TEXT', 'Text', [ident(i) for i in d]))
    for de in defs:
        if de['kind'] not in ('TEXT', 'STICKER'): continue
        rows.append((de['id'], de['kind'], de['name'], [ident(i) for i in b.pop(de['id'], [])]))
    for k, v in b.items():
        rows.append((k, 'TEXT', 'Text', [ident(i) for i in v]))

    b = dict(sprites)
    d = b.pop('sprite', None)
    if d: rows.append(('sprite', 'SPRITE', 'Sprite', [ident(i) for i in d]))
    for de in defs:
        if de['kind'] != 'SPRITE': continue
        rows.append((de['id'], 'SPRITE', de['name'], [ident(i) for i in b.pop(de['id'], [])]))
    for k, v in b.items():
        rows.append((k, 'SPRITE', 'Sprite', [ident(i) for i in v]))

    b = dict(videos)
    d = b.pop('video', None)
    if d: rows.append(('video', 'VIDEO', 'PiP', [ident(i) for i in d]))
    for de in defs:
        if de['kind'] not in ('VIDEO', 'IMAGE'): continue
        rows.append((de['id'], de['kind'], de['name'], [ident(i) for i in b.pop(de['id'], [])]))
    for k, v in b.items():
        rows.append((k, 'VIDEO', 'PiP', [ident(i) for i in v]))
    return rows

def new_algo(tl):
    defs = tl['layers'].get('trackDefs', [])
    texts = group(tl.get('textOverlays', []), 'text')
    sprites = group(tl.get('spriteOverlays', []), 'sprite')
    videos = group(tl.get('overlayClips', []), 'video')
    def_ids = {de['id'] for de in defs}
    rows = []

    def has(i):
        return i in texts or i in sprites or i in videos

    def build(i, kind, name):
        items = [ident(x) for x in texts.pop(i, [])]
        items += [ident(x) for x in sprites.pop(i, [])]
        items += [ident(x) for x in videos.pop(i, [])]
        rows.append((i, kind, name, items))

    def emit_defs(*kinds):
        for de in defs:
            if de['kind'] in kinds:
                build(de['id'], de['kind'], de['name'])

    def flush(m, kind, name):
        for i in list(m.keys()):
            if i in def_ids: continue
            build(i, kind, name)

    if has('text'): build('text', 'TEXT', 'Text')
    emit_defs('TEXT', 'STICKER'); flush(texts, 'TEXT', 'Text')
    if has('sprite'): build('sprite', 'SPRITE', 'Sprite')
    emit_defs('SPRITE'); flush(sprites, 'SPRITE', 'Sprite')
    if has('video'): build('video', 'VIDEO', 'PiP')
    emit_defs('VIDEO', 'IMAGE'); flush(videos, 'VIDEO', 'PiP')
    emit_defs('LAYER')
    for i in list(texts.keys()) + list(sprites.keys()) + list(videos.keys()):
        if has(i): build(i, 'LAYER', 'Layer')
    return rows

fails = 0
for path in sorted(glob.glob(sys.argv[1])):
    j = json.load(open(path, encoding='utf-8'))
    tl = j['timeline']
    if 'layers' not in tl:
        print(f"skip (no layers block): {os.path.basename(path)}"); continue
    o, n = old_algo(tl), new_algo(tl)
    name = j.get('name') or os.path.basename(path)[:20]
    if o == n:
        print(f"  OK   {name:<28} rows={len(o)}")
    else:
        fails += 1
        print(f"  DIFF {name:<28}")
        for a, b in zip(o, n):
            if a != b: print(f"       old={a}\n       new={b}")
        if len(o) != len(n):
            print(f"       row-count {len(o)} -> {len(n)}")
            for extra in n[len(o):]: print(f"       new-only={extra}")
            for extra in o[len(n):]: print(f"       old-only={extra}")
print(f"\n{'ALL IDENTICAL' if not fails else str(fails)+' PROJECT(S) DIFFER'}")
