package cc.joycreator.joybrush.android.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LifeDigestTest {
    private fun tile(seed: Int) = ByteArray(64) { (it * 7 + seed).toByte() }

    @Test fun theSameTilesGiveTheSameNumberInAnyOrder() {
        val a = linkedMapOf("l/c/0_0" to tile(1), "l/c/1_0" to tile(2), "l/c/0_1" to tile(3))
        val b = linkedMapOf("l/c/0_1" to tile(3), "l/c/0_0" to tile(1), "l/c/1_0" to tile(2))
        assertEquals(LifeDigest.sum(a), LifeDigest.sum(b))
    }

    @Test fun aChangedByteAMissingTileOrAMovedTileGivesAnother() {
        val a = mapOf("l/c/0_0" to tile(1), "l/c/1_0" to tile(2))
        val base = LifeDigest.sum(a)
        assertNotEquals(base, LifeDigest.sum(mapOf("l/c/0_0" to tile(1), "l/c/1_0" to tile(2).also { it[5] = 99 })))
        assertNotEquals(base, LifeDigest.sum(mapOf("l/c/0_0" to tile(1))))
        assertNotEquals(base, LifeDigest.sum(mapOf("l/c/0_0" to tile(2), "l/c/1_0" to tile(1))))
        assertNotEquals(base, LifeDigest.sum(mapOf("l/c/0_0" to tile(1), "l/c/2_0" to tile(2))))
    }
}
