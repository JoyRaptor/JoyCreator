package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.*

/**
 * Editable lines v1 (tasks/joybrush/specs/NATIVE_LINES_V1_20261016.md): the engine-side bookkeeping, pure JVM. Footprints
 * come from a fake function so every crossing rule is stated with explicit tile keys, not geometry.
 */
class NativeLinesTest {
    private val paint = LinePlane("paint", null)
    private val shared = LinePlane("paint", "shared")
    private val other = LinePlane("sketch", "c")

    private fun tile(x: Int, y: Int = 0) = Tiles.key(x, y)
    private fun line(id: String, seq: Long, brushId: String = "pen", x: Float = 1f) = StrokeRecord(id, brushId, 7L, 0f, 1f,
        listOf(PenSample(x, 2f, 0.0)), seq = seq)
    private fun pen(size: Float = 8f) = BrushPreset(id = "pen", name = "Pen", size = Param(size))

    /** Tiles by record id; every call is kept so caching is observable. */
    private class Fake {
        val tilesOf = HashMap<String, Set<Long>>()
        val calls = ArrayList<Pair<StrokeRecord, BrushPreset?>>()
        fun footprint(record: StrokeRecord, brush: BrushPreset?): Set<Long> { calls += record to brush; return tilesOf[record.id].orEmpty() }
    }
    private fun store(fake: Fake = Fake()) = NativeLines(fake::footprint)

    // ── lines, seq and ids ────────────────────────────────────────────────────

    @Test fun anEmptyStoreHasNoLinesAndTheFirstSeqIsOne() {
        val lines = store()
        assertTrue(lines.lines(paint).isEmpty())
        assertFalse(lines.hasLines(paint))
        assertFalse(lines.hasLinesOnLayer("paint"))
        assertTrue(lines.linePlanes().isEmpty())
        assertEquals(1L, lines.nextSeq(paint))
        assertNull(lines.record(paint, "a"))
        assertNull(lines.base(paint, tile(0)))
        assertEquals(0L, lines.revision)
        assertTrue(lines.snapshot().planes.isEmpty())
        assertTrue(lines.baseTextures().isEmpty())
    }

    @Test fun linesAreKeptInSeqOrderWhateverTheOrderTheyArePut() {
        val lines = store()
        lines.put(paint, "c", line("c", 3))
        lines.put(paint, "a", line("a", 1))
        lines.put(paint, "b", line("b", 2))
        assertEquals(listOf("a", "b", "c"), lines.lines(paint).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), lines.lines(paint).map { it.seq })
        assertEquals(4L, lines.nextSeq(paint))
        assertEquals("b", lines.record(paint, "b")?.id)
    }

    @Test fun nextSeqFollowsTheHighestSeqOfEachPlaneSeparately() {
        val lines = store()
        lines.put(paint, "a", line("a", 5))
        lines.put(other, "x", line("x", 2))
        assertEquals(6L, lines.nextSeq(paint))
        assertEquals(3L, lines.nextSeq(other))
        assertEquals(1L, lines.nextSeq(shared), "a plane nothing was put in starts again at 1")
        lines.put(paint, "a", null)
        assertEquals(1L, lines.nextSeq(paint), "without its lines a plane starts again at 1")
    }

    @Test fun planesAreKeptApartByLayerAndCel() {
        val lines = store()
        lines.put(paint, "a", line("a", 1))
        lines.put(shared, "a", line("a", 1))   // the same id in another plane is another line
        lines.put(other, "b", line("b", 1))
        assertEquals(setOf(paint, shared, other), lines.linePlanes())
        assertTrue(lines.hasLinesOnLayer("paint")); assertTrue(lines.hasLinesOnLayer("sketch"))
        assertFalse(lines.hasLinesOnLayer("missing"))
        lines.put(paint, "a", null)
        assertEquals(setOf(shared, other), lines.linePlanes())
        assertTrue(lines.hasLinesOnLayer("paint"), "the layer still has the line of its other plane")
        assertNotNull(lines.record(shared, "a"))
    }

    @Test fun putAtAnExistingIdReplacesThatLineAndReorders() {
        val lines = store()
        lines.put(paint, "a", line("a", 1))
        lines.put(paint, "b", line("b", 2))
        lines.put(paint, "a", line("a", 9, x = 5f))
        assertEquals(listOf("b", "a"), lines.lines(paint).map { it.id })
        assertEquals(9L, lines.record(paint, "a")?.seq)
        assertEquals(2, lines.lines(paint).size)
        // The same id and the same seq is the ordinary "recolour in place": not a seq clash with itself.
        lines.put(paint, "b", line("b", 2, x = 6f))
        assertEquals(6f, lines.record(paint, "b")?.samples?.single()?.x)
    }

    @Test fun putNullRemovesTheLineAndAPlaneWithNothingLeftDisappears() {
        val lines = store()
        lines.put(paint, "a", line("a", 1))
        lines.put(paint, "b", line("b", 2))
        lines.put(paint, "a", null)
        assertEquals(listOf("b"), lines.lines(paint).map { it.id })
        lines.put(paint, "b", null)
        assertFalse(lines.hasLines(paint))
        assertTrue(lines.linePlanes().isEmpty())
        assertTrue(lines.snapshot().planes.isEmpty())
        lines.put(paint, "never-there", null)   // removing an unknown line is harmless
        assertFalse(lines.hasLines(paint))
    }

    @Test fun aPlaneKeepsItsBasesAfterItsLastLineIsRemoved() {
        val lines = store()
        lines.put(paint, "a", line("a", 1))
        lines.setBase(paint, tile(0), 11)
        lines.put(paint, "a", null)
        assertFalse(lines.hasLines(paint))
        assertTrue(lines.linePlanes().isEmpty(), "a plane with only a base holds no lines")
        assertTrue(lines.snapshot().planes.isEmpty())
        assertEquals(11, lines.base(paint, tile(0)), "the base outlives the line: undo can bring the line back")
        assertEquals(setOf(11), lines.baseTextures())
    }

    @Test fun duplicateSeqSeqBelowOneAndAMismatchedIdAreRefused() {
        val lines = store()
        lines.put(paint, "a", line("a", 1))
        lines.put(paint, "b", line("b", 2))
        assertFailsWith<IllegalArgumentException> { lines.put(paint, "c", line("c", 2)) }
        assertFailsWith<IllegalArgumentException> { lines.put(paint, "c", line("c", 0)) }
        assertFailsWith<IllegalArgumentException> { lines.put(paint, "c", line("c", -4)) }
        assertFailsWith<IllegalArgumentException> { lines.put(paint, "c", line("other-id", 3)) }
        assertNull(lines.record(paint, "c"))
        assertEquals(listOf("a", "b"), lines.lines(paint).map { it.id })
        assertEquals(listOf(1L, 2L), lines.lines(paint).map { it.seq })
    }

    @Test fun theListReturnedForAPlaneIsACopy() {
        val lines = store()
        lines.put(paint, "a", line("a", 1))
        val before = lines.lines(paint)
        lines.put(paint, "b", line("b", 2))
        assertEquals(1, before.size)
        assertEquals(2, lines.lines(paint).size)
    }

    // ── footprints and crossing ───────────────────────────────────────────────

    @Test fun crossedAnswersFromTheStoreOrFromAnyExplicitListOfRecords() {
        val fake = Fake().apply {
            tilesOf["a"] = setOf(tile(0), tile(1)); tilesOf["b"] = setOf(tile(1), tile(2)); tilesOf["c"] = setOf(tile(5))
        }
        val lines = store(fake)
        val a = line("a", 1); val b = line("b", 2); val c = line("c", 3)
        lines.put(paint, "a", a); lines.put(paint, "b", b)
        assertTrue(lines.crossed(paint, tile(0))); assertTrue(lines.crossed(paint, tile(2)))
        assertFalse(lines.crossed(paint, tile(5)), "c is not in the plane")
        assertTrue(lines.crossed(paint, tile(5), listOf(c)), "an explicit list (an edit's next state) decides")
        assertFalse(lines.crossed(paint, tile(0), listOf(b, c)))
        assertFalse(lines.crossed(paint, tile(0), emptyList()), "an explicit empty list is no lines, not the store")
        assertFalse(lines.crossed(other, tile(0)), "another plane has no lines")
    }

    @Test fun crossingListsTheLinesOfTheGivenRecordsThatReachTheTileInSeqOrder() {
        val fake = Fake().apply {
            tilesOf["a"] = setOf(tile(0), tile(1)); tilesOf["b"] = setOf(tile(1)); tilesOf["c"] = setOf(tile(1), tile(2))
            tilesOf["d"] = setOf(tile(9))
        }
        val lines = store(fake)
        val given = listOf(line("c", 30), line("a", 10), line("d", 5), line("b", 20))   // not in seq order on purpose
        assertEquals(listOf("a", "b", "c"), lines.crossing(given, tile(1)).map { it.id })
        assertEquals(listOf("a"), lines.crossing(given, tile(0)).map { it.id })
        assertEquals(listOf("c"), lines.crossing(given, tile(2)).map { it.id })
        assertTrue(lines.crossing(given, tile(7)).isEmpty())
        assertTrue(lines.crossing(emptyList(), tile(1)).isEmpty())
    }

    @Test fun aFootprintIsComputedOnceByValueAndGetsTheFrozenBrushOfTheRecord() {
        val fake = Fake().apply { tilesOf["a"] = setOf(tile(3)) }
        val lines = store(fake)
        lines.addBrushes(mapOf("pen" to pen()))
        val a = line("a", 1)
        assertEquals(setOf(tile(3)), lines.footprint(a))
        assertEquals(setOf(tile(3)), lines.footprint(a.copy()), "an undo hands back an equal copy: same cache entry")
        assertEquals(1, fake.calls.size)
        assertEquals(pen(), fake.calls.single().second)
        lines.footprint(line("ghost", 2, brushId = "unknown-brush"))
        assertNull(fake.calls.last().second, "a brush the drawing does not hold is handed over as null")
        lines.footprint(a.copy(seq = 4))
        assertEquals(3, fake.calls.size, "a record that differs in any field is another record")
    }

    @Test fun widenAddsTilesTheGpuDrewAndNeverNarrows() {
        val fake = Fake().apply { tilesOf["a"] = setOf(tile(0), tile(1)) }
        val lines = store(fake)
        val a = line("a", 1)
        lines.put(paint, "a", a)
        lines.widen(a, setOf(tile(1)))
        assertEquals(setOf(tile(0), tile(1)), lines.footprint(a), "tiles it already had change nothing")
        lines.widen(a, emptySet())
        assertEquals(setOf(tile(0), tile(1)), lines.footprint(a))
        lines.widen(a, setOf(tile(1), tile(4)))
        assertEquals(setOf(tile(0), tile(1), tile(4)), lines.footprint(a))
        lines.widen(a, setOf(tile(0)))
        assertEquals(setOf(tile(0), tile(1), tile(4)), lines.footprint(a), "a smaller drawn set never takes tiles away")
        assertTrue(lines.crossed(paint, tile(4)), "a widened tile counts as crossed")
    }

    @Test fun aRecordTheCpuCannotReplayStartsEmptyAndIsWidenedFromWhatTheGpuDrew() {
        val lines = store()   // the fake knows no tiles for it, as cpuFootprint does for a refused brush
        val a = line("a", 1)
        lines.put(paint, "a", a)
        assertTrue(lines.footprint(a).isEmpty())
        assertFalse(lines.crossed(paint, tile(0)))
        lines.widen(a, setOf(tile(0), tile(1)))
        assertTrue(lines.crossed(paint, tile(1)))
    }

    @Test fun theRealFootprintOfAnUnknownBrushIsEmptyAndOfAStrokeIsPaddedPastItsDabs() {
        assertTrue(NativeLines.cpuFootprint(line("a", 1, brushId = "unknown"), null).isEmpty())
        val stroke = StrokeRecord("s", "pen", 7L, 0f, 1f,
            listOf(PenSample(300f, 40f, 0.0), PenSample(330f, 40f, 16.0)), seq = 1)
        val tiles = NativeLines.cpuFootprint(stroke, pen())
        assertTrue(tile(1, 0) in tiles, "the dabs are on tile 1,0")
        assertFalse(tile(5, 5) in tiles)
        // 8 px of padding round each dab reaches the tile on its left from x = 258.
        val edge = StrokeRecord("e", "pen", 7L, 0f, 1f,
            listOf(PenSample(258f, 40f, 0.0), PenSample(262f, 40f, 16.0)), seq = 1)
        assertTrue(tile(0, 0) in NativeLines.cpuFootprint(edge, pen()), "padding makes the footprint a superset")
    }

    // ── bases ─────────────────────────────────────────────────────────────────

    @Test fun aBaseIsPerPlaneAndTileAndSetNullDropsIt() {
        val lines = store()
        lines.put(paint, "a", line("a", 1))
        lines.put(other, "x", line("x", 1))
        lines.setBase(paint, tile(0), 11)
        lines.setBase(paint, tile(1), 12)
        lines.setBase(other, tile(0), 13)
        assertEquals(11, lines.base(paint, tile(0)))
        assertEquals(12, lines.base(paint, tile(1)))
        assertEquals(13, lines.base(other, tile(0)))
        assertNull(lines.base(paint, tile(2)), "an absent base on a crossed tile means transparent")
        assertNull(lines.base(shared, tile(0)))
        assertEquals(setOf(11, 12, 13), lines.baseTextures())
        lines.setBase(paint, tile(0), 21)
        assertEquals(21, lines.base(paint, tile(0)))
        assertEquals(setOf(21, 12, 13), lines.baseTextures())
        lines.setBase(paint, tile(0), null)
        assertNull(lines.base(paint, tile(0)))
        assertEquals(setOf(12, 13), lines.baseTextures())
    }

    @Test fun aBaseOnAPlaneWithNoLinesIsKeptUntilItIsDropped() {
        val lines = store()
        lines.setBase(paint, tile(0), 7)
        assertEquals(7, lines.base(paint, tile(0)))
        assertFalse(lines.hasLines(paint))
        assertTrue(lines.linePlanes().isEmpty())
        lines.setBase(paint, tile(0), null)
        assertNull(lines.base(paint, tile(0)))
        assertTrue(lines.baseTextures().isEmpty())
    }

    // ── rename ────────────────────────────────────────────────────────────────

    @Test fun renameMovesTheLinesAndBasesOfAPlaneAndGrowsTheRevision() {
        val lines = store()
        lines.put(paint, "a", line("a", 1)); lines.put(paint, "b", line("b", 2))
        lines.setBase(paint, tile(0), 11)
        val before = lines.revision
        lines.rename(paint, shared)
        assertTrue(lines.revision > before)
        assertFalse(lines.hasLines(paint))
        assertNull(lines.base(paint, tile(0)))
        assertEquals(listOf("a", "b"), lines.lines(shared).map { it.id })
        assertEquals(11, lines.base(shared, tile(0)))
        assertEquals(setOf(shared), lines.linePlanes())
        assertEquals(3L, lines.nextSeq(shared))
        assertEquals(setOf(shared), lines.snapshot().planes.keys)
    }

    @Test fun renamingToTheSamePlaneOrFromAPlaneWithNothingChangesNothing() {
        val lines = store()
        lines.put(paint, "a", line("a", 1))
        val before = lines.revision
        lines.rename(paint, paint)
        lines.rename(other, shared)
        assertEquals(before, lines.revision)
        assertEquals(setOf(paint), lines.linePlanes())
        assertFalse(lines.hasLines(shared))
    }

    @Test fun renameRefusesToMergeIntoAPlaneThatAlreadyHasLines() {
        val lines = store()
        lines.put(paint, "a", line("a", 1))
        lines.put(shared, "z", line("z", 1))
        lines.setBase(shared, tile(3), 31)
        assertFailsWith<IllegalArgumentException> { lines.rename(paint, shared) }
        assertEquals(listOf("z"), lines.lines(shared).map { it.id }, "the target is exactly as it was")
        assertEquals(31, lines.base(shared, tile(3)))
    }

    // ── snapshot and revision ─────────────────────────────────────────────────

    @Test fun theSnapshotHoldsEveryPlaneWithLinesInSeqOrderAndTheFrozenBrushes() {
        val lines = store()
        lines.addBrushes(mapOf("pen" to pen()))
        lines.put(paint, "b", line("b", 2)); lines.put(paint, "a", line("a", 1))
        lines.put(other, "x", line("x", 1))
        lines.setBase(shared, tile(0), 5)   // a plane with only a base is not in the snapshot
        val snapshot = lines.snapshot()
        assertEquals(setOf(paint, other), snapshot.planes.keys)
        assertEquals(listOf("a", "b"), snapshot.lines(paint).map { it.id })
        assertTrue(snapshot.hasLines(paint)); assertFalse(snapshot.hasLines(shared))
        assertTrue(snapshot.lines(shared).isEmpty())
        assertEquals(pen(), snapshot.brush("pen"))
        assertNull(snapshot.brush("none"))
        assertEquals(lines.revision, snapshot.revision)
    }

    @Test fun aSnapshotDoesNotChangeWhenTheStoreDoes() {
        val lines = store()
        lines.addBrushes(mapOf("pen" to pen()))
        lines.put(paint, "a", line("a", 1))
        val snapshot = lines.snapshot()
        lines.put(paint, "b", line("b", 2)); lines.put(paint, "a", null)
        lines.addBrushes(mapOf("ink" to pen().copy(id = "ink")))
        assertEquals(listOf("a"), snapshot.lines(paint).map { it.id })
        assertEquals(setOf("pen"), snapshot.brushes.keys)
        assertEquals(listOf("b"), lines.snapshot().lines(paint).map { it.id })
    }

    @Test fun theRevisionGrowsWithEveryLineChangeRenameClearAndNewBrush() {
        val lines = store()
        var last = lines.revision
        fun grew(why: String) { assertTrue(lines.revision > last, why); last = lines.revision }
        lines.put(paint, "a", line("a", 1)); grew("a line was added")
        lines.put(paint, "a", line("a", 1, x = 9f)); grew("a line was replaced")
        lines.put(paint, "a", null); grew("a line was removed")
        lines.put(paint, "b", line("b", 2)); grew("another line was added")
        lines.rename(paint, shared); grew("a plane was renamed")
        lines.addBrushes(mapOf("pen" to pen())); grew("a new frozen brush")
        lines.clear(); grew("everything was forgotten")
        assertEquals(last, lines.snapshot().revision)
        val stale = lines.snapshot()
        lines.put(paint, "c", line("c", 1))
        assertTrue(lines.snapshot().revision > stale.revision, "a holder can tell its copy is stale")
    }

    // ── frozen brushes ────────────────────────────────────────────────────────

    @Test fun addBrushesAcceptsNewAndIdenticalDefinitionsAndOnlyTheNewOnesGrowTheRevision() {
        val lines = store()
        lines.addBrushes(mapOf("pen" to pen()))
        assertEquals(pen(), lines.brush("pen"))
        val after = lines.revision
        lines.addBrushes(mapOf("pen" to pen()))   // the same definition again (a second line with the same brush)
        assertEquals(after, lines.revision)
        lines.addBrushes(mapOf("pen" to pen(), "ink" to pen().copy(id = "ink")))
        assertTrue(lines.revision > after)
        assertEquals(setOf("pen", "ink"), lines.brushes().keys)
        assertNull(lines.brush("missing"))
    }

    @Test fun addBrushesRefusesAConflictingDefinitionUnderAKnownIdAndAddsNothingOfThatCall() {
        val lines = store()
        lines.addBrushes(mapOf("pen" to pen()))
        val revision = lines.revision
        assertFailsWith<IllegalArgumentException> {
            lines.addBrushes(mapOf("fresh" to pen().copy(id = "fresh"), "pen" to pen(size = 30f)))
        }
        assertEquals(pen(), lines.brush("pen"), "an id is a definition for good")
        assertNull(lines.brush("fresh"), "the refused call added nothing, not even its new entries")
        assertEquals(revision, lines.revision)
        assertEquals(setOf("pen"), lines.brushes().keys)
    }

    @Test fun theBrushMapReturnedIsACopy() {
        val lines = store()
        lines.addBrushes(mapOf("pen" to pen()))
        val copy = lines.brushes()
        lines.addBrushes(mapOf("ink" to pen().copy(id = "ink")))
        assertEquals(setOf("pen"), copy.keys)
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    // A layer clear (GlPaintEngine.clearLayerWithLines), as NativeLines sees it: bases and lines go together and come back together.
    @Test fun takingEveryLineAndBaseOfAPlaneLeavesNothingAndPuttingThemBackRestoresIt() {
        val fake = Fake().apply { tilesOf["a"] = setOf(tile(0), tile(1)); tilesOf["b"] = setOf(tile(1)) }
        val lines = store(fake)
        val a = line("a", 1); val b = line("b", 2)
        lines.put(paint, "a", a); lines.put(paint, "b", b)
        lines.setBase(paint, tile(0), 11); lines.setBase(paint, tile(1), 12)

        // Clear: one step's worth of bases and lines go together.
        val records = lines.lines(paint)
        val bases = records.flatMapTo(HashSet()) { lines.footprint(it) }.mapNotNull { key -> lines.base(paint, key)?.let { key to it } }
        for ((key, _) in bases) lines.setBase(paint, key, null)
        for (r in records) lines.put(paint, r.id, null)
        assertTrue(lines.linePlanes().isEmpty())
        assertFalse(lines.hasLinesOnLayer("paint"))
        assertTrue(lines.baseTextures().isEmpty())
        assertTrue(lines.snapshot().planes.isEmpty())
        assertEquals(1L, lines.nextSeq(paint))

        // Undo: the same bases and records come back, in seq order, with their footprints.
        for ((key, texture) in bases) lines.setBase(paint, key, texture)
        for (r in records.reversed()) lines.put(paint, r.id, r)
        assertEquals(listOf("a", "b"), lines.lines(paint).map { it.id })
        assertEquals(setOf(11, 12), lines.baseTextures())
        assertEquals(11, lines.base(paint, tile(0))); assertEquals(12, lines.base(paint, tile(1)))
        assertTrue(lines.crossed(paint, tile(0)))
        assertEquals(setOf(tile(0), tile(1)), lines.footprint(a))
    }

    @Test fun clearForgetsLinesBasesBrushesAndCachedFootprints() {
        val fake = Fake().apply { tilesOf["a"] = setOf(tile(0)) }
        val lines = store(fake)
        lines.addBrushes(mapOf("pen" to pen()))
        val a = line("a", 1)
        lines.put(paint, "a", a)
        lines.setBase(paint, tile(0), 11)
        lines.footprint(a)
        lines.clear()
        assertTrue(lines.linePlanes().isEmpty())
        assertNull(lines.base(paint, tile(0)))
        assertTrue(lines.baseTextures().isEmpty())
        assertTrue(lines.brushes().isEmpty())
        assertEquals(1L, lines.nextSeq(paint))
        assertTrue(lines.snapshot().planes.isEmpty())
        lines.footprint(a)
        assertEquals(2, fake.calls.size, "the cache went with the drawing")
    }
}
