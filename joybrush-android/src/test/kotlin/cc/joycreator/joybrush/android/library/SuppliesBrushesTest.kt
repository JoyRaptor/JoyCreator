package cc.joycreator.joybrush.android.library

import cc.joycreator.joybrush.androidkit.BrushFileImport
import cc.joycreator.joybrush.androidkit.MaterializedBrushImports
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.SampledTipSpec
import cc.joycreator.joybrush.core.chrome.BrushShelf
import com.fadcam.joyfolder.FileJoyFs
import com.fadcam.joyfolder.JoyFolder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** BRUSH-IMPORT step 2: imported brushes become files in Supplies/Brushes and come back as the drawer's Imported shelf. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SuppliesBrushesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val png = MaterializedBrushImports.pngCodec.encodeAlpha(2, 2, byteArrayOf(0, 64, 127, -1))
    private fun imported(name: String) = BrushFileImport.Brush(
        BrushPreset(id = "imported.x", name = name, size = Param(9f), sourceFormat = "abr")
            .let { it.copy(tip = it.tip.copy(sampled = SampledTipSpec(listOf("tips/one.png")))) },
        FrozenBrushPngs.snapshot(mapOf("tips/one.png" to png)), emptyList())

    @Test fun importedBrushesAreWrittenAsReadableFilesAndReadBackOnTheImportedShelf() {
        val jf = JoyFolder.open(FileJoyFs(tmp.root))
        val written = SuppliesBrushes.add(jf, listOf(imported("Kyle Ink"), imported("Kyle Ink"), imported("Dry/Rake")))
        assertEquals(listOf("Supplies/Brushes/Kyle Ink.brush.json", "Supplies/Brushes/Kyle Ink 2.brush.json"),
            written.take(2))
        assertEquals(3, written.size)
        assertTrue(written.all { java.io.File(tmp.root, it).isFile })

        val back = SuppliesBrushes.load(JoyFolder.open(FileJoyFs(tmp.root)))
        assertEquals(3, back.size)
        assertEquals(setOf("Kyle Ink", "Kyle Ink 2"), back.map { it.preset.name }.filter { it.startsWith("Kyle") }.toSet())
        assertEquals(back.size, back.map { it.preset.id }.toSet().size)
        assertTrue(back.all { it.preset.id.startsWith(SuppliesBrushes.ID_PREFIX) })
        assertArrayEquals(png, back.first().images.requirePng("tips/one.png"))
        assertTrue(back.all { BrushShelf.kindOf(it.preset) == BrushShelf.Kind.IMPORTED })
    }

    @Test fun aBrokenOrForeignFileInTheFolderIsSkippedNotFatal() {
        val jf = JoyFolder.open(FileJoyFs(tmp.root))
        SuppliesBrushes.add(jf, listOf(imported("Good")))
        val dir = java.io.File(tmp.root, "Supplies/Brushes")
        java.io.File(dir, "Broken.brush.json").writeText("{ not a brush")
        java.io.File(dir, "notes.txt").writeText("hello")
        val back = SuppliesBrushes.load(JoyFolder.open(FileJoyFs(tmp.root)))
        assertEquals(listOf("Good"), back.map { it.preset.name })
    }

    @Test fun readingAFolderWithNoBrushesCreatesNothing() {
        val back = SuppliesBrushes.load(JoyFolder.open(FileJoyFs(tmp.root)))
        assertTrue(back.isEmpty())
        assertTrue(!java.io.File(tmp.root, "Supplies").exists())
    }

    @Test fun aRenamedFileIsARenamedBrush() {
        val a = SuppliesBrushes.named(BrushPreset(id = "x", name = "Old", size = Param(4f)), "New name.brush.json")
        assertEquals("New name", a.name)
        assertNotEquals(a.id, SuppliesBrushes.named(BrushPreset(id = "x", name = "Old", size = Param(4f)), "New name 2.brush.json").id)
    }
}
