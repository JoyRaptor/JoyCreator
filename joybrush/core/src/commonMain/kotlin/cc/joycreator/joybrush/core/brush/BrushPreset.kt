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
 *
 * The two words JB-1.08a added — `engine: "fill"` and `blend: "behind"` — are not enum constants, so
 * no enum freeze applies to them; what they needed instead was [BRUSH_VERSION] itself, because a file
 * that uses one of them cannot honestly claim to be a version-1 file. That is the bump R3 asks for,
 * made in the same edit as this default.
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
    /** Optional live shape; absent keeps the scalar aspect (brush v7). */
    val aspectDynamics: Param? = null,
    /** 0 = centred; 1 = positive-X endpoint at the pen, long side trailing (brush v7). */
    val anchorDynamics: Param? = null,
)

/** Paper grain image/scale are ignored since JB-9.03: the document paper decides (R10 P4).
 * Tip textures still use their own image and scale. */
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

/**
 * The two rates of a smudge brush (JB-1.06, brush version 3). How hard the smudge presses is the brush's
 * own `flow` — pressure already drives it — so there is no `strength` here.
 *
 * Blueprint §5 / R8: ONE carried colour per brush, mixing toward the canvas AND toward the chosen colour
 * in one dab — never separate reservoir and pickup stores. [pickup] is how fast the carried colour
 * forgets what it has passed over and takes up the canvas; [load] is how fast it takes up the brush's
 * own chosen colour. Both `0..1`.
 * `texturePickup` (version 7) mixes spatial pre-stroke paint into each output pixel without another
 * stored colour; `paint` allows deposition onto empty canvas. Both default to legacy smudge behavior.
 */
@Serializable data class SmudgeSpec(val pickup: Float = 0.5f, val load: Float = 0.15f,
    val texturePickup: Float = 0f, val paint: Boolean = false)

/** How far a push dab moves the pixels under it: a fraction of the tip's radius, along the stroke. `0<a<=1`. */
@Serializable data class PushSpec(val amount: Float = 0.3f)

/**
 * How this brush relates to the document's paper (JB-9.09, owner P4: the paper is universal). Three
 * numbers, each `0..1`, read by the paper engine in JB-9.08 — until then they are stored, validated and
 * tunable, with no visible effect, which is what the knob hints say out loud.
 *
 * The default is 0/0/0 on purpose, and it is the load-bearing decision of the row: **a brush file that
 * says nothing must mean exactly what it always meant.** A pencil saved before this row keeps every
 * mark it ever made, and a build that predates the section reads a file that carries one as a brush
 * with no paper in it — which is why a non-default section needs [VERSION_PAPER] and a default one does
 * not (see `BrushJson.wordsNeedingVersion`).
 *
 * @property influence 0 = ignores the document paper, 1 = feels it fully.
 * @property directional 0 = deposit ignores stroke direction, 1 = dry paint fully catches the side of
 *   each bump that faces the stroke (R10 §5).
 * @property wet 0 = dry, riding the peaks; 1 = wet, pooling in the valleys.
 */
@Serializable data class PaperResponse(
    val influence: Float = 0f,
    val directional: Float = 0f,
    val wet: Float = 0f,
) {
    /** True at 0/0/0, so the section costs no version to say nothing (the LOWEST-version rule). */
    val isDefault: Boolean get() = influence == 0f && directional == 0f && wet == 0f
}

@Serializable data class ScatterSpec(val amount: Param = Param(0f), val count: Int = 1, val countJitter: Float = 0f, val bothAxes: Boolean = true)

@Serializable data class ColorJitter(val hue: Float = 0f, val saturation: Float = 0f, val value: Float = 0f, val perStroke: Boolean = false)

@Serializable data class BrushPreset(
    val format: String = "joybrush.brush",
    val version: Int = BRUSH_VERSION,
    val id: String,
    val name: String,
    val engine: String = "stamp",        // "stamp" | "smudge" | "push" (JB-1.06) | "wet" | "fill" (the fill pen, JB-1.08a) | "tuft" (R9) | "media" (v8)
    val tip: TipSpec = TipSpec(),
    val size: Param,                     // diameter in px
    val opacity: Param = Param(1f),      // ceiling for the whole stroke
    val flow: Param = Param(1f),         // per dab
    val spacing: Float = 0.04f,          // fraction of diameter
    val tipTexture: GrainSpec = GrainSpec(),   // dab space: turns with the tip
    val paperGrain: GrainSpec = GrainSpec(),   // canvas space: stays put
    val scatter: ScatterSpec = ScatterSpec(),
    val smudge: SmudgeSpec = SmudgeSpec(),     // read only when engine == "smudge" (version 3)
    val push: PushSpec = PushSpec(),           // read only when engine == "push" (version 3)
    val tuft: TuftSpec = TuftSpec(),           // read only when engine == "tuft" (version 4, R9 §3B)
    val media: MediaSpec? = null,              // only and always when engine == "media" (version 8, MEDIA_ENGINE_PLAN §4)
    val response: ResponseSpec = ResponseSpec(), // pressure and tilt curves, every engine (version 5 when not straight)
    val paper: PaperResponse = PaperResponse(), // how this brush feels the document paper (version 6 when not default, R10, JB-9.09)
    val sizeJitter: Float = 0f,
    val angleJitter: Float = 0f,         // degrees
    val color: ColorJitter = ColorJitter(),
    val accumulate: String = "wash",     // "wash" (never darker than opacity) | "buildup"
    val blend: String = "normal",        // "normal" | "erase" | "behind" (fill under line art, JB-1.08a)
    val smoothing: Float = 0.3f,
    val license: String = "CC0",
    val author: String = "",
    val sourceFormat: String = "native", // "native" | "abr" | "procreate" | "myb" | "kpp"
    val extensions: Map<String, String> = emptyMap(), // lossless storage of imported raw settings
)
