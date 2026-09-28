package cc.joycreator.joybrush.core.doc

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The animation board's frame operations, JB-3.01.
 *
 * The oracle in every test here is [DocOps.validate]. An operation that produces a document the
 * validator dislikes has produced something a renderer cannot draw, so each test asserts the
 * document is clean and then asserts the exact shape it expected — in that order, because a test
 * that only checks the shape would pass just as happily on a broken document.
 *
 * The fixture is the one the spec asks for: [DocOps.newDocument]'s document with an ANIMATION board
 * at 12 fps beside the canvas, a PAINT layer animated in it and a PAINT layer that is not. One tick
 * at 12 fps is 1000 / 12 ms, so three ticks is exactly 250 ms — which is why the timing tests use
 * holds of 3 and 1 rather than anything awkward.
 */
class AnimOpsTest {

    /** Ids `gen0`, `gen1`, ... in the order they are asked for, so a failure can name the cel. */
    private class Ids(start: Int = 0) {
        private var count = start
        fun next(): String {
            val id = "gen$count"
            count++
            return id
        }
    }

    /**
     * An id factory that hands out [names] in order and then `gen100`, `gen101`, ...
     *
     * For the tests where the generator itself is the thing under test: a caller whose generator
     * repeats an id has a bug, and the operation must say so rather than build a document that fails
     * [DocOps.validate].
     */
    private fun fixedIds(vararg names: String): () -> String {
        var i = 0
        return {
            val name = if (i < names.size) names[i] else "gen${100 + i}"
            i++
            name
        }
    }

    /**
     * One canvas board, one ANIMATION board at 12 fps holding [frames], one static PAINT layer and
     * one PAINT layer animated in the animation board (every frame showing its single cel `c-anim`).
     */
    private fun fixture(frames: List<Frame> = listOf(Frame("f1"))): JbDocument {
        var n = 0
        val base = DocOps.newDocument("doc", "Test", 800, 600) { "auto${n++}" }
        val canvas = base.boards.single().copy(id = "b-canvas", name = "Canvas")
        val anim = Board(
            id = "b-anim", name = "Anim", kind = BoardKind.ANIMATION,
            rect = RectPx(0, 0, 128, 64), fps = 12f, frames = frames,
        )
        val background = Layer(id = "l-static", name = "Static", kind = LayerKind.PAINT, cels = listOf(Cel("c-static")))
        val animated = Layer(
            id = "l-anim", name = "Anim", kind = LayerKind.PAINT, animatedIn = "b-anim",
            cels = listOf(Cel("c-anim")), frameCel = frames.associate { it.id to "c-anim" },
        )
        return base.copy(
            boards = listOf(canvas, anim),
            layers = listOf(background, animated),
            activeLayerId = "l-anim",
            activeBoardId = "b-anim",
        )
    }

    /** A second layer animated in the SAME board, an INK one, so cels and layers are not 1:1. */
    private fun JbDocument.withSecondAnimatedLayer(): JbDocument {
        val board = boards.first { it.id == "b-anim" }
        val layer = Layer(
            id = "l-anim2", name = "Anim2", kind = LayerKind.INK, animatedIn = "b-anim",
            cels = listOf(Cel("c-anim2", strokesFile = "strokes/ink.jsonl")),
            frameCel = board.frames.associate { it.id to "c-anim2" },
        )
        return copy(layers = layers + layer)
    }

    /** The same document plus a second ANIMATION board, so "animated in THIS board" is testable. */
    private fun JbDocument.withSecondAnimationBoard(): JbDocument {
        val other = Board(
            id = "b-anim2", name = "Anim2", kind = BoardKind.ANIMATION,
            rect = RectPx(0, 0, 64, 64), fps = 6f, frames = listOf(Frame("g1"), Frame("g2")),
        )
        val layer = Layer(
            id = "l-other", name = "Other", kind = LayerKind.PAINT, animatedIn = "b-anim2",
            cels = listOf(Cel("c-other")), frameCel = mapOf("g1" to "c-other", "g2" to "c-other"),
        )
        return copy(boards = boards + other, layers = layers + layer)
    }

    private fun JbDocument.board(id: String = "b-anim"): Board = boards.first { it.id == id }

    private fun JbDocument.layer(id: String): Layer = layers.first { it.id == id }

    private fun JbDocument.frameIds(id: String = "b-anim"): List<String> = board(id).frames.map { it.id }

    /** The fixture with its ANIMATION board swapped, so a board the validator dislikes can be tested. */
    private fun JbDocument.replaceBoard(replacement: Board): JbDocument =
        copy(
            boards = boards.map { if (it.id == replacement.id) replacement else it },
            activeBoardId = replacement.id,
        )

    /** The one tick of a 12 fps board, as the expression the production code uses. */
    private val tickMs: Double get() = 1000.0 / 12.0

    // ---------- 1. animateLayer ----------

    @Test
    fun animateLayerPointsEveryFrameAtTheOriginalCel() {
        val doc = fixture(listOf(Frame("f1"), Frame("f2"), Frame("f3")))
        val after = AnimOps.animateLayer(doc, "l-static", "b-anim")

        val animated = after.layer("l-static")
        assertEquals("b-anim", animated.animatedIn)
        assertEquals(
            mapOf("f1" to "c-static", "f2" to "c-static", "f3" to "c-static"),
            animated.frameCel,
            "every frame, not just the first: a later frame with no cel is a hole",
        )
        assertEquals(listOf("c-static"), animated.cels.map { it.id }, "and no cel is invented or lost")
        assertEquals(emptyList(), DocOps.validate(after))
    }

    @Test
    fun animateLayerLeavesTheOtherLayersAndBoardsAlone() {
        val doc = fixture(listOf(Frame("f1"), Frame("f2")))
        val after = AnimOps.animateLayer(doc, "l-static", "b-anim")

        assertEquals(doc.layer("l-anim"), after.layer("l-anim"), "a layer already animated is untouched")
        assertEquals(doc.board("b-canvas"), after.board("b-canvas"))
        assertEquals(listOf("f1", "f2"), after.frameIds())
        assertEquals(doc.id, after.id)
        assertEquals("Test", after.name)
    }

    @Test
    fun animateLayerRefusesWhatItCannotDo() {
        val doc = fixture(listOf(Frame("f1"), Frame("f2")))
        // already animated on a board — a layer animates on one board or none
        assertFailsWith<DocException> { AnimOps.animateLayer(doc, "l-anim", "b-anim") }
        // a board that is not an ANIMATION board has no frames to animate across
        assertFailsWith<DocException> { AnimOps.animateLayer(doc, "l-static", "b-canvas") }
        // unknown ids are named, not guessed
        assertFailsWith<DocException> { AnimOps.animateLayer(doc, "nope", "b-anim") }
        assertFailsWith<DocException> { AnimOps.animateLayer(doc, "l-static", "nope") }
        // and a board with no frames is not somewhere a layer can go yet
        assertFailsWith<DocException> { AnimOps.animateLayer(fixture(emptyList()), "l-static", "b-anim") }
    }

    @Test
    fun animateLayerOnAOneFrameBoardIsTheSameStory() {
        val after = AnimOps.animateLayer(fixture(), "l-static", "b-anim")
        assertEquals(mapOf("f1" to "c-static"), after.layer("l-static").frameCel)
        assertEquals(emptyList(), DocOps.validate(after))
    }

    // ---------- 2. addFrame ----------

    @Test
    fun addBlankFrameGivesEveryAnimatedLayerANewEmptyCel() {
        val doc = fixture()
        val result = AnimOps.addFrame(doc, "b-anim", "f1", NewFrame.BLANK, Ids()::next)

        assertEquals(emptyList(), DocOps.validate(result.doc))
        assertEquals(listOf("f1", "gen0"), result.doc.frameIds(), "inserted after the frame it was given")
        assertEquals(1, result.doc.board().frames[1].holdFrames, "a new frame is held for one tick")
        val layer = result.doc.layer("l-anim")
        assertEquals(listOf("c-anim", "gen1"), layer.cels.map { it.id }, "gen0 was the frame, gen1 the cel")
        assertEquals(mapOf("f1" to "c-anim", "gen0" to "gen1"), layer.frameCel)
        assertEquals(Cel("gen1"), layer.cels.last(), "a blank cel is empty: no tiles, no strokes")
        assertEquals(emptyList(), result.work, "there is nothing for the engine to paint")
    }

    @Test
    fun addDuplicateFrameAsksTheEngineToCopyTheSourceCel() {
        val result = AnimOps.addFrame(fixture(), "b-anim", "f1", NewFrame.DUPLICATE, Ids()::next)

        assertEquals(emptyList(), DocOps.validate(result.doc))
        assertEquals(listOf("c-anim", "gen1"), result.doc.layer("l-anim").cels.map { it.id })
        assertEquals(
            listOf(CelWork.CopyCel(layerId = "l-anim", fromCelId = "c-anim", toCelId = "gen1")),
            result.work,
            "a copy, into a cel that did not exist before",
        )
    }

    @Test
    fun addLinkedFramePointsTwoFramesAtOneCelAndAsksForNothing() {
        val result = AnimOps.addFrame(fixture(), "b-anim", "f1", NewFrame.LINK, Ids()::next)

        assertEquals(emptyList(), DocOps.validate(result.doc))
        assertEquals(listOf("f1", "gen0"), result.doc.frameIds())
        assertEquals(
            mapOf("f1" to "c-anim", "gen0" to "c-anim"),
            result.doc.layer("l-anim").frameCel,
            "the SAME cel, which is how a hold is stored",
        )
        assertEquals(listOf("c-anim"), result.doc.layer("l-anim").cels.map { it.id }, "no second cel")
        assertEquals(emptyList(), result.work, "linking needs no pixels")
    }

    @Test
    fun addFrameAtTheStartCopiesTheFrameItLandsOn() {
        // Two frames, both linked to c-anim, then a DUPLICATE in front of them. The source of a
        // frame inserted at the start is the frame that is currently first.
        val doc = fixture(listOf(Frame("f1"), Frame("f2")))
        val result = AnimOps.addFrame(doc, "b-anim", null, NewFrame.DUPLICATE, Ids()::next)

        assertEquals(emptyList(), DocOps.validate(result.doc))
        assertEquals(listOf("gen0", "f1", "f2"), result.doc.frameIds())
        assertEquals(
            listOf(CelWork.CopyCel(layerId = "l-anim", fromCelId = "c-anim", toCelId = "gen1")),
            result.work,
        )
    }

    @Test
    fun addBlankFrameToAnEmptyBoardIsHowThatBoardIsFixed() {
        val broken = fixture(emptyList())
        assertEquals(1, DocOps.validate(broken).size, "an ANIMATION board with no frames is not a board")
        assertTrue(DocOps.validate(broken).single().contains("no frames"))

        val result = AnimOps.addFrame(broken, "b-anim", null, NewFrame.BLANK, Ids()::next)
        assertEquals(listOf("gen0"), result.doc.frameIds())
        assertEquals(mapOf("gen0" to "gen1"), result.doc.layer("l-anim").frameCel)
        assertEquals(emptyList(), DocOps.validate(result.doc), "and now it is a board again")
    }

    /**
     * The ONE test that pins the id allocation order on purpose: the new frame first, then one cel
     * per animated layer from the bottom of the document upwards, as [AnimOps.addFrame] documents it.
     *
     * Everywhere else in this file asserts STRUCTURE instead — that there are two cels, that the
     * mapping is right, that nothing is orphaned — because a generation counter is an accident of
     * the caller's generator rather than a promise. This one is the contract, so it is checked
     * against the contract, and a failure here means the documented order changed.
     */
    @Test
    fun addFrameCoversEveryAnimatedLayerInTheBoard() {
        val doc = fixture().withSecondAnimatedLayer()
        val result = AnimOps.addFrame(doc, "b-anim", "f1", NewFrame.DUPLICATE, Ids()::next)

        assertEquals(emptyList(), DocOps.validate(result.doc))
        // gen0 the frame, then one cel per animated layer from the bottom up
        assertEquals(listOf("f1", "gen0"), result.doc.frameIds())
        assertEquals(listOf("c-anim", "gen1"), result.doc.layer("l-anim").cels.map { it.id })
        assertEquals(listOf("c-anim2", "gen2"), result.doc.layer("l-anim2").cels.map { it.id })
        assertEquals(
            listOf(
                CelWork.CopyCel(layerId = "l-anim", fromCelId = "c-anim", toCelId = "gen1"),
                CelWork.CopyCel(layerId = "l-anim2", fromCelId = "c-anim2", toCelId = "gen2"),
            ),
            result.work,
        )
    }

    @Test
    fun addFrameLeavesLayersThatDoNotAnimateHereAlone() {
        val doc = fixture().withSecondAnimationBoard()
        val result = AnimOps.addFrame(doc, "b-anim", "f1", NewFrame.BLANK, Ids()::next)

        assertEquals(emptyList(), DocOps.validate(result.doc))
        assertEquals(doc.layer("l-static"), result.doc.layer("l-static"), "a static layer is a held background")
        assertEquals(
            doc.layer("l-other"),
            result.doc.layer("l-other"),
            "a layer animated on ANOTHER board belongs to that board's film strip",
        )
        assertEquals(doc.board("b-anim2"), result.doc.board("b-anim2"))
    }

    @Test
    fun addFrameRefusesWhatItCannotDo() {
        val doc = fixture(listOf(Frame("f1"), Frame("f2")))
        val ids = Ids()
        assertFailsWith<DocException> { AnimOps.addFrame(doc, "nope", "f1", NewFrame.BLANK, ids::next) }
        assertFailsWith<DocException> { AnimOps.addFrame(doc, "b-canvas", "f1", NewFrame.BLANK, ids::next) }
        assertFailsWith<DocException> { AnimOps.addFrame(doc, "b-anim", "nope", NewFrame.BLANK, ids::next) }
        // nothing on an empty board to duplicate or link to
        val empty = fixture(emptyList())
        assertFailsWith<DocException> { AnimOps.addFrame(empty, "b-anim", null, NewFrame.DUPLICATE, ids::next) }
        assertFailsWith<DocException> { AnimOps.addFrame(empty, "b-anim", null, NewFrame.LINK, ids::next) }
    }

    @Test
    fun addDuplicateRefusesWhenTheSourceFrameHasNoCelOnThatLayer() {
        // A document that is already broken: the layer has a cel for f2 but not for f1. Copying f1
        // would mean naming a cel that does not exist, so it is refused in words.
        val doc = fixture(listOf(Frame("f1"), Frame("f2")))
        val broken = doc.copy(
            layers = doc.layers.map { if (it.id == "l-anim") it.copy(frameCel = mapOf("f2" to "c-anim")) else it },
        )
        assertFailsWith<DocException> { AnimOps.addFrame(broken, "b-anim", "f1", NewFrame.DUPLICATE, Ids()::next) }
        assertFailsWith<DocException> { AnimOps.addFrame(broken, "b-anim", "f1", NewFrame.LINK, Ids()::next) }
        // BLANK needs no source, so the same broken document can still be added to.
        val before = DocOps.validate(broken)
        val blank = AnimOps.addFrame(broken, "b-anim", "f1", NewFrame.BLANK, Ids()::next)
        assertEquals(listOf("f1", "gen0", "f2"), blank.doc.frameIds())
        assertEquals("gen1", blank.doc.layer("l-anim").frameCel["gen0"])
        assertEquals(
            before,
            DocOps.validate(blank.doc),
            "a blank frame neither fills nor worsens the hole the document came in with",
        )
    }

    @Test
    fun addFrameRefusesAnIdTheDocumentAlreadyUses() {
        val doc = fixture()
        // the new FRAME id is one the board already has
        assertFailsWith<DocException> { AnimOps.addFrame(doc, "b-anim", "f1", NewFrame.BLANK, fixedIds("f1")) }
        // the new CEL id is one a layer already has
        assertFailsWith<DocException> {
            AnimOps.addFrame(doc, "b-anim", "f1", NewFrame.BLANK, fixedIds("fresh-frame", "c-anim"))
        }
        // ...even one that belongs to a layer this operation will not touch, which is stricter than
        // DocOps.validate on purpose: one flat rule is easier to keep than four namespaces.
        assertFailsWith<DocException> {
            AnimOps.addFrame(doc, "b-anim", "f1", NewFrame.BLANK, fixedIds("fresh-frame", "c-static"))
        }
    }

    // ---------- 3. deleteFrame ----------

    @Test
    fun deletingAFrameThatHoldsACelAnotherFrameStillShowsKeepsTheCel() {
        val linked = AnimOps.addFrame(fixture(), "b-anim", "f1", NewFrame.LINK, Ids()::next).doc
        val result = AnimOps.deleteFrame(linked, "b-anim", "gen0")

        assertEquals(emptyList(), DocOps.validate(result.doc))
        assertEquals(listOf("f1"), result.doc.frameIds())
        assertEquals(listOf("c-anim"), result.doc.layer("l-anim").cels.map { it.id })
        assertEquals(mapOf("f1" to "c-anim"), result.doc.layer("l-anim").frameCel)
        assertEquals(emptyList(), result.work, "nothing to free: the cel is still on screen")
    }

    @Test
    fun deletingAFrameDropsTheCelOnlyItWasShowing() {
        val duplicated = AnimOps.addFrame(fixture(), "b-anim", "f1", NewFrame.DUPLICATE, Ids()::next).doc
        val result = AnimOps.deleteFrame(duplicated, "b-anim", "gen0")

        assertEquals(emptyList(), DocOps.validate(result.doc))
        assertEquals(listOf("f1"), result.doc.frameIds())
        assertEquals(listOf("c-anim"), result.doc.layer("l-anim").cels.map { it.id }, "the orphan is gone")
        assertEquals(mapOf("f1" to "c-anim"), result.doc.layer("l-anim").frameCel, "and so is its mapping")
        assertEquals(listOf(CelWork.DropCel(layerId = "l-anim", celId = "gen1")), result.work)
    }

    @Test
    fun deletingOneOfTwoFramesShowingTheSameCelDropsItOnlyOnTheSecond() {
        // f1, f2, f3 where f2 and f3 share c2. Deleting f2 leaves f3 holding c2; deleting f3 then
        // has to free it, or the layer keeps a cel nothing shows.
        val doc = fixture(listOf(Frame("f1"), Frame("f2"), Frame("f3")))
        val twoCels = doc.copy(
            layers = doc.layers.map {
                if (it.id == "l-anim") it.copy(cels = listOf(Cel("c1"), Cel("c2")), frameCel = mapOf("f1" to "c1", "f2" to "c2", "f3" to "c2")) else it
            },
        )
        val first = AnimOps.deleteFrame(twoCels, "b-anim", "f2")
        assertEquals(emptyList(), DocOps.validate(first.doc))
        assertEquals(emptyList(), first.work)
        assertEquals(listOf("c1", "c2"), first.doc.layer("l-anim").cels.map { it.id })

        val second = AnimOps.deleteFrame(first.doc, "b-anim", "f3")
        assertEquals(emptyList(), DocOps.validate(second.doc))
        assertEquals(listOf("c1"), second.doc.layer("l-anim").cels.map { it.id })
        assertEquals(listOf(CelWork.DropCel(layerId = "l-anim", celId = "c2")), second.work)
    }

    @Test
    fun deletingAFrameCleansUpEveryAnimatedLayerInTheBoard() {
        val doc = fixture().withSecondAnimatedLayer()
        val two = AnimOps.addFrame(doc, "b-anim", "f1", NewFrame.DUPLICATE, Ids()::next).doc
        val copied = two.frameIds().last()
        val result = AnimOps.deleteFrame(two, "b-anim", copied)

        assertEquals(emptyList(), DocOps.validate(result.doc))
        // One instruction per animated layer, in document order, each naming a cel that is really
        // gone — asserted by what it means, not by which generation number the copy happened to get.
        val dropped = result.work.filterIsInstance<CelWork.DropCel>()
        assertEquals(2, dropped.size, "one per animated layer, and nothing else: ${result.work}")
        assertEquals(listOf("l-anim", "l-anim2"), dropped.map { it.layerId }, "in document order")
        for (work in dropped) {
            val left = result.doc.layer(work.layerId).cels.map { it.id }
            assertTrue(work.celId !in left, "${work.celId} is gone from ${work.layerId}")
        }
        assertTrue(dropped[0].celId != dropped[1].celId, "each layer dropped its OWN cel")
        assertEquals(listOf("c-anim"), result.doc.layer("l-anim").cels.map { it.id })
        assertEquals(listOf("c-anim2"), result.doc.layer("l-anim2").cels.map { it.id })
    }

    @Test
    fun deletingFramesOneAtATimeNeverLeavesAnOrphanCel() {
        var doc = fixture(listOf(Frame("f1"), Frame("f2"), Frame("f3")))
        val ids = Ids()
        // Three frames plus two links, all five showing the same one cel, so there is only ever that
        // cel to lose: no deletion on the way down can strand it.
        repeat(2) { doc = AnimOps.addFrame(doc, "b-anim", doc.frameIds().last(), NewFrame.LINK, ids::next).doc }
        assertEquals(5, doc.frameIds().size)

        for (frameId in listOf("gen1", "gen0", "f3", "f2")) {
            val result = AnimOps.deleteFrame(doc, "b-anim", frameId)
            doc = result.doc
            assertEquals(emptyList(), DocOps.validate(doc), "after deleting $frameId")
            assertNoOrphanCels(doc, "after deleting $frameId")
            assertEquals(listOf("c-anim"), doc.layer("l-anim").cels.map { it.id })
            assertEquals(emptyList(), result.work, "the one remaining frame still shows c-anim")
        }
        assertEquals(listOf("f1"), doc.frameIds())
    }

    @Test
    fun deletingTheOnlyFrameIsRefused() {
        val doc = fixture()
        val error = assertFailsWith<DocException> { AnimOps.deleteFrame(doc, "b-anim", "f1") }
        assertTrue(error.message!!.contains("no frames"), "the message must say why: ${error.message}")
        assertEquals(emptyList(), DocOps.validate(doc), "and the document is untouched")
    }

    @Test
    fun deleteFrameRefusesWhatItCannotDo() {
        val doc = fixture(listOf(Frame("f1"), Frame("f2")))
        assertFailsWith<DocException> { AnimOps.deleteFrame(doc, "nope", "f1") }
        assertFailsWith<DocException> { AnimOps.deleteFrame(doc, "b-anim", "nope") }
        assertFailsWith<DocException> { AnimOps.deleteFrame(doc, "b-canvas", "f1") }
        // a board with no frames has no frame to name, and says so rather than throwing on an index
        assertFailsWith<DocException> { AnimOps.deleteFrame(fixture(emptyList()), "b-anim", "f1") }
    }

    @Test
    fun deletingAFrameLeavesOtherBoardsAndStaticLayersAlone() {
        val doc = fixture(listOf(Frame("f1"), Frame("f2"))).withSecondAnimationBoard()
        val result = AnimOps.deleteFrame(doc, "b-anim", "f1")

        assertEquals(emptyList(), DocOps.validate(result.doc))
        assertEquals(doc.layer("l-static"), result.doc.layer("l-static"))
        assertEquals(doc.board("b-anim2"), result.doc.board("b-anim2"))
        assertEquals(doc.layer("l-other"), result.doc.layer("l-other"))
        assertEquals(listOf("g1", "g2"), result.doc.frameIds("b-anim2"))
    }

    // ---------- 4. setHold and the schedule ----------

    @Test
    fun threeTicksAtTwelveFramesPerSecondIsExactly250Ms() {
        val doc = AnimOps.setHold(fixture(), "b-anim", "f1", 3)

        assertEquals(emptyList(), DocOps.validate(doc))
        assertEquals(3, doc.board().frames.single().holdFrames)
        assertEquals(250.0, AnimOps.totalDurationMs(doc.board()), 0.0, "3 x 1000 / 12 is exactly 250")
        assertEquals(listOf(0.0), AnimOps.frameStartsMs(doc.board()))
    }

    @Test
    fun theScheduleAndTheTotalAgreeExactly() {
        val doc = AnimOps.setHold(fixture(listOf(Frame("f1"), Frame("f2"), Frame("f3"))), "b-anim", "f1", 3)
        val board = doc.board()
        val starts = AnimOps.frameStartsMs(board)

        assertEquals(3, starts.size, "one start per frame, in play order")
        assertEquals(0.0, starts[0], 0.0)
        assertEquals(250.0, starts[1], 0.0, "f1 is held for three ticks")
        assertEquals(250.0 + tickMs, starts[2], 0.0, "then f2 is held for one")
        // The total is the last start plus one more tick, walked by the same expression in the same
        // order, so this is exact and not "close enough".
        assertEquals(starts[2] + tickMs, AnimOps.totalDurationMs(board), 0.0)
    }

    @Test
    fun setHoldClampsToSomethingThatCanActuallyBeShown() {
        val doc = fixture(listOf(Frame("f1"), Frame("f2")))
        assertEquals(1, AnimOps.setHold(doc, "b-anim", "f1", 0).board().frames[0].holdFrames, "0 ticks is not a frame")
        assertEquals(1, AnimOps.setHold(doc, "b-anim", "f1", -40).board().frames[0].holdFrames)
        assertEquals(999, AnimOps.setHold(doc, "b-anim", "f1", 100000).board().frames[0].holdFrames)
        assertEquals(1, AnimOps.setHold(doc, "b-anim", "f1", 1).board().frames[0].holdFrames)
        assertEquals(999, AnimOps.setHold(doc, "b-anim", "f1", 999).board().frames[0].holdFrames)
        assertEquals(emptyList(), DocOps.validate(AnimOps.setHold(doc, "b-anim", "f1", 100000)))
    }

    @Test
    fun setHoldTouchesNothingButTheOneFrame() {
        val doc = fixture(listOf(Frame("f1"), Frame("f2"), Frame("f3"))).withSecondAnimatedLayer()
        val after = AnimOps.setHold(doc, "b-anim", "f2", 5)

        assertEquals(emptyList(), DocOps.validate(after))
        assertEquals(listOf(1, 5, 1), after.board().frames.map { it.holdFrames })
        assertEquals(doc.layers, after.layers, "a hold is a number; no cel and no pixel is involved")
    }

    @Test
    fun setHoldRefusesWhatItCannotDo() {
        val doc = fixture(listOf(Frame("f1"), Frame("f2")))
        assertFailsWith<DocException> { AnimOps.setHold(doc, "nope", "f1", 2) }
        assertFailsWith<DocException> { AnimOps.setHold(doc, "b-anim", "nope", 2) }
        assertFailsWith<DocException> { AnimOps.setHold(doc, "b-canvas", "f1", 2) }
        assertFailsWith<DocException> { AnimOps.setHold(fixture(emptyList()), "b-anim", "f1", 2) }
    }

    // ---------- 5. frameAt ----------

    private fun threeFrames(): Board = fixture(listOf(Frame("f1"), Frame("f2"), Frame("f3"))).board()

    @Test
    fun aBoundaryBelongsToTheFrameThatIsStarting() {
        val board = threeFrames()
        val starts = AnimOps.frameStartsMs(board)

        assertEquals("f1", AnimOps.frameAt(board, 0.0).id)
        assertEquals("f2", AnimOps.frameAt(board, starts[1]).id, "the exact start of frame 2 IS frame 2")
        assertEquals("f1", AnimOps.frameAt(board, starts[1] - 0.5).id, "a hair before it is still frame 1")
        assertEquals("f3", AnimOps.frameAt(board, starts[2]).id)
        assertEquals("f2", AnimOps.frameAt(board, starts[2] - 0.5).id)
    }

    @Test
    fun beforeTheStartIsTheFirstFrameAndAfterTheEndIsTheLast() {
        val board = threeFrames()
        val total = AnimOps.totalDurationMs(board)

        assertEquals("f1", AnimOps.frameAt(board, -1.0).id)
        assertEquals("f1", AnimOps.frameAt(board, -1e9).id)
        assertEquals("f3", AnimOps.frameAt(board, total).id, "the end of the play is the last frame")
        assertEquals("f3", AnimOps.frameAt(board, total + 1e9).id)
        assertEquals("f3", AnimOps.frameAt(board, Double.MAX_VALUE).id)
    }

    @Test
    fun everyFrameIsShowingAtItsOwnStart() {
        val board = threeFrames()
        val starts = AnimOps.frameStartsMs(board)
        for (i in board.frames.indices) {
            assertEquals(board.frames[i].id, AnimOps.frameAt(board, starts[i]).id, "frame $i at its own start")
        }
        // A time nobody chose is still answered with a frame rather than an index off the end.
        assertEquals("f1", AnimOps.frameAt(board, Double.NaN).id)
    }

    @Test
    fun aOneFrameBoardShowsThatFrameAtEveryTime() {
        val board = fixture().board()
        assertEquals("f1", AnimOps.frameAt(board, -5.0).id)
        assertEquals("f1", AnimOps.frameAt(board, 0.0).id)
        assertEquals("f1", AnimOps.frameAt(board, 1e6).id)
        assertEquals(tickMs, AnimOps.totalDurationMs(board), 0.0)
        assertEquals(listOf(0.0), AnimOps.frameStartsMs(board))
    }

    @Test
    fun holdsChangeWhatIsShowingWhen() {
        // f1 held for four ticks, f2 for one. The boundary is asked about as the schedule itself
        // reports it, so this cannot fail on a rounding difference of one double ulp.
        val doc = AnimOps.setHold(fixture(listOf(Frame("f1"), Frame("f2"))), "b-anim", "f1", 4)
        val board = doc.board()
        val starts = AnimOps.frameStartsMs(board)

        assertEquals("f1", AnimOps.frameAt(board, 0.0).id)
        assertEquals("f1", AnimOps.frameAt(board, starts[1] - 0.5).id, "just before the long hold ends")
        assertEquals("f2", AnimOps.frameAt(board, starts[1]).id, "and f2 from its own start")
        assertEquals(5.0 * tickMs, AnimOps.totalDurationMs(board), 1e-9, "four ticks plus one")
    }

    @Test
    fun aBoardWithNoFramesHasNothingToShowAndNoLength() {
        val board = fixture(emptyList()).board()
        val error = assertFailsWith<DocException> { AnimOps.frameAt(board, 0.0) }
        assertTrue(error.message!!.contains("no frames"), "the message must say why: ${error.message}")
        // The two schedule readers are still total: no frames is an empty schedule, not a broken board.
        assertEquals(0.0, AnimOps.totalDurationMs(board), 0.0)
        assertEquals(emptyList(), AnimOps.frameStartsMs(board))
    }

    @Test
    fun aBoardThatCannotBeTimedIsRefusedInWords() {
        // Every one of these is a rate no board can play at, and each is a value a hand-edited or
        // half-written document really does contain. 0.9 and 120 are the range ends; NaN and the
        // infinities are the ones a `<` or `<=` guard waves through.
        for (fps in listOf(0f, -1f, 0.9f, 120f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val board = threeFrames().copy(fps = fps)
            assertFailsWith<DocException>("fps $fps should be refused") { AnimOps.frameAt(board, 0.0) }
            assertFailsWith<DocException>("fps $fps should be refused") { AnimOps.totalDurationMs(board) }
            assertFailsWith<DocException>("fps $fps should be refused") { AnimOps.frameStartsMs(board) }
        }
        // The ends of the range are legal: 1 and 60 play.
        for (fps in listOf(1f, 12f, 60f)) {
            val board = threeFrames().copy(fps = fps)
            assertEquals(3 * 1000.0 / fps.toDouble(), AnimOps.totalDurationMs(board), 1e-9, "fps $fps")
        }
    }

    @Test
    fun theRateGuardIsTheModelsOwnRuleAndSaysSo() {
        // AnimOps and DocOps must never disagree about whether a board can play, so every fps the
        // validator calls broken is one the maths refuses too, and vice versa.
        for (fps in listOf(0f, -1f, 0.9f, 120f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val board = threeFrames().copy(fps = fps)
            val problems = DocOps.validate(fixture(listOf(Frame("f1"), Frame("f2"), Frame("f3"))).replaceBoard(board))
            assertTrue(
                problems.any { it.contains("fps") },
                "the validator should already call fps $fps broken, got $problems",
            )
            assertFailsWith<DocException>("fps $fps") { AnimOps.totalDurationMs(board) }
        }
    }

    // ---------- 6. moveFrame ----------

    @Test
    fun moveFrameReordersAndLeavesTheMappingAlone() {
        val doc = fixture(listOf(Frame("f1"), Frame("f2"), Frame("f3")))
        val moved = AnimOps.moveFrame(doc, "b-anim", "f1", 2)

        assertEquals(emptyList(), DocOps.validate(moved))
        assertEquals(listOf("f2", "f3", "f1"), moved.frameIds())
        assertEquals(
            doc.layer("l-anim").frameCel,
            moved.layer("l-anim").frameCel,
            "frameCel is keyed by frame id, so play order cannot disturb it",
        )
        assertEquals(doc.layer("l-anim").cels, moved.layer("l-anim").cels)
    }

    @Test
    fun moveFrameToTheSameIndexChangesNothing() {
        val doc = fixture(listOf(Frame("f1"), Frame("f2"), Frame("f3")))
        assertEquals(doc, AnimOps.moveFrame(doc, "b-anim", "f2", 1))
        assertEquals(doc, AnimOps.moveFrame(doc, "b-anim", "f1", 0))
    }

    @Test
    fun moveFrameToTheEnds() {
        val doc = fixture(listOf(Frame("f1"), Frame("f2"), Frame("f3")))
        assertEquals(listOf("f2", "f3", "f1"), AnimOps.moveFrame(doc, "b-anim", "f1", 2).frameIds())
        assertEquals(listOf("f3", "f1", "f2"), AnimOps.moveFrame(doc, "b-anim", "f3", 0).frameIds())
        assertEquals(listOf("f1", "f3", "f2"), AnimOps.moveFrame(doc, "b-anim", "f3", 1).frameIds())
    }

    @Test
    fun moveFrameRefusesAnIndexNoFrameOccupies() {
        val doc = fixture(listOf(Frame("f1"), Frame("f2"), Frame("f3")))
        // 0 to 2 are the only indices a frame can end up at; 3 would be a gap, not a place.
        assertFailsWith<DocException> { AnimOps.moveFrame(doc, "b-anim", "f1", 3) }
        assertFailsWith<DocException> { AnimOps.moveFrame(doc, "b-anim", "f1", -1) }
        assertFailsWith<DocException> { AnimOps.moveFrame(doc, "b-anim", "nope", 0) }
        assertFailsWith<DocException> { AnimOps.moveFrame(doc, "nope", "f1", 0) }
        assertFailsWith<DocException> { AnimOps.moveFrame(doc, "b-canvas", "f1", 0) }
        assertFailsWith<DocException> { AnimOps.moveFrame(fixture(emptyList()), "b-anim", "f1", 0) }
    }

    @Test
    fun reorderingFramesWithDifferentHoldsMovesTheDurationsWithThem() {
        val doc = AnimOps.setHold(fixture(listOf(Frame("f1"), Frame("f2"), Frame("f3"))), "b-anim", "f1", 3)
        assertEquals(250.0, AnimOps.frameStartsMs(doc.board())[1], 0.0, "f1 is the long one first")

        val moved = AnimOps.moveFrame(doc, "b-anim", "f1", 2)
        assertEquals(emptyList(), DocOps.validate(moved))
        assertEquals(listOf(1, 1, 3), moved.board().frames.map { it.holdFrames }, "the hold travelled with the frame")

        val starts = AnimOps.frameStartsMs(moved.board())
        assertEquals(0.0, starts[0], 0.0)
        assertEquals(tickMs, starts[1], 1e-9)
        assertEquals(2.0 * tickMs, starts[2], 1e-9)
        assertEquals(5.0 * tickMs, AnimOps.totalDurationMs(moved.board()), 1e-9, "five ticks, whatever the order")
        // and the frame showing at a time is still decided by the same arithmetic
        for (i in moved.board().frames.indices) {
            assertEquals(moved.board().frames[i].id, AnimOps.frameAt(moved.board(), starts[i]).id)
        }
    }

    // ---------- 7. nothing is mutated in place ----------

    @Test
    fun operationsReturnANewDocumentAndLeaveTheOldOneExactlyAsItWas() {
        val doc = fixture(listOf(Frame("f1"), Frame("f2")))
        val two = AnimOps.addFrame(doc, "b-anim", "f1", NewFrame.DUPLICATE, Ids()::next).doc

        assertEquals(listOf("f1", "f2"), doc.frameIds(), "the document handed in is not the one handed back")
        assertEquals(listOf("f1", "gen0", "f2"), two.frameIds())
        assertEquals(listOf("c-anim"), doc.layer("l-anim").cels.map { it.id })
        assertEquals(emptyList(), DocOps.validate(doc), "and it is still the document it was")
    }

    /** Every cel an animated layer still has must be shown by one of its frames. */
    private fun assertNoOrphanCels(doc: JbDocument, stage: String) {
        for (layer in doc.layers) {
            val shown = layer.frameCel.values.toSet()
            for (cel in layer.cels) {
                assertTrue(
                    layer.animatedIn == null || shown.contains(cel.id),
                    "$stage: layer ${layer.id} still holds cel ${cel.id}, which no frame shows",
                )
            }
        }
    }

    // ---------- 8. the question the whole task is really asking ----------

    @Test
    fun aScriptedSequenceThatAlsoAnimatesALayerStaysValid() {
        val ids = Ids()
        var doc = AnimOps.animateLayer(fixture(listOf(Frame("f1"), Frame("f2"))), "l-static", "b-anim")
        assertEquals(emptyList(), DocOps.validate(doc))

        // A duplicate after f1: one new frame, and one new cel on each of the two now-animated
        // layers. The new frame is identified as "the one that was not there before" — never by a
        // generation counter, and never by a hard-coded index, both of which are accidents of the
        // caller's id generator and of the insertion position respectively.
        val beforeDuplicate = doc.frameIds().toSet()
        doc = AnimOps.addFrame(doc, "b-anim", "f1", NewFrame.DUPLICATE, ids::next).doc
        val added = doc.frameIds() - beforeDuplicate
        assertEquals(1, added.size, "exactly one frame was added")
        val duplicate = added.single()
        assertEquals(listOf("f1", "f2"), doc.frameIds().filter { it != duplicate }, "only one frame was added")
        assertEquals(emptyList(), DocOps.validate(doc), "after a duplicate")

        doc = AnimOps.setHold(doc, "b-anim", duplicate, 4)

        // A link at the front: the new frame goes in before f1 and shows each layer's OWN cel.
        doc = AnimOps.addFrame(doc, "b-anim", null, NewFrame.LINK, ids::next).doc
        val link = doc.frameIds()[0]
        assertEquals(listOf("f1", "f2"), doc.frameIds().drop(1).filter { it != duplicate })
        assertEquals(duplicate, doc.frameIds()[2], "the duplicate kept its place after f1")
        assertEquals("c-anim", doc.layer("l-anim").frameCel[link], "a link is the original cel, not a copy")
        assertEquals("c-static", doc.layer("l-static").frameCel[link])
        assertEquals(emptyList(), DocOps.validate(doc), "after a link at the front")

        doc = AnimOps.moveFrame(doc, "b-anim", duplicate, 3)
        assertEquals(duplicate, doc.frameIds().last(), "the duplicate is now last")
        assertEquals(emptyList(), DocOps.validate(doc), "after the move")

        // f2's cel is still shown by the link, so nothing is dropped
        val dropped = AnimOps.deleteFrame(doc, "b-anim", "f2")
        assertEquals(emptyList(), dropped.work, "the link still shows that cel")
        doc = dropped.doc
        assertEquals(emptyList(), DocOps.validate(doc), "after deleting f2")

        doc = AnimOps.setHold(doc, "b-anim", duplicate, 100000)
        assertEquals(999, doc.board().frames.last().holdFrames, "clamped")

        doc = AnimOps.deleteFrame(doc, "b-anim", "f1").doc
        assertEquals(listOf(link, duplicate), doc.frameIds(), "the two frames that were left")
        assertEquals(emptyList(), DocOps.validate(doc))
        assertNoOrphanCels(doc, "end of the scripted sequence")

        // The link still shows both original cels; the duplicate shows a PRIVATE copy on each layer,
        // so painting on one layer's frames can never reach the other.
        assertEquals("c-anim", doc.layer("l-anim").frameCel[link])
        assertEquals("c-static", doc.layer("l-static").frameCel[link])
        val copyOnAnim = doc.layer("l-anim").frameCel[duplicate]
        val copyOnStatic = doc.layer("l-static").frameCel[duplicate]
        assertTrue(copyOnAnim != "c-anim", "a duplicate is a copy, not the original cel")
        assertTrue(copyOnStatic != "c-static", "a duplicate is a copy, not the original cel")
        assertTrue(copyOnAnim != copyOnStatic, "each layer got its own copy, not one shared cel")
        assertEquals(2, doc.layer("l-anim").cels.size, "its own cel plus the copy")
        assertEquals(2, doc.layer("l-static").cels.size, "its own cel plus the copy")
    }

    @Test
    fun animateLayerRefusesToQuietlyThrowAwayAJunkDocument() {
        // Rule 6 already calls both of these documents wrong. Tidying them up here would be a silent
        // repair, so the caller is told instead.
        val doc = fixture(listOf(Frame("f1"), Frame("f2")))
        val withMappings = doc.copy(
            layers = doc.layers.map { if (it.id == "l-static") it.copy(frameCel = mapOf("f1" to "c-static")) else it },
        )
        assertFailsWith<DocException> { AnimOps.animateLayer(withMappings, "l-static", "b-anim") }
        assertEquals(
            mapOf("f1" to "c-static"),
            withMappings.layer("l-static").frameCel,
            "and nothing was changed",
        )

        val withTwoCels = doc.copy(
            layers = doc.layers.map { if (it.id == "l-static") it.copy(cels = it.cels + Cel("spare")) else it },
        )
        assertFailsWith<DocException> { AnimOps.animateLayer(withTwoCels, "l-static", "b-anim") }
    }

    @Test
    fun aLongRandomSequenceOfOperationsNeverProducesAnInvalidDocument() {
        var doc = fixture(listOf(Frame("f1"), Frame("f2"))).withSecondAnimatedLayer()
        val random = Random(20260928)
        val ids = Ids()

        repeat(300) { step ->
            val frames = doc.board().frames
            val pick = frames[random.nextInt(frames.size)]
            try {
                doc = when (random.nextInt(6)) {
                    0 -> AnimOps.addFrame(doc, "b-anim", null, NewFrame.BLANK, ids::next).doc
                    1 -> AnimOps.addFrame(doc, "b-anim", frames.last().id, NewFrame.DUPLICATE, ids::next).doc
                    2 -> AnimOps.addFrame(doc, "b-anim", frames.last().id, NewFrame.LINK, ids::next).doc
                    3 -> if (frames.size > 1) {
                        AnimOps.deleteFrame(doc, "b-anim", frames[random.nextInt(frames.size)].id).doc
                    } else {
                        doc
                    }
                    4 -> AnimOps.setHold(doc, "b-anim", pick.id, random.nextInt(1200) - 100)
                    else -> AnimOps.moveFrame(doc, "b-anim", pick.id, random.nextInt(frames.size))
                }
            } catch (e: DocException) {
                // A refusal is a legal answer to any of these. A document DocOps.validate dislikes is not.
            }

            val problems = DocOps.validate(doc)
            assertEquals(emptyList(), problems, "step $step left an invalid document: $problems")
            assertNoOrphanCels(doc, "step $step")
            // The static layer must be exactly as static as it started, after 300 operations.
            assertEquals(1, doc.layer("l-static").cels.size)
            assertTrue(doc.layer("l-static").frameCel.isEmpty(), "a static layer has no frame mappings")
            assertNull(doc.layer("l-static").animatedIn)
        }
        assertTrue(doc.board().frames.isNotEmpty(), "and the board is still a board")
    }
}
