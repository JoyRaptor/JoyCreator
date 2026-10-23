package cc.joycreator.joybrush.core.media

/** One document tile; every atlas store for it occupies the same slot. */
data class MediaAtlasPage(val layerId: String, val planeId: String?, val tileKey: Long) {
    init { require(layerId.isNotEmpty()); require(planeId == null || planeId.isNotEmpty()) }
}

/**
 * GL-thread-confined page table. One of [physicalSlots] is always free for failure-safe staging.
 * [bytesPerSlot] includes EVERY working target and its halos; durable payload/undo have a separate budget.
 * No GL objects or durable tile contents are owned here. Live simulation and read dependencies must be pinned.
 */
class MediaAtlasSlots(
    val physicalSlots: Int, val bytesPerSlot: Long, workingBudgetBytes: Long,
    /**
     * Slots per atlas row, or 0. With it, a new page goes near the pages held now (pinned: this frame's), and room is made
     * from one of the [NEAR_CHOICES] least recently used pages, the one nearest them: a frame's pages gather in one corner
     * of the atlas, so the scissor round a pass (MediaAtlasGl.drawRect) covers less of it. The Note 9's GPU pays per pass
     * for the area under that scissor (2026-10-21).
     */
    private val columns: Int = 0,
) {
    init {
        require(physicalSlots >= 2) { "atlas needs a resident slot and a staging slot" }
        require(bytesPerSlot > 0 && workingBudgetBytes >= 0)
        require(physicalSlots.toLong() <= workingBudgetBytes / bytesPerSlot) { "atlas working allocation exceeds budget" }
    }
    val capacity: Int = physicalSlots - 1
    val workingBytes: Long = physicalSlots.toLong() * bytesPerSlot

    data class Resident(val page: MediaAtlasPage, val slot: Int, val pins: Int, val dirty: Boolean)
    sealed interface Admission
    data class Present(val slot: Int) : Admission
    data object Refused : Admission
    class Reservation internal constructor(
        val page: MediaAtlasPage, val slot: Int, val victim: Resident?,
    ) : Admission

    private val pages = LinkedHashMap<MediaAtlasPage, Resident>()
    private var pending: Reservation? = null

    fun resident(page: MediaAtlasPage): Resident? = pages[page]
    fun residents(): List<Resident> = pages.values.toList()

    /** Reserves a free staging slot, without changing any visible page mapping. */
    fun begin(page: MediaAtlasPage): Admission {
        idle()
        pages[page]?.let { touch(page); return Present(it.slot) }
        val near = heldCentre()
        val victim = if (pages.size < capacity) {
            null
        } else if (near == null) {
            pages.values.firstOrNull { it.pins == 0 } ?: return Refused
        } else {
            pages.values.asSequence().filter { it.pins == 0 }.take(NEAR_CHOICES).minByOrNull { dist2(it.slot, near) } ?: return Refused
        }
        val occupied = pages.values.mapTo(HashSet()) { it.slot }
        val free = (0 until physicalSlots).filter { it !in occupied }
        val slot = if (near == null) free.first() else free.minBy { dist2(it, near) }
        return Reservation(page, slot, victim).also { pending = it }
    }

    /** Caller has successfully loaded staging and atomically written back the dirty victim, if any. */
    fun commit(reservation: Reservation): Int {
        check(pending === reservation) { "foreign, canceled or already committed atlas reservation" }
        reservation.victim?.let { pages.remove(it.page) }
        pages[reservation.page] = Resident(reservation.page, reservation.slot, 0, false)
        pending = null
        return reservation.slot
    }

    fun cancel(reservation: Reservation) {
        check(pending === reservation) { "foreign, canceled or already committed atlas reservation" }
        pending = null
    }

    fun pin(page: MediaAtlasPage) {
        idle()
        val r = required(page)
        check(r.pins < Int.MAX_VALUE) { "atlas pin count overflow" }
        pages[page] = r.copy(pins = r.pins + 1)
    }

    fun unpin(page: MediaAtlasPage) {
        idle()
        val r = required(page)
        check(r.pins > 0) { "atlas page is not pinned" }
        pages[page] = r.copy(pins = r.pins - 1)
    }

    fun markDirty(page: MediaAtlasPage) {
        idle()
        pages[page] = required(page).copy(dirty = true)
    }

    /** The page's state was written back: it no longer needs a write-back before its slot is reused. */
    fun clean(page: MediaAtlasPage) {
        idle()
        pages[page] = required(page).copy(dirty = false)
    }

    /** Lets [page] go (the caller wrote it back first if it was dirty); its slot is free again. Must not be pinned. */
    fun remove(page: MediaAtlasPage) {
        idle()
        check(required(page).pins == 0) { "a pinned atlas page cannot leave" }
        pages.remove(page)
    }

    /** Every page leaves (the layer or the screen changed; the caller wrote back what was dirty). */
    fun clear() {
        idle()
        pages.clear()
    }

    fun touch(page: MediaAtlasPage) {
        idle()
        val r = required(page)
        pages.remove(page)
        pages[page] = r
    }

    /** Where the held pages sit (slot column, row), or null (no layout given, or nothing held). */
    private fun heldCentre(): DoubleArray? {
        if (columns <= 0) return null
        var n = 0; var x = 0.0; var y = 0.0
        for (r in pages.values) if (r.pins > 0) { n++; x += r.slot % columns; y += r.slot / columns }
        return if (n == 0) null else doubleArrayOf(x / n, y / n)
    }
    private fun dist2(slot: Int, c: DoubleArray): Double {
        val dx = slot % columns - c[0]; val dy = slot / columns - c[1]
        return dx * dx + dy * dy
    }

    private fun required(page: MediaAtlasPage) = checkNotNull(pages[page]) { "atlas page is not resident" }
    private fun idle() { check(pending == null) { "atlas replacement is already pending" } }

    companion object {
        /** How many of the least recently used pages may give way for a nearer slot. */
        const val NEAR_CHOICES = 4
    }
}
