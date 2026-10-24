package cc.joycreator.joybrush.android.select

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.select.Homography
import cc.joycreator.joybrush.core.select.LineTransform

/** Process-session clipboard for editable lines. A drawing switch must not discard copied records. */
object LineSelectionClipboard {
    data class Entry(val record: StrokeRecord, val brush: BrushPreset?)

    @Volatile private var copied: List<Entry> = emptyList()

    val hasLines: Boolean get() = copied.isNotEmpty()

    fun copy(lines: List<Entry>) {
        copied = lines.map { entry ->
            entry.copy(record = entry.record.copy(samples = entry.record.samples.toList()))
        }
    }

    fun snapshot(): List<Entry> = copied.toList()

    /** Build a single destination batch. The caller supplies fresh identities and target brush ids. */
    fun paste(
        entries: List<Entry>,
        plane: LinePlane,
        newIds: List<String>,
        brushIds: List<String>,
        dx: Float,
        dy: Float,
        newGroupId: () -> String = { CanvasSelectionHost.randomId("group") },
    ): List<AddressedStroke> {
        require(entries.isNotEmpty() && entries.size == newIds.size && entries.size == brushIds.size)
        require(newIds.all { it.isNotBlank() } && newIds.toSet().size == newIds.size)
        require(dx.isFinite() && dy.isFinite())
        val groups = entries.mapNotNull { it.record.groupId }.distinct().associateWith { newGroupId() }
        return entries.indices.map { i ->
            val copiedRecord = entries[i].record
            val moved = if (dx == 0f && dy == 0f) copiedRecord else requireNotNull(
                LineTransform.apply(copiedRecord, Homography.translate(dx.toDouble(), dy.toDouble()), LineTransform.Weight.KEEP)
            ) { "The copied lines are too far from this view" }
            val record = moved.copy(
                id = newIds[i], brushId = brushIds[i], seq = 0L,
                groupId = copiedRecord.groupId?.let { groups.getValue(it) },
            )
            AddressedStroke(plane.address(record.id), record)
        }
    }

    internal fun clearForTests() { copied = emptyList() }
}
