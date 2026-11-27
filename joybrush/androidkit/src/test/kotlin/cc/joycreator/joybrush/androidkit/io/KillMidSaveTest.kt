package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.TILE_SIZE
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JBX-36 S4: the app killed in the middle of a save. A kill runs nothing: whatever files are on the disk at that instant
 * are what the next start finds. A save writes `.tmp` whole and synced, moves the file to `.bak`, then `.tmp` onto the
 * file. Wherever the kill falls, the next start opens the newest save that is whole on the disk, never a half file.
 */
class KillMidSaveTest {
    private val layer = "paint"
    private val cel = "c"
    private val names = (0 until 40).map { "${it % 8}_${it / 8}" }

    @BeforeTest fun on() { ArchiveCarrying.streamTiles = true }
    @AfterTest fun off() { ArchiveCarrying.streamTiles = false }

    private fun doc(name: String) = JbDocument(
        id = "doc-kill", name = name,
        boards = listOf(Board(id = "b", name = "Board", kind = BoardKind.CANVAS, rect = RectPx(0, 0, 8 * TILE_SIZE, 5 * TILE_SIZE))),
        layers = listOf(Layer(id = layer, name = "Layer 1", kind = LayerKind.PAINT, cels = listOf(Cel(id = cel, tiles = names)))),
        activeLayerId = layer, activeBoardId = "b",
    )
    private fun tile(i: Int, seed: Int) = ByteArray(TILE_BYTES) { ((it * 7 + i * 13 + seed * 29 + (it shr 11)) and 0xFF).toByte() }
    private fun drawing(name: String, seed: Int) = JbContents(doc = doc(name),
        tiles = names.withIndex().associate { (i, n) -> Triple(layer, cel, n) to tile(i, seed) }, strokes = emptyMap())

    /** A drawing whose tiles come one at a time, and which stops for good at tile [stopAt]: the kill. */
    private class Stalling(private val all: Map<Triple<String, String, String>, ByteArray>, private val stopAt: Int) :
        AbstractMap<Triple<String, String, String>, ByteArray>(), TileStream<Triple<String, String, String>> {
        val reached = CountDownLatch(1)
        private val never = CountDownLatch(1)
        private var given = 0
        override val keys get() = all.keys
        override val entries get() = all.entries
        override fun sizeOf(key: Triple<String, String, String>) = all[key]?.size
        override fun take(key: Triple<String, String, String>): ByteArray {
            if (++given == stopAt) { reached.countDown(); never.await() }
            return all.getValue(key)
        }
    }

    private fun inDirectory(test: (File) -> Unit) {
        val dir = Files.createTempDirectory("joybrush-kill-").toFile()
        try { test(dir) } finally { dir.walkBottomUp().forEach { it.delete() } }
    }

    private fun assertDrawing(want: JbContents, got: JbContents) {
        assertEquals(want.doc.name, got.doc.name)
        for ((k, v) in want.tiles) assertContentEquals(v, got.tiles.getValue(k), "tile $k of ${want.doc.name}")
    }

    @Test fun killedWhileTheNewFileIsBeingWrittenTheLastSaveOpens() = inDirectory { dir ->
        val file = File(dir, "working.joybrush")
        val first = drawing("First", 1)
        JbArchive.saveCarrying(file, first, null)
        val before = file.readBytes()
        val second = drawing("Second", 2)
        val stalling = Stalling(second.tiles, stopAt = 20)
        val save = Thread { try { JbArchive.saveCarrying(file, second.copy(tiles = stalling), null) } catch (_: Throwable) {} }
        save.isDaemon = true
        save.start()
        assertTrue(stalling.reached.await(20, TimeUnit.SECONDS), "the save reached its 20th tile")
        // The kill: the save never goes on. This is what the next start finds.
        assertTrue(File(file.path + ".tmp").isFile, "half of the new file is on the disk")
        assertContentEquals(before, file.readBytes(), "the last save is untouched")
        val opened = DrawingHistory.readWorking(file) { JbArchive.open(it) }!!
        assertFalse(opened.recoveredBackup)
        assertDrawing(first, opened.contents)
        // The next start saves again over the half file, and that save is whole.
        val third = drawing("Third", 3)
        JbArchive.saveCarrying(file, third, null)
        assertDrawing(third, DrawingHistory.readWorking(file) { JbArchive.open(it) }!!.contents)
    }

    @Test fun killedBetweenTheTwoMovesTheNewestSaveOpens() = inDirectory { dir ->
        val file = File(dir, "working.joybrush")
        val first = drawing("First", 1)
        val second = drawing("Second", 2)
        JbArchive.saveCarrying(file, first, null)
        // The second save wrote its .tmp whole and synced, moved the file aside to .bak, and was killed before the
        // last move: no file, the newest save in .tmp, the one before in .bak.
        val made = File(dir, "made.joybrush")
        JbArchive.saveCarrying(made, second, null)
        assertTrue(file.renameTo(File(file.path + ".bak")))
        assertTrue(made.renameTo(File(file.path + ".tmp")))
        val opened = DrawingHistory.readWorking(file) { JbArchive.open(it) }!!
        assertDrawing(second, opened.contents)
        assertFalse(opened.recoveredBackup, "the newest save, not the one before it")
        assertTrue(file.isFile, "the interrupted move is finished")
        assertFalse(File(file.path + ".tmp").exists())
        assertDrawing(first, JbArchive.open(File(file.path + ".bak")))
    }

    @Test fun aHalfNewFileWithTheFileMovedAsideIsNeverOpened() = inDirectory { dir ->
        val file = File(dir, "working.joybrush")
        val first = drawing("First", 1)
        JbArchive.saveCarrying(file, first, null)
        assertTrue(file.renameTo(File(file.path + ".bak")))
        val half = File(file.path + ".tmp")
        JbArchive.saveCarrying(File(dir, "full.joybrush"), drawing("Second", 2), null)
        val bytes = File(dir, "full.joybrush").readBytes()
        half.writeBytes(bytes.copyOf(bytes.size / 2))
        val opened = DrawingHistory.readWorking(file) { JbArchive.open(it) }!!
        assertTrue(opened.recoveredBackup)
        assertDrawing(first, opened.contents)
        assertFalse(file.exists(), "a half file is never moved into place")
        assertDrawing(first, JbArchive.open(File(file.path + ".bak")))
    }
}
