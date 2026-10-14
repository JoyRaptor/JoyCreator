package cc.joycreator.joybrush.core.paint

import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.paint.UndoLog.SlabChange
import cc.joycreator.joybrush.core.paint.UndoLog.SlabValue
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Ordered slab history owns textures independently of legacy tiles and composed looks. */
class SlabUndoTest {
    @Test
    fun borrowedHistoryHandlesFollowUndoRedoAndReleaseOwnershipAcrossFamilies() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(100, { 1L }, released::add)
        val edit = UndoLog.Step(listOf(UndoLog.TileChange("plain", 0, 1, 2)), slabs = listOf(
            SlabChange("paint", null, 0, 1, SlabValue(3, "normal"), SlabValue(4, "erase"))))
        assertTrue(log.heldTextures().isEmpty())
        log.push(edit)
        val detached = log.heldTextures()
        assertEquals(setOf(1, 3), detached)
        log.undo()
        assertEquals(setOf(2, 4), log.heldTextures())
        assertEquals(setOf(1, 3), detached)
        log.redo()
        assertEquals(setOf(1, 3), log.heldTextures())
        assertTrue(released.isEmpty())
        log.undo()
        log.push(UndoLog.Step(listOf(UndoLog.TileChange("plain", 1, null, 5))))
        assertTrue(log.heldTextures().isEmpty())
        assertEquals(listOf(2, 4), released)
        log.clear()
        assertTrue(log.heldTextures().isEmpty())
    }

    private fun value(texture: Int, op: String = "normal") = SlabValue(texture, op)

    private fun slab(before: SlabValue<Int>?, after: SlabValue<Int>?, layer: String = "L", cel: String? = "c",
                     key: Long = 7L, seq: Long = 1L) = SlabChange(layer, cel, key, seq, before, after)

    private fun step(vararg slabs: SlabChange<Int>) = UndoLog.Step(emptyList(), slabs = slabs.toList())

    @Test
    fun sameTileAndSequenceInDifferentLayersAndCelsStayIndependent() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(100, { 1L }, released::add)
        val addresses = listOf(
            slab(value(1), value(2), cel = null),
            slab(value(3), value(4), cel = ""),
            slab(value(5), value(6), cel = "frame-a"),
            slab(value(7), value(8), layer = "M", cel = "frame-a"),
            slab(value(9), value(10), cel = "frame-a", seq = 2),
            slab(value(11), value(12), cel = "frame-a", key = 8),
        )
        log.push(UndoLog.Step(emptyList(), slabs = addresses))
        val edited = slab(addresses[2].after, value(13, "erase"), cel = "frame-a")
        log.push(step(edited))
        log.mergeNewest(2)

        val merged = assertNotNull(log.undo())
        assertEquals(6, merged.slabs.size)
        addresses.forEachIndexed { i, original ->
            val actual = merged.slabs[i]
            assertEquals(original.layerId, actual.layerId)
            assertEquals(original.celId, actual.celId)
            assertEquals(original.key, actual.key)
            assertEquals(original.seq, actual.seq)
            assertSame(original.before, actual.before)
            assertSame(if (i == 2) edited.after else original.after, actual.after)
        }
        assertEquals(listOf(6), released)
        assertSame(merged, log.redo())
        log.clear()
        assertEquals(listOf(6, 1, 3, 5, 7, 9, 11), released)
        assertEquals(released.size, released.toSet().size)
    }

    @Test
    fun foldingKeepsEndpointTexturesAndOperatorsAndReleasesEachIntermediateOnce() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(100, { it.toLong() }, released::add)
        val oldest = value(1, "multiply")
        val middleA = value(2, "erase")
        val middleB = value(3, "normal")
        val newest = value(4, "behind")
        log.push(step(slab(oldest, middleA)))
        log.push(step(slab(middleA, middleB)))
        log.push(step(slab(middleB, newest)))
        log.mergeNewest(3)

        assertEquals(1, log.undoDepth)
        assertEquals(1L, log.heldBytes)
        val folded = assertNotNull(log.peekUndo()).slabs.single()
        assertSame(oldest, folded.before)
        assertSame(newest, folded.after)
        assertEquals("multiply", folded.before!!.op)
        assertEquals("behind", folded.after!!.op)
        assertEquals(listOf(2, 3), released)
        log.undo()
        assertEquals(4L, log.heldBytes)
        log.clear()
        log.clear()
        assertEquals(listOf(2, 3, 4), released)
    }

    @Test
    fun creationAndDeletionFoldWithNullEndpoints() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(100, { 1L }, released::add)
        val transient = value(10, "erase")
        log.push(step(slab(null, transient)))
        log.push(step(slab(transient, null)))
        log.mergeNewest(2)
        assertTrue(assertNotNull(log.undo()).slabs.isEmpty(), "a slab created and deleted in the batch leaves no change")
        assertEquals(0L, log.heldBytes)
        log.clear()
        assertEquals(listOf(10), released)
    }

    @Test
    fun branchingReleasesRedoSlabAftersAlongsideLegacyTiles() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(100, { 1L }, released::add)
        val first = step(slab(null, value(1)))
        val second = UndoLog.Step(listOf(UndoLog.TileChange("L", 7, 101, 102)),
            slabs = listOf(slab(value(1), value(2)), slab(null, value(3), cel = "other")))
        log.push(first)
        log.push(second)
        assertSame(second, log.undo())
        assertEquals(3L, log.heldBytes)
        log.push(step(slab(value(1), value(4))))

        assertFalse(log.canRedo)
        assertEquals(listOf(102, 2, 3), released)
        assertEquals(2, log.undoDepth)
        assertEquals(1L, log.heldBytes)
        log.clear()
        assertEquals(listOf(102, 2, 3, 1), released)
    }

    @Test
    fun budgetTrimsSlabBeforesAndKeepsNewestEvenWhenItExceedsBudget() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(5, { it.toLong() }, released::add)
        log.push(step(slab(value(1), value(2), seq = 1)))
        log.push(step(slab(value(3), value(4), seq = 2)))
        val newest = step(slab(value(6), value(7), seq = 3))
        log.push(newest)

        assertEquals(listOf(1, 3), released)
        assertEquals(1, log.undoDepth)
        assertEquals(6L, log.heldBytes)
        assertSame(newest, log.undo())
        assertEquals(7L, log.heldBytes)
        assertSame(newest, log.redo())
        assertEquals(6L, log.heldBytes)
        log.clear()
        assertEquals(listOf(1, 3, 6), released)
    }

    @Test
    fun clearReleasesOnlyOwnedSidesOfMixedUndoAndRedoSteps() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(100, { it.toLong() }, released::add)
        log.push(UndoLog.Step(listOf(UndoLog.TileChange("L", 1, 11, 12)),
            slabs = listOf(slab(value(1), value(2)))))
        log.push(UndoLog.Step(listOf(UndoLog.TileChange("L", 2, 13, 14)),
            slabs = listOf(slab(value(3), value(4), seq = 2))))
        log.undo()
        assertEquals(30L, log.heldBytes)
        log.clear()
        log.clear()
        assertEquals(listOf(11, 1, 14, 4), released)
        assertEquals(0L, log.heldBytes)
        assertFalse(log.canUndo)
        assertFalse(log.canRedo)
    }

    @Test
    fun extendingAndReplacingTilesPreservesSlabsAndSlabOnlyStep() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(100, { 1L }, released::add)
        val slabs = listOf(slab(null, value(1, "behind")))
        log.push(UndoLog.Step(listOf(UndoLog.TileChange("L", 1, null, 11)), slabs = slabs))
        assertTrue(log.extendNewest(listOf(UndoLog.TileChange("L", 2, null, 12))) { true })
        assertEquals(slabs, assertNotNull(log.peekUndo()).slabs)
        assertTrue(log.replaceInNewest(listOf(
            UndoLog.TileChange("L", 1, null, null), UndoLog.TileChange("L", 2, null, null))))

        assertEquals(1, log.undoDepth)
        val slabOnly = assertNotNull(log.undo())
        assertTrue(slabOnly.changes.isEmpty())
        assertEquals(slabs, slabOnly.slabs)
        assertSame(slabOnly, log.redo())
        assertTrue(released.isEmpty())
    }

    @Test
    fun replacingLastTileAlsoPreservesLineOnlyStep() {
        val log = UndoLog<Int>(1000, { 1L }, {})
        val line = CelComposer.Line(1, StrokeRecord("line", "pen", 1, 0f, 1f, emptyList()))
        val lines = listOf(UndoLog.LineChange("L", "c", "line", null, line))
        log.push(UndoLog.Step(listOf(UndoLog.TileChange("L", 1, null, 11)), lines = lines))
        assertTrue(log.replaceInNewest(listOf(UndoLog.TileChange("L", 1, null, null))))
        assertEquals(1, log.undoDepth)
        val saved = assertNotNull(log.undo()).lines.single()
        assertEquals("L", saved.layerId)
        assertEquals("c", saved.celId)
        assertEquals("line", saved.id)
        assertNull(saved.before)
        assertEquals(line.seq, saved.after!!.seq)
        assertEquals(line.record, saved.after!!.record)
    }

    @Test
    fun peeksDoNotChangeDepthBytesOwnershipOrPendingRedo() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(100, { it.toLong() }, released::add)
        assertNull(log.peekUndo())
        assertNull(log.peekRedo())
        val first = step(slab(value(1), value(2)))
        val second = step(slab(value(3), value(4), seq = 2))
        log.push(first)
        log.push(second)
        repeat(3) {
            assertSame(second, log.peekUndo())
            assertNull(log.peekRedo())
            assertEquals(2, log.undoDepth)
            assertEquals(4L, log.heldBytes)
        }
        assertSame(second, log.undo())
        repeat(3) {
            assertSame(first, log.peekUndo())
            assertSame(second, log.peekRedo())
            assertEquals(1, log.undoDepth)
            assertEquals(5L, log.heldBytes)
            assertNull(log.newestExtendable())
        }
        assertSame(second, log.redo())
        assertSame(second, log.peekUndo())
        assertNull(log.peekRedo())
        assertTrue(released.isEmpty())
    }

    @Test
    fun slabSequenceAndOperatorAreValidatedInCommonCode() {
        (BlendMode.entries.map { it.name.lowercase() } + listOf("erase", "behind")).forEach { op ->
            assertEquals(op, value(1, op).op)
        }
        assertFailsWith<IllegalArgumentException> { value(1, "unknown") }
        assertFailsWith<IllegalArgumentException> { value(1, "NORMAL") }
        assertFailsWith<IllegalArgumentException> { slab(null, value(1), seq = -1) }
        assertEquals(0L, slab(null, value(1), seq = 0).seq)
        assertEquals(Long.MAX_VALUE, slab(null, value(1), seq = Long.MAX_VALUE).seq)
    }

    @Test
    fun sharedEndpointTextureIsRefusedEvenForAnOperatorOnlyChange() {
        val before = value(1, "normal")
        assertFailsWith<IllegalArgumentException> { slab(before, value(1, "erase")) }
        assertFailsWith<IllegalArgumentException> { slab(before, before) }
        val after = value(2, "erase")
        assertSame(after, slab(before, after).after)
    }
}
