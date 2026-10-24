package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.select.Homography
import cc.joycreator.joybrush.core.select.LineTransform
import cc.joycreator.joybrush.core.stroke.StrokeWashFrame
import cc.joycreator.joybrush.core.vector.InkReplay
import java.io.ByteArrayInputStream
import kotlin.test.*

/** Archive/recipe proof; the saved look bytes here are fixtures, not a GPU appearance comparison. */
class WatercolourLineArchiveTest {
    @Test fun aFramedWashReopensAndEditsUsingOnlyItsFrozenBrush() {
        val brush = BrushPreset(id = "archived-private-wash", name = "Archived wash",
            version = VERSION_WATERCOLOUR, engine = ENGINE_WATERCOLOUR,
            size = Param(300f), watercolour = WatercolourSpec())
        val frame = assertNotNull(StrokeWashFrame.IDENTITY.transformed(Homography.translate(17.0, -23.0)))
        val fixture = LineArchiveFixture.linesOnly()
        val source = fixture.copy(doc = fixture.doc.copy(brushes = listOf(brush.id)),
            brushes = mapOf(brush.id to brush),
            strokes = fixture.strokes.mapValues { (_, rs) -> rs.map { it.copy(brushId = brush.id, washFrame = frame) } })
        val encoded = LineArchiveFixture.encoded(source)
        var externalLookups = 0
        val reopened = JbArchive.read(ByteArrayInputStream(encoded)) {
            externalLookups++
            error("The external brush library is absent")
        }
        assertEquals(0, externalLookups)
        assertEquals(source.brushes, reopened.brushes)
        assertEquals(source.strokes.mapValues { (_, rs) -> rs.sortedBy { it.seq } }, reopened.strokes)
        for ((key, pixels) in source.looks) assertContentEquals(pixels, reopened.looks.getValue(key))
        for ((key, pixels) in source.tiles) assertContentEquals(pixels, reopened.tiles.getValue(key))
        val before = reopened.strokes.values.first().first()
        val moved = assertNotNull(LineTransform.apply(before, Homography.translate(8.0, 5.0), LineTransform.Weight.KEEP))
        assertEquals(frame.transformed(Homography.translate(8.0, 5.0)), moved.washFrame)
        val frozen = reopened.brushes.getValue(moved.brushId)
        assertEquals(InkReplay.dabs(before, frozen).size, InkReplay.dabs(moved, frozen).size)
        val resaved = reopened.copy(strokes = reopened.strokes.mapValues { (_, rs) ->
            rs.map { if (it.id == before.id) moved else it }
        })
        val back = JbArchive.read(ByteArrayInputStream(LineArchiveFixture.encoded(resaved)), null)
        assertEquals(resaved.strokes, back.strokes)
        assertEquals(resaved.brushes, back.brushes)
    }
}
