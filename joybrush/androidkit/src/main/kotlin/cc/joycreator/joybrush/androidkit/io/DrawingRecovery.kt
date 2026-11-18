package cc.joycreator.joybrush.androidkit.io

import java.io.File
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

    private var backupAllowed = false
    /** What [read] last handed out from [opened] itself: let go before [opened] is written over (let go, then write). */
    private var handed: JbContents? = null
    private fun letGo() { handed?.let { DeferredMedia.release(it) }; handed = null }
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
        // This is a selected drawing, not another revision of the previous one. Its backup
        // may share the same default document ID, so it must not be used as its fallback.
        backupAllowed = false
    }

    /** Call only AFTER the working archive's durable save succeeds, on the same file executor. */
    fun rememberWorking(file: File) {
        letGo()
        JbArchive.copySaved(file, opened)
        backupAllowed = true
    }

    /** Never restore another drawing, even if another screen has replaced the shared working file. */
    fun read(documentId: String): JbContents {
        val candidates = if (backupAllowed) listOf(opened, File(opened.path + ".bak")) else listOf(opened)
        for (file in candidates) {
            if (!file.isFile) continue
            // Check identity without inflating hundreds of MiB of another drawing's pixels.
            val id = try {
                ZipFile(file).use { zip ->
                    val entry = zip.getEntry("document.json") ?: return@use null
                    zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { DocJson.decode(it.readText()).id }
                }
            } catch (_: Exception) { null }
            if (id != documentId) continue
            val contents = try { JbArchive.openDeferringMedia(file) } catch (_: Exception) { continue }
            if (contents.doc.id == documentId) {
                // Held until the canvas has taken its tiles, or until this file is written over ([letGo]); a refusal
                // lets it go at once.
                try { CanvasSnapshot.requireSupportedPayload(contents) } catch (e: Exception) { DeferredMedia.release(contents); throw e }
                letGo(); handed = contents
                return contents
            }
            DeferredMedia.release(contents)
        }
        throw JbArchiveException("no readable saved copy of this drawing is available; your files were kept")
    }

    /** Session files only; never removes current.joybrush or its backup. */
    fun close() {
        letGo()
        listOf(opened, File(opened.path + ".bak"), File(opened.path + ".tmp")).forEach { it.delete() }
    }
}
