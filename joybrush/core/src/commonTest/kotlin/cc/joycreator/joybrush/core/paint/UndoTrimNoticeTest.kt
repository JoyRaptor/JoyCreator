package cc.joycreator.joybrush.core.paint

import kotlin.test.*

/** Undo letting its oldest steps go over the memory budget is said ONCE per history, never silent (just-works sweep). */
class UndoTrimNoticeTest {
    private fun step(before: String?, after: String) = UndoLog.Step(listOf(UndoLog.TileChange("L", 0L, before, after)))

    /** A log that holds 2 "bytes" (each tile is 1), so the fourth push has to let the oldest step go. */
    private fun log(released: MutableList<String> = mutableListOf()) =
        UndoLog<String>(budgetBytes = 2, sizeOf = { 1L }, release = { released.add(it) })

    @Test fun nothingIsSaidWhileTheHistoryFitsTheBudget() {
        val told = mutableListOf<Int>()
        val log = log().also { it.onFirstTrim = { kept -> told.add(kept) } }
        log.push(step(null, "A")); log.push(step("A", "B")); log.push(step("B", "C"))
        assertEquals(emptyList(), told)
        assertEquals(3, log.undoDepth)
    }

    @Test fun theFirstTrimIsReportedWithHowManyStepsUndoStillReaches() {
        val released = mutableListOf<String>()
        val told = mutableListOf<Int>()
        val log = log(released).also { it.onFirstTrim = { kept -> told.add(kept) } }
        log.push(step(null, "A")); log.push(step("A", "B")); log.push(step("B", "C"))
        log.push(step("C", "D")) // befores A,B,C = 3 > budget 2: the oldest step goes
        // befores A, B, C = 3 > budget 2. The oldest step has no before (its tile did not exist), so dropping it frees
        // nothing and the next one goes too: undo still reaches the last two steps.
        assertEquals(listOf(2), told)
        assertEquals(2, log.undoDepth, "the callback reports exactly what undo still reaches")
        assertEquals(listOf("A"), released, "the budget itself is unchanged: the oldest step is still released")
    }

    @Test fun furtherTrimsStayQuietUntilAHistoryIsClearedAndStartsAgain() {
        val told = mutableListOf<Int>()
        val log = log().also { it.onFirstTrim = { kept -> told.add(kept) } }
        for ((i, name) in listOf("A", "B", "C", "D", "E", "F").withIndex()) log.push(step(if (i == 0) null else "prev$i", name))
        assertEquals(1, told.size, "once per history, however many steps are let go")
        log.clear()
        for ((i, name) in listOf("A", "B", "C", "D").withIndex()) log.push(step(if (i == 0) null else "p$i", name))
        assertEquals(2, told.size, "a cleared history (a drawing opened) gets its own single word")
    }

    @Test fun growingTheNewestStepCanTrimAndIsReportedToo() {
        val told = mutableListOf<Int>()
        val log = log().also { it.onFirstTrim = { kept -> told.add(kept) } }
        log.push(step(null, "A")); log.push(step("A", "B"))
        assertTrue(log.extendNewest(listOf(UndoLog.TileChange("L", 5L, "x", "y"))) { true })
        assertEquals(emptyList(), told) // 2 held, budget 2
        assertTrue(log.extendNewest(listOf(UndoLog.TileChange("L", 6L, "p", "q"))) { true })
        assertEquals(listOf(1), told) // 3 held: the older step goes, the newest is always kept
    }

    @Test fun theSentenceIsCalmAndGetsItsGrammarRight() {
        assertEquals("Undo now reaches back 12 steps; older steps were let go to keep the phone responsive.", UndoLog.trimmedSentence(12))
        assertEquals("Undo now reaches back 1 step; older steps were let go to keep the phone responsive.", UndoLog.trimmedSentence(1))
    }

    @Test fun aNoticeThatThrowsCannotBreakTheEditItReportsOn() {
        val released = mutableListOf<String>()
        val log = log(released).also { it.onFirstTrim = { error("toast failed") } }
        log.push(step(null, "A")); log.push(step("A", "B")); log.push(step("B", "C"))
        log.push(step("C", "D"))
        assertEquals(2, log.undoDepth)
        assertEquals(listOf("A"), released)
        assertNotNull(log.undo(), "undo still works after the failed notice")
    }

    @Test fun noCallbackMeansNoFuss() {
        val log = log()
        for ((i, name) in listOf("A", "B", "C", "D", "E").withIndex()) log.push(step(if (i == 0) null else "p$i", name))
        assertTrue(log.canUndo)
    }

    @Test fun aPreparedProposalSaysHowManyStepsItsBudgetLetGo() {
        val log = log()
        log.push(step(null, "A")); log.push(step("A", "B")); log.push(step("B", "C"))
        assertEquals(0, log.prepareNew(step(null, "Z")).trimmedSteps, "a step whose tile did not exist costs the budget nothing")
        assertEquals(2, log.prepareNew(step("C", "D")).trimmedSteps, "the step with no before frees nothing, so two go")
    }
}
