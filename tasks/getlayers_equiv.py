"""Regression proof for the neutral-substrate getLayers() rewrite.

Simulates the OLD (per-type, pre-d420272) and NEW (layerId-first) emission
algorithms against real project.json files and diffs the emitted row sequence
(id, kind, name, ordered item ids). They must be IDENTICAL for every project
that has no LAYER def and no cross-type layerId — i.e. every project that
exists today. Pre-sort order is compared: sortBandByZIndex is unchanged and
stable, so equal input order => equal output order.
"""
import json, sys, glob, os

# The seeded lane ids that have an emission phase of their own but no LayerTrackDef.
SEEDED = ('text', 'sprite', 'video')

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
            if i in SEEDED: continue  # its own phase emits it later (see Timeline.SEEDED_LANE_IDS)
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
# Print the match count and treat zero as a HARD FAILURE. A guard that "passes"
# over no files is worse than no guard: this one silently did exactly that once,
# because Windows python cannot resolve a /c/... path (pass C:/... instead).
_paths = sorted(glob.glob(sys.argv[1], recursive=True))
print(f"pattern : {sys.argv[1]}")
print(f"MATCHED : {len(_paths)} file(s)")
if not _paths:
    print("FAIL: zero files matched - nothing was verified.")
    sys.exit(1)
for path in _paths:
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

# ---------------------------------------------------------------------------
# Seeded-lane pre-emption regression (device-proven 2026-07-25, Note 9).
# A payload sitting on ANOTHER type's seeded lane must NOT let an earlier
# phase's leftover flush claim that lane: doing so renamed the row, flipped its
# TrackKind and HOISTED it up the band -- which is a silent z change under
# cross-type Z. Asserts the lane keeps its own kind, name and band position.
# ---------------------------------------------------------------------------
def _lane_case(name, tl, want):
    got = [(r[0], r[1], r[2]) for r in new_algo(tl)]
    ok = got == want
    print(f"{'PASS' if ok else 'FAIL'}  {name}")
    if not ok:
        print(f"      want={want}\n      got ={got}")
    return ok

seeded_ok = True
# A text overlay dropped on the seeded PiP lane: 'video' must stay VIDEO/'PiP'
# and stay LAST, with the text merged into it.
seeded_ok &= _lane_case(
    "text on the seeded PiP lane keeps it VIDEO/'PiP' and last",
    {'layers': {'trackDefs': []},
     'textOverlays': [{'id': 't1', 'layerId': 'video'}],
     'spriteOverlays': [{'id': 's1'}],
     'overlayClips': [{'id': 'v1', 'layerId': 'video'}]},
    [('sprite', 'SPRITE', 'Sprite'), ('video', 'VIDEO', 'PiP')])
# A text dropped on the seeded Sprite lane: 'sprite' must stay SPRITE/'Sprite'.
seeded_ok &= _lane_case(
    "text on the seeded Sprite lane keeps it SPRITE/'Sprite'",
    {'layers': {'trackDefs': []},
     'textOverlays': [{'id': 't1', 'layerId': 'sprite'}],
     'spriteOverlays': [{'id': 's1'}],
     'overlayClips': []},
    [('sprite', 'SPRITE', 'Sprite')])
# A sprite dropped on the seeded PiP lane: the sprite phase must not claim it.
seeded_ok &= _lane_case(
    "sprite on the seeded PiP lane keeps it VIDEO/'PiP'",
    {'layers': {'trackDefs': []},
     'textOverlays': [],
     'spriteOverlays': [{'id': 's1', 'layerId': 'video'}],
     'overlayClips': [{'id': 'v1', 'layerId': 'video'}]},
    [('video', 'VIDEO', 'PiP')])
# A genuine ORPHAN id must still be flushed in its phase (unchanged behavior).
seeded_ok &= _lane_case(
    "orphan layerId still flushes in its own phase",
    {'layers': {'trackDefs': []},
     'textOverlays': [{'id': 't1', 'layerId': 'sprite-legacy-uuid'}],
     'spriteOverlays': [], 'overlayClips': []},
    [('sprite-legacy-uuid', 'TEXT', 'Text')])

if not seeded_ok:
    sys.exit(1)
