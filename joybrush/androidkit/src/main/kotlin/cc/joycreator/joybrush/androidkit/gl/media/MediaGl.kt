package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.GlProgram
import cc.joycreator.joybrush.androidkit.gl.ShaderLibrary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** A GL texture name (so an Int texture is never mistaken for an int value where it must be one or the other). */
@JvmInline value class Tex(val id: Int)

/**
 * Where [UniformBank] finally sends a write: GL on the device ([GlUniformSink]), a recorder in the JVM tests
 * (MediaUniformsParityTest) - the one seam that lets the uniform logic be proved without a GPU.
 */
internal interface UniformSink {
    fun uniform1f(loc: Int, a: Float)
    fun uniform2f(loc: Int, a: Float, b: Float)
    fun uniform3f(loc: Int, a: Float, b: Float, c: Float)
    fun uniform4f(loc: Int, a: Float, b: Float, c: Float, d: Float)
    fun uniform1i(loc: Int, a: Int)

    /** Binds [tex] on texture [unit] and leaves unit 0 active again, exactly as the map-based `use` always did. */
    fun bindTexture(unit: Int, tex: Int)
}

internal object GlUniformSink : UniformSink {
    override fun uniform1f(loc: Int, a: Float) = GLES30.glUniform1f(loc, a)
    override fun uniform2f(loc: Int, a: Float, b: Float) = GLES30.glUniform2f(loc, a, b)
    override fun uniform3f(loc: Int, a: Float, b: Float, c: Float) = GLES30.glUniform3f(loc, a, b, c)
    override fun uniform4f(loc: Int, a: Float, b: Float, c: Float, d: Float) = GLES30.glUniform4f(loc, a, b, c, d)
    override fun uniform1i(loc: Int, a: Int) = GLES30.glUniform1i(loc, a)
    override fun bindTexture(unit: Int, tex: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }
}

/** One active uniform of a linked program: where it lives, what the shader declared it as, and what we last wrote to it. */
internal class UniformSlot(val loc: Int, val type: Int, val unit: Int) {
    /** The last value written (floats in [f] for the float types, [i] for int/bool/sampler); meaningful only once [known]. */
    val f = FloatArray(4)
    var i = 0
    var known = false
}

/**
 * Typed, allocation-free uniform writes for ONE linked program, with a shadow copy of what GL holds.
 *
 * WHY the shadow copy is exact: a GL uniform keeps its value in the program object until it is written again (across
 * glUseProgram, draws, and other programs' writes). Every write to this program goes through this bank, so "the value
 * I last wrote" IS "the value GL holds"; writing the same bits again changes nothing and is skipped. That turns the
 * per-pass set of brush / paper / layer constants into a handful of compares without anyone having to argue which
 * uniform is constant when: a value that changed is simply written. Floats compare by raw bits, so -0f, NaN and every
 * other pattern are written whenever they differ. A fresh program starts with nothing known, so every uniform's first
 * set is always issued.
 *
 * Conversions are the ones the old map-based `MediaProgram.set` made, by the DECLARED type: a number written to a
 * float uniform is `toFloat()`; written to an int/bool uniform it is `toFloat().toInt()` (kept even for Int input, so
 * the rounding is the one that always happened); a Boolean is 1/0; a vector takes its components `toFloat()`. A name
 * the program does not have (optimised away, or belonging to another pass) is skipped, as before. A name with the wrong
 * declared type throws, as before. Textures are ALWAYS rebound (other code changes the bindings); only the sampler's
 * unit uniform, a constant, is written once. Names are looked up in a HashMap (String hashes are cached), the one cost
 * left per call besides the compare. GL thread only.
 */
internal class UniformBank(private val slots: Map<String, UniformSlot>, private val sink: UniformSink, private val label: String) {

    // ---- scalars ----
    fun f1(name: String, x: Float) {
        val s = slots[name] ?: return
        when (s.type) {
            GLES30.GL_FLOAT -> {
                if (s.known && s.f[0].toRawBits() == x.toRawBits()) return
                sink.uniform1f(s.loc, x); s.f[0] = x; s.known = true
            }
            GLES30.GL_INT, GLES30.GL_BOOL -> writeInt(s, x.toInt())
            else -> wrongType(name, "a number")
        }
    }
    fun f1(name: String, x: Double) = f1(name, x.toFloat())
    /** An Int goes through Float on purpose: the old path did `num(v).toInt()` for int uniforms, and that rounding must stay. */
    fun f1(name: String, x: Int) = f1(name, x.toFloat())
    fun b1(name: String, x: Boolean) = f1(name, if (x) 1f else 0f)

    private fun writeInt(s: UniformSlot, x: Int) {
        if (s.known && s.i == x) return
        sink.uniform1i(s.loc, x); s.i = x; s.known = true
    }

    // ---- vectors ----
    fun f2(name: String, x: Float, y: Float) {
        val s = slots[name] ?: return
        if (s.type != GLES30.GL_FLOAT_VEC2) wrongType(name, "a vec2")
        val f = s.f
        if (s.known && f[0].toRawBits() == x.toRawBits() && f[1].toRawBits() == y.toRawBits()) return
        sink.uniform2f(s.loc, x, y); f[0] = x; f[1] = y; s.known = true
    }
    fun f2(name: String, x: Double, y: Double) = f2(name, x.toFloat(), y.toFloat())

    fun f3(name: String, x: Float, y: Float, z: Float) {
        val s = slots[name] ?: return
        if (s.type != GLES30.GL_FLOAT_VEC3) wrongType(name, "a vec3")
        val f = s.f
        if (s.known && f[0].toRawBits() == x.toRawBits() && f[1].toRawBits() == y.toRawBits() && f[2].toRawBits() == z.toRawBits()) return
        sink.uniform3f(s.loc, x, y, z); f[0] = x; f[1] = y; f[2] = z; s.known = true
    }
    fun f3(name: String, v: FloatArray) { require(v.size >= 3) { "vector too short" }; f3(name, v[0], v[1], v[2]) }

    fun f4(name: String, x: Float, y: Float, z: Float, w: Float) {
        val s = slots[name] ?: return
        if (s.type != GLES30.GL_FLOAT_VEC4) wrongType(name, "a vec4")
        val f = s.f
        if (s.known && f[0].toRawBits() == x.toRawBits() && f[1].toRawBits() == y.toRawBits() &&
            f[2].toRawBits() == z.toRawBits() && f[3].toRawBits() == w.toRawBits()) return
        sink.uniform4f(s.loc, x, y, z, w); f[0] = x; f[1] = y; f[2] = z; f[3] = w; s.known = true
    }
    fun f4(name: String, v: FloatArray) { require(v.size >= 4) { "vector too short" }; f4(name, v[0], v[1], v[2], v[3]) }

    // ---- samplers ----
    /** Binds [tex] on the sampler's unit (units in declaration order, as before); the unit uniform itself is written once. */
    fun tex(name: String, tex: Int) {
        val s = slots[name] ?: return
        if (s.type != GLES30.GL_SAMPLER_2D) wrongType(name, "a sampler")
        sink.bindTexture(s.unit, tex)
        writeInt(s, s.unit)
    }

    private fun wrongType(name: String, wanted: String): Nothing =
        error("$label: $name is not $wanted (declared type differs)")
}

/**
 * A media shader program. Uniforms are written by DECLARED type through a [UniformBank]: `val u = prog.use()` makes the
 * program current and hands back its bank (see [MediaUniforms] for what each pass writes). Samplers get texture units
 * in declaration order. The program's active uniforms and their locations are read once, here, at link time. GL thread
 * only.
 */
class MediaProgram(shaders: ShaderLibrary, vert: String, frag: String, label: String) {
    val program = GlProgram(shaders.source(vert), shaders.source(frag), label)

    private val bank: UniformBank

    init {
        val slots = HashMap<String, UniformSlot>()
        val count = IntArray(1)
        GLES30.glGetProgramiv(program.id, GLES30.GL_ACTIVE_UNIFORMS, count, 0)
        var units = 0
        val size = IntArray(1)
        val type = IntArray(1)
        for (i in 0 until count[0]) {
            val name = GLES30.glGetActiveUniform(program.id, i, size, 0, type, 0).substringBefore('[')
            val unit = if (type[0] == GLES30.GL_SAMPLER_2D) units++ else -1
            slots[name] = UniformSlot(GLES30.glGetUniformLocation(program.id, name), type[0], unit)
        }
        bank = UniformBank(slots, GlUniformSink, label)
    }

    /** Makes this program current and returns its uniform bank. */
    internal fun use(): UniformBank { program.use(); return bank }

    fun release() = program.release()
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
