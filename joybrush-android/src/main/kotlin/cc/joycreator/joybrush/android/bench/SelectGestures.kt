package cc.joycreator.joybrush.android.bench

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.hypot

/**
 * Synthetic pen and finger gestures for [SelectBench], played through the WINDOW's root view so every layer gets its say
 * as on the phone (the Studio box above the canvas, the row and bar, the strip). Points are in the canvas's own px; they
 * are moved into window px here. Pen events are S Pen-shaped like [cc.joycreator.joybrush.androidkit.bench.StrokeReplay]
 * (TOOL_TYPE_STYLUS, pressure, tilt); fingers are TOOL_TYPE_FINGER. Debug benches only.
 */
class SelectGestures(private val canvas: View) {
    private val ui = Handler(Looper.getMainLooper())
    private val root: View get() = canvas.rootView
    private val at = IntArray(2)
    private var stopped = false
    private var contact: MotionEvent? = null

    /** Cancel a synthetic contact, never finish it as an UP, and discard every remaining scheduled gesture. */
    fun cancel() {
        if (stopped) return
        stopped = true
        ui.removeCallbacksAndMessages(null)
        contact?.let { last ->
            contact = null
            last.action = MotionEvent.ACTION_CANCEL
            try { root.dispatchTouchEvent(last) } finally { last.recycle() }
        }
    }

    class P(val x: Float, val y: Float)

    /** One pointer along [path] (canvas px) over [ms], 120 reports a second; [done] runs [settleMs] after the lift. */
    fun stroke(path: List<P>, ms: Long, pen: Boolean, settleMs: Long = 350, done: () -> Unit) {
        if (stopped) return
        val pts = resample(path, ms)
        val base = SystemClock.uptimeMillis() + 30
        pts.forEachIndexed { i, (p, t) ->
            ui.postAtTime({
                val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; pts.lastIndex -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                send(base, base + t, action, listOf(p), pen)
                if (i == pts.lastIndex) ui.postDelayed(done, settleMs)
            }, base + t)
        }
    }

    fun tap(x: Float, y: Float, pen: Boolean, settleMs: Long = 350, done: () -> Unit) =
        stroke(listOf(P(x, y), P(x, y)), 60, pen, settleMs, done)

    /** A finger tap on the middle of [v], wherever it sits in the window. */
    fun tapView(v: View, settleMs: Long = 450, done: () -> Unit) {
        val c = IntArray(2); canvas.getLocationInWindow(c)
        val w = IntArray(2); v.getLocationInWindow(w)
        tap(w[0] - c[0] + v.width / 2f, w[1] - c[1] + v.height / 2f, pen = false, settleMs = settleMs, done = done)
    }

    /** The pen hovering (no contact) along [path]: enter, moves, then it stays (no exit) so hover read-outs can be checked. */
    fun hover(path: List<P>, ms: Long, settleMs: Long = 250, done: () -> Unit) {
        if (stopped) return
        val pts = resample(path, ms)
        val base = SystemClock.uptimeMillis() + 30
        pts.forEachIndexed { i, (p, t) ->
            ui.postAtTime({
                val action = if (i == 0) MotionEvent.ACTION_HOVER_ENTER else MotionEvent.ACTION_HOVER_MOVE
                val ev = obtain(base, base + t, action, listOf(p), pen = true)
                root.dispatchGenericMotionEvent(ev); ev.recycle()
                if (i == pts.lastIndex) ui.postDelayed(done, settleMs)
            }, base + t)
        }
    }

    /** The pen leaving the screen after [hover]. */
    fun hoverExit(p: P) {
        if (stopped) return
        val now = SystemClock.uptimeMillis()
        val ev = obtain(now, now, MotionEvent.ACTION_HOVER_EXIT, listOf(p), pen = true)
        root.dispatchGenericMotionEvent(ev); ev.recycle()
    }

    /** Two fingers about ([cx],[cy]) on a horizontal line, apart [from] → [to] px, over [ms]. */
    fun pinch(cx: Float, cy: Float, from: Float, to: Float, ms: Long, settleMs: Long = 400, done: () -> Unit) {
        if (stopped) return
        val n = (ms * 120 / 1000).toInt().coerceAtLeast(4)
        val base = SystemClock.uptimeMillis() + 30
        fun pair(k: Int): List<P> { val d = (from + (to - from) * k / n) / 2f; return listOf(P(cx - d, cy), P(cx + d, cy)) }
        val steps = ArrayList<Pair<Int, List<P>>>()
        steps += MotionEvent.ACTION_DOWN to pair(0).take(1)
        steps += (MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)) to pair(0)
        for (k in 1..n) steps += MotionEvent.ACTION_MOVE to pair(k)
        steps += (MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)) to pair(n)
        steps += MotionEvent.ACTION_UP to pair(n).take(1)
        steps.forEachIndexed { i, (action, ps) ->
            val t = i * ms / steps.size
            ui.postAtTime({
                send(base, base + t, action, ps, pen = false)
                if (i == steps.lastIndex) ui.postDelayed(done, settleMs)
            }, base + t)
        }
    }

    /** Every view under the window's root of type [T] that is on screen. */
    inline fun <reified T : View> find(): List<T> = all().filterIsInstance<T>().filter { it.isShown }

    /** The first shown view whose hover label starts with [label]. */
    fun labelled(label: String, under: View? = null): View? =
        all(under ?: canvas.rootView).firstOrNull { it.isShown && it.contentDescription?.toString()?.startsWith(label) == true }

    fun all(from: View = canvas.rootView): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) { out += v; if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(from)
        return out
    }

    private fun send(down: Long, t: Long, action: Int, ps: List<P>, pen: Boolean) {
        if (stopped) return
        val ev = obtain(down, t, action, ps, pen)
        contact?.recycle()
        contact = if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) null
            else MotionEvent.obtain(ev)
        try { root.dispatchTouchEvent(ev) } finally { ev.recycle() }
    }

    private fun obtain(down: Long, t: Long, action: Int, ps: List<P>, pen: Boolean): MotionEvent {
        canvas.getLocationInWindow(at)
        val props = Array(ps.size) { i -> MotionEvent.PointerProperties().apply {
            id = i; toolType = if (pen) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(ps.size) { i -> MotionEvent.PointerCoords().apply {
            x = ps[i].x + at[0]; y = ps[i].y + at[1]
            pressure = if (pen) 0.6f else 1f; size = if (pen) 0.01f else 0.08f
            if (pen) { orientation = 0.9f; setAxisValue(MotionEvent.AXIS_TILT, 0.5f) }
        } }
        val source = if (pen) InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_TOUCHSCREEN
        return MotionEvent.obtain(down, t, action, ps.size, props, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
    }

    /** [path] walked at constant speed, one point per 1/120 s: (point, ms from the first). */
    private fun resample(path: List<P>, ms: Long): List<Pair<P, Long>> {
        if (path.size < 2) return listOf(path[0] to 0L, path[0] to ms)
        val seg = DoubleArray(path.size - 1) { i -> hypot((path[i + 1].x - path[i].x).toDouble(), (path[i + 1].y - path[i].y).toDouble()) }
        val total = seg.sum()
        val n = (ms * 120 / 1000).toInt().coerceAtLeast(2)
        return List(n + 1) { k ->
            val d = total * k / n
            var i = 0; var acc = 0.0
            while (i < seg.lastIndex && acc + seg[i] < d) { acc += seg[i]; i++ }
            val f = if (seg[i] == 0.0) 0.0 else ((d - acc) / seg[i]).coerceIn(0.0, 1.0)
            P((path[i].x + (path[i + 1].x - path[i].x) * f).toFloat(), (path[i].y + (path[i + 1].y - path[i].y) * f).toFloat()) to ms * k / n
        }
    }
}
