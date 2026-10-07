package cc.joycreator.joybrush.android.board

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import cc.joycreator.joybrush.core.anim.PlaybackClock
import cc.joycreator.joybrush.core.anim.PlayMode
import cc.joycreator.joybrush.core.anim.FilmStrip
import cc.joycreator.joybrush.core.chrome.BoardChromeIdentity
import cc.joycreator.joybrush.core.chrome.BoardChromePenFade
import cc.joycreator.joybrush.core.chrome.BoardChromeLayout as Chrome
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.view.ViewTransform
import cc.joycreator.joybrush.core.sprite.SpriteBoard
import cc.joycreator.joybrush.core.sprite.SpriteGridMath
import cc.joycreator.joybrush.core.sprite.CellRoll
import java.util.UUID
import kotlin.math.*

/** UI-only board selection and transient previews. Content transactions belong to the canvas. */
class BoardRuntimeController(private val context: Context, private val parent: FrameLayout, private val host: Host) {
    interface Host {
        fun ensureDocument(ready: (JbDocument) -> Unit)
        fun edit(change: (JbDocument) -> RegionChange)
        fun selectFrame(boardId: String, frameId: String)
        fun preview(frames: Map<String, String>)
        fun transform(): ViewTransform
        fun contentRevision(): Long = 0L
        fun refusal(message: String)
        fun export(boardId: String) {}
        fun selectionChanged(bounds: RectPx?)
        fun thumbnails(boardId: String, frames: List<String>, width: Int, height: Int, ready: (Map<String, IntArray>) -> Unit)
        fun spriteThumbnails(boardId: String, cells: List<Int>, width: Int, height: Int, ready: (Map<Int, IntArray>) -> Unit) { ready(emptyMap()) }
    }
    private var doc: JbDocument? = null
    private var session = BoardSession()
    private val views = linkedMapOf<String, BoardChromeView>()
    private val scroll = mutableMapOf<String, Int>()
    private val modes = mutableMapOf<String, PlayMode>()
    private val fpsPanels = mutableSetOf<String>()
    private val spritePixels = mutableMapOf<String, Boolean>()
    private val spriteSubGrids = mutableMapOf<String, Int>()
    private val spriteRolls = mutableMapOf<String, CellRoll>()
    private var spritePlaying: String? = null
    private var spriteClock: PlaybackClock? = null
    private var spriteStarted = 0L
    private var spritePlayingCell: Int? = null
    private data class GeometryDrag(val documentId: String, val board: Board, val handle: Int, val zoom: Float)
    private var geometryDrag: GeometryDrag? = null
    private var geometryPreview: Board? = null
    private data class SpriteDrag(val documentId: String, val board: Board, val cell: Int, val from: Chrome.Point)
    private var spriteDrag: SpriteDrag? = null
    private var spriteTarget: Int? = null
    private var spriteLiftOffset = Chrome.Point(0f,0f)
    private val armedTick = Runnable { refreshTransform() }
    private val reducedMotion: Boolean get() = android.os.Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()
    private var epoch = 0L
    private var artEpoch = 0L
    private var spriteArtEpoch = 0L
    private var contentRevision = Long.MIN_VALUE
    private var pendingSelection: String? = null
    private val art = object : LruCache<Pair<String, String>, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Pair<String, String>, value: Bitmap) = value.allocationByteCount
    }
    private val requested = mutableSetOf<Pair<String, String>>()
    private var capture: View? = null
    private var placementEpoch = 0L
    private var playingBoard: String? = null
    private var clock: PlaybackClock? = null
    private var started = 0L
    private var previewFrame: String? = null
    private var pen: Pair<Float, Float>? = null
    private var penDown = false
    private var penChanged = SystemClock.uptimeMillis() - 1000
    private var holdPreview: Triple<String, String, Int>? = null
    private var lifted: Int? = null
    private var gap: Int? = null
    private var liftScene: BoardChromeIdentity? = null
    private var active = true
    private var refreshingTransform = false
    private var refreshAgainNeeded = false
    private val refreshAgain = Runnable { refreshTransform() }
    private val penFades = mutableMapOf<String, BoardChromePenFade>()
    private val nearBoards = mutableSetOf<String>()
    private val approachExit = mutableMapOf<String, Long>()
    val selectedBounds: RectPx? get() = doc?.boards?.firstOrNull { it.id == session.selectedBoardId }?.rect
    val selectedBoardId: String? get() = session.selectedBoardId

    init {
        parent.clipChildren = false
        parent.clipToPadding = false
        parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refreshTransform() }
    }

    fun documentChanged(value: JbDocument) {
        stopPreview()
        if (geometryDrag != null || spriteDrag != null) views.values.forEach { it.stopInteractions() }
        geometryDrag = null; geometryPreview = null; spriteDrag = null; spriteTarget = null
        val previous = doc
        val newDocument = previous != null && previous.id != value.id
        val revision = host.contentRevision()
        // Cursor navigation changes the displayed scene, but not the stable frame artwork.
        val ownershipChanged = previous == null || previous.id != value.id ||
            previous.boards.map { it.copy(currentFrameId = null) } != value.boards.map { it.copy(currentFrameId = null) } ||
            previous.layers.map { it.id to it.regions } != value.layers.map { it.id to it.regions }
        if (newDocument) {
            cancelInteractions()
            session = BoardSession(); pendingSelection = null
            scroll.clear(); modes.clear(); fpsPanels.clear(); lifted = null; gap = null
            spritePixels.clear(); spriteSubGrids.clear()
            spriteRolls.clear()
            penFades.clear(); nearBoards.clear(); approachExit.clear()
            views.values.forEach { parent.removeView(it) }; views.clear()
        }
        doc = value
        value.boards.forEach { b ->
            if (previous?.boards?.firstOrNull { it.id == b.id }?.grid != b.grid) spriteRolls.remove(b.id)
            if (session.armedBoardId == b.id && !b.locked && b.kind == BoardKind.SPRITE) session = session.copy(armedBoardId = null)
        }
        epoch++
        if (revision != contentRevision || ownershipChanged) {
            artEpoch++; spriteArtEpoch++; art.evictAll(); requested.clear()
        } else if(previous?.boards?.map { it.id to it.currentFrameId } != value.boards.map { it.id to it.currentFrameId }) {
            // A Sprite crop can cross a neighboring Animation board's saved current frame.
            spriteArtEpoch++
            art.snapshot().keys.filter { it.second.startsWith("sprite-") }.forEach { art.remove(it) }
            requested.removeAll { it.second.startsWith("sprite-") }
        }
        contentRevision = revision
        session = session.reconcile(value)
        pendingSelection?.takeIf { id -> value.boards.any { it.id == id } }?.let {
            session = session.select(value, it); pendingSelection = null
        }
        views.keys.filter { id -> value.boards.none { it.id == id } }.toList().forEach {
            spritePixels.remove(it); spriteSubGrids.remove(it)
            spriteRolls.remove(it)
            views.remove(it)?.let { view -> view.stopInteractions(); parent.removeView(view) }
        }
        value.boards.forEach { board ->
            views.getOrPut(board.id) { BoardChromeView(context).also { view ->
                view.host = adapter(board.id)
                parent.addView(view, FrameLayout.LayoutParams(1, 1))
            } }
        }
        host.selectionChanged(selectedBounds)
        refreshTransform()
    }

    fun select(id: String?) {
        val current = doc ?: return
        stopPreview()
        session = session.select(current, id)
        host.selectionChanged(selectedBounds)
        refreshTransform()
    }

    fun stopPreview(cancelHover: Boolean = true) {
        parent.removeCallbacks(tick)
        parent.removeCallbacks(spriteTick)
        spritePlaying?.let { id -> spriteClock?.let { c -> spriteRolls[id]?.let { roll ->
            if(!roll.isEmpty) spriteRolls[id]=roll.focused(c.frameIndexAt((SystemClock.uptimeMillis()-spriteStarted).toDouble()))
        } } }
        spritePlaying = null; spriteClock = null; spritePlayingCell = null
        val hadPreview = playingBoard != null
        playingBoard = null; clock = null; previewFrame = null
        if (hadPreview) host.preview(emptyMap())
        if (cancelHover) views.values.forEach { it.cancelHoverPreview() }
    }

    fun stop() {
        active = false
        artEpoch++; spriteArtEpoch++; requested.clear()
        cancelInteractions()
    }

    fun resume() {
        active = true
        refreshTransform()
    }

    private fun cancelInteractions() {
        stopPreview()
        views.values.forEach { it.stopInteractions() }
        holdPreview = null; lifted = null; gap = null; liftScene = null
        geometryDrag = null; geometryPreview = null; spriteDrag = null; spriteTarget = null
        parent.removeCallbacks(fade)
        parent.removeCallbacks(refreshAgain)
        parent.removeCallbacks(armedTick)
        refreshAgainNeeded = false
        placementEpoch++
        val placement = capture
        capture = null
        // Removing a current touch target can dispatch CANCEL synchronously. Finish dispatch first.
        placement?.let { parent.post { if (it.parent === parent) parent.removeView(it) } }
        penDown = false; pen = null
        penFades.values.forEach { it.reset() }
        nearBoards.clear(); approachExit.clear()
    }

    fun penEvent(event: MotionEvent) {
        if (!active) return
        if (event.pointerCount == 0) return
        val stylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        if (!stylus) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { stopPreview(); penDown = true; penChanged = SystemClock.uptimeMillis() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { penDown = false; penChanged = SystemClock.uptimeMillis() }
            MotionEvent.ACTION_HOVER_EXIT -> pen = null
        }
        if (event.actionMasked != MotionEvent.ACTION_HOVER_EXIT) pen = host.transform().screenToDoc(event.x, event.y)
        refreshTransform()
        parent.removeCallbacks(fade)
        parent.postOnAnimation(fade)
    }
    private val fade = object : Runnable {
        override fun run() {
            if (!active) return
            refreshTransform()
            val now = SystemClock.uptimeMillis()
            if (now - penChanged < 320 || approachExit.values.any { now - it < 300 }) parent.postOnAnimation(this)
        }
    }

    fun refreshTransform() {
        if (!active) return
        // Scene cancellation may synchronously call stripGap/holdCancelled and refresh again.
        // Finish this timestamp's complete pass before applying a newer one to any pen fade.
        if (refreshingTransform) { refreshAgainNeeded = true; return }
        parent.removeCallbacks(refreshAgain)
        refreshAgainNeeded = false
        refreshingTransform = true
        try { refreshTransformNow() }
        finally {
            refreshingTransform = false
            if (active && refreshAgainNeeded) parent.postOnAnimation(refreshAgain)
        }
    }

    private fun refreshTransformNow() {
        val current = doc ?: return
        if (parent.width == 0 || parent.height == 0) return
        val t = host.transform()
        // Oversize the transformed native hit surface: inverse-rotated viewport corners must
        // remain inside the view even at 45 degrees. Artwork itself still uses bounded patches.
        val margin = ceil(hypot(parent.width.toDouble(), parent.height.toDouble())).toInt()
        val sizeW = parent.width + margin * 2; val sizeH = parent.height + margin * 2
        val density = context.resources.displayMetrics.density
        val now = SystemClock.uptimeMillis()
        current.boards.forEach { saved ->
            val b = geometryPreview?.takeIf { it.id == saved.id && geometryDrag?.board == saved } ?: saved
            val v = views[b.id] ?: return@forEach
            val params = v.layoutParams as FrameLayout.LayoutParams
            if (params.width != sizeW || params.height != sizeH || params.leftMargin != -margin || params.topMargin != -margin)
                v.layoutParams = FrameLayout.LayoutParams(sizeW, sizeH).apply { leftMargin = -margin; topMargin = -margin }
            v.pivotX = t.panX + margin; v.pivotY = t.panY + margin
            v.rotation = Math.toDegrees(t.rotation.toDouble()).toFloat()
            val left = t.panX + margin + b.rect.x * t.zoom
            val top = t.panY + margin + b.rect.y * t.zoom
            val rect = Chrome.Rect(left, top, left + b.rect.w * t.zoom, top + b.rect.h * t.zoom)
            val cursor = if (playingBoard == b.id) previewFrame else b.currentFrameId
            val index = b.frames.indexOfFirst { it.id == cursor }.coerceAtLeast(0)
            val holds = b.frames.map { f -> holdPreview?.takeIf { it.first == b.id && it.second == f.id }?.third ?: f.holdFrames }
            val p = pen?.let { Chrome.Point(t.panX + margin + it.first * t.zoom, t.panY + margin + it.second * t.zoom) }
            val near = p?.let { rect.distance(it) <= Chrome.APPROACH_DP * density } == true
            if (near) { nearBoards.add(b.id); approachExit.remove(b.id) }
            else if (nearBoards.remove(b.id)) approachExit[b.id] = now
            v.sceneIdentity = BoardChromeIdentity(b.id, b.frames.map { it.id })
            val input = Chrome.Input(board = rect, density = density, kind = b.kind, name = b.name,
                selected = session.selectedBoardId == b.id, locked = b.locked, tiled = b.tiled, armed = session.armedBoardId == b.id,
                currentFrame = index + 1, holds = holds.ifEmpty { listOf(1) }, pixelWidth = b.rect.w, pixelHeight = b.rect.h,
                pen = p, penDown = penDown, penDownElapsedMs = SystemClock.uptimeMillis() - penChanged,
                penLiftElapsedMs = SystemClock.uptimeMillis() - penChanged,
                approachWasNear = b.id in approachExit, approachExitElapsedMs = now - (approachExit[b.id] ?: (now - 300)),
                screen = Chrome.Rect(margin.toFloat(), margin.toFloat(), (margin + parent.width).toFloat(), (margin + parent.height).toFloat()),
                stripScrollPx = (scroll[b.id] ?: 0).toFloat(), liftedFrame = lifted?.takeIf { session.selectedBoardId == b.id },
                insertionIndex = gap?.takeIf { session.selectedBoardId == b.id }, playing = playingBoard == b.id || spritePlaying == b.id,
                loopGlyph = when (modes[b.id] ?: PlayMode.LOOP) { PlayMode.LOOP -> "loop"; PlayMode.PING_PONG -> "ping-pong"; PlayMode.ONCE -> "once" },
                showFps = b.id in fpsPanels, fps = b.fps.roundToInt(), columns = b.grid?.cols ?: 1,
                rows = b.grid?.rows ?: 1, cellWidth = b.grid?.cellW ?: b.rect.w, cellHeight = b.grid?.cellH ?: b.rect.h,
                gridByPixels = spritePixels[b.id] == true, subGrid = spriteSubGrids[b.id] ?: 2,
                spriteOrder = if(b.locked) spriteRolls[b.id]?.entries?.map { it.cell }.orEmpty() else emptyList(),
                playingCell = if(spritePlaying == b.id) spritePlayingCell else spriteRolls[b.id]?.let { it.entries.getOrNull(it.cursor)?.cell },
                liftedCell = spriteDrag?.takeIf { it.board.id == b.id }?.cell, targetCell = spriteTarget?.takeIf { spriteDrag?.board?.id == b.id },
                liftedCellOffset = spriteLiftOffset, reducedMotion = reducedMotion, wiggleElapsedMs = now)
            v.show(penFades.getOrPut(b.id) { BoardChromePenFade() }.apply(input, now))
            if (b.kind == BoardKind.ANIMATION && session.selectedBoardId == b.id) requestArt(b, rect.width, density)
            if (b.kind == BoardKind.SPRITE && session.selectedBoardId == b.id && geometryPreview == null) requestSpriteArt(b)
        }
        parent.removeCallbacks(armedTick)
        if(!reducedMotion && session.selectedBoardId == session.armedBoardId && current.boards.any { it.id == session.armedBoardId && it.kind == BoardKind.SPRITE && it.locked })
            parent.postDelayed(armedTick,80)
    }

    private fun requestArt(b: Board, width: Float, density: Float) {
        if (penDown) return
        var x = -(scroll[b.id] ?: 0).toFloat()
        val visible = b.frames.filter { f -> val start = x; x += f.holdFrames * 44 * density; start < width && x > 0 }
            .filter { art[b.id to it.id] == null && (b.id to it.id) !in requested }.take(16)
        if (visible.isEmpty()) return
        val stamp = artEpoch
        val sceneId = doc?.id
        visible.forEach { requested += b.id to it.id }
        host.thumbnails(b.id, visible.map { it.id }, 80, 80) { pixels ->
            if (stamp != artEpoch || sceneId != doc?.id || doc?.boards?.none { it.id == b.id } != false) return@thumbnails
            pixels.forEach { (frame, data) -> if (visible.any { it.id == frame } && data.size == 6400)
                {
                    art.put(b.id to frame, Bitmap.createBitmap(data, 80, 80, Bitmap.Config.ARGB_8888))
                    requested.remove(b.id to frame)
                } }
            views[b.id]?.invalidate()
            // A wide visible strip may need multiple bounded batches. Failed entries remain
            // requested until the next content revision, preventing an empty-response loop.
            refreshTransform()
        }
    }

    private fun board(id: String) = doc?.boards?.firstOrNull { it.id == id }
    private fun requestSpriteArt(b: Board) {
        if (penDown) return
        val count = b.grid?.let { it.cols * it.rows } ?: return
        val wanted = if (session.armedBoardId == b.id) listOfNotNull(spriteDrag?.takeIf { it.board.id == b.id }?.cell,spriteTarget).distinct()
            else spriteRolls[b.id]?.takeUnless { it.isEmpty }?.let { roll ->
                val cursor=if(spritePlaying == b.id) spriteClock?.frameIndexAt((SystemClock.uptimeMillis()-spriteStarted).toDouble()) ?: roll.cursor else roll.cursor
                (0 until min(16,roll.size)).map { roll.entries[(cursor+it)%roll.size].cell }.distinct()
            }.orEmpty()
        val cells = wanted.filter { art[b.id to "sprite-$it"] == null && (b.id to "sprite-$it") !in requested }.take(16)
        if (cells.isEmpty()) return
        val stamp = spriteArtEpoch; val scene = doc?.id
        cells.forEach { requested += b.id to "sprite-$it" }
        host.spriteThumbnails(b.id,cells,80,80) { pixels ->
            if (!active || stamp != spriteArtEpoch || scene != doc?.id || board(b.id) != b) return@spriteThumbnails
            pixels.forEach { (cell,data) -> if (cell in cells && data.size == 6400) {
                art.put(b.id to "sprite-$cell",Bitmap.createBitmap(data,80,80,Bitmap.Config.ARGB_8888))
                requested.remove(b.id to "sprite-$cell")
            } }
            views[b.id]?.invalidate(); refreshTransform()
        }
    }
    private fun spriteAction(b: Board, control: String, held: Boolean) {
        if (!b.locked) return
        if (control == "preview-clear") { stopPreview(); spriteRolls.remove(b.id); refreshTransform(); return }
        if (control == "preview-play") {
            if (spritePlaying == b.id) { stopPreview(); refreshTransform(); return }
            val roll = spriteRolls[b.id]?.takeUnless { it.isEmpty } ?: return
            stopPreview(); spritePlaying = b.id
            spriteClock = PlaybackClock(roll.asBoard(b.fps,b.rect)); spriteStarted = SystemClock.uptimeMillis()
            spriteTick.run(); return
        }
        if (session.armedBoardId == b.id) return
        val cell = control.removePrefix("sprite-cell-").toIntOrNull() ?: return
        val count = b.grid?.let { it.cols * it.rows } ?: return
        stopPreview()
        val roll = spriteRolls[b.id] ?: CellRoll()
        spriteRolls[b.id] = if (held) roll.untappedAll(cell).roll else if(roll.size < 4096) roll.tapped(cell,count) else roll
        refreshTransform()
    }
    private val spriteTick = object : Runnable {
        override fun run() {
            val id = spritePlaying ?: return
            if (!active) return
            val roll = spriteRolls[id]?.takeUnless { it.isEmpty } ?: return
            val c = spriteClock ?: return
            val elapsed = (SystemClock.uptimeMillis()-spriteStarted).toDouble()
            spritePlayingCell = roll.entries[c.frameIndexAt(elapsed)].cell
            refreshTransform()
            parent.postDelayed(this,(c.nextChangeMs(elapsed)-elapsed).toLong().coerceIn(16,250))
        }
    }
    private fun clearGeometry() {
        geometryDrag = null; geometryPreview = null
        host.selectionChanged(selectedBounds); refreshTransform()
    }
    private fun boardSizeForm(b: Board) {
        val documentId = doc?.id ?: return
        textForm("Board size in pixels",listOf("Width" to "${b.rect.w}","Height" to "${b.rect.h}")) { values ->
            val w=values[0].toIntOrNull(); val h=values[1].toIntOrNull()
            if (w == null || h == null || w < 1 || h < 1) { host.refusal("Enter positive whole pixel dimensions"); return@textForm }
            edit { live ->
                if (live.id != documentId || live.boards.firstOrNull { it.id == b.id } != b) throw DocException("The board changed; choose its size again")
                RegionChange(BoardDocumentOps.resizeTyped(live,b.id,w,h))
            }
        }
    }
    private fun changeSpriteGrid(expected: Board, expectedDocumentId: String, grid: SpriteGrid) {
        if (!active || expected.kind != BoardKind.SPRITE) return
        edit { live ->
            if (live.id != expectedDocumentId || live.boards.firstOrNull { it.id == expected.id } != expected)
                throw DocException("The Sprite board changed; choose its grid again")
            RegionChange(BoardDocumentOps.setSpriteGrid(live, expected.id, grid))
        }
    }
    private fun spriteGridAction(b: Board, control: String) {
        if (!active || b.kind != BoardKind.SPRITE) return
        val documentId = doc?.id ?: return
        val pixels = spritePixels[b.id] == true
        when(control) {
            "grid-count", "grid-px" -> { spritePixels[b.id] = control == "grid-px"; refreshTransform() }
            "subgrid" -> { spriteSubGrids[b.id] = ((spriteSubGrids[b.id] ?: 2)-1)%7+2; refreshTransform() }
            "cols", "rows" -> {
                val grid = b.grid ?: return
                val horizontal = control == "cols"
                val current = if(pixels) { if(horizontal) grid.cellW else grid.cellH } else { if(horizontal) grid.cols else grid.rows }
                val title = if(pixels) { if(horizontal) "Cell width in pixels" else "Cell height in pixels" } else { if(horizontal) "Columns" else "Rows" }
                textForm(title,listOf(title to "$current")) { values ->
                    val value = values.single().toIntOrNull()
                    if(value == null || value < 1) { host.refusal("Enter a positive whole number"); return@textForm }
                    val model = SpriteBoard(b)
                    if(pixels && value > SpriteGridMath.MAX_CELL_PX) { host.refusal("Cell size must be 1–${SpriteGridMath.MAX_CELL_PX} pixels"); return@textForm }
                    val next = if(pixels) grid.copy(cellW=if(horizontal)value else grid.cellW,cellH=if(horizontal)grid.cellH else value)
                        else model.byCount(if(horizontal)value else grid.cols,if(horizontal)grid.rows else value)
                    changeSpriteGrid(b,documentId,next)
                }
            }
            else -> {
                val axis = if(control.startsWith("cols")) { if(pixels) SpriteBoard.Axis.CELL_W else SpriteBoard.Axis.COLS }
                    else { if(pixels) SpriteBoard.Axis.CELL_H else SpriteBoard.Axis.ROWS }
                val delta = if(control.endsWith("plus"))1 else -1
                // Each queued tap steps the live grid. Rapid taps must not collapse into one value.
                edit { live ->
                    if(live.id != documentId) throw DocException("The drawing changed; choose its grid again")
                    val target = live.boards.firstOrNull { it.id == b.id && it.kind == BoardKind.SPRITE }
                        ?: throw DocException("The Sprite board no longer exists")
                    val g = target.grid!!
                    val next = if(pixels) g.copy(cellW=if(axis == SpriteBoard.Axis.CELL_W)(g.cellW+delta).coerceIn(1,SpriteGridMath.MAX_CELL_PX) else g.cellW,
                        cellH=if(axis == SpriteBoard.Axis.CELL_H)(g.cellH+delta).coerceIn(1,SpriteGridMath.MAX_CELL_PX) else g.cellH)
                        else SpriteBoard(target).stepped(axis,delta)
                    RegionChange(BoardDocumentOps.setSpriteGrid(live,b.id,next))
                }
            }
        }
    }
    private fun edit(change: (JbDocument) -> RegionChange) { stopPreview(); host.edit(change) }
    private fun metadata(change: (JbDocument) -> JbDocument) = edit { RegionChange(change(it)) }
    private fun chooseFrame(id: String, index: Int) { val b = board(id) ?: return; b.frames.getOrNull(index)?.let { stopPreview(); host.selectFrame(id, it.id) } }
    private fun adapter(id: String) = object : BoardChromeView.Host {
        override fun acceptsPointer(control: String, event: MotionEvent): Boolean {
            if (!active) return false
            if (!control.startsWith("sprite-cell-")) return true
            val b = board(id) ?: return false
            // Unlocked fingers and every pen keep painting. Locked fingers build the roll/swap.
            return b.kind == BoardKind.SPRITE && b.locked && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        }
        override fun action(control: String, held: Boolean) {
            val b = board(id) ?: return
            when (control) {
                "kind" -> if (held) boardMenu(id) else select(if (session.selectedBoardId == id) null else id)
                "title" -> textForm("Board name", listOf("Name" to b.name)) { metadata { d -> BoardDocumentOps.rename(d, id, it[0]) } }
                "size" -> boardSizeForm(b)
                "cell-size" -> if(b.kind == BoardKind.SPRITE) {
                    val g=b.grid ?: return; val documentId=doc?.id ?: return
                    textForm("Cell size in pixels",listOf("Width" to "${g.cellW}","Height" to "${g.cellH}")) { values ->
                        val w=values[0].toIntOrNull(); val h=values[1].toIntOrNull()
                        if(w == null || h == null || w !in 1..SpriteGridMath.MAX_CELL_PX || h !in 1..SpriteGridMath.MAX_CELL_PX)
                            host.refusal("Cell size must be 1–${SpriteGridMath.MAX_CELL_PX} whole pixels")
                        else changeSpriteGrid(b,documentId,g.copy(cellW=w,cellH=h))
                    }
                }
                "lock" -> if (b.kind == BoardKind.ANIMATION && b.frames.size > 1) host.refusal("Move all frames needs a content transaction") else metadata { BoardDocumentOps.setLocked(it, id, !b.locked) }
                "add" -> if (held) frameMenu(id) else add(id, NewFrame.DUPLICATE)
                "play" -> if (held) { if (!fpsPanels.add(id)) fpsPanels.remove(id); refreshTransform() } else togglePlay(id)
                "loop" -> { modes[id] = PlayMode.values()[((modes[id] ?: PlayMode.LOOP).ordinal + 1) % 3]; stopPreview(); refreshTransform() }
                "fps-minus", "fps-plus" -> metadata { d -> d.copy(boards = d.boards.map { if (it.id == id) it.copy(fps = (it.fps + if (control == "fps-plus") 1 else -1).coerceIn(1f, 60f)) else it }) }
                "feature" -> if(b.kind == BoardKind.SPRITE) {
                    if(!b.locked) host.refusal("Lock the board to rearrange cells") else {
                        stopPreview(); session=session.arm(doc!!,if(session.armedBoardId == id)null else id); refreshTransform()
                    }
                } else host.refusal("Wrapped tile painting is not connected yet")
                "onion" -> host.refusal("Onion skin rendering is not connected yet")
                "export" -> { stopPreview(); host.export(id) }
                "grid-count", "grid-px", "cols-minus", "cols-plus", "rows-minus", "rows-plus", "cols", "rows", "subgrid" -> spriteGridAction(b,control)
                "preview-play", "preview-clear" -> spriteAction(b,control,held)
                else -> if(control.startsWith("sprite-cell-")) spriteAction(b,control,held)
            }
        }
        override fun frame(frame: Int) = chooseFrame(id, frame - 1)
        override fun stripScrub(boardId: String, frameId: String) { if (boardId == id && board(id)?.frames?.any { it.id == frameId } == true) { stopPreview(); host.selectFrame(id, frameId) } }
        override fun stripScrollBy(pixels: Int) { val b = board(id) ?: return; val total = b.frames.sumOf { it.holdFrames.toLong() } * 44 * context.resources.displayMetrics.density; val width = b.rect.w * host.transform().zoom
            scroll[id] = ((scroll[id] ?: 0) + pixels).coerceIn(0, (total - width).coerceAtLeast(0f).toInt()); refreshTransform() }
        override fun stripLift(index: Int, dx: Float, dy: Float, removing: Boolean, active: Boolean) {
            if (active && lifted == null) liftScene = board(id)?.let { BoardChromeIdentity(it.id, it.frames.map { f -> f.id }) }
            lifted = if (active) index + 1 else null; refreshTransform()
        }
        override fun stripGap(index: Int) { gap = index; refreshTransform() }
        override fun stripDrop(from: Int, insertionGap: Int, remove: Boolean) {
            val b = board(id) ?: return
            val scene = liftScene ?: return
            val frame = scene.liftedFrame(from) ?: return
            lifted = null; gap = null; liftScene = null
            if (!scene.stillCurrent(b) || insertionGap !in 0..scene.frameIds.size) { host.refusal("The frame order changed; drag again"); return }
            val order = reordered(scene.frameIds, frame, insertionGap)
            edit { d ->
                val live = d.boards.firstOrNull { it.id == id } ?: throw DocException("The board no longer exists")
                if (!scene.stillCurrent(live)) throw DocException("The frame order changed; drag again")
                if (remove) RegionDocumentOps.deleteFrame(d, id, frame)
                else RegionChange(RegionDocumentOps.reorderFrames(d, id, order))
            }
        }
        override fun stripHold(boardId: String, frameId: String, holdAtDown: Int, dragPx: Float, density: Float, finished: Boolean) {
            if (boardId != id || board(id)?.frames?.none { it.id == frameId } != false) return
            val current = doc ?: return
            val preview = FilmStrip(requireNotNull(board(id)), density).setHoldByDrag(current, id, frameId, holdAtDown, dragPx)
            val ticks = preview.boards.first { it.id == id }.frames.first { it.id == frameId }.holdFrames
            holdPreview = if (finished) null else Triple(id, frameId, ticks)
            if (finished) metadata { d -> FilmStrip(d.boards.first { it.id == id }, density).setHoldByDrag(d, id, frameId, holdAtDown, dragPx) } else refreshTransform()
        }
        override fun stripHoldCancelled(boardId: String, frameId: String) { holdPreview = null; refreshTransform() }
        override fun hoverLoop(playing: Boolean, restoreFrame: Int?) {
            if (playing) startPlay(id, PlayMode.LOOP, hover = true)
            else { stopPreview(cancelHover = false); refreshTransform() }
        }
        override fun drag(control: String, from: Chrome.Point, to: Chrome.Point, finished: Boolean) {
            val b = board(id) ?: return
            if(control.startsWith("sprite-cell-")) {
                if(!b.locked || session.armedBoardId != id) return
                val index=control.removePrefix("sprite-cell-").toIntOrNull() ?: return
                val gesture=spriteDrag ?: SpriteDrag(doc!!.id,b,index,from).also { spriteDrag=it; stopPreview() }
                if(gesture.board != b || gesture.documentId != doc?.id) { spriteDrag=null; spriteTarget=null; return }
                val t=host.transform()
                val margin=ceil(hypot(parent.width.toDouble(),parent.height.toDouble())).toInt()
                val target=SpriteGridMath.cellAt(b,(to.x-t.panX-margin)/t.zoom,(to.y-t.panY-margin)/t.zoom).takeIf { it >= 0 }
                spriteTarget=target
                spriteLiftOffset=Chrome.Point(to.x-from.x,to.y-from.y)
                if(finished) {
                    spriteDrag=null; spriteTarget=null; refreshTransform()
                    if(target != null && target != index) edit { live ->
                        if(live.id != gesture.documentId || live.boards.firstOrNull { it.id == id } != b) throw DocException("The Sprite grid changed; drag again")
                        SpriteCellOps.swap(live,id,index,target)
                    }
                } else refreshTransform()
                return
            }
            val handle = control.removePrefix("handle-").toIntOrNull() ?: return
            if(b.locked || b.kind == BoardKind.ANIMATION) return
            val gesture=geometryDrag ?: GeometryDrag(doc!!.id,b,handle,host.transform().zoom).also { geometryDrag=it; stopPreview() }
            if(gesture.board != b || gesture.documentId != doc?.id) { clearGeometry(); return }
            try {
                val rect=resized(gesture.board.rect,handle,((to.x-from.x)/gesture.zoom).roundToInt(),((to.y-from.y)/gesture.zoom).roundToInt())
                val preview=BoardDocumentOps.resize(doc!!,id,rect).boards.first { it.id == id }
                if(finished) {
                    clearGeometry()
                    if(preview != b) edit { live ->
                        if(live.id != gesture.documentId || live.boards.firstOrNull { it.id == id } != b) throw DocException("The board changed; resize again")
                        RegionChange(BoardDocumentOps.resize(live,id,rect))
                    }
                } else { geometryPreview=preview; host.selectionChanged(preview.rect); refreshTransform() }
            } catch(e:IllegalArgumentException) { clearGeometry(); host.refusal(e.message ?: "Choose a smaller board size") }
              catch(e:DocException) { clearGeometry(); host.refusal(e.message ?: "Choose a smaller board size") }
        }
        override fun cancel(control: String) {
            if(control.startsWith("handle-")) clearGeometry()
            if(control.startsWith("sprite-cell-")) { spriteDrag=null; spriteTarget=null; refreshTransform() }
        }
        override fun art(canvas: Canvas, element: Chrome.Element) {
            val b=board(id) ?: return
            if(b.kind == BoardKind.SPRITE) {
                val cell=when { element.id == "preview-art" -> if(spritePlaying == id)spritePlayingCell else spriteRolls[id]?.let { it.entries.getOrNull(it.cursor)?.cell }
                    element.id == "sprite-lift" -> spriteDrag?.cell
                    else -> element.id.removePrefix("sprite-cell-").toIntOrNull() } ?: return
                val bitmap=art[id to "sprite-$cell"] ?: return
                val r=element.rect; val g=b.grid ?: return
                val scale=min(r.width/g.cellW,r.height/g.cellH)
                val w=g.cellW*scale; val h=g.cellH*scale
                canvas.drawBitmap(bitmap,null,RectF(r.cx-w/2,r.cy-h/2,r.cx+w/2,r.cy+h/2),Paint(Paint.FILTER_BITMAP_FLAG)); return
            }
            val index = element.id.removePrefix("cell-").toIntOrNull() ?: return
            val frame = board(id)?.frames?.getOrNull(index)?.id ?: return
            val bitmap = art[id to frame] ?: return
            val r = element.rect
            canvas.drawBitmap(bitmap, null, RectF(r.left, r.top, r.right, r.bottom), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun add(id: String, mode: NewFrame) = edit { RegionDocumentOps.addFrame(it, id, mode) { UUID.randomUUID().toString() } }
    private fun frameMenu(id: String) {
        val b = board(id) ?: return
        AlertDialog.Builder(context).setTitle("Frame").setItems(arrayOf("Duplicate", "Link", "Blank", "Hold…", "Delete")) { _, n -> when(n) {
            0 -> add(id, NewFrame.DUPLICATE); 1 -> add(id, NewFrame.LINK); 2 -> add(id, NewFrame.BLANK)
            3 -> { val frame = b.frames.firstOrNull { it.id == b.currentFrameId } ?: return@setItems
                textForm("Frame hold", listOf("Ticks" to "${frame.holdFrames}")) { values -> values[0].toIntOrNull()?.let { ticks -> metadata { RegionDocumentOps.setHold(it, id, frame.id, ticks) } } ?: host.refusal("Enter a whole tick count") } }
            4 -> b.currentFrameId?.let { frame -> edit { RegionDocumentOps.deleteFrame(it, id, frame) } }
        } }.show()
    }
    private fun boardMenu(id: String) {
        AlertDialog.Builder(context).setTitle(board(id)?.name).setItems(arrayOf("Select", "Duplicate board", "Remove board", "Frames…", "Move…")) { _, n -> when(n) {
            0 -> select(id); 1 -> edit { BoardDocumentOps.duplicatePassive(it, id) { UUID.randomUUID().toString() } }
            2 -> metadata { BoardDocumentOps.remove(it, id) }; 3 -> if (board(id)?.kind == BoardKind.ANIMATION) frameMenu(id) else host.refusal("This board has no animation frames")
            4 -> { val b = board(id) ?: return@setItems; textForm("Board position",listOf("X" to "${b.rect.x}","Y" to "${b.rect.y}")) { values ->
                val x = values[0].toIntOrNull(); val y = values[1].toIntOrNull()
                if (x == null || y == null) host.refusal("Enter whole pixel coordinates") else metadata { BoardDocumentOps.move(it,id,x,y) }
            } }
        } }.show()
    }
    fun showMenu() {
        host.ensureDocument { d ->
            if (doc == null || doc?.id != d.id) documentChanged(d)
            val labels = arrayOf("New Image board…", "New Animation board…", "New Sprite board…", "Page (clear selection)") + d.boards.map { it.name }
            AlertDialog.Builder(context).setTitle("Boards").setItems(labels) { _, n -> when {
                n < 3 -> beginPlacement(listOf(BoardKind.CANVAS, BoardKind.ANIMATION, BoardKind.SPRITE)[n])
                n == 3 -> select(null); else -> select(d.boards[n - 4].id)
            } }.show()
        }
    }
    private fun beginPlacement(kind: BoardKind) {
        cancelInteractions()
        val placement = placementEpoch
        var start: Pair<Float, Float>? = null
        var end: Pair<Float, Float>? = null
        val overlay = object : View(context) {
            private var finished = false
            private fun finish(rect: RectPx? = null) {
                if (finished) return
                finished = true
                if (capture === this) capture = null
                val target = this
                this@BoardRuntimeController.parent.post {
                    if (target.parent === this@BoardRuntimeController.parent) this@BoardRuntimeController.parent.removeView(target)
                    if (rect != null && active && placement == placementEpoch) creationForm(kind, rect)
                }
            }
            override fun onDraw(canvas: Canvas) { val a = start ?: return; val b = end ?: a
                val t = host.transform(); val p = t.screenToDoc(a.first,a.second); val q = t.screenToDoc(b.first,b.second)
                val path = Path(); listOf(p.first to p.second,q.first to p.second,q.first to q.second,p.first to q.second).forEachIndexed { n, point ->
                    val s = t.docToScreen(point.first,point.second); if (n == 0) path.moveTo(s.first,s.second) else path.lineTo(s.first,s.second)
                }; path.close()
                canvas.drawPath(path,Paint().apply { color = 0xff42c8ed.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * resources.displayMetrics.density }) }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (finished) return true
                if (!active || placement != placementEpoch) { finish(); return true }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { start = event.x to event.y; end = start }
                    MotionEvent.ACTION_MOVE -> { end = event.x to event.y; invalidate() }
                    MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> finish()
                    MotionEvent.ACTION_UP -> {
                        val a = start
                        if (a == null) { finish(); return true }
                        val t = host.transform(); val p = t.screenToDoc(a.first,a.second); val q = t.screenToDoc(event.x,event.y)
                        val rect = RectPx(floor(min(p.first,q.first)).toInt(),floor(min(p.second,q.second)).toInt(),abs(q.first-p.first).roundToInt().coerceAtLeast(1),abs(q.second-p.second).roundToInt().coerceAtLeast(1))
                        finish(rect)
                    }
                }; return true
            }
        }
        capture = overlay; parent.addView(overlay, FrameLayout.LayoutParams(-1,-1))
        host.refusal("Drag a rectangle for the new board")
    }
    private fun creationForm(kind: BoardKind, rect: RectPx) {
        val fields = mutableListOf("Name" to when(kind) { BoardKind.CANVAS -> "Image"; BoardKind.ANIMATION -> "Animation"; BoardKind.SPRITE -> "Sprite"; else -> "Board" })
        if (kind == BoardKind.SPRITE) { fields += "Columns" to "1"; fields += "Rows" to "1" }
        textForm("New board", fields) { values ->
            val cols = if (kind == BoardKind.SPRITE) values[1].toIntOrNull() else 1
            val rows = if (kind == BoardKind.SPRITE) values[2].toIntOrNull() else 1
            if (cols == null || rows == null || cols < 1 || rows < 1 || rect.w % cols != 0 || rect.h % rows != 0) { host.refusal("Choose a whole grid of cells within this rectangle"); return@textForm }
            val id = UUID.randomUUID().toString(); pendingSelection = id
            edit { d -> var first = true; val ids = { if (first) { first = false; id } else UUID.randomUUID().toString() }
                when(kind) {
                    BoardKind.CANVAS -> BoardDocumentOps.createImage(d,values[0],rect,ids)
                    BoardKind.ANIMATION -> BoardDocumentOps.createAnimation(d,values[0],rect,ids)
                    BoardKind.SPRITE -> BoardDocumentOps.createSprite(d,values[0],rect,SpriteGrid(cols,rows,rect.w/cols,rect.h/rows),ids)
                    else -> throw DocException("This board kind is not available for placement")
                }
            }
        }
    }
    private fun textForm(title: String, fields: List<Pair<String,String>>, done: (List<String>) -> Unit) {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val inputs = fields.map { (label,value) ->
            box.addView(android.widget.TextView(context).apply { text=label; setPadding(16,8,16,0) })
            EditText(context).apply {
                hint = label; if(label != "Name") inputType=android.text.InputType.TYPE_CLASS_NUMBER
                setText(value); setSelectAllOnFocus(true); setSingleLine(); box.addView(this)
            }
        }
        AlertDialog.Builder(context).setTitle(title).setView(box).setNegativeButton("Cancel",null).setPositiveButton("Apply") { _, _ -> done(inputs.map { it.text.toString() }) }.show()
    }
    private fun togglePlay(id: String) { if (playingBoard == id) { stopPreview(); refreshTransform() } else startPlay(id) }
    private fun startPlay(id: String, mode: PlayMode = modes[id] ?: PlayMode.LOOP, hover: Boolean = false) {
        if (!active) return
        val b = board(id) ?: return; if (b.frames.isEmpty()) return
        stopPreview(cancelHover = !hover); playingBoard = id; clock = PlaybackClock(b, mode); started = SystemClock.uptimeMillis(); tick.run()
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            val id = playingBoard ?: return; val b = board(id) ?: return; val c = clock ?: return
            val elapsed = (SystemClock.uptimeMillis() - started).toDouble()
            previewFrame = b.frames.getOrNull(c.frameIndexAt(elapsed))?.id
            previewFrame?.let { host.preview(mapOf(id to it)) }; refreshTransform()
            if (c.isFinished(elapsed)) { stopPreview(); refreshTransform() } else parent.postDelayed(this, (c.nextChangeMs(elapsed) - elapsed).toLong().coerceIn(16,250))
        }
    }
    companion object {
        internal fun resized(r: RectPx, handle: Int, dx: Int, dy: Int): RectPx {
            require(handle in 0..7)
            var left = r.x.toLong(); var top = r.y.toLong(); var right = left + r.w; var bottom = top + r.h
            if (handle in listOf(0,6,7)) left = (left+dx).coerceAtMost(right-1)
            if (handle in listOf(2,3,4)) right = (right+dx).coerceAtLeast(left+1)
            if (handle in listOf(0,1,2)) top = (top+dy).coerceAtMost(bottom-1)
            if (handle in listOf(4,5,6)) bottom = (bottom+dy).coerceAtLeast(top+1)
            require(listOf(left,top,right,bottom,right-left,bottom-top).all { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }) { "Choose a smaller board size" }
            return RectPx(left.toInt(),top.toInt(),(right-left).toInt(),(bottom-top).toInt())
        }
        /** Native drag gap addresses the original order, before removing the dragged stable ID. */
        internal fun reordered(ids: List<String>, frame: String, gap: Int): List<String> {
            val from = ids.indexOf(frame); require(from >= 0)
            require(gap in 0..ids.size)
            return ids.toMutableList().apply { removeAt(from); add((gap - if (gap > from) 1 else 0).coerceIn(0,size),frame) }
        }
    }
}
