package cc.joycreator.joybrush.android.bench

import android.util.Log
import cc.joycreator.joybrush.androidkit.JbCanvasView
import java.util.zip.CRC32

/**
 * What a drawing holds, as two checksums in the log: its picture tiles and its paint store tiles (JBX-37, the lifecycle
 * gate). Two digests that agree are the same tiles under the same names with the same bytes, whatever happened to the
 * screen between them (a save and an open, a rebuilt screen, lost graphics).
 *
 * Read as the autosave reads (the wash stays wet), so taking a digest changes nothing.
 */
object LifeDigest {
    fun log(canvas: JbCanvasView, label: String) {
        canvas.snapshot({ c ->
            Log.i(BenchRunner.TAG, "life: digest $label: ${c.tiles.size} picture tiles ${hex(sum(c.tiles))}, " +
                "${c.mediaTiles.size} paint store tiles ${hex(sum(c.mediaTiles))}, ${c.doc.layers.size} layers" +
                // Editable lines (JBX-42): a tile that lines cross is saved as its look (and its paint between lines as
                // slabs), not as a picture tile; then how many lines the drawing keeps AS lines, and their records.
                (if (c.looks.isEmpty()) "" else ", ${c.looks.size} looks ${hex(sum(c.looks))}") +
                (if (c.slabs.isEmpty()) "" else ", ${c.slabs.size} slabs ${hex(sum(c.slabs))}") +
                (if (c.strokes.isEmpty()) "" else ", ${c.strokes.values.sumOf { it.size }} editable lines ${hex(sum(c.strokes.mapValues { (_, l) -> cc.joycreator.joybrush.core.stroke.StrokeCodec.encodeAll(l) }))}"))
        }, { why -> Log.w(BenchRunner.TAG, "life: digest $label FAILED: $why") }, keepWet = true)
    }

    /** One number for a set of named tiles: the same for the same names and bytes in any order, different otherwise. */
    fun <K> sum(tiles: Map<K, ByteArray>): Long {
        val crc = CRC32()
        for ((name, bytes) in tiles.map { (k, v) -> k.toString() to v }.sortedBy { it.first }) {
            crc.update(name.toByteArray(Charsets.UTF_8)); crc.update(0); crc.update(bytes)
        }
        return crc.value
    }

    private fun hex(v: Long) = java.lang.Long.toHexString(v).padStart(8, '0')
}
