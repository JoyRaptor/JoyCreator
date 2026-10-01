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
import cc.joycreator.joybrush.androidkit.gl.MASK_SUFFIX
import cc.joycreator.joybrush.androidkit.gl.maskStoreId
import cc.joycreator.joybrush.androidkit.gl.SmudgeParams
import cc.joycreator.joybrush.androidkit.input.MotionEventSamples
import cc.joycreator.joybrush.androidkit.io.JbArchiveException
import cc.joycreator.joybrush.androidkit.tools.EyedropState
import cc.joycreator.joybrush.androidkit.tools.Eyedropper
import cc.joycreator.joybrush.core.input.PenAction
import cc.joycreator.joybrush.core.input.PenButton
import cc.joycreator.joybrush.core.input.PenButtonMap
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.androidkit.io.CanvasSnapshot
import cc.joycreator.joybrush.androidkit.io.TILE_BYTES
import cc.joycreator.joybrush.core.brush.BrushDabber
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE
import cc.joycreator.joybrush.core.brush.ENGINE_TUFT
import cc.joycreator.joybrush.core.brush.TuftStroke
import cc.joycreator.joybrush.core.brush.DabInputs
import cc.joycreator.joybrush.core.brush.Scatter
import cc.joycreator.joybrush.core.brush.SplitMix
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.guide.Guide
import cc.joycreator.joybrush.core.guide.GuideSnapper
import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.layers.LayerBudget
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.layers.LayerState
import cc.joycreator.joybrush.core.render.LayerMask
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
     * Called when four fingers tap: JB-2.01's chrome hides or comes back.
     */
    var onToggleUi: (() -> Unit)? = null

    /** Called on the UI thread whenever a pan, zoom or turn moves the page (JB-2.01: the top icons re-read what is behind them). */
    var onViewMoved: (() -> Unit)? = null

    // ── guides (JB-2.12) ─────────────────────────────────────────────────────

    /**
     * The guides strokes are pulled onto (JB-2.12a's GuideSnapper), or empty for none. The screen sets it from its guide
     * settings — empty when snapping is off, even with the lines showing. Read at pen-down.
     */
    @Volatile var snapTo: List<Guide> = emptyList()

    /** Called on the UI thread when the stroke in progress locks onto a guide (true) and when it ends (false). */
    var onGuideLock: ((Boolean) -> Unit)? = null

    private var snapper: GuideSnapper? = null
    private var lockShown = false

    /** Outstanding [sampleScreen] requests: view px as x,y pairs, and who wants the answer. All answered after the next frame. */
    private val screenSamples = java.util.concurrent.ConcurrentLinkedQueue<Pair<FloatArray, (IntArray) -> Unit>>()

    /**
     * The colours actually SHOWN at [points] (view px, x,y pairs), read from the screen right after the next frame is drawn
     * and handed to [onColors] on the UI thread as opaque ARGB, one per point. This is what is on the glass — every layer,
     * the paper, and the grey outside the page — which is exactly what an icon over the picture has to be legible against.
     * Every request is answered (the top icons and the eyedropper both ask, and neither may starve the other).
     */
    fun sampleScreen(points: FloatArray, onColors: (IntArray) -> Unit) {
        screenSamples.add(Pair(points.copyOf(), onColors))
        requestRender()
    }

    /** GL thread, after the frame: answers every waiting [sampleScreen]. A point off the surface reads as black. */
    private fun answerScreenSample() {
        while (true) answerOne(screenSamples.poll() ?: return)
    }

    private fun answerOne(req: Pair<FloatArray, (IntArray) -> Unit>) {
        val pts = req.first
        val out = IntArray(pts.size / 2)
        val px = java.nio.ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        for (i in out.indices) {
            val x = pts[2 * i].toInt()
            val y = viewH - 1 - pts[2 * i + 1].toInt()
            if (x < 0 || y < 0 || x >= viewW || y >= viewH) { out[i] = OPAQUE_BLACK; continue }
            px.clear()
            GLES30.glReadPixels(x, y, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
            val r = px.get(0).toInt() and 0xFF
            val g = px.get(1).toInt() and 0xFF
            val b = px.get(2).toInt() and 0xFF
            out[i] = OPAQUE_BLACK or (r shl 16) or (g shl 8) or b
        }
        val answer = req.second
        post { answer(out) }
    }

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

    // Metadata belongs to the opened document, not the GPU's layer projection. Written on GL;
    // the UI reads the immutable document to respect locks before starting a stroke.
    // Metadata only after upload: GPU tiles own the live pixels.
    @Volatile private var retainedContents: JbContents? = null
    @Volatile private var contentLost = false
    @Volatile private var graphicsEpoch = 0

    /** UI-thread callback after GPU pixels are lost. The host restores a durable archive. */
    var onGraphicsLost: ((documentId: String) -> Unit)? = null
    val needsRecovery: Boolean get() = contentLost

    /** Stop the interrupted pen gesture without ever committing it to the replacement context. */
    fun cancelInterruptedStroke() { if (drawing) cancelStroke() }

    // ── layers (JB-2.04) ─────────────────────────────────────────────────────

    /** The layer the brush paints on. Written on the UI thread; read by the GL work a stroke queues. */
    @Volatile private var activeLayer = FIRST_LAYER

    /** The stack as the UI last heard it from the engine (UI thread only). Every change goes through [commitStack]. */
    private var stackUi = LayerStack.single(FIRST_LAYER, FIRST_LAYER_NAME)

    /** The page, in document px: the board a new drawing gets (the screen, the first time) or the one a file brings. */
    @Volatile private var pageW = 0
    @Volatile private var pageH = 0

    /** How many layers this device may hold ([LayerBudget]); the screen sets it from the phone's RAM. */
    var maxLayers = LayerBudget.MIN

    /** Called on the UI thread whenever the stack changes: a layer added, removed, moved, renamed, shown or hidden, an undo. */
    var onLayersChanged: ((LayerStack) -> Unit)? = null

    /** Called on the UI thread when something the person tried is refused, with the reason in words. */
    var onRefused: ((String) -> Unit)? = null

    val layers: LayerStack get() = stackUi
    val activeLayerId: String get() = activeLayer
    val pageWidth: Int get() = pageW
    val pageHeight: Int get() = pageH

    fun selectLayer(id: String) {
        val next = stackUi.select(id)
        if (next === stackUi) return
        editingMask = false
        stackUi = next
        activeLayer = next.activeId
        onLayersChanged?.invoke(next)
    }

    /** A new empty layer above the active one. False (and [onRefused]) when the device has no room for another. */
    fun addLayer(): Boolean {
        if (!roomForAnother()) return false
        val before = stackUi
        commitStack(before, before.add(before.freshId()))
        return true
    }

    fun duplicateLayer(id: String): Boolean {
        if (drawing || !roomForAnother()) return false
        val before = stackUi
        val newId = before.freshId()
        val after = before.duplicate(id, newId) ?: return false
        adopt(after)
        onGl { engine.duplicateLayerStep(id, newId, before, after); reportHistory() }
        return true
    }

    /** False for the last layer: a drawing always has somewhere to paint. */
    fun deleteLayer(id: String): Boolean {
        if (drawing) return false
        val before = stackUi
        val after = before.delete(id)
        if (after == null) {
            onRefused?.invoke("A drawing needs at least one layer, so the last one stays.")
            return false
        }
        adopt(after)
        onGl { engine.deleteLayerStep(id, before, after); reportHistory() }
        return true
    }

    fun moveLayer(id: String, toIndex: Int) {
        val before = stackUi
        val after = before.move(id, toIndex)
        if (after != before) commitStack(before, after)
    }

    fun renameLayer(id: String, name: String) {
        val before = stackUi
        val after = before.rename(id, name)
        if (after != before) commitStack(before, after)
    }

    fun setLayerBlend(id: String, mode: BlendMode) {
        val before = stackUi
        val after = before.withBlend(id, mode)
        if (after != before) commitStack(before, after)
    }

    /**
     * Opacity while a finger is on the slider: shown at once, NOT an undo step. [commitLayerOpacity] with the stack as it
     * was when the finger went down makes the whole drag ONE step.
     */
    fun previewLayerOpacity(id: String, opacity: Float) {
        stackUi = stackUi.withOpacity(id, opacity)
        val v = stackUi[id]?.opacity ?: return
        onGl { engine.setLayerOpacity(id, v) }
    }

    fun commitLayerOpacity(before: LayerStack, id: String, opacity: Float) {
        val after = before.withOpacity(id, opacity)
        if (after != before) commitStack(before, after) else onGl { engine.setLayerOpacity(id, opacity); reportHistory() }
    }

    // ── masks and clipping (JB-2.23) ─────────────────────────────────────────

    /**
     * The brush paints the ACTIVE layer's mask instead of its pixels. A state the person sets on purpose, by tapping the
     * mask (JB-2.23 Decision 9), never guessed from where the pen lands. Choosing another layer turns it off.
     */
    var editingMask = false
        private set

    fun setEditingMask(on: Boolean) {
        val next = on && stackUi.active.hasMask
        if (next == editingMask) return
        editingMask = next
        onLayersChanged?.invoke(stackUi)
    }

    /** An empty mask (it shows everything) on [id], as ONE undo step; the brush goes to it, ready to hide. */
    fun addMask(id: String) {
        val before = stackUi
        if (before[id]?.hasMask != false) return
        editingMask = id == before.activeId
        commitStack(before, before.withMask(id, true))
    }

    /** Throws the mask away as ONE undo step (its pixels go into the step: Undo brings it back). */
    fun deleteMask(id: String) {
        if (drawing) return
        val before = stackUi
        if (before[id]?.hasMask != true) return
        val after = before.withMask(id, false)
        if (id == before.activeId) editingMask = false
        adopt(after)
        onGl { engine.deleteMaskStep(id, before, after); reportHistory() }
    }

    /** Clipped to the layer below, or not, as ONE undo step. The bottom layer cannot be clipped, and says so. */
    fun setClip(id: String, on: Boolean) {
        val before = stackUi
        if (on && before.indexOf(id) == 0) {
            onRefused?.invoke("The bottom layer has nothing below it to clip to.")
            return
        }
        val after = before.withClip(id, on)
        if (after != before) commitStack(before, after)
    }

    /** Shown or hidden. Not an undo step: it changes no pixel (JB-2.04 Decision 7). It IS saved. */
    fun setLayerVisible(id: String, visible: Boolean) {
        stackUi = stackUi.withVisible(id, visible)
        onGl { engine.setLayerVisible(id, visible); reportHistory() }
    }

    /**
     * Small pictures of [ids], each [w] x [h] px of the whole page (ARGB, not premultiplied), rendered on the GL thread
     * and handed over on the UI thread. A layer that has gone by then is simply missing from the map.
     */
    fun layerThumbnails(ids: List<String>, w: Int, h: Int, onReady: (Map<String, IntArray>) -> Unit) {
        val pw = pageW
        val ph = pageH
        if (pw <= 0 || ph <= 0) return
        onGl {
            val out = HashMap<String, IntArray>()
            for (id in ids) engine.renderThumbnail(id, pw, ph, w, h)?.let { out[id] = it }
            post { onReady(out) }
        }
    }

    private fun roomForAnother(): Boolean {
        if (stackUi.size < maxLayers) return true
        onRefused?.invoke("This phone has room for $maxLayers layers, and this drawing has ${stackUi.size}.")
        return false
    }

    private fun adopt(next: LayerStack) {
        stackUi = next
        activeLayer = next.activeId
        if (!next.active.hasMask) editingMask = false
        onLayersChanged?.invoke(next)
    }

    /** A stack-only change as ONE undo step. Shown at once; the engine confirms through [reportHistory]. */
    private fun commitStack(before: LayerStack, after: LayerStack) {
        if (drawing) return
        adopt(after)
        onGl { engine.pushStackStep(before, after); reportHistory() }
    }
    @Volatile private var viewW = 1
    @Volatile private var viewH = 1

    /** The document's own name, so a file that is opened and saved again keeps the name it had. */
    @Volatile private var documentName = "Joy Brush"
    private val whitePaper = 0xFFFFFFFF.toInt()

    /** Every touch that is not drawing (JB-2.02). Owns pan, zoom, rotate and the tap gestures. */
    private val gestures = CanvasGestures(
        view,
        onViewChanged = {
            requestRender()
            onViewMoved?.invoke()
        },
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

    /** R9: a tuft brush's stroke — the sable brush's own state, one stroke long. Null for every other engine. */
    private var strokeTuft: TuftStroke? = null
    private var strokeSeed = 0L

    // queueEvent can run before EGL is current, both at startup and after a file picker.
    // Only renderer callbacks guarantee a current context AND surface. GL-thread only.
    private val frameWork = ArrayDeque<() -> Unit>()

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                if (engine.ready) graphicsEpoch += 1
                engine.init()
                // Never let a context-loss placeholder overwrite the person's last good file.
                contentLost = contentLost || engine.lostContent || retainedContents != null
                engine.addLayer(FIRST_LAYER)
                engine.setLayerName(FIRST_LAYER, FIRST_LAYER_NAME)
                post {
                    surfaceReady = true
                    if (contentLost) {
                        val recover = onGraphicsLost
                        if (recover != null) recover(retainedContents?.doc?.id ?: DOC_ID)
                        else onRefused?.invoke("The graphics restarted. Reopen your saved drawing before continuing; the empty canvas will not be saved.")
                    }
                    onReady?.invoke()
                }
            }
            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                // The view is NOT touched here: a new ViewTransform is already identity (the page
                // starts on its own top-left corner, unzoomed and upright), and later layouts —
                // rotation, a keyboard, a resumed Activity — must not make the page jump.
                viewW = width; viewH = height
                // A new drawing's page is the screen it was started on, once; a rotation must not resize it.
                if (pageW <= 0 || pageH <= 0) { pageW = width; pageH = height }
            }
            override fun onDrawFrame(gl: GL10?) {
                while (frameWork.isNotEmpty()) frameWork.removeFirst()()
                val s = viewSnapshot
                drawView.zoom = s[0]; drawView.rotation = s[1]; drawView.panX = s[2]; drawView.panY = s[3]
                engine.draw(viewW, viewH, drawView.docToClip(viewW, viewH), paperArgb)
                answerScreenSample()
            }
        })
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun undo() = onGl { engine.undoStep(); reportHistory() }
    fun redo() = onGl { engine.redoStep(); reportHistory() }
    /** Empties the layer being painted on, as one undo step. */
    fun clearCanvas() {
        val id = activeLayer
        if (contentLost || retainedContents?.doc?.layers?.any { it.id == id && it.locked } == true) {
            onRefused?.invoke("This drawing cannot be cleared while it is locked or awaiting recovery.")
            return
        }
        onGl { engine.clearLayer(id); reportHistory() }
    }

    // ── input ────────────────────────────────────────────────────────────────

    override fun onHoverEvent(event: MotionEvent): Boolean {
        onRawEvent?.invoke(event)
        reportPen(event)
        if (MotionEventSamples.tool(event, 0).let { it == Tool.STYLUS || it == Tool.ERASER }) {
            penSeen = true
            penHovering = event.actionMasked != MotionEvent.ACTION_HOVER_EXIT
        }
        return super.onHoverEvent(event)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        onRawEvent?.invoke(ev)
        reportPen(ev)
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

    /** The eyedropper is being driven by a finger: big ring, lifted off the fingertip (owner, 2026-09-30). */
    private var eyedropFinger = false
    private var fingerX = 0f
    private var fingerY = 0f

    /** The long-press being watched is a finger's, not a pen's. */
    private var holdIsFinger = false

    private val holdFired = Runnable {
        if (!drawing || !longPressEyedropper) return@Runnable
        // A hold that stayed still: the dot the pen made is thrown away (nothing is committed) and the ring comes up.
        cancelStroke()
        startEyedrop(holdX, holdY, withCancelCircle = true, finger = holdIsFinger)
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
        holdIsFinger = MotionEventSamples.tool(ev, index) == Tool.FINGER
        postDelayed(holdFired, Eyedropper.HOLD_MS)
    }

    private fun stopHoldWatch() {
        removeCallbacks(holdFired)
        holdPointer = -1
    }

    private fun startEyedrop(x: Float, y: Float, withCancelCircle: Boolean, finger: Boolean = false) {
        eyedropping = true
        eyedropFinger = finger
        eyedropOld = strokeColor
        eyedropNew = eyedropOld
        cancelX = x; cancelY = y
        cancelR = if (withCancelCircle) Eyedropper.CANCEL_CIRCLE_DP * resources.displayMetrics.density / 2f else 0f
        eyedropMove(x, y)
    }

    /** The touch is at ([x], [y]); the eyedropper samples there (a pen) or off the fingertip (a finger). */
    private fun eyedropMove(x: Float, y: Float) {
        fingerX = x; fingerY = y
        val (sx, sy) = Eyedropper.samplePoint(x, y, eyedropFinger, resources.displayMetrics.density)
        eyedropX = sx; eyedropY = sy
        publishEyedrop()
        val seq = ++sampleSeq
        sampleAt(sx, sy) { argb -> if (seq == sampleSeq && eyedropping) { eyedropNew = argb; publishEyedrop() } }
    }

    /** Cancel is where the TOUCH went back to, not where the lifted ring is. */
    private fun overCancel(): Boolean =
        cancelR > 0f && Eyedropper.insideCircle(fingerX, fingerY, cancelX, cancelY, cancelR)

    private fun publishEyedrop() {
        onEyedrop?.invoke(EyedropState(eyedropX, eyedropY, eyedropNew, eyedropOld, cancelX, cancelY, cancelR, overCancel(),
            eyedropFinger, fingerX, fingerY))
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
     * The colour a person SEES at a screen point (view-local px): read from the screen itself after the next frame, so it is
     * every visible layer at its opacity and blend over the paper (JB-2.04: one layer's pixel stopped being "what you see"
     * the day there were two). [onColor] arrives on the UI thread, opaque.
     */
    fun sampleAt(screenX: Float, screenY: Float, onColor: (Int) -> Unit) {
        sampleScreen(floatArrayOf(screenX, screenY)) { c -> onColor(c[0]) }
    }

    /**
     * The drag-off-the-colour-pill eyedropper (JB-2.03a Decision 2), driven by the screen that owns the pill. Points are VIEW-LOCAL px of
     * this canvas. [dragEyedropEnd] with `take = false` (lifted back on the pill) changes nothing.
     */
    fun dragEyedropMove(x: Float, y: Float, finger: Boolean = false) {
        if (!eyedropping) startEyedrop(x, y, withCancelCircle = false, finger = finger) else eyedropMove(x, y)
    }

    fun dragEyedropEnd(take: Boolean) {
        if (eyedropping) eyedropEnd(take)
    }

    /** True for the pen's two ends — a stylus and an eraser barrel, never a finger or a mouse. */
    private fun isPenAt(ev: MotionEvent, pointerIndex: Int): Boolean {
        val tool = MotionEventSamples.tool(ev, pointerIndex)
        return tool == Tool.STYLUS || tool == Tool.ERASER
    }

    /** The layer the stroke in progress paints on, fixed at pen-down: choosing another layer mid-stroke must not split it. */
    private var strokeLayerId = FIRST_LAYER

    /** The stroke in progress paints a mask, so its colour is a grey (JB-2.23). */
    private var strokeOnMask = false

    /** A colour as the grey a mask stores: its luminance (Rec. 709), so dark paint hides and light paint shows. */
    private fun maskGrey(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val y = (0.2126f * r + 0.7152f * g + 0.0722f * b + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (y shl 16) or (y shl 8) or y
    }

    private fun startStroke(eraser: Boolean) {
        if (contentLost) {
            drawing = false
            onRefused?.invoke("Reopen your saved drawing before continuing.")
            return
        }
        if (retainedContents?.doc?.layers?.any { it.id == activeLayer && it.locked } == true) {
            drawing = false
            onRefused?.invoke("\"${stackUi.active.name}\" is locked.")
            return
        }
        // A hidden layer is refused out loud: painting where the paint cannot be seen is how work gets lost (JB-2.04).
        if (!stackUi.active.visible) {
            drawing = false
            onRefused?.invoke("\"${stackUi.active.name}\" is hidden. Show it to paint on it.")
            return
        }
        val b = brush
        val p = preset
        val erase = b.erase || eraser
        // JB-2.23: on the mask, the stroke goes to the mask's store and paints GREY — white shows, black hides.
        strokeOnMask = editingMask && stackUi.active.hasMask
        strokeLayerId = if (strokeOnMask) maskStoreId(activeLayer) else activeLayer
        resetSnapper(fresh = true)
        drawing = true
        glBegan = false
        strokePreset = p
        strokeDabber = null
        scatterRng = null
        strokeTuft = null
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
            val target = strokeLayerId
            onGl {
                engine.beginStroke(target, (colorArgb ?: b.argb).let { if (strokeOnMask) maskGrey(it) else it }, b.opacity, b.accumulate,
                    if (erase) StrokeBlend.ERASE else StrokeBlend.NORMAL, b.tip)
            }
        } else if (p.engine == ENGINE_TUFT) {
            // R9: the tuft brush lays footprints, not dabs. Its speed is judged in screen px, so it is told the zoom.
            // Begun on the GL thread with its first footprints, like a file brush.
            strokeSeed = SystemClock.uptimeMillis()
            strokeTuft = TuftStroke(p, strokeSeed, screenPerDoc = view.zoom)
            placer = null
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
        // R9: a tuft stroke's whole-stroke shader numbers (streaks, tooth), from the file and this stroke's seed.
        val tuft = if (p != null && strokeTuft != null) TuftStroke.shading(p, strokeSeed) else null
        val argb = (colorArgb ?: b.argb).let { if (strokeOnMask) maskGrey(it) else it }
        val target = strokeLayerId
        onGl { engine.beginStroke(target, argb, opacity, accumulate,
            if (eraseBlend) StrokeBlend.ERASE else StrokeBlend.NORMAL, tip, grain, smudge, tuft) }
    }

    private fun feed(ev: MotionEvent) {
        val idx = ev.findPointerIndex(pointerId)
        if (idx < 0) return
        val sm = smoother ?: return
        val released = ArrayList<PenSample>()
        // The pen reports SCREEN px; a stroke is recorded in DOCUMENT px, and the page's own turn
        // is what the lean direction is read against.
        val samples = MotionEventSamples.from(ev, idx, { x, y -> view.screenToDoc(x, y) }, view.rotation)
        // JB-2.12: onto the guides first (position only; pressure and tilt pass through), then the brush's own response.
        val snap = snapper
        for (s in samples) feedOne(sm, snap?.map(s) ?: s, released)
        if (snap != null && snap.locked && !lockShown) {
            lockShown = true
            onGuideLock?.invoke(true)
        }
        paint(released)
    }

    /** A new stroke gets a new snapper (it remembers one stroke's start and lock); the old one's highlight goes. */
    private fun resetSnapper(fresh: Boolean) {
        val guides = snapTo
        snapper = if (fresh && guides.isNotEmpty()) GuideSnapper(guides, view.zoom) else null
        if (lockShown) {
            lockShown = false
            onGuideLock?.invoke(false)
        }
    }

    private fun finishStroke() {
        smoother?.let { paint(it.finish()) }
        resetSnapper(fresh = false)
        // R9: the lift — a fast one carries on as the bristles leave the paper — and any spatter it throws.
        strokeTuft?.let { t -> paintTuft(t.finish()) }
        drawing = false
        smoother = null; placer = null; tracker = null
        strokePreset = null; strokeDabber = null; scatterRng = null; strokeErase = false; strokeTuft = null
        onGl { engine.endStroke(); reportHistory() }
        onStrokeEnded?.invoke()
    }

    private fun cancelStroke() {
        resetSnapper(fresh = false)
        drawing = false
        smoother = null; placer = null; tracker = null
        strokePreset = null; strokeDabber = null; scatterRng = null; strokeErase = false; strokeTuft = null
        onGl { engine.cancelStroke() }
        onStrokeEnded?.invoke()
    }

    private fun paint(points: List<PenSample>) {
        if (points.isEmpty()) return
        strokeTuft?.let { t -> paintTuft(t.add(points)); return }
        val placed = placer?.add(points) ?: return
        if (placed.isEmpty()) return
        val p = strokePreset
        val dabs = if (p == null) placed else scattered(p, placed)
        if (!glBegan) beginStrokeNow()
        onGl { engine.addDabs(dabs) }
    }

    /**
     * Lays [strokes] with the brush in hand, as if drawn, and makes them ONE undo step (R9: the tuning sheet's "Test"). The
     * samples are SCREEN px of this view; they are put on the page where they appear. Ignored while a stroke is drawn.
     */
    fun drawStrokes(strokes: List<List<PenSample>>) {
        if (drawing || strokes.isEmpty()) return
        var mark = 0
        onGl { mark = engine.undo.undoDepth }
        for (samples in strokes) {
            startStroke(eraser = false)
            val sm = smoother ?: continue
            val released = ArrayList<PenSample>()
            for (s in samples) {
                val (dx, dy) = view.screenToDoc(s.x, s.y)
                feedOne(sm, s.copy(x = dx, y = dy), released)
            }
            paint(released)
            finishStroke()
        }
        onGl { engine.undo.mergeNewest(engine.undo.undoDepth - mark); reportHistory() }
    }

    /** Called on the UI thread once the GL surface exists and strokes can be laid (and again after a context loss). */
    var onReady: (() -> Unit)? = null

    /** True once [onReady] has fired: before that, a stroke queued for the GL thread would find no engine. */
    @Volatile var surfaceReady = false
        private set

    /**
     * A brush's live preview (owner, 2026-09-30): empties this canvas WITHOUT an undo step and lays [strokes] afresh. For a
     * small canvas used as a preview, never for the person's drawing. Does nothing before [onReady] or mid-stroke.
     */
    fun replaceWithStrokes(strokes: List<List<PenSample>>) {
        if (!surfaceReady || drawing) return
        // One fresh layer, and the brush on it (JB-2.04: strokes go to the active layer, so it must be the one re-added).
        val fresh = LayerStack.single(FIRST_LAYER, FIRST_LAYER_NAME)
        stackUi = fresh
        activeLayer = FIRST_LAYER
        onGl {
            engine.resetDocument()
            engine.setStack(fresh)
        }
        drawStrokes(strokes)
    }

    /**
     * One pen sample into the stroke: through the brush's response curves (how it hears the pen), then the smoother. A
     * pen that pressed or turned WITHOUT moving releases nothing from the smoother, so a tuft brush is told directly
     * ([TuftStroke.dwell]): pressing grows the blot, and turning the pen swings the belly round its point.
     */
    private fun feedOne(sm: StrokeSmoother, raw: PenSample, released: MutableList<PenSample>) {
        val s = strokePreset?.response?.apply(raw) ?: raw
        val before = released.size
        released.addAll(sm.add(s))
        val t = strokeTuft
        if (t != null && released.size == before && !s.predicted) {
            paint(released)
            released.clear()
            paintTuft(t.dwell(s))
        }
    }

    /**
     * What the pen reads right now, for the brush settings' pen dot (owner, 2026-09-30): pressure 0..1, tilt in radians
     * from upright, and the direction it leans on the SCREEN (radians, 0 = up, as Android's AXIS_ORIENTATION). Hovering
     * reports pressure 0. UI thread.
     */
    var onPenReading: ((pressure: Float, tilt: Float, orientation: Float) -> Unit)? = null

    private fun reportPen(ev: MotionEvent) {
        val l = onPenReading ?: return
        if (!isPenAt(ev, 0)) return
        val hovering = ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE || ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER
        val up = ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_HOVER_EXIT
        l(if (hovering || up) 0f else ev.getPressure(0), ev.getAxisValue(MotionEvent.AXIS_TILT, 0),
            ev.getAxisValue(MotionEvent.AXIS_ORIENTATION, 0))
    }

    /** R9: a tuft stroke's footprints to the GL thread, beginning the stroke there with the first of them. */
    private fun paintTuft(stamps: List<cc.joycreator.joybrush.core.paint.TuftStamp>) {
        if (stamps.isEmpty()) return
        if (!glBegan) beginStrokeNow()
        onGl { engine.addTuftStamps(stamps) }
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
     * It is one canvas board with static paint layers. The layer's id is the engine's own, because that is where
     * the tiles live and where the next stroke will be laid down. Anything that cannot be written is
     * refused with a [JbArchiveException] on [onSnapshotFailed] — never skipped.
     */
    fun snapshot(onReady: (JbContents) -> Unit) = snapshot(onReady) { why -> onSnapshotFailed?.invoke(why) }

    /** Failure belongs to this request, including a late response after a caller's timeout. */
    fun snapshot(onReady: (JbContents) -> Unit, onFailure: (String) -> Unit) {
        val w = viewW
        val h = viewH
        val epoch = graphicsEpoch
        onGl(allowLost = true) {
            val made: JbContents? = try {
                if (contentLost || epoch != graphicsEpoch) throw JbArchiveException("the graphics restarted; wait for drawing recovery")
                readContents(w, h)
            } catch (e: OutOfMemoryError) {
                post { onFailure("there is not enough memory to save this drawing; your previous save is kept") }
                null
            } catch (e: Exception) {
                post { onFailure(e.message ?: e.javaClass.simpleName) }
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
     * refused is any file this engine cannot show in full: ink, an animated layer, a layer with several
     * cels, several boards, a board that is not a canvas, a paper texture, or a tile that is not a tile.
     * Several PAINT layers are shown in full (JB-2.04). Opening the half that can be shown and dropping
     * the rest is the one thing this must never do.
     */
    fun load(contents: JbContents, onDone: () -> Unit) {
        val doc = contents.doc
        checkContents(contents)

        val states = doc.layers.map { LayerState(it.id, it.name, it.opacity, it.visible, it.blend, it.mask != null, it.clip) }
        val active = doc.activeLayerId?.takeIf { id -> doc.layers.any { it.id == id } } ?: doc.layers.last().id
        val stack = LayerStack(states, active)
        val wanted = ArrayList<Triple<String, Long, ByteArray>>(contents.tiles.size)
        for (entry in contents.tiles) {
            // Iterating the map gives the ENTRY: its key is (layer, cel, "tx_ty").
            // A mask's tiles go to the mask's store (JB-2.23); every other tile to its layer.
            val layer = doc.layers.first { it.id == entry.key.first }
            val store = if (layer.mask?.id == entry.key.second) maskStoreId(layer.id) else layer.id
            wanted.add(Triple(store, tileKeyOf(entry.key.third), entry.value))
        }
        val paper = paperArgbOf(doc.paper.color)
        val name = doc.name
        val board = doc.boards[0].rect

        // The UI's own picture of the stack changes now, so nothing drawn after this lands on a layer the file does not have.
        stackUi = stack
        activeLayer = active
        editingMask = false
        onGl(allowLost = true) {
            // resetDocument() empties EVERY layer; the file's stack is put back whole, then its pixels.
            engine.resetDocument()
            engine.setStack(stack)
            for (item in wanted) engine.writeTile(item.first, item.second, item.third)
            retainedContents = CanvasSnapshot.metadataOf(contents)
            contentLost = false
            paperArgb = paper
            reportHistory()
            post { onDone() }
        }
        pageW = board.w
        pageH = board.h
        documentName = name
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
        if (doc.layers.isEmpty()) return "this drawing has no layers"
        if (doc.boards.size != 1) {
            return "this drawing has ${doc.boards.size} boards, and this screen holds one"
        }
        val board = doc.boards[0]
        if (board.kind != BoardKind.CANVAS) {
            return "board \"${board.id}\" is ${board.kind}, and this screen shows a canvas board"
        }
        if (board.rect.w <= 0 || board.rect.h <= 0) return "board \"${board.id}\" has no room"
        if (doc.paper.textureId != null) {
            return "this drawing has a paper texture, which this screen cannot show yet"
        }
        val celOf = HashMap<String, String>()
        for (layer in doc.layers) {
            if (layer.id.endsWith(MASK_SUFFIX)) {
                return "layer \"${layer.name}\" uses a reserved mask identifier; this screen cannot open it safely"
            }
            if (layer.kind != LayerKind.PAINT) {
                return "layer \"${layer.name}\" is ${layer.kind}, and this screen only paints pixels"
            }
            if (layer.animatedIn != null) {
                return "layer \"${layer.name}\" is animated, and this screen cannot play animation yet"
            }
            if (layer.cels.size != 1) {
                return "layer \"${layer.name}\" has ${layer.cels.size} cels, and this screen holds one"
            }
            celOf[layer.id] = layer.cels[0].id
        }
        for (key in contents.tiles.keys) {
            val owner = doc.layers.firstOrNull { it.id == key.first }
            // JB-2.23: a tile of the layer's own cel, or of its mask.
            val stored = owner?.let { DocOps.storedCels(it) }?.firstOrNull { it.id == key.second }
            if (owner == null || stored == null) {
                return "a tile is in cel \"${key.second}\" of layer \"${key.first}\", which this drawing does not have"
            }
            val listed = stored.tiles
            if (key.third !in listed) {
                return "the drawing lists tile \"${key.third}\", and this file does not have it"
            }
            val bytes = contents.tiles.getValue(key)
            if (bytes.size != TILE_BYTES) {
                return "tile \"${key.third}\" is ${bytes.size} bytes, and a paint tile is $TILE_BYTES"
            }
        }
        for (layer in doc.layers) for (cel in DocOps.storedCels(layer)) {
            for (tile in cel.tiles) {
                if (Triple(layer.id, cel.id, tile) !in contents.tiles) {
                    return "the drawing lists tile \"$tile\", and this file does not have it"
                }
            }
        }
        return null
    }

    /** Check a proposed Open before preservation starts, without changing the current drawing. */
    fun checkContents(contents: JbContents) {
        refusalFor(contents)?.let { throw JbArchiveException(it) }
    }

    /** The GL-thread half of [snapshot]: every layer, every tile, read back, under one document. */
    private fun readContents(w: Int, h: Int): JbContents {
        // Read back, not guessed at: the tiles are the drawing, so the archive carries exactly the
        // bytes the engine is holding and nothing has to be rebuilt from stroke records to save.
        val stack = engine.stack(activeLayer)
        val tiles = LinkedHashMap<Triple<String, String, String>, ByteArray>()
        val listed = HashMap<String, List<String>>()
        val maskListed = HashMap<String, List<String>>()
        for (s in stack.layers) {
            val names = ArrayList<String>()
            for (key in engine.tileKeys(s.id)) {
                val bytes = engine.readTile(s.id, key)
                if (bytes == null || bytes.size != TILE_BYTES) {
                    throw JbArchiveException("a tile of layer \"${s.name}\" could not be read back from the GPU")
                }
                val name = DocOps.key(Tiles.tx(key), Tiles.ty(key))
                names.add(name)
                tiles[Triple(s.id, CEL_ID, name)] = bytes
            }
            names.sort()
            listed[s.id] = names
            // JB-2.23: the mask's tiles, under the mask's own cel.
            if (s.hasMask) {
                val maskNames = ArrayList<String>()
                val store = maskStoreId(s.id)
                for (key in engine.tileKeys(store)) {
                    val bytes = engine.readTile(store, key)
                    if (bytes == null || bytes.size != TILE_BYTES) {
                        throw JbArchiveException("a tile of the mask of layer \"${s.name}\" could not be read back from the GPU")
                    }
                    val name = DocOps.key(Tiles.tx(key), Tiles.ty(key))
                    maskNames.add(name)
                    tiles[Triple(s.id, LayerMask.MASK_CEL, name)] = bytes
                }
                maskNames.sort()
                maskListed[s.id] = maskNames
            }
        }

        // The factory makes the board, and a one-layer template every layer is written from. Each layer keeps the
        // engine's own id, because that is where its tiles are; a cel id only has to be unique within its layer.
        val pw = if (pageW > 0) pageW else w
        val ph = if (pageH > 0) pageH else h
        val ids = ArrayDeque(listOf(BOARD_ID, stack.layers[0].id, CEL_ID))
        val base = DocOps.newDocument(DOC_ID, documentName, pw, ph) { ids.removeFirst() }
        val template = base.layers[0]
        val templateCel = template.cels[0]
        val docLayers = stack.layers.map { s ->
            template.copy(
                id = s.id,
                name = s.name,
                visible = s.visible,
                opacity = s.opacity,
                blend = s.blend,
                cels = listOf(templateCel.copy(tiles = listed[s.id] ?: emptyList())),
                mask = if (s.hasMask) Cel(LayerMask.MASK_CEL, maskListed[s.id] ?: emptyList()) else null,
                clip = s.clip,
            )
        }
        val doc = base.copy(
            paper = base.paper.copy(color = paperHex(paperArgb)),
            layers = docLayers,
            activeLayerId = stack.activeId,
        )
        return CanvasSnapshot.merge(retainedContents, JbContents(doc = doc, tiles = tiles, strokes = emptyMap(), thumbnailPng = null))
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
        return (0xFF000000L or value).toInt()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Also publishes [view] to the GL thread when called on the UI thread (see [viewSnapshot]). */
    override fun requestRender() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            viewSnapshot = floatArrayOf(view.zoom, view.rotation, view.panX, view.panY)
        }
        super.requestRender()
    }

    private fun onGl(allowLost: Boolean = false, block: () -> Unit) {
        val epoch = graphicsEpoch
        queueEvent {
            frameWork.addLast { if (allowLost || (!contentLost && epoch == graphicsEpoch)) block() }
            // Wake from inside the event too: an earlier render request may already be consumed.
            super@JbCanvasView.requestRender()
        }
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
        val stack = engine.stack(activeLayer)
        post {
            // The engine is the truth after an undo or a redo: the UI takes its stack, and keeps its own brush layer if
            // that layer is still there.
            stackUi = stack
            activeLayer = stack.activeId
            onLayersChanged?.invoke(stack)
            onHistoryChanged?.invoke(u, r)
        }
    }

    companion object {
        /** Fingers are ignored for this long after the pen lifts (palm rejection). */
        const val PALM_GRACE_MS = 400L

        /** Opaque black, the alpha a screen sample is given (the screen has no transparency). */
        private const val OPAQUE_BLACK = -0x1000000

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

        /** A new drawing's one layer. */
        private const val FIRST_LAYER = "layer-1"
        private const val FIRST_LAYER_NAME = "Layer 1"
    }
}
