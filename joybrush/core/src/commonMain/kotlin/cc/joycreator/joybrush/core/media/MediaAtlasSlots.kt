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
class MediaAtlasSlots(val physicalSlots: Int, val bytesPerSlot: Long, workingBudgetBytes: Long) {
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
        val victim = if (pages.size < capacity) {
            null
        } else {
            pages.values.firstOrNull { it.pins == 0 } ?: return Refused
        }
        val occupied = pages.values.mapTo(HashSet()) { it.slot }
        val slot = (0 until physicalSlots).first { it !in occupied }
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

    fun touch(page: MediaAtlasPage) {
        idle()
        val r = required(page)
        pages.remove(page)
        pages[page] = r
    }

    private fun required(page: MediaAtlasPage) = checkNotNull(pages[page]) { "atlas page is not resident" }
    private fun idle() { check(pending == null) { "atlas replacement is already pending" } }
}
