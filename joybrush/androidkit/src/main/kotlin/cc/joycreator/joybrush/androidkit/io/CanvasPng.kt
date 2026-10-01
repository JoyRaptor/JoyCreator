package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.render.TileSource

/** Export the active board through the same document compositor used by other exports. */
object CanvasPng {
    fun encode(contents: JbContents, includePaper: Boolean): ByteArray {
        val doc = contents.doc
        val problems = DocOps.validate(doc)
        if (problems.isNotEmpty()) throw JbArchiveException(problems.joinToString("; "))
        if (doc.layers.any { it.kind != LayerKind.PAINT || it.animatedIn != null }) {
            throw JbArchiveException("this PNG export requires static paint layers")
        }
        val board = doc.boards.firstOrNull { it.id == doc.activeBoardId }
            ?: doc.boards.singleOrNull()
            ?: throw JbArchiveException("choose a board to export")
        for (layer in doc.layers) for (cel in DocOps.storedCels(layer)) for (key in cel.tiles) {
            if (Triple(layer.id, cel.id, key) !in contents.tiles) {
                throw JbArchiveException("a tile of \"${layer.name}\" is missing; the PNG was not written")
            }
        }
        val source = TileSource { layer, cel, tx, ty -> contents.tiles[Triple(layer, cel, DocOps.key(tx, ty))] }
        val pixels = RegionRenderer.render(doc, source, board.rect, null, if (includePaper) doc.paper.color else null)
        return PngWriter.encode(board.rect.w, board.rect.h, pixels)
    }
}
