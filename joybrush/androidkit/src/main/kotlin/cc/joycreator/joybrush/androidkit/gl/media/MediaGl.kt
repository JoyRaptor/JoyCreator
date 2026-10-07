package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.GlProgram
import cc.joycreator.joybrush.androidkit.gl.ShaderLibrary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** A GL texture name for [MediaProgram.set] (so an Int texture is never mistaken for an int uniform). */
@JvmInline value class Tex(val id: Int)

/**
 * A media shader program that sets uniforms BY THEIR DECLARED TYPE, the way the lab's `use()` does: the same
 * value maps work for both, and a misspelt or optimised-away uniform is skipped instead of crashing. Samplers get
 * texture units in declaration order. GL thread only.
 */
class MediaProgram(shaders: ShaderLibrary, vert: String, frag: String, label: String) {
    val program = GlProgram(shaders.source(vert), shaders.source(frag), label)

    private class Uniform(val loc: Int, val type: Int, val unit: Int)

    private val uniforms = HashMap<String, Uniform>()

    init {
        val count = IntArray(1)
        GLES30.glGetProgramiv(program.id, GLES30.GL_ACTIVE_UNIFORMS, count, 0)
        var units = 0
        val size = IntArray(1)
        val type = IntArray(1)
        for (i in 0 until count[0]) {
            val name = GLES30.glGetActiveUniform(program.id, i, size, 0, type, 0).substringBefore('[')
            val unit = if (type[0] == GLES30.GL_SAMPLER_2D) units++ else -1
            uniforms[name] = Uniform(GLES30.glGetUniformLocation(program.id, name), type[0], unit)
        }
    }

    fun use(values: Map<String, Any?>) {
        program.use()
        for ((name, v) in values) set(name, v ?: continue)
    }

    fun set(name: String, v: Any) {
        val u = uniforms[name] ?: return
        when (u.type) {
            GLES30.GL_FLOAT -> GLES30.glUniform1f(u.loc, num(v))
            GLES30.GL_INT, GLES30.GL_BOOL -> GLES30.glUniform1i(u.loc, if (v is Boolean) (if (v) 1 else 0) else num(v).toInt())
            GLES30.GL_FLOAT_VEC2 -> vec(v, 2).let { GLES30.glUniform2f(u.loc, it[0], it[1]) }
            GLES30.GL_FLOAT_VEC3 -> vec(v, 3).let { GLES30.glUniform3f(u.loc, it[0], it[1], it[2]) }
            GLES30.GL_FLOAT_VEC4 -> vec(v, 4).let { GLES30.glUniform4f(u.loc, it[0], it[1], it[2], it[3]) }
            GLES30.GL_SAMPLER_2D -> {
                val t = (v as? Tex)?.id ?: error("$name needs a Tex")
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + u.unit)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
                GLES30.glUniform1i(u.loc, u.unit)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            }
            else -> error("${program.id}: unsupported uniform type for $name")
        }
    }

    fun release() = program.release()

    private fun num(v: Any): Float = when (v) {
        is Number -> v.toFloat()
        is Boolean -> if (v) 1f else 0f
        else -> error("not a number: $v")
    }

    private fun vec(v: Any, n: Int): FloatArray = when (v) {
        is FloatArray -> v
        is DoubleArray -> FloatArray(n) { v[it].toFloat() }
        is IntArray -> FloatArray(n) { v[it].toFloat() }
        is List<*> -> FloatArray(n) { (v[it] as Number).toFloat() }
        else -> error("not a vector: $v")
    }.also { require(it.size >= n) { "vector too short" } }
}

/** Texture and framebuffer helpers for the media engine (GL thread only). */
internal object MediaTex {
    fun make(w: Int, h: Int, internal: Int = GLES30.GL_RGBA16F, format: Int = GLES30.GL_RGBA, type: Int = GLES30.GL_HALF_FLOAT,
             filter: Int = GLES30.GL_LINEAR, data: java.nio.Buffer? = null): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, data)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    /** Full float, NEAREST (full floats are not linearly filterable everywhere; the shaders filter by hand). */
    fun full(w: Int, h: Int) = make(w, h, GLES30.GL_RGBA32F, GLES30.GL_RGBA, GLES30.GL_FLOAT, GLES30.GL_NEAREST)

    fun fbo(vararg textures: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ids[0])
        textures.forEachIndexed { i, t ->
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0 + i, GLES30.GL_TEXTURE_2D, t, 0)
        }
        GLES30.glDrawBuffers(textures.size, IntArray(textures.size) { GLES30.GL_COLOR_ATTACHMENT0 + it }, 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "media framebuffer incomplete: 0x${status.toString(16)}" }
        return ids[0]
    }

    fun deleteTex(vararg t: Int) = GLES30.glDeleteTextures(t.size, t, 0)
    fun deleteFbo(vararg f: Int) = GLES30.glDeleteFramebuffers(f.size, f, 0)

    fun floats(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
}
