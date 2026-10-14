package cc.joycreator.joybrush.android.board

import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.io.*
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.media.MediaStores
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual public Open/load admission on unattached surfaces; no GPU execution is claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasLoadAdmissionTest {
    private val surfaces = ArrayList<JbCanvasView>()
    @After fun pauseSurfaces() { surfaces.forEach { it.onPause() } }

    private fun field(view: JbCanvasView, name: String): Any? =
        JbCanvasView::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(view)
    private fun set(view: JbCanvasView, name: String, value: Any?) =
        JbCanvasView::class.java.getDeclaredField(name).also { it.isAccessible = true }.set(view, value)
    private fun surface(): JbCanvasView = JbCanvasView(RuntimeEnvironment.getApplication()).also {
        surfaces.add(it)
        set(it, "documentName", "Keep this drawing")
        set(it, "pageW", 777); set(it, "pageH", 999); set(it, "paperRevision", 37L)
        (field(it, "engine") as GlPaintEngine).addLayer("owner-sentinel")
    }
    private fun raster(): JbContents {
        val doc = JbDocument(id = "load-fixture", name = "Incoming drawing",
            boards = listOf(Board("board", "Board", BoardKind.CANVAS, RectPx(0, 0, 64, 64))),
            layers = listOf(Layer("paint", "Paint", LayerKind.PAINT,
                cels = listOf(Cel("cel", listOf("0_0"))))),
            activeLayerId = "paint", activeBoardId = "board")
        return JbContents(doc, mapOf(Triple("paint", "cel", "0_0") to ByteArray(TILE_BYTES)), emptyMap())
    }
    private fun cel(base: JbContents, change: (Cel) -> Cel) = base.copy(doc = base.doc.copy(
        layers = base.doc.layers.map { it.copy(cels = it.cels.map(change)) }))

    private fun requireRefusal(view: JbCanvasView, contents: JbContents) {
        val refs = listOf("stackUi", "retainedContents", "engine").associateWith { field(view, it) }
        val values = listOf("activeLayer", "editingMask", "paperRevision", "pageW", "pageH", "documentName")
            .associateWith { field(view, it) }
        val queues = listOf("frameWork", "afterFrameWork", "pendingPaperVisits")
            .associateWith { (field(view, it) as Collection<*>).toList() }
        val engine = refs.getValue("engine") as GlPaintEngine
        val owners = engine.layerIds().toList()
        val cursor = engine.undo.snapshotCursor()
        fun unchanged() {
            refs.forEach { (name, value) -> assertSame(name, value, field(view, name)) }
            values.forEach { (name, value) -> assertEquals(name, value, field(view, name)) }
            queues.forEach { (name, value) -> assertEquals(name, value, (field(view, name) as Collection<*>).toList()) }
            assertEquals(owners, engine.layerIds()); assertSame(cursor, engine.undo.snapshotCursor())
        }
        try { view.checkContents(contents); fail("Open admission must refuse the complete payload") }
        catch (_: JbArchiveException) { }
        unchanged()
        var completed = false
        try { view.load(contents) { completed = true }; fail("Load must refuse before queuing replacement") }
        catch (_: JbArchiveException) { }
        assertFalse(completed); unchanged()
    }

    @Test fun upperSlabAndLookBodiesRefuseBeforeEitherPublicEntryMutatesOrQueues() {
        val view = surface(); val base = raster(); val bytes = ByteArray(TILE_BYTES)
        val header = cel(base) { it.copy(slabs = listOf(SlabRef("0_0", 0), SlabRef("0_0", 2, "erase"))) }
        requireRefusal(view, header.copy(slabs = mapOf(SlabKey("paint", "cel", "0_0", 2) to bytes)))
        requireRefusal(view, base.copy(slabs = mapOf(SlabKey("paint", "cel", "0_0", 2) to bytes)))
        requireRefusal(view, base.copy(looks = mapOf(Triple("paint", "cel", "0_0") to bytes)))
    }

    @Test fun recordedCelHeadersRefuseWithoutDependingOnBodyMaps() {
        val view = surface(); val base = raster()
        requireRefusal(view, cel(base) { it.copy(slabs = listOf(SlabRef("0_0", 0), SlabRef("0_0", 2))) })
        requireRefusal(view, cel(base) { it.copy(looks = listOf("0_0")) })
        requireRefusal(view, cel(base) { it.copy(strokesFile = "layers/paint/cel/strokes.jbs") })
    }

    @Test fun frozenBrushCopiesRefuseFromHeaderBodyOrBoth() {
        val view = surface(); val base = raster(); val brush = BrushPreset(id = "frozen", name = "Frozen", size = Param(4f))
        requireRefusal(view, base.copy(doc = base.doc.copy(brushes = listOf(brush.id))))
        requireRefusal(view, base.copy(brushes = mapOf(brush.id to brush)))
        requireRefusal(view, base.copy(doc = base.doc.copy(brushes = listOf(brush.id)), brushes = mapOf(brush.id to brush)))
    }

    @Test fun invalidRetainedMetadataRefusesOnCallerThreadAsArchiveError() {
        val view = surface(); val base = raster()
        requireRefusal(view, base.copy(doc = base.doc.copy(layers = base.doc.layers.map { it.copy(opacity = 2f) })))
    }

    @Test fun ordinaryRasterMediaAndEmptyMaskRemainAdmitted() {
        val view = surface(); val base = raster()
        view.checkContents(base)
        val withMedia = cel(base) { it.copy(floatTiles = listOf("0_0")) }.copy(
            mediaTiles = mapOf(MediaTileKey("paint", "cel", "0_0", "p0") to
                ByteArray(MediaStores.tileBytes(TILE_SIZE, "p0"))))
        view.checkContents(withMedia)
        view.checkContents(base.copy(doc = base.doc.copy(layers = base.doc.layers.map { it.copy(mask = Cel("mask")) })))
    }

    @Test fun regionalAnimationKeepsAllPhysicalCelsAndBoardsDuringMetadataAdmission() {
        val view = surface(); var serial = 0
        val original = DocOps.newDocument("regional", "Regional", 128, 128) { "region-${++serial}" }
        val regional = RegionDocumentOps.create(original, "Loop", RectPx(0, 0, 64, 64)) { "region-${++serial}" }.doc
        assertTrue(DocOps.validate(regional).isEmpty())
        val contents = JbContents(regional, emptyMap(), emptyMap())
        view.checkContents(contents)
        assertSame(regional, CanvasSnapshot.metadataOf(contents).doc)
        assertTrue(regional.boards.any { it.kind == BoardKind.ANIMATION })
        assertTrue(regional.layers.any { it.regions.isNotEmpty() })
    }
}
