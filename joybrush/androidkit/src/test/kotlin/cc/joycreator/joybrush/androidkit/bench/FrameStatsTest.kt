package cc.joycreator.joybrush.androidkit.bench

import kotlin.test.Test
import kotlin.test.assertEquals

class FrameStatsTest {
    @Test
    fun fullSpanIncludesEveryRecordedFrameFromDownAndUsesLastGpuFinishedFrame() {
        val span = FrameStats.fullSpan(
            startsNs = listOf(0L, 10_000_000L, 20_000_000L, 30_000_000L),
            endsNs = listOf(9_000_000L, 19_000_000L, 29_000_000L, 39_000_000L),
            busyMs = listOf(100.0, 20.0, 40.0, 80.0),
            downNs = 8_000_000L,
            upNs = 21_000_000L,
        )

        assertEquals(listOf(1, 2, 3), span.frameIndices)
        assertEquals(FrameStats.Pct(40.0, 40.0, 80.0, 46.666666666666664), span.busy)
        // This is the last recorded GPU-finished frame after UP, not a claim about the exact fade commit instant.
        assertEquals(18.0, span.finalRecordedFrameEndAfterUpMs)
    }

    @Test
    fun fullSpanWithoutUpHasNoInventedUpRelativeTimestamp() {
        val span = FrameStats.fullSpan(
            startsNs = listOf(10_000_000L),
            endsNs = listOf(12_000_000L),
            busyMs = listOf(2.0),
            downNs = 0L,
            upNs = 0L,
        )

        assertEquals(-1.0, span.finalRecordedFrameEndAfterUpMs)
    }
}
