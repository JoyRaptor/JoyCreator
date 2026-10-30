package cc.joycreator.joybrush.core.brush

import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

@Serializable
enum class ColorMode { perDab, perStroke, perStrokeDrift }

/** New, opt-in dynamics; these fields do not activate dormant legacy ColorJitter values. */
@Serializable
data class ColorDynamicsSpec(
    val mode: ColorMode = ColorMode.perDab,
    /** Symmetric random hue displacement, 0..180 degrees. */
    val hueRangeDegrees: Float = 0f,
    /** Symmetric additive HSV saturation displacement, 0..1. */
    val saturationRange: Float = 0f,
    /** Symmetric additive HSV value displacement, 0..1. */
    val valueRange: Float = 0f,
    /** Uniform random interpolation amount from 0 through this maximum, 0..1. */
    val secondaryMix: Float = 0f,
    /** Added hue at full pressure, -180..180 degrees; zero at zero/missing pressure. */
    val pressureHueDegrees: Float = 0f,
    /** Document pixels between drift knots; finite and strictly positive. */
    val driftDistancePx: Float = 64f,
) {
    /** Computed runtime switch; no backing field and therefore no serialized schema word. */
    val isActive: Boolean get() = hueRangeDegrees != 0f || saturationRange != 0f ||
        valueRange != 0f || secondaryMix != 0f || pressureHueDegrees != 0f
}

/** Frozen at contact. Alpha belongs to the existing stroke commit, not color dynamics. */
data class StrokeColors(val primaryArgb: Int, val secondaryArgb: Int)

/** Straight sRGB, each channel finite and in 0..1. */
data class ColorDab(val r: Float, val g: Float, val b: Float)

/** Same byte-domain Rec.709 conversion as the canvas mask adapter, after colour dynamics. */
fun ColorDab.toMaskGray(): ColorDab {
    require(r in 0f..1f && g in 0f..1f && b in 0f..1f) { "Mask ink needs finite RGB in 0..1" }
    val red = (r * 255f + .5f).toInt().coerceIn(0,255)
    val green = (g * 255f + .5f).toInt().coerceIn(0,255)
    val blue = (b * 255f + .5f).toInt().coerceIn(0,255)
    val y = (.2126f * red + .7152f * green + .0722f * blue + .5f).toInt().coerceIn(0,255) / 255f
    return ColorDab(y,y,y)
}

/**
 * One generator per stroke, called once per placed dab before scatter. Its independent SplitMix
 * stream never consumes the legacy size/angle/random stream or Scatter's stream. Native event
 * batches and render zoom are absent from its inputs. Changing a palette preserves every random
 * operand, so recolor can replay from the original seed rather than editing previously mixed RGB.
 *
 * The four fixed draws are hue, saturation, value, secondary mix, in that order. Per-stroke keeps
 * those operands; pressure hue is applied afterwards in every mode. Mix primary toward secondary
 * in straight sRGB first, then apply HSV displacements. Achromatic hue is canonically zero.
 *
 * Drift is bounded smoothstep interpolation between independently seeded distance knots, not an
 * accumulating walk. It is continuous with zero derivative at knots, needs constant work after an
 * arbitrary jump, and repeats after 2^32 knots (274,877,906,944 doc px at the default pitch). The
 * explicit period permits deterministic indexing even for distances beyond Long's integer range.
 *
 * Model validation belongs to BrushValidate at integration. Safe local fallbacks below keep a
 * hand-built invalid value from producing NaN pixels. This class is stateful only for perDab.
 */
class ColorDynamics(spec: ColorDynamicsSpec, seed: Long, colors: StrokeColors) {
    private val mode = spec.mode
    private val hueRange = finiteRange(spec.hueRangeDegrees, 0f, 180f)
    private val saturationRange = finiteRange(spec.saturationRange, 0f, 1f)
    private val valueRange = finiteRange(spec.valueRange, 0f, 1f)
    private val secondaryMix = finiteRange(spec.secondaryMix, 0f, 1f)
    private val pressureHue = finiteRange(spec.pressureHueDegrees, -180f, 180f)
    private val driftPitch = spec.driftDistancePx.takeIf { it.isFinite() && it > 0f } ?: 64f
    private val primary = rgb(colors.primaryArgb)
    private val secondary = rgb(colors.secondaryArgb)
    private val colorSeed = seed xor COLOR_SALT
    private val dabStream = SplitMix(colorSeed)
    private val stroke = operands(SplitMix(colorSeed))

    fun at(distancePx: Float, pressure: Float): ColorDab {
        val draw = when (mode) {
            ColorMode.perDab -> operands(dabStream)
            ColorMode.perStroke -> stroke
            ColorMode.perStrokeDrift -> drift(distancePx)
        }
        val mix = draw.mix * secondaryMix
        val base = if (mix == 0f) primary else ColorDab(
            primary.r + (secondary.r - primary.r) * mix,
            primary.g + (secondary.g - primary.g) * mix,
            primary.b + (secondary.b - primary.b) * mix,
        )
        val p = if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 0f
        val dh = draw.hue * hueRange + p * pressureHue
        val ds = draw.saturation * saturationRange
        val dv = draw.value * valueRange
        // Preserve primary and a pure RGB mix exactly: an HSV round trip is unnecessary here.
        if (dh == 0f && ds == 0f && dv == 0f) return base
        return shifted(base, dh, ds, dv)
    }

    private fun drift(distancePx: Float): Operands {
        val distance = if (distancePx.isFinite() && distancePx > 0f) distancePx.toDouble() else 0.0
        val position = distance / driftPitch.toDouble()
        val cell = floor(position)
        val index = (cell % KNOT_PERIOD).toLong()
        val t = position - cell
        val s = (t * t * (3.0 - 2.0 * t)).toFloat()
        val a = knot(index)
        val b = knot((index + 1L) and KNOT_MASK)
        return Operands(
            a.hue + (b.hue - a.hue) * s,
            a.saturation + (b.saturation - a.saturation) * s,
            a.value + (b.value - a.value) * s,
            a.mix + (b.mix - a.mix) * s,
        )
    }

    private fun knot(index: Long): Operands = operands(SplitMix(colorSeed + index * KNOT_STEP))

    private data class Operands(val hue: Float, val saturation: Float, val value: Float, val mix: Float)

    companion object {
        /** ASCII "JB_COLOR"; versioned replay identity, independent of all existing brush salts. */
        const val COLOR_SALT: Long = 0x4A425F434F4C4F52L
        // Deliberately not SplitMix's own state increment: adjacent knots must not reuse three
        // of the previous knot's four color draws as different channels.
        private const val KNOT_STEP: Long = 0x632BE59BD9B4E019L
        private const val KNOT_MASK: Long = 0xFFFF_FFFFL
        private const val KNOT_PERIOD: Double = 4294967296.0

        private fun operands(rng: SplitMix): Operands = Operands(
            2f * rng.nextFloat() - 1f,
            2f * rng.nextFloat() - 1f,
            2f * rng.nextFloat() - 1f,
            rng.nextFloat(),
        )

        private fun finiteRange(v: Float, lo: Float, hi: Float): Float =
            if (v.isFinite()) v.coerceIn(lo, hi) else 0f

        private fun rgb(argb: Int): ColorDab = ColorDab(
            ((argb ushr 16) and 255) / 255f,
            ((argb ushr 8) and 255) / 255f,
            (argb and 255) / 255f,
        )

        private fun shifted(c: ColorDab, dh: Float, ds: Float, dv: Float): ColorDab {
            val r = c.r.toDouble(); val g = c.g.toDouble(); val b = c.b.toDouble()
            val top = max(r, max(g, b)); val low = min(r, min(g, b))
            val delta = top - low
            val hue = when {
                delta == 0.0 -> 0.0
                top == r -> 60.0 * ((g - b) / delta)
                top == g -> 60.0 * ((b - r) / delta + 2.0)
                else -> 60.0 * ((r - g) / delta + 4.0)
            }
            val h = ((hue + dh.toDouble()) % 360.0 + 360.0) % 360.0
            val saturation = ((if (top == 0.0) 0.0 else delta / top) + ds).coerceIn(0.0, 1.0)
            val value = (top + dv).coerceIn(0.0, 1.0)
            val chroma = value * saturation
            val sector = h / 60.0
            val x = chroma * (1.0 - kotlin.math.abs(sector % 2.0 - 1.0))
            val m = value - chroma
            val out = when (floor(sector).toInt()) {
                0 -> ColorDab((chroma + m).toFloat(), (x + m).toFloat(), m.toFloat())
                1 -> ColorDab((x + m).toFloat(), (chroma + m).toFloat(), m.toFloat())
                2 -> ColorDab(m.toFloat(), (chroma + m).toFloat(), (x + m).toFloat())
                3 -> ColorDab(m.toFloat(), (x + m).toFloat(), (chroma + m).toFloat())
                4 -> ColorDab((x + m).toFloat(), m.toFloat(), (chroma + m).toFloat())
                else -> ColorDab((chroma + m).toFloat(), m.toFloat(), (x + m).toFloat())
            }
            return ColorDab(out.r.coerceIn(0f, 1f), out.g.coerceIn(0f, 1f), out.b.coerceIn(0f, 1f))
        }
    }
}
