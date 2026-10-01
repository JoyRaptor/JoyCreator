package cc.joycreator.joybrush.core.paper

import kotlin.math.roundToInt

/**
 * An imported brush's own texture becomes one of our surfaces (JB-9.11).
 *
 * Every paper surface in this app is a height map, and every texture an imported brush brings is a
 * height map too: a Photoshop pattern, a Procreate `Grain.png`, a Krita grain. The importers already
 * KEEP those bytes (R40, "kept but not drawn yet"), and what they could not do was give them
 * **slopes** — which way each spot faces — so a dry brush can catch the faces that meet the stroke.
 * That is the owner's P4: a Photoshop brush loaded in here works better than in its native app,
 * because a normal map is derived for it instead of left to the source app.
 *
 * This object is the conversion, once, for every importer. The arithmetic is not here: it is
 * [SurfaceMaps] (JB-9.01), the Kotlin twin of `joybrush/tools/paper/pack.py`, so an imported texture
 * and a shipped paper surface are byte-for-byte the same kind of thing. What lives here is only what
 * an IMPORT knows and a paper asset does not:
 *
 *  - the source app's **Invert** flag, which is a property of the file, not of our surface;
 *  - the fact that a **colour** pattern has to become greyscale before it can be a height at all;
 *  - the fact that the source size is kept (Decision 3 — `GrainTextures` handles any size, and a
 *    rescaled texture is a different texture).
 *
 * It draws nothing (that is JB-1.05d and JB-9.08) and it stores nothing: where the packed bytes go
 * is open under the spec's Questions, and the 256 KiB `extensions` budget may not have room for
 * them. The height convention per source app is open there too — this function takes the convention
 * as its `invert` argument and does not second-guess it.
 */
object ImportedTexture {

    /**
     * Any imported greyscale pattern (`w*h` bytes, any size ≥ 3, tileable as the source app tiles it)
     * → a packed surface. `invert` is the source app's "Invert" flag: the height bytes become
     * `255 − b`, which is also what negates the slopes, because a gradient of `255 − b` is the
     * negation of a gradient of `b` scaled by the same divisor.
     *
     * Returns the RGBA bytes plus the `slopeRange` used ([SurfaceMaps.defaultSlopeRange]), because a
     * caller that binds the surface to a shader needs the range to decode `.rg` and cannot invent it.
     *
     * A pattern that is one value all over has no slope at all, and gets Decision 4's `0.001`: the
     * range is a floor [SurfaceMaps] already applies, so a flat texture packs to a valid surface with
     * `R = G = 128` instead of a range no packer will accept. Flagging it in the import report is the
     * importer's job, and is open under the spec's Questions.
     *
     * The Scharr pass runs twice — once here to take the range from, once inside [SurfaceMaps.pack]
     * for the bytes themselves. That is deliberate: the layout is written in exactly one place, so a
     * change to it cannot drift between this row and JB-9.01, and a 512² texture costs a few
     * milliseconds of arithmetic once per import.
     */
    fun toSurface(grey: ByteArray, w: Int, h: Int, invert: Boolean): Surface {
        require(w >= 3 && h >= 3) { "an imported texture needs at least 3x3 to have slopes, got ${w}x$h" }
        require(grey.size == w * h) { "grey has ${grey.size} bytes, but ${w}x$h needs ${w * h}" }
        val heightBytes = if (invert) {
            ByteArray(grey.size) { i -> (255 - grey[i].toInt().and(0xFF)).toByte() }
        } else {
            grey.copyOf()
        }
        val height = FloatArray(heightBytes.size) { i -> heightBytes[i].toInt().and(0xFF) / 255f }
        val (dx, dy) = SurfaceMaps.slopes(height, w, h)
        val slopeRange = SurfaceMaps.defaultSlopeRange(dx, dy)
        return Surface(w, h, SurfaceMaps.pack(heightBytes, w, h, slopeRange), slopeRange)
    }

    /**
     * One packed surface: the source size, the RGBA8 bytes in [SurfaceMaps]'s layout
     * (`R = dx`, `G = dy`, `B = the height byte`, `A = h²`), and the `slopeRange` the two slope
     * channels were encoded against.
     *
     * `rgba` is `w*h*4` bytes, row-major, row 0 = top — the same layout `pack.py` writes and
     * `jb_paper.glsl` reads, so a brush that brings its own paper is indistinguishable downstream
     * from a paper the owner chose.
     */
    class Surface(val w: Int, val h: Int, val rgba: ByteArray, val slopeRange: Float)

    /**
     * Colour patterns to greyscale: `luminance = 0.2126 R + 0.7152 G + 0.0722 B` on sRGB bytes,
     * rounded. An ABR pattern can be RGB, and a height map has one channel, so this is where a
     * colour texture is reduced — before [toSurface], never inside it, so B still round-trips the
     * byte this produced.
     *
     * The coefficients are the sRGB luma weights (Rec. 709), which is what the eye answers to; they
     * are the same three numbers Photoshop and Krita use to make a pattern greyscale. **Interleaved
     * RGB triples, three bytes a texel** — but note that a Photoshop `.pat` file stores its channels
     * PLANAR, all the reds of a row and then all the greens, so whoever decodes a `patt` has to
     * repack it before calling this. That repack is part of the open question about ABR, not of here.
     */
    fun luminance(rgb: ByteArray, w: Int, h: Int): ByteArray {
        require(w >= 1 && h >= 1) { "a pattern has at least one texel, got ${w}x$h" }
        require(rgb.size == w * h * 3) { "rgb has ${rgb.size} bytes, but ${w}x$h RGB needs ${w * h * 3}" }
        val grey = ByteArray(w * h)
        for (i in grey.indices) {
            val r = rgb[i * 3].toInt().and(0xFF)
            val g = rgb[i * 3 + 1].toInt().and(0xFF)
            val b = rgb[i * 3 + 2].toInt().and(0xFF)
            grey[i] = (0.2126 * r + 0.7152 * g + 0.0722 * b).roundToInt().coerceIn(0, 255).toByte()
        }
        return grey
    }
}
