package cc.joycreator.joybrush.android.chrome

/**
 * What to throw out of the stored previews after one is written. Pure: it is given the folder's listing and says which names
 * go. A preview belongs to one version of one brush, so the first to go are the other versions of the brush just written (a
 * brush that was tuned leaves its old pictures behind); after that the oldest go until the folder fits its limits.
 */
object PreviewDiskTrim {

    class Entry(val name: String, val bytes: Long, val modifiedMs: Long)

    class Limits(val maxFiles: Int = 400, val maxBytes: Long = 48L shl 20)

    /** The names to delete. The just-written [keep] is never one of them, and files that are not ours are never touched. */
    fun victims(listing: List<Entry>, keep: BrushPreviewKey.Key, limits: Limits): List<String> {
        val ours = listing.mapNotNull { e -> BrushPreviewKey.parse(e.name)?.let { it to e } }
        val out = ArrayList<String>()
        val stale = ours.filter { (k, _) -> k.idPart == keep.idPart && k.hash != keep.hash }
        stale.forEach { out.add(it.second.name) }
        val left = ours.filter { (_, e) -> e.name !in out }.sortedBy { it.second.modifiedMs }
        var files = left.size
        var bytes = left.sumOf { it.second.bytes }
        for ((k, e) in left) {
            if (files <= limits.maxFiles && bytes <= limits.maxBytes) break
            if (k == keep) continue
            out.add(e.name)
            files--
            bytes -= e.bytes
        }
        return out
    }
}
