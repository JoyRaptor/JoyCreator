package cc.joycreator.joybrush.core.grain

import cc.joycreator.joybrush.core.brush.GrainSpec
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.PaperResponse
import cc.joycreator.joybrush.core.paper.HexTile
import cc.joycreator.joybrush.core.paper.PaperTexture
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * CPU twin of `joybrush/shaders/jb_grain.glsl` + `jb_grain_sample.glsl` (JB-1.05c).
 * Any change to the shader's maths is made here too, in the same commit (the rule `TipMath` follows).
 *
 * THE NaN ROW (Decision 1, the gate both JB-1.02 reviewers set): a finger reports `tilt = NaN` and
 * `azimuth = NaN` by `PenSample`'s own contract, and `0 * NaN = NaN` — so one NaN among the tilt
 * numbers would poison the whole dab's grain level and the shipped pencil would draw nothing on a
 * finger. GLSL leaves `clamp`/`min`/`max` on a NaN undefined, so the shader can not clean up after a
 * bad input: the bad input never arrives. [tiltAmount], [leanX] and [leanY] are the guard, and the
 * values that reach the shader as uniforms are the guarded ones.
 */
object GrainMath {

    /** A pen lying flat on the glass: tilt is `π/2`. */
    const val TILT_FLAT = 1.5707964f

    /**
     * Document px per repeat of a grain texture when its `scale` is 1 (Decision 6, provisional —
     * LEAD_RULINGS R38): `pitchPx = GRAIN_UNIT_PX / scale`. A fixed PHYSICAL size, so a bigger brush
     * shows more clumps rather than the same few clumps blown up.
     */
    const val GRAIN_UNIT_PX = 64f

    /** The file names a grain may load from the packaged assets: a plain name, never a path. */
    private val SAFE_NAME = Regex("[A-Za-z0-9_.-]{1,64}\\.png")

    /** The asset a grain with no image of its own uses (Decision 7). */
    const val DEFAULT_CLOUD = "cloud_256.png"

    /** JB-9.03: one physical paper surface for every brush (R10 P4). */
    const val DEFAULT_SURFACE = "surface_pulp_artisan.png"
    const val SURFACE_SIZE = 512f
    const val DEFAULT_SURFACE_TEXEL_PX = 2f
    const val SURFACE_TEXEL_PX = DEFAULT_SURFACE_TEXEL_PX
    const val SURFACE_SLOPE_RANGE = 0.099f
    const val SURFACE_HEX_TEXELS = 180f
    const val SURFACE_ROTATABLE = true

    /** Paper image and scale belong to the document surface, never the brush's legacy grain entry. */
    fun paperUniformsFor(spec: GrainSpec, depthAtFirstDab: Float): GrainUniforms =
        if (!spec.enabled) GrainUniforms.OFF else GrainUniforms(
            DEFAULT_SURFACE_TEXEL_PX, unit(depthAtFirstDab, unit(spec.depth.base, 1f)),
            if (spec.edge.isFinite()) spec.edge.coerceIn(0f, 1f) else 0.3f,
            if (spec.tiltGradient.isFinite()) spec.tiltGradient else 0f,
            if (spec.radial.isFinite()) spec.radial else 0f, DEFAULT_SURFACE,
        )

    const val JB_DIR_GAIN = 0.35f
    const val JB_WET_GAIN = 1f
    const val UNIVERSAL_DEPTH = 0.85f
    const val UNIVERSAL_EDGE = 0.3f
    const val UNIVERSAL_TILT_GRADIENT = 0f
    const val UNIVERSAL_RADIAL = 0f

    /** JB-9.08: slopes remain height per doc px; do not normalise them as normals. */
    fun paperEffectiveHeight(surf: FloatArray, hCoarse: Float, travelX: Float, travelY: Float,
                             slopeRangeDocPx: Float, directional: Float, wet: Float): Float {
        require(surf.size >= 4)
        val face = ((surf[0] * travelX + surf[1] * travelY) / max(slopeRangeDocPx, 1e-6f)).coerceIn(-1f, 1f)
        val hDry = surf[2] + JB_DIR_GAIN * directional * face
        val hWet = surf[2] + JB_WET_GAIN * (hCoarse - surf[2])
        return hDry + (hWet - hDry) * wet
    }

    /** CPU surroundings: mean of hex-read B over an 8x8 texel box; GPU coarse-mip tolerance ±0.03. */
    fun paperCoarseHeight(tex: PaperTexture, texelX: Double, texelY: Double, hexTexels: Double,
                          rotatable: Boolean, slopeRange: Float, seed: Int = 0): Float {
        val sampled = FloatArray(4)
        var sum = 0f
        for (y in 0..7) for (x in 0..7) {
            HexTile.sampleSurface(tex, texelX + x - 3.5, texelY + y - 3.5, hexTexels, rotatable, slopeRange, sampled, seed)
            sum += sampled[2]
        }
        return sum / 64f
    }

    /** Universal response gives previously ungrained brushes physical paper without changing their tip. */
    fun strokeUniformsFor(preset: BrushPreset, tipDepth: Float = preset.tipTexture.depth.base,
                          paperDepth: Float = preset.paperGrain.depth.base): StrokeGrain {
        val paper = if (preset.paperGrain.enabled) paperUniformsFor(preset.paperGrain, paperDepth)
            else if (preset.paper.influence > 0f) GrainUniforms(DEFAULT_SURFACE_TEXEL_PX, UNIVERSAL_DEPTH,
                UNIVERSAL_EDGE, UNIVERSAL_TILT_GRADIENT, UNIVERSAL_RADIAL, DEFAULT_SURFACE)
            else GrainUniforms.OFF
        return StrokeGrain(uniformsFor(preset.tipTexture, tipDepth), paper, preset.paper)
    }

    // ---- the NaN row --------------------------------------------------------------------------

    /** `sin(tilt)`; a channel that is not a finite number means "no tilt sensor", which is upright. */
    fun tiltAmount(tiltRad: Float): Float =
        if (tiltRad.isFinite()) sin(tiltRad.coerceIn(0f, TILT_FLAT)) else 0f

    /** `cos(azimuth)`; no azimuth means NO lean, which is (0, 0). */
    fun leanX(azimuthRad: Float): Float = if (azimuthRad.isFinite()) cos(azimuthRad) else 0f

    /** `sin(azimuth)`, with the same NaN row as [leanX]. */
    fun leanY(azimuthRad: Float): Float = if (azimuthRad.isFinite()) sin(azimuthRad) else 0f

    /** A file number that is not finite reads as [fallback]; a finite one is clamped to 0..1. */
    fun unit(v: Float, fallback: Float): Float = if (v.isFinite()) v.coerceIn(0f, 1f) else fallback

    // ---- jb_grain.glsl, transcribed -----------------------------------------------------------

    /** `jb_heightCoverage`. `edge` is floored at 1e-3, exactly as the shader does. */
    fun heightCoverage(height: Float, level: Float, edge: Float): Float {
        val w = max(edge, 1e-3f)
        val threshold = 1f - level
        return ((height - threshold) / w + 0.5f).coerceIn(0f, 1f)
    }

    /**
     * `jb_grainLevel`, with [localNx]/[localNy] already in the tip's frame (offset rotated by `-angle`,
     * divided by the radius) and the lean already rotated into the same frame.
     */
    fun grainLevel(
        depth: Float, tipCov: Float,
        localNx: Float, localNy: Float,
        leanX: Float, leanY: Float,
        tiltAmount: Float, tiltGradient: Float, radial: Float,
    ): Float {
        val plane = tiltGradient * tiltAmount * (localNx * leanX + localNy * leanY)
        val dome = radial * (localNx * localNx + localNy * localNy)
        return (depth * tipCov + plane - dome).coerceIn(0f, 1f)
    }

    /** `jb_grainedCoverage`: grain decides WHERE paint lands; the tip's own edge still bounds it. */
    fun grainedCoverage(tipCov: Float, grainCov: Float): Float = grainCov * smoothstep(0f, 0.06f, tipCov)

    /**
     * Two grains combine by MINIMUM (Decision 5): commutative and associative, so there is no order
     * to get wrong. An OFF grain contributes exactly 1.
     */
    fun combine(tipGrain: Float, paperGrain: Float): Float = min(tipGrain, paperGrain)

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ---- jb_grain_sample.glsl, transcribed ----------------------------------------------------

    /**
     * Texture coordinates for the TIP texture: the offset from the dab centre, rotated into the tip's
     * own frame (`-angle`, the identical rotation `jb_tipCoverage` uses), in repeats of [pitchPx], so
     * the texture turns with the brush. Writes two floats into [out] (size >= 2).
     */
    fun tipGrainUv(offsetPxX: Float, offsetPxY: Float, angle: Float, pitchPx: Float, out: FloatArray) {
        require(out.size >= 2) { "tipGrainUv needs an output array of at least 2 floats, got ${out.size}" }
        val c = cos(angle)
        val s = sin(angle)
        val qx = c * offsetPxX + s * offsetPxY
        val qy = -s * offsetPxX + c * offsetPxY
        val p = max(pitchPx, 1e-3f)
        out[0] = qx / p + 0.5f
        out[1] = qy / p + 0.5f
    }

    /**
     * Texture coordinates for the PAPER grain: the fragment's document position in repeats of
     * [pitchPx]. No rotation and no offset, ever — the same document point is the same texel whatever
     * the brush is doing, which is the whole point of the second texture. Negative coordinates give
     * negative coordinates (what `GL_REPEAT` wants). Writes two floats into [out] (size >= 2).
     */
    fun paperGrainUv(docX: Float, docY: Float, pitchPx: Float, out: FloatArray) {
        require(out.size >= 2) { "paperGrainUv needs an output array of at least 2 floats, got ${out.size}" }
        val p = max(pitchPx, 1e-3f)
        out[0] = docX / p
        out[1] = docY / p
    }

    // ---- from a brush file to the numbers the shader takes -------------------------------------

    /** Pitch in document px for a `scale`; refuses a scale that is not a positive finite number. */
    fun pitchPxFor(scale: Float): Float {
        require(scale.isFinite() && scale > 0f) { "grain scale must be a positive finite number, was $scale" }
        return GRAIN_UNIT_PX / scale
    }

    /** 0 when the grain is off (the shader reads a pitch of 0 as "this grain is OFF" and never samples it into the result). */
    fun pitchPxFor(spec: GrainSpec): Float = if (spec.enabled) pitchPxFor(spec.scale) else 0f

    /** The packaged asset a grain loads, or null if the file names something that is not a plain PNG name. */
    fun assetNameFor(spec: GrainSpec): String? {
        val name = spec.image?.takeIf { it.isNotBlank() } ?: DEFAULT_CLOUD
        return if (SAFE_NAME.matches(name) && !name.contains("..")) name else null
    }

    /** Everything the shader takes for ONE grain of a stroke. Depth is per-stroke (Decision 10). */
    data class GrainUniforms(
        val pitchPx: Float, val depth: Float, val edge: Float, val tiltGradient: Float, val radial: Float,
        val asset: String,
    ) {
        val enabled: Boolean get() = pitchPx > 0f
        companion object {
            val OFF = GrainUniforms(0f, 0f, 0.3f, 0f, 0f, DEFAULT_CLOUD)
        }
    }

    /** The two grains of a stroke. */
    data class StrokeGrain(val tip: GrainUniforms, val paper: GrainUniforms, val paperResponse: PaperResponse = PaperResponse()) {
        val any: Boolean get() = tip.enabled || paper.enabled
    }

    /**
     * Builds one grain's uniforms from its file entry and the depth evaluated at the stroke's FIRST dab.
     * A depth that is not finite reads as the grain's own `depth.base`, then 1 (Decision 1); `edge`,
     * `tiltGradient` and `radial` are validated file numbers, but a value that is not finite is turned
     * into a harmless default rather than reaching the GPU.
     */
    fun uniformsFor(spec: GrainSpec, depthAtFirstDab: Float): GrainUniforms {
        val pitch = try { pitchPxFor(spec) } catch (e: IllegalArgumentException) { 0f }
        val asset = assetNameFor(spec)
        if (pitch <= 0f || asset == null) return GrainUniforms.OFF
        return GrainUniforms(
            pitchPx = pitch,
            depth = unit(depthAtFirstDab, unit(spec.depth.base, 1f)),
            edge = if (spec.edge.isFinite()) spec.edge.coerceIn(0f, 1f) else 0.3f,
            tiltGradient = if (spec.tiltGradient.isFinite()) spec.tiltGradient else 0f,
            radial = if (spec.radial.isFinite()) spec.radial else 0f,
            asset = asset,
        )
    }

    /** Rotates (x, y) by `-angle` — into the tip's frame. Writes two floats into [out]. */
    fun intoTipFrame(x: Float, y: Float, angle: Float, out: FloatArray) {
        val c = cos(angle)
        val s = sin(angle)
        out[0] = c * x + s * y
        out[1] = -s * x + c * y
    }
}
