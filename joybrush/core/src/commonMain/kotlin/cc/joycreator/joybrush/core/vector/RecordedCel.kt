package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/**
 * Physical cel metadata beside GPU textures. Null celId denotes the engine's shared plane.
 * The look cache is deliberately absent: it is derived from these ordered items, never a slab.
 * Preparing history returns a new value and cannot mutate the current cel if validation fails.
 * Texture ownership changes only when the caller publishes this value with its history step.
 */
class RecordedCel<T : Any>(
    val layerId: String,
    val celId: String?,
    records: List<StrokeRecord> = emptyList(),
    slabs: Map<Long, List<PixelSlab<T>>> = emptyMap(),
) {
    class PixelSlab<T : Any>(val seq: Long, val value: UndoLog.SlabValue<T>)

    val records: List<StrokeRecord> = records.map { it.copy(samples = it.samples.toList()) }.sortedBy { it.seq }
    val slabs: Map<Long, List<PixelSlab<T>>> = slabs.filterValues { it.isNotEmpty() }
        .mapValues { (_, list) -> list.toList().sortedBy { it.seq } }

    init {
        require(layerId.isNotBlank() && (celId == null || celId.isNotBlank())) { "A physical cel needs its full address" }
        require(this.records.map { it.id }.toSet().size == this.records.size) { "Two records share an id within a cel" }
        require(this.records.all { it.seq > 0L }) { "A stored record needs a positive sequence" }
        val sequences = this.records.map { it.seq }.toSet()
        require(sequences.size == this.records.size) { "Two lines share a sequence within a cel" }
        for (list in this.slabs.values) {
            require(list.all { it.seq >= 0L && it.seq !in sequences }) { "A pixel slab collides with a line sequence" }
            require(list.map { it.seq }.toSet().size == list.size) { "Two slabs share a sequence on a tile" }
        }
        val textures = this.slabs.values.flatMap { list -> list.map { it.value.texture } }
        require(textures.distinct().size == textures.size) { "A texture cannot be owned by two pixel slabs" }
    }

    /** An exhausted sequence space is a refusal before a stroke allocates or edits anything. */
    fun nextSeq(): Long {
        val last = maxOf(records.maxOfOrNull { it.seq } ?: 0L,
            slabs.values.maxOfOrNull { list -> list.maxOf { it.seq } } ?: 0L)
        require(last < Long.MAX_VALUE) { "This cel has no sequence numbers left" }
        return last + 1L
    }

    /** D2: paint reuses only a normal top slab with no newer intersecting line. */
    fun writableTop(key: Long, reaches: (StrokeRecord, Long) -> Boolean): PixelSlab<T>? {
        val top = slabs[key]?.lastOrNull() ?: return null
        if (top.value.op != CelComposer.NORMAL) return null
        return top.takeUnless { records.any { line -> line.seq > top.seq && reaches(line, key) } }
    }

    /** Apply only this exact layer/cel's changes, checking their expected source before staging. */
    fun apply(step: UndoLog.Step<T>, forward: Boolean): RecordedCel<T> {
        val changedLines = step.lines.filter { it.layerId == layerId && it.celId == celId }
        val changedSlabs = step.slabs.filter { it.layerId == layerId && it.celId == celId }
        if (changedLines.isEmpty() && changedSlabs.isEmpty()) return this
        val lines = records.associateBy { it.id }.toMutableMap()
        val pixels = slabs.mapValues { (_, list) -> list.associateBy { it.seq }.toMutableMap() }.toMutableMap()
        for (change in if (forward) changedLines else changedLines.asReversed()) {
            val expected = if (forward) change.before else change.after
            val after = if (forward) change.after else change.before
            for (line in listOfNotNull(expected, after)) {
                require(line.record.id == change.id && line.seq == line.record.seq) { "Line history has mismatched identity or sequence" }
            }
            require(lines[change.id] == expected?.record) { "Line history does not match the current cel" }
            lines.remove(change.id)
            after?.record?.let { lines[change.id] = it }
        }
        for (change in if (forward) changedSlabs else changedSlabs.asReversed()) {
            val expected = if (forward) change.before else change.after
            val after = if (forward) change.after else change.before
            val list = pixels.getOrPut(change.key) { linkedMapOf() }
            val present = list[change.seq]?.value
            require(sameSlab(present, expected)) { "Slab history does not match the current cel" }
            list.remove(change.seq)
            after?.let { list[change.seq] = PixelSlab(change.seq, it) }
        }
        return RecordedCel(layerId, celId, lines.values.toList(), pixels.mapValues { it.value.values.toList() })
    }

    private fun sameSlab(a: UndoLog.SlabValue<T>?, b: UndoLog.SlabValue<T>?): Boolean =
        if (a == null || b == null) a == null && b == null else a.texture == b.texture && a.op == b.op

    companion object {
        /** Promotion retains the old plain tile as seq0. It neither copies nor releases a texture. */
        fun <T : Any> fromPixels(layerId: String, celId: String?, tiles: Map<Long, T>): RecordedCel<T> =
            RecordedCel(layerId, celId, slabs = tiles.mapValues { (_, texture) ->
                listOf(PixelSlab(0L, UndoLog.SlabValue(texture, CelComposer.NORMAL)))
            })
    }
}
