package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer
import kotlin.test.*

/** Editable lines v1.2: the step changes that move whole planes of items (clear, delete, duplicate, flatten, bake). */
class LineItemsTest {
    private val paint = LinePlane("paint", "c1")
    private val copy = LinePlane("paint-2", "c2")
    private val planeOf: (String, String?) -> LinePlane = { layer, cel -> LinePlane(layer, cel) }

    private fun line(id: String, seq: Long) = StrokeRecord(id, "pen", 7L, 0f, 1f, listOf(PenSample(1f, 2f, 0.0)), seq = seq)

    private class Fake(val tiles: Map<String, Set<Long>>) {
        fun footprint(r: StrokeRecord, b: BrushPreset?): Set<Long> = tiles[r.id].orEmpty()
    }

    /** a (seq 1) on tiles 0,1; slab (seq 2, erase) on tiles 1,2; b (seq 3) on tile 1; bases on 0 and 1. */
    private fun populated(): NativeLines {
        val nl = NativeLines(Fake(mapOf("a" to setOf(0L, 1L), "b" to setOf(1L)))::footprint)
        nl.put(paint, "a", line("a", 1)); nl.put(paint, "b", line("b", 3))
        nl.setSlab(paint, 2, 1L, 21, "erase"); nl.setSlab(paint, 2, 2L, 22, "erase")
        nl.setBase(paint, 0L, 10); nl.setBase(paint, 1L, 11)
        return nl
    }

    private fun snapshot(nl: NativeLines, plane: LinePlane) = listOf(
        nl.lines(plane).map { it.id to it.seq },
        nl.bases(plane).toSortedMap().toList(),
        nl.slabSeqs(plane).map { seq -> nl.slab(plane, seq)!!.let { Triple(seq, it.op, it.tiles.toSortedMap().toList()) } },
        nl.crossedKeys(plane).sorted(),
    )

    @Test fun removalTakesEveryRecordBaseAndSlabTileAndCarriesTheTexturesItTook() {
        val nl = populated()
        val changes = LineItems.removal(nl, paint, "paint", "c1")
        assertEquals(setOf("a", "b"), changes.lines.map { it.id }.toSet())
        assertTrue(changes.lines.all { it.after == null && it.before != null })
        assertEquals(setOf(0L to 0L, 0L to 1L, 2L to 1L, 2L to 2L), changes.slabs.map { it.seq to it.key }.toSet())
        assertEquals(setOf(10, 11, 21, 22), changes.slabs.map { it.before!!.texture }.toSet())
        assertTrue(changes.slabs.all { it.after == null })
        assertEquals("erase", changes.slabs.first { it.seq == 2L }.before!!.op)
        assertTrue(nl.hasItems(paint), "removal only describes; nothing was applied")
    }

    @Test fun applyingARemovalEmptiesThePlaneAndUndoingItBringsEverythingBackExactly() {
        val nl = populated()
        val before = snapshot(nl, paint)
        val changes = LineItems.removal(nl, paint, "paint", "c1")
        LineItems.apply(nl, changes.slabs, changes.lines, forward = true, planeOf = planeOf)
        assertFalse(nl.hasItems(paint)); assertTrue(nl.textures().isEmpty()); assertTrue(nl.crossedKeys(paint).isEmpty())
        LineItems.apply(nl, changes.slabs, changes.lines, forward = false, planeOf = planeOf)
        assertEquals(before, snapshot(nl, paint))
        assertEquals(setOf(10, 11, 21, 22), nl.textures())
        LineItems.apply(nl, changes.slabs, changes.lines, forward = true, planeOf = planeOf)
        assertFalse(nl.hasItems(paint))
    }

    @Test fun anEmptyPlaneRemovesNothing() {
        val nl = populated()
        assertTrue(LineItems.removal(nl, LinePlane("none", "x"), "none", "x").isEmpty)
    }

    @Test fun copyOfGivesTheNewPlaneTheSameItemsOnFreshTextures() {
        val nl = populated()
        var next = 100
        val changes = LineItems.copyOf(nl, paint, "paint-2", "c2") { next++ }
        // Each texture was copied once: the new plane shares none with the old one.
        val copies = changes.slabs.map { it.after!!.texture }
        assertEquals(copies.size, copies.toSet().size)
        assertTrue(copies.all { it >= 100 })
        LineItems.apply(nl, changes.slabs, changes.lines, forward = true, planeOf = planeOf)
        assertEquals(nl.lines(paint).map { it.id to it.seq }, nl.lines(copy).map { it.id to it.seq })
        assertEquals(nl.slabSeqs(paint), nl.slabSeqs(copy))
        assertEquals("erase", nl.slab(copy, 2)?.op)
        assertEquals(nl.bases(paint).keys, nl.bases(copy).keys)
        assertEquals(nl.slab(paint, 2)!!.tiles.keys, nl.slab(copy, 2)!!.tiles.keys)
        assertTrue(nl.textures().size == 8 && nl.textures().containsAll(setOf(10, 11, 21, 22)), "four originals and four copies")
        assertEquals(nl.crossedKeys(paint), nl.crossedKeys(copy))
        // Undo removes only the copy.
        LineItems.apply(nl, changes.slabs, changes.lines, forward = false, planeOf = planeOf)
        assertFalse(nl.hasItems(copy))
        assertEquals(setOf(10, 11, 21, 22), nl.textures())
    }

    // Rasterize Down's shape: the line goes and a slab takes its seq in one step. Applied forward the line must go first.
    @Test fun aLineBecomingASlabAtItsSeqAppliesInTheRightOrderBothWays() {
        val nl = NativeLines(Fake(mapOf("a" to setOf(5L)))::footprint)
        val a = line("a", 4)
        nl.put(paint, "a", a)
        nl.setBase(paint, 5L, 50)
        val lines = listOf(UndoLog.LineChange("paint", "c1", "a", CelComposer.Line(4, a), null))
        val slabs = listOf(UndoLog.SlabChange("paint", "c1", 5L, 4L, null, UndoLog.SlabValue(54, "multiply")))
        LineItems.apply(nl, slabs, lines, forward = true, planeOf = planeOf)
        assertTrue(nl.lines(paint).isEmpty())
        assertEquals("multiply", nl.slab(paint, 4)?.op)
        assertEquals(50, nl.base(paint, 5L), "the base is untouched by the swap")
        LineItems.apply(nl, slabs, lines, forward = false, planeOf = planeOf)
        assertEquals(listOf("a"), nl.lines(paint).map { it.id })
        assertNull(nl.slab(paint, 4))
        assertEquals(listOf("a"), nl.linesAt(paint, 5L).map { it.id })
    }

    @Test fun aChangeThatNamesNoCelFollowsThePlaneTheCallerResolves() {
        val nl = NativeLines(Fake(emptyMap())::footprint)
        val resolved = LinePlane("paint", "shared")
        val slabs = listOf(UndoLog.SlabChange<Int>("paint", null, 3L, 2L, null, UndoLog.SlabValue(9, "normal")))
        LineItems.apply(nl, slabs, emptyList(), true) { layer, cel -> LinePlane(layer, cel ?: "shared") }
        assertEquals(mapOf(3L to 9), nl.slab(resolved, 2)?.tiles)
    }
}
