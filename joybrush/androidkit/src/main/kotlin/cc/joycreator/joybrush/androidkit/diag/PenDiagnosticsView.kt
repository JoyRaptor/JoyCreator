package cc.joycreator.joybrush.androidkit.diag

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import cc.joycreator.joybrush.core.input.AxisMapping
import java.util.Locale
import kotlin.math.PI
import kotlin.math.min

// JB-0.06. This is a debug panel, not a design surface, so it does not wear the room tokens: the
// androidkit module has no Android resources of its own (see its build.gradle.kts) and must not
// gain any for this. The literals live here, named, and nowhere else.
private val PANEL_COLOR = 0xF20A0D12.toInt()
private val TEXT_COLOR = 0xFFE6F0FA.toInt()
private const val WAITING = "Joy Brush pen diagnostics - waiting for input"

/**
 * The hidden pen probe (JB-0.06). Shows the raw pen numbers live and hands them out as plain text
 * so the per-device calibration table is built from measurements, not from forum reports.
 *
 * Feed it every [MotionEvent] (the UI thread) through JbCanvasView's `onRawEvent`. It never touches
 * drawing: it stores numbers, and redraws at most [FRAME_MS] while it is on screen. While it is
 * hidden it costs one `isShown` check per event.
 *
 * Two conventions worth knowing when reading a report:
 * - "gaps" are the intervals between consecutive SAMPLES (Android batches several into one event,
 *   and at 240 Hz most of a stroke's path is in the history), counted over the last [WINDOW_MS].
 * - min/max are running values since the panel was last shown, and are tracked for the pen only.
 */
class PenDiagnosticsView(context: Context) : View(context) {

    // ── the latest event ─────────────────────────────────────────────────────
    private var toolName = "-"
    private var actionName = "-"
    private var pressure = Float.NaN
    private var tilt = Float.NaN
    private var orientation = Float.NaN
    private var azimuth = Float.NaN
    private var distance = Float.NaN
    private var buttonState = 0
    private var history = 0

    /** Event times (ms) of every sample seen inside the last [WINDOW_MS]. Rate and gaps come from it. */
    private val stamps = ArrayList<Long>(256)

    // ── running extremes, pen only, since the panel was shown ────────────────
    private var pressMin = Float.NaN
    private var pressMax = Float.NaN
    private var tiltMin = Float.NaN
    private var tiltMax = Float.NaN

    private var lines: Array<String> = arrayOf(WAITING)
    private var lastDrawAt = 0L

    private val panelPaint = Paint()
    private val textPaint = Paint()
    private val box = RectF()
    private val pad: Float
    private val textSize: Float
    private val lineH: Float

    init {
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
        pad = dp(8f)
        textSize = sp(11f)
        lineH = textSize * 1.25f
        panelPaint.color = PANEL_COLOR
        panelPaint.style = Paint.Style.FILL
        textPaint.color = TEXT_COLOR
        textPaint.textSize = textSize
        textPaint.typeface = Typeface.MONOSPACE
        textPaint.isAntiAlias = true
    }

    /** Nothing here is a control, so the panel never eats a touch. */
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    /** Forgets the sample window and the running extremes; called when the panel is shown. */
    fun reset() {
        stamps.clear()
        pressMin = Float.NaN
        pressMax = Float.NaN
        tiltMin = Float.NaN
        tiltMax = Float.NaN
        lastDrawAt = 0L
        rebuild()
        invalidate()
    }

    /** Store one event. UI thread only, exactly as JbCanvasView delivers it. */
    fun onRawEvent(ev: MotionEvent) {
        if (!isShown) return
        val now = SystemClock.uptimeMillis()
        prune(now)
        val n = ev.historySize
        for (h in 0 until n) stamps.add(ev.getHistoricalEventTime(h).toLong())
        stamps.add(ev.eventTime.toLong())

        toolName = toolLabel(ev.getToolType(0))
        actionName = actionLabel(ev.actionMasked)
        history = n
        buttonState = ev.buttonState
        pressure = ev.getPressure(0)
        tilt = ev.getAxisValue(MotionEvent.AXIS_TILT, 0)
        orientation = ev.getAxisValue(MotionEvent.AXIS_ORIENTATION, 0)
        azimuth = AxisMapping.androidOrientationToAzimuth(orientation, 0f)
        distance = ev.getAxisValue(MotionEvent.AXIS_DISTANCE, 0)
        val type = ev.getToolType(0)
        if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER) {
            val p = pressure
            if (!p.isNaN()) {
                if (pressMin.isNaN() || p < pressMin) pressMin = p
                if (pressMax.isNaN() || p > pressMax) pressMax = p
            }
            val t = tilt
            if (!t.isNaN()) {
                if (tiltMin.isNaN() || t < tiltMin) tiltMin = t
                if (tiltMax.isNaN() || t > tiltMax) tiltMax = t
            }
        }

        if (now - lastDrawAt < FRAME_MS) return
        lastDrawAt = now
        rebuild()
        invalidate()
    }

    /**
     * The whole readout as plain text, for the clipboard and for
     * `tasks/joybrush/research/DEVICE_PEN_REPORTS.md`.
     */
    fun report(): String {
        rebuild()
        val sb = StringBuilder()
        sb.append("Joy Brush pen diagnostics\n")
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        sb.append("  android ").append(Build.VERSION.RELEASE)
        sb.append(" (sdk ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("gaps are between consecutive samples over the last ")
        sb.append(WINDOW_MS).append(" ms; min/max are since the panel was shown.\n")
        for (s in lines) sb.append(s).append('\n')
        return sb.toString()
    }

    // ── drawing ──────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var want = pad * 2f
        for (s in lines) {
            val tw = textPaint.measureText(s)
            if (tw > want) want = tw
        }
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            want.toInt()
        } else {
            min(want, MeasureSpec.getSize(widthMeasureSpec).toFloat()).toInt()
        }
        val h = (pad * 2f + lineH * lines.size).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        box.set(0f, 0f, w, h)
        canvas.drawRoundRect(box, pad, pad, panelPaint)
        var y = pad + textSize
        for (s in lines) {
            canvas.drawText(s, pad, y, textPaint)
            y += lineH
        }
    }

    // ── the text ─────────────────────────────────────────────────────────────

    private fun rebuild() {
        val n = stamps.size
        val counts = IntArray(6)
        for (i in 1 until n) counts[bucketOf(stamps[i] - stamps[i - 1])]++
        val out = ArrayList<String>(10)
        out.add("Joy Brush pen diagnostics")
        out.add("tool " + toolName + "  " + actionName)
        out.add("press " + f3(pressure) + "  tilt " + deg(tilt))
        out.add("orient " + deg(orientation) + "  azim " + deg(azimuth))
        out.add("dist " + f3(distance) + "  btn 0x" + Integer.toHexString(buttonState))
        out.add("hist " + history + "  rate " + n + "/s  gaps " + (n - 1).coerceAtLeast(0))
        out.add("gap ms  <3  3-5  5-8  8-12  12-20  >20")
        out.add(
            String.format(
                Locale.US, "      %5d%6d%6d%6d%6d%6d",
                counts[0], counts[1], counts[2], counts[3], counts[4], counts[5],
            ).trimEnd()
        )
        out.add("press min/max " + f3(pressMin) + " / " + f3(pressMax))
        out.add("tilt min/max " + deg(tiltMin) + " / " + deg(tiltMax))
        lines = out.toTypedArray()
    }

    private fun prune(now: Long) {
        var k = 0
        while (k < stamps.size && now - stamps[k] > WINDOW_MS) k++
        if (k > 0) stamps.subList(0, k).clear()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun toolLabel(type: Int): String = when (type) {
        MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
        MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
        MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
        MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
        else -> "UNKNOWN"
    }

    private fun actionLabel(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        MotionEvent.ACTION_POINTER_DOWN -> "PTR_DOWN"
        MotionEvent.ACTION_POINTER_UP -> "PTR_UP"
        MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
        MotionEvent.ACTION_HOVER_ENTER -> "HOVER_ENTER"
        MotionEvent.ACTION_HOVER_EXIT -> "HOVER_EXIT"
        else -> "0x" + Integer.toHexString(action)
    }

    /** Bucket index for an inter-sample interval in ms: <3, 3-5, 5-8, 8-12, 12-20, >20. */
    private fun bucketOf(ms: Long): Int = when {
        ms < 3L -> 0
        ms < 5L -> 1
        ms < 8L -> 2
        ms < 12L -> 3
        ms < 20L -> 4
        else -> 5
    }

    private fun f3(v: Float): String =
        if (v.isNaN()) "--" else String.format(Locale.US, "%.3f", v)

    /** An axis angle in radians as the owner reads it: degrees. */
    private fun deg(v: Float): String =
        if (v.isNaN()) "--" else String.format(Locale.US, "%.1f deg", v.toDouble() * 180.0 / PI)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        /** The redraw ceiling: 30 fps is plenty for numbers. */
        const val FRAME_MS = 33L

        /** The sliding window for "samples per second" and the gap histogram. */
        const val WINDOW_MS = 1000L
    }
}
