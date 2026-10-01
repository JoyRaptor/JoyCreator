package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.DocOps
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.*

class StagedDrawingTest {
    private fun contents(name: String): JbContents {
        var id = 0
        return JbContents(DocOps.newDocument("doc", name, 2, 2) { "id${id++}" }, emptyMap(), emptyMap())
    }
    private fun inDirectory(test: (File) -> Unit) {
        val dir = Files.createTempDirectory("joybrush-open-").toFile()
        try { test(dir) } finally { dir.walkBottomUp().forEach { it.delete() } }
    }
    @Test fun selectedRecentSurvivesPruningBeforeDecode() = inDirectory { dir ->
        val history = File(dir, "recent")
        val selected = DrawingHistory.preserve(history, contents("Selected"))
        val staged = selected.inputStream().use { StagedDrawing.stage(File(dir, "stage"), it) }
        staged.use {
            repeat(DrawingHistory.KEEP) { DrawingHistory.preserve(history, contents("Previous $it")) }
            assertFalse(selected.exists())
            assertEquals("Selected", it.read().doc.name)
        }
        assertTrue(File(dir, "stage").listFiles()!!.isEmpty())
    }
    @Test fun providerChangesCannotReplaceQueuedSelection() = inDirectory { dir ->
        val source = File(dir, "source.joybrush")
        JbArchive.save(source, contents("Selected"))
        val staged = source.inputStream().use { StagedDrawing.stage(File(dir, "stage"), it) }
        JbArchive.save(source, contents("Changed"))
        staged.use { assertEquals("Selected", it.read().doc.name) }
        assertEquals("Changed", JbArchive.open(source).doc.name)
    }
    @Test fun corruptCandidateLeavesPreservedPreviousDrawingReadable() = inDirectory { dir ->
        val staged = StagedDrawing.stage(File(dir, "stage"), ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        val previous = DrawingHistory.preserve(File(dir, "recent"), contents("Previous"))
        staged.use { assertFailsWith<JbArchiveException> { it.read() } }
        assertEquals("Previous", JbArchive.open(previous).doc.name)
    }
    @Test fun failedProviderReadRemovesPartialCopy() = inDirectory { dir ->
        val input = object : InputStream() {
            var count = 0
            override fun read(): Int = if (count++ < 10) 42 else throw IOException("lost provider")
        }
        assertFailsWith<JbArchiveException> { StagedDrawing.stage(dir, input) }
        assertTrue(dir.listFiles()!!.isEmpty())
    }
    @Test fun stagingStreamsLargeInputWithoutDecodingOrOwningTheInput() = inDirectory { dir ->
        val input = object : InputStream() {
            var remaining = 32 * 1024 * 1024
            var closed = false
            override fun read(): Int = error("must use bounded bulk reads")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                assertTrue(length <= 8192)
                if (remaining == 0) return -1
                val count = minOf(length, remaining)
                bytes.fill(0, offset, offset + count)
                remaining -= count
                return count
            }
            override fun close() { closed = true }
        }
        val staged = StagedDrawing.stage(dir, input)
        assertFalse(input.closed)
        assertEquals(32L * 1024 * 1024, dir.listFiles()!!.single().length())
        staged.close()
        assertTrue(dir.listFiles()!!.isEmpty())
    }
    @Test fun queuedSelectionsHaveIndependentFilesAndCleanup() = inDirectory { dir ->
        val source = File(dir, "source.joybrush")
        JbArchive.save(source, contents("First"))
        val first = source.inputStream().use { StagedDrawing.stage(dir, it) }
        JbArchive.save(source, contents("Second"))
        val second = source.inputStream().use { StagedDrawing.stage(dir, it) }
        first.close()
        assertFailsWith<JbArchiveException> { first.read() }
        second.use { assertEquals("Second", it.read().doc.name) }
        assertTrue(source.isFile)
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("open-") })
    }
}
