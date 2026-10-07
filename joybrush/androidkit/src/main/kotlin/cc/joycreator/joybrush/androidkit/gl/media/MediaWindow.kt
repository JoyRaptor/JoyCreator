package cc.joycreator.joybrush.androidkit.gl.media

import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.gl.MEDIA_LOOK
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.media.MediaWindowMath
import cc.joycreator.joybrush.core.paint.Tiles

/**
 * The media window (contract point 8, MEDIA_ENGINE_PLAN M5.3c): one [MediaLayerEngine] of [MediaWindowMath.PX] square,
 * placed over the stroke on ONE media layer, loading that layer's stores from [paint] and writing them back. Never a
 * whole-canvas allocation. GL thread only.
 *
 * - [place] puts the window under the pen (writing back and moving only when it has to).
 * - [follow] moves it when a stroke comes within [MediaWindowMath.MARGIN] of its edge.
 * - [flush], at every frame boundary, copies what the passes wrote into the stores (copy-on-write through
 *   [GlPaintEngine.writableMediaTiles], so undo and the running-water rule hold) and the look into the look tiles.
 *   [renderLook] draws the look for a window-px rectangle into [MediaLayerEngine.lookTexture]; the caller holds the
 *   paper and lighting it needs.
 * - [restored] is [GlPaintEngine.onMediaRestored]: undo or redo stops the water and reloads (it never restarts by itself).
 *
 * The window simulates in full float; the stores rest as half floats, so each write-back rounds once (the Lead's ruling).
 */
class MediaWindow(private val paint: GlPaintEngine, val media: MediaLayerEngine, private val renderLook: (FloatArray) -> Unit) {
    var layerId: String? = null; private set
    var tx = 0; private set
    var ty = 0; private set

    private val tile get() = Tiles.SIZE

    /** Water dried while a stroke was open: its tiles go at the first flush after the stroke. */
    private var dryPending = false

    /** Puts the window on [layer] around the pen at ([docX], [docY]): a no-op while it already holds that point. */
    fun place(layer: String, docX: Double, docY: Double, resumeWater: Boolean = true) {
        if (layer == layerId && MediaWindowMath.holds(tx, ty, doubleArrayOf(docX, docY, docX, docY))) return
        moveTo(layer, docX, docY, resumeWater)
    }

    /** Keeps a stroke's area ([x0, y0, x1, y1], document px) inside the window, moving it to centre on the pen if not. */
    fun follow(docRect: DoubleArray, penX: Double, penY: Double) {
        val id = layerId ?: return
        if (!MediaWindowMath.holds(tx, ty, docRect)) moveTo(id, penX, penY, resumeWater = true)
    }

    private fun moveTo(layer: String, docX: Double, docY: Double, resumeWater: Boolean) {
        flush()
        val (nx, ny) = MediaWindowMath.placeFor(docX, docY)
        load(layer, nx, ny, resumeWater)
    }

    /** Copies what changed since the last flush into the stores and the look tiles. Call at every frame boundary. */
    fun flush() {
        val id = layerId ?: return
        if (dryPending && !paint.mediaStrokeInProgress) { dryPending = false; dropWater(id) }
        val rect = media.takeDirty() ?: return
        val keys = MediaWindowMath.keysIn(tx, ty, rect)
        if (keys.isEmpty()) return
        // The look covers whole tiles: a tile is written back whole.
        val tileRect = floatArrayOf(
            keys.minOf { Tiles.tx(it) - tx } * tile.toFloat(), keys.minOf { Tiles.ty(it) - ty } * tile.toFloat(),
            (keys.maxOf { Tiles.tx(it) - tx } + 1) * tile.toFloat(), (keys.maxOf { Tiles.ty(it) - ty } + 1) * tile.toFloat())
        renderLook(tileRect)
        val stores = media.takeDirtyStores().toMutableList()
        val water = stores.filter { it in MediaStores.WATER }
        // Water that has dried is not written back: its tiles go (water stores exist only where wet).
        val dried = water.isNotEmpty() && !media.wetActive
        if (dried && paint.mediaStrokeInProgress) dryPending = true
        val drop = dried && !dryPending
        if (drop) stores.removeAll(MediaStores.WATER)
        val targets = paint.writableMediaTiles(id, keys, stores + MEDIA_LOOK)
        for (key in keys) {
            val (wx, wy) = (Tiles.tx(key) - tx) * tile to (Tiles.ty(key) - ty) * tile
            for (store in stores) {
                val src = media.current(store) ?: continue
                media.blit(src, wx, wy, wx + tile, wy + tile, targets.getValue(store).getValue(key), 0, 0, tile, tile)
            }
            media.blit(media.lookTexture(), wx, wy, wx + tile, wy + tile, targets.getValue(MEDIA_LOOK).getValue(key), 0, 0, tile, tile)
        }
        if (drop) dropWater(id)
    }

    private fun dropWater(id: String) {
        val all = MediaWindowMath.allKeys(tx, ty)
        for (store in MediaStores.WATER) paint.dropMediaTiles(id, store, all.filter { paint.mediaTile(id, store, it) != null })
    }

    /** Undo or redo put tiles of [layer] back: stop the water and reload what the window shows. */
    fun restored(layer: String, keys: Set<Long>) {
        if (layer != layerId) return
        val mine = MediaWindowMath.allKeys(tx, ty).toSet()
        if (keys.none { it in mine }) return
        media.stopWater()
        load(layer, tx, ty, resumeWater = false)
    }

    /** Before a new stroke into sleeping water (after an undo, or a reopened drawing): let it run again. */
    fun wakeWater() {
        val id = layerId ?: return
        if (media.wetActive) return
        waterRect(id)?.let { media.resumeWater(it) }
    }

    private fun load(layer: String, nx: Int, ny: Int, resumeWater: Boolean) {
        layerId = layer; tx = nx; ty = ny
        media.moveTo((nx * tile).toFloat(), (ny * tile).toFloat())
        val look = media.lookTexture()
        for (key in MediaWindowMath.allKeys(nx, ny)) {
            val (wx, wy) = (Tiles.tx(key) - nx) * tile to (Tiles.ty(key) - ny) * tile
            for (store in MediaStores.ALL) {
                val src = paint.mediaTile(layer, store, key) ?: continue
                for (dst in media.loadTargets(store)) media.blit(src, 0, 0, tile, tile, dst, wx, wy, wx + tile, wy + tile)
            }
            paint.mediaTile(layer, MEDIA_LOOK, key)?.let { media.blit(it, 0, 0, tile, tile, look, wx, wy, wx + tile, wy + tile) }
        }
        // Loading is not painting: nothing loaded is written back until a pass changes it.
        media.takeDirty(); media.takeDirtyStores()
        if (resumeWater) waterRect(layer)?.let { media.resumeWater(it) }
    }

    /** The window-px rectangle of the water tiles [layer] has inside the window, or null when there are none. */
    private fun waterRect(layer: String): FloatArray? {
        val wet = MediaWindowMath.allKeys(tx, ty).filter { paint.mediaTile(layer, "w0", it) != null }
        if (wet.isEmpty()) return null
        return floatArrayOf(
            wet.minOf { Tiles.tx(it) - tx } * tile.toFloat(), wet.minOf { Tiles.ty(it) - ty } * tile.toFloat(),
            (wet.maxOf { Tiles.tx(it) - tx } + 1) * tile.toFloat(), (wet.maxOf { Tiles.ty(it) - ty } + 1) * tile.toFloat())
    }
}
