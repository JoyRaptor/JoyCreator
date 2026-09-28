package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30

/** A compiled and linked shader program with cached uniform locations. GL thread only. */
class GlProgram(vertexSrc: String, fragmentSrc: String, private val label: String) {

    val id: Int
    private val uniforms = HashMap<String, Int>()

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs)
        GLES30.glAttachShader(id, fs)
        GLES30.glLinkProgram(id)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(id)
            GLES30.glDeleteProgram(id)
            throw IllegalStateException("link failed ($label): $log")
        }
    }

    fun use() = GLES30.glUseProgram(id)

    fun loc(name: String): Int = uniforms.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun release() = GLES30.glDeleteProgram(id)

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            throw IllegalStateException("compile failed ($label, ${if (type == GLES30.GL_VERTEX_SHADER) "vertex" else "fragment"}): $log")
        }
        return s
    }
}
