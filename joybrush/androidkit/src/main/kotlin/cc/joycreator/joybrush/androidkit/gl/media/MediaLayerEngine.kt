package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.ShaderLibrary
import cc.joycreator.joybrush.core.media.DabBatch
import cc.joycreator.joybrush.core.media.MediaPaper
import cc.joycreator.joybrush.core.media.OpaquePaint
import cc.joycreator.joybrush.core.media.PASTE_DEPTH
import cc.joycreator.joybrush.core.media.PASTE_LANES
import cc.joycreator.joybrush.core.media.PasteBrush
import cc.joycreator.joybrush.core.media.PasteStep
import cc.joycreator.joybrush.core.media.StickMaterial
import cc.joycreator.joybrush.core.media.WetConstants
import cc.joycreator.joybrush.core.media.WetSpread
import cc.joycreator.joybrush.core.media.cellCap
import cc.joycreator.joybrush.core.media.wetUniforms
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The paper as the media passes read it: the document surface the app already binds for its brushes (the same
 * jb_paper.glsl uniforms), plus the media statistics (contact table, height percentiles) from [MediaPaper].
 */
class MediaPaperGl(
    val stats: MediaPaper,
    val surface: Tex, val texelPx: Float, val size: Float, val hexTexels: Float, val slopeRange: Float, val rotatable: Boolean,
    val fluid: Tex, val fluidTexelPx: Float, val fluidSize: Float, val fluidHexTexels: Float,
) {
    fun uniforms(): Map<String, Any> = mapOf(
        "u_paperSurface" to surface, "u_paperTexelPx" to texelPx, "u_paperSize" to size, "u_paperHexTexels" to hexTexels,
        "u_paperSlopeRange" to slopeRange, "u_paperRotatable" to rotatable, "u_paperHeightMean" to stats.heightMean.toFloat(),
        "u_paperHBot" to stats.heightBot.toFloat(), "u_paperHTop" to stats.heightTop.toFloat(),
        "u_paperFluid" to fluid, "u_paperFluidTexelPx" to fluidTexelPx, "u_paperFluidSize" to fluidSize,
        "u_paperFluidHexTexels" to fluidHexTexels,
    )
}

/** How the look tiles are lit (the app's fixed lamp; relief 0 because the app draws the paper itself). */
class MediaLook(
    val paperColor: FloatArray, val lamp: FloatArray = floatArrayOf(-0.55f, 0.55f, 0.62f).let { normalise(it) },
    val impasto: Float = 1.4f, val sheen: Float = 0.06f, val capMm: Float = 0.004f,
) {
    companion object {
        fun normalise(v: FloatArray): FloatArray { val l = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return floatArrayOf(v[0] / l, v[1] / l, v[2] / l) }
    }
}

/**
 * One media layer's GPU state and passes (M5.4): a port of lab/media/js/engine.js, running the shared shaders in
 * joybrush/shaders/media unchanged. Layer state is FULL float (p0, p1, paper; w0/w1 while wet): watercolour moves
 * pigment in thousands of tiny steps and half floats stall it (seen 2026-10-06). The device must support
 * EXT_color_buffer_float (the Note 9 does); [init] refuses without it rather than fall back.
 *
 * The working textures are a WINDOW ([w] × [h] px at document [originX], [originY]; layerPx = (docPx − origin) · scale),
 * never the whole canvas (contract point 8). [MediaWindow] places it, loads the layer's stores into it ([loadTargets],
 * [lookTexture]) and writes back what the passes wrote ([takeDirtyStores], [current]) through the app's stores
 * (#p0 #p1 #paper #w0 #w1 + the RGBA8 look; MEDIA_ENGINE_PLAN M5.3c). GL thread only.
 */
class MediaLayerEngine(private val shaders: ShaderLibrary = ShaderLibrary()) {
    var w = 0; private set
    var h = 0; private set
    private var originX = 0f
    private var originY = 0f
    private var scale = 1f

    private lateinit var progDab: MediaProgram
    private lateinit var progApply: MediaProgram
    private lateinit var progCopy: MediaProgram
    private lateinit var progRender: MediaProgram
    private lateinit var progBake: MediaProgram
    private lateinit var progWetDab: MediaProgram
    private lateinit var progFlux: MediaProgram
    private lateinit var progUpdate: MediaProgram
    private lateinit var progPasteDab: MediaProgram
    private lateinit var progPasteBrush: MediaProgram
    private lateinit var progBead: MediaProgram

    private class State(val p0: Int, val p1: Int, val paper: Int, val fbo: Int)
    private lateinit var state: Array<State>
    private var pFbo0 = 0
    private var pFbo1 = 0
    private class Brush(val b0: Int, val b1: Int, val fbo: Int)
    private lateinit var brush: Array<Brush>
    private var bc = 0

    // made on first use
    private var delta = 0; private var deltaFbo = 0
    var bakeTex = 0; private set
    private var fluidBake = 0; private var waterBake = 0; private var bakeFbo = 0
    private var bakedPaper: MediaPaperGl? = null
    private class Wet(val w0: Int, val w1: Int, val flux: Int)
    private var wet: Array<Wet>? = null
    private var run = 0
    private var bead = IntArray(0); private var beadFbo = IntArray(0); private var beadW = 0; private var beadH = 0
    private var fluxFbo = IntArray(0); private var updFbo = IntArray(0); private var wetCopyFbo = IntArray(0)
    private var in0 = 0; private var in1 = 0; private var inFbo = 0
    private var wc = 0
    private var lutTex = 0
    private var lutSrc: Any? = null
    private var lookTex = 0; private var lookFbo = 0
    private var empty = 0

    private var quadBuf = 0; private var quadVao = 0; private var dabBuf = 0; private var dabVao = 0; private var emptyVao = 0
    private var readFbo = 0; private var drawFbo = 0

    /** Where water is being simulated (layer px), null = everything dry. */
    var wetRect: FloatArray? = null; private set
    private var paintRect: FloatArray? = null
    private var wetIdle = 0.0
    private var wetSince: FloatArray? = null
    /** The paper's tilt as a downhill slope (sin of the angle), layer space; [0, 0] = flat. */
    var slope = floatArrayOf(0f, 0f)

    val wetActive get() = wetRect != null

    fun init(width: Int, height: Int, originX: Float = 0f, originY: Float = 0f, scale: Float = 1f) {
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        check("EXT_color_buffer_float" in ext) { "media layers need EXT_color_buffer_float" }
        w = width; h = height; this.originX = originX; this.originY = originY; this.scale = scale
        fun p(v: String, f: String, l: String) = MediaProgram(shaders, "media/$v", "media/$f", l)
        progDab = p("jb_dry_dab.vert", "jb_dry_dab.frag", "media dry dab")
        progApply = p("jb_media_quad.vert", "jb_media_apply.frag", "media apply")
        progCopy = p("jb_media_quad.vert", "jb_media_copy.frag", "media copy")
        progRender = p("jb_media_screen.vert", "jb_media_render.frag", "media render")
        progBake = p("jb_media_quad.vert", "jb_media_bake.frag", "media bake")
        progWetDab = p("jb_wet_dab.vert", "jb_wet_dab.frag", "media wet dab")
        progFlux = p("jb_media_quad.vert", "jb_wet_flux.frag", "media wet flux")
        progUpdate = p("jb_media_quad.vert", "jb_wet_update.frag", "media wet update")
        progPasteDab = p("jb_media_quad.vert", "jb_paste_dab.frag", "media paste dab")
        progPasteBrush = p("jb_media_quad.vert", "jb_paste_brush.frag", "media paste brush")
        progBead = p("jb_media_quad.vert", "jb_wet_bead.frag", "media wet bead")
        allocate()
        brush = Array(2) {
            val b0 = MediaTex.make(PASTE_LANES, PASTE_DEPTH, GLES30.GL_RGBA32F, GLES30.GL_RGBA, GLES30.GL_FLOAT, GLES30.GL_NEAREST)
            val b1 = MediaTex.make(PASTE_LANES, PASTE_DEPTH, GLES30.GL_RGBA32F, GLES30.GL_RGBA, GLES30.GL_FLOAT, GLES30.GL_NEAREST)
            Brush(b0, b1, MediaTex.fbo(b0, b1))
        }
        val ids = IntArray(2)
        GLES30.glGenFramebuffers(2, ids, 0); readFbo = ids[0]; drawFbo = ids[1]
        // One unit quad at attribute 0 for every pass; the dab VAO adds five instanced vec4s (20 floats).
        GLES30.glGenBuffers(2, ids, 0); quadBuf = ids[0]; dabBuf = ids[1]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuf)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 32, MediaTex.floats(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)), GLES30.GL_STATIC_DRAW)
        val vaos = IntArray(3)
        GLES30.glGenVertexArrays(3, vaos, 0); quadVao = vaos[0]; dabVao = vaos[1]; emptyVao = vaos[2]
        GLES30.glBindVertexArray(quadVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuf)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(dabVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuf)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dabBuf)
        for (i in 0 until 5) {
            GLES30.glEnableVertexAttribArray(1 + i)
            GLES30.glVertexAttribPointer(1 + i, 4, GLES30.GL_FLOAT, false, DabBatch.FLOATS * 4, i * 16)
            GLES30.glVertexAttribDivisor(1 + i, 1)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        clearAll()
    }

    /** The full-float state: the one part of the window every medium needs. */
    private fun allocate() {
        state = Array(2) {
            val p0 = MediaTex.full(w, h); val p1 = MediaTex.full(w, h); val pp = MediaTex.full(w, h)
            State(p0, p1, pp, MediaTex.fbo(p0, p1, pp))
        }
        pFbo0 = MediaTex.fbo(state[0].p0, state[0].p1)
        pFbo1 = MediaTex.fbo(state[1].p0, state[1].p1)
        asleep = false
    }

    /** True while the window's textures are released ([sleep]); the programs and the brush cells stay. */
    var asleep = false; private set

    /** Whether water targets exist: what [cc.joycreator.joybrush.core.layers.LayerBudget.windowFits] is told the window holds. */
    val hasWater: Boolean get() = wet != null

    /**
     * Releases the window's textures (the Lead's rule: the window is transient; the pen has been up and the water asleep
     * for a while, or the layer or screen changed). Everything worth keeping is in the stores already. [wake] makes them
     * again for the next stroke without recompiling a shader.
     */
    fun sleep() {
        if (asleep) return
        val tex = ArrayList<Int>()
        for (s in state) tex += listOf(s.p0, s.p1, s.paper)
        wet?.forEach { tex += listOf(it.w0, it.w1, it.flux) }
        tex += listOf(delta, bakeTex, fluidBake, waterBake, run, in0, in1, lookTex) + bead.toList()
        tex.filter { it != 0 }.toIntArray().let { if (it.isNotEmpty()) MediaTex.deleteTex(*it) }
        val fbos = ArrayList<Int>()
        for (s in state) fbos += s.fbo
        fbos += listOf(pFbo0, pFbo1, deltaFbo, bakeFbo, inFbo, lookFbo) + beadFbo.toList() + fluxFbo.toList() + updFbo.toList() + wetCopyFbo.toList()
        fbos.filter { it != 0 }.toIntArray().let { if (it.isNotEmpty()) MediaTex.deleteFbo(*it) }
        delta = 0; deltaFbo = 0; bakeTex = 0; fluidBake = 0; waterBake = 0; bakeFbo = 0; bakedPaper = null
        wet = null; run = 0; in0 = 0; in1 = 0; inFbo = 0; lookTex = 0; lookFbo = 0
        bead = IntArray(0); beadFbo = IntArray(0); fluxFbo = IntArray(0); updFbo = IntArray(0); wetCopyFbo = IntArray(0)
        pFbo0 = 0; pFbo1 = 0
        wetRect = null; paintRect = null; wetSince = null; stores.clear(); dirtySinceLook = null; strokeRect = null
        asleep = true
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Makes the window's state again after [sleep]; water targets follow lazily, as on first use. */
    fun wake() {
        if (!asleep) return
        allocate()
        clearAll()
    }

    // ---- textures made on first use ----
    private fun ensureDry() {
        if (delta != 0) return
        delta = MediaTex.make(w, h); deltaFbo = MediaTex.fbo(delta); clearFbo(deltaFbo)
    }

    private fun ensureBake() {
        if (bakeTex != 0) return
        bakeTex = MediaTex.make(w, h)
        fluidBake = MediaTex.make(w, h, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_NEAREST)
        waterBake = MediaTex.make(w, h)
        bakeFbo = MediaTex.fbo(bakeTex, fluidBake, waterBake)
    }

    private fun ensureWet() {
        if (wet != null) return
        ensureBake()
        val ws = Array(2) { Wet(MediaTex.full(w, h), MediaTex.full(w, h), MediaTex.make(w, h)) }
        wet = ws
        run = MediaTex.make(w, h)
        beadW = (w + 1) / 2; beadH = (h + 1) / 2
        bead = IntArray(2) { MediaTex.make(beadW, beadH) }
        beadFbo = IntArray(2) { MediaTex.fbo(bead[it]) }
        fluxFbo = IntArray(2) { MediaTex.fbo(ws[it].flux, run) }
        updFbo = IntArray(2) { MediaTex.fbo(ws[it].w0, ws[it].w1, state[1].p0, state[1].p1) }
        wetCopyFbo = IntArray(2) { if (it == 0) MediaTex.fbo(ws[0].w0, ws[0].w1, ws[0].flux, run) else MediaTex.fbo(ws[1].w0, ws[1].w1, ws[1].flux) }
        in0 = MediaTex.make(w, h); in1 = MediaTex.make(w, h); inFbo = MediaTex.fbo(in0, in1)
        for (f in wetCopyFbo) clearFbo(f)
        clearFbo(inFbo)
    }

    private fun emptyTex(): Int {
        if (empty == 0) empty = MediaTex.make(1, 1, GLES30.GL_RGBA16F, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, GLES30.GL_NEAREST)
        return empty
    }

    private fun clearFbo(f: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    fun clearAll() {
        for (s in state) clearFbo(s.fbo)
        if (deltaFbo != 0) clearFbo(deltaFbo)
        if (wet != null) { for (f in wetCopyFbo) clearFbo(f); clearFbo(inFbo) }
        wetRect = null
        paintRect = null
        wetIdle = 0.0
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun common(values: Map<String, Any?>): Map<String, Any?> =
        mapOf<String, Any?>("u_layerOrigin" to floatArrayOf(originX, originY), "u_layerScale" to scale) + values

    /** A doc-px rectangle → this layer's px, padded (doc px) and clamped. */
    private fun layerRect(r: DoubleArray?, padDoc: Double = 0.0): FloatArray? {
        if (r == null) return null
        val pad = padDoc * scale
        val x0 = max(0.0, floor((r[0] - originX) * scale - pad)); val y0 = max(0.0, floor((r[1] - originY) * scale - pad))
        val x1 = min(w.toDouble(), ceil((r[2] - originX) * scale + pad)); val y1 = min(h.toDouble(), ceil((r[3] - originY) * scale + pad))
        return if (x1 > x0 && y1 > y0) floatArrayOf(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat()) else null
    }

    /** The area the current stroke touched (layer px), for undo snapshots and look tiles. */
    var strokeRect: FloatArray? = null; private set

    private fun touch(r: FloatArray?) {
        if (r == null) return
        val u = strokeRect
        strokeRect = if (u == null) r.copyOf() else floatArrayOf(min(u[0], r[0]), min(u[1], r[1]), max(u[2], r[2]), max(u[3], r[3]))
        val d = dirtySinceLook
        dirtySinceLook = if (d == null) r.copyOf() else floatArrayOf(min(d[0], r[0]), min(d[1], r[1]), max(d[2], r[2]), max(d[3], r[3]))
    }

    /** Everything touched since the last [takeDirty] (look tiles to re-render, float tiles to store). */
    private var dirtySinceLook: FloatArray? = null
    fun takeDirty(): FloatArray? = dirtySinceLook.also { dirtySinceLook = null }

    /** The stores the passes since the last [takeDirtyStores] wrote: what a write-back copies (stores are lazy, M5.3c). */
    private val stores = LinkedHashSet<String>()
    fun takeDirtyStores(): Set<String> = LinkedHashSet(stores).also { stores.clear() }

    // ---- the window (M5.3c): where this engine's textures sit on the document, and what is loaded into them ----

    /**
     * Moves the window to document px ([originX], [originY]) and empties it: the caller loads the stores for the new
     * place. Water stops (what was outside the old place stays asleep in the stores until a window loads it again).
     */
    fun moveTo(originX: Float, originY: Float) {
        this.originX = originX; this.originY = originY
        bakedPaper = null
        clearAll()
        if (lookFbo != 0) clearFbo(lookFbo)
        stores.clear(); dirtySinceLook = null; strokeRect = null
    }

    /** The textures a store loads into (both water buffers, so the next substep reads what was loaded). */
    fun loadTargets(store: String): List<Int> = when (store) {
        "p0" -> listOf(state[0].p0); "p1" -> listOf(state[0].p1); "paper" -> listOf(state[0].paper)
        "w0" -> { ensureWet(); wet!!.map { it.w0 } }
        "w1" -> { ensureWet(); wet!!.map { it.w1 } }
        else -> throw IllegalArgumentException("unknown media store $store")
    }

    /** The texture a store's current state is read from, for a write-back. Null for water that was never made. */
    fun current(store: String): Int? = when (store) {
        "p0" -> state[0].p0; "p1" -> state[0].p1; "paper" -> state[0].paper
        "w0" -> wet?.get(wc)?.w0; "w1" -> wet?.get(wc)?.w1
        else -> throw IllegalArgumentException("unknown media store $store")
    }

    /** The look texture (window px), made on first use: the window loads stored look tiles into it and writes it back. */
    fun lookTexture(): Int {
        if (lookTex == 0) {
            lookTex = MediaTex.make(w, h, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_NEAREST)
            lookFbo = MediaTex.fbo(lookTex)
            clearFbo(lookFbo)
        }
        return lookTex
    }

    /** Stops the water where it is (undo while wet, the Lead's rule): the state stays, nothing runs until [resumeWater]. */
    fun stopWater() {
        wetRect = null; paintRect = null; wetIdle = 0.0
        wet?.let { ws -> for (x in ws) { val f = MediaTex.fbo(x.flux); clearFbo(f); MediaTex.deleteFbo(f) } }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Lets loaded water run again over [rect] (window px): a reopened wet drawing, or a new stroke into sleeping water. */
    fun resumeWater(rect: FloatArray) {
        ensureWet()
        wetRect = union(wetRect, rect); paintRect = union(paintRect, rect); wetIdle = 0.0
    }

    fun beginStroke() { strokeRect = null }
    fun endStroke(): FloatArray? = strokeRect.also { strokeRect = null }

    // ---- paper ----
    fun bakePaper(paper: MediaPaperGl) {
        ensureBake()
        if (bakedPaper === paper) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bakeFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDisable(GLES30.GL_BLEND)
        progBake.use(common(paper.uniforms() + mapOf("u_cellPx" to floatArrayOf(1f, 1f), "u_rect" to floatArrayOf(0f, 0f, w.toFloat(), h.toFloat()),
            "u_targetSize" to floatArrayOf(w.toFloat(), h.toFloat()))))
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        bakedPaper = paper
    }

    private fun contactTexture(paper: MediaPaperGl): Int {
        val lut = paper.stats.contactLut()
        if (lutSrc === lut) return lutTex
        if (lutTex == 0) lutTex = MediaTex.make(lut.a.size, 1, GLES30.GL_R16F, GLES30.GL_RED, GLES30.GL_FLOAT, GLES30.GL_LINEAR)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F, lut.a.size, 1, 0, GLES30.GL_RED, GLES30.GL_FLOAT, MediaTex.floats(lut.a))
        lutSrc = lut
        return lutTex
    }

    private fun copyRect(src: State, dstFbo: Int, rect: FloatArray) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dstFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDisable(GLES30.GL_BLEND)
        progCopy.use(common(mapOf("u_a" to Tex(src.p0), "u_b" to Tex(src.p1), "u_c" to Tex(src.paper),
            "u_targetSize" to floatArrayOf(w.toFloat(), h.toFloat()), "u_rect" to rect)))
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    // ---- dry media (pencil) ----
    fun dryFrame(batch: DabBatch, travelX: Double, travelY: Double, paper: MediaPaperGl, mat: StickMaterial, pxPerMm: Double) {
        if (batch.count == 0) return
        ensureDry()
        val cur = state[0]
        val nxt = state[1]
        val rect = layerRect(batch.dirty, pxPerMm * mat.smearMm + 2) ?: return
        touch(rect)
        stores += "paper"
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, deltaFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        val t = paper.stats.toothMm
        progDab.use(common(paper.uniforms() + mapOf(
            "u_crush" to Tex(cur.paper), "u_contactLut" to Tex(contactTexture(paper)), "u_lutMaxMm" to paper.stats.contactLut().maxMm,
            "u_targetSize" to floatArrayOf(w.toFloat(), h.toFloat()), "u_pxPerMm" to pxPerMm, "u_toothMm" to t,
            "u_dirStrength" to mat.dirStrength, "u_dustRate" to mat.dustRate, "u_clump" to mat.clump,
            "u_pileRange" to doubleArrayOf(mat.pileRangeLo, mat.pileRangeHi), "u_crushStart" to mat.crushStart * t,
            "u_conform" to mat.conform, "u_transferExp" to mat.transferExp, "u_leadSoft" to mat.leadSoft, "u_plateau" to mat.plateau,
        )))
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dabBuf)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, batch.dabs.size * 4, MediaTex.floats(batch.dabs), GLES30.GL_STREAM_DRAW)
        GLES30.glBindVertexArray(dabVao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, batch.count)
        GLES30.glDisable(GLES30.GL_BLEND)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, nxt.fbo)
        progApply.use(common(mapOf(
            "u_p0" to Tex(cur.p0), "u_p1" to Tex(cur.p1), "u_paperState" to Tex(cur.paper), "u_delta" to Tex(delta),
            "u_targetSize" to floatArrayOf(w.toFloat(), h.toFloat()), "u_rect" to rect,
            "u_capMm" to mat.capMm, "u_abrasion" to mat.abrasion, "u_flakeR" to mat.flakeR, "u_crushRate" to mat.crushRate,
            "u_crushMax" to mat.crushMax, "u_smear" to mat.smear,
            "u_smearPx" to doubleArrayOf(travelX * mat.smearMm * pxPerMm * scale, travelY * mat.smearMm * pxPerMm * scale),
        )))
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        copyRect(nxt, cur.fbo, rect)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, deltaFbo)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(floor(rect[0]).toInt(), floor(rect[1]).toInt(), ceil(rect[2] - rect[0]).toInt() + 1, ceil(rect[3] - rect[1]).toInt() + 1)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }

    // ---- paste media (oil, knives) ----
    /** Fill every brush cell with fresh paint (a loading tray; never sampled from the canvas: patent guard). */
    fun reloadBrush(paint: OpaquePaint, brushDef: PasteBrush, seed: Int = 1, belly: OpaquePaint? = null) {
        if (brushDef.clean) { clearBrush(); return }
        val cap = cellCap(brushDef)
        val a = FloatArray(PASTE_LANES * PASTE_DEPTH * 4)
        val b = FloatArray(PASTE_LANES * PASTE_DEPTH * 4)
        var x = (seed.toLong() * 2654435761L) and 0xFFFFFFFFL
        fun rnd(): Double { x = (x * 1664525L + 1013904223L) and 0xFFFFFFFFL; return x.toDouble() / 4294967296.0 }
        val lane = DoubleArray(PASTE_LANES)
        var walk = 0.0
        for (i in 0 until PASTE_LANES) { walk = walk * 0.6 + (rnd() - 0.5); lane[i] = walk }
        val laneTone = DoubleArray(PASTE_LANES)
        val laneHue = Array(PASTE_LANES) { DoubleArray(3) }
        var tw = 0.0
        for (i in 0 until PASTE_LANES) { tw = tw * 0.7 + (rnd() - 0.5); laneTone[i] = tw; laneHue[i] = doubleArrayOf(rnd() - 0.5, rnd() - 0.5, rnd() - 0.5) }
        fun ss(e0: Double, e1: Double, v: Double): Double { val t = min(1.0, max(0.0, (v - e0) / (e1 - e0))); return t * t * (3 - 2 * t) }
        for (j in 0 until PASTE_DEPTH) for (i in 0 until PASTE_LANES) {
            val amt = cap * max(0.15, 1 + 0.55 * lane[i] + 0.15 * (rnd() - 0.5)) * (0.8 + 0.4 * j / (PASTE_DEPTH - 1))
            // Loading tray: round = tip one colour, belly the other; flat = side to side.
            val wgt = if (belly == null) 0.0 else if (brushDef.shape == 0) ss(1.5, 3.5, j.toDouble()) else ss(PASTE_LANES * 0.35, PASTE_LANES * 0.65, i.toDouble())
            val shade = 1 + 0.18 * laneTone[i]
            val o = (j * PASTE_LANES + i) * 4
            for (c in 0 until 3) {
                val k = (paint.k[c] * (1 - wgt) + (belly?.k?.get(c)?.times(wgt) ?: 0.0)) * shade * (1 + 0.06 * laneHue[i][c])
                a[o + c] = (k * amt).toFloat()
            }
            a[o + 3] = amt.toFloat()
            b[o] = ((paint.s * (1 - wgt) + (belly?.s?.times(wgt) ?: 0.0)) * amt).toFloat()
        }
        for (t in brush) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.b0)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, PASTE_LANES, PASTE_DEPTH, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, MediaTex.floats(a))
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.b1)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, PASTE_LANES, PASTE_DEPTH, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, MediaTex.floats(b))
        }
    }

    fun clearBrush() {
        for (t in brush) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.fbo)
            GLES30.glClearBufferfv(GLES30.GL_COLOR, 0, floatArrayOf(0f, 0f, 0f, 0f), 0)
            GLES30.glClearBufferfv(GLES30.GL_COLOR, 1, floatArrayOf(0f, 0f, 0f, 0f), 0)
        }
    }

    /** One step of a paste brush: the brush trades with the canvas under it, both sides from the same state. */
    fun pasteStep(st: PasteStep, b: PasteBrush, paper: MediaPaperGl, pxPerMm: Double, seed: Int) {
        bakePaper(paper)
        val cap = cellCap(b)
        val t = paper.stats.toothMm
        val u = mapOf<String, Any?>(
            "u_center" to doubleArrayOf(st.x, st.y), "u_wDir" to doubleArrayOf(st.wDirX, st.wDirY), "u_lDir" to doubleArrayOf(st.lDirX, st.lDirY),
            "u_halfW" to st.halfW, "u_len" to st.len, "u_lenMax" to st.lenMax, "u_shape" to b.shape, "u_pressure" to st.pressure,
            "u_thick" to (st.thick ?: b.thickMm), "u_scrape" to b.scrape, "u_fingers" to (st.fingers ?: b.fingers ?: 1.0),
            "u_rag" to (b.rag ?: 1.0), "u_arc" to (b.arc ?: 0.18), "u_sparse" to (b.sparse ?: 0.0),
            "u_blade" to (if (b.blade) 1 else 0), "u_fillDips" to (b.fillDips ?: 1.0), "u_bladeLoad" to (if (st.loadMode) 1 else 0),
            "u_bladeDir" to doubleArrayOf(st.bladeDirX, st.bladeDirY), "u_travel" to doubleArrayOf(st.travelX, st.travelY),
            "u_bladeLen" to (st.bladeLen ?: b.bladeLenMm.takeIf { it != 0.0 } ?: 1.0),
            "u_bladeHalfW" to (st.bladeHalf ?: b.bladeHalfMm.takeIf { it != 0.0 } ?: 1.0),
            "u_bladeH0" to (st.bladeH0 ?: 0.0), "u_bladeTan" to (st.bladeTan ?: 0.0), "u_bladeBead" to b.bead,
            "u_bladePressMm" to (b.pressIn ?: 0.6) * t * (st.pressK ?: (st.pressure * st.pressure)), "u_bladeFilm" to (b.film ?: 0.004),
            "u_hairDepth" to b.hairDepth, "u_ridge" to b.ridge, "u_rate" to b.rate, "u_mix" to b.mix, "u_swap" to b.swap,
            "u_bow" to b.bow, "u_lump" to b.lump, "u_rigid" to b.rigid, "u_slideMm" to st.slideMm, "u_cellCap" to cap,
            "u_seed" to seed, "u_pxPerMm" to pxPerMm, "u_toothMm" to t,
            "u_paperBake" to Tex(bakeTex), "u_p0" to Tex(state[0].p0), "u_p1" to Tex(state[0].p1),
        )
        val cur = brush[bc]
        val nxt = brush[1 - bc]
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(quadVao)
        // Brush side first (reads the canvas BEFORE this step), into the other brush buffer.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, nxt.fbo)
        GLES30.glViewport(0, 0, PASTE_LANES, PASTE_DEPTH)
        progPasteBrush.use(common(u + mapOf("u_brush0" to Tex(cur.b0), "u_brush1" to Tex(cur.b1), "u_wick" to b.wick, "u_active" to 1,
            "u_layerSize" to floatArrayOf(w.toFloat(), h.toFloat()), "u_rect" to floatArrayOf(0f, 0f, PASTE_LANES.toFloat(), PASTE_DEPTH.toFloat()),
            "u_targetSize" to floatArrayOf(PASTE_LANES.toFloat(), PASTE_DEPTH.toFloat()))))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        // Canvas side, from the same (old) brush cells, over the footprint's rectangle.
        val r = (if (b.blade) (st.bladeLen ?: b.bladeLenMm) + b.bladeHalfMm + 1.5 else hypot(st.halfW, st.lenMax) + 0.3) * pxPerMm
        val rect = layerRect(doubleArrayOf(st.x - r, st.y - r, st.x + r, st.y + r), 2.0)
        if (rect != null) {
            touch(rect)
            stores += "p0"; stores += "p1"
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pFbo1)
            GLES30.glViewport(0, 0, w, h)
            progPasteDab.use(common(u + mapOf("u_brush0" to Tex(cur.b0), "u_brush1" to Tex(cur.b1),
                "u_targetSize" to floatArrayOf(w.toFloat(), h.toFloat()), "u_rect" to rect)))
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            copyRect(state[1], pFbo0, rect)
        }
        bc = 1 - bc
    }

    // ---- wet media ----
    /** One frame of water: new dabs (if any) go into the brush input, then the water runs [WetConstants.substeps] times. */
    fun wetFrame(batch: DabBatch?, paper: MediaPaperGl, pxPerMm: Double, wc0: WetConstants = WetConstants(), substeps: Int? = null,
                 evapOverride: Double? = null, maxWetSeconds: Double = 240.0) {
        ensureWet()
        bakePaper(paper)
        val ws = wet!!
        var useInput = false
        if (batch != null && batch.count > 0) {
            val r = layerRect(batch.dirty, 48.0)
            if (r != null) { wetRect = union(wetRect, r); paintRect = union(paintRect, r) }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, inFbo)
            GLES30.glViewport(0, 0, w, h)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
            progWetDab.use(common(mapOf("u_w0" to Tex(ws[wc].w0), "u_paperBake" to Tex(bakeTex),
                "u_targetSize" to floatArrayOf(w.toFloat(), h.toFloat()), "u_pxPerMm" to pxPerMm, "u_fullMm" to wc0.fullMm)))
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dabBuf)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, batch.dabs.size * 4, MediaTex.floats(batch.dabs), GLES30.GL_STREAM_DRAW)
            GLES30.glBindVertexArray(dabVao)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, batch.count)
            GLES30.glDisable(GLES30.GL_BLEND)
            useInput = true
            wetIdle = 0.0
        }
        val rect0 = wetRect ?: return
        // On a tilted paper running water can leave the rectangle: it grows downhill as fast as water can run, never
        // further than a long drip past the paint, nor past this window (WetSpread, the tested cap).
        val steps = substeps ?: wc0.substeps
        val rect = WetSpread.grow(rect0, paintRect, slope[0], slope[1], steps, 1 / (pxPerMm * scale), w, h)
        wetRect = rect
        stores += WATER_WRITES
        touch(rect)
        wetSince = union(wetSince, rect)
        val beadCells = wc0.beadMm * pxPerMm * scale
        val uni = HashMap<String, Any?>(wetUniforms(paper.stats, wc0))
        uni["u_slope"] = slope
        uni["u_cellMm"] = 1 / (pxPerMm * scale)
        uni["u_beadCells"] = beadCells
        if (evapOverride != null) uni["u_evap"] = evapOverride
        val tilted = hypot(slope[0].toDouble(), slope[1].toDouble()) > 1e-4
        GLES30.glDisable(GLES30.GL_BLEND)
        for (k in 0 until steps) {
            val a = ws[wc]
            val bi = 1 - wc
            val b = ws[bi]
            GLES30.glBindVertexArray(quadVao)
            if (tilted) beadPasses(a.w0, rect, beadCells)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fluxFbo[bi])
            GLES30.glViewport(0, 0, w, h)
            progFlux.use(common(uni + mapOf("u_bead" to Tex(bead[0]), "u_p0" to Tex(state[0].p0), "u_w0" to Tex(a.w0), "u_flux" to Tex(a.flux),
                "u_paperBake" to Tex(bakeTex), "u_fluidBake" to Tex(fluidBake), "u_waterBake" to Tex(waterBake), "u_rect" to rect,
                "u_targetSize" to floatArrayOf(w.toFloat(), h.toFloat()))))
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, updFbo[bi])
            progUpdate.use(common(uni + mapOf("u_w0" to Tex(a.w0), "u_w1" to Tex(a.w1), "u_flux" to Tex(b.flux), "u_run" to Tex(run),
                "u_p0" to Tex(state[0].p0), "u_p1" to Tex(state[0].p1), "u_paperBake" to Tex(bakeTex), "u_waterBake" to Tex(waterBake),
                "u_fluidBake" to Tex(fluidBake), "u_in0" to Tex(in0), "u_in1" to Tex(in1), "u_useInput" to (if (k == 0 && useInput) 1 else 0),
                "u_rect" to rect, "u_targetSize" to floatArrayOf(w.toFloat(), h.toFloat()))))
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            copyRect(state[1], pFbo0, rect)
            wc = bi
        }
        if (useInput) clearFbo(inFbo)
        wetIdle += steps * wc0.dt
        if (wetIdle > maxWetSeconds) dryNow(paper, pxPerMm)
    }

    // The running bead's field: 2×2 average of the water, then a Gaussian along x and y (jb_wet_bead.frag).
    private fun beadPasses(w0: Int, rect: FloatArray, beadCells: Double) {
        val sigma = min(4.8, max(1.0, 0.25 * beadCells))
        val r = min(12, ceil(2.5 * sigma).toInt())
        val m = 3 + 2 * r
        val hr = floatArrayOf(max(0f, floor(rect[0] / 2) - m), max(0f, floor(rect[1] / 2) - m),
            min(beadW.toFloat(), ceil(rect[2] / 2) + m), min(beadH.toFloat(), ceil(rect[3] / 2) + m))
        fun pass(fbo: Int, pass: Int, src: Int, axisX: Float, axisY: Float) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glViewport(0, 0, beadW, beadH)
            progBead.use(common(mapOf("u_rect" to hr, "u_targetSize" to floatArrayOf(beadW.toFloat(), beadH.toFloat()), "u_sigma" to sigma,
                "u_pass" to pass, "u_w0" to Tex(w0), "u_src" to Tex(src), "u_axis" to floatArrayOf(axisX, axisY))))
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        pass(beadFbo[0], 0, bead[1], 1f, 0f)
        pass(beadFbo[1], 1, bead[0], 1f, 0f)
        pass(beadFbo[0], 2, bead[1], 0f, 1f)
    }

    /** Let everything dry where it lies (the Dry button, and the end of a long idle). */
    fun dryNow(paper: MediaPaperGl, pxPerMm: Double) {
        if (wet == null || wetRect == null) return
        wetFrame(null, paper, pxPerMm, substeps = 1, evapOverride = 1e4, maxWetSeconds = 1e9)
        for (f in wetCopyFbo) clearFbo(f)
        wetRect = null
        paintRect = null
    }

    // ---- the look: what the app's layer stack composites ----
    /** Render the look (premultiplied RGBA8, paper removed) for [rect] (layer px) into the returned texture. */
    fun renderLook(rect: FloatArray, paper: MediaPaperGl, look: MediaLook, pxPerMm: Double): Int {
        lookTexture()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, lookFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(floor(rect[0]).toInt(), floor(rect[1]).toInt(), ceil(rect[2] - rect[0]).toInt(), ceil(rect[3] - rect[1]).toInt())
        GLES30.glDisable(GLES30.GL_BLEND)
        val ws = wet
        // gl_FragCoord is layer px: docPx = (frag − pan) / zoom = origin + frag / scale.
        progRender.use(common(paper.uniforms() + mapOf(
            "u_p0" to Tex(state[0].p0), "u_p1" to Tex(state[0].p1), "u_paperState" to Tex(state[0].paper),
            "u_paperBake" to Tex(if (bakeTex != 0) bakeTex else emptyTex()),
            "u_w0" to Tex(ws?.get(wc)?.w0 ?: emptyTex()), "u_w1" to Tex(ws?.get(wc)?.w1 ?: emptyTex()),
            "u_targetSize" to floatArrayOf(w.toFloat(), h.toFloat()), "u_pan" to floatArrayOf(-originX * scale, -originY * scale),
            "u_zoom" to scale, "u_pxPerMm" to pxPerMm, "u_toothMm" to paper.stats.toothMm, "u_paperColor" to look.paperColor,
            "u_lamp" to look.lamp, "u_relief" to 0f, "u_sheen" to look.sheen, "u_capMm" to look.capMm, "u_mode" to 0,
            "u_impasto" to look.impasto, "u_look" to 1f,
        )))
        GLES30.glBindVertexArray(emptyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        return lookTex
    }

    // ---- state in and out (tile sync, undo through the app's stores) ----
    /** The current state textures by store suffix ("p0", "p1", "paper"; "w0", "w1" while water exists). */
    fun stateTextures(): Map<String, Int> {
        val m = linkedMapOf("p0" to state[0].p0, "p1" to state[0].p1, "paper" to state[0].paper)
        wet?.let { m["w0"] = it[wc].w0; m["w1"] = it[wc].w1 }
        return m
    }

    /** Copy a rectangle of [src] (any texture of the same format) into [dst] (blit, exact). */
    fun blit(src: Int, sx0: Int, sy0: Int, sx1: Int, sy1: Int, dst: Int, dx0: Int, dy0: Int, dx1: Int, dy1: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, readFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, src, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, drawFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_DRAW_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, dst, 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0), 0)
        GLES30.glBlitFramebuffer(sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
    }

    /** After state was written from outside (undo, load): water there starts again from rest. */
    fun stateRestored(rect: FloatArray) {
        wet?.let { ws ->
            for (x in ws) {
                val f = MediaTex.fbo(x.flux)
                GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
                GLES30.glScissor(rect[0].toInt(), rect[1].toInt(), (rect[2] - rect[0]).toInt(), (rect[3] - rect[1]).toInt())
                GLES30.glClearColor(0f, 0f, 0f, 0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
                MediaTex.deleteFbo(f)
            }
            // keep both water buffers in step (the next substep reads the current one)
            blit(ws[wc].w0, rect[0].toInt(), rect[1].toInt(), rect[2].toInt(), rect[3].toInt(), ws[1 - wc].w0, rect[0].toInt(), rect[1].toInt(), rect[2].toInt(), rect[3].toInt())
            blit(ws[wc].w1, rect[0].toInt(), rect[1].toInt(), rect[2].toInt(), rect[3].toInt(), ws[1 - wc].w1, rect[0].toInt(), rect[1].toInt(), rect[2].toInt(), rect[3].toInt())
        }
    }

    fun release() {
        for (p in listOf(progDab, progApply, progCopy, progRender, progBake, progWetDab, progFlux, progUpdate, progPasteDab, progPasteBrush, progBead)) p.release()
        val tex = ArrayList<Int>()
        for (s in state) tex += listOf(s.p0, s.p1, s.paper)
        for (b in brush) tex += listOf(b.b0, b.b1)
        wet?.forEach { tex += listOf(it.w0, it.w1, it.flux) }
        tex += listOf(delta, bakeTex, fluidBake, waterBake, run, in0, in1, lutTex, lookTex, empty) + bead.toList()
        tex.filter { it != 0 }.toIntArray().let { if (it.isNotEmpty()) MediaTex.deleteTex(*it) }
        val fbos = ArrayList<Int>()
        for (s in state) fbos += s.fbo
        for (b in brush) fbos += b.fbo
        fbos += listOf(pFbo0, pFbo1, deltaFbo, bakeFbo, inFbo, lookFbo, readFbo, drawFbo) + beadFbo.toList() + fluxFbo.toList() + updFbo.toList() + wetCopyFbo.toList()
        fbos.filter { it != 0 }.toIntArray().let { if (it.isNotEmpty()) MediaTex.deleteFbo(*it) }
        GLES30.glDeleteBuffers(2, intArrayOf(quadBuf, dabBuf), 0)
        GLES30.glDeleteVertexArrays(3, intArrayOf(quadVao, dabVao, emptyVao), 0)
    }

    private companion object {
        val WATER_WRITES = listOf("p0", "p1", "w0", "w1")
    }

    private fun union(a: FloatArray?, b: FloatArray): FloatArray =
        if (a == null) b.copyOf() else floatArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
}
