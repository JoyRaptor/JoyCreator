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
class MediaWindow(private val paint: GlPaintEngine, val media: MediaLayerEngine, private val renderLook: (FloatArray) -> Unit) : MediaPlace {
    override var layerId: String? = null; private set
    /** The plane of [layerId] the window holds (JB-2.40 slice 3): null = the shared plane, else a frame cel id. */
    override var plane: String? = null; private set
    var tx = 0; private set
    var ty = 0; private set

    private val tile get() = Tiles.SIZE

    /** Water dried while a stroke was open: its tiles go at the first flush after the stroke. */
    private var dryPending = false

    /** Tiles in the window with no payload yet whose ground is a copy of their look: a first flush keeps it as `g`. */
    private val groundFromLook = HashSet<Long>()

    /** When the window last did anything (ms, the caller's clock): it is released after [IDLE_MS] asleep. */
    private var lastActiveMs = 0L

    /** The drawing this window's state was loaded from: none of it is written back once another has opened. */
    private val drawing = DocumentStamp { paint.documentGeneration }

    /** Another drawing opened since this loaded ([DocumentStamp]): let everything go unwritten. True when it did. */
    private fun staleDrawing(): Boolean {
        if (layerId == null || !drawing.stale) return false
        android.util.Log.w("JoyBrushMedia", "another drawing opened under the live paint: let it go unwritten")
        forget()
        return true
    }

    /** The debug replay's counters (window moves and reloads), or null. */
    override var stats: cc.joycreator.joybrush.androidkit.bench.FrameStats? = null

    /** The pen is down, or water is running: the window is in use. Call from every media frame. */
    override fun touched(nowMs: Long) { lastActiveMs = nowMs }

    override fun heldKeys(): List<Long> = MediaWindowMath.allKeys(tx, ty)

    /**
     * Releases the window once the pen has been up and the water asleep for [IDLE_MS] (the Lead's rule: the window is
     * the tool, made for a stroke and let go after). The next stroke makes it again.
     */
    override fun releaseIfIdle(nowMs: Long): Boolean {
        if (layerId == null || media.asleep || media.wetActive || paint.mediaStrokeInProgress) return false
        if (nowMs - lastActiveMs < IDLE_MS) return false
        release()
        return true
    }

    /** Writes back and lets the window go now: the layer or the screen changed. */
    override fun release() {
        if (layerId == null || staleDrawing()) return
        flush()
        media.sleep()
        layerId = null
    }

    companion object {
        const val IDLE_MS = 10_000L
        /** The look shader's widest read (impasto shadows): 51 px march + 1 px gradient + 1 px bilinear. */
        const val LOOK_HALO_MAX = 53
        /** Without impasto the look reads only ±1 px gradients through a bilinear tap. */
        const val LOOK_HALO_FLAT = 2
    }

    /** Puts the window on [layer] around the pen at ([docX], [docY]): a no-op while it already holds that point. */
    override fun place(layer: String, plane: String?, docX: Double, docY: Double, resumeWater: Boolean) {
        staleDrawing()
        if (layer == layerId && plane == this.plane && MediaWindowMath.holds(tx, ty, doubleArrayOf(docX, docY, docX, docY))) return
        if (layerId != null && (layer != layerId || plane != this.plane)) release()
        moveTo(layer, plane, docX, docY, resumeWater)
    }

    /** Keeps a stroke's area ([x0, y0, x1, y1], document px) inside the window, moving it to centre on the pen if not. */
    override fun follow(docRect: DoubleArray, penX: Double, penY: Double) {
        val id = layerId ?: return
        if (!MediaWindowMath.holds(tx, ty, docRect)) mediaTrace("JB.media.window.move") { moveTo(id, plane, penX, penY, resumeWater = true) }
    }

    /**
     * Whether [follow]ing ([docRect], [penX], [penY]) would leave part of [paints] (document px) off the window: a frame
     * that would clip its own dabs (a slow cold first frame, a very fast flick). Only those are drawn in parts.
     */
    fun wouldClip(docRect: DoubleArray, penX: Double, penY: Double, paints: DoubleArray): Boolean {
        val (nx, ny) = if (MediaWindowMath.holds(tx, ty, docRect)) tx to ty else MediaWindowMath.placeFor(penX, penY)
        return !MediaWindowMath.within(nx, ny, paints)
    }

    /**
     * A frame whose dabs the usual follow would clip: the window centred on [dabs] instead, if that holds them all (most
     * such frames on the Note 9: the pen-centred window leaves as little as 128 px behind a fast pen). One move, as follow
     * would have made; true when the frame can now run whole. False: no window holds it; it must go in parts.
     */
    fun holdWhole(dabs: DoubleArray): Boolean {
        val id = layerId ?: return false
        val (nx, ny) = MediaWindowMath.placeForRect(dabs)
        if (!MediaWindowMath.within(nx, ny, dabs)) return false
        if (nx != tx || ny != ty) mediaTrace("JB.media.window.move") { moveTo(id, plane, nx, ny, resumeWater = true) }
        return true
    }

    /** A part of a frame too wide for one window (MediaCanvas.runWindowParts): the window centred on it, so up to SPAN fits. */
    fun followCentred(docRect: DoubleArray) {
        val id = layerId ?: return
        if (MediaWindowMath.holds(tx, ty, docRect)) return
        val (nx, ny) = MediaWindowMath.placeForRect(docRect)
        mediaTrace("JB.media.window.move") { moveTo(id, plane, nx, ny, resumeWater = true) }
    }

    private fun moveTo(layer: String, plane: String?, docX: Double, docY: Double, resumeWater: Boolean) {
        val (nx, ny) = MediaWindowMath.placeFor(docX, docY)
        moveTo(layer, plane, nx, ny, resumeWater)
    }

    // Water the window leaves is stored as it is (wet, frozen until a window loads it again). Drying it where it lay
    // (2026-10-19, 323ace7bc) made tile-shaped hard edges and boxed in tilted drips on the Note 9: worse than the one edge
    // it was to soften. The edge goes with the atlas (no window).
    private fun moveTo(layer: String, plane: String?, nx: Int, ny: Int, resumeWater: Boolean) {
        stats?.let { if (it.recording) it.mediaWindowMoves++ }
        flush()
        load(layer, plane, nx, ny, resumeWater)
    }

    /**
     * How far (window px) the look shader reads around a pixel: the impasto shadow march reaches 51 px, gradients 1,
     * bilinear 1 more (jb_media_render.frag). A look pixel is only right where everything it reads is loaded.
     */
    override var lookHalo = LOOK_HALO_MAX

    /** Stores written by passes but not yet copied into the layer's tiles, and where (window px): see [flush]. */
    private val pendingStores = LinkedHashSet<String>()
    private var pendingRect: FloatArray? = null

    /**
     * Copies what changed since the last flush into the look tiles, and (when [stores], the default) into the stores.
     * Call at every frame boundary, and tell the history buttons afterwards: dried water can change the undo stack.
     *
     * While a stroke is painting, frames call this with [stores] false: the person sees the look at once, and the float
     * stores (several RGBA32F tiles per touched tile) are copied once, at the stroke's end or when the window moves or is
     * released, instead of every frame. Nothing reads the stores in between: saving and undo wait for the stroke
     * (JbCanvasView finishes or cancels it first) and the display composites the look only. The tiles are still made and
     * copied-on-write at first touch, so the memory ceiling refuses at the same moment it always did.
     *
     * The look is written back only where it was recomputed with all its inputs loaded (the dirty area plus the shader's
     * reach, kept off the window's outer band), never as whole tiles: re-rendering a tile at the window's edge without its
     * neighbours stamped a wrong band into the painting (owner's seams, 2026-10-08; LEAD_PLAN F3).
     */
    override fun flush(stores: Boolean) {
        val id = layerId ?: return
        if (staleDrawing()) return
        // The layer went (deleted, or its making undone): nothing here has anywhere to go.
        if (id !in paint.layerIds()) { forget(); return }
        if (dryPending && !paint.mediaStrokeInProgress) { dryPending = false; dropWater(id) }
        val rect = media.takeDirty()
        pendingStores += media.takeDirtyStores()
        if (rect != null) pendingRect = union(pendingRect, rect)
        if (rect != null) mediaTrace("JB.media.look.write") { writeLook(id, rect) }
        mediaTrace("JB.media.store.write") { if (stores) writeStores(id) else reserveStores() }
    }

    /** Debug A/B only (bench `jb_media_legacy`): the pre-2026-10-10 whole-tile look write-back. */
    override var legacyLook = false

    private fun writeLook(id: String, dirty: FloatArray) {
        val px = MediaWindowMath.PX.toFloat()
        val h = lookHalo.toFloat()
        // Recompute the dirty area and what it shades around it, but only where the shader's own reach stays loaded…
        val inner = floatArrayOf(maxOf(dirty[0] - h, h), maxOf(dirty[1] - h, h), minOf(dirty[2] + h, px - h), minOf(dirty[3] + h, px - h))
        // …except the dirty pixels themselves, which must show even near the edge (best effort there).
        val out = if (legacyLook) MediaWindowMath.keysIn(tx, ty, dirty).let { k ->
            if (k.isEmpty()) return
            floatArrayOf(k.minOf { Tiles.tx(it) - tx } * tile.toFloat(), k.minOf { Tiles.ty(it) - ty } * tile.toFloat(),
                (k.maxOf { Tiles.tx(it) - tx } + 1) * tile.toFloat(), (k.maxOf { Tiles.ty(it) - ty } + 1) * tile.toFloat())
        } else union(if (inner[2] > inner[0] && inner[3] > inner[1]) inner else null, clamp(dirty, px))!!
        val r = intArrayOf(kotlin.math.floor(out[0]).toInt(), kotlin.math.floor(out[1]).toInt(), kotlin.math.ceil(out[2]).toInt(), kotlin.math.ceil(out[3]).toInt())
        val keys = MediaWindowMath.keysIn(tx, ty, floatArrayOf(r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat()))
        if (keys.isEmpty()) return
        renderLook(floatArrayOf(r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat()))
        val targets = paint.writableMediaTiles(id, keys, listOf(MEDIA_LOOK), plane).getValue(MEDIA_LOOK)
        for (key in keys) {
            val wx = (Tiles.tx(key) - tx) * tile; val wy = (Tiles.ty(key) - ty) * tile
            val x0 = maxOf(r[0], wx); val y0 = maxOf(r[1], wy); val x1 = minOf(r[2], wx + tile); val y1 = minOf(r[3], wy + tile)
            if (x1 <= x0 || y1 <= y0) continue
            media.blit(media.lookTexture(), x0, y0, x1, y1, targets.getValue(key), x0 - wx, y0 - wy, x1 - wx, y1 - wy)
        }
    }

    private fun writeStores(id: String) {
        val rect = pendingRect
        pendingRect = null
        val stores = pendingStores.toMutableList()
        pendingStores.clear()
        if (rect == null || stores.isEmpty()) return
        val keys = MediaWindowMath.keysIn(tx, ty, rect)
        if (keys.isEmpty()) return
        val water = stores.filter { it in MediaStores.WATER }
        // Water that has dried is not written back: its tiles go (water stores exist only where wet).
        val dried = water.isNotEmpty() && !media.wetActive
        if (dried && paint.mediaStrokeInProgress) dryPending = true
        val drop = dried && !dryPending
        if (drop) stores.removeAll(MediaStores.WATER)
        // A tile getting its first payload over pixels keeps them as its ground, exactly as loaded (requirement 3).
        val firstTouch = if (stores.isEmpty()) emptyList() else keys.filter { it in groundFromLook }
        val grounds = if (firstTouch.isEmpty()) emptyMap() else paint.writableMediaTiles(id, firstTouch, listOf(MediaStores.GROUND), plane).getValue(MediaStores.GROUND)
        // The look is not copied here: [writeLook] wrote it, sub-tile, at every frame.
        val targets = if (stores.isEmpty()) emptyMap() else paint.writableMediaTiles(id, keys, stores, plane)
        for (key in keys) {
            val (wx, wy) = (Tiles.tx(key) - tx) * tile to (Tiles.ty(key) - ty) * tile
            for (store in stores) {
                val src = media.current(store) ?: continue
                media.blit(src, wx, wy, wx + tile, wy + tile, targets.getValue(store).getValue(key), 0, 0, tile, tile)
            }
            grounds[key]?.let { media.blit(media.groundTexture(), wx, wy, wx + tile, wy + tile, it, 0, 0, tile, tile) }
        }
        groundFromLook.removeAll(firstTouch.toSet())
        if (drop) dropWater(id)
    }

    /**
     * Reserves (copy-on-write, and the memory ceiling's check) the store tiles the passes since the last call touched,
     * without copying anything yet: [flush] with stores false calls it so a refusal still comes at the first frame.
     */
    fun reserveStores() {
        val id = layerId ?: return
        val rect = pendingRect ?: return
        val keys = MediaWindowMath.keysIn(tx, ty, rect)
        val stores = pendingStores.filter { it !in MediaStores.WATER || media.wetActive }
        if (keys.isEmpty() || stores.isEmpty()) return
        paint.writableMediaTiles(id, keys, stores, plane)
        // A first payload also makes the tile's ground: count it against the ceiling now, not at the stroke's end.
        val firstTouch = keys.filter { it in groundFromLook }
        if (firstTouch.isNotEmpty()) paint.writableMediaTiles(id, firstTouch, listOf(MediaStores.GROUND), plane)
    }

    private fun clamp(r: FloatArray, px: Float) = floatArrayOf(maxOf(r[0], 0f), maxOf(r[1], 0f), minOf(r[2], px), minOf(r[3], px))

    private fun union(a: FloatArray?, b: FloatArray?): FloatArray? = when {
        a == null -> b?.copyOf()
        b == null -> a.copyOf()
        else -> floatArrayOf(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[2], b[2]), maxOf(a[3], b[3]))
    }

    /** Nothing here has anywhere to go (the layer went, or the drawing did): drop it all, unwritten. */
    private fun forget() {
        layerId = null; dryPending = false; pendingStores.clear(); pendingRect = null
        media.takeDirty(); media.takeDirtyStores(); media.stopWater()
    }

    private fun dropWater(id: String) {
        val all = MediaWindowMath.allKeys(tx, ty)
        for (store in MediaStores.WATER) paint.dropMediaTiles(id, store, all.filter { paint.mediaTile(id, store, it, plane) != null }, plane)
    }

    /** Undo or redo put tiles of [layer] back: stop the water and reload what the window shows. True when it stopped it. */
    override fun restored(layer: String, plane: String?, keys: Set<Long>): Boolean {
        if (staleDrawing()) return false
        if (layer != layerId || plane != this.plane) return false
        val mine = MediaWindowMath.allKeys(tx, ty).toSet()
        if (keys.none { it in mine }) return false
        media.stopWater()
        load(layer, plane, tx, ty, resumeWater = false)
        return true
    }

    /**
     * A plain write changed the look of [keys] of [layer] ([GlPaintEngine.onLookWritten]): reload those tiles, their
     * payload as the engine now has it (none, after slice 1's drop) and their ground, and let the water elsewhere run on.
     */
    override fun lookWritten(layer: String, plane: String?, keys: Set<Long>) {
        if (staleDrawing()) return
        if (layer != layerId || plane != this.plane || media.asleep) return
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
    override fun refreshLook(rect: FloatArray) {
        if (staleDrawing()) return
        val id = layerId ?: return
        writeLook(id, rect)
    }

    override fun wakeWater() {
        if (staleDrawing()) return
        val id = layerId ?: return
        if (media.wetActive) return
        waterRect(id)?.let { media.resumeWater(it) }
    }

    private fun load(layer: String, plane: String?, nx: Int, ny: Int, resumeWater: Boolean) {
        stats?.let { if (it.recording) it.mediaWindowLoads++ }
        // Requirement 5: a payload whose look changed behind the media's back goes, rather than render over the new pixels.
        paint.dropStalePayload(layer, MediaWindowMath.allKeys(nx, ny), plane)
        media.wake()
        drawing.stamp()
        layerId = layer; this.plane = plane; tx = nx; ty = ny
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
            val src = paint.mediaTile(layer, store, key, plane) ?: continue
            for (dst in media.loadTargets(store)) media.blit(src, 0, 0, tile, tile, dst, wx, wy, wx + tile, wy + tile)
        }
        val look = paint.mediaTile(layer, MEDIA_LOOK, key, plane)
        look?.let { media.blit(it, 0, 0, tile, tile, media.lookTexture(), wx, wy, wx + tile, wy + tile) }
        groundFromLook.remove(key)
        if (look != null && !paint.hasPayload(layer, key, plane)) {
            media.blit(look, 0, 0, tile, tile, media.groundTexture(), wx, wy, wx + tile, wy + tile)
            groundFromLook.add(key)
        }
    }

    /** The window-px rectangle of the water tiles [layer] has inside the window, or null when there are none. */
    private fun waterRect(layer: String): FloatArray? {
        val wet = MediaWindowMath.allKeys(tx, ty).filter { paint.mediaTile(layer, "w0", it, plane) != null }
        if (wet.isEmpty()) return null
        return floatArrayOf(
            wet.minOf { Tiles.tx(it) - tx } * tile.toFloat(), wet.minOf { Tiles.ty(it) - ty } * tile.toFloat(),
            (wet.maxOf { Tiles.tx(it) - tx } + 1) * tile.toFloat(), (wet.maxOf { Tiles.ty(it) - ty } + 1) * tile.toFloat())
    }
}
