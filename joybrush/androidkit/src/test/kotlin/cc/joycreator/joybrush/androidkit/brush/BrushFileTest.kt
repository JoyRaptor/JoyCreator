package cc.joycreator.joybrush.androidkit.brush

import cc.joycreator.joybrush.androidkit.BrushFileImport
import cc.joycreator.joybrush.androidkit.MaterializedBrushImports
import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.SampledTipSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BRUSH-IMPORT: one brush is one `.brush.json` file holding its recipe and its tip pictures. */
class BrushFileTest {
    private val png = MaterializedBrushImports.pngCodec.encodeAlpha(2, 2, byteArrayOf(0, 64, 127, -1))
    private val plain = BrushPreset(id = "imported.kyle.ink", name = "Kyle Ink", size = Param(9f), sourceFormat = "abr")
    private val sampled = plain.copy(tip = plain.tip.copy(sampled = SampledTipSpec(listOf("tips/one.png"))))

    @Test fun aBrushWithPicturesComesBackWholeFromOneFile() {
        val text = BrushFile.write(sampled, FrozenBrushPngs.snapshot(mapOf("tips/one.png" to png)))
        assertTrue(text.contains("\"assets\""))
        val back = BrushFile.read(text)
        assertEquals(sampled.id, back.preset.id)
        assertEquals(sampled.name, back.preset.name)
        assertEquals("abr", back.preset.sourceFormat)
        assertEquals(setOf("tips/one.png"), back.images.paths)
        assertContentEquals(png, back.images.requirePng("tips/one.png"))
    }

    @Test fun aBrushWithoutPicturesIsAnOrdinaryBrushFile() {
        val text = BrushFile.write(plain, FrozenBrushPngs.empty())
        assertFalse(text.contains("\"assets\""))
        assertEquals(plain.id, BrushFile.read(text).preset.id)
        assertTrue(BrushFile.read(text).images.paths.isEmpty())
    }

    @Test fun brokenFilesAreRefusedForTheLogNotMisread() {
        assertFailsWith<BrushException> { BrushFile.read("{not json") }
        val good = BrushFile.write(sampled, FrozenBrushPngs.snapshot(mapOf("tips/one.png" to png)))
        assertFailsWith<BrushException> { BrushFile.read(good.replace(Regex("\"tips/one.png\": \"[^\"]+\""), "\"tips/one.png\": \"%%%\"")) }
        assertFailsWith<BrushException> { BrushFile.read(good.replace("\"tips/one.png\": \"", "\"../escape.png\": \"")) }
    }

    @Test fun aPickedFileSaysWhatCameInInPlainWords() {
        val myb = """{"version": 3, "comment": "", "group": "", "parent_brush_name": "",
            "settings": {"radius_logarithmic": {"base_value": 1.5, "inputs": {}}}}"""
        val one = BrushFileImport.run("Soft charcoal.myb", myb.encodeToByteArray())
        assertEquals(1, one.brushes.size)
        assertEquals("Added 1 brush from \"Soft charcoal\".", one.sentence)
        val notBrush = BrushFileImport.run("holiday.jpg", byteArrayOf(-1, -40, -1, 0))
        assertTrue(notBrush.brushes.isEmpty())
        assertEquals("\"holiday\" isn't a brush file JoyBrush can read. It takes Photoshop, Procreate, Krita and MyPaint brushes.", notBrush.sentence)
        val broken = BrushFileImport.run("Broken.abr", byteArrayOf(0, 6, 0, 2, 1))
        assertTrue(broken.brushes.isEmpty())
        assertEquals("None of the brushes in \"Broken\" could come across. Nothing was added.", broken.sentence)
        assertTrue(broken.reasons.isNotEmpty())
    }
}
