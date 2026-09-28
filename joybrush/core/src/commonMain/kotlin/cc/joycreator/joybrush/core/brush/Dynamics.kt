package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.dynamics.Curve
import cc.joycreator.joybrush.core.input.Angles
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs

/** Everything the evaluator needs about one dab. NaN = not available. */
data class DabInputs(
    val pressure: Float, val tilt: Float, val speedPxPerS: Float, val direction: Float,
    val lean: Float, val distancePx: Float, val random: Float, val strokeRandom: Float, val barrel: Float,
)

/**
 * "input → curve → setting" (MyPaint's model, R3). Every numeric brush setting is a base value plus
 * zero or more curves; the curves are combined with the base by the Param's `combine` mode. The
 * result is NOT clamped here — clamping is per setting (size stays above 0, opacity stays in 0..1) and
 * belongs to whoever owns the setting, so one evaluator serves all of them.
 */
object Dynamics {

    private val HALF_PI = (PI / 2).toFloat()
    private val PI_F = PI.toFloat()
    private val TAU_F = (PI * 2).toFloat()

    fun normalise(input: BrushInput, d: DabInputs): Float = when (input) {
        BrushInput.pressure -> d.pressure
        BrushInput.tilt -> d.tilt / HALF_PI
        BrushInput.speed -> cap1(d.speedPxPerS / 3000f)
        BrushInput.direction -> (d.direction + PI_F) / TAU_F
        BrushInput.lean -> (d.lean + PI_F) / TAU_F
        BrushInput.attack -> abs(Angles.wrap((d.lean - d.direction).toDouble()).toFloat()) / PI_F
        BrushInput.distance -> cap1(d.distancePx / 1000f)
        BrushInput.random -> d.random
        BrushInput.strokeRandom -> d.strokeRandom
        BrushInput.barrel -> (d.barrel + PI_F) / TAU_F
    }

    fun eval(param: Param, d: DabInputs): Float {
        val c = CurveCache.compiled(param)
        if (c.add) {
            var sum = 0f
            for (i in c.curves.indices) {
                val n = normalise(c.inputs[i], d)
                if (!n.isNaN()) sum += c.curves[i].eval(n)
            }
            return param.base + sum
        }
        var product = 1f
        for (i in c.curves.indices) {
            val n = normalise(c.inputs[i], d)
            if (!n.isNaN()) product *= c.curves[i].eval(n)
        }
        return param.base * product
    }

    /** min(v, 1) that lets NaN through: an input the device cannot report must not become 0. */
    private fun cap1(v: Float): Float = if (v.isNaN() || v <= 1f) v else 1f
}

/** One [Param] with its curves already built, and its combine mode already decided. */
internal class Compiled(val inputs: List<BrushInput>, val curves: List<Curve>, val add: Boolean) {

    companion object {
        val EMPTY = Compiled(emptyList(), emptyList(), false)

        /**
         * Curves are built from the file's `[[x,y],…]` points. A curve with no usable point is left
         * out entirely: an unvalidated brush file must not throw inside a stroke, and
         * [BrushValidate] is what tells the user the file is broken. A combine mode that is neither
         * "multiply" nor "add" is read as "multiply", the default.
         */
        fun of(p: Param): Compiled {
            val inputs = ArrayList<BrushInput>(p.inputs.size)
            val curves = ArrayList<Curve>(p.inputs.size)
            for (ic in p.inputs) {
                val points = ic.curve.mapNotNull { if (it.size == 2) it[0] to it[1] else null }
                if (points.isEmpty()) continue
                inputs.add(ic.input)
                curves.add(Curve(points))
            }
            return Compiled(inputs, curves, p.combine == "add")
        }
    }
}

internal class CacheEntry(val param: Param, val compiled: Compiled)

/**
 * Curves are built once per [Param] and kept, because eval() runs for every dab of every stroke, and
 * a dab evaluates up to eight settings (size, opacity, flow, angle, hardness, two grain depths,
 * scatter) — so the cache holds the preset's Params, keyed by IDENTITY, and is scanned. Identity is
 * the point: a [Param] is a data class, and hashing one walks the whole curve list on every eval.
 *
 * The array is copy-on-write behind a volatile field: readers (the render thread, several times per
 * dab) only ever see a finished array, so there is no lock, no allocation on a hit, and a half-built
 * entry can never be seen. Two threads compiling the same new Param at once is harmless — one store
 * wins. The cap keeps a program that invents endless Params from growing this without bound; a real
 * preset needs a handful.
 */
internal object CurveCache {

    private const val MAX_ENTRIES = 32

    @Volatile
    private var entries: Array<CacheEntry> = emptyArray()

    /** How many times curves have been built from a file. The tests read this; nothing else does. */
    var builds: Int = 0
        private set

    internal fun compiled(param: Param): Compiled {
        if (param.inputs.isEmpty()) return Compiled.EMPTY
        val snapshot = entries
        for (i in snapshot.indices) {
            val e = snapshot[i]
            if (e.param === param) return e.compiled
        }
        val built = Compiled.of(param)
        builds++
        val size = if (snapshot.size >= MAX_ENTRIES) 1 else snapshot.size + 1
        entries = Array(size) { i -> if (i < snapshot.size) snapshot[i] else CacheEntry(param, built) }
        return built
    }
}
