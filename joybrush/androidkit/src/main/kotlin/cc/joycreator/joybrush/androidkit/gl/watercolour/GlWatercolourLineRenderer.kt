package cc.joycreator.joybrush.androidkit.gl.watercolour

import cc.joycreator.joybrush.androidkit.gl.GlProgram
import cc.joycreator.joybrush.androidkit.gl.ShaderLibrary
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.watercolourRuntime
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkReplay

/**
 * The JB-5.20 look-cache adapter: vector edits replay the exact live dabber, never smear cached pixels.
 * Caller owns the output textures and cel sequence/undo. Pooling is always final (no clock in a line record).
 * The most recently prepared geometry keeps its bands, so recolouring only runs the finish pass.
 * This does not claim the lead's record-capture/selection UI is already connected on the phone.
 */
internal class GlWatercolourLineRenderer(shaders: ShaderLibrary, private val tileSize: Int = Tiles.SIZE,
                                        renderScale: Float = 1f) {
    private val pass = WatercolourPass(shaders,tileSize,renderScale)
    private var prepared: Geometry? = null
    private data class Geometry(val samples: List<PenSample>,val seed:Long,val widthScale:Float,
                                val brush:cc.joycreator.joybrush.core.brush.WatercolourSpec)
    private var keys: Set<Long> = emptySet()

    /** Output targets must cover [touchedTiles]; missing targets are refused, not silently omitted. */
    fun touchedTiles(record: StrokeRecord,preset:BrushPreset): Set<Long> {
        require(preset.engine == ENGINE_WATERCOLOUR)
        return InkReplay.dabs(record,preset).flatMap { Tiles.touchedBy(it,tileSize) }.toSet()
    }
    fun render(record:StrokeRecord,preset:BrushPreset,outputs:Map<Long,Int>,hasPaper:Boolean,
               bindPaper:(GlProgram)->Unit): Set<Long> {
        require(preset.engine == ENGINE_WATERCOLOUR)
        InkReplay.refusal(record,preset)?.let { throw cc.joycreator.joybrush.core.vector.InkReplayException(it) }
        val spec=preset.watercolourRuntime()
        val identity=Geometry(record.samples.toList(),record.seed,record.widthScale,spec)
        val tint=floatArrayOf(((record.colorArgb ushr 16)and 255)/255f,((record.colorArgb ushr 8)and 255)/255f,(record.colorArgb and 255)/255f)
        if(identity != prepared) {
            val dabs=InkReplay.dabs(record,preset)
            val buckets=Tiles.bucket(dabs,tileSize)
            require(outputs.keys.containsAll(buckets.keys)) { "Watercolor line look is missing output tiles" }
            pass.begin(spec,record.seed,tint,hasPaper,bindPaper)
            pass.add(buckets) { null }
            keys=buckets.keys.toSet(); prepared=identity
        } else {
            require(outputs.keys.containsAll(keys)) { "Watercolor line look is missing output tiles" }
            pass.recolour(tint,hasPaper,bindPaper)
        }
        pass.finishInto(outputs,1f,keys)
        return keys
    }
    fun forget() { pass.forget(); prepared=null; keys=emptySet() }
    fun release() { pass.release(); prepared=null; keys=emptySet() }
}
