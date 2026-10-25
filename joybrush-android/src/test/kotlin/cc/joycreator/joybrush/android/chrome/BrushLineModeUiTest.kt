package cc.joycreator.joybrush.android.chrome

import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import cc.joycreator.joybrush.android.select.BrushModeBadge
import cc.joycreator.joybrush.android.select.testBrush
import cc.joycreator.joybrush.core.chrome.BrushShelf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
class BrushLineModeUiTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun settings(editableLines: Boolean?, changed: (Boolean) -> Unit = {}): BrushSettingsView {
        val context = RuntimeEnvironment.getApplication()
        return BrushSettingsView(
            kit = ChromeKit(context),
            base = testBrush("eligible-ink", name = "Eligible ink"),
            start = emptyMap(),
            preview = View(context),
            onChange = { _, _ -> },
            onReset = {},
            onTest = {},
            onDone = {},
            editableLines = editableLines,
            onEditableLinesChanged = changed,
        )
    }

    @Test fun eligibleSheetSwitchReflectsInitialModeWithoutWritingThenReportsToggle() {
        for (initial in listOf(true, false)) {
            val changes = ArrayList<Boolean>()
            val view = settings(initial, changes::add)
            val switch = descendants(view).filterIsInstance<Switch>().single()
            assertEquals(initial, switch.isChecked)
            assertEquals("Editable lines", switch.contentDescription)
            assertTrue("constructing the view must not write the initial preference", changes.isEmpty())

            switch.performClick()

            assertEquals(listOf(!initial), changes)
            assertEquals(!initial, switch.isChecked)
        }
    }

    @Test fun pixelsOnlySheetShowsExactReasonAndNoSwitch() {
        val view = settings(editableLines = null)
        assertTrue(descendants(view).filterIsInstance<Switch>().isEmpty())
        assertEquals(1, descendants(view).filterIsInstance<TextView>()
            .count { it.text.toString() == BrushModeBadge.PIXELS_LABEL })
    }

    @Test fun drawerQueriesEachBrushModeAndShowsTwelveDpBadgesWithMatchingLabels() {
        val context = RuntimeEnvironment.getApplication()
        val kit = ChromeKit(context)
        val lineBrush = testBrush("line-brush", name = "Line brush")
        val pixelBrush = testBrush("pixel-brush", name = "Pixel brush")
        val modes = mapOf(lineBrush.id to true, pixelBrush.id to false)
        val queried = ArrayList<String>()
        val drawer = BrushDrawerView(
            kit = kit,
            library = listOf(lineBrush, pixelBrush),
            startKind = BrushShelf.Kind.ALL,
            currentId = null,
            onPick = {},
            editableLines = { brush -> queried += brush.id; modes.getValue(brush.id) },
        )

        val badges = descendants(drawer).filterIsInstance<BrushModeBadge>()
        assertEquals(setOf(lineBrush.id, pixelBrush.id), queried.toSet())
        assertEquals(2, badges.size)
        val byMode = badges.associateBy { it.editableLines }
        assertEquals("Makes editable lines", byMode.getValue(true).contentDescription)
        assertEquals(BrushModeBadge.PIXELS_LABEL, byMode.getValue(false).contentDescription)
        for (badge in badges) {
            val params = badge.layoutParams
            assertEquals(kit.dpi(12f), params.width)
            assertEquals(kit.dpi(12f), params.height)
        }
    }
}
