package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.Tool
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.DabLook
import cc.joycreator.joybrush.core.paint.DabPlacer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BrushDabberTest {

    // The two shipped example brushes, byte for byte — commonTest cannot open a file, so if one of
    // these is edited, edit joybrush/brushes/{ink,pencil}/brush.json to match. Same as BrushTest.

    private val inkJson: String = """
        {
          "format": "joybrush.brush",
          "version": 1,
          "id": "joybrush.ink",
          "name": "Ink",
          "engine": "stamp",
          "tip": {
            "corner": 2,
            "hardness": { "base": 0.95 }
          },
          "size": {
            "base": 6,
            "inputs": [ { "input": "pressure", "curve": [ [0, 0.15], [1, 1] ] } ]
          },
          "opacity": { "base": 1 },
          "flow": { "base": 1 },
          "spacing": 0.04,
          "accumulate": "wash",
          "blend": "normal",
          "smoothing": 0.35,
          "license": "CC0"
        }
    """.trimIndent() + "\n"

    private val pencilJson: String = """
        {
          "format": "joybrush.brush",
          "version": 1,
          "id": "joybrush.pencil",
          "name": "Pencil",
          "engine": "stamp",
          "tip": {
            "corner": 2,
            "aspect": 0,
            "followDirection": false
          },
          "size": {
            "base": 3,
            "inputs": [ { "input": "pressure", "curve": [ [0, 0.6], [1, 1] ] } ]
          },
          "opacity": {
            "base": 1,
            "inputs": [ { "input": "pressure", "curve": [ [0, 0.1], [1, 0.9] ] } ]
          },
          "paperGrain": {
            "enabled": true,
            "source": "cloud",
            "depth": { "base": 1, "inputs": [ { "input": "pressure", "curve": [ [0, 0.2], [1, 0.9] ] } ] },
            "edge": 0.25,
            "tiltGradient": 0.6
          },
          "smoothing": 0.2,
          "license": "CC0"
        }
    """.trimIndent() + "\n"

    private val ink: BrushPreset = BrushJson.decode(inkJson)
    private val pencil: BrushPreset = BrushJson.decode(pencilJson)

    private fun curve(vararg points: Pair<Float, Float>, input: BrushInput = BrushInput.pressure) =
        InputCurve(input, points.map { listOf(it.first, it.second) })

    /** One sample on a straight run: [n] steps of [step] px, [ms] ms apart, at a fixed pressure. */
    private fun line(n: Int, step: Float = 10f, ms: Double = 10.0, pressure: Float = 1f): List<PenSample> =
        (0..n).map { PenSample(it * step, 0f, it * ms, pressure = pressure) }

    /**
     * A brush whose tip angle IS the speed, so the speed filter can be read off a dab's angle: the
     * curve is the identity into 0..3000 px/s and it is ADDED to a base of 0, so the angle a dab
     * carries is `toRadians(speed)`. It goes through the same 256-entry table as any other curve, so
     * the reading is good to a part in 512 — plenty for a filter, not enough to pin a random draw.
     */
    private fun speedProbe(): BrushPreset = BrushPreset(
        id = "probe", name = "probe", size = Param(1f), spacing = 0.04f,
        tip = TipSpec(angle = Param(0f, listOf(curve(0f to 0f, 1f to 3000f, input = BrushInput.speed)), "add")),
    )

    // ---- SplitMix -------------------------------------------------------------------------------

    /**
     * There is no golden value pinned here, on purpose: the numbers can only be produced by running
     * this code, and a hand-copied 64-bit constant is exactly the sort of thing a later edit to the
     * mixer's constants would leave behind as a lie. What is pinned is the property that matters —
     * one seed gives one sequence for as long as the code stands, and the float view of it is the top
     * 24 bits of the long view.
     */
    @Test
    fun theSameSeedGivesTheSameNumbersForever() {
        val a = SplitMix(20260928L)
        val b = SplitMix(20260928L)
        val fromA = List(64) { a.nextLong() }
        assertEquals(fromA, List(64) { b.nextLong() })

        // nextFloat is the top 24 bits over 2^24, and costs exactly one long draw.
        val floats = SplitMix(7L)
        val longs = SplitMix(7L)
        val expected = (0 until 16).map { (longs.nextLong() ushr 40).toFloat() / 16777216f }
        val actual = (0 until 16).map { floats.nextFloat() }
        assertEquals(expected, actual)
        assertTrue(actual.all { it >= 0f && it < 1f }, "out of 0..1: $actual")

        // It is a mixer, not a counter. The 64-bit stream must not repeat: 4096 draws out of 2^64 is
        // a birthday collision of about one part in 10^14, so this asks a real question.
        //
        // The 24-bit PROJECTION must not be asked the same one. 4096 draws out of 2^24 gives
        // 4096² / 2 / 2^24 ≈ 0.5 expected collisions, so two of them landing on the same float is a
        // coin flip rather than a defect. The first version of this test asserted 4096 distinct
        // floats and duly failed on a genuine, correct collision — a flaky assertion, not a bug.
        val gen = SplitMix(99L)
        val longDraws = ArrayList<Long>(4096)
        val floatDraws = ArrayList<Float>(4096)
        for (i in 0 until 4096) {
            val l = gen.nextLong()
            longDraws.add(l)
            floatDraws.add((l ushr 40).toFloat() / 16777216f) // exactly what nextFloat does
        }
        assertEquals(4096, longDraws.toSet().size, "a 64-bit draw repeated inside 4096")
        // The float view may repeat, but only about as often as a 24-bit projection should: a broken
        // mixer that lost its high bits would collide hundreds of times over.
        val collisions = 4096 - floatDraws.toSet().size
        assertTrue(collisions < 20, "far more collisions than a 24-bit projection explains: $collisions")
        assertTrue(floatDraws.count { it < 0.01f } > 0, "never got near the bottom of the range")
        assertTrue(floatDraws.count { it > 0.99f } > 0, "never got near the top of the range")

        // A different seed is a different stream, from the very first draw.
        assertNotEquals(SplitMix(1L).nextLong(), SplitMix(2L).nextLong())
        assertNotEquals(List(4) { SplitMix(1L).nextLong() }, List(4) { SplitMix(2L).nextLong() })
    }

    // ---- the stream is drawn at a fixed rate ---------------------------------------------------

    /**
     * The mirror image of the bug this whole class exists for: [BrushDabber.look] is asked ONCE per
     * dab, so it must consume exactly three draws — the `random` input, the size jitter, the angle
     * jitter — after the single one the constructor took for the stroke.
     *
     * Both jitters are read back bit-exactly, which is why the probe uses the jitters and not a
     * curve: a curve goes through a 256-entry table and would quantise the value away. The three
     * draws are consumed in Decision 1's order — `random`, size, angle — so the first of each group
     * is spent and unobserved. The expressions below are the dabber's own, written out again, so the
     * comparison is exact: what is pinned is the POSITION in the stream, not the float arithmetic.
     */
    @Test
    fun nDabsDrawExactlyThreeFloatsEach() {
        val probe = BrushPreset(
            id = "jitter", name = "jitter", size = Param(1f), spacing = 0.04f,
            tip = TipSpec(minPx = 0.25f), sizeJitter = 1f, angleJitter = 180f,
        )
        fun sizeJittered(j: Float): Float = 1f * (1f + 1f * (2f * j - 1f))

        val seed = 4242L
        val replay = SplitMix(seed)
        replay.nextFloat() // the stroke's own draw, taken in the constructor
        val dabber = BrushDabber(probe, seed)
        for (i in 0 until 12) {
            replay.nextFloat() // the FIRST of the three: the `random` input, which is not observable
            val sizeDraw = replay.nextFloat() // the SECOND of the three: the size jitter
            val angleDraw = replay.nextFloat() // the THIRD: the angle jitter
            val look = dabber.look(PenSample(i * 10f, 0f, i * 10.0), i * 10f, i)
            assertEquals(max(sizeJittered(sizeDraw), 0.25f), look.radius * 2f, "dab $i size draw")
            assertEquals(degToRad(180f) * (2f * angleDraw - 1f), look.angle, "dab $i angle draw")
        }

        // A brush that uses NEITHER jitter must come out the same size every time — the stream moves
        // on regardless, and nothing may leak out of it into a setting the file did not ask to jitter.
        val noJitter = BrushDabber(ink, seed)
        val plainRadii = (0 until 4).map {
            noJitter.look(PenSample(it * 10f, 0f, it * 10.0, pressure = 1f), it * 10f, it).radius
        }
        assertTrue(plainRadii.all { it == 3f }, "a brush with no jitter was moved by the stream: $plainRadii")

        // What the loop above really pins is the constructor's single draw: drop `replay.nextFloat()`
        // and every comparison in it is one float out of step, because the dabber is one ahead. The
        // other half of decision 1 — that the three draws are taken even when a jitter is off — is NOT
        // asserted, and cannot be: a no-jitter brush exposes only curves, and a curve's value comes
        // out of a 256-entry table, so the draw cannot be read back exactly. See the report.
    }

    // ---- the whole stroke, through the placer ---------------------------------------------------

    @Test
    fun theSameSeedReplaysTheSameDabsAndADifferentOneDoesNot() {
        val brush = ink.copy(sizeJitter = 0.3f, angleJitter = 20f)
        fun run(seed: Long, chunks: List<List<PenSample>>): List<Dab> {
            val d = BrushDabber(brush, seed)
            val placer = DabPlacer(spacing = d.spacing, look = d::look)
            return chunks.flatMap { placer.add(it) }
        }

        val samples = line(20)
        val whole = run(4242L, listOf(samples))
        assertTrue(whole.size > 20, "only ${whole.size} dabs — the test would prove nothing")
        assertTrue(whole.all { it.radius.isFinite() && it.angle.isFinite() && it.cap in 0f..1f })

        // Same seed, same stroke, the same dabs — every channel, bit for bit.
        assertEquals(whole, run(4242L, listOf(samples)))

        // And the brush keeping state does not make a stroke depend on the device's event rate: the
        // placer hands the dabber the same interpolated samples however the batched-up input is cut,
        // so the speed filter and the direction tracker see the same sequence either way.
        assertEquals(whole, run(4242L, samples.map { listOf(it) }))
        assertEquals(whole, run(4242L, samples.chunked(3)))
        assertEquals(whole, run(4242L, samples.chunked(7)))

        // A different seed scatters differently. sizeJitter 0.3 over this many dabs cannot coincide.
        assertNotEquals(whole, run(1L, listOf(samples)))
    }

    // ---- size -----------------------------------------------------------------------------------

    @Test
    fun inkIsThinAtNoPressureAndFullAtFullPressure() {
        val dabber = BrushDabber(ink, 1L)
        // 6 px × 0.15 = 0.9 px of DIAMETER, but tip.minPx is 1, so the floor wins: 0.5 px of radius.
        val light = dabber.look(PenSample(0f, 0f, 0.0, pressure = 0f), 0f, 0)
        assertEquals(0.5f, light.radius)
        // With the floor out of the way the curve's own answer shows: 6 × 0.15 / 2 = 0.45. The
        // tolerance is one part in a million because 0.15f is not 0.15 — 6 × 0.15f is 0.90000004f,
        // and half of that is 0.45000002f. The other radius asserts here are exact by construction
        // (a minPx floor of 1 makes 0.5 exactly), which is why only this one needs a tolerance.
        val floored = BrushDabber(ink.copy(tip = ink.tip.copy(minPx = 0.25f)), 1L)
        assertEquals(0.45f, floored.look(PenSample(0f, 0f, 0.0, pressure = 0f), 0f, 0).radius, 1e-6f)
        // Full pressure is the base diameter halved, and the floor is nowhere near it.
        val full = dabber.look(PenSample(0f, 0f, 10.0, pressure = 1f), 10f, 1)
        assertEquals(3f, full.radius)
        // Halfway: 6 × (0.15 + 0.85 × 0.5) / 2 = 6 × 0.575 / 2 = 1.725.
        val half = dabber.look(PenSample(0f, 0f, 20.0, pressure = 0.5f), 20f, 2)
        assertEquals(1.725f, half.radius, 1e-3f)
        // Ink has no flow or opacity curves, so both are 1 — and asking again is legal, it only
        // moves the random stream along.
        assertEquals(1f, light.flow)
        assertEquals(1f, light.cap)
    }

    @Test
    fun aSizeWithNoCurvesIsTheBaseWhateverThePenSays() {
        val plain = ink.copy(size = Param(6f))
        val dabber = BrushDabber(plain, 1L)
        for (p in listOf(0f, 0.5f, 1f, Float.NaN)) {
            assertEquals(3f, dabber.look(PenSample(0f, 0f, 0.0, pressure = p), 0f, 0).radius, "pressure $p")
        }
    }

    @Test
    fun minPxKeepsAStrokeVisibleWhenTheFileAsksForNoSize() {
        // A curve of zeros, and one of negatives: the floor is the only thing between this brush and
        // a stroke of radius 0, and it is applied before the halving.
        val zero = ink.copy(size = Param(0f, listOf(curve(0f to 0f, 1f to 0f)), combine = "add"))
        assertEquals(0.5f, BrushDabber(zero, 1L).look(PenSample(0f, 0f, 0.0), 0f, 0).radius)
        val negative = ink.copy(size = Param(1f, listOf(curve(0f to -5f, 1f to -5f)), combine = "add"))
        assertEquals(0.5f, BrushDabber(negative, 1L).look(PenSample(0f, 0f, 0.0), 0f, 0).radius)
    }

    // ---- flow, cap, accumulate -------------------------------------------------------------------

    @Test
    fun aWashBrushGivesEveryDabItsOwnCapFromTheOpacityCurve() {
        assertEquals("wash", pencil.accumulate, "the shipped Pencil is a wash brush")
        val dabber = BrushDabber(pencil, 1L)
        // 1 × (0.1 + 0.8p): the cap is a ceiling that MOVES, which is the point of asking for it per
        // dab — a stroke given one cap for the whole of itself could not fade with pressure.
        assertEquals(0.1f, dabber.look(PenSample(0f, 0f, 0.0, pressure = 0f), 0f, 0).cap, 1e-3f)
        assertEquals(0.5f, dabber.look(PenSample(0f, 0f, 10.0, pressure = 0.5f), 10f, 1).cap, 1e-3f)
        assertEquals(0.9f, dabber.look(PenSample(0f, 0f, 20.0, pressure = 1f), 20f, 2).cap, 1e-3f)
        // Pencil's flow is an untouched base of 1.
        assertEquals(1f, dabber.look(PenSample(0f, 0f, 30.0), 30f, 3).flow)
    }

    @Test
    fun aBuildUpBrushCapsEveryDabAtOneAndFixesOpacityAtTheFirst() {
        // base 0.5 with a pressure curve ADDED on top, so the file's base and its first dab's answer
        // are different numbers and this test can tell which one it is looking at.
        val buildUp = pencil.copy(
            accumulate = "buildup",
            opacity = Param(0.5f, listOf(curve(0f to 0f, 1f to 0.5f)), combine = "add"),
        )
        val dabber = BrushDabber(buildUp, 1L)
        assertEquals(0.5f, dabber.strokeOpacity, 1e-3f, "before the first dab, the file's own base")
        val looks = (0 until 4).map { i ->
            dabber.look(PenSample(i * 10f, 0f, i * 10.0, pressure = 0.3f), i * 10f, i)
        }
        assertTrue(looks.all { it.cap == 1f }, "a build-up dab is never capped: ${looks.map { it.cap }}")
        // 0.5 + (0 + 0.5 × 0.3) = 0.65, taken once.
        assertEquals(0.65f, dabber.strokeOpacity, 1e-3f)
        // …and it stays there, whatever the pen does afterwards: it is applied on commit.
        dabber.look(PenSample(100f, 0f, 100.0, pressure = 1f), 100f, 4)
        assertEquals(0.65f, dabber.strokeOpacity, 1e-3f)
        // A wash stroke has no whole-stroke opacity to apply at all.
        assertEquals(1f, BrushDabber(pencil, 1L).strokeOpacity)
    }

    @Test
    fun hardnessIsTheStrokeUniformAndIsTakenAtTheFirstDab() {
        val brush = ink.copy(tip = ink.tip.copy(hardness = Param(0.5f, listOf(curve(0f to 0f, 1f to 0.8f)), "add")))
        val dabber = BrushDabber(brush, 1L)
        assertEquals(0.5f, dabber.strokeHardness, 1e-3f, "before the first dab, the file's own base")
        dabber.look(PenSample(0f, 0f, 0.0, pressure = 0.2f), 0f, 0)
        assertEquals(0.66f, dabber.strokeHardness, 1e-3f) // 0.5 + 0.8 × 0.2
        dabber.look(PenSample(10f, 0f, 10.0, pressure = 1f), 10f, 1)
        assertEquals(0.66f, dabber.strokeHardness, 1e-3f, "a shader uniform is set once")
        // Out of range is clamped: it is a uniform in a gradient, and a NaN one would poison the
        // batch. A first dab at full pressure would otherwise ask for 0.5 + 0.8 = 1.3.
        val slammed = BrushDabber(brush, 1L)
        slammed.look(PenSample(0f, 0f, 0.0, pressure = 1f), 0f, 0)
        assertEquals(1f, slammed.strokeHardness)
        // An untouched brush is its own base: ink's tip.hardness is 0.95.
        assertEquals(0.95f, BrushDabber(ink, 1L).strokeHardness)
    }

    // ---- angle ----------------------------------------------------------------------------------

    @Test
    fun aTipThatFollowsDirectionTurnsToFaceTheWayItIsMoving() {
        val brush = BrushPreset(id = "f", name = "f", size = Param(1f), tip = TipSpec(followDirection = true))
        val dabber = BrushDabber(brush, 1L)
        // Straight UP (+y), which is +PI/2 — not down the +x axis the line() helper draws.
        val up = (0..8).map { PenSample(0f, it * 10f, it * 10.0) }
        val angles = up.mapIndexed { i, s -> dabber.look(s, i * 10f, i).angle }
        // The tracker has no direction before the pen has moved anywhere, so the tip starts along +x.
        assertEquals(0f, angles[0], 1e-4f)
        // Its first real movement seeds it directly, so from the second dab on it is the movement.
        for (a in angles) assertTrue(a >= -1e-4f && a <= (PI / 2).toFloat() + 1e-4f, "swung the wrong way: $angles")
        for (a in angles.drop(1)) assertTrue(abs(a - (PI / 2).toFloat()) < 1e-4f, "not facing +y: $angles")
        // A brush that does not follow ignores the movement entirely — Pencil's file, unmodified.
        val still = BrushDabber(pencil, 1L)
        val stillAngles = line(5).mapIndexed { i, s -> still.look(s, i * 10f, i).angle }
        assertTrue(stillAngles.all { it == 0f }, "$stillAngles")
    }

    // ---- speed ----------------------------------------------------------------------------------

    @Test
    fun speedConvergesOnTheRateTheNibIsReallyMovingAt() {
        val dabber = BrushDabber(speedProbe(), 1L)
        val seen = ArrayList<Float>()
        for (i in 0 until 60) {
            seen.add(dabber.look(PenSample(i * 10f, 0f, i * 10.0), i * 10f, i).angle)
        }
        val converged = degToRad(1000f) // 10 px every 10 ms is 1000 px/s
        // The first dab has no previous dab to measure against, so it is at rest.
        assertEquals(0f, seen[0], 1e-6f)
        // Then an exponential approach: s(n) = 1000 × (1 − e^(−0.2n)), which after 59 steps is
        // 999.99 px/s. Slow, because the filter's time constant is 50 ms and dabs are 10 ms apart.
        assertTrue(abs(seen.last() - converged) < 0.01f, "ended at ${seen.last()}, want $converged")
        for (i in 1 until seen.size) assertTrue(seen[i] >= seen[i - 1], "not monotone: $seen")
    }

    @Test
    fun aPenThatHasNotMovedIsAPenAtRestAndOneDabSharingATimestampKeepsTheOldSpeed() {
        // Every dab at the same place: the raw speed is 0, so the filter never leaves 0.
        val still = BrushDabber(speedProbe(), 1L)
        for (i in 0 until 5) {
            assertEquals(0f, still.look(PenSample(50f, 0f, i * 10.0), 0f, i).angle, 1e-6f, "dab $i")
        }
        // Two dabs sharing a timestamp cannot be divided by a time of zero. The first of the pair
        // sets 10 px/10 ms = 1000 px/s raw, and the second must carry the speed that gives rather
        // than zero it and not come out as a NaN.
        val paused = BrushDabber(speedProbe(), 1L)
        paused.look(PenSample(0f, 0f, 0.0), 0f, 0)
        val moving = paused.look(PenSample(10f, 0f, 10.0), 10f, 1).angle
        val sameMoment = paused.look(PenSample(20f, 0f, 10.0), 20f, 2).angle
        assertTrue(moving > 0f, "the filter never started")
        assertEquals(moving, sameMoment, 1e-6f, "a repeated timestamp threw the speed away")
        // A time that is not a number at all cannot be divided by either; the speed stands still.
        val noTime = BrushDabber(speedProbe(), 1L)
        for (i in 0 until 3) {
            assertEquals(0f, noTime.look(PenSample(i * 10f, 0f, Double.NaN), i * 10f, i).angle, 1e-6f, "dab $i")
        }
    }

    // ---- inputs a device cannot report ----------------------------------------------------------

    @Test
    fun aChannelTheDeviceCannotReportContributesNothingAndDoesNotZeroTheBrush() {
        val brush = ink.copy(size = Param(6f, listOf(curve(0f to 0.5f, 1f to 1f, input = BrushInput.tilt))))
        val dabber = BrushDabber(brush, 1L)
        // NaN tilt: the curve is skipped, so the Param is its base — 6 px, radius 3. NOT 0, which is
        // what "clamp the input" would give.
        assertEquals(3f, dabber.look(PenSample(0f, 0f, 0.0, tilt = Float.NaN), 0f, 0).radius, 1e-3f)
        // Upright: 6 × 0.5 = 3 px of diameter, radius 1.5.
        assertEquals(1.5f, dabber.look(PenSample(0f, 0f, 10.0, tilt = 0f), 10f, 1).radius, 1e-3f)

        // Every channel the DEVICE reports, and none of them reported: pressure, tilt, lean and
        // barrel are NaN, and every curve is skipped, so the base stands. This is the whole point of
        // a NaN reading: not a zero, and not a clamp to the middle of the curve.
        //
        // Speed is deliberately NOT in this list — it is not a device reading. See the test below.
        val everything = ink.copy(
            size = Param(6f, listOf(
                curve(0f to 0f, 1f to 0f, input = BrushInput.tilt),
                curve(0f to 0f, 1f to 0f, input = BrushInput.barrel),
                curve(0f to 0f, 1f to 0f, input = BrushInput.lean),
                curve(0f to 0f, 1f to 0f, input = BrushInput.pressure),
            )),
        )
        val saysNothing = PenSample(
            x = 0f, y = 0f, timeMs = 0.0, pressure = Float.NaN, tilt = Float.NaN,
            azimuth = Float.NaN, barrel = Float.NaN,
        )
        val bare = BrushDabber(everything, 1L).look(saysNothing, 0f, 0)
        assertEquals(3f, bare.radius, 1e-3f, "every curve skipped means the base stands")
        assertTrue(bare.angle.isFinite() && bare.flow.isFinite() && bare.cap in 0f..1f)
        // …and one that IS readable is not mistaken for a missing one: pressure 1 zeroes the size,
        // so this is the opposite answer and the same base.
        assertEquals(0.5f, BrushDabber(everything, 1L)
            .look(saysNothing.copy(pressure = 1f), 0f, 0).radius, 1e-3f)

        // A finger reports no tilt, no azimuth and no barrel, and still draws a stroke.
        val fingerBrush = BrushDabber(ink, 1L)
        val finger = DabPlacer(spacing = 0.04f, look = fingerBrush::look).add(
            listOf(PenSample(0f, 0f, 0.0, tool = Tool.FINGER), PenSample(20f, 0f, 10.0, tool = Tool.FINGER)),
        )
        assertTrue(finger.isNotEmpty() && finger.all { it.radius > 0f })
    }

    @Test
    fun speedIsAReadingThisClassMakesAndIsNeverMissing() {
        // The other kind of input is not a channel the pen reports: speed is this class's own filter
        // output, and it is always finite — 0 on a stationary dab, because a nib that has not moved
        // really is at a standstill. A speed curve is therefore EVALUATED, not skipped, and a brush
        // that scales its size with speed draws at minPx until the pen moves. That is the file's
        // instruction being obeyed, not an absent reading being invented.
        val sizeBySpeed = ink.copy(size = Param(6f, listOf(curve(0f to 0f, 1f to 1f, input = BrushInput.speed))))
        val dabber = BrushDabber(sizeBySpeed, 1L)
        val radii = (0 until 8).map { i ->
            dabber.look(PenSample(i * 20f, 0f, i * 10.0), i * 20f, i).radius
        }
        // The first dab has no previous dab to measure against: 6 × 0 = 0, floored to minPx.
        assertEquals(0.5f, radii.first(), "a stationary nib is at the floor, not at the base")
        // 20 px per 10 ms is a raw 2000 px/s, and the filter is at 1500 after 7 steps: 6 × 0.5 = 1.5.
        assertTrue(radii.last() > 1f, "a speed curve never came alive: $radii")
        for (i in 1 until radii.size) assertTrue(radii[i] >= radii[i - 1], "not monotone: $radii")
    }

    // ---- curves that are odd but legal ----------------------------------------------------------

    @Test
    fun aCurveInTheWrongOrderOrWithOnePointStillBehaves() {
        // Out of order: points are sorted when the curve is built, so this is ink's own curve.
        val reversed = ink.copy(size = Param(6f, listOf(curve(1f to 1f, 0f to 0.15f))))
        val straight = ink.copy(size = Param(6f, listOf(curve(0f to 0.15f, 1f to 1f))))
        val a = BrushDabber(reversed, 1L)
        val b = BrushDabber(straight, 1L)
        for (p in listOf(0f, 0.25f, 0.5f, 1f)) {
            val one = a.look(PenSample(0f, 0f, 0.0, pressure = p), 0f, 0).radius
            val two = b.look(PenSample(0f, 0f, 0.0, pressure = p), 0f, 0).radius
            assertEquals(one, two, "pressure $p")
        }
        // One point: the curve is that point's value everywhere, so the size never changes.
        val single = ink.copy(
            size = Param(6f, listOf(InputCurve(BrushInput.pressure, listOf(listOf(0.5f, 0.5f))))),
        )
        val dabber = BrushDabber(single, 1L)
        for (p in listOf(0f, 0.5f, 1f)) {
            assertEquals(1.5f, dabber.look(PenSample(0f, 0f, 0.0, pressure = p), 0f, 0).radius, 1e-3f, "pressure $p")
        }
    }

    // ---- what a stroke does that is not a stroke -------------------------------------------------

    @Test
    fun edgesThatAreNotDabs() {
        // A stroke of one sample is one dab, and a sample that does not move is not a second one.
        val oneBrush = BrushDabber(ink, 1L)
        val one = DabPlacer(spacing = 0.04f, look = oneBrush::look).add(listOf(PenSample(0f, 0f, 0.0)))
        assertEquals(1, one.size)
        val stillBrush = BrushDabber(ink, 1L)
        val still = DabPlacer(spacing = 0.04f, look = stillBrush::look)
        val a = PenSample(5f, 5f, 0.0, pressure = 0.4f)
        assertEquals(1, still.add(listOf(a, a, a)).size, "a zero-length segment makes no dab")

        // A stroke nobody drew: both whole-stroke numbers are still safe to read.
        val untouched = BrushDabber(ink, 1L)
        assertEquals(0.95f, untouched.strokeHardness)
        assertEquals(1f, untouched.strokeOpacity)
        assertEquals(0.04f, untouched.spacing)

        // Calling look twice for one dab is NOT the same answer, on purpose: the stream has moved.
        // The placer asks once, and that is the whole contract. The angle is the safe channel to
        // read — it has no floor, so unlike a jittered radius it cannot flatten two draws into one.
        val twice = BrushDabber(ink.copy(angleJitter = 180f), 1L)
        val s = PenSample(0f, 0f, 0.0, pressure = 1f)
        assertNotEquals(twice.look(s, 0f, 0).angle, twice.look(s, 0f, 0).angle)

        // A NaN in the file's own angle does not take the tracker's direction down with it.
        val badAngle = ink.copy(tip = ink.tip.copy(angle = Param(Float.NaN), followDirection = true))
        val turned = BrushDabber(badAngle, 1L)
        val first = turned.look(PenSample(0f, 0f, 0.0), 0f, 0).angle
        val second = turned.look(PenSample(0f, 40f, 10.0), 40f, 1).angle
        assertTrue(first.isFinite() && second.isFinite() && second > 1f, "angles were $first then $second")
    }

    @Test
    fun everyDabIsSomethingThePlacerCanUse() {
        // The contract this leans on: NaN cap means "use the placer's cap", not "no cap".
        assertTrue(DabLook(1f).cap.isNaN())

        val brush = ink.copy(
            size = Param(6f, listOf(curve(0f to 0f, 1f to 0f, input = BrushInput.pressure)), combine = "add"),
            tip = ink.tip.copy(angle = Param(Float.NaN), minPx = 0.25f),
            opacity = Param(Float.NaN), flow = Param(Float.NaN),
        )
        val dabber = BrushDabber(brush, 1L)
        val dabs = DabPlacer(spacing = 0.04f, look = dabber::look).add(line(6))
        assertTrue(dabs.isNotEmpty())
        for (d in dabs) {
            assertTrue(d.radius.isFinite() && d.radius >= 0f, "radius ${d.radius}")
            assertTrue(d.angle.isFinite(), "angle ${d.angle}")
            assertTrue(d.flow in 0f..1f, "flow ${d.flow}")
            assertTrue(d.cap in 0f..1f, "cap ${d.cap}")
        }
        // A NaN opacity hands the ceiling back to the placer instead of clamping a stroke to nothing,
        // and a NaN flow draws at full strength rather than not at all.
        assertEquals(1f, dabs.first().cap)
        assertEquals(1f, dabs.first().flow)
        assertEquals(3f, dabs.first().radius)
    }

    @Test
    fun oneCallIsOneDab() {
        // The one thing this class is for, stated as a test: the brush is asked exactly once per dab,
        // so a stateful brush does not double-count. (PaintTest pins the placer's half of it.)
        var calls = 0
        val dabber = BrushDabber(ink, 1L)
        val dabs = DabPlacer(spacing = 0.04f, look = { s, distance, i ->
            calls += 1
            dabber.look(s, distance, i)
        }).add(line(10))
        assertEquals(dabs.size, calls)
        assertTrue(calls > 10, "only $calls dabs — the test would prove nothing")
    }
}

/** Degrees to radians, matching `BrushDabber` and `StrokeSmoother`. */
private fun degToRad(deg: Float): Float = deg * PI.toFloat() / 180f
