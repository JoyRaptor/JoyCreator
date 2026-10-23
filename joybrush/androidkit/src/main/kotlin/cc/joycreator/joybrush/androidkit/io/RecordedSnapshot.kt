package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import cc.joycreator.joybrush.core.brush.FrozenBrushRegistry
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.StrokeCodec
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.LineRules
import cc.joycreator.joybrush.core.vector.RecordedCel

/** Physical source readback: records and slab textures are authoritative, never a projection. */
object RecordedSnapshot {
    fun <T : Any> capture(
        doc: JbDocument,
        planes: Map<Pair<String, String?>, RecordedCel<T>>,
        brushes: Map<String, BrushPreset>,
        readTexture: (T) -> ByteArray?,
        keys: (String, String) -> List<Long>,
        read: (String, String, Long) -> ByteArray?,
        expectedLookKeys: Map<Pair<String, String?>, List<Long>> = emptyMap(),
        lookRead: (String, String?, Long) -> ByteArray? = { _, _, _ -> null },
        paper: Paper = doc.paper,
        mediaKeys: (String, String) -> Map<String, List<Long>> = { _, _ -> emptyMap() },
        mediaRead: (String, String, String, Long) -> ByteArray? = { _, _, _, _ -> null },
        brushAssets: Map<String, FrozenBrushPngs> = emptyMap(),
    ): JbContents {
        require(DocOps.validate(doc).isEmpty()) { DocOps.validate(doc).joinToString("; ") }
        require(doc.layers.all { it.kind.hasPixels && it.animatedIn == null }) {
            "Physical recorded snapshot requires supported paint layers"
        }
        val addresses = linkedMapOf<Pair<String, String?>, Pair<Layer, Cel>>()
        for (layer in doc.layers) {
            require(layer.mask?.let { it.strokesFile == null && it.slabs.isEmpty() && it.looks.isEmpty() } != false) {
                "A mask needs plain physical pixels"
            }
            for (cel in layer.cels) {
                val shared = layer.sharedCelId ?: layer.cels.singleOrNull()?.id
                val address = layer.id to cel.id.takeUnless { it == shared }
                require(addresses.put(address, layer to cel) == null) { "Two cels share a physical address" }
            }
        }
        // Validate every supplied identity before any callback (including key enumeration).
        for ((address, plane) in planes) {
            require(address in addresses && address == (plane.layerId to plane.celId)) {
                "Recorded plane does not match a document physical cel"
            }
            require(plane.records.isEmpty() || address in expectedLookKeys) {
                "A recorded plane with lines needs an explicit authoritative look index"
            }
        }
        for ((address, listed) in expectedLookKeys) {
            require(address in addresses && address in planes) { "Looks need a recorded physical cel" }
            require(listed.distinct().size == listed.size) { "A look tile is listed twice" }
            require(listed.isEmpty() || planes.getValue(address).records.isNotEmpty()) { "Looks need lines" }
        }
        planes.values.forEach { cc.joycreator.joybrush.core.stroke.CapturedStrokeRules.requireOrdinary(it.records) }
        val frozen = FrozenBrushRegistry.validate(brushes)
        val images = brushAssets.filterKeys { it in frozen }.toMap()
        FrozenBrushAssets.prepare(frozen, images, retainEntries = false)
        if (planes.isEmpty() && frozen.isEmpty() && expectedLookKeys.isEmpty()) {
            return BoardSnapshot.capture(doc, keys, read, paper, mediaKeys, mediaRead)
        }
        val strokes = linkedMapOf<Pair<String, String>, List<StrokeRecord>>()
        val prepared = linkedMapOf<Pair<String, String>, Cel>()
        for ((address, owner) in addresses) {
            val (layer, cel) = owner
            val plane = planes[address]
            if (plane == null) {
                require(cel.strokesFile == null && cel.slabs.isEmpty() && cel.looks.isEmpty()) {
                    "Recorded cel metadata has no physical source"
                }
                continue
            }
            val records = plane.records.map { it.copy(samples = it.samples.toList()) }.sortedBy { it.seq }
            val refs = plane.slabs.flatMap { (key, stack) ->
                if (stack.size == 1 && stack.single().seq == 0L && stack.single().value.op == SlabOps.NORMAL)
                    emptyList()
                else stack.map { SlabRef(name(key), it.seq, it.value.op) }
            }.sortedWith(compareBy({ it.key }, { it.seq }))
            val current = cel.copy(tiles = plane.slabs.keys.map(::name).sorted(), slabs = refs,
                strokesFile = if (records.isEmpty()) null else "layers/${layer.id}/${cel.id}/strokes.jbs",
                looks = expectedLookKeys[address].orEmpty().map(::name).sorted(), floatTiles = emptyList())
            val problems = LineRules.problems(layer.id, current, records, frozen.keys)
            require(problems.isEmpty()) { problems.joinToString("; ") }
            StrokeCodec.encodeAll(records) // Check serializability before readback; preserve raw records.
            prepared[layer.id to cel.id] = current
            if (records.isNotEmpty()) strokes[layer.id to cel.id] = records
        }
        val metadata = doc.copy(paper = paper, brushes = frozen.keys.sorted(), layers = doc.layers.map { layer ->
            layer.copy(cels = layer.cels.map { prepared[layer.id to it.id] ?: it })
        })
        require(DocOps.validate(metadata).isEmpty()) { DocOps.validate(metadata).joinToString("; ") }
        val pixels = linkedMapOf<Triple<String, String, String>, ByteArray>()
        val upper = linkedMapOf<SlabKey, ByteArray>()
        val looks = linkedMapOf<Triple<String, String, String>, ByteArray>()
        val media = linkedMapOf<MediaTileKey, ByteArray>()
        fun rgba(bytes: ByteArray?): ByteArray {
            val value = bytes ?: throw JbArchiveException("A physical source tile could not be read")
            if (value.size != TILE_BYTES) throw JbArchiveException("A physical source tile has the wrong size")
            return value.copyOf()
        }
        fun plain(layer: String, cel: Cel, mask: Boolean = false): Cel {
            val listed = linkedSetOf<String>()
            for (key in keys(layer, cel.id)) {
                val bytes = rgba(read(layer, cel.id, key))
                if (!mask && Tiles.isBlank(bytes)) continue
                listed += name(key)
                pixels[Triple(layer, cel.id, name(key))] = bytes
            }
            return cel.copy(tiles = listed.sorted())
        }
        fun mediaState(layer: String, cel: Cel): Cel {
            val listed = linkedSetOf<String>()
            for ((store, storeKeys) in mediaKeys(layer, cel.id)) for (key in storeKeys) {
                val bytes = mediaRead(layer, cel.id, store, key)
                    ?: throw JbArchiveException("A pencil, watercolour or oil tile could not be read")
                if (bytes.size != MediaStores.tileBytes(TILE_SIZE, store))
                    throw JbArchiveException("A pencil, watercolour or oil tile has the wrong size")
                if (Tiles.isBlank(bytes)) continue
                listed += name(key)
                media[MediaTileKey(layer, cel.id, name(key), store)] = bytes.copyOf()
            }
            return cel.copy(floatTiles = listed.sorted())
        }
        for ((address, plane) in planes) {
            val (layer, cel) = addresses.getValue(address)
            for ((key, stack) in plane.slabs) for ((index, slab) in stack.withIndex()) {
                val bytes = rgba(readTexture(slab.value.texture))
                if (index == 0) pixels[Triple(layer.id, cel.id, name(key))] = bytes
                else upper[SlabKey(layer.id, cel.id, name(key), slab.seq)] = bytes
            }
            for (key in expectedLookKeys[address].orEmpty()) {
                looks[Triple(layer.id, cel.id, name(key))] = rgba(lookRead(layer.id, address.second, key))
            }
        }
        val current = metadata.copy(layers = metadata.layers.map { layer -> layer.copy(
            cels = layer.cels.map { cel -> mediaState(layer.id,
                if ((layer.id to cel.id) in prepared) cel else plain(layer.id, cel)) },
            mask = layer.mask?.let { plain(layer.id, it, true) },
        ) })
        return JbContents(current, pixels, strokes, mediaTiles = media, slabs = upper, looks = looks, brushes = frozen, brushAssets = images)
    }

    private fun name(key: Long): String = DocOps.key(Tiles.tx(key), Tiles.ty(key))
}
