package cc.joycreator.joybrush.core.brush

/** Generic drawer size/flow/opacity edits remain live knobs for the new engine, not duplicated dead values. */
fun BrushPreset.watercolourRuntime(): WatercolourSpec {
    require(engine == ENGINE_WATERCOLOUR) { "This is not a watercolor brush" }
    val wc = requireNotNull(watercolour) { "Watercolor settings are missing" }
    // The generic picker stores Float, whereas the reference brush maths retains Double. A
    // no-op knob sync must not quantize approved .15/.89 and move a replay's stamp positions.
    fun edited(knob: Float, original: Double) = if (knob == original.toFloat()) original else knob.toDouble()
    return wc.copy(size = edited(size.base, wc.size), opacity = edited(flow.base, wc.opacity),
        strokeOpacity = edited(opacity.base, wc.strokeOpacity), spacing = edited(spacing, wc.spacing))
}
