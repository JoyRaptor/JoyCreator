package cc.joycreator.joybrush.androidkit.lines

import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import kotlin.test.*

class LineEditGateTest {
    @Test fun aLockedOrHiddenLayerRefusesInWordsAndLockedIsSaidFirst() {
        assertEquals("\"Ink\" is locked, so its lines can't be changed.", LineEditGate.layerRefusal("Ink", locked = true, visible = true))
        assertEquals("\"Ink\" is hidden, so its lines can't be changed.", LineEditGate.layerRefusal("Ink", locked = false, visible = false))
        assertEquals("\"Ink\" is locked, so its lines can't be changed.", LineEditGate.layerRefusal("Ink", locked = true, visible = false))
        assertNull(LineEditGate.layerRefusal("Ink", locked = false, visible = true))
    }

    @Test fun anEditIsStaleWhenTheDrawingWasReloadedTheGraphicsRestartedOrTheContentIsLost() {
        assertNull(LineEditGate.stale(3, 3, 1, 1, contentLost = false))
        assertEquals(LineEditGate.CHANGED, LineEditGate.stale(3, 4, 1, 1, contentLost = false))
        assertEquals(LineEditGate.CHANGED, LineEditGate.stale(3, 3, 1, 2, contentLost = false))
        assertEquals(LineEditGate.CHANGED, LineEditGate.stale(3, 3, 1, 1, contentLost = true))
        assertEquals("The drawing changed; try again.", LineEditGate.CHANGED)
    }

    @Test fun everyKindOfEditNamesTheLayersItTouches() {
        fun a(layer: String, id: String) = LineAddress(layer, "c", id)
        assertEquals(setOf("a", "b"), LineEdit.Delete(setOf(a("a", "1"), a("b", "2"), a("a", "3"))).layerIds())
        assertEquals(setOf("a"), LineEdit.Bake(setOf(a("a", "1"))).layerIds())
        assertTrue(LineEdit.Delete(emptySet()).layerIds().isEmpty())
    }
}
