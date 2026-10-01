package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.brush.BrushInput
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE
import cc.joycreator.joybrush.core.brush.ENGINE_TUFT
import cc.joycreator.joybrush.core.brush.InputCurve
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.TuftSpec
import cc.joycreator.joybrush.core.brush.VERSION_RESPONSE
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Every brush's advanced settings (owner, 2026-09-30: "long-pressing a brush in the drawer should always bring up advanced
 * settings, so the user always has that power"). One slider per thing the brush can do, chosen by its engine. The screen
 * draws whatever [forBrush] returns; a new knob is an entry here, and nothing on the screen changes.
 *
 * Every knob is a 0..1 slider position. [Knob.get] and [Knob.set] map that onto the brush's own number, so a saved
 * tuning is a list of slider positions ([BrushTuning]) laid over the shipped file, never a rewritten file.
 */
object BrushKnobs {

    class Knob(
        /** Stable name for the saved tuning: `tuft.dry`, `stamp.hardness`, … */
        val key: String,
        /** What the slider says. */
        val label: String,
        /** The hover label: what moving it does, in the person's words. */
        val hint: String,
        /** 0..1 slider position for this brush. */
        val get: (BrushPreset) -> Float,
        /** The brush with this slider at a 0..1 position. */
        val set: (BrushPreset, Float) -> BrushPreset,
        /** How the value reads beside the slider. */
        val show: (BrushPreset) -> String,
        /** False for a number a curve editor owns (the response handles): saved and applied like a slider, drawn as a curve. */
        val slider: Boolean = true,
        /** An on/off setting: drawn as a checkbox, kept as 0 or 1. */
        val toggle: Boolean = false,
    )

    /** The knobs for [p], in the order a person meets them: the line first, then the ink, then the accidents. */
    fun forBrush(p: BrushPreset): List<Knob> = when (p.engine) {
        ENGINE_TUFT -> TUFT + SMOOTHING
        ENGINE_SMUDGE -> SMUDGE + STAMP + SMOOTHING
        ENGINE_FILL -> SMOOTHING
        else -> STAMP + (if (p.paperGrain.enabled) listOf(GRAIN) else emptyList()) + SMOOTHING
    } + RESPONSE

    /** The keys of a response curve's four handle numbers, `response.<curve>.0` … `.3` ([x1, y1, x2, y2]). */
    fun curveKeys(curve: String): List<String> = (0 until 4).map { "response.$curve.$it" }

    private fun handle(curve: String, i: Int) = Knob("response.$curve.$i", "$curve curve", "The $curve curve's handle",
        get = { p -> (if (curve == "pressure") p.response.pressure else p.response.tilt).getOrElse(i) { 0.5f }.coerceIn(0f, 1f) },
        set = { p, v ->
            val r = p.response
            val out = if (curve == "pressure") p.copy(response = r.copy(pressure = r.pressure.toMutableList().also { if (i < it.size) it[i] = v }))
            else p.copy(response = r.copy(tilt = r.tilt.toMutableList().also { if (i < it.size) it[i] = v }))
            // A bent curve is a version-5 word: the tuned brush says so, as its file would.
            if (!out.response.isDefault && out.version < VERSION_RESPONSE) out.copy(version = VERSION_RESPONSE) else out
        },
        show = { "" }, slider = false)

    private val RESPONSE: List<Knob> = (0 until 4).map { handle("pressure", it) } + (0 until 4).map { handle("tilt", it) }

    private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

    private fun knob(key: String, label: String, hint: String, get: (BrushPreset) -> Float, set: (BrushPreset, Float) -> BrushPreset) =
        Knob(key, label, hint, get, set, show = { p -> pct(get(p)) })

    // ── the tuft brush (R9 §3A, O1–O13) ──

    private const val TIP_MIN = 0.3f
    private const val TIP_MAX = 6f

    private fun tuft(key: String, label: String, hint: String, get: (TuftSpec) -> Float, set: (TuftSpec, Float) -> TuftSpec) =
        knob("tuft.$key", label, hint, { p -> get(p.tuft) }, { p, v -> p.copy(tuft = set(p.tuft, v)) })

    private val TUFT: List<Knob> = listOf(
        Knob("tuft.tipPx", "Hairline", "How fine the needle point is. It stays this fine however big the brush is.",
            get = { ((it.tuft.tipPx - TIP_MIN) / (TIP_MAX - TIP_MIN)).coerceIn(0f, 1f) },
            set = { p, v -> p.copy(tuft = p.tuft.copy(tipPx = TIP_MIN + (TIP_MAX - TIP_MIN) * v)) },
            show = { p -> "${(p.tuft.tipPx * 10).roundToInt() / 10f} px" }),
        tuft("shelf", "Light touch", "How much of the light end of the pressure stays a hairline before the belly opens.",
            { it.shelf }, { s, v -> s.copy(shelf = v) }),
        tuft("flatten", "Press flat", "Past line weight, pressing down spreads the bristles wide for shadows. Higher is wider.",
            { it.flatten }, { s, v -> s.copy(flatten = v) }),
        tuft("tilt", "Tilt spread", "Laying the pen over multiplies the width and stretches the mark on the diagonal.",
            { it.tilt }, { s, v -> s.copy(tilt = v) }),
        tuft("graze", "Graze", "A laid-over pen pressed lightly: how wispy and scratchy the far side of the brush is, for shading. Pressed hard it is black.",
            { it.graze }, { s, v -> s.copy(graze = v) }),
        Knob("tuft.grazeAtPoint", "Graze at the point", "Checked: the point end of a laid-over brush grazes and the far end stays solid. Unchecked: the other way round.",
            get = { p -> if (p.tuft.grazeAtPoint) 1f else 0f },
            set = { p, v -> p.copy(tuft = p.tuft.copy(grazeAtPoint = v >= 0.5f)) },
            show = { p -> if (p.tuft.grazeAtPoint) "on" else "off" }, toggle = true),
        tuft("steady", "Steadiness", "The brush smooths your hand's wobble across the line into a calm drift. The line never lags behind the pen.",
            { it.steady }, { s, v -> s.copy(steady = v) }),
        tuft("trail", "Trail", "How long the bristles trail when you move with a little pressure: thin but long, calligraphic. Also how far a quick lift carries on.",
            { it.trail }, { s, v -> s.copy(trail = v) }),
        tuft("snap", "Spring", "How quickly the bristles swing round to a new direction. High is a stiff sable, low is a soft, floppy brush.",
            { it.snap }, { s, v -> s.copy(snap = v) }),
        tuft("corner", "Turn blot", "The thick spot and broken bristles at a sharp turn while the bristles settle.",
            { it.corner }, { s, v -> s.copy(corner = v) }),
        tuft("settle", "Slow settle", "Slow lines get a little thicker and more solid, as the ink has time to settle.",
            { it.settle }, { s, v -> s.copy(settle = v) }),
        tuft("speedThin", "Fast thinning", "How much a fast stroke thins out.",
            { it.speedThin }, { s, v -> s.copy(speedThin = v) }),
        tuft("ink", "Ink load", "How much ink the brush holds. More ink means longer strokes before it runs dry. Every stroke starts full.",
            { it.ink }, { s, v -> s.copy(ink = v) }),
        tuft("dry", "Dry brush", "How readily fast, heavy strokes break into dry-brush streaks.",
            { it.dry }, { s, v -> s.copy(dry = v) }),
        tuft("action", "Bristle action", "Bristle marks even when the brush is loaded: broken edges and streaks, strongest at the sides.",
            { it.action }, { s, v -> s.copy(action = v) }),
        tuft("sweep", "Sweep", "On fast curves the ink is thrown to the inside, and the outside runs dry and broken.",
            { it.sweep }, { s, v -> s.copy(sweep = v) }),
        tuft("splay", "Splay", "How far the bristles spread on jolts and quick lifts, for split, rough ends.",
            { it.splay }, { s, v -> s.copy(splay = v) }),
        tuft("bristles", "Bristles", "How fine the dry streaks are. Low gives a few coarse clumps, high gives many fine hairs.",
            { it.bristles }, { s, v -> s.copy(bristles = v) }),
        tuft("tooth", "Paper tooth", "How much the paper's grain breaks up the dry parts. A loaded brush fills the grain either way.",
            { it.tooth }, { s, v -> s.copy(tooth = v) }),
        tuft("spatter", "Spatter", "Ink thrown off by quick flicks, sudden hard presses and anything jolty.",
            { it.spatter }, { s, v -> s.copy(spatter = v) }),
        tuft("strays", "Stray hairs", "Broken hairs beside the stroke, only when you paint with the belly: some long, some short, some stuttering on the paper.",
            { it.strays }, { s, v -> s.copy(strays = v) }),
    )

    // ── stamp brushes (ink, pencil, marker, airbrush, eraser) ──

    /** The first point of [input]'s curve on [param], or null when the setting does not follow it. */
    private fun firstY(param: Param, input: BrushInput): Float? =
        param.inputs.firstOrNull { it.input == input }?.curve?.firstOrNull()?.getOrNull(1)

    /** [param] with [input]'s curve starting at [y] (added as a straight ramp to 1 when there was none). */
    private fun withFirstY(param: Param, input: BrushInput, y: Float): Param {
        val i = param.inputs.indexOfFirst { it.input == input }
        if (i < 0) return param.copy(inputs = param.inputs + InputCurve(input, listOf(listOf(0f, y), listOf(1f, 1f))))
        val c = param.inputs[i]
        val curve = c.curve.toMutableList()
        curve[0] = listOf(curve[0][0], y)
        return param.copy(inputs = param.inputs.toMutableList().also { it[i] = c.copy(curve = curve) })
    }

    private const val SPACING_MIN = 0.01f
    private const val SPACING_MAX = 0.3f

    private val STAMP: List<Knob> = listOf(
        knob("stamp.thinnest", "Thinnest", "How thin the lightest pressure draws, as a share of the full size. 100% ignores pressure.",
            { p -> (firstY(p.size, BrushInput.pressure) ?: 1f).coerceIn(0f, 1f) },
            { p, v -> p.copy(size = withFirstY(p.size, BrushInput.pressure, v)) }),
        knob("stamp.opacityPressure", "Pressure fades", "How much a light touch fades the colour. 0% is always full strength.",
            { p -> (1f - (firstY(p.opacity, BrushInput.pressure) ?: 1f)).coerceIn(0f, 1f) },
            { p, v -> p.copy(opacity = withFirstY(p.opacity, BrushInput.pressure, 1f - v)) }),
        knob("stamp.hardness", "Hardness", "How crisp the edge is. Low is soft and feathered.",
            { p -> p.tip.hardness.base.coerceIn(0f, 1f) }, { p, v -> p.copy(tip = p.tip.copy(hardness = p.tip.hardness.copy(base = v))) }),
        knob("stamp.flow", "Flow", "How much each touch of the brush lays down. Low builds up gently.",
            { p -> p.flow.base.coerceIn(0f, 1f) }, { p, v -> p.copy(flow = p.flow.copy(base = v)) }),
        Knob("stamp.spacing", "Spacing", "How far apart the brush's dabs are. Wide spacing shows beads along the line.",
            get = { p -> ((p.spacing - SPACING_MIN) / (SPACING_MAX - SPACING_MIN)).coerceIn(0f, 1f) },
            set = { p, v -> p.copy(spacing = SPACING_MIN + (SPACING_MAX - SPACING_MIN) * v) },
            show = { p -> "${(p.spacing * 100).roundToInt()}%" }),
        knob("stamp.sizeJitter", "Size jitter", "Each dab a random amount bigger or smaller.",
            { p -> p.sizeJitter.coerceIn(0f, 1f) }, { p, v -> p.copy(sizeJitter = v) }),
        Knob("stamp.angleJitter", "Angle jitter", "Each dab turned a random amount. Shows on shaped tips.",
            get = { p -> (p.angleJitter / 360f).coerceIn(0f, 1f) }, set = { p, v -> p.copy(angleJitter = v * 360f) },
            show = { p -> "${p.angleJitter.roundToInt()}°" }),
    )

    private val GRAIN = knob("stamp.grain", "Paper grain", "How much the paper's grain shows through the stroke.",
        { p -> p.paperGrain.depth.base.coerceIn(0f, 1f) },
        { p, v -> p.copy(paperGrain = p.paperGrain.copy(depth = p.paperGrain.depth.copy(base = v))) })

    private val SMUDGE: List<Knob> = listOf(
        knob("smudge.pickup", "Pick up", "How quickly the smudge takes on the colour it drags over.",
            { p -> p.smudge.pickup }, { p, v -> p.copy(smudge = p.smudge.copy(pickup = v)) }),
        knob("smudge.load", "Own colour", "How much of the brush's own colour the smudge mixes in.",
            { p -> p.smudge.load }, { p, v -> p.copy(smudge = p.smudge.copy(load = v)) }),
    )

    private val SMOOTHING: List<Knob> = listOf(
        knob("smoothing", "Smoothing", "How much this brush smooths your line. The menu's Smoothing, once you move it, overrides this for every brush.",
            { p -> p.smoothing.coerceIn(0f, 1f) }, { p, v -> p.copy(smoothing = v) }),
    )
}

/**
 * The owner's slider positions, kept per brush id: `{brushId: {knobKey: 0..1}}`. Laid over the shipped brush whenever it is
 * picked, so the file is never rewritten, a later version of the brush keeps the tuning, and Reset is forgetting the entry.
 */
object BrushTuning {

    private val FORMAT = Json { ignoreUnknownKeys = true }
    private val SERIALIZER = MapSerializer(String.serializer(), MapSerializer(String.serializer(), Float.serializer()))
    private val LEGACY = MapSerializer(String.serializer(), TuftSpec.serializer())

    /** [p] with its saved slider positions applied. Positions for knobs this brush does not have are ignored. */
    fun apply(p: BrushPreset, tuning: Map<String, Map<String, Float>>): BrushPreset {
        val mine = tuning[p.id] ?: return p
        var out = p
        for (k in BrushKnobs.forBrush(p)) {
            val v = mine[k.key] ?: continue
            if (v.isFinite()) out = k.set(out, v.coerceIn(0f, 1f))
        }
        return out
    }

    fun encode(tuning: Map<String, Map<String, Float>>): String = FORMAT.encodeToString(SERIALIZER, tuning)

    /** The saved tuning, or none when nothing is saved or it cannot be read — a broken entry never costs the brush. */
    fun decode(text: String?): Map<String, Map<String, Float>> {
        if (text.isNullOrBlank()) return emptyMap()
        return try {
            FORMAT.decodeFromString(SERIALIZER, text)
        } catch (e: IllegalArgumentException) {
            emptyMap()
        }
    }

    /**
     * The first tuning format (whole tuft settings per brush) as slider positions, so a brush tuned before the settings
     * sheet grew to every brush keeps its tuning. Brushes not in [library] are dropped.
     */
    fun fromLegacyTuft(text: String?, library: List<BrushPreset>): Map<String, Map<String, Float>> {
        if (text.isNullOrBlank()) return emptyMap()
        val old = try {
            FORMAT.decodeFromString(LEGACY, text)
        } catch (e: IllegalArgumentException) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, Map<String, Float>>()
        for ((id, spec) in old) {
            val base = library.firstOrNull { it.id == id && it.engine == ENGINE_TUFT } ?: continue
            val tuned = base.copy(tuft = spec)
            out[id] = BrushKnobs.forBrush(tuned).filter { it.key.startsWith("tuft.") }.associate { it.key to it.get(tuned) }
        }
        return out
    }
}
