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
    private val done: () -> Unit) {
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
                // The static case never plays: the same canvas drawn the same number of frames is the renderer's own baseline.
                if(!static) clips?.playAll() else canvas.requestRender()
                val callback=object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        val elapsed=SystemClock.uptimeMillis()-start
                        callbacks++
                        if(static) canvas.requestRender()
                        val next=scheduler.framesAt(elapsed.toDouble())
                        if(next != previous) { previous=next; changed++; if(clips==null) canvas.previewBoardFrames(next) }
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
                                .put("gapP50Ms",stats.gap.p50).put("gapP95Ms",stats.gap.p95).put("gapMaxMs",stats.gap.max)
                                .put("phases",JSONObject().also { p -> stats.phases.forEach { (n,v) ->
                                    p.put(n,JSONObject().put("p50",v.p50).put("p95",v.p95).put("max",v.max).put("mean",v.mean)) } })
                            Log.i(BenchRunner.TAG,report.toString()); done()
                        }
                    }
                }
                Choreographer.getInstance().postFrameCallback(callback)
            }
            },1500)
        }
    }
}
