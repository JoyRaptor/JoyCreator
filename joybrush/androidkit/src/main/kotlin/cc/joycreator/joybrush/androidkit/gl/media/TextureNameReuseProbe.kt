package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import org.json.JSONObject

/**
 * Does this phone's graphics driver hand a deleted texture name out again, and how soon? (JBX-35, the lost tiles of 10-31.)
 *
 * Why it matters: a texture deleted TWICE harms nobody unless, between the two deletes, the driver gave the same name to
 * someone else. Then the second delete takes the someone else's memory: a layer tile keeps a name with no storage, draws
 * black and cannot be read, which is what the four lost tiles looked like. The engine's double delete of 10-31
 * ([MediaLayerEngine.release] after a sleep) was fixed on 11-01, but [MediaFailureProbe]'s check of it never saw a name
 * reused, so it could not say whether that double delete was able to cause the loss at all. This measures the driver
 * itself, with no engine in the way.
 *
 * Debug bench only; GL thread, a current context. It makes and deletes only its own 1 x 1 textures.
 */
internal object TextureNameReuseProbe {
    fun measure(): JSONObject {
        val o = JSONObject()
        val one = IntArray(1)
        // The engine remembers what it bound: this leaves the unit and its binding exactly as it found them.
        val was = IntArray(2)
        GLES30.glGetIntegerv(GLES30.GL_ACTIVE_TEXTURE, was, 0)
        GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_2D, was, 1)

        // A: delete a name, ask for one at once.
        var immediate = 0
        repeat(IMMEDIATE) {
            val a = make(one)
            GLES30.glDeleteTextures(1, intArrayOf(a), 0)
            val b = make(one)
            if (a == b) immediate++
            GLES30.glDeleteTextures(1, intArrayOf(b), 0)
        }
        o.put("immediateReuse", immediate).put("immediateTries", IMMEDIATE)

        // B: the engine's own shape: a batch freed, the driver flushed, a batch made.
        val first = IntArray(BATCH) { make(one) }
        GLES30.glDeleteTextures(BATCH, first, 0)
        GLES30.glFlush()
        val second = IntArray(BATCH) { make(one) }
        o.put("batchReuse", second.count { it in first }).put("batch", BATCH)
        GLES30.glDeleteTextures(BATCH, second, 0)

        // C: a long session's churn: a standing set of live names, the oldest deleted and a new one made, many times over.
        val live = ArrayDeque<Int>()
        repeat(STANDING) { live.addLast(make(one)) }
        val deletedAt = HashMap<Int, Int>()
        var reused = 0
        var soonest = Int.MAX_VALUE
        var lowest = Int.MAX_VALUE
        var highest = 0
        for (i in 0 until CHURN) {
            val gone = live.removeFirst()
            GLES30.glDeleteTextures(1, intArrayOf(gone), 0)
            deletedAt[gone] = i
            val made = make(one)
            deletedAt.remove(made)?.let { at -> reused++; if (i - at < soonest) soonest = i - at }
            if (made < lowest) lowest = made
            if (made > highest) highest = made
            live.addLast(made)
        }
        o.put("churnMade", CHURN).put("churnReused", reused)
            .put("soonestReuseAfterDeletes", if (reused == 0) -1 else soonest)
            .put("lowestName", lowest).put("highestName", highest)
        GLES30.glDeleteTextures(live.size, live.toIntArray(), 0)

        // D: the harm itself, only where the driver reuses at once: is a second delete of the old name the death of the new owner?
        var killed = -1
        if (immediate > 0) {
            killed = 0
            repeat(32) {
                val a = make(one)
                GLES30.glDeleteTextures(1, intArrayOf(a), 0)
                val b = make(one)
                if (a == b) {
                    GLES30.glDeleteTextures(1, intArrayOf(a), 0)   // the double delete
                    if (!GLES30.glIsTexture(b)) killed++ else GLES30.glDeleteTextures(1, intArrayOf(b), 0)
                } else GLES30.glDeleteTextures(1, intArrayOf(b), 0)
            }
        }
        o.put("secondDeleteKilledNewOwner", killed)
        GLES30.glActiveTexture(was[0])
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (GLES30.glIsTexture(was[1])) was[1] else 0)
        o.put("renderer", GLES30.glGetString(GLES30.GL_RENDERER) ?: "")
        o.put("glError", GLES30.glGetError())
        return o
    }

    /** A name with storage behind it: a name alone is not a texture to the driver until it is bound. */
    private fun make(one: IntArray): Int {
        GLES30.glGenTextures(1, one, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, one[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        return one[0]
    }

    private const val IMMEDIATE = 2000
    private const val BATCH = 32
    private const val STANDING = 64
    private const val CHURN = 50_000
}
