package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.gl.lines.NativeLines
import cc.joycreator.joybrush.androidkit.lines.*
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.*

/** Metadata proof only: the engine and physical owners are real, no EGL or replay is invoked. */
class GlLineCopyTest {
    private val brush = BrushPreset(id = "brush", name = "Ink", size = Param(6f))
    private fun record(id: String, seq: Long) = StrokeRecord(id, brush.id, 1L, 0f, 1f,
        listOf(PenSample(10f, 10f, 0.0), PenSample(48f, 10f, 8.0)), seq = seq)
    private fun native(e: GlPaintEngine) = GlPaintEngine::class.java.getDeclaredField("nativeLines")
        .also { it.isAccessible = true }.get(e) as NativeLines
    private fun engine(vararg layers: String) = GlPaintEngine().also { e ->
        e.initWith { }; layers.forEach(e::addLayer); native(e).addBrushes(mapOf(brush.id to brush))
    }
    private fun put(e: GlPaintEngine, p: LinePlane, r: StrokeRecord) { native(e).put(p, r.id, r, emptySet()) }
    private fun request(e: GlPaintEngine, selected: List<AddressedStroke>? = null): LineCopyRequest {
        val s = e.lineSnapshot()
        val all = selected ?: s.planes.flatMap { (p, rs) -> rs.map { AddressedStroke(p.address(it.id), it) } }
        return assertNotNull(LineCopyRequest.capture(s, all, Long.MAX_VALUE))
    }

    @Test fun overlapUsesRealLayerOrderAndPayloadIsOwnedWithoutHistory() {
        val e = engine("lower", "upper")
        val lower = LinePlane("lower", null); val upper = LinePlane("upper", null)
        val samples = record("red-under", 100).samples.toMutableList()
        put(e, upper, record("blue-over", 1)) // Reverse NativeLines' insertion order too.
        put(e, lower, record("red-under", 100).copy(samples = samples))
        val before = e.lineSnapshot()
        val payload = assertIs<LineCopyResult.Ready>(e.prepareLineCopy(request(e), Long.MAX_VALUE)).payload
        assertEquals(listOf("red-under", "blue-over"), payload.entries.map { it.record.id })
        assertEquals(setOf(lower, upper), payload.proof.owners)
        assertEquals(e.documentGeneration, payload.proof.documentGeneration)
        assertEquals(before.revision, payload.proof.revision)
        assertFalse(e.undo.canUndo)
        assertEquals(before.revision, e.lineSnapshot().revision)
        samples.clear()
        assertEquals(2, payload.entries.first().record.samples.size)
        assertEquals(brush, payload.entries.first().brush())
        assertFailsWith<UnsupportedOperationException> { (payload.entries as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (payload.proof.owners as MutableSet<*>).clear() }
    }

    @Test fun changedRecordOrDrawingGenerationCannotReuseARequest() {
        val e = engine("layer"); val p = LinePlane("layer", null); val r = record("a", 1)
        put(e, p, r)
        val stale = request(e)
        put(e, p, r.copy(locked = true))
        assertIs<LineCopyResult.Refused>(e.prepareLineCopy(stale, Long.MAX_VALUE))
        val another = request(e)
        GlPaintEngine::class.java.getDeclaredField("documentGeneration").also { it.isAccessible = true }
            .setLong(e, e.documentGeneration + 1)
        assertIs<LineCopyResult.Refused>(e.prepareLineCopy(another, Long.MAX_VALUE))
    }

    @Test fun exactExpectedRecordsAreCheckedEvenWithCurrentRevision() {
        val e = engine("layer"); val p = LinePlane("layer", null); val r = record("a", 1)
        put(e, p, r)
        val forgedExpectation = request(e, listOf(AddressedStroke(p.address("a"), r.copy(locked = true))))
        assertIs<LineCopyResult.Refused>(e.prepareLineCopy(forgedExpectation, Long.MAX_VALUE))
        val absent = request(e, listOf(AddressedStroke(p.address("absent"), r.copy(id = "absent"))))
        assertIs<LineCopyResult.Refused>(e.prepareLineCopy(absent, Long.MAX_VALUE))
    }

    @Test fun aNativeRecordOnAnUnknownPhysicalCelIsNotAnUnboundedProof() {
        val e = engine("layer"); val p = LinePlane("layer", "unknown")
        put(e, p, record("a", 1))
        assertIs<LineCopyResult.Refused>(e.prepareLineCopy(request(e), Long.MAX_VALUE))
        assertFalse(e.undo.canUndo)
    }

    @Test fun frameCopiesAndTheirSharedOutsideDomainBothRefuse() {
        for (copyBoth in listOf(false, true)) {
            val e = engine("layer")
            val base = JbDocument(id = "drawing", name = "Test", boards = listOf(
                Board("canvas", "Canvas", BoardKind.CANVAS, RectPx(0, 0, 100, 100))),
                layers = listOf(Layer("layer", "Layer", LayerKind.PAINT, cels = listOf(Cel("shared")))))
            var serial = 0
            val doc = RegionDocumentOps.create(base, "Frame", RectPx(20, 0, 40, 100)) { "region-${serial++}" }.doc
            e.setBoardDocument(doc)
            val plan = RegionDocumentOps.paintPlans(doc, emptyMap()).getValue("layer")
            val shared = LinePlane("layer", plan.shared.celId)
            val frame = LinePlane("layer", plan.frames.single().celId)
            put(e, shared, record("shared-copy", 1).copy(groupId = "whole-recipe"))
            if (copyBoth) put(e, frame, record("frame-copy", 1).copy(groupId = "whole-recipe"))
            val result = assertIs<LineCopyResult.Refused>(e.prepareLineCopy(request(e), Long.MAX_VALUE))
            assertTrue(result.message.contains("animation regions"))
            assertFalse(e.undo.canUndo)
        }
    }

    @Test fun anOrdinaryDocumentSharedCelCanBeProvedAndLowHeapRefuses() {
        val e = engine("layer")
        val doc = JbDocument(id = "drawing", name = "Test", boards = listOf(
            Board("canvas", "Canvas", BoardKind.CANVAS, RectPx(0, 0, 100, 100))),
            layers = listOf(Layer("layer", "Layer", LayerKind.PAINT, cels = listOf(Cel("shared")))))
        e.setBoardDocument(doc)
        put(e, LinePlane("layer", "shared"), record("a", 1))
        val req = request(e)
        assertIs<LineCopyResult.Ready>(e.prepareLineCopy(req, Long.MAX_VALUE))
        assertIs<LineCopyResult.Refused>(e.prepareLineCopy(req, 1L))
        assertFalse(e.undo.canUndo)
    }

    @Test fun hiddenClippedAndMaskedLayerOwnersRefuseWithoutHistory() {
        for (kind in listOf("hidden", "clip", "mask")) {
            val e = engine("layer"); put(e, LinePlane("layer", null), record("a", 1))
            val layers = GlPaintEngine::class.java.getDeclaredField("localLayers").also { it.isAccessible = true }
                .get(e) as Map<*, *>
            val layer = layers.getValue("layer")!!
            when (kind) {
                "hidden" -> e.setLayerVisible("layer", false)
                "clip" -> layer.javaClass.getDeclaredField("clip").also { it.isAccessible = true }.setBoolean(layer, true)
                else -> {
                    val constructor = layer.javaClass.declaredConstructors.single { it.parameterCount == 4 }
                        .also { it.isAccessible = true }
                    val mask = constructor.newInstance("mask", true, HashMap<Long, Int>(), Any())
                    layer.javaClass.getDeclaredField("mask").also { it.isAccessible = true }.set(layer, mask)
                }
            }
            assertIs<LineCopyResult.Refused>(e.prepareLineCopy(request(e), Long.MAX_VALUE))
            assertFalse(e.undo.canUndo)
        }
    }
}
