package cc.joycreator.joybrush.androidkit.gl.media

import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.gl.MEDIA_LOOK
import cc.joycreator.joybrush.androidkit.gl.MediaRoomException
import cc.joycreator.joybrush.core.media.MediaAtlasPage
import cc.joycreator.joybrush.core.media.MediaAtlasPlacement
import cc.joycreator.joybrush.core.media.MediaAtlasSlots
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Media Atlas v2, slice B2 (tasks/joybrush/specs/MEDIA_ATLAS_V2_20261016.md): the media engine's state on ONE layer with
 * no window. Layer tiles are placed freely in [media]'s slots (15 resident + 1 staging, A1's [MediaAtlasSlots]) and loaded
 * where the passes need them: the engine asks before every pass ([MediaLayerEngine.onNeed]) for the tiles its rectangle
 * and its read reach touch (the residency rule, [MediaAtlasPlacement]); a tile that cannot be made resident is refused in
 * words before that pass draws anything. There is no edge to move, so nothing is ever clipped or creased by one.
 *
 * Same contract as [MediaWindow] towards [MediaCanvas] ([MediaPlace]) and towards the layer's stores: the payload, the
 * look and the ground travel through [GlPaintEngine.writableMediaTiles] (copy-on-write, so undo and the running-water rule
 * hold), a tile with no payload lies on its own look (ground-from-look), dried water is dropped, and undo stops the water.
 * Proved headless bit for bit against one big window (tools/media_atlas_b2_check.js); off by default
 * ([MediaAtlasGl.enabled]) until the device probe has passed. GL thread only.
 */
class MediaAtlas(private val paint: GlPaintEngine, val media: MediaLayerEngine, private val renderLook: (FloatArray) -> Unit) : MediaPlace {
    override var layerId: String? = null; private set
    override var plane: String? = null; private set
    override var stats: cc.joycreator.joybrush.androidkit.bench.FrameStats? = null
    override var legacyLook = false
    override var lookHalo = MediaWindow.LOOK_HALO_MAX

    private val tile get() = Tiles.SIZE
    // The window's reservation already pays for these slots (the same textures): the byte budget is one unit per slot.
    private val slots = MediaAtlasSlots(media.slotCount, 1L, media.slotCount.toLong())
    /** Pages held for the frame in progress (released at [flush]): a later pass of the same frame may still read them. */
    private val pinned = LinkedHashSet<MediaAtlasPage>()
    /** Load water as it comes in (a stroke) or leave it asleep (an undo reloading tiles, the Lead's rule). */
    private var resumeOnLoad = true
    private var dryPending = false
    private val groundFromLook = HashSet<Long>()
    private var lastActiveMs = 0L
    private val pendingStores = LinkedHashSet<String>()
    private var pendingRect: FloatArray? = null

    init {
        media.onNeed = { rect, reach -> need(rect, reach) }
        media.setResidency(emptyMap())   // free placement from now on: the engine has no window edge
    }

    private fun page(key: Long) = MediaAtlasPage(layerId!!, plane, key)
    private fun resident(): Map<Long, Int> = slots.residents().associate { it.page.tileKey to it.slot }
    override fun heldKeys(): List<Long> = slots.residents().map { it.page.tileKey }

    override fun touched(nowMs: Long) { lastActiveMs = nowMs }

    override fun releaseIfIdle(nowMs: Long): Boolean {
        if (layerId == null || media.asleep || media.wetActive || paint.mediaStrokeInProgress) return false
        if (nowMs - lastActiveMs < MediaWindow.IDLE_MS) return false
        release()
        return true
    }

    override fun release() {
        if (layerId == null) return
        flush()
        for (r in slots.residents()) if (r.dirty) writeBackSlot(r.slot, r.page.tileKey)
        pinned.clear(); slots.clear(); media.setResidency(emptyMap())
        media.sleep()
        layerId = null
    }

    override fun place(layer: String, plane: String?, docX: Double, docY: Double, resumeWater: Boolean) {
        if (layerId != null && (layer != layerId || plane != this.plane)) release()
        if (layerId == null) {
            // Requirement 5 happens per tile as it loads (dropStalePayload in [loadSlot]).
            media.wake()
            layerId = layer; this.plane = plane
            groundFromLook.clear()
        }
        // The tiles round the pen, so water lying there can run again ([wakeWater]) as it did in the window.
        resumeOnLoad = resumeWater
        val (lx, ly) = layerPx(docX, docY)
        try { need(floatArrayOf(lx, ly, lx + 1f, ly + 1f), tile) } finally { resumeOnLoad = true }
    }

    /** The atlas has no edge to keep a stroke inside: it makes the stroke's area resident instead (atomic, or refused). */
    override fun follow(docRect: DoubleArray, penX: Double, penY: Double) {
        if (layerId == null) return
        val (x0, y0) = layerPx(docRect[0], docRect[1]); val (x1, y1) = layerPx(docRect[2], docRect[3])
        need(floatArrayOf(x0, y0, x1, y1), 0)
        media.wetRect?.let { need(it, MediaAtlasPlacement.WET_REACH) }
    }

    private fun layerPx(x: Double, y: Double): Pair<Float, Float> = media.toLayerPx(x, y)

    /**
     * The residency rule ([MediaLayerEngine.onNeed]): every tile [rect] (layer px) dilated by [reach] touches is made
     * resident and held for this frame, or nothing is drawn: a [MediaRoomException] in words.
     */
    private fun need(rect: FloatArray, reach: Int) {
        if (layerId == null) return
        val keys = MediaAtlasPlacement.tiles(rect[0].toDouble(), rect[1].toDouble(), rect[2].toDouble(), rect[3].toDouble(), reach)
        val missing = keys.filter { slots.resident(page(it)) == null }
        // Atomic: count what this pass needs against what can be freed BEFORE loading any of it (a tile held for this frame,
        // or by running water, cannot leave).
        val free = slots.capacity - slots.residents().size + slots.residents().count { it.pins == 0 && it.page !in pinned && it.page.tileKey !in keys }
        if (missing.size > free || keys.size > slots.capacity) {
            throw MediaRoomException("This pencil, watercolour or oil stroke reaches further than this phone can hold at once " +
                "(${keys.size} areas of ${slots.capacity}). Paint a smaller area at a time.")
        }
        val wet = media.wetActive
        for (key in keys) {
            val p = page(key)
            residency.ensure(p)
                ?: throw MediaRoomException("There is no room for more pencil, watercolour or oil here right now.")
            slots.touch(p)
            if (p !in pinned) { slots.pin(p); pinned += p }
        }
        // Running water may flow into any tile it is in: nothing wet leaves while it runs (B3 refines this per tile).
        if (wet) for (r in slots.residents()) if (r.page !in pinned) { slots.pin(r.page); pinned += r.page }
        if (missing.isNotEmpty()) media.setResidency(resident())
    }

    private val residency by lazy { MediaAtlasResidency(slots, backing) }
    private val backing = object : MediaAtlasBacking {
        override fun load(slot: Int, page: MediaAtlasPage) = loadSlot(slot, page.tileKey)
        override fun writeBack(slot: Int, page: MediaAtlasPage) { writeBackSlot(slot, page.tileKey); dropFromTable(page.tileKey) }
    }

    /** One tile into [slot]: its payload, its look and its ground (its `g`, or its look when it has no payload), its paper. */
    private fun loadSlot(slot: Int, key: Long) {
        val id = layerId!!
        stats?.let { if (it.recording) it.mediaWindowLoads++ }
        paint.dropStalePayload(id, listOf(key), plane)
        val (ax, ay) = media.slotTexel(slot)
        media.clearSlot(ax, ay)
        var wetHere = false
        for (store in MediaStores.PAYLOAD) {
            val src = paint.mediaTile(id, store, key, plane) ?: continue
            if (store == "w0") wetHere = true
            for (dst in media.loadTargets(store)) media.blit(src, 0, 0, tile, tile, dst, ax, ay, ax + tile, ay + tile)
        }
        val look = paint.mediaTile(id, MEDIA_LOOK, key, plane)
        look?.let { media.blit(it, 0, 0, tile, tile, media.lookTexture(), ax, ay, ax + tile, ay + tile) }
        groundFromLook.remove(key)
        if (look != null && !paint.hasPayload(id, key, plane)) {
            media.blit(look, 0, 0, tile, tile, media.groundTexture(), ax, ay, ax + tile, ay + tile)
            groundFromLook.add(key)
        }
        // The table must hold the new tile before its paper is baked through it.
        val table = resident().toMutableMap().also { it[key] = slot }
        media.setResidency(table)
        media.bakeTile(key, paint.mediaPaper())
        if (wetHere && resumeOnLoad) media.resumeWater(tileRect(key))
    }

    private fun tileRect(key: Long) = floatArrayOf((Tiles.tx(key) * tile).toFloat(), (Tiles.ty(key) * tile).toFloat(),
        ((Tiles.tx(key) + 1) * tile).toFloat(), ((Tiles.ty(key) + 1) * tile).toFloat())

    private fun dropFromTable(key: Long) { media.setResidency(resident().filterKeys { it != key }) }

    /** An evicted or released tile's pending stores (and its first ground) into the layer: copy-on-write, as [flush] does. */
    private fun writeBackSlot(slot: Int, key: Long) {
        val id = layerId ?: return
        val stores = pendingStores.filter { it !in MediaStores.WATER || media.wetActive }
        if (stores.isEmpty()) return
        val (ax, ay) = media.slotTexel(slot)
        val targets = paint.writableMediaTiles(id, listOf(key), stores, plane)
        for (store in stores) {
            val src = media.current(store) ?: continue
            media.blit(src, ax, ay, ax + tile, ay + tile, targets.getValue(store).getValue(key), 0, 0, tile, tile)
        }
        if (key in groundFromLook) {
            val g = paint.writableMediaTiles(id, listOf(key), listOf(MediaStores.GROUND), plane).getValue(MediaStores.GROUND).getValue(key)
            media.blit(media.groundTexture(), ax, ay, ax + tile, ay + tile, g, 0, 0, tile, tile)
            groundFromLook.remove(key)
        }
    }

    // ---- the frame boundary (as MediaWindow.flush: the look every frame, the stores at the stroke's end) ----
    override fun flush(stores: Boolean) {
        val id = layerId ?: return
        if (id !in paint.layerIds()) {
            layerId = null; dryPending = false; pendingStores.clear(); pendingRect = null; pinned.clear(); slots.clear(); media.setResidency(emptyMap())
            media.takeDirty(); media.takeDirtyStores(); media.stopWater(); return
        }
        if (dryPending && !paint.mediaStrokeInProgress) { dryPending = false; dropWater(id) }
        val rect = media.takeDirty()
        pendingStores += media.takeDirtyStores()
        if (rect != null) {
            pendingRect = union(pendingRect, rect)
            for (key in keysIn(rect)) slots.resident(page(key))?.let { slots.markDirty(it.page) }
            writeLook(id, rect)
        }
        if (stores) writeStores(id) else reserveStores()
        // The frame is over: its tiles may leave again (running water keeps its own, see [need]).
        for (p in pinned) if (slots.resident(p) != null) slots.unpin(p)
        pinned.clear()
    }

    private fun keysIn(r: FloatArray): List<Long> = MediaAtlasPlacement.tiles(r[0].toDouble(), r[1].toDouble(), r[2].toDouble(), r[3].toDouble(), 0)

    private fun writeLook(id: String, dirty: FloatArray) {
        val h = lookHalo.toFloat()
        // Recompute the dirty area and what it shades around it, with everything the look reads resident (no window edge).
        val out = floatArrayOf(dirty[0] - h, dirty[1] - h, dirty[2] + h, dirty[3] + h)
        val r = floatArrayOf(floor(out[0]), floor(out[1]), ceil(out[2]), ceil(out[3]))
        try { need(r, MediaAtlasPlacement.LOOK_REACH) } catch (e: MediaRoomException) {
            // The look of what was painted still shows; only its shading reach is cut short (logged, never silent).
            android.util.Log.w("JoyBrushMediaAtlas", "look reach not resident: ${e.message}")
        }
        val keys = keysIn(r).filter { slots.resident(page(it)) != null }
        if (keys.isEmpty()) return
        renderLook(r)
        val targets = paint.writableMediaTiles(id, keys, listOf(MEDIA_LOOK), plane).getValue(MEDIA_LOOK)
        for (key in keys) {
            val slot = slots.resident(page(key))!!.slot
            val (ax, ay) = media.slotTexel(slot)
            val tx0 = Tiles.tx(key) * tile; val ty0 = Tiles.ty(key) * tile
            val x0 = maxOf(r[0].toInt(), tx0); val y0 = maxOf(r[1].toInt(), ty0); val x1 = minOf(r[2].toInt(), tx0 + tile); val y1 = minOf(r[3].toInt(), ty0 + tile)
            if (x1 <= x0 || y1 <= y0) continue
            media.blit(media.lookTexture(), ax + x0 - tx0, ay + y0 - ty0, ax + x1 - tx0, ay + y1 - ty0, targets.getValue(key), x0 - tx0, y0 - ty0, x1 - tx0, y1 - ty0)
        }
    }

    private fun writeStores(id: String) {
        val rect = pendingRect
        pendingRect = null
        val stores = pendingStores.toMutableList()
        pendingStores.clear()
        if (rect == null || stores.isEmpty()) return
        val keys = keysIn(rect).filter { slots.resident(page(it)) != null }
        if (keys.isEmpty()) return
        val water = stores.filter { it in MediaStores.WATER }
        val dried = water.isNotEmpty() && !media.wetActive
        if (dried && paint.mediaStrokeInProgress) dryPending = true
        val drop = dried && !dryPending
        if (drop) stores.removeAll(MediaStores.WATER)
        val firstTouch = if (stores.isEmpty()) emptyList() else keys.filter { it in groundFromLook }
        val grounds = if (firstTouch.isEmpty()) emptyMap() else paint.writableMediaTiles(id, firstTouch, listOf(MediaStores.GROUND), plane).getValue(MediaStores.GROUND)
        val targets = if (stores.isEmpty()) emptyMap() else paint.writableMediaTiles(id, keys, stores, plane)
        for (key in keys) {
            val res = slots.resident(page(key))!!
            val (ax, ay) = media.slotTexel(res.slot)
            for (store in stores) {
                val src = media.current(store) ?: continue
                media.blit(src, ax, ay, ax + tile, ay + tile, targets.getValue(store).getValue(key), 0, 0, tile, tile)
            }
            grounds[key]?.let { media.blit(media.groundTexture(), ax, ay, ax + tile, ay + tile, it, 0, 0, tile, tile) }
            slots.clean(res.page)
        }
        groundFromLook.removeAll(firstTouch.toSet())
        if (drop) dropWater(id)
    }

    private fun reserveStores() {
        val id = layerId ?: return
        val rect = pendingRect ?: return
        val keys = keysIn(rect).filter { slots.resident(page(it)) != null }
        val stores = pendingStores.filter { it !in MediaStores.WATER || media.wetActive }
        if (keys.isEmpty() || stores.isEmpty()) return
        paint.writableMediaTiles(id, keys, stores, plane)
        val firstTouch = keys.filter { it in groundFromLook }
        if (firstTouch.isNotEmpty()) paint.writableMediaTiles(id, firstTouch, listOf(MediaStores.GROUND), plane)
    }

    private fun dropWater(id: String) {
        val keys = heldKeys()
        for (store in MediaStores.WATER) paint.dropMediaTiles(id, store, keys.filter { paint.mediaTile(id, store, it, plane) != null }, plane)
    }

    override fun restored(layer: String, plane: String?, keys: Set<Long>): Boolean {
        if (layer != layerId || plane != this.plane) return false
        val mine = heldKeys().toSet()
        if (keys.none { it in mine }) return false
        media.stopWater()
        resumeOnLoad = false
        try { for (r in slots.residents()) loadSlot(r.slot, r.page.tileKey) } finally { resumeOnLoad = true }
        media.takeDirty(); media.takeDirtyStores()
        return true
    }

    override fun lookWritten(layer: String, plane: String?, keys: Set<Long>) {
        if (layer != layerId || plane != this.plane || media.asleep) return
        for (key in keys) {
            val res = slots.resident(page(key)) ?: continue
            loadSlot(res.slot, key)
            media.stateRestored(tileRect(key))
        }
    }

    override fun wakeWater() {
        val id = layerId ?: return
        if (media.wetActive) return
        val wet = heldKeys().filter { paint.mediaTile(id, "w0", it, plane) != null }
        if (wet.isEmpty()) return
        media.resumeWater(floatArrayOf(wet.minOf { Tiles.tx(it) } * tile.toFloat(), wet.minOf { Tiles.ty(it) } * tile.toFloat(),
            (wet.maxOf { Tiles.tx(it) } + 1) * tile.toFloat(), (wet.maxOf { Tiles.ty(it) } + 1) * tile.toFloat()))
    }

    private fun union(a: FloatArray?, b: FloatArray?): FloatArray? = when {
        a == null -> b?.copyOf()
        b == null -> a.copyOf()
        else -> floatArrayOf(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[2], b[2]), maxOf(a[3], b[3]))
    }
}
