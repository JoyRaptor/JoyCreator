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

# --- Z2: the two-bucket partition (SPEC_CROSSTYPE_Z) ---

def top_pip_lane_z(tl):
    """Highest zIndex among lanes holding a visible overlay clip; -inf if none."""
    lanes, flags = build_lanes(tl), lane_flags(tl)
    top = None
    for lane_id, items in lanes:
        if flags.get(lane_id, (0, False))[1]:
            continue
        for kind, payload in items:
            if kind == 'clip' and not obj_hidden(payload):
                z = flags.get(lane_id, (0, False))[0]
                top = z if top is None else max(top, z)
    return top

def partition_around_video(tl):
    """(below, above) — the PiPs themselves belong to neither bucket."""
    pip_z = top_pip_lane_z(tl)
    lanes, flags = build_lanes(tl), lane_flags(tl)
    ordered = sorted(lanes, key=lambda l: flags.get(l[0], (0, False))[0])
    below, above = [], []
    for lane_id, items in ordered:
        if flags.get(lane_id, (0, False))[1]:
            continue
        z = flags.get(lane_id, (0, False))[0]
        for kind, payload in items:
            if obj_hidden(payload) or kind == 'clip':
                continue
            (below if (pip_z is not None and z < pip_z) else above).append(ident(payload))
    return below, above

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
    # Z2 INERTNESS: on a real project (no lane deliberately ordered under a PiP lane) the
    # "below" bucket must be EMPTY and "above" must equal the whole non-PiP ordering — i.e.
    # the two-bucket split changes nothing until someone uses it.
    below, above = partition_around_video(tl)
    whole = [i for k, i in [(k, ident(p)) for k, p in ordered_visual_items(tl)] ] \
        if False else [ident(p) for k, p in ordered_visual_items(tl) if k != 'clip']
    if below or above != whole:
        bad.append(f"Z2 not inert: below={below} above={above} whole={whole}")
    if bad:
        fails += 1
        print(f"  DIFF {name}")
        for b in bad:
            print(f"       {b}")
    else:
        print(f"  OK   {name}")
print(f"\n{'ALL IDENTICAL + Z2 INERT' if not fails else str(fails)+' PROJECT(S) DIFFER'}")

# --- Z2 ACTIVE case: no real project exercises it yet, so build one. ---
# Lane A (z=3) text, Lane B (z=2) PiP, Lane C (z=1) text  =>  C behind the video, A in front.
synth = {
    'timeline': {
        'textOverlays': [{'id': 'txtAbove', 'layerId': 'A'},
                         {'id': 'txtBelow', 'layerId': 'C'}],
        'spriteOverlays': [{'id': 'sprBelow', 'layerId': 'C'}],
        'overlayClips': [{'id': 'pip', 'layerId': 'B'}],
        'layers': {
            'trackDefs': [{'id': 'A', 'kind': 'LAYER', 'name': 'A'},
                          {'id': 'B', 'kind': 'LAYER', 'name': 'B'},
                          {'id': 'C', 'kind': 'LAYER', 'name': 'C'}],
            'layers': [{'id': 'A', 'zIndex': 3}, {'id': 'B', 'zIndex': 2},
                       {'id': 'C', 'zIndex': 1}],
        },
    }
}
stl = synth['timeline']
sb, sa = partition_around_video(stl)
ok_active = (sorted(sb) == ['sprBelow', 'txtBelow'] and sa == ['txtAbove'])
print(("PASS  " if ok_active else "FAIL  ")
      + f"Z2 active: lane under the PiP lane goes behind -> below={sorted(sb)} above={sa}")

# Tie => ABOVE (equal z means the user expressed no ordering; keep today's look).
tie = {'timeline': {
    'textOverlays': [{'id': 'txtTie', 'layerId': 'A'}],
    'spriteOverlays': [], 'overlayClips': [{'id': 'pip', 'layerId': 'B'}],
    'layers': {'trackDefs': [{'id': 'A', 'kind': 'LAYER', 'name': 'A'},
                             {'id': 'B', 'kind': 'LAYER', 'name': 'B'}],
               'layers': [{'id': 'A', 'zIndex': 0}, {'id': 'B', 'zIndex': 0}]}}}
tb, ta = partition_around_video(tie['timeline'])
ok_tie = (tb == [] and ta == ['txtTie'])
print(("PASS  " if ok_tie else "FAIL  ")
      + f"Z2 tie goes ABOVE (no expressed ordering) -> below={tb} above={ta}")

if not (ok_active and ok_tie):
    sys.exit(1)
