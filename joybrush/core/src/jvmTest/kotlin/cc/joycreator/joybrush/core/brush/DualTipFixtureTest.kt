package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.paint.TipMath
import cc.joycreator.joybrush.core.paint.TipShape
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exports the actual baked masks and CPU coverage for the production shader oracle. */
class DualTipFixtureTest {
    @Test fun writeActualDualBakeAndCpuCoverageForProductionShaderCheck() {
        val primaryMasks=listOf(
            ImageMask.fromR8(1,1,byteArrayOf(-1)),
            ImageMask.fromR8(3,2,byteArrayOf(0,64,-1,-1,-128,32)),
        )
        val primary=SampledTipSet(listOf("primary1.png","primary2.png"),primaryMasks)
        val atlas=primary.atlas(64)
        val secondary=SampledTipSet(listOf("secondary.png"),listOf(
            ImageMask.fromR8(3,2,byteArrayOf(0,-1,64,-1,32,-128))))
        val secondaryShape=QuadShapeSpec(points=listOf(QuadPoint(-.8f,-1f),QuadPoint(1f,-.6f),
            QuadPoint(.65f,1f),QuadPoint(-1f,.7f)),roundness=.3f,aspect=-.2f)
        val mainShape=QuadShapeSpec(points=listOf(QuadPoint(-1f,-.8f),QuadPoint(.85f,-1f),
            QuadPoint(1f,.75f),QuadPoint(-.7f,1f)),roundness=.42f)
        val mainQuad=TipQuads.interpolate(mainShape,null,0f)
        val custom=TipProfileSpec(TipProfile.custom,listOf(listOf(0f,1f),listOf(.4f,.7f),listOf(1f,0f)))
        val baked=ArrayList<DualMask>()
        val caseValues=ArrayList<JsonObject>()
        for(source in 0..1) for(mode in DualMode.values()) {
            val recipe=DualTipSpec(shape=if(source==0) secondaryShape else null,
                image=if(source==1) DualImageSpec("secondary.png") else null,
                scale=.25f,spacing=1f,scatter=.45f,mode=mode)
            val seed=111L
            val dual=DualTips.bake(recipe,seed,if(source==1) secondary else null)
            assertTrue(dual.matches(recipe,seed));assertEquals(256,dual.width);assertEquals(256,dual.height)
            val maskIndex=baked.size;baked+=dual
            for(variant in 0..3) {
                val sampled=when(variant){2->SampledStamp(primary,0,1f);3->SampledStamp(primary,1,1f);else->null}
                val tip=TipShape(
                    sampled=sampled,dual=dual,
                    aspect=when(variant){0->.4f;1->.7f;2->.2f;else->-.55f},
                    taper=when(variant){0->.3f;1->.6f;2->.35f;else->.4f},
                    anchor=when(variant){0->.3f;1->.6f;2->.2f;else->.4f},
                    hardness=.2f,
                    profile=when(variant){1->custom;2->null;else->TipProfileSpec(TipProfile.gaussian)},
                    quad=if(variant==2)null else mainQuad,
                    fadeAmount=when(variant){0->.4f;1->.7f;2->.25f;else->.5f},
                    fadeAngle=if(variant==1)-.4f else .8f,
                )
                val angle=if(variant==1)-.55f else .36f
                val radius=if(variant==2)15f else 19f
                val base=tip.copy(dual=null)
                val coverage=FloatArray(64*64){i->TipMath.coverage(i%64+.5f-32f,i/64+.5f-32f,radius,angle,tip)}
                val plain=FloatArray(64*64){i->TipMath.coverage(i%64+.5f-32f,i/64+.5f-32f,radius,angle,base)}
                assertTrue(coverage.all{it.isFinite()&&it in 0f..1f})
                assertTrue(coverage.indices.all{coverage[it]<=plain[it]+1e-6f})
                val prepared=tip.quad?.let(QuadMath::prepare)
                caseValues+=buildJsonObject {
                    put("name","${if(source==0)"shape" else "image"}-mode${mode.code}-variant$variant")
                    put("secondaryKind",if(source==0)"shape" else "image")
                    put("width",64);put("height",64);put("radius",radius);put("angle",angle)
                    put("maskIndex",maskIndex);put("mode",dual.mode.code);put("period",dual.period)
                    put("index",if(sampled==null)-1 else if(variant==2)0 else 1)
                    put("imageWidth",sampled?.mask?.width ?: 1);put("imageHeight",sampled?.mask?.height ?: 1)
                    put("aspect",tip.aspect);put("taper",tip.taper);put("anchor",tip.anchor)
                    put("hardness",tip.hardness);put("minPx",1f)
                    put("fade",tip.fadeAmount);put("fadeAngle",tip.fadeAngle)
                    put("flow",if(variant==1).35f else .8f);put("cap",if(variant==2).65f else .9f)
                    put("ink",buildJsonArray{add(.3f);add(.6f);add(.8f)})
                    put("profile",tip.profile?.kind?.code ?: 0)
                    put("lut",buildJsonArray{(tip.profile?.let(TipProfiles::samples)?:FloatArray(256)).forEach{add(it)}})
                    put("quad",buildJsonArray{for(i in 0 until 4){add(if(prepared!=null&&i<prepared.count)prepared.x(i)else 0f);add(if(prepared!=null&&i<prepared.count)prepared.y(i)else 0f)}})
                    put("quadMeta",buildJsonArray{add(prepared?.roundness?:0f);add(prepared?.normalizationScale?:1f);add(0f);add(if(prepared==null)0 else if(prepared.circle)5 else prepared.count)})
                    put("coverage",buildJsonArray{coverage.forEach{add(it)}})
                    put("baseCoverage",buildJsonArray{plain.forEach{add(it)}})
                }
            }
        }
        val fixture=buildJsonObject {
            put("producer","Actual DualTips.bake R8 bytes and TipMath.coverage")
            put("atlasWidth",atlas.width);put("atlasHeight",atlas.height)
            put("atlasBytes",buildJsonArray{atlas.bytes().forEach{add(it.toInt()and 255)}})
            put("rects",buildJsonArray{atlas.rects().forEach{r->add(buildJsonArray{add(r.u0);add(r.v0);add(r.u1);add(r.v1)})}})
            put("masks",buildJsonArray{baked.forEach{mask->add(buildJsonObject{
                put("width",mask.width);put("height",mask.height);put("period",mask.period);put("mode",mask.mode.code)
                put("bytes",buildJsonArray{mask.bytes().forEach{add(it.toInt()and 255)}})
            })}})
            put("cases",JsonArray(caseValues))
        }
        val core=generateSequence(File("").absoluteFile){it.parentFile}.first{
            File(it,"src/commonMain/kotlin/cc/joycreator/joybrush/core").isDirectory}
        File(core,"build/unibrush-dual-fixture.json").writeText(fixture.toString())
    }
}
