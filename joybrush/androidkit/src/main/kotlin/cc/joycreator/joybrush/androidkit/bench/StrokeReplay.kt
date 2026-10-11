package cc.joycreator.joybrush.androidkit.bench

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic S Pen strokes for the debug replay (LEAD_PLAN_20261010 step 3): the same pen path every run, so a change
 * can be timed before and after on the phone. Samples are in VIEW pixels, at [HZ] like an EMR pen, and go through the
 * view's own [View.dispatchTouchEvent], so everything after the hardware is the real path (palm rules, smoothing,
 * dabbing, media, rendering).
 */
object StrokeReplay {
    const val HZ = 240.0

    /** One pen report: view px, pressure 0..1, tilt (rad from upright), orientation (rad), time from pen-down (ms). */
    class Sample(val x: Float, val y: Float, val pressure: Float, val tilt: Float, val orientation: Float, val tMs: Double)

    /** The named cases, each sized to the view. */
    fun case(name: String, w: Int, h: Int): List<Sample>? = when (name) {
        // The owner's complaint (Note 20, 2026-10-08): a large quick C. 270 degrees, radius 35% of the width, 120 ms.
        "fastC" -> arc(w * 0.5f, h * 0.5f, w * 0.35f, 135.0, -135.0 - 0.0, 120.0)
        // A slower C, same shape: 600 ms.
        "slowC" -> arc(w * 0.5f, h * 0.5f, w * 0.35f, 135.0, -135.0, 600.0)
        // Three seconds of back-and-forth shading down the page (pencil hatching, fast).
        "scribble" -> sweep(3000.0) { t ->
            val x = w * (0.15f + 0.7f * tri(t * 1.6))
            val y = h * (0.25f + 0.5f * t.toFloat())
            x to y
        }
        // A long fast spiral that leaves any 1024 px window several times (media window stress).
        "spiral" -> sweep(1500.0) { t ->
            val a = t * 6 * PI
            val r = w * (0.08f + 0.36f * t.toFloat())
            (w * 0.5f + (r * cos(a)).toFloat()) to (h * 0.5f + (r * sin(a)).toFloat())
        }
        else -> null
    }

    private fun arc(cx: Float, cy: Float, r: Float, fromDeg: Double, toDeg: Double, ms: Double): List<Sample> = sweep(ms) { t ->
        val a = (fromDeg + (toDeg - fromDeg) * t) * PI / 180
        (cx + (r * cos(a)).toFloat()) to (cy - (r * sin(a)).toFloat())
    }

    /** [path] maps 0..1 to a view point; pressure swells and fades, the pen leans ~35 degrees and turns slowly. */
    private fun sweep(ms: Double, path: (Double) -> Pair<Float, Float>): List<Sample> {
        val n = (ms / 1000.0 * HZ).toInt().coerceAtLeast(2)
        return List(n + 1) { i ->
            val t = i.toDouble() / n
            val (x, y) = path(t)
            val pressure = (0.25 + 0.55 * sin(PI * t)).toFloat()
            Sample(x, y, pressure, 0.6f, (0.9 + 0.4 * sin(2 * PI * t)).toFloat(), t * ms)
        }
    }

    private fun tri(x: Double): Float { val f = x - Math.floor(x); return (1 - abs(2 * f - 1)).toFloat() }

    /**
     * Plays [samples] into [view] in real time on the main thread, one event per report (as with unbuffered dispatch).
     * [onDown] runs just before the first event; [onUp] just after the last.
     */
    fun play(view: View, samples: List<Sample>, stats: FrameStats?, onDown: () -> Unit, onUp: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val base = SystemClock.uptimeMillis() + 50
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        samples.forEachIndexed { i, s ->
            val at = base + s.tMs.toLong()
            handler.postAtTime({
                if (i == 0) onDown()
                val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; samples.lastIndex -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val c = MotionEvent.PointerCoords().apply {
                    x = s.x; y = s.y; pressure = s.pressure; size = 0.01f; orientation = s.orientation
                    setAxisValue(MotionEvent.AXIS_TILT, s.tilt)
                }
                val ev = MotionEvent.obtain(base, at, action, 1, props, arrayOf(c), 0, 0, 1f, 1f, 0, 0,
                    InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN, 0)
                view.dispatchTouchEvent(ev)
                stats?.noteInput()
                ev.recycle()
                if (i == samples.lastIndex) { stats?.noteUp(); onUp() }
            }, at)
        }
    }
}
