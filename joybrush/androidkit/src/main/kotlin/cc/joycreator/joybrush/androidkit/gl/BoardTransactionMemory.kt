package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.RegionChange
import cc.joycreator.joybrush.core.doc.BoardKind

/**
 * The board edit's atomic snapshot allowance, derived from the engine's configured undo
 * allowance. This is not a measurement of free driver memory or a whole-process governor.
 * Copies never read pixels onto the CPU: originals and staged RGBA8 textures coexist on the
 * GPU. Undo retains the originals by handle. A copy replacing an already staged texture
 * briefly needs one additional texture. Handles/maps on the CPU are bounded by these tiles.
 */
internal class BoardTransactionMemory<A>(
    private val allowanceBytes: Long,
    private val tileBytes: Long,
    private val replacementScratch: Boolean,
    private val operation: String,
) {
    private val originals = HashSet<Int>()
    private val staged = HashSet<A>()
    val originalCount: Int get() = originals.size
    val stagedCount: Int get() = staged.size
    val peakBytes: Long get() {
        val tiles = originals.size.toLong() + staged.size + if (replacementScratch && staged.isNotEmpty()) 1 else 0
        return if (tileBytes <= 0L || tiles > Long.MAX_VALUE / tileBytes) Long.MAX_VALUE else tiles * tileBytes
    }

    /** Called in preflight and again at allocation; duplicate addresses/handles are counted once. */
    fun observe(address: A, original: Int?, createsTexture: Boolean) {
        original?.let(originals::add)
        if (createsTexture) staged.add(address)
        require(peakBytes <= allowanceBytes) {
            "$operation needs at least ${mib(peakBytes)} MiB of tile snapshots, above the configured " +
                "${mib(allowanceBytes.coerceAtLeast(0))} MiB allowance. ${suggestion(operation)}"
        }
    }

    companion object {
        private fun mib(bytes: Long): Long = bytes / (1L shl 20) + if (bytes % (1L shl 20) == 0L) 0L else 1L
        private fun suggestion(operation: String): String = when (operation) {
            "Duplicating this frame" -> "Try a smaller board or fewer painted layers."
            "Removing this board or frame" -> "Clear smaller painted areas first, then remove it; each clear can be undone."
            "Swapping these cells" -> "Try smaller cells or fewer painted layers."
            "Resizing this board" -> "Try a smaller resize or fewer painted frames or layers."
            else -> "Try a smaller board or fewer painted frames or layers."
        }

        fun operation(before: JbDocument, change: RegionChange): String {
            val next = change.doc
            if (before.boards.any { old -> next.boards.none { it.id == old.id } } ||
                before.boards.any { old -> next.boards.firstOrNull { it.id == old.id }?.let { it.frames.size < old.frames.size } == true })
                return "Removing this board or frame"
            val added = next.boards.filter { after -> before.boards.none { it.id == after.id } }
            if (added.isNotEmpty()) {
                val cloned = change.transfers.any { transfer -> added.any { it.rect == transfer.destinationRect } &&
                    before.boards.any { it.kind == BoardKind.ANIMATION && it.rect == transfer.sourceRect } }
                return if (cloned) "Duplicating this board" else "Creating this board"
            }
            for (old in before.boards) {
                val after = next.boards.firstOrNull { it.id == old.id } ?: continue
                if (old.rect.w != after.rect.w || old.rect.h != after.rect.h) return "Resizing this board"
                if (old.rect.x != after.rect.x || old.rect.y != after.rect.y) return "Moving this board"
                if (after.frames.size > old.frames.size && change.copies.isNotEmpty()) return "Duplicating this frame"
            }
            if (before.layers.zip(next.layers).any { (old, after) -> old.regions != after.regions })
                return "Changing this board's layers"
            if (change.transfers.isNotEmpty() || change.maskTransfers.isNotEmpty()) return "Swapping these cells"
            if (change.copies.isNotEmpty()) return "Copying this board's paint"
            return "Changing this board"
        }
    }
}
