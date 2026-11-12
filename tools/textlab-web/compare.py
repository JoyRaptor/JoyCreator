"""TextLab: compare the phone's still with the look book's still for the same look, words, size and moment.

    python -I tools/textlab-web/compare.py <look book png> <phone png> [<diff png out>]

Prints the sizes, the worst per-channel difference (0..255), the mean, and how many pixels differ by
more than 8, 16 and 32. The diff picture shows each pixel's worst channel difference, amplified 4x.
"""
import sys

import numpy as np
from PIL import Image


def main():
    ref = np.asarray(Image.open(sys.argv[1]).convert('RGB')).astype(np.int16)
    got = np.asarray(Image.open(sys.argv[2]).convert('RGB')).astype(np.int16)
    print('look book %dx%d, phone %dx%d' % (ref.shape[1], ref.shape[0], got.shape[1], got.shape[0]))
    h, w = min(ref.shape[0], got.shape[0]), min(ref.shape[1], got.shape[1])
    if ref.shape != got.shape:
        print('SIZES DIFFER: comparing the top-left %dx%d' % (w, h))
    d = np.abs(ref[:h, :w] - got[:h, :w])
    worst = d.max(axis=2)
    n = worst.size
    print('worst %d, mean %.2f, >8: %.2f%%, >16: %.2f%%, >32: %.2f%%' % (
        worst.max(), d.mean(), 100.0 * (worst > 8).sum() / n, 100.0 * (worst > 16).sum() / n,
        100.0 * (worst > 32).sum() / n))
    if len(sys.argv) > 3:
        Image.fromarray(np.clip(worst * 4, 0, 255).astype(np.uint8)).save(sys.argv[3])


if __name__ == '__main__':
    main()
