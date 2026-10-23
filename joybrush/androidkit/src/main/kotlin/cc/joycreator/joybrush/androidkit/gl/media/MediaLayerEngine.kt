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
                       private val atlasBroken: Boolean = false,
                       /**
                        * Coarse water (tasks/joybrush/specs/COARSE_WATER_20261021.md): the water lives on 2 x 2 pixel cells (a
                        * quarter of the work and of the water's memory); the paint, the paper and the look stay at full
                        * resolution. False = the water at full resolution, as before.
                        */
                       val coarseWater: Boolean = coarseWaterDefault) {
    /** Pixels per water cell along each side: 2 with [coarseWater], else 1. */
    private val wf = if (coarseWater) 2 else 1
    /** The water's resolution in the atlas's terms: its slots are 256 / [wf] cells (shift 7 when coarse). */
    private val wShift = if (coarseWater) MediaAtlasDraw.SHIFT - 1 else MediaAtlasDraw.SHIFT
    /** The water textures' size (cells). */
    private val ww get() = (w + wf - 1) / wf
    private val wh get() = (h + wf - 1) / wf
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
    /** Coarse water only: the paint step (jb_wet_paint.frag) and the ground under each cell (jb_wet_terrain.frag). */
    private var progWetPaint: MediaProgram? = null
    private var progWetTerrain: MediaProgram? = null
    /** The water's passes built with JB_ATLAS_WALLS: drawn instead while a drained tile is a wall (MediaAtlasGl.wallsOn). */
    private var progFluxW: MediaProgram? = null
    private var progUpdateW: MediaProgram? = null
    private var progBeadW: MediaProgram? = null
    private fun fluxProgram() = atlas?.takeIf { it.wallsOn }?.let { progFluxW } ?: progFlux
    private fun updateProgram() = atlas?.takeIf { it.wallsOn }?.let { progUpdateW } ?: progUpdate
    private fun beadProgram() = atlas?.takeIf { it.wallsOn }?.let { progBeadW } ?: progBead

    /**
     * Every media program, in compile order: field setter, vertex, fragment, label, and whether it draws into the paste
     * brush's cells (not layer-shaped: JB_PLAIN_TARGET in atlas mode). One table, so [compileNext] can never skip one.
     */
    private val programTable: List<ProgramSpec> = listOf(
        ProgramSpec({ progDab = it }, "jb_dry_dab.vert", "jb_dry_dab.frag", "media dry dab"),
        ProgramSpec({ progApply = it }, "jb_media_quad.vert", "jb_media_apply.frag", "media apply", atlasDefs = ATLAS_NBR),
        // Copy and erase read only their own cell: no neighbours, so one instanced draw for every slot (2026-10-20: drawn slot by
        // slot with the 3x3 handed over, the copies were most of an oil or knife step's cost in the atlas).
        ProgramSpec({ progCopy = it }, "jb_media_quad.vert", "jb_media_copy.frag", "media copy", atlasDefs = ATLAS_OWN),
        ProgramSpec({ progRender = it }, "jb_media_screen.vert", "jb_media_render.frag", "media render", wet = true, atlasDefs = ATLAS_NBR,
            ownReach = LOOK_NEAR_REACH, ownDefine = "JB_ATLAS_NEAR"),
        ProgramSpec({ progBake = it }, "jb_media_quad.vert", "jb_media_bake.frag", "media bake", atlasDefs = ATLAS_NBR),
        ProgramSpec({ progWetDab = it }, "jb_wet_dab.vert", "jb_wet_dab.frag", "media wet dab", wet = true),
        ProgramSpec({ progFlux = it }, "jb_media_quad.vert", "jb_wet_flux.frag", "media wet flux", wet = true, atlasDefs = ATLAS_NBR,
            ownReach = 128 /* none until set each frame ([fluxReach]) */, ownDefine = "JB_ATLAS_NEAR"),
        ProgramSpec({ progUpdate = it }, "jb_media_quad.vert", "jb_wet_update.frag", "media wet update", wet = true, atlasDefs = ATLAS_NBR,
            ownReach = UPDATE_REACH),
        ProgramSpec({ progPasteDab = it }, "jb_media_quad.vert", "jb_paste_dab.frag", "media paste dab", atlasDefs = ATLAS_NBR,
            ownReach = PASTE_DAB_REACH),
        ProgramSpec({ progPasteBrush = it }, "jb_media_quad.vert", "jb_paste_brush.frag", "media paste brush", plain = true, atlasDefs = ATLAS_NBR5),
        ProgramSpec({ progBead = it }, "jb_media_quad.vert", "jb_wet_bead.frag", "media wet bead", wet = true, atlasDefs = ATLAS_NBR,
            ownReach = BEAD_REACH),
        ProgramSpec({ progEraseDab = it }, "jb_media_erase_dab.vert", "jb_media_erase_dab.frag", "media erase dab"),
        ProgramSpec({ progErase = it }, "jb_media_quad.vert", "jb_media_erase_apply.frag", "media erase"),
        ProgramSpec({ progActivity = it }, "jb_media_screen.vert", "jb_wet_activity.frag", "media wet activity", wet = true),
    ) + (if (!atlasMode) emptyList() else listOf(
        // The quadrant reads pay nothing for walls; these do, and only run while one exists (jb_media_atlas.glsl).
        ProgramSpec({ progFluxW = it }, "jb_media_quad.vert", "jb_wet_flux.frag", "media wet flux (walls)", wet = true, atlasDefs = ATLAS_NBR_WALLS),
        ProgramSpec({ progUpdateW = it }, "jb_media_quad.vert", "jb_wet_update.frag", "media wet update (walls)", wet = true, atlasDefs = ATLAS_NBR_WALLS),
        ProgramSpec({ progBeadW = it }, "jb_media_quad.vert", "jb_wet_bead.frag", "media wet bead (walls)", wet = true, atlasDefs = ATLAS_NBR_WALLS),
    )) + (if (!coarseWater) emptyList() else listOf(
        // Coarse water: each pixel's share of a water step (it reads only its own pixel and its own cell), and the ground under
        // each cell once a frame (its own four pixels): both the OWN build alone, every slot in one draw.
        ProgramSpec({ progWetPaint = it }, "jb_media_quad.vert", "jb_wet_paint.frag", "media wet paint", wet = true, atlasDefs = ATLAS_OWN),
        ProgramSpec({ progWetTerrain = it }, "jb_media_quad.vert", "jb_wet_terrain.frag", "media wet terrain", wet = true, atlasDefs = ATLAS_OWN),
    ))
    private class ProgramSpec(val set: (MediaProgram) -> Unit, val vert: String, val frag: String, val label: String,
                              /** Reads the water: built with JB_WET_COARSE when the engine's water is coarse. */ val wet: Boolean = false,
                              val plain: Boolean = false,
                              /** More defines for the atlas build only (the look's JB_ATLAS_NBR). */ val atlasDefs: List<String> = emptyList(),
                              /** >= 0: how far the pass reads (full-res layer px): its tiles' insides are drawn by its OWN build. */
                              val ownReach: Int = -1,
                              /** The twin's define: JB_ATLAS_OWN, or JB_ATLAS_NEAR (its far reads keep the quadrant address). */
                              val ownDefine: String = "JB_ATLAS_OWN")

    /** The programs compiled so far, in [programTable]'s order (what [releasePrograms] deletes). */
    private val programs = ArrayList<MediaProgram>()

    /**
     * Compiles the next media program; false once all are compiled. A canvas calls it once per idle moment after a drawing
     * opens (MediaCanvas.prewarmStep), so the first pencil, watercolour or oil stroke no longer stalls for all of them at once
     * (the device matrix measured a 1012 ms first frame on the Note 9). [init] compiles whatever is left. No textures here.
     */
    fun compileNext(): Boolean {
        val spec = programTable.getOrNull(programs.size) ?: return false
        val defs = (if (atlasMode) listOf("JB_ATLAS") + spec.atlasDefs else emptyList()) + (if (coarseWater && spec.wet) listOf("JB_WET_COARSE") else emptyList())
        val made = MediaProgram(shaders, "media/${spec.vert}", "media/${spec.frag}", spec.label, if (spec.plain && atlasMode) defs + "JB_PLAIN_TARGET" else defs,
            if (atlasMode && !atlasBroken) spec.ownReach else -1, spec.ownDefine)
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
    // The brush input, twice (tasks/joybrush/specs/WATER_JOBS_20261021.md): new dabs go into buffer [inNext], a water job eats
    // the other. Buffer b: inTex[2b] = (dw mm, dK rgb), inTex[2b + 1] = (dS, solubility weight).
    private var inTex = IntArray(0)
    private var inFbos = IntArray(0)
    private var inNext = 0
    /** Where buffer b holds dabs (layer px), null = none. */
    private val inArea = arrayOfNulls<FloatArray>(2)
    /** Buffer b may hold dabs anywhere (drawn with no rectangle to clip to): its clear is whole. */
    private val inAll = BooleanArray(2)
    /** Coarse water: the water mid-step for the paint step (jb_wet_coarse.glsl), and the ground under each cell. */
    private var mid0 = 0; private var mid1 = 0; private var terrain = 0; private var terrainFbo = 0
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
        if (atlasMode) atlas = MediaAtlasGl(w / MediaAtlasDraw.SIDE, h / MediaAtlasDraw.SIDE, quadBuf, atlasBroken).also { it.readScale = maxOf(1f, scale) }
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
        finishWaterJob()
        val tex = ArrayList<Int>()
        for (s in state) tex += listOf(s.p0, s.p1, s.paper)
        wet?.forEach { tex += listOf(it.w0, it.w1, it.flux) }
        tex += listOf(delta, bakeTex, fluidBake, waterBake, run, lookTex, groundTex, mid0, mid1, terrain) + bead.toList() + inTex.toList()
        tex.filter { it != 0 }.toIntArray().let { if (it.isNotEmpty()) MediaTex.deleteTex(*it) }
        val fbos = ArrayList<Int>()
        for (s in state) fbos += s.fbo
        fbos += listOf(pFbo0, pFbo1, deltaFbo, bakeFbo, lookFbo, groundFbo, terrainFbo) + inFbos.toList() + beadFbo.toList() + fluxFbo.toList() + updFbo.toList() +
            wetCopyFbo.toList() + eraseWetFbo.toList()
        fbos.filter { it != 0 }.toIntArray().let { if (it.isNotEmpty()) MediaTex.deleteFbo(*it) }
        eraseWetFbo = IntArray(0)
        groundTex = 0; groundFbo = 0
        delta = 0; deltaFbo = 0; bakeTex = 0; fluidBake = 0; waterBake = 0; bakeFbo = 0; bakedPaper = null
        wet = null; run = 0; inTex = IntArray(0); inFbos = IntArray(0); forgetInput(); job = null; lookTex = 0; lookFbo = 0
        mid0 = 0; mid1 = 0; terrain = 0; terrainFbo = 0
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
        clearFbo(bakeFbo)   // the atlas's zero slot must read as zero in every texture (and made textures start undefined)
    }

    private fun ensureWet() {
        if (wet != null) return
        ensureBake()
        // The water's textures are [ww] x [wh] cells (a quarter of the pixels with coarse water).
        val ws = Array(2) { Wet(MediaTex.full(ww, wh), MediaTex.full(ww, wh), MediaTex.make(ww, wh)) }
        wet = ws
        // The running water's outflow is read as .xy only (jb_wet_update.frag), so two half-float channels.
        run = MediaTex.make(ww, wh, GLES30.GL_RG16F, GLES30.GL_RG, GLES30.GL_HALF_FLOAT)
        beadW = (w + 1) / 2; beadH = (h + 1) / 2
        bead = IntArray(2) { MediaTex.make(beadW, beadH) }
        beadFbo = IntArray(2) { MediaTex.fbo(bead[it]) }
        for (f in beadFbo) clearFbo(f)
        fluxFbo = IntArray(2) { MediaTex.fbo(ws[it].flux, run) }
        // One per (water buffer written, state buffer written): the paint planes ping-pong with the substeps (see wetFrame).
        // Coarse water: one per water buffer written (the water and the mid-step water for jb_wet_paint.frag, which writes
        // the paint through pFbo0 / pFbo1); the mid-step pair is full float, so each pixel reads exactly what its cell summed.
        updFbo = if (coarseWater) {
            mid0 = MediaTex.full(ww, wh); mid1 = MediaTex.full(ww, wh)
            terrain = MediaTex.make(ww, wh, filter = GLES30.GL_NEAREST); terrainFbo = MediaTex.fbo(terrain); clearFbo(terrainFbo)
            IntArray(2) { MediaTex.fbo(ws[it].w0, ws[it].w1, mid0, mid1) }
        } else IntArray(4) { MediaTex.fbo(ws[it shr 1].w0, ws[it shr 1].w1, state[it and 1].p0, state[it and 1].p1) }
        wetCopyFbo = IntArray(2) { if (it == 0) MediaTex.fbo(ws[0].w0, ws[0].w1, ws[0].flux, run) else MediaTex.fbo(ws[1].w0, ws[1].w1, ws[1].flux) }
        // Plane 1 holds two values (dS, the solubility weight): two half-float channels.
        inTex = IntArray(4) { if (it % 2 == 0) MediaTex.make(ww, wh) else MediaTex.make(ww, wh, GLES30.GL_RG16F, GLES30.GL_RG, GLES30.GL_HALF_FLOAT) }
        inFbos = IntArray(2) { MediaTex.fbo(inTex[2 * it], inTex[2 * it + 1]) }
        for (f in wetCopyFbo) clearFbo(f)
        for (f in inFbos) clearFbo(f)
        forgetInput()
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
        job = null
        for (s in state) clearFbo(s.fbo)
        if (groundFbo != 0) clearFbo(groundFbo)
        if (deltaFbo != 0) clearFbo(deltaFbo)
        if (wet != null) { for (f in wetCopyFbo) clearFbo(f); for (f in inFbos) clearFbo(f); forgetInput() }
        wetRect = null
        paintRect = null
        wetIdle = 0.0
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** The layer window's placement, written before each pass's own uniforms ([MediaUniforms.common]). */
    private fun common(u: UniformBank) { MediaUniforms.common(u, originX, originY, scale); atlas?.uniforms(u) }
    /** [common] for a pass over the water's cells: its layer is [wf] times coarser, its slots at [wShift]. */
    private fun commonWater(u: UniformBank) { MediaUniforms.common(u, originX, originY, scale / wf); atlas?.uniforms(u, wShift) }

    /**
     * The wet frame's constants for cells [wf] pixels wide: a cell's size in mm and a bead's in cells; and the rates given per
     * cell² (the levelling flow, the wick, the mingling) slowed by [wf]², so the water spreads as far per second whatever
     * its cell (a coarse cell is wf² times the area: the same per-cell rate would be wf² times faster across the paper).
     */
    private fun loadWetFrame(paper: MediaPaperGl, wc0: WetConstants, evapOverride: Double?, pxPerMm: Double) {
        wetFrameUniforms.load(wetUniforms(paper.stats, wc0) + ("u_gran" to wetGran), evapOverride, slope, wf / (pxPerMm * scale), wc0.beadMm * pxPerMm * scale / wf)
        if (wf > 1) for (n in listOf("u_flow", "u_wick", "u_mingle")) wetFrameUniforms.scale(n, 1.0 / (wf * wf))
    }

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
    class Need(val rect: FloatArray, val reach: Int, val water: Boolean = false, val input: Boolean = false,
               /** [input]: the dabs' own box (layer px): where the look changes this frame (WATER_JOBS_20261021). */
               val lookRect: FloatArray? = null)

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
        if (b == null || b.count == 0) null else layerRect(b.dirty, 48.0)?.let { Need(it, MediaAtlasPlacement.WET_REACH, input = true, lookRect = layerRect(b.dirty, 2.0)) }
    }
    /** The engine's texture width (layer px at full resolution): the atlas's slots × 256 across. */
    val textureWidth: Int get() = w
    /** How many slots the atlas hands out (B2: one of them is staging). The last physical slot is the ZERO slot, never a tile's. */
    val slotCount: Int get() = (w / MediaAtlasDraw.SIDE) * (h / MediaAtlasDraw.SIDE) - 1
    /** Slots per atlas row (MediaAtlasSlots keeps a frame's pages together by it). */
    val slotColumns: Int get() = w / MediaAtlasDraw.SIDE
    fun slotTexel(slot: Int): Pair<Int, Int> = requireNotNull(atlas) { "not an atlas engine" }.slotTexel(slot)
    fun setResidency(map: Map<Long, Int>) { requireNotNull(atlas) { "not an atlas engine" }.setResidency(map) }
    fun toLayerPx(docX: Double, docY: Double): Pair<Float, Float> = ((docX - originX) * scale).toFloat() to ((docY - originY) * scale).toFloat()
    /** Same-format copy of layer-px [x0, y0, x1, y1) from [src] to [dst] (two textures of this engine): window, or slot by slot. */
    private fun blitLayer(src: Int, dst: Int, x0: Int, y0: Int, x1: Int, y1: Int, shift: Int = MediaAtlasDraw.SHIFT) {
        val a = atlas
        if (!free || a == null) { blit(src, x0, y0, x1, y1, dst, x0, y0, x1, y1); return }
        val down = MediaAtlasDraw.SHIFT - shift
        a.forEachPart(floatArrayOf(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat()), shift) { slot, tx, ty, cx0, cy0, cx1, cy1 ->
            val (ax, ay) = a.slotTexel(slot)
            val ox = (ax shr down) - (tx shl shift); val oy = (ay shr down) - (ty shl shift)
            blit(src, cx0 + ox, cy0 + oy, cx1 + ox, cy1 + oy, dst, cx0 + ox, cy0 + oy, cx1 + ox, cy1 + oy)
        }
    }
    /** Empties one slot of every slot-shaped target (a tile about to be loaded there starts from rest, as a new window did). */
    fun clearSlot(ax: Int, ay: Int) {
        val side = MediaAtlasDraw.SIDE
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glScissor(ax, ay, side, side)
        for (f in listOf(state[0].fbo, state[1].fbo, groundFbo, deltaFbo, lookFbo)) {
            if (f == 0) continue
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glScissor(ax / wf, ay / wf, side / wf, side / wf)   // the water's own resolution
        for (f in inFbos.toList() + wetCopyFbo.toList()) {
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
    /** Layer px [r] in the water's cells (floor, ceil): the same rectangle with coarse water off. */
    private fun waterRect(r: FloatArray): FloatArray = if (wf == 1) r else
        floatArrayOf(floor(r[0] / wf), floor(r[1] / wf), ceil(r[2] / wf), ceil(r[3] / wf))
    private val fullRect = FloatArray(4)
    private fun full(): FloatArray { fullRect[0] = 0f; fullRect[1] = 0f; fullRect[2] = w.toFloat(); fullRect[3] = h.toFloat(); return fullRect }

    /** Instanced dabs: window = one draw of [batch]; atlas = each dab once per slot its box (a doc-px [reach] round its centre) touches. */
    private fun dabs(batch: DabBatch, clip: FloatArray? = null, water: Boolean = false, reach: (FloatArray, Int) -> Float) {
        val a = atlas
        if (a != null) {
            // [water]: the dabs land on the water's cells ([wf] px each, slots at [wShift]); [clip] is in those cells.
            val sc = if (water) scale / wf else scale
            val shift = if (water) wShift else MediaAtlasDraw.SHIFT
            val box = { d: FloatArray, i: Int, out: FloatArray ->
                val o = i * DabBatch.FLOATS
                val cx = (d[o] - originX) * sc; val cy = (d[o + 1] - originY) * sc
                val r = reach(d, o) * sc + 2f
                out[0] = cx - r; out[1] = cy - r; out[2] = cx + r; out[3] = cy + r
            }
            // Wet input: each slot under its own scissor. One draw clipped in the shader instead cost the tilted Wash 70 ms a
            // frame against 10 (2026-10-20): unscissored, a blended pass makes this GPU load and store its whole target.
            if (free && clip != null) a.drawDabs(batch, box, clip, shift) else a.drawDabs(batch, box, shift)
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

    /**
     * A stored tile of [store] ([src], 256²) into this engine at (x, y), its first texel at full resolution (window px, or an
     * atlas slot's origin). Coarse water lands on its cells: each the mean of its 2 x 2 pixels (a linear blit at exact
     * centres); the round trip with [writeStoreTile] gives back what it got.
     */
    fun loadStoreTile(store: String, src: Int, x: Int, y: Int) {
        val t = Tiles.SIZE
        val water = store in WATER_STORES && wf > 1
        for (dst in loadTargets(store)) {
            if (water) blit(src, 0, 0, t, t, dst, x / wf, y / wf, (x + t) / wf, (y + t) / wf, GLES30.GL_LINEAR)
            else blit(src, 0, 0, t, t, dst, x, y, x + t, y + t)
        }
    }

    /** [store]'s current state at (x, y) (see [loadStoreTile]) into the stored tile [dst]; coarse water fills each cell's pixels. */
    fun writeStoreTile(store: String, dst: Int, x: Int, y: Int) {
        finishWaterStep()   // a finished step is a whole state ([current] reads it)
        val src = current(store) ?: return
        val t = Tiles.SIZE
        if (store in WATER_STORES && wf > 1) blit(src, x / wf, y / wf, (x + t) / wf, (y + t) / wf, dst, 0, 0, t, t)
        else blit(src, x, y, x + t, y + t, dst, 0, 0, t, t)
    }

    /** The texture a store's current state is read from, for a write-back. Null for water that was never made. */
    fun current(store: String): Int? = when (store) {
        "p0" -> finishedPaint().p0; "p1" -> finishedPaint().p1; "paper" -> state[0].paper
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
        finishWaterJob()   // it reads or writes what a water job does
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(x0, y0, x1 - x0, y1 - y0)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        for (f in listOf(state[0].fbo, groundFbo)) {
            if (f == 0) continue
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glScissor(x0 / wf, y0 / wf, (x1 - x0 + wf - 1) / wf, (y1 - y0 + wf - 1) / wf)   // the water's own resolution
        for (f in wetCopyFbo) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Stops the water where it is (undo while wet, the Lead's rule): the state stays, nothing runs until [resumeWater]. */
    fun stopWater() {
        // Undo (or the layer going): the state is about to be replaced, so the job is dropped, not finished, and the brush
        // input still waiting goes too (it belonged to what was undone).
        job = null
        if (inFbos.isNotEmpty()) { for (f in inFbos) clearFbo(f); forgetInput() }
        wetRect = null; paintRect = null; wetIdle = 0.0
        wet?.let { ws -> for (x in ws) { val f = MediaTex.fbo(x.flux); clearFbo(f); MediaTex.deleteFbo(f) } }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Lets loaded water run again over [rect] (window px): a reopened wet drawing, or a new stroke into sleeping water. */
    fun resumeWater(rect: FloatArray) {
        ensureWet()
        wetRect = union(wetRect, rect); paintRect = union(paintRect, rect); wetIdle = 0.0
        dabsSinceGrow = true   // not grown for its water yet: the next job grows it
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
        finishWaterJob()   // it reads or writes what a water job does
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
        finishWaterJob()   // it reads or writes what a water job does
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
        finishWaterJob()   // it reads or writes what a water job does
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
            // Over the water's cells (coarse water: each cell takes the mean of its four pixels' coverage).
            val cells = waterRect(rect)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, eraseWetFbo[other])
            GLES30.glViewport(0, 0, ww, wh)
            val wu = progErase.use()
            commonWater(wu); MediaUniforms.erase(wu, ws[wc].w0, ws[wc].w1, ws[wc].w0, delta, 1, ww, wh, cells)
            wu.f1("u_wf", wf)
            quad(cells, wShift)
            val (x0, y0, x1, y1) = intArrayOf(floor(cells[0]).toInt(), floor(cells[1]).toInt(), ceil(cells[2]).toInt(), ceil(cells[3]).toInt()).let { listOf(it[0], it[1], it[2], it[3]) }
            blitLayer(ws[other].w0, ws[wc].w0, x0, y0, x1, y1, wShift)
            blitLayer(ws[other].w1, ws[wc].w1, x0, y0, x1, y1, wShift)
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
        // (A water job keeps the tiles skipped and walled when it started: [runJob].)
        wetFrameInner(batch, paper, pxPerMm, wc0, substeps, evapOverride, maxWetSeconds)
    }

    /**
     * The newest wet brush's granulation (WetBrush.gran, 0..2): how much more its pigment settles in the paper's fine dips
     * than on its tops. The canvas sets it as each wet stroke starts; the water keeps it while it runs on. Until 10-21 it
     * never reached the shaders (u_gran stayed 0): the lab passed it, the app's port did not, so nothing granulated.
     */
    var wetGran = 0.0

    /** Tiles out of the water's simulation (Media Atlas v2 B3, set by the host): the water passes draw nothing there. */
    var waterSkip: Set<Long> = emptySet()

    /**
     * How long the frame being drawn took to come round on the clock (ms; the canvas sets it, 0 = unknown). The water runs at
     * a fixed step whatever the phone manages, so a slow frame leaves it behind the clock; its wet age is kept in real time
     * from this ([wetFrame]), so the older-water-dries-faster rule (WetConstants.dryAfter) means seconds on the clock.
     */
    var frameRealMs = 0f

    /** Tiles of whole pools set aside while the pen works elsewhere (MediaAtlasWater.schedule): left exactly as they are. */
    var waterPaused: Set<Long> = emptySet()

    /**
     * Pools just set aside keep their state exactly: both halves of every ping-pong pair get the current values at these
     * atlas slots (texel origins), so the frames they sit out, some of an odd number of water steps (the brush-first frame
     * takes one), cannot leave them reading a half that is a step behind when they run again.
     */
    fun holdStill(slotOrigins: List<Pair<Int, Int>>) {
        finishWaterJob()   // it reads or writes what a water job does
        val ws = wet ?: return
        val s = MediaAtlasDraw.SIDE
        val a = ws[wc]; val b = ws[1 - wc]
        for ((x, y) in slotOrigins) {
            for ((src, dst) in listOf(a.w0 to b.w0, a.w1 to b.w1, a.flux to b.flux))
                blit(src, x / wf, y / wf, (x + s) / wf, (y + s) / wf, dst, x / wf, y / wf, (x + s) / wf, (y + s) / wf)
            for ((src, dst) in listOf(state[0].p0 to state[1].p0, state[0].p1 to state[1].p1, state[0].paper to state[1].paper))
                blit(src, x, y, x + s, y + s, dst, x, y, x + s, y + s)
        }
    }

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
        if (batch != null && batch.count > 0) {
            val r = layerRect(batch.dirty, 48.0)
            if (r != null) { wetRect = union(wetRect, r); paintRect = union(paintRect, r) }
            // Only the simulated rectangle ever reads the brush input, so the dabs are drawn there and the input is cleared
            // there after the step that eats it (the full-window clear wrote 16 B/px of nothing). Where there is no rectangle
            // to clip to, the dabs are drawn whole and that buffer's next clear is whole too, exactly as before.
            val inRect = simRect()
            inRect?.let { needWater(it, MediaAtlasPlacement.WET_REACH) }
            // The brush input lands on the water's cells ([ww] x [wh]; the layer's pixels without coarse water), in the buffer
            // no running job reads ([inNext]).
            val inCells = inRect?.let(::waterRect)
            val ib = inNext
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, inFbos[ib])
            GLES30.glViewport(0, 0, ww, wh)
            if (inCells != null && !free) scissorTo(inCells)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
            val du = progWetDab.use()
            commonWater(du); MediaUniforms.wetDab(du, ws[wc].w0, bakeTex, ww, wh, pxPerMm, wc0.fullMm)
            // half-length, half-width (mm) at floats 4-5, plus the vertex shader's 0.08 mm.
            val ppm = pxPerMm.toFloat()
            mediaTrace("JB.media.wet.input") { dabs(batch, inCells, water = true) { d, o -> (hypot(d[o + 4], d[o + 5]) + 0.1f) * ppm } }
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            if (inRect != null) inArea[ib] = union(inArea[ib], inRect) else inAll[ib] = true
            wetIdle = 0.0
            dabsSinceGrow = true
        }
        val s0 = waterSteps
        var grown = false
        if (job == null) {
            // On a tilted paper running water can leave the rectangle: it grows downhill as fast as water can run, never
            // further than a long drip past the paint, nor past this window (WetSpread, the tested cap).
            val rect = simRect() ?: return
            startJob(rect, steps, paper, pxPerMm, wc0, evapOverride, maxWetSeconds, grown = true)
            grown = true
        }
        var left = waterBudget - runJob(waterBudget)
        // A sliced job is two steps. With the pen up ([catchUp]) and while the frame's budget lasts, the next starts at once, up
        // to the steps a whole frame ran before jobs (owner, 2026-10-23: one job a frame ran the water at half its old speed;
        // tilting no longer made it race). Not while a stroke paints: on the Note 9 that cost wet brushes 25-30 % slower frames
        // and slowed oil and pencil strokes too (water left by earlier strokes runs under them).
        // The atlas admitted this call's water grown once by [steps] steps (wetNeed): a job reuses the rectangle while the steps
        // run on it since it grew fit in that growth and no dabs came since; otherwise it grows, once a call.
        while (catchUp && job == null && left > 0.0 && waterBudget.isFinite() && waterSteps - s0 < steps) {
            val ran = jobSteps(steps, sliced = true)
            val reuse = !dabsSinceGrow && rectSteps + ran <= steps
            if (!reuse && grown) break
            val rect = (if (reuse) wetRect else simRect()) ?: break
            startJob(rect, steps, paper, pxPerMm, wc0, evapOverride, maxWetSeconds, grown = !reuse)
            if (!reuse) grown = true
            left -= runJob(left, atLeastOne = false)
        }
    }

    // ---- water jobs (tasks/joybrush/specs/WATER_JOBS_20261021.md) ----
    /**
     * A job: [steps] water steps over [rect] (layer px; [cells] in the water's cells), its constants fixed when it starts, run
     * stage by stage and band by band ([runJob]) as far as each frame's budget goes. Every pass writes the other half of its
     * ping-pong pair, so what anything outside the job reads (the look, the next frame's input) is always a finished step:
     * the water flips ([wc]) when a step's last pass is done. [inIdx]: the brush input buffer it eats in its first step.
     */
    private class WaterJob(val rect: FloatArray, val cells: FloatArray, val steps: Int, val wc0: WetConstants, val beadCells: Double,
                           val inIdx: Int, val useInput: Boolean, val ageScale: Float, val paper: MediaPaperGl,
                           val pxPerMm: Double, val maxWetSeconds: Double, val stages: List<Int>, val hr: FloatArray?, val sigma: Double,
                           val skip: Set<Long>, val walls: Set<Long>, val sliced: Boolean) {
        var k = 0; var stage = 0; var band = 0
    }
    private var job: WaterJob? = null
    private var lastJobNs = 0L
    /** Real time (ms) the water's age has not yet taken in; below 0, steps that ran ahead of the clock. */
    private var ageOweMs = 0f
    /** Steps started on [wetRect] since it last grew, and whether dabs landed since: what a job may reuse it for. */
    private var rectSteps = 0
    private var dabsSinceGrow = false

    /**
     * The water's work this frame, in px x stage weight ([JOB_WEIGHT]): a job runs bands until it is spent (at least one).
     * Infinite (the window, the device probe, every engine until a host sets it) = a job runs whole in the frame it starts,
     * exactly as the frames before jobs: the same passes in the same order.
     */
    var waterBudget = Double.POSITIVE_INFINITY
    /** The pen is up (the host's frame has no stroke): the water may run more than one job a frame ([wetFrameInner]). */
    var catchUp = false
    /** A sliced job's steps (2, even: no paint copy); 0 = the frame's own (the device probe's banded engine, to compare). */
    internal var slicedSteps = 2
    /** A water job is under way (its rectangle must stay resident: [waterJobRect]). */
    val waterJobRunning get() = job != null
    val waterJobRect: FloatArray? get() = job?.rect
    /** Counts finished water steps (the host redraws the water's look when it moves). */
    var waterSteps = 0L; private set
    /** Some brush input is not yet in the water (the look shows it: [renderLook]). */
    val inputPending get() = inArea[0] != null || inArea[1] != null || inAll[0] || inAll[1]

    private fun forgetInput() { inArea[0] = null; inArea[1] = null; inAll[0] = false; inAll[1] = false; inNext = 0 }

    /**
     * A job's steps. The atlas has no window to stop the water, so a wide wash can ask for more than the window ever drew in a
     * frame (2026-10-21, tilted Wash on the Note 9: up to 18.9 M px-steps against the window's 4.2 M, frames of 3 s). Past
     * that, a whole-frame job takes fewer steps. A sliced job (a host's budget) takes two whatever its size: the budget
     * spreads it over frames, and two (even) end in state[0] with no copy.
     */
    private fun jobSteps(steps: Int, sliced: Boolean, area: Double = 0.0): Int = when {
        sliced && steps > 1 && slicedSteps > 0 -> slicedSteps
        free && area * steps > WET_PX_STEPS -> max(1, (WET_PX_STEPS / area).toInt())
        else -> steps
    }

    /** [grown]: [rect] is [wetRect] grown for this job ([wetFrameInner]); otherwise the job reuses the last growth. */
    private fun startJob(rect: FloatArray, steps: Int, paper: MediaPaperGl, pxPerMm: Double, wc0: WetConstants, evapOverride: Double?,
                         maxWetSeconds: Double, grown: Boolean) {
        wetRect = rect
        val sliced = waterBudget.isFinite()
        val area = (rect[2] - rect[0]).toDouble() * (rect[3] - rect[1]) / (wf * wf)   // in water cells
        val ran = jobSteps(steps, sliced, area)
        if (grown) { rectSteps = ran; dabsSinceGrow = false } else rectSteps += ran
        MediaPhases.timer?.invoke("wet.kpx (count)", area / 1000.0 * ran)
        // Wet age in real time: a whole-frame job stands for its frame. Sliced jobs take in the real time since the last one
        // started, as owed time: a frame running two jobs ages by its real time, not by it plus the second job's steps.
        val now = System.nanoTime()
        val simMs = (ran * wc0.dt * 1000).toFloat()
        // No clock from the host (frameRealMs 0: the device probe, a bench replay): the water's own time, as always.
        val ageScale = when {
            frameRealMs <= 0f -> 1f
            sliced -> {
                // At most 2 s and 60x the job's steps, as before: the rest of a long pause is let go, not carried.
                ageOweMs = min(min(2000f, 60f * simMs), ageOweMs + if (lastJobNs != 0L) (now - lastJobNs) / 1e6f else frameRealMs)
                val s = max(1f, ageOweMs / simMs)
                // Ahead of the clock by at most one frame's steps, so a slower stretch is aged in real time again at once.
                ageOweMs = max(-steps * wc0.dt.toFloat() * 1000f, ageOweMs - simMs * s)
                s
            }
            else -> (frameRealMs / simMs).coerceIn(1f, 60f)
        }
        lastJobNs = now
        stores += WATER_WRITES
        touch(rect)
        wetSince = union(wetSince, rect)
        val beadCells = wc0.beadMm * pxPerMm * scale
        // The job's constants, flattened once; its passes only apply them (unchanged values cost no GL call).
        loadWetFrame(paper, wc0, evapOverride, pxPerMm)
        val cells = waterRect(rect)
        val tilted = hypot(slope[0].toDouble(), slope[1].toDouble()) > 1e-4
        GLES30.glDisable(GLES30.GL_BLEND)
        // The paint planes (p0, p1) used to be written to state[1] by every update and copied back to state[0] by a
        // whole extra pass. The update reads them at its OWN pixel only, so instead the substeps alternate: substep k reads
        // state[k mod 2] and writes the other, and an even number of substeps ends in state[0] with no copy.
        // The flux pass also reads p0.a at the neighbours (the water's surface, and the paper's slope d cells away when
        // tilted); inside the rectangle those are the previous substep's output, and beyond it the paint never changes,
        // so state[0]'s values for the ring around the rectangle are copied into state[1] once, before the first substep.
        needWater(rect, MediaAtlasPlacement.WET_REACH)
        // Coarse water: the flux reads the ground from the terrain (once a job, as far round as it reads), not the paint, so
        // there is no paint ring to keep in step.
        if (wf > 1) mediaTrace("JB.media.wet.terrain") { terrainPass(cells, fluxReach(wc0, beadCells / wf) + 2, paper) }
        else if (ran > 1) mediaTrace("JB.media.wet.ring") { copyP0Ring(rect, ringCells(wc0, beadCells)) }
        // The running bead's field (tilted): 2x2 average of the water, then a Gaussian along x and y, on the half grid.
        val sigma = min(4.8, max(1.0, 0.25 * beadCells))
        val hr = if (!tilted) null else {
            val r = min(12, ceil(2.5 * sigma).toInt())
            val m = 3 + 2 * r
            if (free) floatArrayOf(floor(rect[0] / 2) - m, floor(rect[1] / 2) - m, ceil(rect[2] / 2) + m, ceil(rect[3] / 2) + m)
            else floatArrayOf(max(0f, floor(rect[0] / 2) - m), max(0f, floor(rect[1] / 2) - m),
                min(beadW.toFloat(), ceil(rect[2] / 2) + m), min(beadH.toFloat(), ceil(rect[3] / 2) + m))
        }
        val stages = ArrayList<Int>()
        if (tilted) { stages += STAGE_BEAD0; stages += STAGE_BEAD1; stages += STAGE_BEAD2 }
        stages += STAGE_FLUX; stages += STAGE_UPDATE
        if (wf > 1) stages += STAGE_PAINT
        // The job eats the input drawn so far; dabs from now on go to the other buffer.
        val ib = inNext
        inNext = 1 - ib
        job = WaterJob(rect, cells, ran, wc0, beadCells, ib, inArea[ib] != null || inAll[ib], ageScale, paper, pxPerMm,
            maxWetSeconds, stages, hr, sigma, waterSkip + waterPaused, waterSkip, sliced)
    }

    /** Runs every pass the running job has left, now (anything that reads or writes what the job does calls it first). */
    fun finishWaterJob() { if (job != null) runJob(Double.POSITIVE_INFINITY) }

    /**
     * Runs the running job's passes up to the end of its current step (nothing when it stands between steps): a finished
     * step is a whole state, the water in [wc] and the paint in [finishedPaint]. What a stroke's stores need, at most a step.
     */
    fun finishWaterStep() { if (job != null) runJob(Double.POSITIVE_INFINITY, toStep = true) }

    /** The paint as the last finished step left it: state[k mod 2] for a job about to run step k, state[0] otherwise. */
    private fun finishedPaint(): State = job?.let { state[it.k and 1] } ?: state[0]

    /**
     * The water caught up with the brush: the running job finished and the input still waiting taken in by one more whole
     * job, so the state holds every dab (a stroke's end: what its stores keep, and what undo goes back to).
     */
    fun settleInput(paper: MediaPaperGl, pxPerMm: Double, wc0: WetConstants) {
        finishWaterJob()
        if (!inputPending || wet == null || wetRect == null) return
        val budget = waterBudget
        waterBudget = Double.POSITIVE_INFINITY
        try { wetFrame(null, paper, pxPerMm, wc0) } finally { waterBudget = budget }
    }

    /**
     * Runs the job's stages, band by band, until [budget] (px x [JOB_WEIGHT]) is spent; at least one band unless not
     * [atLeastOne] (a job started on what a frame has left). Returns what it spent.
     */
    private fun runJob(budget: Double, toStep: Boolean = false, atLeastOne: Boolean = true): Double {
        val j = job ?: return 0.0
        if (wet == null) { job = null; return 0.0 }
        val ws = wet!!
        // B3: tiles the water left (a drip's dried tail, MediaAtlasWater.excluded) or set aside (paused pools) are not
        // simulated; the water beside a tile it left sees a wall there (zero gradient, no flow), as at the window's edge.
        val a = atlas
        if (a != null) { a.skip = j.skip; a.setWalls(j.walls); a.wallsOn = j.walls.isNotEmpty() }
        try { return runJobInner(j, ws, budget, toStep, atLeastOne) } finally { if (a != null) { a.skip = emptySet(); a.wallsOn = false } }
    }

    private fun runJobInner(j: WaterJob, ws: Array<Wet>, budget: Double, toStep: Boolean, atLeastOne: Boolean): Double {
        if (toStep && j.stage == 0 && j.band == 0) return 0.0
        var spent = 0.0
        GLES30.glDisable(GLES30.GL_BLEND)
        while (true) {
            val stage = j.stages[j.stage]
            val (area, shift) = stageArea(j, stage)
            val bands = bandsOf(area, shift, j.sliced)
            val band = bands[j.band]
            val cost = bandCost(band, stage)
            if ((spent > 0.0 || !atLeastOne) && spent + cost > budget) return spent
            runStage(j, ws, stage, area, band)
            spent += cost
            if (++j.band < bands.size) continue
            j.band = 0
            if (++j.stage < j.stages.size) continue
            // A water step is done: the water flips, and the input it ate leaves its buffer.
            j.stage = 0
            wc = 1 - wc
            if (j.k == 0 && j.useInput) clearInput(j.inIdx)
            waterSteps++
            if (++j.k < j.steps) { if (toStep) return spent; continue }
            endJob(j)
            return spent
        }
    }

    private fun endJob(j: WaterJob) {
        job = null
        // An odd number of substeps (the dry-now frame) leaves the paint in state[1]: one copy, as every substep used to make.
        if ((j.steps and 1) == 1) mediaTrace("JB.media.wet.tail") { copyRect(state[1], pFbo0, j.rect) }
        wetIdle += j.steps * j.wc0.dt
        if (wetIdle > j.maxWetSeconds) dryNow(j.paper, j.pxPerMm)
    }

    private fun clearInput(ib: Int) {
        val r = inArea[ib]
        if (inAll[ib] || r == null) clearFbo(inFbos[ib])
        else if (free) atlas!!.clearRect(inFbos[ib], waterRect(r), wShift)
        else {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, inFbos[ib])
            GLES30.glViewport(0, 0, ww, wh)
            scissorTo(waterRect(r))
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        }
        inArea[ib] = null; inAll[ib] = false
    }

    /** The rectangle a stage draws over and its target's slot shift. */
    private fun stageArea(j: WaterJob, stage: Int): Pair<FloatArray, Int> = when (stage) {
        STAGE_BEAD0, STAGE_BEAD1, STAGE_BEAD2 -> j.hr!! to MediaAtlasDraw.SHIFT - 1
        STAGE_PAINT -> j.rect to MediaAtlasDraw.SHIFT
        else -> j.cells to wShift
    }

    /**
     * A stage's rectangle cut into bands of whole tile rows (of the stage's own grid) when the job is sliced in the atlas (its
     * passes draw slot by slot, so a band draws exactly the slots it covers); one band, the whole rectangle, otherwise (the
     * window's passes draw their rectangle through u_rect, which the flux also reads as the water's edge).
     */
    private fun bandsOf(r: FloatArray, shift: Int, sliced: Boolean): List<FloatArray> {
        if (!sliced || atlas == null) return listOf(r)
        val side = (1 shl shift).toFloat()
        val out = ArrayList<FloatArray>()
        var y0 = r[1]
        while (y0 < r[3]) {
            val y1 = min(r[3], (floor(y0 / side) + BAND_TILES) * side)
            out += floatArrayOf(r[0], y0, r[2], y1)
            y0 = y1
        }
        return if (out.isEmpty()) listOf(r) else out
    }

    private fun bandCost(b: FloatArray, stage: Int): Double = (b[2] - b[0]).toDouble() * (b[3] - b[1]) * JOB_WEIGHT[stage]

    /** One stage of the job's current step over [band] (the stage's uniforms describe its whole rectangle [area]). */
    private fun runStage(j: WaterJob, ws: Array<Wet>, stage: Int, area: FloatArray, band: FloatArray) {
        val k = j.k
        val a = ws[wc]
        val bi = 1 - wc
        val b = ws[bi]
        val src = state[k and 1]
        GLES30.glBindVertexArray(quadVao)
        when (stage) {
            STAGE_BEAD0, STAGE_BEAD1, STAGE_BEAD2 -> mediaTrace("JB.media.wet.bead") {
                // 0: 2x2 average + blur along x into bead[0]; 1: blur along x into bead[1]; 2: along y into bead[0].
                val pass = stage - STAGE_BEAD0
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, beadFbo[pass and 1])
                GLES30.glViewport(0, 0, beadW, beadH)
                val bu = beadProgram().use()
                common(bu)
                MediaUniforms.bead(bu, area, beadW, beadH, j.sigma, pass, a.w0, bead[1 - (pass and 1)], if (pass < 2) 1f else 0f, if (pass < 2) 0f else 1f)
                atlas?.uniforms(bu, MediaAtlasDraw.SHIFT - 1)   // the half-resolution field's slots are 128 cells
                quad(band, MediaAtlasDraw.SHIFT - 1)
            }
            STAGE_FLUX -> {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fluxFbo[bi])
                GLES30.glViewport(0, 0, ww, wh)
                val fu = fluxProgram().use()
                commonWater(fu); wetFrameUniforms.apply(fu)
                fu.ownReach = fluxReach(j.wc0, j.beadCells / wf) * wf   // the reach in full-resolution px
                MediaUniforms.wetFlux(fu, bead[0], src.p0, a.w0, a.flux, bakeTex, fluidBake, waterBake, area, ww, wh)
                if (wf > 1) fu.tex("u_terrain", terrain)
                mediaTrace("JB.media.wet.flux") { quad(band, wShift) }
            }
            STAGE_UPDATE -> {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, if (wf > 1) updFbo[bi] else updFbo[bi * 2 + 1 - (k and 1)])
                GLES30.glViewport(0, 0, ww, wh)
                val pu = updateProgram().use()
                commonWater(pu); wetFrameUniforms.apply(pu)
                pu.ownReach = UPDATE_REACH * wf + (wf - 1)   // a coarse cell also reads its own 2 x 2 pixels
                MediaUniforms.wetUpdate(pu, a.w0, a.w1, b.flux, run, src.p0, src.p1, bakeTex, waterBake, fluidBake,
                    inTex[2 * j.inIdx], inTex[2 * j.inIdx + 1], if (k == 0 && j.useInput) 1 else 0, area, ww, wh)
                MediaUniforms.wetAgeScale(pu, j.ageScale)
                mediaTrace("JB.media.wet.update") { quad(band, wShift) }
            }
            STAGE_PAINT -> mediaTrace("JB.media.wet.paint") { paintStep(src, if ((k and 1) == 0) pFbo1 else pFbo0, band) }
        }
    }

    /**
     * Coarse water: the ground under each cell of [cells] grown by [reach] cells (jb_wet_terrain.frag), from the paint as the
     * frame starts (state[0]) and the paper.
     */
    private fun terrainPass(cells: FloatArray, reach: Int, paper: MediaPaperGl) {
        val r = floatArrayOf(cells[0] - reach, cells[1] - reach, cells[2] + reach, cells[3] + reach)
        val g = if (free) r else floatArrayOf(max(0f, r[0]), max(0f, r[1]), min(ww.toFloat(), r[2]), min(wh.toFloat(), r[3]))
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, terrainFbo)
        GLES30.glViewport(0, 0, ww, wh)
        GLES30.glDisable(GLES30.GL_BLEND)
        val tu = progWetTerrain!!.use()
        commonWater(tu)
        tu.tex("u_p0", state[0].p0); tu.tex("u_waterBake", waterBake); tu.tex("u_fluidBake", fluidBake)
        tu.f1("u_toothMm", paper.stats.toothMm)
        tu.f4("u_rect", g); tu.f2("u_targetSize", ww.toFloat(), wh.toFloat())
        quad(g, wShift)
    }

    /**
     * Coarse water: each pixel's share of a water step (jb_wet_paint.frag), from the paint in [src] into [dstFbo] (the other
     * paint buffer, as the full-resolution update wrote it), over layer px [rect]. The update just wrote the mid-step water.
     */
    private fun paintStep(src: State, dstFbo: Int, rect: FloatArray) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dstFbo)
        GLES30.glViewport(0, 0, w, h)
        val qu = progWetPaint!!.use()
        common(qu); wetFrameUniforms.apply(qu)
        val ib = job?.inIdx ?: inNext
        qu.tex("u_mid0", mid0); qu.tex("u_mid1", mid1); qu.tex("u_in0", inTex[2 * ib]); qu.tex("u_in1", inTex[2 * ib + 1])
        qu.tex("u_p0", src.p0); qu.tex("u_p1", src.p1); qu.tex("u_paperBake", bakeTex); qu.tex("u_waterBake", waterBake)
        qu.f4("u_rect", rect); qu.f2("u_targetSize", w.toFloat(), h.toFloat())
        quad(rect)
    }

    /** Scissor to the whole pixels a layer-px rectangle can touch. */
    private fun scissorTo(r: FloatArray) {
        val x0 = max(0, floor(r[0]).toInt()); val y0 = max(0, floor(r[1]).toInt())
        val x1 = min(w, ceil(r[2]).toInt()); val y1 = min(h, ceil(r[3]).toInt())
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(x0, y0, max(0, x1 - x0), max(0, y1 - y0))
    }

    /**
     * How far the flux pass's NEAR reads go from its pixel (full-resolution cells), for the part of each tile its NEAR build
     * draws (MediaAtlasGl.drawSplit). From jb_wet_flux.frag: the paper slope d cells away, the bead and its slope (+-2,
     * read bilinearly at half resolution: +3), the neighbours (+-1); plus one for rounding. The drip taps, a drip gap and a
     * millimetre away, are jb_bilinearLSFar reads and keep the quadrant address.
     */
    private fun fluxReach(wc0: WetConstants, beadCells: Double): Int = max(max(2, (wc0.steerScale * beadCells).toInt()), 5) + 1

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

    /** Let everything dry where it lies (the Dry button, and the end of a long idle). */
    fun dryNow(paper: MediaPaperGl, pxPerMm: Double) {
        if (wet == null || wetRect == null) return
        finishWaterJob()
        val budget = waterBudget
        waterBudget = Double.POSITIVE_INFINITY   // in one go, as before jobs
        try { wetFrame(null, paper, pxPerMm, substeps = 1, evapOverride = 1e4, maxWetSeconds = 1e9) } finally { waterBudget = budget }
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
        named["run"] = run; bead.forEachIndexed { i, b -> named["bead$i"] = b }; inTex.forEachIndexed { i, t -> named["in$i"] = t }; named["look"] = lookTex
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
        finishWaterJob()
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
        for (r in rects) clearIn(fluxFbo[bi], r, water = true)
        loadWetFrame(paper, wc0, 1e4, pxPerMm)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, if (wf > 1) updFbo[bi] else updFbo[bi * 2 + 1])
        GLES30.glViewport(0, 0, ww, wh)
        val pu = updateProgram().use()
        commonWater(pu); wetFrameUniforms.apply(pu)
        pu.ownReach = UPDATE_REACH * wf + (wf - 1)
        for (r in rects) {
            val cells = waterRect(r)
            MediaUniforms.wetUpdate(pu, ws[wc].w0, ws[wc].w1, ws[bi].flux, run, state[0].p0, state[0].p1, bakeTex, waterBake, fluidBake,
                inTex[0], inTex[1], 0, cells, ww, wh)
            quad(cells, wShift)
        }
        if (wf > 1) for (r in rects) paintStep(state[0], pFbo1, r)
        for (r in rects) {
            copyRect(state[1], pFbo0, r)
            for (f in wetCopyFbo) clearIn(f, r, water = true)
            touch(r)
        }
        stores += WATER_WRITES
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Clears layer-px [rect] of every target of [fbo] (slot by slot in the atlas); [water]: [fbo] holds water textures. */
    private fun clearIn(fbo: Int, rect: FloatArray, water: Boolean = false) {
        val a = atlas
        val r = if (water) waterRect(rect) else rect
        if (a != null && free) { a.clearRect(fbo, r, if (water) wShift else MediaAtlasDraw.SHIFT); return }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, if (water) ww else w, if (water) wh else h)
        scissorTo(r)
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
    fun renderLook(rect: FloatArray, paper: MediaPaperGl, look: MediaLook, pxPerMm: Double): Int = renderLook(listOf(rect), paper, look, pxPerMm)

    /**
     * The look of every rectangle of [rects] (layer px) in one go: the program and its textures set once (a frame's look in
     * pieces cost ~3 ms of setup a piece on the Note 9, 10-21). The same pixels as one call each.
     */
    fun renderLook(rects: List<FloatArray>, paper: MediaPaperGl, look: MediaLook, pxPerMm: Double): Int {
        pasteSettle()
        lookTexture()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, lookFbo)
        GLES30.glViewport(0, 0, w, h)
        fun scissorWindow(rect: FloatArray) {
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
            val e = emptyTex()
            val ru = progRender.use()
            common(ru); MediaUniforms.paper(ru, paper)
            MediaUniforms.render(ru, state[0].p0, state[0].p1, state[0].paper, groundTex, bakeT, w0T, w1T, w, h, originX, originY, scale,
                pxPerMm, paper.stats.toothMm, look)
            // The brush input not yet in the water (a water job is running, or this frame's dabs wait for the next one).
            val pending = ws != null && inputPending
            ru.f1("u_inputPending", if (pending) 1f else 0f)
            ru.tex("u_inA0", if (pending) inTex[0] else e); ru.tex("u_inA1", if (pending) inTex[1] else e)
            ru.tex("u_inB0", if (pending) inTex[2] else e); ru.tex("u_inB1", if (pending) inTex[3] else e)
            GLES30.glBindVertexArray(emptyVao)
            val a = atlas
            if (a != null) {   // identity (the device probe) too: every NBR look draw needs its tile's neighbours
                // Each slot's texels map back to layer px through u_pan: gl_FragCoord − pan = layer px (exact integers at scale 1).
                // The part of each tile a few px in from its edges is drawn by the NEAR build (its near reads own-tile, only the
                // shadow march addressed), the strips along the edges by this one (MediaAtlasGl.forEachLookPart).
                val near = ru.own
                val reach = if (near != null) ceil(ru.ownReach * max(1f, scale)).toInt() else -1
                fun drawPart(u: UniformBank, slot: Int, tx: Int, ty: Int) {
                    val (ax, ay) = a.slotTexel(slot)
                    u.f2("u_pan", -originX * scale + (ax - tx * MediaAtlasDraw.SIDE), -originY * scale + (ay - ty * MediaAtlasDraw.SIDE))
                    // The look reads its tile and the 8 around it straight from these, not through the table (JB_ATLAS_NBR).
                    a.neighbours(tx, ty, nbrSlots)
                    nbrTile[0] = tx; nbrTile[1] = ty; u.i2v("u_nbrTile", nbrTile); u.i2v("u_nbrSlot", nbrSlots)
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                }
                for (rect in rects) {
                    if (!free) scissorWindow(rect)
                    a.forEachLookPart(rect, reach, inner = false) { slot, tx, ty -> drawPart(ru, slot, tx, ty) }
                }
                if (near != null) {
                    val nu = near.use()
                    ru.copyInto(nu)
                    for (rect in rects) {
                        if (!free) scissorWindow(rect)
                        a.forEachLookPart(rect, reach, inner = true) { slot, tx, ty -> drawPart(nu, slot, tx, ty) }
                    }
                    progRender.use()
                    ru.rebindTextures()
                }
            } else for (rect in rects) { scissorWindow(rect); GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3) }
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        return lookTex
    }

    // ---- state in and out (tile sync, undo through the app's stores) ----
    /** The current state textures by store suffix ("p0", "p1", "paper"; "w0", "w1" while water exists). */
    /** Pixels per texel of a [stateTextures] plane: 2 for the water's with coarse water, else 1. */
    fun stateTexelPx(name: String): Int = if (name in WATER_STORES) wf else 1

    fun stateTextures(): Map<String, Int> {
        finishWaterJob()   // it reads or writes what a water job does
        val m = linkedMapOf("p0" to state[0].p0, "p1" to state[0].p1, "paper" to state[0].paper)
        wet?.let { m["w0"] = it[wc].w0; m["w1"] = it[wc].w1 }
        return m
    }

    /** Copy a rectangle of [src] (any texture of the same format) into [dst] (blit, exact). */
    fun blit(src: Int, sx0: Int, sy0: Int, sx1: Int, sy1: Int, dst: Int, dx0: Int, dy0: Int, dx1: Int, dy1: Int, filter: Int = GLES30.GL_NEAREST) {
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, readFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, src, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, drawFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_DRAW_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, dst, 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0), 0)
        GLES30.glBlitFramebuffer(sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, GLES30.GL_COLOR_BUFFER_BIT, filter)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
    }

    /** After state was written from outside (undo, load): water there starts again from rest. */
    fun stateRestored(rect: FloatArray) {
        finishWaterJob()   // it reads or writes what a water job does
        val a = atlas
        if (free && a != null) {
            wet?.let { ws ->
                val wr = waterRect(rect)
                for (x in ws) { val f = MediaTex.fbo(x.flux); a.clearRect(f, wr, wShift); MediaTex.deleteFbo(f) }
                val r = intArrayOf(floor(wr[0]).toInt(), floor(wr[1]).toInt(), ceil(wr[2]).toInt(), ceil(wr[3]).toInt())
                blitLayer(ws[wc].w0, ws[1 - wc].w0, r[0], r[1], r[2], r[3], wShift)
                blitLayer(ws[wc].w1, ws[1 - wc].w1, r[0], r[1], r[2], r[3], wShift)
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return
        }
        wet?.let { ws ->
            val rect = waterRect(rect)
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
        job = null   // everything goes; what was worth keeping was written back before (a write-back finishes the job)
        releasePrograms()
        val tex = ArrayList<Int>()
        for (s in state) tex += listOf(s.p0, s.p1, s.paper)
        for (b in brush) tex += listOf(b.b0, b.b1)
        wet?.forEach { tex += listOf(it.w0, it.w1, it.flux) }
        tex += listOf(delta, bakeTex, fluidBake, waterBake, run, lutTex, lookTex, groundTex, empty, actTex, mid0, mid1, terrain) + bead.toList() + inTex.toList()
        tex.filter { it != 0 }.toIntArray().let { if (it.isNotEmpty()) MediaTex.deleteTex(*it) }
        val fbos = ArrayList<Int>()
        for (s in state) fbos += s.fbo
        for (b in brush) fbos += b.fbo
        fbos += listOf(pFbo0, pFbo1, deltaFbo, bakeFbo, lookFbo, groundFbo, readFbo, drawFbo, actFbo, terrainFbo) + inFbos.toList() + beadFbo.toList() + fluxFbo.toList() +
            updFbo.toList() + wetCopyFbo.toList() + eraseWetFbo.toList()
        fbos.filter { it != 0 }.toIntArray().let { if (it.isNotEmpty()) MediaTex.deleteFbo(*it) }
        GLES30.glDeleteBuffers(2, intArrayOf(quadBuf, dabBuf), 0)
        GLES30.glDeleteVertexArrays(3, intArrayOf(quadVao, dabVao, emptyVao), 0)
        if (actPbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(actPbo), 0)
        if (actFence != 0L) GLES30.glDeleteSync(actFence)
        actTex = 0; actFbo = 0; actPbo = 0; actFence = 0L
        atlas?.release(); atlas = null
        uploadBuffer.reset()
    }

    companion object {
        private val WATER_WRITES = listOf("p0", "p1", "w0", "w1")
        private val WATER_STORES = listOf("w0", "w1")
        /**
         * What new engines use (the bench sets it for an A/B). Off: on the Note 9 (10-21) coarse water lost the fine beading
         * the owner preferred and, once the look was drawn once per tile, was not reliably faster. Kept for a later choice.
         */
        @Volatile @JvmStatic var coarseWaterDefault = false
        /** The bench's `jb_atlas_rows` A/B: MediaAtlasGl.rowPasses (2 shipped, 0 = one pass). */
        @JvmStatic var atlasRowPasses: Int
            get() = MediaAtlasGl.rowPasses
            set(v) { MediaAtlasGl.rowPasses = v }
    }

    private fun union(a: FloatArray?, b: FloatArray): FloatArray =
        if (a == null) b.copyOf() else floatArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
}

/**
 * The atlas build of every pass drawn slot by slot: reads through the 3x3 neighbour slots, not the page table
 * (JB_ATLAS_NBR, jb_media_atlas.glsl): compiles in a fraction of the time and runs near the window's speed.
 */
private val ATLAS_NBR = listOf("JB_ATLAS_NBR")
/** Passes reading only their own pixel (the copy): the OWN build alone, every slot in one draw. */
private val ATLAS_OWN = listOf("JB_ATLAS_NBR", "JB_ATLAS_OWN")
/** How far jb_media_render.frag's near reads go (full-resolution cells): its gradients' bilinear taps a cell either side
 * (c-1 .. c+2), and one. The impasto shadow march (to 51 px) reads far (jb_bilinearLSFar). */
private const val LOOK_NEAR_REACH = 4   // 3, and one for coarse water's half-resolution taps
/** How far jb_paste_dab.frag reads (full-resolution cells): its surface taps 8 px away, read bilinearly (cells c-8 .. c+9). */
private const val PASTE_DAB_REACH = 9
/** The most water a frame simulates in the atlas, px x steps: what the window's 1024 px square does at 4 steps. */
private const val WET_PX_STEPS = 4.0 * 1024 * 1024
/** A water job's stages, in the order a step runs them (MediaLayerEngine.runJob). */
private const val STAGE_BEAD0 = 0
private const val STAGE_BEAD1 = 1
private const val STAGE_BEAD2 = 2
private const val STAGE_FLUX = 3
private const val STAGE_UPDATE = 4
private const val STAGE_PAINT = 5
/**
 * Each stage's cost per px of its band, in flat-flux px: the Note 9 crowd bench 10-21 (passes by atlas row): flux ~15 ns,
 * update ~25 ns a px; the bead's three passes are on the half grid and light.
 */
private val JOB_WEIGHT = doubleArrayOf(0.4, 0.4, 0.4, 1.0, 1.7, 1.0)
/** A sliced job's band: this many tile rows of its stage's own grid. */
private const val BAND_TILES = 1
/** How far jb_wet_update.frag reads from its pixel (full-resolution cells): the 3-cell ring of its sizing test, and one. */
private const val UPDATE_REACH = 4
/** How far jb_wet_bead.frag reads, full-resolution cells: its blur's 12 half cells (24 + the half cell's two), and one. */
private const val BEAD_REACH = 26
/** The water's passes while a drained tile is a wall: the same reads plus the wall test (jb_media_atlas.glsl). */
private val ATLAS_NBR_WALLS = listOf("JB_ATLAS_NBR", "JB_ATLAS_WALLS")
/** The paste brush's build: the same, 5x5 around the step (its target is the brush's cells, not a slot). */
private val ATLAS_NBR5 = listOf("JB_ATLAS_NBR", "JB_ATLAS_NBR5")
