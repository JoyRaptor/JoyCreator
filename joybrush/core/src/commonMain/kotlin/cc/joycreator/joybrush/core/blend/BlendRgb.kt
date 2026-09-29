package cc.joycreator.joybrush.core.blend

import cc.joycreator.joybrush.core.doc.BlendMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The Studio's TWENTY-SIX blend modes, as one pure-Kotlin RGB function.
 *
 * WHY A SECOND IMPLEMENTATION AT ALL. Lead ruling R23: Joy Brush's core is pure Kotlin (the iOS
 * door), so it cannot call the Studio's Java. The two copies of the same equations are therefore
 * tied together by a GENERATED golden table (`BlendGolden`, written by
 * `tools/blend-golden/GenBlendGolden.java` running the Studio's own `BlendModes.blend`) plus a
 * drift check (`tools/gen_blend_golden.sh --check`) — never by anybody comparing two blocks of text
 * and deciding they look the same. `BlendParityTest` is the half of that which runs in CI, and
 * `BlendRgbIdentityTest` checks the equations themselves rather than the table.
 *
 * WHAT THE PARITY PROOF DOES **NOT** COVER, added 2026-09-29 after an adversarial review found the
 * KDoc claiming more than was true. `--model` is a third transcription (Python, from the GLSL text)
 * and it genuinely does catch a typo in this file, because it never looks at this file or at the
 * Java. But it is **not** a Java-versus-GLSL check: it reads only the generated table, so a change
 * to the GLSL the GPU actually runs would pass every check in the repo. So the honest summary of the
 * evidence is: *the Kotlin agrees with the Java, and the Kotlin agrees with an independent
 * transcription of the GLSL* — three implementations, none of which compares the Java to the GLSL
 * directly. Closing that last gap is a Lead question; see the spec's Questions.
 *
 * THE CONTRACT, which is the Studio's contract verbatim and not a convenient one:
 *
 *  - [b] is the STRAIGHT (un-premultiplied) backdrop and [s] the STRAIGHT source, each 3 floats.
 *  - The result goes to [out], 3 floats, and is UNCLAMPED. `BlendModes.blend`'s javadoc says so
 *    ("@return the blended rgb, UNCLAMPED — exactly like the GLSL, whose callers clamp"), and the
 *    caller clamps. Clamping here instead would be a different function that happens to agree on
 *    0..1 input: SCREEN, EXCLUSION and OVERLAY all legitimately return values above 1 when either
 *    input is, and clipping in the blend term rather than in the composite is how an export ends up
 *    not doing what the Studio does. The golden table's out-of-range rows are what pin this.
 *  - Separable modes are per channel. HUE, SATURATION, COLOR, LUMINOSITY and DARKER/LIGHTER_COLOR
 *    are WHOLE-COLOUR and go through the W3C SetSat / SetLum / ClipColor arithmetic exactly as the
 *    Studio's GLSL does it inline.
 *  - A code outside 0..25 yields [s], which is the Studio's deliberate "an unreadable blend mode
 *    must cost a project its blend, never its export". Joy Brush's refusal of an unknown mode
 *    happens at a different door — `DocJson.decode` throws on a token that is not a [BlendMode]
 *    constant at all — which is why [studioCodeOf] throws for an unknown NAME rather than quietly
 *    falling back to NORMAL.
 *
 * SCRATCH, not allocation. The whole-colour modes need a temporary pixel. [scratch] is the
 * caller's, for the same reason `render.Blend.apply` refuses to write into its own arguments: a
 * shared buffer here would be a data race the first time two engines blend on two threads, and a
 * heap allocation per pixel is a cost the export path cannot afford. Nothing below allocates.
 */
object BlendRgb {

    /**
     * The Studio's `0.00001`, the epsilon every one of its divisions is floored at, so a division
     * by zero produces a large finite number the mode then clamps rather than an infinity or a NaN
     * that would poison a whole scanline.
     */
    private const val EPS = 0.00001f

    /**
     * The Studio's modes, in ITS code order. A transcription of `BlendModes.ALL`, and checked
     * against the generated table (`BlendGolden.NAMES`) by a test — which is what catches a Studio
     * mode nobody carried over.
     */
    val STUDIO_MODES: List<String> = listOf(
        "NORMAL", "MULTIPLY", "SCREEN", "OVERLAY", "ADD", "DIFFERENCE", "COLOR",
        "DARKEN", "LIGHTEN", "COLOR_DODGE", "COLOR_BURN", "LINEAR_BURN",
        "HARD_LIGHT", "SOFT_LIGHT", "VIVID_LIGHT", "LINEAR_LIGHT", "PIN_LIGHT", "HARD_MIX",
        "EXCLUSION", "SUBTRACT", "DIVIDE", "DARKER_COLOR", "LIGHTER_COLOR",
        "HUE", "SATURATION", "LUMINOSITY",
    )

    /**
     * A Studio mode NAME to its shader CODE — the whole of `BlendModes.modeCode`, written out by
     * NAME so a rename is a compile error here, and so a grep for the Studio's switch shows the
     * correspondence without anyone having to hold both files in their head.
     *
     * Keyed by NAME rather than by [BlendMode] so that appending the remaining modes to the enum
     * (JB-2.20a's document half) needs no edit here at all.
     *
     * Throws for a name the Studio has no mode for — today that is `ERASE_BELOW`, which is Joy
     * Brush's own destination-out erase and is NOT a blend term: `render.Blend.apply` handles it
     * before asking for one, and its alpha rule (`a = da(1 - sa)`) is why it could not be one.
     */
    fun studioCodeOf(name: String): Int = when (name) {
        "NORMAL" -> 0
        "MULTIPLY" -> 1
        "SCREEN" -> 2
        "OVERLAY" -> 3
        "ADD" -> 4
        "DIFFERENCE" -> 5
        "COLOR" -> 6
        "DARKEN" -> 7
        "LIGHTEN" -> 8
        "COLOR_DODGE" -> 9
        "COLOR_BURN" -> 10
        "LINEAR_BURN" -> 11
        "HARD_LIGHT" -> 12
        "SOFT_LIGHT" -> 13
        "VIVID_LIGHT" -> 14
        "LINEAR_LIGHT" -> 15
        "PIN_LIGHT" -> 16
        "HARD_MIX" -> 17
        "EXCLUSION" -> 18
        "SUBTRACT" -> 19
        "DIVIDE" -> 20
        "DARKER_COLOR" -> 21
        "LIGHTER_COLOR" -> 22
        "HUE" -> 23
        "SATURATION" -> 24
        "LUMINOSITY" -> 25
        else -> throw IllegalArgumentException(
            "'$name' is not one of the Studio's blend modes (${STUDIO_MODES.size} of them). " +
                "ERASE_BELOW is Joy Brush's own destination-out erase and has no blend term.",
        )
    }

    /** The mode a [BlendMode] composites as. Throws for [BlendMode.ERASE_BELOW] — see [studioCodeOf]. */
    fun codeOf(mode: BlendMode): Int = studioCodeOf(mode.name)

    // ── the doors ─────────────────────────────────────────────────────────────────

    /** Allocate and return. Convenience for callers that are not on a per-pixel path. */
    fun blendRgb(mode: BlendMode, b: FloatArray, s: FloatArray): FloatArray {
        val out = FloatArray(3)
        blendRgb(codeOf(mode), b, s, out, FloatArray(3))
        return out
    }

    fun blendRgb(code: Int, b: FloatArray, s: FloatArray): FloatArray {
        val out = FloatArray(3)
        blendRgb(code, b, s, out, FloatArray(3))
        return out
    }

    /**
     * The hot path. [out] and [scratch] are 3 floats each and must not be [b] or [s].
     *
     * A NOTE ON PROOF, because the whole task rests on it: the arithmetic below is a transcription
     * of the Studio's Java, and a transcription is exactly the thing that looks right and is wrong.
     * So it is not taken on trust — `BlendParityTest` runs it against every row of a table the
     * Studio itself produced, and `BlendRgbIdentityTest` pins the properties the equations are
     * supposed to have, which is a check that would survive a wrong table.
     */
    fun blendRgb(mode: BlendMode, b: FloatArray, s: FloatArray, out: FloatArray, scratch: FloatArray) {
        blendRgb(codeOf(mode), b, s, out, scratch)
    }

    fun blendRgb(code: Int, b: FloatArray, s: FloatArray, out: FloatArray, scratch: FloatArray) {
        require(b.size >= 3 && s.size >= 3 && out.size >= 3 && scratch.size >= 3) {
            "a blend needs 3 channels and a 3-float scratch, got ${b.size}/${s.size}/${out.size}/${scratch.size}"
        }
        require(out !== b && out !== s && scratch !== b && scratch !== s) {
            "out and scratch must be the caller's own arrays: this function writes through both"
        }
        val m = code.toFloat()

        // Every separable mode below is one expression over (b[i], s[i]), written as a plain loop
        // rather than through a shared lambda: this runs once per pixel per layer, so it allocates
        // nothing, and each mode reads on its own the way a reviewer checking a transcription
        // against BlendModes.java needs it to.

        // NORMAL: the source, unchanged. The caller's mix-by-alpha is already source-over.
        if (m < 0.5f) return write(s, out)

        // MULTIPLY
        if (m < 1.5f) {
            for (i in 0..2) out[i] = b[i] * s[i]
            return
        }

        // SCREEN
        if (m < 2.5f) {
            for (i in 0..2) out[i] = 1f - (1f - b[i]) * (1f - s[i])
            return
        }

        // OVERLAY — see hardLightFamily for the expression and the one thing that differs.
        if (m < 3.5f) return hardLightFamily(b, s, out, bySource = false)

        // ADD — the Studio's "Linear Dodge (Add)".
        if (m < 4.5f) {
            for (i in 0..2) out[i] = min(b[i] + s[i], 1f)
            return
        }

        // DIFFERENCE
        if (m < 5.5f) {
            for (i in 0..2) out[i] = abs(b[i] - s[i])
            return
        }

        // COLOR: the source's hue and saturation on the backdrop's luminosity. The delta is the same
        // number for all three channels, so it is computed once rather than three times.
        if (m < 6.5f) {
            val delta = lum(b) - lum(s)
            for (i in 0..2) scratch[i] = s[i] + delta
            clipColorInPlace(scratch)
            return write(scratch, out)
        }

        // DARKEN
        if (m < 7.5f) {
            for (i in 0..2) out[i] = min(b[i], s[i])
            return
        }

        // LIGHTEN
        if (m < 8.5f) {
            for (i in 0..2) out[i] = max(b[i], s[i])
            return
        }

        // COLOR DODGE — the W3C's three cases (b == 0 -> 0; s == 1 -> 1; else min(1, b/(1-s)))
        // collapse into this one expression: b == 0 makes the numerator 0 whatever the epsilon does,
        // and s == 1 makes the quotient enormous so the min pins it at 1.
        if (m < 9.5f) {
            for (i in 0..2) out[i] = min(b[i] / max(1f - s[i], EPS), 1f)
            return
        }

        // COLOR BURN — the same collapse, mirrored.
        if (m < 10.5f) {
            for (i in 0..2) out[i] = 1f - min((1f - b[i]) / max(s[i], EPS), 1f)
            return
        }

        // LINEAR BURN
        if (m < 11.5f) {
            for (i in 0..2) out[i] = max(b[i] + s[i] - 1f, 0f)
            return
        }

        // HARD LIGHT — see hardLightFamily for the expression and the one thing that differs.
        if (m < 12.5f) return hardLightFamily(b, s, out, bySource = true)

        // SOFT LIGHT, including the D(b) the W3C folds in:
        //   b <= 0.25 : D = ((16b - 12)b + 4)b    (the quartic near black)
        //   b >  0.25 : D = sqrt(b)
        // and the two halves of the mode meet at s == 0.5, where both give b.
        if (m < 13.5f) {
            for (i in 0..2) {
                val bi = b[i]
                val si = s[i]
                val d = if (bi <= 0.25f) ((16f * bi - 12f) * bi + 4f) * bi else sqrt(max(bi, 0f))
                out[i] =
                    if (si <= 0.5f) bi - (1f - 2f * si) * bi * (1f - bi)
                    else bi + (2f * si - 1f) * (d - bi)
            }
            return
        }

        // VIVID LIGHT: colour-burn on the dark half, colour-dodge on the light half, with the
        // source remapped to 0..1 first so both halves agree at s == 0.5 (there, both give b).
        if (m < 14.5f) {
            for (i in 0..2) {
                val bi = b[i]
                val si = s[i]
                val burn = 1f - min((1f - bi) / max(2f * si, EPS), 1f)
                val dodge = min(bi / max(2f - 2f * si, EPS), 1f)
                out[i] = if (si <= 0.5f) burn else dodge
            }
            return
        }

        // LINEAR LIGHT
        if (m < 15.5f) {
            for (i in 0..2) out[i] = max(0f, min(1f, b[i] + 2f * s[i] - 1f))
            return
        }

        // PIN LIGHT
        if (m < 16.5f) {
            for (i in 0..2) {
                out[i] = if (s[i] <= 0.5f) min(b[i], 2f * s[i]) else max(b[i], 2f * s[i] - 1f)
            }
            return
        }

        // HARD MIX: vivid light thresholded at 0.5, which reduces exactly to b + s >= 1 on both
        // halves — so this is the exact mode and not an approximation of it.
        if (m < 17.5f) {
            for (i in 0..2) out[i] = if (b[i] + s[i] >= 1f) 1f else 0f
            return
        }

        // EXCLUSION
        if (m < 18.5f) {
            for (i in 0..2) out[i] = b[i] + s[i] - 2f * b[i] * s[i]
            return
        }

        // SUBTRACT
        if (m < 19.5f) {
            for (i in 0..2) out[i] = max(b[i] - s[i], 0f)
            return
        }

        // DIVIDE
        if (m < 20.5f) {
            for (i in 0..2) out[i] = min(b[i] / max(s[i], EPS), 1f)
            return
        }

        // DARKER / LIGHTER COLOR compare the WHOLE pixel's luminosity and hand back one of the two
        // colours BYTE FOR BYTE. They are not per-channel min/max — that is DARKEN/LIGHTEN, and
        // getting that confused is the classic non-separable-mode bug.
        if (m < 21.5f) return write(if (lum(s) < lum(b)) s else b, out)
        if (m < 22.5f) return write(if (lum(s) > lum(b)) s else b, out)

        // HUE / SATURATION / LUMINOSITY, one band, because all three end in the same
        // SetLum + ClipColor tail that COLOR already needed. Only the colour fed into it differs:
        //   HUE        = SetLum(SetSat(source,   Sat(backdrop)), Lum(backdrop))
        //   SATURATION = SetLum(SetSat(backdrop, Sat(source)),   Lum(backdrop))
        //   LUMINOSITY = SetLum(backdrop,                          Lum(source))
        if (m < 25.5f) {
            scratch[0] = b[0]
            scratch[1] = b[1]
            scratch[2] = b[2]
            var targetLum = lum(s)
            if (m < 24.5f) {
                val hueFrom = if (m < 23.5f) s else b      // whose HUE survives
                val satFrom = if (m < 23.5f) b else s      // whose SATURATION survives
                val sat = max(max(satFrom[0], satFrom[1]), satFrom[2]) -
                    min(min(satFrom[0], satFrom[1]), satFrom[2])
                val lo = min(min(hueFrom[0], hueFrom[1]), hueFrom[2])
                val hi = max(max(hueFrom[0], hueFrom[1]), hueFrom[2])
                // Slide the colour to zero and rescale its span to the wanted saturation, which is
                // what the W3C's min/mid/max walk computes. A colour with no span at all has no
                // saturation to restore, so it becomes black.
                if (hi > lo) {
                    for (i in 0..2) scratch[i] = (hueFrom[i] - lo) * sat / (hi - lo)
                } else {
                    scratch[0] = 0f
                    scratch[1] = 0f
                    scratch[2] = 0f
                }
                targetLum = lum(b)
            }
            val shift = targetLum - lum(scratch)
            for (i in 0..2) scratch[i] += shift
            clipColorInPlace(scratch)
            return write(scratch, out)
        }

        // Past the last known code: the SOURCE, not the mode above it. The Studio made ADD an
        // explicit band for exactly this reason, and made this final return not a fall-through for
        // the same one: a future mode appended without its own band must look obviously
        // unimplemented, never quietly like LUMINOSITY.
        return write(s, out)
    }

    // ── the shared colour helpers ─────────────────────────────────────────────────

    /**
     * OVERLAY and HARD LIGHT. One expression: `2*b*s` below the halfway point and
     * `1 - 2(1-b)(1-s)` above it. [bySource] false tests the BACKDROP (OVERLAY), true tests the
     * SOURCE (HARD LIGHT). That one operand is the whole of the difference between the two modes,
     * and swapping it renders one as the other for every pixel without anything failing.
     */
    private fun hardLightFamily(b: FloatArray, s: FloatArray, out: FloatArray, bySource: Boolean) {
        for (i in 0..2) {
            val x = b[i]
            val y = s[i]
            val lo = 2f * x * y
            val hi = 1f - 2f * (1f - x) * (1f - y)
            out[i] = if (if (bySource) y < 0.5f else x < 0.5f) lo else hi
        }
    }

    private fun write(c: FloatArray, out: FloatArray) {
        out[0] = c[0]
        out[1] = c[1]
        out[2] = c[2]
    }

    /**
     * The Studio's luminance weights: 0.3 / 0.59 / 0.11 — not Rec.709's 0.2126/0.7152/0.0722 and not
     * Rec.601's 0.299. It is `BlendModes.lum` verbatim, and a luma swap here would move every
     * whole-colour mode in the table, which is one of the things the table is for.
     */
    private fun lum(c: FloatArray): Float = 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2]

    /**
     * The W3C ClipColor tail, in place, for the Studio's whole-colour modes.
     *
     * Moving luminosity can push a channel out of 0..1, and clamping it there would change the HUE
     * — which is the thing the caller asked for. So the colour is scaled back toward its own
     * luminosity instead, which keeps the result the source's colour rather than a clipped
     * approximation of it. [cl] is deliberately NOT recomputed after the first rescale: both
     * branches measure against the original colour's luminosity, exactly as `BlendModes.clipColor`
     * and the GLSL do, and recomputing it would be a difference from the Studio.
     */
    private fun clipColorInPlace(c: FloatArray) {
        val cl = lum(c)
        val cn = min(min(c[0], c[1]), c[2])
        val cx = max(max(c[0], c[1]), c[2])
        if (cn < 0f) {
            val k = cl / max(cl - cn, EPS)
            for (i in 0..2) c[i] = cl + (c[i] - cl) * k
        }
        if (cx > 1f) {
            val k = (1f - cl) / max(cx - cl, EPS)
            for (i in 0..2) c[i] = cl + (c[i] - cl) * k
        }
        for (i in 0..2) c[i] = max(0f, min(1f, c[i]))
    }
}
