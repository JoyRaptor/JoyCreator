package cc.joycreator.joybrush.core.doc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JB-NOW-18 — region-frame edit sequence invariants.
 *
 * Deterministic add/duplicate/delete/reorder/hold sequences across TWO nonoverlapping
 * Animation boards plus multiple layers, using only the real public
 * [RegionDocumentOps] operations. Small fixtures only (20x20 boards, a handful of
 * frames, two layers): no random or giant documents.
 *
 * The test keeps INDEPENDENT expectations (frame order lists, current-frame ids,
 * shared-cel ids, held flags, per-layer frame->cel maps, link groups) and checks the
 * document against them after every step, so board A edits must leave board B and the
 * shared outside-region ownership byte-identical.
 *
 * Reported gaps (not implemented here, per contract): [RegionDocumentOps] exposes
 * full-permutation [RegionDocumentOps.reorderFrames] but no AnimOps-style single-frame
 * moveFrame(board, frameId, toIndex); there is no standalone duplicate/clone op beyond
 * addFrame(DUPLICATE/LINK); selectFrame returns a plain document with no pixel work.
 */
class RegionFrameSequenceCorpusTest {

    private val rectA = RectPx(0, 0, 20, 20)
    private val rectB = RectPx(60, 60, 20, 20)
    private val layerIds = listOf("layer-a", "layer-b")

    private fun base(): JbDocument = JbDocument(
        id = "doc-seq",
        name = "Seq",
        boards = listOf(Board("root", "Root", BoardKind.CANVAS, RectPx(0, 0, 200, 200))),
        layers = layerIds.map { Layer(it, it, LayerKind.PAINT, cels = listOf(Cel("shared-$it"))) },
    )

    private class Fixture(var doc: JbDocument, val boardA: String, val boardB: String)

    private fun twoBoard(ids: () -> String): Fixture {
        var doc = RegionDocumentOps.create(base(), "A", rectA, ids, animate = null).doc
        val boardA = doc.boards.last().id
        doc = RegionDocumentOps.create(doc, "B", rectB, ids, animate = null).doc
        return Fixture(doc, boardA, doc.boards.last().id)
    }

    @Test fun `deterministic two board edit sequence preserves ownership`() {
        var n = 0
        val ids: () -> String = { "seq-${++n}" }
        val fixture = twoBoard(ids)
        var doc = fixture.doc
        val boardA = fixture.boardA
        val boardB = fixture.boardB

        // ---- independent expectations (never derived from the op under test) ----
        val orderA = ArrayList(doc.boards.first { it.id == boardA }.frames.map { it.id })
        val orderB = ArrayList(doc.boards.first { it.id == boardB }.frames.map { it.id })
        var currentA = doc.boards.first { it.id == boardA }.currentFrameId!!
        var currentB = doc.boards.first { it.id == boardB }.currentFrameId!!
        val holds = HashMap<String, Int>()
        val mapping = HashMap<String, HashMap<String, HashMap<String, String>>>()
        val held = HashMap<String, HashMap<String, Boolean>>()
        val sharedOf = HashMap<String, String>()
        val celsOf = HashMap<String, MutableSet<String>>()
        for (l in layerIds) {
            val layer = doc.layers.first { it.id == l }
            sharedOf[l] = layer.sharedCelId!!
            celsOf[l] = layer.cels.map { it.id }.toMutableSet()
            mapping[l] = HashMap()
            held[l] = HashMap()
            for (b in listOf(boardA, boardB)) {
                mapping[l]!![b] = HashMap(layer.regions.first { it.boardId == b }.frameCel)
                held[l]!![b] = false
            }
        }

        fun check(tag: String) {
            val problems = DocOps.validate(doc)
            assertTrue(problems.isEmpty(), "valid after $tag: $problems")
            val liveA = doc.boards.first { it.id == boardA }
            val liveB = doc.boards.first { it.id == boardB }
            assertEquals(orderA, liveA.frames.map { it.id }, "A order after $tag")
            assertEquals(orderB, liveB.frames.map { it.id }, "B order after $tag")
            assertEquals(currentA, liveA.currentFrameId, "A cursor after $tag")
            assertEquals(currentB, liveB.currentFrameId, "B cursor after $tag")
            for (frame in liveA.frames + liveB.frames) {
                assertEquals(holds[frame.id] ?: 1, frame.holdFrames, "hold ${frame.id} after $tag")
            }
            for (l in layerIds) {
                val layer = doc.layers.first { it.id == l }
                assertEquals(sharedOf.getValue(l), layer.sharedCelId, "shared $l after $tag")
                assertEquals(celsOf.getValue(l), layer.cels.map { it.id }.toSet(), "cels $l after $tag")
                for (b in listOf(boardA, boardB)) {
                    val region = layer.regions.first { it.boardId == b }
                    assertEquals(mapping.getValue(l).getValue(b), region.frameCel, "map $l/$b after $tag")
                    assertEquals(held.getValue(l).getValue(b), region.held, "held $l/$b after $tag")
                }
                // Paint plan is the outside-region ownership proof: held boards vanish from
                // the plan, shared stays the shared cel, current cels match the mapping.
                val plan = RegionDocumentOps.paintPlan(doc, l)
                assertEquals(sharedOf.getValue(l), plan.shared.celId, "plan shared $l after $tag")
                val expectBoards = listOf(boardA, boardB).filter { !held.getValue(l).getValue(it) }
                assertEquals(expectBoards, plan.frames.map { it.boardId }, "plan boards $l after $tag")
                for (entry in plan.frames) {
                    val cur = if (entry.boardId == boardA) currentA else currentB
                    assertEquals(cur, entry.frameId, "plan cursor $l/${entry.boardId} after $tag")
                    assertEquals(
                        mapping.getValue(l).getValue(entry.boardId).getValue(cur),
                        entry.celId,
                        "plan cel $l/${entry.boardId} after $tag",
                    )
                    assertEquals(
                        if (entry.boardId == boardA) rectA else rectB,
                        entry.rect,
                        "plan rect $l/${entry.boardId} after $tag",
                    )
                }
            }
        }

        fun freshFrame(known: List<String>, board: String): String {
            val live = doc.boards.first { it.id == board }.frames.map { it.id }.toSet()
            return (live - known.toSet()).single()
        }

        check("two-board baseline")

        // ---- add BLANK on A: new empty cels, no copies, B untouched ----
        run {
            val previousCurrent = currentA
            val result = RegionDocumentOps.addFrame(doc, boardA, NewFrame.BLANK, ids)
            assertTrue(result.copies.isEmpty(), "blank copies nothing")
            assertTrue(result.drops.isEmpty() && result.transfers.isEmpty() && result.clears.isEmpty())
            doc = result.doc
            val frame = freshFrame(orderA, boardA)
            orderA.add(orderA.indexOf(previousCurrent) + 1, frame)
            currentA = frame
            holds[frame] = 1
            for (l in layerIds) {
                val cel = doc.layers.first { it.id == l }
                    .regions.first { it.boardId == boardA }.frameCel.getValue(frame)
                assertTrue(cel !in celsOf.getValue(l), "blank cel is new on $l")
                celsOf.getValue(l) += cel
                mapping.getValue(l).getValue(boardA)[frame] = cel
            }
            check("add blank on A")
        }

        // ---- add DUPLICATE on A: per-layer copies from the source frame, B untouched ----
        run {
            val source = layerIds.associateWith { mapping.getValue(it).getValue(boardA).getValue(currentA) }
            val previousCurrent = currentA
            val result = RegionDocumentOps.addFrame(doc, boardA, NewFrame.DUPLICATE, ids)
            doc = result.doc
            val frame = freshFrame(orderA, boardA)
            orderA.add(orderA.indexOf(previousCurrent) + 1, frame)
            currentA = frame
            holds[frame] = 1
            val newCels = HashMap<String, String>()
            for (l in layerIds) {
                val cel = doc.layers.first { it.id == l }
                    .regions.first { it.boardId == boardA }.frameCel.getValue(frame)
                assertTrue(cel !in celsOf.getValue(l), "duplicate cel is new on $l")
                assertTrue(cel != source.getValue(l), "duplicate cel is a copy, not a link, on $l")
                celsOf.getValue(l) += cel
                mapping.getValue(l).getValue(boardA)[frame] = cel
                newCels[l] = cel
            }
            assertEquals(
                layerIds.map { RegionCopy(it, source.getValue(it), newCels.getValue(it), rectA) },
                result.copies,
                "duplicate copies name source, new cel and board rect",
            )
            check("add duplicate on A")
        }

        // ---- add LINK on A: shared cel, no new cels, no copies, B untouched ----
        run {
            val source = layerIds.associateWith { mapping.getValue(it).getValue(boardA).getValue(currentA) }
            val previousCurrent = currentA
            val before = layerIds.associateWith { celsOf.getValue(it).toSet() }
            val result = RegionDocumentOps.addFrame(doc, boardA, NewFrame.LINK, ids)
            assertTrue(result.copies.isEmpty(), "link allocates nothing")
            doc = result.doc
            val frame = freshFrame(orderA, boardA)
            orderA.add(orderA.indexOf(previousCurrent) + 1, frame)
            currentA = frame
            holds[frame] = 1
            for (l in layerIds) {
                val cel = doc.layers.first { it.id == l }
                    .regions.first { it.boardId == boardA }.frameCel.getValue(frame)
                assertEquals(source.getValue(l), cel, "linked frame shares the source cel on $l")
                mapping.getValue(l).getValue(boardA)[frame] = cel
                assertEquals(before.getValue(l), celsOf.getValue(l), "link adds no cels on $l")
            }
            check("add link on A")
        }

        // ---- holds on both boards travel independently ----
        run {
            doc = RegionDocumentOps.setHold(doc, boardA, orderA[1], 4)
            holds[orderA[1]] = 4
            doc = RegionDocumentOps.setHold(doc, boardB, currentB, 3)
            holds[currentB] = 3
            check("holds on A and B")
        }

        // ---- reorder A: order changes, cursor/mappings/holds follow ids, B fixed ----
        run {
            val permutation = orderA.reversed()
            doc = RegionDocumentOps.reorderFrames(doc, boardA, permutation)
            orderA.reverse()
            check("reorder A")
        }

        // ---- select first frame on A; B cursor is independent ----
        run {
            doc = RegionDocumentOps.selectFrame(doc, boardA, orderA.first())
            currentA = orderA.first()
            check("select first frame on A")
        }

        // ---- hold layer-a in A: copies current into shared; layer-b and B unaffected ----
        run {
            val currentCel = mapping.getValue("layer-a").getValue(boardA).getValue(currentA)
            val result = RegionDocumentOps.setHeld(doc, boardA, "layer-a", true)
            assertEquals(
                listOf(RegionTransfer("layer-a", currentCel, sharedOf.getValue("layer-a"), rectA, rectA)),
                result.transfers,
            )
            doc = result.doc
            held.getValue("layer-a")[boardA] = true
            check("hold layer-a in A")
            // Re-holding is a no-op returning the same document.
            val again = RegionDocumentOps.setHeld(doc, boardA, "layer-a", true)
            assertTrue(
                again.copies.isEmpty() && again.drops.isEmpty() &&
                    again.transfers.isEmpty() && again.clears.isEmpty(),
            )
            assertEquals(doc, again.doc)
        }

        // ---- unhold moves the shared art back into the current frame only ----
        run {
            val currentCel = mapping.getValue("layer-a").getValue(boardA).getValue(currentA)
            val result = RegionDocumentOps.setHeld(doc, boardA, "layer-a", false)
            assertEquals(
                listOf(RegionTransfer("layer-a", sharedOf.getValue("layer-a"), currentCel, rectA, rectA)),
                result.transfers,
            )
            assertEquals(
                listOf(RegionClear("layer-a", sharedOf.getValue("layer-a"), rectA)),
                result.clears,
            )
            doc = result.doc
            held.getValue("layer-a")[boardA] = false
            check("unhold layer-a in A")
        }

        // ---- delete the SELECTED frame (linked: cel survives via its partner) ----
        run {
            val victim = currentA // orderA.first() after the select above
            val result = RegionDocumentOps.deleteFrame(doc, boardA, victim)
            assertTrue(result.drops.isEmpty(), "linked cel survives through its partner frame")
            doc = result.doc
            orderA.remove(victim)
            for (l in layerIds) mapping.getValue(l).getValue(boardA).remove(victim)
            currentA = doc.boards.first { it.id == boardA }.currentFrameId!!
            assertEquals(orderA.first(), currentA, "cursor falls back to the adjacent frame")
            check("delete selected linked frame on A")
        }

        // ---- delete a non-current frame: cursor stays, unique cels drop, B fixed ----
        run {
            val victim = orderA.last()
            val victimCels = layerIds.associateWith {
                mapping.getValue(it).getValue(boardA).getValue(victim)
            }
            val cursorBefore = currentA
            val result = RegionDocumentOps.deleteFrame(doc, boardA, victim)
            assertEquals(
                layerIds.map { RegionDrop(it, victimCels.getValue(it)) },
                result.drops,
            )
            doc = result.doc
            orderA.remove(victim)
            for (l in layerIds) {
                mapping.getValue(l).getValue(boardA).remove(victim)
                celsOf.getValue(l) -= victimCels.getValue(l)
            }
            assertEquals(cursorBefore, currentA)
            assertEquals(cursorBefore, doc.boards.first { it.id == boardA }.currentFrameId)
            check("delete non-current frame on A")
        }

        // ---- delete down to the last allowable frame, then refusal ----
        run {
            while (orderA.size > 1) {
                val victim = orderA.last()
                val victimCels = layerIds.associateWith {
                    mapping.getValue(it).getValue(boardA).getValue(victim)
                }
                val result = RegionDocumentOps.deleteFrame(doc, boardA, victim)
                doc = result.doc
                orderA.remove(victim)
                for (l in layerIds) {
                    mapping.getValue(l).getValue(boardA).remove(victim)
                    // The linked pair shares one cel: only drop it from expectations when
                    // no remaining frame names it.
                    if (victimCels.getValue(l) !in mapping.getValue(l).getValue(boardA).values) {
                        celsOf.getValue(l) -= victimCels.getValue(l)
                    }
                }
                currentA = doc.boards.first { it.id == boardA }.currentFrameId!!
            }
            check("A at last allowable frame")
            val snap = doc
            assertFailsWith<DocException> {
                RegionDocumentOps.deleteFrame(doc, boardA, orderA.single())
            }
            assertEquals(snap, doc, "refused last-frame deletion changes nothing")
            check("last-frame refusal on A")
        }
    }

    @Test fun `invalid frame and board ids are refused without mutation`() {
        var n = 0
        val ids: () -> String = { "bad-${++n}" }
        val fixture = twoBoard(ids)
        var doc = RegionDocumentOps.addFrame(fixture.doc, fixture.boardA, NewFrame.BLANK, ids).doc
        val boardA = fixture.boardA
        val boardB = fixture.boardB
        val orderA = doc.boards.first { it.id == boardA }.frames.map { it.id }
        val currentB = doc.boards.first { it.id == boardB }.currentFrameId!!

        val snap = doc
        // Unknown boards and frames.
        assertFailsWith<DocException> { RegionDocumentOps.addFrame(doc, "no-board", NewFrame.BLANK, ids) }
        assertFailsWith<DocException> { RegionDocumentOps.deleteFrame(doc, "no-board", orderA.first()) }
        assertFailsWith<DocException> { RegionDocumentOps.selectFrame(doc, "no-board", orderA.first()) }
        assertFailsWith<DocException> { RegionDocumentOps.deleteFrame(doc, boardA, "no-frame") }
        assertFailsWith<DocException> { RegionDocumentOps.selectFrame(doc, boardA, "no-frame") }
        assertFailsWith<DocException> { RegionDocumentOps.setHold(doc, boardA, "no-frame", 2) }
        assertFailsWith<DocException> { RegionDocumentOps.setHeld(doc, boardA, "no-layer", true) }
        // The passive root board is not a region animation board.
        assertFailsWith<DocException> { RegionDocumentOps.addFrame(doc, "root", NewFrame.BLANK, ids) }
        assertFailsWith<DocException> { RegionDocumentOps.setHeld(doc, "root", layerIds.first(), true) }
        // Reorder must name every frame exactly once.
        assertFailsWith<DocException> { RegionDocumentOps.reorderFrames(doc, boardA, emptyList()) }
        assertFailsWith<DocException> { RegionDocumentOps.reorderFrames(doc, boardA, listOf(orderA.first())) }
        assertFailsWith<DocException> {
            RegionDocumentOps.reorderFrames(doc, boardA, listOf(orderA.first(), orderA.first()))
        }
        // Untouched board B still guards its last frame.
        assertFailsWith<DocException> { RegionDocumentOps.deleteFrame(doc, boardB, currentB) }
        assertEquals(snap, doc, "every refusal leaves the document unchanged")
        assertTrue(DocOps.validate(doc).isEmpty())
    }
}
