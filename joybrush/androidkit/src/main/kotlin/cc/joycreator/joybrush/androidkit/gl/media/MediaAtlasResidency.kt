package cc.joycreator.joybrush.androidkit.gl.media

import cc.joycreator.joybrush.core.media.MediaAtlasPage
import cc.joycreator.joybrush.core.media.MediaAtlasSlots

/**
 * A1 boundary for a future GL backend. All callbacks run on the owning GL thread.
 * load overwrites ALL stores/targets in a free staging slot, including absent state and ground/look semantics.
 * writeBack reads a resident slot and atomically publishes its durable COW state/ground/look; on failure, old durable bytes
 * and resident pixels must remain unchanged. Callbacks must not reenter residency mutations. Admission hooks bracket staging
 * ownership; rollback must undo backend ownership and temporary table changes. commitAdmission runs after slot publication
 * and must not throw or allocate. MediaAtlas's GL-backed commit implementation has not yet been proven to meet that constraint;
 * keep it unactivated until its allocator/commit behavior and durable writeBack atomicity are established.
 */
interface MediaAtlasBacking {
    /** Begins backend bookkeeping before any staging load or victim mutation. */
    fun beginAdmission(page: MediaAtlasPage, victim: MediaAtlasSlots.Resident?) {}
    fun load(slot: Int, page: MediaAtlasPage)
    fun writeBack(slot: Int, page: MediaAtlasPage)
    /** Every victim, dirty or not, before [writeBack] (ATLAS_ROOM: a tile with running water parks whether or not it is dirty). */
    fun evicted(slot: Int, page: MediaAtlasPage) {}
    /** Validates or pre-stages fallible backend releases before the slot mapping is published. */
    fun prepareCommitAdmission(page: MediaAtlasPage, victim: MediaAtlasSlots.Resident?) {}
    /** Called after [MediaAtlasSlots.commit] published the new resident; must not throw or allocate. */
    fun commitAdmission(page: MediaAtlasPage, victim: MediaAtlasSlots.Resident?) {}
    /** Best-effort restoration of backend ownership after any pre-publication callback fails. */
    fun rollbackAdmission(page: MediaAtlasPage, victim: MediaAtlasSlots.Resident?) {}
}

/** Fakeable load/write-back ordering. This does not replace MediaWindow or allocate real GL textures yet. */
class MediaAtlasResidency(val slots: MediaAtlasSlots, private val backing: MediaAtlasBacking) {
    /** null means every eviction candidate is pinned; no backend work occurs on refusal or a cache hit. */
    fun ensure(page: MediaAtlasPage): Int? = when (val admission = slots.begin(page)) {
        is MediaAtlasSlots.Present -> admission.slot
        MediaAtlasSlots.Refused -> null
        is MediaAtlasSlots.Reservation -> {
            var begun = false
            var published = false
            try {
                backing.beginAdmission(page, admission.victim)
                begun = true
                backing.load(admission.slot, page)
                admission.victim?.let { backing.evicted(it.slot, it.page) }
                admission.victim?.takeIf { it.dirty }?.let { backing.writeBack(it.slot, it.page) }
                backing.prepareCommitAdmission(page, admission.victim)
                val slot = slots.commit(admission)
                published = true
                backing.commitAdmission(page, admission.victim)
                slot
            } catch (failure: Throwable) {
                fun retain(cleanup: Throwable) {
                    if (cleanup !== failure) try { failure.addSuppressed(cleanup) } catch (_: Throwable) {}
                }
                if (!published) {
                    try { slots.cancel(admission) } catch (cleanup: Throwable) { retain(cleanup) }
                    if (begun) try { backing.rollbackAdmission(page, admission.victim) } catch (cleanup: Throwable) { retain(cleanup) }
                }
                throw failure
            }
        }
    }
}
