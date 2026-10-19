package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.layers.LayerState
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer
import kotlin.test.*

/**
 * Editable lines v1.2 H1: pixel slabs at seq 1 and up move through the engine's real undo, redo and clear exactly like
 * lines and bases do. No GL driver: textures are plain names, and the steps are the ones the commit paths build.
 */
class GlNativeSlabHistoryTest {
    private val at = LinePlane("paint", null)

    private fun engine() = GlPaintEngine().also {
        it.initWith {}
        it.setStack(LayerStack(listOf(LayerState("paint", "Paint")), "paint"))
    }

    private fun line(id: String, seq: Long, x: Float = 1f) =
        StrokeRecord(id, "frozen", 19, 0f, 1f, listOf(PenSample(x, 0f, 0.0)), seq = seq)

    private fun slabChange(seq: Long, key: Long, before: Pair<Int, String>?, after: Pair<Int, String>?) =
        UndoLog.SlabChange("paint", null, key, seq,
            before?.let { UndoLog.SlabValue(it.first, it.second) }, after?.let { UndoLog.SlabValue(it.first, it.second) })

    @Test fun undoAndRedoOfASlabStepMoveTheSlabAndItsBaseWithTheirOperators() {
        val engine = engine()
        // What a stroke on a plane holding items commits: a base under a tile it is first to cross, and the slab itself.
        engine.nativeLines.setBase(at, 5L, 50)
        engine.nativeLines.setSlab(at, 3, 5L, 53, "multiply")
        val step = UndoLog.Step<Int>(emptyList(), slabs = listOf(
            slabChange(0, 5L, null, 50 to "normal"), slabChange(3, 5L, null, 53 to "multiply")))
        engine.undo.push(step)

        assertTrue(engine.undoStep())
        assertNull(engine.nativeLines.base(at, 5L))
        assertNull(engine.nativeLines.slab(at, 3))
        assertFalse(engine.nativeLines.hasItems(at))
        assertTrue(engine.nativeLines.textures().isEmpty())
        assertSame(step, engine.undo.peekRedo())

        assertTrue(engine.redoStep())
        assertEquals(50, engine.nativeLines.base(at, 5L))
        val slab = assertNotNull(engine.nativeLines.slab(at, 3))
        assertEquals("multiply", slab.op)
        assertEquals(mapOf(5L to 53), slab.tiles)
        assertTrue(engine.nativeLines.isCrossed(at, 5L))
        assertSame(step, engine.undo.peekUndo())
    }

    @Test fun aSlabOnATileTheSecondTimeIsOneMoreSeqAndUndoOnlyTakesTheNewestOne() {
        val engine = engine()
        engine.nativeLines.setSlab(at, 2, 1L, 21)
        engine.undo.push(UndoLog.Step<Int>(emptyList(), slabs = listOf(slabChange(2, 1L, null, 21 to "normal"))))
        engine.nativeLines.setSlab(at, 3, 1L, 31, "erase")
        engine.undo.push(UndoLog.Step<Int>(emptyList(), slabs = listOf(slabChange(3, 1L, null, 31 to "erase"))))
        assertEquals(listOf(2L, 3L), engine.nativeLines.slabSeqs(at))
        assertEquals(4L, engine.nativeLines.nextSeq(at))
        assertTrue(engine.undoStep())
        assertEquals(listOf(2L), engine.nativeLines.slabSeqs(at))
        assertEquals(3L, engine.nativeLines.nextSeq(at))
        assertTrue(engine.undoStep())
        assertFalse(engine.nativeLines.hasItems(at))
    }

    // Rasterize Down's shape: the line goes and a slab takes its seq in ONE step. Undo must take the slab away BEFORE the
    // line comes back (they share a seq), redo the other way round.
    @Test fun aStepThatSwapsALineForASlabAtItsSeqUndoesAndRedoesInTheRightOrder() {
        val engine = engine()
        val a = line("a", 4)
        engine.nativeLines.put(at, "a", a, footprint = setOf(7L))
        engine.nativeLines.put(at, "a", null)
        engine.nativeLines.setSlab(at, 4, 7L, 74, "behind")
        val step = UndoLog.Step<Int>(emptyList(),
            lines = listOf(UndoLog.LineChange("paint", null, "a", CelComposer.Line(4, a), null)),
            slabs = listOf(slabChange(4, 7L, null, 74 to "behind")))
        engine.undo.push(step)

        assertTrue(engine.undoStep())
        assertEquals(a, engine.nativeLines.record(at, "a"))
        assertNull(engine.nativeLines.slab(at, 4))
        assertTrue(engine.nativeLines.isCrossed(at, 7L), "the line crosses the tile again")

        assertTrue(engine.redoStep())
        assertNull(engine.nativeLines.record(at, "a"))
        assertEquals("behind", engine.nativeLines.slab(at, 4)?.op)
        assertEquals(listOf(4L), engine.nativeLines.seqsAt(at, 7L))
    }

    @Test fun clearingALayerThatHoldsOnlySlabsTakesThemAndTheirBasesInOneStepAndUndoBringsThemBack() {
        val engine = engine()
        engine.nativeLines.setBase(at, 0L, 10)
        engine.nativeLines.setSlab(at, 2, 0L, 20, "erase")
        engine.nativeLines.setSlab(at, 2, 1L, 21, "erase")
        assertTrue(engine.layerHasLines("paint"), "a layer with slabs counts as holding items")

        engine.clearLayer("paint")
        assertFalse(engine.layerHasLines("paint"))
        assertTrue(engine.nativeLines.textures().isEmpty())
        val step = assertNotNull(engine.undo.peekUndo())
        assertEquals(setOf(0L to 0L, 2L to 0L, 2L to 1L), step.slabs.map { it.seq to it.key }.toSet())
        assertTrue(step.slabs.all { it.after == null && it.before != null }, "the step owns every texture it took")
        assertEquals("erase", step.slabs.first { it.seq == 2L }.before?.op)

        assertTrue(engine.undoStep())
        assertEquals(10, engine.nativeLines.base(at, 0L))
        assertEquals(mapOf(0L to 20, 1L to 21), engine.nativeLines.slab(at, 2)?.tiles)
        assertEquals("erase", engine.nativeLines.slab(at, 2)?.op)

        assertTrue(engine.redoStep())
        assertFalse(engine.layerHasLines("paint"))
        assertTrue(engine.nativeLines.textures().isEmpty())
    }

    @Test fun releasingTheDocumentForgetsSlabTexturesToo() {
        val engine = engine()
        engine.nativeLines.setSlab(at, 2, 0L, 20)
        engine.nativeLines.setBase(at, 0L, 10)
        engine.resetDocument()
        assertFalse(engine.nativeLines.hasItems(at))
        assertTrue(engine.nativeLines.textures().isEmpty())
    }
}
