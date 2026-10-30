package cc.joycreator.joybrush.core.brush

/** Exact logical paths needed by modern rendering; old opaque import payloads are not PNGs. */
object BrushAssetPaths {
    /** Ordered source uses count repeated primary images and the secondary image conservatively. */
    fun ordered(preset: BrushPreset): List<String> = preset.tip.sampled?.images.orEmpty() +
        listOfNotNull(preset.tip.dual?.image?.image)
    fun required(preset: BrushPreset): List<String> = ordered(preset).distinct()
}
