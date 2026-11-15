package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.chrome.Footprint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * BRUSH-UI section K.4: the brush's footprint, the mark it leaves seen from above, with the pen upright, at 35° and laid
 * over at 70° ([Footprint], from the brush's own code). A brush that ignores tilt shows one mark. The mark nearest the
 * pen's tilt right now is lit. When [onTap] is given (Unibrush), tapping it opens the tip's shape editor.
 */
@SuppressLint("ViewConstructor")
class FootprintView(private val kit: ChromeKit, onTap: (() -> Unit)? = null) : View(kit.context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private var preset: BrushPreset? = null
    /** One outline per shown tilt, in document px. */
    private var marks: List<List<DoubleArray>> = emptyList()
    private var tiltsDeg: List<Int> = emptyList()
    private var liveTilt = Float.NaN

    init {
        if (onTap != null) {
            isClickable = true; isFocusable = true
            setOnClickListener { onTap() }
            kit.label(this, "Footprint: the mark this brush leaves as the pen tilts. Tap to edit the tip's shape")
        } else kit.label(this, "Footprint: the mark this brush leaves as the pen tilts")
    }

    fun show(p: BrushPreset) {
        if (p == preset) return
        preset = p
        val all = TILTS.map { deg -> deg to safely { Footprint.outline(p, Footprint.held(p, (deg * PI / 180).toFloat())) } }
        val varies = all.zipWithNext().any { (a, b) -> differs(a.second, b.second) }
        val shown = if (varies) all else all.take(1)
        marks = shown.map { it.second }; tiltsDeg = shown.map { it.first }
        invalidate()
    }

    /** The pen's tilt now (radians), or NaN; lights the nearest mark. */
    fun showTilt(tilt: Float) {
        if (tiltsDeg.size < 2) return
        val was = nearest(liveTilt); liveTilt = tilt
        if (nearest(tilt) != was) invalidate()
    }

    internal fun shownTilts(): List<Int> = tiltsDeg

    private fun nearest(t: Float): Int =
        if (!t.isFinite()) -1 else tiltsDeg.indices.minByOrNull { abs(tiltsDeg[it] - t * 180f / PI.toFloat()) } ?: -1

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f || marks.isEmpty()) return
        val n = marks.size
        val label = kit.dp(10f)
        val pad = kit.dp(4f)
        val cellW = (w - pad * 2) / n
        val cellH = h - pad * 2 - label
        // Each mark centred in its cell on its own middle; one scale for all, so they compare: the largest fits.
        var spanX = 0.0; var spanY = 0.0
        for (m in marks) { spanX = max(spanX, m.maxOf { it[0] } - m.minOf { it[0] }); spanY = max(spanY, m.maxOf { it[1] } - m.minOf { it[1] }) }
        if (spanX <= 0.0 && spanY <= 0.0) return
        val room = kit.dp(4f)
        val scale = min((cellW - room) / max(spanX, 1e-6).toFloat(), (cellH - room) / max(spanY, 1e-6).toFloat())
        val lit = nearest(liveTilt)
        words.textSize = kit.dp(8.5f); words.color = kit.p.drawerDim
        for ((i, m) in marks.withIndex()) {
            val cx = pad + cellW * (i + .5f); val cy = pad + cellH / 2f
            val mx = ((m.maxOf { it[0] } + m.minOf { it[0] }) / 2).toFloat(); val my = ((m.maxOf { it[1] } + m.minOf { it[1] }) / 2).toFloat()
            path.rewind()
            for ((j, pt) in m.withIndex()) {
                val x = cx + (pt[0].toFloat() - mx) * scale; val y = cy + (pt[1].toFloat() - my) * scale
                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            fill.color = kit.ink(.18f); c.drawPath(path, fill)
            line.strokeWidth = kit.dp(1.2f)
            line.color = if (i == lit) kit.p.stateSelected else kit.ink(.6f)
            c.drawPath(path, line)
            c.drawText(if (n > 1) "${tiltsDeg[i]}°" else "same at any tilt", cx, h - pad, words)
        }
    }

    private companion object {
        val TILTS = listOf(0, 35, 70)

        fun safely(f: () -> List<DoubleArray>): List<DoubleArray> =
            try { f() } catch (e: Exception) { android.util.Log.w("JoyBrushFootprint", "no footprint", e); emptyList() }

        fun differs(a: List<DoubleArray>, b: List<DoubleArray>): Boolean =
            a.size != b.size || a.indices.any { abs(a[it][0] - b[it][0]) > 0.5 || abs(a[it][1] - b[it][1]) > 0.5 }
    }
}
