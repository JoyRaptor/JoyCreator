package cc.joycreator.joybrush.androidkit

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import cc.joycreator.joybrush.androidkit.diag.BlendSelfCheck
import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.gl.SmudgeParams
import cc.joycreator.joybrush.androidkit.input.MotionEventSamples
import cc.joycreator.joybrush.androidkit.io.JbArchiveException
import cc.joycreator.joybrush.androidkit.tools.EyedropState
import cc.joycreator.joybrush.androidkit.tools.Eyedropper
import cc.joycreator.joybrush.core.input.PenAction
import cc.joycreator.joybrush.core.input.PenButton
import cc.joycreator.joybrush.core.input.PenButtonMap
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.androidkit.io.TILE_BYTES
import cc.joycreator.joybrush.core.brush.BrushDabber
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE
import cc.joycreator.joybrush.core.brush.DabInputs
import cc.joycreator.joybrush.core.brush.Scatter
import cc.joycreator.joybrush.core.brush.SplitMix
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.input.DirectionTracker
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.input.Tool
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.paint.StrokeBlend
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.view.ViewTransform
import java.util.Locale
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

    @Volatile var paperArgb: Int = 0xFFFFFFFF.toInt()
        set(v) { field = v; requestRender() }

    /**
     * Zoom, rotation and pan (JB-2.02) — the one place that says where the document is on the
     * screen. Public so the screen can fit the board or put it back where it was; the gesture
     * machine writes to it, nothing else does.
     *
     * UI thread only. The GL thread never reads it: [requestRender] (called on the UI thread after
     * every change) copies the four numbers into [drawView] in one go, so a frame can never pair
     * one gesture step's zoom with another's pan.
     */
    val view = ViewTransform()

    /** GL thread's copy of [view], replaced whole — never mutated — so it cannot be half-updated. */
    @Volatile private var viewSnapshot = floatArrayOf(1f, 0f, 0f, 0f)
    private val drawView = ViewTransform()

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

    /**
     * Called on the UI thread when a snapshot cannot be taken (JB-0.08b), with the reason in words.
     * The screen shows it: a save that failed has to say so, or the drawing is simply gone.
     */
    var onSnapshotFailed: ((String) -> Unit)? = null

    /**
     * Whether the pen is mid-stroke right now (R11). A snapshot taken in that state captures the
     * tiles as they were BEFORE this stroke, so a save that ran then would write a drawing missing
     * the mark the person is making at that instant — and the autosave is precisely where that
     * matters. The screen asks before it saves and never ends or cancels a stroke to make the
     * question easier.
     *
     * This is the UI thread's own [drawing] flag and not the engine's, on purpose (JB-0.08b review
     * finding 4): the engine's `strokeLayer` is written on the GL thread and read here with no
     * synchronisation. And the UI flag is the exact one: [finishStroke] and [cancelStroke] clear it
     * only AFTER they have queued the stroke's end on the GL thread, and a snapshot is queued behind
     * that, so a snapshot asked for while this reads false always sees the finished stroke.
     */
    val strokeInProgress: Boolean get() = drawing

    /**
     * Called on the UI thread when a stroke has ended OR been cancelled, after its end is queued on
     * the GL thread. This is the one signal a save that was waiting for the pen is released by; an
     * undo, redo or clear is a history event and is NOT it (JB-0.08b review finding 2).
     */
    var onStrokeEnded: (() -> Unit)? = null

    // ── colour (JB-2.03a) ────────────────────────────────────────────────────

    /** The colour strokes are drawn in, or null for the brush's own. UI thread. Read when a stroke begins. */
    @Volatile var colorArgb: Int? = null

    /** Called on the UI thread after an eyedropper result has been taken ([colorArgb] is already set). */
    var onColorPicked: ((Int) -> Unit)? = null

    /** The eyedropper's ring to draw, or null to hide it. The screen owns the overlay that draws it. */
    var onEyedrop: ((EyedropState?) -> Unit)? = null

    /** Long-press on the canvas picks a colour (default on; a setting can turn it off). */
    var longPressEyedropper = true

    /** What the pen's buttons do (R42). The screen loads the person's own map; until it does, the defaults. */
    var penButtons: PenButtonMap = PenButtonMap.DEFAULT

    /** The colour a new stroke starts with: the override if there is one, else the brush's. */
    val strokeColor: Int get() = colorArgb ?: brush.argb

    private val engine = GlPaintEngine()
    private val layerId = "layer-1"
    @Volatile private var viewW = 1
    @Volatile private var viewH = 1

    /** The document's own name, so a file that is opened and saved again keeps the name it had. */
    @Volatile private var documentName = "Joy Brush"
    private val whitePaper = 0xFFFFFFFF.toInt()

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
                // The view is NOT touched here: a new ViewTransform is already identity (the page
                // starts on its own top-left corner, unzoomed and upright), and later layouts —
                // rotation, a keyboard, a resumed Activity — must not make the page jump.
                viewW = width; viewH = height
            }
            override fun onDrawFrame(gl: GL10?) {
                val s = viewSnapshot
                drawView.zoom = s[0]; drawView.rotation = s[1]; drawView.panX = s[2]; drawView.panY = s[3]
                engine.draw(viewW, viewH, drawView.docToClip(viewW, viewH), paperArgb)
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
        if (eyedropTouch(ev)) return true
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            if (isPenAt(ev, 0) && (ev.buttonState and PenButton.BIT_STYLUS_PRIMARY) != 0) {
                // The pen button held at touch-down is a BUTTON gesture, never a stroke (R42): a quick tap runs the button's
                // tap action (the eyedropper by default). A drag with it held is the hold action (lasso), which is not wired to
                // this screen yet, so it does nothing rather than paint.
                buttonDown = true
                buttonDownAt = SystemClock.uptimeMillis()
                buttonDownX = ev.x; buttonDownY = ev.y
                buttonMoved = false
                penSeen = true
                return true
            }
            if (isPenAt(ev, 0)) {
                penSeen = true
                if (Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(ev)
                pointerId = ev.getPointerId(0)
                startStroke(eraser = MotionEventSamples.tool(ev, 0) == Tool.ERASER)
                feed(ev)
                watchForHold(ev, 0)
            } else if (penHovering || SystemClock.uptimeMillis() - penUpAt < PALM_GRACE_MS) {
                return true // a palm, not a finger: it must not draw and it must not navigate
            } else {
                // After the first pen a finger only navigates; before it, one finger draws.
                gestures.fingersNavigate = penSeen
                if (!penSeen) {
                    pointerId = ev.getPointerId(0)
                    startStroke(eraser = false)
                    feed(ev)
                    watchForHold(ev, 0)
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
                gestures.handOver() // …which the gesture machine never saw start (JB-2.02 review)
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

    // ── the eyedropper (JB-2.03a) ────────────────────────────────────────────

    private var buttonDown = false
    private var buttonDownAt = 0L
    private var buttonDownX = 0f
    private var buttonDownY = 0f
    private var buttonMoved = false

    /** The stroke's first touch, for the long-press: where and when it began and whether it has wandered. */
    private var holdX = 0f
    private var holdY = 0f
    private var holdSlop = 0f
    private var holdPointer = -1

    /** The eyedropper is up (after a long-press, or dragged off the colour pill). */
    private var eyedropping = false
    private var eyedropOld = 0
    private var eyedropNew = 0
    private var eyedropX = 0f
    private var eyedropY = 0f
    private var cancelX = 0f
    private var cancelY = 0f
    private var cancelR = 0f
    private var sampleSeq = 0

    private val holdFired = Runnable {
        if (!drawing || !longPressEyedropper) return@Runnable
        // A hold that stayed still: the dot the pen made is thrown away (nothing is committed) and the ring comes up.
        cancelStroke()
        startEyedrop(holdX, holdY, withCancelCircle = true)
    }

    /** The pen button's tap, the long-press, and a live eyedropper own the touch. True = handled here. */
    private fun eyedropTouch(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        if (buttonDown) {
            val slop = Eyedropper.PEN_SLOP_PX
            if (Math.hypot((ev.x - buttonDownX).toDouble(), (ev.y - buttonDownY).toDouble()) > slop) buttonMoved = true
            if (action == MotionEvent.ACTION_UP) {
                buttonDown = false
                penUpAt = SystemClock.uptimeMillis()
                val quick = SystemClock.uptimeMillis() - buttonDownAt <= Eyedropper.PEN_BUTTON_TAP_MS
                if (quick && !buttonMoved &&
                    penButtons.tapActionFor(PenButton.bit(PenButton.BIT_STYLUS_PRIMARY)) == PenAction.EYEDROPPER) {
                    sampleAt(ev.x, ev.y) { argb -> take(argb) }
                }
            } else if (action == MotionEvent.ACTION_CANCEL) {
                buttonDown = false
            }
            return true
        }
        if (eyedropping) {
            when (action) {
                MotionEvent.ACTION_MOVE -> eyedropMove(ev.x, ev.y)
                MotionEvent.ACTION_UP -> eyedropEnd(take = true)
                // A second finger is the other way out.
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> eyedropEnd(take = false)
            }
            return true
        }
        // The long-press watch, while a stroke is being drawn by its first touch.
        if (drawing && holdPointer >= 0) {
            val i = ev.findPointerIndex(holdPointer)
            if (action == MotionEvent.ACTION_MOVE && i >= 0 &&
                Math.hypot((ev.getX(i) - holdX).toDouble(), (ev.getY(i) - holdY).toDouble()) > holdSlop) {
                stopHoldWatch()
            } else if (action != MotionEvent.ACTION_MOVE) {
                stopHoldWatch()
            }
        }
        return false
    }

    private fun watchForHold(ev: MotionEvent, index: Int) {
        stopHoldWatch()
        if (!longPressEyedropper) return
        holdPointer = ev.getPointerId(index)
        holdX = ev.getX(index)
        holdY = ev.getY(index)
        holdSlop = if (isPenAt(ev, index)) Eyedropper.PEN_SLOP_PX else Eyedropper.FINGER_SLOP_PX
        postDelayed(holdFired, Eyedropper.HOLD_MS)
    }

    private fun stopHoldWatch() {
        removeCallbacks(holdFired)
        holdPointer = -1
    }

    private fun startEyedrop(x: Float, y: Float, withCancelCircle: Boolean) {
        eyedropping = true
        eyedropOld = strokeColor
        eyedropNew = eyedropOld
        cancelX = x; cancelY = y
        cancelR = if (withCancelCircle) Eyedropper.CANCEL_CIRCLE_DP * resources.displayMetrics.density / 2f else 0f
        eyedropMove(x, y)
    }

    private fun eyedropMove(x: Float, y: Float) {
        eyedropX = x; eyedropY = y
        publishEyedrop()
        val seq = ++sampleSeq
        sampleAt(x, y) { argb -> if (seq == sampleSeq && eyedropping) { eyedropNew = argb; publishEyedrop() } }
    }

    private fun overCancel(): Boolean =
        cancelR > 0f && Eyedropper.insideCircle(eyedropX, eyedropY, cancelX, cancelY, cancelR)

    private fun publishEyedrop() {
        onEyedrop?.invoke(EyedropState(eyedropX, eyedropY, eyedropNew, eyedropOld, cancelX, cancelY, cancelR, overCancel()))
    }

    private fun eyedropEnd(take: Boolean) {
        val took = take && !overCancel()
        val x = eyedropX
        val y = eyedropY
        eyedropping = false
        sampleSeq++
        onEyedrop?.invoke(null)
        // Sample once more exactly where the pen left, so a fast lift still takes the colour under it.
        if (took) sampleAt(x, y) { argb -> take(argb) }
    }

    private fun take(argb: Int) {
        colorArgb = argb
        onColorPicked?.invoke(argb)
    }

    /**
     * The colour a person SEES at a screen point (view-local px): the layer's pixel at that document point, at the layer's opacity, over
     * the paper. Read on the GL thread (a texture can only be read there); [onColor] arrives on the UI thread, opaque.
     */
    fun sampleAt(screenX: Float, screenY: Float, onColor: (Int) -> Unit) {
        val (dx, dy) = view.screenToDoc(screenX, screenY)
        val px = kotlin.math.floor(dx).toInt()
        val py = kotlin.math.floor(dy).toInt()
        val paper = paperArgb
        onGl {
            val pixel = engine.readPixel(layerId, px, py)
            val argb = Eyedropper.seen(pixel, 0, engine.layerOpacity(layerId), paper)
            post { onColor(argb) }
        }
    }

    /**
     * The drag-off-the-colour-pill eyedropper (JB-2.03a Decision 2), driven by the screen that owns the pill. Points are VIEW-LOCAL px of
     * this canvas. [dragEyedropEnd] with `take = false` (lifted back on the pill) changes nothing.
     */
    fun dragEyedropMove(x: Float, y: Float) {
        if (!eyedropping) startEyedrop(x, y, withCancelCircle = false) else eyedropMove(x, y)
    }

    fun dragEyedropEnd(take: Boolean) {
        if (eyedropping) eyedropEnd(take)
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
                engine.beginStroke(layerId, colorArgb ?: b.argb, b.opacity, b.accumulate,
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
        // The grain the file asked for, with its depth as evaluated at the first dab (JB-1.05c). The
        // hard-coded Brush has none.
        val grain = d?.strokeGrain ?: GrainMath.StrokeGrain(GrainMath.GrainUniforms.OFF, GrainMath.GrainUniforms.OFF)
        // JB-1.06: a smudge brush carries ONE colour from the layer under the tip (R47); how hard it presses is the
        // dab's own flow. Stamp brushes pass nothing.
        val smudge = if (p != null && p.engine == ENGINE_SMUDGE) SmudgeParams(p.smudge.pickup, p.smudge.load) else null
        val argb = colorArgb ?: b.argb
        onGl { engine.beginStroke(layerId, argb, opacity, accumulate,
            if (eraseBlend) StrokeBlend.ERASE else StrokeBlend.NORMAL, tip, grain, smudge) }
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
        onStrokeEnded?.invoke()
    }

    private fun cancelStroke() {
        drawing = false
        smoother = null; placer = null; tracker = null
        strokePreset = null; strokeDabber = null; scatterRng = null; strokeErase = false
        onGl { engine.cancelStroke() }
        onStrokeEnded?.invoke()
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
     * What [Scatter] gets to ask about one dab: its pressure and its tilt. A [Dab] does not carry
     * speed or distance, so those are NaN — which the curves read as "not available" and skip,
     * leaving a scatter curve on speed to contribute its base rather than a number invented here.
     * The tilt is NaN for a finger, and read the same way.
     */
    private fun dabInputsOf(dab: Dab) = DabInputs(
        pressure = dab.pressure,
        tilt = dab.tilt,
        speedPxPerS = Float.NaN,
        direction = Float.NaN,
        lean = Float.NaN,
        distancePx = Float.NaN,
        random = Float.NaN,
        strokeRandom = Float.NaN,
        barrel = Float.NaN,
    )

    // ── save and open (JB-0.08b) ─────────────────────────────────────────────

    /**
     * The whole canvas as a [JbContents], ready for `JbArchive.write`.
     *
     * The GL thread reads the tiles — a GPU texture cannot be read from any other thread — and
     * [onReady] arrives on the UI thread, so the caller may hand the result to a background writer
     * without touching the GL context again.
     *
     * It is ONE board, ONE paint layer, ONE cel, because that is all this engine holds (multi-layer
     * and ink arrive with 2.04/5.01). The layer's id is the engine's own, because that is where
     * the tiles live and where the next stroke will be laid down. Anything that cannot be written is
     * refused with a [JbArchiveException] on [onSnapshotFailed] — never skipped.
     */
    fun snapshot(onReady: (JbContents) -> Unit) {
        val w = viewW
        val h = viewH
        onGl {
            val made: JbContents? = try {
                readContents(w, h)
            } catch (e: Exception) {
                post { onSnapshotFailed?.invoke(e.message ?: e.javaClass.simpleName) }
                null
            }
            if (made != null) {
                val contents = made
                post { onReady(contents) }
            }
        }
    }

    /**
     * Puts [contents] on the canvas, replacing what is there, then calls [onDone] on the UI thread.
     *
     * REFUSES — by throwing [JbArchiveException] HERE, on the caller's thread, BEFORE any GL work is
     * queued, so a refused file leaves the drawing that was on screen exactly as it was. What is
     * refused is any file this engine cannot show in full: ink, several layers, several cels, an
     * animated layer, several boards, a board that is not a canvas, a paper texture, or a tile that
     * is not a tile. Opening the half that can be shown and dropping the rest is the one thing this
     * must never do.
     */
    fun load(contents: JbContents, onDone: () -> Unit) {
        val doc = contents.doc
        val refusal = refusalFor(contents)
        if (refusal != null) throw JbArchiveException(refusal)

        val docLayer = doc.layers[0]
        val visible = docLayer.visible
        val opacity = docLayer.opacity
        val blend = docLayer.blend
        val wanted = ArrayList<Pair<Long, ByteArray>>(contents.tiles.size)
        for (entry in contents.tiles) {
            // Iterating the map gives the ENTRY, so the tile's "tx_ty" is the entry key's third part.
            wanted.add(Pair(tileKeyOf(entry.key.third), entry.value))
        }
        val paper = paperArgbOf(doc.paper.color)
        val name = doc.name

        onGl {
            // resetDocument() empties EVERY layer, including the one this view draws into, and
            // beginStroke() refuses a layer that is not there. So it goes back before anything else.
            engine.resetDocument()
            engine.addLayer(layerId)
            engine.setLayerVisible(layerId, visible)
            engine.setLayerOpacity(layerId, opacity)
            engine.setLayerBlend(layerId, blend)   // JB-2.20b: the GPU composites all 27
            for (item in wanted) {
                engine.writeTile(layerId, item.first, item.second)
            }
            reportHistory()
            post { onDone() }
        }
        paperArgb = paper
        documentName = if (name.isBlank()) "Joy Brush" else name
    }

    /**
     * Why this file cannot be shown in full, in the person's words — or null, which means EVERY
     * check passed and not just the ones that happened to run.
     */
    private fun refusalFor(contents: JbContents): String? {
        val doc = contents.doc
        if (contents.strokes.isNotEmpty()) {
            return "this drawing has ink strokes in it, and this screen cannot show ink yet"
        }
        if (doc.layers.size != 1) {
            return "this drawing has ${doc.layers.size} layers, and this screen holds one"
        }
        val layer = doc.layers[0]
        if (layer.kind != LayerKind.PAINT) {
            return "layer \"${layer.id}\" is ${layer.kind}, and this screen only paints pixels"
        }
        if (layer.animatedIn != null) {
            return "layer \"${layer.id}\" is animated, and this screen cannot play animation yet"
        }
        if (layer.cels.size != 1) {
            return "layer \"${layer.id}\" has ${layer.cels.size} cels, and this screen holds one"
        }
        val cel = layer.cels[0]
        if (doc.boards.size != 1) {
            return "this drawing has ${doc.boards.size} boards, and this screen holds one"
        }
        val board = doc.boards[0]
        if (board.kind != BoardKind.CANVAS) {
            return "board \"${board.id}\" is ${board.kind}, and this screen shows a canvas board"
        }
        if (doc.paper.textureId != null) {
            return "this drawing has a paper texture, which this screen cannot show yet"
        }
        for (key in contents.tiles.keys) {
            if (key.first != layer.id || key.second != cel.id) {
                return "a tile is in cel \"${key.second}\" of layer \"${key.first}\", which this drawing does not have"
            }
            if (key.third !in cel.tiles) {
                return "the drawing lists tile \"${key.third}\", and this file does not have it"
            }
            val bytes = contents.tiles.getValue(key)
            if (bytes.size != TILE_BYTES) {
                return "tile \"${key.third}\" is ${bytes.size} bytes, and a paint tile is $TILE_BYTES"
            }
        }
        for (tile in cel.tiles) {
            if (Triple(layer.id, cel.id, tile) !in contents.tiles) {
                return "the drawing lists tile \"$tile\", and this file does not have it"
            }
        }
        return null
    }

    /** The GL-thread half of [snapshot]: every tile, read back, under one document. */
    private fun readContents(w: Int, h: Int): JbContents {
        // Read back, not guessed at: the tiles are the drawing, so the archive carries exactly the
        // bytes the engine is holding and nothing has to be rebuilt from stroke records to save.
        val tiles = LinkedHashMap<Triple<String, String, String>, ByteArray>()
        val listed = ArrayList<String>()
        for (key in engine.tileKeys(layerId)) {
            val bytes = engine.readTile(layerId, key)
            if (bytes == null || bytes.size != TILE_BYTES) {
                throw JbArchiveException("a tile of the drawing could not be read back from the GPU")
            }
            val name = DocOps.key(Tiles.tx(key), Tiles.ty(key))
            listed.add(name)
            tiles[Triple(layerId, CEL_ID, name)] = bytes
        }
        listed.sort()

        // The three ids the factory hands out, in the order it asks for them — board, layer, cel.
        // The layer's MUST be the engine's own layer id, because that is where the tiles are and
        // where the next stroke goes; if the factory ever stops agreeing, that is said out loud
        // rather than quietly writing tiles into a layer nothing draws on.
        val ids = ArrayDeque(listOf(BOARD_ID, layerId, CEL_ID))
        val base = DocOps.newDocument(DOC_ID, documentName, w, h) { ids.removeFirst() }
        val docLayer = base.layers[0]
        if (docLayer.id != layerId) {
            throw JbArchiveException("a new document's layer is called \"${docLayer.id}\", not \"$layerId\"")
        }
        val docCel = docLayer.cels[0]
        val doc = base.copy(
            paper = base.paper.copy(color = paperHex(paperArgb)),
            layers = listOf(
                docLayer.copy(
                    visible = engine.layerVisible(layerId),
                    opacity = engine.layerOpacity(layerId),
                    cels = listOf(docCel.copy(tiles = listed)),
                ),
            ),
        )
        return JbContents(doc = doc, tiles = tiles, strokes = emptyMap(), thumbnailPng = null)
    }

    /** `"3_-2"` → the engine's packed tile key. Signed, because the canvas has no edge. */
    private fun tileKeyOf(name: String): Long {
        val parts = name.split('_')
        val tx = if (parts.size == 2) parts[0].toIntOrNull() else null
        val ty = if (parts.size == 2) parts[1].toIntOrNull() else null
        if (tx == null || ty == null) throw JbArchiveException("\"$name\" is not a \"tx_ty\" tile key")
        return Tiles.key(tx, ty)
    }

    /** The paper setting as a document states it: `#RRGGBB`, the paper colour's own format. */
    private fun paperHex(argb: Int): String = String.format(Locale.US, "#%06X", 0xFFFFFF and argb)

    /**
     * A `#RRGGBB` paper colour as this view's ARGB. A malformed one is white rather than a crash:
     * `JbArchive.read` has already refused anything malformed by the time a file arrives here.
     */
    private fun paperArgbOf(hex: String): Int {
        val digits = hex.removePrefix("#")
        val value = if (digits.length == 6) digits.toLongOrNull(16) else null
        if (value == null) return whitePaper
        return (whitePaper.toLong() or value).toInt()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Also publishes [view] to the GL thread when called on the UI thread (see [viewSnapshot]). */
    override fun requestRender() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            viewSnapshot = floatArrayOf(view.zoom, view.rotation, view.panX, view.panY)
        }
        super.requestRender()
    }

    private fun onGl(block: () -> Unit) {
        queueEvent(block)
        requestRender()
    }

    /**
     * The JB-2.20b owner check, one tap (the hidden diagnostics panel has the button): draws a fixed
     * 27-layer test drawing (one blend mode per layer, over opaque and half-transparent backdrops)
     * through a SECOND, throwaway engine on this same GL context, reads the pixels back, and compares
     * them with the export renderer's picture of the same drawing ([BlendSelfCheck]). It never touches
     * the person's own drawing: that lives in [engine], which this does not use. [onResult] arrives on
     * the UI thread with one sentence.
     */
    fun runBlendCheck(onResult: (String) -> Unit) {
        onGl {
            val sentence = try {
                blendCheckOnGl()
            } catch (e: Exception) {
                "The blend check could not run: ${e.message ?: e.javaClass.simpleName}."
            }
            post { onResult(sentence) }
        }
    }

    private fun blendCheckOnGl(): String {
        val w = viewW
        val h = viewH
        if (w < BlendSelfCheck.WIDTH || h < BlendSelfCheck.HEIGHT) {
            return "The screen is too small for the blend check (it needs ${BlendSelfCheck.WIDTH} x ${BlendSelfCheck.HEIGHT})."
        }
        val scene = BlendSelfCheck.build()
        val modes = drawCheckScene(scene, w, h, forceComposite = false)
        modes.error?.let { return it }
        val report = BlendSelfCheck.compare(BlendSelfCheck.expected(scene), modes.pixels!!)

        // The second question: does the composite path give the same picture as the plain fast path when every
        // layer is NORMAL? The same drawing with every mode set to NORMAL, drawn once the plain way, and once
        // with an empty MULTIPLY layer on top (which sends the stack down the composite path and changes
        // nothing about the picture).
        val flat = BlendSelfCheck.Scene(
            scene.doc,
            scene.layers.map { BlendSelfCheck.CheckLayer(it.id, cc.joycreator.joybrush.core.doc.BlendMode.NORMAL, it.opacity, it.tiles) },
        )
        val plain = drawCheckScene(flat, w, h, forceComposite = false)
        val composited = drawCheckScene(flat, w, h, forceComposite = true)
        plain.error?.let { return it }
        composited.error?.let { return it }
        val pathDifference = BlendSelfCheck.worstDifference(plain.pixels!!, composited.pixels!!)
        val pathsAgree = pathDifference <= BlendSelfCheck.TOLERANCE

        return report.sentence() +
            if (pathsAgree) " The two drawing paths agree (worst difference $pathDifference out of 255)."
            else " BUT the fast path and the blend path draw ordinary layers differently (worst difference $pathDifference out of 255)."
    }

    private class CheckPicture(val pixels: ByteArray?, val error: String?)

    /**
     * Draws [scene] on a throwaway engine on this GL context and reads the top-left corner of the surface back,
     * PREMULTIPLIED, top row first. With [forceComposite] an empty MULTIPLY layer goes on top, which changes no
     * pixel but makes the engine composite the stack the shader way.
     */
    private fun drawCheckScene(scene: BlendSelfCheck.Scene, w: Int, h: Int, forceComposite: Boolean): CheckPicture {
        val check = GlPaintEngine()
        try {
            check.init()
            for (l in scene.layers) {
                check.addLayer(l.id)
                check.setLayerOpacity(l.id, l.opacity)
                check.setLayerBlend(l.id, l.blend)
                for ((key, bytes) in l.tiles) check.writeTile(l.id, key, bytes)
            }
            if (forceComposite) {
                check.addLayer("force-composite")
                check.setLayerBlend("force-composite", cc.joycreator.joybrush.core.doc.BlendMode.MULTIPLY)
            }
            // Document pixels land 1:1 in the top-left corner of the surface.
            val m = floatArrayOf(2f / w, 0f, 0f, 0f, -2f / h, 0f, -1f, 1f, 1f)
            check.draw(w, h, m, 0xFF808080.toInt())
            check.compositeError?.let {
                return CheckPicture(null, "This GPU would not build the blend shader, so blend modes are drawn as normal here: $it")
            }
            val buf = java.nio.ByteBuffer.allocateDirect(BlendSelfCheck.WIDTH * BlendSelfCheck.HEIGHT * 4)
                .order(java.nio.ByteOrder.nativeOrder())
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glReadPixels(0, h - BlendSelfCheck.HEIGHT, BlendSelfCheck.WIDTH, BlendSelfCheck.HEIGHT,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            val raw = ByteArray(buf.capacity())
            buf.rewind(); buf.get(raw)
            // glReadPixels gives the bottom row first; the export's picture has the top row first.
            val rowBytes = BlendSelfCheck.WIDTH * 4
            val out = ByteArray(raw.size)
            for (row in 0 until BlendSelfCheck.HEIGHT) {
                System.arraycopy(raw, (BlendSelfCheck.HEIGHT - 1 - row) * rowBytes, out, row * rowBytes, rowBytes)
            }
            return CheckPicture(out, null)
        } finally {
            check.release()
        }
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

        /** This screen holds exactly one drawing, so its document id is a constant. */
        private const val DOC_ID = "joy-brush"
        private const val BOARD_ID = "board-1"
        private const val CEL_ID = "cel-1"
    }
}
