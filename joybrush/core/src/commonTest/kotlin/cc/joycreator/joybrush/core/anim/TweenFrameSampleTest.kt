package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TweenFrameSampleTest {
    @Test
    fun heldTweenFrameUsesOneStableFractionPerTick() {
        val frames = listOf(Frame("a", holdFrames = 4, tween = true), Frame("b"))

        val samples = (0L..3L).map { TweenFrameSample.atTick(frames, it) }
        assertEquals(listOf("a", "a", "a", "a"), samples.map { it.frameId })
        assertEquals(listOf(0L, 1L, 2L, 3L), samples.map { it.tickOffset })
        assertEquals(listOf(0.0, 0.25, 0.5, 0.75), samples.map { it.fraction })
        assertEquals(listOf(1, 1, 1, 1), samples.map { it.nextFrameIndex })
        assertEquals(listOf("b", "b", "b", "b"), samples.map { it.nextFrameId })
        assertEquals(samples[2], TweenFrameSample.atTick(frames, 2L), "polling inside a tick cannot move the fraction")
    }

    @Test
    fun disabledOrSingleTickTweenHasNoInterpolationTarget() {
        val frames = listOf(
            Frame("off", holdFrames = 3, tween = false),
            Frame("one", holdFrames = 1, tween = true),
            Frame("next"),
        )

        val off = TweenFrameSample.atTick(frames, 2L)
        assertEquals("off", off.frameId)
        assertEquals(2L, off.tickOffset)
        assertEquals(null, off.nextFrameId)
        assertEquals(null, off.nextFrameIndex)
        assertEquals(0.0, off.fraction)

        val one = TweenFrameSample.atTick(frames, 3L)
        assertEquals("one", one.frameId)
        assertEquals(null, one.nextFrameId)
        assertEquals(0.0, one.fraction)
    }

    @Test
    fun lastFrameTargetsFirstOnlyForLoopingBoard() {
        val frames = listOf(Frame("a"), Frame("last", holdFrames = 3, tween = true))

        val noLoop = TweenFrameSample.atTick(frames, 2L, loop = false)
        assertEquals("last", noLoop.frameId)
        assertEquals(1, noLoop.frameIndex)
        assertEquals(null, noLoop.nextFrameId)
        assertEquals(0.0, noLoop.fraction)

        val loop = TweenFrameSample.atTick(frames, 2L, loop = true)
        assertEquals("a", loop.nextFrameId)
        assertEquals(0, loop.nextFrameIndex)
        assertEquals(1.0 / 3.0, loop.fraction) // board tick 2 is offset 1 after frame a's one tick.

        val lastTickStartMs = 3L * 1000.0 / 2.0
        val timeLoop = TweenFrameSample.atTimeMs(frames, 2f, lastTickStartMs, loop = true)
        assertEquals("last", timeLoop.frameId)
        assertEquals(1, timeLoop.frameIndex)
        assertEquals(2L, timeLoop.tickOffset)
        assertEquals("a", timeLoop.nextFrameId)
        assertEquals(0, timeLoop.nextFrameIndex)
        assertEquals(2.0 / 3.0, timeLoop.fraction)
    }

    @Test
    fun integerTickOutOfRangeClampsOrWrapsExplicitly() {
        val frames = listOf(Frame("a", holdFrames = 2), Frame("b", holdFrames = 3))

        assertEquals("a", TweenFrameSample.atTick(frames, -1L, loop = true).frameId)
        assertEquals("b", TweenFrameSample.atTick(frames, Long.MAX_VALUE).frameId)
        assertEquals("a", TweenFrameSample.atTick(frames, 5L, loop = true).frameId)
        assertEquals("a", TweenFrameSample.atTick(frames, 6L, loop = true).frameId)
        assertEquals("b", TweenFrameSample.atTick(frames, 7L, loop = true).frameId)
    }

    @Test
    fun exactTimelineBoundariesAreStableAtEverySupportedFps() {
        val frames = listOf(Frame("a", holdFrames = 4, tween = true), Frame("b", holdFrames = 3, tween = true), Frame("c"))
        for (fps in 1..60) {
            val rate = fps.toDouble()
            for (tick in 0L..3L) {
                val atBoundary = tick * 1000.0 / rate
                val sample = TweenFrameSample.atTimeMs(frames, fps.toFloat(), atBoundary)
                assertEquals("a", sample.frameId, "fps=$fps at tick boundary $tick")
                assertEquals(tick, sample.tickOffset, "fps=$fps at tick boundary $tick")
                assertEquals(tick.toDouble() / 4.0, sample.fraction, "fps=$fps at tick boundary $tick")
                if (tick > 0L) {
                    val justBefore = TweenFrameSample.atTimeMs(frames, fps.toFloat(), atBoundary - 1e-7)
                    assertEquals("a", justBefore.frameId, "fps=$fps just before tick boundary $tick")
                    assertEquals(tick - 1L, justBefore.tickOffset, "fps=$fps just before tick boundary $tick")
                }
            }
            val bStart = 4L * 1000.0 / rate
            assertEquals("b", TweenFrameSample.atTimeMs(frames, fps.toFloat(), bStart).frameId, "fps=$fps exact frame boundary")
            val cStart = 7L * 1000.0 / rate
            assertEquals("c", TweenFrameSample.atTimeMs(frames, fps.toFloat(), cStart).frameId, "fps=$fps next exact frame boundary")
        }
    }

    @Test
    fun timelineEndAndLoopSeamMatchAnimOpsClampingRules() {
        val frames = listOf(Frame("a", holdFrames = 2, tween = true), Frame("b", holdFrames = 1))
        val endMs = 3_000.0 / 12.0

        assertEquals("b", TweenFrameSample.atTimeMs(frames, 12f, endMs).frameId)
        assertEquals("a", TweenFrameSample.atTimeMs(frames, 12f, endMs, loop = true).frameId)
        assertEquals("a", TweenFrameSample.atTimeMs(frames, 12f, -1.0, loop = true).frameId)
        assertEquals("a", TweenFrameSample.atTimeMs(frames, 12f, Double.NaN, loop = true).frameId)
        assertEquals("b", TweenFrameSample.atTimeMs(frames, 12f, Double.POSITIVE_INFINITY).frameId)
        assertEquals("a", TweenFrameSample.atTimeMs(frames, 12f, Double.POSITIVE_INFINITY, loop = true).frameId)
    }

    @Test
    fun invalidScheduleIsRefused() {
        assertFailsWith<DocException> { TweenFrameSample.atTick(emptyList(), 0L) }
        assertFailsWith<DocException> { TweenFrameSample.atTick(listOf(Frame("same"), Frame("same")), 0L) }
        assertFailsWith<DocException> { TweenFrameSample.atTick(listOf(Frame("bad", holdFrames = 0)), 0L) }
        for (fps in listOf(0f, -1f, 0.9f, 60.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<DocException> {
                TweenFrameSample.atTimeMs(listOf(Frame("a")), fps, 0.0)
            }
        }
    }
}
