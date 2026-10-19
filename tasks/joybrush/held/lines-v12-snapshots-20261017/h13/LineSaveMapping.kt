package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.doc.SlabRef
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/**
 * What the engine holds of one plane's items at the moment of a save (editable lines v1 + v1.2). Tile keys are the
 * document's "tx_ty" names so the mapping stays pure.
 */
class PlaneItems(
    val layerId: String,
    val celId: String,
    /** The plane's lines in seq order. */
    val records: List<StrokeRecord>,
    /** Every tile some item crosses. */
    val crossed: Set<String>,
    /** The base (what was under the first item) of the tiles that have one that is not transparent. */
    val bases: Map<String, ByteArray>,
    /** The plane's pixel slabs above the base: seq to its operator and one tile of bytes per tile it crosses. */
    val slabs: Map<Long, SlabBytes>,
)

class SlabBytes(val op: String, val tiles: Map<String, ByteArray>)

/**
 * Editable lines into a board snapshot (Codex's document v11 layout, unchanged; NATIVE_LINES_V1 + V12 H13). Pure.
 *
 *  - `Cel.tiles` + the tile files = the BOTTOM slab: the base for tiles items cross (transparent bases are not written
 *    unless a slab sits above, because a stack needs its bottom), else the plain tile;
 *  - `Cel.looks` + look files = the composed tile of every tile items cross, for a cel that has lines (a cel of slabs
 *    alone keeps no look: the document says a look is only for tiles lines cross, and the drawing composes them on open);
 *  - `Cel.slabs` + slab files = the WHOLE stack (seq 0 first, then each slab above) of every tile a slab sits on;
 *  - `strokes.jbs` = the records; `JbDocument.brushes` = the frozen brushes.
 */
object LineSaveMapping {
    fun apply(c: JbContents, planes: List<PlaneItems>, brushes: Map<String, BrushPreset>): JbContents {
        // A plane whose cel the document does not have is history only (a deleted layer or frame): it is never written.
        val docCels = c.doc.layers.flatMap { l -> l.cels.map { l.id to it.id } }.toSet()
        val live = planes.filter { (it.layerId to it.celId) in docCels && (it.records.isNotEmpty() || it.slabs.isNotEmpty()) }
        if (live.isEmpty()) return c
        val tiles = LinkedHashMap(c.tiles)
        val looks = LinkedHashMap(c.looks)
        val strokes = LinkedHashMap(c.strokes)
        val slabFiles = LinkedHashMap(c.slabs)
        val celLooks = HashMap<Pair<String, String>, MutableList<String>>()
        val celStacks = HashMap<Pair<String, String>, MutableList<SlabRef>>()
        for (plane in live) {
            val cel = plane.layerId to plane.celId
            val hasLines = plane.records.isNotEmpty()
            if (hasLines) strokes[cel] = plane.records
            val above = HashMap<String, MutableList<SlabRef>>()
            for ((seq, slab) in plane.slabs.toSortedMap()) for ((name, bytes) in slab.tiles) {
                slabFiles[SlabKey(plane.layerId, plane.celId, name, seq)] = bytes
                above.getOrPut(name) { ArrayList() } += SlabRef(name, seq, slab.op)
            }
            for (name in (plane.crossed + above.keys).sorted()) {
                val at = Triple(plane.layerId, plane.celId, name)
                val shown = c.tiles[at]
                val base = plane.bases[name]
                val stack = above[name]
                if (stack == null) {
                    // Lines only: the tile file is the base; the look is what the screen shows.
                    if (shown == null && base == null) continue          // nothing under, nothing on top
                    looks[at] = shown ?: ByteArray(TILE_BYTES)            // a look that erased everything is still a look
                    celLooks.getOrPut(cel) { ArrayList() }.add(name)
                    if (base != null) tiles[at] = base else tiles.remove(at)
                } else {
                    // A slab sits here: the bottom of the stack is the base (a transparent one is written out in full).
                    tiles[at] = base ?: ByteArray(TILE_BYTES)
                    if (hasLines) {
                        looks[at] = shown ?: ByteArray(TILE_BYTES)
                        celLooks.getOrPut(cel) { ArrayList() }.add(name)
                    }
                    celStacks.getOrPut(cel) { ArrayList() } += SlabRef(name, 0L, "normal")
                    celStacks.getValue(cel) += stack.sortedBy { it.seq }
                }
            }
        }
        val layers = c.doc.layers.map { layer ->
            layer.copy(cels = layer.cels.map { cel ->
                val at = layer.id to cel.id
                if (at !in strokes && at !in celStacks) cel else cel.copy(
                    tiles = tiles.keys.filter { it.first == layer.id && it.second == cel.id }.map { it.third }.sorted(),
                    looks = celLooks[at].orEmpty().sorted(),
                    strokesFile = if (at in strokes) "strokes.jbs" else null,
                    slabs = celStacks[at].orEmpty().sortedWith(compareBy({ it.key }, { it.seq })),
                )
            })
        }
        val doc = c.doc.copy(layers = layers, brushes = brushes.keys.sorted(), version = maxOf(c.doc.version, 11))
        return c.copy(doc = doc, tiles = tiles, looks = looks, strokes = strokes, slabs = slabFiles, brushes = brushes)
    }
}
