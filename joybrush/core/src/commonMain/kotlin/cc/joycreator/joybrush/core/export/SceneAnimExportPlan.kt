package cc.joycreator.joybrush.core.export

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.anim.*
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Scene sampling follows the same clocks as Play all; it never retimes the source drawings. */
data class SceneAnimExportPlan(val rect: RectPx, val fps: Float, val previews: List<Map<String,String>>,
    val capped: Boolean, val fullLoop: Boolean, val loops: Boolean = fullLoop) {
    fun delayMsAt(i: Int): Int = (((i+1)*1000.0/fps).roundToInt()-(i*1000.0/fps).roundToInt()).coerceAtLeast(1)
    companion object {
        const val MAX_FRAMES = 600
        private fun gcd(a: Long,b: Long): Long { var x=a; var y=b; while(y != 0L) { val z=x%y; x=y; y=z }; return x }
        fun build(doc: JbDocument, fullLoop: Boolean = false, maxFps: Float = 60f): SceneAnimExportPlan {
            require(maxFps.isFinite() && maxFps in 1f..60f)
            val clips=doc.boards.filter { it.kind == BoardKind.ANIMATION && it.frames.isNotEmpty() }
            if(clips.isEmpty()) throw DocException("Add an Animation clip to export Play all")
            val fps=clips.maxOf { it.fps }.coerceAtMost(maxFps)
            clips.forEach { AnimOps.totalDurationMs(it) } // Validate before allocating or constructing clocks.
            // Count exposures before converting cadence: summing rounded milliseconds can add a spurious tick.
            var length=ceil(clips.maxOf { b -> b.frames.sumOf { it.holdFrames.toLong() }*(fps.toDouble()/b.fps) }).toLong().coerceAtLeast(1)
            val looping=clips.filter { BoardEnd.of(it.end) in listOf(BoardEnd.LOOP,BoardEnd.PING_PONG) }
            var capped=false
            if(fullLoop && looping.isNotEmpty()) {
                var cycle=1L
                val master=(fps.toDouble()*1_000_000).roundToLong()
                for(b in looping) {
                    val source=(b.fps.toDouble()*1_000_000).roundToLong()
                    val rateGcd=gcd(master,source)
                    val num=master/rateGcd; val den=source/rateGcd
                    var ticks=b.frames.sumOf { it.holdFrames.toLong() }
                    if(BoardEnd.of(b.end)==BoardEnd.PING_PONG && b.frames.size>2) ticks += b.frames.subList(1,b.frames.lastIndex).sumOf { it.holdFrames.toLong() }
                    val reduced=ticks/gcd(ticks,den)
                    val period=if(reduced > MAX_FRAMES/num) MAX_FRAMES+1L else reduced*num
                    val factor=period/gcd(cycle,period)
                    if(cycle > MAX_FRAMES/factor) { cycle=MAX_FRAMES+1L; break }
                    cycle *= factor
                }
                // Include finite clips without cutting a looping clip at a partial cycle.
                length=if(cycle>MAX_FRAMES) cycle else ((length+cycle-1)/cycle)*cycle
            }
            if(length>100_000 && !fullLoop) throw DocException("This scene needs more than 100,000 frames; shorten its holds before exporting")
            if(fullLoop && length>MAX_FRAMES) capped=true
            val count=if(fullLoop) length.coerceAtMost(MAX_FRAMES.toLong()).toInt() else length.toInt()
            val rect=clips.map { it.rect }.reduce(AnimationBoardOps::union)
            val scheduler=ClipPlaybackScheduler(clips)
            val frames=List(count) { scheduler.framesAt(it*1000.0/fps) }
            return SceneAnimExportPlan(rect,fps,frames,capped,fullLoop,fullLoop && looping.isNotEmpty())
        }
    }
}
