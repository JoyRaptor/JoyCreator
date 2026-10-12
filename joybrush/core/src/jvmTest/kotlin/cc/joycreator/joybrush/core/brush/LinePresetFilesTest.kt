package cc.joycreator.joybrush.core.brush

import java.io.File
import kotlin.test.*

/** The approved ten-brush line catalogue. It is staged separately from the existing drawer. */
class LinePresetFilesTest {
    private fun presets(): Map<String, BrushPreset> = File(joybrushRoot(), "brushes/lines")
        .listFiles()!!.filter { it.extension == "json" }.associate { file ->
            file.nameWithoutExtension to BrushJson.decodeChecked(file.readText())
        }

    @Test fun exactlyTenNamedPresetsValidateAndRoundTrip() {
        val all=presets()
        assertEquals(setOf("line_pen","brush_pen","fineliner","wire","sketch_pencil",
            "shading_pencil","chisel_marker","watercolour","dash_pen","fill_pen"),all.keys)
        assertEquals(10,all.values.map { it.id }.toSet().size)
        all.forEach { (name,preset) ->
            assertEquals(emptyList(),BrushValidate.validate(preset),name)
            assertEquals(preset,BrushJson.decodeChecked(BrushJson.encode(preset)),name)
        }
    }

    @Test fun PresetsExpressTheirDifferentJobsAndPreserveApprovedWatercolour() {
        val all=presets()
        assertTrue(all.getValue("line_pen").size.inputs.any { it.input == BrushInput.speed })
        assertTrue(all.getValue("brush_pen").line!!.taperOut>all.getValue("line_pen").line!!.taperOut)
        assertEquals(emptyList(),all.getValue("fineliner").size.inputs)
        assertEquals(1f,all.getValue("wire").line!!.screenWidthPx)
        assertTrue(all.getValue("sketch_pencil").paperGrain.enabled)
        assertTrue(all.getValue("shading_pencil").size.inputs.any { it.input == BrushInput.tilt })
        assertEquals("wash",all.getValue("chisel_marker").accumulate)
        assertTrue(all.getValue("chisel_marker").tip.angle.inputs.any { it.input == BrushInput.lean })
        assertTrue(all.getValue("dash_pen").line!!.dashOff>0f)
        assertEquals(ENGINE_FILL,all.getValue("fill_pen").engine)
        val approved=BrushJson.decodeChecked(File(joybrushRoot(),"brushes/watercolour/brush.json").readText())
        val wc=all.getValue("watercolour")
        assertEquals(approved.watercolourRuntime(),wc.watercolourRuntime())
        assertEquals(BLEND_MULTIPLY,wc.blend)
        assertNull(wc.line)
    }
}
