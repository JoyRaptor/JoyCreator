package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.gl.media.UniformSink
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.ScatterSpec
import cc.joycreator.joybrush.core.brush.TipProfile
import cc.joycreator.joybrush.core.brush.TipProfileSpec
import cc.joycreator.joybrush.core.brush.TipProfiles
import cc.joycreator.joybrush.core.brush.TipSpec
import cc.joycreator.joybrush.core.brush.QuadShapeSpec
import cc.joycreator.joybrush.core.brush.QuadPoint
import cc.joycreator.joybrush.core.brush.TipQuads
import cc.joycreator.joybrush.core.brush.QuadMath
import cc.joycreator.joybrush.core.brush.ColorDab
import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkReplay
import java.nio.FloatBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** Exercises CPU packing and the uniform sink only; no Android GL entry point is called. */
class UnibrushStampPassTest {
    private class Grains : StampPass.GrainSource {
        override val placeholder = 7
        override fun textureFor(asset: String): Int? = null
        override fun paperSize(asset: String): Int = 1
    }

    private class Rec : UniformSink {
        private val locations = LinkedHashMap<String, Int>()
        private val names = HashMap<Int, String>()
        val integers = LinkedHashMap<String, Int>()
        val vectors = ArrayList<Pair<String, List<Float>>>()
        val triples = LinkedHashMap<String, List<Float>>()
        fun loc(name: String): Int = locations.getOrPut(name) {
            (locations.size + 1).also { names[it] = name }
        }
        override fun uniform1f(loc: Int, a: Float) {}
        override fun uniform2f(loc: Int, a: Float, b: Float) {}
        override fun uniform3f(loc: Int, a: Float, b: Float, c: Float) { triples[names.getValue(loc)] = listOf(a,b,c) }
        override fun uniform4f(loc: Int, a: Float, b: Float, c: Float, d: Float) {
            vectors.add(names.getValue(loc) to listOf(a, b, c, d))
        }
        override fun uniform1i(loc: Int, a: Int) { integers[names.getValue(loc)] = a }
        override fun bindTexture(unit: Int, tex: Int) {}
    }

    private fun look(pass: StampPass, tip: TipShape = TipShape()): StampPass.StrokeLook = pass.lookFor(
        tip, GrainMath.StrokeGrain(GrainMath.GrainUniforms.OFF, GrainMath.GrainUniforms.OFF),
        null, null, null, null, null,
    )

    private fun bits(buffer: FloatBuffer): List<Int> = List(buffer.limit()) { buffer.get(it).toRawBits() }

    @Test fun fadeTailLeavesEveryLegacyPrefixBitInPlaceAndResolvesMissingValues() {
        val pass = StampPass(Grains())
        val look = look(pass, TipShape(aspect = -.4f, hardness = .7f, fadeAmount = .4f, fadeAngle = 1.1f))
        val dabs = listOf(
            Dab(11f, 12f, 13f, angle = .2f, flow = .3f, cap = .4f,
                travelX = .5f, travelY = -.6f, aspect = -.2f, hardness = .9f,
                tipDepth = .8f, paperDepth = .6f, anchor = .1f, fadeAmount = .3f, fadeAngle = .7f),
            Dab(21f, 22f, 23f, tilt = .4f, azimuth = -.2f),
            Dab(31f, 32f, 33f, fadeAmount = 0f, fadeAngle = Float.POSITIVE_INFINITY),
            Dab(41f, 42f, 43f, fadeAmount = 2f, fadeAngle = -1.2f),
            Dab(51f, 52f, 53f, fadeAmount = -.5f, fadeAngle = .2f),
        )
        // The pass reuses its buffer: snapshot the prefix BEFORE asking for the draw layout.
        val prefix = bits(pass.pack(dabs, look))
        val draw = bits(pass.packForDraw(dabs, look))
        assertEquals(17 * dabs.size, prefix.size)
        assertEquals(19 * dabs.size, draw.size)
        assertEquals(prefix, draw.take(prefix.size), "fade data must follow the entire 17-float-per-dab prefix")
        val tail = listOf(.3f, .7f, .4f, 1.1f, 0f, 1.1f, 1f, -1.2f, 0f, .2f)
        assertEquals(tail.map { it.toRawBits() }, draw.drop(prefix.size))
        assertTrue(draw.drop(prefix.size).all { Float.fromBits(it).isFinite() })
    }

    @Test fun zeroFadeAndLegacyDabsDoNotUploadATailAfterAFadedBatch() {
        val pass = StampPass(Grains())
        val look = look(pass)
        val faded = listOf(Dab(1f, 2f, 3f, fadeAmount = .8f, fadeAngle = .6f))
        assertEquals(19, pass.packForDraw(faded, look).limit())
        val profile = TipProfileSpec(TipProfile.gaussian)
        val noFade = listOf(Dab(1f, 2f, 3f), Dab(4f, 5f, 6f, fadeAmount = 0f, profile = profile))
        val expected = bits(pass.pack(noFade, look))
        val actual = bits(pass.packForDraw(noFade, look))
        assertEquals(34, actual.size)
        assertEquals(expected, actual, "a reused large buffer must expose only the legacy prefix")
    }

    @Test fun builtInProfileKindsUploadTheirCodesAndLegacyResetsToZero() {
        val pass = StampPass(Grains())
        val gl = Rec()
        for ((kind, expected) in listOf(TipProfile.hard to 1, TipProfile.linear to 2,
            TipProfile.smooth to 3, TipProfile.gaussian to 4)) {
            pass.setProfileUniforms(gl, gl::loc, TipProfileSpec(kind))
            assertEquals(expected, gl.integers["u_tipProfile"])
        }
        assertTrue(gl.vectors.isEmpty(), "built-in profiles need no LUT uploads")
        pass.setProfileUniforms(gl, gl::loc, null)
        assertEquals(0, gl.integers["u_tipProfile"])
    }

    @Test fun customProfileUploadsAll256SamplesIn64OrderedVectorsAndCannotLeakIntoLegacy() {
        val pass = StampPass(Grains())
        val gl = Rec()
        val profile = TipProfileSpec(TipProfile.custom,
            listOf(listOf(0f, 1f), listOf(.2f, .9f), listOf(.7f, .1f), listOf(1f, 0f)))
        val expected = TipProfiles.samples(profile).map { it.toRawBits() }
        repeat(2) {
            gl.vectors.clear()
            pass.setProfileUniforms(gl, gl::loc, profile)
            assertEquals(5, gl.integers["u_tipProfile"])
            assertEquals((0 until 64).map { "u_tipProfileLut[$it]" }, gl.vectors.map { it.first })
            assertEquals(expected, gl.vectors.flatMap { it.second }.map { it.toRawBits() })
        }
        gl.vectors.clear()
        pass.setProfileUniforms(gl, gl::loc, null)
        assertEquals(0, gl.integers["u_tipProfile"], "a previous custom lookup must be disabled")
        assertTrue(gl.vectors.isEmpty())
    }

    @Test fun newestDabProfileOverridesTheStrokeFallbackAndMissingProfileRestoresIt() {
        val pass = StampPass(Grains())
        val gl = Rec()
        val strokeLook = look(pass, TipShape(profile = TipProfileSpec(TipProfile.linear)))
        pass.setStrokeUniforms(gl, gl::loc, strokeLook,
            Dab(1f, 2f, 3f, profile = TipProfileSpec(TipProfile.gaussian)), 256, 1f)
        assertEquals(4, gl.integers["u_tipProfile"])
        pass.setStrokeUniforms(gl, gl::loc, strokeLook, Dab(1f, 2f, 3f), 256, 1f)
        assertEquals(2, gl.integers["u_tipProfile"])
        pass.setStrokeUniforms(gl, gl::loc, look(pass), Dab(1f, 2f, 3f), 256, 1f)
        assertEquals(0, gl.integers["u_tipProfile"])
    }

    @Test fun theSharedProfileSurvivesPlacementScatterAndRecordedWidthScaling() {
        val profile = TipProfileSpec(TipProfile.gaussian)
        val brush = BrushPreset(id = "profile", name = "Profile", size = Param(12f),
            tip = TipSpec(profile = profile), scatter = ScatterSpec(amount = Param(.2f), count = 2))
        val record = StrokeRecord("line", brush.id, 91L, 0f, 1f,
            listOf(PenSample(10f, 10f, 0.0), PenSample(50f, 20f, 25.0)))
        val original = InkReplay.dabs(record, brush)
        val scaled = InkReplay.dabs(record.copy(widthScale = 2f), brush)
        assertTrue(original.size > 20, "scatter and placement must produce a nonempty corpus")
        assertEquals(original.size, scaled.size)
        assertTrue(original.all { it.profile === profile } && scaled.all { it.profile === profile },
            "no profile/LUT should be copied per dab")
        for ((a, b) in original.zip(scaled)) {
            assertEquals(a.x, b.x)
            assertEquals(a.y, b.y)
            assertEquals(a.radius * 2f, b.radius)
        }
    }

    @Test fun shapeTailFollowsTheCompleteFadeTailAndLegacyPrefixAndResets() {
        val pass = StampPass(Grains())
        val look = look(pass)
        val square = TipQuads.interpolate(QuadShapeSpec(), null, 0f)
        val triangle = TipQuads.interpolate(QuadShapeSpec(points = listOf(QuadPoint(-1f,-1f),QuadPoint(1f,-1f),
            QuadPoint(0f,1f),QuadPoint(0f,1f)), roundness = .35f), null, 0f)
        val dabs = listOf(Dab(1f,2f,3f,quad = square,profileMix = .25f,fadeAmount = .3f,fadeAngle = .2f),
            Dab(4f,5f,6f,quad = triangle,profileMix = .75f), Dab(7f,8f,9f))
        val prefix = bits(pass.pack(dabs, look))
        val draw = pass.packForDraw(dabs, look)
        assertEquals(3 * (17 + 2 + 12), draw.limit())
        assertEquals(prefix, bits(draw).take(51))
        assertEquals(.3f, draw.get(51)); assertEquals(.2f, draw.get(52))
        val start = 57
        for ((index, q) in listOf(square, triangle).withIndex()) {
            val shape = QuadMath.prepare(q)
            val offset = start + index * 12
            for (i in 0 until 4) {
                assertEquals(if (i < shape.count) shape.x(i) else 0f, draw.get(offset + i * 2))
                assertEquals(if (i < shape.count) shape.y(i) else 0f, draw.get(offset + i * 2 + 1))
            }
            assertEquals(shape.roundness, draw.get(offset + 8))
            assertEquals(shape.normalizationScale, draw.get(offset + 9))
            assertEquals(dabs[index].profileMix, draw.get(offset + 10))
            assertEquals(if (shape.circle) 5f else 3f, draw.get(offset + 11))
        }
        assertEquals(0f, draw.get(start + 24 + 11), "a mixed legacy instance must disable quad coverage")
        assertEquals(17, pass.packForDraw(listOf(Dab(1f,2f,3f)), look).limit(), "no stale shape tail")
        assertEquals(29, pass.packForDraw(listOf(dabs.first().copy(fadeAmount = 0f)), look).limit())
    }

    @Test fun theSecondProfileUploadsItsOwnLookupAndResetsIndependently() {
        val pass = StampPass(Grains()); val gl = Rec()
        val a = TipProfileSpec(TipProfile.custom)
        val b = TipProfileSpec(TipProfile.custom, listOf(listOf(0f,.25f),listOf(1f,0f)))
        pass.setProfileUniforms(gl, gl::loc, a)
        pass.setProfileUniforms(gl, gl::loc, b, secondary = true)
        assertEquals(5, gl.integers["u_tipProfileB"])
        val bVectors = gl.vectors.filter { it.first.startsWith("u_tipProfileLutB[") }
        assertEquals(64, bVectors.size)
        assertEquals(TipProfiles.samples(b).toList(), bVectors.flatMap { it.second })
        pass.setProfileUniforms(gl, gl::loc, null, secondary = true)
        assertEquals(0, gl.integers["u_tipProfileB"])
        assertEquals(5, gl.integers["u_tipProfile"], "clearing B cannot change A")
    }

    @Test fun rgbTailFollowsEveryEarlierTailAndCannotLeakIntoLegacy() {
        val pass = StampPass(Grains()); val look = look(pass)
        val quad = TipQuads.interpolate(QuadShapeSpec(),null,0f)
        val list = listOf(Dab(1f,2f,3f,quad=quad,fadeAmount=.7f,fadeAngle=.2f,color=ColorDab(.1f,.4f,.8f)),
            Dab(4f,5f,6f))
        val legacyPrefix = bits(pass.pack(list,look))
        val draw = pass.packForDraw(list,look)
        assertEquals(70,draw.limit())
        assertEquals(legacyPrefix,bits(draw).take(34))
        assertEquals(listOf(.1f,.4f,.8f,1f,0f,0f,0f,0f),List(8) { draw.get(62+it) })
        assertEquals(17,pass.packForDraw(listOf(Dab(1f,2f,3f)),look).limit())
        assertEquals(21,pass.packForDraw(listOf(Dab(1f,2f,3f,color=ColorDab(1f,0f,0f))),look).limit())
        assertFailsWith<IllegalArgumentException> {
            pass.packForDraw(listOf(Dab(1f,2f,3f,color=ColorDab(Float.NaN,0f,0f))),look)
        }
    }

    @Test fun fixedColourFallbackIsFrozenInTheLookAndUploadedForMixedDabs() {
        val pass = StampPass(Grains()); val gl = Rec()
        val look = pass.lookFor(TipShape(),GrainMath.StrokeGrain(GrainMath.GrainUniforms.OFF,GrainMath.GrainUniforms.OFF),
            null,null,null,null,null,baseColor=ColorDab(.2f,.4f,.7f))
        pass.setStrokeUniforms(gl,gl::loc,look,Dab(1f,2f,3f),256,1f)
        assertEquals(listOf(.2f,.4f,.7f),gl.triples["u_stampBaseColor"])
        pass.setStrokeUniforms(gl,gl::loc,look(pass),Dab(1f,2f,3f),256,1f)
        assertEquals(listOf(0f,0f,0f),gl.triples["u_stampBaseColor"])
    }
}
