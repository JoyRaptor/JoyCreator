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
    /** Editable lines v1: ordinary line records, their saved look and a frozen brush copy; no upper pixel slab. */
    private fun linesOnly() = LineArchiveFixture.linesOnly()
    /** The same lines carrying a captured physical source: the exact-provenance format this screen cannot show yet. */
    private fun capturedLines() = LineArchiveFixture.capturedLines()
    private fun assertSnapshotRefuses(unsupported: JbContents, fresh: JbContents) {
        assertFailsWith<JbArchiveException> { CanvasSnapshot.metadataOf(unsupported) }
        assertFailsWith<JbArchiveException> { CanvasSnapshot.merge(null, unsupported) }
        assertFailsWith<JbArchiveException> { CanvasSnapshot.merge(unsupported, fresh) }
        assertFailsWith<JbArchiveException> { CanvasSnapshot.merge(fresh, unsupported) }
    }
    private fun inDirectory(test: (File) -> Unit) {
        val dir = Files.createTempDirectory("jb-line-lifecycle-").toFile()
        try { test(dir) } finally { dir.walkBottomUp().forEach { it.delete() } }
    }

    @Test fun stagedReadRefusesSlabbedDrawingsWithoutChangingOrDeletingStagedBytes() = inDirectory { dir ->
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

    @Test fun stagedReadAdmitsOrdinaryLinesLooksAndFrozenBrushes() = inDirectory { dir ->
        val expected = linesOnly()
        val bytes = LineArchiveFixture.encoded(expected)
        StagedDrawing.stage(dir, ByteArrayInputStream(bytes)).use { staged ->
            val read = staged.read()
            assertEquals(expected.doc, read.doc)
            assertEquals(expected.strokes.mapValues { it.value.sortedBy { r -> r.seq } }, read.strokes)
            assertEquals(expected.brushes, read.brushes)
            expected.looks.forEach { (key, look) -> assertContentEquals(look, read.looks.getValue(key)) }
            expected.tiles.forEach { (key, base) -> assertContentEquals(base, read.tiles.getValue(key)) }
            assertContentEquals(bytes, dir.listFiles()!!.single().readBytes(), "a read leaves the staged selection as it was")
        }
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun recoveryRefusesSlabbedPayloadWithoutReplacingOrDeletingSavedFiles() = inDirectory { dir ->
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

    @Test fun recoveryKeepsOrdinaryLinesLooksAndFrozenBrushesAcrossRememberAndRead() = inDirectory { dir ->
        val lines = linesOnly()
        val opened = File(dir, "opened.joybrush")
        val recovery = DrawingRecovery(opened)
        recovery.rememberOpened(lines)
        val fromOpened = recovery.read(lines.doc.id)
        assertEquals(lines.strokes.mapValues { it.value.sortedBy { r -> r.seq } }, fromOpened.strokes)
        assertEquals(lines.brushes, fromOpened.brushes)
        val working = File(dir, "current.joybrush")
        JbArchive.save(working, lines)
        recovery.rememberWorking(working)
        val fromWorking = recovery.read(lines.doc.id)
        assertEquals(fromOpened.doc, fromWorking.doc)
        lines.looks.forEach { (key, look) -> assertContentEquals(look, fromWorking.looks.getValue(key)) }
    }

    @Test fun snapshotRejectsPixelSlabsBeforeReconstruction() {
        val fresh = pixel()
        for (unsupported in listOf(LineArchiveFixture.contents(), slabOnly())) assertSnapshotRefuses(unsupported, fresh)
    }

    @Test fun snapshotRejectsCapturedSourceLinesBeforeReconstruction() {
        assertSnapshotRefuses(capturedLines(), pixel())
    }

    // Editable lines v1 (NATIVE_LINES_V1): ordinary lines, their looks and frozen brushes are no longer refused; the
    // metadata template keeps the document's own declarations and drops every payload map (live readback supplies them).
    @Test fun snapshotKeepsOrdinaryLinesLooksAndFrozenBrushesInTheirMetadata() {
        val lines = linesOnly()
        val meta = CanvasSnapshot.metadataOf(lines)
        assertEquals(lines.doc, meta.doc, "the document keeps naming its lines, looks and brushes")
        assertTrue(meta.tiles.isEmpty()); assertTrue(meta.strokes.isEmpty()); assertTrue(meta.looks.isEmpty())
        assertTrue(meta.brushes.isEmpty()); assertTrue(meta.slabs.isEmpty()); assertTrue(meta.mediaTiles.isEmpty())
        assertSame(lines, CanvasSnapshot.merge(null, lines))
        val strayLooks = pixel().copy(looks = mapOf(Triple("l", "c", "0_0") to LineArchiveFixture.tile(10)))
        assertTrue(CanvasSnapshot.metadataOf(strayLooks).looks.isEmpty())
    }

    @Test fun snapshotStillRefusesLooksDeclaredForACelWithNoLines() {
        val fresh = pixel()
        val declared = fresh.copy(doc = fresh.doc.copy(layers = fresh.doc.layers.map { l ->
            l.copy(cels = l.cels.map { it.copy(looks = listOf("0_0")) }) }))
        // The document itself is inconsistent (a look is only saved for a tile that lines cross), however the lines are stored.
        assertFailsWith<IllegalArgumentException> { CanvasSnapshot.metadataOf(declared) }
        assertFailsWith<JbArchiveException> { CanvasSnapshot.merge(declared, fresh) }
        assertFailsWith<JbArchiveException> { CanvasSnapshot.merge(fresh, declared) }
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
