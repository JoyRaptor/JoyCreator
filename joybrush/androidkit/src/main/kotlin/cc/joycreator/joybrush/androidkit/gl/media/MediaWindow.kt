package cc.joycreator.joybrush.androidkit.gl.media

import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.gl.MEDIA_LOOK
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.media.MediaWindowMath
import cc.joycreator.joybrush.core.paint.Tiles

/**
 * The media window (contract point 8, MEDIA_ENGINE_PLAN M5.3c): one [MediaLayerEngine] of [MediaWindowMath.PX] square,
 * placed over the stroke on ONE layer, loading that layer's payload from [paint] and writing it back. Never a
 * whole-canvas allocation. GL thread only.
 *
 * The ground (JB-2.40 §Q1): a tile with a payload lies on its `g` store (or on nothing); a tile without one lies on its
 * own look, copied exactly into the window's ground, and the first flush that gives it a payload keeps that copy as its
 * `g`. A plain write the engine tells the window about ([lookWritten]) reloads those tiles.
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

    /** Tiles in the window with no payload yet whose ground is a copy of their look: a first flush keeps it as `g`. */
    private val groundFromLook = HashSet<Long>()

    /** When the window last did anything (ms, the caller's clock): it is released after [IDLE_MS] asleep. */
    private var lastActiveMs = 0L

    /** The pen is down, or water is running: the window is in use. Call from every media frame. */
    fun touched(nowMs: Long) { lastActiveMs = nowMs }

    /**
     * Releases the window once the pen has been up and the water asleep for [IDLE_MS] (the Lead's rule: the window is
     * the tool, made for a stroke and let go after). The next stroke makes it again.
     */
    fun releaseIfIdle(nowMs: Long): Boolean {
        if (layerId == null || media.asleep || media.wetActive || paint.mediaStrokeInProgress) return false
        if (nowMs - lastActiveMs < IDLE_MS) return false
        release()
        return true
    }

    /** Writes back and lets the window go now: the layer or the screen changed. */
    fun release() {
        if (layerId == null) return
        flush()
        media.sleep()
        layerId = null
    }

    companion object {
        const val IDLE_MS = 10_000L
    }

    /** Puts the window on [layer] around the pen at ([docX], [docY]): a no-op while it already holds that point. */
    fun place(layer: String, docX: Double, docY: Double, resumeWater: Boolean = true) {
        if (layer == layerId && MediaWindowMath.holds(tx, ty, doubleArrayOf(docX, docY, docX, docY))) return
        if (layerId != null && layer != layerId) release()
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

    /**
     * Copies what changed since the last flush into the stores and the look tiles. Call at every frame boundary, and tell
     * the history buttons afterwards: dried water can change the undo stack (a step left empty is popped).
     */
    fun flush() {
        val id = layerId ?: return
        // The layer went (deleted, or its making undone): nothing here has anywhere to go.
        if (id !in paint.layerIds()) { layerId = null; dryPending = false; media.takeDirty(); media.takeDirtyStores(); media.stopWater(); return }
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
        // A tile getting its first payload over pixels keeps them as its ground, exactly as loaded (requirement 3).
        val firstTouch = if (stores.isEmpty()) emptyList() else keys.filter { it in groundFromLook }
        val grounds = if (firstTouch.isEmpty()) emptyMap() else paint.writableMediaTiles(id, firstTouch, listOf(MediaStores.GROUND)).getValue(MediaStores.GROUND)
        for (key in keys) {
            val (wx, wy) = (Tiles.tx(key) - tx) * tile to (Tiles.ty(key) - ty) * tile
            for (store in stores) {
                val src = media.current(store) ?: continue
                media.blit(src, wx, wy, wx + tile, wy + tile, targets.getValue(store).getValue(key), 0, 0, tile, tile)
            }
            grounds[key]?.let { media.blit(media.groundTexture(), wx, wy, wx + tile, wy + tile, it, 0, 0, tile, tile) }
            media.blit(media.lookTexture(), wx, wy, wx + tile, wy + tile, targets.getValue(MEDIA_LOOK).getValue(key), 0, 0, tile, tile)
        }
        groundFromLook.removeAll(firstTouch.toSet())
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

    /**
     * A plain write changed the look of [keys] of [layer] ([GlPaintEngine.onLookWritten]): reload those tiles, their
     * payload as the engine now has it (none, after slice 1's drop) and their ground, and let the water elsewhere run on.
     */
    fun lookWritten(layer: String, keys: Set<Long>) {
        if (layer != layerId || media.asleep) return
        val mine = MediaWindowMath.allKeys(tx, ty).toSet()
        for (key in keys) {
            if (key !in mine) continue
            val (wx, wy) = (Tiles.tx(key) - tx) * tile to (Tiles.ty(key) - ty) * tile
            media.clearRect(wx, wy, wx + tile, wy + tile)
            loadKey(layer, key)
            media.stateRestored(floatArrayOf(wx.toFloat(), wy.toFloat(), (wx + tile).toFloat(), (wy + tile).toFloat()))
        }
    }

    /** Before a new stroke into sleeping water (after an undo, or a reopened drawing): let it run again. */
    fun wakeWater() {
        val id = layerId ?: return
        if (media.wetActive) return
        waterRect(id)?.let { media.resumeWater(it) }
    }

    private fun load(layer: String, nx: Int, ny: Int, resumeWater: Boolean) {
        // Requirement 5: a payload whose look changed behind the media's back goes, rather than render over the new pixels.
        paint.dropStalePayload(layer, MediaWindowMath.allKeys(nx, ny))
        media.wake()
        layerId = layer; tx = nx; ty = ny
        media.moveTo((nx * tile).toFloat(), (ny * tile).toFloat())
        groundFromLook.clear()
        for (key in MediaWindowMath.allKeys(nx, ny)) loadKey(layer, key)
        // Loading is not painting: nothing loaded is written back until a pass changes it.
        media.takeDirty(); media.takeDirtyStores()
        if (resumeWater) waterRect(layer)?.let { media.resumeWater(it) }
    }

    /** One tile of [layer] into the window: its payload, its look, and its ground (its `g`, or its look when it has no payload). */
    private fun loadKey(layer: String, key: Long) {
        val (wx, wy) = (Tiles.tx(key) - tx) * tile to (Tiles.ty(key) - ty) * tile
        for (store in MediaStores.PAYLOAD) {
            val src = paint.mediaTile(layer, store, key) ?: continue
            for (dst in media.loadTargets(store)) media.blit(src, 0, 0, tile, tile, dst, wx, wy, wx + tile, wy + tile)
        }
        val look = paint.mediaTile(layer, MEDIA_LOOK, key)
        look?.let { media.blit(it, 0, 0, tile, tile, media.lookTexture(), wx, wy, wx + tile, wy + tile) }
        groundFromLook.remove(key)
        if (look != null && !paint.hasPayload(layer, key)) {
            media.blit(look, 0, 0, tile, tile, media.groundTexture(), wx, wy, wx + tile, wy + tile)
            groundFromLook.add(key)
        }
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
