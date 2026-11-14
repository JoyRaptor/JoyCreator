package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_MEDIA
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.chrome.BrushKnobGroups.EndPicture
import cc.joycreator.joybrush.core.chrome.BrushKnobGroups.Group
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BRUSH-UI section K: every slider lands in a group and has words (and a picture kind) for both ends. */
class BrushKnobGroupsTest {
    @Test fun theOwnersExamplesLandWhereHeSaid() {
        // Dabs for softness, shape and size; tiny strokes for spacing, taper, pickup and blending.
        assertEquals(EndPicture.DAB, BrushKnobGroups.endsOf("stamp.hardness").picture)
        assertEquals(EndPicture.DAB, BrushKnobGroups.endsOf("stamp.thinnest").picture)
        assertEquals(EndPicture.STROKE, BrushKnobGroups.endsOf("stamp.spacing").picture)
        assertEquals(EndPicture.STROKE, BrushKnobGroups.endsOf("media.paste.ends").picture)
        assertEquals(EndPicture.STROKE, BrushKnobGroups.endsOf("smudge.pickup").picture)
        assertEquals("Soft" to "Crisp", BrushKnobGroups.endsOf("stamp.hardness").let { it.low to it.high })
    }

    @Test fun groupsBySense() {
        assertEquals(Group.SCATTER, BrushKnobGroups.groupOf("stamp.spacing"))
        assertEquals(Group.SCATTER, BrushKnobGroups.groupOf("stamp.angleJitter"))
        assertEquals(Group.PAPER, BrushKnobGroups.groupOf("stamp.grain"))
        assertEquals(Group.WATER, BrushKnobGroups.groupOf("media.wetness"))
        assertEquals(Group.COLOUR, BrushKnobGroups.groupOf("smudge.pickup"))
        assertEquals(Group.ENDS, BrushKnobGroups.groupOf("media.paste.ends"))
        assertEquals(Group.INK, BrushKnobGroups.groupOf("stamp.flow"))
        assertEquals(Group.PRESSURE, BrushKnobGroups.groupOf("stamp.opacityPressure"))
        assertEquals(Group.FEEL, BrushKnobGroups.groupOf("smoothing"))
        assertEquals(Group.SHAPE, BrushKnobGroups.groupOf("stamp.hardness"))
    }

    @Test fun everyRealKnobHasTwoDifferentWords() {
        val brushes = listOf(
            BrushPreset(id = "s", name = "S", size = Param(10f)),
            BrushPreset(id = "m", name = "M", engine = ENGINE_MEDIA, size = Param(10f)),
        )
        for (p in brushes) for (knob in runCatching { BrushKnobs.forBrush(p) }.getOrDefault(emptyList())) {
            val e = BrushKnobGroups.endsOf(knob.key)
            assertTrue(e.low.isNotBlank() && e.high.isNotBlank() && e.low != e.high, knob.key)
        }
    }

    @Test fun pencilWatercolourAndOilSettingsHaveRealWords() {
        assertEquals("Sharp" to "Blunt", BrushKnobGroups.endsOf("media.stick.tipR").let { it.low to it.high })
        assertEquals(EndPicture.DAB, BrushKnobGroups.endsOf("media.stick.tipR").picture)
        assertEquals(Group.WATER, BrushKnobGroups.groupOf("media.wet.dwellMmPerS"))
        assertEquals(Group.ENDS, BrushKnobGroups.groupOf("media.paste.riseMaxMm"))
        assertEquals("Pure" to "Mixes", BrushKnobGroups.endsOf("media.paste.mix").let { it.low to it.high })
    }
}
