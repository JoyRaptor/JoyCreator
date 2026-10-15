package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.*

class RecordedTilePlanTest {
    private fun record(id: String = "line", seq: Long = 1L) = StrokeRecord(
        id, "frozen-brush", 7L, 0f, 1f,
        listOf(PenSample(10f, 10f, 0.0), PenSample(20f, 20f, 0.1)), seq = seq,
    )
    private fun address(id: String = "line", layer: String = "layer", cel: String? = "frame") =
        LineAddress(layer, cel, id)
    private fun slab(seq: Long, texture: Int, op: String = "normal") =
        RecordedCel.PixelSlab(seq, UndoLog.SlabValue(texture, op))
    private fun plan(records: List<StrokeRecord>, footprints: Map<LineAddress, Set<Long>>,
                     slabs: Map<Long, List<RecordedCel.PixelSlab<Int>>> = emptyMap()) =
        RecordedTilePlan(RecordedCel("layer", "frame", records, slabs), footprints)

    @Test fun sameRecordIdUsesFullLayerAndCelAddress() {
        val footprints = mapOf(
            address() to setOf(11L), address(layer = "other") to setOf(22L),
            address(cel = null) to setOf(33L), address(cel = "other-frame") to setOf(44L),
        )
        for ((owner, expected) in footprints) {
            val p = RecordedTilePlan(RecordedCel<Int>(owner.layerId, owner.celId, listOf(record())), footprints)
            assertEquals(expected, p.tileKeys)
            assertEquals(owner, (p.itemsOn(expected.single()).single() as RecordedTilePlan.Line).address)
        }
    }

    @Test fun sparseActualUnitKeysDoNotExpandToStrokeBoundingRectangle() {
        val left = Tiles.key(-1000, 5)
        val right = Tiles.key(1000, 5)
        val p = plan(listOf(record()), mapOf(address() to setOf(left, right)))
        assertEquals(setOf(left, right), p.tileKeys)
        assertTrue(p.itemsOn(Tiles.key(0, 5)).isEmpty())
        assertEquals(1, p.itemsOn(left).size)
        assertEquals(1, p.itemsOn(right).size)
    }

    @Test fun missingFootprintRefusesEvenWhenSameIdExistsInAnotherScope() {
        val error = assertFailsWith<RecordedTilePlanException> {
            plan(listOf(record()), mapOf(address(layer = "other") to setOf(1L)))
        }
        assertEquals(address(), error.address)
        assertTrue(error.message!!.contains("Missing recorded-line tile footprint"))
        assertTrue(error.message!!.contains("frame"))
    }

    @Test fun explicitEmptyFootprintSupportsNoDabRecordWithoutInventingContent() {
        val noDabs = record().copy(samples = emptyList())
        val p = plan(listOf(noDabs), mapOf(address() to emptySet()))
        assertTrue(p.tileKeys.isEmpty())
        assertTrue(p.itemsOn(0L).isEmpty())
        assertFailsWith<RecordedTilePlanException> { plan(listOf(noDabs), emptyMap()) }
    }

    @Test fun allSlabOperatorsStayInStrictSequenceAmongWholeLineUnits() {
        val early = record("early", 2L)
        val late = record("late", 6L)
        val p = plan(listOf(late, early), mapOf(address("late") to setOf(1L), address("early") to setOf(1L)),
            mapOf(1L to listOf(slab(7L, 107, "screen"), slab(3L, 103, "erase"),
                slab(0L, 100), slab(5L, 105, "multiply"), slab(4L, 104, "behind"))))
        val items = p.itemsOn(1L)
        assertEquals(listOf(0L, 2L, 3L, 4L, 5L, 6L, 7L), items.map { it.seq })
        assertEquals(listOf("normal", "erase", "behind", "multiply", "screen"),
            items.filterIsInstance<RecordedTilePlan.Pixel<Int>>().map { it.value.op })
        assertEquals(listOf(early, late), items.filterIsInstance<RecordedTilePlan.Line>().map { it.record })
        assertEquals(listOf(100, 103, 104, 105, 107),
            items.filterIsInstance<RecordedTilePlan.Pixel<Int>>().map { it.value.texture })
    }

    @Test fun deletedLineAndSlabInvalidateOldTilesEvenWhenNewPlanIsEmpty() {
        val old = plan(listOf(record()), mapOf(address() to setOf(1L, 2L)), mapOf(3L to listOf(slab(0L, 103))))
        val empty = plan(emptyList(), emptyMap())
        assertEquals(setOf(1L, 2L, 3L), RecordedTilePlan.affectedTiles(old, empty))
        assertTrue(empty.itemsOn(1L).isEmpty())
        assertEquals(old.tileKeys, RecordedTilePlan.affectedTiles(old, null))
        assertEquals(old.tileKeys, RecordedTilePlan.affectedTiles(null, old))
    }

    @Test fun editedLineInvalidatesOldAndNewFootprintsButExcludesUnchangedDistantLines() {
        val changed = record()
        val distant = record("distant", 2L)
        val old = plan(listOf(changed, distant), mapOf(address() to setOf(1L, 2L), address("distant") to setOf(999L)))
        val new = plan(listOf(changed.copy(widthScale = 2f), distant),
            mapOf(address() to setOf(2L, 3L), address("distant") to setOf(999L)))
        assertEquals(setOf(1L, 2L, 3L), RecordedTilePlan.affectedTiles(old, new))
        assertTrue(RecordedTilePlan.affectedTiles(old, old).isEmpty())
    }

    @Test fun footprintChangeAloneInvalidatesBothSidesWithIdenticalRawRecord() {
        val r = record()
        val old = plan(listOf(r), mapOf(address() to setOf(1L)))
        val new = plan(listOf(r), mapOf(address() to setOf(2L)))
        assertEquals(setOf(1L, 2L), RecordedTilePlan.affectedTiles(old, new))
    }

    @Test fun slabTextureOperatorAndSequenceChangesAreComparedWithoutInvalidatingOtherTiles() {
        val old = plan(emptyList(), emptyMap(), mapOf(
            1L to listOf(slab(1L, 101)), 2L to listOf(slab(2L, 102)),
            3L to listOf(slab(3L, 103)), 4L to listOf(slab(4L, 104))))
        val new = plan(emptyList(), emptyMap(), mapOf(
            1L to listOf(slab(1L, 201)), 2L to listOf(slab(2L, 102, "erase")),
            3L to listOf(slab(5L, 103)), 4L to listOf(slab(4L, 104))))
        assertEquals(setOf(1L, 2L, 3L), RecordedTilePlan.affectedTiles(old, new))
    }

    @Test fun captureOwnsInputCollectionsAndSamplesWhileBorrowingSlabTexture() {
        val r = record()
        val inputSamples = r.samples.toMutableList()
        val records = mutableListOf(r.copy(samples = inputSamples))
        val keys = mutableSetOf(1L, 2L)
        val footprints = mutableMapOf(address() to keys)
        val slabs = mutableListOf(slab(0L, 100))
        val cel = RecordedCel("layer", "frame", records, mutableMapOf(1L to slabs))
        val p = RecordedTilePlan(cel, footprints)
        val captured = (p.itemsOn(2L).single() as RecordedTilePlan.Line).record
        assertNotSame(cel.records.single().samples, captured.samples)
        records.clear()
        keys.clear()
        footprints.clear()
        slabs.clear()
        // The input alias remains mutable; the cel now also enforces its read-only contract.
        inputSamples.clear()
        assertFails { (cel.records.single().samples as MutableList<PenSample>).clear() }
        assertEquals(setOf(1L, 2L), p.tileKeys)
        assertEquals(r, captured)
        assertEquals(100, (p.itemsOn(1L).first() as RecordedTilePlan.Pixel<Int>).value.texture)
    }

    @Test fun invalidationRefusesCrossCelComparisons() {
        val shared = RecordedTilePlan(RecordedCel<Int>("layer", null), emptyMap())
        val frame = plan(emptyList(), emptyMap())
        assertFailsWith<IllegalArgumentException> { RecordedTilePlan.affectedTiles(shared, frame) }
    }

    @Test fun capturedRecipeBitsInvalidateAndPlanRecordsCannotBeMutated() {
        val r = record().copy(capturedPhysicalSource = cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource(
            cc.joycreator.joybrush.core.stroke.CaptureDomain()), samples = listOf(
            PenSample(10f, 10f, 0.0, tilt = Float.fromBits(0x7fc00001))))
        val changed = r.copy(samples = listOf(r.samples.single().copy(tilt = Float.fromBits(0x7fc00002))))
        val before = plan(listOf(r), mapOf(address() to setOf(1L)))
        val after = plan(listOf(changed), mapOf(address() to setOf(1L)))
        assertEquals(setOf(1L), RecordedTilePlan.affectedTiles(before, after))
        val borrowed = (before.itemsOn(1L).single() as RecordedTilePlan.Line).record
        assertFails { (borrowed.samples as MutableList<PenSample>).clear() }
        assertEquals(0x7fc00001, borrowed.samples.single().tilt.toRawBits())
    }
}
