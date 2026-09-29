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

    /**
     * F2. A `frameCel` entry has two halves, and both of them are checked: the key naming a frame,
     * and the VALUE naming a cel the layer actually has. The value half was missing, so DUPLICATE
     * handed the engine a [CelWork.CopyCel] reading `fromCelId = "ghost"` for a cel not in the
     * document, and LINK wrote `"ghost"` into a second `frameCel` entry — turning the one validation
     * problem this document already had into two.
     */
    @Test
    fun addDuplicateAndLinkRefuseWhenTheSourceFramePointsAtACelTheLayerDoesNotHave() {
        val doc = fixture(listOf(Frame("f1"), Frame("f2")))
        val broken = doc.copy(
            layers = doc.layers.map {
                if (it.id == "l-anim") it.copy(frameCel = mapOf("f1" to "ghost", "f2" to "c-anim")) else it
            },
        )
        val problems = DocOps.validate(broken)
        assertTrue(
            problems.any { it.contains("ghost") },
            "the validator already calls this broken: $problems",
        )

        for (mode in listOf(NewFrame.DUPLICATE, NewFrame.LINK)) {
            val error = assertFailsWith<DocException>("$mode must refuse a cel that is not there") {
                AnimOps.addFrame(broken, "b-anim", "f1", mode, Ids()::next)
            }
            val message = error.message!!
            assertTrue(message.contains("ghost"), "the message must name the cel it cannot use: $message")
            assertTrue(message.contains("l-anim"), "and the layer that cannot show it: $message")
        }

        // BLANK needs no source frame, so the same broken document can still be added to — and it does
        // not get one problem worse, which is what LINK used to do.
        val before = DocOps.validate(broken)
        val blank = AnimOps.addFrame(broken, "b-anim", "f1", NewFrame.BLANK, Ids()::next)
        assertEquals(listOf("f1", "gen0", "f2"), blank.doc.frameIds())
        assertEquals("gen1", blank.doc.layer("l-anim").frameCel["gen0"])
        assertEquals(before, DocOps.validate(blank.doc), "one problem in, one problem out")
    }

    // ---------- 3. deleteFrame ----------

    /**
     * F3. `DocOps.validate` rule 4 forbids two frames of one board sharing an id, so `deleteFrame` may
     * not guess which of the two "f1" was meant. It refuses instead.
     *
     * What it used to do, and why that is data loss rather than an edge case: `removeAt` drops the
     * FIRST copy, the `frameCel` mapping is keyed by id so BOTH copies lose it at once, the cel is then
     * in no remaining mapping and is filtered out of `cels`, and a [CelWork.DropCel] hands the live
     * cel to the engine to free. The frame that is left has no cel at all.
     */
    @Test
    fun twoFramesCalledTheSameThingAreRefusedRatherThanHalfDeleted() {
        // `associate` collapses the two f1 frames to ONE mapping entry, which is exactly the shape the
        // reviewer's input has and exactly the case that loses the cel.
        val broken = fixture(listOf(Frame("f1"), Frame("f1")))
        assertEquals(mapOf("f1" to "c-anim"), broken.layer("l-anim").frameCel)
        assertTrue(
            DocOps.validate(broken).any { it.contains("two frames called \"f1\"") },
            "rule 4 already forbids this: ${DocOps.validate(broken)}",
        )

        val error = assertFailsWith<DocException> { AnimOps.deleteFrame(broken, "b-anim", "f1") }
        assertTrue(
            error.message!!.contains("f1"),
            "the message must name the id that is ambiguous: ${error.message}",
        )
        // The other three are refused for the same reason: a frame id that names two frames cannot be
        // acted on without picking one, and setHold would set the hold on both.
        assertFailsWith<DocException> { AnimOps.setHold(broken, "b-anim", "f1", 4) }
        assertFailsWith<DocException> { AnimOps.moveFrame(broken, "b-anim", "f1", 1) }
        assertFailsWith<DocException> { AnimOps.addFrame(broken, "b-anim", "f1", NewFrame.BLANK, Ids()::next) }
        assertFailsWith<DocException> { AnimOps.animateLayer(broken, "l-static", "b-anim") }

        // Nothing was changed and, above all, nothing was dropped.
        assertEquals(listOf("f1", "f1"), broken.frameIds())
        assertEquals(listOf("c-anim"), broken.layer("l-anim").cels.map { it.id }, "the ink is still there")
        assertEquals(mapOf("f1" to "c-anim"), broken.layer("l-anim").frameCel)
    }

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

    // ---------- 4b. a hold the validator already calls broken ----------

    /**
     * F1. `DocOps.validate` rule 4 rejects a frame held for fewer than one tick, and none of the three
     * time functions may answer for such a board.
     *
     * WHAT THE OLD CODE DID, walked by hand from its own arithmetic (`hold × 1000 / fps`, 12 fps, so
     * one tick = 83.333333333333329 ms):
     *
     *   frames [f1 held 0, f2 held 1]  ->  starts [0.0, 0.0], total 83.333333333333329
     *     frameAt(board, 0.0): 0.0 is not < 0.0 and not >= 83.33, so the walk runs `0.0 <= 0.0`
     *     twice, chosen = 1, and it answers **f2**. f1 — the frame held for NOTHING — is shown at
     *     no time in the whole play except a negative one.
     *   frames [f1 held 0, f2 held 0, f3 held 0]  ->  starts [0.0, 0.0, 0.0], total 0.0
     *     every time is at or past a total of zero, so frameAt answers the LAST frame at every time
     *     in the world.
     *
     * Both are the "confident wrong answer" the fps guard exists to prevent, reached through the other
     * half of the same rule. Both now throw, and say which frame and which hold.
     */
    @Test
    fun aFrameHeldForNoTicksIsRefusedInWords() {
        val boards = listOf(
            "first" to fixture(listOf(Frame("f1", holdFrames = 0), Frame("f2"))),
            "middle" to fixture(listOf(Frame("f1"), Frame("f2", holdFrames = 0), Frame("f3"))),
            "last" to fixture(listOf(Frame("f1"), Frame("f2"), Frame("f3", holdFrames = 0))),
            "all of them" to fixture(listOf(Frame("f1", 0), Frame("f2", 0), Frame("f3", 0))),
            "negative too" to fixture(listOf(Frame("f1", -5), Frame("f2"))),
        )
        for ((which, broken) in boards) {
            val board = broken.board()
            // The validator calls it broken first — the guard is that rule and not a second opinion.
            assertTrue(
                DocOps.validate(broken).any { it.contains("held for") },
                "the validator should already refuse the $which board: ${DocOps.validate(broken)}",
            )
            val error = assertFailsWith<DocException>("the $which board should be refused") {
                AnimOps.frameAt(board, 0.0)
            }
            val message = error.message!!
            assertTrue(
                message.contains(board.frames.first { it.holdFrames < 1 }.id),
                "the message must name the frame held for nothing: $message",
            )
            assertTrue(message.contains("held"), "and say that a hold is the problem: $message")
            assertFailsWith<DocException>("totalDurationMs, $which") { AnimOps.totalDurationMs(board) }
            assertFailsWith<DocException>("frameStartsMs, $which") { AnimOps.frameStartsMs(board) }
        }
    }

    /**
     * The held-frame answer for a hold of 0, 1 and 2 — the three numbers that decide whether this
     * change is right, in one place.
     *
     * Derived by hand at 12 fps, one tick = 1000/12 ms:
     *   hold 0 -> no time on the timeline at all -> refused, in words, by all three
     *   hold 1 -> one tick:  starts [0.0],  total 1000/12 = 83.333333333333329,
     *            and the frame is on screen for the whole of that one tick
     *   hold 2 -> two ticks: starts [0.0],  total 2000/12 = 166.66666666666666,
     *            and the frame is STILL on screen a whole tick in, which is the only thing that
     *            distinguishes a hold of 2 from a hold of 1
     *
     * A board of one frame is used so that "which frame is showing" cannot be answered by accident:
     * with a single frame the only question left is when it stops being on screen, and that boundary
     * is the whole of the arithmetic.
     */
    @Test
    fun aHoldOfZeroOneAndTwoTicksAreThreeDifferentAnswers() {
        val legal = fixture().board() // 12 fps, one frame held for one tick
        val none = legal.copy(frames = listOf(Frame("f1", holdFrames = 0)))
        val one = legal
        val two = legal.copy(frames = listOf(Frame("f1", holdFrames = 2)))

        // 0 — refused by every reader, and not quietly answered as "the frame that is there".
        assertFailsWith<DocException> { AnimOps.frameAt(none, 0.0) }
        assertFailsWith<DocException> { AnimOps.totalDurationMs(none) }
        assertFailsWith<DocException> { AnimOps.frameStartsMs(none) }

        // 1 — one tick, exact.
        assertEquals(listOf(0.0), AnimOps.frameStartsMs(one))
        assertEquals(1000.0 / 12.0, AnimOps.totalDurationMs(one), 0.0)
        assertEquals("f1", AnimOps.frameAt(one, 0.0).id)
        assertEquals("f1", AnimOps.frameAt(one, 1000.0 / 12.0 - 0.5).id, "half a tick in")

        // 2 — two ticks, exact, and the frame outlives a whole tick.
        assertEquals(listOf(0.0), AnimOps.frameStartsMs(two))
        assertEquals(2000.0 / 12.0, AnimOps.totalDurationMs(two), 0.0)
        assertEquals("f1", AnimOps.frameAt(two, 0.0).id)
        assertEquals("f1", AnimOps.frameAt(two, 1000.0 / 12.0).id, "a whole tick in, not out")
        assertEquals("f1", AnimOps.frameAt(two, 2 * 1000.0 / 12.0 - 0.5).id, "the last half tick")
    }

    /**
     * The boundary of a hold that spans a range: three frames held 1, 2 and 1 ticks.
     *
     * THE TIMELINE, WRITTEN OUT BEFORE ANY EXPECTATION BELOW:
     *
     *     f1   1 tick    [ 0 .................................... 1 tick )
     *     f2   2 ticks   [ 1 tick ............................... 3 ticks )
     *     f3   1 tick    [ 3 ticks .............................. 4 ticks )
     *
     * At 12 fps one tick is 1000/12 = 83.333333333333329 ms, so the starts are 0, one tick and three
     * ticks. Three ticks is `3000.0 / 12.0`, which is EXACTLY 250.0 in binary floating point, and
     * `1000.0/12.0 + 2000.0/12.0` is also exactly 250.0 — so the second boundary does not depend on
     * how the addition rounds, which is what makes a delta of 0.0 honest here rather than lucky.
     * The total is four ticks: the walk gives 333.33333333333331, which is bit-for-bit the same
     * double as `4 * 1000.0 / 12.0` and as 83.333333333333329 + 166.66666666666666 + 83.333333333333329.
     *
     * The times asked about are whole milliseconds, which are exactly representable, so none of these
     * comparisons sits on a rounding knife edge except the two boundaries themselves — and those are
     * asked about from BOTH sides, because a boundary tested from one side only is a boundary that can
     * be off by one in the direction nobody looked.
     */
    @Test
    fun aHoldThatSpansARangeMovesEveryBoundaryAfterItByExactlyThatManyTicks() {
        // Two ticks for f2, in a document built by the only writer of holds there is.
        val doc = AnimOps.setHold(
            fixture(listOf(Frame("f1"), Frame("f2"), Frame("f3"))), "b-anim", "f2", 2,
        )
        val held = doc.board()
        assertEquals(emptyList(), DocOps.validate(doc))
        assertEquals(listOf(1, 2, 1), held.frames.map { it.holdFrames })

        assertEquals(listOf(0.0, 1000.0 / 12.0, 250.0), AnimOps.frameStartsMs(held), "0, 1 tick, 3 ticks")
        assertEquals(250.0, AnimOps.frameStartsMs(held)[2], 0.0, "three ticks is exactly 250 ms")
        assertEquals(4 * 1000.0 / 12.0, AnimOps.totalDurationMs(held), 0.0, "four ticks in all")

        assertEquals("f1", AnimOps.frameAt(held, 0.0).id)
        assertEquals("f1", AnimOps.frameAt(held, 1000.0 / 12.0 - 0.5).id, "half a tick before the first boundary")
        assertEquals("f2", AnimOps.frameAt(held, 1000.0 / 12.0).id, "the first boundary belongs to f2")
        assertEquals("f2", AnimOps.frameAt(held, 125.0).id, "1.5 ticks: inside the TWO-tick hold")
        assertEquals("f2", AnimOps.frameAt(held, 249.0).id, "a whole tick before f3 starts")
        assertEquals("f3", AnimOps.frameAt(held, 250.0).id, "the second boundary belongs to f3")
        assertEquals("f3", AnimOps.frameAt(held, 333.0).id, "inside f3's single tick")
        assertEquals("f3", AnimOps.frameAt(held, 4 * 1000.0 / 12.0 - 0.5).id, "the last half ms")
        assertEquals("f3", AnimOps.frameAt(held, AnimOps.totalDurationMs(held)).id, "and the end is still it")
    }

    /**
     * The same timeline at 10 fps, where every number is a whole number of milliseconds.
     *
     * This is the SECOND, INDEPENDENT derivation of the test above, and the one that needs no
     * rounding argument at all: one tick is `1000 / 10` = 100 ms exactly, two is 200 exactly, so
     * holds of 1, 2, 1 are starts [0, 100, 300] and a total of 400 with nothing left over. If the
     * 12 fps expectations and these disagree, one of the two derivations is wrong.
     */
    @Test
    fun theSameTimelineAtTenFramesPerSecondIsExactInEveryValue() {
        val doc = AnimOps.setHold(fixture(listOf(Frame("f1"), Frame("f2"), Frame("f3"))), "b-anim", "f2", 2)
        val board = doc.board().copy(fps = 10f)

        assertEquals(listOf(0.0, 100.0, 300.0), AnimOps.frameStartsMs(board))
        assertEquals(400.0, AnimOps.totalDurationMs(board), 0.0)
        assertEquals("f1", AnimOps.frameAt(board, 0.0).id)
        assertEquals("f1", AnimOps.frameAt(board, 99.0).id, "the last ms of f1's single tick")
        assertEquals("f2", AnimOps.frameAt(board, 100.0).id, "f2 starts")
        assertEquals("f2", AnimOps.frameAt(board, 150.0).id, "and runs for TWO ticks")
        assertEquals("f2", AnimOps.frameAt(board, 299.0).id, "the last ms of the long hold")
        assertEquals("f3", AnimOps.frameAt(board, 300.0).id)
        assertEquals("f3", AnimOps.frameAt(board, 399.0).id)
        assertEquals("f3", AnimOps.frameAt(board, 400.0).id, "and the end of the play")
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

    /**
     * Only an ANIMATION board has a schedule. Every mutating operation says so through
     * `playableBoard`; these three used to be the only operations here that did not check, so a
     * CANVAS board that somehow held frames could be timed and asked for "the frame showing" as
     * though it were playing.
     *
     * (`DocOps.validate` says nothing about frames on a non-ANIMATION board, so such a board can be
     * valid. It is also not something any operation here can produce. A renderer asked to play it has
     * no honest answer, which is why the maths refuses rather than inventing one.)
     */
    @Test
    fun onlyAnAnimationBoardHasAScheduleToTime() {
        val notAnimating = threeFrames().copy(kind = BoardKind.CANVAS)
        assertFailsWith<DocException> { AnimOps.frameAt(notAnimating, 0.0) }
        assertFailsWith<DocException> { AnimOps.totalDurationMs(notAnimating) }
        assertFailsWith<DocException> { AnimOps.frameStartsMs(notAnimating) }

        // The very same frames on an ANIMATION board answer, so it is the kind and nothing else.
        val animating = threeFrames()
        assertEquals(listOf(0.0, 1000.0 / 12.0, 2000.0 / 12.0), AnimOps.frameStartsMs(animating))
        assertEquals(3 * 1000.0 / 12.0, AnimOps.totalDurationMs(animating), 0.0)
        assertEquals("f1", AnimOps.frameAt(animating, 0.0).id)
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

    /**
     * Spec test clause 7: "moveFrame reorders; **the static layer and other boards are unchanged**."
     *
     * That second half had no test at all, which is a gap and not a design choice: the sibling
     * operations each prove it (`addFrameLeavesLayersThatDoNotAnimateHereAlone`,
     * `deletingAFrameLeavesOtherBoardsAndStaticLayersAlone`). Reordering is exactly where a "just
     * rewrite the board list" implementation could touch a neighbouring layer, so the proof is worth
     * having rather than assuming.
     */
    @Test
    fun moveFrameLeavesTheStaticLayerAndEveryOtherBoardAlone() {
        val doc = fixture(listOf(Frame("f1"), Frame("f2"), Frame("f3"))).withSecondAnimationBoard()
        val moved = AnimOps.moveFrame(doc, "b-anim", "f1", 2)

        assertEquals(emptyList(), DocOps.validate(moved))
        assertEquals(listOf("f2", "f3", "f1"), moved.frameIds(), "the board it was asked about is the one that moves")

        assertEquals(doc.layer("l-static"), moved.layer("l-static"), "a static layer is a held background")
        assertEquals(
            doc.layer("l-other"),
            moved.layer("l-other"),
            "a layer animated on ANOTHER board belongs to that board's film strip",
        )
        assertEquals(doc.board("b-canvas"), moved.board("b-canvas"))
        assertEquals(doc.board("b-anim2"), moved.board("b-anim2"))
        assertEquals(listOf("g1", "g2"), moved.frameIds("b-anim2"), "the other film strip still reads g1, g2")
        assertEquals(doc.id, moved.id)
        assertEquals(doc.activeBoardId, moved.activeBoardId)
        assertEquals(doc.layers.size, moved.layers.size, "no layer was added or lost either")
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
