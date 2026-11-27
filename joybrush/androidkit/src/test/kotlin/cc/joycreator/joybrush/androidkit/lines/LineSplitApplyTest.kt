package cc.joycreator.joybrush.androidkit.lines

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * LineEdit.Split (11-27, the slice and partial erasers): shortened lines in place, erased lines out, in one edit. The
 * engine's own tile work needs GL, so the list change it makes is tested here through [LineSplitApply], the one call it
 * makes for a Split. The other edit kinds are untouched code in the engine; their addresses are pinned here as before.
 */
class LineSplitApplyTest {
    private val plane = LinePlane("L", null)
    private fun line(id: String, seq: Long, n: Int = 10) = StrokeRecord(id = id, brushId = "b", seed = 1L, smoothing = 0f,
        screenPerDoc = 1f, samples = List(n) { PenSample(x = it * 2f, y = seq * 10f, timeMs = it.toDouble()) }, seq = seq)
    private fun at(id: String) = plane.address(id)

    @Test fun aShortenedLineKeepsItsPlaceAndAnErasedOneLeavesInOnePass() {
        val list = mutableListOf(line("a", 1), line("b", 2), line("c", 3), line("d", 4))
        val shorter = line("b", 0, n = 4)                          // the seq it carries is ignored: its place is kept
        LineSplitApply.apply(LineEdit.Split(listOf(AddressedStroke(at("b"), shorter)), setOf(at("c")))) { list }
        assertEquals(listOf("a", "b", "d"), list.map { it.id })
        assertEquals(2L, list[1].seq, "the shortened line keeps its seq, so nothing above or below it moves")
        assertEquals(4, list[1].samples.size)
        assertEquals(listOf(1L, 4L), listOf(list[0].seq, list[2].seq))
        assertEquals(line("a", 1), list[0], "untouched lines are the same records")
    }

    @Test fun aLineThatIsGoneRefusesTheEditInTheEnginesWords() {
        val list = mutableListOf(line("a", 1))
        assertEquals(LineSplitApply.GONE, assertFailsWith<IllegalArgumentException> {
            LineSplitApply.apply(LineEdit.Split(emptyList(), setOf(at("zz")))) { list }
        }.message)
        assertEquals(LineSplitApply.GONE, assertFailsWith<IllegalArgumentException> {
            LineSplitApply.apply(LineEdit.Split(listOf(AddressedStroke(at("zz"), line("zz", 1))), emptySet())) { list }
        }.message)
    }

    @Test fun everyEditKindNamesItsLinesAsBefore() {
        val r = AddressedStroke(at("a"), line("a", 1))
        assertEquals(listOf(at("a")), LineEdit.Replace(listOf(r)).addresses().toList())
        assertEquals(listOf(at("a")), LineEdit.Add(listOf(r)).addresses().toList())
        assertEquals(setOf(at("a")), LineEdit.Delete(setOf(at("a"))).addresses().toSet())
        assertEquals(setOf(at("a")), LineEdit.Bake(setOf(at("a"))).addresses().toSet())
        assertEquals(listOf(at("a"), at("b")), LineEdit.Split(listOf(r), setOf(at("b"))).addresses().toList())
        assertEquals(setOf("L"), LineEdit.Split(listOf(r), setOf(at("b"))).layerIds())
    }
}
