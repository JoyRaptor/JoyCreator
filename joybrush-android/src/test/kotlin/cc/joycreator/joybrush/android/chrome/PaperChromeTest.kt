package cc.joycreator.joybrush.android.chrome

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.SeekBar
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.layers.LayerState
import cc.joycreator.joybrush.core.paper.LookEntry
import cc.joycreator.joybrush.core.paper.PaperCatalogue
import cc.joycreator.joybrush.core.paper.SurfaceEntry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PaperChromeTest {
    private val surface = SurfaceEntry("tooth", "Tooth", "tooth.png", 64, 1f, 1f, 16f, true)
    private val catalogue = PaperCatalogue(surfaces = listOf(surface), looks = listOf(
        LookEntry("warm", "Warm", "#D8C8A0", defaultSurface = surface.id),
        LookEntry("black", "Black", "#000000", lightByDefault = false),
    ))
    private fun kit() = ChromeKit(RuntimeEnvironment.getApplication())

    private class SheetHost(start: Paper) : PaperSheetView.Host {
        var latest = start
        val applied = mutableListOf<Paper>()
        var closed = 0
        var pickerTint: Boolean? = null
        var pickerCurrent = 0
        var onPicked: ((Int?) -> Unit)? = null
        override fun apply(paper: Paper) { latest = paper; applied += paper }
        override fun pickColour(tint: Boolean, current: Int, onPicked: (Int?) -> Unit) {
            pickerTint = tint; pickerCurrent = current; this.onPicked = onPicked
        }
        override fun close() { closed++ }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun control(sheet: View, labelPrefix: String): View = descendants(sheet).first {
        it.contentDescription?.toString()?.startsWith(labelPrefix) == true
    }

    @Test fun backgroundUsesItsDefaultSurfaceAndTransparentRetainsPhysicalSettings() {
        val start = Paper(textureId = "other", textureScale = 1.75f, tint = "#AABBCC", bite = 0.37f,
            includeInExport = true, screenTransparent = true)
        val host = SheetHost(start)
        val sheet = PaperSheetView(kit(), start, catalogue, host)
        control(sheet, "Warm:").performClick()
        assertEquals("warm", host.latest.lookId)
        assertEquals("tooth", host.latest.textureId)
        assertNull(host.latest.tint)
        assertFalse(host.latest.screenTransparent)
        assertEquals(0.37f, host.latest.bite, 0f)
        assertEquals(1.75f, host.latest.textureScale, 0f)

        control(sheet, "None:").performClick()
        assertTrue(host.latest.screenTransparent)
        assertFalse(host.latest.includeInExport)
        assertEquals("tooth", host.latest.textureId)
        assertEquals(0.37f, host.latest.bite, 0f)
        val export = control(sheet, "Include the visible") as CheckBox
        assertFalse(export.isEnabled)
        assertFalse(export.isChecked)
        assertTrue(sheet.previewRequests().first { it.key == "none" }.paper.screenTransparent)

        control(sheet, "Smooth:").performClick()
        assertNull(host.latest.textureId)
        assertTrue(host.latest.screenTransparent)
        control(sheet, "Black:").performClick()
        assertTrue(export.isEnabled)
        assertFalse((control(sheet, "Light the paper") as CheckBox).isChecked)
        assertEquals(0, host.closed)
        control(sheet, "Close the Paper").performClick()
        assertEquals(1, host.closed)
    }

    @Test fun customColourAndTintSharePickerAndLongPressRestoresLookColours() {
        val start = Paper(lookId = "warm", textureId = "tooth", bite = 0.45f, tint = "#112233")
        val host = SheetHost(start)
        val sheet = PaperSheetView(kit(), start, catalogue, host)
        control(sheet, "Colour:").performClick()
        assertEquals(false, host.pickerTint)
        host.onPicked!!.invoke(null)
        assertTrue(host.applied.isEmpty())
        host.onPicked!!.invoke(0xFF336699.toInt())
        assertEquals("#336699", host.latest.color)
        assertNull(host.latest.lookId)
        assertNull(host.latest.tint)
        assertEquals("tooth", host.latest.textureId)
        assertEquals(0.45f, host.latest.bite, 0f)

        sheet.refresh(start)
        val tint = control(sheet, "Tint:")
        tint.performClick()
        assertEquals(true, host.pickerTint)
        assertEquals(0xFF112233.toInt(), host.pickerCurrent)
        host.onPicked!!.invoke(0xFF778899.toInt())
        assertEquals("warm", host.latest.lookId)
        assertEquals("#778899", host.latest.tint)
        assertTrue(tint.performLongClick())
        assertNull(host.latest.tint)
        assertEquals("warm", host.latest.lookId)
    }

    @Test fun undoRefreshDoesNotReportEditAndOldPreviewCannotReplaceNewColour() {
        val start = Paper(color = "#123456", textureId = "tooth", textureScale = 4f, show = 0f, bite = 1f)
        val host = SheetHost(start)
        val sheet = PaperSheetView(kit(), start, catalogue, host)
        val old = sheet.previewRequests().first { it.key == "colour" }
        val bitmap = Bitmap.createBitmap(old.size, old.size, Bitmap.Config.ARGB_8888)
        assertTrue(sheet.setPreview(old, bitmap))

        sheet.refresh(start.copy(color = "#ABCDEF", textureScale = 0.25f, show = 1f, bite = 0f))
        assertTrue(host.applied.isEmpty())
        assertEquals(0, host.closed)
        assertEquals(0, (control(sheet, "Scale:") as SeekBar).progress)
        assertEquals(100, (control(sheet, "Show:") as SeekBar).progress)
        assertEquals(0, (control(sheet, "Bite:") as SeekBar).progress)
        assertFalse(sheet.setPreview(old, bitmap))
        val next = sheet.previewRequests().first { it.key == "colour" }
        assertTrue(sheet.setPreview(next, bitmap))
        assertEquals(0.25f, next.paper.scale, 0f)
        assertEquals(0xFFABCDEF.toInt(), next.paper.baseArgb)
    }

    @Test fun everyActionIsLabelledAndScaleRangeIsExactly25Through400() {
        val start = Paper()
        val host = SheetHost(start)
        val sheet = PaperSheetView(kit(), start, catalogue, host)
        val actions = descendants(sheet).filter { it.isClickable || it is SeekBar }
        assertTrue(actions.isNotEmpty())
        for (action in actions) assertFalse("Missing label on ${action.javaClass.simpleName}", action.contentDescription.isNullOrBlank())
        val scale = control(sheet, "Scale:") as SeekBar
        assertEquals(375, scale.max)
        assertEquals(100, (control(sheet, "Show:") as SeekBar).max)
        assertEquals(100, (control(sheet, "Bite:") as SeekBar).max)
        // Drive the platform listener as a user gesture, rather than programmatic progress rebinding.
        val listener = org.robolectric.Shadows.shadowOf(scale).onSeekBarChangeListener
        listener.onProgressChanged(scale, 375, true)
        assertEquals(4f, host.latest.textureScale, 0f)
        listener.onProgressChanged(scale, 0, true)
        assertEquals(0.25f, host.latest.textureScale, 0f)
        assertEquals(2, host.applied.size)
        assertEquals(0, host.closed)
    }

    @Test fun paperTapOpensSheetWithoutChangingPaintTargetAndSurvivesLayerRebuilds() {
        var paperOpens = 0
        var paintTargets = 0
        val host = object : LayerColumnView.Host {
            override fun addLayer() {}
            override fun selectLayer(id: String) { paintTargets++ }
            override fun openLayer(id: String, anchor: View) { paintTargets++ }
            override fun moveLayer(id: String, toIndex: Int) { paintTargets++ }
            override fun maskTapped(id: String) { paintTargets++ }
            override fun openPaper(anchor: View) { paperOpens++ }
        }
        val column = LayerColumnView(kit(), host)
        val stack = LayerStack(listOf(LayerState("bottom", "Bottom"), LayerState("top", "Top")), "top")
        column.show(stack, 16)
        val anchor = column.paperAnchor
        val layerAnchor = column.cellFor("top")
        assertSame(anchor, column.getChildAt(column.childCount - 1))
        assertEquals("Paper — tap to change", anchor.contentDescription)
        anchor.performClick()
        assertEquals(1, paperOpens)
        assertEquals(0, paintTargets)
        column.setPaperPreview(Bitmap.createBitmap(44, 28, Bitmap.Config.ARGB_8888))
        column.show(stack.select("bottom"), 16)
        assertSame(layerAnchor, column.cellFor("top"))
        column.show(stack.add("new"), 16)
        assertSame(anchor, column.paperAnchor)
        assertSame(anchor, column.getChildAt(column.childCount - 1))
        anchor.performClick()
        assertEquals(2, paperOpens)
        assertEquals(0, paintTargets)
    }
}
