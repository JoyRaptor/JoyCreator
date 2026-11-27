package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.AnimOps
import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JB-NOW-22 — filmstrip weighted-scrub consistency.
 *
 * The strip's x axis is ticks, not milliseconds: cell `i` is `holdFrames[i] x tickPx` wide and
 * starts at the summed widths before it. Playback's time axis is weighted the same way: frame `i`
 * starts at the summed `holdFrames x 1000 / fps` before it. So a scrub position and a playback
 * time name the same frame exactly when both walks agree, and the walk is one sentence — "the
 * last start at or before the probe" — written three times: [AnimOps.frameAt], the private
 * `PlaybackClock.slotAt` behind [PlaybackClock.frameIndexAt] (and [FrameStepper.frameOnScreen]),
 * and [FilmStrip.frameAt]. There is no fourth shared helper; this file pins the three against
 * each other in their native domains (px, ms, elapsed) rather than inventing a px-to-ms bridge
 * the production code never builds.
 *
 * Fixtures (hand-computed throughout; no expectation is read back off the code under test):
 * - board A: holds 1, 2, 1, 3 at 12 fps, density 1. Ticks cumulate 0, 1, 3, 4, 7, so with
 *   `tickPx = 44` the widths are 44, 88, 44, 132, the lefts 0, 44, 132, 176 and the strip 308.
 * - board B: holds 3, 1, 2 at 12 fps, density 1. Widths 132, 44, 88, lefts 0, 132, 176, strip 264.
 *   The widest cell is first, so any position-weighted (rather than hold-weighted) logic fails it.
 * - single: one frame held 2 ticks. Width 88, strip 88, edge zone [64, 88].
 * - empty: an animation board with no frames. The strip is total over it by design (nothing to
 *   draw, nothing to hit); the timing APIs refuse it.
 *
 * Exact-boundary ties come from the existing specs and are pinned, not chosen here: every
 * boundary belongs to the frame that STARTS there (half-open `[left, left + width)` in
 * [FilmStrip.frameAt], "boundaries belong to the frame that is starting" in [AnimOps.frameAt],
 * "the largest k with rangeStarts[k] <= position" in the clock). Where the specs deliberately
 * differ — NaN handling, the LOOP wrap at the range end — the actual divergence is asserted and
 * labelled so a future reader cannot "fix" one side into agreement.
 *
 * Ambiguity report (things this file does NOT decide): there is no specified mapping from a
 * strip px to a clock elapsed; the cross-checks below probe each API in its own units at the
 * boundaries the holds determine. Touch latency, accessibility and any Android view behaviour
 * are out of scope — this is CPU geometry/hit consistency only.
 */
class FilmStripScrubCorpusTest {

    private fun boardA(fps: Float = 12f): Board = Board(
        id = "b-anim",
        name = "Anim",
        kind = BoardKind.ANIMATION,
        rect = RectPx(0, 0, 128, 64),
        fps = fps,
        frames = listOf(
            Frame("f0", holdFrames = 1),
            Frame("f1", holdFrames = 2),
            Frame("f2", holdFrames = 1),
            Frame("f3", holdFrames = 3),
        ),
    )

    private fun boardB(): Board = Board(
        id = "b-anim",
        name = "Anim",
        kind = BoardKind.ANIMATION,
        rect = RectPx(0, 0, 128, 64),
        fps = 12f,
        frames = listOf(
            Frame("g0", holdFrames = 3),
            Frame("g1", holdFrames = 1),
            Frame("g2", holdFrames = 2),
        ),
    )

    private fun singleBoard(): Board = Board(
        id = "b-anim",
        name = "Anim",
        kind = BoardKind.ANIMATION,
        rect = RectPx(0, 0, 128, 64),
        fps = 12f,
        frames = listOf(Frame("s0", holdFrames = 2)),
    )

    private fun emptyBoard(): Board = Board(
        id = "b-anim",
        name = "Anim",
        kind = BoardKind.ANIMATION,
        rect = RectPx(0, 0, 128, 64),
        fps = 12f,
        frames = emptyList(),
    )

    private fun docWith(board: Board): JbDocument = JbDocument(
        id = "doc",
        name = "Test",
        boards = listOf(
            Board(id = "b-canvas", name = "Canvas", kind = BoardKind.CANVAS, rect = RectPx(0, 0, 800, 600)),
            board,
        ),
        layers = listOf(
            Layer(id = "l-static", name = "Static", kind = LayerKind.PAINT, cels = listOf(Cel("c-static"))),
        ),
        activeLayerId = "l-static",
        activeBoardId = "b-anim",
    )

    // ---------- 1. displayed widths follow the holds ----------

    /**
     * Board A: 1, 2, 1, 3 ticks at 44 px a tick are 44, 88, 44, 132 px starting at 0, 44, 132,
     * 176, and the strip is their sum, 308 — which is also f3's right edge, 176 + 132.
     * Board B: 3, 1, 2 ticks are 132, 44, 88 px starting at 0, 132, 176, strip 264.
     * Contiguity (no gaps, no overlaps) is structural: each left is the previous right.
     */
    @Test
    fun weightedWidthsMatchVariableHolds() {
        val a = FilmStrip(boardA())
        assertEquals(listOf(44f, 88f, 44f, 132f), (0..3).map { a.cellWidth(it) }, "hold x 44 px")
        assertEquals(listOf(0f, 44f, 132f, 176f), (0..3).map { a.cellLeft(it) }, "cumulative ticks x 44")
        assertEquals(308f, a.stripWidth(), "44 + 88 + 44 + 132")
        assertEquals(a.cellLeft(3) + a.cellWidth(3), a.stripWidth(), "the strip ends where f3 ends")

        val b = FilmStrip(boardB())
        assertEquals(listOf(132f, 44f, 88f), (0..2).map { b.cellWidth(it) }, "3, 1, 2 ticks x 44 px")
        assertEquals(listOf(0f, 132f, 176f), (0..2).map { b.cellLeft(it) }, "cumulative ticks x 44")
        assertEquals(264f, b.stripWidth(), "132 + 44 + 88")

        for (strip in listOf(a, b)) {
            val n = if (strip === a) 4 else 3
            for (i in 0 until n - 1) {
                assertEquals(
                    strip.cellLeft(i) + strip.cellWidth(i),
                    strip.cellLeft(i + 1),
                    "cell $i ends where cell ${i + 1} starts",
                )
            }
            assertEquals(0f, strip.cellWidth(-1), "no cell before the first")
            assertEquals(0f, strip.cellWidth(n), "no cell past the last")
            assertEquals(0f, strip.cellLeft(-5), "before the first cell is the strip's left end")
            assertEquals(strip.stripWidth(), strip.cellLeft(n + 10), "past the last cell is the strip's right end")
        }
    }

    // ---------- 2. hit positions follow the same weighted boundaries ----------

    /**
     * Board A boundaries are 0, 44, 132, 176 (and the end, 308). Each is probed from both sides
     * and exactly on: the exact pixel belongs to the frame that STARTS there, per the half-open
     * contract. Before the strip is frame 0, past the end and NaN are the last frame — the
     * spec's answers, not conveniences chosen here.
     */
    @Test
    fun hitPositionsFollowHoldWeightedBoundaries() {
        val strip = FilmStrip(boardA())
        val bounds = listOf(0f, 44f, 132f, 176f)
        for (i in bounds.indices) {
            assertEquals(i, strip.frameAt(bounds[i]), "boundary $i starts frame $i")
            assertEquals(i, strip.frameAt(bounds[i] + 0.001f), "just inside frame $i")
            if (i > 0) {
                assertEquals(i - 1, strip.frameAt(bounds[i] - 0.001f), "just before it, the frame that ends there")
            }
        }
        assertEquals(0, strip.frameAt(-5f), "before the strip is frame 0")
        assertEquals(0, strip.frameAt(0f), "the strip's own left end is inside the first cell")
        assertEquals(3, strip.frameAt(307.999f), "last cell holds to its right edge")
        assertEquals(3, strip.frameAt(308f), "the exact end clamps to the last frame")
        assertEquals(3, strip.frameAt(1e6f), "far past the end is still the last frame")
        assertEquals(3, strip.frameAt(Float.NaN), "spec: a NaN is nowhere, answered with the last frame")
        assertEquals(0, strip.frameAt(Float.NEGATIVE_INFINITY), "far before the strip is frame 0")
        assertEquals(3, strip.frameAt(Float.POSITIVE_INFINITY), "far past the strip is the last frame")

        // Board B interiors: the long first cell really is long everywhere inside it.
        val wide = FilmStrip(boardB())
        assertEquals(0, wide.frameAt(1f))
        assertEquals(0, wide.frameAt(131.999f), "g0 spans [0, 132)")
        assertEquals(1, wide.frameAt(132f), "the boundary starts g1")
        assertEquals(2, wide.frameAt(200f), "g2 spans [176, 264)")
    }

    // ---------- 3. scrub, model and clock name the same frame ----------

    /**
     * The weighted-scrub core: at the middle of every frame, the strip (px), the model schedule
     * (ms) and the clock (elapsed, LOOP) all name that frame. Probes are derived from the
     * oracle's own edges — `frameStartsMs` plus the board end — so no px-to-ms bridge is
     * invented; the claim is that both axes are weighted by the same holds in the same order.
     * The first time boundary is also pinned absolutely: one tick at 12 fps is 1000/12 ms.
     */
    @Test
    fun weightedScrubAgreesWithModelSchedule() {
        val board = boardA()
        val strip = FilmStrip(board)
        val clock = PlaybackClock(board)
        val stepper = FrameStepper(clock)
        val starts = AnimOps.frameStartsMs(board)
        val end = AnimOps.totalDurationMs(board)

        assertEquals(1000.0 / 12.0, starts[1], "one tick of a 12 fps board")
        assertEquals(listOf(0, 1, 2, 3), (0..3).map { strip.frameAt(strip.cellLeft(it) + strip.cellWidth(it) / 2f) })

        val edges = starts + end
        for (i in 0..3) {
            val midMs = (edges[i] + edges[i + 1]) / 2.0
            assertEquals(
                board.frames[i].id,
                AnimOps.frameAt(board, midMs).id,
                "model mid-frame $i",
            )
            assertEquals(i, clock.frameIndexAt(midMs), "clock mid-frame $i")
            assertEquals(i, stepper.frameOnScreen(midMs), "stepper reading mid-frame $i")
        }
        assertEquals(3, clock.frameIndexAt(end - 0.001), "the last frame holds to its end")
        assertEquals(3, stepper.frameOnScreen(end - 0.001))
    }

    // ---------- 4. selected endpoints restrict playback, not strip geometry ----------

    /**
     * A clock over frames 1..2 plays f1 then f2 and wraps at the subrange length; the strip
     * still draws the whole board (cellLeft(1) is 44 px, while the clock's relative start is 0).
     * Reversed endpoints select the same range; a one-frame selection never changes and reports
     * +Inf. The subrange length matches an independent board holding just f1..f2's holds.
     */
    @Test
    fun selectedEndpointsRestrictPlaybackToSubrange() {
        val board = boardA()
        val starts = AnimOps.frameStartsMs(board)
        val sub = PlaybackClock(board, firstFrame = 1, lastFrame = 2)

        val f1Len = starts[2] - starts[1]
        val f2Len = starts[3] - starts[2]
        assertEquals(1, sub.frameIndexAt(0.0), "the range starts on f1")
        assertEquals(1, sub.frameIndexAt(f1Len / 2.0), "inside f1's share")
        assertEquals(2, sub.frameIndexAt(f1Len), "f2 begins on its boundary")
        assertEquals(2, sub.frameIndexAt(f1Len + f2Len - 0.001), "f2 holds to the range end")
        assertEquals(1, sub.frameIndexAt(sub.rangeMs), "a LOOP range wraps at its own length")

        val subBoard = board.copy(frames = board.frames.subList(1, 3))
        assertEquals(
            AnimOps.totalDurationMs(subBoard),
            sub.rangeMs,
            1.0e-9,
            "the range length is f1 + f2 in ms (tolerance: two different accumulation orders)",
        )

        val reversed = PlaybackClock(board, firstFrame = 2, lastFrame = 1)
        assertEquals(sub.rangeMs, reversed.rangeMs, "reversed endpoints select the same range")
        assertEquals(1, reversed.frameIndexAt(0.0))

        val single = PlaybackClock(board, firstFrame = 2, lastFrame = 2)
        assertEquals(starts[3] - starts[2], single.rangeMs, "one frame's own length")
        assertEquals(2, single.frameIndexAt(0.0))
        assertEquals(2, single.frameIndexAt(1e9), "a one-frame range cannot leave its frame")
        assertEquals(Double.POSITIVE_INFINITY, single.nextChangeMs(0.0), "and never changes")

        assertEquals(44f, FilmStrip(board).cellLeft(1), "the strip is not clipped to the selection")
    }

    // ---------- 5. an empty strip is total ----------

    /**
     * Nothing to draw, nothing to hit, a playhead that cannot move: widths are 0, every hit is
     * frame 0 (the only total answer), no edge exists, and a scrub gesture publishes Nothing.
     * The timing APIs refuse the same board instead — asserted as their documented refusals.
     */
    @Test
    fun emptyStripIsTotal() {
        val board = emptyBoard()
        val strip = FilmStrip(board)
        assertEquals(0f, strip.stripWidth())
        assertEquals(0f, strip.cellWidth(0))
        assertEquals(0f, strip.cellLeft(0))
        assertEquals(0f, strip.cellLeft(9))
        assertEquals(0f, strip.cellLeft(-3))
        assertEquals(0, strip.frameAt(0f))
        assertEquals(0, strip.frameAt(100f))
        assertEquals(0, strip.frameAt(Float.NaN))
        assertEquals(0, strip.frameAt(Float.POSITIVE_INFINITY))
        assertEquals(-1, strip.edgeAt(0f))
        assertEquals(-1, strip.edgeAt(22f))
        assertEquals(-1, strip.edgeAt(Float.NaN))
        assertEquals(false, strip.canDelete(), "nothing to delete")
        assertEquals("zx", strip.stepPlayhead("zx", 5), "no frames: an id stands as given")
        assertNull(strip.stepPlayhead(null, 1), "no frames: null stays null")
        assertEquals("f0", strip.playheadAfterDelete(board, "f0", "f0"), "nothing was deleted from this board")
        assertNull(strip.playheadAfterDelete(board, "f0", null), "no playhead, nothing to re-anchor")

        val doc = docWith(board)
        val g = FilmStripGesture(doc, "b-anim")
        assertEquals(Grab.Scrub, g.down(5f), "no edge zones exist, so every down is a scrub")
        assertEquals(0, g.previewFrameAt(5f))
        assertEquals(0, g.previewHoldAt(5f), "no cell here — and that 0 is not a hold of 0")
        assertEquals(StripStep.Nothing, g.up(5f), "a scrub over no frames publishes no playhead")

        assertFailsWith<DocException> { AnimOps.frameAt(board, 0.0) }
        assertFailsWith<IllegalArgumentException> { PlaybackClock(board) }
    }

    // ---------- 6. a single frame is always the answer ----------

    /**
     * One frame held 2 ticks: 88 px wide, strip 88 px, zone [64, 88]. Every finite hit is frame
     * 0 including the exact end (clamped to last, which is also first); NaN is the last frame,
     * which is frame 0 too. Delete is dead on the control AND refused by the model; the
     * stepper clamps; a scrub publishes the only frame; a one-frame clock never changes.
     */
    @Test
    fun singleFrameStripAlwaysHitsIt() {
        val board = singleBoard()
        val strip = FilmStrip(board)
        assertEquals(88f, strip.cellWidth(0), "2 ticks x 44 px")
        assertEquals(0f, strip.cellLeft(0))
        assertEquals(88f, strip.cellLeft(1), "one past the last is the strip's right end")
        assertEquals(88f, strip.stripWidth())
        assertEquals(0, strip.frameAt(-50f))
        assertEquals(0, strip.frameAt(0f))
        assertEquals(0, strip.frameAt(87.999f))
        assertEquals(0, strip.frameAt(88f), "the exact end clamps to the last (only) frame")
        assertEquals(0, strip.frameAt(1000f))
        assertEquals(0, strip.frameAt(Float.NaN), "last frame, which is frame 0")

        assertEquals(0, strip.edgeAt(64f), "24 px deep, left of the edge, inclusive")
        assertEquals(0, strip.edgeAt(88f), "the edge itself")
        assertEquals(-1, strip.edgeAt(63.999f))
        assertEquals(-1, strip.edgeAt(0f), "the strip's left end is not an edge")
        assertEquals(-1, strip.edgeAt(88.001f), "one-sided: nothing right of the edge")
        assertEquals(-1, strip.edgeAt(Float.NaN))

        assertEquals(false, strip.canDelete())
        val doc = docWith(board)
        assertFailsWith<DocException> { strip.delete(doc, "b-anim", "s0") }
        assertEquals("s0", strip.stepPlayhead("s0", 99), "clamped, and there is nowhere else")
        assertEquals("s0", strip.stepPlayhead("s0", -99))
        assertEquals("s0", strip.stepPlayhead(null, +1), "a next frame exists and nothing was chosen")
        assertNull(strip.stepPlayhead(null, -1), "nothing is before the first frame")
        assertEquals("nope", strip.stepPlayhead("nope", 1), "an unknown id stands as given")
        assertNull(
            strip.playheadAfterDelete(emptyBoard(), "s0", "s0"),
            "the only frame is gone, so no playhead survives",
        )

        val g = FilmStripGesture(doc, "b-anim")
        assertEquals(Grab.Scrub, g.down(10f), "10 is in no zone")
        assertEquals(0, g.previewFrameAt(10f))
        val step = g.up(10f)
        assertTrue(step is StripStep.PlayheadTo, "a scrub publishes a playhead: $step")
        assertEquals(StripStep.PlayheadTo("s0"), step)
        assertEquals(listOf(2), g.doc.boards.first { it.id == "b-anim" }.frames.map { it.holdFrames })
        assertTrue(g.doc === doc, "the gesture never replaced the document it was given")

        val clock = PlaybackClock(board)
        assertEquals(0, clock.frameIndexAt(0.0))
        assertEquals(0, clock.frameIndexAt(1e9), "a one-frame LOOP range wraps onto itself")
        assertEquals(Double.POSITIVE_INFINITY, clock.nextChangeMs(0.0))
    }

    // ---------- 7. supported density scaling preserves the weighted hits ----------

    /**
     * Densities 1, 2, 3 (the spec's R32 axis): every width, left and the strip itself scale by
     * exactly the density, the grab depth scales with them (24 dp), zones stay disjoint because
     * 24 < 44 at every scale, and the same boundary names the same frame at every density.
     */
    @Test
    fun scalingPreservesWeightedHits() {
        val board = boardA()
        val widths1 = listOf(44f, 88f, 44f, 132f)
        val lefts1 = listOf(0f, 44f, 132f, 176f)
        for (d in listOf(1f, 2f, 3f)) {
            val strip = FilmStrip(board, d)
            assertEquals(44f * d, strip.tickPx, "44 dp at density $d")
            assertEquals(24f * d, strip.edgeGrabPx, "24 dp at density $d")
            assertEquals(widths1.map { it * d }, (0..3).map { strip.cellWidth(it) }, "widths scale at $d")
            assertEquals(lefts1.map { it * d }, (0..3).map { strip.cellLeft(it) }, "lefts scale at $d")
            assertEquals(308f * d, strip.stripWidth(), "and so does the strip at $d")
            assertTrue(strip.edgeGrabPx < strip.tickPx, "24 < 44 at every density: zones stay disjoint")

            for (i in 0..2) {
                val b = lefts1[i + 1] * d
                assertEquals(i + 1, strip.frameAt(b), "boundary $b names frame ${i + 1} at density $d")
                assertEquals(i + 1, strip.frameAt(b + 0.001f), "just inside at density $d")
                assertEquals(i, strip.frameAt(b - 0.001f), "just before at density $d")
                assertEquals(i, strip.edgeAt(b - 0.001f), "zone of frame $i at density $d")
                assertEquals(i, strip.edgeAt(b), "edge itself at density $d")
                assertEquals(-1, strip.edgeAt(b + 0.001f), "one-sided at density $d")
            }
        }
    }

    // ---------- 8. a scrub across variable holds publishes each frame ----------

    /**
     * A scrub down at 60 px (inside f1's [44, 132) span but in no edge zone — asserted, not
     * assumed) then swept across the strip publishes 0, 1, 2, 3 in order, including both
     * interiors of the long f3 cell ([176, 308)), and lifts onto f3. The document is untouched:
     * a scrub moves a playhead, never a hold.
     */
    @Test
    fun scrubAcrossVariableHoldsPublishesEachFrame() {
        val doc = docWith(boardA())
        val strip = FilmStrip(doc.boards.first { it.id == "b-anim" })
        assertEquals(-1, strip.edgeAt(60f), "60 is inside f1 but in no edge zone: this will be a scrub")

        val g = FilmStripGesture(doc, "b-anim")
        assertEquals(Grab.Scrub, g.down(60f))
        assertEquals(1, g.previewFrameAt(60f), "down inside f1")
        assertEquals(0, g.previewFrameAt(10f), "f0 spans [0, 44)")
        assertEquals(2, g.previewFrameAt(140f), "f2 spans [132, 176)")
        assertEquals(3, g.previewFrameAt(200f), "inside f3's long cell")
        assertEquals(3, g.previewFrameAt(300f), "still f3, near its right edge")
        assertEquals(3, g.previewHoldAt(300f), "a scrub has no edge: the model's own hold of f3")

        val step = g.up(300f)
        assertTrue(step is StripStep.PlayheadTo, "a scrub publishes a playhead and no document: $step")
        assertEquals(StripStep.PlayheadTo("f3"), step)
        assertEquals(
            listOf(1, 2, 1, 3),
            g.doc.boards.first { it.id == "b-anim" }.frames.map { it.holdFrames },
            "no hold was touched",
        )
        assertEquals(StripStep.Nothing, g.up(300f), "a second lift writes nothing")
    }

    // ---------- 9. exact-boundary ties come from the spec ----------

    /**
     * Every interior boundary names the incoming frame in all three implementations — strip px,
     * model ms, clock elapsed — plus the stepper reading. The two specified divergences are
     * pinned as actual behaviour: NaN is answered three different ways (model: first, strip:
     * last, gesture: last published), and the exact range end wraps under LOOP while the
     * non-looping answers clamp to the last frame. Neither is a bug; "fixing" either side into
     * agreement would contradict its spec.
     */
    @Test
    fun exactBoundaryTiesComeFromTheSpec() {
        val board = boardA()
        val strip = FilmStrip(board)
        val clock = PlaybackClock(board)
        val stepper = FrameStepper(clock)
        val starts = AnimOps.frameStartsMs(board)
        val end = AnimOps.totalDurationMs(board)

        for (i in 1..3) {
            assertEquals(i, strip.frameAt(strip.cellLeft(i)), "strip: the boundary starts frame $i")
            assertEquals(
                board.frames[i].id,
                AnimOps.frameAt(board, starts[i]).id,
                "model: boundaries belong to the frame that is starting",
            )
            assertEquals(i, clock.frameIndexAt(starts[i]), "clock: last start at or before the probe")
            assertEquals(i, stepper.frameOnScreen(starts[i]), "stepper reads what the clock says")
        }

        // The exact end: clamping answers stay on the last frame, the LOOP clock wraps.
        assertEquals(board.frames.last().id, AnimOps.frameAt(board, end).id, "model clamps past the end")
        assertEquals(3, strip.frameAt(strip.stripWidth()), "strip clamps past the end")
        assertEquals(0, clock.frameIndexAt(clock.rangeMs), "LOOP wraps at the range length — by design")
        assertEquals(0, stepper.frameOnScreen(clock.rangeMs))

        // NaN, three specified answers. The gesture goes down at 0 (a scrub, last published 0),
        // moves onto f1 so last published is 1, and must still say 1 for NaN and +Inf.
        assertEquals("f0", AnimOps.frameAt(board, Double.NaN).id, "model: NaN is before the start")
        assertEquals(3, strip.frameAt(Float.NaN), "strip: NaN is nowhere, answered with the last frame")
        val doc = docWith(board)
        val g = FilmStripGesture(doc, "b-anim")
        assertEquals(Grab.Scrub, g.down(0f), "0 is in no zone; last published is frame 0")
        assertEquals(1, g.previewFrameAt(44f), "a real position moves the preview to f1")
        assertEquals(1, g.previewFrameAt(Float.NaN), "gesture: NaN repeats the last published frame")
        assertEquals(1, g.previewFrameAt(Float.POSITIVE_INFINITY), "and so does +Inf")
        assertEquals("f3", AnimOps.frameAt(board, Double.POSITIVE_INFINITY).id, "model clamps +Inf to last")
        assertEquals(3, strip.frameAt(Float.POSITIVE_INFINITY), "strip clamps +Inf to last")
    }
}
