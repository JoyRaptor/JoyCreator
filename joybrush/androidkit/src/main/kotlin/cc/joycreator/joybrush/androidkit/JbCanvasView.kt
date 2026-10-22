package cc.joycreator.joybrush.androidkit

import android.content.Context
import cc.joycreator.joybrush.androidkit.gl.media.MediaCanvas
import cc.joycreator.joybrush.core.brush.BLEND_MULTIPLY
import cc.joycreator.joybrush.core.brush.BLEND_SCREEN
import cc.joycreator.joybrush.core.brush.BrushRules
import cc.joycreator.joybrush.core.brush.ENGINE_MEDIA
import cc.joycreator.joybrush.core.brush.MEDIUM_DRY
import cc.joycreator.joybrush.core.brush.MEDIUM_WET
import cc.joycreator.joybrush.core.media.MediaBelly
import cc.joycreator.joybrush.core.media.MediaInput
import cc.joycreator.joybrush.core.media.MediaSample
import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.io.PaperResources
import cc.joycreator.joybrush.core.paper.ResolvedPaper
import cc.joycreator.joybrush.core.paper.PaperState
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.RegionDocumentOps
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import cc.joycreator.joybrush.androidkit.diag.BlendSelfCheck
import cc.joycreator.joybrush.androidkit.gl.FillRequest
import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.gl.SpritePreviewRequest
import cc.joycreator.joybrush.androidkit.gl.MASK_SUFFIX
import cc.joycreator.joybrush.androidkit.gl.maskStoreId
import cc.joycreator.joybrush.androidkit.gl.SmudgeParams
import cc.joycreator.joybrush.androidkit.input.MotionEventSamples
import cc.joycreator.joybrush.androidkit.io.JbArchiveException
import cc.joycreator.joybrush.androidkit.tools.EyedropState
import cc.joycreator.joybrush.androidkit.tools.Eyedropper
import cc.joycreator.joybrush.androidkit.tools.FillPenRaster
import cc.joycreator.joybrush.core.select.foldInto
import cc.joycreator.joybrush.androidkit.tools.FillPenPreviewState
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.fill.MaskPaintMode
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.input.PenAction
import cc.joycreator.joybrush.core.input.PenButton
import cc.joycreator.joybrush.core.input.PenButtonMap
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.androidkit.io.BoardSnapshot
import cc.joycreator.joybrush.androidkit.io.CanvasSnapshot
import cc.joycreator.joybrush.androidkit.io.LineOpenPlan
import cc.joycreator.joybrush.androidkit.io.LineSaveMapping
import cc.joycreator.joybrush.androidkit.io.PlaneItems
import cc.joycreator.joybrush.androidkit.io.SlabBytes
import cc.joycreator.joybrush.androidkit.io.TILE_BYTES
import cc.joycreator.joybrush.androidkit.lines.LineEdit
import cc.joycreator.joybrush.androidkit.lines.LineAdmission
import cc.joycreator.joybrush.androidkit.lines.FrozenBrushCache
import cc.joycreator.joybrush.androidkit.lines.LineEditGate
import cc.joycreator.joybrush.androidkit.lines.LineEditResult
import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.androidkit.lines.LineSnapshot
import cc.joycreator.joybrush.core.brush.BrushDabber
import cc.joycreator.joybrush.core.brush.WatercolourDabber
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.watercolourRuntime
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushRegistry
import cc.joycreator.joybrush.core.brush.FrozenBrushResult
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
import cc.joycreator.joybrush.core.doc.hasPixels
import cc.joycreator.joybrush.core.media.MediaStores
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
import cc.joycreator.joybrush.core.stroke.StrokeCapture
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.UndoLog
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

    @Volatile private var documentPaperState = Paper()
    @Volatile var paper: ResolvedPaper = PaperState.resolve(documentPaperState, PaperResources.catalogue)
        set(v) {
            field = v
            documentPaperState = documentPaperState.copy(textureId=v.surface?.id,lookId=v.look?.id,
                textureScale=v.scale,color=paperHex(v.baseArgb),tint=if(v.tintSet)paperHex(v.baseArgb) else null,
                show=v.show,bite=v.bite,light=v.light,screenTransparent=v.screenTransparent)
            if (Looper.myLooper() == Looper.getMainLooper()) {
                val next = documentPaperState
                onGl { engine.setDocumentPaper(next) }
            }
            requestRender()
        }
    val documentPaper: Paper get() = documentPaperState
    var onPaperChanged: ((Paper) -> Unit)? = null
    var onBeforeHistoryChange: (() -> Unit)? = null
    @Volatile private var paperRevision = 0L
    @Volatile private var paperHistoryRevision = 0L
    private val pendingPaperVisits = ArrayDeque<Pair<Paper,Paper>>()

    /** Keep exact nullable defaults/export flags rather than round-tripping through resolved values. */
    fun applyDocumentPaper(value: Paper) {
        paperRevision++
        paper = PaperState.resolve(value, PaperResources.catalogue)
        documentPaperState = value
        onGl { engine.setDocumentPaper(value) }
        onPaperChanged?.invoke(value)
    }

    fun commitPaperVisit(before: Paper) {
        val after = documentPaperState
        if (before == after) return
        pendingPaperVisits.addLast(before to after)
        if (!drawing) flushPaperVisits()
    }

    private fun flushPaperVisits() {
        while (pendingPaperVisits.isNotEmpty()) {
            val (before,after) = pendingPaperVisits.removeFirst()
            onGl { engine.recordPaperChange(before,after); reportHistory() }
        }
    }

    var paperArgb: Int
        get() = paper.baseArgb
        set(v) { paper = paper.copy(baseArgb=v or 0xFF000000.toInt(),tintSet=true) }

    /** A missing catalogue id is reported but never prevents opening the drawing. */
    @Volatile var paperWarnings: List<String> = emptyList()
        private set

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

    /**
     * Opt-in UI-thread diagnostic source, never a document/history/save transaction. The observer
     * present at contact receives that contact's result. Null avoids freezing and raw buffering.
     * A refusal concerns capture only: the existing drawing tool continues normally.
     * Completion follows queuing endStroke, before any GPU commit confirmation: it is not save truth.
     */
    var onDiagnosticStrokeCapture: ((DiagnosticStrokeCaptureResult) -> Unit)? = null

    /** The values actually handed to beginStroke, after the live dabber evaluates its first dab. */
    data class DiagnosticStrokeStart(val colorArgb: Int, val opacity: Float, val hardness: Float)

    sealed class DiagnosticStrokeCaptureResult {
        /**
         * [physicalCelId] is the known document Cel.id, unknown on a fresh canvas. It is not the
         * engine's null-for-shared-plane address; no engine mapping, cel or sequence is allocated.
         */
        data class Captured(val layerId: String, val physicalCelId: String?, val blend: StrokeBlend,
                            val brush: FrozenBrushResult, val record: StrokeRecord,
                            val start: DiagnosticStrokeStart) : DiagnosticStrokeCaptureResult()
        data class Refused(val reason: String) : DiagnosticStrokeCaptureResult()
    }

    private class DiagnosticContact(val observer: (DiagnosticStrokeCaptureResult) -> Unit,
                                    val layerId: String, val physicalCelId: String?, val blend: StrokeBlend,
                                    val brush: FrozenBrushResult, val capture: StrokeCapture) {
        var sampleCount = 0
        var start: DiagnosticStrokeStart? = null
    }
    private var diagnosticContact: DiagnosticContact? = null
    private var diagnosticSerial = 0L

    private fun refuseDiagnosticCapture(reason: String) {
        val contact = diagnosticContact ?: return
        diagnosticContact = null
        contact.capture.cancel()
        contact.observer(DiagnosticStrokeCaptureResult.Refused(reason))
    }

    private fun finishDiagnosticCapture(): Pair<(DiagnosticStrokeCaptureResult) -> Unit, DiagnosticStrokeCaptureResult>? {
        val contact = diagnosticContact ?: return null
        diagnosticContact = null
        if (strokeWatercolour?.capped == true) {
            contact.capture.cancel()
            return contact.observer to DiagnosticStrokeCaptureResult.Refused("Diagnostic capture cannot replay a capped watercolor stroke")
        }
        val record = contact.capture.finish() ?: return null
        val start = contact.start ?: return contact.observer to DiagnosticStrokeCaptureResult.Refused(
            "Diagnostic contact did not produce live dabs")
        return contact.observer to DiagnosticStrokeCaptureResult.Captured(contact.layerId, contact.physicalCelId,
            contact.blend, contact.brush, record.copy(colorArgb=start.colorArgb), start)
    }

    private fun diagnosticCaptureRefusal(p: BrushPreset?, erase: Boolean, amount: Float): String? {
        val layer = boardDocument?.layers?.firstOrNull { it.id == activeLayer }
        return when {
            p == null -> "Diagnostic capture needs a brush preset; the built-in round brush is unsupported"
            editingMask -> "Diagnostic capture does not support masks"
            p.engine !in setOf("stamp", ENGINE_WATERCOLOUR) -> "Diagnostic capture does not support the ${p.engine} engine"
            p.line != null -> "Diagnostic capture does not support line shaping"
            p.engine == ENGINE_WATERCOLOUR && (erase || p.blend == "erase") -> "Diagnostic capture does not support watercolor erasers"
            p.blend !in setOf("normal", "erase", BLEND_MULTIPLY, BLEND_SCREEN) -> "Diagnostic capture does not support the ${p.blend} blend"
            p.tip.source != "procedural" || (p.tipTexture.enabled && p.tipTexture.source == "image") ||
                (p.paperGrain.enabled && p.paperGrain.source == "image") -> "Diagnostic capture does not freeze image brush assets"
            tileBoardId != null -> "Diagnostic capture does not support tiling"
            layer != null && layer.kind != LayerKind.PAINT -> "Diagnostic capture needs a paint layer"
            layer?.animatedIn != null || layer?.regions?.isNotEmpty() == true -> "Diagnostic capture does not resolve region or animated cel ownership"
            layer?.cels?.any { it.floatTiles.isNotEmpty() } == true -> "Diagnostic capture does not support a layer with media payload"
            layer != null && (layer.cels.singleOrNull() == null ||
                layer.cels.singleOrNull()?.id != (layer.sharedCelId ?: layer.cels.singleOrNull()?.id)) ->
                "Diagnostic capture cannot resolve the physical shared cel"
            !amount.isFinite() || amount !in 0f..1f || !view.zoom.isFinite() || view.zoom <= 0f ->
                "Diagnostic capture needs valid live smoothing and zoom"
            else -> null
        }
    }

    /** The fill pen's transient shape. Screen owns its transparent overlay; never saved. */
    var onFillPreview: ((FillPenPreviewState?) -> Unit)? = null
    private val fillSamples = ArrayList<PenSample>()
    private val fillPoints = ArrayList<Pt>()
    private var fillSerial = 0L
    private var fillSmoothing = 0f
    private var fillZoom = 1f
    private var fillArgb = 0
    private var fillOpacity = 1f
    private var fillPath = 0.0

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

    private val engine = GlPaintEngine().also {
        // Undo letting its oldest steps go over the memory budget is said once per drawing, never done silently.
        it.undo.onFirstTrim = { kept -> post { onNotice?.invoke(UndoLog.trimmedSentence(kept)) } }
        // A stroke meant as an editable line that had to stay pixels (v1.2 H12), said once for that stroke.
        it.onLineNotice = { said -> post { onNotice?.invoke(said) } }
    }

    /**
     * Pencil, watercolour and oil (MEDIA_ENGINE_PLAN step 4c), on the GL thread beside [engine]. It asks the phone how much
     * memory is free before it makes its window (the Lead's rule: refuse in words, never fail mid-stroke).
     */
    private val mediaCanvas = MediaCanvas(engine, memory = {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.availMem to info.threshold
    })

    /** The brush drawer's previews, drawn on a throwaway engine of this GL context ([renderBrushPreview]). GL thread only. */
    private val brushPreviews = cc.joycreator.joybrush.androidkit.preview.BrushPreviewRenderer.forContext(context)

    /** Debug A/B only: the media window's pre-2026-10-10 placement and write-back (see MediaCanvas.legacyWindow). */
    var mediaLegacyWindow: Boolean
        get() = mediaCanvas.legacyWindow
        set(v) { mediaCanvas.legacyWindow = v }

    /** Debug comparison only: the water's coarser time step (see MediaCanvas.wetQuality); null = shipped. */
    var mediaWetQuality: cc.joycreator.joybrush.core.media.WetConstants?
        get() = mediaCanvas.wetQuality
        set(v) { mediaCanvas.wetQuality = v }

    /** Debug A/B only: new media engines draw through the media atlas (MediaAtlasGl.enabled; off until it ships). */
    var mediaAtlasEnabled: Boolean
        get() = cc.joycreator.joybrush.androidkit.gl.media.MediaAtlasGl.enabled
        set(v) { cc.joycreator.joybrush.androidkit.gl.media.MediaAtlasGl.enabled = v }

    /**
     * The owner's paint-engine switch (the ⋯ menu): realistic paint through the media atlas (the whole layer, no window
     * edge) or the old 1024 px window. Takes effect at once: the engine in use writes back and is let go.
     */
    fun setMediaAtlas(on: Boolean) {
        if (mediaAtlasEnabled == on) return
        mediaAtlasEnabled = on
        onGl { mediaCanvas.releaseEngine() }
    }

    /** Water jobs in the atlas (MediaCanvas.waterJobs; on): the bench's A/B. Any thread. */
    var mediaWaterJobs: Boolean
        get() = mediaCanvas.waterJobs
        set(v) { mediaCanvas.waterJobs = v }

    /** The owner's watercolour comparison (core WaterAB; the default is today's water). Any thread. */
    var mediaWaterAB: cc.joycreator.joybrush.core.media.WaterAB
        get() = mediaCanvas.waterAB
        set(v) { mediaCanvas.waterAB = v }

    /** The paper's downhill slope for pencil, watercolour and oil, document space ([0, 0] = flat). Main thread. */
    fun setMediaSlope(x: Float, y: Float) {
        onGl { mediaCanvas.slope = floatArrayOf(x, y) }
    }

    /** Frame timings for the debug stroke replay (bench/StrokeReplay); records nothing until started. */
    val frameStats = cc.joycreator.joybrush.androidkit.bench.FrameStats().also { mediaCanvas.stats = it }

    // Metadata belongs to the opened document, not the GPU's layer projection. Written on GL;
    // the UI reads the immutable document to respect locks before starting a stroke.
    // Metadata only after upload: GPU tiles own the live pixels.
    @Volatile private var retainedContents: JbContents? = null
    @Volatile private var contentLost = false
    @Volatile private var graphicsEpoch = 0

    /** UI-thread callback after GPU pixels are lost. The host restores a durable archive. */
    var onGraphicsLost: ((documentId: String) -> Unit)? = null
    val needsRecovery: Boolean get() = contentLost
    @Volatile var tileBoardId: String? = null
        private set
    var onTileBoardChanged: ((String?) -> Unit)? = null
    fun setTileBoard(id: String?,ready: (String?) -> Unit = {}) {
        if(drawing) finishStroke()
        eyedropEnd(take=false)
        onGl {
            try { engine.setTileBoard(id) }
            catch(e: Exception) { post { onRefused?.invoke(e.message ?: "Tiling could not be started") } }
            val actual=engine.tileBoardId
            post { tileBoardId=actual; onTileBoardChanged?.invoke(actual); ready(actual) }
        }
    }
    /**
     * Live resize of the armed tile board: [rect] is shown as the repeating tile until null clears it. Queued on
     * the GL thread behind any edit already queued, so clearing right after a commit lands after it and the
     * screen goes straight from the dragged rectangle to the committed one with no frame of the old size.
     */
    fun setTilePreviewRect(id: String,rect: RectPx?) {
        onGl { engine.setTilePreviewRect(id,rect) }
    }
    fun setOnionBoard(id: String?) {
        onGl { try { engine.setOnionBoard(id) } catch(e: Exception) { post { onRefused?.invoke(e.message ?: "Onion preview could not be started") } } }
    }
    /**
     * A tool that cannot wrap round an armed tile board says so EVERY time it is tried (once per stroke, which is when
     * this runs). It used to say it once per arming and then do nothing at all, which read as a broken brush (owner's
     * report: "brushes not working on all the boards", LEAD_PLAN F6).
     */
    private fun refuseTileTool(tool: String): Boolean {
        if(tileBoardId == null) return false
        onRefused?.invoke("Turn tiling off to use $tool")
        return true
    }
    private fun boardStrokeWork(block: () -> Unit) {
        onGl {
            try { block() }
            catch(e: Exception) {
                noteBenchError("stroke", e)
                engine.cancelStroke()
                post { if(drawing) cancelStroke(); onRefused?.invoke(e.message ?: "That stroke could not be painted") }
            }
        }
    }

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

    /** Called on the UI thread with a calm sentence about something the app did on its own, which is not a refusal. */
    var onNotice: ((String) -> Unit)? = null

    val layers: LayerStack get() = stackUi
    val activeLayerId: String get() = activeLayer
    val pageWidth: Int get() = pageW
    val pageHeight: Int get() = pageH

    // ── editable lines (tasks/joybrush/specs/NATIVE_LINES_V1_20261016.md) ────

    /**
     * The "Editable lines" switch (the app keeps it). On: a stroke whose brush the engine can replay is kept as an
     * editable line. A plane that already holds lines keeps every replayable stroke as a line whatever this says, and
     * refuses the others in words: its pixels must stay what its lines draw.
     */
    @Volatile var editableLines: Boolean = false

    /** The engine's lines as of its last change (UI thread). */
    var lineSnapshot: LineSnapshot = LineSnapshot.EMPTY
        private set

    /** UI thread, after [lineSnapshot] changed: a line drawn, an edit, an undo or redo, a drawing opened. */
    var onLinesChanged: (() -> Unit)? = null

    /** The planes a selection on the active layer covers: its shared cel and each board's current frame. */
    fun activeLinePlanes(): List<LinePlane> = if (layerLocked(activeLayer)) emptyList() else linePlanesOf(activeLayer)

    /** Every visible, unlocked layer's planes, bottom to top (the All layers chip). A locked layer's lines still show. */
    fun visibleLinePlanes(): List<LinePlane> = stackUi.layers.filter { it.visible && !layerLocked(it.id) }.flatMap { linePlanesOf(it.id) }

    /** Is [id] locked in the drawing (v1.2 H9: Select neither picks nor changes its lines)? */
    private fun layerLocked(id: String): Boolean = stackUi[id]?.locked == true

    /**
     * Lock or unlock [id] (LAYER_OPS L1), ONE undo step, saved with the drawing. A locked layer's pixels, lines and mask
     * can't change (each attempt is refused in words); its opacity, blend and visibility still can.
     */
    fun setLayerLocked(id: String, locked: Boolean) {
        if (penIsDown()) return
        val before = stackUi
        val after = before.withLocked(id, locked)
        if (after != before) commitStack(before, after)
    }

    /** The sentence for a change a locked layer refuses. */
    private fun lockedSentence(id: String): String = "\"${stackUi[id]?.name ?: "This layer"}\" is locked. Unlock it from its menu to change it."

    /**
     * Bumped by every open (v1.2 H10): an edit handed in before a load is about another drawing, and is answered
     * "The drawing changed" rather than applied. Select clears its in-flight edits when this moves.
     */
    @Volatile var lineEditEpoch = 0
        private set

    // Cached per board document (immutable, replaced whenever it changes): hover asks for these on every move.
    private var linePlanesDoc: JbDocument? = null
    private val linePlanesCache = HashMap<String, List<LinePlane>>()

    private fun linePlanesOf(layerId: String): List<LinePlane> {
        val doc = boardDocument ?: return listOf(LinePlane(layerId, null))
        if (doc !== linePlanesDoc) { linePlanesCache.clear(); linePlanesDoc = doc }
        return linePlanesCache.getOrPut(layerId) { computeLinePlanes(doc, layerId) }
    }

    private fun computeLinePlanes(doc: JbDocument, layerId: String): List<LinePlane> {
        val meta = doc.layers.firstOrNull { it.id == layerId } ?: return emptyList()
        val shared = meta.sharedCelId ?: meta.cels.singleOrNull()?.id ?: return emptyList()
        val frames = try { RegionDocumentOps.paintPlans(doc, emptyMap())[layerId]?.frames.orEmpty() } catch (e: Exception) { emptyList() }
        return listOf(LinePlane(layerId, shared)) + frames.map { LinePlane(layerId, it.celId) }
    }

    /** Runs [edit] on the GL thread as ONE undo step; [done] runs on the UI thread with what happened. */
    fun submitLineEdit(edit: LineEdit, done: (LineEditResult) -> Unit) {
        if (drawing) { done(LineEditResult(false, "Lift the pen before editing lines")); return }
        // H9: a locked or hidden layer's lines can't be changed (they may still show), said in words.
        for (layerId in edit.layerIds()) {
            val layer = stackUi.layers.firstOrNull { it.id == layerId } ?: continue
            LineEditGate.layerRefusal(layer.name, layerLocked(layerId), layer.visible)?.let { done(LineEditResult(false, it)); return }
        }
        // H10: every edit is answered. GL work that would be dropped (the graphics restarted, the drawing was reloaded)
        // runs anyway and says so instead of leaving Select waiting.
        val epoch = lineEditEpoch
        val graphics = graphicsEpoch
        onGl(allowLost = true) {
            val stale = LineEditGate.stale(epoch, lineEditEpoch, graphics, graphicsEpoch, contentLost)
            val result = if (stale != null) LineEditResult(false, stale) else try {
                engine.editLines(edit)
                LineEditResult(true)
            } catch (e: Exception) {
                LineEditResult(false, e.message ?: "Those lines could not be changed")
            }
            if (result.ok) reportHistory()
            if (stale == null) publishLines()
            post { done(result) }
        }
    }

    /** A pen contact being kept as an editable line: its raw recording and any brush copies frozen for it. */
    private class LineContact(val capture: StrokeCapture, val brushes: Map<String, BrushPreset>) {
        var samples = 0
        var startArgb: Int? = null
    }
    private var lineContact: LineContact? = null

    /**
     * The id of the document this canvas makes when it has none from a file: unique per drawing (a new library drawing,
     * a fresh bench canvas), kept for every later snapshot of it. Audit 2026-10-18 P1: a shared constant let separate
     * drawings overwrite one folder copy.
     */
    private var freshDocId: String = newDocumentId()

    private fun newDocumentId(): String = "jb-" + java.util.UUID.randomUUID()

    /** H8: pen-down freezes the brush against the drawing's kept brushes once per (brush set, brush), not every stroke. */
    private val frozenBrushes = FrozenBrushCache()

    private fun newLineName(prefix: String): String =
        prefix + "-" + java.lang.Long.toHexString(java.util.UUID.randomUUID().mostSignificantBits and 0xFFFFFFFFFFFFL)

    /** Why this stroke can't be kept as an editable line, as the end of a sentence; null = it can. */
    private fun lineRefusal(p: BrushPreset?, erase: Boolean, amount: Float): String? {
        val layer = boardDocument?.layers?.firstOrNull { it.id == activeLayer }
        return LineAdmission.lineRefusal(p, erase, amount, view.zoom, tileBoardId != null,
            layer?.cels?.any { it.floatTiles.isNotEmpty() } == true)
    }

    // Select's live drag (NATIVE_LINES_V1 v1.1): the lifted lines leave their plane's tiles and follow the box through a
    // homography until put back. Never history; the engine puts the tiles back before anything reads or edits them.
    /** Lift [ids] for a live preview; false when nothing could be lifted (the box then drags without one). UI thread. */
    fun beginLineLift(ids: Set<cc.joycreator.joybrush.core.stroke.LineAddress>): Boolean {
        if (drawing || ids.isEmpty()) return false
        onGl {
            try { engine.beginLift(ids) }
            catch (e: Exception) { android.util.Log.w("JoyBrushLines", "lift refused: ${e.message}") }
        }
        return true
    }
    /** The lifted lines' document → document homography (row-major 3×3), null = where they were. UI thread. */
    fun setLineLiftTransform(rowMajor: DoubleArray?) { val m = rowMajor?.copyOf(); onGl { engine.setLiftTransform(m) } }
    /** Re-draw the lifted lines from [records] (a live recolour or weight scrub), no history. UI thread. */
    fun previewLineLift(records: List<cc.joycreator.joybrush.core.stroke.AddressedStroke>) {
        onGl { try { engine.previewLift(records) } catch (e: Exception) { android.util.Log.w("JoyBrushLines", "lift preview: ${e.message}") } }
    }
    /** Put the lifted lines back where they were (no history). UI thread. */
    fun endLineLift() { onGl { engine.endLift() } }

    /** GL thread: hands the UI a fresh copy of the engine's lines when they changed. */
    private fun publishLines() {
        val snapshot = engine.lineSnapshot()
        if (snapshot.revision == publishedLineRevision) return
        publishedLineRevision = snapshot.revision
        post { lineSnapshot = snapshot; onLinesChanged?.invoke() }
    }
    private var publishedLineRevision = 0L

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
        if (penIsDown() || !roomForAnother()) return false
        val before = stackUi
        commitStack(before, before.add(before.freshId()))
        return true
    }

    fun duplicateLayer(id: String): Boolean {
        if (penIsDown() || !roomForAnother()) return false
        val before = stackUi
        val newId = before.freshId()
        val after = before.duplicate(id, newId) ?: return false
        adopt(after)
        onGl { engine.duplicateLayerStep(id, newId, before, after); reportHistory() }
        return true
    }

    /** False for the last layer: a drawing always has somewhere to paint. */
    fun deleteLayer(id: String): Boolean {
        if (penIsDown()) return false
        val before = stackUi
        val after = before.delete(id)
        if (after == null) {
            onRefused?.invoke("A drawing needs at least one layer, so the last one stays.")
            return false
        }
        adopt(after)
        onGl { engine.deleteLayerStep(id, before, after); reportHistory() }
        tellIfUnclipped(before, after)
        return true
    }

    /**
     * Merge Down (LAYER_OPS L2): [id] into the layer right under it, ONE undo step. The plan decides on the GL thread
     * (what is there, what can stay editable); a refusal or the one sentence about lines that became paint is said.
     */
    fun mergeDown(id: String, animationBoardId: String? = null) {
        if (penIsDown()) return
        val before = stackUi
        val upper = before[id] ?: return
        val lower = before.layers.getOrNull(before.indexOf(id) - 1)
        // On an animation board, only the frame being worked on merges (owner, 2026-10-18).
        val frameBoard = animationBoardId?.takeIf { b -> boardDocument?.boards?.any { it.id == b && it.kind == BoardKind.ANIMATION } == true }
        onBeforeHistoryChange?.invoke()
        onGl {
            // The realistic-paint window writes its live state back and lets go first, so the merge reads current paint
            // and nothing stale can flush into the merged layer later (audit 2026-10-18).
            mediaCanvas.releaseWindow()
            val plan = try {
                cc.joycreator.joybrush.androidkit.layers.MergeDownPlan.decide(engine.mergeSide(upper, frameBoard), lower?.let { engine.mergeSide(it, frameBoard) })
            } catch (e: Exception) { cc.joycreator.joybrush.androidkit.layers.MergeDownPlan.Result.Refused(e.message ?: "Those layers could not be merged") }
            when (plan) {
                is cc.joycreator.joybrush.androidkit.layers.MergeDownPlan.Result.Refused -> post { onRefused?.invoke(plan.sentence) }
                is cc.joycreator.joybrush.androidkit.layers.MergeDownPlan.Result.Merge -> try {
                    val after = if (frameBoard != null) before else before.delete(id)!!.let { it.copy(activeId = lower!!.id) }
                    val media = engine.mergeDownStep(id, lower!!.id, plan.way, plan.sheetOp, plan.flattenLower, before, after, frameBoard)
                    reportHistory(); publishLines()
                    post {
                        if (frameBoard == null) adopt(after)
                        listOfNotNull(plan.said ?: if (frameBoard != null) "This frame of \"${upper.name}\" merged down; its other frames stay." else null, media)
                            .joinToString(" ").ifEmpty { null }?.let { onNotice?.invoke(it) }
                    }
                } catch (e: Exception) {
                    post { onRefused?.invoke(e.message ?: "Those layers could not be merged") }
                }
            }
        }
    }

    /** Freeze layer (LAYER_OPS §3): [id]'s editable lines and realistic paint become plain pixels, ONE undo step. */
    fun freezeLayer(id: String) {
        if (penIsDown()) return
        if (layerLocked(id)) { onRefused?.invoke(lockedSentence(id)); return }
        val name = stackUi[id]?.name ?: "This layer"
        onBeforeHistoryChange?.invoke()
        onGl {
            mediaCanvas.releaseWindow()   // its live state written back first; nothing stale flushes into the frozen layer later
            val done = try { engine.freezeLayerStep(id) } catch (e: Exception) { post { onRefused?.invoke(e.message ?: "\"$name\" could not be frozen") }; return@onGl }
            if (done) { reportHistory(); publishLines(); post { onNotice?.invoke("\"$name\" is plain pixels now. Undo brings its lines and paint back.") } }
            else post { onNotice?.invoke("\"$name\" is already plain pixels.") }
        }
    }

    /** Are any layers hidden? Flatten asks what to do with them (keep, or discard), unless the person chose to be remembered. */
    val hasHiddenLayers: Boolean get() = stackUi.layers.any { !it.visible }

    /**
     * Flatten (LAYER_OPS L6): every visible layer frozen into one layer of plain pixels, the picture unchanged, ONE undo
     * step; [discardHidden] drops hidden layers in the same step. Lines, slabs and pencil/watercolour/oil state are baked.
     */
    fun flatten(discardHidden: Boolean) {
        if (penIsDown()) return
        val before = stackUi
        onBeforeHistoryChange?.invoke()
        onGl {
            mediaCanvas.releaseWindow()   // its live state written back first; nothing stale flushes into the result later
            val plan = try {
                cc.joycreator.joybrush.androidkit.layers.FlattenPlan.decide(before, discardHidden) { id ->
                    cc.joycreator.joybrush.androidkit.layers.FlattenPlan.Facts(hasFrames = before[id]?.let { engine.mergeSide(it).hasFrames } == true)
                }
            } catch (e: Exception) { cc.joycreator.joybrush.androidkit.layers.FlattenPlan.Result.Refused(e.message ?: "The layers could not be flattened") }
            when (plan) {
                is cc.joycreator.joybrush.androidkit.layers.FlattenPlan.Result.Refused -> post { onRefused?.invoke(plan.sentence) }
                is cc.joycreator.joybrush.androidkit.layers.FlattenPlan.Result.Flatten -> try {
                    engine.flattenStep(plan.targetId, plan.merged, plan.discarded, before, plan.after)
                    reportHistory(); publishLines()
                    val name = before[plan.targetId]?.name ?: "one layer"
                    post { adopt(plan.after); onNotice?.invoke("Flattened into \"$name\". Undo brings the layers back.") }
                } catch (e: Exception) {
                    post { onRefused?.invoke(e.message ?: "The layers could not be flattened") }
                }
            }
        }
    }

    fun moveLayer(id: String, toIndex: Int) {
        val before = stackUi
        val after = before.move(id, toIndex)
        if (after != before) { commitStack(before, after); if (!drawing) tellIfUnclipped(before, after) }
    }

    fun renameLayer(id: String, name: String) {
        if (name.isBlank()) { onRefused?.invoke("A layer needs a name."); return }
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
        if (drawing) return // nothing is shown that the release could not keep: it says "Lift the pen first." once, at commit
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
        if (on && !stackUi.active.hasMask) { onRefused?.invoke("This layer has no mask yet. Add one from its menu."); return }
        val next = on && stackUi.active.hasMask
        if (next == editingMask) return
        editingMask = next
        onLayersChanged?.invoke(stackUi)
    }

    /** An empty mask (it shows everything) on [id], as ONE undo step; the brush goes to it, ready to hide. */
    fun addMask(id: String) {
        if (penIsDown()) return
        if (layerLocked(id)) { onRefused?.invoke(lockedSentence(id)); return }
        val before = stackUi
        val layer = before[id] ?: return
        if (layer.hasMask) { onRefused?.invoke("This layer already has a mask."); return }
        editingMask = id == before.activeId
        commitStack(before, before.withMask(id, true))
    }

    /** Throws the mask away as ONE undo step (its pixels go into the step: Undo brings it back). */
    fun deleteMask(id: String) {
        if (penIsDown()) return
        if (layerLocked(id)) { onRefused?.invoke(lockedSentence(id)); return }
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
    fun layerThumbnails(ids: List<String>, w: Int, h: Int, bounds: RectPx? = null,
                        onReady: (Map<String, IntArray>) -> Unit) {
        val pw = pageW
        val ph = pageH
        if (pw <= 0 || ph <= 0) return
        onGl {
            val out = HashMap<String, IntArray>()
            val scope = bounds ?: RectPx(0, 0, pw, ph)
            for (id in ids) engine.renderThumbnail(id, scope, w, h)?.let { out[id] = it }
            post { onReady(out) }
        }
    }

    /** True board/frame pictures for the visible strip only, never an archive or saved navigation. */
    fun boardFrameThumbnails(boardId: String, frameIds: List<String>, w: Int, h: Int,
                             onReady: (Map<String, IntArray>) -> Unit) {
        require(frameIds.size <= 16) { "Request only the visible filmstrip frames" }
        val captured = frameIds.distinct().toList()
        onGl {
            if (engine.strokeInProgress) { post { onReady(emptyMap()) }; return@onGl }
            val board = engine.boardDocument?.boards?.firstOrNull { it.id == boardId }
            if (board == null) { post { onReady(emptyMap()) }; return@onGl }
            val out = HashMap<String, IntArray>()
            try {
                for (frameId in captured) {
                    if (board.frames.any { it.id == frameId }) {
                        out[frameId] = engine.renderBoardThumbnail(boardId, frameId, w, h, paperArgb)
                    }
                }
                post { onReady(out) }
            } catch (e: Exception) {
                post { onReady(emptyMap()); onRefused?.invoke(e.message ?: "The frame previews could not be drawn") }
            }
        }
    }

    /** At most sixteen visible cell pictures; stale results are discarded rather than retried. */
    fun boardSpriteThumbnails(boardId: String, cells: List<Int>, w: Int, h: Int,
                              onReady: (Map<Int, IntArray>) -> Unit) {
        val document = boardDocument ?: run { onReady(emptyMap()); return }
        val request = try { SpritePreviewRequest(document, boardId, cells.toList(), w, h, boardContentRevision) }
            catch (_: IllegalArgumentException) { onReady(emptyMap()); return }
        onGl {
            if (engine.strokeInProgress || !request.matches(engine.boardDocument, boardContentRevision)) {
                post { onReady(emptyMap()) }; return@onGl
            }
            val out = LinkedHashMap<Int, IntArray>()
            try {
                for (cell in request.bounds.keys) {
                    out[cell] = engine.renderSpriteCellThumbnail(boardId, cell, w, h, paperArgb)
                }
                post {
                    onReady(if (!drawing && request.matches(boardDocument, boardContentRevision)) out else emptyMap())
                }
            } catch (e: Exception) {
                post { onReady(emptyMap()); onRefused?.invoke(e.message ?: "The cell previews could not be drawn") }
            } catch (e: OutOfMemoryError) {
                out.clear()
                post { onReady(emptyMap()); onRefused?.invoke("This phone could not allocate the cell previews") }
            }
        }
    }

    /** The ceiling on resident pencil, watercolour and oil state, from the phone's memory ([LayerBudget.mediaBudgetBytes]). */
    var mediaBudgetBytes: Long = Long.MAX_VALUE
        set(v) { field = v; onGl { engine.mediaBudgetBytes = v } }

    /** Every layer counts as one (R51): pencil, watercolour and oil are paid for by [mediaBudgetBytes], not by layers. */
    private fun roomForAnother(): Boolean {
        if (stackUi.size < maxLayers) return true
        onRefused?.invoke("This phone has room for $maxLayers layers, and this drawing has ${stackUi.size}.")
        return false
    }

    /** A move or a delete left a clipped layer at the bottom with nothing to clip to, so the stack unclipped it: said, not silent. */
    private fun tellIfUnclipped(before: LayerStack, after: LayerStack) {
        if (before.lostClipIn(after)) onNotice?.invoke(LayerStack.UNCLIPPED)
    }

    /** A layer change asked for with the pen down is said no to in words; the stroke is never ended to make room for it (R26). */
    private fun penIsDown(): Boolean {
        if (!drawing) return false
        onRefused?.invoke("Lift the pen first.")
        return true
    }

    private fun adopt(next: LayerStack) {
        if (next.activeId != activeLayer) onGl { mediaCanvas.releaseWindow() }
        stackUi = next
        activeLayer = next.activeId
        if (!next.active.hasMask) editingMask = false
        onLayersChanged?.invoke(next)
    }

    /** A stack-only change as ONE undo step. Shown at once; the engine confirms through [reportHistory]. */
    private fun commitStack(before: LayerStack, after: LayerStack) {
        if (penIsDown()) return
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

    /**
     * A pen has touched or hovered this canvas: from then on one finger navigates, it never paints. Remembered across restarts
     * (2026-10-21: after a crash it started false, and the owner's fingers, moving the canvas, left daubs of watercolour).
     */
    private var penSeen: Boolean
        get() = penSeenNow ?: context.getSharedPreferences(PEN_PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(PREF_PEN_SEEN, false).also { penSeenNow = it }
        set(v) {
            if (v == penSeen) return
            penSeenNow = v
            if (v) context.getSharedPreferences(PEN_PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(PREF_PEN_SEEN, true).apply()
        }
    private var penSeenNow: Boolean? = null
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
    private var strokeWatercolour: WatercolourDabber? = null
    private var scatterRng: SplitMix? = null
    private var strokeErase = false
    private var glBegan = false

    /** R9: a tuft brush's stroke — the sable brush's own state, one stroke long. Null for every other engine. */
    private var strokeTuft: TuftStroke? = null
    private var strokeSeed = 0L

    // queueEvent can run before EGL is current, both at startup and after a file picker.
    // Only renderer callbacks guarantee a current context AND surface. GL-thread only.
    private val frameWork = ArrayDeque<() -> Unit>()
    private val afterFrameWork = ArrayDeque<() -> Unit>()

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                if (engine.ready) graphicsEpoch += 1
                engine.init()
                mediaCanvas.forget()
                brushPreviews.forget()
                engine.onMediaRestored = { layer, plane, keys -> mediaCanvas.restored(layer, plane, keys) }
                engine.onLookWritten = { layer, plane, keys -> mediaCanvas.lookWritten(layer, plane, keys) }
                engine.onMediaWarning = { android.util.Log.w("JoyBrushMedia", it) }
                engine.setDocumentPaper(documentPaperState)
                // Never let a context-loss placeholder overwrite the person's last good file.
                contentLost = contentLost || engine.lostContent || retainedContents != null
                engine.addLayer(FIRST_LAYER)
                engine.setLayerName(FIRST_LAYER, FIRST_LAYER_NAME)
                post {
                    fillSerial++; onFillPreview?.invoke(null)
                    surfaceReady = true
                    // A new drawing and a graphics restart compile the media programs before the first stroke too,
                    // not only a drawing opened from a file (the first stroke after an install paid for all of them).
                    postDelayed({ pumpMediaPrewarm() }, 400)
                    if (contentLost) {
                        val recover = onGraphicsLost
                        if (recover != null) recover(retainedContents?.doc?.id ?: freshDocId)
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
                frameStats.frameBegin()
                // Bench only: while the bench records, the paint engine's and the media's sections of this frame are timed
                // (MediaPhases, glFinish brackets) into its phases. Off (the app): nothing set, nothing timed.
                val timing = frameStats.recording
                if (timing) cc.joycreator.joybrush.androidkit.gl.media.MediaPhases.timer = { name, ms -> frameStats.phase(name, ms) }
                try { drawFrame() } finally {
                    if (timing) cc.joycreator.joybrush.androidkit.gl.media.MediaPhases.timer = null
                    frameStats.frameEnd()
                }
            }
            private fun drawFrame() {
                while (frameWork.isNotEmpty()) frameWork.removeFirst()()
                frameStats.afterWork()
                // Media strokes and running water advance once per drawn frame, and keep frames coming while they run.
                try {
                    if (mediaCanvas.frame(SystemClock.uptimeMillis())) { reportMediaHistory(); super@JbCanvasView.requestRender() }
                } catch (e: Exception) {
                    noteBenchError("media frame", e)
                    // What the stroke already painted stays, as its one undo step; a step left open made the NEXT media
                    // stroke crash ("a media stroke inside another stroke", Note 9 2026-10-20, after a refused atlas frame).
                    val refused = e is cc.joycreator.joybrush.androidkit.gl.MediaRoomException
                    if (refused) mediaCanvas.dropRefusedStroke()   // the engine and its water stay
                    if (engine.mediaStrokeInProgress) { engine.endMediaStroke(); reportMediaHistory() }
                    if (!refused) mediaCanvas.release()
                    post { if (drawing && (mediaStroke || mediaErase)) cancelStroke(); onRefused?.invoke(e.message ?: "The pencil, watercolour or oil stroke could not be painted") }
                }
                frameStats.afterMedia()
                if(engine.watercolourFrame(SystemClock.uptimeMillis())) reportHistory()
                if(engine.watercolourAnimating) super@JbCanvasView.requestRender()
                val s = viewSnapshot
                drawView.zoom = s[0]; drawView.rotation = s[1]; drawView.panX = s[2]; drawView.panY = s[3]
                paperWarnings = (PaperState.problems(documentPaperState,PaperResources.catalogue) + engine.paperWarnings).distinct()
                try { engine.drawDisplay(viewW, viewH, drawView.docToClip(viewW, viewH), paperArgb) }
                catch(e: Exception) {
                    noteBenchError("display", e)
                    engine.cancelStroke(); engine.setTileBoard(null)
                    engine.setOnionBoard(null)
                    post { tileBoardId=null; onTileBoardChanged?.invoke(null); onRefused?.invoke(e.message ?: "Board preview could not be drawn") }
                    engine.draw(viewW,viewH,drawView.docToClip(viewW,viewH),paperArgb)
                }
                frameStats.afterDisplay()
                answerScreenSample()
                while (afterFrameWork.isNotEmpty()) afterFrameWork.removeFirst()()
            }
        })
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun undo() {
        onBeforeHistoryChange?.invoke()
        if (drawing) finishStroke()
        val editRevision = paperRevision; val historyRevision = ++paperHistoryRevision
        onGl { mediaCanvas.settleOwed(); engine.undoStep(); reportHistory(editRevision,historyRevision) }
    }
    fun redo() {
        onBeforeHistoryChange?.invoke()
        if (drawing) finishStroke()
        val editRevision = paperRevision; val historyRevision = ++paperHistoryRevision
        onGl { mediaCanvas.settleOwed(); engine.redoStep(); reportHistory(editRevision,historyRevision) }
    }
    /** Empties the layer being painted on, as one undo step. */
    fun clearCanvas() {
        val id = activeLayer
        if (contentLost) {
            onRefused?.invoke("This drawing cannot be cleared while it is awaiting recovery.")
            return
        }
        if (layerLocked(id)) { onRefused?.invoke(lockedSentence(id)); return }
        onGl { engine.clearLayer(id); reportHistory() }
    }

    // ── input ────────────────────────────────────────────────────────────────

    override fun onHoverEvent(event: MotionEvent): Boolean {
        onRawEvent?.invoke(event)
        reportPen(event)
        tool?.hover(event, view)
        if (MotionEventSamples.tool(event, 0).let { it == Tool.STYLUS || it == Tool.ERASER }) {
            penSeen = true
            penHovering = event.actionMasked != MotionEvent.ACTION_HOVER_EXIT
        }
        return super.onHoverEvent(event)
    }

    /**
     * The non-brush tool in use (Select, lasso, nudge: the UI lane's [CanvasTool]), or null when the brushes draw. A tool
     * swapped mid-gesture has that gesture cancelled. Main thread.
     */
    var tool: cc.joycreator.joybrush.androidkit.tools.CanvasTool? = null
        set(v) {
            if (toolOwnsGesture) { field?.cancel(); toolOwnsGesture = false }
            field = v
        }
    private var toolOwnsGesture = false

    /** Routes a contact to [tool] (see [cc.joycreator.joybrush.androidkit.tools.CanvasTool]'s contract). True = consumed. */
    private fun toolTouch(ev: MotionEvent): Boolean {
        val t = tool ?: return false
        val action = ev.actionMasked
        if (toolOwnsGesture) {
            val keep = t.event(ev, view)
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) toolOwnsGesture = false
            else if (!keep) { toolOwnsGesture = false; gestures.handOver(); gestures.onEvent(ev) }   // the rest navigates
            return true
        }
        if (action == MotionEvent.ACTION_DOWN && !drawing) {
            val pen = isPenAt(ev, 0)
            // A palm (a contact while the pen hovers or just after it lifted) is offered to nobody, as with a brush (H2).
            if (!pen && (penHovering || SystemClock.uptimeMillis() - penUpAt < PALM_GRACE_MS)) return false
            if (t.down(ev, 0, view)) {
                if (pen) penSeen = true
                toolOwnsGesture = true
                return true
            }
        }
        // A pen landing while fingers navigate is offered too (H8): as with a brush, the pen wins the gesture.
        if (action == MotionEvent.ACTION_POINTER_DOWN && !drawing && isPenAt(ev, ev.actionIndex) && t.down(ev, ev.actionIndex, view)) {
            penSeen = true
            gestures.reset()
            toolOwnsGesture = true
            return true
        }
        return false
    }

    /** True once a pen has touched or hovered this canvas: from then on fingers navigate or use the tool finger (H1). */
    val penHasBeenSeen: Boolean get() = penSeen

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        onRawEvent?.invoke(ev)
        reportPen(ev)
        if (eyedropTouch(ev)) return true
        if (toolTouch(ev)) return true
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
                requestUnbufferedDispatch(ev)   // the MotionEvent form exists since API 21 (the Note 9 is 29: it was skipped)
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
    private var cancelArmed = false
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
        cancelArmed = false
        cancelR = if (withCancelCircle) Eyedropper.CANCEL_CIRCLE_DP * resources.displayMetrics.density / 2f else 0f
        eyedropMove(x, y)
    }

    /** The touch is at ([x], [y]); the eyedropper samples there (a pen) or off the fingertip (a finger). */
    private fun eyedropMove(x: Float, y: Float) {
        fingerX = x; fingerY = y
        cancelArmed = Eyedropper.armsCancel(x, y, cancelX, cancelY, cancelR, cancelArmed)
        val (sx, sy) = Eyedropper.samplePoint(x, y, eyedropFinger, resources.displayMetrics.density)
        eyedropX = sx; eyedropY = sy
        publishEyedrop()
        val seq = ++sampleSeq
        sampleAt(sx, sy) { argb -> if (seq == sampleSeq && eyedropping) { eyedropNew = argb; publishEyedrop() } }
    }

    /** Cancel is where the TOUCH went back to, not where the lifted ring is. */
    private fun overCancel(): Boolean =
        Eyedropper.overCancel(fingerX, fingerY, cancelX, cancelY, cancelR, cancelArmed)

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
        // On an armed Tile board the screen shows the repeat, so the colour under the pen is the wrapped pixel.
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

    // ── media strokes (step 4c) ──────────────────────────────────────────────

    /** The stroke in progress is a media brush's, fed to [mediaCanvas] without the smoother (the Lead's step-4 check 2). */
    private var mediaStroke = false
    /**
     * The stroke in progress is the media eraser: its dabs take paint and water away instead of pixels. Never set in
     * JB-2.40 slice 1, where every eraser is the plain one and a plain write over media goes through the engine's bake;
     * slice 2 decides whether an eraser over media comes back here.
     */
    private var mediaErase = false
    /** The media stroke has been begun on the GL thread (at its first sample, where the window is placed). */
    private var mediaBegun = false
    /** Making the board document for this media stroke failed, and the reason was already said. */
    @Volatile private var mediaDocRefused = false
    /** The colour of the last media stroke: the "last colour" belly mode. */
    private var lastMediaArgb: Int? = null
    /** GL thread: what the history buttons last heard, so running water reports only when the undo stack changes. */
    private var mediaHistorySeen = Pair(-1, false)

    /** The active layer's kind, from the document; a layer the document has not seen yet is paint. */
    private fun activeLayerKind(): LayerKind = boardDocument?.layers?.firstOrNull { it.id == activeLayer }?.kind ?: LayerKind.PAINT

    private fun startMediaStroke(p: BrushPreset) {
        // Painting INTO a mask with a media brush stays refused (the Lead, step 1): a mask is a grey, not a paint.
        if (editingMask && stackUi.active.hasMask) {
            drawing = false
            onRefused?.invoke("A mask is painted with paint brushes. Pencil, watercolour and oil paint on the layer itself.")
            return
        }
        // The common refusals come BEFORE any layer is made (the Lead, 4c review): a GPU that cannot, a phone without the
        // free memory for the window. Said in words; nothing changes.
        mediaCanvas.unsupported?.let { drawing = false; onRefused?.invoke(it); return }
        val wet = p.media?.let { m -> m.medium == MEDIUM_WET || (m.thinner > 0 && m.paste?.let { it.shape < 2 && !it.clean } == true) } == true
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val heldMediaBytes = mediaCanvas.heldBytes
        if (!LayerBudget.windowFits(info.availMem, info.threshold, wet, haveBytes = heldMediaBytes)) {
            android.util.Log.w("JoyBrushMediaMemory",
                "refused stage=ui wet=$wet available=${info.availMem} threshold=${info.threshold} held=$heldMediaBytes")
            drawing = false
            onRefused?.invoke(LayerBudget.windowRefusal(wet))
            return
        }
        // R51: the media brush paints on the active layer itself; only a layer without pixels refuses it.
        BrushRules.refusalFor(p.engine, activeLayerKind())?.let { drawing = false; onRefused?.invoke(it); return }
        // JB-2.40 requirement 11: the media window paints where the pen really is and cannot wrap round a tile board yet,
        // which the repeat preview could not show; refused in words like smudge and fill.
        if (refuseTileTool("pencil, watercolour and oil")) { drawing = false; return }
        // JB-2.40 slice 3: on a layer with animation frames the stroke paints the plane under its first touch (the current
        // frame inside a board, the shared plane outside), chosen on the GL thread by GlPaintEngine.mediaPlaneAt.
        resetSnapper(fresh = true)
        strokeOnMask = false
        val stack = stackUi
        val w = viewW; val h = viewH
        // The Lead's check 8: a fresh drawing has no board document until its first save. Make it first, so the first
        // autosave after this stroke takes the path that keeps media state.
        mediaDocRefused = false
        onGl {
            try {
                if (engine.boardDocument == null) engine.setBoardDocument(syncBoardLayersFor(readContents(w, h).doc, stack))
            } catch (e: Exception) {
                mediaDocRefused = true
                post { onRefused?.invoke(e.message ?: "This drawing could not take pencil, watercolour or oil") }
            }
        }
        strokeLayerId = activeLayer
        drawing = true
        mediaStroke = true
        mediaBegun = false
        strokePreset = p
        smoother = null; placer = null; tracker = null; strokeTuft = null; strokeDabber = null
    }

    private fun feedMedia(ev: MotionEvent, idx: Int) {
        val p = strokePreset ?: return
        val samples = MotionEventSamples.from(ev, idx, { x, y -> view.screenToDoc(x, y) }, view.rotation)
        val snap = snapper
        val out = ArrayList<MediaSample>(samples.size)
        for (s in samples) {
            // A predicted point is drawn for latency elsewhere; paint and water cannot be taken back, so media never uses one.
            if (s.predicted) continue
            val placed = snap?.map(s) ?: s
            // A tuned pencil knows its own pen reach (the tilt that counts as lying flat); every other media brush uses the app's.
            out += MediaInput.sample(p.response.apply(placed), p.media?.stick?.tuning?.tiltReachDeg ?: MediaInput.REACH_DEG)
        }
        if (snap != null && snap.locked && !lockShown) { lockShown = true; onGuideLock?.invoke(true) }
        if (out.isEmpty()) return
        val target = strokeLayerId
        if (!mediaBegun) {
            mediaBegun = true
            val first = out[0]
            val argb = colorArgb ?: brush.argb
            val now = SystemClock.uptimeMillis()
            val belly = p.media?.let { m ->
                MediaBelly.colorFor(m.belly, rgbOf(argb), now.toInt(), last = lastMediaArgb?.let(::rgbOf))?.let(::argbOf)
            }
            lastMediaArgb = argb
            onGl {
                // Without the board document a save would drop the media state, so nothing is painted - and that is always
                // said: the reason when making the document failed (above), else a sentence here. Never a silent stroke.
                if (engine.boardDocument != null) mediaCanvas.begin(target, p, argb, belly, first.x, first.y, now)?.let { why -> post { onRefused?.invoke(why) } }
                else if (!mediaDocRefused) post { onRefused?.invoke("This drawing could not take pencil, watercolour or oil") }
            }
        }
        onGl { mediaCanvas.add(out) }
    }

    private fun finishMediaStroke() {
        resetSnapper(fresh = false)
        drawing = false
        mediaStroke = false; mediaErase = false; mediaBegun = false
        smoother = null; placer = null; tracker = null
        strokePreset = null; strokeDabber = null; strokeWatercolour = null; scatterRng = null; strokeErase = false; strokeTuft = null
        onGl {
            // Said in words, never a crash: what the stroke painted stays (its look), it just could not all be kept.
            try { mediaCanvas.end() } catch (e: cc.joycreator.joybrush.androidkit.gl.MediaRoomException) {
                noteBenchError("media end", e)
                post { onRefused?.invoke(e.message ?: "This pencil, watercolour or oil stroke could not all be kept") }
            }
            reportHistory()
        }
        flushPaperVisits()
        onStrokeEnded?.invoke()
    }

    private fun cancelMediaStroke() {
        resetSnapper(fresh = false)
        drawing = false
        mediaStroke = false; mediaErase = false; mediaBegun = false
        smoother = null; placer = null; tracker = null
        strokePreset = null; strokeDabber = null; strokeWatercolour = null; scatterRng = null; strokeErase = false; strokeTuft = null
        onGl {
            mediaCanvas.cancel()
            reportHistory()
        }
        flushPaperVisits()
        onStrokeEnded?.invoke()
    }

    /** GL thread: running water changed the undo stack (a step joined or popped): tell the history buttons. */
    private fun reportMediaHistory() {
        val now = engine.undo.undoDepth to engine.undo.canRedo
        if (now == mediaHistorySeen) return
        mediaHistorySeen = now
        reportHistory()
    }

    /** [syncBoardLayers] against an explicit stack, for a document made before the stack changes. */
    private fun syncBoardLayersFor(doc: JbDocument, stack: LayerStack): JbDocument {
        val old = doc.layers.associateBy { it.id }
        val next = stack.layers.map { state ->
            val base = old[state.id] ?: error("Layer metadata is missing for ${state.id}")
            base.copy(name = state.name, opacity = state.opacity, visible = state.visible, blend = state.blend,
                clip = state.clip, mask = if (state.hasMask) base.mask ?: error("Mask metadata is missing") else null)
        }
        return doc.copy(layers = next, activeLayerId = stack.activeId, paper = documentPaperState)
    }

    private fun rgbOf(argb: Int) = doubleArrayOf(((argb shr 16) and 0xFF) / 255.0, ((argb shr 8) and 0xFF) / 255.0, (argb and 0xFF) / 255.0)
    private fun argbOf(c: DoubleArray): Int {
        fun b(v: Double) = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
        return (0xFF shl 24) or (b(c[0]) shl 16) or (b(c[1]) shl 8) or b(c[2])
    }

    private fun startStroke(eraser: Boolean, watercolourPreviewSeed: Long? = null) {
        if (contentLost) {
            drawing = false
            onRefused?.invoke("Reopen your saved drawing before continuing.")
            return
        }
        if (layerLocked(activeLayer)) {
            drawing = false
            onRefused?.invoke(lockedSentence(activeLayer))
            return
        }
        // A hidden layer is refused out loud: painting where the paint cannot be seen is how work gets lost (JB-2.04).
        if (!stackUi.active.visible) {
            drawing = false
            onRefused?.invoke("\"${stackUi.active.name}\" is hidden. Show it to paint on it.")
            return
        }
        onBeforeStroke?.invoke()
        val b = brush
        var p = preset
        if(p?.engine == ENGINE_FILL && editingMask) {
            drawing=false; onRefused?.invoke("Select the paint layer to use the fill pen; masks are not supported yet."); return
        }
        val erase = b.erase || eraser
        val diagnosticObserver = onDiagnosticStrokeCapture
        val amount = if (p != null && !smoothingFromUser) p.smoothing else smoothing
        var frozen: FrozenBrushResult? = null
        if (diagnosticObserver != null) {
            val reason = diagnosticCaptureRefusal(p, erase, amount)
            if (reason != null) diagnosticObserver(DiagnosticStrokeCaptureResult.Refused(reason))
            else try {
                // Stamp dab evaluation never reads blend. Freeze the runtime eraser override too,
                // so a record replayed from this definition uses the actual live stroke operator.
                val effective = if (erase) p!!.copy(blend="erase") else p!!
                frozen = FrozenBrushRegistry.freeze(emptyMap(), effective) { "diagnostic-brush-${++diagnosticSerial}" }
                // Use the same detached definition for the live dabber and its diagnostic record.
                p = frozen.preset
            } catch (e: Exception) {
                frozen = null
                diagnosticObserver(DiagnosticStrokeCaptureResult.Refused("Diagnostic brush could not be frozen: ${e.message}"))
            }
        }
        // Editable lines (NATIVE_LINES_V1, v1.2): a layer that holds items (lines or pixel slabs) keeps every stroke it can
        // replay as a line; any other stroke is still painted, and the engine keeps it as a pixel slab in time order with
        // the lines. Only realistic media (and a tiled board) stay refused in words. With the switch on, a replayable
        // stroke starts a line.
        // R51, any brush works on any layer (owner, 2026-10-21: his daughter and his wife found the pen did nothing with a brush
        // picked): a realistic brush on a layer that holds editable lines first turns them into paint (Freeze: the picture
        // unchanged, its own undo step, said once), then paints. It used to be refused.
        if (p?.engine == ENGINE_MEDIA && !eraser && !editingMask && watercolourPreviewSeed == null && tileBoardId == null &&
            lineSnapshot.holdsItemsOnLayer(activeLayer)) {
            val id = activeLayer; val name = stackUi.active.name
            onBeforeHistoryChange?.invoke()
            onGl {
                mediaCanvas.releaseWindow()
                val done = try { engine.freezeLayerStep(id) } catch (e: Exception) { noteBenchError("freeze for media", e); false }
                if (done) {
                    reportHistory(); publishLines()
                    post { onNotice?.invoke("Lines on \"$name\" became paint so pencil, watercolour and oil fit. Undo brings them back.") }
                }
            }
            startMediaStroke(p)
            return
        }
        var lineFrozen: FrozenBrushResult? = null
        if (!editingMask && watercolourPreviewSeed == null) {
            val holdsItems = lineSnapshot.holdsItemsOnLayer(activeLayer)
            if (editableLines || holdsItems) {
                if (holdsItems) LineAdmission.slabRefusal(p, tileBoardId != null)?.let { why ->
                    drawing = false
                    onRefused?.invoke(LineAdmission.refusalSentence(stackUi.active.name, why))
                    return
                }
                val refusal = lineRefusal(p, erase, amount)
                // Not a line, on a layer with items: it will be kept as a slab, within the slab budget.
                if (holdsItems && refusal != null) LineAdmission.slabBudgetRefusal(lineSnapshot.slabTiles)?.let { why ->
                    drawing = false
                    onRefused?.invoke(LineAdmission.refusalSentence(stackUi.active.name, why))
                    return
                }
                if (refusal == null) try {
                    val effective = if (erase) p!!.copy(blend = "erase") else p!!
                    lineFrozen = frozenBrushes.freeze(lineSnapshot.brushRevision, lineSnapshot.brushes, effective) { newLineName("lb") }
                    p = lineFrozen.preset   // the live dabber and the record use the same detached definition
                } catch (e: Exception) {
                    lineFrozen = null       // a brush that can't be frozen is a plain stroke (a slab on a layer with items)
                }
            }
        }
        // The glaze blends (brush version 9) composite colour over colour. A mask holds grey with missing tiles meaning
        // white, so a screen glaze would grey out the white it should keep: refused in words, never painted as normal.
        if ((p?.blend == BLEND_MULTIPLY || p?.blend == BLEND_SCREEN) && editingMask && !erase) {
            drawing = false
            onRefused?.invoke("Multiply and screen brushes don't paint on masks. Select the paint layer, or pick another brush for the mask.")
            return
        }
        // A media brush paints through the media engine (step 4c) on the active layer (R51).
        if (p?.engine == ENGINE_MEDIA && !eraser) { startMediaStroke(p); return }
        // Every other brush, the eraser included, writes pixels; over media the engine's bake keeps the two agreeing.
        mediaErase = false
        mediaBegun = false
        // JB-2.23: on the mask, the stroke goes to the mask's store and paints GREY — white shows, black hides.
        strokeOnMask = editingMask && stackUi.active.hasMask
        strokeLayerId = if (strokeOnMask) maskStoreId(activeLayer) else activeLayer
        resetSnapper(fresh = true)
        drawing = true
        glBegan = false
        strokePreset = p
        strokeDabber = null
        strokeWatercolour = null
        scatterRng = null
        strokeTuft = null
        strokeErase = erase
        // Smoothing is measured in SCREEN px, so it is told the zoom: the slider then means the
        // same thing at every zoom, and the samples stay in document px either way.
        smoother = StrokeSmoother(amount, screenPerDoc = view.zoom)
        val tr = DirectionTracker().also { tracker = it }
        if (p?.engine == ENGINE_FILL && !mediaErase) {
            fillSerial++; fillSamples.clear(); fillPoints.clear(); fillPath=0.0
            fillSmoothing=amount; fillZoom=view.zoom
            fillArgb=colorArgb ?: b.argb; fillOpacity=p.opacity.base
            placer=null
        } else if (p == null) {
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
            if (!mediaErase) boardStrokeWork {
                engine.beginStroke(target, (colorArgb ?: b.argb).let { if (strokeOnMask) maskGrey(it) else it }, b.opacity, b.accumulate,
                    if (erase) StrokeBlend.ERASE else StrokeBlend.NORMAL, b.tip)
            }
        } else if (p.engine == ENGINE_WATERCOLOUR && !erase) {
            strokeSeed=watercolourPreviewSeed ?: SystemClock.uptimeMillis()
            strokeWatercolour=WatercolourDabber(p.watercolourRuntime(),strokeSeed)
            placer=null
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
            strokeSeed = seed
            val d = BrushDabber(p, seed)
            strokeDabber = d
            scatterRng = SplitMix(seed xor SCATTER_SALT)
            placer = DabPlacer(spacing = d.spacing, look = d::look)
            // glBegan stays false here on purpose: the stroke is begun on the GL thread only once a
            // first dab exists, so the tip's hardness and the stroke's opacity are the ones the
            // file evaluated AT that dab rather than the base values the file happens to carry.
        }
        lineContact = lineFrozen?.let { f ->
            try {
                LineContact(StrokeCapture(newLineName("ln"), f.id, strokeSeed, amount, view.zoom, colorArgb ?: b.argb), f.newEntries)
            } catch (e: Exception) { null }
        }
        // Lines live under document cels and are saved by the board path: a fresh drawing gets its board document first
        // (queued before this stroke's own GL work, as the media path does).
        if (lineContact != null && boardDocument == null) {
            val stack = stackUi
            val w = viewW; val h = viewH
            onGl {
                try {
                    if (engine.boardDocument == null) engine.setBoardDocument(syncBoardLayersFor(readContents(w, h).doc, stack))
                } catch (e: Exception) {
                    post { onRefused?.invoke(e.message ?: "This drawing could not keep editable lines") }
                }
            }
        }
        if (frozen != null && diagnosticObserver != null) {
            val layer = boardDocument?.layers?.firstOrNull { it.id == strokeLayerId }
            val argb = colorArgb ?: b.argb
            diagnosticContact = DiagnosticContact(diagnosticObserver, strokeLayerId,
                layer?.let { it.sharedCelId ?: it.cels.singleOrNull()?.id }, strokeBlend(p, erase), frozen,
                StrokeCapture("diagnostic-stroke-$diagnosticSerial", frozen.id, strokeSeed, amount, view.zoom, argb))
        }
    }

    private fun strokeBlend(p: BrushPreset?, erase: Boolean): StrokeBlend = when {
        erase || p?.blend == "erase" -> StrokeBlend.ERASE
        p?.blend == BLEND_MULTIPLY -> StrokeBlend.MULTIPLY
        p?.blend == BLEND_SCREEN -> StrokeBlend.SCREEN
        else -> StrokeBlend.NORMAL
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
        val opacity = strokeWatercolour?.strokeOpacity ?: if (p == null) b.opacity else if (d == null) p.opacity.base else d.strokeOpacity
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
        val smudge = if (p != null && p.engine == ENGINE_SMUDGE) SmudgeParams(p.smudge.pickup, p.smudge.load, p.smudge.texturePickup, p.smudge.paint) else null
        // R9: a tuft stroke's whole-stroke shader numbers (streaks, tooth), from the file and this stroke's seed.
        val tuft = if (p != null && strokeTuft != null) TuftStroke.shading(p, strokeSeed) else null
        val argb = (colorArgb ?: b.argb).let { if (strokeOnMask) maskGrey(it) else it }
        diagnosticContact?.let { it.start = DiagnosticStrokeStart(argb, opacity, tip.hardness) }
        lineContact?.startArgb = argb
        val target = strokeLayerId
        val watercolorOptions=strokeWatercolour?.spec
        val watercolorSeed=strokeSeed
        boardStrokeWork { engine.beginStroke(target, argb, opacity, accumulate,
            strokeBlend(p, eraseBlend), tip, grain, smudge, tuft, watercolorOptions, watercolorSeed) }
    }

    private fun feed(ev: MotionEvent) {
        val idx = ev.findPointerIndex(pointerId)
        if (idx < 0) return
        if (mediaStroke) { feedMedia(ev, idx); return }
        val sm = smoother ?: return
        val released = ArrayList<PenSample>()
        // The pen reports SCREEN px; a stroke is recorded in DOCUMENT px, and the page's own turn
        // is what the lean direction is read against.
        val samples = MotionEventSamples.from(ev, idx, { x, y -> view.screenToDoc(x, y) }, view.rotation)
        // JB-2.12: onto the guides first (position only; pressure and tilt pass through), then the brush's own response.
        val snap = snapper
        for (s in samples) {
            if(!drawing) break
            feedOne(sm, snap?.map(s) ?: s, released)
        }
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
        if (mediaStroke || mediaErase) {
            if (mediaErase) smoother?.let { paint(it.finish()) }   // the eraser's last dabs, as for any eraser
            finishMediaStroke()
            return
        }
        if(strokeWatercolour == null) smoother?.let { paint(it.finish()) }
        else paintWatercolourDabs(strokeWatercolour!!.finish())
        resetSnapper(fresh = false)
        if (strokePreset?.engine == ENGINE_FILL) {
            val samples=fillSamples.toList(); val target=strokeLayerId
            val argb=fillArgb; val opacity=fillOpacity; val amount=fillSmoothing; val zoom=fillZoom
            val mode=when {
                strokeErase || strokePreset?.blend == "erase" -> MaskPaintMode.ERASE
                strokePreset?.blend == "behind" -> MaskPaintMode.BEHIND
                else -> MaskPaintMode.FILL
            }
            val serial=fillSerial
            drawing=false; smoother=null; placer=null; tracker=null
            strokePreset=null; strokeDabber=null; strokeWatercolour=null; scatterRng=null; strokeErase=false; strokeTuft=null
            boardStrokeWork {
                try {
                    val mask=FillPenRaster.prepare(samples,amount,zoom)
                    if(FillPenRaster.fillsEnough(mask,zoom)) {
                        val ownership=engine.boardDocument?.let { RegionDocumentOps.paintPlans(it,emptyMap())[target] }
                        // An armed Tile board fills the repeat: the shape is folded back into the board rectangle (R50).
                        val tileBoard=engine.tileBoardId?.let { id -> engine.boardDocument?.boards?.firstOrNull { it.id == id } }
                        val shape=if(tileBoard == null) mask else mask.foldInto(tileBoard.rect)
                        val changes=FillPenRaster.plan(shape,argb,opacity,mode,ownership) { engine.readTile(target,it) }
                        engine.replaceTiles(target,changes,wrappedBoardId=tileBoard?.id,fill=FillRequest(shape,argb,opacity,mode))
                        if(changes.isNotEmpty()) reportHistory() else post { onRefused?.invoke(FillPenRaster.NOTHING_TO_FILL) }
                    } else post { onRefused?.invoke(FillPenRaster.TOO_SMALL) }
                } finally {
                    afterFrameWork.addLast { post { if(fillSerial == serial) onFillPreview?.invoke(null) } }
                }
            }
            fillSamples.clear(); fillPoints.clear()
            flushPaperVisits(); onStrokeEnded?.invoke(); return
        }
        // R9: the lift — a fast one carries on as the bristles leave the paper — and any spatter it throws.
        strokeTuft?.let { t -> paintTuft(t.finish()) }
        val diagnosticResult = finishDiagnosticCapture()
        // The line's record carries the colour the stroke actually began with (as the first dab evaluated it).
        val line = lineContact?.let { c -> c.capture.finish()?.let { r -> c.startArgb?.let { r.copy(colorArgb = it) } } }
        val lineBrushes = lineContact?.brushes.orEmpty()
        lineContact = null
        drawing = false
        smoother = null; placer = null; tracker = null
        strokePreset = null; strokeDabber = null; strokeWatercolour = null; scatterRng = null; strokeErase = false; strokeTuft = null
        boardStrokeWork { engine.setPendingLine(line, lineBrushes); engine.endStroke(); reportHistory() }
        flushPaperVisits()
        onStrokeEnded?.invoke()
        diagnosticResult?.let { (observer, result) -> observer(result) }
    }

    private fun cancelStroke() {
        diagnosticContact?.capture?.cancel()
        diagnosticContact = null
        lineContact?.capture?.cancel()
        lineContact = null
        // A media stroke given up (a second finger, a cancelled gesture) goes back to before it, as a plain stroke does: no step,
        // redo kept, and its water gone with it. It used to stay as a step: the owner's finger daubs, 2026-10-21, which a
        // two-finger-tap undo could not reach (each tap's first finger made and removed a daub of its own).
        if (mediaStroke || mediaErase) { cancelMediaStroke(); return }
        resetSnapper(fresh = false)
        fillSerial++; fillSamples.clear(); fillPoints.clear(); onFillPreview?.invoke(null)
        drawing = false
        smoother = null; placer = null; tracker = null
        strokePreset = null; strokeDabber = null; strokeWatercolour = null; scatterRng = null; strokeErase = false; strokeTuft = null
        onGl { engine.cancelStroke() }
        flushPaperVisits()
        onStrokeEnded?.invoke()
    }

    private fun paint(points: List<PenSample>) {
        if (points.isEmpty()) return
        strokeWatercolour?.let { wc ->
            val dabs=points.flatMap { wc.add(it) }
            paintWatercolourDabs(dabs)
            return
        }
        if (strokePreset?.engine == ENGINE_FILL && !mediaErase) {
            fillPoints.addAll(points.map { Pt(it.x.toDouble(),it.y.toDouble()) })
            onFillPreview?.invoke(FillPenPreviewState(fillPoints.toList(),fillArgb,fillOpacity))
            return
        }
        strokeTuft?.let { t -> paintTuft(t.add(points)); return }
        val placed = placer?.add(points) ?: return
        if (placed.isEmpty()) return
        if (mediaErase) {
            val target = strokeLayerId
            if (!mediaBegun) {
                mediaBegun = true
                val first = placed[0]
                onGl { mediaCanvas.beginErase(target, first.x.toDouble(), first.y.toDouble(), SystemClock.uptimeMillis())?.let { why -> post { onRefused?.invoke(why) } } }
            }
            onGl { mediaCanvas.addErase(placed) }
            return
        }
        val p = strokePreset
        val dabs = if (p == null) placed else scattered(p, placed)
        if (!glBegan) beginStrokeNow()
        boardStrokeWork { engine.addDabs(dabs) }
    }

    private fun paintWatercolourDabs(dabs: List<Dab>) {
        if(dabs.isEmpty()) return
        if(!glBegan) beginStrokeNow()
        boardStrokeWork { engine.addDabs(dabs) }
        if(strokeWatercolour?.capped == true) onRefused?.invoke("Watercolor stroke reached its dab budget; lift the pen and continue")
    }

    /**
     * Lays [strokes] with the brush in hand, as if drawn, and makes them ONE undo step (R9: the tuning sheet's "Test"). The
     * samples are SCREEN px of this view; they are put on the page where they appear. Ignored while a stroke is drawn.
     */
    fun drawStrokes(strokes: List<List<PenSample>>) {
        if (strokes.isEmpty() || penIsDown()) return
        var mark = 0
        onGl { mark = engine.undo.undoDepth }
        for ((index, samples) in strokes.withIndex()) {
            // Automated previews keep the same dabs while controls change. Freehand starts
            // still use the clock; a new test stroke can have its own random pattern.
            val seeds = preset?.takeIf { it.engine == ENGINE_WATERCOLOUR }?.watercolour?.sampleSeeds
            val previewSeed = seeds?.getOrNull(index) ?: seeds?.firstOrNull()?.plus(index.toLong())
            startStroke(eraser = false, watercolourPreviewSeed = previewSeed)
            val sm = smoother ?: continue
            val released = ArrayList<PenSample>()
            for (s in samples) {
                val (dx, dy) = view.screenToDoc(s.x, s.y)
                feedOne(sm, s.copy(x = dx, y = dy), released)
            }
            paint(released)
            finishStroke()
        }
        onGl { engine.settleWatercolourFade(); engine.undo.mergeNewest(engine.undo.undoDepth - mark); reportHistory() }
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
        // Feed supplies post-guide coordinates; capture before response and smoothing alter them.
        if (raw.isPlaceable && !raw.predicted) diagnosticContact?.let { contact ->
            if (contact.sampleCount >= 32768) refuseDiagnosticCapture("Diagnostic capture reached its 32768 raw sample budget")
            else { contact.capture.add(raw); contact.sampleCount++ }
        }
        if (raw.isPlaceable && !raw.predicted) lineContact?.let { it.capture.add(raw); it.samples++ }
        // The watercolor dabber owns the POC's pressure/tilt/path filters. Never smooth or curve it twice.
        if(strokeWatercolour != null) { if(raw.isPlaceable && !raw.predicted) released.add(raw); return }
        if (strokePreset?.engine == ENGINE_FILL && !mediaErase) {
            if(!raw.isPlaceable || raw.predicted) return
            fillSamples.lastOrNull()?.let { previous ->
                fillPath+=kotlin.math.hypot(raw.x.toDouble()-previous.x,raw.y.toDouble()-previous.y)*fillZoom
            }
            if(fillSamples.size >= 32768 || !fillPath.isFinite() || fillPath > 32768.0) {
                cancelStroke(); onRefused?.invoke("Fill stroke is too long; draw a smaller shape"); return
            }
            fillSamples.add(raw)
            released.addAll(sm.add(raw)); return
        }
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
        boardStrokeWork { engine.addTuftStamps(stamps) }
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
     * cels, several boards, a board that is not a canvas, or a tile that is not a tile.
     * Several PAINT layers are shown in full (JB-2.04). Opening the half that can be shown and dropping
     * the rest is the one thing this must never do.
     */
    fun load(opened: JbContents, onDone: () -> Unit) {
        lineEditEpoch += 1          // edits handed in before this are about the old drawing (H10)
        frozenBrushes.clear()
        // A drawing that still carries the once-shared id gets its own now; the next save writes it (audit 2026-10-18).
        val contents = if (opened.doc.id == LEGACY_SHARED_DOC_ID) opened.copy(doc = opened.doc.copy(id = newDocumentId())) else opened
        freshDocId = contents.doc.id
        val doc = withoutLineDeclarations(contents.doc)
        val retained = validatedLoadMetadata(contents).let { it.copy(doc = withoutLineDeclarations(it.doc)) }

        val states = doc.layers.map { LayerState(it.id, it.name, it.opacity, it.visible, it.blend, it.mask != null, it.clip, it.locked) }
        val active = doc.activeLayerId?.takeIf { id -> doc.layers.any { it.id == id } } ?: doc.layers.last().id
        val stack = LayerStack(states, active)
        val wanted = ArrayList<Pair<Triple<String,String,Long>,ByteArray>>(contents.tiles.size)
        // Editable lines (NATIVE_LINES_V1): where a cel keeps a look, the screen shows the look and the tile file is the
        // base under its lines; no line is replayed to open.
        // v1.2 H13: a tile under items (a look, or a slab stack) is a base, never the picture; a stack with no look is
        // composed from its base and slabs by the engine.
        val linePlan = LineOpenPlan.of(contents)
        for ((key, look) in contents.looks) wanted.add(Triple(key.first, key.second, tileKeyOf(key.third)) to look)
        val lineRecords = linePlan.records.mapKeys { (k, _) -> LinePlane(k.first, k.second) }
        val lineBases = linePlan.bases.entries.associate { (at, bytes) -> (LinePlane(at.first, at.second) to tileKeyOf(at.third)) to bytes }
        val lineSlabs = LinkedHashMap<LinePlane, MutableMap<Long, Pair<String, MutableMap<Long, ByteArray>>>>()
        for ((k, slab) in linePlan.slabs) {
            val bySeq = lineSlabs.getOrPut(LinePlane(k.layerId, k.celId)) { LinkedHashMap() }
            bySeq.getOrPut(k.seq) { slab.first to LinkedHashMap() }.second[tileKeyOf(k.key)] = slab.second
        }
        val lineCompose = linePlan.composeKeys.groupBy({ LinePlane(it.first, it.second) }, { tileKeyOf(it.third) }).mapValues { it.value.toSet() }
        for (entry in contents.tiles) {
            if (entry.key in linePlan.itemTiles) continue
            // Iterating the map gives the ENTRY: its key is (layer, cel, "tx_ty").
            // A mask's tiles go to the mask's store (JB-2.23); every other tile to its layer.
            val layer = doc.layers.first { it.id == entry.key.first }
            val store = if (layer.mask?.id == entry.key.second) maskStoreId(layer.id) else layer.id
            wanted.add(Triple(store,entry.key.second,tileKeyOf(entry.key.third)) to entry.value)
        }
        val resolved = PaperState.resolve(doc.paper,PaperResources.catalogue)
        val name = doc.name
        val board = doc.boards[0].rect

        // The UI's own picture of the stack changes now, so nothing drawn after this lands on a layer the file does not have.
        paperRevision++
        pendingPaperVisits.clear()
        stackUi = stack
        activeLayer = active
        editingMask = false
        onGl(allowLost = true) {
            // The previous drawing's realistic paint (its media window and any moving water) is discarded first, never
            // flushed into the new drawing's layers (audit 2026-10-18 P1: a wet wash could paint into the next drawing).
            if (contentLost) mediaCanvas.forget() else mediaCanvas.release()
            // resetDocument() empties EVERY layer; the file's stack is put back whole, then its pixels.
            engine.resetDocument()
            engine.setStack(stack)
            engine.setBoardDocument(doc)
            for ((address,rgba) in wanted) {
                if(address.first.endsWith(MASK_SUFFIX))engine.writeTile(address.first,address.third,rgba)
                else engine.writeCelTile(address.first,address.second,address.third,rgba)
            }
            // Media payloads (DocModel v8, v9): their stores and grounds, beside the look the tiles already put back, which
            // they agree with.
            for ((k, bytes) in contents.mediaTiles) engine.writeMediaTile(k.layerId, k.store, tileKeyOf(k.key), bytes, k.celId)
            engine.agreeAllPayloads()
            engine.loadLines(lineRecords, lineBases, contents.brushes, lineSlabs, lineCompose)
            post { postDelayed({ pumpMediaPrewarm() }, 400) }
            retainedContents = retained
            contentLost = false
            paper = resolved
            documentPaperState = doc.paper
            engine.setDocumentPaper(doc.paper)
            paperWarnings = PaperState.problems(doc.paper,PaperResources.catalogue) + engine.paperWarnings
            paperWarnings.forEach { android.util.Log.w("JoyBrushPaper",it) }
            reportHistory()
            post { onPaperChanged?.invoke(doc.paper); onDone() }
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
        if (contents.strokes.values.any { lines -> lines.any { it.capturedPhysicalSource != null } }) {
            return "this drawing has exact-copy lines in it, and this screen cannot show them yet"
        }
        LineOpenPlan.refusal(contents)?.let { return it }
        // Editable lines (NATIVE_LINES_V1): what the drawing lists and what it carries must agree exactly; a drawing
        // half-written in either direction is refused before anything on screen changes.
        val declaredLooks = doc.layers.flatMap { l -> DocOps.storedCels(l).flatMap { c -> c.looks.map { Triple(l.id, c.id, it) } } }.toSet()
        if (declaredLooks != contents.looks.keys) return "this drawing's saved line pictures do not match what it lists"
        if (contents.looks.values.any { it.size != TILE_BYTES }) return "a saved line picture in this drawing has the wrong size"
        val declaredLines = doc.layers.flatMap { l -> DocOps.storedCels(l).filter { it.strokesFile != null }.map { l.id to it.id } }.toSet()
        if (declaredLines != contents.strokes.filterValues { it.isNotEmpty() }.keys) return "this drawing's editable lines do not match what it lists"
        if (doc.brushes.toSet() != contents.brushes.keys) return "this drawing's kept brushes do not match what it lists"
        if (contents.strokes.values.any { lines -> lines.any { it.brushId !in contents.brushes } })
            return "a line in this drawing names a brush the drawing does not keep"
        if (doc.layers.isEmpty()) return "this drawing has no layers"
        if(doc.boards.any{it.kind !in listOf(BoardKind.CANVAS,BoardKind.ANIMATION,BoardKind.SPRITE)})
            return "This renderer does not show puppet or character boards yet"
        for (layer in doc.layers) {
            if (layer.id.endsWith(MASK_SUFFIX)) {
                return "layer \"${layer.name}\" uses a reserved mask identifier; this screen cannot open it safely"
            }
            if (!layer.kind.hasPixels) {
                return "layer \"${layer.name}\" is ${layer.kind}, and this screen only paints pixels"
            }
            if(layer.animatedIn!=null)return "Whole-layer animation is an obsolete test format"
            if(layer.regions.isEmpty() && layer.cels.size!=1)return "A layer has no shared paint address"
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
        validatedLoadMetadata(contents)
    }

    /** Prepare every format/payload refusal on the caller thread, before UI or GL replacement. */
    private fun validatedLoadMetadata(contents: JbContents): JbContents {
        refusalFor(contents)?.let { throw JbArchiveException(it) }
        return try {
            CanvasSnapshot.metadataOf(contents)
        } catch (error: IllegalArgumentException) {
            throw JbArchiveException(error.message ?: "This drawing cannot be retained safely")
        }
    }

    /**
     * Editable lines into a board snapshot (NATIVE_LINES_V1, Codex's v11 layout): a tile some line crosses is saved as
     * its look (what the screen shows) with its base as the tile file under it; the records go to the cel's strokes and
     * the frozen brushes with the drawing. GL thread.
     */
    private fun withLines(c: JbContents): JbContents {
        val save = engine.lineSave()
        if (save.records.isEmpty()) return c
        fun name(key: Long) = DocOps.key(Tiles.tx(key), Tiles.ty(key))
        // Editable lines v1 + v1.2 (H13: pixel slabs): the engine's items, as the document's names; the mapping is pure.
        val planes = save.records.mapNotNull { (plane, records) ->
            val cel = plane.celId ?: return@mapNotNull null
            PlaneItems(plane.layerId, cel, records,
                crossed = save.crossed[plane].orEmpty().mapTo(LinkedHashSet(), ::name),
                bases = save.crossed[plane].orEmpty().mapNotNull { k -> save.bases[plane to k]?.takeUnless { Tiles.isBlank(it) }?.let { name(k) to it } }.toMap(),
                slabs = save.slabs[plane].orEmpty().mapValues { (_, s) -> SlabBytes(s.first, s.second.mapKeys { name(it.key) }) })
        }
        return LineSaveMapping.apply(c, planes, save.brushes)
    }

    /**
     * The document as the engine holds it after an open: a cel that kept looks lists them among its tiles (the screen
     * shows them there), and no cel names line storage and the drawing names no frozen brushes. Those live in the
     * engine's NativeLines, and [withLines] declares them again on every save.
     */
    private fun withoutLineDeclarations(doc: JbDocument): JbDocument {
        if (doc.brushes.isEmpty() && doc.layers.none { l -> l.cels.any { it.strokesFile != null || it.looks.isNotEmpty() || it.slabs.isNotEmpty() } }) return doc
        return doc.copy(brushes = emptyList(), layers = doc.layers.map { l ->
            l.copy(cels = l.cels.map { c -> c.copy(tiles = (c.tiles + c.looks).distinct().sorted(), looks = emptyList(), strokesFile = null,
                slabs = emptyList()) })
        })
    }

    /** The GL-thread half of [snapshot]: every layer, every tile, read back, under one document. */
    private fun readContents(w: Int, h: Int): JbContents {
        if (engine.hasJointPaintPublication) throw JbArchiveException(
            "This recorded drawing needs its physical-source save connection; your previous save is kept")
        // Pen-up already released UI drawing state; the cosmetic fade must finish before
        // capturing metadata or enumerating keys, otherwise newly painted tiles are absent.
        if(engine.settleWatercolourFade()) reportHistory()
        engine.boardDocument?.let { doc ->
            val current=syncBoardLayers(doc)
            engine.setBoardDocument(current)
            return withLines(BoardSnapshot.capture(current,
                mediaKeys={layer,cel->val p=engine.mediaPlaneOfCel(layer,cel); MediaStores.PAYLOAD.associateWith{engine.mediaTileKeys(layer,it,p)}},
                mediaRead={layer,cel,store,key->engine.readMediaTile(layer,store,key,engine.mediaPlaneOfCel(layer,cel))},
                keys={layer,cel->
                    val mask=current.layers.first{it.id==layer}.mask
                    if(mask?.id==cel)engine.tileKeys(maskStoreId(layer)) else engine.celTileKeys(layer,cel)
                }, read={layer,cel,key->
                    val mask=current.layers.first{it.id==layer}.mask
                    if(mask?.id==cel)engine.readTile(maskStoreId(layer),key) else engine.readCelTile(layer,cel,key)
                },paper=documentPaperState))
        }
        // Read back, not guessed at: the tiles are the drawing, so the archive carries exactly the
        // bytes the engine is holding and nothing has to be rebuilt from stroke records to save.
        val stack = engine.stack(activeLayer)
        // This older path writes paint layers only: a media layer's state must never be dropped by a save.
        stack.layers.firstOrNull { engine.hasMediaState(it.id) }?.let {
            throw JbArchiveException("\"${it.name}\" is a pencil, watercolour or oil layer, and this save path cannot keep it; your previous save is kept")
        }
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
                // A tile the engine allocated but nothing ever painted is not part of the drawing.
                // Saving one costs ~271 bytes in the archive (it deflates to almost nothing) but a
                // FULL 262144-byte array on every open, because readBounded inflates each entry into
                // its own buffer. That asymmetry is why a two-layer scratch drawing reached 28.5 MB
                // and then raised OutOfMemoryError on a 512 MB heap: tiles accumulated far past the
                // visible page from scribbling while zoomed out, and the untouched ones were kept.
                //
                // Not listing it is the whole fix. The archive already forbids a cel from listing a
                // tile it has no payload for (JbArchive validates both directions), so skipping the
                // name here keeps the file self-consistent rather than inventing an "exists but empty"
                // state the format cannot express.
                if (Tiles.isBlank(bytes)) continue
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
                    // Kept even when all zero: a mask tile erased to nothing HIDES its area, while a missing one shows
                    // everything (audit 2026-10-18 P1; BoardSnapshot already keeps them).
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
        val base = DocOps.newDocument(freshDocId, documentName, pw, ph) { ids.removeFirst() }
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
                locked = s.locked,
            )
        }
        val doc = base.copy(
            paper = documentPaperState,
            layers = docLayers,
            activeLayerId = stack.activeId,
        )
        return CanvasSnapshot.merge(retainedContents, JbContents(doc = doc, tiles = tiles, strokes = emptyMap(), thumbnailPng = null), documentPaperState)
    }

    private fun syncBoardLayers(doc: JbDocument): JbDocument {
        val old=doc.layers.associateBy{it.id}
        val stack=engine.stack(activeLayer)
        val next=stack.layers.map { state ->
            val base=old[state.id] ?: error("Layer metadata is missing for ${state.id}")
            base.copy(name=state.name,opacity=state.opacity,visible=state.visible,blend=state.blend,
                clip=state.clip,mask=if(state.hasMask)base.mask ?: error("Mask metadata is missing")else null)
        }
        return doc.copy(layers=next,activeLayerId=stack.activeId,paper=documentPaperState)
    }

    val boardDocument: JbDocument? get() = retainedContents?.doc
    /** Physical/content edits invalidate strip pictures; saved frame navigation does not. */
    @Volatile var boardContentRevision: Long = 0L
        private set
    var onBoardsChanged: ((JbDocument)->Unit)? = null
    /** Stop hover/playback before a real stroke freezes its saved content addresses. UI thread. */
    var onBeforeStroke: (() -> Unit)? = null

    /** Materialize initial board metadata once, without making a paint or undo operation. */
    fun ensureBoardDocument(onReady: (JbDocument) -> Unit) {
        onGl {
            try {
                val current = engine.boardDocument ?: readContents(viewW, viewH).doc.also(engine::setBoardDocument)
                val synced = syncBoardLayers(current)
                engine.setBoardDocument(synced)
                retainedContents = JbContents(synced, emptyMap(), emptyMap(), null)
                post { onBoardsChanged?.invoke(synced); onReady(synced) }
            } catch (e: Exception) { post { onRefused?.invoke(e.message ?: "The boards could not be opened") } }
        }
    }

    /** Saved navigation, deliberately absent from Undo. Playback uses [previewBoardFrames] instead. */
    fun selectBoardFrame(boardId: String, frameId: String) {
        onBeforeHistoryChange?.invoke()
        if (drawing) finishStroke()
        onGl {
            try {
                val current = engine.boardDocument ?: error("The boards are not ready")
                val next = RegionDocumentOps.selectFrame(syncBoardLayers(current), boardId, frameId)
                engine.setFramePreviews(emptyMap())
                engine.setBoardDocument(next)
                if (next != current) reportHistory(contentChanged = false)
            } catch (e: Exception) { post { onRefused?.invoke(e.message ?: "That frame could not be selected") } }
        }
    }

    /** Ephemeral display planes. No metadata publication, undo step, or save callback. */
    fun previewBoardFrames(frames: Map<String, String>) {
        val captured = frames.toMap()
        onGl {
            if (!engine.strokeInProgress) {
                try { engine.setFramePreviews(captured) }
                catch (e: Exception) { post { onRefused?.invoke(e.message ?: "That preview could not be shown") } }
            }
        }
    }

    /** One serialized metadata/pixel transaction; no board changes while a brush owns its frame plan. */
    fun editBoards(edit: (JbDocument)->cc.joycreator.joybrush.core.doc.RegionChange) {
        onBeforeHistoryChange?.invoke()
        if(drawing)finishStroke()
        onGl {
            try {
                val current=engine.boardDocument ?: readContents(viewW,viewH).doc.also{engine.setBoardDocument(it)}
                val synced=syncBoardLayers(current); engine.setBoardDocument(synced)
                engine.setFramePreviews(emptyMap())
                // A sentence when editable lines became pixels for this change (v1.2 H4), shown like any other notice.
                engine.applyBoardChange(edit(synced))?.let { said -> post { onNotice?.invoke(said) } }; reportHistory()
            } catch(e:Exception) { post{onRefused?.invoke(e.message ?: "That board change could not be applied")} }
        }
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

    /** Debug replay: start recording on the GL thread, then run [then] on the UI thread. */
    fun startFrameStats(then: () -> Unit) {
        queueEvent { frameStats.start(); post { then() } }
        requestRender()
    }

    /** Debug replay: stop recording, wait for the GPU, collect its timings, and hand the summary to the UI thread. */
    fun finishFrameStats(ready: (cc.joycreator.joybrush.androidkit.bench.FrameStats.Summary) -> Unit) {
        queueEvent {
            frameStats.stop()
            val s = frameStats.snapshot()
            post { ready(s) }
        }
    }

    // ── device matrix probes (debug bench only: bench/MatrixBench) ────────────────────────────────────────────────

    /**
     * What the matrix reads back. [planes]: "visible" is the active layer as the screen shows it; on a layer with animation
     * regions also "shared:<cel>" (the canvas under the boards) and "frame:<cel>" for each frame. Tiles are premultiplied
     * RGBA8, [TILE_BYTES] each, by engine tile key. [idle]: no stroke, media stroke, running water or watercolour fade.
     */
    class BenchState(val planes: Map<String, Map<Long, ByteArray>>, val undoDepth: Int, val canUndo: Boolean,
                     val doc: JbDocument?, val tileBoardId: String?, val idle: Boolean)

    /** Exceptions the GL thread turned into a refusal sentence, collected only while [benchWatch] is on. */
    val benchErrors = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile var benchWatch = false
    private fun noteBenchError(where: String, e: Throwable) {
        if (benchWatch && benchErrors.size < 50) benchErrors.add("$where: ${e.javaClass.simpleName}: ${e.message}")
    }

    /** Reads the active layer back on the GL thread, behind everything already queued (so it is also a fence). */
    fun benchState(readPixels: Boolean = true, ready: (BenchState?, String?) -> Unit) {
        onGl(allowLost = true) {
            var state: BenchState? = null
            var failure: String? = null
            try {
                val layerId = activeLayer
                val doc = engine.boardDocument
                val planes = LinkedHashMap<String, Map<Long, ByteArray>>()
                if (readPixels) {
                    planes["visible"] = engine.tileKeys(layerId).associateWith { k -> engine.readTile(layerId, k) ?: ByteArray(TILE_BYTES) }
                    val meta = doc?.layers?.firstOrNull { it.id == layerId }
                    if (meta != null && meta.regions.isNotEmpty()) {
                        fun cel(id: String) = engine.celTileKeys(layerId, id).associateWith { k -> engine.readCelTile(layerId, id, k) ?: ByteArray(TILE_BYTES) }
                        meta.sharedCelId?.let { planes["shared:$it"] = cel(it) }
                        for (r in meta.regions) for (id in r.frameCel.values) planes["frame:$id"] = cel(id)
                    }
                }
                state = BenchState(planes, engine.undo.undoDepth, engine.undo.canUndo, doc, engine.tileBoardId,
                    idle = !mediaCanvas.inStroke && !mediaCanvas.running && !engine.strokeInProgress && !engine.watercolourAnimating)
            } catch (e: Exception) { failure = "${e.javaClass.simpleName}: ${e.message}" }
            post { ready(state, failure) }
        }
    }

    /**
     * A brand-new empty drawing on this screen's page, through the same [load] a file open uses: no boards, no history, no
     * media window, nothing armed. The matrix starts every cell from it.
     */
    fun benchFreshDrawing(onDone: () -> Unit) {
        if (drawing) cancelStroke()
        val ids = ArrayDeque(listOf(BOARD_ID, FIRST_LAYER, CEL_ID))
        freshDocId = newDocumentId()
        val fresh = DocOps.newDocument(freshDocId, documentName, viewW, viewH) { ids.removeFirst() }.copy(paper = documentPaperState)
        onGl(allowLost = true) { mediaCanvas.releaseWindow() }
        load(JbContents(fresh, emptyMap(), emptyMap(), null), onDone)
    }

    /** Debug adapter arithmetic check on a throwaway engine; it never reads/writes the drawing. */
    /** Media Atlas v2 B1's device proof (debug bench only): window and atlas media engines through the same frames. */
    fun runMediaAtlasProbe(ready: (String) -> Unit) {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            ready("{\"pass\":false,\"error\":\"Media atlas probe is debug only\"}")
            return
        }
        onGl {
            val report = try {
                cc.joycreator.joybrush.androidkit.gl.media.MediaAtlasProbe.probe(cc.joycreator.joybrush.androidkit.gl.ShaderLibrary(), engine.mediaPaper())
            } catch (e: Throwable) { "{\"pass\":false,\"error\":" + org.json.JSONObject.quote(e.toString()) + "}" }
            post { ready(report) }
        }
    }

    fun runRecordedReplayProbe(preset: BrushPreset, record: cc.joycreator.joybrush.core.stroke.StrokeRecord,
                               bake: Boolean = false,
                               openCold: Boolean = false,
                               joint: Boolean = false,
                               promotion: Boolean = false,
                               coldLoad: Boolean = false,
                               regional: Boolean = false,
                               capturedCopy: Boolean = false,
                               coldPayload: Boolean = false,
                               coldFailures: Boolean = false,
                               capturedJoint: Boolean = false,
                               ready: (String) -> Unit) {
        require(!capturedJoint || (!bake && !openCold && !joint && !promotion && !coldLoad && !regional && !capturedCopy && !coldPayload && !coldFailures)) {
            "Captured joint testing requires its independent disposable engine"
        }
        require(!capturedCopy || (!bake && !openCold && !joint && !promotion && !coldLoad && !regional && !coldPayload && !coldFailures)) {
            "Captured copy requires its independent disposable diagnostic"
        }
        require(!coldLoad || (promotion && !regional && !joint && !bake)) {
            "Cold root loading requires the flat promotion diagnostic"
        }
        require(!coldPayload || (promotion && !regional && !joint && !bake && !coldLoad && !openCold && !coldFailures)) {
            "Cold payload loading requires its independent flat promotion diagnostic"
        }
        require(!coldFailures || (promotion && !regional && !joint && !bake && !coldLoad && !openCold && !coldPayload)) {
            "Cold failure testing requires its independent flat promotion diagnostic"
        }
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            ready("{\"passed\":false,\"failure\":\"Recorded replay probe is debug only\"}")
            return
        }
        val paper = documentPaperState
        onGl {
            val probe = GlPaintEngine()
            val report = try {
                probe.init()
                probe.setDocumentPaper(paper)
                probe.addLayer("recorded-probe")
                val frozen = cc.joycreator.joybrush.core.brush.FrozenBrushRegistry.freeze(emptyMap(), preset) { "probe-frozen" }
                val value = if (capturedJoint) cc.joycreator.joybrush.androidkit.gl.RecordedCapturedJointProbe.run(
                    probe, "recorded-probe", frozen.preset, record.copy(brushId = frozen.id))
                else if (capturedCopy) org.json.JSONObject(probe.runCapturedCopyDiagnostic(
                    "recorded-probe", record.copy(brushId = frozen.id), frozen.preset))
                else if (promotion) cc.joycreator.joybrush.androidkit.gl.RecordedPromotionProbe.run(
                    probe, "recorded-probe", frozen.preset, record.copy(brushId = frozen.id), regional, coldLoad, coldPayload, coldFailures)
                else if (joint) cc.joycreator.joybrush.androidkit.gl.RecordedJointProbe.json(
                    cc.joycreator.joybrush.androidkit.gl.RecordedJointProbe.run(probe, "recorded-probe",
                        frozen.preset, record.copy(brushId = frozen.id), openCold = openCold))
                else if (bake) cc.joycreator.joybrush.androidkit.gl.RecordedBakeProbe.json(
                    cc.joycreator.joybrush.androidkit.gl.RecordedBakeProbe.run(probe, "recorded-probe",
                        frozen.preset, record.copy(brushId = frozen.id), openCold = openCold))
                else cc.joycreator.joybrush.androidkit.gl.RecordedReplayProbe.json(
                    cc.joycreator.joybrush.androidkit.gl.RecordedReplayProbe.run(probe, "recorded-probe",
                        frozen.preset, record.copy(brushId = frozen.id)))
                probe.release()
                val error = android.opengl.GLES30.glGetError()
                value.put("engineReleaseGlError", error)
                if (error != android.opengl.GLES30.GL_NO_ERROR) value.put("passed", false)
                value.toString()
            } catch (error: Exception) {
                org.json.JSONObject().put("passed", false).put("failure", error.message ?: error.javaClass.simpleName).toString()
            } finally {
                if (probe.ready) probe.release()
            }
            post { ready(report) }
        }
    }

    class RecordedCaptureAdmission internal constructor()

    private class RecordedCaptureAdmissionState(
        val ticket: RecordedCaptureAdmission,
        val epoch: Int,
        val layerId: String,
        val physicalDocumentCelId: String?,
        val nextSerial: Long,
        val engineDoc: cc.joycreator.joybrush.core.doc.JbDocument?,
        val uiDoc: cc.joycreator.joybrush.core.doc.JbDocument?,
        val paper: cc.joycreator.joybrush.core.doc.Paper,
        val effectivePresetJson: String,
        val stack: cc.joycreator.joybrush.core.layers.LayerStack,
        val contentRevision: Long,
        val paperRevision: Long,
        val paperHistoryRevision: Long,
        val undoHead: cc.joycreator.joybrush.core.paint.UndoLog.Step<Int>?,
        val undoDepth: Int,
        val heldTextures: Set<Int>,
        val heldBytes: Long,
        val otherLayerKeys: Map<String, Set<Long>>,
    )

    // GL-queue owned. A subsequent admission invalidates its predecessor; run consumes it once.
    private var recordedCaptureAdmissionState: RecordedCaptureAdmissionState? = null

    /** UI entry: returns only after an actual GL empty-plane check BEFORE MotionEvent DOWN. */
    fun prepareRecordedCaptureProbe(ready: (RecordedCaptureAdmission?, String?) -> Unit) {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val terminal = java.util.concurrent.atomic.AtomicBoolean(false)
        val timeout = Runnable {
            if (terminal.compareAndSet(false, true)) ready(null, "Inconclusive: GL admission callback timed out")
        }
        fun answer(ticket: RecordedCaptureAdmission?, reason: String?) {
            handler.post {
                if (terminal.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeout)
                    ready(ticket, reason)
                }
            }
        }
        handler.postDelayed(timeout, 10_000L)
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            answer(null, "Recorded capture parity admission is debug only")
            return
        }
        val epoch = graphicsEpoch
        val target = activeLayer
        val serial = diagnosticSerial
        val uiDocument = boardDocument
        val uiPaper = documentPaperState
        val admittedPreset = preset
        val admittedErase = brush.erase
        val admittedAmount = if (admittedPreset != null && !smoothingFromUser) admittedPreset.smoothing else smoothing
        // allowLost prevents onGl's epoch filter silently dropping the result. The body explicitly
        // refuses loss/epoch change, and timeout still resolves a missing/paused frame-work drain.
        onGl(allowLost = true) {
            if (terminal.get()) return@onGl
            try {
                recordedCaptureAdmissionState = null
                check(engine.ready && !contentLost && graphicsEpoch == epoch) { "Graphics are unavailable/restarted" }
                check(!drawing && !engine.strokeInProgress && !mediaStroke && !mediaErase && !editingMask) { "Finish the stroke/media/mask operation first" }
                check(activeLayer == target && diagnosticSerial == serial && diagnosticContact == null) { "Diagnostic contact/layer changed before admission" }
                check(tileBoardId == null && engine.tileBoardId == null) { "Periodic target cannot be admitted" }
                val doc = engine.boardDocument
                check(doc == uiDocument && boardDocument == uiDocument && documentPaperState == uiPaper && engine.documentPaper == uiPaper) { "Document/paper changed before admission" }
                val layer = doc?.layers?.firstOrNull { it.id == target }
                check(doc == null || layer != null) { "No physical target in current document" }
                check(layer == null || (layer.kind == cc.joycreator.joybrush.core.doc.LayerKind.PAINT &&
                    layer.animatedIn == null && layer.regions.isEmpty() && layer.cels.size == 1 &&
                    (layer.sharedCelId == null || layer.sharedCelId == layer.cels.single().id))) { "Admission requires a static continuous shared paint plane" }
                check(!engine.hasMediaState(target)) { "Admission requires a target without media payload" }
                check(preset == admittedPreset && brush.erase == admittedErase) { "Brush changed before admission" }
                check(diagnosticCaptureRefusal(admittedPreset, admittedErase, admittedAmount) == null) { "Current brush/contact cannot be captured by this diagnostic" }
                val effective = checkNotNull(admittedPreset).let { if (admittedErase) it.copy(blend = "erase") else it }
                val effectiveJson = cc.joycreator.joybrush.core.brush.BrushJson.encode(effective)
                check(target in engine.layerIds() && engine.tileKeys(target).isEmpty()) { "Actual pre-stroke physical target is not empty" }
                check(!engine.undo.canRedo) { "Admission requires no detached redo branch" }
                check(serial < Long.MAX_VALUE) { "Diagnostic serial is exhausted" }
                val ticket = RecordedCaptureAdmission()
                recordedCaptureAdmissionState = RecordedCaptureAdmissionState(ticket, epoch, target,
                    layer?.cels?.single()?.id, serial + 1L, doc, uiDocument, uiPaper, effectiveJson, engine.stack(target),
                    boardContentRevision, paperRevision, paperHistoryRevision, engine.undo.peekUndo(),
                    engine.undo.undoDepth, engine.undo.heldTextures(), engine.undo.heldBytes,
                    engine.layerIds().filter { it != target }.associateWith { engine.tileKeys(it).toSet() })
                answer(ticket, null)
            } catch (e: Exception) {
                answer(null, "Inconclusive admission: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** UI entry AFTER UP's endStroke is queued. Captured means raw UI completion, not GPU commit. */
    fun runRecordedCaptureProbe(ticket: RecordedCaptureAdmission,
                                captured: DiagnosticStrokeCaptureResult.Captured,
                                ready: (String) -> Unit) {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val terminal = java.util.concurrent.atomic.AtomicBoolean(false)
        fun refusal(reason: String) = org.json.JSONObject().put("probe", "recordedCaptureParity")
            .put("passed", false).put("inconclusive", true).put("failure", reason)
            .put("originalEngineReleased", false).toString()
        val timeout = Runnable {
            if (terminal.compareAndSet(false, true)) ready(org.json.JSONObject(refusal(
                "GL captured parity callback timed out; no activation claimed")).put("callbackTimeout", true).toString())
        }
        fun answer(result: String) {
            handler.post {
                if (terminal.compareAndSet(false, true)) { handler.removeCallbacks(timeout); ready(result) }
            }
        }
        handler.postDelayed(timeout, 15_000L)
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            answer(refusal("Recorded capture parity is debug only")); return
        }
        val callbackEpoch = graphicsEpoch
        val callbackSerial = diagnosticSerial
        onGl(allowLost = true) {
            if (terminal.get()) return@onGl
            try {
                val admission = checkNotNull(recordedCaptureAdmissionState) { "No current capture admission" }
                check(admission.ticket === ticket) { "This capture admission was superseded" }
                recordedCaptureAdmissionState = null // One use, including refusal.
                check(engine.ready && !contentLost && callbackEpoch == admission.epoch && graphicsEpoch == admission.epoch) { "Graphics epoch changed" }
                check(!drawing && !mediaStroke && !mediaErase && !editingMask && diagnosticContact == null) { "Another contact/tool is active" }
                check(activeLayer == admission.layerId && captured.layerId == admission.layerId) { "Captured physical layer changed" }
                check(callbackSerial == admission.nextSerial && diagnosticSerial == admission.nextSerial &&
                    captured.record.id == "diagnostic-stroke-${admission.nextSerial}" &&
                    captured.brush.id == "diagnostic-brush-${admission.nextSerial}" && captured.record.brushId == captured.brush.id) {
                    "Captured header/serial does not match the admitted next contact"
                }
                // Document Cel.id in the capture header is NOT engine celId=null for the shared plane.
                check(captured.physicalCelId == admission.physicalDocumentCelId) { "Captured document cel ownership changed" }
                check(captured.record.seq == 0L && !captured.record.locked && captured.record.samples.isNotEmpty()) { "Unexpected captured raw record header" }
                val admittedDefinition = cc.joycreator.joybrush.core.brush.BrushJson.decodeChecked(admission.effectivePresetJson)
                val frozenDefinition = captured.brush.preset
                check(cc.joycreator.joybrush.core.brush.BrushJson.encode(frozenDefinition.copy(id = admittedDefinition.id)) == admission.effectivePresetJson &&
                    captured.record.colorArgb == captured.start.colorArgb && captured.record.samples.none { it.predicted }) {
                    "Captured frozen definition/start colour/raw prediction header disagrees with admission"
                }
                val expectedBlend = when (frozenDefinition.blend) {
                    "normal" -> StrokeBlend.NORMAL
                    "erase" -> StrokeBlend.ERASE
                    "multiply" -> StrokeBlend.MULTIPLY
                    "screen" -> StrokeBlend.SCREEN
                    else -> error("Unsupported captured operator")
                }
                check(captured.blend == expectedBlend) { "Captured live engine blend disagrees with frozen definition" }
                check(engine.boardDocument == admission.engineDoc && boardDocument == admission.uiDoc &&
                    engine.documentPaper == admission.paper && documentPaperState == admission.paper &&
                    paperRevision == admission.paperRevision && paperHistoryRevision == admission.paperHistoryRevision &&
                    engine.stack(admission.layerId) == admission.stack && tileBoardId == null && engine.tileBoardId == null) {
                    "Document/paper/shared-plane/stack scope changed since admission"
                }
                val revisionsBeforeSettle = boardContentRevision - admission.contentRevision
                val capturedIsWc = captured.brush.preset.engine == ENGINE_WATERCOLOUR
                check(revisionsBeforeSettle == 1L || (capturedIsWc && revisionsBeforeSettle == 2L)) {
                    "Expected one ordinary UP request and at most its already-completed WC fade"
                }
                // May legitimately finish a WC fade started by that queued UP. Its report is additional
                // completion of this SAME stroke, not a new source capture or permission to paint.
                val settledWc = engine.settleWatercolourFade()
                if (settledWc) reportHistory()
                check(!engine.strokeInProgress) { "Legacy stroke still active after settlement" }
                check(!settledWc || revisionsBeforeSettle == 1L) { "WC fade was counted twice before host settlement" }
                check(boardContentRevision == admission.contentRevision + revisionsBeforeSettle + (if (settledWc) 1L else 0L)) { "Unexpected content publication count" }
                check(engine.boardDocument == admission.engineDoc && boardDocument == admission.uiDoc &&
                    engine.documentPaper == admission.paper && documentPaperState == admission.paper &&
                    engine.stack(admission.layerId) == admission.stack) { "Settlement changed the admitted document/paper/stack scope" }
                check(!engine.hasMediaState(admission.layerId)) { "Target acquired an unsupported media payload" }
                check(engine.undo.undoDepth == admission.undoDepth + 1 && !engine.undo.canRedo) { "Legacy history did not commit exactly one stroke" }
                val step = checkNotNull(engine.undo.peekUndo()) { "Captured legacy stroke produced no history" }
                check(step !== admission.undoHead && step.changes.isNotEmpty() && step.lines.isEmpty() && step.slabs.isEmpty() &&
                    step.stackBefore == null && step.stackAfter == null && step.paperBefore == null && step.paperAfter == null &&
                    step.documentBefore == null && step.documentAfter == null) { "Unexpected mixed/structural captured history step" }
                val keys = engine.tileKeys(admission.layerId).toSet()
                check(step.changes.all { it.layerId == admission.layerId && it.celId == null && it.before == null && it.after != null } &&
                    step.changes.map { it.key }.toSet() == keys && step.changes.size == keys.size) {
                    "Legacy history is not precisely one admitted empty-target/shared-plane stroke"
                }
                check(engine.undo.heldTextures() == admission.heldTextures && engine.undo.heldBytes == admission.heldBytes) { "Earlier history ownership changed" }
                check(engine.layerIds().filter { it != admission.layerId }.associateWith { engine.tileKeys(it).toSet() } == admission.otherLayerKeys) { "Other physical layer tile scope changed" }
                val frozen = captured.brush.preset
                val result = cc.joycreator.joybrush.androidkit.gl.RecordedCaptureProbe.json(
                    cc.joycreator.joybrush.androidkit.gl.RecordedCaptureProbe.run(engine, admission.layerId,
                        frozen, captured.record.copy(seq = 1L)))
                    .put("preStrokeEmptyPlaneVerifiedOnGl", true).put("admittedDiagnosticSerial", admission.nextSerial)
                    .put("admittedGraphicsEpoch", admission.epoch).put("capturedDocumentCelId", captured.physicalCelId ?: org.json.JSONObject.NULL)
                    .put("enginePhysicalCelAddress", org.json.JSONObject.NULL).put("hostCapturePublicationTokenVerified", true)
                    .put("exactOneScopedLegacyStrokeVerified", true).put("hostSettledCapturedWc", settledWc)
                    .put("originalEngineReleased", false)
                answer(result.toString())
            } catch (e: Exception) {
                answer(refusal("Capture admission guard ${e.javaClass.simpleName}: ${e.message}"))
            }
        }
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
     * One brush's preview for the drawer ([cc.joycreator.joybrush.androidkit.preview.BrushPreviewRenderer]): a canned stroke
     * drawn by the brush's own engine on a throwaway engine of this GL context, [widthPx] x [heightPx], handed back on the UI
     * thread (null when none could be made). Never touches the drawing or its history. Metered a few per frame on the GL thread;
     * the returned ticket calls a queued one off (the drawer closed).
     */
    fun renderBrushPreview(preset: BrushPreset, widthPx: Int, heightPx: Int, onBitmap: (android.graphics.Bitmap?) -> Unit) =
        renderBrushPreview(preset, widthPx, heightPx, cc.joycreator.joybrush.androidkit.preview.BrushPreviewStyle(), onBitmap)

    fun renderBrushPreview(preset: BrushPreset, widthPx: Int, heightPx: Int,
                           style: cc.joycreator.joybrush.androidkit.preview.BrushPreviewStyle,
                           onBitmap: (android.graphics.Bitmap?) -> Unit): cc.joycreator.joybrush.androidkit.preview.BrushPreviewTicket {
        val ticket = cc.joycreator.joybrush.androidkit.preview.BrushPreviewTicket()
        onGl(allowLost = true) {
            val request = cc.joycreator.joybrush.androidkit.preview.BrushPreviewRenderer.Request(preset, widthPx, heightPx, style, ticket) { bmp -> post { onBitmap(bmp) } }
            if (brushPreviews.submit(request)) pumpBrushPreviews()
        }
        return ticket
    }

    /** GL thread: a few previews now, and the rest on the next frames; once quiet, free the throwaway engines after a while. */
    /**
     * After a drawing opens, the pencil, watercolour and oil programs compile one per idle moment (GL thread), so the first
     * realistic stroke starts at once instead of stalling about a second on a Note 9. Never during a stroke.
     */
    /** One prewarm chain at a time: a surface and a load straight after it ask for it together. Main thread. */
    private var prewarmPumping = false

    private fun pumpMediaPrewarm() {
        if (prewarmPumping) return
        prewarmPumping = true
        pumpMediaPrewarmStep()
    }

    private fun pumpMediaPrewarmStep() {
        onGl {
            if (drawing || engine.strokeInProgress) { post { postDelayed({ pumpMediaPrewarmStep() }, 500) }; return@onGl }
            if (mediaCanvas.prewarmStep()) post { postDelayed({ pumpMediaPrewarmStep() }, 60) }
            else post { prewarmPumping = false }
        }
    }

    private fun pumpBrushPreviews() {
        if (brushPreviews.pump(SystemClock.uptimeMillis())) onGl(allowLost = true) { pumpBrushPreviews() }
        else postDelayed({ onGl(allowLost = true) { brushPreviews.releaseIfIdle(SystemClock.uptimeMillis()) } },
            cc.joycreator.joybrush.androidkit.preview.BrushPreviewRenderer.IDLE_MS)
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

    private fun reportHistory(restorePaperRevision: Long? = null, historyRevision: Long? = null,
                              contentChanged: Boolean = true) {
        val actualTile=engine.tileBoardId
        if(actualTile != tileBoardId) post { tileBoardId=actualTile; onTileBoardChanged?.invoke(actualTile) }
        if (contentChanged) boardContentRevision++
        engine.boardDocument?.let{doc->
            val current=syncBoardLayers(doc)
            engine.setBoardDocument(current)
            retainedContents=JbContents(current,emptyMap(),emptyMap(),null)
            post{onBoardsChanged?.invoke(current)}
        }
        publishLines()
        val u = engine.undo.canUndo
        val r = engine.undo.canRedo
        val stack = engine.stack(activeLayer)
        val restoredPaper = engine.documentPaper
        val paperChanged = restorePaperRevision != null && paperRevision == restorePaperRevision && documentPaperState != restoredPaper
        if (paperChanged) {
            // Save/readback queued after undo must see the restored setting before the UI post runs.
            paper = PaperState.resolve(restoredPaper,PaperResources.catalogue)
            documentPaperState = restoredPaper
        }
        post {
            if (restorePaperRevision != null && paperRevision == restorePaperRevision && paperHistoryRevision == historyRevision)
                onPaperChanged?.invoke(restoredPaper)
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
        private const val PEN_PREFS = "joybrush.canvas"
        private const val PREF_PEN_SEEN = "penSeen"

        /** Opaque black, the alpha a screen sample is given (the screen has no transparency). */
        private const val OPAQUE_BLACK = -0x1000000

        /**
         * The scatter generator's salt, so its stream is not the dabber's own. A second generator
         * rather than a second stream out of one, because [BrushDabber]'s draws per dab are fixed
         * by its own contract and must not be walked differently.
         */
        const val SCATTER_SALT = 0x5CA7L

        /** This screen holds exactly one drawing, so its document id is a constant. */
        /**
         * The id every drawing used to share (audit 2026-10-18, P1: two drawings with one id could overwrite each other's
         * folder copy). Never given to a new document any more; a drawing that still carries it gets its own on open.
         */
        const val LEGACY_SHARED_DOC_ID = "joy-brush"
        private const val BOARD_ID = "board-1"
        private const val CEL_ID = "cel-1"

        /** A new drawing's one layer. */
        private const val FIRST_LAYER = "layer-1"
        private const val FIRST_LAYER_NAME = "Layer 1"
    }
}
