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
        assertNull(LineCopyRequest.capture(snapshot(generation = -1L), listOf(item()), Long.MAX_VALUE))
    }

    @Test fun oversizedSamplesRefuseBeforeAnySampleIsReadOrCopied() {
        var armed = false
        val oversized = record.copy(samples = object : AbstractList<PenSample>() {
            override val size get() = if (armed) Int.MAX_VALUE else 1
            override fun get(index: Int): PenSample = if (armed) error("admission must precede copying") else record.samples.first()
        })
        armed = true // StrokeRecord's own predicted-sample invariant must finish before the admission probe is armed.
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
        assertTrue(LineCopyBudget.needsAssets(brush.copy(tip = brush.tip.copy(source = "unknown"))))
        assertTrue(LineCopyBudget.needsAssets(brush.copy(tipTexture = brush.tipTexture.copy(source = "unknown"))))
    }

    @Test fun aGrowingSampleListRefusesRatherThanTruncatingOrFollowingItsNewSize() {
        val backing = mutableListOf(PenSample(1f, 2f, 0.0), PenSample(3f, 4f, 1.0))
        var reads = 0
        var armed = false
        val racing = record.copy(samples = object : AbstractList<PenSample>() {
            override val size get() = backing.size
            override fun get(index: Int): PenSample {
                if (armed && ++reads == 1) backing += PenSample(5f, 6f, 2.0)
                return backing[index]
            }
        })
        armed = true
        assertNull(LineCopyRequest.capture(snapshot(racing), listOf(item(racing)), Long.MAX_VALUE))
        assertEquals(2, reads)
        assertEquals(3, backing.size)
    }

    @Test fun aShrinkingSampleListRefusesWithoutReturningAPartialRequest() {
        val backing = mutableListOf(PenSample(1f, 2f, 0.0), PenSample(3f, 4f, 1.0))
        var reads = 0
        var armed = false
        val racing = record.copy(samples = object : AbstractList<PenSample>() {
            override val size get() = backing.size
            override fun get(index: Int): PenSample {
                if (armed && ++reads == 1) backing.removeAt(1)
                return backing[index]
            }
        })
        armed = true
        assertNull(LineCopyRequest.capture(snapshot(racing), listOf(item(racing)), Long.MAX_VALUE))
    }

    @Test fun decodeAllowanceAccountsForManySmallObjectsBeforeAnyRawCopy() {
        val dense = brush.copy(extensions = (0 until 2000).associate { "key-$it" to "" })
        val wire = assertNotNull(LineCopyBudget.brushUpperBound(dense))
        val allowance = assertNotNull(LineCopyBudget.brushDecodeBytes(wire))
        assertTrue(allowance >= wire * 8L + 64L * 1024)
        var armed = false
        val r = record.copy(samples = object : AbstractList<PenSample>() {
            override val size = 1
            override fun get(index: Int): PenSample = if (armed) error("heap admission must precede raw copy") else record.samples.first()
        })
        armed = true
        val source = LineSnapshot(1L, mapOf(plane to listOf(r)), mapOf(dense.id to dense), sourceDocumentGeneration = 0L)
        val heapEnoughOnlyForWire = 8L * 1024 * 1024 + wire * 3L
        assertNull(LineCopyRequest.capture(source, listOf(item(r)), heapEnoughOnlyForWire))
    }
}
