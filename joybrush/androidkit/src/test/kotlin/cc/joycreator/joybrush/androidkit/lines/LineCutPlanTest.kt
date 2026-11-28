package cc.joycreator.joybrush.androidkit.lines

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A line erased in its middle (Concepts parity 4a): the later pieces take places right after the line they came from,
 * everything above moves up to make room, and the order of every item stays exactly what it was.
 */
class LineCutPlanTest {
    private val plane = LinePlane("L", null)
    private fun line(id: String, seq: Long, n: Int = 10) = StrokeRecord(id = id, brushId = "b", seed = 1L, smoothing = 0f,
        screenPerDoc = 1f, samples = List(n) { PenSample(x = it * 2f, y = seq * 10f, timeMs = it.toDouble()) }, seq = seq)
    private fun cut(of: StrokeRecord, vararg ids: String) =
        LineCutPlan.Cut(plane.address(of.id), ids.map { of.copy(id = it, samples = of.samples.take(3)) })

    /** Every item's new seq, by what it is: a line by id, a slab by "slab@old". */
    private fun order(lines: List<StrokeRecord>, slabs: List<Long>, plan: LineCutPlan.Plan): List<String> {
        val items = HashMap<Long, String>()
        for (l in lines) items[plan.moves[l.seq] ?: l.seq] = plan.first[l.id]?.id ?: l.id
        for (s in slabs) items[plan.moves[s] ?: s] = "slab@$s"
        for (e in plan.extra) assertEquals(null, items.put(e.seq, e.id), "a seq belongs to one thing")
        return items.toSortedMap().values.toList()
    }

    @Test fun theSecondPieceGoesRightAfterItsLineAndEverythingAboveMovesUp() {
        val lines = listOf(line("a", 1), line("b", 2), line("c", 4))
        val slabs = listOf(3L, 5L)
        val plan = LineCutPlan.plan(lines, slabs, listOf(cut(lines[1], "b", "b~2")))
        assertEquals(listOf("a", "b", "b~2", "slab@3", "c", "slab@5"), order(lines, slabs, plan))
        assertEquals(mapOf(3L to 4L, 4L to 5L, 5L to 6L), plan.moves, "only what is above the cut moves, by one")
        assertEquals(2L, plan.first.getValue("b").seq, "the first piece keeps the line's place")
        assertEquals(3L, plan.extra.single().seq)
    }

    @Test fun cuttingTheTopLineMovesNothing() {
        val lines = listOf(line("a", 1), line("b", 2))
        val plan = LineCutPlan.plan(lines, listOf(), listOf(cut(lines[1], "b", "b~2", "b~3")))
        assertTrue(plan.moves.isEmpty())
        assertEquals(listOf(3L, 4L), plan.extra.map { it.seq })
        assertEquals(listOf("a", "b", "b~2", "b~3"), order(lines, listOf(), plan))
    }

    @Test fun twoCutsInOneEditKeepEveryItemInItsOrder() {
        val lines = listOf(line("a", 1), line("b", 2), line("c", 3), line("d", 5))
        val slabs = listOf(4L)
        val plan = LineCutPlan.plan(lines, slabs, listOf(cut(lines[0], "a", "a~2"), cut(lines[2], "c", "c~2", "c~3")))
        assertEquals(listOf("a", "a~2", "b", "c", "c~2", "c~3", "slab@4", "d"), order(lines, slabs, plan))
        assertEquals(4L, plan.first.getValue("c").seq, "a cut line above another cut moves up too")
    }

    @Test fun aPieceNeverTakesTheNameOfALineAlreadyThere() {
        // b was cut before, so b~2 is a line of its own; cutting b again names the new piece b~3.
        val lines = listOf(line("b", 1), line("b~2", 2))
        val plan = LineCutPlan.plan(lines, listOf(), listOf(cut(lines[0], "b", "b~2")))
        assertEquals("b~3", plan.extra.single().id)
        assertEquals(listOf("b", "b~3", "b~2"), order(lines, listOf(), plan))
    }

    @Test fun aCutOfALineThatIsGoneIsRefusedInTheEnginesWords() {
        val e = assertFailsWith<IllegalArgumentException> { LineCutPlan.plan(listOf(line("a", 1)), listOf(), listOf(cut(line("zz", 9), "zz", "zz~2"))) }
        assertEquals(LineSplitApply.GONE, e.message)
    }

    @Test fun nothingBelowTheCutMoves() {
        val lines = (1L..20L).map { line("l$it", it) }
        val plan = LineCutPlan.plan(lines, listOf(), listOf(cut(lines[14], "l15", "l15~2")))
        assertTrue(plan.moves.keys.all { it > 15L }, "only seqs above 15 move")
        assertEquals(5, plan.moves.size)
    }
}
