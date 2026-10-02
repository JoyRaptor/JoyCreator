package cc.joycreator.joybrush.android.board

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import cc.joycreator.joybrush.core.chrome.BoardChromeLayout as Chrome
import cc.joycreator.joybrush.core.chrome.BoardHoverLoop
import cc.joycreator.joybrush.core.chrome.IconInk
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
        fun drag(id: String, from: Chrome.Point, to: Chrome.Point, finished: Boolean) {}
        fun frame(frame: Int) {}
        fun stripScrollBy(pixels: Int) {}
        fun stripLift(index: Int, dx: Float, dy: Float, removing: Boolean, active: Boolean) {}
        fun stripGap(index: Int) {}
        fun stripHint(active: Boolean, removing: Boolean) {}
        fun stripDrop(from: Int, insertionGap: Int, remove: Boolean) {}
        fun hoverLoop(playing: Boolean, restoreFrame: Int?) {}
        /** Draw the board's region art into the supplied, already clipped screen rectangle. */
        fun art(canvas: Canvas, element: Chrome.Element) {}
        /** Optional Frost snapshot, already blurred by the shared Frost provider. */
        fun frost(canvas: Canvas, element: Chrome.Element) {}
    }

    var host: Host = object : Host {}
    var paperInk: IconInk = IconInk.LIGHT
        set(value) { field = value; invalidate() }
    var paperColour: Int = Color.TRANSPARENT
        set(value) { field = value; invalidate() }
    /** Optional injection of the ONE app ic_export_studio asset. */
    var exportDrawable: Drawable? = null
        set(value) { field = value?.mutate(); invalidate() }

    private var layout = Chrome.Layout(emptyList(), emptyList(), false, false, false)
    private var input: Chrome.Input? = null
    private val targets = linkedMapOf<String, View>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Path()
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
        // Canvas shadows must work on the owner's API 28 device as well as newer releases.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun show(input: Chrome.Input) {
        val previous = this.input
        if (previous != null && (previous.density != input.density || previous.selected != input.selected || previous.kind != input.kind || previous.holds != input.holds)) {
            stopInteractions()
        }
        this.input = input
        submit(Chrome.layout(input))
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
        layout = value
        val ids = value.controls.map { it.id }.toSet()
        targets.keys.filter { it !in ids && targets[it] !== activeTarget }.toList().forEach {
            removeView(targets.remove(it))
        }
        value.controls.forEach { control ->
            val target = targets.getOrPut(control.id) { Target(control.id).also { addView(it) } }
            target.contentDescription = control.tooltip
            // Compat tooltips take the long-click listener on API 24–25. Native hover tooltips
            // preserve the board menu, FPS and add-frame holds on API 26+ (including Note 9).
            if (Build.VERSION.SDK_INT >= 26) target.tooltipText = control.tooltip
            else (target as Target).hoverLabel = control.tooltip
            target.layout(control.hit.left.roundToInt(), control.hit.top.roundToInt(),
                control.hit.right.roundToInt(), control.hit.bottom.roundToInt())
        }
        requestLayout()
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
        layout.elements.forEach { draw(canvas, it) }
    }

    private fun draw(canvas: Canvas, e: Chrome.Element) {
        val save = canvas.save()
        e.clip?.let { canvas.clipRect(it.left, it.top, it.right, it.bottom) }
        val fadeLayer = if (e.fadeStartPx != null && e.fadeEndPx != null) canvas.saveLayer(null, null) else -1
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
                if (e.colour == Chrome.Colour.GLASS) clipped(canvas, outline) { host.frost(canvas, e) }
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
                if (e.artSlot || e.id == "sprite-lift" || e.id.startsWith("sprite-cell-")) clipped(canvas, outline) { host.art(canvas, e) }
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
            maskPaint.reset()
            maskPaint.shader = LinearGradient(e.fadeStartPx!!, 0f, e.fadeEndPx!!, 0f,
                Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            e.clip?.let { canvas.drawRect(it.left, it.top, it.right, it.bottom, maskPaint) }
        }
        canvas.restoreToCount(save)
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
    }
    private fun contrastColour() = if (paperInk == IconInk.DARK) Color.WHITE else Color.BLACK

    /** Core chooses among overlapping >=40dp targets. No child z order steals a neighbour. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            activeTarget = Chrome.hit(layout, Chrome.Point(event.x, event.y))?.let { targets[it.id] }
        }
        val target = activeTarget ?: return false
        val local = MotionEvent.obtain(event)
        local.offsetLocation(-target.left.toFloat(), -target.top.toFloat())
        val handled = target.dispatchTouchEvent(local)
        local.recycle()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) activeTarget = null
        return handled
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val target = if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) null
            else Chrome.hit(layout, Chrome.Point(event.x, event.y))?.let { targets[it.id] }
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
    /** The Activity calls this before pausing; a hover loop never survives leaving the screen. */
    fun stopInteractions() {
        targets.values.forEach { (it as Target).stopInteraction() }
        activeTarget = null
        hoveredTarget = null
        stopHover()
        roll?.end(false)
    }
    override fun onDetachedFromWindow() { stopInteractions(); super.onDetachedFromWindow() }

    private inner class Target(private val id: String) : View(context) {
        fun deliverHover(event: MotionEvent): Boolean = dispatchHoverEvent(event)
        var hoverLabel: String = ""
        private var from = Chrome.Point(0f, 0f)
        private var startFrame = 1
        private var dragging = false
        private var lastPoint = Chrome.Point(0f, 0f)
        private var hoverToast: Toast? = null
        private val showLabel = Runnable {
            if (hoverLabel.isNotEmpty()) hoverToast = Toast.makeText(context, hoverLabel, Toast.LENGTH_LONG).also { it.show() }
        }
        fun stopInteraction() {
            cancelLongPress(); isPressed = false; dragging = false
            removeCallbacks(showLabel); hoverToast?.cancel(); hoverToast = null
        }
        init {
            isClickable = true; isFocusable = true
            setOnClickListener { host.action(id, false) }
            setOnLongClickListener {
                val frameIndex = if (id.startsWith("cell-")) id.removePrefix("cell-").toIntOrNull() else null
                if (frameIndex != null && !layout.folded) roll?.begin(frameIndex)
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
                MotionEvent.ACTION_DOWN -> { from = point; lastPoint = point; startFrame = input?.currentFrame ?: 1; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val frameIndex = if (id.startsWith("cell-")) id.removePrefix("cell-").toIntOrNull() else null
                    if (frameIndex != null && roll?.isDragging(frameIndex) == true) {
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
                MotionEvent.ACTION_UP -> if (dragging || id.removePrefix("cell-").toIntOrNull()?.let { roll?.isDragging(it) } == true) {
                    if (id.startsWith("cell-")) roll?.end(true) else host.drag(id, from, point, true)
                    dragging = false; return true
                }
                MotionEvent.ACTION_CANCEL -> { roll?.end(false); dragging = false; isPressed = false }
            }
            return super.onTouchEvent(event)
        }
    }
}
