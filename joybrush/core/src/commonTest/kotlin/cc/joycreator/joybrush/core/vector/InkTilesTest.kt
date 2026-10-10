package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.math.abs
import kotlin.test.*

class InkTilesTest {
    private val pen = BrushPreset(id = "pen", name = "Pen", size = Param(13.3f),
        tip = TipSpec(hardness = Param(0.63f)), spacing = 0.08f)
    private val fill = pen.copy(id = "fill", engine = ENGINE_FILL)
    private fun line(id: String = "line", brushId: String = "pen", color: Int = 0xff527ead.toInt()) =
        StrokeRecord(id, brushId, 73L, 0f, 1f, (0..16).map {
            PenSample(-24.12345f + it * 19.76543f, -13.4321f + it * 18.54321f, it * 12.0)
        }, color)
    private fun shape(color: Int = 0x805527aa.toInt()) = StrokeRecord("shape", "fill", 1L, 0f, 1f,
        listOf(PenSample(-20.25f, -10.5f, 0.0), PenSample(310.5f, 22.75f, 1.0),
            PenSample(274.25f, 320.25f, 2.0), PenSample(-33.5f, 282.5f, 3.0)), color)
    private fun crop(big: ByteArray, tx: Int, ty: Int): ByteArray {
        val out = ByteArray(256 * 256 * 4)
        for (y in 0..255) {
            val src = (((ty + 1) * 256 + y) * 768 + (tx + 1) * 256) * 4
            big.copyInto(out, y * 256 * 4, src, src + 256 * 4)
        }
        return out
    }
    @Test fun fractionalStampCrossesPositiveAndNegativeSeamsExactly() {
        val record = line()
        val big = assertNotNull(InkRaster.stamps(InkReplay.dabs(record, pen),
            TipShape(hardness = pen.tip.hardness.base), Accumulate.WASH, 1f, record.colorArgb,
            -256, -256, 768, 768, 1f))
        for (ty in -1..1) for (tx in -1..1) {
            val tile = InkTiles.render(listOf(record), tx, ty) { pen }
            assertTrue(tile.refusals.isEmpty())
            assertContentEquals(crop(big, tx, ty), tile.pixels ?: ByteArray(256 * 256 * 4), "tile $tx,$ty")
        }
    }
    @Test fun filledShapeCrossesPositiveAndNegativeSeamsExactly() {
        val r = shape()
        val big = assertNotNull(InkRaster.fill(FillPen.outline(r.samples, r.smoothing, r.screenPerDoc),
            r.colorArgb, -256, -256, 768, 768, 1f))
        for (ty in -1..1) for (tx in -1..1) assertContentEquals(crop(big, tx, ty),
            InkTiles.render(listOf(r), tx, ty) { fill }.pixels ?: ByteArray(256 * 256 * 4))
    }
    @Test fun listOrderAndDuplicateIdsGivePremultipliedSourceOver() {
        val red = shape(0x80ff0000.toInt())
        val blue = shape(0x800000ff.toInt()).copy(id = red.id)
        val forward = assertNotNull(InkTiles.render(listOf(red, blue), 0, 0) { fill }.pixels)
        val reverse = assertNotNull(InkTiles.render(listOf(blue, red), 0, 0) { fill }.pixels)
        val i = (100 * 256 + 100) * 4
        assertEquals(listOf(64, 0, 128, 192), (0..3).map { forward[i + it].toInt() and 255 })
        assertEquals(listOf(128, 0, 64, 192), (0..3).map { reverse[i + it].toInt() and 255 })
    }
    @Test fun missingAndUnreplayableRecordsAreSkippedAndReportedByPosition() {
        val records = listOf(line(brushId = "missing"), line(brushId = "wet"), line())
        val result = InkTiles.render(records, 0, 0) { id -> when (id) {
            "pen" -> pen; "wet" -> pen.copy(engine = "wet"); else -> null
        } }
        assertNotNull(result.pixels)
        assertEquals(listOf(0, 1), result.refusals.map { it.index })
        assertEquals(InkReplay.refusal(records[0], null), result.refusals[0].reason)
        assertEquals(InkReplay.refusal(records[1], pen.copy(engine = "wet")), result.refusals[1].reason)
    }
    @Test fun emptyOutsideTransparentAndRefusedOnlyTilesAreNull() {
        assertNull(InkTiles.render(emptyList(), 0, 0) { pen }.pixels)
        assertNull(InkTiles.render(listOf(line()), 50, -80) { pen }.pixels)
        assertNull(InkTiles.render(listOf(shape(0)), 0, 0) { fill }.pixels)
        val refused = InkTiles.render(listOf(line()), 0, 0) { null }
        assertNull(refused.pixels)
        assertEquals(1, refused.refusals.size)
    }
    @Test fun prepareFreezesLookupAndReportsOnlyOnceAcrossTiles() {
        var lookups = 0
        val prepared = InkTiles.prepare(listOf(line(), line(brushId = "missing"))) { id ->
            lookups++; if (id == "pen") pen else null
        }
        val a = InkTiles.render(prepared, 0, 0)
        val b = InkTiles.render(prepared, 1, 1)
        assertEquals(2, lookups)
        assertEquals(a.refusals, b.refusals)
        assertContentEquals(InkTiles.render(listOf(line()), 0, 0) { pen }.pixels, a.pixels)
    }
    @Test fun behindEraseAndMultiplyHaveExplicitPixelMeaning() {
        val red = shape(0xffff0000.toInt())
        val blue = shape(0x800000ff.toInt()).copy(brushId = "special")
        fun render(blend: String) = assertNotNull(InkTiles.render(listOf(red, blue), 0, 0) {
            if (it == "special") fill.copy(blend = blend) else fill
        }.pixels).let { bytes -> (0..3).map { bytes[(100 * 256 + 100) * 4 + it].toInt() and 255 } }
        assertEquals(listOf(255, 0, 0, 255), render("behind"))
        assertEquals(listOf(127, 0, 0, 127), render("erase"))
        assertEquals(listOf(127, 0, 0, 255), render("multiply"))
    }
    @Test fun unsupportedBlendAndImageAndGrainAreRefusedInsteadOfInvented() {
        for (brush in listOf(pen.copy(blend = "invented"), pen.copy(tip = pen.tip.copy(source = "image")),
            pen.copy(tipTexture = GrainSpec(enabled = true)))) {
            val result = InkTiles.render(listOf(line()), 0, 0) { brush }
            assertNull(result.pixels)
            assertEquals(1, result.refusals.size)
        }
    }
    /**
     * The stroke's two whole-stroke numbers are the ones the live path EVALUATED AT THE FIRST DAB, not the file's
     * bases: `JbCanvasView.beginStrokeNow` builds its TipShape with `dabber.strokeHardness` and passes
     * `dabber.strokeOpacity`, and `BrushDabber` fixes both from the first dab's inputs. A pre-contact preset
     * (version < VERSION_CONTACT) carries no per-dab hardness, so the stroke's hardness is the only one it has.
     * Non-vacuity: pressure 0.3 on a pressure curve moves both numbers well away from their bases, and the tile
     * drawn from the bases is a different picture.
     */
    @Test fun hardnessAndBuildUpOpacityAreTheFirstDabsAsLive() {
        val pressured = listOf(InputCurve(BrushInput.pressure, listOf(listOf(0f, 0f), listOf(1f, 1f))))
        val brush = pen.copy(version = VERSION_CONTACT - 1, accumulate = "buildup",
            tip = pen.tip.copy(hardness = Param(0.9f, pressured)), opacity = Param(1f, pressured))
        val record = line().let { r -> r.copy(samples = r.samples.map { it.copy(pressure = 0.3f) }) }
        val live = liveDabber(record, brush)
        assertTrue(abs(live.strokeHardness - brush.tip.hardness.base) > 0.3f, "hardness ${live.strokeHardness}")
        assertTrue(abs(live.strokeOpacity - brush.opacity.base) > 0.3f, "opacity ${live.strokeOpacity}")
        fun expected(hardness: Float, opacity: Float) = InkRaster.stamps(InkReplay.dabs(record, brush),
            TipShape(brush.tip.aspect, brush.tip.corner, brush.tip.taper, hardness, brush.tip.minPx),
            Accumulate.BUILD_UP, opacity, record.colorArgb, 0, 0, 256, 256, 1f)
        val tile = InkTiles.render(listOf(record), 0, 0) { brush }.pixels
        assertFalse(expected(brush.tip.hardness.base, brush.opacity.base).contentEquals(
            expected(live.strokeHardness, live.strokeOpacity)), "NON-VACUITY: the bases draw a different tile")
        assertContentEquals(expected(live.strokeHardness, live.strokeOpacity), tile, "the first dab's numbers, as live")
    }
    /** `JbCanvasView.startStroke` + `feed`: the dabber the live path asks for the stroke's whole-stroke numbers. */
    private fun liveDabber(record: StrokeRecord, brush: BrushPreset): BrushDabber {
        val smoother = StrokeSmoother(record.smoothing, record.screenPerDoc)
        val points = ArrayList<PenSample>()
        for (s in record.samples) points.addAll(smoother.add(brush.response.apply(s)))
        points.addAll(smoother.finish())
        return BrushDabber(brush, record.seed).also { DabPlacer(spacing = it.spacing, look = it::look).add(points) }
    }
    /**
     * Paper grain is NOT drawn on the CPU (no CPU twin of the live stamp-with-paper shader exists: GrainMath has the
     * formulas, but the paper texture, the document surface and the per-batch uniforms never reach core), so a
     * pencil-like line is drawn WITHOUT it and says so in [InkTiles.Result.omissions] — not refused, because refusing
     * would drop every pencil line from an export, and not silently, because a smooth line where the screen showed a
     * grained one is a difference somebody must be able to see. A brush with no paper grain reports nothing.
     */
    @Test fun paperGrainIsReportedAsNotDrawnRatherThanSilentlyDropped() {
        val grained = pen.copy(paperGrain = GrainSpec(enabled = true))
        val records = listOf(line(id = "plain"), line(id = "pencil", brushId = "grained"))
        val result = InkTiles.render(records, 0, 0) { if (it == "grained") grained else pen }
        assertNotNull(result.pixels)
        assertTrue(result.refusals.isEmpty(), "drawn, not refused: ${result.refusals}")
        assertEquals(listOf(1 to "pencil"), result.omissions.map { it.index to it.strokeId })
        assertTrue("paper grain" in result.omissions.single().what, result.omissions.single().what)
        assertEquals(InkTiles.prepare(records) { if (it == "grained") grained else pen }.omissions, result.omissions)
        assertTrue(InkTiles.render(listOf(line()), 0, 0) { pen }.omissions.isEmpty(), "no grain, nothing to report")
        assertTrue(InkTiles.render(listOf(shape()), 0, 0) { fill.copy(paperGrain = GrainSpec(enabled = true)) }
            .omissions.isEmpty(), "a fill is a polygon live too, so it owes no grain")
    }
    @Test fun tileCoordinatesCannotWrap() {
        assertFailsWith<IllegalArgumentException> { InkTiles.render(emptyList(), Int.MAX_VALUE, 0) { pen } }
    }
}
