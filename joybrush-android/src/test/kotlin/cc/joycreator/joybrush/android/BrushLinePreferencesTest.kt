package cc.joycreator.joybrush.android

import android.content.Context
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.Param
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrushLinePreferencesTest {
    private val water = BrushPreset(id = "water", name = "Water", size = Param(12f), engine = ENGINE_WATERCOLOUR)
    private val ink = BrushPreset(id = "ink", name = "Ink", size = Param(12f))
    private fun preferences() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("brush-line-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun watercolourStartsAsLinesWhileAnOrdinaryBrushKeepsTheLegacyDefault() {
        val mode = BrushLinePreferences(preferences())
        assertTrue(mode.enabled(water))
        assertFalse(mode.enabled(ink))
        assertFalse(mode.enabled(null))
    }

    @Test fun choicesPersistIndependentlyForEachBrushAndCanOverrideTheWatercolourDefault() {
        val prefs = preferences()
        val mode = BrushLinePreferences(prefs)
        mode.set(water, false)
        mode.set(ink, true)
        val reopened = BrushLinePreferences(prefs)
        assertFalse(reopened.enabled(water))
        assertTrue(reopened.enabled(ink))
        assertFalse(reopened.enabled(ink.copy(id = "another-ink")))
        mode.set(water, true)
        assertTrue(reopened.enabled(water))
    }

    @Test fun anExistingGlobalChoiceIsPreservedAsFallbackWithoutOverwritingBrushChoices() {
        val prefs = preferences()
        prefs.edit().putBoolean(BrushLinePreferences.LEGACY, true).commit()
        val mode = BrushLinePreferences(prefs)
        assertTrue(mode.enabled(ink))
        mode.set(water, false)
        assertFalse(mode.enabled(water))
        assertTrue(mode.enabled(ink))
        assertTrue(prefs.getBoolean(BrushLinePreferences.LEGACY, false))
    }
}
