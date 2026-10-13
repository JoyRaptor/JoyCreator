package cc.joycreator.joybrush.android.select

import cc.joycreator.joybrush.androidkit.select.*
import cc.joycreator.joybrush.core.select.*
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectToolFillTest {
    private class Pixels : LiftedPixels {
        var fills = mutableListOf<Int>()
        var releases = 0
        var commits = 0
        var changed = true
        override fun setTransform(h: Homography) {}
        override fun commit(): Boolean { commits++; return true }
        override fun clear() = false
        override fun fill(argb: Int): Boolean { fills += argb; return changed }
        override fun release() { releases++ }
    }
    private class Host(val pixels: Pixels?) : SelectionHost {
        override var paintClip: SelectionMask? = null
        var redraws = 0
        override fun activeLines() = emptyList<SelectionHost.Line>()
        override fun visibleLines() = activeLines()
        override fun liftLines(ids: Set<LineAddress>): LiftedLines? = null
        override fun replaceLines(after: List<AddressedStroke>) = false
        override fun addLines(records: List<AddressedStroke>) = false
        override fun deleteLines(ids: Set<LineAddress>) = false
        override fun bakeLines(ids: Set<LineAddress>) = false
        override fun liftPixels(mask: SelectionMask, keepSource: Boolean): LiftedPixels? = pixels
        override fun dimRest(alpha: Float) {}
        override fun adjustColour(hueDeg: Float, saturation: Float, brightness: Float, done: Boolean) {}
        override fun penButtonTap(screenX: Float, screenY: Float) {}
        override fun toast(text: String, action: String?, onAction: (() -> Unit)?) {}
        override fun invalidate() { redraws++ }
    }
    private fun selectPaint(tool: SelectTool) {
        tool.state.loop(listOf(Pt(0.0, 0.0), Pt(40.0, 0.0), Pt(40.0, 40.0), Pt(0.0, 40.0)),
            emptyList(), emptyList(), SelectionState.Reading(), adding = false)
        tool.reread(SelectionState.Reading(take = SelectionState.Take.PIXELS))
    }

    @Test fun fillEditsLiftOnceWithoutCommittingAgainOrDroppingTheClip() {
        val pixels = Pixels()
        val host = Host(pixels)
        val tool = SelectTool(host, 1f) { true }
        assertFalse(tool.fill(0xFF123456.toInt()))
        assertFalse(tool.canFill)
        selectPaint(tool)
        val selection = tool.selection
        val clip = host.paintClip
        assertNotNull(clip)
        assertTrue(tool.canFill)
        assertTrue(tool.fill(0xFF123456.toInt()))
        assertEquals(listOf(0xFF123456.toInt()), pixels.fills)
        assertEquals(0, pixels.commits)
        assertEquals(0, pixels.releases)
        assertSame(selection, tool.selection)
        assertSame(clip, host.paintClip)
        pixels.changed = false
        assertFalse(tool.fill(0xFF123456.toInt()))
        tool.end()
        assertEquals(1, pixels.releases)
        assertNull(host.paintClip)
    }

    @Test fun refusedLiftCannotFillOrLoseTheSelection() {
        val tool = SelectTool(Host(null), 1f) { true }
        selectPaint(tool)
        assertFalse(tool.canFill)
        assertFalse(tool.fill(0))
        assertTrue(tool.selection is SelectionState.Pixels)
    }
}
