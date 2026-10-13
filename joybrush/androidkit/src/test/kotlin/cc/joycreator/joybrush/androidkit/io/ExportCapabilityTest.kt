package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.chrome.BoardExportLayout as Export
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.export.AnimExport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ExportCapabilityTest {
    private fun pixel(): JbContents {
        val base = LineArchiveFixture.contents()
        return JbContents(base.doc.copy(brushes = emptyList(), layers = base.doc.layers.map { l ->
            l.copy(cels = listOf(Cel("c", tiles = listOf("0_0"))))
        }), base.tiles, emptyMap())
    }
    private fun variants(): List<JbContents> {
        val base = pixel()
        fun with(cel: Cel) = base.copy(doc = base.doc.copy(layers = base.doc.layers.map { it.copy(cels = listOf(cel)) }))
        return listOf(LineArchiveFixture.contents(),
            with(Cel("c", tiles = listOf("0_0"), slabs = listOf(SlabRef("0_0", 0)))),
            with(Cel("c", tiles = listOf("0_0"), looks = listOf("0_0"))),
            with(Cel("c", strokesFile = "layers/l/c/strokes.jbs")))
    }
    private fun inDirectory(test: (File) -> Unit) {
        val dir = Files.createTempDirectory("jb-export-capability-").toFile()
        try { test(dir) } finally { dir.walkBottomUp().forEach { it.delete() } }
    }

    @Test fun openRasterRefusesBeforeOutputPaperWarningsOrLegacyCallbacks() {
        for (contents in variants()) {
            val out = ByteArrayOutputStream()
            var calls = 0
            val failure = assertFailsWith<JbArchiveException> {
                OraExport.write(out, contents, "b", null, true,
                    paperRenderer = { calls++; error("paper must not run") }, onWarning = { calls++ },
                    brushLookup = { calls++; LineArchiveFixture.brush }, onInkRefusal = { calls++ })
            }.message.orEmpty()
            assertTrue("cannot be exported" in failure, failure)
            assertEquals(0, out.size()); assertEquals(0, calls)
        }
    }

    @Test fun pngAndAnimationRefuseBeforePaperFramesOrSinkOutput() {
        for (contents in variants()) {
            var calls = 0
            val paper: (RectPx) -> ByteArray = { calls++; error("paper must not run") }
            assertFailsWith<JbArchiveException> { CanvasPng.encode(contents, true, paper) { calls++ } }
            assertFailsWith<JbArchiveException> { CanvasPng.encodeBoard(contents, "b", true, paperRenderer = paper, onWarning = { calls++ }) }
            val board = contents.doc.boards.single().copy(kind = BoardKind.ANIMATION,
                frames = listOf(Frame("f1"), Frame("f2")), currentFrameId = "f1")
            val animation = contents.copy(doc = contents.doc.copy(boards = listOf(board)))
            val plan = AnimExport.plan(board, "Test")
            for (format in listOf(AnimFormat.GIF, AnimFormat.SPRITE_SHEET)) {
                assertFailsWith<JbArchiveException> {
                    AnimExportRunner.encodeOne(format, animation, "b", plan, true,
                        paperRenderer = paper, onWarning = { calls++ }, onFrame = { _, _ -> calls++ })
                }
            }
            assertFailsWith<JbArchiveException> {
                AnimExportRunner.writeSequence(animation, "b", plan, true, AnimFileSink { _, _, _ -> calls++ },
                    paperRenderer = paper, onWarning = { calls++ }, onFrame = { _, _ -> calls++ })
            }
            assertEquals(0, calls)
        }
    }

    @Test fun boardExportRefusesBeforeCreatingCacheAndKeepsExistingArtifacts() = inDirectory { dir ->
        val saved = File(dir, "previous.joybrush").also { JbArchive.save(it, pixel()) }
        val preserved = saved.readBytes()
        val cache = File(dir, "not-created")
        for (contents in variants()) {
            val board = contents.doc.boards.single()
            val request = BoardExportRequest.capture(contents.doc,
                Export.Choice("b", Export.Scope.BOARD, Export.Format.PNG, "b", "b", "b"), true)
            var calls = 0
            assertFailsWith<JbArchiveException> { BoardExport.stage(contents, request, cache, { calls++ }, { _, _ -> calls++ }) }
            assertFalse(cache.exists()); assertEquals(0, calls)
            assertContentEquals(preserved, saved.readBytes())
            assertEquals(listOf(saved.name), dir.listFiles()!!.map { it.name })
            assertEquals(BoardKind.CANVAS, board.kind)
        }
    }

    @Test fun snapshotsRefuseLineStorageAndFrozenMetadataBeforeCallbacksOrReconstruction() {
        val base = pixel()
        val frozen = base.copy(doc = base.doc.copy(brushes = listOf(LineArchiveFixture.brush.id)),
            brushes = mapOf(LineArchiveFixture.brush.id to LineArchiveFixture.brush))
        val brushMapOnly = base.copy(brushes = frozen.brushes)
        for (contents in variants() + listOf(frozen, brushMapOnly, base.copy(doc = frozen.doc))) {
            for (snapshot in listOf<() -> Unit>(
                { CanvasSnapshot.metadataOf(contents); Unit },
                { CanvasSnapshot.merge(null, contents); Unit },
                { CanvasSnapshot.merge(contents, base); Unit },
                { CanvasSnapshot.merge(base, contents); Unit })) {
                assertFailsWith<JbArchiveException> { snapshot() }
            }
            if (contents !== brushMapOnly) {
                var reads = 0
                assertFailsWith<IllegalArgumentException> { BoardSnapshot.capture(contents.doc,
                    { _, _ -> reads++; emptyList() }, { _, _, _ -> reads++; null },
                    mediaKeys = { _, _ -> reads++; emptyMap() }, mediaRead = { _, _, _, _ -> reads++; null }) }
                assertEquals(0, reads)
            }
        }
    }

    @Test fun frozenMetadataOnlyRefusalKeepsStagedAndRecoveryArtifacts() = inDirectory { dir ->
        val base = pixel()
        val brush = LineArchiveFixture.brush
        val frozen = base.copy(doc = base.doc.copy(brushes = listOf(brush.id)), brushes = mapOf(brush.id to brush))
        val bytes = LineArchiveFixture.encoded(frozen)
        val stageDir = File(dir, "stage")
        StagedDrawing.stage(stageDir, ByteArrayInputStream(bytes)).use { staged ->
            assertFailsWith<JbArchiveException> { staged.read() }
            assertContentEquals(bytes, stageDir.listFiles()!!.single().readBytes())
        }
        val saved = File(dir, "opened.joybrush")
        val recovery = DrawingRecovery(saved)
        recovery.rememberOpened(base)
        val preserved = saved.readBytes()
        assertFailsWith<JbArchiveException> { recovery.rememberOpened(frozen) }
        assertContentEquals(preserved, saved.readBytes())
        val working = File(dir, "working.joybrush").also { JbArchive.save(it, frozen) }
        recovery.rememberWorking(working)
        val before = saved.readBytes()
        val workingBefore = working.readBytes()
        assertFailsWith<JbArchiveException> { recovery.read(base.doc.id) }
        assertContentEquals(before, saved.readBytes()); assertContentEquals(workingBefore, working.readBytes())
    }

    @Test fun unusedFrozenBrushListDoesNotBlockPlainPixelExport() {
        val base = pixel()
        val frozen = base.copy(doc = base.doc.copy(brushes = listOf(LineArchiveFixture.brush.id)),
            brushes = mapOf(LineArchiveFixture.brush.id to LineArchiveFixture.brush))
        assertContentEquals(CanvasPng.encode(base, false), CanvasPng.encode(frozen, false))
    }
}
