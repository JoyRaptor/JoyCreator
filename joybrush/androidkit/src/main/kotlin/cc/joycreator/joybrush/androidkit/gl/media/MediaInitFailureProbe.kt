package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.GraphicsMemoryException
import org.json.JSONObject

/**
 * A realistic-paint engine whose start fails partway (ENG-04, Codex audit 2026-11-15): the phone's graphics memory runs out
 * on the Nth texture or framebuffer. Before the fix the textures made before the failure were never given back, and
 * [MediaLayerEngine.release] threw on its unset fields before deleting anything, so every retry made the shortage worse.
 *
 * Every acquisition of a start is failed in turn. After each: the start must fail with the engine's own exception, release
 * must not throw, and no texture or framebuffer made through [MediaTex] may be left. Then one start that is allowed to
 * succeed, a sleep, a wake that fails, and a release: nothing left either, and no name deleted twice (a texture made in
 * between must survive, which is how the 10-31 tiles were lost: JBX-35).
 *
 * Debug bench only, GL thread. Its own engines; the person's canvas is not touched.
 */
internal object MediaInitFailureProbe {
    private const val SIDE = 256

    fun run(): JSONObject {
        val o = JSONObject()
        var faults = 0; var leakedTex = 0; var leakedFbo = 0; var releaseThrew = 0; var wrongFailure = 0
        try {
            var n = 0
            while (n < 64) {
                MediaTex.liveTextures = HashSet(); MediaTex.liveFbos = HashSet()
                MediaTex.failAcquisitionIn = n
                val e = MediaLayerEngine(atlasMode = false)
                val failed = runCatching { e.init(SIDE, SIDE) }.exceptionOrNull()
                val reached = MediaTex.failAcquisitionIn < 0   // the countdown ran out: this start met its fault
                MediaTex.failAcquisitionIn = -1
                if (runCatching { e.release() }.isFailure) releaseThrew++
                if (!reached) {
                    // A start with fewer acquisitions than n: it succeeded whole, and its release must leave nothing as well.
                    if (failed != null) wrongFailure++
                    leakedTex += MediaTex.liveTextures!!.size; leakedFbo += MediaTex.liveFbos!!.size
                    break
                }
                faults++
                if (failed !is GraphicsMemoryException && failed !is IllegalStateException) wrongFailure++
                leakedTex += MediaTex.liveTextures!!.size; leakedFbo += MediaTex.liveFbos!!.size
                n++
            }
            o.put("startFaults", faults).put("leakedTextures", leakedTex).put("leakedFramebuffers", leakedFbo)
                .put("releaseThrew", releaseThrew).put("wrongFailure", wrongFailure)

            // A wake that fails: the sleeping engine's old names are not deleted again by its release.
            MediaTex.liveTextures = HashSet(); MediaTex.liveFbos = HashSet()
            val e = MediaLayerEngine(atlasMode = false)
            e.init(SIDE, SIDE)
            e.sleep()
            val bystanders = IntArray(16) { MediaTex.make(1, 1, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_NEAREST) }
            MediaTex.failAcquisitionIn = 4
            val wakeFailed = runCatching { e.wake() }.isFailure
            MediaTex.failAcquisitionIn = -1
            val wakeReleaseThrew = runCatching { e.release() }.isFailure
            val survived = bystanders.count { GLES30.glIsTexture(it) }
            MediaTex.deleteTex(*bystanders)
            o.put("wakeFailed", wakeFailed).put("wakeReleaseThrew", wakeReleaseThrew)
                .put("bystanders", bystanders.size).put("bystandersSurvived", survived)
                .put("wakeLeakedTextures", MediaTex.liveTextures!!.size).put("wakeLeakedFramebuffers", MediaTex.liveFbos!!.size)
            o.put("ok", faults > 0 && leakedTex == 0 && leakedFbo == 0 && releaseThrew == 0 && wrongFailure == 0 && wakeFailed &&
                !wakeReleaseThrew && survived == bystanders.size && MediaTex.liveTextures!!.isEmpty() && MediaTex.liveFbos!!.isEmpty())
        } finally {
            MediaTex.failAcquisitionIn = -1; MediaTex.liveTextures = null; MediaTex.liveFbos = null
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) Unit
        }
        return o
    }
}
