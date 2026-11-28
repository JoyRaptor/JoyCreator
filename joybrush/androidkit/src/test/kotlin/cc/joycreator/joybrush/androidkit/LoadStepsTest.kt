package cc.joycreator.joybrush.androidkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ATLAS-SOLID item 2, the load split: a drawing's load runs as short tasks, in order, and says it is moving between them. */
class LoadStepsTest {

    private class Rig(costs: List<Long>, budget: Long, val alive: () -> Boolean = { true }, failAt: Int = -1) {
        var now = 0L
        val ran = ArrayList<Int>()
        val queue = ArrayDeque<() -> Unit>()
        val taskLengths = ArrayList<Long>()
        val progress = ArrayList<Pair<Int, Int>>()
        var finished = 0
        val failures = ArrayList<Exception>()
        val steps = LoadSteps(costs.mapIndexed { i, c -> { if (i == failAt) throw IllegalStateException("unit $i"); ran.add(i); now += c } },
            budget, schedule = { queue.addLast(it) }, alive = alive,
            onProgress = { d, of -> progress.add(d to of) }, onFinish = { finished++ }, onFailed = { failures.add(it) }, clock = { now })

        /** Runs queued tasks one at a time, as the graphics thread runs one a frame. */
        fun drain(): Rig {
            steps.start()
            while (queue.isNotEmpty()) { val t = queue.removeFirst(); val at = now; t(); taskLengths.add(now - at) }
            return this
        }
    }

    @Test fun aBigLoadRunsInOrderAsShortTasksAndFinishesOnce() {
        // 600 tiles of 12 ms: 7.2 s of work, as a full oil page on the Note 9.
        val r = Rig(List(600) { 12_000_000L }, LoadSteps.BUDGET_NANOS).drain()
        assertEquals((0 until 600).toList(), r.ran, "every unit, in order")
        assertEquals(1, r.finished)
        assertTrue(r.steps.tasks >= 28, "split into many tasks (${r.steps.tasks})")
        assertTrue(r.taskLengths.all { it <= LoadSteps.BUDGET_NANOS + 12_000_000L }, "no task runs past its budget by more than one unit: ${r.taskLengths.max()}")
        assertEquals(r.steps.tasks - 1, r.progress.size, "a word of progress between every two tasks")
        assertTrue(r.progress.zipWithNext().all { (a, b) -> b.first > a.first } && r.progress.all { it.second == 600 })
    }

    @Test fun aUnitLongerThanTheBudgetStillMovesOn() {
        val r = Rig(listOf(900_000_000L, 1L, 900_000_000L), LoadSteps.BUDGET_NANOS).drain()
        assertEquals(listOf(0, 1, 2), r.ran)
        assertEquals(1, r.finished)
        assertEquals(2, r.steps.tasks)
    }

    @Test fun aSmallLoadIsOneTask() {
        val r = Rig(List(10) { 1_000_000L }, LoadSteps.BUDGET_NANOS).drain()
        assertEquals(1, r.steps.tasks); assertEquals(1, r.finished); assertEquals(0, r.progress.size)
    }

    @Test fun anotherLoadEndsTheChainWithoutFinishing() {
        // The 41st unit finds a new load begun (another drawing opened, or the graphics lost and a restore started).
        lateinit var rig: Rig
        rig = Rig(List(100) { 12_000_000L }, LoadSteps.BUDGET_NANOS, alive = { rig.ran.size < 40 })
        rig.drain()
        assertEquals(40, rig.ran.size, "stops where it was")
        assertEquals(0, rig.finished, "never says done")
        assertTrue(rig.failures.isEmpty())
        assertTrue(rig.queue.isEmpty(), "and schedules nothing more")
    }

    @Test fun aFailingUnitEndsTheLoadWithItsFailureAndRunsNothingAfter() {
        val r = Rig(List(50) { 12_000_000L }, LoadSteps.BUDGET_NANOS, failAt = 30).drain()
        assertEquals((0 until 30).toList(), r.ran)
        assertEquals(1, r.failures.size); assertEquals("unit 30", r.failures[0].message)
        assertEquals(0, r.finished)
    }

    @Test fun nothingToLoadStillFinishes() {
        val r = Rig(emptyList(), LoadSteps.BUDGET_NANOS).drain()
        assertEquals(1, r.finished); assertEquals(1, r.steps.tasks)
    }
}
