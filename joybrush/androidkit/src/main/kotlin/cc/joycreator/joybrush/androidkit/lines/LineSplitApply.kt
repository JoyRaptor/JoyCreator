package cc.joycreator.joybrush.androidkit.lines

import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/**
 * How a [LineEdit.Split] changes a plane's line list, kept apart from the engine so it is tested on a plain JVM
 * (the engine's tile work needs GL). [GlPaintEngine.editLines] calls this for a Split and for nothing else; the other
 * edit kinds keep their own code untouched.
 *
 * A shortened line keeps its place: same id, same seq (time order is the only order, and a seq belongs to one thing,
 * LineRules D1), so nothing above or below it changes. An erased line leaves its list. A line that is gone already
 * refuses the whole edit, in the same words the other kinds use.
 */
object LineSplitApply {
    const val GONE = "That line is gone (undone or deleted)"

    fun apply(edit: LineEdit.Split, listOf: (LineAddress) -> MutableList<StrokeRecord>) {
        for (a in edit.after) {
            val l = listOf(a.address)
            val i = l.indexOfFirst { it.id == a.address.recordId }
            require(i >= 0) { GONE }
            l[i] = a.record.copy(seq = l[i].seq)
        }
        for (a in edit.removed) {
            val l = listOf(a)
            require(l.removeAll { it.id == a.recordId }) { GONE }
        }
    }
}
