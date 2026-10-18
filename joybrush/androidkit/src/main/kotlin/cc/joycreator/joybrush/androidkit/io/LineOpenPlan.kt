package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelLayout

/**
 * Editable lines v1 + v1.2 H13: how an opened drawing's line payload maps onto the engine, with no line replayed. Pure.
 *
 *  - a tile a LOOK is saved for shows its look, and its tile file is the base under the items;
 *  - a tile with a slab stack but no look (a cel of slabs alone) shows nothing yet: its tile file is the base, and the
 *    engine composes base + slabs on load ([composeKeys]);
 *  - every other tile is plain, and not this plan's business.
 *
 * Tile names are the document's "tx_ty". A transparent base is absent (an absent base on a crossed tile means transparent).
 */
class LineOpenPlan private constructor(
    /** Tiles the items own: their tile file is a base (or a bottom slab), never the picture. */
    val itemTiles: Set<Triple<String, String, String>>,
    /** Base of every item-crossed tile that has a non-transparent one. */
    val bases: Map<Triple<String, String, String>, ByteArray>,
    /** Upper slabs: (layer, cel, name, seq) to its operator and bytes. */
    val slabs: Map<SlabKey, Pair<String, ByteArray>>,
    /** Slab tiles with no look: their screen tile is composed from base + slabs. */
    val composeKeys: Set<Triple<String, String, String>>,
    val records: Map<Pair<String, String>, List<StrokeRecord>>,
) {
    companion object {
        fun of(contents: JbContents): LineOpenPlan {
            val ops = HashMap<Triple<String, String, String>, Map<Long, String>>()
            for (layer in contents.doc.layers) for (cel in layer.cels) {
                for ((name, stack) in CelLayout.stacks(cel)) if (stack.size > 1) {
                    ops[Triple(layer.id, cel.id, name)] = stack.drop(1).associate { it.seq to it.op }
                }
            }
            val itemTiles = LinkedHashSet<Triple<String, String, String>>()
            val bases = LinkedHashMap<Triple<String, String, String>, ByteArray>()
            val compose = LinkedHashSet<Triple<String, String, String>>()
            for ((at, bytes) in contents.tiles) {
                val stacked = at in ops
                if (at !in contents.looks && !stacked) continue
                itemTiles += at
                if (!Tiles.isBlank(bytes)) bases[at] = bytes
                if (stacked && at !in contents.looks) compose += at
            }
            val slabs = LinkedHashMap<SlabKey, Pair<String, ByteArray>>()
            for ((key, bytes) in contents.slabs) {
                val op = ops[Triple(key.layerId, key.celId, key.key)]?.get(key.seq)
                    ?: throw JbArchiveException("a pixel slab at seq ${key.seq} on tile \"${key.key}\" is not in its cel's list")
                slabs[key] = op to bytes
            }
            return LineOpenPlan(itemTiles, bases, slabs, compose, contents.strokes.filterValues { it.isNotEmpty() })
        }

        /**
         * Why this screen cannot open [contents]' pixel slabs, as the end of a sentence; null = it can (v1.2 H13). The
         * archive has already checked that every declared slab has its bytes and that no slab shares a line's seq; this
         * adds what the engine composes: a stack starts with its plain bottom (seq 0), each upper slab's operator is one
         * the engine applies, and one seq means one operator across a cel's tiles (a slab is ONE item).
         */
        fun refusal(contents: JbContents): String? {
            for (layer in contents.doc.layers) for (cel in layer.cels) {
                if (cel.slabs.isEmpty()) continue
                val opOfSeq = HashMap<Long, String>()
                for ((_, stack) in CelLayout.stacks(cel)) {
                    val bottom = stack.first()
                    if (bottom.seq != CelLayout.BASE_SEQ || bottom.op != "normal") return "a pixel slab stack in this drawing does not start from its tile"
                    for (s in stack.drop(1)) {
                        if (s.seq < 1) return "a pixel slab in this drawing is out of order"
                        if (s.op !in OPERATORS) return "a pixel slab in this drawing uses the ${s.op} operator, which this screen does not paint yet"
                        if (opOfSeq.getOrPut(s.seq) { s.op } != s.op) return "one pixel slab in this drawing has two operators"
                    }
                }
            }
            return null
        }

        /** The operators the engine composes a slab with (NativeLines.OPERATORS). */
        private val OPERATORS = setOf("normal", "erase", "behind", "multiply", "screen")
    }
}
