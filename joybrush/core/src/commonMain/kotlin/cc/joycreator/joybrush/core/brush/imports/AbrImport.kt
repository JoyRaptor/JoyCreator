package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.BrushInput
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BrushValidate
import cc.joycreator.joybrush.core.brush.ColorJitter
import cc.joycreator.joybrush.core.brush.GrainSpec
import cc.joycreator.joybrush.core.brush.InputCurve
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.ScatterSpec
import cc.joycreator.joybrush.core.brush.TipSpec
import cc.joycreator.joybrush.core.brush.imports.AbrReader.normaliseId

/**
 * Photoshop `.abr` → a Joy Brush preset.
 *
 * The reader ([AbrReader]) is a port of ag-psd's format knowledge and carries its MIT notice; this
 * file is the mapping, and it is where the three buckets are spent. Every Photoshop field is
 * **MAPPED** (a [BrushPreset] field carries it), **LOSSY** (the nearest field carries it, a warning
 * names the setting and what was lost, and the raw value is kept in `extensions`), or **REFUSED**
 * (the whole brush is dropped with a sentence, because the alternative is a *different brush*).
 *
 * The distinction is the whole point: a slightly different brush is acceptable if the person is told
 * and a completely different one is not acceptable at all. A bristle tip is therefore REFUSED — a
 * strand simulation has no analogue and a round dab wearing its name is a lie — while an airbrush tip
 * is LOSSY, because a soft round dab really is an airbrush minus the spatter.
 *
 * **Nothing here is silent.** Every number that is clamped says so with both numbers, every setting
 * with no home is kept raw and named, and the one fact that cannot be resolved from the file — the
 * stored scale of Photoshop's colour jitters — is said once for the whole file rather than silently
 * guessed (see [FileNotes]).
 */
object AbrImport {

    // ---- budgets (Decision 3). Every one is a cap *and* a refusal -----------------------------------------------------------------

    /** The whole file. R4 §B.1: an `.abr` is a brush pack; 64 MiB is generous and bounds every read. */
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** `8BIM` sections in one file. A pack holds four kinds; a file with 4 096 of them is not a pack. */
    const val MAX_SECTIONS = 4_096

    /** One section's declared length. A `desc` full of 2 000 brushes is the biggest real one. */
    const val MAX_SECTION_BYTES = 32L * 1024 * 1024

    /** Brushes in one file. Ruled at 2 048 (R40). */
    const val MAX_BRUSHES = 2_048

    /** Descriptor nesting. A real preset is four deep; a list of lists of descriptors is a bomb. */
    const val MAX_DESCRIPTOR_DEPTH = 32

    /** Descriptor entries in one file, counted across the whole tree rather than per descriptor. */
    const val MAX_DESCRIPTOR_NODES = 200_000

    /** Items in one list, and tips in one `samp`, and patterns in one `patt`. */
    const val MAX_LIST_ITEMS = 20_000

    /** A key, a class id or a string, in bytes. */
    const val MAX_STRING_BYTES = 64 * 1024

    /** A sampled tip's width and height, px. */
    const val MAX_TIP_DIM = 2_048L

    /** A sampled tip's pixel count. `MAX_TIP_DIM²`, so with the side cap it cannot fire on its own. */
    const val MAX_TIP_PIXELS = 4_194_304L

    /** A sampled tip's decoded size, in bytes. Bounds the one PackBits pass Decision 4 needs. */
    const val MAX_TIP_BYTES = 16L * 1024 * 1024

    // ---- the contract ------------------------------------------------------------------------------

    /**
     * Read [bytes] and convert every brush it can.
     *
     * @param idPrefix prefix for generated ids; a brush's own name is used for its id, sanitised by
     *   [brushId] — the one sanitiser, because an id is a folder name and all three Phase 8 importers
     *   turn a stranger's brush name into one.
     * @throws BrushException if the FILE itself cannot be read (bad magic, unknown version, a
     *   section that overruns, a budget). One bad BRUSH is never a `BrushException` — it is a
     *   [RefusedBrush], and the brushes around it still convert.
     */
    fun convert(bytes: ByteArray, idPrefix: String): ImportLibrary {
        val file = AbrReader.read(bytes)
        val notes = FileNotes()
        val brushes = ArrayList<ImportResult>(file.brushes.size)
        val refused = ArrayList<RefusedBrush>()

        for ((index, entry) in file.brushes.withIndex()) {
            when (entry) {
                is AbrBrush.Unreadable ->
                    refused += RefusedBrush(index, "brush ${index + 1}", entry.reason)
                is AbrBrush.Read -> {
                    val name = entry.descriptor.text("Nm  ")?.takeIf { it.isNotBlank() } ?: "brush ${index + 1}"
                    try {
                        brushes += BrushImport(file, notes).build(entry.descriptor, name, brushId(idPrefix, name))
                    } catch (e: BrushException) {
                        refused += RefusedBrush(index, name, e.message ?: "it cannot be read")
                    }
                }
            }
        }

        // Decision 8: the whole-file facts are ONE sentence, and it is said ONCE across the library —
        // not once per brush, which is JB-8.03's Finding 2 (a warning that fires on every brush
        // trains people to ignore warnings). It rides on the first brush that converted, because
        // `ImportLibrary` has no field of its own for a file-level warning and Decision 2 forbids
        // changing that shape: JB-8.02 and JB-8.04 read this file. See the report's PROVISIONAL note.
        val wholeFile = notes.sentence()
        if (wholeFile != null && brushes.isNotEmpty()) {
            val first = brushes[0]
            brushes[0] = first.copy(warnings = first.warnings + wholeFile)
        }
        return ImportLibrary(brushes, refused)
    }
}

// ---- the whole-file facts (Decision 8) ----------------------------------------------------------------

/**
 * The facts that are true of the **file**, not of one brush, collected while the brushes are read and
 * said once at the end.
 *
 * There are exactly two: the `bVTy` control codes 5 to 8 are inferred (R4 §A.2 records that ag-psd and
 * abrkit disagree about 6 and 7), and any colour-jitter field whose stored scale the magnitude could
 * not resolve. A third kind of fact — a clamp, a lost setting — is about one brush and goes in that
 * brush's own warnings, where it belongs.
 */
private class FileNotes {
    private val inferredControls = LinkedHashSet<String>()
    private val unresolvedScales = LinkedHashSet<String>()
    private val percentageScales = LinkedHashSet<String>()

    /** A `bVTy` code of 5 to 8 on [setting]. The setting is named so the sentence says where. */
    fun inferredControl(setting: String) {
        inferredControls += setting
    }

    /** A colour-jitter field read as 0..1 because its value could have been 0..100. */
    fun unresolvedScale(key: String) {
        unresolvedScales += key
    }

    /** A colour-jitter field read as a percentage because its value was over 1. */
    fun percentageScale(key: String) {
        percentageScales += key
    }

    /** The one sentence, or null when there is nothing to say. */
    fun sentence(): String? {
        val facts = ArrayList<String>(3)
        if (inferredControls.isNotEmpty()) {
            facts += "the bVTy control codes 5 to 8 (Initial Direction, Direction, Initial Rotation, " +
                "Rotation) are inferred from two MIT parsers rather than documented, and on " +
                inferredControls.joinToString(", ") + " the control falls back to the fade curve"
        }
        if (unresolvedScales.isNotEmpty()) {
            facts += "unresolved stored scale on ${unresolvedScales.joinToString(", ")} (the file may " +
                "store 0..1 or 0..100), read as 0..1 and kept raw in extensions"
        }
        if (percentageScales.isNotEmpty()) {
            facts += "${percentageScales.joinToString(", ")}: stored as a percentage, read as 0..100"
        }
        if (facts.isEmpty()) return null
        return "this file: " + facts.joinToString("; ")
    }
}

// ---- one brush ----------------------------------------------------------------------------------------

/**
 * One preset being built, field by field.
 *
 * A class rather than one very long function because the spec's rule is that the base64 store and the
 * R40 warning are written **while the tip row is in front of you** — Decision 4's own warning that a
 * builder thinks the tip row is finished three steps before it is. Here the image is *decided* with
 * the tip ([storedTip]) and written at [finish], where the extensions budget is already known, because
 * Decision 4a needs the budget and Decision 4's two-sentence warning needs both facts at once.
 */
private class BrushImport(private val file: AbrFile, private val notes: FileNotes) {

    private val warnings = ArrayList<String>()
    private val extensions = LinkedHashMap<String, String>()

    private var aspect = 0f
    private var angleBase = 0f
    private val angleInputs = ArrayList<InputCurve>()
    private var hardnessBase = 0.9f

    private var sizeBase = DEFAULT_DIAMETER_PX
    private val sizeInputs = ArrayList<InputCurve>()
    private var opacityBase = 1f
    private val opacityInputs = ArrayList<InputCurve>()
    private var flowBase = 1f
    private val flowInputs = ArrayList<InputCurve>()
    private var spacing = DEFAULT_SPACING

    private var scatterAmount = 0f
    private val scatterInputs = ArrayList<InputCurve>()
    private var scatterCount = 1
    private var countJitter = 0f
    private var bothAxes = true

    private var paperGrain = GrainSpec()
    private var sizeJitter = 0f
    private var angleJitter = 0f
    private var color = ColorJitter()
    private var accumulate = "wash"
    private var smoothing = 0f

    /** Set by [sampled]; written at [finish], once the extensions budget is known (Decision 4/4a). */
    private var storedTip: StoredTip? = null

    private class StoredTip(val fileName: String, val bytes: ByteArray)

    fun build(descriptor: AbrDescriptor, name: String, id: String): ImportResult {
        val tipDescriptor = descriptor.descriptor("Brsh")
            ?: throw BrushException("it has no \"Brsh\" tip object, so it is not a brush this build reads")

        when (tipDescriptor.classId) {
            "dBrush" -> throw BrushException(BRISTLE)
            "dTips" -> {
                val shape = tipDescriptor.number("Shp ")
                if (shape == 5f) airbrush(tipDescriptor) else throw BrushException(ERODIBLE)
            }
            "sampledBrush" -> sampled(tipDescriptor)
            "computedBrush" -> computed(tipDescriptor)
            else -> throw BrushException(
                "its tip class \"${tipDescriptor.classId}\" is not one this build reads, and guessing " +
                    "would give a brush that draws differently"
            )
        }

        sizeDynamics(descriptor)
        angleDynamics(descriptor)
        roundnessDynamics(descriptor)
        scattering(descriptor)
        texture(descriptor)
        colour(descriptor)
        transfer(descriptor)
        toggles(descriptor)
        toolOptions(descriptor)
        unknowns(descriptor, BRUSH_KEYS, "abr")

        return finish(name, id)
    }

    // ---- the tip ------------------------------------------------------------------------------------

    private fun computed(tip: AbrDescriptor) {
        sizeBase = diameter(tip, tip.number("Dmtr"))
        hardnessBase = percent("hardness", tip.number("Hrdn")?.div(100f))
        angleBase = tip.number("Angl") ?: 0f
        aspect = roundness(tip.number("Rndn"))
        spacingFrom(tip)
        flip(tip)
        unknowns(tip, TIP_KEYS, "abr.Brsh")
    }

    /**
     * A sampled tip: **LOSSY and, under R40, STORED** (Decision 4). The two are not alternatives.
     *
     * The numbers come from the bitmap so the brush paints at the right size *today* — `size.base` is
     * the bitmap's own width and the hardness is R40's accepted stand-in `0.25 + 0.7 × meanAlpha` —
     * and the artist's own bytes are kept in `extensions` for JB-1.05d, which will be the first build
     * that draws them.
     *
     * The bitmap is never decoded *into an image*: PackBits is undone (one pass, [MAX_TIP_BYTES]) and
     * a mean is taken of the gray samples. No PNG, no inflate, no image maths.
     */
    private fun sampled(tip: AbrDescriptor) {
        val uuid = tip.text("sampledData")
            ?: throw BrushException("its sampled tip names no bitmap, so there is nothing to draw with")
        val abrTip = file.tipsById[normaliseId(uuid)]
            ?: throw BrushException("its sampled tip \"$uuid\" is not in this file")
        // Before anything is read out of it, and before its width becomes a number: D10's bounds
        // check is the tip's own sentence, not one about the brush that happens to name it.
        abrTip.error?.let { throw BrushException("its sampled tip cannot be read: $it") }

        sizeBase = diameter(tip, abrTip.width.toFloat())
        val mean = file.meanAlpha(abrTip)
        // R40 accepts this stand-in uncalibrated; Decision 4 writes it down. 0.25 is the softest a
        // procedural tip can be and 0.95 the hardest, so the result is always in 0..1 with no clamp.
        hardnessBase = 0.25f + 0.7f * mean
        aspect = roundness(tip.number("Rndn"))
        angleBase = tip.number("Angl") ?: 0f
        spacingFrom(tip)
        flip(tip)
        unknowns(tip, TIP_KEYS, "abr.Brsh")

        // R40: "the file name names the encoding of the stored bytes". These bytes are the file's own
        // `samp` payload, so they are PackBits when the entry says PackBits and raw gray when it says
        // raw. Calling either of them "tip.png" would be a lie written into a file a future writer
        // would materialise.
        val stored = file.storedBytes(abrTip)
        storedTip = StoredTip(
            if (abrTip.compression == AbrReader.COMPRESSION_PACKBITS) "tip.packbits" else "tip.raw",
            stored,
        )
    }

    /**
     * An airbrush tip is **LOSSY**: a soft round dab really is an airbrush, minus the spatter, the
     * streakiness and the cut-off angle. It is the counterpart to the bristle refusal above, and the
     * difference is the whole of the spec's MAPPED / LOSSY / REFUSED rule in two lines.
     */
    private fun airbrush(tip: AbrDescriptor) {
        sizeBase = diameter(tip, tip.number("Dmtr"))
        hardnessBase = percent("hardness", tip.number("dtipsHardness")?.div(100f))
        warn("airbrush tip: imported as a soft round dab, because the spatter, the streakiness and " +
            "the tilt cut-off have no Joy Brush equivalent yet; the rest is kept raw in extensions")
        keepRaw(tip, D_TIPS_KEYS, "abr.Brsh")
    }

    // ---- size ---------------------------------------------------------------------------------------

    private fun sizeDynamics(descriptor: AbrDescriptor) {
        val szVr = descriptor.descriptor("szVr")
        if (szVr != null) {
            val jitter = szVr.number("jitter")
            if (jitter != null) sizeJitter = percent("sizeJitter", jitter.div(100f))
            // `Mnm ` is a percentage of the diameter; `minimumDiameter` is a diameter, so the second
            // becomes a fraction of the first. Both land in the same place: the low end of the curve.
            val minimumDiameter = descriptor.number("minimumDiameter")
            val fromAbsolute = if (szVr.number("Mnm ") == null && minimumDiameter != null && sizeBase > 0f) {
                (minimumDiameter / sizeBase).coerceIn(0f, 1f)
            } else {
                null
            }
            dynamics(sizeInputs, szVr, "szVr", fromAbsolute)
        }
        val tiltScale = descriptor.number("tiltScale")
        if (tiltScale != null && sizeInputs.none { it.input == BrushInput.tilt }) {
            addCurve(sizeInputs, BrushInput.tilt, percent("tiltScale", tiltScale.div(100f)))
        }
    }

    private fun angleDynamics(descriptor: AbrDescriptor) {
        val ad = descriptor.descriptor("angleDynamics")
        if (ad == null) return
        val jitter = ad.number("jitter")
        if (jitter != null) {
            // `jitter` is a percentage; `angleJitter` is degrees, so it goes round once, not once and a
            // bit (BrushValidate rule 11). Its range is 0..360, not 0..1, so it is clamped against
            // *that* — clamping a full turn into 0..1 would be the one place `percent()` is wrong.
            val raw = jitter / 100f * 360f
            if (raw < 0f || raw > 360f) {
                warn("angleJitter $raw clamped to ${raw.coerceIn(0f, 360f)} (it is degrees)")
                angleJitter = raw.coerceIn(0f, 360f)
            } else {
                angleJitter = raw
            }
        }
        dynamics(angleInputs, ad, "angleDynamics", null)
    }

    /**
     * Roundness dynamics has **no home**: `TipSpec.aspect` is a bare `Float` with no `inputs` and no
     * `base`, so a curve cannot be attached to it and writing one would not compile. The jitter
     * percentage has nowhere to go either. Both ride in one `extensions` entry with one warning.
     */
    private fun roundnessDynamics(descriptor: AbrDescriptor) {
        val rd = descriptor.descriptor("roundnessDynamics") ?: return
        extensions["abr.roundnessDynamics"] = render(rd)
        warn("roundness dynamics has no Joy Brush setting to go with it (tip.aspect takes no curve), " +
            "so the tip is fixed at $aspect; kept raw in extensions")
    }

    // ---- scattering ---------------------------------------------------------------------------------

    private fun scattering(descriptor: AbrDescriptor) {
        if (descriptor.flag("useScatter") == false) {
            scatterAmount = 0f
        } else {
            descriptor.descriptor("scatterDynamics")?.let {
                dynamics(scatterInputs, it, "scatterDynamics", null)
            }
        }
        descriptor.flag("bothAxes")?.let { bothAxes = it }
        descriptor.number("Cnt ")?.let { raw ->
            val wanted = raw.toInt()
            val clamped = wanted.coerceIn(1, 16)
            if (clamped != wanted) warn("scatter count $wanted clamped to $clamped")
            scatterCount = clamped
        }
        val cd = descriptor.descriptor("countDynamics") ?: return
        countJitter = percent("scatter.countJitter", (cd.number("jitter") ?: 0f).div(100f))
        if ((cd.number("bVTy") ?: 0f) != 0f) {
            // `countJitter` is a plain float with no curve, so a controlled count jitter is lost. The
            // *inferred-ness* of the code is a whole-file fact and goes to [notes]; what is lost here
            // is a setting, and that is this brush's own loss.
            extensions["abr.countDynamics"] = render(cd)
            warn("count jitter is controlled, and scatter.countJitter takes no curve, so only the " +
                "jitter amount was mapped; kept raw in extensions")
        }
    }

    // ---- texture (Decision 5) -----------------------------------------------------------------------

    /**
     * A texture pattern carries the R40 warning **word for word** and the grain stays off.
     *
     * `patt` pattern data is not an image file under any name Joy Brush could give it, so there is
     * nothing to store and `paperGrain.enabled` stays `false`. What is kept is the pattern's id and
     * whatever the descriptor says about it, so the same grep finds this sentence here, in JB-8.02's
     * grain and in JB-8.04's texture. *Why not `source = "image"`: R40's rule is "store the image",
     * and there is no image.*
     */
    private fun texture(descriptor: AbrDescriptor) {
        val txtr = descriptor.descriptor("Txtr")
        if (txtr != null) {
            val id = txtr.text("Idnt")
            extensions["abr.texturePattern"] =
                (if (id != null) "pattern \"$id\"; " else "") + render(txtr)
            warn("$TEXTURE_NOT_DRAWN; its texture is the Photoshop pattern ${id ?: "(unnamed)"}, which " +
                "is not an image file Joy Brush can name, so paperGrain stays off; kept raw in extensions")
        }
        descriptor.value("InvT")?.let {
            extensions["abr.InvT"] = render(it)
            warn("texture invert has no Joy Brush setting; kept raw in extensions")
        }
        descriptor.number("textureScale")?.let {
            // Rule 14: a grain scale is above 0 and at most 64, so both ends are clamped, not refused —
            // the number is a number, only Joy's range is narrower.
            if (!(it > 0f) || it > 64f) {
                val clamped = if (it.isNaN() || it <= 0f) 1f else 64f
                warn("textureScale $it clamped to $clamped")
                paperGrain = paperGrain.copy(scale = clamped)
            } else {
                paperGrain = paperGrain.copy(scale = it)
            }
        }
        descriptor.number("textureDepth")?.let {
            // R4 §A.5 gives the texture depth as one of Photoshop's percentage controls (0–100 in the
            // panel) and `GrainSpec.depth` is a 0..1 level, so the divide is the documented one. It is
            // the only place this importer turns a percentage into a fraction without the
            // magnitude-decides rule, because there is no ambiguity here to guard against: a level
            // above 1 is not a level.
            paperGrain = paperGrain.copy(depth = Param(percent("textureDepth", it / 100f)))
        }
        descriptor.flag("TxtC")?.let { paperGrain = paperGrain.copy(enabled = it) }
        for (key in TEXTURE_LOSSY) {
            descriptor.value(key)?.let {
                extensions["abr.$key"] = render(it)
                warn("$key has no Joy Brush equivalent (the texture it describes is not drawn yet); " +
                    "kept raw in extensions")
            }
        }
    }

    // ---- colour -------------------------------------------------------------------------------------

    /**
     * Hue, saturation and brightness jitter, on the rule the spec's note under the mapping table
     * writes out, because the *stored* scale is not documented anywhere: R4 §A.7 states the Photoshop
     **UI** is "0–100% random offsets in HSB" while R4's own mapping table marks the file mapping `≈`
     * with "Distribution unknown" (`:774`). So the magnitude decides, and nothing is divided on a
     * guess:
     *
     *  - `v == 0` → `0`. Both readings agree, so there is nothing to say.
     *  - `0 < v <= 1` → `v`, **unresolved**: it is either `v` or `v / 100`. The whole-file sentence
     *    says so, once, for the file rather than for each of 200 brushes.
     *  - `v > 1` → `v / 100`, clamped to 1, with a per-brush clamp warning naming both numbers.
     *
     * A blind `/100` here would be worse than no answer: a real `Brgh = 0.5` would become `0.005`, a
     * 100× too-small jitter that is still inside `0f..1f`, so rule 15 says nothing, no clamp fires and
     * no warning can be produced. That is the "imports successfully, draws differently" outcome this
     * spec exists to prevent, written into the mapping itself.
     */
    private fun colour(descriptor: AbrDescriptor) {
        val hue = colourJitter(descriptor, "H   ")
        val saturation = colourJitter(descriptor, "Strt")
        val value = colourJitter(descriptor, "Brgh")
        // `colorDynamicsPerTip` is the flag; the field it maps to is `color.perStroke`, because
        // ColorJitter has no `perDab` (BrushPreset.kt:57).
        val perStroke = descriptor.flag("colorDynamicsPerTip") ?: false
        color = ColorJitter(hue, saturation, value, perStroke)
        for (key in COLOUR_LOSSY) {
            descriptor.value(key)?.let {
                extensions["abr.$key"] = render(it)
                warn("$key has no Joy Brush equivalent; kept raw in extensions")
            }
        }
    }

    private fun colourJitter(descriptor: AbrDescriptor, key: String): Float {
        val raw = descriptor.value(key) ?: return 0f
        extensions["abr.$key"] = render(raw)          // the raw value always survives
        val value = (raw as? AbrValue.Num)?.value?.toFloat() ?: return 0f
        if (!value.isFinite() || value == 0f) return 0f
        if (value <= 1f) {
            notes.unresolvedScale(key)
            return value.coerceIn(0f, 1f)
        }
        notes.percentageScale(key)
        val divided = value / 100f
        if (divided > 1f) {
            warn("$key $value clamps to 1.0 (colour jitter is 0..1)")
            return 1f
        }
        return divided
    }

    // ---- transfer and toggles ------------------------------------------------------------------------

    private fun transfer(descriptor: AbrDescriptor) {
        descriptor.descriptor("opVr")?.let { dynamics(opacityInputs, it, "opVr", null) }
        descriptor.descriptor("prVr")?.let { dynamics(flowInputs, it, "prVr", null) }
        for (key in TRANSFER_LOSSY) {
            descriptor.value(key)?.let {
                extensions["abr.$key"] = render(it)
                warn("$key has no Joy Brush equivalent yet; kept raw in extensions")
            }
        }
    }

    private fun toggles(descriptor: AbrDescriptor) {
        descriptor.flag("Nose")?.let {
            extensions["abr.Nose"] = render(descriptor.value("Nose")!!)
            warn("noise is on, and there is no Joy Brush noise; kept raw in extensions")
        }
        descriptor.flag("Wtdg")?.let {
            extensions["abr.Wtdg"] = render(descriptor.value("Wtdg")!!)
            warn("wet edges is on, and there is no Joy Brush wet edge yet; kept raw in extensions")
        }
        if (descriptor.flag("Rpt ") == true) accumulate = "buildup"
    }

    private fun toolOptions(descriptor: AbrDescriptor) {
        val options = descriptor.descriptor("toolOptions") ?: return
        options.number("Opct")?.let { opacityBase = percent("opacity", it.div(100f)) }
        options.number("flow")?.let { flowBase = percent("flow", it.div(100f)) }
        options.number("smoothingValue")?.let { smoothing = percent("smoothing", it.div(100f)) }
        unknowns(options, TOOL_OPTION_KEYS, "abr.toolOptions")
    }

    // ---- the control curve (R4 §A.2) -------------------------------------------------------------------

    /**
     * One dynamics object — `{ jitter, bVTy, fStp, Mnm }` — becomes a curve on [into].
     *
     * **A `bVTy` control is never read as "just the base value".** That is the rule the JB-8.03
     * review produced and it is a hard constraint here: codes 1 to 4 have a real `BrushInput`, code
     * 5 to 8 does not, and those fall back to the fade curve — so the jitter is still bounded by
     * `Mnm ` rather than dropped — while the control itself is recorded raw and the *inferred* codes
     * go to the whole-file sentence once (Decision 8).
     *
     * The curve itself is Photoshop's own shape: the setting rises from `Mnm ` at the start of the
     * control to its full value at the end, which is what a `fStp`-step fade means. Two points, both
     * inside `0f..1f`, so `BrushValidate` rule 19 is satisfied by construction.
     */
    private fun dynamics(into: MutableList<InputCurve>, dyn: AbrDescriptor, setting: String, minimum: Float?) {
        val control = dyn.number("bVTy")?.toInt() ?: 0
        val fadeSteps = dyn.number("fStp")?.toInt() ?: 0
        val low = (minimum ?: dyn.number("Mnm ")?.div(100f) ?: 1f).coerceIn(0f, 1f)
        val input = when (control) {
            0 -> if (fadeSteps > 0) BrushInput.distance else null
            1 -> BrushInput.distance
            2 -> BrushInput.pressure
            3 -> BrushInput.tilt
            4 -> BrushInput.barrel
            in 5..8 -> {
                extensions["abr.bVTy.$setting"] = control.toString()
                notes.inferredControl(setting)
                BrushInput.distance
            }
            else -> null
        }
        if (input == null) return
        addCurve(into, input, low)
    }

    /** One curve, with both of `BrushValidate`'s curve budgets checked before the points are built. */
    private fun addCurve(into: MutableList<InputCurve>, input: BrushInput, low: Float) {
        if (into.size >= BrushValidate.MAX_INPUTS) {
            throw BrushException(
                "it asks for more than ${BrushValidate.MAX_INPUTS} controls on one setting, which is " +
                    "the most a Joy Brush evaluates"
            )
        }
        val points = if (low >= 1f) listOf(listOf(1f, 1f)) else listOf(listOf(0f, low), listOf(1f, 1f))
        if (points.size > BrushValidate.MAX_CURVE_POINTS) {
            throw BrushException(
                "a curve of ${points.size} points is over the ${BrushValidate.MAX_CURVE_POINTS} a " +
                    "Joy Brush evaluates"
            )
        }
        into += InputCurve(input, points)
    }

    // ---- the numbers that have to be clamped or refused ---------------------------------------------------

    private fun diameter(tip: AbrDescriptor, raw: Float?): Float {
        if (raw == null) {
            // Photoshop always writes `Dmtr` on a computed tip, so this is a file that does not. The
            // default is said rather than picked in silence, because silence is the one thing this
            // importer must not do.
            warn("it does not say how big its tip is; used ${DEFAULT_DIAMETER_PX} px, the Photoshop default")
            return DEFAULT_DIAMETER_PX
        }
        if (!raw.isFinite()) {
            throw BrushException("its diameter is $raw, which is not a size")
        }
        if (raw > BrushValidate.MAX_SIZE_PX) {
            warn("tip diameter $raw px clamped to ${BrushValidate.MAX_SIZE_PX}")
            return BrushValidate.MAX_SIZE_PX
        }
        if (raw <= 0f) {
            warn("tip diameter $raw px clamped to $MIN_DIAMETER_PX")
            return MIN_DIAMETER_PX
        }
        return raw
    }

    private fun roundness(raw: Float?): Float {
        if (raw == null) return 0f
        val aspect = -(1f - raw / 100f)
        if (aspect < -1f) {
            warn("roundness $raw% clamps to -1 (tip.aspect is -1..1)")
            return -1f
        }
        if (aspect > 1f) {
            warn("roundness $raw% clamps to 1 (tip.aspect is -1..1)")
            return 1f
        }
        return aspect
    }

    private fun spacingFrom(tip: AbrDescriptor) {
        val raw = tip.value("Spcn")?.let { it as? AbrValue.Num }?.value
        if (raw == null) return
        val value = (raw / 100.0).toFloat()
        if (!value.isFinite()) {
            // The one number on a brush that cannot be clamped into range: a spacing of NaN or
            // Infinity has no fraction of a diameter to be, and a guess here is a stroke of zero dabs
            // or a stroke of one. Refused, in words (Decision 11).
            throw BrushException("spacing is $raw%, which is not a fraction of the diameter")
        }
        if (value < SPACING_MIN) {
            warn("spacing $value clamped to $SPACING_MIN")
            spacing = SPACING_MIN
        } else if (value > SPACING_MAX) {
            warn("spacing $value clamped to $SPACING_MAX")
            spacing = SPACING_MAX
        } else {
            spacing = value
        }
    }

    private fun flip(tip: AbrDescriptor) {
        if (tip.flag("Intr") == false) {
            extensions["abr.spacingMode"] = "speed"
            warn("this brush spaces its dabs by speed, and Joy Brush always spaces by distance, so " +
                "the spacing you see is the percentage the file states; kept raw in extensions")
        }
        for (key in FLIP_KEYS) {
            if (tip.flag(key) == true) {
                extensions["abr.$key"] = "true"
                warn("the tip is flipped ($key) and Joy Brush has no flip yet, so it draws unflipped; " +
                    "kept raw in extensions")
            }
        }
    }

    /** A percentage straight into a 0..1 field, clamped with both numbers named. */
    private fun percent(field: String, value: Float?): Float {
        if (value == null) return when (field) {
            "hardness" -> HARDNESS_DEFAULT
            else -> 0f
        }
        if (!value.isFinite()) {
            warn("$field is $value, which is not a number; used 0")
            return 0f
        }
        if (value < 0f) {
            warn("$field ${value}f clamped to 0 (the field is 0..1)")
            return 0f
        }
        if (value > 1f) {
            warn("$field ${value}f clamped to 1 (the field is 0..1)")
            return 1f
        }
        return value
    }

    // ---- finish --------------------------------------------------------------------------------------

    private fun finish(name: String, id: String): ImportResult {
        var tip = TipSpec(
            aspect = aspect,
            angle = Param(angleBase, angleInputs),
            hardness = Param(hardnessBase),
        )
        storedTip?.let { stored ->
            val b64 = base64Of(stored.bytes)
            val others = extensionChars()
            if (others + b64.length <= MAX_EXTENSION_BYTES) {
                extensions[TIP_IMAGE_KEY] = b64
                tip = tip.copy(source = "image", image = stored.fileName)
                warn("$TEXTURE_NOT_DRAWN; bitmap tip: $STAND_IN")
            } else {
                // Decision 4a: over the cap the image is dropped **whole**. Storing a prefix of an
                // image is the one thing this importer will not do — it is a file that is not the
                // thing it says it is. The numbers of Decision 4 are untouched, so the brush still
                // paints at the right size, and the warning says how big it was and what the cap is.
                warn("$TEXTURE_NOT_DRAWN; bitmap tip: $STAND_IN Its ${stored.bytes.size} bytes are " +
                    "$b64.length base64 characters, over the $MAX_EXTENSION_BYTES-character extensions " +
                    "cap, so none of it is stored and the tip stays procedural")
            }
        }
        val characters = extensionChars()
        if (characters > MAX_EXTENSION_BYTES) {
            throw BrushException(
                "its extensions come to $characters characters, at most $MAX_EXTENSION_BYTES"
            )
        }

        val preset = BrushPreset(
            id = id,
            name = name,
            engine = "stamp",
            tip = tip,
            size = Param(sizeBase, sizeInputs),
            opacity = Param(opacityBase, opacityInputs),
            flow = Param(flowBase, flowInputs),
            spacing = spacing,
            paperGrain = paperGrain,
            scatter = ScatterSpec(Param(scatterAmount, scatterInputs), scatterCount, countJitter, bothAxes),
            sizeJitter = sizeJitter,
            angleJitter = angleJitter,
            color = color,
            accumulate = accumulate,
            smoothing = smoothing,
            // `.abr` carries no licence and no author, and R4 §5 is explicit that redistributing a
            // converted third-party pack is not ours to do — so the field says "unknown", never
            // "CC0", and nothing is invented to fill it.
            license = "unknown",
            author = "",
            sourceFormat = "abr",
            extensions = extensions,
        )

        // Decision 11: everything ends up validate-clean or absent. Every value above has already been
        // clamped or checked, so this should always be empty; if it is not, the *mapping* is wrong and
        // the house rule is to refuse in words rather than ship a brush the validator will reject.
        val problems = BrushValidate.validate(preset)
        if (problems.isNotEmpty()) {
            throw BrushException("it would not be a legal brush: " + problems.joinToString("; "))
        }
        return ImportResult(preset, warnings)
    }

    private fun extensionChars(): Int {
        var total = 0
        for (v in extensions.values) total += v.length
        return total
    }

    private fun warn(message: String) {
        warnings += message
    }

    /** Every key this importer does not read, kept raw under [prefix] and said once, not per key. */
    private fun unknowns(from: AbrDescriptor, read: Set<String>, prefix: String) {
        val left = from.items.filter { it.key !in read }
        if (left.isEmpty()) return
        for (item in left) extensions["$prefix.${item.key}"] = render(item.value)
        val named = left.take(6).joinToString(", ") { it.key }
        val rest = if (left.size > 6) " and ${left.size - 6} more" else ""
        warn("${left.size} setting$rest this build does not map ($named); kept raw in extensions")
    }

    private fun keepRaw(tip: AbrDescriptor, read: Set<String>, prefix: String) {
        val left = tip.items.filter { it.key !in read }
        if (left.isEmpty()) return
        for (item in left) extensions["$prefix.${item.key}"] = render(item.value)
    }

    /**
     * One descriptor value as text for `extensions`.
     *
     * Depth-bounded by [MAX_DESCRIPTOR_DEPTH] because a file can nest a list inside a descriptor
     * inside a list, and this string ends up in a `brush.json`; a value too deep to print is printed
     * as its depth, not walked further.
     */
    private fun render(descriptor: AbrDescriptor, depth: Int = 0): String = render(AbrValue.Desc(descriptor), depth)

    private fun render(value: AbrValue?, depth: Int = 0): String {
        if (value == null) return ""
        if (depth > 8) return "…"
        return when (value) {
            is AbrValue.Num -> {
                val v = value.value
                if (v.isNaN()) "NaN" else if (v.isInfinite()) if (v > 0) "Infinity" else "-Infinity"
                else if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString()
                else v.toString()
            }
            is AbrValue.Text -> value.value
            is AbrValue.Enum -> value.valueId
            is AbrValue.Blob -> "${value.value.size} bytes"
            is AbrValue.Ref -> "reference ${value.index + 1}"
            is AbrValue.Desc -> buildString {
                append('{')
                append(value.descriptor.classId)
                value.descriptor.items.forEach { item ->
                    append(", ").append(item.key).append('=').append(render(item.value, depth + 1))
                }
                append('}')
            }
            is AbrValue.Items ->
                value.values.joinToString(", ", "[", "]") { render(it, depth + 1) }
        }
    }

    companion object {
        /** Photoshop's own default tip size, for the rare file that states no diameter. Said, not guessed. */
        private const val DEFAULT_DIAMETER_PX = 13f

        /** A dab below this is not a brush, and there is no lower rule in the validator. */
        private const val MIN_DIAMETER_PX = 0.01f

        private const val HARDNESS_DEFAULT = 0.9f

        /** `BrushValidate` rule 4's range, restated here because the clamp has to say both numbers. */
        private const val SPACING_MIN = 0.005f
        private const val SPACING_MAX = 5f
        private const val DEFAULT_SPACING = 0.04f

        /** Where R40 puts the stored tip bytes, and the file name that names their encoding. */
        private const val TIP_IMAGE_KEY = "abr.tipImage"

        /** Decision 4's second sentence, the half that says what the numbers are standing in for. */
        private const val STAND_IN =
            "imported as a soft round tip, because Joy Brush has no image-tip engine yet (JB-1.05d)"

        private val BRISTLE =
            "a bristle tip (dBrush) is a strand simulation with no Joy Brush equivalent; a round dab " +
                "wearing the name of a bristle brush would be a different brush, not a coarser one"

        private val ERODIBLE =
            "an erodible tip (dTips) wears down with distance and pressure, which Joy Brush cannot " +
                "draw; the whole brush is dropped rather than replaced by a tip that looks similar"

        /** Keys read on the brush descriptor. Everything else is kept raw and named. */
        private val BRUSH_KEYS = setOf(
            "Nm  ", "Brsh", "Spcn",
            "szVr", "minimumDiameter", "tiltScale", "angleDynamics", "roundnessDynamics",
            "useScatter", "scatterDynamics", "bothAxes", "Cnt ", "countDynamics",
            "Txtr", "InvT", "textureScale", "textureDepth", "TxtC",
            "textureBlendMode", "textureBrightness", "textureContrast",
            "H   ", "Strt", "Brgh", "colorDynamicsPerTip", "clVr", "purity",
            "opVr", "prVr", "wtVr", "mxVr",
            "Nose", "Wtdg", "Rpt ", "toolOptions",
        )

        /** Keys read on the tip object, for a computed or a sampled brush. */
        private val TIP_KEYS = setOf(
            "Dmtr", "Hrdn", "Angl", "Rndn", "Spcn", "Intr", "flipX", "flipY", "sampledData",
        )

        /** Keys read on an erodible or airbrush tip. */
        private val D_TIPS_KEYS = setOf("Dmtr", "Shp ", "dtipsHardness")

        private val TOOL_OPTION_KEYS = setOf("Opct", "flow", "smoothingValue")

        private val FLIP_KEYS = listOf("flipX", "flipY")

        private val TEXTURE_LOSSY = listOf("textureBlendMode", "textureBrightness", "textureContrast")

        private val COLOUR_LOSSY = listOf("clVr", "purity")

        private val TRANSFER_LOSSY = listOf("wtVr", "mxVr")
    }
}

// ---- base64 (RFC 4648 §4) -------------------------------------------------------------------------------

/**
 * RFC 4648 §4 "Base 64 encoding", the standard alphabet `A–Z a–z 0–9 + /` with `=` padding.
 *
 * Derived, not recalled: three input bytes are 24 bits, and each of the four output characters takes
 * six, so the four indices are `(n >> 18) & 63`, `(n >> 12) & 63`, `(n >> 6) & 63`, `n & 63`, where
 * `n` is the three bytes big-endian. A trailing group of one byte is 8 bits and pads with `==`; two
 * bytes are 16 bits and pad with `=`. No line breaks — `extensions` is a JSON string.
 *
 * Written here rather than taken from `kotlin.io.encoding` so that the importer has no experimental
 * opt-in in `commonMain`, and so that the test can check it against an independently written decoder
 * rather than against itself.
 */
private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

private fun base64Of(bytes: ByteArray): String {
    val sb = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i + 3 <= bytes.size) {
        val n = ((bytes[i].toInt() and 0xFF) shl 16) or
            ((bytes[i + 1].toInt() and 0xFF) shl 8) or
            (bytes[i + 2].toInt() and 0xFF)
        sb.append(BASE64_ALPHABET[(n ushr 18) and 63])
        sb.append(BASE64_ALPHABET[(n ushr 12) and 63])
        sb.append(BASE64_ALPHABET[(n ushr 6) and 63])
        sb.append(BASE64_ALPHABET[n and 63])
        i += 3
    }
    when (bytes.size - i) {
        1 -> {
            val n = (bytes[i].toInt() and 0xFF) shl 16
            sb.append(BASE64_ALPHABET[(n ushr 18) and 63])
            sb.append(BASE64_ALPHABET[(n ushr 12) and 63])
            sb.append("==")
        }
        2 -> {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            sb.append(BASE64_ALPHABET[(n ushr 18) and 63])
            sb.append(BASE64_ALPHABET[(n ushr 12) and 63])
            sb.append(BASE64_ALPHABET[(n ushr 6) and 63])
            sb.append('=')
        }
    }
    return sb.toString()
}
