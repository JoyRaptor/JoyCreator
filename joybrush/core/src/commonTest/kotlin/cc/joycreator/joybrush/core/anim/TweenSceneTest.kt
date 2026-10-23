package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.RegionFrames
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class TweenSceneTest {
    private fun record(id: String, x: Float, seq: Long = 1L) = StrokeRecord(
        id = id,
        brushId = "frozen-brush",
        seed = 31L,
        smoothing = 0f,
        screenPerDoc = 1f,
        samples = listOf(PenSample(x, 0f, 0.0), PenSample(x + 1f, 0f, 8.0)),
        seq = seq,
    )

    private fun layer(
        id: String,
        cels: List<Cel>,
        mapping: Map<String, String> = mapOf("a" to "${id}a", "b" to "${id}b"),
        held: Boolean = false,
    ) = Layer(
        id = id,
        name = id,
        kind = LayerKind.PAINT,
        sharedCelId = "${id}-shared",
        cels = listOf(Cel("${id}-shared")) + cels,
        regions = listOf(RegionFrames("anim", mapping, held)),
    )

    private fun document(vararg layers: Layer) = JbDocument(
        id = "doc",
        name = "Doc",
        boards = listOf(
            Board(
                id = "anim",
                name = "Animation",
                kind = BoardKind.ANIMATION,
                rect = RectPx(0, 0, 32, 32),
                frames = listOf(Frame("a", holdFrames = 2, tween = true), Frame("b")), currentFrameId = "a",
            ),
        ),
        layers = layers.toList(),
    )

    @Test
    fun tweenUsesEachLayersOwnCelsAndKeepsSourceOrderAndFrozenBrushIds() {
        val la = layer("L", listOf(Cel("La", strokesFile = "La.lines"), Cel("Lb", strokesFile = "Lb.lines")))
        val other = layer("M", listOf(Cel("Ma", strokesFile = "Ma.lines"), Cel("Mb", strokesFile = "Mb.lines")))
        val a = listOf(record("shared", 0f, 7), record("source-only", 4f, 8))
        val b = listOf(record("shared", 2f, 99), record("target-only", 6f, 100))
        val ma = listOf(record("shared", 20f, 7), record("source-only", 24f, 8))
        val mb = listOf(record("shared", 40f, 99), record("target-only", 46f, 100))
        val calls = mutableListOf<Pair<String, String>>()
        val scene = TweenScene.build(document(la, other), "anim", TweenFrameSample.atTick(
            listOf(Frame("a", 2, true), Frame("b")), 1L,
        )) { layerId, celId ->
            calls += layerId to celId
            when (layerId to celId) {
                "L" to "La" -> a
                "L" to "Lb" -> b
                "M" to "Ma" -> ma
                "M" to "Mb" -> mb
                else -> error("unexpected stream request: $layerId/$celId")
            }
        }

        assertEquals(listOf("L" to "La", "L" to "Lb", "M" to "Ma", "M" to "Mb"), calls)
        assertEquals(listOf("La" to "Lb", "Ma" to "Mb"), scene.layers.map { it.sourceCelId to it.targetCelId })
        for (layerScene in scene.layers) {
            assertEquals(listOf("shared", "source-only"), layerScene.records.map { it.id })
            assertEquals(listOf(7L, 8L), layerScene.records.map { it.seq })
            assertEquals(listOf("frozen-brush", "frozen-brush"), layerScene.records.map { it.brushId })
            assertEquals(if (layerScene.layerId == "L") 1f else 30f, layerScene.records.first().samples.first().x)
        }
    }

    @Test
    fun zeroFractionDoesNotReadTargetAndReturnsSourceRecordsUnchanged() {
        val doc = document(layer("L", listOf(Cel("La", strokesFile = "La.lines"), Cel("Lb", strokesFile = "Lb.lines"))))
        val source = listOf(record("line", 3f))
        val calls = mutableListOf<String>()
        val scene = TweenScene.build(doc, "anim", TweenFrameSample.atTick(doc.boards.single().frames, 0L)) { _, celId ->
            calls += celId
            if (celId == "La") source else error("target should not be read at t=0")
        }
        assertEquals(listOf("La"), calls)
        assertSame(source, scene.layers.single().records)
    }

    @Test
    fun sharedPhysicalCelIsReturnedUntouchedWithoutSecondRead() {
        val sharedLayer = layer(
            "L",
            listOf(Cel("shared", strokesFile = "shared.lines")),
            mapping = mapOf("a" to "shared", "b" to "shared"),
        )
        val source = listOf(record("line", 3f))
        var reads = 0
        val scene = TweenScene.build(document(sharedLayer), "anim", TweenFrameSample.atTick(
            listOf(Frame("a", 2, true), Frame("b")), 1L,
        )) { _, _ -> reads++; source }
        assertEquals(1, reads)
        assertEquals("shared", scene.layers.single().targetCelId)
        assertSame(source, scene.layers.single().records)
    }

    @Test
    fun heldLayerIsOmittedAndCelsWithoutStreamsNeedNoLoader() {
        val held = layer("held", listOf(Cel("helda"), Cel("heldb")), held = true)
        val empty = layer("empty", listOf(Cel("emptya"), Cel("emptyb")))
        var reads = 0
        val scene = TweenScene.build(document(held, empty), "anim", TweenFrameSample.atTick(
            listOf(Frame("a", 2, true), Frame("b")), 1L,
        )) { _, _ -> reads++; error("cels without streams must not be read") }
        assertEquals(listOf("empty"), scene.layers.map { it.layerId })
        assertEquals(emptyList(), scene.layers.single().records)
        assertEquals(0, reads)
    }

    @Test
    fun missingDeclaredStreamIsAnError() {
        val doc = document(layer("L", listOf(Cel("La", strokesFile = "La.lines"), Cel("Lb", strokesFile = "Lb.lines"))))
        assertFailsWith<DocException> {
            TweenScene.build(doc, "anim", TweenFrameSample.atTick(doc.boards.single().frames, 0L)) { _, _ -> null }
        }
    }

    @Test
    fun nonTweenSampleUsesSourceCelForBothAddresses() {
        val original = document(layer("L", listOf(Cel("La", strokesFile = "La.lines"), Cel("Lb", strokesFile = "Lb.lines"))))
        val doc = original.copy(boards = original.boards.map { b -> b.copy(frames = b.frames.map { it.copy(tween = false) }) })
        val sample = TweenFrameSample.atTick(doc.boards.single().frames, 1L)
        val source = listOf(record("line", 3f))
        val scene = TweenScene.build(doc, "anim", sample) { _, _ -> source }
        assertEquals(null, scene.targetFrameId)
        assertEquals("La", scene.layers.single().sourceCelId)
        assertEquals("La", scene.layers.single().targetCelId)
        assertSame(source, scene.layers.single().records)
    }

    @Test
    fun malformedSamplesAndNonAnimationBoardsAreRejected() {
        val doc = document(layer("L", listOf(Cel("La"), Cel("Lb"))))
        val frames = doc.boards.single().frames
        val valid = TweenFrameSample.atTick(frames, 0L)
        val invalidSamples = listOf(
            valid.copy(nextFrameId = null, nextFrameIndex = null, fraction = 0.0),
            valid.copy(nextFrameIndex = 0),
            valid.copy(nextFrameId = "missing"),
            valid.copy(fraction = 1.0),
            valid.copy(fraction = Double.NaN),
            valid.copy(fraction = -0.1),
            valid.copy(frameIndex = 1),
        )
        for (sample in invalidSamples) {
            assertFailsWith<DocException> { TweenScene.build(doc, "anim", sample) { _, _ -> emptyList() } }
        }
        assertFailsWith<DocException> { TweenScene.build(doc, "missing", valid) { _, _ -> emptyList() } }
        val canvas = doc.copy(boards = listOf(doc.boards.single().copy(kind = BoardKind.CANVAS)))
        assertFailsWith<DocException> { TweenScene.build(canvas, "anim", valid) { _, _ -> emptyList() } }
        val duplicate = doc.copy(boards = listOf(doc.boards.single().copy(frames = listOf(frames[0], frames[0]))))
        assertFailsWith<DocException> { TweenScene.build(duplicate, "anim", valid) { _, _ -> emptyList() } }
    }
}
