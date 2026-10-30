package cc.joycreator.joybrush.core.vector

import kotlin.test.*

class StrokeLiftTransactionTest {
    private class Port:StrokeLiftPort<Int,String> {
        var current=true
        var prepared=0
        var published=0
        var history=0
        var previewDrops=0
        var discarded=0
        var refuse=false
        var discardFailures=0
        var previewFailures=0
        var onPrepare:(()->Unit)?=null
        var onCheck:(()->Unit)?=null
        var onDiscard:(()->Unit)?=null
        var onPreviewDiscard:(()->Unit)?=null
        override fun isCurrent():Boolean { onCheck?.invoke(); return current }
        override fun prepare():Int { prepared++;onPrepare?.invoke();return 17 }
        override fun publish(staged:Int):LiftPublication<String> {
            assertEquals(17,staged)
            if(refuse)return LiftPublication.Refused("source admission refused")
            published++;history++
            return LiftPublication.Published("accepted-root")
        }
        override fun discard(staged:Int) {
            assertEquals(17,staged)
            onDiscard?.invoke()
            if(discardFailures-- > 0)error("retry staged retirement")
            discarded++
        }
        override fun discardPreview() {
            onPreviewDiscard?.invoke()
            if(previewFailures-- > 0)error("retry preview retirement")
            previewDrops++
        }
    }

    @Test fun oneLiftPublishesOnlyOneHistoryStepAndNeverCommitsThePreview() {
        val port=Port();val t=StrokeLiftTransaction(port)
        val result=t.finish()
        assertIs<LiftResult.Published<String>>(result)
        assertSame(result,t.finish())
        t.cancel()
        assertEquals(1,port.prepared);assertEquals(1,port.published);assertEquals(1,port.history)
        assertEquals(1,port.previewDrops);assertEquals(0,port.discarded)
    }

    @Test fun refusedPreparationOrChangedOwnerPreservesPreviewAndArtwork() {
        for(changed in listOf(false,true)) {
            val port=Port();port.refuse=!changed
            if(changed)port.onPrepare={port.current=false}
            val t=StrokeLiftTransaction(port)
            assertIs<LiftResult.Refused>(t.finish())
            assertEquals(0,port.history);assertEquals(0,port.previewDrops);assertEquals(1,port.discarded)
            t.cancel();assertEquals(1,port.previewDrops)
        }
    }

    @Test fun cancelDuringPreparationRetiresStagingWithoutPublishing() {
        val port=Port();val t=StrokeLiftTransaction(port)
        port.onPrepare={t.cancel()}
        assertEquals(LiftResult.Cancelled,t.finish())
        assertEquals(0,port.published);assertEquals(0,port.history)
        assertEquals(1,port.discarded);assertEquals(1,port.previewDrops)
    }

    @Test fun cleanupAfterPublicationCannotTurnSuccessIntoAnotherCommitAttempt() {
        val port=Port();port.previewFailures=1
        val t=StrokeLiftTransaction(port)
        val result=assertIs<LiftResult.Published<String>>(t.finish())
        assertNotNull(result.previewCleanupFailure)
        assertSame(result,t.finish())
        t.retryPreviewCleanup();t.retryPreviewCleanup()
        assertEquals(1,port.history);assertEquals(1,port.previewDrops)
    }

    @Test fun failedStagingCleanupRetainsOwnershipForExplicitRetry() {
        val port=Port();port.refuse=true;port.discardFailures=1
        val t=StrokeLiftTransaction(port)
        assertFailsWith<IllegalStateException>{t.finish()}
        assertEquals(0,port.discarded)
        t.retryStagingCleanup();t.retryStagingCleanup()
        assertEquals(1,port.discarded);assertEquals(0,port.history)
        t.cancel();assertEquals(1,port.previewDrops)
    }

    @Test fun cancellationFromEitherOwnerCheckCannotProceedToPublication() {
        for(cancelAt in 1..2) {
            val port=Port();val t=StrokeLiftTransaction(port);var checks=0
            port.onCheck={if(++checks==cancelAt)t.cancel()}
            assertEquals(LiftResult.Cancelled,t.finish())
            assertEquals(0,port.history);assertEquals(1,port.previewDrops)
            assertEquals(if(cancelAt==1)0 else 1,port.prepared)
            assertEquals(if(cancelAt==1)0 else 1,port.discarded)
        }
    }

    @Test fun finishAndCleanupCallbacksCannotReenterTheirOwners() {
        val port=Port();val t=StrokeLiftTransaction(port)
        port.onCheck={assertFailsWith<IllegalStateException>{t.finish()}}
        port.onPreviewDiscard={assertFailsWith<IllegalStateException>{t.retryPreviewCleanup()}}
        assertIs<LiftResult.Published<String>>(t.finish())
        assertEquals(1,port.history);assertEquals(1,port.previewDrops)
        val refused=Port();refused.refuse=true
        val denied=StrokeLiftTransaction(refused)
        refused.onDiscard={assertFailsWith<IllegalStateException>{denied.retryStagingCleanup()}}
        assertIs<LiftResult.Refused>(denied.finish())
        assertEquals(1,refused.discarded)
    }
}
