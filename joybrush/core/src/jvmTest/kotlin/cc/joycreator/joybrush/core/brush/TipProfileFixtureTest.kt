package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.paint.TipMath
import cc.joycreator.joybrush.core.paint.TipShape
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Actual CPU outputs for the headless shader oracle; no reimplementation of the tip in JS. */
class TipProfileFixtureTest {
    @Test fun writeProfileShaderFixture() {
        val cases = buildJsonArray {
            for (kind in listOf(null) + TipProfile.entries) for (hardness in listOf(0f, .7f, 1f)) for (shape in 0..3) {
                val profile = kind?.let { TipProfileSpec(it, if (it == TipProfile.custom)
                    listOf(listOf(0f, 1f), listOf(.25f, .85f), listOf(.65f, .1f), listOf(1f, 0f))
                    else listOf(listOf(0f, 1f), listOf(1f, 0f))) }
                val tip = TipShape(aspect = if (shape == 3) .78f else 0f,
                    corner = if (shape == 1) 8f else 2f, taper = if (shape == 2) .65f else 0f,
                    hardness = hardness, profile = profile, fadeAmount = if (shape >= 2) .85f else 0f, fadeAngle = .9f)
                val values = FloatArray(64 * 64) { i -> TipMath.coverage(i % 64 + .5f - 32f, i / 64 + .5f - 32f, 25f, .4f, tip) }
                assertTrue(values.all { it.isFinite() && it in 0f..1f })
                add(buildJsonObject {
                    put("name", "${kind?.name ?: "legacy"}-h$hardness-shape$shape")
                    put("width", 64); put("height", 64); put("radius", 25f); put("angle", .4f)
                    put("aspect", tip.aspect); put("corner", tip.corner); put("taper", tip.taper)
                    put("hardness", hardness); put("minPx", tip.minPx); put("profile", kind?.code ?: 0)
                    put("fade", tip.fadeAmount); put("fadeAngle", tip.fadeAngle)
                    put("lut", buildJsonArray { TipProfiles.samples(profile ?: TipProfileSpec()).forEach { add(it) } })
                    put("coverage", buildJsonArray { values.forEach { add(it) } })
                })
            }
        }
        val core = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "src/commonMain/kotlin/cc/joycreator/joybrush/core").isDirectory }
        val target = File(core, "build/unibrush-profile-fixture.json")
        target.parentFile.mkdirs()
        target.writeText(buildJsonObject { put("cases", cases) }.toString(), Charsets.UTF_8)
        assertTrue(target.length() > 10000)
    }
}
