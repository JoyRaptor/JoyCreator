package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * JB-5.40a: the owner's tweens — a held frame slides its lines into the next frame (R51 Phase 4; spec decisions T2–T7).
 *
 * Pure functions on [StrokeRecord]s. A matched pair of lines (same id in frame A and frame B, T2) moves in two parts:
 * the best similarity from A to B (turn, uniform scale, shift; a least-squares fit) is interpolated AS a turn about its
 * own pivot (T3), and what that leaves over (the nudge, the recurve) is interpolated point by point in A's frame and
 * carried along by the turn. Lines the owner moved together share one fit (T4). Points are paired by arc length (T5),
 * colour slides in HSB (T6). No clock, no randomness: the same input gives the same lines, whatever order it came in.
 */
object Tween {

    /**
     * Frame A's lines at [t] on their way to frame B's (0 = A, 1 = B). The result is in A's order. A line only in A is
     * returned unchanged; a line only in B is not returned (it appears at B, T2). Brush, seed, smoothing and timing are
     * A's (T7).
     */
    fun between(a: List<StrokeRecord>, b: List<StrokeRecord>, t: Float): List<StrokeRecord> {
        val tt = t.toDouble()
        val bById = HashMap<String, StrokeRecord>()
        for (r in b) if (r.id !in bById) bById[r.id] = r
        val pairs = a.filter { it.samples.isNotEmpty() && bById[it.id]?.samples?.isNotEmpty() == true }
            .distinctBy { it.id }
            .sortedBy { it.id }
            .map { pair(it, bById.getValue(it.id)) }
        val fits = HashMap<String, Similarity>()
        for (group in groups(pairs)) {
            val fit = if (group.size == 1) group[0].fit else Similarity.fit(group.flatMap { it.from }, group.flatMap { it.to })
            for (p in group) fits[p.a.id] = fit
        }
        val byId = pairs.associateBy { it.a.id }
        return a.map { record ->
            val p = byId[record.id] ?: return@map record
            if (p.a !== record) return@map record // a repeated id in A: only its first line tweens
            moved(p, fits.getValue(record.id), tt)
        }
    }

    // ── one pair ──

    internal class Matched(val a: StrokeRecord, val b: StrokeRecord, val sa: List<PenSample>, val sb: List<PenSample>) {
        val from: List<P> = sa.map { P(it.x.toDouble(), it.y.toDouble()) }
        val to: List<P> = sb.map { P(it.x.toDouble(), it.y.toDouble()) }
        val fit: Similarity = Similarity.fit(from, to)
        val centroid: P = P(from.sumOf { it.x } / from.size, from.sumOf { it.y } / from.size)
    }

    private fun pair(a: StrokeRecord, b: StrokeRecord): Matched {
        if (a.samples.size == b.samples.size) return Matched(a, b, a.samples, b.samples)
        val n = max(a.samples.size, b.samples.size)
        return Matched(a, b, resample(a.samples, n), resample(b.samples, n))
    }

    private fun moved(p: Matched, g: Similarity, t: Double): StrokeRecord {
        val samples = ArrayList<PenSample>(p.sa.size)
        for (i in p.sa.indices) {
            val sa = p.sa[i]
            val sb = p.sb[i]
            val home = g.inverse(p.to[i])                    // B's point pulled back into A's frame
            val bent = P(p.from[i].x + t * (home.x - p.from[i].x), p.from[i].y + t * (home.y - p.from[i].y))
            val at = g.at(t, bent)
            samples += PenSample(
                x = at.x.toFloat(), y = at.y.toFloat(), timeMs = sa.timeMs,
                pressure = lerpOrA(sa.pressure, sb.pressure, t),
                tilt = lerpOrA(sa.tilt, sb.tilt, t),
                azimuth = turnAngle(sa.azimuth, sb.azimuth, g.angle, t),
                barrel = turnAngle(sa.barrel, sb.barrel, g.angle, t),
                tool = sa.tool,
            )
        }
        return p.a.copy(
            samples = samples,
            colorArgb = hsbSlide(p.a.colorArgb, p.b.colorArgb, t.toFloat()),
            widthScale = (p.a.widthScale + t * (p.b.widthScale - p.a.widthScale)).toFloat(),
        )
    }

    /** T5: [n] samples at equal arc-length fractions of [s], keeping its first and last exactly. */
    internal fun resample(s: List<PenSample>, n: Int): List<PenSample> {
        require(n >= 1 && s.isNotEmpty())
        if (s.size == 1 || n == 1) return List(n) { s[0] }
        val cum = DoubleArray(s.size)
        for (i in 1 until s.size) cum[i] = cum[i - 1] + hypot((s[i].x - s[i - 1].x).toDouble(), (s[i].y - s[i - 1].y).toDouble())
        val total = cum.last()
        val out = ArrayList<PenSample>(n)
        var seg = 0
        for (k in 0 until n) {
            if (k == 0) { out += s.first(); continue }
            if (k == n - 1) { out += s.last(); continue }
            if (total <= 0.0) { out += s.first(); continue }
            val want = total * k / (n - 1)
            while (seg < s.size - 2 && cum[seg + 1] < want) seg++
            val len = cum[seg + 1] - cum[seg]
            val f = if (len > 0.0) ((want - cum[seg]) / len).coerceIn(0.0, 1.0) else 0.0
            val p = s[seg]
            val q = s[seg + 1]
            out += PenSample(
                x = (p.x + f * (q.x - p.x)).toFloat(), y = (p.y + f * (q.y - p.y)).toFloat(),
                timeMs = p.timeMs + f * (q.timeMs - p.timeMs),
                pressure = lerpOrA(p.pressure, q.pressure, f), tilt = lerpOrA(p.tilt, q.tilt, f),
                azimuth = turnAngle(p.azimuth, q.azimuth, 0.0, f), barrel = turnAngle(p.barrel, q.barrel, 0.0, f),
                tool = p.tool,
            )
        }
        return out
    }

    /**
     * T4: lines moved together. Sorted by id and grown greedily: a line joins a group when its own fit agrees with the
     * group's first line's fit (turn within 0.5°, scale within 0.5%) and that fit carries the line's own centroid to
     * within 0.5 doc px of where the line's own fit carries it.
     */
    internal fun groups(pairs: List<Matched>): List<List<Matched>> {
        val left = pairs.sortedBy { it.a.id }.toMutableList()
        val out = ArrayList<List<Matched>>()
        while (left.isNotEmpty()) {
            val lead = left.removeAt(0)
            val group = arrayListOf(lead)
            val it = left.iterator()
            while (it.hasNext()) {
                val p = it.next()
                val f = lead.fit
                val g = p.fit
                val turn = abs(wrap(f.angle - g.angle))
                val scale = abs(f.scale / g.scale - 1.0)
                val c1 = f.apply(p.centroid)
                val c2 = g.apply(p.centroid)
                if (turn <= SAME_TURN && scale <= SAME_SCALE && hypot(c1.x - c2.x, c1.y - c2.y) <= SAME_PLACE) {
                    group += p
                    it.remove()
                }
            }
            out += group
        }
        return out
    }

    // ── the similarity, as complex numbers: z' = k z + c, k = scale · e^(i angle) ──

    internal data class P(val x: Double, val y: Double)

    internal class Similarity(val angle: Double, val scale: Double, val tx: Double, val ty: Double) {
        private val kr = scale * cos(angle)
        private val ki = scale * sin(angle)

        /** Where the motion has no pivot to turn about: no turn and no scale, a shift only. */
        private val shiftOnly: Boolean = abs(angle) < 1e-9 && abs(ln(scale)) < 1e-9

        /** The fixed point: c = T / (1 − k). */
        private val pivot: P = if (shiftOnly) P(0.0, 0.0) else {
            val dr = 1.0 - kr
            val di = -ki
            val d = dr * dr + di * di
            P((tx * dr + ty * di) / d, (ty * dr - tx * di) / d)
        }

        fun apply(p: P): P = P(kr * p.x - ki * p.y + tx, ki * p.x + kr * p.y + ty)

        fun inverse(q: P): P {
            val x = q.x - tx
            val y = q.y - ty
            val d = kr * kr + ki * ki
            return P((kr * x + ki * y) / d, (kr * y - ki * x) / d)
        }

        /** The motion [t] of the way along: a turn and a log-scale about the pivot, or a straight shift. */
        fun at(t: Double, p: P): P {
            if (shiftOnly) return P(p.x + t * tx, p.y + t * ty)
            val s = exp(t * ln(scale))
            val a = t * angle
            val cr = s * cos(a)
            val ci = s * sin(a)
            val x = p.x - pivot.x
            val y = p.y - pivot.y
            return P(pivot.x + cr * x - ci * y, pivot.y + ci * x + cr * y)
        }

        companion object {
            /** Least-squares similarity from [from] to [to] (Umeyama in 2D); a lone point or a zero-length line only shifts. */
            fun fit(from: List<P>, to: List<P>): Similarity {
                val n = from.size
                val mx = from.sumOf { it.x } / n; val my = from.sumOf { it.y } / n
                val nx = to.sumOf { it.x } / n; val ny = to.sumOf { it.y } / n
                var a = 0.0; var b = 0.0; var spread = 0.0
                for (i in 0 until n) {
                    val px = from[i].x - mx; val py = from[i].y - my
                    val qx = to[i].x - nx; val qy = to[i].y - ny
                    a += px * qx + py * qy
                    b += px * qy - py * qx
                    spread += px * px + py * py
                }
                if (spread <= 1e-12 || hypot(a, b) <= 1e-12) return Similarity(0.0, 1.0, nx - mx, ny - my)
                val angle = atan2(b, a)
                val scale = hypot(a, b) / spread
                val kr = scale * cos(angle); val ki = scale * sin(angle)
                return Similarity(angle, scale, nx - (kr * mx - ki * my), ny - (ki * mx + kr * my))
            }
        }
    }

    // ── colour and channels ──

    /**
     * T6: [a] to [b] in HSB, hue the short way round, saturation, brightness and alpha straight. A grey end (no
     * saturation) takes the other end's hue, so grey to red stays red and never swings through green.
     */
    fun hsbSlide(a: Int, b: Int, t: Float): Int {
        val ha = hsb(a)
        val hb = hsb(b)
        val hueA = if (ha[1] <= 1e-6 && hb[1] > 1e-6) hb[0] else ha[0]
        val hueB = if (hb[1] <= 1e-6 && ha[1] > 1e-6) ha[0] else hb[0]
        var dh = (hueB - hueA) % 360.0
        if (dh > 180.0) dh -= 360.0
        if (dh < -180.0) dh += 360.0
        val h = ((hueA + t * dh) % 360.0 + 360.0) % 360.0
        val s = ha[1] + t * (hb[1] - ha[1])
        val v = ha[2] + t * (hb[2] - ha[2])
        val alpha = ((a ushr 24) + t * ((b ushr 24) - (a ushr 24))).roundToInt().coerceIn(0, 255)
        return (alpha shl 24) or rgb(h, s, v)
    }

    private fun hsb(argb: Int): DoubleArray {
        val r = ((argb shr 16) and 255) / 255.0
        val g = ((argb shr 8) and 255) / 255.0
        val b = (argb and 255) / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d <= 0.0 -> 0.0
            mx == r -> 60.0 * (((g - b) / d) % 6.0)
            mx == g -> 60.0 * ((b - r) / d + 2.0)
            else -> 60.0 * ((r - g) / d + 4.0)
        }
        return doubleArrayOf((h + 360.0) % 360.0, if (mx <= 0.0) 0.0 else d / mx, mx)
    }

    private fun rgb(h: Double, s: Double, v: Double): Int {
        val c = v * s
        val x = c * (1.0 - abs((h / 60.0) % 2.0 - 1.0))
        val m = v - c
        val (r, g, b) = when ((h / 60.0).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0.0); 1 -> Triple(x, c, 0.0); 2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c); 4 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
        }
        fun byte(f: Double) = ((f + m) * 255.0).roundToInt().coerceIn(0, 255)
        return (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }

    /** A channel the pen measured at both ends slides; one it did not measure at A stays unmeasured; else A's stands. */
    private fun lerpOrA(a: Float, b: Float, t: Double): Float =
        if (a.isNaN()) a else if (b.isNaN()) a else (a + t * (b - a)).toFloat()

    /** A document-space angle: it turns with the motion's [turn], then slides the short way to B's (NaN at A stays NaN). */
    private fun turnAngle(a: Float, b: Float, turn: Double, t: Double): Float {
        if (a.isNaN()) return a
        if (b.isNaN()) return wrap(a + t * turn).toFloat()
        return wrap(a + t * turn + t * wrap((b - turn) - a)).toFloat()
    }

    private fun wrap(x: Double): Double {
        var r = (x + PI) % (2 * PI)
        if (r < 0) r += 2 * PI
        return r - PI
    }

    private const val SAME_TURN = 0.5 * PI / 180.0
    private const val SAME_SCALE = 0.005
    private const val SAME_PLACE = 0.5
}
