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
    private val ink = BrushPreset(id = "joybrush.ink", name = "Ink", size = Param(12f))
    private val paint = BrushPreset(id = "paint", name = "Paint", size = Param(12f))
    private fun preferences() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("brush-line-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun watercolourAndInksStartAsLinesWhilePaintingStartsAsPixels() {
        val mode = BrushLinePreferences(preferences())
        assertTrue(mode.enabled(water))
        assertTrue(mode.enabled(ink))
        assertFalse(mode.enabled(paint))
        assertFalse(mode.enabled(null))
    }

    @Test fun choicesPersistIndependentlyForEachBrushAndCanOverrideTheWatercolourDefault() {
        val prefs = preferences()
        val mode = BrushLinePreferences(prefs)
        mode.set(water, false)
        mode.set(paint, true)
        val reopened = BrushLinePreferences(prefs)
        assertFalse(reopened.enabled(water))
        assertTrue(reopened.enabled(paint))
        assertFalse(reopened.enabled(paint.copy(id = "another-paint")))
        mode.set(water, true)
        assertTrue(reopened.enabled(water))
    }

    @Test fun oldGlobalChoiceDoesNotEnablePaintingOrOverwritePerBrushChoices() {
        val prefs = preferences()
        prefs.edit().putBoolean("joybrush.editableLines", true).commit()
        val mode = BrushLinePreferences(prefs)
        assertTrue(mode.enabled(ink))
        assertFalse(mode.enabled(paint))
        mode.set(water, false)
        assertFalse(mode.enabled(water))
        assertTrue(mode.enabled(ink))
        assertTrue(prefs.getBoolean("joybrush.editableLines", false))
    }

    @Test fun everyStagedLinePresetDefaultsOnEvenIfItsNameLooksLikePainting() {
        val mode = BrushLinePreferences(preferences())
        for (id in listOf("brush_pen", "chisel_marker", "dash_pen", "fill_pen", "fineliner", "pen",
            "shading_pencil", "sketch_pencil", "watercolour", "wire")) {
            assertTrue(id, mode.enabled(paint.copy(id = "joybrush.lines.$id")))
        }
    }

    @Test fun pixelsAlwaysEnginesIgnoreOldPerBrushOnAndDoNotOfferOrWriteASwitch() {
        val prefs = preferences()
        val mode = BrushLinePreferences(prefs)
        for (engine in listOf("media", "smudge", "push", "wet", "blur", "clone", "paste")) {
            val brush = water.copy(id = "joybrush.lines.pen", engine = engine)
            prefs.edit().putBoolean("joybrush.brushMakesLines.${brush.id}", true).commit()
            assertFalse(engine, BrushLinePreferences.canSwitch(brush))
            assertFalse(engine, mode.enabled(brush))
            mode.set(brush, false)
            assertTrue(engine, prefs.getBoolean("joybrush.brushMakesLines.${brush.id}", false))
        }
    }

    @Test fun nullBrushDoesNotReadOrCreateAGlobalSetting() {
        val prefs = preferences()
        val mode = BrushLinePreferences(prefs)
        assertFalse(BrushLinePreferences.canSwitch(null))
        mode.set(null, true)
        assertTrue(prefs.all.isEmpty())
        assertFalse(mode.enabled(null))
    }

    @Test fun paintingPencilsAndMarkersDoNotInheritTheOldShelfDefaults() {
        val prefs = preferences()
        val mode = BrushLinePreferences(prefs)
        assertFalse(mode.enabled(paint.copy(id = "joybrush.pencil", name = "Pencil")))
        assertFalse(mode.enabled(paint.copy(id = "joybrush.marker", name = "Marker")))
        mode.set(paint, true)
        assertTrue(BrushLinePreferences(prefs).enabled(paint))
    }

    @Test fun inkAndPenSubstringsDoNotTurnAPaintingBrushIntoLines() {
        val mode = BrushLinePreferences(preferences())
        assertFalse(mode.enabled(paint.copy(id = "pink_paint", name = "Pen wash")))
        assertFalse(mode.enabled(paint.copy(id = "custom.ink", name = "Ink painting")))
        assertTrue(mode.enabled(ink.copy(name = "Renamed")))
    }
}
