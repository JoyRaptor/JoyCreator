package cc.joycreator.joybrush.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
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
import cc.joycreator.joybrush.androidkit.BrushLibrary
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.diag.PenDiagnosticsView
import cc.joycreator.joybrush.androidkit.io.JB_MIMETYPE
import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.io.JbArchiveException
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.androidkit.lab.BrushHotReload
import cc.joycreator.joybrush.core.brush.BrushPreset
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// 10% and 12% white, the spec's overlay colours. Both literals fit in an Int.
private const val OVERLAY_FILL = 0x1AFFFFFF
private const val OVERLAY_RING = 0x1FFFFFFF
private const val SMOOTHING_DEFAULT = 35
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

private const val REQUEST_OPEN = 4101
private const val REQUEST_SAVE_COPY = 4102

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
 * The Joy Brush screen (JB-0.05): a [JbCanvasView] filling the window with a few plain overlay
 * controls on top of it.
 *
 * Nothing about input lives here. The pen, palm rejection, smoothing and the GPU renderer all
 * belong to JbCanvasView; this Activity only hosts it, keeps the screen awake, and wires the
 * overlay controls to the view's public surface (brush preset and eraser, smoothing, the brush
 * picker, undo, redo, clear).
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
    private lateinit var undoBtn: TextView
    private lateinit var redoBtn: TextView
    private lateinit var eraserBtn: TextView
    private var erasing = false

    // JB-1.05b: the shipped brush files. The pill shows whichever one is current, and the view
    // draws with it — the hard-coded round brush is only reached by leaving preset null.
    private val brushes: List<BrushPreset> = BrushLibrary.builtIn()
    private var brushIndex = 0
    private lateinit var brushBtn: TextView

    // JB-0.06: the hidden pen probe. GONE until the owner long-presses the close button.
    private lateinit var diag: PenDiagnosticsView
    private lateinit var diagBox: LinearLayout
    private var diagShown = false

    // JB-0.08b. `ui` is the main thread's queue; `saving` keeps two writers off one file; `changes`
    // counts the edits that are not in the working file yet, and zero means it is all there.
    private val ui = Handler(Looper.getMainLooper())
    private val saving = AtomicBoolean(false)
    private var changes = 0

    /**
     * A save that was asked for while the pen was down (R11), and is therefore waiting for the
     * stroke to end rather than being dropped. It is a flag and not a timer because the moment to
     * write is when the drawing is complete, which is not a moment the clock knows about.
     */
    private var saveOwed = false
    private var viewPausePending = false

    /** The autosave the last change asked for, and the one the screen leaving asks for. */
    private val idleSave = Runnable { if (changes > 0) saveWorkingFile() }

    /** Pausing the GL thread, once a snapshot has answered or the wait for one is up. */
    private val pauseView = Runnable { pauseViewNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        canvas = JbCanvasView(this)
        // The first shipped brush file drives the view from the moment the screen opens (JB-1.05b).
        canvas.preset = brushes.firstOrNull()
        val overlays = buildOverlays()

        val root = FrameLayout(this)
        root.addView(canvas, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(overlays, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        // JbCanvasView reports this on the UI thread via post(), after every committed stroke,
        // undo, redo and clear. Undo and Redo start disabled -- there is no history yet.
        canvas.onHistoryChanged = { canUndo, canRedo ->
            updateHistoryButtons(canUndo, canRedo)
            // Every one of those is work that is not in the working file yet, so each one restarts
            // the 30 s clock (JB-0.08b decision 2).
            noteChange()
            // A stroke has just been committed or thrown away, so a save that was waiting for the
            // pen to lift can go now (R11). Ordered after `noteChange` on purpose: this is what
            // makes the deferred save land, and a timer that has just been restarted is exactly the
            // one that would otherwise fire 30 s later with nothing new to write.
            onStrokeFinished()
        }
        updateHistoryButtons(false, false)
        // JB-0.06: the diagnostics overlay sees every pen event. It only stores numbers, and only
        // while it is visible, so drawing is untouched.
        canvas.onRawEvent = { ev -> diag.onRawEvent(ev) }
        // A drawing that cannot be read back is not a drawing that was saved, and saying so is the
        // whole of the difference between "saved" and "lost".
        canvas.onSnapshotFailed = { why ->
            pauseViewNow()
            saving.set(false)
            toast("Could not read the drawing: $why")
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
        if (!saving.get()) saveWorkingFile()
        super.onPause()
    }

    // ── the overlay controls ────────────────────────────────────────────────

    private fun buildOverlays(): FrameLayout {
        val overlays = FrameLayout(this)

        // top-right: close. A long press is the hidden way into the pen diagnostics (JB-0.06);
        // returning true from the long-click keeps it from also closing the screen.
        val closeBtn = TextView(this).apply {
            text = "×"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = oval(OVERLAY_FILL, dp(1))
            contentDescription = "Close Joy Brush"
            ViewCompat.setTooltipText(this, "Close Joy Brush")
            setOnClickListener { finish() }
            setOnLongClickListener { toggleDiagnostics(); true }
        }
        overlays.addView(closeBtn, corner(dp(40), dp(40), Gravity.TOP or Gravity.END))

        // top-left: the pen diagnostics panel and its copy pill, both hidden until asked for.
        diag = PenDiagnosticsView(this)
        diagBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        diagBox.addView(diag, LinearLayout.LayoutParams(WRAP, WRAP))
        val copyBtn = pillButton("Copy report", "Copy the pen diagnostics to the clipboard") { copyReport() }
        diagBox.addView(copyBtn, LinearLayout.LayoutParams(WRAP, dp(40)))
        overlays.addView(diagBox, corner(WRAP, WRAP, Gravity.TOP or Gravity.START, 10))

        // top-centre: smoothing
        val smoothRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill()
            setPadding(dp(12), 0, dp(12), 0)
        }
        val smoothLabel = TextView(this).apply {
            text = "Smoothing"
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        val smoothSeek = SeekBar(this).apply {
            max = 100
            contentDescription = "Stroke smoothing"
            ViewCompat.setTooltipText(this, "Stroke smoothing")
        }
        smoothRow.addView(smoothLabel, pillChild())
        smoothRow.addView(smoothSeek, LinearLayout.LayoutParams(dp(140), dp(40)))
        overlays.addView(smoothRow, corner(WRAP, dp(40), Gravity.TOP or Gravity.CENTER_HORIZONTAL, 10))
        smoothSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                canvas.smoothing = progress / 100f
                // JB-1.05b: only a real move of the slider takes smoothing away from the brush
                // file, so the starting 35% set below does not count as the person choosing.
                if (fromUser) canvas.smoothingFromUser = true
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                // nothing to do
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                // nothing to do
            }
        })
        // Setting progress fires onProgressChanged, so the canvas gets its 35% starting value.
        smoothSeek.progress = SMOOTHING_DEFAULT

        // bottom-left: the two buttons that move a drawing off this screen and back on, stacked over
        // undo / redo / clear.
        val fileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fileRow.addView(pillButton("Save a copy…", "Save a copy of this drawing where you choose") { askWhereToSave() }, pillChild())
        fileRow.addView(pillButton("Open…", "Open a drawing from your files") { askWhichToOpen() }, pillChild())

        // bottom-left: undo / redo / clear
        val historyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        undoBtn = pillButton("Undo", "Undo the last stroke") { canvas.undo() }
        redoBtn = pillButton("Redo", "Redo the last undone stroke") { canvas.redo() }
        val clearBtn = pillButton("Clear", "Clear the canvas") { canvas.clearCanvas() }
        historyRow.addView(undoBtn, pillChild())
        historyRow.addView(redoBtn, pillChild())
        historyRow.addView(clearBtn, pillChild())

        val bottomLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        bottomLeft.addView(fileRow, LinearLayout.LayoutParams(WRAP, WRAP))
        bottomLeft.addView(historyRow, LinearLayout.LayoutParams(WRAP, dp(40)))
        overlays.addView(bottomLeft, corner(WRAP, WRAP, Gravity.BOTTOM or Gravity.START, 12))

        // bottom-right: eraser toggle
        eraserBtn = pillButton("Eraser", "Toggle the eraser") { toggleEraser() }
        overlays.addView(eraserBtn, corner(WRAP, dp(40), Gravity.BOTTOM or Gravity.END, 12))

        // bottom-centre: the brush picker. Its label IS the current brush's name, so there is no
        // drawer to open and no second place to look for what is in the nib.
        brushBtn = pillButton(brushLabel(), "Change the brush") { cycleBrush() }
        overlays.addView(brushBtn, corner(WRAP, dp(40), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 12))

        return overlays
    }

    private fun updateHistoryButtons(canUndo: Boolean, canRedo: Boolean) {
        undoBtn.isEnabled = canUndo
        undoBtn.alpha = if (canUndo) 1f else 0.4f
        redoBtn.isEnabled = canRedo
        redoBtn.alpha = if (canRedo) 1f else 0.4f
    }

    private fun toggleEraser() {
        erasing = !erasing
        canvas.brush = canvas.brush.copy(erase = erasing)
        eraserBtn.alpha = if (erasing) 1f else 0.55f
    }

    /**
     * The brush pill's label: the name of the file the view is drawing with. "Brush" alone, with
     * no name after it, means no brush file was packaged into the build.
     */
    private fun brushLabel(): String {
        val p = brushes.getOrNull(brushIndex)
        return if (p == null) "Brush" else "Brush: ${p.name}"
    }

    /** Tap cycles through the packaged brush files, wrapping round at the end. */
    private fun cycleBrush() {
        if (brushes.isEmpty()) return
        brushIndex += 1
        if (brushIndex >= brushes.size) brushIndex = 0
        canvas.preset = brushes[brushIndex]
        brushBtn.text = brushLabel()
        // The tooltip is read when the person presses and holds, so it has to be brought up to
        // date with the label rather than always saying the same thing.
        ViewCompat.setTooltipText(brushBtn, "Change the brush — now ${brushes[brushIndex].name}")
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
     * Takes a snapshot on the GL thread and writes it on the writer thread.
     *
     * [failurePrefix] is the sentence shown if the write fails, with the reason appended. It is a
     * parameter rather than derived from a name because R11 asks for one specific sentence on the
     * copy path: a person who picked a destination, watched it fail, and cannot see whether their
     * drawing survived has to be told THAT first and the reason second — "Couldn't save the copy.
     * Your drawing is safe." Everything else says "X did not work: …".
     *
     * [done] is shown only when the write actually worked, because "Saved" is the one word here
     * that must never be a lie. One at a time: two writers would fight over the working file's own
     * temporary file, and the loser would say so in a message about a file the person has never
     * heard of.
     */
    private fun saveAsync(
        failurePrefix: String,
        done: String?,
        write: (JbContents) -> Unit,
    ) {
        if (!saving.compareAndSet(false, true)) {
            // One save is already running. Ask again later rather than losing this change to it.
            ui.removeCallbacks(idleSave)
            ui.postDelayed(idleSave, AUTOSAVE_AFTER_MS)
            return
        }
        // Read on the UI thread, before the snapshot: only a save that started at this count and
        // found the count unchanged when it finished has written everything, so a stroke made while
        // it was writing is still counted and still waiting for its own autosave.
        val from = changes
        canvas.snapshot { contents ->
            pauseViewNow()
            fileIo.execute {
                val problem = try {
                    write(contents)
                    null
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
                saving.set(false)
                if (problem != null) {
                    runOnUiThread { toast("$failurePrefix$problem") }
                } else {
                    runOnUiThread {
                        if (changes == from) changes = 0
                        if (done != null) toast(done)
                    }
                }
            }
        }
    }

    /** The autosave: the working file, through the archive's own atomic write. */
    private fun saveWorkingFile() {
        // R11: never end or cancel the person's stroke to save. If one is in flight, a snapshot
        // would capture the tiles as they were BEFORE this stroke, so the drawing on disk would
        // silently lack the mark they are making right now — and the autosave that is supposed to
        // be the safety net is exactly where that hurts most. So: note that a save is owed, and
        // do it the moment the stroke ends, when the snapshot will be complete.
        if (canvas.strokeInProgress) {
            saveOwed = true
            return
        }
        writeWorkingFile()
    }

    private fun writeWorkingFile() {
        val file = workingFile()
        if (file == null) {
            toast("This device has nowhere to keep a working drawing")
            return
        }
        saveAsync("The autosave did not work: ", null) { JbArchive.save(file, it) }
    }

    /**
     * Called when a stroke has ended or been cancelled, and the snapshot can therefore be complete.
     * Runs whether the stroke was going to be saved or not: a stroke that changed nothing still
     * costs one small write, and the alternative is a flag that can be left set forever.
     */
    private fun onStrokeFinished() {
        if (!saveOwed) return
        saveOwed = false
        if (!canvas.strokeInProgress) writeWorkingFile()
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

    /**
     * "Save a copy…": the person picks where, and the archive is written to that stream.
     *
     * R11: a SAF `Uri` cannot be made atomic — there is no rename, and no way to say "only put it
     * there if it all fits". So the copy is BUILT IN FULL in `cacheDir` first, through the same
     * code path a normal save uses, and only a complete archive is ever streamed to the chosen
     * place. That does not make the final copy atomic — a card that fills one byte from the end
     * still leaves a half file at the name the person chose — but it means a drawing that cannot be
     * written is refused *before* the chosen file is opened at all, and it guarantees this can
     * never touch the working file.
     */
    private fun saveCopyTo(uri: Uri) {
        saveAsync(
            "Couldn't save the copy. Your drawing is safe. ",
            "Copy saved",
        ) { contents ->
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

    // ── view helpers, all programmatic so this module needs no resources ─────

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

    private fun oval(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke > 0) setStroke(stroke, OVERLAY_RING)
    }

    private fun corner(w: Int, h: Int, gravity: Int, marginDp: Int = 0): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(w, h).apply {
            this.gravity = gravity
            val m = dp(marginDp)
            setMargins(m, m, m, m)
        }

    private fun pillChild(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(WRAP, dp(40)).apply { marginEnd = dp(8) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * The canvas is deliberately full-bleed; only the overlay layer steps aside of the cutout and
     * the (transient) system bars, so the close button never lands under the punch-hole.
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
