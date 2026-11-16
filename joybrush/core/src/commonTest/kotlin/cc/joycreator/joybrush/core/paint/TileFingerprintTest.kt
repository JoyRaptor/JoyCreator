package cc.joycreator.joybrush.core.paint

import kotlin.random.Random
import kotlin.test.*

class TileFingerprintTest {
    private val n = TileFingerprint.SIDE * TileFingerprint.SIDE

    @Test fun theSameBytesGiveTheSamePrintAndItIsStable() {
        val r = Random(7)
        val tile = ByteArray(n * 4) { r.nextInt().toByte() }
        assertEquals(TileFingerprint.ofBytes(tile), TileFingerprint.ofBytes(tile.copyOf()))
        assertEquals(32, TileFingerprint.ofBytes(tile).toString().length)
        assertNotEquals(TileFingerprint.ofBytes(tile), TileFingerprint.ofBytes(ByteArray(n * 4)))
    }

    @Test fun anyOneChangedByteAnywhereChangesThePrint() {
        val r = Random(11)
        val tile = ByteArray(n * 4) { r.nextInt().toByte() }
        val base = TileFingerprint.ofBytes(tile)
        val spots = listOf(0, 1, 2, 3, 4 * 15 + 3, 4 * 16, 4 * 255 + 3, 4 * 256, 4 * (n - 1), 4 * (n - 1) + 3) + List(400) { r.nextInt(n * 4) }
        for (i in spots) {
            val changed = tile.copyOf(); changed[i] = (changed[i] + 1 + r.nextInt(254)).toByte()
            assertNotEquals(base, TileFingerprint.ofBytes(changed), "byte $i")
        }
        // A blank tile and one with a single faint pixel.
        val blank = ByteArray(n * 4)
        for (i in spots) assertNotEquals(TileFingerprint.ofBytes(blank), TileFingerprint.ofBytes(blank.copyOf().also { it[i] = 1 }), "blank, byte $i")
    }

    @Test fun twoTexelsSwappedOrTwoChangesThatWouldCancelInAPlainSumStillShow() {
        val r = Random(13)
        val tile = ByteArray(n * 4) { r.nextInt().toByte() }
        val base = TileFingerprint.ofBytes(tile)
        repeat(200) {
            val a = r.nextInt(n); var b = r.nextInt(n); if (b == a) b = (a + 1) % n
            val swapped = tile.copyOf()
            for (c in 0 until 4) { swapped[a * 4 + c] = tile[b * 4 + c]; swapped[b * 4 + c] = tile[a * 4 + c] }
            if (!swapped.contentEquals(tile)) assertNotEquals(base, TileFingerprint.ofBytes(swapped))
            val shifted = tile.copyOf(); shifted[a * 4] = (tile[a * 4] + 1).toByte(); shifted[b * 4] = (tile[b * 4] - 1).toByte()
            assertNotEquals(base, TileFingerprint.ofBytes(shifted))
        }
    }

    @Test fun twoFlippedSignsInAPaintStoreDoNotCancel() {
        // A multiply alone carries a difference only upward: two flipped top bits cancelled in every lane.
        val r = Random(17)
        val store = ByteArray(n * 8) { r.nextInt().toByte() }
        // No NaN or infinity in the test data: exponent bits not all ones.
        for (i in 1 until store.size step 2) if (store[i].toInt() and 0x7C == 0x7C) store[i] = (store[i].toInt() and 0x7C.inv()).toByte()
        val base = TileFingerprint.ofHalves(store)
        repeat(300) {
            val a = r.nextInt(n * 4); var b = r.nextInt(n * 4); if (b == a) b = (a + 1) % (n * 4)
            val one = store.copyOf(); one[a * 2 + 1] = (one[a * 2 + 1].toInt() xor 0x80).toByte()
            assertNotEquals(base, TileFingerprint.ofHalves(one), "one sign, value $a")
            val two = one.copyOf(); two[b * 2 + 1] = (two[b * 2 + 1].toInt() xor 0x80).toByte()
            assertNotEquals(base, TileFingerprint.ofHalves(two), "two signs, values $a and $b")
        }
        val low = store.copyOf(); low[0] = (low[0] + 1).toByte()
        assertNotEquals(base, TileFingerprint.ofHalves(low), "the lowest bit of the first value")
    }

    @Test fun manyDifferentTilesGiveDifferentPrints() {
        val r = Random(19)
        val seen = HashSet<String>()
        val tile = ByteArray(n * 4)
        repeat(300) {
            tile[r.nextInt(n * 4)] = r.nextInt().toByte()
            seen.add(TileFingerprint.ofBytes(tile).toString() + tile.contentHashCode())
        }
        val prints = seen.map { it.take(32) }.toSet()
        assertEquals(seen.size, prints.size)
    }
}
