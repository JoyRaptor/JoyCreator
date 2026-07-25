"""Regression proof for the Z1 refactor (SPEC_CROSSTYPE_Z).

LayerPreviewController's three visible*() methods each used to re-derive the
paint ordering (sort lanes by zIndex, skip hidden lanes, walk items, apply the
per-object eye). They are now DERIVED from one shared ordering,
orderedVisualItems(). This simulates both formulations over real project.json
files and diffs the three resulting lists, in order.

They must be identical for every project: the refactor is pure code motion, and
this is what proves it rather than asserting it.

Usage:  python tasks/visible_equiv.py "<dir>/proj_*.json"
"""
import json, sys, glob, os

# --- shared: rebuild the lanes the way Timeline.getLayers() does (layerId-first) ---

def group(items, default_id):
    out = {}
    for it in items:
        out.setdefault(it.get('layerId') or default_id, []).append(it)
    return out

def build_lanes(tl):
    """[(lane_id, [item, ...])] in getLayers() emission order."""
    defs = tl['layers'].get('trackDefs', [])
    texts = group(tl.get('textOverlays', []), 'text')
    sprites = group(tl.get('spriteOverlays', []), 'sprite')
    videos = group(tl.get('overlayClips', []), 'video')
    def_ids = {d['id'] for d in defs}
    lanes = []

    def has(i):
        return i in texts or i in sprites or i in videos

    def build(i):
        items = []
        for x in texts.pop(i, []):   items.append(('text', x))
        for x in sprites.pop(i, []): items.append(('sprite', x))
        for x in videos.pop(i, []):  items.append(('clip', x))
        lanes.append((i, items))

    def emit_defs(*kinds):
        for d in defs:
            if d['kind'] in kinds:
                build(d['id'])

    def flush(m):
        for i in list(m.keys()):
            if i not in def_ids:
                build(i)

    if has('text'):   build('text')
    emit_defs('TEXT', 'STICKER'); flush(texts)
    if has('sprite'): build('sprite')
    emit_defs('SPRITE'); flush(sprites)
    if has('video'):  build('video')
    emit_defs('VIDEO', 'IMAGE'); flush(videos)
    emit_defs('LAYER')
    for i in list(texts) + list(sprites) + list(videos):
        if has(i):
            build(i)
    return lanes

def lane_flags(tl):
    """id -> (zIndex, hidden), from the serialized track entries."""
    out = {}
    for t in tl['layers'].get('layers', []):
        out[t.get('id')] = (t.get('zIndex', 0) or 0, bool(t.get('hidden', False)))
    return out

def obj_hidden(payload):
    return bool(payload.get('objHidden', False))

def ident(x):
    return x.get('id') or repr(sorted(x.items()))[:40]

# --- OLD: each visible*() sorts and walks the lanes itself ---

def old_visible(tl, want):
    lanes, flags = build_lanes(tl), lane_flags(tl)
    ordered = sorted(lanes, key=lambda l: flags.get(l[0], (0, False))[0])  # stable, ascending
    out = []
    for lane_id, items in ordered:
        if flags.get(lane_id, (0, False))[1]:
            continue  # hidden lane
        for kind, payload in items:
            if kind != want:
                continue
            if obj_hidden(payload):
                continue  # per-object eye
            out.append(ident(payload))
    return out

# --- NEW: one ordering, then filter by payload ---

def ordered_visual_items(tl):
    lanes, flags = build_lanes(tl), lane_flags(tl)
    ordered = sorted(lanes, key=lambda l: flags.get(l[0], (0, False))[0])
    out = []
    for lane_id, items in ordered:
        if flags.get(lane_id, (0, False))[1]:
            continue
        for kind, payload in items:
            if obj_hidden(payload):
                continue
            out.append((kind, payload))
    return out

def new_visible(tl, want):
    return [ident(p) for kind, p in ordered_visual_items(tl) if kind == want]

fails = 0
for path in sorted(glob.glob(sys.argv[1])):
    j = json.load(open(path, encoding='utf-8'))
    tl = j['timeline']
    if 'layers' not in tl:
        print(f"skip (no layers block): {os.path.basename(path)}"); continue
    name = j.get('name') or os.path.basename(path)[:20]
    bad = []
    for want, label in (('text', 'visibleTextOverlays'),
                        ('sprite', 'visibleSpriteItems'),
                        ('clip', 'visibleOverlayVideoClips')):
        o, n = old_visible(tl, want), new_visible(tl, want)
        if o != n:
            bad.append(f"{label}: old={o} new={n}")
    if bad:
        fails += 1
        print(f"  DIFF {name}")
        for b in bad:
            print(f"       {b}")
    else:
        print(f"  OK   {name}")
print(f"\n{'ALL THREE LISTS IDENTICAL' if not fails else str(fails)+' PROJECT(S) DIFFER'}")
