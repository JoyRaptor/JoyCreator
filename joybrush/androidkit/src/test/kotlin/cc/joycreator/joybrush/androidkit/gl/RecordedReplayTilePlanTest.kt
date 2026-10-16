package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordedReplayTilePlanTest {
    @Test fun matchesExistingAllocatorForNormalNegativeEdgeAndAnchoredDabs() {
        val dabs = listOf(
            Dab(x = 12.5f, y = 19.25f, radius = 3f, anchor = 0.6f),
            Dab(x = -4.5f, y = 11f, radius = 2f, anchor = 0.25f),
            Dab(x = 520f, y = -260f, radius = 4f, anchor = 1f),
            Dab(x = 0f, y = 0f, radius = 0f),
        )

        assertEquals(Tiles.bucket(dabs).keys, RecordedReplayTilePlan.keys(dabs, maxTiles = 32))
        assertEquals(Tiles.bucket(dabs, size = 4).keys, RecordedReplayTilePlan.keys(dabs, maxTiles = 64, tileSize = 4))
    }

    @Test fun repeatedDabsHaveNoSampleCountCapAndReturnImmutableSnapshot() {
        val dab = Dab(x = 100f, y = 100f, radius = 0f)
        val keys = RecordedReplayTilePlan.keys(List(16_385) { dab }, maxTiles = 1)
        assertEquals(Tiles.touchedBy(dab).toSet(), keys)
        assertFailsWith<UnsupportedOperationException> { (keys as MutableSet<Long>).add(Tiles.key(20, 20)) }
    }

    @Test fun emptyInputAllowsZeroBudgetAndInvalidArgumentsAreRejected() {
        assertTrue(RecordedReplayTilePlan.keys(emptyList(), maxTiles = 0).isEmpty())
        assertFailsWith<IllegalArgumentException> { RecordedReplayTilePlan.keys(emptyList(), maxTiles = -1) }
        assertFailsWith<IllegalArgumentException> { RecordedReplayTilePlan.keys(emptyList(), maxTiles = 0, tileSize = 0) }
        assertFailsWith<IllegalArgumentException> { RecordedReplayTilePlan.keys(listOf(Dab(1f, 1f, 0f)), maxTiles = 0) }
    }

    @Test fun invalidDabGeometryAndOversizedFootprintsRefuseBeforeEnumeration() {
        val invalid = listOf(
            Dab(Float.NaN, 1f, 1f), Dab(1f, Float.POSITIVE_INFINITY, 1f),
            Dab(1f, 1f, Float.NaN), Dab(1f, 1f, -1f),
        )
        for (dab in invalid) assertFailsWith<IllegalArgumentException> {
            RecordedReplayTilePlan.keys(listOf(dab), maxTiles = 64)
        }
        assertFailsWith<IllegalArgumentException> {
            RecordedReplayTilePlan.keys(listOf(Dab(0f, 0f, 100f)), maxTiles = 8, tileSize = 4)
        }
    }

    @Test fun tileUnionBudgetIsCheckedAcrossDabs() {
        val dabs = listOf(Dab(0f, 0f, 0f), Dab(512f, 0f, 0f))
        val expected = Tiles.bucket(dabs).keys
        assertEquals(expected, RecordedReplayTilePlan.keys(dabs, maxTiles = expected.size))
        assertFailsWith<IllegalArgumentException> {
            RecordedReplayTilePlan.keys(dabs, maxTiles = expected.size - 1)
        }
    }

    @Test fun fullTileRangesMustRemainInsideIntPixelAddressSpace() {
        val highValid = Dab(Int.MAX_VALUE.toFloat() - 256f, 0f, 0f)
        val lowValid = Dab(Int.MIN_VALUE.toFloat() + 256f, 0f, 0f)
        val edgeKeys = RecordedReplayTilePlan.keys(listOf(highValid, lowValid), maxTiles = 16)
        assertTrue(edgeKeys.all { Tiles.tx(it) in -8_388_608..8_388_607 })

        assertFailsWith<IllegalArgumentException> {
            RecordedReplayTilePlan.keys(listOf(Dab(Float.MAX_VALUE, 0f, 0f)), maxTiles = 16)
        }
        assertFailsWith<IllegalArgumentException> {
            RecordedReplayTilePlan.keys(listOf(Dab(Int.MAX_VALUE.toFloat(), 0f, 0f)), maxTiles = 16)
        }
        assertEquals(setOf(Tiles.key(-8_388_608, 0)),
            RecordedReplayTilePlan.keys(listOf(Dab(Int.MIN_VALUE.toFloat(), 0f, 0f)), maxTiles = 1))
        assertFailsWith<IllegalArgumentException> {
            RecordedReplayTilePlan.keys(listOf(Dab(Int.MIN_VALUE.toFloat() - 512f, 0f, 0f)), maxTiles = 16)
        }
        assertFalse(edgeKeys.isEmpty())
    }
}
