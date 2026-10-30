package cc.joycreator.joybrush.core.input

import kotlin.math.hypot

/**
 * Unibrush v18 contact/replay contract. Uses existing StrokeSmoother for exact historical replay.
 * A null spec is the old recording contract: smooth with the record's legacy amount.
 * List and admission-sink APIs process precisely the same ordered state transitions.
 * A throwing sink aborts this contact; never reuse/reenter this instance after refusal.
 */
class StrokeStabilizer(spec: StabilizerSpec?, legacySmoothing: Float, screenPerDoc: Float = 1f) {
    private val actual = (spec ?: StabilizerSpec(strength = legacySmoothing)).also {
        // Validate before sizing the ring, so hostile window sizes never allocate memory.
        if (spec != null) require(it.problems().isEmpty()) { it.problems().joinToString("; ") }
    }
    private val zoom = if (screenPerDoc.isFinite() && screenPerDoc > 0f) screenPerDoc.toDouble() else 1.0
    private val smoother = if (actual.mode == StabilizerMode.smooth)
        StrokeSmoother(actual.strength, screenPerDoc) else null
    private val radius = actual.radiusPx.toDouble() * actual.strength / zoom
    private var finished = false
    private var dropped = 0
    private var lastRaw: PenSample? = null
    private var ropeX = 0.0
    private var ropeY = 0.0
    private val average = arrayOfNulls<PenSample>(if (actual.mode == StabilizerMode.movingAverage) actual.windowSize else 1)
    private var count = 0
    private var cursor = 0
    private var sumX = 0.0
    private var sumY = 0.0

    val droppedSamples: Int get() = smoother?.droppedSamples ?: dropped

    fun add(sample: PenSample): List<PenSample> = ArrayList<PenSample>().also { out -> addTo(sample, out::add) }
    fun finish(): List<PenSample> = ArrayList<PenSample>().also { out -> finishTo(out::add) }

    fun addTo(sample: PenSample, emit: (PenSample) -> Unit) {
        check(!finished) { "stroke already finished" }
        smoother?.let { it.addTo(sample, emit); return }
        if (sample.predicted) return
        if (!sample.isPlaceable) { dropped++; return }
        val first = lastRaw == null
        lastRaw = sample
        if (actual.strength == 0f ||
            (actual.mode == StabilizerMode.movingAverage && actual.windowSize == 1)) {
            emit(sample)
            return
        }
        when (actual.mode) {
            StabilizerMode.off -> emit(sample)
            StabilizerMode.smooth -> error("Missing smooth implementation")
            StabilizerMode.movingAverage -> {
                average[cursor]?.let { old -> sumX -= old.x; sumY -= old.y }
                average[cursor] = sample
                sumX += sample.x; sumY += sample.y
                count = (count + 1).coerceAtMost(average.size)
                cursor = (cursor + 1) % average.size
                val a = actual.strength.toDouble()
                emit(sample.copy(x = (sample.x + (sumX / count - sample.x) * a).toFloat(),
                    y = (sample.y + (sumY / count - sample.y) * a).toFloat()))
            }
            StabilizerMode.pulledString, StabilizerMode.catchUpOnLift -> {
                if (first || radius <= 0.0) { ropeX = sample.x.toDouble(); ropeY = sample.y.toDouble() }
                else {
                    val dx = sample.x.toDouble() - ropeX
                    val dy = sample.y.toDouble() - ropeY
                    val d = hypot(dx, dy)
                    if (d > radius) {
                        val f = (d - radius) / d
                        ropeX += dx * f; ropeY += dy * f
                    }
                }
                emit(sample.copy(x = ropeX.toFloat(), y = ropeY.toFloat()))
            }
        }
    }

    fun finishTo(emit: (PenSample) -> Unit) {
        if (finished) return
        finished = true
        smoother?.let { it.finishTo(emit); return }
        val raw = lastRaw ?: return
        if (actual.mode == StabilizerMode.catchUpOnLift && radius > 0.0 &&
            (ropeX.toFloat() != raw.x || ropeY.toFloat() != raw.y)) emit(raw)
        average.fill(null)
    }
}
