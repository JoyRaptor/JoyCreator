package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.lines.LineEdit
import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.watercolourRuntime
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.select.Homography
import cc.joycreator.joybrush.core.select.LineTransform
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.CapturedStrokeRules
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import java.nio.ByteBuffer
import org.json.JSONArray
import org.json.JSONObject

/** Standalone disposable-engine diagnostic. Authored, not wired into a View or bench case. */
internal object WatercolourOrdinaryProbe {
    private const val TILE = 256
    private const val BYTES = TILE * TILE * 4
    private const val MAX_KEYS = 128
    private const val DX = 37
    private const val DY = -19

    /** Caller owns/reclaims a NEW initialized engine; never pass the drawing's engine. */
    fun run(engine: GlPaintEngine, layerId: String, preset: BrushPreset, input: StrokeRecord): JSONObject {
        val report = JSONObject().put("probe", "watercolourOrdinary")
            .put("scope", "Disposable ordinary NativeLines Add/Replace, integer wash translation and Undo/redo; no selected lift, cache, physical fade, View, save/recovery or appearance acceptance")
        val rows = JSONArray()
        val keys = linkedSetOf<Long>()
        var phase = "admission"
        var fbo = 0
        var initialDepth = -1
        val plane = LinePlane(layerId, null)
        val address = LineAddress(layerId, null, input.id)

        fun include(extra: Collection<Long>) {
            require(extra.size <= MAX_KEYS && (keys + extra).size <= MAX_KEYS) { "Probe tile budget exceeded" }
            keys.addAll(extra)
        }
        fun checkGl(label: String) {
            var first = GLES30.GL_NO_ERROR
            var code = GLES30.glGetError()
            while (code != GLES30.GL_NO_ERROR) {
                if (first == GLES30.GL_NO_ERROR) first = code
                code = GLES30.glGetError()
            }
            check(first == GLES30.GL_NO_ERROR) { "$label GL error 0x${first.toString(16)}" }
        }
        fun readTexture(texture: Int): ByteArray {
            check(texture != 0 && GLES30.glIsTexture(texture)) { "Missing source texture" }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
            GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
            GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS, 0)
            val buffer = ByteBuffer.allocateDirect(BYTES)
            GLES30.glReadPixels(0, 0, TILE, TILE, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            checkGl("source read")
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, 0, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            buffer.position(0)
            return ByteArray(BYTES).also { buffer.get(it) }
        }
        fun canonical(record: StrokeRecord, readPixels: Boolean = true): Map<Long, ByteArray> {
            include(engine.nativeLines.estimate(record, preset))
            val unit = engine.prepareRecordedLine(layerId, record, preset)
            try {
                include(unit.tiles.keys)
                return if (readPixels) unit.tiles.mapValues { readTexture(it.value) } else emptyMap()
            } finally { engine.discardRecordedLine(unit) }
        }
        // Independent integer pixel transport: never replay the moved record to build this oracle.
        fun translate(source: Map<Long, ByteArray>): Map<Long, ByteArray> {
            val result = linkedMapOf<Long, ByteArray>()
            for ((key, bytes) in source) for (y in 0 until TILE) for (x in 0 until TILE) {
                val wx = Tiles.tx(key).toLong() * TILE + x + DX
                val wy = Tiles.ty(key).toLong() * TILE + y + DY
                val tx = Math.floorDiv(wx, TILE.toLong())
                val ty = Math.floorDiv(wy, TILE.toLong())
                require(tx in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
                    ty in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                val targetKey = Tiles.key(tx.toInt(), ty.toInt())
                if (targetKey !in result) {
                    include(listOf(targetKey))
                    result[targetKey] = ByteArray(BYTES)
                }
                val to = (Math.floorMod(wy, TILE.toLong()).toInt() * TILE +
                    Math.floorMod(wx, TILE.toLong()).toInt()) * 4
                val from = (y * TILE + x) * 4
                bytes.copyInto(result.getValue(targetKey), to, from, from + 4)
            }
            return result
        }
        fun compare(expected: Map<Long, ByteArray>, label: String) {
            include(expected.keys)
            include(engine.tileKeys(layerId)) // Unexpected current tiles must not escape comparison.
            include(engine.nativeLines.footprint(plane, input.id))
            for (key in keys.sorted()) {
                val actual = engine.readTile(layerId, key)
                val reference = expected[key]
                val equal = if (actual == null) reference == null || Tiles.isBlank(reference)
                    else if (reference == null) Tiles.isBlank(actual) else actual.contentEquals(reference)
                check(equal) { "$label exact RGBA8 mismatch at tile $key" }
            }
            rows.put(JSONObject().put("phase", label).put("comparedTiles", keys.size)
                .put("transparentTilesIncluded", true).put("exactRgba8", true))
        }
        fun recordIs(expected: StrokeRecord) {
            check(CapturedStrokeRules.sameRecord(engine.nativeLines.record(plane, input.id), expected)) {
                "$phase installed recipe/raw sensor/seed/frame/sequence mismatch"
            }
            check(engine.nativeLines.brush(expected.brushId) == preset) { "Frozen brush changed" }
        }
        fun replace(record: StrokeRecord, depth: Int, expected: Map<Long, ByteArray>) {
            engine.editLines(LineEdit.Replace(listOf(AddressedStroke(address, record))))
            check(engine.undo.undoDepth == depth) { "$phase must create exactly one Undo step" }
            recordIs(record)
            compare(expected, phase)
        }

        try {
            require(engine.ready && layerId in engine.layerIds() && engine.boardDocument == null)
            require(!engine.strokeInProgress && !engine.watercolourAnimating && !engine.lifting)
            require(!engine.undo.canUndo && !engine.undo.canRedo)
            require(engine.tileKeys(layerId).isEmpty() && !engine.nativeLines.hasItems(plane)) {
                "Needs a fresh disposable continuous paint plane"
            }
            require(preset.engine == "watercolour" && preset.blend in setOf("normal", "multiply"))
            require(input.brushId == preset.id && input.capturedPhysicalSource == null && !input.locked)
            require(input.samples.size in 1..2048 && input.samples.all {
                it.x.isFinite() && it.y.isFinite() && kotlin.math.abs(it.x) <= 4096f && kotlin.math.abs(it.y) <= 4096f
            }) { "Probe requires bounded raw sample positions" }
            val options = preset.watercolourRuntime()
            require(options.size in 1.0..300.0 && options.paperCut == 0.0 && options.grain == 0.0) {
                "Translation fixture must disable world-fixed paper cutting/fine grain; do not mutate the supplied frozen preset"
            }
            val original = CapturedStrokeRules.snapshot(input)
            val moved = checkNotNull(LineTransform.apply(original,
                Homography.translate(DX.toDouble(), DY.toDouble()), LineTransform.Weight.KEEP))
            val tinted = moved.copy(colorArgb = if (original.colorArgb == 0xFFB44D73.toInt())
                0xFF3C9A72.toInt() else 0xFFB44D73.toInt())

            phase = "read_fbo"
            val names = IntArray(1)
            GLES30.glGenFramebuffers(1, names, 0)
            fbo = names[0]
            check(fbo != 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            check(GLES30.glIsFramebuffer(fbo))
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            checkGl("create framebuffer")

            phase = "ordinary_controls"
            val originalPixels = canonical(original)
            val movedPixels = translate(originalPixels)
            // Discover the moved replay's actual/halo keys before Add, without using its pixels
            // as the translation reference. Unexpected installed keys are still checked below.
            canonical(moved, readPixels = false)
            val tintPixels = canonical(tinted)
            check(originalPixels.values.any { bytes -> bytes.indices.any { it % 4 == 3 && bytes[it] != 0.toByte() } }) {
                "Original source has no nonzero-alpha pixels"
            }
            // Freeze the full original/moved/tint key union before the first actual Add comparison.
            phase = "add"
            engine.editLines(LineEdit.Add(listOf(AddressedStroke(address, original)), mapOf(preset.id to preset)))
            val installed = checkNotNull(engine.nativeLines.record(plane, input.id))
            val atOrigin = original.copy(seq = installed.seq)
            val atMoved = moved.copy(seq = installed.seq)
            val atTint = tinted.copy(seq = installed.seq)
            recordIs(atOrigin)
            initialDepth = engine.undo.undoDepth
            check(initialDepth == 1) { "Add must create exactly one Undo step" }
            compare(originalPixels, phase)

            phase = "translate_37_minus19"
            replace(atMoved, initialDepth + 1, movedPixels)
            phase = "tint"
            replace(atTint, initialDepth + 2, tintPixels)
            for ((label, expectedRecord, expectedPixels) in listOf(
                Triple("undo_tint", atMoved, movedPixels), Triple("undo_move", atOrigin, originalPixels))) {
                phase = label
                check(engine.undoStep()) { "$label refused" }
                check(engine.undo.undoDepth == if (label == "undo_tint") initialDepth + 1 else initialDepth)
                recordIs(expectedRecord)
                compare(expectedPixels, label)
            }
            for ((label, expectedRecord, expectedPixels) in listOf(
                Triple("redo_move", atMoved, movedPixels), Triple("redo_tint", atTint, tintPixels))) {
                phase = label
                check(engine.redoStep()) { "$label refused" }
                check(engine.undo.undoDepth == if (label == "redo_move") initialDepth + 1 else initialDepth + 2)
                recordIs(expectedRecord)
                compare(expectedPixels, label)
            }
            checkGl("complete")
            report.put("passed", true).put("phase", "complete")
                .put("translationPixels", JSONArray(listOf(DX, DY)))
                .put("initialUndoDepth", initialDepth).put("finalUndoDepth", engine.undo.undoDepth)
        } catch (t: Throwable) {
            report.put("passed", false).put("phase", phase)
                .put("failure", t.javaClass.simpleName + ": " + (t.message ?: "no message"))
        } finally {
            if (fbo != 0 && GLES30.glIsFramebuffer(fbo)) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, 0, 0)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            }
        }
        return report.put("tileUnion", keys.size).put("tileChecks", rows)
    }
}
