package cc.joycreator.joybrush.androidkit.brush

import cc.joycreator.joybrush.core.brush.GrainSpec

/** Packaged production GrainTextures assets, not imported/frozen brush PNG resources. */
object PackagedBrushGrains {
    data class Choice(val label: String, val fileName: String)
    private val entries = listOf(Choice("Cloud", "cloud_256.png"), Choice("Fine cloud", "cloud_fine_256.png"),
        Choice("Broad cloud", "cloud_512.png"))
    fun choices(): List<Choice> = entries.toList()
    // GrainMath uses a nonblank image even when source says cloud. Disabled metadata stays dormant.
    fun supported(spec: GrainSpec): Boolean = !spec.enabled || spec.image.isNullOrBlank() || entries.any { it.fileName == spec.image }
}
