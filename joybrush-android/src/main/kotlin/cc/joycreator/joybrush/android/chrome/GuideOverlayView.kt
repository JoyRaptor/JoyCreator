package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import cc.joycreator.joybrush.core.guide.Guide
import cc.joycreator.joybrush.core.guide.GuideLines
import cc.joycreator.joybrush.core.guide.GuideSettings
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.view.ViewTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The guides on screen (JB-2.12): one transparent view over the drawing that draws JB-2.12a's [GuideLines] and nothing
 * else. It holds no engine, no canvas and no document — only the page's [ViewTransform] to place the lines — so a guide
 * has no way into a tile or an export (blueprint §3: "helpers are overlays, never part of the art").
 *
 * Lines are thin violet hints (visual language: GUIDE, a hint, never a state); the guide a stroke has locked onto brightens
 * while it is drawn. In [adjusting] mode the perspective points and the ruler's ends are round handles a finger drags;
 * otherwise the view takes no touch at all — except the symmetry line, which is always live.
 *
 * The symmetry line (owner, 2026-10-19) is the one guide that is always live: it is NOT one of [GuideSettings.all] — the
 * snapper must never see it — but it is drawn here, and it is dragged whenever it is on: by an end, by its body, or turned
 * with two fingers. Its angle snaps softly to 0/45/90/135/180 degrees.
 */
@SuppressLint("ViewConstructor")
class GuideOverlayView(private val kit: ChromeKit, private val transform: ViewTransform) : View(kit.context) {

    var settings: GuideSettings = GuideSettings.NONE
        set(value) { field = value; invalidate() }

    /** A stroke is locked onto a guide: the lines brighten (GuideSnapper.locked). */
    var locked = false
        set(value) { if (field != value) { field = value; invalidate() } }

    /**
     * The handles are shown and draggable. Off, the view takes no touch — except the symmetry line, whose two ends are
     * always there, because the line is dragged whenever it is on.
     */
    var adjusting = false
        set(value) { field = value; invalidate() }

    /** The settings changed by a handle drag; called on every move and once more when the finger lifts. */
    var onChanged: ((GuideSettings, done: Boolean) -> Unit)? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The visible page in document px, filled in place: the same numbers every frame, no new array. */
    private val viewDoc = FloatArray(4)

    // ── the symmetry's gesture ──

    /** The handle under the finger ("vp0", "ra0", "rb0", "sa", "sb"), or null. */
    private var grabbed: String? = null

    /** The finger holds the line's body: both ends move together. */
    private var bodyGrab = false

    /** Two fingers: the line turns round its own middle. */
    private var rotating = false

    private var pointer0 = MotionEvent.INVALID_POINTER_ID
    private var pointer1 = MotionEvent.INVALID_POINTER_ID

    /** Where the finger was last seen, in document px: the body's move is a difference, not a place. */
    private var lastDocX = 0f
    private var lastDocY = 0f

    /** The angle between the two fingers last time, in radians. */
    private var lastFingerAngle = 0.0

    /** The multiple of 45 degrees the line last snapped to, so the tick is once per snap and not once per frame. */
    private var snappedTo = NO_SNAP

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The visible page, in document px: the box around the four screen corners (the page may be turned). */
    private fun visibleDoc(): FloatArray {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0..3) {
            val sx = if (i == 1 || i == 3) width.toFloat() else 0f
            val sy = if (i < 2) 0f else height.toFloat()
            val (dx, dy) = transform.screenToDoc(sx, sy)
            l = minOf(l, dx); t = minOf(t, dy); r = maxOf(r, dx); b = maxOf(b, dy)
        }
        viewDoc[0] = l; viewDoc[1] = t; viewDoc[2] = r; viewDoc[3] = b
        return viewDoc
    }

    override fun onDraw(c: Canvas) {
        val s = settings
        if (s.isEmpty) return
        val zoom = transform.zoom
        val view = visibleDoc()
        val alpha = if (locked) 235 else 120
        // A soft dark line under the violet, so a guide reads on white paper and on black paint alike.
        val under = ColorUtils.setAlphaComponent(kit.p.ground, if (locked) 110 else 60)
        val over = ColorUtils.setAlphaComponent(kit.p.guide, alpha)
        for (g in s.all()) {
            val shown = if (g is Guide.Grid) GuideSettings.withMinScreenSpacing(g, zoom) else g
            val segs = GuideLines.visible(shown, view, zoom)
            for (pass in 0..1) {
                line.color = if (pass == 0) under else over
                line.strokeWidth = if (pass == 0) kit.dp(2.5f) else kit.dp(if (locked) 1.5f else 1f)
                for (seg in segs) {
                    val (x0, y0) = transform.docToScreen(seg[0], seg[1])
                    val (x1, y1) = transform.docToScreen(seg[2], seg[3])
                    c.drawLine(x0, y0, x1, y1, line)
                }
            }
        }
        // The symmetry line is drawn on its own: it is not one of s.all(), so the snapper never sees it.
        s.symmetry?.let { sym -> drawSymmetry(c, sym, view, zoom) }
        if (adjusting) for (p in adjustHandles(s)) drawHandle(c, p.second)
        // The symmetry's ends are always there: the line is dragged whenever it is on.
        s.symmetry?.let { sym ->
            drawHandle(c, sym.a)
            drawHandle(c, sym.b)
        }
    }

    /** The symmetry line, in the guide colour and with the same two passes as the other guides. */
    private fun drawSymmetry(c: Canvas, sym: Guide.Symmetry, view: FloatArray, zoom: Float) {
        val segs = symmetrySegments(sym, view, zoom)
        if (segs.isEmpty()) return
        // One copy is the original stroke alone: the line is still there, but dim, so it can be picked up again.
        val dim = !sym.active
        val under = ColorUtils.setAlphaComponent(kit.p.ground, if (locked) 110 else if (dim) 30 else 60)
        val over = ColorUtils.setAlphaComponent(kit.p.guide, if (locked) 235 else if (dim) 70 else 120)
        for (pass in 0..1) {
            line.color = if (pass == 0) under else over
            line.strokeWidth = if (pass == 0) kit.dp(2.5f) else kit.dp(if (locked) 1.5f else 1f)
            for (seg in segs) {
                val (x0, y0) = transform.docToScreen(seg[0], seg[1])
                val (x1, y1) = transform.docToScreen(seg[2], seg[3])
                c.drawLine(x0, y0, x1, y1, line)
            }
        }
    }

    /** The symmetry's drawn segments, kept until the line or the page moves: no work, and no garbage, every frame. */
    private var symmetrySegs: List<FloatArray> = emptyList()
    private var symmetryKey: SymKey? = null

    private class SymKey(
        val ax: Double, val ay: Double, val bx: Double, val by: Double,
        val left: Float, val top: Float, val right: Float, val bottom: Float, val zoom: Float,
    )

    private fun symmetrySegments(sym: Guide.Symmetry, view: FloatArray, zoom: Float): List<FloatArray> {
        val k = symmetryKey
        if (k != null && k.ax == sym.a.x && k.ay == sym.a.y && k.bx == sym.b.x && k.by == sym.b.y &&
            k.left == view[0] && k.top == view[1] && k.right == view[2] && k.bottom == view[3] && k.zoom == zoom
        ) return symmetrySegs
        val segs = GuideLines.visible(sym, view, zoom)
        symmetrySegs = segs
        symmetryKey = SymKey(sym.a.x, sym.a.y, sym.b.x, sym.b.y, view[0], view[1], view[2], view[3], zoom)
        return segs
    }

    private fun drawHandle(c: Canvas, p: Pt) {
        val (x, y) = transform.docToScreen(p.x.toFloat(), p.y.toFloat())
        // A point off the screen is drawn pinned to the edge, so it can always be grabbed back.
        val m = kit.dp(HANDLE_DP)
        val cx = x.coerceIn(m, width - m)
        val cy = y.coerceIn(m, height - m)
        handle.style = Paint.Style.FILL
        handle.color = ColorUtils.setAlphaComponent(kit.p.ground, 150)
        c.drawCircle(cx, cy, kit.dp(HANDLE_DP), handle)
        handle.style = Paint.Style.STROKE
        handle.strokeWidth = kit.dp(2f)
        handle.color = kit.p.guide
        c.drawCircle(cx, cy, kit.dp(HANDLE_DP), handle)
        handle.style = Paint.Style.FILL
        handle.color = kit.p.drawerInk
        c.drawCircle(cx, cy, kit.dp(3f), handle)
    }

    /** Every point the "adjust guides" mode moves, with a key saying which it is. */
    private fun adjustHandles(s: GuideSettings): List<Pair<String, Pt>> {
        val out = ArrayList<Pair<String, Pt>>()
        s.perspective?.vanishingPoints?.forEachIndexed { i, p -> out.add("vp$i" to p) }
        s.tracers.forEachIndexed { i, t -> if (t is Guide.Ruler) { out.add("ra$i" to t.a); out.add("rb$i" to t.b) } }
        return out
    }

    /**
     * The handle under the finger, or null. The nearest one within reach wins; a point off the screen is judged where it
     * is drawn, pinned to the edge.
     */
    private fun handleAt(s: GuideSettings, x: Float, y: Float): String? {
        val reach = kit.dp(HANDLE_DP + 10f)
        val m = kit.dp(HANDLE_DP)
        var best: String? = null
        var bestDistance = Float.MAX_VALUE
        fun consider(key: String, p: Pt) {
            val (sx, sy) = transform.docToScreen(p.x.toFloat(), p.y.toFloat())
            val d = hypot(sx.coerceIn(m, width - m) - x, sy.coerceIn(m, height - m) - y)
            if (d < bestDistance) { bestDistance = d; best = key }
        }
        if (adjusting) {
            s.perspective?.vanishingPoints?.forEachIndexed { i, p -> consider("vp$i", p) }
            s.tracers.forEachIndexed { i, t -> if (t is Guide.Ruler) { consider("ra$i", t.a); consider("rb$i", t.b) } }
        }
        s.symmetry?.let { sym -> consider(KEY_SYM_A, sym.a); consider(KEY_SYM_B, sym.b) }
        return best?.takeIf { bestDistance <= reach }
    }

    /** Is the point on the symmetry's line? The line is infinite, like the ruler's. */
    private fun nearLine(sym: Guide.Symmetry, x: Float, y: Float): Boolean {
        val (px, py) = transform.screenToDoc(x, y)
        val dx = sym.b.x - sym.a.x
        val dy = sym.b.y - sym.a.y
        val len = hypot(dx, dy)
        if (!(len > 0.0) || !len.isFinite()) return false
        // The distance from the point to the line through a and b, in document px, turned into screen px.
        val distance = abs(dx * (py - sym.a.y) - dy * (px - sym.a.x)) / len
        return distance * transform.zoom <= kit.dp(BODY_DP)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val sym = settings.symmetry
        if (!adjusting && sym == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                grabbed = null
                bodyGrab = false
                rotating = false
                snappedTo = NO_SNAP
                val key = handleAt(settings, e.x, e.y)
                if (key != null) {
                    grabbed = key
                } else if (sym != null && nearLine(sym, e.x, e.y)) {
                    // The body of the line: both ends move together.
                    bodyGrab = true
                    val (dx, dy) = transform.screenToDoc(e.x, e.y)
                    lastDocX = dx
                    lastDocY = dy
                } else {
                    // Nothing of ours under the finger: the canvas keeps the stroke.
                    return false
                }
                pointer0 = e.getPointerId(0)
                pointer1 = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val held = grabbed == KEY_SYM_A || grabbed == KEY_SYM_B || bodyGrab
                if (!held || pointer1 != MotionEvent.INVALID_POINTER_ID) return true
                val id = e.getPointerId(e.actionIndex)
                val a = e.findPointerIndex(pointer0)
                val b = e.findPointerIndex(id)
                if (a < 0 || b < 0) return true
                pointer1 = id
                // The angle between the two fingers is the turn; the line turns with them.
                lastFingerAngle = fingerAngle(e, a, b)
                rotating = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val next = step(e)
                settings = next
                onChanged?.invoke(next, false)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val gone = e.getPointerId(e.actionIndex)
                if (gone == pointer0 || gone == pointer1) {
                    // Keep going with whichever finger stays, from where it is now: no jump.
                    val keep = if (gone == pointer0) pointer1 else pointer0
                    if (keep != MotionEvent.INVALID_POINTER_ID) {
                        pointer0 = keep
                        val i = e.findPointerIndex(keep)
                        if (i >= 0) {
                            val (dx, dy) = transform.screenToDoc(e.getX(i), e.getY(i))
                            lastDocX = dx
                            lastDocY = dy
                        }
                    } else {
                        pointer0 = MotionEvent.INVALID_POINTER_ID
                    }
                    pointer1 = MotionEvent.INVALID_POINTER_ID
                    rotating = false
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val next = step(e)
                settings = next
                onChanged?.invoke(next, true)
                grabbed = null
                bodyGrab = false
                rotating = false
                pointer0 = MotionEvent.INVALID_POINTER_ID
                pointer1 = MotionEvent.INVALID_POINTER_ID
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                grabbed = null
                bodyGrab = false
                rotating = false
                pointer0 = MotionEvent.INVALID_POINTER_ID
                pointer1 = MotionEvent.INVALID_POINTER_ID
                return true
            }
        }
        return false
    }

    /** One step of the gesture: the settings as they are after this move. */
    private fun step(e: MotionEvent): GuideSettings {
        if (rotating) return turned(e)
        val key = grabbed
        val index = e.findPointerIndex(pointer0).let { if (it >= 0) it else 0 }
        if (key != null) {
            // An end is placed where the finger is.
            val (dx, dy) = transform.screenToDoc(e.getX(index), e.getY(index))
            var next = moved(settings, key, Pt(dx.toDouble(), dy.toDouble()))
            val sym = next.symmetry
            if (sym != null && (key == KEY_SYM_A || key == KEY_SYM_B)) {
                val (na, nb) = snapped(sym.a, sym.b, if (key == KEY_SYM_A) Anchor.END_A else Anchor.END_B)
                next = next.copy(symmetry = sym.copy(a = na, b = nb))
            }
            return next
        }
        if (bodyGrab) {
            // The body is a difference, not a place: both ends go the same way.
            val sym = settings.symmetry ?: return settings
            val (dx, dy) = transform.screenToDoc(e.getX(index), e.getY(index))
            val ddx = (dx - lastDocX).toDouble()
            val ddy = (dy - lastDocY).toDouble()
            lastDocX = dx
            lastDocY = dy
            return settings.copy(symmetry = sym.copy(
                a = Pt(sym.a.x + ddx, sym.a.y + ddy),
                b = Pt(sym.b.x + ddx, sym.b.y + ddy),
            ))
        }
        return settings
    }

    /**
     * The two-finger turn: both ends go round the line's OWN middle, keeping the length. The two fingers only give the
     * angle — the turn they make is applied to the line, so the line turns with them whether or not they are on it.
     */
    private fun turned(e: MotionEvent): GuideSettings {
        val sym = settings.symmetry ?: return settings
        val a = e.findPointerIndex(pointer0)
        val b = e.findPointerIndex(pointer1)
        if (a < 0 || b < 0) return settings
        val angle = fingerAngle(e, a, b)
        var d = angle - lastFingerAngle
        // Unwrapped, so a turn past the straight-up mark does not jump the other way.
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        lastFingerAngle = angle
        val c = cos(d)
        val s = sin(d)
        // The middle is the pivot: it stays where it is and the ends go round it, so the length is kept.
        val mx = (sym.a.x + sym.b.x) / 2.0
        val my = (sym.a.y + sym.b.y) / 2.0
        val ax = sym.a.x - mx
        val ay = sym.a.y - my
        val bx = sym.b.x - mx
        val by = sym.b.y - my
        val na = Pt(mx + ax * c - ay * s, my + ax * s + ay * c)
        val nb = Pt(mx + bx * c - by * s, my + bx * s + by * c)
        val (sa, sb) = snapped(na, nb, Anchor.MIDDLE)
        return settings.copy(symmetry = sym.copy(a = sa, b = sb))
    }

    /** The angle between two fingers, in radians. The frame does not matter: only the turn between two frames is used. */
    private fun fingerAngle(e: MotionEvent, a: Int, b: Int): Double =
        atan2((e.getY(b) - e.getY(a)).toDouble(), (e.getX(b) - e.getX(a)).toDouble())

    /**
     * The soft 45-degree snap: the line's angle is pulled to the nearest multiple of 45 degrees when it comes within
     * [SNAP_TOLERANCE] of one, keeping the length and the anchor — the grabbed end, or the middle for a turn. The finger
     * may drag on past it: there is no detent and nothing latches. A tick, once per snap and not once per frame.
     */
    private fun snapped(a: Pt, b: Pt, anchor: Anchor): Pair<Pt, Pt> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (!(len > 0.0) || !len.isFinite()) return a to b
        val angle = atan2(dy, dx)
        val k = Math.round(angle / SNAP_STEP).toInt()
        val target = k * SNAP_STEP
        if (abs(angle - target) > SNAP_TOLERANCE) {
            snappedTo = NO_SNAP
            return a to b
        }
        if (k != snappedTo) {
            snappedTo = k
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        val ux = cos(target)
        val uy = sin(target)
        return when (anchor) {
            // The grabbed end stays where the finger is; the other end goes onto the snapped angle.
            Anchor.END_A -> a to Pt(a.x + len * ux, a.y + len * uy)
            Anchor.END_B -> Pt(b.x - len * ux, b.y - len * uy) to b
            // A turn turns about the middle: the middle stays, the ends go round it.
            Anchor.MIDDLE -> {
                val mx = (a.x + b.x) / 2.0
                val my = (a.y + b.y) / 2.0
                val half = len / 2.0
                Pt(mx - half * ux, my - half * uy) to Pt(mx + half * ux, my + half * uy)
            }
        }
    }

    private fun moved(s: GuideSettings, key: String, to: Pt): GuideSettings {
        if (key == KEY_SYM_A) return s.copy(symmetry = s.symmetry?.copy(a = to))
        if (key == KEY_SYM_B) return s.copy(symmetry = s.symmetry?.copy(b = to))
        if (key.startsWith("vp")) {
            val i = key.removePrefix("vp").toIntOrNull() ?: return s
            return s.perspective?.let { p ->
                s.copy(perspective = Guide.Perspective(p.vanishingPoints.mapIndexed { j, v -> if (j == i) to else v }))
            } ?: s
        }
        if (key.startsWith("ra") || key.startsWith("rb")) {
            val i = key.removePrefix("ra").removePrefix("rb").toIntOrNull() ?: return s
            return s.copy(tracers = s.tracers.mapIndexed { j, t ->
                if (j == i && t is Guide.Ruler) (if (key.startsWith("ra")) t.copy(a = to) else t.copy(b = to)) else t
            })
        }
        return s
    }

    /** What the soft snap keeps still while the angle is pulled onto a quarter turn. */
    private enum class Anchor { END_A, END_B, MIDDLE }

    private companion object {
        const val HANDLE_DP = 13f

        /**
         * How close a finger has to be to the line's body to take hold of it, dp. Kept tight (10 dp): painting exactly ON
         * the symmetry axis is ordinary work, and the axis is where the copies overlap, so a fat grab area would eat
         * those strokes. A touch outside this, and outside the end handles, falls through to the canvas.
         */
        const val BODY_DP = 10f

        /** The symmetry's two ends, as handle keys. */
        const val KEY_SYM_A = "sa"
        const val KEY_SYM_B = "sb"

        /** A quarter turn: the multiples the line snaps to. */
        const val SNAP_STEP = PI / 4.0

        /** How close to a quarter turn the line has to come before it is pulled onto it, radians. */
        const val SNAP_TOLERANCE = 3.0 * PI / 180.0

        /** No quarter turn: the line is not on one. */
        const val NO_SNAP = Int.MIN_VALUE
    }
}
