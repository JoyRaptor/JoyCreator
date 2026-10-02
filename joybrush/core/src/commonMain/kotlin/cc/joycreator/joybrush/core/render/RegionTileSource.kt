package cc.joycreator.joybrush.core.render

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.paint.Tiles

/** Projects one bounded tile at a time. Masks remain independent; no image-wide tile cache. */
internal class RegionTileSource(doc: JbDocument, private val source: TileSource, frameId: String?) : TileSource {
    private val plans = doc.layers.filter { it.regions.isNotEmpty() }.associate { layer ->
        layer.id to RegionDocumentOps.paintPlanUnchecked(doc, layer.id, frameId)
    }

    init {
        if (plans.isNotEmpty() && frameId != null) {
            val owners = doc.boards.count { b -> b.frames.any { it.id == frameId } }
            if (owners != 1) throw DocException("Export frame must belong to exactly one board")
        }
    }

    override fun tile(layerId: String, celId: String, tx: Int, ty: Int): ByteArray? {
        val plan = plans[layerId]
        if (plan == null || celId != plan.shared.celId) return source.tile(layerId, celId, tx, ty)
        val fetched = HashMap<String, ByteArray?>()
        var result: ByteArray? = null
        val slices = plan.tileSlices(Tiles.key(tx, ty))
        if (slices.size == 1 && slices.single().plane == plan.shared) return source.tile(layerId, celId, tx, ty)
        for (slice in slices) {
            val id = slice.plane.celId
            val bytes = if (fetched.containsKey(id)) fetched[id] else source.tile(layerId, id, tx, ty).also { fetched[id] = it }
            if (bytes == null) continue
            require(bytes.size == RegionRenderer.TILE_BYTES) { "Invalid tile for layer $layerId cel $id at $tx,$ty" }
            val target = result ?: ByteArray(RegionRenderer.TILE_BYTES).also { result = it }
            RegionPaintPlan.copyRgbaSlice(bytes, target, slice.rect)
        }
        return result
    }
}
