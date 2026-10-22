package cc.joycreator.joybrush.core.doc

import cc.joycreator.joybrush.core.paint.Tiles

/** A physical paint plane. Shared pixels have no board/frame; linked frames may share a cel. */
data class RegionPlane(val celId: String, val boardId: String? = null, val frameId: String? = null, val blank: Boolean = false)

/** The current frame of one region in one layer. Held layers omit that board from their plan. */
data class RegionFrame(val boardId: String, val rect: RectPx, val frameId: String, val celId: String, val blank: Boolean = false)

/** Half-open, top-left-origin tile-local pixels; also suitable for a renderer's clip uniform. */
data class RegionTileRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    init {
        require(x >= 0 && y >= 0 && w > 0 && h > 0)
        require(x.toLong() + w <= Tiles.SIZE && y.toLong() + h <= Tiles.SIZE)
    }
    val right: Int get() = x + w
    val bottom: Int get() = y + h
    fun contains(x: Int, y: Int): Boolean = x >= this.x && y >= this.y && x < right && y < bottom
}

data class RegionTileSlice(val plane: RegionPlane, val rect: RegionTileRect)

/**
 * JB-3.00a B9/B10: one pixel-ownership rule for preview, export and paint commit.
 * Capture this immutable per-layer plan at stroke start. Every dab uses that same plan, and all
 * affected planes belong to ONE caller-owned undo transaction; routing never creates history.
 *
 * This is runtime geometry, not a new document schema. The future region-model adapter supplies
 * the shared cel and each board's saved current frame/cel. Legacy whole-layer cels must not be
 * passed here without migration. No pixels, GL handles or persisted model are owned by this plan.
 */
class RegionPaintPlan(sharedCelId: String, frames: List<RegionFrame>, roots: List<RegionFrame> = frames) {
    val shared = RegionPlane(sharedCelId)
    /** Physical, disjoint fragments. A logical board/frame can have several component cels. */
    val frames: List<RegionFrame> = frames.toList()
    /** Original logical roots, one per board, for frame commands rather than physical writes. */
    val roots: List<RegionFrame> = roots.toList()
    val physicalCelIds: Set<String> = this.frames.mapTo(linkedSetOf(sharedCelId)) { it.celId }
    fun coversCel(celId: String): Boolean = celId in physicalCelIds

    init {
        require(sharedCelId.isNotBlank()) { "Shared cel ID is empty" }
        // Logical roots stay unambiguous even when a component drawing resolves to several
        // physical pieces. The two-argument constructor therefore retains its original strictness.
        val rootByBoard = HashMap<String, RegionFrame>()
        val rootCels = HashSet<String>()
        for ((i, root) in this.roots.withIndex()) {
            require(root.boardId.isNotBlank() && root.frameId.isNotBlank() && root.celId.isNotBlank())
            require(root.rect.w > 0 && root.rect.h > 0) { "Animation region has no area" }
            require(rootByBoard.put(root.boardId, root) == null) { "A board has two current frames" }
            require(root.celId != sharedCelId && rootCels.add(root.celId)) { "Different paint regions share a logical cel" }
            for (j in 0 until i) require(!overlap(root.rect, this.roots[j].rect)) { "Animation regions overlap" }
        }
        val boards = HashMap<String, String>()
        val cels = HashMap<String, String>()
        val covered = HashMap<String, Long>()
        for ((i, frame) in this.frames.withIndex()) {
            require(frame.boardId.isNotBlank() && frame.frameId.isNotBlank() && frame.celId.isNotBlank())
            require(frame.rect.w > 0 && frame.rect.h > 0) { "Animation region has no area" }
            val root = requireNotNull(rootByBoard[frame.boardId]) { "A physical drawing has no logical frame" }
            require(frame.frameId == root.frameId && frame.blank == root.blank) { "A physical drawing disagrees with its logical frame" }
            require(frame.rect.x.toLong() >= root.rect.x && frame.rect.y.toLong() >= root.rect.y &&
                frame.rect.x.toLong() + frame.rect.w <= root.rect.x.toLong() + root.rect.w &&
                frame.rect.y.toLong() + frame.rect.h <= root.rect.y.toLong() + root.rect.h) { "A physical drawing exceeds its logical frame" }
            covered[frame.boardId] = covered.getOrElse(frame.boardId) { 0L } + frame.rect.w.toLong() * frame.rect.h
            val previousFrame = boards.put(frame.boardId, frame.frameId)
            require(previousFrame == null || previousFrame == frame.frameId) { "A board has two current frames" }
            require(frame.celId != sharedCelId) { "An animation region borrows the shared cel" }
            val previousBoard = cels.put(frame.celId, frame.boardId)
            require(previousBoard == null || previousBoard == frame.boardId) { "Different paint regions share a physical cel" }
            for (j in 0 until i) {
                val previous = this.frames[j]
                require(!overlap(frame.rect, previous.rect)) { "Animation regions overlap" }
            }
        }
        for (root in this.roots) require(covered[root.boardId] == root.rect.w.toLong() * root.rect.h) {
            "Physical drawings do not cover their logical frame"
        }
    }

    /** Pixel coordinates, not a dab centre: a brush's footprint may cross several planes. */
    fun planeAt(x: Long, y: Long): RegionPlane {
        for (frame in frames) if (contains(frame.rect, x, y)) return frame.plane()
        return shared
    }

    /**
     * Partition one touched tile into disjoint ownership rectangles. All 65,536 pixels appear
     * exactly once, including shared pixels between boards in the SAME tile. Never round a board
     * to tile boundaries. Tile origins and board ends use Long to avoid signed Int wraparound.
     */
    fun tileSlices(key: Long): List<RegionTileSlice> {
        val originX = Tiles.tx(key).toLong() * Tiles.SIZE
        val originY = Tiles.ty(key).toLong() * Tiles.SIZE
        var outside = listOf(RegionTileRect(0, 0, Tiles.SIZE, Tiles.SIZE))
        val inside = ArrayList<RegionTileSlice>()
        for (frame in frames) {
            val left = maxOf(0L, frame.rect.x.toLong() - originX)
            val top = maxOf(0L, frame.rect.y.toLong() - originY)
            val right = minOf(Tiles.SIZE.toLong(), frame.rect.x.toLong() + frame.rect.w - originX)
            val bottom = minOf(Tiles.SIZE.toLong(), frame.rect.y.toLong() + frame.rect.h - originY)
            if (right <= left || bottom <= top) continue
            val clip = RegionTileRect(left.toInt(), top.toInt(), (right-left).toInt(), (bottom-top).toInt())
            inside.add(RegionTileSlice(frame.plane(), clip))
            outside = outside.flatMap { subtract(it, clip) }
        }
        return outside.map { RegionTileSlice(shared, it) } + inside
    }

    companion object {
        /** Copy just an owned slice, preserving the other destination pixels and premultiplied RGBA. */
        fun copyRgbaSlice(source: ByteArray, destination: ByteArray, rect: RegionTileRect) {
            val bytes = Tiles.SIZE * Tiles.SIZE * 4
            require(source.size == bytes && destination.size == bytes) { "Expected one RGBA tile" }
            for (y in rect.y until rect.bottom) {
                val start = (y * Tiles.SIZE + rect.x) * 4
                source.copyInto(destination, start, start, start + rect.w * 4)
            }
        }

        private fun contains(rect: RectPx, x: Long, y: Long): Boolean =
            x >= rect.x.toLong() && y >= rect.y.toLong() &&
                x < rect.x.toLong() + rect.w && y < rect.y.toLong() + rect.h

        private fun overlap(a: RectPx, b: RectPx): Boolean =
            a.x.toLong() < b.x.toLong() + b.w && b.x.toLong() < a.x.toLong() + a.w &&
                a.y.toLong() < b.y.toLong() + b.h && b.y.toLong() < a.y.toLong() + a.h

        private fun RegionFrame.plane() = RegionPlane(celId, boardId, frameId, blank)

        private fun subtract(source: RegionTileRect, cut: RegionTileRect): List<RegionTileRect> {
            val l = maxOf(source.x, cut.x); val t = maxOf(source.y, cut.y)
            val r = minOf(source.right, cut.right); val b = minOf(source.bottom, cut.bottom)
            if (r <= l || b <= t) return listOf(source)
            return buildList {
                if (t > source.y) add(RegionTileRect(source.x, source.y, source.w, t-source.y))
                if (b < source.bottom) add(RegionTileRect(source.x, b, source.w, source.bottom-b))
                if (l > source.x) add(RegionTileRect(source.x, t, l-source.x, b-t))
                if (r < source.right) add(RegionTileRect(r, t, source.right-r, b-t))
            }
        }
    }
}
