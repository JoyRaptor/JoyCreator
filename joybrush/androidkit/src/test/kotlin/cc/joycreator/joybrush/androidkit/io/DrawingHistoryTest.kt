package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DrawingHistoryTest {
    private fun contents(name: String = "Drawing"): JbContents {
        var id = 0
        return JbContents(DocOps.newDocument("doc", name, 2, 2) { "id${id++}" }, emptyMap(), emptyMap())
    }
    private fun inDirectory(test: (File) -> Unit) {
        val dir = Files.createTempDirectory("joybrush-history-").toFile()
        try { test(dir) } finally { dir.walkBottomUp().forEach { it.delete() } }
    }
    @Test fun preservationCreatesReadableCopyWithoutChangingWorkingFile() = inDirectory { dir ->
        val working = File(dir, "current.joybrush")
        JbArchive.save(working, contents("Working"))
        val before = working.readBytes()
        val copy = DrawingHistory.preserve(File(dir, "recent"), contents("Previous"))
        assertEquals("Previous", JbArchive.open(copy).doc.name)
        assertContentEquals(before, working.readBytes())
    }
    @Test fun historyKeepsFiveCompleteDrawingsAndAlwaysKeepsLatestWrite() = inDirectory { dir ->
        val history = File(dir, "recent")
        repeat(DrawingHistory.KEEP + 2) { DrawingHistory.preserve(history, contents("Drawing $it")) }
        assertEquals(DrawingHistory.KEEP, DrawingHistory.list(history).size)
        // A future-dated old file must not make the new safety copy prune itself.
        val future = File(history, "9999999999999-future.joybrush")
        JbArchive.save(future, contents("Future"))
        val latest = DrawingHistory.preserve(history, contents("Latest"))
        assertTrue(latest.isFile)
        assertEquals(DrawingHistory.KEEP, DrawingHistory.list(history).size)
        DrawingHistory.list(history).forEach { JbArchive.open(it) }
    }
    @Test fun failedPreservationNeverPrunesExistingDrawings() = inDirectory { dir ->
        repeat(DrawingHistory.KEEP) { DrawingHistory.preserve(dir, contents("Drawing $it")) }
        val before = DrawingHistory.list(dir).associate { it.name to it.readBytes() }
        val good = contents()
        val layer = good.doc.layers.single()
        val bad = good.copy(doc = good.doc.copy(layers = listOf(layer.copy(cels = listOf(layer.cels.single().copy(tiles = listOf("0_0")))))))
        assertFailsWith<JbArchiveException> { DrawingHistory.preserve(dir, bad) }
        assertEquals(before.keys, DrawingHistory.list(dir).map { it.name }.toSet())
        DrawingHistory.list(dir).forEach { assertContentEquals(before.getValue(it.name), it.readBytes()) }
    }
    @Test fun absentWorkingFileReturnsNull() = inDirectory { dir ->
        assertNull(DrawingHistory.readWorking(File(dir, "current.joybrush")))
    }
    @Test fun healthyMainWinsOverBackup() = inDirectory { dir ->
        val file = File(dir, "current.joybrush")
        JbArchive.save(file, contents("Old"))
        JbArchive.save(file, contents("Current"))
        val read = DrawingHistory.readWorking(file)!!
        assertFalse(read.recoveredBackup)
        assertEquals("Current", read.contents.doc.name)
    }
    @Test fun missingOrCorruptMainRecoversValidatedBackup() = inDirectory { dir ->
        val file = File(dir, "current.joybrush")
        JbArchive.save(File(file.path + ".bak"), contents("Recovered"))
        val missing = DrawingHistory.readWorking(file)!!
        assertTrue(missing.recoveredBackup)
        assertEquals("Recovered", missing.contents.doc.name)
        file.writeText("broken")
        val corrupt = DrawingHistory.readWorking(file)!!
        assertEquals(missing, corrupt)
        assertEquals("broken", file.readText())
    }
    @Test fun corruptMainAndBackupAreRefusedWithoutRewritingEither() = inDirectory { dir ->
        val file = File(dir, "current.joybrush")
        val backup = File(file.path + ".bak")
        file.writeText("main")
        backup.writeText("backup")
        assertFailsWith<JbArchiveException> { DrawingHistory.readWorking(file) }
        assertEquals("main", file.readText())
        assertEquals("backup", backup.readText())
    }
}
