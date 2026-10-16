package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.gl.lines.RecordedScene
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paper.ResolvedPaper
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.CaptureDomain
import cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import cc.joycreator.joybrush.core.vector.RecordedEdits
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

/** No-GL refusal tests for incremental replay admission before line preparation or publication. */
class GlRecordedReplayAdmissionTest {
    private val plane = "paint" to null
    private val brush = BrushPreset(id = "bounded-replay", name = "Fine pen", size = Param(4f))
    private val brushes = mapOf(brush.id to brush)
    private val allowance = 16L * Tiles.SIZE * Tiles.SIZE * 32L

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.get(owner) as T

    private fun engine() = GlPaintEngine().also {
        it.initWith { }
        it.addLayer("paint")
        it.javaClass.getDeclaredField("resolvedPaper").also { f -> f.isAccessible = true }
            .set(it, ResolvedPaper(null, null, 0xff202020.toInt(), 1f, 0f, 0f, false))
    }

    private fun record(id: String, captured: Boolean, samples: List<PenSample>) = StrokeRecord(
        id = id,
        brushId = brush.id,
        seed = 31L,
        smoothing = 0.6f,
        screenPerDoc = 12f,
        samples = samples,
        widthScale = 1f,
        seq = 1L,
        capturedPhysicalSource = if (captured) CapturedPhysicalSource(CaptureDomain()) else null,
    )

    private fun scene(engine: GlPaintEngine): RecordedScene<Int> = engine.createCapturedRecordedScene(
        mapOf(plane to RecordedCel("paint", null)), brushes, workingTileBudgetBytes = allowance,
    )

    private fun prepared(scene: RecordedScene<Int>, record: StrokeRecord) = RecordedEdits.add(
        scene.planes,
        listOf(AddressedStroke(LineAddress("paint", null, record.id), record)),
        brushes.keys,
    )

    private fun assertNoPartialAdmission(captured: Boolean, samples: List<PenSample>) {
        val engine = engine()
        val scene = scene(engine)
        val input = record("long-${if (captured) "captured" else "ordinary"}", captured, samples)
        val rawBefore = input.samples.toList()
        val stateBefore = scene.snapshotState()
        val history = field<Any>(engine, "undo")
        val cursorBefore = field<Any>(history, "localCursor")
        val rootBefore = field<Any?>(engine, "publishedPaint")
        val edit = prepared(scene, input)

        assertFailsWith<IllegalArgumentException> { scene.stage(edit) }

        assertEquals(0L, engine.recordedLinePreparationCalls)
        assertEquals(0L, engine.recordedCompositionCalls)
        assertSame(stateBefore, scene.snapshotState(), "failed admission must not publish a scene state")
        assertSame(cursorBefore, field(history, "localCursor"), "failed admission must not move history")
        assertSame(rootBefore, field(engine, "publishedPaint"), "failed admission must not replace the artwork root")
        assertEquals(rawBefore, input.samples, "raw pen samples remain intact")
        assertEquals(captured, input.capturedPhysicalSource != null)
    }

    @Test(timeout = 10_000) fun hugeRawSegmentsRefuseIncrementallyForOrdinaryAndCapturedRecords() {
        val samples = listOf(
            PenSample(0f, 100f, 0.0, pressure = 0.7f),
            PenSample(1_000_000_000f, 100f, 1.0, pressure = 0.7f),
        )
        assertNoPartialAdmission(captured = false, samples)
        assertNoPartialAdmission(captured = true, samples)
    }

    @Test fun unaddressableFirstDabRefusesBeforeLinePreparation() {
        val samples = listOf(PenSample(Float.MAX_VALUE, 100f, 0.0, pressure = 0.7f))
        assertNoPartialAdmission(captured = false, samples)
        assertNoPartialAdmission(captured = true, samples)
    }
}
