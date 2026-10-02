package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.LayerKind

/**
 * Combines the single-canvas renderer's live pixels with the document it is displaying.
 *
 * The renderer owns stack order, names, visibility, opacity, blend, clipping and pixels. It does
 * not own board geometry, document identity, paper options or cel identities. Keep those from the
 * opened document, and move payload keys together with their cel IDs. Animated paint arrives as
 * a complete, separately addressed readback of every cel, including those not currently displayed.
 */
object CanvasSnapshot {
    /** After GPU upload, keep identities/settings only. A second pixel copy can exhaust the heap.
     * This is a merge template, not an archive: live readback supplies every tile when saving.
     */
    fun metadataOf(contents: JbContents): JbContents {
        requireCanvas(contents)
        return contents.copy(tiles = emptyMap(), strokes = emptyMap())
    }

    fun merge(retained: JbContents?, fresh: JbContents,
              animationTiles: Map<Triple<String, String, String>, ByteArray>? = null): JbContents {
        if (retained == null) return fresh
        requireCanvas(retained)
        requireCanvas(fresh)
        val oldLayers = retained.doc.layers.associateBy { it.id }
        if (oldLayers.values.any { it.animatedIn != null } && animationTiles == null) {
            throw JbArchiveException("all animation cels must be read before this drawing can be saved")
        }
        val celIds = HashMap<Pair<String, String>, String>()
        val layers = fresh.doc.layers.map { live ->
            val old = oldLayers[live.id]
            val liveCel = live.cels.single()
            val cel = liveCel.copy(id = old?.cels?.first()?.id ?: liveCel.id)
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
                cels = if (old.animatedIn == null) listOf(cel) else old.cels.map { stored ->
                    stored.copy(tiles = animationTiles!!.keys.filter { it.first == old.id && it.second == stored.id }
                        .map { it.third }.sorted())
                },
                mask = mask,
            )
        }
        val tiles = LinkedHashMap<Triple<String, String, String>, ByteArray>()
        for ((key, bytes) in fresh.tiles) {
            if (oldLayers[key.first]?.animatedIn != null && key.second == fresh.doc.layers.first { it.id == key.first }.cels.first().id) {
                throw JbArchiveException("an animation snapshot must read each cel under its own identity")
            }
            val cel = celIds[key.first to key.second]
                ?: throw JbArchiveException("a snapshot tile belongs to an unknown layer or cel")
            tiles[Triple(key.first, cel, key.third)] = bytes
        }
        for ((key, bytes) in animationTiles.orEmpty()) {
            val owner = oldLayers[key.first]
            if (owner?.animatedIn == null || owner.cels.none { it.id == key.second } || bytes.size != TILE_BYTES) {
                throw JbArchiveException("an animation tile has no valid paint cel")
            }
            tiles[key] = bytes
        }
        val doc = retained.doc.copy(
            format = fresh.doc.format,
            version = fresh.doc.version,
            name = fresh.doc.name,
            paper = retained.doc.paper.copy(color = fresh.doc.paper.color),
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
        if (doc.boards.size != 1 || doc.boards.single().kind !in listOf(BoardKind.CANVAS, BoardKind.ANIMATION) ||
            doc.layers.isEmpty() || doc.paper.textureId != null || contents.strokes.isNotEmpty() ||
            doc.layers.any { it.kind != LayerKind.PAINT || (it.animatedIn == null && it.cels.size != 1) }
        ) {
            throw JbArchiveException("this snapshot requires one canvas or animation board with paint layers")
        }
    }
}
