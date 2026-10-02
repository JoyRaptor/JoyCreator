package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.RectPx

/** Owner's G7a preview scope. This is a read request, never a crop/mutation of layer pixels. */
data class BoardLayerPreview(val boardId: String?, val bounds: RectPx, val frameId: String?) {
    data class Key(val scope: BoardLayerPreview, val layerId: String, val contentRevision: Long)
    fun key(layerId: String, contentRevision: Long) = Key(this, layerId, contentRevision)

    companion object {
        fun scope(page: RectPx, board: Board?, selected: Boolean, currentFrameId: String? = null): BoardLayerPreview {
            if (!selected || board == null) return BoardLayerPreview(null, page, null)
            val frame = if (board.kind == BoardKind.ANIMATION) {
                require(currentFrameId != null && board.frames.any { it.id == currentFrameId }) {
                    "Animation preview needs the current frame ID from the live scene"
                }
                currentFrameId
            } else null
            return BoardLayerPreview(board.id, board.rect, frame)
        }
    }
}

/** Immutable IDs from one displayed scene. Translate strip indices using THIS snapshot only. */
class BoardChromeIdentity(boardId: String, frameIds: List<String>) {
    val boardId = boardId
    val frameIds = frameIds.toList()
    init {
        require(boardId.isNotBlank())
        require(this.frameIds.all { it.isNotBlank() } && this.frameIds.distinct().size == this.frameIds.size)
    }
    fun frame(number: Int): String? = frameIds.getOrNull(number - 1)
    fun liftedFrame(index: Int): String? = frameIds.getOrNull(index)
    /** Null means append; an invalid gap is refused rather than silently targeting another frame. */
    fun insertBefore(gap: Int): String? {
        require(gap in 0..frameIds.size)
        return frameIds.getOrNull(gap)
    }
    fun stillCurrent(board: Board): Boolean = board.id == boardId && board.frames.map { it.id } == frameIds
}

/** Latest chrome state per UI frame; never use this queue for painting's pen samples. */
class BoardChromeFrameQueue<T : Any> {
    private var pending: T? = null
    private var shown: T? = null
    private var scheduled = false
    fun offer(value: T): Boolean {
        if (value == pending || pending == null && value == shown) return false
        pending = value
        if (scheduled) return false
        scheduled = true
        return true
    }
    fun take(): T? {
        scheduled = false
        val value = pending
        pending = null
        if (value != null) shown = value
        return value
    }
    fun clear() { pending = null; shown = null; scheduled = false }
}

/** Capture only a core hit target. Gaps return null, and CANCEL never becomes a click. */
class BoardChromePointerCapture {
    var active: String? = null
        private set
    fun down(layout: BoardChromeLayout.Layout, point: BoardChromeLayout.Point): String? {
        active = BoardChromeLayout.hit(layout, point)?.id
        return active
    }
    fun release(): String? = active.also { active = null }
    fun cancel() { active = null }
    fun retain(layout: BoardChromeLayout.Layout): Boolean {
        if (active != null && layout.controls.none { it.id == active }) { cancel(); return false }
        return true
    }
}
