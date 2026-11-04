package cc.joycreator.joybrush.android

import cc.joycreator.joybrush.core.io.SaveQueue
import cc.joycreator.joybrush.core.io.SaveReason
import cc.joycreator.joybrush.core.io.SaveTarget
import org.junit.Test
import org.junit.Assert.*

/** Deterministic lifecycle tests for the process-wide handoff between canvas snapshots and disk reads. */
class WorkingSaveBarrierTest {
    private class FakeDisk {
        val files = mutableMapOf<String, List<String>>()
        val reads = mutableListOf<String>()

        fun read(path: String): List<String>? {
            reads += path
            return files[path]
        }
    }

    @Test
    fun delayedOutgoingSnapshotQueuesWriteBeforeReopenAndBothEditsSurvive() {
        val path = "/drawings/current.joybrush"
        val disk = FakeDisk().apply { files[path] = listOf("before outgoing edit") }
        val barrier = WorkingSaveBarrier()
        val outgoingTicket = barrier.begin(path) // register before the GL snapshot is requested
        var snapshot: (() -> Unit)? = null
        var writeFinished: (() -> Unit)? = null
        var restored: List<String>? = null
        var reopenProblem: String? = null

        // Model the delayed GL readback, then its asynchronous archive write.
        snapshot = {
            val outgoingContents = listOf("before outgoing edit", "outgoing edit")
            writeFinished = {
                disk.files[path] = outgoingContents
                outgoingTicket.complete(null)
            }
        }
        barrier.whenSettled(path) { problem ->
            reopenProblem = problem
            if (problem == null) restored = disk.read(path)
        }

        assertTrue("restore must wait for the snapshot and its queued write", disk.reads.isEmpty())
        snapshot!!.invoke()
        assertTrue("snapshot completion only queues the write", disk.reads.isEmpty())
        writeFinished!!.invoke()

        assertNull(reopenProblem)
        assertEquals(listOf("before outgoing edit", "outgoing edit"), restored)
        val reopenedEdits = restored!!.toMutableList().also { it += "reopened edit" }
        disk.files[path] = reopenedEdits
        assertEquals(listOf("before outgoing edit", "outgoing edit", "reopened edit"), disk.read(path))
    }

    @Test
    fun pauseBarrierRegisteredBeforeSaveQueueRequestWaitsBehindExistingSave() {
        val path = "/drawings/current.joybrush"
        val disk = FakeDisk().apply { files[path] = listOf("old") }
        val barrier = WorkingSaveBarrier()
        val oldWriteCompletion = mutableListOf<() -> Unit>()
        val target = object : SaveTarget<String> {
            override val strokeInProgress = false
            override fun start(reason: SaveReason, destination: String, finished: (String?) -> Unit) {
                oldWriteCompletion += { finished(null) }
            }
        }
        val queue = SaveQueue(target)
        queue.request(SaveReason.EXPLICIT, "copy already in progress")

        val pauseTicket = barrier.begin(path) // onPause registers before requesting the queued save
        queue.request(SaveReason.IDLE, "working")
        var read: List<String>? = null
        barrier.whenSettled(path) { if (it == null) read = disk.read(path) }
        assertTrue(read == null)

        oldWriteCompletion.removeAt(0).invoke()
        assertTrue(read == null)
        oldWriteCompletion.removeAt(0).invoke() // queued working save has now started/finished
        pauseTicket.complete(null)

        assertEquals(listOf("old"), read)
    }

    @Test
    fun samePathTicketsJoinButOtherPathSettlesIndependently() {
        val barrier = WorkingSaveBarrier()
        val first = barrier.begin("same")
        val second = barrier.begin("same")
        val unrelated = barrier.begin("other")
        val events = mutableListOf<String>()
        barrier.whenSettled("same") { events += "same:$it" }
        barrier.whenSettled("other") { events += "other:$it" }

        unrelated.complete(null)
        assertEquals(listOf("other:null"), events)
        first.complete(null)
        assertEquals("one same-path save cannot release the joined read", listOf("other:null"), events)
        second.complete(null)
        assertEquals(listOf("other:null", "same:null"), events)
    }

    @Test
    fun cancelledOrDestroyedWaiterDoesNotRunWhenSaveSettles() {
        val barrier = WorkingSaveBarrier()
        val ticket = barrier.begin("drawing")
        var callbackCount = 0
        val waiter = barrier.whenSettled("drawing") { callbackCount++ }

        waiter.close() // equivalent to the Activity being destroyed while waiting
        ticket.complete(null)
        assertEquals(0, callbackCount)
    }

    @Test
    fun snapshotTimeoutReleasesOnceAndLateSnapshotCompletionCannotChangeOutcome() {
        val barrier = WorkingSaveBarrier()
        val ticket = barrier.begin("drawing")
        val outcomes = mutableListOf<String?>()
        barrier.whenSettled("drawing") { outcomes += it }

        ticket.complete("the drawing did not answer in time")
        ticket.complete(null) // late GL callback after timeout
        assertEquals(listOf("the drawing did not answer in time"), outcomes)
        var retryOutcome: String? = "unset"
        barrier.whenSettled("drawing") { retryOutcome = it }
        assertEquals("the drawing did not answer in time", retryOutcome)
    }

    @Test
    fun failureIsStickyForReopenUntilANewRetryCompletesSuccessfully() {
        val barrier = WorkingSaveBarrier()
        barrier.begin("drawing").complete("disk full")
        var staleReadStarted = false
        var failure: String? = null
        barrier.whenSettled("drawing") {
            failure = it
            staleReadStarted = it == null
        }
        assertEquals("disk full", failure)
        assertFalse("a failed outgoing save must not permit reading the older archive", staleReadStarted)

        val retry = barrier.begin("drawing")
        var retryResult: String? = "waiting"
        barrier.whenSettled("drawing") { retryResult = it }
        assertEquals("waiting", retryResult)
        retry.complete(null)
        assertNull(retryResult)
    }

    @Test
    fun oneFailureWhileAnotherSaveIsPendingStopsEveryJoinedWaiter() {
        val barrier = WorkingSaveBarrier()
        val failed = barrier.begin("drawing")
        val pending = barrier.begin("drawing")
        val results = mutableListOf<String?>()
        barrier.whenSettled("drawing") { results += it }
        barrier.whenSettled("drawing") { results += it }

        failed.complete("write failed")
        assertTrue("waiters remain held while another same-path save is pending", results.isEmpty())
        pending.complete(null)
        assertEquals(listOf("write failed", "write failed"), results)
    }

    @Test fun retryStartedByFirstWaiterCannotChangeOtherWaitersFailure() {
        val barrier = WorkingSaveBarrier()
        val failed = barrier.begin("drawing")
        val results = mutableListOf<String?>()
        barrier.whenSettled("drawing") {
            results += it
            barrier.begin("drawing").complete(null)
        }
        barrier.whenSettled("drawing") { results += it }
        failed.complete("write failed")
        assertEquals(listOf("write failed", "write failed"), results)
    }
}
