package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.lines.LineEdit
import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.stroke.StrokeWashFrame
import java.nio.ByteBuffer
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only disposable-engine check for the selected watercolor line finish-only cache. */
internal object WatercolourEditProbe {
    private const val TILE = 256
    private const val BYTES = TILE * TILE * 4

    fun run(engine: GlPaintEngine, layerId: String, preset: BrushPreset, input: StrokeRecord): JSONObject {
        val report = JSONObject().put("probe", "watercolourEditCache")
            .put("scope", "Disposable GL engine native-line add/lift/recolour/history; no View, save, recovery or quality claim")
        var phase = "entry"
        var fbo = 0
        val rows = JSONArray()
        var depositionBefore = -1L
        var finishBefore = -1L
        var depositionAfter = -1L
        var finishAfter = -1L
        var initialDepth = -1
        var afterTintDepth = -1
        var finalDepth = -1
        var keys = emptySet<Long>()
        val address = LineAddress(layerId, null, input.id)
        val plane = LinePlane(layerId, null)

        fun checkGl(label: String) {
            var first = GLES30.GL_NO_ERROR
            var code = GLES30.glGetError()
            while (code != GLES30.GL_NO_ERROR) {
                if (first == GLES30.GL_NO_ERROR) first = code
                code = GLES30.glGetError()
            }
            check(first == GLES30.GL_NO_ERROR) { "$label GL error 0x${first.toString(16)}" }
        }
        fun readTexture(texture: Int, label: String): ByteArray {
            check(texture != 0 && GLES30.glIsTexture(texture)) { "$label missing texture" }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "$label incomplete read framebuffer"
            }
            GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
            GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS, 0)
            val buffer = ByteBuffer.allocateDirect(BYTES)
            GLES30.glReadPixels(0, 0, TILE, TILE, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            checkGl(label)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, 0, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            buffer.position(0)
            return ByteArray(BYTES).also { buffer.get(it) }
        }
        fun canonical(record: StrokeRecord): Map<Long, ByteArray?> {
            val unit = engine.prepareRecordedLine(layerId, record, preset)
            try {
                val footprint = engine.nativeLines.estimate(record, preset) + unit.tiles.keys
                return footprint.associateWith { key -> unit.tiles[key]?.let { readTexture(it, "canonical:$key") } }
            } finally {
                engine.discardRecordedLine(unit)
            }
        }
        fun compare(expected: Map<Long, ByteArray?>, label: String) {
            check(expected.keys == keys) { "$label key mismatch expected=${expected.keys.size} actual=${keys.size}" }
            val actualKeys = engine.tileKeys(layerId).toSet() + engine.nativeLines.lines(plane)
                .flatMap { engine.nativeLines.footprint(plane, it.id) }
            check(actualKeys == keys) { "$label leaked or lost tiles outside the complete expected footprint" }
            for (key in keys.sorted()) {
                val actual = engine.readTile(layerId, key)
                val reference = expected[key]
                val matches = if (actual == null) reference == null || reference.all { it == 0.toByte() }
                    else if (reference == null) actual.all { it == 0.toByte() }
                    else actual.contentEquals(reference)
                check(matches) {
                    "$label RGBA8/tile-presence mismatch at tile $key"
                }
                rows.put(JSONObject().put("phase", label).put("key", key.toString())
                    .put("matchedRgba8", true).put("normalizedTransparent", reference == null || actual == null)
                    .put("present", actual != null))
            }
        }

        try {
            check(preset.engine == "watercolour") { "probe requires a watercolor preset" }
            check(input.brushId == preset.id) { "record brush does not match supplied preset" }
            val original = input.copy(samples = input.samples.toList(), washFrame = input.washFrame ?:
                StrokeWashFrame(1.0, 0.0, 17.0, 0.0, 1.0, 29.0, 0.0, 0.0, 1.0))
            report.put("fixtureHasWashFrame", true)
            val tintA = original.copy(colorArgb = 0xFFB44D73.toInt())
            val tintB = original.copy(colorArgb = 0xFF3C9A72.toInt())
            phase = "read_fbo"
            val names = IntArray(1)
            GLES30.glGenFramebuffers(1, names, 0)
            fbo = names[0]
            check(fbo != 0) { "could not allocate local read framebuffer" }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            check(GLES30.glIsFramebuffer(fbo)) { "could not bind local read framebuffer" }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            checkGl("create read framebuffer")

            // Build independent ordinary replay references before selection/cache state exists.
            phase = "canonical_controls"
            val controls = listOf(
                "original" to canonical(original.copy(id = "${input.id}-control-0")),
                "tintA" to canonical(tintA.copy(id = "${input.id}-control-1")),
                "tintB" to canonical(tintB.copy(id = "${input.id}-control-2")),
            ).toMap()

            phase = "add_native_line"
            engine.editLines(LineEdit.Add(listOf(AddressedStroke(address, original)), mapOf(preset.id to preset)))
            val installed = engine.nativeLines.record(plane, original.id)
                ?: error("native line Add did not install the record")
            keys = engine.nativeLines.footprint(plane, original.id)
            check(keys.isNotEmpty()) { "native line has empty footprint" }
            compare(controls.getValue("original"), "add")
            check(keys.any { key -> engine.readTile(layerId, key)?.let { bytes ->
                bytes.indices.any { it % 4 == 3 && bytes[it] != 0.toByte() }
            } == true }) { "canonical Add produced no visible nonzero-alpha watercolor pixels" }
            initialDepth = engine.undo.undoDepth

            phase = "prime_selected_line_cache"
            val depositionBeforeLift = engine.watercolourLineDepositionCalls
            engine.beginLift(setOf(address))
            check(engine.lifting) { "single-line lift did not start" }
            engine.endLift()
            check(!engine.lifting) { "single-line lift did not end" }
            depositionBefore = engine.watercolourLineDepositionCalls
            finishBefore = engine.watercolourLineFinishOnlyCalls
            check(depositionBefore == depositionBeforeLift + 1) { "single-line lift did not deposit/prime retained watercolor coverage once" }
            report.put("liftDepositionDelta", depositionBefore - depositionBeforeLift)

            phase = "noop_original_colour"
            engine.editLines(LineEdit.Replace(listOf(AddressedStroke(address, installed))))
            check(engine.undo.undoDepth == initialDepth) { "unchanged color added a history step" }
            check(engine.watercolourLineDepositionCalls == depositionBefore &&
                engine.watercolourLineFinishOnlyCalls == finishBefore) { "unchanged color unnecessarily rendered the line" }
            compare(controls.getValue("original"), "noop_original_colour")

            phase = "tint_a"
            val installedTintA = tintA.copy(seq = installed.seq)
            engine.editLines(LineEdit.Replace(listOf(AddressedStroke(address, installedTintA))))
            check(engine.undo.undoDepth == initialDepth + 1) { "first tint did not add exactly one undo step" }
            check(engine.watercolourLineDepositionCalls == depositionBefore) { "first tint redeposited watercolor dabs" }
            check(engine.watercolourLineFinishOnlyCalls == finishBefore + 1) { "first tint missed finish-only cache path" }
            check(engine.nativeLines.record(plane, original.id) == installedTintA) { "first tint record mismatch" }
            compare(controls.getValue("tintA"), "tint_a")

            phase = "tint_b"
            val installedTintB = tintB.copy(seq = installed.seq)
            engine.editLines(LineEdit.Replace(listOf(AddressedStroke(address, installedTintB))))
            afterTintDepth = engine.undo.undoDepth
            depositionAfter = engine.watercolourLineDepositionCalls
            finishAfter = engine.watercolourLineFinishOnlyCalls
            check(afterTintDepth == initialDepth + 2) { "second tint did not add exactly one undo step" }
            check(depositionAfter == depositionBefore) { "second tint redeposited watercolor dabs" }
            check(finishAfter == finishBefore + 2) { "second tint missed finish-only cache path" }
            check(engine.nativeLines.record(plane, original.id) == installedTintB) { "second tint record mismatch" }
            compare(controls.getValue("tintB"), "tint_b")

            phase = "undo_last_tint"
            check(engine.undoStep()) { "undo of second tint was refused" }
            check(engine.undo.undoDepth == initialDepth + 1) { "undo did not remove exactly one history step" }
            check(engine.nativeLines.record(plane, original.id) == installedTintA) { "undo did not restore first tint record" }
            compare(controls.getValue("tintA"), "undo_tint_b")

            phase = "redo_last_tint"
            check(engine.redoStep()) { "redo of second tint was refused" }
            check(engine.undo.undoDepth == afterTintDepth) { "redo did not restore history depth" }
            check(engine.nativeLines.record(plane, original.id) == installedTintB) { "redo did not restore second tint record" }
            compare(controls.getValue("tintB"), "redo_tint_b")

            phase = "multi_selection_fallback"
            val sibling = original.copy(id = "${input.id}-sibling", seed = original.seed + 1,
                samples = original.samples.map { it.copy(x = it.x + 2048f) })
            val siblingAddress = LineAddress(layerId, null, sibling.id)
            val siblingControl = canonical(sibling.copy(id = "${sibling.id}-control"))
            check(keys.intersect(siblingControl.keys).isEmpty()) { "fallback fixture must have disjoint line footprints" }
            engine.editLines(LineEdit.Add(listOf(AddressedStroke(siblingAddress, sibling))))
            val beforeFallbackDeposition = engine.watercolourLineDepositionCalls
            val beforeFallbackFinish = engine.watercolourLineFinishOnlyCalls
            engine.beginLift(setOf(address, siblingAddress))
            engine.endLift()
            val fallbackTint = installedTintB.copy(colorArgb = 0xFF4964CB.toInt())
            val fallbackControl = canonical(fallbackTint.copy(id = "${input.id}-fallback-control"))
            val beforeFallbackUndo = engine.undo.undoDepth
            val beforeFallbackPreparations = engine.recordedLinePreparationCalls
            engine.editLines(LineEdit.Replace(listOf(AddressedStroke(address, fallbackTint))))
            check(engine.undo.undoDepth == beforeFallbackUndo + 1) { "fallback tint did not add one history step" }
            check(engine.recordedLinePreparationCalls > beforeFallbackPreparations) { "fallback failed to prepare the changed line" }
            check(engine.watercolourLineDepositionCalls == beforeFallbackDeposition &&
                engine.watercolourLineFinishOnlyCalls == beforeFallbackFinish) { "multi-selection used the single-line cache" }
            keys = fallbackControl.keys + siblingControl.keys
            compare(fallbackControl + siblingControl, "multi_selection_fallback")
            report.put("multiSelectionFallbackExact", true).put("noopColourAddsHistory", false)

            report.put("passed", true).put("phase", "complete").put("footprintTiles", keys.size)
                .put("initialUndoDepth", initialDepth).put("afterTwoTintsUndoDepth", afterTintDepth)
                .put("depositionCallsBeforeTints", depositionBefore).put("depositionCallsAfterTints", depositionAfter)
                .put("finishOnlyCallsBeforeTints", finishBefore).put("finishOnlyCallsAfterTints", finishAfter)
                .put("undoDepthAfterFirstTint", initialDepth + 1).put("tileChecks", rows)
        } catch (t: Throwable) {
            val reportError = t.javaClass.simpleName + ": " + (t.message ?: "no message")
            report.put("passed", false).put("phase", phase).put("failure", reportError)
                .put("footprintTiles", keys.size).put("initialUndoDepth", initialDepth)
                .put("afterTwoTintsUndoDepth", afterTintDepth)
                .put("depositionCallsBeforeTints", depositionBefore).put("depositionCallsAfterTints", depositionAfter)
                .put("finishOnlyCallsBeforeTints", finishBefore).put("finishOnlyCallsAfterTints", finishAfter)
                .put("tileChecks", rows)
        } finally {
            if (fbo != 0 && GLES30.glIsFramebuffer(fbo)) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, 0, 0)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            }
        }
        return report
    }
}
