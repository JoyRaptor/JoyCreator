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
    private var window: MediaPlace? = null
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
        MediaPhases.timer = rec?.let { s -> { name: String, ms: Double -> s.phase(name, ms) } }
        try { return frameInner(e, w, nowMs) } finally {
            MediaPhases.timer = null
            if (rec != null && (rec.memory == null || (e.hasWaterTextures && !memoryHadWater))) {
                rec.memory = e.memoryReport(); memoryHadWater = e.hasWaterTextures
            }
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
        // Water left lying in the window (a layer switch, a reopen, a window that was released and reloaded) starts to run
        // again when the paper tilts, not only at the next stroke. Water an undo stopped stays stopped.
        if (tilted && st == null && !e.wetActive && !e.asleep && !waterStoppedByUndo) w.wakeWater()
        if (tilted && st == null && waterAsleep && !waterStoppedByUndo) { waterAsleep = false; quiet.reset(nowMs) }
        if (st != null) {
            stats?.let { if (it.recording) it.mediaFrames++ }
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
        run(e, w, st, last = true)
        e.endStroke()
        stroke = null
        paint.endMediaStroke()
    }

    /** A stroke given up (a second finger, a cancelled gesture): what it already painted stays, as one step. */
    fun cancel() = end()

    /** [GlPaintEngine.onMediaRestored]: undo or redo stops the water and reloads (never restarting it). */
    fun restored(layerId: String, plane: String?, keys: Set<Long>) {
        if (window?.restored(layerId, plane, keys) == true) { waterStoppedByUndo = true; waterAsleep = false }
    }

    /** [GlPaintEngine.onLookWritten]: a plain write changed these tiles' pixels; the window reloads them. */
    fun lookWritten(layerId: String, plane: String?, keys: Set<Long>) { window?.lookWritten(layerId, plane, keys) }

    /** The layer or the screen changed: write back and let the window go. */
    fun releaseWindow() { if (stroke == null) { window?.let(::refreshFlat); window?.release(); noteHeld() } }

    /** GL context lost or the canvas closing. */
    fun release() {
        engine?.release()
        warm?.releasePrograms()
        engine = null; window = null; stroke = null; warm = null
        heldBytes = 0L
    }

    /** The context these GL names came from is gone: forget them without deleting (they died with it). */
    fun forget() { engine = null; window = null; stroke = null; heldBytes = 0L; warm = null }

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
        warm?.let { if (it.atlasMode != atlas) { it.releasePrograms(); warm = null } }
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
        val made = warm?.takeIf { it.atlasMode == atlas } ?: MediaLayerEngine(shaders, atlasMode = atlas)
        if (warm !== made) warm?.releasePrograms()
        warm = null
        try {
            if (atlas) {
                // Slot scaling (the Lead, Oct 18): as many slots as 20 % of what is free pays for, 16 to 64, near square.
                val (cols, rows) = MediaAtlasPlacement.slotGrid(avail)
                android.util.Log.i("JoyBrushMediaMemory", "atlas $cols x $rows slots (available=$avail)")
                made.init(cols * Tiles.SIZE, rows * Tiles.SIZE)
            } else made.init(MediaWindowMath.PX, MediaWindowMath.PX)
        } catch (x: IllegalStateException) {
            unsupported = "This phone's graphics cannot paint pencil, watercolour and oil (it has no float colour buffers)."
            return null
        }
        engine = made
        val render = { r: FloatArray ->
            val l = lookNow()
            if (l !== look) flatArea = flatArea?.let { a -> floatArrayOf(minOf(a[0], r[0]), minOf(a[1], r[1]), maxOf(a[2], r[2]), maxOf(a[3], r[3])) } ?: r.copyOf()
            made.renderLook(r, paint.mediaPaper(), l, DOC_PX_PER_MM); Unit
        }
        val dry = { rects: List<FloatArray> -> made.dryTilesNow(rects, paint.mediaPaper(), DOC_PX_PER_MM, water()) }
        window = (if (atlas) MediaAtlas(paint, made, render, dry) else MediaWindow(paint, made, render)).also { it.stats = stats }
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
        if (legacyWindow) {
            val r = st.reachPx + 4
            w.follow(doubleArrayOf(st.penX - r, st.penY - r, st.penX + r, st.penY + r), st.penX, st.penY)
        } else if (batch != null && batch.count > 0 && batch.dirty != null) {
            val pad = st.mat!!.smearMm * DOC_PX_PER_MM + 4
            val d = batch.dirty!!
            w.follow(doubleArrayOf(d[0] - pad, d[1] - pad, d[2] + pad, d[3] + pad), (d[0] + d[2]) / 2, (d[1] + d[3]) / 2)
        } else {
            val r = st.reachPx + 4
            w.follow(doubleArrayOf(st.penX - r, st.penY - r, st.penX + r, st.penY + r), st.penX, st.penY)
        }
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
