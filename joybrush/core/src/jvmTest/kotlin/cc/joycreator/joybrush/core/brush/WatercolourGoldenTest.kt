package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import java.io.File
import kotlin.math.abs
import kotlinx.serialization.json.*
import kotlin.test.*

/** Exact index and <=1e-4 absolute error on all outputs of all 1,746 authoritative POC stamps. */
class WatercolourGoldenTest {
    private val fixture by lazy {
        Json.parseToJsonElement(File(joybrushRoot(), "core/src/jvmTest/resources/watercolour/poc-golden.json").readText()).jsonObject
    }
    private val format = Json { ignoreUnknownKeys = true }

    @Test fun allThreePocPathsMatchEveryStamp() {
        val spec = format.decodeFromJsonElement(WatercolourSpec.serializer(), fixture.getValue("settings"))
        assertEquals("joybrush.watercolour.poc-golden.v1", fixture.getValue("format").jsonPrimitive.content)
        assertEquals(3, fixture.getValue("paths").jsonArray.size)
        for ((pathIndex, value) in fixture.getValue("paths").jsonArray.withIndex()) {
            val path = value.jsonObject
            val dabber = WatercolourDabber(spec, path.getValue("seed").jsonPrimitive.long)
            val points = path.getValue("points").jsonArray.map {
                val p = it.jsonObject
                WatercolourDabber.Point(p.getValue("x").jsonPrimitive.double,
                    p.getValue("y").jsonPrimitive.double, p.getValue("p").jsonPrimitive.double,
                    p.getValue("t").jsonPrimitive.double)
            }
            val actual = dabber.begin(points.first()) + points.drop(1).flatMap(dabber::add) + dabber.finish()
            val expected = path.getValue("dabs").jsonArray
            assertEquals(expected.size, actual.size, "path $pathIndex count")
            for ((index, dab) in actual.withIndex()) {
                val row = expected[index].jsonArray.map { it.jsonPrimitive.double }
                assertEquals(row[0].toInt(), dab.sampleIndex, "path $pathIndex stamp $index index")
                val numbers = listOf(dab.x.toDouble(), dab.y.toDouble(), dab.radius.toDouble() * 2,
                    dab.angle.toDouble(), dab.flow.toDouble(), dab.bandWeight0.toDouble(),
                    dab.bandWeight1.toDouble(), dab.bandWeight2.toDouble(), dab.band0.toDouble(),
                    dab.band1.toDouble(), dab.band2.toDouble())
                for (j in numbers.indices) {
                    val error = abs(numbers[j] - row[j + 1])
                    assertTrue(error <= 1e-4, "path $pathIndex stamp $index column ${j+1}: ${numbers[j]} != ${row[j+1]} (error $error)")
                }
            }
            val offsets = path.getValue("backdropOffset").jsonArray
            assertEquals(offsets[0].jsonPrimitive.double, dabber.backdropOffsetX, 1e-12)
            assertEquals(offsets[1].jsonPrimitive.double, dabber.backdropOffsetY, 1e-12)
        }
    }

    @Test fun shippedPresetIsExactlyApprovedSettings() {
        val preset = BrushJson.decodeChecked(File(joybrushRoot(), "brushes/watercolour/brush.json").readText())
        val approved = format.decodeFromJsonElement(WatercolourSpec.serializer(), fixture.getValue("settings"))
        assertEquals(approved, preset.watercolour)
        assertEquals(approved, preset.watercolourRuntime(), "untouched picker knobs must preserve approved Double values")
        assertEquals(ENGINE_WATERCOLOUR, preset.engine)
        assertEquals(BLEND_MULTIPLY, preset.blend)
        assertEquals(.8f, preset.opacity.base)
        assertEquals(.89f, preset.flow.base)
    }

    @Test fun floatPenSamplesAlsoMatchExactSourceTrace() {
        val spec = format.decodeFromJsonElement(WatercolourSpec.serializer(), fixture.getValue("settings"))
        for ((pathIndex, value) in fixture.getValue("penPaths").jsonArray.withIndex()) {
            val path = value.jsonObject
            val samples = path.getValue("samples").jsonArray.map { value ->
                val p = value.jsonObject
                PenSample(p.getValue("x").jsonPrimitive.float, p.getValue("y").jsonPrimitive.float,
                    p.getValue("timeMs").jsonPrimitive.double, p.getValue("pressure").jsonPrimitive.float,
                    p.getValue("tilt").jsonPrimitive.float)
            }
            val actual = WatercolourDabber(spec, path.getValue("seed").jsonPrimitive.long).replay(samples)
            val expected = path.getValue("dabs").jsonArray
            assertEquals(expected.size, actual.size, "phone path $pathIndex count")
            for ((index, dab) in actual.withIndex()) {
                val row = expected[index].jsonArray.map { it.jsonPrimitive.double }
                assertEquals(row[0].toInt(), dab.sampleIndex, "phone path $pathIndex stamp $index index")
                val numbers = listOf(dab.x.toDouble(), dab.y.toDouble(), dab.radius.toDouble() * 2,
                    dab.angle.toDouble(), dab.flow.toDouble(), dab.bandWeight0.toDouble(),
                    dab.bandWeight1.toDouble(), dab.bandWeight2.toDouble(), dab.band0.toDouble(),
                    dab.band1.toDouble(), dab.band2.toDouble())
                for (j in numbers.indices) assertTrue(abs(numbers[j] - row[j + 1]) <= 1e-4,
                    "phone path $pathIndex stamp $index column ${j+1}: ${numbers[j]} != ${row[j+1]}")
            }
        }
    }
}
