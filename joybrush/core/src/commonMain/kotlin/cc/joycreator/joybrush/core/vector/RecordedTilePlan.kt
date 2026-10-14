package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/** A missing authoritative footprint is a render refusal, never a guessed rectangle or CPU look. */
class RecordedTilePlanException(val address: LineAddress) : IllegalArgumentException(
    "Missing recorded-line tile footprint for layer \"${address.layerId}\", " +
        "cel ${address.celId?.let { "\"$it\"" } ?: "shared"}, record \"${address.recordId}\"",
)

/**
 * Pure ordered inputs for a physical cel's GPU tile composition. The caller replays each line ONCE
 * and supplies the actual unit-buffer tile keys, addressed by layer, cel and record ID. An explicit
 * empty set means the replay touches no tiles; a missing entry refuses the entire capture.
 *
 * No replay, bounds scan or pixel rendering happens here. Lines retain their recordings and addresses
 * so the caller can retrieve their GPU units and frozen-brush operators. Pixels retain their slab's
 * operator. The tile look is derived from these items and is never substituted for a pixel slab.
 *
 * Capture owns copies of collections and sample lists. Texture handles remain borrowed: this plan
 * neither copies, mutates nor releases their resources; the caller preserves their lifetime.
 */
class RecordedTilePlan<T : Any>(cel: RecordedCel<T>, footprints: Map<LineAddress, Set<Long>>) {
    sealed class Item<out T : Any> { abstract val seq: Long }
    class Line(val address: LineAddress, val record: StrokeRecord) : Item<Nothing>() {
        override val seq: Long get() = record.seq
    }
    class Pixel<T : Any>(override val seq: Long, val value: UndoLog.SlabValue<T>) : Item<T>()

    val layerId: String = cel.layerId
    val celId: String? = cel.celId

    private class CapturedLine(val line: Line, val keys: Set<Long>)
    private val lines: Map<LineAddress, CapturedLine>
    private val pixels: Map<Long, List<Pixel<T>>>
    private val tiles: Map<Long, List<Item<T>>>
    val tileKeys: Set<Long>

    init {
        lines = cel.records.associate { record ->
            val address = LineAddress(layerId, celId, record.id)
            val keys = footprints[address] ?: throw RecordedTilePlanException(address)
            address to CapturedLine(Line(address, record.copy(samples = record.samples.toList())), keys.toSet())
        }
        pixels = cel.slabs.mapValues { (_, slabs) ->
            slabs.map { Pixel(it.seq, UndoLog.SlabValue(it.value.texture, it.value.op)) }
        }
        val index = LinkedHashMap<Long, MutableList<Item<T>>>()
        for ((key, slabs) in pixels) index.getOrPut(key) { ArrayList() }.addAll(slabs)
        for (captured in lines.values) for (key in captured.keys) {
            index.getOrPut(key) { ArrayList() }.add(captured.line)
        }
        tiles = index.mapValues { (_, items) -> items.sortedBy { it.seq } }
        tileKeys = tiles.keys.toSet()
    }

    /** Whole stroke units and pixel slabs, interleaved strictly by seq. An absent tile has no items. */
    fun itemsOn(key: Long): List<Item<T>> = tiles[key].orEmpty()

    companion object {
        /**
         * Tiles to rebuild when replacing this physical cel's captured content. Old footprints are
         * retained for deletes and moves, even if the new plan has no content at those keys. An
         * unchanged line does not invalidate its tiles. A changed footprint counts even when its
         * recording is unchanged. Pixel comparisons include seq, texture and operator.
         */
        fun affectedTiles(before: RecordedTilePlan<*>?, after: RecordedTilePlan<*>?): Set<Long> {
            require(before == null || after == null ||
                (before.layerId == after.layerId && before.celId == after.celId)) {
                "Tile invalidation must compare the same physical cel"
            }
            if (before == null) return after?.tileKeys?.toSet().orEmpty()
            if (after == null) return before.tileKeys.toSet()
            val out = LinkedHashSet<Long>()
            for (address in before.lines.keys + after.lines.keys) {
                val old = before.lines[address]
                val new = after.lines[address]
                if (old?.line?.record != new?.line?.record || old?.keys != new?.keys) {
                    old?.let { out.addAll(it.keys) }
                    new?.let { out.addAll(it.keys) }
                }
            }
            for (key in before.pixels.keys + after.pixels.keys) {
                val old = before.pixels[key].orEmpty()
                val new = after.pixels[key].orEmpty()
                if (old.size != new.size || old.indices.any { i ->
                        old[i].seq != new[i].seq || old[i].value.texture != new[i].value.texture ||
                            old[i].value.op != new[i].value.op
                    }) out.add(key)
            }
            return out
        }
    }
}
