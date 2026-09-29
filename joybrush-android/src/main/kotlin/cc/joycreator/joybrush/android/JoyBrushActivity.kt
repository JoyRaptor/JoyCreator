package cc.joycreator.joybrush.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.diag.PenDiagnosticsView

// 10% and 12% white, the spec's overlay colours. Both literals fit in an Int.
private const val OVERLAY_FILL = 0x1AFFFFFF
private const val OVERLAY_RING = 0x1FFFFFFF
private const val SMOOTHING_DEFAULT = 35
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/**
 * The Joy Brush screen (JB-0.05): a [JbCanvasView] filling the window with a few plain overlay
 * controls on top of it.
 *
 * Nothing about input lives here. The pen, palm rejection, smoothing and the GPU renderer all
 * belong to JbCanvasView; this Activity only hosts it, keeps the screen awake, and wires the
 * overlay controls to the view's public surface (brush, smoothing, undo, redo, clear).
 */
class JoyBrushActivity : Activity() {

    private lateinit var canvas: JbCanvasView
    private lateinit var undoBtn: TextView
    private lateinit var redoBtn: TextView
    private lateinit var eraserBtn: TextView
    private var erasing = false

    // JB-0.06: the hidden pen probe. GONE until the owner long-presses the close button.
    private lateinit var diag: PenDiagnosticsView
    private lateinit var diagBox: LinearLayout
    private var diagShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        canvas = JbCanvasView(this)
        val overlays = buildOverlays()

        val root = FrameLayout(this)
        root.addView(canvas, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(overlays, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        // JbCanvasView reports this on the UI thread via post(), after every committed stroke,
        // undo, redo and clear. Undo and Redo start disabled -- there is no history yet.
        canvas.onHistoryChanged = { canUndo, canRedo -> updateHistoryButtons(canUndo, canRedo) }
        updateHistoryButtons(false, false)
        // JB-0.06: the diagnostics overlay sees every pen event. It only stores numbers, and only
        // while it is visible, so drawing is untouched.
        canvas.onRawEvent = { ev -> diag.onRawEvent(ev) }

        goFullScreen(overlays)
    }

    override fun onResume() {
        super.onResume()
        canvas.onResume()
    }

    override fun onPause() {
        canvas.onPause()
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
        overlays.addView(historyRow, corner(WRAP, dp(40), Gravity.BOTTOM or Gravity.START, 12))

        // bottom-right: eraser toggle
        eraserBtn = pillButton("Eraser", "Toggle the eraser") { toggleEraser() }
        overlays.addView(eraserBtn, corner(WRAP, dp(40), Gravity.BOTTOM or Gravity.END, 12))

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
}
