"""Pack the owner's small physical test set. No decorative image generation.
Canvas inputs are the existing 16-bit geometry candidates; their SHA256 is recorded
in SOURCES.md. Pulp is deterministic from the periodic fibre/formation model.
Usage: python prepare_test_surfaces.py <candidate-surfaces-directory>
"""
import sys
from pathlib import Path
import hashlib
import json
import numpy as np
from PIL import Image
from pack import pack
def pulp(seed, rough):
    """Periodic fibre formation plus felt tooth, with pressed tops (NumPy-only)."""
    rng = np.random.default_rng(seed)
    n = 1024
    def norm(a):
        return (a - a.min()) / (a.max() - a.min())
    fy = np.fft.fftfreq(n)[:, None]
    fx = np.fft.rfftfreq(n)[None, :]
    def blur(a, width):
        kernel = np.exp(-2 * np.pi**2 * width**2 * (fx*fx + fy*fy))
        return np.fft.irfft2(np.fft.rfft2(a) * kernel, s=(n, n))
    fibres = np.zeros((n, n))
    count = 22000
    x, y = rng.uniform(0, n, (2, count))
    angle = rng.uniform(0, 2*np.pi, count)
    weight = rng.uniform(.4, 1., count)
    for step in range(30):
        np.add.at(fibres, (y.astype(int) % n, x.astype(int) % n), weight)
        angle += rng.normal(0, .025, count)
        x += np.cos(angle); y += np.sin(angle)
    formation = norm(blur(rng.standard_normal((n, n)), 30))
    tooth = norm(blur(rng.standard_normal((n, n)), 4 + 6*rough))
    h = norm(.35*norm(blur(fibres, .45)) + .3*formation + (.15+.6*rough)*tooth)
    press = .55 + .4*rough
    return norm(np.minimum(h, press) + .15*np.maximum(h-press, 0))

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'assets/paper'
SET = [
    ('canvas_linen', 'Fine linen', .30, .75, False, '#F2F1ED'),
    ('canvas_cotton_duck', 'Cotton duck', .50, 1., False, '#F0EFEB'),
    ('canvas_jute', 'Rough jute', .85, 1.25, False, '#E7E4DE'),
    ('pulp_factory', 'Factory pulp', .32, 2., True, '#F5F4F1'),
    ('pulp_handmade', 'Handmade pulp', .90, 2., True, '#F2F0EC'),
]

def main(directory):
    catalogue = json.loads((ASSETS / 'catalogue.json').read_text())
    notes = ['# Paper sources', '', 'Original numerical materials authored for Joy Brush. No third-party scans or AI-generated look images in this test set.', '',
             'Canvases: existing periodic 3D yarn candidates (alternating over/under centrelines, three helical cylindrical strands, wrapped slubs and knots). Pulp: seeded NumPy fibre/formation model in this script. 1024 heights are reduced by 2x2 area averaging, then centred physical amplitude is applied without renormalisation. Packed slopes use a shared 0.099 range and toroidal Scharr sampling. Colours are neutral material bases, without an amber grade.', '',
             'Rebuild: `python joybrush/tools/paper/prepare_test_surfaces.py <candidate-surfaces-directory>`; original canvas inputs remain in the owner’s ignored candidates/surfaces directory.', '']
    for id, name, span, texel, rotate, colour in SET:
        if id.startswith('canvas_'):
            source = Path(directory) / (id + '.png')
            with Image.open(source) as image:
                values = np.asarray(image)
                if values.shape != (1024, 1024) or values.dtype.itemsize < 2:
                    raise ValueError('Canvas source must be 1024-square 16-bit height: ' + id)
                h = values.astype(np.float64) / 65535
            origin = 'candidate SHA256 ' + hashlib.sha256(source.read_bytes()).hexdigest()
        else:
            seed, rough = (810, 0.) if id == 'pulp_factory' else (811, .9)
            h = pulp(seed, rough)
            origin = f'prepare_test_surfaces.pulp(seed={seed}, rough={rough})'
        h = h.reshape(512, 2, 512, 2).mean(axis=(1, 3))
        hb = np.rint(255 * (.5 + span * (h - .5))).astype(np.uint8)
        rgba, _ = pack(hb, .099)
        file = 'surface_' + id + '.png'
        Image.fromarray(rgba).save(ASSETS / file, optimize=True)
        surface = dict(id=id, name=name, file=file, size=512, texelPx=texel,
                       slopeRange=.099, hexTexels=180, rotatable=rotate, relief=1.)
        look = dict(id=id, name=name, base=colour, file=None, defaultSurface=id)
        for key, value in [('surfaces', surface), ('looks', look)]:
            catalogue[key] = [old for old in catalogue[key] if old['id'] != id] + [value]
        notes.append(f'- `{file}`: {origin}; physical span {span}, texelPx {texel}.')
        print(id, 'height spread', float(hb.std()), 'bytes', (ASSETS / file).stat().st_size)
    (ASSETS / 'catalogue.json').write_text(json.dumps(catalogue, indent=2) + '\n')
    (ASSETS / 'SOURCES.md').write_text('\n'.join(notes) + '\n', encoding='utf-8')

if __name__ == '__main__':
    main(sys.argv[1])
