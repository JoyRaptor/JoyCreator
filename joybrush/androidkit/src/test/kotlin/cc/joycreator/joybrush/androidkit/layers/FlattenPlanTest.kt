package cc.joycreator.joybrush.androidkit.layers

import cc.joycreator.joybrush.androidkit.layers.FlattenPlan.Facts
import cc.joycreator.joybrush.androidkit.layers.FlattenPlan.Result
import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.layers.LayerState
import kotlin.test.*

/** LAYER_OPS L6: which layers Flatten freezes into which, and the stack after. */
class FlattenPlanTest {
    private val none: (String) -> Facts = { Facts() }
    private val stack = LayerStack(listOf(
        LayerState("a", "Sketch", opacity = 0.5f, blend = BlendMode.MULTIPLY, hasMask = true),
        LayerState("h", "Hidden", visible = false),
        LayerState("b", "Ink"),
        LayerState("c", "Colour", clip = true)), "c")

    @Test fun theBottomVisibleLayerBecomesThePlainFlattenedOneAndHiddenLayersAreKept() {
        val r = assertIs<Result.Flatten>(FlattenPlan.decide(stack, discardHidden = false, none))
        assertEquals("a", r.targetId)
        assertEquals(listOf("a", "b", "c"), r.merged)
        assertTrue(r.discarded.isEmpty())
        assertEquals(listOf("a", "h"), r.after.layers.map { it.id })
        assertEquals(LayerState("a", "Sketch"), r.after["a"], "its blend, opacity and mask are in its pixels now")
        assertEquals("a", r.after.activeId)
    }

    @Test fun discardingHiddenLayersTakesThemInTheSameStep() {
        val r = assertIs<Result.Flatten>(FlattenPlan.decide(stack, discardHidden = true, none))
        assertEquals(listOf("h"), r.discarded)
        assertEquals(listOf("a"), r.after.layers.map { it.id })
    }

    @Test fun refusalsAreSentences() {
        val one = LayerStack(listOf(LayerState("a", "A"), LayerState("b", "B", visible = false)), "a")
        assertIs<Result.Refused>(FlattenPlan.decide(one, discardHidden = false, none))
        assertIs<Result.Flatten>(FlattenPlan.decide(one, discardHidden = true, none), "one visible layer plus hidden ones to discard")
        val locked = assertIs<Result.Refused>(FlattenPlan.decide(stack.withLocked("b", true), false, none))
        assertTrue(locked.sentence.contains("Ink"))
        val frames = assertIs<Result.Refused>(FlattenPlan.decide(stack, false) { Facts(hasFrames = it == "b") })
        assertTrue(frames.sentence.contains("animation"))
    }
}
