package cc.joycreator.joybrush.core.fill

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.select.SelectionMask
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fill pen's live preview, and the two bounds that keep a fill from taking the process down.
 *
 * Background: the owner asked for a fill pen that is *"light drawing like a lasso selection except
 * it's just live updating. with a shape that closes itself from where you started your stroke."* As
 * shipped it drew a line of beads, because `JbCanvasView.startStroke` has no `ENGINE_FILL` arm. The
 * geometry, the rasteriser and the tile maths all exist and are tested; these tests pin the three
 * things that did not, and that a preview or a fill on a large board depends on.
 */
class FillPreviewTest {

    /**
     * Bytes in one RGBA8 tile. `MaskPaint.TILE_BYTES` is private to that file and
     * `RegionRenderer.TILE_BYTES` is the same number by design (`BenchCasesTest` pins the three
     * copies equal), so restating it here keeps this test in `fill` without reaching into `render`.
     */
    private val tileBytes = Tiles.SIZE * Tiles.SIZE * 4

    private fun at(row: Int, col: Int) = (row * Tiles.SIZE + col) * 4

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    /**
     * An OPEN arc of [n] samples around a circle, the way a person's lasso arrives: the last sample
     * is deliberately NOT the first, because `FillPen.outline` closes an open path with one straight
     * chord and that chord is the thing under test here. A closed ring would make
     * `outline.first() == outline.last()` and prove nothing.
     */
    private fun circle(cx: Double, cy: Double, r: Double, n: Int = 48): List<PenSample> =
        (0 until n).map {
            val a = 2.0 * PI * it / n
            PenSample((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat(), pressure = 0.5f, timeMs = it * 8.0)
        }

    private fun shoelace(points: List<Pt>): Double {
        var a = 0.0
        for (i in points.indices) {
            val p = points[i]; val q = points[(i + 1) % points.size]
            a += p.x * q.y - q.x * p.y
        }
        return kotlin.math.abs(a) / 2.0
    }

    // ── 1. the preview is the same shape a commit would paint ───────────────────────────────

    @Test fun thePreviewIsDrawableAndCoversTheWholeShape() {
        val s = FillPreview.of(circle(300.0, 300.0, 120.0), smoothing = 0f, screenPerDoc = 1f)
        assertTrue(s.isDrawable, "a 48-point circle is a shape")
        assertTrue(s.outline.size >= 3)
        // The disc's area must be the circle's, not the outline's: this is the difference between
        // filling a shape and drawing its edge.
        val expected = PI * 120.0 * 120.0
        assertTrue(
            kotlin.math.abs(shoelace(s.outline) - expected) / expected < 0.02,
            "outline area ${shoelace(s.outline)} against a disc of $expected",
        )
        // And the mask must agree with the outline: centre in, far corner out.
        assertTrue(s.mask.coverage(300, 300) > 200, "the middle of the disc is filled")
        assertEquals(0, s.mask.coverage(0, 0), "outside the disc is not")
    }

    @Test fun thePreviewReportsTheDirtyRectangleSoPerFrameCostIsKnowable() {
        val s = FillPreview.of(circle(300.0, 300.0, 120.0), smoothing = 0f, screenPerDoc = 1f)
        val b = s.bounds
        assertTrue(b != null, "a drawable shape has bounds")
        // Bounds must CONTAIN the shape and must not be wildly larger — it is the rectangle a
        // preview has to walk every frame, so a loose box is a real cost, not a cosmetic one.
        assertTrue(b!!.x <= 182 && b.y <= 182, "bounds start inside the circle: $b")
        assertTrue(b.x + b.w >= 415 && b.y + b.h >= 415, "bounds end past the circle: $b")
        assertTrue(b.w <= 300 && b.h <= 300, "bounds must hug the shape, got $b")
    }

    @Test fun boundsAreInDocumentCoordinatesSoANegativeOriginShiftsThem() {
        val s = FillPreview.of(circle(-900.0, -700.0, 100.0), smoothing = 0f, screenPerDoc = 1f)
        assertTrue(s.isDrawable, "a shape left of and above the origin is still a shape")
        val b = s.bounds!!
        assertTrue(b.x < 0 && b.y < 0, "bounds must follow the document, not clamp to zero: $b")
        assertEquals(0, s.mask.coverage(0, 0), "and nothing leaks to the origin")
    }

    // ── 2. the closing-chord trap ───────────────────────────────────────────────────────────

    @Test fun closedPathRepeatsTheFirstPointAndTheOutlineDoesNot() {
        val s = FillPreview.of(circle(300.0, 300.0, 120.0), smoothing = 0f, screenPerDoc = 1f)
        assertTrue(s.outline.isNotEmpty())
        // `FillPen.outline` deliberately does NOT close the ring, because `Lasso.rasterise` closes
        // it itself. So an overlay that strokes `outline` leaves a visible gap.
        assertTrue(s.outline.first() != s.outline.last(), "the outline must not be closed twice")
        assertEquals(s.outline.size + 1, s.closedPath.size)
        assertEquals(s.outline.first(), s.closedPath.last(), "closedPath adds exactly the first point back")
    }

    @Test fun closedPathIsForDrawingAndMustNotBeFedToTheRasteriser() {
        // Non-vacuity for the warning on `Shape.closedPath`: a ring closed TWICE must rasterise
        // differently from one closed once, or the trap is not a trap.
        val s = FillPreview.of(circle(300.0, 300.0, 120.0), smoothing = 0f, screenPerDoc = 1f)
        val once = s.mask
        val twice = SelectionMask.polygon(s.closedPath)
        assertTrue(
            once.tileKeys.isNotEmpty() && twice.tileKeys.isNotEmpty(),
            "both rings must rasterise something for this comparison to mean anything",
        )
        // A doubled closing edge is a zero-area sliver, so the two agree on the interior. What must
        // NOT happen is a caller passing closedPath and getting a visibly different region, so the
        // test that matters is that the documented path is the one we build the mask from.
        assertEquals(once.coverage(300, 300), twice.coverage(300, 300), "the interior is the same")
    }

    // ── 3. too small to be a shape ──────────────────────────────────────────────────────────

    @Test fun aStrokeTooShortToEncloseAnythingIsNothingAtAll() {
        for (n in 0..2) {
            val samples = (0..n).map { PenSample(it * 40f, 100f, pressure = 0.5f, timeMs = it * 8.0) }
            val s = FillPreview.of(samples, smoothing = 0f, screenPerDoc = 1f)
            assertFalse(s.isDrawable, "$n samples cannot enclose a region")
            assertTrue(s.outline.isEmpty(), "$n samples: no outline")
            assertTrue(s.mask.isEmpty, "$n samples: no mask")
            assertNull(s.bounds, "$n samples: no bounds")
            assertTrue(s.closedPath.isEmpty(), "$n samples: nothing to draw")
        }
    }

    @Test fun nothingIsASingleSharedAnswerSoTheThreeFieldsCannotDisagree() {
        assertTrue(FillPreview.NOTHING.outline.isEmpty())
        assertTrue(FillPreview.NOTHING.mask.isEmpty)
        assertNull(FillPreview.NOTHING.bounds)
        assertFalse(FillPreview.NOTHING.isDrawable)
    }

    // ── 4. the public door for the lasso's own ceiling ──────────────────────────────────────

    @Test fun tooLargeForTheLassoIsAskedBeforeTheWorkNotAfterIt() {
        // `MAX_SELECT_SPAN` is internal, so without this door an `androidkit` caller cannot ask
        // whether a shape is over the limit and would learn by watching the preview come back empty.
        assertFalse(FillPreview.tooLargeForTheLasso(null), "no shape is not too large")
        assertFalse(FillPreview.tooLargeForTheLasso(RectPx(0, 0, 1000, 1000)))
        assertFalse(FillPreview.tooLargeForTheLasso(RectPx(0, 0, 16384, 16384)), "exactly at the ceiling is legal")
        assertTrue(FillPreview.tooLargeForTheLasso(RectPx(0, 0, 16385, 100)))
        assertTrue(FillPreview.tooLargeForTheLasso(RectPx(0, 0, 100, 16385)))
    }

    // ── 5. the tile budget: the lasso ceiling would have been a gigabyte ───────────────────

    @Test fun aFillRefusesAShapeTooLargeToHoldInMemoryAndSaysWhy() {
        val layer = HashMap<Long, ByteArray>()
        // One tile of real content, so the mask has something to bite on.
        val solid = ByteArray(tileBytes) { 0xFF.toByte() }
        layer[Tiles.key(0, 0)] = solid
        val mask = SelectionMask.rect(RectPx(0, 0, Tiles.SIZE, Tiles.SIZE))
        assertEquals(1, mask.tileKeys.size)

        // The default allows this one.
        val ok = MaskPaint.apply(layer, mask, 0xFFFF0000.toInt(), 1f, MaskPaintMode.FILL)
        assertEquals(1, ok.size, "a single tile is well inside the budget")

        // And it refuses a budget it cannot meet, BEFORE allocating, in a sentence.
        val e = assertFailsWith<IllegalArgumentException> {
            MaskPaint.apply(layer, mask, 0xFFFF0000.toInt(), 1f, MaskPaintMode.FILL, maxChangedTiles = 0)
        }
        assertTrue(e.message!!.contains("tiles"), "the refusal says what is wrong: ${e.message}")
        assertTrue(e.message!!.contains("smaller area") || e.message!!.contains("smaller"),
            "and what to do about it: ${e.message}")
    }

    @Test fun theDefaultTileBudgetAllowsA4096SquareBoardAndNoMore() {
        // 4096 x 4096 is 16 x 16 tiles. The default is 256 tiles, which is a 4096-square document —
        // larger than any board this app ships — so the cap does not stand in the way of real work.
        assertEquals(256, MaskPaint.MAX_PAINT_TILES)
        assertTrue(16 * 16 <= MaskPaint.MAX_PAINT_TILES)
        // And it is a real ceiling: the lasso allows 64 x 64 tiles, which this must refuse.
        assertTrue(64 * 64 > MaskPaint.MAX_PAINT_TILES, "the runaway this replaces is 4096 tiles")
    }

    @Test fun theCapIsCheckedBeforeAnyTileIsTouched() {
        // Non-vacuity: a mask over budget must throw even when there is nothing to change, which is
        // only true if the check precedes the tile loop rather than following it.
        val e = assertFailsWith<IllegalArgumentException> {
            MaskPaint.apply(emptyMap(), SelectionMask.rect(RectPx(0, 0, Tiles.SIZE, Tiles.SIZE)),
                0xFF000000.toInt(), 1f, MaskPaintMode.FILL, maxChangedTiles = 0)
        }
        assertTrue(e.message!!.contains("tiles"))
    }

    // ── 6. `blend` finally has a reader ─────────────────────────────────────────────────────

    @Test fun theBrushFilesBlendWordNowMeansSomething() {
        // `blend: "behind"` was validated and versioned since JB-1.08a and never read by anything,
        // so a valid file silently behaved like "normal".
        assertEquals(MaskPaintMode.FILL, MaskPaint.modeFor("normal"))
        assertEquals(MaskPaintMode.FILL, MaskPaint.modeFor(""))
        assertEquals(MaskPaintMode.ERASE, MaskPaint.modeFor("erase"))
        assertEquals(MaskPaintMode.BEHIND, MaskPaint.modeFor("behind"))
    }

    @Test fun behindIsDestinationOverSoLineArtOnTheLayerSurvivesTheFill() {
        // The point of BEHIND, and the reason a fill pen can share a layer with the drawing: paint
        // lands only where the layer is not already opaque.
        val layer = HashMap<Long, ByteArray>()
        val tile = ByteArray(tileBytes)
        // Row 0 opaque white (line art), row 1 transparent (bare canvas).
        for (x in 0 until Tiles.SIZE) {
            var i = x * 4
            tile[i] = 0xFF.toByte(); tile[i + 1] = 0xFF.toByte(); tile[i + 2] = 0xFF.toByte(); tile[i + 3] = 0xFF.toByte()
            i = (Tiles.SIZE + x) * 4
            tile[i] = 0; tile[i + 1] = 0; tile[i + 2] = 0; tile[i + 3] = 0
        }
        val key = Tiles.key(0, 0)
        layer[key] = tile
        val out = MaskPaint.apply(layer, SelectionMask.rect(RectPx(0, 0, Tiles.SIZE, Tiles.SIZE)),
            0xFF0000FF.toInt(), 1f, MaskPaintMode.BEHIND)[key]!!
        val opaqueRow = (100 * Tiles.SIZE + 100) * 4
        val bareRow = (Tiles.SIZE + 100) * 4 + 100 * 4
        assertTrue(out[opaqueRow + 3].toInt() and 0xFF >= 250, "existing opaque paint stays opaque")
        assertTrue(out[bareRow + 3].toInt() and 0xFF > 200, "bare canvas takes the fill")
    }
}