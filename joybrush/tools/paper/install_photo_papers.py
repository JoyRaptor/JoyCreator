"""Install the owner's photographic papers into joybrush/assets/paper and catalogue.json (2026-10-06).

Each source photo becomes a LOOK, a SURFACE (tooth) and a FLUID map through photo2paper.py. Where the procedural library
already had a paper of that kind, its id is kept and its files are replaced (documents that name it keep working, and
now get the real paper); the rest are added. The source photos are the owner's own image generations; they stay out of
the repo (tasks/joybrush/research/GBT texture sample image generations/), and SOURCES.md records the provenance.

python install_photo_papers.py <photo_dir> <assets_paper_dir>
"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

LOOK_TEXEL = 2.5      # doc px per look texel: a 1024 tile is ~128 mm (R10: 1 doc px ≈ 0.05 mm)
SURFACE_TEXEL = 1.0   # the tooth is finer than the look: ~5 doc px ≈ 0.25 mm grain
HEX = 300.0

# (source file, look id, look name, surface id, surface name, kind, rotatable)
PAPERS = [
    ('construciton-paper.png', 'construction_tan', 'Tan construction paper', 'construction_pulp', 'Construction pulp', 'pulp', True),
    ('construction paper-blue.png', 'construction_blue', 'Blue construction paper', 'construction_blue_pulp', 'Blue construction pulp', 'pulp', True),
    ('cardbord01.png', 'cardboard', 'Cardboard', 'cardboard', 'Cardboard', 'board', True),
    ('chalkbord-black-ChatGPT Image Oct 5, 2026, 11_08_28 PM.png', 'chalkboard_black', 'Black chalkboard', 'chalk_grit', 'Chalk grit', 'chalk', True),
    ('ricepaper01-ChatGPT Image Oct 5, 2026, 11_05_38 PM.png', 'rice_cool', 'Rice paper, cool white', 'rice_fibres', 'Rice fibres', 'rice', True),
    ('ricepaper02-ChatGPT Image Oct 5, 2026, 11_05_38 PM.png', 'rice_white', 'Rice paper, white', 'rice_white_fibres', 'Rice fibres, white', 'rice', True),
    ('ricepaper03-tan-ChatGPT Image Oct 5, 2026, 11_05_38 PM.png', 'rice_cream', 'Rice paper, cream', 'rice_cream_fibres', 'Rice fibres, cream', 'rice', True),
    ('ricepaper04n-ChatGPT Image Oct 5, 2026, 11_05_38 PM.png', 'rice_lace', 'Rice paper, lace', 'rice_lace_fibres', 'Rice fibres, lace', 'rice', True),
    ('thai-ChatGPT Image Oct 5, 2026, 11_08_59 PM.png', 'sugarcane', 'Sugarcane paper', 'sugarcane_pulp', 'Sugarcane pulp', 'chunk', True),
    ('thai02-ChatGPT Image Oct 5, 2026, 11_08_59 PM.png', 'thai_kraft', 'Thai kraft paper', 'thai_kraft_pulp', 'Thai kraft pulp', 'chunk', True),
    ('thai03-ChatGPT Image Oct 5, 2026, 11_09_43 PM.png', 'thai_rose', 'Thai rose paper', 'thai_rose_pulp', 'Thai rose pulp', 'chunk', True),
]


def main(photo_dir, assets):
    cat_path = os.path.join(assets, 'catalogue.json')
    cat = json.load(open(cat_path, encoding='utf-8'))
    looks = {l['id']: l for l in cat['looks']}
    surfaces = {s['id']: s for s in cat['surfaces']}
    work = os.path.join(HERE, 'out', 'photo')
    for src, look_id, look_name, surf_id, surf_name, kind, rot in PAPERS:
        res = subprocess.run([sys.executable, os.path.join(HERE, 'photo2paper.py'), os.path.join(photo_dir, src), work, look_id,
                              '--kind', kind], check=True, capture_output=True, text=True)
        info = json.loads(res.stdout.strip().splitlines()[-1])
        for src_name, name in ((f'look_{look_id}.jpg', f'look_{look_id}.jpg'), (f'height_{look_id}.png', f'height_{surf_id}.png'),
                               (f'fluid_{look_id}.png', f'fluid_{surf_id}.png')):
            os.replace(os.path.join(work, src_name), os.path.join(assets, name))
        # A replaced paper's old packed surface file is no longer referenced.
        old_file = (surfaces.get(surf_id) or {}).get('file')
        if old_file and old_file != f'height_{surf_id}.png' and os.path.exists(os.path.join(assets, old_file)):
            os.remove(os.path.join(assets, old_file))
        old_look = (looks.get(look_id) or {}).get('file')
        if old_look and old_look != f'look_{look_id}.jpg' and os.path.exists(os.path.join(assets, old_look)):
            os.remove(os.path.join(assets, old_look))
        surf = {
            'id': surf_id, 'name': surf_name, 'file': f'height_{surf_id}.png', 'packed': False, 'size': info['size'], 'texelPx': SURFACE_TEXEL,
            'slopeRange': info['slopeRange'], 'hexTexels': HEX, 'rotatable': rot, 'relief': 0.35, 'heightMean': info['heightMean'],
            'fluid': f'fluid_{surf_id}.png', 'fluidTexelPx': LOOK_TEXEL * 4, 'fluidHexTexels': HEX / 4,
            **{k: info[k] for k in ('toothDepthMm', 'compliance', 'sizing', 'absorbency', 'capacity', 'wickSpeed', 'anisotropy')},
        }
        old_s = surfaces.get(surf_id)
        if old_s is not None:
            old_s.clear(); old_s.update(surf)
        else:
            cat['surfaces'].append(surf); surfaces[surf_id] = surf
        look = {
            'id': look_id, 'name': look_name, 'base': info['mean'], 'file': f'look_{look_id}.jpg', 'mean': info['mean'],
            'texelPx': LOOK_TEXEL, 'hexTexels': HEX, 'rotatable': rot, 'defaultSurface': surf_id,
            # A photograph already carries the light the eye sees; relief lighting on top is what read as 90s 3D.
            'lightByDefault': False,
        }
        old_l = looks.get(look_id)
        if old_l is not None:
            old_l.clear(); old_l.update(look)
        else:
            cat['looks'].append(look); looks[look_id] = look
        print(look_id, surf_id, info['mean'])
    # The green chalkboard: the black chalkboard photograph, recoloured dusty green (the procedural one read as fake).
    from PIL import Image
    import numpy as np
    black = looks['chalkboard_black']
    src = np.asarray(Image.open(os.path.join(assets, black['file'])).convert('RGB'), dtype=np.float64)
    bmean = np.array([int(black['mean'][k:k + 2], 16) for k in (1, 3, 5)], dtype=np.float64)
    green_base = np.array([0x29, 0x44, 0x3D], dtype=np.float64)
    gpath = os.path.join(assets, 'look_chalkboard_green.jpg')
    Image.fromarray(np.round(np.clip(src * (green_base / np.maximum(bmean, 1)), 0, 255)).astype(np.uint8)).save(
        gpath, quality=92, subsampling=0, optimize=True)
    gmean = np.asarray(Image.open(gpath).convert('RGB'), dtype=np.float64).reshape(-1, 3).mean(0)
    ghex = '#%02X%02X%02X' % tuple(int(round(c)) for c in gmean)
    old_green = looks.get('chalkboard_green')
    if old_green and old_green.get('file') not in (None, 'look_chalkboard_green.jpg') and os.path.exists(os.path.join(assets, old_green['file'])):
        os.remove(os.path.join(assets, old_green['file']))
    green_entry = {'id': 'chalkboard_green', 'name': 'Green chalkboard', 'base': ghex, 'file': 'look_chalkboard_green.jpg', 'mean': ghex,
                   'texelPx': LOOK_TEXEL, 'hexTexels': HEX, 'rotatable': True, 'defaultSurface': 'chalk_grit', 'lightByDefault': False}
    if old_green is not None:
        old_green.clear(); old_green.update(green_entry)
    else:
        cat['looks'].append(green_entry)
    with open(cat_path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(cat, f, indent=2, ensure_ascii=False)
        f.write('\n')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
