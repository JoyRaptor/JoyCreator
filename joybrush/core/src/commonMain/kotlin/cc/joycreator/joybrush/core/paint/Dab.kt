package cc.joycreator.joybrush.core.paint

/**
 * One stamp of the brush tip. Positions and sizes are document pixels (1 document px = 1 tile texel).
 *
 * @property flow how much this dab adds to the stroke (0..1).
 * @property cap the ceiling the stroke may build to: the brush opacity in WASH mode, 1 in BUILD_UP
 *   mode (where opacity is applied once, when the stroke is committed). See [Accumulate].
 * @property tilt the pen's tilt at this dab, radians, or NaN when the pen has no tilt sensor (a finger).
 *   Kotlin-side only: it is NOT in the GPU instance buffer, so JB-0.07's reviewed layout is untouched —
 *   the engine reads the newest dab of a batch to set the two per-batch grain uniforms (JB-1.05c D10).
 * @property azimuth the direction the pen leans, radians, or NaN. Same rule as [tilt].
 */
data class Dab(
    val x: Float,
    val y: Float,
    val radius: Float,
    val angle: Float = 0f,
    val flow: Float = 1f,
    val cap: Float = 1f,
    val pressure: Float = 1f,
    val tilt: Float = Float.NaN,
    val azimuth: Float = Float.NaN,
    val travelX: Float = 0f,
    val travelY: Float = 0f,
    val travelKnown: Boolean = false, // CPU only: true also distinguishes intentional first/dwell zero.
    /** Live contact overrides; NaN selects the stroke uniform for old recordings. */
    val aspect: Float = Float.NaN,
    val hardness: Float = Float.NaN,
    val tipDepth: Float = Float.NaN,
    val paperDepth: Float = Float.NaN,
    val anchor: Float = Float.NaN,
    /** Watercolour CPU fields, uploaded by its separate pass; the legacy packed dab ABI is unchanged. */
    val sampleIndex: Int = -1,
    val band0: Float = 0f,
    val band1: Float = 0f,
    val band2: Float = 0f,
    val bandWeight0: Float = 0f,
    val bandWeight1: Float = 0f,
    val bandWeight2: Float = 0f,
)

/**
 * How dabs within ONE stroke combine (JB-0.07, blueprint §1 idea 6).
 *
 * Both modes use the same maths per dab — `s' = cap·d + s·(1 − d)`, where d = tip coverage × flow —
 * which is ordinary premultiplied "over" blending, so the GPU does it with fixed-function blending,
 * batched, with no read-back. The only difference is WHERE opacity goes:
 *
 * - [WASH]: cap = opacity. The stroke approaches the opacity and never passes it, so ink and marker
 *   strokes do not go darker where they cross themselves (Krita's "alpha darken" behaviour).
 * - [BUILD_UP]: cap = 1; opacity multiplies the whole stroke on commit. Overlaps inside the stroke
 *   build up, like an airbrush.
 */
enum class Accumulate { WASH, BUILD_UP }

/**
 * What a committed stroke does to its layer. [MULTIPLY] and [SCREEN] (brush version 9) are the W3C separable modes
 * applied to the WHOLE stroke buffer once (JB-5.20 D8a), so a stroke never darkens itself, only what lay under it.
 */
enum class StrokeBlend { NORMAL, ERASE, MULTIPLY, SCREEN }

/** The tip shape, mirroring `JbTip` in `joybrush/shaders/jb_tip.glsl` (radius and angle live on each [Dab]). */
data class TipShape(
    val aspect: Float = 0f,
    val corner: Float = 2f,
    val taper: Float = 0f,
    val hardness: Float = 0.9f,
    val minPx: Float = 1f,
    val anchor: Float = 0f,
)

/** Resolve live contact values; missing channels use the stroke shape. */
fun Dab.tipShape(fallback: TipShape): TipShape = fallback.copy(
    aspect = if (aspect.isFinite()) aspect.coerceIn(-1f, 1f) else fallback.aspect,
    hardness = if (hardness.isFinite()) hardness.coerceIn(0f, 1f) else fallback.hardness,
    anchor = if (anchor.isFinite()) anchor.coerceIn(0f, 1f) else fallback.anchor,
)
