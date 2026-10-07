package cc.joycreator.joybrush.core.media

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// Fluid media (watercolour, ink, the water riding thinned oil): the brush, its stroke → dab builder, and the
// simulation constants. Port of lab/media/js/wet.js; the water itself runs on the GPU (shaders/media/jb_wet_*).
// The brush exchanges water with the paper by wetness, one state per brush (no pickup-vs-reservoir split:
// patent guard, MEDIA_ENGINE_PLAN §1.2). The owner rated the watercolour S-tier.

private const val D2R = PI / 180

/** Simulation constants (sim seconds). Paper numbers scale them per paper (see [wetUniforms]). */
data class WetConstants(
    val dt: Double = 1.0 / 120, val substeps: Int = 4, val flow: Double = 30.0, val keep: Double = 0.9,
    val pinMm: Double = 0.22, val dampS: Double = 0.35, val minMm: Double = 0.002, val evap: Double = 0.003,
    val edgeEvap: Double = 4.0, val absorb: Double = 0.25, val capMm: Double = 0.08, val wick: Double = 5.0,
    val sDry: Double = 0.03, val settle: Double = 0.08, val lift: Double = 0.04, val stainCarry: Double = 0.12,
    val filmMm: Double = 0.12, val edgeDep: Double = 5.0, val mingle: Double = 12.0, val fullMm: Double = 0.4,
    // Running water on a tilted paper (drips):
    val runMmPerS: Double = 8.0, val runMm: Double = 0.3, val runHold: Double = 0.5, val runFilm: Double = 0.012,
    val runCohere: Double = 0.3, val runCoherent: Double = 1.0, val steerScale: Double = 1.0, val runHoldK: Double = 1.0,
    val quiet: Double = 0.01, val dripGap: Double = 4.0, val runSizing: Double = 0.45, val runPin: Double = 0.5,
    val runSteer: Double = 4.0, val runAlong: Double = 0.25, val beadMm: Double = 0.6,
)

/** How wet the brush is, tapped up and down like Expresii's water drop and napkin (owner, 2026-10-07). */
data class Wetness(val name: String, val load: Double, val flow: Double)

val WETNESS: List<Wetness> = listOf(
    Wetness("dry", 0.15, 0.6),
    Wetness("damp", 0.4, 0.8),
    Wetness("wet", 0.8, 1.0),
    Wetness("loaded", 1.0, 1.25),
    Wetness("runny", 1.35, 1.7),
)

/** Thinner in oil paint: [water] is the WETNESS index of the water stroke riding along (null = neat). */
data class Thinner(val name: String, val water: Int?, val body: Double)

val THINNER: List<Thinner> = listOf(
    Thinner("neat", null, 1.0),
    Thinner("thinned", 1, 0.8),
    Thinner("watery", 2, 0.6),
    Thinner("runny", 4, 0.4),
)

/** A watercolour brush. Its field names are brush-file words (brush version 8, `media.wet`): rename none of them. */
@Serializable data class WetBrush(
    val bellyMm: Double, val tipMm: Double, val waterPerMm: Double, val capacityMm3: Double, val load: Double,
    val gran: Double, val stain: Double, val beadMm: Double, val liftMm: Double, val dwellMmPerS: Double,
    val allround: Boolean = false, val clear: Boolean = false,
)

object WetBrushes {
    val ALL: Map<String, WetBrush> = linkedMapOf(
        "All-round" to WetBrush(4.5, 0.06, 0.09, 150.0, 0.95, 1.0, 0.3, 2.5, 3.0, 1.5, allround = true),
        "Round" to WetBrush(3.0, 0.3, 0.09, 120.0, 0.95, 1.0, 0.3, 2.5, 3.0, 1.5),
        "Wash" to WetBrush(6.0, 2.0, 0.1, 260.0, 1.0, 1.0, 0.3, 3.0, 4.0, 2.0),
        "Dry brush" to WetBrush(3.5, 0.8, 0.05, 90.0, 0.22, 1.0, 0.3, 0.0, 0.0, 0.3),
        "Water" to WetBrush(4.0, 0.6, 0.1, 120.0, 1.0, 1.0, 0.0, 2.5, 3.0, 1.5, clear = true),
        "Thirsty" to WetBrush(3.0, 0.6, 0.05, 90.0, 0.08, 1.0, 0.0, 0.0, 0.0, 0.5, clear = true),
    )
}

/** A transparent paint: absorption K per channel and scattering S, per mm of water. */
class WetPaint(val k: DoubleArray, val s: Double)

private fun srgbToLinear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

/** The colour a full-strength wash dries to on white → a transparent paint (Beer–Lambert, a little scattering). */
fun paintFromColor(rgb: DoubleArray, strength: Double = 1.0): WetPaint {
    val k = DoubleArray(3) { -ln(max(srgbToLinear(rgb[it]), 0.015)) / 2 }
    val refMm = 0.4
    val meanK = (k[0] + k[1] + k[2]) / 3
    return WetPaint(DoubleArray(3) { k[it] * strength / refMm }, 0.04 * meanK * strength / refMm)
}

/** Paper tilt → the slope gravity pulls along: sin(tiltDeg) toward [dir] (unit, doc space, downhill). */
fun tiltSlope(tiltDeg: Double, dirX: Double, dirY: Double): DoubleArray {
    val s = sin(min(90.0, max(0.0, tiltDeg)) * D2R)
    return doubleArrayOf(dirX * s, dirY * s)
}

/** The wet passes' uniforms that depend on the paper and the constants (names as in the shaders). */
fun wetUniforms(paper: MediaPaper, w: WetConstants = WetConstants()): Map<String, Double> {
    val sizing = paper.sizing
    return linkedMapOf(
        "u_dt" to w.dt, "u_flow" to w.flow, "u_keep" to w.keep, "u_pinMm" to w.pinMm * (0.4 + sizing),
        "u_dampS" to w.dampS, "u_minMm" to w.minMm, "u_evap" to w.evap, "u_edgeEvap" to w.edgeEvap,
        "u_absorb" to w.absorb * paper.absorbency * 2 * (1 - 0.95 * sizing),
        "u_capMm" to w.capMm * paper.capacity * 2, "u_wick" to min(0.24 / w.dt, w.wick * paper.wickSpeed * 2),
        "u_sDry" to w.sDry, "u_settle" to w.settle, "u_lift" to w.lift, "u_stainCarry" to w.stainCarry,
        "u_toothMm" to paper.toothMm,
        "u_runMmPerS" to w.runMmPerS, "u_runMm" to w.runMm, "u_runHoldMm" to w.runHold * paper.toothMm,
        "u_runFilmMm" to w.runFilm, "u_runPin" to w.runPin, "u_runSizing" to w.runSizing, "u_dripGapMm" to w.dripGap,
        "u_quietMm" to w.quiet, "u_runHoldK" to w.runHoldK, "u_runCoherent" to w.runCoherent, "u_runCohere" to w.runCohere,
        "u_steerScale" to w.steerScale, "u_runSteer" to w.runSteer, "u_runAlong" to w.runAlong,
        "u_heightMean" to paper.heightMean, "u_fullMm" to w.fullMm, "u_filmMm" to w.filmMm, "u_edgeDep" to w.edgeDep,
        "u_mingle" to min(0.24 / w.dt, w.mingle),
    )
}

/** The medium that runs out of thinned oil paint: as wide as the paint's footprint. */
fun thinnerBrush(paste: PasteBrush): WetBrush {
    val half = paste.widthMm / 2
    return WetBrush(
        bellyMm = half, tipMm = if (paste.allround) max(0.1, paste.tipHalfMm) else half * 0.55,
        waterPerMm = 0.09, capacityMm3 = 160.0, load = 0.9, gran = 0.6, stain = 0.25, beadMm = 1.5, liftMm = 2.5,
        dwellMmPerS = 1.2, allround = paste.allround,
    )
}

/** The water stroke riding along a thinned oil stroke (null when the paint is neat). */
fun thinnerStroke(paste: PasteBrush, color: DoubleArray, pxPerMm: Double, seed: Int, level: Int): WetStroke? {
    val t = THINNER.getOrNull(level) ?: return null
    val water = t.water ?: return null
    return WetStroke(thinnerBrush(paste), paintFromColor(color, 0.55 + 0.15 * level), pxPerMm, seed + 7919, WETNESS[water])
}

/** One wet stroke: samples in, wet dabs out (20 floats each, see jb_wet_dab.vert). */
class WetStroke(
    private val brush: WetBrush,
    paint: WetPaint,
    private val pxPerMm: Double,
    private val seed: Int = 1,
    wetness: Wetness? = null,
) {
    // Blotting the brush leaves the paint in it strong; dipping it thins it: strength ∝ (0.8 / load)^0.6.
    private val paint: WetPaint
    var water: Double = if (wetness != null) min(1.5, wetness.load) else brush.load   // 0..1+ of capacity
        private set
    private val flow = wetness?.flow ?: 1.0
    private var travelled = 0.0
    private var last: MediaSample? = null
    private var dirX = 0.0
    private var dirY = 0.0
    private var hasDir = false
    private var carry = 0.0
    private var started = false
    private var beadTravel = 0.0
    private val out = DabCollector()

    init {
        val conc = if (wetness != null && !brush.clear) min(2.6, max(0.7, (0.8 / max(0.1, wetness.load)).pow(0.6))) else 1.0
        this.paint = if (brush.clear) WetPaint(DoubleArray(3), 0.0) else WetPaint(DoubleArray(3) { paint.k[it] * conc }, paint.s * conc)
    }

    fun add(s: MediaSample) {
        val prev = last
        last = s
        if (prev == null) return
        val dx = s.x - prev.x
        val dy = s.y - prev.y
        val len = hypot(dx, dy)
        if (len > 1e-6) {
            // The hairs swing round over about a millimetre and a half of travel, not per pen report.
            val tx = dx / len
            val ty = dy / len
            if (hasDir) {
                val k = 1 - exp(-(len / pxPerMm) / 1.5)
                val n = norm2(dirX + (tx - dirX) * k, dirY + (ty - dirY) * k)
                dirX = n[0]; dirY = n[1]
            } else { dirX = tx; dirY = ty; hasDir = true }
            travelled += len / pxPerMm
        }
        if (!hasDir) { dirX = cos(s.az); dirY = sin(s.az); hasDir = true }
        val b0 = halfWidth(prev)
        val b1 = halfWidth(s)
        val spacingPx = max(0.6, 0.22 * min(b0, b1) * pxPerMm)
        var pos = spacingPx - carry
        if (!started && s.p > 0.02) started = true
        if (len < 1e-6) {
            // A brush resting in place keeps bleeding water into the paper.
            emit(s, s, 0.0, brush.dwellMmPerS * max(0.0, (s.t - prev.t) / 1000))
            return
        }
        while (pos <= len) {
            emit(prev, s, pos / len, spacingPx / pxPerMm)
            pos += spacingPx
        }
        carry = len - (pos - spacingPx)
    }

    /** Lift: what is left hanging on the tip drops where the stroke ends; a slow lift leaves more. */
    fun finish() {
        val s = last ?: return
        if (!started) return
        emit(s, s, 0.0, brush.liftMm * water * water)
    }

    private fun halfWidth(s: MediaSample): Double {
        val p = max(0.0, min(1.0, s.p))
        val t = min(1.0, s.tilt / (60 * D2R))
        return (brush.tipMm + (brush.bellyMm - brush.tipMm) * p.pow(if (brush.allround) 1.6 else 0.75)) * (1 + 0.35 * t)
    }

    private fun emit(a: MediaSample, b: MediaSample, w: Double, slideIn: Double) {
        // The bead hanging at a loaded tip comes off over the first few millimetres: the stroke starts in a pool.
        beadTravel += slideIn
        val beadLen = 3.0
        val slideMm = slideIn * (1 + (brush.beadMm / beadLen) * water * exp(-beadTravel / beadLen))
        fun l(x: Double, y: Double) = x + (y - x) * w
        val sp = l(a.p, b.p)
        if (sp <= 0.002) return
        val s = MediaSample(l(a.x, b.x), l(a.y, b.y), sp, l(a.tilt, b.tilt), 0.0, 0.0)
        val half = halfWidth(s)
        // A round puddle on touchdown; the footprint only stretches along the drag once the brush is moving.
        val along = half * (1 + (0.25 + 0.6 * min(1.0, s.tilt / (60 * D2R))) * smoothstep(0.0, 3.0, travelled))
        val dryness = 1 - smoothstep(0.05, 0.45, water)
        val wet = water
        val inst = doubleArrayOf(
            s.x, s.y, dirX, dirY,
            along, half, brush.waterPerMm * flow, wet,
            paint.k[0], paint.k[1], paint.k[2], paint.s,
            slideMm, dryness, 1 - brush.stain, seed.toDouble(),
            0.0, 0.0, 0.0, 0.0,
        )
        // Water leaves the brush roughly in proportion to what it lays down on dry paper.
        val area = PI * along * half * 0.6
        val given = brush.waterPerMm * flow * slideMm * wet * area * (1 - 0.5 * dryness)
        water = max(0.0, water - given / brush.capacityMm3)
        val r = (along + 0.1) * pxPerMm + 2
        out.push(inst, doubleArrayOf(s.x - r, s.y - r, s.x + r, s.y + r))
    }

    fun take(): DabBatch = out.take()
}
