package cc.joycreator.joybrush.core.paper

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The no-repeat read, on a hexagonal lattice. This is the CANONICAL maths for reading a paper texture
 * (Mikkelsen 2022, JCGT 11(3)); `joybrush/shaders/jb_paper.glsl` is its line-for-line twin, and any
 * change to one is made to the other in the same commit. It is also how the CPU renders paper for PNG
 * export (JB-9.06), which is why this lives in core and not next to the shader.
 *
 * Why three reads and not one: a single read of a tiling texture repeats every `tex.w` texels, and at
 * 43 px the eye reads that as a grid. So every hex of a lattice gets its OWN random offset into the
 * texture (and, for a paper with no grain direction, its own rotation), and the point is the blend of
 * the three hexes around it. Because a hex's read is offset and turned, no two lattice neighbours ever
 * show the same patch, and the period the eye could latch onto is gone. Blending SLOPES rather than
 * normals is what makes this work: slopes are linear, so a convex blend of three rotated, offset
 * patches is still a slope field, with no crease at the hex borders.
 *
 * Why the hex centre is a named function here and not a literal: the same `H·(i + j/2, j·√3/2)` appears
 * in this row twice, once in [centreX]/[centreY] and once written out inline in
 * `joybrush/shaders/jb_paper.glsl`. Only this side can be tested without a GPU — `HexTileTest` calls
 * [centreX] and [centreY] rather than writing the formula out a third time — so if the two ever drift,
 * this side is the one that notices and says so.
 */
object HexTile {

    /**
     * Weight contrast: `w^HEX_GAMMA`, renormalised. High enough to keep the paper crisp, low enough to
     * leave no seam. It is a WHOLE NUMBER, because [gammaWeights] applies it as a count of multiplies
     * and refuses anything else rather than truncating it to one.
     */
    const val HEX_GAMMA = 3f

    /** = sqrt(3.0): the hex lattice is only regular at this value, and it is the shader's constant too. */
    val SQRT3: Double = kotlin.math.sqrt(3.0)

    private val TAU = 2.0 * PI

    /**
     * Integer hash → [0, 1). lowbias32 (Wellons); identical in GLSL ES 3.00 with uint maths, which is
     * why this is not `fract(sin(i * 12.9898))`: `sin` differs between GPUs, and the two sides of the twin
     * would then disagree. 24 bits of output, so the value is exact in a float.
     *
     * `shr` on a `UInt` is the GLSL `>>>`: unsigned types have no sign bit to extend, so the logical and
     * arithmetic shifts are the same operation. The shader writes `x >> 16u`.
     */
    fun hash(i: Int, j: Int, k: Int): Float {
        var x = i.toUInt() * 73856093u
        x = x xor (j.toUInt() * 19349663u)
        x = x xor (k.toUInt() * 83492791u)
        x = x xor (x shr 16)
        x *= 0x7feb352du
        x = x xor (x shr 15)
        x *= 0x846ca68bu
        x = x xor (x shr 16)
        return (x shr 8).toFloat() / 16777216f
    }

    /** The three lattice vertices around texel point (px, py), and their weights (sum 1, BEFORE gamma). */
    class Lattice(val vi: IntArray, val vj: IntArray, val w: FloatArray)

    /**
     * `lattice(p, H)` in the spec's notation: a shear that turns the hex lattice into the unit square's
     * triangulation, so the three neighbours are the same three for every point.
     *
     * [hexTexels] is the divisor, so it is the one argument that has to be checked. At 0 each quotient
     * is an infinity where the numerator is non-zero and a NaN where it is not, `floor` of an infinity
     * saturates, and `Double.toInt()` of it is `Int.MAX_VALUE` or `Int.MIN_VALUE` while `Double.toInt()`
     * of a NaN is 0 (JLS 5.1.3). None of those throw. So this used to hand back hexes with indices at
     * the ends of the integer range, or all three at the origin, and the read went on to return a
     * plausible finite sample of the wrong place. A NaN divisor and the two infinities get in the same
     * way. Every path into the division arrives here first — [sampleSurface] and [sampleLook] both call
     * this before anything else — so the guard is one `require` and it names the argument, as the guards
     * in `SurfaceMaps` do.
     */
    fun lattice(px: Double, py: Double, hexTexels: Double): Lattice {
        require(hexTexels.isFinite() && hexTexels > 0.0) {
            "hexTexels must be a positive finite number, it is what the lattice divides by, was $hexTexels"
        }
        val qx = px / hexTexels
        val qy = py / hexTexels
        val a = qx - qy / SQRT3
        val b = 2.0 * qy / SQRT3
        val i0 = floor(a)
        val j0 = floor(b)
        val fa = a - i0
        val fb = b - j0
        val i = i0.toInt()
        val j = j0.toInt()
        return if (fa + fb > 1.0) {
            Lattice(
                intArrayOf(i + 1, i + 1, i),
                intArrayOf(j + 1, j, j + 1),
                floatArrayOf((fa + fb - 1.0).toFloat(), (1.0 - fb).toFloat(), (1.0 - fa).toFloat()),
            )
        } else {
            // `1 - (fa + fb)`, not `(1 - fa) - fb`: the parenthesised form is the one that cannot come
            // out a hair below zero, and a weight of -1e-8 fails a "weights are never negative" test
            // for no reason a reader of the maths would want.
            Lattice(
                intArrayOf(i, i + 1, i),
                intArrayOf(j, j, j + 1),
                floatArrayOf((1.0 - (fa + fb)).toFloat(), fa.toFloat(), fb.toFloat()),
            )
        }
    }

    /**
     * The centre of hex (i, j), in texels: `H · (i + j/2, j·√3/2)`, the x half of the spec's maths block
     * line 66. `j / 2.0` and not `j / 2`, so a negative odd j keeps its half.
     *
     * The same formula is written out inline in `joybrush/shaders/jb_paper.glsl`, which no JVM test can
     * reach. `HexTileTest` calls this function rather than restating it, so the value is pinned here and
     * a change to the shader alone is the one that can go unnoticed.
     */
    fun centreX(i: Int, j: Int, hexTexels: Double): Double = hexTexels * (i + j / 2.0)

    /** The y half of the same centre: `H · (j·√3/2)`. */
    fun centreY(i: Int, j: Int, hexTexels: Double): Double = hexTexels * (j * SQRT3 / 2.0)

    /**
     * Sample [tex] at TEXEL point (px, py), no visible repeat.
     * Returns, into [out]: [0] = dh/dx, [1] = dh/dy  (height per TEXEL, in the PAPER's frame, i.e. un-rotated),
     *                      [2] = height 0..1,   [3] = height² 0..1.
     * [slopeRange] decodes R,G (SurfaceMaps.decodeFilteredSlope). [rotatable] = false → no per-hex rotation
     * (weaves, laid lines, papyrus: a weave that turned on its own would lose its grain direction).
     */
    fun sampleSurface(
        tex: PaperTexture,
        px: Double,
        py: Double,
        hexTexels: Double,
        rotatable: Boolean,
        slopeRange: Float,
        out: FloatArray,
        seed: Int = 0,
        footprint: Double = 1.0,
        heightMean: Float = 0.5f,
    ) {
        require(out.size >= 4) { "sampleSurface needs an output array of at least 4 floats, got ${out.size}" }
        val l = lattice(px, py, hexTexels)
        val w = gammaWeights(l.w)
        val keep = contrastKeep(w)
        val s = FloatArray(4)
        val contribution = FloatArray(4)
        var spread = 0f
        // The shader's accumulator starts at vec4(0.0); an out array the caller has used before is
        // overwritten rather than added to, so a second call cannot inherit the first one's answer.
        for (q in 0..3) out[q] = 0f
        for (n in 0..2) {
            readAt(tex, px, py, hexTexels, l.vi[n], l.vj[n], rotatable, s, seed, footprint)
            contribution[0] = SurfaceMaps.decodeFilteredSlope(s[0], slopeRange)
            contribution[1] = SurfaceMaps.decodeFilteredSlope(s[1], slopeRange)
            if (rotatable) {
                // R(-θ) turns the slope back into the paper's own frame. Without it a rotated hex would
                // face the wrong way, and the directional deposit of R10 §5 would be wrong with it.
                val theta = rotation(l.vi[n], l.vj[n], seed)
                val c = cos(theta)
                val sn = sin(theta)
                val sx = contribution[0].toDouble()
                val sy = contribution[1].toDouble()
                contribution[0] = (c * sx + sn * sy).toFloat()
                contribution[1] = (-sn * sx + c * sy).toFloat()
            }
            contribution[2] = s[2] - heightMean
            contribution[3] = 0f
            for (q in 0..3) out[q] += w[n] * contribution[q]
            spread += w[n] * w[n] * (s[3] - s[2] * s[2]).coerceAtLeast(0f)
        }
        // Variance-preserving: slopes and height keep their contrast across the blend. A = E[h²] stays consistent with the
        // blend's mean: B² plus the reads' leftover (mip) variance weighted as the blend weights them, so A − B² is a valid
        // local variance at every mip — the same as jb_paper.glsl.
        out[0] *= keep
        out[1] *= keep
        out[2] = (heightMean + out[2] * keep).coerceIn(0f, 1f)
        out[3] = out[2] * out[2] + spread * keep * keep
    }

    /** Same lattice, offsets and weights, for a LOOK texture (RGB colour; A ignored). Returns r,g,b 0..1 into [out]. */
    fun sampleLook(tex: PaperTexture, px: Double, py: Double, hexTexels: Double, rotatable: Boolean, out: FloatArray, seed: Int = 0,
                   footprint: Double = 1.0, mean: FloatArray? = null) {
        require(out.size >= 3) { "sampleLook needs an output array of at least 3 floats, got ${out.size}" }
        val l = lattice(px, py, hexTexels)
        val w = gammaWeights(l.w)
        val keep = if (mean != null) contrastKeep(w) else 1f
        val s = FloatArray(4)
        for (q in 0..2) out[q] = 0f
        for (n in 0..2) {
            readAt(tex, px, py, hexTexels, l.vi[n], l.vj[n], rotatable, s, seed, footprint)
            for (q in 0..2) out[q] += w[n] * (s[q] - (mean?.get(q) ?: 0f))
        }
        if (mean != null) for (q in 0..2) out[q] = (mean[q] + out[q] * keep).coerceIn(0f, 1f)
    }

    /**
     * The FLUID map at TEXEL point (px, py) of the fluid texture: [0] = absorbency, [1],[2] = fibre direction as
     * (cos2θ, sin2θ)·coherence in the DOCUMENT's frame (signed, −1..1), [3] = pore capacity. A rotated hex turns its
     * fibres by twice its angle (the double-angle form), so a direction always means the same on the page. R and A keep
     * their contrast around 0.5 (variance-preserving); the direction blends plainly, so where differently-turned patches
     * meet the coherence drops — fibres crossing, which is what they do. Twin of jb_paperFluid in jb_paper.glsl.
     */
    fun sampleFluid(tex: PaperTexture, px: Double, py: Double, hexTexels: Double, rotatable: Boolean, out: FloatArray,
                    seed: Int = 0, footprint: Double = 1.0) {
        require(out.size >= 4) { "sampleFluid needs an output array of at least 4 floats, got ${out.size}" }
        val l = lattice(px, py, hexTexels)
        val w = gammaWeights(l.w)
        val keep = contrastKeep(w)
        val s = FloatArray(4)
        for (q in 0..3) out[q] = 0f
        for (n in 0..2) {
            readAt(tex, px, py, hexTexels, l.vi[n], l.vj[n], rotatable, s, seed, footprint)
            var c2 = s[1] * 2f - 1f
            var s2 = s[2] * 2f - 1f
            if (rotatable) {
                // The read turned the paper by θ; turn its fibres back into the page's frame: double angle, so 2θ.
                val t = -2.0 * rotation(l.vi[n], l.vj[n], seed)
                val c = cos(t).toFloat(); val sn = sin(t).toFloat()
                val rc = c * c2 - sn * s2
                val rs = sn * c2 + c * s2
                c2 = rc; s2 = rs
            }
            out[0] += w[n] * (s[0] - 0.5f)
            out[1] += w[n] * c2
            out[2] += w[n] * s2
            out[3] += w[n] * (s[3] - 0.5f)
        }
        out[0] = (0.5f + out[0] * keep).coerceIn(0f, 1f)
        out[3] = (0.5f + out[3] * keep).coerceIn(0f, 1f)
    }

    /**
     * `1 / sqrt(Σ w²)`: how much a blend of three reads must be scaled about its mean to keep the contrast of ONE read
     * (variance-preserving blending, Heitz & Neyret 2018). A plain average of three different patches of paper is flatter
     * than any one of them, so a convex blend left soft, washed-out blotches wherever the hexes met; with photographic
     * papers (2026-10-06) those blotches were the most visible thing on the page. This supersedes JB-9.02 Decision 2.
     * One read alone (a weight of 1) gives exactly 1, so the middle of a hex is untouched.
     */
    internal fun contrastKeep(w: FloatArray): Float {
        val s = w[0] * w[0] + w[1] * w[1] + w[2] * w[2]
        return if (s > 0f) (1.0 / sqrt(s.toDouble())).toFloat() else 1f
    }

    /**
     * `w^gamma`, renormalised, which is the spec's `w'_k = w_k^HEX_GAMMA / Σ w^HEX_GAMMA`.
     *
     * [gamma] is READ, not written out as `w*w*w`. The spec's contract names `HEX_GAMMA` as the
     * weight-contrast knob (JB-9.02 line 30) and its maths block uses the name, so a public constant
     * that nothing reads is a knob that lies to the next reader. It is applied as a whole number of
     * multiplies rather than through `Math.pow`, so gamma 3 comes out bit-for-bit as the `w*w*w` this
     * row shipped with — `1f·w` is exactly `w`, so the loop is `(w·w)·w` — and the blend does not drift
     * by an ulp when nobody has touched the knob. A gamma that is not a whole number is refused rather
     * than truncated, because a knob that silently rounds is worse than a knob that is dead.
     *
     * This is a CONVEX blend of all four channels, with no variance-preserving formula, because that is
     * Decision 2 of JB-9.02. Note what that does and does not give: a convex blend keeps A and B a
     * valid mean and a valid second moment, but `A − B²` is NOT a local variance and must not be read as
     * one — see `SurfaceMaps.pack` for the arithmetic and for the sign a consumer has to clamp.
     *
     * Visible to this module's tests so the contrast can be pinned. It is not part of the paper read's
     * contract and nothing outside this object should call it.
     */
    internal fun gammaWeights(w: FloatArray, gamma: Float = HEX_GAMMA): FloatArray {
        val steps = gamma.toInt()
        require(steps >= 0 && steps.toFloat() == gamma) {
            "HEX_GAMMA must be a whole number of multiplies, was $gamma"
        }
        val out = FloatArray(3)
        var total = 0f
        for (k in 0..2) {
            var g = 1f
            for (n in 0 until steps) g *= w[k]
            out[k] = g
            total += g
        }
        for (k in 0..2) out[k] = if (total > 0f) out[k] / total else 1f / 3f
        return out
    }

    /**
     * One hex's read: its own random offset into the texture, its own rotation if [rotatable], and a
     * wrapping bilinear sample of the four channels. `R(θ)·(p - c) + c + offset` turns the read about the
     * hex's own centre and then moves it, so a hex is a window onto a different patch every time.
     */
    private fun readAt(
        tex: PaperTexture,
        px: Double,
        py: Double,
        hexTexels: Double,
        i: Int,
        j: Int,
        rotatable: Boolean,
        out: FloatArray,
        seed: Int,
        footprint: Double,
    ) {
        val cxp = centreX(i, j, hexTexels)
        val cyp = centreY(i, j, hexTexels)
        val ox = hash(i, j, 1 + seed) * tex.w
        val oy = hash(i, j, 2 + seed) * tex.h
        if (!rotatable) {
            tex.filtered(px + ox, py + oy, footprint, out)
            return
        }
        val theta = rotation(i, j, seed)
        val c = cos(theta)
        val s = sin(theta)
        val dx = px - cxp
        val dy = py - cyp
        tex.filtered(c * dx - s * dy + cxp + ox, s * dx + c * dy + cyp + oy, footprint, out)
    }

    /** A hex's own turn: [rotatable] papers get one, weaves and laid lines do not. */
    private fun rotation(i: Int, j: Int, seed: Int): Double = hash(i, j, 3 + seed) * TAU

}
