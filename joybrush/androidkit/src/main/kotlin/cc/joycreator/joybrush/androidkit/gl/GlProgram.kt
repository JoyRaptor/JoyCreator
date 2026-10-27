package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import android.opengl.EGL14

/** A compiled and linked shader program with cached uniform locations. GL thread only. */
class GlProgram(vertexSrc: String, fragmentSrc: String, private val label: String) {

    val id: Int
    private val uniforms = HashMap<String, Int>()

    init {
        id = GlProgramConstruction.build(vertexSrc, fragmentSrc, label, GlesProgramDriver())
    }

    fun use() = GLES30.glUseProgram(id)

    fun loc(name: String): Int = uniforms.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun release() = GLES30.glDeleteProgram(id)

}

private class GlesProgramDriver : GlProgramDriver {
    private val context = EGL14.eglGetCurrentContext()
    private val status = IntArray(1)
    override fun current() = context != EGL14.EGL_NO_CONTEXT && EGL14.eglGetCurrentContext() == context
    override fun createShader(type: Int) = GLES30.glCreateShader(type)
    override fun compile(shader: Int, source: String) {
        GLES30.glShaderSource(shader, source); GLES30.glCompileShader(shader)
    }
    override fun shaderCompiled(shader: Int): Boolean {
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0); return status[0] != 0
    }
    override fun shaderLog(shader: Int): String = GLES30.glGetShaderInfoLog(shader)
    override fun createProgram() = GLES30.glCreateProgram()
    override fun attach(program: Int, shader: Int) = GLES30.glAttachShader(program, shader)
    override fun link(program: Int) = GLES30.glLinkProgram(program)
    override fun linked(program: Int): Boolean {
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0); return status[0] != 0
    }
    override fun programLog(program: Int): String = GLES30.glGetProgramInfoLog(program)
    override fun deleteShader(shader: Int) = GLES30.glDeleteShader(shader)
    override fun deleteProgram(program: Int) = GLES30.glDeleteProgram(program)
}
