package cc.joycreator.joybrush.core.doc

import kotlin.test.*

/** ANIMATION_CLIPS C1/C2: clips animate the active layer only, and clips on different layers may overlap. */
class AnimationClipsTest {
    private var n = 0
    private fun ids(): String = "c${++n}"

    /** A page with a background layer "bg" and a character layer "ch"; [active] is the layer being painted. */
    private fun doc(active: String): JbDocument {
        val base = DocOps.newDocument("d", "Doc", 2000, 1000, ::ids)
        val bg = base.layers.single().copy(id = "bg", name = "Background")
        val ch = Layer("ch", "Character", LayerKind.PAINT, cels = listOf(Cel("ch-cel")))
        return base.copy(layers = listOf(bg, ch), activeLayerId = active)
    }

    @Test fun aNewClipAnimatesOnlyTheActiveLayerAndLeavesTheOthersStill() {
        val change = BoardDocumentOps.createAnimation(doc("ch"), "Walk", RectPx(0, 0, 200, 200), ::ids)
        val board = change.doc.boards.last()
        val ch = change.doc.layers.first { it.id == "ch" }.regions.single { it.boardId == board.id }
        val bg = change.doc.layers.first { it.id == "bg" }.regions.single { it.boardId == board.id }
        assertFalse(ch.held, "the active layer animates in the new clip")
        assertTrue(bg.held, "the other layer is still there")
        assertEquals(listOf("ch"), change.transfers.map { it.layerId }, "only the active layer's pixels move into frame 1")
        assertEquals(listOf("ch"), change.clears.map { it.layerId })
    }

    @Test fun clipsOnDifferentLayersMayOverlap() {
        var d = BoardDocumentOps.createAnimation(doc("ch"), "Walk", RectPx(0, 0, 200, 200), ::ids).doc
        d = BoardDocumentOps.createAnimation(d.copy(activeLayerId = "bg"), "Sky", RectPx(100, 100, 400, 300), ::ids).doc
        assertTrue(DocOps.validate(d).isEmpty(), DocOps.validate(d).joinToString())
        assertEquals(2, d.boards.count { it.kind == BoardKind.ANIMATION })
    }

    @Test fun oneLayerCannotAnimateInTwoOverlappingClips() {
        var d = BoardDocumentOps.createAnimation(doc("ch"), "Walk", RectPx(0, 0, 200, 200), ::ids).doc
        d = BoardDocumentOps.createAnimation(d.copy(activeLayerId = "bg"), "Sky", RectPx(100, 100, 400, 300), ::ids).doc
        val sky = d.boards.last().id
        val e = assertFailsWith<DocException> { RegionDocumentOps.setHeld(d, sky, "ch", held = false) }
        assertTrue(e.message.orEmpty().contains("Character"), e.message)
    }

    @Test fun aClipsEndBehaviourIsSavedAndAnUnknownWordPlaysAsALoop() {
        var d = BoardDocumentOps.createAnimation(doc("ch"), "Walk", RectPx(0, 0, 200, 200), ::ids).doc
        val id = d.boards.last().id
        assertEquals(BoardEnd.LOOP, d.boards.last().end)
        d = BoardDocumentOps.setEnd(d, id, BoardEnd.PING_PONG)
        val back = DocJson.decode(DocJson.encode(d))
        assertEquals(BoardEnd.PING_PONG, back.boards.first { it.id == id }.end)
        assertEquals(cc.joycreator.joybrush.core.anim.PlayMode.PING_PONG, cc.joycreator.joybrush.core.anim.PlayMode.of(BoardEnd.PING_PONG))
        assertEquals(cc.joycreator.joybrush.core.anim.PlayMode.ONCE, cc.joycreator.joybrush.core.anim.PlayMode.of(BoardEnd.HOLD))
        assertEquals(BoardEnd.LOOP, BoardEnd.of("spin"))
        assertFalse(DocJson.encode(BoardDocumentOps.setEnd(d, id, BoardEnd.LOOP)).contains("\"end\""), "a loop is not written")
    }
}
