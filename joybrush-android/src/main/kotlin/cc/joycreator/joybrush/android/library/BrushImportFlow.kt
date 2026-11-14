package cc.joycreator.joybrush.android.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import cc.joycreator.joybrush.androidkit.BrushFileImport
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import com.fadcam.joyfolder.android.JoyFolderHome
import java.io.ByteArrayOutputStream

/**
 * BRUSH-IMPORT step 3: the JoyBrush screen's side of bringing brushes in. It asks for a file, converts it off the
 * screen thread ([BrushFileImport]), keeps the brushes in the person's folder ([SuppliesBrushes]) and hands the screen
 * the brushes to list ([brushes]) and the tip pictures to paint with ([pngs]). Everything the artist sees is one
 * plain sentence through [say]; the converters' own reasons only go to the log.
 */
class BrushImportFlow(
    private val activity: Activity,
    /** The folder's brushes changed (loaded, or a file was imported): the screen rebuilds its brush list. */
    private val changed: () -> Unit,
    private val say: (String) -> Unit,
) {
    /** The person's brushes from `Supplies/Brushes/`, empty until [load] finishes. UI thread. */
    var brushes: List<BrushPreset> = emptyList()
        private set
    private var images: Map<String, FrozenBrushPngs> = emptyMap()
    /** True once the first [load] has answered, with brushes or without (no folder, or it could not be read). */
    var loaded = false
        private set
    @Volatile private var destroyed = false

    /** The tip pictures of a brush from the person's folder, or null (not one of ours, or it has none). */
    fun imagesFor(p: BrushPreset): FrozenBrushPngs? = images[p.id]?.takeIf { it.paths.isNotEmpty() }

    /** True for a brush that came from the person's folder. */
    fun owns(p: BrushPreset?): Boolean = p != null && p.id in images

    /** Reads the folder's brushes in the background; [changed] when they are in. */
    fun load() {
        JoyFolderHome.io().execute {
            val jf = JoyFolderHome.open(activity)
            val list = if (jf == null) emptyList() else try { SuppliesBrushes.load(jf) } catch (t: Throwable) {
                Log.w(TAG, "brushes not read", t); emptyList()
            }
            ui { publish(list) }
        }
    }

    fun ask() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { activity.startActivityForResult(intent, REQUEST) }
        catch (e: android.content.ActivityNotFoundException) { say("This device has nowhere to open files from") }
    }

    /** The picker's answer; true when it was ours. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST) return false
        val uri = data?.data
        if (resultCode == Activity.RESULT_OK && uri != null) bringIn(uri)
        return true
    }

    private fun bringIn(uri: Uri) {
        say("Bringing the brushes in…")
        JoyFolderHome.io().execute {
            val sentence = try { importNow(uri) } catch (t: Throwable) {
                Log.w(TAG, "import failed", t); "Those brushes could not come in. Nothing was added."
            }
            ui { say(sentence) }
        }
    }

    private fun importNow(uri: Uri): String {
        val r = activity.contentResolver
        var name: String? = null
        var size = -1L
        r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > BrushFileImport.MAX_BYTES) return "That file is too big to be a brush set JoyBrush can read."
        val bytes = r.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(256 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > BrushFileImport.MAX_BYTES) return "That file is too big to be a brush set JoyBrush can read."
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return "That file could not be opened."
        val outcome = BrushFileImport.run(name, bytes)
        outcome.reasons.forEach { Log.i(TAG, "left out: $it") }
        outcome.brushes.forEach { b -> b.warnings.forEach { Log.i(TAG, "${b.preset.name}: $it") } }
        if (outcome.brushes.isEmpty()) return outcome.sentence
        val jf = JoyFolderHome.open(activity)
            ?: return "JoyBrush keeps brushes in your Joy Creator folder. Choose the folder in Setup, then try again."
        val written = SuppliesBrushes.add(jf, outcome.brushes)
        if (written.isEmpty()) return "Those brushes could not be saved in your folder. Nothing was added."
        val list = SuppliesBrushes.load(jf)
        ui { publish(list) }
        return outcome.sentence
    }

    private fun publish(list: List<SuppliesBrushes.Brush>) {
        brushes = list.map { it.preset }
        images = list.associate { it.preset.id to it.images }
        loaded = true
        changed()
    }

    /**
     * The canvas's picture lookup with the folder's brushes in front of whatever lookup it had: wrap once per pick,
     * never inside an earlier wrapper (the Unibrush controller swaps its own lookup in and out).
     */
    fun pngs(inner: ((BrushPreset) -> FrozenBrushPngs?)?): (BrushPreset) -> FrozenBrushPngs? =
        Lookup(images, if (inner is Lookup) inner.inner else inner)

    private class Lookup(val mine: Map<String, FrozenBrushPngs>, val inner: ((BrushPreset) -> FrozenBrushPngs?)?) :
        (BrushPreset) -> FrozenBrushPngs? {
        override fun invoke(p: BrushPreset): FrozenBrushPngs? = mine[p.id] ?: inner?.invoke(p)
    }

    fun destroy() { destroyed = true }

    private fun ui(block: () -> Unit) = activity.runOnUiThread { if (!destroyed && !activity.isFinishing) block() }

    companion object {
        const val REQUEST = 4106
        private const val TAG = "JoyBrushImport"
    }
}
