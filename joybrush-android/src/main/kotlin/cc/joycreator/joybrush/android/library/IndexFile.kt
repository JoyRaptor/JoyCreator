package cc.joycreator.joybrush.android.library

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Reading and writing the drawing list's file so that one bad write can never cost the list (work-loss audit
 * 2026-11-10).
 *
 * Write: the new list goes to a temporary file and is forced to disk; the list as it was becomes `library.json.bak`;
 * the new one is renamed into place. The old code wrote without forcing to disk (a dead battery could leave an empty
 * file under the right name) and, when the rename failed, deleted the list and tried once more.
 *
 * Read: the file, else its backup, else an empty list. The caller then adds back any drawing file the list does not
 * know ([unlisted]), so even both files lost costs the names, never the drawings.
 */
internal object IndexFile {

    /** The list in [file], else in its backup, else empty. [problem] is told which one could not be read. */
    fun read(file: File, problem: (String, Exception) -> Unit = { _, _ -> }): JSONObject {
        for (candidate in listOf(file, backupOf(file))) {
            if (!candidate.isFile) continue
            try {
                val text = candidate.readText()
                // A file cut off at zero bytes is not "no drawings": go on to the backup.
                if (text.isBlank()) throw IllegalStateException("empty file")
                return JSONObject(text)
            } catch (e: Exception) {
                problem(candidate.name, e)
            }
        }
        return JSONObject()
    }

    /** Replaces [file] with [text]. Throws when the new list could not be written; the list on disk is then untouched. */
    fun write(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        val bak = backupOf(file)
        if (file.isFile) {
            // Only a list that reads is worth keeping as the backup: a damaged one must not push out a good backup.
            val readable = try { file.readText().let { it.isNotBlank() && JSONObject(it).length() >= 0 } } catch (e: Exception) { false }
            if (readable) {
                bak.delete()
                if (!file.renameTo(bak)) file.copyTo(bak, overwrite = true)
            }
        }
        if (!tmp.renameTo(file)) {
            // Some systems will not rename over a file. Whatever is still under the name is either kept as the
            // backup already or does not read, so it can go; then once more.
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                // Never leave the folder with no list at all.
                if (!file.isFile && bak.isFile) bak.copyTo(file, overwrite = true)
                tmp.delete()
                throw java.io.IOException("the drawing list could not be saved")
            }
        }
    }

    fun backupOf(file: File): File = File(file.path + ".bak")

    /** The names the library itself gives a drawing's file: `drawing-<time>.joybrush`, `drawing-<time>-<n>.joybrush`. */
    private val OWN_NAME = Regex("^drawing-\\d+(-\\d+)?\\.joybrush$")

    /**
     * The drawing files in a folder listing that the list does not know, oldest name first. Only files the library
     * named itself: a stray archive left in the folder by a test or a tool (the Note 9 had `export-check.joybrush`
     * from October) is not a drawing the person lost, and must not turn up on their list as "Recovered drawing".
     * Never a save's `.bak` or `.tmp` beside a drawing. [legacy] is the first drawing's fixed file, which the
     * library adds by itself under its own name.
     */
    fun unlisted(names: Array<String>?, listed: Set<String>, legacy: String): List<String> =
        (names ?: emptyArray()).filter { OWN_NAME.matches(it) && it != legacy && it !in listed }.sorted()
}
