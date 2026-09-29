package cc.joycreator.joybrush.core.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JB-2.15 / the three MAJORs of the JB-0.08b review, one test each and named for the finding.
 *
 * Every test drives a [FakeTarget] whose saves finish ONLY when the test says so, which is how a
 * real save behaves (a GL readback then a file write) and is the only way to test "asked while
 * another was in flight". Nothing here depends on time or on a thread.
 */
class SaveQueueTest {

    /** A screen that records what it was asked, and finishes a save only when told to. */
    private class FakeTarget(private val synchronous: Boolean = false) : SaveTarget<String> {
        override var strokeInProgress = false
        val started = ArrayList<String>()
        val strokeStateAtEachStart = ArrayList<Boolean>()
        private val unanswered = ArrayList<(String?) -> Unit>()
        var overlaps = 0
        private var open = 0

        val inFlightCount: Int get() = unanswered.size

        override fun start(reason: SaveReason, destination: String, finished: (String?) -> Unit) {
            started.add(destination)
            strokeStateAtEachStart.add(strokeInProgress)
            open += 1
            if (open > 1) overlaps += 1
            if (synchronous) {
                open -= 1
                finished(null)
            } else {
                unanswered.add { problem ->
                    open -= 1
                    finished(problem)
                }
            }
        }

        /** The oldest save answers. */
        fun finishOne(problem: String? = null) {
            val f = unanswered.removeAt(0)
            f(problem)
        }
    }

    private fun queueOf(t: FakeTarget) = SaveQueue(t)

    // ---- MAJOR 1: a save asked for during another is not lost -----------------------------------

    @Test
    fun anExplicitSaveDuringAnAutosaveIsNotDiscarded() {
        val t = FakeTarget()
        val q = queueOf(t)

        q.request(SaveReason.IDLE, "working")
        assertEquals(listOf("working"), t.started)

        // The person taps "Save a copy…" while the autosave is still writing.
        q.request(SaveReason.EXPLICIT, "copy-uri")
        assertEquals(listOf("working"), t.started, "the copy must wait: two saves are never at once")
        assertEquals(1, q.pending, "…and it is HELD, not thrown away")

        t.finishOne()
        assertEquals(listOf("working", "copy-uri"), t.started, "the copy runs, with its own destination")
        t.finishOne()
        assertEquals(0, q.pending)
        assertFalse(q.busy)
    }

    @Test
    fun aPauseSaveIsQueuedWhileAnotherSaveIsInFlightNotSkipped() {
        val t = FakeTarget()
        val q = queueOf(t)
        q.request(SaveReason.IDLE, "first")

        // A save in flight is not "pending", so the pause request is a new entry, not a merge.
        q.request(SaveReason.IDLE, "pause")
        assertEquals(1, q.pending)

        t.finishOne()
        assertEquals(listOf("first", "pause"), t.started)
    }

    // ---- MAJOR 2: only the stroke ending pays a debt owed by a stroke ---------------------------

    @Test
    fun nothingButTheStrokeEndingReleasesASaveOwedByAStroke() {
        val t = FakeTarget()
        val q = queueOf(t)
        t.strokeInProgress = true
        q.request(SaveReason.IDLE, "working")
        assertEquals(1, q.pending)

        // Every other event a screen could raise mid-stroke — an undo's history report, the idle
        // timer, a pause — ends up calling drain(). None of them may release the debt.
        repeat(5) { q.drain() }
        assertEquals(0, t.started.size)
        assertEquals(1, q.pending, "the save is still owed")

        t.strokeInProgress = false
        q.strokeFinished()
        assertEquals(listOf("working"), t.started)
        assertEquals(0, q.pending)
    }

    @Test
    fun aCancelledStrokeReleasesTheDebtToo() {
        // The old code reported history only from endStroke, so a save owed before a CANCELLED
        // stroke lingered forever. strokeFinished() is the one call for both, and it is level-
        // triggered, so a cancel that arrives is enough.
        val t = FakeTarget()
        val q = queueOf(t)
        t.strokeInProgress = true
        q.request(SaveReason.IDLE, "working")

        t.strokeInProgress = false // cancelled
        q.strokeFinished()
        assertEquals(listOf("working"), t.started)
    }

    // ---- MAJOR 3: no snapshot is ever taken with a stroke in flight, whoever asked --------------

    @Test
    fun anExplicitCopyDuringAStrokeWaitsAndThenContainsIt() {
        val t = FakeTarget()
        val q = queueOf(t)
        t.strokeInProgress = true

        q.request(SaveReason.EXPLICIT, "copy-uri")
        assertEquals(0, t.started.size, "a copy with the pen down must not be started yet")

        t.strokeInProgress = false
        q.strokeFinished()
        assertEquals(listOf("copy-uri"), t.started)
    }

    @Test
    fun noSaveEverStartsWithAStrokeInFlightWhateverAskedForIt() {
        val t = FakeTarget(synchronous = true)
        val q = queueOf(t)
        for (i in 0 until 40) {
            t.strokeInProgress = i % 3 != 0
            q.request(if (i % 2 == 0) SaveReason.IDLE else SaveReason.EXPLICIT, "r$i")
            if (i % 5 == 0) q.drain()
        }
        t.strokeInProgress = false
        q.strokeFinished()

        assertTrue(t.started.isNotEmpty())
        assertTrue(t.strokeStateAtEachStart.none { it }, "a save started while the pen was down")
    }

    // ---- the properties the reviewer's sentence asked for ---------------------------------------

    @Test
    fun nothingIsEverPerformedConcurrentlyAndEverythingIsInOrder() {
        val t = FakeTarget()
        val q = queueOf(t)
        for (i in 0 until 20) q.request(SaveReason.EXPLICIT, "r$i")

        while (t.inFlightCount > 0) t.finishOne()

        assertEquals((0 until 20).map { "r$it" }, t.started, "every request ran, in the order asked")
        assertEquals(0, t.overlaps, "two saves were in flight at once")
        assertEquals(0, q.pending)
    }

    @Test
    fun idleRequestsMergeOnlyWithAWaitingIdleAtTheEnd() {
        val t = FakeTarget()
        val q = queueOf(t)
        q.request(SaveReason.IDLE, "busy") // starts at once and is now in flight

        val a = q.request(SaveReason.IDLE, "a")
        val b = q.request(SaveReason.IDLE, "b")
        assertEquals(a, b, "the second IDLE merged into the waiting one")
        assertEquals(1, q.pending)

        // An explicit request between two IDLEs prevents merging ACROSS it.
        q.request(SaveReason.EXPLICIT, "copy")
        q.request(SaveReason.IDLE, "c")
        assertEquals(3, q.pending)

        while (t.inFlightCount > 0) t.finishOne()
        // The merged one carried the NEWEST destination ("b"), and the order is the order asked.
        assertEquals(listOf("busy", "b", "copy", "c"), t.started)
    }

    @Test
    fun aFailureBelongsToItsOwnRequestAndTheQueueKeepsGoing() {
        val t = FakeTarget()
        val q = queueOf(t)
        q.request(SaveReason.EXPLICIT, "first")
        q.request(SaveReason.EXPLICIT, "second")

        t.finishOne("disk full") // the first one fails
        assertEquals(listOf("first", "second"), t.started, "the failure did not stop the next request")
        t.finishOne()
        assertFalse(q.busy)
        assertEquals(0, q.pending)
    }

    @Test
    fun anIdleQueueDoesNothingHoweverOftenItIsAsked() {
        val t = FakeTarget()
        val q = queueOf(t)
        repeat(10) { q.drain(); q.strokeFinished() }
        assertEquals(0, t.started.size)
    }

    @Test
    fun aSecondAnswerFromOneSaveIsIgnored() {
        // A watchdog and the late real answer may both call finished(): only the first counts.
        var answer: ((String?) -> Unit)? = null
        val started = ArrayList<String>()
        val target = object : SaveTarget<String> {
            override val strokeInProgress = false
            override fun start(reason: SaveReason, destination: String, finished: (String?) -> Unit) {
                started.add(destination)
                if (destination == "one") answer = finished
            }
        }
        val q = SaveQueue(target)
        q.request(SaveReason.EXPLICIT, "one")
        q.request(SaveReason.EXPLICIT, "two")
        q.request(SaveReason.EXPLICIT, "three")

        answer!!.invoke("timed out") // the first answer: "two" starts
        answer!!.invoke(null)        // the late one: must not release "three"
        assertEquals(listOf("one", "two"), started)
        assertTrue(q.busy)
        assertEquals(1, q.pending)
    }

    @Test
    fun aRequestMadeFromInsideAnAnswerStillRuns() {
        val t = FakeTarget()
        val q = queueOf(t)
        q.request(SaveReason.EXPLICIT, "a")
        t.finishOne()
        q.request(SaveReason.EXPLICIT, "b") // the screen re-asks straight after a completion
        assertEquals(listOf("a", "b"), t.started)
    }

    @Test
    fun aLongSynchronousRunDoesNotRecurseOffTheStack() {
        val t = FakeTarget(synchronous = true)
        val q = queueOf(t)
        t.strokeInProgress = true
        for (i in 0 until 20_000) q.request(SaveReason.EXPLICIT, "r")
        t.strokeInProgress = false
        q.strokeFinished() // 20 000 answers arrive synchronously inside the loop: must not overflow
        assertEquals(20_000, t.started.size)
    }
}
