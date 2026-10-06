package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.graphics.ColorUtils
import cc.joycreator.joybrush.core.layers.BlendNames
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.layers.LayerState
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.android.board.BoardGlyphs
import kotlin.math.roundToInt

/**
 * The layer column (JB-2.04, to the owner-approved JB-2.01 mockup): a slim column of thumbnails on the right edge, the
 * top layer at the top — Infinite Painter's compact stack in Joy Creator's clothes.
 *
 * Each cell is the layer alone, the page's own shape, over a faint checkerboard. The layer the brush paints on wears the
 * cyan ring. A hidden layer is dimmed with a crossed eye; opacity under 100% and a blend mode other than Normal are
 * printed small in the cell's lower corners. MASKS and CLIPPING (JB-2.23): a layer's mask sits as a small second thumbnail
 * over the cell's lower right corner — tap it to paint on the mask, and the ring moves to it — and a clipped layer steps in
 * with an arrow.
 *
 * Tap a cell to paint on it; tap the ringed cell for its options ([Host.openLayer]). Press and hold, then drag, to move a
 * layer; ＋ adds one above the ringed layer. The column only reports; the canvas owns the stack.
 */
@SuppressLint("ViewConstructor")
class LayerColumnView(private val kit: ChromeKit, private val host: Host) : LinearLayout(kit.context) {

    interface Host {
        fun addLayer()
        fun selectLayer(id: String)
        fun openLayer(id: String, anchor: View)
        fun moveLayer(id: String, toIndex: Int)
        /** The mask thumbnail was tapped: paint on the mask (or back on the layer). JB-2.23 Decision 9. */
        fun maskTapped(id: String)
        /** Paper is a document setting, never a layer or paint target. */
        fun openPaper(anchor: View) {}
        /** K9: the selected animation board owns this layer's held/animated state. */
        fun setLayerHeld(boardId: String, layerId: String, held: Boolean) {}
    }

    private val plus = PlusCell()
    private val paper = PaperCell()
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Reserve the final swatch before a long stack consumes the column's height limit.
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val available = (MeasureSpec.getSize(heightMeasureSpec) - kit.dpi(PAPER_DP)).coerceAtLeast(0)
            val capped = if (mode == MeasureSpec.UNSPECIFIED) heightMeasureSpec
                else MeasureSpec.makeMeasureSpec(available, mode)
            super.onMeasure(widthMeasureSpec, capped)
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(rows)
    }
    private var stack: LayerStack? = null
    private var max = 0
    private val thumbs = HashMap<String, Bitmap>()
    private val maskThumbs = HashMap<String, Bitmap>()
    private var editingMask = false
    private var animationBoardId: String? = null
    private var heldByLayer: Map<String, Boolean> = emptyMap()
    private val cells = linkedMapOf<String, Cell>()
    private val markers = linkedMapOf<String, AnimationMarker>()

    /** Supply saved metadata and the UI-selected board, or null to return to ordinary rows.
     * Only PAINT/INK layers mapped to this animation board can toggle; masks remain mask controls.
     */
    fun setAnimationBoard(document: JbDocument?, boardId: String?) {
        val board = document?.boards?.firstOrNull { it.id == boardId && it.kind == BoardKind.ANIMATION }
        val next = if (board == null) emptyMap() else document?.layers.orEmpty().mapNotNull { layer ->
            if (layer.kind != LayerKind.PAINT && layer.kind != LayerKind.INK) return@mapNotNull null
            layer.regions.firstOrNull { it.boardId == board.id }?.let { layer.id to it.held }
        }.toMap()
        if (animationBoardId == board?.id && heldByLayer == next) return
        val widthChanged = (animationBoardId == null) != (board == null)
        animationBoardId = board?.id
        heldByLayer = next
        if (widthChanged) {
            layoutParams?.let { it.width = widthPx; layoutParams = it }
            rebuild()
            requestLayout()
        } else markers.values.forEach { it.refresh() }
    }

    /** The page's shape, width over height: every cell is drawn in it. */
    var pageAspect = 0.5f
        set(value) { field = value.coerceIn(0.2f, 5f); requestLayout(); rebuild() }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val r = kit.dp(14f)
        kit.chromeSurface(this, floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r))
        setPadding(kit.dpi(4f), kit.dpi(2f), kit.dpi(4f), kit.dpi(6f))
        addView(plus, LayoutParams(LayoutParams.MATCH_PARENT, kit.dpi(PLUS_DP)))
        // WRAP, not a weight: the column is as tall as its layers, and the screen's own limit makes the list scroll.
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(paper, LayoutParams(LayoutParams.MATCH_PARENT, kit.dpi(PAPER_DP)))
        kit.label(plus, "Add a layer above this one")
        plus.setOnClickListener { host.addLayer() }
        kit.label(paper, "Paper — tap to change")
        paper.setOnClickListener { host.openPaper(paper) }
    }

    /** The column's width, for the screen to keep other things clear of it. */
    val widthPx: Int get() = kit.dpi(WIDTH_DP + if (animationBoardId != null) MARKER_COLUMN_DP else 0f)

    val paperAnchor: View get() = paper

    /** The host renders this live crop on its preview worker; the cell never owns a bitmap. */
    fun setPaperPreview(bitmap: Bitmap?) { paper.bitmap = bitmap; paper.invalidate() }

    fun show(s: LayerStack, maxLayers: Int, maskEditing: Boolean = false) {
        editingMask = maskEditing
        // Cells are only rebuilt when the LIST changes (a layer added, removed or moved). A change of opacity, blend,
        // visibility or the ringed layer redraws the same cells, so a panel anchored to one never loses its anchor.
        val sameList = stack?.layers?.map { it.id } == s.layers.map { it.id }
        stack = s
        max = maxLayers
        plus.invalidate()
        if (sameList && rows.childCount == s.size) {
            cells.values.forEach { it.invalidate() }
            markers.values.forEach { it.refresh() }
        } else {
            rebuild()
        }
    }

    /** The cell showing layer [id] right now, or null. */
    fun cellFor(id: String): View? = cells[id]

    /** The K9 toggle, if this row has eligible content in the selected animation board. */
    fun animationMarkerFor(id: String): View? = markers[id]?.takeIf { it.visibility == VISIBLE }

    fun setThumbnails(map: Map<String, Bitmap>) {
        thumbs.putAll(map)
        cells.values.forEach { it.invalidate() }
    }

    /** Pictures of the masks, by LAYER id (white shows, black hides). */
    fun setMaskThumbnails(map: Map<String, Bitmap>) {
        maskThumbs.putAll(map)
        cells.values.forEach { it.invalidate() }
    }

    /** The cell size in px, the page's own shape inside a [CELL_W_DP]-wide cell (capped in height so a tall page stays compact). */
    fun thumbSize(): Pair<Int, Int> {
        val w = kit.dp(CELL_W_DP)
        val h = (w / pageAspect).coerceAtMost(kit.dp(CELL_MAX_H_DP))
        val fitW = (h * pageAspect).coerceAtMost(w)
        return Pair(fitW.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
    }

    private fun rebuild() {
        val s = stack ?: return
        rows.removeAllViews()
        cells.clear()
        markers.clear()
        val ids = s.layers.map { it.id }.toSet()
        thumbs.keys.retainAll(ids)
        maskThumbs.keys.retainAll(s.layers.filter { it.hasMask }.map { it.id }.toSet())
        for (layer in s.layers.asReversed()) {
            val cell = Cell(layer.id)
            cells[layer.id] = cell
            val h = thumbSize().second + kit.dpi(8f)
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            if (animationBoardId != null) {
                val marker = AnimationMarker(layer.id)
                markers[layer.id] = marker
                marker.refresh()
                row.addView(marker, LayoutParams(kit.dpi(MARKER_COLUMN_DP), LayoutParams.MATCH_PARENT))
            }
            row.addView(cell, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            rows.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, h))
        }
    }

    private inner class AnimationMarker(private val layerId: String) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var touchState: Pair<String, Boolean>? = null
        private fun state(): Pair<String, Boolean>? {
            val board = animationBoardId ?: return null
            val held = heldByLayer[layerId] ?: return null
            return if (markers[layerId] === this && stack?.get(layerId) != null) board to held else null
        }
        fun refresh() {
            val current = state()
            visibility = if (current == null) INVISIBLE else VISIBLE
            isClickable = current != null
            isFocusable = current != null
            if (current != null) kit.label(this, "${stack?.get(layerId)?.name ?: "Layer"} — " +
                if (current.second) "Same on every frame. Tap to animate in this board."
                else "Animates in this board. Tap for same on every frame.")
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            val held = state()?.second ?: return
            val size = kit.dp(MARKER_GLYPH_DP)
            val left = (width-size)/2f; val top = (height-size)/2f
            paint.shader = if (held) null else LinearGradient(0f,0f,24f,24f,
                0xff35f6bf.toInt(),0xff97fe8b.toInt(),Shader.TileMode.CLAMP)
            paint.color = if (held) 0xffa1a1aa.toInt() else android.graphics.Color.WHITE
            canvas.save()
            canvas.translate(left,top); canvas.scale(size/24f,size/24f)
            BoardGlyphs.path(if (held) "mountain" else "runner")?.let { canvas.drawPath(it,paint) }
            canvas.restore()
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touchState = state()
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) touchState = null
            return super.onTouchEvent(event)
        }
        override fun performClick(): Boolean {
            val current = state()
            val expected = touchState
            touchState = null
            if (current == null || expected != null && expected != current) return false
            super.performClick()
            host.setLayerHeld(current.first,layerId,!current.second)
            return true
        }
    }

    // ── ＋ and the count ──

    private inner class PaperCell : View(context) {
        var bitmap: Bitmap? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val box = RectF()
        private val clip = Path()

        init { isClickable = true; isFocusable = true }

        override fun onDraw(c: Canvas) {
            // A circle, matching PaperSheetView's swatch (radius = min(w,h)/2 - 4dp). It was a
            // 44x28dp rounded rectangle, which read as a squashed slab under the layer stack.
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) / 2f - kit.dp(4f)
            box.set(cx - radius, cy - radius, cx + radius, cy + radius)
            clip.reset()
            clip.addCircle(cx, cy, radius, Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            paint.color = kit.p.drawerDim
            c.drawRect(box, paint)
            bitmap?.takeUnless { it.isRecycled }?.let { c.drawBitmap(it, null, box, paint) }
            c.restore()
        }
    }

    private inner class PlusCell : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            textSize = kit.dp(8.5f)
        }

        init { isClickable = true; isFocusable = true }

        override fun onDraw(c: Canvas) {
            val s = stack
            val full = s != null && s.size >= max
            val cx = width / 2f
            val cy = kit.dp(15f)
            paint.color = if (full) kit.ink(0.35f) else kit.p.drawerInk
            paint.strokeWidth = kit.dp(2.2f)
            paint.strokeCap = Paint.Cap.ROUND
            val arm = kit.dp(7f)
            c.drawLine(cx - arm, cy, cx + arm, cy, paint)
            c.drawLine(cx, cy - arm, cx, cy + arm, paint)
            if (s != null) {
                // The budget, always on screen: the limit is visible before it is reached (JB-2.04 Decision 6).
                text.color = if (full) kit.p.stateCareful else kit.p.drawerDim
                c.drawText("${s.size}/$max", cx, height - kit.dp(4f), text)
            }
        }
    }

    // ── one layer ──

    private inner class Cell(val id: String) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = kit.dp(8f)
        }
        private val box = RectF()
        private val maskBox = RectF()
        private var hasMaskBox = false
        private val clip = Path()
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downY = 0f
        private var dragging = false
        private var pressedAt = 0L
        private val lift = Runnable { startDrag() }

        init {
            isClickable = true
            isFocusable = true
            val layer = stack?.get(id)
            kit.label(this, (layer?.name ?: "Layer") + " — tap to paint on it, tap again for its options, hold to move it")
        }

        override fun onDraw(c: Canvas) {
            val s = stack ?: return
            val layer = s[id] ?: return
            val (tw, th) = thumbSize()
            val indent = if (layer.clip) kit.dp(CLIP_INDENT_DP) else 0f
            val left = (width - tw) / 2f + indent / 2f
            val top = (height - th) / 2f
            box.set(left, top, left + tw, top + th)
            drawCell(c, layer, box, thumbs[id])
            if (layer.clip) {
                // The clip arrow: this layer only shows where the layer below it has paint.
                text.color = kit.p.drawerDim
                text.textSize = kit.dp(10f)
                c.drawText("↳", box.left - kit.dp(10f), box.top + kit.dp(12f), text)
                text.textSize = kit.dp(8f)
            }
            hasMaskBox = layer.hasMask
            if (layer.hasMask) {
                // The mask, as a small second thumbnail over the cell's lower right corner: white shows, black hides.
                val mw = tw * 0.46f
                val mh = th * 0.46f
                maskBox.set(box.right - mw * 0.55f, box.bottom - mh + kit.dp(3f), box.right + mw * 0.45f, box.bottom + kit.dp(3f))
                paint.style = Paint.Style.FILL
                paint.color = kit.p.drawerInk
                c.drawRect(maskBox, paint)
                maskThumbs[id]?.let { m -> paint.alpha = 255; c.drawBitmap(m, null, maskBox, paint) }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = kit.dp(1f)
                paint.color = kit.p.line
                c.drawRect(maskBox, paint)
                paint.style = Paint.Style.FILL
            }
            if (layer.id == s.activeId) {
                // The ring is on whatever the brush paints: the layer, or its mask.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = kit.dp(2f)
                paint.color = kit.p.stateSelected
                if (editingMask && layer.hasMask) {
                    c.drawRect(maskBox.left - kit.dp(2f), maskBox.top - kit.dp(2f), maskBox.right + kit.dp(2f), maskBox.bottom + kit.dp(2f), paint)
                } else {
                    c.drawRoundRect(box.left - kit.dp(2f), box.top - kit.dp(2f), box.right + kit.dp(2f), box.bottom + kit.dp(2f),
                        kit.dp(7f), kit.dp(7f), paint)
                }
                paint.style = Paint.Style.FILL
            }
            if (dragging) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = kit.dp(1.5f)
                paint.color = kit.p.drawerInk
                c.drawRoundRect(box, kit.dp(6f), kit.dp(6f), paint)
                paint.style = Paint.Style.FILL
            }
        }

        private fun drawCell(c: Canvas, layer: LayerState, r: RectF, bmp: Bitmap?) {
            val radius = kit.dp(6f)
            clip.reset()
            clip.addRoundRect(r, radius, radius, Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            // A faint checkerboard, so an empty layer and a white one are told apart.
            val cell = kit.dp(5f)
            var y = r.top
            var j = 0
            while (y < r.bottom) {
                var x = r.left
                var i = 0
                while (x < r.right) {
                    paint.color = if ((i + j) % 2 == 0) kit.p.drawerInk else kit.p.drawerDim
                    c.drawRect(x, y, x + cell, y + cell, paint)
                    x += cell; i++
                }
                y += cell; j++
            }
            if (bmp != null) {
                paint.alpha = if (layer.visible) 255 else 90
                c.drawBitmap(bmp, null, r, paint)
                paint.alpha = 255
            }
            c.restore()

            if (!layer.visible) drawEyeOff(c, r.right - kit.dp(9f), r.top + kit.dp(8f))
            if (layer.opacity < 0.995f) corner(c, "${(layer.opacity * 100).roundToInt()}%", r.left + kit.dp(3f), r.bottom - kit.dp(3f), left = true)
            val short = BlendNames.short(layer.blend)
            if (short.isNotEmpty()) corner(c, short, r.right - kit.dp(3f), r.bottom - kit.dp(3f), left = false)
        }

        /** A tiny label on a dark pill, so it reads on any paint. */
        private fun corner(c: Canvas, s: String, x: Float, baseline: Float, left: Boolean) {
            val w = text.measureText(s)
            val x0 = if (left) x else x - w - kit.dp(4f)
            paint.color = ColorUtils.setAlphaComponent(kit.p.ground, 170)
            c.drawRoundRect(x0, baseline - kit.dp(9f), x0 + w + kit.dp(4f), baseline + kit.dp(1.5f), kit.dp(3f), kit.dp(3f), paint)
            text.color = kit.p.drawerInk
            c.drawText(s, x0 + kit.dp(2f), baseline - kit.dp(1f), text)
        }

        /** A crossed eye: this layer is hidden. */
        private fun drawEyeOff(c: Canvas, cx: Float, cy: Float) {
            val r = kit.dp(6.5f)
            paint.color = ColorUtils.setAlphaComponent(kit.p.ground, 180)
            c.drawCircle(cx, cy, r + kit.dp(1.5f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = kit.dp(1.3f)
            paint.color = kit.p.drawerInk
            c.drawOval(cx - r * 0.8f, cy - r * 0.45f, cx + r * 0.8f, cy + r * 0.45f, paint)
            c.drawLine(cx - r * 0.7f, cy + r * 0.7f, cx + r * 0.7f, cy - r * 0.7f, paint)
            paint.style = Paint.Style.FILL
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    dragging = false
                    pressedAt = e.eventTime
                    postDelayed(lift, ViewConfiguration.getLongPressTimeout().toLong())
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        translationY = e.rawY - downY
                        return true
                    }
                    if (Math.abs(e.rawY - downY) > slop) {
                        // A scroll of the column, not a press on this cell.
                        removeCallbacks(lift)
                        return false
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(lift)
                    if (dragging) { drop(e.rawY - downY); return true }
                    performClick()
                    val s = stack ?: return true
                    // A tap on the mask thumbnail (with a little slack around it, it is small) is about the mask.
                    val slack = kit.dp(6f)
                    if (hasMaskBox && e.x >= maskBox.left - slack && e.x <= maskBox.right + slack &&
                        e.y >= maskBox.top - slack && e.y <= maskBox.bottom + slack) {
                        host.maskTapped(id)
                        return true
                    }
                    if (s.activeId == id) host.openLayer(id, this) else host.selectLayer(id)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(lift)
                    if (dragging) { translationY = 0f; elevation = 0f; (parent as? View)?.elevation = 0f; dragging = false; invalidate() }
                    return true
                }
            }
            return super.onTouchEvent(e)
        }

        private fun startDrag() {
            dragging = true
            parent?.requestDisallowInterceptTouchEvent(true)
            elevation = kit.dp(8f)
            (parent as? View)?.elevation = elevation
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }

        /** Let go after moving [dy] px: the cell lands where its centre is, counted in cells. */
        private fun drop(dy: Float) {
            val s = stack
            translationY = 0f
            elevation = 0f
            (parent as? View)?.elevation = 0f
            dragging = false
            invalidate()
            if (s == null || height == 0) return
            val steps = (dy / height).roundToInt()
            if (steps == 0) return
            // The column lists the TOP layer first, so moving DOWN the column is moving DOWN the stack.
            val from = s.indexOf(id)
            host.moveLayer(id, (from - steps).coerceIn(0, s.size - 1))
        }
    }

    companion object {
        const val WIDTH_DP = 60f
        const val MARKER_COLUMN_DP = 16f
        const val MARKER_GLYPH_DP = 13f
        const val CELL_W_DP = 44f
        const val CELL_MAX_H_DP = 64f
        const val PLUS_DP = 44f
        const val PAPER_DP = 36f
        const val CLIP_INDENT_DP = 10f
    }
}
