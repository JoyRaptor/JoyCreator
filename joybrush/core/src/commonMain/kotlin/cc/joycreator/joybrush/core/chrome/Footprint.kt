package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.brush.BrushDabber
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_MEDIA
import cc.joycreator.joybrush.core.brush.ENGINE_TUFT
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.StrokeColors
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.media.HoverFootprint
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.paint.TipMath
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.paint.tipShape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The mark a brush leaves on the paper under a pen held like this, seen from above (BRUSH-UI section K.4: "the
 * footprint shows the tip lying down as the pen tilts"). Every brush, from its own code, never a drawing of it:
 *
 *  - oil brushes and knives: [HoverFootprint], the paste stroke's own last step;
 *  - every dab brush (stamp, smudge, push, Unibrush): the dab [BrushDabber] would lay at the end of a short straight
 *    drag with the pen held as given, traced from [TipMath.coverage], so a four-point shape, a thinness that follows
 *    tilt, an anchor or a directional fade all show as they paint;
 *  - anything else (pencil, watercolour, Sable): a circle of the brush's size, until those engines can say more.
 *
 * Pure. A few thousand coverage samples a call: keep the answer while the brush is unchanged.
 */
object Footprint {
    /** How many directions the dab's edge is looked for in. */
    private const val RAYS = 48
    /** Where the edge is: the coverage a mark still visibly has. */
    private const val EDGE = 0.25f
    /** The brush size the trace is made at (the outline is scaled to its box anyway). */
    private const val TRACE_SIZE = 64f
    private const val DRAG_SAMPLES = 24

    /**
     * The closed outline in document px around (0, 0) = the pen point, corner after corner. [pen]'s place and time are
     * not read; pressure, tilt (radians from upright) and azimuth (document space) are.
     */
    fun outline(preset: BrushPreset, pen: PenSample): List<DoubleArray> {
        val paste = HoverFootprint.outline(preset, pen)
        if (paste.isNotEmpty()) return paste
        if (preset.engine in setOf(ENGINE_MEDIA, ENGINE_TUFT, ENGINE_WATERCOLOUR)) return circle(preset.size.base / 2.0)
        return try {
            dab(preset, pen) ?: circle(preset.size.base / 2.0)
        } catch (e: Exception) {
            // A brush whose dab needs something only a real stroke has (frozen PNG masks, a stroke seed): its size.
            circle(preset.size.base / 2.0)
        }
    }

    /** Whether this brush's footprint changes with tilt at all (else one mark says it all). */
    fun followsTilt(preset: BrushPreset): Boolean {
        val a = outline(preset, held(preset, 0f))
        val b = outline(preset, held(preset, (70.0 * PI / 180).toFloat()))
        if (a.size != b.size) return true
        return a.indices.any { i -> kotlin.math.abs(a[i][0] - b[i][0]) > 0.5 || kotlin.math.abs(a[i][1] - b[i][1]) > 0.5 }
    }

    /** A full press with the pen tilted [tilt] radians, leaning along +x. */
    fun held(@Suppress("UNUSED_PARAMETER") preset: BrushPreset, tilt: Float): PenSample =
        PenSample(0f, 0f, 0.0, pressure = 1f, tilt = tilt, azimuth = 0f)

    private fun dab(preset: BrushPreset, pen: PenSample): List<DoubleArray>? {
        // Traced at one size; PNG tips and dual tips need masks a real stroke prepares, so their outline is the shape
        // that holds them (the brush's quad or round tip), which is still what it covers.
        val sized = ToolMemory.sized(preset, TRACE_SIZE, preset.opacity.base)
        val shown = sized.copy(tip = sized.tip.copy(sampled = null, dual = null))
        val dabber = BrushDabber(shown, 1L, StrokeColors(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
        val placer = DabPlacer(spacing = dabber.spacing, look = dabber::look)
        // A straight drag along the pen's lean that ends at the pen point, so a tip that turns with the stroke has turned.
        val run = max(4.0 * TRACE_SIZE, 40.0)
        val samples = (0..DRAG_SAMPLES).map { i ->
            val back = (run * (DRAG_SAMPLES - i) / DRAG_SAMPLES).toFloat()
            shown.response.apply(pen.copy(x = -back, y = 0f, timeMs = i * 8.0))
        }
        val d = placer.add(samples).lastOrNull() ?: return null
        val tip = d.tipShape(TipShape(aspect = shown.tip.aspect, corner = shown.tip.corner, taper = shown.tip.taper,
            hardness = shown.tip.hardness.base, minPx = shown.tip.minPx, profile = shown.tip.profile))
        val reach = TipMath.extent(d.radius, tip.anchor, null, tip.aspect)
        val step = max(0.25f, reach / 160f)
        val out = ArrayList<DoubleArray>(RAYS)
        for (k in 0 until RAYS) {
            val a = 2 * PI * k / RAYS
            val ux = cos(a).toFloat(); val uy = sin(a).toFloat()
            var edge = 0f
            var r = 0f
            while (r <= reach) {
                if (TipMath.coverage(ux * r, uy * r, d.radius, d.angle, tip) >= EDGE) edge = r
                r += step
            }
            out += doubleArrayOf((d.x + ux * edge).toDouble(), (d.y + uy * edge).toDouble())
        }
        // A tip with no coverage at all (an emptied quad) has no footprint to show.
        return out.takeIf { pts -> pts.any { it[0] != d.x.toDouble() || it[1] != d.y.toDouble() } }
    }

    private fun circle(r: Double): List<DoubleArray> {
        val radius = max(0.5, r)
        return List(RAYS) { k -> val a = 2 * PI * k / RAYS; doubleArrayOf(cos(a) * radius, sin(a) * radius) }
    }
}
