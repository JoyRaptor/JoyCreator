package cc.joycreator.joybrush.core.export

import cc.joycreator.joybrush.core.anim.PlayMode
import cc.joycreator.joybrush.core.anim.PlaybackClock
import cc.joycreator.joybrush.core.doc.AnimOps
import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.RectPx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JB-3.06b, the plan layer. Which frames, in what order, held for how long, at what size — as a
 * pure function of the board, with no canvas, no tiles and no phone anywhere near it.
 *
 * **EVERY EXPECTED DELAY IN THIS FILE IS WRITTEN AS AN EXPRESSION OVER
 * `AnimOps.frameStartsMs`, NEVER AS A HAND-TYPED `hold × 1000 / fps`.** That is JB-3.05a rule 1,
 * verbatim, and it is the rule this file exists to hold: an exporter that re-derives a frame's
 * length from the hold and the rate has a second definition of a number `AnimOps` owns, and the two
 * can differ in the last ulp. The last ulp is a frame boundary in a file somebody else will play.
 * The ONLY hand-typed numbers in the whole file are the tie cases in
 * [aTieGoesTo63Not62], and those are exact halves, which is the whole point of them.
 *
 * There is one deviation from the brief, and it is forced by landed code rather than chosen:
 * `sheetClip` numbers cells from ZERO WITHIN THE PLAN, so `planRange(board, 1, 2, …)` folds to
 * `[0, 0, 1]` and not to the `[1, 1, 2]` the brief's tenth test wrote. `SpritePacker.pack` refuses
 * any clip frame outside `0 until cells.size` (`SpritePacker.kt:189-194`), and a two-frame plan
 * packs two cells, so `[1, 1, 2]` is a sidecar the packer would throw on. See
 * [aHoldIsARepeatedCellIndex], which checks both the ranged plan and the middle of the whole one.
 *
 * This is a CLASS on purpose. JUnit 4 runs methods on an instance, so a file of top-level `@Test`
 * functions compiles, reports a green build and executes nothing.
 */
class AnimExportPlanTest {

    // ── the fixtures ───────────────────────────────────────────────────────────

    /** 12 fps, four frames `f0..f3` held `[1, 2, 1, 3]`, at the origin, 64 by 48. */
    private val board = animBoard(
        id = "b1",
        fps = 12f,
        holds = listOf(1, 2, 1, 3),
        rect = RectPx(0, 0, 64, 48),
    )

    /** A board at a NEGATIVE origin, which is the half of `everyFrameIsTheBoardsOwnRect` that hurts. */
    private val offOrigin = animBoard(
        id = "b2",
        fps = 12f,
        holds = listOf(1, 1),
        rect = RectPx(-40, 300, 32, 32),
    )

    private fun animBoard(
        id: String,
        fps: Float,
        holds: List<Int>,
        rect: RectPx,
        ids: List<String> = holds.indices.map { "f$it" },
    ) = Board(
        id = id,
        name = "Board $id",
        kind = BoardKind.ANIMATION,
        rect = rect,
        fps = fps,
        frames = ids.mapIndexed { i, frameId -> Frame(frameId, holds[i]) },
    )

    /** The derivation under test, computed HERE in the test rather than in the object. */
    private fun expectedDelays(b: Board): List<Int> {
        val starts = AnimOps.frameStartsMs(b)
        val total = AnimOps.totalDurationMs(b)
        return starts.indices.map { i ->
            val end = if (i < starts.lastIndex) starts[i + 1] else total
            floor(end - starts[i] + 0.5).toInt().coerceAtLeast(1)
        }
    }

    // ── Decision 3: the delays ARE the real starts, differenced ─────────────────

    @Test
    fun theDelaysAreDifferencesOfTheRealStarts() {
        val plan = AnimExport.plan(board, "Walk")
        // The numbers, written out once so a reader can see what the plan produced. They are NOT
        // derived in this assertion — `expectedDelays` below re-derives them from `AnimOps` and
        // checks the two agree, which is the half that cannot be satisfied by coincidence.
        assertEquals(listOf(83, 167, 83, 250), plan.delayMs, "the 12 fps fixture's delays")

        val derived = expectedDelays(board)
        assertEquals(derived, plan.delayMs, "delayMs against frameStartsMs differenced in the test")
        for (i in plan.delayMs.indices) {
            val starts = AnimOps.frameStartsMs(board)
            val end = if (i < starts.lastIndex) starts[i + 1] else AnimOps.totalDurationMs(board)
            assertEquals(
                floor(end - starts[i] + 0.5).toInt(),
                plan.delayMs[i],
                "frame $i is not the difference of the two starts the renderer will actually use",
            )
        }
        assertEquals(plan.delayMs.sum(), plan.totalMs, "totalMs is the sum of the delays")
    }

    @Test
    fun theDelaysAreNeverReDerivedFromTheHoldAndTheRate() {
        // The same board, reached twice: once through `plan` and once through a plan of a RANGE
        // covering all four frames. If the object accumulated `hold * 1000 / fps` it would still
        // agree here, so this is not the load-bearing test — `theDelaysAreDifferencesOfTheRealStarts`
        // is. What this adds is the range half, which takes a different code path (a different
        // `starts` index set) and would drift separately if the two paths were spelled differently.
        val whole = AnimExport.plan(board, "Walk")
        val ranged = AnimExport.planRange(board, 0, 3, "Walk")
        assertEquals(whole.delayMs, ranged.delayMs, "a range covering everything must be the whole plan")
        assertEquals(whole.frameIds, ranged.frameIds, "and the same frames, in the same order")
    }

    // ── Decision 2: half up, never ties to even, never zero ─────────────────────

    @Test
    fun aTieGoesTo63Not62() {
        // 16 fps, one frame held once: `1 * 1000 / 16` is exactly 62.5 ms. `kotlin.math.round` is
        // TIES TO EVEN, so it answers 62 here — and 62 is a frame 1 ms shorter than the board played
        // it, in a file somebody else will time. `floor(x + 0.5)` answers 63, which is the only
        // defensible half-up. Asserted against the value, and against `round` in the same test so
        // the disagreement is visible rather than described.
        val sixteen = animBoard("b16", 16f, listOf(1), RectPx(0, 0, 8, 8))
        val once = AnimExport.plan(sixteen, "Sixteen").delayMs.single()
        assertEquals(63, once, "a 16 fps hold of one frame is 62.5 ms, which rounds half UP to 63")
        assertEquals(62, roundTiesToEven(62.5), "the wrong answer, pinned so the two cannot swap")

        // And a second case at the same rate, so this is a rule rather than one fixture's number.
        val three = animBoard("b16b", 16f, listOf(3), RectPx(0, 0, 8, 8))
        val thrice = AnimExport.plan(three, "Sixteen").delayMs.single()
        assertEquals(188, thrice, "`3 * 1000 / 16` is 187.5 ms, which rounds half up to 188")
        assertEquals(188, roundTiesToEven(187.5), "ties to even happens to agree on this one")
    }

    /**
     * `kotlin.math.round` as it really behaves, so the assertion above is a comparison and not a
     * restatement. **TIES TO EVEN**, which on 62.5 is 62 and not 63 — the whole reason Decision 2
     * spells the rounding as `floor(x + 0.5)` and says so in the KDoc.
     */
    private fun roundTiesToEven(x: Double): Int = kotlin.math.round(x).toInt()

    @Test
    fun noDelayIsEverZero() {
        // Every rate a board can play at and every hold `setHold` will store: 60 x 999 = 59 940
        // boards, one frame each. The smallest possible delay is `1000 / 60` = 16.67 ms, so 17 is
        // the floor in practice; the assertion is `>= 1` because that is the promise the plan makes,
        // and a delay of 0 is a frame nobody ever sees — a silent frame loss.
        var smallest = Int.MAX_VALUE
        var cases = 0
        for (fps in 1..60) {
            for (hold in 1..999) {
                val b = animBoard("s", fps.toFloat(), listOf(hold), RectPx(0, 0, 4, 4))
                val delay = AnimExport.plan(b, "S").delayMs.single()
                if (delay < 1) {
                    throw AssertionError("fps $fps hold $hold produced a delay of $delay ms")
                }
                if (delay < smallest) smallest = delay
                cases++
            }
        }
        assertEquals(60 * 999, cases, "every rate and every hold were walked")
        assertEquals(17, smallest, "the smallest delay the whole sweep produces, which is 1000/60")
    }

    @Test
    fun noHoldIsLostOrGainedByTheRoundTrip() {
        // `sheetClip` has to get a hold out of the plan, and the plan carries a DURATION and a RATE
        // rather than a hold. So the question is whether `delayMs -> hold` is exact over the whole
        // space, and it is: the delay is `floor(exact + 0.5)`, so the error is at most half a
        // millisecond, and half a millisecond at 60 fps is 0.03 of a tick. Same 59 940 cases, and
        // this is the assertion that makes Decision 7 a fact rather than a hope.
        for (fps in 1..60) {
            for (hold in 1..999) {
                val b = animBoard("r", fps.toFloat(), listOf(hold), RectPx(0, 0, 4, 4))
                val plan = AnimExport.plan(b, "R")
                val clip = AnimExport.sheetClip(plan, "R", "loop")
                if (clip.frames.size != hold) {
                    throw AssertionError(
                        "fps $fps hold $hold: the plan says ${plan.delayMs.single()} ms, which " +
                            "round-tripped to ${clip.frames.size} cell entries",
                    )
                }
                assertEquals(listOf(0), clip.frames.distinct(), "and every entry is still cell 0")
            }
        }
    }

    // ── Decision 9: one size, the board's own rect ──────────────────────────────

    @Test
    fun everyFrameIsTheBoardsOwnRect() {
        val plan = AnimExport.plan(offOrigin, "Off")
        assertEquals(32, plan.width, "width is the board's own, not its origin's")
        assertEquals(32, plan.height, "height likewise")
        assertEquals(RectPx(-40, 300, 32, 32), plan.rect, "the rect is carried VERBATIM, origin and all")
        // The negative origin is irrelevant to the size and relevant to the crop: a board at a
        // negative x exports its own 32 pixels, not a 32-pixel window somewhere else in the canvas.
        assertEquals(plan.rect.w, plan.width, "width comes from the rect")
        assertEquals(plan.rect.h, plan.height, "height comes from the rect")
        assertEquals(2, plan.frameCount, "both frames, in play order")
        assertEquals(listOf("f0", "f1"), plan.frameIds, "in play order, not sorted")
    }

    // ── Decision 4: a sub-range is the clock's range ────────────────────────────

    @Test
    fun aSubRangeIsSwappedAndClampedLikeTheClock() {
        // NOT against a second implementation. A real `PlaybackClock` built from the same two
        // numbers is asked which frames it plays, and the plan must name the same ones. The clock
        // is the definition of "frames 2..5"; if this plan ever disagrees with it, an export and a
        // playback are different animations, which is the one bug a person cannot argue with.
        val cases = listOf(
            Triple(3, 1, "swapped"),
            Triple(0, 99, "past the end"),
            Triple(-5, -9, "before the start, and swapped"),
            Triple(2, 2, "one frame"),
        )
        for ((first, last, why) in cases) {
            val clock = PlaybackClock(board, PlayMode.LOOP, firstFrame = first, lastFrame = last)
            val played = framesTheClockPlays(clock)
            val plan = AnimExport.planRange(board, first, last, "Walk")
            assertEquals(
                played.map { board.frames[it].id },
                plan.frameIds,
                "planRange($first, $last) — $why — must be the frames PlaybackClock plays",
            )
            // And the delays, which the clock also has an opinion about: one pass through the
            // range at speed 1 is the sum of the plan's delays. This is the same fact with a
            // different witness, not a second derivation of it.
            val sum = plan.delayMs.sum()
            assertTrue(sum > 0, "a range's delays add up to its own length")
            assertTrue(
                sum <= planOfEverything().delayMs.sum(),
                "no sub-range is longer than the whole board",
            )
        }

        // The whole board, which is `plan` and not `planRange`, and is the case a person actually
        // hits on the export button.
        val clock = PlaybackClock(board, PlayMode.LOOP, firstFrame = 0, lastFrame = 3)
        val plan = AnimExport.plan(board, "Walk")
        val whole = framesTheClockPlays(clock)
        assertEquals(4, whole.size, "the clock plays every frame of an in-range request")
        assertEquals(whole.map { board.frames[it].id }, plan.frameIds)
        assertEquals(4, plan.frameCount)
    }

    private fun planOfEverything(): AnimExportPlan = AnimExport.plan(board, "Walk")

    /**
     * Every frame index the clock shows over ONE pass of the range, walked by its own `nextChangeMs`
     * from 0 and stopping at `rangeMs`.
     *
     * ONE PASS, and the stop is `next >= rangeMs` rather than `> rangeMs`: under `PlayMode.LOOP` the
     * clock never finishes, so `nextChangeMs` at the last frame answers the range's own end and the
     * frame after it is the FIRST one again. A walk that kept going would report `f1, f2, f3, f1`
     * for a three-frame range and then compare a plan of three frames against it — which is not a
     * disagreement about the range, it is a disagreement about what "the range" means. The clock
     * wraps forever and an export does not, so the walk stops where the range does.
     */
    private fun framesTheClockPlays(clock: PlaybackClock): List<Int> {
        val played = ArrayList<Int>()
        var at = 0.0
        while (at < clock.rangeMs) {
            played.add(clock.frameIndexAt(at))
            val next = clock.nextChangeMs(at)
            if (!next.isFinite() || next >= clock.rangeMs) return played
            at = next
        }
        return played
    }

    // ── the refusals, all `DocException`, all naming the board ──────────────────

    @Test
    fun anEmptyBoardIsRefusedInWords() {
        val empty = Board("empty", "Empty", BoardKind.ANIMATION, RectPx(0, 0, 10, 10), fps = 12f)
        val e = assertFailsWith<DocException> { AnimExport.plan(empty, "Empty") }
        val message = e.message ?: ""
        assertTrue(message.contains("empty"), "the sentence must name the board: $message")
        assertTrue(message.contains("no frames"), "and say there are none: $message")
    }

    @Test
    fun aBoardWithTwoFramesOfTheSameIdIsRefused() {
        val twin = animBoard(
            id = "twin",
            fps = 12f,
            holds = listOf(1, 1),
            rect = RectPx(0, 0, 8, 8),
            ids = listOf("f0", "f0"),
        )
        val e = assertFailsWith<DocException> { AnimExport.plan(twin, "Twin") }
        val message = e.message ?: ""
        assertTrue(message.contains("twin"), "the sentence must name the board: $message")
        assertTrue(message.contains("f0"), "and the frame that is ambiguous: $message")
    }

    @Test
    fun aBoardAtZeroFpsIsRefusedInWords() {
        val stopped = animBoard("still", 0f, listOf(1, 1), RectPx(0, 0, 8, 8))
        // The fps refusal is `AnimOps`' own, arriving unchanged: it is the same 1..60 range the
        // validator uses (`DocOps.kt:87`) reached through `AnimOps.playableFps` (`AnimOps.kt:543`).
        // So the message is AnimOps' sentence, and it already names both the board and the rate.
        val e = assertFailsWith<DocException> { AnimExport.plan(stopped, "Still") }
        val message = e.message ?: ""
        assertTrue(message.contains("still"), "the sentence must name the board: $message")
        assertTrue(message.contains("0.0"), "and the rate that has no length in time: $message")
    }

    @Test
    fun aBoardThatIsNotAnAnimationIsRefused() {
        val canvas = Board("c1", "Canvas", BoardKind.CANVAS, RectPx(0, 0, 8, 8), fps = 12f)
        val e = assertFailsWith<DocException> { AnimExport.plan(canvas, "Canvas") }
        val message = e.message ?: ""
        assertTrue(message.contains("c1"), "the sentence must name the board: $message")
        assertTrue(message.contains("CANVAS"), "and what kind of board it is: $message")
    }

    // ── Decision 5: the name, sanitised and never empty ─────────────────────────

    @Test
    fun aNameWithASeparatorIsSanitisedNotHonoured() {
        // PROPERTIES, not expected strings. A test that hard-coded this fixture's answers could be
        // satisfied by a `safeBaseName` that returns a lookup table, which is not a rule at all.
        // These are the things `JbArchive.unsafeReason` refuses in a name, restated as things this
        // function must never produce.
        val hostile = listOf(
            "../etc/passwd",
            "a/b",
            "a\\b",
            "C:evil",
            "a\u0000b",
            "x:y|z?",
            "trailing dot . ",
            ".. ",
            "foo/./bar",
            "a/ /../b",
            "///",
            "   ",
            " ",
            "tab\there",
        )
        for (raw in hostile) {
            val name = AnimExport.safeBaseName(raw)
            assertTrue(name.isNotEmpty(), "\"$raw\" must not come back empty")
            assertTrue(name.length <= 512, "\"$raw\" is ${name.length} characters, over 512")
            for (ch in name) {
                assertTrue(ch.code >= 0x20, "\"$raw\" kept the control character U+%04X".format(ch.code))
                assertTrue(ch.code != 0x7F, "\"$raw\" kept a DEL")
                assertTrue(ch != '/', "\"$raw\" kept a slash")
                assertTrue(ch != '\\', "\"$raw\" kept a backslash")
                assertTrue(ch != ':', "\"$raw\" kept a colon")
            }
            assertTrue(name != ".", "\"$raw\" came back as the current folder")
            assertTrue(name != "..", "\"$raw\" came back as the parent folder")
            assertTrue(name.first() != '.', "\"$raw\" starts with a dot: $name")
            assertTrue(name.first() != ' ', "\"$raw\" starts with a space: $name")
            assertTrue(name.last() != '.', "\"$raw\" ends with a dot, which Windows drops: $name")
            assertTrue(name.last() != ' ', "\"$raw\" ends with a space, which Windows drops: $name")
        }
        // The four the brief spells out, because they are the four a reader wants to check by eye
        // and a property cannot tell you they came out readable.
        assertEquals("C evil", AnimExport.safeBaseName("C:evil"))
        assertEquals("etc passwd", AnimExport.safeBaseName("../etc/passwd"))
        assertEquals("ab", AnimExport.safeBaseName("a\u0000b"))
        assertEquals("Animation", AnimExport.safeBaseName("///"))
    }

    @Test
    fun anEmptyNameFallsBackToAnimation() {
        for (raw in listOf("", "   ", "///", "...", "   .  ")) {
            assertEquals("Animation", AnimExport.safeBaseName(raw), "\"$raw\" has nothing usable in it")
        }
    }

    @Test
    fun aNameIsCappedAtFiveHundredAndTwelveAndNeverEndsInADot() {
        val long = "a".repeat(600)
        val capped = AnimExport.safeBaseName(long)
        assertEquals(512, capped.length, "MAX_NAME_CHARS, the same ceiling JbArchive applies")
        // A cap that lands in the middle of a run of dots would leave a trailing dot, which Windows
        // drops off the name — so the cap trims again, and this is the case that proves it does.
        val dotted = "b".repeat(510) + "...".repeat(4)
        val trimmed = AnimExport.safeBaseName(dotted)
        assertTrue(trimmed.length <= 512, "the capped name is ${trimmed.length} characters")
        assertTrue(trimmed.last() != '.', "the capped name must not end in a dot: $trimmed")
    }

    // ── Decision 6: the sheet is near square, with no picker ────────────────────

    @Test
    fun theSheetIsNearSquare() {
        val expected = mapOf(1 to 1, 2 to 2, 3 to 2, 4 to 2, 5 to 3, 9 to 3, 10 to 4, 40 to 7)
        for ((n, cols) in expected) {
            assertEquals(cols, AnimExport.sheetCols(n), "$n frames want $cols columns")
        }
        // BOTH ends of the rule, not one. The exact value says "this is what the brief asked for";
        // the bound says "and it is a grid rather than a strip", which is the reason for the rule.
        for (n in 1..200) {
            val cols = AnimExport.sheetCols(n)
            val rows = ceil(n.toDouble() / cols).toInt()
            val cells = cols.toLong() * rows.toLong()
            assertTrue(
                cells <= n.toLong() + sqrt(n.toDouble()) + cols,
                "$n frames in $cols x $rows is $cells cells, which is not near-square",
            )
            assertTrue(cols >= 1, "a column count of $cols for $n frames")
        }
        // And the empty case, because a column count is a divisor and a divisor of nothing is a
        // question rather than an answer.
        assertEquals(1, AnimExport.sheetCols(0), "no frames still needs one column")
    }

    // ── Decision 7: a hold is a REPEATED cell index ─────────────────────────────

    @Test
    fun aHoldIsARepeatedCellIndex() {
        val plan = AnimExport.plan(board, "Walk")
        val clip = AnimExport.sheetClip(plan, "Walk", "loop")
        // Holds [1,2,1,3] over four cells: cell 0 once, cell 1 twice, cell 2 once, cell 3 three
        // times. Seven entries for four frames, which is the sum of the holds and nothing else.
        //
        // THE BRIEF'S OWN EXAMPLE LIST IS A TRANSCRIPTION SLIP AND IS NOT WHAT IS ASSERTED HERE.
        // Decision 7 writes "Holds [1,2,1,3] over four frames become [0,1,1,2,2,2,3]" — but that list
        // is the fold of [1,2,3,1]: cell 2 repeated three times and cell 3 once, not the reverse.
        // The two disagree and only one of them is the fold of the fixture's own holds, which is
        // [1,2,1,3]. The line below is derived from `board.frames[i].holdFrames` rather than typed,
        // and the literal beside it is the derived one, so this test cannot pass on a mistyped list.
        val derived = buildList {
            for ((i, frame) in board.frames.withIndex()) repeat(frame.holdFrames) { add(i) }
        }
        assertEquals(derived, clip.frames, "the fold IS the board's own holds, in order")
        assertEquals(listOf(0, 1, 1, 2, 3, 3, 3), clip.frames, "holds [1,2,1,3] folded as repeats")
        assertEquals(7, clip.frames.size, "one entry per held tick, and 1+2+1+3 is 7")
        assertEquals(4, clip.frames.distinct().size, "and four distinct cells")
        assertEquals("Walk", clip.name, "the clip's own name, verbatim")
        assertEquals("loop", clip.type, "the type, verbatim from the app's own three words")
        assertEquals(plan.fps, clip.fps, "and the plan's fps")
        // No `weights` key is invented, because `Clip` has no such field. If JB-4.03c adds one,
        // this row must keep folding repeats — see the spec's Question 3.
        assertEquals("Clip", Clip::class.simpleName, "the landed Clip")

        // The same rule on a RANGE. Cells are numbered from zero WITHIN THE plan, so a two-frame
        // plan folds to [0, 0, 1] and never to the board's [1, 1, 2]: `SpritePacker.pack` refuses
        // a clip frame outside `0 until cells.size` and this plan packs two cells.
        val ranged = AnimExport.sheetClip(AnimExport.planRange(board, 1, 2, "Walk"), "Walk", "loop")
        assertEquals(listOf(0, 0, 1), ranged.frames, "a two-frame plan numbers its cells 0 and 1")
        // ...and the board's [1, 1, 2] IS the middle of the whole plan's list, which is what the
        // brief's number describes: frames 1 and 2 of this board, held twice and once.
        assertEquals(listOf(1, 1, 2), clip.frames.subList(1, 4), "the same two cells, board-numbered")    }

    @Test
    fun theSheetCarriesExactlyOneClip() {
        val plan = AnimExport.plan(board, "Walk")
        val packed = SpritePacker.pack(
            cells = List(plan.frameCount) { ByteArray(plan.width * plan.height * 4) },
            cellW = plan.width,
            cellH = plan.height,
            cols = AnimExport.sheetCols(plan.frameCount),
            id = plan.baseName,
            name = plan.baseName,
            sheetFileName = "${plan.baseName}.png",
            fps = plan.fps,
            clips = listOf(AnimExport.sheetClip(plan, "Walk", "loop")),
            cellNames = AnimExport.sheetCellNames(plan),
        )
        // Parsed out of the JSON STRING the packer returned, not out of the arguments, so this
        // checks the file that would be written rather than the call that would write it.
        val sidecar = Json.parseToJsonElement(packed.sidecarJson).jsonObject
        val presets = sidecar.getValue("presets").jsonArray
        assertEquals(1, presets.size, "one clip covers every exported frame")

        val clip = presets[0].jsonObject
        val frames = clip.getValue("frames").jsonArray.map { it.jsonPrimitive.int }
        assertEquals(
            AnimExport.sheetClip(plan, "Walk", "loop").frames,
            frames,
            "the sidecar's frames are the folded list",
        )
        assertEquals(plan.frameCount, 4, "the fixture has four cells")
        for (index in frames) {
            assertTrue(index in 0 until plan.frameCount, "the sidecar plays cell $index, which is not on the sheet")
        }
        assertEquals("loop", clip.getValue("type").jsonPrimitive.content, "type, verbatim")
        assertEquals(12f, clip.getValue("fps").jsonPrimitive.float, "fps, because the plan's is never 0")
        assertEquals("Walk", clip.getValue("name").jsonPrimitive.content, "name, verbatim")

        // Decision 15: `sheetUri` is a name, relative, beside the sidecar — never a path.
        val sheetUri = sidecar.getValue("sheetUri").jsonPrimitive.content
        assertEquals("${plan.baseName}.png", sheetUri, "the sheet's own file name, verbatim")
        assertTrue(!sheetUri.contains('/'), "sheetUri must not be a path: $sheetUri")
        assertTrue(!sheetUri.contains(".."), "sheetUri must not climb: $sheetUri")

        // And `cellNames` says which cell is which frame, from the sparse map the packer writes.
        val cellNames = sidecar.getValue("cellNames").jsonObject
        assertEquals(plan.frameCount, cellNames.size, "one name per cell")
        for ((index, frameId) in plan.frameIds.withIndex()) {
            assertEquals(frameId, cellNames.getValue(index.toString()).jsonPrimitive.content, "cell $index")
        }
    }

    @Test
    fun theSheetIsAcceptedOrRefusedByThePackerItself() {
        // `sheetCols(40)` is 7, and 40 cells at 7 columns is 6 rows. What this file does NOT own is
        // whether that fits in an array, so the test delegates: it packs and reports whatever the
        // packer says, rather than predicting a limit that belongs to `SpritePacker.kt:201`.
        val n = 40
        val cols = AnimExport.sheetCols(n)
        assertEquals(7, cols, "40 frames is a 7-wide grid")
        val cellW = 4
        val cellH = 4
        val cell = ByteArray(cellW * cellH * 4)
        try {
            val packed = SpritePacker.pack(
                cells = List(n) { cell.copyOf() },
                cellW = cellW,
                cellH = cellH,
                cols = cols,
                id = "Big",
                name = "Big",
                sheetFileName = "Big.png",
                fps = 12f,
                clips = listOf(Clip("Big", (0 until n).toList(), "loop", 12f)),
                cellNames = (0 until n).associateWith { "f$it" },
            )
            assertEquals(cols * cellW, packed.width, "the packed width follows the column count")
            assertEquals(ceil(n.toDouble() / cols).toInt() * cellH, packed.height, "and the height the rows")
            // Whatever it accepted, it accepted consistently: the sheet is the grid it describes.
            packed.assertEncodedSize("Big.png", packed.width, packed.height)
        } catch (e: IllegalArgumentException) {
            // Delegated on purpose. The refusal is the packer's sentence and it names the numbers,
            // so the only thing this file asserts about it is that it SAYS something.
            val message = e.message ?: ""
            assertTrue(message.isNotEmpty(), "a refusal must arrive as a sentence")
            println("SpritePacker refused 40 cells in 7 columns, as this test delegates: $message")
        }
    }

    // ── Decision 8: this layer imposes no size budget of its own ────────────────

    @Test
    fun thePlanLayerHoldsNoSizeBudgetAndTheRunnerHoldsTheOnlyOne() {
        // `MAX_REGION_PX` is `RegionRenderer`'s, and `AnimExportRunner` checks the board against it
        // before rendering a frame. This layer deliberately does NOT: a plan is a description of
        // what to export and a plan that could not be built would mean the sentence naming the
        // board could not name the board. The refusal is the runner's, where there is a board id and
        // a button to say it on — `anOversizedBoardIsRefusedBeforeAnyFrameIsRendered` in
        // `AnimExportTest` is the test that proves it, and it is the ONLY size check there is.
        val huge = animBoard("huge", 12f, listOf(1), RectPx(0, 0, 4000, 4000))
        val plan = AnimExport.plan(huge, "Huge")
        assertEquals(4000, plan.width, "the plan is buildable; the runner is what refuses it")
        assertEquals(4000, plan.height)
    }

    @Test
    fun totalMsAndFrameCountAreDerivedFieldsNotStoredNumbers() {
        val plan = AnimExport.plan(board, "Walk")
        assertEquals(plan.delayMs.size, plan.frameCount, "frameCount is the list of ids")
        assertEquals(plan.delayMs.sum(), plan.totalMs, "totalMs is the sum of the delays")
        // A sub-range is a different plan, and its total is the RANGE's, not the board's — which is
        // what makes "export frames 2..5" a document of its own rather than a slower version of the
        // whole one.
        val ranged = AnimExport.planRange(board, 1, 2, "Walk")
        assertEquals(2, ranged.frameCount)
        assertEquals(ranged.delayMs.sum(), ranged.totalMs)
        assertTrue(ranged.totalMs < plan.totalMs, "a two-frame range is shorter than the whole board")
    }
}
