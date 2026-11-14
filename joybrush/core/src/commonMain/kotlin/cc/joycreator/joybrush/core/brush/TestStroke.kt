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
 *  - [STRIPES]: three bands of colour laid first, which the curve passes through (pick-up, smear, blur);
 *  - [pen], the curve ([PATH]): a hairline start "as light as possible" at the lower left, light getting heavier, two
 *    S-turns through the stripes, a loop that crosses itself, a broad arc over the top and down the right at full
 *    pressure with the pen laid over, and a hook back to the left that ends in a quick flick: a sudden lift, not a fade.
 */
object TestStroke {
    /** Bumped whenever the stroke, its marks or its stripes change: stored brush previews drawn with an older one are stale. */
    const val VERSION = 4

    /** One traced point of the curve: [x], [y] as shares of the box (0..1), the pen's [pressure] and [tilt] (radians from upright) there. */
    class P(val x: Float, val y: Float, val pressure: Float, val tilt: Float)

    /** The curve, traced from drawing A, in the order the pen travels. */
    val PATH: List<P> = listOf(
        P(0.0177f, 0.7734f, 0.02f, 0.10f),
        P(0.0779f, 0.7770f, 0.02f, 0.10f),
        P(0.1398f, 0.7734f, 0.03f, 0.12f),
        P(0.2018f, 0.7410f, 0.06f, 0.16f),
        P(0.2549f, 0.6763f, 0.12f, 0.22f),
        P(0.2991f, 0.5863f, 0.20f, 0.28f),
        P(0.3345f, 0.4964f, 0.28f, 0.34f),
        P(0.3566f, 0.4640f, 0.32f, 0.38f),
        P(0.3673f, 0.5072f, 0.34f, 0.42f),
        P(0.3681f, 0.6115f, 0.34f, 0.45f),
        P(0.3717f, 0.7086f, 0.34f, 0.45f),
        P(0.3832f, 0.7482f, 0.34f, 0.42f),
        P(0.4053f, 0.7086f, 0.36f, 0.40f),
        P(0.4407f, 0.5755f, 0.38f, 0.40f),
        P(0.4761f, 0.4424f, 0.40f, 0.42f),
        P(0.5071f, 0.3633f, 0.42f, 0.46f),
        P(0.5248f, 0.3705f, 0.42f, 0.52f),
        P(0.5292f, 0.4496f, 0.42f, 0.58f),
        P(0.5159f, 0.5647f, 0.42f, 0.62f),
        P(0.4938f, 0.6583f, 0.42f, 0.62f),
        P(0.4850f, 0.7302f, 0.44f, 0.58f),
        P(0.5009f, 0.7662f, 0.46f, 0.54f),
        P(0.5336f, 0.7086f, 0.48f, 0.52f),
        P(0.5779f, 0.5504f, 0.50f, 0.55f),
        P(0.6195f, 0.3849f, 0.54f, 0.62f),
        P(0.6531f, 0.2842f, 0.60f, 0.70f),
        P(0.6796f, 0.2626f, 0.63f, 0.74f),
        P(0.6885f, 0.2914f, 0.66f, 0.78f),
        P(0.6708f, 0.3633f, 0.69f, 0.82f),
        P(0.6460f, 0.5072f, 0.72f, 0.86f),
        P(0.6283f, 0.6583f, 0.75f, 0.90f),
        P(0.6336f, 0.7806f, 0.78f, 0.94f),
        P(0.6549f, 0.8381f, 0.81f, 0.97f),
        P(0.6814f, 0.8022f, 0.84f, 1.00f),
        P(0.7044f, 0.6835f, 0.87f, 1.03f),
        P(0.7310f, 0.5504f, 0.90f, 1.06f),
        P(0.7664f, 0.4137f, 0.93f, 1.09f),
        P(0.8053f, 0.3058f, 0.96f, 1.12f),
        P(0.8522f, 0.2338f, 1.00f, 1.15f),
        P(0.9097f, 0.2410f, 1.00f, 1.15f),
        P(0.9504f, 0.3345f, 1.00f, 1.12f),
        P(0.9681f, 0.4712f, 0.98f, 1.05f),
        P(0.9628f, 0.6223f, 0.94f, 0.95f),
        P(0.9398f, 0.7230f, 0.90f, 0.85f),
        P(0.9053f, 0.7950f, 0.86f, 0.75f),
        P(0.8566f, 0.8094f, 0.82f, 0.65f),
        P(0.8212f, 0.7914f, 0.55f, 0.55f),
        P(0.7965f, 0.7806f, 0.12f, 0.50f),
    )

    /** A band of colour the curve passes through: its middle [x] and half-width [half] as shares of the box's width, [y0]..[y1] of its height, opaque ARGB. */
    class Stripe(val x: Float, val half: Float, val y0: Float, val y1: Float, val argb: Int)

    /**
     * The colour lines the curve crosses, painted first, underneath it (the owner's ruling, BRUSH-UI section K.5): the print
     * colours cyan, magenta, yellow, black and white, close together with a little space between each, where his three
     * bands were, so each shows what the brush does with it (pick-up, smear, blur, cover).
     */
    val STRIPES: List<Stripe> = listOf(
        Stripe(0.430f, 0.025f, 0.07f, 0.97f, 0xFF00AEEF.toInt()),   // cyan
        Stripe(0.492f, 0.025f, 0.07f, 0.97f, 0xFFEC008C.toInt()),   // magenta
        Stripe(0.554f, 0.025f, 0.07f, 0.97f, 0xFFFFF200.toInt()),   // yellow
        Stripe(0.616f, 0.025f, 0.07f, 0.97f, 0xFF231F20.toInt()),   // black
        Stripe(0.678f, 0.025f, 0.07f, 0.97f, 0xFFFFFFFF.toInt()),   // white
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
    enum class Part { WHOLE, SWOOSH }

    /**
     * The S-turns, from the hairline start through both turns to where the loop begins, stretched to fill the box, its pressure
     * spread from a feather touch to a full press so a tiny picture (a slider's end, about 40 x 20 dp) still shows the range.
     */
    val SWOOSH: List<P> by lazy {
        val part = PATH.subList(2, 25)
        val x0 = part.minOf { it.x }; val x1 = part.maxOf { it.x }
        val y0 = part.minOf { it.y }; val y1 = part.maxOf { it.y }
        val p0 = part.minOf { it.pressure }; val p1 = part.maxOf { it.pressure }
        part.map { P((it.x - x0) / (x1 - x0), (it.y - y0) / (y1 - y0), 0.03f + 0.97f * (it.pressure - p0) / (p1 - p0), it.tilt) }
    }

    /** The curve for a [width] x [height] picture and a brush [sizePx] wide, a sample about every [pitchPx] along it. */
    fun pen(width: Int, height: Int, sizePx: Float, pitchPx: Float = 3f, part: Part = Part.WHOLE): List<PenSample> {
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
