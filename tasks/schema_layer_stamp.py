"""Offline proof for audit 1.2 — the TrackKind.LAYER schema hole.

Three things are proved here, against REAL pulled project.json files, before any
Java is written:

  A. SURVEY — what schemaVersion each real project carries on disk today, and
     whether it has any trackDefs at all / any of kind LAYER. Establishes the
     blast radius of raising the stamp.

  B. CORRUPTION — simulate an OLD build (one whose TrackKind enum has no LAYER,
     so TrackKind.fromName("LAYER") falls back to VIDEO) loading a project with
     a LAYER def and re-serializing it. Diff the getLayers() row sequence before
     vs after. If the row MOVES BAND POSITION, paint order changed permanently.

  C. GUARD — show the ladder change (stamp 11 when a LAYER def exists) makes the
     old build's downgrade guard (on-disk > running) fire, so it never re-saves.

Run:  python tasks/schema_layer_stamp.py "C:/Users/JoyRaptor/fadcam-safety-2026-07-26/**/*.json"
Windows python cannot read /c/... paths — pass C:/... . The matched file count is
printed and zero matches is a HARD FAILURE (a guard that silently passes over no
files is worse than no guard).
"""
import json, sys, glob, os

# Reuse getlayers_equiv.py's new_algo rather than re-deriving the emission order —
# one authority. It cannot be imported (its module body runs a glob over argv and
# may sys.exit), so exec only the definitions ABOVE its driver line. If that marker
# ever moves, this fails loudly instead of silently testing a stale copy.
_src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                         'getlayers_equiv.py'), encoding='utf-8').read()
_MARKER = '\nfails = 0\n'
if _MARKER not in _src:
    print("FAIL: getlayers_equiv.py no longer has the 'fails = 0' driver marker; "
          "cannot safely extract new_algo.")
    sys.exit(1)
_ns = {}
exec(compile(_src.split(_MARKER)[0], 'getlayers_equiv.py', 'exec'), _ns)
new_algo = _ns['new_algo']

RUNNING_OLD = 10   # SCHEMA_VERSION of a build that has the downgrade guard but no LAYER kind
RUNNING_NEW = 11   # SCHEMA_VERSION after this fix

# ── the stamp ladder ────────────────────────────────────────────────────────────
# Mirrors ProjectStorage.ProjectSerializer. `layer_features` is the part that needs
# real Timeline semantics, so it is taken from the on-disk stamp instead of being
# reimplemented: every one of these files was WRITTEN by the current build, so its
# recorded schemaVersion IS what the old ladder produced for it.

def def_floor_new(tl):
    """The NEW term: max over trackDefs of TrackKind.minSchemaVersion()."""
    floor = 7
    for d in tl.get('layers', {}).get('trackDefs', []):
        floor = max(floor, 11 if d.get('kind') == 'LAYER' else 7)
    return floor


def old_stamp(j):
    return int(j.get('schemaVersion', 7))


def new_stamp(j):
    return max(old_stamp(j), def_floor_new(j['timeline']))


# ── A. survey the real files ───────────────────────────────────────────────────
pattern = sys.argv[1] if len(sys.argv) > 1 else \
    "C:/Users/JoyRaptor/fadcam-safety-2026-07-26/**/*.json"
paths = sorted(glob.glob(pattern, recursive=True))
print(f"pattern : {pattern}")
print(f"MATCHED : {len(paths)} file(s)")
if not paths:
    print("FAIL: zero files matched — nothing was verified.")
    sys.exit(1)

print("\nA. SURVEY  (stamp on disk -> stamp after the fix)")
print(f"   {'file':<26} {'ver':>3} {'defs':>4} {'LAYER defs':>10}   new ver")
raised = []
for p in paths:
    try:
        j = json.load(open(p, encoding='utf-8'))
    except Exception as e:
        print(f"   {os.path.basename(p):<26} unreadable: {e}")
        continue
    if 'timeline' not in j:
        print(f"   {os.path.basename(p):<26} (not a project file)")
        continue
    defs = j['timeline'].get('layers', {}).get('trackDefs', [])
    layer_defs = [d for d in defs if d.get('kind') == 'LAYER']
    o, n = old_stamp(j), new_stamp(j)
    flag = "  RAISED" if n != o else ""
    if n != o:
        raised.append(os.path.basename(p))
    print(f"   {os.path.basename(p):<26} {o:>3} {len(defs):>4} {len(layer_defs):>10}   {n}{flag}")

print(f"\n   projects whose stamp the fix raises: {raised if raised else 'none'}")
print("   (a raise is CORRECT only for a project that really has a LAYER def —")
print("    it is what makes an older build refuse the file instead of rewriting it)")

# ── B. simulate the old build corrupting a LAYER project ──────────────────────
print("\nB. CORRUPTION  (old build: TrackKind.fromName('LAYER') -> VIDEO, then re-save)")

# A realistic LAYER-lane project. Two details make it exercise PAINT ORDER and not
# just the kind string:
#   * the LAYER def is emitted LAST (after every typed phase); a def coerced to
#     VIDEO is emitted inside the VIDEO phase, i.e. EARLIER.
#   * so the band only reorders if something is emitted between those two points —
#     the video LEFTOVER FLUSH. `v_orphan` is an overlay clip on a layerId with no
#     def, which is exactly that (real projects have such ids; see getlayers_equiv's
#     "legacy sprite-<uuid>" note). Without it the two positions coincide and the
#     reorder hides.
fixture = {
    'layers': {'trackDefs': [
        {'id': 'L1', 'kind': 'LAYER', 'name': 'Layer 1'},
    ]},
    'textOverlays': [{'id': 't_seed'},
                     {'id': 't_on_layer', 'layerId': 'L1'}],
    'spriteOverlays': [{'id': 's1'}],
    'overlayClips': [{'id': 'v1', 'layerId': 'video'},
                     {'id': 'v_orphan', 'layerId': 'legacy-uuid-no-def'}],
}


def coerce_old_build(tl):
    """What an old build's loader does: fromName's VIDEO fallback on every unknown
    kind, then serializeTrack/trackDefs writes that coerced kind back out."""
    out = json.loads(json.dumps(tl))
    KNOWN = {'MASTER', 'VIDEO', 'IMAGE', 'TEXT', 'STICKER', 'SPRITE',
             'CAPTION', 'VISUALIZER', 'AUDIO'}   # no LAYER
    for d in out.get('layers', {}).get('trackDefs', []):
        if d.get('kind') not in KNOWN:
            d['kind'] = 'VIDEO'
    return out


before = [(r[0], r[1], r[2]) for r in new_algo(fixture)]
after = [(r[0], r[1], r[2]) for r in new_algo(coerce_old_build(fixture))]
print("   band BEFORE (this build):")
for i, r in enumerate(before):
    print(f"     [{i}] {r}")
print("   band AFTER  (round-tripped through the old build):")
for i, r in enumerate(after):
    print(f"     [{i}] {r}")

pos_before = [r[0] for r in before].index('L1')
pos_after = [r[0] for r in after].index('L1')
kind_before = before[pos_before][1]
kind_after = after[pos_after][1]
print(f"\n   lane L1: kind {kind_before} -> {kind_after}, "
      f"band index {pos_before} -> {pos_after}")
reordered = [r[0] for r in before] != [r[0] for r in after]
corrupted = (kind_before != kind_after) and reordered
print(f"   identity rewrite : {'YES' if kind_before != kind_after else 'no'}")
print(f"   band reorder     : {'YES' if reordered else 'no'}  "
      f"(row sequence {[r[0] for r in before]} -> {[r[0] for r in after]})")
print(f"   {'CONFIRMED' if corrupted else 'NOT REPRODUCED'}: the round-trip "
      f"{'permanently changes what paints over what' if corrupted else 'left paint order intact'}")

# ── C. the guard, before and after the fix ────────────────────────────────────
print("\nC. GUARD  (ProjectStorage: refuse to save when on-disk > running)")
for label, stamped in (("today (LAYER stamps 8/9/10)", 8),
                       ("after the fix (LAYER stamps 11)", RUNNING_NEW)):
    fires = stamped > RUNNING_OLD
    print(f"   {label:<34} on-disk={stamped} running(old build)={RUNNING_OLD} "
          f"-> guard {'FIRES, file untouched' if fires else 'SILENT, file rewritten'}")

# also confirm the fix does not lock the CURRENT build out of its own files
print(f"   this build reading its own v{RUNNING_NEW} file: "
      f"{RUNNING_NEW} > {RUNNING_NEW} = False -> writable (no self-lockout)")

# ── D. source tripwires ───────────────────────────────────────────────────────
# The proof above is about the ALGORITHM. These four checks are about the SOURCE,
# so the fix cannot be silently reverted and — the point — so the NEXT kind added
# to the enum cannot repeat audit 1.2 by relying on fromName's fallback.
print("\nD. SOURCE TRIPWIRES")

APP = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   '..', 'app', 'src', 'main', 'java', 'com', 'fadcam', 'ui', 'faditor')


def _read(*parts):
    p = os.path.normpath(os.path.join(APP, *parts))
    if not os.path.exists(p):
        return None, p
    return open(p, encoding='utf-8').read(), p


src_checks = []


def check(label, cond, detail=''):
    src_checks.append(cond)
    print(f"   {'PASS' if cond else 'FAIL'}  {label}{('  — ' + detail) if detail else ''}")


proj, pp = _read('model', 'FaditorProject.java')
if proj is None:
    check('FaditorProject.java found', False, pp)
else:
    import re
    m = re.search(r'SCHEMA_VERSION\s*=\s*(\d+)', proj)
    got = int(m.group(1)) if m else -1
    check(f'FaditorProject.SCHEMA_VERSION == {RUNNING_NEW}', got == RUNNING_NEW,
          f'found {got}')

tk, tp = _read('layers', 'TrackKind.java')
if tk is None:
    check('TrackKind.java found', False, tp)
else:
    import re
    check('TrackKind.minSchemaVersion() exists',
          'int minSchemaVersion()' in tk)
    m = re.search(r'minSchemaVersion\(\)\s*\{(.*?)\n    \}', tk, re.S)
    body = m.group(1) if m else ''
    check('...and it floors LAYER at 11', 'LAYER' in body and '11' in body)

    # KIND DRIFT: every constant in the enum must be one this guard has seen and
    # deliberately assigned a floor. A new constant fails here on purpose.
    body_src = tk.split('public enum TrackKind {', 1)[-1].split(';', 1)[0]
    constants = [c.strip() for c in body_src.split(',') if c.strip()]
    EXPECTED = ['MASTER', 'VIDEO', 'IMAGE', 'TEXT', 'STICKER', 'SPRITE',
                'CAPTION', 'VISUALIZER', 'AUDIO', 'LAYER']
    drift = [c for c in constants if c not in EXPECTED]
    check('no unreviewed TrackKind constants', not drift,
          f'NEW KIND(S) {drift}: give each one a minSchemaVersion() equal to the '
          f'SCHEMA_VERSION that introduced it, then add it to EXPECTED here. '
          f'"the fromName fallback makes it safe" is audit 1.2.'
          if drift else f'{len(constants)} constants, all reviewed')

ps, psp = _read('project', 'ProjectStorage.java')
if ps is None:
    check('ProjectStorage.java found', False, psp)
else:
    check('serializer applies the per-def stamp floor',
          'def.getKind().minSchemaVersion()' in ps)
    # The rig branch must NOT reference SCHEMA_VERSION, or bumping the constant
    # silently locks v10 builds out of rig projects they can read fine.
    ladder = ps.split('int stampedVersion', 1)[-1][:400]
    check('rig branch stamps a literal 10, not SCHEMA_VERSION',
          'SCHEMA_VERSION' not in ladder and 'usesRigs ? 10' in ladder)

ok = corrupted and (RUNNING_NEW > RUNNING_OLD) and all(src_checks)
print(f"\n{'PROVED' if ok else 'FAILED'}: corruption reproduces offline, the raised "
      "stamp trips the guard, and the source matches")
sys.exit(0 if ok else 1)
