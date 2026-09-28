package cc.joycreator.joybrush.core.render

import cc.joycreator.joybrush.core.doc.BlendMode
import kotlin.math.min

/**
 * The eight [BlendMode]s as ONE compositing step, on PREMULTIPLIED floats in 0..1.
 *
 * WHY PREMULTIPLIED, and why it is the only choice: the GPU stores tiles as premultiplied RGBA8
 * (`jb_tile.frag` blends them straight into a premultiplied framebuffer with ONE,
 * ONE_MINUS_SRC_ALPHA), and so does everything this module hands back
 * ([RegionRenderer.renderPremultiplied]). A straight-alpha renderer would have to un-premultiply
 * and re-premultiply at every single step, and the round trip across an 8-bit boundary is exactly
 * where a half-transparent edge goes quietly wrong — it shows up as "the colours are slightly off"
 * rather than as an obvious bug. Compositing in premultiplied space has no such step to get wrong.
 *
 * The seven separable modes are W3C Compositing and Blending Level 1 exactly. Per channel, with
 * `Cs` the straight source colour and `Cb` the straight backdrop colour:
 *
 *     co = sa(1 - da)Cs + sa * da * B(Cb, Cs) + (1 - sa)da * Cb     (this IS the premultiplied out)
 *     a  = sa + da(1 - sa)
 *
 * Because every `B` here stays in 0..1, `co` is always in 0..a, so a blended pixel can never be an
 * impossible premultiplied colour and un-premultiplying it can never run away. The blend terms are
 * in `term`; the one line of arithmetic that uses them is `compositeChannel`.
 *
 * [BlendMode.ERASE_BELOW] is NOT separable and is NOT source-over: it is destination-out,
 * `d * (1 - sa)`, on ALL FOUR CHANNELS INCLUDING ALPHA. That is what the GPU does for an erase
 * (`jb_commit.frag`: `dst * (1.0 - a)` on the whole vec4) and what `RefCanvas.endStroke` does on
 * the CPU, so an erased pixel leaves the destination TRANSPARENT instead of leaving its colour
 * behind at reduced alpha. Scaling only the colour and keeping the alpha is the most expensive way
 * to get this wrong: the phone shows the eraser working and the export shows grey smears. Note
 * that the alpha rule is different too — `a = da(1 - sa)`, not `sa + da(1 - sa)` — which is
 * exactly why it cannot be expressed by swapping `B`.
 */
object Blend {

    /**
     * The one implementation, dispatched on [mode]. [s] is the source and [d] the backdrop, both
     * premultiplied; [out] receives the premultiplied result and MUST NOT be [s] or [d] (the
     * renderer keeps its own scratch pixel for that reason).
     *
     * [s] is expected to have the layer's opacity already folded into it, so that `s[3]` is the
     * effective alpha — see `jb_tile.frag`, which scales the premultiplied texel before the blend.
     */
    fun apply(mode: BlendMode, s: FloatArray, d: FloatArray, out: FloatArray) {
        require(s.size >= 4 && d.size >= 4 && out.size >= 4) {
            "a blend needs 4 channels, got ${s.size}/${d.size}/${out.size}"
        }
        val sa = s[3]
        val da = d[3]

        if (mode == BlendMode.ERASE_BELOW) {
            val k = 1f - sa
            out[0] = d[0] * k
            out[1] = d[1] * k
            out[2] = d[2] * k
            out[3] = da * k
            return
        }

        // A zero alpha has no colour to divide by, and 0/0 is NaN rather than 0. Letting that NaN
        // into the blend term is how "a region no layer covers" turns from transparent into a
        // pixel of garbage, so the guard is a divide-and-do-not-divide rather than a defensive
        // clamp: a fully transparent backdrop IS a backdrop of black.
        val cs0 = if (sa > 0f) s[0] / sa else 0f
        val cs1 = if (sa > 0f) s[1] / sa else 0f
        val cs2 = if (sa > 0f) s[2] / sa else 0f
        val cb0 = if (da > 0f) d[0] / da else 0f
        val cb1 = if (da > 0f) d[1] / da else 0f
        val cb2 = if (da > 0f) d[2] / da else 0f

        out[0] = compositeChannel(cs0, cb0, term(mode, cs0, cb0), sa, da)
        out[1] = compositeChannel(cs1, cb1, term(mode, cs1, cb1), sa, da)
        out[2] = compositeChannel(cs2, cb2, term(mode, cs2, cb2), sa, da)
        out[3] = sa + da * (1f - sa)
    }

    /** One channel of the W3C formula, named once so all seven modes provably share it. */
    private fun compositeChannel(cs: Float, cb: Float, b: Float, sa: Float, da: Float): Float =
        sa * (1f - da) * cs + sa * da * b + (1f - sa) * da * cb

    /**
     * `B(Cb, Cs)` per channel: the separable blend function, on STRAIGHT (un-premultiplied) 0..1
     * colours so that the mode means what a colour picker says it means.
     *
     * The cases are written out by NAME rather than behind an `else` so that a ninth
     * [BlendMode] cannot be added without a compiler error here. DocModel says an unknown blend is
     * refused rather than approximated, and this is the half of that promise Kotlin can keep.
     */
    private fun term(mode: BlendMode, cs: Float, cb: Float): Float = when (mode) {
        BlendMode.NORMAL -> cs
        BlendMode.MULTIPLY -> cs * cb
        BlendMode.SCREEN -> cs + cb - cs * cb
        // Overlay tests the BACKDROP, which is what separates it from plain multiply and is the
        // one line most worth reading twice.
        BlendMode.OVERLAY -> if (cb <= 0.5f) 2f * cs * cb else 1f - 2f * (1f - cs) * (1f - cb)
        // ADD is Joy Brush's own mode, not a W3C one: a straight-colour sum, clamped. Clamping B
        // (rather than the finished pixel) is what keeps `co` in 0..a, so two near-white pixels
        // still come out as exactly white instead of as an out-of-range value an exporter would
        // have to guess about.
        BlendMode.ADD -> min(1f, cs + cb)
        // `<` and `>` rather than min/max so a tie returns the shared value instead of depending
        // on which argument a library happens to prefer first.
        BlendMode.DARKEN -> if (cs < cb) cs else cb
        BlendMode.LIGHTEN -> if (cs > cb) cs else cb
        // Unreachable: apply() returns before any separable term is asked for. Listed so that the
        // `when` stays exhaustive and a future mode cannot fall through as a silent NORMAL.
        BlendMode.ERASE_BELOW -> 0f
    }

    // ── the eight names ───────────────────────────────────────────────────────────
    // Thin doors onto apply(), so the enum and the arithmetic cannot drift apart. They exist
    // because a caller (or a test) should be able to ask for one mode by name.

    fun normal(s: FloatArray, d: FloatArray, out: FloatArray) = apply(BlendMode.NORMAL, s, d, out)
    fun multiply(s: FloatArray, d: FloatArray, out: FloatArray) = apply(BlendMode.MULTIPLY, s, d, out)
    fun screen(s: FloatArray, d: FloatArray, out: FloatArray) = apply(BlendMode.SCREEN, s, d, out)
    fun overlay(s: FloatArray, d: FloatArray, out: FloatArray) = apply(BlendMode.OVERLAY, s, d, out)
    fun add(s: FloatArray, d: FloatArray, out: FloatArray) = apply(BlendMode.ADD, s, d, out)
    fun darken(s: FloatArray, d: FloatArray, out: FloatArray) = apply(BlendMode.DARKEN, s, d, out)
    fun lighten(s: FloatArray, d: FloatArray, out: FloatArray) = apply(BlendMode.LIGHTEN, s, d, out)

    /** Destination-out on all four channels. See the class note for why alpha goes too. */
    fun eraseBelow(s: FloatArray, d: FloatArray, out: FloatArray) = apply(BlendMode.ERASE_BELOW, s, d, out)
}
