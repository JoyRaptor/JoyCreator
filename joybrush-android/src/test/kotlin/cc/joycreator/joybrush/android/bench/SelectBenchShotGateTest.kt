package cc.joycreator.joybrush.android.bench

import android.os.Handler
import android.os.Looper
import java.io.File
import java.time.Duration
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SelectBenchShotGateTest {
    @Rule @JvmField val temp = TemporaryFolder()

    private val runId = "0123456789abcdef0123456789abcdef"
    private val looper get() = Shadows.shadowOf(Looper.getMainLooper())

    private fun gate(timeoutMs: Long = 20_000L) =
        SelectBenchShotGate(temp.root, runId, Handler(Looper.getMainLooper()), timeoutMs)

    private fun marker(id: String, event: Int) = File(temp.root, "select-bench-$id-$event.ack")

    @Test fun noAdvancementOccursUntilTheExactAckAppears() {
        val gate = gate()
        assertTrue(gate.expect(4))
        var ready = 0
        var timedOut = 0
        gate.await({ ready++ }, { timedOut++ })

        looper.idleFor(Duration.ofMillis(300))
        assertEquals(0, ready)
        assertEquals(0, timedOut)

        val ack = marker(runId, 4)
        ack.writeText("ack")
        looper.idleFor(Duration.ofMillis(100))
        assertEquals(1, ready)
        assertEquals(0, timedOut)
    }

    @Test fun markerPredatingItsRequestIsNotAcceptedAsCaptureProof() {
        val old = marker(runId, 4).also { it.writeText("predates request") }
        val gate = gate()
        assertFalse(gate.expect(4))
        assertTrue(old.isFile)
        assertTrue(gate.expect(5))
        var ready = 0
        gate.await({ ready++ }, {})
        looper.idleFor(Duration.ofMillis(300))
        assertEquals(0, ready)
        gate.cancel()
    }

    @Test fun wrongRunAndWrongEventMarkersDoNotReleaseTheHold() {
        val gate = gate(timeoutMs = 2_000L)
        val event = 8
        assertTrue(gate.expect(event))
        marker("fedcba9876543210fedcba9876543210", event).writeText("wrong run")
        marker(runId, event + 1).writeText("wrong event")
        var ready = 0
        var timedOut = 0
        gate.await({ ready++ }, { timedOut++ })

        looper.idleFor(Duration.ofMillis(400))
        assertEquals(0, ready)
        assertEquals(0, timedOut)
        assertTrue(marker("fedcba9876543210fedcba9876543210", event).isFile)
        assertTrue(marker(runId, event + 1).isFile)

        marker(runId, event).writeText("right run and event")
        looper.idleFor(Duration.ofMillis(100))
        assertEquals(1, ready)
        assertEquals(0, timedOut)
        assertFalse(marker(runId, event).exists())
    }

    @Test fun matchingAckIsConsumedAndAdvancesExactlyOnce() {
        val gate = gate()
        val event = 11
        assertTrue(gate.expect(event))
        val ack = marker(runId, event)
        ack.writeText("ack")
        var ready = 0
        var timedOut = 0
        gate.await({ ready++ }, { timedOut++ })
        looper.idleFor(Duration.ofMillis(100))
        looper.idleFor(Duration.ofSeconds(1))

        assertEquals(1, ready)
        assertEquals(0, timedOut)
        assertFalse("the consumed ACK marker must be deleted", ack.exists())
    }

    @Test fun timeoutFiresOnceWithoutCallingReady() {
        val gate = gate(timeoutMs = 250L)
        assertTrue(gate.expect(2))
        var ready = 0
        var timedOut = 0
        gate.await({ ready++ }, { timedOut++ })

        looper.idleFor(Duration.ofSeconds(1))
        assertEquals(0, ready)
        assertEquals(1, timedOut)
        looper.idleFor(Duration.ofSeconds(1))
        assertEquals(1, timedOut)
    }

    @Test fun duplicateOrStaleEventCannotStartAnotherWait() {
        val gate = gate()
        val event = 6
        assertTrue(gate.expect(event))
        marker(runId, event).writeText("ack")
        var ready = 0
        var timedOut = 0
        gate.await({ ready++ }, { timedOut++ })
        looper.idleFor(Duration.ofMillis(100))
        assertEquals(1, ready)

        assertFalse(gate.expect(event))
        marker(runId, event).writeText("late duplicate")
        assertFalse(gate.expect(event - 1))
        looper.idleFor(Duration.ofSeconds(1))
        assertEquals(1, ready)
        assertEquals(0, timedOut)
        assertTrue("a rejected stale marker was not adopted or consumed", marker(runId, event).isFile)
    }

    @Test fun cancelBeforeAckSuppressesBothCallbacksAndDeletesOnlyItsOwnPendingMarker() {
        val gate = gate()
        val event = 9
        assertTrue(gate.expect(event))
        val ownAck = marker(runId, event).also { it.writeText("pending") }
        val foreignAck = marker("fedcba9876543210fedcba9876543210", event).also { it.writeText("foreign") }
        var ready = 0
        var timedOut = 0
        gate.await({ ready++ }, { timedOut++ })

        gate.cancel()
        assertFalse("cancel removes this gate's pending marker", ownAck.exists())
        assertTrue("cancel leaves unrelated markers alone", foreignAck.isFile)
        looper.idleFor(Duration.ofSeconds(2))
        assertEquals(0, ready)
        assertEquals(0, timedOut)
    }
}
