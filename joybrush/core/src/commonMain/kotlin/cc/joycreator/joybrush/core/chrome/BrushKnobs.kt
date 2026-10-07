package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.brush.BrushInput
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BELLY_MODES
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.ENGINE_MEDIA
import cc.joycreator.joybrush.core.brush.ENGINE_PUSH
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE
import cc.joycreator.joybrush.core.brush.ENGINE_TUFT
import cc.joycreator.joybrush.core.brush.InputCurve
import cc.joycreator.joybrush.core.brush.MediaSpec
import cc.joycreator.joybrush.core.brush.PaperResponse
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.TuftSpec
import cc.joycreator.joybrush.core.brush.VERSION_PAPER
import cc.joycreator.joybrush.core.brush.VERSION_RESPONSE
import cc.joycreator.joybrush.core.media.EdgeMode
import cc.joycreator.joybrush.core.media.FaceMode
import cc.joycreator.joybrush.core.media.PasteBrush
import cc.joycreator.joybrush.core.media.PressMode
import cc.joycreator.joybrush.core.media.Stick
import cc.joycreator.joybrush.core.media.THINNER
import cc.joycreator.joybrush.core.media.WETNESS
import cc.joycreator.joybrush.core.media.WetBrush
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

    /**
     * The knobs for [p], in the order a person meets them: the line first, then the ink, then the accidents.
     *
     * The three `paper` sliders go after the engine's own knobs and before Smoothing (JB-9.09 Decision 2),
     * and on every engine that LAYS PAINT. Smudge, push and fill do not: they drag paint that is already
     * there, move pixels, and fill a shape, so there is no deposit for the paper to hold or catch and a
     * slider there would be a control that cannot do anything. Push keeps the grain knob it always had.
     */
    const val PAPER_ENGINE_LIVE = true // JB-9.08 enables the three response controls.
    private val livePaper get() = if (PAPER_ENGINE_LIVE) PAPER else emptyList()

    fun forBrush(p: BrushPreset): List<Knob> = when (p.engine) {
        ENGINE_TUFT -> TUFT + livePaper + SMOOTHING
        ENGINE_SMUDGE -> SMUDGE + STAMP + SMOOTHING
        ENGINE_PUSH -> STAMP + (if (p.paperGrain.enabled) listOf(GRAIN) else emptyList()) + SMOOTHING
        ENGINE_FILL -> SMOOTHING
        // The media engine feels the paper through its own physics, so the three paper sliders would do nothing here.
        ENGINE_MEDIA -> media(p) + SMOOTHING
        else -> STAMP + (if (p.paperGrain.enabled) listOf(GRAIN) else emptyList()) + livePaper + SMOOTHING
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

    // ── media brushes (brush version 8, MEDIA_ENGINE_PLAN §4) ──
    //
    // Every number of the brush's tool gets a straight-line slider over a range wide enough to break it on purpose
    // (the owner tunes on the phone; the lab's values sit inside each range). The tapped states (water, thinner, belly,
    // and a blade's edge, pressure and face) are stepped sliders that snap to their named levels.

    private fun stepped(key: String, label: String, hint: String, names: List<String>,
                        get: (MediaSpec) -> Int, set: (MediaSpec, Int) -> MediaSpec) = Knob(key, label, hint,
        get = { p -> p.media?.let { get(it).coerceIn(0, names.lastIndex).toFloat() / names.lastIndex } ?: 0f },
        set = { p, v -> p.media?.let { p.copy(media = set(it, (v * names.lastIndex).roundToInt().coerceIn(0, names.lastIndex))) } ?: p },
        show = { p -> p.media?.let { names[get(it).coerceIn(0, names.lastIndex)] } ?: "" })

    private fun ranged(key: String, label: String, hint: String, lo: Double, hi: Double, unit: String,
                       get: (MediaSpec) -> Double?, set: (MediaSpec, Double) -> MediaSpec) = Knob(key, label, hint,
        get = { p -> p.media?.let(get)?.let { ((it - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) } ?: 0f },
        set = { p, v -> p.media?.let { p.copy(media = set(it, lo + (hi - lo) * v)) } ?: p },
        show = { p ->
            val x = p.media?.let(get) ?: 0.0
            if (unit == "%") pct(x.toFloat()) else "${(x * 100).roundToInt() / 100.0}$unit"
        })

    private fun stick(key: String, label: String, hint: String, lo: Double, hi: Double, unit: String,
                      get: (Stick) -> Double, set: (Stick, Double) -> Stick) =
        ranged("media.stick.$key", label, hint, lo, hi, unit, { m -> m.stick?.let(get) }, { m, v -> m.copy(stick = m.stick?.let { set(it, v) }) })

    private fun wet(key: String, label: String, hint: String, lo: Double, hi: Double, unit: String,
                    get: (WetBrush) -> Double, set: (WetBrush, Double) -> WetBrush) =
        ranged("media.wet.$key", label, hint, lo, hi, unit, { m -> m.wet?.let(get) }, { m, v -> m.copy(wet = m.wet?.let { set(it, v) }) })

    private fun paste(key: String, label: String, hint: String, lo: Double, hi: Double, unit: String,
                      get: (PasteBrush) -> Double, set: (PasteBrush, Double) -> PasteBrush) =
        ranged("media.paste.$key", label, hint, lo, hi, unit, { m -> m.paste?.let(get) }, { m, v -> m.copy(paste = m.paste?.let { set(it, v) }) })

    // rInf is how much light a full layer of lead still reflects: lower is darker, so Darkness runs the other way.
    private const val R_LIGHT = 0.3
    private const val R_DARK = 0.002
    private fun darkness(s: Stick) = ((R_LIGHT - s.rInf) / (R_LIGHT - R_DARK)).toFloat().coerceIn(0f, 1f)

    private val STICK: List<Knob> = listOf(
        stick("tipR", "Point", "How fine the point draws at the lightest touch.", 0.05, 1.0, " mm", { it.tipR }, { s, v -> s.copy(tipR = v) }),
        stick("tipMax", "Point pressed", "How wide the point spreads when you press hard.", 0.1, 3.0, " mm", { it.tipMax }, { s, v -> s.copy(tipMax = v) }),
        stick("side1", "Worn face", "How long the worn face is when you tilt the pencil halfway.", 0.5, 12.0, " mm", { it.side1 }, { s, v -> s.copy(side1 = v) }),
        stick("side2", "Side", "How far the side reaches when the pencil lies nearly flat.", 1.0, 30.0, " mm", { it.side2 }, { s, v -> s.copy(side2 = v) }),
        stick("face", "Face width", "How wide the side of the lead is.", 0.2, 4.0, " mm", { it.face }, { s, v -> s.copy(face = v) }),
        stick("soft", "Softness", "Soft leads lay more graphite and crumble into the paper. Hard leads stay pale and clean.", 0.0, 1.0, "%",
            { it.soft }, { s, v -> s.copy(soft = v) }),
        Knob("media.stick.rInf", "Darkness", "How dark the lead gets where it is laid on thick.",
            get = { p -> p.media?.stick?.let(::darkness) ?: 0f },
            set = { p, v -> p.media?.let { m -> p.copy(media = m.copy(stick = m.stick?.copy(rInf = R_LIGHT - (R_LIGHT - R_DARK) * v))) } ?: p },
            show = { p -> pct(p.media?.stick?.let(::darkness) ?: 0f) }),
    )

    private val WET: List<Knob> = listOf(
        stepped("media.wetness", "Water", "How much water the brush carries: dry, damp, wet, loaded or runny.", WETNESS.map { it.name },
            { it.wetness }, { m, i -> m.copy(wetness = i) }),
        wet("bellyMm", "Belly", "How wide the brush spreads when you press.", 1.0, 12.0, " mm", { it.bellyMm }, { w, v -> w.copy(bellyMm = v) }),
        wet("tipMm", "Tip", "How fine the tip is at the lightest touch.", 0.02, 3.0, " mm", { it.tipMm }, { w, v -> w.copy(tipMm = v) }),
        wet("load", "Pigment", "How much colour is in the water. Low is a pale glaze.", 0.0, 1.0, "%", { it.load }, { w, v -> w.copy(load = v) }),
        wet("waterPerMm", "Water out", "How quickly the brush gives up its water along the stroke.", 0.0, 0.3, "", { it.waterPerMm }, { w, v -> w.copy(waterPerMm = v) }),
        wet("capacityMm3", "Holds", "How much water the brush holds before it runs dry.", 20.0, 400.0, " mm³", { it.capacityMm3 }, { w, v -> w.copy(capacityMm3 = v) }),
        wet("gran", "Granulation", "How much pigment settles into the paper's dips.", 0.0, 2.0, "", { it.gran }, { w, v -> w.copy(gran = v) }),
        wet("stain", "Staining", "How much colour sinks in for good. A staining colour does not lift.", 0.0, 1.0, "%", { it.stain }, { w, v -> w.copy(stain = v) }),
        wet("beadMm", "Bead", "The pool of water the stroke pushes along in front of it.", 0.0, 6.0, " mm", { it.beadMm }, { w, v -> w.copy(beadMm = v) }),
        wet("liftMm", "Lift puddle", "The puddle a wet brush leaves where you lift it.", 0.0, 6.0, " mm", { it.liftMm }, { w, v -> w.copy(liftMm = v) }),
        wet("dwellMmPerS", "Pooling", "How fast water pools while the brush is held still.", 0.0, 4.0, " mm/s", { it.dwellMmPerS }, { w, v -> w.copy(dwellMmPerS = v) }),
    )

    /** The tapped states of a paste brush: thinner (not the clean scraper), the belly (hair only), a blade's modes. */
    private fun pasteState(m: MediaSpec): List<Knob> {
        val b = m.paste ?: return emptyList()
        val out = ArrayList<Knob>()
        if (!b.clean) out += stepped("media.thinner", "Thinner", "Thinner in the paint. Thinned and watery paint runs and drips.",
            THINNER.map { it.name }, { it.thinner }, { s, i -> s.copy(thinner = i) })
        if (m.isHair) out += stepped("media.belly", "Belly colour", "A second colour deeper in the brush, so a stroke shifts as it runs out.",
            BELLY_MODES, { BELLY_MODES.indexOf(it.belly).coerceAtLeast(0) }, { s, i -> s.copy(belly = BELLY_MODES[i]) })
        if (b.blade) {
            out += stepped("media.paste.orient", "Edge", "Where the edge lies: along the pen's lean, across the stroke, or along the stroke.",
                EdgeMode.entries.map { it.label }, { it.paste?.orient?.ordinal ?: 0 },
                { s, i -> s.copy(paste = s.paste?.copy(orient = EdgeMode.entries[i])) })
            if (!b.clean) out += stepped("media.paste.press", "Pressure", "What pressing harder does: lays more paint, or digs deeper.",
                PressMode.entries.map { it.id }, { (it.paste?.press ?: PressMode.LOAD).ordinal },
                { s, i -> s.copy(paste = s.paste?.copy(press = PressMode.entries[i])) })
            if (b.edgeHalfMm != null) out += stepped("media.paste.face", "Touches with", "The knife's thin edge, or its flat underside.",
                FaceMode.entries.map { it.id }, { (it.paste?.face ?: FaceMode.EDGE).ordinal },
                { s, i -> s.copy(paste = s.paste?.copy(face = FaceMode.entries[i])) })
        }
        return out
    }

    /** A paste tool's numbers: a blade's edge geometry, or a hair brush's or trowel's shape; then the paint it lays. */
    private fun pasteNumbers(b: PasteBrush): List<Knob> {
        val paint = listOf(
            paste("thickMm", "Body", "How thick a full brush lays the paint.", 0.0, 2.0, " mm", { it.thickMm }, { x, v -> x.copy(thickMm = v) }),
            paste("loadLenMm", "Paint load", "How far a full load goes before the brush starts to run dry.", 10.0, 300.0, " mm",
                { it.loadLenMm }, { x, v -> x.copy(loadLenMm = v) }),
            paste("mix", "Mixing", "How much the brush picks up the paint it passes over and mixes it in.", 0.0, 1.0, "%", { it.mix }, { x, v -> x.copy(mix = v) }),
            paste("lump", "Lumps", "Lumps and uneven body in the paint it lays.", 0.0, 1.0, "%", { it.lump }, { x, v -> x.copy(lump = v) }),
        )
        if (b.blade) return listOf(
            paste("bladeLenMm", "Blade length", "How much of the edge lies down when the pen lies flat.", 4.0, 40.0, " mm",
                { it.bladeLenMm }, { x, v -> x.copy(bladeLenMm = v) }),
            paste("edgeMinMm", "Edge upright", "How much of the edge touches with the pen upright.", 0.5, 10.0, " mm",
                { it.edgeMinMm }, { x, v -> x.copy(edgeMinMm = v) }),
            paste("acrossMaxMm", "Squeegee width", "How wide the edge reaches in the across-the-stroke mode.", 2.0, 30.0, " mm",
                { it.acrossMaxMm }, { x, v -> x.copy(acrossMaxMm = v) }),
            paste("riseMaxMm", "Taper", "How steeply the cut rises from the point with the pen upright. Laid flat it is always even.", 0.0, 2.0, " mm",
                { it.riseMaxMm ?: 0.0 }, { x, v -> x.copy(riseMaxMm = v) }),
            paste("bladeHmax", "Reach", "How high above the canvas the blade rides at its lightest.", 0.0, 2.0, " mm",
                { it.bladeHmax }, { x, v -> x.copy(bladeHmax = v) }),
            paste("bead", "Bead", "The ridge of paint the blade pushes up at its sides.", 0.0, 1.0, "%", { it.bead }, { x, v -> x.copy(bead = v) }),
        ) + if (b.clean) emptyList() else paint
        val hair = if (b.trowel) emptyList() else listOf(
            paste("hairDepth", "Bristle marks", "How deep the bristles comb grooves into the paint.", 0.0, 1.0, "%", { it.hairDepth }, { x, v -> x.copy(hairDepth = v) }),
            paste("scrape", "Scrape", "How much the brush scrapes paint aside rather than laying it.", 0.0, 1.0, "%", { it.scrape }, { x, v -> x.copy(scrape = v) }),
            paste("bow", "Bend", "How far the bristles bow under pressure.", 0.0, 1.0, "%", { it.bow }, { x, v -> x.copy(bow = v) }),
        )
        return listOf(
            paste("widthMm", "Width", "How wide the brush is.", 1.0, 20.0, " mm", { it.widthMm }, { x, v -> x.copy(widthMm = v) }),
            paste("lenMm", "Length", "How long the hairs or the knife are.", 1.0, 20.0, " mm", { it.lenMm }, { x, v -> x.copy(lenMm = v) }),
            paste("ridge", "Ridges", "Ridges of paint left at the stroke's edges.", 0.0, 1.0, "%", { it.ridge }, { x, v -> x.copy(ridge = v) }),
        ) + paint + hair
    }

    private fun media(p: BrushPreset): List<Knob> {
        val m = p.media ?: return emptyList()
        return when {
            m.stick != null -> STICK
            m.wet != null -> WET
            m.paste != null -> pasteState(m) + pasteNumbers(m.paste)
            else -> emptyList()
        }
    }

    private val SMOOTHING: List<Knob> = listOf(
        knob("smoothing", "Smoothing", "How much this brush smooths your line. The menu's Smoothing, once you move it, overrides this for every brush.",
            { p -> p.smoothing.coerceIn(0f, 1f) }, { p, v -> p.copy(smoothing = v) }),
    )

    // ── the paper response (JB-9.09, R10) ──
    //
    // Owner P4: the paper is universal, so every brush that lays paint feels it — at a strength that
    // suits it, which is why the shipped values differ per brush (Decision 4) rather than being one
    // global setting. Holding a brush shows these three with the live preview.


    private fun paper(
        field: String,
        label: String,
        hint: String,
        get: (PaperResponse) -> Float,
        set: (PaperResponse, Float) -> PaperResponse,
    ): Knob = Knob(
        "paper.$field", label, hint,
        get = { p -> get(p.paper).coerceIn(0f, 1f) },
        set = { p, v ->
            val out = p.copy(paper = set(p.paper, v))
            // A paper that is not 0/0/0 is a version-6 word, so the tuned brush says so — exactly as a
            // bent response curve above bumps itself to VERSION_RESPONSE, and for the same reason: a file
            // written back out must not claim a version that cannot express its own words.
            if (!out.paper.isDefault && out.version < VERSION_PAPER) out.copy(version = VERSION_PAPER) else out
        },
        show = { p -> pct(get(p.paper)) },
    )

    internal val PAPER: List<Knob> = listOf(
        paper("influence", "Paper", "How much this brush feels the paper's surface.",
            { it.influence }, { s, v -> s.copy(influence = v) }),
        paper("directional", "Direction", "Dry paint catches the side of each bump that faces the stroke.",
            { it.directional }, { s, v -> s.copy(directional = v) }),
        paper("wet", "Wet", "Dry rides the tops of the paper; wet sinks into the dips.",
            { it.wet }, { s, v -> s.copy(wet = v) }),
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
