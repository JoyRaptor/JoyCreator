package cc.joycreator.joybrush.core.brush

/**
 * Is this brush file usable? Every problem found is one readable line, so a screen can list them and
 * a person can fix the file. One message per rule, with everything that broke that rule inside it.
 *
 * Every number in a file is checked, because a file can come off a Wi-Fi hot-reload or out of
 * another person's brush pack. A range is written `v in lo..hi`, which fails NaN and ±Infinity as
 * well as the numbers outside it. A bound that *excludes* an endpoint — "above 0" — is written
 * `!(v > lo) || v > hi`, which fails the same three. (A `!(v > 0f)` test on its own does not: it
 * lets +Infinity through, which is how a `"base": 1e999` size once reached the dab loop and made it
 * step by Infinity forever.)
 */
object BrushValidate {

    private val ENGINES = setOf("stamp", ENGINE_SMUDGE, "wet", ENGINE_FILL, ENGINE_PUSH, ENGINE_TUFT)
    private val ACCUMULATES = setOf("wash", "buildup")
    private val BLENDS = setOf("normal", "erase", BLEND_BEHIND)
    private val COMBINES = setOf("multiply", "add")
    private val TIP_SOURCES = setOf("procedural", "image")
    private val GRAIN_SOURCES = setOf("cloud", "image")

    /** A dab bigger than this is not a big brush, it is a mistake. px. */
    /** The largest brush diameter, px. Public so every size control clamps to the SAME number. */
    const val MAX_SIZE_PX = 4096f

    /** Per [Param]. Each input is a curve evaluated on every dab of every stroke. */
    const val MAX_INPUTS = 8

    /** The widest needle point a tuft brush may have, px. */
    const val MAX_TUFT_TIP_PX = 64f

    /** The tuft sliders, by the name the file uses — every [TuftSpec] number except `tipPx`. */
    fun tuftSliders(t: TuftSpec): List<Pair<String, Float>> = listOf(
        "shelf" to t.shelf, "steady" to t.steady, "trail" to t.trail, "snap" to t.snap, "corner" to t.corner,
        "settle" to t.settle, "speedThin" to t.speedThin, "ink" to t.ink, "dry" to t.dry, "sweep" to t.sweep,
        "splay" to t.splay, "bristles" to t.bristles, "tooth" to t.tooth, "spatter" to t.spatter,
        "strays" to t.strays, "tilt" to t.tilt, "flatten" to t.flatten, "action" to t.action, "graze" to t.graze,
    )

    /** Per curve. */
    const val MAX_CURVE_POINTS = 64

    /** The grain texture's size in cells: above 0, and never so large the noise is one flat tone. */
    private const val MAX_GRAIN_SCALE = 64f

    /**
     * The [Param] bases a range rule below already speaks for, so a base that breaks *both* is named
     * once. It is a deny-list on purpose: a [Param] missing from [paramsOf] is not caught by this
     * check, it is caught by nothing — and a [Param] wrongly *left out* of this set only produces a
     * second message, which every `assertSole` in the tests would notice. The trap, therefore, is
     * [paramsOf], not this set.
     */
    private val RANGED_BASES = setOf("size", "tip.hardness")

    fun validate(p: BrushPreset): List<String> {
        val out = ArrayList<String>()

        // 1 — this build can read this file. One message: a file this old is a mistake, and a file
        // from the future is a mistake, and the format tag is a third mistake — but only the first
        // one is worth saying until it is fixed.
        if (p.version > BRUSH_VERSION) {
            out += "brush is from a newer Joy Brush (version ${p.version}, this build reads $BRUSH_VERSION)"
        } else if (p.version < 1) {
            out += "unknown brush version ${p.version}"
        } else if (p.format != BRUSH_FORMAT) {
            out += "format is \"${p.format}\", expected \"$BRUSH_FORMAT\""
        }

        // 1b — a word the file's own version cannot mean. A brush built in Kotlin (or saved by an
        // older Joy Brush and hand-edited) can say `engine: "fill"` under `"version": 1`; [BrushJson]
        // refuses that on the way IN, and this is the same sentence for a preset that never came from
        // a file. It is deliberately not the "from a newer Joy Brush" message above: this file is
        // older than the word it uses, and saying the opposite would send the person looking for a
        // build that does not exist.
        for (word in BrushJson.wordsNeedingVersion(p)) {
            if (p.version < word.minVersion) out += "${word.text} needs brush version ${word.minVersion}"
        }

        // 2 — a brush without an id cannot be saved, exported or replaced.
        if (p.id.isBlank()) out += "id is empty"

        // 3 — a brush with no size paints nothing. A brush with an infinite size is worse: the
        // evaluator is deliberately unclamped, so `size` evaluates to Infinity, the dab loop places
        // one dab and then steps by Infinity — one dot, and the rest of the stroke swallowed. Both
        // are refused here, and the message says which number it was.
        val size = p.size.base
        if (!(size > 0f) || size > MAX_SIZE_PX) {
            out += "size.base must be above 0 and at most ${MAX_SIZE_PX.toInt()}, is $size"
        }

        // 4 — spacing is a fraction of the diameter: too small is a million dabs, too big is dots.
        // Every range test is written `!in`, so a NaN is caught too: a NaN spacing would make the dab
        // loop walk off into never-land rather than paint a wrong pixel.
        if (p.spacing !in 0.005f..5f) out += "spacing ${p.spacing} is outside 0.005..5"

        // 5 — the superellipse exponent (jb_tip.glsl).
        if (p.tip.corner !in 0.5f..64f) out += "tip.corner ${p.tip.corner} is outside 0.5..64"

        // 6/7 — the tip's shape limits.
        if (p.tip.taper !in 0f..1f) out += "tip.taper ${p.tip.taper} is outside 0..1"
        if (p.tip.aspect !in -1f..1f) out += "tip.aspect ${p.tip.aspect} is outside -1..1"

        // 8 — the tip never fades below minPx, and a tip wider than 16 px on screen is a mistake.
        if (p.tip.minPx !in 0.25f..16f) out += "tip.minPx ${p.tip.minPx} is outside 0.25..16"

        // 9 — how much the pen's samples are averaged before a dab.
        if (p.smoothing !in 0f..1f) out += "smoothing ${p.smoothing} is outside 0..1"

        // 10/11 — the two jitters. angleJitter is degrees, so it goes round once, not once and a bit.
        if (p.sizeJitter !in 0f..1f) out += "sizeJitter ${p.sizeJitter} is outside 0..1"
        if (p.angleJitter !in 0f..360f) out += "angleJitter ${p.angleJitter} is outside 0..360"

        // 12/13 — scatter: at least one dab, and never a hundred of them.
        if (p.scatter.count !in 1..16) out += "scatter.count ${p.scatter.count} is outside 1..16"
        if (p.scatter.countJitter !in 0f..1f) {
            out += "scatter.countJitter ${p.scatter.countJitter} is outside 0..1"
        }

        // 14 — the two grains are the same rule four times over, so one message per rule names
        // whichever of them is wrong. A scale of 0 is as wrong as one of Infinity: the first fills
        // the whole texture, the second none of it. A disabled grain is still ranged — a number
        // nobody reads is still a number the file cannot be written back out with.
        val grainScale = ArrayList<String>()
        val grainEdge = ArrayList<String>()
        val grainTilt = ArrayList<String>()
        val grainRadial = ArrayList<String>()
        for ((n, g) in listOf("tipTexture" to p.tipTexture, "paperGrain" to p.paperGrain)) {
            if (!(g.scale > 0f) || g.scale > MAX_GRAIN_SCALE) grainScale += "$n.scale ${g.scale}"
            if (g.edge !in 0f..1f) grainEdge += "$n.edge ${g.edge} is outside 0..1"
            if (g.tiltGradient !in -4f..4f) {
                grainTilt += "$n.tiltGradient ${g.tiltGradient} is outside -4..4"
            }
            if (g.radial !in 0f..4f) grainRadial += "$n.radial ${g.radial} is outside 0..4"
        }
        if (grainScale.isNotEmpty()) {
            out += "grain scale must be above 0 and at most ${MAX_GRAIN_SCALE.toInt()}, but: " +
                grainScale.joinToString("; ")
        }
        if (grainEdge.isNotEmpty()) out += "grain edge is outside 0..1: " + grainEdge.joinToString("; ")
        if (grainTilt.isNotEmpty()) {
            out += "grain tiltGradient is outside -4..4: " + grainTilt.joinToString("; ")
        }
        if (grainRadial.isNotEmpty()) {
            out += "grain radial is outside 0..4: " + grainRadial.joinToString("; ")
        }

        // 14b — the tuft brush's sliders (R9): each 0..1, and the needle point a real width. Ranged even when the
        // engine is not "tuft", for the grain rule's reason: a number nobody reads is still one the file writes back.
        val tuftBad = ArrayList<String>()
        for ((n, v) in tuftSliders(p.tuft)) if (v !in 0f..1f) tuftBad += "tuft.$n $v"
        if (!(p.tuft.tipPx > 0f) || p.tuft.tipPx > MAX_TUFT_TIP_PX) tuftBad += "tuft.tipPx ${p.tuft.tipPx} (must be above 0 and at most ${MAX_TUFT_TIP_PX.toInt()})"
        if (tuftBad.isNotEmpty()) out += "tuft settings are outside 0..1: " + tuftBad.joinToString("; ")

        // 14c — the response curves: two handles each, every number in 0..1 (x must be, or the curve could fold back).
        val curveBad = ArrayList<String>()
        for ((n, h) in listOf("pressure" to p.response.pressure, "tilt" to p.response.tilt)) {
            if (h.size != 4) curveBad += "response.$n has ${h.size} numbers, not 4"
            else if (h.any { it !in 0f..1f }) curveBad += "response.$n $h"
        }
        if (curveBad.isNotEmpty()) out += "response curves are outside 0..1: " + curveBad.joinToString("; ")

        // 15 — colour jitter: a fraction of the hue circle, of the saturation, of the value.
        val colours = ArrayList<String>()
        if (p.color.hue !in 0f..1f) colours += "hue ${p.color.hue}"
        if (p.color.saturation !in 0f..1f) colours += "saturation ${p.color.saturation}"
        if (p.color.value !in 0f..1f) colours += "value ${p.color.value}"
        if (colours.isNotEmpty()) {
            out += "color jitter is outside 0..1: " + colours.joinToString("; ")
        }

        // 16 — a number no range speaks for must still be a number. `"hardness": {"base": 1e999}`
        // decodes to +Infinity, which draws nothing and which JSON cannot write back out, so a brush
        // holding one cannot be saved again. Six bases are in this state (opacity, flow, tip.angle,
        // the two grain depths, scatter.amount) and this is all that can be asked of them until they
        // are ranged.
        val notNumbers = ArrayList<String>()
        for ((name, param) in paramsOf(p)) {
            if (name in RANGED_BASES) continue
            if (!param.base.isFinite()) notNumbers += "$name.base = ${param.base}"
        }
        if (notNumbers.isNotEmpty()) {
            out += "not a finite number: " + notNumbers.joinToString("; ")
        }

        // 17 — how much curve a file may carry. Every input of every setting is a 256-float lookup
        // table, built on the render thread's first dab and read on every dab after it, so a file
        // with ten thousand inputs is a denial of service wearing a brush pack's clothes.
        val tooMuch = ArrayList<String>()
        for ((name, param) in paramsOf(p)) {
            if (param.inputs.size > MAX_INPUTS) {
                tooMuch += "$name has ${param.inputs.size} inputs, at most $MAX_INPUTS"
            }
            for ((i, ic) in param.inputs.withIndex()) {
                if (ic.curve.size > MAX_CURVE_POINTS) {
                    tooMuch += "$name input ${i + 1} (${ic.input}) has ${ic.curve.size} points, " +
                        "at most $MAX_CURVE_POINTS"
                }
            }
        }
        if (tooMuch.isNotEmpty()) out += "too much curve: " + tooMuch.joinToString("; ")

        // 18 — a curve is [[x,y],…]: at least one point, exactly two numbers in each.
        val curves = ArrayList<String>()
        for ((name, param) in paramsOf(p)) for ((i, ic) in param.inputs.withIndex()) {
            if (ic.curve.isEmpty()) curves += "$name input ${i + 1} (${ic.input}) has no points"
            val bad = ic.curve.withIndex().filter { it.value.size != 2 }
            if (bad.isNotEmpty()) {
                curves += "$name input ${i + 1} (${ic.input}) has a point of ${bad[0].value.size} numbers, " +
                    "not 2, at point ${bad[0].index + 1}" + if (bad.size > 1) " (and ${bad.size - 1} more)" else ""
            }
        }
        if (curves.isNotEmpty()) out += "curves: " + curves.joinToString("; ")

        // 19 — …and a pair of numbers. x is where the input sits, so 0..1: outside that it is
        // silently clamped by Curve.eval, which is a ramp the person never drew. y is the setting's
        // own value, so it is not ranged here — it only has to be a number. A point that is not a
        // pair is rule 18's message, and is left alone here.
        val badPoints = ArrayList<String>()
        for ((name, param) in paramsOf(p)) for ((i, ic) in param.inputs.withIndex()) {
            for ((j, pt) in ic.curve.withIndex()) {
                if (pt.size != 2) continue
                val x = pt[0]
                val y = pt[1]
                if (x !in 0f..1f) {
                    badPoints += "$name input ${i + 1} (${ic.input}) point ${j + 1}: x $x is not in 0..1"
                }
                if (!y.isFinite()) {
                    badPoints += "$name input ${i + 1} (${ic.input}) point ${j + 1}: y $y is not a number"
                }
            }
        }
        if (badPoints.isNotEmpty()) out += "curve points: " + badPoints.joinToString("; ")

        // 20 — combine is how the curves meet the base.
        val combines = paramsOf(p).filter { (_, param) -> param.combine !in COMBINES }
            .map { (name, param) -> "$name (\"${param.combine}\")" }
        if (combines.isNotEmpty()) out += "combine must be multiply or add, on: " + combines.joinToString(", ")

        // 21 — the three words that pick the engine's behaviour.
        val words = ArrayList<String>()
        if (p.engine !in ENGINES) words += "engine \"${p.engine}\""
        if (p.accumulate !in ACCUMULATES) words += "accumulate \"${p.accumulate}\""
        if (p.blend !in BLENDS) words += "blend \"${p.blend}\""
        if (words.isNotEmpty()) out += "unknown: " + words.joinToString(", ")

        // 22 — …and the two that pick where a texture comes from. A typo here used to render as
        // procedural with nothing said at all.
        val sources = ArrayList<String>()
        if (p.tip.source !in TIP_SOURCES) sources += "tip (\"${p.tip.source}\")"
        if (p.tipTexture.source !in GRAIN_SOURCES) sources += "tipTexture (\"${p.tipTexture.source}\")"
        if (p.paperGrain.source !in GRAIN_SOURCES) sources += "paperGrain (\"${p.paperGrain.source}\")"
        if (sources.isNotEmpty()) {
            out += "source must be procedural or image (tip), cloud or image (grain), on: " +
                sources.joinToString(", ")
        }

        // 23 — an image source has to say which image.
        val images = ArrayList<String>()
        if (p.tip.source == "image" && p.tip.image.isNullOrBlank()) images += "tip"
        if (p.tipTexture.source == "image" && p.tipTexture.image.isNullOrBlank()) images += "tipTexture"
        if (p.paperGrain.source == "image" && p.paperGrain.image.isNullOrBlank()) images += "paperGrain"
        if (images.isNotEmpty()) out += "source is image but no image path is set: " + images.joinToString(", ")

        // 24 — the tip's hardness, which is a 0..1 fraction. `jb_tip.glsl` saturates it, so before
        // this rule a file could say `"hardness": {"base": 1e30}`, get a perfectly hard tip, and the
        // person who wrote the file would never hear about it: the engine clamps, a validator that
        // clamps would be a second and invisible source of truth, so this REFUSES and says the number.
        //
        // It sits at the END rather than beside rules 6/7, where it belongs physically, because rules
        // 9–23 are numbered in the order they were added and those numbers are named in JB-0.03b's
        // table and in `BrushTest`'s section comments. One out-of-order rule is cheaper than
        // renumbering three files this row does not own.
        //
        // Only the BASE: a curve's `y` is the setting's own value and is rule 19's business ("it only
        // has to be a number"), and `TipMath.coverage` clamps it at the point of use. Written `!in`,
        // so NaN is refused as well as ±Infinity — `< 0f || > 1f` would let NaN straight through.
        if (p.tip.hardness.base !in 0f..1f) {
            out += "tip.hardness ${p.tip.hardness.base} is outside 0..1"
        }

        // 25 — the smudge engine's two rates and the push engine's amount (JB-1.06). Only ranged when
        // that engine is the one in use, but ALWAYS ranged when present in a file: a number nobody
        // reads today is one the brush cannot be written back out with. Written `!in`, so NaN is
        // refused as well as +-Infinity. `strength` is not here on purpose: it is the brush's `flow`.
        val smudgeRates = ArrayList<String>()
        if (p.smudge.pickup !in 0f..1f) smudgeRates += "smudge.pickup ${p.smudge.pickup}"
        if (p.smudge.load !in 0f..1f) smudgeRates += "smudge.load ${p.smudge.load}"
        if (smudgeRates.isNotEmpty()) out += "smudge rate is outside 0..1: " + smudgeRates.joinToString("; ")
        if (!(p.push.amount > 0f) || p.push.amount > 1f) out += "push.amount ${p.push.amount} must be above 0 and at most 1"

        return out
    }

    /**
     * Every [Param] in the preset, named the way the file names it.
     *
     * This list is the validator's whole view of a `Param`: rules 16 (finite base), 17 (input and
     * point caps), 18 (curve shape), 19 (curve numbers) and 20 (combine) all walk it and nothing
     * else, so **a `Param` added to [BrushPreset] and not added here is checked by none of them** —
     * which is exactly how a NaN `tip.hardness` once loaded clean. Adding one is a two-line change:
     * the field here, and a case in `BrushTest.everyBaseNoRuleRangedMustStillBeANumber` (or a range
     * rule, plus its name in [RANGED_BASES]). See the Questions in JB-0.03b.
     */
    private fun paramsOf(p: BrushPreset): List<Pair<String, Param>> = listOf(
        "size" to p.size,
        "opacity" to p.opacity,
        "flow" to p.flow,
        "tip.angle" to p.tip.angle,
        "tip.hardness" to p.tip.hardness,
        "tipTexture.depth" to p.tipTexture.depth,
        "paperGrain.depth" to p.paperGrain.depth,
        "scatter.amount" to p.scatter.amount,
    )
}
