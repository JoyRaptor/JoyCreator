package cc.joycreator.joybrush.core.media

/**
 * CPU reference for the JB-2.40 §Q1 plain-on-media bake (base/fix, decision 2026-10-07).
 *
 * A payload tile keeps RGBA8 premultiplied ground (`<id>#g`, MediaStores.GROUND) holding the plain
 * pixels under the media; look = render(state over ground). A plain write on the tile bakes the
 * current look into ground within its footprint and clears paint state there.
 *
 * Reference rule (per pixel, coverage = what the plain write changed, 1/pixel):
 * - coverage STRICTLY greater than 1/255 bakes: ground takes the post-write look bytes EXACTLY,
 *   p0/p1/w0/w1 are zeroed (all 4 channels), paper keeps crush (r, raw bits) and zeroes g/b/a.
 * - at or below 1/255 preserves ground and all state raw bits EXACTLY (including signed zero and
 *   NaN payloads when untouched; do not sanitize).
 * - Missing stores stay missing; no media is invented. Inputs are never mutated; outputs are fresh
 *   copies that alias no input array.
 *
 * CPU reference semantics only: does NOT render paint, erase media, settle water, satisfy GPU
 * integration, or complete slice 2. No shader/GL/UI/schema/brush design.
 */
object MediaPlainBake {
    /** Coverage strictly greater than this bakes. Exactly 1/255 as Float. */
    val COVERAGE_THRESHOLD: Float = 1f / 255f

    /** Copied bake result: fresh ground bytes plus fresh arrays for exactly the input store keys. */
    class BakeResult(val ground: ByteArray, val stores: Map<String, FloatArray>)

    /**
     * Bakes a plain write over media state.
     *
     * @param ground RGBA8 premultiplied ground before the write, 4 bytes/pixel.
     * @param look RGBA8 premultiplied post-write look, same length as [ground].
     * @param coverage plain-write coverage per pixel, finite in 0..1.
     * @param stores existing float stores by short name (MediaStores.ALL: p0,p1,paper,w0,w1),
     *   each 4 floats/pixel. Missing stores stay missing.
     * @return fresh [BakeResult] copying inputs; never mutates inputs nor aliases them.
     * @throws IllegalArgumentException on shape mismatch, non-finite/out-of-range coverage,
     *   or unknown store IDs.
     */
    fun bake(
        ground: ByteArray,
        look: ByteArray,
        coverage: FloatArray,
        stores: Map<String, FloatArray> = emptyMap(),
    ): BakeResult {
        require(ground.size % 4 == 0) { "ground must be RGBA8, got ${ground.size} bytes" }
        val pixels = ground.size / 4
        require(look.size == ground.size) { "look must match ground, got ${look.size} vs ${ground.size}" }
        require(coverage.size == pixels) { "coverage must have one value per pixel, got ${coverage.size} vs $pixels" }
        for ((name, values) in stores) {
            require(name in MediaStores.ALL) { "unknown media store $name" }
            require(values.size == pixels * 4) { "store $name must have 4 floats per pixel, got ${values.size} vs ${pixels * 4}" }
        }
        for (i in coverage.indices) {
            val c = coverage[i]
            // NaN and infinities fail the range test, so this also enforces finiteness.
            require(c >= 0f && c <= 1f) { "coverage[$i] must be finite in 0..1, got $c" }
        }

        val outGround = ground.copyOf()
        val outStores = LinkedHashMap<String, FloatArray>(stores.size)
        for ((name, values) in stores) {
            outStores[name] = values.copyOf()
        }

        for (i in 0 until pixels) {
            if (coverage[i] > COVERAGE_THRESHOLD) {
                val b = i * 4
                outGround[b] = look[b]
                outGround[b + 1] = look[b + 1]
                outGround[b + 2] = look[b + 2]
                outGround[b + 3] = look[b + 3]
                for ((name, out) in outStores) {
                    val f = i * 4
                    if (name == "paper") {
                        // Crush (r) stays bit-exact; flakes (g/b) and reflectance sum stay cleared.
                        out[f + 1] = 0f
                        out[f + 2] = 0f
                        out[f + 3] = 0f
                    } else {
                        out[f] = 0f
                        out[f + 1] = 0f
                        out[f + 2] = 0f
                        out[f + 3] = 0f
                    }
                }
            }
        }
        return BakeResult(outGround, outStores)
    }
}
