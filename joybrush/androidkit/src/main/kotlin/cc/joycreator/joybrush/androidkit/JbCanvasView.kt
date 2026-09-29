package cc.joycreator.joybrush.androidkit

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.input.MotionEventSamples
import cc.joycreator.joybrush.core.brush.BrushDabber
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.DabInputs
import cc.joycreator.joybrush.core.brush.Scatter
import cc.joycreator.joybrush.core.brush.SplitMix
import cc.joycreator.joybrush.core.input.DirectionTracker
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.input.Tool
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.paint.StrokeBlend
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.view.ViewTransform
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The Joy Brush drawing surface (JB-0.07): pen in, GPU paint out. Drop it into any Activity.
 *
 * UI thread: reads the pen, smooths ([StrokeSmoother]), turns the tip ([DirectionTracker]) and places
 * dabs ([DabPlacer]). GL thread: [GlPaintEngine] paints them. Dabs cross threads through queueEvent,
 * so the pen path is never blocked by rendering.
 *
 * A stroke is laid down in one of two ways. With [preset] null it is the hard-coded round [Brush]
 * below. With a brush FILE set, the dab itself is the file's answer ([BrushDabber], one call per
 * dab), its scatter is [Scatter]'s, and the engine's whole-stroke numbers -- opacity, accumulate,
 * blend and the tip -- are read off the same file. The file is read once per stroke, not per dab.
 *
 * Pen vs finger (owner's ruling): until a pen has been seen, ONE finger draws. The moment a pen is
 * seen a finger never draws here again — it navigates instead (JB-2.02), through [CanvasGestures]:
 * two fingers pan, zoom and turn the page, and a tap of two, three or four fingers is undo, redo
 * and hide-the-UI. A finger landing while the pen HOVERS, or within [PALM_GRACE_MS] of the pen
 * lifting, is ignored outright (palm rejection — a resting palm must never drag the page), and a
 * second finger landing during a finger stroke cancels that stroke and hands the stream to the
 * gestures. A cancelled touch discards the stroke.
 *
 * The view transform is [view]: a pen sample is recorded in DOCUMENT pixels, so every sample goes
 * through `view.screenToDoc` and the smoother is told the zoom, and the GL draw is handed
 * `view.docToClip` instead of a plain view matrix.
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

    /**
     * The brush FILE in use (JB-1.05b), or null for the hard-coded round [Brush] above. Change it
     * between strokes: a stroke in progress keeps the preset it started with, so every dab of one
     * stroke comes from one file and one seed.
     */
    @Volatile var preset: BrushPreset? = null

    /**
     * True once the person has moved the smoothing slider, and only then does the slider beat the
     * brush file's own `smoothing` -- so Ink keeps its 0.35 and Pencil its 0.2 without either of
     * those numbers being written down here.
     */
    @Volatile var smoothingFromUser = false

    var paperArgb: Int = 0xFFFFFFFF.toInt()
        set(v) { field = v; requestRender() }

    /**
     * Zoom, rotation and pan (JB-2.02) — the one place that says where the document is on the
     * screen. Public so the screen can fit the board or put it back where it was; the gesture
     * machine writes to it, nothing else does.
     */
    val view = ViewTransform()

    /**
     * Called when four fingers tap. Nothing on screen listens yet — the chrome that would hide is
     * JB-2.01's — and the view ignores it until something does.
     */
    var onToggleUi: (() -> Unit)? = null

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

    /** Every touch that is not drawing (JB-2.02). Owns pan, zoom, rotate and the tap gestures. */
    private val gestures = CanvasGestures(
        view,
        onViewChanged = { requestRender() },
        onUndo = { undo() },
        onRedo = { redo() },
        onToggleUi = { onToggleUi?.invoke() },
    )

    private var penSeen = false
    private var penHovering = false
    private var penUpAt = 0L
    private var drawing = false
    private var pointerId = -1
    private var laidOut = false

    private var smoother: StrokeSmoother? = null
    private var tracker: DirectionTracker? = null
    private var placer: DabPlacer? = null

    // JB-1.05b: the file-driven stroke's own state, all of it one stroke long.
    private var strokePreset: BrushPreset? = null
    private var strokeDabber: BrushDabber? = null
    private var scatterRng: SplitMix? = null
    private var strokeErase = false
    private var glBegan = false

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
                if (!laidOut) {
                    // First layout: the page starts on its own top-left corner, unzoomed and
                    // upright, so document (0, 0) is exactly the screen origin. Later layouts —
                    // rotation, a keyboard, a resumed Activity — must NOT touch the view, or the
                    // page would jump under the person mid-drawing.
                    laidOut = true
                    view.zoom = 1f
                    view.rotation = 0f
                    view.panX = 0f
                    view.panY = 0f
                }
            }
            override fun onDrawFrame(gl: GL10?) {
                engine.draw(viewW, viewH, view.docToClip(viewW, viewH), paperArgb)
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
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            if (isPenAt(ev, 0)) {
                penSeen = true
                if (Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(ev)
                pointerId = ev.getPointerId(0)
                startStroke(eraser = MotionEventSamples.tool(ev, 0) == Tool.ERASER)
                feed(ev)
            } else if (penHovering || SystemClock.uptimeMillis() - penUpAt < PALM_GRACE_MS) {
                return true // a palm, not a finger: it must not draw and it must not navigate
            } else {
                // After the first pen a finger only navigates; before it, one finger draws.
                gestures.fingersNavigate = penSeen
                if (!penSeen) {
                    pointerId = ev.getPointerId(0)
                    startStroke(eraser = false)
                    feed(ev)
                }
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (drawing) feed(ev)
        } else if (action == MotionEvent.ACTION_UP) {
            if (drawing) {
                feed(ev)
                finishStroke()
            }
            if (MotionEventSamples.tool(ev, 0) != Tool.FINGER) penUpAt = SystemClock.uptimeMillis()
        } else if (action == MotionEvent.ACTION_CANCEL) {
            if (drawing) cancelStroke()
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = ev.actionIndex
            if (isPenAt(ev, i)) {
                // A pen always draws, even when it lands while a finger is already down.
                penSeen = true
                gestures.reset()
                if (drawing) cancelStroke()
                pointerId = ev.getPointerId(i)
                startStroke(eraser = MotionEventSamples.tool(ev, i) == Tool.ERASER)
                feed(ev)
            } else if (drawing) {
                cancelStroke() // a second finger during a finger stroke: that is a view gesture
            }
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            val i = ev.actionIndex
            if (drawing && ev.getPointerId(i) == pointerId) {
                feed(ev)
                finishStroke()
                if (isPenAt(ev, i)) penUpAt = SystemClock.uptimeMillis()
            }
        }
        if (drawing && Build.VERSION.SDK_INT >= 33 && (ev.flags and MotionEvent.FLAG_CANCELED) != 0) cancelStroke()
        if (!drawing) gestures.onEvent(ev)
        return true
    }

    /** True for the pen's two ends — a stylus and an eraser barrel, never a finger or a mouse. */
    private fun isPenAt(ev: MotionEvent, pointerIndex: Int): Boolean {
        val tool = MotionEventSamples.tool(ev, pointerIndex)
        return tool == Tool.STYLUS || tool == Tool.ERASER
    }

    private fun startStroke(eraser: Boolean) {
        val b = brush
        val p = preset
        val erase = b.erase || eraser
        drawing = true
        glBegan = false
        strokePreset = p
        strokeDabber = null
        scatterRng = null
        strokeErase = erase
        val amount = if (p != null && !smoothingFromUser) p.smoothing else smoothing
        // Smoothing is measured in SCREEN px, so it is told the zoom: the slider then means the
        // same thing at every zoom, and the samples stay in document px either way.
        smoother = StrokeSmoother(amount, screenPerDoc = view.zoom)
        val tr = DirectionTracker().also { tracker = it }
        if (p == null) {
            val cap = if (b.accumulate == Accumulate.WASH) b.opacity else 1f
            placer = DabPlacer(
                spacing = b.spacing,
                radiusOf = { s -> 0.5f * b.sizePx * (b.minSizeFraction + (1f - b.minSizeFraction) * s.pressure) },
                angleOf = if (b.followDirection) { s -> tr.update(s) } else { _ -> 0f },
                flowOf = { b.flow },
                cap = cap,
            )
            glBegan = true
            onGl {
                engine.beginStroke(layerId, b.argb, b.opacity, b.accumulate,
                    if (erase) StrokeBlend.ERASE else StrokeBlend.NORMAL, b.tip)
            }
        } else {
            // One dabber per stroke: it holds the random stream, the speed filter and the tip
            // direction, and the placer asks it exactly once per dab. The placer's own cap stays 1
            // -- a WASH dab carries its own opacity as its cap, and a BUILD_UP dab wants 1.
            val seed = SystemClock.uptimeMillis()
            val d = BrushDabber(p, seed)
            strokeDabber = d
            scatterRng = SplitMix(seed xor SCATTER_SALT)
            placer = DabPlacer(spacing = d.spacing, look = d::look)
            // glBegan stays false here on purpose: the stroke is begun on the GL thread only once a
            // first dab exists, so the tip's hardness and the stroke's opacity are the ones the
            // file evaluated AT that dab rather than the base values the file happens to carry.
        }
    }

    /**
     * Posts [GlPaintEngine.beginStroke] for the stroke in progress, from [paint] on its first real
     * dabs. The hard-coded [Brush] branch of [startStroke] has already done this, which is what
     * [glBegan] remembers.
     */
    private fun beginStrokeNow() {
        val b = brush
        val p = strokePreset
        val d = strokeDabber
        val accumulate = if (p == null) b.accumulate
        else if (p.accumulate == "buildup") Accumulate.BUILD_UP
        else Accumulate.WASH
        val eraseBlend = strokeErase || (p != null && p.blend == "erase")
        val opacity = if (p == null) b.opacity else if (d == null) p.opacity.base else d.strokeOpacity
        val tip = if (p == null) b.tip else TipShape(
            aspect = p.tip.aspect,
            corner = p.tip.corner,
            taper = p.tip.taper,
            hardness = if (d == null) p.tip.hardness.base else d.strokeHardness,
            minPx = p.tip.minPx,
        )
        glBegan = true
        onGl { engine.beginStroke(layerId, b.argb, opacity, accumulate,
            if (eraseBlend) StrokeBlend.ERASE else StrokeBlend.NORMAL, tip) }
    }

    private fun feed(ev: MotionEvent) {
        val idx = ev.findPointerIndex(pointerId)
        if (idx < 0) return
        val sm = smoother ?: return
        val released = ArrayList<PenSample>()
        // The pen reports SCREEN px; a stroke is recorded in DOCUMENT px, and the page's own turn
        // is what the lean direction is read against.
        val samples = MotionEventSamples.from(ev, idx, { x, y -> view.screenToDoc(x, y) }, view.rotation)
        for (s in samples) released.addAll(sm.add(s))
        paint(released)
    }

    private fun finishStroke() {
        smoother?.let { paint(it.finish()) }
        drawing = false
        smoother = null; placer = null; tracker = null
        strokePreset = null; strokeDabber = null; scatterRng = null; strokeErase = false
        onGl { engine.endStroke(); reportHistory() }
    }

    private fun cancelStroke() {
        drawing = false
        smoother = null; placer = null; tracker = null
        strokePreset = null; strokeDabber = null; scatterRng = null; strokeErase = false
        onGl { engine.cancelStroke() }
    }

    private fun paint(points: List<PenSample>) {
        if (points.isEmpty()) return
        val placed = placer?.add(points) ?: return
        if (placed.isEmpty()) return
        val p = strokePreset
        val dabs = if (p == null) placed else scattered(p, placed)
        if (!glBegan) beginStrokeNow()
        onGl { engine.addDabs(dabs) }
    }

    /**
     * The file's own scatter, over the dabs the placer has just built. [Scatter] asks once per input
     * dab, and every draw a scatter makes is in the same order whatever the batches were, so
     * feeding it batch by batch gives the same leaves as feeding it the whole stroke at once.
     */
    private fun scattered(p: BrushPreset, placed: List<Dab>): List<Dab> {
        val rng = scatterRng ?: return placed
        return Scatter.expand(placed, p.scatter, rng) { dab -> dabInputsOf(dab) }
    }

    /**
     * What [Scatter] gets to ask about one dab: its pressure, and nothing else. A [Dab] does not
     * carry tilt or speed or distance, so those are NaN — which the curves read as "not available"
     * and skip, leaving a scatter curve on tilt to contribute its base rather than a number invented
     * here.
     */
    private fun dabInputsOf(dab: Dab) = DabInputs(
        pressure = dab.pressure,
        tilt = Float.NaN,
        speedPxPerS = Float.NaN,
        direction = Float.NaN,
        lean = Float.NaN,
        distancePx = Float.NaN,
        random = Float.NaN,
        strokeRandom = Float.NaN,
        barrel = Float.NaN,
    )

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

    companion object {
        /** Fingers are ignored for this long after the pen lifts (palm rejection). */
        const val PALM_GRACE_MS = 400L

        /**
         * The scatter generator's salt, so its stream is not the dabber's own. A second generator
         * rather than a second stream out of one, because [BrushDabber]'s draws per dab are fixed
         * by its own contract and must not be walked differently.
         */
        const val SCATTER_SALT = 0x5CA7L
    }
}
