package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import cc.joycreator.joybrush.core.guide.Guide
import cc.joycreator.joybrush.core.guide.GuideLines
import cc.joycreator.joybrush.core.guide.GuideSettings
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.view.ViewTransform
import kotlin.math.hypot

/**
 * The guides on screen (JB-2.12): one transparent view over the drawing that draws JB-2.12a's [GuideLines] and nothing
 * else. It holds no engine, no canvas and no document — only the page's [ViewTransform] to place the lines — so a guide
 * has no way into a tile or an export (blueprint §3: "helpers are overlays, never part of the art").
 *
 * Lines are thin violet hints (visual language: GUIDE, a hint, never a state); the guide a stroke has locked onto brightens
 * while it is drawn. In [adjusting] mode the perspective points and the ruler's ends are round handles a finger drags;
 * otherwise the view takes no touch at all.
 */
@SuppressLint("ViewConstructor")
class GuideOverlayView(private val kit: ChromeKit, private val transform: ViewTransform) : View(kit.context) {

    var settings: GuideSettings = GuideSettings.NONE
        set(value) { field = value; invalidate() }

    /** A stroke is locked onto a guide: the lines brighten (GuideSnapper.locked). */
    var locked = false
        set(value) { if (field != value) { field = value; invalidate() } }

    /** The handles are shown and draggable. Off, the view takes no touch. */
    var adjusting = false
        set(value) { field = value; invalidate() }

    /** The settings changed by a handle drag; called on every move and once more when the finger lifts. */
    var onChanged: ((GuideSettings, done: Boolean) -> Unit)? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corners = FloatArray(8)

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The visible page, in document px: the box around the four screen corners (the page may be turned). */
    private fun visibleDoc(): FloatArray {
        val pts = listOf(0f to 0f, width.toFloat() to 0f, 0f to height.toFloat(), width.toFloat() to height.toFloat())
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for ((x, y) in pts) {
            val (dx, dy) = transform.screenToDoc(x, y)
            l = minOf(l, dx); t = minOf(t, dy); r = maxOf(r, dx); b = maxOf(b, dy)
        }
        return floatArrayOf(l, t, r, b)
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
        if (adjusting) for (p in handles(s)) drawHandle(c, p.second)
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

    /** Every draggable point, with a key saying which it is. */
    private fun handles(s: GuideSettings): List<Pair<String, Pt>> {
        val out = ArrayList<Pair<String, Pt>>()
        s.perspective?.vanishingPoints?.forEachIndexed { i, p -> out.add("vp$i" to p) }
        s.tracers.forEachIndexed { i, t -> if (t is Guide.Ruler) { out.add("ra$i" to t.a); out.add("rb$i" to t.b) } }
        return out
    }

    private var grabbed: String? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!adjusting) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val reach = kit.dp(HANDLE_DP + 10f)
                val m = kit.dp(HANDLE_DP)
                grabbed = handles(settings).minByOrNull { (_, p) ->
                    val (x, y) = transform.docToScreen(p.x.toFloat(), p.y.toFloat())
                    hypot(x.coerceIn(m, width - m) - e.x, y.coerceIn(m, height - m) - e.y)
                }?.takeIf { (_, p) ->
                    val (x, y) = transform.docToScreen(p.x.toFloat(), p.y.toFloat())
                    hypot(x.coerceIn(m, width - m) - e.x, y.coerceIn(m, height - m) - e.y) <= reach
                }?.first
                if (grabbed == null) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val key = grabbed ?: return false
                val (dx, dy) = transform.screenToDoc(e.x, e.y)
                val next = moved(settings, key, Pt(dx.toDouble(), dy.toDouble()))
                settings = next
                val done = e.actionMasked == MotionEvent.ACTION_UP
                onChanged?.invoke(next, done)
                if (done) grabbed = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> { grabbed = null; return true }
        }
        return false
    }

    private fun moved(s: GuideSettings, key: String, to: Pt): GuideSettings {
        val i = key.drop(2).toIntOrNull() ?: key.drop(3).toIntOrNull() ?: return s
        return when {
            key.startsWith("vp") -> s.perspective?.let { p ->
                s.copy(perspective = Guide.Perspective(p.vanishingPoints.mapIndexed { j, v -> if (j == i) to else v }))
            } ?: s
            key.startsWith("ra") || key.startsWith("rb") -> s.copy(tracers = s.tracers.mapIndexed { j, t ->
                if (j == i && t is Guide.Ruler) (if (key.startsWith("ra")) t.copy(a = to) else t.copy(b = to)) else t
            })
            else -> s
        }
    }

    private companion object {
        const val HANDLE_DP = 13f
    }
}
