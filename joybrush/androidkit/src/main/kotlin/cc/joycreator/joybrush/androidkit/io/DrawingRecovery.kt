package cc.joycreator.joybrush.androidkit.io

import java.io.File
import java.util.zip.ZipFile
import cc.joycreator.joybrush.core.doc.DocJson

/** File-thread only. Keep pixels on disk, never a second full drawing in the Android heap. */
class DrawingRecovery(private val opened: File) {
    private var backupAllowed = false
    /** A newly opened drawing may not be in current.joybrush yet. Keep it before GPU upload. */
    fun rememberOpened(contents: JbContents) {
        JbArchive.save(opened, contents)
        // This is a selected drawing, not another revision of the previous one. Its backup
        // may share the same default document ID, so it must not be used as its fallback.
        backupAllowed = false
    }

    /** Call only AFTER the working archive's durable save succeeds, on the same file executor. */
    fun rememberWorking(file: File) {
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
            val contents = try { JbArchive.open(file) } catch (_: Exception) { continue }
            if (contents.doc.id == documentId) return contents
        }
        throw JbArchiveException("no readable saved copy of this drawing is available; your files were kept")
    }

    /** Session files only; never removes current.joybrush or its backup. */
    fun close() {
        listOf(opened, File(opened.path + ".bak"), File(opened.path + ".tmp")).forEach { it.delete() }
    }
}
