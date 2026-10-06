"""Turn a photograph (or a photoreal render) of a paper into a Joy Brush paper: a seamless LOOK and a matching SURFACE.

Why (owner, 2026-10-06): the procedural papers read as "early-90s 3D graphics". A real paper's look is mostly its colour
and fibres under soft, even light, and the tooth a pencil catches is the same structure the eye sees. So both halves come
from ONE picture:

  1. De-light: divide out the picture's very-low-frequency light (vignettes, a lamp's falloff), keep the paper's own mottling.
  2. Seamless: Moisan's periodic + smooth decomposition. The smooth part (the edge mismatch, spread over the whole picture
     as gently as possible) is removed; what is left tiles with no seam and no blending band, and keeps every fibre.
  3. LOOK: the seamless colour picture at a power-of-two size, and its mean colour (the tint reference).
  4. SURFACE: height from the look's own luminance, band-passed to the tooth scale (no lamp gradients, no big blotches),
     with a per-paper recipe for what is raised (pulp: the light grain; rice paper: almost flat, the fibres only faintly;
     chunk papers: the chunks are colour, not holes). Rank-equalised so the height is uniform 0..1: the share of paper a
     dry brush touches then rises in step with its pressure. Packed with pack.py (the JB-9.01 contract).

Usage: python photo2paper.py <photo> <out_dir> <id> [--kind pulp|rice|chunk|board|chalk] [--size 1024] [--tooth 1.6]
Writes look_<id>.png, surface_<id>.png and prints a JSON line with mean colour and slopeRange for the catalogue.
"""
import argparse
import json
import os

import numpy as np
from PIL import Image

import pack


def srgb_to_linear(c):
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def linear_to_srgb(c):
    c = np.clip(c, 0, 1)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * c ** (1 / 2.4) - 0.055)


def gaussian(img, sigma):
    """Periodic Gaussian blur through the FFT (the picture is treated as a torus, which is what a tile is)."""
    h, w = img.shape[:2]
    fy = np.fft.fftfreq(h)[:, None]
    fx = np.fft.fftfreq(w)[None, :]
    g = np.exp(-2 * (np.pi ** 2) * (sigma ** 2) * (fx ** 2 + fy ** 2))
    if img.ndim == 2:
        return np.real(np.fft.ifft2(np.fft.fft2(img) * g))
    return np.dstack([np.real(np.fft.ifft2(np.fft.fft2(img[..., k]) * g)) for k in range(img.shape[2])])


def periodic_component(u):
    """Moisan (2011), periodic plus smooth decomposition, one channel. Returns the periodic part."""
    m, n = u.shape
    v = np.zeros_like(u)
    v[0, :] += u[-1, :] - u[0, :]
    v[-1, :] += u[0, :] - u[-1, :]
    v[:, 0] += u[:, -1] - u[:, 0]
    v[:, -1] += u[:, 0] - u[:, -1]
    q = np.arange(m)[:, None]
    r = np.arange(n)[None, :]
    denom = 2 * np.cos(2 * np.pi * q / m) + 2 * np.cos(2 * np.pi * r / n) - 4
    denom[0, 0] = 1
    s_hat = np.fft.fft2(v) / denom
    s_hat[0, 0] = 0
    s = np.real(np.fft.ifft2(s_hat))
    return u - s


def rank_uniform(x):
    """Rank-equalise to a uniform 0..1 distribution (ties broken by position, which is invisible at this scale)."""
    flat = x.ravel()
    order = np.argsort(flat, kind='stable')
    ranks = np.empty_like(order)
    ranks[order] = np.arange(flat.size)
    return (ranks / (flat.size - 1)).reshape(x.shape)


def convert(photo, size, kind, tooth, keep_mottle):
    rgb = np.asarray(Image.open(photo).convert('RGB'), dtype=np.float64) / 255.0
    side = min(rgb.shape[0], rgb.shape[1])
    rgb = rgb[:side, :side]
    lin = srgb_to_linear(rgb)

    # 1. De-light: remove only the very lowest frequencies of brightness (a lamp, a vignette), keep the paper's mottling.
    y = 0.2126 * lin[..., 0] + 0.7152 * lin[..., 1] + 0.0722 * lin[..., 2]
    low = gaussian(y, side / 5.0)
    mid = gaussian(y, side / 24.0)
    light = low * (mid / np.maximum(low, 1e-6)) ** (1.0 - keep_mottle)
    lin = lin * (y.mean() / np.maximum(light, 1e-6))[..., None]

    # 2. Seamless, per channel, in linear light.
    lin = np.dstack([periodic_component(lin[..., k]) for k in range(3)])
    lin = np.clip(lin, 0, None)

    # 3. The look at a power-of-two size (Lanczos), back to sRGB bytes.
    srgb = linear_to_srgb(lin)
    look = Image.fromarray(np.round(srgb * 255).astype(np.uint8)).resize((size, size), Image.LANCZOS)
    look_arr = np.asarray(look, dtype=np.float64) / 255.0
    # The resize can reopen a hairline seam; wrap-aware blur of the last pixel row/column is not needed because the
    # periodic component is smooth across the wrap, and Lanczos on it stays continuous.
    mean = look_arr.reshape(-1, 3).mean(axis=0)

    # 4. The surface, from the look's luminance at the tooth scale.
    yl = srgb_to_linear(look_arr)
    yl = 0.2126 * yl[..., 0] + 0.7152 * yl[..., 1] + 0.0722 * yl[..., 2]
    # Two bands, each normalised: the TOOTH (what a pencil's dust catches, a fraction of a millimetre) dominates, the
    # weave of the pulp (a few millimetres) rides under it. The picture's broad blotches are left to the look alone.
    def norm(b):
        return (b - b.mean()) / (b.std() + 1e-9)
    tooth_band = norm(gaussian(yl, 0.45) - gaussian(yl, tooth * 0.9))
    pulp_band = norm(gaussian(yl, tooth * 0.9) - gaussian(yl, tooth * 3.5))
    if kind == 'rice':
        # Rice paper is a flat, translucent sheet: whiter = more opaque, not raised (owner P12). A faint fine tooth only.
        h = tooth_band + 0.2 * pulp_band
    elif kind == 'chunk':
        # Chunks of plant matter are colour, not holes: the tooth is the fine band; the chunks' dark blobs are damped.
        h = tooth_band + 0.25 * np.clip(pulp_band, -1.0, None)
    elif kind == 'chalk':
        h = tooth_band + 0.3 * pulp_band
    elif kind == 'board':
        h = tooth_band + 0.6 * pulp_band
    else:  # pulp: the light grain is raised, the shadowed pits are low
        h = tooth_band + 0.45 * pulp_band
    # Uniform 0..1, then the paper's relief: a flat sheet (rice) spans little height, a toothy pulp the whole range.
    span = {'rice': 0.3, 'chunk': 0.75, 'chalk': 0.8, 'board': 1.0, 'pulp': 1.0}[kind]
    h = 0.5 + span * (rank_uniform(h) - 0.5)
    hbytes = np.round(h * 255).astype(np.uint8)
    surface, slope_range = pack.pack(hbytes)
    fluid = fluid_map(yl, kind)
    return look, surface, mean, slope_range, float(hbytes.mean() / 255.0), fluid


def fluid_map(yl, kind):
    """The FLUID surface (for the wet and impasto engines), at the look's scale, RGBA8:
    R = absorbency / sizing variation (paper formation, the 2–8 mm flocs), mean ~0.5 — ragged blooms and wet fronts.
    G,B = fibre direction in double-angle form, 0.5 + 0.5·(cos2θ, sin2θ)·coherence — fibre-directed wicking.
          From the structure tensor of the picture: fibres run ACROSS the brightness gradient.
    A = pore capacity (how much water a spot holds), mean ~0.5, correlated with formation and the tooth's valleys.
    The look is 0.125 mm per texel at its 2.5 doc px (R10: 1 doc px ≈ 0.05 mm), so 2–8 mm is 16–64 texels."""
    def norm(b):
        return (b - b.mean()) / (b.std() + 1e-9)
    formation = norm(gaussian(yl, 4.0) - gaussian(yl, 24.0))
    if kind == 'rice':
        formation = norm(0.6 * formation + 0.4 * norm(gaussian(yl, 2.0)))   # whiter, opaque fibre clumps wick more
    r = np.clip(0.5 + 0.17 * formation, 0, 1)
    # Structure tensor of the fine detail.
    f = gaussian(yl, 0.8)
    gx = (np.roll(f, -1, 1) - np.roll(f, 1, 1)) / 2
    gy = (np.roll(f, -1, 0) - np.roll(f, 1, 0)) / 2
    jxx = gaussian(gx * gx, 3.0); jyy = gaussian(gy * gy, 3.0); jxy = gaussian(gx * gy, 3.0)
    lam = np.sqrt((jxx - jyy) ** 2 + 4 * jxy ** 2)
    coherence = np.clip(lam / (jxx + jyy + 1e-12), 0, 1)
    # Double angle of the GRADIENT is (jxx-jyy, 2jxy)/lam; the fibre runs at +90°, which negates the double angle.
    c2 = -(jxx - jyy) / (lam + 1e-12)
    s2 = -(2 * jxy) / (lam + 1e-12)
    g = np.clip(0.5 + 0.5 * c2 * coherence, 0, 1)
    b = np.clip(0.5 + 0.5 * s2 * coherence, 0, 1)
    valleys = norm(-(gaussian(yl, 1.0) - gaussian(yl, 6.0)))
    a = np.clip(0.5 + 0.12 * valleys + 0.08 * formation, 0, 1)
    return np.round(np.dstack([r, g, b, a]) * 255).astype(np.uint8)


# Physical behaviour per kind of paper, for the dry, wet and impasto engines (catalogue fields; mm are real millimetres).
PHYSICAL = {
    'pulp':  dict(toothDepthMm=0.12, compliance=0.35, sizing=0.35, absorbency=0.55, capacity=0.5, wickSpeed=0.4, anisotropy=0.2),
    'board': dict(toothDepthMm=0.15, compliance=0.15, sizing=0.3, absorbency=0.6, capacity=0.45, wickSpeed=0.35, anisotropy=0.15),
    'rice':  dict(toothDepthMm=0.03, compliance=0.6, sizing=0.05, absorbency=0.9, capacity=0.35, wickSpeed=0.85, anisotropy=0.7),
    'chunk': dict(toothDepthMm=0.1, compliance=0.45, sizing=0.25, absorbency=0.65, capacity=0.55, wickSpeed=0.5, anisotropy=0.35),
    'chalk': dict(toothDepthMm=0.02, compliance=0.0, sizing=1.0, absorbency=0.0, capacity=0.05, wickSpeed=0.05, anisotropy=0.0),
}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('photo')
    ap.add_argument('out_dir')
    ap.add_argument('id')
    ap.add_argument('--kind', default='pulp', choices=['pulp', 'rice', 'chunk', 'board', 'chalk'])
    ap.add_argument('--size', type=int, default=1024)
    ap.add_argument('--tooth', type=float, default=1.6, help='tooth scale in texels: larger = coarser grain')
    ap.add_argument('--keep-mottle', type=float, default=0.85, help='0 = flatten all mid-scale light, 1 = keep it')
    a = ap.parse_args()
    look, surface, mean, sr, hmean, fluid = convert(a.photo, a.size, a.kind, a.tooth, a.keep_mottle)
    os.makedirs(a.out_dir, exist_ok=True)
    # The look as a high-quality JPEG (a photograph; PNG of paper grain is 5x the size for no visible gain).
    look.save(os.path.join(a.out_dir, f'look_{a.id}.jpg'), quality=92, subsampling=0, optimize=True)
    # The surface ships as its HEIGHT alone (8-bit grey); the app derives the slopes and h^2 at load with
    # SurfaceMaps.pack, the Kotlin twin of pack.py, so the packed RGBA never has to be stored.
    # An 8-bit grey PNG (no gAMA chunk). Android decodes it as-is. Java's ImageIO getRGB treats grey as LINEAR and
    # brightens it, so a JVM reader must take the raster's samples, as LaunchMaterialsTest does.
    Image.fromarray(surface[..., 2]).save(os.path.join(a.out_dir, f'height_{a.id}.png'), optimize=True)
    # The fluid map holds only broad structure (formation, fibre direction, ~0.5 mm+): a quarter of the resolution.
    Image.fromarray(fluid).resize((a.size // 4, a.size // 4), Image.LANCZOS).save(os.path.join(a.out_dir, f'fluid_{a.id}.png'), optimize=True)
    # The tint divides by the mean of what SHIPS, so measure it on the decoded JPEG, not on the picture before it.
    shipped = np.asarray(Image.open(os.path.join(a.out_dir, f'look_{a.id}.jpg')).convert('RGB'), dtype=np.float64) / 255.0
    mean = shipped.reshape(-1, 3).mean(axis=0)
    hexmean = '#%02X%02X%02X' % tuple(int(round(c * 255)) for c in mean)
    print(json.dumps({'id': a.id, 'mean': hexmean, 'slopeRange': sr, 'size': a.size, 'heightMean': round(hmean, 4),
                      **PHYSICAL[a.kind]}))


if __name__ == '__main__':
    main()
