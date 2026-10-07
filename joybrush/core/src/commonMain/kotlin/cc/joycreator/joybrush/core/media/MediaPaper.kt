package cc.joycreator.joybrush.core.media

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A paper as the media engine reads it (lab: paper.js loadSurface + stick.js contact tables).
 *
 * @param hist the height histogram of the paper's height map: 256 bins of the BLUE (height) byte, normalised to 1.
 * @param toothMm peak-to-valley tooth depth; @param compliance how soft the pad under the sheet is.
 * The rest are the catalogue's fluid numbers (sizing, absorbency, capacity, wickSpeed), 0..1.
 */
class MediaPaper(
    val hist: DoubleArray,
    val toothMm: Double,
    val compliance: Double,
    val sizing: Double = 0.6,
    val absorbency: Double = 0.5,
    val capacity: Double = 0.5,
    val wickSpeed: Double = 0.5,
    val heightMeanOverride: Double? = null,
) {
    init { require(hist.size == 256) { "height histogram must have 256 bins" } }

    /** Mean height 0..1 (the catalogue's heightMean wins when given, as in the lab). */
    val heightMean: Double = heightMeanOverride ?: (0 until 256).sumOf { hist[it] * it / 255.0 }

    /** 2nd and 98th height percentiles: paint and water normalise to them (u_paperHBot / u_paperHTop). */
    val heightBot: Double
    val heightTop: Double

    init {
        var acc = 0.0
        var bot = 0.0
        var top = 1.0
        for (b in 0 until 256) {
            val prev = acc
            acc += hist[b]
            if (prev < 0.02 && acc >= 0.02) bot = b / 255.0
            if (prev < 0.98 && acc >= 0.98) top = b / 255.0
        }
        heightBot = bot
        heightTop = top
    }

    /** Φ(a) = E[max(0, a − u)], u = 1 − h: how much paper a flat level pushed to depth a (tooth units) meets. */
    val phi: DoubleArray = phiTable(hist)

    /** Ψ(a) = E[(a − u)^1.5 ; u < a] (Hertzian asperity load), built from Φ exactly as the lab does. */
    val psi: DoubleArray by lazy { psiTable(phi) }

    private var lutKey: String? = null
    private var lut: ContactLut? = null

    /** The two-layer paper's squeeze → tooth-level table for these press settings (cached). */
    fun contactLut(press: Press = Press()): ContactLut {
        val key = "${press.toothStiffness}:${press.lutMaxMm}:$compliance:${press.padSoft}"
        lut?.let { if (lutKey == key) return it }
        return buildContactLut(this, press).also { lut = it; lutKey = key }
    }

    companion object {
        const val PHI_N = 256

        /** A histogram from height bytes (one per texel). */
        fun histogramOf(heights: ByteArray): DoubleArray {
            val h = DoubleArray(256)
            for (b in heights) h[b.toInt() and 0xFF] += 1.0
            for (i in 0 until 256) h[i] /= heights.size.toDouble()
            return h
        }

        fun phiTable(hist: DoubleArray): DoubleArray {
            val t = DoubleArray(PHI_N + 1)
            for (i in 0..PHI_N) {
                val a = i.toDouble() / PHI_N
                var e = 0.0
                for (b in 0 until 256) {
                    val u = 1 - b / 255.0
                    if (a > u) e += hist[b] * (a - u)
                }
                t[i] = e
            }
            return t
        }

        internal fun phiLin(table: DoubleArray, a: Double): Double {
            val n = table.size - 1
            if (a <= 0) return 0.0
            if (a >= 1) return table[n] + (a - 1)
            val f = a * n
            val i = floor(f).toInt()
            val w = f - i
            return table[i] * (1 - w) + table[i + 1] * w
        }

        internal fun psiTable(phi: DoubleArray): DoubleArray {
            val n = 256
            val t = DoubleArray(n + 1)
            // dΦ/da = P(u < a): recover the CDF of u from Φ by finite differences, then integrate.
            val cdf = DoubleArray(n + 1)
            for (i in 0..n) {
                val a0 = max(0.0, i - 0.5) / n
                val a1 = min(n.toDouble(), i + 0.5) / n
                cdf[i] = min(1.0, max(0.0, (phiLin(phi, a1) - phiLin(phi, a0)) / max(a1 - a0, 1e-9)))
            }
            for (i in 0..n) {
                val a = i.toDouble() / n
                var e = 0.0
                val steps = max(1, i)
                for (k in 0 until steps) {
                    val v = (k + 0.5) / steps * a
                    e += 1.5 * sqrt(a - v) * cdf[min(n, jsRound(v * n))] * (a / steps)
                }
                t[i] = e
            }
            return t
        }

        internal fun psiAt(t: DoubleArray, a: Double): Double {
            val n = t.size - 1
            if (a <= 0) return 0.0
            if (a >= 1) return t[n] * a.pow(1.5)
            val f = a * n
            val i = floor(f).toInt()
            val w = f - i
            return t[i] * (1 - w) + t[i + 1] * w
        }

        /** JavaScript's Math.round (halves round up, toward +∞). */
        internal fun jsRound(x: Double): Int = floor(x + 0.5).toInt()
    }
}

/** Squeeze c (mm, √-spaced over 0..[maxMm]) → tooth level a (tooth units, Float like the GPU table) and pressure. */
class ContactLut(val a: FloatArray, val p: DoubleArray, val maxMm: Double) {
    companion object { const val N = 255 }

    fun levelAt(c: Double): Double = at(c) { a[it].toDouble() }
    fun pressureAt(c: Double): Double = at(c) { p[it] }

    private inline fun at(c: Double, v: (Int) -> Double): Double {
        if (c <= 0) return 0.0
        val f = min(N.toDouble(), sqrt(c / maxMm) * N)
        val i = min(N - 1, floor(f).toInt())
        val w = f - i
        return v(i) * (1 - w) + v(i + 1) * w
    }
}

// Two-layer paper: a stiff rough tooth on a soft pad (springs in series). c = how far the stick pushed the paper
// down (mm); the tooth takes c_t, the pad the rest, both at one pressure:
//   pad p = (c − c_t) / compliance;  tooth p = k · T · Ψ(c_t / T).
internal fun buildContactLut(paper: MediaPaper, press: Press): ContactLut {
    val t = paper.toothMm
    val psi = paper.psi
    val kPad = 1 / max(0.05, paper.compliance * press.padSoft)
    val a = FloatArray(ContactLut.N + 1)
    val p = DoubleArray(ContactLut.N + 1)
    for (i in 0..ContactLut.N) {
        val q = i.toDouble() / ContactLut.N
        val c = q * q * press.lutMaxMm
        var lo = 0.0
        var hi = c
        repeat(40) {
            val ct = 0.5 * (lo + hi)
            if (kPad * (c - ct) > press.toothStiffness * t * MediaPaper.psiAt(psi, ct / t)) lo = ct else hi = ct
        }
        val ct = 0.5 * (lo + hi)
        a[i] = (ct / t).toFloat()
        p[i] = kPad * (c - ct)
    }
    return ContactLut(a, p, press.lutMaxMm)
}
