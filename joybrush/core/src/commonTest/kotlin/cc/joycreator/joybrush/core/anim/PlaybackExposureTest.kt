package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.RectPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackExposureTest {
    private fun board(vararg frames: Frame, fps: Float = 24f) = Board(
        id = "anim",
        name = "Animation",
        kind = BoardKind.ANIMATION,
        rect = RectPx(0, 0, 8, 8),
        fps = fps,
        frames = frames.toList(),
    )

    private fun scene(board: Board, sample: TweenFrameSample) = TweenScene.build(
        doc = JbDocument(id = "doc", name = "Doc", boards = listOf(board), layers = emptyList()),
        boardId = board.id,
        sample = sample,
    ) { _, _ -> error("an empty document has no line streams") }

    private fun sample(clock: PlaybackClock, ticks: Long): TweenFrameSample =
        clock.exposureAt(ticks * 1000.0 / 24.0).sample!!

    @Test
    fun exactCumulativeBoundariesSelectUnevenHoldsAt24Fps() {
        val board = board(
            Frame("A", holdFrames = 2, tween = true),
            Frame("B", holdFrames = 3, tween = true),
            Frame("C", holdFrames = 1),
        )
        val clock = PlaybackClock(board)

        val beforeB = clock.exposureAt(2_000.0 / 24.0 - 1e-7).sample!!
        val atB = clock.exposureAt(2_000.0 / 24.0).sample!!
        val atC = clock.exposureAt(5_000.0 / 24.0).sample!!

        assertEquals("A", beforeB.frameId)
        assertEquals(1L, beforeB.tickOffset)
        assertEquals("B", atB.frameId)
        assertEquals(0L, atB.tickOffset)
        assertEquals(2, atB.nextFrameIndex)
        assertEquals(0.0, atB.fraction)
        assertEquals("C", atC.frameId)
        assertEquals(0L, atC.tickOffset)
        assertEquals("B", scene(board, atB).frameId)
        assertEquals("C", scene(board, atC).frameId)
    }

    @Test
    fun selectedRangeLoopsToItsFirstFrameAndOnceStopsAtItsLast() {
        val board = board(
            Frame("A"),
            Frame("B", holdFrames = 2, tween = true),
            Frame("C", holdFrames = 2, tween = true),
            Frame("D"),
        )
        val loop = PlaybackClock(board, mode = PlayMode.LOOP, firstFrame = 1, lastFrame = 2)
        val once = PlaybackClock(board, mode = PlayMode.ONCE, firstFrame = 1, lastFrame = 2)

        val loopC = loop.exposureAt(2_000.0 / 24.0).sample!!
        assertEquals("C", loopC.frameId)
        // The sample records the actual B..C range; the first index is B (1), not C.
        assertEquals(1, loopC.rangeFirstFrameIndex)
        assertEquals(2, loopC.rangeLastFrameIndex)
        assertTrue(loopC.loops)
        assertEquals("B", loopC.nextFrameId)
        assertEquals(1, loopC.nextFrameIndex)
        assertEquals("B", scene(board, loopC).targetFrameId)

        val onceC = once.exposureAt(2_000.0 / 24.0).sample!!
        assertEquals("C", onceC.frameId)
        assertNull(onceC.nextFrameId)
        assertNull(onceC.nextFrameIndex)
        assertFalse(onceC.loops)
        assertNull(scene(board, onceC).targetFrameId)
    }

    @Test
    fun pingPongReversesInteriorTickOffsetsWithoutRepeatingEndpoints() {
        val board = board(
            Frame("A", holdFrames = 1),
            Frame("B", holdFrames = 2, tween = true),
            Frame("C", holdFrames = 3, tween = true),
            Frame("D", holdFrames = 4, tween = true),
        )
        val clock = PlaybackClock(board, mode = PlayMode.PING_PONG)
        val expected = listOf(
            "A" to 0L,
            "B" to 0L, "B" to 1L,
            "C" to 0L, "C" to 1L, "C" to 2L,
            "D" to 0L, "D" to 1L, "D" to 2L, "D" to 3L,
            "C" to 2L, "C" to 1L, "C" to 0L,
            "B" to 1L, "B" to 0L,
        )

        val samples = expected.indices.map { sample(clock, it.toLong()) }
        assertEquals(expected, samples.map { it.frameId to it.tickOffset })
        assertEquals(listOf(false, false, false, false, false, false, false, false, false, false,
            true, true, true, true, true),
            samples.mapIndexed { i, _ -> clock.exposureAt(i * 1000.0 / 24.0).isReverse })
        assertEquals("D", samples[9].frameId)
        assertEquals("C", samples[10].frameId)
        assertEquals(3, samples[10].nextFrameIndex)
        assertEquals(2.0 / 3.0, samples[10].fraction)
        assertEquals("D", scene(board, samples[10]).targetFrameId)
        assertEquals((2.0 / 3.0).toFloat(), scene(board, samples[10]).fraction)
        assertEquals("A", sample(clock, 15L).frameId, "cycle seam returns to A without a reverse A exposure")
    }

    @Test
    fun oneAndTwoFramePingPongRangesDoNotCreateAReverseLeg() {
        val one = board(Frame("A", holdFrames = 2, tween = true))
        val oneClock = PlaybackClock(one, mode = PlayMode.PING_PONG)
        assertEquals(listOf(0L, 1L, 0L), (0L..2L).map { sample(oneClock, it).tickOffset })
        assertTrue((0L..2L).none { oneClock.exposureAt(it * 1000.0 / 24.0).isReverse })
        assertNull(sample(oneClock, 1L).nextFrameId)

        val two = board(Frame("A", holdFrames = 1), Frame("B", holdFrames = 2, tween = true))
        val twoClock = PlaybackClock(two, mode = PlayMode.PING_PONG)
        val samples = (0L..3L).map { sample(twoClock, it) }
        assertEquals(listOf("A", "B", "B", "A"), samples.map { it.frameId })
        assertEquals(listOf(0L, 0L, 1L, 0L), samples.map { it.tickOffset })
        assertTrue((0L..3L).none { twoClock.exposureAt(it * 1000.0 / 24.0).isReverse })
        assertNull(samples[2].nextFrameId, "PING_PONG does not invent a LOOP tween at the range end")
    }

    @Test
    fun speedScalesTickWakeupsInWallTime() {
        val board = board(Frame("A", holdFrames = 4, tween = true), Frame("B"))
        val half = PlaybackClock(board, speed = 0.5f).exposureAt(0.0).nextWakeupMs
        val double = PlaybackClock(board, speed = 2f).exposureAt(0.0).nextWakeupMs

        assertTrue(kotlin.math.abs(half - (2_000.0 / 24.0)) < 1e-9)
        assertTrue(kotlin.math.abs(double - (500.0 / 24.0)) < 1e-9)
    }

    @Test
    fun hugeElapsedCannotReturnAnImmediateBusyWakeup() {
        val board = board(Frame("A", holdFrames = 2, tween = true), Frame("B"))
        val result = PlaybackClock(board).exposureAt(Double.MAX_VALUE)

        assertEquals(Double.POSITIVE_INFINITY, result.nextWakeupMs)
    }
}
