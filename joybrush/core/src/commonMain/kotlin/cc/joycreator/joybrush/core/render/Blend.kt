package cc.joycreator.joybrush.core.render

import cc.joycreator.joybrush.core.blend.BlendRgb
import cc.joycreator.joybrush.core.doc.BlendMode
import kotlin.math.min

/**
 * All TWENTY-SEVEN [BlendMode]s as ONE compositing step, on PREMULTIPLIED floats in 0..1.
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

        // The twenty modes whose answer belongs to the pixel rather than to a channel (JB-2.20a).
        if (mode.needsWholePixelBlend()) {
            STRAIGHT_S[0] = cs0; STRAIGHT_S[1] = cs1; STRAIGHT_S[2] = cs2
            STRAIGHT_B[0] = cb0; STRAIGHT_B[1] = cb1; STRAIGHT_B[2] = cb2
            val b = wholePixelTerm(mode, STRAIGHT_B, STRAIGHT_S)
            out[0] = compositeChannel(cs0, cb0, b[0], sa, da)
            out[1] = compositeChannel(cs1, cb1, b[1], sa, da)
            out[2] = compositeChannel(cs2, cb2, b[2], sa, da)
            out[3] = sa + da * (1f - sa)
            return
        }

        out[0] = compositeChannel(cs0, cb0, term(mode, cs0, cb0), sa, da)
        out[1] = compositeChannel(cs1, cb1, term(mode, cs1, cb1), sa, da)
        out[2] = compositeChannel(cs2, cb2, term(mode, cs2, cb2), sa, da)
        out[3] = sa + da * (1f - sa)
    }

    /**
     * The blend term `B(Cb, Cs)` for all THREE channels at once, which is what the twenty non-
     * separable modes need and what [term] cannot give: HUE, SATURATION, COLOR and LUMINOSITY all
     * mix the channels into each other, so there is no per-channel answer to hand to
     * [compositeChannel] — the answer only exists for the pixel.
     *
     * [BlendRgb] is the single implementation of those modes (JB-2.20a), proved equal to the Studio's
     * Java by a generated golden table rather than transcribed by eye — see R23. Calling it here is
     * what keeps the CPU renderer and the Studio from being two implementations that agree today.
     *
     * THE CLAMP LIVES HERE, NOT IN [BlendRgb]. [BlendRgb] is deliberately UNCLAMPED, because SCREEN,
     * EXCLUSION and OVERLAY all legitimately return values above 1 for inputs above 1 and clipping
     * them inside the blend term is how an exporter stops doing what the Studio does. Clamping the
     * finished term keeps this path in the same 0..1 world [term] works in, so a blended pixel can
     * never be an impossible premultiplied colour.
     *
     * NOT "in exactly one place", which an earlier version of this comment claimed and which was
     * false: [term] clamps too, and it must — `BlendMode.ADD` is Joy Brush's own straight-colour
     * sum and its clamp is part of that mode's definition, not a shared post-step. The two paths
     * agree on the 0..1 result they hand on; they are not the same line of code.
     *
     * The scratch arrays are the object's own, and safe because [apply] is not re-entrant: it writes
     * [out] only after [blendRgb] has returned, and [out] may not be [s] or [d] (which the KDoc on
     * [apply] already requires) and so cannot be these either.
     */
    private fun wholePixelTerm(
        mode: BlendMode,
        b: FloatArray,
        s: FloatArray,
    ): FloatArray {
        BlendRgb.blendRgb(mode, b, s, B_TERM, SCRATCH)
        for (i in 0..2) B_TERM[i] = B_TERM[i].coerceIn(0f, 1f)
        return B_TERM
    }

    /** One channel of the W3C formula, named once so all seven modes provably share it. */
    private fun compositeChannel(cs: Float, cb: Float, b: Float, sa: Float, da: Float): Float =
        sa * (1f - da) * cs + sa * da * b + (1f - sa) * da * cb

    /**
     * `B(Cb, Cs)` per channel: the separable blend function, on STRAIGHT (un-premultiplied) 0..1
     * colours so that the mode means what a colour picker says it means.
     *
     * THE SEVEN CASES ARE WRITTEN OUT BY NAME rather than behind an `else`, and the reason is no
     * longer "so a new mode will not compile". JB-2.20a made that untrue: the enum now has
     * twenty-seven constants, so a twenty-eighth is a compile error nobody would get — it lands in
     * the `else` below and is routed to [BlendRgb] by [needsWholePixelBlend] first, which is the
     * SAFE default and is the opposite failure from a compiler error. What naming them by hand
     * still buys is that a mode cannot be quietly per-channel when it should be whole-pixel: the
     * separation of concerns is visible in the source instead of living in a reader's memory.
     *
     * [term] now answers only the SEPARABLE modes, and every one of them is separable per channel by
     * definition. The modes whose answer belongs to the pixel rather than to a channel — HUE,
     * SATURATION, COLOR, LUMINOSITY, and DARKER/LIGHTER_COLOR among them — are dispatched to
     * [BlendRgb] in [apply] before this is ever reached, via [needsWholePixelBlend]. They are listed
     * here as `else -> 0f` purely to satisfy the compiler, and that value is unreachable because
     * [needsWholePixelBlend] and this `when` are exhaustive over the same enum —
     * `theSevenSeparableModesAreTheOnesBlendAnswersPerChannel` in the test suite is what keeps them
     * that way.
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
        // The twenty non-separable modes. Unreachable: `apply` routes every one of them to
        // `wholePixelTerm` first, because their answer belongs to the pixel and not to a channel.
        // `0f` is here so that adding one of them to this `when` is a deliberate act.
        else -> 0f
    }

    /**
     * True for the modes that cannot be answered one channel at a time, so [apply] knows to take the
     * [BlendRgb] path. Note it is a NEGATIVE list, so the twenty it covers are the ones it does not
     * name: the KDoc above once said the other twenty were "named in" here, which was backwards.
     *
     * WRITTEN AS A NEGATIVE LIST ON PURPOSE. The positive form ("these seven are separable") is what
     * [term] already says by name, and the two together are exhaustive. Listing them separately
     * would be a second thing to keep in step with the enum — and this project's own history is a
     * case where two lists of the same fact drifted. A new mode therefore lands in this `else` and
     * is automatically treated as a whole-pixel blend, which is the SAFE default: a whole-pixel
     * blend that behaves per channel is merely a little slower, whereas a per-channel term silently
     * applied to HUE would give a wrong pixel rather than a slow one.
     */
    private fun BlendMode.needsWholePixelBlend(): Boolean = when (this) {
        BlendMode.NORMAL, BlendMode.MULTIPLY, BlendMode.SCREEN, BlendMode.OVERLAY,
        BlendMode.ADD, BlendMode.DARKEN, BlendMode.LIGHTEN,
        -> false
        else -> true
    }

    // Scratch for the whole-pixel path. Object-level rather than per-call because this runs once per
    // pixel per layer and an allocation there is the kind of thing that turns a 60 fps canvas into a
    // warm one. Safe because `apply` is not re-entrant: it reads these into locals via
    // `wholePixelTerm` and writes `out` only after the blend has been read back, and `out` may not be
    // `s` or `d` (the KDoc on `apply` already requires that) and so cannot be these. A concurrent
    // call on a second thread would of course corrupt them — which is the same single-thread
    // assumption every other object in this engine makes, and the reason the GPU path hands numbers
    // across on one thread.
    private val STRAIGHT_B = FloatArray(3)
    private val STRAIGHT_S = FloatArray(3)
    private val B_TERM = FloatArray(3)
    private val SCRATCH = FloatArray(3)

    // ── the eight named doors ──────────────────────────────────────────────────────
    // Thin doors onto apply(), so the enum and the arithmetic cannot drift apart. They exist
    // because a caller (or a test) should be able to ask for one mode by name. EIGHT of
    // twenty-seven: the doors predate the Studio's nineteen and those have none, deliberately —
    // see `theEightNamedFunctionsMatchTheEnum` for why one door per mode would be nineteen more
    // places for the enum and the arithmetic to drift apart.

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
