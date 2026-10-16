package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Pure peak arithmetic only; no GL allocation, runtime ownership inventory or budget claim. */
class RecordedWorkingTilePlanTest {
    @Test fun replayCountsCanonicalSourcesPlacedOutputsAndPotentialEmptyHelper() {
        val area = Tiles.SIZE.toLong() * Tiles.SIZE
        assertEquals(40L * area, RecordedWorkingTilePlan.replay(1))
        assertEquals(112L * area, RecordedWorkingTilePlan.replay(3, 2))
        assertEquals(5_376L, RecordedWorkingTilePlan.replay(2, 3, tileSize = 8))
        assertEquals(12L, RecordedWorkingTilePlan.replay(0, 1, tileSize = 1))
    }

    @Test fun composeCountsWholeBatchAndOnePairOfFloatScratchTiles() {
        val area = Tiles.SIZE.toLong() * Tiles.SIZE
        assertEquals(36L * area, RecordedWorkingTilePlan.compose(1))
        assertEquals(44L * area, RecordedWorkingTilePlan.compose(3))
        assertEquals(3_072L, RecordedWorkingTilePlan.compose(4, tileSize = 8))
        assertEquals(40L, RecordedWorkingTilePlan.compose(2, tileSize = 1))
    }

    @Test fun emptyReplayRetainsHelperAllowanceWhileEmptyComposeAllocatesNothing() {
        assertEquals(128L, RecordedWorkingTilePlan.replay(0, 0, tileSize = 4))
        assertEquals(0L, RecordedWorkingTilePlan.compose(0, tileSize = 4))
        assertEquals(0L, RecordedWorkingTilePlan.compose(0, tileSize = Int.MAX_VALUE))
    }

    @Test fun negativeCountsAndNonpositiveSizesRefuseEvenForEmptyWork() {
        assertFailsWith<IllegalArgumentException> { RecordedWorkingTilePlan.replay(-1) }
        assertFailsWith<IllegalArgumentException> { RecordedWorkingTilePlan.replay(1, -1) }
        assertFailsWith<IllegalArgumentException> { RecordedWorkingTilePlan.compose(-1) }
        for (size in listOf(0, -1, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { RecordedWorkingTilePlan.replay(0, 0, size) }
            assertFailsWith<IllegalArgumentException> { RecordedWorkingTilePlan.compose(0, size) }
        }
    }

    @Test fun replayChecksMultiplicationAndAdditionInsteadOfWrapping() {
        assertFailsWith<ArithmeticException> { RecordedWorkingTilePlan.replay(1, tileSize = Int.MAX_VALUE) }
        assertFailsWith<ArithmeticException> { RecordedWorkingTilePlan.replay(Int.MAX_VALUE, tileSize = 100_000) }
        // Each source/destination product fits; adding them exceeds Long.MAX_VALUE.
        assertFailsWith<ArithmeticException> { RecordedWorkingTilePlan.replay(200_000, 750_000, 1_000_000) }
        assertFailsWith<ArithmeticException> { RecordedWorkingTilePlan.replay(0, 0, Int.MAX_VALUE) }
    }

    @Test fun composeChecksOutputScratchAndCombinedPeakOverflow() {
        assertFailsWith<ArithmeticException> { RecordedWorkingTilePlan.compose(Int.MAX_VALUE, 100_000) }
        assertFailsWith<ArithmeticException> { RecordedWorkingTilePlan.compose(1, Int.MAX_VALUE) }
        // 2e18 output bytes and 8e18 scratch bytes separately fit; their sum does not.
        assertFailsWith<ArithmeticException> { RecordedWorkingTilePlan.compose(2, 500_000_000) }
    }
}
