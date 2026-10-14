package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/** Prepare the complete addressed edit before the engine allocates a look or publishes history. */
object RecordedEdits {
    class Prepared<T : Any> internal constructor(
        val before: Map<Pair<String, String?>, RecordedCel<T>>,
        val after: Map<Pair<String, String?>, RecordedCel<T>>,
        val step: UndoLog.Step<T>,
        val changedPlanes: Set<Pair<String, String?>>,
    ) {
        /** The GL caller checks again after staging, immediately before publication. */
        fun isCurrent(planes: Map<Pair<String, String?>, RecordedCel<T>>): Boolean =
            planes.keys == before.keys && before.all { (key, cel) -> planes[key] === cel }
    }

    fun <T : Any> replace(planes: Map<Pair<String, String?>, RecordedCel<T>>, after: List<AddressedStroke>,
                          frozenIds: Set<String>, allowLocked: Boolean = false): Prepared<T> {
        unique(after.map { it.address })
        val changes = after.mapNotNull { entry ->
            val old = find(planes, entry.address)
            require(allowLocked || !old.locked) { "Unlock the line before editing it" }
            require(entry.record.seq == old.seq) { "Editing a line cannot change its sequence" }
            require(entry.record.brushId in frozenIds) { "A line must use a frozen document brush" }
            val next = entry.record.copy(samples = entry.record.samples.toList())
            if (old == next) null else change(entry.address, old, next)
        }
        return prepare(planes, UndoLog.Step(emptyList(), lines = changes), true)
    }

    fun <T : Any> add(planes: Map<Pair<String, String?>, RecordedCel<T>>, additions: List<AddressedStroke>,
                      frozenIds: Set<String>): Prepared<T> {
        unique(additions.map { it.address })
        val sequences = HashMap<Pair<String, String?>, Long>()
        val changes = additions.map { entry ->
            val address = entry.address
            val key = address.layerId to address.celId
            val plane = planes[key] ?: error("No physical destination for this line")
            require(plane.records.none { it.id == address.recordId }) { "A line already occupies this destination" }
            require(entry.record.brushId in frozenIds) { "A line must use a frozen document brush" }
            val seq = sequences[key] ?: plane.nextSeq()
            // MAX_VALUE is a valid final stored sequence; any further addition refuses the batch.
            require(seq > 0L) { "This cel has no sequence numbers left" }
            sequences[key] = if (seq == Long.MAX_VALUE) 0L else seq + 1L
            val record = entry.record.copy(seq = seq, samples = entry.record.samples.toList())
            change(address, null, record)
        }
        return prepare(planes, UndoLog.Step(emptyList(), lines = changes), true)
    }

    fun <T : Any> delete(planes: Map<Pair<String, String?>, RecordedCel<T>>, addresses: Set<LineAddress>,
                         allowLocked: Boolean = false): Prepared<T> {
        val changes = addresses.map { address ->
            val old = find(planes, address)
            require(allowLocked || !old.locked) { "Unlock the line before deleting it" }
            change(address, old, null)
        }
        return prepare(planes, UndoLog.Step(emptyList(), lines = changes), true)
    }

    /** Slabs and lines use one step; undo is prepared without popping the actual history yet. */
    fun <T : Any> prepare(planes: Map<Pair<String, String?>, RecordedCel<T>>, step: UndoLog.Step<T>,
                          forward: Boolean): Prepared<T> {
        val changed = (step.lines.map { it.layerId to it.celId } + step.slabs.map { it.layerId to it.celId }).toSet()
        val next = planes.toMutableMap()
        for (key in changed) {
            val before = planes[key] ?: error("No physical cel for this history step")
            require(key == (before.layerId to before.celId)) { "A physical cel has mismatched ownership" }
            next[key] = before.apply(step, forward)
        }
        return Prepared(planes.toMap(), next.toMap(), step, changed)
    }

    private fun unique(addresses: List<LineAddress>) {
        require(addresses.distinct().size == addresses.size) { "An edit names the same line twice" }
    }

    private fun <T : Any> find(planes: Map<Pair<String, String?>, RecordedCel<T>>, address: LineAddress): StrokeRecord {
        val plane = planes[address.layerId to address.celId] ?: error("No physical cel for this line")
        require(plane.layerId == address.layerId && plane.celId == address.celId) { "A physical cel has mismatched ownership" }
        return plane.records.firstOrNull { it.id == address.recordId } ?: error("This line no longer exists")
    }

    private fun change(address: LineAddress, before: StrokeRecord?, after: StrokeRecord?) =
        UndoLog.LineChange(address.layerId, address.celId, address.recordId,
            before?.let { CelComposer.Line(it.seq, it) }, after?.let { CelComposer.Line(it.seq, it) })
}
