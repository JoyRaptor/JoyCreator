package cc.joycreator.joybrush.core.brush

import kotlinx.serialization.Serializable

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
