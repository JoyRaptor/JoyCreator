package cc.joycreator.joybrush.androidkit.lines

import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.*

class LineCopyBudgetTest {
    private val plane = LinePlane("layer", null)
    private val brush = BrushPreset(id = "brush", name = "Ink", size = Param(6f))
    private val record = StrokeRecord("line", brush.id, 1L, 0f, 1f, listOf(PenSample(1f, 2f, 0.0)), seq = 1)
    private fun snapshot(r: StrokeRecord = record, generation: Long? = 7L) =
        LineSnapshot(9L, mapOf(plane to listOf(r)), mapOf(brush.id to brush), sourceDocumentGeneration = generation)
    private fun item(r: StrokeRecord = record) = AddressedStroke(plane.address(r.id), r)

    @Test fun unknownSnapshotGenerationIsRefused() {
        assertNull(LineCopyRequest.capture(snapshot(generation = null), listOf(item()), Long.MAX_VALUE))
    }

    @Test fun oversizedSamplesRefuseBeforeAnySampleIsReadOrCopied() {
        val oversized = record.copy(samples = object : AbstractList<PenSample>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): PenSample = error("admission must precede copying")
        })
        assertNull(LineCopyRequest.capture(snapshot(oversized), listOf(item(oversized)), Long.MAX_VALUE))
    }

    @Test fun acceptedRequestOwnsItsSamplesAndRetainsEpochAndRevisions() {
        val samples = record.samples.toMutableList()
        val original = record.copy(samples = samples)
        val request = assertNotNull(LineCopyRequest.capture(snapshot(original), listOf(item(original)), Long.MAX_VALUE))
        samples.clear()
        assertEquals(1, request.expected.single().record.samples.size)
        assertEquals(7L, request.documentGeneration)
        assertEquals(9L, request.revision)
        assertFailsWith<UnsupportedOperationException> { (request.expected as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (request.expected.single().record.samples as MutableList<*>).clear() }
    }

    @Test fun duplicatesMissingBrushAndInsufficientHeapRefuse() {
        assertNull(LineCopyRequest.capture(snapshot(), listOf(item(), item()), Long.MAX_VALUE))
        assertNull(LineCopyBudget.estimate(listOf(item()), emptyMap()))
        assertNull(LineCopyRequest.capture(snapshot(), listOf(item()), 1L))
        assertFalse(LineCopyBudget.heapAdmits(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test fun serializerBoundCoversEscapingAndRejectsOversizedExtensions() {
        val escaped = brush.copy(name = "Ink\n\u0000\"雪", extensions = mapOf("raw" to "\"\\\n雪".repeat(20)))
        assertTrue(assertNotNull(LineCopyBudget.brushUpperBound(escaped)) >= BrushJson.encode(escaped).encodeToByteArray().size)
        assertNull(LineCopyBudget.brushUpperBound(brush.copy(extensions = mapOf("raw" to "x".repeat(1024 * 1024)))))
    }

    @Test fun pictureTipAndImportedAssetTransferStayGated() {
        assertTrue(LineCopyBudget.needsAssets(brush.copy(tip = brush.tip.copy(source = "image", image = "custom.png"))))
        assertTrue(LineCopyBudget.needsAssets(brush.copy(sourceFormat = "abr")))
        assertFalse(LineCopyBudget.needsAssets(brush))
    }
}
