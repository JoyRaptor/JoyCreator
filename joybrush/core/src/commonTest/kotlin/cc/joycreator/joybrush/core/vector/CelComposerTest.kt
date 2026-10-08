package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.TipSpec
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer.Items
import cc.joycreator.joybrush.core.vector.CelComposer.Line
import cc.joycreator.joybrush.core.vector.CelComposer.Slab
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JB-5.20b: pixels and lines in one cel, in the order they were made. Every expected pixel is worked out in its comment.
 * The bake tests compare a cel with its line to the same cel with the line baked: they must be the same bytes, which is
 * the whole of D8a, and they would differ if a slab dropped the line's operator (Codex's stop, 2026-10-07).
 */
class CelComposerTest {

    private val t = RegionRenderer.TILE_BYTES
    private val k00 = Tiles.key(0, 0)
    private val k10 = Tiles.key(1, 0)

    /** A hard, opaque pen 9 px wide, so the middle of the line is exactly its colour. */
    private val pen = BrushPreset(id = "pen", name = "Pen", size = Param(9f), tip = TipSpec(hardness = Param(1f)), spacing = 0.05f)
    private val brushes = mapOf("pen" to pen, "eraser" to pen.copy(id = "eraser", blend = "erase"),
        "behind" to pen.copy(id = "behind", blend = "behind"))

    /** A level line at y = 128 from x = 40 to x = 460: it crosses from tile (0,0) into tile (1,0). */
    private fun line(id: String, brush: String = "pen", argb: Int = 0xFF000000.toInt()) =
        StrokeRecord(id, brush, 5L, 0f, 1f, (0..42).map { PenSample(40f + it * 10f, 128f, it * 8.0) }, argb)

    private fun solid(r: Int, g: Int, b: Int, a: Int) = ByteArray(t) { i -> when (i % 4) { 0 -> r; 1 -> g; 2 -> b; else -> a }.toByte() }

    /** One pixel of a tile, as four ints. */
    private fun px(tile: ByteArray?, x: Int, y: Int): List<Int> {
        if (tile == null) return listOf(0, 0, 0, 0)
        val i = (y * 256 + x) * 4
        return (0..3).map { tile[i + it].toInt() and 255 }
    }

    private fun draw(items: Items) = CelComposer.draw(items) { brushes[it] }

    // ── D3: the look ──

    @Test
    fun aCelWithNoLinesLooksExactlyLikeItsSlab() {
        // Premultiplied and uneven on purpose: a straight copy is the only way every byte survives.
        val bytes = ByteArray(t) { i -> if (i % 4 == 3) ((i / 4) % 251 + 4).toByte() else (((i / 4) % 251 + 4) * (i % 4 + 1) / 4).toByte() }
        val d = draw(Items(emptyList(), mapOf(k00 to listOf(Slab(1, bytes)))))
        assertContentEquals(bytes, CelComposer.look(d, k00))
        assertNull(CelComposer.look(d, k10), "an empty tile is nothing")
    }

    @Test
    fun timeOrderDecidesWhatCovers() {
        // Red paint (seq 1), then a black line (seq 2), then one green pixel painted on the line (seq 3).
        // On the line at x = 100: black. Where the green pixel went (x = 200 on the line): green. Far from the line: red.
        val green = ByteArray(t).also { val i = (128 * 256 + 200) * 4; it[i + 1] = 255.toByte(); it[i + 3] = 255.toByte() }
        val d = draw(Items(listOf(Line(2, line("a"))), mapOf(k00 to listOf(Slab(1, solid(255, 0, 0, 255)), Slab(3, green)))))
        val look = CelComposer.look(d, k00)
        assertEquals(listOf(0, 0, 0, 255), px(look, 100, 128), "the line covers the paint under it")
        assertEquals(listOf(0, 255, 0, 255), px(look, 200, 128), "the later pixel covers the line")
        assertEquals(listOf(255, 0, 0, 255), px(look, 100, 20), "the paint shows where the line is not")
    }

    // ── D2: where a pixel write goes ──

    @Test
    fun aWriteGoesIntoTheTopSlabUntilALineComesBetween() {
        val red = solid(255, 0, 0, 255)
        var d = draw(Items(emptyList(), mapOf(k00 to listOf(Slab(1, red)))))
        d = CelComposer.paint(d, k00, seq = 2) { it }
        assertEquals(listOf(1L), d.items.slabs.getValue(k00).map { it.seq }, "no line above: the write joins slab 1")

        // A line drawn after slab 1 crosses the tile: the next write starts a new slab above it.
        d = draw(Items(listOf(Line(2, line("a"))), d.items.slabs))
        d = CelComposer.paint(d, k00, seq = 3) { solid(0, 0, 255, 255) }
        assertEquals(listOf(1L, 3L), d.items.slabs.getValue(k00).map { it.seq })
        assertEquals(listOf(0, 0, 255, 255), px(CelComposer.look(d, k00), 100, 128), "the new paint covers the line")

        // A tile the line does not reach keeps writing into its own top slab.
        val k01 = Tiles.key(0, 1)
        d = draw(Items(d.items.lines, d.items.slabs + (k01 to listOf(Slab(1, red)))))
        d = CelComposer.paint(d, k01, seq = 4) { it }
        assertEquals(listOf(1L), d.items.slabs.getValue(k01).map { it.seq })

        // A slab that is a baked line with its own operator is never written into.
        val behind = draw(Items(emptyList(), mapOf(k00 to listOf(Slab(1, red, "behind")))))
        assertNull(CelComposer.writableTop(behind, k00))
        // A write that needs a new slab must carry a seq later than everything on the tile (here the line's 2).
        val lineOverPaint = draw(Items(listOf(Line(2, line("a"))), mapOf(k00 to listOf(Slab(1, red)))))
        assertFailsWith<IllegalArgumentException>("a new slab must come after everything on the tile") {
            CelComposer.paint(lineOverPaint, k00, seq = 2) { red }
        }
    }

    // ── D8a: a baked line is the same pixels ──

    @Test
    fun bakingALineChangesNoPixelForAnyOperator() {
        for (brush in listOf("pen", "eraser", "behind")) {
            // Half-transparent blue paint under (seq 1), the line (seq 2), and a later band of yellow (seq 3) over part of it.
            val band = ByteArray(t).also { for (y in 120..136) for (x in 150..170) { val i = (y * 256 + x) * 4
                it[i] = 200.toByte(); it[i + 1] = 200.toByte(); it[i + 3] = 200.toByte() } }
            val items = Items(listOf(Line(2, line("a", brush, 0xCC336699.toInt()))),
                mapOf(k00 to listOf(Slab(1, solid(0, 0, 128, 128)), Slab(3, band)), k10 to listOf(Slab(1, solid(0, 0, 128, 128)))))
            val d = draw(items)
            val baked = CelComposer.bake(d, setOf("a"))
            assertTrue(baked.items.lines.isEmpty(), "$brush: the line is gone")
            for (key in listOf(k00, k10)) {
                assertContentEquals(CelComposer.look(d, key), CelComposer.look(baked, key), "$brush: tile $key unchanged by the bake")
                val slab = baked.items.slabs.getValue(key).single { it.seq == 2L }
                assertEquals(brush.let { if (it == "pen") "normal" else if (it == "eraser") "erase" else it }, slab.op,
                    "$brush: the slab keeps the line's operator")
            }
        }
    }

    @Test
    fun aBakedLineStoredAsPlainPaintWouldLookDifferent() {
        // Non-vacuity for the test above: the same buffer with the operator dropped is NOT the same picture.
        val items = Items(listOf(Line(2, line("a", "eraser"))), mapOf(k00 to listOf(Slab(1, solid(255, 0, 0, 255)))))
        val baked = CelComposer.bake(draw(items), setOf("a"))
        val wrong = Items(emptyList(), mapOf(k00 to baked.items.slabs.getValue(k00).map { Slab(it.seq, it.bytes) }))
        assertEquals(listOf(0, 0, 0, 0), px(CelComposer.look(baked, k00), 100, 128), "erased: a hole in the red")
        assertTrue(px(CelComposer.look(draw(wrong), k00), 100, 128) != listOf(0, 0, 0, 0), "as plain paint it would not be a hole")
    }

    @Test
    fun aLineWhoseBrushIsMissingIsNeitherDrawnNorBakedNorLost() {
        val items = Items(listOf(Line(2, line("lost", brush = "gone"))), mapOf(k00 to listOf(Slab(1, solid(255, 0, 0, 255)))))
        val d = draw(items)
        assertEquals(listOf("lost"), d.refusals.map { it.strokeId })
        val baked = CelComposer.bake(d, setOf("lost"))
        assertEquals(listOf("lost"), baked.items.lines.map { it.record.id }, "kept, so nothing is lost")
        assertEquals(listOf(255, 0, 0, 255), px(CelComposer.look(baked, k00), 100, 128))
    }

    // ── D8: tools that ignore lines ──

    @Test
    fun aToolThatIgnoresLinesNeverTouchesOne() {
        val items = Items(listOf(Line(2, line("a"))), mapOf(k00 to listOf(Slab(1, solid(255, 0, 0, 255)))))
        val cleared = CelComposer.eachSlab(draw(items), k00) { null }
        assertTrue(cleared.items.slabs[k00].isNullOrEmpty())
        // What is left is the line alone, exactly as InkTiles draws it.
        val alone = InkTiles.render(listOf(line("a")), 0, 0) { brushes[it] }.pixels
        assertContentEquals(alone, CelComposer.look(cleared, k00))
    }

    // ── compacting ──

    @Test
    fun neighbouringPaintSlabsMergeOnlyWithNoLineBetween() {
        val a = solid(0, 0, 128, 128)
        val b = ByteArray(t).also { for (i in 0 until t step 4) { it[i] = 100.toByte(); it[i + 3] = 100.toByte() } }
        val plain = draw(Items(emptyList(), mapOf(k00 to listOf(Slab(1, a), Slab(3, b)))))
        val merged = CelComposer.compact(plain, k00)
        assertEquals(listOf(1L), merged.items.slabs.getValue(k00).map { it.seq })
        val before = assertNotNull(CelComposer.look(plain, k00))
        val after = assertNotNull(CelComposer.look(merged, k00))
        for (i in 0 until t) assertTrue(abs((before[i].toInt() and 255) - (after[i].toInt() and 255)) <= 1, "byte $i within one step")

        val lined = draw(Items(listOf(Line(2, line("a"))), mapOf(k00 to listOf(Slab(1, a), Slab(3, b)))))
        assertEquals(lined.items.slabs.getValue(k00).size, CelComposer.compact(lined, k00).items.slabs.getValue(k00).size,
            "a line between them keeps them apart")
    }

    // ── the rules on the data ──

    @Test
    fun seqsAreCheckedAndUnknownOperatorsRefused() {
        assertFailsWith<IllegalArgumentException> { Items(listOf(Line(1, line("a")), Line(1, line("b"))), emptyMap()) }
        assertFailsWith<IllegalArgumentException> { Items(listOf(Line(1, line("a"))), mapOf(k00 to listOf(Slab(1, ByteArray(t))))) }
        assertFailsWith<IllegalArgumentException> { Slab(1, ByteArray(t), "sparkle") }
        assertEquals(4L, Items(listOf(Line(3, line("a"))), mapOf(k00 to listOf(Slab(1, ByteArray(t))))).nextSeq)
        assertEquals(setOf(k00, k10), CelComposer.tiles(draw(Items(listOf(Line(1, line("a"))), emptyMap()))))
    }
}
