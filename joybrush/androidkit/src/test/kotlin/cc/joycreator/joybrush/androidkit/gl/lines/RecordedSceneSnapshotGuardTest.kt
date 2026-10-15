package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** Contract tests for snapshot admission; fake integer handles, real scene/history, no GL. */
class RecordedSceneSnapshotGuardTest {
    private val plane = "paint" to null
    private val brush = BrushPreset(id = "pen", name = "Pen", size = Param(4f))
    private val record = StrokeRecord("line", brush.id, 1L, 0f, 1f,
        listOf(PenSample(1f, 1f, 0.0)), seq = 1L)

    private class Renderer : RecordedScene.Renderer<Int> {
        var context = true
        var contextCalls = 0
        var onContext: (() -> Unit)? = null
        override fun contextCurrent(): Boolean {
            contextCalls++
            onContext?.invoke()
            return context
        }
        override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset) =
            RecordedScene.Unit<Int>("normal", emptyMap())
        override fun look(plane: Pair<String, String?>, key: Long,
                          sources: List<RecordedScene.Source<Int>>) = 700 + key.toInt()
        override fun release(texture: Int) = Unit
    }

    private data class Root(
        override val recorded: RecordedScene.State<Int>,
        override val cursor: UndoLog.Cursor<Int>,
        override val graphicsEpoch: Long = 9L,
        override val protectedTextures: Set<Int> = emptySet(),
    ) : RecordedScene.Publication<Int>

    private class Fixture(
        val renderer: Renderer,
        val history: UndoLog<Int>,
        val scene: RecordedScene<Int>,
        var root: Root,
    ) {
        fun bind(bindHistory: Boolean = true) {
            scene.bindState { root }
            if (bindHistory) history.bindCursor { root.cursor }
        }
        fun admit(expected: Root = root) = scene.checkSnapshotPublication(expected)
    }

    private fun fixture(cold: Boolean, records: List<StrokeRecord> = emptyList(), bind: Boolean = true): Fixture {
        val renderer = Renderer()
        val history = UndoLog<Int>(1_000_000, { 4L }, {})
        val cel = RecordedCel("paint", null, records,
            slabs = mapOf(0L to listOf(RecordedCel.PixelSlab(0L, UndoLog.SlabValue(41, "normal")))))
        val scene = if (cold) RecordedScene.createSaved(
            mapOf(plane to cel), if (records.isEmpty()) emptyMap() else mapOf(brush.id to brush), history, renderer,
            mapOf(plane to if (records.isEmpty()) emptySet() else setOf(0L)),
            mapOf(plane to if (records.isEmpty()) emptyMap() else mapOf(0L to 42)),
        ) else RecordedScene.create(
            mapOf(plane to cel), if (records.isEmpty()) emptyMap() else mapOf(brush.id to brush), history, renderer,
        )
        val f = Fixture(renderer, history, scene, Root(scene.snapshotState(), history.snapshotCursor()))
        if (bind) f.bind()
        renderer.contextCalls = 0
        return f
    }

    @Test fun unboundSceneAndUnboundHistoryCannotAuthorizeSnapshot() {
        val unbound = fixture(cold = true, bind = false)
        assertFailsWith<IllegalStateException> { unbound.admit() }

        val missingHistory = fixture(cold = true, bind = false)
        missingHistory.bind(bindHistory = false)
        assertFailsWith<IllegalStateException> { missingHistory.admit() }
        assertEquals(0, missingHistory.renderer.contextCalls)
    }

    @Test fun onlyTheExactCurrentPublicationCanBeSnapshotted() {
        val f = fixture(cold = true)
        val stale = f.root
        f.root = f.root.copy(graphicsEpoch = f.root.graphicsEpoch + 1)
        assertFailsWith<IllegalStateException> { f.admit(stale) }
        assertEquals(0, f.renderer.contextCalls)
        f.admit()
        assertEquals(1, f.renderer.contextCalls)
    }

    @Test fun coldLineBearingPlaneRefusesBeforeRendererOrProviderWork() {
        val f = fixture(cold = true, records = listOf(record))
        // Admission rejects unresolved line footprints before asking the renderer about GL context.
        f.renderer.context = true
        assertFailsWith<IllegalArgumentException> { f.admit() }
        assertEquals(0, f.renderer.contextCalls)
        assertFalse(f.scene.isMaterialized(plane))
        assertSame(f.root.recorded, f.scene.snapshotState())
        assertSame(f.root.cursor, f.history.snapshotCursor())
    }

    @Test fun lineFreeColdAndMeasuredEagerPlanesAreReadOnlyAdmissible() {
        val cold = fixture(cold = true)
        val coldState = cold.root.recorded
        val coldCursor = cold.root.cursor
        cold.admit()
        assertFalse(cold.scene.isMaterialized(plane)) // no line footprint needs measuring
        assertSame(coldState, cold.scene.snapshotState())
        assertSame(coldCursor, cold.history.snapshotCursor())

        val eager = fixture(cold = false, records = listOf(record))
        assertTrue(eager.scene.isMaterialized(plane))
        val eagerState = eager.root.recorded
        val eagerCursor = eager.root.cursor
        eager.admit()
        assertSame(eagerState, eager.scene.snapshotState())
        assertSame(eagerCursor, eager.history.snapshotCursor())
        assertEquals(1, eager.renderer.contextCalls)
    }

    @Test fun outstandingJointLeaseBlocksAndAdmissionRecoversAfterClose() {
        val f = fixture(cold = false, records = listOf(record))
        val step = UndoLog.Step<Int>(emptyList(), lines = listOf(UndoLog.LineChange(
            plane.first, plane.second, record.id,
            cc.joycreator.joybrush.core.vector.CelComposer.Line(record.seq, record),
            cc.joycreator.joybrush.core.vector.CelComposer.Line(record.seq,
                record.copy(samples = listOf(PenSample(2f, 2f, 0.0))))
        )))
        val staged = f.scene.stageJoint(step)
        f.renderer.contextCalls = 0
        try {
            assertFailsWith<IllegalStateException> { f.admit() }
            assertEquals(0, f.renderer.contextCalls)
        } finally { staged.close() }
        f.admit()
        assertEquals(1, f.renderer.contextCalls)
    }

    @Test fun unavailableContextRejectsSnapshot() {
        val f = fixture(cold = true)
        f.renderer.context = false
        assertFailsWith<IllegalStateException> { f.admit() }
        assertEquals(1, f.renderer.contextCalls)
        assertSame(f.root.recorded, f.scene.snapshotState())
        assertSame(f.root.cursor, f.history.snapshotCursor())
    }

    @Test fun reentrantRootReplacementDuringContextCheckIsDetected() {
        val f = fixture(cold = true)
        val before = f.root
        f.renderer.onContext = { f.root = before.copy(graphicsEpoch = before.graphicsEpoch + 1) }
        assertFailsWith<IllegalStateException> { f.admit(before) }
        assertEquals(1, f.renderer.contextCalls)
    }

    @Test fun reentrantJointLeaseDuringContextCheckIsDetected() {
        val f = fixture(cold = false, records = listOf(record))
        val after = record.copy(samples = listOf(PenSample(2f, 2f, 0.0)))
        val step = UndoLog.Step<Int>(emptyList(), lines = listOf(UndoLog.LineChange(
            plane.first, plane.second, record.id,
            cc.joycreator.joybrush.core.vector.CelComposer.Line(record.seq, record),
            cc.joycreator.joybrush.core.vector.CelComposer.Line(after.seq, after)
        )))
        var staged: RecordedScene<Int>.JointStaged? = null
        var entered = false
        f.renderer.onContext = {
            if (!entered) {
                entered = true
                staged = f.scene.stageJoint(step)
            }
        }
        assertFailsWith<IllegalStateException> { f.admit() }
        staged?.close()
    }
}
