package cc.joycreator.joybrush.android.chrome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_MEDIA
import cc.joycreator.joybrush.core.brush.ENGINE_TUFT
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.chrome.Footprint
import cc.joycreator.joybrush.core.brush.mediaScale
import cc.joycreator.joybrush.core.brush.scaled
import cc.joycreator.joybrush.core.media.DOC_PX_PER_MM
import cc.joycreator.joybrush.core.media.MediaInput
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The brush's outline under a hovering pen (the owner, 2026-11-11: "a hover of what its maximum could be, very thin
 * outline, so that I can see what angle I'm actually putting things down at, because sometimes it's not quite right").
 *
 * Drawn on the screen from what the pen reports while it hovers: where it is, how far it is tilted and which way its
 * barrel leans. A blade lies from the pen's point along the lean, as long as that tilt lays down; a hair brush or the
 * trowel lies the same way before it has moved; everything else is a circle of its size. The solid outline is what this
 * hold would put down; the faint one is the most the tool can cover. It never takes a touch.
 */
class HoverOutlineView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * density; color = 0xB0000000.toInt() }
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.6f * density; color = 0xD0FFFFFF.toInt() }
    private val faintDark = Paint(dark).apply { color = 0x48000000; strokeWidth = 0.8f * density }
    private val path = Path()

    private var shown = false
    private var px = 0f
    private var py = 0f
    private var tilt = 0f
    private var orientation = 0f
    private var zoom = 1f
    private var preset: BrushPreset? = null

    init { isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** The pen hovers at ([x], [y]) in this view's px, tilted [tilt] rad from upright, its tip pointing [orientation] (Android's axis). */
    fun show(x: Float, y: Float, tilt: Float, orientation: Float, zoom: Float, preset: BrushPreset?) {
        px = x; py = y; this.tilt = tilt; this.orientation = orientation; this.zoom = zoom; this.preset = preset
        shown = preset != null
        // BRUSH-UI item 2: does this pen report its lean while hovering, or only on contact? (The Note 9's digitiser has
        // tilt X/Y and Android derives orientation from them.) At most twice a second, under JoyBrushHover.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastLog > 500) {
            lastLog = now
            android.util.Log.i("JoyBrushHover", "tilt=%.3f orientation=%.3f".format(tilt, orientation))
        }
        invalidate()
    }
    private var lastLog = 0L
    private companion object { const val TRACE_PX = 64f }

    fun hide() { if (shown) { shown = false; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        val p = preset ?: return
        if (!shown) return
        // The barrel leans away from where the tip points (MediaInput: the media turn the pen's axis half round).
        val bx = -sin(orientation)
        val by = cos(orientation)
        val t = min(1.0, tilt / (MediaInput.REACH_DEG * PI / 180)).toFloat()
        val mm = (DOC_PX_PER_MM * zoom).toFloat()
        val paste = p.media?.scaled(p.mediaScale())?.paste
        when {
            paste != null && paste.blade -> {
                val edgeHalf = (paste.edgeHalfMm ?: paste.bladeHalfMm).toFloat()
                val len = (paste.edgeMinMm + (paste.bladeLenMm - paste.edgeMinMm) * t).toFloat()
                slab(canvas, bx, by, paste.bladeLenMm.toFloat() * mm, max(edgeHalf, 0.2f) * mm, faintDark, null)
                slab(canvas, bx, by, len * mm, max(edgeHalf, 0.2f) * mm, dark, light)
            }
            paste != null -> {
                val half = (paste.widthMm / 2).toFloat() * mm
                slab(canvas, bx, by, (paste.lenMm * 1.4).toFloat() * mm, half, faintDark, null)
                slab(canvas, bx, by, (paste.lenMm * (0.25 + 0.75 * 0.6) * (1 + 0.4 * t)).toFloat() * mm, half, dark, light)
            }
            else -> {
                val shape = dabOutline(p, t)
                if (shape != null) {
                    // BRUSH-UI K hover outline: the dab this brush would lay with the pen held like this (Footprint, the same
                    // outline the editor shows), traced at 64 px and scaled to the brush's size, turned to the pen's lean.
                    val k = p.size.base.toFloat() * zoom / TRACE_PX
                    val a = atan2(by, bx)
                    val ca = cos(a); val sa = sin(a)
                    path.rewind()
                    shape.forEachIndexed { i, pt ->
                        val x = pt[0].toFloat() * k; val y = pt[1].toFloat() * k
                        val X = px + x * ca - y * sa; val Y = py + x * sa + y * ca
                        if (i == 0) path.moveTo(X, Y) else path.lineTo(X, Y)
                    }
                    path.close()
                    canvas.drawPath(path, dark)
                    canvas.drawPath(path, light)
                } else {
                    val r = max(1.5f * density, p.size.base.toFloat() * zoom / 2)
                    canvas.drawCircle(px, py, r, dark)
                    canvas.drawCircle(px, py, r, light)
                }
            }
        }
    }

    private var cachedFor: BrushPreset? = null
    private var cachedTilt = -1f
    private var cachedShape: List<DoubleArray>? = null

    /** The dab's outline for this hold, or null for brushes whose footprint is just a circle. Kept while brush and tilt (to 0.05 rad) are unchanged. */
    private fun dabOutline(p: BrushPreset, t: Float): List<DoubleArray>? {
        val tiltRad = (Math.round(tilt / 0.05f) * 0.05f).coerceIn(0f, (MediaInput.REACH_DEG * PI / 180).toFloat())
        if (cachedFor !== p || cachedTilt != tiltRad) {
            cachedFor = p; cachedTilt = tiltRad
            cachedShape = try {
                val o = Footprint.outline(p, Footprint.held(p, tiltRad))
                if (p.engine in setOf(ENGINE_MEDIA, ENGINE_TUFT, ENGINE_WATERCOLOUR)) null else o
            } catch (e: Exception) { null }
        }
        return cachedShape
    }

    /** A strip from the pen's point along ([bx], [by]), [len] px long and [half] px to each side, its far end rounded. */
    private fun slab(c: Canvas, bx: Float, by: Float, len: Float, half: Float, a: Paint, b: Paint?) {
        val nx = -by
        val ny = bx
        val h = max(half, 0.75f * density)
        path.rewind()
        path.moveTo(px + nx * h, py + ny * h)
        path.lineTo(px + bx * len + nx * h, py + by * len + ny * h)
        path.lineTo(px + bx * len - nx * h, py + by * len - ny * h)
        path.lineTo(px - nx * h, py - ny * h)
        path.close()
        c.drawPath(path, a)
        if (b != null) c.drawPath(path, b)
    }
}
