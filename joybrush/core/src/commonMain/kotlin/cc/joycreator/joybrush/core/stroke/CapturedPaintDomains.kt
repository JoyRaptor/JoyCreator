package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.RegionFrame
import cc.joycreator.joybrush.core.doc.RegionPaintPlan
import cc.joycreator.joybrush.core.doc.RegionPlane

/**
 * Immutable capture-space ownership copied from the one stable region paint plan admitted at
 * contact start. It records only planes present in that plan; later board/frame changes cannot
 * reroute it. The input plan must not be mutated concurrently with [prepare].
 */
class CapturedPaintDomains private constructor(
    val sharedCelId: String,
    planes: List<Plane>,
) {
    class Plane(val owner: RegionPlane, val engineCelId: String?, val source: CapturedPhysicalSource)

    /** Shared plane first, followed by each distinct physical current-frame plane. */
    val planes: List<Plane> = FrozenPlanes(planes)

    fun forCel(celId: String?): Plane? = planes.firstOrNull { it.engineCelId == celId }

    companion object {
        const val MAX_REGIONS = CAPTURE_DOMAIN_MAX_EXCLUSIONS

        /**
         * Reconstruct and revalidate the exposed plan before deriving domains. RegionPaintPlan's
         * frame list may have been mutated through a MutableList cast since its constructor ran.
         */
        fun prepare(plan: RegionPaintPlan): CapturedPaintDomains {
            require(plan.frames.size <= MAX_REGIONS) { "Captured paint plan exceeds the region budget" }
            val sharedId = plan.shared.celId
            require(plan.shared.boardId == null && plan.shared.frameId == null) {
                "The shared captured plane must not name a board or frame"
            }
            val copied = ArrayList<RegionFrame>(plan.frames.size)
            for (frame in plan.frames) {
                require(copied.size < MAX_REGIONS) { "Captured paint plan exceeds the region budget" }
                val rect = frame.rect
                // CaptureRect validates positive dimensions and checked exclusive endpoints.
                CaptureRect(rect.x, rect.y, rect.w, rect.h)
                copied += RegionFrame(frame.boardId, RectPx(rect.x, rect.y, rect.w, rect.h), frame.frameId, frame.celId)
            }
            require(plan.roots.size <= MAX_REGIONS) { "Captured paint plan exceeds the region budget" }
            val roots = plan.roots.map { root ->
                val rect = root.rect
                CaptureRect(rect.x, rect.y, rect.w, rect.h)
                root.copy(rect = RectPx(rect.x, rect.y, rect.w, rect.h), blank = false)
            }
            val checked = RegionPaintPlan(sharedId, copied, roots)
            require(checked.frames.size <= MAX_REGIONS) { "Captured paint plan exceeds the region budget" }
            val sharedDomain = CaptureDomain(excluded = checked.roots.map { it.rect.toCaptureRect() })
            val result = ArrayList<Plane>(checked.frames.size + 1)
            result += Plane(checked.shared, null, CapturedPhysicalSource(sharedDomain))
            for (pieces in checked.frames.groupBy { it.celId }.values) {
                val frame = pieces.first()
                // A component may own disconnected pieces of this logical frame. One canonical
                // physical address must capture all of them; forCel must never return only one.
                val root = checked.roots.first { it.boardId == frame.boardId }
                val excluded = checked.frames.filter { it.boardId == frame.boardId && it.celId != frame.celId }
                    .map { it.rect.toCaptureRect() }
                val domain = CaptureDomain(window = root.rect.toCaptureRect(), excluded = excluded)
                result += Plane(frame.plane(), frame.celId, CapturedPhysicalSource(domain))
            }
            return CapturedPaintDomains(sharedId, result)
        }

        private fun RectPx.toCaptureRect() = CaptureRect(x, y, w, h)
        private fun RegionFrame.plane() = RegionPlane(celId, boardId, frameId)

    }

    private class FrozenPlanes(values: List<Plane>) : AbstractList<Plane>() {
        private val items = values.toTypedArray()
        override val size: Int get() = items.size
        override fun get(index: Int): Plane = items[index]
    }
}
