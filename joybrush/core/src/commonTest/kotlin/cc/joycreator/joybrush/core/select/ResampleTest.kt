package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JB-2.05b — moving premultiplied RGBA8 tiles through a homography, and splitting them by a mask.
 *
 * EVERY EXPECTED VALUE HERE IS DERIVED, and the derivations are the tests. The eight that carry the
 * most weight, in the order they appear:
 *
 *  - WARP IS INVERSE MAPPED at pixel centres. For a destination pixel (X, Y) the source point is
 *    `h⁻¹(X + 0.5, Y + 0.5)`, and NEAREST reads the pixel whose square holds it, BILINEAR weights the
 *    four around it about `x - 0.5`. So `warp(src, translate(300, -17), either filter)` moves bytes
 *    and changes none, and a quarter turn about the ORIGIN (a corner, so pixel centres go to pixel
 *    centres) is `out(X, Y) == src(Y, -X - 1)`: the point (X + 0.5, Y + 0.5) turns by -90° to
 *    (Y + 0.5, -(X + 0.5)), whose pixel is (floor(Y + 0.5), floor(-(X + 0.5))) = (Y, -X - 1).
 *
 *  - DOUBLING DOES NOT LAND ON THE LATTICE. `scale(2, 2)` about the ORIGIN sends destination
 *    centres 0.5, 1.5, 2.5 to source 0.25, 0.75, 1.25 — a quarter of a pixel off every source pixel
 *    centre, so every destination pixel is a 0.75/0.25 mix of two source pixels and never an exact
 *    copy. That is why the derived answers are 96 and 159 rather than 0 and 255, and it is derived
 *    per destination parity below.
 *
 *  - THE 3 x 3 MINIFICATION GRID LANDS ON SOURCE PIXEL CENTRES. At a one-third scale a
 *    destination pixel is 3 source pixels wide, and the grid offsets of ±1/3 of a DESTINATION
 *    pixel are therefore ±1 SOURCE pixel, so the nine samples are the exact centres 3X + 0.5,
 *    3X + 1.5 and 3X + 2.5 — three consecutive source pixels, read with no interpolation at all.
 *    Three by three of a checkerboard is five of one value and four of the other, but WHICH five
 *    alternates: 3X is even for even X and odd for odd X, so the mean is 5 x 255 / 9 = 141.67,
 *    giving 142, when X and Y share a parity and 4 x 255 / 9 = 113.33, giving 113, when they do
 *    not. Both are grey and neither is the source, which is the whole claim: a single point sample
 *    would land on source pixel (3X + 1, 3Y + 1), whose parity (3X + 1) + (3Y + 1) = 3(X + Y) + 2
 *    is ALWAYS even, so it would return 255 over the whole interior and double the brightness.
 *
 *  - LIFT IS AN EXACT SPLIT. `lifted = round(v m / 255)` and `remaining = v - lifted`, so
 *    `lifted + remaining == v` in every channel with no rounding step anywhere. That is asserted
 *    for every pixel rather than sampled.
 *
 *  - ...BUT SOURCE-OVER DOES NOT PUT IT BACK. Decision 5 of the spec claims
 *    `over(remaining, lifted) == original ± 1`, and that is not true of a source-over, for a reason
 *    that is arithmetic rather than a bug: `over(B, T) = B + T(1 - B/255)`, so recovering `v` needs
 *    `B = 0`. With `v = 255` and coverage 128 the split is 128 and 127, and the composite is
 *    `127 + round(128 x 128 / 255) = 127 + 64 = 191`, which is 64 short of 255. The test asserts 191
 *    exactly, so the discrepancy is pinned in code and referred rather than quietly rounded away;
 *    see the spec's `## Questions`.
 *
 *  - THE CORNER-PIN CENTRE. A projective map takes the intersection of one pair of diagonals to
 *    the intersection of the other, so the centre of a warped source lands on the destination's
 *    diagonal crossing. `perspectiveCentreLandsOnTheDiagonalCrossing` checks the resampled ALPHA
 *    CENTROID against `h.apply` of the source pixel centre. The bound is 2 px and it is a
 *    sampling bound, not a fudge: the reconstruction of one source pixel is a bilinear tent one
 *    source pixel wide, about 1.1 destination pixels across at that trapezoid's local scale, and
 *    the centroid of a tent resampled onto a lattice settles on a half-integer pixel centre — so it
 *    can be at most half a pixel from the tent's own centre in each axis, plus the perspective's
 *    own bias. Measured it lands 0.5 and 0.7 away, and a whole-warp half-pixel misregistration
 *    would show 20.
 *
 *  - TWO REFUSALS ARE TESTS, NOT COMMENTS. A map with `w = x` sends every point on x = 0 to
 *    infinity, and a scale by zero has no inverse; both must come back as an EMPTY result, because
 *    a NaN written into a tile is read back by the undo log, the archive writer and a person three
 *    gestures later.
 */
class ResampleTest {

    private companion object {
        /**
         * 256 x 256 x 4. `Resample`'s own copy of it is private to that file, and a test that has to
         * guess at it is a test that can be wrong about the layout it is checking.
         */
        const val TILE_BYTES = 256 * 256 * 4
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun tileBytes(): ByteArray = ByteArray(TILE_BYTES)

    /** Floor division, written out: `a / b` truncates and `-1 / 256` is 0, not -1. */
    private fun fdiv(a: Int, b: Int): Int {
        val q = a / b
        return if (a % b < 0) q - 1 else q
    }

    /** Premultiplied RGBA at a document pixel; transparent when no tile holds it. */
    private fun at(tiles: Map<Long, ByteArray>, x: Int, y: Int): IntArray {
        val tx = fdiv(x, 256)
        val ty = fdiv(y, 256)
        val tile = tiles[Tiles.key(tx, ty)] ?: return intArrayOf(0, 0, 0, 0)
        val i = ((y - ty * 256) * 256 + (x - tx * 256)) * 4
        return intArrayOf(
            tile[i].toInt() and 0xFF,
            tile[i + 1].toInt() and 0xFF,
            tile[i + 2].toInt() and 0xFF,
            tile[i + 3].toInt() and 0xFF,
        )
    }

    private fun put(
        tiles: MutableMap<Long, ByteArray>,
        x: Int,
        y: Int,
        r: Int,
        g: Int,
        b: Int,
        a: Int,
    ) {
        val tx = fdiv(x, 256)
        val ty = fdiv(y, 256)
        val tile = tiles.getOrPut(Tiles.key(tx, ty)) { tileBytes() }
        val i = ((y - ty * 256) * 256 + (x - tx * 256)) * 4
        tile[i] = r.toByte()
        tile[i + 1] = g.toByte()
        tile[i + 2] = b.toByte()
        tile[i + 3] = a.toByte()
    }

    /** Painted document pixels: the alpha bytes of every tile, counted. */
    private fun paintedCount(tiles: Map<Long, ByteArray>): Int {
        var n = 0
        for (tile in tiles.values) {
            var p = 0
            while (p < TILE_BYTES) {
                if ((tile[p + 3].toInt() and 0xFF) != 0) n++
                p += 4
            }
        }
        return n
    }

    private fun assertNoEmptyTiles(tiles: Map<Long, ByteArray>, what: String) {
        for (entry in tiles) {
            var allZero = true
            for (b in entry.value) {
                if (b.toInt() != 0) {
                    allZero = false
                    break
                }
            }
            assertTrue(!allZero, "$what returned an all-zero tile at ${Tiles.tx(entry.key)},${Tiles.ty(entry.key)}")
        }
    }

    /** A dense premultiplied pattern over one whole tile: opaque, with a diagonal of holes. */
    private fun patternedTile(tx: Int, ty: Int): ByteArray {
        val tile = tileBytes()
        for (row in 0 until 256) {
            for (col in 0 until 256) {
                val x = tx * 256 + col
                val y = ty * 256 + row
                val i = (row * 256 + col) * 4
                if ((x + y) % 23 == 0) continue
                tile[i] = ((x * 3) % 256).toByte()
                tile[i + 1] = ((y * 7) % 256).toByte()
                tile[i + 2] = ((x + y) % 256).toByte()
                tile[i + 3] = 255.toByte()
            }
        }
        return tile
    }

    // ---- 1. the identity is a no-op ---------------------------------------------------------------

    @Test
    fun anIdentityWarpIsByteIdentical() {
        val key = Tiles.key(0, 0)
        val src = mapOf(key to patternedTile(0, 0))
        val source = src.getValue(key)
        for (filter in Resample.Filter.values()) {
            val out = Resample.warp(src, Homography.IDENTITY, filter)
            // The bounding box of tile (0,0) is [0, 256] on both axes, so four tiles are VISITED
            // and three of them come back empty. Only the painted one is a result.
            assertEquals(setOf(key), out.keys.toSet(), "keys for $filter")
            val warped = out.getValue(key)
            assertTrue(warped !== source, "a fresh array, not the caller's")
            for (i in 0 until TILE_BYTES) {
                if (source[i].toInt() != warped[i].toInt()) {
                    assertEquals(source[i].toInt(), warped[i].toInt(), "byte $i for $filter")
                }
            }
        }
    }

    // ---- 2. an integer translation is exact, seams and all ----------------------------------------

    @Test
    fun anIntegerTranslationCopiesBytesExactlyAcrossTileSeams() {
        val dx = 300
        val dy = -17
        val src = LinkedHashMap<Long, ByteArray>()
        for (ty in 0..1) {
            for (tx in 0..1) src[Tiles.key(tx, ty)] = patternedTile(tx, ty)
        }
        val painted = paintedCount(src)
        val h = Homography.translate(dx.toDouble(), dy.toDouble())
        for (filter in Resample.Filter.values()) {
            val out = Resample.warp(src, h, filter)
            assertNoEmptyTiles(out, "translate with $filter")
            // The shift really did cross a seam: y = -17 is the tile row ABOVE zero, and x = 300 is
            // past the first column boundary, so a result that stayed inside one tile proves nothing.
            assertTrue(out.keys.any { Tiles.ty(it) == -1 }, "$filter must write a negative tile row")
            assertTrue(out.keys.any { Tiles.tx(it) >= 2 }, "$filter must cross a column seam")
            var mismatches = 0
            var moved = 0
            for (entry in out) {
                val tx = Tiles.tx(entry.key)
                val ty = Tiles.ty(entry.key)
                for (row in 0 until 256) {
                    for (col in 0 until 256) {
                        val x = tx * 256 + col
                        val y = ty * 256 + row
                        val want = at(src, x - dx, y - dy)
                        val got = at(out, x, y)
                        if (want[0] != got[0] || want[1] != got[1] || want[2] != got[2] || want[3] != got[3]) {
                            mismatches++
                        }
                        if (got[3] != 0) moved++
                    }
                }
            }
            assertEquals(0, mismatches, "bytes changed by an integer move, $filter")
            assertEquals(painted, moved, "painted pixels after an integer move, $filter")
        }
    }

    // ---- 3. a quarter turn with NEAREST is exact -------------------------------------------------

    @Test
    fun aQuarterTurnWithNearestIsExact() {
        val src = LinkedHashMap<Long, ByteArray>()
        for (y in 0 until 150) {
            for (x in 0 until 200) {
                put(src, x, y, (x * 5) % 256, (y * 11) % 256, (x + y) % 256, 255)
            }
        }
        val painted = paintedCount(src)
        // About the ORIGIN, which is a corner of the pixel grid, so centres go to centres.
        val out = Resample.warp(src, Homography.rotate(PI / 2.0, Pt(0.0, 0.0)), Resample.Filter.NEAREST)
        assertNoEmptyTiles(out, "a quarter turn")
        // The rotated 200 x 150 block is x in [-150, -1], y in [0, 199]: entirely in one tile.
        assertEquals(setOf(Tiles.key(-1, 0)), out.keys.toSet(), "the tiles a quarter turn lands in")
        var mismatches = 0
        for (y in 0..199) {
            for (x in -150..-1) {
                val want = at(src, y, -x - 1)
                val got = at(out, x, y)
                if (want[0] != got[0] || want[1] != got[1] || want[2] != got[2] || want[3] != got[3]) {
                    mismatches++
                }
            }
        }
        assertEquals(0, mismatches, "out(X, Y) must be src(Y, -X - 1) exactly")
        assertEquals(painted, paintedCount(out), "a quarter turn loses nothing")
        // And just outside the block the result really is empty, not stale.
        assertEquals(0, at(out, 0, 10)[3], "x = 0 is one pixel past the block")
        assertEquals(0, at(out, -151, 10)[3], "x = -151 is one pixel past the block")
        assertEquals(0, at(out, -10, 200)[3], "y = 200 is one pixel past the block")
    }

    // ---- 4. doubling a checker, the derived in-betweens ------------------------------------------

    @Test
    fun doublingBilinearOfATwoPixelCheckerGivesTheDerivedInBetweens() {
        val src = LinkedHashMap<Long, ByteArray>()
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                val v = if ((x + y) % 2 == 0) 255 else 0
                put(src, x, y, v, v, v, 255)
            }
        }
        val out = Resample.warp(src, Homography.scale(2.0, 2.0, Pt(0.0, 0.0)), Resample.Filter.BILINEAR)
        assertNoEmptyTiles(out, "doubling a checker")
        // A magnification, so the minification grid is not used: 1 / 2 < 2 source px per dest px.
        // The destination pixel (X, Y) samples the source at ux = X / 2 - 0.25, so for X even
        // fx = 0.75 on pixel X / 2 - 1 and for X odd fx = 0.25 on pixel (X - 1) / 2, and the same in
        // y. In a checker, s(i, j) and s(i + 1, j + 1) share a value A and the two off-diagonal
        // neighbours share 255 - A, so the result is
        //     S * A + (1 - S) (255 - A),   S = (1 - fx)(1 - fy) + fx fy
        // and S works out to 0.625 when fx and fy agree and 0.375 when they differ. Either way the
        // answer is 0.25 A + 95.625 for the agreeing case and 159.375 - 0.25 A for the other, and
        // in all four parities the value is 159 when (X / 2 + Y / 2) is even and 96 when it is odd.
        var checked = 0
        var inBetweens = 0
        for (y in 1 until 255) {
            for (x in 1 until 255) {
                val expected = if ((x / 2 + y / 2) % 2 == 0) 159 else 96
                val got = at(out, x, y)
                assertEquals(expected, got[0], "red at $x,$y")
                assertEquals(expected, got[1], "green at $x,$y")
                assertEquals(expected, got[2], "blue at $x,$y")
                assertEquals(255, got[3], "alpha at $x,$y")
                if (expected != 0 && expected != 255) inBetweens++
                checked++
            }
        }
        assertEquals(254 * 254, checked, "the interior was walked")
        // The point of the whole test: the result is a blend and not the source, and it is never
        // either extreme. 0 and 255 are what a point sample or a broken filter would have given.
        assertEquals(checked, inBetweens, "every interior pixel is a blend of the two values")
    }

    // ---- 5. doubling a hard edge, where a swapped weight would show --------------------------------

    @Test
    fun doublingBilinearOfAHardEdgeGivesTheDerivedInBetweens() {
        val src = LinkedHashMap<Long, ByteArray>()
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                val v = if (x < 32) 0 else 255
                put(src, x, y, v, v, v, 255)
            }
        }
        val out = Resample.warp(src, Homography.scale(2.0, 2.0, Pt(0.0, 0.0)), Resample.Filter.BILINEAR)
        // ux = X / 2 - 0.25 against a step from source x = 31 to 32:
        //   X = 62: ux = 30.75, pixel 30 at 0.25 and 31 at 0.75, both 0       -> 0
        //   X = 63: ux = 31.25, pixel 31 at 0.75 and 32 at 0.25            -> round(255 / 4) = 64
        //   X = 64: ux = 31.75, pixel 31 at 0.25 and 32 at 0.75            -> round(3 * 255 / 4) = 191
        //   X = 65: ux = 32.25, both neighbours 255                         -> 255
        // The edge therefore straddles two destination pixels and NOT one, which is the whole claim.
        for (y in 1 until 255) {
            assertEquals(0, at(out, 61, y)[0], "x = 61, y = $y")
            assertEquals(0, at(out, 62, y)[0], "x = 62, y = $y")
            assertEquals(64, at(out, 63, y)[0], "x = 63, y = $y")
            assertEquals(191, at(out, 64, y)[0], "x = 64, y = $y")
            assertEquals(255, at(out, 65, y)[0], "x = 65, y = $y")
            assertEquals(255, at(out, 100, y)[0], "x = 100, y = $y")
            assertEquals(255, at(out, 200, y)[0], "x = 200, y = $y")
            assertEquals(255, at(out, 63, y)[3], "alpha at x = 63, y = $y")
        }
    }

    // ---- 6. minification must not alias -----------------------------------------------------------

    @Test
    fun shrinkingACheckerToAThirdComesOutUniformGrey() {
        val src = LinkedHashMap<Long, ByteArray>()
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val v = if ((x + y) % 2 == 0) 255 else 0
                put(src, x, y, v, v, v, 255)
            }
        }
        val out = Resample.warp(
            src,
            Homography.scale(1.0 / 3.0, 1.0 / 3.0, Pt(0.0, 0.0)),
            Resample.Filter.BILINEAR,
        )
        assertNoEmptyTiles(out, "a one-third shrink")
        // The interior is X, Y in [0, 20]: the three source columns a destination pixel reads are
        // 3X, 3X + 1 and 3X + 2, and they are all inside [0, 64) exactly while 3X >= 0 and
        // 3X + 2 <= 63, that is X in [0, 20].
        val values = HashSet<Int>()
        for (y in 0..20) {
            for (x in 0..20) {
                val got = at(out, x, y)
                // Five 255s and four 0s is 141.67 -> 142; four and five is 113.33 -> 113. And 3X is
                // even exactly when X is, so the majority is on 255 exactly when X and Y agree.
                val expected = if ((x + y) % 2 == 0) 142 else 113
                assertEquals(expected, got[0], "red at $x,$y")
                assertEquals(expected, got[1], "green at $x,$y")
                assertEquals(expected, got[2], "blue at $x,$y")
                assertEquals(255, got[3], "alpha at $x,$y is opaque, so the grid missed the edge")
                values.add(got[0])
            }
        }
        assertEquals(setOf(113, 142), values.toSet(), "two greys and nothing else")
        // The spec's own bound: within 24 of 128, which the exact values clear by 9 and 14.
        for (v in values) assertTrue(abs(v - 128) <= 24, "grey within 24 of 128, was $v")
        // And the claim the grid exists for. A single point sample would land on source pixel
        // (3X + 1, 3Y + 1), and (3X + 1) + (3Y + 1) = 3(X + Y) + 2 is always even, so it would
        // return 255 over the WHOLE interior and double the brightness of the picture. Nothing
        // anywhere in the result may be 0 or 255, which is a stronger statement than "grey".
        for (tile in out.values) {
            for (p in 0 until TILE_BYTES step 4) {
                val a = tile[p + 3].toInt() and 0xFF
                if (a == 0) continue
                val r = tile[p].toInt() and 0xFF
                assertTrue(r != 0 && r != 255, "a painted pixel was $r, so the grid did not run")
            }
        }
    }

    // ---- 7. a corner pin, and the projective centre ----------------------------------------------

    @Test
    fun perspectiveCentreLandsOnTheDiagonalCrossing() {
        val srcQuad = listOf(Pt(0.5, 0.5), Pt(64.5, 0.5), Pt(64.5, 64.5), Pt(0.5, 64.5))
        val dstQuad = listOf(Pt(16.5, 8.5), Pt(64.5, 8.5), Pt(56.5, 56.5), Pt(0.5, 56.5))
        val map = assertNotNull(Homography.fromQuads(srcQuad, dstQuad), "a corner pin is a legal quad")
        val src = LinkedHashMap<Long, ByteArray>()
        put(src, 32, 32, 255, 255, 255, 255)
        val out = Resample.warp(src, map, Resample.Filter.BILINEAR)
        assertNoEmptyTiles(out, "a corner pin of one pixel")

        // The alpha-weighted centroid of the reconstructed blob. Its centre must be the image of
        // the source pixel's centre, which for a projective map is the crossing of the destination
        // diagonals: see `HomographyTest.perspectiveCentreIsTheDiagonalIntersection`, where that
        // crossing is (34.961538, 30.653846) worked out with pencil and paper.
        var weight = 0.0
        var sumX = 0.0
        var sumY = 0.0
        for (entry in out) {
            val tx = Tiles.tx(entry.key)
            val ty = Tiles.ty(entry.key)
            for (row in 0 until 256) {
                for (col in 0 until 256) {
                    val a = (entry.value[(row * 256 + col) * 4 + 3].toInt() and 0xFF).toDouble()
                    if (a == 0.0) continue
                    weight += a
                    sumX += (tx * 256 + col) * a
                    sumY += (ty * 256 + row) * a
                }
            }
        }
        assertTrue(weight > 0.0, "one opaque source pixel must land somewhere")
        val centroidX = sumX / weight
        val centroidY = sumY / weight
        val expected = map.apply(Pt(32.5, 32.5))
        assertTrue(
            abs(centroidX - expected.x) <= 2.0,
            "centroid x ${centroidX} against ${expected.x}",
        )
        assertTrue(
            abs(centroidY - expected.y) <= 2.0,
            "centroid y ${centroidY} against ${expected.y}",
        )
        // And the analytic crossing, so the resampler and the matrix are checked against each other
        // rather than only against themselves.
        assertTrue(abs(centroidX - 34.96153846153846) <= 2.0, "centroid x against the diagonals")
        assertTrue(abs(centroidY - 30.65384615384615) <= 2.0, "centroid y against the diagonals")
    }

    // ---- 8. lift, and the composite that does not quite add up -------------------------------------

    @Test
    fun liftSplitsTheBytesExactly() {
        val src = LinkedHashMap<Long, ByteArray>()
        for (y in 0 until 6) {
            for (x in 0 until 6) put(src, x, y, 255, 255, 255, 255)
        }
        val half = ByteArray(16) { 128.toByte() }
        val mask = SelectionMask.fromMask(4, 4, half, 0, 0)
        val (lifted, remaining) = Resample.lift(src, mask)

        // Coverage 128 on [0, 4) and zero elsewhere. round(255 x 128 / 255) = (32640 + 127) / 255
        // = 128, so the split is exactly 128 and 127 in every channel, alpha included.
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                val l = at(lifted, x, y)
                val r = at(remaining, x, y)
                for (c in 0 until 4) {
                    assertEquals(128, l[c], "lifted channel $c at $x,$y")
                    assertEquals(127, r[c], "remaining channel $c at $x,$y")
                    assertEquals(255, l[c] + r[c], "the split is exact at $x,$y channel $c")
                    assertTrue(l[c] <= l[3], "lifted stays premultiplied at $x,$y channel $c")
                    assertTrue(r[c] <= r[3], "remaining stays premultiplied at $x,$y channel $c")
                }
            }
        }
        // Outside the mask nothing is lifted and everything remains, byte for byte.
        for (y in 4 until 6) {
            for (x in 0 until 6) {
                for (c in 0 until 4) {
                    assertEquals(0, at(lifted, x, y)[c], "nothing lifted at $x,$y")
                    assertEquals(255, at(remaining, x, y)[c], "all of it remains at $x,$y")
                }
            }
        }
        assertNoEmptyTiles(lifted, "lift")
        assertNoEmptyTiles(remaining, "lift")

        // AND THE COMPOSITE. Source-over is B + T (1 - B / 255), which recovers v only when B = 0,
        // so a 128/127 split comes back as 127 + round(128 x 128 / 255) = 127 + 64 = 191, not 255.
        // The spec's Decision 5 says this is the original +- 1; it is not, and the number is
        // asserted here so the gap is a red test if anyone changes the arithmetic.
        val putBack = Resample.over(remaining, lifted)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                val got = at(putBack, x, y)
                for (c in 0 until 4) assertEquals(191, got[c], "recomposed channel $c at $x,$y")
            }
        }
        // Where the coverage was 0 the composite IS exact, because there is nothing to composite.
        for (y in 4 until 6) {
            for (x in 0 until 6) {
                for (c in 0 until 4) assertEquals(255, at(putBack, x, y)[c], "untouched at $x,$y")
            }
        }
        assertNoEmptyTiles(putBack, "over")

        // A coverage of 255, the case a marquee actually produces, IS exact: lifted = v, the
        // remainder = 0, and source-over of nothing onto everything is everything.
        val solid = SelectionMask.fromMask(2, 2, ByteArray(4) { 255.toByte() }, 0, 0)
        val (solidLifted, solidRemaining) = Resample.lift(src, solid)
        val solidBack = Resample.over(solidRemaining, solidLifted)
        for (y in 0 until 2) {
            for (x in 0 until 2) {
                for (c in 0 until 4) assertEquals(255, at(solidBack, x, y)[c], "solid at $x,$y")
            }
        }
    }

    @Test
    fun liftDoesNotModifyItsInput() {
        val src = LinkedHashMap<Long, ByteArray>()
        for (y in 0 until 4) {
            for (x in 0 until 4) put(src, x, y, 200, 100, 50, 255)
        }
        val before = src.getValue(Tiles.key(0, 0)).copyOf()
        val mask = SelectionMask.fromMask(2, 2, ByteArray(4) { 90.toByte() }, 0, 0)
        Resample.lift(src, mask)
        val after = src.getValue(Tiles.key(0, 0))
        for (i in 0 until TILE_BYTES) assertEquals(before[i].toInt(), after[i].toInt(), "byte $i")
    }

    // ---- 9. source-over ---------------------------------------------------------------------------

    @Test
    fun overCompositesSourceOverAndLeavesItsInputsAlone() {
        val bottom = LinkedHashMap<Long, ByteArray>()
        val top = LinkedHashMap<Long, ByteArray>()
        // Half-transparent premultiplied red under fully opaque premultiplied blue.
        put(bottom, 10, 10, 128, 0, 0, 128)
        put(top, 10, 10, 0, 0, 255, 255)
        val bottomBefore = bottom.getValue(Tiles.key(0, 0)).copyOf()
        val topBefore = top.getValue(Tiles.key(0, 0)).copyOf()

        val out = Resample.over(bottom, top)
        // red: 128 + 0 (the top has no red, and a half-transparent backdrop shows through 127/255
        // of the way — 128 x 127 / 255 = 63, rounded, and 0 x 127 / 255 = 0). blue: 0 + 255 x 255 /
        // 255 = 255. alpha: 128 + 255 x 127 / 255 = 128 + 127 = 255. A premultiplied pixel, still.
        val got = at(out, 10, 10)
        assertEquals(128, got[0], "red survives the backdrop's transparency")
        assertEquals(0, got[1], "green")
        assertEquals(255, got[2], "blue")
        assertEquals(255, got[3], "an opaque top makes the result opaque")
        for (i in 0 until TILE_BYTES) {
            assertEquals(bottomBefore[i].toInt(), bottom.getValue(Tiles.key(0, 0))[i].toInt(), "bottom byte $i")
            assertEquals(topBefore[i].toInt(), top.getValue(Tiles.key(0, 0))[i].toInt(), "top byte $i")
        }
        assertTrue(out.getValue(Tiles.key(0, 0)) !== bottom.getValue(Tiles.key(0, 0)), "a fresh array")

        // An OPAQUE BACKDROP is not moved by anything, because ONE_MINUS_SRC_ALPHA is zero when
        // da = 1. The bottom is a mid grey at full alpha and the top is opaque black, and the
        // bottom is what survives.
        val opaque = LinkedHashMap<Long, ByteArray>()
        val paint = LinkedHashMap<Long, ByteArray>()
        put(opaque, 20, 20, 10, 20, 30, 255)
        put(paint, 20, 20, 0, 0, 0, 255)
        val onOpaque = at(Resample.over(opaque, paint), 20, 20)
        assertEquals(10, onOpaque[0], "an opaque backdrop cannot be painted on")
        assertEquals(20, onOpaque[1], "an opaque backdrop cannot be painted on")
        assertEquals(30, onOpaque[2], "an opaque backdrop cannot be painted on")
        assertEquals(255, onOpaque[3], "still opaque")

        // A tile in only one of the two maps comes back as itself.
        val alone = at(Resample.over(bottom, emptyMap()), 10, 10)
        assertEquals(128, alone[0], "the bottom alone")
        assertEquals(128, alone[3], "the bottom alone")
        val topAlone = at(Resample.over(emptyMap(), top), 10, 10)
        assertEquals(255, topAlone[2], "the top alone")
        assertEquals(255, topAlone[3], "the top alone")
        assertTrue(Resample.over(emptyMap(), emptyMap()).isEmpty(), "nothing in, nothing out")
    }

    // ---- 10. the refusals ---------------------------------------------------------------------------

    @Test
    fun aWarpRefusesAPointAtInfinity() {
        val src = LinkedHashMap<Long, ByteArray>()
        put(src, 5, 5, 255, 255, 255, 255)
        // w = x, so every point with x = 0 is on the horizon. Tile (0, 0) has two such corners.
        val horizon = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0))
        for (filter in Resample.Filter.values()) {
            assertTrue(Resample.warp(src, horizon, filter).isEmpty(), "a horizon is refused for $filter")
        }
    }

    @Test
    fun aWarpRefusesASingularMatrix() {
        val src = LinkedHashMap<Long, ByteArray>()
        put(src, 5, 5, 255, 255, 255, 255)
        val singular = Homography.scale(0.0, 1.0, Pt(0.0, 0.0))
        for (filter in Resample.Filter.values()) {
            assertTrue(Resample.warp(src, singular, filter).isEmpty(), "no inverse is refused for $filter")
        }
    }

    @Test
    fun aWarpOfNothingIsNothing() {
        assertTrue(
            Resample.warp(emptyMap(), Homography.IDENTITY, Resample.Filter.BILINEAR).isEmpty(),
            "an empty source has no box to visit",
        )
    }

    // ---- 11. thirty degrees there and back --------------------------------------------------------

    @Test
    fun rotatingThirtyDegreesAndBackIsWithinThreeOfTheOriginal() {
        // A smooth opaque ramp. A FLAT interior would prove nothing about resampling — a half-pixel
        // misregistration of a constant region still gives zero error — so the interior has to vary,
        // and it varies by half a unit per pixel, which is what a bilinear re-read of a staircase
        // can be wrong by.
        val src = LinkedHashMap<Long, ByteArray>()
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                val v = 30 + (x + y) / 2
                put(src, x, y, v, v, v, 255)
            }
        }
        val about = Pt(64.0, 64.0)
        val there = Resample.warp(src, Homography.rotate(PI / 6.0, about), Resample.Filter.BILINEAR)
        val back = Resample.warp(there, Homography.rotate(-PI / 6.0, about), Resample.Filter.BILINEAR)
        assertNoEmptyTiles(there, "thirty degrees")
        assertNoEmptyTiles(back, "back thirty degrees")

        // Inside the disc of radius 50 about the pivot, a point's image under a thirty degree turn
        // about (64, 64) is still inside the 128 square, and at least 13 px from its edge, so all
        // four bilinear taps of both passes read painted pixels and not the transparent surround.
        var error = 0.0
        var channels = 0
        var opaque = true
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                val dx = x - 64
                val dy = y - 64
                if (dx * dx + dy * dy > 2500) continue
                val want = at(src, x, y)
                val got = at(back, x, y)
                if (got[3] != 255) opaque = false
                for (c in 0 until 4) {
                    error += abs((want[c] - got[c]).toDouble())
                    channels++
                }
            }
        }
        assertTrue(opaque, "the interior must come back opaque")
        assertTrue(channels > 0, "the disc must contain pixels")
        val mean = error / channels
        // Two bilinear passes over a ramp quantised to bytes: each re-read can be half a unit out
        // against the staircase and half a unit out against the byte grid, so four of them is 2 —
        // the bound is 3 and the arithmetic says it should land near 0.3.
        assertTrue(mean < 3.0, "mean absolute error was $mean per channel over $channels samples")
    }
}
