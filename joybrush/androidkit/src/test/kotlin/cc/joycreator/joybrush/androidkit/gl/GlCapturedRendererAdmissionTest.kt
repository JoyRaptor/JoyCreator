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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** No-GL tests for opt-in and pre-allocation gates; fake texture names are never sampled. */
class GlCapturedRendererAdmissionTest {
    private val plane = "paint" to null
    private val brush = BrushPreset(id = "frozen", name = "Pen", size = Param(4f))
    private val brushes = mapOf(brush.id to brush)

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.get(owner) as T

    private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.set(owner, value)

    private fun engine() = GlPaintEngine().also {
        it.initWith { }
        it.addLayer("paint")
        set(it, "resolvedPaper", paper(0xff202020.toInt()))
    }

    private fun paper(color: Int) = ResolvedPaper(
        surface = null, look = null, baseArgb = color, scale = 1f,
        show = 0f, bite = 0f, light = false,
    )

    private fun record(
        id: String = "line", seq: Long = 1L, captured: Boolean = false,
        samples: List<PenSample> = listOf(PenSample(2f, 3f, 0.0)), widthScale: Float = 1f,
        screenPerDoc: Float = 1f,
    ) = StrokeRecord(
        id = id, brushId = brush.id, seed = 19L, smoothing = 0f, screenPerDoc = screenPerDoc,
        samples = samples, widthScale = widthScale, seq = seq,
        capturedPhysicalSource = if (captured) CapturedPhysicalSource(CaptureDomain()) else null,
    )

    private fun longSamples(count: Int = 96) = List(count) { index ->
        PenSample(5_000f + index % 3, 6_000f + index % 2, index * 1.25, pressure = 0.3f + (index % 5) * 0.1f)
    }

    private fun scene(engine: GlPaintEngine, budget: Long = 0L): RecordedScene<Int> =
        engine.createCapturedRecordedScene(
            mapOf(plane to RecordedCel("paint", null)), brushes, workingTileBudgetBytes = budget,
        )

    private fun renderer(scene: RecordedScene<Int>): RecordedScene.Renderer<Int> = field(scene, "renderer")

    @Test fun defaultRendererIsNotCapturedCapableButExplicitFactoryOptsIn() {
        val ordinaryEngine = engine()
        val ordinary = ordinaryEngine.createRecordedScene(
            mapOf(plane to RecordedCel("paint", null)), brushes,
        )
        assertFalse(renderer(ordinary) is RecordedScene.CapturedSourceRenderer<*>)

        val refusingEngine = engine()
        assertFails {
            refusingEngine.createRecordedScene(
                mapOf(plane to RecordedCel("paint", null, listOf(record(captured = true)))), brushes,
            )
        }
        assertEquals(0L, refusingEngine.recordedLinePreparationCalls)
        assertEquals(0L, refusingEngine.recordedCompositionCalls)

        val captured = scene(engine())
        assertIs<RecordedScene.CapturedSourceRenderer<*>>(renderer(captured))
    }

    @Test fun zeroBudgetRefusesOrdinaryReplayBeforePreparation() {
        val engine = engine()
        val scene = scene(engine)
        val edit = cc.joycreator.joybrush.core.vector.RecordedEdits.add(
            scene.planes,
            listOf(cc.joycreator.joybrush.core.stroke.AddressedStroke(
                LineAddress("paint", null, "ordinary"), record(id = "ordinary"),
            )),
            brushes.keys,
        )

        assertFails { scene.stage(edit) }
        assertEquals(0L, engine.recordedLinePreparationCalls)
        assertEquals(0L, engine.recordedCompositionCalls)
    }

    @Test fun capturedBudgetAdmissionAcceptsLongRawRecipeOutsideDiagnosticLimits() {
        val engine = engine()
        val scene = scene(engine, budget = 64L * 1024 * 1024)
        val samples = longSamples()
        val input = record(captured = true, samples = samples, widthScale = 3f, screenPerDoc = 12f)
        val renderer = assertIs<RecordedScene.CapturedSourceRenderer<Int>>(renderer(scene))

        assertTrue(renderer.admitCapturedSource(LineAddress("paint", null, "line"), input, brush))
        assertEquals(samples, input.samples)
        assertEquals(3f, input.widthScale)
        assertEquals(12f, input.screenPerDoc)
        assertEquals(0L, engine.recordedLinePreparationCalls)
        assertEquals(0L, engine.recordedCompositionCalls)
    }

    @Test fun zeroBudgetRefusesLongCapturedAndOrdinaryRendererLinesBeforeGpuPreparation() {
        fun attempt(captured: Boolean) {
            val engine = engine()
            val scene = scene(engine, budget = 0L)
            val samples = longSamples()
            val longRecord = record(id = "long", captured = captured, samples = samples,
                widthScale = 3f, screenPerDoc = 12f)
            val edit = cc.joycreator.joybrush.core.vector.RecordedEdits.add(
                scene.planes,
                listOf(cc.joycreator.joybrush.core.stroke.AddressedStroke(
                    LineAddress("paint", null, "long"), longRecord,
                )),
                brushes.keys,
            )

            assertFails { scene.stage(edit) }
            assertEquals(0L, engine.recordedLinePreparationCalls)
            assertEquals(0L, engine.recordedCompositionCalls)
        }

        attempt(captured = true)
        attempt(captured = false)
    }

    @Test fun lookAndBakePeaksRefuseBeforeCompositionAndEmptyBakeDoesNotCompose() {
        val engine = engine()
        val scene = scene(engine, budget = 0L)
        val renderer = renderer(scene)
        assertIs<RecordedScene.CapturedSourceRenderer<Int>>(renderer)

        assertFails { renderer.look(plane, 0L, listOf(RecordedScene.Source(1L, 73, "normal"))) }
        assertEquals(0L, engine.recordedCompositionCalls)
        assertFails {
            renderer.copyForBake(
                LineAddress("paint", null, "ordinary"),
                RecordedScene.Unit("normal", mapOf(0L to 74)),
            )
        }
        assertEquals(0L, engine.recordedCompositionCalls)
        val emptyBake = renderer.copyForBake(
            LineAddress("paint", null, "empty"), RecordedScene.Unit("normal", emptyMap()),
        )
        assertTrue(emptyBake.tiles.isEmpty())
        assertEquals(0L, engine.recordedCompositionCalls)

        // A one-tile look peak fits exactly unless separately staged working owners are included.
        val area = Tiles.SIZE.toLong() * Tiles.SIZE
        val exactLookPeak = RecordedWorkingTilePlan.compose(1)
        val stagedEngine = engine()
        val stagedScene = scene(stagedEngine, budget = exactLookPeak)
        val stagedLedger = field<RecordedWorkingTileLedger>(stagedEngine, "recordedWorkingTiles")
        val stagedEpoch = field<Long>(stagedEngine, "physicalContextEpoch")
        stagedLedger.register(stagedEpoch, listOf(75), area * 4L)
        val stagedRenderer = renderer(stagedScene)
        assertFails {
            stagedRenderer.look(plane, 0L, listOf(RecordedScene.Source(1L, 76, "normal")))
        }
        assertEquals(0L, stagedEngine.recordedCompositionCalls)
    }

    @Test fun paperMutationInvalidatesCapturedAdmission() {
        val engine = engine()
        val scene = scene(engine)
        val renderer = assertIs<RecordedScene.CapturedSourceRenderer<Int>>(renderer(scene))
        val address = LineAddress("paint", null, "captured")
        assertTrue(renderer.contextCurrent())

        set(engine, "resolvedPaper", paper(0xff303030.toInt()))
        assertFalse(renderer.contextCurrent())
        assertFalse(renderer.admitCapturedSource(address, record(id = "captured", captured = true), brush))
        assertEquals(0L, engine.recordedLinePreparationCalls)
        assertEquals(0L, engine.recordedCompositionCalls)
    }

    @Test fun pendingTileBytesAreActualOwnersAndLedgerRemainsSeparatelyCharged() {
        val engine = engine()
        val owner = field<Map<String, Any>>(engine, "localLayers").getValue("paint")
        field<MutableMap<Long, Int>>(owner, "tiles")[0L] = 90

        val area = Tiles.SIZE.toLong() * Tiles.SIZE
        set(engine, "recordedPendingTileBytes", mapOf(90 to area * 8L, 91 to area * 8L))
        val actual = engine.recordedWorkingTileBytes()
        assertEquals(area * 8L, actual.getValue(90), "Pending RGBA16F metadata wins over the RGBA8 layer alias")
        assertEquals(area * 8L, actual.getValue(91))

        val ledger = field<RecordedWorkingTileLedger>(engine, "recordedWorkingTiles")
        val epoch = field<Long>(engine, "physicalContextEpoch")
        ledger.register(epoch, listOf(92), area * 4L)
        assertFalse(92 in actual, "Staged ledger bytes stay separate from the actual-owner inventory")
        val total = area * 20L
        assertEquals(total, ledger.admit(epoch, actual, 0L, total))
        assertFailsWith<IllegalArgumentException> { ledger.admit(epoch, actual, 0L, total - 1L) }
    }
}
