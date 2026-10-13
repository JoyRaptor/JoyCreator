package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.brush.*
import kotlin.math.min
import kotlin.math.roundToInt

/** Only controls consumed by the native POC-derived engine, with stable persisted tuning keys. */
internal object WatercolourKnobs {
    private fun options(p: BrushPreset) = p.watercolourRuntime()
    private fun changed(p: BrushPreset, w: WatercolourSpec): BrushPreset =
        p.copy(watercolour = w, version = maxOf(p.version, VERSION_WATERCOLOUR))

    /** Same-position slider replay must keep approved Double values, including non-round decimals. */
    private fun number(key: String, label: String, hint: String, lo: Double = 0.0, hi: Double = 1.0,
                       unit: String = "%", read: (WatercolourSpec) -> Double,
                       write: (WatercolourSpec, Double) -> WatercolourSpec,
                       sync: (BrushPreset, Double) -> BrushPreset = { p, _ -> p }): BrushKnobs.Knob {
        fun position(w: WatercolourSpec) = if (hi <= lo) 0f
            else ((read(w) - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
        return BrushKnobs.Knob("watercolour.$key", label, hint,
            get = { position(options(it)) },
            set = { p, v ->
                if (hi <= lo || !v.isFinite() || v.coerceIn(0f, 1f) == position(options(p))) p
                else {
                    val x = lo + (hi - lo) * v.coerceIn(0f, 1f).toDouble()
                    changed(sync(p, x), write(options(p), x))
                }
            },
            show = { p -> val x = read(options(p)); if (unit == "%") "${(x * 100).roundToInt()}%"
                else "${(x * 100).roundToInt() / 100.0}$unit" })
    }

    private fun toggle(key: String, label: String, hint: String, read: (WatercolourSpec) -> Boolean,
                       write: (WatercolourSpec, Boolean) -> WatercolourSpec) = BrushKnobs.Knob(
        "watercolour.$key", label, hint,
        get = { if (read(options(it))) 1f else 0f },
        set = { p, v -> if (!v.isFinite()) p else changed(p, write(options(p), v >= .5f)) },
        show = { if (read(options(it))) "on" else "off" }, toggle = true)

    fun setCurveHandle(p: BrushPreset, curve: String, index: Int, value: Float): BrushPreset {
        val wc = p.watercolour ?: return p
        if (index !in 0..3 || !value.isFinite()) return p
        val original = if (curve == "pressure") wc.pressureBezier else wc.tiltBezier
        if (original.size != 4 || value == original[index].toFloat()) return p
        var v = value.toDouble().coerceIn(0.0, 1.0)
        // The LUT assumes monotone x; keep the same handle ordering as the POC editor.
        if (index == 0) v = minOf(v, original[2])
        if (index == 2) v = maxOf(v, original[0])
        val handles = original.toMutableList().also { it[index] = v }
        return changed(p, if (curve == "pressure") wc.copy(pressureBezier = handles) else wc.copy(tiltBezier = handles))
    }

    private fun dynamics(kind: String): List<BrushKnobs.Knob> {
        fun read(w: WatercolourSpec) = if (kind == "scatter") w.scatterInputs else w.spacingInputs
        fun write(w: WatercolourSpec, inputs: WatercolourInputs) =
            if (kind == "scatter") w.copy(scatterInputs = inputs) else w.copy(spacingInputs = inputs)
        val label = if (kind == "scatter") "Scatter" else "Spacing"
        return listOf(
            toggle("${kind}Inputs.pressure", "$label follows pressure", "Full pressure uses the full $kind setting.",
                { read(it).pressure }, { w, v -> write(w, read(w).copy(pressure = v)) }),
            toggle("${kind}Inputs.invertPressure", "$label pressure inverted", "Light pressure uses more $kind; hard pressure uses less.",
                { read(it).invertPressure }, { w, v -> write(w, read(w).copy(invertPressure = v)) }),
            toggle("${kind}Inputs.tilt", "$label follows tilt", "Laying the pen over uses more $kind.",
                { read(it).tilt }, { w, v -> write(w, read(w).copy(tilt = v)) }),
            toggle("${kind}Inputs.invertTilt", "$label tilt inverted", "The upright pen uses more $kind; leaning uses less.",
                { read(it).invertTilt }, { w, v -> write(w, read(w).copy(invertTilt = v)) }),
        )
    }

    fun forBrush(p: BrushPreset): List<BrushKnobs.Knob> {
        if (p.watercolour == null) return emptyList()
        val body = listOf(
            number("size", "Maximum size", "The full diameter when the pen is laid over and pressed.", .25, 600.0, " px",
                { it.size }, { w, v -> w.copy(size = v, minimum = min(w.minimum, v)) },
                { b, v -> b.copy(size = b.size.copy(base = v.toFloat())) }),
            number("minimum", "Minimum size", "The finest point at the lightest touch.", .25, min(16.0, options(p).size), " px",
                { it.minimum }, { w, v -> w.copy(minimum = min(v, w.size)) }),
            number("taper", "Pressure taper", "How much pressure opens the tip into a larger brush.",
                read = { it.taper }, write = { w, v -> w.copy(taper = v) }),
            number("tipAngle", "Tip angle allowance", "Your usual pen lean still counts as using the fine tip.", 0.0, 59.0, "°",
                { it.tipAngle }, { w, v -> w.copy(tipAngle = v) }),
            number("tipWidth", "Tip width", "How wide the upright tip gets at full pressure, as a share of maximum size.",
                read = { it.tipWidth }, write = { w, v -> w.copy(tipWidth = v) }),
            number("tiltMinimum", "Tilt raises minimum", "Leaning the pen raises the smallest diameter even with very light pressure.",
                read = { it.tiltMinimum }, write = { w, v -> w.copy(tiltMinimum = v) }),
            BrushKnobs.Knob("watercolour.window", "Dab window", "How many neighboring dab samples tilt can randomly select.",
                get = { b -> val w = options(b); if (w.dabImages.size < 2) 0f else (w.window - 1).toFloat() / (w.dabImages.size - 1) },
                set = { b, v -> if (!v.isFinite()) b else { val w = options(b)
                    changed(b, w.copy(window = (1 + v.coerceIn(0f, 1f) * (w.dabImages.size - 1)).roundToInt())) } },
                show = { "${options(it).window} dabs" }),
            number("spacing", "Dab spacing", "Distance between touches, as a share of the current diameter.", .005, 1.0, "%",
                { it.spacing }, { w, v -> w.copy(spacing = v) }, { b, v -> b.copy(spacing = v.toFloat()) }),
            number("scatter", "XY scatter", "Random spread around the stroke, scaled to each dab's diameter.",
                read = { it.scatter }, write = { w, v -> w.copy(scatter = v) }),
            number("opacity", "Pigment flow", "How much pigment each touch adds; close dabs build up.",
                read = { it.opacity }, write = { w, v -> w.copy(opacity = v) },
                sync = { b, v -> b.copy(flow = b.flow.copy(base = v.toFloat())) }),
            number("strokeOpacity", "Wash opacity", "Transparency of the assembled wash, separate from each dab's flow.",
                read = { it.strokeOpacity }, write = { w, v -> w.copy(strokeOpacity = v) },
                sync = { b, v -> b.copy(opacity = b.opacity.copy(base = v.toFloat())) }),
            number("edgePool", "Edge pigment", "Richer, darker pigment at the outline of the whole stroke.",
                read = { it.edgePool }, write = { w, v -> w.copy(edgePool = v) }),
            number("edgeAlpha", "Edge opacity", "Builds pigment coverage along the whole stroke's edge.",
                read = { it.edgeAlpha }, write = { w, v -> w.copy(edgeAlpha = v) }),
            number("edgeWidth", "Edge width", "How far the pooled edge reaches into the wash.", 0.0, 32.0, " px",
                { it.edgeWidth }, { w, v -> w.copy(edgeWidth = v) }),
            number("gravityPool", "Gravity pooling", "A subtle richer gradient toward the lower boundaries of the wash.",
                read = { it.gravityPool }, write = { w, v -> w.copy(gravityPool = v) }),
            number("gravityWidth", "Gravity depth", "How far the downward pigment gradient reaches.", 0.0, 128.0, " px",
                { it.gravityWidth }, { w, v -> w.copy(gravityWidth = v) }),
            number("grain", "Fine granulation", "Fine pigment texture inside the wash.",
                read = { it.grain }, write = { w, v -> w.copy(grain = v) }),
            number("paperCut", "Paper breakup", "Dry pigment catches the real paper height; reduce for a smoother wash.",
                read = { it.paperCut }, write = { w, v -> w.copy(paperCut = v) }),
            number("dryness", "Initial dryness", "How dry the brush is at pen-down.",
                read = { it.dryness }, write = { w, v -> w.copy(dryness = v) }),
            number("dryBuildup", "Dryness buildup", "Dry-brush breakup grows as the stroke travels.",
                read = { it.dryBuildup }, write = { w, v -> w.copy(dryBuildup = v) }),
            number("depletion", "Pigment depletion", "How much a long stroke loses pigment.",
                read = { it.depletion }, write = { w, v -> w.copy(depletion = v) }),
            number("travel", "Reservoir distance", "How far the brush travels before depletion and dryness build up.", 32.0, 8000.0, " px",
                { it.travel }, { w, v -> w.copy(travel = v) }),
            number("variation", "Pigment variation", "Gentle seeded differences in concentration along the path.",
                read = { it.variation }, write = { w, v -> w.copy(variation = v) }),
            number("variationLength", "Variation length", "Distance over which pigment concentration changes.", 16.0, 1000.0, " px",
                { it.variationLength }, { w, v -> w.copy(variationLength = v) }),
            number("texture", "Wash texture", "Strength of the secondary watercolor backdrop mask.",
                read = { it.texture }, write = { w, v -> w.copy(texture = v) }),
            number("textureScale", "Wash texture scale", "Size of the watercolor backdrop tile in canvas coordinates.", .25, 4.0, "×",
                { it.textureScale }, { w, v -> w.copy(textureScale = v) }),
            number("textureContrast", "Wash texture contrast", "Contrast of the watercolor backdrop's pigment mask.", 0.0, 3.0, "×",
                { it.textureContrast }, { w, v -> w.copy(textureContrast = v) }),
            number("backdropOffset", "Wash offset per stroke", "Each new stroke starts the backdrop at a different seeded offset.",
                read = { it.backdropOffset }, write = { w, v -> w.copy(backdropOffset = v) }),
            number("rotation", "Random rotation", "Turns samples randomly to hide repeated shapes.",
                read = { it.rotation }, write = { w, v -> w.copy(rotation = v) }),
            number("follow", "Follow direction", "Turns the samples with the direction of travel.",
                read = { it.follow }, write = { w, v -> w.copy(follow = v) }),
            number("smoothPressure", "Pressure smoothing", "Calms size changes from noisy pressure readings.",
                read = { it.smoothPressure }, write = { w, v -> w.copy(smoothPressure = v) }),
            number("smoothTilt", "Tilt smoothing", "Calms size and sample changes from noisy tilt readings.",
                read = { it.smoothTilt }, write = { w, v -> w.copy(smoothTilt = v) }),
            number("smoothPath", "Path smoothing", "Smooths hand movement using the watercolor path filter.",
                read = { it.smoothPath }, write = { w, v -> w.copy(smoothPath = v) }),
            number("poolingFadeMilliseconds", "Pooling fade", "How long pooling settles after lifting the pen. Zero applies immediately.", 0.0, 2000.0, " ms",
                { it.poolingFadeMilliseconds.toDouble() }, { w, v -> w.copy(poolingFadeMilliseconds = v.roundToInt()) }),
            toggle("spacingCompensation", "Compensate spacing", "Keep paint strength steadier when changing the distance between dabs.",
                { it.spacingCompensation }, { w, v -> w.copy(spacingCompensation = v) }),
            toggle("backdropInverted", "Invert wash texture", "Swap the light and dark areas of the secondary wash mask.",
                { it.backdropInverted }, { w, v -> w.copy(backdropInverted = v) }),
            BrushKnobs.Knob("watercolour.glazing", "Multiply glazing", "Overlapping completed washes deepen each other's color.",
                get = { if (it.blend == BLEND_MULTIPLY) 1f else 0f },
                set = { b, v -> if (!v.isFinite()) b else b.copy(blend = if (v >= .5f) BLEND_MULTIPLY else "normal") },
                show = { if (it.blend == BLEND_MULTIPLY) "multiply" else "normal" }, toggle = true),
        )
        return body + dynamics("scatter") + dynamics("spacing")
    }
}
