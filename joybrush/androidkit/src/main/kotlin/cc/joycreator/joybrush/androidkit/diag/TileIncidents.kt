package cc.joycreator.joybrush.androidkit.diag

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The tile failures this phone has met, kept in a small file as well as said to logcat.
 *
 * Why a file: on 2026-10-31 four oil tiles lost their graphics memory on the owner's Note 9 and drew black; by the time
 * anyone looked, logcat had rolled over, and the fault has not been met again on a bench. Every place the engine finds a
 * tile it cannot use (no memory behind a new texture, a texture lost, a copy that failed, a texture let go twice) says so
 * through [e]: the line, when it happened and what the engine held are appended to [file], which a debug build gives up to
 * `adb shell run-as <package> cat files/joybrush-incidents.log`. The person painting is never shown any of it.
 *
 * The file stays small: past [MAX_BYTES] the older half is dropped.
 */
object TileIncidents {
    const val TAG = "JoyBrushTiles"
    const val FILE_NAME = "joybrush-incidents.log"
    private const val MAX_BYTES = 256 * 1024L

    /** Where the incidents are kept (the app's private files folder); null until the canvas says, and in JVM tests. */
    @Volatile var file: File? = null

    /** What the engine holds right now, in a few words, asked on the thread the incident happened on; null = nothing to add. */
    @Volatile var context: (() -> String)? = null

    /** How many incidents this run has written (the bench reads it). */
    @Volatile var count = 0
        private set

    /** An incident: said to logcat under [TAG] and appended to [file]. Never throws. */
    fun e(message: String, t: Throwable? = null) {
        try { if (t != null) android.util.Log.e(TAG, message, t) else android.util.Log.e(TAG, message) } catch (_: Throwable) { }
        append(message, t)
    }

    private val lock = Any()

    private fun append(message: String, t: Throwable?) {
        val f = file ?: return
        try {
            val held = try { context?.invoke() } catch (x: Throwable) { "context failed: ${x.javaClass.simpleName}" }
            val line = buildString {
                append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
                append(" | ").append(message.replace('\n', ' '))
                if (held != null) append(" | ").append(held)
                if (t != null) {
                    append(" | ").append(t.javaClass.simpleName).append(": ").append(t.message ?: "")
                    for (s in t.stackTrace.take(8)) append(" < ").append(s.className.substringAfterLast('.')).append('.').append(s.methodName).append(':').append(s.lineNumber)
                }
                append('\n')
            }
            synchronized(lock) {
                if (f.exists() && f.length() > MAX_BYTES) {
                    val all = f.readBytes()
                    var from = all.size / 2
                    while (from < all.size && all[from] != '\n'.code.toByte()) from++
                    f.writeBytes(all.copyOfRange(minOf(all.size, from + 1), all.size))
                }
                f.appendText(line)
                count++
            }
        } catch (_: Throwable) {
            // The record is a courtesy: it must never be the thing that fails.
        }
    }
}
