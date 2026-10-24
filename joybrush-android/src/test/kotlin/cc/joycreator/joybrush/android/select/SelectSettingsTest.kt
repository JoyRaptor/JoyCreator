package cc.joycreator.joybrush.android.select

import android.content.Context
import android.view.View
import android.view.ViewGroup
import cc.joycreator.joybrush.android.chrome.ChromeKit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectSettingsTest {
    private fun descendants(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    @Test fun defaultsPanAndPixelsAndChangesReachTheirOwnSetting() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("joybrush_select", Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = SelectPreferences(context)
        assertFalse(preferences.fingerSelects)
        assertFalse(preferences.pickLocked)
        assertTrue(preferences.dimRest)
        var shelfLines = false
        var shelfWrites = 0
        val menu = SelectSettingsView(ChromeKit(context), preferences.fingerSelects, shelfLines,
            { preferences.fingerSelects = it }, { shelfLines = it; shelfWrites++ })
        val choices = descendants(menu).filter { it.isClickable && it.contentDescription in setOf("Pans", "Selects", "Pixels", "Editable lines") }
        assertEquals(4, choices.size)
        assertTrue(choices.all { !it.contentDescription.isNullOrBlank() })
        assertTrue(descendants(menu).any { it.contentDescription == "Select every editable line on this layer" })
        assertFalse(descendants(menu).any { it.contentDescription == "Paste the copied editable lines onto this layer" })
        fun choice(label: String) = choices.first { it.contentDescription == label }
        assertTrue(choice("Pans").isSelected)
        assertTrue(choice("Pixels").isSelected)
        choice("Selects").performClick()
        assertTrue(SelectPreferences(context).fingerSelects)
        assertEquals(0, shelfWrites)
        assertFalse(choice("Pans").isSelected)
        choice("Editable lines").performClick()
        assertTrue(shelfLines)
        assertTrue(choice("Editable lines").isSelected)
        assertEquals(1, shelfWrites)
        choice("Pixels").performClick()
        assertFalse(shelfLines)
        assertTrue(preferences.fingerSelects)
        choice("Pans").performClick()
        assertFalse(SelectPreferences(context).fingerSelects)
    }
}
