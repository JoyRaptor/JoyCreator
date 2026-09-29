package cc.joycreator.joybrush.core.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpritePackerTest {

    private companion object {
        const val CELL = 16
        val TRANSPARENT = intArrayOf(0, 0, 0, 0)
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** One cell, one flat colour. Alpha defaults to opaque so "transparent" always means empty. */
    private fun solid(w: Int, h: Int, r: Int, g: Int = 20, b: Int = 30, a: Int = 255): ByteArray {
        val out = ByteArray(w * h * 4)
        var i = 0
        while (i < out.size) {
            out[i] = r.toByte()
            out[i + 1] = g.toByte()
            out[i + 2] = b.toByte()
            out[i + 3] = a.toByte()
            i += 4
        }
        return out
    }

    /** Cell `i` is red = i * 10, so one wrong byte shows up as a wrong colour. */
    private fun board(n: Int, w: Int = CELL, h: Int = CELL): List<ByteArray> =
        (0 until n).map { solid(w, h, it * 10) }

    private fun pack(
        cells: List<ByteArray>,
        cols: Int,
        clips: List<Clip> = emptyList(),
        names: Map<Int, String> = emptyMap(),
        fps: Float = 8f,
        cellW: Int = CELL,
        cellH: Int = CELL,
    ) = SpritePacker.pack(
        cells = cells,
        cellW = cellW,
        cellH = cellH,
        cols = cols,
        id = "walk-01",
        name = "Walk cycle",
        sheetFileName = "Walk cycle.png",
        fps = fps,
        clips = clips,
        cellNames = names,
    )

    private fun rgbaAt(bytes: ByteArray, x: Int, y: Int, sheetW: Int): IntArray {
        val at = (y * sheetW + x) * 4
        return intArrayOf(
            bytes[at].toInt() and 0xFF,
            bytes[at + 1].toInt() and 0xFF,
            bytes[at + 2].toInt() and 0xFF,
            bytes[at + 3].toInt() and 0xFF,
        )
    }

    private fun parse(sidecar: String) = Json.parseToJsonElement(sidecar).jsonObject

    /** The one preset of a one-clip pack, at the level the app's `fromJson` reads it from. */
    private fun presetOf(clip: Clip) =
        parse(pack(board(5), cols = 3, clips = listOf(clip)).sidecarJson)["presets"]!!
            .jsonArray[0].jsonObject

    private fun framesOf(preset: JsonObject) =
        preset["frames"]!!.jsonArray.map { it.jsonPrimitive.int }

    private fun weightsOf(preset: JsonObject) =
        preset["weights"]!!.jsonArray.map { it.jsonPrimitive.int }

    // ── geometry ───────────────────────────────────────────────────────────

    @Test
    fun fiveCellsInThreeColumnsIsA48x32Sheet() {
        val sheet = pack(board(5), cols = 3)
        // rows = ceil(5 / 3) = 2
        assertEquals(48, sheet.width)
        assertEquals(32, sheet.height)
        assertEquals(48 * 32 * 4, sheet.rgba.size)
    }

    @Test
    fun cellsLandInReadingOrderAndTheSixthSlotIsTransparent() {
        val sheet = pack(board(5), cols = 3)
        // Cell i is at column i % 3, row i / 3: 0,1,2 across the top, 3,4 below, slot 5 empty.
        assertContentEquals(intArrayOf(0, 20, 30, 255), rgbaAt(sheet.rgba, 0, 0, 48))
        assertContentEquals(intArrayOf(10, 20, 30, 255), rgbaAt(sheet.rgba, 16, 0, 48))
        assertContentEquals(intArrayOf(20, 20, 30, 255), rgbaAt(sheet.rgba, 32, 0, 48))
        assertContentEquals(intArrayOf(30, 20, 30, 255), rgbaAt(sheet.rgba, 0, 16, 48))
        assertContentEquals(intArrayOf(40, 20, 30, 255), rgbaAt(sheet.rgba, 16, 16, 48))
        // Pixel (32,16) is byte 3200: the first byte of the empty slot.
        assertContentEquals(TRANSPARENT, rgbaAt(sheet.rgba, 32, 16, 48))
    }

    @Test
    fun everyPixelOfEveryCellIsCopiedExactly() {
        val cells = board(5)
        val sheet = pack(cells, cols = 3)
        for (i in cells.indices) {
            for (y in 0 until CELL) {
                for (x in 0 until CELL) {
                    val want = rgbaAt(cells[i], x, y, CELL)
                    val got = rgbaAt(sheet.rgba, (i % 3) * CELL + x, (i / 3) * CELL + y, 48)
                    assertContentEquals(want, got, "cell $i at $x,$y")
                }
            }
        }
    }

    @Test
    fun oneCellIsOneCell() {
        val sheet = pack(board(1), cols = 1)
        assertEquals(CELL, sheet.width)
        assertEquals(CELL, sheet.height)
        val root = parse(sheet.sidecarJson)
        assertEquals(1, root["cols"]!!.jsonPrimitive.int)
        assertEquals(1, root["rows"]!!.jsonPrimitive.int)
    }

    @Test
    fun aColumnOfFiveIsFiveRows() {
        val sheet = pack(board(5), cols = 1)
        assertEquals(CELL, sheet.width)
        assertEquals(5 * CELL, sheet.height)
    }

    @Test
    fun moreColumnsThanCellsLeavesTransparentSlotsTheGridStillAccountsFor() {
        val sheet = pack(board(5), cols = 8)
        val root = parse(sheet.sidecarJson)
        assertEquals(128, sheet.width)
        assertEquals(CELL, sheet.height)
        // The grid is 8 by 1 = 8 slots for 5 drawings. That is not a claim the sheet cannot back:
        // the image really is 8 cells wide, the last 3 of them empty.
        assertEquals(8, root["cols"]!!.jsonPrimitive.int)
        assertEquals(1, root["rows"]!!.jsonPrimitive.int)
        assertContentEquals(intArrayOf(40, 20, 30, 255), rgbaAt(sheet.rgba, 80 - CELL, 0, 128))
        assertContentEquals(TRANSPARENT, rgbaAt(sheet.rgba, 80, 0, 128))
    }

    // ── sidecar shape ──────────────────────────────────────────────────────

    @Test
    fun sidecarHasExactlyTheAppsKeysInTheAppsOrder() {
        val sheet = pack(
            cells = board(5),
            cols = 3,
            clips = listOf(Clip("walk", listOf(0, 1, 2))),
            names = mapOf(0 to "idle_01"),
        )
        val root = parse(sheet.sidecarJson)
        assertEquals(
            listOf(
                "spriteSchemaVersion", "id", "name", "sheetUri",
                "cols", "rows", "fps", "presets", "cellNames",
            ),
            root.keys.toList(),
        )
        assertEquals(1, root["spriteSchemaVersion"]!!.jsonPrimitive.int)
        assertEquals("walk-01", root["id"]!!.jsonPrimitive.content)
        assertEquals("Walk cycle", root["name"]!!.jsonPrimitive.content)
        assertEquals("Walk cycle.png", root["sheetUri"]!!.jsonPrimitive.content)
        assertEquals(3, root["cols"]!!.jsonPrimitive.int)
        assertEquals(2, root["rows"]!!.jsonPrimitive.int)
        assertEquals(8f, root["fps"]!!.jsonPrimitive.float)
    }

    @Test
    fun nothingTheAppDoesNotWriteIsWritten() {
        val root = parse(pack(board(5), cols = 3).sidecarJson)
        for (absent in listOf(
            "marginX", "marginY", "spacingX", "spacingY", "order", "bgKeyColor", "keyTolerance",
            "pivotX", "pivotY", "cells", "cellXf", "visemeMap", "bakedFrom", "cellOrder",
            "kind", "frameUris", "resizeMode",
        )) {
            assertFalse(root.containsKey(absent), "$absent must not be written")
        }
    }

    @Test
    fun sidecarIsPrettyWithTwoSpaceIndent() {
        val text = pack(board(5), cols = 3).sidecarJson
        val lines = text.lines()
        // The brace alone on line one is only true of pretty output: a writer that ignored
        // prettyPrint would put the whole document on line one and fail here.
        assertEquals("{", lines[0])
        // The column of the first quote IS the indent width, and it is read off the RAW line. A
        // helper that trims or normalises whitespace hides the one thing this test exists to see,
        // which is exactly how a real indent of zero once passed for an indent of two.
        assertEquals(2, lines[1].indexOf('"'), "line one starts: [${lines[1].take(8)}]")
        // Whole line, minus the separator, with nothing stripped: this pins every other character.
        assertEquals("  \"spriteSchemaVersion\": 1", lines[1].removeSuffix(","))
        assertFalse(text.contains('\t'), "a sidecar the app writes pretty has no tabs")
    }

    @Test
    fun presetsCarryTheirFramesAndOmitAnInheritedFps() {
        val sheet = pack(
            cells = board(5),
            cols = 3,
            clips = listOf(Clip("walk", listOf(0, 1, 2, 1), "pingpong")),
        )
        val preset = parse(sheet.sidecarJson)["presets"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("id", "name", "type", "frames"), preset.keys.toList())
        assertEquals("walk-01-clip-0", preset["id"]!!.jsonPrimitive.content)
        assertEquals("walk", preset["name"]!!.jsonPrimitive.content)
        assertEquals("pingpong", preset["type"]!!.jsonPrimitive.content)
        assertEquals(listOf(0, 1, 2, 1), preset["frames"]!!.jsonArray.map { it.jsonPrimitive.int })
        // fps 0 means "inherit the sheet's", and the app leaves the key out then.
        assertFalse(preset.containsKey("fps"))
    }

    @Test
    fun aClipWithItsOwnFpsWritesIt() {
        val sheet = pack(
            cells = board(5),
            cols = 3,
            clips = listOf(Clip("blink", listOf(3), "once", 12f)),
        )
        val preset = parse(sheet.sidecarJson)["presets"]!!.jsonArray[0].jsonObject
        assertEquals(12f, preset["fps"]!!.jsonPrimitive.float)
        assertEquals(listOf(3), preset["frames"]!!.jsonArray.map { it.jsonPrimitive.int })
    }

    @Test
    fun cellNamesAndPresetsAreWrittenOnlyWhenGiven() {
        val bare = parse(pack(board(5), cols = 3).sidecarJson)
        assertFalse(bare.containsKey("cellNames"))
        assertFalse(bare.containsKey("presets"))

        val named = parse(pack(board(5), cols = 3, names = mapOf(0 to "idle_01")).sidecarJson)
        val entries = named["cellNames"]!!.jsonObject
            .mapValues { it.value.jsonPrimitive.content }
        assertEquals(mapOf("0" to "idle_01"), entries)
    }

    @Test
    fun cellNamesComeOutInCellOrderWhateverOrderTheyWentIn() {
        val shuffled = HashMap<Int, String>()
        shuffled[4] = "turn_03"
        shuffled[0] = "idle_01"
        shuffled[2] = "idle_03"
        val sheet = pack(board(5), cols = 3, names = shuffled)
        assertEquals(
            listOf("0", "2", "4"),
            parse(sheet.sidecarJson)["cellNames"]!!.jsonObject.keys.toList(),
        )
    }

    // ── held frames: the sidecar's `weights`, the app's key ─────────────────

    @Test
    fun aHeldFrameIsWrittenAsWeightsBesideItsFrames() {
        val preset = presetOf(Clip("walk", listOf(0, 1, 2), weights = listOf(3, 1, 1)))
        // The app's own key ORDER is part of the contract: frames, then weights, then nothing.
        assertEquals(listOf("id", "name", "type", "frames", "weights"), preset.keys.toList())
        assertEquals(listOf(0, 1, 2), framesOf(preset))
        // This is the row. Cell 0 is held for three ticks and the file says so; before it, the
        // sidecar described the sheet in every other particular and said nothing about this one.
        assertEquals(listOf(3, 1, 1), weightsOf(preset))
    }

    @Test
    fun weightsIsWrittenAfterFramesAndNotBefore() {
        val text = pack(
            cells = board(5),
            cols = 3,
            clips = listOf(Clip("walk", listOf(0, 1, 2), weights = listOf(3, 1, 1))),
        ).sidecarJson
        // The order is checked in the RAW text, not only in a parsed key list: a key list answers
        // "which keys", and this is about "in what order the file says them".
        val framesAt = text.indexOf("\"frames\"")
        val weightsAt = text.indexOf("\"weights\"")
        assertTrue(framesAt >= 0 && weightsAt > framesAt, "frames at $framesAt, weights at $weightsAt")
        // And inside the preset, not hoisted to the root: the app reads it off `p`, one array per
        // preset, so a root key is a key it would drop on the floor.
        val root = parse(text)
        assertFalse(root.containsKey("weights"))
        assertTrue(root["presets"]!!.jsonArray[0].jsonObject.containsKey("weights"))
    }

    @Test
    fun aClipWithNothingHeldWritesNoWeightsKey() {
        // Three ways of saying "no hold", and the app writes nothing for any of them.
        assertFalse(presetOf(Clip("walk", listOf(0, 1, 2))).containsKey("weights"))
        assertFalse(presetOf(Clip("walk", listOf(0, 1, 2), weights = emptyList())).containsKey("weights"))
        assertFalse(presetOf(Clip("walk", listOf(0, 1, 2), weights = listOf(1, 1, 1))).containsKey("weights"))

        // The app's promise is stronger than "no key": an all-1s array "carries no information,
        // and omitting it keeps a pre-weights preset byte-identical" (SpriteSheet.java:646-647). So
        // this compares the STRINGS. A key-list comparison would still pass if the packer wrote
        // the key somewhere else, or with different spacing than the default case.
        assertEquals(
            pack(board(5), cols = 3, clips = listOf(Clip("walk", listOf(0, 1, 2)))).sidecarJson,
            pack(
                board(5),
                cols = 3,
                clips = listOf(Clip("walk", listOf(0, 1, 2), weights = listOf(1, 1, 1))),
            ).sidecarJson,
        )
    }

    @Test
    fun aHoldOnTheFirstCellIsTheOrdinaryCaseAndItSurvives() {
        val written = Json.parseToJsonElement(
            pack(
                cells = board(5),
                cols = 3,
                clips = listOf(Clip("walk", listOf(0, 1, 2), weights = listOf(3, 1, 1))),
            ).sidecarJson,
        ).jsonObject["presets"]!!.jsonArray[0].jsonObject

        val frames = framesOf(written)
        val weights = weightsOf(written)
        assertEquals(3, frames.size)
        assertEquals(frames.indices.toList(), weights.indices.toList())
        assertEquals(3, weights[0])

        // What the key is FOR. Without it the app's `weightAt` answers DEFAULT_WEIGHT for every
        // frame — a preset with no weights resolves down the identical code path as one written
        // before weights existed — so cell 0 gets one tick, the person who held it for three has
        // lost the hold, and nothing anywhere in the file says so. With it, the hold is on the
        // other side of the export.
        //
        // The read the app actually performs (SpriteSheet.fromJson:813-824) clamps every entry
        // into 1..9999 and then `fit`s the array to the frame count. This is that read stated as
        // an assertion: what is on disk is already what the app will hold, which is the property
        // the refusal tests protect, seen from the writing side.
        assertTrue(weights.all { it in 1..9999 }, "a written weight the app would change on read: $weights")
    }

    @Test
    fun packingTwiceWithTheSameWeightsProducesTheSameJson() {
        val clip = Clip("walk", listOf(0, 1, 2), weights = listOf(3, 1, 1))
        val first = pack(board(5), cols = 3, clips = listOf(clip))
        val second = pack(board(5), cols = 3, clips = listOf(clip))
        assertEquals(first.sidecarJson, second.sidecarJson)
    }

    @Test
    fun weightsOfTheWrongLengthIsRefused() {
        val short = assertFailsWith<IllegalArgumentException> {
            pack(
                board(5),
                cols = 3,
                clips = listOf(Clip("walk", listOf(0, 1, 2, 3, 4), weights = listOf(1, 3))),
            )
        }
        assertTrue(short.message!!.contains("walk"), short.message!!)
        assertTrue(short.message!!.contains("2"), short.message!!)
        assertTrue(short.message!!.contains("5"), short.message!!)
        // The other direction, which is just as wrong: a longer array's tail would be read as
        // weights on frames that are not there. Neither direction is padded or truncated.
        assertFailsWith<IllegalArgumentException> {
            pack(
                board(5),
                cols = 3,
                clips = listOf(Clip("walk", listOf(0, 1), weights = listOf(1, 1, 1))),
            )
        }
    }

    @Test
    fun aWeightOutsideOneToNineThousandNineHundredAndNinetyNineIsRefused() {
        for (bad in listOf(0, -1, 10000)) {
            val e = assertFailsWith<IllegalArgumentException> {
                pack(
                    board(5),
                    cols = 3,
                    clips = listOf(Clip("walk", listOf(0, 1, 2), weights = listOf(bad, 1, 1))),
                )
            }
            assertTrue(e.message!!.contains("walk"), e.message!!)
            assertTrue(e.message!!.contains("$bad"), e.message!!)
        }
        // Both edges are legal, and 1 is the default weight: the boundaries are accepted, and a
        // legal 9999 is written as 9999 rather than folded down to something rounder.
        assertEquals(listOf(1, 9999, 1), weightsOf(presetOf(Clip("walk", listOf(0, 1, 2), weights = listOf(1, 9999, 1)))))
    }

    // ── determinism ────────────────────────────────────────────────────────

    @Test
    fun packingTwiceProducesTheSameBytesAndTheSameJson() {
        val first = pack(board(5), cols = 3, clips = listOf(Clip("walk", listOf(0, 1, 2))))
        val second = pack(board(5), cols = 3, clips = listOf(Clip("walk", listOf(0, 1, 2))))
        assertContentEquals(first.rgba, second.rgba)
        assertEquals(first.sidecarJson, second.sidecarJson)
    }

    @Test
    fun cellNameOrderInTheCallersMapDoesNotReachTheFile() {
        val ascending = HashMap<Int, String>().apply { put(0, "a"); put(2, "c") }
        val descending = HashMap<Int, String>().apply { put(2, "c"); put(0, "a") }
        assertEquals(
            pack(board(5), cols = 3, names = ascending).sidecarJson,
            pack(board(5), cols = 3, names = descending).sidecarJson,
        )
    }

    // ── the sidecar must not describe a sheet that is not there ────────────

    @Test
    fun everyIndexInTheSidecarIsACellTheSheetHolds() {
        val sheet = pack(
            cells = board(5),
            cols = 3,
            clips = listOf(Clip("walk", listOf(0, 4, 2), "once", 6f)),
            names = mapOf(0 to "idle", 4 to "turn"),
        )
        val root = parse(sheet.sidecarJson)
        val cols = root["cols"]!!.jsonPrimitive.int
        val rows = root["rows"]!!.jsonPrimitive.int
        val cells = 5
        // The grid the sidecar claims is real, and big enough for the cells that were packed.
        assertEquals(3, cols)
        assertEquals(2, rows)
        assertTrue(cols * rows >= cells, "grid $cols x $rows cannot hold $cells cells")
        for (key in root["cellNames"]!!.jsonObject.keys) {
            assertTrue(key.toInt() in 0 until cells, "cellNames key $key is outside the sheet")
        }
        for (frame in root["presets"]!!.jsonArray[0].jsonObject["frames"]!!.jsonArray) {
            assertTrue(frame.jsonPrimitive.int in 0 until cells, "frame outside the sheet")
        }
    }

    // ── the encoded file has to be the sheet the sidecar describes ─────────

    @Test
    fun theEncodedPngIsCheckedAgainstWhatTheSidecarSays() {
        val sheet = pack(board(5), cols = 3)
        // Agreeing sizes pass, and say nothing about it.
        sheet.assertEncodedSize("Walk cycle.png", sheet.width, sheet.height)
        // The mistake this exists for: a PNG written at some other size, next to a sidecar that
        // describes a 48x32 sheet. The sidecar is internally perfect and describes a file that is
        // not the one next to it.
        val e = assertFailsWith<IllegalStateException> {
            sheet.assertEncodedSize("Walk cycle.png", 32, 32)
        }
        assertTrue(e.message!!.contains("Walk cycle.png"), e.message!!)
        assertTrue(e.message!!.contains("48x32"), e.message!!)
        assertFailsWith<IllegalStateException> {
            sheet.assertEncodedSize("Walk cycle.png", 96, 64)
        }
    }

    // ── refused inputs ─────────────────────────────────────────────────────

    @Test
    fun aCellOfTheWrongSizeIsRefused() {
        val tooSmall = listOf(solid(15, 15, 1), solid(CELL, CELL, 2))
        val e = assertFailsWith<IllegalArgumentException> { pack(tooSmall, cols = 2) }
        assertTrue(e.message!!.contains("cell 0"), e.message!!)
    }

    @Test
    fun aCellLargerThanTheCellSizeIsRefusedRatherThanCropped() {
        val mixed = listOf(solid(CELL, CELL, 1), solid(CELL + 1, CELL, 2))
        assertFailsWith<IllegalArgumentException> { pack(mixed, cols = 2) }
    }

    @Test
    fun degenerateShapesAreRefused() {
        assertFailsWith<IllegalArgumentException> { pack(emptyList(), cols = 3) }
        assertFailsWith<IllegalArgumentException> { pack(board(2), cols = 0) }
        assertFailsWith<IllegalArgumentException> { pack(board(2), cols = 3, cellW = 0) }
        assertFailsWith<IllegalArgumentException> { pack(board(2), cols = 3, cellH = -4) }
    }

    @Test
    fun aNameOrAFrameOnACellTheSheetDoesNotHaveIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            pack(board(5), cols = 3, names = mapOf(5 to "ghost"))
        }
        assertFailsWith<IllegalArgumentException> {
            pack(board(5), cols = 3, names = mapOf(-1 to "ghost"))
        }
        // A transparent trailing slot is in the GRID but holds no drawing, so naming it is still a
        // name on nothing.
        assertFailsWith<IllegalArgumentException> {
            pack(board(5), cols = 3, names = mapOf(7 to "ghost"))
        }
        assertFailsWith<IllegalArgumentException> {
            pack(board(5), cols = 3, clips = listOf(Clip("walk", listOf(0, 5))))
        }
    }

    @Test
    fun aClipThatCouldNeverShowAnythingIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            pack(board(5), cols = 3, clips = listOf(Clip("empty", emptyList())))
        }
        assertFailsWith<IllegalArgumentException> {
            pack(board(5), cols = 3, clips = listOf(Clip("bounce", listOf(0), "bounce")))
        }
    }

    @Test
    fun anUnwritableFpsIsRefused() {
        assertFailsWith<IllegalArgumentException> { pack(board(2), cols = 2, fps = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { pack(board(2), cols = 2, fps = -1f) }
        assertFailsWith<IllegalArgumentException> {
            pack(
                board(2),
                cols = 2,
                clips = listOf(Clip("walk", listOf(0), fps = Float.POSITIVE_INFINITY)),
            )
        }
        // 0 is allowed and is written: it means the sheet has no cadence chosen, and the app's
        // reader clamps that up to its own minimum.
        val none = parse(pack(board(2), cols = 2, fps = 0f).sidecarJson)
        assertEquals(0f, none["fps"]!!.jsonPrimitive.float)
    }

    // ── text that has to survive a round trip ──────────────────────────────

    @Test
    fun quotesTabsAndNewlinesInNamesRoundTrip() {
        val awkward = "he said \"hi\"\nand left\tdone"
        val sheet = SpritePacker.pack(
            cells = board(2),
            cellW = CELL,
            cellH = CELL,
            cols = 2,
            id = "id-1",
            name = awkward,
            sheetFileName = "a \"quoted\" file.png",
            fps = 8f,
            clips = listOf(Clip(awkward, listOf(0), "once", 1.5f)),
            cellNames = mapOf(0 to awkward),
        )
        val root = parse(sheet.sidecarJson)
        assertEquals(awkward, root["name"]!!.jsonPrimitive.content)
        assertEquals("a \"quoted\" file.png", root["sheetUri"]!!.jsonPrimitive.content)
        val preset = root["presets"]!!.jsonArray[0].jsonObject
        assertEquals(awkward, preset["name"]!!.jsonPrimitive.content)
        assertEquals(1.5f, preset["fps"]!!.jsonPrimitive.float)
        assertEquals(awkward, root["cellNames"]!!.jsonObject["0"]!!.jsonPrimitive.content)
    }
}
