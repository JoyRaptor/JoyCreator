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
import cc.joycreator.joybrush.core.media.DryStroke
import cc.joycreator.joybrush.core.media.MediaSample
import cc.joycreator.joybrush.core.media.MediaSpline
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.media.MediaWindowMath
import cc.joycreator.joybrush.core.media.PasteBrush
import cc.joycreator.joybrush.core.media.PasteStroke
import cc.joycreator.joybrush.core.media.StickMaterial
import cc.joycreator.joybrush.core.media.THINNER
import cc.joycreator.joybrush.core.media.WETNESS
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
    private var window: MediaWindow? = null
    /** Why this GPU cannot do media, once found out: said again rather than retried every stroke. Read by the UI thread. */
    @Volatile var unsupported: String? = null
        private set

    /** The paper's downhill slope in document space (the phone-tilt switch, M5.6); [0, 0] = flat. */
    var slope = floatArrayOf(0f, 0f)

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
            w.place(layerId, x, y)
            w.wakeWater()
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
        w.place(layerId, x, y)
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
        val st = stroke
        if (st != null) {
            run(e, w, st)
            w.touched(nowMs)
            return true
        }
        if (e.wetActive) {
            e.wetFrame(null, paint.mediaPaper(), DOC_PX_PER_MM)
            w.flush()
            w.touched(nowMs)
            return true
        }
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
        run(e, w, st)
        e.endStroke()
        stroke = null
        paint.endMediaStroke()
    }

    /** A stroke given up (a second finger, a cancelled gesture): what it already painted stays, as one step. */
    fun cancel() = end()

    /** [GlPaintEngine.onMediaRestored]: undo or redo stops the water and reloads (never restarting it). */
    fun restored(layerId: String, keys: Set<Long>) { window?.restored(layerId, keys) }

    /** The layer or the screen changed: write back and let the window go. */
    fun releaseWindow() { if (stroke == null) { window?.release(); noteHeld() } }

    /** GL context lost or the canvas closing. */
    fun release() {
        engine?.release()
        engine = null; window = null; stroke = null
        heldBytes = 0L
    }

    /** The context these GL names came from is gone: forget them without deleting (they died with it). */
    fun forget() { engine = null; window = null; stroke = null; heldBytes = 0L }

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
        val made = MediaLayerEngine(shaders)
        try {
            made.init(MediaWindowMath.PX, MediaWindowMath.PX)
        } catch (x: IllegalStateException) {
            unsupported = "This phone's graphics cannot paint pencil, watercolour and oil (it has no float colour buffers)."
            return null
        }
        engine = made
        window = MediaWindow(paint, made) { r -> made.renderLook(r, paint.mediaPaper(), look, DOC_PX_PER_MM) }
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

    /** One frame of the stroke: keep the window over the pen, run what the builders made, write it back. */
    private fun run(e: MediaLayerEngine, w: MediaWindow, st: Stroke) {
        val r = st.reachPx + 4
        w.follow(doubleArrayOf(st.penX - r, st.penY - r, st.penX + r, st.penY + r), st.penX, st.penY)
        val paper = paint.mediaPaper()
        st.dry?.let { dry ->
            val b = dry.take()
            if (b.count > 0) {
                val last = (b.count - 1) * DabBatch.FLOATS
                e.dryFrame(b, b.dabs[last + 16].toDouble(), b.dabs[last + 17].toDouble(), paper, st.mat!!, DOC_PX_PER_MM)
            }
        }
        st.thin?.let { e.wetFrame(it.take(), paper, DOC_PX_PER_MM) }
        st.paste?.let { ps -> for (step in ps.take()) e.pasteStep(step, st.brush!!, paper, DOC_PX_PER_MM, st.seed) }
        st.wet?.let { e.wetFrame(it.take(), paper, DOC_PX_PER_MM) }
        if (st.erase && st.eraseDabs.isNotEmpty()) {
            e.eraseFrame(eraseBatch(st.eraseDabs), existingStores(st.layerId, w))
            st.eraseDabs.clear()
        }
        // Water that is already running in the window keeps running under a dry or paste stroke.
        if (st.wet == null && st.thin == null && e.wetActive) e.wetFrame(null, paper, DOC_PX_PER_MM)
        w.flush()
    }

    private fun eraseBatch(dabs: List<Dab>): DabBatch {
        val out = FloatArray(dabs.size * DabBatch.FLOATS)
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        dabs.forEachIndexed { i, d ->
            val o = i * DabBatch.FLOATS
            out[o] = d.x; out[o + 1] = d.y; out[o + 2] = d.radius
            out[o + 3] = (d.cap * d.flow.coerceAtMost(1f)).coerceIn(0f, 1f)
            x0 = minOf(x0, (d.x - d.radius).toDouble()); y0 = minOf(y0, (d.y - d.radius).toDouble())
            x1 = maxOf(x1, (d.x + d.radius).toDouble()); y1 = maxOf(y1, (d.y + d.radius).toDouble())
        }
        return DabBatch(out, dabs.size, doubleArrayOf(x0 - 2, y0 - 2, x1 + 2, y1 + 2))
    }

    /** The stores this layer has inside the window: the eraser writes only those (stores stay lazy). */
    private fun existingStores(layerId: String, w: MediaWindow): Set<String> {
        val keys = MediaWindowMath.allKeys(w.tx, w.ty)
        return MediaStores.ALL.filter { st -> keys.any { paint.mediaTile(layerId, st, it) != null } }.toSet()
    }

    /** How far from the pen the tool reaches, in document px: the window keeps that much room around it. */
    private fun reachPx(spec: MediaSpec): Double = spec.referenceMm * DOC_PX_PER_MM * when (spec.medium) {
        MEDIUM_DRY -> 12.0    // a pencil laid flat reaches its side (side2), many point-widths from the tip
        else -> 1.0
    }

    private fun rgb(argb: Int) = doubleArrayOf(((argb shr 16) and 0xFF) / 255.0, ((argb shr 8) and 0xFF) / 255.0, (argb and 0xFF) / 255.0)
}
