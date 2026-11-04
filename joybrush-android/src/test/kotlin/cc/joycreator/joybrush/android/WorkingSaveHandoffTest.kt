package cc.joycreator.joybrush.android

import cc.joycreator.joybrush.core.io.SaveQueue
import cc.joycreator.joybrush.core.io.SaveReason
import cc.joycreator.joybrush.core.io.SaveTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Queue ordering tests for a pause handoff that must span all work already in the save queue. */
class WorkingSaveHandoffTest {
    private class Harness(private val synchronous: Boolean = false) {
        val barrier = WorkingSaveBarrier()
        val handoff = WorkingSaveHandoff(barrier)
        val started = mutableListOf<String>()
        val events = mutableListOf<String>()
        val pending = mutableListOf<(String?) -> Unit>()
        var strokeInProgress = false
        lateinit var queue: SaveQueue<String>

        init {
            queue = SaveQueue(object : SaveTarget<String> {
                override val strokeInProgress: Boolean get() = this@Harness.strokeInProgress

                override fun start(reason: SaveReason, destination: String, finished: (String?) -> Unit) {
                    started += destination
                    if (synchronous) {
                        completeOperation(destination, null)
                        finishThroughQueue(destination, null, finished)
                    } else {
                        pending += { problem ->
                            completeOperation(destination, problem)
                            finishThroughQueue(destination, problem, finished)
                        }
                    }
                }
            })
        }

        private fun completeOperation(destination: String, problem: String?) {
            events += "done:$destination:${problem ?: "ok"}"
        }

        private fun finishThroughQueue(destination: String, problem: String?, finished: (String?) -> Unit) {
            if (destination.startsWith("working")) handoff.queueAdvanced(problem, drained = false)
            finished(problem) // may synchronously start the next queued operation
            handoff.queueAdvanced(null, drained = !queue.busy && queue.pending == 0)
        }

        fun request(reason: SaveReason, destination: String) = queue.request(reason, destination)

        fun finishNext(problem: String? = null) {
            pending.removeAt(0).invoke(problem)
        }

        fun pause(path: String = "same-drawing") {
            handoff.requested(path) // Activity registers before asking SaveQueue
            queue.request(SaveReason.IDLE, "working-pause")
        }
    }

    @Test
    fun pauseHandoffSpansPriorWorkingCopyOpenAndFinalWorkingSaveInQueueOrder() {
        val h = Harness()
        val reads = mutableListOf<String?>()
        h.request(SaveReason.EXPLICIT, "copy-in-flight")
        h.request(SaveReason.IDLE, "working-A")
        h.request(SaveReason.EXPLICIT, "open")
        h.pause()
        h.barrier.whenSettled("same-drawing") { reads += it; h.events += "read" }

        assertEquals(listOf("copy-in-flight"), h.started)
        h.finishNext()
        assertEquals(listOf("copy-in-flight", "working-A"), h.started)
        h.finishNext()
        assertEquals(listOf("copy-in-flight", "working-A", "open"), h.started)
        assertTrue("the earlier Working save must not settle the pause handoff", reads.isEmpty())
        h.finishNext()
        assertEquals(listOf("copy-in-flight", "working-A", "open", "working-pause"), h.started)
        assertTrue("the queued Open must also finish before the read starts", reads.isEmpty())
        h.finishNext()

        assertEquals(listOf(null), reads)
        assertEquals("read", h.events.last())
        assertFalse(h.queue.busy)
        assertEquals(0, h.queue.pending)
    }

    @Test
    fun pauseIdleMergesWithWaitingIdleAndRepeatedPauseUsesOneTicket() {
        val h = Harness()
        h.request(SaveReason.EXPLICIT, "copy-in-flight")
        h.request(SaveReason.IDLE, "working-A")
        h.pause()
        h.pause() // repeated pause reuses the ticket and merges into the same waiting idle
        var settled = 0
        h.barrier.whenSettled("same-drawing") { assertNull(it); settled++ }

        assertEquals(1, h.queue.pending)
        h.finishNext()
        assertEquals(listOf("copy-in-flight", "working-pause"), h.started)
        assertEquals(0, settled)
        h.finishNext()

        assertEquals(1, settled)
        assertEquals(0, h.queue.pending)
    }

    @Test
    fun failureInEarlierWorkingSaveIsRetainedUntilLaterQueuedWorkDrains() {
        val h = Harness()
        h.request(SaveReason.EXPLICIT, "copy-in-flight")
        h.request(SaveReason.IDLE, "working-A")
        h.request(SaveReason.EXPLICIT, "open")
        h.pause()
        val outcomes = mutableListOf<String?>()
        h.barrier.whenSettled("same-drawing") { outcomes += it }

        h.finishNext()
        h.finishNext("snapshot failed")
        assertTrue("the handoff remains pending while Open and pause save are queued", outcomes.isEmpty())
        h.finishNext()
        assertTrue("Open completion alone does not drain the final pause save", outcomes.isEmpty())
        h.finishNext()

        assertEquals(listOf("snapshot failed"), outcomes)
    }

    @Test
    fun destroyedActivitySaveThatFailsBeforeSnapshotStillSettlesHandoffAfterQueueDrains() {
        val h = Harness()
        h.strokeInProgress = true
        h.handoff.requested("same-drawing")
        h.request(SaveReason.IDLE, "working-pause")
        var outcome: String? = "waiting"
        h.barrier.whenSettled("same-drawing") { outcome = it }
        assertTrue("startup/loading gate keeps the queued save from starting", h.started.isEmpty())

        // onDestroy clears the activity's loading gate and drains the queue; beginSave on the
        // destroyed Activity completes the queued request with its closed-screen failure.
        h.strokeInProgress = false
        h.queue.drain()
        assertEquals(listOf("working-pause"), h.started)
        h.finishNext("screen closed")

        assertEquals("screen closed", outcome)
        assertEquals(0, h.queue.pending)
    }

    @Test
    fun synchronousQueueCompletionsCannotReleaseBeforeTheLastQueuedSave() {
        val h = Harness(synchronous = true)
        h.strokeInProgress = true
        h.request(SaveReason.EXPLICIT, "copy-in-flight")
        h.request(SaveReason.IDLE, "working-A")
        h.request(SaveReason.EXPLICIT, "open")
        h.pause()
        val outcomes = mutableListOf<String?>()
        h.barrier.whenSettled("same-drawing") { outcomes += it; h.events += "read" }
        assertTrue(outcomes.isEmpty())
        h.strokeInProgress = false
        h.queue.drain()

        assertEquals(listOf(null), outcomes)
        assertEquals("read", h.events.last())
        assertEquals(listOf("copy-in-flight", "working-A", "open", "working-pause"), h.started)
    }
}
