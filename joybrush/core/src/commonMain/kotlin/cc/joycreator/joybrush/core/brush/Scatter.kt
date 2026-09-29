package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.paint.Dab
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Scatter: one dab of a stroke becomes [ScatterSpec.count] dabs, each pushed off the stroke by a
 * random amount. A slider at a few percent is the waver a hand leaves behind its own path; the
 * same slider at one is a spray of leaves.
 *
 * Asked ONCE per stroke, over the whole list [BrushDabber] and [cc.joycreator.joybrush.core.paint.DabPlacer]
 * have already built, with that stroke's own [SplitMix] — the one already advanced past the dabber's
 * three draws per dab. So the caller must hand this the SAME generator the brush was drawing from,
 * not a fresh one: a stroke's scatter is a pure function of (dabs, spec, seed) only because the
 * dabber's draws came first.
 *
 * ## The geometry, in a dab's own frame
 *
 * A dab's [Dab.angle] is in RADIANS. The TANGENT — along the stroke — is (cos angle, sin angle), and
 * the PERPENDICULAR — across it, the dab's angle turned a quarter turn — is (cos(angle + PI/2),
 * sin(angle + PI/2)), which is (-sin angle, cos angle) with no rounding and no second trig call.
 *
 * `bothAxes = false` offsets ACROSS the stroke only. `true` adds the along component, and that is
 * the difference between jitter and leaves: across-only smears a line sideways, both-axes sprays it
 * in two dimensions.
 *
 * ## The two draws, and why they come in that order
 *
 * Per input dab this takes `c` (the count jitter) and then, for each copy, `a` then `b` — the two
 * offset axes drawn INTERLEAVED, one copy's pair at a time, not every `a` and then every `b`. A
 * fixed order per dab is fine, but a fixed order per PHASE is how a scatter ends up visibly
 * correlated: the across offsets of copy 0 and copy 3 would then be read off neighbouring numbers
 * in the stream while the along offsets of the same copies were read off numbers an entire block
 * away, and the marks line up into rows.
 *
 * `b` is drawn even when `bothAxes` is false and thrown away, so the stream — and therefore every
 * later dab, and every other brush sharing the seed — does not move when that checkbox is ticked.
 * The same argument puts `c` in the stream even when `countJitter` is 0. The total is 1 + 2n draws
 * per input dab, whatever the settings are, which is the one number a caller can rely on.
 *
 * ## The size of the box
 *
 * `amount` is a fraction of the dab's DIAMETER and is evaluated per dab, so an amount with a curve
 * on it can follow the pen. One scale factor (`amount × 2 × radius`) multiplies a sum of two
 * PERPENDICULAR unit vectors, so the offsets fill a SQUARE of side `amount × diameter`: with
 * `bothAxes` on, a corner of that square is √2 × `amount × diameter` from the stroke line. That is
 * what Decision 3 asks for and it is what this does — see JB-1.05a's Questions, where the alternative
 * (dividing by √2 when bothAxes is on, so the slider means the same reach either way) is put to the
 * owner rather than decided here.
 */
object Scatter {

    /**
     * The salt the scatter's own generator is seeded with, so its stream is not the dabber's.
     *
     * `0x5CA7L` — MOVED, not chosen: this is the number `JbCanvasView.SCATTER_SALT`
     * (`JbCanvasView.kt:651`) has always held, and the view reads a `SystemClock` value into its
     * seed at `JbCanvasView.kt:329` before xor-ing it with exactly this. It lives in core because a
     * REPLAY has to scatter identically to the drawing: [cc.joycreator.joybrush.core.vector.InkReplay]
     * seeds `SplitMix(record.seed xor SALT)` because that is the stream the pen drew with, and a
     * replay whose scatter lands somewhere else is a recording that does not look like the line.
     *
     * A SECOND generator rather than a second stream out of one, because [BrushDabber]'s three
     * draws per dab are fixed by its own contract and must not be walked differently.
     *
     * Until the view reads this constant the two hold the same number in two places, and
     * `InkReplayTest` pins this one to the literal so the pair cannot drift apart unnoticed; the
     * two-line edit that makes the view read it is the Lead's, in JB-5.01b, because
     * LEAD_RULINGS R30 item 2 reserves `JbCanvasView.kt`.
     */
    const val SALT = 0x5CA7L

    /**
     * Expands [dabs] in order. For each input dab: n = spec.count, reduced by `countJitter`, at
     * least 1. Each of the n copies is offset by (u, v) × amount × diameter, where
     * amount = Dynamics.eval(spec.amount, inputsOf(dab)) and the dab's own radius is the scale.
     *
     * Returns a new list; the input dabs are not modified, and every copy keeps every field of its
     * source except x and y. Deterministic for a given [SplitMix] state: the same seed and the same
     * stroke give the same leaves, on every platform, forever.
     *
     * [inputsOf] is called ONCE per input dab, not per copy — the pen's state is a property of the
     * dab the stroke would have drawn, and all n copies are the same dab moved.
     */
    fun expand(
        dabs: List<Dab>,
        spec: ScatterSpec,
        rng: SplitMix,
        inputsOf: (Dab) -> DabInputs,
    ): List<Dab> {
        // A stroke nobody drew is not a stroke, and must not take anything out of the stream either:
        // a caller that expands an empty list has to be sitting exactly where it was.
        if (dabs.isEmpty()) return emptyList()

        val out = ArrayList<Dab>(dabs.size)
        for (dab in dabs) {
            // Decision 1: the count draw is FIRST and is taken whether or not countJitter is set.
            val c = rng.nextFloat()
            val raw = spec.count * (1f - spec.countJitter * c)
            // The max is the "at least 1" of decision 2: a countJitter of 1 can round a count of 3
            // down to 0, and a stroke with no dabs in it is not a setting, it is a blank page.
            //
            // The isFinite() is not about the range — BrushValidate owns that, and rules 12/13 keep
            // count in 1..16 and countJitter in 0..1 for any brush that came through it. It is about
            // a brush that did NOT: roundToInt() THROWS on a NaN rather than answering 0, so a file
            // carrying `"countJitter": "NaN"` would take the render thread down with it. One dab,
            // un-scattered, is the answer that still draws.
            val copies = if (raw.isFinite()) max(1, raw.roundToInt()) else 1

            val amount = Dynamics.eval(spec.amount, inputsOf(dab))
            val wanted = amount * 2f * dab.radius
            // A brush file can be loaded without the validator ever having run, and one NaN here
            // would poison every position in the batch rather than this brush's alone. Not moving
            // the copies is the answer that still draws the stroke — and it leaves the draw count,
            // and so the stream, exactly as it was.
            val scale = if (wanted.isFinite()) wanted else 0f

            val cosA = cos(dab.angle.toDouble()).toFloat()
            val sinA = sin(dab.angle.toDouble()).toFloat()

            repeat(copies) {
                // One copy's pair at a time, never a block of a's and then a block of b's.
                val a = rng.nextFloat()
                // Drawn even when bothAxes is off, and then thrown away — see the class note. Nothing
                // here says that to the compiler, because a compiler that knew would delete it.
                val b = rng.nextFloat()
                val across = 2f * a - 1f
                val along = if (spec.bothAxes) 2f * b - 1f else 0f

                // across × perpendicular + along × tangent, where the perpendicular is the angle
                // turned a quarter turn: (-sinA, cosA), and the tangent is (cosA, sinA).
                val dx = (across * -sinA + along * cosA) * scale
                val dy = (across * cosA + along * sinA) * scale
                out.add(shifted(dab, dx, dy))
            }
        }
        return out
    }

    /**
     * The copy, moved by (dx, dy).
     *
     * A zero offset hands back the SOURCE [Dab] rather than a copy with `x + 0f` written into it,
     * and that is not tidiness: for an x of -0.0f the addition is a change of value, so "amount 0
     * leaves the stroke exactly as it was" would be a promise this class only mostly kept. A Dab
     * carries no mutable state, so sharing one is safe.
     */
    private fun shifted(dab: Dab, dx: Float, dy: Float): Dab =
        if (dx == 0f && dy == 0f) dab else dab.copy(x = dab.x + dx, y = dab.y + dy)
}
