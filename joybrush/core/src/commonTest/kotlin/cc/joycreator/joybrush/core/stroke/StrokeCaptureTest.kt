package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import kotlin.test.*

class StrokeCaptureTest {
    private fun capture() = StrokeCapture("stroke", "frozen", 9182L, .7f, 2f, 0xFF123456.toInt())

    @Test fun guidePlacedSamplesStayRawAndRetainEveryPenAttribute() {
        val raw = (0..8).map { PenSample(it * 4f, it * 3f, it * 10.0, pressure = .2f + it * .05f,
            tilt = .5f, azimuth = .8f).copy(y = 20f) }
        val capture = capture()
        val smoother = StrokeSmoother(.7f, 2f)
        val smoothed = raw.flatMap { smoother.add(it) } + smoother.finish()
        raw.forEach(capture::add)
        val record = assertNotNull(capture.finish())
        assertEquals(raw, record.samples)
        assertNotEquals(smoothed, record.samples)
        assertEquals(9182L, record.seed)
        assertEquals(.7f, record.smoothing)
        assertEquals(2f, record.screenPerDoc)
        assertEquals(0xFF123456.toInt(), record.colorArgb)
        assertNull(record.secondaryColorArgb, "legacy capture callers retain the absent-palette sentinel")
        assertEquals("frozen", record.brushId)
        assertEquals(0L, record.seq)
    }

    @Test fun predictedSamplesRefuseWithoutContaminatingTheRecording() {
        val capture = capture()
        val sample = PenSample(1f, 2f, 0.0)
        capture.add(sample)
        assertFailsWith<IllegalArgumentException> { capture.add(sample.copy(predicted = true)) }
        assertEquals(listOf(sample), assertNotNull(capture.finish()).samples)
        assertFailsWith<IllegalStateException> { capture.add(sample) }
        assertFailsWith<IllegalStateException> { capture.finish() }
    }

    @Test fun secondaryPaletteIsFrozenWithPrimaryAndSurvivesRecolorAndCodec() {
        val primary = 0x80123456.toInt()
        val secondary = 0x10293847
        val capture = StrokeCapture("stroke", "frozen", 9182L, .7f, 2f, primary, secondary)
        val raw = PenSample(-0f, 2f, 4.5, pressure = .6f, tilt = .5f, azimuth = -.7f, barrel = .8f)
        capture.add(raw)
        val record = assertNotNull(capture.finish())
        assertEquals(primary, record.colorArgb)
        assertEquals(secondary, record.secondaryColorArgb)
        assertEquals(listOf(raw), record.samples)
        val edited = StrokeEdit.recolor(record, 0xFFABCDEF.toInt())
        assertEquals(record.copy(colorArgb = 0xFFABCDEF.toInt()), edited)
        assertEquals(secondary, edited.secondaryColorArgb)
        assertEquals(edited, StrokeCodec.decode(StrokeCodec.encode(edited)))
    }

    @Test fun canceledAndEmptyContactsCannotCreateRecords() {
        assertNull(capture().finish())
        val canceled = capture()
        canceled.add(PenSample(1f, 2f, 0.0))
        canceled.cancel()
        assertFailsWith<IllegalStateException> { canceled.finish() }
        assertFailsWith<IllegalStateException> { canceled.add(PenSample(1f, 2f, 0.0)) }
    }
}
