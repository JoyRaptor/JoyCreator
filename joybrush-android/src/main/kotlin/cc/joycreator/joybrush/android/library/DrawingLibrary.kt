package cc.joycreator.joybrush.android.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.hasPixels
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.render.TileSource
import com.fadcam.joyfolder.FileKind
import com.fadcam.joyfolder.JoyFolder
import com.fadcam.joyfolder.Placement
import com.fadcam.joyfolder.ReadableNames
import com.fadcam.joyfolder.android.JoyFolderHome
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Many drawings, each with a picture of itself, each kept in the Joy Creator folder.
 *
 * The owner, 2026-10-14: the lobby "doesn't show any previews of your most recent work, or the ability to organize or
 * navigate files or even create new ones." Before this, JoyBrush had exactly one drawing (`current.joybrush`) and "New
 * drawing" reopened it.
 *
 * HOW IT FITS WITHOUT TOUCHING THE ENGINE. The drawing screen still has one working file per run; the library only
 * decides WHICH file that is ([fileToOpen], from the lobby's intent) and hears about each successful save ([onSaved]).
 * Saving, recovery, backups and history are the drawing screen's own and are unchanged.
 *
 * WHERE THINGS LIVE.
 *  - The app's working copies: `<external files>/joybrush/` (`current.joybrush` is the first drawing; new ones are
 *    `drawing-<time>.joybrush`). Fast, private, and gone on uninstall, exactly like Studio's.
 *  - The person's copy: `<Joy Creator folder>/<Name>.joybrush`, written a moment after each save (the Studio mirror's
 *    design). Moved or renamed by the person? Found again by the drawing's id inside the file.
 *  - Names, ids and where each one went: `library.json` beside the working copies. Thumbnails: `thumbs/<file>.png`.
 *
 * Deleting goes to the one Trash (the folder's), and a drawing put back from Trash, or found in the folder after a
 * reinstall, comes back into the list on the next [syncFromFolder].
 */
object DrawingLibrary {

    private const val TAG = "DrawingLibrary"
    const val EXTRA_DRAWING = "joybrush_drawing_file"
    private const val LEGACY_FILE = "current.joybrush"
    private const val INDEX = "library.json"
    private const val THUMBS = "thumbs"
    private const val THUMB_SIDE = 768
    /** A thumbnail is redrawn at most this often; a save in between keeps the old picture. */
    private const val THUMB_EVERY_MS = 45_000L

    class Drawing(
        /** The working copy's file name, the key of everything here. */
        @JvmField val file: String,
        @JvmField val name: String,
        @JvmField val modified: Long,
        /** Absolute path of its thumbnail, or null when it has none yet. */
        @JvmField val thumbPath: String?,
    )

    private val lock = Any()

    /**
     * Told when a drawing's picture or list entry changed (a save lands a moment after the lobby has
     * already come back). Called on a background thread; the listener posts to its own UI.
     */
    @JvmStatic
    @Volatile
    var onChange: Runnable? = null

    @JvmStatic
    fun dir(context: Context): File? = context.getExternalFilesDir("joybrush")?.also { if (!it.isDirectory) it.mkdirs() }

    // ── list / create / rename / delete (any thread; delete and rename touch the folder: not the main thread) ──

    /** Every drawing, most recently changed first. */
    @JvmStatic
    fun list(context: Context): List<Drawing> {
        val dir = dir(context) ?: return emptyList()
        synchronized(lock) {
            val index = readIndex(dir)
            val entries = index.optJSONObject("drawings") ?: JSONObject()
            // The original drawing is part of the library from the start, named after itself.
            val legacy = File(dir, LEGACY_FILE)
            if (legacy.isFile && !entries.has(LEGACY_FILE)) {
                entries.put(LEGACY_FILE, JSONObject().put("name", "My drawing").put("created", legacy.lastModified()))
                index.put("drawings", entries)
                writeIndex(dir, index)
            }
            val out = ArrayList<Drawing>()
            for (key in entries.keys()) {
                val f = File(dir, key)
                val e = entries.getJSONObject(key)
                // A drawing made but never saved has no file yet; it still exists for the person.
                val modified = if (f.isFile) f.lastModified() else e.optLong("created", 0L)
                val thumb = File(File(dir, THUMBS), "$key.png")
                out.add(Drawing(key, e.optString("name", "Drawing"), modified, if (thumb.isFile) thumb.absolutePath else null))
            }
            out.sortByDescending { it.modified }
            return out
        }
    }

    /** A new, empty drawing ("Drawing 3"). Returns its file name, ready for [openIntent]. */
    @JvmStatic
    fun create(context: Context): String? {
        val dir = dir(context) ?: return null
        synchronized(lock) {
            val index = readIndex(dir)
            val entries = index.optJSONObject("drawings") ?: JSONObject()
            val taken = HashSet<String>()
            for (k in entries.keys()) taken.add(entries.getJSONObject(k).optString("name").lowercase())
            var n = entries.length() + 1
            while ("drawing $n" in taken) n++
            val now = System.currentTimeMillis()
            var file = "drawing-$now.joybrush"
            while (File(dir, file).exists() || entries.has(file)) file = "drawing-${now + 1}.joybrush"
            entries.put(file, JSONObject().put("name", "Drawing $n").put("created", now))
            index.put("drawings", entries)
            writeIndex(dir, index)
            return file
        }
    }

    /** The intent that opens [file] on the drawing screen. */
    @JvmStatic
    fun openIntent(context: Context, file: String): Intent =
        Intent(context, cc.joycreator.joybrush.android.JoyBrushActivity::class.java).putExtra(EXTRA_DRAWING, file)

    /** The drawing the lobby's "Carry on" opens: the one last opened, else the newest. */
    @JvmStatic
    fun lastOpened(context: Context): String? {
        val dir = dir(context) ?: return null
        val all = list(context)
        val last = synchronized(lock) { readIndex(dir).optString("lastOpened", "") }
        return all.firstOrNull { it.file == last }?.file ?: all.firstOrNull()?.file
    }

    /**
     * Rename: the list's name and the file in the person's folder ("Dino walk.joybrush"). The working copy keeps its
     * file name. Not on the main thread.
     */
    @JvmStatic
    fun rename(context: Context, file: String, newName: String): Boolean {
        val dir = dir(context) ?: return false
        val clean = ReadableNames.sanitize(newName.trim()).ifEmpty { return false }
        val folderPath: String?
        synchronized(lock) {
            val index = readIndex(dir)
            val e = index.optJSONObject("drawings")?.optJSONObject(file) ?: return false
            e.put("name", clean)
            folderPath = e.optString("folderPath", "").ifEmpty { null }
            writeIndex(dir, index)
        }
        if (folderPath != null) {
            try {
                val jf = JoyFolderHome.open(context)
                if (jf != null && jf.fs.stat(folderPath) != null) {
                    val wanted = jf.freeName(Placement.parentOf(folderPath), "$clean.joybrush")
                    if (jf.fs.rename(folderPath, wanted)) setFolderPath(dir, file, Placement.join(Placement.parentOf(folderPath), wanted))
                }
            } catch (e: Exception) {
                Log.w(TAG, "folder copy not renamed; the next save writes the new name", e)
            }
        }
        return true
    }

    /**
     * Delete: the folder's copy goes to the one Trash (restorable from Recently deleted), then the working copy and
     * its thumbnail go. Refused when the folder can't be reached, because then nothing could bring it back.
     * Not on the main thread. Returns null when done, else the reason in words.
     */
    @JvmStatic
    fun delete(context: Context, file: String): String? {
        val dir = dir(context) ?: return "this phone has no drawing folder"
        val working = File(dir, file)
        val jf = JoyFolderHome.open(context) ?: return "your Joy Creator folder can't be reached, so it couldn't be put back later"
        try {
            if (working.isFile) {
                // Make sure the folder has the latest before it goes to Trash.
                val rel = mirrorFile(context, jf, dir, file, null)
                if (rel != null) jf.trash(rel, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            return "it couldn't be moved to Trash (${e.message})"
        }
        synchronized(lock) {
            val index = readIndex(dir)
            index.optJSONObject("drawings")?.remove(file)
            if (index.optString("lastOpened") == file) index.remove("lastOpened")
            writeIndex(dir, index)
        }
        working.delete()
        File(working.path + ".bak").delete()
        File(File(dir, THUMBS), "$file.png").delete()
        return null
    }

    // ── the drawing screen's two hooks ──

    /**
     * Which working file the drawing screen uses for this run: the one the lobby asked for, else the last one opened,
     * else the original. Remembered as "last opened". Main thread is fine (no folder I/O).
     */
    @JvmStatic
    fun fileToOpen(context: Context, intent: Intent?): File? {
        val dir = dir(context) ?: return null
        val asked = intent?.getStringExtra(EXTRA_DRAWING)
        synchronized(lock) {
            val index = readIndex(dir)
            val entries = index.optJSONObject("drawings") ?: JSONObject()
            var name = asked?.takeIf { it.endsWith(".joybrush") && !it.contains('/') && !it.contains('\\') && entries.has(it) }
                ?: index.optString("lastOpened", "").takeIf { it.isNotEmpty() && entries.has(it) }
                ?: LEGACY_FILE
            if (name == LEGACY_FILE && !entries.has(LEGACY_FILE)) {
                entries.put(LEGACY_FILE, JSONObject().put("name", "My drawing").put("created", System.currentTimeMillis()))
                index.put("drawings", entries)
            }
            index.put("lastOpened", name)
            writeIndex(dir, index)
            return File(dir, name)
        }
    }

    /**
     * After a successful save of [file] (the drawing screen's writer thread): refresh the thumbnail now and then, and
     * have the person's folder copy follow. Never throws: a library problem must not look like a failed save.
     */
    @JvmStatic
    fun onSaved(context: Context, file: File, contents: JbContents) {
        val app = context.applicationContext
        val dir = file.parentFile ?: return
        try {
            synchronized(lock) {
                val index = readIndex(dir)
                val e = index.optJSONObject("drawings")?.optJSONObject(file.name)
                if (e != null) {
                    e.put("docId", contents.doc.id)
                    writeIndex(dir, index)
                }
            }
            val thumb = File(File(dir, THUMBS), "${file.name}.png")
            if (!thumb.isFile || System.currentTimeMillis() - thumb.lastModified() > THUMB_EVERY_MS) {
                thumbnailOf(contents)?.let { bmp ->
                    thumb.parentFile?.mkdirs()
                    val tmp = File(thumb.path + ".tmp")
                    FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                    if (!tmp.renameTo(thumb)) { thumb.delete(); tmp.renameTo(thumb) }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "thumbnail not refreshed for ${file.name}", t)
        }
        onChange?.run()
        if (!JoyFolderHome.isChosen(app)) return
        JoyFolderHome.io().execute {
            try {
                val jf = JoyFolderHome.open(app)
                if (jf == null) {
                    Log.w(TAG, "folder copy of ${file.name} not written: the Joy Creator folder can't be opened")
                    tellFolderCopyFailed(app, file.name)
                    return@execute
                }
                mirrorFile(app, jf, dir, file.name, contents.doc.id)
            } catch (e: Exception) {
                Log.w(TAG, "folder copy of ${file.name} not written; the next save tries again", e)
                tellFolderCopyFailed(app, file.name)
            }
        }
    }

    private val folderCopyNotice = FolderCopyNotice()

    /** Tells the person, once per drawing per session, that the copy in their Joy Creator folder was not made. Any thread. */
    private fun tellFolderCopyFailed(app: Context, file: String) {
        if (!folderCopyNotice.firstFailure(file)) return
        Handler(Looper.getMainLooper()).post { Toast.makeText(app, FolderCopyNotice.SENTENCE, Toast.LENGTH_LONG).show() }
    }

    /**
     * Bring in drawings that are in the person's folder but not in the list: put back from Trash, copied in by hand,
     * or the whole folder after a reinstall. Folder I/O: call on [JoyFolderHome.io]. Returns how many came in.
     */
    @JvmStatic
    fun syncFromFolder(context: Context): Int {
        val dir = dir(context) ?: return 0
        val jf = JoyFolderHome.open(context) ?: return 0
        jf.index.scan(jf.fs)
        val known = HashSet<String>()
        val knownPaths = HashSet<String>()
        synchronized(lock) {
            val entries = readIndex(dir).optJSONObject("drawings") ?: JSONObject()
            for (k in entries.keys()) {
                val e = entries.getJSONObject(k)
                e.optString("docId", "").takeIf { it.isNotEmpty() }?.let { known.add(it) }
                e.optString("folderPath", "").takeIf { it.isNotEmpty() }?.let { knownPaths.add(it) }
            }
        }
        var added = 0
        for (it in jf.index.items()) {
            if (FileKind.of(Placement.nameOf(it.path)) != FileKind.DRAWING || it.path in knownPaths) continue
            val id = docIdIn(jf, it.path) ?: continue
            if (id in known) continue
            val now = System.currentTimeMillis()
            val name = "drawing-$now-${added + 1}.joybrush"
            val target = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            jf.fs.open(it.path).use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
            if (!tmp.renameTo(target)) { tmp.delete(); continue }
            synchronized(lock) {
                val index = readIndex(dir)
                val entries = index.optJSONObject("drawings") ?: JSONObject()
                entries.put(name, JSONObject()
                    .put("name", FileKind.baseOf(Placement.nameOf(it.path)))
                    .put("created", now).put("docId", id).put("folderPath", it.path))
                index.put("drawings", entries)
                writeIndex(dir, index)
            }
            known.add(id)
            added++
            // A picture for it straight away, so the list isn't a row of blanks after a reinstall.
            try {
                val contents = JbArchive.open(target)
                thumbnailOf(contents)?.let { bmp ->
                    val thumb = File(File(dir, THUMBS), "$name.png")
                    thumb.parentFile?.mkdirs()
                    FileOutputStream(thumb).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    bmp.recycle()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "no thumbnail for restored ${it.path}", t)
            }
        }
        return added
    }

    /**
     * Draw the pictures drawings don't have yet (made before thumbnails existed). Reads each archive, so: a
     * background thread, one at a time, and a drawing too big for memory is skipped, never fatal. Returns how many.
     */
    @JvmStatic
    fun fillMissingThumbnails(context: Context): Int {
        val dir = dir(context) ?: return 0
        var made = 0
        for (d in list(context)) {
            if (d.thumbPath != null) continue
            val f = File(dir, d.file)
            if (!f.isFile) continue
            try {
                val bmp = thumbnailOf(JbArchive.open(f)) ?: continue
                val thumb = File(File(dir, THUMBS), "${d.file}.png")
                thumb.parentFile?.mkdirs()
                FileOutputStream(thumb).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                made++
            } catch (t: Throwable) {
                Log.w(TAG, "no thumbnail for ${d.file}", t)
            }
        }
        return made
    }

    // ── internals ──

    /**
     * Write the working copy [file] to its place in the folder. The place is remembered; a copy the person moved is
     * found again by the drawing's id; a new one goes where new work goes (the top), by its readable name.
     * Returns the folder path.
     */
    private fun mirrorFile(context: Context, jf: JoyFolder, dir: File, file: String, docId: String?): String? {
        val working = File(dir, file)
        if (!working.isFile) return null
        var name = "Drawing"
        var rel: String?
        var id = docId
        synchronized(lock) {
            val e = readIndex(dir).optJSONObject("drawings")?.optJSONObject(file) ?: return null
            name = e.optString("name", "Drawing")
            rel = e.optString("folderPath", "").ifEmpty { null }
            if (id == null) id = e.optString("docId", "").ifEmpty { null }
        }
        if (rel != null && jf.fs.stat(rel!!) == null) rel = null
        if (rel == null && id != null) {
            // Moved or renamed by the person: same drawing id inside.
            jf.index.scan(jf.fs)
            rel = jf.index.items().firstOrNull {
                FileKind.of(Placement.nameOf(it.path)) == FileKind.DRAWING && docIdIn(jf, it.path) == id
            }?.path
        }
        if (rel == null) {
            val where = jf.prepareDir(Placement.What.NEW_WORK, null, null, FileKind.DRAWING)
            rel = Placement.join(where, jf.freeName(where, ReadableNames.sanitize(name) + ".joybrush"))
        }
        jf.writeAtomic(rel!!, working.readBytes())
        val st = jf.fs.stat(rel!!)
        if (st != null) jf.index.put(rel!!, st.size, st.modified, null, "joybrush")
        jf.saveIndex()
        setFolderPath(dir, file, rel!!)
        return rel
    }

    private fun setFolderPath(dir: File, file: String, rel: String) = synchronized(lock) {
        val index = readIndex(dir)
        index.optJSONObject("drawings")?.optJSONObject(file)?.put("folderPath", rel)
        writeIndex(dir, index)
    }

    /** The drawing id inside a `.joybrush` in the folder (its document.json's first "id"), or null. */
    private fun docIdIn(jf: JoyFolder, rel: String): String? = try {
        ZipInputStream(jf.fs.open(rel)).use { zip ->
            var entry = zip.nextEntry
            var found: String? = null
            while (entry != null && found == null) {
                if (entry.name == "document.json") {
                    val head = ByteArray(4096)
                    val n = zip.read(head)
                    if (n > 0) found = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(String(head, 0, n, Charsets.UTF_8))?.groupValues?.get(1)
                }
                entry = zip.nextEntry
            }
            found
        }
    } catch (e: Exception) {
        null
    }

    /**
     * A small picture of the active board over its paper colour. Renders only the rows the picture samples, one row at
     * a time, so a big board costs a few megabytes, never the full-size composite. Layers this renderer can't draw
     * (vector, animated) are left out rather than failing the whole picture.
     */
    internal fun thumbnailOf(contents: JbContents): Bitmap? {
        val full = contents.doc
        val doc = full.copy(layers = full.layers.filter { it.kind.hasPixels && it.animatedIn == null })
        val board = doc.boards.firstOrNull { it.id == doc.activeBoardId } ?: doc.boards.firstOrNull() ?: return null
        val r = board.rect
        if (r.w <= 0 || r.h <= 0) return null
        val scale = min(1f, THUMB_SIDE / max(r.w, r.h).toFloat())
        val tw = max(1, (r.w * scale).roundToInt())
        val th = max(1, (r.h * scale).roundToInt())
        val paper = full.paper.color.takeIf { Regex("^#[0-9a-fA-F]{6}$").matches(it) } ?: "#FFFFFF"
        val source = TileSource { layer, cel, tx, ty -> contents.tiles[Triple(layer, cel, DocOps.key(tx, ty))] }
        val out = IntArray(tw * th)
        for (oy in 0 until th) {
            val sy = min(r.h - 1, ((oy + 0.5f) / scale).toInt())
            val row = RegionRenderer.render(doc, source, RectPx(r.x, r.y + sy, r.w, 1), null, paper)
            for (ox in 0 until tw) {
                val sx = min(r.w - 1, ((ox + 0.5f) / scale).toInt())
                val i = sx * 4
                out[oy * tw + ox] = ((row[i + 3].toInt() and 0xFF) shl 24) or ((row[i].toInt() and 0xFF) shl 16) or
                    ((row[i + 1].toInt() and 0xFF) shl 8) or (row[i + 2].toInt() and 0xFF)
            }
        }
        return Bitmap.createBitmap(out, tw, th, Bitmap.Config.ARGB_8888)
    }

    private fun readIndex(dir: File): JSONObject = try {
        val f = File(dir, INDEX)
        if (f.isFile) JSONObject(f.readText()) else JSONObject()
    } catch (e: Exception) {
        Log.w(TAG, "library.json unreadable; starting it again", e)
        JSONObject()
    }

    private fun writeIndex(dir: File, index: JSONObject) {
        val f = File(dir, INDEX)
        val tmp = File(dir, "$INDEX.tmp")
        tmp.writeText(index.toString(2))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
