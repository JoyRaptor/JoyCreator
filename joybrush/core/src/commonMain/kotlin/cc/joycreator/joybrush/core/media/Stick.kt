package cc.joycreator.joybrush.core.media

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Dry media (graphite, charcoal, pastel): where the stick touches the paper and how hard, and the stroke →
// dab builder. Port of lab/media/js/stick.js; the GPU half is shaders/media/jb_stick.glsl (jb_stickSqueeze).
//
// The contact is three overlapping zones, ALL anchored at the pen tip (owner, 2026-10-07, from Infinite
// Painter's Proko pencil): the point (a hard edge, 0.5 → 2 mm with pressure), side 1 (the worn face, a strong
// gradient, comes in at middle tilts) and side 2 (the whole side, feathering, near flat). Pressure sets the
// squeeze; tilt sets which zones are down. Nothing is ever placed away from the pen tip. The owner rated it A+.

/**
 * A grade. Its field names are brush-file words (brush version 8, `media.stick`): rename none of them. Sizes in mm: tipR → tipMax the point's radius over pressure; side1/side2 zone lengths; face the half-width. */
@Serializable data class Stick(
    val tipR: Double, val tipMax: Double, val side1: Double, val side2: Double,
    val face: Double, val soft: Double, val rInf: Double,
)

object Sticks {
    val ALL: Map<String, Stick> = linkedMapOf(
        // Proto: a soft woodless stick, the owner's Infinite Painter "Proko" reference.
        "Proto" to Stick(0.25, 1.0, 5.5, 16.0, 1.6, 0.75, 0.006),
        "2H" to Stick(0.12, 0.35, 2.2, 5.0, 0.6, 0.15, 0.22),
        "HB" to Stick(0.14, 0.45, 2.6, 5.5, 0.7, 0.35, 0.12),
        "2B" to Stick(0.17, 0.6, 3.0, 6.0, 0.8, 0.5, 0.07),
        "6B" to Stick(0.2, 0.7, 3.5, 7.0, 0.9, 0.8, 0.03),
        "Woodless 8B" to Stick(0.3, 1.2, 6.0, 24.0, 2.0, 0.95, 0.006),
    )
}

/** When each zone comes in, as a fraction of the pen's tilt range (0 upright … 1 the flattest it reports). */
data class Zones(
    val side1From: Double = 0.5, val side1Full: Double = 0.78,
    val side2From: Double = 0.7, val side2Full: Double = 0.98,
    val evenFrom: Double = 0.88,
    val grad1: Double = 1.2,
    val grad2: Double = 0.9,
)

data class Press(
    val depth: Double = 1.9,          // squeeze at the tip at full pressure, in tooth depths
    val gamma: Double = 1.5,          // pressure curve: a long, gentle light end
    val sideEase: Double = 0.45,      // the same force along the side squeezes less
    val toothStiffness: Double = 9.0, // tooth vs pad stiffness
    val padSoft: Double = 1.0,        // × paper compliance
    val lutMaxMm: Double = 2.0,       // deepest squeeze tabulated
)

/** Engine numbers derived from a stick: everything the dry shaders need per stroke. */
data class StickMaterial(
    val capMm: Double, val flakeR: Double, val abrasion: Double, val transferExp: Double, val leadSoft: Double,
    val plateau: Double, val crushRate: Double, val crushStart: Double, val crushMax: Double, val smear: Double,
    val smearMm: Double, val dustRate: Double, val clump: Double, val pileRangeLo: Double, val pileRangeHi: Double,
    val dirStrength: Double, val conform: Double, val sheen: Double,
) {
    companion object {
        fun of(stick: Stick): StickMaterial {
            val s = stick.soft
            return StickMaterial(
                capMm = 0.004,
                flakeR = stick.rInf,
                abrasion = 400 * (0.25 + 0.75 * s * s),
                transferExp = 2.2,
                leadSoft = 0.08 + 0.22 * s,
                plateau = 2.2,
                crushRate = 26 * (1.15 - s),
                crushStart = 0.45,
                crushMax = 0.6,
                smear = 0.18 + 0.6 * s * s,
                smearMm = 0.15 + 0.3 * s,
                dustRate = 0.0006 + 0.0024 * s,
                clump = 0.35,
                pileRangeLo = 0.12, pileRangeHi = 0.75,
                dirStrength = 0.9,
                conform = 0.8,
                sheen = 0.03 + 0.10 * s,
            )
        }
    }
}

/** The contact for one pen sample: squeeze at the tip [d] (mm), zone sizes (mm), zone weights (sum 1), extents. */
data class StickContact(
    val d: Double, val r0: Double, val l1: Double, val l2: Double, val h: Double,
    val w0: Double, val w1: Double, val w2: Double, val even: Double,
    val xMin: Double, val xMax: Double, val yMax: Double,
)

fun stickContact(stick: Stick, tilt: Double, pressure: Double, toothMm: Double, press: Press = Press(), zones: Zones = Zones()): StickContact? {
    val p = max(0.0, min(1.0, pressure))
    if (p <= 0) return null
    val t = max(0.0, min(1.0, tilt / (PI / 2)))
    val s1 = smoothstep(zones.side1From, zones.side1Full, t)
    val s2 = smoothstep(zones.side2From, zones.side2Full, t)
    // Near flat and lightly held, the side lies down evenly and the tip no longer dominates.
    val even = smoothstep(zones.evenFrom, 1.0, t) * (1 - 0.6 * p)
    val r0 = stick.tipR + (stick.tipMax - stick.tipR) * p.pow(0.8)
    val l1 = r0 + (stick.side1 - r0) * s1
    val l2 = l1 + (stick.side2 - l1) * s2
    var w0 = 1 - 0.8 * even
    var w1 = 0.9 * s1 * (1 - 0.35 * s2)
    var w2 = 0.8 * s2
    val sum = w0 + w1 + w2
    w0 /= sum; w1 /= sum; w2 /= sum
    val d = toothMm * press.depth * p.pow(press.gamma) / (1 + press.sideEase * (0.4 * s1 + s2))
    val sideOn = w1 + w2 > 0.005
    return StickContact(
        d, r0, l1, l2, if (sideOn) stick.face else r0, w0, w1, w2, even,
        xMin = -r0,
        xMax = if (sideOn) max(r0, if (w2 > 0.005) l2 else l1) else r0,
        yMax = if (sideOn) max(r0, stick.face) else r0,
    )
}

/** Twin of jb_stickSqueeze: the squeeze (mm) at a stick-frame point (x toward the barrel, y across). */
fun stickSqueeze(x: Double, y: Double, c: StickContact, zones: Zones = Zones()): Double {
    val point = max(0.0, 1 - (x * x + y * y) / (c.r0 * c.r0))
    val front = if (x < 0) max(0.0, 1 - (x * x) / (c.r0 * c.r0)) else 1.0
    fun ramp(l: Double, g: Double, ev: Double): Double {
        if (x < 0) return 1.0
        val u = x / max(l, 1e-3)
        if (u >= 1) return 0.0
        return (1 - ev) * (1 - u).pow(g) + ev * (1 - smoothstep(0.45, 1.0, u))
    }
    fun across(l: Double): Double {
        val hw = c.r0 + (c.h - c.r0) * smoothstep(0.0, 0.3 * l, max(x, 0.0))
        return sqrt(max(0.0, 1 - (y * y) / (hw * hw)))
    }
    val g2 = zones.grad2 + (0.55 - zones.grad2) * c.even
    return c.d * (c.w0 * point + (c.w1 * ramp(c.l1, zones.grad1, 0.0) * across(c.l1) + c.w2 * ramp(c.l2, g2, c.even) * across(c.l2)) * front)
}

/** One stroke of a dry stick: samples in, instanced dabs out (20 floats each, see jb_dry_dab.vert). */
class DryStroke(
    private val stick: Stick,
    private val toothMm: Double,
    private val pxPerMm: Double,
    private val press: Press = Press(),
    private val zones: Zones = Zones(),
) {
    private class Solved(val s: MediaSample, val c: StickContact?, val leanX: Double, val leanY: Double)

    private var last: Solved? = null
    private var travelX = 1.0
    private var travelY = 0.0
    private var carry = 0.0
    private val out = DabCollector()

    private fun solve(s: MediaSample) = Solved(s, stickContact(stick, s.tilt, s.p, toothMm, press, zones), cos(s.az), sin(s.az))

    fun add(sample: MediaSample) {
        val cur = solve(sample)
        val prev = last
        last = cur
        if (prev == null) return
        val dx = cur.s.x - prev.s.x
        val dy = cur.s.y - prev.s.y
        val len = hypot(dx, dy)
        if (len > 1e-6) {
            val k = 0.35   // light low-pass on the travel direction (pointer jitter)
            val tx = travelX * (1 - k) + dx / len * k
            val ty = travelY * (1 - k) + dy / len * k
            val tl = hypot(tx, ty).let { if (it == 0.0) 1.0 else it }
            travelX = tx / tl; travelY = ty / tl
        }
        if (prev.c == null && cur.c == null) return
        fun width(c: StickContact?) = if (c != null) max(0.05, min(c.xMax - c.xMin, 2 * c.yMax)) else 0.05
        val spacingPx = min(2.5, max(0.35, 0.18 * min(width(prev.c), width(cur.c)) * pxPerMm))
        val dwellMm = max(0.0, cur.s.t - prev.s.t) * 0.00012   // a held pencil still leaves a trace
        var pos = spacingPx - carry
        if (len < 1e-6) {
            emit(cur, cur, 1.0, dwellMm)
            return
        }
        while (pos <= len) {
            emit(prev, cur, pos / len, spacingPx / pxPerMm + dwellMm * spacingPx / len)
            pos += spacingPx
        }
        carry = len - (pos - spacingPx)
    }

    private fun emit(a: Solved, b: Solved, w: Double, slideMm: Double) {
        val ca = a.c
        val cb = b.c
        if (ca == null && cb == null) return
        val some = ca ?: cb!!
        val zero = StickContact(0.0, some.r0, some.l1, some.l2, some.h, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val ra = ca ?: zero
        val rb = cb ?: zero
        fun l(x: Double, y: Double) = x + (y - x) * w
        var lx = l(a.leanX, b.leanX)
        var ly = l(a.leanY, b.leanY)
        val ll = hypot(lx, ly).let { if (it == 0.0) 1.0 else it }
        lx /= ll; ly /= ll
        val cx = l(a.s.x, b.s.x)
        val cy = l(a.s.y, b.s.y)
        val inst = doubleArrayOf(
            cx, cy, lx, ly,
            min(ra.xMin, rb.xMin), max(ra.xMax, rb.xMax), max(ra.yMax, rb.yMax), l(ra.d, rb.d),
            l(ra.r0, rb.r0), l(ra.l1, rb.l1), l(ra.l2, rb.l2), l(ra.h, rb.h),
            l(ra.w0, rb.w0), l(ra.w1, rb.w1), l(ra.w2, rb.w2), slideMm,
            travelX, travelY, l(ra.even, rb.even), l(a.s.p, b.s.p),
        )
        val r = max(max(abs(inst[4]), abs(inst[5])), inst[6]) + 0.06
        val rp = r * pxPerMm + 2
        out.push(inst, doubleArrayOf(cx - rp, cy - rp, cx + rp, cy + rp))
    }

    fun take(): DabBatch = out.take()
}
