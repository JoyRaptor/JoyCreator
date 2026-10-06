package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RegionDocumentOps
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.render.MAX_REGION_PX
import cc.joycreator.joybrush.core.render.RegionException
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.render.TileSource

/** Export the active board through the same document compositor used by other exports. */
object CanvasPng {
    /** Explicit target captured by board chrome. A frame override exists only in this export copy. */
    fun encodeBoard(contents: JbContents, boardId: String, includePaper: Boolean, frameId: String? = null,
        paperRenderer: ((RectPx) -> ByteArray)? = null, onWarning: (String) -> Unit = {},
    ): ByteArray {
        val original = contents.doc
        if (original.boards.none { it.id == boardId }) throw JbArchiveException("that board no longer exists")
        val selected = original.copy(activeBoardId = boardId)
        val doc = if (frameId == null) selected else RegionDocumentOps.selectFrame(selected, boardId, frameId)
        return encode(contents.copy(doc = doc), includePaper, paperRenderer, onWarning)
    }

    fun encode(contents: JbContents, includePaper: Boolean,
        paperRenderer: ((RectPx) -> ByteArray)? = null,
        onWarning: (String) -> Unit = {},
    ): ByteArray {
        val doc = contents.doc
        val problems = DocOps.validate(doc)
        if (problems.isNotEmpty()) throw JbArchiveException(problems.joinToString("; "))
        if (doc.layers.any { it.kind != LayerKind.PAINT || it.animatedIn != null }) {
            throw JbArchiveException("this PNG renderer supports raster layers; vector rendering is not connected")
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
        // THE REGION IS REFUSED BEFORE THE PAPER IS EVEN NAMED. `paperRendererFor` and
        // `flatPaperRenderer` both call `PaperResources.load`, which decodes the look and surface
        // textures — two 1024² images and the arrays they decode into — and a board that
        // `RegionRenderer` is going to refuse must not pay for that on its way to being told no.
        // `RegionRenderer.render` is where `requireSize` throws, i.e. AFTER the decode, so the check
        // is repeated here in the one door that lacked it; `AnimExport.checkRegionFits` and
        // `OraExport.boardOf` already refuse before resolving their paper.
        val regionPx = board.rect.w.toLong() * board.rect.h.toLong()
        if (regionPx > MAX_REGION_PX) {
            throw RegionException(
                "a ${board.rect.w} by ${board.rect.h} region is $regionPx pixels, and the most this " +
                    "renderer will allocate is $MAX_REGION_PX. Export a smaller area, or a piece of it at a time.",
            )
        }
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
