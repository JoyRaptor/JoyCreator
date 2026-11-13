package cc.joycreator.joybrush.android.library

import android.util.Log
import cc.joycreator.joybrush.androidkit.BrushFileImport
import cc.joycreator.joybrush.androidkit.brush.BrushFile
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import com.fadcam.joyfolder.FileKind
import com.fadcam.joyfolder.Fingerprint
import com.fadcam.joyfolder.JoyFolder
import com.fadcam.joyfolder.Placement
import com.fadcam.joyfolder.ReadableNames

/**
 * BRUSH-IMPORT step 2: the person's own brushes, one [BrushFile] each in `Supplies/Brushes/` of their Joy Creator folder
 * (FileKind.BRUSH). Imported brushes are written here; the drawer reads them back on its Imported shelf. A brush's name
 * and id come from its FILE name, so renaming or copying a file in the folder is all it takes to rename or duplicate a
 * brush, and two files can never fight over one id. A file that cannot be read is skipped and logged, never fatal.
 * Folder thread only (JoyFolderHome.io()): SAF calls block.
 */
object SuppliesBrushes {
    private const val TAG = "JoyBrushSupplies"
    /** Ids of brushes from the folder: never the same as a built-in's. */
    const val ID_PREFIX = "supplies."

    class Brush(val preset: BrushPreset, val images: FrozenBrushPngs, val rel: String)

    private fun dir(jf: JoyFolder): String = jf.prepareDir(Placement.What.SUPPLY, null, null, FileKind.BRUSH)

    /** Every readable brush in `Supplies/Brushes/`, in name order. */
    fun load(jf: JoyFolder): List<Brush> {
        val dir = dir(jf)
        val out = ArrayList<Brush>()
        for (e in jf.fs.list(dir).sortedBy { it.name.lowercase() }) {
            if (e.dir || !e.name.lowercase().endsWith(BrushFile.ENDING)) continue
            val rel = Placement.join(dir, e.name)
            try {
                val read = BrushFile.read(jf.readAll(rel).decodeToString())
                out += Brush(named(read.preset, e.name), read.images, rel)
            } catch (t: Throwable) {
                Log.w(TAG, "skipped $rel", t)
            }
        }
        return out
    }

    /** Writes each imported brush as its own file; returns the files written. A brush that will not write is logged. */
    fun add(jf: JoyFolder, brushes: List<BrushFileImport.Brush>): List<String> {
        val dir = dir(jf)
        val written = ArrayList<String>()
        for (b in brushes) {
            try {
                val name = jf.freeName(dir, ReadableNames.sanitize(b.preset.name).trim().ifEmpty { "Brush" } + BrushFile.ENDING)
                val rel = Placement.join(dir, name)
                jf.writeAtomic(rel, BrushFile.write(named(b.preset, name), b.images).encodeToByteArray())
                jf.fs.stat(rel)?.let { st -> jf.index.put(rel, st.size, st.modified, Fingerprint.of(jf.fs, rel), null) }
                written += rel
            } catch (t: Throwable) {
                Log.w(TAG, "brush ${b.preset.name} not written", t)
            }
        }
        if (written.isNotEmpty()) jf.saveIndex()
        return written
    }

    /** The brush as its file names it: "Kyle Ink 2.brush.json" is "Kyle Ink 2", id "supplies.kyle-ink-2". */
    internal fun named(p: BrushPreset, fileName: String): BrushPreset {
        val name = fileName.removeSuffix(BrushFile.ENDING).removeSuffix(BrushFile.ENDING.uppercase()).trim()
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "brush" }
        return p.copy(id = ID_PREFIX + slug + "-" + Integer.toHexString(name.hashCode()), name = name)
    }
}
