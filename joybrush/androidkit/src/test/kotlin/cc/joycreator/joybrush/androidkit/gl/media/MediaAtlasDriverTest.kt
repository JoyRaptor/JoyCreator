package cc.joycreator.joybrush.androidkit.gl.media

import cc.joycreator.joybrush.androidkit.gl.ShaderLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Media Atlas v2 B1, the Kotlin driver's GL-free parts: JB_ATLAS builds, the page-table uniforms, sized uploads. */
class MediaAtlasDriverTest {

    @Test fun definesGoRightAfterTheVersionLineAndNoDefinesIsTheSameText() {
        val files = mapOf(
            "a.frag" to "#version 300 es\nprecision highp float;\n#include \"b.glsl\"\nvoid main() {}\n",
            "b.glsl" to "#ifdef JB_ATLAS\nint atlas;\n#endif\n",
        )
        val lib = ShaderLibrary { files.getValue(it) }
        val plain = lib.source("a.frag")
        assertSame(plain, lib.source("a.frag", emptyList()), "no defines: the window build's very text")
        val atlas = lib.source("a.frag", listOf("JB_ATLAS", "JB_PLAIN_TARGET"))
        assertEquals("#version 300 es\n#define JB_ATLAS\n#define JB_PLAIN_TARGET\n" + plain.substringAfter("#version 300 es\n"), atlas)
        assertSame(atlas, lib.source("a.frag", listOf("JB_ATLAS", "JB_PLAIN_TARGET")), "cached per define set")
        assertTrue(plain != lib.source("a.frag", listOf("JB_ATLAS")))
        assertEquals("#version 300 es\n#define X\n", ShaderLibrary.withDefines("#version 300 es", listOf("X")), "a #version with no newline")
        assertFailsWith<IllegalArgumentException> { ShaderLibrary.withDefines("void main(){}", listOf("X")) }
        assertFailsWith<IllegalArgumentException> { ShaderLibrary.withDefines("#version 300 es\n", listOf("X 1")) }
    }

    @Test fun everyMediaShaderBuildsBothWaysAndOnlyTheAtlasBuildDeclaresTheTable() {
        val lib = ShaderLibrary()
        for (name in listOf("jb_media_quad.vert", "jb_dry_dab.vert", "jb_wet_dab.vert", "jb_media_erase_dab.vert", "jb_media_screen.vert",
                "jb_media_copy.frag", "jb_media_apply.frag", "jb_media_erase_apply.frag", "jb_media_erase_dab.frag", "jb_dry_dab.frag",
                "jb_wet_dab.frag", "jb_wet_bead.frag", "jb_wet_flux.frag", "jb_wet_update.frag", "jb_media_bake.frag", "jb_media_render.frag",
                "jb_paste_dab.frag", "jb_paste_brush.frag")) {
            val window = lib.source("media/$name")
            val atlas = lib.source("media/$name", listOf("JB_ATLAS"))
            assertTrue(window.startsWith("#version 300 es"), name)
            assertTrue(atlas.lines()[1] == "#define JB_ATLAS", name)
            assertTrue("#ifdef JB_ATLAS" in window || "defined(JB_ATLAS)" in window, "$name is routed through jb_media_atlas.glsl")
            assertEquals(window, atlas.replaceFirst("#define JB_ATLAS\n", ""), "$name: the define is the only difference")
        }
    }

    private class Rec : UniformSink {
        val log = ArrayList<String>()
        override fun uniform1f(loc: Int, a: Float) { log += "1f $loc $a" }
        override fun uniform2f(loc: Int, a: Float, b: Float) { log += "2f $loc" }
        override fun uniform3f(loc: Int, a: Float, b: Float, c: Float) { log += "3f $loc" }
        override fun uniform4f(loc: Int, a: Float, b: Float, c: Float, d: Float) { log += "4f $loc" }
        override fun uniform1i(loc: Int, a: Int) { log += "1i $loc $a" }
        override fun uniform4i(loc: Int, a: Int, b: Int, c: Int, d: Int) { log += "4i $loc $a $b $c $d" }
        override fun bindTexture(unit: Int, tex: Int) { log += "bind $unit $tex" }
    }

    @Test fun thePageTableUniformsAreTypedAndWrittenOnce() {
        val rec = Rec()
        val bank = UniformBank(mapOf(
            "u_atlasTable" to UniformSlot(3, Gl.INT_SAMPLER_2D, 5),
            "u_atlasGrid" to UniformSlot(4, Gl.INT_VEC4, -1),
            "u_atlasShift" to UniformSlot(6, Gl.INT, -1),
            "u_p0" to UniformSlot(7, Gl.SAMPLER_2D, 0),
        ), rec, "test")
        bank.itex("u_atlasTable", 42); bank.i4("u_atlasGrid", 0, 0, 4, 4); bank.f1("u_atlasShift", 8)
        bank.itex("u_atlasTable", 42); bank.i4("u_atlasGrid", 0, 0, 4, 4); bank.f1("u_atlasShift", 8)
        assertEquals(listOf("bind 5 42", "1i 3 5", "4i 4 0 0 4 4", "1i 6 8", "bind 5 42"), rec.log, "the table is rebound every pass; values once")
        bank.f1("u_atlasShift", 7); bank.i4("u_atlasGrid", 0, 0, 4, 3)
        assertEquals(listOf("1i 6 7", "4i 4 0 0 4 3"), rec.log.takeLast(2))
        assertFailsWith<IllegalStateException> { bank.itex("u_p0", 1) }
        assertFailsWith<IllegalStateException> { bank.tex("u_atlasTable", 1) }
        assertFailsWith<IllegalStateException> { bank.i4("u_atlasShift", 0, 0, 0, 0) }
        bank.itex("u_absent", 9); bank.i4("u_absent", 1, 2, 3, 4)   // a name the program does not have is skipped, as for every type
        assertEquals(7, rec.log.size)
    }

    @Test fun aSizedUploadStagesOnlyThePrefixOfAReusedArray() {
        val up = FloatUploadBuffer()
        val big = FloatArray(64) { it.toFloat() }
        val b = up.upload(big, 10)
        assertEquals(0, b.position()); assertEquals(10, b.limit())
        assertEquals((0 until 10).map { it.toFloat() }, (0 until 10).map { b.get(it) })
        assertEquals(64, up.upload(big).limit(), "the whole-array form is unchanged")
        assertFailsWith<IllegalArgumentException> { up.upload(big, 65) }
    }
}

/** GL enum values (android.jar is compile-only here, as in MediaUniformsParityTest). */
private object Gl {
    const val INT = 0x1404
    const val INT_VEC4 = 0x8B55
    const val SAMPLER_2D = 0x8B5E
    const val INT_SAMPLER_2D = 0x8DCA
}
