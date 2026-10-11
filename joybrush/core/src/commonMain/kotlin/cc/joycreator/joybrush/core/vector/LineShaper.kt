package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.LineStyleSpec
import cc.joycreator.joybrush.core.input.Angles
import cc.joycreator.joybrush.core.paint.Dab
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * What a line brush does to its WHOLE stroke, as opposed to one dab at a time (VECTOR_RENDER_DESIGN §3).
 *
 * Every length is in brush DIAMETERS (the preset's `size`), so a style reads the same on a 2 px fineliner and a 40 px
 * brush pen, and re-weighting a line keeps its proportions. A number that is not finite is read as the value that
 * changes nothing (LEAD_RULINGS R1/R10: a broken number is read as a safe one, at the point that reads it).
 *
 * @property taperIn how far from the start the line grows to full width.
 * @property taperOut how far from the end it shrinks back to [taperTip]. Only knowable once the stroke has ended,
 *   which is why a line's look is committed by replay at pen-up.
 * @property taperTip the width at the very tip, as a fraction of full width (0..1).
 * @property taperShape the exponent `p` of the taper profile `(1 − (1 − t)²)^p`: 0.5 is a round bullet, 1 a soft
 *   point, 2 a needle. Every value meets the full-width body without a kink, because the inner `1 − (1 − t)²` has
 *   zero slope at `t = 1`.
 * @property dashOn the length of one dash, end to end including its round caps. 0 = a solid line.
 * @property dashOff the gap between dashes. A dash of one diameter or less is a dot.
 * @property screenWidthPx above 0, the line is a WIRE: this many screen pixels wide at every zoom.
 */
data class LineStyle(
    val taperIn: Float = 0f,
    val taperOut: Float = 0f,
    val taperTip: Float = 0.1f,
    val taperShape: Float = 1f,
    val dashOn: Float = 0f,
    val dashOff: Float = 0f,
    val screenWidthPx: Float = 0f,
) {
    val tapers: Boolean get() = safeLength(taperIn) > 0f || safeLength(taperOut) > 0f
    val dashes: Boolean get() = safeLength(dashOn) > 0f && safeLength(dashOff) > 0f
    val isWire: Boolean get() = safeLength(screenWidthPx) > 0f

    companion object {
        /** No shaping at all: what a brush without a `line` section gets. */
        val NONE = LineStyle()

        /** The style a brush file's `line` section asks for; [NONE] when it has none. */
        fun of(spec: LineStyleSpec?): LineStyle = if (spec == null) NONE else LineStyle(
            taperIn = spec.taperIn, taperOut = spec.taperOut, taperTip = spec.taperTip, taperShape = spec.taperShape,
            dashOn = spec.dashOn, dashOff = spec.dashOff, screenWidthPx = spec.screenWidthPx,
        )

        internal fun safeLength(v: Float): Float = if (v.isFinite() && v > 0f) v else 0f
    }
}

/**
 * Whole-stroke shaping of a dab list: taper, dashes, wire width and densifying for deep zoom.
 *
 * Every function here is a PURE function of its arguments. No clock, no random draw, no zoom other than the one passed
 * in. The order the replay applies them in is: placed dabs → [taper] → [dashRuns] → scatter → `widthScale`, so dashes
 * and tapers are measured along the drawn path and never along a scattered one.
 *
 * Distance along the stroke is the sum of the gaps between consecutive dab centres. The placer puts dabs at most
 * `max(2r·spacing, 0.5)` doc px apart along the smoothed path, so the chord sum differs from the true arc length by
 * far less than a pixel over any stroke a hand can draw.
 */
object LineShaper {

    /** Distance along the stroke at each dab, in doc px; element 0 is 0. */
    fun arcLengths(dabs: List<Dab>): FloatArray {
        val out = FloatArray(dabs.size)
        for (i in 1 until dabs.size) {
            val a = dabs[i - 1]
            val b = dabs[i]
            out[i] = out[i - 1] + hypot(b.x - a.x, b.y - a.y)
        }
        return out
    }

    // ── taper ────────────────────────────────────────────────────────────────────────────

    /**
     * [dabs] with each radius multiplied by the taper at its distance along the stroke. Positions, angles, flows and
     * every other field are untouched, so a tapered line keeps its seed's scatter and its picker geometry.
     *
     * When the two tapers are together longer than the stroke, both are shortened in proportion so they meet: a short
     * flick is all taper, which is what a brush does. A stroke of zero length (a tap) is returned as it is, because a
     * tapered tap would be a dot one tenth the size the person asked for.
     *
     * @param diameterPx the brush's nominal diameter, the unit of [LineStyle.taperIn] and [LineStyle.taperOut].
     */
    fun taper(dabs: List<Dab>, style: LineStyle, diameterPx: Float): List<Dab> {
        if (!style.tapers || dabs.size < 2) return dabs
        val d = LineStyle.safeLength(diameterPx)
        val s = arcLengths(dabs)
        val total = s.last()
        // `!(total > 0f)` and not `total <= 0f`: a position that is not a number makes the length NaN, and NaN <= 0 is false.
        if (d <= 0f || !(total > 0f) || !total.isFinite()) return dabs
        var lenIn = LineStyle.safeLength(style.taperIn) * d
        var lenOut = LineStyle.safeLength(style.taperOut) * d
        if (lenIn + lenOut > total) {
            val k = total / (lenIn + lenOut)
            lenIn *= k
            lenOut *= k
        }
        // A tip of 0 is drawn at 2%: a dab of radius exactly 0 is a degenerate tip some rasters do not guard against.
        val tip = if (style.taperTip.isFinite()) style.taperTip.coerceIn(MIN_TIP, 1f) else 0.1f
        val shape = if (style.taperShape.isFinite()) style.taperShape.coerceIn(0.25f, 4f) else 1f
        val out = ArrayList<Dab>(dabs.size)
        for (i in dabs.indices) {
            val f = min(ramp(s[i], lenIn, tip, shape), ramp(total - s[i], lenOut, tip, shape))
            val dab = dabs[i]
            out.add(if (f >= 1f) dab else dab.copy(radius = dab.radius * f))
        }
        return out
    }

    /** The thinnest a taper draws its tip, as a fraction of full width. */
    const val MIN_TIP = 0.02f

    /** Width fraction at [distance] from a tip, over a taper [length]; 1 past the taper or with no taper. */
    internal fun ramp(distance: Float, length: Float, tip: Float, shape: Float): Float {
        if (length <= 0f || distance >= length) return 1f
        val t = (distance / length).coerceIn(0f, 1f)
        val u = 1f - (1f - t) * (1f - t)
        return tip + (1f - tip) * u.pow(shape)
    }

    // ── dashes ───────────────────────────────────────────────────────────────────────────

    /**
     * [dabs] cut into dashes: one list per dash, in stroke order. A solid style returns `listOf(dabs)`.
     *
     * Each dash keeps the dab centres in a window `dashOn − 1` diameters long, so with its round end caps the dash
     * shows `dashOn` diameters end to end and the gap shows `dashOff`. The first dab of every period is always kept, so a
     * dash of one diameter or less, or one shorter than the gap between dabs, is still a dot and never vanishes. The pattern starts at the stroke's first dab, so
     * a re-drawn line dashes in the same places. The runs are kept apart so SVG export can write one sub-path per
     * dash inside the stroke's single `<path>` (R8).
     */
    fun dashRuns(dabs: List<Dab>, style: LineStyle, diameterPx: Float): List<List<Dab>> {
        val d = LineStyle.safeLength(diameterPx)
        if (!style.dashes || d <= 0f || dabs.isEmpty()) return listOf(dabs)
        val on = LineStyle.safeLength(style.dashOn) * d
        val period = on + LineStyle.safeLength(style.dashOff) * d
        val window = max(on - d, 0f)
        val s = arcLengths(dabs)
        if (!s.last().isFinite()) return listOf(dabs)
        val runs = ArrayList<List<Dab>>()
        var run: ArrayList<Dab>? = null
        var runPeriod = -1L
        var previousPeriod = -1L
        for (i in dabs.indices) {
            val k = floor(s[i] / period).toLong()
            val u = s[i] - k * period
            val firstInPeriod = k != previousPeriod
            previousPeriod = k
            val keep = u <= window || firstInPeriod
            if (!keep) continue
            if (k != runPeriod) {
                run = ArrayList()
                runs.add(run)
                runPeriod = k
            }
            run!!.add(dabs[i])
        }
        return runs
    }

    /** [dashRuns] joined back into one dab list, for the raster path. */
    fun dash(dabs: List<Dab>, style: LineStyle, diameterPx: Float): List<Dab> =
        if (!style.dashes) dabs else dashRuns(dabs, style, diameterPx).flatten()

    // ── deep zoom ────────────────────────────────────────────────────────────────────────

    /**
     * How far apart two dabs of radius [radiusPx] may be before the dip between them shows at [zoom].
     *
     * Two discs of radius r a step `h` apart leave a dip of `r − √(r² − (h/2)²)` in the edge between them. That dip is
     * below a quarter of a screen pixel when `zoom · dip ≤ ¼`. The placer's 0.5 px floor keeps the dip invisible at
     * 1:1, but a 1 px fineliner shows beads from about 4×. Returns [Float.POSITIVE_INFINITY] when any step is fine
     * (a radius below the quarter pixel itself, where the line is a hairline whatever the step).
     */
    fun maxStepFor(radiusPx: Float, zoom: Float): Float {
        if (!(radiusPx > 0f) || !(zoom > 0f) || !radiusPx.isFinite() || !zoom.isFinite()) return Float.POSITIVE_INFINITY
        val dip = 0.25f / zoom
        if (radiusPx <= dip) return Float.POSITIVE_INFINITY
        val inner = radiusPx - dip
        return 2f * sqrt(radiusPx * radiusPx - inner * inner)
    }

    /**
     * True when adding dabs between these ones cannot change the look, only remove beading.
     *
     * Within a stroke, dabs combine as `s' = cap·d + s(1 − d)`. Where the tip is solid (d = flow), after the `overlap`
     * dabs that cover a point (overlap = 2r / step) the stroke is within `(1 − flow)^overlap` of its cap. If that is
     * already under half a percent, more dabs change nothing inside the line, and a hard edge (hardness ≥ 0.9, an
     * antialiasing band of about one pixel) means they change nothing at the rim either. Soft or low-flow brushes
     * fail this test: denser dabs would build them up darker, so they are never densified.
     */
    fun densifySafe(flow: Float, hardness: Float, overlap: Float): Boolean {
        if (!flow.isFinite() || !hardness.isFinite() || !overlap.isFinite()) return false
        if (hardness < 0.9f || flow <= 0f) return false
        val residue = (1f - flow.coerceIn(0f, 1f)).pow(max(overlap, 1f))
        return residue <= 0.005f
    }

    /**
     * [dabs] with dabs interpolated in between wherever two neighbours are further apart than [maxStepFor] allows at
     * [zoom]. Only for lists [densifySafe] accepts, and never for scattered dabs (the scatter's leaves are not a path).
     *
     * The inserted dabs are linear blends of their two neighbours: position, radius, flow, cap and pressure linearly;
     * angle and azimuth the short way round; optional channels only when both neighbours have them. The original dabs
     * are all present, unchanged and in order.
     */
    fun densify(dabs: List<Dab>, zoom: Float): List<Dab> {
        if (dabs.size < 2) return dabs
        val out = ArrayList<Dab>(dabs.size)
        out.add(dabs[0])
        for (i in 1 until dabs.size) {
            val a = dabs[i - 1]
            val b = dabs[i]
            val gap = hypot(b.x - a.x, b.y - a.y)
            val limit = maxStepFor(min(a.radius, b.radius), zoom)
            if (gap > limit) {
                val pieces = kotlin.math.ceil(gap / limit).toInt().coerceAtMost(MAX_SUBDIVISIONS)
                for (j in 1 until pieces) out.add(blend(a, b, j.toFloat() / pieces))
            }
            out.add(b)
        }
        return out
    }

    // ── wire ─────────────────────────────────────────────────────────────────────────────

    /** A wire's radius in doc px when drawn at [zoom]: never thinner than one target pixel. */
    fun wireRadius(screenWidthPx: Float, zoom: Float): Float {
        val z = if (zoom.isFinite() && zoom > 0f) zoom else 1f
        return max(LineStyle.safeLength(screenWidthPx), 1f) * 0.5f / z
    }

    /**
     * [dabs] drawn as a wire at [zoom]: every radius is the wire's, then the list is densified for that zoom. A wire
     * is hard and solid by definition, so densifying is always safe for it. A style that is not a wire is returned
     * unchanged.
     */
    fun wire(dabs: List<Dab>, style: LineStyle, zoom: Float): List<Dab> {
        if (!style.isWire) return dabs
        val r = wireRadius(style.screenWidthPx, zoom)
        return densify(dabs.map { if (it.radius == r) it else it.copy(radius = r) }, zoom)
    }

    /** Most dabs [densify] inserts between one pair: a 4096 px gap at 16× on a 1 px line, far past any real stroke. */
    const val MAX_SUBDIVISIONS = 1 shl 16

    private fun blend(a: Dab, b: Dab, t: Float): Dab = Dab(
        x = a.x + (b.x - a.x) * t,
        y = a.y + (b.y - a.y) * t,
        radius = a.radius + (b.radius - a.radius) * t,
        angle = Angles.lerp(a.angle, b.angle, t),
        flow = a.flow + (b.flow - a.flow) * t,
        cap = a.cap + (b.cap - a.cap) * t,
        pressure = a.pressure + (b.pressure - a.pressure) * t,
        tilt = both(a.tilt, b.tilt, t),
        azimuth = Angles.lerp(a.azimuth, b.azimuth, t),
        travelX = b.travelX,
        travelY = b.travelY,
        travelKnown = b.travelKnown,
        aspect = both(a.aspect, b.aspect, t),
        hardness = both(a.hardness, b.hardness, t),
        tipDepth = both(a.tipDepth, b.tipDepth, t),
        paperDepth = both(a.paperDepth, b.paperDepth, t),
        anchor = both(a.anchor, b.anchor, t),
    )

    private fun both(a: Float, b: Float, t: Float): Float =
        if (a.isNaN() || b.isNaN()) Float.NaN else a + (b - a) * t
}
