package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.lines.RecordedScene
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.render.Blend
import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import cc.joycreator.joybrush.core.vector.RecordedEdits
import java.nio.ByteBuffer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Debug GL-thread fixture. Consumes a fresh throwaway engine, never the view engine.
 * Requires bound-safe resetDocument()/release() lifecycle before adoption. No bake/save/Select.
 * All independent sources are prepared BEFORE binding and retained until all comparisons finish.
 * Current fixture supports NORMAL only; ink and WC use the exact canonical engine replay adapter.
 */
internal object RecordedJointProbe {
    internal fun json(result: JSONObject): JSONObject = result

    internal fun run(engine: GlPaintEngine, layerId: String, preset: BrushPreset,
                     input: StrokeRecord, openCold: Boolean = false): JSONObject {
        val report = JSONObject().put("probe", "recordedJoint").put("schemaVersion", 1)
            .put("openCold", openCold).put("comparison", "Exact RGBA8, every actual old/new union key")
            .put("proofScope", "Actual joint engine root + public undo/redo + canonical GPU line/look; no view activation, save, quality or performance claim")
            .put("internalEngineChecksCounted", false).put("optionalNativeMediaExecuted", false)
        val rows = JSONArray()
        val n = Tiles.SIZE
        val length = n * n * 4
        val plane: Pair<String, String?> = layerId to null
        val siblingId = "joint-recorded-sibling"
        val siblingPlane: Pair<String, String?> = siblingId to null
        val pixelsId = "joint-native"
        val nativeSiblingId = "joint-native-sibling"
        val nativeKey = Tiles.key(8, 8)
        val nativeSiblingKey = Tiles.key(9, 8)
        val old = input.copy(samples = input.samples.toList())
        val moved = old.copy(samples = old.samples.map { it.copy(x = it.x + n * 2f, y = it.y + n) })
        val sibling = old.copy(id = "joint-sibling-line", samples = old.samples.map {
            it.copy(x = it.x + n * 5f, y = it.y + n * 3f)
        })
        val rawUnits = arrayListOf<GlPaintEngine.RecordedLineUnit>()
        val rawBytes = arrayListOf<Map<Long, ByteArray>>()
        val unadoptedUploads = linkedSetOf<Int>()
        var scene: RecordedScene<Int>? = null
        var readFbo = 0
        var failure: String? = null
        var phase = "entry"
        var reads = 0
        var glChecks = 0
        var errors = 0
        var fboChecks = 0
        var badFbos = 0
        var feedbackChecks = 0
        var badFeedback = 0
        var comparisons = 0
        var checkedChannels = 0L
        var checkedPixels = 0L
        var differentChannels = 0L
        var differentPixels = 0L
        var maxDelta = 0
        var rawPrepCalls = 0
        var savedUploadCalls = 0
        var sceneFactoryCalls = 0
        var bindCalls = 0
        var publishCalls = 0
        var undoCalls = 0
        var redoCalls = 0
        var rootChecks = 0
        var historyChecks = 0
        var nativeApiChecks = 0
        var nativeReadChecks = 0
        var siblingChecks = 0
        var rawPreservationChecks = 0
        var physicalSourceChecks = 0
        var unionAbsenceChecks = 0
        var nativeAfter = 0
        var nativeBefore = 0
        var cleanupComplete = false
        var resetCalls = 0
        var sourceInkPixels = 0
        var invalidPremultipliedPixels = 0
        var changedReferenceChannels = 0L
        var actualUnion: Set<Long> = emptySet()
        var openingLineCalls = -1L
        var openingCompositionCalls = -1L
        var firstEditLineCalls = -1L
        var firstEditCompositionCalls = -1L

        fun gl(label: String) {
            glChecks++
            var first = GLES30.GL_NO_ERROR
            var code = GLES30.glGetError()
            while (code != GLES30.GL_NO_ERROR) {
                if (first == GLES30.GL_NO_ERROR) first = code
                errors++
                code = GLES30.glGetError()
            }
            check(first == GLES30.GL_NO_ERROR) { "$label GL error $first" }
        }
        fun feedback(ok: Boolean, label: String) {
            feedbackChecks++
            if (!ok) badFeedback++
            check(ok) { "$label external texture ownership/alias failure" }
        }
        fun read(texture: Int, label: String): ByteArray {
            feedback(texture != 0 && GLES30.glIsTexture(texture), "$label live texture")
            for (slot in 0..1) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + slot)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, readFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0)
            fboChecks++
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) badFbos++
            check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "$label framebuffer $status" }
            GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
            GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS, 0)
            val buffer = ByteBuffer.allocateDirect(length)
            reads++
            GLES30.glReadPixels(0, 0, n, n, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            gl(label)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, 0, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            buffer.position(0)
            return ByteArray(length).also { buffer.get(it) }
        }
        fun upload(bytes: ByteArray, label: String): Int {
            require(bytes.size == length)
            val names = IntArray(1)
            GLES30.glGenTextures(1, names, 0)
            val texture = names[0]
            unadoptedUploads.add(texture) // Register cleanup before any later failure.
            feedback(texture != 0 && texture !in engine.protectedRecordedTextureHandles() &&
                rawUnits.none { texture in it.tiles.values }, "$label new name")
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            val buffer = ByteBuffer.allocateDirect(length).put(bytes)
            buffer.position(0)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, n, n, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            gl(label)
            return texture
        }
        fun compare(expected: ByteArray, actual: ByteArray, label: String, key: Long) {
            require(expected.size == length && actual.size == length)
            var channels = 0
            var pixels = 0
            var deltaMax = 0
            for (p in expected.indices step 4) {
                var changed = false
                for (c in 0..3) {
                    val delta = kotlin.math.abs((expected[p + c].toInt() and 255) - (actual[p + c].toInt() and 255))
                    if (delta != 0) { channels++; changed = true }
                    deltaMax = maxOf(deltaMax, delta)
                }
                if (changed) pixels++
            }
            comparisons++; checkedChannels += length; checkedPixels += n.toLong() * n
            differentChannels += channels; differentPixels += pixels; maxDelta = maxOf(maxDelta, deltaMax)
            rows.put(JSONObject().put("phase", label).put("key", key.toString())
                .put("differentChannels", channels).put("differentPixels", pixels).put("maxByteDelta", deltaMax))
            check(channels == 0) { "$label/$key differs: $channels channels" }
        }
        fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
            .also { it.isAccessible = true }.get(owner)
        fun root(): Any = checkNotNull(field(engine, "publishedPaint"))
        fun checkRoot(current: RecordedScene<Int>) {
            rootChecks++
            val value = root()
            check(field(value, "recorded") === current.snapshotState())
            check(field(value, "cursor") === engine.undo.snapshotCursor())
            check(root() === value)
        }
        fun swatch(r: Int, g: Int, b: Int, a: Int): ByteArray = ByteArray(length).also {
            for (p in it.indices step 4) { it[p] = r.toByte(); it[p+1] = g.toByte(); it[p+2] = b.toByte(); it[p+3] = a.toByte() }
        }
        val beforeBytes = swatch(11, 23, 37, 61)
        val afterBytes = swatch(43, 157, 211, 255)
        val nativeSiblingBytes = swatch(7, 13, 19, 31)
        try {
            gl("entry")
            check(engine.ready && preset.blend == "normal" && old.seq > 0 && !old.locked)
            check(old.brushId == preset.id && old.samples.size in 2..64)
            check(engine.tileKeys(layerId).isEmpty() && !engine.undo.canUndo && !engine.undo.canRedo)
            engine.addLayer(siblingId); engine.addLayer(pixelsId); engine.addLayer(nativeSiblingId)
            val names = IntArray(1)
            GLES30.glGenFramebuffers(1, names, 0); readFbo = names[0]
            check(readFbo != 0); gl("read framebuffer allocation")
            phase = "independent source preparation"
            for ((owner, record) in listOf(layerId to old, layerId to moved, siblingId to sibling)) {
                rawPrepCalls++
                val unit = engine.prepareRecordedLine(owner, record, preset)
                rawUnits.add(unit)
                gl("$owner independent source preparation")
                check(unit.op == "normal" && unit.tiles.isNotEmpty() && unit.tiles.size <= 16)
                val bytes = unit.tiles.mapValues { read(it.value, "$owner independent canonical source") }
                rawBytes.add(bytes)
                check(bytes.values.any { tile -> (3 until tile.size step 4).any { (tile[it].toInt() and 255) > 0 } }) {
                    "$owner independent canonical source is empty-alpha; inconclusive"
                }
                for (tile in bytes.values) for (p in tile.indices step 4) {
                    val a = tile[p+3].toInt() and 255
                    if (a != 0) sourceInkPixels++
                    if ((0..2).any { (tile[p+it].toInt() and 255) > a }) invalidPremultipliedPixels++
                }
            }
            check(sourceInkPixels > 0 && invalidPremultipliedPixels == 0)
            actualUnion = rawBytes[0].keys + rawBytes[1].keys
            check(actualUnion.size <= 16 && rawBytes[0].keys != rawBytes[1].keys) { "Movement must change actual tile bounds" }
            // Independent CPU composition starts from immutable canonical source readbacks,
            // not a look cache. NORMAL over a seq0 physical source, quantized once to RGBA8.
            val baseBytes = swatch(43, 157, 211, 255)
            fun reference(source: ByteArray): ByteArray {
                val out = ByteArray(length)
                val s = FloatArray(4); val d = FloatArray(4); val result = FloatArray(4)
                for (p in source.indices step 4) {
                    for (c in 0..3) {
                        s[c] = (source[p+c].toInt() and 255) / 255f
                        d[c] = (baseBytes[p+c].toInt() and 255) / 255f
                    }
                    Blend.apply(BlendMode.NORMAL, s, d, result)
                    for (c in 0..3) out[p+c] = (result[c].coerceIn(0f, 1f) * 255f).roundToInt().toByte()
                }
                return out
            }
            val expectedOld = rawBytes[0].mapValues { reference(it.value) }
            val expectedNew = rawBytes[1].mapValues { reference(it.value) }
            val expectedOldFull = actualUnion.associateWith { expectedOld[it] ?: baseBytes }
            val expectedNewFull = actualUnion.associateWith { expectedNew[it] ?: baseBytes }
            val baseTextures = actualUnion.associateWith { upload(baseBytes, "physical seq0 base source") }
            for (key in actualUnion) {
                val a = expectedOldFull.getValue(key); val b = expectedNewFull.getValue(key)
                changedReferenceChannels += a.indices.count { a[it] != b[it] }
            }
            check(changedReferenceChannels > 0)
            phase = "native source setup"
            engine.writeTile(pixelsId, nativeKey, beforeBytes)
            engine.writeTile(nativeSiblingId, nativeSiblingKey, nativeSiblingBytes)
            gl("native source setup")
            nativeBefore = checkNotNull(engine.mediaTile(pixelsId, MEDIA_LOOK, nativeKey))
            nativeAfter = upload(afterBytes, "native after RGBA8")
            compare(afterBytes, read(nativeAfter, "native after upload identity"), "native-upload", nativeKey)
            val slabs = baseTextures.mapValues { listOf(RecordedCel.PixelSlab(0L, UndoLog.SlabValue(it.value, "normal"))) }
            val planes = mapOf(plane to RecordedCel<Int>(layerId, null, listOf(old), slabs),
                siblingPlane to RecordedCel<Int>(siblingId, null, listOf(sibling)))
            val startLines = engine.recordedLinePreparationCalls
            val startComposes = engine.recordedCompositionCalls
            sceneFactoryCalls++
            scene = if (openCold) {
                val uploaded = mapOf(plane to expectedOld.mapValues {
                    savedUploadCalls++; upload(it.value, "cold old look")
                }, siblingPlane to rawBytes[2].mapValues {
                    savedUploadCalls++; upload(it.value, "cold sibling look")
                })
                engine.createSavedRecordedScene(planes, mapOf(preset.id to preset),
                    uploaded.mapValues { it.value.keys }, uploaded).also {
                    uploaded.values.flatMap { it.values }.forEach(unadoptedUploads::remove)
                }
            } else engine.createRecordedScene(planes, mapOf(preset.id to preset))
            val current = checkNotNull(scene)
            openingLineCalls = engine.recordedLinePreparationCalls - startLines
            openingCompositionCalls = engine.recordedCompositionCalls - startComposes
            if (openCold) check(openingLineCalls == 0L && openingCompositionCalls == 0L)
            else check(openingLineCalls == 2L) { "Eager fixture must replay moving and sibling records once each" }
            gl("scene factory")
            phase = "binding"
            bindCalls++; engine.bindRecordedPaintPublication()
            baseTextures.values.forEach(unadoptedUploads::remove) // Transfer before any fallible check.
            checkRoot(current); gl("bound root")
            val siblingCel = current.planes.getValue(siblingPlane)
            val siblingLooks = current.lookKeys(siblingPlane).associateWith { checkNotNull(current.look(siblingPlane, it)) }
            val untouchedNative = checkNotNull(engine.mediaTile(nativeSiblingId, MEDIA_LOOK, nativeSiblingKey))
            val replacement = RecordedEdits.replace(current.planes,
                listOf(AddressedStroke(LineAddress(layerId, null, old.id), moved)), setOf(preset.id))
            val step = UndoLog.Step(listOf(UndoLog.TileChange(pixelsId, nativeKey, nativeBefore, nativeAfter)),
                lines = replacement.step.lines)
            check(step.lines.single().before!!.seq == old.seq && step.lines.single().after!!.seq == old.seq)
            fun checkpoint(label: String, expected: Map<Long, ByteArray>, record: StrokeRecord,
                           expectedNative: Int, expectedNativeBytes: ByteArray, historyEndpoint: Int) {
                phase = label; checkRoot(current)
                historyChecks++
                when (historyEndpoint) {
                    0 -> check(!engine.undo.canUndo && !engine.undo.canRedo)
                    1 -> check(engine.undo.peekUndo() === step && !engine.undo.canRedo)
                    2 -> check(engine.undo.peekRedo() === step && !engine.undo.canUndo)
                }
                check(current.planes.getValue(plane).records.single() == record)
                check(current.lookKeys(plane) == expected.keys)
                check(current.savedLookKeys(plane) == if (record == old) rawBytes[0].keys else rawBytes[1].keys)
                for (key in actualUnion) {
                    val texture = current.look(plane, key)
                    if (key !in expected) {
                        unionAbsenceChecks++
                        check(texture == null) { "$label stale old/new union key $key" }
                    }
                    else {
                        feedback(texture !in rawUnits.flatMap { it.tiles.values } && texture != nativeBefore &&
                            texture != nativeAfter && texture !in siblingLooks.values && texture !in baseTextures.values,
                            "$label recorded look")
                        // Independent canonical source + seq0 base composition, never a cached look.
                        compare(expected.getValue(key), read(checkNotNull(texture), "$label actual look"), label, key)
                    }
                }
                nativeApiChecks++
                check(engine.mediaTile(pixelsId, MEDIA_LOOK, nativeKey) == expectedNative)
                check(engine.tileKeys(pixelsId).toSet() == setOf(nativeKey))
                compare(expectedNativeBytes, read(expectedNative, "$label native physical API texture"), "$label-native", nativeKey)
                nativeReadChecks++
                compare(expectedNativeBytes, checkNotNull(engine.readTile(pixelsId, nativeKey)), "$label-native-readTile", nativeKey)
                siblingChecks++
                check(current.planes.getValue(siblingPlane) === siblingCel)
                check(current.lookKeys(siblingPlane) == siblingLooks.keys)
                for ((key, texture) in siblingLooks) {
                    check(current.look(siblingPlane, key) == texture)
                    compare(rawBytes[2].getValue(key), read(texture, "$label recorded sibling"), "$label-recorded-sibling", key)
                }
                check(engine.mediaTile(nativeSiblingId, MEDIA_LOOK, nativeSiblingKey) == untouchedNative)
                compare(nativeSiblingBytes, checkNotNull(engine.readTile(nativeSiblingId, nativeSiblingKey)), "$label-native-sibling", nativeSiblingKey)
                for (i in rawUnits.indices) for ((key, texture) in rawUnits[i].tiles) {
                    rawPreservationChecks++
                    compare(rawBytes[i].getValue(key), read(texture, "$label retained independent source"), "$label-raw-$i", key)
                }
                check(input == old && preset.id == old.brushId) // Input collections never mutated.
                for ((key, texture) in baseTextures) {
                    physicalSourceChecks++
                    check(current.planes.getValue(plane).slabs.getValue(key).single().value.texture == texture)
                    compare(baseBytes, read(texture, "$label physical source slab"), "$label-physical-source", key)
                }
                gl("$label checkpoint")
            }
            checkpoint("before", if (openCold) expectedOld else expectedOldFull, old, nativeBefore, beforeBytes, 0)
            val initialRoot = root()
            val editLines = engine.recordedLinePreparationCalls
            val editComposes = engine.recordedCompositionCalls
            phase = "publish"
            publishCalls++; check(engine.publishJointPaintStep(step))
            unadoptedUploads.remove(nativeAfter) // Engine/history own both native endpoints now.
            firstEditLineCalls = engine.recordedLinePreparationCalls - editLines
            firstEditCompositionCalls = engine.recordedCompositionCalls - editComposes
            check(firstEditLineCalls == if (openCold) 2L else 1L) {
                "First edit must replay old+moved only when cold, or moved only when eager"
            }
            check(root() !== initialRoot)
            checkpoint("published", expectedNewFull, moved, nativeAfter, afterBytes, 1)
            val publishedRoot = root()
            phase = "undo"; undoCalls++; check(engine.undoStep()); check(root() !== publishedRoot)
            checkpoint("undone", expectedOldFull, old, nativeBefore, beforeBytes, 2)
            val undoneRoot = root()
            phase = "redo"; redoCalls++; check(engine.redoStep()); check(root() !== undoneRoot)
            checkpoint("redone", expectedNewFull, moved, nativeAfter, afterBytes, 1)
        } catch (error: Exception) {
            failure = "$phase ${error.javaClass.simpleName}: ${error.message}"
        } finally {
            try {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                for (slot in 0..1) {
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + slot)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
                }
                if (readFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(readFbo), 0)
                // Retained independent units are caller-owned; release through their engine pool.
                rawUnits.forEach(engine::discardRecordedLine)
                if (unadoptedUploads.isNotEmpty()) GLES30.glDeleteTextures(unadoptedUploads.size,
                    unadoptedUploads.toIntArray(), 0)
                // MUST use the bound engine lifecycle. scene.close() alone is invalid when bound.
                resetCalls++; engine.resetDocument()
                check(!engine.undo.canUndo && !engine.undo.canRedo && engine.layerIds().isEmpty())
                gl("bound engine reset cleanup")
                cleanupComplete = true
            } catch (error: Exception) {
                failure = listOfNotNull(failure, "Cleanup ${error.javaClass.simpleName}: ${error.message}").joinToString("; ")
            }
        }
        return report.put("passed", failure == null && cleanupComplete && errors == 0 && badFbos == 0 && badFeedback == 0)
            .put("failure", failure ?: JSONObject.NULL).put("phase", phase).put("cleanupComplete", cleanupComplete)
            .put("tileSize", n).put("maxActualUnionKeys", 16).put("actualUnionKeys", JSONArray(actualUnion.sorted().map { it.toString() }))
            .put("comparisonCount", comparisons).put("checkedChannels", checkedChannels).put("checkedPixels", checkedPixels)
            .put("differentChannels", differentChannels).put("differentPixels", differentPixels).put("maxByteDelta", maxDelta)
            .put("comparisons", rows).put("changedReferenceChannels", changedReferenceChannels)
            .put("sourceNonzeroAlphaPixels", sourceInkPixels).put("sourceInvalidPremultipliedPixels", invalidPremultipliedPixels)
            .put("rawPreparationCalls", rawPrepCalls).put("savedUploadCalls", savedUploadCalls).put("sceneFactoryCalls", sceneFactoryCalls)
            .put("openingLineCalls", openingLineCalls).put("openingCompositionCalls", openingCompositionCalls)
            .put("firstEditLineCalls", firstEditLineCalls).put("firstEditCompositionCalls", firstEditCompositionCalls)
            .put("bindCalls", bindCalls).put("publishCalls", publishCalls).put("undoCalls", undoCalls).put("redoCalls", redoCalls)
            .put("rootChecks", rootChecks).put("historyChecks", historyChecks).put("nativeApiChecks", nativeApiChecks)
            .put("nativeReadChecks", nativeReadChecks).put("untouchedSiblingChecks", siblingChecks).put("rawPreservationChecks", rawPreservationChecks)
            .put("physicalSourceChecks", physicalSourceChecks)
            .put("unionAbsenceChecks", unionAbsenceChecks).put("maxComparisonRows", 397)
            .put("readbackCalls", reads).put("glErrorChecks", glChecks).put("observedGlErrors", errors)
            .put("framebufferChecks", fboChecks).put("incompleteFramebuffers", badFbos)
            .put("feedbackChecks", feedbackChecks).put("feedbackFailures", badFeedback).put("resetCalls", resetCalls)
    }
}
