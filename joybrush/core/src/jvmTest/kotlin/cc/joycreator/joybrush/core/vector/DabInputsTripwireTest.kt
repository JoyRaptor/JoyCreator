package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.joybrushRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WHAT SCATTER IS TOLD ABOUT A DAB IS WRITTEN THREE TIMES, and this is the drift detector for all three — the same
 * kind of tripwire as `InkReplayTest.theSaltIsTheOneTheViewUses`, one level up.
 *
 * `JbCanvasView.dabInputsOf` (androidkit, the live path), `InkReplay.dabInputsOf` (core, the replay) and
 * `InkReplayTest.viewInputsOf` (the test's transcription of the live path) are the same eight arguments. The parity
 * test in `InkReplayTest` compares the replay against the TRANSCRIPTION, not against the view — the view is Android
 * and cannot run here — so a transcription that drifted along with the replay keeps every parity test green. That is
 * not hypothetical: the replay and the transcription both said `tilt = Float.NaN` while the view said
 * `tilt = dab.tilt`, and a pen-tilted scatter line replayed somewhere it was not drawn.
 *
 * So this reads the three SOURCE files and demands the same `DabInputs(...)` argument list, name by name. It is a
 * source-text check and knows it: it proves the three are written alike, not that the view behaves like the replay.
 * It is in `jvmTest` because reading a file is not a `commonTest` thing.
 *
 * LIMITATION, stated rather than hidden: `JbCanvasView.kt` is not a declared input of `:core:jvmTest`
 * (`core/build.gradle.kts` declares `brushes/`, `assets/` and `shaders/`), so an edit to the VIEW alone leaves this
 * task UP-TO-DATE and the tripwire fires on the next run that has any core change, not on the view's own build.
 */
class DabInputsTripwireTest {

    @Test
    fun theReplayAndItsTestTellScatterWhatTheViewTellsIt() {
        val root = joybrushRoot()
        val view = argumentsOf(File(root, "androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt"), "dabInputsOf")
        val replay = argumentsOf(File(root, "core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/InkReplay.kt"), "dabInputsOf")
        val test = argumentsOf(File(root, "core/src/commonTest/kotlin/cc/joycreator/joybrush/core/vector/InkReplayTest.kt"), "viewInputsOf")

        assertTrue(view.size >= 9 && "tilt" in view, "NON-VACUITY: the view's list was really found: $view")
        assertEquals(view, replay, "InkReplay.dabInputsOf must tell Scatter exactly what JbCanvasView.dabInputsOf does")
        assertEquals(view, test, "InkReplayTest.viewInputsOf must transcribe JbCanvasView.dabInputsOf exactly")
    }

    /** `name = value` for every argument of the `DabInputs(` call in function [fn] of [file], whitespace removed. */
    private fun argumentsOf(file: File, fn: String): Map<String, String> {
        val text = file.readText()
        val at = text.indexOf("fun $fn(")
        assertTrue(at >= 0, "no `fun $fn(` in ${file.name}")
        val open = text.indexOf("DabInputs(", at) + "DabInputs".length
        assertTrue(open > at, "no `DabInputs(` after `fun $fn(` in ${file.name}")
        var depth = 0
        var close = open
        while (true) {
            when (text[close]) { '(' -> depth++; ')' -> if (--depth == 0) break }
            close++
        }
        return text.substring(open + 1, close).filterNot { it.isWhitespace() }.split(',').filter { it.isNotEmpty() }
            .associate { arg -> arg.substringBefore('=') to arg.substringAfter('=') }
    }
}
