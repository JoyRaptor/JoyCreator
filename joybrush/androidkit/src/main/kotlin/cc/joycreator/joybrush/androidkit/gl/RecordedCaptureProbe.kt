package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.lines.RecordedScene
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import java.nio.ByteBuffer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Actual captured-record parity against the ORIGINAL completed benchmark engine. Caller must
 * supply the detached frozen definition and exact post-guide/pre-response raw capture/seed,
 * queued AFTER its legacy stroke ends, and attest that the target physical paint plane was empty
 * at pen-down. This helper cannot establish that earlier condition from the completed engine.
 * Call on its GL thread, in a fresh unsaved diagnostic bench, with no concurrent stroke/playback.
 *
 * Settling an outstanding WC fade is the sole permitted completion of pre-existing work. All
 * mutation guards take their baseline AFTER settlement. The helper temporarily registers a
 * concrete recorded scene with ONE record/no slabs, never stages or publishes history, then
 * closes ONLY that scene's derived caches and its own read FBO. NEVER release the original engine.
 * Existing registered scenes are refused by createRecordedScene; only a successful factory
 * return grants this helper permission to releaseRecordedScene. No view/save/Working activation.
 *
 * Limit: static continuous/shared target only, <=16 union tiles. Region frames are excluded
 * because public readTile returns a projection there, not the needed physical shared pixels.
 * GL draw state is not preserved; the benchmark must set its normal draw state afterwards.
 * Counters describe external calls/checkpoints, not unobservable internal draw/FBO/error guards.
 */
internal object RecordedCaptureProbe {
    internal data class Result(val details: JSONObject)
    internal fun json(result: Result): JSONObject = result.details

    internal fun run(engine: GlPaintEngine, layerId: String,
                     frozenPreset: BrushPreset, input: StrokeRecord): Result {
        val report = JSONObject().put("probe", "recordedCaptureParity")
            .put("comparison", "Exact raw RGBA8 on union of actual settled pixel and derived scene look keys")
            .put("preStrokeEmptyPlaneEvidence", "Caller required; cannot infer prior emptiness from completed pixels")
            .put("coverage", "Captured record replay on original GL context; temporary derived scene only")
            .put("capturePublicationTokenEvidence", "Host queue/capture token must be checked by caller; unavailable through engine API")
            .put("engineOwnerIdentity", System.identityHashCode(engine))
            .put("workingOrRecoveryInstalled", false).put("internalEngineChecksCounted", false)
            .put("feedbackCheckMeaning", "External derived caches vs held history/latest live stroke handles only")
        val comparisons = JSONArray()
        val size = Tiles.SIZE
        val length = size * size * 4
        val transparent = ByteArray(length)
        val plane = layerId to null
        var ownedScene: RecordedScene<Int>? = null
        var readFbo = 0
        var baselinePixels: Map<Long, ByteArray>? = null
        var verifyBaseline: (() -> Unit)? = null
        var failure: String? = null
        var phase = "entry"
        var cleanupComplete = false
        var mutationGuardsPassed = false
        var settlementCalls = 0
        var settlementCommitted = false
        var createSceneCalls = 0
        var closeSceneCalls = 0
        var actualPixelReadCalls = 0
        var actualPixelReadbacks = 0
        var derivedReadbacks = 0
        var glChecks = 0
        var glErrors = 0
        var fboChecks = 0
        var badFbos = 0
        var feedbackChecks = 0
        var badFeedback = 0
        var actualTileCount = 0
        var derivedTileCount = 0
        var unionTileCount = 0
        var actualInkPixels = 0
        var derivedInkPixels = 0

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
        fun detach() {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            for (slot in 0..1) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + slot)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            }
        }
        fun pack() {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
            GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS, 0)
        }
        fun actualPixels(label: String): Map<Long, ByteArray> {
            val keys = engine.tileKeys(layerId).toSet()
            check(keys.size <= 16) { "$label target tile count ${keys.size} exceeds scope" }
            return keys.associateWith { key ->
                detach()
                pack()
                actualPixelReadCalls++
                val bytes = checkNotNull(engine.readTile(layerId, key)) { "$label physical tile $key disappeared" }
                actualPixelReadbacks++
                check(bytes.size == length)
                errors("$label actual pixels $key")
                detach()
                bytes
            }
        }
        fun derived(texture: Int, key: Long): ByteArray {
            check(texture != 0)
            detach()
            pack()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, readFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0)
            fboChecks++
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) badFbos++
            check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Derived $key incomplete FBO 0x${status.toString(16)}" }
            GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0)
            val buffer = ByteBuffer.allocateDirect(length)
            derivedReadbacks++
            GLES30.glReadPixels(0, 0, size, size, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            errors("derived look $key")
            buffer.position(0)
            val bytes = ByteArray(length).also { buffer.get(it) }
            detach()
            return bytes
        }
        fun compare(expected: ByteArray, actual: ByteArray, key: Long, label: String, enforce: Boolean = true): Boolean {
            var channels = 0
            var pixels = 0
            var max = 0
            for (p in expected.indices step 4) {
                var different = false
                for (c in 0..3) {
                    val delta = abs((expected[p + c].toInt() and 255) - (actual[p + c].toInt() and 255))
                    if (delta != 0) { channels++; different = true }
                    max = maxOf(max, delta)
                }
                if (different) pixels++
            }
            comparisons.put(JSONObject().put("phase", label).put("tileKey", key)
                .put("differentChannels", channels).put("differentPixels", pixels).put("maxByteDelta", max))
            if (enforce) check(channels == 0) { "$label tile $key: $channels differing channels/$pixels pixels, max $max" }
            return channels == 0
        }
        fun ink(bytes: ByteArray): Int = (3 until bytes.size step 4).count { (bytes[it].toInt() and 255) != 0 }
        try {
            errors("entry")
            report.put("glVendor", GLES30.glGetString(GLES30.GL_VENDOR) ?: JSONObject.NULL)
                .put("glRenderer", GLES30.glGetString(GLES30.GL_RENDERER) ?: JSONObject.NULL)
                .put("glVersion", GLES30.glGetString(GLES30.GL_VERSION) ?: JSONObject.NULL)
                .put("layerId", layerId).put("recordId", input.id).put("recordSeq", input.seq)
                .put("recordSeed", input.seed).put("capturedSampleCount", input.samples.size)
                .put("brushId", input.brushId).put("brushEngine", frozenPreset.engine).put("operator", frozenPreset.blend)
            errors("driver strings")
            require(input.seq > 0 && !input.locked && input.samples.isNotEmpty()) { "Needs a stored unlocked nonempty actual captured record" }
            require(input.brushId == frozenPreset.id) { "Captured record must resolve its detached frozen definition" }
            require(frozenPreset.blend in setOf("normal", "erase", "multiply", "screen")) { "Behind/other diagnostic operators are refused" }
            require(layerId in engine.layerIds()) { "Captured target layer no longer exists" }
            val metadata = engine.boardDocument?.layers?.firstOrNull { it.id == layerId }
            require(engine.boardDocument == null || metadata != null) { "Target has no physical document identity" }
            require(metadata == null || (metadata.regions.isEmpty() && metadata.animatedIn == null && metadata.cels.size == 1)) {
                "Region/multiple-cel/animation targets need a physical readback API, not projected readTile"
            }
            phase = "settle completed stroke"
            settlementCalls++
            settlementCommitted = engine.settleWatercolourFade()
            check(!engine.strokeInProgress) { "Captured legacy stroke has not ended" }
            errors(phase)
            // Do not claim settlement left history unchanged: it may legitimately finish pen-up WC.
            val undoHead = engine.undo.peekUndo()
            val redoHead = engine.undo.peekRedo()
            val undoDepth = engine.undo.undoDepth
            val held = engine.undo.heldTextures()
            val heldBytes = engine.undo.heldBytes
            report.put("historyOwnerIdentity", System.identityHashCode(engine.undo))
                .put("settledUndoHeadIdentity", undoHead?.let { System.identityHashCode(it) } ?: JSONObject.NULL)
                .put("settledRedoHeadIdentity", redoHead?.let { System.identityHashCode(it) } ?: JSONObject.NULL)
                .put("settledUndoDepth", undoDepth).put("settledHeldTextureCount", held.size)
            val doc = engine.boardDocument
            val paper = engine.documentPaper
            val mediaBytes = engine.mediaResidentBytes()
            val stack = engine.stack(null)
            val layerIds = engine.layerIds()
            val record = input.copy(samples = input.samples.toList())
            phase = "settled actual pixel baseline"
            val pixels = actualPixels("before replay").also { baselinePixels = it }
            actualTileCount = pixels.size
            actualInkPixels = pixels.values.sumOf(::ink)
            verifyBaseline = {
                check(engine.undo.peekUndo() === undoHead && engine.undo.peekRedo() === redoHead &&
                    engine.undo.undoDepth == undoDepth && engine.undo.heldTextures() == held && engine.undo.heldBytes == heldBytes) {
                    "Temporary replay changed actual legacy history heads/ownership"
                }
                check(engine.boardDocument === doc && engine.documentPaper == paper && engine.stack(null) == stack &&
                    engine.layerIds() == layerIds && engine.mediaResidentBytes() == mediaBytes) {
                    "Temporary replay changed document/paper/layer identities or presentation metadata"
                }
                check(!engine.strokeInProgress) { "Temporary replay left a stroke active" }
                val after = actualPixels("after temporary scene close")
                check(after.keys == pixels.keys) { "Temporary replay changed physical pixel tile keys" }
                for ((key, bytes) in pixels) compare(bytes, after.getValue(key), key, "original pixels unchanged")
                mutationGuardsPassed = true
            }
            phase = "temporary recorded scene factory"
            createSceneCalls++
            // The factory checks prior registration before rendering. Assignment happens ONLY on
            // successful return; an existing scene or partial-create failure never becomes ours.
            val scene = engine.createRecordedScene(mapOf(plane to RecordedCel<Int>(layerId, null, listOf(record))),
                mapOf(record.brushId to frozenPreset)).also { ownedScene = it }
            errors(phase)
            feedbackChecks++
            val headLiveSources = undoHead?.changes.orEmpty()
                .filter { it.layerId == layerId && it.celId == null }.mapNotNull { it.after }.toSet()
            val aliases = scene.heldCacheTextures().intersect(held + headLiveSources)
            if (aliases.isNotEmpty()) badFeedback++
            check(aliases.isEmpty()) { "Derived scene aliased history-held/latest live stroke sources" }
            check(scene.planes.getValue(plane).records == listOf(record) && scene.planes.getValue(plane).slabs.isEmpty())
            val sceneKeys = scene.lookKeys(plane)
            derivedTileCount = sceneKeys.size
            val keys = pixels.keys + sceneKeys
            unionTileCount = keys.size
            check(keys.size in 1..16) { "Union tile count ${keys.size} outside bounded 1..16 scope" }
            check(scene.savedLookKeys(plane) == sceneKeys) { "Single captured record lost its authoritative look footprint" }
            val names = IntArray(1)
            GLES30.glGenFramebuffers(1, names, 0)
            readFbo = names[0]
            check(readFbo != 0)
            errors("derived read FBO allocation")
            phase = "actual captured replay parity"
            var parityExact = true
            for (key in keys) {
                val live = pixels[key] ?: transparent
                val replay = scene.look(plane, key)?.let { derived(it, key) } ?: transparent
                derivedInkPixels += ink(replay)
                if (!compare(live, replay, key, "captured live vs replay", enforce = false)) parityExact = false
            }
            check(parityExact) { "Actual captured replay differs; all union-tile RGBA8 diffs are reported" }
            if (frozenPreset.blend == "normal") check(actualInkPixels > 0 && derivedInkPixels > 0) {
                "Normal/WC actual and replay need nonzero alpha; empty result is inconclusive"
            }
        } catch (e: Exception) {
            failure = "$phase ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            var externalCleanupSucceeded = false
            try {
                detach()
                if (readFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(readFbo), 0)
                externalCleanupSucceeded = true
            } catch (e: Exception) {
                failure = listOfNotNull(failure, "Read FBO cleanup ${e.javaClass.simpleName}: ${e.message}").joinToString("; ")
            }
            // Always attempt scene unregistration, even if independent FBO cleanup failed.
            try {
                if (ownedScene != null) {
                    closeSceneCalls++
                    engine.releaseRecordedScene() // NEVER release the original engine or its history.
                    ownedScene = null
                }
                errors("temporary cleanup")
                cleanupComplete = externalCleanupSucceeded
            } catch (e: Exception) {
                failure = listOfNotNull(failure, "Cleanup ${e.javaClass.simpleName}: ${e.message}").joinToString("; ")
            }
            // Separate guard from cleanup so parity/cleanup failures still attempt original-state verification.
            try { verifyBaseline?.invoke() } catch (e: Exception) {
                failure = listOfNotNull(failure, "Mutation guard ${e.javaClass.simpleName}: ${e.message}").joinToString("; ")
            }
        }
        return Result(report.put("passed", failure == null && cleanupComplete && mutationGuardsPassed)
            .put("failure", failure ?: JSONObject.NULL).put("cleanupComplete", cleanupComplete)
            .put("mutationGuardsPassed", mutationGuardsPassed).put("baselineCaptured", baselinePixels != null)
            .put("settlementCalls", settlementCalls).put("settlementCommittedExistingWcStroke", settlementCommitted)
            .put("temporarySceneCreateCalls", createSceneCalls).put("temporarySceneCloseCalls", closeSceneCalls)
            .put("actualTileCount", actualTileCount).put("derivedTileCount", derivedTileCount).put("unionTileCount", unionTileCount)
            .put("actualNonzeroAlphaPixels", actualInkPixels).put("derivedNonzeroAlphaPixels", derivedInkPixels)
            .put("actualEnginePixelReadCalls", actualPixelReadCalls).put("actualEnginePixelReadbacks", actualPixelReadbacks)
            .put("derivedExternalReadbacks", derivedReadbacks).put("glErrorChecks", glChecks).put("observedGlErrors", glErrors)
            .put("externalFramebufferChecks", fboChecks).put("incompleteFramebuffers", badFbos)
            .put("feedbackChecks", feedbackChecks).put("feedbackFailures", badFeedback).put("comparisons", comparisons))
    }
}
