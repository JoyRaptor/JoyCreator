package cc.joycreator.joybrush.core.paint

/**
 * One stamp of the brush tip. Positions and sizes are document pixels (1 document px = 1 tile texel).
 *
 * @property flow how much this dab adds to the stroke (0..1).
 * @property cap the ceiling the stroke may build to: the brush opacity in WASH mode, 1 in BUILD_UP
 *   mode (where opacity is applied once, when the stroke is committed). See [Accumulate].
 */
data class Dab(
    val x: Float,
    val y: Float,
    val radius: Float,
    val angle: Float = 0f,
    val flow: Float = 1f,
    val cap: Float = 1f,
    val pressure: Float = 1f,
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

/** What a committed stroke does to its layer. */
enum class StrokeBlend { NORMAL, ERASE }

/** The tip shape, mirroring `JbTip` in `joybrush/shaders/jb_tip.glsl` (radius and angle live on each [Dab]). */
data class TipShape(
    val aspect: Float = 0f,
    val corner: Float = 2f,
    val taper: Float = 0f,
    val hardness: Float = 0.9f,
    val minPx: Float = 1f,
)
