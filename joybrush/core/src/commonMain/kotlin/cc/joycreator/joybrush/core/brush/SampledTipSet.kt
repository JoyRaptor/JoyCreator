package cc.joycreator.joybrush.core.brush

/** Ordered, immutable decoded masks for one frozen brush; shared by all its placed/scattered dabs. */
class SampledTipSet(paths: List<String>, masks: List<ImageMask>,
                    val coverage: SampleCoverage = SampleCoverage.alpha, val invert: Boolean = false) {
    private val names = paths.toList()
    private val images = masks.toList()
    val size: Int get() = images.size
    init {
        if (names.size !in 1..SampledTips.MAX_IMAGES || names.size != images.size || names.any { !SampledTips.safePath(it) })
            throw BrushException("A sampled tip needs all its ordered image masks")
        if (images.sumOf { it.width.toLong() * it.height } > SampleMaskLimits.MAX_TOTAL_SOURCE_PIXELS)
            throw BrushException("The sampled tip set exceeds the decoded pixel budget")
    }
    fun paths(): List<String> = names.toList()
    fun matches(recipe: SampledTipSpec): Boolean = names == recipe.images && coverage == recipe.coverage && invert == recipe.invert
    fun mask(index: Int): ImageMask = images[index]
    fun atlas(maxTextureSize: Int): SampleMaskAtlas = SampleMaskAtlas.pack(images, maxTextureSize)
}

/** Resolved per-dab selection. A mask's immutable pixels are never copied for placement/scatter. */
data class SampledStamp(val set: SampledTipSet, val index: Int, val minPx: Float) {
    init {
        require(index in 0 until set.size)
        require(minPx.isFinite() && minPx >= 0f)
    }
    val mask: ImageMask get() = set.mask(index)
}
