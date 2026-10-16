package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.androidkit.io.MediaTileKey
import cc.joycreator.joybrush.androidkit.io.SlabKey
import cc.joycreator.joybrush.androidkit.io.TILE_BYTES
import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.DocJson
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.SlabRef
import cc.joycreator.joybrush.core.media.HalfFloat
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.vector.CelLayout

/** Deterministic, diagnostic-only cold upload payload fixture. This does not prove GL upload behavior. */
internal object ColdPayloadProbeFixture {
    const val PREFIX = "cold-payload-probe-"
    const val MASK_CEL = "cold-payload-probe-mask-cel"
    const val MEDIA_LAYER = "cold-payload-probe-media-layer"
    const val MEDIA_CEL = "cold-payload-probe-media-cel"
    const val MASK_TILE = "17_-19"
    const val MEDIA_TILE = "-23_29"

    data class Address(val layerId: String, val celId: String, val key: String,
                       val store: String? = null, val seq: Long? = null)
    class ExpectedPayload internal constructor(val category: String, val address: Address, bytes: ByteArray) {
        private val payload = bytes.copyOf()
        fun bytesCopy(): ByteArray = payload.copyOf()
    }
    class Prepared internal constructor(contents: JbContents, expected: List<ExpectedPayload>) {
        private val snapshot = copyContents(contents)
        val expected: List<ExpectedPayload> = FrozenList(expected.toTypedArray())
        fun contentsCopy(): JbContents = copyContents(snapshot)
    }

    /** Input must remain stable for this call. Output owns copies of all maps, metadata and byte arrays. */
    fun enrich(input: JbContents): Prepared {
        refuse(input)
        JbArchive.requireStructure(input)
        val recordedLayer = input.doc.layers.single { it.cels.single().strokesFile != null }
        val recordedCel = recordedLayer.cels.single()
        val nativeLayer = input.doc.layers.single { it !== recordedLayer }
        val lineKey = recordedCel.looks.minWithOrNull(compareBy<String>({ tileCoordinate(it, 0) }, { tileCoordinate(it, 1) }))
            ?: throw IllegalArgumentException("Probe fixture requires a saved line-look key")
        require(lineKey in recordedCel.tiles || recordedCel.tiles.size < MAX_TILES) {
            "Probe fixture would exceed the cel tile bound"
        }
        val lineAddress = Triple(recordedLayer.id, recordedCel.id, lineKey)
        val existingStack = CelLayout.stacks(recordedCel)[lineKey]
        val existingBottom = input.tiles[lineAddress]
        require(existingStack == null || existingBottom != null) {
            "Probe fixture refuses a listed bottom tile without its existing bytes"
        }
        require(existingStack != null || existingBottom == null) {
            "Probe fixture refuses an unlisted bottom payload at the chosen saved-look key"
        }
        val allSeq = buildList {
            input.strokes.values.flatten().forEach { add(it.seq) }
            input.doc.layers.flatMap { it.cels }.flatMap { it.slabs }.forEach { add(it.seq) }
        }
        val maxSeq = allSeq.maxOrNull() ?: -1L
        require(maxSeq < Long.MAX_VALUE) { "Probe upper-slab sequence would overflow" }
        val upperSeq = Math.addExact(maxSeq, 1L)
        require(upperSeq >= 0L) { "Probe upper-slab sequence is unsupported" }

        val upper = patternedOpaqueTile()
        require(input.slabs.none { (key, bytes) -> key.layerId == recordedLayer.id && key.celId == recordedCel.id &&
            key.key == lineKey && bytes.contentEquals(upper) }) { "Probe upper-slab fixture state already exists" }
        require(input.looks[Triple(recordedLayer.id, recordedCel.id, lineKey)]?.contentEquals(upper) != true) {
            "Probe upper-look fixture state already exists"
        }
        val p0 = halfTile()
        val ground = ByteArray(TILE_BYTES) { i -> if (i % 4 == 3) 0xff.toByte() else ((i * 17 + 29) and 255).toByte() }
        val mask = ByteArray(TILE_BYTES) { i -> if (i % 4 == 3) 0xff.toByte() else ((i * 7 + 11) and 255).toByte() }
        val slabRef = SlabRef(lineKey, upperSeq, "normal")
        val existingLineSlabs = recordedCel.slabs.filter { it.key == lineKey }
        require(existingStack != null || existingLineSlabs.isEmpty()) {
            "Probe fixture refuses slab references outside the bottom tile manifest"
        }
        // An implicit plain bottom becomes explicit when a second slab is added. A line key
        // without any bottom instead makes the generated slab the first stack entry at its own seq.
        val bottomRef = if (existingStack != null && existingLineSlabs.isEmpty()) listOf(SlabRef(lineKey, 0L)) else emptyList()
        val updatedRecorded = recordedCel.copy(
            tiles = (recordedCel.tiles + lineKey).distinct(),
            slabs = recordedCel.slabs + bottomRef + slabRef,
        )
        val updatedLayers = input.doc.layers.map { layer ->
            when (layer.id) {
                recordedLayer.id -> layer.copy(cels = listOf(updatedRecorded))
                nativeLayer.id -> layer.copy(mask = Cel(MASK_CEL, tiles = listOf(MASK_TILE)))
                else -> layer
            }
        } + cc.joycreator.joybrush.core.doc.Layer(
            MEDIA_LAYER, "Probe media", LayerKind.PAINT,
            cels = listOf(Cel(MEDIA_CEL, floatTiles = listOf(MEDIA_TILE)))
        )
        val doc = DocJson.decode(DocJson.encode(input.doc.copy(layers = updatedLayers)))
        val tiles = input.tiles.mapValues { it.value.copyOf() }.toMutableMap()
        if (existingStack == null) tiles[lineAddress] = upper.copyOf()
        tiles[Triple(nativeLayer.id, MASK_CEL, MASK_TILE)] = mask.copyOf()
        val slabs = input.slabs.mapValues { it.value.copyOf() }.toMutableMap()
        if (existingStack != null) slabs[SlabKey(recordedLayer.id, recordedCel.id, lineKey, upperSeq)] = upper.copyOf()
        val looks = input.looks.mapValues { it.value.copyOf() }.toMutableMap()
        looks[Triple(recordedLayer.id, recordedCel.id, lineKey)] = upper.copyOf()
        val media = input.mediaTiles.mapValues { it.value.copyOf() }.toMutableMap()
        media[MediaTileKey(MEDIA_LAYER, MEDIA_CEL, MEDIA_TILE, "p0")] = p0.copyOf()
        media[MediaTileKey(MEDIA_LAYER, MEDIA_CEL, MEDIA_TILE, MediaStores.GROUND)] = ground.copyOf()
        val contents = input.copy(doc = doc, tiles = tiles.toMap(), slabs = slabs.toMap(), looks = looks.toMap(),
            mediaTiles = media.toMap(), strokes = input.strokes.mapValues { (_, records) -> records.sortedBy { it.seq }.map { it.copy(samples = it.samples.map { s -> s.copy() }) } },
            thumbnailPng = input.thumbnailPng?.copyOf(), brushes = input.brushes.mapValues { BrushJson.decodeChecked(BrushJson.encode(it.value)) })
        JbArchive.requireStructure(contents)
        val expected = listOf(
            ExpectedPayload("MASK_RGBA8", Address(nativeLayer.id, MASK_CEL, MASK_TILE), mask),
            ExpectedPayload("MEDIA_RGBA16F", Address(MEDIA_LAYER, MEDIA_CEL, MEDIA_TILE, "p0"), p0),
            ExpectedPayload("GROUND_RGBA8", Address(MEDIA_LAYER, MEDIA_CEL, MEDIA_TILE, MediaStores.GROUND), ground),
            ExpectedPayload("SOURCE_SLAB_RGBA8", Address(recordedLayer.id, recordedCel.id, lineKey, seq = upperSeq), upper),
        )
        return Prepared(contents, expected)
    }

    private fun refuse(c: JbContents) {
        require(c.doc.layers.size == 2 && c.doc.layers.all { it.kind == LayerKind.PAINT && it.animatedIn == null && it.mask == null && it.regions.isEmpty() }) {
            "Probe fixture requires exactly two plain paint layers without masks or regions"
        }
        require(c.doc.layers.all { it.cels.size == 1 && (it.sharedCelId == null || it.sharedCelId == it.cels.single().id) }) {
            "Probe fixture requires one ordinary cel per layer and canonical shared-cel metadata"
        }
        val recorded = c.doc.layers.filter { it.cels.single().strokesFile != null || it.cels.single().looks.isNotEmpty() || it.cels.single().slabs.isNotEmpty() }
        val native = c.doc.layers.filter { it.cels.single().strokesFile == null && it.cels.single().looks.isEmpty() && it.cels.single().slabs.isEmpty() }
        require(recorded.size == 1 && native.size == 1) { "Probe fixture requires one recorded and one plain native plane" }
        require(c.strokes.keys == setOf(recorded.single().id to recorded.single().cels.single().id)) {
            "Probe fixture refuses orphan or ambiguous stroke owners"
        }
        val rc = recorded.single().cels.single()
        require(rc.looks.isNotEmpty() && rc.looks.size <= MAX_TILES && rc.tiles.isNotEmpty() && rc.tiles.size <= MAX_TILES &&
            rc.floatTiles.isEmpty()) {
            "Probe fixture requires at most $MAX_TILES ordinary RGBA8 bottom and saved-look tiles"
        }
        require(c.strokes.values.flatten().none { it.capturedPhysicalSource != null }) {
            "Probe fixture refuses captured-kind records"
        }
        require(c.mediaTiles.isEmpty()) { "Probe fixture refuses preexisting media" }
        require(c.doc.layers.all { it.cels.all { cel -> cel.floatTiles.isEmpty() } }) { "Probe fixture refuses existing media declarations" }
        require(c.doc.layers.none { it.id.startsWith(PREFIX) || it.cels.any { cel -> cel.id.startsWith(PREFIX) } ||
            it.mask?.id?.startsWith(PREFIX) == true } && c.doc.boards.none { it.id.startsWith(PREFIX) } &&
            c.doc.brushes.none { it.startsWith(PREFIX) }) {
            "Probe fixture reserved identifier already exists"
        }
        require(c.tiles.keys.none { it.first.startsWith(PREFIX) || it.second.startsWith(PREFIX) || it.third.startsWith(PREFIX) } &&
            c.slabs.keys.none { it.layerId.startsWith(PREFIX) || it.celId.startsWith(PREFIX) || it.key.startsWith(PREFIX) } &&
            c.looks.keys.none { it.first.startsWith(PREFIX) || it.second.startsWith(PREFIX) || it.third.startsWith(PREFIX) }) {
            "Probe fixture reserved payload address already exists"
        }
        require(c.slabs.keys.none { it.seq == Long.MAX_VALUE } &&
            c.strokes.values.flatten().none { it.seq == Long.MAX_VALUE }) { "Probe fixture sequence is at its upper limit" }
        require(rc.looks.all { c.looks.containsKey(Triple(recorded.single().id, rc.id, it)) }) {
            "Probe fixture saved-look bytes are missing"
        }
    }

    private fun patternedOpaqueTile() = ByteArray(TILE_BYTES).also { bytes ->
        for (i in bytes.indices step 4) {
            bytes[i] = ((i / 4 * 13 + 31) and 255).toByte()
            bytes[i + 1] = ((i / 4 * 29 + 47) and 255).toByte()
            bytes[i + 2] = ((i / 4 * 37 + 59) and 255).toByte()
            bytes[i + 3] = 0xff.toByte()
        }
    }

    private fun halfTile(): ByteArray {
        val count = MediaStores.tileBytes(cc.joycreator.joybrush.core.doc.TILE_SIZE, "p0") / 2
        val values = FloatArray(count) { i -> when (i % 4) { 0 -> 0.3333f; 1 -> 1.2345f; 2 -> 3.1416f; else -> 0.7311f } }
        return HalfFloat.encode(values)
    }

    private fun tileCoordinate(raw: String, axis: Int): Int {
        val parts = raw.split('_', limit = 2)
        require(parts.size == 2) { "Probe fixture has a noncanonical tile key" }
        val x = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Probe fixture has a noncanonical tile key")
        val y = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Probe fixture has a noncanonical tile key")
        require("${x}_${y}" == raw) { "Probe fixture has a noncanonical tile key" }
        return if (axis == 0) x else y
    }

    private fun copyContents(c: JbContents) = c.copy(doc = DocJson.decode(DocJson.encode(c.doc)),
        tiles = c.tiles.mapValues { it.value.copyOf() },
        strokes = c.strokes.mapValues { (_, rs) -> rs.map { it.copy(samples = it.samples.map { s -> s.copy() }) } },
        thumbnailPng = c.thumbnailPng?.copyOf(), mediaTiles = c.mediaTiles.mapValues { it.value.copyOf() },
        slabs = c.slabs.mapValues { it.value.copyOf() }, looks = c.looks.mapValues { it.value.copyOf() },
        brushes = c.brushes.mapValues { BrushJson.decodeChecked(BrushJson.encode(it.value)) })

    private const val MAX_TILES = 16
    private class FrozenList<T>(values: Array<T>) : AbstractList<T>() {
        private val items = values.copyOf()
        override val size: Int get() = items.size
        override fun get(index: Int): T = items[index]
    }
}
