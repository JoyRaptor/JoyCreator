package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Moving premultiplied RGBA8 tiles through a [Homography], and splitting them by a [SelectionMask]
 * first. The arithmetic the Studio's `TransformOverlayView` needs underneath it, and nothing else:
 * no handles, no touch, no GL.
 *
 * TILES ARE THE UNIT, and the unit is [Tiles.SIZE] x [Tiles.SIZE] x 4 bytes, PREMULTIPLIED, row 0 =
 * the tile's top document row, keyed by `Tiles.key` — the same layout `GlPaintEngine.readTile` and
 * `replaceTiles` speak, so a warped result can be handed to the undo step without a repack.
 *
 * WHY PREMULTIPLIED, EVERYWHERE INCLUDING THE FILTERS. Interpolating straight RGBA and
 * re-premultiplying afterwards gives a colour brighter than its own alpha at every soft edge, and
 * an un-premultiply of that runs away. Interpolating the premultiplied channels is what `jb_tile.frag`
 * does when the GPU magnifies, so a warp and a zoom agree, and interpolating the ALPHA with them is
 * what keeps a semi-transparent dab from gaining a rim on the way through.
 *
 * THREE REFUSALS, ALL OF THEM WORDS. A projective map that throws a tile corner to infinity, a
 * matrix with no inverse, and a destination too large to allocate each produce an EMPTY result
 * rather than a NaN byte, because a NaN written into a tile is read back by the undo log, by the
 * archive writer and by a person three gestures later, and none of the three can say where it came
 * from. The Studio overlay never produces the first of these from a convex quad; a caller outside
 * it might.
 */
object Resample {

    /** How a destination pixel is filled from the source. */
    enum class Filter {
        /** The one source pixel whose square holds the point. Pixel art stays pixel art. */
        NEAREST,

        /** Weighted average of the four source pixels around the point, on premultiplied channels. */
        BILINEAR,
    }

    /**
     * Split [src] by [mask] into what moves and what stays.
     *
     * `lifted = round(v * m / 255)` and `remaining = v - lifted`, per channel, with `v` the
     * premultiplied byte and `m` the coverage. The two are exact complements, so `lifted + remaining`
     * is the original byte in every channel with no rounding at all.
     *
     * The result is a PAIR, not a map of two: the same document pixel is in BOTH maps, because a
     * soft selection is not a partition of the picture — it is a weighting of it. A caller that
     * wants the untouched half draws `remaining` back down; a caller that wants the moved half
     * draws `lifted` through [warp].
     *
     * A tile of [src] that the mask does not mention at all goes entirely into `remaining`, byte
     * for byte. An all-zero tile comes back in neither, because a tile of nothing is not a result.
     */
    fun lift(
        src: Map<Long, ByteArray>,
        mask: SelectionMask,
    ): Pair<Map<Long, ByteArray>, Map<Long, ByteArray>> {
        val lifted = LinkedHashMap<Long, ByteArray>()
        val remaining = LinkedHashMap<Long, ByteArray>()
        for (entry in src) {
            val bytes = entry.value
            require(bytes.size == TILE_BYTES) {
                "tile ${Tiles.tx(entry.key)},${Tiles.ty(entry.key)} is ${bytes.size} bytes, not $TILE_BYTES"
            }
            if (allZero(bytes)) continue
            val coverage = mask.tiles[entry.key]
            if (coverage == null) {
                remaining[entry.key] = bytes.copyOf()
                continue
            }
            val liftedTile = ByteArray(TILE_BYTES)
            val remainingTile = ByteArray(TILE_BYTES)
            for (pixel in 0 until PIXELS_PER_TILE) {
                val m = coverage[pixel].toInt() and 0xFF
                val at = pixel * 4
                if (m == 0) {
                    remainingTile[at] = bytes[at]
                    remainingTile[at + 1] = bytes[at + 1]
                    remainingTile[at + 2] = bytes[at + 2]
                    remainingTile[at + 3] = bytes[at + 3]
                    continue
                }
                for (c in 0 until 4) {
                    val v = bytes[at + c].toInt() and 0xFF
                    // (v * m + 127) / 255 is round(v * m / 255) for integers, and is exact at the
                    // ends: m = 255 gives v, m = 0 gives 0, both without a rounding step.
                    val q = (v * m + 127) / 255
                    liftedTile[at + c] = q.toByte()
                    remainingTile[at + c] = (v - q).toByte()
                }
            }
            if (!allZero(liftedTile)) lifted[entry.key] = liftedTile
            if (!allZero(remainingTile)) remaining[entry.key] = remainingTile
        }
        return Pair(lifted, remaining)
    }

    /**
     * [src] mapped through [h] into FRESH tiles. All-zero tiles are never returned, and a tile in
     * the result never shares an array with a tile in [src].
     *
     * INVERSE MAPPING, and the reason is aliasing. A forward map asks "which destination pixel does
     * this source pixel land on", and the answer is a point, not a pixel: rounding it either leaves
     * holes or doubles pixels depending on which way the rounding went, and a scale of 3 loses two
     * pixels in three. An inverse map asks the other question — for each destination pixel CENTRE
     * (x + 0.5, y + 0.5) map back through `h.inverse()` and sample there — and every destination
     * pixel is filled exactly once because the lattice it lives on is the one being walked.
     *
     * WHICH TILES ARE VISITED is the bounding box of the source's NON-EMPTY tiles mapped through
     * [h], corner by corner. The bounding box rather than the mapped quad itself, because a
     * destination pixel centre is inside the box exactly when the pixel it belongs to is, and the
     * box is four numbers instead of a polygon clip. It over-covers by at most a tile, which costs
     * a memset and saves a scanline clipper per pixel.
     *
     * The box can be too small in one case, and it is stated rather than hidden: a projective map
     * with a pole INSIDE a source tile can send part of that tile's interior outside the box its
     * own corners map to. The pixels that fall outside then arrive through [sample] as "not a
     * point", that is transparent — never as a NaN byte and never as another tile's pixels. A
     * person dragging a corner of a convex quad never gets near that; a document loaded from an
     * archive with a hand-typed matrix might.
     *
     * MINIFICATION. A destination pixel covering more than [MINIFY_SIGMA] source pixels is filled
     * with the average of a 3 x 3 grid of bilinear samples taken across it, rather than one.
     * A point sample of a shrinking image is an aliaser, and the phase decides how it shows: a
     * 1 px checkerboard shrunk to a third and point-sampled lands on one parity and comes back
     * UNIFORMLY WHITE, doubling the brightness of a 64 px brush that has shrunk to 21. Nine samples
     * bring it back to the mean. They cannot remove a moire in general, but they remove the
     * one-frequency beating this produces, and the cost is only paid where it is needed — the
     * factor is measured from the Jacobian at the destination tile's centre, so a document that is
     * scaled up never pays it. NEAREST never pays it at all, because a pixel-art filter that
     * averaged would stop being pixel art.
     *
     * ROUNDING is to nearest, in Double, at the very end, once per channel. Rounding each of the
     * nine samples and averaging the bytes would be a second, different answer.
     *
     * @return the warped tiles, or an EMPTY map when [h] has no inverse, when a source tile's
     *   corner maps to infinity (a perspective beyond the horizon), or when the destination is
     *   wider than [MAX_SELECT_SPAN] px. An empty map is also what an empty [src] gives, so a
     *   caller that must tell those apart has to look at [h] itself; there is no sentinel for it.
     */
    fun warp(src: Map<Long, ByteArray>, h: Homography, filter: Filter): Map<Long, ByteArray> {
        for (entry in src) {
            require(entry.value.size == TILE_BYTES) {
                "tile ${Tiles.tx(entry.key)},${Tiles.ty(entry.key)} is ${entry.value.size} bytes, not $TILE_BYTES"
            }
        }
        val inverse = h.inverse() ?: return emptyMap()

        // The destination bounding box. Computed over the source's NON-EMPTY tiles, because a
        // document that has been scrolled round keeps all-zero tiles around and their corners say
        // nothing about where anything went.
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var seen = false
        for (entry in src) {
            if (allZero(entry.value)) continue
            seen = true
            val originX = Tiles.tx(entry.key).toDouble() * Tiles.SIZE
            val originY = Tiles.ty(entry.key).toDouble() * Tiles.SIZE
            for (corner in CORNER_UNITS) {
                val p = h.apply(
                    Pt(originX + corner.x * Tiles.SIZE, originY + corner.y * Tiles.SIZE),
                )
                if (!p.x.isFinite() || !p.y.isFinite()) return emptyMap()
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
        }
        if (!seen) return emptyMap()

        // Everything is refused BEFORE an Int is made of it. A corner at 1e300 floors to
        // Int.MIN_VALUE, and every index after that is a wrap rather than an answer — the same
        // failure `Lasso.rasterise` is careful about, for the same reason.
        if (abs(minX) > ADDRESS_LIMIT || abs(maxX) > ADDRESS_LIMIT) return emptyMap()
        if (abs(minY) > ADDRESS_LIMIT || abs(maxY) > ADDRESS_LIMIT) return emptyMap()
        if (maxX - minX > MAX_SELECT_SPAN || maxY - minY > MAX_SELECT_SPAN) return emptyMap()

        val tx0 = floor(minX / Tiles.SIZE).toInt()
        val tx1 = floor(maxX / Tiles.SIZE).toInt()
        val ty0 = floor(minY / Tiles.SIZE).toInt()
        val ty1 = floor(maxY / Tiles.SIZE).toInt()

        val out = LinkedHashMap<Long, ByteArray>()
        val one = DoubleArray(4)
        val point = DoubleArray(2)
        val scratch = ByteArray(16)
        for (ty in ty0..ty1) {
            for (tx in tx0..tx1) {
                // The minification factor is a property of the TILE, measured once at its centre,
                // not of the pixel. A document warped by a corner-pin has a factor that changes
                // across itself, and measuring per pixel would cost a 2x2 determinant a quarter of
                // a million times to reach the same answer to within a few percent.
                val minifying = if (filter == Filter.BILINEAR) {
                    val centreX = tx.toDouble() * Tiles.SIZE + Tiles.SIZE / 2.0
                    val centreY = ty.toDouble() * Tiles.SIZE + Tiles.SIZE / 2.0
                    sampleSpan(inverse, centreX, centreY) > MINIFY_SIGMA
                } else {
                    false
                }
                val tile = ByteArray(TILE_BYTES)
                val originX = tx * Tiles.SIZE
                val originY = ty * Tiles.SIZE
                for (row in 0 until Tiles.SIZE) {
                    val dy = (originY + row) + 0.5
                    for (col in 0 until Tiles.SIZE) {
                        val dx = (originX + col) + 0.5
                        val at = (row * Tiles.SIZE + col) * 4
                        if (minifying) {
                            var total = 0.0
                            var totalG = 0.0
                            var totalB = 0.0
                            var totalA = 0.0
                            for (subY in 0 until 3) {
                                for (subX in 0 until 3) {
                                    // The offset is added to the DESTINATION pixel and the result is
                                    // mapped back, not the other way round: the grid is a box across
                                    // the destination pixel, and a perspective map turns that box
                                    // into a quadrilateral on the way in. Adding the offset to the
                                    // source point instead is a subtly different filter which agrees
                                    // with this one for every affine map and disagrees for exactly
                                    // the corner-pin this whole class exists for.
                                    mapPoint(
                                        inverse,
                                        dx + SUBSAMPLE_OFFSETS[subX],
                                        dy + SUBSAMPLE_OFFSETS[subY],
                                        point,
                                    )
                                    sample(src, point[0], point[1], filter, scratch, one)
                                    total += one[0]
                                    totalG += one[1]
                                    totalB += one[2]
                                    totalA += one[3]
                                }
                            }
                            tile[at] = toByte255(total / SUBSAMPLES)
                            tile[at + 1] = toByte255(totalG / SUBSAMPLES)
                            tile[at + 2] = toByte255(totalB / SUBSAMPLES)
                            tile[at + 3] = toByte255(totalA / SUBSAMPLES)
                        } else {
                            mapPoint(inverse, dx, dy, point)
                            sample(src, point[0], point[1], filter, scratch, one)
                            for (c in 0 until 4) tile[at + c] = toByte255(one[c])
                        }
                    }
                }
                if (!allZero(tile)) out[Tiles.key(tx, ty)] = tile
            }
        }
        return out
    }

    /**
     * Premultiplied SOURCE-OVER of [top] onto [bottom], per tile. Neither input is modified and
     * neither is shared with the result.
     *
     * `out = bottom + top * (1 - bottom / 255)` on all four channels, which is `ONE,
     * ONE_MINUS_SRC_ALPHA` — the blend `GlPaintEngine` sets once, and the one the GPU has. The
     * alpha channel gets the same rule as the colours and not a special one: alpha is a channel,
     * and a source-over that raised the alpha and left the colours alone invents opacity.
     *
     * A key present in only one of the two maps is composited against nothing, so it comes back as
     * a copy of itself. An all-zero result tile is dropped, as everywhere else here.
     */
    fun over(bottom: Map<Long, ByteArray>, top: Map<Long, ByteArray>): Map<Long, ByteArray> {
        val keys = HashSet<Long>(bottom.size + top.size)
        keys.addAll(bottom.keys)
        keys.addAll(top.keys)
        val out = LinkedHashMap<Long, ByteArray>()
        for (key in keys) {
            val b = bottom[key]
            val t = top[key]
            if (b == null && t == null) continue
            if (b == null) {
                if (t != null && !allZero(t)) out[key] = t.copyOf()
                continue
            }
            if (t == null) {
                if (!allZero(b)) out[key] = b.copyOf()
                continue
            }
            val tile = ByteArray(TILE_BYTES)
            for (pixel in 0 until PIXELS_PER_TILE) {
                val at = pixel * 4
                for (c in 0 until 4) {
                    val bv = b[at + c].toInt() and 0xFF
                    val tv = t[at + c].toInt() and 0xFF
                    // The two early exits are not an optimisation. b = 255 is an opaque backdrop
                    // and the top cannot show at all; t = 0 is no top. Both are exact, and both
                    // are the cases a selection edge spends most of its pixels in.
                    if (bv == 255) {
                        tile[at + c] = b[at + c]
                    } else if (tv == 0) {
                        tile[at + c] = b[at + c]
                    } else {
                        tile[at + c] = (bv + (tv * (255 - bv) + 127) / 255).coerceIn(0, 255).toByte()
                    }
                }
            }
            if (!allZero(tile)) out[key] = tile
        }
        return out
    }

    // ── sampling ───────────────────────────────────────────────────────────────────

    /**
     * The image of ([x], [y]) under [h], written into `out[0..1]`, or NaN into both when there is
     * no image.
     *
     * [Homography.apply] in all but the allocation: this runs once per destination pixel and nine
     * times again where the minification grid is on, so a quarter of a million `Pt`s per tile is
     * not a trade worth making for one subtraction. The rule is the same as `apply`'s, including
     * the refusal, and a NaN here is read by [sample] as "outside the source" — a transparent
     * pixel — so a destination pixel that maps past the horizon contributes nothing rather than a
     * NaN byte.
     */
    private fun mapPoint(h: Homography, x: Double, y: Double, out: DoubleArray) {
        val m = h.m
        val w = m[6] * x + m[7] * y + m[8]
        if (!w.isFinite() || abs(w) < HORIZON_EPS) {
            out[0] = Double.NaN
            out[1] = Double.NaN
            return
        }
        val nx = (m[0] * x + m[1] * y + m[2]) / w
        val ny = (m[3] * x + m[4] * y + m[5]) / w
        out[0] = if (nx.isFinite()) nx else Double.NaN
        out[1] = if (ny.isFinite()) ny else Double.NaN
    }

    /**
     * One destination pixel, filled into [out] as four 0..255 doubles, or left at zero when the
     * point is outside the source or is not a point at all.
     *
     * THE NON-FINITE GUARD IS THE POINT OF THE FUNCTION. An inverse map can carry a perfectly
     * ordinary destination pixel to a point at infinity, and `floor(NaN)` is 0 while
     * `floor(1e300).toInt()` is [Int.MAX_VALUE]: both would index a real pixel and write its bytes
     * into the result. So a coordinate that is not finite, or not addressable, is a TRANSPARENT
     * PIXEL and nothing else. It is the one honest answer, and it is local: the caller above has
     * already refused the whole warp if a tile corner goes to infinity, so this only fires in the
     * inside of a box whose corners are fine.
     *
     * BILINEAR ADDRESSES PIXEL CENTRES, so the fractional part is taken about `x - 0.5`: the
     * sample at a pixel centre lands on `i` with weight 1 and the next pixel gets nothing, which
     * is what makes an identity warp byte-identical rather than byte-nearly-identical.
     */
    private fun sample(
        src: Map<Long, ByteArray>,
        x: Double,
        y: Double,
        filter: Filter,
        scratch: ByteArray,
        out: DoubleArray,
    ) {
        out[0] = 0.0
        out[1] = 0.0
        out[2] = 0.0
        out[3] = 0.0
        if (!x.isFinite() || !y.isFinite()) return
        if (x < ADDRESS_LIMIT_NEG || x > ADDRESS_LIMIT || y < ADDRESS_LIMIT_NEG || y > ADDRESS_LIMIT) return
        if (filter == Filter.NEAREST) {
            val i = floor(x).toInt()
            val j = floor(y).toInt()
            pixelAt(src, i, j, scratch, 0)
            for (c in 0 until 4) out[c] = (scratch[c].toInt() and 0xFF).toDouble()
            return
        }
        val ux = x - 0.5
        val uy = y - 0.5
        val i0 = floor(ux).toInt()
        val j0 = floor(uy).toInt()
        val fx = ux - i0.toDouble()
        val fy = uy - j0.toDouble()
        pixelAt(src, i0, j0, scratch, 0)
        pixelAt(src, i0 + 1, j0, scratch, 4)
        pixelAt(src, i0, j0 + 1, scratch, 8)
        pixelAt(src, i0 + 1, j0 + 1, scratch, 12)
        val w00 = (1.0 - fx) * (1.0 - fy)
        val w10 = fx * (1.0 - fy)
        val w01 = (1.0 - fx) * fy
        val w11 = fx * fy
        for (c in 0 until 4) {
            out[c] = (scratch[c].toInt() and 0xFF) * w00 +
                (scratch[4 + c].toInt() and 0xFF) * w10 +
                (scratch[8 + c].toInt() and 0xFF) * w01 +
                (scratch[12 + c].toInt() and 0xFF) * w11
        }
    }

    /**
     * The premultiplied RGBA of source pixel ([x], [y]) into `scratch[off..off+3]`, or transparent
     * when no such tile exists.
     *
     * [tileOfPx] floors, so a negative pixel is in the tile BELOW zero and not in tile 0 with
     * negative local indices. Getting that wrong here would be invisible for a document that never
     * goes left of the origin and would paint a seam for every one that does.
     */
    private fun pixelAt(src: Map<Long, ByteArray>, x: Int, y: Int, scratch: ByteArray, off: Int) {
        scratch[off] = 0
        scratch[off + 1] = 0
        scratch[off + 2] = 0
        scratch[off + 3] = 0
        val tx = tileOfPx(x)
        val ty = tileOfPx(y)
        val tile = src[Tiles.key(tx, ty)] ?: return
        if (tile.size != TILE_BYTES) return
        val at = ((y - ty * Tiles.SIZE) * Tiles.SIZE + (x - tx * Tiles.SIZE)) * 4
        scratch[off] = tile[at]
        scratch[off + 1] = tile[at + 1]
        scratch[off + 2] = tile[at + 2]
        scratch[off + 3] = tile[at + 3]
    }

    /**
     * How many SOURCE pixels one destination pixel covers at ([x], [y]): the larger singular value
     * of the Jacobian of the map, so it answers the question for an anisotropic stretch as well as
     * a uniform one.
     *
     * The Jacobian of a projective map is not its matrix, which is why this is nine lines and not
     * three. With `W = c0x + c1y + c2`, `X = a0x + a1y + a2`, `Y = b0x + b1y + b2`, the quotient
     * rule gives `d(X/W)/dx = (a0W - Xc0)/W²` and so on for all four partials.
     *
     * `s² = (S + sqrt(S² - 4 det²)) / 2` with `S` the sum of the four squared partials is the
     * standard closed form for the larger singular value of a 2x2, and it is used rather than an
     * iteration because this runs once per tile and there is no reason to have a loop here that
     * can fail to converge.
     *
     * THE DISCRIMINANT IS CLAMPED, NOT TESTED FOR ZERO — and that is the whole subtlety of the
     * formula. For a plain uniform scale J = kI, `S²` and `4 det²` are BOTH `4k⁴`, so the
     * discriminant is exactly zero, not a small negative number to be suspicious of. A test of
     * `<= 0` would therefore refuse every uniform scale, a one-third shrink included, and the
     * minification grid would silently never run.
     *
     * A point where `W` is zero, or where the partials are not finite, gives 1.0 — "not
     * minifying" — because the pixel at that spot samples transparent (see [sample]) and averaging
     * nine transparents is still transparent.
     */
    private fun sampleSpan(h: Homography, x: Double, y: Double): Double {
        val m = h.m
        val a0 = m[0]; val a1 = m[1]; val a2 = m[2]
        val b0 = m[3]; val b1 = m[4]; val b2 = m[5]
        val c0 = m[6]; val c1 = m[7]; val c2 = m[8]
        val w = c0 * x + c1 * y + c2
        if (!w.isFinite() || abs(w) < HORIZON_EPS) return 1.0
        val w2 = w * w
        val bigX = a0 * x + a1 * y + a2
        val bigY = b0 * x + b1 * y + b2
        val jxx = (a0 * w - bigX * c0) / w2
        val jxy = (a1 * w - bigX * c1) / w2
        val jyx = (b0 * w - bigY * c0) / w2
        val jyy = (b1 * w - bigY * c1) / w2
        if (!jxx.isFinite() || !jxy.isFinite() || !jyx.isFinite() || !jyy.isFinite()) return 1.0
        val s = jxx * jxx + jxy * jxy + jyx * jyx + jyy * jyy
        val determinant = jxx * jyy - jxy * jyx
        val raw = s * s - 4.0 * determinant * determinant
        // Zero for every uniform scale and slightly negative only for rounding, so it is clamped.
        val discriminant = if (raw > 0.0) raw else 0.0
        val span = sqrt((s + sqrt(discriminant)) / 2.0)
        if (!span.isFinite()) return 1.0
        return span
    }

    /**
     * A 0..255 double as a byte, rounded to nearest and clamped.
     *
     * `floor(v + 0.5)` rather than `round(v)`, and it is clamped rather than wrapped: a premultiplied
     * channel that somehow ran past 255 must come back white, not dark, because a wrapped byte is
     * a pixel a person sees and a clamped one is a pixel they do not.
     */
    private fun toByte255(v: Double): Byte {
        val rounded = (v + 0.5).toInt()
        val clamped = if (rounded < 0) 0 else if (rounded > 255) 255 else rounded
        return clamped.toByte()
    }

    /** True when a tile holds nothing. Stops at the first byte that does. */
    private fun allZero(tile: ByteArray): Boolean {
        for (b in tile) if (b.toInt() != 0) return false
        return true
    }

    // ── constants ──────────────────────────────────────────────────────────────────

    /** Bytes in one RGBA8 tile: 256 x 256 x 4. The layout `GlPaintEngine` reads and writes. */
    private const val TILE_BYTES = Tiles.SIZE * Tiles.SIZE * 4

    /** Coverage bytes in one tile, one per document pixel. */
    private const val PIXELS_PER_TILE = Tiles.SIZE * Tiles.SIZE

    /**
     * Above this many source pixels per destination pixel, one sample per pixel is a moire and
     * nine samples are not. 2 is the line because a factor of 2 already samples the source lattice
     * at the Nyquist limit, where a point sample is a coin toss.
     */
    private const val MINIFY_SIGMA = 2.0

    /** Nine samples, so this is the divisor. */
    private const val SUBSAMPLES = 9

    /**
     * The `w` floor here, the same 1e-9 `Homography` uses for its own horizon, stated as a constant
     * rather than shared so that this file stays arithmetic with no private knowledge of another.
     */
    private const val HORIZON_EPS = 1e-9

    /** Where the nine go across a destination pixel: thirds, so the middle one IS the centre. */
    private val SUBSAMPLE_OFFSETS: DoubleArray = doubleArrayOf(-1.0 / 3.0, 0.0, 1.0 / 3.0)

    /** The four corners of a tile, as fractions of its side. Order does not matter: it is a box. */
    private val CORNER_UNITS: Array<Pt> = arrayOf(
        Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0),
    )

    /**
     * How far a mapped coordinate may be and still be turned into a tile index: 1e9 px, about
     * four million tiles. The canvas is unbounded in principle and bounded in practice by
     * `MAX_SELECT_SPAN` and by whatever a person has drawn on, so this only ever fires on arithmetic
     * that has already gone wrong — and it fires BEFORE `.toInt()`, which is the point.
     */
    private const val ADDRESS_LIMIT = 1.0e9
    private const val ADDRESS_LIMIT_NEG = -1.0e9
}
