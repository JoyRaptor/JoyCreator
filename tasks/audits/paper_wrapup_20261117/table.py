"""Print the per-paper status table of the report from analyse.json and a shrink_papers.py output folder."""
import json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
an = json.load(open(os.path.join(HERE, 'analyse.json')))
plan = json.load(open(os.path.join(HERE, 'plan_sizes.json')))
shr = sys.argv[1]
VERDICT = {
    'eval_owner_1': 'Replace today\'s Cardboard (same photo, sharper)', 'eval_owner_2': 'Replace today\'s Black chalkboard (same photo)',
    'eval_owner_4': 'Replace today\'s Blue construction paper (same photo)', 'eval_owner_7': 'Replace today\'s Rice paper, cream (same photo)',
    'eval_owner_8': 'Replace today\'s Rice paper, lace (same photo)', 'eval_owner_9': 'Replace today\'s Sugarcane paper (same photo)',
    'eval_owner_10': 'Replace today\'s Thai kraft paper (same photo)',
    'eval_earlier_construction_tan_03': 'Drop: the same picture as "supplied 1" (cardboard)',
    'eval_earlier_chalkboard_black_01': 'Drop: the same picture as "supplied 2" (black chalkboard)',
    'eval_earlier_rice_cream_01': 'Drop: the same picture as "supplied 7" (cream rice paper)',
    'eval_earlier_sugarcane_01': 'Drop: the same picture as "supplied 9" (sugarcane)',
    'eval_earlier_sugarcane_02': 'Drop: the same picture as "supplied 10" (Thai kraft)',
    'eval_earlier_rice_cool_01': 'Your eye: very close to today\'s cool white rice paper',
    'eval_earlier_chalkboard_green_03': 'Your eye: twin of green 02 (you noted 02 has fewer scuff marks)',
    'eval_silk_1': 'Ship; your eye: close to General fabric 1', 'eval_fabric_1': 'Ship; your eye: close to Silk 1',
}
print('| Paper | In the app\'s chooser today | Tooth + water map + numbers | Edges join | Repeat risk | Size now | Size after |')
print('|---|---|---|---|---|---|---|')
tb = ta = 0
for k, r in an.items():
    if not k.startswith('eval_') or '_p1k' in k or '_p512' in k:
        continue
    p = plan[k]
    now = sum(p['now'].values())
    hid = k
    after = sum(os.path.getsize(os.path.join(shr, f)) for f in ('look_%s.jpg' % k, 'height_%s.jpg' % hid, 'fluid_%s.png' % hid))
    tb += now; ta += after
    seam = max(r['look_seam'], r.get('h_seam', 0))
    seam_s = '✅' if seam <= 1.4 else '⚠️ %.1f' % seam
    rep = []
    if r['look_blotch'] > 10: rep.append('colour patches')
    if r.get('h_blotch', 0) > 25: rep.append('big folds')
    rep_s = '⚠️ ' + ', '.join(rep) if rep else '✅'
    print('| %s<br><sub>%s</sub> | ❌ | ✅ | %s | %s | %.1f MB | %.2f MB |' % (r['name'].replace('Test ', ''), VERDICT.get(k, 'Ship'), seam_s, rep_s, now / 1e6, after / 1e6))
print('| **All 45** | | | | | **%.1f MB** | **%.1f MB** |' % (tb / 1e6, ta / 1e6))
