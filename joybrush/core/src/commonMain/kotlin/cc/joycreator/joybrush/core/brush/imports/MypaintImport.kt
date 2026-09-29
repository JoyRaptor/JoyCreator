package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.BrushInput
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BrushValidate
import cc.joycreator.joybrush.core.brush.InputCurve
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.ScatterSpec
import cc.joycreator.joybrush.core.brush.TipSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.exp

/**
 * What an import produced: the brush, and every word the reader had to spend to get there.
 *
 * The warnings are not decoration. A `.myb` carries about sixty settings and Joy Brush has room for
 * ten of them, so a MyPaint brush always arrives with most of itself left over, and a person who
 * cannot see that will one day be surprised that their watercolour brush does not smear. [warnings]
 * is where that is said out loud, once per thing that was dropped or fudged, in a sentence a screen
 * can show without rewriting it.
 */
data class ImportResult(val preset: BrushPreset, val warnings: List<String>)

/**
 * The array of points for one input, after every point has been checked to be a pair of numbers.
 *
 * This is the **shape** gate, and it is the same gate for every input on every setting whether or
 * not this importer maps it. That consistency is the point: the same malformed curve used to be
 * refused when it sat on a mapped input and silently kept when it sat on a dropped one, which meant
 * whether a file was legal depended on which setting you happened to look at.
 */
private fun shapeOf(setting: String, input: String, value: JsonElement): JsonArray {
    val points = value as? JsonArray
        ?: throw BrushException("setting \"$setting\" input \"$input\" is not an array of points")
    if (points.isEmpty()) {
        throw BrushException("setting \"$setting\" input \"$input\" has no points")
    }
    for (i in points.indices) {
        val point = points[i] as? JsonArray
            ?: throw BrushException("setting \"$setting\" input \"$input\" point ${i + 1} is not an array")
        if (point.size != 2) {
            throw BrushException("setting \"$setting\" input \"$input\" point ${i + 1} has " +
                "${point.size} numbers, not 2")
        }
        point.numberAt(i, 0, "x")
        point.numberAt(i, 1, "y")
    }
    return points
}

/**
 * Shape-check every input curve on one setting. The whole `.myb` gets this, not only the settings
 * this importer reads: a file that cannot describe a curve is not a brush file, and refusing it is
 * different from a setting this importer has no home for.
 *
 * Note what is deliberately **not** here: the x range and the 64-point cap. Those are domain rules,
 * and the domain is not 0..1 for every MyPaint input — `direction_angle` runs 0..360, `gridmap_x/y`
 * 0..256, `barrel_rotation` ±180, `custom` ±10 — so a curve on an input this importer does not map
 * may honestly carry an x outside 0..1, and refusing that would refuse real brushes. The cap is a Joy
 * limit on curves Joy evaluates; an unmapped curve is only ever text in `extensions`.
 */
private fun checkShapes(setting: String, inputs: JsonObject) {
    for ((input, value) in inputs) shapeOf(setting, input, value)
}

/**
 * One input curve as `[[x, y], …]`, mapped.
 *
 * [xScale] divides the abscissa: 1 for pressure (already 0..1), 90 for `tilt_declination` (which
 * libmypaint states in degrees). [y] is applied afterwards — `e^y` in the log-radius domain, because
 * an additive offset on a log radius is a multiplier, and the identity for the settings that are
 * already linear.
 *
 * On top of [shapeOf] this adds the two domain rules that only make sense for a curve Joy will
 * evaluate: the x range (outside it `Curve.eval` silently clamps, on a ramp nobody drew) and the
 * 64-point cap. Both are refusals rather than clamps, because a clamp would be a curve the artist
 * did not draw. Neither occurs in a real `.myb`.
 *
 * File-level rather than inside [MypaintImport] on purpose: [Setting] is a nested class, and this
 * way it reaches these without needing the outer object as a receiver.
 */
private fun pointsOf(
    setting: String,
    input: String,
    source: JsonElement,
    xScale: Float,
    y: (Float) -> Float,
): List<List<Float>> {
    val curve = shapeOf(setting, input, source)
    val n = curve.size
    if (n > BrushValidate.MAX_CURVE_POINTS) {
        throw BrushException("setting \"$setting\" input \"$input\" has $n points, at most ${BrushValidate.MAX_CURVE_POINTS}")
    }
    // `n` is already known to be in 1..64, so this allocation is bounded by a constant, not by a
    // number the file got to choose.
    val out = ArrayList<List<Float>>(n)
    for (i in 0 until n) {
        // `shapeOf` has already proved each element is a two-number array.
        val point = curve[i] as JsonArray
        val rawX = point.numberAt(i, 0, "x")
        val x = rawX / xScale
        if (!(x in 0f..1f)) {
            throw BrushException("setting \"$setting\" input \"$input\" point ${i + 1}: " +
                "x $rawX is not in 0..${xScale.toInt()}")
        }
        val value = y(point.numberAt(i, 1, "y"))
        if (!value.isFinite()) {
            throw BrushException("setting \"$setting\" input \"$input\" point ${i + 1}: " +
                "y maps to $value, which is not a number")
        }
        out.add(listOf(x, value))
    }
    return out
}

/**
 * The number at [index] of a two-number curve point, checked.
 *
 * @param point the 1-based point number, for the message.
 * @param which "x" or "y", for the message.
 */
private fun JsonArray.numberAt(point: Int, index: Int, which: String): Float {
    val primitive = this[index] as? JsonPrimitive
        ?: throw BrushException("curve point $point: $which is not a number")
    if (primitive.isString) {
        throw BrushException("curve point $point: $which is the string \"${primitive.content}\", not a number")
    }
    // Through Double so that a literal which overflows a Float is caught here rather than becoming
    // an Infinity downstream. `1e40` is well-formed JSON and is not a size.
    val value = primitive.doubleOrNull?.toFloat()
        ?: throw BrushException("curve point $point: $which is \"${primitive.content}\", not a number")
    if (!value.isFinite()) {
        throw BrushException("curve point $point: $which is ${primitive.content}, which is not finite")
    }
    return value
}

/**
 * MyPaint `.myb` (version 3) → a Joy Brush preset.
 *
 * The `.myb` file is plain JSON: a header and a `settings` object in which every entry is a *base
 * value* plus one piecewise-linear curve per input, and every curve **adds** to the base. MyPaint
 * stores its radius in log space (`radius_logarithmic`), so an additive offset there is a
 * multiplier — which is why the size curves below become multiply curves with `e^y` on the ordinate.
 * That is the whole of the trick; everything else is a table lookup (R3 §8.2, §8.6).
 *
 * **This reads files from strangers.** A brush arrives over Wi-Fi hot-reload and out of other
 * people's packs, so every number is treated as a claim to be checked rather than a value to be
 * believed, and nothing here allocates on a count or a length the file states: the file is length-
 * and depth-capped before it is parsed, a curve's length is read from `JsonArray.size` before one
 * element is copied, and a curve longer than the cap is refused rather than truncated — a truncated
 * curve is a curve the artist did not draw.
 *
 * The three ways out of here, in order of severity:
 *  - **Refused** ([BrushException]): the file cannot be read at all, or carries a number that has no
 *    meaning here (a curve point with three numbers in it, an x outside 0..1, a version we do not
 *    read). Refusing one point is harsh on the whole file, but a brush that imports "successfully"
 *    and draws differently is the worse outcome, and [extensions] keeps the raw text either way.
 *  - **Clamped, with a warning**: a real number outside Joy Brush's narrower range. The value is a
 *    number, the mapping is honest, only the range is Joy's.
 *  - **Dropped, with a warning**: something with no home in [BrushPreset] at all. It is kept verbatim
 *    in `extensions` under `myb.<setting>`, so nothing is lost — it is only lost from the drawing.
 *
 * Source of the format: github.com/mypaint/libmypaint (`brushsettings.json`, `mypaint-mapping.c`)
 * and github.com/mypaint/mypaint-brushes @ 08da4a4, whose `.myb` files are CC0-1.0 per
 * `Licenses.dep5`. No MyPaint code is copied here; the two are separate implementations that happen
 * to read the same files.
 */
object MypaintImport {

    // ---- what the format is ----------------------------------------------------------------------

    /** The only `.myb` this build reads. Version 1 and 2 predate the settings model. */
    private val MYB_VERSION = 3

    /** A `.myb` in the wild is 5–25 KB. 256 KB is generous and bounds every allocation below it. */
    private val MAX_CHARS = 262_144

    /** `root > settings > setting > inputs > point` is five. Sixty-four is unreachable by a real file. */
    private val MAX_DEPTH = 64

    /** libmypaint ships about sixty settings; a file claiming more is not one we want to walk. */
    private val MAX_SETTINGS = 256

    // ---- bounds Joy Brush has and the file does not ------------------------------------------------

    /** A dab below this is not a brush. There is no lower rule in the validator, so this is mine. */
    private val MIN_SIZE_PX = 0.01f

    private val SPACING_MIN = 0.005f
    private val SPACING_MAX = 5f

    /** Used when both `dabs_per_*` are 0: no dab would ever be placed, so a plain round line is used. */
    private val SPACING_DEFAULT = 0.1f

    /** `tilt_declination` is 0..90° (R3 §8.1). Joy's `tilt` input is 0..1. */
    private val TILT_DEGREES = 90f

    /** MyPaint's own default for `radius_logarithmic`, used only when the file omits the setting. */
    private val DEFAULT_LOG_RADIUS = 2.0f

    // ---- which `.myb` names this reader takes, and which of them keep their input curves ------------

    /** Every setting whose `base_value` (or base + density) drives a number in [BrushPreset]. */
    private val VALUED = setOf(
        "radius_logarithmic", "opaque", "hardness",
        "dabs_per_actual_radius", "dabs_per_basic_radius",
        "elliptical_dab_ratio", "elliptical_dab_angle",
        "offset_by_random", "slow_tracking",
    )

    /** …plus `opaque_multiply`, which contributes no base but does contribute its pressure curve. */
    private val SETTINGS = VALUED + "opaque_multiply"

    /**
     * Inputs that survive, per setting. Everything else on a [SETTINGS] entry is dropped and kept
     * raw. `random` is on `radius_logarithmic` because the spec folds that one curve into
     * `sizeJitter`; it is an approximation and says so in a warning when it happens.
     */
    private val DYNAMIC = mapOf(
        "radius_logarithmic" to setOf("pressure", "tilt_declination", "random"),
        "opaque_multiply" to setOf("pressure", "tilt_declination"),
    )

    /** Smudge settings get their own warning wording, because "dropped" undersells what is lost. */
    private val SMUDGE = setOf("smudge", "smudge_length", "smudge_length_log", "smudge_radius_log", "smudge_transparency")

    /** The four free-text header fields a `.myb` may put a licence in. See [license]. */
    private val FREE_TEXT = listOf("comment", "description", "notes", "parent_brush_name")

    private val NO_INPUTS = JsonObject(emptyMap())

    /** y → `e^y`. The log-space curve: an additive offset on a log radius is a multiplier. */
    private val LOG_MUL: (Float) -> Float = { exp(it.toDouble()).toFloat() }

    /** y → y. For the settings that are already linear in the units Joy Brush uses. */
    private val LINEAR: (Float) -> Float = { it }

    /**
     * The one transform every MyPaint radius goes through: a log base becomes a **diameter** in px.
     *
     * Named so that the space cannot be got wrong twice. `radius_logarithmic` is a log *radius*; the
     * 2 is diameter-not-radius; this is the only place either is written down, and both the mapped
     * path and the absent-setting fallback go through it.
     */
    private fun diameterOf(logBase: Float): Float = 2f * exp(logBase.toDouble()).toFloat()

    // ---- the contract ----------------------------------------------------------------------------

    /**
     * Read one `.myb` file. [id] and [name] are the caller's: a `.myb` names the brush's *group*,
     * not its identity, and the id has to be unique in a library.
     *
     * @throws BrushException when the file cannot be read, is a version we do not read, or carries a
     *   number this importer will not invent a meaning for. Never returns a brush that
     *   [BrushValidate] would refuse — if one somehow slips through, its problems are appended to
     *   [ImportResult.warnings] rather than swallowed.
     */
    fun convert(mybJson: String, id: String, name: String): ImportResult {
        if (id.isBlank()) throw BrushException("a Joy Brush needs an id")

        val root = parseRoot(mybJson)
        val settings = root.objectOrNull("settings")
            ?: throw BrushException("a .myb must carry a \"settings\" object")
        if (settings.size > MAX_SETTINGS) {
            throw BrushException("a .myb carries at most $MAX_SETTINGS settings, this one claims ${settings.size}")
        }

        val warnings = ArrayList<String>()
        val extensions = LinkedHashMap<String, String>()

        // Every curve in the file is shape-checked here, before anything is mapped, whatever setting
        // it is on and whether or not this importer reads that setting. Without this pass a malformed
        // curve was refused on a mapped input and quietly kept on a dropped one.
        for ((settingName, value) in settings) {
            val raw = (value as? JsonObject)?.get("inputs") ?: continue
            val inputs = raw as? JsonObject
                ?: throw BrushException("setting \"$settingName\" has an \"inputs\" that is not an object")
            checkShapes(settingName, inputs)
        }

        // ---- size: 2·e^b px, and the pressure/tilt curves that scale it ----------------------------
        // `sizeBase` is a diameter in px on **both** paths. The fallback goes through the same
        // transform as the mapped path, because a log base that reaches the preset untransformed is
        // a brush that imports successfully and draws at the wrong size — once with 2.0 as its
        // *base*, and never again once somebody noticed.
        val sizeInputs = ArrayList<InputCurve>()
        var sizeBase = clampSize(diameterOf(DEFAULT_LOG_RADIUS), warnings)
        var sizeJitter = 0f
        val radius = readSetting(settings, "radius_logarithmic")
        if (radius == null) {
            warn(warnings, "no radius_logarithmic; used MyPaint's default base $DEFAULT_LOG_RADIUS " +
                "($sizeBase px diameter)")
        } else {
            sizeBase = clampSize(diameterOf(radius.base), warnings)
            radius.curve("pressure", sizeInputs, BrushInput.pressure, 1f, LOG_MUL)
            radius.curve("tilt_declination", sizeInputs, BrushInput.tilt, TILT_DEGREES, LOG_MUL)
            val random = radius.inputOrNull("random")
            if (random != null) {
                // sizeJitter is a fraction; MyPaint's random curve is a log-radius offset. The closest
                // one-number reading is "the biggest multiplier this curve can ask for, less 1".
                val points = pointsOf("radius_logarithmic", "random", random, 1f, LINEAR)
                val biggest = points.maxOf { it[1] }
                val rough = exp(biggest.toDouble()).toFloat() - 1f
                sizeJitter = rough.coerceIn(0f, 1f)
                warn(warnings, "sizeJitter $sizeJitter is only an approximation of radius_logarithmic's " +
                    "\"random\" input (max $biggest); kept raw in extensions")
                radius.keep("myb.radius_logarithmic", extensions)
            }
        }

        // ---- opacity: `opaque` is the ceiling, `opaque_multiply` is what pressure scales ---------
        val opacityInputs = ArrayList<InputCurve>()
        var opacityBase = 1f
        val opaque = readSetting(settings, "opaque")
        if (opaque != null) {
            opacityBase = opaque.base.coerceIn(0f, 1f)
            if (opacityBase != opaque.base) warn(warnings, "opaque ${opaque.base} clamped to $opacityBase")
        }
        val opaqueMultiply = readSetting(settings, "opaque_multiply")
        if (opaqueMultiply != null) {
            opaqueMultiply.curve("pressure", opacityInputs, BrushInput.pressure, 1f, LINEAR)
            opaqueMultiply.curve("tilt_declination", opacityInputs, BrushInput.tilt, TILT_DEGREES, LINEAR)
            if (opaqueMultiply.base != 0f) {
                // The base has nowhere to go: `opaque` already owns opacity.base, and Joy's Param has
                // one base. It is 0.0 in every stock MyPaint brush, so this is nearly never said.
                warn(warnings, "opaque_multiply base_value ${opaqueMultiply.base} is not applied; " +
                    "kept raw in extensions")
                opaqueMultiply.keep("myb.opaque_multiply", extensions)
            }
        }

        // ---- spacing: dabs per radius, two of them ------------------------------------------------
        // Both densities are finite (readSetting says so) and their sum can only overflow to ±∞,
        // never to NaN, so `spacing` below is always a number and the two branches are enough.
        val perActual = readSetting(settings, "dabs_per_actual_radius")?.base ?: 0f
        val perBasic = readSetting(settings, "dabs_per_basic_radius")?.base ?: 0f
        val density = perActual + perBasic
        var spacing = if (density > 0f) (1.0 / (2.0 * density.toDouble())).toFloat() else SPACING_DEFAULT
        if (!(density > 0f)) {
            warn(warnings, "dabs_per_actual_radius and dabs_per_basic_radius are both 0; " +
                "spacing set to $SPACING_DEFAULT")
        }
        if (spacing < SPACING_MIN) {
            warn(warnings, "spacing $spacing clamped to $SPACING_MIN")
            spacing = SPACING_MIN
        } else if (spacing > SPACING_MAX) {
            warn(warnings, "spacing $spacing clamped to $SPACING_MAX")
            spacing = SPACING_MAX
        }

        // ---- tip: hardness, aspect, angle ----------------------------------------------------------
        // `hardness` is "as is" per the spec, and BrushValidate has no range for it — rule 16 only
        // asks for finiteness — so a file can put a number here that is finite and meaningless.
        // Clamping would invent a rendering decision (does jb_tip.glsl saturate above 1, or is it an
        // error?), which is not the importer's to make, and which belongs in BrushValidate with the
        // rest of the range table. Until then the value passes through untouched and the person is
        // told, because silence is the one thing this importer must not do about it.
        val hardness = readSetting(settings, "hardness")?.base ?: 0.9f
        if (hardness < 0f || hardness > 1f) {
            warn(warnings, "hardness $hardness is outside the 0..1 MyPaint documents; " +
                "passed through unchanged")
        }
        val ratio = readSetting(settings, "elliptical_dab_ratio")?.base ?: 1f
        // MyPaint's ratio is "1 = round, bigger = flatter". Joy's aspect is a −1..1 squash, so a
        // ratio below 1 means the same round dab as 1 and must not produce a negative aspect.
        val aspect = 1f - 1f / maxOf(ratio, 1f)
        val angle = readSetting(settings, "elliptical_dab_angle")?.base ?: 0f

        // ---- scatter: MyPaint offsets in radii, Joy Brush in diameters -----------------------------
        val offset = readSetting(settings, "offset_by_random")?.base ?: 0f
        var scatterBase = offset / 2f
        if (!(scatterBase >= 0f)) {
            warn(warnings, "offset_by_random $offset clamped to 0")
            scatterBase = 0f
        }

        // ---- smoothing: slow_tracking is in milliseconds, ours is a 0..1 fraction -------------------
        val slow = readSetting(settings, "slow_tracking")?.base ?: 0f
        val rawSmoothing = slow / 10f
        var smoothing = rawSmoothing.coerceIn(0f, 1f)
        if (smoothing != rawSmoothing) warn(warnings, "smoothing $rawSmoothing clamped to $smoothing")

        // ---- the inputs of the mapped settings that are not mapped -------------------------------
        // One warning per setting, naming every input that is going into extensions rather than into
        // the brush. This is where most of the loss is, so it is said once and plainly.
        for (name in SETTINGS) {
            val setting = readSetting(settings, name) ?: continue
            val kept = DYNAMIC[name].orEmpty()
            val dropped = setting.inputs.keys.filter { it !in kept }
            if (dropped.isEmpty()) continue
            val said = dropped.take(6).joinToString(", ")
            val rest = if (dropped.size > 6) " and ${dropped.size - 6} more" else ""
            warn(warnings, "$name: input $said$rest not mapped; kept raw in extensions")
            setting.keep("myb.$name", extensions)
        }

        // ---- every other setting: kept whole, said once --------------------------------------------
        for ((name, value) in settings) {
            if (name in SETTINGS) continue
            extensions["myb.$name"] = value.toString()
            if (name == "smudge") {
                val amount = (value as? JsonObject)?.floatOrNull("base_value")
                if (amount != null && amount > 0f) {
                    warn(warnings, "smudge is $amount but the smudge engine is not built: this brush " +
                        "stamps instead; kept raw in extensions")
                    continue
                }
            }
            warn(warnings, if (name in SMUDGE) {
                "$name is a smudge setting with no Joy Brush equivalent; kept raw in extensions"
            } else {
                "$name has no Joy Brush setting; kept raw in extensions"
            })
        }

        val preset = BrushPreset(
            id = id,
            name = name,
            engine = "stamp",
            tip = TipSpec(aspect = aspect, angle = Param(angle), hardness = Param(hardness)),
            size = Param(sizeBase, sizeInputs),
            opacity = Param(opacityBase, opacityInputs),
            spacing = spacing,
            scatter = ScatterSpec(amount = Param(scatterBase)),
            sizeJitter = sizeJitter,
            smoothing = smoothing,
            license = license(root),
            author = author(root),
            sourceFormat = "myb",
            accumulate = "wash",
            extensions = extensions,
        )

        // The importer's own last chance to be wrong. Every value above has already been checked or
        // clamped, so this should always be empty; if it is not, the caller hears about it here
        // rather than finding out when the brush will not save.
        for (problem in BrushValidate.validate(preset)) {
            warn(warnings, "imported brush would be refused: $problem")
        }
        return ImportResult(preset, warnings)
    }

    // ---- the file -------------------------------------------------------------------------------

    private fun parseRoot(text: String): JsonObject {
        if (text.length > MAX_CHARS) {
            throw BrushException("a .myb is at most $MAX_CHARS characters, this one is ${text.length}")
        }
        if (!balanced(text)) {
            throw BrushException("a .myb's brackets do not balance, or it nests deeper than $MAX_DEPTH")
        }
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            throw BrushException("a .myb cannot be read: ${e.message}")
        }
        val root = element as? JsonObject ?: throw BrushException("a .myb must be a JSON object")
        val version = (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw BrushException("a .myb must carry an integer \"version\"; this build reads $MYB_VERSION")
        if (version != MYB_VERSION) {
            throw BrushException("a .myb of version $version cannot be read; this build reads $MYB_VERSION")
        }
        return root
    }

    /**
     * Are the brackets in this text balanced and no deeper than [MAX_DEPTH]?
     *
     * Checked on the raw characters, before the parser sees the file, and it costs one pass and no
     * allocation. It exists because of `[[[[[[…`: a document whose nesting is a function of its
     * length can run the parser out of stack, and a crash on a stranger's file is the one outcome
     * this importer must not have. Brackets inside strings are not brackets.
     */
    private fun balanced(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        if (depth > MAX_DEPTH) return false
                    }
                    '}', ']' -> {
                        depth--
                        if (depth < 0) return false
                    }
                }
            }
        }
        return depth == 0 && !inString
    }

    // ---- header ---------------------------------------------------------------------------------

    /**
     * "CC0" if the file says so, otherwise "unknown" — never a guess. The spec names `comment`; the
     * real `mypaint-brushes` files put the licence in `notes` (`deevad/basic_digital_brush.myb`
     * says "license: CC-Zero/Public-Domain" there and nothing in its comment), so all four free-text
     * header fields are read. Missing every one of them is the answer, and no guess is made.
     */
    private fun license(root: JsonObject): String {
        val text = FREE_TEXT.mapNotNull { root.stringOrNull(it) }.joinToString(" ").lowercase()
        val cc0 = listOf("cc0", "cc-0", "cc-zero", "cc zero", "public-domain", "public domain")
        return if (cc0.any { text.contains(it) }) "CC0" else "unknown"
    }

    private fun author(root: JsonObject): String = root.stringOrNull("comment").orEmpty().trim()

    // ---- one setting ---------------------------------------------------------------------------

    /**
     * One `settings` entry, once checked: a finite base and its inputs.
     *
     * The inputs are **not** validated here. Every curve in the file goes through [checkShapes] in
     * one pass before any mapping starts, so that whether a curve is legal cannot depend on which
     * setting it happens to sit on. This class only reads.
     */
    private class Setting(
        val name: String,
        val body: JsonObject,
        val base: Float,
        val inputs: JsonObject,
    ) {
        /** The raw element for [input], or null. Checked by [pointsOf] when it is read. */
        fun inputOrNull(input: String): JsonElement? = inputs[input]

        /** Add [input] to [out] as a Joy input curve, if the setting has one. */
        fun curve(input: String, out: MutableList<InputCurve>, into: BrushInput, xScale: Float, y: (Float) -> Float) {
            val source = inputOrNull(input) ?: return
            out += InputCurve(into, pointsOf(name, input, source, xScale, y))
        }

        /** Keep this setting's raw JSON under [key], so a dropped curve is still in the file. */
        fun keep(key: String, extensions: MutableMap<String, String>) {
            extensions[key] = body.toString()
        }
    }

    private fun readSetting(settings: JsonObject, name: String): Setting? {
        val element = settings[name] ?: return null
        val body = element as? JsonObject
            ?: throw BrushException("setting \"$name\" is not an object")
        val base = body.floatOrNull("base_value")
            ?: throw BrushException("setting \"$name\" has no finite \"base_value\"")
        val raw = body["inputs"]
        if (raw == null) {
            return Setting(name, body, base, NO_INPUTS)
        }
        val inputs = raw as? JsonObject
            ?: throw BrushException("setting \"$name\" has an \"inputs\" that is not an object")
        return Setting(name, body, base, inputs)
    }

    // ---- numbers -------------------------------------------------------------------------------

    /** The finite number at [key], or null. A string, a NaN, an Infinity and a missing key are all null. */
    private fun JsonObject.floatOrNull(key: String): Float? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val value = primitive.doubleOrNull?.toFloat() ?: return null
        return if (value.isFinite()) value else null
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

    private fun clampSize(raw: Float, warnings: MutableList<String>): Float = when {
        !raw.isFinite() -> {
            warn(warnings, "size 2·e^b is $raw; clamped to ${BrushValidate.MAX_SIZE_PX} px")
            BrushValidate.MAX_SIZE_PX
        }
        raw > BrushValidate.MAX_SIZE_PX -> {
            warn(warnings, "size $raw px clamped to ${BrushValidate.MAX_SIZE_PX}")
            BrushValidate.MAX_SIZE_PX
        }
        !(raw > 0f) -> {
            warn(warnings, "size $raw px clamped to $MIN_SIZE_PX")
            MIN_SIZE_PX
        }
        else -> raw
    }

    private fun warn(warnings: MutableList<String>, message: String) {
        warnings.add(message)
    }
}
