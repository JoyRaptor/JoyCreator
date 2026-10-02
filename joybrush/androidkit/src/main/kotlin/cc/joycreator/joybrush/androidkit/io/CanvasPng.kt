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
        // Paper is decided ONCE, here, and then handed to the compositor as a callback — the
        // renderer lays it down as the floor of the stack, in bounded blocks, BEFORE the first
        // layer. It used to be composited in this function, over the finished picture, which is the
        // whole of the JB-9.06b defect: a MULTIPLY layer had multiplied TRANSPARENCY, and the paper
        // was pasted over the result afterwards, so the PNG disagreed with the screen for every
        // separable blend mode. The callback is also why no region-sized paper buffer is alive here
        // beside the 160 MiB composite.
        val renderer = paperRendererFor(contents, includePaper, paperRenderer, onWarning)
            ?: flatPaperRenderer(contents, includePaper, onWarning)
        val pixels = RegionRenderer.render(doc, source, board.rect, null, null, renderer)
        return PngWriter.encode(board.rect.w, board.rect.h, pixels)
    }

    /**
     * The paper for a document with no look, no texture and no tint: still [PaperResources], not the
     * bare `#RRGGBB`, and that is a deliberate difference from the animation and OpenRaster paths.
     *
     * [paperRendererFor] answers null for such a document, because those two exporters pass the
     * colour to [RegionRenderer] itself and have always done so. This one never has: it has always
     * resolved the paper through [PaperResources], and a document can carry a SURFACE with no look,
     * no texture and no tint, in which case the resource path has relief to contribute and a bare
     * colour would quietly drop it. Resolving the material is a separate decision from deciding
     * WHERE it is composited, and this row changes only the second.
     */
    private fun flatPaperRenderer(
        contents: JbContents,
        includePaper: Boolean,
        onWarning: (String) -> Unit,
    ): ((RectPx) -> ByteArray)? {
        if (!includePaper || contents.doc.paper.screenTransparent) return null
        val loaded = PaperResources.load(contents.doc.paper)
        loaded.warnings.forEach(onWarning)
        return loaded::render
    }

    internal fun paperRendererFor(contents: JbContents, includePaper: Boolean,
        renderer: ((RectPx) -> ByteArray)?, onWarning: (String) -> Unit,
    ): ((RectPx) -> ByteArray)? {
        if (!includePaper || contents.doc.paper.screenTransparent) return null
        if (renderer != null) return renderer
        val p = contents.doc.paper
        // Keep legacy flat animation/ORA compositing byte-for-byte for existing documents.
        if (p.lookId == null && p.textureId == null && p.tint == null) return null
        val loaded = PaperResources.load(p)
        loaded.warnings.forEach(onWarning)
        return loaded::render
    }
}
