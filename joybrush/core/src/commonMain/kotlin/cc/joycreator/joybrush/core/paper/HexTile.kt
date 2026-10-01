package cc.joycreator.joybrush.core.paper

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

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
 */
object HexTile {

    /** Weight contrast: `w^3`, renormalised. High enough to keep the paper crisp, low enough to leave no seam. */
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
     */
    fun lattice(px: Double, py: Double, hexTexels: Double): Lattice {
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

    /** The centre of hex (i, j), in texels: `H · (i + j/2, j·√3/2)`. */
    fun centreX(i: Int, j: Int, hexTexels: Double): Double = hexTexels * (i + j / 2.0)

    fun centreY(i: Int, j: Int, hexTexels: Double): Double = hexTexels * (j * SQRT3 / 2.0)

    /**
     * Sample [tex] at TEXEL point (px, py), no visible repeat.
     * Returns, into [out]: [0] = dh/dx, [1] = dh/dy  (height per TEXEL, in the PAPER's frame, i.e. un-rotated),
     *                      [2] = height 0..1,   [3] = height² 0..1.
     * [slopeRange] decodes R,G (SurfaceMaps.decodeSlope). [rotatable] = false → no per-hex rotation
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
    ) {
        require(out.size >= 4) { "sampleSurface needs an output array of at least 4 floats, got ${out.size}" }
        val l = lattice(px, py, hexTexels)
        val w = gammaWeights(l.w)
        val s = FloatArray(4)
        val contribution = FloatArray(4)
        // The shader's accumulator starts at vec4(0.0); an out array the caller has used before is
        // overwritten rather than added to, so a second call cannot inherit the first one's answer.
        for (q in 0..3) out[q] = 0f
        for (n in 0..2) {
            readAt(tex, px, py, hexTexels, l.vi[n], l.vj[n], rotatable, s, seed)
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
            contribution[2] = s[2]
            contribution[3] = s[3]
            for (q in 0..3) out[q] += w[n] * contribution[q]
        }
    }

    /** Same lattice, offsets and weights, for a LOOK texture (RGB colour; A ignored). Returns r,g,b 0..1 into [out]. */
    fun sampleLook(tex: PaperTexture, px: Double, py: Double, hexTexels: Double, rotatable: Boolean, out: FloatArray, seed: Int = 0) {
        require(out.size >= 3) { "sampleLook needs an output array of at least 3 floats, got ${out.size}" }
        val l = lattice(px, py, hexTexels)
        val w = gammaWeights(l.w)
        val s = FloatArray(4)
        for (q in 0..2) out[q] = 0f
        for (n in 0..2) {
            readAt(tex, px, py, hexTexels, l.vi[n], l.vj[n], rotatable, s, seed)
            for (q in 0..2) out[q] += w[n] * s[q]
        }
    }

    /**
     * `w^HEX_GAMMA`, renormalised. A convex blend of all four channels, with no variance-preserving
     * formula: A - B² has to stay a real variance, because the zoom maths in JB-9.06 reads roughness
     * out of it.
     */
    private fun gammaWeights(w: FloatArray): FloatArray {
        val out = FloatArray(3)
        var total = 0f
        for (k in 0..2) {
            out[k] = w[k] * w[k] * w[k]
            total += out[k]
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
    ) {
        val cxp = centreX(i, j, hexTexels)
        val cyp = centreY(i, j, hexTexels)
        val ox = hash(i, j, 1 + seed) * tex.w
        val oy = hash(i, j, 2 + seed) * tex.h
        if (!rotatable) {
            tex.bilinear(px + ox, py + oy, out)
            return
        }
        val theta = rotation(i, j, seed)
        val c = cos(theta)
        val s = sin(theta)
        val dx = px - cxp
        val dy = py - cyp
        tex.bilinear(c * dx - s * dy + cxp + ox, s * dx + c * dy + cyp + oy, out)
    }

    /** A hex's own turn: [rotatable] papers get one, weaves and laid lines do not. */
    private fun rotation(i: Int, j: Int, seed: Int): Double = hash(i, j, 3 + seed) * TAU

}
