package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.export.*
import cc.joycreator.joybrush.core.render.*
import kotlin.math.roundToLong

/** Same region renderer as board exports, with one frame override per clip and no saved cursor writes. */
object SceneAnimExportRunner {
    fun write(contents: JbContents, plan: SceneAnimExportPlan, gif: Boolean, includePaper: Boolean, sink: AnimFileSink,
        onWarning: (String) -> Unit = {}, onFrame: (Int,Int) -> Unit = { _,_ -> }) {
        val doc=contents.doc
        val output=if(gif && plan.fps>50f) {
            onWarning("GIF uses up to 50 frames per second; PNG frames keep the scene's full speed")
            SceneAnimExportPlan.build(doc,plan.fullLoop,maxFps=50f)
        } else plan
        if(output.capped && !plan.capped) onWarning("Full loop is capped at 600 frames at GIF's export speed")
        val pixels=plan.rect.w.toLong()*plan.rect.h
        val runs=if(gif) output.previews.indices.filter { it == 0 || output.previews[it] != output.previews[it-1] } else output.previews.indices.toList()
        val held=pixels * if(gif) runs.size else 1
        if(pixels > MAX_REGION_PX || held > AnimExportRunner.MAX_EXPORT_PX)
            throw JbArchiveException("This scene is too large to export${if(gif) " as GIF; try PNG frames" else " on this device"}")
        val paperRenderer=CanvasPng.paperRendererFor(contents,includePaper,null,onWarning)
        val paper=if(includePaper && !doc.paper.screenTransparent && paperRenderer == null) doc.paper.color else null
        val tiles=TileSource { layer,cel,x,y -> contents.tiles[Triple(layer,cel,DocOps.key(x,y))] }
        val encoder=if(gif) GifEncoder(plan.rect.w,plan.rect.h,loop=output.loops) else null
        val timing=StringBuilder("file,delay_ms\n")
        runs.forEachIndexed { run, i ->
            val frames=output.previews[i]
            val end=if(gif && run<runs.lastIndex) runs[run+1] else if(gif) output.previews.size else i+1
            val bytes=RegionRenderer.renderWithPreviews(doc,tiles,plan.rect,null,paper,paperRenderer,brushLookup=null,frameOverrides=frames)
            if(encoder != null) {
                // Quantise cumulative boundaries once, so fractional frame delays never accumulate drift.
                // Sampling at <=50 fps guarantees at least the encoder's two-centisecond minimum.
                var centiseconds=(end*100.0/output.fps).roundToLong()-(i*100.0/output.fps).roundToLong()
                while(centiseconds > 0) {
                    // A GIF delay is unsigned 16-bit. Leave at least two centiseconds in a tail.
                    val chunk=if(centiseconds == 65_536L) 65_534L else minOf(centiseconds,65_535L)
                    encoder.addFrame(bytes,(chunk*10).toInt())
                    centiseconds -= chunk
                }
            }
            else {
                val name="frame-${(i+1).toString().padStart(4,'0')}.png"
                sink.write(name,"image/png",PngWriter.encode(plan.rect.w,plan.rect.h,bytes))
                timing.append(name).append(',').append(output.delayMsAt(i)).append('\n')
            }
            onFrame(run+1,runs.size)
        }
        if(encoder != null) sink.write("Play all.gif","image/gif",encoder.finish())
        else sink.write("timing.csv","text/csv",timing.toString().toByteArray(Charsets.UTF_8))
    }
}
