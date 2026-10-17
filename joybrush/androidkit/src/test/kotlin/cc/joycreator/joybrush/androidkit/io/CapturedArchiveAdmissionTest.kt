package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.stroke.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

/** Wire fixtures are assembled independently of the intentionally unsupported captured writer. */
class CapturedArchiveAdmissionTest {
    private val address = "l" to "c"
    private fun source() = LineArchiveFixture.contents()
    private fun captured(c: JbContents = source()): JbContents = c.copy(strokes = c.strokes.mapValues { (_, rs) ->
        rs.map { it.copy(capturedPhysicalSource = CapturedPhysicalSource(
            CaptureDomain(CaptureRect(-10, -20, 80, 90), listOf(CaptureRect(3, 4, 5, 6))), SourcePlacement(7, -8))) }
    })
    private fun read(bytes: ByteArray) = JbArchive.read(ByteArrayInputStream(bytes))
    private fun wire(c: JbContents): ByteArray {
        val base = LineArchiveFixture.entries(LineArchiveFixture.encoded(source()))
        return LineArchiveFixture.zip(base.map { (name, bytes) -> name to when (name) {
            "document.json" -> DocJson.encode(c.doc).toByteArray()
            "layers/l/c/strokes.jbs" -> StrokeCodec.encodeAll(c.strokes.getValue(address))
            else -> bytes
        } })
    }
    private fun regional(c: JbContents): JbContents {
        val animation = Board("animation", "Region", BoardKind.ANIMATION, RectPx(4, 5, 30, 40),
            frames = listOf(Frame("f")), currentFrameId = "f")
        return c.copy(doc = c.doc.copy(boards = c.doc.boards + animation, layers = c.doc.layers.map {
            it.copy(sharedCelId = "shared", cels = it.cels + Cel("shared"),
                regions = listOf(RegionFrames(animation.id, mapOf("f" to "c"))))
        }))
    }
    private fun refusedBeforeOutput(c: JbContents): String {
        val out = ByteArrayOutputStream().also { it.write(47) }
        val message = assertFailsWith<JbArchiveException> { JbArchive.write(out, c) }.message.orEmpty()
        assertContentEquals(byteArrayOf(47), out.toByteArray())
        return message
    }

    @Test fun ordinaryStrokeSaveUpgradesOnlyOutputMetadata() {
        val c = source().let { it.copy(doc = it.doc.copy(version = 10)) }
        val back = read(LineArchiveFixture.encoded(c))
        assertEquals(11, back.doc.version)
        assertEquals(10, c.doc.version)
        assertEquals(c.strokes.mapValues { it.value.sortedBy { r -> r.seq } }, back.strokes)
    }

    @Test fun rasterOnlyOldHeaderIsPreserved() {
        val c = source().let { it.copy(doc = it.doc.copy(version = 9, brushes = emptyList(), layers = it.doc.layers.map { l ->
            l.copy(cels = listOf(Cel("c", tiles = listOf("0_0"))))
        }), strokes = emptyMap(), slabs = emptyMap(), looks = emptyMap(), brushes = emptyMap()) }
        assertEquals(9, read(LineArchiveFixture.encoded(c)).doc.version)
    }

    @Test fun capturedWireReadsItsExactDomainPlacementAndRecipeWithoutReplay() {
        val c = captured()
        val back = read(wire(c))
        val expected = c.strokes.getValue(address).sortedBy { it.seq }
        val actual = back.strokes.getValue(address)
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (a, b) -> assertTrue(CapturedStrokeRules.sameRecord(a, b)) }
        assertContentEquals(c.looks.values.single(), back.looks.values.single())
        JbArchive.requireStructure(back)
    }

    @Test fun capturedUnderVersionTenRefusesOnReadAndBeforeWrite() {
        val c = captured().let { it.copy(doc = it.doc.copy(version = 10)) }
        assertTrue(assertFailsWith<JbArchiveException> { read(wire(c)) }.message.orEmpty().contains("version11"))
        assertTrue(refusedBeforeOutput(c).contains("version11"))
    }

    @Test fun capturedSavedLooksCannotUseLegacyUnknownReplayShortcut() {
        assertTrue(refusedBeforeOutput(captured()).contains("clipping-aware"))
    }

    @Test fun capturedWithNoLookIndexStillCannotSave() {
        val c = captured().let { it.copy(doc = it.doc.copy(layers = it.doc.layers.map { l ->
            l.copy(cels = l.cels.map { cel -> cel.copy(looks = emptyList()) })
        }), looks = emptyMap()) }
        JbArchive.requireStructure(c)
        assertTrue(refusedBeforeOutput(c).contains("clipping-aware"))
    }

    @Test fun invalidCapturedNumericsUseArchiveExceptionBeforeSerialization() {
        val c = captured().let { it.copy(strokes = it.strokes.mapValues { (_, rs) -> rs.map { r -> r.copy(widthScale = Float.NaN) } }) }
        assertFailsWith<JbArchiveException> { JbArchive.requireStructure(c) }
        assertTrue(refusedBeforeOutput(c).contains("widthScale"))
    }

    // Editable lines v1 (NATIVE_LINES_V1): an ordinary line on a regional layer needs no captured source; the plane's
    // current regions clip it when it is re-rendered. What must still hold: resaving never INVENTS a captured domain.
    @Test fun legacyRegionalNullSourceReadsAndResavesAsOrdinaryWithoutInventingADomain() {
        val c = regional(source()).let { it.copy(doc = it.doc.copy(version = 10)) }
        val back = read(wire(c))
        assertEquals(10, back.doc.version)
        assertTrue(back.strokes.getValue(address).all { it.capturedPhysicalSource == null })
        JbArchive.requireStructure(back)
        val again = read(LineArchiveFixture.encoded(back))
        assertEquals(11, again.doc.version, "only the output metadata is upgraded")
        assertEquals(back.strokes, again.strokes)
        assertTrue(again.strokes.getValue(address).all { it.capturedPhysicalSource == null })
    }

    @Test fun currentRegionalNullSourceIsAdmittedOnReadStructureAndWrite() {
        val c = regional(source())
        assertEquals(11, c.doc.version)
        val back = read(wire(c))
        JbArchive.requireStructure(c)
        assertEquals(c.strokes.mapValues { it.value.sortedBy { r -> r.seq } }, back.strokes)
        assertTrue(back.strokes.getValue(address).all { it.capturedPhysicalSource == null })
        assertEquals(back.strokes, read(LineArchiveFixture.encoded(c)).strokes)
    }

    @Test fun capturedLineOnARegionalLayerKeepsItsDomainAndStillRefusesTheWriter() {
        val c = captured(regional(source()))
        val back = read(wire(c))
        assertTrue(back.strokes.getValue(address).all { it.capturedPhysicalSource != null })
        JbArchive.requireStructure(back)
        assertTrue(refusedBeforeOutput(back).contains("clipping-aware"))
    }

    @Test fun legacyRegionalInkMigrationStopsAtTenAndResavesAsOrdinaryWithoutAssertingCapturedOwnership() {
        val entries = LineArchiveFixture.entries(LineArchiveFixture.oldInk())
        val oldDoc = JbDocument(version = 9, id = "old", name = "Old ink",
            boards = listOf(Board("b", "Region", BoardKind.ANIMATION, RectPx(0, 0, 64, 64),
                frames = listOf(Frame("f")), currentFrameId = "f")),
            layers = listOf(Layer("l", "Ink", LayerKind.INK, sharedCelId = "shared",
                cels = listOf(Cel("shared"), Cel("c", strokesFile = "layers/l/c/strokes.jbs")),
                regions = listOf(RegionFrames("b", mapOf("f" to "c"))))))
        val bytes = LineArchiveFixture.zip(entries.map { (name, payload) -> name to
            if (name == "document.json") DocJson.encode(oldDoc).toByteArray() else payload })
        val back = JbArchive.read(ByteArrayInputStream(bytes)) { LineArchiveFixture.brush }
        assertEquals(10, back.doc.version)
        assertEquals(LayerKind.PAINT, back.doc.layers.single().kind)
        assertEquals(2, back.strokes.getValue(address).size)
        assertTrue(back.strokes.getValue(address).all { it.capturedPhysicalSource == null })
        val again = read(LineArchiveFixture.encoded(back))
        assertEquals(11, again.doc.version)
        assertEquals(back.strokes, again.strokes)
        assertEquals(back.brushes, again.brushes)
    }
}
