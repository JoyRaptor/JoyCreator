package cc.joycreator.joybrush.core.brush

/**
 * Is this brush file usable? Every problem found is one readable line, so a screen can list them and
 * a person can fix the file. One message per rule, with everything that broke that rule inside it.
 */
object BrushValidate {

    private val ENGINES = setOf("stamp", "smudge", "wet")
    private val ACCUMULATES = setOf("wash", "buildup")
    private val BLENDS = setOf("normal", "erase")
    private val COMBINES = setOf("multiply", "add")

    fun validate(p: BrushPreset): List<String> {
        val out = ArrayList<String>()

        // 1 — this build can read this file.
        if (p.version > BRUSH_VERSION) {
            out += "brush is from a newer Joy Brush (version ${p.version}, this build reads $BRUSH_VERSION)"
        } else if (p.format != BRUSH_FORMAT) {
            out += "format is \"${p.format}\", expected \"$BRUSH_FORMAT\""
        }

        // 2 — a brush without an id cannot be saved, exported or replaced.
        if (p.id.isBlank()) out += "id is empty"

        // 3 — a brush with no size paints nothing.
        if (!(p.size.base > 0f)) out += "size.base must be above 0, is ${p.size.base}"

        // 4 — spacing is a fraction of the diameter: too small is a million dabs, too big is dots.
        // Every range test is written `!in`, so a NaN is caught too: a NaN spacing would make the dab
        // loop walk off into never-land rather than paint a wrong pixel.
        if (p.spacing !in 0.005f..5f) out += "spacing ${p.spacing} is outside 0.005..5"

        // 5 — the superellipse exponent (jb_tip.glsl).
        if (p.tip.corner !in 0.5f..64f) out += "tip.corner ${p.tip.corner} is outside 0.5..64"

        // 6/7 — the tip's shape limits.
        if (p.tip.taper !in 0f..1f) out += "tip.taper ${p.tip.taper} is outside 0..1"
        if (p.tip.aspect !in -1f..1f) out += "tip.aspect ${p.tip.aspect} is outside -1..1"

        // 8 — a curve is [[x,y],…]: at least one point, exactly two numbers in each.
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

        // 9 — combine is how the curves meet the base.
        val combines = paramsOf(p).filter { (_, param) -> param.combine !in COMBINES }
            .map { (name, param) -> "$name (\"${param.combine}\")" }
        if (combines.isNotEmpty()) out += "combine must be multiply or add, on: " + combines.joinToString(", ")

        // 10 — the three words that pick the engine's behaviour.
        val words = ArrayList<String>()
        if (p.engine !in ENGINES) words += "engine \"${p.engine}\""
        if (p.accumulate !in ACCUMULATES) words += "accumulate \"${p.accumulate}\""
        if (p.blend !in BLENDS) words += "blend \"${p.blend}\""
        if (words.isNotEmpty()) out += "unknown: " + words.joinToString(", ")

        // 11 — an image source has to say which image.
        val images = ArrayList<String>()
        if (p.tip.source == "image" && p.tip.image.isNullOrBlank()) images += "tip"
        if (p.tipTexture.source == "image" && p.tipTexture.image.isNullOrBlank()) images += "tipTexture"
        if (p.paperGrain.source == "image" && p.paperGrain.image.isNullOrBlank()) images += "paperGrain"
        if (images.isNotEmpty()) out += "source is image but no image path is set: " + images.joinToString(", ")

        return out
    }

    /** Every [Param] in the preset, named the way the file names it. */
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
