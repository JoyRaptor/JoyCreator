package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.blend.BlendRgb
import cc.joycreator.joybrush.core.doc.BlendMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JB-2.20b — everything about the GPU blend path that can be checked WITHOUT a GPU.
 *
 * The other half, that the shader gives the Studio's answers, cannot run here (a JVM test cannot run a
 * fragment shader). It is `joybrush/tools/blend_gpu_check.js`: 853,524 comparisons of `jb_composite.frag`
 * against the Studio's Java through the generated golden table, on a real GL driver. And the third half,
 * that the generated GLSL is the Studio's, is `tools/blend-glsl/gen_blend_glsl.sh --check`.
 */
class BlendGlslShapeTest {

    private val shaders = ShaderLibrary()

    @Test
    fun theGeneratedFileTakesTheModeAsAParameterNotAUniform() {
        // The header comment talks ABOUT #version, main and uniforms; only the code may not have them.
        val text = shaders.source("jb_blend.glsl").lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        assertTrue(
            text.contains("vec3 blendPix(vec3 b, vec3 s, float uBlendMode) {"),
            "a per-layer uniform would need 27 programs; the mode must be a parameter"
        )
        assertFalse(Regex("""(?m)^\s*uniform\b""").containsMatchIn(text), "jb_blend.glsl declares functions only")
        assertFalse(text.contains("#version"), "the includer owns #version")
        assertFalse(text.contains("void main"), "no main in an include")
    }

    @Test
    fun theGeneratedFileSaysItIsGeneratedAndHowToRegenerateIt() {
        val head = shaders.source("jb_blend.glsl").lineSequence().take(6).joinToString("\n")
        assertTrue(head.contains("GENERATED FILE, DO NOT EDIT BY HAND"))
        assertTrue(head.contains("gen_blend_glsl.sh"))
    }

    @Test
    fun theCompositeShaderCompilesFromTheSharedIncludeAndHandlesErase() {
        val text = shaders.source("jb_composite.frag")
        assertTrue(text.startsWith("#version 300 es"))
        assertTrue(text.contains("vec3 blendPix("), "the include was not expanded")
        assertTrue(text.contains("uniform float u_mode;"))
        assertTrue(text.contains("u_mode < -0.5"), "ERASE_BELOW has no Studio code and is its own branch")
    }

    @Test
    fun everyModeHasExactlyOneGpuCodeAndOnlyEraseIsOutsideTheStudiosRange() {
        val codes = BlendMode.entries.associateWith { BlendCodes.codeOf(it) }
        val studio = codes.filterKeys { it != BlendMode.ERASE_BELOW }
        assertEquals(26, studio.size)
        assertEquals((0..25).map { it.toFloat() }.toSet(), studio.values.toSet(), "the Studio's codes 0..25, each once")
        assertEquals(BlendCodes.ERASE_CODE, codes.getValue(BlendMode.ERASE_BELOW))
        assertTrue(BlendCodes.ERASE_CODE < 0f && studio.values.none { it == BlendCodes.ERASE_CODE })
        // The mapping is BlendRgb's name-keyed one, never the enum ordinal (wrong for 22 of the 27).
        for ((mode, code) in studio) assertEquals(BlendRgb.codeOf(mode).toFloat(), code, mode.name)
        assertTrue(BlendMode.entries.count { it.ordinal.toFloat() == codes.getValue(it) } < 27, "ordinal must not be the code")
    }

    @Test
    fun theGpuAndTheCpuImplementTheSameSetOfNames() {
        assertEquals(BlendMode.entries.map { it.name }.toSet(), BlendCodes.supported.map { it.name }.toSet())
        assertEquals(27, BlendCodes.supported.size)
    }

    @Test
    fun anAllNormalStackNeverLeavesTheFixedFunctionPath() {
        // The maths half of Decision 5: NORMAL's blend term is the source, so the composite formula
        //   co = sa(1-da)Cs + sa*da*Cs + (1-sa)da*Cb  reduces to  sa*Cs + (1-sa)*da*Cb  = premultiplied source-over.
        val cs = floatArrayOf(0.8f, 0.3f, 0.1f); val cb = floatArrayOf(0.2f, 0.9f, 0.5f)
        for (sa in listOf(0f, 0.25f, 0.5f, 1f)) for (da in listOf(0f, 0.5f, 1f)) for (ch in 0..2) {
            val composite = sa * (1 - da) * cs[ch] + sa * da * cs[ch] + (1 - sa) * da * cb[ch]
            val sourceOver = sa * cs[ch] + (1 - sa) * (da * cb[ch])
            assertEquals(sourceOver, composite, 1e-6f, "sa=$sa da=$da ch=$ch")
        }
    }
}
