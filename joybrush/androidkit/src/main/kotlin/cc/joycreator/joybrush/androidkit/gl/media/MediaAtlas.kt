package cc.joycreator.joybrush.androidkit.gl.media

import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.gl.MEDIA_LOOK
import cc.joycreator.joybrush.androidkit.gl.MediaRoomException
import cc.joycreator.joybrush.core.media.MediaAtlasPage
import cc.joycreator.joybrush.core.media.MediaAtlasPlacement
import cc.joycreator.joybrush.core.media.MediaAtlasSlots
import cc.joycreator.joybrush.core.media.MediaAtlasWater
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Media Atlas v2, slice B2 (tasks/joybrush/specs/MEDIA_ATLAS_V2_20261016.md): the media engine's state on ONE layer with
 * no window. Layer tiles are placed freely in [media]'s slots (15 resident + 1 staging, A1's [MediaAtlasSlots]) and loaded
 * where the passes need them (the residency rule, [MediaAtlasPlacement]: each pass's rectangle and its read reach). B3:
 * a frame is admitted whole before it draws ([admit]); room comes from writing back the least recently used tiles that
 * are not held, so a stroke crosses any number of tiles and is refused, in words and before drawing, only if one frame
 * needs more than the atlas holds. There is no edge to move, so nothing is ever clipped or creased by one.
 *
 * Same contract as [MediaWindow] towards [MediaCanvas] ([MediaPlace]) and towards the layer's stores: the payload, the
 * look and the ground travel through [GlPaintEngine.writableMediaTiles] (copy-on-write, so undo and the running-water rule
 * hold), a tile with no payload lies on its own look (ground-from-look), dried water is dropped, and undo stops the water.
 * Proved headless bit for bit against one big window (tools/media_atlas_b2_check.js); off by default
 * ([MediaAtlasGl.enabled]) until the device probe has passed. GL thread only.
 */
class MediaAtlas(private val paint: GlPaintEngine, val media: MediaLayerEngine, private val renderLook: (FloatArray) -> Unit,
                 /** Dries the water of these tiles (layer px) where it lies: [MediaLayerEngine.dryTilesNow] with the canvas's paper. */
                 private val dryTiles: (List<FloatArray>) -> Unit = {}) : MediaPlace {
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

    // ---- B3 steps 3-5: the water, tile by tile (MediaAtlasWater; the Lead's D1/D2) ----
    private val water = MediaAtlasWater()
    /** What the water needs held (its tiles and their 8-neighbours, as last admitted): pinned before anything else loads. */
    private var waterHeld: Set<Long> = emptySet()
    /** This frame's own drawing (its passes' rects, where the brush put water): where the look is recomputed besides the water. */
    private val frameDirty = ArrayList<FloatArray>()
    private var framesSinceQuery = 0
    /** Slot -> tile when the water was last measured: a measurement counts only for tiles still in the same slot. */
    private var querySlots: Map<Int, Long>? = null
    /** Quiet components dried in place, and drip tails dried and taken out of the water (D1, D2), since this atlas was made. */
    var settledComponents = 0; private set
    var drainedTails = 0; private set

    init {
        media.onNeed = { rect, reach -> need(rect, reach) }
        media.onNeedWater = { rect, reach -> needWater(rect, reach) }
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
        pinned.clear(); admitted = false; slots.clear(); media.setResidency(emptyMap())
        resetWater()
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
        // Loaded, not held: the first frame's admission decides what it needs (these tiles must not crowd it out).
        unpinAll()
    }

    /** The atlas has no edge to keep a stroke inside: each frame is admitted whole instead ([admit]). */
    override fun follow(docRect: DoubleArray, penX: Double, penY: Double) {}

    /** True between a frame's [admit] and its [flush]: every pass of the frame should find its tiles held already. */
    private var admitted = false
    /** Passes that asked past their frame's admission (a planning bug; the pass is still served if there is room). */
    var admissionMisses = 0; private set

    override fun admit(needs: List<MediaLayerEngine.Need>) {
        if (layerId == null || needs.isEmpty()) return
        val now = nowMs()
        if (!media.wetActive && needs.none { it.water }) resetWater()
        val keys = LinkedHashSet<Long>()
        frameDirty.clear()
        var box: FloatArray? = null
        var waterRect: FloatArray? = null
        for (n in needs) {
            if (n.water) { waterRect = n.rect; continue }
            keys += tilesOf(n.rect, n.reach)
            frameDirty += n.rect
            box = union(box, n.rect)
            if (n.input) water.touched(intArrayOf(floor(n.rect[0]).toInt(), floor(n.rect[1]).toInt(), ceil(n.rect[2]).toInt(), ceil(n.rect[3]).toInt()), now)
        }
        // The look at the frame's end ([writeLook]): the dirty area (inside these rects), its halo, whole pixels, its reach.
        box?.let { keys += tilesOf(it, lookHalo + 1 + MediaAtlasPlacement.LOOK_REACH) }
        waterHeld = if (waterRect != null) admitWater(waterRect, keys, box ?: waterRect, now) else emptySet()
        keys += waterHeld
        media.waterSkip = water.excluded.toSet()
        hold(keys)
        admitted = true
    }

    /**
     * The water's part of a frame: only what holds water and what that water reads (MediaAtlasWater.mustStay), never the
     * whole simulated rectangle, so a long wet stroke moves on like a dry one. Short of room, D1 then D2 make it: whole quiet
     * components dry in place, farthest from the pen first; then the oldest drip loses its far end. Refused only when this
     * frame itself cannot fit.
     */
    private fun admitWater(waterRect: FloatArray, frame: Set<Long>, pen: FloatArray, now: Long): Set<Long> {
        val domain = tilesOf(waterRect, 0)
        val reads = tilesOf(waterRect, MediaAtlasPlacement.WET_REACH)
        for (k in domain) if (water.state(k) == null) water.entered(k, now, storedWater(k))
        val held = pinned.mapTo(HashSet()) { it.tileKey }
        var must = water.mustStay(domain, reads)
        if (MediaAtlasPlacement.fits(slots.capacity, frame + must, held)) return must
        val plan = water.plan(domain, reads, frame, held, slots.capacity, (pen[0] + pen[2]) / 2.0, (pen[1] + pen[3]) / 2.0, now)
            ?: throw MediaRoomException("This watercolour stroke reaches further than this phone can hold at once. " +
                "Paint a smaller area at a time.")
        for (a in plan) {
            // Every tile here holds water, so it is resident (water never leaves), and so are its neighbours (it reads them).
            val rects = a.keys.map(::tileRect)
            // Where their water was: the look changes only there (and its halo), and everything that reads is held.
            val wet = a.keys.mapNotNull { water.extent(it) }.map { e -> floatArrayOf(e[0].toFloat(), e[1].toFloat(), e[2].toFloat(), e[3].toFloat()) }
            dryTiles(rects)
            water.dried(a.keys, a.drain)
            pendingStores += media.takeDirtyStores()
            for (k in a.keys) slots.resident(page(k))?.let { slots.markDirty(it.page) }
            // Their look now, while everything it reads is still here: once dry they may leave this very frame.
            for (r in wet) writeLook(layerId!!, r, hold = false)
            if (a.drain) {
                drainedTails++
                android.util.Log.i(TAG, "watercolour: a drip's tail dried where it lay to make room (${a.keys.size} area)")
            } else settledComponents++
        }
        must = water.mustStay(domain, reads)
        return must
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    private fun storedWater(key: Long): Boolean = paint.mediaTile(layerId!!, "w0", key, plane) != null

    private fun resetWater() {
        water.reset(); waterHeld = emptySet(); media.waterSkip = emptySet(); querySlots = null; framesSinceQuery = 0
    }

    /** The water passes' ask: after admission it is held by the water's rules; outside a frame (the Dry button), the same rules. */
    private fun needWater(rect: FloatArray, reach: Int) {
        if (layerId == null || admitted) return
        val now = nowMs()
        val domain = tilesOf(rect, 0)
        for (k in domain) if (water.state(k) == null) water.entered(k, now, storedWater(k))
        waterHeld = water.mustStay(domain, tilesOf(rect, reach))
        hold(waterHeld)
    }

    /** Takes in a finished measurement and starts the next one every [QUERY_FRAMES] frames while water runs. */
    private fun measureWater() {
        media.takeWaterActivity()?.let { a ->
            val slotsNow = querySlots
            querySlots = null
            if (slotsNow != null) applyActivity(a, slotsNow)
        }
        if (++framesSinceQuery >= QUERY_FRAMES && querySlots == null && media.requestWaterActivity()) {
            querySlots = resident().entries.associate { (k, s) -> s to k }
            framesSinceQuery = 0
        }
    }

    private fun applyActivity(a: FloatArray, at: Map<Int, Long>) {
        val now = nowMs()
        val perRow = media.slotCount.let { kotlin.math.sqrt(it.toDouble()).toInt() } * (tile / 16)
        val minMm = WET_MIN_MM
        for ((slot, key) in at) {
            if (slots.resident(page(key))?.slot != slot) continue
            val (ax, ay) = media.slotTexel(slot)
            var mw = 0f; var ms = 0f; var mf = 0f
            // Where the water is: the 16 px blocks holding any, as one rectangle (layer px).
            var ext: IntArray? = null
            var wetExt: IntArray? = null
            val tx0 = Tiles.tx(key) * tile; val ty0 = Tiles.ty(key) * tile
            for (j in 0 until tile / 16) for (i in 0 until tile / 16) {
                val o = ((ay / 16 + j) * perRow + ax / 16 + i) * 4
                mw = maxOf(mw, a[o]); ms = maxOf(ms, a[o + 1]); mf = maxOf(mf, a[o + 2])
                val b = intArrayOf(tx0 + i * 16, ty0 + j * 16, tx0 + i * 16 + 16, ty0 + j * 16 + 16)
                if (a[o] > minMm || a[o + 1] > 0f) ext = MediaAtlasWater.union(ext, b)
                if (a[o] > minMm) wetExt = MediaAtlasWater.union(wetExt, b)
            }
            water.observe(key, mw, ms, mf, now, minMm, FLOW_EPS, ext, wetExt)
        }
    }

    private fun layerPx(x: Double, y: Double): Pair<Float, Float> = media.toLayerPx(x, y)

    /**
     * The residency rule ([MediaLayerEngine.onNeed]): every tile [rect] (layer px) dilated by [reach] touches is resident and
     * held for this frame. After [admit] that is already true for every pass of the frame (the fast path); a pass asking
     * past its admission is counted and served if it fits, else refused before it draws.
     */
    private fun need(rect: FloatArray, reach: Int) {
        if (layerId == null) return
        val keys = tilesOf(rect, reach)
        if (keys.all { page(it) in pinned }) return
        if (admitted) {
            admissionMisses++
            android.util.Log.w(TAG, "a pass asked past its frame's admission (${keys.size} areas)")
        }
        hold(keys)
    }

    private fun tilesOf(r: FloatArray, reach: Int): List<Long> =
        MediaAtlasPlacement.tiles(r[0].toDouble(), r[1].toDouble(), r[2].toDouble(), r[3].toDouble(), reach)

    /**
     * Makes [keys] resident and held until [flush], atomically: first counts them with every tile that must stay (this
     * frame's, and what running water holds and reads) against the slots, and only then loads. Room
     * comes from writing back the least recently used unpinned tiles (A1's [MediaAtlasResidency]): a stroke moves on over
     * any number of tiles, and is only refused when ONE frame needs more than the atlas holds.
     */
    private fun hold(keys: Collection<Long>) {
        val wanted = keys.toSet()
        // Running water keeps what it holds and reads (MediaAtlasWater.mustStay, as last admitted); nothing else is held for it.
        if (media.wetActive) for (k in waterHeld) {
            val p = page(k)
            if (slots.resident(p) != null && p !in pinned) { slots.pin(p); pinned += p }
        }
        val held = pinned.mapTo(HashSet()) { it.tileKey }
        if (!MediaAtlasPlacement.fits(slots.capacity, wanted, held)) {
            throw MediaRoomException("This pencil, watercolour or oil stroke reaches further than this phone can hold at once " +
                "(${(wanted + held).size} areas of ${slots.capacity}). Paint a smaller area at a time.")
        }
        // Hold what is here first: loading the rest writes back and evicts whatever is not held.
        for (key in wanted) {
            val p = page(key)
            if (slots.resident(p) != null) { slots.touch(p); if (p !in pinned) { slots.pin(p); pinned += p } }
        }
        var loaded = false
        for (key in wanted) {
            val p = page(key)
            if (slots.resident(p) != null) continue
            residency.ensure(p) ?: throw MediaRoomException("There is no room for more pencil, watercolour or oil here right now.")
            slots.pin(p); pinned += p; loaded = true
        }
        if (loaded) media.setResidency(resident())
    }

    private fun unpinAll() {
        for (p in pinned) if (slots.resident(p) != null) slots.unpin(p)
        pinned.clear()
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
            layerId = null; dryPending = false; pendingStores.clear(); pendingRect = null; pinned.clear(); admitted = false; slots.clear(); media.setResidency(emptyMap())
            media.takeDirty(); media.takeDirtyStores(); media.stopWater(); return
        }
        if (dryPending && !paint.mediaStrokeInProgress) { dryPending = false; dropWater(id) }
        val rect = media.takeDirty()
        pendingStores += media.takeDirtyStores()
        if (rect != null) {
            pendingRect = union(pendingRect, rect)
            for (key in keysIn(rect)) slots.resident(page(key))?.let { slots.markDirty(it.page) }
            if (admitted && media.wetActive) {
                // The water's rectangle is mostly tiles that did not change (dry, maybe not even here): the look is redone
                // where the frame drew and where water lies (its extent), whose every read is held (MediaAtlasWater.mustStay).
                for (r in frameDirty) writeLook(id, r, hold = false)
                for (k in waterHeld) {
                    val e = water.extent(k) ?: continue
                    if (slots.resident(page(k)) != null) writeLook(id, floatArrayOf(e[0].toFloat(), e[1].toFloat(), e[2].toFloat(), e[3].toFloat()), hold = false)
                }
            } else writeLook(id, rect)
        }
        if (media.wetActive) measureWater() else if (querySlots != null || framesSinceQuery != 0) resetWater()
        if (stores) writeStores(id) else reserveStores()
        // The frame is over: its tiles may leave again (running water keeps its own, see [hold]).
        unpinAll()
        admitted = false
    }

    private fun keysIn(r: FloatArray): List<Long> = tilesOf(r, 0)

    /** [hold] false: everything the look reads is known to be here already (it must not be held: the frame is admitted). */
    private fun writeLook(id: String, dirty: FloatArray, hold: Boolean = true) {
        val h = lookHalo.toFloat()
        // Recompute the dirty area and what it shades around it, with everything the look reads resident (no window edge).
        val out = floatArrayOf(dirty[0] - h, dirty[1] - h, dirty[2] + h, dirty[3] + h)
        val r = floatArrayOf(floor(out[0]), floor(out[1]), ceil(out[2]), ceil(out[3]))
        if (!hold) {
            val missing = tilesOf(r, MediaAtlasPlacement.LOOK_REACH).count { slots.resident(page(it)) == null }
            if (missing > 0) android.util.Log.w(TAG, "look of a water tile with $missing of its reads not here (should not happen)")
        } else try { need(r, MediaAtlasPlacement.LOOK_REACH) } catch (e: MediaRoomException) {
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

    private companion object {
        const val TAG = "JoyBrushMediaAtlas"
        /** Frames between water measurements (B3 plan: ~8; quiet means quiet for MediaAtlasWater.quietMs). */
        const val QUERY_FRAMES = 8
        /**
         * Outflow (mm/s) above which water is running (a drip), whatever its extent does. A settling pool keeps 0.2-0.4 mm/s
         * feeding its drying edge (measured: tools/media_atlas_b3w_check.js), so it is not "running"; its quiet is that its
         * extent stops changing (MediaAtlasWater.observe).
         */
        const val FLOW_EPS = 1.0f
        /** Water deeper than this (mm) is water (WetConstants.minMm). */
        val WET_MIN_MM = cc.joycreator.joybrush.core.media.WetConstants().minMm.toFloat()
    }

    private fun union(a: FloatArray?, b: FloatArray?): FloatArray? = when {
        a == null -> b?.copyOf()
        b == null -> a.copyOf()
        else -> floatArrayOf(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[2], b[2]), maxOf(a[3], b[3]))
    }
}
