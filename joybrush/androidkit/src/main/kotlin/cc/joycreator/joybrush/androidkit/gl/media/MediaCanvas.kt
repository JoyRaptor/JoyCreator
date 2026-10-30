package cc.joycreator.joybrush.androidkit.gl.media

import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.gl.MediaRoomException
import cc.joycreator.joybrush.androidkit.gl.ShaderLibrary
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.MEDIUM_DRY
import cc.joycreator.joybrush.core.brush.MEDIUM_PASTE
import cc.joycreator.joybrush.core.brush.MEDIUM_WET
import cc.joycreator.joybrush.core.brush.MediaSpec
import cc.joycreator.joybrush.core.brush.mediaScale
import cc.joycreator.joybrush.core.brush.scaled
import cc.joycreator.joybrush.core.layers.LayerBudget
import cc.joycreator.joybrush.core.media.DOC_PX_PER_MM
import cc.joycreator.joybrush.core.media.DabBatch
import cc.joycreator.joybrush.core.media.MediaAtlasPlacement
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.media.PasteStep
import cc.joycreator.joybrush.core.media.DryStroke
import cc.joycreator.joybrush.core.media.MediaSample
import cc.joycreator.joybrush.core.media.MediaSpline
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.media.MediaWindowMath
import cc.joycreator.joybrush.core.media.PasteBrush
import cc.joycreator.joybrush.core.media.PasteSpacing
import cc.joycreator.joybrush.core.media.PasteStroke
import cc.joycreator.joybrush.core.media.StickMaterial
import cc.joycreator.joybrush.core.media.THINNER
import cc.joycreator.joybrush.core.media.WaterAB
import cc.joycreator.joybrush.core.media.WaterQuiet
import cc.joycreator.joybrush.core.media.WaterSchedule
import cc.joycreator.joybrush.core.media.WETNESS
import cc.joycreator.joybrush.core.media.WetConstants
import cc.joycreator.joybrush.core.media.WetStroke
import cc.joycreator.joybrush.core.media.opaquePaint
import cc.joycreator.joybrush.core.media.paintFromColor
import cc.joycreator.joybrush.core.media.thinnerStroke
import cc.joycreator.joybrush.core.paint.Dab
import kotlin.math.max

/**
 * Media strokes on the GL thread (step 4c): the lab's live loop (main.js startStroke and the frame loop), driving one
 * [MediaLayerEngine] through a [MediaWindow] on the app's stores. The canvas feeds pen samples ([add]) as they come,
 * and calls [frame] once per drawn frame: dabs and steps are run per FRAME, never per pen report, as in the lab, so the
 * water runs at the same speed however fast the pen reports.
 *
 * Everything that can refuse does so in words before anything is made (the Lead's rules): a GPU without float colour
 * buffers, a phone without the free memory for the window, a drawing past the media ceiling.
 *
 * [memory] answers (available bytes, low-memory threshold) from the phone (ActivityManager.MemoryInfo).
 */
class MediaCanvas(
    private val paint: GlPaintEngine,
    private val memory: () -> Pair<Long, Long>,
    private val shaders: ShaderLibrary = ShaderLibrary(),
) {
    private var engine: MediaLayerEngine? = null
    init {
        paint.registerPaperTextureBorrower(this) { engine?.borrowedPaperTextureHandles().orEmpty() }
    }
    private var window: MediaPlace? = null
    /** Debug probe only (MediaUndoProbe): the atlas's slot grid, columns x rows, instead of the phone's memory. */
    internal var slotGrid: Pair<Int, Int>? = null
    /** The atlas in use, if the engine places tiles freely (probe counters). */
    internal val atlas: MediaAtlas? get() = window as? MediaAtlas
    /** Why this GPU cannot do media, once found out: said again rather than retried every stroke. Read by the UI thread. */
    @Volatile var unsupported: String? = null
        private set

    /** Debug A/B only (bench `jb_media_legacy`): the pre-2026-10-10 window placement and every-frame store copies. */
    @Volatile var legacyWindow = false

    /**
     * Debug comparison only (bench `jb_wet_substeps`): the water's time step, coarser for fewer, longer substeps per frame
     * (the same simulated time, less accuracy). Null = the shipped constants.
     */
    @Volatile var wetQuality: cc.joycreator.joybrush.core.media.WetConstants? = null

    /**
     * The owner's watercolour A/B ([WaterAB]: brush first, sleep when still, flat look while wet, no relief). The default
     * is today's water exactly. Set from any thread; read on the GL thread each frame.
     */
    @Volatile var waterAB = WaterAB()
    /** Water jobs in the atlas (WATER_JOBS_20261021): the brush at the screen's pace. Off = every frame runs its water whole. */
    @Volatile var waterJobs = true
    private val schedule = WaterSchedule()
    private val quiet = WaterQuiet(WetConstants().minMm.toFloat())
    /** Option S: the water stood still long enough; it is not simulated again until a stroke, a tilt or an undo. */
    private var waterAsleep = false
    /** Where the look was drawn flat while wet and not yet drawn over in full (layer px). */
    private var flatArea: FloatArray? = null
    private var activitySeen = 0
    private var framesSinceQuery = 0

    /** The water's constants this frame: the shipped ones (or the bench's), with the A/B applied. */
    private fun water(): WetConstants = waterAB.apply(wetQuality ?: WetConstants())

    /** The look drawn now: without relief while water runs, when the A/B asks for it. */
    private fun lookNow(): MediaLook =
        if (waterAB.flatLookWhileWet && engine?.wetActive == true && !waterAsleep)
            MediaLook(look.paperColor, look.lamp, impasto = 0f, sheen = look.sheen, capMm = look.capMm)
        else look

    private fun haloOf(l: MediaLook) = if (l.impasto > 0f) MediaWindow.LOOK_HALO_MAX else MediaWindow.LOOK_HALO_FLAT

    /** The water stopped or slept: the full look over everything drawn flat while it ran. */
    private fun refreshFlat(w: MediaPlace) {
        val r = flatArea ?: return
        flatArea = null
        w.lookHalo = haloOf(look)
        mediaTrace("JB.media.look.refresh") { w.refreshLook(r) }
    }

    /** Option S's watch: how long the water has stood still. The atlas measures the water for its own rules; a window here. */
    private fun quietMs(e: MediaLayerEngine, w: MediaPlace, nowMs: Long): Long {
        if (w !is MediaAtlas) {
            e.takeWaterActivity()
            if (++framesSinceQuery >= QUIET_QUERY_FRAMES && e.requestWaterActivity()) framesSinceQuery = 0
        }
        if (e.activitySeq != activitySeen) { activitySeen = e.activitySeq; e.lastActivity?.let { quiet.observe(it, nowMs) } }
        return quiet.quietFor(nowMs)
    }

    /** The debug replay's counters, handed to each window made (null in normal use). */
    var stats: cc.joycreator.joybrush.androidkit.bench.FrameStats? = null

    /** The paper's downhill slope in document space (the phone-tilt switch, M5.6); [0, 0] = flat. */
    var slope = floatArrayOf(0f, 0f)
    /** The slope the last [frame] saw: a change wakes water that is lying still (owner: tilt worked only at the next stroke). */
    private var lastSlope = floatArrayOf(0f, 0f)
    /** Undo or redo stopped the water; only the next stroke ([begin]) may start it again, never a tilt (the Lead's rule). */
    private var waterStoppedByUndo = false

    // The app's lamp, in document space (y DOWN, unlike the lab's y-up view): light from the upper left.
    private var look = MediaLook(floatArrayOf(1f, 1f, 1f), lamp = MediaLook.normalise(floatArrayOf(-0.55f, -0.55f, 0.62f)))

    private class Stroke(
        val layerId: String,
        val seed: Int,
        val spline: MediaSpline,
        val dry: DryStroke? = null, val mat: StickMaterial? = null,
        val wet: WetStroke? = null,
        val paste: PasteStroke? = null, val brush: PasteBrush? = null, val thin: WetStroke? = null,
        val erase: Boolean = false,
    ) {
        var penX = 0.0
        var penY = 0.0
        var reachPx = 0.0
        /** When this stroke's last frame was drawn (0: none yet), for [PasteSpacing]. */
        var lastFrameMs = 0L
        /** Eraser dabs waiting for the next frame: x, y, radius, strength (then 16 unused floats each). */
        val eraseDabs = ArrayList<Dab>()
    }

    private var stroke: Stroke? = null

    val inStroke: Boolean get() = stroke != null

    /**
     * What the window holds now, for a refusal made before anything is made (the UI thread reads it; a stale read only
     * makes the check a little stricter or looser for one stroke, and the GL-thread check in [begin] still runs).
     */
    @Volatile var heldBytes: Long = 0L
        private set
    private fun noteHeld() {
        val e = engine
        heldBytes = when {
            e == null || e.asleep -> 0L
            e.hasWater -> LayerBudget.MEDIA_WINDOW_WET_BYTES
            else -> LayerBudget.MEDIA_WINDOW_DRY_BYTES
        }
    }
    /** Water is running somewhere in the window: keep drawing frames. */
    val running: Boolean get() = engine?.wetActive == true

    /**
     * Begins a media stroke of [preset] on [layerId] at document ([x], [y]). [argb] is the paint colour; [bellyArgb] the
     * second colour of a hair paste brush (or null). Returns the sentence to show when the stroke cannot be painted.
     */
    fun begin(layerId: String, preset: BrushPreset, argb: Int, bellyArgb: Int?, x: Double, y: Double, nowMs: Long): String? {
        val spec = preset.media ?: return "This brush has no pencil, watercolour or oil settings."
        val thinned = spec.medium == MEDIUM_PASTE && spec.thinner > 0 && spec.paste?.let { it.shape < 2 && !it.clean } == true
        val needsWater = spec.medium == MEDIUM_WET || thinned
        val e = ensure(needsWater) ?: return unsupported ?: LayerBudget.windowRefusal(needsWater)
        val w = window!!
        return try {
            // The last stroke's dabs the water had not taken in yet go into its own stores first (water jobs; its layer).
            (w as? MediaAtlas)?.payOwedStores(paint.mediaPaper(), DOC_PX_PER_MM, water())
            // JB-2.40 slice 3: the stroke paints the plane under its first touch (an animation frame, or the shared plane).
            w.place(layerId, paint.mediaPlaneAt(layerId, x, y), x, y)
            w.wakeWater()
            waterStoppedByUndo = false
            waterAsleep = false
            quiet.reset(nowMs)
            e.slope = slope
            look = MediaLook(rgb(paint.paperArgb).map { it.toFloat() }.toFloatArray(), lamp = look.lamp)
            paint.beginMediaStroke()
            e.beginStroke()
            stroke = build(layerId, spec.scaled(preset.mediaScale()), preset.mediaScale(), argb, bellyArgb, thinned, (nowMs and 0x7FFFFFFF).toInt())
                .also { it.penX = x; it.penY = y; it.reachPx = reachPx(spec.scaled(preset.mediaScale())) }
            w.touched(nowMs)
            noteHeld()
            null
        } catch (r: MediaRoomException) {
            if (paint.mediaStrokeInProgress) paint.endMediaStroke()
            stroke = null
            r.message
        }
    }

    /** An eraser stroke on a media layer: the canvas places the dabs as for any eraser, and they take the state away. */
    fun beginErase(layerId: String, x: Double, y: Double, nowMs: Long): String? {
        val e = ensure(false) ?: return unsupported ?: LayerBudget.windowRefusal(false)
        val w = window!!
        (w as? MediaAtlas)?.payOwedStores(paint.mediaPaper(), DOC_PX_PER_MM, water())
        w.place(layerId, paint.mediaPlaneAt(layerId, x, y), x, y)
        paint.beginMediaStroke()
        e.beginStroke()
        stroke = Stroke(layerId, 0, MediaSpline { }, erase = true).also { it.penX = x; it.penY = y }
        w.touched(nowMs)
        noteHeld()
        return null
    }

    /** Pen samples for the stroke in progress (document px; [MediaInput] made them). Painted at the next [frame]. */
    fun add(samples: List<MediaSample>) {
        val st = stroke ?: return
        for (s in samples) { st.penX = s.x; st.penY = s.y; st.spline.add(s) }
    }

    /** The eraser's dabs, placed by the canvas's own placer. */
    fun addErase(dabs: List<Dab>) {
        val st = stroke ?: return
        if (!st.erase) return
        st.eraseDabs.addAll(dabs)
        dabs.lastOrNull()?.let { st.penX = it.x.toDouble(); st.penY = it.y.toDouble() }
        st.reachPx = max(st.reachPx, dabs.maxOfOrNull { it.radius.toDouble() } ?: 0.0)
    }

    /**
     * One drawn frame: the stroke's dabs and steps (or the water running on after it), written back to the stores and
     * the look. Returns true while another frame is wanted (a stroke, or running water). Releases the window when idle.
     * The caller reports the history afterwards: dried water can change the undo stack.
     */
    fun frame(nowMs: Long): Boolean {
        val e = engine ?: return false
        val w = window ?: return false
        // Bench only: time every media section of this frame (glFinish brackets) and note what the textures cost.
        val rec = stats?.takeIf { it.recording }
        // The canvas may already time the whole GL frame (JbCanvasView.onDrawFrame): keep its timer, put it back after.
        val outer = MediaPhases.timer
        MediaPhases.timer = rec?.let { s -> { name: String, ms: Double -> s.phase(name, ms) } } ?: outer
        try { return frameInner(e, w, nowMs) } finally {
            MediaPhases.timer = outer
            if (rec != null && (rec.memory == null || (e.hasWaterTextures && !memoryHadWater))) {
                rec.memory = e.memoryReport(); memoryHadWater = e.hasWaterTextures
            }
            paint.collectPaperTextures()
        }
    }

    private var memoryHadWater = false

    private fun frameInner(e: MediaLayerEngine, w: MediaPlace, nowMs: Long): Boolean {
        // The paper's tilt is read every frame, so running water turns as the pad or the phone turns (it was read only
        // at a stroke's start: owner, 2026-10-12).
        val tilted = !slope.contentEquals(lastSlope)
        if (tilted) {
            lastSlope = slope.copyOf()
            android.util.Log.d("JoyBrushTilt", "slope=${slope[0]},${slope[1]} wetActive=${e.wetActive} wetRect=${e.wetRect?.contentToString()}")
        }
        e.slope = slope
        val st = stroke
        e.frameRealMs = if (lastFrameAt > 0L) (nowMs - lastFrameAt).coerceIn(0L, 1000L).toFloat() else 0f
        lastFrameAt = nowMs
        // Water jobs (WATER_JOBS_20261021): in the atlas the water does what the frame's budget allows and the brush shows at
        // once; the window runs every frame's water whole, as always.
        val budget = if (w is MediaAtlas && waterJobs) jobBudget.next(e.frameRealMs) else Double.POSITIVE_INFINITY
        // The water's steps take most of it, the water's look the rest (its parts drawn into the look texture until it is spent).
        e.waterBudget = budget * WATER_SHARE
        // With the pen up the water catches up (more than one job a frame); while a stroke paints, the brush comes first.
        e.catchUp = st == null
        if (budget.isFinite()) MediaPhases.timer?.invoke("water.budget k (count)", budget / 1000)
        if (w is MediaAtlas) w.lookBudget = budget * (1 - WATER_SHARE)
        // Water left lying in the window (a layer switch, a reopen, a window that was released and reloaded) starts to run
        // again when the paper tilts, not only at the next stroke. Water an undo stopped stays stopped.
        if (tilted && st == null && !e.wetActive && !e.asleep && !waterStoppedByUndo) w.wakeWater()
        if (tilted && st == null && waterAsleep && !waterStoppedByUndo) { waterAsleep = false; quiet.reset(nowMs) }
        if (st != null) {
            stats?.let { if (it.recording) it.mediaFrames++ }
            // A phone behind the pen paints the steps to come further apart (PasteSpacing), instead of falling further behind.
            st.paste?.let { p -> PasteSpacing.fixed?.let { p.spacing = it } ?: run { if (st.lastFrameMs > 0) p.spacing = PasteSpacing.next(p.spacing, nowMs - st.lastFrameMs) } }
            st.lastFrameMs = nowMs
            run(e, w, st)
            w.touched(nowMs)
            quiet.reset(nowMs)
            return true
        }
        if (e.wetActive && waterAsleep) return false   // Option S: the water lies still in the window until it is woken
        waterAsleep = false
        if (e.wetActive) {
            val ab = waterAB
            val wq0 = water()
            val wq = if (ab.brushFirst) wq0.copy(substeps = schedule.whileIdle(true, wq0.substeps)) else wq0
            if (w is MediaAtlas) w.admit(listOfNotNull(e.wetNeed(listOf(null), DOC_PX_PER_MM, wq.substeps)))
            e.wetFrame(null, paint.mediaPaper(), DOC_PX_PER_MM, wq)
            w.flush()
            w.touched(nowMs)
            val sleepS = ab.sleepAfterQuietSec
            if (sleepS != null && e.wetActive && quietMs(e, w, nowMs) >= sleepS * 1000) {
                android.util.Log.i("JoyBrushWater", "the water stood still ${sleepS}s: asleep until the next stroke, tilt or undo")
                waterAsleep = true
                schedule.reset()
                refreshFlat(w)
                return false
            }
            return true
        }
        schedule.reset()
        refreshFlat(w)
        if (w.releaseIfIdle(nowMs)) noteHeld()
        return false
    }

    /** Lift: the rest of the stroke, then its ONE undo step. Water carries on in [frame]. */
    fun end() {
        val st = stroke ?: return
        val e = engine!!
        val w = window!!
        st.spline.finish()
        st.wet?.finish()
        st.thin?.finish()
        // The stroke closes whatever its last frame meets: a frame that could not be stored (the drawing's media room full,
        // 2026-10-21 on the owner's Note 9) used to throw out of here with the stroke still open, and the app crashed.
        try { run(e, w, st, last = true) } finally {
            e.endStroke()
            stroke = null
            paint.endMediaStroke()
        }
    }

    /**
     * A stroke given up (a second finger, a cancelled gesture): everything it painted goes back ([GlPaintEngine.cancelMediaStroke],
     * which reloads the window or atlas through [restored]), no step is made, and the water that was there before carries on.
     */
    fun cancel() {
        stroke ?: return
        val e = engine!!
        e.endStroke()
        stroke = null
        paint.cancelMediaStroke()
        waterStoppedByUndo = false
        window?.wakeWater()
    }

    /** [GlPaintEngine.onMediaRestored]: undo or redo stops the water and reloads (never restarting it). */
    fun restored(layerId: String, plane: String?, keys: Set<Long>) {
        if (window?.restored(layerId, plane, keys) == true) { waterStoppedByUndo = true; waterAsleep = false }
    }

    /** [GlPaintEngine.onLookWritten]: a plain write changed these tiles' pixels; the window reloads them. */
    fun lookWritten(layerId: String, plane: String?, keys: Set<Long>) { window?.lookWritten(layerId, plane, keys) }

    /** The layer or the screen changed: write back and let the window go. */
    fun releaseWindow() { if (stroke == null) { settleOwed(); window?.let(::refreshFlat); window?.release(); noteHeld() } }

    /** The paint-engine switch (atlas or window): write back and let the engine go, so the next stroke makes the other kind. */
    fun releaseEngine() { if (stroke != null) return; settleOwed(); window?.let(::refreshFlat); window?.release(); release(); noteHeld() }

    /**
     * Water jobs: the last stroke's dabs the water had not taken in yet go into its own stores now (before an undo or a redo,
     * and before the window is let go), so the history and the saved drawing always hold the whole stroke.
     */
    fun settleOwed() { if (stroke == null) (window as? MediaAtlas)?.payOwedStores(paint.mediaPaper(), DOC_PX_PER_MM, water()) }

    /** GL context lost or the canvas closing. */
    fun release() {
        engine?.release()
        warm?.releasePrograms()
        engine = null; window = null; stroke = null; warm = null
        heldBytes = 0L
        paint.collectPaperTextures()
    }

    /** The context these GL names came from is gone: forget them without deleting (they died with it). */
    fun forget() { engine = null; window = null; stroke = null; heldBytes = 0L; warm = null }

    /** The atlas grid last made (see [ensure]). */
    private var lastGrid: Pair<Int, Int>? = null

    /**
     * A frame the atlas could not hold even split in time ([MediaRoomException], said in words by the caller): the stroke ends
     * where it got to, as its one undo step (the caller closes it), and the engine and its water stay as they are.
     */
    fun dropRefusedStroke() {
        stroke ?: return
        engine?.endStroke()
        stroke = null
        try { window?.flush() } catch (x: RuntimeException) { android.util.Log.w("JoyBrushMedia", "flush after a refused frame: ${x.message}") }
    }

    /** The water's work per frame in the atlas (water jobs), steered by the frame time. */
    private val jobBudget = cc.joycreator.joybrush.core.media.WaterJobBudget()

    /** When the last frame was drawn (ms; the water's wet-age clock, MediaLayerEngine.frameRealMs). */
    private var lastFrameAt = 0L

    /** An engine whose programs are being compiled ahead (no textures yet): the next media stroke's engine takes it over. */
    private var warm: MediaLayerEngine? = null

    /**
     * GL thread, an idle moment after a drawing opens (JbCanvasView.pumpMediaPrewarm, never during a stroke): compiles ONE
     * more media program ahead of the first pencil, watercolour or oil stroke, in the mode the next engine will use (atlas or
     * window). True while there is more to do. Never makes the textures; a phone that can't compile them simply stops trying
     * (the first stroke then says why, as before).
     */
    fun prewarmStep(): Boolean {
        if (engine != null || unsupported != null) return false
        val atlas = MediaAtlasGl.enabled
        warm?.let { if (it.atlasMode != atlas || it.coarseWater != MediaLayerEngine.coarseWaterDefault) { it.releasePrograms(); warm = null } }
        return try {
            val e = warm ?: MediaLayerEngine(shaders, atlasMode = atlas).also { warm = it }
            e.compileNext()
        } catch (x: RuntimeException) {
            warm?.releasePrograms(); warm = null
            android.util.Log.w("JoyBrushMedia", "prewarm stopped: ${x.message}")
            false
        }
    }

    // ---- inside ----

    private fun ensure(needsWater: Boolean): MediaLayerEngine? {
        unsupported?.let { return null }
        val e = engine
        val (avail, threshold) = memory()
        if (e == null || e.asleep) {
            if (!LayerBudget.windowFits(avail, threshold, needsWater)) {
                android.util.Log.w("JoyBrushMediaMemory",
                    "refused stage=gl-cold wet=$needsWater available=$avail threshold=$threshold asleep=${e?.asleep}")
                return null
            }
        } else if (needsWater && !e.hasWater) {
            if (!LayerBudget.windowFits(avail, threshold, true, LayerBudget.MEDIA_WINDOW_DRY_BYTES)) {
                android.util.Log.w("JoyBrushMediaMemory",
                    "refused stage=gl-water available=$avail threshold=$threshold held=${LayerBudget.MEDIA_WINDOW_DRY_BYTES}")
                return null
            }
        }
        if (e != null) return e
        // Media Atlas v2 B2 (off by default, MediaAtlasGl.enabled): the same textures as the window, tiles placed freely.
        val atlas = MediaAtlasGl.enabled
        val made = warm?.takeIf { it.atlasMode == atlas && it.coarseWater == MediaLayerEngine.coarseWaterDefault } ?: MediaLayerEngine(shaders, atlasMode = atlas)
        if (warm !== made) warm?.releasePrograms()
        warm = null
        try {
            if (atlas) {
                // Slot scaling (MediaAtlasPlacement.slotGrid: what is free beyond a reserve for the phone, 24 to 48, near square). An atlas made again
                // keeps at least the size it had while the phone still has room for it: sized afresh each time, it shrank as the
                // app's own memory grew (30 -> 25 slots on the Note 9, 2026-10-20) and refused more.
                val perSlot = if (MediaLayerEngine.coarseWaterDefault) MediaAtlasPlacement.BYTES_PER_SLOT_COARSE else MediaAtlasPlacement.BYTES_PER_SLOT
                val fresh = MediaAtlasPlacement.slotGrid(avail, perSlot)
                val kept = lastGrid?.takeIf { (c, r) -> c * r > fresh.first * fresh.second && avail > 2L * c * r * perSlot }
                val (cols, rows) = slotGrid ?: kept ?: fresh
                lastGrid = cols to rows
                android.util.Log.i("JoyBrushMediaMemory", "atlas $cols x $rows slots (available=$avail)")
                made.init(cols * Tiles.SIZE, rows * Tiles.SIZE)
            } else made.init(MediaWindowMath.PX, MediaWindowMath.PX)
        } catch (x: IllegalStateException) {
            // Say what actually failed: a program that would not compile is this build's fault, not the phone's (a shader
            // error read "no float colour buffers" on the Note 9, 2026-10-27).
            android.util.Log.e("JoyBrushMedia", "media engine could not start", x)
            unsupported = if (x.message?.contains("compile failed") == true)
                "Pencil, watercolour and oil could not start in this version of the app (a paint program failed to load)."
            else "This phone's graphics cannot paint pencil, watercolour and oil (it has no float colour buffers)."
            return null
        }
        engine = made
        val render = { r: FloatArray ->
            val l = lookNow()
            if (l !== look) flatArea = flatArea?.let { a -> floatArrayOf(minOf(a[0], r[0]), minOf(a[1], r[1]), maxOf(a[2], r[2]), maxOf(a[3], r[3])) } ?: r.copyOf()
            made.renderLook(r, paint.mediaPaper(), l, DOC_PX_PER_MM); Unit
        }
        val renderAll = { rs: List<FloatArray> ->
            val l = lookNow()
            if (l !== look) for (r in rs) flatArea = flatArea?.let { a -> floatArrayOf(minOf(a[0], r[0]), minOf(a[1], r[1]), maxOf(a[2], r[2]), maxOf(a[3], r[3])) } ?: r.copyOf()
            made.renderLook(rs, paint.mediaPaper(), l, DOC_PX_PER_MM); Unit
        }
        val dry = { rects: List<FloatArray> -> made.dryTilesNow(rects, paint.mediaPaper(), DOC_PX_PER_MM, water()) }
        window = (if (atlas) MediaAtlas(paint, made, render, dry, renderAll) else MediaWindow(paint, made, render)).also { it.stats = stats }
        return made
    }

    private fun build(layerId: String, spec: MediaSpec, scale: Double, argb: Int, bellyArgb: Int?, thinned: Boolean, seed: Int): Stroke {
        val color = rgb(argb)
        val paper = paint.mediaPaper()
        val e = engine!!
        return when (spec.medium) {
            MEDIUM_DRY -> {
                val stick = spec.stick!!
                val dry = DryStroke(stick, paper.stats.toothMm, DOC_PX_PER_MM)
                Stroke(layerId, seed, MediaSpline(sink = dry::add), dry = dry, mat = StickMaterial.of(stick))
            }
            MEDIUM_WET -> {
                val brush = spec.wet!!
                val level = WETNESS[spec.wetness.coerceIn(0, WETNESS.lastIndex)]
                // A clear brush (water, thirsty) carries at least its own load of water whatever the level says (lab).
                val wetness = if (brush.clear) level.copy(load = max(level.load, brush.load)) else level
                val wet = WetStroke(brush, paintFromColor(color), DOC_PX_PER_MM, seed, wetness)
                e.wetGran = brush.gran
                Stroke(layerId, seed, MediaSpline(sink = wet::add), wet = wet)
            }
            else -> {
                val brush = spec.paste!!
                val belly = bellyArgb?.takeIf { spec.isHair }?.let { opaquePaint(rgb(it)) }
                e.reloadBrush(opaquePaint(color), brush, seed, belly)
                val body = if (thinned) THINNER[spec.thinner.coerceIn(0, THINNER.lastIndex)].body else 1.0
                val ps = PasteStroke(brush, DOC_PX_PER_MM, 1.0, body = body)
                val thin = if (thinned) thinnerStroke(brush, color, DOC_PX_PER_MM, seed, spec.thinner) else null
                Stroke(layerId, seed, MediaSpline { s -> thin?.add(s); ps.add(s) }, paste = ps, brush = brush, thin = thin)
            }
        }.also { if (scale <= 0.0) error("a media brush needs a size") }
    }

    /**
     * One frame of the stroke: keep the window over what this frame paints, run what the builders made, write the look
     * back ([last]: the stores too). A pencil's window follows the frame's actual dab batch (its rotated footprints plus
     * the smear the apply pass reads), not a worst-case reach: 12 point-widths did not fit the window for most pencils,
     * so it moved and reloaded every frame and clipped dabs outside it (LEAD_PLAN F1, F2).
     */
    private fun run(e: MediaLayerEngine, w: MediaPlace, st: Stroke, last: Boolean = false) {
        val batch = st.dry?.take()
        w.legacyLook = legacyWindow
        e.legacyRect = legacyWindow   // bench A/B: the old full-square paste rect too
        val followRect: DoubleArray; val fx: Double; val fy: Double
        if (!legacyWindow && batch != null && batch.count > 0 && batch.dirty != null) {
            val pad = st.mat!!.smearMm * DOC_PX_PER_MM + 4
            val d = batch.dirty!!
            followRect = doubleArrayOf(d[0] - pad, d[1] - pad, d[2] + pad, d[3] + pad); fx = (d[0] + d[2]) / 2; fy = (d[1] + d[3]) / 2
        } else {
            val r = st.reachPx + 4
            followRect = doubleArrayOf(st.penX - r, st.penY - r, st.penX + r, st.penY + r); fx = st.penX; fy = st.penY
        }
        // The window patch: a frame whose paint would fall off the window it is about to follow to is drawn in parts.
        // Every other frame runs exactly as before.
        if (w is MediaWindow && !legacyWindow) {
            val wq0 = water()
            val wq = if (waterAB.brushFirst && (st.wet != null || st.thin != null || e.wetActive))
                wq0.copy(substeps = schedule.whileDrawing(true, wq0.substeps)) else wq0
            val input = FrameInput(batch, st.thin?.take(), st.paste?.take(), st.wet?.take(),
                if (st.erase && st.eraseDabs.isNotEmpty()) eraseBatch(st.eraseDabs) else null)
            // Clipped means dabs lost: the dabs' own boxes, not the reach around them (the water's spread and the smear
            // are clamped to the window as they always were; measuring them split most topScribble frames on the Note 9).
            val dabs = frameRect(st, input, padded = false)
            w.lookHalo = haloOf(lookNow())
            when {
                dabs == null || !w.wouldClip(followRect, fx, fy, dabs) -> { w.follow(followRect, fx, fy); runFrame(e, w, st, input, wq, last) }
                // Most frames the usual follow would clip fit one window placed on their dabs: one move, the frame whole.
                w.holdWhole(dabs) -> runFrame(e, w, st, input, wq, last)
                else -> {
                    val parts = ArrayList<Pair<FrameInput, DoubleArray?>>()
                    windowParts(st, input, parts)
                    if (parts.size <= MAX_WINDOW_PARTS) runWindowParts(e, w, st, parts, wq, last)
                    else {
                        // Never let a brush's shape freeze a frame (the palette knife made 604 parts, 45 s on the Note 9,
                        // when its steps were measured by their old full square): past the cap, the frame runs as before.
                        android.util.Log.w("JoyBrushMedia", "a frame would need ${parts.size} parts: drawn as before, clipped")
                        w.follow(followRect, fx, fy); runFrame(e, w, st, input, wq, last)
                    }
                }
            }
            if (input.erase != null) st.eraseDabs.clear()
            return
        }
        w.follow(followRect, fx, fy)
        w.lookHalo = haloOf(lookNow())
        // Every input of this frame, taken before any pass runs, so the atlas can admit the whole frame first (B3).
        // Option A (brush first): while the pen is down the water runs one step a frame; the rest is paid back after.
        val wq0 = water()
        val wq = if (waterAB.brushFirst && (st.wet != null || st.thin != null || e.wetActive))
            wq0.copy(substeps = schedule.whileDrawing(true, wq0.substeps)) else wq0
        val input = FrameInput(batch, st.thin?.take(), st.paste?.take(), st.wet?.take(),
            if (st.erase && st.eraseDabs.isNotEmpty()) eraseBatch(st.eraseDabs) else null)
        runFrame(e, w, st, input, wq, last)
        if (input.erase != null) st.eraseDabs.clear()
    }

    /**
     * The window patch (2026-10-19): one frame of a stroke in the window, every dab drawn. A frame whose dabs reach wider
     * than the window holds (MediaWindowMath.SPAN; a slow first frame after an install batches a whole stroke) is drawn in
     * parts, its dabs in order, the window moving to each, exactly as a faster phone would have drawn them; the window
     * clipped such dabs before, and lost the stroke's far end. The parts share the frame's water steps: one each, the
     * rest with the last (each part runs its own water for its own dabs, and N parts with every step made one frame N
     * times as long: 4.7 s on the Note 9).
     */
    private fun runWindowParts(e: MediaLayerEngine, w: MediaWindow, st: Stroke, parts: List<Pair<FrameInput, DoubleArray?>>,
                               wq: WetConstants, last: Boolean) {
        android.util.Log.i("JoyBrushMedia", "a frame wider than the window, drawn in ${parts.size} parts")
        for ((i, p) in parts.withIndex()) {
            val (part, r) = p
            if (r != null) w.followCentred(r)
            val final = i == parts.lastIndex
            val steps = if (final) maxOf(1, wq.substeps - (parts.size - 1)) else 1
            runFrame(e, w, st, part, if (steps == wq.substeps) wq else wq.copy(substeps = steps), last && final)
        }
    }

    /** [input] cut in halves, in dab order, until each part fits the window (MediaWindowMath.SPAN), with what it reaches. */
    private fun windowParts(st: Stroke, input: FrameInput, out: MutableList<Pair<FrameInput, DoubleArray?>>) {
        val r = frameRect(st, input)
        if (r != null && (r[2] - r[0] > MediaWindowMath.SPAN || r[3] - r[1] > MediaWindowMath.SPAN)) {
            val halves = input.halves()
            if (halves != null) { windowParts(st, halves.first, out); windowParts(st, halves.second, out); return }
            android.util.Log.w("JoyBrushMedia", "one dab wider than the window holds (${(r[2] - r[0]).toInt()} px)")
        }
        out += input to r
    }

    /**
     * Everything [input] paints and reads, in document px (what the window must hold for it); null: nothing to paint.
     * [padded] false: the dabs' (and paste steps') own extent only.
     */
    private fun frameRect(st: Stroke, input: FrameInput, padded: Boolean = true): DoubleArray? {
        var r: DoubleArray? = null
        fun add(d: DoubleArray?, pad0: Double) {
            if (d == null) return
            val pad = if (padded) pad0 else 0.0
            val a = r
            r = if (a == null) doubleArrayOf(d[0] - pad, d[1] - pad, d[2] + pad, d[3] + pad)
            else doubleArrayOf(minOf(a[0], d[0] - pad), minOf(a[1], d[1] - pad), maxOf(a[2], d[2] + pad), maxOf(a[3], d[3] + pad))
        }
        input.dry?.takeIf { it.count > 0 }?.let { add(it.dirty, st.mat!!.smearMm * DOC_PX_PER_MM + 4) }
        // The water's input: dabs + 48 (MediaLayerEngine.wetFrame), and the brush's own reach as the window always kept it.
        val wetPad = maxOf(48.0, st.reachPx) + 4
        input.thin?.takeIf { it.count > 0 }?.let { add(it.dirty, wetPad) }
        input.wet?.takeIf { it.count > 0 }?.let { add(it.dirty, wetPad) }
        input.erase?.takeIf { it.count > 0 }?.let { add(it.dirty, 4.0) }
        // A paste step's own footprint (the oriented box the paste pass draws), not its old full square: for the palette
        // knife the square was wider than any window, so every frame split down to single steps.
        input.paste?.forEach { step -> add(cc.joycreator.joybrush.core.media.PasteFootprint.docBounds(step, st.brush!!, 1.0, DOC_PX_PER_MM),
            MediaAtlasPlacement.PASTE_REACH + 4.0) }
        return r
    }

    /** One frame's inputs, in pen order within each medium. */
    private class FrameInput(val dry: DabBatch?, val thin: DabBatch?, val paste: List<PasteStep>?, val wet: DabBatch?, val erase: DabBatch?) {
        private fun n(b: DabBatch?) = b?.count ?: 0
        val units get() = maxOf(n(dry), n(thin), paste?.size ?: 0, n(wet), n(erase))

        /**
         * The first and second halves of every input, in pen order (Media Atlas v2 B3 step 6, the Lead's D3): what a faster
         * phone would have drawn as two frames. Null when it cannot be split (one dab, or dabs without their boxes).
         */
        fun halves(): Pair<FrameInput, FrameInput>? {
            if (units < 2) return null
            fun cut(b: DabBatch?): Pair<DabBatch?, DabBatch?>? {
                if (b == null) return null to null
                val m = (b.count + 1) / 2
                return (b.slice(0, m) ?: return null) to (b.slice(m, b.count) ?: return null)
            }
            val d = cut(dry) ?: return null; val t = cut(thin) ?: return null; val w = cut(wet) ?: return null; val r = cut(erase) ?: return null
            val pm = ((paste?.size ?: 0) + 1) / 2
            return FrameInput(d.first, t.first, paste?.subList(0, pm), w.first, r.first) to
                FrameInput(d.second, t.second, paste?.subList(pm, paste.size), w.second, r.second)
        }
    }

    /**
     * The passes of one frame. In the atlas the frame is admitted first; one that the atlas cannot hold even after the
     * water made room (D1/D2) is split in time, its dabs in order, into frames that fit, each drawn, looked at and let go
     * in turn, exactly as a faster phone would have batched the same dabs; only a single dab that cannot fit is refused.
     */
    private fun runFrame(e: MediaLayerEngine, w: MediaPlace, st: Stroke, input: FrameInput, wq: WetConstants, last: Boolean) {
        val paper = paint.mediaPaper()
        val batch = input.dry; val thin = input.thin; val steps = input.paste; val wetIn = input.wet; val eraseIn = input.erase
        if (w is MediaAtlas) {
            try { w.admit(frameNeeds(e, st, batch, thin, steps, wetIn, eraseIn, wq)) } catch (r: MediaRoomException) {
                val (a, b) = input.halves() ?: throw r
                android.util.Log.i("JoyBrushMediaAtlas", "a frame too big for the atlas, split in time (${input.units} dabs)")
                runFrame(e, w, st, a, wq, last = false)
                runFrame(e, w, st, b, wq, last)
                return
            }
        }
        // Bench only: split the frame's media cost into the simulation (dabs, steps, water) and the write-back (look
        // render, tile copies), each finished on the GPU so the split is real time, not command submission.
        val timing = stats?.takeIf { it.recording }
        if (timing != null) android.opengl.GLES30.glFinish()
        val t0 = System.nanoTime()
        if (batch != null && batch.count > 0) {
            val lastDab = (batch.count - 1) * DabBatch.FLOATS
            e.dryFrame(batch, batch.dabs[lastDab + 16].toDouble(), batch.dabs[lastDab + 17].toDouble(), paper, st.mat!!, DOC_PX_PER_MM)
        }
        if (st.thin != null) e.wetFrame(thin, paper, DOC_PX_PER_MM, wq)
        if (steps != null && steps.isNotEmpty()) {
            mediaTrace("JB.media.paste.frame") {
                for (step in steps) e.pasteStep(step, st.brush!!, paper, DOC_PX_PER_MM, st.seed)
                e.pasteSettle()   // an unpaired last step's box back into the canvas everything else reads
            }
            MediaPhases.timer?.invoke("paste.steps (count)", steps.size.toDouble())
        }
        if (st.wet != null) e.wetFrame(wetIn, paper, DOC_PX_PER_MM, wq)
        if (eraseIn != null) e.eraseFrame(eraseIn, existingStores(st.layerId, w))
        // Water that is already running in the window keeps running under a dry or paste stroke.
        if (st.wet == null && st.thin == null && e.wetActive) e.wetFrame(null, paper, DOC_PX_PER_MM, wq)
        if (timing != null) android.opengl.GLES30.glFinish()
        val t1 = System.nanoTime()
        w.flush(stores = last || legacyWindow)
        if (timing != null) {
            android.opengl.GLES30.glFinish()
            timing.simAcc += (t1 - t0) / 1e6
            timing.flushAcc += (System.nanoTime() - t1) / 1e6
        }
    }

    /**
     * Media Atlas v2 B3: what this frame's passes will ask the atlas for, computed by the passes' own code
     * ([MediaLayerEngine.dryNeed] and the rest), so [MediaAtlas.admit] can hold all of it before the first one draws.
     */
    private fun frameNeeds(e: MediaLayerEngine, st: Stroke, dry: DabBatch?, thin: DabBatch?, steps: List<PasteStep>?, wetIn: DabBatch?,
                           eraseIn: DabBatch?, wq: WetConstants): List<MediaLayerEngine.Need> {
        val out = ArrayList<MediaLayerEngine.Need>()
        if (dry != null && dry.count > 0) e.dryNeed(dry, st.mat!!, DOC_PX_PER_MM)?.let(out::add)
        steps?.forEach { step -> e.pasteNeed(step, st.brush!!, DOC_PX_PER_MM)?.let(out::add) }
        if (eraseIn != null) e.eraseNeed(eraseIn)?.let(out::add)
        // The water frames in the order run() makes them (the same batches, or none while water already runs).
        val water = ArrayList<DabBatch?>()
        if (st.thin != null) water += thin
        if (st.wet != null) water += wetIn
        if (st.wet == null && st.thin == null && e.wetActive) water += null
        if (water.isNotEmpty()) e.wetNeed(water, DOC_PX_PER_MM, wq.substeps)?.let(out::add)
        out += e.wetInputNeeds(water)
        return out
    }

    private fun eraseBatch(dabs: List<Dab>): DabBatch {
        val out = FloatArray(dabs.size * DabBatch.FLOATS)
        val boxes = DoubleArray(dabs.size * 4)
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        dabs.forEachIndexed { i, d ->
            val o = i * DabBatch.FLOATS
            out[o] = d.x; out[o + 1] = d.y; out[o + 2] = d.radius
            out[o + 3] = (d.cap * d.flow.coerceAtMost(1f)).coerceIn(0f, 1f)
            x0 = minOf(x0, (d.x - d.radius).toDouble()); y0 = minOf(y0, (d.y - d.radius).toDouble())
            x1 = maxOf(x1, (d.x + d.radius).toDouble()); y1 = maxOf(y1, (d.y + d.radius).toDouble())
            boxes[i * 4] = (d.x - d.radius - 2).toDouble(); boxes[i * 4 + 1] = (d.y - d.radius - 2).toDouble()
            boxes[i * 4 + 2] = (d.x + d.radius + 2).toDouble(); boxes[i * 4 + 3] = (d.y + d.radius + 2).toDouble()
        }
        return DabBatch(out, dabs.size, doubleArrayOf(x0 - 2, y0 - 2, x1 + 2, y1 + 2), boxes)
    }

    /** The stores this layer has inside the window: the eraser writes only those (stores stay lazy). */
    private fun existingStores(layerId: String, w: MediaPlace): Set<String> {
        val keys = w.heldKeys()
        return MediaStores.ALL.filter { st -> keys.any { paint.mediaTile(layerId, st, it, w.plane) != null } }.toSet()
    }

    /** How far from the pen the tool reaches, in document px: the window keeps that much room around it. */
    private fun reachPx(spec: MediaSpec): Double = spec.referenceMm * DOC_PX_PER_MM * when (spec.medium) {
        MEDIUM_DRY -> 12.0    // a pencil laid flat reaches its side (side2), many point-widths from the tip
        else -> 1.0
    }

    private fun rgb(argb: Int) = doubleArrayOf(((argb shr 16) and 0xFF) / 255.0, ((argb shr 8) and 0xFF) / 255.0, (argb and 0xFF) / 255.0)
}

/** Frames between the water measurements Option S reads in a window (the atlas measures every 8 frames for its own rules). */
private const val QUIET_QUERY_FRAMES = 8

/** The most parts a frame is drawn in by the window patch; a frame needing more runs as before (MediaCanvas.run). */
private const val MAX_WINDOW_PARTS = 8

/** The share of a frame's water budget its steps get; the water's look gets the rest (WATER_JOBS_20261021). */
private const val WATER_SHARE = 0.6
