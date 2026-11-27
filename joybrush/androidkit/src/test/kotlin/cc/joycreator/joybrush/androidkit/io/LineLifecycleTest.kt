package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.media.MediaStores
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class LineLifecycleTest {
    private fun pixel(): JbContents {
        val mixed = LineArchiveFixture.contents()
        val doc = mixed.doc.copy(brushes = emptyList(), layers = mixed.doc.layers.map { l ->
            l.copy(cels = listOf(Cel("c", tiles = listOf("0_0"), floatTiles = listOf("0_0"))))
        })
        return JbContents(doc, mixed.tiles, emptyMap(), mediaTiles = mapOf(
            MediaTileKey("l", "c", "0_0", "p0") to ByteArray(MediaStores.tileBytes(TILE_SIZE, "p0")) { (it % 31).toByte() },
            MediaTileKey("l", "c", "0_0", MediaStores.GROUND) to LineArchiveFixture.tile(12)))
    }
    private fun slabOnly(): JbContents {
        val mixed = LineArchiveFixture.contents()
        return mixed.copy(doc = mixed.doc.copy(brushes = emptyList(), layers = mixed.doc.layers.map { l ->
            l.copy(cels = l.cels.map { it.copy(strokesFile = null, looks = emptyList()) })
        }), strokes = emptyMap(), looks = emptyMap(), brushes = emptyMap())
    }
    private fun inDirectory(test: (File) -> Unit) {
        val dir = Files.createTempDirectory("jb-line-lifecycle-").toFile()
        try { test(dir) } finally { dir.walkBottomUp().forEach { it.delete() } }
    }

    @Test fun stagedReadRefusesLinesAndSlabOnlyWithoutChangingOrDeletingStagedBytes() = inDirectory { dir ->
        for (contents in listOf(LineArchiveFixture.contents(), slabOnly())) {
            val bytes = LineArchiveFixture.encoded(contents)
            val staged = StagedDrawing.stage(dir, ByteArrayInputStream(bytes))
            staged.use {
                val file = dir.listFiles()!!.single()
                val error = assertFailsWith<JbArchiveException> { it.read() }.message.orEmpty()
                assertTrue(error.contains("cannot show yet"), error)
                assertTrue(file.isFile, "read refusal must not close or delete the staged selection")
                assertContentEquals(bytes, file.readBytes())
            }
            assertTrue(dir.listFiles()!!.isEmpty(), "normal explicit close still owns cleanup")
        }
    }

    @Test fun recoveryRefusesUnsupportedPayloadWithoutReplacingOrDeletingSavedFiles() = inDirectory { dir ->
        val opened = File(dir, "opened.joybrush")
        val recovery = DrawingRecovery(opened)
        val before = pixel()
        recovery.rememberOpened(before)
        val bytes = opened.readBytes()
        assertFailsWith<JbArchiveException> { recovery.rememberOpened(slabOnly()) }
        assertContentEquals(bytes, opened.readBytes())
        val working = File(dir, "current.joybrush")
        JbArchive.save(working, slabOnly())
        recovery.rememberWorking(working)
        val preserved = dir.listFiles()!!.associate { it.name to it.readBytes() }
        val error = assertFailsWith<JbArchiveException> { recovery.read(before.doc.id) }.message.orEmpty()
        assertTrue(error.contains("cannot show yet"), error)
        assertEquals(preserved.keys, dir.listFiles()!!.map { it.name }.toSet())
        dir.listFiles()!!.forEach { assertContentEquals(preserved.getValue(it.name), it.readBytes()) }
    }

    @Test fun snapshotRejectsUnsupportedMapsAndDeclarationsBeforeReconstruction() {
        val fresh = pixel()
        for (unsupported in listOf(LineArchiveFixture.contents(), slabOnly(),
            fresh.copy(looks = mapOf(Triple("l", "c", "0_0") to LineArchiveFixture.tile(10))),
            fresh.copy(doc = fresh.doc.copy(layers = fresh.doc.layers.map { l -> l.copy(cels = l.cels.map { it.copy(looks = listOf("0_0")) }) })))) {
            assertFailsWith<JbArchiveException> { CanvasSnapshot.metadataOf(unsupported) }
            assertFailsWith<JbArchiveException> { CanvasSnapshot.merge(null, unsupported) }
            assertFailsWith<JbArchiveException> { CanvasSnapshot.merge(unsupported, fresh) }
            assertFailsWith<JbArchiveException> { CanvasSnapshot.merge(fresh, unsupported) }
        }
    }

    @Test fun pixelAndMediaStillPassStageRecoveryAndSnapshotWithoutPayloadChanges() = inDirectory { dir ->
        val expected = pixel()
        StagedDrawing.stage(dir, ByteArrayInputStream(LineArchiveFixture.encoded(expected))).use { staged ->
            val read = staged.read()
            assertEquals(expected.doc, read.doc)
            expected.mediaTiles.forEach { (key, bytes) -> assertContentEquals(bytes, read.mediaTiles.getValue(key)) }
            val metadata = CanvasSnapshot.metadataOf(read)
            assertTrue(metadata.tiles.isEmpty()); assertTrue(metadata.mediaTiles.isEmpty())
            val restored = CanvasSnapshot.merge(metadata, read)
            assertEquals(expected.doc, restored.doc)
            expected.tiles.forEach { (key, bytes) -> assertContentEquals(bytes, restored.tiles.getValue(key)) }
            expected.mediaTiles.forEach { (key, bytes) -> assertContentEquals(bytes, restored.mediaTiles.getValue(key)) }
            val recovery = DrawingRecovery(File(dir, "opened.joybrush"))
            recovery.rememberOpened(restored)
            val recovered = recovery.read(expected.doc.id)
            expected.mediaTiles.forEach { (key, bytes) -> assertContentEquals(bytes, recovered.mediaTiles.getValue(key)) }
            expected.tiles.forEach { (key, bytes) -> assertContentEquals(bytes, recovered.tiles.getValue(key)) }
        }
    }
}
