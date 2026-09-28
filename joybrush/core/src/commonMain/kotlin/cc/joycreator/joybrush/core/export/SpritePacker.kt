package cc.joycreator.joybrush.core.export

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The version in the sidecar, matching `SpriteSheet.SPRITE_SCHEMA_VERSION` on the Android side.
 * Bump only with the app.
 */
const val SPRITE_SIDECAR_SCHEMA_VERSION = 1

/**
 * A reusable animation over CELL INDICES, one per animation on a sprite board.
 *
 * @param frames cell indices in playing order; a repeat is a repeat, not a mistake, which is how
 *   a ping-pong animation is written (`0,1,2,1`). Every index must be a cell the sheet holds.
 * @param type one of `loop`, `pingpong`, `once` — the app's vocabulary, nothing else.
 * @param fps 0 means "inherit the sheet's fps", and is then left out of the sidecar exactly as
 *   the app leaves it out.
 */
data class Clip(
    val name: String,
    val frames: List<Int>,
    val type: String = "loop",
    val fps: Float = 0f,
)

/**
 * One packed sheet: straight RGBA8 pixels plus the sidecar that describes them.
 *
 * The two go out together because they are one fact — a `.sprite.json` that describes a sheet
 * nobody can see is worse than no sidecar, and the pixels are useless without the grid that
 * names them. The caller writes the RGBA as a PNG under [PackedSheet.width] by
 * [PackedSheet.height] and the [PackedSheet.sidecarJson] as `<name>.sprite.json` beside it, then
 * calls [assertEncodedSize] on what it encoded before it writes either file.
 *
 * NOTE: [rgba] is a `ByteArray`, so the generated `equals` compares it by IDENTITY. Two sheets
 * packed from the same cells are equal in every field except that one, which says nothing about
 * the pixels. Compare with `contentEquals`.
 */
data class PackedSheet(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
    val sidecarJson: String,
) {

    /**
     * Checks the PNG that was just encoded against the size this sidecar describes, and does
     * nothing when they agree.
     *
     * This exists because the one way the sidecar can end up lying is not a bug in here. The
     * sidecar cannot carry the tie itself — a checksum or a size field would be a key the app does
     * not write, and the format is closed — and this packer never sees a file, so it cannot
     * compare a sidecar with the bytes the encoder produced. Whoever encodes the PNG is the only
     * party that knows both, which makes the check theirs to make. Putting it here rather than in
     * the encoder gives it one name, one message and one place to be tested.
     *
     * Call it BEFORE writing either file: a wrong size then costs a message, and never a pair of
     * files on disk that disagree with each other.
     *
     * @param fileName the file just encoded, so a failure names the file instead of a dimension.
     * @throws IllegalStateException if the encoded size is not [width] by [height].
     */
    fun assertEncodedSize(fileName: String, pngWidth: Int, pngHeight: Int) {
        check(pngWidth == width && pngHeight == height) {
            "$fileName was encoded ${pngWidth}x$pngHeight, but its sidecar describes a " +
                "${width}x$height sheet; the file and the .sprite.json would disagree"
        }
    }
}

/**
 * Packs a sprite board (or an animation's frames) into ONE image plus the `.sprite.json` sidecar
 * that SpriteLab and the Studio already read, so a sheet opens there with no conversion.
 *
 * The sidecar is not a report on the sheet, it is the contract: the key set, the key ORDER and the
 * sparse-write rule (a key that would carry its default is not written) all follow
 * `SpriteSheet.toJson()` on the Android side, because that file is what parses it. Adding a key
 * the app does not know is out of scope; changing one that it does is a format change.
 *
 * Two properties this exists to hold:
 *
 * 1. **A FILE IS ALWAYS WRITTEN FIRST.** Packing is pure: no file, no SpriteLab, no network, no
 *    clock, no random source. "Export and open in SpriteLab" writes the same two bytes this
 *    returns and then hands them to whatever is there, so the button works on a machine with no
 *    SpriteLab and the two buttons can never disagree about what is on disk.
 * 2. **DETERMINISTIC.** The same cells, in the same order, with the same arguments produce the
 *    same pixels and the same JSON string, byte for byte. Cell names are emitted in ascending
 *    index order rather than the caller's map order for the same reason — a re-export that
 *    reorders keys looks like a change to anyone watching the repo. Everything optional is
 *    omitted rather than written as a default, so a sheet that gains nothing does not churn the
 *    file.
 *
 * What it refuses, and why: a cell of the wrong size, a cell index or clip frame outside the
 * packed cells, a clip with no frames, an unknown clip type, and an empty pack. Each of those
 * would write a sidecar describing something the sheet does not contain, or an image with no
 * drawing in it. Failing here is a message on the export button; failing later is a sheet that
 * renders blank in someone else's app.
 */
@OptIn(ExperimentalSerializationApi::class)
object SpritePacker {

    /** Two spaces, like `document.json`; the app's own sidecar writer is pretty. */
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** The closed set the app reads. An unknown type is stored verbatim and then behaves as a
     *  loop, which is exactly the kind of quiet wrongness a writer should catch. */
    private val CLIP_TYPES = setOf("loop", "pingpong", "once")

    /**
     * Lays [cells] out in reading order on one RGBA8 sheet and writes the sidecar for it.
     *
     * @param cells straight RGBA8 images, all `cellW` by `cellH`, in reading order. Cell `i` goes
     *   to column `i % cols`, row `i / cols`.
     * @param cols cells per row. `rows` is `ceil(cells.size / cols)`, so `cols` may be larger than
     *   the cell count: the surplus slots are written as fully transparent and the sidecar's grid
     *   still accounts for them, so it never claims a cell the image does not have.
     * @param sheetFileName e.g. `"Walk cycle.png"`, written as `sheetUri` verbatim — relative,
     *   next to the sidecar. The packer never touches a filesystem and never learns a path.
     * @param fps the sheet's default cadence, always written. 0 means "not chosen"; the app's
     *   reader clamps that up to its own minimum, so it is honest rather than broken.
     * @param clips the animations; `presets` is written only when this is non-empty.
     * @param cellNames sparse cell aliases, keyed by cell index. `cellNames` is written only when
     *   non-empty, in ascending index order.
     *
     * @throws IllegalArgumentException if any cell is not exactly `cellW` by `cellH` of RGBA, if
     *   a cell size, [cols] or [cells] is degenerate, if a name or clip frame names a cell index
     *   the sheet does not hold, if a clip has no frames or an unknown type, or if [fps] is
     *   negative or not a finite number (JSON has no word for NaN, and the app would read a
     *   missing key as its own default).
     */
    fun pack(
        cells: List<ByteArray>,
        cellW: Int,
        cellH: Int,
        cols: Int,
        id: String,
        name: String,
        sheetFileName: String,
        fps: Float,
        clips: List<Clip>,
        cellNames: Map<Int, String> = emptyMap(),
    ): PackedSheet {
        require(cellW > 0 && cellH > 0) { "cell size must be positive, got ${cellW}x$cellH" }
        require(cols >= 1) { "cols must be at least 1, got $cols" }
        require(cells.isNotEmpty()) {
            "nothing to pack: a sheet with no cells is an image with nothing in it"
        }
        require(fps.isFinite() && fps >= 0f) { "fps must be a finite number of 0 or more, got $fps" }

        // Long arithmetic throughout: cellW * cellH * 4 overflows Int long before the sheet does,
        // and an overflowed size check is a size check that passes.
        val cellBytes = cellW.toLong() * cellH.toLong() * 4L
        for (i in cells.indices) {
            require(cells[i].size.toLong() == cellBytes) {
                "cell $i is ${cells[i].size} bytes; every cell must be ${cellW}x$cellH} RGBA8 " +
                    "(${cellBytes} bytes)"
            }
        }

        // Nothing may be written to the sidecar that the sheet will not contain, so the index
        // space is checked before a single byte of JSON is built.
        for (index in cellNames.keys) {
            require(index in 0 until cells.size) {
                "cellNames names cell $index, but the sheet holds ${cells.size} cells " +
                    "(0..${cells.size - 1}); a name on a transparent slot is a name on nothing"
            }
        }
        for (clip in clips) {
            require(clip.type in CLIP_TYPES) {
                "clip \"${clip.name}\" has type \"${clip.type}\"; the app knows " +
                    CLIP_TYPES.sorted().joinToString(", ")
            }
            require(clip.fps.isFinite() && clip.fps >= 0f) {
                "clip \"${clip.name}\" has fps ${clip.fps}"
            }
            require(clip.frames.isNotEmpty()) { "clip \"${clip.name}\" has no frames" }
            for (frame in clip.frames) {
                require(frame in 0 until cells.size) {
                    "clip \"${clip.name}\" plays cell $frame, but the sheet holds " +
                        "${cells.size} cells (0..${cells.size - 1})"
                }
            }
        }

        val rows = ((cells.size.toLong() + cols - 1) / cols).toInt()
        val width = cols.toLong() * cellW
        val height = rows.toLong() * cellH
        val pixels = width * height
        require(pixels * 4L <= Int.MAX_VALUE) {
            "a ${width}x$height sheet needs ${pixels * 4L} bytes, which does not fit in an array"
        }

        // Zero-filled, so every slot with no cell in it is transparent (0,0,0,0) with no pass
        // over the image. A copy is one row at a time: a whole cell only lands contiguously when
        // it is the full width of the sheet, and that case is not worth the special case.
        val cellStride = cellW * 4
        val sheetStride = width.toInt() * 4
        val rgba = ByteArray(sheetStride * height.toInt())
        for (i in cells.indices) {
            val source = cells[i]
            val left = (i % cols) * cellStride
            val top = (i / cols) * cellH
            var from = 0
            for (y in 0 until cellH) {
                source.copyInto(rgba, (top + y) * sheetStride + left, from, from + cellStride)
                from += cellStride
            }
        }

        val sidecar = buildJsonObject {
            put("spriteSchemaVersion", SPRITE_SIDECAR_SCHEMA_VERSION)
            put("id", id)
            put("name", name)
            put("sheetUri", sheetFileName)
            put("cols", cols)
            put("rows", rows)
            put("fps", fps)
            // Everything below is sparse, in the app's order, and nothing the app does not write.
            if (clips.isNotEmpty()) {
                putJsonArray("presets") {
                    clips.forEachIndexed { index, clip ->
                        addJsonObject {
                            put("id", "$id-clip-$index")
                            put("name", clip.name)
                            put("type", clip.type)
                            if (clip.fps > 0f) put("fps", clip.fps)
                            putJsonArray("frames") {
                                for (frame in clip.frames) add(frame)
                            }
                        }
                    }
                }
            }
            if (cellNames.isNotEmpty()) {
                putJsonObject("cellNames") {
                    for (index in cellNames.keys.sorted()) {
                        put(index.toString(), cellNames.getValue(index))
                    }
                }
            }
        }

        return PackedSheet(
            width = width.toInt(),
            height = height.toInt(),
            rgba = rgba,
            sidecarJson = json.encodeToString(JsonObject.serializer(), sidecar),
        )
    }
}
