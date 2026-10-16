package cc.joycreator.joybrush.androidkit.gl.watercolour

import cc.joycreator.joybrush.androidkit.gl.ShaderLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Reflection-seeded metadata only: no GL allocation or texture-format proof. */
class WatercolourWorkingTileInventoryTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.get(owner) as T

    private fun put(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.set(owner, value)

    @Test fun inventoryDeduplicatesTileOwnersAtScaledRgba16fBytesAndExcludesImages() {
        val pass = WatercolourPass(ShaderLibrary { error("No shader access in metadata test") }, 64, 1.5f)
        val bands = field<MutableMap<Long, Int>>(pass, "bands")
        val plain = field<MutableMap<Long, Int>>(pass, "plain")
        val pooled = field<MutableMap<Long, Int>>(pass, "pooled")
        val free = field<ArrayDeque<Int>>(pass, "free")
        val highBit = Int.MIN_VALUE + 17
        bands[1] = 41
        bands[2] = highBit
        plain[3] = 41 // Same actual name in another store.
        pooled[4] = 73
        free.addLast(73) // A free alias must remain one resident allocation.
        free.addLast(89)
        put(pass, "empty", highBit) // The empty tile can alias a tile-owner identity in metadata.

        val textures = field<WatercolourTextures>(pass, "textures")
        field<MutableMap<String, WatercolourTextures.Set>>(textures, "sets")["assets"] =
            WatercolourTextures.Set(dabs = 901, backdrop = 902, grain = 903, count = 4)

        val snapshot = pass.heldWorkingTileBytes()
        val bytes = 96L * 96 * 8
        assertEquals(mapOf(41 to bytes, highBit to bytes, 73 to bytes, 89 to bytes), snapshot)
        assertEquals(bytes * 4, snapshot.values.sum())

        bands.clear()
        plain.clear()
        pooled.clear()
        free.clear()
        put(pass, "empty", 0)
        assertEquals(mapOf(41 to bytes, highBit to bytes, 73 to bytes, 89 to bytes), snapshot)
        assertEquals(emptyMap(), pass.heldWorkingTileBytes())
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot as MutableMap<Int, Long>)[7] = bytes
        }
    }

    @Test fun inventoryRefusesTileByteOverflow() {
        val pass = WatercolourPass(ShaderLibrary { error("No shader access in metadata test") }, Int.MAX_VALUE, 1f)
        field<MutableMap<Long, Int>>(pass, "bands")[0] = 1
        assertFailsWith<ArithmeticException> { pass.heldWorkingTileBytes() }
    }
}
