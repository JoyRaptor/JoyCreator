package cc.joycreator.joybrush.android.chrome

import java.util.concurrent.Executor

/** UI-confined metadata FIFO with one reserved IO/cache/renderer lease. ui must always enqueue/defer. */
class SerialResourcePreviewLane<M:Any,R:Any,V:Any>(
    private val io:Executor,private val ui:Executor,
    private val prepare:(M)->R,
    private val submit:(R,(V)->Unit)->PreviewCancel,
    private val onError:(Throwable)->Unit,
    private val onRelease:(R)->Unit = {},
    private val maxQueued:Int=32,
) {
    private class Job<M:Any,R:Any,V:Any>(val metadata:M,val ready:(V)->Unit) {
        var cancelled=false;var loading=true;var made=false;var resource:R?=null;var child:PreviewCancel?=null
    }
    private val waiting=ArrayDeque<Job<M,R,V>>()
    private var active:Job<M,R,V>?=null
    private var closed=false
    init{require(maxQueued in 1..32)}
    val queued:Int get()=waiting.size
    val leases:Int get()=if(active==null)0 else 1

    fun enqueue(metadata:M,ready:(V)->Unit):PreviewCancel {
        if(closed)return PreviewCancel{}
        if(waiting.size>=maxQueued){report(IllegalStateException("Brush preview queue is full; reopen the shelf after it finishes"));return PreviewCancel{}}
        val job=Job<M,R,V>(metadata,ready);waiting.addLast(job);pump()
        return PreviewCancel {
            job.cancelled=true
            if(active===job){
                try{job.child?.cancel()}catch(error:Throwable){report(error)}
                finally{if(!job.loading&&!job.made)finish(job)}
            }else waiting.remove(job)
        }
    }
    private fun pump() {
        if(closed||active!=null)return
        val job=waiting.removeFirstOrNull()?:return
        if(job.cancelled){pump();return}
        active=job
        try { io.execute {
            try {
                val resource=prepare(job.metadata)
                ui.execute delivered@{
                    if(closed||active!==job){onRelease(resource);return@delivered}
                    job.loading=false;job.resource=resource
                    if(job.cancelled){finish(job);return@delivered}
                    try {
                        val child=submit(resource){value->
                            try{if(!job.cancelled&&!closed)job.ready(value)}catch(error:Throwable){report(error)}
                            finally{if(!job.made)finish(job)}
                        }
                        if(active===job)job.child=child else child.cancel()
                    }catch(error:Throwable){
                        try{if(!job.cancelled)report(error)}finally{if(!job.made)finish(job)}
                    }
                }
            }catch(error:Throwable){ui.execute{try{if(!job.cancelled&&!closed)report(error)}finally{finish(job)}}}
        } }catch(error:Throwable){try{report(error)}finally{finish(job)}}
    }
    /** Called by actual maker before submission; hits never call this and can release on delivered value. */
    fun started(resource:R){active?.takeIf{it.resource===resource}?.made=true}
    /** Separate from nullable image delivery: cancel/null/throw also eventually retire the actual GL owner. */
    fun retired(resource:R){active?.takeIf{it.resource===resource}?.let{finish(it)}}
    private fun finish(job:Job<M,R,V>) {
        if(active!==job)return
        val resource=job.resource;job.resource=null;job.child=null;active=null
        try{resource?.let(onRelease)}finally{ui.execute{pump()}}
    }
    private fun report(error:Throwable){
        try{onError(error)}catch(reportFailure:Throwable){
            if(reportFailure!==error)runCatching{error.addSuppressed(reportFailure)}
        }
    }
    fun close(){
        if(closed)return
        closed=true;waiting.clear()
        val job=active?:return
        job.cancelled=true
        try{job.child?.cancel()}catch(error:Throwable){report(error)}finally{finish(job)}
    }
}
