package cc.joycreator.joybrush.androidkit.lab

import android.os.Handler
import android.os.Looper
import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BrushValidate
import java.io.File

/**
 * JB-1.21, the phone half of the brush lab: a brush file changed on a PC is felt on the phone a
 * second later, with no rebuild and no reinstall.
 *
 * The lab folder is `getExternalFilesDir("joybrush/lab")` — the caller resolves it, because only an
 * Android `Context` can, and this class takes the resolved [File] so its whole decision can be
 * tested on a plain JVM. Inside it, one folder per brush, each holding a `brush.json` in the format
 * [BrushJson] reads: the same shape `joybrush/brushes/` has on the PC, so the file a person edits
 * is literally the file a brush folder already is.
 *
 * Every [POLL_MS] on the main thread, [scanOnce] walks the lab folder and reports the files whose
 * lastModified has moved since this watcher last saw them. A file is decoded with [BrushJson] and
 * then asked [BrushValidate] whether it is usable — the same two calls, in the same order, that
 * `BrushLibrary` makes on the packaged brushes, so a file the lab accepts is a file the app already
 * knows how to paint with. Usable goes to [onBrush]; anything else goes to [onError] with the
 * sentences that refused it, and the caller's current brush is left exactly where it was.
 *
 * [onError] is never called with an empty list — a file either produced sentences or produced a
 * brush, and a screen that shows "the first problem" may take `first()` without checking. That is
 * a promise this class makes, not one it asks its caller to keep.
 *
 * There is no server and no socket: the phone only reads a folder it owns, and only a debuggable
 * build is allowed to start one (the caller checks `ApplicationInfo.FLAG_DEBUGGABLE`).
 */
class BrushHotReload(
    private val lab: File,
    private val onBrush: (BrushPreset) -> Unit,
    private val onError: (File, List<String>) -> Unit,
) {

    /** The file inside each brush folder, and the only file in it this watcher reads. */
    companion object {
        const val BRUSH_FILE = "brush.json"

        /**
         * The lab folder, as an argument to `Context.getExternalFilesDir` — so the full path is
         * `<external files>/joybrush/lab`. Owned here rather than spelled out by the screen so the
         * two cannot disagree about where the lab is, and so `tools/brushlab_push.sh` has one
         * string in this repository to agree with rather than two.
         */
        const val LAB_DIR = "joybrush/lab"

        /** How often the lab folder is looked at, ms. The spec's number. */
        const val POLL_MS = 500L
    }

    /**
     * The lastModified already reported, per file. Empty means "nothing has been seen yet", so a
     * brush already sitting in the lab when the watcher starts is reported on its first scan —
     * which is the whole point of pushing a folder and then opening the app.
     */
    private val seen = HashMap<String, Long>()

    /**
     * Built by [start] and never by the constructor: this class has to be constructible — and
     * testable — on a machine with no Android runtime at all, and a `Handler` is the one thing here
     * that needs one.
     */
    private var ui: Handler? = null
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            // A tick already queued when stop() ran would otherwise re-arm the loop for ever.
            if (!running) return
            scanOnce()
            ui?.postDelayed(this, POLL_MS)
        }
    }

    /** Begins polling. A second call while running does nothing. */
    fun start() {
        if (running) return
        val handler = Handler(Looper.getMainLooper())
        ui = handler
        running = true
        handler.postDelayed(tick, POLL_MS)
    }

    /** Stops polling and drops the queued tick. Safe to call when never started. */
    fun stop() {
        running = false
        ui?.removeCallbacks(tick)
        ui = null
    }

    /**
     * One pass over the lab folder. This is the whole of the decision; [start] only decides when it
     * happens.
     *
     * Folders are taken in name order so two changed brushes reload the same way every time, and a
     * file is offered on the FIRST time it is seen as well as on every later change, so a brush
     * pushed before the screen was opened is live the moment it is.
     */
    fun scanOnce() {
        // A lab folder that is not there yet is not an error — nobody has pushed anything — and a
        // null here is the ordinary answer from File, not a failure to report.
        val folders = lab.listFiles() ?: return
        for (folder in folders.sortedBy { it.name }) {
            if (!folder.isDirectory) continue
            val file = File(folder, BRUSH_FILE)
            if (!file.isFile) continue

            // The time is read BEFORE the bytes, and that order is the whole reason an edit is
            // never lost. Reading it afterwards would stamp the entry with the time of a file that
            // had already been rewritten again, and the newer version would then look unchanged for
            // ever. This way a save that lands mid-scan is simply caught by the next tick.
            val stamp = file.lastModified()
            if (seen[file.path] == stamp) continue
            // Marked even when the read below fails, so a file that cannot be read says so once
            // rather than twice a second for as long as the lab is open. Fixing the file moves its
            // time, and a fixed file is picked up like any other change.
            seen[file.path] = stamp

            val text = try {
                file.readText()
            } catch (e: Exception) {
                onError(file, listOf("brush.json could not be read: ${e.message}"))
                continue
            }
            val preset = try {
                BrushJson.decode(text)
            } catch (e: BrushException) {
                onError(file, listOf(e.message ?: "brush.json could not be read"))
                continue
            }
            val problems = BrushValidate.validate(preset)
            if (problems.isNotEmpty()) {
                onError(file, problems)
                continue
            }
            onBrush(preset)
        }
    }
}
