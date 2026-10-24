package cc.joycreator.joybrush.android.chrome

import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class SerialResourcePreviewLaneTest {
    private class Queue:Executor {
        val work=ArrayDeque<Runnable>()
        override fun execute(command:Runnable){work.addLast(command)}
        fun one(){work.removeFirst().run()}
        fun drain(){while(work.isNotEmpty())one()}
    }
    private class Resource(val id:Int)
    private class Fixture(val cap:Int=32) {
        val io=Queue();val ui=Queue();val errors=ArrayList<Throwable>();val delivered=ArrayList<Int>()
        val live=linkedSetOf<Resource>();var maxLive=0;var cancels=0;var throwsPrepare=false;var throwsSubmit=false
        var synchronousHit=false;var startedOnSubmit=false;var synchronousRetire=false
        var throwsCancel=false;var throwsReport=false
        val pictures=linkedMapOf<Resource,(Int)->Unit>();val metadata=ArrayList<Int>()
        lateinit var lane:SerialResourcePreviewLane<Int,Resource,Int>
        init { lane = SerialResourcePreviewLane(io,ui,
            prepare={id->
                if(throwsPrepare)throw IllegalStateException("decode")
                Resource(id).also{metadata.add(id);live.add(it);maxLive=maxOf(maxLive,live.size)}
            },
            submit={resource,done->
                pictures[resource]=done
                if(startedOnSubmit)lane.started(resource)
                if(throwsSubmit)throw IllegalStateException("submit")
                if(synchronousHit)done(resource.id)
                if(synchronousRetire)lane.retired(resource)
                PreviewCancel{cancels++;if(throwsCancel)throw IllegalStateException("cancel")}
            },onError={errors.add(it);if(throwsReport)throw IllegalStateException("report")},onRelease={pictures.remove(it);check(live.remove(it))},maxQueued=cap) }
        fun add(id:Int,ready:(Int)->Unit={delivered.add(it)})=lane.enqueue(id,ready)
        fun loaded():Resource {io.one();ui.one();return live.single()}
        fun retire(resource:Resource){pictures.remove(resource);lane.retired(resource);ui.drain()}
    }

    @Test fun cancelledIoRetainsOneLeaseUntilPreparationReturnsAndNeverSubmits() {
        val f=Fixture();val cancel=f.add(1);f.add(2)
        cancel.cancel();assertEquals(1,f.lane.leases);assertEquals(1,f.io.work.size)
        f.io.one();f.ui.one();assertTrue(f.pictures.isEmpty());assertTrue(f.live.isEmpty())
        f.ui.drain();assertEquals(1,f.io.work.size);val second=f.loaded()
        assertEquals(2,second.id);assertEquals(1,f.maxLive);f.retire(second)
    }
    @Test fun cancelAfterGlSubmissionWaitsForExplicitTerminalEvenWithoutAPicture() {
        val f=Fixture();f.startedOnSubmit=true
        val cancel=f.add(1);val first=f.loaded();f.add(2);cancel.cancel()
        assertEquals(1,f.lane.leases);assertEquals(1,f.live.size);assertTrue(f.io.work.isEmpty())
        f.retire(first);val second=f.loaded();assertEquals(2,second.id);assertEquals(1,f.maxLive);f.retire(second)
        assertTrue(f.delivered.isEmpty())
    }
    @Test fun diskHitAndSynchronousHitReleaseBeforeNextPrepareAndCancelLateReturnedTicket() {
        val f=Fixture();f.synchronousHit=true;f.add(1);f.add(2)
        f.io.one();f.ui.drain();assertEquals(listOf(1),f.delivered);assertEquals(1,f.cancels)
        assertTrue(f.live.isEmpty());f.io.one();f.ui.drain()
        assertEquals(listOf(1,2),f.delivered);assertEquals(1,f.maxLive);assertEquals(0,f.lane.leases)
    }
    @Test fun nullableRendererCompletionRetiresWithoutOnReadyAndDoubleTerminalIsHarmless() {
        val f=Fixture();f.startedOnSubmit=true;f.add(1);val first=f.loaded();f.add(2)
        f.retire(first);f.lane.retired(first);assertEquals(1,f.io.work.size);assertTrue(f.delivered.isEmpty())
        val second=f.loaded();f.retire(second);assertEquals(1,f.maxLive)
    }
    @Test fun synchronousPictureAndTerminalCompleteOnceBeforeTheReturnedTicketIsAttached() {
        val f=Fixture();f.startedOnSubmit=true;f.synchronousHit=true;f.synchronousRetire=true
        f.add(1);f.add(2);f.io.one();f.ui.drain();f.io.one();f.ui.drain()
        assertEquals(listOf(1,2),f.delivered);assertEquals(2,f.cancels)
        assertEquals(0,f.lane.leases);assertEquals(1,f.maxLive);assertTrue(f.live.isEmpty())
    }
    @Test fun callbackThrowDoesNotReleaseGlLeaseEarlyAndQueuedNextJobStillRunsAfterRetire() {
        val f=Fixture();f.startedOnSubmit=true;f.add(1){throw IllegalStateException("consumer")}
        val first=f.loaded();f.add(2);f.pictures.getValue(first)(1)
        assertEquals(1,f.errors.size);assertEquals(1,f.live.size);assertTrue(f.io.work.isEmpty())
        f.retire(first);val second=f.loaded();f.retire(second);assertEquals(1,f.maxLive)
    }
    @Test fun prepareAndPreGlSubmitFailuresReleaseAndAllowNextQueuedJob() {
        val f=Fixture();f.throwsPrepare=true;f.add(1);f.add(2)
        f.io.one();f.ui.drain();assertEquals(1,f.errors.size);f.throwsPrepare=false
        f.throwsSubmit=true;f.io.one();f.ui.drain();assertEquals(2,f.errors.size);assertTrue(f.live.isEmpty())
        assertEquals(0,f.lane.leases);assertEquals(1,f.maxLive)
    }
    @Test fun throwAfterGlStartedKeepsLeaseUntilTerminalAndSynchronousTerminalIsSafe() {
        val f=Fixture();f.startedOnSubmit=true;f.throwsSubmit=true;f.add(1);val first=f.loaded();f.add(2)
        assertEquals(1,f.errors.size);assertTrue(f.io.work.isEmpty());f.retire(first)
        f.throwsSubmit=false;f.synchronousRetire=true;f.io.one();f.ui.drain()
        assertTrue(f.live.isEmpty());assertEquals(0,f.lane.leases);assertEquals(1,f.maxLive)
    }
    @Test fun destroyDropsQueueAndReleasesActiveOrEventuallyPreparedOwnerWithoutDelivery() {
        val f=Fixture();f.add(1);f.add(2);f.lane.close();assertEquals(0,f.lane.queued)
        f.io.one();f.ui.drain();assertTrue(f.live.isEmpty());assertTrue(f.pictures.isEmpty());assertTrue(f.delivered.isEmpty())
        assertEquals(0,f.lane.leases)
        val gl=Fixture();gl.startedOnSubmit=true;gl.add(1);val active=gl.loaded();gl.add(2);gl.lane.close()
        gl.retire(active);assertTrue(gl.live.isEmpty());assertTrue(gl.io.work.isEmpty());assertEquals(1,gl.cancels)
    }
    @Test fun metadataQueueBoundAndQueuedCancellationNeverAcquireAnImageOwner() {
        val f=Fixture(cap=2);f.add(1);val cancelled=f.add(2);f.add(3);f.add(4)
        assertEquals(1,f.errors.size);assertEquals(2,f.lane.queued);assertTrue(f.live.isEmpty())
        cancelled.cancel();assertEquals(1,f.lane.queued);val first=f.loaded();f.retire(first)
        val next=f.loaded();assertEquals(3,next.id);assertEquals(listOf(1,3),f.metadata);f.retire(next);assertEquals(1,f.maxLive)
    }
    @Test fun cancellationAndReporterFaultsReleasePreGlLeaseWithoutBlockingTheNextJob() {
        val f=Fixture();val cancel=f.add(1);f.loaded();f.add(2);f.throwsCancel=true;f.throwsReport=true
        cancel.cancel();f.ui.drain()
        assertEquals(1,f.errors.size);assertEquals("cancel",f.errors.single().message)
        assertEquals("report",f.errors.single().suppressed.single().message)
        f.throwsCancel=false;f.throwsReport=false;val next=f.loaded();f.retire(next)
        assertEquals(1,f.maxLive);assertEquals(0,f.lane.leases)
    }
    @Test fun cancellationFaultAfterGlStartStillWaitsForTerminalAndCloseContinuesCleanup() {
        val f=Fixture();f.startedOnSubmit=true;val cancel=f.add(1);val first=f.loaded();f.add(2)
        f.throwsCancel=true;cancel.cancel();assertEquals(1,f.lane.leases);assertTrue(f.io.work.isEmpty())
        f.retire(first);f.throwsCancel=false;val second=f.loaded();f.throwsCancel=true
        f.lane.close();f.retire(second)
        assertEquals(2,f.errors.size);assertTrue(f.live.isEmpty());assertEquals(0,f.lane.leases);assertEquals(1,f.maxLive)
    }
    @Test fun consumerAndReporterFaultsDuringSynchronousHitDoNotAbortQueueDrain() {
        val f=Fixture();f.synchronousHit=true;f.throwsReport=true
        f.add(1){throw IllegalStateException("consumer")};f.add(2)
        f.io.one();f.ui.drain();f.io.one();f.ui.drain()
        assertEquals(listOf(2),f.delivered);assertEquals(1,f.errors.size)
        assertTrue(f.live.isEmpty());assertEquals(0,f.lane.leases);assertEquals(1,f.maxLive)
    }

}
