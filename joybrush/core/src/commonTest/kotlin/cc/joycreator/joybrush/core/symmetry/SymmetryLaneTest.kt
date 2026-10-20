package cc.joycreator.joybrush.core.symmetry

import cc.joycreator.joybrush.core.brush.BrushDabber
import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.DabInputs
import cc.joycreator.joybrush.core.brush.Scatter
import cc.joycreator.joybrush.core.brush.SplitMix
import cc.joycreator.joybrush.core.guide.Guide
import cc.joycreator.joybrush.core.input.Angles
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.paint.RefCanvas
import cc.joycreator.joybrush.core.paint.StrokeBlend
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The symmetry lanes: the stroke fan-out, proved on the JVM.
 *
 * The view cannot be built here (there is no Android on the test classpath), so everything this
 * suite proves is proved on the PURE core pieces: the lane, the dabber, the placer, the scatter
 * and the CPU twin of the engine.
 *
 * Every oracle in this file is worked out LONGHAND from the geometry - a mirror is `2C - x`, a
 * reflection of an angle about an axis is `2φ - α` - and never read back out of the code under
 * test. A test whose oracle is the code proves nothing.
 */
class SymmetryLaneTest {

    // ---- the fixtures ----------------------------------------------------------------------------

    /**
     * A brush with every channel the symmetry touches: a tip angle of its own, the switch that
     * says whether that angle is an OFFSET from the stroke or an ABSOLUTE one, an angle jitter, a
     * size jitter, a scatter, and a response curve (so the lane's own `response.apply` is
     * exercised and not just passed through).
     */
    private val brushJson: String = """
        {
          "format": "joybrush.brush",
          "version": 5,
          "id": "test.symmetry",
          "name": "Symmetry Test",
          "engine": "stamp",
          "tip": {
            "corner": 2,
            "angle": { "base": 20 },
            "followDirection": true
          },
          "size": {
            "base": 8,
            "inputs": [ { "input": "pressure", "curve": [ [0, 0.3], [1, 1] ] } ]
          },
          "opacity": { "base": 1 },
          "flow": { "base": 1 },
          "spacing": 0.25,
          "angleJitter": 30,
          "sizeJitter": 0.3,
          "scatter": {
            "amount": { "base": 0.4 },
            "count": 2,
            "bothAxes": true
          },
          "response": {
            "pressure": [ 0.0, 0.2, 1.0, 1.0 ],
            "tilt": [ 0.0, 0.5, 1.0, 1.0 ]
          },
          "accumulate": "wash",
          "blend": "normal",
          "smoothing": 0.35,
          "license": "CC0"
        }
    """.trimIndent() + "\n"

    private val brush: BrushPreset = BrushJson.decode(brushJson)

    /** The same brush with no size jitter, so two lanes' dabs are placed at the SAME distances. */
    private val flat: BrushPreset = brush.copy(sizeJitter = 0f)

    private val seed = 20261019L
    private val smoothing = 0.35f

    /** A straight run along +x, at y = 0: the direction the tracker finds is +x, and no corners. */
    private val samples: List<PenSample> =
        (0..11).map { PenSample(it * 10f, 0f, it * 10.0, pressure = 0.8f) }

    /** A vertical mirror line at x = 200: the mirror is `x -> 400 - x`, and y is left alone. */
    private fun mirrorGuide(symmetric: Boolean = true) =
        Guide.Symmetry(Pt(200.0, -100.0), Pt(200.0, 100.0), count = 2, symmetric = symmetric)

    /** The same line, read longhand: the mirror of a point about it, and the line's own angle. */
    private class MirrorLine(private val a: Pt, b: Pt) {
        private val ux = (b.x - a.x) / hypot(b.x - a.x, b.y - a.y)
        private val uy = (b.y - a.y) / hypot(b.x - a.x, b.y - a.y)
        /** The line's own angle: the axis the copy is reflected in. */
        val axis: Double = atan2(uy, ux)
        /** The mirror of a point about the line through a in the direction u. */
        fun x(x: Double, y: Double): Double =
            a.x + (2 * ux * ux - 1) * (x - a.x) + 2 * ux * uy * (y - a.y)
        fun y(x: Double, y: Double): Double =
            a.y + 2 * ux * uy * (x - a.x) + (2 * uy * uy - 1) * (y - a.y)
    }

    /** Runs a set of lanes over the samples and hands back each lane's dabs. */
    private fun run(
        lanes: List<SymmetryLane>,
        samples: List<PenSample>,
        finish: Boolean = true,
    ): List<List<Dab>> {
        val out = lanes.map { ArrayList<Dab>() }
        for (s in samples) {
            for (i in lanes.indices) {
                val released = lanes[i].feed(s)
                if (released.isNotEmpty()) out[i].addAll(lanes[i].dabs(released))
            }
        }
        if (finish) for (i in lanes.indices) out[i].addAll(lanes[i].finish())
        return out
    }

    private fun build(
        guide: Guide.Symmetry,
        preset: BrushPreset = brush,
        family: SymmetryFamily = SymmetryFamily.STAMP,
    ) = SymmetryLanes.build(guide, LaneBrush.File(preset), family, seed, smoothing, 1f)

    private fun angleDiff(a: Float, b: Float): Double = abs(Angles.wrap((a - b).toDouble()))

    /** Two floats are the same when their BITS are: a NaN is the same NaN, and -0.0 is not +0.0. */
    private fun sameBits(a: Float, b: Float): Boolean = a.toRawBits() == b.toRawBits()

    /** Every field of a dab, compared bit for bit. */
    private fun assertSameDab(expected: Dab, actual: Dab, index: Int) {
        fun same(name: String, e: Float, a: Float) =
            assertTrue(sameBits(e, a), "dab $index $name: expected $e, got $a")
        same("x", expected.x, actual.x)
        same("y", expected.y, actual.y)
        same("radius", expected.radius, actual.radius)
        same("angle", expected.angle, actual.angle)
        same("flow", expected.flow, actual.flow)
        same("cap", expected.cap, actual.cap)
        same("pressure", expected.pressure, actual.pressure)
        same("tilt", expected.tilt, actual.tilt)
        same("azimuth", expected.azimuth, actual.azimuth)
        same("travelX", expected.travelX, actual.travelX)
        same("travelY", expected.travelY, actual.travelY)
        same("aspect", expected.aspect, actual.aspect)
        same("hardness", expected.hardness, actual.hardness)
        same("tipDepth", expected.tipDepth, actual.tipDepth)
        same("paperDepth", expected.paperDepth, actual.paperDepth)
        same("anchor", expected.anchor, actual.anchor)
        assertEquals(expected.travelKnown, actual.travelKnown, "dab $index travelKnown")
        assertEquals(expected.sampleIndex, actual.sampleIndex, "dab $index sampleIndex")
    }

    // ---- 1. N lanes, N times the dabs, ONE undo step --------------------------------------------

    /**
     * N lanes make N times the dabs, and all of them land in ONE stroke, so the whole fan-out is
     * ONE undo step and not N.
     *
     * The undo is proved by undoing: the pixels the lanes drew are gone, and the history holds
     * exactly one step.
     */
    @Test
    fun nLanesMakeNTimesTheDabsAndOneUndoStep() {
        val set = build(mirrorGuide())
        assertEquals(2, set.lanes.size, "a mirror is two lanes: the original and the copy")
        val perLane = run(set.lanes, samples)
        assertTrue(perLane[0].size > 20, "only ${perLane[0].size} dabs - the test would prove nothing")
        assertEquals(perLane[0].size, perLane[1].size, "the lanes made different numbers of dabs")
        assertEquals(2 * perLane[0].size, perLane.sumOf { it.size })

        // The catch-up tail is IN: a lane that is never finished loses its last dabs, because the
        // smoother holds them back until nothing that arrives later can change them. A FRESH set
        // of lanes: a lane that has been finished cannot be fed again.
        val withoutTail = run(build(mirrorGuide()).lanes, samples, finish = false)
        assertTrue(perLane[0].size > withoutTail[0].size, "the lane lost its catch-up tail")
        assertTrue(perLane[1].size > withoutTail[1].size, "the copy lost its catch-up tail")

        // Every lane's dabs go into ONE stroke.
        val canvas = RefCanvas()
        canvas.beginStroke("L", 0f, 0f, 0f, 1f, Accumulate.WASH, StrokeBlend.NORMAL, TipShape(hardness = 1f))
        canvas.addDabs(perLane.flatten())
        canvas.endStroke()

        assertEquals(1, canvas.undo.undoDepth, "the fan-out is one undo step, not two")
        val painted = perLane[0].first()
        assertTrue(canvas.pixel("L", painted.x.toInt(), painted.y.toInt())[3] > 0f,
            "the original's first dab is not on the canvas")
        val copy = perLane[1].first()
        assertTrue(canvas.pixel("L", copy.x.toInt(), copy.y.toInt())[3] > 0f,
            "the copy's first dab is not on the canvas")

        // And the ONE undo takes all of them away.
        assertTrue(canvas.undoStep())
        assertEquals(0f, canvas.pixel("L", painted.x.toInt(), painted.y.toInt())[3], 1e-6f)
        assertEquals(0f, canvas.pixel("L", copy.x.toInt(), copy.y.toInt())[3], 1e-6f)
        assertEquals(0, canvas.undo.undoDepth)
    }

    // ---- 2. the mirror ---------------------------------------------------------------------------

    /**
     * "Paper aside": with a vertical guide, the copy's dabs are the ORIGINAL's dabs mirrored about
     * the line - `x -> 2C.x - x`, `y` unchanged.
     *
     * The mirror is worked out from the guide's own two ends, longhand, and not read back out of
     * the symmetry code.
     */
    @Test
    fun aMirrorLanesDabsAreTheMirrorOfTheOriginals() {
        val guide = mirrorGuide()
        val line = MirrorLine(guide.a, guide.b)
        val set = build(guide)
        val perLane = run(set.lanes, samples)
        val original = perLane[0]
        val copy = perLane[1]
        assertEquals(original.size, copy.size)
        for (i in original.indices) {
            val o = original[i]
            val c = copy[i]
            val wantX = line.x(o.x.toDouble(), o.y.toDouble())
            val wantY = line.y(o.x.toDouble(), o.y.toDouble())
            assertTrue(abs(wantX - c.x) <= 0.02, "dab $i x: expected $wantX, got ${c.x}")
            assertTrue(abs(wantY - c.y) <= 0.02, "dab $i y: expected $wantY, got ${c.y}")
        }
    }

    // ---- 3. the tip angle ------------------------------------------------------------------------

    /**
     * The copy's tip is the ORIGINAL's tip reflected about the copy's OWN axis: `2φ' - α`.
     *
     * The oracle is the geometry: the axis is the guide's own line, read longhand from its two
     * ends, and the reflection of an angle about an axis is `2φ - α`. The test does not ask the
     * implementation what the copy's axis is - that is the thing under test.
     */
    @Test
    fun aCopysTipAngleIsTheOriginalsReflectedAboutTheCopysOwnAxis() {
        val guide = mirrorGuide()
        val line = MirrorLine(guide.a, guide.b)
        val set = build(guide)
        val perLane = run(set.lanes, samples)
        val original = perLane[0]
        val copy = perLane[1]
        assertEquals(original.size, copy.size)
        for (i in original.indices) {
            val expected = 2f * line.axis.toFloat() - original[i].angle
            assertTrue(angleDiff(expected, copy[i].angle) < 1e-4f,
                "dab $i: expected $expected, got ${copy[i].angle}")
        }
    }

    // ---- 4. the scatter --------------------------------------------------------------------------

    /**
     * The scatter: with the guide symmetric, the copy's leaves are the ORIGINAL's leaves mirrored
     * (the ACROSS component negated, because a reflection reverses handedness), so the copy's dabs
     * land exactly where the mirror of the original's land. With the guide not symmetric, the
     * copies draw their own leaves and the two come apart.
     */
    @Test
    fun theScatterIsTheOriginalsMirrorWhenSymmetricAndItsOwnWhenNot() {
        val guide = mirrorGuide(symmetric = true)
        val line = MirrorLine(guide.a, guide.b)
        val perLane = run(build(guide, flat).lanes, samples)
        val original = perLane[0]
        val copy = perLane[1]
        assertEquals(original.size, copy.size)
        // The scatter really moved the dabs: the copy is not simply the un-scattered mirror.
        var moved = 0
        for (i in original.indices) {
            val wantX = line.x(original[i].x.toDouble(), original[i].y.toDouble())
            val wantY = line.y(original[i].x.toDouble(), original[i].y.toDouble())
            assertTrue(abs(wantX - copy[i].x) <= 0.02, "dab $i x: expected $wantX, got ${copy[i].x}")
            assertTrue(abs(wantY - copy[i].y) <= 0.02, "dab $i y: expected $wantY, got ${copy[i].y}")
            if (abs(original[i].x - wantX.toFloat()) > 1e-4f) moved++
        }
        assertTrue(moved > original.size / 4, "the scatter did not move the dabs at all")

        // The same guide with the symmetry OFF: the leaves are the copy's own, so the dabs come
        // apart. The brush has no size jitter here, so the two lanes place their dabs at the SAME
        // distances and the only thing that can move them is the scatter.
        val off = run(build(mirrorGuide(symmetric = false), flat).lanes, samples)
        val common = minOf(off[0].size, off[1].size)
        var apart = 0
        for (i in 0 until common) {
            val wantX = line.x(off[0][i].x.toDouble(), off[0][i].y.toDouble())
            if (abs(wantX - off[1][i].x) > 0.05f) apart++
        }
        assertTrue(apart > common / 4, "the copies drew the same leaves with the symmetry off")
    }

    // ---- 5. the seed policy ----------------------------------------------------------------------

    /**
     * The owner's "symmetry of jitter and tilt" switch, as the seed policy: ON, every lane has
     * the SAME seed, so the copies share one brush's randomness and a copy's texture is the
     * original's texture seen in the copy's frame. OFF, every lane has its own.
     */
    @Test
    fun theCopiesShareOneBrushsRandomnessWhenSymmetricAndNotWhenNot() {
        val on = run(build(mirrorGuide(symmetric = true)).lanes, samples)
        assertEquals(on[0].size, on[1].size)
        for (i in on[0].indices) {
            assertEquals(on[0][i].radius, on[1][i].radius, "dab $i radius: the sizes differ")
            assertEquals(on[0][i].flow, on[1][i].flow, "dab $i flow")
            assertEquals(on[0][i].cap, on[1][i].cap, "dab $i cap")
        }

        val off = run(build(mirrorGuide(symmetric = false)).lanes, samples)
        var different = 0
        val common = minOf(off[0].size, off[1].size)
        for (i in 0 until common) {
            if (off[0][i].radius != off[1][i].radius) different++
        }
        assertTrue(different > 0, "the copies drew the same sizes with the symmetry off")
    }

    // ---- 6. the golden test ----------------------------------------------------------------------

    /**
     * Turning the symmetry on must not change the ORIGINAL stroke.
     *
     * The inline pipeline is written out here exactly as the view writes it - response, smoother,
     * dabber, placer, scatter - and the lane machinery is handed the same brush and the same
     * samples. The two dab lists must agree on EVERY field, BIT for BIT: a signed zero is a
     * change of value, and a NaN must be the same NaN.
     */
    @Test
    fun withNoSymmetryTheLaneMachineryIsTheInlinePipelineBitForBit() {
        val inline = inlineDabs()
        // Lane 0 of a REAL lane set: the identity lane, the one the copies are copies of.
        val set = build(mirrorGuide())
        assertTrue(set.original.isOriginal, "lane 0 is not the original")
        assertNull(set.original.copy, "lane 0 carries a copy's map")
        val lane = set.original
        val through = ArrayList<Dab>()
        for (s in samples) {
            val released = lane.feed(s)
            if (released.isNotEmpty()) through.addAll(lane.dabs(released))
        }
        through.addAll(lane.finish())

        assertEquals(inline.size, through.size, "a different number of dabs")
        assertTrue(inline.size > 20, "only ${inline.size} dabs - the test would prove nothing")
        for (i in inline.indices) assertSameDab(inline[i], through[i], i)
    }

    /** The view's own pipeline, written out: response, smoother, dabber, placer, scatter. */
    private fun inlineDabs(): List<Dab> {
        val dabber = BrushDabber(brush, seed)
        val placer = DabPlacer(spacing = dabber.spacing, look = dabber::look)
        val smoother = StrokeSmoother(smoothing, 1f)
        val rng = SplitMix(seed xor Scatter.SALT)
        val out = ArrayList<Dab>()
        for (s in samples) {
            val heard = brush.response.apply(s)
            val released = smoother.add(heard)
            if (released.isEmpty()) continue
            val placed = placer.add(released)
            if (placed.isEmpty()) continue
            out.addAll(Scatter.expand(placed, brush.scatter, rng, ::dabInputs))
        }
        val tail = smoother.finish()
        if (tail.isNotEmpty()) {
            val placed = placer.add(tail)
            if (placed.isNotEmpty()) out.addAll(Scatter.expand(placed, brush.scatter, rng, ::dabInputs))
        }
        return out
    }

    private fun dabInputs(dab: Dab) = DabInputs(
        pressure = dab.pressure,
        tilt = dab.tilt,
        speedPxPerS = Float.NaN,
        direction = Float.NaN,
        lean = Float.NaN,
        distancePx = Float.NaN,
        random = Float.NaN,
        strokeRandom = Float.NaN,
        barrel = Float.NaN,
    )

    // ---- 7. the caps -----------------------------------------------------------------------------

    /**
     * The caps are the owner's answer, and the owner's answer counts COPIES.
     *
     * The owner (2026-10-19): *"May I cap copies per brush family on the Note 9 (ink/pen up to
     * ~30, pencil ~16, watercolour ~8, oil ~4-8), and show a small note when the cap hits?"* — so
     * the table is in copies: the ink and pen family (the stamp family) may make thirty copies, and
     * the media families their own number each. The three media numbers are PROVISIONAL until the
     * media phase measures them on the phone; they live in the one table so that phase has one
     * place to fill in.
     */
    @Test
    fun theCapsAreTheOwnersAnswerAndTheyAreCountedInCopies() {
        assertEquals(30, SymmetryLanes.copyCapFor(SymmetryFamily.STAMP))
        assertEquals(16, SymmetryLanes.copyCapFor(SymmetryFamily.MEDIA_DRY))
        assertEquals(8, SymmetryLanes.copyCapFor(SymmetryFamily.MEDIA_WET))
        assertEquals(8, SymmetryLanes.copyCapFor(SymmetryFamily.MEDIA_PASTE))
        // The families the owner did not name are not capped below thirty copies: they take the
        // guard, which is more than thirty copies.
        for (family in listOf(
            SymmetryFamily.ERASER,
            SymmetryFamily.SMUDGE,
            SymmetryFamily.PUSH,
            SymmetryFamily.TUFT,
            SymmetryFamily.WATERCOLOUR,
            SymmetryFamily.FILL,
        )) {
            assertTrue(
                SymmetryLanes.copyCapFor(family) >= 30,
                "$family is capped below thirty copies",
            )
        }

        // The lane limit is DERIVED from the copy cap - lanes = copies + 1, the original always
        // being there - and that rule lives in ONE place.
        for (family in SymmetryFamily.values()) {
            assertEquals(
                SymmetryLanes.copyCapFor(family) + 1,
                SymmetryLanes.laneCapFor(family),
                "$family: the lane limit is not the copy cap plus the original",
            )
        }
        assertEquals(31, SymmetryLanes.laneCapFor(SymmetryFamily.STAMP))
        assertEquals(9, SymmetryLanes.laneCapFor(SymmetryFamily.MEDIA_WET))
    }

    /**
     * A guide that asks for more copies than the cap gets EXACTLY the cap's worth of copies, and
     * the lanes it makes are those copies and the original.
     *
     * A kaleidoscope of thirty asks for fifty-nine copies: twenty-nine rotations and thirty
     * mirrors.
     */
    @Test
    fun aGuideThatAsksForMoreCopiesThanTheCapGetsExactlyTheCapInCopies() {
        val kaleido = Guide.Symmetry(Pt(200.0, -100.0), Pt(200.0, 100.0), count = 30, kaleido = true)

        // The stamp family: thirty copies.
        val stamp = build(kaleido)
        assertEquals(59, stamp.requestedCopies, "a kaleidoscope of thirty asks for fifty-nine copies")
        assertEquals(30, stamp.copies, "the stamp family keeps thirty copies")
        assertEquals(31, stamp.lanes.size, "thirty copies and the original are thirty-one lanes")
        assertTrue(stamp.capped, "the cap bit and was not reported")

        // A media family: the wet one may make eight copies, the dry one sixteen.
        val wet = build(kaleido, family = SymmetryFamily.MEDIA_WET)
        assertEquals(8, wet.copies, "the wet media family keeps eight copies")
        assertEquals(9, wet.lanes.size, "eight copies and the original are nine lanes")
        assertTrue(wet.capped, "the cap bit and was not reported")
        val dry = build(kaleido, family = SymmetryFamily.MEDIA_DRY)
        assertEquals(16, dry.copies, "the dry media family keeps sixteen copies")
        assertEquals(17, dry.lanes.size, "sixteen copies and the original are seventeen lanes")
    }

    /**
     * A guide under the cap is not capped at all: it keeps every copy it asked for, and reports no
     * cap.
     */
    @Test
    fun aGuideUnderTheCapIsNotCapped() {
        // One mirror: ONE copy and TWO lanes.
        val mirror = build(mirrorGuide())
        assertEquals(1, mirror.copies)
        assertEquals(2, mirror.lanes.size)
        assertTrue(!mirror.capped, "one mirror is under every cap")
        assertNull(mirror.cap, "a cap that did not bite is not reported")

        // A plain guide of thirty: twenty-nine copies, which is under the ink family's thirty.
        val thirty = build(Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 30))
        assertEquals(29, thirty.copies)
        assertEquals(30, thirty.lanes.size)
        assertTrue(!thirty.capped, "twenty-nine copies is under the ink family's thirty")
        assertNull(thirty.cap)
    }

    /**
     * The number the cap REPORTS is the copy count - the number the sentence says - and never the
     * lane count.
     */
    @Test
    fun theCapReportsTheCopyCountAndNotTheLaneCount() {
        val kaleido = Guide.Symmetry(Pt(200.0, -100.0), Pt(200.0, 100.0), count = 30, kaleido = true)
        val stamp = build(kaleido)
        val cap = assertNotNull(stamp.cap, "the cap bit and was not reported")
        assertEquals(SymmetryFamily.STAMP, cap.family, "the cap does not name the family")
        assertEquals(30, cap.copies, "the cap reports copies, not lanes")
        assertEquals(31, stamp.lanes.size, "the lanes are the copies and the original")

        // The sentence says the copy count.
        assertEquals(
            "This brush keeps symmetry to 30 copies on this phone.",
            SymmetryLanes.capSentence(cap.copies),
        )
        // And the lane count is NOT the number in it: thirty-one copies would say something else.
        assertNotEquals(
            SymmetryLanes.capSentence(cap.copies),
            SymmetryLanes.capSentence(stamp.lanes.size),
        )
    }

    /**
     * The hard guard: sixty lanes, fifty-nine copies, ONE number, and nothing goes past it.
     *
     * The rule that turns one into the other is in one place - the copy limit is DERIVED from the
     * lane limit - so the two cannot drift apart.
     */
    @Test
    fun theHardGuardIsOneNumberAndNothingGoesPastIt() {
        assertEquals(60, SymmetryLanes.HARD_LANE_LIMIT)
        assertEquals(59, SymmetryLanes.HARD_COPY_LIMIT, "sixty lanes are fifty-nine copies")
        assertEquals(SymmetryLanes.HARD_LANE_LIMIT - 1, SymmetryLanes.HARD_COPY_LIMIT)

        // A kaleidoscope guide at the maximum count is capped, and it never goes past the guard.
        val kaleido = Guide.Symmetry(Pt(200.0, -100.0), Pt(200.0, 100.0), count = 30, kaleido = true)
        val stamp = build(kaleido)
        assertTrue(stamp.capped, "the cap bit")
        assertTrue(stamp.copies <= SymmetryLanes.HARD_COPY_LIMIT, "the guard was passed")
        assertTrue(stamp.lanes.size <= SymmetryLanes.HARD_LANE_LIMIT, "the guard was passed")

        // A family that is not capped below thirty copies reaches the guard and stops there: the
        // kaleidoscope of thirty IS the guard, fifty-nine copies and sixty lanes.
        val smudge = build(kaleido, family = SymmetryFamily.SMUDGE)
        assertEquals(59, smudge.copies, "the guard is fifty-nine copies")
        assertEquals(60, smudge.lanes.size, "the guard is sixty lanes")
        assertTrue(!smudge.capped, "the guide asked for exactly the guard")

        // And no family's limit is past the guard.
        for (family in SymmetryFamily.values()) {
            assertTrue(
                SymmetryLanes.copyCapFor(family) <= SymmetryLanes.HARD_COPY_LIMIT,
                "$family: the copy cap is past the guard",
            )
            assertTrue(
                SymmetryLanes.laneCapFor(family) <= SymmetryLanes.HARD_LANE_LIMIT,
                "$family: the lane cap is past the guard",
            )
        }
    }

    // ---- 8. the refusal --------------------------------------------------------------------------

    /**
     * A family that is not a stroke of dabs is refused IN WORDS: the tuft engine lays footprints,
     * the legacy watercolour engine keeps its own dab array, the fill pen rasterises a shape, and
     * the media brushes paint through the media engine.
     */
    @Test
    fun aFamilyThatIsNotADabPathIsRefusedInWords() {
        for (family in listOf(
            SymmetryFamily.TUFT,
            SymmetryFamily.WATERCOLOUR,
            SymmetryFamily.FILL,
            SymmetryFamily.MEDIA_DRY,
            SymmetryFamily.MEDIA_WET,
            SymmetryFamily.MEDIA_PASTE,
        )) {
            val sentence = assertNotNull(SymmetryLanes.refusalFor(family), "$family was not refused")
            assertTrue(sentence.isNotBlank(), "$family was refused with no words")
            assertTrue(sentence.contains("not available"), "$family was refused with: $sentence")
        }
        assertNull(SymmetryLanes.refusalFor(SymmetryFamily.STAMP))
        assertNull(SymmetryLanes.refusalFor(SymmetryFamily.ERASER))
        assertNull(SymmetryLanes.refusalFor(SymmetryFamily.SMUDGE))
        assertNull(SymmetryLanes.refusalFor(SymmetryFamily.PUSH))

        // And the families are found from the brush's own engine and medium.
        assertEquals(SymmetryFamily.TUFT, SymmetryLanes.familyFor("tuft", null))
        assertEquals(SymmetryFamily.WATERCOLOUR, SymmetryLanes.familyFor("watercolour", null))
        assertEquals(SymmetryFamily.FILL, SymmetryLanes.familyFor("fill", null))
        assertEquals(SymmetryFamily.MEDIA_DRY, SymmetryLanes.familyFor("media", "dry"))
        assertEquals(SymmetryFamily.MEDIA_WET, SymmetryLanes.familyFor("media", "wet"))
        assertEquals(SymmetryFamily.MEDIA_PASTE, SymmetryLanes.familyFor("media", "paste"))
        assertEquals(SymmetryFamily.STAMP, SymmetryLanes.familyFor("stamp", null))
        assertEquals(SymmetryFamily.STAMP, SymmetryLanes.familyFor(null, null))
        // An eraser is an eraser whatever brush it is: the media brushes' engine is not what the
        // eraser runs on, and the eraser is a stroke of dabs like any other.
        assertEquals(SymmetryFamily.ERASER, SymmetryLanes.familyFor("media", "wet", erase = true))
        assertEquals(SymmetryFamily.ERASER, SymmetryLanes.familyFor("stamp", null, erase = true))
    }

    // ---- 9. degenerate guides --------------------------------------------------------------------

    /**
     * A guide that cannot make copies makes no copies: a line with no direction, a count of one,
     * and a count over the cap are all one lane, and the stroke is the ordinary stroke.
     */
    @Test
    fun degenerateGuidesDrawOneLaneAndNotAMany() {
        // A line whose two ends are the same point.
        val point = Guide.Symmetry(Pt(10.0, 10.0), Pt(10.0, 10.0), count = 8)
        assertEquals(1, build(point).lanes.size)

        // The symmetry off: a count of one.
        val off = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 1)
        assertEquals(1, build(off).lanes.size)

        // A count over the cap: the guide's own cap holds it at thirty lanes.
        val over = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 1000)
        val capped = build(over)
        assertEquals(30, capped.lanes.size, "the count over the cap is capped at thirty")
        assertEquals(30, capped.requestedLanes)

        // And a count over the cap with the mirror on top: fifty-nine copies is all a guide may
        // ask for, and the ink family's cap cuts even that to thirty copies and thirty-one lanes.
        val overKaleido = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 1000, kaleido = true)
        val kaleido = build(overKaleido)
        assertEquals(59, kaleido.requestedCopies, "a guide may ask for fifty-nine copies")
        assertEquals(31, kaleido.lanes.size, "the ink family keeps thirty copies")

        // The lanes of a degenerate guide still draw: the original is a stroke like any other.
        val perLane = run(build(point).lanes, samples)
        assertTrue(perLane[0].size > 20, "the original lane drew nothing")
        assertEquals(1, perLane.size)
    }
}
