package cc.joycreator.joybrush.core.paint

import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.guide.Guide
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.symmetry.LaneBrush
import cc.joycreator.joybrush.core.symmetry.SymmetryFamily
import cc.joycreator.joybrush.core.symmetry.SymmetryLanes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaintTest {

    private val hard = TipShape(hardness = 1f)

    @Test
    fun tileKeysRoundTripIncludingNegatives() {
        for ((x, y) in listOf(0 to 0, -1 to -1, 5 to -7, -300000 to 123456)) {
            val k = Tiles.key(x, y)
            assertEquals(x, Tiles.tx(k)); assertEquals(y, Tiles.ty(k))
        }
    }

    @Test
    fun dabsAreEvenlySpacedWhateverTheBatching() {
        val line = (0..10).map { PenSample(it * 10f, 0f, it * 4.0) }
        fun place(chunks: List<List<PenSample>>): List<Dab> {
            val p = DabPlacer(spacing = 0.1f, radiusOf = { 10f })
            return chunks.flatMap { p.add(it) }
        }
        val whole = place(listOf(line))
        assertEquals(51, whole.size) // every 2 px from 0 to 100
        assertEquals(100f, whole.last().x, 1e-3f)
        assertEquals(whole, place(line.map { listOf(it) }))
        assertEquals(whole, place(line.chunked(3)))
    }

    @Test
    fun tipCoverageShape() {
        assertEquals(1f, TipMath.coverage(0f, 0f, 10f, 0f, hard), 1e-4f)
        assertEquals(0f, TipMath.coverage(20f, 0f, 10f, 0f, hard), 1e-4f)
        // Tall-and-thin tip: narrow in x, full in y.
        val thin = TipShape(aspect = 0.8f, hardness = 1f)
        assertEquals(0f, TipMath.coverage(5f, 0f, 10f, 0f, thin), 1e-4f)
        assertEquals(1f, TipMath.coverage(0f, 5f, 10f, 0f, thin), 1e-4f)
        // Razor extreme stays a faint hairline instead of vanishing.
        val razor = TipShape(aspect = 1f, hardness = 1f, minPx = 1f)
        val c = TipMath.coverage(0f, 0f, 10f, 0f, razor)
        assertTrue(c > 0.2f && c < 0.8f, "razor coverage $c")
        // Taper = 1: the top end closes to a point.
        val tri = TipShape(taper = 1f, corner = 16f, hardness = 1f)
        assertTrue(TipMath.coverage(5f, -7f, 10f, 0f, tri) > 0.9f)  // wide near the bottom
        assertEquals(0f, TipMath.coverage(8f, 8f, 10f, 0f, tri), 1e-4f) // top corner gone
    }

    @Test
    fun washNeverPassesItsOpacityEvenWhereTheStrokeCrossesItself() {
        val c = RefCanvas()
        c.beginStroke("L", 1f, 0f, 0f, opacity = 0.5f, mode = Accumulate.WASH, blend = StrokeBlend.NORMAL, tip = hard)
        val cap = c.capForStroke()
        c.addDabs(List(40) { Dab(50f, 50f, 10f, flow = 0.3f, cap = cap) })
        c.endStroke()
        val p = c.pixel("L", 50, 50)
        assertTrue(p[3] <= 0.5f + 1e-5f, "alpha ${p[3]}")
        assertTrue(p[3] > 0.49f, "alpha ${p[3]} should approach 0.5")
        assertEquals(p[3], p[0], 1e-5f) // premultiplied red
    }

    @Test
    fun buildUpAccumulatesThenAppliesOpacityOnce() {
        val c = RefCanvas()
        c.beginStroke("L", 0f, 0f, 1f, opacity = 0.8f, mode = Accumulate.BUILD_UP, blend = StrokeBlend.NORMAL, tip = hard)
        c.addDabs(List(2) { Dab(10f, 10f, 5f, flow = 0.5f, cap = c.capForStroke()) })
        c.endStroke()
        assertEquals(0.75f * 0.8f, c.pixel("L", 10, 10)[3], 1e-5f)
    }

    @Test
    fun eraseRemovesPaint() {
        val c = RefCanvas()
        c.beginStroke("L", 1f, 1f, 1f, 1f, Accumulate.WASH, StrokeBlend.NORMAL, hard)
        c.addDabs(listOf(Dab(30f, 30f, 8f)))
        c.endStroke()
        c.beginStroke("L", 0f, 0f, 0f, 1f, Accumulate.WASH, StrokeBlend.ERASE, hard)
        c.addDabs(listOf(Dab(30f, 30f, 8f)))
        c.endStroke()
        assertEquals(0f, c.pixel("L", 30, 30)[3], 1e-6f)
    }

    @Test
    fun strokesAcrossTileEdgesAreSeamless() {
        val c = RefCanvas()
        c.beginStroke("L", 1f, 1f, 1f, 1f, Accumulate.WASH, StrokeBlend.NORMAL, hard)
        c.addDabs(listOf(Dab(256f, 256f, 12f)))
        c.endStroke()
        assertEquals(4, c.tiles("L").size)
        for ((x, y) in listOf(255 to 255, 256 to 255, 255 to 256, 256 to 256)) assertEquals(1f, c.pixel("L", x, y)[3], 1e-4f)
        c.beginStroke("L", 1f, 1f, 1f, 1f, Accumulate.WASH, StrokeBlend.NORMAL, hard)
        c.addDabs(listOf(Dab(-3f, -3f, 6f)))
        c.endStroke()
        assertEquals(1f, c.pixel("L", -3, -3)[3], 1e-4f)
    }

    @Test
    fun undoAndRedoSwapWholeTilesBack() {
        val c = RefCanvas()
        c.beginStroke("L", 1f, 0f, 0f, 1f, Accumulate.WASH, StrokeBlend.NORMAL, hard)
        c.addDabs(listOf(Dab(10f, 10f, 5f)))
        c.endStroke()
        val first = c.tiles("L").getValue(Tiles.key(0, 0))
        c.beginStroke("L", 0f, 1f, 0f, 1f, Accumulate.WASH, StrokeBlend.NORMAL, hard)
        c.addDabs(listOf(Dab(12f, 10f, 5f)))
        c.endStroke()
        val second = c.tiles("L").getValue(Tiles.key(0, 0))

        assertTrue(c.undoStep())
        assertSame(first, c.tiles("L")[Tiles.key(0, 0)])
        assertTrue(c.redoStep())
        assertSame(second, c.tiles("L")[Tiles.key(0, 0)])
        assertTrue(c.undoStep()); assertTrue(c.undoStep())
        assertNull(c.tiles("L")[Tiles.key(0, 0)])
        assertFalse(c.undoStep())
    }

    @Test
    fun undoLogReleasesEveryTileExactlyOnce() {
        val released = ArrayList<String>()
        val log = UndoLog<String>(budgetBytes = 2, sizeOf = { 1L }, release = { released.add(it) })
        fun step(before: String?, after: String) = UndoLog.Step(listOf(UndoLog.TileChange("L", 0L, before, after)))
        log.push(step(null, "A"))
        log.push(step("A", "B"))
        log.push(step("B", "C"))
        log.push(step("C", "D")) // holds befores A,B,C = 3 > budget 2 → drops oldest with a before: A
        assertEquals(listOf("A"), released)
        log.undo(); log.undo()   // redo stack owns D and C
        log.push(step("B", "E")) // discards redo: releases afters D and C
        assertEquals(setOf("A", "C", "D"), released.toSet())
        assertEquals(3, released.size)
        log.clear()
        assertEquals(released.size, released.toSet().size, "released twice: $released")
    }

    @Test
    fun theBrushIsAskedExactlyOncePerDabInOrder() {
        val seen = ArrayList<Pair<Int, Float>>()
        val p = DabPlacer(spacing = 0.1f, look = { _, dist, i -> seen.add(i to dist); DabLook(10f) })
        val dabs = p.add((0..10).map { PenSample(it * 10f, 0f, it * 4.0) })
        assertEquals(dabs.size, seen.size)
        assertEquals((0 until dabs.size).toList(), seen.map { it.first })
        for ((i, d) in seen) assertEquals(i * 2f, d, 1e-3f) // distance along the stroke, 2 px apart
    }

    @Test
    fun anInfiniteBrushSizeCannotFreezeTheStroke() {
        val p = DabPlacer(spacing = 0.1f, look = { _, _, _ -> DabLook(Float.POSITIVE_INFINITY, Float.NaN, Float.NaN) })
        val dabs = p.add((0..10).map { PenSample(it * 10f, 0f, it * 4.0) })
        assertTrue(dabs.size > 1, "stroke stopped after ${dabs.size} dab(s)")
        assertTrue(dabs.all { it.radius.isFinite() && it.angle.isFinite() && it.flow.isFinite() })
    }

    @Test
    fun leanDirectionTakesTheShortWayRoundBetweenSamples() {
        val a = PenSample(0f, 0f, 0.0, azimuth = 3.0f)
        val b = PenSample(100f, 0f, 10.0, azimuth = -3.0f) // 3.0 → -3.0 is only ~0.28 rad the short way
        val dabs = DabPlacer(spacing = 0.25f, look = { s, _, _ -> DabLook(10f, angle = s.azimuth) }).add(listOf(a, b))
        assertTrue(dabs.all { kotlin.math.abs(it.angle) > 2.9f }, "lean swung the long way: ${dabs.map { it.angle }}")
    }

    /**
     * The symmetry fan-out is ONE undo step.
     *
     * Every lane's dabs go into ONE stroke, so the whole fan-out is one step in the history and
     * the ONE undo takes all of it away - not one undo per copy, which would be a way of undoing
     * a stroke that the painter never drew.
     */
    @Test
    fun symmetryLanesMakeOneUndoStepAndTheUndoTakesThemAll() {
        val brush = BrushJson.decode(
            """
            {
              "format": "joybrush.brush",
              "version": 1,
              "id": "test.undo",
              "name": "Undo Test",
              "engine": "stamp",
              "tip": { "corner": 2, "hardness": { "base": 0.95 } },
              "size": { "base": 6 },
              "opacity": { "base": 1 },
              "flow": { "base": 1 },
              "spacing": 0.1,
              "accumulate": "wash",
              "blend": "normal",
              "smoothing": 0.35,
              "license": "CC0"
            }
            """.trimIndent() + "\n"
        )
        val guide = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 3)
        val set = SymmetryLanes.build(guide, LaneBrush.File(brush), SymmetryFamily.STAMP, 4242L, 0.35f, 1f)
        assertEquals(3, set.lanes.size, "three copies around the line's middle is three lanes")

        val samples = (0..11).map { PenSample(it * 10f, 0f, it * 10.0, pressure = 1f) }
        val perLane = set.lanes.map { ArrayList<Dab>() }
        for (s in samples) {
            for (i in set.lanes.indices) {
                val released = set.lanes[i].feed(s)
                if (released.isNotEmpty()) perLane[i].addAll(set.lanes[i].dabs(released))
            }
        }
        for (i in set.lanes.indices) perLane[i].addAll(set.lanes[i].finish())
        val all = perLane.flatten()
        assertTrue(all.size > 20, "only ${all.size} dabs - the test would prove nothing")
        assertEquals(3 * perLane[0].size, all.size, "the lanes made different numbers of dabs")

        val c = RefCanvas()
        c.beginStroke("L", 1f, 0f, 0f, 1f, Accumulate.WASH, StrokeBlend.NORMAL, hard)
        c.addDabs(all)
        c.endStroke()
        assertEquals(1, c.undo.undoDepth, "the fan-out is ONE undo step, not one per lane")
        for (lane in perLane) {
            val d = lane.first()
            assertTrue(c.pixel("L", d.x.toInt(), d.y.toInt())[3] > 0f, "a lane's dab is not on the canvas")
        }

        assertTrue(c.undoStep())
        for (lane in perLane) {
            val d = lane.first()
            assertEquals(0f, c.pixel("L", d.x.toInt(), d.y.toInt())[3], 1e-6f, "a lane's dab is still there")
        }
        assertEquals(0, c.undo.undoDepth)
    }
}
