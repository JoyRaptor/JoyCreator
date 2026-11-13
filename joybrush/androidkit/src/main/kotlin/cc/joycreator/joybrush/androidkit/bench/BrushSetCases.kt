package cc.joycreator.joybrush.androidkit.bench

import cc.joycreator.joybrush.androidkit.bench.StrokeReplay.Sample
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * The brush lane's replay strokes (2026-11-13), so the real engine on the phone draws what the PC lab's sheets draw
 * (joybrush/lab/media/js/tests.js `proko`, `pencilcolour`, `setdry` / `setwet` / `setoil`):
 *
 *  - `proko0` .. `proko17`: the owner's three Proko screenshots as one sheet, one pencil at one size: six pressure
 *    steps, two upright S lines, a tilted ribbon S, a tilted zigzag, three tall side strokes (light, wide, firm), a
 *    firm zigzag that thins to a hairline, a super light sweep, two crossing side strokes, a dense firm zigzag.
 *  - `pcol0` .. `pcol16`: the pencil in colour: five rows (give `jb_bench_colors` black, red, blue, yellow, green,
 *    three times each) of a firm line, a side stroke and a light side stroke, then two side strokes that cross.
 *  - `setA<i>`, `setB<i>`: cell i of a three-wide contact sheet: an S whose pressure lands, holds and lifts, and under
 *    it a line from a feather touch to full pressure. The bench asks for `setA,setB` and numbers them by brush.
 *
 * Run them at `jb_bench_zoom 1` with `jb_bench_clear false`. The sheet is laid out in document px as in the lab (one
 * screen px a document px), so widths compare with the lab's pictures.
 *
 * Lean: the bench's orientation is a quarter turn from the lean the engine reads ([StrokeReplay]'s `stroke`), so a
 * barrel that should lean toward the screen angle a (y down) is asked for as a - 90 degrees. Tilt: a pencil's full
 * side is its own reach ([REACH_DEG], the tuned pencils'), so the lab's 0..90 degrees are scaled onto that.
 */
object BrushSetCases {
    private const val REACH_DEG = 61.0
    private const val SHEET_W = 1000f
    private const val SHEET_H = 1700f

    /** The barrel toward the lower left of a right hand (the lab's 200 degrees, y up), and a quarter turn from it. */
    private val LEAN = orient(160.0)
    private val LEAN_ACROSS = orient(250.0)

    private fun orient(screenDeg: Double): Float {
        var a = (screenDeg - 90.0) * PI / 180
        while (a > PI) a -= 2 * PI
        while (a < -PI) a += 2 * PI
        return a.toFloat()
    }

    private fun tilt(labDeg: Double): Float = (labDeg / 90.0 * REACH_DEG * PI / 180).toFloat()

    private fun sstep(a: Double, b: Double, x: Double): Double { val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }

    /** A hand's pressure through a stroke: lands, holds, eases off to [tail] of the peak. */
    private fun env(peak: Double, tail: Double): (Double) -> Double = { u -> peak * sstep(0.0, 0.12, u) * (1 - (1 - tail) * sstep(0.7, 1.0, u)) }
    private fun ramp(a: Double, b: Double): (Double) -> Double = { u -> a + (b - a) * u }
    private fun flat(p: Double): (Double) -> Double = { p }

    private fun line(x0: Double, y0: Double, x1: Double, y1: Double): (Double) -> Pair<Double, Double> =
        { u -> (x0 + (x1 - x0) * u) to (y0 + (y1 - y0) * u) }

    private fun bez(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>, d: Pair<Double, Double>): (Double) -> Pair<Double, Double> = { u ->
        val v = 1 - u
        (v * v * v * a.first + 3 * v * v * u * b.first + 3 * v * u * u * c.first + u * u * u * d.first) to
            (v * v * v * a.second + 3 * v * v * u * b.second + 3 * v * u * u * c.second + u * u * u * d.second)
    }

    private fun zig(x0: Double, x1: Double, y: Double, amp: Double, n: Double): (Double) -> Pair<Double, Double> = { u ->
        val ph = u * n * 2
        val k = floor(min(ph, n * 2 - 1e-6)).toInt()
        val t = ph - k
        (x0 + (x1 - x0) * u) to (y + amp * (if (k % 2 == 0) t else 1 - t) * 2 - amp)
    }

    /** A stroke along [path] (sheet px, y UP as in the lab) placed on a [w] x [h] view. */
    private fun sheet(w: Int, h: Int, ms: Double, path: (Double) -> Pair<Double, Double>, p: (Double) -> Double, tiltRad: Float, lean: Float): List<Sample> {
        val k = min(1f, min(w * 0.82f / SHEET_W, h * 0.74f / SHEET_H))
        val ox = w * 0.15f
        val oy = h * 0.14f
        return build(ms, p, tiltRad, lean) { u -> val (x, y) = path(u); (ox + x.toFloat() * k) to (oy + (SHEET_H - y.toFloat()) * k) }
    }

    private fun build(ms: Double, p: (Double) -> Double, tiltRad: Float, lean: Float, at: (Double) -> Pair<Float, Float>): List<Sample> {
        val n = (ms / 1000.0 * StrokeReplay.HZ).toInt().coerceAtLeast(2)
        return List(n + 1) { i ->
            val u = i.toDouble() / n
            val (x, y) = at(u)
            Sample(x, y, p(u).coerceIn(0.0, 1.0).toFloat(), tiltRad, lean, u * ms)
        }
    }

    fun case(name: String, w: Int, h: Int): List<Sample>? {
        index(name, "proko")?.let { return proko(it, w, h) }
        index(name, "pcol")?.let { return colour(it, w, h) }
        index(name, "setA")?.let { return cell(it, w, h, first = true) }
        index(name, "setB")?.let { return cell(it, w, h, first = false) }
        return null
    }

    private fun index(name: String, prefix: String): Int? = if (name.startsWith(prefix)) name.removePrefix(prefix).toIntOrNull() else null

    private val PRESS_STEPS = doubleArrayOf(0.06, 0.14, 0.26, 0.42, 0.65, 1.0)

    private fun proko(i: Int, w: Int, h: Int): List<Sample>? {
        fun vert(x: Double, p: (Double) -> Double, deg: Double) =
            sheet(w, h, 620.0, { u -> (x + 20 * sin(u * 2.4)) to (680 - u * 560) }, p, tilt(deg), LEAN)
        return when (i) {
            in 0..5 -> sheet(w, h, 220.0, line(700.0, 1600.0 - i * 34, 900.0, 1560.0 - i * 34), env(PRESS_STEPS[i], 0.5), tilt(8.0), LEAN)
            6 -> sheet(w, h, 500.0, bez(180.0 to 1620.0, 120.0 to 1380.0, 420.0 to 1700.0, 760.0 to 1640.0), env(0.95, 0.05), tilt(8.0), LEAN)
            7 -> sheet(w, h, 560.0, bez(100.0 to 1500.0, 80.0 to 1220.0, 460.0 to 1600.0, 640.0 to 1360.0), env(0.85, 0.2), tilt(10.0), LEAN)
            8 -> sheet(w, h, 750.0, bez(60.0 to 1180.0, 330.0 to 980.0, 520.0 to 1420.0, 800.0 to 1080.0), env(0.55, 0.3), tilt(58.0), LEAN)
            9 -> sheet(w, h, 1000.0, zig(80.0, 620.0, 900.0, 110.0, 3.0), env(0.75, 0.4), tilt(66.0), LEAN)
            10 -> vert(110.0, env(0.2, 0.6), 79.0)
            11 -> vert(330.0, env(0.45, 0.6), 80.0)
            12 -> vert(540.0, env(0.8, 0.6), 76.0)
            13 -> sheet(w, h, 550.0, zig(640.0, 960.0, 620.0, 60.0, 5.0), ramp(0.95, 0.03), tilt(20.0), LEAN)
            14 -> sheet(w, h, 570.0, bez(660.0 to 520.0, 760.0 to 470.0, 860.0 to 480.0, 980.0 to 380.0), ramp(0.3, 0.03), tilt(70.0), LEAN)
            15 -> sheet(w, h, 360.0, line(680.0, 330.0, 960.0, 110.0), env(0.5, 0.5), tilt(74.0), LEAN)
            16 -> sheet(w, h, 360.0, line(680.0, 110.0, 960.0, 330.0), env(0.5, 0.5), tilt(74.0), LEAN_ACROSS)
            17 -> sheet(w, h, 1170.0, zig(700.0, 960.0, 800.0, 30.0, 9.0), env(1.0, 0.6), tilt(45.0), LEAN)
            else -> null
        }
    }

    /** Rows of three (a firm upright line, a side stroke, a light side stroke), one colour a row; then a cross. */
    private fun colour(i: Int, w: Int, h: Int): List<Sample>? = when (i) {
        in 0..14 -> {
            val y = 1600.0 - (i / 3) * 150
            when (i % 3) {
                0 -> sheet(w, h, 360.0, line(40.0, y, 300.0, y - 30), env(0.9, 0.3), tilt(8.0), LEAN)
                1 -> sheet(w, h, 360.0, line(350.0, y, 620.0, y - 30), env(0.7, 0.5), tilt(74.0), LEAN_ACROSS)
                else -> sheet(w, h, 360.0, line(680.0, y, 950.0, y - 30), env(0.25, 0.5), tilt(74.0), LEAN_ACROSS)
            }
        }
        15 -> sheet(w, h, 400.0, line(120.0, 760.0, 520.0, 460.0), flat(0.7), tilt(74.0), LEAN)
        16 -> sheet(w, h, 400.0, line(120.0, 460.0, 520.0, 760.0), flat(0.7), tilt(74.0), LEAN_ACROSS)
        else -> null
    }

    /** Cell [i] of the contact sheet, three across and up to twelve down, clear of the tool strip and the top bar. */
    private fun cell(i: Int, w: Int, h: Int, first: Boolean): List<Sample>? {
        if (i !in 0..35) return null
        val cw = w * 0.27f
        val ch = h * 0.066f
        val x0 = w * 0.15f + (i % 3) * cw
        val y0 = h * 0.13f + (i / 3) * ch
        val a = orient(120.0)
        return if (first) {
            val path = bez((0.08 to 0.42), (0.36 to -0.05), (0.56 to 0.80), (0.90 to 0.30))
            build(700.0, env(0.9, 0.15), 0.5f, a) { u -> val (x, y) = path(u); (x0 + cw * x.toFloat()) to (y0 + ch * y.toFloat()) }
        } else {
            build(600.0, ramp(0.06, 1.0), 0.45f, a) { u -> (x0 + cw * (0.08f + 0.82f * u.toFloat())) to (y0 + ch * 0.82f) }
        }
    }
}
