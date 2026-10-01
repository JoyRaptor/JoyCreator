package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.DocJson
import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.TILE_SIZE
import cc.joycreator.joybrush.core.stroke.StrokeCodec
import cc.joycreator.joybrush.core.stroke.StrokeRecord
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
    /** (layerId, celId) to the stroke records of that INK cel. */
    val strokes: Map<Pair<String, String>, List<StrokeRecord>>,
    val thumbnailPng: ByteArray? = null,
)

/**
 * The `.joybrush` file (JB-0.08a): a zip holding one document's everything.
 *
 * | Entry | Content | Compression |
 * |---|---|---|
 * | `mimetype` | [JB_MIMETYPE] | STORED, and the FIRST entry, like OpenRaster |
 * | `document.json` | `DocJson.encode(doc)` | deflate |
 * | `layers/<layerId>/<celId>/<tx>_<ty>.rgba` | one PAINT tile, [TILE_BYTES] bytes | deflate |
 * | `layers/<layerId>/<celId>/strokes.jbs` | `StrokeCodec.encodeAll` of an INK cel | deflate |
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
    private const val MAX_IGNORED_BYTES = 64 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    private const val CHUNK = 8 * 1024

    private val TILE_ORDER = compareBy<Triple<String, String, String>>({ it.first }, { it.second }, { it.third })
    private val CEL_ORDER = compareBy<Pair<String, String>>({ it.first }, { it.second })

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
     * Writes the archive to [out], which is NOT closed — `finish()` is called and the stream is
     * flushed, so the caller decides when it ends.
     *
     * Everything is checked before the first byte is written, so a refused archive leaves [out] as
     * it was found instead of half a file long.
     */
    fun write(out: OutputStream, contents: JbContents) {
        val doc = contents.doc
        val problems = DocOps.validate(doc)
        if (problems.isNotEmpty()) {
            throw JbArchiveException("this document cannot be saved: " + problems.joinToString("; "))
        }

        val declared = declaredTiles(doc)

        val tileEntries = ArrayList<Pair<String, ByteArray>>()
        for (key in contents.tiles.keys.sortedWith(TILE_ORDER)) {
            val bytes = contents.tiles.getValue(key)
            checkTile(key, bytes, declared)
            tileEntries.add(tilePath(key) to bytes)
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

        val strokeEntries = ArrayList<Pair<String, ByteArray>>()
        for (cel in contents.strokes.keys.sortedWith(CEL_ORDER)) {
            if (declared[cel] == null) {
                throw JbArchiveException(
                    "strokes are given for layer \"${cel.first}\" cel \"${cel.second}\", and this document has no such cel"
                )
            }
            strokeEntries.add(strokesPath(cel) to StrokeCodec.encodeAll(contents.strokes.getValue(cel)))
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

        // STORED, because a reader can identify the file by the first bytes without inflating, and
        // because that is what OpenRaster does and what the reader expects.
        val zos = ZipOutputStream(out)
        zos.setLevel(ARCHIVE_DEFLATE_LEVEL)
        try {
            writeMimetype(zos)

            zos.putNextEntry(ZipEntry(DOCUMENT_NAME))
            zos.write(DocJson.encode(doc).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            for (entry in tileEntries) {
                zos.putNextEntry(ZipEntry(entry.first))
                zos.write(entry.second)
                zos.closeEntry()
            }
            for (entry in strokeEntries) {
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
     */
    fun read(input: InputStream): JbContents {
        val zis = ZipInputStream(input)
        val names = HashSet<String>()
        val budget = ByteBudget(MAX_TOTAL_BYTES)
        var mimetypeSeen = false
        var document: ByteArray? = null
        var thumbnail: ByteArray? = null
        val tiles = LinkedHashMap<Triple<String, String, String>, ByteArray>()
        val strokes = LinkedHashMap<Pair<String, String>, List<StrokeRecord>>()
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
        return documentFrom(docBytes, tiles, strokes, thumbnail)
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
    ): JbContents = try {
        val doc = try {
            DocJson.decode(docBytes.toString(Charsets.UTF_8))
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

        JbContents(doc, tiles, strokes, thumbnail)
    } catch (e: JbArchiveException) {
        throw e
    } catch (e: RuntimeException) {
        throw JbArchiveException("this archive cannot be read: ${e.javaClass.simpleName}: ${brief(e)}")
    }

    /** Reads a `.joybrush` off disk. The file is closed before this returns. */
    fun open(file: File): JbContents {
        if (!file.isFile) throw JbArchiveException("there is no file at ${file.path}")
        // Wrapped for the same promise [read] makes: a caller that says "this file is damaged" in
        // one catch must not meet a FileNotFoundException from underneath it.
        return try {
            FileInputStream(file).use { read(it) }
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
