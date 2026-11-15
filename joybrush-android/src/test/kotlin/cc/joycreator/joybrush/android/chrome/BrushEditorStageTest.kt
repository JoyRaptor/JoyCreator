package cc.joycreator.joybrush.android.chrome

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import cc.joycreator.joybrush.android.select.testBrush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** BRUSH-UI section K: the brush editor's Stage layout: Studio-style top bar with group buttons, Done and cancel; end words. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
class BrushEditorStageTest {
    private fun descendants(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    @Test fun topBarHasGroupsDoneAndCancelAndCancelRestoresTheBrush() {
        val context = RuntimeEnvironment.getApplication()
        val changes = ArrayList<Pair<Map<String, Float>, Boolean>>()
        var closed = 0
        val start = mapOf("stamp.spacing" to .3f)
        val view = BrushSettingsView(ChromeKit(context), testBrush("ink", name = "Ink pen"), start, View(context),
            onChange = { p, done -> changes += p to done }, onReset = {}, onTest = {}, onDone = { closed++ })
        val all = descendants(view)
        val labels = all.mapNotNull { it.contentDescription?.toString() }
        assertTrue(labels.toString(), labels.contains("Go to Shape settings"))
        assertTrue(labels.toString(), labels.contains("Go to Spacing & scatter settings"))
        val cancel = all.first { it.contentDescription?.toString()?.startsWith("Cancel") == true }
        cancel.performClick()
        assertEquals(start to true, changes.last())
        assertEquals(1, closed)
        // Words at the ends of the Spacing slider.
        val words = all.filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(words.toString(), words.containsAll(listOf("Smooth", "Dotted")))
        assertTrue(words.toString(), words.contains("Ink pen"))
    }
}
