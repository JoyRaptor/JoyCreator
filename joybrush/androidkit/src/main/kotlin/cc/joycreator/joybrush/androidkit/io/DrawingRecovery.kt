package cc.joycreator.joybrush.androidkit.io

import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile
import cc.joycreator.joybrush.core.doc.DocJson

/** File-thread only. Keep pixels on disk, never a second full drawing in the Android heap. */
class DrawingRecovery(private val opened: File) {

    init {
        // Sweep the previous session's leftovers. close() only runs on a clean exit, so a force-stop,
        // a crash, or a low-memory kill leaked a FULL copy of the drawing every time - each one is
        // the same bytes as current.joybrush. The owner's phone had accumulated 24 of them, 536 MB,
        // in a cache directory nobody ever read again. Sweeping on construction is the only moment
        // a leftover is provably dead: this session's own file is named with its own UUID, and a
        // concurrent screen would have its own file that it deletes in close().
        //
        // Only files matching OUR prefix and not our own path are touched, so current.joybrush in the
        // documents directory and anything else in cache is untouched.
        opened.parentFile?.listFiles()?.forEach { stale ->
            if (stale.name.startsWith("joybrush-recovery-") && stale.name != opened.name) stale.delete()
        }
    }

    /**
     * A saved generation of the drawing kept for the restore WITHOUT copying it (Codex audit round 4, JB-SAVE-IO: the
     * recovery copy read and wrote the whole file again after every autosave). Every writer of a drawing file writes a new
     * file and renames it into place (JbArchive's `.tmp` and rename, the Library's imports); none writes into an existing
     * one. So the file as it lies right after this screen's save is that generation for as long as it exists, and holding it
     * open keeps it readable even after another writer renames it aside or removes it. While the path still names the very
     * file held ([identity]), the restore reads the path; otherwise the held file is copied out once, then.
     */
    private class Pin(val path: File, val identity: String, val held: FileInputStream)

    /** The generations a restore may use, newest first: a [Pin], or a file of this recovery's own ([opened] and its `.bak`). */
    private val kept = ArrayList<Any>(2)
    private var backupAllowed = false
    /** How the last working save was kept: "held, not copied" or "copied" (the save's log line). */
    var lastKept = ""; private set
    /** What [read] last handed out from a file of this recovery's own: let go before that file is written over (let go, then write). */
    private var handed: JbContents? = null
    private fun letGo() { handed?.let { DeferredMedia.release(it) }; handed = null }
    private val pinCopy = File(opened.path + ".pin")

    /** [newest] and [older] are what a restore may use from now on; a generation neither is goes (a file of our own is removed). */
    private fun keep(newest: Any, older: Any?) {
        for (old in kept) {
            if (old == newest || old == older) continue
            if (old is Pin) try { old.held.close() } catch (_: Exception) { }
            if (old is File) { letGo(); old.delete() }
        }
        kept.clear(); kept.add(newest); if (older != null) kept.add(older)
    }

    /** A newly opened drawing may not be in current.joybrush yet. Keep it before GPU upload. */
    fun rememberOpened(contents: JbContents) {
        CanvasSnapshot.requireSupportedPayload(contents)
        // A drawing whose paint state is still in its file (JbArchive.openDeferringMedia): the recovery copy is a copy of
        // that file, which was checked whole when it was opened. Saving it again would read every paint tile back into
        // memory at once, the very thing opening it this way avoids, and inflate the drawing twice.
        val source = DeferredMedia.fileOf(contents)
        when {
            source == null -> { letGo(); JbArchive.save(opened, contents) }
            source.canonicalFile == opened.canonicalFile -> Unit   // it IS the recovery copy
            source.isFile -> { letGo(); JbArchive.copySaved(source, opened) }
            else -> { letGo(); JbArchive.save(opened, contents) }  // its path is gone; the open file still reads
        }
        keep(opened, null)
        // This is a selected drawing, not another revision of the previous one. Its backup
        // may share the same default document ID, so it must not be used as its fallback.
        backupAllowed = false
    }

    /** Call only AFTER the working archive's durable save succeeds, on the same file executor. */
    fun rememberWorking(file: File) {
        letGo()
        val pin = pinOf(file)
        if (pin == null) {
            // This file system cannot say which file a path names (a JVM on Windows): a copy, as always.
            JbArchive.copySaved(file, opened)
            keep(opened, File(opened.path + ".bak"))
        } else {
            keep(pin, kept.firstOrNull())
        }
        lastKept = if (pin == null) "copied" else "held, not copied"

        backupAllowed = true
    }

    private fun pinOf(file: File): Pin? {
        val before = identity(file) ?: run {
            if (!file.isFile) throw JbArchiveException("there is no saved drawing at ${file.name} to keep for a restore")
            return null
        }
        val held = try { FileInputStream(file) } catch (e: Exception) {
            throw JbArchiveException("${file.name} cannot be kept for a restore: ${e.message ?: e.javaClass.simpleName}")
        }
        // The path still names the file just opened (nothing renamed another over it in between): then it is that one.
        if (identity(file) != before) { try { held.close() } catch (_: Exception) { }; return null }
        return Pin(file, before, held)
    }

    /** Never restore another drawing, even if another screen has replaced the shared working file. */
    fun read(documentId: String): JbContents {
        val candidates = if (backupAllowed) kept.toList() else kept.take(1)
        for (gen in candidates) {
            val contents = when (gen) {
                is Pin -> readPin(gen, documentId)
                is File -> readFile(gen, documentId)
                else -> null
            } ?: continue
            return contents
        }
        throw JbArchiveException("no readable saved copy of this drawing is available; your files were kept")
    }

    private fun readPin(pin: Pin, documentId: String): JbContents? {
        if (identity(pin.path) == pin.identity) {
            val contents = readFile(pin.path, documentId)
            // Still the held file once it is open (files are replaced, never written into): that is the generation, read or
            // not (a copy of it would read no better).
            if (identity(pin.path) == pin.identity) return contents
            if (contents != null) { DeferredMedia.release(contents); if (handed === contents) handed = null }
        }
        // The path names another file now, or none: the held one is copied out, this once.
        letGo()
        try {
            pinCopy.outputStream().use { out ->
                val ch = pin.held.channel
                var at = 0L
                val size = ch.size()
                while (at < size) {
                    val n = ch.transferTo(at, size - at, out.channel)
                    if (n <= 0) throw java.io.IOException("the kept drawing could not be read to its end")
                    at += n
                }
                out.fd.sync()
            }
        } catch (_: Exception) { pinCopy.delete(); return null }
        return readFile(pinCopy, documentId)
    }

    private fun readFile(file: File, documentId: String): JbContents? {
        if (!file.isFile) return null
        // Check identity without inflating hundreds of MiB of another drawing's pixels.
        val id = try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("document.json") ?: return@use null
                zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { DocJson.decode(it.readText()).id }
            }
        } catch (_: Exception) { null }
        if (id != documentId) return null
        val contents = try { JbArchive.openDeferringMedia(file) } catch (_: Exception) { return null }
        if (contents.doc.id == documentId) {
            // Held until the canvas has taken its tiles, or until this file is written over ([letGo]); a refusal
            // lets it go at once.
            try { CanvasSnapshot.requireSupportedPayload(contents) } catch (e: Exception) { DeferredMedia.release(contents); throw e }
            letGo(); handed = contents
            return contents
        }
        DeferredMedia.release(contents)
        return null
    }

    /** Session files only; never removes current.joybrush or its backup. */
    fun close() {
        letGo()
        keep(opened, null); kept.clear()
        listOf(opened, File(opened.path + ".bak"), File(opened.path + ".tmp"), pinCopy).forEach { it.delete() }
    }

    internal companion object {
        /**
         * Which file [file] names: device and inode, or null when that cannot be told (then the recovery copies, as before).
         * Android: Os.stat. A JVM (the unit tests): the file key, which Windows does not give.
         */
        fun identity(file: File): String? {
            try {
                val st = android.system.Os.stat(file.path)
                return "${st.st_dev}:${st.st_ino}"
            } catch (_: Exception) { } catch (_: LinkageError) { }
            return try {
                java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()?.toString()
            } catch (_: Exception) { null } catch (_: LinkageError) { null }
        }
    }
}
