package cc.joycreator.joybrush.core.brush

/**
 * The ONE carried colour of a smudge brush (JB-1.06; blueprint §5, LEAD_RULINGS R8).
 *
 * **THE PATENT RULE, which is a hard constraint and not a style choice.** Blueprint §5, read from the
 * actual claims (US 8,462,173 and 8,599,213): a smudge is ONE carried colour per brush that mixes
 * toward the canvas and toward the chosen colour in one dab — *never* separate reservoir and pickup
 * stores, and never "deposit only picked-up paint when there is enough". This class is the only place
 * a smudge brush's colour lives between dabs. It has no second slot, no counter, no threshold and no
 * "enough paint yet" branch, and every field is a `val`: a dab does not change it, it returns the next
 * one. A test asserts convergence and monotonicity (a reservoir would be a discontinuity), one asserts
 * the per-dab delta against the closed form, and one asserts the class has no mutable state.
 *
 * Everything is PREMULTIPLIED RGBA in `0..1`, the form the canvas is held in.
 *
 * @property loadR the brush's own chosen colour, straight (not premultiplied). The "load" source.
 * @property strength 0..1 — how much a dab replaces the canvas with the carried colour. The engine passes
 *   1: how hard a smudge presses is the brush's `flow`, which pressure already drives, so it arrives as
 *   [Smudge.dab]'s `coverage`. Kept as a field so the maths can be tested on its own.
 * @property pickup 0..1 — how much of the canvas the carried colour takes on per dab.
 * @property load 0..1 — how much of the brush's own colour the carried colour takes on per dab.
 * @property carriedR the carried colour, premultiplied. STARTS as the brush's own colour, opaque: the first
 *   dab smears the brush's colour and picks up as it goes (Decision 3).
 */
data class SmudgeCarried(
    val loadR: Float, val loadG: Float, val loadB: Float,
    val strength: Float, val pickup: Float, val load: Float,
    val carriedR: Float = loadR, val carriedG: Float = loadG, val carriedB: Float = loadB, val carriedA: Float = 1f,
) {
    init {
        // A NaN here would poison the whole stroke (JB-1.05c: jb_grainLevel did), so every number is refused
        // in words at construction, naming the field and the value. `in` is false for NaN and the infinities.
        check("loadR", loadR); check("loadG", loadG); check("loadB", loadB)
        check("strength", strength); check("pickup", pickup); check("load", load)
        check("carriedR", carriedR); check("carriedG", carriedG); check("carriedB", carriedB); check("carriedA", carriedA)
    }

    /**
     * THE one step (Decision 1): from the canvas under the tip, the next carried colour.
     * `after = lerp( lerp(carried, canvas, pickup), load colour, load )`. A pixel with nothing on the canvas
     * (`canvasA == 0`) changes nothing (Decision 4) and returns this same instance.
     */
    fun afterDab(canvasR: Float, canvasG: Float, canvasB: Float, canvasA: Float): SmudgeCarried {
        if (!(canvasA > 0f)) return this
        // Every input is in 0..1, so every output is too in exact arithmetic; in Float a lerp can land a hair past 1
        // (1.0000001), which the constructor would refuse and take the whole stroke down with it. Clamp, don't crash.
        return copy(
            carriedR = mix(mix(carriedR, canvasR, pickup), loadR, load).coerceIn(0f, 1f),
            carriedG = mix(mix(carriedG, canvasG, pickup), loadG, load).coerceIn(0f, 1f),
            carriedB = mix(mix(carriedB, canvasB, pickup), loadB, load).coerceIn(0f, 1f),
            // The chosen colour is opaque, so the alpha moves toward 1 as `load` says.
            carriedA = mix(mix(carriedA, canvasA, pickup), 1f, load).coerceIn(0f, 1f),
        )
    }

    private fun check(field: String, v: Float) {
        require(v in 0f..1f) { "SmudgeCarried.$field must be a number from 0 to 1, was $v" }
    }

    companion object {
        /** A fresh carried colour for a stroke: the brush's own colour, having carried nothing. */
        fun start(r: Float, g: Float, b: Float, strength: Float, pickup: Float, load: Float) =
            SmudgeCarried(r, g, b, strength, pickup, load)

        internal fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    }
}

object Smudge {

    /**
     * What ONE dab writes at one pixel, premultiplied RGBA, into [out] (4 floats): the carried paint laid over the canvas
     * with weight `t = strength x coverage`, i.e. `carried x t + canvas x (1 - carriedAlpha x t)`. For an opaque carried
     * colour that is exactly `lerp(canvas, carried, t)` (the spec's formula); for a faded one it is the same "over" the
     * GPU's stroke buffer does, so this function and the shader cannot disagree (LEAD_RULINGS R47).
     *
     * [coverage] is the tip's antialiased coverage times flow; it scales the MIX, not the output opacity, which is the
     * difference between a soft edge and a soft-looking hard edge. A pixel the canvas has nothing in leaves the canvas
     * alone (Decision 4): a smudge moves paint that is already there, it does not paint bare canvas in a colour the
     * person never chose for that spot.
     *
     * The write uses the carried colour BEFORE this dab's update; the update is [SmudgeCarried.afterDab].
     */
    fun dab(
        carried: SmudgeCarried,
        canvasR: Float, canvasG: Float, canvasB: Float, canvasA: Float,
        coverage: Float,
        out: FloatArray,
    ) {
        require(out.size >= 4) { "Smudge.dab needs an output array of at least 4 floats, got ${out.size}" }
        if (!(canvasA > 0f)) {
            out[0] = canvasR; out[1] = canvasG; out[2] = canvasB; out[3] = canvasA
            return
        }
        val t = (carried.strength * coverage).coerceIn(0f, 1f)
        val keep = 1f - carried.carriedA * t
        out[0] = carried.carriedR * t + canvasR * keep
        out[1] = carried.carriedG * t + canvasG * keep
        out[2] = carried.carriedB * t + canvasB * keep
        out[3] = carried.carriedA * t + canvasA * keep
    }

    /** [dab] into a fresh array. */
    fun dab(carried: SmudgeCarried, canvasR: Float, canvasG: Float, canvasB: Float, canvasA: Float, coverage: Float): FloatArray =
        FloatArray(4).also { dab(carried, canvasR, canvasG, canvasB, canvasA, coverage, it) }
}
