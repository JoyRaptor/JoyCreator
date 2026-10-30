package cc.joycreator.joybrush.core.brush

/** Prepared once from a frozen brush/PNG owner and seed, before capture or GPU preparation. */
data class StampTipResources(val sampled: SampledTipSet? = null, val dual: DualMask? = null)
