package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BrushValidate
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.watercolourRuntime
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.StrokeBlend
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.stroke.CapturedStrokeRules
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkReplay
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Throwaway-engine GPU fixture for actual live scratch extraction only. No record publication,
 * View, source adoption, recovery, paper mutation or physical context-loss capability is granted.
 */
internal object RecordedLiveSourceProbe {
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.get(owner) as T

    private class Readback(val storage: Int, val format: Int, val type: Int, val bytes: ByteArray)

    internal fun run(engine: GlPaintEngine, layerId: String, preset: BrushPreset,
                     input: StrokeRecord): JSONObject {
        val report = JSONObject().put("probe", "recordedLiveSource").put("schemaVersion", 1)
            .put("proofScope", "Actual live scratch lease versus fully settled native RGBA8 on empty ground; no View/raw/seed/record publication/adoption/recovery claim")
            .put("internalEngineChecksCounted", false)
            .put("wrongOpacityControl", "CPU double-opacity scaling of settled native bytes; comparator sensitivity only")
        val rows = JSONArray()
        val ownershipRows = JSONArray()
        val rawRows = JSONArray()
        val n = Tiles.SIZE
        val length = n * n * 4
        val tileBytes = length.toLong()
        val sibling = "live-source-native-sibling"
        val siblingKey = Tiles.key(11, -9)
        var phase = "entry"
        var failure: String? = null
        var cleanupComplete = false
        var readFbo = 0
        var physical: Long? = null
        var ticket: GlPaintEngine.LiveRecordedSourceTicket? = null
        var budgetTicket: GlPaintEngine.LiveRecordedSourceTicket? = null
        var lease: GlPaintEngine.LiveRecordedSourceLease? = null
        var reads = 0; var glChecks = 0; var errors = 0; var fboChecks = 0; var badFbos = 0
        var feedbackChecks = 0; var badFeedback = 0
        var comparisons = 0; var comparedChannels = 0L; var comparedPixels = 0L
        var differentChannels = 0L; var differentPixels = 0L; var maxDelta = 0; var keyDifferences = 0
        var wrongOpacityDifferences = 0L; var scratchDifferences = 0L
        var inkPixels = 0L; var badPremultiplied = 0L; var transparentKeys = 0
        var baselineKeys: Set<Long> = emptySet(); var sourceKeys: Set<Long> = emptySet()
        var baselineBytes: Map<Long, ByteArray> = emptyMap()
        var sourceBytes: Map<Long, ByteArray> = emptyMap()
        var descriptorOpacity = Float.NaN; var dabCount = 0
        var beginCalls = 0; var addCalls = 0; var nativeEndCalls = 0; var nativeSettleCalls = 0
        var admissionCalls = 0; var finishCalls = 0; var cancelCalls = 0; var resetCalls = 0
        var nativeSwatchWrites = 0
        var noPublicationChecks = 0; var absentNativeKeys = 0; var paperChecks = 0
        var ticketSingleUseChecks = 0; var leaseCloseChecks = 0; var budgetChecks = 0
        var budgetBeforeExtraction = false; var ledgerRegistrationChecks = 0
        var allocatedBytes = 0L
        var budgetActualWorkingBytes = 0L
        fun alive() = physical != null && engine.ready && field<Long>(engine, "physicalContextEpoch") == physical &&
            !field<Boolean>(engine, "forgettingGraphicsContext")
        fun gl(label: String) {
            check(alive()) { "$label physical context retired" }
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
            check(ok) { "$label texture ownership/feedback failure" }
        }
        fun packDefaults() {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
            GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS, 0)
        }
        fun read(texture: Int, label: String, rgba8: Boolean = true): Readback {
            check(alive()) { "$label physical context retired" }
            feedback(texture != 0 && GLES30.glIsTexture(texture), "$label live texture")
            for (unit in 0..1) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
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
            packDefaults()
            val format = GLES30.GL_RGBA
            val type: Int; val bytesPerPixel: Int; val storage: Int
            if (rgba8) { storage = GLES30.GL_RGBA8; type = GLES30.GL_UNSIGNED_BYTE; bytesPerPixel = 4 }
            else {
                // Match the formats actually passed by newStrokeTile. FLOAT readback widens
                // finite half values exactly, as the engine's readMediaTile does; an arbitrary
                // implementation-selected UNSIGNED_BYTE pair would hide small float changes.
                storage = if (field<Boolean>(engine, "strokeIsRgba")) field(engine, "smudgeInternal")
                    else field(engine, "strokeInternal")
                when (storage) {
                    GLES30.GL_R8, GLES30.GL_RGBA8 -> { type = GLES30.GL_UNSIGNED_BYTE; bytesPerPixel = 4 }
                    GLES30.GL_R16F, GLES30.GL_RGBA16F, GLES30.GL_R32F, GLES30.GL_RGBA32F -> {
                        type = GLES30.GL_FLOAT; bytesPerPixel = 16
                    }
                    else -> error("$label unsupported scratch storage format $storage")
                }
            }
            val buffer = ByteBuffer.allocateDirect(n * n * bytesPerPixel).order(ByteOrder.nativeOrder())
            reads++
            GLES30.glReadPixels(0, 0, n, n, format, type, buffer)
            gl(label)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, 0, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            buffer.position(0)
            if (!rgba8 && type == GLES30.GL_FLOAT) {
                val values = buffer.asFloatBuffer()
                while (values.hasRemaining()) check(values.get().isFinite()) { "$label nonfinite scratch value" }
            }
            return Readback(storage, format, type, ByteArray(buffer.capacity()).also { buffer.get(it) })
        }
        fun compare(expected: ByteArray, actual: ByteArray, label: String, key: Long, positiveControl: Boolean = false) {
            require(expected.size == length && actual.size == length)
            var channels = 0; var pixels = 0; var largest = 0
            for (p in 0 until length step 4) {
                var changed = false
                for (c in 0..3) {
                    val delta = abs((expected[p+c].toInt() and 255) - (actual[p+c].toInt() and 255))
                    if (delta != 0) { channels++; changed = true }
                    largest = maxOf(largest, delta)
                }
                if (changed) pixels++
            }
            rows.put(JSONObject().put("phase", label).put("key", key.toString())
                .put("expectedPresent", true).put("actualPresent", true).put("positiveControl", positiveControl)
                .put("checkedChannels", length).put("differentChannels", channels)
                .put("differentPixels", pixels).put("maxByteDelta", largest))
            if (positiveControl) wrongOpacityDifferences += channels
            else {
                comparisons++; comparedChannels += length; comparedPixels += n.toLong() * n
                differentChannels += channels; differentPixels += pixels; maxDelta = maxOf(maxDelta, largest)
            }
        }
        fun compareMaps(expected: Map<Long, ByteArray>, actual: Map<Long, ByteArray>, label: String) {
            val before = differentChannels
            val missing = keyDifferences
            for (key in (expected.keys + actual.keys).sorted()) {
                val a = expected[key]; val b = actual[key]
                if (a == null || b == null) {
                    keyDifferences++
                    rows.put(JSONObject().put("phase", label).put("key", key.toString())
                        .put("expectedPresent", a != null).put("actualPresent", b != null))
                } else compare(a, b, label, key)
            }
            check(differentChannels == before && keyDifferences == missing) { "$label full tile/key comparison differs" }
        }
        fun nativeOwners(): Map<String, Map<Long, Int>> = field<LinkedHashMap<String, Any>>(engine, "localLayers")
            .mapValues { (_, owner) -> field<HashMap<Long, Int>>(owner, "tiles").toMap() }
        fun scratch() = field<HashMap<Long, Int>>(engine, "strokeTiles").toMap()
        fun pool() = field<ArrayDeque<Int>>(engine, "freeLayerTex").toList()
        fun ledger() = field<RecordedWorkingTileLedger>(engine, "recordedWorkingTiles").snapshot(checkNotNull(physical))
        fun noPublication(cursor: Any, owners: Map<String, Map<Long, Int>>, label: String) {
            check(engine.undo.snapshotCursor() === cursor && nativeOwners() == owners &&
                !engine.hasJointPaintPublication && field<Any?>(engine, "publishedPaint") == null &&
                field<Any?>(engine, "localRecordedScene") == null && !engine.undo.canUndo && !engine.undo.canRedo) {
                "$label unexpectedly published native pixels, recorded state or history"
            }
            noPublicationChecks++
        }
        fun ownership(label: String) {
            val entries = JSONArray()
            ledger().forEach { (name, bytes) -> entries.put(JSONObject().put("texture", name).put("bytes", bytes)) }
            ownershipRows.put(JSONObject().put("phase", label).put("staged", entries)
                .put("scratchKeys", JSONArray(scratch().keys.sorted().map { it.toString() }))
                .put("rgba8Pool", JSONArray(pool())).put("undoDepth", engine.undo.undoDepth))
        }
        try {
            physical = field(engine, "physicalContextEpoch")
            gl("entry")
            require(engine.ready && !engine.strokeInProgress && !engine.hasJointPaintPublication &&
                field<Any?>(engine, "localRecordedScene") == null && engine.boardDocument == null && engine.tileBoardId == null)
            require(!engine.undo.canUndo && !engine.undo.canRedo && nativeOwners().values.all { it.isEmpty() } && ledger().isEmpty())
            require(preset.blend == "normal" && BrushValidate.validate(preset).isEmpty() && preset.line == null &&
                (preset.engine == "stamp" && preset.tip.source == "procedural" || preset.engine == ENGINE_WATERCOLOUR))
            require(input.brushId == preset.id && input.samples.size == 24 && input.samples.all {
                it.isPlaceable && !it.predicted && abs(it.x) <= 4096f && abs(it.y) <= 4096f
            } && input.widthScale in 0.1f..2f && input.screenPerDoc in 0.125f..12f &&
                (preset.engine == ENGINE_WATERCOLOUR || preset.size.base in 1f..64f)) { "Fixture needs its small 24-sample recipe" }
            require(input.samples.zipWithNext().sumOf { (a, b) ->
                kotlin.math.hypot((b.x-a.x).toDouble(), (b.y-a.y).toDouble()) } <= 2048.0)
            val paper = checkNotNull(field<Any?>(engine, "resolvedPaper"))
            val loaded = field<Any?>(engine, "loadedPaper")
            val paperSetting = engine.documentPaper
            val resources = listOf(field<Int?>(engine, "lookTexture"), field<Int?>(engine, "surfaceTexture"), field<Int?>(engine, "fluidTexture"))
            require(engine.paperWarnings.isEmpty())
            fun samePaper() {
                check(field<Any?>(engine, "resolvedPaper") === paper && field<Any?>(engine, "loadedPaper") === loaded &&
                    engine.documentPaper == paperSetting && resources == listOf(field<Int?>(engine, "lookTexture"),
                        field<Int?>(engine, "surfaceTexture"), field<Int?>(engine, "fluidTexture")) && engine.paperWarnings.isEmpty())
                paperChecks++
            }
            // Raw input constructs test dabs only. No captured-domain metadata is published or applied.
            val recipe = CapturedStrokeRules.snapshot(input.copy(capturedPhysicalSource = null))
            val replay = InkReplay.replay(recipe, preset)
            descriptorOpacity = replay.strokeOpacity; dabCount = replay.dabs.size
            require(dabCount in 1..6000 && descriptorOpacity > 0f && descriptorOpacity < 1f) {
                "Opacity-sensitive fixture needs finite nontrivial opacity and bounded nonempty test dabs"
            }
            val tip = TipShape(aspect = preset.tip.aspect, corner = preset.tip.corner, taper = preset.tip.taper,
                hardness = replay.strokeHardness, minPx = preset.tip.minPx)
            fun begin() {
                samePaper(); beginCalls++
                engine.beginStroke(layerId, recipe.colorArgb, replay.strokeOpacity,
                    if (preset.accumulate == "buildup") Accumulate.BUILD_UP else Accumulate.WASH,
                    StrokeBlend.NORMAL, tip, replay.strokeGrain,
                    watercolour = if (preset.engine == ENGINE_WATERCOLOUR) preset.watercolourRuntime() else null,
                    watercolourSeed = recipe.seed)
            }
            fun add() { addCalls++; engine.addDabs(replay.dabs); gl("actual live dabs") }
            fun nativeRead(owner: String, key: Long): ByteArray {
                packDefaults()
                return checkNotNull(engine.readTile(owner, key)).also { gl("native public readTile") }
            }
            val names = IntArray(1)
            GLES30.glGenFramebuffers(1, names, 0); readFbo = names[0]
            check(readFbo != 0); gl("diagnostic framebuffer")
            phase = "settled native baseline"
            engine.addLayer(layerId)
            begin(); add(); nativeEndCalls++; engine.endStroke()
            nativeSettleCalls++; engine.settleWatercolourFade(); gl("fully settled native endStroke")
            check(!engine.strokeInProgress && !field<Boolean>(engine, "wcFading") && engine.undo.undoDepth == 1)
            baselineKeys = engine.tileKeys(layerId).toSet()
            require(baselineKeys.isNotEmpty() && baselineKeys.size <= 16) { "Fixture needs a small actual native footprint" }
            baselineBytes = baselineKeys.associateWith { nativeRead(layerId, it) }
            val nativeTextures = nativeOwners().getValue(layerId)
            compareMaps(baselineBytes, nativeTextures.mapValues { read(it.value, "native external FBO").bytes }, "native-public-vs-external")
            for ((key, bytes) in baselineBytes) {
                val wrong = ByteArray(length) { ((bytes[it].toInt() and 255) * descriptorOpacity).roundToInt().coerceIn(0, 255).toByte() }
                compare(bytes, wrong, "wrong-double-opacity-control", key, positiveControl = true)
            }
            check(wrongOpacityDifferences > 0) { "Wrong-opacity positive control was insensitive" }

            phase = "actual live source extraction"
            resetCalls++; engine.resetDocument(); engine.addLayer(layerId); samePaper()
            val cursor = engine.undo.snapshotCursor(); val owners = nativeOwners()
            begin(); admissionCalls++
            val admitted = engine.admitLiveRecordedSource(layerId).also { ticket = it }
            check(admitted.capturedDomains == null && admitted.sharedDocumentCelId == null &&
                admitted.domains.keys == setOf<String?>(null) && admitted.domains.getValue(null).domain.window == null &&
                admitted.domains.getValue(null).domain.excluded.isEmpty())
            add()
            val scratchBefore = scratch()
            check(scratchBefore.isNotEmpty() && scratchBefore.keys == baselineKeys && ledger().isEmpty())
            ownership("before-finish")
            finishCalls++
            val source = admitted.finish().also { lease = it }
            gl("live source finish")
            check(engine.strokeInProgress && scratch() == scratchBefore && !field<Boolean>(engine, "wcFading"))
            check(preset.engine != ENGINE_WATERCOLOUR || field<Boolean>(engine, "wcPrepared"))
            noPublication(cursor, owners, "live finish")
            for (key in baselineKeys) { check(engine.readTile(layerId, key) == null); absentNativeKeys++ }
            sourceKeys = source.unit.tiles.keys
            allocatedBytes = source.allocatedBytes
            check(source.unit.op == "normal" && source.unit.physicalEpoch == physical &&
                source.unit.tiles.values.distinct().size == source.unit.tiles.size && allocatedBytes == sourceKeys.size * tileBytes)
            feedback(source.unit.tiles.values.none { it in scratchBefore.values || it in pool() }, "canonical outputs separate from scratch/pool")
            val tags = ledger()
            check(tags.keys == source.unit.tiles.values.toSet() && tags.values.all { it == tileBytes })
            ledgerRegistrationChecks++
            ownership("after-finish")
            sourceBytes = source.unit.tiles.mapValues { read(it.value, "actual canonical source").bytes }
            compareMaps(baselineBytes, sourceBytes, "settled-native-vs-live-source")
            for (tile in sourceBytes.values) {
                var ink = 0
                for (p in tile.indices step 4) {
                    val alpha = tile[p+3].toInt() and 255
                    if (alpha != 0) { ink++; inkPixels++ }
                    if ((0..2).any { (tile[p+it].toInt() and 255) > alpha }) badPremultiplied++
                }
                if (ink == 0) transparentKeys++
            }
            check(inkPixels > 0 && badPremultiplied == 0L)
            var twice = false
            try { finishCalls++; admitted.finish() } catch (_: IllegalStateException) { twice = true }
            check(twice); ticketSingleUseChecks++
            admitted.close(); admitted.close(); noPublication(cursor, owners, "spent ticket close")
            check(scratch() == scratchBefore && ledger() == tags)
            cancelCalls++; engine.cancelStroke()
            check(!engine.strokeInProgress && scratch().isEmpty())
            noPublication(cursor, owners, "source contact cancel")

            phase = "occupied zero-budget refusal"
            engine.addLayer(sibling)
            val swatch = ByteArray(length).also { bytes ->
                for (p in bytes.indices step 4) { bytes[p] = 11; bytes[p+1] = 23; bytes[p+2] = 37; bytes[p+3] = 61 }
            }
            nativeSwatchWrites++; engine.writeTile(sibling, siblingKey, swatch); gl("real native sibling swatch")
            begin(); admissionCalls++
            val refused = engine.admitLiveRecordedSource(layerId, 0L).also { budgetTicket = it }
            add()
            val occupiedScratch = scratch()
            val occupiedOwners = nativeOwners(); val occupiedCursor = engine.undo.snapshotCursor()
            val occupiedTags = ledger(); val occupiedPool = pool()
            val occupiedInventory = engine.recordedWorkingTileBytes()
            budgetActualWorkingBytes = occupiedInventory.values.fold(0L) { total, bytes -> Math.addExact(total, bytes) }
            val scratchBytes = occupiedScratch.mapValues { read(it.value, "budget scratch before", rgba8 = false) }
            val preparedBefore = field<Boolean>(engine, "wcPrepared")
            val lineCalls = engine.recordedLinePreparationCalls; val compositionCalls = engine.recordedCompositionCalls
            check(!preparedBefore && occupiedTags == tags)
            ownership("before-budget-refusal")
            var refusedForBudget = false
            try { finishCalls++; refused.finish() } catch (error: IllegalArgumentException) {
                refusedForBudget = error.message.orEmpty().contains("exceeds budget")
            }
            check(refusedForBudget && engine.strokeInProgress && scratch() == occupiedScratch &&
                ledger() == occupiedTags && pool() == occupiedPool && field<Boolean>(engine, "wcPrepared") == preparedBefore &&
                engine.recordedWorkingTileBytes() == occupiedInventory &&
                engine.recordedLinePreparationCalls == lineCalls && engine.recordedCompositionCalls == compositionCalls)
            noPublication(occupiedCursor, occupiedOwners, "budget refusal")
            for ((key, before) in scratchBytes) {
                val after = read(occupiedScratch.getValue(key), "budget scratch after", rgba8 = false)
                val delta = if (before.bytes.size == after.bytes.size) before.bytes.indices.count { before.bytes[it] != after.bytes[it] }
                    else maxOf(before.bytes.size, after.bytes.size)
                scratchDifferences += delta
                rawRows.put(JSONObject().put("key", key.toString()).put("format", before.format).put("type", before.type)
                    .put("storageFormat", before.storage).put("comparison", "Exact full raw readback bytes; finite half values widened losslessly to FLOAT")
                    .put("bytes", before.bytes.size).put("differentBytes", delta)
                    .put("readPairUnchanged", before.storage == after.storage && before.format == after.format && before.type == after.type))
                check(before.storage == after.storage && before.format == after.format && before.type == after.type)
            }
            check(scratchDifferences == 0L)
            compareMaps(sourceBytes, source.unit.tiles.mapValues { read(it.value, "budget retained source").bytes }, "budget-retained-canonical-source")
            compare(swatch, nativeRead(sibling, siblingKey), "budget-native-sibling", siblingKey)
            check(differentChannels == 0L)
            var spent = false
            try { finishCalls++; refused.finish() } catch (_: IllegalStateException) { spent = true }
            check(spent); ticketSingleUseChecks++
            refused.close(); refused.close()
            budgetChecks++; budgetBeforeExtraction = true
            ownership("after-budget-refusal")

            phase = "lease retirement and explicit cancel"
            val outputNames = source.unit.tiles.values.toSet()
            source.close()
            val once = pool()
            check(outputNames.all { texture -> once.count { it == texture } == 1 } && ledger().keys.none { it in outputNames })
            feedback(outputNames.all { GLES30.glIsTexture(it) }, "recycled canonical outputs remain live pool allocations")
            source.close()
            check(pool() == once && ledger().keys.none { it in outputNames })
            leaseCloseChecks++
            check(scratch() == occupiedScratch); noPublication(occupiedCursor, occupiedOwners, "lease close")
            cancelCalls++; engine.cancelStroke()
            check(!engine.strokeInProgress && scratch().isEmpty())
            noPublication(occupiedCursor, occupiedOwners, "budget contact cancel")
            compare(swatch, nativeRead(sibling, siblingKey), "cancel-native-sibling", siblingKey)
            check(differentChannels == 0L && engine.tileKeys(layerId).isEmpty() && ledger().isEmpty())
            ownership("after-source-close-and-cancel")
            samePaper(); gl("completed live source fixture")
        } catch (error: Exception) {
            failure = "$phase ${error.javaClass.simpleName}: ${error.message}"
        } finally {
            ticket?.close(); budgetTicket?.close()
            if (!alive()) {
                failure = listOfNotNull(failure, "Cleanup abandoned old-context fixture names").joinToString("; ")
            } else try {
                lease?.close()
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                for (unit in 0..1) {
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
                }
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                if (readFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(readFbo), 0)
                resetCalls++; engine.resetDocument()
                check(!engine.strokeInProgress && engine.layerIds().isEmpty() && !engine.undo.canUndo && !engine.undo.canRedo && ledger().isEmpty())
                gl("fixture reset cleanup")
                cleanupComplete = true
            } catch (error: Exception) {
                failure = listOfNotNull(failure, "Cleanup ${error.javaClass.simpleName}: ${error.message}").joinToString("; ")
            }
        }
        return report.put("passed", failure == null && cleanupComplete && errors == 0 && badFbos == 0 && badFeedback == 0 &&
            comparisons > 0 && differentChannels == 0L && keyDifferences == 0 && inkPixels > 0 && badPremultiplied == 0L &&
            wrongOpacityDifferences > 0 && scratchDifferences == 0L && budgetChecks == 1 && budgetBeforeExtraction &&
            ticketSingleUseChecks == 2 && leaseCloseChecks == 1 && ledgerRegistrationChecks == 1 && absentNativeKeys > 0)
            .put("failure", failure ?: JSONObject.NULL).put("phase", phase).put("cleanupComplete", cleanupComplete)
            .put("physicalEpoch", physical ?: JSONObject.NULL)
            .put("engine", preset.engine).put("accumulate", preset.accumulate)
            .put("descriptorOpacity", if (descriptorOpacity.isFinite()) descriptorOpacity.toDouble() else JSONObject.NULL)
            .put("sampleCount", input.samples.size).put("dabCount", dabCount).put("tileSize", n)
            .put("baselineKeys", JSONArray(baselineKeys.sorted().map { it.toString() }))
            .put("sourceKeys", JSONArray(sourceKeys.sorted().map { it.toString() })).put("transparentSourceKeys", transparentKeys)
            .put("comparisonCount", comparisons).put("checkedChannels", comparedChannels).put("checkedPixels", comparedPixels)
            .put("differentChannels", differentChannels).put("differentPixels", differentPixels).put("maxByteDelta", maxDelta)
            .put("keyDifferences", keyDifferences).put("wrongOpacityDifferentChannels", wrongOpacityDifferences)
            .put("sourceNonzeroAlphaPixels", inkPixels).put("sourceInvalidPremultipliedPixels", badPremultiplied)
            .put("allocatedRgba8Bytes", allocatedBytes).put("ledgerRegistrationChecks", ledgerRegistrationChecks)
            .put("ticketSingleUseChecks", ticketSingleUseChecks).put("leaseCloseChecks", leaseCloseChecks)
            .put("noPublicationChecks", noPublicationChecks).put("absentNativeSourceKeyChecks", absentNativeKeys).put("samePaperChecks", paperChecks)
            .put("budgetRefusalChecks", budgetChecks).put("budgetRefusalBeforeExtraction", budgetBeforeExtraction)
            .put("budgetActualMutableWorkingBytes", budgetActualWorkingBytes)
            .put("budgetScratchDifferentBytes", scratchDifferences).put("budgetScratchReadbacks", rawRows)
            .put("nativeBeginCalls", beginCalls).put("addDabsCalls", addCalls).put("nativeEndCalls", nativeEndCalls)
            .put("nativeSettleCalls", nativeSettleCalls).put("ticketAdmissionCalls", admissionCalls).put("ticketFinishAttempts", finishCalls)
            .put("nativeSwatchWrites", nativeSwatchWrites).put("cancelCalls", cancelCalls).put("resetCalls", resetCalls)
            .put("comparisons", rows).put("ownershipChecks", ownershipRows)
            .put("readbackCalls", reads).put("glErrorChecks", glChecks).put("observedGlErrors", errors)
            .put("framebufferChecks", fboChecks).put("incompleteFramebuffers", badFbos)
            .put("feedbackChecks", feedbackChecks).put("feedbackFailures", badFeedback)
    }
}
