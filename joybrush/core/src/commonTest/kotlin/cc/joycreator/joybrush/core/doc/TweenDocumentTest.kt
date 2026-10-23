package cc.joycreator.joybrush.core.doc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TweenDocumentTest {
    private fun document(frames: List<Frame> = listOf(Frame("f1"), Frame("f2"))): JbDocument {
        var id = 0
        val base = DocOps.newDocument("doc", "Test", 64, 64) { "id${id++}" }
        val canvas = base.boards.single()
        val animation = Board(
            id = "anim", name = "Animation", kind = BoardKind.ANIMATION,
            rect = RectPx(0, 0, 64, 64), frames = frames,
        )
        val layer = Layer(
            id = "animated", name = "Animated", kind = LayerKind.PAINT,
            animatedIn = animation.id,
            cels = frames.map { Cel("cel-${it.id}") },
            frameCel = frames.associate { it.id to "cel-${it.id}" },
        )
        return base.copy(boards = listOf(canvas, animation), layers = listOf(layer), activeLayerId = layer.id)
    }

    private fun JbDocument.animation() = boards.single { it.id == "anim" }

    private fun regionDocument(): JbDocument {
        var generated = 0
        val base = JbDocument(
            id = "region-doc", name = "Region test",
            boards = listOf(Board("root", "Root", BoardKind.CANVAS, RectPx(0, 0, 64, 64))),
            layers = listOf(Layer("region-layer", "Paint", LayerKind.PAINT, cels = listOf(Cel("shared")))),
        )
        return RegionDocumentOps.create(base, "Animation", RectPx(0, 0, 32, 32)) { "region-${generated++}" }.doc
    }

    @Test
    fun tweenFlagSerializesAndOldFramesDefaultToOff() {
        val base = document()
        val source = base.copy(
            boards = base.boards.map { b ->
                if (b.id == "anim") b.copy(frames = b.frames.mapIndexed { i, f -> f.copy(tween = i == 0) }) else b
            },
        )
        val encoded = DocJson.encode(source)
        assertTrue(encoded.contains("\"tween\": true"))
        assertFalse(encoded.contains("\"tween\": false"), "default false is omitted for old-reader compatibility")
        assertEquals(source, DocJson.decode(encoded))

        // A pre-tween drawing has no field; the constructor default must preserve its old meaning.
        val oldText = DocJson.encode(source.copy(version = 11)).replace(Regex("\\s*,\\s*\"tween\": (?:true|false)"), "")
        val old = DocJson.decode(oldText)
        assertTrue(old.animation().frames.all { !it.tween })
        assertEquals(11, old.version)
    }

    @Test
    fun validatorRejectsTweenOnAOneTickFrame() {
        val bad = document(listOf(Frame("f1", tween = true), Frame("f2")))
        assertTrue(DocOps.validate(bad).any { it.contains("tween") && it.contains("f1") })
        val unsupported = bad.copy(version = 13)
        assertTrue(DocOps.validate(unsupported).any { it.contains("version 14") })
    }

    @Test
    fun setTweenValidatesBoardFrameAndHoldAndCanDisableOnOneTick() {
        val oneTick = document(listOf(Frame("f1"), Frame("f2")))
        val error = assertFailsWith<DocException> { AnimOps.setTween(oneTick, "anim", "f1", true) }
        assertTrue(error.message.orEmpty().contains("hold"))

        val enabled = AnimOps.setTween(AnimOps.setHold(oneTick, "anim", "f1", 2), "anim", "f1", true)
        assertTrue(enabled.animation().frames.first().tween)
        assertEquals(DOC_VERSION, enabled.version)
        val disabled = AnimOps.setTween(enabled, "anim", "f1", false)
        assertFalse(disabled.animation().frames.first().tween)

        assertTrue(assertFailsWith<DocException> {
            AnimOps.setTween(oneTick, "missing", "f1", true)
        }.message.orEmpty().contains("board"))
        assertTrue(assertFailsWith<DocException> {
            AnimOps.setTween(oneTick, "anim", "missing", true)
        }.message.orEmpty().contains("frame"))
        assertTrue(assertFailsWith<DocException> {
            AnimOps.setTween(oneTick, oneTick.boards.first().id, "f1", true)
        }.message.orEmpty().contains("ANIMATION"))
    }

    @Test
    fun setHoldToOneClearsTweenInSameDocumentChange() {
        val source = AnimOps.setTween(AnimOps.setHold(document(), "anim", "f1", 3), "anim", "f1", true)
        val changed = AnimOps.setHold(source, "anim", "f1", 1)
        assertEquals(Frame("f1", holdFrames = 1, tween = false), changed.animation().frames.first())
        assertEquals(source.animation().frames[1], changed.animation().frames[1])
        assertEquals(emptyList(), DocOps.validate(changed))
    }

    @Test
    fun duplicateStartsTweenOffAndMovingFramesPreservesFlagsAndOtherBoards() {
        val source = AnimOps.setTween(AnimOps.setHold(document(), "anim", "f1", 3), "anim", "f1", true)
        var generated = 0
        val duplicate = AnimOps.addFrame(source, "anim", "f1", NewFrame.DUPLICATE) { "new-${generated++}" }
        val newFrame = duplicate.doc.animation().frames[1]
        assertEquals(1, newFrame.holdFrames)
        assertFalse(newFrame.tween)
        assertTrue(duplicate.doc.animation().frames.first().tween)
        assertEquals(emptyList(), DocOps.validate(duplicate.doc))

        val moved = AnimOps.moveFrame(source, "anim", "f1", 1)
        assertEquals(listOf(Frame("f2"), Frame("f1", holdFrames = 3, tween = true)), moved.animation().frames)
        assertEquals(source.boards.first(), moved.boards.first())
        assertEquals(emptyList(), DocOps.validate(moved))
    }

    @Test
    fun regionAnimationTweenOpsValidateAndKeepFlagsFrameLocal() {
        var generated = 0
        val created = regionDocument()
        val board = created.boards.last()
        val withSecond = RegionDocumentOps.addFrame(created, board.id, NewFrame.DUPLICATE) { "extra-${generated++}" }.doc
        val firstId = board.frames.single().id
        val enabled = RegionDocumentOps.setTween(
            RegionDocumentOps.setHold(withSecond, board.id, firstId, 3), board.id, firstId, true,
        )
        assertTrue(enabled.boards.last().frames.first { it.id == firstId }.tween)
        assertFalse(enabled.boards.last().frames.first { it.id != firstId }.tween)
        assertEquals(DOC_VERSION, enabled.version)

        val legacy = withSecond.copy(version = 13)
        val upgraded = RegionDocumentOps.setTween(
            RegionDocumentOps.setHold(legacy, board.id, firstId, 2), board.id, firstId, true,
        )
        assertEquals(14, upgraded.version, "enabling the new field upgrades a clip-era document")

        val duplicate = RegionDocumentOps.addFrame(enabled, board.id, NewFrame.DUPLICATE) { "extra-${generated++}" }.doc
        assertFalse(duplicate.boards.last().frames[1].tween)
        assertTrue(duplicate.boards.last().frames.first { it.id == firstId }.tween)
        val reordered = RegionDocumentOps.reorderFrames(enabled, board.id, enabled.boards.last().frames.map { it.id }.reversed())
        assertTrue(reordered.boards.last().frames.first { it.id == firstId }.tween)
        assertEquals(emptyList(), DocOps.validate(reordered))

        val cleared = RegionDocumentOps.setHold(enabled, board.id, firstId, 1)
        assertFalse(cleared.boards.last().frames.first { it.id == firstId }.tween)
        assertEquals(emptyList(), DocOps.validate(cleared))

        assertTrue(assertFailsWith<DocException> {
            RegionDocumentOps.setTween(withSecond, board.id, withSecond.boards.last().currentFrameId!!, true)
        }.message.orEmpty().contains("hold"))
        assertFailsWith<DocException> { RegionDocumentOps.setTween(enabled, "missing", firstId, true) }
        assertFailsWith<DocException> { RegionDocumentOps.setTween(enabled, board.id, "missing", true) }
    }
}
