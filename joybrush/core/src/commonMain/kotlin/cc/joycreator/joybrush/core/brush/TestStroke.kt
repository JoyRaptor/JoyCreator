package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The one test stroke every brush is shown with, traced from the owner's own drawing of 2026-11-14
 * (tasks/joybrush/research/test_stroke_20261114/owner_test_stroke_A.png; job BRUSH-UI). He rejected the first one
 * ("it just won't do") and drew this: "maximums of tilt to minimums of size, and maximum of pressure to minimum of
 * pressure, all in one line", concave and convex turns, and what the brush does where it crosses itself.
 *
 * It is data, not drawing, and belongs to no engine: every brush (stamp and Unibrush, tuft, pencil, watercolour, oil)
 * takes the same pen samples, so two pictures differ only by the brush. Pure and repeatable.
 *
 * What the test area holds, left to right in a box drawn about 4 : 1 (y grows down):
 *  - [marks]: a short fat swatch and three straight lines at one angle, up and to the right, each lighter and more
 *    upright than the last, the last running off the box;
 *  - [STRIPES]: five slanted colour lines laid first, which the curve crosses (pick-up, smear, blur, cover);
 *  - [pen], the curve ([PATH]): a hairline start "as light as possible" at the lower left, light getting heavier, two
 *    S-turns through the stripes, a loop that crosses itself, a broad arc over the top and down the right at full
 *    pressure with the pen laid over, and a hook back to the left that ends in a quick flick: a sudden lift, not a fade.
 */
object TestStroke {
    /** Bumped whenever the stroke, its marks or its stripes change: stored brush previews drawn with an older one are stale. */
    const val VERSION = 9

    /** One traced point of the curve: [x], [y] as shares of the box (0..1), the pen's [pressure] and [tilt] (radians from upright) there. */
    class P(val x: Float, val y: Float, val pressure: Float, val tilt: Float)

    /** The curve, traced from drawing A, in the order the pen travels. */
    val PATH: List<P> = listOf(
        P(0.0177f, 0.8011f, 0.02f, 0.10f),
        P(0.0376f, 0.8036f, 0.02f, 0.10f),
        P(0.0580f, 0.8043f, 0.02f, 0.10f),
        P(0.0909f, 0.8004f, 0.02f, 0.11f),
        P(0.1186f, 0.7892f, 0.03f, 0.12f),
        P(0.1438f, 0.7694f, 0.04f, 0.13f),
        P(0.1706f, 0.7392f, 0.05f, 0.15f),
        P(0.1942f, 0.7043f, 0.07f, 0.17f),
        P(0.2128f, 0.6687f, 0.09f, 0.19f),
        P(0.2376f, 0.6126f, 0.12f, 0.22f),
        P(0.2542f, 0.5766f, 0.16f, 0.25f),
        P(0.2642f, 0.5601f, 0.17f, 0.26f),
        P(0.2721f, 0.5536f, 0.19f, 0.27f),
        P(0.2767f, 0.5550f, 0.20f, 0.28f),
        P(0.2801f, 0.5612f, 0.20f, 0.28f),
        P(0.2830f, 0.5741f, 0.21f, 0.29f),
        P(0.2847f, 0.5921f, 0.22f, 0.30f),
        P(0.2847f, 0.6306f, 0.24f, 0.31f),
        P(0.2799f, 0.7147f, 0.28f, 0.34f),
        P(0.2804f, 0.7378f, 0.29f, 0.35f),
        P(0.2822f, 0.7486f, 0.30f, 0.36f),
        P(0.2847f, 0.7547f, 0.31f, 0.37f),
        P(0.2917f, 0.7554f, 0.32f, 0.38f),
        P(0.2990f, 0.7446f, 0.33f, 0.40f),
        P(0.3080f, 0.7237f, 0.34f, 0.42f),
        P(0.3260f, 0.6680f, 0.34f, 0.45f),
        P(0.3488f, 0.5989f, 0.34f, 0.44f),
        P(0.3658f, 0.5511f, 0.35f, 0.41f),
        P(0.3819f, 0.5101f, 0.36f, 0.40f),
        P(0.4040f, 0.4640f, 0.38f, 0.40f),
        P(0.4130f, 0.4514f, 0.38f, 0.40f),
        P(0.4198f, 0.4468f, 0.38f, 0.40f),
        P(0.4251f, 0.4489f, 0.38f, 0.40f),
        P(0.4288f, 0.4590f, 0.39f, 0.41f),
        P(0.4304f, 0.4737f, 0.39f, 0.41f),
        P(0.4300f, 0.4932f, 0.39f, 0.41f),
        P(0.4277f, 0.5158f, 0.39f, 0.41f),
        P(0.4228f, 0.5460f, 0.40f, 0.42f),
        P(0.3993f, 0.6536f, 0.42f, 0.45f),
        P(0.3925f, 0.6953f, 0.42f, 0.49f),
        P(0.3910f, 0.7169f, 0.42f, 0.51f),
        P(0.3920f, 0.7335f, 0.42f, 0.52f),
        P(0.3958f, 0.7428f, 0.42f, 0.53f),
        P(0.4014f, 0.7424f, 0.42f, 0.55f),
        P(0.4089f, 0.7320f, 0.42f, 0.58f),
        P(0.4171f, 0.7137f, 0.42f, 0.59f),
        P(0.4423f, 0.6421f, 0.42f, 0.62f),
        P(0.4637f, 0.5795f, 0.43f, 0.61f),
        P(0.4901f, 0.5058f, 0.46f, 0.54f),
        P(0.5152f, 0.4414f, 0.48f, 0.52f),
        P(0.5383f, 0.3903f, 0.49f, 0.53f),
        P(0.5627f, 0.3446f, 0.50f, 0.55f),
        P(0.5834f, 0.3129f, 0.51f, 0.57f),
        P(0.6110f, 0.2835f, 0.53f, 0.61f),
        P(0.6312f, 0.2719f, 0.55f, 0.60f),
        P(0.6480f, 0.2716f, 0.57f, 0.57f),
        P(0.6627f, 0.2809f, 0.58f, 0.55f),
        P(0.6764f, 0.3058f, 0.59f, 0.56f),
        P(0.6841f, 0.3345f, 0.60f, 0.57f),
        P(0.6873f, 0.3543f, 0.60f, 0.57f),
        P(0.6897f, 0.3802f, 0.61f, 0.58f),
        P(0.6918f, 0.4309f, 0.61f, 0.58f),
        P(0.6902f, 0.4968f, 0.62f, 0.59f),
        P(0.6857f, 0.5608f, 0.63f, 0.60f),
        P(0.6779f, 0.6263f, 0.65f, 0.62f),
        P(0.6679f, 0.6817f, 0.66f, 0.63f),
        P(0.6588f, 0.7187f, 0.67f, 0.64f),
        P(0.6435f, 0.7612f, 0.68f, 0.65f),
        P(0.6290f, 0.7867f, 0.69f, 0.65f),
        P(0.6075f, 0.8076f, 0.70f, 0.66f),
        P(0.5899f, 0.8115f, 0.71f, 0.67f),
        P(0.5766f, 0.8025f, 0.72f, 0.68f),
        P(0.5696f, 0.7863f, 0.73f, 0.68f),
        P(0.5658f, 0.7626f, 0.73f, 0.69f),
        P(0.5649f, 0.7306f, 0.74f, 0.69f),
        P(0.5670f, 0.6971f, 0.75f, 0.70f),
        P(0.5731f, 0.6493f, 0.77f, 0.71f),
        P(0.5817f, 0.6011f, 0.79f, 0.72f),
        P(0.5918f, 0.5586f, 0.81f, 0.74f),
        P(0.6050f, 0.5140f, 0.82f, 0.75f),
        P(0.6149f, 0.4856f, 0.83f, 0.75f),
        P(0.6313f, 0.4457f, 0.85f, 0.76f),
        P(0.6630f, 0.3860f, 0.88f, 0.78f),
        P(0.6804f, 0.3597f, 0.89f, 0.79f),
        P(0.7039f, 0.3291f, 0.90f, 0.80f),
        P(0.7404f, 0.2932f, 0.93f, 0.83f),
        P(0.7680f, 0.2773f, 0.96f, 0.87f),
        P(0.7896f, 0.2727f, 0.97f, 0.93f),
        P(0.8248f, 0.2741f, 1.00f, 1.05f),
        P(0.8556f, 0.2824f, 1.00f, 1.13f),
        P(0.8804f, 0.2953f, 1.00f, 1.15f),
        P(0.9063f, 0.3180f, 1.00f, 1.14f),
        P(0.9266f, 0.3471f, 1.00f, 1.13f),
        P(0.9411f, 0.3773f, 1.00f, 1.11f),
        P(0.9543f, 0.4191f, 0.99f, 1.08f),
        P(0.9625f, 0.4604f, 0.98f, 1.05f),
        P(0.9653f, 0.4845f, 0.97f, 1.04f),
        P(0.9673f, 0.5144f, 0.97f, 1.02f),
        P(0.9679f, 0.5683f, 0.95f, 0.98f),
        P(0.9666f, 0.5978f, 0.94f, 0.96f),
        P(0.9635f, 0.6367f, 0.93f, 0.93f),
        P(0.9556f, 0.6953f, 0.91f, 0.87f),
        P(0.9437f, 0.7540f, 0.89f, 0.82f),
        P(0.9300f, 0.7978f, 0.87f, 0.77f),
        P(0.9172f, 0.8219f, 0.85f, 0.74f),
        P(0.9050f, 0.8299f, 0.84f, 0.71f),
        P(0.8921f, 0.8219f, 0.83f, 0.68f),
        P(0.8820f, 0.8025f, 0.82f, 0.66f),
        P(0.8727f, 0.7647f, 0.74f, 0.62f),
        P(0.8665f, 0.7219f, 0.64f, 0.58f),
        P(0.8607f, 0.6608f, 0.46f, 0.54f),
        P(0.8563f, 0.5871f, 0.12f, 0.50f),
    )

    /** A band of colour the curve passes through: its middle [x] and half-width [half] as shares of the box's width, [y0]..[y1] of its height, opaque ARGB. */
    /**
     * One colour line: a single straight stroke on a slant, its middle at [xTop] where the box's top edge is and at [xBottom]
     * where its bottom edge is (shares of the box inside the inset), [half] wide each side. It runs on past both edges
     * ([OVERSHOOT]), so wherever the picture is cropped it goes off the edge instead of ending in a cap.
     */
    class Stripe(val xTop: Float, val xBottom: Float, val half: Float, val argb: Int) {
        /** The line's middle (share of the box's width) at height [y] (share of its height, 0 = top). */
        fun xAt(y: Float): Float = xTop + (xBottom - xTop) * y
    }

    /**
     * The brush that lays the colour lines: never the brush being shown (owner, 11-14). The lines are paint already on the
     * paper, thin and the same for every brush, so the curve shows what this brush does to colour it meets (pick-up,
     * smear, a second colour in the belly, cover). Wet paint under pencils and watercolour, oil paint under oils, a plain
     * marker under everything else. Ids of shipped brushes.
     */
    fun lineBrushId(shown: BrushPreset): String = when {
        shown.engine != ENGINE_MEDIA -> "joybrush.marker"
        shown.media?.medium == MEDIUM_PASTE -> "media:paste:Oil flat"
        else -> "media:wet:Round"
    }

    /** How far the colour lines run past the box's top and bottom, as a share of its height. */
    const val OVERSHOOT = 0.25f

    /**
     * The colour lines the curve crosses, painted first, underneath it (the owner's rulings, BRUSH-UI K.5 and his 11-14
     * note): the print colours cyan, magenta, yellow, black and white, one thin line each, a tenth of the width apart, leaning
     * with his italic (the top to the right) and running off the box. The loop crosses the black line three times.
     */
    val STRIPES: List<Stripe> = listOf(
        Stripe(0.40f, 0.26f, 0.009f, 0xFF00AEEF.toInt()),   // cyan
        Stripe(0.50f, 0.36f, 0.009f, 0xFFEC008C.toInt()),   // magenta
        Stripe(0.60f, 0.46f, 0.009f, 0xFFFFF200.toInt()),   // yellow
        Stripe(0.70f, 0.56f, 0.009f, 0xFF231F20.toInt()),   // black
        Stripe(0.80f, 0.66f, 0.009f, 0xFFFFFFFF.toInt()),   // white
    )

    /** The pen leans up and to the left as a right hand holds it (radians, 0 = +x, y grows down); it swings round with the hand through the loop and the arc. */
    const val LEAN = (-0.75 * PI).toFloat()
    const val LEAN_SWING = 1.2f

    /** Speed along the path, picture pixels per millisecond. */
    const val PX_PER_MS = 1.2f

    private const val MIN_SAMPLES = 48
    private const val MAX_SAMPLES = 900

    /** How far from the box's edge the pen stays for a brush [sizePx] wide. */
    fun inset(sizePx: Float): Float = sizePx / 2f + 2f

    private class Box(width: Int, height: Int, sizePx: Float) {
        val inset = inset(sizePx)
        val w = (width - 2f * inset).coerceAtLeast(1f)
        val h = (height - 2f * inset).coerceAtLeast(0f)
        val left = inset
        // A picture no taller than the brush has no room to move up or down: everything runs along its middle.
        val top = if (h > 0f) inset else height / 2f
        fun x(share: Float) = left + w * share
        fun y(share: Float) = top + h * share
    }

    /** Catmull-Rom through four values at [t] in 0..1 (between the second and third). */
    private fun cr(a: Float, b: Float, c: Float, d: Float, t: Float): Float =
        0.5f * (2f * b + (c - a) * t + (2f * a - 5f * b + 4f * c - d) * t * t + (3f * b - a - 3f * c + d) * t * t * t)

    /** Which part of the curve to draw: all of it, or ([SWOOSH]) the light-to-heavy S-turns alone, for a picture too small for the loop. */
    /**
     * Which part of the test to draw: all of it, the S-turns alone ([SWOOSH], a slider's tiny end picture), or a single
     * [DAB]: one press at the box's centre, for the ends of the softness, shape and size sliders (BRUSH-UI K.3).
     */
    enum class Part { WHOLE, SWOOSH, DAB }

    /** A [Part.DAB]'s tilt: as at his stroke's middle. */
    const val DAB_TILT = 0.45f

    /** A single press at the middle of a [width] x [height] box: full pressure, held a moment in one place, no movement. */
    fun dab(width: Int, height: Int): List<PenSample> = List(4) { i ->
        PenSample(width / 2f, height / 2f, timeMs = i * 12.0, pressure = 1f, tilt = DAB_TILT, azimuth = LEAN)
    }

    /**
     * The S-turns, from the hairline start through both turns to where the loop begins, stretched to fill the box, its pressure
     * spread from a feather touch to a full press so a tiny picture (a slider's end, about 40 x 20 dp) still shows the range.
     */
    val SWOOSH: List<P> by lazy {
        val part = PATH.subList(2, 54)
        val x0 = part.minOf { it.x }; val x1 = part.maxOf { it.x }
        val y0 = part.minOf { it.y }; val y1 = part.maxOf { it.y }
        val p0 = part.minOf { it.pressure }; val p1 = part.maxOf { it.pressure }
        part.map { P((it.x - x0) / (x1 - x0), (it.y - y0) / (y1 - y0), 0.03f + 0.97f * (it.pressure - p0) / (p1 - p0), it.tilt) }
    }

    /** The curve for a [width] x [height] picture and a brush [sizePx] wide, a sample about every [pitchPx] along it. */
    fun pen(width: Int, height: Int, sizePx: Float, pitchPx: Float = 3f, part: Part = Part.WHOLE): List<PenSample> {
        if (part == Part.DAB) return dab(width, height)
        val box = Box(width, height, sizePx)
        val PATH = if (part == Part.SWOOSH) SWOOSH else PATH
        // A fine polyline through the traced points, with pressure and tilt carried along it.
        val fine = ArrayList<FloatArray>(PATH.size * FINE)
        for (i in 0 until PATH.size - 1) {
            val a = PATH[maxOf(0, i - 1)]; val b = PATH[i]; val c = PATH[i + 1]; val d = PATH[minOf(PATH.lastIndex, i + 2)]
            val last = if (i == PATH.size - 2) FINE else FINE - 1
            for (k in 0..last) {
                val t = k / FINE.toFloat()
                fine.add(floatArrayOf(
                    box.x(cr(a.x, b.x, c.x, d.x, t).coerceIn(0f, 1f)), box.y(cr(a.y, b.y, c.y, d.y, t).coerceIn(0f, 1f)),
                    (b.pressure + (c.pressure - b.pressure) * t), (b.tilt + (c.tilt - b.tilt) * t), (i + t) / (PATH.size - 1)))
            }
        }
        val along = FloatArray(fine.size)
        for (i in 1 until fine.size) along[i] = along[i - 1] + hypot(fine[i][0] - fine[i - 1][0], fine[i][1] - fine[i - 1][1])
        val length = along.last()
        val n = (length / pitchPx.coerceAtLeast(1f)).roundToInt().coerceIn(MIN_SAMPLES, MAX_SAMPLES)
        val out = ArrayList<PenSample>(n)
        var j = 0
        for (i in 0 until n) {
            val s = length * i / (n - 1f)
            while (j < fine.size - 2 && along[j + 1] < s) j++
            val span = along[j + 1] - along[j]
            val t = if (span > 0f) ((s - along[j]) / span).coerceIn(0f, 1f) else 0f
            val a = fine[j]; val b = fine[j + 1]
            fun mix(k: Int) = a[k] + (b[k] - a[k]) * t
            // The hand swings the pen round as it goes into the loop and over the arc (the last 45 % of the points).
            val swing = ((mix(4) - 0.55f) / 0.45f).coerceIn(0f, 1f)
            out.add(PenSample(mix(0), mix(1), timeMs = (s / PX_PER_MS).toDouble(), pressure = mix(2).coerceIn(0f, 1f), tilt = mix(3),
                azimuth = LEAN - LEAN_SWING * swing))
        }
        return out
    }

    private const val FINE = 12

    /**
     * The marks at the top left: a short fat swatch, then three straight lines at the same angle, each lighter and more
     * upright than the last (the same angle, different tilt and pressure), the last running to the box's top edge.
     */
    fun marks(width: Int, height: Int, sizePx: Float, pitchPx: Float = 3f): List<List<PenSample>> {
        val box = Box(width, height, sizePx)
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, p0: Float, p1: Float, tilt: Float): List<PenSample> {
            val ax = box.x(x0); val ay = box.y(y0); val bx = box.x(x1); val by = box.y(y1)
            val len = hypot(bx - ax, by - ay)
            val n = (len / pitchPx.coerceAtLeast(1f)).roundToInt().coerceIn(8, MAX_SAMPLES)
            return List(n) { i ->
                val k = i / (n - 1f)
                PenSample(ax + (bx - ax) * k, ay + (by - ay) * k, timeMs = (len * k / PX_PER_MS).toDouble(),
                    pressure = p0 + (p1 - p0) * k, tilt = tilt, azimuth = LEAN)
            }
        }
        return listOf(
            line(0.010f, 0.34f, 0.050f, 0.09f, 0.75f, 0.85f, 1.05f),   // the swatch: pressed, laid over
            line(0.058f, 0.37f, 0.126f, 0.05f, 0.55f, 0.04f, 0.60f),
            line(0.120f, 0.37f, 0.184f, 0.05f, 0.25f, 0.02f, 0.30f),
            line(0.176f, 0.37f, 0.250f, 0.00f, 0.10f, 0.01f, 0.10f),
        )
    }
}
