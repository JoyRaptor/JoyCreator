package cc.joycreator.joybrush.core.brush

/** Exact logical paths needed by modern rendering; old opaque import payloads are not PNGs. */
object BrushAssetPaths {
    fun required(preset: BrushPreset): List<String> = preset.tip.sampled?.images.orEmpty().distinct()
}
