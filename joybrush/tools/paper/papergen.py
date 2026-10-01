"""Joy Brush paper generator (pilot). Every output TILES (all maths on a torus).
Height: 0 = deepest pit, 1 = highest bump. Albedo: RGB, mean ~ the paper's base colour.
Deterministic: a fixed seed per paper."""
import numpy as np
from scipy import ndimage as ndi
from PIL import Image
import os, sys

N = 1024
OUT = os.path.join(os.path.dirname(__file__), "out")
os.makedirs(OUT, exist_ok=True)

def norm(a):
    a = a - a.min(); m = a.max()
    return a / m if m > 0 else a

def blur(a, s):
    return ndi.gaussian_filter(a, s, mode="wrap")

def fbm(rng, octaves=6, base=4, pers=0.5, n=N):
    """Band-limited noise built in the frequency domain, so it tiles exactly."""
    out = np.zeros((n, n))
    amp = 1.0
    for o in range(octaves):
        cells = base * 2 ** o
        w = rng.standard_normal((n, n))
        out += amp * norm(blur(w, n / cells / 2.5)) 
        amp *= pers
    return norm(out)

def fibres(rng, count, length, width, curl=0.15, n=N, weight_range=(0.4, 1.0), aniso=None):
    """Random curved fibres drawn on a torus. aniso=(angle, strength) biases direction."""
    acc = np.zeros((n, n))
    pts = []
    for _ in range(count):
        x, y = rng.uniform(0, n, 2)
        if aniso is None:
            a = rng.uniform(0, np.pi)
        else:
            a = aniso[0] + rng.normal(0, aniso[1])
        L = length * rng.uniform(0.4, 1.6)
        steps = max(2, int(L))
        w = rng.uniform(*weight_range)
        da = rng.normal(0, curl / max(1, steps) * 6)
        for s in range(steps):
            a += da + rng.normal(0, curl * 0.02)
            x += np.cos(a); y += np.sin(a)
            pts.append((int(y) % n, int(x) % n, w))
    pts = np.array(pts)
    np.add.at(acc, (pts[:, 0].astype(int), pts[:, 1].astype(int)), pts[:, 2])
    return blur(acc, width)

def save_grey(a, name):
    Image.fromarray((np.clip(a, 0, 1) * 255 + 0.5).astype(np.uint8), "L").save(os.path.join(OUT, name))

def save_rgb(a, name):
    Image.fromarray((np.clip(a, 0, 1) * 255 + 0.5).astype(np.uint8), "RGB").save(os.path.join(OUT, name))

def tint(detail, rgb, strength=1.0):
    """detail ~ mean 0.5 luminance variation -> albedo around rgb."""
    d = (detail - detail.mean()) * strength
    return np.clip(np.array(rgb)[None, None, :] * (1 + d[..., None]), 0, 1)

# ---------------- papers ----------------
def pulp(seed, rough):
    """Pressed paper pulp. rough 0 = factory, 1 = chunky handmade.
    Scale: 1 texel = 1 doc px ~ 0.05 mm on the Note 9 at 1x. Fibres are hair-thin (sub-texel) and
    20-60 px long and overlap densely; what reads at 1x is FORMATION (flocs, 1-5 mm) and TOOTH
    (felt-imprint bumps, 0.5-2 mm). Pressing flattens the tops."""
    rng = np.random.default_rng(seed)
    fib = fibres(rng, int(22000 + 8000 * rough), 30, 0.45, curl=0.25)
    flocs = fbm(rng, octaves=4, base=8, pers=0.55)
    # tooth: felt imprint — soft lumps sized 10-40 px, more and bigger when rough
    lumps = blur(rng.standard_normal((N, N)), 4 + 6 * rough)
    lumps = norm(lumps)
    h = 0.35 * norm(fib) + (0.25 + 0.15 * rough) * flocs + (0.15 + 0.6 * rough) * lumps
    if rough > 0.6:
        chunks = blur((rng.random((N, N)) < 0.00015).astype(float), 5)
        h += 0.5 * norm(chunks)
    h = norm(h)
    # pressing: the press flattens the highest fibres (factory paper is pressed hardest)
    press = 0.55 + 0.4 * rough
    h = np.minimum(h, press) + 0.15 * np.maximum(h - press, 0)
    return norm(h)

def canvas(seed, threads=48, slub=0.35):
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:N, 0:N] / N
    # per-thread thickness & wobble (slubs) so the weave is regular but its flaws are not
    nw = threads
    wob_x = blur(rng.standard_normal(N), 30, ) if False else None
    def thread_profile(coord, other, count):
        k = coord * count
        idx = np.floor(k).astype(int) % count
        thick = 0.75 + slub * norm(blur(rng.standard_normal((count, N)), (0, 18)))[idx, (other * N).astype(int) % N]
        f = k - np.floor(k)
        return np.clip(1 - np.abs(f - 0.5) * 2 / thick, 0, 1) ** 0.6, idx
    warp, wi = thread_profile(x, y, nw)
    weft, fi = thread_profile(y, x, nw)
    over = ((wi + fi) % 2 == 0)
    # over/under: the thread on top rises in the middle of its crossing
    cx = np.sin(np.pi * ((y * nw) % 1)); cy = np.sin(np.pi * ((x * nw) % 1))
    h = np.where(over, warp * (0.6 + 0.4 * cx), weft * (0.6 + 0.4 * cy))
    h = np.maximum(h, np.where(over, weft * 0.45, warp * 0.45))
    h = blur(h, 0.8) + 0.12 * fibres(rng, 4000, 10, 0.6) + 0.08 * fbm(rng, 4, 8)
    return norm(h)

def chalkboard(seed):
    rng = np.random.default_rng(seed)
    grit = blur(rng.random((N, N)), 0.6)
    pits = blur((rng.random((N, N)) < 0.01).astype(float), 0.8)
    h = norm(grit) * 0.7 - norm(pits) * 0.4 + 0.15 * fbm(rng, 3, 4)
    # albedo: erased chalk dust â€” big soft swipes + fine powder
    swipes = np.zeros((N, N))
    for _ in range(26):
        sw = fibres(rng, 1, 1, 0) * 0
        cx, cy = rng.uniform(0, N, 2); a = rng.uniform(0, np.pi); L = rng.uniform(150, 600); W = rng.uniform(30, 120)
        yy, xx = np.mgrid[0:N, 0:N]
        dx = (xx - cx + N / 2) % N - N / 2; dy = (yy - cy + N / 2) % N - N / 2
        u = dx * np.cos(a) + dy * np.sin(a); v = -dx * np.sin(a) + dy * np.cos(a)
        swipes += rng.uniform(0.2, 1) * np.exp(-(u / L) ** 2 - (v / W) ** 2)
    streaks = blur(rng.standard_normal((N, N)), (0.5, 12))
    dust = norm(swipes) * (0.6 + 0.4 * norm(streaks)) + 0.25 * fbm(rng, 5, 3)
    return norm(h), norm(dust)

def crumpled(seed, cells=40):
    rng = np.random.default_rng(seed)
    pts = rng.uniform(0, N, (cells, 2))
    yy, xx = np.mgrid[0:N, 0:N].astype(float)
    planes = rng.normal(0, 1, (cells, 3))
    d1 = np.full((N, N), 1e9); d2 = np.full((N, N), 1e9); h = np.zeros((N, N))
    for i, (px, py) in enumerate(pts):
        dx = (xx - px + N / 2) % N - N / 2; dy = (yy - py + N / 2) % N - N / 2
        d = np.hypot(dx, dy)
        nearer = d < d1
        d2 = np.where(nearer, d1, np.minimum(d2, d)); d1 = np.where(nearer, d, d1)
        facet = planes[i, 0] * dx / N * 3 + planes[i, 1] * dy / N * 3 + planes[i, 2] * 0.3
        h = np.where(nearer, facet, h)
    crease = np.exp(-((d2 - d1) / 3.0) ** 2)
    h = blur(h, 4) + 0.35 * crease + 0.1 * pulp(seed + 1, 0.1)
    return norm(h)

if __name__ == "__main__":
    which = sys.argv[1:] or ["pulp"]
    if "pulp" in which:
        for r, nm in [(0.0, "factory"), (0.5, "artisan"), (1.0, "handmade")]:
            save_grey(pulp(11, r), f"pulp_{nm}_h.png")
    if "canvas" in which:
        save_grey(canvas(21), "canvas_medium_h.png")
    if "chalk" in which:
        h, d = chalkboard(31); save_grey(h, "chalk_h.png"); save_grey(d, "chalk_dust.png")
    if "crumpled" in which:
        save_grey(crumpled(41), "crumpled_h.png")

def canvas2(seed, threads=64, slub=0.25, twist=0.12, wobble=2.0):
    """Tight plain weave: rounded threads that nearly touch, twisted (diagonal striation),
    uneven along their length (slubs), and not quite straight (wobble, px)."""
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:N, 0:N].astype(float)
    p = N / threads
    def wav(axis_len_noise):
        return wobble * norm(blur(rng.standard_normal(N), 25)) * 2 - wobble
    wx = np.interp(np.arange(N), np.arange(N), wav(0)); wy = np.interp(np.arange(N), np.arange(N), wav(0))
    X = xx + wx[None, :] * 0 + wy[:, None]    # vertical threads wobble sideways along y
    Y = yy + wx[None, :]                       # horizontal threads wobble along x
    ix = np.floor(X / p).astype(int) % threads; fx = (X / p) % 1
    iy = np.floor(Y / p).astype(int) % threads; fy = (Y / p) % 1
    thickV = 0.85 + slub * (norm(blur(rng.standard_normal((threads, N)), (0, 14))) - 0.5)
    thickH = 0.85 + slub * (norm(blur(rng.standard_normal((threads, N)), (0, 14))) - 0.5)
    tv = thickV[ix, yy.astype(int)]; th = thickH[iy, xx.astype(int)]
    prof = lambda f, t: np.sqrt(np.clip(1 - ((f - 0.5) * 2 / t) ** 2, 0, 1))
    V = prof(fx, tv) * (1 + twist * np.sin(2 * np.pi * (Y / p * 1.0 + fx * 1.0) * 2))
    H = prof(fy, th) * (1 + twist * np.sin(2 * np.pi * (X / p * 1.0 + fy * 1.0) * 2))
    over = ((ix + iy) % 2 == 0)
    lift = 0.55 + 0.45 * np.where(over, np.sin(np.pi * fy), np.sin(np.pi * fx))
    top = np.where(over, V, H) * lift
    under = np.where(over, H, V) * 0.5
    h = np.maximum(top, under)
    h = blur(h, 0.7) + 0.06 * fibres(rng, 9000, 6, 0.4) + 0.06 * fbm(rng, 4, 6)
    return norm(h)

def crumpled2(seed):
    """Crumpled paper: facets at three sizes, each a tilted plane; the steps between facets are the
    creases, some up and some down. Softened, then a little pulp."""
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:N, 0:N].astype(float)
    total = np.zeros((N, N))
    for cells, amp, soft in [(10, 1.0, 6), (30, 0.55, 3), (90, 0.25, 1.5)]:
        pts = rng.uniform(0, N, (cells, 2)); g = rng.normal(0, 1, (cells, 2)); off = rng.normal(0, 0.4, cells)
        best = np.full((N, N), 1e9); h = np.zeros((N, N))
        for i, (px, py) in enumerate(pts):
            dx = (xx - px + N / 2) % N - N / 2; dy = (yy - py + N / 2) % N - N / 2
            d = dx * dx + dy * dy
            m = d < best; best = np.where(m, d, best)
            h = np.where(m, (g[i, 0] * dx + g[i, 1] * dy) / (N / cells) + off[i], h)
        total += amp * norm(blur(h, soft))
    return norm(total + 0.08 * pulp(seed + 1, 0.0))

if __name__ == "__main__" and "v2" in sys.argv:
    save_grey(canvas2(22), "canvas2_h.png"); save_grey(crumpled2(42), "crumpled2_h.png")
