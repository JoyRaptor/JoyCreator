package cc.joycreator.joybrush.android.select

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.androidkit.lines.LineCopyBudget
import cc.joycreator.joybrush.androidkit.lines.LineCopyItem
import cc.joycreator.joybrush.androidkit.lines.LineCopyPayload
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.select.Homography
import cc.joycreator.joybrush.core.select.LineTransform

/** Process-session clipboard for editable lines. A drawing switch must not discard copied records. */
object LineSelectionClipboard {
    @Volatile private var copied: LineCopyPayload? = null

    val hasLines: Boolean get() = copied?.entries?.isNotEmpty() == true

    /** Failed admission never erases the previous clipboard. UI-supplied records cannot mint source proof. */
    fun accept(payload: LineCopyPayload): Boolean {
        if (payload.entries.isEmpty() || payload.entries.size > LineCopyBudget.MAX_LINES ||
            payload.estimatedRetainedBytes !in 1L..LineCopyBudget.MAX_BYTES ||
            payload.proof.documentGeneration < 0L || payload.proof.revision < 0L) return false
        if (payload.entries.any { it.proof !== payload.proof || it.record.id != it.address.recordId ||
                LinePlane(it.address.layerId, it.address.celId) !in payload.proof.owners }) return false
        copied = payload
        return true
    }

    fun snapshot(): List<LineCopyItem> = copied?.entries.orEmpty()

    /** A debug bench uses a second unsaved drawing and restores the owner's process-session clipboard on completion. */
    internal fun preserveForBench(): () -> Unit {
        val before = copied
        return { copied = before }
    }

    internal const val MEMORY_REFUSAL = "Not enough memory to paste those lines. Try fewer lines."
    fun canPaste(entries: List<LineCopyItem>, availableHeapBytes: Long): Boolean =
        LineCopyBudget.estimateCopied(entries)?.let { LineCopyBudget.heapAdmits(it, availableHeapBytes) } == true

    /** Build a single destination batch. The caller supplies fresh identities and target brush ids. */
    fun paste(
        entries: List<LineCopyItem>,
        plane: LinePlane,
        newIds: List<String>,
        brushIds: List<String>,
        dx: Float,
        dy: Float,
        availableHeapBytes: Long = Runtime.getRuntime().let { it.maxMemory() - (it.totalMemory() - it.freeMemory()) },
        newGroupId: () -> String = { CanvasSelectionHost.randomId("group") },
    ): List<AddressedStroke> {
        require(entries.isNotEmpty() && entries.size == newIds.size && entries.size == brushIds.size)
        require(canPaste(entries, availableHeapBytes)) { MEMORY_REFUSAL }
        require(entries.size <= LineCopyBudget.MAX_LINES)
        require(entries.all { LinePlane(it.address.layerId, it.address.celId) in it.proof.owners })
        require(newIds.all { it.isNotBlank() } && newIds.toSet().size == newIds.size)
        require(newIds.none { id -> entries.any { it.record.id == id } })
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

    internal fun clearForTests() { copied = null }
}
