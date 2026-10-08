package cc.joycreator.joybrush.android.board

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import cc.joycreator.joybrush.core.chrome.BoardChromeLayout as Chrome
import cc.joycreator.joybrush.core.chrome.BoardHoverLoop
import cc.joycreator.joybrush.core.chrome.BoardChromeFrameQueue
import cc.joycreator.joybrush.core.chrome.BoardChromePointerCapture
import cc.joycreator.joybrush.core.chrome.BoardChromeRenderPlan
import cc.joycreator.joybrush.core.chrome.BoardChromeIdentity
import cc.joycreator.joybrush.core.chrome.IconInk
import cc.joycreator.joybrush.core.anim.FilmStrip as CoreFilmStrip
import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.BoardKind
import com.fadcam.ui.type.Type
import com.fadcam.ui.faditor.sprite.RollDragController
import com.fadcam.ui.faditor.sprite.FilmStrip
import kotlin.math.roundToInt

/**
 * Android renderer for the locked §K layout. Every board/strip/control rectangle, baseline,
 * transform and fade boundary comes from core. The host supplies art and all document actions.
 * No frame pixels, document state, or export implementation is owned by this overlay.
 */
class BoardChromeView(context: Context) : ViewGroup(context) {
    interface Host {
        fun action(id: String, held: Boolean) {}
        /** The live adapter may reserve a stylus gesture for painting even over a cell target. */
        fun acceptsPointer(id: String, event: MotionEvent): Boolean = true
        fun cancel(id: String) {}
        fun drag(id: String, from: Chrome.Point, to: Chrome.Point, finished: Boolean) {}
        fun frame(frame: Int) {}
        fun stripScrollBy(pixels: Int) {}
        fun stripLift(index: Int, dx: Float, dy: Float, removing: Boolean, active: Boolean) {}
        fun stripGap(index: Int) {}
        fun stripHint(active: Boolean, removing: Boolean) {}
        fun stripDrop(from: Int, insertionGap: Int, remove: Boolean) {}
        /** IDs are frozen at pointer-down. Host rejects IDs removed from the live document. */
        fun stripScrub(boardId: String, frameId: String) {}
        /** Preview until finished; commit once on release through FilmStrip.setHoldByDrag. */
        fun stripHold(boardId: String, frameId: String, holdAtDown: Int, dragPx: Float,
                      density: Float, finished: Boolean) {}
        fun stripHoldCancelled(boardId: String, frameId: String) {}
        fun hoverLoop(playing: Boolean, restoreFrame: Int?) {}
        /** Draw the board's region art into the supplied, already clipped screen rectangle. */
        fun art(canvas: Canvas, element: Chrome.Element) {}
        /** Optional Frost snapshot, already blurred by the shared Frost provider. */
        fun frost(canvas: Canvas, element: Chrome.Element) {}
    }

    var host: Host = object : Host {}
        set(value) { if (field !== value) { stopInteractions(); field = value } }
    /** Supply before show(). Replacing a board/order cancels gestures before their indices can move. */
    var sceneIdentity: BoardChromeIdentity? = null
        set(value) {
            val changed = field?.boardId != value?.boardId || field?.frameIds != value?.frameIds
            // Cancellation calls the host synchronously; its refresh must already see this identity.
            field = value
            if (changed) {
                identityAwaitingLayout = value != null
                stopInteractions()
            }
        }
    private var identityAwaitingLayout = false
    var paperInk: IconInk = IconInk.LIGHT
        set(value) { if (field != value) { field = value; invalidate() } }
    var paperColour: Int = Color.TRANSPARENT
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Optional injection of the ONE app ic_export_studio asset. */
    var exportDrawable: Drawable? = null
        set(value) { field = value?.mutate(); invalidate() }

    private var layout = Chrome.Layout(emptyList(), emptyList(), false, false, false)
    private var input: Chrome.Input? = null
    private val inputQueue = BoardChromeFrameQueue<Chrome.Input>()
    private val renderPending = Runnable { inputQueue.take()?.let { applyInput(it) } }
    private val capture = BoardChromePointerCapture()
    private val targets = linkedMapOf<String, View>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Path()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private data class RasterKey(val element: Chrome.Element, val ink: IconInk, val viewport: Chrome.Rect)
    private data class RasterPatch(val bounds: BoardChromeRenderPlan.Patch, val bitmap: Bitmap)
    private val shadows = object : LruCache<RasterKey, List<RasterPatch>>(4 * 1024 * 1024) {
        override fun sizeOf(key: RasterKey, value: List<RasterPatch>) = value.sumOf { it.bitmap.allocationByteCount }
    }
    /** Measurable cache footprint; no window-sized software layer is allocated by this view. */
    val cachedShadowBytes: Int get() = shadows.size()
    var layoutBuildCount: Long = 0
        private set
    var shadowRasterizations: Long = 0
        private set
    private var activeTarget: View? = null
    private var hoveredTarget: View? = null
    private var hover = BoardHoverLoop()
    private var roll: RollDragController? = null
    private var rollDensity = 0f
    private fun cell(index: Int) = layout.elements.first { it.id == "cell-$index" }.rect
    private val resolvedExport: Drawable? by lazy {
        val id = context.resources.getIdentifier("ic_export_studio", "drawable", context.packageName)
        if (id == 0) null else context.getDrawable(id)?.mutate()
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        // Only bounded shadow patches use software Canvas. The transparent scene keeps the
        // normal hardware pipeline, including on API 28 where non-text shadows need software.
    }

    fun show(input: Chrome.Input) {
        if (inputQueue.offer(Chrome.visualInput(input))) postOnAnimation(renderPending)
    }

    private fun applyInput(input: Chrome.Input) {
        val previous = this.input
        this.input = input
        if (previous != null && (previous.density != input.density || previous.selected != input.selected || previous.kind != input.kind ||
                    previous.holds != input.holds && (activeTarget as? Target)?.isHolding != true)) {
            stopInteractions()
        }
        identityAwaitingLayout = input.kind == BoardKind.ANIMATION &&
            sceneIdentity?.frameIds?.size?.let { it != input.holds.size } == true
        layoutBuildCount++
        applyLayout(Chrome.layout(input))
        if (roll == null || rollDensity != input.density) {
            rollDensity = input.density
            roll = RollDragController(object : RollDragController.Bounds {
                override fun size() = this@BoardChromeView.input?.holds?.size ?: 0
                override fun centerX(index: Int) = cell(index).cx
                override fun centerY(index: Int) = cell(index).cy
                override fun height(index: Int) = cell(index).height
                override fun viewportLeft() = this@BoardChromeView.input!!.board.left
                override fun viewportRight() = this@BoardChromeView.input!!.board.right
                override fun scrollBy(pixels: Int) = host.stripScrollBy(pixels)
            }, object : RollDragController.Listener {
                override fun onLift(index: Int, dx: Float, dy: Float, removing: Boolean) =
                    host.stripLift(index, dx, dy, removing, roll?.isDragging(index) == true)
                override fun onGap(gap: Int) = host.stripGap(gap)
                override fun onHint(active: Boolean, removing: Boolean) = host.stripHint(active, removing)
                override fun onDrop(from: Int, gap: Int, remove: Boolean) = host.stripDrop(from, gap, remove)
            }, input.density)
        }
        if ((input.selected || input.kind != BoardKind.ANIMATION || input.otherTileArmed) && hover.playing) stopHover()
    }

    /** Also accepts core layerMarker output, without inventing another marker layout. */
    fun submit(value: Chrome.Layout) {
        removeCallbacks(renderPending)
        inputQueue.clear()
        input = null
        applyLayout(value)
    }

    private fun applyLayout(value: Chrome.Layout) {
        if (layout == value) return
        val controlsChanged = layout.controls != value.controls
        if (!capture.retain(value)) stopInteractions()
        layout = value
        val ids = value.controls.map { it.id }.toSet()
        targets.keys.filter { it !in ids && targets[it] !== activeTarget }.toList().forEach {
            removeView(targets.remove(it))
        }
        value.controls.forEach { control ->
            val target = targets.getOrPut(control.id) { Target(control.id).also { addView(it) } }
            target.isEnabled = control.enabled
            if (target.contentDescription != control.tooltip) target.contentDescription = control.tooltip
            // Compat tooltips take the long-click listener on API 24–25. Native hover tooltips
            // preserve the board menu, FPS and add-frame holds on API 26+ (including Note 9).
            if (Build.VERSION.SDK_INT >= 26 && target.tooltipText != control.tooltip) target.tooltipText = control.tooltip
            else (target as Target).hoverLabel = control.tooltip
            if (controlsChanged) target.layout(control.hit.left.roundToInt(), control.hit.top.roundToInt(),
                control.hit.right.roundToInt(), control.hit.bottom.roundToInt())
        }
        if (controlsChanged) requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        layout.controls.forEach { c -> targets[c.id]?.measure(
            MeasureSpec.makeMeasureSpec(c.hit.width.roundToInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(c.hit.height.roundToInt(), MeasureSpec.EXACTLY)) }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        layout.controls.forEach { c -> targets[c.id]?.layout(c.hit.left.roundToInt(), c.hit.top.roundToInt(),
            c.hit.right.roundToInt(), c.hit.bottom.roundToInt()) }
    }

    override fun onDraw(canvas: Canvas) {
        layout.elements.forEach { e ->
            if(e.colour == Chrome.Colour.GLASS && e.shadowPx > 0) drawFrost(canvas,e)
            if (e.shadowPx > 0 || e.haloPx > 0 && e.colour == Chrome.Colour.PAPER) drawShadowPatch(canvas, e)
            else draw(canvas, e)
        }
    }

    private fun drawShadowPatch(canvas: Canvas, e: Chrome.Element) {
        val viewport = Chrome.Rect(0f, 0f, width.toFloat(), height.toFloat())
        val rasterViewport = BoardChromeRenderPlan.rasterViewport(e, viewport)
        val key = RasterKey(e.copy(alpha = 1f, rotationDeg = 0f, scale = 1f, transformOrigin = null,
            clip = null, fadeStartPx = null, fadeEndPx = null), paperInk, rasterViewport)
        val patches = shadows[key] ?: BoardChromeRenderPlan.patches(key.element, rasterViewport).map { bounds ->
            val bitmap = Bitmap.createBitmap(bounds.width, bounds.height, Bitmap.Config.ARGB_8888)
            val software = Canvas(bitmap)
            software.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
            draw(software, key.element, artwork = false)
            RasterPatch(bounds, bitmap)
        }.also { shadowRasterizations++; shadows.put(key, it) }
        val save = canvas.save()
        e.clip?.let { canvas.clipRect(it.left, it.top, it.right, it.bottom) }
        val fadeLayer = if (e.fadeStartPx != null && e.fadeEndPx != null) {
            val r = e.clip ?: e.rect
            canvas.saveLayer(r.left, r.top, r.right, r.bottom, null)
        } else -1
        val transformSave = canvas.save()
        val pivot = e.transformOrigin ?: Chrome.Point(e.rect.cx, e.rect.cy)
        canvas.rotate(e.rotationDeg, pivot.x, pivot.y)
        canvas.scale(e.scale, e.scale, pivot.x, pivot.y)
        bitmapPaint.alpha = (e.alpha * 255).roundToInt().coerceIn(0, 255)
        for (patch in patches) canvas.drawBitmap(patch.bitmap, patch.bounds.left.toFloat(), patch.bounds.top.toFloat(), bitmapPaint)
        canvas.restoreToCount(transformSave)
        if (fadeLayer >= 0) fadeMask(canvas, e)
        canvas.restoreToCount(save)
        if (hasArt(e)) drawArt(canvas, e)
    }

    private fun hasArt(e: Chrome.Element) = e.artSlot || e.id == "sprite-lift" || e.id.startsWith("sprite-cell-")

    private fun drawFrost(canvas: Canvas,e: Chrome.Element) {
        val save=canvas.save()
        e.clip?.let { canvas.clipRect(it.left,it.top,it.right,it.bottom) }
        val pivot=e.transformOrigin ?: Chrome.Point(e.rect.cx,e.rect.cy)
        canvas.rotate(e.rotationDeg,pivot.x,pivot.y); canvas.scale(e.scale,e.scale,pivot.x,pivot.y)
        val layer=if(e.alpha < 1f) canvas.saveLayerAlpha(e.rect.left,e.rect.top,e.rect.right,e.rect.bottom,(e.alpha*255).roundToInt()) else -1
        shape(e); clipped(canvas,outline) { host.frost(canvas,e) }
        if(layer >= 0) canvas.restoreToCount(layer)
        canvas.restoreToCount(save)
    }

    private fun drawArt(canvas: Canvas, e: Chrome.Element) {
        val save = canvas.save()
        e.clip?.let { canvas.clipRect(it.left, it.top, it.right, it.bottom) }
        val pivot = e.transformOrigin ?: Chrome.Point(e.rect.cx, e.rect.cy)
        canvas.rotate(e.rotationDeg, pivot.x, pivot.y)
        canvas.scale(e.scale, e.scale, pivot.x, pivot.y)
        shape(e)
        clipped(canvas, outline) { host.art(canvas, e) }
        canvas.restoreToCount(save)
    }

    private fun draw(canvas: Canvas, e: Chrome.Element, artwork: Boolean = true) {
        val save = canvas.save()
        e.clip?.let { canvas.clipRect(it.left, it.top, it.right, it.bottom) }
        val fadeLayer = if (e.fadeStartPx != null && e.fadeEndPx != null) {
            val r = e.clip ?: e.rect
            canvas.saveLayer(r.left, r.top, r.right, r.bottom, null)
        } else -1
        val transformSave = canvas.save()
        val pivot = e.transformOrigin ?: Chrome.Point(e.rect.cx, e.rect.cy)
        canvas.rotate(e.rotationDeg, pivot.x, pivot.y)
        canvas.scale(e.scale, e.scale, pivot.x, pivot.y)
        paint.reset()
        paint.isAntiAlias = true
        paint.color = colour(e.colour)
        paint.alpha = (Color.alpha(paint.color) * e.alpha).roundToInt().coerceIn(0, 255)
        paint.strokeWidth = e.strokePx
        paint.style = if (e.strokePx > 0f) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.BUTT
        if (e.dashed) paint.pathEffect = DashPathEffect(floatArrayOf(e.strokePx * 3, e.strokePx * 3), 0f)
        e.gradient?.let {
            val stops = when (it) {
                BoardKind.ANIMATION -> intArrayOf(0xff35f6bf.toInt(), 0xff97fe8b.toInt())
                BoardKind.SPRITE -> intArrayOf(0xffff008c.toInt(), 0xffcc27ff.toInt())
                else -> intArrayOf(0xff5c43fd.toInt(), 0xff4397fd.toInt())
            }
            paint.shader = LinearGradient(e.rect.left, e.rect.top, e.rect.right, e.rect.top, stops, null, Shader.TileMode.CLAMP)
        }
        if (e.shadowPx > 0f) paint.setShadowLayer(e.shadowPx, 0f, e.shadowPx, 0x99000000.toInt())
        if (e.haloPx > 0f) paint.setShadowLayer(e.haloPx, 0f, 0f,
            if (e.colour == Chrome.Colour.PAPER) contrastColour() else colour(e.colour))
        when (e.shape) {
            Chrome.Shape.LINE -> canvas.drawLine(e.rect.left, e.rect.top, e.rect.right, e.rect.bottom, paint)
            Chrome.Shape.ROUND_RECT -> {
                shape(e)
                if (artwork && e.colour == Chrome.Colour.GLASS) clipped(canvas, outline) { host.frost(canvas, e) }
                if (e.haloPx > 0f && e.colour == Chrome.Colour.CYAN) {
                    val original = Paint(paint)
                    paint.clearShadowLayer()
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = e.strokePx + 2 * e.haloPx
                    paint.alpha = (e.alpha * Chrome.STATE_HALO_ALPHA * 255).roundToInt()
                    canvas.drawPath(outline, paint)
                    paint.set(original)
                    paint.clearShadowLayer()
                }
                if (e.id.startsWith("sprocket-")) {
                    FilmStrip.drawOutline(canvas, paint, emptyList(), listOf(RectF(e.rect.left, e.rect.top, e.rect.right, e.rect.bottom)), e.radiusPx)
                } else canvas.drawPath(outline, paint)
                if (e.colour == Chrome.Colour.CHROME || e.colour == Chrome.Colour.GLASS) {
                    paint.shader = null
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = input?.density ?: resources.displayMetrics.density
                    paint.color = 0xfff2f2f5.toInt()
                    paint.alpha = (Chrome.CHROME_RING_ALPHA * e.alpha * 255).roundToInt()
                    paint.clearShadowLayer()
                    canvas.drawPath(outline, paint)
                }
                if (artwork && hasArt(e)) clipped(canvas, outline) { host.art(canvas, e) }
            }
            Chrome.Shape.TEXT -> {
                paint.typeface = when (e.font) {
                    Chrome.Font.ARCHIVO -> Type.display(context, e.weight)
                    Chrome.Font.MONO -> Type.mono(context, e.weight)
                    Chrome.Font.SANS -> Type.body(context, e.weight)
                }
                paint.textSize = e.sizeSp * resources.displayMetrics.scaledDensity
                paint.isFakeBoldText = false
                paint.textAlign = when (e.align) {
                    Chrome.Align.LEFT -> Paint.Align.LEFT
                    Chrome.Align.CENTRE -> Paint.Align.CENTER
                    Chrome.Align.RIGHT -> Paint.Align.RIGHT
                }
                val x = when (e.align) { Chrome.Align.LEFT -> e.rect.left; Chrome.Align.RIGHT -> e.rect.right; else -> e.rect.cx }
                canvas.drawText(e.text, x, e.baselinePx, paint)
            }
            Chrome.Shape.GLYPH -> drawGlyph(canvas, e)
        }
        canvas.restoreToCount(transformSave)
        if (fadeLayer >= 0) {
            // Restore transforms before applying the core's screen-space strip mask.
            fadeMask(canvas, e)
        }
        canvas.restoreToCount(save)
    }

    private fun fadeMask(canvas: Canvas, e: Chrome.Element) {
        maskPaint.reset()
        maskPaint.shader = LinearGradient(e.fadeStartPx!!, 0f, e.fadeEndPx!!, 0f,
            Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        val r = e.clip ?: e.rect
        canvas.drawRect(r.left, r.top, r.right, r.bottom, maskPaint)
    }

    private fun shape(e: Chrome.Element) {
        outline.reset()
        val r = RectF(e.rect.left, e.rect.top, e.rect.right, e.rect.bottom)
        val corners = e.cornerRadiiPx
        if (corners != null) outline.addRoundRect(r, corners.flatMap { listOf(it, it) }.toFloatArray(), Path.Direction.CW)
        else outline.addRoundRect(r, e.radiusPx, e.radiusPx, Path.Direction.CW)
    }

    private inline fun clipped(canvas: Canvas, path: Path, draw: () -> Unit) {
        val save = canvas.save(); canvas.clipPath(path); draw(); canvas.restoreToCount(save)
    }

    private fun drawGlyph(canvas: Canvas, e: Chrome.Element) {
        if (e.glyph == "export") {
            val drawable = exportDrawable ?: resolvedExport ?: return
            drawable.setTint(colour(e.colour))
            drawable.alpha = (e.alpha * 255).roundToInt()
            drawable.setBounds(e.rect.left.roundToInt(), e.rect.top.roundToInt(), e.rect.right.roundToInt(), e.rect.bottom.roundToInt())
            drawable.draw(canvas)
            return
        }
        val save = canvas.save()
        canvas.translate(e.rect.left, e.rect.top)
        canvas.scale(e.rect.width / 24f, e.rect.height / 24f)
        if (e.colour == Chrome.Colour.PAPER && e.haloPx > 0f) {
            paint.setShadowLayer(e.haloPx * 24f / e.rect.width, 0f, 0f, contrastColour())
        }
        // The glyph path is in 24-unit space; move its shader into that same drawing space.
        paint.shader?.let { shader ->
            val local = Matrix()
            local.setRectToRect(RectF(e.rect.left, e.rect.top, e.rect.right, e.rect.bottom), RectF(0f, 0f, 24f, 24f), Matrix.ScaleToFit.FILL)
            shader.setLocalMatrix(local)
        }
        if (e.glyph == "onion") {
            canvas.drawCircle(9f, 12f, 6f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.2f
            canvas.drawCircle(15.5f, 12f, 5.2f, paint)
        } else BoardGlyphs.path(e.glyph)?.let { canvas.drawPath(it, paint) }
        canvas.restoreToCount(save)
    }

    private fun colour(c: Chrome.Colour): Int = when (c) {
        Chrome.Colour.PAPER -> if (paperInk == IconInk.DARK) 0xff0b0b0e.toInt() else 0xfff2f2f5.toInt()
        Chrome.Colour.PAPER_SURFACE -> paperColour
        Chrome.Colour.CHROME -> 0xff0c0c0f.toInt()
        Chrome.Colour.GLASS -> 0xff060608.toInt()
        Chrome.Colour.INK -> 0xfff2f2f5.toInt()
        Chrome.Colour.DIM -> 0xffc9c9d3.toInt()
        Chrome.Colour.CYAN -> 0xff22d3ee.toInt()
        Chrome.Colour.PINK -> 0xfff43f8e.toInt()
        Chrome.Colour.AMBER -> 0xfffbbf24.toInt()
        Chrome.Colour.GREY -> 0xffa1a1aa.toInt()
        Chrome.Colour.ON_GRADIENT -> 0xff050507.toInt()
        Chrome.Colour.SPRITE_ON -> 0xffff008c.toInt()
        Chrome.Colour.ANIMATION_ON -> 0xff35f6bf.toInt()
    }
    private fun contrastColour() = if (paperInk == IconInk.DARK) Color.WHITE else Color.BLACK

    /** Core chooses among overlapping >=40dp targets. No child z order steals a neighbour. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Identity and geometry must belong to the same displayed scene, not a queued next frame.
        if (identityAwaitingLayout) return false
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && activeTarget != null) {
            stopInteractions()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val id = capture.down(layout, Chrome.Point(event.x, event.y))
            activeTarget = id?.takeIf { host.acceptsPointer(it, event) }?.let { targets[it] }
            if (activeTarget == null) capture.cancel()
        }
        val target = activeTarget ?: return false
        val local = MotionEvent.obtain(event)
        local.offsetLocation(-target.left.toFloat(), -target.top.toFloat())
        val handled = target.dispatchTouchEvent(local)
        local.recycle()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            capture.release()
            activeTarget = null
        }
        return handled
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val target = if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) null
            else Chrome.hit(layout, Chrome.Point(event.x, event.y), enabledOnly = false)?.let { targets[it.id] }
        if (hoveredTarget !== target) {
            hoveredTarget?.let { deliverHover(it, event, MotionEvent.ACTION_HOVER_EXIT) }
            target?.let { deliverHover(it, event, MotionEvent.ACTION_HOVER_ENTER) }
            hoveredTarget = target
        } else target?.let { deliverHover(it, event, event.actionMasked) }
        val state = input
        val pointer = when (event.getToolType(0)) {
            MotionEvent.TOOL_TYPE_STYLUS -> BoardHoverLoop.Pointer.STYLUS
            MotionEvent.TOOL_TYPE_MOUSE -> BoardHoverLoop.Pointer.MOUSE
            else -> BoardHoverLoop.Pointer.FINGER
        }
        if (target === targets["kind"] && state != null) {
            val next = hover.enter(pointer, state.kind, state.selected, state.currentFrame)
            if (!hover.playing && next.playing) host.hoverLoop(true, null)
            hover = next
        } else stopHover()
        return target != null
    }

    private fun deliverHover(target: View, event: MotionEvent, action: Int) {
        val local = MotionEvent.obtain(event); local.action = action
        local.offsetLocation(-target.left.toFloat(), -target.top.toFloat())
        (target as Target).deliverHover(local); local.recycle()
    }
    private fun stopHover() {
        val (next, restore) = hover.exit(); hover = next
        if (restore != null) host.hoverLoop(false, restore)
    }
    /** Cancel a preview without disturbing an in-flight frame scrub or duration edit. */
    fun cancelHoverPreview() { stopHover() }
    /** The Activity calls this before pausing; a hover loop never survives leaving the screen. */
    private var stoppingInteractions = false
    fun stopInteractions() {
        if (stoppingInteractions) return
        stoppingInteractions = true
        try {
            removeCallbacks(renderPending)
            inputQueue.clear()
            capture.cancel()
            val previousTargets = targets.values.toList()
            activeTarget = null
            hoveredTarget = null
            previousTargets.forEach { (it as Target).stopInteraction() }
            stopHover()
            roll?.end(false)
        } finally { stoppingInteractions = false }
    }
    override fun onDetachedFromWindow() { stopInteractions(); shadows.evictAll(); super.onDetachedFromWindow() }

    private inner class Target(private val id: String) : View(context) {
        fun deliverHover(event: MotionEvent): Boolean = dispatchHoverEvent(event)
        var hoverLabel: String = ""
        private var from = Chrome.Point(0f, 0f)
        private var startFrame = 1
        private var dragging = false
        private var held = false
        private var tapEligible = false
        private var lastPoint = Chrome.Point(0f, 0f)
        private var stripIdentity: BoardChromeIdentity? = null
        private var stripGeometry: CoreFilmStrip? = null
        private var stripOrigin = 0f
        private var stripEdge = -1
        private var lastScrub: String? = null
        val isHolding: Boolean get() = stripIdentity != null && stripEdge >= 0

        private fun beginStrip(point: Chrome.Point) {
            val state = input ?: return
            val identity = sceneIdentity ?: return
            if (!id.startsWith("cell-") || layout.folded || state.kind != BoardKind.ANIMATION ||
                identity.frameIds.size != state.holds.size) return
            stripIdentity = identity
            stripGeometry = CoreFilmStrip(Board(identity.boardId, state.name, BoardKind.ANIMATION,
                RectPx(0, 0, 1, 1), frames = identity.frameIds.mapIndexed { n, frame -> Frame(frame, state.holds[n]) }), state.density)
            // Exactly the layout's unfurled cell origin, including its 8 dp inset and current scroll.
            stripOrigin = state.board.left + 8f * state.density - state.stripScrollPx
            stripEdge = stripGeometry!!.edgeAt(point.x - stripOrigin)
            lastScrub = null
        }

        private fun scrub(point: Chrome.Point) {
            val identity = stripIdentity ?: return
            val geometry = stripGeometry ?: return
            val frameId = identity.frameIds[geometry.frameAt(point.x - stripOrigin)]
            if (frameId != lastScrub) {
                lastScrub = frameId
                host.stripScrub(identity.boardId, frameId)
            }
        }

        private fun hold(point: Chrome.Point, finished: Boolean) {
            val identity = stripIdentity ?: return
            val geometry = stripGeometry ?: return
            val edge = stripEdge
            if (edge < 0) return
            val frame = geometry.board.frames[edge]
            if (finished) clearStrip() // reentrant scene updates cannot cancel a committed gesture
            host.stripHold(identity.boardId, frame.id, frame.holdFrames, point.x - from.x, geometry.density, finished)
        }

        private fun clearStrip() { stripIdentity = null; stripGeometry = null; stripEdge = -1; lastScrub = null }

        private fun cancelStrip() {
            val identity = stripIdentity
            val frameId = identity?.frameIds?.getOrNull(stripEdge)
            clearStrip()
            if (identity != null && frameId != null) host.stripHoldCancelled(identity.boardId, frameId)
        }
        private var hoverToast: Toast? = null
        private val showLabel = Runnable {
            if (hoverLabel.isNotEmpty()) hoverToast = Toast.makeText(context, hoverLabel, Toast.LENGTH_LONG).also { it.show() }
        }
        fun stopInteraction() {
            cancelStrip()
            if (dragging && !id.startsWith("cell-")) host.cancel(id)
            clearNativeTouch()
            cancelLongPress(); isPressed = false; dragging = false
            tapEligible = false
            removeCallbacks(showLabel); hoverToast?.cancel(); hoverToast = null
        }
        private fun clearNativeTouch(event: MotionEvent? = null) {
            val cancel = event?.let { MotionEvent.obtain(it) }
                ?: MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            cancel.action = MotionEvent.ACTION_CANCEL
            try { super.onTouchEvent(cancel) } finally { cancel.recycle() }
        }
        init {
            isClickable = true; isFocusable = true
            setOnClickListener { host.action(id, false) }
            setOnLongClickListener {
                held = true
                val frameIndex = if (id.startsWith("cell-")) id.removePrefix("cell-").toIntOrNull() else null
                // An edge is only a duration gesture once it moves. Short cells have
                // no area outside edgeGrab, so a stationary hold must still lift them.
                if (isHolding && dragging) return@setOnLongClickListener true
                if (frameIndex != null && !layout.folded) { clearStrip(); roll?.begin(frameIndex) }
                else host.action(id, true)
                true
            }
        }
        override fun onHoverEvent(event: MotionEvent): Boolean {
            if (Build.VERSION.SDK_INT < 26) when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> postDelayed(showLabel, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                MotionEvent.ACTION_HOVER_EXIT -> { removeCallbacks(showLabel); hoverToast?.cancel(); hoverToast = null }
            }
            return super.onHoverEvent(event)
        }
        override fun onDetachedFromWindow() {
            removeCallbacks(showLabel); hoverToast?.cancel(); super.onDetachedFromWindow()
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val point = Chrome.Point(event.x + left, event.y + top)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { from = point; lastPoint = point; startFrame = input?.currentFrame ?: 1; dragging = false; held = false; tapEligible = true; beginStrip(point) }
                MotionEvent.ACTION_MOVE -> {
                    if (!Chrome.containsHit(layout, id, point)) tapEligible = false
                    val frameIndex = if (id.startsWith("cell-")) id.removePrefix("cell-").toIntOrNull() else null
                    if (stripIdentity != null) {
                        if (dragging || kotlin.math.abs(point.x - from.x) > android.view.ViewConfiguration.get(context).scaledTouchSlop) {
                            dragging = true
                            if (isHolding) hold(point, false) else scrub(point)
                        }
                    } else if (frameIndex != null && roll?.isDragging(frameIndex) == true) {
                        dragging = true; roll?.move(point.x, point.y)
                    } else if (frameIndex != null && (dragging || kotlin.math.abs(point.x - from.x) > android.view.ViewConfiguration.get(context).scaledTouchSlop)) {
                        dragging = true; host.stripScrollBy((lastPoint.x - point.x).roundToInt())
                    } else if (id == "wheel") {
                        dragging = true
                        input?.let { host.frame(Chrome.wheelFrame(startFrame, from.y - point.y, it.density, it.holds.size)) }
                    } else if (id.startsWith("handle-") || id.startsWith("sprite-cell-")) {
                        dragging = true; host.drag(id, from, point, false)
                    }
                    lastPoint = point
                    if (dragging) { cancelLongPress(); isPressed = false; return true }
                }
                MotionEvent.ACTION_UP -> if (stripIdentity != null) {
                    if (isHolding && dragging) {
                        hold(point, true)
                    } else {
                        // Tapping selects the frame even inside its duration-edge area.
                        if (dragging || tapEligible && !held) scrub(point)
                        clearStrip()
                    }
                    clearNativeTouch(event); tapEligible = false; dragging = false
                    return true
                } else if (dragging || id.removePrefix("cell-").toIntOrNull()?.let { roll?.isDragging(it) } == true) {
                    if (id.startsWith("cell-")) roll?.end(true) else if(id!="wheel") host.drag(id, from, point, true)
                    clearNativeTouch(event); tapEligible = false
                    dragging = false; return true
                } else {
                    // Finish synchronously against the displayed scene. View's posted click
                    // runnable could otherwise fire after that scene or its host was replaced.
                    val click = isEnabled && tapEligible && !held
                    clearNativeTouch(event); tapEligible = false
                    if (click) performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelStrip()
                    if (dragging && !id.startsWith("cell-")) host.cancel(id)
                    roll?.end(false); dragging = false; isPressed = false; tapEligible = false
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
