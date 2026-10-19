package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.paint.TipMath
import cc.joycreator.joybrush.core.paint.TipShape
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TipQuadFixtureTest {
    @Test fun writeQuadShaderFixture() {
        fun points(vararg xy: Float) = xy.toList().chunked(2).map { QuadPoint(it[0], it[1]) }
        val shapes = listOf(
            "square" to QuadShapeSpec(),
            "trapezoid" to QuadShapeSpec(points(-.5f,-1f,.6f,-1f,1f,1f,-1f,1f)),
            "concave" to QuadShapeSpec(points(-1f,-1f,1f,-.8f,-.25f,0f,-.8f,1f)),
            "crossed" to QuadShapeSpec(points(-1f,-1f,1f,1f,1f,-1f,-1f,1f)),
            "triangle" to QuadShapeSpec(points(-1f,-.8f,1f,-.8f,0f,1f,0f,1f)),
            "collinear" to QuadShapeSpec(points(-1f,0f,0f,0f,1f,0f,0f,1f)),
            "offcenter" to QuadShapeSpec(points(.1f,-.7f,.9f,-.7f,.9f,.1f,.1f,.1f)),
            "thin" to QuadShapeSpec(points(-1f,-.015f,1f,-.01f,.8f,.02f,-.9f,.01f)),
            "nearStraight" to QuadShapeSpec(points(-1f,0f,0f,-.000001f,1f,0f,0f,1f)),
            "collapsed" to QuadShapeSpec(points(0f,0f,0f,0f,0f,0f,0f,0f)),
            "backtrack" to QuadShapeSpec(points(-1f,0f,1f,0f,-1f,0f,0f,1f)),
            "microscopic" to QuadShapeSpec(points(-1e-30f,-1e-30f,1e-30f,-1e-30f,1e-30f,1e-30f,-1e-30f,1e-30f)),
            "sliver" to QuadShapeSpec(points(-1f,-.000001f,1f,-.000001f,1f,.000001f,-1f,.000001f)),
        )
        val profile = TipProfileSpec(TipProfile.custom, listOf(listOf(0f,1f),listOf(.3f,.75f),listOf(.7f,.1f),listOf(1f,0f)))
        val cases = buildJsonArray {
            for ((name, spec) in shapes) for (round in listOf(0f, 1e-20f, .45f, 1f)) for (mix in listOf(0f, .4f, 1f)) {
                val shape = spec.copy(roundness = round)
                val quad = TipQuads.interpolate(shape, null, 0f)
                val prepared = QuadMath.prepare(quad)
                val second = TipProfileSpec(if (mix == 1f) TipProfile.hard else TipProfile.custom,
                    listOf(listOf(0f,.6f),listOf(.5f,.5f),listOf(1f,0f)))
                val tip = TipShape(quad = quad, profile = profile, profileB = second, profileMix = mix,
                    hardness = if (mix == 1f) .65f else 0f, aspect = if (mix == .4f) .45f else 0f,
                    fadeAmount = if (mix == .4f) .7f else 0f, fadeAngle = -.8f)
                val values = FloatArray(64 * 64) { i -> TipMath.coverage(i % 64 + .5f - 32f, i / 64 + .5f - 32f, 25f, .4f, tip) }
                assertTrue(values.all { it.isFinite() && it in 0f..1f }, "$name $round $mix")
                add(buildJsonObject {
                    put("name", "$name-round$round-mix$mix"); put("width",64); put("height",64)
                    put("radius",25f); put("angle",.4f); put("aspect",tip.aspect); put("corner",2f); put("taper",0f)
                    put("hardness",tip.hardness); put("minPx",1f); put("profile",profile.kind.code)
                    put("fade",tip.fadeAmount); put("fadeAngle",tip.fadeAngle)
                    put("lut",buildJsonArray { TipProfiles.samples(profile).forEach { add(it) } })
                    put("profileB",second.kind.code); put("lutB",buildJsonArray { TipProfiles.samples(second).forEach { add(it) } })
                    put("quad",buildJsonArray {
                        for (i in 0 until 4) { add(if (i < prepared.count) prepared.x(i) else 0f); add(if (i < prepared.count) prepared.y(i) else 0f) }
                    })
                    put("quadMeta",buildJsonArray {
                        add(prepared.roundness); add(prepared.normalizationScale); add(mix)
                        add(when { prepared.empty -> 1; prepared.circle -> 5; else -> prepared.count })
                    })
                    put("coverage",buildJsonArray { values.forEach { add(it) } })
                })
            }
        }
        val core = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,"src/commonMain/kotlin/cc/joycreator/joybrush/core").isDirectory }
        File(core,"build/unibrush-quad-fixture.json").writeText(buildJsonObject { put("cases",cases) }.toString())
    }
}
