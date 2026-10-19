package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.ShaderLibrary
import cc.joycreator.joybrush.core.media.MediaAtlasDraw
import cc.joycreator.joybrush.core.media.MediaAtlasPlacement
import cc.joycreator.joybrush.core.media.DabBatch
import cc.joycreator.joybrush.core.media.MediaPaper
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.media.OpaquePaint
import cc.joycreator.joybrush.core.media.PASTE_DEPTH
import cc.joycreator.joybrush.core.media.PASTE_LANES
import cc.joycreator.joybrush.core.media.PasteBrush
import cc.joycreator.joybrush.core.media.PasteFootprint
import cc.joycreator.joybrush.core.media.PasteStep
import cc.joycreator.joybrush.core.media.StickMaterial
import cc.joycreator.joybrush.core.media.WetConstants
import cc.joycreator.joybrush.core.media.WetSpread
import cc.joycreator.joybrush.core.media.cellCap
import cc.joycreator.joybrush.core.media.wetUniforms
import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The paper as the media passes read it: the document surface the app already binds for its brushes (the same
 * jb_paper.glsl uniforms), plus the media statistics (contact table, height percentiles) from [MediaPaper]. Its uniforms
 * are written by [MediaUniforms.paper].
 */
class MediaPaperGl(
    val stats: MediaPaper,
    val surface: Tex, val texelPx: Float, val size: Float, val hexTexels: Float, val slopeRange: Float, val rotatable: Boolean,
    val fluid: Tex, val fluidTexelPx: Float, val fluidSize: Float, val fluidHexTexels: Float,
)

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
 * The working textures are a WINDOW ([w] Ã— [h] px at document [originX], [originY]; layerPx = (docPx âˆ’ origin) Â· scale),
 * never the whole canvas (contract point 8). [MediaWindow] places it, loads the layer's stores into it ([loadTargets],
 * [lookTexture]) and writes back what the passes wrote ([takeDirtyStores], [current]) through the app's stores
 * (#p0 #p1 #paper #w0 #w1 + the RGBA8 look; MEDIA_ENGINE_PLAN M5.3c). GL thread only.
 */
class MediaLayerEngine(private val shaders: ShaderLibrary = ShaderLibrary(), val atlasMode: Boolean = MediaAtlasGl.enabled,
                       private val atlasBroken: Boolean = false) {
    /** Media Atlas v2 B1: the per-slot driver when this engine draws through the atlas (identity layout); null = the window. */
    private var atlas: MediaAtlasGl? = null
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
    private lateinit var progEraseDab: MediaProgram
    private lateinit var progErase: MediaProgram
    private lateinit var progActivity: MediaProgram

    /**
     * Every media program, in compile order: field setter, vertex, fragment, label, and whether it draws into the paste
     * brush's cells (not layer-shaped: JB_PLAIN_TARGET in atlas mode). One table, so [compileNext] can never skip one.
     */
    private val programTable: List<ProgramSpec> = listOf(
        ProgramSpec({ progDab = it }, "jb_dry_dab.vert", "jb_dry_dab.frag", "media dry dab"),
        ProgramSpec({ progApply = it }, "jb_media_quad.vert", "jb_media_apply.frag", "media apply", atlasDefs = ATLAS_NBR),
        ProgramSpec({ progCopy = it }, "jb_media_quad.vert", "jb_media_copy.frag", "media copy", atlasDefs = ATLAS_NBR),
        ProgramSpec({ progRender = it }, "jb_media_screen.vert", "jb_media_render.frag", "media render", atlasDefs = ATLAS_NBR),
        ProgramSpec({ progBake = it }, "jb_media_quad.vert", "jb_media_bake.frag", "media bake", atlasDefs = ATLAS_NBR),
        ProgramSpec({ progWetDab = it }, "jb_wet_dab.vert", "jb_wet_dab.frag", "media wet dab"),
        ProgramSpec({ progFlux = it }, "jb_media_quad.vert", "jb_wet_flux.frag", "media wet flux", atlasDefs = ATLAS_NBR),
        ProgramSpec({ progUpdate = it }, "jb_media_quad.vert", "jb_wet_update.frag", "media wet update", atlasDefs = ATLAS_NBR),
        ProgramSpec({ progPasteDab = it }, "jb_media_quad.vert", "jb_paste_dab.frag", "media paste dab", atlasDefs = ATLAS_NBR),
        ProgramSpec({ progPasteBrush = it }, "jb_media_quad.vert", "jb_paste_brush.frag", "media paste brush", plain = true, atlasDefs = ATLAS_NBR5),
        ProgramSpec({ progBead = it }, "jb_media_quad.vert", "jb_wet_bead.frag", "media wet bead", atlasDefs = ATLAS_NBR),
        ProgramSpec({ progEraseDab = it }, "jb_media_erase_dab.vert", "jb_media_erase_dab.frag", "media erase dab"),
        ProgramSpec({ progErase = it }, "jb_media_quad.vert", "jb_media_erase_apply.frag", "media erase", atlasDefs = ATLAS_NBR),
        ProgramSpec({ progActivity = it }, "jb_media_screen.vert", "jb_wet_activity.frag", "media wet activity"),
    )
    private class ProgramSpec(val set: (MediaProgram) -> Unit, val vert: String, val frag: String, val label: String, val plain: Boolean = false,
                              /** More defines for the atlas build only (the look's JB_ATLAS_NBR). */ val atlasDefs: List<String> = emptyList())

    /** The programs compiled so far, in [programTable]'s order (what [releasePrograms] deletes). */
    private val programs = ArrayList<MediaProgram>()

    /**
     * Compiles the next media program; false once all are compiled. A canvas calls it once per idle moment after a drawing
     * opens (MediaCanvas.prewarmStep), so the first pencil, watercolour or oil stroke no longer stalls for all of them at once
     * (the device matrix measured a 1012 ms first frame on the Note 9). [init] compiles whatever is left. No textures here.
     */
    fun compileNext(): Boolean {
        val spec = programTable.getOrNull(programs.size) ?: return false
        val defs = if (atlasMode) listOf("JB_ATLAS") + spec.atlasDefs else emptyList()
        val made = MediaProgram(shaders, "media/${spec.vert}", "media/${spec.frag}", spec.label, if (spec.plain && atlasMode) defs + "JB_PLAIN_TARGET" else defs)
        programs += made
        spec.set(made)
        return programs.size < programTable.size
    }

    /** Deletes the programs compiled so far (an engine compiled ahead and let go before any media stroke, or [release]). */
    fun releasePrograms() { programs.forEach { it.release() }; programs.clear() }
    private var eraseWetFbo = IntArray(0)

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
    /** True while the brush input may hold dabs outside the last scissored area (a dab drawn with no rectangle to clip to). */
    private var inFull = false
    private var wc = 0
    private var lutTex = 0
    private var lutSrc: Any? = null
    private var lookTex = 0; private var lookFbo = 0
    /** The pixels the media lies on (JB-2.40 Â§Q1): RGBA8 premultiplied, window px. Made with the state. */
    private var groundTex = 0; private var groundFbo = 0
    private var empty = 0

    private var quadBuf = 0; private var quadVao = 0; private var dabBuf = 0; private var dabVao = 0; private var emptyVao = 0
    private var readFbo = 0; private var drawFbo = 0

    // The wet frame's flattened constants (one per frame, shared by every substep and both wet programs).
    private val wetFrameUniforms = WetFrameUniforms()

    // Reusable GL-thread-only direct upload buffer for the per-frame dab batches (dry/erase/wet).
    private val uploadBuffer = FloatUploadBuffer()

    /** Where water is being simulated (layer px), null = everything dry. */
    var wetRect: FloatArray? = null; private set
    private var paintRect: FloatArray? = null
    private var wetIdle = 0.0
    private var wetSince: FloatArray? = null
    /** The paper's tilt as a downhill slope (sin of the angle), layer space; [0, 0] = flat. */
    var slope = floatArrayOf(0f, 0f)

    val wetActive get() = wetRect != null
    /** True once the water's textures exist (the memory report then lists them). */
    val hasWaterTextures get() = wet != null

    fun init(width: Int, height: Int, originX: Float = 0f, originY: Float = 0f, scale: Float = 1f) {
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        check("EXT_color_buffer_float" in ext) { "media layers need EXT_color_buffer_float" }
        w = width; h = height; this.originX = originX; this.originY = originY; this.scale = scale
        // Atlas mode builds every media program with JB_ATLAS; the paste brush's cells are not layer-shaped (JB_PLAIN_TARGET).
        if (atlasMode) require(width % MediaAtlasDraw.SIDE == 0 && height % MediaAtlasDraw.SIDE == 0) { "an atlas window is whole slots" }
        while (compileNext()) Unit   // whatever was not compiled ahead (MediaCanvas.prewarmStep)
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
        if (atlasMode) atlas = MediaAtlasGl(w / MediaAtlasDraw.SIDE, h / MediaAtlasDraw.SIDE, quadBuf, atlasBroken)
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
        groundTex = MediaTex.make(w, h, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_NEAREST)
        groundFbo = MediaTex.fbo(groundTex)
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
        tex += listOf(delta, bakeTex, fluidBake, waterBake, run, in0, in1, lookTex, groundTex) + bead.toList()
        tex.filter { it != 0 }.toIntArray().let { if (it.isNotEmpty()) MediaTex.deleteTex(*it) }
        val fbos = ArrayList<Int>()
        for (s in state) fbos += s.fbo
        fbos += listOf(pFbo0, pFbo1, deltaFbo, bakeFbo, inFbo, lookFbo, groundFbo) + beadFbo.toList() + fluxFbo.toList() + updFbo.toList() +
            wetCopyFbo.toList() + eraseWetFbo.toList()
        fbos.filter { it != 0 }.toIntArray().let { if (it.isNotEmpty()) MediaTex.deleteFbo(*it) }
        eraseWetFbo = IntArray(0)
        groundTex = 0; groundFbo = 0
        delta = 0; deltaFbo = 0; bakeTex = 0; fluidBake = 0; waterBake = 0; bakeFbo = 0; bakedPaper = null
        wet = null; run = 0; in0 = 0; in1 = 0; inFbo = 0; inFull = false; lookTex = 0; lookFbo = 0
        bead = IntArray(0); beadFbo = IntArray(0); fluxFbo = IntArray(0); updFbo = IntArray(0); wetCopyFbo = IntArray(0)
        pFbo0 = 0; pFbo1 = 0
        wetRect = null; paintRect = null; wetSince = null; stores.clear(); dirtySinceLook = null; strokeRect = null
        uploadBuffer.reset()
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
        // Only .r of the fluid and water bakes is ever read (jb_wet_flux.frag, jb_wet_update.frag: texelFetch(...).r), so they
        // are one channel; the same value is rounded to the same 8-bit / half float, the other channels were never read.
        fluidBake = MediaTex.make(w, h, GLES30.GL_R8, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_NEAREST)
        waterBake = MediaTex.make(w, h, GLES30.GL_R16F, GLES30.GL_RED, GLES30.GL_HALF_FLOAT)
        bakeFbo = MediaTex.fbo(bakeTex, fluidBake, waterBake)
    }

    private fun ensureWet() {
        if (wet != null) return
        ensureBake()
        val ws = Array(2) { Wet(MediaTex.full(w, h), MediaTex.full(w, h), MediaTex.make(w, h)) }
        wet = ws
        // The running water's outflow is read as .xy only (jb_wet_update.frag), so two half-float channels.
        run = MediaTex.make(w, h, GLES30.GL_RG16F, GLES30.GL_RG, GLES30.GL_HALF_FLOAT)
        beadW = (w + 1) / 2; beadH = (h + 1) / 2
        bead = IntArray(2) { MediaTex.make(beadW, beadH) }
        beadFbo = IntArray(2) { MediaTex.fbo(bead[it]) }
        fluxFbo = IntArray(2) { MediaTex.fbo(ws[it].flux, run) }
        // One per (water buffer written, state buffer written): the paint planes ping-pong with the substeps (see wetFrame).
        updFbo = IntArray(4) { MediaTex.fbo(ws[it shr 1].w0, ws[it shr 1].w1, state[it and 1].p0, state[it and 1].p1) }
        wetCopyFbo = IntArray(2) { if (it == 0) MediaTex.fbo(ws[0].w0, ws[0].w1, ws[0].flux, run) else MediaTex.fbo(ws[1].w0, ws[1].w1, ws[1].flux) }
        in0 = MediaTex.make(w, h); in1 = MediaTex.make(w, h); inFbo = MediaTex.fbo(in0, in1)
        for (f in wetCopyFbo) clearFbo(f)
        clearFbo(inFbo)
        inFull = false
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
        if (groundFbo != 0) clearFbo(groundFbo)
        if (deltaFbo != 0) clearFbo(deltaFbo)
        if (wet != null) { for (f in wetCopyFbo) clearFbo(f); clearFbo(inFbo); inFull = false }
        wetRect = null
        paintRect = null
        wetIdle = 0.0
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** The layer window's placement, written before each pass's own uniforms ([MediaUniforms.common]). */
    private fun common(u: UniformBank) { MediaUniforms.common(u, originX, originY, scale); atlas?.uniforms(u) }

    // ---- Media Atlas v2 B2: free placement (a [MediaAtlas] host places tiles; the engine then has no window edge) ----
    /** True once a host placed tiles freely: rectangles are not clamped to a window, and every scissor and blit goes slot by slot. */
    private val free get() = atlas?.free == true
    /** The residency rule's hook: the host makes [rect] (layer px) and [reach] around it resident, or throws, before a pass. */
    var onNeed: ((FloatArray, Int) -> Unit)? = null
    private fun need(rect: FloatArray, reach: Int) { if (free) onNeed?.invoke(rect, reach) }
    /** The water passes' ask (B3): the host keeps what the water needs by its own rules (MediaAtlasWater), not the whole rectangle. */
    var onNeedWater: ((FloatArray, Int) -> Unit)? = null
    private fun needWater(rect: FloatArray, reach: Int) { if (free) (onNeedWater ?: onNeed)?.invoke(rect, reach) }

    /**
     * One pass's ask of the host: [rect] (layer px) and [reach] around it, exactly as that pass will call [need].
     * [water] = the water's simulated rectangle (held by the water's rules); [input] = where the brush puts water this frame.
     */
    class Need(val rect: FloatArray, val reach: Int, val water: Boolean = false, val input: Boolean = false)

    // Media Atlas v2 B3: a frame is admitted whole before it draws (MediaCanvas -> [MediaPlace.admit]), so a frame is never
    // refused or short of a tile half-way. These compute what each pass will ask for with the passes' own code.
    /** What [dryFrame] will ask for. */
    fun dryNeed(batch: DabBatch, mat: StickMaterial, pxPerMm: Double): Need? =
        if (batch.count == 0) null
        else layerRect(batch.dirty, pxPerMm * mat.smearMm + 2)?.let { Need(it, MediaAtlasPlacement.dryReach(pxPerMm * mat.smearMm * scale)) }

    /** What [pasteStep] will ask for: the old square (the quad it still draws), with the paste reach. */
    fun pasteNeed(st: PasteStep, b: PasteBrush, pxPerMm: Double): Need? =
        layerRect(PasteFootprint.legacyBounds(st, b, pxPerMm), 2.0)?.let { Need(it, MediaAtlasPlacement.PASTE_REACH) }

    /** What [eraseFrame] will ask for. */
    fun eraseNeed(batch: DabBatch): Need? =
        if (batch.count == 0) null else layerRect(batch.dirty, 2.0)?.let { Need(it, MediaAtlasPlacement.ERASE_REACH) }

    /**
     * What [frames] calls of [wetFrame] (the batches in order, null = water only) will ask for at most: the simulated
     * rectangle after the last of them, which contains every earlier one (it only grows within an episode). Null: no water.
     */
    fun wetNeed(batches: List<DabBatch?>, pxPerMm: Double, steps: Int): Need? {
        var wr = wetRect; var pr = paintRect
        for (batch in batches) {
            if (batch != null && batch.count > 0) layerRect(batch.dirty, 48.0)?.let { wr = union(wr, it); pr = union(pr, it) }
            wr = wr?.let { WetSpread.grow(it, pr, slope[0], slope[1], steps, 1 / (pxPerMm * scale),
                if (free) Int.MAX_VALUE else w, if (free) Int.MAX_VALUE else h) }
        }
        return wr?.let { Need(it, MediaAtlasPlacement.WET_REACH, water = true) }
    }

    /** Where [batches] put water ([wetFrame]'s brush input, before it is clipped to the water): made resident and marked wet. */
    fun wetInputNeeds(batches: List<DabBatch?>): List<Need> = batches.mapNotNull { b ->
        if (b == null || b.count == 0) null else layerRect(b.dirty, 48.0)?.let { Need(it, MediaAtlasPlacement.WET_REACH, input = true) }
    }
    /** The engine's texture width (layer px at full resolution): the atlas's slots × 256 across. */
    val textureWidth: Int get() = w
    /** How many slots the atlas has (B2: one of them is staging). */
    val slotCount: Int get() = (w / MediaAtlasDraw.SIDE) * (h / MediaAtlasDraw.SIDE)
    fun slotTexel(slot: Int): Pair<Int, Int> = requireNotNull(atlas) { "not an atlas engine" }.slotTexel(slot)
    fun setResidency(map: Map<Long, Int>) { requireNotNull(atlas) { "not an atlas engine" }.setResidency(map) }
    fun toLayerPx(docX: Double, docY: Double): Pair<Float, Float> = ((docX - originX) * scale).toFloat() to ((docY - originY) * scale).toFloat()
    /** Same-format copy of layer-px [x0, y0, x1, y1) from [src] to [dst] (two textures of this engine): window, or slot by slot. */
    private fun blitLayer(src: Int, dst: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        val a = atlas
        if (!free || a == null) { blit(src, x0, y0, x1, y1, dst, x0, y0, x1, y1); return }
        a.forEachPart(floatArrayOf(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat())) { slot, tx, ty, cx0, cy0, cx1, cy1 ->
            val (ax, ay) = a.slotTexel(slot)
            val ox = ax - tx * MediaAtlasDraw.SIDE; val oy = ay - ty * MediaAtlasDraw.SIDE
            blit(src, cx0 + ox, cy0 + oy, cx1 + ox, cy1 + oy, dst, cx0 + ox, cy0 + oy, cx1 + ox, cy1 + oy)
        }
    }
    /** Empties one slot of every slot-shaped target (a tile about to be loaded there starts from rest, as a new window did). */
    fun clearSlot(ax: Int, ay: Int) {
        val side = MediaAtlasDraw.SIDE
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glScissor(ax, ay, side, side)
        for (f in listOf(state[0].fbo, state[1].fbo, groundFbo, deltaFbo, inFbo, lookFbo) + wetCopyFbo.toList()) {
            if (f == 0) continue
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        if (beadFbo.isNotEmpty()) {
            GLES30.glScissor(ax / 2, ay / 2, side / 2, side / 2)
            for (f in beadFbo) { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT) }
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }
    /** The paper baked for one tile's slot (a free-placed tile, as it loads). */
    fun bakeTile(key: Long, paper: MediaPaperGl) {
        ensureBake()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bakeFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDisable(GLES30.GL_BLEND)
        val u = progBake.use()
        common(u); MediaUniforms.paper(u, paper); MediaUniforms.bake(u, w, h)
        val side = MediaAtlasDraw.SIDE.toFloat()
        quad(floatArrayOf(Tiles.tx(key) * side, Tiles.ty(key) * side, (Tiles.tx(key) + 1) * side, (Tiles.ty(key) + 1) * side))
        bakedPaper = paper
    }

    /** A layer-shaped pass over [rect] (layer px at the target's resolution; shift 7 = the half-resolution bead field). */
    private fun quad(rect: FloatArray, shift: Int = MediaAtlasDraw.SHIFT) {
        val a = atlas
        if (a != null) { a.drawRect(rect, shift); return }
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }
    private val fullRect = FloatArray(4)
    private fun full(): FloatArray { fullRect[0] = 0f; fullRect[1] = 0f; fullRect[2] = w.toFloat(); fullRect[3] = h.toFloat(); return fullRect }

    /** Instanced dabs: window = one draw of [batch]; atlas = each dab once per slot its box (a doc-px [reach] round its centre) touches. */
    private fun dabs(batch: DabBatch, clip: FloatArray? = null, reach: (FloatArray, Int) -> Float) {
        val a = atlas
        if (a != null) {
            val box = { d: FloatArray, i: Int, out: FloatArray ->
                val o = i * DabBatch.FLOATS
                val cx = (d[o] - originX) * scale; val cy = (d[o + 1] - originY) * scale
                val r = reach(d, o) * scale + 2f
                out[0] = cx - r; out[1] = cy - r; out[2] = cx + r; out[3] = cy + r
            }
            if (free && clip != null) a.drawDabs(batch, box, clip) else a.drawDabs(batch, box)
            return
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dabBuf)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, batch.dabs.size * 4, uploadBuffer.upload(batch.dabs), GLES30.GL_STREAM_DRAW)
        GLES30.glBindVertexArray(dabVao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, batch.count)
    }

    /** A doc-px rectangle â†’ this layer's px, padded (doc px) and clamped. */
    private fun layerRect(r: DoubleArray?, padDoc: Double = 0.0): FloatArray? {
        if (r == null) return null
        val pad = padDoc * scale
        if (free) {   // no window: the host's residency decides what can be painted (refused in words), never a clamp
            val x0 = floor((r[0] - originX) * scale - pad); val y0 = floor((r[1] - originY) * scale - pad)
            val x1 = ceil((r[2] - originX) * scale + pad); val y1 = ceil((r[3] - originY) * scale + pad)
            return if (x1 > x0 && y1 > y0) floatArrayOf(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat()) else null
        }
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
        MediaStores.GROUND -> listOf(groundTex)
        "w0" -> { ensureWet(); wet!!.map { it.w0 } }
        "w1" -> { ensureWet(); wet!!.map { it.w1 } }
        else -> throw IllegalArgumentException("unknown media store $store")
    }

    /** The texture a store's current state is read from, for a write-back. Null for water that was never made. */
    fun current(store: String): Int? = when (store) {
        "p0" -> state[0].p0; "p1" -> state[0].p1; "paper" -> state[0].paper
        MediaStores.GROUND -> groundTex
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

    /** The ground texture (window px): what the media lies on, loaded from `g` tiles or, where a tile has no payload, its look. */
    fun groundTexture(): Int = groundTex

    /**
     * Empties a window-px rectangle of every target a tile loads into (state, ground, both water buffers and their flow),
     * so a tile can be loaded again while the water elsewhere keeps running ([MediaWindow.lookWritten]).
     */
    fun clearRect(x0: Int, y0: Int, x1: Int, y1: Int) {
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(x0, y0, x1 - x0, y1 - y0)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        for (f in listOf(state[0].fbo, groundFbo) + wetCopyFbo.toList()) {
            if (f == 0) continue
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
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
        if (free) { for (key in atlas!!.resident.keys) bakeTile(key, paper); bakedPaper = paper; return }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bakeFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDisable(GLES30.GL_BLEND)
        val u = progBake.use()
        common(u); MediaUniforms.paper(u, paper); MediaUniforms.bake(u, w, h)   // (the old u_cellPx named no uniform of jb_media_bake.frag)
        quad(full())
        bakedPaper = paper
    }

    /** The paper's contact table for this stick's press (a tuned stick has its own tooth stiffness and cushion). */
    private fun contactTexture(paper: MediaPaperGl, mat: StickMaterial): Int {
        val lut = paper.stats.contactLut(mat.lutPress)
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
        val u = progCopy.use()
        common(u); MediaUniforms.copy(u, src.p0, src.p1, src.paper, w, h, rect)
        quad(rect)
    }

    // ---- dry media (pencil) ----
    fun dryFrame(batch: DabBatch, travelX: Double, travelY: Double, paper: MediaPaperGl, mat: StickMaterial, pxPerMm: Double) {
        pasteSettle()
        if (batch.count == 0) return
        ensureDry()
        val cur = state[0]
        val nxt = state[1]
        val n = dryNeed(batch, mat, pxPerMm) ?: return
        val rect = n.rect
        need(rect, n.reach)
        touch(rect)
        stores += "paper"
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, deltaFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        val t = paper.stats.toothMm
        val lutTex = contactTexture(paper, mat)   // before the program is made current, as the old map building did (it binds on unit 0)
        val du = progDab.use()
        common(du); MediaUniforms.paper(du, paper)
        MediaUniforms.dryDab(du, cur.paper, lutTex, paper.stats.contactLut(mat.lutPress).maxMm, w, h, pxPerMm, t, mat)
        mediaTrace("JB.media.dry.delta") {
            // The stick's footprint: xMin, xMax, yMax (mm, stick frame) at floats 4-6, plus the vertex shader's 0.06 mm margin.
            val ppm = pxPerMm.toFloat()
            dabs(batch) { d, o -> (hypot(max(abs(d[o + 4]), abs(d[o + 5])), d[o + 6]) + 0.1f) * ppm }
        }
        GLES30.glDisable(GLES30.GL_BLEND)

        mediaTrace("JB.media.dry.apply") {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, nxt.fbo)
            val au = progApply.use()
            common(au)
            MediaUniforms.dryApply(au, cur.p0, cur.p1, cur.paper, delta, w, h, rect, mat, travelX, travelY, pxPerMm, scale)
            quad(rect)
        }
        mediaTrace("JB.media.dry.copy") {
            copyRect(nxt, cur.fbo, rect)
        }

        if (free) { atlas!!.clearRect(deltaFbo, rect); return }
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

    /**
     * Debug/A-B switch: true = every paste step shades and copies the whole old square (half-size hypot(halfW, lenMax)
     * + 0.3 mm) instead of scissoring it to [PasteFootprint]'s tight oriented box. Both give identical pixels (the dab
     * shader passes its input through outside the footprint); only the fill cost differs. Default false.
     */
    var legacyRect = false

    /** One step of a paste brush: the brush trades with the canvas under it, both sides from the same state. */
    // Its three passes are systrace sections only: a paste stroke makes a dozen steps a frame, and timing each (glFinish
    // brackets) would measure the brackets. The canvas times all of a frame's steps at once (paste.frame).
    fun pasteStep(st: PasteStep, b: PasteBrush, paper: MediaPaperGl, pxPerMm: Double, seed: Int) {
        traced("JB.media.paper.bake") { bakePaper(paper) }
        val cap = cellCap(b)
        val t = paper.stats.toothMm
        val cur = brush[bc]
        val nxt = brush[1 - bc]
        val square = pasteNeed(st, b, pxPerMm)?.rect
        val rect = if (legacyRect) square else layerRect(PasteFootprint.docBounds(st, b, scale.toDouble(), pxPerMm), 2.0)
        // Paired steps ([pastePairs]): a B step reads state[1], where the A step before it left its box; first everything it
        // reads beyond that box is brought over from state[0] (which still holds the canvas there).
        val held = pasteHeld
        val pairB = pastePairs && held != null && square != null && rect != null
        if (pairB) traced("JB.media.paste.strip") {
            for (r in rectMinus(grow(rect!!, MediaAtlasPlacement.PASTE_REACH.toFloat()), held!!)) copyRect(state[0], pFbo1, r)
        }
        val src = if (pairB) state[1] else state[0]
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(quadVao)
        // Brush side first (reads the canvas BEFORE this step), into the other brush buffer.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, nxt.fbo)
        GLES30.glViewport(0, 0, PASTE_LANES, PASTE_DEPTH)
        val bu = progPasteBrush.use()
        common(bu)
        // JB_ATLAS_NBR5: the brush's cells read the canvas around the step through the 5x5 tiles around its middle.
        val a = atlas
        if (a != null && square != null) {
            val ctx = floor((square[0] + square[2]) / 2f / MediaAtlasDraw.SIDE).toInt()
            val cty = floor((square[1] + square[3]) / 2f / MediaAtlasDraw.SIDE).toInt()
            val reach = MediaAtlasPlacement.PASTE_REACH
            if (floor((square[0] - reach) / MediaAtlasDraw.SIDE).toInt() < ctx - 2 || floor((square[1] - reach) / MediaAtlasDraw.SIDE).toInt() < cty - 2 ||
                floor((square[2] + reach) / MediaAtlasDraw.SIDE).toInt() > ctx + 2 || floor((square[3] + reach) / MediaAtlasDraw.SIDE).toInt() > cty + 2)
                android.util.Log.w("JoyBrushMediaAtlas", "a paste step wider than 5 tiles: its brush reads past what it is handed")
            a.neighbours(ctx, cty, nbrSlots5, r = 2)
            nbrTile[0] = ctx; nbrTile[1] = cty; bu.i2v("u_nbrTile", nbrTile); bu.i2v("u_nbrSlot", nbrSlots5)
        }
        MediaUniforms.paste(bu, st, b, cap, t, pxPerMm, seed, bakeTex, src.p0, src.p1)
        MediaUniforms.pasteBrush(bu, cur.b0, cur.b1, b.wick, w, h, PASTE_LANES, PASTE_DEPTH)
        traced("JB.media.paste.brush") { GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4) }
        // Canvas side, from the same (old) brush cells. The dab shader hands back its input untouched wherever the brush
        // has no coverage (jb_paste_dab.frag lines 40/47/83), so only the oriented box around the real footprint
        // ([PasteFootprint], ~7-10x smaller than the old square for Oil round) has to be shaded and copied back.
        // The quad is STILL the old square and the box is a scissor on it: a smaller quad would interpolate v_docPx /
        // v_layerPx with different rounding, and the noise inside the shader would shift by an ulp (measured:
        // tools/media_paste_rect_parity_check.js). Same primitive + scissor = the same fragments, bit for bit.
        if (square != null && rect != null) {
            need(square, MediaAtlasPlacement.PASTE_REACH)
            touch(rect)
            stores += "p0"; stores += "p1"
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, if (pairB) pFbo0 else pFbo1)
            GLES30.glViewport(0, 0, w, h)
            val tight = rect !== square && !free
            if (tight) {
                GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
                GLES30.glScissor(rect[0].toInt(), rect[1].toInt(), (rect[2] - rect[0]).toInt(), (rect[3] - rect[1]).toInt())
            }
            val du = progPasteDab.use()
            common(du)
            MediaUniforms.paste(du, st, b, cap, t, pxPerMm, seed, bakeTex, src.p0, src.p1)
            MediaUniforms.pasteDab(du, cur.b0, cur.b1, w, h, square)
            // Window: the square, trimmed by the scissor to the footprint box. Free placement: the box itself, slot by slot
            // (the same pixels: no varying is interpolated across the quad any more).
            traced("JB.media.paste.dab") { quad(if (free) rect else square) }
            if (tight) GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            when {
                // B: its box is in state[0] now; what is left of A's box in state[1] comes back, and state[0] is all of it.
                pairB -> { traced("JB.media.paste.copy") { for (r in rectMinus(held!!, rect)) copyRect(state[1], pFbo0, r) }; pasteHeld = null }
                // A: its box stays in state[1] for the next step to read.
                pastePairs -> pasteHeld = rect
                else -> traced("JB.media.paste.copy") { copyRect(state[1], pFbo0, rect) }
            }
        }
        bc = 1 - bc
    }

    /**
     * Paste steps in pairs: bit for bit what one copy-back per step gives, with a third fewer passes. Step A writes its box
     * into state[1] and leaves it there; step B copies over only what it reads beyond that box (state[0] holds the canvas
     * there), reads state[1], writes its own box into state[0], then copies back the part of A's box it did not cover. Two
     * thin strips per pair instead of two whole boxes. False: every step copies its box back (the path before; kept to
     * compare, MediaAtlasProbe "pasteCopy").
     */
    var pastePairs = true

    /** Step A's box while the canvas there is in state[1] (state[0] everywhere else); null: state[0] is all of it. */
    private var pasteHeld: FloatArray? = null

    /** Ends a frame's paste steps: an unpaired last step's box goes back to state[0], which everything else reads. */
    fun pasteSettle() {
        val h = pasteHeld ?: return
        pasteHeld = null
        traced("JB.media.paste.copy") { copyRect(state[1], pFbo0, h) }
    }

    private fun grow(r: FloatArray, d: Float) = floatArrayOf(r[0] - d, r[1] - d, r[2] + d, r[3] + d)

    /** [a] less [b] as at most four rectangles (layer px). */
    private fun rectMinus(a: FloatArray, b: FloatArray): List<FloatArray> {
        val ix0 = max(a[0], b[0]); val iy0 = max(a[1], b[1]); val ix1 = min(a[2], b[2]); val iy1 = min(a[3], b[3])
        if (ix1 <= ix0 || iy1 <= iy0) return listOf(a)
        val out = ArrayList<FloatArray>(4)
        if (iy0 > a[1]) out += floatArrayOf(a[0], a[1], a[2], iy0)
        if (iy1 < a[3]) out += floatArrayOf(a[0], iy1, a[2], a[3])
        if (ix0 > a[0]) out += floatArrayOf(a[0], iy0, ix0, iy1)
        if (ix1 < a[2]) out += floatArrayOf(ix1, iy0, a[2], iy1)
        return out
    }

    // ---- the eraser on a media layer (app only; the Lead, step-4 check 1) ----
    /**
     * Takes paint, graphite and water out of the state under [batch]'s dabs (first four floats of each: x, y, radius in
     * doc px, strength 0..1), so the next look agrees. Only [stores] that exist here are touched (stores stay lazy); the
     * paper's crush is kept.
     */
    fun eraseFrame(batch: DabBatch, stores: Set<String>) {
        pasteSettle()
        if (batch.count == 0 || stores.isEmpty()) return
        ensureDry()
        val n = eraseNeed(batch) ?: return
        val rect = n.rect
        need(rect, n.reach)
        touch(rect)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, deltaFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glBlendEquation(GLES30.GL_MAX)   // overlapping dabs never take away more than one does
        val eu = progEraseDab.use()
        common(eu); MediaUniforms.eraseDab(eu, w, h)
        // radius (doc px) at float 2; the vertex shader pads by 1.5 layer px and never draws a dab under half a pixel.
        dabs(batch) { d, o -> d[o + 2] + (2f + 0.5f) / scale }
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(quadVao)
        if (stores.any { it == "p0" || it == "p1" || it == "paper" }) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, state[1].fbo)
            val su = progErase.use()
            common(su); MediaUniforms.erase(su, state[0].p0, state[0].p1, state[0].paper, delta, 0, w, h, rect)
            quad(rect)
            copyRect(state[1], state[0].fbo, rect)
            for (st in listOf("p0", "p1", "paper")) if (st in stores) this.stores += st
        }
        val ws = wet
        if (ws != null && stores.any { it in WATER_STORES }) {
            if (eraseWetFbo.isEmpty()) eraseWetFbo = IntArray(2) { MediaTex.fbo(ws[it].w0, ws[it].w1) }
            val other = 1 - wc
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, eraseWetFbo[other])
            GLES30.glViewport(0, 0, w, h)
            val wu = progErase.use()
            common(wu); MediaUniforms.erase(wu, ws[wc].w0, ws[wc].w1, ws[wc].w0, delta, 1, w, h, rect)
            quad(rect)
            val (x0, y0, x1, y1) = intArrayOf(floor(rect[0]).toInt(), floor(rect[1]).toInt(), ceil(rect[2]).toInt(), ceil(rect[3]).toInt()).let { listOf(it[0], it[1], it[2], it[3]) }
            blitLayer(ws[other].w0, ws[wc].w0, x0, y0, x1, y1)
            blitLayer(ws[other].w1, ws[wc].w1, x0, y0, x1, y1)
            this.stores += "w0"; this.stores += "w1"
        }
        if (free) { atlas!!.clearRect(deltaFbo, rect); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0); return }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, deltaFbo)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(floor(rect[0]).toInt(), floor(rect[1]).toInt(), ceil(rect[2] - rect[0]).toInt() + 1, ceil(rect[3] - rect[1]).toInt() + 1)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    // ---- wet media ----
    /** One frame of water: new dabs (if any) go into the brush input, then the water runs [WetConstants.substeps] times. */
    fun wetFrame(batch: DabBatch?, paper: MediaPaperGl, pxPerMm: Double, wc0: WetConstants = WetConstants(), substeps: Int? = null,
                 evapOverride: Double? = null, maxWetSeconds: Double = 240.0) {
        // B3: tiles the water left (a drip's dried tail, MediaAtlasWater.excluded) are not simulated, as beyond the 4 cm cap.
        // The water beside such a tile sees a wall there (zero gradient, no flow), as at the window's edge: no dry front.
        val a = atlas
        if (a != null) { a.skip = waterSkip; a.setWalls(waterSkip); a.wallsOn = waterSkip.isNotEmpty() }
        try { wetFrameInner(batch, paper, pxPerMm, wc0, substeps, evapOverride, maxWetSeconds) }
        finally { if (a != null) { a.skip = emptySet(); a.wallsOn = false } }
    }

    /** Tiles out of the water's simulation (Media Atlas v2 B3, set by the host): the water passes draw nothing there. */
    var waterSkip: Set<Long> = emptySet()

    private fun wetFrameInner(batch: DabBatch?, paper: MediaPaperGl, pxPerMm: Double, wc0: WetConstants, substeps: Int?,
                              evapOverride: Double?, maxWetSeconds: Double) {
        pasteSettle()
        ensureWet()
        mediaTrace("JB.media.paper.bake") { bakePaper(paper) }
        val ws = wet!!
        val steps = substeps ?: wc0.substeps
        // The rectangle the substeps will simulate: a pure function of the rectangles, the tilt and the step count.
        fun simRect(): FloatArray? =
            wetRect?.let { WetSpread.grow(it, paintRect, slope[0], slope[1], steps, 1 / (pxPerMm * scale),
                if (free) Int.MAX_VALUE else w, if (free) Int.MAX_VALUE else h) }   // free: only the 4 cm cap, no window
        var useInput = false
        var inRect: FloatArray? = null
        if (batch != null && batch.count > 0) {
            val r = layerRect(batch.dirty, 48.0)
            if (r != null) { wetRect = union(wetRect, r); paintRect = union(paintRect, r) }
            // Only the simulated rectangle ever reads the brush input, so the dabs are drawn there and the input is cleared
            // there after the frame (the full-window clear wrote 16 B/px of nothing). Where there is no rectangle to clip to,
            // the dabs are drawn whole and the next clear is whole too (inFull), exactly as before.
            inRect = simRect()
            inRect?.let { needWater(it, MediaAtlasPlacement.WET_REACH) }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, inFbo)
            GLES30.glViewport(0, 0, w, h)
            if (inRect != null) { if (!free) scissorTo(inRect) } else inFull = true
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
            val du = progWetDab.use()
            common(du); MediaUniforms.wetDab(du, ws[wc].w0, bakeTex, w, h, pxPerMm, wc0.fullMm)
            // half-length, half-width (mm) at floats 4-5, plus the vertex shader's 0.08 mm.
            val ppm = pxPerMm.toFloat()
            mediaTrace("JB.media.wet.input") { dabs(batch, inRect) { d, o -> (hypot(d[o + 4], d[o + 5]) + 0.1f) * ppm } }
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            useInput = true
            wetIdle = 0.0
        }
        // On a tilted paper running water can leave the rectangle: it grows downhill as fast as water can run, never
        // further than a long drip past the paint, nor past this window (WetSpread, the tested cap).
        val rect = simRect() ?: return
        wetRect = rect
        stores += WATER_WRITES
        touch(rect)
        wetSince = union(wetSince, rect)
        val beadCells = wc0.beadMm * pxPerMm * scale
        // The frame's constants, flattened once; the substeps below only apply them (unchanged values cost no GL call).
        wetFrameUniforms.load(wetUniforms(paper.stats, wc0), evapOverride, slope, 1 / (pxPerMm * scale), beadCells)
        val tilted = hypot(slope[0].toDouble(), slope[1].toDouble()) > 1e-4
        GLES30.glDisable(GLES30.GL_BLEND)
        // The paint planes (p0, p1) used to be written to state[1] by every update and copied back to state[0] by a
        // whole extra pass. The update reads them at its OWN pixel only, so instead the substeps alternate: substep k reads
        // state[k mod 2] and writes the other, and a frame of an even number of substeps ends in state[0] with no copy.
        // The flux pass also reads p0.a at the neighbours (the water's surface, and the paper's slope d cells away when
        // tilted); inside the rectangle those are the previous substep's output, and beyond it the paint never changes,
        // so state[0]'s values for the ring around the rectangle are copied into state[1] once, before the first substep.
        needWater(rect, MediaAtlasPlacement.WET_REACH)
        if (steps > 1) mediaTrace("JB.media.wet.ring") { copyP0Ring(rect, ringCells(wc0, beadCells)) }
        for (k in 0 until steps) {
            val a = ws[wc]
            val bi = 1 - wc
            val b = ws[bi]
            val src = state[k and 1]
            GLES30.glBindVertexArray(quadVao)
            if (tilted) mediaTrace("JB.media.wet.bead") { beadPasses(a.w0, rect, beadCells) }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fluxFbo[bi])
            GLES30.glViewport(0, 0, w, h)
            val fu = progFlux.use()
            common(fu); wetFrameUniforms.apply(fu)
            MediaUniforms.wetFlux(fu, bead[0], src.p0, a.w0, a.flux, bakeTex, fluidBake, waterBake, rect, w, h)
            mediaTrace("JB.media.wet.flux") { quad(rect) }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, updFbo[bi * 2 + 1 - (k and 1)])
            val pu = progUpdate.use()
            common(pu); wetFrameUniforms.apply(pu)
            MediaUniforms.wetUpdate(pu, a.w0, a.w1, b.flux, run, src.p0, src.p1, bakeTex, waterBake, fluidBake, in0, in1,
                if (k == 0 && useInput) 1 else 0, rect, w, h)
            mediaTrace("JB.media.wet.update") { quad(rect) }
            wc = bi
        }
        // An odd number of substeps (the dry-now frame) leaves the result in state[1]: one copy, as every substep used to make.
        if ((steps and 1) == 1) mediaTrace("JB.media.wet.tail") { copyRect(state[1], pFbo0, rect) }
        if (useInput) {
            if (inFull || inRect == null) { clearFbo(inFbo); inFull = false }
            else if (free) atlas!!.clearRect(inFbo, inRect)
            else {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, inFbo)
                GLES30.glViewport(0, 0, w, h)
                scissorTo(inRect)
                GLES30.glClearColor(0f, 0f, 0f, 0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            }
        }
        wetIdle += steps * wc0.dt
        if (wetIdle > maxWetSeconds) dryNow(paper, pxPerMm)
    }

    /** Scissor to the whole pixels a layer-px rectangle can touch. */
    private fun scissorTo(r: FloatArray) {
        val x0 = max(0, floor(r[0]).toInt()); val y0 = max(0, floor(r[1]).toInt())
        val x1 = min(w, ceil(r[2]).toInt()); val y1 = min(h, ceil(r[3]).toInt())
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(x0, y0, max(0, x1 - x0), max(0, y1 - y0))
    }

    /** How far beyond the rectangle the flux pass reads p0.a: one cell, or the paper-slope distance when tilted, and some margin. */
    private fun ringCells(wc0: WetConstants, beadCells: Double): Int = max(4, ceil(wc0.steerScale * beadCells).toInt() + 3)

    /** state[0].p0 -> state[1].p0 over the ring of [ring] cells around [rect] (blit: exact). */
    private fun copyP0Ring(rect: FloatArray, ring: Int) {
        val rx0 = max(0, floor(rect[0]).toInt()); val ry0 = max(0, floor(rect[1]).toInt())
        val rx1 = min(w, ceil(rect[2]).toInt()); val ry1 = min(h, ceil(rect[3]).toInt())
        val ox0 = max(0, rx0 - ring); val oy0 = max(0, ry0 - ring); val ox1 = min(w, rx1 + ring); val oy1 = min(h, ry1 + ring)
        fun strip(x0: Int, y0: Int, x1: Int, y1: Int) {
            if (x1 > x0 && y1 > y0) blitLayer(state[0].p0, state[1].p0, x0, y0, x1, y1)
        }
        strip(ox0, oy0, ox1, ry0)      // below
        strip(ox0, ry1, ox1, oy1)      // above
        strip(ox0, ry0, rx0, ry1)      // left
        strip(rx1, ry0, ox1, ry1)      // right
    }

    // The running bead's field: 2Ã—2 average of the water, then a Gaussian along x and y (jb_wet_bead.frag).
    private fun beadPasses(w0: Int, rect: FloatArray, beadCells: Double) {
        val sigma = min(4.8, max(1.0, 0.25 * beadCells))
        val r = min(12, ceil(2.5 * sigma).toInt())
        val m = 3 + 2 * r
        val hr = if (free) floatArrayOf(floor(rect[0] / 2) - m, floor(rect[1] / 2) - m, ceil(rect[2] / 2) + m, ceil(rect[3] / 2) + m)
            else floatArrayOf(max(0f, floor(rect[0] / 2) - m), max(0f, floor(rect[1] / 2) - m),
            min(beadW.toFloat(), ceil(rect[2] / 2) + m), min(beadH.toFloat(), ceil(rect[3] / 2) + m))
        fun pass(fbo: Int, pass: Int, src: Int, axisX: Float, axisY: Float) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glViewport(0, 0, beadW, beadH)
            val bu = progBead.use()
            common(bu); MediaUniforms.bead(bu, hr, beadW, beadH, sigma, pass, w0, src, axisX, axisY)
            atlas?.uniforms(bu, MediaAtlasDraw.SHIFT - 1)   // the half-resolution field's slots are 128 cells
            quad(hr, MediaAtlasDraw.SHIFT - 1)
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

    /**
     * Bench only: every layer-shaped texture this engine holds, as GL reports it (real internal format and size, GLES 3.1+),
     * the total, and what one 256² slot costs across all of them: the atlas's price per slot (Media Atlas v2: the slot
     * count is sized from this and the phone's memory). JSON; "error" when the GL cannot say.
     */
    fun memoryReport(): String {
        val named = LinkedHashMap<String, Int>()
        state.forEachIndexed { i, st -> named["state$i.p0"] = st.p0; named["state$i.p1"] = st.p1; named["state$i.paper"] = st.paper }
        named["ground"] = groundTex; named["delta"] = delta; named["bake"] = bakeTex; named["fluidBake"] = fluidBake; named["waterBake"] = waterBake
        wet?.forEachIndexed { i, x -> named["wet$i.w0"] = x.w0; named["wet$i.w1"] = x.w1; named["wet$i.flux"] = x.flux }
        named["run"] = run; bead.forEachIndexed { i, b -> named["bead$i"] = b }; named["in0"] = in0; named["in1"] = in1; named["look"] = lookTex
        val out = org.json.JSONObject().put("w", w).put("h", h)
        try {
            val tex = org.json.JSONObject(); var total = 0L; val v = IntArray(1)
            for ((name, id) in named) {
                if (id == 0) continue
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
                android.opengl.GLES31.glGetTexLevelParameteriv(GLES30.GL_TEXTURE_2D, 0, android.opengl.GLES31.GL_TEXTURE_WIDTH, v, 0); val tw = v[0]
                android.opengl.GLES31.glGetTexLevelParameteriv(GLES30.GL_TEXTURE_2D, 0, android.opengl.GLES31.GL_TEXTURE_HEIGHT, v, 0); val th = v[0]
                android.opengl.GLES31.glGetTexLevelParameteriv(GLES30.GL_TEXTURE_2D, 0, android.opengl.GLES31.GL_TEXTURE_INTERNAL_FORMAT, v, 0)
                val (fmt, bpp) = when (v[0]) {
                    GLES30.GL_RGBA32F -> "RGBA32F" to 16; GLES30.GL_RGBA16F -> "RGBA16F" to 8; GLES30.GL_RG16F -> "RG16F" to 4
                    GLES30.GL_R16F -> "R16F" to 2; GLES30.GL_R8 -> "R8" to 1; GLES30.GL_RGBA8 -> "RGBA8" to 4
                    else -> "0x" + Integer.toHexString(v[0]) to 4
                }
                val bytes = tw.toLong() * th * bpp
                tex.put(name, org.json.JSONObject().put("w", tw).put("h", th).put("format", fmt).put("bytes", bytes))
                total += bytes
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            val slots = (w / MediaAtlasDraw.SIDE) * (h / MediaAtlasDraw.SIDE)
            out.put("textures", tex).put("totalBytes", total).put("slots", slots).put("bytesPerSlot", total / max(1, slots))
        } catch (e: Throwable) { out.put("error", e.toString()) }
        return out.toString()
    }

    // ---- Media Atlas v2 B3: water tile by tile (MediaAtlasWater) ----
    /**
     * Dries the water in [rects] (layer px, whole tiles) where it lies, in one step, as [dryNow] does for all of it: nothing
     * flows out (their flux and running water are cleared first), evaporation without limit, the pigment left where it is;
     * then their water buffers are emptied, so they hold no water and no dampness and stay as they are (a fixed point).
     * D1 (a quiet component) and D2 (a drip's tail) of the B3 plan; the water elsewhere keeps running.
     */
    fun dryTilesNow(rects: List<FloatArray>, paper: MediaPaperGl, pxPerMm: Double, wc0: WetConstants = WetConstants()) {
        val ws = wet ?: return
        if (rects.isEmpty()) return
        bakePaper(paper)
        val bi = 1 - wc
        // Everything else is a wall while these dry: nothing runs in from the water beside them, nothing reads dry paper
        // there, so the pigment stays exactly where it lies (no edge drop-out along the tile's edge).
        val a = atlas
        if (a != null && free) {
            val drying = rects.flatMap { MediaAtlasPlacement.tiles(it[0].toDouble(), it[1].toDouble(), it[2].toDouble(), it[3].toDouble(), 0) }.toSet()
            a.setWalls(a.resident.keys - drying + waterSkip)
            a.wallsOn = true
        }
        try { dryTilesInner(ws, rects, paper, pxPerMm, wc0, bi) } finally { if (a != null && free) { a.wallsOn = false; a.setWalls(waterSkip) } }
    }

    private fun dryTilesInner(ws: Array<Wet>, rects: List<FloatArray>, paper: MediaPaperGl, pxPerMm: Double, wc0: WetConstants, bi: Int) {
        for (r in rects) clearIn(fluxFbo[bi], r)
        wetFrameUniforms.load(wetUniforms(paper.stats, wc0), 1e4, slope, 1 / (pxPerMm * scale), wc0.beadMm * pxPerMm * scale)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, updFbo[bi * 2 + 1])
        GLES30.glViewport(0, 0, w, h)
        val pu = progUpdate.use()
        common(pu); wetFrameUniforms.apply(pu)
        for (r in rects) {
            MediaUniforms.wetUpdate(pu, ws[wc].w0, ws[wc].w1, ws[bi].flux, run, state[0].p0, state[0].p1, bakeTex, waterBake, fluidBake,
                in0, in1, 0, r, w, h)
            quad(r)
        }
        for (r in rects) {
            copyRect(state[1], pFbo0, r)
            for (f in wetCopyFbo) clearIn(f, r)
            touch(r)
        }
        stores += WATER_WRITES
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Clears layer-px [rect] of every target of [fbo] (slot by slot in the atlas). */
    private fun clearIn(fbo: Int, rect: FloatArray) {
        val a = atlas
        if (a != null && free) { a.clearRect(fbo, rect); return }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        scissorTo(rect)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }

    private var actTex = 0
    private var actFbo = 0
    private var actPbo = 0
    private var actFence = 0L

    /**
     * Starts measuring the water, coarsely and without waiting for the GPU: every 16×16 block of the current water state
     * reduced to (max depth, max dampness, max outflow) (jb_wet_activity.frag), read back through a pixel buffer.
     * [takeWaterActivity] collects it, frames later. False when there is no water or a measurement is still on its way.
     */
    fun requestWaterActivity(): Boolean {
        val ws = wet ?: return false
        if (actFence != 0L) return false
        val aw = w / 16; val ah = h / 16
        if (actTex == 0) {
            actTex = MediaTex.make(aw, ah, GLES30.GL_RGBA32F, GLES30.GL_RGBA, GLES30.GL_FLOAT, GLES30.GL_NEAREST)
            actFbo = MediaTex.fbo(actTex)
            val ids = IntArray(1); GLES30.glGenBuffers(1, ids, 0); actPbo = ids[0]
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, actPbo)
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, aw * ah * 16, null, GLES30.GL_STREAM_READ)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, actFbo)
        GLES30.glViewport(0, 0, aw, ah)
        GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        val u = progActivity.use()
        u.tex("u_w0", ws[wc].w0)
        GLES30.glBindVertexArray(emptyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, actPbo)
        GLES30.glReadPixels(0, 0, aw, ah, GLES30.GL_RGBA, GLES30.GL_FLOAT, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        actFence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return true
    }

    /**
     * The measurement [requestWaterActivity] started, once the GPU has finished it (null before): (w / 16) × (h / 16) blocks,
     * rows from the bottom, 4 floats each (max depth mm, max dampness, max outflow mm/s, 0). Never waits.
     */
    fun takeWaterActivity(): FloatArray? {
        if (actFence == 0L) return null
        val r = GLES30.glClientWaitSync(actFence, 0, 0L)
        if (r != GLES30.GL_ALREADY_SIGNALED && r != GLES30.GL_CONDITION_SATISFIED) return null
        GLES30.glDeleteSync(actFence); actFence = 0L
        val n = (w / 16) * (h / 16) * 4
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, actPbo)
        val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, n * 4, GLES30.GL_MAP_READ_BIT) as java.nio.ByteBuffer?
        val out = FloatArray(n)
        mapped?.order(java.nio.ByteOrder.nativeOrder())?.asFloatBuffer()?.get(out)
        GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        if (mapped == null) return null
        lastActivity = out; activitySeq++
        return out
    }

    private val nbrSlots = IntArray(18)
    private val nbrSlots5 = IntArray(50)
    private val nbrTile = IntArray(2)

    /** The newest water measurement, whoever asked for it (the atlas's residency, or the canvas's quiet watch), and its count. */
    var lastActivity: FloatArray? = null; private set
    var activitySeq = 0; private set

    // ---- the look: what the app's layer stack composites ----
    /** Render the look (premultiplied RGBA8, paper removed) for [rect] (layer px) into the returned texture. */
    fun renderLook(rect: FloatArray, paper: MediaPaperGl, look: MediaLook, pxPerMm: Double): Int {
        pasteSettle()
        lookTexture()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, lookFbo)
        GLES30.glViewport(0, 0, w, h)
        if (!free) {
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            GLES30.glScissor(floor(rect[0]).toInt(), floor(rect[1]).toInt(), ceil(rect[2] - rect[0]).toInt(), ceil(rect[3] - rect[1]).toInt())
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        val ws = wet
        // gl_FragCoord is layer px: docPx = (frag âˆ’ pan) / zoom = origin + frag / scale.
        mediaTrace("JB.media.look.render") {
            // emptyTex() may make a texture (it binds on unit 0), so resolve before the program is made current, as the old map building did.
            val bakeT = if (bakeTex != 0) bakeTex else emptyTex()
            val w0T = ws?.get(wc)?.w0 ?: emptyTex()
            val w1T = ws?.get(wc)?.w1 ?: emptyTex()
            val ru = progRender.use()
            common(ru); MediaUniforms.paper(ru, paper)
            MediaUniforms.render(ru, state[0].p0, state[0].p1, state[0].paper, groundTex, bakeT, w0T, w1T, w, h, originX, originY, scale,
                pxPerMm, paper.stats.toothMm, look)
            GLES30.glBindVertexArray(emptyVao)
            val a = atlas
            if (free && a != null) {
                // Each slot's texels map back to layer px through u_pan: gl_FragCoord − pan = layer px (exact integers at scale 1).
                a.forEachPart(rect) { slot, tx, ty, _, _, _, _ ->
                    if (a.scissorSlot(slot, tx, ty, rect)) {
                        val (ax, ay) = a.slotTexel(slot)
                        ru.f2("u_pan", -originX * scale + (ax - tx * MediaAtlasDraw.SIDE), -originY * scale + (ay - ty * MediaAtlasDraw.SIDE))
                        // The look reads its tile and the 8 around it straight from these, not through the table (JB_ATLAS_NBR).
                        a.neighbours(tx, ty, nbrSlots)
                        nbrTile[0] = tx; nbrTile[1] = ty; ru.i2v("u_nbrTile", nbrTile); ru.i2v("u_nbrSlot", nbrSlots)
                        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                    }
                }
            } else GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
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
        val a = atlas
        if (free && a != null) {
            wet?.let { ws ->
                for (x in ws) { val f = MediaTex.fbo(x.flux); a.clearRect(f, rect); MediaTex.deleteFbo(f) }
                val r = intArrayOf(floor(rect[0]).toInt(), floor(rect[1]).toInt(), ceil(rect[2]).toInt(), ceil(rect[3]).toInt())
                blitLayer(ws[wc].w0, ws[1 - wc].w0, r[0], r[1], r[2], r[3])
                blitLayer(ws[wc].w1, ws[1 - wc].w1, r[0], r[1], r[2], r[3])
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return
        }
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
        releasePrograms()
        val tex = ArrayList<Int>()
        for (s in state) tex += listOf(s.p0, s.p1, s.paper)
        for (b in brush) tex += listOf(b.b0, b.b1)
        wet?.forEach { tex += listOf(it.w0, it.w1, it.flux) }
        tex += listOf(delta, bakeTex, fluidBake, waterBake, run, in0, in1, lutTex, lookTex, groundTex, empty, actTex) + bead.toList()
        tex.filter { it != 0 }.toIntArray().let { if (it.isNotEmpty()) MediaTex.deleteTex(*it) }
        val fbos = ArrayList<Int>()
        for (s in state) fbos += s.fbo
        for (b in brush) fbos += b.fbo
        fbos += listOf(pFbo0, pFbo1, deltaFbo, bakeFbo, inFbo, lookFbo, groundFbo, readFbo, drawFbo, actFbo) + beadFbo.toList() + fluxFbo.toList() + updFbo.toList() +
            wetCopyFbo.toList() + eraseWetFbo.toList()
        fbos.filter { it != 0 }.toIntArray().let { if (it.isNotEmpty()) MediaTex.deleteFbo(*it) }
        GLES30.glDeleteBuffers(2, intArrayOf(quadBuf, dabBuf), 0)
        GLES30.glDeleteVertexArrays(3, intArrayOf(quadVao, dabVao, emptyVao), 0)
        if (actPbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(actPbo), 0)
        if (actFence != 0L) GLES30.glDeleteSync(actFence)
        actTex = 0; actFbo = 0; actPbo = 0; actFence = 0L
        atlas?.release(); atlas = null
        uploadBuffer.reset()
    }

    private companion object {
        val WATER_WRITES = listOf("p0", "p1", "w0", "w1")
        val WATER_STORES = listOf("w0", "w1")
    }

    private fun union(a: FloatArray?, b: FloatArray): FloatArray =
        if (a == null) b.copyOf() else floatArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
}

/**
 * The atlas build of every pass drawn slot by slot: reads through the 3x3 neighbour slots, not the page table
 * (JB_ATLAS_NBR, jb_media_atlas.glsl): compiles in a fraction of the time and runs near the window's speed.
 */
private val ATLAS_NBR = listOf("JB_ATLAS_NBR")
/** The paste brush's build: the same, 5x5 around the step (its target is the brush's cells, not a slot). */
private val ATLAS_NBR5 = listOf("JB_ATLAS_NBR", "JB_ATLAS_NBR5")
