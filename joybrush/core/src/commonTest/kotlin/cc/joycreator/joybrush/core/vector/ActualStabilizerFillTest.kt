package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.input.*
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.StrokeEdit
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.svg.LineSvg
import cc.joycreator.joybrush.core.vector.svg.SvgWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DRAFT — not compiled/run. Actual trajectory survives rebrush-to-fill, SVG and CPU tiles. */
class ActualStabilizerFillTest {
    private val fill = BrushPreset(id = "fill", name = "Fill", size = Param(1f), engine = ENGINE_FILL)
    private val raw = listOf(
        20f to 20f, 20f to 20f, 20f to 20f,
        180f to 28f, 180f to 28f, 180f to 28f,
        168f to 180f, 28f to 168f, 20f to 20f,
    ).mapIndexed { i, (x, y) -> PenSample(x, y, i * 16.0, .1f + i * .08f) }
    private fun points(samples: List<PenSample>): List<Pt> {
        val out = ArrayList<Pt>()
        for (sample in samples) {
            val p = Pt(sample.x.toDouble(), sample.y.toDouble())
            if (out.lastOrNull() != p) out += p
        }
        return if (out.size >= 3) out else emptyList()
    }
    private fun rawUnique(samples: List<PenSample>): List<PenSample> {
        val out = ArrayList<PenSample>()
        for (s in samples) if (out.isEmpty() || out.last().x != s.x || out.last().y != s.y) out += s
        return out
    }
    private fun actualOutline(actual: StabilizerSpec, samples: List<PenSample> = raw, zoom: Float = 2f): List<Pt> {
        val filter = StrokeStabilizer(actual, .73f, zoom)
        val released = ArrayList<PenSample>()
        for (sample in samples) filter.addTo(sample) { released += it }
        filter.finishTo { released += it }
        return points(released)
    }
    private fun stroke(actual: StabilizerSpec?) = StrokeRecord("contact", "stamp", 0x1234, .73f, 2f,
        raw, colorArgb = 0xFF527EAD.toInt(), seq = 7, stabilizer = actual)

    @Test fun actualOutlineUsesEveryRawDwellSampleAndKeepsTheLegacyNullBranchExact() {
        val old = points(StrokeSmoother.smoothAll(rawUnique(raw), .73f, 2f))
        assertEquals(old, FillPen.outline(raw, .73f, 2f))
        assertEquals(old, FillPen.outline(raw, .73f, 2f, null))
        for (mode in StabilizerMode.entries) {
            val actual = StabilizerSpec(mode, .85f, 25f, 4)
            assertEquals(actualOutline(actual), FillPen.outline(raw, .73f, 2f, actual), mode.name)
        }
        val moving = StabilizerSpec(StabilizerMode.movingAverage, 1f, 25f, 4)
        assertNotEquals(actualOutline(moving, rawUnique(raw)), actualOutline(moving),
            "Pre-dedup would change actual moving-average windows")
    }

    @Test fun rebrushToFillPreservesActualTrajectoryAcrossSvgAndCpuRaster() {
        val actual = StabilizerSpec(StabilizerMode.movingAverage, 1f, 25f, 4)
        val record = StrokeEdit.rebrush(stroke(actual), fill.id)
        assertEquals(actual, record.stabilizer)
        assertEquals(raw, record.samples)
        val expectedOutline = actualOutline(actual)
        val expectedPixels = assertNotNull(InkRaster.fill(expectedOutline, record.colorArgb, 0, 0, 256, 256, 1f))
        val tile = InkTiles.render(listOf(record), 0, 0) { fill }
        assertTrue(tile.refusals.isEmpty())
        assertContentEquals(expectedPixels, assertNotNull(tile.pixels))
        val expectedSvg = SvgWriter.write(256.0, 256.0, listOf(SvgWriter.Fill(record.seq, record.id,
            expectedOutline, record.colorArgb, 1f))).svg
        val svg = LineSvg.export(256.0, 256.0, listOf(record), { fill })
        assertTrue(svg.skipped.isEmpty())
        assertTrue(svg.approximated.isEmpty())
        assertEquals(expectedSvg, svg.svg)

        val wrong = record.copy(stabilizer = null)
        val wrongTile = assertNotNull(InkTiles.render(listOf(wrong), 0, 0) { fill }.pixels)
        assertTrue(!expectedPixels.contentEquals(wrongTile), "Legacy smooth substitution changes real pixels")
        assertNotEquals(expectedSvg, LineSvg.export(256.0, 256.0, listOf(wrong), { fill }).svg)
    }

    @Test fun brushSuggestionDoesNotChangeSavedFillActualModeAndDegenerateContractsRemain() {
        val actual = StabilizerSpec(StabilizerMode.pulledString, .8f, 25f, 4)
        val record = StrokeEdit.rebrush(stroke(actual), fill.id)
        val defaultSvg = LineSvg.export(256.0, 256.0, listOf(record), { fill }).svg
        val changedSuggestion = fill.copy(stabilizer = StabilizerSpec(StabilizerMode.off), smoothing = 0f)
        assertEquals(defaultSvg, LineSvg.export(256.0, 256.0, listOf(record), { changedSuggestion }).svg)
        for (mode in StabilizerMode.entries) {
            val spec = actual.copy(mode = mode)
            assertTrue(FillPen.outline(raw.take(1), .73f, 2f, spec).isEmpty())
            assertTrue(FillPen.outline(listOf(raw[0], raw[3]), .73f, 2f, spec).isEmpty())
            assertTrue(FillPen.outline(List(10) { raw[0] }, .73f, 2f, spec).isEmpty())
        }
        assertFailsWith<IllegalArgumentException> {
            FillPen.outline(emptyList(), .73f, 2f, actual.copy(strength = Float.NaN))
        }
    }

    @Test fun watercolorCannotSilentlyIgnoreAnyRecordedActualMode() {
        val watercolor = BrushPreset(id = "wc", name = "Water", size = Param(20f), engine = ENGINE_WATERCOLOUR,
            watercolour = WatercolourSpec())
        val legacy = StrokeEdit.rebrush(stroke(null), watercolor.id)
        assertNull(InkReplay.refusal(legacy, watercolor), "Existing Helper-owned raw path retains null-mode behavior")
        for (mode in StabilizerMode.entries) {
            val recorded = legacy.copy(stabilizer = StabilizerSpec(mode))
            val reason = assertNotNull(InkReplay.refusal(recorded, watercolor))
            assertTrue(reason.contains("recorded stabilizer"))
            assertEquals(reason, assertFailsWith<InkReplayException> { InkReplay.replay(recorded, watercolor) }.message)
            assertEquals(reason, assertFailsWith<InkReplayException> {
                InkReplay.replayAdmitted(recorded, watercolor) { }
            }.message)
            assertEquals(reason, LineSvg.export(256.0, 256.0, listOf(recorded), { watercolor }).skipped.single().reason)
        }
    }
}
