package cc.joycreator.joybrush.core.render

import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.SlabRef
import cc.joycreator.joybrush.core.vector.CelLayout
import cc.joycreator.joybrush.core.vector.InkTiles

/**
 * Reads the pixels a saved cel says are on screen, one tile at a time.
 *
 * A declared saved look is authoritative: it already contains the exact line/slab composition (and any brush grain),
 * so it is returned verbatim. Cels without looks retain their ordinary pixels; when they declare a slab stack, the
 * bottom tile is fetched from [base] once and the remaining slabs are applied in seq order. This mirrors the archive
 * contract in [CelLayout] and deliberately does not replay line records.
 *
 * [validate] is an eager, bounded preflight for export. It visits every cel, including hidden and noncurrent cels, but
 * holds only one tile at a time. No document-wide pixel cache is kept.
 */
class SavedCelTileSource(
    private val document: JbDocument,
    private val base: TileSource,
    private val readLook: (layerId: String, celId: String, tx: Int, ty: Int) -> ByteArray?,
    private val readSlab: (layerId: String, celId: String, seq: Long, tx: Int, ty: Int) -> ByteArray?,
    /** True only when the archive has actual line records for this cel; storage may contain an empty strokes file. */
    private val hasLineRecords: (layerId: String, celId: String) -> Boolean = { _, _ -> true },
) : TileSource {

    private val layersById = document.layers.associateBy { it.id }
    // Index only metadata. Rebuilding every stack for each output tile would make a large
    // slab-only export quadratic; pixel arrays remain uncached and are read one at a time.
    private val stackIndexes = HashMap<Pair<String, String>, Map<String, List<SlabRef>>>()
    private val explicitSlabKeys = HashMap<Pair<String, String>, Set<String>>()

    /** Verify every declared saved payload before an exporter opens or writes its output. */
    fun validate() {
        for (layer in document.layers) {
            val mask = layer.mask
            if (mask != null && hasItemPayload(mask)) {
                throw RegionException("Mask of layer \"${layer.id}\" has line, look, or slab payloads that static export cannot compose")
            }
            for (cel in layer.cels) {
                val hasLines = layer.kind != LayerKind.INK && cel.strokesFile != null && hasLineRecords(layer.id, cel.id)
                if (hasLines && cel.looks.isEmpty()) {
                    throw RegionException("Cel \"${cel.id}\" of layer \"${layer.id}\" has lines but no saved look; export will not replay or drop them")
                }
                if (layer.kind != LayerKind.INK && cel.looks.isNotEmpty() && !hasLineRecords(layer.id, cel.id)) {
                    throw RegionException("Cel \"${cel.id}\" of layer \"${layer.id}\" has saved looks but no line records")
                }
                for (key in cel.looks) {
                    val (tx, ty) = parseKey(layer.id, cel.id, key)
                    checked(readLook(layer.id, cel.id, tx, ty), "look", layer.id, cel.id, key)
                }
                val stacks = stacksOrRefuse(layer, cel)
                for ((key, stack) in stacks) {
                    val (tx, ty) = parseKey(layer.id, cel.id, key)
                    if (stack.isEmpty()) continue
                    // CelLayout defines the first entry as the bottom tile payload, regardless of its seq (including
                    // seq 0). It is stored at .rgba and must be fetched exactly once from the ordinary tile source.
                    checked(base.tile(layer.id, cel.id, tx, ty), "bottom slab", layer.id, cel.id, key)
                    for (upper in stack.drop(1)) {
                        checked(readSlab(layer.id, cel.id, upper.seq, tx, ty), "slab at seq ${upper.seq}", layer.id, cel.id, key)
                    }
                }
                // A ref outside CelLayout's listed tile domain is malformed, and must not disappear during preflight.
                val tileKeys = cel.tiles.toHashSet()
                for (ref in cel.slabs) if (ref.key !in tileKeys) {
                    throw RegionException("Cel \"${cel.id}\" of layer \"${layer.id}\" declares a slab on unlisted tile \"${ref.key}\"")
                }
            }
        }
    }

    override fun tile(layerId: String, celId: String, tx: Int, ty: Int): ByteArray? {
        val layer = layersById[layerId]
            ?: throw RegionException("Unknown layer \"$layerId\" in saved-cel export")
        val cel = layer.cels.firstOrNull { it.id == celId }
            ?: layer.mask?.takeIf { it.id == celId }
            ?: throw RegionException("Unknown cel \"$celId\" of layer \"$layerId\" in saved-cel export")
        if (cel === layer.mask && hasItemPayload(cel)) {
            throw RegionException("Mask of layer \"$layerId\" has line, look, or slab payloads that static export cannot compose")
        }
        val key = DocOps.key(tx, ty)
        if (key in cel.looks) {
            return checked(readLook(layerId, celId, tx, ty), "look", layerId, celId, key)
        }
        val stacks = stacksOrRefuse(layer, cel)
        if (key !in explicitSlabKeys.getValue(layerId to celId)) return checkedBaseOrNull(layerId, celId, tx, ty)
        val stack = stacks[key] ?: return checkedBaseOrNull(layerId, celId, tx, ty)
        if (stack.isEmpty()) return checkedBaseOrNull(layerId, celId, tx, ty)

        val bottom = checked(base.tile(layerId, celId, tx, ty), "bottom slab", layerId, celId, key)
        val px = FloatArray(RegionRenderer.TILE_BYTES)
        apply(SlabRef(key, stack.first().seq, stack.first().op), bottom, px, layerId, celId)
        for (ref in stack.drop(1)) {
            val bytes = checked(readSlab(layerId, celId, ref.seq, tx, ty), "slab at seq ${ref.seq}", layerId, celId, key)
            apply(ref, bytes, px, layerId, celId)
        }
        return InkTiles.quantise(px)
    }

    private fun checkedBaseOrNull(layerId: String, celId: String, tx: Int, ty: Int): ByteArray? {
        val bytes = base.tile(layerId, celId, tx, ty) ?: return null
        return checked(bytes, "tile", layerId, celId, DocOps.key(tx, ty))
    }

    private fun stacksOrRefuse(layer: Layer, cel: Cel): Map<String, List<SlabRef>> {
        stackIndexes[layer.id to cel.id]?.let { return it }
        val duplicateTile = cel.tiles.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicateTile != null) {
            throw RegionException("Cel \"${cel.id}\" of layer \"${layer.id}\" lists tile \"${duplicateTile.key}\" twice")
        }
        val duplicateLook = cel.looks.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicateLook != null) {
            throw RegionException("Cel \"${cel.id}\" of layer \"${layer.id}\" lists look tile \"${duplicateLook.key}\" twice")
        }
        try {
            for (key in cel.tiles) parseKey(layer.id, cel.id, key)
            for (ref in cel.slabs) {
                parseKey(layer.id, cel.id, ref.key)
                if (ref.seq < 0) {
                    throw RegionException("Cel \"${cel.id}\" of layer \"${layer.id}\" has a negative slab seq ${ref.seq} on tile \"${ref.key}\"")
                }
                if (!cc.joycreator.joybrush.core.doc.SlabOps.known(ref.op)) {
                    throw RegionException("Cel \"${cel.id}\" of layer \"${layer.id}\" has unsupported slab operator \"${ref.op}\"")
                }
            }
            val duplicate = cel.slabs.groupBy { it.key to it.seq }.entries.firstOrNull { it.value.size > 1 }
            if (duplicate != null) {
                throw RegionException("Cel \"${cel.id}\" of layer \"${layer.id}\" has duplicate slab seq ${duplicate.key.second} on tile \"${duplicate.key.first}\"")
            }
            return CelLayout.stacks(cel).also {
                stackIndexes[layer.id to cel.id] = it
                explicitSlabKeys[layer.id to cel.id] = cel.slabs.mapTo(HashSet()) { ref -> ref.key }
            }
        } catch (e: RegionException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw RegionException("Cel \"${cel.id}\" of layer \"${layer.id}\" has an invalid saved slab layout")
        }
    }

    private fun parseKey(layerId: String, celId: String, key: String): Pair<Int, Int> {
        if (!DocOps.isTileKey(key)) throw RegionException("Cel \"$celId\" of layer \"$layerId\" declares invalid tile key \"$key\"")
        return try {
            val parts = key.split('_', limit = 2)
            parts[0].toInt() to parts[1].toInt()
        } catch (_: NumberFormatException) {
            throw RegionException("Cel \"$celId\" of layer \"$layerId\" declares invalid tile key \"$key\"")
        }
    }

    private fun apply(ref: SlabRef, bytes: ByteArray, pixels: FloatArray, layerId: String, celId: String) {
        try {
            InkTiles.applyOp(ref.op, bytes, pixels)
        } catch (e: IllegalArgumentException) {
            throw RegionException("Cel \"$celId\" of layer \"$layerId\" has unsupported slab operator \"${ref.op}\"")
        }
    }

    private fun checked(bytes: ByteArray?, what: String, layerId: String, celId: String, key: String): ByteArray {
        if (bytes == null) throw RegionException("Cel \"$celId\" of layer \"$layerId\" is missing declared $what bytes on tile \"$key\"")
        if (bytes.size != RegionRenderer.TILE_BYTES) {
            throw RegionException("Cel \"$celId\" of layer \"$layerId\" has invalid $what bytes on tile \"$key\" (${bytes.size}, expected ${RegionRenderer.TILE_BYTES})")
        }
        return bytes
    }

    private fun hasItemPayload(cel: Cel): Boolean = cel.strokesFile != null || cel.looks.isNotEmpty() ||
        cel.slabs.isNotEmpty() || cel.floatTiles.isNotEmpty()
}
