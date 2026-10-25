package cc.joycreator.joybrush.androidkit.lines

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class LineCopyReplyTest {
    @Test fun normalResultWinsOnceAndTimeoutOrDuplicateCannotRedeliver() {
        val got = ArrayList<LineCopyResult>()
        val reply = LineCopyReply({ true }) { got += it }
        val original = LineCopyResult.Refused("original")
        assertTrue(reply.finish(original))
        assertFalse(reply.finish(LineCopyResult.Refused("timeout")))
        assertSame(original, got.single())
        assertTrue(reply.isFinished)
    }

    @Test fun aDrawingOrContextChangeBeforeUiDeliveryBecomesARefusal() {
        var epoch = 1
        val captured = epoch
        val got = ArrayList<LineCopyResult>()
        val reply = LineCopyReply({ epoch == captured }) { got += it }
        epoch++
        assertTrue(reply.finish(LineCopyResult.Refused("old result")))
        assertTrue(assertIs<LineCopyResult.Refused>(got.single()).message.contains("drawing changed"))
        assertFalse(reply.finish(LineCopyResult.Refused("duplicate")))
    }

    @Test fun timeoutWinsAndLateGlCompletionCannotCallBackAgain() {
        val got = ArrayList<LineCopyResult>()
        val reply = LineCopyReply({ true }) { got += it }
        assertTrue(reply.finish(LineCopyResult.Refused("timeout")))
        assertFalse(reply.finish(LineCopyResult.Refused("late GL result")))
        assertEquals("timeout", assertIs<LineCopyResult.Refused>(got.single()).message)
    }

    @Test fun aThrowingClientDoesNotReopenTheTerminalGate() {
        val reply = LineCopyReply({ true }) { error("client failed") }
        assertFailsWith<IllegalStateException> { reply.finish(LineCopyResult.Refused("result")) }
        assertTrue(reply.isFinished)
        assertFalse(reply.finish(LineCopyResult.Refused("duplicate")))
    }

    @Test fun competingTerminalPathsHaveExactlyOneWinner() {
        val ready = CountDownLatch(1)
        val calls = AtomicInteger()
        val reply = LineCopyReply({ true }) { calls.incrementAndGet() }
        val threads = List(4) { i -> Thread { ready.await(); reply.finish(LineCopyResult.Refused("terminal-$i")) } }
        threads.forEach { it.start() }; ready.countDown(); threads.forEach { it.join() }
        assertEquals(1, calls.get())
    }
}
