package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.paint.TipMath
import cc.joycreator.joybrush.core.paint.TipShape
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SampledTipFixtureTest {
    @Test fun writeActualCpuImageCoverageAndAtlasForProductionShaderCheck() {
        val masks=listOf(
            ImageMask.fromR8(1,1,byteArrayOf(-1)),
            ImageMask.fromR8(2,1,byteArrayOf(0,-1)),
            ImageMask.fromR8(3,2,byteArrayOf(0,64,-1,-1,-128,0)),
            ImageMask.fromR8(4,4,ByteArray(16) { if(it%5==0 || it%3==0) -1 else 0 }),
            ImageMask.fromR8(2,7,ByteArray(14) { (it*19).toByte() }),
            ImageMask.fromArgb(2,2,intArrayOf(0,0x80FFFFFF.toInt(),0xFF000000.toInt(),0xFF00FF00.toInt()),SampleCoverage.luminance),
        )
        val set=SampledTipSet(masks.indices.map { "tip$it.png" },masks)
        val atlas=set.atlas(64)
        val custom=TipProfileSpec(TipProfile.custom,listOf(listOf(0f,1f),listOf(.4f,.7f),listOf(1f,0f)))
        val cases=buildJsonArray {
            for(index in masks.indices) for(variant in 0..2) {
                val stamp=SampledStamp(set,index,1f)
                val quad=if(variant==2) TipQuads.interpolate(QuadShapeSpec(roundness=.55f),null,0f) else null
                val prepared=quad?.let(QuadMath::prepare)
                val tip=TipShape(sampled=stamp,aspect=if(variant==1) .65f else 0f,
                    taper=if(variant==1) .4f else 0f,anchor=if(variant==1) .6f else 0f,
                    hardness=0f,profile=when(variant) {1 -> TipProfileSpec(TipProfile.gaussian);2 -> custom;else -> null},
                    quad=quad,fadeAmount=if(variant==2) .7f else 0f,fadeAngle=.8f)
                val angle=if(variant==0) 0f else .4f
                val values=FloatArray(64*64) { i -> TipMath.coverage(i%64+.5f-32f,i/64+.5f-32f,15f,angle,tip) }
                assertTrue(values.all { it.isFinite() && it in 0f..1f })
                add(buildJsonObject {
                    put("name","image$index-variant$variant");put("width",64);put("height",64);put("radius",15f)
                    put("index",index);put("imageWidth",stamp.mask.width);put("imageHeight",stamp.mask.height)
                    put("angle",angle);put("aspect",tip.aspect);put("taper",tip.taper);put("anchor",tip.anchor)
                    put("hardness",tip.hardness);put("minPx",1f);put("fade",tip.fadeAmount);put("fadeAngle",tip.fadeAngle)
                    put("profile",tip.profile?.kind?.code ?: 0)
                    put("lut",buildJsonArray { (tip.profile?.let(TipProfiles::samples) ?: FloatArray(256)).forEach { add(it) } })
                    put("quad",buildJsonArray { for(i in 0 until 4) { add(if(prepared!=null && i<prepared.count) prepared.x(i) else 0f);add(if(prepared!=null && i<prepared.count) prepared.y(i) else 0f) } })
                    put("quadMeta",buildJsonArray { add(prepared?.roundness ?: 0f);add(prepared?.normalizationScale ?: 1f);add(0f);add(if(prepared==null) 0 else if(prepared.circle) 5 else prepared.count) })
                    put("coverage",buildJsonArray { values.forEach { add(it) } })
                })
            }
        }
        val fixture=buildJsonObject {
            put("atlasWidth",atlas.width);put("atlasHeight",atlas.height)
            put("atlasBytes",buildJsonArray { atlas.bytes().forEach { add(it.toInt() and 255) } })
            put("rects",buildJsonArray { atlas.rects().forEach { r -> add(buildJsonArray { add(r.u0);add(r.v0);add(r.u1);add(r.v1) }) } })
            put("cases",cases)
        }
        val core=generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it,"src/commonMain/kotlin/cc/joycreator/joybrush/core").isDirectory }
        File(core,"build/unibrush-sample-fixture.json").writeText(fixture.toString())
    }
}
