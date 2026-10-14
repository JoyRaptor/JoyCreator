package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.io.JbArchiveException
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.androidkit.io.RecordedSnapshot
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkTiles
import cc.joycreator.joybrush.core.vector.RecordedCel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

/** Contract-only integration fixtures: the fake renderer uses CPU ink bytes for the line tile. */
class RecordedSceneSnapshotTest {
    private val brush = BrushPreset(id = "frozen-pen", name = "Pen", size = Param(8f))
    private val brushes get() = mapOf(brush.id to brush)
    private fun drawing(): JbDocument {
        var id = 0
        return DocOps.newDocument("doc", "Art", 256, 256) { "id-${++id}" }
    }

    private fun record(samples: List<PenSample> = listOf(
        PenSample(128f, 128f, 0.0), PenSample(132f, 128f, 16.0),
    )) = StrokeRecord("line", brush.id, 19L, 0f, 1f, samples, seq = 1L)

    private fun slabBytes(): ByteArray = ByteArray(RegionRenderer.TILE_BYTES).also {
        // One opaque premultiplied pixel makes the plain base payload observable in the archive.
        it[0] = 31
        it[3] = -1
    }

    private class FakeRenderer : RecordedScene.Renderer<Int> {
        private var next = 100
        val pixels = linkedMapOf<Int, ByteArray>()
        val released = mutableListOf<Int>()

        override fun line(
            address: LineAddress,
            record: StrokeRecord,
            preset: BrushPreset,
        ): RecordedScene.Unit<Int> {
            if (record.samples.isEmpty()) return RecordedScene.Unit(preset.blend, emptyMap())
            val bytes = requireNotNull(InkTiles.render(listOf(record), 0, 0) { id ->
                preset.takeIf { it.id == id }
            }.pixels) { "The fixture line should create visible ink in tile 0" }
            val texture = next++
            pixels[texture] = bytes
            return RecordedScene.Unit(preset.blend, mapOf(Tiles.key(0, 0) to texture))
        }

        override fun look(
            plane: Pair<String, String?>,
            key: Long,
            sources: List<RecordedScene.Source<Int>>,
        ): Int? {
            if (sources.isEmpty()) return null
            // This fake composes by selecting the last source. Pixel parity is outside this contract test.
            val source = requireNotNull(pixels[sources.last().texture])
            val texture = next++
            pixels[texture] = source.copyOf()
            return texture
        }

        override fun release(texture: Int) { released += texture }
    }

    private data class Fixture(
        val plane: Pair<String, String?>,
        val renderer: FakeRenderer,
        val history: UndoLog<Int>,
        val scene: RecordedScene<Int>,
    )

    private fun fixture(doc: JbDocument, cel: RecordedCel<Int>, initialPixels: Map<Int, ByteArray> = emptyMap()): Fixture {
        val layerId = doc.layers.single().id
        val address = layerId to null
        require(cel.layerId == layerId && cel.celId == null)
        val renderer = FakeRenderer()
        renderer.pixels.putAll(initialPixels.mapValues { it.value.copyOf() })
        val history = UndoLog<Int>(1_000_000, { 4 }, renderer.released::add)
        val scene = RecordedScene.create(mapOf(address to cel), brushes, history, renderer)
        return Fixture(address, renderer, history, scene)
    }

    private fun snapshot(
        doc: JbDocument,
        fixture: Fixture,
        lookRead: (String, String?, Long) -> ByteArray? = { _, _, key ->
            fixture.scene.look(fixture.plane, key)?.let { fixture.renderer.pixels[it] }
        },
    ) = RecordedSnapshot.capture(
        doc = doc,
        planes = fixture.scene.planes,
        brushes = fixture.scene.brushes,
        readTexture = { fixture.renderer.pixels[it] },
        keys = { _, _ -> emptyList() },
        read = { _, _, _ -> error("Recorded planes own their physical source tiles") },
        expectedLookKeys = mapOf(fixture.plane to fixture.scene.savedLookKeys(fixture.plane).sorted()),
        lookRead = lookRead,
    )

    private fun roundTrip(contents: JbContents) = JbArchive.read(
        ByteArrayInputStream(ByteArrayOutputStream().also { JbArchive.write(it, contents) }.toByteArray()),
    )

    @Test fun archiveKeepsOnlyLineCrossedLooksAndPreservesTheDistantPlainSlab() {
        val doc = drawing()
        val layerId = doc.layers.single().id
        val distant = Tiles.key(1, 0)
        val slab = slabBytes()
        val fixture = fixture(doc, RecordedCel(
            layerId = layerId,
            celId = null,
            records = listOf(record()),
            slabs = mapOf(distant to listOf(RecordedCel.PixelSlab(0L, UndoLog.SlabValue(7, "normal")))),
        ), mapOf(7 to slab))

        assertEquals(setOf(Tiles.key(0, 0), distant), fixture.scene.lookKeys(fixture.plane))
        assertEquals(setOf(Tiles.key(0, 0)), fixture.scene.savedLookKeys(fixture.plane))

        val captured = snapshot(doc, fixture)
        val savedCel = captured.doc.layers.single().cels.single()
        assertEquals(listOf("0_0"), savedCel.looks)
        assertContentEquals(slab, captured.tiles.getValue(Triple(layerId, savedCel.id, "1_0")))
        assertEquals(listOf(record()), captured.strokes.getValue(layerId to savedCel.id))
        assertEquals(brushes, captured.brushes)

        val loaded = roundTrip(captured)
        val loadedCel = loaded.doc.layers.single().cels.single()
        assertEquals(listOf("0_0"), loadedCel.looks)
        assertEquals(setOf(Triple(layerId, loadedCel.id, "0_0")), loaded.looks.keys)
        assertContentEquals(slab, loaded.tiles.getValue(Triple(layerId, loadedCel.id, "1_0")))
        assertEquals(listOf(record()), loaded.strokes.getValue(layerId to loadedCel.id))
        assertEquals(brushes, loaded.brushes)
    }

    @Test fun noDabLineArchivesWithAnExplicitEmptyLookIndex() {
        val doc = drawing()
        val layerId = doc.layers.single().id
        val fixture = fixture(doc, RecordedCel(layerId, null, listOf(record(samples = emptyList()))))

        assertTrue(fixture.scene.savedLookKeys(fixture.plane).isEmpty())
        val captured = snapshot(doc, fixture, lookRead = { _, _, _ -> error("No look should be read") })
        val capturedCel = captured.doc.layers.single().cels.single()
        assertTrue(capturedCel.looks.isEmpty())
        assertEquals(listOf(record(samples = emptyList())), captured.strokes.getValue(layerId to capturedCel.id))

        val loaded = roundTrip(captured)
        val cel = loaded.doc.layers.single().cels.single()
        assertTrue(cel.looks.isEmpty())
        assertTrue(loaded.looks.isEmpty())
        assertEquals(listOf(record(samples = emptyList())), loaded.strokes.getValue(layerId to cel.id))
        assertEquals(brushes, loaded.brushes)
    }

    @Test fun failedLookReadbackLeavesSceneAndHistoryReadyForSuccessfulRetry() {
        val doc = drawing()
        val layerId = doc.layers.single().id
        val fixture = fixture(doc, RecordedCel(layerId, null, listOf(record())))
        val originalPlanes = fixture.scene.planes
        val originalLookKeys = fixture.scene.lookKeys(fixture.plane)
        val expectedRecord = fixture.scene.planes.getValue(fixture.plane).records.single()

        assertFailsWith<JbArchiveException> {
            snapshot(doc, fixture, lookRead = { _, _, _ -> null })
        }
        assertSame(originalPlanes, fixture.scene.planes)
        assertEquals(originalLookKeys, fixture.scene.lookKeys(fixture.plane))
        assertFalse(fixture.history.canUndo)
        assertFalse(fixture.history.canRedo)

        val loaded = roundTrip(snapshot(doc, fixture))
        val cel = loaded.doc.layers.single().cels.single()
        assertEquals(listOf("0_0"), cel.looks)
        assertEquals(listOf(expectedRecord), loaded.strokes.getValue(layerId to cel.id))
        assertEquals(brushes, loaded.brushes)
        assertFalse(fixture.history.canUndo)
    }
}
