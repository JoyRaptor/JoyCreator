package cc.joycreator.joybrush.android.bench

import android.os.SystemClock
import android.view.Choreographer
import android.util.Log
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.bench.PlayAllFixture
import cc.joycreator.joybrush.core.anim.ClipPlaybackScheduler
import org.json.JSONObject

/** Debug-only case: real preview composites and rendered-frame timings; never opens a working drawing. */
class PlayAllBench(private val canvas: JbCanvasView,
    private val clips: cc.joycreator.joybrush.android.board.BoardRuntimeController?, private val static: Boolean = false,
    private val trace: Boolean = false, private val done: () -> Unit) {
    fun start() {
        if(!canvas.surfaceReady || canvas.width==0) { canvas.postDelayed({ start() },200); return }
        val contents=PlayAllFixture.contents()
        val scheduler=ClipPlaybackScheduler(contents.doc.boards)
        canvas.benchWatch=true; canvas.benchErrors.clear()
        canvas.load(contents) {
            clips?.select("clip-2")
            // Match the existing device matrix warmup so shader/thumbnail startup is outside the playback sample.
            canvas.postDelayed({
            canvas.startFrameStats {
                val start=SystemClock.uptimeMillis(); var callbacks=0; var changed=0
                var previous: Map<String,String> = emptyMap()
                // UI-thread side of the hitch recorder: a vsync callback that came long after the last one, how late it ran,
                // and what the body of the callback cost. The GL thread records its own (FrameStats.hitches).
                val uiHitches=org.json.JSONArray(); var lastVsyncNs=0L
                val gcStart=cc.joycreator.joybrush.androidkit.bench.FrameStats.gcTotals()
                // What the UI thread did in the first 400 ms of playback: every message it dispatched and each layout/draw stage.
                val events=org.json.JSONArray(); val traceZero=System.nanoTime(); var tracing=true
                fun mark(what: String) { if(tracing) events.put(String.format("%.1f %s",(System.nanoTime()-traceZero)/1e6,what)) }
                // Only the `playAllTrace` case turns this on: it perturbs the very frames it watches (message logging, a sampler thread).
                if(trace) {
                    val looper=android.os.Looper.getMainLooper()
                    looper.setMessageLogging { line -> mark(line.take(150)) }
                    val tree=canvas.rootView.viewTreeObserver
                    val onLayout=android.view.ViewTreeObserver.OnGlobalLayoutListener { mark("LAYOUT-done") }
                    val onPre=android.view.ViewTreeObserver.OnPreDrawListener { mark("PREDRAW"); true }
                    val onDraw=android.view.ViewTreeObserver.OnDrawListener { mark("DRAW") }
                    tree.addOnGlobalLayoutListener(onLayout); tree.addOnPreDrawListener(onPre); tree.addOnDrawListener(onDraw)
                    // A poor man's sampling profiler for the same 400 ms: where the UI thread is every 5 ms (its app frames, innermost first).
                    val uiThread=looper.thread
                    Thread {
                        while(tracing) {
                            try { Thread.sleep(5) } catch(e: InterruptedException) { break }
                            val at=(System.nanoTime()-traceZero)/1e6; val stack=uiThread.stackTrace
                            fun label(e: StackTraceElement)="${e.className.substringAfterLast('.')}.${e.methodName}:${e.lineNumber}"
                            val app=stack.filter { it.className.startsWith("cc.joycreator") || it.className.startsWith("com.fadcam") }.take(5).joinToString(" < ") { label(it) }
                            Log.i(BenchRunner.TAG,String.format("STALLSAMPLE %.0f top[%s] app[%s]",at,stack.take(3).joinToString(" < ") { label(it) },app))
                        }
                    }.also { it.isDaemon=true; it.name="JoyBrush-stall-sampler" }.start()
                    canvas.postDelayed({
                        tracing=false; looper.setMessageLogging(null)
                        val t=canvas.rootView.viewTreeObserver
                        if(t.isAlive) { t.removeOnGlobalLayoutListener(onLayout); t.removeOnPreDrawListener(onPre); t.removeOnDrawListener(onDraw) }
                    },400)
                } else tracing=false
                // The static case never plays: the same canvas drawn the same number of frames is the renderer's own baseline.
                if(!static) clips?.playAll() else canvas.requestRender()
                val callback=object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        val bodyBegan=System.nanoTime()
                        val elapsed=SystemClock.uptimeMillis()-start
                        val vsyncGap=if(lastVsyncNs==0L) 0.0 else (frameTimeNanos-lastVsyncNs)/1e6
                        val lateMs=SystemClock.uptimeMillis()-frameTimeNanos/1_000_000
                        lastVsyncNs=frameTimeNanos
                        callbacks++
                        if(static) canvas.requestRender()
                        val next=scheduler.framesAt(elapsed.toDouble())
                        if(next != previous) { previous=next; changed++; if(clips==null) canvas.previewBoardFrames(next) }
                        val bodyMs=(System.nanoTime()-bodyBegan)/1e6
                        if(vsyncGap>cc.joycreator.joybrush.androidkit.bench.FrameStats.HITCH_MS || bodyMs>8.0)
                            uiHitches.put(JSONObject().put("atMs",elapsed).put("vsyncGapMs",vsyncGap).put("lateMs",lateMs).put("bodyMs",bodyMs)
                                .put("gc",org.json.JSONArray(cc.joycreator.joybrush.androidkit.bench.FrameStats.gcTotals().toList())))
                        if(elapsed<5000) Choreographer.getInstance().postFrameCallback(this)
                        else canvas.finishFrameStats { stats ->
                            if(clips!=null) clips.stopPreview() else canvas.previewBoardFrames(emptyMap())
                            canvas.benchWatch=false
                            val report=JSONObject().put("case","playAll").put("elapsedMs",elapsed).put("callbacks",callbacks)
                                .put("sampledMapChanges",changed).put("driver",if(clips==null) "scheduler" else "BoardRuntimeController")
                                .put("selectedClip","clip-2").put("warmupMs",1500)
                                .put("uiFps",callbacks*1000.0/elapsed).put("renderFrames",stats.frames)
                                .put("busyP95Ms",stats.busy.p95).put("budgetMs",1000.0/60)
                                .put("errors",org.json.JSONArray(canvas.benchErrors.toList()))
                                .put("workP95Ms",stats.work.p95).put("displayP95Ms",stats.display.p95).put("finishP95Ms",stats.finish.p95)
                                .put("busyP50Ms",stats.busy.p50).put("busyMaxMs",stats.busy.max).put("over17",stats.over17).put("over34",stats.over34)
                                .put("uiHitches",uiHitches)
                                .put("glHitches",org.json.JSONArray(canvas.frameStats.hitches().map { h -> JSONObject()
                                    .put("atMs",h.atMs-start).put("gapMs",h.gapMs).put("busyMs",h.busyMs).put("waitMs",h.waitMs)
                                    .put("workMs",h.workMs).put("displayMs",h.displayMs).put("finishMs",h.finishMs)
                                    .put("gc",org.json.JSONArray(h.gc.toList()))
                                    .put("phases",JSONObject().also { p -> h.phases.forEach { (n,v) -> if(v>0.5) p.put(n,v) } }) }))
                                .put("gcStart",org.json.JSONArray(gcStart.toList())).put("gcEnd",org.json.JSONArray(cc.joycreator.joybrush.androidkit.bench.FrameStats.gcTotals().toList()))
                                .put("gapP50Ms",stats.gap.p50).put("gapP95Ms",stats.gap.p95).put("gapMaxMs",stats.gap.max)
                                .put("phases",JSONObject().also { p -> stats.phases.forEach { (n,v) ->
                                    p.put(n,JSONObject().put("p50",v.p50).put("p95",v.p95).put("max",v.max).put("mean",v.mean)) } })
                            report.put("uiTrace",events)
                            val text=report.toString()
                            // One logcat line holds about 4000 bytes; a longer report is cut into numbered parts (put them back in order).
                            if(text.length<=3500) Log.i(BenchRunner.TAG,text)
                            else text.chunked(3500).let { parts -> parts.forEachIndexed { n,p -> Log.i(BenchRunner.TAG,"PLAYALL_PART ${n+1}/${parts.size} $p") } }
                            done()
                        }
                    }
                }
                Choreographer.getInstance().postFrameCallback(callback)
            }
            },1500)
        }
    }
}
