package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.paint.Tiles

/** Independent CPU byte oracle for the debug probe; no renderer/transfer helper is used. */
internal object FrameDuplicatePixels {
    fun clipped(source: Map<Long, ByteArray>, rect: RectPx): Map<Long, ByteArray> =
        source.mapValues { (key, bytes) ->
            require(bytes.size == Tiles.SIZE * Tiles.SIZE * 4)
            ByteArray(bytes.size).also { out ->
                for (y in 0 until Tiles.SIZE) for (x in 0 until Tiles.SIZE) {
                    val wx = Tiles.tx(key).toLong() * Tiles.SIZE + x
                    val wy = Tiles.ty(key).toLong() * Tiles.SIZE + y
                    if (wx >= rect.x && wy >= rect.y && wx < rect.x.toLong() + rect.w &&
                        wy < rect.y.toLong() + rect.h) {
                        val offset = (y * Tiles.SIZE + x) * 4
                        bytes.copyInto(out, offset, offset, offset + 4)
                    }
                }
            }
        }

    fun halo(keys: Set<Long>): Set<Long> = buildSet {
        for (key in keys) for (dy in -1..1) for (dx in -1..1) {
            val tx = Tiles.tx(key).toLong() + dx
            val ty = Tiles.ty(key).toLong() + dy
            require(tx in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
                ty in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            add(Tiles.key(tx.toInt(), ty.toInt()))
        }
    }

    fun same(expected: ByteArray?, actual: ByteArray?): Boolean = when {
        expected == null -> actual == null || actual.all { it == 0.toByte() }
        actual == null -> expected.all { it == 0.toByte() }
        else -> expected.contentEquals(actual)
    }

    fun painted(source: Map<Long, ByteArray>): Long = source.values.sumOf { bytes ->
        (3 until bytes.size step 4).count { bytes[it] != 0.toByte() }.toLong()
    }

    fun changed(a: Map<Long, ByteArray>, b: Map<Long, ByteArray>): Long {
        var count = 0L
        for (key in a.keys + b.keys) {
            val before = a[key]; val after = b[key]
            for (offset in 0 until Tiles.SIZE * Tiles.SIZE * 4 step 4) {
                if ((0..3).any { (before?.get(offset + it) ?: 0.toByte()) !=
                        (after?.get(offset + it) ?: 0.toByte()) }) count++
            }
        }
        return count
    }
}
