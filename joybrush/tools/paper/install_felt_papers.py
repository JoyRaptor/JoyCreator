"""Install the felt-finished papers (watercolour cold/hot/rough, drawing paper) into joybrush/assets/paper (2026-10-06).

All four come from ONE raking-lit photograph of a real cold-press watercolour sheet (CC0, sources/; SOURCES.md has the
provenance) through felt2paper.py: the felt bumps measured from the photo, the fibre grain from a fibre-network model at
true size, each grade finished as the mill finishes it. Requested by the brush-engines session for pencil and watercolour.

python install_felt_papers.py <scan.jpg> <assets_paper_dir> [grade ...]     (no grades = all; each grade is ~2 min)
"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
HEX = 300.0

# (grade, look id, look name, surface id, surface name). felt2paper exposes each look to its sheet's white, so the base
# colour is the look's measured mean.
FELT = [
    ('cold', 'watercolour_cold', 'Watercolour, cold press', 'cold_press', 'Cold-press tooth'),
    ('rough', 'watercolour_rough', 'Watercolour, rough', 'rough_press', 'Rough watercolour tooth'),
    ('hot', 'watercolour_hot', 'Watercolour, hot press', 'hot_press', 'Hot-press tooth'),
    ('drawing', 'drawing_paper', 'Drawing paper', 'drawing_tooth', 'Drawing tooth'),
    ('bristol', 'bristol_paper', 'Bristol, smooth', 'bristol_tooth', 'Bristol tooth'),
]
PHYSICS = ('toothDepthMm', 'compliance', 'sizing', 'absorbency', 'capacity', 'wickSpeed', 'anisotropy')


def upsert(entries, by_id, entry):
    old = by_id.get(entry['id'])
    if old is not None:
        old.clear(); old.update(entry)
    else:
        entries.append(entry); by_id[entry['id']] = entry


def main(scan, assets, only=()):
    cat_path = os.path.join(assets, 'catalogue.json')
    cat = json.load(open(cat_path, encoding='utf-8'))
    looks = {l['id']: l for l in cat['looks']}
    surfaces = {s['id']: s for s in cat['surfaces']}
    work = os.path.join(HERE, 'out', 'felt')
    for grade, look_id, look_name, surf_id, surf_name in FELT:
        if only and grade not in only:
            continue
        res = subprocess.run([sys.executable, os.path.join(HERE, 'felt2paper.py'), scan, work, look_id, '--grade', grade],
                             check=True, capture_output=True, text=True)
        info = json.loads(res.stdout.strip().splitlines()[-1])
        for src, dst in ((f'look_{look_id}.jpg', f'look_{look_id}.jpg'), (f'height_{look_id}.png', f'height_{surf_id}.png'),
                         (f'fluid_{look_id}.png', f'fluid_{surf_id}.png')):
            os.replace(os.path.join(work, src), os.path.join(assets, dst))
        upsert(cat['surfaces'], surfaces, {
            'id': surf_id, 'name': surf_name, 'file': f'height_{surf_id}.png', 'packed': False, 'size': info['size'],
            'texelPx': info['texelPx'], 'slopeRange': info['slopeRange'], 'hexTexels': HEX, 'rotatable': True, 'relief': 0.35,
            'heightMean': info['heightMean'],
            # The fluid map is a quarter of the surface's size over the same tile.
            'fluid': f'fluid_{surf_id}.png', 'fluidTexelPx': info['texelPx'] * 4, 'fluidHexTexels': HEX / 4,
            **{k: info[k] for k in PHYSICS},
        })
        upsert(cat['looks'], looks, {
            'id': look_id, 'name': look_name, 'base': info['mean'], 'file': f'look_{look_id}.jpg', 'mean': info['mean'],
            'texelPx': info['lookTexelPx'], 'hexTexels': HEX, 'rotatable': True, 'defaultSurface': surf_id,
            # Cold and rough: the look keeps little of the photo's lamp, and the faint relief light draws the bumps from
            # the surface, so the light and the pigment sit on the same hills. Hot press and drawing paper read flat.
            'lightByDefault': info['light'],
        })
        print(look_id, surf_id, info['mean'], info['lookTexelPx'])
    with open(cat_path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(cat, f, indent=2, ensure_ascii=False)
        f.write('\n')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], tuple(sys.argv[3:]))
