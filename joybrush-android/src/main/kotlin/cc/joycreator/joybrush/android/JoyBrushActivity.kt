package cc.joycreator.joybrush.android

import android.app.Activity
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
import cc.joycreator.joybrush.android.chrome.JbIcon
import cc.joycreator.joybrush.android.chrome.Popovers
import cc.joycreator.joybrush.android.chrome.ReferenceView
import cc.joycreator.joybrush.android.chrome.ToolStripView
import cc.joycreator.joybrush.android.chrome.TopButton
import cc.joycreator.joybrush.android.chrome.ValueHud
import cc.joycreator.joybrush.androidkit.BrushLibrary
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.diag.PenDiagnosticsView
import cc.joycreator.joybrush.androidkit.io.JB_MIMETYPE
import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.io.JbArchiveException
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.androidkit.lab.BrushHotReload
import cc.joycreator.joybrush.androidkit.tools.Eyedropper
import cc.joycreator.joybrush.androidkit.tools.EyedropperRingView
import com.fadcam.ui.faditor.tools.ColorPickerDialog
import com.fadcam.ui.faditor.tools.ColorRecents
import com.fadcam.ui.faditor.tools.RecentColorsBar
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.chrome.BrushShelf
import cc.joycreator.joybrush.core.chrome.IconContrast
import cc.joycreator.joybrush.core.chrome.StripPlacement
import cc.joycreator.joybrush.core.chrome.ToolMemory
import cc.joycreator.joybrush.core.chrome.ToolSlot
import cc.joycreator.joybrush.core.io.SaveQueue
import cc.joycreator.joybrush.core.tool.SizeOpacityDrag
import cc.joycreator.joybrush.core.io.SaveReason
import cc.joycreator.joybrush.core.io.SaveTarget
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 * timer asks again; a late answer still writes its file, it just is not announced twice.
 */
private const val SNAPSHOT_TIMEOUT_MS = 15_000L

private const val REQUEST_OPEN = 4101
private const val REQUEST_SAVE_COPY = 4102
private const val REQUEST_REFERENCE = 4103

// JB-2.01: where the chrome remembers itself between visits — the strip's place, each tool's brush and size, the
// pinned reference picture.
private const val CHROME_PREFS = "joybrush_chrome"
private const val PREF_STRIP = "strip"
private const val PREF_TOOLS = "tools"
private const val PREF_REF_URI = "reference_uri"
private const val PREF_REF_PLACE = "reference_place"
private const val PREF_REF_SHOWN = "reference_shown"

/** The top bar's height plus its margin: the strip never slides under it. */
private const val TOP_RESERVE_DP = 54f

/** A reference picture is decoded no bigger than this on its long side: a pinned picture never needs more. */
private const val REFERENCE_MAX_PX = 1600

/** How often, at most, the top icons re-read the picture behind them while it moves. */
private const val ICON_CHECK_MS = 120L

/** The icons read a GRID × GRID square of points each. */
private const val GRID = 3

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
    private val topButtons = ArrayList<TopButton>()
    private var iconsInked = false
    private var iconCheckPending = false
    private lateinit var overlaysView: FrameLayout
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
    }

    /**
     * EVERY save goes through this one queue (JB-2.15): a request is an entry that waits its turn,
     * never a flag that can be cleared or a call that can return without doing its work. The three
     * work-loss bugs of the first wiring were one bug — a copy asked for during an autosave was
     * dropped, an undo could eat a save owed by a stroke, and a copy could be taken with the pen
     * down — and all three are the queue's job now. There is deliberately no `saveOwed` boolean.
     */
    private val saves = SaveQueue(object : SaveTarget<SaveDest> {
        override val strokeInProgress: Boolean get() = canvas.strokeInProgress
        override fun start(reason: SaveReason, destination: SaveDest, finished: (String?) -> Unit) {
            beginSave(destination, finished)
        }
    })

    /** The save whose snapshot the view is taking right now, so a failed snapshot can be reported. */
    private var activeSave: ((String?) -> Unit)? = null

    private var viewPausePending = false

    /** The autosave the last change asked for, and the one the screen leaving asks for. */
    private val idleSave = Runnable { if (changes > 0) saves.request(SaveReason.IDLE, SaveDest.Working) }

    /** Pausing the GL thread, once a snapshot has answered or the wait for one is up. */
    private val pauseView = Runnable { pauseViewNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        canvas = JbCanvasView(this)
        kit = ChromeKit(this)
        prefs = getSharedPreferences(CHROME_PREFS, Context.MODE_PRIVATE)
        // Each tool as the person left it; the first shipped brush of each kind on a first visit (JB-1.05b, JB-2.01).
        tools = ToolMemory.decode(prefs.getString(PREF_TOOLS, null), brushes)
        placement = StripPlacement.decode(prefs.getString(PREF_STRIP, null))
        val overlays = buildOverlays()
        overlaysView = overlays

        val root = FrameLayout(this)
        root.addView(canvas, FrameLayout.LayoutParams(MATCH, MATCH))
        // The eyedropper's ring is drawn over the canvas and is exactly its size, so the canvas's own coordinates are the ring's.
        root.addView(ring, FrameLayout.LayoutParams(MATCH, MATCH))
        // The pinned reference floats over the drawing, full-bleed like it, under the chrome. It is a view, never a layer.
        reference = ReferenceView(kit)
        root.addView(reference, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(overlays, FrameLayout.LayoutParams(MATCH, MATCH))
        // Joy Brush's identity, as a hairline along the very top (visual language §4.2): the section colour, never a button.
        hairline = View(this).apply { background = JbColors.roomGradient(this@JoyBrushActivity) }
        root.addView(hairline, FrameLayout.LayoutParams(MATCH, kit.dpi(2f), Gravity.TOP))
        setContentView(root)
        applyTool()
        restoreReference()
        // Four fingers tap: the chrome goes, the picture stays (JB-2.02's gesture, JB-2.01's one toggle).
        canvas.onToggleUi = { toggleChrome() }
        // The top icons re-read the picture behind them whenever it can have changed under them.
        canvas.onViewMoved = { checkIcons() }
        reference.onMoved = { saveReferencePlace(); checkIcons() }
        root.post { checkIcons() }
        // JbCanvasView reports this on the UI thread via post(), after every committed stroke,
        // undo, redo and clear. Undo and Redo start disabled -- there is no history yet.
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
        canvas.onRawEvent = { ev -> diag.onRawEvent(ev) }
        // A drawing that cannot be read back is not a drawing that was saved, and saying so is the
        // whole of the difference between "saved" and "lost".
        canvas.onSnapshotFailed = { why ->
            pauseViewNow()
            activeSave?.invoke("Could not read the drawing: $why")
        }

        goFullScreen(overlays)

        // Where the person left off, if they left off anywhere.
        restoreWorkingFile()
    }

    override fun onResume() {
        super.onResume()
        viewPausePending = false
        ui.removeCallbacks(pauseView)
        canvas.onResume()
        startBrushLab()
        saves.drain()
    }

    override fun onPause() {
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

    // ── the chrome (JB-2.01) ────────────────────────────────────────────────
    //
    // Infinite Painter's lesson, in Joy Creator's clothes: Home, Undo and Redo top-left; the reference pin and ⋯ top-right;
    // a slim tool strip on one edge with the recent-colour hair beside it; everything else is the picture. Guides and
    // Layers join the top bar when their rows land — a button that does nothing is worse than none.

    private fun buildOverlays(): FrameLayout {
        val overlays = FrameLayout(this)
        popovers = Popovers(kit, overlays)
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
        pinBtn = TopButton(kit, JbIcon.PIN, "Pin a reference picture — hold for its options").apply {
            setOnClickListener { pinTapped() }
            setOnLongClickListener { referenceMenu(); true }
        }
        val more = TopButton(kit, JbIcon.MORE, "More — save a copy, open, smoothing, put everything back").apply {
            setOnClickListener { moreMenu(this) }
            // JB-0.06's hidden door moved here from the old close button: hold ⋯ for the pen diagnostics.
            setOnLongClickListener { toggleDiagnostics(); true }
        }
        val touch = kit.dpi(ChromeKit.TOUCH_DP)
        for (b in listOf(home, undoBtn, redoBtn)) topBar.addView(b, LinearLayout.LayoutParams(touch, touch))
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        for (b in listOf(pinBtn, more)) topBar.addView(b, LinearLayout.LayoutParams(touch, touch))
        topButtons.addAll(listOf(home, undoBtn, redoBtn, pinBtn, more))
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
        if (lp.gravity != g || lp.topMargin != top) {
            lp.gravity = g
            lp.topMargin = top
            strip.layoutParams = lp
        }
    }

    /**
     * The tool in the hand goes to the canvas: its brush at its remembered size and opacity. Saved at once, so a tool is
     * never lost to a force-stop.
     */
    private fun applyTool() {
        canvas.preset = tools.presetFrom(brushes) ?: brushes.firstOrNull()
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
        val drawer = BrushDrawerView(kit, brushes, start, current) { picked ->
            tools = tools.pick(picked)
            applyTool()
            popovers.close()
        }
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
        box.addView(menuRow("Save a copy…", "Save a copy of this drawing where you choose") { askWhereToSave() })
        box.addView(menuRow("Open…", "Open a drawing from your files") { askWhichToOpen() })

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

        box.addView(menuRow("Put everything back", "Move the tool strip and the reference picture back to where they started") { putEverythingBack() })
        // The one destructive row carries the one colour allowed to mean "something is lost", as a dot: red TEXT over a
        // see-through panel was hard to read on the Note 9. Undo brings the drawing back.
        box.addView(menuRow("Clear drawing", "Clear the drawing — Undo brings it back", dot = kit.p.stateDestroy) { canvas.clearCanvas() })
        popovers.show(box, anchor, Popovers.Side.BELOW, widthDp = 240f)
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
        for (v in listOf<View>(topBar, strip, hairline)) {
            v.animate().cancel()
            if (chromeShown) {
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(170L).start()
            } else {
                v.animate().alpha(0f).setDuration(170L).withEndAction { if (!chromeShown) v.visibility = View.GONE }.start()
            }
        }
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
    private fun openColourPicker() {
        val before = canvas.strokeColor
        ColorPickerDialog.show(this, "Colour", before, false,
            { live -> if (live != null) { canvas.colorArgb = live; refreshColour() } },
            { picked -> if (picked != null) { canvas.colorArgb = picked; refreshColour() } })
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
            ev.x >= 0f && ev.y >= 0f && ev.x <= v.width && ev.y <= v.height

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
                        canvas.dragEyedropMove(x, y)
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
                activeSave = null
                if (problem != null) {
                    toast(
                        if (dest is SaveDest.Copy) "Couldn't save the copy. Your drawing is safe. $problem"
                        else "The autosave did not work: $problem",
                    )
                    // A working save that failed is still owed: ask again after the usual quiet.
                    if (dest is SaveDest.Working) {
                        ui.removeCallbacks(idleSave)
                        ui.postDelayed(idleSave, AUTOSAVE_AFTER_MS)
                    }
                } else if (dest is SaveDest.Copy) {
                    // A COPY never touches the working file, so it says nothing about whether the
                    // working file is up to date: `changes` is left exactly as it was.
                    toast("Copy saved")
                } else if (changes == from) {
                    changes = 0
                }
                finished(problem)
                pauseViewIfDrained()
            }
        }
        watchdog = Runnable { answer("the drawing did not answer in time") }
        activeSave = answer
        ui.postDelayed(watchdog, SNAPSHOT_TIMEOUT_MS)

        val file = if (dest is SaveDest.Working) workingFile() else null
        if (dest is SaveDest.Working && file == null) {
            answer("this device has nowhere to keep a working drawing")
            return
        }
        canvas.snapshot { contents ->
            // The GL thread has done its part. Nothing else is waiting for it, so it can rest now.
            pauseViewIfDrained()
            fileIo.execute {
                val problem = try {
                    when (dest) {
                        is SaveDest.Working -> JbArchive.save(file!!, contents)
                        is SaveDest.Copy -> writeCopy(dest.uri, contents)
                    }
                    null
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
                runOnUiThread { answer(problem) }
            }
        }
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
        val file = workingFile() ?: return
        if (!file.isFile) return
        fileIo.execute {
            val read = try {
                JbArchive.open(file)
            } catch (e: Exception) {
                ui.post { toast("Your last drawing could not be opened: ${e.message}") }
                null
            }
            if (read != null) {
                val contents = read
                ui.post {
                    // A stroke that landed while the file was being read outranks the file.
                    if (changes == 0) showContents(contents)
                }
            }
        }
    }

    /** "Save a copy…": queued behind anything already running, never dropped (JB-2.15). */
    private fun saveCopyTo(uri: Uri) {
        saves.request(SaveReason.EXPLICIT, SaveDest.Copy(uri))
    }

    /** "Open…": the person picks a file, and it replaces whatever is on the canvas. */
    private fun openFrom(uri: Uri) {
        fileIo.execute {
            val read = try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw JbArchiveException("the file could not be opened")
                input.use { JbArchive.read(it) }
            } catch (e: Exception) {
                ui.post { toast("That drawing could not be opened: ${e.message}") }
                null
            }
            if (read != null) {
                val contents = read
                ui.post { showContents(contents) }
            }
        }
    }

    /**
     * Puts a drawing on the canvas, or says why it cannot go there and leaves the drawing that is
     * already there alone. UI thread.
     */
    private fun showContents(contents: JbContents) {
        try {
            canvas.load(contents) {
                // Loading reports a history change of its own; that is not work to be autosaved.
                changes = 0
                ui.removeCallbacks(idleSave)
            }
        } catch (e: JbArchiveException) {
            toast(e.message ?: "that drawing cannot be opened")
        }
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
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == REQUEST_OPEN) openFrom(uri)
        if (requestCode == REQUEST_SAVE_COPY) saveCopyTo(uri)
        if (requestCode == REQUEST_REFERENCE) pinReference(uri)
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
