package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_TUFT
import cc.joycreator.joybrush.core.brush.TuftSpec
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * The tuft brush's sliders (R9, "Tune this brush…"): one per behaviour the owner described, in the order a person meets
 * them — the line first, then the ink, then the accidents. The screen draws whatever is in [all]; adding a knob is a line
 * here, and nothing on the screen changes.
 *
 * Every knob works in 0..1 of its own slider. [get] and [set] map that onto the [TuftSpec] number, which is 0..1 too
 * except for the needle point, whose slider covers 0.3–6 px.
 */
object TuftKnobs {

    class Knob(
        /** The field's name in brush.json. */
        val key: String,
        /** What the slider says. */
        val label: String,
        /** The hover label: what moving it does, in the person's words. */
        val hint: String,
        /** 0..1 slider position for this spec. */
        val get: (TuftSpec) -> Float,
        /** The spec with this slider at a 0..1 position. */
        val set: (TuftSpec, Float) -> TuftSpec,
        /** How the value reads beside the slider. */
        val show: (TuftSpec) -> String = { s -> "${(get(s) * 100).roundToInt()}%" },
    )

    private const val TIP_MIN = 0.3f
    private const val TIP_MAX = 6f

    private fun knob(key: String, label: String, hint: String, get: (TuftSpec) -> Float, set: (TuftSpec, Float) -> TuftSpec) =
        Knob(key, label, hint, get, set)

    val all: List<Knob> = listOf(
        Knob("tipPx", "Hairline", "How fine the needle point is. It stays this fine however big the brush is.",
            get = { ((it.tipPx - TIP_MIN) / (TIP_MAX - TIP_MIN)).coerceIn(0f, 1f) },
            set = { s, v -> s.copy(tipPx = TIP_MIN + (TIP_MAX - TIP_MIN) * v) },
            show = { s -> "${(s.tipPx * 10).roundToInt() / 10f} px" }),
        knob("shelf", "Light touch", "How much of the light end of the pressure stays a hairline before the belly opens.",
            { it.shelf }, { s, v -> s.copy(shelf = v) }),
        knob("steady", "Steadiness", "The brush smooths your hand's wobble across the line into a calm drift. The line never lags behind the pen.",
            { it.steady }, { s, v -> s.copy(steady = v) }),
        knob("trail", "Trail", "How long the bristles trail when you move with a little pressure: thin but long, calligraphic. Also how far a quick lift carries on.",
            { it.trail }, { s, v -> s.copy(trail = v) }),
        knob("snap", "Spring", "How quickly the bristles swing round to a new direction. High is a stiff sable, low is a soft, floppy brush.",
            { it.snap }, { s, v -> s.copy(snap = v) }),
        knob("corner", "Turn blot", "The thick spot and broken bristles at a sharp turn while the bristles settle.",
            { it.corner }, { s, v -> s.copy(corner = v) }),
        knob("settle", "Slow settle", "Slow lines get a little thicker and more solid, as the ink has time to settle.",
            { it.settle }, { s, v -> s.copy(settle = v) }),
        knob("speedThin", "Fast thinning", "How much a fast stroke thins out.",
            { it.speedThin }, { s, v -> s.copy(speedThin = v) }),
        knob("ink", "Ink load", "How much ink the brush holds. More ink means longer strokes before it runs dry. Every stroke starts full.",
            { it.ink }, { s, v -> s.copy(ink = v) }),
        knob("dry", "Dry brush", "How readily fast, heavy strokes break into dry-brush streaks.",
            { it.dry }, { s, v -> s.copy(dry = v) }),
        knob("sweep", "Sweep", "On fast curves the ink is thrown to the inside, and the outside runs dry and broken.",
            { it.sweep }, { s, v -> s.copy(sweep = v) }),
        knob("splay", "Splay", "How far the bristles spread on jolts and quick lifts, for split, rough ends.",
            { it.splay }, { s, v -> s.copy(splay = v) }),
        knob("bristles", "Bristles", "How fine the dry streaks are. Low gives a few coarse clumps, high gives many fine hairs.",
            { it.bristles }, { s, v -> s.copy(bristles = v) }),
        knob("tooth", "Paper tooth", "How much the paper's grain breaks up the dry parts. A loaded brush fills the grain either way.",
            { it.tooth }, { s, v -> s.copy(tooth = v) }),
        knob("spatter", "Spatter", "Ink thrown off by quick flicks, sudden hard presses and anything jolty.",
            { it.spatter }, { s, v -> s.copy(spatter = v) }),
        knob("strays", "Stray hairs", "A broken hair or two that only shows when you paint with the belly: a thin line beside the stroke that comes and goes.",
            { it.strays }, { s, v -> s.copy(strays = v) }),
        knob("tilt", "Tilt spread", "Laying the pen over spreads the belly wider. Only on pens that report tilt.",
            { it.tilt }, { s, v -> s.copy(tilt = v) }),
    )
}

/**
 * The owner's tuning, kept per brush id (R9): what the sliders say, laid over the shipped brush file whenever that brush
 * is picked. The file itself is never rewritten, so "Put this brush back" is simply forgetting the entry.
 */
object TuftTuning {

    private val FORMAT = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val SERIALIZER = MapSerializer(String.serializer(), TuftSpec.serializer())

    /** [p] with its tuning applied, or [p] itself when it is not a tuft brush or has not been tuned. */
    fun apply(p: BrushPreset, tuning: Map<String, TuftSpec>): BrushPreset {
        if (p.engine != ENGINE_TUFT) return p
        val spec = tuning[p.id] ?: return p
        return p.copy(tuft = spec)
    }

    fun encode(tuning: Map<String, TuftSpec>): String = FORMAT.encodeToString(SERIALIZER, tuning)

    /** The saved tuning, or none when there is nothing saved or it cannot be read — a broken entry never costs the brush. */
    fun decode(text: String?): Map<String, TuftSpec> {
        if (text.isNullOrBlank()) return emptyMap()
        return try {
            FORMAT.decodeFromString(SERIALIZER, text)
        } catch (e: IllegalArgumentException) {
            emptyMap()
        }
    }
}
