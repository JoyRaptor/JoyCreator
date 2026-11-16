package cc.joycreator.joybrush.core.export

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.RectPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Codex audit JB-ANIM-01 and JB-ANIM-02 (11-15): an exported animation lasts what the board plays. */
class GifTimingTest {
    private fun board(fps: Float, holds: List<Int>) = Board(
        id = "b", name = "B", kind = BoardKind.ANIMATION, rect = RectPx(0, 0, 4, 4), fps = fps,
        frames = holds.mapIndexed { i, h -> Frame("f$i", h) },
    )

    private fun gifOf(plan: AnimExportPlan) = GifTiming.of(plan.frameIds.indices.toList(), plan.delayMs)

    @Test fun pngTimingAddsUpToTheBoardsLength() {
        // Was 990 ms at 30 fps and 1008 ms at 24 fps: every frame rounded on its own.
        assertEquals(1000, AnimExport.plan(board(30f, List(30) { 1 }), "A").delayMs.sum())
        assertEquals(1000, AnimExport.plan(board(24f, List(24) { 1 }), "A").delayMs.sum())
        assertEquals(1000, AnimExport.plan(board(60f, List(60) { 1 }), "A").delayMs.sum())
        assertEquals(5000, AnimExport.plan(board(7f, List(35) { 1 }), "A").delayMs.sum())
        // A frame is still within a millisecond of its exact length.
        val p = AnimExport.plan(board(30f, List(30) { 1 }), "A")
        assertTrue(p.delayMs.all { it == 33 || it == 34 }, "${p.delayMs}")
    }

    @Test fun aGifLastsWhatTheBoardPlays() {
        // Was 900 ms at 30 fps and 960 ms at 24 fps.
        for ((fps, n) in listOf(30f to 30, 24f to 24, 12f to 12, 25f to 50, 15f to 45)) {
            val t = gifOf(AnimExport.plan(board(fps, List(n) { 1 }), "A"))
            assertEquals(n * 100 / fps.toInt(), t.pieces.sumOf { it.centiseconds }, "$n frames at $fps fps")
            assertEquals(0, t.dropped, "no drawing left out at $fps fps")
            assertEquals((0 until n).toList(), t.pieces.map { it.picture })
        }
    }

    @Test fun aboveFiftyASecondTheLengthStaysAndShortDrawingsAreCounted() {
        // Was 1200 ms: every 17 ms frame raised to GIF's 20 ms floor.
        val t = gifOf(AnimExport.plan(board(60f, List(60) { 1 }), "A"))
        assertEquals(100, t.pieces.sumOf { it.centiseconds })
        assertTrue(t.pieces.all { it.centiseconds >= GifTiming.MIN_CS })
        assertTrue(t.dropped > 0)
        assertEquals(60, t.pieces.size + t.dropped, "every drawing is either shown or counted")
        assertEquals(t.pieces.map { it.picture }.sorted(), t.pieces.map { it.picture }, "still in play order")
    }

    @Test fun aLongHoldIsWrittenWholeNotCut() {
        // 999 ticks at 1 fps is 999 s; was clamped to 655.35 s.
        val t = gifOf(AnimExport.plan(board(1f, listOf(999, 1)), "A"))
        assertEquals(99_900 + 100, t.pieces.sumOf { it.centiseconds })
        assertTrue(t.pieces.all { it.centiseconds in GifTiming.MIN_CS..GifTiming.MAX_CS })
        assertEquals(listOf(0, 0, 1), t.pieces.map { it.picture })
        // A hold one hundredth over a GIF delay never leaves a 1 cs tail.
        val edge = GifTiming.of(listOf(0), listOf((GifTiming.MAX_CS + 1) * 10))
        assertEquals(listOf(GifTiming.MAX_CS - 1, 2), edge.pieces.map { it.centiseconds })
    }

    @Test fun pingPongOrderIsKeptAndTheLastDrawingIsNeverTooShort() {
        val t = GifTiming.of(listOf(0, 1, 2, 1), listOf(100, 100, 100, 100))
        assertEquals(listOf(0, 1, 2, 1), t.pieces.map { it.picture })
        assertEquals(listOf(10, 10, 10, 10), t.pieces.map { it.centiseconds })
        val tail = GifTiming.of(listOf(0, 1), listOf(100, 10))
        assertEquals(listOf(GifTiming.Piece(0, 11)), tail.pieces)
        assertEquals(1, tail.dropped)
    }
}
