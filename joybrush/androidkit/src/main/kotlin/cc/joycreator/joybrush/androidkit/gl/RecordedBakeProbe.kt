package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.lines.RecordedScene
import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.androidkit.io.RecordedSnapshot
import cc.joycreator.joybrush.androidkit.io.SlabKey
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.SlabRef
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import java.nio.ByteBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Scratch GL-thread probe, for a SECOND initialized throwaway engine and its existing EMPTY
 * continuous paint layer. This function consumes/releases that engine, even on refusal. Never
 * pass the view's engine. No view activation, layer publication, Working/recovery installation
 * or device execution claim is made. In-memory archive roundtrips use synthetic document metadata
 * addressed to this actual scratch layer and its actual paper. GPU callback bytes are archived
 * as-is: no PNG, image normalization, row flip, premultiplication or coordinate conversion.
 * Supply a supported frozen brush with constant fractional opacity and an unlocked seq>0 record.
 * The caller's original record/brush remain unchanged. Every actual tile is checked (max 16).
 *
 * Synthetic seq0 backdrops are privately allocated GL textures, borrowed by RecordedScene.
 * Scene owns only derived line/look caches. Committed new slabs belong to physical scene/history;
 * this private probe explicitly reclaims LIVE-only slabs after scene/history close, while undo
 * clear reclaims HELD slabs. Engine release finally deletes its pools. No slab is freed twice.
 * Counts are EXTERNAL checkpoints, not engine-internal draw/FBO/feedback/error guard counts.
 */
internal object RecordedBakeProbe {
    internal data class Result(val details: JSONObject)
    internal fun json(result: Result): JSONObject = result.details

    internal fun run(engine: GlPaintEngine, layerId: String,
                     preset: BrushPreset, input: StrokeRecord, openCold: Boolean = false): Result {
        val report = JSONObject().put("probe", "recordedBake")
            .put("comparison", "Exact RGBA8 on every actual tile: bake, undo, redo and raw source copy")
            .put("coverage", "Concrete createRecordedScene/stageBake/public engine undoStep/redoStep; continuous empty plane")
            .put("archiveRoundtripExecuted", false)
            .put("archiveCoverage", "In-memory synthetic document metadata + actual physical GPU source/look readback")
            .put("archiveByteOrder", "Raw glReadPixels RGBA8 preserved verbatim, including row/tile coordinate order")
            .put("workingOrRecoveryInstalled", false)
            .put("internalEngineChecksCounted", false)
            .put("feedbackCheckMeaning", "External ownership/alias checks only; no per-draw internal count")
        val comparisons = JSONArray()
        val archives = JSONArray()
        val n = Tiles.SIZE
        val length = n * n * 4
        val plane = layerId to null
        val backdrops = linkedMapOf<Long, Int>()
        var raw: GlPaintEngine.RecordedLineUnit? = null
        var scene: RecordedScene<Int>? = null
        val unadoptedSavedLooks = linkedMapOf<Long, Int>()
        var coldAccepted = false
        var coldOpeningLineCalls = -1L
        var coldOpeningCompositionCalls = -1L
        var coldFirstEditLineCalls = -1L
        var readFbo = 0
        var reads = 0
        var glChecks = 0
        var glErrors = 0
        var fboChecks = 0
        var badFbos = 0
        var feedbackChecks = 0
        var badFeedback = 0
        var cleanupComplete = false
        var failure: String? = null
        var stageLabel = "entry"
        var sourceTiles = 0
        var inkPixels = 0
        var opacityControlChannels = 0
        var bakeCommitted = false
        var sequenceOperatorPreserved = false
        var recordRemoved = false
        var recordRestored = false
        var recordRemovedAgain = false
        var undoAccepted = false
        var redoAccepted = false
        var rawPrepCalls = 0
        var createSceneCalls = 0
        var stageBakeCalls = 0
        var undoCalls = 0
        var redoCalls = 0
        var liveSlabsRecycled = 0
        var heldSlabsClosed = 0
        var snapshotCaptureCalls = 0
        var archiveWriteCalls = 0
        var archiveReadCalls = 0
        var archiveRoundtripsCompleted = 0
        var archiveCpuComparisons = 0
        var archivalBottomPixelsPreserved = false

        fun errors(label: String) {
            glChecks++
            var first = GLES30.GL_NO_ERROR
            var code = GLES30.glGetError()
            while (code != GLES30.GL_NO_ERROR) {
                if (first == GLES30.GL_NO_ERROR) first = code
                glErrors++
                code = GLES30.glGetError()
            }
            check(first == GLES30.GL_NO_ERROR) { "$label GL error 0x${first.toString(16)}" }
        }
        fun feedback(ok: Boolean, label: String) {
            feedbackChecks++
            if (!ok) badFeedback++
            check(ok) { "$label texture ownership/alias violation" }
        }
        fun detach() {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            for (slot in 0..1) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + slot)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            }
        }
        fun read(texture: Int, label: String): ByteArray {
            detach()
            feedback(texture != 0, "$label read attachment")
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, readFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0)
            fboChecks++
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) badFbos++
            check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "$label incomplete FBO 0x${status.toString(16)}" }
            GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
            GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS, 0)
            val buffer = ByteBuffer.allocateDirect(length)
            reads++
            GLES30.glReadPixels(0, 0, n, n, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            errors(label)
            buffer.position(0)
            val bytes = ByteArray(length).also { buffer.get(it) }
            detach() // Do not leave a pool texture attached while scene publication recycles it.
            return bytes
        }
        fun compare(a: ByteArray, b: ByteArray, label: String, key: Long) {
            check(a.size == b.size)
            var channels = 0
            var pixels = 0
            var max = 0
            for (p in a.indices step 4) {
                var different = false
                for (c in 0..3) {
                    val delta = abs((a[p + c].toInt() and 255) - (b[p + c].toInt() and 255))
                    if (delta != 0) { channels++; different = true }
                    max = maxOf(max, delta)
                }
                if (different) pixels++
            }
            comparisons.put(JSONObject().put("phase", label).put("tileKey", key)
                .put("differentChannels", channels).put("differentPixels", pixels).put("maxByteDelta", max))
            check(channels == 0) { "$label tile $key differs: $channels channels, $pixels pixels, max $max" }
        }
        fun looks(current: RecordedScene<Int>, keys: Set<Long>, label: String): Map<Long, ByteArray> {
            check(current.lookKeys(plane) == keys) { "$label changed look tile keys" }
            return keys.associateWith { key -> read(checkNotNull(current.look(plane, key)), "$label $key") }
        }
        fun archiveRoundtrip(current: RecordedScene<Int>, record: StrokeRecord,
                             expectedLooks: Map<Long, ByteArray>, rawBytes: Map<Long, ByteArray>,
                             phase: String, baked: Boolean): JbContents {
            val sourcePlane = current.planes.getValue(plane)
            val savedKeys = current.savedLookKeys(plane)
            check(savedKeys == if (baked) emptySet<Long>() else expectedLooks.keys) {
                "$phase authoritative saved look index disagrees with actual line footprint"
            }
            // This independent serialization fixture is never attached to the live/throwaway engine.
            var id = 0
            val baseDoc = DocOps.newDocument("archive-probe-doc", "Recorded bake archive probe", n, n) {
                "archive-probe-${++id}"
            }
            val document = baseDoc.copy(paper = engine.documentPaper,
                layers = listOf(baseDoc.layers.single().copy(id = layerId)), activeLayerId = layerId)
            val celId = document.layers.single().cels.single().id
            val namedKeys = rawBytes.keys.map { DocOps.key(Tiles.tx(it), Tiles.ty(it)) }.toSet()
            snapshotCaptureCalls++
            val snapshot = RecordedSnapshot.capture(document, current.planes, current.brushes,
                readTexture = { texture -> read(texture, "$phase snapshot physical source") },
                keys = { _, _ -> error("$phase unexpectedly requested a plain/projection key") },
                read = { _, _, _ -> error("$phase unexpectedly requested plain/projection bytes") },
                expectedLookKeys = mapOf(plane to savedKeys.toList()),
                lookRead = { owner, physicalCel, key ->
                    check(owner == layerId && physicalCel == null && key in savedKeys)
                    read(checkNotNull(current.look(plane, key)), "$phase snapshot saved look $key")
                })
            val capturedCel = snapshot.doc.layers.single().cels.single()
            check(snapshot.doc.layers.single().id == layerId && capturedCel.id == celId)
            check(snapshot.doc.paper == engine.documentPaper)
            check(snapshot.doc.brushes == listOf(record.brushId) && snapshot.brushes == current.brushes)
            check(snapshot.tiles.keys == namedKeys.map { Triple(layerId, celId, it) }.toSet())
            check(capturedCel.tiles.toSet() == namedKeys)
            check(snapshot.mediaTiles.isEmpty() && snapshot.thumbnailPng == null)
            if (baked) {
                check(snapshot.strokes.isEmpty() && capturedCel.strokesFile == null)
                check(snapshot.looks.isEmpty() && capturedCel.looks.isEmpty())
                val refs = namedKeys.flatMap { key -> listOf(SlabRef(key, 0, "normal"), SlabRef(key, record.seq, preset.blend)) }
                    .sortedWith(compareBy({ it.key }, { it.seq }))
                check(capturedCel.slabs == refs) { "$phase archive metadata lost slab seq/operator" }
                check(snapshot.slabs.keys == namedKeys.map { SlabKey(layerId, celId, it, record.seq) }.toSet())
                for ((key, bytes) in rawBytes) {
                    val name = DocOps.key(Tiles.tx(key), Tiles.ty(key))
                    compare(bytes, snapshot.slabs.getValue(SlabKey(layerId, celId, name, record.seq)),
                        "$phase archive canonical slab vs original raw", key)
                }
            } else {
                check(snapshot.strokes == mapOf((layerId to celId) to listOf(record)))
                check(capturedCel.strokesFile == "layers/$layerId/$celId/strokes.jbs")
                check(snapshot.slabs.isEmpty() && capturedCel.slabs.isEmpty())
                check(snapshot.looks.keys == namedKeys.map { Triple(layerId, celId, it) }.toSet())
                check(capturedCel.looks.toSet() == namedKeys)
                for ((key, bytes) in expectedLooks) {
                    val name = DocOps.key(Tiles.tx(key), Tiles.ty(key))
                    compare(bytes, snapshot.looks.getValue(Triple(layerId, celId, name)), "$phase snapshot actual saved look", key)
                }
            }
            // Independent CPU source arrays now feed the real archive writer/reader. No GPU replay
            // or format conversion occurs here; .rgba payload comparison checks every byte.
            archiveWriteCalls++
            val output = ByteArrayOutputStream()
            JbArchive.write(output, snapshot)
            val encoded = output.toByteArray()
            archiveReadCalls++
            val loaded = JbArchive.read(ByteArrayInputStream(encoded))
            check(snapshot.doc == loaded.doc) { "$phase archive document metadata/ids changed" }
            check(snapshot.strokes == loaded.strokes) { "$phase archive raw records/seq/seed/samples changed" }
            check(snapshot.brushes == loaded.brushes) { "$phase archive frozen definitions/ids changed" }
            fun <K> sameBytes(expected: Map<K, ByteArray>, actual: Map<K, ByteArray>, family: String) {
                check(expected.keys == actual.keys) { "$phase archive $family physical address keys changed" }
                for ((key, bytes) in expected) {
                    archiveCpuComparisons++
                    check(bytes.contentEquals(actual.getValue(key))) { "$phase archive $family $key changed RGBA byte order/content" }
                }
            }
            sameBytes(snapshot.tiles, loaded.tiles, "bottom pixels")
            sameBytes(snapshot.slabs, loaded.slabs, "upper source slabs")
            sameBytes(snapshot.looks, loaded.looks, "saved looks")
            sameBytes(snapshot.mediaTiles, loaded.mediaTiles, "media")
            check(loaded.thumbnailPng == null)
            archiveRoundtripsCompleted++
            archives.put(JSONObject().put("phase", phase).put("passed", true).put("archiveBytes", encoded.size)
                .put("physicalLayerId", layerId).put("physicalCelId", celId)
                .put("rawRecordCount", loaded.strokes.values.sumOf { it.size })
                .put("bottomPixelTileCount", loaded.tiles.size).put("upperSlabTileCount", loaded.slabs.size)
                .put("savedLookTileCount", loaded.looks.size).put("frozenBrushCount", loaded.brushes.size)
                .put("rawGpuBytesPreserved", true).put("metadataAndPhysicalIdsExact", true))
            check(current.planes.getValue(plane) === sourcePlane) { "$phase capture/archive mutated physical scene" }
            return loaded
        }
        try {
            errors("entry")
            report.put("glVendor", GLES30.glGetString(GLES30.GL_VENDOR) ?: JSONObject.NULL)
                .put("glRenderer", GLES30.glGetString(GLES30.GL_RENDERER) ?: JSONObject.NULL)
                .put("glVersion", GLES30.glGetString(GLES30.GL_VERSION) ?: JSONObject.NULL)
                .put("operator", preset.blend).put("brushId", input.brushId)
                .put("brushOpacity", preset.opacity.base).put("recordId", input.id).put("recordSeq", input.seq)
            errors("driver strings")
            check(layerId in engine.layerIds() && engine.tileKeys(layerId).isEmpty()) { "Needs an existing empty paint layer" }
            check(engine.boardDocument == null) { "Archive fixture supports only the isolated continuous scratch engine" }
            check(!engine.undo.canUndo && !engine.undo.canRedo) { "Needs empty throwaway history" }
            require(input.seq > 0 && !input.locked) { "Needs an unlocked stored record with seq>0" }
            require(preset.opacity.inputs.isEmpty() && preset.opacity.base > 0f && preset.opacity.base < 1f) {
                "Needs constant fractional opacity to make opacity-once sensitive"
            }
            val record = input.copy(samples = input.samples.toList())
            val names = IntArray(1)
            GLES30.glGenFramebuffers(1, names, 0)
            readFbo = names[0]
            check(readFbo != 0)
            errors("read FBO allocation")
            stageLabel = "raw preparation"
            rawPrepCalls++
            val source = engine.prepareRecordedLine(layerId, record, preset).also { raw = it }
            sourceTiles = source.tiles.size
            check(sourceTiles in 1..16) { "Actual tile count $sourceTiles outside bounded 1..16 probe scope" }
            errors(stageLabel)
            val keys = source.tiles.keys.toSet()
            val rawBytes = source.tiles.mapValues { (key, texture) -> read(texture, "raw $key") }
            for (bytes in rawBytes.values) for (p in bytes.indices step 4) {
                val alpha = bytes[p + 3].toInt() and 255
                if (alpha > 0) inkPixels++
                check((0..2).all { (bytes[p + it].toInt() and 255) <= alpha }) { "Raw source is not premultiplied" }
                for (c in 0..3) {
                    val original = bytes[p + c].toInt() and 255
                    if ((original * preset.opacity.base).roundToInt() != original) opacityControlChannels++
                }
            }
            check(inkPixels > 0 && opacityControlChannels > 0) { "Empty/opacity-insensitive source; inconclusive" }

            stageLabel = "backdrop upload"
            val baseBytes = ByteArray(length)
            for (y in 0 until n) for (x in 0 until n) {
                val rgba = if (((x / 4 + y / 4) and 1) == 0) intArrayOf(43,157,211,255) else intArrayOf(13,7,29,47)
                val p = (y * n + x) * 4
                for (c in 0..3) baseBytes[p + c] = rgba[c].toByte()
            }
            for (key in keys) {
                GLES30.glGenTextures(1, names, 0)
                val texture = names[0]
                check(texture != 0)
                // Record private ownership immediately, before any GL/check failure.
                backdrops[key] = texture
                feedback(texture !in source.tiles.values && backdrops.values.count { it == texture } == 1, "backdrop")
                detach()
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
                GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, 0)
                GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, 0)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                val upload = ByteBuffer.allocateDirect(length).put(baseBytes)
                upload.position(0)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, n, n, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, upload)
                errors("backdrop $key")
            }
            detach()
            val original = RecordedCel(layerId, null, listOf(record), backdrops.mapValues { (_, texture) ->
                listOf(RecordedCel.PixelSlab(0, UndoLog.SlabValue(texture, "normal")))
            })
            stageLabel = "scene creation"
            createSceneCalls++
            var current = engine.createRecordedScene(mapOf(plane to original), mapOf(record.brushId to preset))
                .also { scene = it }
            errors(stageLabel)
            if (openCold) {
                stageLabel = "clone uploaded saved looks"
                for (key in keys) {
                    val oldLook = checkNotNull(current.look(plane, key))
                    unadoptedSavedLooks[key] = checkNotNull(engine.composeRecordedTile(listOf(
                        GlPaintEngine.OrderedRecordedUnit(0, oldLook, "normal"))))
                }
                feedback(unadoptedSavedLooks.values.toSet().size == keys.size &&
                    unadoptedSavedLooks.values.none { it in current.heldCacheTextures() ||
                        it in source.tiles.values || it in backdrops.values }, "cold upload ownership")
                val expectedSaved = looks(current, keys, "warm saved baseline")
                engine.releaseRecordedScene(); scene = null
                val lineCalls = engine.recordedLinePreparationCalls
                val compositionCalls = engine.recordedCompositionCalls
                stageLabel = "cold saved scene acceptance"
                current = engine.createSavedRecordedScene(mapOf(plane to original), mapOf(record.brushId to preset),
                    mapOf(plane to keys), mapOf(plane to unadoptedSavedLooks.toMap())).also { scene = it }
                coldAccepted = true
                unadoptedSavedLooks.clear() // Only a successful engine factory return transfers uploads.
                coldOpeningLineCalls = engine.recordedLinePreparationCalls - lineCalls
                coldOpeningCompositionCalls = engine.recordedCompositionCalls - compositionCalls
                check(coldOpeningLineCalls == 0L && coldOpeningCompositionCalls == 0L) { "Cold opening rendered source/looks" }
                check(!current.isMaterialized(plane) && current.savedLookKeys(plane) == keys)
                check(current.heldCacheTextures().size == keys.size && !engine.undo.canUndo && !engine.undo.canRedo)
                val coldBytes = looks(current, keys, "cold uploaded looks")
                for (key in keys) compare(expectedSaved.getValue(key), coldBytes.getValue(key), "cold uploaded look identity", key)
                errors(stageLabel)
            }
            val originalCaches = current.heldCacheTextures()
            feedback(originalCaches.intersect(source.tiles.values.toSet() + backdrops.values).isEmpty(), "scene cache vs raw/base")
            val before = looks(current, keys, "before")
            stageLabel = "before bake snapshot/archive"
            val beforeArchive = archiveRoundtrip(current, record, before, rawBytes, "before bake", false)

            stageLabel = "stage bake"
            stageBakeCalls++
            val beforeBakeLineCalls = engine.recordedLinePreparationCalls
            current.stageBake(setOf(LineAddress(layerId, null, record.id))).use { proposal ->
                check(current.planes.getValue(plane) === original && !engine.undo.canUndo) { "Staging published early" }
                check(proposal.commit()) { "Bake publication refused" }
                bakeCommitted = true
            }
            if (openCold) {
                coldFirstEditLineCalls = engine.recordedLinePreparationCalls - beforeBakeLineCalls
                check(coldFirstEditLineCalls == 1L && current.isMaterialized(plane)) {
                    "Cold first edit must materialize its one old record exactly once"
                }
            }
            errors(stageLabel)
            val baked = current.planes.getValue(plane)
            check(baked.records.isEmpty()) { "Baked record still editable" }
            recordRemoved = true
            check(baked.slabs.keys == keys && engine.undo.undoDepth == 1 && !engine.undo.canRedo)
            val newSlabs = baked.slabs.mapValues { (_, stack) ->
                check(stack.size == 2 && stack.first().seq == 0L)
                val slab = stack.last()
                check(slab.seq == record.seq && slab.value.op == source.op) { "Bake lost sequence/operator" }
                slab.value.texture
            }
            feedback(newSlabs.values.toSet().size == keys.size &&
                newSlabs.values.none { it in originalCaches || it in source.tiles.values || it in backdrops.values },
                "new baked slabs vs original raw/source/cache/base")
            feedback(current.heldCacheTextures().intersect(newSlabs.values.toSet()).isEmpty(), "baked cache vs slabs")
            check(engine.undo.peekUndo()?.lines?.single()?.before?.record == record)
            check(engine.undo.peekUndo()?.slabs?.size == keys.size)
            sequenceOperatorPreserved = true
            val after = looks(current, keys, "after bake")
            for (key in keys) {
                compare(before.getValue(key), after.getValue(key), "bake look", key)
                compare(rawBytes.getValue(key), read(newSlabs.getValue(key), "baked source $key"), "opacity-once canonical copy", key)
                val stack = baked.slabs.getValue(key)
                check(stack.first().value.texture == backdrops.getValue(key) && stack.first().value.op == "normal")
            }
            stageLabel = "after bake snapshot/archive"
            val afterArchive = archiveRoundtrip(current, record, after, rawBytes, "after bake", true)
            check(beforeArchive.tiles.keys == afterArchive.tiles.keys && beforeArchive.brushes == afterArchive.brushes)
            check(beforeArchive.doc.id == afterArchive.doc.id &&
                beforeArchive.doc.layers.single().id == afterArchive.doc.layers.single().id &&
                beforeArchive.doc.layers.single().cels.single().id == afterArchive.doc.layers.single().cels.single().id)
            for ((key, bytes) in beforeArchive.tiles) {
                archiveCpuComparisons++
                check(bytes.contentEquals(afterArchive.tiles.getValue(key))) { "Bake changed archived bottom pixels $key" }
            }
            archivalBottomPixelsPreserved = true
            stageLabel = "public undo"
            undoCalls++
            undoAccepted = engine.undoStep()
            check(undoAccepted && !engine.undo.canUndo && engine.undo.canRedo)
            errors(stageLabel)
            val undone = current.planes.getValue(plane)
            check(undone.records == original.records && undone.slabs.values.all { it.size == 1 && it.single().seq == 0L })
            recordRestored = true
            check(engine.undo.heldTextures() == newSlabs.values.toSet()) { "Undo did not transfer new slabs to redo ownership" }
            feedback(current.heldCacheTextures().intersect(newSlabs.values.toSet()).isEmpty(), "undo caches vs held slabs")
            val undoLooks = looks(current, keys, "undo")
            for (key in keys) compare(before.getValue(key), undoLooks.getValue(key), "undo look", key)
            stageLabel = "public redo"
            redoCalls++
            redoAccepted = engine.redoStep()
            check(redoAccepted && engine.undo.undoDepth == 1 && !engine.undo.canRedo && engine.undo.heldTextures().isEmpty())
            errors(stageLabel)
            val redone = current.planes.getValue(plane)
            check(redone.records.isEmpty())
            recordRemovedAgain = true
            for (key in keys) {
                val slab = redone.slabs.getValue(key).last()
                check(slab.seq == record.seq && slab.value.op == source.op && slab.value.texture == newSlabs.getValue(key))
            }
            val redoLooks = looks(current, keys, "redo")
            for (key in keys) compare(before.getValue(key), redoLooks.getValue(key), "redo look", key)
        } catch (e: Exception) {
            failure = "$stageLabel ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            // Query final ownership, never infer it from the last successful checkpoint.
            try {
                detach()
                val held = engine.undo.heldTextures()
                val live = scene?.planes?.values.orEmpty().flatMap { cel ->
                    cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } }
                }.toSet() - backdrops.values.toSet()
                val caches = scene?.heldCacheTextures().orEmpty()
                val reclaimLive = live - held
                // Defensive de-duplication also on failure: an invalid alias must not recycle raw twice.
                val reclaimRaw = raw?.tiles.orEmpty().filterValues { it !in live && it !in held && it !in caches }
                val reclaimUploads = unadoptedSavedLooks.values.toSet() - live - held - caches -
                    raw?.tiles.orEmpty().values.toSet() - backdrops.values.toSet()
                heldSlabsClosed = held.size
                engine.releaseRecordedScene() // Derived caches only; borrowed sources untouched.
                engine.undo.clear()           // Releases HELD sources through the engine pool API.
                if (reclaimLive.isNotEmpty()) {
                    engine.discardRecordedLine(GlPaintEngine.RecordedLineUnit("normal",
                        reclaimLive.mapIndexed { i, texture -> i.toLong() to texture }.toMap()))
                    liveSlabsRecycled = reclaimLive.size
                }
                if (reclaimRaw.isNotEmpty()) engine.discardRecordedLine(
                    GlPaintEngine.RecordedLineUnit(raw?.op ?: "normal", reclaimRaw))
                if (reclaimUploads.isNotEmpty()) engine.discardRecordedLine(
                    GlPaintEngine.RecordedLineUnit("normal", reclaimUploads.mapIndexed { i, texture -> i.toLong() to texture }.toMap()))
                if (readFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(readFbo), 0)
                if (backdrops.isNotEmpty()) GLES30.glDeleteTextures(backdrops.size, backdrops.values.toIntArray(), 0)
                engine.release() // Deletes engine pool entries exactly once, including newly recycled slabs.
                errors("cleanup")
                cleanupComplete = true
            } catch (e: Exception) {
                failure = listOfNotNull(failure, "Cleanup ${e.javaClass.simpleName}: ${e.message}").joinToString("; ")
            }
        }
        return Result(report.put("passed", failure == null && cleanupComplete)
            .put("archiveRoundtripExecuted", archiveWriteCalls > 0 || archiveReadCalls > 0)
            .put("snapshotCaptureCalls", snapshotCaptureCalls).put("archiveWriteCalls", archiveWriteCalls)
            .put("archiveReadCalls", archiveReadCalls).put("archiveRoundtripsCompleted", archiveRoundtripsCompleted)
            .put("archiveExactCpuPayloadComparisons", archiveCpuComparisons).put("archives", archives)
            .put("archivalBottomPixelsPreservedAcrossBake", archivalBottomPixelsPreserved)
            .put("failure", failure ?: JSONObject.NULL).put("cleanupComplete", cleanupComplete)
            .put("tileSize", n).put("sourceTileCount", sourceTiles).put("sourceNonzeroAlphaPixels", inkPixels)
            .put("cpuSecondOpacityMutantDifferentChannels", opacityControlChannels)
            .put("bakeCommitted", bakeCommitted).put("undoAccepted", undoAccepted).put("redoAccepted", redoAccepted)
            .put("sequenceOperatorPreserved", sequenceOperatorPreserved)
            .put("recordRemoved", recordRemoved).put("recordRestoredByUndo", recordRestored)
            .put("recordRemovedByRedo", recordRemovedAgain)
            .put("rawPreparationCalls", rawPrepCalls).put("createSceneCalls", createSceneCalls)
            .put("coldOpeningRequested", openCold).put("coldSceneAccepted", coldAccepted)
            .put("coldOpeningLinePreparationCalls", coldOpeningLineCalls)
            .put("coldOpeningCompositionCalls", coldOpeningCompositionCalls)
            .put("coldFirstEditLinePreparationCalls", coldFirstEditLineCalls)
            .put("stageBakeCalls", stageBakeCalls).put("publicUndoCalls", undoCalls).put("publicRedoCalls", redoCalls)
            .put("readbackCalls", reads).put("glErrorChecks", glChecks).put("observedGlErrors", glErrors)
            .put("framebufferChecks", fboChecks).put("incompleteFramebuffers", badFbos)
            .put("feedbackChecks", feedbackChecks).put("feedbackFailures", badFeedback)
            .put("liveBakedSlabsRecycled", liveSlabsRecycled).put("heldSlabsClosedByHistory", heldSlabsClosed)
            .put("comparisons", comparisons))
    }
}
