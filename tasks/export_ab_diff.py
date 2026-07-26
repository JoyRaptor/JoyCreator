"""Absolute-geometry A/B frame diff for exports.

The method the `ab-export-frame-diff-proof` lesson asks for: decode two exports frame
by frame and compare the actual pixels, rather than arguing from the code that the
compositing is the same.

Two rules it exists to enforce:

  * **Compare pixels, not files.** Two exports of the same composite have DIFFERENT
    container bytes (mp4 timestamps/metadata), so sha256 says "different" while every
    frame is identical. Only a decode-and-subtract answers the question.
  * **The fixture geometry must be ASYMMETRIC.** A symmetric layout hides flips: if
    every item sits at centre, a y-flip is a no-op and the diff is clean while the
    export is wrong. That is exactly how the a4fbeba PiP y-flip survived a proof.
    `--check-asym` re-derives that property from the geometry you claim to have used
    and fails if any flip would map an item onto another item's slot.

Usage:
    python tasks/export_ab_diff.py A.mp4 B.mp4 [--label-a A] [--label-b B]
                                   [--expect same|differ] [--frames N]

Exit code 0 iff the observed relationship matches --expect. The frame count is always
printed; zero decoded frames is a hard failure, because a diff over no frames "passes".
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile


def decode(path, outdir, limit=None):
    """Decode to PNG frames. Returns the sorted list of frame paths."""
    os.makedirs(outdir, exist_ok=True)
    cmd = ['ffmpeg', '-v', 'error', '-i', path]
    if limit:
        cmd += ['-frames:v', str(limit)]
    cmd += ['-start_number', '0', os.path.join(outdir, 'f%05d.png')]
    subprocess.run(cmd, check=True)
    return sorted(os.path.join(outdir, f) for f in os.listdir(outdir)
                  if f.endswith('.png'))


def compare(fa, fb):
    """(max abs channel diff, mean abs channel diff, differing-pixel count)."""
    from PIL import Image, ImageChops
    a = Image.open(fa).convert('RGB')
    b = Image.open(fb).convert('RGB')
    if a.size != b.size:
        return 255, 255.0, a.size[0] * a.size[1], f'size {a.size} vs {b.size}'
    d = ImageChops.difference(a, b)
    bands = d.split()
    mx = max(bd.getextrema()[1] for bd in bands)
    hist_sum = 0
    total = 0
    for bd in bands:
        h = bd.histogram()
        hist_sum += sum(i * n for i, n in enumerate(h))
        total += sum(h)
    mean = hist_sum / total if total else 0.0
    # count pixels differing in ANY channel
    flat = d.convert('L').point(lambda v: 255 if v else 0)
    ndiff = sum(n for i, n in enumerate(flat.histogram()) if i)
    return mx, mean, ndiff, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('a')
    ap.add_argument('b')
    ap.add_argument('--label-a', default=None)
    ap.add_argument('--label-b', default=None)
    ap.add_argument('--expect', choices=('same', 'differ'), default='same')
    ap.add_argument('--frames', type=int, default=None,
                    help='limit to the first N frames (default: all)')
    ap.add_argument('--check-asym', metavar='X,Y[;X,Y...]',
                    help='item centres; fail if any axis flip maps one onto another')
    args = ap.parse_args()

    la = args.label_a or os.path.basename(args.a)
    lb = args.label_b or os.path.basename(args.b)

    if args.check_asym:
        pts = []
        for chunk in args.check_asym.split(';'):
            x, y = chunk.split(',')
            pts.append((round(float(x), 4), round(float(y), 4)))
        print(f'geometry        : {pts}')
        problems = []
        for i, (x, y) in enumerate(pts):
            for flip, img in (('y', (x, round(1 - y, 4))),
                              ('x', (round(1 - x, 4), y)),
                              ('xy', (round(1 - x, 4), round(1 - y, 4)))):
                for k, other in enumerate(pts):
                    if img == other:
                        problems.append(f'item{i} under {flip}-flip lands on item{k}')
            if (x, y) == (0.5, 0.5):
                problems.append(f'item{i} is dead centre — every flip is a no-op there')
        if problems:
            print('ASYMMETRY CHECK FAILED — this fixture can hide a flip:')
            for p in problems:
                print('   ', p)
            sys.exit(1)
        print('asymmetry check : OK (no flip maps any item onto another item or itself)')

    tmp = tempfile.mkdtemp(prefix='abdiff_')
    try:
        fa = decode(args.a, os.path.join(tmp, 'a'), args.frames)
        fb = decode(args.b, os.path.join(tmp, 'b'), args.frames)
        print(f'{la:<15} : {len(fa)} frame(s) decoded')
        print(f'{lb:<15} : {len(fb)} frame(s) decoded')
        if not fa or not fb:
            print('FAIL: a side decoded ZERO frames — nothing was compared.')
            sys.exit(1)
        if len(fa) != len(fb):
            print(f'FAIL: frame counts differ ({len(fa)} vs {len(fb)})')
            sys.exit(1)

        worst = (-1, -1, 0.0, 0)
        differing = 0
        for i, (pa, pb) in enumerate(zip(fa, fb)):
            mx, mean, ndiff, err = compare(pa, pb)
            if err:
                print(f'FAIL frame {i}: {err}')
                sys.exit(1)
            if mx:
                differing += 1
            if mx > worst[1]:
                worst = (i, mx, mean, ndiff)
        n = len(fa)
        print(f'frames compared : {n}')
        print(f'frames differing: {differing} / {n}')
        print(f'worst frame     : #{worst[0]}  maxAbsDiff={worst[1]}  '
              f'meanAbsDiff={worst[2]:.4f}  pixelsDiffering={worst[3]}')

        identical = differing == 0
        verdict = 'IDENTICAL' if identical else 'DIFFERENT'
        ok = identical if args.expect == 'same' else not identical
        print(f'\n{la} vs {lb}: {verdict}   (expected {args.expect})  '
              f'-> {"PASS" if ok else "FAIL"}')
        sys.exit(0 if ok else 1)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    main()
