package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import org.json.JSONObject

/**
 * Tiles the driver lost (the owner's black square, 2026-10-31; debug bench only). Two painted tiles on a fresh engine; one
 * loses its memory. Reading it must fail as the save did; [GlPaintEngine.repairLostTiles] must find exactly that one and
 * leave it blank and readable, and its neighbour byte for byte as painted. GL thread; the user's canvas is not touched.
 */
internal object LostTileProbe {
    private const val LAYER = "lost-tile-probe"
    private const val LOST = 0L
    private const val KEPT = 1L

    fun probe(): String {
        val report = JSONObject().put("probe", "lostTile")
        val engine = GlPaintEngine()
        try {
            engine.init()
            engine.addLayer(LAYER)
            val red = ByteArray(256 * 256 * 4) { if (it % 4 == 0 || it % 4 == 3) -1 else 0 }
            engine.writeTile(LAYER, LOST, red)
            engine.writeTile(LAYER, KEPT, red)
            val healthy = engine.repairLostTiles()
            engine.loseTileForProbe(LAYER, LOST)
            val readFailed = runCatching { engine.readTile(LAYER, LOST) }.isFailure
            val repaired = engine.repairLostTiles()
            val blank = engine.readTile(LAYER, LOST)?.all { it.toInt() == 0 } == true
            val kept = engine.readTile(LAYER, KEPT)?.contentEquals(red) == true
            val again = engine.repairLostTiles()
            val glError = GLES30.glGetError()
            report.put("healthyRepaired", healthy).put("readFailed", readFailed).put("repaired", repaired).put("blank", blank)
                .put("neighbourKept", kept).put("repairedAgain", again).put("glError", glError)
            report.put("pass", healthy == 0 && readFailed && repaired == 1 && blank && kept && again == 0 && glError == GLES30.GL_NO_ERROR)
        } catch (e: Throwable) {
            report.put("pass", false).put("error", e.toString())
        } finally {
            if (engine.ready) runCatching { engine.release() }
        }
        return report.toString()
    }
}
