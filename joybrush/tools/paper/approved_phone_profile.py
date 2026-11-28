"""Derive a smaller evaluation profile from a completed master, preserving units/moments.

No baking or app installation here. Root selects at most3 materials for the first
phone batch after the measured master bake; this file prepares that resource path.
"""
from pathlib import Path
import argparse
import hashlib
import json

import numpy as np
from PIL import Image

import pack


def area(a, ratio):
    if ratio < 1 or a.shape[0] % ratio or a.shape[1] % ratio:
        raise ValueError('profile must divide master dimensions exactly')
    h, w = a.shape[:2]
    return a.reshape(h // ratio, ratio, w // ratio, ratio, *a.shape[2:]).mean(axis=(1, 3))


def surface_profile(master_height, ratio, master_slope_range):
    h = master_height.astype(np.float64) / 255
    filtered = area(h, ratio)
    b = np.rint(filtered * 255).astype(np.uint8)
    # A profile texel covers ratio times the document length, so a derivative
    # measured per texel changes units. Recompute R/G using production pack maths.
    rgba, slope_range = pack.pack(b, master_slope_range * ratio)
    # Preserve E[h²] through footprint filtering; squaring only averaged h would
    # erase unresolved tooth variance and change dry/wet deposition at minification.
    rgba[..., 3] = np.rint(area(h * h, ratio) * 255).astype(np.uint8)
    return rgba, slope_range


def mip_bytes(side):
    total = 0
    while True:
        total += side * side * 4
        if side == 1:
            return total
        side = max(1, side // 2)


def derive(master_root, receipt_path, output, size=1024):
    receipt = json.loads(receipt_path.read_text())
    s, look = receipt['surface'], receipt['look']
    if s['size'] % size or size not in (512, 1024):
        raise ValueError('phone profiles must be512 or1024 and divide master')
    ratio = s['size'] // size
    output.mkdir(parents=True, exist_ok=True)
    profile_id = s['id'] + ('_p1k' if size == 1024 else '_p512')
    if len(profile_id) > 40:
        raise ValueError(profile_id)
    height = np.asarray(Image.open(master_root / s['file']))
    rgba, slope_range = surface_profile(height, ratio, s['slopeRange'])
    surface_name = f'surface_{profile_id}.png'
    Image.fromarray(rgba).save(output / surface_name, optimize=True)
    colour = np.asarray(Image.open(master_root / look['file']).convert('RGB'), dtype=np.float64)
    look_name = f'look_{profile_id}.jpg'
    Image.fromarray(np.rint(area(colour, ratio)).astype(np.uint8)).save(output / look_name, quality=92, subsampling=0, optimize=True)
    # Fluid double-angle vectors are linear encoded components; area averaging
    # retains their mean direction and reduces coherence rather than inventing one.
    fluid = np.asarray(Image.open(master_root / s['fluid']), dtype=np.float64)
    fluid_name = f'fluid_{profile_id}.png'
    Image.fromarray(np.rint(area(fluid, ratio)).astype(np.uint8)).save(output / fluid_name, optimize=True)
    ps = dict(s, id=profile_id, size=size, file=surface_name, packed=True,
              texelPx=s['texelPx'] * ratio, hexTexels=s['hexTexels'] / ratio,
              slopeRange=slope_range, fluid=fluid_name,
              fluidTexelPx=s['fluidTexelPx'] * ratio,
              fluidHexTexels=s['fluidHexTexels'] / ratio,
              heightMean=float(rgba[..., 2].mean() / 255))
    mean = np.asarray(Image.open(output / look_name)).mean(axis=(0, 1))
    mean = '#%02X%02X%02X' % tuple(np.rint(mean).astype(int))
    pl = dict(look, id=profile_id, file=look_name, defaultSurface=profile_id,
              texelPx=look['texelPx'] * ratio, hexTexels=look['hexTexels'] / ratio,
              base=mean, mean=mean)
    files = []
    for name in (surface_name, look_name, fluid_name):
        p = output / name
        im = Image.open(p)
        files.append(dict(file=name, width=im.width, height=im.height, format=im.format,
                          mode=im.mode, bytes=p.stat().st_size,
                          sha256=hashlib.sha256(p.read_bytes()).hexdigest()))
    report = dict(selectionId=receipt['selectionId'], surface=ps, look=pl, files=files,
                  encodedBytes=sum(x['bytes'] for x in files),
                  modeledGpuRgba8WithMips=mip_bytes(size) * 2 + mip_bytes(size // 4),
                  decodedBaseRgbaBytes=size * size * 4 * 2 + (size // 4) ** 2 * 4,
                  limits='Modeled base/map bytes only; not measured residency or transient decode/upload peak.',
                  resolutionLimit='Profile loses spatial detail; filtered second moment retains unresolved tooth variance.',
                  sourceRecipe=receipt_path.name,
                  sourceRecipeSha256=hashlib.sha256(receipt_path.read_bytes()).hexdigest())
    return report


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('master_root', type=Path)
    p.add_argument('receipt', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--size', type=int, default=1024)
    args = p.parse_args()
    result = derive(args.master_root, args.receipt, args.output, args.size)
    (args.output / 'profile-receipt.json').write_text(json.dumps(result, indent=2))
