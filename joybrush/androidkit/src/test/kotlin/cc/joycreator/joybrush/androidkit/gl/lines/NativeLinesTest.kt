package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.*

/**
 * Editable lines v1 + v1.2 (tasks/joybrush/specs/NATIVE_LINES_V1_20261016.md, NATIVE_LINES_V12_HARDENING_20261017.md): the
 * engine-side bookkeeping, pure JVM. Footprints come from a fake function so every crossing rule is stated with explicit
 * tile keys, not geometry.
 */
class NativeLinesTest {
    private val paint = LinePlane("paint", null)
    private val shared = LinePlane("paint", "shared")
    private val other = LinePlane("sketch", "c")

    private fun tile(x: Int, y: Int = 0) = Tiles.key(x, y)
    private fun line(id: String, seq: Long, brushId: String = "pen", x: Float = 1f) = StrokeRecord(id, brushId, 7L, 0f, 1f,
        listOf(PenSample(x, 2f, 0.0)), seq = seq)
    private fun pen(size: Float = 8f) = BrushPreset(id = "pen", name = "Pen", size = Param(size))

    /** Tiles by record id; every call is kept so computing is observable. */
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
        assertTrue(lines.textures().isEmpty())
        assertFalse(lines.hasItems(paint))
        assertTrue(lines.itemPlanes().isEmpty())
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
        assertFalse(lines.hasItems(paint), "a base alone is not an item")
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

    // ── footprints and crossing (identity, never value) ───────────────────────

    @Test fun theIndexAnswersWhichItemsCrossATileInSeqOrder() {
        val fake = Fake().apply {
            tilesOf["a"] = setOf(tile(0), tile(1)); tilesOf["b"] = setOf(tile(1), tile(2)); tilesOf["c"] = setOf(tile(5))
        }
        val lines = store(fake)
        lines.put(paint, "b", line("b", 2)); lines.put(paint, "a", line("a", 1))
        assertTrue(lines.isCrossed(paint, tile(0))); assertTrue(lines.isCrossed(paint, tile(2)))
        assertFalse(lines.isCrossed(paint, tile(5)), "c is not in the plane")
        assertEquals(listOf(1L, 2L), lines.seqsAt(paint, tile(1)))
        assertEquals(listOf("a", "b"), lines.linesAt(paint, tile(1)).map { it.id })
        assertEquals(listOf("a"), lines.linesAt(paint, tile(0)).map { it.id })
        assertEquals(listOf("b"), lines.linesAt(paint, tile(2)).map { it.id })
        assertTrue(lines.linesAt(paint, tile(7)).isEmpty())
        assertFalse(lines.isCrossed(other, tile(0)), "another plane has no lines")
        assertEquals(setOf(tile(0), tile(1), tile(2)), lines.crossedKeys(paint))
    }

    @Test fun removingOrReplacingALineTakesItsTilesOutOfTheIndex() {
        val fake = Fake().apply { tilesOf["a"] = setOf(tile(0), tile(1)); tilesOf["b"] = setOf(tile(1)) }
        val lines = store(fake)
        lines.put(paint, "a", line("a", 1)); lines.put(paint, "b", line("b", 2))
        lines.put(paint, "a", null)
        assertFalse(lines.isCrossed(paint, tile(0)))
        assertEquals(listOf("b"), lines.linesAt(paint, tile(1)).map { it.id })
        fake.tilesOf["b"] = setOf(tile(3))
        lines.put(paint, "b", line("b", 2, x = 9f))   // moved: the footprint is the new one
        assertFalse(lines.isCrossed(paint, tile(1)))
        assertTrue(lines.isCrossed(paint, tile(3)))
        assertEquals(setOf(tile(3)), lines.footprint(paint, "b"))
        assertEquals(emptySet(), lines.footprint(paint, "a"), "a removed line has no footprint")
    }

    @Test fun aFootprintIsComputedOnceWhenALineIsPutAndGetsTheFrozenBrushOfTheRecord() {
        val fake = Fake().apply { tilesOf["a"] = setOf(tile(3)) }
        val lines = store(fake)
        lines.addBrushes(mapOf("pen" to pen()))
        lines.put(paint, "a", line("a", 1))
        assertEquals(1, fake.calls.size)
        assertEquals(pen(), fake.calls.single().second)
        assertEquals(setOf(tile(3)), lines.footprint(paint, "a"))
        assertEquals(setOf(tile(3)), lines.footprint(paint, "a"))
        assertEquals(1, fake.calls.size, "reading a footprint never computes it again")
        lines.estimate(line("ghost", 2, brushId = "unknown-brush"))
        assertNull(fake.calls.last().second, "a brush the drawing does not hold is handed over as null")
    }

    @Test fun anExplicitFootprintReplacesTheEstimate() {
        val fake = Fake().apply { tilesOf["a"] = setOf(tile(0)) }
        val lines = store(fake)
        lines.put(paint, "a", line("a", 1), footprint = setOf(tile(0), tile(4)))
        assertEquals(setOf(tile(0), tile(4)), lines.footprint(paint, "a"))
        assertTrue(lines.isCrossed(paint, tile(4)))
    }

    @Test fun widenAddsTilesTheGpuDrewAndNeverNarrows() {
        val fake = Fake().apply { tilesOf["a"] = setOf(tile(0), tile(1)) }
        val lines = store(fake)
        lines.put(paint, "a", line("a", 1))
        lines.widen(paint, "a", setOf(tile(1)))
        assertEquals(setOf(tile(0), tile(1)), lines.footprint(paint, "a"), "tiles it already had change nothing")
        lines.widen(paint, "a", emptySet())
        assertEquals(setOf(tile(0), tile(1)), lines.footprint(paint, "a"))
        lines.widen(paint, "a", setOf(tile(1), tile(4)))
        assertEquals(setOf(tile(0), tile(1), tile(4)), lines.footprint(paint, "a"))
        lines.widen(paint, "a", setOf(tile(0)))
        assertEquals(setOf(tile(0), tile(1), tile(4)), lines.footprint(paint, "a"), "a smaller drawn set never takes tiles away")
        assertTrue(lines.isCrossed(paint, tile(4)), "a widened tile counts as crossed")
        lines.widen(paint, "unknown", setOf(tile(9)))   // widening a line that is not there is harmless
        assertFalse(lines.isCrossed(paint, tile(9)))
    }

    @Test fun aWidenedFootprintComesBackWhenAnUndoPutsTheLineAgain() {
        val fake = Fake().apply { tilesOf["a"] = setOf(tile(0)) }
        val lines = store(fake)
        val a = line("a", 1)
        lines.put(paint, "a", a)
        lines.widen(paint, "a", setOf(tile(6)))
        lines.put(paint, "a", null)          // deleted
        assertFalse(lines.isCrossed(paint, tile(6)))
        lines.put(paint, "a", a)             // undo of the delete hands back an equal record, nothing else
        assertEquals(setOf(tile(0), tile(6)), lines.footprint(paint, "a"))
    }

    @Test fun aRecordTheCpuKnowsNothingOfStartsEmptyAndIsWidenedFromWhatTheGpuDrew() {
        val lines = store()   // the fake knows no tiles for it, as cpuFootprint does for a missing brush
        val a = line("a", 1)
        lines.put(paint, "a", a)
        assertTrue(lines.footprint(paint, "a").isEmpty())
        assertFalse(lines.isCrossed(paint, tile(0)))
        lines.widen(paint, "a", setOf(tile(0), tile(1)))
        assertTrue(lines.isCrossed(paint, tile(1)))
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
        assertEquals(mapOf(tile(0) to 11, tile(1) to 12), lines.bases(paint))
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

    // ── slabs (v1.2 H1) ───────────────────────────────────────────────────────

    @Test fun aSlabIsKeptPerSeqWithItsOperatorAndOneTexturePerTile() {
        val lines = store()
        lines.setSlab(paint, 3, tile(0), 31, "erase")
        lines.setSlab(paint, 3, tile(1), 32, "erase")
        lines.setSlab(paint, 5, tile(1), 51)
        assertEquals(listOf(3L, 5L), lines.slabSeqs(paint))
        val slab = assertNotNull(lines.slab(paint, 3))
        assertEquals("erase", slab.op)
        assertEquals(mapOf(tile(0) to 31, tile(1) to 32), slab.tiles)
        assertEquals("normal", lines.slab(paint, 5)?.op)
        assertNull(lines.slab(paint, 4))
        assertTrue(lines.hasSlabs(paint)); assertTrue(lines.hasItems(paint))
        assertTrue(lines.hasItemsOnLayer("paint")); assertFalse(lines.hasLinesOnLayer("paint"))
        assertEquals(setOf(paint), lines.itemPlanes())
        assertTrue(lines.linePlanes().isEmpty(), "a slab is not a line")
        assertEquals(setOf(31, 32, 51), lines.textures())
        assertTrue(lines.baseTextures().isEmpty())
    }

    @Test fun aSlabCrossesItsOwnTilesAndTheIndexListsItBesideTheLinesInSeqOrder() {
        val fake = Fake().apply { tilesOf["a"] = setOf(tile(0), tile(1)); tilesOf["b"] = setOf(tile(1)) }
        val lines = store(fake)
        lines.put(paint, "a", line("a", 1))
        lines.setSlab(paint, 2, tile(1), 21, "multiply")
        lines.setSlab(paint, 2, tile(4), 24, "multiply")
        lines.put(paint, "b", line("b", 3))
        assertEquals(listOf(1L, 2L, 3L), lines.seqsAt(paint, tile(1)))
        assertEquals(listOf("a", "b"), lines.linesAt(paint, tile(1)).map { it.id })
        val slabs = lines.slabsAt(paint, tile(1))
        assertEquals(listOf(2L), slabs.map { it.seq }); assertEquals(listOf("multiply"), slabs.map { it.op }); assertEquals(listOf(21), slabs.map { it.texture })
        assertTrue(lines.isCrossed(paint, tile(4)), "a slab alone crosses its tile")
        assertTrue(lines.linesAt(paint, tile(4)).isEmpty())
        assertEquals(setOf(tile(0), tile(1), tile(4)), lines.crossedKeys(paint))
        assertEquals(4L, lines.nextSeq(paint), "seqs are shared: the next is past the slab and the lines")
    }

    @Test fun nextSeqCountsSlabsToo() {
        val lines = store()
        lines.put(paint, "a", line("a", 1))
        lines.setSlab(paint, 7, tile(0), 70)
        assertEquals(8L, lines.nextSeq(paint))
        lines.setSlab(paint, 7, tile(0), null)
        assertEquals(2L, lines.nextSeq(paint))
    }

    @Test fun droppingEverySlabTileRemovesTheSlabAndAPlaneWithNothingLeftDisappears() {
        val lines = store()
        lines.setSlab(paint, 2, tile(0), 20); lines.setSlab(paint, 2, tile(1), 21)
        lines.setSlab(paint, 2, tile(0), null)
        assertEquals(mapOf(tile(1) to 21), lines.slab(paint, 2)?.tiles)
        assertFalse(lines.isCrossed(paint, tile(0)))
        lines.setSlab(paint, 2, tile(1), null)
        assertNull(lines.slab(paint, 2))
        assertTrue(lines.slabSeqs(paint).isEmpty())
        assertFalse(lines.hasItems(paint))
        assertTrue(lines.itemPlanes().isEmpty())
        lines.setSlab(paint, 9, tile(0), null)   // dropping what is not there is harmless
        assertFalse(lines.hasItems(paint))
    }

    @Test fun aLineAndASlabNeverShareASeq() {
        val lines = store()
        lines.put(paint, "a", line("a", 2))
        assertFailsWith<IllegalArgumentException> { lines.setSlab(paint, 2, tile(0), 20) }
        assertFalse(lines.hasSlabs(paint), "the refused slab left nothing behind")
        lines.setSlab(paint, 3, tile(0), 30)
        assertFailsWith<IllegalArgumentException> { lines.put(paint, "b", line("b", 3)) }
        assertNull(lines.record(paint, "b"))
        assertEquals(mapOf(tile(0) to 30), lines.slab(paint, 3)?.tiles)
        // A slab takes the seq of a line that has gone (Rasterize Down), and the line takes it back on undo.
        lines.put(paint, "a", null)
        lines.setSlab(paint, 2, tile(0), 20)
        assertFailsWith<IllegalArgumentException> { lines.put(paint, "a", line("a", 2)) }
        lines.setSlab(paint, 2, tile(0), null)
        lines.put(paint, "a", line("a", 2))
        assertEquals(listOf("a"), lines.lines(paint).map { it.id })
    }

    @Test fun aSlabSeqBelowOneAndAnUnknownOperatorAreRefused() {
        val lines = store()
        assertFailsWith<IllegalArgumentException> { lines.setSlab(paint, 0, tile(0), 1) }
        assertFailsWith<IllegalArgumentException> { lines.setSlab(paint, -3, tile(0), 1) }
        assertFailsWith<IllegalArgumentException> { lines.setSlab(paint, 2, tile(0), 1, "overlay") }
        assertFalse(lines.hasItems(paint))
        for (op in NativeLines.OPERATORS) lines.setSlab(paint, 2, tile(0), 5, op)
        assertEquals("screen".takeIf { "screen" in NativeLines.OPERATORS }, lines.slab(paint, 2)?.op?.takeIf { it == "screen" })
    }

    @Test fun theSlabReturnedIsACopy() {
        val lines = store()
        lines.setSlab(paint, 2, tile(0), 20)
        val copy = lines.slab(paint, 2)!!
        lines.setSlab(paint, 2, tile(1), 21)
        assertEquals(setOf(tile(0)), copy.tiles.keys)
        assertEquals(setOf(tile(0), tile(1)), lines.slab(paint, 2)!!.tiles.keys)
    }

    @Test fun planesKeepTheirSlabsApartAndRenameMovesThemWithTheLines() {
        val lines = store()
        lines.put(paint, "a", line("a", 1))
        lines.setSlab(paint, 2, tile(0), 20)
        lines.setBase(paint, tile(0), 11)
        lines.setSlab(other, 2, tile(0), 90)
        lines.rename(paint, shared)
        assertFalse(lines.hasItems(paint))
        assertEquals(listOf("a"), lines.lines(shared).map { it.id })
        assertEquals(mapOf(tile(0) to 20), lines.slab(shared, 2)?.tiles)
        assertEquals(11, lines.base(shared, tile(0)))
        assertTrue(lines.isCrossed(shared, tile(0)))
        assertEquals(mapOf(tile(0) to 90), lines.slab(other, 2)?.tiles)
        assertEquals(setOf(20, 90, 11), lines.textures())
    }

    // A Rasterize Down in NativeLines' terms: the line goes and a slab takes its seq, in one step; undo swaps them back.
    @Test fun aLineBecomingASlabAtItsOwnSeqAndBackLeavesTheIndexExact() {
        val fake = Fake().apply { tilesOf["a"] = setOf(tile(0), tile(1)) }
        val lines = store(fake)
        val a = line("a", 4)
        lines.put(paint, "a", a)
        lines.put(paint, "a", null)
        lines.setSlab(paint, 4, tile(0), 40, "behind"); lines.setSlab(paint, 4, tile(1), 41, "behind")
        assertEquals(listOf(4L), lines.seqsAt(paint, tile(1)))
        assertTrue(lines.lines(paint).isEmpty())
        assertTrue(lines.hasItems(paint))
        lines.setSlab(paint, 4, tile(0), null); lines.setSlab(paint, 4, tile(1), null)
        lines.put(paint, "a", a)
        assertEquals(listOf("a"), lines.linesAt(paint, tile(1)).map { it.id })
        assertFalse(lines.hasSlabs(paint))
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
        assertFalse(snapshot.holdsItems(shared)); assertTrue(snapshot.holdsItems(other))
    }

    @Test fun theSnapshotKnowsWhichPlanesHoldOnlySlabs() {
        val lines = store()
        lines.setSlab(shared, 2, tile(0), 20)
        lines.put(paint, "a", line("a", 1))
        val snapshot = lines.snapshot()
        assertTrue(snapshot.holdsItems(shared)); assertTrue(snapshot.holdsItems(paint))
        assertTrue(snapshot.holdsItemsOnLayer("paint")); assertFalse(snapshot.holdsItemsOnLayer("sketch"))
        assertFalse(snapshot.hasLines(shared), "a slab is not a line")
        assertEquals(setOf(paint, shared), snapshot.itemPlanes)
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

    @Test fun theRevisionGrowsWithEveryLineChangeSlabChangeRenameClearAndNewBrush() {
        val lines = store()
        var last = lines.revision
        fun grew(why: String) { assertTrue(lines.revision > last, why); last = lines.revision }
        lines.put(paint, "a", line("a", 1)); grew("a line was added")
        lines.put(paint, "a", line("a", 1, x = 9f)); grew("a line was replaced")
        lines.put(paint, "a", null); grew("a line was removed")
        lines.put(paint, "b", line("b", 2)); grew("another line was added")
        lines.setSlab(paint, 3, tile(0), 30); grew("a slab appeared (the UI mirror must learn the plane holds items)")
        lines.setSlab(paint, 3, tile(0), null); grew("a slab went")
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

    // A layer clear (GlPaintEngine.clearLayerWithLines), as NativeLines sees it: bases, lines and slabs go together and
    // come back together.
    @Test fun takingEveryItemAndBaseOfAPlaneLeavesNothingAndPuttingThemBackRestoresIt() {
        val fake = Fake().apply { tilesOf["a"] = setOf(tile(0), tile(1)); tilesOf["b"] = setOf(tile(1)) }
        val lines = store(fake)
        val a = line("a", 1); val b = line("b", 3)
        lines.put(paint, "a", a); lines.put(paint, "b", b)
        lines.setSlab(paint, 2, tile(1), 21, "erase"); lines.setSlab(paint, 2, tile(2), 22, "erase")
        lines.setBase(paint, tile(0), 11); lines.setBase(paint, tile(1), 12)

        // Clear: one step's worth of bases, lines and slabs go together.
        val records = lines.lines(paint)
        val bases = lines.bases(paint)
        val slab = assertNotNull(lines.slab(paint, 2))
        for ((key, _) in bases) lines.setBase(paint, key, null)
        for (key in slab.tiles.keys) lines.setSlab(paint, 2, key, null)
        for (r in records) lines.put(paint, r.id, null)
        assertTrue(lines.linePlanes().isEmpty())
        assertFalse(lines.hasItemsOnLayer("paint"))
        assertTrue(lines.textures().isEmpty())
        assertTrue(lines.snapshot().planes.isEmpty())
        assertEquals(1L, lines.nextSeq(paint))

        // Undo: the same bases, slabs and records come back, in seq order, with their footprints.
        for ((key, texture) in bases) lines.setBase(paint, key, texture)
        for ((key, texture) in slab.tiles) lines.setSlab(paint, 2, key, texture, slab.op)
        for (r in records.reversed()) lines.put(paint, r.id, r)
        assertEquals(listOf("a", "b"), lines.lines(paint).map { it.id })
        assertEquals(setOf(11, 12, 21, 22), lines.textures())
        assertEquals(11, lines.base(paint, tile(0))); assertEquals(12, lines.base(paint, tile(1)))
        assertTrue(lines.isCrossed(paint, tile(0)))
        assertEquals(setOf(tile(0), tile(1)), lines.footprint(paint, "a"))
        assertEquals(listOf(1L, 2L, 3L), lines.seqsAt(paint, tile(1)))
    }

    @Test fun clearForgetsLinesSlabsBasesBrushesAndTheWidenedMemory() {
        val fake = Fake().apply { tilesOf["a"] = setOf(tile(0)) }
        val lines = store(fake)
        lines.addBrushes(mapOf("pen" to pen()))
        val a = line("a", 1)
        lines.put(paint, "a", a)
        lines.widen(paint, "a", setOf(tile(8)))
        lines.setSlab(paint, 2, tile(0), 20)
        lines.setBase(paint, tile(0), 11)
        lines.clear()
        assertTrue(lines.linePlanes().isEmpty())
        assertTrue(lines.itemPlanes().isEmpty())
        assertNull(lines.base(paint, tile(0)))
        assertTrue(lines.textures().isEmpty())
        assertTrue(lines.brushes().isEmpty())
        assertEquals(1L, lines.nextSeq(paint))
        assertTrue(lines.snapshot().planes.isEmpty())
        val callsBefore = fake.calls.size
        lines.put(paint, "a", a)
        assertFalse(lines.isCrossed(paint, tile(8)), "the widened memory went with the drawing")
        assertEquals(callsBefore + 1, fake.calls.size, "putting the line again estimated it afresh")
    }
}
