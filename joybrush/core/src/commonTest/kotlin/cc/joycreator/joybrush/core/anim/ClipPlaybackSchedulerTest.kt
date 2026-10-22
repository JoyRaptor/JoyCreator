package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.*
import kotlin.test.*

class ClipPlaybackSchedulerTest {
    private fun clip(id: String, fps: Float, end: String) = Board(id,id,BoardKind.ANIMATION,RectPx(0,0,256,256),fps=fps,
        frames=listOf(Frame("$id-1"),Frame("$id-2",2)), currentFrameId="$id-1",end=end)
    @Test fun independentClocksAndHoldsShareOneOrigin() {
        val a=clip("a",12f,"loop"); val b=clip("b",24f,"hold"); val c=clip("c",6f,"blank")
        val s=ClipPlaybackScheduler(listOf(a,b,c))
        assertEquals(mapOf("a" to "a-1","b" to "b-1","c" to "c-1"),s.framesAt(0.0))
        assertEquals(mapOf("a" to "a-2","b" to "b-2","c" to "c-1"),s.framesAt(100.0))
        assertEquals(mapOf("a" to "a-1","b" to "b-2","c" to ""),s.framesAt(500.0))
        assertEquals("a-1",s.framesAt(0.0)["a"])
    }
    @Test fun blankChangesEvenOnAOneFrameClipAndHoldNeverRestoresSavedCursor() {
        val b=clip("b",10f,"blank").copy(frames=listOf(Frame("b-1")))
        val blank=PlaybackClock(b,PlayMode.of(b.end))
        assertEquals(100.0,blank.nextChangeMs(0.0)); assertFalse(blank.isBlank(99.0)); assertTrue(blank.isBlank(100.0))
        assertEquals(Double.POSITIVE_INFINITY,blank.nextChangeMs(100.0))
        val s=ClipPlaybackScheduler(listOf(clip("h",10f,"hold"),b))
        assertEquals(mapOf("h" to "h-2","b" to ""),s.framesAt(1000.0))
        assertEquals(Double.POSITIVE_INFINITY,s.nextChangeMs(1000.0))
    }
}
