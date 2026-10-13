package cc.joycreator.joybrush.core.doc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JB-NOW-19 — layer clone alias/link preservation corpus.
 *
 * Exercises the real [LayerContentClone.plan] with shared cels, repeated linked
 * cels, two non-overlapping region animations and deterministic ID allocation.
 * Every expectation is built from the SOURCE document plus a known supplier
 * sequence, never from the clone under test.
 *
 * Document clone proof only: old-to-new physical cel mapping, linked frames stay
 * linked inside the clone, the clone never aliases an original ID, and
 * [LayerContentClone.copies] holds exactly one entry per
 * [DocOps.storedCels] entry (shared source content is deduplicated, as the plan
 * documents). Visibility, lock, blend and opacity are preserved.
 *
 * Byte/payload transfer is deliberately NOT proven here. The plan remaps IDs
 * into empty [Cel] shells; copying tile bytes, mask coverage, INK record bytes
 * or MEDIA float state needs the caller's executor (for example the
 * `RegionCopy` / GPU path in `GlPaintEngine.duplicateLayerStep`), which this
 * corpus never invokes.
 *
 * Limits: no tile/float/strokes bytes are asserted copied (they are asserted
 * ABSENT); no media ground, paper look, atomic GL execution, Undo coalescing,
 * save/open or device behaviour is claimed. Unsupported payload semantics are
 * the executor's job, not the plan's.
 */
class LayerCloneIdentityCorpusTest {

    private val rectA = RectPx(0, 0, 20, 20)
    private val rectB = RectPx(60, 60, 20, 20)

    private fun styledPaint(id: String, celId: String, maskId: String): Layer = Layer(
        id = id,
        name = "Paint",
        kind = LayerKind.PAINT,
        visible = false,
        locked = true,
        opacity = 0.25f,
        blend = BlendMode.MULTIPLY,
        cels = listOf(Cel(celId)),
        mask = Cel(maskId, tiles = listOf("1_1")),
    )

    private fun baseDoc(layer: Layer): JbDocument = JbDocument(
        id = "doc-clone",
        name = "Clone",
        boards = listOf(Board("root", "Root", BoardKind.CANVAS, RectPx(0, 0, 200, 200))),
        layers = listOf(layer),
    )

    private fun regionOf(layer: Layer, boardId: String): Map<String, String> =
        layer.regions.first { it.boardId == boardId }.frameCel

    private fun partition(frameCel: Map<String, String>): Map<String, Set<String>> {
        val out = HashMap<String, MutableSet<String>>()
        for ((frame, cel) in frameCel) out.getOrPut(cel) { HashSet() } += frame
        return out.mapValues { it.value.toSet() }
    }

    @Test fun `shared canvas plus linked pair clones once per stored cel with links intact`() {
        var m = 0
        val docIds: () -> String = { "doc-a-${++m}" }
        var n = 0
        val cloneIds: () -> String = { "clone-a-${++n}" }

        var doc = RegionDocumentOps.create(baseDoc(styledPaint("paint", "shared-a", "mask-a")), "A", rectA, docIds).doc
        val boardA = doc.activeBoardId!!
        doc = RegionDocumentOps.addFrame(doc, boardA, NewFrame.LINK, docIds).doc
        val source = doc.layers.single()
        assertTrue(DocOps.validate(doc).isEmpty())

        // Independent source shape: shared canvas + one frame cel shared by two frames + mask.
        val stored = DocOps.storedCels(source)
        assertEquals(3, stored.size, "shared + one frame cel + mask")
        assertEquals(listOf("shared-a", source.regions.single().frameCel.values.first(), "mask-a"), stored.map { it.id })
        val frames = doc.boards.first { it.id == boardA }.frames.map { it.id }
        assertEquals(2, frames.size)
        assertEquals(1, regionOf(source, boardA).values.toSet().size, "linked pair shares one cel")
        assertEquals(setOf(frames[0], frames[1]), partition(regionOf(source, boardA)).values.single())

        val clone = LayerContentClone.plan(source, "copy-paint", "Paint copy", cloneIds)

        // Deterministic allocation follows storedCels order.
        assertEquals(stored.map { it.id }, clone.copies.map { it.fromCelId })
        assertEquals(listOf("clone-a-1", "clone-a-2", "clone-a-3"), clone.copies.map { it.toCelId })
        val expected = stored.map { it.id }.zip(clone.copies.map { it.toCelId }).toMap()
        assertTrue(clone.copies.all { it.fromCelId != it.toCelId }, "no self-mapping")
        assertEquals(3, clone.copies.map { it.toCelId }.toSet().size, "fresh IDs are distinct")
        assertTrue(clone.copies.none { it.toCelId in stored.map { c -> c.id }.toSet() }, "no alias of an original")

        // Old-to-new mapping is applied everywhere IDs live.
        assertEquals("copy-paint", clone.layer.id)
        assertEquals("Paint copy", clone.layer.name)
        assertEquals(listOf(expected.getValue("shared-a"), expected.getValue(stored[1].id)), clone.layer.cels.map { it.id })
        assertEquals(expected.getValue("shared-a"), clone.layer.sharedCelId)
        assertEquals(expected.getValue("mask-a"), clone.layer.mask?.id)
        assertEquals(
            regionOf(source, boardA).mapValues { expected.getValue(it.value) },
            regionOf(clone.layer, boardA),
        )
        // Linked references remain linked within the clone.
        assertEquals(1, regionOf(clone.layer, boardA).values.toSet().size)
        assertEquals(setOf(frames[0], frames[1]), partition(regionOf(clone.layer, boardA)).values.single())

        // Presentation fields survive; payload shells stay empty (bytes belong to the executor).
        assertEquals(source.kind, clone.layer.kind)
        assertEquals(false, clone.layer.visible)
        assertEquals(true, clone.layer.locked)
        assertEquals(0.25f, clone.layer.opacity)
        assertEquals(BlendMode.MULTIPLY, clone.layer.blend)
        assertEquals(source.clip, clone.layer.clip)
        assertEquals(source.animatedIn, clone.layer.animatedIn)
        assertTrue(clone.layer.frameCel.isEmpty(), "region layers carry no legacy frame map")
        assertTrue(clone.layer.cels.all { it.tiles.isEmpty() && it.strokesFile == null && it.floatTiles.isEmpty() })
        assertTrue(clone.layer.mask?.tiles?.isEmpty() == true)
        assertTrue(DocOps.validate(doc.copy(layers = doc.layers + clone.layer)).isEmpty())
    }

    @Test fun `three repeated linked frames stay linked and deduplicate copies`() {
        var m = 0
        val docIds: () -> String = { "doc-b-${++m}" }
        var n = 0
        val cloneIds: () -> String = { "clone-b-${++n}" }

        var doc = RegionDocumentOps.create(baseDoc(Layer("paint", "Paint", LayerKind.PAINT, cels = listOf(Cel("shared-b")))), "A", rectA, docIds).doc
        val boardA = doc.activeBoardId!!
        doc = RegionDocumentOps.addFrame(doc, boardA, NewFrame.LINK, docIds).doc
        doc = RegionDocumentOps.addFrame(doc, boardA, NewFrame.LINK, docIds).doc
        val source = doc.layers.single()
        assertTrue(DocOps.validate(doc).isEmpty())

        val frames = doc.boards.first { it.id == boardA }.frames.map { it.id }
        assertEquals(3, frames.size)
        assertEquals(1, regionOf(source, boardA).values.toSet().size, "three frames share one cel")
        // Two stored cels (shared canvas + one frame cel); three frame references deduplicate to one copy.
        assertEquals(2, DocOps.storedCels(source).size)
        assertEquals(3, regionOf(source, boardA).size)

        val clone = LayerContentClone.plan(source, "copy-b", "Copy B", cloneIds)

        assertEquals(2, clone.copies.size, "one copy per stored cel, not per frame reference")
        assertEquals(listOf("clone-b-1", "clone-b-2"), clone.copies.map { it.toCelId })
        val expected = DocOps.storedCels(source).map { it.id }.zip(clone.copies.map { it.toCelId }).toMap()
        assertEquals(
            regionOf(source, boardA).mapValues { expected.getValue(it.value) },
            regionOf(clone.layer, boardA),
        )
        assertEquals(1, regionOf(clone.layer, boardA).values.toSet().size)
        assertEquals(frames.toSet(), partition(regionOf(clone.layer, boardA)).values.single())
        assertTrue(clone.layer.cels.none { cel -> source.cels.any { it.id == cel.id } })
        assertTrue(DocOps.validate(doc.copy(layers = doc.layers + clone.layer)).isEmpty())
    }

    @Test fun `two region boards remap independently while sharing one deterministic supplier`() {
        var m = 0
        val docIds: () -> String = { "doc-c-${++m}" }
        var n = 0
        val cloneIds: () -> String = { "clone-c-${++n}" }

        var doc = RegionDocumentOps.create(
            baseDoc(Layer("paint", "Paint", LayerKind.PAINT, visible = true, locked = false, opacity = 0.75f, blend = BlendMode.SCREEN, cels = listOf(Cel("shared-c")), mask = Cel("mask-c"))),
            "A", rectA, docIds,
        ).doc
        val boardA = doc.activeBoardId!!
        doc = RegionDocumentOps.create(doc, "B", rectB, docIds).doc
        val boardB = doc.activeBoardId!!
        // A: linked pair shares one cel. B: blank adds a second distinct cel.
        doc = RegionDocumentOps.addFrame(doc, boardA, NewFrame.LINK, docIds).doc
        doc = RegionDocumentOps.addFrame(doc, boardB, NewFrame.BLANK, docIds).doc
        // Hold B: board identity and mappings must still clone; held only changes the paint plan.
        doc = RegionDocumentOps.setHeld(doc, boardB, "paint", true).doc
        val source = doc.layers.single()
        assertTrue(DocOps.validate(doc).isEmpty())
        assertEquals(2, source.regions.size)

        val stored = DocOps.storedCels(source)
        // Shared + one A cel + two B cels + mask.
        assertEquals(5, stored.size)
        assertEquals(1, regionOf(source, boardA).values.toSet().size)
        assertEquals(2, regionOf(source, boardB).values.toSet().size)
        assertTrue(regionOf(source, boardA).values.toSet().intersect(regionOf(source, boardB).values.toSet()).isEmpty(), "frame cels are never shared between boards")

        val clone = LayerContentClone.plan(source, "copy-c", "Copy C", cloneIds)

        assertEquals(stored.map { it.id }, clone.copies.map { it.fromCelId })
        assertEquals((1..5).map { "clone-c-$it" }, clone.copies.map { it.toCelId })
        val expected = stored.map { it.id }.zip(clone.copies.map { it.toCelId }).toMap()
        assertEquals(2, clone.layer.regions.size)
        assertEquals(listOf(boardA, boardB), clone.layer.regions.map { it.boardId }, "board identities are preserved in order")
        assertEquals(source.regions.map { it.held }, clone.layer.regions.map { it.held }, "held flags are preserved")
        assertEquals(
            source.regions.map { it.frameCel.keys },
            clone.layer.regions.map { it.frameCel.keys },
            "frame identities are preserved; only cel addresses are remapped",
        )
        for (board in listOf(boardA, boardB)) {
            assertEquals(
                regionOf(source, board).mapValues { expected.getValue(it.value) },
                regionOf(clone.layer, board),
            )
        }
        // Link shape survives per board: A still linked, B still distinct.
        assertEquals(1, regionOf(clone.layer, boardA).values.toSet().size)
        assertEquals(2, regionOf(clone.layer, boardB).values.toSet().size)
        assertTrue(regionOf(clone.layer, boardA).values.toSet().intersect(regionOf(clone.layer, boardB).values.toSet()).isEmpty())
        // Shared canvas and mask follow the same deterministic map.
        assertEquals(expected.getValue("shared-c"), clone.layer.sharedCelId)
        assertEquals(expected.getValue("mask-c"), clone.layer.mask?.id)
        assertEquals(0.75f, clone.layer.opacity)
        assertEquals(BlendMode.SCREEN, clone.layer.blend)
        assertTrue(DocOps.validate(doc.copy(layers = doc.layers + clone.layer)).isEmpty())
    }

    @Test fun `deterministic allocation follows stored order and refuses id collisions`() {
        var m = 0
        val docIds: () -> String = { "doc-d-${++m}" }
        var doc = RegionDocumentOps.create(baseDoc(Layer("paint", "Paint", LayerKind.PAINT, cels = listOf(Cel("shared-d")), mask = Cel("mask-d"))), "A", rectA, docIds).doc
        val source = doc.layers.single()
        val snapshot = source
        val storedIds = DocOps.storedCels(source).map { it.id }

        val fixed = listOf("fixed-1", "fixed-2", "fixed-3").iterator()
        val clone = LayerContentClone.plan(source, "copy-d", "Copy D") { fixed.next() }
        assertEquals(storedIds, clone.copies.map { it.fromCelId })
        assertEquals(listOf("fixed-1", "fixed-2", "fixed-3"), clone.copies.map { it.toCelId })

        assertFailsWith<IllegalArgumentException> {
            LayerContentClone.plan(source, "", "Copy") { "unused-1" }
        }
        assertFailsWith<IllegalArgumentException> {
            LayerContentClone.plan(source, source.id, "Copy") { "unused-2" }
        }
        assertFailsWith<IllegalArgumentException> {
            LayerContentClone.plan(source, "copy-blank", "Copy") { "" }
        }
        assertFailsWith<IllegalArgumentException> {
            LayerContentClone.plan(source, "copy-clash", "Copy") { storedIds.first() }
        }
        var calls = 0
        assertFailsWith<IllegalArgumentException> {
            LayerContentClone.plan(source, "copy-repeat", "Copy") { calls++; "repeat" }
        }
        assertEquals(snapshot, source, "refused plans never mutate the source layer value")
        assertTrue(DocOps.validate(doc).isEmpty())
    }

    @Test fun `clone carries no bytes and preserves presentation on static paint ink and media`() {
        val paint = Layer(
            "paint-s", "Paint", LayerKind.PAINT,
            visible = false, locked = true, opacity = 0.5f, blend = BlendMode.MULTIPLY,
            cels = listOf(Cel("paint-cel", tiles = listOf("0_0", "1_0"))),
        )
        val ink = Layer(
            "ink-s", "Ink", LayerKind.INK,
            visible = true, locked = true, opacity = 0.8f, blend = BlendMode.SCREEN,
            cels = listOf(Cel("ink-cel", strokesFile = "ink.bin")),
        )
        // JB-2.40: MEDIA is legacy and refused at version 9; media state lives on a PAINT layer.
        val media = Layer(
            "media-s", "Media", LayerKind.PAINT,
            visible = true, locked = false, opacity = 0.6f, blend = BlendMode.NORMAL,
            cels = listOf(Cel("media-cel", tiles = listOf("0_0"), floatTiles = listOf("0_0"))),
        )
        val doc = JbDocument(
            id = "doc-static", version = 9,
            name = "Static",
            boards = listOf(Board("root", "Root", BoardKind.CANVAS, RectPx(0, 0, 64, 64))),
            layers = listOf(paint, ink, media),
        )
        assertTrue(DocOps.validate(doc).isEmpty())

        var n = 0
        val ids: () -> String = { "static-${++n}" }
        val paintClone = LayerContentClone.plan(paint, "copy-paint-s", "Paint copy", ids)
        val inkClone = LayerContentClone.plan(ink, "copy-ink-s", "Ink copy", ids)
        val mediaClone = LayerContentClone.plan(media, "copy-media-s", "Media copy", ids)

        // One stored cel each, deterministic across the shared supplier.
        assertEquals(listOf("static-1"), paintClone.copies.map { it.toCelId })
        assertEquals(listOf("static-2"), inkClone.copies.map { it.toCelId })
        assertEquals(listOf("static-3"), mediaClone.copies.map { it.toCelId })
        assertEquals("paint-cel", paintClone.copies.single().fromCelId)
        assertEquals("ink-cel", inkClone.copies.single().fromCelId)
        assertEquals("media-cel", mediaClone.copies.single().fromCelId)

        // Document proof only: new IDs address empty shells. Tile bytes, stroke-file
        // bytes and MEDIA float bytes are the executor's RegionCopy/GPU work.
        for (result in listOf(paintClone, inkClone, mediaClone)) {
            assertEquals(1, result.layer.cels.size)
            assertEquals(result.copies.single().toCelId, result.layer.cels.single().id)
            assertTrue(result.layer.cels.single().tiles.isEmpty())
            assertTrue(result.layer.cels.single().strokesFile == null)
            assertTrue(result.layer.cels.single().floatTiles.isEmpty())
        }
        assertEquals(LayerKind.PAINT, paintClone.layer.kind)
        assertEquals(LayerKind.INK, inkClone.layer.kind)
        assertEquals(LayerKind.PAINT, mediaClone.layer.kind) // JB-2.40: media is a PAINT layer with float tiles
        assertEquals(false, paintClone.layer.visible)
        assertEquals(true, inkClone.layer.locked)
        assertEquals(0.5f, paintClone.layer.opacity)
        assertEquals(BlendMode.MULTIPLY, paintClone.layer.blend)
        assertEquals(0.8f, inkClone.layer.opacity)
        assertEquals(BlendMode.SCREEN, inkClone.layer.blend)
        assertEquals(0.6f, mediaClone.layer.opacity)
        val withCopies = doc.copy(layers = doc.layers + listOf(paintClone.layer, inkClone.layer, mediaClone.layer))
        assertTrue(DocOps.validate(withCopies).isEmpty())
    }

    @Test fun `mask is cloned once with its own fresh id and no payload`() {
        var m = 0
        val docIds: () -> String = { "doc-e-${++m}" }
        var n = 0
        val cloneIds: () -> String = { "clone-e-${++n}" }
        var doc = RegionDocumentOps.create(baseDoc(styledPaint("paint", "shared-e", "mask-e")), "A", rectA, docIds).doc
        val source = doc.layers.single()
        val clone = LayerContentClone.plan(source, "copy-e", "Copy E", cloneIds)

        val stored = DocOps.storedCels(source)
        assertEquals(stored.last().id, "mask-e")
        assertEquals("mask-e", clone.copies.last().fromCelId)
        assertEquals("clone-e-3", clone.copies.last().toCelId)
        assertEquals("clone-e-3", clone.layer.mask?.id)
        assertTrue(clone.layer.mask?.tiles?.isEmpty() == true)
        assertTrue(clone.layer.mask?.strokesFile == null)
        assertTrue(clone.layer.mask?.floatTiles?.isEmpty() == true)
        assertTrue(stored.none { it.id == clone.layer.mask?.id }, "mask id is fresh")
        assertTrue(DocOps.validate(doc.copy(layers = doc.layers + clone.layer)).isEmpty())
    }
}
