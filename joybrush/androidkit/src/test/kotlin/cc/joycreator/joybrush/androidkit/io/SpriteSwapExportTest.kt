package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.androidkit.gl.RegionTransferTiles
import cc.joycreator.joybrush.core.chrome.BoardExportLayout as Export
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.sprite.SpriteGridMath
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * Sprite swap moves pixels end to end: the real [SpriteCellOps] transfer plan, applied through
 * the production tile subdivision ([RegionTransferTiles.slices]), then the real [BoardExport]
 * sprite-sheet encode, decoded independently with [ImageIO].
 *
 * The regression this guards: a swap that only rewrites metadata (cell indices, sidecar order)
 * while leaving tile bytes in place. Such a change keeps [RegionChange.doc] identical AND would
 * still produce a sheet — but with the old colours in the old cells. Every colour assertion
 * below is derived from the fixture description (which cell was painted what, swapped 0<->1),
 * never from the transfer list, so a metadata-only swap fails every swapped-pixel assertion
 * while a correct one passes.
 *
 * Production helpers used (no homemade geometry): [SpriteCellOps.swap] for the plan,
 * [SpriteGridMath.cellRect] for the expected cell rectangles, [RegionTransferTiles.slices] for
 * splitting each transfer at BOTH tile grids (negative-safe floorMod/floorDiv, off-boundary
 * origins), [DocOps.key]/[DocOps.tileOf]/[Tiles.SIZE] for tile addressing, [BoardExport.stage]
 * for the sheet. Test-side by necessity (the GL executor's texture blit has no JVM twin): the
 * fixture painter, the 4-byte translated copy inside each production slice, and the pixel
 * reader. The copy follows the [RegionTransfer] contract exactly — every source byte is read
 * from the immutable before-state, transparent sources overwrite (zero) rather than merge —
 * which is the same modelling [RegionTransferTilesTest] uses for its reciprocal-copy oracle.
 *
 * False positives considered:
 * - Solid fills alone cannot catch mirroring/offsets: each swapped cell carries a 1px corner
 *   marker (top-left), so a mirrored or shifted blit puts the marker at the wrong sheet pixel.
 * - The marker is 1px TALL at the top row, so a vertical flip also fails.
 * - The top layer is opaque over cell 0 but transparent over cell 1, so the post-swap sheet
 *   shows the TOP paint in cell 1 and the BOTTOM paint in cell 0: a swap that moves only one
 *   layer fails. The tile-level assertions additionally check the hidden layer directly.
 * - A merge-only executor (leaves old destination where the source is transparent) fails the
 *   top-cell-0-is-zero assertions and the sheet cell-0 assertions (stale yellow would show).
 * - Swapping the wrong pair fails the cell-2-unchanged assertions; over-wide writes fail the
 *   outside-sentinel assertion.
 */
class SpriteSwapExportTest {
    private val boardRect = RectPx(-260, -1, 780, 3)
    private val grid = SpriteGrid(3, 1, 260, 3)

    private fun document(): JbDocument = JbDocument(
        id = "drawing",
        name = "SwapExport",
        boards = listOf(Board("sprite", "Sheet", BoardKind.SPRITE, boardRect, grid = grid, locked = true)),
        layers = listOf(
            Layer("paint", "Paint", LayerKind.PAINT, cels = listOf(Cel("shared"))),
            Layer("top", "Top", LayerKind.PAINT, cels = listOf(Cel("top-shared"))),
        ),
    )

    private fun paintPixel(
        tiles: MutableMap<Triple<String, String, String>, ByteArray>,
        layer: String,
        cel: String,
        x: Int,
        y: Int,
        argb: Int,
    ) {
        val (tx, ty) = DocOps.tileOf(x, y)
        val tile = tiles.getOrPut(Triple(layer, cel, DocOps.key(tx, ty))) { ByteArray(TILE_BYTES) }
        val o = (Math.floorMod(y, Tiles.SIZE) * Tiles.SIZE + Math.floorMod(x, Tiles.SIZE)) * 4
        tile[o] = ((argb shr 16) and 0xFF).toByte()
        tile[o + 1] = ((argb shr 8) and 0xFF).toByte()
        tile[o + 2] = (argb and 0xFF).toByte()
        tile[o + 3] = ((argb ushr 24) and 0xFF).toByte()
    }

    private fun fill(
        tiles: MutableMap<Triple<String, String, String>, ByteArray>,
        layer: String,
        cel: String,
        rect: RectPx,
        argb: Int,
    ) {
        for (y in rect.y until rect.y + rect.h) for (x in rect.x until rect.x + rect.w) {
            paintPixel(tiles, layer, cel, x, y, argb)
        }
    }

    private fun pixelAt(
        tiles: Map<Triple<String, String, String>, ByteArray>,
        layer: String,
        cel: String,
        x: Int,
        y: Int,
    ): Int {
        val (tx, ty) = DocOps.tileOf(x, y)
        val tile = tiles[Triple(layer, cel, DocOps.key(tx, ty))] ?: return 0
        val o = (Math.floorMod(y, Tiles.SIZE) * Tiles.SIZE + Math.floorMod(x, Tiles.SIZE)) * 4
        val r = tile[o].toInt() and 0xFF
        val g = tile[o + 1].toInt() and 0xFF
        val b = tile[o + 2].toInt() and 0xFF
        val a = tile[o + 3].toInt() and 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Applies [change]'s transfers to tile bytes. Subdivision is the production helper; the
     * inner copy is 4 bytes per pixel at the slice offsets, reading always from [before].
     */
    private fun applyViaProductionSlices(
        before: Map<Triple<String, String, String>, ByteArray>,
        change: RegionChange,
    ): MutableMap<Triple<String, String, String>, ByteArray> {
        val after = before.mapValuesTo(HashMap()) { (_, bytes) -> bytes.copyOf() }
        for (transfer in change.transfers) {
            for (slice in RegionTransferTiles.slices(transfer.sourceRect, transfer.destinationRect)) {
                val fromKey = Triple(transfer.layerId, transfer.fromCelId,
                    DocOps.key(Tiles.tx(slice.sourceKey), Tiles.ty(slice.sourceKey)))
                val toKey = Triple(transfer.layerId, transfer.toCelId,
                    DocOps.key(Tiles.tx(slice.destinationKey), Tiles.ty(slice.destinationKey)))
                val from = before[fromKey]
                val to = after.getOrPut(toKey) { ByteArray(TILE_BYTES) }
                val src = slice.source
                val dst = slice.destination
                for (row in 0 until src.h) {
                    val sPos = ((src.y + row) * Tiles.SIZE + src.x) * 4
                    val dPos = ((dst.y + row) * Tiles.SIZE + dst.x) * 4
                    val bytes = src.w * 4
                    if (from == null) for (i in 0 until bytes) to[dPos + i] = 0
                    else from.copyInto(to, dPos, sPos, sPos + bytes)
                }
            }
        }
        return after
    }

    private fun tileKeysFor(rect: RectPx): List<String> {
        val (tx0, ty0) = DocOps.tileOf(rect.x, rect.y)
        val (tx1, ty1) = DocOps.tileOf(rect.x + rect.w - 1, rect.y + rect.h - 1)
        return buildList {
            for (ty in ty0..ty1) for (tx in tx0..tx1) add(DocOps.key(tx, ty))
        }
    }

    @Test fun swapMovesBothLayersThroughRealSheetExport() {
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        val blue = 0xFF0000FF.toInt()
        val yellow = 0xFFFFFF00.toInt()
        val magenta = 0xFFFF00FF.toInt()
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val dark = 0xFF7F0000.toInt()
        val sentinel = 0xFF123456.toInt()

        val doc = document()
        assertTrue(DocOps.validate(doc).isEmpty())
        // Negative, non-tile-multiple origin; 260px cells straddle the 256px tile seams.
        val a = SpriteGridMath.cellRect(doc.boards.single(), 0)
        val b = SpriteGridMath.cellRect(doc.boards.single(), 1)
        val c = SpriteGridMath.cellRect(doc.boards.single(), 2)
        assertEquals(RectPx(-260, -1, 260, 3), a)
        assertEquals(RectPx(0, -1, 260, 3), b)
        assertEquals(RectPx(260, -1, 260, 3), c)

        // Bottom layer: every cell opaque and distinct; 1px top-left corner markers.
        val before = HashMap<Triple<String, String, String>, ByteArray>()
        fill(before, "paint", "shared", a, red)
        paintPixel(before, "paint", "shared", a.x, a.y, white)
        fill(before, "paint", "shared", b, green)
        paintPixel(before, "paint", "shared", b.x, b.y, black)
        fill(before, "paint", "shared", c, blue)
        paintPixel(before, "paint", "shared", -261, -1, sentinel)
        // Top layer: opaque over cell 0 (with its own corner marker), transparent over cell 1,
        // opaque over cell 2. Cell 1 therefore shows the bottom layer through.
        fill(before, "top", "top-shared", a, yellow)
        paintPixel(before, "top", "top-shared", a.x, a.y, dark)
        fill(before, "top", "top-shared", c, magenta)

        val change = SpriteCellOps.swap(doc, "sprite", 0, 1)
        // Static layers: one reciprocal pair per layer; metadata itself never changes.
        assertSame(doc, change.doc)
        assertTrue(change.copies.isEmpty() && change.drops.isEmpty())
        assertTrue(change.clears.isEmpty() && change.maskClears.isEmpty() && change.maskTransfers.isEmpty())
        assertEquals(4, change.transfers.size)
        assertEquals(setOf("paint", "top"), change.transfers.map { it.layerId }.toSet())
        assertEquals(
            setOf(a to b, b to a),
            change.transfers.map { it.sourceRect to it.destinationRect }.toSet(),
        )

        val after = applyViaProductionSlices(before, change)

        // Tile level, in board coordinates: bottom layer contents exchanged 0<->1.
        assertEquals(black, pixelAt(after, "paint", "shared", -260, -1))
        assertEquals(green, pixelAt(after, "paint", "shared", -260, 0))
        assertEquals(green, pixelAt(after, "paint", "shared", -259, -1))
        assertEquals(green, pixelAt(after, "paint", "shared", -1, 1))
        assertEquals(white, pixelAt(after, "paint", "shared", 0, -1))
        assertEquals(red, pixelAt(after, "paint", "shared", 0, 0))
        assertEquals(red, pixelAt(after, "paint", "shared", 1, -1))
        assertEquals(red, pixelAt(after, "paint", "shared", 259, 1))
        // Top layer exchanged too — including transparent replacement, not a merge.
        assertEquals(dark, pixelAt(after, "top", "top-shared", 0, -1))
        assertEquals(yellow, pixelAt(after, "top", "top-shared", 0, 0))
        assertEquals(yellow, pixelAt(after, "top", "top-shared", 1, -1))
        assertEquals(yellow, pixelAt(after, "top", "top-shared", 259, 1))
        assertEquals(0, pixelAt(after, "top", "top-shared", -260, -1))
        assertEquals(0, pixelAt(after, "top", "top-shared", -259, -1))
        assertEquals(0, pixelAt(after, "top", "top-shared", -260, 1))
        // Unrelated third cell and outside pixel untouched, every pixel of them.
        for (y in c.y until c.y + c.h) for (x in c.x until c.x + c.w) {
            assertEquals(blue, pixelAt(after, "paint", "shared", x, y))
            assertEquals(magenta, pixelAt(after, "top", "top-shared", x, y))
        }
        assertEquals(sentinel, pixelAt(after, "paint", "shared", -261, -1))

        val keys = tileKeysFor(boardRect)
        val manifest = doc.copy(layers = doc.layers.map { layer ->
            layer.copy(cels = layer.cels.map { it.copy(tiles = keys) })
        })
        val contents = JbContents(manifest, after, emptyMap())
        val request = BoardExportRequest.capture(
            manifest,
            Export.Choice("sprite", Export.Scope.BOARD, Export.Format.SPRITE_SHEET, "sprite", "sprite", "sprite"),
            false,
        )
        val cache = Files.createTempDirectory("jb-sprite-swap-export-test").toFile()
        try {
            BoardExport.stage(contents, request, cache).use { staged ->
                assertEquals(2, staged.files.size)
                val image = ImageIO.read(staged.files.first().file)
                assertEquals(3 * 260, image.width)
                assertEquals(3, image.height)
                // Sheet x = board x + 260. Cell 0 shows the bottom paint (top went transparent):
                // old cell-1 green with its black corner now at the top-left.
                assertEquals(black, image.getRGB(0, 0))
                assertEquals(green, image.getRGB(0, 1))
                assertEquals(green, image.getRGB(0, 2))
                assertEquals(green, image.getRGB(1, 0))
                assertEquals(green, image.getRGB(259, 1))
                // Cell 1 shows the top paint (opaque yellow over the bottom red): old cell-0
                // yellow with its dark corner now at cell 1's top-left.
                assertEquals(dark, image.getRGB(260, 0))
                assertEquals(yellow, image.getRGB(260, 1))
                assertEquals(yellow, image.getRGB(260, 2))
                assertEquals(yellow, image.getRGB(261, 0))
                assertEquals(yellow, image.getRGB(519, 2))
                // Cell 2 never participated: still top magenta over bottom blue.
                assertEquals(magenta, image.getRGB(520, 0))
                assertEquals(magenta, image.getRGB(520, 2))
                assertEquals(magenta, image.getRGB(650, 1))
                assertEquals(magenta, image.getRGB(779, 2))
                assertEquals(doc.id, contents.doc.id)
            }
        } finally {
            cache.deleteRecursively()
        }
    }
}
