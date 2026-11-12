package cc.joycreator.joybrush.android.chrome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.mediaScale
import cc.joycreator.joybrush.core.brush.scaled
import cc.joycreator.joybrush.core.media.DOC_PX_PER_MM
import cc.joycreator.joybrush.core.media.MediaInput
import kotlin.math.PI
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
        invalidate()
    }

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
                val r = max(1.5f * density, p.size.base.toFloat() * zoom / 2)
                canvas.drawCircle(px, py, r, dark)
                canvas.drawCircle(px, py, r, light)
            }
        }
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
