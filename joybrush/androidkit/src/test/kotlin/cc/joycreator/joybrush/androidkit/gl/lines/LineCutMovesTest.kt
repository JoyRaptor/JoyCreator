package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LineCutPlan
import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Concepts parity 4a: the room a cut makes, applied to a plane of lines and pixel slabs and undone, on a plain JVM. The
 * moves must apply in one go though items change places past each other, keep every item's order, and undo exactly.
 */
class LineCutMovesTest {
    private val paint = LinePlane("paint", "c1")
    private val planeOf: (String, String?) -> LinePlane = { layer, cel -> LinePlane(layer, cel) }
    private fun line(id: String, seq: Long) = StrokeRecord(id, "pen", 7L, 0f, 1f, List(8) { PenSample(it * 3f, 2f, it.toDouble()) }, seq = seq)

    private class Fake(val tiles: Map<String, Set<Long>>) {
        fun footprint(r: StrokeRecord, b: BrushPreset?): Set<Long> = tiles[r.id.substringBefore('~')].orEmpty()
    }

    /** a (1), b (2), a normal slab (3) on tiles 5 and 6, c (4) on tile 6, an erase slab (5) on tile 6, d (6). */
    private fun populated(): NativeLines {
        val nl = NativeLines(Fake(mapOf("a" to setOf(1L), "b" to setOf(5L, 6L), "c" to setOf(6L), "d" to setOf(9L)))::footprint)
        nl.put(paint, "a", line("a", 1)); nl.put(paint, "b", line("b", 2)); nl.put(paint, "c", line("c", 4)); nl.put(paint, "d", line("d", 6))
        nl.setSlab(paint, 3, 5L, 35, "normal"); nl.setSlab(paint, 3, 6L, 36, "normal")
        nl.setSlab(paint, 5, 6L, 56, "erase")
        return nl
    }

    /** Every item by seq: a line by id, a slab by its op and its tiles. Textures differ after a move (copies), so not compared. */
    private fun order(nl: NativeLines): List<String> {
        val items = sortedMapOf<Long, String>()
        for (l in nl.lines(paint)) items[l.seq] = l.id
        for (s in nl.slabSeqs(paint)) items[s] = nl.slab(paint, s)!!.let { "slab ${it.op} ${it.tiles.keys.sorted()}" }
        return items.values.toList()
    }

    private fun exact(nl: NativeLines) = listOf(nl.lines(paint).map { it.id to it.seq },
        nl.slabSeqs(paint).map { s -> nl.slab(paint, s)!!.let { Triple(s, it.op, it.tiles.toSortedMap().toList()) } },
        nl.crossedKeys(paint).sorted())

    @Test fun cuttingBMovesEverythingAboveItUpInOneGoAndUndoPutsItAllBack() {
        val nl = populated()
        val before = exact(nl)
        val b = nl.record(paint, "b")!!
        val plan = LineCutPlan.plan(nl.lines(paint), nl.slabSeqs(paint),
            listOf(LineCutPlan.Cut(paint.address("b"), listOf(b.copy(samples = b.samples.take(3)), b.copy(id = "b~2", samples = b.samples.drop(5))))))
        var next = 100
        val copies = ArrayList<Int>()
        val moves = LineCutMoves.of(nl, paint, "paint", "c1", plan) { next++.also(copies::add) }
        assertEquals(setOf(35, 36, 56), moves.slabs.mapNotNull { it.before?.texture }.toSet(), "history keeps the textures the slabs had")
        assertEquals(copies.toSet(), moves.slabs.mapNotNull { it.after?.texture }.toSet(), "the moved slabs own their copies")
        assertEquals(moves.slabs.size, moves.slabs.map { it.seq to it.key }.toSet().size, "one change per (seq, tile)")

        LineItems.apply(nl, moves.slabs, moves.lines, forward = true, footprints = moves.footprints, planeOf = planeOf)
        nl.put(paint, "b~2", plan.extra.single())
        assertEquals(listOf("a", "b", "b~2", "slab normal [5, 6]", "c", "slab erase [6]", "d"), order(nl),
            "the second piece right after b; everything else in the order it had")
        assertTrue(6L in nl.footprint(paint, "c"), "a moved line keeps where it reaches")

        nl.put(paint, "b~2", null)
        LineItems.apply(nl, moves.slabs, moves.lines, forward = false, footprints = moves.footprints, planeOf = planeOf)
        assertEquals(before, exact(nl), "undo gives the plane back exactly, its own textures included")
    }

    @Test fun cuttingTheTopLineMovesNothing() {
        val nl = populated()
        val d = nl.record(paint, "d")!!
        val plan = LineCutPlan.plan(nl.lines(paint), nl.slabSeqs(paint),
            listOf(LineCutPlan.Cut(paint.address("d"), listOf(d.copy(samples = d.samples.take(3)), d.copy(id = "d~2", samples = d.samples.drop(5))))))
        val moves = LineCutMoves.of(nl, paint, "paint", "c1", plan) { error("nothing is copied") }
        assertTrue(moves.lines.isEmpty() && moves.slabs.isEmpty())
        assertEquals(7L, plan.extra.single().seq)
    }
}
