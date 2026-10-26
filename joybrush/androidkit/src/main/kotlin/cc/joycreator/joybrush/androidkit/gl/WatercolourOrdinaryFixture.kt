package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BrushValidate
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.FrozenBrushRegistry
import cc.joycreator.joybrush.core.brush.FrozenBrushResult
import cc.joycreator.joybrush.core.brush.watercolourRuntime
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.CapturedStrokeRules
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.stroke.StrokeWashFrame

/** Debug diagnostic fixture only. Never writes a library, preferences, document or engine. */
internal object WatercolourOrdinaryFixture {
    data class Override(val field: String, val before: String, val after: String)

    class Prepared internal constructor(
        val sourceId: String,
        val frozen: FrozenBrushResult,
        changes: List<Override>,
    ) {
        private val changes = changes.toList()
        val overrides: List<Override> get() = changes.toList()

        /** Fresh immutable sample list each time; spans negative coordinates and tile boundaries. */
        fun record(): StrokeRecord = CapturedStrokeRules.snapshot(StrokeRecord(
            id = "ordinary-wc-diagnostic", brushId = frozen.id, seed = 78234L,
            smoothing = .2f, screenPerDoc = 1f,
            samples = (0 until 24).map { i -> PenSample(
                x = -40f + i * 12f, y = -24f + (i % 6) * 18f, timeMs = i * 16.0,
                pressure = .7f, tilt = .4f, azimuth = .3f,
            ) }, colorArgb = 0xFF205CA9.toInt(), seq = 1L,
            washFrame = StrokeWashFrame(1.0, 0.0, 17.0, 0.0, 1.0, -23.0, 0.0, 0.0, 1.0),
        ))

        /** Ready for JSONObject(metadata()); report alongside the exact frozen brush JSON/hash. */
        fun metadata(): Map<String, Any> {
            val preset = frozen.preset
            val runtime = preset.watercolourRuntime()
            return mapOf(
                "fixtureSourceId" to sourceId, "fixtureFrozenId" to frozen.id,
                "fixtureSeed" to 78234L, "fixtureRawSamples" to 24,
                "fixtureOperator" to preset.blend, "fixtureSize" to runtime.size,
                "fixtureGrain" to runtime.grain, "fixturePaperCut" to runtime.paperCut,
                "fixtureOverrides" to overrides.map { mapOf("field" to it.field, "before" to it.before, "after" to it.after) },
                "fixtureScope" to "Debug-only transported wash optics: no physical paper/grain, owner-preset appearance, selected cache, fade, View save or recovery acceptance",
            )
        }
    }

    fun prepare(
        source: BrushPreset,
        operator: String = source.blend,
        newId: () -> String = { "ordinary-wc-diagnostic-frozen" },
    ): Prepared {
        require(source.engine == ENGINE_WATERCOLOUR && source.watercolour != null) { "Requires a watercolor source" }
        require(BrushValidate.validate(source).isEmpty()) { "Invalid source preset; fixture must not repair it" }
        require(operator == "normal" || operator == "multiply") { "Ordinary probe supports only normal/multiply" }
        // Detach before deriving: even caller-owned nested mutable lists remain untouched/unshared.
        val detached = BrushJson.decodeChecked(BrushJson.encode(source))
        val runtime = detached.watercolourRuntime()
        val size = runtime.size.coerceAtMost(300.0)
        require(size >= 1.0) { "Ordinary fixture requires size >=1" }
        val changes = arrayListOf<Override>()
        fun numeric(field: String, before: Double, after: Double) {
            if (before != after) changes += Override(field, before.toString(), after.toString())
        }
        numeric("watercolour.grain", runtime.grain, 0.0)
        numeric("watercolour.paperCut", runtime.paperCut, 0.0)
        numeric("runtime.size", runtime.size, size)
        if (detached.blend != operator) changes += Override("blend", detached.blend, operator)
        val derived = detached.copy(
            blend = operator,
            size = detached.size.copy(base = size.toFloat()),
            watercolour = runtime.copy(size = size, grain = 0.0, paperCut = 0.0),
        )
        val frozen = FrozenBrushRegistry.freeze(emptyMap(), derived, newId)
        return Prepared(source.id, frozen, changes)
    }
}
