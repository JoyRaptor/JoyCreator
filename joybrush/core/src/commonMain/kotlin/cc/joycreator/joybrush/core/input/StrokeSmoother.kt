package cc.joycreator.joybrush.core.input

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Stroke smoothing behind ONE user-facing slider (owner, 2026-09-28: "a simple slider … that
 * understands the proper context of what it's smoothing so a user can do corners and detail work
 * without having to constantly bump it around").
 *
 * The slider value [amount] (0..1) drives three cooperating stages. The painter never sees them:
 *
 * 1. **Even spacing** — the pen path is resampled every [STEP] screen pixels, carrying pressure,
 *    tilt, lean direction and time along, so later stages do not depend on how often the device
 *    happens to report.
 * 2. **Corner protection** — sharp turns are found (the turning angle over a short window, kept only
 *    where it peaks) and the stroke is cut there. Smoothing never reaches across a corner, so a
 *    square stays square at any slider setting instead of turning into a rounded blob.
 * 3. **Speed-aware wave removal** — each piece between corners is smoothed with a Gaussian over ARC
 *    LENGTH (Krita's "weighted" smoothing, R3). The window is wider on slow stretches — where tremor
 *    and a wavy pen show — and narrower on fast confident ones ("velocity sensitive"). It shrinks
 *    symmetrically towards the piece's ends, so ends and corners stay pinned exactly where drawn.
 *
 * Deliberately NOT used on positions: a causal low-pass such as [OneEuroFilter]. It lags, and a
 * lagging filter cuts every corner before the corner detector can see it.
 *
 * Everything is measured in SCREEN pixels (document pixels × [screenPerDoc]), so the same slider
 * feels the same at every zoom: zoom in to do detail and the smoothing shrinks with the detail.
 *
 * On pen-up the line is finished right up to where the pen actually lifted ("catch-up"), so fast
 * flicks and tapered ends never fall short.
 *
 * STREAMING = BATCH: feeding samples one at a time through [add] and then calling [finish] returns
 * exactly the same points as feeding them all and calling [finish] once. A point is only released
 * when nothing that arrives later can change it. That is what makes stroke recordings replayable and
 * lets an ink stroke be re-smoothed later with a different slider value (blueprint §2).
 *
 * @param amount the slider, 0 = raw pen, 1 = maximum smoothing.
 * @param screenPerDoc zoom: screen pixels per document pixel, fixed for the whole stroke.
 */
class StrokeSmoother(amount: Float, private val screenPerDoc: Float = 1f) {

    val amount: Float = amount.coerceIn(0f, 1f)

    /** Gaussian width in screen px at this slider value (0 when the slider is 0). */
    val sigma: Double = amount.coerceIn(0f, 1f).toDouble().pow(1.5) * MAX_SIGMA

    /** Corner-test window, in resampled points either side. Grows with smoothing so waves aren't read as corners. */
    private val k: Int = max(2, ceil((4.0 + 0.6 * sigma) / STEP).toInt())

    /** Largest Gaussian half-window, in points (the velocity factor can widen sigma by up to [MAX_SPEED_FACTOR]). */
    private val w: Int = ceil(3.0 * sigma * MAX_SPEED_FACTOR / STEP).toInt()

    /** How many points past a point must exist before that point can no longer change. */
    private val lookahead: Int = w + 2 * k

    // Resampled points, screen space.
    private val xs = ArrayList<Double>()
    private val ys = ArrayList<Double>()
    private val ts = ArrayList<Double>()
    private val ps = ArrayList<Float>()
    private val tilts = ArrayList<Float>()
    private val azs = ArrayList<Float>()
    private val barrels = ArrayList<Float>()

    private var tool: Tool = Tool.STYLUS
    private var released = 0
    private var finished = false

    // Resampling state: the previous filtered point and how far along the path the next resample is due.
    private var hasPrev = false
    private var prevX = 0.0
    private var prevY = 0.0
    private var prevSample: PenSample? = null
    private var untilNext = 0.0
    private var lastRaw: PenSample? = null

    /** Test hook: when true, [add] releases nothing and [finish] releases everything (pure batch). */
    internal var holdRelease = false

    /** Feeds one pen sample. Returns the points that are now final (possibly none). Predicted samples are ignored. */
    fun add(sample: PenSample): List<PenSample> {
        check(!finished) { "stroke already finished" }
        if (sample.predicted) return emptyList()
        if (lastRaw == null) tool = sample.tool
        lastRaw = sample
        feedPath(sample.x.toDouble() * screenPerDoc, sample.y.toDouble() * screenPerDoc, sample)
        if (holdRelease) return emptyList()
        return release(xs.size - 1 - lookahead)
    }

    /** Pen lifted: finishes the line to where the pen really was and returns every remaining point. */
    fun finish(): List<PenSample> {
        if (finished) return emptyList()
        finished = true
        val raw = lastRaw ?: return emptyList()
        // Catch-up: resampling leaves up to one STEP undrawn; the line must end at the true lift-off point.
        val n = xs.size
        if (n > 0 && hypot(xs[n - 1] - prevX, ys[n - 1] - prevY) > 1e-9) {
            push(prevX, prevY, raw, raw, 1.0)
        }
        return release(xs.size - 1)
    }

    // ── resampling ────────────────────────────────────────────────────────────

    private fun feedPath(fx: Double, fy: Double, s: PenSample) {
        if (!hasPrev) {
            hasPrev = true
            prevX = fx; prevY = fy; prevSample = s
            push(fx, fy, s, s, 1.0)
            untilNext = STEP
            return
        }
        val a = prevSample!!
        val segLen = hypot(fx - prevX, fy - prevY)
        var along = untilNext
        while (along <= segLen) {
            val f = along / segLen
            push(prevX + (fx - prevX) * f, prevY + (fy - prevY) * f, a, s, f)
            along += STEP
        }
        untilNext = along - segLen
        prevX = fx; prevY = fy; prevSample = s
    }

    private fun push(x: Double, y: Double, a: PenSample, b: PenSample, f: Double) {
        val t = f.toFloat()
        xs.add(x); ys.add(y)
        ts.add(a.timeMs + (b.timeMs - a.timeMs) * f)
        ps.add(a.pressure + (b.pressure - a.pressure) * t)
        tilts.add(lerpNullable(a.tilt, b.tilt, t))
        azs.add(Angles.lerp(a.azimuth, b.azimuth, t))
        barrels.add(Angles.lerp(a.barrel, b.barrel, t))
    }

    private fun lerpNullable(a: Float, b: Float, t: Float): Float =
        if (a.isNaN() || b.isNaN()) Float.NaN else a + (b - a) * t

    // ── release ───────────────────────────────────────────────────────────────

    private fun release(upTo: Int): List<PenSample> {
        if (upTo < released) return emptyList()
        val out = ArrayList<PenSample>(upTo - released + 1)
        for (i in released..upTo) out.add(smoothedAt(i))
        released = upTo + 1
        return out
    }

    private fun smoothedAt(i: Int): PenSample {
        val n = xs.size
        var x = xs[i]
        var y = ys[i]
        var p = ps[i]
        if (sigma > 0.0) {
            val a = boundaryBefore(i)
            val b = boundaryAfter(i, n)
            if (i != a && i != b) {
                val sig = sigma * speedFactor(i, n)
                val half = minOf(i - a, b - i, ceil(3.0 * sig / STEP).toInt())
                var sw = 0.0; var sx = 0.0; var sy = 0.0; var sp = 0.0
                for (j in i - half..i + half) {
                    val d = (j - i) * STEP
                    val wgt = exp(-(d * d) / (2 * sig * sig))
                    sw += wgt; sx += wgt * xs[j]; sy += wgt * ys[j]; sp += wgt * ps[j]
                }
                x = sx / sw; y = sy / sw; p = (sp / sw).toFloat()
            }
        }
        return PenSample(
            x = (x / screenPerDoc).toFloat(),
            y = (y / screenPerDoc).toFloat(),
            timeMs = ts[i],
            pressure = p,
            tilt = tilts[i],
            azimuth = azs[i],
            barrel = barrels[i],
            tool = tool,
        )
    }

    /** Nearest segment boundary at or before i: a corner, or the stroke start. */
    private fun boundaryBefore(i: Int): Int {
        val lo = max(0, i - w)
        for (j in i downTo lo) if (j == 0 || isCorner(j)) return j
        return lo - 1 // beyond the window: only its distance matters, and it is larger than any half-window
    }

    /** Nearest segment boundary at or after i: a corner, or the stroke end (only once the stroke is finished). */
    private fun boundaryAfter(i: Int, n: Int): Int {
        val hi = min(n - 1, i + w)
        for (j in i..hi) if ((finished && j == n - 1) || isCorner(j)) return j
        return hi + 1
    }

    /** Wider smoothing on slow stretches (where wobble lives), narrower on fast ones. */
    private fun speedFactor(i: Int, n: Int): Double {
        val lo = max(0, i - 2)
        val hi = min(n - 1, i + 2)
        val dt = ts[hi] - ts[lo]
        if (hi == lo || dt <= 0.0) return 1.0
        val speed = (hi - lo) * STEP / dt * 1000.0 // screen px per second
        return (REF_SPEED / speed).pow(0.25).coerceIn(MIN_SPEED_FACTOR, MAX_SPEED_FACTOR)
    }

    // ── corners ──────────────────────────────────────────────────────────────

    /** Turning angles already computed. An angle never changes once both sides exist, so it is cached. */
    private val angleCache = ArrayList<Double>()

    /** Turning angle at j over ±k points, or -1 where there is not enough stroke on both sides yet. */
    private fun turnAngle(j: Int): Double {
        val n = xs.size
        if (j - k < 0 || j + k > n - 1) return -1.0
        while (angleCache.size <= j) angleCache.add(Double.NaN)
        val cached = angleCache[j]
        if (!cached.isNaN()) return cached
        val a = computeTurnAngle(j)
        angleCache[j] = a
        return a
    }

    private fun computeTurnAngle(j: Int): Double {
        val ax = xs[j] - xs[j - k]; val ay = ys[j] - ys[j - k]
        val bx = xs[j + k] - xs[j]; val by = ys[j + k] - ys[j]
        val la = hypot(ax, ay); val lb = hypot(bx, by)
        if (la < 1e-9 || lb < 1e-9) return -1.0
        val c = ((ax * bx + ay * by) / (la * lb)).coerceIn(-1.0, 1.0)
        return acos(c)
    }

    /** A corner is a turn sharper than [CORNER_ANGLE] that is the sharpest within ±k (earliest wins ties). */
    private fun isCorner(j: Int): Boolean {
        val aj = turnAngle(j)
        if (aj < CORNER_ANGLE) return false
        for (m in j - k..j + k) {
            if (m == j) continue
            val am = turnAngle(m)
            if (am > aj || (am == aj && m < j)) return false
        }
        return true
    }

    companion object {
        /** Resample spacing, screen px. */
        const val STEP = 1.0
        /** Gaussian width at slider = 1, screen px. */
        const val MAX_SIGMA = 14.0
        /** Turns sharper than this (radians, ~55°) are treated as corners. */
        val CORNER_ANGLE = 55.0 * PI / 180.0
        /** Speed at which the velocity factor is 1, screen px per second. */
        const val REF_SPEED = 300.0
        const val MIN_SPEED_FACTOR = 0.75
        const val MAX_SPEED_FACTOR = 1.3

        /** Convenience for tests and offline re-smoothing: smooths a whole recorded stroke at once. */
        fun smoothAll(samples: List<PenSample>, amount: Float, screenPerDoc: Float = 1f): List<PenSample> {
            val s = StrokeSmoother(amount, screenPerDoc)
            val out = ArrayList<PenSample>()
            for (p in samples) out.addAll(s.add(p))
            out.addAll(s.finish())
            return out
        }
    }
}
