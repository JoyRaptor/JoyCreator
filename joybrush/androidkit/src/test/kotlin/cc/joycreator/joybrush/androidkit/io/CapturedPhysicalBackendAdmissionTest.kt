package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.CaptureDomain
import cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CapturedPhysicalBackendAdmissionTest {
    private val brush = BrushPreset(id = "frozen", name = "Pen", size = Param(4f))

    private fun record(captured: Boolean) = StrokeRecord(
        id = "line", brushId = brush.id, seed = 17L, smoothing = 0f, screenPerDoc = 1f,
        samples = listOf(PenSample(4f, 5f, 0.0)), seq = 1L,
        capturedPhysicalSource = if (captured) CapturedPhysicalSource(CaptureDomain()) else null,
    )

    private fun snapshotFixture(): Triple<cc.joycreator.joybrush.core.doc.JbDocument, Pair<String, String?>, String> {
        var id = 0
        val doc = DocOps.newDocument("snapshot", "Snapshot", 32, 32) { "snapshot-${id++}" }
        val layer = doc.layers.single()
        return Triple(doc, layer.id to null, layer.cels.single().id)
    }

    @Test
    fun physicalSnapshotRefusesCapturedSourceBeforeAnyPhysicalReadCallback() {
        val (base, address, celId) = snapshotFixture()
        val doc = base.copy(brushes = listOf(brush.id))
        val plane = RecordedCel<Int>(address.first, null, listOf(record(captured = true)))
        var callbacks = 0

        assertFailsWith<IllegalArgumentException> {
            RecordedSnapshot.capture(
                doc, mapOf(address to plane), mapOf(brush.id to brush),
                readTexture = { callbacks++; ByteArray(TILE_BYTES) },
                keys = { _, _ -> callbacks++; emptyList() },
                read = { _, _, _ -> callbacks++; ByteArray(TILE_BYTES) },
                expectedLookKeys = mapOf(address to emptyList()),
                lookRead = { _, _, _ -> callbacks++; ByteArray(TILE_BYTES) },
            )
        }
        assertEquals(0, callbacks, "source-kind refusal precedes texture, key, slab, and look reads")

        // An ordinary line in the same otherwise-valid one-cel document remains snapshotable.
        val ordinary = RecordedSnapshot.capture(
            base,
            mapOf(address to RecordedCel<Int>(address.first, null, listOf(record(captured = false)))),
            mapOf(brush.id to brush),
            readTexture = { error("there is no slab texture") },
            keys = { _, _ -> emptyList() },
            read = { _, _, _ -> error("there is no native tile") },
            expectedLookKeys = mapOf(address to emptyList()),
            lookRead = { _, _, _ -> error("there is no saved look") },
        )
        assertEquals(listOf("line"), ordinary.strokes.getValue(address.first to celId).map { it.id })
    }

    @Test
    fun publicColdPlanRejectsCapturedRecordsAndRetainsAnOrdinaryPlanControl() {
        val ordinaryInput = LineArchiveFixture.contents()
        val accepted = ColdRecordedLoadPlan.prepare(ordinaryInput)
        assertTrue(accepted.recordedPlanes.isNotEmpty())
        assertTrue(accepted.recordsCopy(ColdRecordedLoadPlan.Address("l", null)).all {
            it.capturedPhysicalSource == null
        })

        val address = "l" to "c"
        val captured = ordinaryInput.strokes.getValue(address).first().copy(
            capturedPhysicalSource = CapturedPhysicalSource(CaptureDomain()),
        )
        val capturedInput = ordinaryInput.copy(strokes = mapOf(address to listOf(captured)))
        JbArchive.requireStructure(capturedInput)
        val refused = assertFailsWith<JbArchiveException> {
            ColdRecordedLoadPlan.prepare(capturedInput)
        }
        assertTrue(refused.message.orEmpty().contains("clipping-aware cold loader"),
            "the explicit cold-plan predicate should reject after generic archive structure passes")
        assertNotNull(ordinaryInput.strokes[address])
        assertTrue(ordinaryInput.strokes.getValue(address).all { it.capturedPhysicalSource == null },
            "preparation does not mutate the caller's ordinary source")
    }
}
