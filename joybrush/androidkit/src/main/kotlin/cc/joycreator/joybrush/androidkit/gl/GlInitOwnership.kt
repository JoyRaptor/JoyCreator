package cc.joycreator.joybrush.androidkit.gl

/** Preallocated ledgers for one creation attempt, never populated from old engine fields. */
internal class GlInitOwnership(
    private val current: () -> Boolean,
    private val delete: (Kind, Int) -> Unit,
    programs: Int = 0, shaders: Int = 0, buffers: Int = 0,
    vertexArrays: Int = 0, framebuffers: Int = 0, textures: Int = 0,
) {
    enum class Kind { PROGRAM, SHADER, BUFFER, VERTEX_ARRAY, FRAMEBUFFER, TEXTURE }
    val programNames = IntArray(programs)
    val shaderNames = IntArray(shaders)
    val bufferNames = IntArray(buffers)
    val vertexArrayNames = IntArray(vertexArrays)
    val framebufferNames = IntArray(framebuffers)
    val textureNames = IntArray(textures)

    fun rollback(failure: Throwable) {
        retire(Kind.TEXTURE, textureNames, failure)
        retire(Kind.FRAMEBUFFER, framebufferNames, failure)
        retire(Kind.VERTEX_ARRAY, vertexArrayNames, failure)
        retire(Kind.BUFFER, bufferNames, failure)
        retire(Kind.PROGRAM, programNames, failure)
        retire(Kind.SHADER, shaderNames, failure)
    }

    private fun retire(kind: Kind, names: IntArray, failure: Throwable) {
        for (i in names.lastIndex downTo 0) {
            val name = names[i]
            names[i] = 0 // An attempted release is never retried or redirected to a new epoch.
            if (name == 0) continue
            for (j in 0 until i) if (names[j] == name) names[j] = 0
            try { if (current()) delete(kind, name) }
            catch (cleanup: Throwable) {
                if (cleanup !== failure) try { failure.addSuppressed(cleanup) } catch (_: Throwable) {}
            }
        }
    }
}
