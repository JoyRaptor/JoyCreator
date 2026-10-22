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
    private val clips: cc.joycreator.joybrush.android.board.BoardRuntimeController?, private val done: () -> Unit) {
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
                clips?.playAll()
                val callback=object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        val elapsed=SystemClock.uptimeMillis()-start
                        callbacks++
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
