package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.anim.TweenFrameSample
import cc.joycreator.joybrush.core.export.*
import cc.joycreator.joybrush.core.render.*
import kotlin.math.roundToLong

/** Same region renderer as board exports, with one frame override per clip and no saved cursor writes. */
object SceneAnimExportRunner {
    /** Preserve the old API shape, including its trailing progress lambda. */
    fun write(contents: JbContents, plan: SceneAnimExportPlan, gif: Boolean, includePaper: Boolean, sink: AnimFileSink,
        onWarning: (String) -> Unit = {}, onFrame: (Int,Int) -> Unit = { _,_ -> }) =
        write(contents, plan, gif, includePaper, sink, onWarning, onFrame, null)

    fun write(contents: JbContents, plan: SceneAnimExportPlan, gif: Boolean, includePaper: Boolean, sink: AnimFileSink,
        onWarning: (String) -> Unit = {}, onFrame: (Int,Int) -> Unit = { _,_ -> },
        tweenRenderer: ((JbContents, Map<String, String>, Map<String, TweenFrameSample>, RectPx, Boolean) -> ByteArray)?) {
        val doc=contents.doc
        validateTweenPlan(doc, plan, tweenRenderer)
        val output=if(gif && plan.fps>50f) {
            onWarning("GIF uses up to 50 frames per second; PNG frames keep the scene's full speed")
            SceneAnimExportPlan.build(doc,plan.fullLoop,maxFps=50f)
        } else plan
        if(output.capped && !plan.capped) onWarning("Full loop is capped at 600 frames at GIF's export speed")
        validateTweenPlan(doc, output, tweenRenderer)
        if (output.tweenSamples == null || tweenRenderer == null) {
            RegionRenderer.unsupportedContentReason(doc)?.let { throw JbArchiveException(it) }
        }
        val pixels=output.rect.w.toLong()*output.rect.h
        val runs=if(gif) output.previews.indices.filter { i ->
            i == 0 || output.previews[i] != output.previews[i-1] ||
                !sameTweenExposure(output.tweenSamples?.get(i), output.tweenSamples?.get(i-1))
        } else output.previews.indices.toList()
        val held=pixels * if(gif) runs.size else 1
        if(pixels > MAX_REGION_PX || held > AnimExportRunner.MAX_EXPORT_PX)
            throw JbArchiveException("This scene is too large to export${if(gif) " as GIF; try PNG frames" else " on this device"}")
        val paperRenderer=CanvasPng.paperRendererFor(contents,includePaper,null,onWarning)
        val paper=if(includePaper && !doc.paper.screenTransparent && paperRenderer == null) doc.paper.color else null
        val tiles=TileSource { layer,cel,x,y -> contents.tiles[Triple(layer,cel,DocOps.key(x,y))] }
        val encoder=if(gif) GifEncoder(output.rect.w,output.rect.h,loop=output.loops) else null
        val timing=StringBuilder("file,delay_ms\n")
        runs.forEachIndexed { run, i ->
            val frames=output.previews[i]
            val end=if(gif && run<runs.lastIndex) runs[run+1] else if(gif) output.previews.size else i+1
            val bytes=output.tweenSamples?.get(i)?.let { samples ->
                tweenRenderer!!.invoke(contents, output.previews[i], samples, output.rect, includePaper).also { rendered ->
                    val expected = output.rect.w.toLong() * output.rect.h * 4L
                    if (expected > Int.MAX_VALUE || rendered.size.toLong() != expected) {
                        throw JbArchiveException("the tween renderer returned the wrong pixel size for the scene")
                    }
                }
            } ?: RegionRenderer.renderWithPreviews(doc,tiles,output.rect,null,paper,paperRenderer,brushLookup=null,frameOverrides=frames)
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
                sink.write(name,"image/png",PngWriter.encode(output.rect.w,output.rect.h,bytes))
                timing.append(name).append(',').append(output.delayMsAt(i)).append('\n')
            }
            onFrame(run+1,runs.size)
        }
        if(encoder != null) sink.write("Play all.gif","image/gif",encoder.finish())
        else sink.write("timing.csv","text/csv",timing.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sameTweenExposure(a: Map<String, TweenFrameSample>?, b: Map<String, TweenFrameSample>?): Boolean {
        if (a == null || b == null) return a == b
        fun stable(map: Map<String, TweenFrameSample>) = map.mapValues { (_, sample) ->
            if (sample.nextFrameId == null) sample.copy(tickOffset = 0L) else sample
        }
        return stable(a) == stable(b)
    }

    private fun validateTweenPlan(
        doc: JbDocument,
        plan: SceneAnimExportPlan,
        renderer: ((JbContents, Map<String, String>, Map<String, TweenFrameSample>, RectPx, Boolean) -> ByteArray)?,
    ) {
        val samples = plan.tweenSamples ?: run {
            val boardIds = plan.previews.flatMap { it.keys }.toSet()
            if (doc.boards.any { board -> board.id in boardIds && board.frames.any { it.tween && it.holdFrames > 1 } }) {
                throw DocException("The scene needs a new tween export plan")
            }
            return
        }
        if (samples.size != plan.previews.size || !plan.fps.isFinite() || plan.fps !in 1f..60f) {
            throw DocException("The scene tween export plan has incomplete timing data")
        }
        if (renderer == null) throw DocException("This scene uses tween exposures; its export needs the live tween renderer")
        for (index in samples.indices) {
            val preview = plan.previews[index]
            val sampleMap = samples[index]
            val expectedBoardIds = preview.filterValues { it != RegionDocumentOps.BLANK_PREVIEW }.keys
            if (sampleMap.keys != expectedBoardIds) {
                throw DocException("The scene tween export plan does not match its active clip states")
            }
            for ((boardId, sample) in sampleMap) {
                val board = doc.boards.firstOrNull { it.id == boardId && it.kind == BoardKind.ANIMATION }
                    ?: throw DocException("The scene tween export plan names a missing clip")
                val source = board.frames.getOrNull(sample.frameIndex)
                if (preview[boardId] != sample.frameId || source?.id != sample.frameId ||
                    sample.tickOffset < 0L || (source != null && sample.tickOffset >= source.holdFrames.toLong())) {
                    throw DocException("The scene tween export plan has a stale frame sample for clip \"$boardId\"")
                }
                val first = sample.rangeFirstFrameIndex
                val last = sample.rangeLastFrameIndex ?: board.frames.lastIndex
                if (first !in board.frames.indices || last !in first..board.frames.lastIndex || sample.frameIndex !in first..last) {
                    throw DocException("The scene tween export plan has a stale range for clip \"$boardId\"")
                }
                val targetIndex = sample.nextFrameIndex
                val expectedTarget = if (source?.tween == true && source.holdFrames > 1) when {
                    sample.frameIndex < last -> sample.frameIndex + 1
                    sample.loops -> first
                    else -> null
                } else null
                if (targetIndex != expectedTarget || sample.nextFrameId != expectedTarget?.let(board.frames::get)?.id ||
                    sample.fraction != if (expectedTarget == null) 0.0 else sample.tickOffset.toDouble() / source!!.holdFrames) {
                    throw DocException("The scene tween export plan has a stale target sample for clip \"$boardId\"")
                }
            }
        }
    }
}
