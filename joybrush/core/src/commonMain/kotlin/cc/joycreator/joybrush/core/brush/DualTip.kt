package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.paint.TipMath
import cc.joycreator.joybrush.core.paint.TipShape
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Unibrush v17 secondary coverage operations; numeric codes are replay ABI. */
@Serializable enum class DualMode(val code: Int) { multiply(1), subtract(2), min(3) }

/** Exactly one frozen PNG; deliberately no picker, nested tip, dynamics, or recursive dual field. */
@Serializable data class DualImageSpec(
    val image: String,
    val coverage: SampleCoverage = SampleCoverage.alpha,
    val invert: Boolean = false,
) {
    fun sampledRecipe() = SampledTipSpec(listOf(image), coverage = coverage, invert = invert)
}

@Serializable data class DualTipSpec(
    /** Exactly one of shape/image. Shape profile is always hard; no inherited A/B morph. */
    val shape: QuadShapeSpec? = QuadShapeSpec(),
    val image: DualImageSpec? = null,
    /** Secondary radius / primary radius, before the main tip's aspect/taper deformation. */
    val scale: Float = .3f,
    /** Lattice pitch / secondary DIAMETER; .5 means one secondary radius between centers. */
    val spacing: Float = .75f,
    /** Independent symmetric x/y center displacement as a fraction of lattice pitch. */
    val scatter: Float = .35f,
    val mode: DualMode = DualMode.multiply,
)

data class DualBakeLimits(
    /** Owned bake/output pixels; already-resolved image source memory stays under D5's limits. */
    val maxPixels: Long = 256L * 256 + 128L * 128,
    val maxCandidates: Long = 16384,
    val maxSampleWork: Long = 16_000_000,
)

/** Immutable periodic R8 mask: all dabs in a stroke share this object and one GPU upload. */
class DualMask internal constructor(
    private val recipe: DualTipSpec,
    private val seed: Long,
    val period: Float,
    val mode: DualMode,
    private val pixels: ByteArray,
    val stats: Stats,
) {
    data class Stats(val cellsPerAxis: Int, val candidateCount: Int, val sampleWork: Long)
    val width: Int get() = DualTips.BAKE_SIDE
    val height: Int get() = DualTips.BAKE_SIDE
    fun bytes(): ByteArray = pixels.copyOf()
    fun matches(spec: DualTipSpec, strokeSeed: Long): Boolean = recipe == spec && seed == strokeSeed
    private fun pixel(x: Int, y: Int): Float {
        val xx = ((x % width) + width) % width
        val yy = ((y % height) + height) % height
        return (pixels[yy * width + xx].toInt() and 255) / 255f
    }

    /** Same REPEAT + LINEAR convention as normalized texture sampling, including the seam. */
    fun sample(qx: Float, qy: Float): Float {
        if (!qx.isFinite() || !qy.isFinite()) return 0f
        fun texel(q: Float): Double {
            var phase = (q.toDouble() / period + .5) % 1.0
            if (phase < 0) phase += 1.0
            return phase * width - .5
        }
        val x = texel(qx); val y = texel(qy)
        val ix = floor(x).toInt(); val iy = floor(y).toInt()
        val fx = (x - ix).toFloat(); val fy = (y - iy).toFloat()
        val a = pixel(ix, iy); val b = pixel(ix + 1, iy)
        val c = pixel(ix, iy + 1); val d = pixel(ix + 1, iy + 1)
        val top = a + (b - a) * fx
        return top + ((c + (d - c) * fx) - top) * fy
    }

    fun apply(main: Float, qx: Float, qy: Float): Float = DualTips.combine(main, sample(qx, qy), mode)
}

object DualTips {
    // These exact quality/seed constants must be frozen by a brush version when landed.
    const val BAKE_SIDE = 256
    private const val SOURCE_SIDE = 128
    private const val SOURCE_RADIUS = 48f
    private const val MAX_CELLS_PER_AXIS = 64
    private const val SEED_SALT = 0x4455414c4c415454L
    private const val INDEX_STEP = -0x61c8864680b583ebL

    fun problems(spec: DualTipSpec): List<String> = buildList {
        if ((spec.shape == null) == (spec.image == null)) add("dual tip needs exactly one shape or image")
        if (!spec.scale.isFinite() || spec.scale !in .05f..2f) add("dual scale must be .05..2")
        if (!spec.spacing.isFinite() || spec.spacing !in .125f..4f) add("dual spacing must be .125..4 diameters")
        if (!spec.scatter.isFinite() || spec.scatter !in 0f..1f) add("dual scatter must be 0..1 lattice pitches")
        spec.image?.let { if (!SampledTips.safePath(it.image)) add("dual image needs a safe relative PNG path") }
        spec.shape?.let { shape ->
            if (shape.points.size != 4 || shape.points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in -1f..1f || it.y !in -1f..1f })
                add("dual shape needs four finite points in -1..1")
            if (!shape.roundness.isFinite() || shape.roundness !in 0f..1f) add("dual roundness must be 0..1")
            if (shape.aspect != null && (!shape.aspect.isFinite() || shape.aspect !in -1f..1f)) add("dual aspect must be -1..1")
            if (shape.profile != null) add("dual secondary shapes use their fixed hard profile")
        }
        val pitch = 2.0 * spec.scale * spec.spacing
        if (pitch.isFinite() && pitch > 0 && ceil(2.0 / pitch) > MAX_CELLS_PER_AXIS)
            add("dual scale/spacing needs more than $MAX_CELLS_PER_AXIS lattice cells per axis")
    }

    /** No mode expands the primary coverage, so the primary's existing dab bounds remain valid. */
    fun combine(main: Float, secondary: Float, mode: DualMode): Float {
        val a = if (main.isFinite()) main.coerceIn(0f, 1f) else 0f
        val b = if (secondary.isFinite()) secondary.coerceIn(0f, 1f) else 0f
        return when (mode) {
            DualMode.multiply -> a * b
            DualMode.subtract -> max(a - b, 0f)
            DualMode.min -> min(a, b)
        }
    }

    private data class Source(val mask: ImageMask, val span: Float)
    private data class Candidate(val cx: Float, val cy: Float, val x0: Int, val x1: Int, val y0: Int, val y1: Int)

    /**
     * Resolved set must match the ONE frozen image and its alpha/luminance/invert recipe exactly.
     * No image loads, random-stream consumption, or GPU calls occur during dab placement.
     * Complete candidate/work bounds are checked before allocating the output R8 image.
     */
    fun bake(spec: DualTipSpec, seed: Long, resolved: SampledTipSet? = null,
             limits: DualBakeLimits = DualBakeLimits()): DualMask {
        val errors = problems(spec)
        if (errors.isNotEmpty()) throw BrushException(errors.joinToString("; "))
        val sourceWork = if (spec.shape != null) SOURCE_SIDE.toLong() * SOURCE_SIDE else 0L
        if (limits.maxPixels < BAKE_SIDE.toLong() * BAKE_SIDE + sourceWork || limits.maxCandidates <= 0 ||
            limits.maxSampleWork <= sourceWork)
            throw BrushException("dual tip bake exceeds its pixel/candidate/work allowance")
        val source = if (spec.image != null) {
            val set = resolved ?: throw BrushException("dual image mask was not resolved from the frozen brush")
            if (!set.matches(spec.image.sampledRecipe())) throw BrushException("dual image mask does not match its frozen recipe")
            Source(set.mask(0), 1f)
        } else {
            if (resolved != null) throw BrushException("a procedural dual shape must not receive an unrelated image mask")
            shapeSource(checkNotNull(spec.shape))
        }
        val cells = max(1, ceil(2.0 / (2.0 * spec.scale * spec.spacing)).toInt())
        val period = (cells * (2.0 * spec.scale * spec.spacing)).toFloat()
        val pitch = period / cells
        val texel = period / BAKE_SIDE
        // Include a conservative float-arithmetic guard, not merely the geometric edge.
        val guard = max(1f, period) * 1e-5f
        val halfX = spec.scale * source.span * source.mask.supportHalfWidth + guard
        val halfY = spec.scale * source.span * source.mask.supportHalfHeight + guard
        val candidates = ArrayList<Candidate>()
        var work = sourceWork
        // Every base cell contributes ALL of its periodic copies intersecting the tile.
        // No arbitrary +/-neighbor stencil or truncated candidate list can omit a wide stamp.
        for (j in 0 until cells) for (i in 0 until cells) {
            val random = SplitMix(seed xor SEED_SALT xor ((j * cells + i).toLong() * INDEX_STEP))
            val cx = (i + .5f + (2f * random.nextFloat() - 1f) * spec.scatter) * pitch
            val cy = (j + .5f + (2f * random.nextFloat() - 1f) * spec.scatter) * pitch
            val shiftX0 = ceil((-halfX - cx).toDouble() / period).toInt()
            val shiftX1 = floor((period + halfX - cx).toDouble() / period).toInt()
            val shiftY0 = ceil((-halfY - cy).toDouble() / period).toInt()
            val shiftY1 = floor((period + halfY - cy).toDouble() / period).toInt()
            for (sy in shiftY0..shiftY1) for (sx in shiftX0..shiftX1) {
                val x = cx + sx * period; val y = cy + sy * period
                val x0 = max(0, ceil((x - halfX).toDouble() / texel - .5).toInt())
                val x1 = min(BAKE_SIDE - 1, floor((x + halfX).toDouble() / texel - .5).toInt())
                val y0 = max(0, ceil((y - halfY).toDouble() / texel - .5).toInt())
                val y1 = min(BAKE_SIDE - 1, floor((y + halfY).toDouble() / texel - .5).toInt())
                if (x1 < x0 || y1 < y0) continue
                if (candidates.size.toLong() >= limits.maxCandidates) throw BrushException("dual tip exceeds its complete candidate allowance")
                work += (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
                if (work > limits.maxSampleWork) throw BrushException("dual tip exceeds its complete sampling-work allowance")
                candidates += Candidate(x, y, x0, x1, y0, y1)
            }
        }
        val bytes = ByteArray(BAKE_SIDE * BAKE_SIDE)
        val scale = spec.scale * source.span
        for (candidate in candidates) for (y in candidate.y0..candidate.y1) for (x in candidate.x0..candidate.x1) {
            val qx = ((x + .5f) * texel - candidate.cx) / scale
            val qy = ((y + .5f) * texel - candidate.cy) / scale
            val value = (source.mask.sample(qx, qy) * 255f).roundToInt().coerceIn(0, 255)
            val index = y * BAKE_SIDE + x
            // Max-union: multiple secondary stamps make one mask, never repeated flow/opacity.
            if (value > (bytes[index].toInt() and 255)) bytes[index] = value.toByte()
        }
        return DualMask(spec.copy(shape = spec.shape?.let { it.copy(points = it.points.toList()) }),
            seed, period, spec.mode, bytes, DualMask.Stats(cells, candidates.size, work))
    }

    private fun shapeSource(shape: QuadShapeSpec): Source {
        val quad = TipQuads.interpolate(shape, null, 0f)
        val tip = TipShape(aspect = shape.aspect ?: 0f, hardness = 1f, minPx = .5f,
            profile = TipProfileSpec(TipProfile.hard), quad = quad)
        val bytes = ByteArray(SOURCE_SIDE * SOURCE_SIDE)
        for (y in 0 until SOURCE_SIDE) for (x in 0 until SOURCE_SIDE) {
            val coverage = TipMath.coverage(x + .5f - SOURCE_SIDE * .5f, y + .5f - SOURCE_SIDE * .5f,
                SOURCE_RADIUS, 0f, tip)
            bytes[y * SOURCE_SIDE + x] = (coverage * 255f).roundToInt().coerceIn(0, 255).toByte()
        }
        return Source(ImageMask.fromR8(SOURCE_SIDE, SOURCE_SIDE, bytes), SOURCE_SIDE * .5f / SOURCE_RADIUS)
    }
}
