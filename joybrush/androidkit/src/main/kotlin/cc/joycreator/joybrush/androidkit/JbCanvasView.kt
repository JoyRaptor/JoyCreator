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
        val p = preset
        val erase = b.erase || eraser
        drawing = true
        glBegan = false
        strokePreset = p
        strokeDabber = null
        scatterRng = null
        strokeErase = erase
        val amount = if (p != null && !smoothingFromUser) p.smoothing else smoothing
        smoother = StrokeSmoother(amount, screenPerDoc = 1f)
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
        for (s in MotionEventSamples.from(ev, idx, { x, y -> x to y })) released.addAll(sm.add(s))
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

    /** View px (y down) → clip space (y up), column-major 3×3. */
    private fun viewToClip(w: Int, h: Int): FloatArray =
        floatArrayOf(2f / w, 0f, 0f, 0f, -2f / h, 0f, -1f, 1f, 1f)

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
