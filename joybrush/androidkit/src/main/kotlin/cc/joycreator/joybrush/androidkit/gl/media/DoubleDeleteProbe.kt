package cc.joycreator.joybrush.androidkit.gl.media

import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.gl.MEDIA_LOOK
import android.opengl.GLES30
import cc.joycreator.joybrush.core.paint.Tiles
import org.json.JSONObject

/**
 * The lost tiles of 2026-10-31, made to happen on purpose (JBX-35), on the real engine and this phone's driver.
 *
 * The order of events that day, as the source of the time had it: the realistic-paint engine slept and deleted its window
 * textures; a stroke then made new layer tiles; the paint engine was released and deleted its sleeping textures AGAIN
 * ([MediaLayerEngine.release], fixed 11-01). [TextureNameReuseProbe] shows the driver gives the newest deleted names
 * straight back out, so those new layer tiles could be holding them, and the second delete took their memory: a tile whose
 * name has no storage, which draws black and cannot be read or copied ("Board tile copy failed").
 *
 * Two runs, the same but for the second delete: the driver is asked which layer tiles are left without memory, and
 * [GlPaintEngine.repairLostTiles] (the check before every save) is asked to find and mend them.
 * The 10-31 way must lose every tile that took a reused name; today's way must lose none. Stand-in textures play the paint
 * engine's window (the mistake was in when names are deleted, not in what the textures held).
 *
 * Debug bench only, GL thread. Its own engine and layer: the person's canvas is not touched. Each lost tile writes a line
 * to the incident log, as a real one would; they name the layer "double-delete-probe".
 */
internal object DoubleDeleteProbe {
    private const val LAYER = "double-delete-probe"
    private const val TILES = 24

    fun run(): JSONObject {
        val old = pass(deleteAgain = true)
        val now = pass(deleteAgain = false)
        val reused = old.getInt("tilesOnReusedNames")
        return JSONObject().put("the1031Way", old).put("today", now)
            // Shown when the old way loses exactly the tiles on reused names and today's loses none; where the driver reuses
            // no name at all (reused 0) the probe can show nothing either way on this phone.
            .put("reproduced", reused > 0 && old.getInt("tilesLost") == reused && now.getInt("tilesLost") == 0)
            .put("conclusive", reused > 0)
    }

    private fun pass(deleteAgain: Boolean): JSONObject {
        val engine = GlPaintEngine()
        try {
            engine.init()
            engine.addLayer(LAYER)
            // The paint engine's window: the newest textures in the context.
            val window = IntArray(TILES) { MediaTex.make(64, 64) }
            // It sleeps: they are deleted, and their names are free.
            MediaTex.deleteTex(*window)
            // A stroke lands on the layer: new tiles, which ask the driver for names.
            val rgba = ByteArray(Tiles.SIZE * Tiles.SIZE * 4) { 0x7F }
            val keys = List(TILES) { Tiles.key(it, 0) }
            for (k in keys) engine.writeTile(LAYER, k, rgba)
            val reused = keys.count { engine.mediaTile(LAYER, MEDIA_LOOK, it)?.let { t -> t in window } == true }
            // The paint engine is released. On 10-31 it deleted the names it had already deleted when it slept.
            if (deleteAgain) MediaTex.deleteTex(*window)
            // Counted here from the driver itself, then by the engine's own check before a save: the two must agree, or the
            // save-time check is missing lost tiles.
            fun dead() = keys.count { k -> engine.mediaTile(LAYER, MEDIA_LOOK, k)?.let { t -> !GLES30.glIsTexture(t) } == true }
            val lost = dead()
            val found = engine.repairLostTiles()
            return JSONObject().put("tiles", TILES).put("tilesOnReusedNames", reused).put("tilesLost", lost)
                .put("repairFound", found).put("stillLostAfterRepair", dead())
        } finally {
            if (engine.ready) runCatching { engine.release() }
        }
    }
}
