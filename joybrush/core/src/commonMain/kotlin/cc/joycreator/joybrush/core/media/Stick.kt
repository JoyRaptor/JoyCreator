package cc.joycreator.joybrush.core.media

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
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
 * A grade. Its field names are brush-file words (brush version 8, `media.stick`): rename none of them. Sizes in mm: tipR → tipMax the point's radius over pressure; side1/side2 zone lengths; face the half-width.
 *
 * [blend] and [build] are brush version 11 ([cc.joycreator.joybrush.core.brush.VERSION_PENCIL_BLEND]; the owner,
 * 2026-10-14: the tilted pencil "looks like two brushes overlaid", and its black "should build up a little slower"):
 * [blend] 0 draws the three zones as before, 1 one footprint (a soft point that melts into a soft-edged side); [build]
 * scales how readily the lead sheds graphite (1 = as before).
 */
@Serializable data class Stick(
    val tipR: Double, val tipMax: Double, val side1: Double, val side2: Double,
    val face: Double, val soft: Double, val rInf: Double,
    val blend: Double = 0.0, val build: Double = 1.0,
    /** The stick's own tuning (brush version 11), or null for the shared tables: [Press], [Zones], the STICK_* shape, [StickMaterial.of]. */
    val tuning: StickTuning? = null,
)

/**
 * Every number the pencil tuner moves (lab/media/pencil.html), carried by the stick itself so a tuned pencil paints the
 * same everywhere (brush version 11, `media.stick.tuning`; the owner, 2026-10-15: "pretty dialed in now - lets make it
 * the flagship"). Each default is the shared table's value, so `StickTuning()` paints exactly as no tuning does. Its
 * field names are brush-file words: rename none of them. Twin of the lab's `tuning` object (stick.js).
 *
 * Material: the `...X` numbers multiply what the grade's softness derives ([StickMaterial.of]); the rest replace it.
 * Curves are the app's response-curve form, `[x1, y1, x2, y2]` from (0, 0) to (1, 1) (see [stickCurve]): [sizeCurve]
 * and [darkCurve] shape how the point grows and darkens with pressure, [sideCurve] and [pointCurve] how the side comes
 * in and the point gives way with tilt. [tiltReachDeg] is the pen tilt that counts as lying flat ([MediaInput]).
 */
@Serializable data class StickTuning(
    val pressDepth: Double = 1.9, val pressGamma: Double = 1.5, val sideEase: Double = 0.45,
    val toothStiffness: Double = 9.0, val padSoft: Double = 1.0,
    val side1From: Double = 0.5, val side1Full: Double = 0.78, val side2From: Double = 0.7, val side2Full: Double = 0.98,
    val evenFrom: Double = 0.88, val grad1: Double = 1.2, val grad2: Double = 0.9,
    val bell: Double = STICK_BELL, val bellReach: Double = STICK_BELL_REACH, val acrossSoft: Double = STICK_ACROSS_SOFT,
    val tipYield: Double = STICK_TIP_YIELD, val tail: Double = STICK_TAIL,
    val capMm: Double = 0.004, val transferExp: Double = 2.2, val plateau: Double = 2.2, val crushStart: Double = 0.45,
    val crushMax: Double = 0.6, val clump: Double = 0.35, val pileLo: Double = 0.12, val pileHi: Double = 0.75,
    val dirStrength: Double = 0.9, val conform: Double = 0.8,
    val abrasionX: Double = 1.0, val leadSoftX: Double = 1.0, val dustRateX: Double = 1.0, val smearX: Double = 1.0,
    val smearMmX: Double = 1.0, val crushRateX: Double = 1.0, val sheenX: Double = 1.0,
    val sizeCurve: List<Double> = LINEAR_CURVE, val darkCurve: List<Double> = LINEAR_CURVE,
    val sideCurve: List<Double> = LINEAR_CURVE, val pointCurve: List<Double> = LINEAR_CURVE,
    val tiltReachDeg: Double = MediaInput.REACH_DEG,
) {
    val press: Press get() = Press(pressDepth, pressGamma, sideEase, toothStiffness, padSoft)
    val zones: Zones get() = Zones(side1From, side1Full, side2From, side2Full, evenFrom, grad1, grad2)
}

/** The straight response curve: what a missing curve means. */
val LINEAR_CURVE: List<Double> = listOf(1.0 / 3, 1.0 / 3, 2.0 / 3, 2.0 / 3)

/** y at [x] on a response curve `[x1, y1, x2, y2]` (core ResponseCurve's maths, in double; twin of stick.js curveEval). */
fun stickCurve(h: List<Double>, x: Double): Double {
    // The straight line is exactly the identity (a solved Bezier is only within 1e-7 of it): an untouched curve changes nothing.
    if (h.size != 4 || (0 until 4).all { kotlin.math.abs(h[it] - LINEAR_CURVE[it]) < 1e-9 }) return x
    val x1 = h[0].coerceIn(0.0, 1.0); val y1 = h[1]; val x2 = h[2].coerceIn(0.0, 1.0); val y2 = h[3]
    fun bez(a: Double, b: Double, t: Double): Double { val u = 1 - t; return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t }
    val target = x.coerceIn(0.0, 1.0)
    var lo = 0.0
    var hi = 1.0
    repeat(24) { val mid = (lo + hi) / 2; if (bez(x1, x2, mid) < target) lo = mid else hi = mid }
    return bez(y1, y2, (lo + hi) / 2).coerceIn(0.0, 1.0)
}

object Sticks {
    val ALL: Map<String, Stick> = linkedMapOf(
        // Proto Pencil, the flagship (the owner, 2026-10-15): his own settings from the pencil tuner, every one of them.
        "Proto Pencil" to Stick(0.15, 0.55, 3.5, 9.2, 1.1, 0.53, 0.01243, blend = 1.0, build = 0.36, tuning = StickTuning(
            pressDepth = 1.4, pressGamma = 1.68, sideEase = 0.73, toothStiffness = 9.2, padSoft = 0.9,
            side1From = 0.57, side1Full = 0.82, side2From = 0.74, side2Full = 0.95, evenFrom = 0.98, grad1 = 1.2, grad2 = 2.0,
            bell = 4.45, bellReach = 0.6, acrossSoft = 1.6, tipYield = 0.5, tail = 3.6,
            capMm = 0.0055, transferExp = 2.2, plateau = 2.05, crushStart = 0.58, crushMax = 0.45, clump = 0.46,
            pileLo = 0.11, pileHi = 0.44, dirStrength = 1.31, conform = 0.8,
            abrasionX = 1.39, leadSoftX = 1.98, dustRateX = 2.0, smearX = 1.5, smearMmX = 2.0, crushRateX = 0.56, sheenX = 1.12,
            sizeCurve = listOf(0.327214, 0.1815, 0.776646, 0.608461), darkCurve = listOf(0.16948, 0.639946, 0.695278, 0.473668),
            sideCurve = listOf(0.340697, 0.377986, 0.682266, 0.607197), pointCurve = listOf(0.533494, 0.112884, 0.470578, 0.890346),
            tiltReachDeg = 61.0,
        )),
        // Proto: a soft woodless stick, the owner's Infinite Painter "Proko" reference.
        "Proto" to Stick(0.25, 1.0, 5.5, 16.0, 1.6, 0.75, 0.006),
        // Proto 2 (the owner, 2026-10-14): a finer point, a smaller side when tilted, one blended footprint, and
        // black that builds more slowly. Proto stays beside it to compare.
        "Proto 2" to Stick(0.15, 0.55, 3.5, 10.0, 1.1, 0.75, 0.006, blend = 1.0, build = 0.6),
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
    /** 0 the three zones, 1 one blended footprint ([Stick.blend]): the dry dab shader's u_stickBlend. */
    val blend: Double = 0.0,
    /** The blended footprint's shape (u_stickShape): bell, acrossSoft, tipYield, tail (the STICK_* constants). */
    val bell: Double = STICK_BELL, val acrossSoft: Double = STICK_ACROSS_SOFT, val tipYield: Double = STICK_TIP_YIELD,
    val tail: Double = STICK_TAIL,
    /** The side zones' fade curves (u_zoneGrad): [Zones.grad1], [Zones.grad2]. */
    val grad1: Double = Zones().grad1, val grad2: Double = Zones().grad2,
    /** The paper's two-layer contact table is built for these (MediaPaper.contactLut). */
    val toothStiffness: Double = Press().toothStiffness, val padSoft: Double = Press().padSoft,
) {
    /** The press the contact table is built for. */
    val lutPress: Press get() = Press(toothStiffness = toothStiffness, padSoft = padSoft)

    companion object {
        fun of(stick: Stick): StickMaterial {
            val base = shared(stick)
            val tu = stick.tuning ?: return base
            return base.copy(
                capMm = tu.capMm, transferExp = tu.transferExp, plateau = tu.plateau, crushStart = tu.crushStart,
                crushMax = tu.crushMax, clump = tu.clump, pileRangeLo = tu.pileLo, pileRangeHi = tu.pileHi,
                dirStrength = tu.dirStrength, conform = tu.conform,
                abrasion = base.abrasion * tu.abrasionX, leadSoft = base.leadSoft * tu.leadSoftX, dustRate = base.dustRate * tu.dustRateX,
                smear = base.smear * tu.smearX, smearMm = base.smearMm * tu.smearMmX, crushRate = base.crushRate * tu.crushRateX,
                sheen = base.sheen * tu.sheenX,
                bell = tu.bell, acrossSoft = tu.acrossSoft, tipYield = tu.tipYield, tail = tu.tail, grad1 = tu.grad1, grad2 = tu.grad2,
                toothStiffness = tu.toothStiffness, padSoft = tu.padSoft,
            )
        }

        /** What the grade's softness gives, before any tuning. */
        private fun shared(stick: Stick): StickMaterial {
            val s = stick.soft
            return StickMaterial(
                capMm = 0.004,
                flakeR = stick.rInf,
                abrasion = 400 * (0.25 + 0.75 * s * s) * stick.build,
                transferExp = 2.2,
                leadSoft = 0.08 + 0.22 * s,
                plateau = 2.2,
                crushRate = 26 * (1.15 - s),
                crushStart = 0.45,
                crushMax = 0.6,
                smear = 0.18 + 0.6 * s * s,
                smearMm = 0.15 + 0.3 * s,
                dustRate = (0.0006 + 0.0024 * s) * stick.build,
                clump = 0.35,
                pileRangeLo = 0.12, pileRangeHi = 0.75,
                dirStrength = 0.9,
                conform = 0.8,
                sheen = 0.03 + 0.10 * s,
                blend = stick.blend,
            )
        }
    }
}

/** The contact for one pen sample: squeeze at the tip [d] (mm), zone sizes (mm), zone weights (sum 1), extents. */
data class StickContact(
    val d: Double, val r0: Double, val l1: Double, val l2: Double, val h: Double,
    val w0: Double, val w1: Double, val w2: Double, val even: Double,
    val xMin: Double, val xMax: Double, val yMax: Double,
    val blend: Double = 0.0,
)

/** The blended footprint (Stick.blend 1; twin of stick.js and jb_stick.glsl): a bell point exp(−BELL·r²/R0²) that
 * reaches BELL_REACH past R0, side edges falling as (1 − y²/H²)^ACROSS_SOFT, and a point that yields TIP_YIELD of its
 * share where the side is down and runs on as a tail into it (the owner, 2026-10-14, on fine linen: the dark core
 * thinner, the gradient continuous). */
const val STICK_BELL = 2.2
const val STICK_BELL_REACH = 0.6
const val STICK_ACROSS_SOFT = 1.6
const val STICK_TIP_YIELD = 0.5

/** Blended, the point's darkness runs on this many R0 toward the barrel where the side is down, fading into it. */
const val STICK_TAIL = 2.5

/**
 * The contact for one pen sample. A stick with its own [Stick.tuning] uses its press, zones, fringe and curves; the
 * defaults here are then only what an untuned stick uses. With straight curves (or none) the point's share and the
 * side's split are exactly w / Σw, the contact of version 8: the point's share comes from the point curve's tilt, the
 * side's split of the rest from the side curve's (a point cannot give way where no side is down to take the pressure).
 */
fun stickContact(stick: Stick, tilt: Double, pressure: Double, toothMm: Double,
                 press: Press = stick.tuning?.press ?: Press(), zones: Zones = stick.tuning?.zones ?: Zones()): StickContact? {
    val p = max(0.0, min(1.0, pressure))
    if (p <= 0) return null
    val tu = stick.tuning
    val t = max(0.0, min(1.0, tilt / (PI / 2)))
    val ts = if (tu == null) t else stickCurve(tu.sideCurve, t)
    val tp = if (tu == null) t else stickCurve(tu.pointCurve, t)
    val side = zonesAt(zones, ts, p)
    val pt = if (tp == ts) side else zonesAt(zones, tp, p)
    val s1 = side.s1
    val s2 = side.s2
    val even = side.even
    val pSize = if (tu == null) p else stickCurve(tu.sizeCurve, p)
    val pDark = if (tu == null) p else stickCurve(tu.darkCurve, p)
    val r0 = stick.tipR + (stick.tipMax - stick.tipR) * pSize.pow(0.8)
    val l1 = r0 + (stick.side1 - r0) * s1
    val l2 = l1 + (stick.side2 - l1) * s2
    val share0 = pt.w0 / (pt.w0 + pt.w1 + pt.w2)
    val sideSum = side.w1 + side.w2
    var w0 = 1.0
    var w1 = 0.0
    var w2 = 0.0
    if (sideSum > 1e-9) { w0 = share0; w1 = (1 - share0) * side.w1 / sideSum; w2 = (1 - share0) * side.w2 / sideSum }
    val d = toothMm * press.depth * pDark.pow(press.gamma) / (1 + press.sideEase * (0.4 * s1 + s2))
    val sideOn = w1 + w2 > 0.005
    // A blended point is a soft bell that reaches a little past r0: the footprint grows to hold it.
    val reach = r0 * (1 + (tu?.bellReach ?: STICK_BELL_REACH) * stick.blend)
    return StickContact(
        d, r0, l1, l2, if (sideOn) stick.face else r0, w0, w1, w2, even,
        xMin = -reach,
        xMax = if (sideOn) max(reach, if (w2 > 0.005) l2 else l1) else reach,
        yMax = if (sideOn) max(reach, stick.face) else reach,
        blend = stick.blend,
    )
}

/** The zones at a tilt fraction [u]: how far each side is down, the near-flat evening, and the raw zone weights. */
private class ZoneState(val s1: Double, val s2: Double, val even: Double, val w0: Double, val w1: Double, val w2: Double)

private fun zonesAt(zones: Zones, u: Double, p: Double): ZoneState {
    val a = smoothstep(zones.side1From, zones.side1Full, u)
    val b = smoothstep(zones.side2From, zones.side2Full, u)
    // Near flat and lightly held, the side lies down evenly and the tip no longer dominates.
    val ev = smoothstep(zones.evenFrom, 1.0, u) * (1 - 0.6 * p)
    return ZoneState(a, b, ev, 1 - 0.8 * ev, 0.9 * a * (1 - 0.35 * b), 0.8 * b)
}

/** Twin of jb_stickSqueeze: the squeeze (mm) at a stick-frame point (x toward the barrel, y across). [tuning] gives the
 * shape and fades of a tuned stick (the shader's u_stickShape and u_zoneGrad). */
fun stickSqueeze(x: Double, y: Double, c: StickContact, zones: Zones = Zones(), tuning: StickTuning? = null): Double {
    val bellK = tuning?.bell ?: STICK_BELL
    val tailK = tuning?.tail ?: STICK_TAIL
    val acrossK = tuning?.acrossSoft ?: STICK_ACROSS_SOFT
    val yieldK = tuning?.tipYield ?: STICK_TIP_YIELD
    val b = c.blend
    val r2 = c.r0 * c.r0
    fun mix(a: Double, z: Double) = a + (z - a) * b
    val sideDown = min(1.0, (c.w1 + c.w2) * 2)
    val along = if (x < 0) exp(-bellK * x * x / r2)
        else exp(-bellK * x * x / r2).let { bell -> bell + (exp(-x / (tailK * c.r0)) - bell) * sideDown }
    val point = mix(max(0.0, 1 - (x * x + y * y) / r2), exp(-bellK * y * y / r2) * along)
    val front = if (x < 0) mix(max(0.0, 1 - (x * x) / r2), exp(-bellK * x * x / r2)) else 1.0
    fun ramp(l: Double, g: Double, ev: Double): Double {
        if (x < 0) return 1.0
        val u = x / max(l, 1e-3)
        if (u >= 1) return 0.0
        return (1 - ev) * (1 - u).pow(g) + ev * (1 - smoothstep(0.45, 1.0, u))
    }
    fun across(l: Double): Double {
        val hw = c.r0 + (c.h - c.r0) * smoothstep(0.0, 0.3 * l, max(x, 0.0))
        val e = max(0.0, 1 - (y * y) / (hw * hw))
        return mix(sqrt(e), e.pow(acrossK))
    }
    val g2 = zones.grad2 + (0.55 - zones.grad2) * c.even
    val side = (c.w1 * ramp(c.l1, zones.grad1, 0.0) * across(c.l1) + c.w2 * ramp(c.l2, g2, c.even) * across(c.l2)) * front
    // Blended, the point stops sitting on top of the side as a second mark: where the side is down it yields.
    val pointShare = c.w0 * (1 - yieldK * b * sideDown)
    return c.d * (pointShare * point + side)
}

/** One stroke of a dry stick: samples in, instanced dabs out (20 floats each, see jb_dry_dab.vert). */
class DryStroke(
    private val stick: Stick,
    private val toothMm: Double,
    private val pxPerMm: Double,
    private val press: Press = stick.tuning?.press ?: Press(),
    private val zones: Zones = stick.tuning?.zones ?: Zones(),
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
        val zero = StickContact(0.0, some.r0, some.l1, some.l2, some.h, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, some.blend)
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
        // Project both local axes: a rotated rectangle can extend beyond its
        // largest local extent. Keep the existing two-pixel raster padding.
        val along = max(abs(inst[4]), abs(inst[5])) + 0.06
        val across = inst[6] + 0.06
        val rx = (along * abs(lx) + across * abs(ly)) * pxPerMm + 2
        val ry = (along * abs(ly) + across * abs(lx)) * pxPerMm + 2
        out.push(inst, doubleArrayOf(cx - rx, cy - ry, cx + rx, cy + ry))
    }

    fun take(): DabBatch = out.take()
}
