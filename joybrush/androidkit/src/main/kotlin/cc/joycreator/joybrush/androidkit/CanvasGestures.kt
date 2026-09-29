package cc.joycreator.joybrush.androidkit

import android.view.MotionEvent
import cc.joycreator.joybrush.core.view.ViewTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Every touch that is NOT laying down a stroke (JB-2.02): two fingers pan, zoom and turn the
 * page; one finger pans, but only once a pen has been seen; and a quick tap of two, three or four
 * fingers is undo, redo and hide-the-UI. Nothing here knows what a stroke is — [JbCanvasView]
 * decides which events are drawing and hands the rest here, which is what keeps "a pen draws, a
 * finger navigates" (the owner's ruling) in one readable place.
 *
 * A TAP is every finger back up within [TAP_MS] of the first going down, with no finger having
 * wandered more than [TAP_SLOP_PX]. Two fingers, undo; three, redo; four, [onToggleUi]. A tap of
 * one finger does nothing at all: that is a drawing tap when fingers draw, and a no-op when they
 * navigate.
 *
 * The two-finger turn has a [ViewTransform.SNAP_DEGREES] dead zone: a pinch that is mostly a pan
 * must not turn the page by the couple of degrees two fingers are never quite still about. Past
 * the dead zone the turn is followed in full, and on the lift the page squares up if it is nearly
 * square already.
 *
 * Cheap enough to sit between two pen samples: the whole gesture is fields, and a move costs a
 * handful of arithmetic and a loop over the pointers.
 */
class CanvasGestures(
    private val view: ViewTransform,
    private val onViewChanged: () -> Unit,
    private val onUndo: () -> Unit,
    private val onRedo: () -> Unit,
    private val onToggleUi: () -> Unit,
) {

    /**
     * Whether ONE finger pans. The view sets this once per gesture, when the first finger lands:
     * false until a pen has been seen (fingers draw then), true afterwards (LEAD_RULINGS R5 — after
     * the first pen, fingers are never dead, they navigate).
     *
     * It does not gate the two-finger pinch, and deliberately so: a second finger always means
     * "navigate, not draw", so zoom and rotation work on a device that has never seen a pen, and a
     * two-finger tap still undoes a stroke the first finger had already started.
     */
    var fingersNavigate: Boolean = false

    private var active = false
    private var handedOver = false
    private var downTimeMs = 0L
    private var maxPointers = 0
    private var wandered = false
    private var navigated = false

    // Where each pointer went down, so the tap test can ask how far it strayed.
    private val downX = HashMap<Int, Float>()
    private val downY = HashMap<Int, Float>()

    // The previous frame of a pan or a pinch.
    private var prevCx = 0f
    private var prevCy = 0f
    private var prevDist = 0f
    private var prevAngle = 0f
    private var turnBase = 0f
    private var turnArmed = false

    /**
     * Feeds one event. Returns true — the machine always consumes what it is given, because a
     * gesture that started in a drawing stroke still has to be seen through to its last finger.
     */
    fun onEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(ev)
            MotionEvent.ACTION_POINTER_DOWN -> pointerDown(ev)
            MotionEvent.ACTION_MOVE -> move(ev)
            MotionEvent.ACTION_POINTER_UP -> pointerUp(ev)
            MotionEvent.ACTION_UP -> up(ev)
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return true
    }

    /**
     * Forgets the gesture in progress without undoing anything — what the view calls when a pen
     * takes over mid-gesture, so a stroke is never half-pan and half-navigate.
     */
    fun reset() {
        active = false
        handedOver = false
        wandered = false
        navigated = false
        maxPointers = 0
        downX.clear()
        downY.clear()
    }

    /**
     * The view just cancelled a one-finger stroke because a second finger landed: the gesture is
     * this machine's from the NEXT event on, even though it never saw the first finger go down.
     * Only the view can say this — a first touch it swallowed as a palm must NOT be adopted, or a
     * resting palm plus one finger would drag the page.
     */
    fun handOver() {
        handedOver = true
    }

    // ── the gesture ───────────────────────────────────────────────────────────────────────────────

    private fun down(ev: MotionEvent) {
        active = true
        downTimeMs = ev.eventTime
        maxPointers = 1
        wandered = false
        navigated = false
        turnArmed = false
        turnBase = 0f
        downX.clear()
        downY.clear()
        downX[ev.getPointerId(0)] = ev.getX(0)
        downY[ev.getPointerId(0)] = ev.getY(0)
        prevCx = ev.getX(0)
        prevCy = ev.getY(0)
        prevDist = 0f
        prevAngle = 0f
    }

    private fun pointerDown(ev: MotionEvent) {
        if (!active) {
            if (handedOver) adopt(ev)
            return
        }
        if (ev.pointerCount > maxPointers) maxPointers = ev.pointerCount
        val i = ev.actionIndex
        downX[ev.getPointerId(i)] = ev.getX(i)
        downY[ev.getPointerId(i)] = ev.getY(i)
        rebase(ev, -1)
    }

    /**
     * A second finger landed on a gesture this machine never saw start: the first finger was
     * drawing, so the view kept its ACTION_DOWN, then cancelled the stroke and handed over. Take the
     * gesture over from here — every finger's CURRENT position counts as where it went down, and the
     * tap clock runs from the first finger's real down time — so the pinch works at once and a quick
     * two-finger tap still undoes.
     */
    private fun adopt(ev: MotionEvent) {
        active = true
        handedOver = false
        downTimeMs = ev.downTime
        maxPointers = ev.pointerCount
        wandered = false
        navigated = false
        turnArmed = false
        turnBase = 0f
        downX.clear()
        downY.clear()
        for (i in 0 until ev.pointerCount) {
            downX[ev.getPointerId(i)] = ev.getX(i)
            downY[ev.getPointerId(i)] = ev.getY(i)
        }
        rebase(ev, -1)
    }

    private fun move(ev: MotionEvent) {
        if (!active) return
        checkWander(ev)
        if (ev.pointerCount >= 2) {
            pinch(ev)
            return
        }
        if (!fingersNavigate) return
        val x = ev.getX(0)
        val y = ev.getY(0)
        if (x != prevCx || y != prevCy) {
            view.panX += x - prevCx
            view.panY += y - prevCy
            navigated = true
            onViewChanged()
        }
        prevCx = x
        prevCy = y
    }

    private fun pointerUp(ev: MotionEvent) {
        if (!active) return
        checkWander(ev)
        val i = ev.actionIndex
        downX.remove(ev.getPointerId(i))
        downY.remove(ev.getPointerId(i))
        // The finger that left is not in the next frame, so the baseline is taken without it and
        // the page does not jump once when it goes.
        rebase(ev, i)
    }

    private fun up(ev: MotionEvent) {
        if (!active) return
        checkWander(ev)
        val isTap = !wandered && ev.eventTime - downTimeMs <= TAP_MS
        if (navigated && maxPointers >= 2) {
            val c = centroid(ev, -1)
            view.snapRotation(c.first, c.second)
            onViewChanged()
        }
        if (isTap) tap(maxPointers)
        reset()
    }

    /** The pinch: the centroid moves the page, the spread zooms, the angle turns. */
    private fun pinch(ev: MotionEvent) {
        val c = centroid(ev, -1)
        val two = firstTwo(ev, -1)
        if (two == null) return
        val cx = c.first
        val cy = c.second
        val dist = two.first
        val angle = two.second
        // Closer together than this the angle is noise, so such a move only translates.
        val usable = prevDist > MIN_SPREAD_PX && dist > MIN_SPREAD_PX
        val scale = if (usable) dist / prevDist else 1f
        val step = if (usable) wrapTurn(angle - prevAngle) else 0f
        turnBase += step
        // The arming frame applies nothing, so the page does not jump by the dead zone the moment
        // the turn starts counting; every frame after it is followed in full.
        val turn = if (turnArmed) step else 0f
        if (!turnArmed && abs(turnBase) > ViewTransform.SNAP_TOLERANCE) turnArmed = true
        if (cx != prevCx || cy != prevCy || scale != 1f || turn != 0f) {
            view.applyPinch(prevCx, prevCy, cx, cy, scale, turn)
            navigated = true
            onViewChanged()
        }
        prevCx = cx
        prevCy = cy
        prevDist = dist
        prevAngle = angle
    }

    private fun tap(fingers: Int) {
        if (fingers == 2) onUndo()
        else if (fingers == 3) onRedo()
        else if (fingers == 4) onToggleUi()
    }

    // ── measuring ────────────────────────────────────────────────────────────────────────────────

    /**
     * Marks the gesture as a drag if any pointer is further than [TAP_SLOP_PX] from where it went
     * down. The batched history is read too, so a fast flick that comes back inside one frame
     * cannot pass itself off as a tap and undo a stroke.
     *
     * A pointer with no recorded down position — one whose ACTION_DOWN the view swallowed as a
     * palm — counts as strayed: the safe direction, because the alternative is letting a finger
     * that has plainly moved pass the tap test and fire an undo.
     */
    private fun checkWander(ev: MotionEvent) {
        for (i in 0 until ev.pointerCount) {
            val id = ev.getPointerId(i)
            val sx = downX[id]
            val sy = downY[id]
            if (sx == null || sy == null) {
                wandered = true
                continue
            }
            for (h in 0 until ev.historySize) {
                if (strayed(ev.getHistoricalX(i, h) - sx, ev.getHistoricalY(i, h) - sy)) wandered = true
            }
            if (strayed(ev.getX(i) - sx, ev.getY(i) - sy)) wandered = true
        }
    }

    private fun strayed(dx: Float, dy: Float): Boolean = hypot(dx, dy) > TAP_SLOP_PX

    /** Mean of every pointer but [skip] (-1 for all of them), in screen px. */
    private fun centroid(ev: MotionEvent, skip: Int): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        var n = 0
        for (i in 0 until ev.pointerCount) {
            if (i == skip) continue
            x += ev.getX(i)
            y += ev.getY(i)
            n++
        }
        if (n == 0) return Pair(ev.getX(0), ev.getY(0))
        return Pair(x / n, y / n)
    }

    /** Distance and direction between the first two pointers but [skip], or null if only one is left. */
    private fun firstTwo(ev: MotionEvent, skip: Int): Pair<Float, Float>? {
        var a = -1
        var b = -1
        var seen = 0
        for (i in 0 until ev.pointerCount) {
            if (i == skip) continue
            if (seen == 0) a = i else if (seen == 1) b = i
            seen++
        }
        if (a < 0 || b < 0) return null
        val dx = ev.getX(b) - ev.getX(a)
        val dy = ev.getY(b) - ev.getY(a)
        return Pair(hypot(dx, dy), atan2(dy.toDouble(), dx.toDouble()).toFloat())
    }

    /** Re-reads the baseline without applying anything: the set of pointers has changed. */
    private fun rebase(ev: MotionEvent, skip: Int) {
        val c = centroid(ev, skip)
        prevCx = c.first
        prevCy = c.second
        val two = firstTwo(ev, skip)
        prevDist = if (two == null) 0f else two.first
        prevAngle = if (two == null) 0f else two.second
    }

    /** A turn between two frames is always the SHORT way round, or 179° → -179° spins the page. */
    private fun wrapTurn(a: Float): Float {
        val twoPi = 2.0 * PI
        var r = (a + PI) % twoPi
        if (r < 0.0) r += twoPi
        return (r - PI).toFloat()
    }

    companion object {
        /** Every finger has to be back up within this long of the first going down, ms. */
        const val TAP_MS = 250L

        /** …and none of them may wander further than this, screen px. */
        const val TAP_SLOP_PX = 20f

        /** Below this separation the two-finger angle is noise, so a move only translates, screen px. */
        const val MIN_SPREAD_PX = 12f
    }
}
