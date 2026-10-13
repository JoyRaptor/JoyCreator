package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.vector.InkLine
import cc.joycreator.joybrush.core.vector.StrokePicker

/**
 * JB-5.20e — what is selected, and the rules that change it (spec D3, D6, D9, and the owner's pick P6). Pure: no view,
 * no engine, no undo. Selecting, cycling and re-reading a loop are never undo steps (D20); the edits that follow are.
 *
 * Lines are given as [InkLine]s (the picker's and the eraser's view of a line: centreline plus half-widths), in drawing
 * order, the most recent last. Coordinates are document px; [screenPerDoc] is the zoom, because every distance a finger
 * aims at is a SCREEN distance.
 */
class SelectionState(
    private val picker: StrokePicker = StrokePicker(),
    private val items: Items = Items.NONE,
) {

    /**
     * What a line is besides its geometry (JB-5.20a: `StrokeRecord.locked`, `StrokeRecord.groupId`). A LOCKED line is
     * skipped by taps, loops and choices unless [pickLocked] is on; a line in a GROUP brings its whole group (within the
     * lines given) into or out of the selection with it.
     */
    interface Items {
        fun locked(id: String): Boolean = false
        fun group(id: String): String? = null

        companion object {
            val NONE: Items = object : Items {}
        }
    }

    /** The Select menu's "Pick locked lines too". */
    var pickLocked: Boolean = false

    private fun pickable(lines: List<InkLine>): List<InkLine> = if (pickLocked) lines else lines.filter { !items.locked(it.id) }

    /** [ids] with every member of their groups among [scope]. */
    private fun withGroups(ids: Collection<String>, scope: List<InkLine>): Set<String> {
        val groups = ids.mapNotNull { items.group(it) }.toSet()
        if (groups.isEmpty()) return ids.toSet()
        val out = ids.toMutableSet()
        for (l in scope) if (items.group(l.id) in groups) out.add(l.id)
        return out
    }

    /** How a kept loop is read (D9): the three chips on the bar. */
    data class Reading(
        val take: Take = Take.AUTO,
        val reach: LineLasso.Reach = LineLasso.Reach.WHOLE,
        val allLayers: Boolean = false,
    )

    /** AUTO: the lines a loop encloses, or its paint when it encloses none (JB-5.20 D11). */
    enum class Take { AUTO, LINES, PIXELS }

    /** Where the last pick tapped, so a tap on the same spot can take the next line under it (D3). */
    data class Pick(val x: Double, val y: Double)

    sealed interface Selection

    /** Lines by id. [loop] and [reading] are present when a loop made it (so the chips can re-read it). */
    data class Lines(val ids: Set<String>, val loop: List<Pt>?, val reading: Reading?, val pick: Pick?) : Selection

    /** Paint inside [loop] on one layer. [linesInLoop] is what the Lines chip would take, for its count. */
    data class Pixels(val loop: List<Pt>, val reading: Reading, val linesInLoop: Int) : Selection

    var current: Selection? = null
        private set

    fun clear() { current = null }

    /**
     * A tap at ([x], [y]). [insideBox] says whether the point is inside the live box (D6). [adding] is the owner's
     * add-mode (P6: the ＋ chip or a resting thumb, or "tap adds"). Returns true when the selection changed.
     *
     * - Same spot (within [CYCLE_REACH_SCREEN_PX]) as the pick that made a one-line selection: the next line under it,
     *   wrapping, with NO time window (D3: the owner re-taps when he sees the wrong line, slower than a double-tap).
     * - On a line: that line alone, or with [adding] toggled in or out of a line selection.
     * - On nothing, inside the box: nothing changes (the likeliest mis-tap while working the handles).
     * - On nothing, outside the box: the selection ends.
     */
    fun tap(x: Double, y: Double, screenPerDoc: Double, lines: List<InkLine>, adding: Boolean, insideBox: (Double, Double) -> Boolean): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        val zoom = if (screenPerDoc.isFinite() && screenPerDoc > 0.0) screenPerDoc else 1.0
        val order = picker.rank(pickable(lines), x, y, zoom)
        val cur = current
        if (cur is Lines && cur.ids.size == 1 && cur.pick != null && !adding && order.size > 1) {
            val reach = CYCLE_REACH_SCREEN_PX / zoom
            val dx = x - cur.pick.x; val dy = y - cur.pick.y
            if (dx * dx + dy * dy <= reach * reach) {
                val at = order.indexOf(cur.ids.first())
                val next = order[(at + 1).mod(order.size)]
                current = Lines(withGroups(listOf(next), lines), null, null, Pick(cur.pick.x, cur.pick.y))
                return true
            }
        }
        if (order.isNotEmpty()) {
            val id = order[0]
            if (adding && cur is Lines) {
                val unit = withGroups(listOf(id), lines)
                val ids = if (id in cur.ids) cur.ids - unit else cur.ids + unit
                current = if (ids.isEmpty()) null else Lines(ids, null, null, null)
                return true
            }
            val was = current
            current = Lines(withGroups(listOf(id), lines), null, null, Pick(x, y))
            return was != current
        }
        if (cur == null || insideBox(x, y)) return false
        current = null
        return true
    }

    /**
     * One line chosen some other way than a tap: the finger crosshair, the fan, or a step of the count scrubber (K11).
     * The owner's K12: with [adding], it is added (or taken back out), exactly as a tap would be; without, it is the whole
     * new selection. [at] is where the pick happened, so a later tap on that spot keeps cycling from it.
     */
    fun choose(id: String, adding: Boolean, at: Pick?, lines: List<InkLine> = emptyList()) {
        if (!pickLocked && items.locked(id)) return
        val unit = withGroups(listOf(id), lines)
        val cur = current
        if (adding && cur is Lines) {
            val ids = if (id in cur.ids) cur.ids - unit else cur.ids + unit
            current = if (ids.isEmpty()) null else Lines(ids, null, null, null)
            return
        }
        current = Lines(unit, null, null, at)
    }

    /** The additive count browses one unit while keeping the other selected units unchanged. */
    fun countBaseWithout(id: String, lines: List<InkLine>): Set<String> =
        ((current as? Lines)?.ids ?: emptySet()) - withGroups(listOf(id), lines)

    /** Atomically choose against the count gesture's fixed base. Picking never writes drawing/history. */
    fun chooseFromBase(id: String, base: Set<String>, at: Pick, lines: List<InkLine>): Boolean {
        if (lines.none { it.id == id } || (!pickLocked && items.locked(id))) return false
        current = Lines(base.toSet() + withGroups(listOf(id), lines), null, null, at)
        return true
    }

    /**
     * A finished loop (D8, D9). [here] is the active layer's lines, [everywhere] every visible layer's (for the
     * All layers chip). With [adding] and a line selection live, the loop's lines are added, or removed when every one of
     * them is already selected (Concepts' "lasso again to subtract", which here cannot mean "drag inside the box": that
     * moves it). Fewer than three points selects nothing and changes nothing.
     */
    fun loop(loop: List<Pt>, here: List<InkLine>, everywhere: List<InkLine>, reading: Reading, adding: Boolean): Boolean {
        if (loop.size < 3) return false
        val cur = current
        if (adding && cur is Lines) {
            val scope = if (reading.allLayers) everywhere else here
            val taken = withGroups(LineLasso.take(pickable(scope), loop, reading.reach), scope).toList()
            if (taken.isEmpty()) return false
            val ids = if (taken.all { it in cur.ids }) cur.ids - taken.toSet() else cur.ids + taken
            current = if (ids.isEmpty()) null else Lines(ids, null, null, null)
            return true
        }
        current = read(loop, here, everywhere, reading)
        return true
    }

    /**
     * The chips changed (D9, owner's K13): the SAME loop is read again. Returns a sentence, and changes nothing, when the
     * reading cannot apply; null when it applied. A selection without a loop cannot be re-read and is left alone.
     *
     * K13, "line filters imply lines": the Select tool passes [Take.LINES] whenever the owner taps Lines, Whole, Touching
     * or All layers. Asked for lines and none is enclosed whole, this falls back to the lines the loop touches (and the
     * resulting [Lines.reading] says Touching, so the chip follows), "because that's what I was meaning anyway" (owner).
     * Only a loop that touches no line at all stays on paint, with [NO_LINES].
     */
    fun reread(reading: Reading, here: List<InkLine>, everywhere: List<InkLine>): String? {
        val cur = current
        val loop = when (cur) { is Lines -> cur.loop; is Pixels -> cur.loop; null -> null } ?: return null
        var next = read(loop, here, everywhere, reading)
        if (next is Pixels && reading.take == Take.LINES) {
            if (reading.reach == LineLasso.Reach.WHOLE) next = read(loop, here, everywhere, reading.copy(reach = LineLasso.Reach.TOUCHING))
            if (next is Pixels) return NO_LINES
        }
        if (next is Pixels && reading.allLayers) return PAINT_ONE_LAYER
        current = next
        return null
    }

    /** After the box moved the selection through [h], the kept loop moves with it, so a later re-read reads where it is. */
    fun moved(h: Homography) {
        val cur = current ?: return
        fun carry(loop: List<Pt>): List<Pt>? = loop.map { h.apply(it) }.takeIf { m -> m.all { it.x.isFinite() && it.y.isFinite() } }
        current = when (cur) {
            is Lines -> cur.copy(loop = cur.loop?.let { carry(it) }, pick = null)
            is Pixels -> carry(cur.loop)?.let { cur.copy(loop = it) } ?: cur
        }
    }

    private fun read(loop: List<Pt>, here: List<InkLine>, everywhere: List<InkLine>, reading: Reading): Selection {
        val scope = if (reading.allLayers) everywhere else here
        val lines = withGroups(LineLasso.take(pickable(scope), loop, reading.reach), scope).toList()
        if (reading.take != Take.PIXELS && lines.isNotEmpty()) return Lines(lines.toSet(), loop, reading, null)
        return Pixels(loop, reading, lines.size)
    }

    companion object {
        /** Two taps this close, in screen px, are "the same spot" (JB-5.02's reach, without its time window). */
        const val CYCLE_REACH_SCREEN_PX = 6.0

        const val PAINT_ONE_LAYER = "Paint can be moved on one layer at a time."

        const val NO_LINES = "This loop doesn't touch any lines."
    }
}
