package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DrawingRecoveryTest {
    private fun art(id: String = "drawing", value: Int = 40): JbContents {
        var next = 0
        val base = DocOps.newDocument(id, id, 128, 96) { "id${next++}" }
        val layer = base.layers.single()
        val cel = layer.cels.single().copy(tiles = listOf("-1_0"))
        val doc = base.copy(paper = Paper("#A0C0E0"),
            boards = listOf(base.boards.single().copy(rect = RectPx(-64, 32, 128, 96))),
            layers = listOf(layer.copy(locked = true, cels = listOf(cel))))
        val tile = ByteArray(TILE_BYTES)
        tile[0] = value.toByte(); tile[3] = 255.toByte()
        return JbContents(doc, mapOf(Triple(layer.id, cel.id, "-1_0") to tile), emptyMap())
    }
    private fun inDirectory(test: (File, DrawingRecovery) -> Unit) {
        val dir = Files.createTempDirectory("joybrush-recovery-").toFile()
        try { test(dir, DrawingRecovery(File(dir, "opened.joybrush"))) }
        finally { dir.walkBottomUp().forEach { it.delete() } }
    }
    private fun same(expected: JbContents, actual: JbContents) {
        assertEquals(expected.doc, actual.doc)
        assertEquals(expected.tiles.keys, actual.tiles.keys)
        expected.tiles.forEach { (key, bytes) -> assertContentEquals(bytes, actual.tiles.getValue(key)) }
    }
    @Test fun openedDrawingRecoversIdentityGeometryPaperLocksAndPixels() = inDirectory { _, recovery ->
        val before = art()
        recovery.rememberOpened(before)
        same(before, recovery.read(before.doc.id))
    }
    @Test fun latestCompletedWorkingSaveWinsOverOriginalOpenedPixels() = inDirectory { dir, recovery ->
        recovery.rememberOpened(art(value = 10))
        val working = File(dir, "current.joybrush")
        JbArchive.save(working, art(value = 20)); recovery.rememberWorking(working)
        JbArchive.save(working, art(value = 30)); recovery.rememberWorking(working)
        same(art(value = 30), recovery.read("drawing"))
    }
    @Test fun corruptWorkingFileRecoversMatchingDurableBackupWithoutRewritingFiles() = inDirectory { dir, recovery ->
        recovery.rememberOpened(art(value = 10))
        val working = File(dir, "current.joybrush")
        JbArchive.save(working, art(value = 20))
        recovery.rememberWorking(working)
        JbArchive.save(working, art(value = 30)); recovery.rememberWorking(working)
        working.writeText("broken")
        File(dir, "opened.joybrush").writeText("broken checkpoint")
        val backup = File(working.path + ".bak").readBytes()
        same(art(value = 20), recovery.read("drawing"))
        assertEquals("broken", working.readText())
        assertContentEquals(backup, File(working.path + ".bak").readBytes())
    }
    @Test fun anotherScreensWorkingDrawingCannotReplaceThisDrawing() = inDirectory { dir, recovery ->
        recovery.rememberOpened(art())
        val working = File(dir, "current.joybrush")
        JbArchive.save(working, art()); recovery.rememberWorking(working)
        JbArchive.save(working, art("other"))
        JbArchive.save(working, art("other-again"))
        same(art(), recovery.read("drawing"))
        assertEquals("other-again", JbArchive.open(working).doc.id)
    }
    @Test fun noMatchingArchiveIsRefusedAndAllFilesStayIntact() = inDirectory { dir, recovery ->
        recovery.rememberOpened(art("other"))
        val before = dir.listFiles()!!.associate { it.name to it.readBytes() }
        assertFailsWith<JbArchiveException> { recovery.read("drawing") }
        dir.listFiles()!!.forEach { assertContentEquals(before.getValue(it.name), it.readBytes()) }
    }
    @Test fun failedCheckpointCannotDestroyPriorOpenedDrawing() = inDirectory { dir, recovery ->
        recovery.rememberOpened(art())
        val before = File(dir, "opened.joybrush").readBytes()
        assertFailsWith<JbArchiveException> { recovery.rememberOpened(art("new").copy(tiles = emptyMap())) }
        same(art(), recovery.read("drawing"))
        assertContentEquals(before, File(dir, "opened.joybrush").readBytes())
    }
    @Test fun newlyOpenedDrawingSupersedesPriorSessionCheckpoint() = inDirectory { dir, recovery ->
        recovery.rememberOpened(art())
        val working = File(dir, "current.joybrush")
        JbArchive.save(working, art()); recovery.rememberWorking(working)
        recovery.rememberOpened(art("new", 90))
        same(art("new", 90), recovery.read("new"))
    }
    @Test fun anotherDrawingWithSameDefaultIdCannotReplaceSessionPixels() = inDirectory { dir, recovery ->
        recovery.rememberOpened(art(value = 10))
        val working = File(dir, "current.joybrush")
        JbArchive.save(working, art(value = 20)); recovery.rememberWorking(working)
        JbArchive.save(working, art(value = 90)) // Another screen reused the default ID.
        same(art(value = 20), recovery.read("drawing"))
    }
    @Test fun selectingDrawingWithSameIdCannotFallBackToPreviousDrawingsPixels() = inDirectory { dir, recovery ->
        recovery.rememberOpened(art(value = 10))
        recovery.rememberOpened(art(value = 90))
        File(dir, "opened.joybrush").writeText("broken")
        assertFailsWith<JbArchiveException> { recovery.read("drawing") }
    }
    @Test fun failedSavedCopyKeepsTheLastGoodRecoveryArchive() = inDirectory { dir, recovery ->
        recovery.rememberOpened(art())
        val before = File(dir, "opened.joybrush").readBytes()
        assertFailsWith<JbArchiveException> { recovery.rememberWorking(File(dir, "missing.joybrush")) }
        same(art(), recovery.read("drawing"))
        assertContentEquals(before, File(dir, "opened.joybrush").readBytes())
    }
    @Test fun cleanupOnlyRemovesSessionCheckpointFiles() = inDirectory { dir, recovery ->
        recovery.rememberOpened(art()); recovery.rememberOpened(art("new"))
        val working = File(dir, "current.joybrush")
        JbArchive.save(working, art()); recovery.rememberWorking(working)
        val before = working.readBytes()
        recovery.close()
        assertContentEquals(before, working.readBytes())
        assertFalse(File(dir, "opened.joybrush").exists())
        assertFalse(File(dir, "opened.joybrush.bak").exists())
    }
}
