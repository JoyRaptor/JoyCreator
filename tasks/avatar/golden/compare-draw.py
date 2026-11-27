"""Compare the phone's pictures of a character (CharacterDrawBenchActivity, X3) with the web pilot's (AL.GL) of the same file and pose.

    python tasks/avatar/golden/compare-draw.py <phone dir> <pilot dir>

Both dirs hold <who>-<pose>.png (the pilot's as pilot-<who>-<pose>.png). For each pair it prints how many pixels differ by more
than 2, 8 and 32 levels (out of 255, worst of R, G, B, A, colours un-premultiplied as PNG keeps them) and the largest difference.
The pilot's pictures of the built-in Topps and Pip (five poses each) are in app/src/androidTest/assets/avatar-golden/ (named
<who>-<pose>.png); the cloud emulator test AvatarDrawParityTest checks against them on every run. Other characters' pictures
(the owner's own art) stay out of the repository, in tools/avatarlab/local/.
"""
import sys
from pathlib import Path
from PIL import Image, ImageChops

phone, pilot = Path(sys.argv[1]), Path(sys.argv[2])
rows = []
for p in sorted(phone.glob('*.png')):
    q = pilot / ('pilot-' + p.name)
    if not q.exists():
        rows.append((p.stem, 'no pilot picture'))
        continue
    a, b = Image.open(p).convert('RGBA'), Image.open(q).convert('RGBA')
    if a.size != b.size:
        rows.append((p.stem, f'sizes differ {a.size} vs {b.size}'))
        continue
    d = ImageChops.difference(a, b)
    bands = d.split()
    worst = Image.new('L', a.size)
    for band in bands:
        worst = ImageChops.lighter(worst, band)
    hist = worst.histogram()
    n = a.size[0] * a.size[1]
    over = lambda t: sum(hist[t + 1:])
    drawn = sum(1 for px in b.getdata() if px[3] > 8)
    rows.append((p.stem, f'{over(2)} px over 2, {over(8)} over 8, {over(32)} over 32 (of {drawn} drawn); largest {max(i for i, c in enumerate(hist) if c)}'))
for name, s in rows:
    print(f'{name:14s} {s}')
