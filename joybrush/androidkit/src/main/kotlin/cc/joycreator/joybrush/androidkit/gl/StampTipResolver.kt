package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.io.FrozenBrushAssets
import cc.joycreator.joybrush.core.brush.*

/** One brush's decoded sources plus one stroke's bake. UI and GL threads need separate owners. */
class StampTipResolver(
    decode: (PreparedPngSamples, SampledTipSpec) -> SampledTipSet = AndroidSamplePngDecoder::resolve,
) {
    private val primary = SampledTipResolver(decode)
    private val secondary = SampledTipResolver(decode)
    private var source: FrozenBrushPngs? = null
    private var primaryRecipe: SampledTipSpec? = null
    private var secondaryRecipe: DualImageSpec? = null
    private var sourcesReady = false
    private var primarySet: SampledTipSet? = null
    private var secondarySet: SampledTipSet? = null
    private var mask: DualMask? = null

    fun resolve(preset: BrushPreset, pngs: FrozenBrushPngs?, seed: Long): StampTipResources {
        val tip = preset.tip
        if (!sourcesReady || source !== pngs || primaryRecipe != tip.sampled || secondaryRecipe != tip.dual?.image) {
            clear()
            try {
                // Admit the COMPLETE source closure (including repeated images and the dual)
                // before either decoder can allocate a Bitmap. Encoded bytes are also bounded.
                val assets = if (BrushAssetPaths.required(preset).isEmpty()) emptyMap()
                    else mapOf(preset.id to (pngs ?: throw BrushException("The brush needs its frozen PNG resources")))
                FrozenBrushAssets.prepare(mapOf(preset.id to preset), assets, retainEntries = false)
                primarySet = primary.resolve(preset, pngs)
                secondarySet = tip.dual?.image?.let { image ->
                    secondary.resolve(preset.copy(tip = TipSpec(sampled = image.sampledRecipe())), pngs)
                }
                source = pngs
                primaryRecipe = tip.sampled?.let { it.copy(images = it.images.toList()) }
                secondaryRecipe = tip.dual?.image
                sourcesReady = true
            } catch (failure: Throwable) { clear(); throw failure }
        }
        val dual = tip.dual
        if (dual == null) mask = null
        else if (mask?.matches(dual, seed) != true) {
            mask = null // Never retain the previous stroke's bake during a replacement bake.
            mask = DualTips.bake(dual, seed, secondarySet)
        }
        return StampTipResources(primarySet, mask)
    }

    fun heldMaskBytes(): Long = primary.heldMaskBytes() + secondary.heldMaskBytes() +
        (mask?.let { it.width.toLong() * it.height } ?: 0L)

    fun clear() {
        source = null; primaryRecipe = null; secondaryRecipe = null; sourcesReady = false
        primarySet = null; secondarySet = null; mask = null
        primary.clear(); secondary.clear()
    }
}
