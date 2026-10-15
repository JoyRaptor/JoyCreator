package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.io.ColdRecordedLoadPlan

/**
 * Read-only borrowed names for one cold debug inspection. Never release, recycle or publish them.
 * The consumer must check [current] before and after every GL operation. No driver texture-format
 * claim is made by the engine's float registry; actual floating-point readback is still required.
 */
internal class ColdUploadedOwners(
    entries: List<Owner>, forbidden: Set<Int>, floatNames: Set<Int>,
    val current: () -> Boolean,
    val unresolvedLinePlanes: Int,
) {
    data class Address(val kind: ColdRecordedLoadPlan.Kind, val layerId: String,
                       val engineCelId: String?, val key: Long, val store: String? = null,
                       val seq: Long? = null, val op: String? = null)
    data class Owner(val address: Address, val texture: Int)

    private val names = entries.toTypedArray()
    private val forbiddenNames = forbidden.toSet()
    private val floatRegistry = floatNames.toSet()
    val entries: List<Owner> = object : AbstractList<Owner>() {
        override val size: Int get() = names.size
        override fun get(index: Int): Owner = names[index]
    }
    fun isForbidden(texture: Int): Boolean = texture in forbiddenNames
    fun floatRegistered(texture: Int): Boolean = texture in floatRegistry
}
