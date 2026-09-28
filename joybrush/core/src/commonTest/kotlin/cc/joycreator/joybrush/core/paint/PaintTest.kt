package cc.joycreator.joybrush.core.paint

import cc.joycreator.joybrush.core.input.PenSample
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
}
