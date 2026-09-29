package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.TipSpec
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.RefCanvas
import cc.joycreator.joybrush.core.paint.StrokeBlend
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.render.MAX_REGION_PX
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Dabs and a polygon, turned into bytes. The arithmetic is NOT this file's — it is
 * [cc.joycreator.joybrush.core.paint.TipMath]'s, [cc.joycreator.joybrush.core.paint.RefCanvas]'s,
 * [cc.joycreator.joybrush.core.select.SelectionMask]'s and [cc.joycreator.joybrush.core.fill.MaskPaint]'s —
 * and test 12 is what says so, by driving `RefCanvas` by hand and demanding equality with no
 * tolerance at all.
 *
 * The rest is about the two things that make an ink layer worth having: the raster is addressed by
 * a DOCUMENT rectangle and a SCALE, so a 32-bit coordinate does not wrap and a far-off origin gives
 * the same bytes; and the raster is RE-DRAWN at any zoom rather than magnified, so the 16x image is
 * a re-raster with a real edge and not a bitmap with a staircase.
 *
 * Every expected number is derived in the test that uses it. Where the spec's own test text and one
 * of its Decisions disagreed — `widthScale = 1e30f` — the Decision was built and the derivation is
 * written down here and in `InkReplayTest`.
 */
class InkRasterTest {

    // ── 12. parity with RefCanvas, in floats ──────────────────────────────────────────────

    /**
     * THE PROOF THAT THIS FILE OWNS NO ARITHMETIC. `RefCanvas` is the repo's CPU reference painter
     * — the same accumulation `s' = cap x d + s x (1 - d)` and the same commit
     * `out = colour x a + dst x (1 - a)` with `a = s x k` — and `InkRaster.premultiplied` must
     * agree with it ELEMENT BY ELEMENT, with NO TOLERANCE. Not "close": identical, because both
     * are FloatArrays built by the same expression in the same order, and any tolerance here would
     * be a licence for a second rule to grow up beside the first.
     *
     * The fixture is 60 samples 4 doc px apart along `y = 0` (so x = 0 … 236), diameter 6,
     * `hardness = 1`, WASH, opacity 1, destination `(0, 0, 256 x 256)` at scale 1 — which is
     * exactly one tile, so `RefCanvas`'s tile at `Tiles.key(0, 0)` and the flat destination are the
     * same 256 x 256 x 4 buffer in the same order.
     *
     * NON-VACUITY: the destination is asserted NOT to be all zeros first. A function that returned
     * zeros, or one that compared equal only where both were empty, would otherwise sail through.
     */
    @Test
    fun parityWithRefCanvasInFloats() {
        val tip = TipShape(corner = 2f, hardness = 1f, minPx = 1f)
        val colorRgb = 0xFF1B1B22.toInt()
        val dabs = InkReplay.dabs(line(60, 4f, 0f), inkBrush())
        assertTrue(dabs.size > 100, "the fixture is a real stroke: ${dabs.size} dabs")

        val canvas = RefCanvas(tileSize = 256)
        canvas.beginStroke(
            "ink", 0x1B / 255f, 0x1B / 255f, 0x22 / 255f, 1f,
            Accumulate.WASH, StrokeBlend.NORMAL, tip,
        )
        canvas.addDabs(dabs)
        canvas.endStroke()

        val reference = canvas.tiles("ink")[Tiles.key(0, 0)]
        assertNotNull(reference, "the stroke reached tile 0,0: it starts at document (0, 0)")
        val mine = InkRaster.premultiplied(dabs, tip, Accumulate.WASH, 1f, colorRgb, 0, 0, 256, 256, 1f)
        assertNotNull(mine, "the same fixture through InkRaster")

        assertTrue(reference.any { it != 0f }, "NON-VACUITY: RefCanvas's tile is not blank")
        assertTrue(mine.any { it != 0f }, "NON-VACUITY: and neither is InkRaster's")
        assertIdentical(reference, mine, "RefCanvas's tile against InkRaster's destination")
    }

    // ── 13. stamps is premultiplied plus a quantisation ───────────────────────────────────

    /**
     * [InkRaster.stamps] is [InkRaster.premultiplied] and one rounding, and the rounding is
     * `RegionRenderer.toByte255`'s exactly: `(v x 255f + 0.5f).toInt().coerceIn(0, 255).toByte()`.
     * Every byte of all four channels is checked against that expression, so "stamps is the same
     * picture in bytes" is an equality rather than a claim.
     *
     * And the interior: a pixel well inside the line has coverage exactly 1 (the tip's inner edge
     * is `min(hardness, 1 - aa)` and a 0.5 px offset from a 3 px radius is nowhere near it), so
     * the accumulation saturates at 1, the commit's `a = 1 x 1 = 1`, and the alpha is exactly 255.
     * A "nearly right" quantisation — a truncating divide, an off-by-one clamp — would show here.
     */
    @Test
    fun stampsIsPremultipliedPlusAQuantisation() {
        val tip = TipShape(corner = 2f, hardness = 1f, minPx = 1f)
        val dabs = InkReplay.dabs(line(60, 4f, 0f), inkBrush())
        val p = assertNotNull(
            InkRaster.premultiplied(dabs, tip, Accumulate.WASH, 1f, 0xFF1B1B22.toInt(), 0, 0, 256, 256, 1f),
        )
        val bytes = assertNotNull(
            InkRaster.stamps(dabs, tip, Accumulate.WASH, 1f, 0xFF1B1B22.toInt(), 0, 0, 256, 256, 1f),
        )
        assertEquals(p.size, bytes.size, "the two doors return the same number of bytes")
        for (i in bytes.indices) {
            val want = (p[i] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            assertEquals(want, bytes[i], "byte $i (pixel ${i / 4}, channel ${i % 4})")
        }

        // x = 118 is the middle of the 0 … 236 line and y = 0 is its centreline.
        val at = ((0 * 256) + 118) * 4
        assertEquals(255.toByte(), bytes[at + 3], "a fully covered interior pixel is alpha 255")
        assertEquals(0x1B.toByte(), bytes[at], "and it carries the colour, premultiplied by its own alpha")
        assertEquals(0x22.toByte(), bytes[at + 2], "on all three channels")

        // AND THE COMMIT FACTOR'S ALPHA, which an all-opaque fixture cannot see at all. The
        // commit is `a = s x k` with `k = alphaOf(colorRgb) x (…)`, and the reason the alpha is
        // there at all is that a `StrokeRecord` carries its colour WITH an alpha and nothing else
        // per-record. `0x801B1B22` has an alpha byte of 128, so `k = 128/255 = 0.5019608`:
        //
        //     A  = 1 x 0.5019608                 -> byte (0.5019608 x 255 + 0.5) = (128.5) = 128
        //     R  = (27/255) x 0.5019608 = 0.05294 -> byte (13.5 + 0.5)          = 14
        //
        // A `stamps` that forgot the alpha factor would answer 255 and 27, and every fixture in
        // this file with an opaque colour would have agreed with it.
        val translucent = assertNotNull(
            InkRaster.stamps(dabs, tip, Accumulate.WASH, 1f, 0x801B1B22.toInt(), 0, 0, 256, 256, 1f),
        )
        assertEquals(128, (translucent[at + 3].toInt() and 0xFF), "alpha 128: s x 128/255, then x 255 and round")
        assertEquals(14, (translucent[at].toInt() and 0xFF), "red 14: the colour premultiplied by that same 128/255")
    }

    // ── 14. no gap at any scale, and a re-raster is not a magnified bitmap ─────────────────

    /**
     * THE POINT OF THE WHOLE ROW. An ink line is DRAWN AGAIN at 16x, so the 16x image is a
     * re-raster of the same recording and not a stretched 1x picture. The property that tells the
     * two apart is on the centreline row: at every scale no pixel whose centre is within half a
     * destination pixel of the centreline may be transparent, and the covered run must be
     * CONTIGUOUS from the first covered pixel to the last.
     *
     * WHY THAT IS NOT A TAUTOLOGY. Consecutive dabs are `2r x spacing` doc px apart — 0.24 with
     * Ink's numbers — which at scale `s` is `0.24s` destination px, so at every scale in the list
     * (0.25 to 16) the beads overlap 4 to 64 times over and a gap can only come from a rasteriser
     * that stamps once and stops, or that drops the dabs between batches. The assertion is over
     * the destination's own grid, so it is a claim about the pixels a person would see.
     *
     * The fixture's right margin is worth a word, because it is arithmetic rather than intention:
     * `docX = -4` with `width = ceil(length x s) + 8` gives 4 doc px of margin on the LEFT but
     * only `8 / s` on the right, so from 4x up the last `4 - 8/s` doc px of the line fall outside
     * the rectangle. The covered run is therefore read off the row itself — first covered pixel to
     * last covered pixel — which is unaffected, and the "no hole" window is clipped to the
     * destination.
     */
    @Test
    fun noGapAtAnyScaleAndAReRasterIsNotAMagnifiedBitmap() {
        val tip = TipShape(corner = 2f, hardness = 1f, minPx = 1f)
        val dabs = InkReplay.dabs(line(400, 4f, 0f), inkBrush())
        val length = 399f * 4f
        val docX = -4
        val docY = -4
        val coveredByScale = mutableMapOf<Float, Int>()

        for (s in listOf(0.25f, 0.5f, 1f, 2f, 4f, 8f, 16f)) {
            val w = ceil(length * s).toInt() + 8
            val h = ceil(8f * s).toInt()
            val bytes = assertNotNull(
                InkRaster.stamps(dabs, tip, Accumulate.WASH, 1f, 0xFF000000.toInt(), docX, docY, w, h, s),
                "scale $s: a ${w}x$h destination is inside the budget",
            )
            // The centreline y = 0 sits at destination row (0 - docY) * s = 4s, and the two rows
            // whose centres are within 0.5 destination px of it are py with |py + 0.5 - 4s| <= 0.5.
            val centre = 4f * s
            val rows = (0 until h).filter { abs(it + 0.5f - centre) <= 0.5f }
            assertTrue(rows.isNotEmpty(), "scale $s: the destination contains the centreline row")

            // The window of destination columns that samples document x in [0, length]:
            // (px + 0.5) in [4s, 1600s], i.e. px in [4s - 0.5, 1600s - 0.5].
            val from = ceil(4f * s - 0.5f).toInt().coerceIn(0, w - 1)
            val to = floor((length + 4f) * s - 0.5f).toInt().coerceIn(0, w - 1)
            assertTrue(to > from, "scale $s: the window is not empty ($from..$to of $w)")

            for (py in rows) {
                for (px in from..to) {
                    val a = (bytes[(py * w + px) * 4 + 3].toInt() and 0xFF)
                    assertTrue(a != 0, "scale $s: a hole at destination ($px, $py), on the centreline")
                }
                val run = coveredRun(bytes, w, py)
                assertTrue(run.first <= run.last, "scale $s: row $py has something covered")
                for (px in run.first..run.last) {
                    val a = (bytes[(py * w + px) * 4 + 3].toInt() and 0xFF)
                    assertTrue(a != 0, "scale $s: a hole inside the covered run at row $py, column $px")
                }
            }
            coveredByScale[s] = coveredCount(bytes, w, rows.first())
        }

        // THE SHARP FORM OF THE CLAIM. A nearest-neighbour 16x blow-up of the 1x raster is a real
        // operation, not a formula: every source pixel becomes 16x16 identical ones, so its centre
        // row is the 1x row with each alpha written out 16 times. A bitmap's edge is a staircase,
        // and a staircase has an area no re-raster reproduces.
        val at1 = assertNotNull(
            InkRaster.stamps(dabs, tip, Accumulate.WASH, 1f, 0xFF000000.toInt(), docX, docY, 1604, 8, 1f),
        )
        val blown = ByteArray(1604 * 16)
        for (x in 0 until 1604) {
            val a = at1[(4 * 1604 + x) * 4 + 3]
            for (k in 0 until 16) blown[x * 16 + k] = a
        }
        val blownCovered = blown.count { (it.toInt() and 0xFF) != 0 }
        val redrawnCovered = coveredByScale[16f]!!
        assertTrue(blownCovered > 0 && redrawnCovered > 0, "both rasters have something on them")
        assertTrue(
            blownCovered != redrawnCovered,
            "a 16x blow-up covers $blownCovered centreline pixels and the re-raster covers " +
                "$redrawnCovered: if these were equal the two would be the same picture",
        )
    }

    // ── 15. the destination is addressed, not assumed ─────────────────────────────────────

    /**
     * `docX, docY` are an ORIGIN, not a hint and not a modulo. A dab at document (100, 200) with
     * `docX = 100, docY = 200` is the top-left pixel of the destination and nothing is; move the
     * origin to 101 and the dab's centre is at destination x = -1, half outside the rectangle, so
     * the bytes must differ. A rasteriser that ignored the origin, or addressed through a tile key
     * modulo 256, would give the same answer for both.
     *
     * And the origin is a SUBTRACTION, so a destination a million document pixels away is the same
     * bytes as one at the origin: a dab at document (1 000 003, 200 000) with `docX = 1 000 000,
     * docY = 200 000` must give BYTE-IDENTICAL output to a dab at (3, 0) with `docX = 0, docY = 0`.
     * A rasteriser that ignored the origin, or addressed through a tile key modulo 256, would give
     * the same answer for both.
     */
    @Test
    fun theDestinationIsAddressedNotAssumed() {
        val tip = TipShape(corner = 2f, hardness = 1f, minPx = 1f)
        val ink = 0xFF000000.toInt()

        val matching = stamps(listOf(Dab(100f, 200f, 3f)), tip, 100, 200, 32, 32)
        assertTrue(alphaSum(matching) > 0, "the matching origin covers its own top-left pixel")
        assertEquals(255, matching[3].toInt() and 0xFF, "dab centre (100, 200) is the centre of pixel (0, 0)")

        val shifted = stamps(listOf(Dab(100f, 200f, 3f)), tip, 101, 200, 32, 32)
        assertTrue(
            !matching.contentEquals(shifted),
            "a one-pixel move of the origin is a one-pixel move of the picture",
        )

        val near = stamps(listOf(Dab(3f, 0f, 3f)), tip, 0, 0, 32, 32)
        val far = stamps(listOf(Dab(1_000_003f, 200_000f, 3f)), tip, 1_000_000, 200_000, 32, 32)
        assertTrue(near.contentEquals(far), "a million document pixels away is the same subtraction")

        // 2e9 = 1953125 * 2^10, so it needs 21 bits of significand and a float holds 24: the
        // coordinate and the origin are the SAME float, and `x - docX` is exactly 0 there.
        val atOrigin = stamps(listOf(Dab(0f, 0f, 3f)), tip, 0, 0, 32, 32)
        val huge = stamps(listOf(Dab(2_000_000_000f, 0f, 3f)), tip, 2_000_000_000, 0, 32, 32)
        assertTrue(atOrigin.contentEquals(huge), "and a coordinate at the far end of an Int does not wrap")
    }

    // ── 16. the raster budget ─────────────────────────────────────────────────────────────

    /**
     * THE BUDGET IS `render.MAX_REGION_PX` AND IT IS REFERENCED, NEVER COPIED. Both doors are
     * checked, because both allocate from the same rectangle and a guard on one and not the other
     * is a guard that can be walked around. And the boundary is on the PRODUCT: a 1 x [MAX_REGION_PX]
     * strip is inside the budget and a 2 x [MAX_REGION_PX / 2 + 1] is not.
     *
     * The at-the-cap case really does allocate 128 MiB of float scratch and 32 MiB of result and
     * then walk 8.4 million pixels, because the constant's claim is "160 MiB is affordable" and the
     * only honest way to check a claim about an allocation is to make it. `RegionRendererTest`
     * already pays exactly this cost for the same reason.
     */
    @Test
    fun theRasterBudget() {
        val tip = TipShape(corner = 2f, hardness = 1f, minPx = 1f)
        val ink = 0xFF000000.toInt()
        val one = listOf(Dab(0f, 0f, 3f))
        val over = (MAX_REGION_PX + 1L).toInt()
        assertTrue(over.toLong() > MAX_REGION_PX, "the fixture is OVER the budget")

        assertNull(
            InkRaster.stamps(one, tip, Accumulate.WASH, 1f, ink, 0, 0, 1, over, 1f),
            "1 x (MAX_REGION_PX + 1) is refused",
        )
        assertNull(
            InkRaster.fill(square(4, 20), ink, 0, 0, 1, over, 1f),
            "and so is fill: both doors allocate from the same rectangle",
        )
        assertNull(
            InkRaster.stamps(one, tip, Accumulate.WASH, 1f, ink, 0, 0, 2, (MAX_REGION_PX / 2 + 1).toInt(), 1f),
            "it is the PRODUCT that decides, so 2 x (MAX_REGION_PX / 2 + 1) is refused too",
        )

        val onBudget = assertNotNull(
            InkRaster.stamps(one, tip, Accumulate.WASH, 1f, ink, 0, 0, 1, MAX_REGION_PX.toInt(), 1f),
            "1 x MAX_REGION_PX is ON the budget and is rendered",
        )
        assertEquals((MAX_REGION_PX * 4).toInt(), onBudget.size, "px x 4 bytes, as MAX_REGION_PX's KDoc says")
        assertEquals(255, (onBudget[3].toInt() and 0xFF), "and the 1 px tall stroke really is in it")

        // A negative side is a CALLER BUG and throws, exactly as RegionRenderer.requireSize does;
        // a side of zero is a legal thing to ask about and answers with an empty array.
        for (bad in listOf(-1 to 8, 8 to -1)) {
            assertFailsWith<IllegalArgumentException>("stamps ${bad.first}x${bad.second}") {
                InkRaster.stamps(one, tip, Accumulate.WASH, 1f, ink, 0, 0, bad.first, bad.second, 1f)
            }
            assertFailsWith<IllegalArgumentException>("fill ${bad.first}x${bad.second}") {
                InkRaster.fill(square(4, 20), ink, 0, 0, bad.first, bad.second, 1f)
            }
        }
        assertEquals(0, stamps(one, tip, 0, 0, 0, 8).size, "width = 0 is an empty array")
        assertEquals(0, stamps(one, tip, 0, 0, 8, 0).size, "height = 0 is an empty array")
    }

    // ── 17. a bad number never poisons a pixel ───────────────────────────────────────────

    /**
     * Everything a corrupt file or a divide-by-nothing could put in this door has an answer that
     * is SAFE, and none of them is "a region of NaN pixels".
     *
     * A `scale` that is not a positive finite number is `null`: with it, every destination
     * coordinate is NaN or ±Infinity and the answer is a rectangle of nothing, which is exactly what
     * an off-by-one in a caller's zoom would look like on screen. A dab with a non-finite `x`/`y`
     * is SKIPPED (there is no nearby pixel to clamp it to, and one NaN reaching `TipMath.coverage`
     * would NaN every pixel it touched and then quantise to a hole in a line that was fine). A dab
     * with a non-finite `radius` is drawn as radius 0, which covers nothing, and the OTHER dabs are
     * untouched. A NaN `opacity` is read as 1 — the same rule `MaskPaint.opacityOf` uses, for the
     * same reason: a NaN that silently multiplied nothing looks exactly like a stroke at 0%.
     */
    @Test
    fun aBadNumberNeverPoisonsAPixel() {
        val tip = TipShape(corner = 2f, hardness = 1f, minPx = 1f)
        val ink = 0xFF000000.toInt()
        val good = listOf(Dab(3f, 0f, 3f))
        for (scale in floatArrayOf(Float.NaN, 0f, Float.POSITIVE_INFINITY, -1f)) {
            assertNull(
                InkRaster.stamps(good, tip, Accumulate.WASH, 1f, ink, 0, 0, 16, 16, scale),
                "stamps with scale $scale",
            )
            assertNull(
                InkRaster.fill(square(4, 20), ink, 0, 0, 16, 16, scale),
                "fill with scale $scale",
            )
        }

        val blank = stamps(listOf(Dab(Float.NaN, 0f, 3f)), tip, 0, 0, 16, 16)
        assertTrue(blank.all { it.toInt() == 0 }, "a dab with a NaN x draws nothing at all")
        val blankY = stamps(listOf(Dab(3f, Float.NaN, 3f)), tip, 0, 0, 16, 16)
        assertTrue(blankY.all { it.toInt() == 0 }, "nor with a NaN y")

        val oneGood = stamps(good, tip, 0, 0, 16, 16)
        val withBadRadius = stamps(listOf(Dab(8f, 8f, Float.NaN)) + good, tip, 0, 0, 16, 16)
        assertTrue(oneGood.contentEquals(withBadRadius), "a NaN radius draws nothing and changes nothing else")
        assertTrue(alphaSum(withBadRadius) > 0, "and the good dab really is there")

        val nanOpacity = assertNotNull(
            InkRaster.stamps(good, tip, Accumulate.BUILD_UP, Float.NaN, ink, 0, 0, 16, 16, 1f),
        )
        val oneOpacity = assertNotNull(
            InkRaster.stamps(good, tip, Accumulate.BUILD_UP, 1f, ink, 0, 0, 16, 16, 1f),
        )
        assertTrue(nanOpacity.contentEquals(oneOpacity), "a NaN opacity is 1, as MaskPaint.opacityOf has it")
    }

    // ── 18. fill is non-zero, and it is JB-2.05a's fill ───────────────────────────────────

    /**
     * A loop drawn twice has the SAME bytes as the same loop drawn once, and that is the whole
     * content of "non-zero winding": a doubled loop winds twice, which is not zero, so it is still
     * solid. An even-odd fill would punch a hole in the middle of the doubled loop and fail here,
     * and the point of the test is that it fails on the BYTES rather than on a claim.
     *
     * The two refusals are decisions rather than surprises, so they are stated here: a polygon of
     * fewer than three points encloses no area, and `SelectionMask.polygon` answers
     * `SelectionMask.EMPTY` for a bounding box wider than `MAX_SELECT_SPAN` (16 384 doc px) — the
     * fill pen is refused by the LASSO's cap, and that ceiling is the repo's, not this row's.
     */
    @Test
    fun fillIsNonZeroAndItIsTheReposFill() {
        val one = square(4, 24)
        val twice = one + one
        val a = assertNotNull(InkRaster.fill(one, 0xFF000000.toInt(), 0, 0, 32, 32, 1f))
        val b = assertNotNull(InkRaster.fill(twice, 0xFF000000.toInt(), 0, 0, 32, 32, 1f))
        assertTrue(a.contentEquals(b), "a loop drawn twice is the same solid shape as a loop drawn once")
        assertTrue(alphaSum(a) > 0, "NON-VACUITY: the shape really is filled")

        val tooFew = assertNotNull(InkRaster.fill(listOf(Pt(4.0, 4.0), Pt(20.0, 4.0)), 0xFF000000.toInt(), 0, 0, 32, 32, 1f))
        assertTrue(tooFew.all { it.toInt() == 0 }, "two points enclose no area")

        val tooWide = assertNotNull(
            InkRaster.fill(listOf(Pt(0.0, 0.0), Pt(20_000.0, 0.0), Pt(0.0, 10.0)), 0xFF000000.toInt(), 0, 0, 8, 8, 1f),
        )
        assertTrue(tooWide.all { it.toInt() == 0 }, "past MAX_SELECT_SPAN the lasso's own cap answers, with nothing")
    }

    // ── 19. fill composites through MaskPaint ────────────────────────────────────────────

    /**
     * `fill` writes `MaskPaint.pixel(FILL, …)`'s answer, not the colour into three channels.
     *
     * The arithmetic, for a fully covered pixel over an all-zero destination, with `colorRgb`'s own
     * alpha byte as the opacity (because `MaskPaint.pixel` ignores `argb`'s alpha and takes
     * `opacity` separately, so `fill` has to hand it over):
     *
     *     a  = 255/255 x 128/255 = 0.5019608
     *     A' = round255(255 x a)      = round255(128.0)     = 128
     *     R' = round255(255 x a + 0)  = round255(128.0)     = 128
     *
     * So a half-transparent red fill is alpha 128 AND red 128 — premultiplied, which is the
     * invariant a white halo on export is made of. A `colorRgb` of `0x00FF0000` has an alpha byte
     * of 0, so it fills NOTHING: `fill`'s opacity is the colour's own alpha.
     */
    @Test
    fun fillCompositesThroughMaskPaint() {
        val outline = square(4, 28)
        val half = assertNotNull(InkRaster.fill(outline, 0x80FF0000.toInt(), 0, 0, 32, 32, 1f))
        val at = ((16 * 32) + 16) * 4
        assertEquals(128, half[at + 3].toInt() and 0xFF, "alpha 128: 255 x 128/255, rounded")
        assertEquals(128, half[at].toInt() and 0xFF, "and red 128: premultiplied, as MaskPaint.pixel answers")
        assertEquals(0, half[at + 1].toInt() and 0xFF, "green 0")
        assertEquals(0, half[at + 2].toInt() and 0xFF, "blue 0")

        val clear = assertNotNull(InkRaster.fill(outline, 0x00FF0000.toInt(), 0, 0, 32, 32, 1f))
        assertTrue(clear.all { it.toInt() == 0 }, "a colour whose own alpha is 0 fills nothing")
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────

    /** The shipped Ink's numbers, with `hardness` raised to 1 so a test's edge is predictable. */
    private fun inkBrush() = BrushPreset(
        id = "joybrush.ink",
        name = "Ink",
        engine = "stamp",
        size = Param(6f),
        tip = TipSpec(corner = 2f, hardness = Param(1f)),
        spacing = 0.04f,
        accumulate = "wash",
    )

    /** `n` samples along `x = 0, step, 2 * step, …` at height `y`, 8 ms apart. */
    private fun line(n: Int, step: Float, y: Float) = StrokeRecord(
        id = "s1",
        brushId = "joybrush.ink",
        seed = 7L,
        smoothing = 0f,
        screenPerDoc = 1f,
        samples = (0 until n).map { PenSample(x = it * step, y = y, timeMs = it * 8.0) },
    )

    /** An axis-aligned square of integer document corners from [from] to [to]. */
    private fun square(from: Int, to: Int) = listOf(
        Pt(from.toDouble(), from.toDouble()),
        Pt(to.toDouble(), from.toDouble()),
        Pt(to.toDouble(), to.toDouble()),
        Pt(from.toDouble(), to.toDouble()),
    )

    private fun stamps(dabs: List<Dab>, tip: TipShape, docX: Int, docY: Int, w: Int, h: Int) =
        assertNotNull(InkRaster.stamps(dabs, tip, Accumulate.WASH, 1f, 0xFF000000.toInt(), docX, docY, w, h, 1f))

    private fun alphaSum(bytes: ByteArray): Int = bytes.sumOf { (it.toInt() and 0xFF) }

    /** The first and last destination column of [row] that has any alpha at all. */
    private fun coveredRun(bytes: ByteArray, w: Int, row: Int): IntRange {
        var first = -1
        var last = -1
        for (x in 0 until w) {
            if ((bytes[(row * w + x) * 4 + 3].toInt() and 0xFF) == 0) continue
            if (first < 0) first = x
            last = x
        }
        if (first < 0) return IntRange.EMPTY
        return first..last
    }

    private fun coveredCount(bytes: ByteArray, w: Int, row: Int): Int {
        var n = 0
        for (x in 0 until w) if ((bytes[(row * w + x) * 4 + 3].toInt() and 0xFF) != 0) n++
        return n
    }

    /**
     * Element-by-element float equality with NO TOLERANCE, and a failure message that says which
     * pixel and which channel — a FloatArray that differs at one index out of 262 144 says nothing
     * at all on its own.
     */
    private fun assertIdentical(expected: FloatArray, actual: FloatArray, what: String) {
        assertEquals(expected.size, actual.size, "$what: length")
        for (i in expected.indices) {
            val same = expected[i] == actual[i] || (expected[i].isNaN() && actual[i].isNaN())
            if (!same) {
                fail(
                    "$what: index $i (pixel ${i / 4}, channel ${i % 4}) is ${expected[i]} and " +
                        "${actual[i]}. There is no tolerance here on purpose: the two are the same " +
                        "expression in the same order, and any difference is a second rule.",
                )
            }
        }
    }
}
