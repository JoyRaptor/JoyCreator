package cc.joycreator.joybrush.core.fill

import cc.joycreator.joybrush.core.brush.FillPen
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkRaster
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JB-NOW-23 — fill contour topology regression.
 *
 * Reads [FillTrace] and extends [FillTraceTest] with the topologies it does not
 * pin down: bounded masks with one and two holes, far-apart islands sharing one
 * record, border-touching columns, a narrow 1 px corridor, the 1 px versus 2 px
 * gap rule implied by the documented 0.75 px growth, and the supported diagonal
 * overlap (grown squares overlap, so corner-touching pixels merge while far
 * diagonals stay split).
 *
 * Every expectation is hand-derived or comes from the independent grown-rectangle
 * union below, which samples the 16 Lasso positions per pixel and never uses the
 * production sweep, union, winding, bridge or SelectionMask code. Vertex order is
 * deliberately never asserted: the contract does not canonicalize it, so tests
 * assert exact determinism across runs (same list twice) plus order-invariant
 * properties (bounds, coverage, quarter-pixel grid). Foundation geometry only;
 * no brush-appearance or runtime-wiring claim.
 */
class FillTraceTopologyCorpusTest {
    private val white = 0xFFFFFFFF.toInt()

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun trace(w: Int, h: Int, mask: ByteArray): StrokeRecord =
        FillTrace.trace(w, h, mask, "shape", "fill-brush", white).single()

    private fun raster(record: StrokeRecord, x: Int, y: Int, w: Int, h: Int): ByteArray =
        InkRaster.fill(
            FillPen.outline(record.samples, record.smoothing, record.screenPerDoc),
            record.colorArgb, x, y, w, h, 1f,
        ) ?: error("fill raster returned null for $w x $h at $x,$y")

    private fun alpha(bytes: ByteArray, w: Int, x: Int, y: Int): Int =
        bytes[(y * w + x) * 4 + 3].toInt() and 0xFF

    private fun countSelected(mask: ByteArray): Int = mask.count { it.toInt() != 0 }

    /**
     * Independent oracle: each selected pixel covers
     * [px - 0.75, px + 1.75) in both axes ([FillTrace.GROW_DOC_PX]), sampled at
     * the 16 Lasso sub-positions. No contour, sweep, winding or bridge logic.
     */
    private fun assertMatchesGrownUnion(
        w: Int,
        h: Int,
        mask: ByteArray,
        dx: Int = -1,
        dy: Int = -1,
        rw: Int = w + 2,
        rh: Int = h + 2,
        label: String,
    ) {
        val record = trace(w, h, mask)
        val bytes = raster(record, dx, dy, rw, rh)
        for (y in 0 until rh) for (x in 0 until rw) {
            val docX = dx + x
            val docY = dy + y
            var hits = 0
            for (sy in 0..3) for (sx in 0..3) {
                val xx = docX + (sx + .5) / 4
                val yy = docY + (sy + .5) / 4
                var inside = false
                for (i in mask.indices) {
                    if (mask[i].toInt() == 0) continue
                    val px = i % w
                    val py = i / w
                    if (xx >= px - .75 && xx < px + 1.75 && yy >= py - .75 && yy < py + 1.75) {
                        inside = true
                        break
                    }
                }
                if (inside) hits++
            }
            val expected = (hits * 255 + 8) / 16
            val at = (y * rw + x) * 4
            for (c in 0..3) assertEquals(
                expected, bytes[at + c].toInt() and 255,
                "$label grown-union at doc ($docX,$docY) channel $c",
            )
        }
    }

    private fun assertFoundationInvariants(record: StrokeRecord, label: String) {
        assertEquals("shape", record.id, "$label id")
        assertEquals("fill-brush", record.brushId, "$label brush")
        assertEquals(0f, record.smoothing, "$label smoothing")
        assertEquals(4f, record.screenPerDoc, "$label screenPerDoc")
        assertTrue(record.samples.isNotEmpty(), "$label has samples")
        for (s in record.samples) {
            assertTrue(s.isPlaceable, "$label sample placeable at ${s.x},${s.y}")
            assertTrue(!s.predicted, "$label no predicted samples")
            // Quarter-pixel grid: x * 4 is an integer (exact for small quarters).
            assertTrue(s.x * 4 == (s.x * 4).toInt().toFloat(), "$label quarter x ${s.x}")
            assertTrue(s.y * 4 == (s.y * 4).toInt().toFloat(), "$label quarter y ${s.y}")
        }
        for (i in 1 until record.samples.size) {
            val a = record.samples[i - 1]
            val b = record.samples[i]
            assertTrue(a.x != b.x || a.y != b.y, "$label no consecutive duplicate at $i")
        }
    }

    // ── 1. bounded solid ────────────────────────────────────────────────────

    @Test fun solidInteriorBlockGrowsThreeQuartersOnEverySide() {
        val w = 6
        val h = 5
        // Repro JB-NOW-23/01: 3 x 2 block at x 1..3, y 1..2 (6 px, fully bounded).
        val mask = ByteArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in 1..3 && y in 1..2) 1 else 0
        }
        assertEquals(6, countSelected(mask), "hand count 3 x 2")
        val before = mask.copyOf()
        val record = trace(w, h, mask)
        assertContentEquals(before, mask, "trace must not mutate its input")
        assertFoundationInvariants(record, "solid block")
        // Grown: x [1 - .75, 3 + 1.75) = [0.25, 4.75); y [0.25, 3.75).
        assertEquals(0.25f, record.samples.minOf { it.x }, "grown left")
        assertEquals(4.75f, record.samples.maxOf { it.x }, "grown right")
        assertEquals(0.25f, record.samples.minOf { it.y }, "grown top")
        assertEquals(3.75f, record.samples.maxOf { it.y }, "grown bottom")
        // Determinism without demanding a canonical vertex order.
        assertEquals(record.samples, trace(w, h, mask).samples, "same input, same vertices")
        assertMatchesGrownUnion(w, h, mask, label = "solid block")
    }

    // ── 2. holes ────────────────────────────────────────────────────────────

    @Test fun singleBoundedHoleStaysEmptyWhileOuterStaysFilled() {
        val w = 7
        // Repro JB-NOW-23/02: full 7 x 7 less the 3 x 3 hole x 2..4, y 2..4.
        val mask = ByteArray(w * w) { i ->
            val x = i % w
            val y = i / w
            if (x in 2..4 && y in 2..4) 0 else 1
        }
        assertEquals(49 - 9, countSelected(mask), "hand count 49 - 9")
        val before = mask.copyOf()
        val record = trace(w, w, mask)
        assertContentEquals(before, mask, "trace must not mutate its input")
        assertFoundationInvariants(record, "single hole")
        val image = raster(record, 0, 0, w, w)
        // Centre of the 3 x 3 hole sits in the grown gap [2.75, 4.25); outer
        // pixel (0,0) is fully inside its own grown square.
        assertEquals(0, alpha(image, w, 3, 3), "hole centre is empty")
        assertEquals(255, alpha(image, w, 0, 0), "outer corner is full")
        assertEquals(record.samples, trace(w, w, mask).samples, "deterministic vertices")
        assertMatchesGrownUnion(w, w, mask, dx = 0, dy = 0, rw = w, rh = w, label = "single hole")
    }

    @Test fun twoSeparateHolesStayEmptyAndWallBetweenThemStaysFilled() {
        val w = 14
        val h = 7
        // Repro JB-NOW-23/03: full 14 x 7 less two 3 x 3 holes
        // (x 2..4 and x 9..11, both y 2..4). Outer = 98 - 18 = 80.
        val mask = ByteArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if ((x in 2..4 || x in 9..11) && y in 2..4) 0 else 1
        }
        assertEquals(80, countSelected(mask), "hand count 98 - 9 - 9")
        val before = mask.copyOf()
        val record = trace(w, h, mask)
        assertContentEquals(before, mask, "trace must not mutate its input")
        assertFoundationInvariants(record, "two holes")
        val image = raster(record, 0, 0, w, h)
        assertEquals(0, alpha(image, w, 3, 3), "first hole centre is empty")
        assertEquals(0, alpha(image, w, 10, 3), "second hole centre is empty")
        // x = 6 is solid wall between the holes; its own grown square covers it.
        assertEquals(255, alpha(image, w, 6, 3), "wall between holes is full")
        assertEquals(record.samples, trace(w, h, mask).samples, "deterministic vertices")
        assertMatchesGrownUnion(w, h, mask, dx = 0, dy = 0, rw = w, rh = h, label = "two holes")
    }

    // ── 3. disconnected islands ─────────────────────────────────────────────

    @Test fun threeFarIslandsShareOneRecordWithoutInventedFill() {
        val w = 11
        val h = 7
        // Repro JB-NOW-23/04: three 2 x 2 islands, 12 px total. Grown extents:
        // A x [-.75, 2.75), B x [7.25, 10.75), C x [3.25, 6.75) y [3.25, 6.75),
        // so no pair of grown squares overlaps.
        val mask = ByteArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if ((x in 0..1 && y in 0..1) || (x in 8..9 && y in 0..1) || (x in 4..5 && y in 4..5)) 1 else 0
        }
        assertEquals(12, countSelected(mask), "hand count 4 + 4 + 4")
        val before = mask.copyOf()
        val records = FillTrace.trace(w, h, mask, "shape", "fill-brush", white)
        assertEquals(1, records.size, "every component travels in one record")
        val record = records.single()
        assertContentEquals(before, mask, "trace must not mutate its input")
        assertFoundationInvariants(record, "three islands")
        val image = raster(record, 0, 0, w, h)
        assertEquals(255, alpha(image, w, 0, 0), "island A filled")
        assertEquals(255, alpha(image, w, 8, 0), "island B filled")
        assertEquals(255, alpha(image, w, 4, 4), "island C filled")
        // (5,1) sits between A/B in x and above C in y; (3,2) sits in the
        // 0.5 px grown gap between A (to 2.75) and C (from 3.25).
        assertEquals(0, alpha(image, w, 5, 1), "no invented fill between A and B")
        assertEquals(0, alpha(image, w, 3, 2), "no invented fill between A and C")
        assertEquals(record.samples, trace(w, h, mask).samples, "deterministic vertices")
        assertMatchesGrownUnion(w, h, mask, dx = 0, dy = 0, rw = w, rh = h, label = "three islands")
    }

    // ── 4. border-touching ──────────────────────────────────────────────────

    @Test fun borderTouchingColumnGrowsBeyondTheReferenceEdge() {
        val w = 5
        val h = 5
        // Repro JB-NOW-23/05: first column only, touching all four reference
        // edges in the thin sense (x = 0, y 0..4). Grown x [-.75, 1.75).
        val mask = ByteArray(w * h) { i -> if (i % w == 0) 1 else 0 }
        assertEquals(5, countSelected(mask), "hand count one column")
        val before = mask.copyOf()
        val record = trace(w, h, mask)
        assertContentEquals(before, mask, "trace must not mutate its input")
        assertFoundationInvariants(record, "border column")
        assertEquals(-0.75f, record.samples.minOf { it.x }, "growth crosses the left edge")
        assertEquals(1.75f, record.samples.maxOf { it.x }, "growth stops 0.75 past x = 0")
        assertEquals(-0.75f, record.samples.minOf { it.y }, "growth crosses the top edge")
        assertEquals(5.75f, record.samples.maxOf { it.y }, "growth crosses the bottom edge")
        val tight = raster(record, 0, 0, w, h)
        assertEquals(255, alpha(tight, w, 0, 2), "border pixel is full")
        // x = 1 straddles the grown edge at 1.75: subsamples 1.125/1.375/1.625
        // inside, 1.875 outside -> 12 of 16 -> (12 * 255 + 8) / 16 = 191.
        assertEquals(191, alpha(tight, w, 1, 2), "0.75 px growth gives 12/16 coverage")
        assertEquals(0, alpha(tight, w, 2, 2), "nothing invented past the growth")
        assertMatchesGrownUnion(w, h, mask, label = "border column")
    }

    // ── 5. narrow corridor ──────────────────────────────────────────────────

    @Test fun narrowOnePixelCorridorKeepsTwoChambersConnected() {
        val w = 9
        val h = 5
        // Repro JB-NOW-23/06: 3 x 3 chambers (x 0..2 and 6..8, y 1..3) joined by
        // y = 2, x 3..5. Total 9 + 9 + 3 = 21; the corridor is one pixel tall.
        val mask = ByteArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if ((x in 0..2 && y in 1..3) || (x in 6..8 && y in 1..3) || (y == 2 && x in 3..5)) 1 else 0
        }
        assertEquals(21, countSelected(mask), "hand count 9 + 9 + 3")
        val before = mask.copyOf()
        val record = trace(w, h, mask)
        assertContentEquals(before, mask, "trace must not mutate its input")
        assertFoundationInvariants(record, "corridor")
        val image = raster(record, 0, 0, w, h)
        assertEquals(255, alpha(image, w, 1, 2), "left chamber is full")
        assertEquals(255, alpha(image, w, 7, 2), "right chamber is full")
        // Corridor pixel (4,2) grown [3.25, 5.75) x [1.25, 3.75) covers it.
        assertEquals(255, alpha(image, w, 4, 2), "corridor pixel is full")
        // (4,0) and (4,4) are a full pixel beyond the grown corridor/walls.
        assertEquals(0, alpha(image, w, 4, 0), "no invented fill above the corridor")
        assertEquals(0, alpha(image, w, 4, 4), "no invented fill below the corridor")
        assertEquals(record.samples, trace(w, h, mask).samples, "deterministic vertices")
        assertMatchesGrownUnion(w, h, mask, dx = 0, dy = 0, rw = w, rh = h, label = "corridor")
    }

    // ── 6. gap rule implied by 0.75 px growth ───────────────────────────────

    @Test fun onePixelGapMergesAfterGrowthWhileTwoPixelGapLeavesPartialCoverage() {
        // Repro JB-NOW-23/07a: 1 px gap at x = 3. Left grown to 3.75, right
        // grown from 3.25, so every sub-position of (3,1) is inside one side.
        val a = ByteArray(8 * 4) { i ->
            val x = i % 8
            val y = i / 8
            if (y in 1..2 && (x in 1..2 || x in 4..5)) 1 else 0
        }
        assertEquals(8, countSelected(a), "hand count 4 + 4")
        val recordA = trace(8, 4, a)
        assertFoundationInvariants(recordA, "1 px gap")
        assertEquals(255, alpha(raster(recordA, 0, 0, 8, 4), 8, 3, 1), "1 px gap is sealed by growth")
        assertMatchesGrownUnion(8, 4, a, dx = 0, dy = 0, rw = 8, rh = 4, label = "1 px gap")

        // Repro JB-NOW-23/07b: 2 px gap at x = 3..4. Left grown to 3.75, right
        // grown from 4.25. Pixel (3,1) keeps 3 of 4 x-positions (12 of 16 ->
        // 191); pixel (4,1) keeps the mirror 12 of 16. The grown shapes are two
        // islands joined only by zero-winding bridges, still one record.
        val b = ByteArray(9 * 4) { i ->
            val x = i % 9
            val y = i / 9
            if (y in 1..2 && (x in 1..2 || x in 5..6)) 1 else 0
        }
        assertEquals(8, countSelected(b), "hand count 4 + 4")
        val recordB = trace(9, 4, b)
        assertFoundationInvariants(recordB, "2 px gap")
        val imageB = raster(recordB, 0, 0, 9, 4)
        assertEquals(191, alpha(imageB, 9, 3, 1), "2 px gap leaves 12/16 on the left lip")
        assertEquals(191, alpha(imageB, 9, 4, 1), "2 px gap leaves 12/16 on the right lip")
        assertMatchesGrownUnion(9, 4, b, dx = 0, dy = 0, rw = 9, rh = 4, label = "2 px gap")
    }

    // ── 7. supported diagonal adjacency ─────────────────────────────────────

    @Test fun touchingDiagonalMergesWhileFarDiagonalStaysSeparate() {
        // Repro JB-NOW-23/08a: corner-touching (1,1) + (2,2). Grown squares
        // [0.25, 2.75) and [1.25, 3.75) overlap, so (1,2) has 15 of 16
        // sub-positions inside the union -> (15 * 255 + 8) / 16 = 239.
        val touching = ByteArray(25) { i ->
            val x = i % 5
            val y = i / 5
            if ((x == 1 && y == 1) || (x == 2 && y == 2)) 1 else 0
        }
        assertEquals(2, countSelected(touching), "hand count two diagonal pixels")
        val recordTouching = trace(5, 5, touching)
        assertFoundationInvariants(recordTouching, "touching diagonal")
        assertEquals(239, alpha(raster(recordTouching, 0, 0, 5, 5), 5, 1, 2), "overlap fills 15/16")
        assertMatchesGrownUnion(5, 5, touching, dx = 0, dy = 0, rw = 5, rh = 5, label = "touching diagonal")

        // Repro JB-NOW-23/08b: far diagonal (0,0) + (3,3). Grown [-.75, 1.75)
        // and [2.25, 4.75) leave a 0.5 px gap, so (1,2) has 0 of 16 inside.
        val far = ByteArray(25) { i ->
            val x = i % 5
            val y = i / 5
            if ((x == 0 && y == 0) || (x == 3 && y == 3)) 1 else 0
        }
        assertEquals(2, countSelected(far), "hand count two far pixels")
        val recordFar = trace(5, 5, far)
        assertFoundationInvariants(recordFar, "far diagonal")
        val imageFar = raster(recordFar, 0, 0, 5, 5)
        assertEquals(0, alpha(imageFar, 5, 1, 2), "far diagonal leaves a true gap")
        assertEquals(255, alpha(imageFar, 5, 0, 0), "first island still filled")
        assertEquals(255, alpha(imageFar, 5, 3, 3), "second island still filled")
        assertMatchesGrownUnion(5, 5, far, dx = 0, dy = 0, rw = 5, rh = 5, label = "far diagonal")
    }

    // ── 8. hand-drawn polygon cross-check ───────────────────────────────────

    @Test fun handDrawnRectangleMatchesTraceCoverageForSolidBlock() {
        val w = 5
        val h = 4
        // Repro JB-NOW-23/09: 2 x 2 block x 1..2, y 1..2. Grown bounds are the
        // hand rectangle below; this is the "hand-drawn polygon" check.
        val mask = ByteArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in 1..2 && y in 1..2) 1 else 0
        }
        assertEquals(4, countSelected(mask), "hand count 2 x 2")
        val record = trace(w, h, mask)
        assertFoundationInvariants(record, "hand rectangle")
        val hand = listOf(Pt(0.25, 0.25), Pt(3.75, 0.25), Pt(3.75, 3.75), Pt(0.25, 3.75))
        val viaTrace = raster(record, 0, 0, w, h)
        val viaHand = InkRaster.fill(hand, white, 0, 0, w, h, 1f)
            ?: error("hand polygon raster returned null")
        assertContentEquals(viaHand, viaTrace, "trace covers exactly the hand rectangle")
        assertMatchesGrownUnion(w, h, mask, dx = 0, dy = 0, rw = w, rh = h, label = "hand rectangle")
    }
}
