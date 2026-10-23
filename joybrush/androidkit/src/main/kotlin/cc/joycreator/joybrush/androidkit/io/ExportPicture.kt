package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.TILE_SIZE
import cc.joycreator.joybrush.core.doc.hasPixels
import cc.joycreator.joybrush.core.render.MAX_REGION_PX
import cc.joycreator.joybrush.core.render.SavedCelTileSource
import cc.joycreator.joybrush.core.render.TileSource

/**
 * What every exporter reads: the picture as the screen shows it, and how far it reaches.
 *
 * A drawing with editable lines saves each tile its lines cross twice: the base (`Cel.tiles`) and the composed look
 * (`Cel.looks`), beside the line records and pixel slabs that keep it editable. The exporters render pixels only and
 * refuse line storage outright (`RegionRenderer.unsupportedContentReason`), so every drawing with editable lines used
 * to refuse to export. [asShown] hands them each look in place of its base and leaves the editing data out; the
 * drawing itself is untouched. INK layers keep their stroke records (the legacy replay path reads them).
 */
object ExportPicture {

    fun asShown(c: JbContents): JbContents {
        val lineData = c.looks.isNotEmpty() || c.slabs.isNotEmpty() || c.doc.layers.any { l ->
            (l.cels + listOfNotNull(l.mask)).any { cel ->
                cel.looks.isNotEmpty() || cel.slabs.isNotEmpty() || (cel.strokesFile != null && (l.kind != LayerKind.INK || cel === l.mask))
            }
        }
        if (!lineData) return c
        // A baked line may leave upper slabs but no line/look. Reading only the bottom tile
        // would silently lose those strokes. Use exact saved looks where present and compose
        // slab-only stacks otherwise; validate every physical cel before preparing output.
        val source = SavedCelTileSource(c.doc,
            TileSource { layer, cel, tx, ty -> c.tiles[Triple(layer, cel, DocOps.key(tx, ty))] },
            readLook = { layer, cel, tx, ty -> c.looks[Triple(layer, cel, DocOps.key(tx, ty))] },
            readSlab = { layer, cel, seq, tx, ty -> c.slabs[SlabKey(layer, cel, DocOps.key(tx, ty), seq)] },
            hasLineRecords = { layer, cel -> c.strokes[layer to cel]?.isNotEmpty() ?: true })
        source.validate()
        val shownTiles = LinkedHashMap(c.tiles)
        val doc = c.doc.copy(layers = c.doc.layers.map { l ->
            fun shown(cel: Cel): Cel {
                val keys = (cel.tiles + cel.looks + cel.slabs.map { it.key }).distinct().sorted()
                val shownKeys = ArrayList<String>(keys.size)
                for (key in keys) {
                    val split = key.indexOf('_')
                    val tile = source.tile(l.id, cel.id, key.substring(0, split).toInt(), key.substring(split + 1).toInt())
                    val address = Triple(l.id, cel.id, key)
                    if (tile == null) shownTiles.remove(address) else {
                        shownTiles[address] = tile
                        shownKeys += key
                    }
                }
                return cel.copy(tiles = shownKeys, looks = emptyList(), slabs = emptyList(),
                    strokesFile = cel.strokesFile.takeIf { l.kind == LayerKind.INK && cel !== l.mask })
            }
            l.copy(cels = l.cels.map(::shown), mask = l.mask?.let(::shown))
        })
        val inkCels = doc.layers.filter { it.kind == LayerKind.INK }.flatMapTo(HashSet()) { l -> l.cels.map { l.id to it.id } }
        return c.copy(doc = doc, tiles = shownTiles, slabs = emptyMap(),
            looks = emptyMap(), strokes = c.strokes.filterKeys { it in inkCels })
    }

    /**
     * The smallest rectangle holding every painted pixel of the visible picture layers, in any frame, in document
     * pixels; null when nothing is drawn. Masks are not picture. Read [asShown] contents, so lines count as drawn.
     */
    fun paintedBounds(c: JbContents): RectPx? {
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
        for (layer in c.doc.layers) {
            if (!layer.visible || !layer.kind.hasPixels) continue
            for (cel in layer.cels) for (key in cel.tiles) {
                val box = alphaBox(c.tiles[Triple(layer.id, cel.id, key)] ?: continue) ?: continue
                val cut = key.indexOf('_')
                val ox = key.substring(0, cut).toInt() * TILE_SIZE
                val oy = key.substring(cut + 1).toInt() * TILE_SIZE
                x0 = minOf(x0, ox + box[0]); y0 = minOf(y0, oy + box[1])
                x1 = maxOf(x1, ox + box[2]); y1 = maxOf(y1, oy + box[3])
            }
        }
        return if (x1 < x0) null else RectPx(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    /** The whole drawing's rectangle (LAYER_OPS §6), or a refusal in words: nothing drawn, or too big for one picture. */
    fun wholeDrawing(c: JbContents): RectPx {
        val r = paintedBounds(c) ?: throw JbArchiveException("nothing is drawn yet, so there is nothing to export")
        if (r.w.toLong() * r.h > MAX_REGION_PX || r.w > MAX_SIDE || r.h > MAX_SIDE) {
            throw JbArchiveException("the whole drawing is ${r.w} by ${r.h} pixels, more than one picture can hold. " +
                "Export its boards one at a time")
        }
        return r
    }

    /** First and last columns and rows (inclusive) with any alpha in an RGBA tile, or null for a blank tile. */
    private fun alphaBox(tile: ByteArray): IntArray? {
        var minX = TILE_SIZE; var minY = TILE_SIZE; var maxX = -1; var maxY = -1
        for (y in 0 until TILE_SIZE) {
            val row = y * TILE_SIZE * 4
            var first = -1; var last = -1
            for (x in 0 until TILE_SIZE) if (tile[row + x * 4 + 3].toInt() != 0) { if (first < 0) first = x; last = x }
            if (first < 0) continue
            if (minY == TILE_SIZE) minY = y
            maxY = y
            minX = minOf(minX, first); maxX = maxOf(maxX, last)
        }
        return if (maxY < 0) null else intArrayOf(minX, minY, maxX, maxY)
    }

    /** The most a PNG side can hold. */
    private const val MAX_SIDE = 65535
}
