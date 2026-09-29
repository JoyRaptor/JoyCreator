#!/usr/bin/env python3
"""glsl_model.py -- JB-2.20a's SECOND, independent transcription of the Studio's blend maths.

WHAT THIS IS FOR. R23: Joy Brush's pure-Kotlin core cannot call the Studio's Java, so the two
copies of 26 equations are tied together by a generated golden table. The table comes from ONE
source -- `BlendModes.blend`, the Studio's Java mirror of its own GLSL -- and `BlendParityTest`
checks the Kotlin against that. So a mistake made consistently in BOTH the Java and the Kotlin is
invisible to every Java-side check: nothing in that chain knows what the shader actually does.

This file closes that gap. It transcribes the OTHER artifact -- the `GLSL_BLEND_FN` string, the
equations a GPU runs -- in a third language, on a separate pass, and recomputes every row of the
committed table. If the Java mirror and the shader have drifted apart, or the mirror carries a
branch the shader does not, this exits non-zero.

It is deliberately NOT a transliteration of BlendModes.java. It is written from the GLSL text, so
`mix`, `step` and the operator order inside ClipColor are expanded as the shader writes them --
which is also where the two can genuinely disagree (see clip_color below).

Usage:  python3 tools/blend-golden/glsl_model.py [path/to/BlendGolden.kt]
Exits 0 when every row agrees, 1 when one does not, 2 when the table cannot be read.

Stdlib only. No network, no build tool, no device.
"""

import math
import re
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_TABLE = (
    ROOT / "joybrush" / "core" / "src" / "commonTest" / "kotlin" / "cc" / "joycreator"
    / "joybrush" / "core" / "blend" / "BlendGolden.kt"
)

# The same tolerance BlendParityTest uses. The model is not expected to be bit-identical to the
# Java -- a GLSL dot()'s summation order is implementation-defined on a GPU, and ClipColor's two
# halves associate differently in the two languages -- so a correct model differs in the last bits.
# A WRONG one differs by a whole branch, which is many orders of magnitude wider than this.
TOL_REL = 1e-6
TOL_ABS = 1e-6


def f32(x):
    """Round a Python double to the nearest IEEE single. Every operation here goes through it, so
    the model has single-precision semantics throughout, as GLSL highp float and a Java `float`
    both do."""
    return struct.unpack("<f", struct.pack("<f", x))[0]


# The Studio's 0.00001 floor on every blend-mode division, as the float32 the shader and the Java
# both hold. Rounded here for the same reason: an unrounded Python double would let a value that
# GLSL would treat as equal slip through a comparison.
EPS = f32(1e-5)

LW = (f32(0.3), f32(0.59), f32(0.11))


def add(a, b):
    return f32(f32(a) + f32(b))


def sub(a, b):
    return f32(f32(a) - f32(b))


def mul(a, b):
    return f32(f32(a) * f32(b))


def div(a, b):
    return f32(f32(a) / f32(b))


def fmin(a, b):
    return a if a < b else b


def fmax(a, b):
    return a if a > b else b


def fclamp(x, lo, hi):
    return fmin(fmax(x, lo), hi)


def dot3(w, c):
    """GLSL's dot(). Its summation ORDER is implementation-defined on a GPU; this models it left
    to right, which is what BlendModes.lum does on the JVM."""
    return add(add(mul(w[0], c[0]), mul(w[1], c[1])), mul(w[2], c[2]))


def min3(c):
    return fmin(fmin(c[0], c[1]), c[2])


def max3(c):
    return fmax(fmax(c[0], c[1]), c[2])


def clip_color(c):
    """The W3C ClipColor tail, as GLSL_BLEND_FN writes it inline.

    TWO THINGS WORTH READING, because both are places the languages can part company:

      * `cl` is measured ONCE, before either rescale. That is what the shader does, and it is why
        the rescale is about the original colour's luminosity rather than the halfway one.
      * the shader's association is `(c - cl) * cl / denom`, while BlendModes.clipColor computes
        the quotient first and multiplies. Those are the same number in real arithmetic and can
        differ by an ulp in float32 -- which is why the tolerance here is 1e-6 and not zero, and
        why it is a tolerance and not a bit comparison.
    """
    cl = dot3(LW, c)
    cn = min3(c)
    cx = max3(c)
    if cn < 0.0:
        den = fmax(sub(cl, cn), EPS)
        c = [add(cl, div(mul(sub(c[i], cl), cl), den)) for i in range(3)]
    if cx > 1.0:
        den = fmax(sub(cx, cl), EPS)
        c = [add(cl, div(mul(sub(c[i], cl), sub(1.0, cl)), den)) for i in range(3)]
    return [fclamp(c[i], 0.0, 1.0) for i in range(3)]


def hard_light_family(b, s, by_source):
    """OVERLAY (which tests the BACKDROP) and HARD_LIGHT (which tests the SOURCE): one expression,
    two modes. The parameter is the whole of the difference between them."""
    out = []
    for i in range(3):
        x, y = b[i], s[i]
        lo = mul(mul(2.0, x), y)
        hi = sub(1.0, mul(mul(2.0, sub(1.0, x)), sub(1.0, y)))
        pick_lo = (y < 0.5) if by_source else (x < 0.5)
        out.append(lo if pick_lo else hi)
    return out


def soft_light(b, s):
    out = []
    for i in range(3):
        bi, si = b[i], s[i]
        sb = f32(math.sqrt(fmax(bi, 0.0)))
        sp = mul(add(mul(sub(mul(16.0, bi), 12.0), bi), 4.0), bi)
        # mix(sb, sp, step(b, vec3(0.25))): step(edge=b, x=0.25) is 1 when b <= 0.25, so the
        # quartic is the half that wins there.
        dd = sp if bi <= 0.25 else sb
        slo = sub(bi, mul(mul(sub(1.0, mul(2.0, si)), bi), sub(1.0, bi)))
        shi = add(bi, mul(sub(mul(2.0, si), 1.0), sub(dd, bi)))
        # mix(shi, slo, step(s, vec3(0.5))): at or below the halfway source, the `slo` half.
        out.append(slo if si <= 0.5 else shi)
    return out


def vivid_light(b, s):
    out = []
    for i in range(3):
        bi, si = b[i], s[i]
        burn = sub(1.0, fmin(div(sub(1.0, bi), fmax(mul(2.0, si), EPS)), 1.0))
        dodge = fmin(div(bi, fmax(sub(2.0, mul(2.0, si)), EPS)), 1.0)
        # mix(vd, vb, step(s, vec3(0.5))): at or below the halfway source, the burn half.
        out.append(burn if si <= 0.5 else dodge)
    return out


def hue_sat_lum(mode, b, s):
    """The band the shader keeps for all three non-separable colour modes, because they all end in
    the same SetLum + ClipColor tail that COLOR already needed:
        HUE        = SetLum(SetSat(source,   Sat(backdrop)), Lum(backdrop))
        SATURATION = SetLum(SetSat(backdrop, Sat(source)),   Lum(backdrop))
        LUMINOSITY = SetLum(backdrop,                          Lum(source))
    """
    nc = list(b)
    target_lum = dot3(LW, s)
    if mode < 24.5:
        hue_from, sat_from = (s, b) if mode < 23.5 else (b, s)
        sat = sub(max3(sat_from), min3(sat_from))
        lo = min3(hue_from)
        hi = max3(hue_from)
        if hi > lo:
            nc = [div(mul(sub(hue_from[i], lo), sat), sub(hi, lo)) for i in range(3)]
        else:
            nc = [0.0, 0.0, 0.0]
        target_lum = dot3(LW, b)
    shift = sub(target_lum, dot3(LW, nc))
    nc = [add(nc[i], shift) for i in range(3)]
    return clip_color(nc)


def blend(code, b, s):
    """GLSL_BLEND_FN, band by band, for one mode code."""
    def per(f):
        return [f(i) for i in range(3)]

    if code == 0:
        return list(s)
    if code == 1:
        return per(lambda i: mul(b[i], s[i]))
    if code == 2:
        return per(lambda i: sub(1.0, mul(sub(1.0, b[i]), sub(1.0, s[i]))))
    if code == 3:
        return hard_light_family(b, s, by_source=False)
    if code == 4:
        return per(lambda i: fmin(add(b[i], s[i]), 1.0))
    if code == 5:
        return per(lambda i: f32(abs(sub(b[i], s[i]))))
    if code == 6:
        d = sub(dot3(LW, b), dot3(LW, s))
        return clip_color([add(s[i], d) for i in range(3)])
    if code == 7:
        return per(lambda i: fmin(b[i], s[i]))
    if code == 8:
        return per(lambda i: fmax(b[i], s[i]))
    if code == 9:
        return per(lambda i: fmin(div(b[i], fmax(sub(1.0, s[i]), EPS)), 1.0))
    if code == 10:
        return per(lambda i: sub(1.0, fmin(div(sub(1.0, b[i]), fmax(s[i], EPS)), 1.0)))
    if code == 11:
        return per(lambda i: fmax(sub(add(b[i], s[i]), 1.0), 0.0))
    if code == 12:
        return hard_light_family(b, s, by_source=True)
    if code == 13:
        return soft_light(b, s)
    if code == 14:
        return vivid_light(b, s)
    if code == 15:
        return per(lambda i: fclamp(sub(add(b[i], mul(2.0, s[i])), 1.0), 0.0, 1.0))
    if code == 16:
        return per(
            lambda i: fmin(b[i], mul(2.0, s[i])) if s[i] <= 0.5 else fmax(b[i], sub(mul(2.0, s[i]), 1.0))
        )
    if code == 17:
        return per(lambda i: 1.0 if add(b[i], s[i]) >= 1.0 else 0.0)
    if code == 18:
        return per(lambda i: sub(add(b[i], s[i]), mul(mul(2.0, b[i]), s[i])))
    if code == 19:
        return per(lambda i: fmax(sub(b[i], s[i]), 0.0))
    if code == 20:
        return per(lambda i: fmin(div(b[i], fmax(s[i], EPS)), 1.0))
    if code == 21:
        return list(s) if dot3(LW, s) < dot3(LW, b) else list(b)
    if code == 22:
        return list(s) if dot3(LW, s) > dot3(LW, b) else list(b)
    if code in (23, 24, 25):
        return hue_sat_lum(code, b, s)
    return list(s)


# ── reading the generated Kotlin table ──────────────────────────────────────────

FLOAT = r"-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?"


def floats_in(block):
    # The lookahead stops the match BEFORE the `f` suffix, so the captured text is the number.
    return [f32(float(t)) for t in re.findall(r"(?:" + FLOAT + r")(?=f)", block)]


def read_table(path):
    text = path.read_text(encoding="utf-8")

    names = {name: int(code) for name, code in re.findall(r'"([A-Z][A-Z_]*)",\s*//\s*(\d+)', text)}
    if not names:
        raise ValueError("no NAMES block found")

    m = re.search(r"val BS: FloatArray = floatArrayOf\((.*?)\n    \)", text, re.S)
    if not m:
        raise ValueError("no BS array found")
    bs = floats_in(m.group(1))

    parts = re.findall(
        r"private fun part(\d+)\(\): FloatArray = floatArrayOf\((.*?)\n    \)", text, re.S
    )
    if not parts:
        raise ValueError("no EXPECTED parts found")
    expected = []
    for _, block in sorted(parts, key=lambda p: int(p[0])):
        expected.extend(floats_in(block))

    def const(name):
        found = re.search(r"const val " + name + r": Int = (\d+)", text)
        if not found:
            raise ValueError("no constant " + name)
        return int(found.group(1))

    return names, bs, expected, const("MODE_COUNT"), const("PAIR_COUNT")


def main(argv):
    path = Path(argv[1]) if len(argv) > 1 else DEFAULT_TABLE
    if not path.exists():
        print("glsl_model: {} does not exist".format(path), file=sys.stderr)
        return 2

    try:
        names, bs, expected, mode_count, pair_count = read_table(path)
    except ValueError as exc:
        print("glsl_model: cannot read the table: {}".format(exc), file=sys.stderr)
        return 2

    if len(bs) != pair_count * 6:
        print(
            "glsl_model: BS has {} floats, {} pairs x 6".format(len(bs), pair_count), file=sys.stderr
        )
        return 2
    if len(expected) != pair_count * mode_count * 3:
        print(
            "glsl_model: EXPECTED has {} floats, {} pairs x {} modes x 3".format(
                len(expected), pair_count, mode_count
            ),
            file=sys.stderr,
        )
        return 2

    by_code = {code: name for name, code in names.items()}
    worst = 0.0
    worst_where = ""
    checked = 0
    exact = 0

    for pair in range(pair_count):
        b = bs[pair * 6:pair * 6 + 3]
        s = bs[pair * 6 + 3:pair * 6 + 6]
        for code in range(mode_count):
            mine = blend(code, b, s)
            base = (pair * mode_count + code) * 3
            for ch in range(3):
                theirs = expected[base + ch]
                d = abs(mine[ch] - theirs)
                checked += 1
                if mine[ch] == theirs:
                    exact += 1
                tol = TOL_ABS + TOL_REL * abs(theirs)
                if d > tol:
                    print(
                        "glsl_model: {} disagrees with the table.\n"
                        "  backdrop {}\n  source   {}\n  channel  {}\n"
                        "  Java     {}\n  GLSL     {}\n  off by   {} (allowed {})\n"
                        "  pair {}, mode {} -- BlendModes.java's Java mirror and its GLSL have "
                        "drifted apart".format(
                            by_code.get(code, code), b, s, ch, theirs, mine[ch], d, tol, pair, code
                        ),
                        file=sys.stderr,
                    )
                    return 1
                if d > worst:
                    worst = d
                    worst_where = "{} pair {} channel {}".format(by_code.get(code, code), pair, ch)

    print("glsl_model: all {} rows agree with an independent transcription of GLSL_BLEND_FN".format(checked))
    print("  {} of them bit-for-bit identical ({:.2f}%)".format(exact, 100.0 * exact / checked))
    print("  largest difference {} at {}".format(worst, worst_where or "n/a"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
