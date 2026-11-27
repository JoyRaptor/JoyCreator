from pathlib import Path
p=Path('joybrush/tools/paper/prepare_test_surfaces.py')
s=p.read_text().replace('from papergen import pulp','''def pulp(seed, rough):
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
    return norm(np.minimum(h, press) + .15*np.maximum(h-press, 0))''')
s=s.replace('original papergen.py model','periodic fibre/formation model').replace('original seeded papergen.py fibre/floc model','seeded NumPy fibre/formation model in this script').replace("f'papergen.pulp(seed={seed}, rough={rough})'", "f'prepare_test_surfaces.pulp(seed={seed}, rough={rough})'")
p.write_text(s)
