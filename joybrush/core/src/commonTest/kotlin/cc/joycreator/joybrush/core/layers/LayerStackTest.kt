package cc.joycreator.joybrush.core.layers

import cc.joycreator.joybrush.core.doc.BlendMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JB-2.04's pure half, as the Lead built it for the thumbnail column: the stack, the names, the budget. */
class LayerStackTest {

    private fun ids(s: LayerStack) = s.layers.map { it.id }

    /** Bottom → top: a, b, c; b active. */
    private val abc = LayerStack(listOf(LayerState("a", "A"), LayerState("b", "B"), LayerState("c", "C")), "b")

    @Test
    fun aNewLayerGoesDirectlyAboveTheActiveOneAndTakesTheBrush() {
        val s = abc.add("d", "D")
        assertEquals(listOf("a", "b", "d", "c"), ids(s))
        assertEquals("d", s.activeId)
        assertFailsWith<IllegalArgumentException> { abc.add("a") }
    }

    @Test
    fun namesCountUpAndNeverRepeat() {
        val one = LayerStack.single()
        assertEquals("Layer 2", one.nextName())
        val three = one.add("x").add("y")
        assertEquals(listOf("Layer 1", "Layer 2", "Layer 3"), three.layers.map { it.name })
        // Deleting Layer 2 does not hand its name out again.
        assertEquals("Layer 4", three.delete("x")!!.nextName())
        assertEquals("layer-4", three.freshId())
    }

    @Test
    fun aDuplicateSitsAboveItsSourceWithItsSettingsAndACopyName() {
        val src = abc.withOpacity("a", 0.4f).withBlend("a", BlendMode.MULTIPLY)
        val s = src.duplicate("a", "a2")!!
        assertEquals(listOf("a", "a2", "b", "c"), ids(s))
        assertEquals(LayerState("a2", "A copy", 0.4f, true, BlendMode.MULTIPLY), s["a2"])
        assertEquals("a2", s.activeId)
        assertNull(abc.duplicate("nope", "z"))
        assertEquals("Hair copy", LayerStack.copyName("Hair"))
        assertEquals("Hair copy 2", LayerStack.copyName("Hair copy"))
        assertEquals("Hair copy 3", LayerStack.copyName("Hair copy 2"))
    }

    @Test
    fun theLastLayerCannotBeDeletedAndTheBrushGoesToTheLayerBelow() {
        assertNull(LayerStack.single().delete("layer-1"))
        assertEquals("a", abc.delete("b")!!.activeId)
        // The bottom layer active and deleted: the one that was above it.
        assertEquals("b", abc.select("a").delete("a")!!.activeId)
        // Deleting another layer leaves the brush where it is.
        assertEquals("b", abc.delete("c")!!.activeId)
        assertNull(abc.delete("nope"))
    }

    @Test
    fun movingIsClampedAndKeepsTheBrush() {
        assertEquals(listOf("b", "c", "a"), ids(abc.move("a", 99)))
        assertEquals(listOf("c", "a", "b"), ids(abc.move("c", -5)))
        assertEquals("b", abc.move("b", 0).activeId)
        assertEquals(abc, abc.move("nope", 0))
    }

    @Test
    fun settingsAreClampedAndABlankNameIsRefused() {
        assertEquals(0f, abc.withOpacity("a", -1f)["a"]!!.opacity)
        assertEquals(1f, abc.withOpacity("a", 7f)["a"]!!.opacity)
        assertEquals(1f, abc.withOpacity("a", Float.NaN)["a"]!!.opacity)
        assertEquals("Sky", abc.rename("a", "  Sky ")["a"]!!.name)
        assertEquals("A", abc.rename("a", "   ")["a"]!!.name)
        assertEquals(LayerStack.MAX_NAME, abc.rename("a", "x".repeat(200))["a"]!!.name.length)
        assertEquals(abc, abc.select("nope"))
    }

    @Test
    fun undoingAStackChangeKeepsTheLayersAsTheyAreShownOrHidden() {
        // Before: b at 100%. The person set it to 30%, then hid c. Undoing the opacity must not bring c back.
        val before = abc
        val now = abc.withOpacity("b", 0.3f).withVisible("c", false)
        val undone = now.restoring(before)
        assertEquals(1f, undone["b"]!!.opacity)
        assertEquals(false, undone["c"]!!.visible)
        // A layer the target has and "now" does not (undoing a delete) comes back as the target had it.
        val deleted = abc.delete("c")!!
        assertEquals(true, deleted.restoring(abc)["c"]!!.visible)
        // The brush stays put when its layer survives, and follows the target when it does not.
        assertEquals("b", abc.select("b").restoring(abc.select("a")).activeId)
        assertEquals("d", abc.restoring(LayerStack(listOf(LayerState("d", "D")), "d")).activeId)
    }

    @Test
    fun aStackIsNeverEmptyAndItsIdsAreUnique() {
        assertFailsWith<IllegalArgumentException> { LayerStack(emptyList(), "a") }
        assertFailsWith<IllegalArgumentException> { LayerStack(listOf(LayerState("a", "A"), LayerState("a", "B")), "a") }
        assertFailsWith<IllegalArgumentException> { LayerStack(listOf(LayerState("a", "A")), "z") }
    }

    @Test
    fun everyBlendModeIsInThePickerExactlyOnceAndHasANameAndAShortName() {
        assertEquals(BlendMode.entries.toSet(), BlendNames.ORDER.toSet())
        assertEquals(BlendMode.entries.size, BlendNames.ORDER.size)
        for (m in BlendMode.entries) {
            assertTrue(BlendNames.name(m).isNotBlank())
            assertTrue(BlendNames.short(m).length <= 5, "${m.name} short name too long")
        }
        assertEquals("", BlendNames.short(BlendMode.NORMAL))
        assertEquals("Colour dodge", BlendNames.name(BlendMode.COLOR_DODGE))
    }

    @Test
    fun theBudgetOnANote9IsThirtyTwoLayers() {
        // 6 GiB × 6% = 386,547,056 B; a 1080 × 2220 page is 5 × 9 = 45 tiles × 262,144 B = 11,796,480 B; → 32.77 → 32.
        assertEquals(32, LayerBudget.maxLayers(6L shl 30, 1080, 2220))
    }

    @Test
    fun theBudgetIsClampedAndNeverBreaksOnNonsense() {
        assertEquals(LayerBudget.MIN, LayerBudget.maxLayers(0L, 1080, 2220))
        assertEquals(LayerBudget.MIN, LayerBudget.maxLayers(-5L, 1080, 2220))
        assertEquals(LayerBudget.MIN, LayerBudget.maxLayers(6L shl 30, 0, 2220))
        assertEquals(LayerBudget.MIN, LayerBudget.maxLayers(1L shl 20, 4096, 4096))
        assertEquals(LayerBudget.MAX, LayerBudget.maxLayers(64L shl 30, 256, 256))
        // More memory never means fewer layers; a bigger page never means more.
        assertTrue(LayerBudget.maxLayers(8L shl 30, 1080, 2220) >= LayerBudget.maxLayers(6L shl 30, 1080, 2220))
        assertTrue(LayerBudget.maxLayers(6L shl 30, 2560, 1600) <= LayerBudget.maxLayers(6L shl 30, 1080, 2220))
    }
}
