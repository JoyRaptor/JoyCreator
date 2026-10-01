package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.Paper

/**
 * Combines the single-canvas renderer's live pixels with the document it is displaying.
 *
 * The renderer owns stack order, names, visibility, opacity, blend, clipping and pixels. It does
 * not own board geometry, document identity or cel identities. Keep those from the
 * opened document, and move payload keys together with their cel IDs. This deliberately does not
 * enable animation or other board types: those require a renderer that can retain every cel.
 */
object CanvasSnapshot {
    /** After GPU upload, keep identities/settings only. A second pixel copy can exhaust the heap.
     * This is a merge template, not an archive: live readback supplies every tile when saving.
     */
    fun metadataOf(contents: JbContents): JbContents {
        requireCanvas(contents)
        return contents.copy(tiles = emptyMap(), strokes = emptyMap())
    }

    /** [livePaper] carries current paper settings; omitted for older colour-only snapshot callers. */
    fun merge(retained: JbContents?, fresh: JbContents, livePaper: Paper? = null): JbContents {
        if (retained == null) return if (livePaper == null) fresh else fresh.copy(doc = fresh.doc.copy(paper = livePaper))
        requireCanvas(retained)
        requireCanvas(fresh)
        val oldLayers = retained.doc.layers.associateBy { it.id }
        val celIds = HashMap<Pair<String, String>, String>()
        val layers = fresh.doc.layers.map { live ->
            val old = oldLayers[live.id]
            val liveCel = live.cels.single()
            val cel = liveCel.copy(id = old?.cels?.single()?.id ?: liveCel.id)
            celIds[live.id to liveCel.id] = cel.id
            val mask = live.mask?.let { liveMask ->
                // Adding a mask must not reuse a pre-existing paint cel's arbitrary ID.
                var id = old?.mask?.id ?: liveMask.id
                if (id == cel.id) id += "-mask"
                celIds[live.id to liveMask.id] = id
                liveMask.copy(id = id)
            }
            if (old == null) live.copy(cels = listOf(cel), mask = mask)
            else old.copy(
                name = live.name,
                visible = live.visible,
                opacity = live.opacity,
                blend = live.blend,
                clip = live.clip,
                cels = listOf(cel),
                mask = mask,
            )
        }
        val tiles = LinkedHashMap<Triple<String, String, String>, ByteArray>()
        for ((key, bytes) in fresh.tiles) {
            val cel = celIds[key.first to key.second]
                ?: throw JbArchiveException("a snapshot tile belongs to an unknown layer or cel")
            tiles[Triple(key.first, cel, key.third)] = bytes
        }
        val doc = retained.doc.copy(
            format = fresh.doc.format,
            version = fresh.doc.version,
            name = fresh.doc.name,
            paper = livePaper ?: retained.doc.paper.copy(color = fresh.doc.paper.color),
            layers = layers,
            activeLayerId = fresh.doc.activeLayerId,
        )
        val problems = DocOps.validate(doc)
        if (problems.isNotEmpty()) {
            throw JbArchiveException("this snapshot cannot be saved: " + problems.joinToString("; "))
        }
        return JbContents(doc, tiles, fresh.strokes, retained.thumbnailPng)
    }

    private fun requireCanvas(contents: JbContents) {
        val doc = contents.doc
        val problems = DocOps.validate(doc)
        if (problems.isNotEmpty()) {
            throw JbArchiveException("this snapshot cannot be saved: " + problems.joinToString("; "))
        }
        if (doc.boards.size != 1 || doc.boards.single().kind != BoardKind.CANVAS ||
            doc.layers.isEmpty() || contents.strokes.isNotEmpty() ||
            doc.layers.any { it.kind != LayerKind.PAINT || it.animatedIn != null || it.cels.size != 1 }
        ) {
            throw JbArchiveException("this snapshot requires one canvas board with static paint layers")
        }
    }
}
