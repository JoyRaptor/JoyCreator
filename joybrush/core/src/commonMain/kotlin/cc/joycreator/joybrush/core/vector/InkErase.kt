package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.math.hypot
import kotlin.math.min

/**
 * JB-5.11 — which eraser is in the person's hand right now. A tool setting; never stored in a
 * document.
 *
 * The three constants are [EraseMode]'s, named separately and mapped by an exhaustive `when` in
 * [InkErase], so a fourth constant in EITHER enum is a compile error rather than a silent
 * fall-through. Not serialised: nothing writes these names to a file, so this needs no version bump
 * (LEAD_RULINGS R3 covers serialised enums; `MaskPaintMode` is the precedent).
 */
enum class InkEraseMode { PARTIAL, WHOLE_STROKE, TO_INTERSECTION }

/**
 * A line the eraser could NOT erase, and why, in one sentence a screen can show.
 *
 * The gesture still happened: every other line it touched was erased, and this one was left exactly
 * as it was found. Two of the three reasons are [InkReplay.refusal]'s own words, so the sentence a
 * person reads and the sentence a test catches are the same string.
 */
data class NotReplayed(
    val strokeId: String,
    val brushId: String,
    val reason: String,
)

/** What the eraser did to an ink cel. */
sealed interface InkEraseOutcome {
    /**
     * The eraser touched nothing: no line came near it. Nothing changed and nothing is wrong.
     *
     * Returned for exactly four reasons and no others — an empty record list, an [EraserPath] with
     * no samples, a radius that is zero or is not a number, and an eraser that came near no line it
     * could measure. The last is a judgement and not a measurement: a record the eraser cannot
     * replay has no geometry, so "was it under the eraser?" is not a question this file can answer
     * (see the spec's Questions). The rule it answers to is the one a person can predict: if
     * nothing at all was cut, there is nothing to report.
     */
    data object Untouched : InkEraseOutcome

    /**
     * The gesture happened. [records] is the whole surviving cel, in drawing order — the records
     * that were NOT touched are in it unchanged, so a caller replaces the cel with this list and
     * needs no second source of truth.
     *
     * [before] holds **only** the ids whose record is absent from [records] or differs from it, so
     * one undo step restores exactly those and nothing else. It is a [LinkedHashMap] and it
     * ITERATES IN THE CEL'S OWN ORDER, which is what a caller needs for a record that left no
     * survivor behind to hold its place.
     *
     * [notReplayed] is empty in the ordinary case. When it is not, it is the report: a person who
     * rubbed over three lines and one of them could not be measured gets three lines erased and one
     * sentence, not a refusal and an untouched drawing.
     */
    data class Erased(
        val celId: String,
        val records: List<StrokeRecord>,
        val before: Map<String, StrokeRecord>,
        val notReplayed: List<NotReplayed>,
    ) : InkEraseOutcome
}

/**
 * The ink eraser: the round trip from a gesture back to recordings.
 *
 * JB-5.10's [VectorEraser] says which stretches of which line survive, in fractional indices along
 * the *replayed line* — and a replayed line is a list of [Dab]s, which is not a recording. This is
 * the step that turns those stretches back into [StrokeRecord]s, so an erased line is still a
 * recording: the same `PenSample`s, the same `seed`, the same `widthScale` and `colorArgb`, and
 * therefore still re-brushable and still re-smoothable (LEAD_RULINGS R20).
 *
 * ## THE TWO IDIOMS IT INHERITS, AND HONOURS EXACTLY
 *
 * [VectorEraser]'s two decisions are load-bearing and are not restated here:
 *  - **an absent id means untouched**, and  - **a present id with an empty list means the line is
 *  gone.** "An empty result means no change" would silently lose a whole line, and the two being
 *  different is what stops it.
 *
 * ## THE SPECK RULE IS NOT OURS
 *
 * [VectorEraser] drops survivor pieces thinner than its own noise floor, once, for all three modes.
 * It is already there, already run, and there is deliberately **no copy of that threshold in this
 * file**: a second rule about the same geometry is a second thing to drift. The only size filter
 * here is [MIN_SURVIVOR_SAMPLES], which is in SAMPLES and not in doc px, and it is not a substitute
 * for the other one (see that constant).
 *
 * ## WHAT THIS FILE DOES NOT KNOW
 *
 * Not layers, not pixels, not GL, not the undo stack. The PAINT half of the context-aware eraser
 * already exists — a `blend = "erase"` stroke through the paint engine — and choosing between the
 * two is the caller's one-line switch on `Layer.kind`.
 */
object InkErase {

    /**
     * Fewest samples a survivor may hold and still be a line. In SAMPLES, not in doc px.
     *
     * One sample is a dab: [InkLine] legally allows a line of one point, but a *recording* of one
     * sample re-brushes into a single dab and nothing else, and `DabPlacer` needs two points before
     * it has a segment at all. A piece holding fewer than this is dropped and produces no record.
     *
     * This is a different filter from [VectorEraser]'s, and neither does the other's job: a sliver
     * of 0.4 doc px on a line sampled every 0.1 doc px holds five samples and passes this one, and
     * is dropped by the arc-length rule before this file ever sees it.
     *
     * WHY IT LOOKS UNREACHABLE, AND THE PROOF. [survivorSamples] takes the first sample at or
     * before the piece's start and the last at or after its end, over a non-decreasing array of
     * sample arc lengths. Call them `i0` and `i1`, and the two lengths `L0 < L1`. By construction
     * `sampleArc[i0] <= L0 < L1 <= sampleArc[i1]`, so `i0 < i1` and every survivor holds at least
     * two samples. The floor is therefore a guard on a property the bracketing already guarantees,
     * not dead weight to be removed: it is what says so in code, and it is what would keep the
     * promise if the bracketing were ever changed. `InkEraseTest` pins the guarantee itself, so a
     * bracketing change that could produce a one-sample survivor is caught there.
     */
    private const val MIN_SURVIVOR_SAMPLES = 2

    /**
     * One record as it arrived, and everything this run worked out about it. A private nested type,
     * so its simple name is `InkErase.Candidate` and cannot collide with a top-level name anywhere
     * in the package.
     *
     * Exactly one of [line] and [report] is set. [lineArc] and [sampleArc] are the cumulative
     * arc lengths each point list gives, and are EMPTY for a record that produced no line because
     * there is nothing to accumulate.
     */
    private class Candidate(
        val record: StrokeRecord,
        val line: InkLine?,
        val lineArc: DoubleArray,
        val sampleArc: DoubleArray,
        val report: NotReplayed?,
    )

    /**
     * Erase along [path] from every record in [records], in the cel [celId].
     *
     * Lines come from [InkReplay.dabs] (JB-5.01): an ink line IS its recording, so the eraser
     * measures against the same geometry the layer draws — centreline from the dab positions,
     * half-width from the dab radii. A record that cannot be replayed has no geometry to measure
     * against; it is **left alone and reported**, and every other line is still erased.
     *
     * ONE PASS, over the records as they arrived and never over the survivors this run is building
     * (Decision 9). Running the eraser repeatedly over its own output is the classic eraser-drag
     * bug: a fast drag cuts a staircase. A caller wanting a second pass calls [run] again with the
     * outcome's own `records`.
     *
     * A cel that already holds TWO RECORDS WITH ONE ID is a cel that is already broken - that is
     * JB-5.02's MAJOR, and it is a defect in what was written to the cel rather than in this cut.
     * [VectorEraser]'s answer is keyed by id, so such a pair gets one answer and both records are
     * rebuilt from it. Repairing that is not this row's job; what this file guarantees is the one
     * thing that matters either way, which is that it never CREATES a duplicate: a line cut into N
     * comes back as N records with N different ids.
     *
     * ## THE RADIUS IS THE CALLER'S, AND THIS IS THE ONE SIZE RULE
     *
     * `path.radius` is this brush's `size.base / 2` in DOCUMENT px, because the eraser is the
     * brush with an erase blend and a product with two size rules has neither (LEAD_RULINGS R10,
     * R37 Q2). The consequence is stated rather than hidden: the eraser grows on screen as you zoom
     * in, exactly like a brush. It is one number at one call site to make it screen px instead, and
     * it is one number here precisely because nothing else in this file knows the radius.
     *
     * @return [InkEraseOutcome.Erased] with the whole surviving cel whenever the eraser touched
     *   anything at all, or [InkEraseOutcome.Untouched] when it touched nothing.
     */
    fun run(
        celId: String,
        records: List<StrokeRecord>,
        path: EraserPath,
        mode: InkEraseMode,
        brushOf: (StrokeRecord) -> BrushPreset?,
    ): InkEraseOutcome {
        // A gesture with no samples yet, a half-built path, and a radius that is not a usable
        // number: all of them are real states mid-gesture, and all of them touch nothing. Not an
        // exception — [VectorEraser] is already tolerant of the empty path, and a NaN radius would
        // make it compare against NaN and match nothing, silently. A sentence beats silence, and
        // Untouched is the sentence.
        if (records.isEmpty() || path.xs.isEmpty() || path.ys.isEmpty()) return InkEraseOutcome.Untouched
        if (!path.radius.isFinite() || path.radius <= 0.0) return InkEraseOutcome.Untouched

        val candidates = ArrayList<Candidate>(records.size)
        val lines = ArrayList<InkLine>(records.size)
        for (record in records) {
            val candidate = classify(record, brushOf)
            candidates.add(candidate)
            candidate.line?.let { lines.add(it) }
        }

        val result = VectorEraser.erase(lines, path, eraseModeOf(mode))
        // Nothing came near anything this file can measure, so nothing happened and there is
        // nothing to report — a missing brush somewhere on the cel is not an event.
        if (result.survivors.isEmpty()) return InkEraseOutcome.Untouched

        val out = ArrayList<StrokeRecord>(records.size + 2)
        val notReplayed = ArrayList<NotReplayed>(candidates.size)
        for (candidate in candidates) {
            val record = candidate.record
            candidate.report?.let { notReplayed.add(it) }
            val line = candidate.line
            if (line == null) {
                // Untouched, un-replayable or not a line at all: it comes back exactly as it came in.
                out.add(record)
                continue
            }
            // An ABSENT id was never touched; a PRESENT id with an empty list is a line that is
            // gone. Those two are VectorEraser's own decisions and this branch is where they part.
            val pieces = result.survivors[line.id] ?: run {
                out.add(record)
                continue
            }
            var made = 0
            for (piece in pieces) {
                val samples = survivorSamples(record, candidate.lineArc, candidate.sampleArc, piece)
                if (samples.size < MIN_SURVIVOR_SAMPLES) continue
                made++
                // The first survivor keeps the id, so an undo, a re-render and a timelapse all still
                // mean "that line". The suffix counts from 2 and keeps every other one distinct,
                // which is the whole of it: two ink records sharing an id make a picker's indexOf
                // stop on the first copy for ever, so a fresh uuid is not an option and a counter
                // is. `made` is already 1 for the first, so the suffix is `made` and the ids are
                // `id`, `id~2`, `id~3`.
                val id = if (made == 1) record.id else "${record.id}~$made"
                out.add(record.copy(id = id, samples = samples))
            }
            // `made == 0` adds nothing, and `before` below then carries the record: the line is gone,
            // either because the mode took all of it or because nothing survived.
        }

        val now = HashMap<String, StrokeRecord>(out.size * 2)
        for (record in out) now[record.id] = record
        // Inserted walking `records` in order, so the map ITERATES in the cel's own order. That is
        // load-bearing for a record this run removed ENTIRELY: nothing on the cel marks where it
        // stood, and its relative order among other removed records is the only thing a caller can
        // recover. Its position among the records that were never touched is NOT carried, and
        // cannot be: a stroke erased whole leaves no mark behind it. See the spec's Questions.
        val before = LinkedHashMap<String, StrokeRecord>()
        for (record in records) {
            val survivor = now[record.id]
            if (survivor == null || survivor != record) before[record.id] = record
        }

        return InkEraseOutcome.Erased(celId, out, before, notReplayed)
    }

    /**
     * The line this record is, or the sentence saying why it is not one.
     *
     * Three answers, in this order, and the order matters:
     *  1. [InkReplay.refusal] is not null — no brush, or one whose engine cannot draw an ink line.
     *     Reported with that function's own words, so the sentence exists in one place.
     *  2. the brush is the fill pen — it draws a shape and its raster is `InkRaster.fill`'s
     *     business, so there is no centreline to cut. Reported, because a person will meet this
     *     (draw a closed shape, rub the eraser over it) and silence is the one answer that is
     *     certainly wrong. The sentence is THIS file's, and it is the only sentence in the engine
     *     about erasing a fill, which is why it is named and not inlined.
     *  3. the replay produced no dabs — the recording has no samples, or none of them has a place in
     *     the world. That is not a line at all (JB-5.01), it is not erased, and it is NOT reported:
     *     there is no brush to complain about and nothing the eraser did to it.
     */
    private fun classify(record: StrokeRecord, brushOf: (StrokeRecord) -> BrushPreset?): Candidate {
        val brush = brushOf(record)
        val refusal = InkReplay.refusal(record, brush)
        if (refusal != null) {
            return Candidate(record, null, EMPTY_ARC, EMPTY_ARC, NotReplayed(record.id, record.brushId, refusal))
        }
        if (brush!!.engine == ENGINE_FILL) {
            val reason = "stroke \"${record.id}\" was drawn with brush \"${record.brushId}\", which " +
                "draws a filled shape with no line to cut: the ink eraser cannot erase part of a fill"
            return Candidate(record, null, EMPTY_ARC, EMPTY_ARC, NotReplayed(record.id, record.brushId, reason))
        }
        // The refusal is known to be null, so this cannot throw the refusal it was just asked about.
        val dabs = InkReplay.dabs(record, brush)
        if (dabs.isEmpty()) return Candidate(record, null, EMPTY_ARC, EMPTY_ARC, null)
        return Candidate(
            record,
            lineOf(record.id, dabs),
            lineArcOf(dabs),
            sampleArcOf(record.samples),
            null,
        )
    }

    /** The replayed line itself: centreline from the dabs' positions, half-width from their radii. */
    private fun lineOf(id: String, dabs: List<Dab>): InkLine {
        val n = dabs.size
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        val halfWidths = DoubleArray(n)
        for (i in 0 until n) {
            val dab = dabs[i]
            xs[i] = dab.x.toDouble()
            ys[i] = dab.y.toDouble()
            halfWidths[i] = dab.radius.toDouble()
        }
        return InkLine(id, xs, ys, halfWidths)
    }

    /**
     * Exactly three arms and no `else`, so a constant added to either enum is a compile error here
     * rather than a gesture that quietly does something nobody chose.
     */
    private fun eraseModeOf(mode: InkEraseMode): EraseMode = when (mode) {
        InkEraseMode.PARTIAL -> EraseMode.PARTIAL
        InkEraseMode.WHOLE_STROKE -> EraseMode.WHOLE_STROKE
        InkEraseMode.TO_INTERSECTION -> EraseMode.TO_INTERSECTION
    }

    /**
     * The samples a surviving piece is, and the heart of the whole file.
     *
     * A [Piece] names fractional indices along the replayed line — a list of DABS, many more points
     * than the recording has, at the brush's own spacing. So the piece's endpoints are turned into
     * positions along that line (arc length from its own first point), and the recording's samples
     * are taken from the **first sample at or before** the start to the **last sample at or
     * after** the end, walking the samples in order. Both tests are inclusive on purpose: a
     * survivor that dropped the sample just outside the cut would come back visibly short of where
     * the ink stops, and the ink stops at the cut.
     *
     * The result is a contiguous sublist of the ORIGINAL samples — the same [PenSample] values, not
     * coordinates rebuilt from a curve — which is what keeps a half-erased line re-brushable
     * (LEAD_RULINGS R20). A cut that lands where the line doubles back on itself still gives one
     * contiguous run per piece, because the search is over arc length along the line and the two
     * arms of a doubling-back line are at different arc lengths.
     */
    private fun survivorSamples(
        record: StrokeRecord,
        lineArc: DoubleArray,
        sampleArc: DoubleArray,
        piece: Piece,
    ): List<PenSample> {
        val n = sampleArc.size
        if (n == 0 || lineArc.isEmpty()) return emptyList()
        val from = arcAt(lineArc, piece.from)
        val to = arcAt(lineArc, piece.to)
        val first = lastIndexAtOrBefore(sampleArc, from)
        val last = firstIndexAtOrAfter(sampleArc, to)
        if (last < first || last >= n) return emptyList()
        return record.samples.subList(first, last + 1).toList()
    }

    /**
     * Arc length at fractional point index [param], interpolated inside the segment it falls in and
     * clamped to the list, so a piece can never ask for a point that is not there.
     */
    private fun arcAt(arc: DoubleArray, param: Double): Double {
        if (arc.size <= 1) return if (arc.isEmpty()) 0.0 else arc[0]
        val last = (arc.size - 1).toDouble()
        val p = if (param.isNaN()) 0.0 else param.coerceIn(0.0, last)
        val i = min(p.toInt(), arc.size - 2)
        return arc[i] + (p - i) * (arc[i + 1] - arc[i])
    }

    /** The largest index whose arc length is at or before [value]; 0 when the value is before the list. */
    private fun lastIndexAtOrBefore(arc: DoubleArray, value: Double): Int {
        if (value <= arc[0]) return 0
        if (value >= arc[arc.size - 1]) return arc.size - 1
        var lo = 0
        var hi = arc.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (arc[mid] <= value) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The smallest index whose arc length is at or after [value]; the last index when there is none. */
    private fun firstIndexAtOrAfter(arc: DoubleArray, value: Double): Int {
        if (value <= arc[0]) return 0
        val last = arc.size - 1
        if (value > arc[last]) return last
        var lo = 0
        var hi = last
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (arc[mid] >= value) hi = mid else lo = mid + 1
        }
        return lo
    }

    /**
     * Cumulative arc length of a recording's samples, in document px.
     *
     * A segment whose length is not a usable number contributes nothing rather than poisoning every
     * length after it with NaN. That is [StrokeEdit.arcLengths]'s rule, applied to the same job, and
     * it is one line: a recording read out of a corrupt file must not delete the samples recorded
     * after the bad float.
     */
    private fun sampleArcOf(samples: List<PenSample>): DoubleArray {
        val n = samples.size
        val arc = DoubleArray(n)
        for (i in 1 until n) {
            val d = hypot(
                (samples[i].x - samples[i - 1].x).toDouble(),
                (samples[i].y - samples[i - 1].y).toDouble(),
            )
            arc[i] = arc[i - 1] + if (d.isFinite()) d else 0.0
        }
        return arc
    }

    /** The same accumulation over the replayed line, which is what a piece's indices are counted in. */
    private fun lineArcOf(dabs: List<Dab>): DoubleArray {
        val n = dabs.size
        val arc = DoubleArray(n)
        for (i in 1 until n) {
            val d = hypot(
                (dabs[i].x - dabs[i - 1].x).toDouble(),
                (dabs[i].y - dabs[i - 1].y).toDouble(),
            )
            arc[i] = arc[i - 1] + if (d.isFinite()) d else 0.0
        }
        return arc
    }

    /** Shared by every candidate that produced no line, so the common case allocates nothing. */
    private val EMPTY_ARC = DoubleArray(0)
}
