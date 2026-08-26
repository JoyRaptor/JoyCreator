#!/usr/bin/env python3
"""
Preview/export frame parity.

WHY THIS EXISTS. preview_parity_lint.py checks that a property NAME appears on both the
export side and the preview side. Every defect found the hard way in the week of
2026-08-19 passed that check while being plainly visible on the device:

  * crop was in both files and rendered in neither preview path
  * masks were wired but invisible unless a blend mode dragged the image into GL
  * lane z was the exact reverse of the timeline for any default project
  * track matte is fully built in export and absent from the preview renderer

A name-matching check cannot see any of that. Only pixels can. This compares the frame the
EXPORT produces at time T against the frame the PREVIEW shows at time T, and fails when
they disagree by more than a stated tolerance.

  extract   pull frame at T ms out of an exported file        (ffmpeg)
  capture   grab the preview area off the device              (adb + uiautomator bounds)
  compare   score two PNGs, write a diff image, exit non-zero on failure
  selftest  NEGATIVE CONTROL: prove the comparison can actually fail

ON TOLERANCE. Bit-identity is not achievable and demanding it would make this cry wolf
forever: the two paths run at different resolutions, resample differently, and the export
goes through a video codec. So both images are reduced to a common small size before
comparison, which is also the honest question -- "does this look like the same picture"
rather than "are these the same bytes". Defaults are deliberately strict; loosen them with
evidence and record why, in this file, next to the number.
"""

import argparse
import os
import subprocess
import sys

import numpy as np
from PIL import Image

# Both frames are reduced to this width before comparison. Small enough that resampling
# and codec noise wash out, large enough that a wrong crop, a missing mask or an inverted
# z-order cannot hide -- every defect listed in the docstring moves whole regions, not
# single pixels.
COMPARE_WIDTH = 480

# Per-channel 0..255. MEAN catches "the whole picture is wrong" (bad crop, missing layer).
# P95 catches "a big region is wrong" while tolerating edge/resampling differences.
DEFAULT_MEAN_TOL = 3.0
DEFAULT_P95_TOL = 12.0


def _load(path):
    img = Image.open(path).convert("RGB")
    w = COMPARE_WIDTH
    h = max(1, round(img.height * (w / img.width)))
    return np.asarray(img.resize((w, h), Image.BILINEAR), dtype=np.float32)


def compare(path_a, path_b, mean_tol, p95_tol, diff_out=None):
    """Return (ok, stats). Letterboxing is NOT trimmed: a preview that letterboxes
    differently from the export is itself a real parity defect and must not be hidden."""
    a, b = _load(path_a), _load(path_b)
    if a.shape != b.shape:
        h = min(a.shape[0], b.shape[0])
        a, b = a[:h], b[:h]
    d = np.abs(a - b)
    stats = {
        "mean": float(d.mean()),
        "p95": float(np.percentile(d, 95)),
        "max": float(d.max()),
        "shape": f"{a.shape[1]}x{a.shape[0]}",
    }
    if diff_out:
        amp = np.clip(d * 4.0, 0, 255).astype(np.uint8)   # x4 so a subtle miss is visible
        Image.fromarray(amp).save(diff_out)
    ok = stats["mean"] <= mean_tol and stats["p95"] <= p95_tol
    return ok, stats


def extract(video, ms, out):
    """One frame out of an exported file, at an exact timestamp."""
    cmd = ["ffmpeg", "-v", "error", "-y", "-ss", f"{ms / 1000.0:.3f}",
           "-i", video, "-frames:v", "1", out]
    subprocess.run(cmd, check=True)
    return out


def capture(serial, out, bounds=None):
    """Screenshot the device and crop to the preview area.

    bounds is 'l,t,r,b' in device pixels. Get it from a uiautomator dump of
    player_container -- hard-coding a rect makes this silently wrong on another screen."""
    png = subprocess.run(
        ["adb", "-s", serial, "exec-out", "screencap", "-p"],
        check=True, stdout=subprocess.PIPE).stdout
    tmp = out + ".full.png"
    with open(tmp, "wb") as f:
        f.write(png)
    img = Image.open(tmp)
    if bounds:
        l, t, r, b = (int(x) for x in bounds.split(","))
        img = img.crop((l, t, r, b))
    img.save(out)
    os.remove(tmp)
    return out


def selftest():
    """NEGATIVE CONTROL. A check that cannot fail proves nothing, and this repo has
    already shipped one that could not (preview_parity_lint passed while crop rendered
    nowhere). Build a picture, damage it the way a real defect would, and assert the
    comparison REJECTS it -- then assert it ACCEPTS a merely-noisy copy."""
    rng = np.random.default_rng(7)
    base = np.zeros((270, 480, 3), dtype=np.uint8)
    base[60:210, 100:380] = (200, 60, 40)          # a bright region, like a video frame
    base[90:150, 150:250] = (40, 180, 220)         # an overlay sitting on it

    ok_all = True
    d = "tools/jvm-harness/out-frameparity"
    os.makedirs(d, exist_ok=True)
    p_base = os.path.join(d, "selftest_base.png")
    Image.fromarray(base).save(p_base)

    # 1. Noise only: must PASS. This is codec + resampling, not a defect.
    noisy = np.clip(base.astype(np.int16) + rng.integers(-2, 3, base.shape), 0, 255)
    p_noisy = os.path.join(d, "selftest_noise.png")
    Image.fromarray(noisy.astype(np.uint8)).save(p_noisy)
    ok, st = compare(p_base, p_noisy, DEFAULT_MEAN_TOL, DEFAULT_P95_TOL)
    print(f"  noise-only            mean={st['mean']:.2f} p95={st['p95']:.2f} -> "
          f"{'PASS' if ok else 'FAIL'}")
    if not ok:
        print("  !! tolerance is too tight: ordinary noise is being called a defect")
        ok_all = False

    # 2. The overlay is MISSING: must FAIL. This is the mask-invisible defect.
    missing = base.copy()
    missing[90:150, 150:250] = (200, 60, 40)
    p_missing = os.path.join(d, "selftest_missing_overlay.png")
    Image.fromarray(missing).save(p_missing)
    ok, st = compare(p_base, p_missing, DEFAULT_MEAN_TOL, DEFAULT_P95_TOL,
                     os.path.join(d, "selftest_missing_overlay.diff.png"))
    print(f"  overlay missing       mean={st['mean']:.2f} p95={st['p95']:.2f} -> "
          f"{'FAIL (correct)' if not ok else 'PASS (WRONG)'}")
    if ok:
        print("  !! the check cannot detect a missing overlay -- it proves nothing")
        ok_all = False

    # 3. Wrong CROP: must FAIL. The whole picture shifts and rescales.
    shifted = np.roll(base, 40, axis=1)
    p_shift = os.path.join(d, "selftest_wrong_crop.png")
    Image.fromarray(shifted).save(p_shift)
    ok, st = compare(p_base, p_shift, DEFAULT_MEAN_TOL, DEFAULT_P95_TOL,
                     os.path.join(d, "selftest_wrong_crop.diff.png"))
    print(f"  wrong crop            mean={st['mean']:.2f} p95={st['p95']:.2f} -> "
          f"{'FAIL (correct)' if not ok else 'PASS (WRONG)'}")
    if ok:
        print("  !! the check cannot detect a wrong crop -- it proves nothing")
        ok_all = False

    # 4. Z-ORDER INVERTED: the two layers swap. Must FAIL. This is 78032689.
    swapped = base.copy()
    swapped[90:150, 150:250] = (200, 60, 40)
    swapped[60:90, 100:380] = (40, 180, 220)
    p_z = os.path.join(d, "selftest_z_inverted.png")
    Image.fromarray(swapped).save(p_z)
    ok, st = compare(p_base, p_z, DEFAULT_MEAN_TOL, DEFAULT_P95_TOL,
                     os.path.join(d, "selftest_z_inverted.diff.png"))
    print(f"  z-order inverted      mean={st['mean']:.2f} p95={st['p95']:.2f} -> "
          f"{'FAIL (correct)' if not ok else 'PASS (WRONG)'}")
    if ok:
        print("  !! the check cannot detect inverted z -- it proves nothing")
        ok_all = False

    return ok_all


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    e = sub.add_parser("extract"); e.add_argument("video"); e.add_argument("ms", type=int)
    e.add_argument("out")

    c = sub.add_parser("capture"); c.add_argument("serial"); c.add_argument("out")
    c.add_argument("--bounds", default=None, help="l,t,r,b in device px")

    k = sub.add_parser("compare"); k.add_argument("a"); k.add_argument("b")
    k.add_argument("--diff", default=None)
    k.add_argument("--mean-tol", type=float, default=DEFAULT_MEAN_TOL)
    k.add_argument("--p95-tol", type=float, default=DEFAULT_P95_TOL)

    sub.add_parser("selftest")
    a = ap.parse_args()

    if a.cmd == "extract":
        print(extract(a.video, a.ms, a.out)); return 0
    if a.cmd == "capture":
        print(capture(a.serial, a.out, a.bounds)); return 0
    if a.cmd == "compare":
        ok, st = compare(a.a, a.b, a.mean_tol, a.p95_tol, a.diff)
        print(f"mean={st['mean']:.2f} p95={st['p95']:.2f} max={st['max']:.0f} "
              f"({st['shape']}) tol mean<={a.mean_tol} p95<={a.p95_tol}")
        print("FRAME PARITY" if ok else "FRAME MISMATCH")
        return 0 if ok else 1
    if a.cmd == "selftest":
        print("=== frame parity: negative controls ===")
        ok = selftest()
        print("SELFTEST PASS  the check rejects real defects and tolerates noise"
              if ok else "SELFTEST FAIL  this check cannot be trusted")
        return 0 if ok else 1
    return 2


if __name__ == "__main__":
    sys.exit(main())
