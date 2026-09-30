package cc.joycreator.joybrush.core.anim

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * How a peg is allowed to LOOK, by name only. **The mapping from a style to a colour token belongs to
 * the view** (JB-3.02b): `JbColors` lives in the app module, not in `core`, so a core type that named
 * a colour would not compile. `IDENTITY` is a fill in the board's own gradient, `STATE_RING` is a
 * raised fill with a state ring, `ACTION` is the app's action pill — and exactly one peg may be
 * `ACTION` (Decision 4).
 */
enum class PegStyle { IDENTITY, STATE_RING, ACTION }

/** The three press semantics blueprint §1(a) already ruled. Named in core; mapped in the view. */
enum class PegPress { TAP, LONG_PRESS, HOVER }

/**
 * The five board-level controls, in order (R33: the peg bar owns PLAY and MODE).
 * APPEND-ONLY if this ever grows, and a growth needs a layout review — Decision 2.
 */
enum class Peg { PLAY, MODE, ONION, CADENCE, EXPORT }

/**
 * The animation paper's geometry. Pure: no Android, no clock, no file.
 *
 * ## UNITS (R32) — the rule this file exists to enforce
 *
 * **Every constant below is in DP and is multiplied by `density` at the use site.** A `const val`
 * cannot be "already × density": density is a runtime value, so a constant that claimed to be scaled
 * would be scaled by whatever the machine that built it happened to be. The draft of this row wrote
 * "44 dp" as `88f` with a comment saying it was already multiplied, which is both twice the size and
 * unachievable. The precedent is landed: `SizeOpacityDrag` keeps `SIZE_PER_DOUBLING_DP = 160f`,
 * `LOCK_TRAVEL_DP = 12f` and multiplies by its guarded `dp` field at each use
 * (`SizeOpacityDrag.kt:97, 125, 156-159`), and [scaleOf] is that same guard, lifted out of the class
 * so a stateless function can be handed a density per call.
 *
 * A length that is a MEASURE is in DOCUMENT px and is an Int or a Long, because a coordinate on an
 * unbounded canvas is not a Float (R19).
 */
object PaperGeometry {

    /** Distance between peg centres, in dp: the house touch floor (R32). */
    const val PEG_PITCH_DP = 44f

    /** A peg's drawn radius, in dp. Half the pitch, so neighbouring pegs touch and never overlap. */
    const val PEG_RADIUS_DP = 14f

    /**
     * How close a finger-down must be to a peg's CENTRE, in dp, to press it. Deliberately larger
     * than the drawn radius: visual ≠ touch size (`JOYBRUSH_VISUAL_LANGUAGE.md` §1.9).
     */
    const val PEG_HIT_RADIUS_DP = 22f

    /** The finest on-screen spacing a ruler tick may have, in dp (Decision 5). */
    const val MIN_LABEL_DP = 48f

    /** Thickness of a ruler strip, in dp. The number is here so it is typed once; the strip is 3.02b's. */
    const val RULER_THICKNESS_DP = 24f

    /**
     * How far a strip of ticks may reach before [ticks] gives up and returns nothing.
     *
     * **PROVISIONAL, and asked for by this implementation rather than by the spec.** Decision 11 says
     * no cap is NEEDED, and that is true of every view a person can be looking at: adjacent ticks are
     * at least 48 dp apart on screen, so a `W` screen-px view holds at most `W / (48 × density) + 1`
     * of them — about 43 on a 2000 px phone at density 1, and fewer as density rises. 65 536 is
     * roughly 1 500 × that ceiling. The bound is here so that a caller who hands [ticks] a range of a
     * billion document pixels gets an empty array rather than a `NegativeArraySizeException` thrown
     * on the GL thread, which is the same posture `RegionRenderer` takes with `MAX_REGION_PX`. It
     * bounds what can be ASKED FOR; it does not shape the answer to any real view.
     */
    private const val MAX_TICKS = 1 shl 16

    /**
     * `2^63` as a [Double]: the first value a [Long] cannot hold. A tick at or past it is not a
     * tick, because [Double.toLong] saturates instead of wrapping and a ruler that printed
     * 9223372036854775807 for four different ticks would be a ruler that lies.
     */
    private const val LONG_LIMIT = 9.223372036854776E18

    /**
     * Where each peg sits, left to right, in a bar [barWidthPx] screen px wide, at [density].
     *
     * ALWAYS [count] values, ascending, and the block is ALWAYS centred on `barWidthPx / 2`. A
     * `count` below 1 is treated as 1. Never a non-finite number for any input a device can produce.
     *
     * The dp → px step is [scaleOf], HERE at the use site and never inside the constant (R32).
     * Everything else is Decision 6: the pitch is [PEG_PITCH_DP] × density unless the block will not
     * fit, then it is `barWidthPx / count` floored at `2 × PEG_RADIUS_DP × density`, and the first
     * centre is `barWidthPx / 2 − (count − 1) × pitch / 2` in BOTH cases — so the block is centred
     * either way, and a bar too narrow even for the floor overflows symmetrically rather than to the
     * right.
     */
    fun pegCentres(count: Int, barWidthPx: Float, density: Float = 1f): FloatArray {
        val n = if (count >= 1) count else 1
        val d = scaleOf(density)
        val bar = if (barWidthPx.isFinite()) barWidthPx else 0f
        val radius = PEG_RADIUS_DP * d
        val preferred = PEG_PITCH_DP * d
        // "Fits" means the last peg's OUTER edge clears the bar, not that its CENTRE does: a peg
        // centred on the last pixel is a peg half off the end of the control.
        val pitch = if ((n - 1) * preferred + 2f * radius <= bar) {
            preferred
        } else {
            val floor = 2f * radius
            val shrunk = bar / n.toFloat()
            if (shrunk > floor) shrunk else floor
        }
        val first = bar / 2f - (n - 1) * pitch / 2f
        val out = FloatArray(n)
        var i = 0
        while (i < n) {
            out[i] = first + i * pitch
            i++
        }
        return out
    }

    /**
     * The index of the peg whose CENTRE is within [PEG_HIT_RADIUS_DP] × [density] of [xPx], or −1.
     * An equal distance to two pegs goes to the LOWER index. `count` is coerced exactly as
     * [pegCentres] coerces it, so a hit test can never disagree with the layout it hits against —
     * which is why this CALLS [pegCentres] instead of repeating its arithmetic.
     *
     * A non-finite [xPx] finds nothing: `abs(NaN − c)` is NaN and every comparison with NaN is
     * false, so "is this within the radius" is answered "no" rather than throwing.
     *
     * This lays the bar out in order to test against it, so a caller that hit-tests on every frame
     * allocates five Floats a frame. At that rate a view that wants to poll should cache the centres
     * from [pegCentres] and ask this only on a finger-down, which is the only place a peg is chosen.
     */
    fun pegAt(xPx: Float, count: Int, barWidthPx: Float, density: Float = 1f): Int {
        val centres = pegCentres(count, barWidthPx, density)
        val tolerance = PEG_HIT_RADIUS_DP * scaleOf(density)
        var best = -1
        var bestDistance = 0f
        var i = 0
        while (i < centres.size) {
            val distance = abs(xPx - centres[i])
            // STRICTLY `<` on the improvement, so an exact tie keeps the earlier (lower) index.
            if (distance <= tolerance && (best < 0 || distance < bestDistance)) {
                best = i
                bestDistance = distance
            }
            i++
        }
        return best
    }

    /**
     * The pixel rulers' tick positions along one axis, as DOCUMENT px RELATIVE TO THE BOARD'S
     * TOP-LEFT CORNER, ascending, for every tick whose position lies within
     * `[viewStartDoc, viewEndDoc]` — a CLOSED interval, so a tick exactly on the far edge is drawn.
     * Both bounds and [boardOriginDoc] are document px (`board.rect.x.toDouble()` and so on).
     *
     * [screenPerDoc] is `ViewTransform.zoom` — screen px per document px, never its inverse (R19,
     * JB-2.16a). The step is the finest of 1/2/5 × 10^k whose on-screen spacing is at least
     * [MIN_LABEL_DP] × [density] (Decision 5), and the ladder starts at 1 (Decision 9).
     *
     * A non-finite or ≤ 0 [screenPerDoc], an inverted view, or a non-finite argument gives an
     * **empty array**, never an exception: a ruler drawn with a broken zoom is a grey block, and a
     * throw out of a `View.onDraw` is a black screen.
     *
     * The one bound this has and the spec did not ask for is [MAX_TICKS] — see the note on it.
     */
    fun ticks(
        viewStartDoc: Double,
        viewEndDoc: Double,
        boardOriginDoc: Double,
        screenPerDoc: Float,
        density: Float = 1f,
    ): LongArray {
        if (!viewStartDoc.isFinite()) return LongArray(0)
        if (!viewEndDoc.isFinite()) return LongArray(0)
        if (!boardOriginDoc.isFinite()) return LongArray(0)
        if (viewEndDoc < viewStartDoc) return LongArray(0)
        if (!screenPerDoc.isFinite() || screenPerDoc <= 0f) return LongArray(0)

        val step = ladderStep(screenPerDoc, density) ?: return LongArray(0)
        val stepD = step.toDouble()

        // Decision 10: k runs over EVERY integer, negative included, so a tick can be at or BELOW a
        // negative coordinate. `ceil` on the low side and `floor` on the high side is what makes a
        // −2..2 view hold a tick at −2 at step 2, where a `k ≥ 0` reading would have left its left
        // edge unruled. This is the floor-not-truncate bug the project keeps meeting at a seam.
        val firstK = ceil((viewStartDoc - boardOriginDoc) / stepD)
        val lastK = floor((viewEndDoc - boardOriginDoc) / stepD)
        val span = lastK - firstK + 1.0
        if (!span.isFinite() || span < 1.0 || span > MAX_TICKS.toDouble()) return LongArray(0)

        val from = firstK.toLong()
        val to = lastK.toLong()
        // The RETURNED value is board-relative (Decision 8), so it is `k × step` and the origin
        // never appears in the answer — two boards at different places on the canvas rule the same
        // numbers, and that is what makes this a measuring scale rather than a map of the document.
        // The endpoints bound every tick between them, because the step is positive and so the
        // sequence is monotone: checking two values bounds the whole array.
        if (!isTickValue(from.toDouble() * stepD)) return LongArray(0)
        if (!isTickValue(to.toDouble() * stepD)) return LongArray(0)

        val out = LongArray((to - from + 1L).toInt())
        var i = 0
        var k = from
        while (i < out.size) {
            out[i] = (k.toDouble() * stepD).toLong()
            k++
            i++
        }
        return out
    }

    /**
     * The finest 1/2/5 × 10^k step whose on-screen spacing is at least [MIN_LABEL_DP] × density, or
     * null when no such step fits a [Long] — which takes a zoom so small that the answer is 10^85,
     * and
     * a tick at 10^85 document pixels is not a tick.
     *
     * The ladder is COMPARED against, never rounded to (Decision 9), so no rounding epsilon can put
     * a tick half a label off. The loop floors the decade and then picks the leading digit, and that
     * is what makes 960 the boundary rather than a fudge: 960 is over 5 × 100, so the smallest ladder
     * value reaching it is 10 × 100.
     */
    private fun ladderStep(screenPerDoc: Float, density: Float): Long? {
        val floorPx = (MIN_LABEL_DP * scaleOf(density)).toDouble()
        val needed = floorPx / screenPerDoc.toDouble()
        // The isFinite guard is what stops the decade loop below running to Infinity: an infinite
        // `wanted` satisfies `decade * 10 <= wanted` for ever.
        if (!needed.isFinite()) return null
        // The ladder starts at 1 (Decision 9), which is what keeps a tick a whole document pixel and
        // lets the answer be a Long at all (R19). Starting below 1 is a contract change, not a tuning.
        val wanted = if (needed > 1.0) needed else 1.0
        // `wanted` is bounded by 48 × Float.MAX_VALUE / Float.MIN_VALUE (about 10^85), so this
        // terminates.
        var decade = 1.0
        while (decade * 10.0 <= wanted) decade *= 10.0
        val step = when {
            decade >= wanted -> decade
            2.0 * decade >= wanted -> 2.0 * decade
            5.0 * decade >= wanted -> 5.0 * decade
            else -> 10.0 * decade
        }
        if (!step.isFinite() || step >= LONG_LIMIT) return null
        return step.toLong()
    }

    /** A tick has to be a [Long] and not a saturation. See [LONG_LIMIT]. */
    private fun isTickValue(v: Double): Boolean = v.isFinite() && v > -LONG_LIMIT && v < LONG_LIMIT

    /**
     * The label a tick prints. Whole pixels, no decimal point, no thousands separator, no unit.
     *
     * [Long.toString] is already exactly that, and it is the whole implementation because a [Long] is
     * what the ladder produces: there is no float to format, so the `-0`, the `0.0` and the `1,400`
     * that a Float or Double formatter would have to be argued out of cannot arise. The test asserts
     * the format anyway, because swapping in a formatter is exactly the edit that brings them back.
     */
    fun label(tickDocRelToOrigin: Long): String = tickDocRelToOrigin.toString()

    /**
     * How a peg is allowed to look, given what is switched on. The one place the "one saturated
     * control" rule exists, so it is a test and not a review comment (Decision 4).
     *
     * `PLAY` is the only `ACTION`, and only while it is doing something: a Play button that looked
     * the same whether it was playing is a control that lies. R33 is why there is nothing to compare
     * it with — the peg bar owns PLAY and MODE, and the film strip has prev/next and nothing else, so
     * this is the ONE saturated control on the whole animation board.
     */
    fun style(peg: Peg, active: Boolean): PegStyle = when (peg) {
        Peg.PLAY -> if (active) PegStyle.ACTION else PegStyle.STATE_RING
        Peg.ONION -> if (active) PegStyle.IDENTITY else PegStyle.STATE_RING
        Peg.MODE, Peg.CADENCE, Peg.EXPORT -> PegStyle.IDENTITY
    }

    /**
     * The density guard, lifted out of [SizeOpacityDrag] so a stateless function can have one. Not
     * finite or ≤ 0 becomes `1f`, for the reason that class gives: a zero density divides by zero in
     * the ruler's tolerance and turns the whole bar into NaN, and that is a bug which only ever
     * appears on one device class.
     *
     * The guard is about GARBAGE, not about magnitude. A density past about 10^37 — which no display
     * reports, and which is the only way the Float arithmetic in this file overflows — is not caught
     * here, and [pegCentres] would answer with an infinity. Catching it would mean inventing a
     * maximum density that nothing in the tree knows, which is a worse lie than the overflow.
     */
    private fun scaleOf(density: Float): Float =
        if (density.isFinite() && density > 0f) density else 1f
}
