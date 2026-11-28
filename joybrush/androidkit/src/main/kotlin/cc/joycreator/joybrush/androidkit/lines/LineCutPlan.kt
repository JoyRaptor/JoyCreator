package cc.joycreator.joybrush.androidkit.lines

import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/**
 * A line erased in its middle becomes two (or more) lines (Concepts parity 4a, 11-28). Every item of a cel has its own
 * place in time order (`seq`, LineRules D1), so the later pieces need places of their own RIGHT AFTER the line they came
 * from, or anything drawn later would end up under them. The plan makes that room by moving every item above the cut up
 * (lines and pixel slabs alike, each by the number of pieces inserted below it), so the order of everything is exactly
 * what it was and the file format is unchanged. Pure, so it is tested on a plain JVM; [GlPaintEngine.editLines] carries it
 * out as part of ONE undo step.
 */
object LineCutPlan {

    /** [pieces] in path order, two or more; the first keeps [address]'s record id. */
    data class Cut(val address: LineAddress, val pieces: List<StrokeRecord>)

    /**
     * [moves]: old seq to new seq, only for items that move. [first]: each cut line's id to its first piece at the line's
     * (moved) seq. [extra]: the later pieces with their own seqs and ids no other line of the plane has.
     */
    class Plan(val moves: Map<Long, Long>, val first: Map<String, StrokeRecord>, val extra: List<StrokeRecord>)

    /** [lines] and [slabSeqs] are one plane's items now; every cut is a line of [lines]. */
    fun plan(lines: List<StrokeRecord>, slabSeqs: Collection<Long>, cuts: List<Cut>): Plan {
        val byId = lines.associateBy { it.id }
        val inserted = HashMap<Long, Cut>()
        for (c in cuts) {
            require(c.pieces.size >= 2) { "a cut leaves two pieces or more" }
            val line = requireNotNull(byId[c.address.recordId]) { LineSplitApply.GONE }
            require(c.pieces[0].id == line.id) { "the first piece keeps the line's name" }
            require(inserted.put(line.seq, c) == null) { "a line is cut once in one edit" }
        }
        val seqs = (lines.map { it.seq } + slabSeqs).toSortedSet()
        val moves = HashMap<Long, Long>()
        val first = LinkedHashMap<String, StrokeRecord>()
        val extra = ArrayList<StrokeRecord>()
        val taken = HashSet<String>(byId.keys)
        var offset = 0L
        for (q in seqs) {
            val now = q + offset
            if (now != q) moves[q] = now
            val cut = inserted[q] ?: continue
            first[cut.pieces[0].id] = cut.pieces[0].copy(seq = now)
            for ((i, piece) in cut.pieces.drop(1).withIndex()) {
                extra += piece.copy(id = freeId(cut.pieces[0].id, piece.id, taken), seq = now + 1 + i)
            }
            offset += cut.pieces.size - 1
        }
        return Plan(moves, first, extra)
    }

    /** [wanted] when no line of the plane has it, else "[base]~n" for the smallest free n from 2. */
    private fun freeId(base: String, wanted: String, taken: MutableSet<String>): String {
        if (taken.add(wanted)) return wanted
        var n = 2
        while (!taken.add("$base~$n")) n++
        return "$base~$n"
    }
}
