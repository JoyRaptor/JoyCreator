package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.render.Blend
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import java.nio.ByteBuffer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Arithmetic/replay-adapter probe ONLY. Invoke on the engine's current GL thread in a fresh,
 * unsaved debug bench, with no stroke/playback/registered recorded scene. Caller supplies its
 * existing paint layer and a supported frozen brush/raw recording. Never save this bench.
 *
 * This deliberately uses the engine's normal predicates and preparation, once. Preparation may
 * produce multiple source tiles; only the FIRST actual key is read/composed, all are released.
 * It does not prove live-view publication, Select/edit transactions, save, or physical brush quality.
 * Engine GL state is not preserved: the fresh bench must reset its ordinary draw state afterwards.
 * Check counts below describe external checkpoints; engine-internal draw/FBO/error/feedback
 * guards remain active but are not observable/countable through this API.
 */
internal object RecordedReplayProbe {
    internal data class Diff(val differentChannels: Int, val differentPixels: Int,
                             val maxByteDelta: Int, val channelsOverOne: Int)
    internal data class Result(
        val passed: Boolean,
        val failure: String?,
        val tileKey: Long?,
        val tileSize: Int,
        val preparedTileCount: Int,
        val sourceNonzeroAlphaPixels: Int,
        val sourceInvalidPremultipliedPixels: Int,
        val identity: Diff?,
        val ordered: Diff?,
        val wrongOrder: Diff?,
        val wrongOrderVersusCorrectCpu: Diff?,
        val preparationCalls: Int,
        val compositionCalls: Int,
        val readbackCalls: Int,
        val glErrorChecks: Int,
        val observedGlErrors: Int,
        val framebufferChecks: Int,
        val incompleteFramebuffers: Int,
        val feedbackChecks: Int,
        val feedbackFailures: Int,
        val cleanupComplete: Boolean,
        val mixedOperations: List<String>,
        val comparison: String = "Identity and ordered exact RGBA8 equality; CPU floats quantized once",
    )

    private data class Step(val texture: Int, val op: String, val bytes: ByteArray)

    internal fun json(result: Result): JSONObject {
        fun detail(diff: Diff?): Any = diff?.let { JSONObject()
            .put("differentChannels", it.differentChannels).put("differentPixels", it.differentPixels)
            .put("maxByteDelta", it.maxByteDelta).put("channelsOverOne", it.channelsOverOne) } ?: JSONObject.NULL
        return JSONObject().put("passed", result.passed).put("failure", result.failure ?: JSONObject.NULL)
            .put("tileKey", result.tileKey ?: JSONObject.NULL).put("tileSize", result.tileSize)
            .put("preparedTileCount", result.preparedTileCount).put("sourceNonzeroAlphaPixels", result.sourceNonzeroAlphaPixels)
            .put("sourceInvalidPremultipliedPixels", result.sourceInvalidPremultipliedPixels)
            .put("identity", detail(result.identity)).put("ordered", detail(result.ordered))
            .put("wrongOrder", detail(result.wrongOrder)).put("wrongOrderVersusCorrectCpu", detail(result.wrongOrderVersusCorrectCpu))
            .put("preparationCalls", result.preparationCalls).put("compositionCalls", result.compositionCalls)
            .put("readbackCalls", result.readbackCalls).put("glErrorChecks", result.glErrorChecks)
            .put("observedGlErrors", result.observedGlErrors).put("framebufferChecks", result.framebufferChecks)
            .put("incompleteFramebuffers", result.incompleteFramebuffers).put("feedbackChecks", result.feedbackChecks)
            .put("feedbackFailures", result.feedbackFailures).put("cleanupComplete", result.cleanupComplete)
            .put("mixedOperations", JSONArray(result.mixedOperations)).put("comparison", result.comparison)
    }

    /** Result is fail-closed, including unsupported input, empty ink and cleanup failure. */
    internal fun run(engine: GlPaintEngine, layerId: String,
                     preset: BrushPreset, record: StrokeRecord): Result {
        val n = Tiles.SIZE
        val length = n * n * 4
        var key: Long? = null
        var preparedTiles = 0
        var inkPixels = 0
        var invalidPixels = 0
        var identity: Diff? = null
        var ordered: Diff? = null
        var wrong: Diff? = null
        var wrongCpu: Diff? = null
        var prepCalls = 0
        var composeCalls = 0
        var reads = 0
        var errorsChecked = 0
        var errors = 0
        var fboChecks = 0
        var badFbos = 0
        var feedbackChecks = 0
        var feedbackFailures = 0
        var failure: String? = null
        var cleanupComplete = false
        var sourceUnit: GlPaintEngine.RecordedLineUnit? = null
        val looks = ArrayList<Int>(3)
        var baseTexture = 0
        var readFbo = 0
        val operations = listOf("normal", "normal", "erase", "behind", "multiply", "screen")

        fun checkErrors(label: String) {
            errorsChecked++
            // Drain all flags so this checkpoint cannot leave an error for a later check.
            var first = GLES30.GL_NO_ERROR
            var code = GLES30.glGetError()
            while (code != GLES30.GL_NO_ERROR) {
                if (first == GLES30.GL_NO_ERROR) first = code
                errors++
                code = GLES30.glGetError()
            }
            check(first == GLES30.GL_NO_ERROR) { "$label GL error 0x${first.toString(16)}" }
        }
        fun feedback(ok: Boolean, label: String) {
            feedbackChecks++
            if (!ok) feedbackFailures++
            check(ok) { "$label texture feedback/ownership violation" }
        }
        fun read(texture: Int, label: String): ByteArray {
            // Readback draws nothing. Detach all texture sampling bindings the composer used;
            // this attachment cannot accidentally remain a sampler for a later draw.
            for (slot in 0..1) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + slot)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            }
            feedback(texture != 0, "$label readback")
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, readFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0)
            fboChecks++
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) badFbos++
            check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "$label incomplete framebuffer 0x${status.toString(16)}" }
            GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
            GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS, 0)
            val bytes = ByteBuffer.allocateDirect(length)
            reads++
            GLES30.glReadPixels(0, 0, n, n, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
            checkErrors(label)
            bytes.position(0)
            return ByteArray(length).also { bytes.get(it) }
        }
        fun compose(steps: List<Step>, label: String): Int {
            composeCalls++
            val output = checkNotNull(engine.composeRecordedTile(steps.mapIndexed { i, s ->
                GlPaintEngine.OrderedRecordedUnit(i.toLong(), s.texture, s.op)
            })) { "$label returned no look" }
            // Register ownership before any check can fail.
            looks.add(output)
            feedback(output != 0 && output != baseTexture &&
                sourceUnit?.tiles?.values?.contains(output) != true &&
                looks.dropLast(1).none { it == output }, label)
            checkErrors(label)
            return output
        }
        try {
            checkErrors("entry") // Existing context errors fail the probe, never disappear silently.
            val names = IntArray(1)
            GLES30.glGenFramebuffers(1, names, 0)
            readFbo = names[0]
            check(readFbo != 0) { "Cannot allocate probe read framebuffer" }
            checkErrors("read framebuffer allocation")
            prepCalls++
            val prepared = engine.prepareRecordedLine(layerId, record, preset)
            sourceUnit = prepared
            preparedTiles = prepared.tiles.size
            checkErrors("source preparation")
            val first = prepared.tiles.entries.firstOrNull()
                ?: error("Recording produced no source tiles")
            key = first.key
            val sourceTexture = first.value
            val source = read(sourceTexture, "source RGBA8")
            for (p in 0 until length step 4) {
                val a = source[p + 3].toInt() and 255
                if (a != 0) inkPixels++
                if ((0..2).any { (source[p + it].toInt() and 255) > a }) invalidPixels++
            }
            check(inkPixels > 0) { "First actual source tile has no nonzero alpha; inconclusive" }
            check(invalidPixels == 0) { "Source tile is not premultiplied RGBA8" }
            val actual = Step(sourceTexture, "normal", source)
            identity = diff(source, read(compose(listOf(actual), "normal identity"), "identity RGBA8"))
            check(identity!!.differentChannels == 0) { "Normal source-unit identity differs" }

            // Tile-sized storage with a small 8x8 repeated swatch pattern: alternate opaque and
            // low-alpha premultiplied coloured backdrops under every possible location of ink.
            val base = ByteArray(length)
            for (y in 0 until n) for (x in 0 until n) {
                val p = (y * n + x) * 4
                val rgba = if (((x / 4 + y / 4) and 1) == 0)
                    intArrayOf(43, 157, 211, 255) else intArrayOf(13, 7, 29, 47)
                for (c in 0..3) base[p + c] = rgba[c].toByte()
            }
            GLES30.glGenTextures(1, names, 0)
            baseTexture = names[0]
            check(baseTexture != 0) { "Cannot allocate probe base texture" }
            feedback(baseTexture != sourceTexture && !prepared.tiles.values.contains(baseTexture), "base upload")
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, baseTexture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            val upload = ByteBuffer.allocateDirect(length).put(base)
            upload.position(0)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, n, n, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, upload)
            checkErrors("base upload")
            val steps = listOf(Step(baseTexture, "normal", base)) +
                operations.drop(1).map { Step(sourceTexture, it, source) }
            val expected = reference(steps, length)
            val correctGpu = read(compose(steps, "mixed ordered"), "mixed ordered RGBA8")
            ordered = diff(expected, correctGpu)
            // Exact final RGBA8 equality is required; retain all rounding differences as failures.
            check(ordered!!.differentChannels == 0) { "Ordered composition differs from CPU float reference" }
            // Same sources/operators, deliberately moved base from first to last, freshly sequenced
            // to keep the engine's strict sequence-order predicate active (never pass descending IDs).
            val reordered = steps.drop(1) + steps.first()
            val wrongGpu = read(compose(reordered, "negative order"), "negative order RGBA8")
            wrong = diff(correctGpu, wrongGpu)
            wrongCpu = diff(expected, wrongGpu)
            check(wrong!!.differentPixels > 0 && wrongCpu!!.channelsOverOne > 0) {
                "Wrong-order control did not differ beyond rounding; inconclusive"
            }
        } catch (e: Exception) {
            failure = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            // Always detach readback before recycling engine-owned units/looks.
            try {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                for (slot in 0..1) {
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + slot)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
                }
                if (readFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(readFbo), 0)
                if (baseTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(baseTexture), 0)
                // Use the actual owner's release API, never delete pooled engine textures directly.
                sourceUnit?.let(engine::discardRecordedLine)
                if (looks.isNotEmpty()) engine.discardRecordedLine(GlPaintEngine.RecordedLineUnit(
                    "normal", looks.distinct().filter { it != baseTexture && sourceUnit?.tiles?.values?.contains(it) != true }.mapIndexed { i, texture -> i.toLong() to texture }.toMap()))
                checkErrors("cleanup")
                cleanupComplete = true
            } catch (e: Exception) {
                failure = listOfNotNull(failure, "Cleanup ${e.javaClass.simpleName}: ${e.message}").joinToString("; ")
            }
        }
        return Result(failure == null && cleanupComplete, failure, key, n, preparedTiles,
            inkPixels, invalidPixels, identity, ordered, wrong, wrongCpu, prepCalls, composeCalls,
            reads, errorsChecked, errors, fboChecks, badFbos, feedbackChecks, feedbackFailures,
            cleanupComplete, operations)
    }

    private fun reference(steps: List<Step>, length: Int): ByteArray {
        val result = ByteArray(length)
        val source = FloatArray(4)
        var destination = FloatArray(4)
        var out = FloatArray(4)
        for (p in 0 until length step 4) {
            destination.fill(0f)
            for (step in steps) {
                for (c in 0..3) source[c] = (step.bytes[p + c].toInt() and 255) / 255f
                when (step.op) {
                    // Behind is destination-over: reuse the SAME CPU reference by swapping inputs.
                    "behind" -> Blend.apply(BlendMode.NORMAL, destination, source, out)
                    "normal" -> Blend.apply(BlendMode.NORMAL, source, destination, out)
                    "erase" -> Blend.apply(BlendMode.ERASE_BELOW, source, destination, out)
                    "multiply" -> Blend.apply(BlendMode.MULTIPLY, source, destination, out)
                    "screen" -> Blend.apply(BlendMode.SCREEN, source, destination, out)
                    else -> error("Unknown probe operator")
                }
                val swap = destination; destination = out; out = swap
            }
            for (c in 0..3) result[p + c] =
                (destination[c].coerceIn(0f, 1f) * 255f).roundToInt().toByte()
        }
        return result
    }

    private fun diff(a: ByteArray, b: ByteArray): Diff {
        require(a.size == b.size && a.size % 4 == 0)
        var channels = 0
        var pixels = 0
        var max = 0
        var overOne = 0
        for (p in a.indices step 4) {
            var different = false
            for (c in 0..3) {
                val delta = abs((a[p + c].toInt() and 255) - (b[p + c].toInt() and 255))
                if (delta != 0) { channels++; different = true }
                if (delta > 1) overOne++
                max = maxOf(max, delta)
            }
            if (different) pixels++
        }
        return Diff(channels, pixels, max, overOne)
    }
}
