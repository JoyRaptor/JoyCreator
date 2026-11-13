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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
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
                // Listing must work even when the list cannot be written just now (a full phone).
                try { writeIndex(dir, index) } catch (e: Exception) { Log.w(TAG, "list not saved yet", e) }
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
            // A drawing that is not on the list would open as some OTHER drawing ([fileToOpen] falls back to the
            // last one): no new drawing rather than that.
            try { writeIndex(dir, index) } catch (e: Exception) { Log.w(TAG, "new drawing not made: the list could not be saved", e); return null }
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
            try { writeIndex(dir, index) } catch (ex: Exception) { Log.w(TAG, "not renamed: the list could not be saved", ex); return false }
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
            // The list first: with the file gone but still listed, the list would show a drawing that opens blank.
            try { writeIndex(dir, index) } catch (e: Exception) {
                Log.w(TAG, "not deleted: the list could not be saved", e)
                return "the drawing list couldn't be saved, so it was left where it is"
            }
            // Inside the lock with the list change: a drawing file the list does not know is put back on the list
            // ([adoptUnlisted]), so the file must be gone before anyone else can read the list.
            working.delete()
            File(working.path + ".bak").delete()
        }
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
            // "Last opened" is a convenience; failing to note it must never stop a drawing from opening.
            try { writeIndex(dir, index) } catch (e: Exception) { Log.w(TAG, "last opened not noted", e) }
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
            } catch (e: Throwable) {
                // Throwable: running out of memory here is an Error, and on this plain worker thread an Error that
                // nobody catches closes the whole app, right after a save (work-loss audit 2026-11-10).
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
        separateSharedCopies(context, jf, dir)
        // Before the paths below are read, or a copy made here would come back in as a new drawing.
        backfillFolderCopies(context, jf, dir)
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
            // The once-shared id says nothing about which drawing a file is: those are told apart by path only.
            if (FolderAdoption.knownById(id, known)) continue
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
    internal fun mirrorFile(context: Context, jf: JoyFolder, dir: File, file: String, docId: String?): String? {
        separateSharedCopies(context, jf, dir)
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
            rel = ownFileInFolder(jf, dir, file, id)
        }
        if (rel == null) {
            val where = jf.prepareDir(Placement.What.NEW_WORK, null, null, FileKind.DRAWING)
            rel = Placement.join(where, jf.freeName(where, ReadableNames.sanitize(name) + ".joybrush"))
        }
        // A piece at a time: a drawing can be tens of megabytes, and reading all of it into memory to copy it ran the
        // phone out of memory while the picture itself was still held for the save.
        FileInputStream(working).use { jf.writeAtomicFrom(rel!!, it) }
        val st = jf.fs.stat(rel!!)
        if (st != null) jf.index.put(rel!!, st.size, st.modified, null, "joybrush")
        jf.saveIndex()
        setFolderPath(dir, file, rel!!)
        return rel
    }

    /**
     * A folder file that is this drawing, moved or renamed by the person: same drawing id inside. Never a file another
     * drawing in the list already keeps its copy in, and never by the id every drawing once shared (audit 2026-10-18 P1:
     * that adoption overwrote another drawing's folder copy).
     */
    private fun ownFileInFolder(jf: JoyFolder, dir: File, file: String, id: String?): String? {
        val claimed = synchronized(lock) {
            val entries = readIndex(dir).optJSONObject("drawings") ?: JSONObject()
            entries.keys().asSequence().filter { it != file }
                .mapNotNull { k -> entries.optJSONObject(k)?.optString("folderPath", "")?.ifEmpty { null } }.toHashSet()
        }
        jf.index.scan(jf.fs)
        return FolderAdoption.pick(id, jf.index.items().asSequence().map { it.path }
            .filter { FileKind.of(Placement.nameOf(it)) == FileKind.DRAWING }, claimed) { docIdIn(jf, it) }
    }

    /**
     * Give a folder copy to every listed drawing that has a working copy but no place in the folder: one not saved since
     * the library arrived on 2026-10-14 never reached [mirrorFile], and an uninstall would lose it (found on the Note 9,
     * 2026-10-31: "My drawing"). Its own file, if the person's folder already has it, is remembered and left as it is;
     * otherwise it gets a new file under a free name. One that fails doesn't stop the others; the next sync tries again.
     */
    internal fun backfillFolderCopies(context: Context, jf: JoyFolder, dir: File) {
        val placed = HashMap<String, String>()
        val missing = synchronized(lock) {
            val entries = readIndex(dir).optJSONObject("drawings") ?: JSONObject()
            for (k in entries.keys()) {
                entries.getJSONObject(k).optString("folderPath", "").takeIf { it.isNotEmpty() && File(dir, k).isFile }
                    ?.let { placed[k] = it }
            }
            entries.keys().asSequence().filter { k ->
                entries.getJSONObject(k).optString("folderPath", "").isEmpty() && File(dir, k).isFile
            }.toList()
        }
        for (file in missing) {
            try {
                val own = ownFileInFolder(jf, dir, file, zipDocId(FileInputStream(File(dir, file))))
                if (own != null) { setFolderPath(dir, file, own); nameRecoveredAfter(dir, file, own) }
                else mirrorFile(context, jf, dir, file, null)
            } catch (e: Throwable) {
                Log.w(TAG, "folder copy of $file not made; the next sync tries again", e)
            }
        }
        // A drawing whose remembered place is not in the folder: the person chose another Joy Creator folder, or
        // removed the copy by hand. It used to get a copy again only when it was next saved, so after a change of
        // folder every drawing not touched since was missing from the new one (work-loss audit 2026-11-10).
        // One listing per folder, not one per drawing.
        val names = HashMap<String, Set<String>?>()
        for ((file, rel) in placed) {
            val parent = Placement.parentOf(rel)
            val there = names.getOrPut(parent) {
                try { jf.fs.list(parent).mapTo(HashSet()) { it.name } } catch (e: Exception) { null }
            } ?: continue   // that folder can't be listed right now: say nothing about its drawings
            if (Placement.nameOf(rel) in there) continue
            try { mirrorFile(context, jf, dir, file, null) }
            catch (e: Throwable) { Log.w(TAG, "folder copy of $file not remade; the next sync tries again", e) }
        }
    }

    /** A drawing put back on the list without its name ([adoptUnlisted]) takes the name of its copy in the folder. */
    private fun nameRecoveredAfter(dir: File, file: String, rel: String) = synchronized(lock) {
        val index = readIndex(dir)
        val e = index.optJSONObject("drawings")?.optJSONObject(file) ?: return@synchronized
        if (!e.optBoolean("recovered", false)) return@synchronized
        val name = FileKind.baseOf(Placement.nameOf(rel))
        if (name.isNotBlank()) e.put("name", name)
        e.remove("recovered")
        writeIndex(dir, index)
    }

    /**
     * Give every drawing its own folder file again. A list made by a build before the 2026-10-18 audit can hold two
     * drawings with one folder path (found on the Note 9, 2026-10-31), and each save then wrote over the other's copy.
     * The file is overwritten by nobody here: the drawing really inside it keeps it, under its own name, and the others
     * get new files from their working copies. If none is really inside it, it stays where it is for [syncFromFolder].
     */
    internal fun separateSharedCopies(context: Context, jf: JoyFolder, dir: File) {
        val paths = HashMap<String, String>()
        val ids = HashMap<String, String>()
        synchronized(lock) {
            val entries = readIndex(dir).optJSONObject("drawings") ?: JSONObject()
            for (k in entries.keys()) {
                val e = entries.getJSONObject(k)
                e.optString("folderPath", "").takeIf { it.isNotEmpty() }?.let { paths[k] = it }
                e.optString("docId", "").takeIf { it.isNotEmpty() }?.let { ids[k] = it }
            }
        }
        for ((rel, drawings) in FolderAdoption.shared(paths)) {
            Log.w(TAG, "${drawings.size} drawings share $rel; separating them")
            val owner = FolderAdoption.owner(drawings, docIdIn(jf, rel), ids::get)
            val others = drawings.filter { it != owner }
            others.forEach { clearFolderPath(dir, it) }
            if (owner != null) {
                val wanted = readableName(dir, owner) + ".joybrush"
                if (Placement.nameOf(rel) != wanted) {
                    val free = jf.freeName(Placement.parentOf(rel), wanted)
                    try { if (jf.fs.rename(rel, free)) setFolderPath(dir, owner, Placement.join(Placement.parentOf(rel), free)) }
                    catch (e: Exception) { Log.w(TAG, "$rel not renamed; it stays this drawing's copy", e) }
                }
            }
            for (other in others) {
                // Left with no folder path, [backfillFolderCopies] tries again on every sync.
                try { mirrorFile(context, jf, dir, other, ids[other]) }
                catch (e: Exception) { Log.w(TAG, "folder copy of $other not made; the next sync tries again", e) }
            }
        }
    }

    private fun readableName(dir: File, file: String): String = synchronized(lock) {
        ReadableNames.sanitize(readIndex(dir).optJSONObject("drawings")?.optJSONObject(file)?.optString("name", "Drawing") ?: "Drawing")
    }

    private fun setFolderPath(dir: File, file: String, rel: String) = synchronized(lock) {
        val index = readIndex(dir)
        index.optJSONObject("drawings")?.optJSONObject(file)?.put("folderPath", rel)
        writeIndex(dir, index)
    }

    private fun clearFolderPath(dir: File, file: String) = synchronized(lock) {
        val index = readIndex(dir)
        index.optJSONObject("drawings")?.optJSONObject(file)?.remove("folderPath")
        writeIndex(dir, index)
    }

    /** The drawing id inside a `.joybrush` in the folder (its document.json's first "id"), or null. */
    private fun docIdIn(jf: JoyFolder, rel: String): String? = try {
        zipDocId(jf.fs.open(rel))
    } catch (e: Exception) {
        null
    }

    private fun zipDocId(input: InputStream): String? = try {
        ZipInputStream(input).use { zip ->
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
     * JBX-15: the paper as the screen and the PNG export show it (its look, surface and tint, through the same
     * [cc.joycreator.joybrush.androidkit.io.PaperResources] the export uses), so a kraft or textured drawing's picture
     * in the list is not plain white. Null for a flat paper (the plain colour is drawn, exactly as before) or a paper
     * whose loading fails outright (the plain colour again: a thumbnail never fails over its paper). A look whose
     * picture is missing comes back from PaperResources as its flat base and tint, as the export does.
     */
    internal fun paperLookOf(p: cc.joycreator.joybrush.core.doc.Paper): ((RectPx) -> ByteArray)? {
        if (p.screenTransparent || (p.lookId == null && p.textureId == null && p.tint == null)) return null
        return try {
            cc.joycreator.joybrush.androidkit.io.PaperResources.load(p)::render
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail paper look unavailable", e); null
        }
    }

    /**
     * A small picture of the active board over its paper (its look and tint since JBX-15, [paperLookOf]). Renders only the rows the picture samples, one row at
     * a time, so a big board costs a few megabytes, never the full-size composite. Layers this renderer can't draw
     * (vector, animated) are left out rather than failing the whole picture. A drawing with editable lines is drawn as the
     * screen shows it ([ExportPicture.asShown], as every export is): handed its line storage, the renderer refused it, and
     * the Library kept a blank or old picture (second code review E8; the Note 9's log, 2026-10-31).
     */
    internal fun thumbnailOf(saved: JbContents): Bitmap? {
        val contents = cc.joycreator.joybrush.androidkit.io.ExportPicture.asShown(saved)
        val full = contents.doc
        val doc = full.copy(layers = full.layers.filter { it.kind.hasPixels && it.animatedIn == null })
        val board = doc.boards.firstOrNull { it.id == doc.activeBoardId } ?: doc.boards.firstOrNull() ?: return null
        val r = board.rect
        if (r.w <= 0 || r.h <= 0) return null
        val scale = min(1f, THUMB_SIDE / max(r.w, r.h).toFloat())
        val tw = max(1, (r.w * scale).roundToInt())
        val th = max(1, (r.h * scale).roundToInt())
        val paper = full.paper.color.takeIf { Regex("^#[0-9a-fA-F]{6}$").matches(it) } ?: "#FFFFFF"
        val look = paperLookOf(full.paper)
        val source = TileSource { layer, cel, tx, ty -> contents.tiles[Triple(layer, cel, DocOps.key(tx, ty))] }
        val out = IntArray(tw * th)
        for (oy in 0 until th) {
            val sy = min(r.h - 1, ((oy + 0.5f) / scale).toInt())
            val row = RegionRenderer.render(doc, source, RectPx(r.x, r.y + sy, r.w, 1), null,
                if (look != null) null else paper, look)
            for (ox in 0 until tw) {
                val sx = min(r.w - 1, ((ox + 0.5f) / scale).toInt())
                val i = sx * 4
                out[oy * tw + ox] = ((row[i + 3].toInt() and 0xFF) shl 24) or ((row[i].toInt() and 0xFF) shl 16) or
                    ((row[i + 1].toInt() and 0xFF) shl 8) or (row[i + 2].toInt() and 0xFF)
            }
        }
        return Bitmap.createBitmap(out, tw, th, Bitmap.Config.ARGB_8888)
    }

    /**
     * The list of drawings. It is one small file, and every drawing's name and place hang on it, so it is read with
     * a fall-back and repaired from the drawings themselves (work-loss audit 2026-11-10). Before: an unreadable
     * library.json was read as an empty list, and the next write (every time Joy Brush opens) saved that. Every
     * drawing vanished from the list while its file sat untouched in the same folder.
     *
     * Order: the file; its backup (the list as it was one write ago); nothing. Then every `.joybrush` file in the
     * folder that the list does not know is put back on it ([adoptUnlisted]): the files are the truth, the list is
     * only how they are named and found. Call with [lock] held.
     */
    private fun readIndex(dir: File): JSONObject {
        val index = IndexFile.read(File(dir, INDEX)) { which, e -> Log.w(TAG, "$which unreadable", e) }
        adoptUnlisted(dir, index)
        return index
    }

    /**
     * Drawings whose files are here but whose line in the list is gone. Named "Recovered drawing N" (the real name
     * went with the list); [backfillFolderCopies] gives one its real name back when its copy in the person's folder
     * is found. A file is told from a stray by being a readable drawing archive: its id is read and kept, so the
     * folder sync does not bring the same drawing in a second time.
     */
    private fun adoptUnlisted(dir: File, index: JSONObject) {
        val entries = index.optJSONObject("drawings") ?: JSONObject()
        val listed = HashSet<String>()
        for (k in entries.keys()) listed.add(k)
        val strays = IndexFile.unlisted(dir.list(), listed, LEGACY_FILE)
        if (strays.isEmpty()) return
        val taken = HashSet<String>()
        for (k in entries.keys()) taken.add(entries.getJSONObject(k).optString("name").lowercase())
        var n = 1
        var adopted = 0
        for (file in strays) {
            val f = File(dir, file)
            val id = try { zipDocId(FileInputStream(f)) } catch (e: Exception) { null } ?: continue
            while ("recovered drawing $n" in taken) n++
            taken.add("recovered drawing $n")
            entries.put(file, JSONObject().put("name", "Recovered drawing $n").put("created", f.lastModified())
                .put("docId", id).put("recovered", true))
            adopted++
        }
        if (adopted == 0) return
        index.put("drawings", entries)
        Log.w(TAG, "$adopted drawing(s) were in the folder but not on the list; put back on it")
        try { writeIndex(dir, index) } catch (e: Exception) { Log.w(TAG, "the repaired list could not be saved yet", e) }
    }

    private fun writeIndex(dir: File, index: JSONObject) = IndexFile.write(File(dir, INDEX), index.toString(2))
}
