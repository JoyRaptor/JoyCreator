package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Dab
import kotlin.math.*

/** Mulberry32, exactly the POC's four draws per stamp (including zero scatter/rotation). */
internal class WatercolourRandom(seed: Long) {
    private var a = seed.toInt()
    fun next(): Double {
        a += 0x6D2B79F5
        var t = a
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)).toLong() and 0xffffffffL) / 4294967296.0
    }
}

/** The POC uses float storage but DOUBLE interpolation, and 15 binary-search iterations. */
internal class WatercolourResponse(handles: List<Double>) {
    private val table = FloatArray(257)
    init {
        require(handles.size == 4 && handles.all { it in 0.0..1.0 } && handles[0] <= handles[2])
        for (j in 1 until 256) {
            var lo = 0.0
            var hi = 1.0
            repeat(15) {
                val t = (lo + hi) / 2
                if (bezier(t, handles[0], handles[2]) < j / 256.0) lo = t else hi = t
            }
            table[j] = bezier((lo + hi) / 2, handles[1], handles[3]).toFloat()
        }
        table[256] = 1f
    }
    fun eval(x: Double): Double {
        if (x <= 0) return 0.0
        if (x >= 1) return 1.0
        val position = x * 256
        val index = floor(position).toInt()
        val a = table[index].toDouble()
        return a + (table[index + 1].toDouble() - a) * (position - index)
    }
    private fun bezier(t: Double, a: Double, b: Double): Double =
        3 * (1 - t) * (1 - t) * t * a + 3 * (1 - t) * t * t * b + t * t * t
}

/**
 * Exact POC placement, input filtering and deposition, shared by live strokes and line replay.
 * Input/segment arithmetic stays Double; only final GPU-facing Dab values are Float. The old
 * DabPlacer and BrushDabber are untouched. One instance per stroke; not thread-safe.
 *
 * [begin] stamps the pen-down; [add] releases only evenly spaced stamps; [finish] deliberately
 * adds no endpoint duplicate (the POC does not). Missing tilt means upright, missing pressure
 * means full pressure. Broken positions/time are skipped without poisoning the carried distance.
 */
class WatercolourDabber(val spec: WatercolourSpec, seed: Long, val maxDabs: Int = 6000) {
    /** Degrees here only; PenSample's radians are converted at the input boundary. */
    data class Point(val x: Double, val y: Double, val pressure: Double, val tiltDegrees: Double)

    private val seed32 = seed and 0xffffffffL
    private var random = WatercolourRandom(seed32)
    private val pressureCurve = WatercolourResponse(spec.pressureBezier)
    private val tiltCurve = WatercolourResponse(spec.tiltBezier)
    private var previous: Point? = null
    private var filtered: Point? = null
    private var toNext = 0.0
    private var travel = 0.0
    private var heading = 0.0
    var dabCount: Int = 0
        private set
    var capped: Boolean = false
        private set
    private var ended = false

    /** Fractions of the backdrop's world tile; a separate stream cannot shift the stamp stream. */
    val backdropOffsetX: Double
    val backdropOffsetY: Double
    val strokeOpacity: Float get() = spec.strokeOpacity.toFloat()
    val distancePx: Double get() = travel

    init {
        require(maxDabs in 1..12000)
        require(spec.dabImages.isNotEmpty() && spec.window in 1..spec.dabImages.size)
        require(spec.tipAngle in 0.0..59.99 && spec.size > 0 && spec.spacing > 0)
        val offset = WatercolourRandom(seed32 xor 0x38bc5721L)
        backdropOffsetX = offset.next() * spec.backdropOffset
        backdropOffsetY = offset.next() * spec.backdropOffset
    }

    fun begin(sample: PenSample): List<Dab> = if (sample.isPlaceable) begin(point(sample)) else emptyList()
    fun add(sample: PenSample): List<Dab> = if (sample.isPlaceable) add(point(sample)) else emptyList()

    /** Double entry is useful for exact reference fixtures and lossless imported vector paths. */
    fun begin(p: Point): List<Dab> {
        if (!placeable(p)) return emptyList()
        val q = safeChannels(p)
        random = WatercolourRandom(seed32)
        previous = q
        filtered = q
        toNext = 0.0
        travel = 0.0
        heading = 0.0
        dabCount = 0
        capped = false
        ended = false
        val first = stamp(q)
        toNext = spacing(q)
        return listOf(first)
    }

    fun add(p: Point): List<Dab> {
        if (ended || !placeable(p)) return emptyList()
        val q = previous ?: return begin(p)
        val f = filtered ?: q
        val input = safeChannels(p)
        val inputDistance = hypot(input.x - f.x, input.y - f.y)
        fun smooth(amount: Double) = if (amount == 0.0) 1.0 else
            1 - exp(-max(.1, inputDistance) / (amount * 40))
        val path = smooth(spec.smoothPath)
        val v = Point(
            f.x + (input.x - f.x) * path,
            f.y + (input.y - f.y) * path,
            f.pressure + (input.pressure - f.pressure) * smooth(spec.smoothPressure),
            f.tiltDegrees + (input.tiltDegrees - f.tiltDegrees) * smooth(spec.smoothTilt),
        )
        filtered = v
        val dx = v.x - q.x
        val dy = v.y - q.y
        val len = hypot(dx, dy)
        if (len == 0.0) { previous = v; return emptyList() }
        heading = atan2(dy, dx)
        var distance = toNext
        val oldTravel = travel
        val out = ArrayList<Dab>()
        while (distance <= len && dabCount < maxDabs) {
            val t = distance / len
            val at = Point(q.x + dx * t, q.y + dy * t,
                q.pressure + (v.pressure - q.pressure) * t,
                q.tiltDegrees + (v.tiltDegrees - q.tiltDegrees) * t)
            travel = oldTravel + distance
            out += stamp(at)
            distance += spacing(at)
        }
        if (distance <= len) {
            capped = true
            distance = len + spacing(v)
        }
        travel = oldTravel + len
        toNext = distance - len
        previous = v
        return out
    }

    fun finish(): List<Dab> { ended = true; return emptyList() }

    fun replay(samples: List<PenSample>): List<Dab> {
        val out = ArrayList<Dab>()
        var started = false
        for (s in samples) if (s.isPlaceable && !s.predicted) {
            out += if (started) add(s) else begin(s).also { started = true }
        }
        out += finish()
        return out
    }

    fun pressureResponse(p: Double): Double = pressureCurve.eval(p.coerceIn(0.0, 1.0))
    fun tiltResponse(degrees: Double): Double =
        tiltCurve.eval(((degrees - spec.tipAngle) / (60 - spec.tipAngle)).coerceIn(0.0, 1.0))

    /** Zero-based inclusive indices; sample count is asset data, never hard-coded to 25. */
    fun dabWindow(degrees: Double): IntRange {
        val start = floor(tiltResponse(degrees) * (spec.dabImages.size - spec.window) + .5).toInt()
        return start until start + spec.window
    }

    fun diameter(pressure: Double, tiltDegrees: Double = 0.0): Double {
        val minimum = min(spec.size, spec.minimum)
        val p = 1 - spec.taper + spec.taper * pressureResponse(pressure)
        val t = tiltResponse(tiltDegrees)
        val ceiling = minimum + (spec.size - minimum) * (spec.tipWidth + (1 - spec.tipWidth) * t)
        val floor = minimum + (ceiling - minimum) * spec.tiltMinimum * t
        return floor + (ceiling - floor) * p
    }

    fun modulation(p: Point, inputs: WatercolourInputs): Double {
        var factor = 1.0
        if (inputs.pressure) {
            val v = pressureResponse(p.pressure)
            factor *= if (inputs.invertPressure) 1 - v else v
        }
        if (inputs.tilt) {
            val v = tiltResponse(p.tiltDegrees)
            factor *= if (inputs.invertTilt) 1 - v else v
        }
        return factor
    }

    fun spacing(p: Point): Double = max(.5,
        diameter(p.pressure, p.tiltDegrees) * spec.spacing * modulation(p, spec.spacingInputs))

    private fun stamp(p: Point): Dab {
        val d = diameter(p.pressure, p.tiltDegrees)
        val range = dabWindow(p.tiltDegrees)
        val index = range.first + floor(random.next() * spec.window).toInt()
        val rotation = (random.next() - .5) * PI * 2 * spec.rotation + heading * spec.follow
        val angle = random.next() * PI * 2
        val radius = sqrt(random.next()) * spec.scatter * d * modulation(p, spec.scatterInputs)
        val x = p.x + cos(angle) * radius
        val y = p.y + sin(angle) * radius
        val wave = .5 + .3 * sin(travel / spec.variationLength * PI * 2 + seed32 % 97) +
            .2 * sin(travel / spec.variationLength * 2.3 + seed32 % 31)
        val variation = 1 - spec.variation * wave
        val depleted = 1 - exp(-travel / spec.travel)
        val depletion = 1 - spec.depletion * depleted
        var alpha = (spec.opacity * variation * depletion).coerceIn(0.0, 1.0)
        if (spec.spacingCompensation) alpha = 1 - (1 - alpha).pow(spacing(p) / max(.5, d * .15))
        val dryness = if (spec.paperCut == 0.0) 0.0 else
            (spec.dryness + (1 - spec.dryness) * spec.dryBuildup * depleted).coerceIn(0.0, 1.0)
        val position = dryness * 2
        val band = min(1, floor(position).toInt())
        val fraction = position - band
        val w0 = if (band == 0) 1 - fraction else 0.0
        val w1 = if (band == 0) fraction else 1 - fraction
        val w2 = if (band == 1) fraction else 0.0
        fun coverage(w: Double) = if (w <= 0) 0f else (1 - (1 - alpha).pow(w)).toFloat()
        dabCount++
        return Dab(x = x.toFloat(), y = y.toFloat(), radius = (d / 2).toFloat(),
            angle = rotation.toFloat(), flow = alpha.toFloat(), cap = 1f,
            pressure = p.pressure.toFloat(), tilt = (p.tiltDegrees * PI / 180).toFloat(),
            sampleIndex = index, band0 = coverage(w0), band1 = coverage(w1), band2 = coverage(w2),
            bandWeight0 = w0.toFloat(), bandWeight1 = w1.toFloat(), bandWeight2 = w2.toFloat())
    }

    private fun point(s: PenSample) = Point(s.x.toDouble(), s.y.toDouble(), s.pressure.toDouble(),
        if (s.tilt.isFinite()) s.tilt.toDouble() * 180 / PI else 0.0)
    private fun placeable(p: Point) = p.x.isFinite() && p.y.isFinite()
    private fun safeChannels(p: Point) = p.copy(
        pressure = if (p.pressure.isFinite()) p.pressure.coerceIn(0.0, 1.0) else 1.0,
        tiltDegrees = if (p.tiltDegrees.isFinite()) p.tiltDegrees.coerceIn(0.0, 90.0) else 0.0,
    )
}
