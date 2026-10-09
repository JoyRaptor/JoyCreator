package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.TipSpec
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.stroke.StrokeEdit
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer.Items
import cc.joycreator.joybrush.core.vector.CelComposer.Line
import cc.joycreator.joybrush.core.vector.CelComposer.Slab
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JB-NOW-26: the cel composer's interleaving rules beyond [CelComposerTest].
 *
 * That file proves the core contract (D2 slab rule, D3 time order, D8a bake identity for every
 * operator, missing-brush refusal, eachSlab isolation, compact merging). This corpus adds only the
 * missing cases, each through the real draw/look/paint/bake/compact/applyLines APIs:
 *
 * - solid deterministic slabs interleaved with supported replayable lines (two lines over one slab,
 *   one slab buried between two lines),
 * - lines confined to exactly one of two tiles (footprint, per-tile look, per-tile bake),
 * - writableTop selection per tile and per operator,
 * - editing a line (recolour, reshape, reweight, undo steps) while its seq stays put,
 * - hand-computed flat-colour normal-blend pixels wherever only opaque flats meet,
 * - look-before/look-after bake identity where D8a guarantees it (pure-line cels, partial bakes,
 *   confined lines leaving the far tile empty),
 * - missing and unsupported brush lookups following the actual refusal path, never fabricated.
 *
 * Unsupported, recorded distinctly rather than tested: fill-engine interleaving (a supported path
 * with its own raster, left to its own corpus); image-tip, tip-grain and unknown-blend refusals
 * (the same [InkTiles.prepare] refusal path as the media case below, not re-fabricated here);
 * document/GL wiring and look-cache persistence (out of scope: the composer is pure). Tile size is
 * fixed at 256 by [RegionRenderer]; fixtures use whole tiles exactly like [CelComposerTest].
 */
class CelComposerInterleaveCorpusTest {

    private val t = RegionRenderer.TILE_BYTES
    private val k00 = Tiles.key(0, 0)
    private val k10 = Tiles.key(1, 0)

    /** A hard, opaque pen 9 px wide, so the middle of a line is exactly its colour. */
    private val pen = BrushPreset(id = "pen", name = "Pen", size = Param(9f), tip = TipSpec(hardness = Param(1f)), spacing = 0.05f)
    private val brushes = mapOf("pen" to pen)

    /**
     * A short level line: 11 samples 10 px apart from ([x0], [y]), seed 5 fixed. At x0 = 40 the
     * whole stroke (dabs plus tip extent of about 6 px) stays inside tile (0,0); at x0 = 300 it
     * stays inside tile (1,0). Two lines 128 px apart in y never touch each other.
     */
    private fun short(id: String, x0: Float, y: Float, brush: String = "pen", argb: Int = 0xFF000000.toInt()) =
        StrokeRecord(id, brush, 5L, 0f, 1f, (0..10).map { PenSample(x0 + it * 10f, y, it * 8.0) }, argb)

    private fun solid(r: Int, g: Int, b: Int, a: Int) = ByteArray(t) { i -> when (i % 4) { 0 -> r; 1 -> g; 2 -> b; else -> a }.toByte() }

    /** One pixel of a tile, as four ints. */
    private fun px(tile: ByteArray?, x: Int, y: Int): List<Int> {
        if (tile == null) return listOf(0, 0, 0, 0)
        val i = (y * 256 + x) * 4
        return (0..3).map { tile[i + it].toInt() and 255 }
    }

    private fun draw(items: Items) = CelComposer.draw(items) { brushes[it] }

    private val red = solid(255, 0, 0, 255)
    private val blue = solid(0, 0, 255, 255)
    private val black = listOf(0, 0, 0, 255)
    private val opaqueRed = listOf(255, 0, 0, 255)
    private val opaqueBlue = listOf(0, 0, 255, 255)

    // ── slabs interleaved with lines ──

    @Test
    fun twoLinesOverOneSlabKeepTimeOrder() {
        // Red paint (seq 1), black line A at y = 64 (seq 2), black line B at y = 192 (seq 3).
        // Normal blend of opaque flats is the top contributor exactly: on either line black, far
        // from both lines red. The lines are 128 px apart, far beyond the ~6 px tip extent.
        val d = draw(Items(listOf(Line(2, short("a", 40f, 64f)), Line(3, short("b", 40f, 192f))),
            mapOf(k00 to listOf(Slab(1, red)))))
        assertEquals(listOf("a", "b"), CelComposer.linesOn(d, k00).map { it.record.id }, "seq order, both reach")
        val look = CelComposer.look(d, k00)
        assertEquals(black, px(look, 100, 64), "line A covers the paint")
        assertEquals(black, px(look, 100, 192), "line B covers the paint")
        assertEquals(opaqueRed, px(look, 100, 20), "the paint shows where no line is")
    }

    @Test
    fun aSlabBetweenTwoLinesBuriesOnlyTheEarlier() {
        // Red (1), line A at y = 64 (2), full blue slab (3), line B at y = 192 (4). The blue slab
        // is opaque, so it covers line A exactly while line B, drawn after it, stays black.
        val d = draw(Items(listOf(Line(2, short("a", 40f, 64f)), Line(4, short("b", 40f, 192f))),
            mapOf(k00 to listOf(Slab(1, red), Slab(3, blue)))))
        val look = CelComposer.look(d, k00)
        assertEquals(opaqueBlue, px(look, 100, 64), "the middle slab buries the earlier line")
        assertEquals(black, px(look, 100, 192), "the later line stays on top")
        assertEquals(opaqueBlue, px(look, 100, 20), "far from both lines the top slab shows")
    }

    // ── one tile out of two ──

    @Test
    fun linesConfinedToOneTileTouchOnlyThatTile() {
        val a = short("a", 40f, 64f)
        val c = short("c", 300f, 64f)
        val d = draw(Items(listOf(Line(2, a), Line(3, c)), emptyMap()))
        assertEquals(setOf(k00, k10), CelComposer.tiles(d), "each line claims exactly its own tile")
        assertEquals(listOf("a"), CelComposer.linesOn(d, k00).map { it.record.id })
        assertEquals(listOf("c"), CelComposer.linesOn(d, k10).map { it.record.id })
        // Each tile's look is exactly that tile's line alone, drawn the InkTiles way.
        assertContentEquals(InkTiles.render(listOf(a), 0, 0) { brushes[it] }.pixels, CelComposer.look(d, k00))
        assertContentEquals(InkTiles.render(listOf(c), 1, 0) { brushes[it] }.pixels, CelComposer.look(d, k10))

        // One confined line and nothing else: the far tile is nothing, not an empty picture.
        val solo = draw(Items(listOf(Line(2, a)), emptyMap()))
        assertEquals(setOf(k00), CelComposer.tiles(solo))
        assertTrue(CelComposer.linesOn(solo, k10).isEmpty())
        assertNull(CelComposer.look(solo, k10), "a tile no line reaches is nothing")
    }

    // ── writableTop selection ──

    @Test
    fun writableTopIsPerTileAndPerOperator() {
        // No slab anywhere: nothing to write into.
        assertNull(CelComposer.writableTop(draw(Items.EMPTY), Tiles.key(5, 5)), "an empty tile starts a new slab")

        // Two normal slabs, no lines: the top one takes the write.
        val open = draw(Items(emptyList(), mapOf(k00 to listOf(Slab(1, red), Slab(3, blue)))))
        assertEquals(3L, assertNotNull(CelComposer.writableTop(open, k00)).seq)

        // A line BELOW the top slab does not block it: only a line above the top does.
        val below = draw(Items(listOf(Line(2, short("a", 40f, 64f))),
            mapOf(k00 to listOf(Slab(1, red), Slab(3, blue)))))
        assertEquals(3L, assertNotNull(CelComposer.writableTop(below, k00)).seq)

        // A later line reaching the tile blocks its top slab, but not the next tile's slab.
        val sided = draw(Items(listOf(Line(4, short("a", 40f, 64f))),
            mapOf(k00 to listOf(Slab(1, red)), k10 to listOf(Slab(1, red)))))
        assertNull(CelComposer.writableTop(sided, k00), "a later line on this tile forces a new slab")
        assertEquals(1L, assertNotNull(CelComposer.writableTop(sided, k10)).seq,
            "the line never reaches the other tile, so its slab stays writable")

        // A baked line's own operator is never written into, with no lines involved at all.
        for (op in listOf("behind", "erase")) {
            val d = draw(Items(emptyList(), mapOf(k00 to listOf(Slab(1, red, op)))))
            assertNull(CelComposer.writableTop(d, k00), "$op slab never takes a pixel write")
        }
    }

    // ── paint ──

    @Test
    fun aNullWriteEmptiesTheTopSlabButNeverTouchesLines() {
        // CelComposerTest proves writes that add paint; the missing paint case is the erasing
        // write: a null result empties the tile's top slab while the line list is untouched.
        val rec = short("a", 40f, 64f)
        val cleared = CelComposer.paint(draw(Items(listOf(Line(1, rec)), mapOf(k00 to listOf(Slab(2, red))))),
            k00, seq = 3) { null }
        assertTrue(cleared.items.slabs[k00].isNullOrEmpty(), "the slab is gone")
        assertEquals(listOf("a"), cleared.items.lines.map { it.record.id }, "the line survives the slab going")
        // What is left is the line alone, exactly as InkTiles draws it.
        val alone = InkTiles.render(listOf(short("a", 40f, 64f)), 0, 0) { brushes[it] }.pixels
        assertContentEquals(alone, CelComposer.look(cleared, k00))

        // A null write where a new slab would be needed changes nothing: the same Drawn comes back.
        val empty = draw(Items.EMPTY)
        assertTrue(CelComposer.paint(empty, k10, seq = 1) { null } === empty)
    }

    // ── bake identity where D8a guarantees it ──

    @Test
    fun bakingOneLineOfTwoLeavesTheOtherDrawable() {
        val before = draw(Items(listOf(Line(2, short("a", 40f, 64f)), Line(3, short("b", 40f, 192f))),
            mapOf(k00 to listOf(Slab(1, red)))))
        val lookBefore = CelComposer.look(before, k00)
        val baked = CelComposer.bake(before, setOf("a"))
        assertEquals(listOf("b"), baked.items.lines.map { it.record.id }, "only the named line is baked")
        assertEquals(listOf("b"), CelComposer.linesOn(baked, k00).map { it.record.id }, "the other line still reaches")
        assertContentEquals(lookBefore, CelComposer.look(baked, k00), "D8a: the picture is unchanged")
        val slab = baked.items.slabs.getValue(k00).single { it.seq == 2L }
        assertEquals("normal", slab.op, "the baked line keeps its own operator")
    }

    @Test
    fun bakingAPureLineCelChangesNoPixel() {
        // No slabs at all: the line IS the picture, and baking only stores it as pixels.
        val d = draw(Items(listOf(Line(2, short("a", 40f, 64f))), emptyMap()))
        val lookBefore = assertNotNull(CelComposer.look(d, k00), "a real line draws a real tile")
        val baked = CelComposer.bake(d, setOf("a"))
        assertTrue(baked.items.lines.isEmpty(), "the line is gone")
        assertContentEquals(lookBefore, CelComposer.look(baked, k00), "D8a: bit-identical after the bake")
        assertEquals(setOf(k00), CelComposer.tiles(baked), "the tile is still claimed, now by the slab")
    }

    @Test
    fun bakingAConfinedLineLeavesTheFarTileEmpty() {
        val d = draw(Items(listOf(Line(2, short("a", 40f, 64f))), mapOf(k00 to listOf(Slab(1, red)))))
        assertNull(CelComposer.look(d, k10), "nothing reaches the far tile before the bake")
        val baked = CelComposer.bake(d, setOf("a"))
        assertNull(CelComposer.look(baked, k10), "the bake adds slabs only where the line reached")
        assertTrue(baked.items.slabs[k10] == null, "no slab leaks onto the far tile")
        assertContentEquals(CelComposer.look(d, k00), CelComposer.look(baked, k00), "D8a on the touched tile")
    }

    // ── editing lines keeps seq ──

    @Test
    fun editingALineKeepsItsSeq() {
        val rec = short("a", 40f, 64f)
        val slabs = mapOf(k00 to listOf(Slab(1, red)))
        assertEquals(black, px(CelComposer.look(draw(Items(listOf(Line(2, rec)), slabs)), k00), 100, 64))

        // Recolour: the same line (id, seed, seq) draws its new colour at its old place in time.
        val recoloured = StrokeEdit.recolor(rec, 0xFFFF0000.toInt())
        assertEquals("a", recoloured.id, "recolour keeps the id")
        assertEquals(rec.seed, recoloured.seed, "recolour keeps the seed")
        val d2 = draw(Items(listOf(Line(2, recoloured)), slabs))
        assertEquals(listOf(2L), d2.items.lines.map { it.seq }, "the seq stays with the line")
        assertEquals(opaqueRed, px(CelComposer.look(d2, k00), 100, 64),
            "opaque red at full coverage is exactly red, as black was exactly black")

        // Reshape with radius 0 moves the grabbed sample alone: sample x = 100 is index 6.
        val moved = StrokeEdit.reshape(rec, grabX = 100f, grabY = 64f, dx = 20f, dy = 0f, radius = 0f)
        assertEquals("a", moved.id, "reshape keeps the id")
        assertEquals(rec.seed, moved.seed, "reshape keeps the seed")
        assertEquals(120f, moved.samples[6].x, "the grabbed sample moves by the full offset")
        assertEquals(rec.samples[0], moved.samples[0], "the far samples stay exactly where they were")
        assertEquals(rec.samples[10], moved.samples[10])
        val d3 = draw(Items(listOf(Line(2, moved)), slabs))
        assertEquals(listOf(2L), d3.items.lines.map { it.seq })
        assertEquals(setOf(k00), CelComposer.tiles(d3), "the nudge stays inside its tile")

        // Reweight doubles the width but the fully covered centre is still exactly black.
        val heavy = StrokeEdit.reweight(rec, 2f)
        assertEquals(2f, heavy.widthScale)
        assertEquals("a", heavy.id, "reweight keeps the id")
        val d4 = draw(Items(listOf(Line(2, heavy)), slabs))
        assertEquals(black, px(CelComposer.look(d4, k00), 100, 64))
    }

    @Test
    fun applyLinesRestoresEditedLinesAtTheirSeq() {
        val a = short("a", 40f, 64f)
        val b = short("b", 40f, 192f)
        val items = Items(listOf(Line(2, a), Line(5, b)), mapOf(k00 to listOf(Slab(1, red))))
        val editA = listOf(UndoLog.LineChange("L", "c", "a", Line(2, a), Line(2, StrokeEdit.recolor(a, 0xFFFF0000.toInt()))))

        val fwd = CelComposer.applyLines(items, editA, "L", "c", forward = true)
        assertEquals(listOf(2L, 5L), fwd.lines.map { it.seq }, "the edited line keeps its seq")
        assertEquals(0xFFFF0000.toInt(), fwd.lines.single { it.record.id == "a" }.record.colorArgb)
        assertEquals(b, fwd.lines.single { it.record.id == "b" }.record, "the other line is untouched")

        val back = CelComposer.applyLines(fwd, editA, "L", "c", forward = false)
        assertEquals(a, back.lines.single { it.record.id == "a" }.record, "undo restores the record")

        // An undone delete returns to its own place in time order, not to the end.
        val deleteA = listOf(UndoLog.LineChange("L", "c", "a", Line(2, a), null))
        val deleted = CelComposer.applyLines(items, deleteA, "L", "c", forward = true)
        assertEquals(listOf("b"), deleted.lines.map { it.record.id })
        val restored = CelComposer.applyLines(deleted, deleteA, "L", "c", forward = false)
        assertEquals(listOf(2L, 5L), restored.lines.map { it.seq }, "the delete undoes into time order")

        // Another layer's steps never touch this cel.
        val foreign = listOf(UndoLog.LineChange("X", "c", "a", Line(2, a), Line(2, StrokeEdit.recolor(a, 0))))
        val same = CelComposer.applyLines(items, foreign, "L", "c", forward = true)
        assertEquals(items.lines.map { it.record }, same.lines.map { it.record })
    }

    // ── refusal: actual contract, nothing fabricated ──

    @Test
    fun aMissingBrushIsRefusedAndNeverFabricated() {
        val supported = short("s", 40f, 64f)
        // The refused line is confined to tile (1,0), which holds no slab: if the composer
        // fabricated an empty line for it, that tile would show up in tiles() or look().
        val lost = short("lost", 300f, 64f, brush = "gone")
        val d = draw(Items(listOf(Line(2, supported), Line(3, lost)), mapOf(k00 to listOf(Slab(1, red)))))
        assertEquals(listOf("lost"), d.refusals.map { it.strokeId })
        val reason = d.refusals.single().reason
        assertTrue(reason.contains("lost") && reason.contains("gone"), "the refusal names stroke and brush: $reason")

        assertEquals(listOf("s"), CelComposer.linesOn(d, k00).map { it.record.id }, "the refused line never reaches")
        assertTrue(CelComposer.linesOn(d, k10).isEmpty())
        assertEquals(setOf(k00), CelComposer.tiles(d), "the refused line claims no tile")
        assertNull(CelComposer.look(d, k10), "no fabricated pixels on the refused tile")

        // The supported line draws exactly as if the refused one had never been listed.
        val plain = draw(Items(listOf(Line(2, supported)), mapOf(k00 to listOf(Slab(1, red)))))
        assertContentEquals(CelComposer.look(plain, k00), CelComposer.look(d, k00))

        // Baking the refused id keeps the line: it is neither drawn, nor baked, nor dropped.
        val baked = CelComposer.bake(d, setOf("lost"))
        assertEquals(listOf("s", "lost"), baked.items.lines.map { it.record.id })
        assertTrue(baked.items.slabs[k10] == null)
    }

    @Test
    fun unsupportedEnginesAreRecordedNotDrawn() {
        // InkReplay refuses every engine outside StrokeEdit.INK_ENGINES by name; the composer must
        // then leave those lines alone. Media stands for the pixel-reading engines; tuft, smudge
        // and wet take the same path and are pinned here too rather than assumed.
        for (engine in listOf("media", "tuft", "smudge", "wet")) {
            val brush = pen.copy(id = "e-$engine", engine = engine)
            val rec = short("u", 40f, 64f, brush = brush.id)
            val d = CelComposer.draw(Items(listOf(Line(2, rec)), mapOf(k00 to listOf(Slab(1, red))))) {
                if (it == brush.id) brush else null
            }
            assertEquals(1, d.refusals.size, engine)
            assertTrue(d.refusals.single().reason.contains(engine), "$engine is named: ${d.refusals.single().reason}")
            assertTrue(CelComposer.linesOn(d, k00).isEmpty(), "$engine draws nothing")
            assertEquals(opaqueRed, px(CelComposer.look(d, k00), 100, 20), "$engine leaves the slab alone")
            val kept = CelComposer.bake(d, setOf("u"))
            assertEquals(listOf("u"), kept.items.lines.map { it.record.id }, "$engine is kept, never dropped")
        }
    }

    // ── compact ──

    @Test
    fun neighbouringOpaqueSlabsMergeToTheTopColourExactly() {
        // Opaque blue over opaque red is exactly blue (normal blend, source alpha 1), so unlike the
        // translucent merge in CelComposerTest this one is exact, and keeps the lower seq.
        val plain = draw(Items(emptyList(), mapOf(k00 to listOf(Slab(1, red), Slab(3, blue)))))
        val merged = CelComposer.compact(plain, k00)
        val slabs = merged.items.slabs.getValue(k00)
        assertEquals(listOf(1L), slabs.map { it.seq })
        assertContentEquals(blue, slabs.single().bytes)
        assertContentEquals(CelComposer.look(plain, k00), CelComposer.look(merged, k00))

        // A baked line's own operator never merges with anything, even with no lines left.
        val mixed = draw(Items(emptyList(), mapOf(k00 to listOf(Slab(1, red), Slab(3, blue, "behind")))))
        assertEquals(listOf(1L, 3L), CelComposer.compact(mixed, k00).items.slabs.getValue(k00).map { it.seq },
            "a non-normal slab is a merge barrier")
    }
}
