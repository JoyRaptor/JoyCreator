package cc.joycreator.joybrush.androidkit.preview

import android.graphics.Bitmap
import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.gl.media.MediaLayerEngine
import cc.joycreator.joybrush.androidkit.gl.media.MediaLook
import cc.joycreator.joybrush.androidkit.gl.media.MediaPaperGl
import cc.joycreator.joybrush.core.brush.MEDIUM_DRY
import cc.joycreator.joybrush.core.brush.MEDIUM_PASTE
import cc.joycreator.joybrush.core.brush.MEDIUM_WET
import cc.joycreator.joybrush.core.brush.mediaScale
import cc.joycreator.joybrush.core.brush.scaled
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.media.DOC_PX_PER_MM
import cc.joycreator.joybrush.core.media.DabBatch
import cc.joycreator.joybrush.core.media.DryStroke
import cc.joycreator.joybrush.core.media.MediaBelly
import cc.joycreator.joybrush.core.media.MediaInput
import cc.joycreator.joybrush.core.media.MediaSample
import cc.joycreator.joybrush.core.media.MediaSpline
import cc.joycreator.joybrush.core.media.PasteBrush
import cc.joycreator.joybrush.core.media.PasteStroke
import cc.joycreator.joybrush.core.media.StickMaterial
import cc.joycreator.joybrush.core.media.THINNER
import cc.joycreator.joybrush.core.media.WETNESS
import cc.joycreator.joybrush.core.media.WetConstants
import cc.joycreator.joybrush.core.media.WetStroke
import cc.joycreator.joybrush.core.media.opaquePaint
import cc.joycreator.joybrush.core.media.paintFromColor
import cc.joycreator.joybrush.core.media.thinnerStroke
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * One pencil, watercolour or oil preview, made by the app's own media builders (DryStroke, WetStroke, PasteStroke) on a
 * [MediaLayerEngine] just big enough for the row, in slices the pump spends a few at a time so a frame never carries a whole
 * stroke. It does what [cc.joycreator.joybrush.androidkit.gl.media.MediaCanvas] does for a live stroke, without a layer, a
 * window or an undo step: the picture is read straight from the look and laid on the reference paper.
 *
 * A wet stroke is left to run a short while and is then dried with [MediaLayerEngine.dryNow], so what is shown is where the
 * water settles, not a wash caught mid-flow. GL thread only; the engine is borrowed and put to sleep again at the end.
 */
internal class MediaPreviewJob(
    private val req: BrushPreviewRenderer.Request,
    private val paint: GlPaintEngine,
    private val me: MediaLayerEngine,
) : PreviewWork {

    override var bitmap: Bitmap? = null
        private set

    private val w = req.width
    private val h = req.height
    private val ppm = DOC_PX_PER_MM
    private val paper: MediaPaperGl = paint.mediaPaper()
    private val shown = PreviewPlan.shown(req.preset, h)
    private val scale = shown.mediaScale()
    private val spec = requireNotNull(shown.media) { "a media preview needs a media section" }.scaled(scale)
    private val color = rgb(req.style.mediaInk)
    private val seed = (CannedStroke.SEED and 0x7FFFFFFF).toInt()
    private val samples: List<MediaSample>
    private val spline: MediaSpline

    private var dry: DryStroke? = null
    private var mat: StickMaterial? = null
    private var wet: WetStroke? = null
    private var paste: PasteStroke? = null
    private var pasteBrush: PasteBrush? = null
    private var thin: WetStroke? = null

    private enum class Phase { PAINT, SETTLE, FINISH, DONE }
    private var phase = Phase.PAINT
    private var next = 0
    private var settled = 0
    private var slept = false

    /** Whether water runs in this stroke (watercolour, or oil thinned with a runny brush). */
    val wets: Boolean

    init {
        val pen = CannedStroke.pen(w, h, shown.size.base, pitchPx = SAMPLE_PITCH_PX)
        val reach = shown.media?.stick?.tuning?.tiltReachDeg ?: MediaInput.REACH_DEG
        samples = pen.map { MediaInput.sample(shown.response.apply(it.copy(x = it.x + PAD, y = it.y + PAD)), reach) }
        val thinned = spec.medium == MEDIUM_PASTE && spec.thinner > 0 && spec.paste?.let { it.shape < 2 && !it.clean } == true
        wets = spec.medium == MEDIUM_WET || thinned
        var sink: (MediaSample) -> Unit = {}
        when (spec.medium) {
            MEDIUM_DRY -> {
                val stick = spec.stick!!
                val d = DryStroke(stick, paper.stats.toothMm, ppm)
                dry = d
                mat = StickMaterial.of(stick)
                sink = d::add
            }
            MEDIUM_WET -> {
                val brush = spec.wet!!
                val level = WETNESS[spec.wetness.coerceIn(0, WETNESS.lastIndex)]
                // A clear brush (water, thirsty) carries at least its own load of water whatever the level says (as live).
                val wetness = if (brush.clear) level.copy(load = max(level.load, brush.load)) else level
                val s = WetStroke(brush, paintFromColor(color), ppm, seed, wetness)
                wet = s
                sink = s::add
            }
            MEDIUM_PASTE -> {
                val brush = spec.paste!!
                val belly = MediaBelly.colorFor(spec.belly, color, seed)?.takeIf { spec.isHair }?.let { opaquePaint(it) }
                me.reloadBrush(opaquePaint(color), brush, seed, belly)
                val body = if (thinned) THINNER[spec.thinner.coerceIn(0, THINNER.lastIndex)].body else 1.0
                val ps = PasteStroke(brush, ppm, 1.0, body = body)
                val t = if (thinned) thinnerStroke(brush, color, ppm, seed, spec.thinner) else null
                paste = ps
                pasteBrush = brush
                thin = t
                sink = { s -> t?.add(s); ps.add(s) }
            }
            else -> error("not a medium: ${spec.medium}")
        }
        spline = MediaSpline(sink = sink)
        me.beginStroke()
    }

    override fun step(): Boolean {
        if (req.ticket.cancelled) { abort(); return true }
        when (phase) {
            Phase.PAINT -> {
                repeat(FRAMES_PER_STEP) {
                    if (next < samples.size) {
                        val end = minOf(samples.size, next + CHUNK)
                        for (i in next until end) spline.add(samples[i])
                        next = end
                        frame()
                    }
                }
                if (next >= samples.size) {
                    spline.finish()
                    wet?.finish()
                    thin?.finish()
                    frame()
                    me.endStroke()
                    phase = if (wets) Phase.SETTLE else Phase.FINISH
                }
            }
            Phase.SETTLE -> {
                repeat(FRAMES_PER_STEP) {
                    if (settled < SETTLE_FRAMES && me.wetActive) {
                        me.wetFrame(null, paper, ppm, WetConstants())
                        settled++
                    }
                }
                if (settled >= SETTLE_FRAMES || !me.wetActive) phase = Phase.FINISH
            }
            Phase.FINISH -> {
                finish()
                phase = Phase.DONE
            }
            Phase.DONE -> {}
        }
        return phase == Phase.DONE
    }

    /** One frame, as MediaCanvas.run does it: the stroke's dabs and steps, then water already running. */
    private fun frame() {
        val batch = dry?.take()
        if (batch != null && batch.count > 0) {
            val last = (batch.count - 1) * DabBatch.FLOATS
            me.dryFrame(batch, batch.dabs[last + 16].toDouble(), batch.dabs[last + 17].toDouble(), paper, mat!!, ppm)
        }
        thin?.let { me.wetFrame(it.take(), paper, ppm, WetConstants()) }
        paste?.let { ps -> for (step in ps.take()) me.pasteStep(step, pasteBrush!!, paper, ppm, seed) }
        wet?.let { me.wetFrame(it.take(), paper, ppm, WetConstants()) }
        if (wet == null && thin == null && me.wetActive) me.wetFrame(null, paper, ppm, WetConstants())
    }

    private fun finish() {
        try {
            // Dry where it lies, so the picture is final.
            me.dryNow(paper, ppm)
            val look = MediaLook(rgb(paint.paperArgb).map { it.toFloat() }.toFloatArray(), lamp = LAMP)
            me.renderLook(floatArrayOf(PAD.toFloat(), PAD.toFloat(), (PAD + w).toFloat(), (PAD + h).toFloat()), paper, look, ppm)
            val rgba = readLook()
            PreviewRaster.overPaper(rgba, paint.renderPaper(RectPx(PAD, PAD, w, h)))
            bitmap = BrushPreviewRenderer.bitmapOf(rgba, w, h)
        } finally {
            sleep()
        }
    }

    /** The look's rows for the picture, top row first (look row 0 is document row 0, as in every tile). */
    private fun readLook(): ByteArray {
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        try {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, me.lookTexture(), 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "preview readback target incomplete" }
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(PAD, PAD, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            val out = ByteArray(w * h * 4)
            buf.rewind()
            buf.get(out)
            return out
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
        }
    }

    /** Gives the window's textures back (the programs stay for the next preview). */
    override fun abort() {
        phase = Phase.DONE
        sleep()
    }

    private fun sleep() {
        if (slept) return
        slept = true
        me.sleep()
    }

    private fun rgb(argb: Int) = doubleArrayOf(((argb shr 16) and 0xFF) / 255.0, ((argb shr 8) and 0xFF) / 255.0, (argb and 0xFF) / 255.0)

    companion object {
        /** Room round the picture on every side, so the water, the pencil's side and the oil's shadow reach (53 px) all land in the window. */
        const val PAD = 56

        /** A pen report about this far apart: what a stylus gives at a relaxed stroke. */
        private const val SAMPLE_PITCH_PX = 5f

        /** Pen reports per drawn frame, and frames per slice the pump spends. */
        private const val CHUNK = 6
        private const val FRAMES_PER_STEP = 3

        /** Frames the water runs after the lift before it is dried (a settling, not a wait for the real evaporation). */
        private const val SETTLE_FRAMES = 24

        /** The app's lamp, in document space (y down): light from the upper left (MediaCanvas.look). */
        private val LAMP = MediaLook.normalise(floatArrayOf(-0.55f, -0.55f, 0.62f))

        /** The window side the picture needs, with its [PAD] all round. */
        fun windowSide(px: Int): Int = px + 2 * PAD
    }
}
