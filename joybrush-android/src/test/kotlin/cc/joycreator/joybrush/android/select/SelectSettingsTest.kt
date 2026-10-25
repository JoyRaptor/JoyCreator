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

    @Test fun lockedOptionAppearsAfterALockAndRemainsAvailableAfterTurningItOff() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("joybrush_select", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = SelectPreferences(context)
        fun menu() = SelectSettingsView(ChromeKit(context), false, false, {}, {},
            pickLocked = prefs.pickLocked, onPickLocked = { prefs.pickLocked = it },
            showLockedOption = prefs.lockedOptionRevealed)
        fun lockedChoices(view: View) = descendants(view).filter {
            it.contentDescription?.startsWith("Pick locked lines too:") == true
        }
        assertTrue(lockedChoices(menu()).isEmpty())
        prefs.observeLockedLines(false)
        assertFalse(prefs.lockedOptionRevealed)
        prefs.observeLockedLines(true)
        assertTrue(SelectPreferences(context).lockedOptionRevealed)
        val shown = menu()
        lockedChoices(shown).first { it.contentDescription.toString().endsWith(": on") }.performClick()
        assertTrue(SelectPreferences(context).pickLocked)
        lockedChoices(shown).first { it.contentDescription.toString().endsWith(": off") }.performClick()
        assertFalse(SelectPreferences(context).pickLocked)
        prefs.observeLockedLines(false)
        assertEquals(2, lockedChoices(menu()).size)
    }

    @Test fun legacyPersistedOffChoiceStillHasAWayBack() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("joybrush_select", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("pick_locked", false).commit()
        assertTrue(SelectPreferences(context).lockedOptionRevealed)
        assertFalse(SelectPreferences(context).pickLocked)
        // A caller supplying an enabled persisted choice also cannot hide its off control.
        val menu = SelectSettingsView(ChromeKit(context), false, false, {}, {}, pickLocked = true)
        assertTrue(descendants(menu).any { it.contentDescription == "Pick locked lines too: off" })
    }

    @Test fun fingerAndLineActionsRemainButGlobalLineModeControlsAreRetired() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("joybrush_select", Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = SelectPreferences(context)
        assertFalse(preferences.fingerSelects)
        assertFalse(preferences.pickLocked)
        assertTrue(preferences.dimRest)
        var obsoleteCallbackCalls = 0
        val menu = SelectSettingsView(ChromeKit(context), preferences.fingerSelects, false,
            { preferences.fingerSelects = it }, { obsoleteCallbackCalls++ })
        val legacyEnabledMenu = SelectSettingsView(ChromeKit(context), preferences.fingerSelects, true,
            { preferences.fingerSelects = it }, { obsoleteCallbackCalls++ }, perBrushLinesOverrideShelf = true)
        val choices = descendants(menu).filter { it.isClickable && it.contentDescription in setOf("Pans", "Selects") }
        assertEquals(2, choices.size)
        assertTrue(choices.all { !it.contentDescription.isNullOrBlank() })
        assertTrue(descendants(menu).any { it.contentDescription == "Select every editable line on this layer" })
        assertFalse(descendants(menu).any { it.contentDescription == "Paste the copied editable lines onto this layer" })
        for (view in listOf(menu, legacyEnabledMenu)) {
            assertFalse(descendants(view).any { it.contentDescription in setOf("Editable lines", "Pixels") })
            assertFalse(descendants(view).filterIsInstance<android.widget.TextView>()
                .any { it.text.toString().contains("Editable lines") || it.text.toString().contains("Pixels") })
        }
        fun choice(label: String) = choices.first { it.contentDescription == label }
        assertTrue(choice("Pans").isSelected)
        choice("Selects").performClick()
        assertTrue(SelectPreferences(context).fingerSelects)
        assertFalse(choice("Pans").isSelected)
        assertEquals(0, obsoleteCallbackCalls)
        assertTrue(preferences.fingerSelects)
        choice("Pans").performClick()
        assertFalse(SelectPreferences(context).fingerSelects)
    }
}
