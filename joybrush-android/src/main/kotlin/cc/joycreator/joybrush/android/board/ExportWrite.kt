package cc.joycreator.joybrush.android.board

import cc.joycreator.joybrush.androidkit.io.JbArchiveException
import java.io.OutputStream

/**
 * Writing a finished export to the place the person chose, and PROVING it got there
 * (EXPORT-FORMATS audit 11-24). Android's file picker hands back a place that can only be written
 * in place, so a write that dies part-way would leave a cut-off file under the person's chosen
 * name while the app said "Picture exported". Here the length that reached the disk is read back;
 * if it is not the whole picture, the cut-off file is removed (where the folder allows it) and the
 * person is told in plain words, so a short file never sits there looking like a good one.
 *
 * Pure Kotlin over three small functions so the test can play every way it goes wrong.
 */
object ExportWrite {

    /**
     * @param open opens the chosen place for writing (null when it cannot be opened)
     * @param writtenSize the size the place now holds, or null when the folder cannot say
     * @param remove removes the file at the chosen place; true when it went
     * @throws JbArchiveException with a sentence for the person when the file did not arrive whole
     */
    fun write(bytes: ByteArray, open: () -> OutputStream?, writtenSize: () -> Long?, remove: () -> Boolean) {
        val out = open() ?: throw JbArchiveException("the export destination could not be opened")
        try {
            out.use { it.write(bytes) }
        } catch (e: Exception) {
            val gone = runCatching(remove).getOrDefault(false)
            throw JbArchiveException(cutOff(gone, e.message))
        }
        val size = runCatching(writtenSize).getOrNull()
        if (size != null && size != bytes.size.toLong()) {
            val gone = runCatching(remove).getOrDefault(false)
            throw JbArchiveException(cutOff(gone, "$size of ${bytes.size} bytes arrived"))
        }
    }

    private fun cutOff(gone: Boolean, why: String?): String =
        (if (gone) "the file did not arrive whole, so it was removed rather than left cut off"
        else "the file did not arrive whole and could not be removed; delete it and export again") +
            (if (why.isNullOrBlank()) "" else " ($why)")
}
