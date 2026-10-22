package cc.joycreator.joybrush.androidkit.preview

import org.junit.Assert.*
import org.junit.Test

class PreviewCallbackBoundaryTest {
    @Test fun forgetStyleBatchContinuesAfterDeliveryAndRetirementFaults() {
        val trace=ArrayList<String>();val faults=ArrayList<Throwable>()
        val requests=(1..3).map{id->PreviewCallbackBoundary<Int?>(
            delivery={trace.add("deliver$id");if(id==1)throw IllegalStateException("delivery")},
            retirement={trace.add("retire$id");if(id==2)throw IllegalStateException("retirement")},report={faults.add(it)})}
        requests.forEach{try{it.deliver(null)}finally{it.retire()}}
        assertEquals(listOf("deliver1","retire1","deliver2","retire2","deliver3","retire3"),trace)
        assertEquals(listOf("delivery","retirement"),faults.map{it.message})
        requests.forEach{it.retire()};assertEquals(6,trace.size)
    }
    @Test fun failingFaultReporterCannotAbortLaterRetirement() {
        val delivery=IllegalStateException("delivery");val retirement=IllegalStateException("retirement")
        val report=IllegalArgumentException("report");var calls=0
        val request=PreviewCallbackBoundary<Unit>({throw delivery},{calls++;throw retirement},{throw report})
        request.deliver(Unit);request.retire();request.retire()
        assertEquals(1,calls);assertSame(report,delivery.suppressed.single());assertSame(report,retirement.suppressed.single())
    }
    @Test fun queuedCancellationRetiresWithoutIntroducingAnyDeliveryCallback() {
        var delivered=0;var retired=0
        val request=PreviewCallbackBoundary<Int>({delivered++},{retired++},{throw AssertionError(it)})
        request.retire();request.retire();assertEquals(0,delivered);assertEquals(1,retired)
    }
    @Test fun recursiveRetirementMarksTerminalBeforeCallingTheConsumer() {
        var retired=0
        lateinit var request:PreviewCallbackBoundary<Int>
        request=PreviewCallbackBoundary({}, {retired++;request.retire()}, {throw AssertionError(it)})
        request.retire();assertEquals(1,retired)
    }
}
