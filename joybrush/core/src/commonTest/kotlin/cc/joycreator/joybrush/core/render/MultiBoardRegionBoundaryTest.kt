package cc.joycreator.joybrush.core.render

import cc.joycreator.joybrush.core.chrome.BoardLayerPreview
import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.RegionDocumentOps
import cc.joycreator.joybrush.core.doc.RegionFrame
import cc.joycreator.joybrush.core.doc.RegionFrames
import cc.joycreator.joybrush.core.doc.RegionPaintPlan
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * JB-NOW-13: multi-board clipping and preview boundary regression.
 *
 * Uses only real public core APIs ([RegionRenderer.render] with a deterministic fake
 * [TileSource], [DocOps], [RegionPaintPlan], [RegionDocumentOps] and
 * [BoardLayerPreview.scope]). Never implements a second renderer: every expected pixel
 * below is hand-computed from the fixture description (which tile a coordinate falls in
 * via [DocOps.tileOf], and whether it lies inside a board's half-open rectangle), never
 * from renderer internals.
 *
 * Fixture pattern (negative, non-tile-multiple origins straddling 256px seams, per-tile
 * solids plus 1px corner markers) follows the setup read from
 * `androidkit/.../io/SpriteSwapExportTest` and `render/RegionTileSourceTest` without
 * editing or copying them: the values here are independently chosen so a mirrored,
 * shifted or whole-tile-clip implementation fails.
 *
 * CPU-only. No GPU, phone appearance or performance claim.
 *
 * Coverage versus existing suites (read before writing):
 * - `RegionRendererTest` covers single-board crops, negative four-tile straddle, paper,
 *   blend, budget and cel-id namespacing for STATIC layers, but has no region
 *   (animation-board) board-boundary case and no two-board overlap case.
 * - `RegionTileSourceTest` covers saved-cursor vs export override, held regions, two
 *   non-overlapping boards in one tile and negative edges, but only on a 4x1 strip and
 *   without left/right/top/bottom edge-exactness, wholly-outside renders, or
 *   passive-overlap intersection equality.
 * - `RegionPaintPlanTest` covers partition geometry, not pixels.
 * - `BoardChromeSessionTest` covers preview scope math, not rendering through it.
 * This file adds the missing pixels: overlapping passive-board intersection equality,
 * region-board four-edge pixel-exactness, wholly-outside behaviour, frame/cel selector
 * isolation with source-immutability, preview-bounds crop semantics (explicitly NOT the
 * Android thumbnail wiring), and the documented overlapping-animation refusal.
 */
class MultiBoardRegionBoundaryTest {

    // ── Test 1: two overlapping PASSIVE boards share identical intersection pixels ──

    /**
     * Two CANVAS boards may overlap (JB-3.00a B7: only animation-animation overlap is
     * refused). Both are crops over the same sparse tiles, so their intersection must be
     * byte-identical. Origins are negative and non-tile-aligned (-5,-3) and (0,-1);
     * both rects straddle the x=0 and y=0 tile seams (tiles -1 and 0 on each axis).
     */
    @Test
    fun overlappingPassiveBoardsShareIdenticalIntersectionPixels() {
        val rectA = RectPx(-5, -3, 10, 8) // x in [-5,5), y in [-3,5)
        val rectB = RectPx(0, -1, 10, 8) // x in [0,10), y in [-1,7)
        // Hand-computed intersection: x in [0,5), y in [-1,5) -> RectPx(0,-1,5,6).
        val inter = RectPx(0, -1, 5, 6)
        assertEquals(0, inter.x)
        assertEquals(5, inter.w)
        assertEquals(6, inter.h)

        val doc = JbDocument(
            id = "d",
            name = "D",
            boards = listOf(
                Board("a", "A", BoardKind.CANVAS, rectA),
                Board("b", "B", BoardKind.CANVAS, rectB),
            ),
            layers = listOf(
                Layer(id = "L", name = "L", kind = LayerKind.PAINT, cels = listOf(Cel("L-cel"))),
            ),
        )
        assertTrue(DocOps.validate(doc).isEmpty(), "fixture must be valid: ${DocOps.validate(doc)}")

        // Per-tile opaque solids (premultiplied == straight at alpha 255):
        // (-1,-1)=red, (0,-1)=green, (-1,0)=blue, (0,0)=white.
        val tiles = HashMap<TKey, ByteArray>()
        fillTile(tiles, "L", "L-cel", -1, -1, 255, 0, 0, 255)
        fillTile(tiles, "L", "L-cel", 0, -1, 0, 255, 0, 255)
        fillTile(tiles, "L", "L-cel", -1, 0, 0, 0, 255, 255)
        fillTile(tiles, "L", "L-cel", 0, 0, 255, 255, 255, 255)
        // 1px markers catch mirroring/shift: white at A's top-left, black at B's top-left.
        paintPixel(tiles, "L", "L-cel", -5, -3, 255, 255, 255, 255)
        paintPixel(tiles, "L", "L-cel", 0, -1, 0, 0, 0, 255)
        val source: TileSource = FakeTiles(tiles)

        val outA = RegionRenderer.render(doc, source, rectA, null, null)
        val outB = RegionRenderer.render(doc, source, rectB, null, null)
        // Exact dimensions: no padding, no truncation.
        assertEquals(10 * 8 * 4, outA.size, "board A dimensions")
        assertEquals(10 * 8 * 4, outB.size, "board B dimensions")

        // Hand-computed spot checks for A (tile via DocOps.tileOf, colour via fixture above).
        assertEquals(listOf(255, 255, 255, 255), pixelAt(outA, rectA, -5, -3), "A top-left marker")
        assertEquals(listOf(255, 0, 0, 255), pixelAt(outA, rectA, -4, -3), "same tile, next column still red")
        assertEquals(listOf(255, 0, 0, 255), pixelAt(outA, rectA, -1, -1), "last col/row of tile (-1,-1)")
        assertEquals(listOf(0, 0, 0, 255), pixelAt(outA, rectA, 0, -1), "first col of tile (0,-1) is the black marker")
        assertEquals(listOf(0, 255, 0, 255), pixelAt(outA, rectA, 1, -1), "next column green")
        assertEquals(listOf(0, 0, 255, 255), pixelAt(outA, rectA, -1, 0), "tile (-1,0) blue")
        assertEquals(listOf(255, 255, 255, 255), pixelAt(outA, rectA, 0, 0), "tile (0,0) white")
        assertEquals(listOf(255, 255, 255, 255), pixelAt(outA, rectA, 4, 4), "A last pixel")

        // Hand-computed spot checks for B.
        assertEquals(listOf(0, 0, 0, 255), pixelAt(outB, rectB, 0, -1), "B top-left marker")
        assertEquals(listOf(0, 255, 0, 255), pixelAt(outB, rectB, 1, -1), "B next column green")
        assertEquals(listOf(255, 255, 255, 255), pixelAt(outB, rectB, 0, 0), "B white")
        assertEquals(listOf(255, 255, 255, 255), pixelAt(outB, rectB, 9, 6), "B last pixel")

        // Identical underlying tiles through different board bounds: every intersection
        // pixel must agree exactly.
        for (y in inter.y until inter.y + inter.h) {
            for (x in inter.x until inter.x + inter.w) {
                assertEquals(
                    pixelAt(outA, rectA, x, y),
                    pixelAt(outB, rectB, x, y),
                    "intersection pixel $x,$y differs between overlapping boards",
                )
            }
        }
        // The shared corner marker proves the equality is not vacuous (both show black).
        assertEquals(listOf(0, 0, 0, 255), pixelAt(outA, rectA, 0, -1))
        assertEquals(listOf(0, 0, 0, 255), pixelAt(outB, rectB, 0, -1))
    }

    // ── Test 2: region board clips pixel-exact on all four edges ──

    /**
     * JB-3.00a B9/B10: inside the edge a pixel comes from the current frame, outside
     * from the shared canvas; the rule is a half-open clip rectangle, never whole tiles.
     * Board RectPx(-2,-1,6,4) is negative, non-tile-aligned and straddles four tiles.
     * The expanded render rect is the board grown by 2px on every side.
     */
    @Test
    fun regionBoardClipsPixelExactOnAllFourEdges() {
        val boardRect = RectPx(-2, -1, 6, 4) // x in [-2,4), y in [-1,3)
        val wide = RectPx(-4, -3, 10, 8) // x in [-4,6), y in [-3,5)
        assertEquals(boardRect.x - 2, wide.x)
        assertEquals(boardRect.y - 2, wide.y)

        val doc = regionDoc(boardRect, current = "f2")
        assertTrue(DocOps.validate(doc).isEmpty(), "region fixture must be valid: ${DocOps.validate(doc)}")
        val tiles = regionTiles()
        val before = tiles.mapValues { (_, v) -> v.copyOf() }

        // Saved cursor is f2 (blue, white marker at board top-left).
        val outF2 = RegionRenderer.render(doc, FakeTiles(tiles), wide, null, null)
        assertEquals(10 * 8 * 4, outF2.size, "expanded render dimensions")

        // Four edges, half-open: last inside column is x=3 (-2+6-1), first outside is 4;
        // last inside row is y=2 (-1+4-1), first outside is 3.
        assertEquals(listOf(255, 0, 0, 255), pixelAt(outF2, wide, -3, 0), "left outside is shared red")
        assertEquals(listOf(0, 0, 255, 255), pixelAt(outF2, wide, -2, 0), "left inside is frame blue")
        assertEquals(listOf(0, 0, 255, 255), pixelAt(outF2, wide, 3, 0), "right last-inside is blue")
        assertEquals(listOf(255, 0, 0, 255), pixelAt(outF2, wide, 4, 0), "right first-outside is red")
        assertEquals(listOf(255, 0, 0, 255), pixelAt(outF2, wide, 0, -2), "top outside is red")
        assertEquals(listOf(0, 0, 255, 255), pixelAt(outF2, wide, 0, -1), "top inside is blue")
        assertEquals(listOf(0, 0, 255, 255), pixelAt(outF2, wide, 0, 2), "bottom last-inside is blue")
        assertEquals(listOf(255, 0, 0, 255), pixelAt(outF2, wide, 0, 3), "bottom first-outside is red")
        // Corners of the expanded rect are wholly outside: shared red.
        assertEquals(listOf(255, 0, 0, 255), pixelAt(outF2, wide, -4, -3))
        assertEquals(listOf(255, 0, 0, 255), pixelAt(outF2, wide, 5, 4))
        // Markers: inside top-left shows the FRAME marker (white), outside near-corner
        // shows the SHARED marker (yellow) even though both live in tile (-1,-1).
        assertEquals(listOf(255, 255, 255, 255), pixelAt(outF2, wide, -2, -1), "inside marker is frame white")
        assertEquals(listOf(255, 255, 0, 255), pixelAt(outF2, wide, -3, -2), "outside marker is shared yellow")

        // Explicit frame override f1 (green, black marker): inside follows f1, outside unchanged.
        val outF1 = RegionRenderer.render(doc, FakeTiles(tiles), wide, "f1", null)
        assertEquals(10 * 8 * 4, outF1.size)
        assertEquals(listOf(0, 255, 0, 255), pixelAt(outF1, wide, -2, 0), "f1 left inside is green")
        assertEquals(listOf(0, 255, 0, 255), pixelAt(outF1, wide, 3, 0), "f1 right inside is green")
        assertEquals(listOf(0, 255, 0, 255), pixelAt(outF1, wide, 0, -1), "f1 top inside is green")
        assertEquals(listOf(0, 255, 0, 255), pixelAt(outF1, wide, 0, 2), "f1 bottom inside is green")
        assertEquals(listOf(0, 0, 0, 255), pixelAt(outF1, wide, -2, -1), "f1 inside marker is black")
        assertEquals(listOf(255, 0, 0, 255), pixelAt(outF1, wide, -3, 0), "f1 outside still red")
        assertEquals(listOf(255, 255, 0, 255), pixelAt(outF1, wide, -3, -2), "f1 outside marker still yellow")
        assertEquals(listOf(255, 0, 0, 255), pixelAt(outF1, wide, 4, 0), "f1 right outside still red")

        // Untouched source bytes stay unchanged after both renders.
        for ((k, v) in before) assertContentEquals(v, tiles.getValue(k), "source tile $k mutated")
    }

    // ── Test 3: wholly outside the selected board ──

    @Test
    fun whollyOutsideSelectedBoardShowsSharedOrTransparent() {
        val boardRect = RectPx(-2, -1, 6, 4)
        val doc = regionDoc(boardRect, current = "f2")
        assertTrue(DocOps.validate(doc).isEmpty())
        val tiles = regionTiles()

        // Same tile (-1,-1) as the board's top-left, but coordinates wholly outside it:
        // must show SHARED red, not the frame blue/white that lives in the same tile.
        val outsideShared = RectPx(-10, -10, 2, 2)
        val outShared = RegionRenderer.render(doc, FakeTiles(tiles), outsideShared, null, null)
        assertEquals(2 * 2 * 4, outShared.size, "outside-shared dimensions")
        assertEquals(listOf(255, 0, 0, 255), pixelAt(outShared, outsideShared, -10, -10))
        assertEquals(listOf(255, 0, 0, 255), pixelAt(outShared, outsideShared, -9, -9))
        // Same rect through the other frame still shows shared: the frame never leaks outside.
        val outSharedF1 = RegionRenderer.render(doc, FakeTiles(tiles), outsideShared, "f1", null)
        assertEquals(listOf(255, 0, 0, 255), pixelAt(outSharedF1, outsideShared, -10, -10))

        // No tiles anywhere near: transparent, exact dimensions (not black, not an error).
        val emptyRect = RectPx(500, 500, 2, 2)
        val outEmpty = RegionRenderer.render(doc, FakeTiles(tiles), emptyRect, null, null)
        assertEquals(2 * 2 * 4, outEmpty.size, "empty-outside dimensions")
        assertEquals(listOf(0, 0, 0, 0), pixelAt(outEmpty, emptyRect, 500, 500))
        assertEquals(listOf(0, 0, 0, 0), pixelAt(outEmpty, emptyRect, 501, 501))
    }

    // ── Test 4: selectors carry identity; sources are never mutated ──

    @Test
    fun unrelatedCelPixelsAreNotLeakedAndSourceBytesUnchanged() {
        val boardRect = RectPx(-2, -1, 6, 4)
        val wide = RectPx(-4, -3, 10, 8)
        val doc = regionDoc(boardRect, current = "f2")
        val tiles = regionTiles()
        val snapshots = tiles.mapValues { (_, v) -> v.copyOf() }

        // Frame selector: rendering f1 must never fetch f2cel, and vice versa.
        val recF1 = RecordingTiles(tiles)
        val outF1 = RegionRenderer.render(doc, recF1, wide, "f1", null)
        assertEquals(listOf(0, 255, 0, 255), pixelAt(outF1, wide, 0, 0), "f1 interior is green")
        assertTrue(recF1.asked.none { it.cel == "f2cel" }, "f1 render fetched unrelated f2cel: ${recF1.asked}")
        assertTrue(recF1.asked.any { it.cel == "f1cel" }, "f1 render must fetch f1cel")
        assertTrue(recF1.asked.any { it.cel == "shared" }, "region render must fetch shared")

        val recF2 = RecordingTiles(tiles)
        val outF2 = RegionRenderer.render(doc, recF2, wide, "f2", null)
        assertEquals(listOf(0, 0, 255, 255), pixelAt(outF2, wide, 0, 0), "f2 interior is blue")
        assertTrue(recF2.asked.none { it.cel == "f1cel" }, "f2 render fetched unrelated f1cel: ${recF2.asked}")

        // Layer selector: TileSource is addressed by (layerId, celId). Two static layers
        // may both hold a cel called "shared" (DocOps forbids repeats only within one
        // layer); those are two different stores and must not be deduped by cel id.
        // Premultiplied 50% red under 50% blue. Straight expectation recomputed here:
        // premult out = (0.25, 0, 0.50196) at a=0.75196 -> straight (85,0,170,192).
        val layered = JbDocument(
            id = "d",
            name = "D",
            boards = listOf(Board("c", "C", BoardKind.CANVAS, RectPx(0, 0, 64, 64))),
            layers = listOf(
                Layer(id = "A", name = "A", kind = LayerKind.PAINT, cels = listOf(Cel("shared"))),
                Layer(id = "B", name = "B", kind = LayerKind.PAINT, cels = listOf(Cel("shared"))),
            ),
        )
        assertTrue(DocOps.validate(layered).isEmpty())
        val twoTiles = mapOf(
            TKey("A", "shared", 0, 0) to solid(128, 0, 0, 128),
            TKey("B", "shared", 0, 0) to solid(0, 0, 128, 128),
        )
        val rec = RecordingTiles(twoTiles)
        val one = RectPx(0, 0, 1, 1)
        val mixed = RegionRenderer.render(layered, rec, one, null, null)
        assertEquals(listOf(TKey("A", "shared", 0, 0), TKey("B", "shared", 0, 0)), rec.asked)
        assertEquals(listOf(85, 0, 170, 192), pixelAt(mixed, one, 0, 0), "both layers contribute; cel id alone would give one")
        // A missing tile on one layer must not borrow the other's pixels.
        val half = RegionRenderer.render(
            layered,
            FakeTiles(mapOf(TKey("A", "shared", 0, 0) to solid(255, 0, 0, 255))),
            one,
            null,
            null,
        )
        assertEquals(listOf(255, 0, 0, 255), pixelAt(half, one, 0, 0), "B empty does not borrow A")

        // Untouched source bytes unchanged across all renders in this test.
        for ((k, v) in snapshots) assertContentEquals(v, tiles.getValue(k), "region source $k mutated")
    }

    // ── Test 5: source crop semantics are not layer-preview UI wiring ──

    /**
     * G7a: an active board scopes layer-selector thumbnails to its rectangle (and an
     * animation board to its current frame); passive restores the page. [BoardLayerPreview]
     * computes that SCOPE; [RegionRenderer] renders it. This test proves the core can
     * render the scoped bounds. It does NOT prove the Android thumbnail selector wires
     * those bounds to the UI: that wiring lives outside core and is not exercised here.
     */
    @Test
    fun previewScopeCropSemanticsAreNotUiWiring() {
        val page = RectPx(0, 0, 100, 100)
        val image = Board("img", "Image", BoardKind.CANVAS, RectPx(10, 10, 20, 10))
        val anim = Board(
            "anim",
            "Anim",
            BoardKind.ANIMATION,
            RectPx(-5, -3, 10, 8),
            frames = listOf(Frame("f1"), Frame("f2")),
            currentFrameId = "f1",
        )

        val active = BoardLayerPreview.scope(page, image, true)
        assertEquals(image.rect, active.bounds, "active image scopes to board bounds")
        assertEquals(image.id, active.boardId)
        assertEquals(null, active.frameId, "passive kind carries no frame")

        val passive = BoardLayerPreview.scope(page, image, false)
        assertEquals(page, passive.bounds, "passive restores page bounds")
        assertEquals(null, passive.boardId)
        assertEquals(BoardLayerPreview(null, page, null), passive)

        val first = BoardLayerPreview.scope(page, anim, true, "f1")
        val second = BoardLayerPreview.scope(page, anim, true, "f2")
        assertEquals(anim.rect, first.bounds)
        assertEquals(anim.rect, second.bounds, "frame changes identity, not bounds")
        assertEquals("f1", first.frameId)
        assertEquals("f2", second.frameId)
        assertNotEquals(first.key("L", 1), second.key("L", 1), "preview key follows the frame")
        assertFailsWith<IllegalArgumentException> { BoardLayerPreview.scope(page, anim, true) }
        assertFailsWith<IllegalArgumentException> { BoardLayerPreview.scope(page, anim, true, "missing") }

        // Scope never mutates the board: preview framing, not a crop of stored pixels.
        assertEquals(RectPx(10, 10, 20, 10), image.rect)
        assertEquals(RectPx(-5, -3, 10, 8), anim.rect)

        // The scoped bounds ARE renderable as a source crop (white tile (0,0) fixture).
        val doc = JbDocument(
            id = "d",
            name = "D",
            boards = listOf(Board("c", "C", BoardKind.CANVAS, page)),
            layers = listOf(Layer(id = "L", name = "L", kind = LayerKind.PAINT, cels = listOf(Cel("L-cel")))),
        )
        val tiles = FakeTiles(mapOf(TKey("L", "L-cel", 0, 0) to solid(255, 255, 255, 255)))
        val scoped = RegionRenderer.render(doc, tiles, active.bounds, null, null)
        assertEquals(20 * 10 * 4, scoped.size, "scoped preview dimensions are the board's")
        assertEquals(listOf(255, 255, 255, 255), pixelAt(scoped, active.bounds, 10, 10))
        assertEquals(listOf(255, 255, 255, 255), pixelAt(scoped, active.bounds, 29, 19))
        // Switching boards uses the new bounds without changing artwork: covered by Test 1's
        // intersection equality; the Android selector that displays these bytes is out of scope.
    }

    // ── Test 6: overlapping ANIMATION boards are refused (documented API limitation) ──

    /**
     * Precise limitation, not a missing assertion: region core cannot express two
     * overlapping animation boards. [DocOps.validate] refuses the document,
     * [RegionPaintPlan] refuses the geometry, and [RegionDocumentOps.create] refuses to
     * add the second board. Overlapping PASSIVE boards remain legal (Test 1). No future
     * parameter is invented here.
     */
    @Test
    fun overlappingAnimationBoardsAreRefusedDocumentedLimitation() {
        val rectA = RectPx(0, 0, 10, 10) // x in [0,10), y in [0,10)
        val rectB = RectPx(5, 5, 10, 10) // x in [5,15), y in [5,15): 5x5 overlap
        assertFailsWith<IllegalArgumentException>("overlapping regions share a tile") {
            RegionPaintPlan(
                "shared",
                listOf(
                    RegionFrame("a", rectA, "f1", "a1"),
                    RegionFrame("b", rectB, "g1", "b1"),
                ),
            )
        }

        val overlapping = JbDocument(
            id = "d",
            name = "D",
            boards = listOf(
                Board("a", "A", BoardKind.ANIMATION, rectA, frames = listOf(Frame("f1")), currentFrameId = "f1"),
                Board("b", "B", BoardKind.ANIMATION, rectB, frames = listOf(Frame("g1")), currentFrameId = "g1"),
            ),
            layers = listOf(
                Layer(
                    id = "L",
                    name = "L",
                    kind = LayerKind.PAINT,
                    cels = listOf(Cel("shared"), Cel("a1"), Cel("b1")),
                    sharedCelId = "shared",
                    regions = listOf(
                        RegionFrames("a", mapOf("f1" to "a1")),
                        RegionFrames("b", mapOf("g1" to "b1")),
                    ),
                ),
            ),
        )
        val problems = DocOps.validate(overlapping)
        assertTrue(problems.any { it.contains("overlap") }, "validator must name the overlap: $problems")

        // Creating the second board over the first is refused before mutation.
        var serial = 0
        fun ids(): String {
            serial++
            return "id-$serial"
        }
        val base = JbDocument(
            id = "drawing",
            name = "base",
            boards = listOf(Board("image", "Image", BoardKind.CANVAS, RectPx(0, 0, 1024, 1024))),
            layers = listOf(Layer(id = "layer", name = "Paint", kind = LayerKind.PAINT, cels = listOf(Cel("base")))),
        )
        val first = RegionDocumentOps.create(base, "A", rectA, ::ids)
        assertTrue(DocOps.validate(first.doc).isEmpty())
        assertFailsWith<DocException>("second overlapping animation board") {
            RegionDocumentOps.create(first.doc, "B", rectB, ::ids)
        }
        // The refused second create left the first document with exactly one animation board.
        assertEquals(2, first.doc.boards.size, "base canvas plus one animation board")
    }

    // ── fixtures ──

    private data class TKey(val layer: String, val cel: String, val tx: Int, val ty: Int)

    private class FakeTiles(private val map: Map<TKey, ByteArray>) : TileSource {
        override fun tile(layerId: String, celId: String, tx: Int, ty: Int): ByteArray? =
            map[TKey(layerId, celId, tx, ty)]
    }

    private class RecordingTiles(private val map: Map<TKey, ByteArray>) : TileSource {
        val asked = ArrayList<TKey>()
        override fun tile(layerId: String, celId: String, tx: Int, ty: Int): ByteArray? {
            val key = TKey(layerId, celId, tx, ty)
            asked += key
            return map[key]
        }
    }

    /** One region animation board with shared=red, f1=green/black-marker, f2=blue/white-marker. */
    private fun regionDoc(boardRect: RectPx, current: String): JbDocument = JbDocument(
        id = "d",
        name = "D",
        boards = listOf(
            Board(
                "anim",
                "Anim",
                BoardKind.ANIMATION,
                boardRect,
                frames = listOf(Frame("f1"), Frame("f2")),
                currentFrameId = current,
            ),
        ),
        layers = listOf(
            Layer(
                id = "paint",
                name = "paint",
                kind = LayerKind.PAINT,
                cels = listOf(Cel("shared"), Cel("f1cel"), Cel("f2cel")),
                sharedCelId = "shared",
                regions = listOf(RegionFrames("anim", mapOf("f1" to "f1cel", "f2" to "f2cel"))),
            ),
        ),
    )

    /**
     * Tiles for [regionDoc]: four tiles per cel covering x in [-256,256), y in [-256,256).
     * Shared is opaque red with a 1px yellow marker at (-3,-2) (outside the board);
     * f1 is green with a black marker at (-2,-1) (board top-left, inside);
     * f2 is blue with a white marker there. All values are opaque, so premultiplied
     * bytes equal straight bytes and expectations need no blend arithmetic.
     */
    private fun regionTiles(): HashMap<TKey, ByteArray> {
        val tiles = HashMap<TKey, ByteArray>()
        for (tx in listOf(-1, 0)) {
            for (ty in listOf(-1, 0)) {
                fillTile(tiles, "paint", "shared", tx, ty, 255, 0, 0, 255)
                fillTile(tiles, "paint", "f1cel", tx, ty, 0, 255, 0, 255)
                fillTile(tiles, "paint", "f2cel", tx, ty, 0, 0, 255, 255)
            }
        }
        paintPixel(tiles, "paint", "shared", -3, -2, 255, 255, 0, 255)
        paintPixel(tiles, "paint", "f1cel", -2, -1, 0, 0, 0, 255)
        paintPixel(tiles, "paint", "f2cel", -2, -1, 255, 255, 255, 255)
        return tiles
    }

    private fun solid(r: Int, g: Int, b: Int, a: Int): ByteArray {
        val t = ByteArray(RegionRenderer.TILE_BYTES)
        var i = 0
        while (i < t.size) {
            t[i] = r.toByte()
            t[i + 1] = g.toByte()
            t[i + 2] = b.toByte()
            t[i + 3] = a.toByte()
            i += 4
        }
        return t
    }

    private fun fillTile(
        tiles: MutableMap<TKey, ByteArray>,
        layer: String,
        cel: String,
        tx: Int,
        ty: Int,
        r: Int,
        g: Int,
        b: Int,
        a: Int,
    ) {
        val tile = tiles.getOrPut(TKey(layer, cel, tx, ty)) { ByteArray(RegionRenderer.TILE_BYTES) }
        var i = 0
        while (i < tile.size) {
            tile[i] = r.toByte()
            tile[i + 1] = g.toByte()
            tile[i + 2] = b.toByte()
            tile[i + 3] = a.toByte()
            i += 4
        }
    }

    /**
     * One premultiplied pixel. Offset uses `x - tx*256` (0..255 via [DocOps.tileOf]'s
     * floorDiv), which is common-safe: no JVM-only floorMod.
     */
    private fun paintPixel(
        tiles: MutableMap<TKey, ByteArray>,
        layer: String,
        cel: String,
        x: Int,
        y: Int,
        r: Int,
        g: Int,
        b: Int,
        a: Int,
    ) {
        val (tx, ty) = DocOps.tileOf(x, y)
        val tile = tiles.getOrPut(TKey(layer, cel, tx, ty)) { ByteArray(RegionRenderer.TILE_BYTES) }
        val lx = x - tx * 256
        val ly = y - ty * 256
        val o = (ly * 256 + lx) * 4
        tile[o] = r.toByte()
        tile[o + 1] = g.toByte()
        tile[o + 2] = b.toByte()
        tile[o + 3] = a.toByte()
    }

    private fun pixelAt(out: ByteArray, rect: RectPx, x: Int, y: Int): List<Int> {
        val i = ((y - rect.y) * rect.w + (x - rect.x)) * 4
        return listOf(
            out[i].toInt() and 0xFF,
            out[i + 1].toInt() and 0xFF,
            out[i + 2].toInt() and 0xFF,
            out[i + 3].toInt() and 0xFF,
        )
    }
}
