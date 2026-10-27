package cc.joycreator.joybrush.android.bench

import org.junit.Assert.*
import org.junit.Test

class UnibrushProbeLifetimeTest {
    @Test fun timeoutRejectsAllAlreadyQueuedDrawReadAndEditCallbacks() {
        val life = UnibrushProbeLifetime()
        val mutations = mutableListOf<String>()
        val queued = listOf("draw", "read", "edit").map { name -> life.guard { mutations += name } }
        life.close()
        queued.forEach { it() }
        assertTrue(mutations.isEmpty())
        assertFalse(life.runIfActive { fail("Terminal callback ran") })
    }

    @Test fun callbacksFromAnOldProbeCannotBeRevivedByANewRun() {
        val old = UnibrushProbeLifetime()
        var count = 0
        val stale = old.guard { count += 100 }
        old.close()
        val next = UnibrushProbeLifetime()
        next.runIfActive { count++ }; stale()
        assertEquals(1, count)
    }

    @Test fun closingBeforeReentrantDeliverySuppressesThatDelivery() {
        val life = UnibrushProbeLifetime()
        var delivered = false
        val pending = life.guard { delivered = true }
        life.runIfActive { life.close(); pending() }
        assertFalse(delivered)
        life.close()
    }

    @Test fun ownedNoticeLeaseForwardsAndRestoresExactlyOnce() {
        val life = UnibrushProbeLifetime()
        val received = mutableListOf<String>()
        val prior: (String) -> Unit = { received += "prior:$it" }
        var hook: ((String) -> Unit)? = prior
        val lease = UnibrushProbeNoticeLease(life, { hook }, { hook = it }) { received += "probe:$it" }
        val stale = hook!!
        hook!!("one")
        lease.close(); lease.close(); stale("late")
        assertEquals(listOf("probe:one", "prior:one"), received)
        assertSame(prior, hook)
        assertFalse(lease.ownerChanged)
    }

    @Test fun laterOwnerSurvivesCleanupAndStaleWrapperCannotForwardNotices() {
        val life = UnibrushProbeLifetime()
        var hook: ((String) -> Unit)? = null
        var delivered = false
        val lease = UnibrushProbeNoticeLease(life, { hook }, { hook = it }) { delivered = true }
        val stale = hook!!
        val replacement: (String) -> Unit = {}
        hook = replacement
        stale("before cleanup"); lease.close(); stale("after cleanup")
        assertSame(replacement, hook)
        assertTrue(lease.ownerChanged)
        assertFalse(delivered)
    }

    @Test fun terminalLifetimeSuppressesInstalledNoticeDeliveryBeforeHookRemoval() {
        val life = UnibrushProbeLifetime()
        var delivered = false
        var hook: ((String) -> Unit)? = { delivered = true }
        val lease = UnibrushProbeNoticeLease(life, { hook }, { hook = it }) { delivered = true }
        life.close(); hook!!("late"); lease.close()
        assertFalse(delivered)
    }

    @Test fun reentrantProbeTerminationDoesNotCallThePriorNoticeOwnerAfterRelease() {
        val life = UnibrushProbeLifetime()
        var priorCalled = false
        var hook: ((String) -> Unit)? = { priorCalled = true }
        val lease = UnibrushProbeNoticeLease(life, { hook }, { hook = it }) { life.close() }
        hook!!("finish now"); lease.close()
        assertFalse(priorCalled)
    }
}
