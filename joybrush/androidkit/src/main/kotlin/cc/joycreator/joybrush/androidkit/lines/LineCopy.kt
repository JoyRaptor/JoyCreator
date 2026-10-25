package cc.joycreator.joybrush.androidkit.lines

import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import java.util.Collections
import cc.joycreator.joybrush.core.input.PenSample
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule

/** Runtime Copy metadata only. No edit, schema change, renderer activation or captured-source admission. */
sealed interface LineCopyResult {
    class Ready internal constructor(val payload: LineCopyPayload) : LineCopyResult
    data class Refused(val message: String) : LineCopyResult
}

/** Module-internal issuance: the engine checks each real plane before creating this proof. Not request authority. */
class LineCopyProof internal constructor(
    val documentGeneration: Long,
    val revision: Long,
    owners: Set<LinePlane>,
) {
    val owners: Set<LinePlane> = Collections.unmodifiableSet(LinkedHashSet(owners))
}

/** Frozen value data from one accepted source. Clients cannot replace records while retaining the proof. */
class LineCopyItem private constructor(
    val address: cc.joycreator.joybrush.core.stroke.LineAddress,
    val record: StrokeRecord,
    private val brushJson: String,
    val proof: LineCopyProof,
) {
    /** Decode a separate brush value; mutating a caller's collections cannot alter this payload. */
    fun brush(): BrushPreset = BrushJson.decodeChecked(brushJson)

    companion object {
        internal fun freeze(address: cc.joycreator.joybrush.core.stroke.LineAddress, record: StrokeRecord,
                            admittedSamples: Int, brushJson: String, proof: LineCopyProof): LineCopyItem? {
            val frozen = freezeSamples(record, admittedSamples) ?: return null
            return LineCopyItem(address, frozen, brushJson, proof)
        }
    }
}

class LineCopyPayload internal constructor(
    val proof: LineCopyProof,
    entries: List<LineCopyItem>,
    val estimatedRetainedBytes: Long,
) {
    val entries: List<LineCopyItem> = Collections.unmodifiableList(ArrayList(entries))
}

/** A bounded frozen expectation captured before queuing GL work; it is not itself a source-domain proof. */
class LineCopyRequest private constructor(
    val documentGeneration: Long,
    val revision: Long,
    val brushRevision: Long,
    expected: List<AddressedStroke>,
    val estimatedRetainedBytes: Long,
) {
    val expected: List<AddressedStroke> = Collections.unmodifiableList(expected)

    companion object {
        fun capture(snapshot: LineSnapshot, expected: List<AddressedStroke>, availableHeapBytes: Long): LineCopyRequest? {
            val generation = snapshot.sourceDocumentGeneration ?: return null
            if (generation < 0L || snapshot.revision < 0L || snapshot.brushRevision < 0L) return null
            val count = expected.size
            if (count == 0 || count > LineCopyBudget.MAX_LINES) return null
            return try {
                // Bound collection growth too; never delegate a live producer's collection.toArray().
                val refs = ArrayList<AddressedStroke>(count)
                for (i in 0 until count) refs += expected[i]
                if (expected.size != count) return null
                val sizes = IntArray(count) { refs[it].record.samples.size }
                val bytes = LineCopyBudget.estimateCounted(refs, snapshot.brushes, sizes) ?: return null
                if (!LineCopyBudget.heapAdmits(bytes, availableHeapBytes)) return null
                if (refs.map { it.address }.toSet().size != count) return null
                val frozen = ArrayList<AddressedStroke>(count)
                for (i in 0 until count) {
                    val record = freezeSamples(refs[i].record, sizes[i]) ?: return null
                    frozen += refs[i].copy(record = record)
                }
                LineCopyRequest(generation, snapshot.revision, snapshot.brushRevision, frozen, bytes)
            } catch (_: IndexOutOfBoundsException) { null }
              catch (_: ConcurrentModificationException) { null }
              catch (_: OutOfMemoryError) { null }
        }
    }
}

/** Admit before cloning sample lists or serialising brush definitions. Nothing is truncated to fit. */
object LineCopyBudget {
    const val MAX_LINES = 2048
    const val MAX_BYTES = 16L * 1024 * 1024
    /** Same per-definition ceiling as the archive's kept-brush entries. */
    const val MAX_BRUSH_BYTES = 4L * 1024 * 1024
    private const val SAMPLE_BYTES = 128L // conservative heap allowance, above StrokeCodec's 21–33 byte wire sample
    private const val HEAP_RESERVE = 8L * 1024 * 1024

    fun estimate(records: List<AddressedStroke>, brushes: Map<String, BrushPreset>): Long? {
        if (records.isEmpty() || records.size > MAX_LINES) return null
        return try { estimateCounted(records, brushes, IntArray(records.size) { records[it].record.samples.size }) }
        catch (_: IndexOutOfBoundsException) { null }
        catch (_: ConcurrentModificationException) { null }
    }

    internal fun estimateCounted(records: List<AddressedStroke>, brushes: Map<String, BrushPreset>, counts: IntArray): Long? {
        if (counts.isEmpty() || counts.size > MAX_LINES || records.size != counts.size) return null
        var total = 256L
        val brushIds = LinkedHashSet<String>()
        for (i in counts.indices) {
            val r = records[i].record
            if (counts[i] < 0) return null
            if (r.capturedPhysicalSource != null) return null
            val strings = r.id.length.toLong() + r.brushId.length + (r.groupId?.length ?: 0)
            val cost = 512L + strings * 6L + counts[i].toLong() * SAMPLE_BYTES
            if (cost > MAX_BYTES - total) return null
            total += cost
            brushIds += r.brushId
        }
        for (id in brushIds) {
            val preset = brushes[id] ?: return null
            if (needsAssets(preset)) return null
            val cost = brushUpperBound(preset) ?: return null
            if (cost > MAX_BYTES - total) return null
            total += cost
        }
        return total
    }

    /** Request, accepted replacement and previous clipboard may coexist; reserve space for all three. */
    fun heapAdmits(bytes: Long, available: Long): Boolean =
        bytes in 1..MAX_BYTES && available >= bytes * 3L + HEAP_RESERVE

    fun needsAssets(p: BrushPreset): Boolean = p.sourceFormat != "native" || p.tip.source != "procedural" ||
        p.tip.image != null || p.tipTexture.source != "cloud" || p.tipTexture.image != null ||
        p.paperGrain.source != "cloud" || p.paperGrain.image != null ||
        p.engine !in setOf("stamp", "fill", "tuft", cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR)

    fun brushUpperBound(preset: BrushPreset): Long? = try {
        val counter = BrushSizeCounter()
        BrushPreset.serializer().serialize(counter, preset)
        counter.bytes
    } catch (_: TooLarge) { null }

    private class TooLarge : RuntimeException()

    /** Counts a conservative JSON upper bound using the real versioned serializer, without allocating JSON. */
    @OptIn(ExperimentalSerializationApi::class)
    private class BrushSizeCounter : AbstractEncoder() {
        override val serializersModule = EmptySerializersModule()
        var bytes = 0L
            private set
        private fun add(n: Long) {
            if (n > MAX_BRUSH_BYTES - bytes) throw TooLarge()
            bytes += n
        }
        override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int) = true
        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder { add(2L); return this }
        override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder {
            if (collectionSize > 16384) throw TooLarge()
            return beginStructure(descriptor)
        }
        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            add(descriptor.getElementName(index).length.toLong() * 6L + 4L)
            return true
        }
        override fun encodeString(value: String) { add(value.length.toLong() * 6L + 2L) }
        override fun encodeValue(value: Any) { add(32L) }
        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) { encodeString(enumDescriptor.getElementName(index)) }
        override fun encodeNull() { add(4L) }
        override fun encodeNotNullMark() { add(1L) }
    }
}

/** Producer-owned records are immutable; still bound and recheck ordinary collection races at the boundary. */
private fun freezeSamples(record: StrokeRecord, admitted: Int): StrokeRecord? {
    if (admitted < 0 || admitted.toLong() * 128L > LineCopyBudget.MAX_BYTES || record.samples.size != admitted) return null
    return try {
        val samples = ArrayList<PenSample>(admitted)
        for (i in 0 until admitted) samples += record.samples[i]
        if (record.samples.size != admitted) null
        else record.copy(samples = Collections.unmodifiableList(samples))
    } catch (_: IndexOutOfBoundsException) { null }
      catch (_: ConcurrentModificationException) { null }
}
