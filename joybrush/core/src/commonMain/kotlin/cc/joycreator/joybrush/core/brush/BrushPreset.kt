package cc.joycreator.joybrush.core.brush

import kotlinx.serialization.Serializable

/**
 * The dynamic inputs a brush may respond to, read in declaration order. The constant NAME is the
 * whole of what lands in `brush.json` (`"input": "pressure"`) — brush.json stores no ordinals, so
 * a reorder here is harmless but a rename is not.
 *
 * SERIALISED: new constants are APPEND-ONLY and require bumping DOC_VERSION (or the brush "version"). See LEAD_RULINGS R3.
 *
 * On this side of the codebase the bump is [BRUSH_VERSION] — together with [BrushPreset.version]'s
 * default, which is a second literal and has to move in the same edit. `DOC_VERSION` is a
 * `document.json` number and has nothing to do with brush.json.
 *
 * Why it matters here more than most: an input a build does not have is refused by name.
 * [BrushJson.decode] throws [BrushException] rather than falling back to some other input, and the
 * fallback would not be a small mistake — a brush that asked for `lean` and silently got `tilt`
 * draws differently, everywhere, forever. [BrushValidate.validate] is what tells the person their
 * brush is "from a newer Joy Brush", and that sentence only appears if the version was bumped, so
 * bump it in the same change as the new constant.
 */
@Serializable enum class BrushInput { pressure, tilt, speed, direction, lean, attack, distance, random, strokeRandom, barrel }
@Serializable data class InputCurve(val input: BrushInput, val curve: List<List<Float>>) // [[x,y],…]
@Serializable data class Param(val base: Float, val inputs: List<InputCurve> = emptyList(), val combine: String = "multiply")

@Serializable data class TipSpec(
    val source: String = "procedural",   // "procedural" | "image"
    val image: String? = null,           // path inside the brush folder when source == image
    val corner: Float = 2f,              // superellipse exponent (jb_tip.glsl)
    val taper: Float = 0f,
    val aspect: Float = 0f,
    val angle: Param = Param(0f),        // degrees
    val followDirection: Boolean = false,// tip turns with DirectionTracker's output
    val hardness: Param = Param(0.9f),
    val minPx: Float = 1f,
)

@Serializable data class GrainSpec(
    val enabled: Boolean = false,
    val source: String = "cloud",        // "cloud" | "image"
    val image: String? = null,
    val scale: Float = 1f,
    val depth: Param = Param(1f),        // jb_grainLevel depth
    val edge: Float = 0.3f,              // jb_heightCoverage edge
    val tiltGradient: Float = 0f,
    val radial: Float = 0f,
)

@Serializable data class ScatterSpec(val amount: Param = Param(0f), val count: Int = 1, val countJitter: Float = 0f, val bothAxes: Boolean = true)

@Serializable data class ColorJitter(val hue: Float = 0f, val saturation: Float = 0f, val value: Float = 0f, val perStroke: Boolean = false)

@Serializable data class BrushPreset(
    val format: String = "joybrush.brush",
    val version: Int = 1,
    val id: String,
    val name: String,
    val engine: String = "stamp",        // "stamp" | "smudge" | "wet" (later phases)
    val tip: TipSpec = TipSpec(),
    val size: Param,                     // diameter in px
    val opacity: Param = Param(1f),      // ceiling for the whole stroke
    val flow: Param = Param(1f),         // per dab
    val spacing: Float = 0.04f,          // fraction of diameter
    val tipTexture: GrainSpec = GrainSpec(),   // dab space: turns with the tip
    val paperGrain: GrainSpec = GrainSpec(),   // canvas space: stays put
    val scatter: ScatterSpec = ScatterSpec(),
    val sizeJitter: Float = 0f,
    val angleJitter: Float = 0f,         // degrees
    val color: ColorJitter = ColorJitter(),
    val accumulate: String = "wash",     // "wash" (never darker than opacity) | "buildup"
    val blend: String = "normal",        // "normal" | "erase"
    val smoothing: Float = 0.3f,
    val license: String = "CC0",
    val author: String = "",
    val sourceFormat: String = "native", // "native" | "abr" | "procreate" | "myb" | "kpp"
    val extensions: Map<String, String> = emptyMap(), // lossless storage of imported raw settings
)
