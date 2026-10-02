package cc.joycreator.joybrush.core.brush

/**
 * Spatial pickup at a single destination pixel, with no additional carried store. The sampled source
 * is premultiplied pre-stroke paint; unpremultiplying its RGB and applying the carried alpha preserves
 * streak colour without importing an empty source's black RGB or changing the brush's coverage.
 * This is a CPU reference for the fragment shader, not within-stroke transport or an impasto solver.
 */
object PaintPickup {
    fun mix(
        carriedR: Float, carriedG: Float, carriedB: Float, carriedA: Float,
        sampledR: Float, sampledG: Float, sampledB: Float, sampledA: Float,
        amount: Float, out: FloatArray,
    ) {
        require(out.size >= 4)
        require(amount in 0f..1f) { "texture pickup amount must be finite and in 0..1" }
        require(listOf(carriedR, carriedG, carriedB, carriedA, sampledR, sampledG, sampledB, sampledA)
            .all { it in 0f..1f }) { "pickup colors must be finite and in 0..1" }
        require(carriedR <= carriedA && carriedG <= carriedA && carriedB <= carriedA &&
            sampledR <= sampledA && sampledG <= sampledA && sampledB <= sampledA) {
            "pickup colors must be premultiplied"
        }
        val weight = amount * sampledA
        if (sampledA == 0f || weight == 0f) {
            out[0] = carriedR; out[1] = carriedG; out[2] = carriedB; out[3] = carriedA
            return
        }
        out[0] = carriedR + ((sampledR / sampledA) * carriedA - carriedR) * weight
        out[1] = carriedG + ((sampledG / sampledA) * carriedA - carriedG) * weight
        out[2] = carriedB + ((sampledB / sampledA) * carriedA - carriedB) * weight
        out[3] = carriedA
    }
}
