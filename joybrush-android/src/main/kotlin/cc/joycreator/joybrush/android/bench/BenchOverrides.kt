package cc.joycreator.joybrush.android.bench

import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.UnibrushModes
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
        require(preset.engine != ENGINE_FILL) { "The debug size override needs a stamp or media brush" }
        return ToolMemory.sized(preset, sizePx, preset.opacity.base)
    }
}

/**
 * Debug bench only: `unibrush:<slug>` (say `unibrush:texture`) names one of the Unibrush modes, which the app keeps hidden
 * until switch-on (UnibrushActivation). Any other name is not ours: the caller looks it up as it always did.
 */
internal object BenchUnibrush {
    private const val PREFIX = "unibrush:"
    private fun slugOf(mode: BrushPreset) = mode.id.removePrefix("unibrush.")

    /** The mode [name] asks for, or null when [name] is not a `unibrush:` one. A slug that is not a mode refuses, naming the valid ones. */
    fun resolve(name: String, modes: List<BrushPreset> = UnibrushModes.presets()): BrushPreset? {
        if (!name.startsWith(PREFIX, ignoreCase = true)) return null
        val slug = name.substring(PREFIX.length).trim()
        return modes.firstOrNull { slugOf(it).equals(slug, ignoreCase = true) }
            ?: throw IllegalArgumentException("No Unibrush mode '$slug'. The bench knows: " + modes.joinToString(", ") { PREFIX + slugOf(it) })
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
