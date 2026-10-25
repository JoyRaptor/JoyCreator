package cc.joycreator.joybrush.androidkit.gl.media

import cc.joycreator.joybrush.core.media.MediaAtlasPage
import cc.joycreator.joybrush.core.media.MediaAtlasSlots

/**
 * A1 boundary for a future GL backend. All callbacks run on the owning GL thread.
 * load overwrites ALL stores/targets in a free staging slot, including absent state and ground/look semantics.
 * writeBack reads a resident slot and atomically publishes its durable COW state/ground/look; if it throws,
 * old durable contents and resident pixels must be preserved. Callbacks must not reenter residency mutations.
 * Staging failures need no cleanup callback: free bytes are invisible and the next load overwrites them.
 */
interface MediaAtlasBacking {
    fun load(slot: Int, page: MediaAtlasPage)
    fun writeBack(slot: Int, page: MediaAtlasPage)
    /** Every victim, dirty or not, before [writeBack] (ATLAS_ROOM: a tile with running water parks whether or not it is dirty). */
    fun evicted(slot: Int, page: MediaAtlasPage) {}
}

/** Fakeable load/write-back ordering. This does not replace MediaWindow or allocate real GL textures yet. */
class MediaAtlasResidency(val slots: MediaAtlasSlots, private val backing: MediaAtlasBacking) {
    /** null means every eviction candidate is pinned; no backend work occurs on refusal or a cache hit. */
    fun ensure(page: MediaAtlasPage): Int? = when (val admission = slots.begin(page)) {
        is MediaAtlasSlots.Present -> admission.slot
        MediaAtlasSlots.Refused -> null
        is MediaAtlasSlots.Reservation -> {
            try {
                backing.load(admission.slot, page)
                admission.victim?.let { backing.evicted(it.slot, it.page) }
                admission.victim?.takeIf { it.dirty }?.let { backing.writeBack(it.slot, it.page) }
            } catch (failure: Throwable) {
                slots.cancel(admission)
                throw failure
            }
            slots.commit(admission)
        }
    }
}
