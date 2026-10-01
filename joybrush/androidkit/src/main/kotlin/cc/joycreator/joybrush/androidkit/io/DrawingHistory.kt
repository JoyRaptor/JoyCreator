package cc.joycreator.joybrush.androidkit.io

import java.io.File
import java.util.UUID

/** Complete safety copies made BEFORE Open replaces the drawing, separate from autosave's backup. */
object DrawingHistory {
    const val KEEP = 5

    fun list(directory: File): List<File> = directory.listFiles()?.filter {
        it.isFile && it.name.endsWith(".joybrush")
    }?.sortedByDescending { it.name } ?: emptyList()

    /** Returns only after the archive has landed. Failed writes never prune previous drawings. */
    fun preserve(directory: File, contents: JbContents): File {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw JbArchiveException("there is nowhere to keep the previous drawing")
        }
        val file = File(directory, "${System.currentTimeMillis()}-${UUID.randomUUID()}.joybrush")
        JbArchive.save(file, contents)
        // Always retain this copy, even if the clock moved backwards or timestamps tied.
        list(directory).filter { it != file }.drop(KEEP - 1).forEach { it.delete() }
        return file
    }

    data class Working(val contents: JbContents, val recoveredBackup: Boolean)

    /** A interrupted rename may leave only .bak; corrupt main files also get a validated fallback. */
    fun readWorking(file: File): Working? {
        var failure: Exception? = null
        if (file.isFile) {
            try { return Working(JbArchive.open(file), false) }
            catch (e: Exception) { failure = e }
        }
        val backup = File(file.path + ".bak")
        if (backup.isFile) {
            try { return Working(JbArchive.open(backup), true) }
            catch (e: Exception) { if (failure == null) failure = e }
        }
        if (failure != null) throw JbArchiveException("the working drawing and its backup could not be read: ${failure.message}")
        return null
    }
}
