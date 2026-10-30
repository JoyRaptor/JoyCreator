package cc.joycreator.joybrush.androidkit.preview

/** Callback faults must not strand later renderer requests. Caller alone decides cancellation delivery. */
internal class PreviewCallbackBoundary<V>(private val delivery:(V)->Unit,
    private val retirement:(()->Unit)?,private val report:(Throwable)->Unit) {
    private var retired=false
    fun deliver(value:V){try{delivery(value)}catch(error:Throwable){reportSafely(error)}}
    fun retire(){
        if(retired)return
        retired=true
        try{retirement?.invoke()}catch(error:Throwable){reportSafely(error)}
    }
    private fun reportSafely(error:Throwable){
        try{report(error)}catch(reportFailure:Throwable){
            if(reportFailure!==error)runCatching{error.addSuppressed(reportFailure)}
        }
    }
}
