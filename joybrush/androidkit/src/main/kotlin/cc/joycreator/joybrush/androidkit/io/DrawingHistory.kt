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

    /**
     * A interrupted rename may leave only .bak; corrupt main files also get a validated fallback.
     *
     * Catches THROWABLE, not Exception, and that is the whole point of the method. A 28 MB working
     * file on a 512 MB heap exhausts memory inside [open], and OutOfMemoryError is an Error, so
     * `catch (e: Exception)` let it escape: the .bak fallback below was unreachable in exactly the
     * case it exists for, and the error then propagated out of the caller's `catch (e: Exception)`
     * on a background thread and killed the process. Observed on the Note 9 as Joy Brush loading,
     * crashing four times in a row and bouncing the owner to the lobby.
     *
     * Reading the backup instead of dying is the intended behaviour and is now reachable. If BOTH
     * fail we still throw, and the caller reports it in words rather than taking the process with it.
     *
     * [open] is a seam so the OOM path is testable: raising a real OutOfMemoryError from the zip
     * reader needs a file large enough to exhaust a real heap, which no unit test should do.
     */
    fun readWorking(file: File, open: (File) -> JbContents = JbArchive::open): Working? {
        var failure: Throwable? = null
        if (file.isFile) {
            try { return Working(open(file), false) }
            catch (e: Throwable) { failure = e }
        }
        val backup = File(file.path + ".bak")
        if (backup.isFile) {
            try { return Working(open(backup), true) }
            catch (e: Throwable) { if (failure == null) failure = e }
        }
        if (failure != null) throw JbArchiveException("the working drawing and its backup could not be read: ${failure.message}")
        return null
    }
}
