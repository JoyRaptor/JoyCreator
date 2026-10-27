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
                 private val dryTiles: (List<FloatArray>) -> Unit = {},
                 /** [renderLook] for several rectangles in one go (the program set once). */
                 private val renderLooks: (List<FloatArray>) -> Unit = { rs -> rs.forEach(renderLook) }) : MediaPlace {
    override var layerId: String? = null; private set
    override var plane: String? = null; private set
    override var stats: cc.joycreator.joybrush.androidkit.bench.FrameStats? = null
    override var legacyLook = false
    override var lookHalo = MediaWindow.LOOK_HALO_MAX

    private val tile get() = Tiles.SIZE
    // The window's reservation already pays for these slots (the same textures): the byte budget is one unit per slot.
    private val slots = MediaAtlasSlots(media.slotCount, 1L, media.slotCount.toLong(), media.slotColumns)
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
    /**
     * L2, the water park (ATLAS_ROOM_20261023): a tile whose water still runs leaves the atlas into it, exactly, and comes
     * back exactly (the f16 stores lose its flow and round its state). Off until [MediaAtlasPark.units] > 0.
     */
    private var park: MediaWaterPark? = null
    /** Tile key -> its park unit, for tiles out of the atlas whose exact state waits in [park]. */
    private val parked = HashMap<Long, Int>()
    /** In a block step (P2): tiles out of the atlas whose block ran this step, their next half (current is in [parked]). */
    private val parkedNext = HashMap<Long, Int>()
    /** Ring-only copies are temporary reads; their canonical water state remains owned by the park. */
    private val borrowedBlockTiles = HashSet<Long>()
    private var loadingBorrowedBlockTile: Long? = null
    private data class ParkOwnership(
        val key: Long,
        val parked: Int?,
        val parkedNext: Int?,
        val borrowed: Boolean,
        val groundFromLook: Boolean,
    )
    private class ParkAdmission(
        val incoming: ParkOwnership,
        val victim: ParkOwnership?,
        val residencyBefore: Map<Long, Int>,
        val previousPark: MediaWaterPark?,
        val parkedTotalBefore: Int,
    ) {
        val takenUnits = ArrayList<Int>(2)
        val deferredUnits = ArrayList<Int>(2)
        var consumeOnCommit: Long? = null
        var createdPark: MediaWaterPark? = null
    }
    private var parkAdmission: ParkAdmission? = null
    /** This frame's water did not fit whole (park on): its job runs in blocks instead of being refused. */
    private var outOfCore = false
    /** The water's tiles this frame: what blocks own; the rest of its rectangle is dry and beyond its reach. */
    private var waterDomainKeys: Set<Long> = emptySet()
    /** Pages a block holds while its passes run, besides the frame's own [pinned]. */
    private val blockPinned = LinkedHashSet<MediaAtlasPage>()
    /** Tiles whose block has run in the step under way. */
    private val doneThisStep = HashSet<Long>()
    /** Blocks run, tiles moved in for them (bench). */
    var blocksRun = 0; private set
    /** What the water needs held (its tiles and their 8-neighbours, as last admitted): pinned before anything else loads. */
    private var waterHeld: Set<Long> = emptySet()
    /** This frame's own drawing (its passes' rects, where the brush put water): where the look is recomputed besides the water. */
    private val frameDirty = ArrayList<FloatArray>()
    /**
     * This frame's wet dabs, their own boxes: brush input not yet in the water changes only the colour under it (the look adds
     * it), not the paint's body, so its look needs no shading halo; the halo comes with the water's look once a step takes
     * it in. The whole halo round each frame's dabs and their water margin was 200-450 k px a frame (10-21, Note 9).
     */
    private val frameInput = ArrayList<FloatArray>()
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

    /** The drawing this atlas's state was loaded from: none of it is written back once another has opened. */
    private val drawing = DocumentStamp { paint.documentGeneration }

    /** Another drawing opened since this loaded ([DocumentStamp]): let everything go unwritten. True when it did. */
    private fun staleDrawing(): Boolean {
        if (layerId == null || !drawing.stale) return false
        android.util.Log.w("JoyBrushMedia", "another drawing opened under the live paint: let it go unwritten")
        forget()
        return true
    }

    override fun releaseIfIdle(nowMs: Long): Boolean {
        if (layerId == null || media.asleep || media.wetActive || paint.mediaStrokeInProgress) return false
        if (nowMs - lastActiveMs < MediaWindow.IDLE_MS) return false
        release()
        return true
    }

    override fun release() {
        if (layerId == null || staleDrawing()) return
        flush()
        for (r in slots.residents()) if (r.dirty) writeBackSlot(r.slot, r.page.tileKey)
        pinned.clear(); admitted = false; slots.clear(); borrowedBlockTiles.clear(); media.setResidency(emptyMap()); media.waterPaused = emptySet()
        unpark()   // the water sleeps from its stores (as before the park)
        resetWater()
        media.sleep()
        layerId = null
    }

    override fun place(layer: String, plane: String?, docX: Double, docY: Double, resumeWater: Boolean) {
        staleDrawing()
        if (layerId != null && (layer != layerId || plane != this.plane)) release()
        if (layerId == null) {
            // Requirement 5 happens per tile as it loads (dropStalePayload in [loadSlot]).
            media.wake()
            drawing.stamp()
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

    /** This frame's look is drawn tile by tile after its passes (its paint and the look's whole ring did not fit at once). */
    private var lookInParts = false

    /** True between a frame's [admit] and its [flush]: every pass of the frame should find its tiles held already. */
    private var admitted = false
    /** Passes that asked past their frame's admission (a planning bug; the pass is still served if there is room). */
    var admissionMisses = 0; private set
    /** Tiles pushed out to make room (bench). */
    var evictions = 0; private set

    override fun admit(needs: List<MediaLayerEngine.Need>) = mediaTrace("JB.media.atlas.admit") { admitInner(needs) }
    private fun admitInner(needs: List<MediaLayerEngine.Need>) {
        if (staleDrawing() || layerId == null || needs.isEmpty()) return
        val now = nowMs()
        if (!media.wetActive && needs.none { it.water }) resetWater()
        outOfCore = false
        val keys = LinkedHashSet<Long>()
        frameDirty.clear()
        frameInput.clear()
        var box: FloatArray? = null
        var waterRect: FloatArray? = null
        for (n in needs) {
            if (n.water) { waterRect = n.rect; continue }
            keys += tilesOf(n.rect, n.reach)
            if (n.input && n.lookRect != null && media.waterBudget.isFinite()) frameInput += n.lookRect else frameDirty += n.rect
            box = union(box, n.rect)
            if (n.input) water.touched(intArrayOf(floor(n.rect[0]).toInt(), floor(n.rect[1]).toInt(), ceil(n.rect[2]).toInt(), ceil(n.rect[3]).toInt()), now)
        }
        // The look at the frame's end ([writeLook]): the dirty area (inside these rects), its halo, whole pixels, its reach.
        val lookKeys = box?.let { tilesOf(it, lookHalo + 1 + MediaAtlasPlacement.LOOK_REACH) }.orEmpty()
        val paintKeys = LinkedHashSet(keys)
        keys += lookKeys
        waterHeld = if (waterRect != null) admitWater(waterRect, keys, box ?: waterRect, now) else emptySet()
        keys += waterHeld
        // Tiles taken out of the water (D2) and tiles set aside (D3) are walls to it and are not simulated.
        media.waterSkip = water.excluded.toSet() + water.setAside
        lookInParts = false
        try { hold(keys) } catch (r: MediaRoomException) {
            // A frame whose paint fits but not with its look's whole shading ring (one palette-knife step: 20 areas of 18 on the
            // Note 9): the paint is held alone and the look is drawn afterwards tile by tile ([writeLook]), each part with its
            // own reads. Dry media only: running water must keep everything it reads at once.
            if (waterRect != null || media.wetActive || lookKeys.isEmpty()) throw r
            hold(paintKeys)
            lookInParts = true
        }
        admitted = true
    }

    /**
     * The water's part of a frame: only what holds water and what that water reads (MediaAtlasWater.mustStay), never the
     * whole simulated rectangle, so a long wet stroke moves on like a dry one. Short of room, D1 then D2 make it: whole quiet
     * components dry in place, farthest from the pen first; then the oldest drip loses its far end. Refused only when this
     * frame itself cannot fit.
     */
    private var lastSimLog = 0L
    private fun admitWater(waterRect: FloatArray, frame: Set<Long>, pen: FloatArray, now: Long): Set<Long> {
        val domain = tilesOf(waterRect, 0)
        val reads = tilesOf(waterRect, MediaAtlasPlacement.WET_REACH)
        for (k in domain) if (water.state(k) == null) water.entered(k, now, storedWater(k))
        val held = pinned.mapTo(HashSet()) { it.tileKey }
        val look = lookHalo + MediaAtlasPlacement.LOOK_REACH
        // While the pen paints, only the pools by it run; every other pool is set aside whole, kept as it is, and may leave the
        // atlas. With the pen up they run again, nearest first, as far as there is room (2026-10-21: the owner's crowded
        // drawing ran every pool on the page in every frame, and ran out of room).
        val (newlyPaused, _) = water.schedule(domain, reads, frame, held, painting = frame.isNotEmpty(), capacity = slots.capacity,
            penX = (pen[0] + pen[2]) / 2.0, penY = (pen[1] + pen[3]) / 2.0, lookReach = look, nowMs = now)
        if (newlyPaused.isNotEmpty()) media.holdStill(newlyPaused.mapNotNull { k -> slots.resident(page(k))?.let { media.slotTexel(it.slot) } })
        media.waterPaused = water.paused.toSet()
        var must = water.mustStay(domain, reads, look)
        if (now - lastSimLog > 1000) {   // measurement (2026-10-20): what the water passes draw vs what the water needs
            lastSimLog = now
            android.util.Log.i(TAG, "water: ${domain.count { slots.resident(page(it)) != null }} resident tiles in its rectangle, ${water.mustStay(domain, reads, 0).size} hold water or are within its reach, ${must.size} kept with the look; " +
                "of the ${domain.count(water::holdsWater)} wet, ${water.stillCount(domain, now, 500)} still for 0.5 s, ${water.stillCount(domain, now, 2000)} for 2 s; ${water.paused.size} paused, ${water.setAside.size} set aside")
        }
        waterDomainKeys = water.mustStay(domain, reads, 0)
        if (MediaAtlasPlacement.fits(slots.capacity, frame + must, held)) return must
        // ATLAS_ROOM P2: with the park on, water too wide for the atlas runs block by block (each block and its ring here in
        // turn, the rest parked exactly) instead of drying old water or refusing: slower when there is a lot of it, never refused.
        if (MediaAtlasPark.units > 0) {
            if (!outOfCore) android.util.Log.i(TAG, "water out of core: must stay ${must.size} + frame ${frame.size} > ${slots.capacity} slots; in blocks")
            outOfCore = true
            return emptySet()
        }
        val plan = water.plan(domain, reads, frame, held, slots.capacity, (pen[0] + pen[2]) / 2.0, (pen[1] + pen[3]) / 2.0, now, look)
            ?: run { android.util.Log.w(TAG, "water refused: frame ${frame.size}, water must stay ${must.size}, held ${held.size}, capacity ${slots.capacity}"); null }
            ?: throw MediaRoomException("This watercolour stroke reaches further than this phone can hold at once. " +
                "Paint a smaller area at a time.")
        for (a in plan) {
            // Every tile here holds water, so it is resident (water never leaves), and so are its neighbours (it reads them).
            val rects = a.keys.map(::tileRect)
            // Where their water was: the look changes only there (and its halo), and everything that reads is held.
            val wet = a.keys.mapNotNull { water.extent(it) }.map { e -> floatArrayOf(e[0].toFloat(), e[1].toFloat(), e[2].toFloat(), e[3].toFloat()) }
            // The tiles and everything their new look reads, here and held while it is drawn: a tail that came back into
            // the water may border a tile that left long ago (b3w, 2026-10-18: 86 px read from a tile not loaded).
            val brief = holdBriefly(a.keys + wet.flatMap { tilesOf(it, lookHalo + MediaAtlasPlacement.LOOK_REACH) },
                slots.residents().map { it.page.tileKey }.filter(water::holdsWater))
            dryTiles(rects)
            water.dried(a.keys, a.drain)
            pendingStores += media.takeDirtyStores()
            for (k in a.keys) slots.resident(page(k))?.let { slots.markDirty(it.page) }
            // Their look now, while everything it reads is still here: once dry they may leave this very frame.
            for (r in wet) writeLook(layerId!!, r, hold = false)
            for (p in brief) { if (slots.resident(p) != null) slots.unpin(p); pinned -= p }
            if (a.drain) {
                drainedTails++
                android.util.Log.i(TAG, "watercolour: a drip's tail dried where it lay to make room (${a.keys.size} area)")
            } else settledComponents++
        }
        must = water.mustStay(domain, reads, look)
        return must
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    private fun storedWater(key: Long): Boolean = paint.mediaTile(layerId!!, "w0", key, plane) != null

    private fun resetWater() {
        water.reset(); waterHeld = emptySet(); media.waterSkip = emptySet(); media.waterPaused = emptySet(); querySlots = null; framesSinceQuery = 0
    }

    /** The water passes' ask: after admission it is held by the water's rules; outside a frame (the Dry button), the same rules. */
    private fun needWater(rect: FloatArray, reach: Int) {
        if (layerId == null || admitted) return
        val now = nowMs()
        val domain = tilesOf(rect, 0)
        for (k in domain) if (water.state(k) == null) water.entered(k, now, storedWater(k))
        waterHeld = water.mustStay(domain, tilesOf(rect, reach), lookHalo + MediaAtlasPlacement.LOOK_REACH)
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
        val perRow = media.textureWidth / 16   // the measurement's rows: one value per 16 px of the atlas's width
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

    /**
     * Loads and pins [keys] for a moment (a draining tail's look); returns what it pinned, to let go after. Nothing in
     * [keep] (the tiles holding water) may be evicted to make room: its flow is not stored, so a water tile pushed out
     * mid-frame would come back without it, and rounded to half floats (b3w). None loaded if they cannot fit beside it.
     */
    private fun holdBriefly(keys: Collection<Long>, keep: Collection<Long>): List<MediaAtlasPage> {
        val wanted = keys.toSet()
        if (!MediaAtlasPlacement.fits(slots.capacity, wanted + keep.filter { slots.resident(page(it)) != null }, pinned.mapTo(HashSet()) { it.tileKey })) {
            android.util.Log.w(TAG, "a draining tail's look and its reads do not fit (${wanted.size} areas): drawn with what is here")
            return emptyList()
        }
        val added = ArrayList<MediaAtlasPage>()
        // What must stay is pinned while the rest loads, then let go with the rest (the frame's own hold pins it again).
        for (key in keep) { val p = page(key); if (p !in pinned && slots.resident(p) != null) { slots.pin(p); pinned += p; added += p } }
        var loaded = false
        for (key in wanted) {
            val p = page(key)
            if (p in pinned) continue
            if (slots.resident(p) != null) slots.touch(p)
            else { residency.ensure(p) ?: continue; loaded = true }
            slots.pin(p); pinned += p; added += p
        }
        if (loaded) media.setResidency(resident())
        return added
    }

    private fun unpinAll() {
        for (p in pinned) if (slots.resident(p) != null) slots.unpin(p)
        pinned.clear()
    }

    private val residency by lazy { MediaAtlasResidency(slots, backing) }
    private val backing = object : MediaAtlasBacking {
        override fun beginAdmission(page: MediaAtlasPage, victim: MediaAtlasSlots.Resident?) = beginParkAdmission(page.tileKey, victim?.page?.tileKey)
        override fun load(slot: Int, page: MediaAtlasPage) = mediaTrace("JB.media.atlas.load") { loadSlot(slot, page.tileKey) }
        override fun writeBack(slot: Int, page: MediaAtlasPage) = mediaTrace("JB.media.atlas.writeBack") {
            writeBackSlot(slot, page.tileKey)
            borrowedBlockTiles.remove(page.tileKey)
            dropFromTable(page.tileKey)
        }
        override fun evicted(slot: Int, page: MediaAtlasPage) { evictions++; parkIfLive(slot, page.tileKey) }
        override fun prepareCommitAdmission(page: MediaAtlasPage, victim: MediaAtlasSlots.Resident?) = prepareParkAdmissionCommit()
        override fun commitAdmission(page: MediaAtlasPage, victim: MediaAtlasSlots.Resident?) = commitParkAdmission()
        override fun rollbackAdmission(page: MediaAtlasPage, victim: MediaAtlasSlots.Resident?) = rollbackParkAdmission()
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
            media.loadStoreTile(store, src, ax, ay)
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
        mediaTrace("JB.media.atlas.table") { media.setResidency(table) }
        // Parked water comes back exactly, over what the stores just loaded (their f16 copy, no flow). Inside a block step
        // (nothing may finish the job) into the current half and the other: the stores' paint first in both halves too.
        if (media.waterInBlocks) {
            media.mirrorPaint(slot)
            if (loadingBorrowedBlockTile == key) {
                borrowedBlockTiles += key
                parked[key]?.let { unit -> mediaTrace("JB.media.park.ring") { media.loadHalf(park!!, unit, slot, both = false, ringOnly = true) } }
            } else if (parkAdmission != null) {
                parkAdmission!!.consumeOnCommit = key
                parked[key]?.let { unit -> mediaTrace("JB.media.park.in") { media.loadHalf(park!!, unit, slot, both = true, ringOnly = false) } }
                parkedNext[key]?.let { unit -> mediaTrace("JB.media.park.next.in") { media.loadHalf(park!!, unit, slot, both = false, ringOnly = false, asNext = true) } }
            } else {
                parked.remove(key)?.let { unit -> mediaTrace("JB.media.park.in") { media.loadHalf(park!!, unit, slot, both = true, ringOnly = false) }; park!!.give(unit) }
                parkedNext.remove(key)?.let { unit -> mediaTrace("JB.media.park.next.in") { media.loadHalf(park!!, unit, slot, both = false, ringOnly = false, asNext = true) }; park!!.give(unit) }
            }
        } else if (parkAdmission != null) {
            parkAdmission!!.consumeOnCommit = key
            parked[key]?.let { unit -> mediaTrace("JB.media.park.in") { media.unparkSlot(park!!, unit, slot) } }
        } else parked.remove(key)?.let { unit -> mediaTrace("JB.media.park.in") { media.unparkSlot(park!!, unit, slot) }; park!!.give(unit) }
        mediaTrace("JB.media.atlas.bake") { media.bakeTile(key, paint.mediaPaper()) }
        if (wetHere && resumeOnLoad) media.resumeWater(tileRect(key))
    }

    private fun tileRect(key: Long) = floatArrayOf((Tiles.tx(key) * tile).toFloat(), (Tiles.ty(key) * tile).toFloat(),
        ((Tiles.tx(key) + 1) * tile).toFloat(), ((Tiles.ty(key) + 1) * tile).toFloat())

    private fun dropFromTable(key: Long) { media.setResidency(resident().filterKeys { it != key }) }

    private fun beginParkAdmission(incoming: Long, victim: Long?) {
        check(parkAdmission == null) { "atlas park admission is already active" }
        fun snapshot(key: Long) = ParkOwnership(key, parked[key], parkedNext[key], key in borrowedBlockTiles, key in groundFromLook)
        parkAdmission = ParkAdmission(
            incoming = snapshot(incoming),
            victim = victim?.let(::snapshot),
            residencyBefore = resident(),
            previousPark = park,
            parkedTotalBefore = parkedTotal,
        )
    }

    /** Commit consumed incoming park units and old units replaced by this victim, after slot publication. */
    private fun commitParkAdmission() {
        val a = checkNotNull(parkAdmission) { "atlas park admission is missing" }
        val p = park
        val consume = a.consumeOnCommit
        if (consume != null && p != null) {
            parked.remove(consume)?.let(p::give)
            parkedNext.remove(consume)?.let(p::give)
        }
        if (p != null) {
            var i = 0
            while (i < a.deferredUnits.size) { p.give(a.deferredUnits[i]); i++ }
        }
        a.takenUnits.clear()
        parkAdmission = null
    }

    /** Do allocation and ownership checks before MediaAtlasSlots publishes the incoming page. */
    private fun prepareParkAdmissionCommit() {
        val a = checkNotNull(parkAdmission) { "atlas park admission is missing" }
        val key = a.consumeOnCommit
        val current = key?.let { parked[it] }
        val next = key?.let { parkedNext[it] }
        val count = (if (current != null) 1 else 0) + (if (next != null) 1 else 0) + a.deferredUnits.size
        if (count == 0) return
        val release = IntArray(count)
        var at = 0
        if (current != null) release[at++] = current
        if (next != null) release[at++] = next
        var d = 0
        while (d < a.deferredUnits.size) { release[at++] = a.deferredUnits[d++] }
        val pool = checkNotNull(park) { "parked units have no pool" }
        for (i in 0 until count) {
            pool.checkCanGive(release[i])
            for (j in 0 until i) check(release[j] != release[i]) { "park unit ${release[i]} would be released twice" }
        }
    }

    /** Restore park ownership and the pre-admission texture table; never publishes failed staging pixels. */
    private fun rollbackParkAdmission() {
        val a = checkNotNull(parkAdmission) { "atlas park admission is missing" }
        var cleanupFailure: Throwable? = null
        fun attempt(block: () -> Unit) {
            try { block() } catch (failure: Throwable) {
                val first = cleanupFailure
                if (first == null) cleanupFailure = failure else if (first !== failure) first.addSuppressed(failure)
            }
        }
        try {
            fun restore(o: ParkOwnership) {
                parked.remove(o.key); o.parked?.let { parked[o.key] = it }
                parkedNext.remove(o.key); o.parkedNext?.let { parkedNext[o.key] = it }
                borrowedBlockTiles.remove(o.key); if (o.borrowed) borrowedBlockTiles += o.key
                groundFromLook.remove(o.key); if (o.groundFromLook) groundFromLook += o.key
            }
            restore(a.incoming); a.victim?.let(::restore)
            park = a.previousPark
            parkedTotal = a.parkedTotalBefore
            attempt { media.setResidency(a.residencyBefore) }
            val pool = a.createdPark ?: a.previousPark
            if (pool != null) {
                var i = 0
                while (i < a.takenUnits.size) { val unit = a.takenUnits[i++]; attempt { pool.give(unit) } }
            }
            a.takenUnits.clear()
            a.createdPark?.let { attempt { it.release() } }
        } finally {
            parkAdmission = null
        }
        cleanupFailure?.let { throw it }
    }

    private fun takeParkUnit(pool: MediaWaterPark): Int? = pool.take()?.also { parkAdmission?.takenUnits?.add(it) }

    private fun giveParkUnit(pool: MediaWaterPark, unit: Int) {
        val a = parkAdmission
        if (a == null) pool.give(unit)
        else if (a.takenUnits.remove(unit)) pool.give(unit)
        else a.deferredUnits += unit
    }

    /**
     * A tile leaving while its water still runs (a pool set aside, then pushed out for room) waits in the park, exactly; its
     * stores are written back as well (undo and saving read those). Not with brush input waiting there (the park holds none),
     * nor when the park is off or full: then it leaves as before, through its stores.
     */
    private fun parkIfLive(slot: Int, key: Long) {
        if (key in borrowedBlockTiles) return
        val units = MediaAtlasPark.units
        if (units <= 0 || !media.wetActive || !media.hasWaterTextures || media.inputPendingIn(key)) return
        val inBlocks = media.waterInBlocks
        // In a block step every tile the water owns carries the step's state; outside one, live water only.
        if (if (inBlocks) key !in waterDomainKeys else !water.holdsWater(key)) return
        val p = park ?: MediaWaterPark(media.waterCell, units).also { park = it; parkAdmission?.createdPark = it }
        val unit = takeParkUnit(p) ?: run { android.util.Log.w(TAG, "water park full (${p.capacity} tiles): a running tile leaves through its stores"); return }
        if (inBlocks) {
            // Inside the job (nothing may finish it): its current half, and its next half if its block has run this step.
            mediaTrace("JB.media.park.out") { media.parkHalf(slot, p, unit, next = false) }
            if (key in doneThisStep) {
                val n = takeParkUnit(p) ?: run { giveParkUnit(p, unit); android.util.Log.w(TAG, "water park full mid-step"); return }
                media.parkHalf(slot, p, n, next = true)
                parkedNext.put(key, n)?.let { giveParkUnit(p, it) }
            }
        } else mediaTrace("JB.media.park.out") { media.parkSlot(key, slot, p, unit) }
        parked.put(key, unit)?.let { giveParkUnit(p, it) }
        parkedTotal++
    }

    /**
     * The atlas as a block host (ATLAS_ROOM P2): a job whose water did not fit this frame ([outOfCore]) runs block by block,
     * each block's tiles and their ring brought in (from the park, or their stores) and held while its passes run.
     */
    private val blocks = object : WaterBlockHost {
        override fun fitsWhole(cells: IntArray, skip: Set<Long>): Boolean = !outOfCore
        override fun dry(cells: IntArray): Set<Long> {
            val all = MediaAtlasPlacement.tiles(cells[0].toDouble(), cells[1].toDouble(), cells[2].toDouble(), cells[3].toDouble(), 0)
            return all.filterTo(HashSet()) { it !in waterDomainKeys }
        }
        override fun enter(block: cc.joycreator.joybrush.core.media.WaterBlocks.Block, k: Int) {
            blocksRun++
            val need = (block.own + block.ring).toSet()
            // Brush input not yet in the water never leaves (the park holds none).
            for (r in slots.residents()) if (media.inputPendingIn(r.page.tileKey)) pinBlock(r.page)
            if (!MediaAtlasPlacement.fits(slots.capacity, need, (pinned + blockPinned).mapTo(HashSet()) { it.tileKey }))
                throw MediaRoomException("A block of water and its ring do not fit beside what the frame holds (${need.size} areas of ${slots.capacity}).")
            // A prior ring visit supplied read-only planes. Promotion needs canonical parked halves, not that partial copy.
            for (key in block.own) if (key in borrowedBlockTiles) dropBorrowedBlockTile(key)
            for (key in need) {
                val p = page(key)
                if (slots.resident(p) == null) {
                    resumeOnLoad = false
                    loadingBorrowedBlockTile = key.takeIf { it !in block.own }
                    try { residency.ensure(p) ?: throw MediaRoomException("There is no room for this water's block right now.") } finally {
                        loadingBorrowedBlockTile = null
                        resumeOnLoad = true
                    }
                    media.setResidency(resident())
                } else slots.touch(p)
                pinBlock(p)
            }
            // The look of the block's own tiles, now that they and what their look reads are here: the last finished step.
            layerId?.let { id -> writeLookOnce(id, emptyList(), block.own.map(::tileRect)) }
        }
        override fun leave(block: cc.joycreator.joybrush.core.media.WaterBlocks.Block, k: Int) {
            doneThisStep += block.own
            for (key in block.own) slots.resident(page(key))?.let { slots.markDirty(it.page) }
            unpinBlocks()
        }
        override fun stepDone(k: Int) {
            val p = park
            dropBorrowedBlockTiles()
            for ((t, u) in parkedNext) { val old = parked.put(t, u); if (old != null && p != null) p.give(old) }
            parkedNext.clear(); doneThisStep.clear()
        }
        override fun jobDone() {
            unpinBlocks()
            dropBorrowedBlockTiles()
            val p = park
            if (p != null) for (u in parkedNext.values) p.give(u)
            parkedNext.clear(); doneThisStep.clear()
        }
    }

    init { media.blockHost = blocks }   // after [blocks] is made (initialisers run in order)

    private fun pinBlock(p: MediaAtlasPage) {
        if (p in pinned || p in blockPinned || slots.resident(p) == null) return
        slots.pin(p); blockPinned += p
    }

    private fun unpinBlocks() {
        for (p in blockPinned) if (slots.resident(p) != null) slots.unpin(p)
        blockPinned.clear()
    }

    /** Discard borrowed atlas copies without touching their canonical parked current half. */
    private fun dropBorrowedBlockTiles() {
        for (key in borrowedBlockTiles.toList()) dropBorrowedBlockTile(key)
        borrowedBlockTiles.clear()
    }

    private fun dropBorrowedBlockTile(key: Long) {
        val resident = slots.resident(page(key))
        if (resident != null) check(resident.pins == 0) { "a borrowed block tile is still pinned" }
        borrowedBlockTiles.remove(key)
        if (resident == null) return
        // Dirty-region accounting can include a ring tile, but its borrowed planes are incomplete and must never be saved.
        if (resident.dirty) slots.clean(resident.page)
        // remove() has no backing write; the park remains the canonical water owner.
        slots.remove(resident.page)
        dropFromTable(key)
    }

    /** Parked state that no longer matches the stores (an undo, a look written from outside, the layer let go): forgotten. */
    private fun unpark(keys: Collection<Long>? = null) {
        val p = park
        val drop = keys?.toList() ?: (parked.keys + parkedNext.keys + borrowedBlockTiles).toSet().toList()
        for (k in drop) {
            if (p != null) { parked.remove(k)?.let(p::give); parkedNext.remove(k)?.let(p::give) }
            if (k in borrowedBlockTiles) dropBorrowedBlockTile(k)
        }
        if (p != null && parked.isEmpty() && parkedNext.isEmpty()) { p.release(); park = null }
    }

    /** Tiles that went into the park since this atlas was made (bench). */
    var parkedTotal = 0; private set

    /** An evicted or released tile's pending stores (and its first ground) into the layer: copy-on-write, as [flush] does. */
    private fun writeBackSlot(slot: Int, key: Long) {
        if (key in borrowedBlockTiles) return
        val id = layerId ?: return
        val stores = pendingStores.filter { it !in MediaStores.WATER || media.wetActive }
        if (stores.isEmpty()) return
        val (ax, ay) = media.slotTexel(slot)
        val targets = paint.writableMediaTiles(id, listOf(key), stores, plane)
        for (store in stores) {
            media.writeStoreTile(store, targets.getValue(store).getValue(key), ax, ay)
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
        if (staleDrawing()) return
        if (id !in paint.layerIds()) { forget(); return }
        if (dryPending && !paint.mediaStrokeInProgress) { dryPending = false; dropWater(id) }
        val rect = media.takeDirty()
        pendingStores += media.takeDirtyStores()
        if (rect != null) {
            pendingRect = union(pendingRect, rect)
            for (key in keysIn(rect)) slots.resident(page(key))?.let { slots.markDirty(it.page) }
        }
        if (admitted && media.wetActive) {
            // The water's rectangle is mostly tiles that did not change (dry, maybe not even here): the look is redone
            // where the frame drew and where water lies (its extent), whose every read is held (MediaAtlasWater.mustStay).
            // Redoing still water's look only a few tiles a frame (10-21 night) left tiles showing different moments side by
            // side for many frames: a straight edge on screen. No tile-shaped decisions on water.
            // Water jobs (WATER_JOBS_20261021): the frame's own drawing at once (the look adds the brush input not yet in the
            // water, so a mark shows the frame it is drawn); the water's look each time a step has finished, drawn part by
            // part within the frame's [lookBudget] and copied out to the layer all at once.
            if (frameDirty.isNotEmpty() || frameInput.isNotEmpty()) writeLookOnce(id, frameDirty, frameInput)
            if (media.waterSteps != lookedSteps) {
                lookedSteps = media.waterSteps
                if (lookTodo.isEmpty() && lookDrawn.isEmpty()) startWaterLook() else lookAgain = true
            }
            advanceWaterLook(id, lookBudget)
        } else {
            if (lookTodo.isNotEmpty() || lookDrawn.isNotEmpty()) advanceWaterLook(id, Double.POSITIVE_INFINITY)
            if (rect != null) writeLook(id, rect)
        }
        if (media.wetActive) measureWater() else if (querySlots != null || framesSinceQuery != 0) resetWater()
        if (MediaAtlasDropDry.on && media.wetActive && !paint.mediaStrokeInProgress) dropDryWater(id)
        if (stores) {
            writeStores(id)
            // Water jobs: dabs the water had not taken in yet are not in what was just written; written in place once they
            // are (below), or before the next stroke starts ([payOwedStores]), so undo always has the whole stroke.
            storesOwed = media.inputPending
        } else if (storesOwed && !media.inputPending) { writeStores(id); storesOwed = false }
        else reserveStores()
        // The frame is over: its tiles may leave again (running water keeps its own, see [hold]).
        unpinAll()
        admitted = false
    }

    private fun keysIn(r: FloatArray): List<Long> = tilesOf(r, 0)
    /** The water step count ([MediaLayerEngine.waterSteps]) the water's look was last drawn at. */
    private var lookedSteps = -1L

    // ---- the water's look, part by part (WATER_JOBS_20261021) ----
    /** How much of the water's look a frame draws: px x [LOOK_WEIGHT]; infinite = all of it in the frame (as before jobs). */
    var lookBudget = Double.POSITIVE_INFINITY
    /** Parts (one per tile, layer px) still to draw; parts drawn into the look texture, with the slot they were drawn in. */
    private val lookTodo = LinkedHashMap<Long, FloatArray>()
    private val lookDrawn = LinkedHashMap<Long, Pair<FloatArray, Int>>()
    /** A step finished while a look was under way: another look once it is out. */
    private var lookAgain = false

    private fun startWaterLook() {
        val ext = waterHeld.mapNotNull { k -> water.extent(k)?.takeIf { slots.resident(page(k)) != null } }
            .map { e -> floatArrayOf(e[0].toFloat(), e[1].toFloat(), e[2].toFloat(), e[3].toFloat()) }
        lookTodo.putAll(lookParts(ext))
    }

    /**
     * Draws the water's look part by part until [budget] is spent (at least one part); once every part is drawn, copies them
     * all out to the layer's tiles together, so no tile shows a different moment from its neighbour for longer than one look.
     */
    private fun advanceWaterLook(id: String, budget: Double) {
        var spent = 0.0
        val todo = lookTodo.entries.iterator()
        val now = ArrayList<FloatArray>()
        while (todo.hasNext()) {
            val (key, r) = todo.next()
            val cost = (r[2] - r[0]).toDouble() * (r[3] - r[1]) * LOOK_WEIGHT
            if (spent > 0.0 && spent + cost > budget) break
            todo.remove()
            val res = slots.resident(page(key)) ?: continue
            // Everything this part's look reads must be here (it was while the water held it; a tile let go since is skipped).
            if (tilesOf(r, MediaAtlasPlacement.LOOK_REACH).any { k -> slots.resident(page(k)) == null }) continue
            now += r
            lookDrawn[key] = r to res.slot
            spent += cost
        }
        if (now.isNotEmpty()) renderLooks(now)
        MediaPhases.timer?.invoke("look.parts (count)", lookDrawn.size.toDouble())
        if (lookTodo.isNotEmpty() || lookDrawn.isEmpty()) return
        // Only parts still in the slot they were drawn in (a tile that left and another that came would copy the wrong pixels).
        val out = lookDrawn.filter { (key, v) -> slots.resident(page(key))?.slot == v.second }
        lookDrawn.clear()
        if (out.isNotEmpty()) copyLookOut(id, out.mapValues { it.value.first })
        if (lookAgain) { lookAgain = false; startWaterLook() }
    }

    private fun dropWaterLook() { lookTodo.clear(); lookDrawn.clear(); lookAgain = false }

    /** A stroke ended with brush input the water had not taken in yet: its stores are written again once it has. */
    var storesOwed = false; private set

    /**
     * Before a new stroke: the last one's stores while dabs of it still wait for the water (a quick next stroke). The water
     * catches up now ([MediaLayerEngine.settleInput]) and the stores are written in place, into the last stroke's step.
     */
    fun payOwedStores(paper: MediaPaperGl, pxPerMm: Double, wc0: cc.joycreator.joybrush.core.media.WetConstants) {
        if (!storesOwed) return
        storesOwed = false
        val id = layerId ?: return
        if (staleDrawing() || id !in paint.layerIds()) return
        media.settleInput(paper, pxPerMm, wc0)
        pendingStores += media.takeDirtyStores()
        media.takeDirty()?.let { r -> pendingRect = union(pendingRect, r); for (key in keysIn(r)) slots.resident(page(key))?.let { slots.markDirty(it.page) } }
        writeStores(id)
    }

    /** One part per tile: the part of each tile inside the [changed] rects grown by the look's halo (whole pixels). */
    private fun lookParts(changed: List<FloatArray>, h: Float = lookHalo.toFloat()): LinkedHashMap<Long, FloatArray> {
        val parts = LinkedHashMap<Long, FloatArray>()
        for (c in changed) {
            val g = floatArrayOf(floor(c[0] - h), floor(c[1] - h), ceil(c[2] + h), ceil(c[3] + h))
            for (key in keysIn(g)) {
                if (slots.resident(page(key)) == null) continue
                val tx0 = (Tiles.tx(key) * tile).toFloat(); val ty0 = (Tiles.ty(key) * tile).toFloat()
                val x0 = maxOf(g[0], tx0); val y0 = maxOf(g[1], ty0); val x1 = minOf(g[2], tx0 + tile); val y1 = minOf(g[3], ty0 + tile)
                if (x1 <= x0 || y1 <= y0) continue
                val p = parts[key]
                if (p == null) parts[key] = floatArrayOf(x0, y0, x1, y1)
                else { p[0] = minOf(p[0], x0); p[1] = minOf(p[1], y0); p[2] = maxOf(p[2], x1); p[3] = maxOf(p[3], y1) }
            }
        }
        return parts
    }

    /** The look texture's [parts] (layer px, one per resident tile) into the layer's look tiles. */
    private fun copyLookOut(id: String, parts: Map<Long, FloatArray>) {
        val targets = paint.writableMediaTiles(id, parts.keys, listOf(MEDIA_LOOK), plane).getValue(MEDIA_LOOK)
        for ((key, r) in parts) {
            val (ax, ay) = media.slotTexel(slots.resident(page(key))!!.slot)
            val tx0 = Tiles.tx(key) * tile; val ty0 = Tiles.ty(key) * tile
            val x0 = r[0].toInt(); val y0 = r[1].toInt(); val x1 = r[2].toInt(); val y1 = r[3].toInt()
            media.blit(media.lookTexture(), ax + x0 - tx0, ay + y0 - ty0, ax + x1 - tx0, ay + y1 - ty0, targets.getValue(key), x0 - tx0, y0 - ty0, x1 - tx0, y1 - ty0)
        }
    }

    /**
     * The look of everything [changed] (each grown by its halo), drawn ONCE per tile: the part of each tile inside the grown
     * rects that touch it. Drawn rect by rect, the water's tiles and their halos overlapped and the same pixels were drawn up
     * to four times a frame (10-21: the look was 60 % of a crowded wet frame). Everything it reads is held (the frame is
     * admitted, MediaAtlasWater.mustStay).
     */
    private fun writeLookOnce(id: String, changed: List<FloatArray>, unhaloed: List<FloatArray> = emptyList()) {
        val parts = lookParts(changed)
        for ((k, r) in lookParts(unhaloed, 0f)) {
            val p = parts[k]
            if (p == null) parts[k] = r else { p[0] = minOf(p[0], r[0]); p[1] = minOf(p[1], r[1]); p[2] = maxOf(p[2], r[2]); p[3] = maxOf(p[3], r[3]) }
        }
        if (parts.isEmpty()) return
        MediaPhases.timer?.invoke("look.frameParts (count)", parts.size.toDouble())
        MediaPhases.timer?.invoke("look.framePx k (count)", parts.values.sumOf { (it[2] - it[0]).toDouble() * (it[3] - it[1]) } / 1000)
        // The parts never overlap (each inside its own tile): all drawn, then each copied out once.
        renderLooks(parts.values.toList())
        copyLookOut(id, parts)
    }

    /** [hold] false: everything the look reads is known to be here already (it must not be held: the frame is admitted). */
    private fun writeLook(id: String, dirty: FloatArray, hold: Boolean = true) {
        val h = lookHalo.toFloat()
        // Recompute the dirty area and what it shades around it, with everything the look reads resident (no window edge).
        val out = floatArrayOf(dirty[0] - h, dirty[1] - h, dirty[2] + h, dirty[3] + h)
        val r = floatArrayOf(floor(out[0]), floor(out[1]), ceil(out[2]), ceil(out[3]))
        if (hold && lookInParts) { writeLookInParts(id, r); return }
        if (!hold) {
            val missingKeys = tilesOf(r, MediaAtlasPlacement.LOOK_REACH).filter { slots.resident(page(it)) == null }
            if (missingKeys.isNotEmpty()) android.util.Log.w(TAG, "look of a water tile with ${missingKeys.size} of its reads not here (should not happen): " +
                "rect ${r.joinToString(",") { it.toInt().toString() }}, missing ${missingKeys.joinToString { "(${Tiles.tx(it)},${Tiles.ty(it)})" }}, " +
                "water state there ${missingKeys.joinToString { water.state(it)?.name ?: "none" }}, held ${missingKeys.count { it in waterHeld }}")
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

    /**
     * The look of [r] one tile at a time, each with the tiles its reads reach held only for its own draw: a frame too wide
     * for its paint and its look's ring at once (see [admit]). The frame's passes are done, so its tiles may leave meanwhile;
     * a tile that leaves is written back first (it is marked dirty), and one that is needed again is loaded again.
     */
    private fun writeLookInParts(id: String, r: FloatArray) {
        unpinAll()
        for (key in keysIn(r)) {
            val tx0 = (Tiles.tx(key) * tile).toFloat(); val ty0 = (Tiles.ty(key) * tile).toFloat()
            val part = floatArrayOf(maxOf(r[0], tx0), maxOf(r[1], ty0), minOf(r[2], tx0 + tile), minOf(r[3], ty0 + tile))
            if (part[2] <= part[0] || part[3] <= part[1]) continue
            val held = holdBriefly(tilesOf(part, MediaAtlasPlacement.LOOK_REACH), emptyList())
            if (held.isEmpty()) { android.util.Log.w(TAG, "a part of the look could not be held: left as it was"); continue }
            val slot = slots.resident(page(key))?.slot
            if (slot != null) {
                renderLook(part)
                val target = paint.writableMediaTiles(id, listOf(key), listOf(MEDIA_LOOK), plane).getValue(MEDIA_LOOK).getValue(key)
                val (ax, ay) = media.slotTexel(slot)
                val x0 = part[0].toInt(); val y0 = part[1].toInt(); val x1 = part[2].toInt(); val y1 = part[3].toInt()
                val ix0 = tx0.toInt(); val iy0 = ty0.toInt()
                media.blit(media.lookTexture(), ax + x0 - ix0, ay + y0 - iy0, ax + x1 - ix0, ay + y1 - iy0, target, x0 - ix0, y0 - iy0, x1 - ix0, y1 - iy0)
            }
            for (p in held) if (slots.resident(p) != null) slots.unpin(p)
            pinned.removeAll(held.toSet())
        }
    }

    private fun writeStores(id: String) {
        val rect = pendingRect
        pendingRect = null
        val stores = pendingStores.toMutableList()
        pendingStores.clear()
        if (rect == null || stores.isEmpty()) return
        val keys = keysIn(rect).filter { slots.resident(page(it)) != null && it !in borrowedBlockTiles }
        if (keys.isEmpty()) return
        val water = stores.filter { it in MediaStores.WATER }
        val dried = water.isNotEmpty() && !media.wetActive
        if (dried && paint.mediaStrokeInProgress) dryPending = true
        val drop = dried && !dryPending
        if (drop) stores.removeAll(MediaStores.WATER)
        val firstTouch = if (stores.isEmpty()) emptyList() else keys.filter { it in groundFromLook }
        val grounds = if (firstTouch.isEmpty()) emptyMap() else paint.writableMediaTiles(id, firstTouch, listOf(MediaStores.GROUND), plane).getValue(MediaStores.GROUND)
        // A tile whose water is measured gone gets no water store (it would hold nothing): a drawing's media room is mostly
        // water while it is wet (2 of a watercolour tile's 4.5 half-MB stores), and it was only let go once ALL of it dried.
        val (waterStores, restStores) = stores.partition { it in MediaStores.WATER }
        val wetKeys = keys.filter { !MediaAtlasDropDry.on || !waterDry(it) || paint.mediaTile(id, MediaStores.WATER[0], it, plane) != null }
        val targets = HashMap<String, Map<Long, Int>>()
        if (restStores.isNotEmpty()) targets.putAll(paint.writableMediaTiles(id, keys, restStores, plane))
        if (waterStores.isNotEmpty() && wetKeys.isNotEmpty()) targets.putAll(paint.writableMediaTiles(id, wetKeys, waterStores, plane))
        for (key in keys) {
            val res = slots.resident(page(key))!!
            val (ax, ay) = media.slotTexel(res.slot)
            for (store in stores) {
                val dst = targets[store]?.get(key) ?: continue
                media.writeStoreTile(store, dst, ax, ay)
            }
            grounds[key]?.let { media.blit(media.groundTexture(), ax, ay, ax + tile, ay + tile, it, 0, 0, tile, tile) }
            slots.clean(res.page)
        }
        groundFromLook.removeAll(firstTouch.toSet())
        if (drop) dropWater(id)
        android.util.Log.i(TAG, "media room: ${paint.mediaResidentBytes() shr 20} MB held, ${driedTiles} tiles' dry water let go so far")
    }

    private fun reserveStores() {
        val id = layerId ?: return
        val rect = pendingRect ?: return
        val keys = keysIn(rect).filter { slots.resident(page(it)) != null && it !in borrowedBlockTiles }
        val stores = pendingStores.filter { it !in MediaStores.WATER || media.wetActive }
        if (keys.isEmpty() || stores.isEmpty()) return
        val (waterStores, restStores) = stores.partition { it in MediaStores.WATER }
        if (restStores.isNotEmpty()) paint.writableMediaTiles(id, keys, restStores, plane)
        val wetKeys = keys.filter { !MediaAtlasDropDry.on || !waterDry(it) || paint.mediaTile(id, MediaStores.WATER[0], it, plane) != null }
        if (waterStores.isNotEmpty() && wetKeys.isNotEmpty()) paint.writableMediaTiles(id, wetKeys, waterStores, plane)
        val firstTouch = keys.filter { it in groundFromLook }
        if (firstTouch.isNotEmpty()) paint.writableMediaTiles(id, firstTouch, listOf(MediaStores.GROUND), plane)
    }

    /** Nothing here has anywhere to go (the layer went, or the drawing did): drop it all, unwritten (no slot written back). */
    private fun forget() {
        dropWaterLook()
        storesOwed = false
        layerId = null; dryPending = false; pendingStores.clear(); pendingRect = null; pinned.clear(); admitted = false; slots.clear(); borrowedBlockTiles.clear(); media.setResidency(emptyMap()); media.waterPaused = emptySet()
        unpark()
        resetWater()
        media.takeDirty(); media.takeDirtyStores(); media.stopWater()
    }

    /** True when this tile's water is measured gone: no depth, no dampness (MediaAtlasWater: DRY). */
    private fun waterDry(key: Long): Boolean = water.state(key) == MediaAtlasWater.State.DRY

    /**
     * Between strokes, while water still runs elsewhere: the water stores of resident tiles measured dry are let go (their
     * slot's water is nothing already), so a long session does not fill the drawing's media room with water that has gone.
     * [dropWater] did it only once the last of the water had dried, and a painter who keeps painting never got there:
     * "this drawing has used it" after ~15 strokes on the owner's Note 9 (2026-10-21).
     */
    private fun dropDryWater(id: String) {
        val dry = heldKeys().filter { waterDry(it) && paint.mediaTile(id, MediaStores.WATER[0], it, plane) != null }
        if (dry.isEmpty()) return
        for (store in MediaStores.WATER) paint.dropMediaTiles(id, store, dry.filter { paint.mediaTile(id, store, it, plane) != null }, plane)
        driedTiles += dry.size
    }
    /** Water stores let go by [dropDryWater] (bench). */
    var driedTiles = 0; private set

    private fun dropWater(id: String) {
        val keys = heldKeys()
        for (store in MediaStores.WATER) paint.dropMediaTiles(id, store, keys.filter { paint.mediaTile(id, store, it, plane) != null }, plane)
    }

    override fun restored(layer: String, plane: String?, keys: Set<Long>): Boolean {
        if (staleDrawing()) return false
        if (layer != layerId || plane != this.plane) return false
        val mine = heldKeys().toSet()
        if (keys.none { it in mine }) return false
        dropWaterLook()   // drawn from what the undo just replaced
        storesOwed = false   // the stroke they belonged to is undone (its waiting dabs go with it: MediaLayerEngine.stopWater)
        media.stopWater()
        unpark()   // the water stopped: what the stores hold now is the state
        resumeOnLoad = false
        try { for (r in slots.residents()) loadSlot(r.slot, r.page.tileKey) } finally { resumeOnLoad = true }
        media.takeDirty(); media.takeDirtyStores()
        // Everything here is what the stores hold now: nothing is waiting to be written (else the next flush wrote the restored
        // tiles back as a new, empty step, and redo was lost).
        pendingStores.clear(); pendingRect = null
        return true
    }

    override fun lookWritten(layer: String, plane: String?, keys: Set<Long>) {
        if (staleDrawing()) return
        if (layer != layerId || plane != this.plane || media.asleep) return
        unpark(keys)
        for (key in keys) {
            val res = slots.resident(page(key)) ?: continue
            loadSlot(res.slot, key)
            media.stateRestored(tileRect(key))
        }
    }

    /** Tile by tile (each with what its look reads), so any area fits; held only while it is redone. */
    override fun refreshLook(rect: FloatArray) {
        if (staleDrawing()) return
        val id = layerId ?: return
        for (key in keysIn(rect)) {
            val part = MediaAtlasWater.intersect(intArrayOf(floor(rect[0]).toInt(), floor(rect[1]).toInt(), ceil(rect[2]).toInt(), ceil(rect[3]).toInt()),
                MediaAtlasWater.rectOf(key)) ?: continue
            writeLook(id, floatArrayOf(part[0].toFloat(), part[1].toFloat(), part[2].toFloat(), part[3].toFloat()))
            unpinAll()
        }
    }

    override fun wakeWater() {
        if (staleDrawing()) return
        val id = layerId ?: return
        if (media.wetActive) return
        val wet = heldKeys().filter { paint.mediaTile(id, "w0", it, plane) != null }
        if (wet.isEmpty()) return
        media.resumeWater(floatArrayOf(wet.minOf { Tiles.tx(it) } * tile.toFloat(), wet.minOf { Tiles.ty(it) } * tile.toFloat(),
            (wet.maxOf { Tiles.tx(it) } + 1) * tile.toFloat(), (wet.maxOf { Tiles.ty(it) } + 1) * tile.toFloat()))
    }

    private companion object {
        const val TAG = "JoyBrushMediaAtlas"
        /** The look's cost per px in the water's units (MediaLayerEngine JOB_WEIGHT: a flat flux px): ~30 ns on the Note 9. */
        const val LOOK_WEIGHT = 2.0
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

/**
 * The water park's size in tiles (ATLAS_ROOM_20261023, P1): 0 = off, as shipped; the bench's `jb_water_park_units`. P4 sets it
 * from the memory the atlas leaves.
 */
object MediaAtlasPark { @Volatile @JvmStatic var units = 0 }

/** Dry tiles let their water stores go while water runs elsewhere (MediaAtlas.dropDryWater); the bench's `jb_drop_dry` A/B. */
object MediaAtlasDropDry { @Volatile @JvmStatic var on = true }
