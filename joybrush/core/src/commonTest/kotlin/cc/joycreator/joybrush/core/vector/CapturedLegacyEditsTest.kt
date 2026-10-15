package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.select.LineDensify
import cc.joycreator.joybrush.core.stroke.CaptureDomain
import cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource
import cc.joycreator.joybrush.core.stroke.StrokeEdit
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CapturedLegacyEditsTest {
    private val source = CapturedPhysicalSource(CaptureDomain())

    private fun record(captured: Boolean) = StrokeRecord(
        id = "line", brushId = "ink", seed = 7L, smoothing = 0f, screenPerDoc = 1f,
        samples = emptyList(), capturedPhysicalSource = if (captured) source else null,
    )

    @Test
    fun sampleAndRecipeEditsRefuseCapturedRecordsBeforeNoOpFastPaths() {
        val captured = record(captured = true)
        assertFailsWith<IllegalArgumentException> { StrokeEdit.reshape(captured, 0f, 0f, Float.NaN, 0f, 1f) }
        assertFailsWith<IllegalArgumentException> { StrokeEdit.reweight(captured, Float.NaN) }
        assertFailsWith<IllegalArgumentException> { StrokeEdit.rebrush(captured, "new-brush") }
        assertFailsWith<IllegalArgumentException> { StrokeEdit.recolor(captured, 0xFF00FF00.toInt()) }
        assertFailsWith<IllegalArgumentException> { LineDensify.densify(captured, 0f) }
    }

    @Test
    fun rawEraseRefusesCapturedRecordBeforeEmptyPathShortcut() {
        val captured = record(captured = true)
        assertFailsWith<IllegalArgumentException> {
            InkErase.run("cel", listOf(captured), EraserPath(doubleArrayOf(), doubleArrayOf(), 0.0), InkEraseMode.PARTIAL) { null }
        }
    }

    @Test
    fun ordinaryRecordsKeepExistingEmptyAndInvalidInputBehavior() {
        val ordinary = record(captured = false)
        assertSame(ordinary, StrokeEdit.reshape(ordinary, 0f, 0f, 1f, 1f, 2f))
        assertSame(ordinary, StrokeEdit.reweight(ordinary, Float.NaN))
        assertEquals("new-brush", StrokeEdit.rebrush(ordinary, "new-brush").brushId)
        assertEquals(0xFF00FF00.toInt(), StrokeEdit.recolor(ordinary, 0xFF00FF00.toInt()).colorArgb)
        assertSame(ordinary, LineDensify.densify(ordinary, 0f))
        assertEquals(
            InkEraseOutcome.Untouched,
            InkErase.run("cel", listOf(ordinary), EraserPath(doubleArrayOf(), doubleArrayOf(), 0.0), InkEraseMode.PARTIAL) { null },
        )
    }
}
