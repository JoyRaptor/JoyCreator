package cc.joycreator.joybrush.androidkit.preview

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.util.Log
import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.gl.ShaderLibrary
import cc.joycreator.joybrush.androidkit.gl.media.MediaLayerEngine
import cc.joycreator.joybrush.androidkit.tools.FillPenRaster
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.TuftStroke
import cc.joycreator.joybrush.core.brush.WatercolourDabber
import cc.joycreator.joybrush.core.brush.watercolourRuntime
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.fill.MaskPaintMode
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.TipShape
import java.nio.ByteBuffer

/** One slice at a time of a preview being made. GL thread only. */
internal interface PreviewWork {
    /** Does one slice; true when the work is over (the [bitmap] is made, or it failed and there is none). */
    fun step(): Boolean
    val bitmap: Bitmap?
    /** Gives back whatever GL objects the work holds. */
    fun abort()
}

/**
 * The brush drawer's stroke previews, drawn by the engines themselves (owner, 2026-10-17: the stand-in "doesn't tell
 * clearly what things look like"). Every request is one canned stroke ([CannedStroke]) of one brush, laid on a THROWAWAY
 * engine that shares the canvas's GL context and nothing else, so the person's drawing, its undo history and its media
 * window are never touched (the precedent is JbCanvasView.runBlendCheck):
 *
 *  - stamp, eraser, smudge and tuft brushes go through [GlPaintEngine]'s own stroke (dabber, placer, scatter, grain, the
 *    commit shader). The eraser cuts through a strip it is given; a smudge drags one colour band into another.
 *  - the fill pen draws its closed blob with its own raster ([FillPenRaster]); no GPU.
 *  - the watercolour LINE brush runs its watercolour pass, on cold-press paper.
 *  - pencil, watercolour and oil run the media builders on a [MediaLayerEngine] the size of the row ([MediaPreviewJob]),
 *    watercolour dried with dryNow so the picture is final.
 *
 * Work is spread: [pump] runs a few slices per frame at most, so asking for a drawer-full of previews never stalls the
 * canvas. Everything here is GL thread only except [Request.ticket]. Every GL object made is released when the queue has
 * been quiet for [IDLE_MS], and on [forget] (a lost context) nothing is deleted, because the names are dead.
 *
 * [memory] answers (available bytes, low-memory threshold) from the phone, as MediaCanvas asks, so a media preview never
 * pushes the phone into killing apps: when there is no room it simply returns no picture.
 */
class BrushPreviewRenderer(private val memory: () -> Pair<Long, Long>) {

    class Request(
        val preset: BrushPreset,
        val width: Int,
        val height: Int,
        val style: BrushPreviewStyle,
        val ticket: BrushPreviewTicket,
        /** Called on the GL thread; null when no picture could be made. */
        val done: (Bitmap?) -> Unit,
    )

    private val queue = ArrayDeque<Request>()
    private var pumping = false
    private var active: Pair<Request, PreviewWork>? = null
    private val shaders = ShaderLibrary()
    private var paint: GlPaintEngine? = null
    private var media: MediaLayerEngine? = null
    private var mediaW = 0
    private var mediaH = 0
    private var mediaUnsupported = false
    private var lastBusyMs = 0L

    /**
     * Queues [r]. True when the caller must start the pump chain (nothing was pumping); false when a chain is already running
     * or the request was refused (its [Request.done] has then been told).
     */
    fun submit(r: Request): Boolean {
        if (r.width !in MIN_PX..MAX_PX || r.height !in MIN_PX..MAX_PX || r.ticket.cancelled) {
            r.done(null)
            return false
        }
        queue.addLast(r)
        if (pumping) return false
        pumping = true
        return true
    }

    /**
     * Spends this frame's share: at least one slice, and more while they are quick, never more than [MAX_STEPS]. True while
     * work remains (call again next frame); false when the queue is empty, after which the next [submit] starts a chain.
     */
    fun pump(nowMs: Long): Boolean {
        val began = System.nanoTime()
        var steps = 0
        try {
            while (steps < MAX_STEPS) {
                val (req, work) = active ?: next() ?: break
                active = req to work
                val finished = try {
                    work.step()
                } catch (t: Throwable) {
                    Log.w(TAG, "preview of ${req.preset.id} failed", t)
                    runCatching { work.abort() }
                    true
                }
                if (finished) {
                    active = null
                    deliver(req, work.bitmap)
                }
                steps++
                if ((System.nanoTime() - began) / 1_000_000.0 >= BUDGET_MS) break
            }
        } finally {
            quiet()
        }
        lastBusyMs = nowMs
        val more = active != null || queue.isNotEmpty()
        if (!more) pumping = false
        return more
    }

    /** Lets every GL object go once nothing has been asked for [IDLE_MS]. Call on the GL thread. */
    fun releaseIfIdle(nowMs: Long) {
        if (active != null || queue.isNotEmpty() || nowMs - lastBusyMs < IDLE_MS) return
        releaseGl()
    }

    /** The GL context is gone: tell everyone waiting there is no picture, and drop every name without deleting it. */
    fun forget() {
        val waiting = ArrayList<Request>()
        active?.let { waiting += it.first }
        waiting += queue
        queue.clear()
        active = null
        pumping = false
        paint = null
        media = null
        mediaW = 0
        mediaH = 0
        for (r in waiting) r.done(null)
    }

    // ---- inside ----

    private fun next(): Pair<Request, PreviewWork>? {
        while (true) {
            val req = queue.removeFirstOrNull() ?: return null
            if (req.ticket.cancelled) continue
            val work = try {
                start(req)
            } catch (t: Throwable) {
                Log.w(TAG, "preview of ${req.preset.id} could not start", t)
                null
            }
            if (work == null) { req.done(null); continue }
            return req to work
        }
    }

    private fun deliver(req: Request, bitmap: Bitmap?) {
        if (req.ticket.cancelled) { bitmap?.recycle(); return }
        req.done(bitmap)
    }

    private fun start(req: Request): PreviewWork? {
        val kind = PreviewPlan.kindOf(req.preset)
        return when (kind) {
            PreviewKind.NONE -> null
            PreviewKind.FILL -> OneShot { renderFill(req) }
            PreviewKind.MEDIA_DRY, PreviewKind.MEDIA_WET, PreviewKind.MEDIA_PASTE -> startMedia(req, kind)
            else -> OneShot { renderStroke(req, kind) }
        }
    }

    private class OneShot(private val run: () -> Bitmap?) : PreviewWork {
        override var bitmap: Bitmap? = null
            private set

        override fun step(): Boolean {
            bitmap = run()
            return true
        }

        override fun abort() {}
    }

    private fun paperOf(kind: PreviewKind): Paper = if (kind == PreviewKind.MEDIA_WET || kind == PreviewKind.WATERCOLOUR) WET_PAPER else DRAWING_PAPER

    /** The throwaway engine, made on first use, on [paper]. */
    private fun engine(paper: Paper): GlPaintEngine {
        val e = paint ?: GlPaintEngine(shaders, UNDO_BYTES).also { fresh ->
            try { fresh.init() } catch (t: Throwable) { runCatching { fresh.release() }; throw t }
            paint = fresh
        }
        e.setDocumentPaper(paper)
        e.resetDocument()
        e.addLayer(LAYER)
        return e
    }

    // ---- stamp, tuft, smudge, eraser, watercolour line ----

    private fun renderStroke(req: Request, kind: PreviewKind): Bitmap? {
        val w = req.width
        val h = req.height
        val shown = PreviewPlan.shown(req.preset, h)
        val onPaper = kind == PreviewKind.WATERCOLOUR
        val e = engine(paperOf(kind))
        val ink = if (onPaper) req.style.mediaInk else req.style.ink
        val erase = PreviewPlan.erases(shown)

        // What the stroke works on: a strip for the eraser to cut, two colour bands for the smudge to drag.
        val ground = HashMap<Long, ByteArray>()
        if (kind == PreviewKind.SMUDGE) {
            PreviewRaster.fillRect(ground, 0, 0, w / 2, h, req.style.bandLeft)
            PreviewRaster.fillRect(ground, w / 2, 0, w, h, req.style.bandRight)
        } else if (erase) {
            PreviewRaster.fillRect(ground, 0, (h * STRIP_INSET).toInt(), w, h - (h * STRIP_INSET).toInt(), req.style.strip)
        }
        for ((key, bytes) in ground) e.writeTile(LAYER, key, bytes)

        // A smudge begins carrying the colour it begins on.
        val argb = if (kind == PreviewKind.SMUDGE) req.style.bandLeft else ink
        val samples = CannedStroke.pen(w, h, shown.size.base)
        when (kind) {
            PreviewKind.WATERCOLOUR -> {
                val seed = shown.watercolour?.sampleSeeds?.firstOrNull() ?: CannedStroke.SEED
                val dabber = WatercolourDabber(shown.watercolourRuntime(), seed)
                val dabs = ArrayList<Dab>()
                // The watercolour dabber owns its own pressure and tilt filters: the pen goes in raw, as live.
                for (s in samples) dabs += dabber.add(s)
                dabs += dabber.finish()
                e.beginStroke(LAYER, ink, dabber.strokeOpacity, StampPreview.accumulateOf(shown), StampPreview.blendOf(shown),
                    TipShape(shown.tip.aspect, shown.tip.corner, shown.tip.taper, shown.tip.hardness.base, shown.tip.minPx),
                    watercolour = dabber.spec, watercolourSeed = seed)
                e.addDabs(dabs)
            }
            PreviewKind.TUFT -> {
                val stroke = TuftStroke(shown, CannedStroke.SEED)
                val stamps = stroke.add(samples.map { shown.response.apply(it) }) + stroke.finish()
                e.beginStroke(LAYER, ink, shown.opacity.base, StampPreview.accumulateOf(shown), StampPreview.blendOf(shown),
                    TipShape(shown.tip.aspect, shown.tip.corner, shown.tip.taper, shown.tip.hardness.base, shown.tip.minPx),
                    tuft = TuftStroke.shading(shown, CannedStroke.SEED))
                e.addTuftStamps(stamps)
            }
            else -> {
                val p = StampPreview.build(shown, samples)
                e.beginStroke(LAYER, argb, p.strokeOpacity, p.accumulate, p.blend, p.tip, p.grain, p.smudge)
                e.addDabs(p.dabs)
            }
        }
        e.endStroke()
        // A watercolour stroke fades its pooling in over a moment on the canvas; the preview is the settled picture.
        e.settleWatercolourFade()

        val tiles = HashMap<Long, ByteArray>()
        for (key in e.tileKeys(LAYER)) {
            if (Tiles.tx(key) !in 0..(w - 1) / Tiles.SIZE || Tiles.ty(key) !in 0..(h - 1) / Tiles.SIZE) continue
            e.readTile(LAYER, key)?.let { tiles[key] = it }
        }
        val rgba = PreviewRaster.assemble(tiles, w, h)
        if (onPaper) PreviewRaster.overPaper(rgba, e.renderPaper(RectPx(0, 0, w, h)))
        return bitmapOf(rgba, w, h)
    }

    // ---- the fill pen ----

    private fun renderFill(req: Request): Bitmap? {
        val w = req.width
        val h = req.height
        val shown = PreviewPlan.shown(req.preset, h)
        val mask = FillPenRaster.prepare(CannedStroke.loop(w, h), shown.smoothing, 1f)
        val changes = FillPenRaster.plan(mask, req.style.ink, shown.opacity.base, MaskPaintMode.FILL, null) { null }
        val tiles = HashMap<Long, ByteArray>()
        for ((key, bytes) in changes) if (bytes != null) tiles[key] = bytes
        return bitmapOf(PreviewRaster.assemble(tiles, w, h), w, h)
    }

    // ---- pencil, watercolour, oil ----

    private fun startMedia(req: Request, kind: PreviewKind): PreviewWork? {
        if (mediaUnsupported) return null
        val wet = kind == PreviewKind.MEDIA_WET || kind == PreviewKind.MEDIA_PASTE &&
            PreviewPlan.shown(req.preset, req.height).media?.let { it.thinner > 0 && it.paste?.let { p -> p.shape < 2 && !p.clean } == true } == true
        val ww = MediaPreviewJob.windowSide(req.width)
        val wh = MediaPreviewJob.windowSide(req.height)
        if (ww > MAX_PX || wh > MAX_PX) return null
        val (avail, threshold) = memory()
        val need = ww.toLong() * wh * (if (wet) WET_BYTES_PER_PX else DRY_BYTES_PER_PX)
        if (avail - 2 * threshold < need) {
            Log.w(TAG, "no room for a media preview: need=$need available=$avail threshold=$threshold")
            return null
        }
        val e = engine(paperOf(kind))
        val me = mediaEngine(ww, wh) ?: return null
        me.wake()
        return try {
            MediaPreviewJob(req, e, me)
        } catch (t: Throwable) {
            me.sleep()
            throw t
        }
    }

    private fun mediaEngine(w: Int, h: Int): MediaLayerEngine? {
        media?.let { if (mediaW == w && mediaH == h) return it }
        media?.let { runCatching { it.release() } }
        media = null
        val made = MediaLayerEngine(shaders, atlasMode = false)
        try {
            made.init(w, h)
        } catch (x: IllegalStateException) {
            // No float colour buffers: the canvas refuses pencil, watercolour and oil on this phone for the same reason.
            runCatching { made.release() }
            mediaUnsupported = true
            return null
        }
        media = made
        mediaW = w
        mediaH = h
        return made
    }

    // ---- housekeeping ----

    private fun releaseGl() {
        media?.let { runCatching { it.release() } }
        media = null
        mediaW = 0
        mediaH = 0
        paint?.let { runCatching { it.release() } }
        paint = null
    }

    /** What the next frame's own drawing must not inherit from this one. */
    private fun quiet() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        // A stray GL error would be blamed on the next caller that checks (a tile read-back checks after its own call).
        var guard = 0
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR && guard++ < 16) { /* drained */ }
    }

    companion object {
        private const val TAG = "JoyBrushPreview"

        /** How long the quiet engines are kept before their GL objects are freed (ms). */
        const val IDLE_MS = 8_000L

        /** Slices per frame at most, and the time after which no more are started this frame. */
        private const val MAX_STEPS = 3
        private const val BUDGET_MS = 8.0

        const val MIN_PX = 8
        const val MAX_PX = 2048

        private const val LAYER = "preview"
        private const val UNDO_BYTES = 16L shl 20

        /** The eraser's strip stops this share of the picture's height short of the top and bottom edges. */
        private const val STRIP_INSET = 0.12f

        /** Bytes per pixel of a media engine's window (LayerBudget.MEDIA_WINDOW_DRY_BYTES / WET_BYTES, per pixel). */
        private const val DRY_BYTES_PER_PX = 132L
        private const val WET_BYTES_PER_PX = 240L

        /** The paper pencil, oil and the stamp brushes' grain meet: a drawing paper with a fine tooth. */
        val DRAWING_PAPER = Paper(lookId = "drawing_paper", textureId = "drawing_tooth")

        /** The paper water meets: cold-press watercolour paper. */
        val WET_PAPER = Paper(lookId = "watercolour_cold", textureId = "cold_press")

        /** A [BrushPreviewRenderer] that asks this phone for its free memory. */
        fun forContext(context: Context): BrushPreviewRenderer = BrushPreviewRenderer {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem to info.threshold
        }

        /** [rgba] (premultiplied, rows top first) as an ARGB_8888 bitmap. */
        internal fun bitmapOf(rgba: ByteArray, w: Int, h: Int): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
            return bmp
        }
    }
}
