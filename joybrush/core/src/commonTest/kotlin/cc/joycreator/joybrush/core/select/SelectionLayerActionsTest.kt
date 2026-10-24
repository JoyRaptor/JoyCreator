package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.layers.LayerState
import cc.joycreator.joybrush.core.stroke.LineAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SelectionLayerActionsTest {
    private val a = LineAddress("a", null, "one")
    private val b = LineAddress("a", "frame", "two")
    private val c = LineAddress("b", null, "other")

    @Test fun rasterizeUsesTheChosenLayersSelectedLinesOrAllItsLines() {
        val all = setOf(a, b, c)
        assertEquals(setOf(a), SelectionLayerActions.bakeScope("a", setOf(a, c), all))
        assertEquals(setOf(a, b), SelectionLayerActions.bakeScope("a", setOf(c), all))
        assertEquals(emptySet(), SelectionLayerActions.bakeScope("missing", setOf(c), all))
    }

    @Test fun flattenLowerKeepsUpperLayersAndTheirActiveSelection() {
        val upper = LayerState("upper", "Upper", opacity = 0.7f, blend = BlendMode.MULTIPLY)
        val stack = LayerStack(listOf(LayerState("bottom", "Bottom", opacity = 0.3f, hasMask = true),
            LayerState("middle", "Middle"), upper), "upper")
        val result = assertIs<SelectionLayerActions.Lower.Plan>(SelectionLayerActions.flattenLower(stack, "middle"))
        assertEquals(listOf("bottom", "middle"), result.merged)
        assertEquals(listOf("bottom", "upper"), result.after.layers.map { it.id })
        assertEquals(upper, result.after["upper"])
        assertEquals("upper", result.after.activeId)
        assertEquals(LayerState("bottom", "Bottom"), result.after["bottom"])
    }

    @Test fun hiddenLowerLayersLeaveInTheSamePlanWithoutBeingMadeVisible() {
        val stack = LayerStack(listOf(LayerState("hidden", "Hidden", visible = false), LayerState("shown", "Shown"),
            LayerState("top", "Top")), "shown")
        val result = assertIs<SelectionLayerActions.Lower.Plan>(SelectionLayerActions.flattenLower(stack, "shown"))
        assertEquals(listOf("shown"), result.merged)
        assertEquals(listOf("hidden"), result.discarded)
        assertEquals("shown", result.after.activeId)
    }

    @Test fun aClipAboveTheResultCannotChangeItsClippingBaseSilently() {
        val stack = LayerStack(listOf(LayerState("bottom", "Bottom"), LayerState("middle", "Middle"),
            LayerState("clipped", "Clipped", clip = true)), "middle")
        assertIs<SelectionLayerActions.Lower.Refused>(SelectionLayerActions.flattenLower(stack, "middle"))
    }

    @Test fun lockedLayersAndAnimationFramesRefuseBeforeAPlanIsIssued() {
        val stack = LayerStack(listOf(LayerState("bottom", "Bottom"), LayerState("middle", "Middle")), "middle")
        assertIs<SelectionLayerActions.Lower.Refused>(SelectionLayerActions.flattenLower(stack.withLocked("bottom", true), "middle"))
        assertIs<SelectionLayerActions.Lower.Refused>(SelectionLayerActions.flattenLower(stack, "middle") { it == "bottom" })
    }
}
