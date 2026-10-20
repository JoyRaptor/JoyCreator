package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer
import java.util.UUID

/**
 * What ONE stroke's records become in ONE plane: the lines they make, and the tiles those lines may
 * draw on. Pure bookkeeping (no GL, no engine state): a plan is made, and the engine applies it.
 *
 * ## Why this is its own file
 *
 * The rules that make the copies of a symmetry stroke ONE thing to Select live here, and they are
 * rules about ids, sequence numbers and one shared group - none of which needs a GPU:
 *
 *  - **One line per record.** A stroke with N lanes hands over N records, and each becomes its own
 *    line: its own id, its own place in the plane's time order, and the samples it was drawn with.
 *  - **One group for the set** ([groupFor]). Every line of the set carries the SAME `groupId`, so
 *    selecting one copy selects them all, and a recolour, a re-brush or a delete reaches the whole
 *    set.
 *  - **One id each.** An id the plane already holds is not reused: the id gets a suffix, as it always
 *    did. Two lines of one set never share an id, so one of them can never be lost in the other's
 *    place.
 *  - **Ascending sequence numbers.** The lines of a set are laid down in the order they were made, so
 *    the plane's time order says which copy is on top of which.
 *
 * The plan is made for ONE plane. A stroke that crossed a frame's edge is planned once per plane it
 * wrote, and the group is the same in all of them: a stroke split across planes is still ONE set.
 */
internal object LineCommit {

    /**
     * One line a record became: the change the step carries, and the tiles the line may draw on.
     *
     * @property change the line's change: it did not exist before, and it is this record now.
     * @property footprint the tiles the line may draw on: the brush's own reach, plus the tiles the
     *   stroke wrote in this plane.
     */
    class Made(val change: UndoLog.LineChange, val footprint: Set<Long>)

    /**
     * The ONE group the set's lines share, or null when the set is one line with no group of its own.
     *
     * - **One line, one plane, no group of its own:** null. The ordinary stroke is unchanged, and a
     *   stroke that was never grouped does not become one by being drawn.
     * - **One line that crossed planes:** a group of its own (H11). A stroke split across a frame's
     *   edge is ONE thing to Select.
     * - **Many lines - the copies of a symmetry stroke:** ONE group for all of them, so Select takes
     *   the whole set, a recolour reaches every copy, and one undo takes them all back.
     *
     * A record that already carries a group keeps it: the group it was made in is the group it
     * belongs to.
     */
    fun groupFor(records: List<StrokeRecord>, planesWritten: Int): String? {
        if (records.isEmpty()) return null
        records.firstOrNull()?.groupId?.let { return it }
        if (records.size > 1 || planesWritten > 1) return "g-" + UUID.randomUUID()
        return null
    }

    /**
     * The lines [records] make in [plane].
     *
     * @param brushes the stroke's frozen brushes: the brush a record names, by its id.
     * @param celId the cel the changes name (the plane's own cel may be the shared one).
     * @param group the group every line of the set carries, or null when the set is one line with no
     *   group of its own.
     * @param written the tiles the stroke wrote in this plane: every line of the set crosses them.
     */
    fun plan(
        nl: NativeLines,
        brushes: Map<String, BrushPreset>,
        records: List<StrokeRecord>,
        plane: LinePlane,
        celId: String?,
        group: String?,
        written: List<Long>,
    ): List<Made> {
        if (records.isEmpty()) return emptyList()
        val out = ArrayList<Made>(records.size)
        val taken = HashSet<String>()
        // The plane's next place in time. Every line of the set takes the next one, so the set's
        // lines are laid down in the order they were made.
        var seq = nl.nextSeq(plane)
        for (source in records) {
            var id = source.id
            while (id in taken || nl.record(plane, id) != null) id = source.id + "-" + (1..99999).random()
            taken.add(id)
            val record = source.copy(id = id, seq = seq++, groupId = group)
            val footprint = nl.estimate(record, brushes[record.brushId]) + written
            out += Made(
                UndoLog.LineChange(plane.layerId, celId, id, null, CelComposer.Line(record.seq, record)),
                footprint,
            )
        }
        return out
    }
}
