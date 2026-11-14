package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.MEDIUM_PASTE
import cc.joycreator.joybrush.core.brush.mediaScale
import cc.joycreator.joybrush.core.brush.scaled
import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * What a brush would put down under a pen held like this, before it touches: the outline a hover preview draws (the
 * owner, 2026-11-11 and 11-14: "where the mark will land and its shape", following tilt and lean).
 *
 * It is not a second copy of the brush's geometry: the brush's own stroke builder ([PasteStroke]) is run over a short
 * straight drag that ends at the pen point with the pen held as given, and its last step is the footprint. So the outline
 * cannot drift from what the stroke lays. A resting brush has no travel yet; [PasteStroke] then lines its body up with
 * the pen's lean, and the drag is made along that line.
 *
 * Covers oil today: hair brushes, the trowel and the blades. Other brushes give an empty list (the caller draws its own
 * mark, a circle of the brush's size). Pure; a few hundred small steps a call, so keep the last answer while the pen's
 * pressure, tilt and lean stay put.
 */
object HoverFootprint {

    /** How far the make-believe drag runs before the pen point (mm): past every brush's body, so the hairs are fully out. */
    private const val RUN_MM = 40.0
    private const val SAMPLE_MM = 0.5
    private const val ROUND_POINTS = 28

    /**
     * The closed outline, in DOCUMENT px around (0, 0) = the pen point, corner after corner. [pen] is the pen as the stroke
     * path would hand it over (tilt from upright, azimuth in document space as `AxisMapping.androidOrientationToAzimuth`
     * gives it, pressure 0..1: the caller picks 1 for the largest mark); its place and time are not read.
     */
    fun outline(preset: BrushPreset, pen: PenSample): List<DoubleArray> {
        val media = preset.media ?: return emptyList()
        if (media.medium != MEDIUM_PASTE) return emptyList()
        val spec = media.scaled(preset.mediaScale())
        val brush = spec.paste ?: return emptyList()
        val held = MediaInput.sample(preset.response.apply(pen.copy(x = 0f, y = 0f, timeMs = 0.0)))
        val step = settled(brush, held) ?: return emptyList()
        val ppm = DOC_PX_PER_MM
        if (brush.blade) {
            // A slab from the step's point along the blade, as long as this tilt lays down and as thick as its edge.
            val len = (step.bladeLen ?: brush.bladeLenMm) * ppm
            val half = max(0.5, (step.bladeHalf ?: brush.bladeHalfMm) * ppm)
            return quad(step.x, step.y, step.bladeDirX, step.bladeDirY, len, half)
        }
        val half = max(0.5, step.halfW * ppm)
        val len = max(half, step.len * ppm)
        // A round's body is an oval trailing from the pen point; a flat's and the trowel's a rectangle.
        return if (brush.shape == 0) oval(step.x, step.y, step.lDirX, step.lDirY, len, half) else quad(step.x, step.y, step.lDirX, step.lDirY, len, half)
    }

    /** The brush's last step after a straight drag to (0, 0), the pen held as [held] all the way; null if it laid none. */
    private fun settled(brush: PasteBrush, held: MediaSample): PasteStep? {
        val ppm = DOC_PX_PER_MM
        val stroke = PasteStroke(brush, ppm, 1.0)
        // A brush at rest lines up with the pen's lean ([PasteStroke.push]: travel = -(cos az, sin az)).
        val dx = -cos(held.az)
        val dy = -sin(held.az)
        val n = (RUN_MM / SAMPLE_MM).toInt()
        var last: PasteStep? = null
        for (i in 0..n) {
            val back = (n - i) * SAMPLE_MM * ppm
            stroke.add(MediaSample(-dx * back, -dy * back, held.p, held.tilt, held.az, i * 4.0))
            stroke.take().lastOrNull()?.let { last = it }
        }
        return last
    }

    /** A rectangle from ([x], [y]) along ([ux], [uy]) for [len], [half] to either side. */
    private fun quad(x: Double, y: Double, ux: Double, uy: Double, len: Double, half: Double): List<DoubleArray> {
        val nx = -uy
        val ny = ux
        return listOf(
            doubleArrayOf(x + nx * half, y + ny * half), doubleArrayOf(x + ux * len + nx * half, y + uy * len + ny * half),
            doubleArrayOf(x + ux * len - nx * half, y + uy * len - ny * half), doubleArrayOf(x - nx * half, y - ny * half),
        )
    }

    /** An oval from ([x], [y]) along ([ux], [uy]) for [len], [half] to either side at its middle. */
    private fun oval(x: Double, y: Double, ux: Double, uy: Double, len: Double, half: Double): List<DoubleArray> {
        val nx = -uy
        val ny = ux
        val cx = x + ux * len / 2
        val cy = y + uy * len / 2
        return List(ROUND_POINTS) { i ->
            val a = 2 * PI * i / ROUND_POINTS
            val along = cos(a) * len / 2
            val across = sin(a) * half
            doubleArrayOf(cx + ux * along + nx * across, cy + uy * along + ny * across)
        }
    }
}
