"""Bake selected, already-expanded candidates into an isolated evaluation resource overlay.

Never edits shipped catalogue or source inputs. Image-derived relief/physical settings are
material hypotheses for owner feel testing, not measured PBR properties. No image generation here.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import sys

import numpy as np
from PIL import Image

import pack
from photo2paper import gaussian, periodic_component, fluid_map, srgb_to_linear
from felt2paper import fft_resize, relief_from_shading

PROFILES = {
    # tooth depth mm, compliance, sizing, absorbency, capacity, wick, anisotropy,
    # height span, source-pixel pitch, physical inference kind
    'linen': (.10, .25, .35, .60, .45, .35, .45, .65, .50, 'weave'),
    'duck': (.14, .35, .30, .65, .55, .40, .35, .70, .65, 'weave'),
    'jute': (.25, .35, .25, .70, .65, .45, .50, .85, .85, 'weave'),
    'papyrus': (.14, .25, .40, .55, .50, .45, .70, .55, 1.0, 'strip'),
    'parchment': (.025, .15, .85, .12, .20, .10, .10, .20, .65, 'smooth'),
    'silk': (.025, .30, .50, .30, .25, .45, .70, .25, .45, 'weave'),
    'fabric': (.07, .60, .20, .75, .65, .55, .45, .45, .60, 'weave'),
    'cement': (.35, .02, .05, .75, .70, .25, .05, .80, 1.0, 'mineral'),
    'rice': (.025, .60, .05, .90, .35, .85, .70, .16, .70, 'rice'),
    'pulp': (.10, .45, .25, .65, .55, .50, .30, .55, .75, 'chunk'),
    'board': (.15, .15, .30, .60, .45, .35, .15, .65, .80, 'chunk'),
    'chalk': (.02, .02, 1.0, .0, .02, .0, .0, .12, .65, 'chalk'),
    'handled': (.035, .40, .55, .40, .35, .30, .15, .20, .65, 'crease'),
    'light': (.10, .40, .50, .45, .40, .30, .15, .45, .65, 'crease'),
    'firm': (.25, .40, .50, .45, .40, .30, .15, .85, .65, 'crease'),
}


def family(job):
    name = (job['id'] + ' ' + job['label']).lower()
    for key in ('linen', 'duck', 'jute', 'papyrus', 'parchment', 'silk', 'fabric', 'cement'):
        if key in name:
            return key
    if 'expanded-' in name:
        return job['id'].removeprefix('expanded-')
    if 'rice' in name:
        return 'rice'
    if 'chalk' in name:
        return 'chalk'
    if 'sugar' in name or 'thai' in name:
        return 'pulp'
    return 'board'


def unit(v):
    return (v - v.mean()) / max(float(v.std()), 1e-8)


def resize_periodic(a, size):
    return fft_resize(a, size) if a.shape[0] != size else a.copy()


def infer_height(y, kind, span):
    fine = gaussian(y, .45) - gaussian(y, 2.0)
    if kind in ('rice', 'chunk', 'chalk'):
        # No absolute brightness-to-height mapping. Embedded pigment is not a pit:
        # use bounded fine structural edge energy, never low-frequency dark blobs.
        detail = unit(fine)
        gx = (np.roll(detail, -1, 1) - np.roll(detail, 1, 1)) * .5
        gy = (np.roll(detail, -1, 0) - np.roll(detail, 1, 0)) * .5
        relief = unit(gaussian(np.hypot(gx, gy), .7))
    elif kind == 'crease':
        # The generated samples explicitly use a soft upper-left lamp. Integrating
        # shading estimates broad relief; it does not claim calibrated geometry.
        recovered, _ = relief_from_shading(y, bump_px=max(12, y.shape[0] / 18))
        relief = unit(recovered)
        relief = .8 * relief + .2 * unit(fine)
    elif kind == 'weave':
        relief = .7 * unit(gaussian(y, .5) - gaussian(y, 5)) + .3 * unit(fine)
    elif kind == 'strip':
        relief = .65 * unit(gaussian(y, 1) - gaussian(y, 14)) + .35 * unit(fine)
    elif kind == 'mineral':
        relief = .55 * unit(fine) + .45 * unit(gaussian(y, 2) - gaussian(y, 9))
    else:
        relief = unit(fine)
    relief = periodic_component(relief)
    # Shared fixed slope range is applied later. Do not percentile-normalize the
    # subtle and strong papers back to identical apparent directional strength.
    return np.clip(.5 + span * .5 * np.tanh(relief / 1.7), 0, 1)


def bake(job, out, size=2048):
    source = Path(job['directory']) / 'tile.png'
    if not source.is_file():
        return None
    key = family(job)
    depth, compliance, sizing, absorb, capacity, wick, anisotropy, span, pitch, kind = PROFILES[key]
    # A primed canvas should not absorb like raw canvas. These are evaluation
    # presets, with their assumptions preserved for later owner tuning.
    description = job['description'].lower()
    if ('primed' in description and 'unprimed' not in description) or 'gesso' in description:
        sizing, absorb, wick = .85, .20, .15
    if job['id'] == 'silk-3':
        depth, span = .015, .18
    material_id = 'eval_' + re.sub('[^a-z0-9_]', '_', job['id'].lower())
    if len(material_id) > 40:
        raise ValueError(material_id)
    im = Image.open(source).convert('RGB')
    rgb = np.asarray(im, dtype=np.float64) / 255
    # Keep the selected look, including natural material colour, without global
    # de-lighting or aesthetic grading. Match renderer power-of-two resources via
    # periodic resampling, preserving document scale in texelPx.
    rgb = np.dstack([resize_periodic(rgb[..., c], size) for c in range(3)]).clip(0, 1)
    look_path = out / f'look_{material_id}.jpg'
    Image.fromarray(np.rint(rgb * 255).astype(np.uint8)).save(look_path, quality=92, subsampling=0, optimize=True)
    # Convert one channel at a time and release RGB before FFT inference. This
    # avoids retaining two full RGB float64 images during the heavy stages.
    y = np.zeros((size, size), dtype=np.float64)
    for channel, weight in enumerate((.2126, .7152, .0722)):
        y += srgb_to_linear(rgb[..., channel]) * weight
    del rgb
    height = infer_height(y, kind, span)
    height_bytes = np.rint(height * 255).astype(np.uint8)
    packed, slope_range = pack.pack(height_bytes, slope_range=.10)
    height_path = out / f'height_{material_id}.png'
    Image.fromarray(height_bytes).save(height_path, optimize=True)
    fluid_kind = {'rice': 'rice', 'chunk': 'chunk', 'chalk': 'chalk'}.get(kind, 'pulp')
    # Infer broad formation on the actual coarse grid, rather than constructing
    # a full2048 fluid working set and reducing it afterwards. The recipe is a
    # coarse material hypothesis, not a bit-exact clone of photo2paper.convert.
    fluid = fluid_map(resize_periodic(y, size // 4), fluid_kind)
    fluid_path = out / f'fluid_{material_id}.png'
    Image.fromarray(np.rint(fluid).clip(0, 255).astype(np.uint8)).save(fluid_path, optimize=True)
    # Scale conversion keeps original thread/crease size after resource resample.
    texel_px = max(.25, round(pitch * im.width / size, 6))
    mean = np.asarray(Image.open(look_path), dtype=np.float64).mean(axis=(0, 1))
    colour = '#%02X%02X%02X' % tuple(np.rint(mean).astype(int))
    name = ('Eval ' + job['label'].split(' — ')[0])[:40]
    hex_texels = min(512, size)
    surface = dict(id=material_id, name=name, file=height_path.name, size=size,
                   texelPx=texel_px, slopeRange=slope_range, hexTexels=hex_texels,
                   rotatable=False, relief=.15, heightMean=float(height_bytes.mean() / 255),
                   packed=False, fluid=fluid_path.name, fluidTexelPx=texel_px * 4,
                   fluidHexTexels=hex_texels / 4, toothDepthMm=depth, compliance=compliance,
                   sizing=sizing, absorbency=absorb, capacity=capacity,
                   wickSpeed=wick, anisotropy=anisotropy)
    look = dict(id=material_id, name=name, base=colour, file=look_path.name, mean=colour,
                texelPx=texel_px, hexTexels=hex_texels, rotatable=False,
                defaultSurface=material_id, lightByDefault=False)
    # Packed diagnostic outside runtime assets: loader must derive exactly this
    # from the shipped grey samples. h² is one rounded byte, R/G encode127=zero.
    diagnostics = out.parent / 'diagnostics'
    diagnostics.mkdir(exist_ok=True)
    Image.fromarray(packed).save(diagnostics / f'packed_{material_id}.png', optimize=True)
    Image.fromarray(np.rint(height * 65535).astype(np.uint16)).save(diagnostics / f'master_height_{material_id}.png')
    assert np.array_equal(packed[..., 2], height_bytes)
    assert np.array_equal(packed[..., 3], np.rint(255 * (height_bytes.astype(float) / 255) ** 2).astype(np.uint8))
    return dict(selectionId=job['id'], surface=surface, look=look, inferenceProfile=key,
                assumptions='Image-derived estimate; physical numbers need owner feel testing.',
                sourceTile=str(source), sourceTileSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                inferenceNote=job.get('note', ''), fluidInferenceGrid=size // 4,
                heightStd=float(height.std()), maxSlopeClippedFraction=float(np.mean((packed[..., :2] == 0) | (packed[..., :2] == 254))))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('batch_root', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--only')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    for job in json.loads((args.batch_root / 'jobs.json').read_text()):
        if args.only and job['id'] != args.only:
            continue
        receipt = args.output.parent / ('receipt_' + job['id'] + '.json')
        if receipt.exists():
            continue
        result = bake(job, args.output)
        if result:
            receipt.write_text(json.dumps(result, indent=2), encoding='utf-8')
            print(job['id'], 'baked', flush=True)


if __name__ == '__main__':
    main()
