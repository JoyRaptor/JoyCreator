package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.guide.Guide
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.select.SelectionLine
import cc.joycreator.joybrush.core.select.SelectionState
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeCapture
import cc.joycreator.joybrush.core.stroke.StrokeEdit
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.symmetry.LaneBrush
import cc.joycreator.joybrush.core.symmetry.SymmetryFamily
import cc.joycreator.joybrush.core.symmetry.SymmetryLanes
import cc.joycreator.joybrush.core.vector.CelComposer
import cc.joycreator.joybrush.core.vector.InkLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Symmetry D1: the copies of a stroke are ONE set of editable lines.
 *
 * The owner wants: select one copy and every copy is selected; a recolour, a re-brush or a delete
 * reaches all of them; ONE undo takes them all back. That is a statement about the RECORDS and the
 * GROUP they share, and it is proved here on the JVM - the engine's own line bookkeeping, the
 * selection's own group expansion and the edit path that follows it.
 *
 * The chain under test, in the order the app runs it:
 *
 *  1. the stroke's lanes each record the samples THEY heard (the copy's own samples);
 *  2. the engine's commit turns one record per lane into one line per lane, in ONE group, with
 *     its own id and its own place in the plane's time order ([LineCommit]);
 *  3. the selection takes the whole group when one copy is taken ([SelectionState]);
 *  4. the recolour that follows reaches every copy, and ONE undo puts them all back.
 *
 * Every oracle here is worked out longhand (a mirror is `x -> 2C - x`) and never read back out of
 * the code under test. A test whose oracle is the code proves nothing.
 */
class SymmetryLineRecordsTest {

    // ---- the fixtures ---------------------------------------------------------------------------

    /**
     * A stamp brush with every channel the symmetry touches: a tip angle of its own, an angle
     * jitter, a size jitter, a scatter and a response curve (so the lane's own `response.apply` is
     * exercised and not just passed through).
     */
    private val brushJson: String = """
        {
          "format": "joybrush.brush",
          "version": 5,
          "id": "test.symmetry.records",
          "name": "Symmetry Records Test",
          "engine": "stamp",
          "tip": {
            "corner": 2,
            "angle": { "base": 20 },
            "followDirection": true
          },
          "size": {
            "base": 8,
            "inputs": [ { "input": "pressure", "curve": [ [0, 0.3], [1, 1] ] } ]
          },
          "opacity": { "base": 1 },
          "flow": { "base": 1 },
          "spacing": 0.25,
          "angleJitter": 30,
          "sizeJitter": 0.3,
          "scatter": {
            "amount": { "base": 0.4 },
            "count": 2,
            "bothAxes": true
          },
          "response": {
            "pressure": [ 0.0, 0.2, 1.0, 1.0 ],
            "tilt": [ 0.0, 0.5, 1.0, 1.0 ]
          },
          "accumulate": "wash",
          "blend": "normal",
          "smoothing": 0.35,
          "license": "CC0"
        }
    """.trimIndent() + "\n"

    private val brush: BrushPreset = BrushJson.decode(brushJson)
    private val brushes = mapOf(brush.id to brush)

    private val seed = 20261020L
    private val smoothing = 0.35f
    private val zoom = 1f
    private val color = 0xFF102030.toInt()

    /** A straight run along +x at y = 0. */
    private val samples: List<PenSample> =
        (0..11).map { PenSample(it * 10f, 0f, it * 10.0, pressure = 0.8f) }

    /** A vertical mirror line at x = 200: the mirror of a point is `x -> 400 - x`. */
    private val mirror = Guide.Symmetry(Pt(200.0, -100.0), Pt(200.0, 100.0), count = 2)

    /** Four lanes around the line's middle (150, 0): the original and three turns. */
    private val radial = Guide.Symmetry(Pt(150.0, -100.0), Pt(150.0, 100.0), count = 4)

    private val plane = LinePlane("paint", "c1")
    private val planeOf: (String, String?) -> LinePlane = { layer, cel -> LinePlane(layer, cel) }

    private fun log() = UndoLog<Int>(budgetBytes = 1L shl 30, sizeOf = { 1L }, release = {})

    /**
     * The stroke's records: ONE per lane, each the lane's OWN recording of the samples it heard.
     *
     * This is the view's own path: a lane is fed the pen's sample and records the copy of it that
     * the brush heard.
     */
    private fun recordsOf(guide: Guide.Symmetry, samples: List<PenSample> = this.samples): List<StrokeRecord> {
        val set = SymmetryLanes.build(guide, LaneBrush.File(brush), SymmetryFamily.STAMP, seed, smoothing, zoom)
        val captures = set.lanes.mapIndexed { i, lane ->
            lane.capture("ln-$i-${lane.seed}", brush.id, smoothing, zoom, color)
        }
        for (s in samples) for (i in set.lanes.indices) set.lanes[i].feed(s, captures[i])
        return captures.mapNotNull { it.finish() }
    }

    /** The commit's plan for [records] in [plane]: the group and the lines. */
    private fun commit(nl: NativeLines, records: List<StrokeRecord>, planesWritten: Int = 1): Pair<String?, List<LineCommit.Made>> {
        val group = LineCommit.groupFor(records, planesWritten)
        return group to LineCommit.plan(nl, brushes, records, plane, "c1", group, listOf(0L))
    }

    /** The picker's view of a record: its centreline, with a flat half-width. */
    private fun geometryOf(r: StrokeRecord): InkLine = InkLine(
        r.id,
        r.samples.map { it.x.toDouble() }.toDoubleArray(),
        r.samples.map { it.y.toDouble() }.toDoubleArray(),
        r.samples.map { 1.0 }.toDoubleArray(),
    )

    // ---- 1. N lanes, N lines, ONE group, ONE undo step -------------------------------------------

    /**
     * The whole of the owner's ask in one test: a stroke of N lanes makes N lines, they share ONE
     * group, they land in ONE step, and ONE undo takes every one of them back.
     */
    @Test fun aStrokeOfNLanesMakesNLineRecordsInOneGroupAndOneUndoStep() {
        val records = recordsOf(radial)
        assertEquals(4, records.size, "four lanes make four recordings")

        val nl = NativeLines()
        val (group, made) = commit(nl, records)
        assertNotNull(group, "a set of four lines is one group")
        assertEquals(4, made.size, "the commit made ${made.size} lines")

        // ONE group for all of them: the set is one thing to Select.
        assertEquals(setOf(group), made.map { it.change.after!!.record.groupId }.toSet(),
            "the copies do not share ONE group")

        // They land in ONE step, and the step carries every one of them.
        val u = log()
        u.push(UndoLog.Step(emptyList(), lines = made.map { it.change }))
        assertEquals(1, u.undoDepth, "the copies are one step, not four")

        // The plane holds the four copies.
        LineItems.apply(nl, emptyList(), made.map { it.change }, forward = true, planeOf = planeOf)
        assertEquals(4, nl.lines(plane).size, "the plane does not hold the four copies")

        // ONE undo takes every one of them back.
        val step = u.undo()!!
        assertEquals(4, step.lines.size, "the step does not carry every copy")
        LineItems.apply(nl, step.slabs, step.lines, forward = false, planeOf = planeOf)
        assertTrue(nl.lines(plane).isEmpty(), "one undo did not take every copy back")
    }

    // ---- 2. the selection takes the whole set -----------------------------------------------------

    /**
     * Selecting ONE copy selects the whole set: the selection's own group expansion does it, and the
     * group's id is the one the commit minted.
     */
    @Test fun selectingOneCopySelectsTheWholeSet() {
        val records = recordsOf(radial)
        val nl = NativeLines()
        val (group, made) = commit(nl, records)
        val lines = made.map { it.change.after!!.record }
        val state = SelectionState(items = object : SelectionState.Items {
            override fun group(id: LineAddress) = lines.firstOrNull { it.id == id.recordId }?.groupId
        })
        val geometries = lines.map { SelectionLine(plane.address(it.id), geometryOf(it)) }

        // One copy is taken: the second one, not the first.
        state.choose(plane.address(lines[1].id), adding = false, at = null, lines = geometries)
        val selection = state.current as SelectionState.Lines
        assertEquals(4, selection.ids.size, "one copy was selected and the set did not come with it")
        assertEquals(lines.map { plane.address(it.id) }.toSet(), selection.ids)
        assertTrue(selection.ids.all { address -> lines.first { it.id == address.recordId }.groupId == group },
            "the selection took a line outside the group")
    }

    // ---- 3. a recolour reaches every copy --------------------------------------------------------

    /**
     * The recolour path: the selection (already the whole set) names every copy, the edit changes
     * every copy's colour, and ONE undo puts the old colour back on all of them.
     */
    @Test fun aRecolourOfTheGroupChangesEveryCopyAndOneUndoPutsThemAllBack() {
        val records = recordsOf(radial)
        val nl = NativeLines()
        val (_, made) = commit(nl, records)
        val lines = made.map { it.change.after!!.record }
        val state = SelectionState(items = object : SelectionState.Items {
            override fun group(id: LineAddress) = lines.firstOrNull { it.id == id.recordId }?.groupId
        })
        val geometries = lines.map { SelectionLine(plane.address(it.id), geometryOf(it)) }
        state.choose(plane.address(lines[0].id), adding = false, at = null, lines = geometries)
        val selection = state.current as SelectionState.Lines
        LineItems.apply(nl, emptyList(), made.map { it.change }, forward = true, planeOf = planeOf)

        // The recolour: every line the selection names, and the selection names every copy.
        val dye = 0xFF00FF00.toInt()
        val chosen = selection.ids.map { address -> lines.first { it.id == address.recordId } }
        assertEquals(4, chosen.size, "the selection did not reach every copy")
        val dyed = chosen.map { StrokeEdit.recolor(it, dye) }
        // The host's own rule: the same ids, the same seqs, ONE step.
        val changes = dyed.map { r ->
            val before = lines.first { it.id == r.id }
            UndoLog.LineChange("paint", "c1", r.id, CelComposer.Line(before.seq, before), CelComposer.Line(r.seq, r))
        }
        val u = log()
        u.push(UndoLog.Step(emptyList(), lines = changes))
        LineItems.apply(nl, emptyList(), changes, forward = true, planeOf = planeOf)

        assertEquals(4, nl.lines(plane).size, "the plane lost a copy")
        assertTrue(nl.lines(plane).all { it.colorArgb == dye }, "a copy kept the old colour")
        assertTrue(nl.lines(plane).all { it.groupId == made[0].change.after!!.record.groupId },
            "the recolour broke the group")

        // ONE undo puts the old colour back on every copy.
        val step = u.undo()!!
        assertEquals(4, step.lines.size, "the recolour was not one step over the whole set")
        LineItems.apply(nl, step.slabs, step.lines, forward = false, planeOf = planeOf)
        assertTrue(nl.lines(plane).all { it.colorArgb == color }, "one undo did not put every copy back")
    }

    // ---- 4. the ids and the seqs -----------------------------------------------------------------

    /**
     * Every copy is its OWN line: its own id, and its own place in the plane's time order.
     *
     * The ids matter because a line is addressed by its id: two copies sharing one would lose one of
     * them. The seqs matter because they say which copy is on top of which.
     */
    @Test fun theCopiesHaveTheirOwnIdsAndAscendingSeqs() {
        val records = recordsOf(radial)
        val nl = NativeLines()
        val (_, made) = commit(nl, records)

        val ids = made.map { it.change.id }
        assertEquals(ids.size, ids.toSet().size, "two copies share an id")
        assertEquals(records.map { it.id }.toSet(), ids.toSet(), "a copy lost its own id")

        val seqs = made.map { it.change.after!!.seq }
        assertEquals(seqs.sorted(), seqs, "the seqs do not ascend")
        assertEquals(seqs.distinct(), seqs, "two copies share a seq")
        assertEquals(1L, seqs.first(), "the first copy does not start the plane's order")
        assertEquals((1L..made.size.toLong()).toList(), seqs, "the copies are not one after another")
    }

    /**
     * A copy whose id the plane already holds does not take it: the id gets a suffix, as it always
     * did, and the copy is still a line of its own.
     */
    @Test fun aCopyThatWouldTakeAnIdAlreadyInThePlaneGetsAnother() {
        val records = recordsOf(radial)
        val nl = NativeLines()
        val (group, made) = commit(nl, records)
        // The first copy is in the plane already.
        LineItems.apply(nl, emptyList(), listOf(made[0].change), forward = true, planeOf = planeOf)

        val again = LineCommit.plan(nl, brushes, records, plane, "c1", group, listOf(0L))
        assertEquals(4, again.size)
        assertEquals(4, again.map { it.change.id }.toSet().size, "a copy took an id that was in use")
        assertTrue(again.none { it.change.id == made[0].change.id }, "a copy took the id that was in use")
    }

    // ---- 5. a stroke with NO symmetry is unchanged ------------------------------------------------

    /**
     * The regression proof: a stroke with no symmetry is ONE record, ONE line, and NO group - the
     * behaviour before the symmetry landed, untouched.
     */
    @Test fun aStrokeWithNoSymmetryMakesOneRecordAndNoGroup() {
        // The ordinary path, written out: one capture, the pen's own samples.
        val capture = StrokeCapture("ln-plain", brush.id, seed, smoothing, zoom, color)
        for (s in samples) capture.add(s)
        val record = assertNotNull(capture.finish(), "the ordinary stroke made no record")
        assertEquals(samples.map { it.x }, record.samples.map { it.x }, "the record is not the pen's own samples")

        val nl = NativeLines()
        val (group, made) = commit(nl, listOf(record))
        assertNull(group, "an ordinary stroke is not a group")
        assertEquals(1, made.size, "an ordinary stroke makes one line")
        assertNull(made[0].change.after!!.record.groupId, "an ordinary stroke's line has a group")
        assertEquals(1L, made[0].change.after!!.seq, "an ordinary stroke's line is not first in the plane")
    }

    /**
     * The ordinary stroke through the LANE machinery is the same stroke: lane 0 is the original, and
     * the symmetry being off changes nothing about it.
     */
    @Test fun theOriginalLaneRecordsTheStrokeItself() {
        val off = Guide.Symmetry(Pt(200.0, -100.0), Pt(200.0, 100.0), count = 1)
        val records = recordsOf(off)
        assertEquals(1, records.size, "a guide with one lane makes one record")
        assertEquals(samples.map { it.x }, records[0].samples.map { it.x }, "lane 0 is not the original")
        assertEquals(samples.map { it.y }, records[0].samples.map { it.y }, "lane 0 is not the original")
        // The recording is PRE-response: it holds the pen's own pressure, not the brush's hearing of
        // it. The brush's pressure curve moves 0.8 to about 0.83, so this is not the same number.
        assertEquals(samples.map { it.pressure }, records[0].samples.map { it.pressure },
            "lane 0 records the brush's hearing, not the pen's reading")
        assertEquals(seed, records[0].seed, "lane 0 does not carry the stroke's own seed")
    }

    // ---- 6. the records hold the COPY's samples ---------------------------------------------------

    /**
     * A copy's record holds the COPY's samples: the mirror of the pen's own, and never the pen's own.
     * A record that held the original's samples would replay as the original - the copy would be
     * painted twice and the original once.
     */
    @Test fun aCopysRecordHoldsTheCopysOwnSamplesAndNotTheOriginals() {
        val records = recordsOf(mirror)
        assertEquals(2, records.size, "a mirror is two lanes")
        val original = records[0]
        val copy = records[1]
        assertEquals(samples.size, original.samples.size, "the original lost samples")
        assertEquals(samples.size, copy.samples.size, "the copy lost samples")
        for (i in samples.indices) {
            assertEquals(samples[i].x, original.samples[i].x, 1e-4f, "sample $i of the original moved")
            // The guide's line is at x = 200, so the mirror of a point is `x -> 400 - x`.
            assertEquals(400.0 - samples[i].x, copy.samples[i].x.toDouble(), 1e-4, "sample $i of the copy is not the mirror")
            assertEquals(samples[i].y, copy.samples[i].y, 1e-4f, "sample $i of the copy moved in y")
        }
        assertNotEquals(original.samples.map { it.x }, copy.samples.map { it.x },
            "the copy's record holds the original's samples")
        // The pressure is the PEN's own in both: the recording is pre-response, and the copy's map
        // does not touch the pressure.
        assertEquals(samples.map { it.pressure }, original.samples.map { it.pressure }, "the original's pressure moved")
        assertEquals(samples.map { it.pressure }, copy.samples.map { it.pressure }, "the copy's pressure moved")
    }

    /**
     * Every lane records the samples IT heard: the copy's own seed goes into the record, so a replay
     * is the stroke that was drawn (R13).
     */
    @Test fun everyLaneCarriesItsOwnSeedIntoItsRecord() {
        val records = recordsOf(mirror)
        val set = SymmetryLanes.build(mirror, LaneBrush.File(brush), SymmetryFamily.STAMP, seed, smoothing, zoom)
        for (i in set.lanes.indices) {
            assertEquals(set.lanes[i].seed, records[i].seed, "lane $i does not carry its own seed")
        }
        // With the guide symmetric the copies share ONE brush's randomness, so they share the seed.
        assertEquals(records[0].seed, records[1].seed, "a symmetric guide gave the copies different seeds")
    }

    // ---- 7. an empty lane contributes nothing -----------------------------------------------------

    /**
     * A lane that produced no samples contributes NO record: an empty contact has nothing to record,
     * and a record with no samples is not a line.
     */
    @Test fun aLaneThatProducedNoSamplesContributesNoRecord() {
        val set = SymmetryLanes.build(mirror, LaneBrush.File(brush), SymmetryFamily.STAMP, seed, smoothing, zoom)
        val captures = set.lanes.mapIndexed { i, lane ->
            lane.capture("ln-empty-$i-${lane.seed}", brush.id, smoothing, zoom, color)
        }
        // The pen never touched down: the lanes are fed nothing.
        val records = captures.mapNotNull { it.finish() }
        assertTrue(records.isEmpty(), "an empty contact made a record")

        // And the commit of an empty set makes no line at all.
        val nl = NativeLines()
        val (group, made) = commit(nl, records)
        assertNull(group, "an empty set is not a group")
        assertTrue(made.isEmpty(), "an empty set made a line")
    }

    /**
     * A lane fed only samples the pen could not place (a NaN reading) records nothing either: the
     * brush never heard them, so the recording must not hold them.
     */
    @Test fun aLaneFedOnlyUnplaceableSamplesRecordsNothing() {
        val set = SymmetryLanes.build(mirror, LaneBrush.File(brush), SymmetryFamily.STAMP, seed, smoothing, zoom)
        val captures = set.lanes.mapIndexed { i, lane ->
            lane.capture("ln-bad-$i-${lane.seed}", brush.id, smoothing, zoom, color)
        }
        val broken = PenSample(Float.NaN, 0f, 0.0)
        for (i in set.lanes.indices) set.lanes[i].feed(broken, captures[i])
        assertTrue(captures.all { it.finish() == null }, "a lane recorded a sample the brush never heard")
    }
}
