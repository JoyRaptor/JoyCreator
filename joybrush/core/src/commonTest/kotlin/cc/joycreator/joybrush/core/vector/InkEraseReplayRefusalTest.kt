package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.*

class InkEraseReplayRefusalTest {
    private val stamp = BrushPreset(id = "stamp", name = "Stamp", size = Param(6f), spacing = .04f)
    private val watercolor = BrushPreset(id = "wc", name = "Watercolor", engine = ENGINE_WATERCOLOUR,
        size = Param(1f), spacing = .5f, flow = Param(.89f), opacity = Param(.8f),
        watercolour = WatercolourSpec(size = 1.0, minimum = 1.0, taper = 0.0, spacing = .5,
            smoothPath = 0.0, smoothPressure = 0.0, smoothTilt = 0.0, scatter = 0.0))
    private val path = EraserPath(doubleArrayOf(140.0), doubleArrayOf(0.0), 3.0)
    private fun capped() = StrokeRecord("wash", watercolor.id, 78234L, 0f, 1f,
        listOf(PenSample(0f, 0f, 0.0, pressure = 1f), PenSample(3000f, 0f, 1.0, pressure = 1f)), seq = 4L)
    private fun line() = StrokeRecord("line", stamp.id, 7L, 0f, 1f,
        (0..100).map { PenSample(it * 2f, 0f, it * 10.0, pressure = 1f) }, seq = 5L)
    private fun lookup(record: StrokeRecord) = when (record.brushId) {
        stamp.id -> stamp
        watercolor.id -> watercolor
        else -> null
    }

    @Test fun aCappedWatercolorRecordIsReportedAndRetainedWhileTheStampLineIsCut() {
        val wash = capped()
        val eligible = line()
        // Cheap compatibility checks pass; the refusal arises only while replaying geometry.
        assertNull(InkReplay.refusal(wash, watercolor))
        val reason = assertFailsWith<InkReplayException> { InkReplay.dabs(wash, watercolor) }.message
        val result = assertIs<InkEraseOutcome.Erased>(
            InkErase.run("cel", listOf(wash, eligible), path, InkEraseMode.PARTIAL, ::lookup))
        assertEquals(listOf("wash", "line", "line~2"), result.records.map { it.id })
        assertTrue(result.records[0] === wash, "the refused record is kept by identity, with no replacement")
        assertEquals(listOf(wash), result.records.filter { it.brushId == watercolor.id })
        assertTrue(result.records.none { it.id.startsWith("wash~") })
        assertEquals(mapOf(eligible.id to eligible), result.before, "only the one eligible cut costs undo")
        val pieces = result.records.filter { it.brushId == stamp.id }
        assertEquals(2, pieces.size)
        assertTrue(pieces[0].samples.last().x < pieces[1].samples.first().x, "a real gap was cut")
        assertEquals(0f, pieces[0].samples.first().x)
        assertEquals(200f, pieces[1].samples.last().x)
        assertEquals(listOf(NotReplayed(wash.id, wash.brushId, assertNotNull(reason))), result.notReplayed)
        assertTrue(result.notReplayed.single().reason.contains("6000"))
        assertTrue(result.notReplayed.single().reason.contains("cannot replay"))
    }

    @Test fun anAllRefusedCelKeepsTheExistingUntouchedOutcome() {
        val wash = capped()
        val missing = line().copy(id = "missing", brushId = "absent")
        val original = listOf(wash, missing)
        val result = InkErase.run("cel", original, path, InkEraseMode.PARTIAL, ::lookup)
        assertEquals(InkEraseOutcome.Untouched, result)
        // Untouched tells the caller to retain its original cel rather than publish any survivors.
        val retained = when (result) {
            InkEraseOutcome.Untouched -> original
            is InkEraseOutcome.Erased -> result.records
        }
        assertTrue(retained === original)
        assertTrue(retained[0] === wash)
        assertTrue(retained[1] === missing)
    }

    @Test fun unrelatedDabberFailuresStillPropagate() {
        val broken = watercolor.copy(watercolour = watercolor.watercolour!!.copy(window = 0))
        assertFailsWith<IllegalArgumentException> {
            InkErase.run("cel", listOf(capped()), path, InkEraseMode.PARTIAL) { broken }
        }
    }
}
