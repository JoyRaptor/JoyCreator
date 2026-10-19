package cc.joycreator.joybrush.androidkit.gl.lines

import kotlin.test.*

class LineEditBudgetTest {
    @Test fun aTileIsAnRgba8SquareAndTheLimitIs192Mib() {
        assertEquals(262_144L, LineEditBudget.TILE_BYTES)
        assertEquals(201_326_592L, LineEditBudget.LIMIT_BYTES)
    }

    @Test fun theEstimateCountsUnitTilesAndComposedTilesAtOneTileEach() {
        assertEquals(0L, LineEditBudget.bytes(0, 0))
        assertEquals(10L * 262_144, LineEditBudget.bytes(6, 4))
    }

    @Test fun exactlyTheLimitFitsAndOneTileMoreIsRefusedInWords() {
        val tiles = LineEditBudget.LIMIT_BYTES / LineEditBudget.TILE_BYTES   // 768
        assertEquals(768L, tiles)
        assertNull(LineEditBudget.refusal(LineEditBudget.bytes(tiles - 100, 100)))
        assertEquals("That's too many lines to change at once on this phone; choose fewer.",
            LineEditBudget.refusal(LineEditBudget.bytes(tiles - 100, 101)))
    }

    @Test fun aHugeEditDoesNotOverflow() {
        assertNotNull(LineEditBudget.refusal(LineEditBudget.bytes(Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong())))
    }
}
