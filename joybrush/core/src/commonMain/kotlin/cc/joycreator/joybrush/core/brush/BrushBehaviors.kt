package cc.joycreator.joybrush.core.brush

import kotlin.math.abs

/** Sheet chip choices, compiled to existing Params. These enums are not serialized. */
enum class PressureBehavior { width, ink, both, none }
enum class TiltBehavior { angle, flatten, size, none, sideFade }

/**
 * One wiring path for the Simple sheet and preset modes, with no new per-dab pressure/tilt branches.
 * Chip choices replace that input's curves; advanced curves on other inputs stay as they were.
 * Custom additive zero-base sums and multiplicative aspect recipes that cannot be rewired without
 * changing other inputs are refused in words, rather than accepting a chip that does nothing.
 */
object BrushBehaviors {
    fun rotation(p: BrushPreset, source: TipAngleSource): BrushPreset = p.copy(
        version = maxOf(p.version, VERSION_UNIBRUSH),
        tip = p.tip.copy(angleSource = source, followDirection = false),
    )

    fun pressure(p: BrushPreset, behavior: PressureBehavior): BrushPreset = p.copy(
        size = pressureParam(p.size, behavior == PressureBehavior.width || behavior == PressureBehavior.both,
            p.tip.minPx..BrushValidate.MAX_SIZE_PX),
        flow = pressureParam(p.flow, behavior == PressureBehavior.ink || behavior == PressureBehavior.both),
    )

    fun tilt(p: BrushPreset, behavior: TiltBehavior): BrushPreset {
        val oldAspect = p.tip.aspectDynamics?.without(BrushInput.tilt)
        val oldFade = p.tip.fade?.let { fade ->
            fade.copy(amount = fade.amount.without(BrushInput.tilt))
        }
        var result = p.copy(size = p.size.without(BrushInput.tilt), tip = p.tip.copy(aspectDynamics = oldAspect, fade = oldFade))
        // Editing a legacy hybrid explicitly separates lean from travel; merely loading it never does.
        if (p.tip.angleSource == null && p.tip.followDirection) result = rotation(result, TipAngleSource.stroke)
        if (result.tip.angleSource == TipAngleSource.penAzimuth) result = rotation(result, TipAngleSource.fixed)
        return when (behavior) {
            TiltBehavior.angle -> rotation(result, TipAngleSource.penAzimuth)
            TiltBehavior.flatten -> {
                // Additive works from a round tip (aspect == 0); multiply-by-zero is a dead control.
                if (oldAspect != null && oldAspect.combine != "add" && oldAspect.inputs.isNotEmpty()) {
                    throw BrushException("This custom aspect multiplies other inputs. Reset its aspect dynamics before choosing Tilt flatten.")
                }
                val base = oldAspect?.base ?: p.tip.aspect
                var low = base
                var high = base
                for (input in oldAspect?.inputs ?: emptyList()) {
                    val values = input.curve.mapNotNull { it.getOrNull(1)?.takeIf { v -> v.isFinite() } }
                    // Missing sensors contribute zero to an additive recipe.
                    low += minOf(0f, values.minOrNull() ?: 0f)
                    high += maxOf(0f, values.maxOrNull() ?: 0f)
                }
                if (low < 0f && high > 0f) {
                    throw BrushException("This custom aspect changes its thin axis. Reset its aspect dynamics before choosing Tilt flatten.")
                }
                val sign = if (low < 0f) -1f else 1f
                val delta = sign * .85f * (1f - abs(base))
                result.copy(version = maxOf(result.version, VERSION_CONTACT), tip = result.tip.copy(
                    aspectDynamics = Param(base, (oldAspect?.inputs ?: emptyList()) + curve(BrushInput.tilt, 0f, delta), "add")))
            }
            TiltBehavior.size -> result.copy(size = multiplier(result.size, BrushInput.tilt, 1f, 2f))
            TiltBehavior.sideFade -> {
                if (oldFade?.amount?.inputs?.isNotEmpty() == true) {
                    throw BrushException("This custom fade uses other inputs. Reset its fade dynamics before choosing Tilt side-fade.")
                }
                result.copy(version = maxOf(result.version, VERSION_TIP_PROFILE), tip = result.tip.copy(fade = TipFadeSpec(
                    amount = Param(0f, listOf(curve(BrushInput.tilt, 0f, 1f)), "add"),
                    angle = p.tip.fade?.angle ?: 0f, relativeTo = FadeFrame.penAzimuth)))
            }
            TiltBehavior.none -> result
        }
    }

    /**
     * The copy of an older stamp brush that a line freezes. Before version 7 grain took its tilt from the newest pen event
     * of each batch, which a recording cannot give back, so with a tilting pen Ink and Marker could never stay lines. From
     * 7 the tilt travels on each dab. Nothing else changes for a brush whose depths and hardness are plain numbers; one with
     * dynamics on them would start to obey them, so it keeps its version (and stays pixels under a tilting pen).
     */
    fun forLine(p: BrushPreset): BrushPreset {
        val plain = p.tip.hardness.inputs.isEmpty() && p.tipTexture.depth.inputs.isEmpty() && p.paperGrain.depth.inputs.isEmpty()
        return if (p.engine == "stamp" && p.version < VERSION_CONTACT && plain) p.copy(version = VERSION_CONTACT) else p
    }

    private fun pressureParam(p: Param, enabled: Boolean, range: ClosedFloatingPointRange<Float>? = null): Param {
        // Disabling pressure keeps its full-pressure strength, including imported additive recipes
        // such as base 0 + pressure 0..1. Simply deleting that curve would erase all their ink.
        val pressure = p.inputs.filter { it.input == BrushInput.pressure }
        val full = if (pressure.isEmpty()) p.base else Dynamics.eval(p.copy(inputs = pressure), FULL_PRESSURE)
        if (!full.isFinite()) throw BrushException("This custom pressure response has no finite full-pressure value.")
        val others = p.without(BrushInput.pressure)
        if (range != null && full !in range && others.inputs.isNotEmpty()) {
            throw BrushException("This custom size needs other inputs to stay in range. Reset its dynamics before choosing this behavior.")
        }
        val clean = others.copy(base = if (range == null) full else full.coerceIn(range))
        return if (enabled) multiplier(clean, BrushInput.pressure, 0f, 1f) else clean
    }

    private fun multiplier(p: Param, input: BrushInput, low: Float, high: Float): Param {
        val clean = p.without(input)
        if (p.combine == "add" && p.base == 0f) {
            throw BrushException("This custom setting adds its value from other inputs. Reset those dynamics before choosing this behavior.")
        }
        val response = if (p.combine == "add") curve(input, p.base * (low - 1f), p.base * (high - 1f))
            else curve(input, low, high)
        return clean.copy(inputs = clean.inputs + response)
    }

    private fun curve(input: BrushInput, low: Float, high: Float) =
        InputCurve(input, listOf(listOf(0f, low), listOf(1f, high)))

    private fun Param.without(input: BrushInput): Param = copy(inputs = inputs.filterNot { it.input == input })

    private val FULL_PRESSURE = DabInputs(1f, Float.NaN, Float.NaN, Float.NaN, Float.NaN,
        Float.NaN, Float.NaN, Float.NaN, Float.NaN)
}
