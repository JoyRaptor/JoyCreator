package cc.joycreator.joybrush.core.input

import kotlin.test.Test
import kotlin.test.assertEquals

/** Stroke files store Tool.ordinal (JB-0.04). Reordering the enum would silently corrupt old files. */
class ToolOrdinalFreezeTest {
    @Test
    fun toolOrdinalsNeverChange() {
        assertEquals(listOf("STYLUS", "ERASER", "FINGER", "MOUSE"), Tool.entries.take(4).map { it.name })
    }
}
