"""Felt-finished papers (watercolour cold/hot/rough, drawing paper) from ONE raking-lit photo plus a fibre-network model.

Why (2026-10-06): the brush-engines session needs real drawing and watercolour papers, with the tooth at its true size
(drawing tooth ~0.1-0.15 mm, cold-press bumps ~1 mm). A photo of such a paper is lit from the side, so its brightness is
the SLOPE of the relief, and it cannot hold the fibre grain at all (a phone or scanner blurs 0.02 mm fibres away). So:

  1. FELT: the bumps the felt blanket pressed in. Recovered from the photo by shape-from-shading (relief_from_shading:
     the lamp's axis read off the spectrum, the slope integrated in Fourier space). Measured, not invented.
  2. FIBRES: the grain a pencil's dust catches. Paper IS a random fibre network (Kallmes & Corte 1960): cotton fibres
     ~2.5 mm long, ~0.02 mm wide, laid at random on the mould, clumped into flocs. fibre_network() lays them on a torus
     at their real size and sums their thickness; the flocs follow the photo's own formation (its mottling).
  3. The grades are the same mould-made sheet, finished differently, so one photo gives all four:
       cold press  the felt as photographed, fibres on its hills;
       rough       never pressed: the felt bumps bigger and deeper;
       hot press   pressed between hot plates: the felt ironed mostly flat, the fibre grain left;
       drawing     a lighter, finer felt, the fibre grain dominant.
  4. The LOOK is the photo itself, its lamp shading reduced (the app's relief light draws the bumps from the SURFACE,
     so light and pigment sit on the same hills), at the photo's physical scale.

All lengths are millimetres (R10: 1 doc px = 0.05 mm, PaperPhysical.DOC_PX_PER_MM = 20).

python felt2paper.py <photo> <out_dir> <id> --grade cold|rough|hot|drawing
Writes look_<id>.jpg, height_<id>.png, fluid_<id>.png; prints the catalogue numbers as one JSON line.
"""
import argparse
import json
import os

import numpy as np
from PIL import Image

import pack
from photo2paper import gaussian, linear_to_srgb, periodic_component, rank_uniform, srgb_to_linear

DOC_PX_PER_MM = 20.0
SIZE = 1024            # surface and look tiles; the fluid map is a quarter
SURFACE_MM = 0.05      # one surface texel = 1 doc px: the fibre grain needs it

# Per grade. feltMm: the felt's bump wavelength. felt/fibre: their weights in the height (each normalised first).
# lookShading: how much of the photo's lamp shading the look keeps. white: the sheet's colour (the photo is an off-white
# sheet under a dim lamp, so its exposure is set to the real paper's). The rest is physics for the engines (R10 §11).
GRADES = {
    'cold':    dict(feltMm=1.1, felt=1.0, fibre=0.55, lookShading=0.3, light=True, white='#F1EEE4',
                    toothDepthMm=0.18, compliance=0.2, sizing=0.8, absorbency=0.4, capacity=0.6, wickSpeed=0.3, anisotropy=0.1),
    'rough':   dict(feltMm=2.2, felt=1.0, fibre=0.4, lookShading=0.45, light=True, white='#F1EEE4',
                    toothDepthMm=0.35, compliance=0.2, sizing=0.75, absorbency=0.45, capacity=0.7, wickSpeed=0.3, anisotropy=0.1),
    'hot':     dict(feltMm=1.1, felt=0.3, fibre=1.0, lookShading=0.08, light=False, white='#F4F2EB',
                    toothDepthMm=0.03, compliance=0.15, sizing=0.85, absorbency=0.35, capacity=0.45, wickSpeed=0.25, anisotropy=0.1),
    'drawing': dict(feltMm=0.5, felt=0.3, fibre=1.0, lookShading=0.1, light=False, white='#F6F5F0',
                    toothDepthMm=0.07, compliance=0.55, sizing=0.6, absorbency=0.5, capacity=0.3, wickSpeed=0.35, anisotropy=0.15),
}
# Cotton fibre (rag papers): length, width, how many fibres deep the surface layer is, curl, machine-direction bias.
FIBRE = dict(length_mm=2.5, width_mm=0.02, coverage=4.0, curl=0.6, anisotropy=0.1)


def fft_resize(img, n):
    """Resample a periodic picture to n x n through its spectrum: exact on a torus, so no wrap seam can open.
    Frequencies are copied by their signed index (k mod size), which is right for odd and even sizes alike; the
    unpaired Nyquist row is dropped. Shrinking tapers the top 16% of the kept band so the cut cannot ring."""
    m = img.shape[0]
    if m == n:
        return img.copy()
    keep = (min(m, n) - 1) // 2
    k = np.arange(-keep, keep + 1)
    f = np.fft.fft2(img)
    out = np.zeros((n, n), dtype=complex)
    w = np.clip((1.0 - np.abs(k) / (keep + 1)) / 0.16, 0, 1) if n < m else np.ones(k.size)
    out[np.ix_(k % n, k % n)] = f[np.ix_(k % m, k % m)] * w[:, None] * w[None, :]
    return np.real(np.fft.ifft2(out)) * (n * n) / (m * m)


def bump_wavelength(y):
    """The relief's dominant wavelength in pixels: the peak of the spectrum's k^2-weighted radial profile."""
    s = y.shape[0]
    p = np.abs(np.fft.fft2(y - y.mean())) ** 2
    fy = np.fft.fftfreq(s)[:, None]
    fx = np.fft.fftfreq(s)[None, :]
    r = np.hypot(fx, fy)
    bins = np.linspace(2.0 / s, 0.5, 240)
    idx = np.digitize(r, bins)
    prof = np.array([p[idx == i].mean() if (idx == i).any() else 0.0 for i in range(1, len(bins))])
    k = 0.5 * (bins[1:] + bins[:-1])
    return float(1.0 / k[np.argmax(prof * k * k)])


def relief_from_shading(y, bump_px, eps=0.3):
    """Height from a raking-lit photo. Brightness = a*dh/dl + b*h: the lamp on the slopes along its axis l, plus a
    little of the height itself (valleys see less of the room, a print's tone curve). In Fourier space that is
    (i*2*pi*(k.l) + b) h^, inverted with eps*|k|^2 regularising the band across the lamp, where the photo holds no relief.

    The axis l is where the spectrum is strongest (a felt's bumps are isotropic; the lamp makes their picture vary as
    cos^2 of the angle to l). b is solved from the picture: for a true isotropic relief the slope along l is as likely
    to climb as to fall, so its skewness is zero, and a wrong b leaves an emboss that skews it. The lamp is taken to be
    up-left, the photographer's convention; the felt then comes out with broad rounded tops and narrow creases (negative
    height skew), which is how a felt-pressed sheet profiles. Returns the height and the b found (per bump wavenumber)."""
    s = y.shape[0]
    f = np.fft.fft2(y - y.mean())
    p = np.abs(f) ** 2
    fy = np.fft.fftfreq(s)[:, None]
    fx = np.fft.fftfreq(s)[None, :]
    r = np.hypot(fx, fy)
    th = np.arctan2(fy, fx)
    m = (r > 0.02) & (r < 0.25)
    phi = np.arctan2((p[m] * np.sin(2 * th[m])).sum(), (p[m] * np.cos(2 * th[m])).sum()) / 2
    if np.cos(phi) + np.sin(phi) < 0:
        phi += np.pi
    d = 2j * np.pi * (fx * np.cos(phi) + fy * np.sin(phi))
    k0 = 2 * np.pi / bump_px

    def solve(b):
        db = d + b * k0
        h = f * np.conj(db) / (np.abs(db) ** 2 + eps * (2 * np.pi) ** 2 * r ** 2 + 1e-12)
        h[0, 0] = 0
        return h

    highpass = 1 - np.exp(-2 * (np.pi * 0.8 * bump_px * r) ** 2)   # the same band the caller keeps

    def skew_of_slope(b):
        dl = np.real(np.fft.ifft2(solve(b) * d * highpass))
        dl -= dl.mean()
        return float((dl ** 3).mean() / (dl ** 2).mean() ** 1.5)

    grid = np.linspace(-1.0, 1.0, 11)
    sk = [skew_of_slope(b) for b in grid]
    b = 0.0
    for i in range(len(grid) - 1):
        if sk[i] == 0 or sk[i] * sk[i + 1] < 0:
            b = grid[i] + (grid[i + 1] - grid[i]) * sk[i] / (sk[i] - sk[i + 1])
            break
    return np.real(np.fft.ifft2(solve(b))), b


def fibre_network(n, texel_mm, floc, length_mm, width_mm, coverage, curl, anisotropy, seed):
    """A random fibre network on an n x n torus: the summed thickness of the fibres (in fibre layers) at each texel.

    Each fibre is a gently curved ribbon (curvature ~ curl/length), its length log-normal around length_mm, its angle
    biased to the machine direction by `anisotropy`. Fibres land where the floc field (mean 1) is dense. Each sample along
    a fibre splats width*step of coverage, bilinearly, so a fibre narrower than a texel still adds its true area."""
    rng = np.random.default_rng(seed)
    area = (n * texel_mm) ** 2
    count = int(coverage * area / (length_mm * width_mm))
    fmax = floc.max()
    out = np.zeros(n * n)
    step = 0.5                                     # texels between samples along a fibre
    done = 0
    while done < count:
        k = min(40000, count - done)
        # Fibre centres, thinned by the floc field (rejection), in texels.
        cx = rng.uniform(0, n, k * 2)
        cy = rng.uniform(0, n, k * 2)
        keep = rng.uniform(0, fmax, k * 2) < floc[cy.astype(int) % n, cx.astype(int) % n]
        cx, cy = cx[keep][:k], cy[keep][:k]
        k = cx.size
        # Angle with a cos(2θ) machine-direction bias, by rejection.
        th = rng.uniform(0, np.pi, k * 3)
        th = th[rng.uniform(0, 1 + anisotropy, k * 3) < 1 + anisotropy * np.cos(2 * th)][:k]
        length = np.exp(rng.normal(np.log(length_mm), 0.35, k)) / texel_mm
        kappa = rng.normal(0, curl, k) / length    # radians per texel
        samples = int(np.ceil(length.max() / step)) + 1
        t = np.linspace(-0.5, 0.5, samples)[None, :] * length[:, None]          # arc length from the centre
        inside = np.abs(t) <= 0.5 * length[:, None]
        ang = th[:, None] + kappa[:, None] * t
        # Integrate the curved path from the centre (midpoint rule on the angle).
        dx = np.cos(ang) * (step * np.sign(t)); dy = np.sin(ang) * (step * np.sign(t))
        mid = samples // 2
        px = np.empty_like(t); py = np.empty_like(t)
        px[:, mid] = 0; py[:, mid] = 0
        px[:, mid + 1:] = np.cumsum(dx[:, mid + 1:], axis=1); py[:, mid + 1:] = np.cumsum(dy[:, mid + 1:], axis=1)
        px[:, :mid] = np.cumsum(dx[:, :mid][:, ::-1], axis=1)[:, ::-1]; py[:, :mid] = np.cumsum(dy[:, :mid][:, ::-1], axis=1)[:, ::-1]
        x = (cx[:, None] + px)[inside]; y = (cy[:, None] + py)[inside]
        w = width_mm / texel_mm * step
        x0 = np.floor(x).astype(np.int64); y0 = np.floor(y).astype(np.int64)
        fx = x - x0; fy = y - y0
        for ox, oy, wt in ((0, 0, (1 - fx) * (1 - fy)), (1, 0, fx * (1 - fy)), (0, 1, (1 - fx) * fy), (1, 1, fx * fy)):
            out += np.bincount(((y0 + oy) % n) * n + (x0 + ox) % n, weights=wt * w, minlength=n * n)
        done += k
    return out.reshape(n, n)


def norm(b):
    return (b - b.mean()) / (b.std() + 1e-9)


def periodic_crop(img, c, at):
    """A c x c crop with its corner at `at` (row, column), made periodic (Moisan): part of a picture as a seamless tile."""
    return periodic_component(img[at[0]:at[0] + c, at[1]:at[1] + c])


def sharpest_square(y, c, bump_px):
    """Where a c x c crop of the photo is most in focus. A raking-lit close-up has a shallow depth of field: a band of
    the sheet is sharp and the rest is soft, and soft bumps would read as a different, smoother paper. Focus is the
    local strength of the bump band; the crop maximises its weakest corner of coverage (its 10th percentile)."""
    band = y - gaussian(y, bump_px)
    focus = np.sqrt(gaussian(band * band, 2.0 * bump_px))
    m = y.shape[0]
    best, at = -1.0, (0, 0)
    for r in range(0, m - c + 1, max(8, (m - c) // 24 or 8)):
        for q in range(0, m - c + 1, max(8, (m - c) // 24 or 8)):
            v = np.percentile(focus[r:r + c:4, q:q + c:4], 10)
            if v > best:
                best, at = v, (r, q)
    return at


def even_angles(h, bump_px, max_gain=3.0):
    """Make the relief isotropic again. Across the lamp the photo held little relief, so the recovered bumps are
    stretched that way; a mould-made sheet has none (its anisotropy is ~0.1). The spectrum's power in the bump band is
    measured in 24 directions and each direction scaled to the average (at most max_gain, where there is little to
    lift)."""
    s = h.shape[0]
    f = np.fft.fft2(h)
    fy = np.fft.fftfreq(s)[:, None]
    fx = np.fft.fftfreq(s)[None, :]
    r = np.hypot(fx, fy)
    th = np.mod(np.arctan2(fy, fx), np.pi)
    bins = np.minimum((th / np.pi * 24).astype(int), 23)
    band = (r > 0.4 / bump_px) & (r < 2.5 / bump_px)
    power = np.array([(np.abs(f[band & (bins == i)]) ** 2).mean() for i in range(24)])
    power = np.convolve(np.r_[power[-1], power, power[0]], [0.25, 0.5, 0.25], 'valid')   # wraps: 0 and pi meet
    gain = np.clip(np.sqrt(power.mean() / power), 1.0 / max_gain, max_gain)
    return np.real(np.fft.ifft2(f * gain[bins]))


def even_amplitude(h, sigma):
    """Divide out slow changes in the relief's strength (focus, lamp distance), keeping its shape: a stationary paper."""
    return h / np.sqrt(gaussian(h * h, sigma) + 1e-12)


def convert(photo, grade, seed=1):
    g = GRADES[grade]
    rgb = np.asarray(Image.open(photo).convert('RGB'), dtype=np.float64) / 255.0
    side = min(rgb.shape[:2])
    lin = srgb_to_linear(rgb[:side, :side])
    # De-light the lamp's falloff, then seamless, as photo2paper does.
    y = lin @ [0.2126, 0.7152, 0.0722]
    lin = lin * (y.mean() / np.maximum(gaussian(y, side / 5.0), 1e-6))[..., None]
    lin = np.clip(np.dstack([periodic_component(lin[..., k]) for k in range(3)]), 0, None)
    y = lin @ [0.2126, 0.7152, 0.0722]

    # The photo's scale: its felt bump is this grade's feltMm.
    bump_px = bump_wavelength(y / gaussian(y, side / 16.0))
    photo_mm_per_px = g['feltMm'] / bump_px
    shade = y / np.maximum(gaussian(y, 2.0 * bump_px), 1e-6)          # the lamp on the bumps
    felt_full, cavity = relief_from_shading(shade, bump_px)
    felt_full = felt_full - gaussian(felt_full, 0.8 * bump_px)         # integration's runaway lows go; the bumps stay

    # SURFACE: SIZE texels of SURFACE_MM. The felt is the matching piece of the photo, upsampled smoothly.
    tile_mm = SIZE * SURFACE_MM
    crop = int(round(tile_mm / photo_mm_per_px))
    if crop > side:
        raise SystemExit(f'{photo} holds {side * photo_mm_per_px:.0f} mm; a {tile_mm:.0f} mm surface needs more')
    at = sharpest_square(y, crop, bump_px)
    felt = periodic_crop(felt_full, crop, at)
    felt = fft_resize(even_amplitude(even_angles(felt, bump_px), 3.0 * bump_px), SIZE)
    # Formation (flocs, 2-8 mm): the photo's own mottling over the same piece, without the bumps.
    mottle = periodic_crop(gaussian(y, 1.5 * bump_px), crop, at)
    mottle = fft_resize(mottle, SIZE)
    formation = norm(gaussian(mottle, 2.0 / SURFACE_MM) - gaussian(mottle, 8.0 / SURFACE_MM))
    floc = np.clip(1.0 + 0.35 * formation, 0.2, None)
    fibres = fibre_network(SIZE, SURFACE_MM, floc, seed=seed, **FIBRE)
    fibres = gaussian(fibres, 0.6)                                      # wet pressing rounds the fibre edges
    # The press evens out the sheet's thick and thin flocs on its top face (they stay in the fluid map, as absorbency);
    # what a pencil meets is the fibre grain within a millimetre.
    grain = fibres - gaussian(fibres, 0.5 / SURFACE_MM)
    h = g['felt'] * norm(felt) + g['fibre'] * norm(grain)
    h = rank_uniform(h)
    hbytes = np.round(h * 255).astype(np.uint8)
    surface, slope_range = pack.pack(hbytes)

    # FLUID (quarter size): formation, the fibres' own direction, capacity in the felt's valleys.
    q = SIZE // 4
    f4 = gaussian(fibres, 1.0)
    gx = (np.roll(f4, -1, 1) - np.roll(f4, 1, 1)) / 2
    gy = (np.roll(f4, -1, 0) - np.roll(f4, 1, 0)) / 2
    sig = 0.5 * FIBRE['length_mm'] / SURFACE_MM
    jxx = gaussian(gx * gx, sig); jyy = gaussian(gy * gy, sig); jxy = gaussian(gx * gy, sig)
    lam = np.sqrt((jxx - jyy) ** 2 + 4 * jxy ** 2)
    coherence = np.clip(lam / (jxx + jyy + 1e-12), 0, 1)
    c2 = -(jxx - jyy) / (lam + 1e-12); s2 = -(2 * jxy) / (lam + 1e-12)   # fibres run across their gradient
    valleys = norm(-(felt - gaussian(felt, 0.5 * g['feltMm'] / SURFACE_MM)))
    fluid = np.dstack([0.5 + 0.17 * formation, 0.5 + 0.5 * c2 * coherence, 0.5 + 0.5 * s2 * coherence,
                       0.5 + 0.12 * valleys + 0.08 * formation])
    fluid = np.dstack([fft_resize(fluid[..., k], q) for k in range(4)])
    fluid = np.round(np.clip(fluid, 0, 1) * 255).astype(np.uint8)

    # LOOK: the whole photo, its lamp shading reduced, at SIZE.
    flat = lin * ((1.0 + g['lookShading'] * (shade - 1.0)) / np.maximum(shade, 1e-6))[..., None]
    look_lin = np.dstack([fft_resize(flat[..., k], SIZE) for k in range(3)])
    # Expose to the sheet's white: each channel scaled in linear light until the sRGB mean is the target (twice, as the
    # curve is not linear). The paper's own colour variation rides on top unchanged.
    target = np.array([int(g['white'][i:i + 2], 16) for i in (1, 3, 5)]) / 255.0
    for _ in range(3):
        mean_srgb = linear_to_srgb(look_lin).reshape(-1, 3).mean(0)
        look_lin = look_lin * (srgb_to_linear(target) / srgb_to_linear(mean_srgb))[None, None, :]
    look = Image.fromarray(np.round(linear_to_srgb(look_lin) * 255).astype(np.uint8))
    look_texel_px = side * photo_mm_per_px * DOC_PX_PER_MM / SIZE
    return dict(look=look, height=hbytes, fluid=fluid, slopeRange=slope_range, heightMean=float(hbytes.mean() / 255.0),
                lookTexelPx=round(look_texel_px, 3), texelPx=SURFACE_MM * DOC_PX_PER_MM, bumpPx=bump_px, crop=(crop, at), cavity=cavity)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('photo'); ap.add_argument('out_dir'); ap.add_argument('id')
    ap.add_argument('--grade', required=True, choices=sorted(GRADES))
    ap.add_argument('--seed', type=int, default=1)
    a = ap.parse_args()
    r = convert(a.photo, a.grade, a.seed)
    os.makedirs(a.out_dir, exist_ok=True)
    look_path = os.path.join(a.out_dir, f'look_{a.id}.jpg')
    r['look'].save(look_path, quality=92, subsampling=0, optimize=True)
    Image.fromarray(r['height']).save(os.path.join(a.out_dir, f'height_{a.id}.png'), optimize=True)
    Image.fromarray(r['fluid']).save(os.path.join(a.out_dir, f'fluid_{a.id}.png'), optimize=True)
    shipped = np.asarray(Image.open(look_path).convert('RGB'), dtype=np.float64).reshape(-1, 3).mean(0)
    g = GRADES[a.grade]
    print(json.dumps({'id': a.id, 'mean': '#%02X%02X%02X' % tuple(int(round(c)) for c in shipped), 'size': SIZE,
                      'slopeRange': r['slopeRange'], 'heightMean': round(r['heightMean'], 4), 'texelPx': r['texelPx'],
                      'lookTexelPx': r['lookTexelPx'], 'bumpPx': round(r['bumpPx'], 3), 'crop': r['crop'], 'cavity': round(r['cavity'], 3), 'light': g['light'],
                      **{k: g[k] for k in ('toothDepthMm', 'compliance', 'sizing', 'absorbency', 'capacity', 'wickSpeed', 'anisotropy')}}))


if __name__ == '__main__':
    main()
