package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.*

/** Editable lines v1.2 H3: Rasterize Down's bookkeeping. The tiles never change, so only items move. */
class LineBakeTest {
    private val paint = LinePlane("paint", "c1")
    private val planeOf: (String, String?) -> LinePlane = { layer, cel -> LinePlane(layer, cel) }

    private fun line(id: String, seq: Long) = StrokeRecord(id, "pen", 7L, 0f, 1f, listOf(PenSample(1f, 2f, 0.0)), seq = seq)

    private class Fake(val tiles: Map<String, Set<Long>>) {
        fun footprint(r: StrokeRecord, b: BrushPreset?): Set<Long> = tiles[r.id].orEmpty()
    }

    /** a (seq 1) on tiles 0,1,2; b (seq 3) on tile 1; an erase slab (seq 2) on tile 2; bases on 0, 1, 2. */
    private fun populated(): NativeLines {
        val nl = NativeLines(Fake(mapOf("a" to setOf(0L, 1L, 2L), "b" to setOf(1L)))::footprint)
        nl.put(paint, "a", line("a", 1)); nl.put(paint, "b", line("b", 3))
        nl.setSlab(paint, 2, 2L, 22, "erase")
        nl.setBase(paint, 0L, 10); nl.setBase(paint, 1L, 11); nl.setBase(paint, 2L, 12)
        return nl
    }

    private fun snapshot(nl: NativeLines) = listOf(
        nl.lines(paint).map { it.id to it.seq }, nl.bases(paint).toSortedMap().toList(),
        nl.slabSeqs(paint).map { s -> nl.slab(paint, s)!!.let { Triple(s, it.op, it.tiles.toSortedMap().toList()) } },
        nl.crossedKeys(paint).sorted(), nl.textures().sorted(),
    )

    @Test fun aBakedLineBecomesASlabAtItsOwnSeqWithItsOperatorAndTheRecordGoes() {
        val nl = populated()
        val a = nl.record(paint, "a")!!
        val changes = LineBake.plan(nl, paint, "paint", "c1", listOf(a), mapOf("a" to LineBake.Unit("multiply", mapOf(0L to 100, 1L to 101))))
        assertEquals(listOf("a"), changes.lines.map { it.id })
        assertNotNull(changes.lines.single().before); assertNull(changes.lines.single().after)
        val newSlabs = changes.slabs.filter { it.seq == 1L }
        assertEquals(setOf(0L, 1L), newSlabs.map { it.key }.toSet())
        assertTrue(newSlabs.all { it.before == null && it.after!!.op == "multiply" })
        assertEquals(setOf(100, 101), newSlabs.map { it.after!!.texture }.toSet())
    }

    @Test fun aBaseGoesOnlyWhereNothingCrossesAnyMoreAndStaysWhereASlabOrAnotherLineDoes() {
        val nl = populated()
        val a = nl.record(paint, "a")!!
        // a reached tiles 0, 1, 2. The unit only has tile 0. Tile 1 still has b; tile 2 still has the erase slab.
        val changes = LineBake.plan(nl, paint, "paint", "c1", listOf(a), mapOf("a" to LineBake.Unit("normal", mapOf(0L to 100))))
        assertTrue(changes.slabs.none { it.seq == 0L }, "tile 0 has the new slab, tile 1 has b, tile 2 has the erase slab")
        // Now a plane where nothing else crosses the tiles the unit does not cover.
        val lone = NativeLines(Fake(mapOf("a" to setOf(0L, 1L)))::footprint)
        lone.put(paint, "a", line("a", 1)); lone.setBase(paint, 0L, 10); lone.setBase(paint, 1L, 11)
        val plan = LineBake.plan(lone, paint, "paint", "c1", listOf(lone.record(paint, "a")!!),
            mapOf("a" to LineBake.Unit("normal", mapOf(0L to 100))))
        assertEquals(listOf(1L), plan.slabs.filter { it.seq == 0L }.map { it.key }, "tile 1 is crossed by nothing now")
        assertEquals(11, plan.slabs.single { it.seq == 0L }.before!!.texture)
    }

    @Test fun applyingTheBakeKeepsEveryCrossedTileCrossedAndUndoRestoresThePlaneExactly() {
        val nl = populated()
        val before = snapshot(nl)
        val a = nl.record(paint, "a")!!
        val changes = LineBake.plan(nl, paint, "paint", "c1", listOf(a), mapOf("a" to LineBake.Unit("normal", mapOf(0L to 100, 1L to 101, 2L to 102))))
        LineItems.apply(nl, changes.slabs, changes.lines, forward = true, planeOf = planeOf)
        assertEquals(listOf("b"), nl.lines(paint).map { it.id })
        assertEquals(listOf(1L, 2L), nl.slabSeqs(paint))
        assertEquals(mapOf(0L to 100, 1L to 101, 2L to 102), nl.slab(paint, 1)!!.tiles)
        assertEquals(setOf(0L, 1L, 2L), nl.crossedKeys(paint))
        assertEquals(listOf(1L, 3L), nl.seqsAt(paint, 1L), "tile 1: the baked slab, then line b")
        assertEquals(listOf(1L, 2L), nl.seqsAt(paint, 2L), "tile 2: the baked slab, then the erase slab")
        LineItems.apply(nl, changes.slabs, changes.lines, forward = false, planeOf = planeOf)
        assertEquals(before, snapshot(nl))
        LineItems.apply(nl, changes.slabs, changes.lines, forward = true, planeOf = planeOf)
        assertEquals(listOf("b"), nl.lines(paint).map { it.id })
    }

    @Test fun twoLinesBakeTogetherEachAtItsOwnSeqInOneChangeSet() {
        val nl = populated()
        val both = listOf(nl.record(paint, "a")!!, nl.record(paint, "b")!!)
        val changes = LineBake.plan(nl, paint, "paint", "c1", both, mapOf(
            "a" to LineBake.Unit("normal", mapOf(0L to 100)), "b" to LineBake.Unit("erase", mapOf(1L to 111))))
        assertEquals(setOf("a", "b"), changes.lines.map { it.id }.toSet())
        assertEquals(setOf(1L, 3L), changes.slabs.filter { it.after != null }.map { it.seq }.toSet())
        LineItems.apply(nl, changes.slabs, changes.lines, forward = true, planeOf = planeOf)
        assertTrue(nl.lines(paint).isEmpty())
        assertEquals(listOf(1L, 2L, 3L), nl.slabSeqs(paint))
        assertEquals("erase", nl.slab(paint, 3)?.op)
    }

    @Test fun aLineWithNoRenderedUnitIsRefusedBeforeAnythingIsPlanned() {
        val nl = populated()
        assertFailsWith<IllegalArgumentException> { LineBake.plan(nl, paint, "paint", "c1", listOf(nl.record(paint, "a")!!), emptyMap()) }
    }
}
