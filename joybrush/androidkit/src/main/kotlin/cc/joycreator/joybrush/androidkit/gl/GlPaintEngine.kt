package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.io.PaperResources
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.paper.PaperState
import cc.joycreator.joybrush.core.paper.ResolvedPaper
import cc.joycreator.joybrush.core.paper.SurfaceEntry
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.brush.SmudgeCarried
import cc.joycreator.joybrush.core.brush.SmudgeStroke
import cc.joycreator.joybrush.core.brush.TileReader
import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.layers.LayerState
import cc.joycreator.joybrush.core.paint.Thumbnails
import cc.joycreator.joybrush.core.render.LayerMask
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.DabTravel
import cc.joycreator.joybrush.core.brush.PaperResponse
import cc.joycreator.joybrush.core.paint.StrokeBlend
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.paint.TuftMath
import cc.joycreator.joybrush.core.paint.TuftShading
import cc.joycreator.joybrush.core.paint.TuftStamp
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.media.HalfFloat
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.layers.LayerBudget
import cc.joycreator.joybrush.core.sprite.SpriteGridMath
import cc.joycreator.joybrush.core.render.MAX_REGION_PX
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.IdentityHashMap
import java.util.UUID

/** Layer thumbnails are drawn this many times bigger, then box-averaged down (JB-2.04). */
private const val THUMB_SUPERSAMPLE = 4

/** Immutable, bounded scene identity for asynchronous Sprite pictures. No graphics state is captured. */
internal class SpritePreviewRequest(
    private val document: JbDocument,
    boardId: String,
    cells: List<Int>,
    val width: Int,
    val height: Int,
    private val revision: Long,
) {
    val board = document.boards.firstOrNull { it.id == boardId }
        ?: throw IllegalArgumentException("No such Sprite board")
    val bounds: Map<Int, RectPx>
    init {
        require(board.kind == BoardKind.SPRITE) { "Cell previews require a Sprite board" }
        require(cells.size <= 16) { "Request only the visible Sprite cells" }
        require(width in 1..256 && height in 1..256) { "Sprite preview dimensions are out of range" }
        bounds = cells.distinct().associateWith { SpriteGridMath.cellRect(board, it) }
    }
    // Includes neighboring animation cursors/ownership, not only the Sprite rectangle.
    fun matches(current: JbDocument?, currentRevision: Long): Boolean =
        document == current && revision == currentRevision
}

internal data class TranslatedTileSlice(
    val sourceKey: Long, val destinationKey: Long,
    val source: RegionTileRect, val destination: RegionTileRect,
)

/** Subdivide at BOTH grids; a translated cell need not have the same tile-local origin. */
internal object RegionTransferTiles {
    fun slices(source: RectPx, destination: RectPx): Sequence<TranslatedTileSlice> = sequence {
        require(source.w > 0 && source.h > 0 && source.w == destination.w && source.h == destination.h) {
            "Transferred rectangles must have the same positive size"
        }
        for (r in listOf(source, destination)) {
            require(r.x.toLong() + r.w <= Int.MAX_VALUE && r.y.toLong() + r.h <= Int.MAX_VALUE) {
                "Transferred pixels are outside addressable canvas coordinates"
            }
        }
        var y = 0
        while (y < source.h) {
            val sy = source.y + y; val dy = destination.y + y
            val sourceY = Math.floorMod(sy, Tiles.SIZE); val destinationY = Math.floorMod(dy, Tiles.SIZE)
            val height = minOf(source.h - y, Tiles.SIZE - sourceY, Tiles.SIZE - destinationY)
            var x = 0
            while (x < source.w) {
                val sx = source.x + x; val dx = destination.x + x
                val sourceX = Math.floorMod(sx, Tiles.SIZE); val destinationX = Math.floorMod(dx, Tiles.SIZE)
                val width = minOf(source.w - x, Tiles.SIZE - sourceX, Tiles.SIZE - destinationX)
                yield(TranslatedTileSlice(
                    Tiles.key(Math.floorDiv(sx, Tiles.SIZE), Math.floorDiv(sy, Tiles.SIZE)),
                    Tiles.key(Math.floorDiv(dx, Tiles.SIZE), Math.floorDiv(dy, Tiles.SIZE)),
                    RegionTileRect(sourceX, sourceY, width, height),
                    RegionTileRect(destinationX, destinationY, width, height),
                ))
                x += width
            }
            y += height
        }
    }
}

/** A layer id with this on the end names its MASK's tile store (JB-2.23): strokes, tiles, undo and thumbnails all take it. */
const val MASK_SUFFIX = "#mask"

fun maskStoreId(layerId: String): String = layerId + MASK_SUFFIX

/** Refusal of a media write past [GlPaintEngine.mediaBudgetBytes]: [message] is the sentence to show. */
class MediaRoomException(message: String) : RuntimeException(message)

/** What [GlPaintEngine.writableMediaTiles] calls a media layer's RGBA8 look, which keeps the layer's own id. */
const val MEDIA_LOOK = "look"

/**
 * The two rates of a smudge stroke (JB-1.06): how fast the ONE carried colour takes on the canvas ([pickup]) and the brush's
 * own colour ([load]), both 0..1. How hard a dab presses is the dab's own flow. See `core/brush/Smudge.kt` for the rule.
 */
class SmudgeParams(val pickup: Float, val load: Float, val texturePickup: Float = 0f, val paint: Boolean = false)

/**
 * The GPU painting engine (JB-0.07). GL THREAD ONLY — every method must be called on the thread that
 * owns the GL context (e.g. via GLSurfaceView.queueEvent).
 *
 * - Layers are sparse maps of 256×256 RGBA8 **premultiplied** tiles (textures). The canvas has no edge.
 * - A stroke paints into a sparse set of single-channel stroke-buffer tiles (R16F where the GPU can
 *   render to it — every OpenGL ES 3.2 device, including the Note 9 — else R8). Dabs are instanced
 *   quads blended with fixed-function (ONE, ONE_MINUS_SRC_ALPHA): no read-back, batched per tile.
 * - Commit is COPY-ON-WRITE: each touched tile is rendered into a NEW texture; the old texture
 *   becomes the undo snapshot ([UndoLog]) — undo costs no copying.
 * - While a stroke is in progress the screen shows it through the same commit maths (preview), so
 *   what you see while drawing is exactly what lands on pen-up.
 *
 * The maths is identical to the CPU reference `RefCanvas` in the core, and the shaders are the shared
 * files in joybrush/shaders.
 */
class GlPaintEngine(
    private val shaders: ShaderLibrary = ShaderLibrary(),
    undoBudgetBytes: Long = 192L shl 20,
) {
    private val size = Tiles.SIZE
    // GL-thread only. Reuse staging storage while a large snapshot fills its output arrays.
    private val tileReadback by lazy { ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder()) }

    private lateinit var dabProg: GlProgram
    private lateinit var commitProg: GlProgram
    private lateinit var tileProg: GlProgram
    private lateinit var smudgeProg: GlProgram
    private lateinit var tuftProg: GlProgram
    /** Compiled the first time a blended stack is drawn, not at [init]: see [drawComposited]. */
    private var compositeProg: GlProgram? = null

    /**
     * Why the composite shader could not be built on this GPU, or null. While it is set, [draw] takes the
     * fixed-function path for every stack (layers then all composite as NORMAL) rather than the engine failing
     * to start; the diagnostics panel's Blend check reports it in words.
     */
    var compositeError: String? = null
        private set

    private var dabVao = 0
    private var tileVao = 0
    private var smudgeVao = 0
    private var tuftVao = 0
    private var quadVbo = 0
    private var unitVbo = 0
    private var instanceVbo = 0
    private var fbo = 0
    private var readFbo = 0
    private var copyFbo = 0
    private var clearTex = 0

    /** 1x1 opaque white: a mask tile that was never painted (full coverage), and "no mask" in the composite shader. */
    private var whiteTex = 0

    /** The grain pictures (JB-1.05c): loaded on first use, dropped on a context loss. */
    private val grains = GrainTextures()
    private val paperBackground = PaperBackground(shaders)
    private var resolvedPaper: ResolvedPaper? = null
    private var loadedPaper: PaperResources.Loaded? = null
    private var lookTexture: Int? = null
    private var surfaceTexture: Int? = null
    /** The paper's FLUID map (2026-10-06), for wet and impasto engines; bound on unit 2 for brush programs. */
    private var fluidTexture: Int? = null
    private var strokeSurface: SurfaceEntry? = null
    private var strokePaperScale = 1f
    val paperWarnings: List<String> get() = loadedPaper?.warnings ?: emptyList()

    var documentPaper: Paper = Paper()
        private set

    fun setDocumentPaper(value: Paper) {
        documentPaper = value
        setPaper(PaperState.resolve(value, PaperResources.catalogue))
    }

    /** Same undo stream as paint; no tiles or texture ownership involved. */
    fun recordPaperChange(before: Paper, after: Paper) {
        check(!strokeInProgress) { "Finish the stroke before closing a paper edit" }
        if (before != after) undo.push(UndoLog.Step(emptyList(), paperBefore=before, paperAfter=after))
    }

    /** GL thread: switching paper invalidates only the background, never painted tiles. */
    fun setPaper(p: ResolvedPaper) {
        if (resolvedPaper == p && loadedPaper != null) return
        loadedPaper = PaperResources.update(p, resolvedPaper, loadedPaper)
        resolvedPaper = p
        val effective = loadedPaper!!.paper
        lookTexture = effective.look?.file?.let { grains.textureFor(it, "paper") }
        surfaceTexture = effective.surface?.file?.let { grains.textureFor(it, "paper") }
        fluidTexture = effective.surface?.fluid?.let { grains.textureFor(it, "paper") }
        paperBackground.invalidate()
    }

    fun renderPaper(rect: RectPx): ByteArray = (loadedPaper ?: PaperResources.load(
        ResolvedPaper(null,null,0xFFFFFFFF.toInt(),1f,1f,1f,false))).render(rect)

    private fun drawPaper(w: Int, h: Int, m: FloatArray, fallback: Int, target: Int) {
        if(boardArtOnly) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,target)
            GLES30.glClearColor(0f,0f,0f,0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        val p = loadedPaper?.paper ?: ResolvedPaper(null,null,fallback,1f,1f,1f,false)
        val lookSize = p.look?.file?.let { grains.sizeFor(it,"paper") } ?: 1
        paperBackground.draw(w,h,m,p,lookTexture,surfaceTexture,lookSize,grains.placeholder,tileVao,target)
    }

    /** The offscreen stack the composite path builds (JB-2.20b); made on first use, dropped on a context loss. */
    internal val compositor = LayerCompositor()
    private val boardCompositor = LayerCompositor()

    private var strokeInternal = GLES30.GL_R8
    private var strokeType = GLES30.GL_UNSIGNED_BYTE

    /** What a smudge stroke's RGBA buffer is made of: half float where the GPU can render to it, else 8 bits. */
    private var smudgeInternal = GLES30.GL_RGBA8
    private var smudgeType = GLES30.GL_UNSIGNED_BYTE

    /** True once [init] has run on the current context. */
    var ready = false
        private set

    /**
     * True when the last [init] found GPU-side state left over from an earlier context — so the
     * pixels are gone and this engine emptied itself instead of carrying the dead texture names
     * into the new context as if they were somebody's drawing (review JB-0.07 F1).
     *
     * A caller that can tell the person ("the drawing was lost, it will have to be opened again")
     * reads this. This class shows nothing on screen: what it guarantees is only that what IS shown
     * is real.
     */
    var lostContent = false
        private set

    /** Which stroke-buffer precision this GPU got (for the diagnostics overlay). */
    val strokeBufferIsHalfFloat: Boolean get() = strokeInternal == GLES30.GL_R16F

    private class Layer(val id: String, val isMask: Boolean = false) {
        val tiles = HashMap<Long, Int>()
        val cels = LinkedHashMap<String, HashMap<Long, Int>>()
        var sharedCel: String? = null
        var plan: RegionPaintPlan? = null
        val projected = HashMap<Long, Int>()
        var visibleProjection: Map<Long,Int>? = null
        /** A media layer's float state by store name ([MediaStores.ALL]); [tiles] is its look. Empty for every other layer. */
        val floats = HashMap<String, HashMap<Long, Int>>()
        fun ownedTextures(): Set<Int> = (tiles.values + cels.values.flatMap { it.values } + projected.values + floats.values.flatMap { it.values }).toSet()
        /** JB-2.23: this layer's mask, a second tile store whose missing tiles are WHITE (full coverage). */
        var mask: Layer? = null
        var clip = false
        var name = id
        var opacity = 1f
        var visible = true
        var blend = BlendMode.NORMAL
    }

    /** The active layer an undo or redo last put the stack back to, for a view whose own active layer it removed. */
    private var activeHint: String? = null

    /** The small target thumbnails are rendered into, made on first use (JB-2.04). 0 = none on this context. */
    private var thumbTex = 0
    private var thumbW = 0
    private var thumbH = 0
    /** Reused across a strip batch: do not allocate sixteen supersampled direct buffers. */
    private var boardThumbReadback: ByteBuffer? = null

    private val layers = LinkedHashMap<String, Layer>()   // bottom → top
    private val freeLayerTex = ArrayDeque<Int>()
    private val freeStrokeTex = ArrayDeque<Int>()
    private val freeSmudgeTex = ArrayDeque<Int>()
    private val freeFloatTex = ArrayDeque<Int>()
    /** Every RGBA16F media tile name this context made: how undo sizes a texture and which pool it goes back to. */
    private val floatNames = HashSet<Int>()

    val undo = UndoLog<Int>(undoBudgetBytes, sizeOf = { size.toLong() * size * (if (it in floatNames) MediaStores.BYTES_PER_TEXEL else 4) }, release = ::recycleLayerTex)

    // Active stroke.
    private var strokeLayer: Layer? = null
    private var strokePlan: RegionPaintPlan? = null
    var boardDocument: JbDocument? = null
        private set
    private var framePreviews: Map<String, String> = emptyMap()
    private val boardPreview = GlBoardPreview()
    private var boardArtOnly = false
    private var boardCropRendering = false
    private var ghostBoardId: String? = null
    var tileBoardId: String? = null
        private set
    private var strokeTileRect: RectPx? = null
    private var onionBoardId: String? = null

    fun setTileBoard(id: String?) {
        check(!strokeInProgress) { "Finish the stroke before switching tiling" }
        if(id != null) {
            val board=TilePainting.board(requireNotNull(boardDocument),id)
            boardPreview.prepare(board.rect.w,board.rect.h)
        }
        tileBoardId=id
    }
    fun setOnionBoard(id: String?) {
        if(id != null) require(boardDocument?.boards?.any { it.id == id && it.kind == BoardKind.ANIMATION } == true)
        onionBoardId=id
    }
    /** GL-thread snapshot. Playback has no saved cursor and contributes no history step. */
    val previewFrames: Map<String, String> get() = framePreviews.toMap()
    private val strokeTiles = HashMap<Long, Int>()
    private var colR = 0f; private var colG = 0f; private var colB = 0f
    private var opacity = 1f
    private var accumulate = Accumulate.WASH
    private var blend = StrokeBlend.NORMAL
    private var tip = TipShape()
    private var grain = GrainMath.StrokeGrain(GrainMath.GrainUniforms.OFF, GrainMath.GrainUniforms.OFF)
    private var tipGrainTex = 0
    private var paperGrainTex = 0

    private val strokeTravel = DabTravel()
    private var strokePaperResponse = PaperResponse()
    private var strokePaperInfluence = 0f
    private var instanceData: FloatBuffer = newFloats(17 * 256)
    private var smudgeInstanceData: FloatBuffer = newFloats(21 * 256)
    private var tuftInstanceData: FloatBuffer = newFloats(TuftStamp.FLOATS * 256)

    /** Set while a tuft stroke is in progress (R9): its whole-stroke shader numbers, and the page's tooth picture. */
    private var tuft: TuftShading? = null
    private var tuftPaperTex = 0

    /** Set while a smudge stroke is in progress: the ONE carried colour, and the layer as it was at pen-down. */
    private var smudge: SmudgeStroke? = null
    private var smudgeTexturePickup = 0f
    private var smudgePaint = false

    /** True while the stroke buffer holds RGBA carried paint (a smudge) instead of a single coverage channel. */
    private var strokeIsRgba = false

    // ── lifecycle ────────────────────────────────────────────────────────────

    /**
     * Creates GL objects. Call once per GL context — and again after a context loss, which is why
     * the first thing it does is forget what the last context held (see [initWith]).
     */
    fun init() = initWith(::createGlObjects)

    /**
     * [init], with the GL calls behind a parameter so the bookkeeping can be tested where there is
     * no context. `create` runs AFTER the loss is absorbed, never before: an object made on the new
     * context must never meet a name the old one minted.
     */
    internal fun initWith(create: () -> Unit) {
        lostContent = forgetEverythingFromTheLastContext()
        create()
        ready = true
    }

    private fun createGlObjects() {
        val version = GLES30.glGetString(GLES30.GL_VERSION) ?: ""
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        val halfFloatRenderable = version.contains("OpenGL ES 3.2") ||
            ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float")
        if (halfFloatRenderable) {
            strokeInternal = GLES30.GL_R16F; strokeType = GLES30.GL_HALF_FLOAT
            smudgeInternal = GLES30.GL_RGBA16F; smudgeType = GLES30.GL_HALF_FLOAT
        }

        dabProg = GlProgram(shaders.source("jb_dab.vert"), shaders.source("jb_dab.frag"), "dab")
        commitProg = GlProgram(shaders.source("jb_tile.vert"), shaders.source("jb_commit.frag"), "commit")
        tileProg = GlProgram(shaders.source("jb_tile.vert"), shaders.source("jb_tile.frag"), "tile")
        smudgeProg = GlProgram(shaders.source("jb_dab.vert"), shaders.source("jb_smudge_dab.frag"), "smudge")
        tuftProg = GlProgram(shaders.source("jb_tuft.vert"), shaders.source("jb_tuft.frag"), "tuft")

        val ids = IntArray(3)
        GLES30.glGenBuffers(3, ids, 0)
        quadVbo = ids[0]; unitVbo = ids[1]; instanceVbo = ids[2]
        upload(quadVbo, floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        upload(unitVbo, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        val vaos = IntArray(4)
        GLES30.glGenVertexArrays(4, vaos, 0)
        dabVao = vaos[0]; tileVao = vaos[1]; smudgeVao = vaos[2]; tuftVao = vaos[3]

        GLES30.glBindVertexArray(dabVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 68, 0)
        GLES30.glVertexAttribDivisor(1, 1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 68, 16)
        GLES30.glVertexAttribDivisor(2, 1)

        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 2, GLES30.GL_FLOAT, false, 68, 24)
        GLES30.glVertexAttribDivisor(4, 1)
        enableContactAttributes(68, 32)

        // The smudge dab: the same quad and the same instance buffer, but 10 floats per dab (the stamp's 6 plus the carried colour).
        GLES30.glBindVertexArray(smudgeVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 84, 0)
        GLES30.glVertexAttribDivisor(1, 1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 84, 16)
        GLES30.glVertexAttribDivisor(2, 1)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, 84, 24)
        GLES30.glVertexAttribDivisor(3, 1)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 2, GLES30.GL_FLOAT, false, 84, 40)
        GLES30.glVertexAttribDivisor(4, 1)
        enableContactAttributes(84, 48)

        // The tuft footprint (R9): the same quad and instance buffer, four vec4s per footprint (TuftStamp.FLOATS).
        GLES30.glBindVertexArray(tuftVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        for (i in 0 until 4) {
            GLES30.glEnableVertexAttribArray(1 + i)
            GLES30.glVertexAttribPointer(1 + i, 4, GLES30.GL_FLOAT, false, TuftStamp.FLOATS * 4, i * 16)
            GLES30.glVertexAttribDivisor(1 + i, 1)
        }

        GLES30.glBindVertexArray(tileVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, unitVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES30.glGenFramebuffers(1, f, 0); readFbo = f[0]
        GLES30.glGenFramebuffers(1,f,0); copyFbo = f[0]

        clearTex = newTexture(1, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE)
        whiteTex = newTexture(1, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE)
        val white = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        white.put(byteArrayOf(-1, -1, -1, -1)).rewind()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, whiteTex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, white)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        grains.create()
    }

    /**
     * Drops every texture name this engine holds, and says whether there was anything to drop. This
     * is [initWith]'s first statement and has to stay it: a texture name only means anything on the
     * context that minted it, and after a loss the old names are free-list numbers the driver will
     * hand to something else — or, once [initWith] has run, names that now belong to the clear tex.
     * That is how a layer's dead texture came to be presented as somebody's painting (JB-0.07 F1).
     *
     * So a loss is not repaired: the pixels cannot come back, and the honest recovery is an EMPTY
     * document, reported through [lostContent], rather than a blank canvas wearing somebody's undo
     * history. Nothing is recycled on this path — a recycled name would be handed straight back out
     * by [newLayerTile] — and the pools are cleared AFTER the stroke and the undo log have released
     * into them, so both end up empty whatever order those releases happen in.
     *
     * The releases themselves delete nothing: the names were minted by the dead context, and this
     * runs before the new context's first `glGen*`, so a pool trimmed here can only be deleting
     * something that does not exist.
     */
    private fun forgetEverythingFromTheLastContext(): Boolean {
        // [heldTextureNames] rather than the maps one by one, so "there was something to lose" and
        // "a name is still being held" can never mean different things. `ready` is deliberately NOT
        // in the question: init is also how a second init on a live context is recovered from, and
        // what the caller needs to be told is that the DRAWING did not survive — not that init ran
        // twice. An engine with nothing in it has nothing to announce.
        val had = heldTextureNames() > 0 || undo.canUndo || undo.canRedo
        // The stroke goes with its buffer, and the layers with their tiles: the stroke was never in
        // a layer, so nothing is released twice and nothing is left pointing at a dead name.
        cancelStroke()
        layers.clear()
        boardDocument = null
        framePreviews = emptyMap()
        tileBoardId=null; onionBoardId=null; strokeTileRect=null
        undo.clear()
        freeLayerTex.clear()
        freeStrokeTex.clear()
        freeSmudgeTex.clear()
        freeFloatTex.clear(); floatNames.clear(); mediaStroke = null; mediaStrokeHeld.clear()
        // The grain pictures are not the person's drawing, so they do not count as "had" -- but their
        // names are just as dead, and a brush is reloaded from its file on the next stroke.
        grains.forget()
        compositor.forget()
        boardCompositor.forget()
        paperBackground.forget()
        boardPreview.forget()
        loadedPaper = null; lookTexture = null; surfaceTexture = null; fluidTexture = null
        compositeProg = null      // a name from the dead context; the new one compiles on first use
        thumbTex = 0; thumbW = 0; thumbH = 0
        compositeError = null
        return had
    }

    /**
     * How many texture names this engine is holding right now — every layer tile, every stroke-buffer
     * tile and both free pools. It exists so a test can prove a context loss leaves NONE of them
     * behind; it is not a count of GL objects, because the programs, buffers, framebuffer and
     * [clearTex] are the driver's business and [initWith] makes new ones rather than reusing old.
     */
    internal fun heldTextureNames(): Int =
        layers.values.sumOf { it.ownedTextures().size + (it.mask?.ownedTextures()?.size ?: 0) } + strokeTiles.size + freeLayerTex.size + freeStrokeTex.size + freeSmudgeTex.size + freeFloatTex.size +
            compositor.heldNames() + boardCompositor.heldNames()

    /** Frees every GL object this engine owns. */
    fun release() {
        if (!ready) return
        undo.clear()
        cancelStroke()
        val all = ArrayList<Int>()
        layers.values.forEach { all.addAll(it.ownedTextures()); it.mask?.let { m -> all.addAll(m.ownedTextures()) } }
        all.addAll(freeLayerTex); all.addAll(freeStrokeTex); all.addAll(freeSmudgeTex); all.addAll(freeFloatTex); all.add(clearTex); all.add(whiteTex)
        if (thumbTex != 0) all.add(thumbTex)
        thumbTex = 0; thumbW = 0; thumbH = 0
        GLES30.glDeleteTextures(all.size, all.toIntArray(), 0)
        layers.clear(); freeLayerTex.clear(); freeStrokeTex.clear(); freeSmudgeTex.clear(); freeFloatTex.clear(); floatNames.clear()
        mediaStroke = null; mediaStrokeHeld.clear()
        GLES30.glDeleteBuffers(3, intArrayOf(quadVbo, unitVbo, instanceVbo), 0)
        GLES30.glDeleteVertexArrays(4, intArrayOf(dabVao, tileVao, smudgeVao, tuftVao), 0)
        GLES30.glDeleteFramebuffers(3, intArrayOf(fbo, readFbo, copyFbo), 0)
        grains.release()
        compositor.release()
        boardCompositor.release()
        paperBackground.release()
        dabProg.release(); commitProg.release(); tileProg.release(); smudgeProg.release(); tuftProg.release()
        boardPreview.release()
        compositeProg?.release(); compositeProg = null
        ready = false
    }

    // ── layers ───────────────────────────────────────────────────────────────

    /** Adds an empty layer on top (no-op if it exists). */
    fun addLayer(id: String) { layers.getOrPut(id) { Layer(id) } }

    /** The tile store [id] names: a layer, or with [MASK_SUFFIX] its mask (JB-2.23). Null if it does not exist. */
    private fun storeOf(id: String): Layer? =
        if (id.endsWith(MASK_SUFFIX)) layers[id.removeSuffix(MASK_SUFFIX)]?.mask else layers[id]

    /** [storeOf], creating what is missing: loading and undo put tiles back into stores that may not exist yet. */
    private fun storeOrCreate(id: String): Layer {
        if (!id.endsWith(MASK_SUFFIX)) return layers.getOrPut(id) { Layer(id) }
        val owner = layers.getOrPut(id.removeSuffix(MASK_SUFFIX)) { Layer(id.removeSuffix(MASK_SUFFIX)) }
        return owner.mask ?: Layer(id, isMask = true).also { owner.mask = it }
    }

    /** What a tile the store does not have looks like: transparent for a layer, WHITE for a mask. */
    private fun emptyTexOf(store: Layer): Int = if (store.isMask) whiteTex else clearTex

    fun setLayerOpacity(id: String, opacity: Float) { layers[id]?.opacity = opacity.coerceIn(0f, 1f) }
    fun setLayerName(id: String, name: String) { layers[id]?.name = name }
    fun layerName(id: String): String = layers[id]?.name ?: id

    // ── the layer stack (JB-2.04) ────────────────────────────────────────────
    //
    // The screen works out the new stack (core's LayerStack, pure and tested); the engine applies it and records ONE
    // undo step. Every structural change goes through here, so undo can never meet a layer it does not know.

    /**
     * The stack as it is now, bottom to top. [preferredActive] is the view's own brush layer; if it is gone (an undo took
     * it away), the layer the last undo or redo named, else the top one.
     */
    fun stack(preferredActive: String?): LayerStack {
        val list = layers.values.map { LayerState(it.id, it.name, it.opacity, it.visible, it.blend, it.mask != null, it.clip) }
        val ids = list.map { it.id }
        val hint = activeHint
        val active = when {
            preferredActive != null && preferredActive in ids -> preferredActive
            hint != null && hint in ids -> hint
            else -> ids.last()
        }
        return LayerStack(list, active)
    }

    /**
     * Makes the engine's layers exactly [target]: order, names, opacity, blend, visibility; creates the ones it lacks and
     * removes the ones it does not list. A layer is only ever removed EMPTY (its tiles belong to an undo step by the time
     * a stack drops it); one that still has tiles is kept on top rather than have its textures freed under the undo
     * log's feet, a visible mistake instead of a crash.
     */
    private fun applyStack(target: LayerStack) {
        val next = LinkedHashMap<String, Layer>()
        for (s in target.layers) {
            val l = layers[s.id] ?: Layer(s.id)
            l.name = s.name; l.opacity = s.opacity; l.visible = s.visible; l.blend = s.blend; l.clip = s.clip
            // A mask appears empty (all white); it only goes when it is empty too, for the same reason a layer does.
            if (s.hasMask && l.mask == null) l.mask = Layer(maskStoreId(s.id), isMask = true)
            if (!s.hasMask && l.mask?.tiles?.isEmpty() == true) l.mask = null
            next[s.id] = l
        }
        for ((id, l) in layers) if (id !in next && l.ownedTextures().isNotEmpty()) next[id] = l
        layers.clear()
        layers.putAll(next)
    }

    /** Stack metadata is part of the same history step as its physical cel edits. */
    private fun documentForStack(doc: JbDocument, target: LayerStack,
                                 replacements: Map<String, cc.joycreator.joybrush.core.doc.Layer> = emptyMap()): JbDocument {
        val old = doc.layers.associateBy { it.id }
        val next = target.layers.map { state ->
            val existing = replacements[state.id] ?: old[state.id]
            val base = existing ?: run {
                val shared = UUID.randomUUID().toString()
                val cels = arrayListOf(Cel(shared))
                val regions = doc.boards.filter { it.kind == BoardKind.ANIMATION }.map { board ->
                    val mappings = board.frames.associate { it.id to UUID.randomUUID().toString() }
                    cels.addAll(mappings.values.map { Cel(it) })
                    RegionFrames(board.id, mappings)
                }
                cc.joycreator.joybrush.core.doc.Layer(state.id, state.name, LayerKind.PAINT,
                    cels = cels, sharedCelId = shared, regions = regions)
            }
            base.copy(name = state.name, opacity = state.opacity, visible = state.visible,
                blend = state.blend, clip = state.clip,
                mask = if (state.hasMask) base.mask ?: Cel(UUID.randomUUID().toString()) else null)
        }
        return doc.copy(layers = next, activeLayerId = target.activeId).also {
            require(DocOps.validate(it).isEmpty()) { "Invalid board layer stack" }
        }
    }

    /** A change to the stack alone (add, move, rename, opacity, blend) as ONE undo step. */
    fun pushStackStep(before: LayerStack, after: LayerStack) {
        check(!strokeInProgress) { "a layer change during a stroke would be undone out of order" }
        val oldDoc = boardDocument
        val next = oldDoc?.let { documentForStack(it, after) }
        applyStack(after)
        next?.let(::setBoardDocument)
        undo.push(UndoLog.Step(emptyList(), before, after, documentBefore = oldDoc, documentAfter = next))
    }

    /** The stack made [target] with NO undo step: loading a drawing. */
    fun setStack(target: LayerStack) {
        applyStack(target)
        activeHint = target.activeId
    }

    /** Deletes [id] (as [after] says) as ONE undo step: its tiles go into the step, so undo brings the pixels back. */
    fun deleteLayerStep(id: String, before: LayerStack, after: LayerStack) {
        check(!strokeInProgress) { "a layer change during a stroke would be undone out of order" }
        val layer = layers[id] ?: return
        val oldDoc=boardDocument
        val changes=ArrayList<UndoLog.TileChange<Int>>()
        if(layer.sharedCel==null)layer.tiles.forEach{(k,t)->changes.add(UndoLog.TileChange(id,k,t,null))}
        else layer.cels.forEach{(cel,store)->store.forEach{(k,t)->changes.add(UndoLog.TileChange(id,k,t,null,cel))}}
        layer.tiles.clear();layer.cels.values.forEach{it.clear()};invalidateProjection(layer)
        // A media layer's float state goes with its look, into the same step.
        layer.floats.forEach { (store, tiles) -> tiles.forEach { (k, t) -> changes.add(UndoLog.TileChange(MediaStores.id(id, store), k, t, null)) } }
        layer.floats.clear()
        // The mask goes with its layer, into the same step, so one undo brings both back.
        layer.mask?.let { m ->
            m.tiles.forEach { (k, tex) -> changes.add(UndoLog.TileChange(m.id, k, tex, null)) }
            m.tiles.clear()
        }
        applyStack(after)
        val next=oldDoc?.copy(layers=oldDoc.layers.filterNot{it.id==id},activeLayerId=after.activeId)
        next?.let(::setBoardDocument)
        undo.push(UndoLog.Step(changes,before,after,documentBefore=oldDoc,documentAfter=next))
    }

    /** Deletes [id]'s mask (as [after] says) as ONE undo step: its tiles go into the step. */
    fun deleteMaskStep(id: String, before: LayerStack, after: LayerStack) {
        check(!strokeInProgress) { "a layer change during a stroke would be undone out of order" }
        val m = layers[id]?.mask ?: return
        val oldDoc = boardDocument
        val next = oldDoc?.let { documentForStack(it, after) }
        val changes = m.tiles.map { (k, tex) -> UndoLog.TileChange<Int>(m.id, k, tex, null) }
        m.tiles.clear()
        applyStack(after)
        next?.let(::setBoardDocument)
        undo.push(UndoLog.Step(changes, before, after, documentBefore = oldDoc, documentAfter = next))
    }

    /** Copies [sourceId]'s pixels into the new layer [newId] that [after] adds, as ONE undo step. Copied on the GPU, tile by tile. */
    fun duplicateLayerStep(sourceId: String, newId: String, before: LayerStack, after: LayerStack) {
        check(!strokeInProgress) { "a layer change during a stroke would be undone out of order" }
        val src = layers[sourceId] ?: return
        require(newId !in layers && after.layers.any { it.id == newId })
        val oldDoc = boardDocument
        val sourceMeta = oldDoc?.layers?.first { it.id == sourceId }
        val clone = sourceMeta?.let { LayerContentClone.plan(it, newId,
            after.layers.first { it.id == newId }.name) { UUID.randomUUID().toString() } }
        val next = oldDoc?.let { documentForStack(it, after, mapOf(newId to clone!!.layer)) }
        val changes = ArrayList<UndoLog.TileChange<Int>>()
        var published = false
        fun copyStore(store: Map<Long, Int>, target: String, cel: String?) {
            for ((key, tex) in store) {
                val copy = newLayerTile()
                changes.add(UndoLog.TileChange(target, key, null, copy, cel))
                initializeTile(copy, tex, false)
            }
        }
        try {
            if (clone == null) copyStore(src.tiles, newId, null)
            else for (copy in clone.copies) {
                if (copy.fromCelId == sourceMeta!!.mask?.id) {
                    copyStore(src.mask?.tiles.orEmpty(), maskStoreId(newId), null)
                } else copyStore(src.cels[copy.fromCelId].orEmpty(), newId, copy.toCelId)
            }
            if (clone == null) src.mask?.let { copyStore(it.tiles, maskStoreId(newId), null) }
            for ((store, tiles) in src.floats) for ((key, tex) in tiles) {
                val copy = newFloatTile()
                changes.add(UndoLog.TileChange(MediaStores.id(newId, store), key, null, copy))
                initializeTile(copy, tex, false)
            }
            // Publish only after every GPU copy succeeds; failures leave the original stack intact.
            applyStack(after)
            published = true
            changes.forEach { put(it.layerId, it.key, it.after, it.celId) }
            next?.let(::setBoardDocument)
            undo.push(UndoLog.Step(changes, before, after, documentBefore = oldDoc, documentAfter = next))
        } catch (e: Throwable) {
            if (published) {
                changes.forEach { put(it.layerId, it.key, null, it.celId) }
                applyStack(before)
                oldDoc?.let(::setBoardDocument)
            }
            changes.forEach { it.after?.let(::recycleLayerTex) }
            throw e
        } finally {
            // An empty clone changes metadata only; no graphics state was touched.
            if (changes.isNotEmpty()) {
                GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            }
        }
    }

    /**
     * One layer, alone, as a small picture of the page [pageW] x [pageH] (document px from 0,0): [outW] x [outH] ARGB,
     * NOT premultiplied (what Bitmap.createBitmap(int[]) takes), transparent where the layer is empty. Rendered at
     * [THUMB_SUPERSAMPLE] times the size and box-averaged, because a 1 px pencil line minified 30 times without it
     * sparkles or vanishes. Null for a layer that does not exist.
     */
    fun renderThumbnail(id: String, pageW: Int, pageH: Int, outW: Int, outH: Int): IntArray? =
        renderThumbnail(id, RectPx(0, 0, pageW, pageH), outW, outH)

    /** The same small preview, bounded to a selected board, including negative document coordinates. */
    fun renderThumbnail(id: String, bounds: RectPx, outW: Int, outH: Int): IntArray? {
        val layer = storeOf(id) ?: return null
        val pageW = bounds.w
        val pageH = bounds.h
        if (outW <= 0 || outH <= 0 || pageW <= 0 || pageH <= 0) return null
        val w = outW * THUMB_SUPERSAMPLE
        val h = outH * THUMB_SUPERSAMPLE
        if (thumbTex == 0 || thumbW != w || thumbH != h) {
            if (thumbTex != 0) GLES30.glDeleteTextures(1, intArrayOf(thumbTex), 0)
            thumbTex = newTexture(w, h, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE)
            thumbW = w; thumbH = h
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        attach(thumbTex)
        GLES30.glViewport(0, 0, w, h)
        // A mask's unpainted tiles are white (it shows everything there), so its picture starts white.
        val bg = if (layer.isMask) 1f else 0f
        GLES30.glClearColor(bg, bg, bg, bg)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        tileProg.use()
        GLES30.glUniform1f(tileProg.loc("u_tileSize"), size.toFloat())
        // The page onto the whole target, its TOP row at row 0 of the readback (the tiles' own convention).
        val m = floatArrayOf(2f / pageW, 0f, 0f, 0f, 2f / pageH, 0f,
            -1f - 2f * bounds.x / pageW, -1f - 2f * bounds.y / pageH, 1f)
        GLES30.glUniformMatrix3fv(tileProg.loc("u_docToClip"), 1, false, m, 0)
        GLES30.glUniform1f(tileProg.loc("u_layerOpacity"), 1f)
        GLES30.glUniform1i(tileProg.loc("u_layer"), 0)
        GLES30.glBindVertexArray(tileVao)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        for ((key, tex) in visibleTiles(layer)) {
            GLES30.glUniform2f(tileProg.loc("u_tileOrigin"), (Tiles.tx(key) * size).toFloat(), (Tiles.ty(key) * size).toFloat())
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES30.glBindVertexArray(0)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val px = ByteArray(w * h * 4)
        buf.rewind(); buf.get(px)
        return Thumbnails.downsample(px, w, h, THUMB_SUPERSAMPLE)
    }

    /** A bounded, fully composited strip picture. The document cursor and history stay untouched. */
    fun renderBoardThumbnail(boardId: String, frameId: String, outW: Int, outH: Int, paperArgb: Int): IntArray {
        check(!strokeInProgress) { "Frame thumbnails cannot replace a live stroke's display plan" }
        require(outW in 1..512 && outH in 1..512) { "Frame preview dimensions are out of range" }
        val doc = boardDocument ?: error("No board document is attached")
        val board = doc.boards.firstOrNull { it.id == boardId } ?: error("No such board")
        val restore = framePreviews
        // The core validates frame identity, even if every layer is held.
        val requested = restore + (boardId to frameId)
        RegionDocumentOps.paintPlans(doc, requested)
        return renderCompositedThumbnail(board.rect, outW, outH, paperArgb, requested)
    }

    /** Sprite cells contain the current saved scene, including held paint and nearby animation ownership. */
    fun renderSpriteCellThumbnail(boardId: String, cell: Int, outW: Int, outH: Int, paperArgb: Int): IntArray {
        check(!strokeInProgress) { "Cell thumbnails cannot replace a live stroke's display plan" }
        require(outW in 1..256 && outH in 1..256) { "Sprite preview dimensions are out of range" }
        val doc = boardDocument ?: error("No board document is attached")
        val board = doc.boards.firstOrNull { it.id == boardId } ?: error("No such board")
        require(board.kind == BoardKind.SPRITE) { "Cell previews require a Sprite board" }
        val rect = SpriteGridMath.cellRect(board, cell)
        // Playback is transient. Cell caches must use saved neighboring frame cursors.
        RegionDocumentOps.paintPlans(doc, emptyMap())
        return renderCompositedThumbnail(rect, outW, outH, paperArgb, emptyMap())
    }

    private fun renderCompositedThumbnail(rect: RectPx, outW: Int, outH: Int, paperArgb: Int,
                                         requested: Map<String, String>): IntArray {
        val restore = framePreviews
        val w = outW * THUMB_SUPERSAMPLE
        val h = outH * THUMB_SUPERSAMPLE
        val bindings = IntArray(2)
        val viewport = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, bindings, 0)
        GLES30.glGetIntegerv(GLES30.GL_READ_FRAMEBUFFER_BINDING, bindings, 1)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0)
        val scissor = GLES30.glIsEnabled(GLES30.GL_SCISSOR_TEST)
        try {
            setFramePreviews(requested)
            if (thumbTex == 0 || thumbW != w || thumbH != h) {
                if (thumbTex != 0) GLES30.glDeleteTextures(1, intArrayOf(thumbTex), 0)
                thumbTex = newTexture(w, h, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE)
                thumbW = w; thumbH = h
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            attach(thumbTex)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            val matrix = floatArrayOf(2f / rect.w, 0f, 0f, 0f, 2f / rect.h, 0f,
                -1f - 2f * rect.x / rect.w, -1f - 2f * rect.y / rect.h, 1f)
            draw(w, h, matrix, paperArgb, fbo)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            val bytes = w * h * 4
            val buf = boardThumbReadback?.takeIf { it.capacity() >= bytes }
                ?: ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).also { boardThumbReadback = it }
            buf.clear()
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Board preview readback failed" }
            val pixels = ByteArray(bytes)
            buf.rewind(); buf.get(pixels)
            return Thumbnails.downsample(pixels, w, h, THUMB_SUPERSAMPLE)
        } finally {
            setFramePreviews(restore)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, bindings[0])
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, bindings[1])
            GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            if (scissor) GLES30.glEnable(GLES30.GL_SCISSOR_TEST) else GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        }
    }
    fun setLayerVisible(id: String, visible: Boolean) { layers[id]?.visible = visible }

    /**
     * How a layer composites over the ones beneath it (JB-2.20b). All 27 [BlendMode]s are supported;
     * a stack in which every visible layer is NORMAL stays on the fixed-function path, and one
     * non-NORMAL layer sends the whole stack down [drawComposited].
     */
    fun setLayerBlend(id: String, mode: BlendMode) { layers[id]?.blend = mode }
    fun layerBlend(id: String): BlendMode = layers[id]?.blend ?: BlendMode.NORMAL
    fun tileCount(id: String): Int = layers[id]?.tiles?.size ?: 0

    /** Empties a layer as one undoable step. */
    fun clearLayer(id: String) {
        val layer = storeOf(id) ?: return
        if (layer.floats.values.all { it.isEmpty() }) { replaceTiles(id,visibleTiles(layer).keys.associateWith{null}); return }
        // A media layer: its float state is emptied in the same step as its look, or the next look render brings it back.
        val changes = ArrayList<UndoLog.TileChange<Int>>()
        layer.floats.forEach { (store, tiles) -> tiles.forEach { (k, t) -> changes.add(UndoLog.TileChange(MediaStores.id(id, store), k, t, null)) } }
        layer.floats.clear()
        undo.push(UndoLog.Step(changes))
        if (replaceTiles(id, visibleTiles(layer).keys.associateWith { null }) > 0) undo.mergeNewest(2)
        reportMediaRestored(changes)
    }

    // ── tile I/O (save, open, export — JB-0.08) ─────────────────────────────

    /**
     * True between [beginStroke] and [endStroke]/[cancelStroke]. The stroke in flight lives only in
     * the stroke buffer, not in any tile, so a snapshot taken now would silently leave it out:
     * autosave checks this and waits for the stroke to end instead (JB-0.08b).
     */
    val strokeInProgress: Boolean get() = strokeLayer != null

    /** Layer ids, bottom → top. */
    fun layerIds(): List<String> = layers.keys.toList()

    /** Keys of every tile a layer has (sparse). */
    fun tileKeys(layerId: String): List<Long> = storeOf(layerId)?.tiles?.keys?.toList() ?: emptyList()

    /**
     * A tile's pixels: 256×256 premultiplied RGBA8, 262,144 bytes, row 0 = the tile's TOP document
     * row (no flip needed). Null if the tile does not exist.
     */
    fun readTile(layerId: String, key: Long): ByteArray? {
        val store=storeOf(layerId) ?: return null
        val tex = visibleTiles(store)[key] ?: return null
        val buf = tileReadback
        buf.clear()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        attach(tex)
        try {
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "tile framebuffer is unavailable"
            }
            GLES30.glReadPixels(0, 0, size, size, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            val error = GLES30.glGetError()
            check(error == GLES30.GL_NO_ERROR) { "tile readback failed (GL $error)" }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
        val out = ByteArray(size * size * 4)
        buf.rewind()
        buf.get(out)
        return out
    }

    /**
     * One pixel of a layer at a DOCUMENT point, as 4 premultiplied RGBA8 bytes, or null where the layer has no tile (JB-2.03a: the
     * eyedropper reads one pixel per move, not a whole 256 KB tile).
     */
    fun readPixel(layerId: String, docX: Int, docY: Int): ByteArray? {
        val tx = Math.floorDiv(docX, size)
        val ty = Math.floorDiv(docY, size)
        val store=storeOf(layerId) ?: return null
        val tex = visibleTiles(store)[Tiles.key(tx,ty)] ?: return null
        val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        attach(tex)
        GLES30.glReadPixels(docX - tx * size, docY - ty * size, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val out = ByteArray(4)
        buf.rewind()
        buf.get(out)
        return out
    }

    /**
     * Sets a tile's pixels (same layout as [readTile]). For loading a document: NOT undoable, and it
     * clears nothing else. Creates the layer and the tile if needed.
     */
    fun writeTile(layerId: String, key: Long, rgba: ByteArray) {
        require(rgba.size == size * size * 4) { "tile must be ${size * size * 4} bytes, got ${rgba.size}" }
        val layer = storeOrCreate(layerId)
        uploadTile(layer.tiles.getOrPut(key) { newLayerTile() }, rgba)
    }

    /**
     * Replaces some of a layer's tiles as ONE undoable step — for everything that edits pixels
     * without a brush: fill, lasso fill, moving a selection (JB-2.06b, 2.07b, 2.05). A null value
     * deletes that tile. Same layout as [readTile]. Copy-on-write like [endStroke]: the old texture
     * becomes the undo snapshot, so nothing is copied. Returns how many tiles changed.
     */
    fun replaceTiles(layerId: String, tiles: Map<Long, ByteArray?>): Int {
        check(!strokeInProgress) { "replaceTiles during a stroke would be undone out of order" }
        val layer = storeOf(layerId) ?: error("no layer $layerId")
        for (rgba in tiles.values) {
            require(rgba == null || rgba.size == size * size * 4) { "tile must be ${size * size * 4} bytes" }
        }
        val changes=ArrayList<UndoLog.TileChange<Int>>()
        try {
            for((key,rgba) in tiles) {
                val source=rgba?.let{uploadTile(newLayerTile(),it)}
                try {
                    val slices=layer.plan?.tileSlices(key) ?: listOf(RegionTileSlice(RegionPlane(""),RegionTileRect(0,0,size,size)))
                    for((plane,owned) in slices.groupBy{it.plane.celId}) {
                        val cel=layer.sharedCel?.let{plane}
                        val before=planeTiles(layer,cel)[key]
                        if(before==null && rgba==null)continue
                        val full=owned.size==1 && owned[0].rect==RegionTileRect(0,0,size,size)
                        if(rgba==null && full) {changes.add(UndoLog.TileChange(layer.id,key,before,null,cel));continue}
                        val after=newLayerTile()
                        changes.add(UndoLog.TileChange(layer.id,key,before,after,cel))
                        initializeTile(after,before,layer.isMask)
                        for(slice in owned) {
                            if(source!=null)copyTextureRect(source,after,slice.rect)else {
                                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,copyFbo);attach(after)
                                GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
                                val r=slice.rect;GLES30.glScissor(r.x,r.y,r.w,r.h)
                                val bg=if(layer.isMask)1f else 0f;GLES30.glClearColor(bg,bg,bg,bg);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                                GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
                            }
                        }
                    }
                }finally{source?.let(::recycleLayerTex)}
            }
        }catch(e:Throwable){changes.forEach{it.after?.let(::recycleLayerTex)};throw e}
        finally{GLES30.glDisable(GLES30.GL_SCISSOR_TEST);GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0)}
        changes.forEach{put(it.layerId,it.key,it.after,it.celId)}
        if(changes.isNotEmpty())undo.push(UndoLog.Step(changes))
        return changes.size
    }

    private fun uploadTile(tex: Int, rgba: ByteArray): Int {
        val buf = ByteBuffer.allocateDirect(rgba.size).order(ByteOrder.nativeOrder())
        buf.put(rgba).rewind()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, size, size, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return tex
    }

    /** Empties the whole document (all layers, all undo). For "open another document". */
    fun resetDocument() {
        cancelStroke()
        mediaStroke = null; mediaStrokeHeld.clear()
        undo.clear()
        layers.values.forEach { l -> l.ownedTextures().forEach(::recycleLayerTex); l.mask?.ownedTextures()?.forEach(::recycleLayerTex) }
        layers.clear()
        boardDocument = null
        framePreviews = emptyMap()
    }

    fun layerOpacity(id: String): Float = layers[id]?.opacity ?: 1f
    fun layerVisible(id: String): Boolean = layers[id]?.visible ?: true

    /** Metadata is renderer-independent. This backend implements PAINT planes; vectors use the same board model. */
    fun setBoardDocument(doc: JbDocument) {
        check(!strokeInProgress) { "A board cannot change during a stroke" }
        val errors=DocOps.validate(doc); require(errors.isEmpty()) { errors.joinToString("; ") }
        require(doc.layers.all { it.kind.hasPixels && it.animatedIn==null }) { "This renderer paints raster layers" }
        val previews = framePreviews.filter { (boardId, frameId) ->
            doc.boards.any { it.id == boardId && it.kind == BoardKind.ANIMATION && it.frames.any { f -> f.id == frameId } }
        }
        // Validate all plans before changing any live layer.
        val plans = RegionDocumentOps.paintPlans(doc, previews)
        for (meta in doc.layers) {
            val layer=layers[meta.id] ?: error("No live layer ${meta.id}")
            val shared=meta.sharedCelId ?: meta.cels.single().id
            if (layer.sharedCel==null) { layer.sharedCel=shared; layer.cels[shared]?.let { layer.tiles.putAll(it) }; layer.cels[shared]=layer.tiles }
            check(layer.sharedCel==shared) { "Shared paint identity cannot silently change" }
            meta.cels.forEach { layer.cels.getOrPut(it.id) { HashMap() } }
            val plan=plans.getValue(meta.id)
            if(layer.plan?.frames!=plan.frames)invalidateProjection(layer)
            layer.plan=plan
        }
        boardDocument=doc
        framePreviews=previews
        if(doc.boards.none { it.id == tileBoardId && it.kind == BoardKind.CANVAS }) tileBoardId=null
        if(doc.boards.none { it.id == onionBoardId && it.kind == BoardKind.ANIMATION }) onionBoardId=null
    }

    /** Switch display planes only. Paint always begins on the document's saved frame. */
    fun setFramePreviews(frames: Map<String, String>) {
        check(!strokeInProgress) { "Playback cannot change during a stroke" }
        val doc = boardDocument
        if (doc == null) { require(frames.isEmpty()) { "No board document is attached" }; return }
        val requested = frames.toMap()
        if (requested == framePreviews) return
        val plans = RegionDocumentOps.paintPlans(doc, requested)
        for ((id, plan) in plans) {
            val layer = layers.getValue(id)
            if (layer.plan?.frames != plan.frames) invalidateProjection(layer)
            layer.plan = plan
        }
        framePreviews = requested
    }

    /** Execute bounded physical copies/drops, then publish metadata and all pixels as ONE history step. */
    fun applyBoardChange(change: RegionChange) {
        check(!strokeInProgress)
        val before=boardDocument ?: error("No board document is attached")
        val errors=DocOps.validate(change.doc); require(errors.isEmpty()){errors.joinToString("; ")}
        require(change.doc.layers.map{it.id}==before.layers.map{it.id}) { "Board edits cannot replace the layer stack" }
        require(change.doc.layers.all { it.kind.hasPixels && it.animatedIn == null }) {
            "This renderer paints raster layers"
        }
        val translated = change.transfers.isNotEmpty() || change.maskTransfers.isNotEmpty() ||
            change.clears.isNotEmpty() || change.maskClears.isNotEmpty()
        val maxTransferTiles = (MAX_REGION_PX / (size.toLong() * size)).toInt()
        // Preflight all original plane identities and bounds before allocating or publishing anything.
        val touched = HashSet<Pair<String, Pair<String?, Long>>>()
        fun preflight(layer: Layer, source: Map<Long, Int>, destination: Map<Long, Int>, cel: String?,
                      sourceRect: RectPx, destinationRect: RectPx) {
            require(sourceRect.w.toLong() * sourceRect.h <= MAX_REGION_PX) {
                "These cells are too large to swap on this phone"
            }
            for (slice in RegionTransferTiles.slices(sourceRect, destinationRect)) {
                if (source[slice.sourceKey] != null || destination[slice.destinationKey] != null) {
                    touched.add(layer.id to (cel to slice.destinationKey))
                    require(touched.size <= maxTransferTiles) { "These painted cells are too large to swap on this phone" }
                }
            }
        }
        for (transfer in change.transfers) {
            val layer = layers[transfer.layerId] ?: error("No layer ${transfer.layerId}")
            val source = layer.cels[transfer.fromCelId] ?: error("No source cel ${transfer.fromCelId}")
            require(change.doc.layers.first { it.id == transfer.layerId }.cels.any { it.id == transfer.toCelId }) {
                "No destination cel ${transfer.toCelId}"
            }
            val destination = layer.cels[transfer.toCelId].orEmpty()
            preflight(layer, source, destination, transfer.toCelId, transfer.sourceRect, transfer.destinationRect)
        }
        for (transfer in change.maskTransfers) {
            val mask = layers[transfer.layerId]?.mask ?: error("No mask on layer ${transfer.layerId}")
            preflight(mask, mask.tiles, mask.tiles, null, transfer.sourceRect, transfer.destinationRect)
        }
        for (clear in change.clears) {
            val layer = layers[clear.layerId] ?: error("No layer ${clear.layerId}")
            val store = layer.cels[clear.celId] ?: error("No cel ${clear.celId}")
            preflight(layer, emptyMap(), store, clear.celId, clear.rect, clear.rect)
        }
        for (clear in change.maskClears) {
            val mask = layers[clear.layerId]?.mask ?: error("No mask on layer ${clear.layerId}")
            preflight(mask, emptyMap(), mask.tiles, null, clear.rect, clear.rect)
        }
        val pending=LinkedHashMap<Pair<String,Pair<String?,Long>>,UndoLog.TileChange<Int>>()
        var published=false
        fun checkRoom(address: Pair<String, Pair<String?, Long>>) {
            if (translated && address !in pending) require(pending.size < maxTransferTiles) {
                "These painted cells are too large to swap on this phone"
            }
        }
        fun stage(layer: Layer,cel: String,key: Long,source: Int?,rect: RegionTileRect) {
            val address=layer.id to (cel to key)
            checkRoom(address)
            val old=pending[address]
            val original=old?.before ?: layer.cels[cel]?.get(key)
            val made=newLayerTile()
            try {
                initializeTile(made,old?.after ?: original,false)
                if(source==null) {
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,copyFbo); attach(made)
                    GLES30.glEnable(GLES30.GL_SCISSOR_TEST); GLES30.glScissor(rect.x,rect.y,rect.w,rect.h)
                    GLES30.glClearColor(0f,0f,0f,0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                    GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
                } else copyTextureRect(source,made,rect)
                old?.after?.let(::recycleLayerTex)
                pending[address]=UndoLog.TileChange(layer.id,key,original,made,cel)
            } catch(e:Throwable){recycleLayerTex(made); throw e}
        }
        fun stageTranslated(layer: Layer, cel: String?, slice: TranslatedTileSlice, source: Int?) {
            val key = slice.destinationKey
            val address = layer.id to (cel to key)
            val original = planeTiles(layer, cel)[key]
            // Two absent planes are already equal; do not manufacture empty tiles across a sparse cell.
            if (source == null && original == null && address !in pending) return
            val old = pending[address]
            val target = old?.after ?: run {
                checkRoom(address)
                val made = newLayerTile()
                try { initializeTile(made, original, layer.isMask) }
                catch (e: Throwable) { recycleLayerTex(made); throw e }
                pending[address] = UndoLog.TileChange(layer.id, key, old?.before ?: original, made, cel)
                made
            }
            if (source != null) copyTextureRectTranslated(source, target, slice.source, slice.destination)
            else {
                val rect = slice.destination
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, copyFbo); attach(target)
                GLES30.glEnable(GLES30.GL_SCISSOR_TEST); GLES30.glScissor(rect.x, rect.y, rect.w, rect.h)
                val bg = if (layer.isMask) 1f else 0f
                GLES30.glClearColor(bg, bg, bg, bg); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Board tile replacement failed" }
            }
        }
        try {
            for (clear in change.clears) {
                val layer = layers.getValue(clear.layerId)
                for (slice in RegionTransferTiles.slices(clear.rect, clear.rect)) stageTranslated(layer, clear.celId, slice, null)
            }
            for (clear in change.maskClears) {
                val mask = requireNotNull(layers.getValue(clear.layerId).mask)
                for (slice in RegionTransferTiles.slices(clear.rect, clear.rect)) stageTranslated(mask, null, slice, null)
            }
            for(copy in change.copies) {
                val layer=layers[copy.layerId] ?: error("No layer ${copy.layerId}")
                val source=layer.cels[copy.fromCelId] ?: error("No source cel ${copy.fromCelId}")
                val destination=layer.cels.getOrPut(copy.toCelId){HashMap()}
                // Include destination keys: copying blank source must clear existing owned pixels too.
                val keys=(source.keys+destination.keys+pending.keys.filter {
                    it.first==copy.layerId && (it.second.first==copy.fromCelId || it.second.first==copy.toCelId)
                }.map{it.second.second}).toSet()
                val plan=RegionPaintPlan("copy-shared",listOf(RegionFrame("copy-board",copy.rect,"copy-frame","copy-target")))
                for(key in keys) for(slice in plan.tileSlices(key)) if(slice.plane.celId=="copy-target") {
                    val sourceAddress=copy.layerId to (copy.fromCelId to key)
                    val texture=if(sourceAddress in pending)pending.getValue(sourceAddress).after else source[key]
                    stage(layer,copy.toCelId,key,texture,slice.rect)
                }
            }
            for (transfer in change.transfers) {
                val layer = layers.getValue(transfer.layerId)
                // Live stores stay immutable until every reciprocal transfer is staged.
                val source = layer.cels.getValue(transfer.fromCelId)
                for (slice in RegionTransferTiles.slices(transfer.sourceRect, transfer.destinationRect)) {
                    stageTranslated(layer, transfer.toCelId, slice, source[slice.sourceKey])
                }
            }
            for (transfer in change.maskTransfers) {
                val mask = requireNotNull(layers.getValue(transfer.layerId).mask)
                for (slice in RegionTransferTiles.slices(transfer.sourceRect, transfer.destinationRect)) {
                    stageTranslated(mask, null, slice, mask.tiles[slice.sourceKey])
                }
            }
            for(drop in change.drops) {
                val layer=layers[drop.layerId] ?: error("No layer ${drop.layerId}")
                val keys=(layer.cels[drop.celId].orEmpty().keys+pending.keys.filter{
                    it.first==drop.layerId && it.second.first==drop.celId
                }.map{it.second.second}).toSet()
                for(key in keys) {
                    val tex=layer.cels[drop.celId]?.get(key)
                    val address=layer.id to (drop.celId to key)
                    val old=pending[address]; old?.after?.let(::recycleLayerTex)
                    pending[address]=UndoLog.TileChange(layer.id,key,old?.before ?: tex,null,drop.celId)
                }
            }
            published=true
            pending.values.forEach { c->put(c.layerId,c.key,c.after,c.celId) }
            setBoardDocument(change.doc)
            if(before!=change.doc || pending.isNotEmpty()) undo.push(UndoLog.Step(pending.values.toList(),
                documentBefore=before,documentAfter=change.doc))
        } catch(e:Throwable) {
            // Restore every published address before releasing prepared textures.
            if(published) { pending.values.forEach{c->put(c.layerId,c.key,c.before,c.celId)}; setBoardDocument(before) }
            pending.values.forEach { c->c.after?.let(::recycleLayerTex) }
            throw e
        } finally { GLES30.glDisable(GLES30.GL_SCISSOR_TEST); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0) }
    }

    fun celTileKeys(layerId: String,celId: String): List<Long> = layers[layerId]?.cels?.get(celId)?.keys?.toList().orEmpty()
    fun writeCelTile(layerId: String,celId: String,key: Long,rgba: ByteArray) {
        require(rgba.size==size*size*4)
        val layer=layers[layerId] ?: error("No live layer $layerId")
        val store=layer.cels[celId] ?: error("No live cel $celId")
        uploadTile(store.getOrPut(key){newLayerTile()},rgba); invalidateProjection(layer)
    }
    fun readCelTile(layerId: String,celId: String,key: Long): ByteArray? = readTextureTile(layers[layerId]?.cels?.get(celId)?.get(key))

    private fun readTextureTile(tex: Int?): ByteArray? {
        if(tex==null)return null
        val buf=tileReadback; buf.clear()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo); attach(tex)
        try {
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)==GLES30.GL_FRAMEBUFFER_COMPLETE)
            GLES30.glReadPixels(0,0,size,size,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,buf)
            check(GLES30.glGetError()==GLES30.GL_NO_ERROR)
        } finally { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0) }
        return ByteArray(size*size*4).also { buf.rewind(); buf.get(it) }
    }

    private fun planeTiles(layer: Layer,celId: String?): HashMap<Long,Int> =
        if(celId==null)layer.tiles else layer.cels.getOrPut(celId){HashMap()}
    private fun invalidateProjection(layer: Layer) {
        layer.projected.values.forEach(::recycleLayerTex); layer.projected.clear(); layer.visibleProjection=null
    }
    private fun visibleTiles(layer: Layer): Map<Long,Int> {
        if (layer.plan?.frames.isNullOrEmpty()) return layer.tiles
        layer.visibleProjection?.let { return it }
        val bindings=IntArray(2)
        GLES30.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING,bindings,0)
        GLES30.glGetIntegerv(GLES30.GL_READ_FRAMEBUFFER_BINDING,bindings,1)
        val scissor=GLES30.glIsEnabled(GLES30.GL_SCISSOR_TEST)
        try { return projectedTiles(layer) } finally {
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER,bindings[0])
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER,bindings[1])
            if(scissor)GLES30.glEnable(GLES30.GL_SCISSOR_TEST) else GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        }
    }
    private fun projectedTiles(layer: Layer): Map<Long,Int> {
        val plan=layer.plan ?: return layer.tiles
        if(plan.frames.isEmpty())return layer.tiles
        layer.visibleProjection?.let{return it}
        val keys=LinkedHashSet<Long>()
        keys.addAll(layer.tiles.keys)
        plan.frames.forEach { keys.addAll(layer.cels[it.celId]?.keys.orEmpty()) }
        val result=LinkedHashMap<Long,Int>()
        for(key in keys) {
            val slices=plan.tileSlices(key)
            if(slices.size==1) { layer.cels[slices[0].plane.celId]?.get(key)?.let{result[key]=it}; continue }
            val sources=slices.mapNotNull { slice -> layer.cels[slice.plane.celId]?.get(key)?.let{slice.rect to it} }
            if(sources.isEmpty())continue
            val tex=layer.projected.getOrPut(key) {
                val made=newLayerTile()
                try { initializeTile(made,null,false); sources.forEach { (rect,source)->copyTextureRect(source,made,rect) }; made }
                catch(e:Throwable) { recycleLayerTex(made); throw e }
            }
            result[key]=tex
        }
        layer.visibleProjection=result
        return result
    }
    private fun initializeTile(target: Int,source: Int?,white: Boolean) {
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,copyFbo); attach(target)
        val bg=if(white)1f else 0f; GLES30.glClearColor(bg,bg,bg,bg); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if(source!=null)copyTextureRect(source,target,RegionTileRect(0,0,size,size))
    }
    private fun copyTextureRect(source: Int,target: Int,r: RegionTileRect) {
        copyTextureRectTranslated(source, target, r, r)
    }
    private fun copyTextureRectTranslated(source: Int, target: Int, from: RegionTileRect, to: RegionTileRect) {
        require(from.w == to.w && from.h == to.h)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER,readFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,source,0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER,copyFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_DRAW_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,target,0)
        GLES30.glBlitFramebuffer(from.x,from.y,from.right,from.bottom,to.x,to.y,to.right,to.bottom,GLES30.GL_COLOR_BUFFER_BIT,GLES30.GL_NEAREST)
        check(GLES30.glGetError()==GLES30.GL_NO_ERROR) { "Board tile copy failed" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo)
    }

    // ── strokes ──────────────────────────────────────────────────────────────

    /** Starts a stroke. [argb] is the brush colour (alpha ignored — [opacity] is the stroke's opacity). */
    fun beginStroke(layerId: String, argb: Int, opacity: Float, accumulate: Accumulate,
                    blend: StrokeBlend, tip: TipShape,
                    grain: GrainMath.StrokeGrain = GrainMath.StrokeGrain(GrainMath.GrainUniforms.OFF, GrainMath.GrainUniforms.OFF),
                    smudge: SmudgeParams? = null, tuft: TuftShading? = null) {
        cancelStroke()
        require(smudge == null || tileBoardId == null) { "Turn tiling off to use smudge" }
        if (framePreviews.isNotEmpty()) setFramePreviews(emptyMap())
        strokeTravel.reset()
        strokeLayer = storeOf(layerId) ?: error("no layer $layerId")
        strokePlan = strokeLayer?.plan
        strokeTileRect=tileBoardId?.let { TilePainting.board(requireNotNull(boardDocument),it).rect }
        strokeIsRgba = smudge != null
        colR = ((argb shr 16) and 0xFF) / 255f
        colG = ((argb shr 8) and 0xFF) / 255f
        colB = (argb and 0xFF) / 255f
        this.opacity = opacity.coerceIn(0f, 1f)
        this.accumulate = accumulate
        this.blend = blend
        this.tip = tip
        // A smudge reads the layer as it stands NOW (pen-down): the stroke buffer is not the layer until pen-up, so the
        // pixels it picks up from stay put for the whole stroke. Colours are decided on the GL thread, where a tile can be read.
        smudgeTexturePickup = smudge?.texturePickup?.coerceIn(0f, 1f) ?: 0f
        smudgePaint = smudge?.paint ?: false
        this.smudge = if (smudge == null) null else SmudgeStroke(
            TileReader { tx, ty -> readTile(layerId, Tiles.key(tx, ty)) },
            SmudgeCarried(colR, colG, colB, 1f, smudge.pickup.coerceIn(0f, 1f), smudge.load.coerceIn(0f, 1f)),
            tip,
        )
        // A grain whose picture is missing is drawn as OFF: a missing picture must never read as "paint
        // everywhere". The picture is bound per batch in addDabs, but resolved here, once per stroke.
        val tipTex = if (grain.tip.enabled) grains.textureFor(grain.tip.asset) else null
        val p = loadedPaper?.paper
        val surface = p?.surface
        strokeSurface = surface
        strokePaperScale = p?.scale ?: 1f
        strokePaperResponse = if (tuft != null) tuft.paperResponse else grain.paperResponse
        // Old direct engine callers have grain settings but no document/response section.
        if (p == null && strokePaperResponse.isDefault && (grain.paper.enabled || (tuft?.paperPitchPx ?: 0f)>0f))
            strokePaperResponse = PaperResponse(influence=1f)
        strokePaperInfluence = strokePaperResponse.influence*(p?.bite ?: 1f)
        val documentGrain = documentPaperGrain(grain.paper,p,strokePaperResponse)
        val paperTex = if (documentGrain.enabled) grains.textureFor(documentGrain.asset) else null
        this.grain = GrainMath.StrokeGrain(
            tip = if (tipTex != null) grain.tip else GrainMath.GrainUniforms.OFF,
            paper = if (paperTex != null) documentGrain else GrainMath.GrainUniforms.OFF,
            paperResponse = strokePaperResponse,
        )
        tipGrainTex = tipTex ?: grains.placeholder
        paperGrainTex = paperTex ?: grains.placeholder
        val tooth = if (tuft == null) null else if (p == null) grains.textureFor(tuft.paperAsset) else surfaceTexture
        tuftPaperTex = tooth ?: grains.placeholder
        this.tuft = tuft?.let {
            if(tooth == null) it.copy(paperPitchPx=0f) else if(p != null && surface != null)
                it.copy(paperAsset=surface.file,paperPitchPx=if(strokePaperInfluence>0f)surface.texelPx*p.scale else 0f,tooth=it.tooth)
            else it
        }
    }

    /** The cap every dab of the active stroke should carry (see Accumulate). */
    fun capForStroke(): Float = if (accumulate == Accumulate.WASH) opacity else 1f

    /** Renders dabs into the stroke buffer. */
    fun addDabs(dabs: List<Dab>) {
        if (strokeLayer == null || dabs.isEmpty()) return
        smudge?.let { addSmudgeDabs(it, dabs); return }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, size, size)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        // New stroke tiles are made HERE, before the grain pictures are bound: making a texture binds it on
        // the active unit and ends by binding 0, which would silently unbind the tip picture on unit 0 for
        // the rest of the batch (a stroke crossing into a new tile lost its grain: review of JB-1.05c).
        val directed = dabs.map { dab ->
            strokeTravel.update(dab.x,dab.y)
            if (dab.travelKnown) dab else dab.copy(travelX=strokeTravel.x,travelY=strokeTravel.y,travelKnown=true)
        }
        val tile=strokeTileRect
        val wrapped=if(tile == null) directed else directed.flatMap { TilePainting.dabs(tile,it) }
        val buckets = Tiles.bucket(wrapped, size).filterKeys { tile == null || TilePainting.clip(tile,it) != null }
        require(tile == null || (strokeTiles.keys + buckets.keys).toSet().size <= TilePainting.MAX_TILES) { "This tile stroke is too large" }
        for (key in buckets.keys) strokeTiles.getOrPut(key) { newStrokeTile() }
        dabProg.use()
        GLES30.glUniform1f(dabProg.loc("u_tileSize"), size.toFloat())
        GLES30.glUniform1f(dabProg.loc("u_aspect"), tip.aspect)
        GLES30.glUniform1f(dabProg.loc("u_corner"), tip.corner)
        GLES30.glUniform1f(dabProg.loc("u_taper"), tip.taper)
        GLES30.glUniform1f(dabProg.loc("u_hardness"), tip.hardness)
        GLES30.glUniform1f(dabProg.loc("u_minPx"), tip.minPx)
        setGrainUniforms(dabs[dabs.size - 1])
        GLES30.glBindVertexArray(dabVao)

        for ((key, list) in buckets) {
            val tex = strokeTiles.getValue(key)
            attach(tex)
            clipTileStroke(key)
            GLES30.glUniform2f(dabProg.loc("u_tileOrigin"), (Tiles.tx(key) * size).toFloat(), (Tiles.ty(key) * size).toFloat())
            fillInstances(list)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, list.size * 68, instanceData, GLES30.GL_STREAM_DRAW)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, list.size)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * A tuft stroke's footprints (R9 §3B), into the same single-channel stroke buffer with the same blending as
     * [addDabs] — so commit, preview and undo are the stamp engine's own. Needs a stroke begun with a [TuftShading].
     */
    fun addTuftStamps(stamps: List<TuftStamp>) {
        val shading = tuft
        if (strokeLayer == null || stamps.isEmpty() || shading == null) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, size, size)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        // Stroke tiles first, for the reason addDabs gives: making one binds texture 0 on the active unit.
        val tile=strokeTileRect
        val wrapped=if(tile == null) stamps else stamps.flatMap { TilePainting.stamps(tile,it) }
        val buckets = TuftMath.bucket(wrapped, size).filterKeys { tile == null || TilePainting.clip(tile,it) != null }
        require(tile == null || (strokeTiles.keys + buckets.keys).toSet().size <= TilePainting.MAX_TILES) { "This tile stroke is too large" }
        for (key in buckets.keys) strokeTiles.getOrPut(key) { newStrokeTile() }
        tuftProg.use()
        GLES30.glUniform1f(tuftProg.loc("u_tileSize"), size.toFloat())
        GLES30.glUniform1f(tuftProg.loc("u_bristles"), shading.bristles)
        GLES30.glUniform1f(tuftProg.loc("u_streakPx"), shading.streakPx)
        GLES30.glUniform1f(tuftProg.loc("u_tooth"), shading.tooth)
        GLES30.glUniform1f(tuftProg.loc("u_seed"), shading.seed)
        GLES30.glUniform1f(tuftProg.loc("u_action"), shading.action)
        // Both samplers get a real picture every batch: an unset sampler reads unit 0, which may be the tile being drawn.
        GLES30.glUniform1i(tuftProg.loc("u_tipGrain"), 0)
        setPaperSurfaceUniforms(tuftProg)
        setPaperResponseUniforms(tuftProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, grains.placeholder)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tuftPaperTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1f(tuftProg.loc("u_tipGrainPitchPx"), 0f)
        GLES30.glUniform1f(tuftProg.loc("u_paperGrainPitchPx"), shading.paperPitchPx)
        GLES30.glBindVertexArray(tuftVao)
        for ((key, list) in buckets) {
            attach(strokeTiles.getValue(key))
            clipTileStroke(key)
            GLES30.glUniform2f(tuftProg.loc("u_tileOrigin"), (Tiles.tx(key) * size).toFloat(), (Tiles.ty(key) * size).toFloat())
            val need = list.size * TuftStamp.FLOATS
            if (tuftInstanceData.capacity() < need) tuftInstanceData = newFloats(need * 2)
            tuftInstanceData.clear()
            for (t in list) {
                tuftInstanceData.put(t.ax).put(t.ay).put(t.bx).put(t.by)
                    .put(t.ra).put(t.rb).put(t.flow).put(t.cap)
                    .put(t.dry).put(t.bias).put(t.splay).put(t.arc)
                    .put(t.kind.toFloat()).put(t.graze).put(t.travelX).put(t.travelY)
            }
            tuftInstanceData.flip()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, need * 4, tuftInstanceData, GLES30.GL_STREAM_DRAW)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, list.size)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * A smudge stroke's dabs (JB-1.06): the same stroke buffer and the same fixed-function blending as a stamp, but each dab
     * carries the colour it paints with (decided by [SmudgeStroke] before anything is bound, because reading a tile binds the
     * framebuffer) and the buffer is RGBA. Nothing here reads the canvas.
     */
    private fun addSmudgeDabs(sm: SmudgeStroke, dabs: List<Dab>) {
        val colours = sm.colours(dabs)
        val at = IdentityHashMap<Dab, Int>(dabs.size * 2)
        for ((i, d) in dabs.withIndex()) at[d] = i
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, size, size)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        val buckets = Tiles.bucket(dabs, size)
        for (key in buckets.keys) strokeTiles.getOrPut(key) { newStrokeTile() }
        smudgeProg.use()
        GLES30.glUniform1f(smudgeProg.loc("u_tileSize"), size.toFloat())
        GLES30.glUniform1f(smudgeProg.loc("u_aspect"), tip.aspect)
        GLES30.glUniform1f(smudgeProg.loc("u_corner"), tip.corner)
        GLES30.glUniform1f(smudgeProg.loc("u_taper"), tip.taper)
        GLES30.glUniform1f(smudgeProg.loc("u_hardness"), tip.hardness)
        GLES30.glUniform1f(smudgeProg.loc("u_minPx"), tip.minPx)
        setGrainUniforms(dabs.last(), smudgeProg)
        GLES30.glUniform1f(smudgeProg.loc("u_texturePickup"), smudgeTexturePickup)
        GLES30.glBindVertexArray(smudgeVao)
        for ((key, list) in buckets) {
            attach(strokeTiles.getValue(key))
            GLES30.glUniform2f(smudgeProg.loc("u_tileOrigin"), (Tiles.tx(key) * size).toFloat(), (Tiles.ty(key) * size).toFloat())
            if (smudgeTexturePickup > 0f) bindPickupTiles(key)
            val need = list.size * 21
            if (smudgeInstanceData.capacity() < need) smudgeInstanceData = newFloats(need * 2)
            smudgeInstanceData.clear()
            for (d in list) {
                val c = (at[d] ?: 0) * 4
                smudgeInstanceData.put(d.x).put(d.y).put(d.radius).put(d.angle).put(d.flow).put(d.cap)
                    .put(colours[c]).put(colours[c + 1]).put(colours[c + 2]).put(colours[c + 3])
                smudgeInstanceData.put(d.travelX).put(d.travelY)
                putContact(smudgeInstanceData, d)
            }
            smudgeInstanceData.flip()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, list.size * 84, smudgeInstanceData, GLES30.GL_STREAM_DRAW)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, list.size)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** JB-9.03: the fixed document surface; both brush programs bind it on unit 1. */
    private fun setPaperSurfaceUniforms(program: GlProgram) {
        // Brush deposition uses document floats (accurate to +/-1e6 px); only display uses local frames.
        GLES30.glUniform1i(program.loc("u_paperSurface"), 1)
        GLES30.glUniform1f(program.loc("u_paperTexelPx"), (strokeSurface?.texelPx ?: GrainMath.SURFACE_TEXEL_PX)*strokePaperScale)
        GLES30.glUniform1f(program.loc("u_paperSize"), strokeSurface?.size?.toFloat() ?: GrainMath.SURFACE_SIZE)
        GLES30.glUniform1f(program.loc("u_paperHexTexels"), strokeSurface?.hexTexels ?: GrainMath.SURFACE_HEX_TEXELS)
        GLES30.glUniform1f(program.loc("u_paperSlopeRange"), strokeSurface?.slopeRange ?: GrainMath.SURFACE_SLOPE_RANGE)
        GLES30.glUniform1f(program.loc("u_paperHeightMean"), strokeSurface?.heightMean ?: 0.5f)
        // The fluid map on unit 2, always bound (an unset sampler reads unit 0, which may be the tile being drawn).
        val fluid = fluidTexture
        GLES30.glUniform1i(program.loc("u_paperFluid"), 2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fluid ?: grains.placeholder)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val surface = strokeSurface
        GLES30.glUniform1f(program.loc("u_paperFluidTexelPx"), if (fluid != null && surface?.fluid != null) surface.fluidTexelPx * strokePaperScale else 0f)
        GLES30.glUniform1f(program.loc("u_paperFluidSize"), surface?.fluid?.let { grains.sizeFor(it, "paper").toFloat() } ?: 1f)
        GLES30.glUniform1f(program.loc("u_paperFluidHexTexels"), surface?.fluidHexTexels ?: 150f)
        GLES30.glUniform1i(program.loc("u_paperRotatable"), if (strokeSurface?.rotatable ?: GrainMath.SURFACE_ROTATABLE) 1 else 0)
    }

    /**
     * Everything the dab shader takes for grain (JB-1.05c). The pictures go on units 0 and 1 EVERY batch
     * and are never left to a default: an unset sampler reads unit 0, and whatever is bound there might
     * be the very stroke tile being drawn into -- a feedback loop, which the driver answers by dropping
     * the draw. A grain that is OFF gets the 1x1 placeholder and a pitch of 0, and the shader ignores it.
     *
     * The lean is from the NEWEST dab of the batch (Decision 10): one set of numbers per draw call, and
     * a batch is a few milliseconds of pen. It goes through [GrainMath.tiltAmount] / [GrainMath.leanX] /
     * [GrainMath.leanY], which turn a finger's NaN into "upright, no lean" before the GPU ever sees it.
     */
    private fun setPaperResponseUniforms(program: GlProgram) {
        GLES30.glUniform1f(program.loc("u_paperInfluence"),strokePaperInfluence)
        GLES30.glUniform1f(program.loc("u_paperDirectional"),strokePaperResponse.directional)
        GLES30.glUniform1f(program.loc("u_paperWet"),strokePaperResponse.wet)
    }

    private fun setGrainUniforms(newest: Dab, program: GlProgram = dabProg) {
        setPaperResponseUniforms(program)
        GLES30.glUniform1i(program.loc("u_tipGrain"), 0)
        setPaperSurfaceUniforms(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tipGrainTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, paperGrainTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val tg = grain.tip
        val pg = grain.paper
        GLES30.glUniform1f(program.loc("u_tipGrainPitchPx"), tg.pitchPx)
        GLES30.glUniform1f(program.loc("u_tipDepth"), tg.depth)
        GLES30.glUniform1f(program.loc("u_tipEdge"), tg.edge)
        GLES30.glUniform1f(program.loc("u_tipTiltGradient"), tg.tiltGradient)
        GLES30.glUniform1f(program.loc("u_tipRadial"), tg.radial)
        GLES30.glUniform1f(program.loc("u_paperGrainPitchPx"), pg.pitchPx)
        GLES30.glUniform1f(program.loc("u_paperDepth"), pg.depth)
        GLES30.glUniform1f(program.loc("u_paperEdge"), pg.edge)
        GLES30.glUniform1f(program.loc("u_paperTiltGradient"), pg.tiltGradient)
        GLES30.glUniform1f(program.loc("u_paperRadial"), pg.radial)
        GLES30.glUniform1f(program.loc("u_tiltAmount"), GrainMath.tiltAmount(newest.tilt))
        GLES30.glUniform2f(program.loc("u_leanDir"), GrainMath.leanX(newest.azimuth), GrainMath.leanY(newest.azimuth))
    }

    /** Commits the active stroke into its layer as one undoable step. Returns tiles changed. */
    fun endStroke(): Int {
        val layer = strokeLayer ?: return 0
        val beforeDoc=boardDocument
        val wrappedId=tileBoardId?.takeIf { strokeTileRect != null }
        val changes = ArrayList<UndoLog.TileChange<Int>>()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, size, size)
        GLES30.glDisable(GLES30.GL_BLEND)
        commitProg.use()
        setCommitUniforms(layerOpacity = 1f)
        GLES30.glBindVertexArray(tileVao)
        // The stroke owns one frozen region plan; every physical plane joins this one history step.
        val pending = ArrayList<UndoLog.TileChange<Int>>()
        try {
            for ((key, strokeTex) in strokeTiles) {
                val allSlices = strokePlan?.tileSlices(key) ?: listOf(RegionTileSlice(
                    RegionPlane(layer.sharedCel ?: ""), RegionTileRect(0,0,size,size)))
                val clip=strokeTileRect?.let { TilePainting.clip(it,key) }
                val slices=if(strokeTileRect == null) allSlices else allSlices.mapNotNull { s ->
                    clip?.let { TilePainting.intersect(s.rect,it) }?.let { s.copy(rect=it) }
                }
                for ((planeId, ownedSlices) in slices.groupBy { it.plane.celId }) {
                    val celId = layer.sharedCel?.let { planeId }
                    val store = planeTiles(layer, celId)
                    val before = store[key]
                    if (before == null && blend == StrokeBlend.ERASE && !layer.isMask) continue
                    val after = newLayerTile()
                    pending.add(UndoLog.TileChange(layer.id,key,before,after,celId))
                    initializeTile(after,before,layer.isMask)
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo); attach(after)
                    GLES30.glViewport(0,0,size,size)
                    GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
                    val ox=(Tiles.tx(key)*size).toFloat(); val oy=(Tiles.ty(key)*size).toFloat()
                    for(slice in ownedSlices) {
                    val r=slice.rect; GLES30.glScissor(r.x,r.y,r.w,r.h)
                    GLES30.glUniform2f(commitProg.loc("u_tileOrigin"),ox,oy)
                    GLES30.glUniformMatrix3fv(commitProg.loc("u_docToClip"),1,false,tileToClip(ox,oy),0)
                    bindTextures(before ?: emptyTexOf(layer),strokeTex)
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP,0,4)
                    }
                    GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
                }
            }
            for (c in pending) { planeTiles(layer,c.celId)[c.key]=c.after!! }
            changes.addAll(pending)
            invalidateProjection(layer)
        } catch (e: Throwable) {
            pending.forEach { it.after?.let(::recycleLayerTex) }
            throw e
        } finally { GLES30.glDisable(GLES30.GL_SCISSOR_TEST) }
        GLES30.glBindVertexArray(0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        releaseStrokeTiles()
        strokeLayer = null
        strokePlan = null
        strokeTileRect = null
        smudge = null
        tuft = null
        if (changes.isNotEmpty()) {
            val afterDoc=if(wrappedId != null && beforeDoc != null) BoardDocumentOps.markWrappedStroke(beforeDoc,wrappedId) else beforeDoc
            if(afterDoc != beforeDoc && afterDoc != null) setBoardDocument(afterDoc)
            undo.push(UndoLog.Step(changes,documentBefore=beforeDoc?.takeIf { it != afterDoc },documentAfter=afterDoc?.takeIf { it != beforeDoc }))
        }
        return changes.size
    }

    fun cancelStroke() {
        releaseStrokeTiles()
        strokeLayer = null
        strokePlan = null
        strokeTileRect = null
        smudge = null
        tuft = null
    }

    fun undoStep(): Boolean {
        val s = undo.undo() ?: return false
        val restored = s.documentBefore?.let { target ->
            val opposite = s.documentAfter
            val live = boardDocument
            if (opposite != null && live != null) BoardHistory.restore(target, opposite, live) else target
        }
        if (framePreviews.isNotEmpty()) setFramePreviews(emptyMap())
        s.changes.forEach { put(it.layerId, it.key, it.before, it.celId) }
        reportMediaRestored(s.changes)
        s.stackBefore?.let { restoreStack(it) }
        s.paperBefore?.let { setDocumentPaper(it) }
        restored?.let { setBoardDocument(it) }
        return true
    }

    fun redoStep(): Boolean {
        val s = undo.redo() ?: return false
        val restored = s.documentAfter?.let { target ->
            val opposite = s.documentBefore
            val live = boardDocument
            if (opposite != null && live != null) BoardHistory.restore(target, opposite, live) else target
        }
        if (framePreviews.isNotEmpty()) setFramePreviews(emptyMap())
        s.changes.forEach { put(it.layerId, it.key, it.after, it.celId) }
        reportMediaRestored(s.changes)
        s.stackAfter?.let { restoreStack(it) }
        s.paperAfter?.let { setDocumentPaper(it) }
        restored?.let { setBoardDocument(it) }
        return true
    }

    /** Undo's half of a stack step: back to [target], every layer that survives keeping how it is shown or hidden. */
    private fun restoreStack(target: LayerStack) {
        applyStack(stack(null).restoring(target))
        activeHint = target.activeId
    }


    // ── media stores (MEDIA_ENGINE_PLAN §4 and M5.3c) ────────────────────────
    //
    // A media layer's float state lives beside its RGBA8 look: `<id>#p0 #p1 #paper #w0 #w1` ([MediaStores]), RGBA16F tiles
    // from their own pool, owned and released exactly like look tiles (UndoLog sizes and recycles each by its kind). A
    // media layer has no frames yet (contract point 3), so a float store is per layer, never per cel.
    //
    // Copy-on-write, as endStroke does for paint: [writableMediaTiles] swaps a fresh copy in before the first write to a
    // tile, and the old texture IS the undo snapshot. During a stroke the copies join the stroke's step ([endMediaStroke]);
    // after pen-up (running water) they join whatever media step is on top, or a fresh water step (the Lead's rule).

    /** The look tiles keep the layer's own id; [writableMediaTiles] calls them this. */
    val mediaLook: String get() = MEDIA_LOOK

    /** Called after undo or redo put media tiles back, with each media layer's restored tile keys: stop its simulation and reload. */
    var onMediaRestored: ((layerId: String, keys: Set<Long>) -> Unit)? = null

    /**
     * The ceiling on resident media state (the Lead's rule, [LayerBudget.mediaBudgetBytes]); the app sets it from the phone's
     * memory. A write that would pass it is refused in words ([MediaRoomException]) before anything is swapped.
     */
    var mediaBudgetBytes: Long = Long.MAX_VALUE

    /** Bytes of media state the layers hold now (undo snapshots are the undo budget's business). */
    fun mediaResidentBytes(): Long = layers.values.sumOf { l -> l.floats.values.sumOf { it.size } }.toLong() * MediaStores.tileBytes(size)

    private var mediaStroke: ArrayList<UndoLog.TileChange<Int>>? = null
    private val mediaStrokeHeld = HashSet<Triple<String, String?, Long>>()

    val mediaStrokeInProgress: Boolean get() = mediaStroke != null

    fun beginMediaStroke() {
        check(mediaStroke == null && !strokeInProgress) { "a media stroke inside another stroke would be undone out of order" }
        mediaStroke = ArrayList()
        mediaStrokeHeld.clear()
    }

    /** Pushes the stroke's step (one press, one step). Returns how many tiles it changed. */
    fun endMediaStroke(): Int {
        val changes = mediaStroke ?: return 0
        mediaStroke = null
        mediaStrokeHeld.clear()
        if (changes.isNotEmpty()) undo.push(UndoLog.Step(changes))
        return changes.size
    }

    /**
     * The textures to write for [keys] of [layerId]'s [stores] ([MediaStores.ALL] names, or [mediaLook] for the look), each
     * copied-on-write the first time this step touches it. A missing tile starts empty: no paint, no crush, no water,
     * transparent look. Returns store → key → texture; write only these.
     */
    fun writableMediaTiles(layerId: String, keys: Collection<Long>, stores: Collection<String>): Map<String, Map<Long, Int>> {
        val layer = layers[layerId] ?: error("no layer $layerId")
        val stroke = mediaStroke
        // Outside a stroke, the newest media step's changes by tile: a tile it holds is written in place, and one it
        // dropped (dried water, [dropMediaTiles]) is revived in that same change rather than snapshotted twice.
        val topHeld: Map<Triple<String, String?, Long>, UndoLog.TileChange<Int>> = if (stroke != null) emptyMap() else
            undo.newestExtendable()?.takeIf { MediaStores.isMediaStep(it) }?.changes?.associateBy { Triple(it.layerId, it.celId, it.key) } ?: emptyMap()
        val held: Set<Triple<String, String?, Long>> = stroke?.let { mediaStrokeHeld } ?: topHeld.keys
        val revived = ArrayList<UndoLog.TileChange<Int>>()
        // Refused before anything is swapped: past the ceiling the layer stops growing, it does not crash.
        val newFloat = stores.filter { it != MEDIA_LOOK }.sumOf { st -> keys.count { layer.floats[st]?.containsKey(it) != true } }
        if (newFloat > 0 && mediaResidentBytes() + newFloat.toLong() * MediaStores.tileBytes(size) > mediaBudgetBytes) {
            throw MediaRoomException(LayerBudget.mediaFullMessage(mediaBudgetBytes))
        }
        val fresh = ArrayList<UndoLog.TileChange<Int>>()
        val out = LinkedHashMap<String, Map<Long, Int>>()
        try {
            for (store in stores) {
                val look = store == MEDIA_LOOK
                require(look || store in MediaStores.ALL) { "unknown media store $store" }
                val storeId = if (look) layerId else MediaStores.id(layerId, store)
                val tiles = if (look) layer.tiles else layer.floats.getOrPut(store) { HashMap() }
                val texes = LinkedHashMap<Long, Int>()
                for (key in keys) {
                    val now = tiles[key]
                    val tile = Triple(storeId, null, key)
                    if (now != null && tile in held) { texes[key] = now; continue }
                    val after = if (look) newLayerTile() else newFloatTile()
                    initializeTile(after, now, false)
                    val dropped = topHeld[tile]
                    if (now == null && dropped != null) revived.add(UndoLog.TileChange(storeId, key, dropped.before, after))
                    else fresh.add(UndoLog.TileChange(storeId, key, now, after))
                    tiles[key] = after
                    texes[key] = after
                }
                out[store] = texes
            }
        } catch (e: Throwable) {
            // Put back what was swapped and free the copies: nothing half-done reaches the history.
            for (c in fresh.asReversed()) { put(c.layerId, c.key, c.before); c.after?.let(::recycleLayerTex) }
            for (c in revived) { put(c.layerId, c.key, null); c.after?.let(::recycleLayerTex) }
            throw e
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
        invalidateProjection(layer)
        if (stroke != null) {
            stroke.addAll(fresh)
            fresh.forEach { mediaStrokeHeld.add(Triple(it.layerId, it.celId, it.key)) }
        } else {
            if (revived.isNotEmpty()) check(undo.replaceInNewest(revived)) { "the step that dropped this water is no longer the newest" }
            MediaStores.recordWater(undo, fresh)
        }
        return out
    }

    /**
     * Forgets [keys] of a water store where the water has dried (the Lead's rule: water stores exist only where wet), as
     * part of the newest media step, or a fresh water step. A tile that step made is simply gone from it; one that was
     * there before comes back on undo. Never during a media stroke: water dries between strokes, and the window waits.
     */
    fun dropMediaTiles(layerId: String, store: String, keys: Collection<Long>) {
        require(store in MediaStores.WATER) { "only water dries away; $store stays" }
        check(mediaStroke == null) { "water is dropped between strokes, not during one" }
        val tiles = layers[layerId]?.floats?.get(store) ?: return
        val id = MediaStores.id(layerId, store)
        val topHeld = undo.newestExtendable()?.takeIf { MediaStores.isMediaStep(it) }?.changes?.associateBy { Triple(it.layerId, it.celId, it.key) }.orEmpty()
        val replaced = ArrayList<UndoLog.TileChange<Int>>()
        val fresh = ArrayList<UndoLog.TileChange<Int>>()
        for (key in keys) {
            val now = tiles.remove(key) ?: continue
            val held = topHeld[Triple(id, null, key)]
            if (held != null) { replaced.add(UndoLog.TileChange(id, key, held.before, null)); recycleLayerTex(now) }
            else fresh.add(UndoLog.TileChange(id, key, now, null))
        }
        if (replaced.isNotEmpty()) check(undo.replaceInNewest(replaced)) { "the newest media step changed under a drop" }
        MediaStores.recordWater(undo, fresh)
    }

    /** The texture of one media tile as it stands (read only), or null where the store has none. */
    fun mediaTile(layerId: String, store: String, key: Long): Int? =
        if (store == MEDIA_LOOK) layers[layerId]?.tiles?.get(key) else layers[layerId]?.floats?.get(store)?.get(key)

    /** Keys of every tile a media store has (sparse): for saving, and for the window to know what exists. */
    fun mediaTileKeys(layerId: String, store: String): List<Long> = layers[layerId]?.floats?.get(store)?.keys?.toList() ?: emptyList()

    /** True when [layerId] holds any float state: a media layer that has been painted. */
    fun hasMediaState(layerId: String): Boolean = layers[layerId]?.floats?.values?.any { it.isNotEmpty() } == true

    /**
     * One store tile for saving: [MediaStores.tileBytes] bytes of little-endian half floats ([HalfFloat]), RGBA, row 0 = the
     * tile's TOP document row (as [readTile]). Read back as full floats (the one readback ES 3 guarantees for a float
     * colour buffer) and rounded on the CPU. Read at a frame boundary (the Lead's rule: never mid-simulation). Null where
     * the store has no tile.
     */
    fun readMediaTile(layerId: String, store: String, key: Long): ByteArray? {
        require(store in MediaStores.ALL) { "unknown media store $store" }
        val tex = layers[layerId]?.floats?.get(store)?.get(key) ?: return null
        val buf = ByteBuffer.allocateDirect(size * size * 4 * 4).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        attach(tex)
        try {
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "media tile framebuffer is unavailable" }
            GLES30.glReadPixels(0, 0, size, size, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
            val error = GLES30.glGetError()
            check(error == GLES30.GL_NO_ERROR) { "media tile readback failed (GL $error)" }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
        val floats = FloatArray(size * size * 4)
        buf.rewind()
        buf.asFloatBuffer().get(floats)
        return HalfFloat.encode(floats)
    }

    /** Sets a float tile (same layout as [readMediaTile]). For loading a document: NOT undoable. Creates what is missing. */
    fun writeMediaTile(layerId: String, store: String, key: Long, bytes: ByteArray) {
        require(store in MediaStores.ALL) { "unknown media store $store" }
        require(bytes.size == MediaStores.tileBytes(size)) { "media tile must be ${MediaStores.tileBytes(size)} bytes, got ${bytes.size}" }
        val layer = storeOrCreate(layerId)
        val tex = layer.floats.getOrPut(store) { HashMap() }.getOrPut(key) { newFloatTile() }
        // Uploaded as full floats (ES 3 takes FLOAT data for an RGBA16F texture) so the rounding is the one [HalfFloat] did.
        val floats = HalfFloat.decode(bytes)
        val buf = ByteBuffer.allocateDirect(floats.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(floats)
        buf.rewind()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, size, size, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /** Media tiles a step put back, by media layer: what [onMediaRestored] is told. */
    private fun reportMediaRestored(changes: List<UndoLog.TileChange<Int>>) {
        val listener = onMediaRestored ?: return
        val byLayer = LinkedHashMap<String, HashSet<Long>>()
        for (c in changes) {
            val owner = MediaStores.parse(c.layerId)?.first ?: c.layerId.takeIf { layers[it]?.floats?.isNotEmpty() == true } ?: continue
            byLayer.getOrPut(owner) { HashSet() }.add(c.key)
        }
        for ((id, keys) in byLayer) listener(id, keys)
    }

    /** A cleared RGBA16F tile, NEAREST like the window's full floats (the media shaders filter by hand). */
    private fun newFloatTile(): Int {
        val tex = freeFloatTex.removeLastOrNull() ?: run {
            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, size, size, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            floatNames.add(t[0])
            t[0]
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, copyFbo); attach(tex)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return tex
    }

    // ── display ──────────────────────────────────────────────────────────────
    private fun clipTileStroke(key: Long) {
        val tile=strokeTileRect ?: return
        val clip=TilePainting.clip(tile,key) ?: return
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST); GLES30.glScissor(clip.x,clip.y,clip.w,clip.h)
    }

    /** Preview modes affect the screen only. Archives, thumbnails and exports always call draw. */
    fun drawDisplay(w: Int,h: Int,m: FloatArray,paper: Int) {
        val doc=boardDocument
        val tile=tileBoardId?.let { id -> doc?.boards?.firstOrNull { it.id == id } }
        if(tile != null) {
            cropPreview(tile.rect,paper,false)
            boardPreview.show(w,h,m,tile.rect,tileVao,true,floatArrayOf(1f,1f,1f,1f))
            return
        }
        draw(w,h,m,paper)
        if(strokeInProgress || framePreviews.isNotEmpty()) return
        val onion=onionBoardId?.let { id -> doc?.boards?.firstOrNull { it.id == id } } ?: return
        val index=onion.frames.indexOfFirst { it.id == onion.currentFrameId }
        if(index < 0) return
        val restore=framePreviews
        try {
            // Held layers remain available as clipping alpha, but never draw their own ghost.
            ghostBoardId=onion.id
            for((offset,tint) in listOf(-1 to floatArrayOf(0.95f,0.3f,0.4f,0.22f),1 to floatArrayOf(0.2f,0.65f,1f,0.22f))) {
                val frame=onion.frames.getOrNull(index+offset) ?: continue
                setFramePreviews(mapOf(onion.id to frame.id))
                cropPreview(onion.rect,paper,true)
                setFramePreviews(restore)
                boardPreview.show(w,h,m,onion.rect,tileVao,false,tint)
            }
        } finally { boardArtOnly=false; ghostBoardId=null; setFramePreviews(restore) }
    }
    private fun cropPreview(rect: RectPx,paper: Int,artOnly: Boolean) {
        require(rect.w.toLong()*rect.h <= MAX_REGION_PX) { "This board is too large to preview on this phone" }
        boardPreview.prepare(rect.w,rect.h)
        val matrix=floatArrayOf(2f/rect.w,0f,0f,0f,2f/rect.h,0f,-1f-2f*rect.x/rect.w,-1f-2f*rect.y/rect.h,1f)
        boardArtOnly=artOnly
        boardCropRendering=true
        try { draw(rect.w,rect.h,matrix,paper,boardPreview.target) } finally { boardArtOnly=false; boardCropRendering=false }
    }

    /**
     * Draws paper and every visible layer (with the live stroke previewed) into the CURRENT
     * framebuffer. [docToClip] is a column-major 3×3 matrix from document px to clip space.
     */
    fun draw(viewportW: Int, viewportH: Int, docToClip: FloatArray, paperArgb: Int, targetFbo: Int = 0) {
        if (needsComposite()) { drawComposited(viewportW, viewportH, docToClip, paperArgb, targetFbo); return }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
        GLES30.glViewport(0, 0, viewportW, viewportH)
        drawPaper(viewportW,viewportH,docToClip,paperArgb,targetFbo)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glBindVertexArray(tileVao)

        for (layer in layers.values) {
            if (!drawsBoardLayer(layer)) continue
            val previewing = layer === strokeLayer && strokeTiles.isNotEmpty()

            tileProg.use()
            GLES30.glUniform1f(tileProg.loc("u_tileSize"), size.toFloat())
            GLES30.glUniformMatrix3fv(tileProg.loc("u_docToClip"), 1, false, docToClip, 0)
            GLES30.glUniform1f(tileProg.loc("u_layerOpacity"), layer.opacity)
            GLES30.glUniform1i(tileProg.loc("u_layer"), 0)
            for ((key, tex) in visibleTiles(layer)) {
                if (previewing && strokeTiles.containsKey(key)) continue
                GLES30.glUniform2f(tileProg.loc("u_tileOrigin"), (Tiles.tx(key) * size).toFloat(), (Tiles.ty(key) * size).toFloat())
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            }

            if (previewing) {
                commitProg.use()
                setCommitUniforms(layerOpacity = layer.opacity)
                GLES30.glUniformMatrix3fv(commitProg.loc("u_docToClip"), 1, false, docToClip, 0)
                for ((key, strokeTex) in strokeTiles) {
                    val base = visibleTiles(layer)[key]
                    if (base == null && blend == StrokeBlend.ERASE) continue
                    GLES30.glUniform2f(commitProg.loc("u_tileOrigin"), (Tiles.tx(key) * size).toFloat(), (Tiles.ty(key) * size).toFloat())
                    bindTextures(base ?: clearTex, strokeTex)
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                }
            }
        }
        GLES30.glBindVertexArray(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }


    /** True when any visible layer composites with something other than plain source-over. */
    private fun drawsBoardLayer(layer: Layer): Boolean = layer.visible &&
        (!boardArtOnly || layer.plan?.frames?.any { it.boardId == ghostBoardId } == true)

    private fun needsComposite(): Boolean =
        compositeError == null && layers.values.any { drawsBoardLayer(it) && (it.blend != BlendMode.NORMAL || it.mask != null || it.clip) }

    /**
     * The whole stack, one layer at a time, into an offscreen target that a shader can read (JB-2.20b).
     *
     * For each visible layer: work out the screen rectangle its tiles can touch, copy that rectangle of
     * the stack-so-far into a backdrop texture, then draw the layer's tiles INTO the stack with
     * `jb_composite.frag`, which reads the backdrop and applies the layer's blend mode and opacity.
     * The stroke being drawn is previewed the same way [draw] previews it: its tiles are committed into
     * temporary textures first and those stand in for the layer's own. Finally the stack is copied to
     * the screen. The Blend check on the phone compares this path with [draw]'s for NORMAL layers as well as
     * checking all 27 modes against the export; up to a few 1/255 apart is rounding (RGBA8 per layer), not a bug.
     */
    private fun drawComposited(w: Int, h: Int, docToClip: FloatArray, paperArgb: Int, targetFbo: Int) {
        val compositor = if(boardCropRendering) boardCompositor else compositor
        val prog = compositeProg ?: try {
            GlProgram(shaders.source("jb_tile.vert"), shaders.source("jb_composite.frag"), "composite").also { compositeProg = it }
        } catch (e: RuntimeException) {
            // The 27-branch blend shader is the biggest one we compile. If a driver refuses it, the rest of the
            // engine must keep working: remember why, and draw this and every later frame the plain way.
            compositeError = e.message ?: e.javaClass.simpleName
            draw(w, h, docToClip, paperArgb, targetFbo)
            return
        }
        compositor.ensure(w, h)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, compositor.targetFbo)
        GLES30.glViewport(0, 0, w, h)
        drawPaper(w,h,docToClip,paperArgb,compositor.targetFbo)

        // The live stroke, rendered once as the tiles it will leave — into a layer or into a mask (JB-2.23). Anything
        // that reads that store this frame (the layer itself, a layer clipped to it, its own mask) sees the preview.
        val live = strokeLayer
        val previews = if (live != null && strokeTiles.isNotEmpty()) renderStrokePreviews(live) else emptyMap()
        fun texOf(store: Layer?, key: Long): Int? =
            if (store == null) null else if (store === live) previews[key] ?: visibleTiles(store)[key] else visibleTiles(store)[key]

        val list = layers.values.toList()
        for ((index, layer) in list.withIndex()) {
            if (!drawsBoardLayer(layer)) continue
            // Clipping by core's LayerMask rules, the same function RegionRenderer asks. A hidden base hides the clip.
            val base = LayerMask.clipBase(index) { list[it].clip }?.let { list[it] }
            if (base != null && !base.visible) continue
            val keys = LinkedHashSet<Long>(visibleTiles(layer).keys)
            if (layer === live) keys.addAll(previews.keys)
            val rect = if (keys.isEmpty()) null else screenRect(keys, docToClip, w, h)
            if (rect != null) {
                compositor.copyToBackdrop(rect[0], rect[1], rect[2], rect[3])
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, compositor.targetFbo)
                GLES30.glViewport(0, 0, w, h)
                prog.use()
                GLES30.glUniform1f(prog.loc("u_tileSize"), size.toFloat())
                GLES30.glUniformMatrix3fv(prog.loc("u_docToClip"), 1, false, docToClip, 0)
                GLES30.glUniform1f(prog.loc("u_layerOpacity"), layer.opacity)
                GLES30.glUniform1f(prog.loc("u_mode"), BlendCodes.codeOf(layer.blend))
                GLES30.glUniform1i(prog.loc("u_layer"), 0)
                GLES30.glUniform1i(prog.loc("u_backdrop"), 1)
                GLES30.glUniform1i(prog.loc("u_mask"), 2)
                GLES30.glUniform1i(prog.loc("u_clipBase"), 3)
                GLES30.glUniform1i(prog.loc("u_clipMask"), 4)
                GLES30.glUniform1f(prog.loc("u_clipped"), if (base != null) 1f else 0f)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, compositor.backdrop)
                GLES30.glBindVertexArray(tileVao)
                for (key in keys) {
                    val tex = texOf(layer, key) ?: continue
                    // Where the clip base has no tile, a clipped layer shows nothing (LayerMask.clipAlpha: 0).
                    val baseTex = if (base != null) (texOf(base, key) ?: continue) else whiteTex
                    val maskTex = texOf(layer.mask, key) ?: whiteTex
                    val baseMaskTex = if (base != null) texOf(base.mask, key) ?: whiteTex else whiteTex
                    GLES30.glUniform2f(prog.loc("u_tileOrigin"), (Tiles.tx(key) * size).toFloat(), (Tiles.ty(key) * size).toFloat())
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, baseTex)
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, baseMaskTex)
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                }
            }
        }
        previews.values.forEach(::recycleLayerTex)
        compositor.blitToScreen(targetFbo)
        GLES30.glBindVertexArray(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /**
     * The tiles of [layer] as they will look once the live stroke is committed, rendered into temporary
     * textures (recycled by the caller). Same commit maths as [endStroke], at layer opacity 1: the
     * composite pass applies the layer's opacity.
     */
    private fun renderStrokePreviews(layer: Layer): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, size, size)
        GLES30.glDisable(GLES30.GL_BLEND)
        commitProg.use()
        setCommitUniforms(layerOpacity = 1f)
        GLES30.glBindVertexArray(tileVao)
        for ((key, strokeTex) in strokeTiles) {
            val base = visibleTiles(layer)[key]
            if (base == null && blend == StrokeBlend.ERASE && !layer.isMask) continue
            val after = newLayerTile()
            attach(after)
            val ox = (Tiles.tx(key) * size).toFloat()
            val oy = (Tiles.ty(key) * size).toFloat()
            GLES30.glUniform2f(commitProg.loc("u_tileOrigin"), ox, oy)
            GLES30.glUniformMatrix3fv(commitProg.loc("u_docToClip"), 1, false, tileToClip(ox, oy), 0)
            bindTextures(base ?: emptyTexOf(layer), strokeTex)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            out[key] = after
        }
        GLES30.glBindVertexArray(0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return out
    }

    /**
     * The screen rectangle (x, y, w, h in pixels, clamped to the [w] x [h] target, one pixel of margin) that
     * the tiles [keys] can touch under [m] (the document-to-clip matrix); null if none of it is on screen.
     * Uses all four corners of every tile, so a rotated view is covered.
     */
    private fun screenRect(keys: Collection<Long>, m: FloatArray, w: Int, h: Int): IntArray? {
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (key in keys) {
            val ox = (Tiles.tx(key) * size).toFloat()
            val oy = (Tiles.ty(key) * size).toFloat()
            for (corner in 0 until 4) {
                val dx = ox + (corner and 1) * size
                val dy = oy + (corner shr 1) * size
                val cw = m[2] * dx + m[5] * dy + m[8]
                val inv = if (cw != 0f) 1f / cw else 1f
                val px = ((m[0] * dx + m[3] * dy + m[6]) * inv * 0.5f + 0.5f) * w
                val py = ((m[1] * dx + m[4] * dy + m[7]) * inv * 0.5f + 0.5f) * h
                x0 = minOf(x0, px); x1 = maxOf(x1, px)
                y0 = minOf(y0, py); y1 = maxOf(y1, py)
            }
        }
        val ix0 = (kotlin.math.floor(x0).toInt() - 1).coerceIn(0, w)
        val iy0 = (kotlin.math.floor(y0).toInt() - 1).coerceIn(0, h)
        val ix1 = (kotlin.math.ceil(x1).toInt() + 1).coerceIn(0, w)
        val iy1 = (kotlin.math.ceil(y1).toInt() + 1).coerceIn(0, h)
        if (ix1 <= ix0 || iy1 <= iy0) return null
        return intArrayOf(ix0, iy0, ix1 - ix0, iy1 - iy0)
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun setCommitUniforms(layerOpacity: Float) {
        GLES30.glUniform1f(commitProg.loc("u_tileSize"), size.toFloat())
        GLES30.glUniform1i(commitProg.loc("u_layer"), 0)
        GLES30.glUniform1i(commitProg.loc("u_stroke"), 1)
        GLES30.glUniform3f(commitProg.loc("u_color"), colR, colG, colB)
        GLES30.glUniform1f(commitProg.loc("u_strokeScale"), if (accumulate == Accumulate.BUILD_UP) opacity else 1f)
        GLES30.glUniform1i(commitProg.loc("u_erase"), if (blend == StrokeBlend.ERASE) 1 else 0)
        GLES30.glUniform1i(commitProg.loc("u_smudge"), if (smudge == null) 0 else if (smudgePaint) 2 else 1)
        GLES30.glUniform1f(commitProg.loc("u_layerOpacity"), layerOpacity)
    }

    private fun bindTextures(layerTex: Int, strokeTex: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, layerTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, strokeTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /** Maps a tile's own square onto the whole render target (column-major). */
    private fun tileToClip(ox: Float, oy: Float): FloatArray {
        val s = 2f / size
        return floatArrayOf(s, 0f, 0f, 0f, s, 0f, -ox * s - 1f, -oy * s - 1f, 1f)
    }

    private fun put(layerId: String, key: Long, tex: Int?, celId: String? = null) {
        MediaStores.parse(layerId)?.let { (owner, store) ->
            require(celId == null) { "a media store has no cels" }
            val tiles = storeOrCreate(owner).floats.getOrPut(store) { HashMap() }
            if (tex == null) tiles.remove(key) else tiles[key] = tex
            return
        }
        val layer = storeOrCreate(layerId)
        val store = planeTiles(layer,celId)
        if (tex == null) store.remove(key) else store[key] = tex
        invalidateProjection(layer)
    }

    private fun attach(tex: Int) {
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
    }

    private fun newLayerTile(): Int = freeLayerTex.removeLastOrNull()
        ?: newTexture(size, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE)

    private fun newStrokeTile(): Int {
        val tex = if (strokeIsRgba) {
            freeSmudgeTex.removeLastOrNull() ?: newTexture(size, smudgeInternal, GLES30.GL_RGBA, smudgeType)
        } else {
            freeStrokeTex.removeLastOrNull() ?: newTexture(size, strokeInternal, GLES30.GL_RED, strokeType)
        }
        attach(tex)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        return tex
    }

    private fun releaseStrokeTiles() {
        // Back into the pool it came from: a smudge's RGBA tiles and a stamp's single-channel tiles are not interchangeable.
        if (strokeIsRgba) { freeSmudgeTex.addAll(strokeTiles.values); trimPool(freeSmudgeTex, 16) }
        else { freeStrokeTex.addAll(strokeTiles.values); trimPool(freeStrokeTex, 32) }
        strokeTiles.clear()
    }

    private fun recycleLayerTex(tex: Int) {
        if (tex in floatNames) {
            freeFloatTex.addLast(tex)
            while (freeFloatTex.size > 16) freeFloatTex.removeFirst().let { floatNames.remove(it); GLES30.glDeleteTextures(1, intArrayOf(it), 0) }
            return
        }
        freeLayerTex.addLast(tex)
        trimPool(freeLayerTex, 64)
    }

    private fun trimPool(pool: ArrayDeque<Int>, keep: Int) {
        while (pool.size > keep) GLES30.glDeleteTextures(1, intArrayOf(pool.removeFirst()), 0)
    }

    private fun newTexture(dim: Int, internal: Int, format: Int, type: Int): Int = newTexture(dim, dim, internal, format, type)

    private fun newTexture(w: Int, h: Int, internal: Int, format: Int, type: Int): Int {
        val dim = if (w == h) w else 0
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        if (dim == 1) {
            // The "no tile yet" texture: one transparent texel.
            val zero = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 1, 1, format, type, zero)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return t[0]
    }

    // The destination's 3x3 pen-down neighbourhood. Pixel pickup is capped at half a tile,
    // so every displaced source lies here, including negative coordinates and tile crossings.
    private fun bindPickupTiles(key: Long) {
        val layer = strokeLayer ?: return
        for (dy in -1..1) for (dx in -1..1) {
            val slot = (dy + 1) * 3 + dx + 1
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + 2 + slot)
            val source = visibleTiles(layer)[Tiles.key(Tiles.tx(key) + dx, Tiles.ty(key) + dy)] ?: emptyTexOf(layer)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, source)
            GLES30.glUniform1i(smudgeProg.loc("u_pickup${dx + 1}${dy + 1}"), 2 + slot)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun enableContactAttributes(stride: Int, offset: Int) {
        for (i in 0..1) {
            GLES30.glEnableVertexAttribArray(5 + i)
            GLES30.glVertexAttribPointer(5 + i, 4, GLES30.GL_FLOAT, false, stride, offset + i * 16)
            GLES30.glVertexAttribDivisor(5 + i, 1)
        }
        GLES30.glEnableVertexAttribArray(7)
        GLES30.glVertexAttribPointer(7, 1, GLES30.GL_FLOAT, false, stride, offset + 32)
        GLES30.glVertexAttribDivisor(7, 1)
    }

    private fun putContact(buffer: FloatBuffer, d: Dab) {
        fun resolved(value: Float, fallback: Float) = if (value.isFinite()) value else fallback
        val live = d.aspect.isFinite() || d.hardness.isFinite() || d.tipDepth.isFinite() || d.paperDepth.isFinite() || d.anchor.isFinite()
        buffer.put(resolved(d.aspect, tip.aspect)).put(resolved(d.hardness, tip.hardness))
            .put(resolved(d.tipDepth, grain.tip.depth)).put(resolved(d.paperDepth, grain.paper.depth))
            .put(resolved(d.anchor, 0f)).put(GrainMath.tiltAmount(d.tilt))
            .put(GrainMath.leanX(d.azimuth)).put(GrainMath.leanY(d.azimuth)).put(if (live) 1f else 0f)
    }

    private fun fillInstances(list: List<Dab>) {
        val need = list.size * 17
        if (instanceData.capacity() < need) instanceData = newFloats(need * 2)
        instanceData.clear()
        for (d in list) {
            instanceData.put(d.x).put(d.y).put(d.radius).put(d.angle).put(d.flow).put(d.cap).put(d.travelX).put(d.travelY)
            putContact(instanceData, d)
        }
        instanceData.flip()
    }

    private fun upload(vbo: Int, data: FloatArray) {
        val b = newFloats(data.size).put(data)
        b.flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, b, GLES30.GL_STATIC_DRAW)
    }

    private fun newFloats(n: Int): FloatBuffer =
        ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
}
