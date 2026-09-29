package cc.joycreator.joybrush.core.input

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StrokeSmootherTest {

    /** A pen at 240 Hz moving along [path] (a polyline) at [speed] px/s, with seeded jitter. */
    private fun drawAlong(
        path: List<Pair<Double, Double>>,
        speed: Double,
        jitter: Double = 0.0,
        wobbleAmp: Double = 0.0,
        wobbleLen: Double = 12.0,
        seed: Int = 7,
    ): List<PenSample> {
        val rnd = Random(seed)
        val dtMs = 1000.0 / 240.0
        val stepPx = speed * dtMs / 1000.0
        val out = ArrayList<PenSample>()
        var t = 0.0
        var travelled = 0.0
        for (seg in 0 until path.size - 1) {
            val (x0, y0) = path[seg]
            val (x1, y1) = path[seg + 1]
            val len = hypot(x1 - x0, y1 - y0)
            val nx = -(y1 - y0) / len
            val ny = (x1 - x0) / len
            var d = 0.0
            while (d < len) {
                val f = d / len
                val wob = wobbleAmp * sin(2 * PI * (travelled + d) / wobbleLen)
                val jx = (rnd.nextDouble() - 0.5) * 2 * jitter
                val jy = (rnd.nextDouble() - 0.5) * 2 * jitter
                out.add(
                    PenSample(
                        x = (x0 + (x1 - x0) * f + nx * wob + jx).toFloat(),
                        y = (y0 + (y1 - y0) * f + ny * wob + jy).toFloat(),
                        timeMs = t,
                        pressure = 0.5f,
                    )
                )
                d += stepPx
                t += dtMs
            }
            travelled += len
        }
        val (lx, ly) = path.last()
        out.add(PenSample(lx.toFloat(), ly.toFloat(), t, pressure = 0.5f))
        return out
    }

    private fun batch(samples: List<PenSample>, amount: Float, zoom: Float = 1f): List<PenSample> {
        val s = StrokeSmoother(amount, zoom)
        s.holdRelease = true
        samples.forEach { s.add(it) }
        return s.finish()
    }

    private fun rmsY(points: List<PenSample>, from: Float, to: Float): Double {
        val sel = points.filter { it.x in from..to }
        return sqrt(sel.sumOf { it.y.toDouble() * it.y } / sel.size)
    }

    @Test
    fun streamingReleasesExactlyWhatBatchWould() {
        val square = listOf(0.0 to 0.0, 200.0 to 0.0, 200.0 to 200.0, 0.0 to 200.0, 0.0 to 20.0)
        val samples = drawAlong(square, speed = 180.0, jitter = 0.6, wobbleAmp = 1.0)
        for (amount in listOf(0f, 0.3f, 0.7f, 1f)) {
            val streamed = StrokeSmoother.smoothAll(samples, amount)
            val batched = batch(samples, amount)
            assertEquals(batched, streamed, "amount=$amount")
        }
    }

    @Test
    fun streamingActuallyReleasesBeforePenUp() {
        val samples = drawAlong(listOf(0.0 to 0.0, 400.0 to 0.0), speed = 300.0)
        val s = StrokeSmoother(0.8f)
        var early = 0
        samples.forEach { early += s.add(it).size }
        assertTrue(early > 200, "only $early points were released while drawing")
    }

    @Test
    fun zeroAmountFollowsThePenExactly() {
        val samples = drawAlong(listOf(0.0 to 0.0, 100.0 to 50.0), speed = 400.0, jitter = 1.5)
        val out = StrokeSmoother.smoothAll(samples, 0f)
        // Every output point lies on the raw polyline (it is only resampled).
        for (p in out) {
            val d = samples.zipWithNext().minOf { (a, b) -> distToSegment(p, a, b) }
            assertTrue(d < 1e-3, "point off the raw path by $d")
        }
    }

    @Test
    fun wavyLineComesOutStraighter() {
        val samples = drawAlong(listOf(0.0 to 0.0, 300.0 to 0.0), speed = 150.0, jitter = 0.5, wobbleAmp = 2.0)
        val raw = rmsY(samples, 40f, 260f)
        val smooth = rmsY(StrokeSmoother.smoothAll(samples, 0.8f), 40f, 260f)
        assertTrue(smooth < raw * 0.4, "raw rms $raw, smoothed rms $smooth")
    }

    @Test
    fun squareCornersStaySharpAtFullSmoothing() {
        val square = listOf(0.0 to 0.0, 200.0 to 0.0, 200.0 to 200.0, 0.0 to 200.0, 0.0 to 20.0)
        val samples = drawAlong(square, speed = 180.0, jitter = 0.3)
        val out = StrokeSmoother.smoothAll(samples, 1f)
        for ((cx, cy) in listOf(200.0 to 0.0, 200.0 to 200.0, 0.0 to 200.0)) {
            val miss = out.minOf { hypot(it.x - cx, it.y - cy) }
            assertTrue(miss < 2.0, "corner ($cx,$cy) missed by $miss px")
        }
    }

    @Test
    fun smoothingIsMeasuredOnScreenSoZoomingInKeepsDetail() {
        val samples = drawAlong(listOf(0.0 to 0.0, 300.0 to 0.0), speed = 150.0, wobbleAmp = 2.0)
        val atFit = rmsY(StrokeSmoother.smoothAll(samples, 0.8f, screenPerDoc = 1f), 40f, 260f)
        val zoomedIn = rmsY(StrokeSmoother.smoothAll(samples, 0.8f, screenPerDoc = 4f), 40f, 260f)
        assertTrue(zoomedIn > atFit * 2, "zoomed-in $zoomedIn should keep far more of the wiggle than $atFit")
    }

    @Test
    fun lineFinishesWhereThePenLifted() {
        val samples = drawAlong(listOf(0.0 to 0.0, 250.0 to 40.0), speed = 900.0, jitter = 0.4)
        val out = StrokeSmoother.smoothAll(samples, 0.9f)
        val last = out.last()
        assertEquals(samples.last().x, last.x, 1e-3f)
        assertEquals(samples.last().y, last.y, 1e-3f)
    }

    @Test
    fun aSingleTapIsOneDot() {
        val out = StrokeSmoother.smoothAll(listOf(PenSample(5f, 6f, 0.0, pressure = 0.3f)), 0.7f)
        assertEquals(1, out.size)
        assertEquals(5f, out[0].x)
        assertEquals(0.3f, out[0].pressure)
    }

    @Test
    fun predictedPointsAreIgnored() {
        val s = StrokeSmoother(0.5f)
        s.add(PenSample(0f, 0f, 0.0))
        s.add(PenSample(500f, 500f, 1.0, predicted = true))
        val out = s.finish()
        assertTrue(out.all { it.x < 1f })
    }

    @Test
    fun missingChannelsStayMissing() {
        val out = StrokeSmoother.smoothAll(drawAlong(listOf(0.0 to 0.0, 50.0 to 0.0), speed = 200.0), 0.5f)
        assertTrue(out.all { it.tilt.isNaN() && it.azimuth.isNaN() && it.barrel.isNaN() })
    }

    // ── review JB-0.01 F1 / M1: the two numbers that must not be trusted ────────────────────────

    /**
     * A zoom that is not a positive finite number is read as 1, so the stroke is smoothed at the
     * wrong scale rather than not at all.
     *
     * The four bad values are the review's own (F1: "0, -1, NaN, +Inf"), and the property asserted is
     * the one that was broken rather than "it does not crash": every released point is a real point
     * in the document, and the stroke is the WHOLE stroke — the same number of points, over the same
     * span, ending where the pen lifted. Before the guard, `0f` released one NaN point and stopped,
     * `±Inf` released one ±Inf point and stopped, and NaN released one NaN point and stopped.
     */
    @Test
    fun aZoomThatIsNotAUsableNumberIsReadAsOneAndTheWholeStrokeSurvives() {
        val path = drawAlong(listOf(0.0 to 0.0, 400.0 to 40.0), speed = 300.0, wobbleAmp = 1.5)
        val atOneToOne = StrokeSmoother.smoothAll(path, 0.8f, 1f)
        assertTrue(atOneToOne.size > 100, "the reference stroke is only ${atOneToOne.size} points")

        for (zoom in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val s = StrokeSmoother(0.8f, zoom)
            val out = ArrayList<PenSample>()
            path.forEach { out.addAll(s.add(it)) }
            out.addAll(s.finish())

            for (p in out) {
                assertTrue(p.x.isFinite(), "zoom $zoom released a non-finite x: $p")
                assertTrue(p.y.isFinite(), "zoom $zoom released a non-finite y: $p")
            }
            // Read as 1, so the result is not merely finite, it is THE SAME STROKE at 1:1.
            assertEquals(atOneToOne, out, "zoom $zoom is not the 1:1 stroke")
            assertEquals(0, s.droppedSamples, "a bad zoom is not a bad sample")
        }
    }

    /**
     * The review's own reproduction (M1), as a permanent test. One NaN sample in the middle of a
     * stroke used to delete everything after it: `segLen` became NaN, `along <= segLen` is then
     * false for EVERY value of `along`, and the pen-up catch-up (`hypot(...) > 1e-9`, also false for
     * NaN) never pushed the lift-off point either — so the stroke silently stopped following the pen.
     *
     * POLICY: FAITHFUL. The bad sample is dropped and the REST of the stroke is kept, because a
     * missing pen sample is an ordinary event (Android coalesces a MotionEvent batch anyway) and the
     * resampler already resamples across the gap, so "the device never reported that point" and
     * "that point was not a number" are the same stroke. Refusing the whole recording instead would
     * throw away the 999 good samples over one bad float; letting the NaN through is what deleted them.
     */
    @Test
    fun oneNonFiniteSampleInTheMiddleCostsThatSampleAndNothingElse() {
        val path = drawAlong(listOf(0.0 to 0.0, 400.0 to 40.0), speed = 300.0, wobbleAmp = 1.5)
        val bad = path.size / 2

        for (broken in listOf(
            PenSample(path[bad].x, Float.NaN, path[bad].timeMs, pressure = path[bad].pressure),
            PenSample(Float.NaN, path[bad].y, path[bad].timeMs, pressure = path[bad].pressure),
            PenSample(path[bad].x, path[bad].y, Double.NaN, pressure = path[bad].pressure),
            PenSample(path[bad].x, path[bad].y, Double.POSITIVE_INFINITY, pressure = path[bad].pressure),
        )) {
            val s = StrokeSmoother(0.8f)
            val out = ArrayList<PenSample>()
            path.forEachIndexed { i, p -> out.addAll(s.add(if (i == bad) broken else p)) }
            out.addAll(s.finish())

            // Not silent: the drop is counted, so a caller replaying a file can say so.
            assertEquals(1, s.droppedSamples, "the broken sample $broken was not counted")

            // What happens to the samples on EITHER SIDE, precisely.
            val clean = path.filterIndexed { i, _ -> i != bad }
            assertEquals(
                StrokeSmoother.smoothAll(clean, 0.8f), out,
                "the stroke must be exactly the stroke without that one sample",
            )
            // The stroke before it and the stroke after it are both still there, and the line still
            // ends where the pen really lifted — the two things the NaN used to destroy.
            assertTrue(out.size > 100, "only ${out.size} points survived")
            for (p in out) {
                assertTrue(p.x.isFinite() && p.y.isFinite() && p.timeMs.isFinite(), "released $p")
            }
            assertEquals(path.first().x, out.first().x, 1e-3f, "the stroke no longer starts at the first sample")
            assertEquals(path.last().x, out.last().x, 1e-3f, "the stroke no longer reaches the last sample")
        }
    }

    /** A stroke made only of bad samples is no stroke, and says so, rather than one NaN point. */
    @Test
    fun aStrokeOfNothingButNonFiniteSamplesIsEmpty() {
        val s = StrokeSmoother(0.5f)
        s.add(PenSample(Float.NaN, 0f, 0.0))
        s.add(PenSample(0f, Float.NaN, 4.0))
        val out = s.add(PenSample(0f, 0f, Double.NaN))
        assertEquals(3, s.droppedSamples)
        assertEquals(emptyList(), out)
        assertEquals(emptyList(), s.finish())
    }

    /** A sensor channel reading NaN is an ABSENT sensor, and must not be mistaken for a bad sample. */
    @Test
    fun anAbsentSensorIsNotAnUnplaceableSample() {
        val s = PenSample(3f, 4f, 5.0, tilt = Float.NaN, azimuth = Float.NaN, barrel = Float.NaN)
        assertTrue(s.isPlaceable, "NaN channels are how an absent sensor is spelled, not a broken point")
        assertTrue(PenSample(0f, 0f, 0.0).isPlaceable)
        assertTrue(!PenSample(Float.NEGATIVE_INFINITY, 0f, 0.0).isPlaceable)
        assertTrue(!PenSample(0f, Float.POSITIVE_INFINITY, 0.0).isPlaceable)
    }

    private fun distToSegment(p: PenSample, a: PenSample, b: PenSample): Double {
        val ax = a.x.toDouble(); val ay = a.y.toDouble()
        val bx = b.x.toDouble(); val by = b.y.toDouble()
        val px = p.x.toDouble(); val py = p.y.toDouble()
        val vx = bx - ax; val vy = by - ay
        val l2 = vx * vx + vy * vy
        val t = if (l2 == 0.0) 0.0 else (((px - ax) * vx + (py - ay) * vy) / l2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * vx), py - (ay + t * vy))
    }
}
