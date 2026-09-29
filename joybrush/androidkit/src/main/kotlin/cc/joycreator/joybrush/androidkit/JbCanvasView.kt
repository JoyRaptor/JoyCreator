package cc.joycreator.joybrush.androidkit

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.input.MotionEventSamples
import cc.joycreator.joybrush.core.input.DirectionTracker
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.input.Tool
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.paint.StrokeBlend
import cc.joycreator.joybrush.core.paint.TipShape
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The Joy Brush drawing surface (JB-0.07): pen in, GPU paint out. Drop it into any Activity.
 *
 * UI thread: reads the pen, smooths ([StrokeSmoother]), turns the tip ([DirectionTracker]) and places
 * dabs ([DabPlacer]). GL thread: [GlPaintEngine] paints them. Dabs cross threads through queueEvent,
 * so the pen path is never blocked by rendering.
 *
 * Pen vs finger (owner's ruling): until a pen has been seen, fingers draw. After the first pen event
 * fingers never draw here (Phase 2 turns them into the tool finger). A finger landing while the pen
 * hovers, or within [PALM_GRACE_MS] of the pen lifting, is ignored (palm rejection). A cancelled
 * touch discards the stroke.
 *
 * Document space == view pixels for now (zoom and pan arrive with the gesture work, JB-2.02).
 */
class JbCanvasView(context: Context) : GLSurfaceView(context) {

    /** The brush in use. Change it between strokes. */
    data class Brush(
        val argb: Int = 0xFF1B1B22.toInt(),
        val sizePx: Float = 12f,
        val minSizeFraction: Float = 0.15f,
        val opacity: Float = 1f,
        val flow: Float = 1f,
        val spacing: Float = 0.04f,
        val accumulate: Accumulate = Accumulate.WASH,
        val erase: Boolean = false,
        val followDirection: Boolean = false,
        val tip: TipShape = TipShape(),
    )

    var brush = Brush()
    var smoothing = 0.35f
    var paperArgb: Int = 0xFFFFFFFF.toInt()
        set(v) { field = v; requestRender() }

    /** Called on the UI thread after each committed stroke / undo / redo (e.g. to enable buttons). */
    var onHistoryChanged: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null

    /**
     * Every touch and hover event, before any of this view's own handling (JB-0.06). The hidden
     * pen diagnostics overlay listens here and nothing else uses it, so drawing is unaffected.
     */
    var onRawEvent: ((MotionEvent) -> Unit)? = null

    private val engine = GlPaintEngine()
    private val layerId = "layer-1"
    @Volatile private var viewW = 1
    @Volatile private var viewH = 1

    private var penSeen = false
    private var penHovering = false
    private var penUpAt = 0L
    private var drawing = false
    private var pointerId = -1

    private var smoother: StrokeSmoother? = null
    private var tracker: DirectionTracker? = null
    private var placer: DabPlacer? = null

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                engine.init()
                engine.addLayer(layerId)
            }
            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                viewW = width; viewH = height
            }
            override fun onDrawFrame(gl: GL10?) {
                engine.draw(viewW, viewH, viewToClip(viewW, viewH), paperArgb)
            }
        })
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun undo() = onGl { engine.undoStep(); reportHistory() }
    fun redo() = onGl { engine.redoStep(); reportHistory() }
    fun clearCanvas() = onGl { engine.clearLayer(layerId); reportHistory() }

    // ── input ────────────────────────────────────────────────────────────────

    override fun onHoverEvent(event: MotionEvent): Boolean {
        onRawEvent?.invoke(event)
        if (MotionEventSamples.tool(event, 0).let { it == Tool.STYLUS || it == Tool.ERASER }) {
            penSeen = true
            penHovering = event.actionMasked != MotionEvent.ACTION_HOVER_EXIT
        }
        return super.onHoverEvent(event)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        onRawEvent?.invoke(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val tool = MotionEventSamples.tool(ev, 0)
                val isPen = tool == Tool.STYLUS || tool == Tool.ERASER
                if (isPen) penSeen = true
                if (!isPen && (penSeen || penHovering || SystemClock.uptimeMillis() - penUpAt < PALM_GRACE_MS)) return true
                if (isPen && Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(ev)
                pointerId = ev.getPointerId(0)
                startStroke(eraser = tool == Tool.ERASER)
                feed(ev)
            }
            MotionEvent.ACTION_MOVE -> if (drawing) feed(ev)
            MotionEvent.ACTION_UP -> if (drawing) {
                feed(ev)
                finishStroke()
                if (MotionEventSamples.tool(ev, 0) != Tool.FINGER) penUpAt = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_CANCEL -> if (drawing) cancelStroke()
            MotionEvent.ACTION_POINTER_DOWN -> if (drawing && !penSeen) cancelStroke() // second finger: not a stroke
        }
        if (drawing && Build.VERSION.SDK_INT >= 33 && (ev.flags and MotionEvent.FLAG_CANCELED) != 0) cancelStroke()
        return true
    }

    private fun startStroke(eraser: Boolean) {
        val b = brush
        drawing = true
        smoother = StrokeSmoother(smoothing, screenPerDoc = 1f)
        val tr = DirectionTracker().also { tracker = it }
        val erase = b.erase || eraser
        val cap = if (b.accumulate == Accumulate.WASH) b.opacity else 1f
        placer = DabPlacer(
            spacing = b.spacing,
            radiusOf = { s -> 0.5f * b.sizePx * (b.minSizeFraction + (1f - b.minSizeFraction) * s.pressure) },
            angleOf = if (b.followDirection) { s -> tr.update(s) } else { _ -> 0f },
            flowOf = { b.flow },
            cap = cap,
        )
        onGl {
            engine.beginStroke(layerId, b.argb, b.opacity, b.accumulate,
                if (erase) StrokeBlend.ERASE else StrokeBlend.NORMAL, b.tip)
        }
    }

    private fun feed(ev: MotionEvent) {
        val idx = ev.findPointerIndex(pointerId)
        if (idx < 0) return
        val sm = smoother ?: return
        val released = ArrayList<PenSample>()
        for (s in MotionEventSamples.from(ev, idx, { x, y -> x to y })) released.addAll(sm.add(s))
        paint(released)
    }

    private fun finishStroke() {
        smoother?.let { paint(it.finish()) }
        drawing = false
        smoother = null; placer = null; tracker = null
        onGl { engine.endStroke(); reportHistory() }
    }

    private fun cancelStroke() {
        drawing = false
        smoother = null; placer = null; tracker = null
        onGl { engine.cancelStroke() }
    }

    private fun paint(points: List<PenSample>) {
        if (points.isEmpty()) return
        val dabs = placer?.add(points) ?: return
        if (dabs.isNotEmpty()) onGl { engine.addDabs(dabs) }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun onGl(block: () -> Unit) {
        queueEvent(block)
        requestRender()
    }

    private fun reportHistory() {
        val u = engine.undo.canUndo
        val r = engine.undo.canRedo
        post { onHistoryChanged?.invoke(u, r) }
    }

    /** View px (y down) → clip space (y up), column-major 3×3. */
    private fun viewToClip(w: Int, h: Int): FloatArray =
        floatArrayOf(2f / w, 0f, 0f, 0f, -2f / h, 0f, -1f, 1f, 1f)

    companion object {
        /** Fingers are ignored for this long after the pen lifts (palm rejection). */
        const val PALM_GRACE_MS = 400L
    }
}
