package cc.joycreator.joybrush.android.bench

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.chrome.ToolMemory

/** Debug bench only: changes this job's recipe, never an installed brush or shared preset. */
internal object BenchBrushOverride {
    fun requireSize(sizePx: Float?) {
        require(sizePx == null || (sizePx.isFinite() && sizePx in 1f..4096f)) {
            "jb_bench_size_px must be a finite float between 1 and 4096"
        }
    }
    fun resolve(preset: BrushPreset, sizePx: Float?): BrushPreset {
        requireSize(sizePx)
        if (sizePx == null) return preset
        require(preset.engine == "stamp") { "The debug size override currently supports stamp recipes only" }
        return ToolMemory.sized(preset, sizePx, preset.opacity.base)
    }
}

/** UI-thread callback lease: observes every refusal and never overwrites a later owner's callback. */
internal class BenchRefusalObservation(
    private val get: () -> ((String) -> Unit)?,
    private val set: (((String) -> Unit)?) -> Unit,
) : AutoCloseable {
    private val previous = get()
    private val reasons = ArrayList<String>()
    private var closed = false
    private val observer: (String) -> Unit = { message -> reasons += message; previous?.invoke(message) }
    init { set(observer) }
    fun snapshot(): List<String> = reasons.toList()
    override fun close() {
        if (closed) return
        closed = true
        if (get() === observer) set(previous)
        else reasons += "The benchmark refusal callback owner changed during the run"
    }
}
