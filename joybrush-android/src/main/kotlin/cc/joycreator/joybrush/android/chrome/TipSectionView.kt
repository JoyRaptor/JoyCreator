package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import cc.joycreator.joybrush.core.brush.TipProfile
import cc.joycreator.joybrush.core.brush.TipProfileSpec
import kotlin.math.abs
import kotlin.math.exp

/**
 * BRUSH-UI item 4 (owner 11-14: "the tip's cross-section is buried far down; it belongs beside the pen pad"): the tip seen
 * edge-on, how much paint it lays from one rim through the centre to the other. It follows the tip profile and the
 * Softness slider with the same rules as the tip shader (`jb_profile.glsl`): Hard ignores hardness and keeps a hard rim;
 * legacy and Smooth soften from `hardness` to the rim; Linear, Gaussian and Custom fall off along their own shape from
 * `hardness` outwards.
 */
@SuppressLint("ViewConstructor")
class TipSectionView(private val kit: ChromeKit) : View(kit.context) {
    private var profile: TipProfileSpec? = null
    private var hardness = .9f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    init { kit.label(this, "The tip from the side: how much paint it lays from rim to rim") }

    fun show(profile: TipProfileSpec?, hardness: Float) {
        if (profile == this.profile && hardness == this.hardness) return
        this.profile = profile; this.hardness = hardness.coerceIn(0f, 1f)
        invalidate()
    }

    /** Coverage at radius [d] (0 centre, 1 rim). Pure, so it is tested off the phone. */
    internal fun coverage(d: Float): Float = coverageOf(profile, hardness, d)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pad = kit.dp(6f)
        val left = pad; val right = w - pad; val top = pad; val bottom = h - pad
        line.color = kit.ink(.18f); line.strokeWidth = kit.dp(1f)
        c.drawLine(left, bottom, right, bottom, line)
        path.reset()
        path.moveTo(left, bottom)
        val steps = 64
        for (i in 0..steps) {
            val x = left + (right - left) * i / steps
            val d = abs(i / steps.toFloat() * 2f - 1f)
            path.lineTo(x, bottom - (bottom - top) * coverage(d))
        }
        path.lineTo(right, bottom); path.close()
        fill.color = kit.ink(.28f)
        c.drawPath(path, fill)
        line.color = kit.p.stateSelected; line.strokeWidth = kit.dp(1.5f)
        c.drawPath(path, line)
    }

    companion object {
        internal fun coverageOf(profile: TipProfileSpec?, hardness: Float, d: Float): Float {
            if (d >= 1f) return 0f
            val inner = hardness.coerceIn(0f, .999f)
            return when (profile?.kind) {
                TipProfile.hard -> if (d < .97f) 1f else (1f - (d - .97f) / .03f).coerceIn(0f, 1f)
                null, TipProfile.smooth -> 1f - smoothstep(inner, 1f, d)
                else -> {
                    if (d <= inner) return 1f
                    val t = ((d - inner) / (1f - inner)).coerceIn(0f, 1f)
                    when (profile.kind) {
                        TipProfile.linear -> 1f - t
                        TipProfile.gaussian -> { val tail = exp(-4.5f); ((exp(-4.5f * t * t) - tail) / (1f - tail)).coerceIn(0f, 1f) }
                        else -> custom(profile.curve, t)
                    }
                }
            }
        }

        private fun smoothstep(a: Float, b: Float, x: Float): Float {
            val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun custom(curve: List<List<Float>>, t: Float): Float {
            if (curve.size < 2) return 1f - t
            for (i in 1 until curve.size) {
                val (x0, y0) = curve[i - 1].let { it[0] to it[1] }
                val (x1, y1) = curve[i].let { it[0] to it[1] }
                if (t <= x1) return if (x1 <= x0) y1 else (y0 + (y1 - y0) * (t - x0) / (x1 - x0)).coerceIn(0f, 1f)
            }
            return curve.last()[1].coerceIn(0f, 1f)
        }
    }
}
