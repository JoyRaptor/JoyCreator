package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.vector.RecordedCel
import cc.joycreator.joybrush.core.vector.RecordedEdits

/**
 * Narrow projection for pre-activation native history on transferred seq0 bases.
 * This does not authorize engine ownership transfer, prepare/publish a cursor, or release textures.
 * Engine must validate actual physical stores/context and supply only captured pre-activation Steps.
 */
internal object PromotedBaseProjection {
    class Prepared<T : Any> internal constructor(
        val fullStep: UndoLog.Step<T>,
        val recordedEdit: RecordedEdits.Prepared<T>,
        val bridgedChanges: List<UndoLog.TileChange<T>>,
    )

    fun <T : Any> prepare(
        planes: Map<Pair<String, String?>, RecordedCel<T>>,
        fullStep: UndoLog.Step<T>,
        direction: RecordedScene.Direction,
        cursor: UndoLog.Cursor<T>,
        activationHistory: Collection<UndoLog.Step<T>>,
        promotedPlanes: Set<Pair<String, String?>>,
    ): Prepared<T> {
        require(fullStep.stackBefore == null && fullStep.stackAfter == null &&
            fullStep.paperBefore == null && fullStep.paperAfter == null &&
            fullStep.documentBefore == null && fullStep.documentAfter == null) {
            "Structural/paper/document history needs a joint adapter before promotion"
        }
        require(promotedPlanes.all { it in planes }) { "A promoted physical cel is missing" }
        require(when (direction) {
            RecordedScene.Direction.NEW -> true
            RecordedScene.Direction.UNDO -> cursor.peekUndo() === fullStep
            RecordedScene.Direction.REDO -> cursor.peekRedo() === fullStep
        }) { "The complete original history Step must be the actual current cursor endpoint" }
        val bridged = fullStep.changes.filter { change ->
            val owner = MediaStores.parse(change.layerId)?.first ?: change.layerId
            (owner to change.celId) in planes
        }
        require(bridged.isEmpty() || (direction != RecordedScene.Direction.NEW && activationHistory.any { it === fullStep })) {
            "Only exact pre-activation undo/redo Steps may address a promoted base"
        }
        val targets = HashSet<Triple<String, String?, Long>>()
        val slabs = ArrayList<UndoLog.SlabChange<T>>()
        for (change in bridged) {
            require(MediaStores.parse(change.layerId) == null) { "Media history cannot become a plain base slab" }
            val address = change.layerId to change.celId
            require(address in promotedPlanes) { "This recorded cel has no native-base transfer proof" }
            require(targets.add(Triple(change.layerId, change.celId, change.key))) {
                "A native Step repeats its promoted physical target"
            }
            val cel = planes.getValue(address)
            require(address == (cel.layerId to cel.celId)) { "A physical cel has a mismatched address" }
            val base = cel.slabs[change.key]?.singleOrNull { it.seq == 0L }
            require(base == null || base.value.op == "normal") { "A promoted base must retain its normal operator" }
            require(change.before != 0 && change.after != 0) { "A promoted base endpoint must be nonzero" }
            require(change.before != change.after || change.before == null) {
                "A texture-owning legacy no-op cannot become a source/history alias"
            }
            val expected = if (direction == RecordedScene.Direction.UNDO) change.after else change.before
            require(base?.value?.texture == expected) { "Native history no longer matches its promoted base endpoint" }
            // Preserve legacy resource-free/no-op history movement without inventing a changed slab.
            if (change.before == change.after) continue
            slabs += UndoLog.SlabChange(change.layerId, change.celId, change.key, 0L,
                change.before?.let { UndoLog.SlabValue(it, "normal") },
                change.after?.let { UndoLog.SlabValue(it, "normal") })
        }
        require(fullStep.slabs.none { it.seq == 0L && Triple(it.layerId, it.celId, it.key) in targets }) {
            "A Step addresses one promoted base through two ownership families"
        }
        require(fullStep.lines.all { (it.layerId to it.celId) in planes } &&
            fullStep.slabs.all { (it.layerId to it.celId) in planes }) {
            "Native slabs and unknown line destinations need their physical owner adapter"
        }
        // Never combine native TileChanges and their same-handle SlabChanges in the actual Step.
        // The projection is borrowed, temporary, and MUST NOT enter history or a release ledger.
        val projection = UndoLog.Step<T>(emptyList(), lines = fullStep.lines, slabs = fullStep.slabs + slabs)
        return Prepared(fullStep, RecordedEdits.prepare(planes, projection,
            direction != RecordedScene.Direction.UNDO), bridged.toList())
    }

    /**
     * Borrowed physical source/display choice for the initial singleton-base scope. Does not add a
     * plain tile to the line-saved index or make its source handle an owned derived look cache.
     * Ordered multi-slab plain display remains an explicit caller composition requirement.
     */
    fun <T : Any> displayTile(cel: RecordedCel<T>, lineKeys: Set<Long>, look: T?, key: Long): T? {
        if (key in lineKeys) return requireNotNull(look) { "A line-crossed physical tile needs its actual composed look" }
        val stack = cel.slabs[key].orEmpty()
        if (stack.isEmpty()) return null
        require(stack.size == 1 && stack.single().seq == 0L && stack.single().value.op == "normal") {
            "Ordered plain slabs need a separate composed display cache"
        }
        return stack.single().value.texture
    }
}
