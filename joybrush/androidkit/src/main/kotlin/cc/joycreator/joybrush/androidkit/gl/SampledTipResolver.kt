package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import cc.joycreator.joybrush.core.brush.SampledTipSet
import cc.joycreator.joybrush.core.brush.SampledTipSpec

/** One decoded brush set, independent of GL. Immutable byte-owner identity prevents stale image reuse. */
class SampledTipResolver(
    private val decode: (PreparedPngSamples, SampledTipSpec) -> SampledTipSet = AndroidSamplePngDecoder::resolve,
) {
    private var source: FrozenBrushPngs? = null
    private var decoded: SampledTipSet? = null

    /** Prepare on brush selection/load before contact. No installed-library fallback exists here. */
    fun resolve(preset: BrushPreset, images: FrozenBrushPngs?): SampledTipSet? {
        val spec = preset.tip.sampled ?: return null
        val pngs = images ?: throw BrushException("The sampled brush is missing its frozen PNG resources")
        decoded?.takeIf { source === pngs && it.matches(spec) }?.let { return it }
        val snapshot = spec.copy(images = spec.images.toList())
        // Preflight reserves every ordered source pixel before any bitmap decode is entered.
        val prepared = PngSamplePreflight.read(snapshot, { path -> pngs.png(path)?.inputStream() })
        val made = decode(prepared, snapshot)
        if (!made.matches(snapshot)) throw BrushException("The decoded image set does not match this sampled brush")
        source = pngs; decoded = made
        return made
    }

    fun heldMaskBytes(): Long = decoded?.let { set ->
        (0 until set.size).sumOf { set.mask(it).width.toLong() * set.mask(it).height }
    } ?: 0L

    fun clear() { source = null; decoded = null }
}
