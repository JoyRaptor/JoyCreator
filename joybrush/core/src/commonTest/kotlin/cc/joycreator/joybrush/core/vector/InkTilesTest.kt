package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.stroke.StrokeRecord
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
    @Test fun tileCoordinatesCannotWrap() {
        assertFailsWith<IllegalArgumentException> { InkTiles.render(emptyList(), Int.MAX_VALUE, 0) { pen } }
    }
}
