package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.stroke.StrokeEdit
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.math.max
import kotlin.math.min

/** How far a reshape grab reaches, in SCREEN px, converted once to doc px by the caller. */
const val RESHAPE_GRAB_SCREEN_PX = 24.0

/**
 * One finished edit, as the undo stack holds it: every record this gesture replaced, and what it was
 * before. [before] is empty for a deletion; a restored stroke comes back with the id it had.
 *
 * `StrokeRecord`s and nothing else. JB-0.01's review established that the engine's undo stack holds
 * tile changes and **never a [StrokeRecord]**, so an ink edit needs an undo of its own and this is
 * what it holds.
 */
data class InkEditStep(val celId: String, val before: Map<String, StrokeRecord>)

/**
 * What a gesture in an ink edit session did. Three answers and no fourth, because there are only
 * three things that can happen: something changed, nothing happened, or the gesture was refused in
 * words a person can read.
 */
sealed interface InkEditResult {
    /** Nothing changed and nothing is wrong: the drag was inside the slop, or the gesture was cancelled. */
    data object NoChange : InkEditResult

    /** Records changed, one undo step. */
    data class Changed(val celId: String, val records: Map<String, StrokeRecord>) : InkEditResult

    /** Refused, with a sentence a screen can show. Nothing changed. */
    data class Refused(val reason: String) : InkEditResult
}

/**
 * JB-5.03 — the SESSION that turns a finger into [StrokeEdit]'s four pure functions.
 *
 * R20, verbatim: *"Every vector brush is swappable after drawing. On an INK layer a stroke is its
 * recording, so selecting one or MANY strokes and picking another pen redraws them as if drawn with
 * that pen."* [StrokeEdit] already knows how to re-shape, re-weight, re-brush and re-colour ONE
 * record. This is everything around that: what is selected, what a drag does, how much of a gesture
 * one undo step covers, and what happens when the brush someone picked cannot draw an ink line.
 *
 * It is a pure state machine — no GL, no view, no [android.view.MotionEvent], no frame, no clock of
 * its own — so every rule below is a test rather than a manual check. R37 Q4: drawing the halo and
 * routing the finger to these calls is the Lead's, with JB-5.01b.
 *
 * ## THE ORDER OF THE ANSWERS, which is one decision and not four
 *
 * Every entry point checks its ARGUMENT first and its selection second. A coordinate that is not a
 * number is refused even with nothing selected, and a selection that is empty is `NoChange` even with
 * a perfectly good brush. The reason is [InkEditResult]'s own promise: the session never reports a
 * change it did not make and never swallows a bad argument, and "nothing was selected" is never the
 * more useful thing to say than "that corner is not a place".
 *
 * ## UNITS
 *
 * Every coordinate here is [Double], including the drag's `dx`/`dy` (R37: the old contract's
 * `dy: Float` was a typo and is gone). That matches [StrokePicker.pick], [EraserPath], [InkLine] and
 * every other type in this package, so one unit and one type run through `vector`. [StrokeEdit.reshape]
 * takes [Float] and is landed and owned by JB-5.03a, so the narrowing happens ONCE, in [drag], on the
 * new side of that boundary. `beginDrag` narrows the grab point and works out the radius once for the
 * whole gesture, so the cast is not per frame either.
 */
class InkEditSession(
    /** Every record in the cel, keyed by id, in drawing order (last drawn = on top). */
    initial: List<StrokeRecord>,
    /** The cel being edited, carried on every [InkEditResult.Changed] and every [InkEditStep]. */
    val celId: String,
) {

    /**
     * The cel's records in DRAWING ORDER. A [LinkedHashMap] rather than a bare map because order is
     * part of the contract — the last line drawn is the one on top — and a re-write of an existing
     * key keeps its place, so editing a line never re-orders the cel.
     *
     * A repeated id is the later record at the earlier one's position. Nothing in this class adds,
     * removes or renames a record (JB-5.11 deletes lines; this session only ever edits one), so the
     * key set is fixed at construction and [order] can never go stale.
     */
    private val records: LinkedHashMap<String, StrokeRecord> = LinkedHashMap()

    /** The same ids, in the same order, for selection bookkeeping. See [records]. */
    private val order: List<String>

    init {
        for (record in initial) records[record.id] = record
        order = records.keys.toList()
    }

    /** What is selected now, in drawing order. Empty = nothing. A copy: the session owns the list. */
    val selection: List<String> get() = ArrayList(chosen)

    /** The undo stack, oldest first. Capped at [MAX_UNDO_STEPS]; the oldest steps are dropped. */
    val undo: List<InkEditStep> get() = ArrayList(steps)

    private val chosen = ArrayList<String>()
    private val steps = ArrayList<InkEditStep>()

    /**
     * One picker for the whole session, because [StrokePicker] HOLDS the last pick: it is what makes
     * two quick taps in the same place a double-tap that cycles through a pile-up. A picker built
     * per call would answer every tap with the same first candidate and the cycling would never
     * happen. Its state belongs to one UI gesture stream, which is what a session is.
     */
    private val picker = StrokePicker()

    /** The drag in progress, or null. Only one gesture can reshape a cel at a time. */
    private var drag: OpenDrag? = null

    // ── what the picker and the view see ──────────────────────────────────────────────────────

    /**
     * The lines the picker sees. One [InkLine] per record that can be replayed, in drawing order.
     *
     * `InkLine(id, xs = dabs.map { it.x }, ys = dabs.map { it.y }, halfWidths = dabs.map
     * { it.radius })` — one point per dab, in stroke order, the half-width being that dab's own
     * radius. It is asked of the records as they are RIGHT NOW, so a line reshaped this frame is
     * this frame's line and not the one it was when the finger went down.
     *
     * **A record that cannot be replayed is simply not a line.** A missing brush, or a `smudge`
     * brush, is asked about through [InkReplay.refusal] — the sentence-throwing-free half of
     * [InkReplay.dabs] — and skipped. It is not an exception here: a document holding a stroke whose
     * brush was deleted must still be editable everywhere else, and [InkReplay.refusal] is how the
     * UI says why on a different screen.
     *
     * A recording that replays to NO dabs is not a line either, and that is a real limitation rather
     * than an oversight: a `fill` record's dabs are empty by JB-5.01's Decision 12 (a fill is a
     * shape, and its raster is [InkRaster.fill]'s business), and [InkLine] requires at least one
     * point. So **a `fill` recording cannot be re-shaped by this session** — see this row's Q1 in
     * the spec. The alternative, inventing a centreline for a shape, is a ruling about what a fill
     * stroke IS, and it is not this file's to make.
     */
    fun lines(brushOf: (StrokeRecord) -> BrushPreset?): List<InkLine> {
        val out = ArrayList<InkLine>(records.size)
        for (record in records.values) {
            val brush = brushOf(record)
            if (InkReplay.refusal(record, brush) != null) continue
            val dabs = InkReplay.dabs(record, brush)
            if (dabs.isEmpty()) continue
            out.add(
                InkLine(
                    id = record.id,
                    xs = DoubleArray(dabs.size) { dabs[it].x.toDouble() },
                    ys = DoubleArray(dabs.size) { dabs[it].y.toDouble() },
                    halfWidths = DoubleArray(dabs.size) { dabs[it].radius.toDouble() },
                ),
            )
        }
        return out
    }

    // ── choosing ─────────────────────────────────────────────────────────────────────────────

    /**
     * A tap: pick the line under ([x], [y]) doc px and select it alone (Decision 2).
     *
     * The pick itself is [StrokePicker.pick]'s, with every one of its rules — the ribbon rather than
     * the centreline, the 12 screen px of slop, the cluster ranking and the double-tap cycle — and
     * the result REPLACES the selection. A tap that adds to a selection could never take anything
     * away again, which is the reason Concepts' two gestures are two gestures.
     *
     * A tap that means nothing empties the selection and is [InkEditResult.NoChange]: that is the
     * half of "replaces" the "a tap selects one" assertion alone does not reach, and a finger on
     * empty paper is the obvious way to deselect.
     *
     * @return [InkEditResult.Changed] with the new selection, [InkEditResult.NoChange] when the tap
     *   means nothing, or [InkEditResult.Refused] for a coordinate that is not a number.
     */
    fun tap(
        x: Double,
        y: Double,
        timeMs: Double,
        screenPerDoc: Double,
        brushOf: (StrokeRecord) -> BrushPreset?,
    ): InkEditResult {
        if (!x.isFinite() || !y.isFinite()) return InkEditResult.Refused(TAP_NOT_A_PLACE)
        val picked = picker.pick(lines(brushOf), x, y, timeMs, screenPerDoc) ?: run {
            chosen.clear()
            return InkEditResult.NoChange
        }
        chosen.clear()
        chosen.add(picked)
        return InkEditResult.Changed(celId, snapshot())
    }

    /**
     * A rubber band around ([x0], [y0])-([x1], [y1]) doc px: every line it touches JOINS the
     * selection. It adds; only [tap] replaces.
     *
     * The rectangle is axis-aligned in DOC px and the corners arrive in whatever order the finger
     * drew them, so it is normalised here. A line joins when **any of its dab centres** is inside
     * it — tested on the dabs [InkReplay] produces, not on the line's bounding box and not on the
     * edges of its ribbon. The bounding box would catch a long diagonal that passes nowhere near
     * the band; the ribbon's edge would catch a line whose ink stops a pixel short of it, and a
     * band is a coarse gesture that should not demand a pixel.
     *
     * A corner that is not a finite number is [InkEditResult.Refused] before any arithmetic runs: a
     * `NaN` in a containment test is how a selection silently empties itself. An EMPTY band
     * ([x0] == [x1] and [y0] == [y1]) contains nothing, so nothing joins and it is
     * [InkEditResult.NoChange] rather than an error.
     */
    fun band(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        brushOf: (StrokeRecord) -> BrushPreset?,
    ): InkEditResult {
        if (!x0.isFinite() || !y0.isFinite() || !x1.isFinite() || !y1.isFinite()) {
            return InkEditResult.Refused(BAND_NOT_A_RECTANGLE)
        }
        val left = min(x0, x1)
        val right = max(x0, x1)
        val top = min(y0, y1)
        val bottom = max(y0, y1)
        var joined = 0
        for (line in lines(brushOf)) {
            if (!anyDabInside(line, left, top, right, bottom)) continue
            if (!chosen.contains(line.id)) {
                chosen.add(line.id)
                joined++
            }
        }
        if (joined == 0) return InkEditResult.NoChange
        // The selection is promised in DRAWING order, so adding to it re-sorts it. `indexOf` never
        // returns -1 for an id this session holds, but a stale id would sort last rather than throw.
        chosen.sortBy { order.indexOf(it) }
        return InkEditResult.Changed(celId, snapshot())
    }

    /** Whether any dab centre of [line] is inside the closed rectangle. */
    private fun anyDabInside(line: InkLine, left: Double, top: Double, right: Double, bottom: Double): Boolean {
        for (i in 0 until line.pointCount) {
            val x = line.xs[i]
            val y = line.ys[i]
            if (x >= left && x <= right && y >= top && y <= bottom) return true
        }
        return false
    }

    /** Empty the selection. Never a change to the drawing, so never an undo step. */
    fun clearSelection() {
        chosen.clear()
    }

    // ── the drag ────────────────────────────────────────────────────────────────────────────

    /**
     * Drag begins: remembers the pre-drag records of everything selected, and fixes THE grab point
     * (Decision 4) and the reach (Decision 5).
     *
     * The reach is [RESHAPE_GRAB_SCREEN_PX] **screen** px divided by the zoom once, and held for the
     * whole gesture. Screen px for the same reason [StrokePicker]'s tolerances are: 24 px is a
     * finger, not a document fact, and it must be the same finger-feel at every zoom.
     *
     * [grabX] and [grabY] are narrowed to [Float] HERE, once, rather than on every frame of
     * [drag] — which is the whole of where Decision 3's cast lives.
     *
     * @return [InkEditResult.Changed] with the current records so the caller can re-render the halo,
     *   or [InkEditResult.NoChange] when nothing is selected — a drag with no selection is not a
     *   refusal, it is a drag that cannot reshape anything, and the caller is told so by a result it
     *   can ignore.
     * @throws IllegalArgumentException if [screenPerDoc] is not a positive finite zoom. That is a
     *   caller bug with no sensible fallback inside a gesture, and it is checked here so it cannot
     *   reach `radius` as `NaN` and silently reshape nothing forever. ([StrokePicker] reads a bad
     *   zoom as 1:1 because a *tolerance* still has a usable answer at 1:1; a *division* does not.)
     */
    fun beginDrag(grabX: Double, grabY: Double, screenPerDoc: Double): InkEditResult {
        require(screenPerDoc.isFinite() && screenPerDoc > 0.0) {
            "beginDrag needs a positive finite zoom to turn $RESHAPE_GRAB_SCREEN_PX screen px into " +
                "doc px, and was given $screenPerDoc"
        }
        if (chosen.isEmpty()) return InkEditResult.NoChange
        drag = OpenDrag(
            before = snapshotOf(chosen),
            grabX = grabX.toFloat(),
            grabY = grabY.toFloat(),
            radius = (RESHAPE_GRAB_SCREEN_PX / screenPerDoc).toFloat(),
        )
        return InkEditResult.Changed(celId, snapshot())
    }

    /**
     * Drag moves by ([dx], [dy]) doc px. Reshapes about THE grab point fixed by [beginDrag] and about
     * nothing else, so every selected line follows the finger together.
     *
     * One grab point for the whole selection is R37's ruling and it is what makes ten lines move as
     * one object rather than in ten directions at once. It is applied to the records **as they
     * now are**, so successive calls accumulate into one bend rather than each re-deriving from the
     * snapshot: the snapshot is for undo and for cancel, and nothing else.
     *
     * [dx] and [dy] are [Double] because every other coordinate in this class is; they are narrowed
     * to [Float] at the [StrokeEdit.reshape] call, and that is the only cast in the file (Decision 3).
     *
     * @return [InkEditResult.Changed] with the reshaped records, or [InkEditResult.NoChange] when
     *   there is no drag in progress, or when the offset is zero. A non-finite `dx`/`dy` is
     *   [InkEditResult.NoChange]: [StrokeEdit.reshape] returns the record untouched for one, and the
     *   session must not report a change it did not make.
     */
    fun drag(dx: Double, dy: Double): InkEditResult {
        val open = drag ?: return InkEditResult.NoChange
        if (!dx.isFinite() || !dy.isFinite()) return InkEditResult.NoChange
        // A zero offset is the same answer for the same reason: `reshape` would hand back an equal
        // record, so [InkEditResult.Changed] would be a re-render request for a drawing that did
        // not move. [endDrag] would still commit nothing — it compares the records — but a caller
        // that repaints on every [InkEditResult.Changed] should not be woken for this.
        if (dx == 0.0 && dy == 0.0) return InkEditResult.NoChange
        val fx = dx.toFloat()
        val fy = dy.toFloat()
        for (id in chosen) {
            val record = records[id] ?: continue
            records[id] = StrokeEdit.reshape(record, open.grabX, open.grabY, fx, fy, open.radius)
        }
        return InkEditResult.Changed(celId, snapshot())
    }

    /**
     * Drag ends: commits ONE undo step for the whole gesture (Decision 6).
     *
     * The step holds the snapshot [beginDrag] took, compared record by record, so a drag that moved
     * nothing commits nothing. Re-rendering during the drag is the caller's business; the session is
     * told nothing about frames.
     */
    fun endDrag(): InkEditResult {
        val open = drag ?: return InkEditResult.NoChange
        drag = null
        for ((id, was) in open.before) {
            if (records[id] != was) return commit(open.before)
        }
        return InkEditResult.NoChange
    }

    /**
     * The finger or pen went down a second time: the whole drag is undone and the session forgets
     * it (Decision 7).
     *
     * The SNAPSHOT goes back, not the last frame — a frame is what the fingers had reached, and a
     * cancel has to leave nothing of the gesture behind. The grab is forgotten too, so an [endDrag]
     * afterwards pushes nothing and a further [drag] does nothing: this is the only way to leave a
     * reshape, and it has to be total or a second finger would leave a half-committed edit behind.
     */
    fun cancelDrag(): InkEditResult {
        val open = drag ?: return InkEditResult.NoChange
        drag = null
        for ((id, was) in open.before) records[id] = was
        return InkEditResult.Changed(celId, snapshot())
    }

    // ── the verbs ────────────────────────────────────────────────────────────────────────────

    /**
     * Multiply every selected line's `widthScale` by [factor]. One undo step for the whole
     * selection (Decision 8).
     *
     * The factor goes straight through and [StrokeEdit.reweight] does the clamping
     * ([StrokeEdit.MIN_WIDTH_SCALE]..[StrokeEdit.MAX_WIDTH_SCALE]) and the broken-number handling —
     * this class does not own a second copy of either. A [factor] that is not a finite number is
     * [InkEditResult.Refused] rather than a silent no-op, because [StrokeEdit.reweight] would hand
     * back the same records and the session must not report a change it did not make.
     */
    fun reweight(factor: Float): InkEditResult {
        if (!factor.isFinite()) return InkEditResult.Refused("a re-weight factor of $factor is not a number")
        if (chosen.isEmpty()) return InkEditResult.NoChange
        return editAll { StrokeEdit.reweight(it, factor) }
    }

    /**
     * Re-draw every selected line with [brush]. ONE undo step for all of them (R20).
     *
     * The refusal is [rebrushRefusal]'s, verbatim, and it is TOTAL: a refused brush leaves the
     * drawing byte-identical and the selection unchanged. Nothing is applied and then checked — the
     * check is first, so there is no window in which a `smudge` has painted half a selection.
     *
     * The refusal is asked before the selection is, deliberately. It is about the BRUSH, not about
     * what happens to be selected: a session with nothing selected that is handed a `smudge` still
     * has to be able to say the brush cannot draw an ink line, or a UI that forgot to ask at pick
     * time would read [InkEditResult.NoChange] as permission.
     */
    fun rebrush(brush: BrushPreset): InkEditResult {
        val refusal = rebrushRefusal(brush)
        if (refusal != null) return InkEditResult.Refused(refusal)
        if (chosen.isEmpty()) return InkEditResult.NoChange
        return editAll { StrokeEdit.rebrush(it, brush.id) }
    }

    /**
     * Recolour every selected line. One undo step, and only the colour changes: [StrokeEdit.recolor]
     * is a `copy(colorArgb = …)` and the session adds nothing to it.
     */
    fun recolor(argb: Int): InkEditResult {
        if (chosen.isEmpty()) return InkEditResult.NoChange
        return editAll { StrokeEdit.recolor(it, argb) }
    }

    // ── the one place a verb is applied ──────────────────────────────────────────────────────

    /**
     * Apply [transform] to every selected record, as ONE undo step.
     *
     * The snapshot is taken and committed here rather than at each call site so that "one step for
     * the whole selection" is a property of this function and not of four call sites each getting
     * it right. An edit that changed a record's *content* always commits, even when the result
     * happens to equal what was there — Decision 6's "moved nothing commits nothing" is a rule about
     * a drag, which is the one verb with a continuous gesture and a cancel, and widening it to the
     * others would make a no-op verb quietly swallow an undo step.
     */
    private inline fun editAll(transform: (StrokeRecord) -> StrokeRecord): InkEditResult {
        val before = snapshotOf(chosen)
        for ((id, was) in before) records[id] = transform(was)
        return commit(before)
    }

    /** Push one step, dropping the oldest past [MAX_UNDO_STEPS], and report the change. */
    private fun commit(before: Map<String, StrokeRecord>): InkEditResult {
        steps.add(InkEditStep(celId, LinkedHashMap(before)))
        while (steps.size > MAX_UNDO_STEPS) steps.removeAt(0)
        return InkEditResult.Changed(celId, snapshot())
    }

    /** The whole cel, as a copy: an [InkEditResult.Changed] may not be used to edit it. */
    private fun snapshot(): Map<String, StrokeRecord> = LinkedHashMap(records)

    /** The records named by [ids], in drawing order, as a copy. */
    private fun snapshotOf(ids: List<String>): LinkedHashMap<String, StrokeRecord> {
        val out = LinkedHashMap<String, StrokeRecord>(ids.size)
        for (id in ids) {
            val record = records[id] ?: continue
            out[id] = record
        }
        return out
    }

    /**
     * A drag in progress: what every selected record was when the finger went down, the ONE grab
     * point, and the reach in doc px. Private and nested, so its name cannot be anything else in
     * this package.
     */
    private class OpenDrag(
        val before: Map<String, StrokeRecord>,
        val grabX: Float,
        val grabY: Float,
        val radius: Float,
    )

    companion object {
        /**
         * RULED (R37, Q3): 50 steps.
         *
         * A STEP COUNT rather than a byte budget, because `UndoLog` budgets in bytes with a
         * `release` hook and an ink record is pure value with nothing to release — there is no
         * resource to free, so a byte budget would be a number with no mechanism behind it. Fifty is
         * generous for a gesture session measured in minutes, and it is one line if the owner ever
         * wants it different.
         */
        const val MAX_UNDO_STEPS = 50

        /** A tap needs a place. See [tap]. */
        private const val TAP_NOT_A_PLACE = "a tap needs a real place on the page"

        /** A band needs four corners. See [band]. */
        private const val BAND_NOT_A_RECTANGLE = "a rubber band needs four real corners"

        /**
         * Why [brush] cannot re-brush an ink line, in words, or `null` when it can.
         *
         * RULED (R37, Q1): the brush picker asks THIS, at pick time, and greys the brush out with
         * the sentence. It is a companion function precisely so the picker can ask it without
         * building a session and without a selection — the UI's job is to grey a brush, and it must
         * be able to do that before anything is selected.
         *
         * The decision is [StrokeEdit.drawsInkLines], not a list of engine names written here: it is
         * the one place that says WHICH engines may, so a fifth engine is a change in one file rather
         * than a change in a list written twice.
         *
         * The sentence names the brush, so a person is not left looking at a greyed row with no
         * explanation, and it is the ONLY copy of these words in the project: the picker and
         * [rebrush] both call this, which is the only way two doors can say one thing.
         */
        fun rebrushRefusal(brush: BrushPreset): String? = when {
            StrokeEdit.drawsInkLines(brush.engine) -> null
            else ->
                "${brush.name} reads the pixels under the pen, and an ink layer has none — it " +
                    "holds the strokes. Try Ink, Pencil or the Fill pen."
        }
    }
}
