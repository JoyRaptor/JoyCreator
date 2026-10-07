package cc.joycreator.joybrush.android

import android.app.Activity
import cc.joycreator.joybrush.android.board.BoardRuntimeController
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cc.joycreator.joybrush.android.chrome.BrushDrawerView
import cc.joycreator.joybrush.android.chrome.ChromeKit
import cc.joycreator.joybrush.android.chrome.ColourPairView
import cc.joycreator.joybrush.android.chrome.GuideOverlayView
import cc.joycreator.joybrush.android.chrome.JbIcon
import cc.joycreator.joybrush.android.chrome.PaperSheetView
import cc.joycreator.joybrush.androidkit.io.PaperResources
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.paper.PaperPreviews
import cc.joycreator.joybrush.core.paper.PaperState
import kotlinx.serialization.json.Json
import cc.joycreator.joybrush.android.chrome.LayerColumnView
import cc.joycreator.joybrush.android.chrome.Popovers
import cc.joycreator.joybrush.android.chrome.ReferenceView
import cc.joycreator.joybrush.android.chrome.ToolStripView
import cc.joycreator.joybrush.android.chrome.TopButton
import cc.joycreator.joybrush.android.chrome.BrushSettingsView
import cc.joycreator.joybrush.android.chrome.ValueHud
import cc.joycreator.joybrush.androidkit.BrushLibrary
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.gl.maskStoreId
import cc.joycreator.joybrush.androidkit.diag.PenDiagnosticsView
import cc.joycreator.joybrush.androidkit.io.JB_MIMETYPE
import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.io.JbArchiveException
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.androidkit.io.DrawingHistory
import cc.joycreator.joybrush.androidkit.io.DrawingRecovery
import cc.joycreator.joybrush.androidkit.io.StagedDrawing
import cc.joycreator.joybrush.androidkit.io.CanvasPng
import cc.joycreator.joybrush.androidkit.lab.BrushHotReload
import cc.joycreator.joybrush.androidkit.tools.Eyedropper
import cc.joycreator.joybrush.androidkit.tools.EyedropperRingView
import com.fadcam.ui.faditor.tools.ColorPickerDialog
import com.fadcam.ui.faditor.tools.ColorWheelView
import com.fadcam.ui.faditor.tools.ColorRecents
import com.fadcam.ui.faditor.tools.RecentColorsBar
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.chrome.BrushShelf
import cc.joycreator.joybrush.core.guide.Guide
import cc.joycreator.joybrush.core.guide.GuideSettings
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.layers.BlendNames
import cc.joycreator.joybrush.core.layers.LayerBudget
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.chrome.IconContrast
import cc.joycreator.joybrush.core.chrome.StripPlacement
import cc.joycreator.joybrush.core.chrome.ToolMemory
import cc.joycreator.joybrush.core.chrome.ToolSlot
import cc.joycreator.joybrush.core.chrome.TuftTestSheet
import cc.joycreator.joybrush.core.chrome.BrushPreviewStrokes
import cc.joycreator.joybrush.core.chrome.BrushTuning
import cc.joycreator.joybrush.core.io.SaveQueue
import cc.joycreator.joybrush.core.tool.SizeOpacityDrag
import cc.joycreator.joybrush.core.io.SaveReason
import cc.joycreator.joybrush.core.io.SaveTarget
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

// 10% and 12% white, the spec's overlay colours. Both literals fit in an Int.
private const val OVERLAY_FILL = 0x1AFFFFFF
private const val OVERLAY_RING = 0x1FFFFFFF
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// JB-0.08b. The working file and its folder are the spec's decisions (1) and (3).
private const val WORKING_DIR = "joybrush"
private const val WORKING_FILE = "current.joybrush"

/**
 * The "Save a copy" staging file in `cacheDir` (R11). A SAF `Uri` has no rename, so the copy is
 * built here in full and only then streamed to the place the person chose. Deleted in a `finally`,
 * so a failure cannot leave a whole drawing sitting in the cache.
 */
private const val COPY_TEMP = "joybrush-copy.tmp"

/** Autosave this long after the last stroke (spec decision 2). */
private const val AUTOSAVE_AFTER_MS = 30_000L

/**
 * How long the GL thread is left running waiting for a snapshot at the end of the screen, before
 * the view is paused anyway. See [JoyBrushActivity.onPause] for why it is not paused immediately.
 */
private const val PAUSE_FALLBACK_MS = 2_000L

/**
 * How long a save waits for the GL thread to hand over the drawing. A GL thread that never
 * answers (paused, or gone) would otherwise leave the save queue "busy" for ever, and every save
 * behind it would silently never happen. Past this the save is reported as failed and the idle
 * timer asks again. A late readback is ignored; once writing starts, the snapshot watchdog stops.
 */
private const val SNAPSHOT_TIMEOUT_MS = 15_000L

private const val REQUEST_OPEN = 4101
private const val REQUEST_SAVE_COPY = 4102
private const val REQUEST_REFERENCE = 4103
private const val REQUEST_EXPORT_PNG = 4104
private const val REQUEST_BOARD_EXPORT = 4105
private const val PREF_EXPORT_PAPER = "export_paper"
private const val PREF_NEW_PAPER = "new_drawing_paper"

// JB-2.01: where the chrome remembers itself between visits — the strip's place, each tool's brush and size, the
// pinned reference picture.
private const val CHROME_PREFS = "joybrush_chrome"
private const val PREF_STRIP = "strip"
private const val PREF_TOOLS = "tools"
/** Every brush's slider positions (BrushTuning), laid over the brush file whenever it is picked. */
private const val PREF_TUNING = "brush_tuning"
/** How long the live preview waits after a slider move before it redraws, ms (a drag sends a move per frame). */
private const val PREVIEW_DEBOUNCE_MS = 40L
/** The first tuning format (tuft brushes only). Read once, carried into [PREF_TUNING], then removed. */
private const val PREF_TUFT = "tuft_tuning"
private const val PREF_REF_URI = "reference_uri"
private const val PREF_REF_PLACE = "reference_place"
private const val PREF_REF_SHOWN = "reference_shown"
private const val PREF_LAYERS_OPEN = "layers_open"

/** JB-2.12, Lead ruling R49: the guides are remembered by the app, never by the drawing. */
private const val PREF_GUIDES = "guides"

/** Layer thumbnails are re-drawn this long after the last change, so a burst of strokes costs one refresh. */
private const val THUMBS_AFTER_MS = 250L

/** The top bar's height plus its margin: the strip never slides under it. */
private const val TOP_RESERVE_DP = 54f

/** A reference picture is decoded no bigger than this on its long side: a pinned picture never needs more. */
private const val REFERENCE_MAX_PX = 1600

/** How often, at most, the top icons re-read the picture behind them while it moves. */
private const val ICON_CHECK_MS = 120L

/** The icons read a GRID × GRID square of points each. */
private const val GRID = 3

/** The small colour wheel beside the strip, dp across. */
private const val WHEEL_DP = 176

/** The brush size slider runs 0..SIZE_STEPS on a log scale from the smallest brush to the largest. */
private const val SIZE_STEPS = 1000

/** What "Open…" accepts: a Joy Brush drawing, and the two types a provider gives one it does not know. */
private val OPENABLE_TYPES = arrayOf(JB_MIMETYPE, "application/zip", "application/octet-stream")

/**
 * ONE writer thread, shared by every instance of the screen.
 *
 * Two would fight over the same working file's `.tmp`, and an executor that is ever shut down turns
 * a snapshot that lands late (after onDestroy) into a `RejectedExecutionException` on the UI thread.
 * An idle thread costs nothing on a phone, and the process ends with the app.
 */
private val fileIo = Executors.newSingleThreadExecutor()

/**
 * The Joy Brush screen (JB-0.05): a [JbCanvasView] filling the window, with the compact chrome
 * of JB-2.01 over it — a top bar, a movable tool strip, one panel at a time, and a pinned
 * reference picture.
 *
 * Nothing about input lives here. The pen, palm rejection, smoothing and the GPU renderer all
 * belong to JbCanvasView; this Activity only hosts it, keeps the screen awake, and wires the
 * chrome to the view's public surface (brush preset, colour, smoothing, undo, redo, clear).
 *
 * Save and open (JB-0.08b) live here too, and only here: the view turns the canvas into a
 * [JbContents] on the GL thread and puts one back, and this Activity owns every path to disk —
 * the working file, "Save a copy…" and "Open…". Three rules run through all of it. A save goes
 * through `JbArchive.save`, which writes a temporary file and only then moves it into place, so a
 * save that fails leaves the drawing the person already had exactly where it was. Nothing is ever
 * written on the UI or GL thread. And a file that cannot be shown in full is refused out loud
 * rather than opened in part.
 */
class JoyBrushActivity : Activity() {

    private lateinit var canvas: JbCanvasView
    private lateinit var undoBtn: TopButton
    private lateinit var redoBtn: TopButton

    // JB-2.01: the compact chrome. The strip carries the tools, size, colour and opacity; the top bar the rest; one panel
    // at a time opens beside what was tapped.
    private lateinit var kit: ChromeKit
    private lateinit var strip: ToolStripView
    private lateinit var topBar: LinearLayout
    private lateinit var hairline: View
    private lateinit var popovers: Popovers
    private lateinit var hud: ValueHud
    private lateinit var reference: ReferenceView
    private lateinit var pinBtn: TopButton
    private lateinit var layersBtn: TopButton
    private lateinit var guidesBtn: TopButton
    private lateinit var guideOverlay: GuideOverlayView
    private lateinit var guideDone: TextView
    private var guides = GuideSettings.NONE
    private var paperPickerRevert: (() -> Unit)? = null
    private var paperSheet: PaperSheetView? = null
    @Volatile private var paperPreviewRevision = 0L
    private val paperPreviewWorker = Executors.newSingleThreadExecutor()
    private var previewPaperRequest: cc.joycreator.joybrush.core.paper.ResolvedPaper? = null
    private var previewPaperMaterial: PaperResources.Loaded? = null
    private val paperPreviews = PaperPreviews(render = { p, r ->
        val loaded = PaperResources.update(p,previewPaperRequest,previewPaperMaterial)
        previewPaperRequest = p; previewPaperMaterial = loaded
        loaded.render(r)
    })
    private lateinit var column: LayerColumnView
    private var columnOpen = false
    private var thumbsPending = false
    private val topButtons = ArrayList<TopButton>()
    private var iconsInked = false
    private var iconCheckPending = false
    private lateinit var overlaysView: FrameLayout
    private lateinit var boardOverlay: FrameLayout
    private var boardController: BoardRuntimeController? = null
    private var boardThumbnailRevision = 0L
    private lateinit var prefs: SharedPreferences
    private var placement = StripPlacement.DEFAULT
    private var chromeShown = true

    // JB-2.03a / D.02c: the recent-colours bar (now the strip's hair) and the eyedropper's ring.
    private lateinit var recentBar: RecentColorsBar
    private lateinit var ring: EyedropperRingView
    private val erasing: Boolean get() = tools.active == ToolSlot.ERASER

    // JB-1.05b: the shipped brush files. Each tool in the strip remembers its own brush, size and opacity (JB-2.01), and
    // the view draws with the one in the hand — the hard-coded round brush is only reached by an empty library.
    private val brushes: List<BrushPreset> = BrushLibrary.builtIn()
    private var tools = ToolMemory.defaults(brushes)

    /** Each brush's slider positions, by brush id (BrushTuning). Empty until the owner moves one. */
    private var tuning: Map<String, Map<String, Float>> = emptyMap()

    /** The brushes as the owner has tuned them: what the drawer shows and what the canvas draws with. */
    private fun tunedBrushes(): List<BrushPreset> = brushes.map { BrushTuning.apply(it, tuning) }

    // JB-0.06: the hidden pen probe. GONE until the owner holds the ⋯ button.
    private lateinit var diag: PenDiagnosticsView
    private lateinit var diagBox: LinearLayout
    private var diagShown = false

    // JB-0.08b / JB-2.15. `ui` is the main thread's queue; `changes` counts the edits that are not in
    // the working file yet, and zero means it is all there.
    private val ui = Handler(Looper.getMainLooper())
    private var changes = 0

    /** Where a save goes. Working is the autosave; Copy is a person's "Save a copy…". */
    private sealed class SaveDest {
        object Working : SaveDest()
        class Copy(val uri: Uri) : SaveDest()
        class Open(val selection: StagedDrawing) : SaveDest()
        class Png(val uri: Uri, val target: PngTarget) : SaveDest()
        class BoardExport(val uri: Uri, val target: cc.joycreator.joybrush.androidkit.io.BoardExportRequest) : SaveDest()
        class BoardHandoff(val target: cc.joycreator.joybrush.androidkit.io.BoardExportRequest) : SaveDest() {
            var bundle: File? = null
        }
    }

    private data class PngTarget(val documentId: String, val boardId: String, val frameId: String?, val includePaper: Boolean)
    private var pendingPng: PngTarget? = null
    private var pendingBoardExport: cc.joycreator.joybrush.androidkit.io.BoardExportRequest? = null
    private val boardExports by lazy { cc.joycreator.joybrush.android.board.BoardExportCoordinator(this,
        { target, name, mime ->
            if(target.format in listOf(cc.joycreator.joybrush.core.chrome.BoardExportLayout.Format.STUDIO,
                cc.joycreator.joybrush.core.chrome.BoardExportLayout.Format.SPRITE_LAB)) {
                toast("Preparing board…"); saves.request(SaveReason.EXPLICIT,SaveDest.BoardHandoff(target))
                return@BoardExportCoordinator
            }
            pendingBoardExport = target
            val intent = Intent(if(target.folder) Intent.ACTION_OPEN_DOCUMENT_TREE else Intent.ACTION_CREATE_DOCUMENT).apply {
                if(!target.folder) { addCategory(Intent.CATEGORY_OPENABLE); type = mime; putExtra(Intent.EXTRA_TITLE,name) }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            launch(intent, REQUEST_BOARD_EXPORT)
        }, { why -> toast(why) }) }

    private var loadingDrawing = true
    private var replacingDrawing = false
    private var workingSaveAllowed = false
    private var destroyed = false
    private var recoveringDrawing = false
    private var drawingLoadVersion = 0
    private var recoveryVersion = 0
    private var recoveryTimeout: Runnable? = null
    private val recovery by lazy {
        DrawingRecovery(File(cacheDir, "joybrush-recovery-${UUID.randomUUID()}.joybrush"))
    }

    /**
     * EVERY save goes through this one queue (JB-2.15): a request is an entry that waits its turn,
     * never a flag that can be cleared or a call that can return without doing its work. The three
     * work-loss bugs of the first wiring were one bug — a copy asked for during an autosave was
     * dropped, an undo could eat a save owed by a stroke, and a copy could be taken with the pen
     * down — and all three are the queue's job now. There is deliberately no `saveOwed` boolean.
     */
    private val saves = SaveQueue(object : SaveTarget<SaveDest> {
        override val strokeInProgress: Boolean get() = !destroyed &&
            (canvas.strokeInProgress || loadingDrawing || recoveringDrawing)
        override fun start(reason: SaveReason, destination: SaveDest, finished: (String?) -> Unit) {
            beginSave(destination, finished)
        }
    })

    private var viewPausePending = false

    /** The autosave the last change asked for, and the one the screen leaving asks for. */
    private val idleSave = Runnable { if (changes > 0) saves.request(SaveReason.IDLE, SaveDest.Working) }

    /** Pausing the GL thread, once a snapshot has answered or the wait for one is up. */
    private val pauseView = Runnable { pauseViewNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingBoardExport = cc.joycreator.joybrush.android.board.BoardExportCoordinator.restore(savedInstanceState?.getBundle("board_export"))
        savedInstanceState?.getString("png_board")?.let { board ->
            savedInstanceState.getString("png_document")?.let { document ->
                pendingPng = PngTarget(document, board, savedInstanceState.getString("png_frame"), savedInstanceState.getBoolean("png_paper"))
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        canvas = JbCanvasView(this)
        getSharedPreferences(CHROME_PREFS, MODE_PRIVATE).getString(PREF_NEW_PAPER,null)?.let { saved ->
            try { canvas.applyDocumentPaper(Json.decodeFromString(Paper.serializer(), saved)) }
            catch (_: Exception) { /* An obsolete preference must not prevent opening the app. */ }
        }
        // Debug-only lifecycle probe: tests use the real EGL destruction/resume path.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            intent.getBooleanExtra("joybrush_test_recreate_context", false)) {
            canvas.preserveEGLContextOnPause = false
        }
        kit = ChromeKit(this)
        prefs = getSharedPreferences(CHROME_PREFS, Context.MODE_PRIVATE)
        // Each tool as the person left it; the first shipped brush of each kind on a first visit (JB-1.05b, JB-2.01).
        tools = ToolMemory.decode(prefs.getString(PREF_TOOLS, null), brushes)
        tuning = BrushTuning.decode(prefs.getString(PREF_TUNING, null))
        prefs.getString(PREF_TUFT, null)?.let { old ->
            // The first format: carry the owner's Sable tuning over rather than lose it.
            tuning = BrushTuning.fromLegacyTuft(old, brushes) + tuning
            prefs.edit().putString(PREF_TUNING, BrushTuning.encode(tuning)).remove(PREF_TUFT).apply()
        }
        placement = StripPlacement.decode(prefs.getString(PREF_STRIP, null))
        val overlays = buildOverlays()
        overlaysView = overlays

        val root = object : FrameLayout(this) {
            // No stroke or layer edit can race startup restore or preservation-before-Open.
            override fun dispatchTouchEvent(event: MotionEvent): Boolean =
                if (loadingDrawing || replacingDrawing || recoveringDrawing || canvas.needsRecovery) true else super.dispatchTouchEvent(event)
        }
        root.addView(canvas, FrameLayout.LayoutParams(MATCH, MATCH))
        // The eyedropper's ring is drawn over the canvas and is exactly its size, so the canvas's own coordinates are the ring's.
        root.addView(ring, FrameLayout.LayoutParams(MATCH, MATCH))
        // The pinned reference floats over the drawing, full-bleed like it, under the chrome. It is a view, never a layer.
        reference = ReferenceView(kit)
        root.addView(reference, FrameLayout.LayoutParams(MATCH, MATCH))
        // JB-2.12: the guides, over the drawing and the reference, under the chrome. A view, never a layer.
        guideOverlay = GuideOverlayView(kit, canvas.view).apply {
            onChanged = { next, done -> guides = next; if (done) guidesChanged() }
        }
        root.addView(guideOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        boardOverlay = FrameLayout(this)
        root.addView(boardOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        boardController = BoardRuntimeController(this, boardOverlay, object : BoardRuntimeController.Host {
            override fun ensureDocument(ready: (cc.joycreator.joybrush.core.doc.JbDocument) -> Unit) = canvas.ensureBoardDocument(ready)
            override fun edit(change: (cc.joycreator.joybrush.core.doc.JbDocument) -> cc.joycreator.joybrush.core.doc.RegionChange) = canvas.editBoards(change)
            override fun selectFrame(boardId: String, frameId: String) = canvas.selectBoardFrame(boardId, frameId)
            override fun preview(frames: Map<String, String>) = canvas.previewBoardFrames(frames)
            override fun tile(boardId: String?,ready: (String?) -> Unit) = canvas.setTileBoard(boardId,ready)
            override fun onion(boardId: String?) = canvas.setOnionBoard(boardId)
            override fun transform() = canvas.view
            override fun contentRevision() = canvas.boardContentRevision
            override fun refusal(message: String) { Toast.makeText(this@JoyBrushActivity, message, Toast.LENGTH_SHORT).show() }
            override fun export(boardId: String) { pngOptions(layersBtn, boardId) }
            override fun selectionChanged(bounds: cc.joycreator.joybrush.core.doc.RectPx?) { updateLayerPreviewAspect(bounds); updateAnimationLayerMarkers(); boardThumbnailRevision++; refreshThumbs() }
            override fun thumbnails(boardId: String, frames: List<String>, width: Int, height: Int, ready: (Map<String, IntArray>) -> Unit) =
                canvas.boardFrameThumbnails(boardId, frames, width, height, ready)
            override fun spriteThumbnails(boardId: String, cells: List<Int>, width: Int, height: Int, ready: (Map<Int, IntArray>) -> Unit) =
                canvas.boardSpriteThumbnails(boardId, cells, width, height, ready)
        })
        canvas.onBoardsChanged = { boardController?.documentChanged(it); updateAnimationLayerMarkers() }
        canvas.onTileBoardChanged = { boardController?.tileChanged(it) }
        canvas.onBeforeStroke = { boardController?.stopPreview() }
        root.addView(overlays, FrameLayout.LayoutParams(MATCH, MATCH))
        // Joy Brush's identity, as a hairline along the very top (visual language §4.2): the section colour, never a button.
        hairline = View(this).apply { background = JbColors.roomGradient(this@JoyBrushActivity) }
        root.addView(hairline, FrameLayout.LayoutParams(MATCH, kit.dpi(2f), Gravity.TOP))
        setContentView(root)
        applyTool()
        restoreReference()
        // Four fingers tap: the chrome goes, the picture stays (JB-2.02's gesture, JB-2.01's one toggle).
        canvas.onToggleUi = { toggleChrome() }
        // JB-2.04: the column follows the stack, and anything refused is said in words.
        canvas.onLayersChanged = { stack -> layersChanged(stack) }
        canvas.onRefused = { why -> toast(why) }
        canvas.onGraphicsLost = { documentId -> boardController?.stop(); recoverGraphics(documentId) }
        setColumnOpen(prefs.getBoolean(PREF_LAYERS_OPEN, false))
        // The top icons re-read the picture behind them whenever it can have changed under them.
        canvas.onViewMoved = { checkIcons(); guideOverlay.invalidate(); boardController?.refreshTransform() }
        canvas.onGuideLock = { on -> guideOverlay.locked = on }
        guides = GuideSettings.decode(prefs.getString(PREF_GUIDES, null))
        guidesChanged()
        reference.onMoved = { saveReferencePlace(); checkIcons() }
        root.post { checkIcons() }
        // JbCanvasView reports this on the UI thread via post(), after every committed stroke,
        // undo, redo and clear. Undo and Redo start disabled -- there is no history yet.
        canvas.onBeforeHistoryChange = { popovers.close(); boardController?.stopPreview() }
        canvas.onPaperChanged = { p -> refreshPaper(p) }
        refreshPaper(canvas.documentPaper)
        canvas.onHistoryChanged = { canUndo, canRedo ->
            updateHistoryButtons(canUndo, canRedo)
            checkIcons()
            // Every one of those is work that is not in the working file yet, so each one restarts
            // the 30 s clock (JB-0.08b decision 2).
            noteChange()
        }
        // The one thing that releases a save that was waiting for the pen (JB-2.15). An undo, a redo
        // and a clear are history events, NOT this: they must not be able to pay — or cancel — a
        // debt a stroke owes.
        canvas.onStrokeEnded = {
            saves.strokeFinished()
            // D.02c Decision 3: a colour joins the history when a stroke is FINISHED with it (not on every live change in the
            // picker). An eraser stroke used no colour. The history is only written when this colour is not already first.
            if (!erasing) {
                val c = canvas.strokeColor
                if (ColorRecents.get(this).firstOrNull()?.let { it and 0xFFFFFF } != (c and 0xFFFFFF)) ColorRecents.push(this, c)
            }
            recentBar.setCurrent(canvas.strokeColor)
        }
        canvas.onEyedrop = { state -> ring.setState(state) }
        canvas.onColorPicked = { argb ->
            ColorRecents.push(this, argb)
            refreshColour()
        }
        updateHistoryButtons(false, false)
        // JB-0.06: the diagnostics overlay sees every pen event. It only stores numbers, and only
        // while it is visible, so drawing is untouched.
        canvas.onRawEvent = { ev -> diag.onRawEvent(ev); boardController?.penEvent(ev) }
        goFullScreen(overlays)

        // Where the person left off, if they left off anywhere.
        restoreWorkingFile()
    }

    override fun onResume() {
        super.onResume()
        viewPausePending = false
        ui.removeCallbacks(pauseView)
        canvas.onResume()
        if (chromeShown) boardController?.resume()
        startBrushLab()
        saves.drain()
    }

    override fun onPause() {
        boardController?.stop()
        popovers.close()
        stopBrushLab()
        // GLSurfaceView DEFERS a GL event that was queued before its GL thread is paused until the
        // next resume — and after a force-stop there is no next resume, so the autosave would never
        // happen at exactly the moment it matters. So the readback is asked for FIRST and the view
        // is paused once it has answered, with a timeout so a GL thread that never answers cannot
        // keep the GPU awake in the background.
        viewPausePending = true
        ui.removeCallbacks(pauseView)
        ui.postDelayed(pauseView, PAUSE_FALLBACK_MS)
        // A request, never a skipped call: if another save is running this one waits behind it and
        // the view stays running until the queue is empty (see [pauseViewIfDrained]).
        saves.request(SaveReason.IDLE, SaveDest.Working)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingBoardExport?.let { outState.putBundle("board_export",cc.joycreator.joybrush.android.board.BoardExportCoordinator.save(it)) }
        pendingPng?.let { target ->
            outState.putString("png_document", target.documentId)
            outState.putString("png_board", target.boardId)
            outState.putString("png_frame", target.frameId)
            outState.putBoolean("png_paper", target.includePaper)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        boardExports.dismiss()
        boardController?.stop()
        destroyed = true
        paperPreviewRevision++
        paperPreviewWorker.shutdownNow()
        ui.removeCallbacks(idleSave)
        recoveryTimeout?.let { ui.removeCallbacks(it) }
        stopBrushLab()
        // In-flight writes and their request watchdogs finish normally; no new old-screen saves.
        fileIo.execute { recovery.close() }
        saves.drain() // Closed-screen Open requests release their private staging files.
        super.onDestroy()
    }

    // ── the chrome (JB-2.01) ────────────────────────────────────────────────
    //
    // Infinite Painter's lesson, in Joy Creator's clothes: Home, Undo and Redo top-left; the reference pin and ⋯ top-right;
    // a slim tool strip on one edge with the recent-colour hair beside it; everything else is the picture. Guides and
    // Layers join the top bar when their rows land — a button that does nothing is worse than none.

    private fun buildOverlays(): FrameLayout {
        val overlays = FrameLayout(this)
        popovers = Popovers(kit, overlays).apply { topInsetPx = kit.dpi(TOP_RESERVE_DP) - kit.dpi(6f) }
        ring = EyedropperRingView(this)

        // The drag readout sits under everything else, in the middle of the screen.
        hud = ValueHud(kit)
        overlays.addView(hud, FrameLayout.LayoutParams(MATCH, MATCH))

        // ── the top bar ──
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val home = TopButton(kit, JbIcon.HOME, "Home — your drawing is kept").apply { setOnClickListener { finish() } }
        undoBtn = TopButton(kit, JbIcon.UNDO, "Undo (or tap with two fingers)").apply { setOnClickListener { canvas.undo() } }
        redoBtn = TopButton(kit, JbIcon.REDO, "Redo (or tap with three fingers)").apply { setOnClickListener { canvas.redo() } }
        guidesBtn = TopButton(kit, JbIcon.GUIDES, "Guides — grid, perspective, ruler").apply { setOnClickListener { guidesPanel(this) } }
        pinBtn = TopButton(kit, JbIcon.PIN, "Pin a reference picture — hold for its options").apply {
            setOnClickListener { pinTapped() }
            setOnLongClickListener { referenceMenu(); true }
        }
        layersBtn = TopButton(kit, JbIcon.LAYERS, "Layers").apply { setOnClickListener { setColumnOpen(!columnOpen) } }
        val more = TopButton(kit, JbIcon.MORE, "More — save a copy, open, smoothing, put everything back").apply {
            setOnClickListener { moreMenu(this) }
            // JB-0.06's hidden door moved here from the old close button: hold ⋯ for the pen diagnostics.
            setOnLongClickListener { toggleDiagnostics(); true }
        }
        val touch = kit.dpi(ChromeKit.TOUCH_DP)
        for (b in listOf(home, undoBtn, redoBtn)) topBar.addView(b, LinearLayout.LayoutParams(touch, touch))
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        for (b in listOf(guidesBtn, pinBtn, layersBtn, more)) topBar.addView(b, LinearLayout.LayoutParams(touch, touch))
        topButtons.addAll(listOf(home, undoBtn, redoBtn, guidesBtn, pinBtn, layersBtn, more))

        // "Done" for adjusting guides: a pill at the top centre while the handles are out.
        guideDone = pillButton("Done", "Finish moving the guides") { setAdjustingGuides(false) }.apply { visibility = View.GONE }
        overlays.addView(guideDone, FrameLayout.LayoutParams(WRAP, dp(40), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = kit.dpi(6f)
        })
        overlays.addView(topBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply {
            val m = kit.dpi(6f)
            setMargins(m, m, m, 0)
        })

        // ── the tool strip ──
        strip = ToolStripView(kit, stripHost)
        recentBar = strip.recentBar.apply {
            setOnPick { argb ->
                canvas.colorArgb = argb
                refreshColour()
            }
        }
        strip.swatch.setOnTouchListener(ColourPillTouch())
        strip.edge = placement.edge
        overlays.addView(strip, FrameLayout.LayoutParams(WRAP, WRAP))
        overlays.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) overlays.post { placeStrip() }
        }
        strip.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) strip.post { placeStrip() }
        }

        // ── the layer column (JB-2.04), on the right edge under the top bar ──
        column = LayerColumnView(kit, columnHost)
        column.visibility = View.GONE
        overlays.addView(column, FrameLayout.LayoutParams(column.widthPx, WRAP, Gravity.TOP or Gravity.END).apply {
            topMargin = kit.dpi(TOP_RESERVE_DP)
            bottomMargin = kit.dpi(8f)
        })

        // ── the hidden pen diagnostics (JB-0.06), under the top bar ──
        diag = PenDiagnosticsView(this)
        diagBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        diagBox.addView(diag, LinearLayout.LayoutParams(WRAP, WRAP))
        val copyBtn = pillButton("Copy report", "Copy the pen diagnostics to the clipboard") { copyReport() }
        diagBox.addView(copyBtn, LinearLayout.LayoutParams(WRAP, dp(40)))
        // JB-2.20b: draws a 27-mode test drawing on this screen's GPU and compares it with the export.
        val blendBtn = pillButton("Blend check", "Check that this screen mixes layers the way the export does") {
            toast("Checking all 27 blend modes…")
            canvas.runBlendCheck { toast(it) }
        }
        diagBox.addView(blendBtn, LinearLayout.LayoutParams(WRAP, dp(40)))
        overlays.addView(diagBox, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = kit.dpi(TOP_RESERVE_DP)
        })

        return overlays
    }

    /** What the strip asks of the screen. The strip only reports; the values live in [tools] and the canvas. */
    private val stripHost = object : ToolStripView.Host {
        override fun toolTapped(slot: ToolSlot, anchor: View) {
            // First tap takes the tool up; a tap on the tool already in the hand opens its brushes (Infinite Painter's way).
            if (tools.active == slot) openBrushes(slot) else { tools = tools.activate(slot); applyTool() }
        }

        override fun sizeNow(): Float = tools.current?.sizePx ?: 12f
        override fun opacityNow(): Float = tools.current?.opacity ?: 1f
        override fun zoom(): Float = canvas.view.zoom

        override fun sizeDragged(px: Float, done: Boolean) {
            tools = tools.withSize(px)
            if (done) { hud.hide(); applyTool() } else { hud.showSize(tools.current?.sizePx ?: px, canvas.view.zoom); showStripValues() }
        }

        override fun opacityDragged(value: Float, done: Boolean) {
            tools = tools.withOpacity(value)
            if (done) { hud.hide(); applyTool() } else { hud.showOpacity(canvas.strokeColor, tools.current?.opacity ?: value); showStripValues() }
        }

        override fun sizeTapped(anchor: View) = sizeSlider(anchor)
        override fun opacityTapped(anchor: View) = opacitySlider(anchor)

        override fun stripDropped(cx: Float, cy: Float) {
            val o = overlaysView
            val w = (o.width - o.paddingLeft - o.paddingRight).toFloat()
            val free = freeHeight()
            placement = StripPlacement.dropAt(cx - o.paddingLeft, cy - o.paddingTop - kit.dp(TOP_RESERVE_DP), w, free, strip.height.toFloat())
            strip.edge = placement.edge
            prefs.edit().putString(PREF_STRIP, placement.encode()).apply()
            strip.post { placeStrip() }
        }
    }

    /** The height the strip may travel in: the screen less the top bar and a small bottom margin. */
    private fun freeHeight(): Float {
        val o = overlaysView
        return (o.height - o.paddingTop - o.paddingBottom).toFloat() - kit.dp(TOP_RESERVE_DP) - kit.dp(8f)
    }

    /** Puts the strip where [placement] says: hugging its edge, at its fraction of the free height. */
    private fun placeStrip() {
        if (!::strip.isInitialized || strip.height == 0) return
        val lp = strip.layoutParams as FrameLayout.LayoutParams
        val g = Gravity.TOP or (if (placement.edge == StripPlacement.Edge.LEFT) Gravity.START else Gravity.END)
        val top = (kit.dp(TOP_RESERVE_DP) + placement.topPx(freeHeight(), strip.height.toFloat())).toInt()
        // A strip on the right steps aside for the open layer column rather than sit on it.
        val end = if (placement.edge == StripPlacement.Edge.RIGHT && columnOpen) column.widthPx else 0
        if (lp.gravity != g || lp.topMargin != top || lp.marginEnd != end) {
            lp.gravity = g
            lp.topMargin = top
            lp.marginEnd = end
            strip.layoutParams = lp
        }
    }

    /**
     * The tool in the hand goes to the canvas: its brush at its remembered size and opacity. Saved at once, so a tool is
     * never lost to a force-stop.
     */
    private fun applyTool() {
        val tuned = tunedBrushes()
        canvas.preset = tools.presetFrom(tuned) ?: tuned.firstOrNull()
        strip.showTools(tools.slots.keys, tools.active)
        showStripValues()
        prefs.edit().putString(PREF_TOOLS, tools.encode()).apply()
    }

    private fun showStripValues() {
        val s = tools.current
        strip.showValues(s?.sizePx ?: 12f, canvas.strokeColor, s?.opacity ?: 1f)
    }

    /** The brush drawer, opened on the shelf the tool's brush is on. Picking closes it at once (0 ms). */
    private fun openBrushes(slot: ToolSlot) {
        val current = tools.current?.brushId
        val own = when (slot) {
            ToolSlot.SMUDGE -> BrushShelf.Kind.SMUDGE
            ToolSlot.ERASER -> BrushShelf.Kind.ERASERS
            ToolSlot.BRUSH -> brushes.firstOrNull { it.id == current }?.let { BrushShelf.kindOf(it) } ?: BrushShelf.Kind.ALL
        }
        // A shelf of one is a nearly empty drawer (seen on the Note 9): open on All until the tool's own shelf has a choice.
        val ownCount = BrushShelf.shelves(brushes).firstOrNull { it.first == own }?.second?.size ?: 0
        val start = if (ownCount >= 2) own else BrushShelf.Kind.ALL
        val drawer = BrushDrawerView(kit, tunedBrushes(), start, current, onPick = { picked ->
            tools = tools.pick(picked)
            applyTool()
            popovers.close()
        }, onSettings = { held ->
            // Holding a brush takes it up AND opens its settings, so the sliders and the pen speak about the same brush.
            tools = tools.pick(held)
            applyTool()
            brushes.firstOrNull { it.id == held.id }?.let { openSettings(it) }
        })
        // Full width on a phone (the Note 9 at its dense setting is ~548 dp); capped on a tablet so strokes stay readable.
        popovers.showSheet(drawer, maxWidthDp = 600f, alignEnd = placement.edge == StripPlacement.Edge.RIGHT)
    }

    /** Size on a slider: a log scale, so a 2 px pen and a 400 px wash are both easy to set. */
    private fun sizeSlider(anchor: View) {
        val min = SizeOpacityDrag.MIN_SIZE
        val max = SizeOpacityDrag.MAX_SIZE
        val ratio = Math.log((max / min).toDouble())
        fun toSize(step: Int) = (min * Math.exp(ratio * step / SIZE_STEPS)).toFloat()
        fun toStep(size: Float) = (Math.log((size / min).toDouble()) / ratio * SIZE_STEPS).toInt()
        val start = tools.current?.sizePx ?: 12f
        valueSlider(anchor, "Size", SIZE_STEPS, toStep(start), { step -> sizeLabel(toSize(step)) }) { step, done ->
            tools = tools.withSize(toSize(step))
            if (done) applyTool() else showStripValues()
        }
    }

    private fun opacitySlider(anchor: View) {
        val start = Math.round((tools.current?.opacity ?: 1f) * 100)
        valueSlider(anchor, "Opacity", 100, start, { v -> "${v.coerceAtLeast(1)}%" }) { v, done ->
            tools = tools.withOpacity(v / 100f)
            if (done) applyTool() else showStripValues()
        }
    }

    private fun sizeLabel(px: Float): String = if (px >= 10f) "${Math.round(px)} px" else String.format(Locale.US, "%.1f px", px)

    /** A labelled slider in a popover beside [anchor]; [onValue] hears every move, and `done` when the finger lifts. */
    private fun valueSlider(anchor: View, title: String, max: Int, start: Int, label: (Int) -> String, onValue: (Int, Boolean) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val name = drawerText(title, 13f)
        val value = drawerText(label(start), 12f).apply { typeface = android.graphics.Typeface.MONOSPACE }
        head.addView(name, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(value, LinearLayout.LayoutParams(WRAP, WRAP))
        val seek = SeekBar(this).apply {
            tintSlider(this)
            this.max = max
            progress = start
            kit.label(this, title)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    value.text = label(progress)
                    onValue(progress, false)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    // nothing to do
                }

                override fun onStopTrackingTouch(bar: SeekBar) = onValue(bar.progress, true)
            })
        }
        box.addView(head, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(6), 0, dp(6), 0) })
        box.addView(seek, LinearLayout.LayoutParams(MATCH, dp(40)))
        popovers.show(box, anchor, Popovers.Side.BESIDE, widthDp = 220f)
    }

    // ── the ⋯ menu ──

    private fun moreMenu(anchor: View) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(menuRow("Boards…", "Create or select an Image, Animation or Sprite board") { popovers.close(); boardController?.showMenu() })
        box.addView(menuRow("Save a copy…", "Save a copy of this drawing where you choose") { askWhereToSave() })
        box.addView(menuRow("Open…", "Open a drawing from your files") { askWhichToOpen() })
        box.addView(menuRow("Recent drawings…", "Recover one of the last drawings kept before Open") { recentDrawings(anchor) })
        box.addView(menuRow("Export board…", "Choose a format for the selected board, or the page") { pngOptions(anchor) })

        // Smoothing lives here now: set once, rarely touched.
        val smoothHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val smoothValue = drawerText("${Math.round(canvas.smoothing * 100)}%", 12f).apply { typeface = android.graphics.Typeface.MONOSPACE }
        smoothHead.addView(drawerText("Smoothing", 14f), LinearLayout.LayoutParams(0, WRAP, 1f))
        smoothHead.addView(smoothValue, LinearLayout.LayoutParams(WRAP, WRAP))
        box.addView(smoothHead, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(10), dp(8), dp(10), 0) })
        box.addView(SeekBar(this).apply {
            tintSlider(this)
            max = 100
            progress = Math.round(canvas.smoothing * 100)
            kit.label(this, "Stroke smoothing")
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    canvas.smoothing = progress / 100f
                    // JB-1.05b: only a real move of the slider takes smoothing away from the brush file.
                    canvas.smoothingFromUser = true
                    smoothValue.text = "$progress%"
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    // nothing to do
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    // nothing to do
                }
            })
        }, LinearLayout.LayoutParams(MATCH, dp(40)))

        // Every brush's advanced settings (also: hold the brush in the drawer).
        val inHand = tools.current?.brushId?.let { id -> brushes.firstOrNull { it.id == id } }
        if (inHand != null) {
            box.addView(menuRow("${inHand.name} settings…", "Adjust how this brush behaves, with a live preview; you can keep drawing while it is open") {
                openSettings(inHand)
            })
        }

        box.addView(menuRow("Put everything back", "Move the tool strip and the reference picture back to where they started") { putEverythingBack() })
        // The one destructive row carries the one colour allowed to mean "something is lost", as a dot: red TEXT over a
        // see-through panel was hard to read on the Note 9. Undo brings the drawing back.
        box.addView(menuRow("Clear drawing", "Clear the drawing — Undo brings it back", dot = kit.p.stateDestroy) { canvas.clearCanvas() })
        popovers.show(box, anchor, Popovers.Side.BELOW, widthDp = 240f)
    }

    /**
     * A brush's advanced settings (owner, 2026-09-30): a live preview, then every slider the brush has. The sheet does not
     * catch the canvas. Each move re-applies the brush (the NEXT stroke uses it) and redraws the preview; the positions are
     * saved when a finger lifts off a slider. [brush] is the brush as it SHIPS; its saved positions are laid over it here.
     */
    private fun openSettings(brush: BrushPreset) {
        val preview = JbCanvasView(this).apply {
            // Above the drawing's own surface, below the chrome.
            setZOrderMediaOverlay(true)
            longPressEyedropper = false
            paperArgb = canvas.paperArgb
            colorArgb = canvas.strokeColor
        }
        fun previewBrush(): BrushPreset {
            val tuned = BrushTuning.apply(brush, tuning)
            // At the size the tool in hand has for it, so the preview is the stroke the pen will make.
            val size = tools.current?.takeIf { it.brushId == brush.id }?.sizePx ?: tuned.size.base
            return cc.joycreator.joybrush.core.chrome.ToolMemory.sized(tuned, size, tuned.opacity.base)
        }
        val redraw = Runnable {
            if (preview.width > 0 && preview.height > 0) {
                preview.preset = previewBrush()
                preview.replaceWithStrokes(BrushPreviewStrokes.strokes(preview.width.toFloat(), preview.height.toFloat()))
            }
        }
        fun redrawSoon() {
            preview.removeCallbacks(redraw)
            preview.postDelayed(redraw, PREVIEW_DEBOUNCE_MS)
        }
        preview.onReady = { redrawSoon() }
        fun save() = prefs.edit().putString(PREF_TUNING, BrushTuning.encode(tuning)).apply()
        val view = BrushSettingsView(kit, brush, tuning[brush.id] ?: emptyMap(), preview,
            onChange = { positions, done ->
                tuning = tuning + (brush.id to positions)
                applyTool()
                redrawSoon()
                if (done) save()
            },
            onReset = {
                tuning = tuning - brush.id
                applyTool()
                redrawSoon()
                save()
            },
            onTest = {
                // Above the sheet, which covers the lower part of the screen.
                val w = canvas.width.toFloat()
                val h = canvas.height.toFloat()
                val left = w * 0.05f
                val top = h * 0.06f
                val strokes = TuftTestSheet.strokes(w * 0.9f, h * 0.36f)
                    .map { s -> s.map { it.copy(x = it.x + left, y = it.y + top) } }
                canvas.drawStrokes(strokes)
            },
            onDone = { popovers.close() },
        )
        // The pen dot and the curves' markers follow the pen on the drawing and on the preview alike.
        val reading: (Float, Float, Float) -> Unit = { p, t, o -> view.showReading(p, t, o) }
        canvas.onPenReading = reading
        preview.onPenReading = reading
        popovers.showSheet(view, maxWidthDp = 600f, alignEnd = placement.edge == StripPlacement.Edge.RIGHT,
            onClosed = { canvas.onPenReading = null }, modal = false)
    }

    private fun menuRow(text: String, label: String, dot: Int? = null, action: () -> Unit): View =
        drawerText(text, 14f).apply {
            gravity = Gravity.CENTER_VERTICAL
            if (dot != null) {
                val d = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dot)
                    setSize(dp(7), dp(7))
                }
                setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null)
                compoundDrawablePadding = dp(8)
            }
            setPadding(dp(10), 0, dp(10), 0)
            minHeight = dp(40)
            kit.label(this, label)
            background = pressWash()
            setOnClickListener {
                popovers.close()
                action()
            }
        }

    private fun drawerText(text: String, sp: Float): TextView = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(kit.p.drawerInk)
    }

    /** Sliders in the chrome: a drawer-ink thumb on a faint track, as the mockup drew them — never the platform's accent. */
    private fun tintSlider(s: SeekBar) {
        val ink = android.content.res.ColorStateList.valueOf(kit.p.drawerInk)
        s.thumbTintList = ink
        s.progressTintList = ink
        s.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(kit.ink(0.25f))
    }

    /** A pressed row washes with drawer ink at 20%; idle rows are bare. */
    private fun pressWash(): android.graphics.drawable.Drawable = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
            cornerRadius = kit.dp(10f)
            setColor(kit.ink(0.2f))
        })
    }

    /** Owner decision 5: everything movable, and one way back. */
    private fun putEverythingBack() {
        placement = StripPlacement.DEFAULT
        strip.edge = placement.edge
        prefs.edit().putString(PREF_STRIP, placement.encode()).apply()
        strip.post { placeStrip() }
        reference.putBack()
    }

    /** Four fingers: the chrome fades out, or back in (170 ms). The drawing and the pinned reference are never touched. */
    private fun toggleChrome() {
        chromeShown = !chromeShown
        popovers.close()
        if (chromeShown) boardController?.resume() else boardController?.stop()
        val views = if (columnOpen) listOf<View>(topBar, strip, hairline, column, boardOverlay) else listOf<View>(topBar, strip, hairline, boardOverlay)
        for (v in views) {
            v.animate().cancel()
            if (chromeShown) {
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(170L).start()
            } else {
                v.animate().alpha(0f).setDuration(170L).withEndAction { if (!chromeShown) v.visibility = View.GONE }.start()
            }
        }
    }

    // ── layers (JB-2.04): the column, its panel, the thumbnails ──

    private val columnHost = object : LayerColumnView.Host {
        override fun openPaper(anchor: View) = openPaperSheet()

        override fun addLayer() {
            canvas.addLayer()
        }

        override fun selectLayer(id: String) = canvas.selectLayer(id)
        override fun openLayer(id: String, anchor: View) = layerPanel(id, anchor)
        override fun moveLayer(id: String, toIndex: Int) = canvas.moveLayer(id, toIndex)

        override fun setLayerHeld(boardId: String, layerId: String, held: Boolean) {
            if(boardController?.selectedBoardId != boardId) return
            boardController?.stopPreview()
            canvas.editBoards { doc ->
                if(boardController?.selectedBoardId != boardId) throw cc.joycreator.joybrush.core.doc.DocException("select that Animation board again")
                cc.joycreator.joybrush.core.doc.RegionDocumentOps.setHeld(doc,boardId,layerId,held)
            }
        }

        /** JB-2.23 Decision 9: the brush goes to the mask only because the person tapped the mask. */
        override fun maskTapped(id: String) {
            if (canvas.activeLayerId != id) canvas.selectLayer(id)
            val on = !canvas.editingMask
            canvas.setEditingMask(on)
            if (on) toast("Painting on the mask: dark hides, light shows")
        }
    }

    private fun openPaperSheet() {
        popovers.close()
        val before = canvas.documentPaper
        lateinit var sheet: PaperSheetView
        sheet = PaperSheetView(kit, before, PaperResources.catalogue, object : PaperSheetView.Host {
            override fun apply(paper: Paper) {
                if (paperSheet !== sheet || destroyed) return
                canvas.applyDocumentPaper(paper)
                noteChange()
            }
            override fun pickColour(tint: Boolean, current: Int, onPicked: (Int?) -> Unit) {
                val start = canvas.documentPaper
                var committed = false
                paperPickerRevert = { canvas.applyDocumentPaper(start); noteChange() }
                ColorPickerDialog.show(this@JoyBrushActivity, if(tint) "Paper tint" else "Paper colour", current, false,
                    { live -> if (live != null && paperSheet === sheet && !destroyed) onPicked(live) },
                    { picked ->
                        if (paperSheet === sheet && !destroyed) {
                            committed = true
                            paperPickerRevert = null
                            onPicked(picked)
                        }
                    }, null, {
                        if (!committed && paperSheet === sheet && !destroyed) paperPickerRevert?.invoke()
                        paperPickerRevert = null
                    })
            }
            override fun close() { popovers.close() }
        })
        paperSheet = sheet
        popovers.showSheet(sheet, maxWidthDp = PaperSheetView.MAX_WIDTH_DP,
            alignEnd = placement.edge == StripPlacement.Edge.RIGHT, modal = false, onClosed = {
                paperPickerRevert?.invoke()
                paperPickerRevert = null
                paperSheet = null
                paperPreviewRevision++
                canvas.commitPaperVisit(before)
                prefs.edit().putString(PREF_NEW_PAPER, Json.encodeToString(Paper.serializer(), canvas.documentPaper)).apply()
                refreshPaper(canvas.documentPaper)
            })
        refreshPaper(before)
    }

    /** One worker owns the bounded cache; obsolete slider/closed-sheet work never reaches views. */
    private fun refreshPaper(p: Paper) {
        if (destroyed || !::column.isInitialized) return
        val sheet = paperSheet
        sheet?.refresh(p)
        val requests = sheet?.previewRequests().orEmpty()
        val revision = ++paperPreviewRevision
        val resolved = PaperState.resolve(p, PaperResources.catalogue)
        paperPreviewWorker.execute {
            if (destroyed || revision != paperPreviewRevision) return@execute
            try {
                // Square, because LayerColumnView's paper swatch is a circle and PaperPreviews
                // documents "the UI clips a returned square to a circle". 64x32 stretched the
                // texture 2:1 inside the circle. Caught by the brush specialist.
                val swatch = paperBitmap(paperPreviews.crop(resolved, 64), 64, 64)
                ui.post {
                    if (!destroyed && revision == paperPreviewRevision) column.setPaperPreview(swatch)
                }
                for (request in requests) {
                    if (destroyed || revision != paperPreviewRevision) break
                    val bitmap = paperBitmap(paperPreviews.crop(request.paper,request.size),request.size,request.size)
                    ui.post {
                        if (!destroyed && revision == paperPreviewRevision && paperSheet === sheet)
                            sheet?.setPreview(request,bitmap)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("JoyBrushPaper", "Paper preview unavailable", e)
            }
        }
    }

    private fun paperBitmap(bytes: ByteArray, w: Int, h: Int): Bitmap {
        val argb = IntArray(w*h) { i ->
            val j=i*4
            ((bytes[j+3].toInt() and 255) shl 24) or ((bytes[j].toInt() and 255) shl 16) or
                ((bytes[j+1].toInt() and 255) shl 8) or (bytes[j+2].toInt() and 255)
        }
        return Bitmap.createBitmap(argb,w,h,Bitmap.Config.ARGB_8888)
    }

    private fun setColumnOpen(open: Boolean) {
        columnOpen = open
        column.visibility = if (open && chromeShown) View.VISIBLE else View.GONE
        layersBtn.on = open
        prefs.edit().putBoolean(PREF_LAYERS_OPEN, open).apply()
        if (open) {
            layersChanged(canvas.layers)
        }
        strip.post { placeStrip() }
        checkIcons()
    }

    /** The stack moved: the column shows it and its pictures are re-drawn shortly. */
    private fun layersChanged(stack: LayerStack) {
        canvas.maxLayers = budget()
        updateLayerPreviewAspect(boardController?.selectedBounds)
        updateAnimationLayerMarkers()
        column.show(stack, canvas.maxLayers, canvas.editingMask)
        refreshThumbs()
    }

    private fun updateAnimationLayerMarkers() {
        if(::column.isInitialized) column.setAnimationBoard(canvas.boardDocument,boardController?.selectedBoardId)
    }

    private fun updateLayerPreviewAspect(bounds: cc.joycreator.joybrush.core.doc.RectPx?) {
        val width = bounds?.w ?: canvas.pageWidth
        val height = bounds?.h ?: canvas.pageHeight
        if (width > 0 && height > 0) column.pageAspect = width.toFloat() / height
    }

    /** How many layers this phone may hold, from its RAM and the page (core's [LayerBudget]). */
    private fun budget(): Int {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return LayerBudget.maxLayers(info.totalMem, canvas.pageWidth, canvas.pageHeight)
    }

    private fun refreshThumbs() {
        if (!columnOpen || thumbsPending) return
        thumbsPending = true
        ui.postDelayed(thumbsNow, THUMBS_AFTER_MS)
    }

    private val thumbsNow = Runnable {
        thumbsPending = false
        val (w, h) = column.thumbSize()
        val revision = boardThumbnailRevision
        val bounds = boardController?.selectedBounds
        canvas.layerThumbnails(canvas.layers.layers.map { it.id }, w, h, bounds) { pixels ->
            if (revision != boardThumbnailRevision || destroyed) return@layerThumbnails
            val bitmaps = pixels.mapValues { (_, argb) -> Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888) }
            column.setThumbnails(bitmaps)
        }
        // And the masks, keyed back to their layers (JB-2.23).
        val masked = canvas.layers.layers.filter { it.hasMask }.map { it.id }
        if (masked.isNotEmpty()) canvas.layerThumbnails(masked.map { maskStoreId(it) }, w, h, bounds) { pixels ->
            if (revision != boardThumbnailRevision || destroyed) return@layerThumbnails
            column.setMaskThumbnails(pixels.entries.associate { (store, argb) ->
                store.removeSuffix(maskStoreId("")) to Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
            })
        }
    }

    /**
     * One layer's panel, beside its cell: its name, its opacity, its blend mode, and what can be done to it. Delete is the
     * one destructive row and wears the red dot; Undo brings a deleted layer back, pixels and all.
     */
    private fun layerPanel(id: String, anchor: View) {
        val layer = canvas.layers[id] ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val name = android.widget.EditText(this).apply {
            setText(layer.name)
            setSingleLine()
            textSize = 14f
            setTextColor(kit.p.drawerInk)
            background = null
            setPadding(dp(8), 0, dp(8), 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            kit.label(this, "Layer name")
            setOnEditorActionListener { v, _, _ ->
                canvas.renameLayer(id, v.text.toString())
                v.clearFocus()
                false
            }
            setOnFocusChangeListener { v, has -> if (!has) canvas.renameLayer(id, (v as android.widget.EditText).text.toString()) }
        }
        box.addView(name, LinearLayout.LayoutParams(MATCH, dp(40)))

        // Opacity: shown live while the finger is on it, ONE undo step when it lets go.
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val value = drawerText("${Math.round(layer.opacity * 100)}%", 12f).apply { typeface = android.graphics.Typeface.MONOSPACE }
        head.addView(drawerText("Opacity", 13f), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(value, LinearLayout.LayoutParams(WRAP, WRAP))
        box.addView(head, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(8), dp(4), dp(8), 0) })
        var before: LayerStack? = null
        box.addView(SeekBar(this).apply {
            tintSlider(this)
            max = 100
            progress = Math.round(layer.opacity * 100)
            kit.label(this, "Layer opacity")
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    value.text = "$progress%"
                    canvas.previewLayerOpacity(id, progress / 100f)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    before = canvas.layers
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    canvas.commitLayerOpacity(before ?: canvas.layers, id, bar.progress / 100f)
                    before = null
                }
            })
        }, LinearLayout.LayoutParams(MATCH, dp(40)))

        box.addView(menuRow("Blend: ${BlendNames.name(layer.blend)}  ›", "Choose how this layer mixes with the ones below") {
            blendList(id, column.cellFor(id) ?: anchor)
        })
        // JB-2.23: the mask, and the clip.
        if (!layer.hasMask) {
            box.addView(menuRow("Add mask", "Add a mask to this layer — paint dark on it to hide, light to show") {
                canvas.selectLayer(id)
                canvas.addMask(id)
                toast("Painting on the mask: dark hides, light shows")
            })
        } else {
            val onMask = canvas.editingMask && canvas.activeLayerId == id
            box.addView(menuRow(if (onMask) "Paint on the layer" else "Paint on the mask",
                if (onMask) "Go back to painting the layer's colours" else "Paint the mask: dark hides, light shows") {
                canvas.selectLayer(id)
                canvas.setEditingMask(!onMask)
            })
            box.addView(menuRow("Delete mask", "Throw the mask away — Undo brings it back", dot = kit.p.stateDestroy) { canvas.deleteMask(id) })
        }
        if (canvas.layers.indexOf(id) > 0) {
            box.addView(menuRow(if (layer.clip) "Unclip" else "Clip to layer below",
                if (layer.clip) "Show this layer everywhere again" else "Show this layer only where the layer below has paint") {
                canvas.setClip(id, !layer.clip)
            })
        }
        box.addView(menuRow(if (layer.visible) "Hide" else "Show", if (layer.visible) "Hide this layer" else "Show this layer") {
            canvas.setLayerVisible(id, !layer.visible)
        })
        box.addView(menuRow("Duplicate", "Make a copy of this layer above it") { canvas.duplicateLayer(id) })
        box.addView(menuRow("Clear layer", "Empty this layer — Undo brings it back") {
            canvas.selectLayer(id)
            canvas.clearCanvas()
        })
        box.addView(menuRow("Delete layer", "Delete this layer — Undo brings it back", dot = kit.p.stateDestroy) { canvas.deleteLayer(id) })
        popovers.show(box, anchor, Popovers.Side.BESIDE, widthDp = 220f)
    }

    /** All 27 blend modes, in the groups painters know, the current one ringed. Picking one is ONE undo step. */
    private fun blendList(id: String, anchor: View) {
        val current = canvas.layers[id]?.blend ?: BlendMode.NORMAL
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((group, modes) in BlendNames.GROUPS) {
            list.addView(drawerText(group.uppercase(Locale.ROOT), 10f).apply {
                setTextColor(kit.p.drawerDim)
                letterSpacing = 0.12f
                setPadding(dp(10), dp(8), dp(10), dp(2))
            })
            for (m in modes) {
                val row = menuRow(BlendNames.name(m), "Blend mode ${BlendNames.name(m)}") { canvas.setLayerBlend(id, m) }
                if (m == current) row.background = GradientDrawable().apply {
                    cornerRadius = kit.dp(10f)
                    setColor(kit.ink(0.10f))
                    setStroke(kit.dpi(1.5f), kit.p.stateSelected)
                }
                list.addView(row)
            }
        }
        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        val maxH = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        val holder = FrameLayout(this)
        holder.addView(scroll, FrameLayout.LayoutParams(MATCH, WRAP))
        holder.layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
        scroll.layoutParams = FrameLayout.LayoutParams(MATCH, maxH)
        popovers.show(holder, anchor, Popovers.Side.BESIDE, widthDp = 200f)
    }

    // ── guides (JB-2.12) ──

    /** The guides changed: the overlay draws them, the canvas snaps to them, the app remembers them. */
    private fun guidesChanged() {
        guideOverlay.settings = guides
        canvas.snapTo = if (guides.snapEnabled) guides.all() else emptyList()
        guidesBtn.on = !guides.isEmpty
        prefs.edit().putString(PREF_GUIDES, guides.encode()).apply()
        if (guides.isEmpty) setAdjustingGuides(false)
    }

    /** The handles out (drag the perspective points and the ruler's ends) and a Done pill; or put away. */
    private fun setAdjustingGuides(on: Boolean) {
        val can = on && (guides.perspective != null || guides.tracers.isNotEmpty())
        guideOverlay.adjusting = can
        guideDone.visibility = if (can) View.VISIBLE else View.GONE
    }

    /** Where the screen's own corners are on the page, so a new guide lands where the person is looking. */
    private fun screenOnPage(fx: Float, fy: Float): Pt {
        val (x, y) = canvas.view.screenToDoc(canvas.width * fx, canvas.height * fy)
        return Pt(x.toDouble(), y.toDouble())
    }

    /** A perspective guide with [n] vanishing points, placed the way painters start: on a horizon 40% down the screen. */
    private fun perspectiveOf(n: Int): Guide.Perspective = Guide.Perspective(when (n) {
        1 -> listOf(screenOnPage(0.5f, 0.4f))
        2 -> listOf(screenOnPage(-0.4f, 0.4f), screenOnPage(1.4f, 0.4f))
        else -> listOf(screenOnPage(-0.4f, 0.4f), screenOnPage(1.4f, 0.4f), screenOnPage(0.5f, 2.2f))
    })

    private fun guidesPanel(anchor: View) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val g = guides
        box.addView(menuRow(if (g.grid != null) "Grid  ✓" else "Grid", "A square grid over the drawing") {
            guides = guides.copy(grid = if (guides.grid != null) null else GuideSettings.DEFAULT_GRID)
            guidesChanged()
        })
        g.grid?.let { grid ->
            // The grid's size, on a log scale from 10 to 1000 document px.
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val value = drawerText("${Math.round(grid.spacing)} px", 12f).apply { typeface = android.graphics.Typeface.MONOSPACE }
            head.addView(drawerText("Grid size", 13f), LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(value, LinearLayout.LayoutParams(WRAP, WRAP))
            box.addView(head, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(10), 0, dp(10), 0) })
            fun toPx(p: Int) = 10.0 * Math.pow(100.0, p / 100.0)
            box.addView(SeekBar(this).apply {
                tintSlider(this)
                max = 100
                progress = (Math.log10(grid.spacing / 10.0) / 2.0 * 100).toInt().coerceIn(0, 100)
                kit.label(this, "Grid size")
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val px = Math.round(toPx(progress)).toDouble()
                        value.text = "${px.toInt()} px"
                        guides = guides.copy(grid = guides.grid?.copy(spacing = px))
                        guideOverlay.settings = guides
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) {
                        // nothing to do
                    }

                    override fun onStopTrackingTouch(bar: SeekBar) = guidesChanged()
                })
            }, LinearLayout.LayoutParams(MATCH, dp(40)))
        }
        box.addView(menuRow(if (g.isometric != null) "Isometric  ✓" else "Isometric", "An isometric grid: lines at 30° either way and upright") {
            guides = guides.copy(isometric = if (guides.isometric != null) null else Guide.Isometric(60.0))
            guidesChanged()
        })
        // Perspective: off, one, two or three points. Choosing one puts the handles out to place them.
        val now = g.perspective?.vanishingPoints?.size ?: 0
        for (n in 1..3) {
            val name = "Perspective, $n point" + (if (n > 1) "s" else "")
            box.addView(menuRow(if (now == n) "$name  ✓" else name, "$name — drag the points where you want them") {
                guides = guides.copy(perspective = if (now == n) null else perspectiveOf(n))
                guidesChanged()
                if (now != n) setAdjustingGuides(true)
            })
        }
        val hasRuler = g.tracers.any { it is Guide.Ruler }
        box.addView(menuRow(if (hasRuler) "Ruler  ✓" else "Ruler", "A straight edge to draw along — drag its ends") {
            guides = if (hasRuler) guides.copy(tracers = guides.tracers.filterNot { it is Guide.Ruler })
            else guides.copy(tracers = guides.tracers + Guide.Ruler(screenOnPage(0.2f, 0.5f), screenOnPage(0.8f, 0.5f)))
            guidesChanged()
            if (!hasRuler) setAdjustingGuides(true)
        })
        if (!g.isEmpty) {
            box.addView(menuRow(if (g.snapEnabled) "Snap to guides  ✓" else "Snap to guides",
                "Pull strokes onto the guides — off keeps the lines and lets the pen go free") {
                guides = guides.copy(snapEnabled = !guides.snapEnabled)
                guidesChanged()
            })
            if (g.perspective != null || g.tracers.isNotEmpty()) {
                box.addView(menuRow("Move guides…", "Drag the perspective points and the ruler's ends") { setAdjustingGuides(true) })
            }
            box.addView(menuRow("Clear guides", "Turn every guide off") {
                guides = GuideSettings(snapEnabled = guides.snapEnabled)
                guidesChanged()
            })
        }
        popovers.show(box, anchor, Popovers.Side.BELOW, widthDp = 230f)
    }

    // ── the pinned reference (owner decision 3) ──

    private fun pinTapped() {
        if (reference.bitmap == null) { askForReference(); return }
        val show = reference.visibility != View.VISIBLE
        reference.visibility = if (show) View.VISIBLE else View.GONE
        pinBtn.on = show
        prefs.edit().putBoolean(PREF_REF_SHOWN, show).apply()
        checkIcons()
    }

    private fun referenceMenu() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(menuRow(if (reference.bitmap == null) "Pin a picture…" else "Change picture…", "Choose the reference picture") { askForReference() })
        if (reference.bitmap != null) {
            box.addView(menuRow("Put it back", "Move the reference picture back to the corner") { reference.putBack() })
            box.addView(menuRow("Remove picture", "Unpin the reference picture") { removeReference() })
        }
        popovers.show(box, pinBtn, Popovers.Side.BELOW, widthDp = 200f)
    }

    private fun askForReference() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        launch(intent, REQUEST_REFERENCE)
    }

    /** A picture was chosen: kept by its address (not copied), so it survives the screen closing but costs no space. */
    private fun pinReference(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // A provider that will not let us keep it still shows it now; it just will not come back next visit.
        }
        loadReference(uri, null, shown = true)
        prefs.edit().putString(PREF_REF_URI, uri.toString()).remove(PREF_REF_PLACE).putBoolean(PREF_REF_SHOWN, true).apply()
    }

    private fun restoreReference() {
        val uri = prefs.getString(PREF_REF_URI, null)?.let { Uri.parse(it) } ?: return
        val place = prefs.getString(PREF_REF_PLACE, null)?.split(' ')?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 9 }?.toFloatArray()
        loadReference(uri, place, shown = prefs.getBoolean(PREF_REF_SHOWN, true), quietIfGone = true)
    }

    /** Decoded on the writer thread, at most [REFERENCE_MAX_PX] on its long side, turned the right way up. */
    private fun loadReference(uri: Uri, place: FloatArray?, shown: Boolean, quietIfGone: Boolean = false) {
        fileIo.execute {
            val bmp = try { decodeReference(uri) } catch (e: Exception) { null }
            ui.post {
                if (bmp == null) {
                    if (!quietIfGone) toast("That picture could not be opened")
                    else removeReference()
                    return@post
                }
                reference.setPicture(bmp, place)
                reference.visibility = if (shown) View.VISIBLE else View.GONE
                pinBtn.on = shown
                checkIcons()
            }
        }
    }

    private fun decodeReference(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val long = maxOf(info.size.width, info.size.height)
                if (long > REFERENCE_MAX_PX) {
                    val s = REFERENCE_MAX_PX.toFloat() / long
                    decoder.setTargetSize(Math.max(1, Math.round(info.size.width * s)), Math.max(1, Math.round(info.size.height * s)))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= REFERENCE_MAX_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun saveReferencePlace() {
        prefs.edit().putString(PREF_REF_PLACE, reference.placement().joinToString(" ")).apply()
    }

    private fun removeReference() {
        prefs.getString(PREF_REF_URI, null)?.let { old ->
            try {
                contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Never held, or already gone: nothing to give back.
            }
        }
        reference.setPicture(null, null)
        pinBtn.on = false
        prefs.edit().remove(PREF_REF_URI).remove(PREF_REF_PLACE).remove(PREF_REF_SHOWN).apply()
    }

    // ── the top icons read the picture behind them (owner, 2026-09-30) ──

    /**
     * Asks for the top icons to re-read what is behind them, at most once every [ICON_CHECK_MS]: a pan sends dozens of
     * moves a second, and one read per settle is plenty to keep a black icon off black paint.
     */
    private fun checkIcons() {
        if (iconCheckPending) return
        iconCheckPending = true
        ui.postDelayed(iconCheck, ICON_CHECK_MS)
    }

    private val iconCheck = Runnable {
        iconCheckPending = false
        readIcons()
    }

    /**
     * Nine points under each icon (a 3 × 3 grid across its face), read from the screen after the next frame, with the
     * pinned reference answering for any point it covers; then [IconContrast] picks each icon's ink.
     */
    private fun readIcons() {
        val buttons = topButtons.filter { it.isShown && it.width > 0 }
        if (buttons.isEmpty()) return
        val here = IntArray(2)
        val there = IntArray(2)
        canvas.getLocationOnScreen(there)
        val step = kit.dp(7f)
        val pts = FloatArray(buttons.size * GRID * GRID * 2)
        var k = 0
        for (b in buttons) {
            b.getLocationOnScreen(here)
            val cx = here[0] - there[0] + b.width / 2f
            val cy = here[1] - there[1] + b.height / 2f
            for (j in -1..1) for (i in -1..1) {
                pts[k++] = cx + i * step
                pts[k++] = cy + j * step
            }
        }
        canvas.sampleScreen(pts) { colours ->
            val per = GRID * GRID
            for ((n, b) in buttons.withIndex()) {
                val samples = IntArray(per) { m ->
                    val idx = n * per + m
                    reference.colourAt(pts[2 * idx], pts[2 * idx + 1]) ?: colours[idx]
                }
                b.ink = IconContrast.inkFor(samples, if (iconsInked) b.ink else null)
            }
            iconsInked = true
        }
    }

    /** The strip's swatch and opacity button show the colour the next stroke will use; the hair outlines it. */
    private fun refreshColour() {
        showStripValues()
    }

    /** The Studio's own colour picker (JB-2.03a Decision 1). This Activity wears the Studio's theme, so its bottom sheet looks the same. */
    /**
     * The small colour wheel beside the strip (the mockup's colour popover): the Studio's own hue ring and triangle, the
     * before/after pair under it, and "More colours…" for the Studio's full picker. The drawing stays in view — the full
     * picker's sheet dims it, which is fine for a careful choice and wrong for a quick one.
     */
    private fun openColourPicker() {
        val before = canvas.strokeColor
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val pair = ColourPairView(kit, before) {
            canvas.colorArgb = before
            refreshColour()
            popovers.close()
        }
        val hsv = FloatArray(3)
        Color.colorToHSV(before, hsv)
        val wheel = ColorWheelView(this).apply {
            setHsb(hsv[0], hsv[1], hsv[2])
            kit.label(this, "Colour wheel — turn the ring for the hue, move in the triangle for how rich and how light")
            setListener { h, s, b ->
                val c = Color.HSVToColor(floatArrayOf(h, s, b))
                canvas.colorArgb = c
                pair.now = c
                refreshColour()
            }
        }
        box.addView(wheel, LinearLayout.LayoutParams(dp(WHEEL_DP), dp(WHEEL_DP)))
        box.addView(pair, LinearLayout.LayoutParams(dp(WHEEL_DP) - dp(24), dp(22)).apply { topMargin = dp(8) })
        box.addView(menuRow("More colours…", "Open the full colour picker: sliders, hex, and saved colours") { openFullColourPicker() })
        popovers.show(box, strip.swatch, Popovers.Side.BESIDE)
    }

    /** The Studio's full picker (JB-2.03a Decision 1), one press away from the wheel. */
    private fun openFullColourPicker() {
        // Cancel goes back to the colour in hand when the full picker opened, after everything its live preview tried.
        val start = canvas.strokeColor
        ColorPickerDialog.show(this, "Colour", start, false,
            { live -> if (live != null) { canvas.colorArgb = live; refreshColour() } },
            { picked ->
                canvas.colorArgb = picked ?: start
                refreshColour()
            })
    }

    /**
     * Tap = the picker. Press and drag off the swatch = the eyedropper (JB-2.03a Decision 2): the ring follows the finger over the
     * drawing, lifting on the drawing takes that colour, and dragging back onto the swatch and lifting changes nothing.
     */
    private inner class ColourPillTouch : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private val loc = IntArray(2)

        private fun onCanvas(ev: MotionEvent): Pair<Float, Float> {
            canvas.getLocationOnScreen(loc)
            return Pair(ev.rawX - loc[0], ev.rawY - loc[1])
        }

        private fun overPill(v: View, ev: MotionEvent): Boolean =
            Eyedropper.overPill(ev.x, ev.y, v.width, v.height)

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; dragging = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val far = Eyedropper.DRAG_OFF_DP * resources.displayMetrics.density
                    if (!dragging && Math.hypot((ev.rawX - downX).toDouble(), (ev.rawY - downY).toDouble()) > far) dragging = true
                    if (dragging) {
                        val (x, y) = onCanvas(ev)
                        // A finger gets the big, lifted ring (owner, 2026-09-30); a pen samples under its tip.
                        canvas.dragEyedropMove(x, y, finger = ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) canvas.dragEyedropEnd(take = !overPill(v, ev))
                    else { v.performClick(); openColourPicker() }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) canvas.dragEyedropEnd(take = false)
                    dragging = false
                }
            }
            return true
        }
    }

    private fun updateHistoryButtons(canUndo: Boolean, canRedo: Boolean) {
        undoBtn.isEnabled = canUndo
        redoBtn.isEnabled = canRedo
    }

    // ── save, open and "save a copy" (JB-0.08b) ─────────────────────────────

    /**
     * The working file: `getExternalFilesDir("joybrush")/current.joybrush`.
     *
     * Its folder goes when the app is uninstalled, which is why "Save a copy…" exists and why
     * documents move to the vault later. Never `adb uninstall` this app (START_HERE rule 3).
     */
    private fun workingFile(): File? {
        val dir = getExternalFilesDir(WORKING_DIR) ?: return null
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return File(dir, WORKING_FILE)
    }

    /** Something changed on the canvas: (re)start the 30 s clock before the autosave. */
    private fun noteChange() {
        changes += 1
        ui.removeCallbacks(idleSave)
        ui.postDelayed(idleSave, AUTOSAVE_AFTER_MS)
    }

    /**
     * One save, started by the queue: a snapshot on the GL thread, then the write on the writer
     * thread, then [finished] back on the UI thread — exactly once, because [answer] is guarded.
     *
     * A failure says so in words and never claims a success it did not have. The copy path's words
     * are R11's own: a person who picked a destination and watched it fail has to be told that their
     * drawing survived FIRST and the reason second.
     */
    private fun beginSave(dest: SaveDest, finished: (String?) -> Unit) {
        if (destroyed) {
            if (dest is SaveDest.Open) dest.selection.close()
            finished("the drawing screen has closed")
            return
        }
        if (dest is SaveDest.Working && !workingSaveAllowed) {
            finished("the previous drawing needs recovery before autosave can replace it")
            return
        }
        if (dest is SaveDest.Open) {
            replacingDrawing = true
            overlaysView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        // Read on the UI thread, before the snapshot: only a save that started at this count and
        // found the count unchanged when it finished has written everything, so a stroke made while
        // it was writing is still counted and still waiting for its own autosave.
        val from = changes
        var answered = false
        lateinit var watchdog: Runnable
        val answer: (String?) -> Unit = { problem ->
            if (!answered) {
                answered = true
                ui.removeCallbacks(watchdog)
                if (dest is SaveDest.Open) {
                    dest.selection.close()
                    replacingDrawing = false
                    overlaysView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                }
                if (problem != null && !destroyed) {
                    toast(
                        when (dest) {
                            is SaveDest.Copy -> "Couldn't save the copy. Your drawing is safe. $problem"
                            is SaveDest.Open -> "Couldn't open the drawing. Your previous drawing was kept. $problem"
                            is SaveDest.Png -> "Couldn't export the PNG. Your drawing is safe. $problem"
                            is SaveDest.BoardExport -> "Couldn't export the board. Your drawing is safe. $problem"
                            is SaveDest.BoardHandoff -> "Couldn't open the board. $problem"
                            is SaveDest.Working -> "The autosave did not work: $problem"
                        },
                    )
                    // A working save that failed is still owed: ask again after the usual quiet.
                    if (dest is SaveDest.Working) {
                        ui.removeCallbacks(idleSave)
                        ui.postDelayed(idleSave, AUTOSAVE_AFTER_MS)
                    }
                } else if (problem == null && dest is SaveDest.Copy && !destroyed) {
                    // A COPY never touches the working file, so it says nothing about whether the
                    // working file is up to date: `changes` is left exactly as it was.
                    toast("Copy saved")
                } else if (problem == null && dest is SaveDest.Png && !destroyed) {
                    toast("PNG exported")
                } else if (problem == null && dest is SaveDest.BoardExport && !destroyed) {
                    toast(if(dest.target.folder) "Board files exported into a new folder" else "Board exported")
                } else if(problem == null && dest is SaveDest.BoardHandoff) {
                    val bundle=dest.bundle
                    if(!destroyed && bundle != null) startActivity(Intent().setClassName(packageName,
                        "com.fadcam.ui.faditor.sprite.JoyBrushBoardImportActivity")
                        .putExtra("joybrush_board_bundle",bundle.absolutePath)
                        .putExtra("joybrush_board_studio",dest.target.format == cc.joycreator.joybrush.core.chrome.BoardExportLayout.Format.STUDIO))
                    else bundle?.deleteRecursively()
                } else if (problem == null && dest is SaveDest.Working && changes == from) {
                    changes = 0
                }
                finished(problem)
                pauseViewIfDrained()
            }
        }
        watchdog = Runnable { answer("the drawing did not answer in time") }
        ui.postDelayed(watchdog, SNAPSHOT_TIMEOUT_MS)

        val file = if (dest is SaveDest.Working) workingFile() else null
        if (dest is SaveDest.Working && file == null) {
            answer("this device has nowhere to keep a working drawing")
            return
        }
        canvas.snapshot({ contents ->
            // A timed-out readback may arrive after another request has started. It owns no write.
            if (answered) return@snapshot
            ui.removeCallbacks(watchdog)
            // The GL thread has done its part. Nothing else is waiting for it, so it can rest now.
            pauseViewIfDrained()
            fileIo.execute {
                val problem = try {
                    when (dest) {
                        is SaveDest.Working -> {
                            JbArchive.save(file!!, contents)
                            recovery.rememberWorking(file)
                        }
                        is SaveDest.Copy -> writeCopy(dest.uri, contents)
                        is SaveDest.Open -> DrawingHistory.preserve(historyDirectory(), contents)
                        is SaveDest.Png -> {
                            // Encode and validate completely before touching the chosen destination.
                            if (contents.doc.id != dest.target.documentId) throw JbArchiveException("the drawing changed; choose the board to export again")
                            val png = CanvasPng.encodeBoard(contents, dest.target.boardId, dest.target.includePaper, dest.target.frameId)
                            val output = contentResolver.openOutputStream(dest.uri, "wt")
                                ?: throw JbArchiveException("the PNG destination could not be opened")
                            output.use { it.write(png) }
                        }
                        is SaveDest.BoardExport -> cc.joycreator.joybrush.android.board.BoardExportCoordinator.write(
                            contentResolver,dest.uri,contents,dest.target,cacheDir)
                        is SaveDest.BoardHandoff -> {
                            val prepared=cc.joycreator.joybrush.androidkit.io.BoardExport.stage(contents,
                                dest.target.copy(format=cc.joycreator.joybrush.core.chrome.BoardExportLayout.Format.SPRITE_SHEET),cacheDir)
                            dest.bundle=prepared.directory
                        }
                    }
                    null
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
                runOnUiThread {
                    if (dest is SaveDest.Open && problem == null && !destroyed && !answered) {
                        // Replacement only follows a successful durable write of the old drawing.
                        // A separate executor task releases this snapshot's pixel arrays BEFORE
                        // decoding the selection. Holding both exceeds the Note 9's 512 MiB heap.
                        fileIo.execute {
                            var readProblem: String? = null
                            val selected = try { dest.selection.read() }
                                catch (_: OutOfMemoryError) {
                                    readProblem = "this device has too little memory for that drawing"
                                    null
                                } catch (e: Exception) {
                                    readProblem = e.message ?: "the selected file could not be read"
                                    null
                                }
                            ui.post {
                                if (answered) return@post
                                if (destroyed || selected == null) {
                                    answer(readProblem ?: "the drawing screen has closed")
                                    return@post
                                }
                                ui.postDelayed(watchdog, 30_000L)
                                showContents(selected) { loaded ->
                                    if (loaded) {
                                        changes = 1
                                        saves.request(SaveReason.IDLE, SaveDest.Working)
                                    }
                                    answer(if (loaded) null else "the selected drawing could not be shown")
                                }
                            }
                        }
                    } else answer(problem)
                }
            }
        // Keep failure bound to this save, even if the GL response arrives after its timeout.
        }, { why -> answer("Could not read the drawing: $why") })
    }

    /**
     * "Save a copy…": the archive is BUILT IN FULL in `cacheDir` first, through the same code path a
     * normal save uses, and only a complete archive is ever streamed to the chosen place (R11). A
     * SAF `Uri` cannot be made atomic — there is no rename — so this does not make the final copy
     * atomic, but a drawing that cannot be written is refused *before* the chosen file is opened at
     * all, and this can never touch the working file. Runs on the writer thread.
     */
    private fun writeCopy(uri: Uri, contents: JbContents) {
        val temp = File(cacheDir, COPY_TEMP)
        try {
            JbArchive.save(temp, contents)
            val out = contentResolver.openOutputStream(uri, "wt")
                ?: throw JbArchiveException("the file you chose could not be opened for writing")
            out.use { stream -> temp.inputStream().use { source -> source.copyTo(stream) } }
        } finally {
            // Whatever happened, the temporary is not left lying around: it is a whole drawing
            // sitting in a cache directory, and the next copy would overwrite it anyway.
            temp.delete()
        }
    }

    /**
     * Pauses the GL thread once the screen is leaving AND no save is waiting for it. Pausing after
     * the first snapshot regardless would strand a second one: a GLSurfaceView holds an event queued
     * before a pause until the next resume, and after a force-stop there is no next resume.
     */
    private fun pauseViewIfDrained() {
        if (viewPausePending && saves.pending == 0) pauseViewNow()
    }

    /**
     * Puts the working file back on the canvas at startup, if there is one and if this screen can
     * show all of it. Read on the writer thread; nothing touches the canvas until it has answered.
     */
    private fun restoreWorkingFile() {
        val file = workingFile()
        if (file == null) { finishStartup(false); return }
        fileIo.execute {
            val read = try {
                DrawingHistory.readWorking(file).also {
                    if (it != null) recovery.rememberWorking(if (it.recoveredBackup) File(file.path + ".bak") else file)
                }
            } catch (e: Throwable) {
                // THROWABLE, not Exception. A working file too large for the heap raises
                // OutOfMemoryError, which is an Error: it sailed past this catch, escaped
                // DrawingHistory.readWorking's own catch, and killed the process from a background
                // thread, so the owner got a crash loop instead of the sentence below. A failed
                // restore must never take the app down — the drawing on disk is left untouched and
                // the screen opens empty, which is recoverable; a dead process is not.
                ui.post {
                    if (!destroyed) toast("Your last drawing could not be opened. Autosave will leave it intact: ${e.message}")
                    finishStartup(false)
                }
                return@execute
            }
            ui.post {
                if (destroyed) { finishStartup(false); return@post }
                if (read == null) finishStartup(true)
                else showContents(read.contents, keepRecoveryCopy = false) { loaded ->
                    if (loaded && read.recoveredBackup) toast("Recovered your drawing from its backup")
                    finishStartup(loaded)
                }
            }
        }
    }

    private fun finishStartup(loaded: Boolean) {
        workingSaveAllowed = loaded
        loadingDrawing = false
        saves.drain()
    }

    private fun historyDirectory(): File = File(
        workingFile()?.parentFile ?: throw JbArchiveException("this device has no drawing folder"), "recent",
    )

    /** "Save a copy…": queued behind anything already running, never dropped (JB-2.15). */
    private fun saveCopyTo(uri: Uri) {
        saves.request(SaveReason.EXPLICIT, SaveDest.Copy(uri))
    }

    /** "Open…": the person picks a file, and it replaces whatever is on the canvas. */
    private fun openFrom(uri: Uri) {
        fileIo.execute {
            val selection = try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw JbArchiveException("the file could not be opened")
                input.use { StagedDrawing.stage(cacheDir, it) }
            } catch (e: Exception) {
                ui.post { if (!destroyed) toast("That drawing could not be opened: ${e.message}") }
                null
            }
            if (selection != null) ui.post { requestOpen(selection) }
        }
    }

    /**
     * Puts a drawing on the canvas, or says why it cannot go there and leaves the drawing that is
     * already there alone. UI thread.
     */
    private fun requestOpen(selection: StagedDrawing) {
        if (destroyed) { selection.close(); return }
        saves.request(SaveReason.EXPLICIT, SaveDest.Open(selection))
    }

    private fun showContents(contents: JbContents, keepRecoveryCopy: Boolean = true, onLoaded: (Boolean) -> Unit = {}) {
        if (destroyed) { onLoaded(false); return }
        try {
            canvas.checkContents(contents)
            val version = ++drawingLoadVersion
            ++recoveryVersion // A selected drawing supersedes any recovery already reading disk.
            recoveryTimeout?.let { ui.removeCallbacks(it) }
            recoveryTimeout = null
            fileIo.execute {
                val problem = try {
                    if (keepRecoveryCopy) recovery.rememberOpened(contents)
                    null
                } catch (e: Exception) { e.message ?: "the recovery copy could not be kept" }
                ui.post {
                    if (destroyed || version != drawingLoadVersion) { onLoaded(false); return@post }
                    if (problem != null) {
                        if (recoveringDrawing) finishGraphicsRecovery()
                        toast("That drawing could not be opened safely: $problem")
                        onLoaded(false)
                        return@post
                    }
                    uploadContents(contents) { loaded ->
                        if (loaded) finishGraphicsRecovery()
                        onLoaded(loaded)
                    }
                }
            }
        } catch (e: JbArchiveException) {
            toast(e.message ?: "that drawing cannot be opened")
            onLoaded(false)
        }
    }

    private fun uploadContents(contents: JbContents, onLoaded: (Boolean) -> Unit) {
        canvas.load(contents) {
            // Loading reports a history change; it is not a new edit.
            changes = 0
            ui.removeCallbacks(idleSave)
            workingSaveAllowed = true
            onLoaded(!destroyed)
        }
    }

    private fun recoverGraphics(documentId: String) {
        if (destroyed || !canvas.needsRecovery) return
        recoveringDrawing = true
        workingSaveAllowed = false
        overlaysView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        ui.removeCallbacks(idleSave)
        canvas.cancelInterruptedStroke()
        val attempt = ++recoveryVersion
        val loadVersion = drawingLoadVersion
        recoveryTimeout?.let { ui.removeCallbacks(it) }
        recoveryTimeout = Runnable {
            if (!destroyed && attempt == recoveryVersion && recoveringDrawing) {
                ++recoveryVersion // A late read/upload cannot claim this timed-out attempt succeeded.
                finishGraphicsRecovery()
                toast("Your saved files are safe. Restoration timed out; close and reopen Joy Brush.")
            }
        }.also { ui.postDelayed(it, 30_000L) }
        toast("Restoring your drawing…")
        // The single file executor places this AFTER every write already in progress.
        fileIo.execute {
            val contents = try { recovery.read(documentId) }
                catch (_: OutOfMemoryError) { null }
                catch (_: Exception) { null }
            ui.post {
                if (destroyed || attempt != recoveryVersion || loadVersion != drawingLoadVersion) return@post
                if (!canvas.needsRecovery) { finishGraphicsRecovery(); return@post }
                if (contents == null) {
                    finishGraphicsRecovery()
                    toast("Your saved files are safe, but the drawing could not be restored. Close and reopen Joy Brush.")
                    return@post
                }
                val unsaved = changes > 0
                try {
                    canvas.checkContents(contents)
                    uploadContents(contents) { loaded ->
                        if (attempt != recoveryVersion) return@uploadContents
                        finishGraphicsRecovery()
                        if (loaded) toast(if (unsaved)
                            "Recovered your last saved drawing. Recent unsaved marks may be missing; undo history restarted."
                            else "Drawing restored; undo history restarted.")
                    }
                } catch (e: JbArchiveException) {
                    finishGraphicsRecovery()
                    toast("The saved drawing was kept, but could not be restored: ${e.message}")
                }
            }
        }
    }

    private fun finishGraphicsRecovery() {
        recoveryTimeout?.let { ui.removeCallbacks(it) }
        recoveryTimeout = null
        recoveringDrawing = false
        overlaysView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        saves.drain()
        pauseViewIfDrained()
    }

    private fun recentDrawings(anchor: View) {
        fileIo.execute {
            val files = try { DrawingHistory.list(historyDirectory()) } catch (_: Exception) { emptyList() }
            ui.post {
                if (destroyed) return@post
                if (files.isEmpty()) { toast("Drawings kept before Open will appear here"); return@post }
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                for (file in files) {
                    val whenSaved = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(file.lastModified()))
                    box.addView(menuRow("Before Open · $whenSaved", "Open the drawing kept on $whenSaved") {
                        fileIo.execute {
                            try {
                                val selection = file.inputStream().use { StagedDrawing.stage(cacheDir, it) }
                                ui.post { requestOpen(selection) }
                            } catch (e: Exception) { ui.post { if (!destroyed) toast("That recent drawing could not be opened: ${e.message}") } }
                        }
                    })
                }
                popovers.show(box, anchor, Popovers.Side.BELOW, widthDp = 260f)
            }
        }
    }

    private fun pngOptions(anchor: View, boardId: String? = boardController?.selectedBoardId) {
        val doc = canvas.boardDocument
        if (doc == null) { canvas.ensureBoardDocument { pngOptions(anchor, boardId) }; return }
        val board = if (boardId == null) doc.boards.firstOrNull { it.kind == cc.joycreator.joybrush.core.doc.BoardKind.CANVAS }
            else doc.boards.firstOrNull { it.id == boardId }
        if (board == null) { toast("That board no longer exists"); return }
        popovers.close()
        boardController?.stopPreview()
        boardExports.show(doc,board.id)
    }

    private fun askWhereToSave() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = JB_MIMETYPE
            putExtra(Intent.EXTRA_TITLE, copyName())
        }
        launch(intent, REQUEST_SAVE_COPY)
    }

    /**
     * "Open…" shows every file, not just the ones a provider happens to have typed as a Joy Brush
     * drawing: a `.joybrush` that arrived through a file manager or a download is usually typed
     * `application/zip` or `application/octet-stream`, and a picker that hides it is a drawing the
     * person cannot get back. Picking the wrong file is harmless — the archive refuses it in words.
     */
    private fun askWhichToOpen() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, OPENABLE_TYPES)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(intent, REQUEST_OPEN)
    }

    /** A device with no document picker at all must say so, not take the screen down with it. */
    private fun launch(intent: Intent, request: Int) {
        try {
            startActivityForResult(intent, request)
        } catch (e: ActivityNotFoundException) {
            if (request == REQUEST_EXPORT_PNG) pendingPng = null
            if (request == REQUEST_BOARD_EXPORT) pendingBoardExport = null
            toast("This device has nowhere to save or open files")
        }
    }

    /**
     * The file picker's answer. This screen is a plain `Activity`, not a `ComponentActivity`, so it
     * has no `registerForActivityResult`; the file picker is the only thing that reaches here, and
     * a request code of its own keeps it from touching anything else that reports back.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val pngTarget = if (requestCode == REQUEST_EXPORT_PNG) pendingPng.also { pendingPng = null } else null
        val boardTarget = if(requestCode == REQUEST_BOARD_EXPORT) pendingBoardExport.also { pendingBoardExport = null } else null
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == REQUEST_OPEN) openFrom(uri)
        if (requestCode == REQUEST_SAVE_COPY) saveCopyTo(uri)
        if (requestCode == REQUEST_REFERENCE) pinReference(uri)
        if (requestCode == REQUEST_EXPORT_PNG) {
            if (pngTarget == null) toast("That export was interrupted; choose the board again")
            else saves.request(SaveReason.EXPLICIT, SaveDest.Png(uri, pngTarget))
        }
        if(requestCode == REQUEST_BOARD_EXPORT) {
            if(boardTarget == null) toast("That export was interrupted; choose the board again")
            else { toast("Preparing board export…"); saves.request(SaveReason.EXPLICIT,SaveDest.BoardExport(uri,boardTarget)) }
        }
    }

    /** `Joy Brush 2026-09-28 2311.joybrush` — the default name the picker opens with. */
    private fun copyName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())
        return "Joy Brush $stamp.joybrush"
    }

    /**
     * Pauses the GL thread, if the screen leaving still owes it one. Called from the snapshot that
     * was asked for on the way out, from the snapshot failing, and from the timeout — never from
     * onPause itself, which is the whole point.
     */
    private fun pauseViewNow() {
        if (!viewPausePending) return
        viewPausePending = false
        ui.removeCallbacks(pauseView)
        canvas.onPause()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ── the hidden pen diagnostics (JB-0.06) ─────────────────────────────────

    private fun toggleDiagnostics() {
        diagShown = !diagShown
        diagBox.visibility = if (diagShown) View.VISIBLE else View.GONE
        if (diagShown) diag.reset()
    }

    private fun copyReport() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Joy Brush pen diagnostics", diag.report()))
    }

    // ── view helpers (the diagnostics panel keeps its plain pills) ──────────

    private fun pillButton(label: String, description: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = pill()
            setPadding(dp(16), 0, dp(16), 0)
            contentDescription = description
            ViewCompat.setTooltipText(this, description)
            setOnClickListener { action() }
        }

    private fun pill(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(40).toFloat()
        setColor(OVERLAY_FILL)
        setStroke(dp(1), OVERLAY_RING)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * The canvas is deliberately full-bleed; only the overlay layer steps aside of the cutout and
     * the (transient) system bars, so no button ever lands under the punch-hole.
     */
    private fun goFullScreen(overlays: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val bars = WindowInsetsControllerCompat(window, window.decorView)
        bars.hide(WindowInsetsCompat.Type.systemBars())
        bars.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        ViewCompat.setOnApplyWindowInsetsListener(overlays) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
    }

    // ── the brush lab (JB-1.21) ───────────────────────────────────────────────
    //
    // A development door, not a feature: `tools/brushlab_push.sh` pushes a brush folder the person
    // is editing on a PC, and the watcher hands every changed one straight to the view. Debuggable
    // builds ONLY, because in any other build this would let a file that reached the device's app
    // folder swap the brush under somebody's hand, invisibly. There is nothing to see in a release
    // build and nothing to turn off.

    private var brushLab: BrushHotReload? = null

    /**
     * Starts watching `<external files>/joybrush/lab` for edited `brush.json` files. Does nothing
     * outside a debuggable build, and nothing if the device will not give us an app folder.
     */
    private fun startBrushLab() {
        if (brushLab != null) return
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val labDir = getExternalFilesDir(BrushHotReload.LAB_DIR) ?: return
        brushLab = BrushHotReload(
            labDir,
            { preset ->
                // Straight onto the view, with no undo step and no GL round trip: the person is
                // mid-session, judging a brush, and a brush is not drawing content.
                canvas.preset = preset
                toast("Reloaded ${preset.name}")
            },
            { file, problems ->
                // The preset is NOT touched here, so the brush that was working is still the one on
                // screen — the whole point of reporting the problem rather than loading half a file.
                toast("Lab brush ${file.parentFile?.name ?: file.name} — ${problems.first()}")
            },
        )
        brushLab?.start()
    }

    /** Stops the watcher. Safe to call when it was never started, which is every release build. */
    private fun stopBrushLab() {
        brushLab?.stop()
        brushLab = null
    }
}
