package cc.joycreator.joybrush.androidkit.gl

import android.content.Context
import android.content.pm.ApplicationInfo
import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.lines.LineEdit
import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.CapturedStrokeRules
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug diagnostic. Caller creates, initializes and finally reclaims a DISPOSABLE engine
 * on its GL thread. Never pass an owner drawing, even an empty one. No archive or owner-View writes.
 */
internal object FrameDuplicateProbe {
    private const val MAX_KEYS = 64
    private val rect = RectPx(-19, -13, 293, 271)

    fun run(context: Context, engine: GlPaintEngine, layerId: String): JSONObject {
        val report = JSONObject().put("probe", "frameDuplicateOrdinaryInk")
            .put("scope", "Disposable ordinary Ink Add, canonical frame duplicate, independent Delete and Undo/redo; no archive/save, low-budget failure, captured/joint or watercolor proof")
        val rows = JSONArray()
        val keys = linkedSetOf<Long>()
        var phase = "admission"
        var lineChangedPixels = 0L
        var deletedPixels = 0L
        var readbackState: IntArray? = null
        var serial = 0
        fun fresh() = "frame-probe-${++serial}"
        fun include(extra: Collection<Long>) {
            require((keys + extra).size <= MAX_KEYS) { "Probe tile budget exceeded" }
            keys.addAll(extra)
        }
        fun checkGl(label: String) {
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "$label GL error" }
        }
        fun read(cel: String): Map<Long, ByteArray> {
            include(engine.celTileKeys(layerId, cel))
            return keys.mapNotNull { key -> engine.readCelTile(layerId, cel, key)?.let { key to it } }.toMap()
        }
        fun compare(cel: String, expected: Map<Long, ByteArray>, label: String) {
            include(expected.keys)
            val actual = read(cel)
            for (key in keys) check(FrameDuplicatePixels.same(expected[key], actual[key])) {
                "$label exact RGBA8 mismatch at (${Tiles.tx(key)},${Tiles.ty(key)})"
            }
            checkGl(label)
            rows.put(JSONObject().put("phase", label).put("comparedTiles", keys.size)
                .put("transparentHaloIncluded", true).put("exactRgba8", true))
        }
        try {
            require(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                "Frame duplicate probe is debug only"
            }
            require(engine.ready && engine.layerIds() == listOf(layerId) && engine.boardDocument == null)
            require(!engine.strokeInProgress && !engine.watercolourAnimating && !engine.lifting)
            require(!engine.hasJointPaintPublication && !engine.undo.canUndo && !engine.undo.canRedo)
            require(engine.tileKeys(layerId).isEmpty() && engine.nativeLines.itemPlanes().isEmpty() &&
                engine.nativeLines.brushes().isEmpty()) { "Needs a fresh disposable engine" }
            val liveLayer = checkNotNull(engine.stack(layerId)[layerId])
            require(liveLayer.opacity == 1f && liveLayer.visible && !liveLayer.locked &&
                liveLayer.blend == BlendMode.NORMAL && !liveLayer.hasMask && !liveLayer.clip)
            checkGl("admission")
            // readCelTile uses a tightly packed client buffer. Inherited PBO/row/skip state
            // must never redirect that read or overrun its allocation on the shared context.
            val readbackEnums = intArrayOf(GLES30.GL_PIXEL_PACK_BUFFER_BINDING, GLES30.GL_PACK_ALIGNMENT,
                GLES30.GL_PACK_ROW_LENGTH, GLES30.GL_PACK_SKIP_ROWS, GLES30.GL_PACK_SKIP_PIXELS,
                GLES30.GL_READ_FRAMEBUFFER_BINDING, GLES30.GL_DRAW_FRAMEBUFFER_BINDING)
            val capturedState = IntArray(readbackEnums.size)
            for (i in readbackEnums.indices) {
                val value = IntArray(1)
                GLES30.glGetIntegerv(readbackEnums[i], value, 0)
                capturedState[i] = value[0]
            }
            checkGl("capture readback state")
            readbackState = capturedState
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
            GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS, 0)
            checkGl("normalize readback state")

            phase = "board_setup"
            val base = JbDocument(id = "frame-probe-doc", name = "Disposable frame probe",
                boards = listOf(Board("frame-probe-root", "Root", BoardKind.CANVAS,
                    RectPx(-512, -512, 1024, 1024))),
                layers = listOf(Layer(layerId, "Probe", LayerKind.PAINT,
                    cels = listOf(Cel("frame-probe-shared")))), activeLayerId = layerId)
            engine.setBoardDocument(base)
            engine.applyBoardChange(RegionDocumentOps.create(base, "Odd negative edges", rect, ::fresh))
            val before = checkNotNull(engine.boardDocument)
            val board = before.boards.single { it.kind == BoardKind.ANIMATION }
            val sourceCel = before.layers.single().regions.single().frameCel.getValue(board.currentFrameId!!)
            val sourcePlane = LinePlane(layerId, sourceCel)
            val sharedCel = checkNotNull(before.layers.single().sharedCelId)
            val sharedPlane = LinePlane(layerId, sharedCel)
            val preset = BrushPreset(id = "frame-probe-ink", name = "Probe Ink", size = Param(21f),
                opacity = Param(1f), flow = Param(1f), smoothing = 0f)
            val input = StrokeRecord(id = "frame-probe-line", brushId = preset.id, seed = 684723L,
                smoothing = 0f, screenPerDoc = 1f, colorArgb = 0xFF266DB5.toInt(), samples = listOf(
                    PenSample(-280f, -270f, 0.0, 0.9f, 0.25f, -0.5f),
                    PenSample(-45f, -22f, 10.0, 0.8f, 0.2f, -0.4f),
                    PenSample(35f, 31f, 20.0, 0.6f, 0.3f, -0.2f),
                    PenSample(190f, 152f, 40.0, 1f, 0.1f, 0.3f),
                    PenSample(310f, 280f, 60.0, 0.7f, 0.4f, 0.5f)))
            val estimate = engine.nativeLines.estimate(input, preset)
            // Patterned edge tiles expose wrong copy offsets. The wholly outside tile forces
            // the zero-slice clear path; opaque sentinels must never reach the duplicate.
            val outsideKey = Tiles.key(-2, -2)
            val seeded = setOf(outsideKey, Tiles.key(-1, -1), Tiles.key(1, 1)).associateWith { key ->
                ByteArray(Tiles.SIZE * Tiles.SIZE * 4).also { bytes ->
                    for (y in 0 until Tiles.SIZE) for (x in 0 until Tiles.SIZE) {
                        val offset = (y * Tiles.SIZE + x) * 4
                        bytes[offset] = (x * 13 + y * 3 + Tiles.tx(key)).toByte()
                        bytes[offset + 1] = (x * 7 + y * 11 + Tiles.ty(key)).toByte()
                        bytes[offset + 2] = (x * 5 + y * 17 + 95).toByte()
                        bytes[offset + 3] = -1
                    }
                }
            }
            include(FrameDuplicatePixels.halo(estimate + seeded.keys))
            for ((key, bytes) in seeded) engine.writeCelTile(layerId, sourceCel, key, bytes)
            val seededSnapshot = read(sourceCel)
            phase = "add_ordinary_ink"
            val sourceAddress = LineAddress(layerId, sourceCel, input.id)
            val addDepth = engine.undo.undoDepth
            engine.editLines(LineEdit.Add(listOf(AddressedStroke(sourceAddress, input)), mapOf(preset.id to preset)))
            check(engine.undo.undoDepth == addDepth + 1)
            val original = checkNotNull(engine.nativeLines.record(sourcePlane, input.id))
            check(CapturedStrokeRules.sameRecord(original, input.copy(seq = original.seq)))
            check(original.seq > 0 && original.capturedPhysicalSource == null)
            include(FrameDuplicatePixels.halo(engine.celTileKeys(layerId, sourceCel).toSet() +
                engine.nativeLines.footprint(sourcePlane, input.id)))
            val sourceSnapshot = read(sourceCel)
            val expectedCopy = FrameDuplicatePixels.clipped(sourceSnapshot, rect)
            val expectedDeleted = FrameDuplicatePixels.clipped(seededSnapshot, rect)
            check(FrameDuplicatePixels.painted(expectedDeleted) > 0) { "Fixture needs retained base paint" }
            lineChangedPixels = FrameDuplicatePixels.changed(expectedDeleted, expectedCopy)
            check(FrameDuplicatePixels.painted(expectedCopy) > 0 && lineChangedPixels > 0) {
                "Ink fixture must actually paint inside the board"
            }
            check(FrameDuplicatePixels.changed(sourceSnapshot, expectedCopy) > 0) {
                "Fixture must include outside-board sentinel pixels"
            }
            check(engine.nativeLines.bases(sourcePlane).isNotEmpty()) { "Fixture did not exercise base copying" }
            val sourceRecords = engine.nativeLines.lines(sourcePlane).toList()
            val sourceBases = engine.nativeLines.bases(sourcePlane)
            check(outsideKey in sourceBases) { "Fixture did not exercise wholly outside base copying" }
            val sourceFootprint = engine.nativeLines.footprint(sourcePlane, input.id).toSet()
            val sourceStep = checkNotNull(engine.undo.peekUndo())
            val sourceTileHandles = sourceStep.changes.filter { it.layerId == layerId && it.celId == sourceCel &&
                it.after != null }.associate { it.key to checkNotNull(it.after) }
            check(sourceTileHandles.keys == engine.celTileKeys(layerId, sourceCel).toSet()) {
                "Add history does not cover all live source tile handles; independence cannot be proved"
            }
            fun sourceUnchanged(label: String) {
                compare(sourceCel, sourceSnapshot, "$label/source")
                compare(sharedCel, emptyMap(), "$label/shared")
                check(!engine.nativeLines.hasItems(sharedPlane) && engine.nativeLines.bases(sharedPlane).isEmpty()) {
                    "$label changed shared plane items"
                }
                val records = engine.nativeLines.lines(sourcePlane)
                check(records.size == sourceRecords.size && records.zip(sourceRecords).all {
                    (a, b) -> CapturedStrokeRules.sameRecord(a, b)
                }) { "$label changed source recipes" }
                check(engine.nativeLines.bases(sourcePlane) == sourceBases &&
                    engine.nativeLines.footprint(sourcePlane, input.id) == sourceFootprint) {
                    "$label changed source bases or footprint"
                }
                check(engine.nativeLines.brush(preset.id) == preset) { "$label changed frozen brush" }
            }

            phase = "canonical_duplicate"
            val change = RegionDocumentOps.addFrame(before, board.id, NewFrame.DUPLICATE, ::fresh)
            val copy = checkNotNull(FrameDuplicatePlan.validate(before, change)).single()
            val targetCel = copy.toCelId
            val targetPlane = LinePlane(layerId, targetCel)
            val targetAddress = LineAddress(layerId, targetCel, input.id)
            val depth = engine.undo.undoDepth
            report.put("boardId", board.id).put("sourceCel", sourceCel).put("targetCel", targetCel)
                .put("duplicateStartingDepth", depth).put("fixtureVersion", 2)
            engine.applyBoardChange(change)
            check(engine.boardDocument == change.doc && engine.undo.undoDepth == depth + 1)
            fun targetRecord() {
                check(engine.boardDocument == change.doc) { "Unexpected document/frame mutation" }
                check(engine.nativeLines.lines(targetPlane).size == 1 &&
                    CapturedStrokeRules.sameRecord(engine.nativeLines.record(targetPlane, input.id), original)) {
                    "Target IDs, seq, seed, brush or full raw recipe differ"
                }
                check(engine.nativeLines.footprint(targetPlane, input.id) == sourceFootprint) {
                    "Target lost the source footprint"
                }
            }
            targetRecord()
            val sourceHandles = engine.nativeLines.bases(sourcePlane).values.toSet()
            val targetHandles = engine.nativeLines.bases(targetPlane).values.toSet()
            check(targetHandles.isNotEmpty() && sourceHandles.intersect(targetHandles).isEmpty()) {
                "Source and target bases share handles"
            }
            check(engine.nativeLines.bases(targetPlane).size == engine.nativeLines.bases(sourcePlane).size)
            val duplicateStep = checkNotNull(engine.undo.peekUndo())
            val copiedHandles = duplicateStep.changes.mapNotNull { it.after } +
                duplicateStep.slabs.mapNotNull { it.after?.texture }
            check(copiedHandles.isNotEmpty() && copiedHandles.size == copiedHandles.toSet().size &&
                copiedHandles.none { it in sourceTileHandles.values || it in sourceHandles }) {
                "Duplicate outputs alias each other or live source handles"
            }
            report.put("independentBaseTextures", targetHandles.size)
            compare(targetCel, expectedCopy, phase)
            sourceUnchanged(phase)

            phase = "independent_target_delete"
            engine.editLines(LineEdit.Delete(setOf(targetAddress)))
            check(engine.undo.undoDepth == depth + 2 && !engine.nativeLines.hasItems(targetPlane))
            check(engine.boardDocument == change.doc)
            val deleted = read(targetCel)
            deletedPixels = FrameDuplicatePixels.changed(expectedCopy, deleted)
            check(deletedPixels > 0) { "Target delete changed no pixels" }
            compare(targetCel, expectedDeleted, phase)
            sourceUnchanged(phase)

            phase = "undo_target_delete"
            check(engine.undoStep() && engine.undo.undoDepth == depth + 1)
            targetRecord(); compare(targetCel, expectedCopy, phase); sourceUnchanged(phase)
            phase = "undo_duplicate"
            check(engine.undoStep() && engine.undo.undoDepth == depth && engine.boardDocument == before)
            check(!engine.nativeLines.hasItems(targetPlane) && engine.nativeLines.bases(targetPlane).isEmpty())
            compare(targetCel, emptyMap(), phase); sourceUnchanged(phase)
            phase = "redo_duplicate"
            check(engine.redoStep() && engine.undo.undoDepth == depth + 1 && engine.boardDocument == change.doc)
            targetRecord(); compare(targetCel, expectedCopy, phase); sourceUnchanged(phase)
            phase = "redo_target_delete"
            check(engine.redoStep() && engine.undo.undoDepth == depth + 2)
            check(engine.boardDocument == change.doc)
            check(!engine.nativeLines.hasItems(targetPlane))
            compare(targetCel, expectedDeleted, phase); sourceUnchanged(phase)
            checkGl("complete")
            report.put("passed", true).put("phase", "complete")
        } catch (failure: Throwable) {
            report.put("passed", false).put("phase", phase)
                .put("failure", failure.javaClass.simpleName + ": " + (failure.message ?: "no message"))
        } finally {
            readbackState?.let { state ->
                try {
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, state[0])
                    GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, state[1])
                    GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, state[2])
                    GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS, state[3])
                    GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS, state[4])
                    GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, state[5])
                    GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, state[6])
                    checkGl("restore readback state")
                    report.put("readbackStateRestored", true)
                } catch (cleanup: Throwable) {
                    report.put("passed", false).put("readbackStateRestored", false)
                        .put("cleanupFailure", cleanup.javaClass.simpleName + ": " + (cleanup.message ?: "no message"))
                }
            }
        }
        return report.put("tileUnion", keys.size).put("tileChecks", rows)
            .put("lineChangedPixels", lineChangedPixels).put("deleteChangedPixels", deletedPixels)
            .put("boardRect", JSONArray(listOf(rect.x, rect.y, rect.w, rect.h)))
            .put("remainingGates", JSONArray(listOf("low-budget refusal and cleanup",
                "injected failure atomicity", "archive/save/reopen", "watercolor", "captured/joint")))
    }
}
