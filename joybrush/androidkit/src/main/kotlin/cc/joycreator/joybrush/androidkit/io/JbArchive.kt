package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BrushValidate
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.doc.DocJson
import cc.joycreator.joybrush.core.doc.DOC_VERSION
import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.TILE_SIZE
import cc.joycreator.joybrush.core.stroke.StrokeCodec
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelLayout
import cc.joycreator.joybrush.core.vector.LineRules
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What is in the `mimetype` entry, byte for byte, with no trailing newline. */
const val JB_MIMETYPE = "application/x-joybrush"

/**
 * Bytes in one PAINT tile: [TILE_SIZE] square, premultiplied RGBA8, row 0 at the top.
 *
 * Derived from the engine's own tile size and NOT written out as a second number, so the engine and
 * this file cannot drift apart and quietly put every tile of every document in the wrong place.
 */
const val TILE_BYTES = TILE_SIZE * TILE_SIZE * 4

/**
 * The deflate level every zip this module writes uses.
 *
 * ONE number, read by [JbArchive] and `OraExport` -- the two writers, and the two places a literal
 * `6` used to sit. They are in this same package, so the second writer reads this with no import and
 * no ceremony. A second copy of the level in either writer is a bug nothing in the tree can catch:
 * both are plain integers on a line that compiles either way, and the file they produce is still a
 * perfectly readable zip that opens in anything. This constant is here so there is nothing left to
 * copy. R39, and JB-0.10's Benchmark harness (which reads it rather than restating it, so the report
 * says which level was measured even if the number is ever changed).
 */
const val ARCHIVE_DEFLATE_LEVEL = 6

/** Thrown by every function here. The message is the whole error report, so it says what is wrong. */
class JbArchiveException(message: String) : Exception(message)

/**
 * A drawing, in memory: the document, its paint tiles, its ink strokes and an optional thumbnail.
 *
 * NOTE: this is a `data class` and the arrays inside it are arrays, so `==` on two [JbContents]
 * compares the arrays by REFERENCE and says two byte-identical documents are different. Compare
 * them with `contentEquals` per tile — which is what every test here does. (A generated
 * `contentEquals` would have been the wrong fix: it would hide a difference in the document itself.)
 */
data class JbContents(
    val doc: JbDocument,
    /** (layerId, celId, "tx_ty") to one tile of exactly [TILE_BYTES] bytes. */
    val tiles: Map<Triple<String, String, String>, ByteArray>,
    /** (layerId, celId) to the line records of that cel, ordered by `seq` (an INK cel until v9, any pixel cel from v10). */
    val strokes: Map<Pair<String, String>, List<StrokeRecord>>,
    val thumbnailPng: ByteArray? = null,
    /** Media payloads (DocModel v8, v9): one store tile of exactly `MediaStores.tileBytes(TILE_SIZE, store)` bytes each. */
    val mediaTiles: Map<MediaTileKey, ByteArray> = emptyMap(),
    /**
     * Pixel slabs ABOVE a tile's bottom one (v10, JB-5.20 D2): each is [TILE_BYTES] bytes. The bottom slab of every tile
     * is in [tiles], exactly as before, so a cel of pure pixels has nothing here.
     */
    val slabs: Map<SlabKey, ByteArray> = emptyMap(),
    /** Saved looks of tiles that lines cross (v10, D3): (layerId, celId, "tx_ty") to [TILE_BYTES] bytes. */
    val looks: Map<Triple<String, String, String>, ByteArray> = emptyMap(),
    /** The frozen brush copies (v10, D5), by brush id; exactly the ids of `doc.brushes`. */
    val brushes: Map<String, BrushPreset> = emptyMap(),
)

/** One non-bottom pixel slab in an archive: `layers/<layerId>/<celId>/<key>.s<seq>.rgba`. */
data class SlabKey(val layerId: String, val celId: String, val key: String, val seq: Long)

/** One media payload tile in an archive: `layers/<layerId>/<celId>/<key>.<store>.f16`, or `<key>.g.rgba` for the ground. */
data class MediaTileKey(val layerId: String, val celId: String, val key: String, val store: String)

/**
 * The `.joybrush` file (JB-0.08a): a zip holding one document's everything.
 *
 * | Entry | Content | Compression |
 * |---|---|---|
 * | `mimetype` | [JB_MIMETYPE] | STORED, and the FIRST entry, like OpenRaster |
 * | `document.json` | `DocJson.encode(doc)` | deflate |
 * | `layers/<layerId>/<celId>/<tx>_<ty>.rgba` | one PAINT tile, [TILE_BYTES] bytes | deflate |
 * | `layers/<layerId>/<celId>/strokes.jbs` | `StrokeCodec.encodeAll` of the cel's line records, ordered by `seq` (INK cel until v9, any pixel cel from v10) | deflate |
 * | `layers/<layerId>/<celId>/<tx>_<ty>.s<seq>.rgba` | a pixel slab above the tile's bottom one, [TILE_BYTES] bytes (v10; absent for a cel of pure pixels) | deflate |
 * | `layers/<layerId>/<celId>/<tx>_<ty>.look.rgba` | the saved look of a tile lines cross, [TILE_BYTES] bytes (v10) | deflate |
 * | `brushes/<n>-<name>.json` | a frozen copy of a brush some line uses, `BrushJson.encode` (v10); the brush's own `id` is its key | deflate |
 * | `layers/<layerId>/<celId>/<tx>_<ty>.<store>.f16` | one media float store tile, little-endian half floats (v8) | deflate |
 * | `layers/<layerId>/<celId>/<tx>_<ty>.g.rgba` | one media ground tile, [TILE_BYTES] bytes like a paint tile (v9) | deflate |
 * | `thumbnail.png` | written by JB-0.08b | stored |
 *
 * Plain JVM only: `java.util.zip` and `java.io`, no Android, so it is testable without a phone.
 *
 * Four rules, in the order they matter when a person is about to lose work:
 *
 *  1. **An entry name is never trusted.** [unsafeReason] refuses `..` anywhere in a name, `/` at the
 *     front, a backslash, a colon (a Windows drive letter or an NTFS stream), a control character
 *     and every empty path segment — on read AND on write, because a hostile layer id must not be
 *     able to write an escaping name either. Both separators are checked because a file that a
 *     Linux phone wrote can be opened on a Windows desktop.
 *  2. **No allocation is ever sized from a number the archive itself declared.** A declared size is
 *     a wish. Every read is bounded by a constant in this file ([MAX_TOTAL_BYTES] and friends) and by
 *     a cap of `limit` bytes, so a twelve-byte archive cannot ask for four gigabytes. A declared
 *     size that DISAGREES with the bytes that actually arrive is itself an error.
 *  3. **A save is all or nothing.** [save] writes `<file>.tmp`, flushes it to the platter, and only
 *     then moves the old file to `<file>.bak` and the new one into place. If anything at all goes
 *     wrong the `.tmp` is deleted and the file the person already had is left untouched.
 *  4. **Nothing here catches an exception it could not have received.** A `catch` that cannot fire
 *     tells the reader they are protected when they are not, and this is the one file where they
 *     would believe it.
 *
 * THE EXCEPTION CONTRACT: every refusal from every function here arrives as a [JbArchiveException].
 * Not "most of them" — all of them, so that one `catch` at the save and open buttons is enough and a
 * person is shown a sentence rather than a stack trace. An [Error] (an [OutOfMemoryError] on a very
 * large drawing, say) is deliberately NOT caught: swallowing that would leave a half-open file and a
 * confused program rather than a crash.
 */
object JbArchive {

    private const val MIMETYPE_NAME = "mimetype"
    private const val DOCUMENT_NAME = "document.json"
    private const val THUMBNAIL_NAME = "thumbnail.png"
    private const val LAYERS_DIR = "layers"
    private const val STROKES_NAME = "strokes.jbs"
    private const val TILE_EXT = ".rgba"
    private const val LOOK_SUFFIX = ".look.rgba"
    private const val BRUSHES_DIR = "brushes"
    private const val BRUSH_EXT = ".json"
    /** `<key>.s<seq>.rgba`'s stem is `<key>.s<seq>`. */
    private val SLAB_STEM = Regex("^(-?\\d+_-?\\d+)\\.s(\\d+)$")

    /*
     * Bounds. Every one of these is OURS. A count or a size read out of the file bounds only the
     * file's own claim about itself, which bounds nothing at all.
     */
    private const val MAX_ENTRIES = 20_000
    private const val MAX_NAME_CHARS = 512
    private const val MAX_MIMETYPE_BYTES = 64
    private const val MAX_DOCUMENT_BYTES = 32 * 1024 * 1024
    private const val MAX_THUMBNAIL_BYTES = 32 * 1024 * 1024
    private const val MAX_STROKES_BYTES = 64 * 1024 * 1024
    private const val MAX_BRUSH_BYTES = 4 * 1024 * 1024
    private const val MAX_IGNORED_BYTES = 64 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    private const val CHUNK = 8 * 1024

    private val TILE_ORDER = compareBy<Triple<String, String, String>>({ it.first }, { it.second }, { it.third })
    private val CEL_ORDER = compareBy<Pair<String, String>>({ it.first }, { it.second })
    private val SLAB_ORDER = compareBy<SlabKey>({ it.layerId }, { it.celId }, { it.key }, { it.seq })
    private val MEDIA_ORDER = compareBy<MediaTileKey>({ it.layerId }, { it.celId }, { it.key }, { it.store })
    private val MEDIA_TILE_BYTES = MediaStores.tileBytes(TILE_SIZE)
    /** A ground tile's name ends `.g.rgba`: checked before the paint tile's `.rgba`, which it also ends with. */
    private val GROUND_SUFFIX = ".${MediaStores.GROUND}${MediaStores.GROUND_EXT}"

    /**
     * Writes the whole archive to [file] without ever leaving a half-written drawing behind.
     *
     * Order of events: write `<file>.tmp`, flush it to disk, move any existing `<file>` to
     * `<file>.bak`, then move the `.tmp` into place. So at every instant the person has either the
     * old file or the new one — and if the last rename fails, the old one is put back and the
     * message says where it went.
     */
    fun save(file: File, contents: JbContents) = atomicWrite(file) { write(it, contents) }

    /** Already validated/saved source, copied without inflating or recompressing its paint tiles. */
    internal fun copySaved(source: File, file: File) = atomicWrite(file) { out ->
        source.inputStream().use { it.copyTo(out) }
    }

    private fun atomicWrite(file: File, writeBytes: (FileOutputStream) -> Unit) {
        val tmp = File(file.path + ".tmp")
        val bak = File(file.path + ".bak")
        try {
            FileOutputStream(tmp).use { out ->
                writeBytes(out)
                // A rename that beats a buffered write to the platter loses the last edit. This is
                // the whole reason the dance above exists, so it is not optional and not caught.
                out.fd.sync()
            }
            if (file.exists()) {
                if (bak.exists() && !bak.delete()) {
                    throw JbArchiveException("the older ${bak.name} cannot be removed, so ${file.name} is left alone")
                }
                if (!file.renameTo(bak)) {
                    throw JbArchiveException("${file.name} cannot be moved aside to ${bak.name}, so it is left alone")
                }
            }
            if (!tmp.renameTo(file)) {
                val restored = if (bak.exists()) bak.renameTo(file) else true
                if (!restored) {
                    throw JbArchiveException(
                        "${tmp.name} cannot be moved onto ${file.name}, and ${bak.name} cannot be put back either. " +
                            "The drawing is still in ${bak.name}."
                    )
                }
                throw JbArchiveException("${tmp.name} cannot be moved onto ${file.name}")
            }
        } catch (e: Exception) {
            // Anything at all went wrong, so the file the person already had is still the file they
            // have. Deleting a `.tmp` that is already gone is a no-op, not an error.
            tmp.delete()
            if (e is JbArchiveException) throw e
            throw JbArchiveException("could not save ${file.name}: ${brief(e)}")
        }
    }

    /**
     * Handle-free, no-IO structural admission using the writer's actual checks. No line replay,
     * look composition or whole-ZIP serialization occurs. Stroke/brush serializability and all
     * generated payload paths are checked; encoded values are discarded one entry at a time.
     *
     * Input is BORROWED and must remain stable for this call. This neither snapshots arrays or
     * nested metadata nor issues a certificate valid after caller mutation. A queued loader must
     * own its immutable input separately. ZIP envelope/size budgets, actual saved-look coverage,
     * renderer capabilities and upload ownership are not established here. Generic archive
     * compatibility is unchanged; stricter cold-loader profiles belong at their own boundary.
     */
    internal fun requireStructure(contents: JbContents) {
        prepareStructure(contents, retainEntries = false)
    }

    private class PreparedEntries(
        val tiles: List<Pair<String, ByteArray>>,
        val slabs: List<Pair<String, ByteArray>>,
        val looks: List<Pair<String, ByteArray>>,
        val media: List<Pair<String, ByteArray>>,
        val strokes: List<Pair<String, ByteArray>>,
        val brushes: List<Pair<String, ByteArray>>,
    )

    /** [retainEntries] controls only retention for writing, never which checks run. */
    private fun prepareStructure(contents: JbContents, retainEntries: Boolean): PreparedEntries {
        fun keep(entries: MutableList<Pair<String, ByteArray>>, path: String, bytes: ByteArray) {
            if (retainEntries) entries.add(path to bytes)
        }
        val doc = contents.doc
        val problems = DocOps.validate(doc)
        if (problems.isNotEmpty()) {
            throw JbArchiveException("this document cannot be saved: " + problems.joinToString("; "))
        }
        // New source-kind diagnostics must precede encoding, which has its own checked exception.
        // Keep ordinary codec/legacy behavior while reporting invalid captured recipes as archive
        // admission failures, before retaining encoded payloads or touching an output stream.
        for(layer in doc.layers) for(cel in DocOps.storedCels(layer)) {
            val sourceProblems = LineRules.sourceProblems(doc.version, layer, cel,
                contents.strokes[layer.id to cel.id].orEmpty())
            if(sourceProblems.isNotEmpty()) throw JbArchiveException(sourceProblems.joinToString("; "))
        }

        val declared = declaredTiles(doc)

        val tileEntries = ArrayList<Pair<String, ByteArray>>()
        for (key in contents.tiles.keys.sortedWith(TILE_ORDER)) {
            val bytes = contents.tiles.getValue(key)
            checkTile(key, bytes, declared)
            keep(tileEntries, tilePath(key), bytes)
        }

        // Check the reverse direction too. Validating only the supplied payloads can otherwise
        // replace a healthy file with an archive that our own reader refuses to reopen.
        for (layer in doc.layers) for (cel in DocOps.storedCels(layer)) {
            for (key in cel.tiles) {
                if (Triple(layer.id, cel.id, key) !in contents.tiles) {
                    throw JbArchiveException(
                        "the document lists tile \"$key\" of layer \"${layer.id}\" cel \"${cel.id}\", and no tile was given"
                    )
                }
            }
        }

        // Slabs above the bottom one and saved looks (v10), both directions like the tiles.
        val slabEntries = ArrayList<Pair<String, ByteArray>>()
        val slabsDeclared = declaredSlabs(doc)
        for (key in contents.slabs.keys.sortedWith(SLAB_ORDER)) {
            checkSlab(key, contents.slabs.getValue(key).size, slabsDeclared)
            keep(slabEntries, slabPath(key), contents.slabs.getValue(key))
        }
        for ((cel, listed) in slabsDeclared) for ((key, seq) in listed) {
            if (SlabKey(cel.first, cel.second, key, seq) !in contents.slabs) {
                throw JbArchiveException(
                    "the document lists a slab at seq $seq on tile \"$key\" of layer \"${cel.first}\" cel \"${cel.second}\", and none was given"
                )
            }
        }
        val lookEntries = ArrayList<Pair<String, ByteArray>>()
        val looksDeclared = declaredLooks(doc)
        for (key in contents.looks.keys.sortedWith(TILE_ORDER)) {
            checkLook(key, contents.looks.getValue(key).size, looksDeclared)
            keep(lookEntries, lookPath(key), contents.looks.getValue(key))
        }
        for ((cel, keys) in looksDeclared) for (key in keys) {
            if (Triple(cel.first, cel.second, key) !in contents.looks) {
                throw JbArchiveException(
                    "the document lists a look for tile \"$key\" of layer \"${cel.first}\" cel \"${cel.second}\", and none was given"
                )
            }
        }

        // The frozen brushes (v10, D5): exactly the ones the document lists, each the brush it says it is.
        val frozen = contents.brushes
        if (frozen.keys != doc.brushes.toSet()) {
            val missing = doc.brushes.filter { it !in frozen }
            val extra = frozen.keys.filter { it !in doc.brushes }
            throw JbArchiveException(
                (if (missing.isNotEmpty()) "the document lists brush ${missing.joinToString { "\"$it\"" }}, and no copy was given. " else "") +
                    (if (extra.isNotEmpty()) "a copy of brush ${extra.joinToString { "\"$it\"" }} was given, and the document does not list it" else "")
            )
        }
        val brushEntries = ArrayList<Pair<String, ByteArray>>()
        for ((index, id) in doc.brushes.sorted().withIndex()) {
            val preset = frozen.getValue(id)
            if (preset.id != id) throw JbArchiveException("the frozen copy kept as \"$id\" says it is brush \"${preset.id}\"")
            val brushProblems = BrushValidate.validate(preset)
            if (brushProblems.isNotEmpty()) throw JbArchiveException("brush \"$id\" cannot be kept with the drawing: ${brushProblems.joinToString("; ")}")
            val text = try {
                BrushJson.encode(preset)
            } catch (e: BrushException) {
                throw JbArchiveException("brush \"$id\" cannot be kept with the drawing: ${brief(e)}")
            }
            keep(brushEntries, brushPath(index, id), text.toByteArray(Charsets.UTF_8))
        }

        // Media state, both directions, as for paint tiles: every file is declared, and every declared tile has state.
        val mediaDeclared = declaredMediaTiles(doc)
        val mediaEntries = ArrayList<Pair<String, ByteArray>>()
        for (key in contents.mediaTiles.keys.sortedWith(MEDIA_ORDER)) {
            val bytes = contents.mediaTiles.getValue(key)
            checkMediaTile(key, bytes.size, mediaDeclared)
            keep(mediaEntries, mediaPath(key), bytes)
        }
        val given = contents.mediaTiles.keys.mapTo(HashSet()) { Triple(it.layerId, it.celId, it.key) }
        for ((cel, keys) in mediaDeclared) for (key in keys) {
            if (Triple(cel.first, cel.second, key) !in given) {
                throw JbArchiveException(
                    "the document lists media tile \"$key\" of layer \"${cel.first}\" cel \"${cel.second}\", and no state was given for it"
                )
            }
        }

        val strokeEntries = ArrayList<Pair<String, ByteArray>>()
        for (cel in contents.strokes.keys.sortedWith(CEL_ORDER)) {
            if (declared[cel] == null) {
                throw JbArchiveException(
                    "strokes are given for layer \"${cel.first}\" cel \"${cel.second}\", and this document has no such cel"
                )
            }
            // In time order on disk: the file says what covers what, whatever order the caller held the lines in.
            keep(strokeEntries, strokesPath(cel), StrokeCodec.encodeAll(contents.strokes.getValue(cel).sortedBy { it.seq }))
        }

        // The cheap record rules are shared with structural admission. Only write checks
        // geometric look reach, after this complete preparation succeeds (opening never replays).
        val lineProblems = lineProblems(doc, contents.strokes, doc.brushes.toSet(), exactLooks = null)
        if (lineProblems.isNotEmpty()) {
            throw JbArchiveException("the lines in this document cannot be saved: " + lineProblems.joinToString("; "))
        }

        // A cel that says where its strokes are, with no strokes to put there, is a drawing that
        // loses its ink on the next save. Refused here rather than discovered on someone's reopen.
        for (l in doc.layers) for (c in l.cels) {
            if (c.strokesFile != null && Pair(l.id, c.id) !in contents.strokes) {
                throw JbArchiveException(
                    "cel \"${c.id}\" of layer \"${l.id}\" says its strokes are in \"${c.strokesFile}\", and no strokes were given"
                )
            }
        }

        return PreparedEntries(tileEntries, slabEntries, lookEntries, mediaEntries, strokeEntries, brushEntries)
    }

    /**
     * Writes the archive to [out], which is NOT closed — `finish()` is called and the stream is
     * flushed, so the caller decides when it ends.
     *
     * Everything is checked before the first byte is written, so a refused archive leaves [out] as
     * it was found instead of half a file long.
     */
    fun write(out: OutputStream, contents: JbContents) {
        val entries = prepareStructure(contents, retainEntries = true)
        // The codec writes v4. Upgrade only the prepared output header of stroke-bearing saves,
        // never caller metadata or ordinary raster/media-only legacy archives. Historical regional
        // null-source records cannot be relabelled as v11 physical truth by this upgrade.
        val doc = if(contents.strokes.isNotEmpty() && contents.doc.version < 11)
            contents.doc.copy(version = 11) else contents.doc
        val sourceProblems = lineProblems(doc, contents.strokes, contents.brushes.keys, null)
        if(sourceProblems.isNotEmpty()) throw JbArchiveException(sourceProblems.joinToString("; "))
        // Save-only geometry remains outside structural admission: this may replay CPU-supported
        // lines, and is deliberately not called by requireStructure or the reader.
        val lookProblems = ArrayList<String>()
        for (layer in doc.layers) for (cel in DocOps.storedCels(layer)) {
            lookProblems += LineRules.lookProblems(layer.id, cel,
                contents.strokes[Pair(layer.id, cel.id)].orEmpty()) { contents.brushes[it] }
        }
        if (lookProblems.isNotEmpty()) {
            throw JbArchiveException("the lines in this document cannot be saved: " + lookProblems.joinToString("; "))
        }

        // STORED, because a reader can identify the file by the first bytes without inflating, and
        // because that is what OpenRaster does and what the reader expects.
        val zos = ZipOutputStream(out)
        zos.setLevel(ARCHIVE_DEFLATE_LEVEL)
        try {
            writeMimetype(zos)

            zos.putNextEntry(ZipEntry(DOCUMENT_NAME))
            zos.write(DocJson.encode(doc).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            for (entry in entries.tiles) {
                zos.putNextEntry(ZipEntry(entry.first))
                zos.write(entry.second)
                zos.closeEntry()
            }
            for (entry in entries.slabs + entries.looks) {
                zos.putNextEntry(ZipEntry(entry.first))
                zos.write(entry.second)
                zos.closeEntry()
            }
            for (entry in entries.media) {
                zos.putNextEntry(ZipEntry(entry.first))
                zos.write(entry.second)
                zos.closeEntry()
            }
            for (entry in entries.strokes) {
                zos.putNextEntry(ZipEntry(entry.first))
                zos.write(entry.second)
                zos.closeEntry()
            }
            for (entry in entries.brushes) {
                zos.putNextEntry(ZipEntry(entry.first))
                zos.write(entry.second)
                zos.closeEntry()
            }
            val thumb = contents.thumbnailPng
            if (thumb != null) {
                zos.putNextEntry(ZipEntry(THUMBNAIL_NAME))
                zos.write(thumb)
                zos.closeEntry()
            }
            zos.finish()
            zos.flush()
            // close() is deliberately not called: it would close the caller's stream too, and
            // finish() has already written the central directory. The deflater is released when
            // the object is collected.
        } catch (e: JbArchiveException) {
            throw e
        } catch (e: Exception) {
            throw JbArchiveException("the archive could not be written: ${brief(e)}")
        }
    }

    /**
     * Reads an archive. [input] is NOT closed — it belongs to the caller.
     *
     * Refuses: an entry name that could climb out of a folder, a duplicated name, a first entry
     * that is not a STORED `mimetype`, a `mimetype` that says something else, no `document.json`, a
     * document that fails `DocOps.validate`, a tile that is not [TILE_BYTES] bytes, a tile listed by
     * the document that the archive does not have, and an entry that declares more bytes than it is
     * allowed. Unknown entries are ignored, but only after their NAMES have been checked.
     *
     * EVERY refusal arrives as a [JbArchiveException] — that is the promise this function makes, and
     * it is why there is a `RuntimeException` catch below. `java.util.zip` is not a tidy library:
     * a hostile file can come back as a `ZipException`, an `IllegalArgumentException` out of a
     * decoder, or something else new, and a caller that catches one type and says "this file is
     * damaged, here is why" must not be handed a raw exception from underneath it. The catch names
     * the exception class in the message so a bug in this file is still diagnosable from one line.
     *
     * A duplicate entry name is refused here even though [write] can never produce one:
     * `ZipOutputStream` keeps its own set of names and throws `ZipException: duplicate entry` before
     * the second one reaches the disk. Another tool can still build such a file, so the guard stays.
     *
     * **Older drawings (JB-5.20 D13).** A file older than version 10 whose layers include INK ones opens with them as PAINT
     * layers of lines ([DocJson.decode]). Those lines name brushes by id and the file has no frozen copies, so
     * [legacyBrushes] (the library) supplies the copies, which are then kept with the drawing from the next save. A line
     * whose brush it cannot supply makes the file unopenable, in words: guessing a brush would draw the line differently.
     */
    fun read(input: InputStream, legacyBrushes: ((String) -> BrushPreset?)? = null): JbContents {
        val zis = ZipInputStream(input)
        val names = HashSet<String>()
        val budget = ByteBudget(MAX_TOTAL_BYTES)
        var mimetypeSeen = false
        var document: ByteArray? = null
        var thumbnail: ByteArray? = null
        val tiles = LinkedHashMap<Triple<String, String, String>, ByteArray>()
        val strokes = LinkedHashMap<Pair<String, String>, List<StrokeRecord>>()
        val media = LinkedHashMap<MediaTileKey, ByteArray>()
        val slabs = LinkedHashMap<SlabKey, ByteArray>()
        val looks = LinkedHashMap<Triple<String, String, String>, ByteArray>()
        val brushes = LinkedHashMap<String, BrushPreset>()
        var count = 0

        try {
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name

                // Checked before a single byte of this entry is inflated: a name is the only part of
                // an archive that can reach outside the folder it is opened in.
                val why = unsafeReason(name)
                if (why != null) throw JbArchiveException("entry \"$name\" is refused: $why")
                if (!names.add(name)) throw JbArchiveException("entry \"$name\" is in this archive twice")
                count++
                if (count > MAX_ENTRIES) {
                    throw JbArchiveException("this archive has more than $MAX_ENTRIES entries")
                }
                if (count == 1 && name != MIMETYPE_NAME) {
                    throw JbArchiveException("the first entry must be \"$MIMETYPE_NAME\" but it is \"$name\"")
                }
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }

                when {
                    name == MIMETYPE_NAME -> {
                        if (entry.method != ZipEntry.STORED) {
                            throw JbArchiveException("\"mimetype\" must be STORED, the way it is written")
                        }
                        val bytes = readBounded(zis, entry, MAX_MIMETYPE_BYTES, budget)
                        // The bytes are not put in the message: they came from outside, and a control
                        // character or a fake "cannot open:" in a log helps nobody.
                        if (!bytes.contentEquals(JB_MIMETYPE.toByteArray(Charsets.US_ASCII))) {
                            throw JbArchiveException("\"mimetype\" is not \"$JB_MIMETYPE\" (it is ${bytes.size} bytes)")
                        }
                        mimetypeSeen = true
                    }

                    name == DOCUMENT_NAME -> {
                        document = readBounded(zis, entry, MAX_DOCUMENT_BYTES, budget)
                    }

                    name == THUMBNAIL_NAME -> {
                        thumbnail = readBounded(zis, entry, MAX_THUMBNAIL_BYTES, budget)
                    }

                    name.startsWith("$LAYERS_DIR/") && name.endsWith(MediaStores.EXT) -> {
                        val parts = name.split('/')
                        val stem = parts.getOrNull(3)?.dropLast(MediaStores.EXT.length)
                        val dot = stem?.lastIndexOf('.') ?: -1
                        if (parts.size != 4 || stem == null || dot <= 0) {
                            throw JbArchiveException("entry \"$name\" is a media path, but not layers/<layer>/<cel>/<tx>_<ty>.<store>${MediaStores.EXT}")
                        }
                        val key = MediaTileKey(parts[1], parts[2], stem.substring(0, dot), stem.substring(dot + 1))
                        if (!isTileKey(key.key)) throw JbArchiveException("entry \"$name\" is media state, but \"${key.key}\" is not a \"tx_ty\" tile key")
                        if (key.store !in MediaStores.ALL) {
                            throw JbArchiveException("entry \"$name\" is media state for \"${key.store}\", which is not one of ${MediaStores.ALL.joinToString(", ")}")
                        }
                        val bytes = readBounded(zis, entry, MEDIA_TILE_BYTES + 1, budget)
                        if (bytes.size != MEDIA_TILE_BYTES) {
                            throw JbArchiveException("media tile \"$name\" is ${bytes.size} bytes, a media tile is $MEDIA_TILE_BYTES")
                        }
                        media[key] = bytes
                    }

                    name.startsWith("$LAYERS_DIR/") && name.endsWith(GROUND_SUFFIX) -> {
                        val parts = name.split('/')
                        val stem = parts.getOrNull(3)?.dropLast(GROUND_SUFFIX.length)
                        if (parts.size != 4 || stem == null || !isTileKey(stem)) {
                            throw JbArchiveException("entry \"$name\" is a media ground path, but not layers/<layer>/<cel>/<tx>_<ty>$GROUND_SUFFIX")
                        }
                        val bytes = readBounded(zis, entry, TILE_BYTES + 1, budget)
                        if (bytes.size != TILE_BYTES) {
                            throw JbArchiveException("media ground tile \"$name\" is ${bytes.size} bytes, a ground tile is $TILE_BYTES")
                        }
                        media[MediaTileKey(parts[1], parts[2], stem, MediaStores.GROUND)] = bytes
                    }

                    name.startsWith("$LAYERS_DIR/") && name.endsWith(LOOK_SUFFIX) -> {
                        val parts = name.split('/')
                        val stem = parts.getOrNull(3)?.dropLast(LOOK_SUFFIX.length)
                        if (parts.size != 4 || stem == null || !isTileKey(stem)) {
                            throw JbArchiveException("entry \"$name\" is a look path, but not layers/<layer>/<cel>/<tx>_<ty>$LOOK_SUFFIX")
                        }
                        val bytes = readBounded(zis, entry, TILE_BYTES + 1, budget)
                        if (bytes.size != TILE_BYTES) {
                            throw JbArchiveException("look \"$name\" is ${bytes.size} bytes, a look tile is $TILE_BYTES")
                        }
                        looks[Triple(parts[1], parts[2], stem)] = bytes
                    }

                    name.startsWith("$LAYERS_DIR/") && name.endsWith(TILE_EXT) && SLAB_STEM.matches(name.substringAfterLast('/').dropLast(TILE_EXT.length)) -> {
                        val parts = name.split('/')
                        val m = SLAB_STEM.matchEntire(parts.last().dropLast(TILE_EXT.length))!!
                        if (parts.size != 4) {
                            throw JbArchiveException("entry \"$name\" is a slab path, but not layers/<layer>/<cel>/<tx>_<ty>.s<seq>.rgba")
                        }
                        if (!isTileKey(m.groupValues[1])) throw JbArchiveException("entry \"$name\" is a slab, but \"${m.groupValues[1]}\" is not a \"tx_ty\" tile key")
                        val seq = m.groupValues[2].toLongOrNull() ?: throw JbArchiveException(
                            "entry \"$name\" has a slab sequence outside the supported range"
                        )
                        val bytes = readBounded(zis, entry, TILE_BYTES + 1, budget)
                        if (bytes.size != TILE_BYTES) {
                            throw JbArchiveException("slab \"$name\" is ${bytes.size} bytes, a slab is $TILE_BYTES")
                        }
                        val key = SlabKey(parts[1], parts[2], m.groupValues[1], seq)
                        if (slabs.put(key, bytes) != null) throw JbArchiveException("this archive keeps slab \"$key\" twice")
                    }

                    name.startsWith("$BRUSHES_DIR/") && name.endsWith(BRUSH_EXT) -> {
                        if (name.split('/').size != 2) {
                            throw JbArchiveException("entry \"$name\" is a brush path, but not brushes/<name>$BRUSH_EXT")
                        }
                        val bytes = readBounded(zis, entry, MAX_BRUSH_BYTES, budget)
                        val preset = try {
                            BrushJson.decodeChecked(bytes.toString(Charsets.UTF_8))
                        } catch (e: BrushException) {
                            throw JbArchiveException("the frozen brush \"$name\" cannot be read: ${brief(e)}")
                        }
                        if (brushes.put(preset.id, preset) != null) {
                            throw JbArchiveException("this archive keeps brush \"${preset.id}\" twice")
                        }
                    }

                    name.startsWith("$LAYERS_DIR/") && name.endsWith(TILE_EXT) -> {
                        val parts = name.split('/')
                        if (parts.size != 4) {
                            throw JbArchiveException("entry \"$name\" is a tile path, but not layers/<layer>/<cel>/<tx>_<ty>.rgba")
                        }
                        val key = parts[3].dropLast(TILE_EXT.length)
                        val xy = key.split('_', limit = 2)
                        if (xy.size != 2 || xy[0].toIntOrNull() == null || xy[1].toIntOrNull() == null) {
                            throw JbArchiveException("entry \"$name\" is a tile, but \"$key\" is not a \"tx_ty\" tile key")
                        }
                        // TILE_BYTES + 1, not TILE_BYTES: a tile one byte too long is a wrong-size
                        // tile and says so, rather than an anonymous overflow.
                        val bytes = readBounded(zis, entry, TILE_BYTES + 1, budget)
                        if (bytes.size != TILE_BYTES) {
                            throw JbArchiveException("tile \"$name\" is ${bytes.size} bytes, a paint tile is $TILE_BYTES")
                        }
                        tiles[Triple(parts[1], parts[2], key)] = bytes
                    }

                    name.startsWith("$LAYERS_DIR/") && name.endsWith("/$STROKES_NAME") -> {
                        val parts = name.split('/')
                        if (parts.size != 4) {
                            throw JbArchiveException("entry \"$name\" is a strokes path, but not layers/<layer>/<cel>/strokes.jbs")
                        }
                        val bytes = readBounded(zis, entry, MAX_STROKES_BYTES, budget)
                        strokes[Pair(parts[1], parts[2])] = decodeStrokes(bytes, name)
                    }

                    else -> {
                        // Forward compatibility: an entry this build does not know is skipped, and
                        // it is still drained so the reader can reach the next one.
                        drainBounded(zis, entry, MAX_IGNORED_BYTES, budget)
                    }
                }
                zis.closeEntry()
            }
        } catch (e: IOException) {
            // Real: ZipInputStream throws ZipException or EOFException on a truncated or mangled
            // archive, and this is the path where a half-copied file is read.
            throw JbArchiveException("this archive cannot be read: ${brief(e)}")
        } catch (e: RuntimeException) {
            // Real, and not paranoia: everything from here on is JDK code being handed hostile
            // bytes. See the promise on [read].
            throw JbArchiveException("this archive cannot be read: ${e.javaClass.simpleName}: ${brief(e)}")
        }

        if (!mimetypeSeen) throw JbArchiveException("this archive has no \"$MIMETYPE_NAME\" entry")
        val docBytes = document ?: throw JbArchiveException("this archive has no \"$DOCUMENT_NAME\" entry")
        return documentFrom(docBytes, tiles, strokes, thumbnail, media, slabs, looks, brushes, legacyBrushes)
    }

    /**
     * The second half of [read]: is what arrived a document at all, and does what arrived agree with
     * what the document says it should be? Split out so the whole of it can sit behind one catch,
     * because the json decoder and the validators are the other place untrusted bytes reach code that
     * was written assuming a well-formed document.
     */
    private fun documentFrom(
        docBytes: ByteArray,
        tiles: Map<Triple<String, String, String>, ByteArray>,
        strokes: Map<Pair<String, String>, List<StrokeRecord>>,
        thumbnail: ByteArray?,
        media: Map<MediaTileKey, ByteArray> = emptyMap(),
        slabs: Map<SlabKey, ByteArray> = emptyMap(),
        looks: Map<Triple<String, String, String>, ByteArray> = emptyMap(),
        brushes: Map<String, BrushPreset> = emptyMap(),
        legacyBrushes: ((String) -> BrushPreset?)? = null,
    ): JbContents = try {
        val text = docBytes.toString(Charsets.UTF_8)
        var doc = try {
            DocJson.decode(text)
        } catch (e: DocException) {
            // DocJson.decode states the problem in words. This only says which file it was reading.
            throw JbArchiveException("\"$DOCUMENT_NAME\" cannot be read: ${e.message ?: "it is not JSON the document shape allows"}")
        }

        val problems = DocOps.validate(doc)
        if (problems.isNotEmpty()) {
            throw JbArchiveException("the document in this archive is not valid: " + problems.joinToString("; "))
        }

        val declared = declaredTiles(doc)
        for (l in doc.layers) for (c in DocOps.storedCels(l)) {
            for (key in c.tiles) {
                if (Triple(l.id, c.id, key) !in tiles) {
                    throw JbArchiveException(
                        "the document lists tile \"$key\" of layer \"${l.id}\" cel \"${c.id}\", and this archive does not have it"
                    )
                }
            }
            if (c.strokesFile != null && Pair(l.id, c.id) !in strokes) {
                throw JbArchiveException(
                    "cel \"${c.id}\" of layer \"${l.id}\" says its strokes are in \"${c.strokesFile}\", and this archive does not have them"
                )
            }
        }
        for (key in tiles.keys) {
            if (key.third !in declared[Pair(key.first, key.second)].orEmpty()) {
                throw JbArchiveException(
                    "this archive has tile \"${key.third}\" for cel \"${key.second}\" of layer \"${key.first}\", which the document does not list"
                )
            }
        }
        for (cel in strokes.keys) {
            if (!declared.containsKey(cel)) {
                throw JbArchiveException(
                    "this archive has strokes for cel \"${cel.second}\" of layer \"${cel.first}\", which the document does not have"
                )
            }
        }

        val mediaDeclared = declaredMediaTiles(doc)
        for ((key, bytes) in media) checkMediaTile(key, bytes.size, mediaDeclared)
        val present = media.keys.mapTo(HashSet()) { Triple(it.layerId, it.celId, it.key) }
        for ((cel, keys) in mediaDeclared) for (key in keys) {
            if (Triple(cel.first, cel.second, key) !in present) {
                throw JbArchiveException(
                    "the document lists media tile \"$key\" of layer \"${cel.first}\" cel \"${cel.second}\", and this archive has no state for it"
                )
            }
        }

        // Slabs and looks (v10), both directions.
        val slabsDeclared = declaredSlabs(doc)
        for ((key, bytes) in slabs) checkSlab(key, bytes.size, slabsDeclared)
        for ((cel, listed) in slabsDeclared) for ((key, seq) in listed) {
            if (SlabKey(cel.first, cel.second, key, seq) !in slabs) {
                throw JbArchiveException(
                    "the document lists a slab at seq $seq on tile \"$key\" of layer \"${cel.first}\" cel \"${cel.second}\", and this archive does not have it"
                )
            }
        }
        val looksDeclared = declaredLooks(doc)
        for ((key, bytes) in looks) checkLook(key, bytes.size, looksDeclared)
        for ((cel, keys) in looksDeclared) for (key in keys) {
            if (Triple(cel.first, cel.second, key) !in looks) {
                throw JbArchiveException(
                    "the document lists a look for tile \"$key\" of layer \"${cel.first}\" cel \"${cel.second}\", and this archive does not have it"
                )
            }
        }

        // The brushes the lines use (D5). A drawing older than version 10 has none frozen: fill them from the library.
        var frozen = brushes
        if ((DocJson.fileVersion(text) ?: DOC_VERSION) < 10) {
            val used = strokes.values.flatten().map { it.brushId }.toSortedSet()
            if (used.isNotEmpty()) {
                val copies = LinkedHashMap<String, BrushPreset>()
                for (id in used) {
                    val copy = legacyBrushes?.invoke(id) ?: throw JbArchiveException(
                        "this older drawing has lines drawn with brush \"$id\", which this version of Joy Brush does not have, " +
                            "so it cannot keep them as editable lines yet"
                    )
                    if (copy.id != id) throw JbArchiveException("the legacy copy requested as \"$id\" says it is brush \"${copy.id}\"")
                    val brushProblems = BrushValidate.validate(copy)
                    if (brushProblems.isNotEmpty()) throw JbArchiveException("the legacy brush \"$id\" cannot be kept with the drawing: ${brushProblems.joinToString("; ")}")
                    // A resolver is an import boundary, like brushes/ JSON. Validate the supplied frozen definition,
                    // so opening cannot succeed only for its next save to fail or silently repair the brush.
                    try { BrushJson.encode(copy) } catch (e: BrushException) {
                        throw JbArchiveException("the legacy brush \"$id\" cannot be kept with the drawing: ${brief(e)}")
                    }
                    copies[id] = copy
                }
                frozen = copies
                // Frozen brush migration introduced v10, not a historical physical capture domain.
                // Raising an old regional source to v11 here would wrongly refuse a valid read.
                doc = doc.copy(version = maxOf(10, doc.version), brushes = used.toList())
            }
        }
        if (frozen.keys != doc.brushes.toSet()) {
            val missing = doc.brushes.filter { it !in frozen }
            val extra = frozen.keys.filter { it !in doc.brushes }
            throw JbArchiveException(
                (if (missing.isNotEmpty()) "the document lists brush ${missing.joinToString { "\"$it\"" }}, and this archive has no copy of it. " else "") +
                    (if (extra.isNotEmpty()) "this archive keeps a copy of brush ${extra.joinToString { "\"$it\"" }}, which the document does not list" else "")
            )
        }

        // The records themselves. Opening never replays a line (D3), so the look check here is the cheap one.
        val lineProblems = lineProblems(doc, strokes, doc.brushes.toSet(), exactLooks = null)
        if (lineProblems.isNotEmpty()) {
            throw JbArchiveException("the lines in this archive are not valid: " + lineProblems.joinToString("; "))
        }

        // In time order for whoever holds them (the file already is, from this build).
        val ordered = strokes.mapValues { (_, v) -> v.sortedBy { it.seq } }
        JbContents(doc, tiles, ordered, thumbnail, media, slabs, looks, frozen)
    } catch (e: JbArchiveException) {
        throw e
    } catch (e: RuntimeException) {
        throw JbArchiveException("this archive cannot be read: ${e.javaClass.simpleName}: ${brief(e)}")
    }

    /** Reads a `.joybrush` off disk. The file is closed before this returns. */
    fun open(file: File, legacyBrushes: ((String) -> BrushPreset?)? = null): JbContents {
        if (!file.isFile) throw JbArchiveException("there is no file at ${file.path}")
        // Wrapped for the same promise [read] makes: a caller that says "this file is damaged" in
        // one catch must not meet a FileNotFoundException from underneath it.
        return try {
            FileInputStream(file).use { read(it, legacyBrushes) }
        } catch (e: JbArchiveException) {
            throw e
        } catch (e: Exception) {
            throw JbArchiveException("${file.name} cannot be opened: ${brief(e)}")
        }
    }

    // ---------------------------------------------------------------- writing

    private fun writeMimetype(zos: ZipOutputStream) {
        val bytes = JB_MIMETYPE.toByteArray(Charsets.US_ASCII)
        val entry = ZipEntry(MIMETYPE_NAME)
        // A STORED entry needs its size and CRC stated up front: there is no deflate stream to
        // measure afterwards, and ZipOutputStream writes the central directory from these.
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = CRC32().apply { update(bytes) }.value
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    /** celId to the tile keys its [cc.joycreator.joybrush.core.doc.Cel] lists. */
    private fun declaredTiles(doc: JbDocument): Map<Pair<String, String>, List<String>> {
        val out = HashMap<Pair<String, String>, List<String>>()
        for (l in doc.layers) for (c in DocOps.storedCels(l)) out[Pair(l.id, c.id)] = c.tiles
        return out
    }

    private fun checkTile(key: Triple<String, String, String>, bytes: ByteArray, declared: Map<Pair<String, String>, List<String>>) {
        val keys = declared[Pair(key.first, key.second)]
        if (keys == null) {
            throw JbArchiveException(
                "tile \"${key.third}\" is in layer \"${key.first}\" cel \"${key.second}\", and this document has no such cel"
            )
        }
        if (key.third !in keys) {
            throw JbArchiveException("tile \"${key.third}\" is in layer \"${key.first}\" cel \"${key.second}\", which does not list it")
        }
        if (!isTileKey(key.third)) {
            throw JbArchiveException("tile key \"${key.third}\" of cel \"${key.second}\" is not \"tx_ty\"")
        }
        if (bytes.size != TILE_BYTES) {
            throw JbArchiveException(
                "tile \"${key.third}\" of layer \"${key.first}\" cel \"${key.second}\" is ${bytes.size} bytes, a paint tile is $TILE_BYTES"
            )
        }
    }

    /** (layerId, celId) to the media tile keys its cel lists ([cc.joycreator.joybrush.core.doc.Cel.floatTiles]). */
    private fun declaredMediaTiles(doc: JbDocument): Map<Pair<String, String>, List<String>> {
        val out = HashMap<Pair<String, String>, List<String>>()
        for (l in doc.layers) for (c in l.cels) if (c.floatTiles.isNotEmpty()) out[Pair(l.id, c.id)] = c.floatTiles
        return out
    }

    /**
     * A media store tile is listed by its cel's floatTiles, names a store the engine has, and is the right size. Which
     * stores a listed tile has is the medium's business (they are made lazily): at least one is required, not all.
     */
    private fun checkMediaTile(key: MediaTileKey, size: Int, declared: Map<Pair<String, String>, List<String>>) {
        val keys = declared[Pair(key.layerId, key.celId)]
            ?: throw JbArchiveException("media tile \"${key.key}\" is in layer \"${key.layerId}\" cel \"${key.celId}\", which lists no media state")
        if (key.key !in keys) {
            throw JbArchiveException("media tile \"${key.key}\" (${key.store}) is in layer \"${key.layerId}\" cel \"${key.celId}\", which does not list it")
        }
        if (key.store !in MediaStores.PAYLOAD) throw JbArchiveException("media store \"${key.store}\" of tile \"${key.key}\" is not one of ${MediaStores.PAYLOAD.joinToString(", ")}")
        if (!isTileKey(key.key)) throw JbArchiveException("media tile key \"${key.key}\" of cel \"${key.celId}\" is not \"tx_ty\"")
        val want = MediaStores.tileBytes(TILE_SIZE, key.store)
        if (size != want) {
            throw JbArchiveException("media tile \"${key.key}\" (${key.store}) of layer \"${key.layerId}\" is $size bytes, a ${key.store} tile is $want")
        }
    }

    /** (layerId, celId) to the (tile key, seq) of every slab ABOVE a tile's bottom one that the cel lists (v10). */
    private fun declaredSlabs(doc: JbDocument): Map<Pair<String, String>, List<Pair<String, Long>>> {
        val out = LinkedHashMap<Pair<String, String>, List<Pair<String, Long>>>()
        for (l in doc.layers) for (c in l.cels) {
            val upper = CelLayout.stacks(c).flatMap { (key, stack) -> stack.drop(1).map { key to it.seq } }
            if (upper.isNotEmpty()) out[Pair(l.id, c.id)] = upper
        }
        return out
    }

    /** (layerId, celId) to the tile keys whose look the cel lists (v10). */
    private fun declaredLooks(doc: JbDocument): Map<Pair<String, String>, List<String>> {
        val out = LinkedHashMap<Pair<String, String>, List<String>>()
        for (l in doc.layers) for (c in l.cels) if (c.looks.isNotEmpty()) out[Pair(l.id, c.id)] = c.looks
        return out
    }

    private fun checkSlab(key: SlabKey, size: Int, declared: Map<Pair<String, String>, List<Pair<String, Long>>>) {
        val listed = declared[Pair(key.layerId, key.celId)]
        if (listed == null || (key.key to key.seq) !in listed) {
            throw JbArchiveException(
                "slab at seq ${key.seq} on tile \"${key.key}\" is in layer \"${key.layerId}\" cel \"${key.celId}\", which does not list it"
            )
        }
        if (!isTileKey(key.key)) throw JbArchiveException("slab key \"${key.key}\" of cel \"${key.celId}\" is not \"tx_ty\"")
        if (size != TILE_BYTES) {
            throw JbArchiveException("slab at seq ${key.seq} on tile \"${key.key}\" of layer \"${key.layerId}\" is $size bytes, a slab is $TILE_BYTES")
        }
    }

    private fun checkLook(key: Triple<String, String, String>, size: Int, declared: Map<Pair<String, String>, List<String>>) {
        val keys = declared[Pair(key.first, key.second)]
        if (keys == null || key.third !in keys) {
            throw JbArchiveException("look for tile \"${key.third}\" is in layer \"${key.first}\" cel \"${key.second}\", which does not list it")
        }
        if (!isTileKey(key.third)) throw JbArchiveException("look key \"${key.third}\" of cel \"${key.second}\" is not \"tx_ty\"")
        if (size != TILE_BYTES) {
            throw JbArchiveException("look for tile \"${key.third}\" of layer \"${key.first}\" cel \"${key.second}\" is $size bytes, a look is $TILE_BYTES")
        }
    }

    /**
     * The rules that need the line records (v10). [exactLooks], when given, resolves a frozen brush so the look check can
     * replay and ask which tiles lines really cross; null = the cheap checks only (what opening does).
     */
    private fun lineProblems(
        doc: JbDocument,
        strokes: Map<Pair<String, String>, List<StrokeRecord>>,
        frozenIds: Set<String>,
        exactLooks: ((String) -> BrushPreset?)?,
    ): List<String> {
        val out = ArrayList<String>()
        for (l in doc.layers) for (c in DocOps.storedCels(l)) {
            val records = strokes[Pair(l.id, c.id)] ?: emptyList()
            if (records.isEmpty() && c.looks.isEmpty()) continue
            out += LineRules.problems(l.id, c, records, frozenIds)
            out += LineRules.sourceProblems(doc.version, l, c, records)
            if (exactLooks != null) out += LineRules.lookProblems(l.id, c, records, exactLooks)
        }
        return out
    }

    private fun slabPath(key: SlabKey): String =
        safeName("$LAYERS_DIR/${key.layerId}/${key.celId}/${key.key}.s${key.seq}$TILE_EXT")

    private fun lookPath(key: Triple<String, String, String>): String =
        safeName("$LAYERS_DIR/${key.first}/${key.second}/${key.third}$LOOK_SUFFIX")

    /** `brushes/<n>-<name>.json`: a brush id is free text (an import's can hold anything), so the file name is a hint only. */
    private fun brushPath(index: Int, id: String): String {
        val hint = id.map { if (it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("").trim('.').take(40).ifEmpty { "brush" }
        return safeName("$BRUSHES_DIR/${(index + 1).toString().padStart(3, '0')}-$hint$BRUSH_EXT")
    }

    private fun mediaPath(key: MediaTileKey): String =
        if (key.store == MediaStores.GROUND) safeName("$LAYERS_DIR/${key.layerId}/${key.celId}/${key.key}$GROUND_SUFFIX")
        else safeName("$LAYERS_DIR/${key.layerId}/${key.celId}/${key.key}.${key.store}${MediaStores.EXT}")

    private fun tilePath(key: Triple<String, String, String>): String =
        safeName("$LAYERS_DIR/${key.first}/${key.second}/${key.third}$TILE_EXT")

    private fun strokesPath(cel: Pair<String, String>): String =
        safeName("$LAYERS_DIR/${cel.first}/${cel.second}/$STROKES_NAME")

    /** `"3_-2"` is a tile key, `"3"` and `"3_4_5"` are not. Signed, because the canvas is unbounded. */
    private fun isTileKey(key: String): Boolean {
        val xy = key.split('_', limit = 2)
        return xy.size == 2 && xy[0].toIntOrNull() != null && xy[1].toIntOrNull() != null
    }

    private fun safeName(name: String): String {
        val why = unsafeReason(name)
        if (why != null) throw JbArchiveException("entry name \"$name\" is refused: $why")
        return name
    }

    // ---------------------------------------------------------------- reading

    /**
     * Why this entry name must not be written anywhere, or null if it is ordinary.
     *
     * The order is cheapest test first, but the point is that ALL of these are refused, on the way
     * in and on the way out. A name that climbs out of the folder on Linux climbs out of it on
     * Windows too, one `..` at a time.
     */
    private fun unsafeReason(name: String): String? {
        if (name.isEmpty()) return "it has no name"
        if (name.length > MAX_NAME_CHARS) return "its name is ${name.length} characters long"
        for (ch in name) {
            if (ch.code < 0x20 || ch.code == 0x7F) return "its name contains a control character"
        }
        if (name.contains('\\')) return "its name contains a backslash, which is a path separator on Windows"
        if (name.startsWith("/")) return "its name is an absolute path"
        if (name.contains(":")) return "its name contains a colon, which is a drive letter or an NTFS stream"
        // One trailing slash is a directory marker, which is harmless and does appear in real zips.
        val path = if (name.endsWith("/")) name.dropLast(1) else name
        if (path.isEmpty()) return "its name is nothing but a separator"
        for (segment in path.split('/')) {
            if (segment.isEmpty()) return "its name has an empty path segment"
            if (segment == "." || segment == "..") return "its name has a \"$segment\" segment, which climbs out of the folder"
            // Windows throws trailing dots and spaces off a path component before it looks at it, so
            // a segment written ".. " or ".." is the parent folder again by the time it is opened.
            // This is the one zip-slip trick that gets past a check for `..` written plainly.
            val trimmed = segment.trimEnd(' ', '.').toString()
            if (trimmed != segment && (trimmed.isEmpty() || trimmed == "." || trimmed == "..")) {
                return "its name has a \"$segment\" segment, which Windows would read as \"$trimmed\""
            }
        }
        return null
    }

    /**
     * Reads one entry into memory, bounded by [limit] and by nothing whatever the archive claims.
     *
     * The buffer grows as it reads and is thrown away the moment the total passes [limit], so the
     * largest array this can allocate is `limit + CHUNK` — a number chosen here. A declared size
     * larger than [limit] is refused before a byte is read.
     *
     * A declared size that DISAGREES with what arrived is refused as well, but only when the size is
     * one the JDK believes: the test is `declared > 0`, never `declared >= 0`. A zip that ends its
     * entries with a data descriptor leaves zeros in the local header, and ZipInputStream does not
     * reliably fill them back in from the descriptor — so a zero there means "I did not write the
     * size here", not "this entry is empty". Comparing against it would refuse every deflated entry
     * in every archive, including the ones [write] produces. `-1` is the other "unknown", and `> 0`
     * rules that out too.
     */
    private fun readBounded(source: InputStream, entry: ZipEntry, limit: Int, budget: ByteBudget): ByteArray {
        val declared = entry.size
        if (declared >= 0 && declared > limit) {
            throw JbArchiveException("entry \"${entry.name}\" says it is $declared bytes, and only $limit are allowed")
        }
        val out = ByteArrayOutputStream(if (limit <= CHUNK) limit else CHUNK)
        val chunk = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            val n = source.read(chunk)
            if (n < 0) break
            total += n
            if (total > limit) {
                throw JbArchiveException("entry \"${entry.name}\" is longer than the $limit bytes allowed for it")
            }
            budget.spend(n)
            out.write(chunk, 0, n)
        }
        // `declared > 0`, not `>= 0`. See [readBounded]: a zero in the local header is the zip
        // saying it wrote the real numbers somewhere else, not saying the entry is empty.
        if (declared > 0 && declared != total) {
            throw JbArchiveException("entry \"${entry.name}\" says it is $declared bytes but it holds $total")
        }
        return out.toByteArray()
    }

    /** Reads an unknown entry past without keeping it, still bounded. */
    private fun drainBounded(source: InputStream, entry: ZipEntry, limit: Int, budget: ByteBudget) {
        val declared = entry.size
        val chunk = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            val n = source.read(chunk)
            if (n < 0) break
            total += n
            if (total > limit) {
                throw JbArchiveException("entry \"${entry.name}\" is longer than the $limit bytes allowed for an ignored entry")
            }
            budget.spend(n)
        }
        // `declared > 0`, not `>= 0`. See [readBounded].
        if (declared > 0 && declared != total) {
            throw JbArchiveException("entry \"${entry.name}\" says it is $declared bytes but it holds $total")
        }
    }

    /**
     * `decodeAll` reads a count and a length out of the bytes themselves and throws from several
     * places for several kinds of damage, so catching here is real rather than decorative.
     */
    private fun decodeStrokes(bytes: ByteArray, name: String): List<StrokeRecord> = try {
        StrokeCodec.decodeAll(bytes)
    } catch (e: Exception) {
        throw JbArchiveException("\"$name\" cannot be read as strokes: ${brief(e)}")
    }

    /** Stops a whole archive costing more memory than it is allowed to, however it is arranged. */
    private class ByteBudget(private val total: Long) {
        private var used = 0L

        fun spend(n: Int) {
            used += n
            if (used > total) throw JbArchiveException("this archive expands to more than $total bytes")
        }
    }

    /** A one-line reason, for an exception that has just crossed into this file's own error type. */
    private fun brief(e: Throwable): String {
        val message = e.message ?: e.javaClass.simpleName
        return if (message.length > 200) message.take(200) + "..." else message
    }
}
