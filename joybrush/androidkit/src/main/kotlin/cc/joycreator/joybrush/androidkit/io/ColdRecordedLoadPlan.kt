package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelLayout
import cc.joycreator.joybrush.core.vector.InkReplay

/**
 * Experimental INTERNAL fresh-engine profile, not View/raster/recovery activation. Input must
 * be stable/exclusively borrowed for prepare; concurrent hostile mutation is not supported.
 * Owns CPU snapshots only. No GL, archive serialization, line replay or composition occurs.
 */
internal object ColdRecordedLoadPlan {
    private val admissionIdentity = Any()
    /**
     * Deliberately small diagnostic defaults. CPU bytes are conservative accounting (payloads,
     * 64 bytes/sample, 256 bytes/metadata item, 24 bytes/string char, 16KiB/brush), not a heap cap.
     * They cover owned snapshots/encoding overhead; the adapter must budget GPU/context resources
     * separately. Enlarging these limits never widens the fixed source capability profile.
     */
    data class Limits(
        val maxCpuBytes: Long = 96L * 1024 * 1024,
        val maxUploadBytes: Long = 64L * 1024 * 1024,
        val maxSamples: Long = 131_072,
        val maxEntries: Long = 20_000,
    ) {
        init { require(maxCpuBytes > 0 && maxUploadBytes > 0 && maxSamples > 0 && maxEntries > 0) }
    }

    data class Address(val layerId: String, val celId: String?)
    enum class Kind { NATIVE_RGBA8, MASK_RGBA8, MEDIA_RGBA16F, GROUND_RGBA8, SOURCE_SLAB_RGBA8, SAVED_LINE_LOOK_RGBA8 }
    data class Upload(val id: Int, val kind: Kind, val layerId: String, val documentCelId: String,
                      val address: Address?, val key: Long, val byteCount: Int,
                      val store: String? = null, val seq: Long? = null, val op: String? = null)
    class Plane internal constructor(val address: Address, val documentCelId: String, val recorded: Boolean,
                                     nativeTiles: List<Upload>, media: List<Upload>, slabs: List<Upload>,
                                     savedLooks: List<Upload>, val recordCount: Int) {
        val nativeTiles = frozen(nativeTiles)
        val media = frozen(media)
        /** Complete stacks, sorted by tile coordinates then seq; first upload is bottom bytes. */
        val slabs = frozen(slabs)
        val savedLooks = frozen(savedLooks)
        /** Explicit even for a recorded plane with zero records/looks. Not proven replay reach. */
        val savedLookKeys = frozen(savedLooks.map { it.key })
    }
    class Mask internal constructor(val layerId: String, val documentCelId: String, tiles: List<Upload>) {
        val tiles = frozen(tiles)
    }
    data class Summary(val uploadBytes: Long, val chargedCpuBytes: Long, val chargedEntries: Long,
                       val sampleCount: Long, val recordCount: Long, val unresolvedLinePlanes: Int,
                       val uploadCount: Int, val nativePlaneCount: Int, val recordedPlaneCount: Int,
                       val maskCount: Int) {
        /** Cold open has not checked actual footprints, WC dab cap, assets or first-edit latency. */
        val dynamicReplayValidated: Boolean get() = false
        val requiresExactPaperResourcesBeforeUpload: Boolean get() = true
    }

    class Prepared internal constructor(
        admission: Any,
        private val documentJson: String,
        private val brushJson: Map<String, String>,
        brushAssets: Map<String, FrozenBrushPngs>,
        private val records: Map<Address, List<StrokeRecord>>,
        private val thumbnail: ByteArray?,
        private val payloads: List<ByteArray>,
        planes: List<Plane>, masks: List<Mask>, uploads: List<Upload>, val summary: Summary,
    ) {
        init { require(admission === admissionIdentity) { "Prepare a cold load plan through its admission factory" } }
        private val images = brushAssets.toMap()
        fun frozenBrushAssetsCopy(): Map<String, FrozenBrushPngs> = images.toMap()
        val planes = frozen(planes)
        val nativePlanes = frozen(planes.filterNot { it.recorded })
        val recordedPlanes = frozen(planes.filter { it.recorded })
        val masks = frozen(masks)
        val uploads = frozen(uploads)
        fun metadataCopy(): JbDocument = DocJson.decode(documentJson)
        fun frozenBrushesCopy(): Map<String, BrushPreset> = brushJson.mapValues { BrushJson.decodeChecked(it.value) }
        fun thumbnailCopy(): ByteArray? = thumbnail?.copyOf()
        fun recordsCopy(address: Address): List<StrokeRecord> {
            check(records.containsKey(address)) { "No paint plane at $address" }
            return copyRecords(records.getValue(address))
        }
        fun copyPayload(id: Int): ByteArray {
            require(id in payloads.indices) { "No cold upload payload $id" }
            return payloads[id].copyOf()
        }
    }

    fun prepare(contents: JbContents, limits: Limits = Limits()): Prepared = try {
        prepareChecked(contents, limits)
    } catch (error: JbArchiveException) { throw error }
    catch (error: Exception) { throw JbArchiveException("Cold source admission failed: ${error.message ?: error.javaClass.simpleName}") }

    private fun prepareChecked(input: JbContents, limits: Limits): Prepared {
        // Complete cheap accounting precedes clones, JSON encoders and requireStructure's codec.
        val budget = account(input, limits)
        need(DocOps.validate(input.doc).isEmpty(), "Invalid cold source document")
        need(input.doc.layers.all { it.kind == LayerKind.PAINT && it.animatedIn == null }, "Cold sources require current paint layers")
        val documentJson = DocJson.encode(input.doc)
        val doc = DocJson.decode(documentJson)
        val brushes = input.brushes.mapValues { (id, brush) ->
            need(id == brush.id && BrushValidate.validate(brush).isEmpty(), "Invalid frozen cold brush: $id")
            BrushJson.decodeChecked(BrushJson.encode(brush))
        }
        val images = input.brushAssets.toMap()
        FrozenBrushAssets.prepare(brushes, images, retainEntries = false)
        val snapshot = input.copy(doc = doc,
            tiles = input.tiles.mapValues { it.value.copyOf() },
            strokes = input.strokes.mapValues { copyRecords(it.value) },
            thumbnailPng = input.thumbnailPng?.copyOf(),
            mediaTiles = input.mediaTiles.mapValues { it.value.copyOf() },
            slabs = input.slabs.mapValues { it.value.copyOf() },
            looks = input.looks.mapValues { it.value.copyOf() }, brushes = brushes, brushAssets = images)
        JbArchive.requireStructure(snapshot)
        need(doc.boards.all { it.kind in setOf(BoardKind.CANVAS, BoardKind.ANIMATION, BoardKind.SPRITE) && !it.tiled },
            "Cold source board type or periodic display is unsupported")
        RegionDocumentOps.paintPlans(doc, emptyMap()) // Validate canonical current physical plans without rendering.
        need(doc.layers.all { !it.id.endsWith("#mask") && MediaStores.parse(it.id) == null }, "Reserved cold source owner identifier")
        val payloads = ArrayList<ByteArray>()
        val uploads = ArrayList<Upload>()
        val planes = ArrayList<Plane>()
        val masks = ArrayList<Mask>()
        val records = LinkedHashMap<Address, List<StrokeRecord>>()
        fun add(kind: Kind, layer: String, cel: String, address: Address?, rawKey: String, bytes: ByteArray,
                store: String? = null, seq: Long? = null, op: String? = null): Upload {
            val upload = Upload(payloads.size, kind, layer, cel, address, key(rawKey), bytes.size, store, seq, op)
            payloads.add(bytes) // Adopt this already-owned snapshot array, never a caller array.
            uploads.add(upload)
            return upload
        }
        for (layer in doc.layers) {
            val shared = layer.sharedCelId ?: layer.cels.singleOrNull()?.id
            need(shared != null && layer.cels.any { it.id == shared }, "Missing shared paint address in ${layer.id}")
            for (cel in layer.cels) {
                val at = Address(layer.id, cel.id.takeUnless { it == shared })
                need(at !in records, "Two document cels share physical address $at")
                val source = snapshot.strokes[layer.id to cel.id].orEmpty().sortedBy { it.seq }
                val declaredStroke = cel.strokesFile != null
                need(!declaredStroke || cel.strokesFile == "layers/${layer.id}/${cel.id}/strokes.jbs", "Noncanonical strokes file in $at")
                need(declaredStroke == snapshot.strokes.containsKey(layer.id to cel.id), "Strokes-file declaration mismatch in $at")
                for (raw in cel.tiles + cel.floatTiles + cel.looks + cel.slabs.map { it.key }) key(raw)
                val recorded = declaredStroke || cel.slabs.isNotEmpty() || cel.looks.isNotEmpty() || source.isNotEmpty()
                need(source.isEmpty() || layer.regions.isEmpty(), "Regional lines need persisted captured physical clipping in $at")
                need(!recorded || cel.floatTiles.isEmpty(), "Recorded sources cannot share media ownership in $at")
                source.forEach { validateReferenced(it, brushes.getValue(it.brushId), images[it.brushId]) }
                val native = ArrayList<Upload>()
                val media = ArrayList<Upload>()
                val slabs = ArrayList<Upload>()
                val looks = ArrayList<Upload>()
                if (recorded) {
                    val lineKeys = cel.looks.map(::key).toSet()
                    for ((raw, stack) in CelLayout.stacks(cel).entries.sortedWith(compareBy({ Tiles.tx(key(it.key)) }, { Tiles.ty(key(it.key)) }))) {
                        need(stack.all { it.op in OPERATORS }, "Unsupported recorded slab operator in $at")
                        need(key(raw) in lineKeys || (stack.size == 1 && stack[0].seq == 0L && stack[0].op == "normal"),
                            "Plain ordered slab display needs composition in $at")
                        stack.forEachIndexed { index, ref ->
                            val bytes = if (index == 0) snapshot.tiles.getValue(Triple(layer.id, cel.id, raw))
                                else snapshot.slabs.getValue(SlabKey(layer.id, cel.id, raw, ref.seq))
                            slabs += add(Kind.SOURCE_SLAB_RGBA8, layer.id, cel.id, at, raw, bytes, seq = ref.seq, op = ref.op)
                        }
                    }
                    for (raw in cel.looks.sorted()) looks += add(Kind.SAVED_LINE_LOOK_RGBA8, layer.id, cel.id, at, raw,
                        snapshot.looks.getValue(Triple(layer.id, cel.id, raw)))
                } else {
                    for (raw in cel.tiles.sorted()) native += add(Kind.NATIVE_RGBA8, layer.id, cel.id, at, raw,
                        snapshot.tiles.getValue(Triple(layer.id, cel.id, raw)))
                    for ((id, bytes) in snapshot.mediaTiles.entries.filter { it.key.layerId == layer.id && it.key.celId == cel.id }
                        .sortedWith(compareBy({ it.key.key }, { it.key.store }))) {
                        media += add(if (id.store == MediaStores.GROUND) Kind.GROUND_RGBA8 else Kind.MEDIA_RGBA16F,
                            layer.id, cel.id, at, id.key, bytes, store = id.store)
                    }
                }
                records[at] = frozen(source) // Samples already deep-owned in the snapshot.
                planes += Plane(at, cel.id, recorded, native, media, slabs, looks, source.size)
            }
            layer.mask?.let { mask ->
                need(mask.strokesFile == null && mask.slabs.isEmpty() && mask.looks.isEmpty() && mask.floatTiles.isEmpty() &&
                    !snapshot.strokes.containsKey(layer.id to mask.id), "A cold mask needs plain native pixels")
                val tiles = mask.tiles.sorted().map { raw -> add(Kind.MASK_RGBA8, layer.id, mask.id, null, raw,
                    snapshot.tiles.getValue(Triple(layer.id, mask.id, raw))) }
                masks += Mask(layer.id, mask.id, tiles)
            }
        }
        need(planes.any { it.recorded } || doc.brushes.isEmpty(), "Frozen-only cold roots are outside the first profile")
        val summary = Summary(budget.uploadBytes, budget.cpuBytes, budget.entries, budget.samples,
            planes.sumOf { it.recordCount.toLong() }, planes.count { it.recordCount > 0 }, uploads.size,
            planes.count { !it.recorded }, planes.count { it.recorded }, masks.size)
        return Prepared(admissionIdentity, documentJson, brushes.mapValues { BrushJson.encode(it.value) }, images, records,
            snapshot.thumbnailPng, payloads, planes, masks, uploads, summary)
    }

    private val OPERATORS = setOf("normal", "erase", "behind", "multiply", "screen")
    private val builtInWc = WatercolourSpec()
    private fun validateReferenced(record: StrokeRecord, brush: BrushPreset, pngs: FrozenBrushPngs?) {
        need(record.capturedPhysicalSource == null, "Captured physical sources need a clipping-aware cold loader")
        need(record.id.isNotBlank(), "A cold line needs its full identity")
        need(record.smoothing.isFinite() && record.smoothing in 0f..1f && record.screenPerDoc.isFinite() && record.screenPerDoc > 0 &&
            record.widthScale.isFinite() && record.widthScale in 0.05f..20f && record.samples.all { it.isPlaceable && !it.predicted },
            "Unsupported cold line header or samples")
        // Saved looks permit deferred image decode. Structural brush validation and the exact
        // PNG manifest were admitted above; dynamic replay still needs a resolved set on first edit.
        need(if (brush.tip.sampled != null || brush.tip.dual != null) brush.engine == "stamp" else InkReplay.refusal(record, brush) == null,
            "Unsupported cold line engine")
        need(brush.line == null && !brush.tipTexture.enabled && !brush.paperGrain.enabled,
            "Cold lines do not support shaping or explicit grain yet")
        // Preserve PaperResponse, including shipped legacy Ink. Saved looks allow cold open;
        // the adapter must resolve exact document paper before uploads. Legacy event-batch and
        // resource-dependent replay constraints remain first-edit checks, never certified here.
        need(brush.tip.source == "procedural" || (brush.tip.sampled != null && pngs != null),
            "Cold sampled lines need their validated frozen PNG bundle")
        when (brush.engine) {
            "stamp" -> need(brush.blend in OPERATORS, "Unsupported cold stamp operator")
            ENGINE_WATERCOLOUR -> {
                val wc = brush.watercolour
                need(brush.blend == "normal" && wc != null && wc.dabImages == builtInWc.dabImages && wc.backdrop == builtInWc.backdrop,
                    "Cold watercolor needs NORMAL and the supported built-in image set")
            }
            else -> need(false, "This cold engine is not connected to GPU replay")
        }
    }

    private fun key(raw: String): Long {
        val xy = raw.split('_', limit = 2)
        val x = xy.getOrNull(0)?.toIntOrNull()
        val y = xy.getOrNull(1)?.toIntOrNull()
        need(x != null && y != null && DocOps.key(x, y) == raw, "Noncanonical or aliased cold tile key: $raw")
        return Tiles.key(x!!, y!!)
    }

    private fun copyRecords(records: List<StrokeRecord>): List<StrokeRecord> = frozen(records.map { record ->
        record.copy(samples = frozen(record.samples.map { it.copy() }))
    })

    /** Cheap schema accounting: all variable-size doc/brush fields are visited before encoders. */
    private fun account(c: JbContents, limits: Limits): Budget {
        val b = Budget(limits)
        fun strings(values: List<String?>) { values.forEach { b.text(it) } }
        fun cel(cel: Cel) {
            b.items(1); b.text(cel.id); b.text(cel.strokesFile)
            for (keys in listOf(cel.tiles, cel.floatTiles, cel.looks)) { b.items(keys.size); keys.forEach { b.text(it) } }
            b.items(cel.slabs.size); cel.slabs.forEach { b.text(it.key); b.text(it.op) }
        }
        val d = c.doc
        b.items(1); strings(listOf(d.format, d.id, d.name, d.activeLayerId, d.activeBoardId, d.paper.color,
            d.paper.textureId, d.paper.lookId, d.paper.tint))
        b.items(d.boards.size); d.boards.forEach { board ->
            strings(listOf(board.id, board.name, board.currentFrameId)); b.items(board.frames.size); board.frames.forEach { b.text(it.id) }
        }
        b.items(d.layers.size); d.layers.forEach { layer ->
            strings(listOf(layer.id, layer.name, layer.animatedIn, layer.sharedCelId)); b.items(layer.cels.size)
            layer.cels.forEach(::cel); layer.mask?.let(::cel)
            b.items(layer.frameCel.size); layer.frameCel.forEach { (k, v) -> b.text(k); b.text(v) }
            b.items(layer.regions.size); layer.regions.forEach { r ->
                b.text(r.boardId); b.items(r.frameCel.size); r.frameCel.forEach { (k, v) -> b.text(k); b.text(v) }
            }
        }
        b.items(d.brushes.size); d.brushes.forEach { b.text(it) }
        for (map in listOf(c.tiles, c.slabs, c.looks, c.mediaTiles)) {
            b.items(map.size); map.values.forEach { b.payload(it.size) }
        }
        // Supplied identities can be unlisted/malformed: account their text before validation too.
        (c.tiles.keys + c.looks.keys).forEach { strings(listOf(it.first, it.second, it.third)) }
        c.slabs.keys.forEach { strings(listOf(it.layerId, it.celId, it.key)) }
        c.mediaTiles.keys.forEach { strings(listOf(it.layerId, it.celId, it.key, it.store)) }
        b.items(c.strokes.size); c.strokes.forEach { (at, records) ->
            strings(listOf(at.first, at.second)); b.items(records.size)
            records.forEach { r -> strings(listOf(r.id, r.brushId, r.groupId)); b.sampleCount(r.samples.size) }
        }
        c.thumbnailPng?.let { b.cpu(it.size.toLong()) }
        b.items(c.brushAssets.size)
        var largestPng = 0L
        c.brushAssets.forEach { (id, images) ->
            b.text(id); b.items(images.paths.size)
            images.paths.forEach { b.text(it) }
            b.cpu(images.encodedBytes)
            // Preflight/manifest preparation owns at most one exported PNG at a time.
            // The immutable retained owners are shared, never duplicated as a whole document.
            largestPng = maxOf(largestPng, minOf(images.encodedBytes, 16L * 1024 * 1024))
        }
        b.cpu(largestPng + if (c.brushAssets.isEmpty()) 0L else 1024L * 1024)
        b.items(c.brushes.size); c.brushes.forEach { (id, p) ->
            b.text(id); b.cpu(16L * 1024)
            strings(listOf(p.format, p.id, p.name, p.engine, p.tip.source, p.tip.image, p.tipTexture.source, p.tipTexture.image,
                p.paperGrain.source, p.paperGrain.image, p.accumulate, p.blend, p.license, p.author, p.sourceFormat))
            p.tip.sampled?.let { sampled ->
                b.items(sampled.images.size); sampled.images.forEach { b.text(it) }
            }
            fun profile(value: TipProfileSpec?) { value?.let {
                b.items(it.curve.size); it.curve.forEach { point -> b.items(point.size) }
            } }
            profile(p.tip.profile)
            p.tip.dual?.let { dual ->
                b.text(dual.image?.image)
                dual.shape?.let { shape -> b.items(shape.points.size); profile(shape.profile) }
            }
            listOfNotNull(p.tip.quad?.a, p.tip.quad?.b).forEach { shape ->
                b.items(shape.points.size); profile(shape.profile)
            }
            b.items(p.extensions.size); p.extensions.forEach { (k, v) -> b.text(k); b.text(v) }
            for (param in listOfNotNull(p.size, p.opacity, p.flow, p.tip.angle, p.tip.hardness, p.tipTexture.depth,
                p.paperGrain.depth, p.scatter.amount, p.tip.aspectDynamics, p.tip.anchorDynamics, p.tip.fade?.amount, p.tip.quad?.morph)) {
                b.text(param.combine); b.items(param.inputs.size); param.inputs.forEach { input ->
                    b.items(input.curve.size); input.curve.forEach { b.items(it.size) }
                }
            }
            b.items(p.response.pressure.size); b.items(p.response.tilt.size)
            p.media?.let { strings(listOf(it.medium, it.tool, it.belly)) }
            p.watercolour?.let { wc ->
                b.items(wc.dabImages.size); wc.dabImages.forEach { b.text(it) }
                strings(listOf(wc.backdrop, wc.approvedTint)); b.items(wc.pressureBezier.size); b.items(wc.tiltBezier.size)
                b.items(wc.worldPixels.size); b.items(wc.sampleSeeds.size)
            }
        }
        return b
    }

    private class Budget(private val limits: Limits) {
        var cpuBytes = 0L; private set
        var uploadBytes = 0L; private set
        var entries = 0L; private set
        var samples = 0L; private set
        fun cpu(n: Long) { need(n >= 0 && n <= limits.maxCpuBytes - cpuBytes, "Cold CPU byte budget exceeded"); cpuBytes += n }
        fun items(n: Int) {
            need(n >= 0 && n.toLong() <= limits.maxEntries - entries, "Cold entry budget exceeded")
            entries += n; cpu(n.toLong() * 256)
        }
        fun text(s: String?) { if (s != null) cpu(s.length.toLong() * 24) }
        fun payload(n: Int) {
            need(n >= 0 && n.toLong() <= limits.maxUploadBytes - uploadBytes, "Cold upload byte budget exceeded")
            uploadBytes += n; cpu(n.toLong())
        }
        fun sampleCount(n: Int) {
            need(n >= 0 && n.toLong() <= limits.maxSamples - samples, "Cold sample budget exceeded")
            samples += n; cpu(n.toLong() * 64)
        }
    }

    private fun need(ok: Boolean, message: String) { if (!ok) throw JbArchiveException(message) }
    private fun <T> frozen(values: List<T>): List<T> = FrozenList(values)
    private class FrozenList<T>(values: List<T>) : AbstractList<T>() {
        private val items = values.toList()
        override val size: Int get() = items.size
        override fun get(index: Int): T = items[index]
    }
}
