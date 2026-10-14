package cc.joycreator.joybrush.android

import cc.joycreator.joybrush.core.io.SaveQueue
import cc.joycreator.joybrush.core.io.SaveReason
import cc.joycreator.joybrush.core.io.SaveTarget
import org.junit.Assert.*
import org.junit.Test

class StrokeSaveClockTest {
    @Test fun repeatedChangesNeverRestartTheMinuteDeadline() {
        val clock = StrokeSaveClock(1_000)
        for (now in 2_000L..60_000L step 1_000) assertFalse(clock.due(now, true, true, false))
        assertTrue(clock.due(61_000, true, true, false))
    }

    @Test fun cleanBenchRecoveryAndBusyQueueNeverRequestAnOverdueSave() {
        val clock = StrokeSaveClock(0)
        assertFalse(clock.due(120_000, false, true, false))
        assertFalse(clock.due(120_000, true, false, false))
        assertFalse(clock.due(120_000, true, true, true))
        assertTrue(clock.due(120_000, true, true, false))
    }

    @Test fun failedAttemptWaitsAMinuteInsteadOfToastingOnEveryStroke() {
        val clock = StrokeSaveClock(0)
        assertTrue(clock.due(60_000, true, true, false))
        clock.attempted(60_000)
        assertFalse(clock.due(60_001, true, true, false))
        assertFalse(clock.due(119_999, true, true, false))
        assertTrue(clock.due(120_000, true, true, false))
    }

    @Test fun successfulWorkingSaveSetsDeadlineEvenWhenNewerEditsRemainDirty() {
        val clock = StrokeSaveClock(0)
        clock.attempted(60_000)
        clock.saved(75_000)
        assertFalse(clock.due(134_999, true, true, false))
        assertTrue(clock.due(135_000, true, true, false))
    }

    @Test fun loadingAnotherDrawingResetsDeadlineAndUsesMonotonicTime() {
        val clock = StrokeSaveClock(10_000)
        assertFalse(clock.due(9_999, true, true, false))
        clock.reset(120_000)
        assertFalse(clock.due(179_999, true, true, false))
        assertTrue(clock.due(180_000, true, true, false))
    }

    @Test fun overdueRequestWaitsForStrokeCommitAndRetainsExplicitCopyOrder() {
        val clock = StrokeSaveClock(0)
        var penDown = true
        val writes = mutableListOf<String>()
        var answer: ((String?) -> Unit)? = null
        val queue = SaveQueue(object : SaveTarget<String> {
            override val strokeInProgress: Boolean get() = penDown
            override fun start(reason: SaveReason, destination: String, finished: (String?) -> Unit) {
                writes += destination
                if (destination == "working") clock.attempted(60_000)
                answer = finished
            }
        })
        queue.request(SaveReason.EXPLICIT, "copy")
        assertFalse(clock.due(60_000, true, true, queue.busy || queue.pending > 0))
        penDown = false
        queue.strokeFinished()
        assertEquals(listOf("copy"), writes)
        assertFalse(clock.due(60_000, true, true, queue.busy || queue.pending > 0))
        answer!!(null)
        assertTrue(clock.due(60_000, true, true, queue.busy || queue.pending > 0))
        queue.request(SaveReason.IDLE, "working")
        assertEquals(listOf("copy", "working"), writes)
        assertFalse(clock.due(60_001, true, true, queue.busy || queue.pending > 0))
    }
}
