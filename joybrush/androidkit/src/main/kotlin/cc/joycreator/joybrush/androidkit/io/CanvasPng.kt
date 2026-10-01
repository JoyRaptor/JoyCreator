package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.render.TileSource

/** Export the active board through the same document compositor used by other exports. */
object CanvasPng {
    fun encode(contents: JbContents, includePaper: Boolean,
        paperRenderer: ((RectPx) -> ByteArray)? = null,
        onWarning: (String) -> Unit = {},
    ): ByteArray {
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
        val pixels = RegionRenderer.render(doc, source, board.rect, null, null)
        if (includePaper) {
            val paper = paperRenderer?.invoke(board.rect) ?: PaperResources.load(doc.paper).let { loaded ->
                loaded.warnings.forEach(onWarning); loaded.render(board.rect)
            }
            overPaper(pixels, paper)
        }
        return PngWriter.encode(board.rect.w, board.rect.h, pixels)
    }

    /** Both inputs are straight RGBA8; only the export buffer is changed, never a document tile. */
    internal fun overPaper(art: ByteArray, paper: ByteArray): ByteArray {
        require(art.size == paper.size && art.size % 4 == 0) { "paper dimensions must match the exported region" }
        for (i in art.indices step 4) {
            require((paper[i + 3].toInt() and 255) == 255) { "paper must be opaque" }
            val alpha = art[i + 3].toInt() and 255
            for (c in 0..2) art[i + c] = (((art[i + c].toInt() and 255) * alpha +
                (paper[i + c].toInt() and 255) * (255 - alpha) + 127) / 255).toByte()
            art[i + 3] = 255.toByte()
        }
        return art
    }

    internal fun paperRendererFor(contents: JbContents, includePaper: Boolean,
        renderer: ((RectPx) -> ByteArray)?, onWarning: (String) -> Unit,
    ): ((RectPx) -> ByteArray)? {
        if (!includePaper) return null
        if (renderer != null) return renderer
        val p = contents.doc.paper
        // Keep legacy flat animation/ORA compositing byte-for-byte for existing documents.
        if (p.lookId == null && p.textureId == null && p.tint == null) return null
        val loaded = PaperResources.load(p)
        loaded.warnings.forEach(onWarning)
        return loaded::render
    }
}
