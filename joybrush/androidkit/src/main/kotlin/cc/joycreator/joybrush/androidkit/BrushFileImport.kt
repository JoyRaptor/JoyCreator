package cc.joycreator.joybrush.androidkit

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import cc.joycreator.joybrush.core.brush.imports.BrushFileKind
import cc.joycreator.joybrush.core.brush.imports.MaterializedImportLibrary
import cc.joycreator.joybrush.core.brush.imports.MypaintImport

/**
 * JBX-18: one picked brush file in, the brushes it holds out, and ONE sentence for the artist. The importers already
 * exist ([MaterializedBrushImports], [MypaintImport]); this only chooses which one runs ([BrushFileKind]) and says the
 * outcome in plain words. An importer's own reasons are technical, so they go to the log, never to the screen: the
 * sentence names the brushes that could not come across and nothing else. Call off the UI thread.
 */
object BrushFileImport {
    /** A brush ready to keep: its recipe and the tip pictures it needs (none for a procedural brush). */
    class Brush(val preset: BrushPreset, val images: FrozenBrushPngs, val warnings: List<String>)

    class Outcome(
        val brushes: List<Brush>,
        /** Names of the brushes in the file that could not be converted. */
        val leftOut: List<String>,
        /** Why each was left out, for the log only. */
        val reasons: List<String>,
        /** The sentence for the toast. Never technical, never empty. */
        val sentence: String,
    )

    /** The most a picked file may be; a bigger one is refused before it is read whole. */
    const val MAX_BYTES = 64L * 1024 * 1024

    fun run(name: String?, bytes: ByteArray): Outcome {
        val title = name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "this file"
        val kinds = BrushFileKind.candidates(name, bytes.copyOf(minOf(bytes.size, 16)))
        if (kinds.isEmpty()) return nothing("\"$title\" isn't a brush file JoyBrush can read. It takes Photoshop, Procreate, Krita and MyPaint brushes.")
        val prefix = "imported." + title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "brush" }
        val reasons = ArrayList<String>()
        for (kind in kinds) {
            val lib = try { convert(kind, bytes, prefix, title) } catch (e: Exception) {
                reasons += "${kind.name}: ${e.message}"; continue
            }
            if (lib.brushes.isEmpty() && kinds.size > 1 && kind != kinds.last()) { reasons += lib.reasons; continue }
            return describe(title, lib.brushes, lib.leftOut, reasons + lib.reasons)
        }
        return Outcome(emptyList(), emptyList(), reasons,
            "None of the brushes in \"$title\" could come across. Nothing was added.")
    }

    private class Converted(val brushes: List<Brush>, val leftOut: List<String>, val reasons: List<String>)

    private fun convert(kind: BrushFileKind, bytes: ByteArray, prefix: String, title: String): Converted = when (kind) {
        BrushFileKind.ABR -> of(MaterializedBrushImports.abr(bytes, prefix))
        BrushFileKind.PROCREATE_BRUSH -> of(MaterializedBrushImports.procreateBrush(bytes, prefix))
        BrushFileKind.PROCREATE_SET -> of(MaterializedBrushImports.procreateSet(bytes, prefix))
        BrushFileKind.KRITA_PRESET -> of(MaterializedBrushImports.kritaKpp(bytes, prefix))
        BrushFileKind.KRITA_BUNDLE -> of(MaterializedBrushImports.kritaBundle(bytes, prefix))
        BrushFileKind.MYPAINT -> MypaintImport.convert(bytes.decodeToString(), prefix, title).let {
            Converted(listOf(Brush(it.preset, FrozenBrushPngs.empty(), it.warnings)), emptyList(), emptyList())
        }
    }

    private fun of(lib: MaterializedImportLibrary) = Converted(
        lib.brushes.map { Brush(it.preset, it.images, it.warnings) },
        lib.refused.map { it.name.ifBlank { "brush ${it.index + 1}" } },
        lib.refused.map { "${it.name}: ${it.reason}" })

    private fun describe(title: String, brushes: List<Brush>, leftOut: List<String>,
                         reasons: List<String>): Outcome {
        val n = brushes.size
        val added = when (n) {
            0 -> "None of the brushes in \"$title\" could come across. Nothing was added."
            1 -> "Added 1 brush from \"$title\"."
            else -> "Added $n brushes from \"$title\"."
        }
        val missing = if (n == 0 || leftOut.isEmpty()) "" else {
            val shown = leftOut.take(3).joinToString(", ")
            val more = if (leftOut.size > 3) " and ${leftOut.size - 3} more" else ""
            " ${leftOut.size} could not come across: $shown$more."
        }
        return Outcome(brushes, leftOut, reasons, added + missing)
    }

    private fun nothing(sentence: String) = Outcome(emptyList(), emptyList(), emptyList(), sentence)
}
