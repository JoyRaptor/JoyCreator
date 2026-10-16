package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import android.os.SystemClock
import cc.joycreator.joybrush.core.brush.WatercolourSpec
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BrushValidate
import cc.joycreator.joybrush.core.brush.VERSION_CONTACT
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.watercolourRuntime
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkReplay
import cc.joycreator.joybrush.core.vector.RecordedCel
import cc.joycreator.joybrush.core.vector.RecordedEdits
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.androidkit.gl.lines.RecordedScene
import cc.joycreator.joybrush.androidkit.gl.lines.NativeBaseTransferProof
import cc.joycreator.joybrush.androidkit.gl.lines.PromotedBaseProjection
import cc.joycreator.joybrush.androidkit.gl.watercolour.WatercolourPass
import cc.joycreator.joybrush.androidkit.gl.media.GlUniformSink
import cc.joycreator.joybrush.androidkit.io.PaperResources
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.androidkit.io.ColdRecordedLoadPlan
import cc.joycreator.joybrush.androidkit.io.RecordedSnapshot
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.paper.PaperState
import cc.joycreator.joybrush.core.paper.ResolvedPaper
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

/** Internal engine adoption evidence; not a View, Working or recovery installation receipt. */
internal data class ColdRecordedLoadReceipt(
    val nativeTiles: Int, val maskTiles: Int, val mediaTiles: Int,
    val sourceSlabs: Int, val savedLooks: Int, val recordedPlanes: Int,
    val unresolvedLinePlanes: Int,
    val linePreparationCalls: Long = 0L, val compositionCalls: Long = 0L,
)

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
        checkRects(source, destination)
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

    /** Visit only rectangles containing existing or staged tiles, never every empty grid cell.
     * Overlapping source/destination candidates can repeat a slice: each reads the same original
     * source and replaces the same pixels, so repetitions do not blend or change the result. */
    fun sparseSlices(source: RectPx, destination: RectPx, sourceKeys: Sequence<Long>, destinationKeys: Sequence<Long>): Sequence<TranslatedTileSlice> = sequence {
        checkRects(source, destination)
        fun translated(rect: RectPx, from: RectPx, to: RectPx) = RectPx(
            (rect.x.toLong() - from.x + to.x).toInt(), (rect.y.toLong() - from.y + to.y).toInt(), rect.w, rect.h)
        for (key in sourceKeys) {
            val rect = intersectTile(source, key) ?: continue
            yieldAll(slices(rect, translated(rect, source, destination)))
        }
        for (key in destinationKeys) {
            val rect = intersectTile(destination, key) ?: continue
            yieldAll(slices(translated(rect, destination, source), rect))
        }
    }

    private fun intersectTile(rect: RectPx, key: Long): RectPx? {
        val ox = Tiles.tx(key).toLong() * Tiles.SIZE; val oy = Tiles.ty(key).toLong() * Tiles.SIZE
        val x = maxOf(rect.x.toLong(), ox); val y = maxOf(rect.y.toLong(), oy)
        val right = minOf(rect.x.toLong() + rect.w, ox + Tiles.SIZE)
        val bottom = minOf(rect.y.toLong() + rect.h, oy + Tiles.SIZE)
        return if (right <= x || bottom <= y) null else RectPx(x.toInt(), y.toInt(), (right - x).toInt(), (bottom - y).toInt())
    }

    private fun checkRects(source: RectPx, destination: RectPx) {
        require(source.w > 0 && source.h > 0 && source.w == destination.w && source.h == destination.h) {
            "Transferred rectangles must have the same positive size"
        }
        for (r in listOf(source, destination)) {
            require(r.x.toLong() + r.w <= Int.MAX_VALUE && r.y.toLong() + r.h <= Int.MAX_VALUE) {
                "Transferred pixels are outside addressable canvas coordinates"
            }
        }
    }
}

/** A layer id with this on the end names its MASK's tile store (JB-2.23): strokes, tiles, undo and thumbnails all take it. */
const val MASK_SUFFIX = "#mask"

fun maskStoreId(layerId: String): String = layerId + MASK_SUFFIX

/** Refusal of a media write past [GlPaintEngine.mediaBudgetBytes]: [message] is the sentence to show. */
class MediaRoomException(message: String) : RuntimeException(message)

/** What [GlPaintEngine.writableMediaTiles] calls a layer's RGBA8 look, which keeps the layer's own id. */
const val MEDIA_LOOK = "look"

/** A payload's agreed look where the tile has no look at all (GL never names a texture 0). */
private const val NO_LOOK = 0

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
    private val boardSnapshotAllowanceBytes = undoBudgetBytes.coerceAtLeast(0L)
    // GL-thread only. Reuse staging storage while a large snapshot fills its output arrays.
    private val tileReadback by lazy { ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder()) }

    private lateinit var dabProg: GlProgram
    private lateinit var commitProg: GlProgram
    private var recordedComposeProg: GlProgram? = null
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
    val paperWarnings: List<String> get() = loadedPaper?.warnings ?: emptyList()

    private var localDocumentPaper: Paper = Paper()
    @set:JvmName("assignDocumentPaper")
    var documentPaper: Paper
        get() = publishedPaint?.paper ?: localDocumentPaper
        private set(value) { localDocumentPaper = value }

    fun setDocumentPaper(value: Paper) {
        requireLocalPaint()
        documentPaper = value
        setPaper(PaperState.resolve(value, PaperResources.catalogue))
    }

    /** Same undo stream as paint; no tiles or texture ownership involved. */
    fun recordPaperChange(before: Paper, after: Paper) {
        requireLocalPaint()
        checkStrokeEnded("Finish the stroke before closing a paper edit")
        if (before != after) undo.push(UndoLog.Step(emptyList(), paperBefore=before, paperAfter=after))
    }

    /** GL thread: switching paper invalidates only the background, never painted tiles. */
    fun setPaper(p: ResolvedPaper) {
        requireLocalPaint()
        if (resolvedPaper == p && loadedPaper != null) return
        loadedPaper = PaperResources.update(p, resolvedPaper, loadedPaper)
        resolvedPaper = p
        val effective = loadedPaper!!.paper
        lookTexture = effective.look?.file?.let { grains.textureFor(it, "paper") }
        surfaceTexture = effective.surface?.file?.let { grains.textureFor(it, "paper") }
        fluidTexture = effective.surface?.fluid?.let { grains.textureFor(it, "paper") }
        paperBackground.invalidate()
    }

    private var mediaPaperFor: PaperResources.Loaded? = null
    private var mediaPaperGl: cc.joycreator.joybrush.androidkit.gl.media.MediaPaperGl? = null

    /**
     * The document paper as the media passes read it (M5.4): the same surface and fluid maps the brushes bind, plus the
     * media statistics (height histogram, tooth, compliance, fluid numbers) from the catalogue. Remade when the paper
     * changes. A smooth paper (no surface) is a flat sheet with a hair of tooth. GL thread.
     */
    fun mediaPaper(): cc.joycreator.joybrush.androidkit.gl.media.MediaPaperGl {
        val loaded = loadedPaper
        mediaPaperGl?.let { if (mediaPaperFor === loaded) return it }
        val p = loaded?.paper
        val surface = p?.surface
        val scale = p?.scale ?: 1f
        val heights = loaded?.surface?.rgba?.let { rgba -> ByteArray(rgba.size / 4) { rgba[it * 4 + 2] } }
        val hist = heights?.let { cc.joycreator.joybrush.core.media.MediaPaper.histogramOf(it) } ?: DoubleArray(256).also { it[255] = 1.0 }
        val stats = cc.joycreator.joybrush.core.media.MediaPaper(hist,
            toothMm = (surface?.toothDepthMm ?: 0.01f).toDouble(), compliance = (surface?.compliance ?: 0.3f).toDouble(),
            sizing = (surface?.sizing ?: 0.5f).toDouble(), absorbency = (surface?.absorbency ?: 0.5f).toDouble(),
            capacity = (surface?.capacity ?: 0.5f).toDouble(), wickSpeed = (surface?.wickSpeed ?: 0.4f).toDouble(),
            heightMeanOverride = surface?.heightMean?.toDouble())
        val fluid = fluidTexture
        val made = cc.joycreator.joybrush.androidkit.gl.media.MediaPaperGl(stats,
            surface = cc.joycreator.joybrush.androidkit.gl.media.Tex(surfaceTexture ?: grains.placeholder),
            texelPx = (surface?.texelPx ?: GrainMath.SURFACE_TEXEL_PX) * scale,
            size = surface?.size?.toFloat() ?: GrainMath.SURFACE_SIZE,
            hexTexels = surface?.hexTexels ?: GrainMath.SURFACE_HEX_TEXELS,
            slopeRange = surface?.slopeRange ?: GrainMath.SURFACE_SLOPE_RANGE,
            rotatable = surface?.rotatable ?: GrainMath.SURFACE_ROTATABLE,
            fluid = cc.joycreator.joybrush.androidkit.gl.media.Tex(fluid ?: grains.placeholder),
            fluidTexelPx = if (fluid != null && surface?.fluid != null) surface.fluidTexelPx * scale else 0f,
            fluidSize = surface?.fluid?.let { grains.sizeFor(it, "paper").toFloat() } ?: 1f,
            fluidHexTexels = surface?.fluidHexTexels ?: 150f)
        mediaPaperFor = loaded
        mediaPaperGl = made
        return made
    }

    /** The paper's colour (opaque ARGB): what a media layer's look is lit against and then removed from. */
    val paperArgb: Int get() = loadedPaper?.paper?.baseArgb ?: 0xFFFFFFFF.toInt()

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

    private class Layer(val id: String, val isMask: Boolean = false,
                        val tiles: HashMap<Long, Int> = HashMap(),
                        val ownershipIdentity: Any = Any()) {
        val cels = LinkedHashMap<String, HashMap<Long, Int>>()
        var sharedCel: String? = null
        var plan: RegionPaintPlan? = null
        val projected = HashMap<Long, Int>()
        var visibleProjection: Map<Long,Int>? = null
        /**
         * The media payload by store name ([MediaStores.PAYLOAD]): float state and ground, only on tiles a media stroke
         * touched (R51); [tiles] is the look. Address it through [payload], the one place a cel (and later a slab) goes.
         */
        val floats = HashMap<String, HashMap<Long, Int>>()
        /**
         * JB-2.40 slice 3: the payload of each FRAME cel (an animation board's own plane), cel id → store → tile. [floats]
         * stays the shared plane's (the cel [tiles] is), so a layer without frames behaves exactly as before.
         */
        val celFloats = HashMap<String, HashMap<String, HashMap<Long, Int>>>()
        /** Every plane's payload: null = the shared plane ([floats]), else a frame cel id. */
        fun planes(): List<Pair<String?, HashMap<String, HashMap<Long, Int>>>> = listOf<Pair<String?, HashMap<String, HashMap<Long, Int>>>>(null to floats) + celFloats.map { it.key to it.value }
        fun ownedTextures(): Set<Int> = (tiles.values + cels.values.flatMap { it.values } + projected.values +
            planes().flatMap { (_, p) -> p.values.flatMap { it.values } }).toSet()
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

    private var localLayers = LinkedHashMap<String, Layer>()   // bottom → top
    private val layers: LinkedHashMap<String, Layer> get() = publishedPaint?.nativeLayers ?: localLayers
    private var localGraphicsEpoch = 0L
    private val graphicsEpoch: Long get() = publishedPaint?.graphicsEpoch ?: localGraphicsEpoch
    private var physicalContextEpoch = 0L
    /** Returned renderer outputs stay visible to accounting before Scene publication/adoption. */
    private val recordedWorkingTiles = RecordedWorkingTileLedger(physicalContextEpoch)
    private var recordedWorkingTileBudgetBytes: Long? = null
    /** Caller-owned endpoints held privately by the one currently staging joint publication. */
    private var recordedPendingTileBytes: Map<Int, Long> = emptyMap()
    private var forgettingGraphicsContext = false
    private var resettingJointPaint = false
    private var preparingPlainPromotion = false

    /** Persistent maps change only by replacement while bound; projection caches remain disposable. */
    private class PublishedPaintState(
        val nativeLayers: LinkedHashMap<String, Layer>,
        val agreements: HashMap<Triple<String, String?, Long>, Int>,
        val document: JbDocument?,
        val paper: Paper,
        override val recorded: RecordedScene.State<Int>,
        override val cursor: UndoLog.Cursor<Int>,
        override val graphicsEpoch: Long,
        override val protectedTextures: Set<Int>,
        val coordinator: RecordedScene<Int>,
        override val promotedBases: Set<Pair<String, String?>> = emptySet(),
        override val legacyBaseSteps: Set<UndoLog.Step<Int>> = emptySet(),
        val baseTransferProof: NativeBaseTransferProof? = null,
    ) : RecordedScene.PromotedPublication<Int> {
        override val baseTransferIdentity: Any? get() = baseTransferProof
        override val promotedSharedCels: Map<String, String> = promotedBases.mapNotNull { (id, cel) ->
            if (cel == null) nativeLayers.getValue(id).sharedCel?.let { id to it } else null
        }.toMap()
    }

    private var publishedPaint: PublishedPaintState? = null
    internal val hasJointPaintPublication: Boolean get() = publishedPaint != null

    private fun requireLocalPaint() {
        check(publishedPaint == null && !resettingJointPaint && !preparingPlainPromotion) {
            "This writer needs the joint recorded artwork publisher"
        }
    }
    private var localRecordedScene: RecordedScene<Int>? = null
    private var recordedScene: RecordedScene<Int>?
        get() = publishedPaint?.coordinator ?: localRecordedScene
        private set(value) {
            localRecordedScene = value
            if (value == null) recordedWorkingTileBudgetBytes = null
        }
    /** GL-thread diagnostic attempt counts; used to prove cold acceptance does no source rendering. */
    internal var recordedLinePreparationCalls = 0L
        private set
    internal var recordedCompositionCalls = 0L
        private set
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
    private var localBoardDocument: JbDocument? = null
    @set:JvmName("assignBoardDocument")
    var boardDocument: JbDocument?
        get() {
            val root = publishedPaint
            return if (root == null) localBoardDocument else root.document
        }
        private set(value) { localBoardDocument = value }
    private var framePreviews: Map<String, String> = emptyMap()
    private val boardPreview = GlBoardPreview()
    private var boardArtOnly = false
    private var boardCropRendering = false
    private var ghostBoardId: String? = null
    var tileBoardId: String? = null
        private set
    private var strokeTileRect: RectPx? = null
    private var onionBoardId: String? = null

    /**
     * The tile rectangle the owner is dragging, shown instead of the board's saved one while the drag lasts
     * (2026-10-11: resize an armed tile live to find the repeat in finished art). Display only: the document,
     * strokes and undo never see it. Set and cleared on the GL thread; null whenever no drag is in flight.
     */
    private var tilePreviewRect: RectPx? = null

    fun setTileBoard(id: String?) {
        requireLocalPaint()
        checkStrokeEnded("Finish the stroke before switching tiling")
        tilePreviewRect=null
        if(id != null) {
            val board=TilePainting.board(requireNotNull(boardDocument),id)
            boardPreview.prepare(board.rect.w,board.rect.h)
        }
        tileBoardId=id
    }
    /**
     * Shows [rect] as the repeating tile of [id] until cleared with null. A rectangle the phone could not
     * preview or paint into is ignored, so a stray proposal can never make [drawDisplay] throw and switch
     * tiling off; the previous preview simply stays. Ignored for a board that is not the armed tile.
     */
    fun setTilePreviewRect(id: String,rect: RectPx?) {
        requireLocalPaint()
        if(rect == null || id != tileBoardId || strokeInProgress) { tilePreviewRect=null; return }
        val max=boardPreview.maxSide()
        if(TileLimits.fits(rect) && rect.w <= max && rect.h <= max) tilePreviewRect=rect
    }
    fun setOnionBoard(id: String?) {
        requireLocalPaint()
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
    /** The stamp draw shared with line rendering (VECTOR_RENDER_DESIGN §8, H1). */
    private val stamp = StampPass(object : StampPass.GrainSource {
        override val placeholder: Int get() = grains.placeholder
        override fun textureFor(asset: String): Int? = grains.textureFor(asset)
        override fun paperSize(asset: String): Int = grains.sizeFor(asset, "paper")
    })
    /** The active stroke's look, frozen at pen-down: tip, resolved grain, paper. The defaults are the old fields' own. */
    private var look = StampPass.StrokeLook(TipShape(), GrainMath.StrokeGrain(GrainMath.GrainUniforms.OFF, GrainMath.GrainUniforms.OFF),
        0, 0, null, 1f, PaperResponse(), 0f, null, null)

    private val strokeTravel = DabTravel()
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
    private var wcPass: WatercolourPass? = null
    private var wcSpec: WatercolourSpec? = null
    private var wcPrepared = false
    private var wcFading = false
    private var wcFadeStart = 0L
    val watercolourAnimating: Boolean get() = wcFading

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
        // A failed re-init must not leave a previous context's readiness receipt behind.
        ready = false
        physicalContextEpoch = Math.addExact(physicalContextEpoch, 1L)
        localGraphicsEpoch = Math.addExact(localGraphicsEpoch, 1L)
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
        // initWith advanced the physical epoch before any replacement-context allocation.
        recordedWorkingTiles.reset(physicalContextEpoch)
        recordedWorkingTileBudgetBytes = null
        recordedPendingTileBytes = emptyMap()
        // [heldTextureNames] rather than the maps one by one, so "there was something to lose" and
        // "a name is still being held" can never mean different things. `ready` is deliberately NOT
        // in the question: init is also how a second init on a live context is recovered from, and
        // what the caller needs to be told is that the DRAWING did not survive — not that init ran
        // twice. An engine with nothing in it has nothing to announce.
        val had = heldTextureNames() > 0 || undo.canUndo || undo.canRedo || recordedScene?.hasContent == true
        val alreadyForgetting = forgettingGraphicsContext
        forgettingGraphicsContext = true
        try {
        resetJointPaint(RecordedScene.ResetMode.CONTEXT_LOST)
        recordedScene?.forget(); recordedScene = null
        wcPass?.forget(); wcPass = null; wcSpec = null; wcFading = false; wcPrepared = false
        // The stroke goes with its buffer, and the layers with their tiles: the stroke was never in
        // a layer, so nothing is released twice and nothing is left pointing at a dead name.
        // Dead stroke names are abandoned, not returned to a pool that can trim/delete them.
        strokeTiles.clear()
        cancelStroke()
        layers.clear()
        boardDocument = null
        framePreviews = emptyMap()
        tileBoardId=null; tilePreviewRect=null; onionBoardId=null; strokeTileRect=null
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
        mediaPaperFor = null; mediaPaperGl = null
        compositeProg = null      // a name from the dead context; the new one compiles on first use
        recordedComposeProg = null
        thumbTex = 0; thumbW = 0; thumbH = 0
        compositeError = null
        return had
        } finally { forgettingGraphicsContext = alreadyForgetting }
    }

    /**
     * How many texture names this engine is holding right now — every layer tile, every stroke-buffer
     * tile and both free pools. It exists so a test can prove a context loss leaves NONE of them
     * behind; it is not a count of GL objects, because the programs, buffers, framebuffer and
     * [clearTex] are the driver's business and [initWith] makes new ones rather than reusing old.
     */
    internal fun heldTextureNames(): Int =
        layers.values.sumOf { it.ownedTextures().size + (it.mask?.ownedTextures()?.size ?: 0) } + strokeTiles.size + freeLayerTex.size + freeStrokeTex.size + freeSmudgeTex.size + freeFloatTex.size +
            compositor.heldNames() + boardCompositor.heldNames() + (recordedScene?.heldCacheTextures()?.size ?: 0)

    /** Frees every GL object this engine owns. */
    fun release() {
        check(!resettingJointPaint) { "Finish joint retirement before releasing the drawing" }
        if (!ready) { recordedWorkingTiles.reset(physicalContextEpoch); return }
        resetJointPaint(RecordedScene.ResetMode.SAME_CONTEXT)
        releaseRecordedScene()
        undo.clear()
        cancelStroke()
        val all = ArrayList<Int>()
        layers.values.forEach { all.addAll(it.ownedTextures()); it.mask?.let { m -> all.addAll(m.ownedTextures()) } }
        all.addAll(freeLayerTex); all.addAll(freeStrokeTex); all.addAll(freeSmudgeTex); all.addAll(freeFloatTex); all.add(clearTex); all.add(whiteTex)
        if (thumbTex != 0) all.add(thumbTex)
        thumbTex = 0; thumbW = 0; thumbH = 0
        GLES30.glDeleteTextures(all.size, all.toIntArray(), 0)
        all.forEach { if (it != 0) recordedWorkingTiles.forget(physicalContextEpoch, it) }
        layers.clear(); freeLayerTex.clear(); freeStrokeTex.clear(); freeSmudgeTex.clear(); freeFloatTex.clear(); floatNames.clear()
        mediaStroke = null; mediaStrokeHeld.clear()
        GLES30.glDeleteBuffers(3, intArrayOf(quadVbo, unitVbo, instanceVbo), 0)
        GLES30.glDeleteVertexArrays(4, intArrayOf(dabVao, tileVao, smudgeVao, tuftVao), 0)
        GLES30.glDeleteFramebuffers(3, intArrayOf(fbo, readFbo, copyFbo), 0)
        wcPass?.release(); wcPass = null
        grains.release()
        compositor.release()
        boardCompositor.release()
        paperBackground.release()
        dabProg.release(); commitProg.release(); tileProg.release(); smudgeProg.release(); tuftProg.release()
        boardPreview.release()
        compositeProg?.release(); compositeProg = null
        recordedComposeProg?.release(); recordedComposeProg = null
        recordedWorkingTiles.reset(physicalContextEpoch)
        ready = false
    }

    // ── layers ───────────────────────────────────────────────────────────────

    /** Adds an empty layer on top (no-op if it exists). */
    fun addLayer(id: String) {
        requireLocalPaint()
        layers.getOrPut(id) { Layer(id) } }

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

    fun setLayerOpacity(id: String, opacity: Float) {
        requireLocalPaint()
        layers[id]?.opacity = opacity.coerceIn(0f, 1f) }
    fun setLayerName(id: String, name: String) {
        requireLocalPaint()
        layers[id]?.name = name }
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
        requireLocalPaint()
        checkStrokeEnded("a layer change during a stroke would be undone out of order")
        val oldDoc = boardDocument
        val next = oldDoc?.let { documentForStack(it, after) }
        applyStack(after)
        next?.let(::setBoardDocument)
        undo.push(UndoLog.Step(emptyList(), before, after, documentBefore = oldDoc, documentAfter = next))
    }

    /** The stack made [target] with NO undo step: loading a drawing. */
    fun setStack(target: LayerStack) {
        requireLocalPaint()
        applyStack(target)
        activeHint = target.activeId
    }

    /** Deletes [id] (as [after] says) as ONE undo step: its tiles go into the step, so undo brings the pixels back. */
    fun deleteLayerStep(id: String, before: LayerStack, after: LayerStack) {
        requireLocalPaint()
        checkStrokeEnded("a layer change during a stroke would be undone out of order")
        val layer = layers[id] ?: return
        val oldDoc=boardDocument
        val changes=ArrayList<UndoLog.TileChange<Int>>()
        if(layer.sharedCel==null)layer.tiles.forEach{(k,t)->changes.add(UndoLog.TileChange(id,k,t,null))}
        else layer.cels.forEach{(cel,store)->store.forEach{(k,t)->changes.add(UndoLog.TileChange(id,k,t,null,cel))}}
        layer.tiles.clear();layer.cels.values.forEach{it.clear()};invalidateProjection(layer)
        // A media layer's float state goes with its look, every plane, into the same step.
        for ((plane, p) in layer.planes()) p.forEach { (store, tiles) -> tiles.forEach { (k, t) -> changes.add(UndoLog.TileChange(MediaStores.id(id, store), k, t, null, plane)) } }
        layer.floats.clear(); layer.celFloats.clear()
        forgetAgreements(id)
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
        requireLocalPaint()
        checkStrokeEnded("a layer change during a stroke would be undone out of order")
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
        requireLocalPaint()
        checkStrokeEnded("a layer change during a stroke would be undone out of order")
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
            // Each plane's payload follows its cel to the copy's cel (the shared plane stays the shared plane).
            val celMap = clone?.copies?.associate { it.fromCelId to it.toCelId }.orEmpty()
            for ((plane, p) in src.planes()) {
                val toPlane = if (plane == null) null else celMap[plane] ?: continue
                for ((store, tiles) in p) for ((key, tex) in tiles) {
                    val copy = newPayloadTile(store)
                    changes.add(UndoLog.TileChange(MediaStores.id(newId, store), key, null, copy, toPlane))
                    initializeTile(copy, tex, false)
                }
            }
            // Publish only after every GPU copy succeeds; failures leave the original stack intact.
            applyStack(after)
            published = true
            changes.forEach { put(it.layerId, it.key, it.after, it.celId) }
            // The copy's payload agrees with the copy's look, as the source's did.
            for (c in changes) if (MediaStores.isMediaStore(c.layerId)) agree(newId, c.key, c.celId)
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
        checkStrokeEnded("Frame thumbnails cannot replace a live stroke's display plan")
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
        checkStrokeEnded("Cell thumbnails cannot replace a live stroke's display plan")
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
    fun setLayerVisible(id: String, visible: Boolean) {
        requireLocalPaint()
        layers[id]?.visible = visible }

    /**
     * How a layer composites over the ones beneath it (JB-2.20b). All 27 [BlendMode]s are supported;
     * a stack in which every visible layer is NORMAL stays on the fixed-function path, and one
     * non-NORMAL layer sends the whole stack down [drawComposited].
     */
    fun setLayerBlend(id: String, mode: BlendMode) {
        requireLocalPaint()
        layers[id]?.blend = mode }
    fun layerBlend(id: String): BlendMode = layers[id]?.blend ?: BlendMode.NORMAL
    fun tileCount(id: String): Int = layers[id]?.let { physicalDisplayTiles(it, null).size } ?: 0

    /** Empties a layer as one undoable step. */
    fun clearLayer(id: String) {
        requireLocalPaint()
        val layer = storeOf(id) ?: return
        if (layer.planes().all { (_, p) -> p.values.all { it.isEmpty() } }) { replaceTiles(id,visibleTiles(layer).keys.associateWith{null}); return }
        // A layer with media: its payload is emptied in the same step as its look, or the next look render brings it back.
        val changes = ArrayList<UndoLog.TileChange<Int>>()
        for ((plane, p) in layer.planes()) p.forEach { (store, tiles) -> tiles.forEach { (k, t) -> changes.add(UndoLog.TileChange(MediaStores.id(id, store), k, t, null, plane)) } }
        layer.floats.clear(); layer.celFloats.clear()
        forgetAgreements(id)
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
    fun tileKeys(layerId: String): List<Long> {
        settleWatercolourFade()
        val layer = storeOf(layerId) ?: return emptyList()
        return if (publishedPaint?.recorded?.planes?.keys?.any { it.first == layer.id } == true)
            visibleTiles(layer).keys.toList() else layer.tiles.keys.toList()
    }

    /**
     * A tile's pixels: 256×256 premultiplied RGBA8, 262,144 bytes, row 0 = the tile's TOP document
     * row (no flip needed). Null if the tile does not exist.
     */
    fun readTile(layerId: String, key: Long): ByteArray? {
        settleWatercolourFade()
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
        requireLocalPaint()
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
        requireLocalPaint()
        checkStrokeEnded("replaceTiles during a stroke would be undone out of order")
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
        if(changes.isNotEmpty())undo.push(UndoLog.Step(changes + settlePlainWrites(changes)))
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
        check(!resettingJointPaint) { "Finish joint retirement before resetting the drawing" }
        resetJointPaint(RecordedScene.ResetMode.SAME_CONTEXT)
        cancelStroke()
        releaseRecordedScene()
        mediaStroke = null; mediaStrokeHeld.clear(); agreedLook.clear()
        undo.clear()
        layers.values.forEach { l -> l.ownedTextures().forEach(::recycleLayerTex); l.mask?.ownedTextures()?.forEach(::recycleLayerTex) }
        layers.clear()
        boardDocument = null
        tilePreviewRect = null
        framePreviews = emptyMap()
    }

    fun layerOpacity(id: String): Float = layers[id]?.opacity ?: 1f
    fun layerVisible(id: String): Boolean = layers[id]?.visible ?: true

    /** Metadata is renderer-independent. This backend implements PAINT planes; vectors use the same board model. */
    fun setBoardDocument(doc: JbDocument) {
        requireLocalPaint()
        checkStrokeEnded("A board cannot change during a stroke")
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
        if(doc.boards.none { it.id == tileBoardId && it.kind == BoardKind.CANVAS }) { tileBoardId=null; tilePreviewRect=null }
        if(doc.boards.none { it.id == onionBoardId && it.kind == BoardKind.ANIMATION }) onionBoardId=null
    }

    /** Switch display planes only. Paint always begins on the document's saved frame. */
    fun setFramePreviews(frames: Map<String, String>) {
        requireLocalPaint()
        checkStrokeEnded("Playback cannot change during a stroke")
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
        requireLocalPaint()
        checkStrokeEnded("Check failed.")
        val before=boardDocument ?: error("No board document is attached")
        val errors=DocOps.validate(change.doc); require(errors.isEmpty()){errors.joinToString("; ")}
        require(change.doc.layers.map{it.id}==before.layers.map{it.id}) { "Board edits cannot replace the layer stack" }
        require(change.doc.layers.all { it.kind.hasPixels && it.animatedIn == null }) {
            "This renderer paints raster layers"
        }
        // GPU-to-GPU copies hold original + staged textures, not an image-sized CPU buffer.
        val memory = BoardTransactionMemory<Pair<String, Pair<String?, Long>>>(
            boardSnapshotAllowanceBytes, size.toLong() * size * 4, change.copies.isNotEmpty(),
            BoardTransactionMemory.operation(before, change))
        val planned = HashSet<Pair<String, Pair<String?, Long>>>()
        fun stagedKeys(id: String, cel: String?) = planned.asSequence().filter {
            it.first == id && it.second.first == cel
        }.map { it.second.second }.toList() // bounded by the texture allowance; snapshot before adding keys.
        fun preflight(layer: Layer, source: Map<Long, Int>, destination: Map<Long, Int>, sourceCel: String?, cel: String?,
                      sourceRect: RectPx, destinationRect: RectPx, stagedSource: Boolean = false) {
            val sourcePending = if (stagedSource) stagedKeys(layer.id, sourceCel) else emptyList()
            val destinationPending = stagedKeys(layer.id, cel)
            for (slice in RegionTransferTiles.sparseSlices(sourceRect, destinationRect,
                source.keys.asSequence() + sourcePending.asSequence(), destination.keys.asSequence() + destinationPending.asSequence())) {
                val address = layer.id to (cel to slice.destinationKey)
                if (source[slice.sourceKey] != null || destination[slice.destinationKey] != null || address in planned ||
                    (stagedSource && (layer.id to (sourceCel to slice.sourceKey)) in planned)) {
                    memory.observe(address, destination[slice.destinationKey], true)
                    planned.add(address)
                }
            }
        }
        fun destinationAllowed(id: String, cel: String) {
            require(change.doc.layers.first { it.id == id }.cels.any { it.id == cel }) { "No destination cel $cel" }
        }
        // Same order as execution; later copies can read a clear/copy already staged by this edit.
        for (clear in change.clears) {
            val layer = layers[clear.layerId] ?: error("No layer ${clear.layerId}")
            val store = layer.cels[clear.celId] ?: error("No cel ${clear.celId}")
            preflight(layer, emptyMap(), store, null, clear.celId, clear.rect, clear.rect)
        }
        for (clear in change.maskClears) {
            val mask = layers[clear.layerId]?.mask ?: error("No mask on layer ${clear.layerId}")
            preflight(mask, emptyMap(), mask.tiles, null, null, clear.rect, clear.rect)
        }
        for (copy in change.copies) {
            val layer = layers[copy.layerId] ?: error("No layer ${copy.layerId}")
            val source = layer.cels[copy.fromCelId] ?: error("No source cel ${copy.fromCelId}")
            destinationAllowed(copy.layerId, copy.toCelId)
            preflight(layer, source, layer.cels[copy.toCelId].orEmpty(), copy.fromCelId, copy.toCelId,
                copy.rect, copy.rect, stagedSource = true)
        }
        for (transfer in change.transfers) {
            val layer = layers[transfer.layerId] ?: error("No layer ${transfer.layerId}")
            val source = layer.cels[transfer.fromCelId] ?: error("No source cel ${transfer.fromCelId}")
            destinationAllowed(transfer.layerId, transfer.toCelId)
            preflight(layer, source, layer.cels[transfer.toCelId].orEmpty(), transfer.fromCelId, transfer.toCelId,
                transfer.sourceRect, transfer.destinationRect)
        }
        for (transfer in change.maskTransfers) {
            val mask = layers[transfer.layerId]?.mask ?: error("No mask on layer ${transfer.layerId}")
            preflight(mask, mask.tiles, mask.tiles, null, null, transfer.sourceRect, transfer.destinationRect)
        }
        for (drop in change.drops) {
            val layer = layers[drop.layerId] ?: error("No layer ${drop.layerId}")
            val store = layer.cels[drop.celId] ?: error("No cel ${drop.celId}")
            for (key in store.keys.asSequence() + stagedKeys(layer.id, drop.celId).asSequence())
                memory.observe(layer.id to (drop.celId to key), store[key], false)
        }
        val pending=LinkedHashMap<Pair<String,Pair<String?,Long>>,UndoLog.TileChange<Int>>()
        var published=false
        fun checkRoom(address: Pair<String, Pair<String?, Long>>, original: Int?) {
            memory.observe(address, original, true)
        }
        fun pendingKeys(id: String, cel: String?) = pending.keys.asSequence().filter {
            it.first == id && it.second.first == cel
        }.map { it.second.second }.toList()
        fun stage(layer: Layer,cel: String,key: Long,source: Int?,rect: RegionTileRect) {
            val address=layer.id to (cel to key)
            val old=pending[address]
            val original=old?.before ?: layer.cels[cel]?.get(key)
            checkRoom(address, original)
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
                checkRoom(address, original)
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
                for (slice in RegionTransferTiles.sparseSlices(clear.rect, clear.rect, emptySequence(),
                    layer.cels.getValue(clear.celId).keys.asSequence() + pendingKeys(layer.id, clear.celId).asSequence()))
                    stageTranslated(layer, clear.celId, slice, null)
            }
            for (clear in change.maskClears) {
                val mask = requireNotNull(layers.getValue(clear.layerId).mask)
                for (slice in RegionTransferTiles.sparseSlices(clear.rect, clear.rect, emptySequence(),
                    mask.tiles.keys.asSequence() + pendingKeys(mask.id, null).asSequence())) stageTranslated(mask, null, slice, null)
            }
            for(copy in change.copies) {
                val layer=layers[copy.layerId] ?: error("No layer ${copy.layerId}")
                val source=layer.cels[copy.fromCelId] ?: error("No source cel ${copy.fromCelId}")
                val destination=layer.cels.getOrPut(copy.toCelId){HashMap()}
                // Include destination keys: copying blank source must clear existing owned pixels too.
                for (slice in RegionTransferTiles.sparseSlices(copy.rect, copy.rect,
                    source.keys.asSequence() + pendingKeys(copy.layerId, copy.fromCelId).asSequence(),
                    destination.keys.asSequence() + pendingKeys(copy.layerId, copy.toCelId).asSequence())) {
                    val key = slice.destinationKey
                    val sourceAddress=copy.layerId to (copy.fromCelId to key)
                    val texture=if(sourceAddress in pending)pending.getValue(sourceAddress).after else source[key]
                    stage(layer,copy.toCelId,key,texture,slice.destination)
                }
            }
            for (transfer in change.transfers) {
                val layer = layers.getValue(transfer.layerId)
                // Live stores stay immutable until every reciprocal transfer is staged.
                val source = layer.cels.getValue(transfer.fromCelId)
                for (slice in RegionTransferTiles.sparseSlices(transfer.sourceRect, transfer.destinationRect,
                    source.keys.asSequence(), layer.cels[transfer.toCelId].orEmpty().keys.asSequence() +
                        pendingKeys(layer.id, transfer.toCelId).asSequence())) {
                    stageTranslated(layer, transfer.toCelId, slice, source[slice.sourceKey])
                }
            }
            for (transfer in change.maskTransfers) {
                val mask = requireNotNull(layers.getValue(transfer.layerId).mask)
                for (slice in RegionTransferTiles.sparseSlices(transfer.sourceRect, transfer.destinationRect,
                    mask.tiles.keys.asSequence(), mask.tiles.keys.asSequence() + pendingKeys(mask.id, null).asSequence())) {
                    stageTranslated(mask, null, slice, mask.tiles[slice.sourceKey])
                }
            }
            for(drop in change.drops) {
                val layer=layers[drop.layerId] ?: error("No layer ${drop.layerId}")
                val keys = layer.cels[drop.celId].orEmpty().keys.asSequence() + pendingKeys(drop.layerId, drop.celId).asSequence()
                for(key in keys.distinct()) {
                    val tex=layer.cels[drop.celId]?.get(key)
                    val address=layer.id to (drop.celId to key)
                    val old=pending[address]; old?.after?.let(::recycleLayerTex)
                    pending[address]=UndoLog.TileChange(layer.id,key,old?.before ?: tex,null,drop.celId)
                }
            }
            published=true
            pending.values.forEach { c->put(c.layerId,c.key,c.after,c.celId) }
            setBoardDocument(change.doc)
            // JB-2.40 slice 3a: payloads are per plane now, so a layer gaining frames keeps them; only tiles whose pixels a
            // board edit moved lose theirs (settled: the look stays, it just can't be re-wetted until slice 3b carries it).
            if(before!=change.doc || pending.isNotEmpty()) undo.push(UndoLog.Step(pending.values.toList() + settlePlainWrites(pending.values),
                documentBefore=before,documentAfter=change.doc))
        } catch(e:Throwable) {
            // Restore every published address before releasing prepared textures.
            if(published) { pending.values.forEach{c->put(c.layerId,c.key,c.before,c.celId)}; setBoardDocument(before) }
            pending.values.forEach { c->c.after?.let(::recycleLayerTex) }
            throw e
        } finally { GLES30.glDisable(GLES30.GL_SCISSOR_TEST); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0) }
    }

    fun celTileKeys(layerId: String,celId: String): List<Long> {
        settleWatercolourFade()
        val layer = layers[layerId] ?: return emptyList()
        return physicalDisplayTiles(layer, planeOf(layer, celId)).keys.toList()
    }
    fun writeCelTile(layerId: String,celId: String,key: Long,rgba: ByteArray) {
        requireLocalPaint()
        require(rgba.size==size*size*4)
        val layer=layers[layerId] ?: error("No live layer $layerId")
        val store=layer.cels[celId] ?: error("No live cel $celId")
        uploadTile(store.getOrPut(key){newLayerTile()},rgba); invalidateProjection(layer)
    }
    fun readCelTile(layerId: String,celId: String,key: Long): ByteArray? {
        settleWatercolourFade()
        val layer = layers[layerId] ?: return null
        return readTextureTile(physicalDisplayTiles(layer, planeOf(layer, celId))[key])
    }

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
    /** Display borrows source bases or actual line looks; it never adopts a look as pixel truth. */
    private fun physicalDisplayTiles(layer: Layer, cel: String?): Map<Long, Int> {
        val root = publishedPaint
        val at = layer.id to cel
        val physical = root?.recorded?.planes?.get(at)
            ?: return if (cel == null) layer.tiles else layer.cels[cel].orEmpty()
        val lineKeys = root.recorded.savedKeys[at].orEmpty()
        return (physical.slabs.keys + lineKeys).associateWith { key ->
            checkNotNull(PromotedBaseProjection.displayTile(physical, lineKeys,
                root.recorded.looks[at]?.get(key), key))
        }
    }
    private fun validateRecordedPhysicalDisplay(state: RecordedScene.State<Int>) {
        for ((at, cel) in state.planes) {
            val keys = state.savedKeys[at].orEmpty()
            require(keys.all { state.looks[at]?.get(it) != null }) { "Recorded display is missing a line-crossed saved look" }
            require(cel.slabs.all { (key, stack) -> key in keys || state.looks[at]?.get(key) != null ||
                (stack.size == 1 && stack.single().seq == 0L && stack.single().value.op == "normal") }) {
                "Ordered plain slabs need their separate display cache before publication"
            }
        }
    }
    private fun visibleTiles(layer: Layer): Map<Long,Int> {
        if (layer.plan?.frames.isNullOrEmpty()) return physicalDisplayTiles(layer, null)
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
        val plan=layer.plan ?: return physicalDisplayTiles(layer, null)
        if(plan.frames.isEmpty())return physicalDisplayTiles(layer, null)
        layer.visibleProjection?.let{return it}
        val keys=LinkedHashSet<Long>()
        val physical = (listOf<String?>(null) + plan.frames.map { planeOf(layer, it.celId) })
            .distinct().associateWith { physicalDisplayTiles(layer, it) }
        keys.addAll(physical[null].orEmpty().keys)
        plan.frames.forEach { keys.addAll(physical[planeOf(layer, it.celId)].orEmpty().keys) }
        val result=LinkedHashMap<Long,Int>()
        for(key in keys) {
            val slices=plan.tileSlices(key)
            if(slices.size==1) { physical[planeOf(layer,slices[0].plane.celId)]?.get(key)?.let{result[key]=it}; continue }
            val sources=slices.mapNotNull { slice -> physical[planeOf(layer,slice.plane.celId)]?.get(key)?.let{slice.rect to it} }
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

    /** A staged line's premultiplied source unit; its operator is applied by the ordered composer. */
    internal class RecordedLineUnit(val op: String, val tiles: Map<Long, Int>,
                                    internal val physicalEpoch: Long? = null)

    /**
     * Replay through this context's live stamping and watercolor paths, without publishing pixels or history.
     * The returned RGBA8 units include stroke opacity once and retain the brush's operator separately.
     * Caller owns every returned texture until it is committed or discarded. Final cel clipping is the composer's job.
     */
    internal fun prepareRecordedLine(layerId: String, record: StrokeRecord, preset: BrushPreset): RecordedLineUnit {
        recordedLinePreparationCalls++
        check(ready) { "The drawing context is not ready for recorded replay" }
        check(!strokeInProgress && !wcFading) { "Finish the current stroke before replaying a line" }
        check(framePreviews.isEmpty()) { "Stop playback before editing lines" }
        require(tileBoardId == null) { "Recorded line replay on a periodic board is not connected yet" }
        require(storeOf(layerId)?.isMask == false) { "Recorded lines require a paint layer" }
        require(record.brushId == preset.id) { "A line must resolve its own frozen brush" }
        require(BrushValidate.validate(preset).isEmpty()) { "This frozen brush cannot be replayed" }
        require(preset.engine == "stamp" || preset.engine == ENGINE_WATERCOLOUR) { "This line engine is not connected to GPU replay yet" }
        require(preset.engine != "stamp" || preset.tip.source == "procedural") { "Image tips are not connected to recorded GPU stamping yet" }
        require(preset.line == null) { "Whole-stroke shaping is not connected to live recording yet" }
        require(preset.blend in setOf("normal", "erase", "behind", "multiply", "screen")) { "This line operator is not connected to GPU composition yet" }
        require(paperWarnings.isEmpty()) { "Recorded replay needs the document's actual paper resources" }
        val replay = InkReplay.replay(record, preset)
        // Older grain brushes used the newest contact of each native event batch as a uniform.
        // Raw samples alone do not retain those batch boundaries, so varying legacy pose cannot
        // be transcribed as one draw without changing the grain. Modern contacts carry it per dab.
        if (preset.version < VERSION_CONTACT &&
            (replay.strokeGrain.tip.enabled || replay.strokeGrain.paper.enabled || !replay.strokeGrain.paperResponse.isDefault)) {
            replay.dabs.firstOrNull()?.let { first ->
                require(replay.dabs.all { dab ->
                    GrainMath.tiltAmount(dab.tilt) == GrainMath.tiltAmount(first.tilt) &&
                        GrainMath.leanX(dab.azimuth) == GrainMath.leanX(first.azimuth) &&
                        GrainMath.leanY(dab.azimuth) == GrainMath.leanY(first.azimuth)
                }) { "This older brush's changing tilt needs its original event batches to replay exactly" }
            }
        }
        val tip = TipShape(aspect = preset.tip.aspect, corner = preset.tip.corner, taper = preset.tip.taper,
            hardness = replay.strokeHardness, minPx = preset.tip.minPx)
        val targets = LinkedHashMap<Long, Int>()
        val physical = physicalContextEpoch
        // Free RGBA8 pool entries are intentionally available as fresh replay outputs.
        val borrowed = (protectedRecordedTextureHandles() - freeLayerTex.toSet()) +
            recordedWorkingTiles.snapshot(physical).keys
        fun current() = ready && !forgettingGraphicsContext && physicalContextEpoch == physical
        var succeeded = false
        var firstFailure: Throwable? = null
        try {
            // Scratch-render contract: callers do not supply unpack storage or layout.
            // Rendering ends with engine scratch bindings reset, not arbitrary caller-state restoration.
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, 0)
            beginStrokeBuffer(layerId, record.colorArgb, replay.strokeOpacity,
                if (preset.accumulate == "buildup") Accumulate.BUILD_UP else Accumulate.WASH,
                StrokeBlend.NORMAL, tip, replay.strokeGrain,
                watercolour = if (preset.engine == ENGINE_WATERCOLOUR) preset.watercolourRuntime() else null,
                watercolourSeed = record.seed)
            requireRecordedGrainAssets(replay.strokeGrain, look, loadedPaper?.paper)
            addDabs(replay.dabs)
            if (wcSpec != null) wcPass?.finishInto(strokeTiles, 1f)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glViewport(0, 0, size, size)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glDisable(GLES30.GL_BLEND)
            commitProg.use()
            setCommitUniforms(layerOpacity = 1f)
            GLES30.glBindVertexArray(tileVao)
            for ((key, source) in strokeTiles) {
                val output = newLayerTile()
                check(output != 0) { "Could not allocate a recorded line tile" }
                targets[key] = output
                initializeTile(output, null, false)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                attach(output)
                check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                    "Recorded line framebuffer is unavailable"
                }
                val ox = Tiles.tx(key).toFloat() * size
                val oy = Tiles.ty(key).toFloat() * size
                GLES30.glUniform2f(commitProg.loc("u_tileOrigin"), ox, oy)
                GLES30.glUniformMatrix3fv(commitProg.loc("u_docToClip"), 1, false, tileToClip(ox, oy), 0)
                bindTextures(clearTex, source)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Recorded line rendering failed" }
            }
            check(current()) { "Recorded replay lost its physical context" }
            require(targets.values.all { it != 0 && it !in borrowed && it !in floatNames } &&
                targets.values.distinct().size == targets.size) { "Recorded replay returned borrowed or aliased source names" }
            val unit = RecordedLineUnit(preset.blend, targets.toMap(), physical)
            recordedWorkingTiles.register(physical, unit.tiles.values, size.toLong() * size * 4)
            succeeded = true
            return unit
        } catch (failure: Throwable) {
            firstFailure = failure
            throw failure
        } finally {
            // Never delete, recycle, or restore old names into a replacement GL context.
            if (current()) {
                try {
                    if (!succeeded) {
                        // Failed storage is not suitable for reuse; borrowed names stay untouched.
                        targets.values.filter { it != 0 && it !in borrowed }.distinct().forEach {
                            GLES30.glDeleteTextures(1, intArrayOf(it), 0)
                            recordedWorkingTiles.forget(physical, it)
                        }
                        if (strokeTiles.isNotEmpty()) {
                            GLES30.glDeleteTextures(strokeTiles.size, strokeTiles.values.toIntArray(), 0)
                            strokeTiles.clear()
                        }
                    }
                    cancelStroke()
                    GLES30.glBindVertexArray(0)
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                } catch (cleanup: Throwable) {
                    val original = firstFailure
                    if (original == null) {
                        // A return whose final cleanup failed must not leave unreachable outputs.
                        targets.values.filter { it != 0 && it !in borrowed }.distinct().forEach {
                            try {
                                GLES30.glDeleteTextures(1, intArrayOf(it), 0)
                                recordedWorkingTiles.forget(physical, it)
                            } catch (secondary: Throwable) {
                                if (secondary !== cleanup) try { cleanup.addSuppressed(secondary) } catch (_: Throwable) { }
                            }
                        }
                        throw cleanup
                    }
                    if (cleanup !== original) try { original.addSuppressed(cleanup) } catch (_: Throwable) { }
                }
            }
        }
    }

    /** A complete unmasked recipe image; only this engine can construct the captured lease. */
    private class CapturedRecipeLease(val record: StrokeRecord, val unit: RecordedLineUnit,
                                      val physical: Long, val graphics: Long, val admittedTileBytes: Long)

    private class CapturedCopyMetrics {
        var allocations = 0
        var copies = 0
        var framebufferChecks = 0
        var glChecks = 0
    }

    /** Diagnostic profile only. No Scene, live input, save or recovery capability is granted. */
    private fun capturedRecipeLease(layerId: String, input: StrokeRecord, preset: BrushPreset,
                                    residentPlanes: Map<Pair<String, String?>, RecordedCel<Int>>? = null,
                                    workingTileBudgetBytes: Long? = null): CapturedRecipeLease {
        val record = cc.joycreator.joybrush.core.stroke.CapturedStrokeRules.snapshot(input)
        require(record.capturedPhysicalSource != null &&
            cc.joycreator.joybrush.core.stroke.CapturedStrokeRules.problems(record).isEmpty()) { "Invalid captured recipe" }
        require(record.brushId == preset.id && BrushValidate.validate(preset).isEmpty()) { "Captured diagnostic needs its valid frozen brush" }
        require(preset.engine == "stamp" || preset.engine == ENGINE_WATERCOLOUR) { "Unsupported captured diagnostic engine" }
        require((preset.engine != "stamp" || preset.tip.source == "procedural") && preset.line == null) {
            "Captured diagnostic needs a procedural unshaped recipe"
        }
        require(record.samples.size in 1..64 && record.samples.all { kotlin.math.abs(it.x) <= 4096f && kotlin.math.abs(it.y) <= 4096f } &&
            record.widthScale <= 2f && record.screenPerDoc in 0.125f..8f &&
            (preset.engine == ENGINE_WATERCOLOUR || preset.size.base in 1f..64f)) {
            "This diagnostic accepts only its bounded small recipe profile"
        }
        val length = record.samples.zipWithNext().sumOf { (a,b) ->
            kotlin.math.hypot((b.x-a.x).toDouble(), (b.y-a.y).toDouble()) }
        require(length <= 2048.0) { "Captured diagnostic path budget exceeded" }
        require(preset.blend == "normal") { "Captured diagnostic supports NORMAL source units" }
        if (workingTileBudgetBytes == null) requireLocalPaint()
        check(ready && (workingTileBudgetBytes != null || recordedScene == null) &&
            !mediaStrokeInProgress && !strokeInProgress && !wcFading) {
            "Captured replay needs an initialized idle engine"
        }
        require(framePreviews.isEmpty() && tileBoardId == null && storeOf(layerId)?.isMask == false) {
            "Captured diagnostic needs a physical paint owner without playback or periodic state"
        }
        require(workingTileBudgetBytes != null || (ownedNativeTextures(layers).isEmpty() && !undo.canUndo && !undo.canRedo &&
            freeLayerTex.isEmpty() && freeStrokeTex.isEmpty() && freeSmudgeTex.isEmpty() && freeFloatTex.isEmpty())) {
            "Captured diagnostic needs a fresh empty engine, history and pixel pools"
        }
        val paper = checkNotNull(resolvedPaper) { "Captured diagnostic needs resolved paper" }
        require(paperWarnings.isEmpty() &&
            (paper.look?.file == null || lookTexture?.let { it != 0 } == true) &&
            (paper.surface?.file == null || surfaceTexture?.let { it != 0 } == true) &&
            (paper.surface?.fluid == null || fluidTexture?.let { it != 0 } == true)) { "Captured diagnostic needs actual paper textures" }
        val physical = physicalContextEpoch
        val graphics = graphicsEpoch
        // Private evaluate-recipe boundary. All recipe/sample bits are retained. The temporary
        // ordinary view never enters source/history/archive or escapes as a captured appearance.
        val recipe = record.copy(capturedPhysicalSource = null)
        // This CPU admission uses the SAME dab generator and tile allocator geometry as replay.
        // It bounds even WC's own size/dynamics/scatter before the first source GPU allocation.
        // The diagnostic evaluates the CPU recipe twice; this is not a performance claim.
        val replay = InkReplay.replay(recipe, preset)
        val admittedKeys = capturedDiagnosticSourceKeys(replay.dabs)
        val admittedPlan = cc.joycreator.joybrush.core.stroke.CapturedSourceCopyPlan.prepare(
            admittedKeys, checkNotNull(record.capturedPhysicalSource), size)
        // This fresh replay uses band + stroke + RGBA8 canonical targets (<=20 bytes/texel
        // for WC). Reserve32 bytes per source texel, plus every planned4-byte destination.
        // Fixed paper/image resources are independently required above and remain engine-owned.
        val admittedTileBytes = RecordedWorkingTilePlan.replay(admittedKeys.size, admittedPlan.destinationKeys.size, size)
        if (workingTileBudgetBytes == null) {
            require(admittedTileBytes <= boardSnapshotAllowanceBytes) { "Captured diagnostic working tile budget exceeded" }
        } else {
            recordedWorkingTiles.admit(physical, recordedWorkingTileBytes(residentPlanes.orEmpty()),
                admittedTileBytes, workingTileBudgetBytes)
        }
        val priorStaged = recordedWorkingTiles.snapshot(physical).keys
        var cleanupProtected = protectedRecordedTextureHandles() + priorStaged
        val discardName = IntArray(1)
        val unit = prepareRecordedLine(layerId, recipe, preset)
        var handedOff = false
        fun current() = ready && !forgettingGraphicsContext && physicalContextEpoch == physical && graphicsEpoch == graphics
        try {
            check(current()) { "Captured recipe context changed; old names are abandoned" }
            val owners = protectedRecordedTextureHandles() + priorStaged
            cleanupProtected = owners
            require(unit.tiles.keys == admittedKeys && unit.tiles.values.all { it != 0 && it !in owners && it !in floatNames } &&
                unit.tiles.values.distinct().size == unit.tiles.size) { "Captured recipe escaped its admitted fresh tile owners" }
            val lease = CapturedRecipeLease(record, unit, physical, graphics, admittedTileBytes)
            handedOff = true
            return lease
        } finally {
            if (!handedOff && current()) {
                // Rejected aliases stay borrowed, and failed outputs never enter a pool.
                for (name in unit.tiles.values) if (name != 0 && name !in cleanupProtected) {
                    discardName[0] = name
                    GLES30.glDeleteTextures(1, discardName, 0)
                    recordedWorkingTiles.forget(physical, name)
                }
            }
        }
    }

    /** Exact allocation keys, with range/count checks BEFORE the old unbounded enumerator. */
    internal fun capturedDiagnosticSourceKeys(dabs: List<Dab>): Set<Long> {
        require(dabs.size <= 16384) { "Captured diagnostic dab budget exceeded" }
        val keys = LinkedHashSet<Long>()
        val minTile = -((-Int.MIN_VALUE.toLong()) / size).toDouble()
        val maxTile = ((Int.MAX_VALUE.toLong()+1-size).floorDiv(size.toLong())).toDouble()
        for (dab in dabs) {
            val extent = cc.joycreator.joybrush.core.paint.TipMath.extent(dab.radius, dab.anchor)
            require(dab.x.isFinite() && dab.y.isFinite() && extent.isFinite() && extent >= 0f) { "Invalid captured dab extent" }
            // Retain Tiles.touchedBy's Float arithmetic, then check before narrowing/enumeration.
            val x0 = kotlin.math.floor((dab.x-extent)/size).toDouble()
            val x1 = kotlin.math.floor((dab.x+extent)/size).toDouble()
            val y0 = kotlin.math.floor((dab.y-extent)/size).toDouble()
            val y1 = kotlin.math.floor((dab.y+extent)/size).toDouble()
            require(listOf(x0,x1,y0,y1).all { it.isFinite() && it >= minTile && it <= maxTile }) {
                "Captured diagnostic dab exceeds addressable source tiles"
            }
            val width = x1.toLong()-x0.toLong()+1
            val height = y1.toLong()-y0.toLong()+1
            require(width in 1..64 && height in 1..64 && width*height <= 64) { "Captured diagnostic source tile budget exceeded" }
            for (key in Tiles.touchedBy(dab,size)) {
                require(key in keys || keys.size < 64) { "Captured diagnostic source tile budget exceeded" }
                keys.add(key)
            }
        }
        return keys.toSet()
    }

    /** Pool completely first; only then intersect the persisted domain and copy exact texels. */
    private fun copyCapturedRecipe(lease: CapturedRecipeLease, metrics: CapturedCopyMetrics): RecordedLineUnit {
        fun physicalAlive() = ready && !forgettingGraphicsContext && physicalContextEpoch == lease.physical
        fun current() = physicalAlive() && graphicsEpoch == lease.graphics
        check(current()) { "Captured copy context changed" }
        val plan = cc.joycreator.joybrush.core.stroke.CapturedSourceCopyPlan.prepare(
            lease.unit.tiles.keys, checkNotNull(lease.record.capturedPhysicalSource), size)
        val otherStaged = recordedWorkingTiles.snapshot(lease.physical).keys - lease.unit.tiles.values.toSet()
        val actualOwners = protectedRecordedTextureHandles() + otherStaged
        val protected = actualOwners + lease.unit.tiles.values
        require(lease.unit.tiles.values.all { it != 0 && it !in floatNames } &&
            lease.unit.tiles.values.distinct().size == lease.unit.tiles.size &&
            lease.unit.tiles.values.none { it in actualOwners }) { "Captured canonical source ownership is invalid" }
        // Primitive rollback ledger exists before glGen/storage or map insertion can fail.
        val names = IntArray(plan.destinationKeys.size)
        val discardName = IntArray(1)
        val outputs = LinkedHashMap<Long, Int>(names.size)
        val drawBuffer = intArrayOf(GLES30.GL_COLOR_ATTACHMENT0)
        var succeeded = false
        var firstFailure: Throwable? = null
        fun checkedGl() { metrics.glChecks++; check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Captured copy GL error" } }
        fun complete(target: Int) {
            metrics.framebufferChecks++
            check(GLES30.glCheckFramebufferStatus(target) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Captured copy framebuffer is incomplete" }
        }
        try {
            checkedGl()
            if (names.isNotEmpty()) GLES30.glGenTextures(names.size, names, 0)
            metrics.allocations = names.count { it != 0 }
            check(names.all { it != 0 && it !in protected } && names.distinct().size == names.size) { "Captured output aliases an owner" }
            checkedGl()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, 0)
            for ((i,key) in plan.destinationKeys.withIndex()) {
                check(current()) { "Captured allocation context changed" }
                val texture = names[i]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, size, size, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, copyFbo)
                attach(texture)
                GLES30.glDrawBuffers(1, drawBuffer, 0)
                complete(GLES30.GL_FRAMEBUFFER)
                GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
                GLES30.glColorMask(true,true,true,true)
                GLES30.glClearColor(0f,0f,0f,0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                checkedGl()
                outputs[key] = texture
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            for (copy in plan.copies) {
                check(current()) { "Captured blit context changed" }
                val from = lease.unit.tiles.getValue(copy.sourceKey)
                val to = outputs.getValue(copy.destinationKey)
                check(from != to && to !in protected) { "Captured copy feedback or borrowed target" }
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, readFbo)
                GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, from, 0)
                GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0)
                complete(GLES30.GL_READ_FRAMEBUFFER)
                GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, copyFbo)
                GLES30.glFramebufferTexture2D(GLES30.GL_DRAW_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, to, 0)
                complete(GLES30.GL_DRAW_FRAMEBUFFER)
                copyTextureRectTranslated(from, to,
                    RegionTileRect(copy.from.x,copy.from.y,copy.from.w,copy.from.h),
                    RegionTileRect(copy.to.x,copy.to.y,copy.to.w,copy.to.h))
                metrics.copies++
                checkedGl()
            }
            check(current()) { "Captured handoff context changed" }
            val result = RecordedLineUnit(lease.unit.op, outputs.toMap(), lease.physical)
            succeeded = true
            return result
        } catch (failure: Throwable) {
            firstFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                if (!physicalAlive()) return
                try { action() } catch (failure: Throwable) {
                    val original = firstFailure ?: cleanupFailure
                    if (original == null) cleanupFailure = failure
                    else if (failure !== original) try { original.addSuppressed(failure) } catch (_: Throwable) { }
                }
            }
            cleanup { GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, readFbo) }
            cleanup { GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, 0, 0) }
            cleanup { GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, copyFbo) }
            cleanup { GLES30.glFramebufferTexture2D(GLES30.GL_DRAW_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, 0, 0) }
            cleanup { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0) }
            cleanup { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0) }
            if (succeeded && !current() && cleanupFailure == null)
                cleanupFailure = IllegalStateException("Captured copy context changed during final cleanup")
            // A failed finally cancels a successful return; those unreturned outputs remain ours.
            if (!succeeded || cleanupFailure != null) for (name in names) if (name != 0 && name !in protected) cleanup {
                discardName[0] = name
                GLES30.glDeleteTextures(1, discardName, 0)
            }
            if (firstFailure == null) {
                cleanupFailure?.let { throw it }
            }
        }
    }

    /** Borrow-free JSON result only: both source and placed leases remain engine-owned throughout. */
    internal fun runCapturedCopyDiagnostic(layerId: String, input: StrokeRecord, preset: BrushPreset): String {
        val root = publishedPaint
        val cursor = undo.snapshotCursor()
        val native = ownedNativeTextures(layers)
        val physical = physicalContextEpoch
        val graphics = graphicsEpoch
        val metrics = CapturedCopyMetrics()
        val deleteName = IntArray(1)
        var canonical: CapturedRecipeLease? = null
        var placed: RecordedLineUnit? = null
        var report = org.json.JSONObject().put("passed", false)
        fun current() = ready && !forgettingGraphicsContext && physicalContextEpoch == physical && graphicsEpoch == graphics
        fun deleteUnit(unit: RecordedLineUnit?) {
            if (unit != null) for (name in unit.tiles.values) {
                deleteName[0] = name
                GLES30.glDeleteTextures(1,deleteName,0)
                recordedWorkingTiles.forget(physical, name)
            }
        }
        try {
            val lease = capturedRecipeLease(layerId, input, preset)
            canonical = lease
            report = RecordedCapturedCopyProbe.compare(lease.unit.tiles, checkNotNull(lease.record.capturedPhysicalSource), ::current) {
                copyCapturedRecipe(lease,metrics).also { placed = it }.tiles
            }
            check(current() && publishedPaint === root && undo.snapshotCursor() === cursor && ownedNativeTextures(layers) == native) {
                "Captured diagnostic changed native ownership or history"
            }
            report.put("nativeOwnershipUnchanged",true).put("historyCursorUnchanged",true).put("publishedRootUnchanged",true)
                .put("admittedWorkingTileBytes",lease.admittedTileBytes)
        } catch (failure: Exception) {
            report.put("passed",false).put("failure",failure.message ?: failure.javaClass.simpleName)
        } finally {
            try {
                check(current()) { "Captured diagnostic context lost; old names abandoned" }
                // The ordinary recipe renderer's main FBO retains its last output attachment.
                // Detach all engine copy/recipe attachments before deleting either private lease.
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo)
                attach(0)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,readFbo)
                attach(0)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,copyFbo)
                attach(0)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0)
                deleteUnit(placed)
                deleteUnit(canonical?.unit)
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Captured lease cleanup failed" }
                report.put("engineLeasesReleased",true)
            } catch (failure: Exception) {
                report.put("passed",false).put("engineLeasesReleased",false).put("cleanupFailure",failure.message)
            }
            report.put("adapterAllocations",metrics.allocations).put("adapterCopyCalls",metrics.copies)
                .put("adapterFramebufferChecks",metrics.framebufferChecks).put("adapterGlChecks",metrics.glChecks)
                .put("adapterScope","Private diagnostic only; no Scene/live/save/recovery activation")
        }
        return report.toString()
    }

    internal fun discardRecordedLine(unit: RecordedLineUnit) {
        if (!ready || forgettingGraphicsContext) return
        if (unit.physicalEpoch != null && unit.physicalEpoch != physicalContextEpoch) return
        unit.tiles.values.forEach(::recycleLayerTex)
    }

    /** Eager source replay, with the same concrete renderer used by saved cold scenes. */
    internal fun createRecordedScene(planes: Map<Pair<String, String?>, RecordedCel<Int>>,
                                     frozen: Map<String, BrushPreset>): RecordedScene<Int> {
        requireLocalPaint()
        checkStrokeEnded("Finish the stroke before opening recorded cels")
        val scene = RecordedScene.create(planes, frozen, undo, recordedRenderer(planes))
        registerRecordedScene(scene)
        return scene
    }

    /**
     * Internal bounded captured-source connection. Paper stays immutable for this scene;
     * View/live recording, archive save/load and paper-history activation remain separate gates.
     * The allowance covers resident/staged mutable tiles plus each callback's complete peak.
     */
    internal fun createCapturedRecordedScene(planes: Map<Pair<String, String?>, RecordedCel<Int>>,
                                             frozen: Map<String, BrushPreset>,
                                             workingTileBudgetBytes: Long = boardSnapshotAllowanceBytes): RecordedScene<Int> {
        requireLocalPaint()
        checkStrokeEnded("Finish the stroke before opening captured cels")
        require(workingTileBudgetBytes in 0..boardSnapshotAllowanceBytes) { "Invalid captured working-tile allowance" }
        val scene = RecordedScene.create(planes, frozen, undo,
            recordedRenderer(planes, capturedWorkingTileBudgetBytes = workingTileBudgetBytes))
        registerRecordedScene(scene)
        recordedWorkingTileBudgetBytes = workingTileBudgetBytes
        return scene
    }

    /**
     * Accept uploaded LINE-CROSSED saved looks without replay, composition, GL allocation or
     * pixel/history mutation. Caller owns all supplied uploads until this function RETURNS;
     * rejection leaves every upload with caller for rollback. Physical slabs stay borrowed.
     * No layer/view/save/load activation is performed, and slab-only display is not composed.
     * Caller must supply valid new RGBA8 tile uploads in this current context, not pooled names.
     * A changed cel's first edit privately materializes and verifies its declared footprint.
     */
    internal fun createSavedRecordedScene(planes: Map<Pair<String, String?>, RecordedCel<Int>>,
                                          frozen: Map<String, BrushPreset>,
                                          savedLookKeys: Map<Pair<String, String?>, Set<Long>>,
                                          uploadedLooks: Map<Pair<String, String?>, Map<Long, Int>>): RecordedScene<Int> {
        requireLocalPaint()
        // Unlike eager opening, cold acceptance must not finish/publish existing paint on refusal.
        check(!strokeInProgress && !wcFading && !mediaStrokeInProgress) { "Finish paint/media before accepting saved recorded cels" }
        val renderer = recordedRenderer(planes)
        require(uploadedLooks.values.all { tiles -> tiles.values.all { it != 0 } }) { "A saved look needs a nonzero uploaded texture" }
        require(planes.values.all { cel -> cel.slabs.values.all { stack -> stack.all { it.value.texture != 0 } } }) {
            "A physical saved slab needs a nonzero borrowed texture"
        }
        val scene = RecordedScene.createSaved(planes, frozen, undo, renderer,
            savedLookKeys, uploadedLooks, protectedRecordedTextureHandles())
        // No throwing operation/reentrant callback between ownership acceptance and publication.
        // recordedRenderer checked registration, and createSaved's actual callbacks only read
        // captured engine state. The GL-thread assignment therefore completes the transfer once.
        recordedScene = scene
        return scene
    }

    /**
     * Diagnostic fresh-engine loader. Never resets an occupied engine to make it qualify, and
     * never changes the View/Open/Working/recovery gates. Uploads are fresh, separately tracked
     * allocations; cold acceptance does not replay lines or compose their saved appearance.
     * First edit still validates the real footprint and all dynamic replay restrictions.
     */
    internal fun loadFreshRecordedArtwork(plan: ColdRecordedLoadPlan.Prepared): ColdRecordedLoadReceipt =
        loadFreshRecordedArtworkImpl(plan,null)

    private class ColdInjectedFailure(val target: ColdUploadFailureTarget) :
        IllegalStateException("Controlled cold upload failure at $target")
    private class ColdInjectedRetirementFailure : IllegalStateException("Controlled failure after cold root retirement")
    private class ColdFailureAttempt(val target: ColdUploadFailureTarget, count: Int) {
        val names=IntArray(count)
        var reached=false
    }

    private fun loadFreshRecordedArtworkImpl(plan: ColdRecordedLoadPlan.Prepared,
                                             attempt: ColdFailureAttempt?): ColdRecordedLoadReceipt {
        requireLocalPaint()
        check(ready && !forgettingGraphicsContext && recordedScene == null && !undo.isCursorBound &&
            layers.isEmpty() && boardDocument == null && !undo.canUndo && !undo.canRedo &&
            !strokeInProgress && !wcFading && !mediaStrokeInProgress && strokeTiles.isEmpty() &&
            mediaStrokeHeld.isEmpty() && agreedLook.isEmpty() && framePreviews.isEmpty() &&
            tileBoardId == null && tilePreviewRect == null && onionBoardId == null && strokeTileRect == null) {
            "Cold source loading needs an initialized empty idle engine without playback or periodic state"
        }
        undo.checkCursorBindingAllowed()
        require(plan.recordedPlanes.isNotEmpty()) { "Use the native loader for an entirely native drawing" }
        require(plan.summary.uploadBytes <= boardSnapshotAllowanceBytes) { "Cold source upload budget exceeded" }
        val mediaBytes = plan.uploads.filter { it.store != null }.sumOf { it.byteCount.toLong() }
        require(mediaBytes <= mediaBudgetBytes) { "Cold source media budget exceeded" }
        val doc = plan.metadataCopy()
        require(PaperState.problems(doc.paper, PaperResources.catalogue).isEmpty()) {
            "Cold recorded sources require the document's actual paper resources"
        }
        val stack = LayerStack(doc.layers.map { LayerState(it.id, it.name, it.opacity, it.visible,
            it.blend, it.mask != null, it.clip) }, doc.activeLayerId ?: doc.layers.last().id)
        val frozen = plan.frozenBrushesCopy()
        val records = plan.recordedPlanes.associate { it.address to plan.recordsCopy(it.address) }
        val receipt = ColdRecordedLoadReceipt(
            plan.uploads.count { it.kind == ColdRecordedLoadPlan.Kind.NATIVE_RGBA8 },
            plan.uploads.count { it.kind == ColdRecordedLoadPlan.Kind.MASK_RGBA8 },
            plan.uploads.count { it.store != null },
            plan.recordedPlanes.sumOf { it.slabs.size }, plan.recordedPlanes.sumOf { it.savedLooks.size },
            plan.recordedPlanes.size, plan.summary.unresolvedLinePlanes)
        val physical = physicalContextEpoch
        val preparationCalls = recordedLinePreparationCalls
        val compositionCalls = recordedCompositionCalls
        val priorPaper = documentPaper
        val priorResolved = resolvedPaper
        val priorLoaded = loadedPaper
        val priorLook = lookTexture; val priorSurface = surfaceTexture; val priorFluid = fluidTexture
        // Indexed primitive ledgers are allocated BEFORE glGen. Every generated name is registered
        // by glGen itself, before storage/upload/map allocation can fail. 1=caller,2=native,3=scene,4=root.
        val names = attempt?.names ?: IntArray(plan.uploads.size)
        val ownership = ByteArray(names.size)
        val glState = ColdUploadGlState.capture()
        var priorOwners: Set<Int> = emptySet()
        fun current() = ready && physicalContextEpoch == physical && !forgettingGraphicsContext
        fun checkedGl() { check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Cold source GL upload failed" } }
        fun faultPoint(phase: ColdUploadFailurePhase, id: Int? = null) {
            val fault=attempt ?: return
            val matched=fault.target == ColdUploadFailureTarget(phase,id) ||
                (phase == ColdUploadFailurePhase.AFTER_ROOT_BIND &&
                    fault.target.phase == ColdUploadFailurePhase.AFTER_ROOT_BIND_WITH_RETIRE_FAILURE)
            if(matched) {
                check(current()) { "Cold context retired before controlled failure" }
                checkedGl()
                fault.reached=true
                throw ColdInjectedFailure(fault.target)
            }
        }
        try {
            checkedGl()
            glState.unpackTightly()
            setDocumentPaper(doc.paper)
            require(paperWarnings.isEmpty()) { "Cold recorded sources could not load the actual paper" }
            val actualPaper = checkNotNull(resolvedPaper)
            require((actualPaper.look?.file == null || lookTexture?.let { it != 0 } == true) &&
                (actualPaper.surface?.file == null || surfaceTexture?.let { it != 0 } == true) &&
                (actualPaper.surface?.fluid == null || fluidTexture?.let { it != 0 } == true)) {
                "Cold recorded sources need every requested paper GPU texture, including fluid"
            }
            checkedGl()
            setStack(stack)
            setBoardDocument(doc)
            val protected = protectedRecordedTextureHandles()
            priorOwners = protected
            for (upload in plan.uploads) {
                check(current()) { "Graphics context changed during cold loading" }
                val bytes = plan.copyPayload(upload.id)
                val isFloat = upload.kind == ColdRecordedLoadPlan.Kind.MEDIA_RGBA16F
                val buffer = if (isFloat) {
                    val floats = HalfFloat.decode(bytes)
                    ByteBuffer.allocateDirect(floats.size * 4).order(ByteOrder.nativeOrder()).also {
                        it.asFloatBuffer().put(floats)
                    }
                } else ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).also { it.put(bytes); it.rewind() }
                GLES30.glGenTextures(1, names, upload.id)
                ownership[upload.id] = 1
                val texture = names[upload.id]
                check(texture != 0 && texture !in protected && (0 until upload.id).none { names[it] == texture }) {
                    "Cold source allocation needs an exclusive fresh texture"
                }
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
                // Instantiate the reserved name before the diagnostic fault so glIsTexture
                // can distinguish an undeleted allocation from an actually released object.
                faultPoint(ColdUploadFailurePhase.AFTER_GENERATE,upload.id)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, if (isFloat) GLES30.GL_RGBA16F else GLES30.GL_RGBA8,
                    size, size, 0, GLES30.GL_RGBA, if (isFloat) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE, null)
                faultPoint(ColdUploadFailurePhase.AFTER_STORAGE,upload.id)
                // Match ordinary native allocation, including later pool reuse and zoom sampling.
                val filter = if (isFloat) GLES30.GL_NEAREST else GLES30.GL_LINEAR
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, size, size, GLES30.GL_RGBA,
                    if (isFloat) GLES30.GL_FLOAT else GLES30.GL_UNSIGNED_BYTE, buffer)
                faultPoint(ColdUploadFailurePhase.AFTER_UPLOAD,upload.id)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                attach(texture)
                check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                    "Cold source texture is not framebuffer complete"
                }
                checkedGl()
                when (upload.kind) {
                    ColdRecordedLoadPlan.Kind.NATIVE_RGBA8 -> {
                        val at = checkNotNull(upload.address)
                        checkNotNull(if (at.celId == null) layers.getValue(at.layerId).tiles
                            else layers.getValue(at.layerId).cels[at.celId])[upload.key] = texture
                        ownership[upload.id] = 2
                    }
                    ColdRecordedLoadPlan.Kind.MASK_RGBA8 -> {
                        checkNotNull(layers.getValue(upload.layerId).mask).tiles[upload.key] = texture
                        ownership[upload.id] = 2
                    }
                    ColdRecordedLoadPlan.Kind.MEDIA_RGBA16F, ColdRecordedLoadPlan.Kind.GROUND_RGBA8 -> {
                        val at = checkNotNull(upload.address)
                        if (isFloat) floatNames.add(texture)
                        payload(layers.getValue(at.layerId), checkNotNull(upload.store), at.celId)[upload.key] = texture
                        ownership[upload.id] = 2
                    }
                    else -> Unit // Slabs and saved looks stay separately caller-owned until their handoff.
                }
            }
            agreeAllPayloads()
            check(current())
            // Restore fallible GL state BEFORE accepting scene/root ownership. Factory/binding are CPU-only.
            glState.restore()
            checkedGl()
            val planes = plan.recordedPlanes.associate { plane ->
                val at = plane.address
                (at.layerId to at.celId) to RecordedCel(at.layerId, at.celId, records.getValue(at),
                    plane.slabs.groupBy { it.key }.mapValues { (_, uploads) -> uploads.map { upload ->
                        RecordedCel.PixelSlab(checkNotNull(upload.seq), UndoLog.SlabValue(names[upload.id], checkNotNull(upload.op)))
                    } })
            }
            val keys = plan.recordedPlanes.associate { (it.address.layerId to it.address.celId) to it.savedLookKeys.toSet() }
            val looks = plan.recordedPlanes.associate { (it.address.layerId to it.address.celId) to
                it.savedLooks.associate { upload -> upload.key to names[upload.id] } }
            createSavedRecordedScene(planes, frozen, keys, looks)
            plan.recordedPlanes.forEach { plane -> plane.savedLooks.forEach { ownership[it.id] = 3 } }
            faultPoint(ColdUploadFailurePhase.AFTER_SAVED_SCENE)
            bindRecordedPaintPublication()
            plan.recordedPlanes.forEach { plane -> plane.slabs.forEach { ownership[it.id] = 4 } }
            faultPoint(ColdUploadFailurePhase.AFTER_ROOT_BIND)
            check(current() && hasJointPaintPublication && !undo.canUndo && !undo.canRedo &&
                boardDocument === doc && recordedLinePreparationCalls == preparationCalls &&
                recordedCompositionCalls == compositionCalls) { "Cold loading changed source rendering or publication" }
            return receipt
        } catch (failure: Throwable) {
            fun cleanup(action: () -> Unit) { try { action() } catch (secondary: Throwable) { failure.addSuppressed(secondary) } }
            if (current()) {
                val wasBound = publishedPaint != null
                if (!wasBound) cleanup { releaseRecordedScene() }
                if(wasBound && attempt?.target?.phase == ColdUploadFailurePhase.AFTER_ROOT_BIND_WITH_RETIRE_FAILURE)
                    cleanup { resetJointPaint(RecordedScene.ResetMode.SAME_CONTEXT,true) }
                cleanup { resetDocument() }
                if (current()) cleanup {
                    // Partial reset/scene-close failure can orphan caller, native or look uploads.
                    // A float-format registry entry alone is not an owner. Retain actual stores,
                    // caches/pools and every pre-attempt name; delete only this attempt's leftovers.
                    // A reset may retire the root, then throw while releasing caches. Its
                    // unrecycled slabs are then orphans too. If retirement did NOT complete,
                    // retain every slab still referenced by the surviving published root.
                    val rootedSlabs = publishedPaint?.recorded?.planes?.values.orEmpty().flatMap { cel ->
                        cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } }
                    }
                    val protected = activeTextureOwners() + priorOwners + rootedSlabs
                    for (id in names.indices) if (ownership[id].toInt() in 1..4 &&
                        names[id] != 0 && names[id] !in protected) {
                        floatNames.remove(names[id])
                        GLES30.glDeleteTextures(1, names, id)
                        recordedWorkingTiles.forget(physical, names[id])
                    }
                }
                if (current()) {
                    localDocumentPaper = priorPaper; resolvedPaper = priorResolved; loadedPaper = priorLoaded
                    lookTexture = priorLook; surfaceTexture = priorSurface; fluidTexture = priorFluid
                    cleanup { paperBackground.invalidate() }
                    // reset/retirement cleanup may bind GL objects even if state was restored
                    // before scene binding. Reestablish caller state after the final cleanup.
                    cleanup { glState.restore() }
                }
            }
            // On context retirement no old ledger name may be deleted/restored in the new epoch.
            throw failure
        }
    }

    /** Names remain private to this engine; only independently checked counts leave the probe. */
    internal fun runColdUploadFailureProbe(plan: ColdRecordedLoadPlan.Prepared,
                                          target: ColdUploadFailureTarget): String {
        require(target in ColdUploadFailureMatrix.targets(plan)) { "Use an admitted bounded fault target" }
        val alive=coldUploadProbeContext()
        val beforeGl=ColdUploadGlState.capture()
        val beforePaper=documentPaper; val beforeResolved=resolvedPaper; val beforeLoaded=loadedPaper
        val beforeLook=lookTexture; val beforeSurface=surfaceTexture; val beforeFluid=fluidTexture
        val beforePixels=renderPaper(RectPx(0,0,8,8))
        val preparations=recordedLinePreparationCalls; val compositions=recordedCompositionCalls
        val attempt=ColdFailureAttempt(target,plan.uploads.size)
        var expectedFailure: ColdInjectedFailure? = null
        var unexpected: String? = null
        var glChecks=0; var generated=0; var owned=0; var deleted=0; var orphaned=0; var dangling=0
        var empty=false; var restored=false; var paperRestored=false; var cpuPaperRestored=false
        var paperTexturesValid=false; var priorPaperTextureChecks=0
        try {
            loadFreshRecordedArtworkImpl(plan,attempt)
            unexpected="Controlled failure target was not reached"
        } catch(error: ColdInjectedFailure) {
            expectedFailure=error
        } catch(error: Exception) {
            unexpected="${error.javaClass.simpleName}: ${error.message}"
        }
        val current=alive()
        if(current) try {
            empty=publishedPaint == null && recordedScene == null && layers.isEmpty() &&
                boardDocument == null && !undo.canUndo && !undo.canRedo
            restored=beforeGl.sameValues(ColdUploadGlState.capture())
            paperRestored=documentPaper == beforePaper && resolvedPaper === beforeResolved && loadedPaper === beforeLoaded &&
                lookTexture == beforeLook && surfaceTexture == beforeSurface && fluidTexture == beforeFluid
            cpuPaperRestored=beforePixels.contentEquals(renderPaper(RectPx(0,0,8,8)))
            val protected=activeTextureOwners() + publishedPaint?.recorded?.planes?.values.orEmpty().flatMap { cel ->
                cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } }
            }
            for(name in attempt.names.filter { it != 0 }.distinct()) {
                check(alive()) { "Context retired during failed-upload inventory" }
                val exists=GLES30.glIsTexture(name)
                check(alive()) { "Context retired after failed-upload query" }
                generated++
                if(name in protected) {
                    if(exists) owned++ else dangling++
                } else if(!exists) deleted++ else orphaned++
                glChecks++; check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Failed-upload texture query GL error" }
            }
            val priorPaperNames=listOfNotNull(beforeLook,beforeSurface,beforeFluid).filter { it != 0 }.distinct()
            paperTexturesValid=priorPaperNames.size == 3
            for(name in priorPaperNames) {
                check(alive())
                if(!GLES30.glIsTexture(name)) paperTexturesValid=false
                check(alive()); glChecks++; priorPaperTextureChecks++
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
            }
            check(recordedLinePreparationCalls == preparations && recordedCompositionCalls == compositions)
            glChecks++; check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
        } catch(error: Exception) { unexpected=listOfNotNull(unexpected,error.message).joinToString("; ") }
        val suppressed=expectedFailure?.suppressed.orEmpty()
        val retirementFailures=suppressed.count { it is ColdInjectedRetirementFailure }
        val injectedRetire=retirementFailures == 1
        val retireExpected=target.phase == ColdUploadFailurePhase.AFTER_ROOT_BIND_WITH_RETIRE_FAILURE
        val unexpectedCleanup=suppressed.filterNot { retireExpected && it is ColdInjectedRetirementFailure }
        val passed=unexpected == null && alive() && attempt.reached && expectedFailure?.target == target &&
            empty && restored && paperRestored && cpuPaperRestored && paperTexturesValid && orphaned == 0 && dangling == 0 &&
            generated > 0 && owned+deleted == generated && injectedRetire == retireExpected &&
            unexpectedCleanup.isEmpty() && retirementFailures == if(retireExpected) 1 else 0
        return org.json.JSONObject().put("passed",passed).put("phase",target.phase.name)
            .put("uploadId",target.uploadId ?: org.json.JSONObject.NULL)
            .put("kind",target.uploadId?.let { plan.uploads[it].kind.name } ?: org.json.JSONObject.NULL)
            .put("expectedFaultReached",attempt.reached).put("retirementFailureObserved",injectedRetire)
            .put("candidateEmpty",empty).put("callerGlStateRestored",restored).put("paperFieldsRestored",paperRestored)
            .put("cpuPaperRestored",cpuPaperRestored).put("priorPaperTexturesValid",paperTexturesValid)
            .put("priorPaperTextureChecks",priorPaperTextureChecks)
            .put("generatedTextures",generated).put("ownedAfterRollback",owned).put("deletedAfterRollback",deleted)
            .put("orphanedAfterRollback",orphaned).put("actualTextureGlChecks",glChecks)
            .put("danglingOwnersAfterRollback",dangling).put("unexpectedCleanupFailures",unexpectedCleanup.size)
            .put("cleanupFailures",org.json.JSONArray(suppressed.map { "${it.javaClass.simpleName}: ${it.message}" }))
            .put("linePreparationCalls",recordedLinePreparationCalls-preparations)
            .put("compositionCalls",recordedCompositionCalls-compositions)
            .put("contextCurrent",alive()).put("failure",unexpected ?: org.json.JSONObject.NULL).toString()
    }

    /** Physical context token for a disposable, empty-engine cold-upload diagnostic only. */
    internal fun coldUploadProbeContext(): () -> Boolean {
        check(ready && !forgettingGraphicsContext && !resettingJointPaint && publishedPaint == null &&
            recordedScene == null && layers.isEmpty() && !undo.canUndo && !undo.canRedo &&
            !strokeInProgress && !wcFading && !mediaStrokeInProgress) { "Cold probe needs a fresh empty context" }
        val physical = physicalContextEpoch
        return { ready && !forgettingGraphicsContext && physicalContextEpoch == physical }
    }

    /**
     * Internal cold-probe inventory, from actual stores rather than a caller's upload descriptors.
     * No GL call, replay, composition, ownership transfer or production load permission occurs.
     */
    internal fun inspectColdUploadedOwners(): ColdUploadedOwners {
        check(ready && !forgettingGraphicsContext && !resettingJointPaint &&
            !strokeInProgress && !wcFading && !mediaStrokeInProgress &&
            framePreviews.isEmpty() && tileBoardId == null) { "Cold inspection needs an idle current engine" }
        val root = checkNotNull(publishedPaint) { "Cold inspection needs a bound source root" }
        val state = root.recorded
        check(!undo.canUndo && !undo.canRedo && state.units.isEmpty() &&
            state.planes.all { (at, cel) -> cel.records.isEmpty() || !root.coordinator.isMaterialized(at) }) {
            "Inspect cold uploads before any edit, replay or history"
        }
        val physical = physicalContextEpoch
        val graphics = graphicsEpoch
        fun current() = ready && !forgettingGraphicsContext && !resettingJointPaint &&
            publishedPaint === root && physicalContextEpoch == physical && graphicsEpoch == graphics
        val owners = ArrayList<ColdUploadedOwners.Owner>()
        fun add(kind: ColdRecordedLoadPlan.Kind, layer: String, cel: String?, key: Long,
                texture: Int, store: String? = null, seq: Long? = null, op: String? = null) {
            check(owners.size < 20_000) { "Cold inspection exceeds its diagnostic entry budget" }
            owners.add(ColdUploadedOwners.Owner(ColdUploadedOwners.Address(kind,layer,cel,key,store,seq,op),texture))
        }
        for ((id, layer) in root.nativeLayers) {
            for ((key, texture) in layer.tiles) add(ColdRecordedLoadPlan.Kind.NATIVE_RGBA8,id,null,key,texture)
            for ((cel, tiles) in layer.cels) if (cel != layer.sharedCel) {
                for ((key, texture) in tiles) add(ColdRecordedLoadPlan.Kind.NATIVE_RGBA8,id,cel,key,texture)
            }
            layer.mask?.let { mask ->
                check(mask.cels.values.all { it === mask.tiles || it.isEmpty() } &&
                    mask.planes().all { (_, stores) -> stores.values.all { it.isEmpty() } }) {
                    "Cold mask inspection cannot omit auxiliary paint or media stores"
                }
                mask.tiles.forEach { (key, texture) -> add(ColdRecordedLoadPlan.Kind.MASK_RGBA8,id,null,key,texture) }
            }
            for ((cel, stores) in layer.planes()) for ((store, tiles) in stores) for ((key, texture) in tiles) {
                add(if (store == MediaStores.GROUND) ColdRecordedLoadPlan.Kind.GROUND_RGBA8
                    else ColdRecordedLoadPlan.Kind.MEDIA_RGBA16F,id,cel,key,texture,store)
            }
        }
        for ((at, cel) in state.planes) for ((key, slabs) in cel.slabs) for (slab in slabs) {
            add(ColdRecordedLoadPlan.Kind.SOURCE_SLAB_RGBA8,at.first,at.second,key,
                slab.value.texture,seq=slab.seq,op=slab.value.op)
        }
        for ((at, tiles) in state.looks) for ((key, texture) in tiles) {
            add(ColdRecordedLoadPlan.Kind.SAVED_LINE_LOOK_RGBA8,at.first,at.second,key,texture)
        }
        // Native shared-cel aliases are deliberately enumerated once; distinct physical roles
        // remain distinct entries so the probe can detect accidental texture aliasing.
        check(owners.map { it.address }.toSet().size == owners.size) { "Duplicate cold owner address" }
        val forbidden = buildSet {
            addAll(undo.heldTextures()); addAll(freeLayerTex); addAll(freeStrokeTex)
            addAll(freeSmudgeTex); addAll(freeFloatTex); addAll(strokeTiles.values)
            for (layer in layers.values) {
                addAll(layer.projected.values); addAll(layer.visibleProjection?.values.orEmpty())
                layer.mask?.let { addAll(it.projected.values); addAll(it.visibleProjection?.values.orEmpty()) }
            }
            addAll(listOf(clearTex,whiteTex,thumbTex,tuftPaperTex,compositor.target,compositor.backdrop,
                boardCompositor.target,boardCompositor.backdrop,look.tipGrainTex,look.paperGrainTex))
            addAll(listOfNotNull(lookTexture,surfaceTexture,fluidTexture,look.fluidTexture))
            addAll(grains.heldTextureHandles()); addAll(boardPreview.heldTextureHandles())
            addAll(paperBackground.heldTextureHandles()); addAll(wcPass?.heldTextureHandles().orEmpty())
            remove(0)
        }
        check(current()) { "Cold root changed during inspection" }
        return ColdUploadedOwners(owners,forbidden,floatNames,::current,
            state.planes.count { (at, cel) -> cel.records.isNotEmpty() && !root.coordinator.isMaterialized(at) })
    }

    /** Pixel unpack state belongs to the caller, including a PBO and strided/subimage uploads. */
    private class ColdUploadGlState private constructor(private val unpack: IntArray,
        private val active: Int, private val texture0: Int, private val drawFbo: Int, private val readFbo: Int) {
        fun sameValues(other: ColdUploadGlState): Boolean = unpack.contentEquals(other.unpack) &&
            active == other.active && texture0 == other.texture0 && drawFbo == other.drawFbo && readFbo == other.readFbo
        fun unpackTightly() {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
            for (i in PIXEL_NAMES.indices) GLES30.glPixelStorei(PIXEL_NAMES[i], if (i == 0) 4 else 0)
        }
        fun restore() {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture0)
            GLES30.glActiveTexture(active)
            for (i in PIXEL_NAMES.indices) GLES30.glPixelStorei(PIXEL_NAMES[i], unpack[i])
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, unpack.last())
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, drawFbo)
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, readFbo)
        }
        companion object {
            private val PIXEL_NAMES = intArrayOf(GLES30.GL_UNPACK_ALIGNMENT, GLES30.GL_UNPACK_ROW_LENGTH,
                GLES30.GL_UNPACK_SKIP_PIXELS, GLES30.GL_UNPACK_SKIP_ROWS, GLES30.GL_UNPACK_IMAGE_HEIGHT, GLES30.GL_UNPACK_SKIP_IMAGES)
            fun capture(): ColdUploadGlState {
                fun get(name: Int) = IntArray(1).also { GLES30.glGetIntegerv(name, it, 0) }[0]
                val unpack = IntArray(PIXEL_NAMES.size + 1) { if (it < PIXEL_NAMES.size) get(PIXEL_NAMES[it]) else get(GLES30.GL_PIXEL_UNPACK_BUFFER_BINDING) }
                val active = get(GLES30.GL_ACTIVE_TEXTURE)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                val texture0 = try { get(GLES30.GL_TEXTURE_BINDING_2D) } finally { GLES30.glActiveTexture(active) }
                return ColdUploadGlState(unpack, active, texture0, get(GLES30.GL_DRAW_FRAMEBUFFER_BINDING), get(GLES30.GL_READ_FRAMEBUFFER_BINDING))
            }
        }
    }

    /** Borrowed read-only inventory for adoption checks. Never count FBO/program/buffer ids here. */
    internal fun protectedRecordedTextureHandles(): Set<Int> = buildSet {
        layers.values.forEach { layer ->
            addAll(layer.ownedTextures())
            addAll(layer.visibleProjection?.values.orEmpty())
            layer.mask?.let { addAll(it.ownedTextures()); addAll(it.visibleProjection?.values.orEmpty()) }
        }
        addAll(strokeTiles.values)
        mediaStroke?.forEach { change -> change.before?.let(::add); change.after?.let(::add) }
        addAll(undo.heldTextures())
        addAll(recordedScene?.heldCacheTextures().orEmpty())
        addAll(freeLayerTex); addAll(freeStrokeTex); addAll(freeSmudgeTex); addAll(freeFloatTex)
        addAll(floatNames)
        add(clearTex); add(whiteTex); add(thumbTex)
        lookTexture?.let(::add); surfaceTexture?.let(::add); fluidTexture?.let(::add)
        addAll(agreedLook.values)
        add(compositor.target); add(compositor.backdrop)
        add(boardCompositor.target); add(boardCompositor.backdrop)
        addAll(grains.heldTextureHandles())
        addAll(boardPreview.heldTextureHandles())
        addAll(paperBackground.heldTextureHandles())
        addAll(wcPass?.heldTextureHandles().orEmpty())
        add(look.tipGrainTex); add(look.paperGrainTex); look.fluidTexture?.let(::add)
        remove(0)
    }

    /** Borrowed identities plus detached contents; inspection never transfers or releases names. */
    internal class PlainPromotionInventory internal constructor(
        val physical: Map<Pair<String, String?>, NativeBaseTransferProof.Physical>,
        val contents: Map<Pair<String, String?>, Map<Long, Int>>,
        val occurrences: List<NativeBaseTransferProof.Occurrence>,
        val rgba8Bases: Set<Int>,
        val cursor: UndoLog.Cursor<Int>,
        val context: NativeBaseTransferProof.Context,
    )

    /**
     * GL-thread dry inspection only. Native paint stores have an RGBA8 contract, including
     * caller-produced names accepted by replacement APIs. This checks current native ownership
     * and the float registry under that contract; it does NOT establish complete allocation
     * lineage or prove a driver's texture format from an integer. A future ticket must refresh
     * the inventory around providers and independently validate the complete metadata/history basis.
     */
    internal fun inspectPlainPromotionInventory(ownerId: String): PlainPromotionInventory {
        check(publishedPaint == null && !resettingJointPaint && !forgettingGraphicsContext && ready) {
            "Plain source inspection needs a current local paint context"
        }
        check(!strokeInProgress && !wcFading && !mediaStrokeInProgress && framePreviews.isEmpty() && tileBoardId == null) {
            "Finish paint/media, playback and periodic painting before inspecting plain sources"
        }
        undo.checkCursorBindingAllowed()
        // A local scene may hold other caches/sources. Exclude outstanding untracked local leases
        // without calling its renderer or changing registration; actual first promotion still
        // needs the separate no-prior-scene gate.
        recordedScene?.checkStateBindingAllowed()
        val physical = plainPromotionPhysical(ownerId)
        val cursor = undo.snapshotCursor()
        val occurrences = plainPromotionOwnershipOccurrences(cursor)
        val grouped = occurrences.groupBy { it.texture }
        val rgba8 = LinkedHashSet<Int>()
        for ((plane, value) in physical) for ((key, texture) in value.store) {
            require(texture != 0 && texture !in floatNames) { "A plain base needs a current native RGBA8 source" }
            val owners = grouped[texture].orEmpty()
            require(owners.size == 1 && owners.single().physical == NativeBaseTransferProof.Slot(plane, key)) {
                "A plain base texture has another physical, history, pool, cache or global owner"
            }
            rgba8.add(texture)
        }
        return PlainPromotionInventory(physical.toMap(), physical.mapValues { it.value.store.toMap() },
            occurrences.toList(), rgba8.toSet(), cursor,
            NativeBaseTransferProof.Context(boardDocument, documentPaper, resolvedPaper, graphicsEpoch, physicalContextEpoch))
    }

    /** A single-use metadata ticket. Cancellation never recycles borrowed native bases. */
    internal inner class PlainPromotionTicket internal constructor(
        private var validate: (() -> Boolean)?, private var install: (() -> Unit)?,
    ) : AutoCloseable {
        fun canPublish(): Boolean = validate?.invoke() == true
        fun publish(): Boolean {
            check(install != null) { "This source transfer is already finished" }
            check(!preparingPlainPromotion && !resettingJointPaint)
            preparingPlainPromotion = true
            try {
                if (!canPublish()) return false
                install!!.invoke()
                return true
            } finally {
                close()
                preparingPlainPromotion = false
            }
        }
        override fun close() { validate = null; install = null }
    }

    /**
     * Diagnostic opt-in only. Transfers one native owner and ALL canonical physical cels without
     * copying/rendering pixels or adding/dropping history. The ordinary view does not call this.
     */
    internal fun preparePlainRecordingPromotion(ownerId: String): PlainPromotionTicket {
        requireLocalPaint()
        check(recordedScene == null) { "Existing recorded sources need an extension transaction" }
        val inventory = inspectPlainPromotionInventory(ownerId)
        validatePlainPromotionHistory(inventory)
        val expectedLayers = localLayers
        val expectedOwners = expectedLayers.toMap()
        val ownerOrder = expectedLayers.keys.toList()
        val ownerChecks = expectedLayers.values.map(::plainPromotionLayerCheck)
        val document = checkNotNull(boardDocument)
        require(document.brushes.isEmpty() && document.layers.all { meta -> DocOps.storedCels(meta).all {
            it.strokesFile == null && it.slabs.isEmpty() && it.looks.isEmpty()
        } }) { "Existing recorded archive metadata needs its complete source registry before native activation" }
        val documentJson = DocJson.encode(document)
        val agreements = agreedLook.toMap()
        val expectedAgreements = localAgreedLook
        preparingPlainPromotion = true
        try {
            val source = layers.getValue(ownerId)
            val addresses = inventory.physical.keys
            val replacement = detachedNativeLayer(source, addresses.map { it.second }.toSet(), emptyRecordedStores = true)
            // These derived textures remain the same owned display allocations until an edit.
            replacement.projected.putAll(source.projected)
            replacement.visibleProjection = source.visibleProjection
            val nextLayers = LinkedHashMap(layers).also { it[ownerId] = replacement }
            val nextPhysical = inventory.physical.mapValues { (at, _) ->
                val store = if (at.second == null) replacement.tiles else replacement.cels.getValue(at.second!!)
                NativeBaseTransferProof.Physical(replacement, replacement.ownershipIdentity, replacement.plan,
                    store, if (at.second == null) store else null)
            }
            val proof = NativeBaseTransferProof.capture(inventory.context, inventory.cursor,
                inventory.physical, nextPhysical, inventory.occurrences, inventory.rgba8Bases,
                inventory.cursor.heldTextures())
            val planes = inventory.contents.mapValues { (at, pixels) -> RecordedCel.fromPixels(at.first, at.second, pixels) }
            val scene = RecordedScene.createSaved(planes, emptyMap(), undo, recordedRenderer(planes, proof),
                planes.mapValues { emptySet<Long>() }, planes.mapValues { emptyMap<Long, Int>() },
                protectedRecordedTextureHandles())
            scene.checkStateBindingAllowed()
            scene.checkBindingContext()
            undo.checkCursorBindingAllowed()
            val next = PublishedPaintState(nextLayers, HashMap(agreements), document, documentPaper,
                scene.snapshotState(), inventory.cursor, graphicsEpoch, persistentNativeTextures(nextLayers),
                scene, addresses.toSet(), (inventory.cursor.undoSteps + inventory.cursor.redoSteps).toSet(), proof)
            proof.prepareRoot(next)
            val sceneRead = { checkNotNull(publishedPaint) }
            val cursorRead = { checkNotNull(publishedPaint).cursor }
            val validate = {
                try {
                    if (publishedPaint != null || localRecordedScene != null || localLayers !== expectedLayers ||
                        layers.keys.toList() != ownerOrder || expectedOwners.any { (id, owner) -> layers[id] !== owner } ||
                        ownerChecks.any { !it() } ||
                        boardDocument !== document || DocJson.encode(document) != documentJson ||
                        localAgreedLook !== expectedAgreements || agreedLook != agreements) false
                    else {
                        val fresh = inspectPlainPromotionInventory(ownerId)
                        proof.canPublish(publishedPaint, fresh.context, fresh.cursor, fresh.physical,
                            fresh.occurrences, fresh.rgba8Bases, fresh.cursor.heldTextures())
                    }
                } catch (_: IllegalArgumentException) { false }
                  catch (_: IllegalStateException) { false }
            }
            check(validate()) { "Native source ownership changed while preparing recording" }
            return PlainPromotionTicket(validate) {
                publishedPaint = next // Sole source/history/coordinator ownership publication.
                proof.acknowledgeInstalled(next)
                scene.bindState(sceneRead)
                undo.bindCursor(cursorRead)
            }
        } finally { preparingPlainPromotion = false }
    }

    /** Captures every surviving owner's mutable metadata, map identities and detached contents. */
    private fun plainPromotionLayerCheck(owner: Layer): () -> Boolean {
        val tiles = owner.tiles.toMap()
        val cels = owner.cels.toMap(); val celContents = cels.mapValues { it.value.toMap() }
        val floats = owner.floats.toMap(); val floatContents = floats.mapValues { it.value.toMap() }
        val payloads = owner.celFloats.toMap()
        val payloadStores = payloads.mapValues { it.value.toMap() }
        val payloadContents = payloadStores.mapValues { (_, stores) -> stores.mapValues { it.value.toMap() } }
        val projected = owner.projected.toMap()
        val visible = owner.visibleProjection; val visibleContents = visible?.toMap()
        val plan = owner.plan; val shared = owner.sharedCel; val mask = owner.mask
        val planFrames = plan?.frames?.toList()
        val maskCheck = mask?.let(::plainPromotionLayerCheck)
        val metadata = listOf(owner.clip, owner.name, owner.opacity, owner.visible, owner.blend)
        return {
            owner.tiles == tiles && owner.cels.keys == cels.keys &&
                cels.all { (id, map) -> owner.cels[id] === map && map == celContents[id] } &&
                owner.floats.keys == floats.keys && floats.all { (id, map) -> owner.floats[id] === map && map == floatContents[id] } &&
                owner.celFloats.keys == payloads.keys && payloads.all { (cel, stores) ->
                    owner.celFloats[cel] === stores && stores.keys == payloadStores.getValue(cel).keys &&
                        payloadStores.getValue(cel).all { (id, map) -> stores[id] === map && map == payloadContents.getValue(cel)[id] }
                } && owner.projected == projected && owner.visibleProjection === visible && visible == visibleContents &&
                owner.plan === plan && plan?.frames == planFrames && owner.sharedCel == shared && owner.mask === mask &&
                (maskCheck?.invoke() != false) &&
                metadata == listOf(owner.clip, owner.name, owner.opacity, owner.visible, owner.blend)
        }
    }

    /** Prove BOTH reachable history branches before a renderer/provider is created. */
    private fun validatePlainPromotionHistory(inventory: PlainPromotionInventory) {
        val promoted = inventory.physical.keys
        val cursor = inventory.cursor
        val allSteps = cursor.undoSteps + cursor.redoSteps
        val current = LinkedHashMap<Triple<String, String?, Long>, Int>()
        for (owner in layers.values) {
            owner.tiles.forEach { (key, tex) -> current[Triple(owner.id, null, key)] = tex }
            owner.cels.filterKeys { it != owner.sharedCel }.forEach { (cel, tiles) ->
                tiles.forEach { (key, tex) -> current[Triple(owner.id, cel, key)] = tex }
            }
            owner.planes().forEach { (cel, stores) -> stores.forEach { (store, tiles) ->
                tiles.forEach { (key, tex) -> current[Triple(MediaStores.id(owner.id, store), cel, key)] = tex }
            } }
        }
        val ownership = inventory.occurrences.groupBy { it.texture }
        val available = current.values.toSet() + cursor.heldTextures()
        require(current.values.distinct().size == current.size) { "Native current sources alias two physical destinations" }
        for (step in allSteps) {
            require(step.stackBefore == null && step.stackAfter == null && step.paperBefore == null && step.paperAfter == null &&
                step.documentBefore == null && step.documentAfter == null && step.lines.isEmpty() && step.slabs.isEmpty() &&
                step.changes.isNotEmpty()) { "Reachable structural or recorded history needs its activation adapter" }
            val targets = HashSet<Triple<String, String?, Long>>()
            for (change in step.changes) {
                val media = MediaStores.parse(change.layerId)
                val id = media?.first ?: change.layerId
                val owner = layers[id] ?: error("A reachable history owner is missing")
                require(!owner.isMask && !id.endsWith(MASK_SUFFIX)) { "Reachable history needs a paint owner" }
                val cel = nativeHistoryCel(owner, change.celId)
                require(targets.add(Triple(change.layerId, cel, change.key))) { "A reachable history Step repeats a target" }
                val bridged = (id to cel) in promoted
                require(!bridged || media == null) { "Selected media history cannot become a plain base" }
                require(change.before != change.after || (bridged && change.before == null)) { "Reachable texture no-ops cannot transfer ownership" }
                for (texture in listOfNotNull(change.before, change.after)) {
                    require(texture != 0 && texture in available && ownership[texture]?.size == 1) {
                        "A reachable endpoint lacks exclusive current/history ownership"
                    }
                    require((texture in floatNames) == (media != null && media.second != MediaStores.GROUND)) {
                        "Reachable history has a mismatched native format contract"
                    }
                }
            }
        }
        fun walk(steps: List<UndoLog.Step<Int>>, forward: Boolean) {
            val state = current.toMutableMap()
            for (step in steps.asReversed()) {
                for (change in step.changes) {
                    val id = MediaStores.parse(change.layerId)?.first ?: change.layerId
                    val at = Triple(change.layerId, nativeHistoryCel(layers.getValue(id), change.celId), change.key)
                    require(state[at] == if (forward) change.before else change.after) { "Reachable history has a stale physical endpoint" }
                    val next = if (forward) change.after else change.before
                    if (next == null) state.remove(at) else state[at] = next
                }
                require(state.values.distinct().size == state.size) { "Reachable history aliases two physical sources" }
            }
        }
        walk(cursor.undoSteps, false); walk(cursor.redoSteps, true)
    }

    /** Existing canonical stores only: never call planeTiles/getOrPut during refusal checks. */
    private fun plainPromotionPhysical(ownerId: String): Map<Pair<String, String?>, NativeBaseTransferProof.Physical> {
        val document = checkNotNull(boardDocument) { "Plain source inspection needs the actual document" }
        require(DocOps.validate(document).isEmpty()) { "The plain source document is not valid" }
        val meta = document.layers.singleOrNull { it.id == ownerId }
            ?: throw IllegalArgumentException("The selected paint owner is missing from the document")
        val owner = layers[ownerId] ?: throw IllegalArgumentException("The selected native owner is missing")
        require(owner.id == ownerId && !owner.isMask && !ownerId.endsWith(MASK_SUFFIX) && meta.kind.hasPixels && meta.animatedIn == null)
        require(owner.mask == null && meta.mask == null && !owner.clip && !meta.clip) {
            "Selected masks and clipping need their source adapter"
        }
        require(owner.planes().all { (_, stores) -> stores.values.all { it.isEmpty() } } &&
            agreedLook.keys.none { it.first == ownerId }) { "Selected media payload/agreement state cannot become plain slabs" }
        require(meta.cels.all { it.strokesFile == null && it.slabs.isEmpty() && it.looks.isEmpty() }) {
            "Selected recorded metadata needs its existing physical sources"
        }
        val shared = meta.sharedCelId ?: meta.cels.singleOrNull()?.id
            ?: throw IllegalArgumentException("The selected document has no canonical shared cel")
        require(owner.sharedCel == shared && owner.cels.keys == meta.cels.map { it.id }.toSet() &&
            owner.cels[shared] === owner.tiles) { "Native shared/frame stores do not match the document" }
        require(owner.celFloats.keys.all { it != shared && it in owner.cels }) {
            "Selected payload metadata names a noncanonical frame cel"
        }
        val plan = checkNotNull(owner.plan) { "The selected paint owner has no physical region plan" }
        val expectedPlan = RegionDocumentOps.paintPlan(document, ownerId)
        require(plan.shared == expectedPlan.shared && plan.frames == expectedPlan.frames) {
            "The selected physical plan does not match saved document frames"
        }
        val physical = LinkedHashMap<Pair<String, String?>, NativeBaseTransferProof.Physical>()
        physical[ownerId to null] = NativeBaseTransferProof.Physical(owner, owner.ownershipIdentity, plan, owner.tiles, owner.cels[shared])
        for (cel in meta.cels) if (cel.id != shared) {
            val store = owner.cels.getValue(cel.id)
            require(physical.values.none { it.store === store }) { "Distinct physical frame cels share a store" }
            physical[ownerId to cel.id] = NativeBaseTransferProof.Physical(owner, owner.ownershipIdentity, plan, store)
        }
        return physical
    }

    /** Allocation occurrences and non-base protections, never subtraction from cold-upload protection. */
    private fun plainPromotionOwnershipOccurrences(cursor: UndoLog.Cursor<Int>): List<NativeBaseTransferProof.Occurrence> {
        val result = ArrayList<NativeBaseTransferProof.Occurrence>()
        val visited = IdentityHashMap<Layer, Boolean>()
        fun nonbase(texture: Int) { if (texture != 0) result.add(NativeBaseTransferProof.Occurrence(texture, null)) }
        fun include(owner: Layer, mask: Boolean = false) {
            require(visited.put(owner, true) == null) { "Native paint/mask ownership contains a repeated owner" }
            fun base(cel: String?, store: Map<Long, Int>) = store.forEach { (key, texture) ->
                if (texture != 0) result.add(NativeBaseTransferProof.Occurrence(texture,
                    if (mask) null else NativeBaseTransferProof.Slot(owner.id to cel, key)))
            }
            base(null, owner.tiles)
            val meta = boardDocument?.layers?.singleOrNull { it.id == owner.id }
            val shared = meta?.let { it.sharedCelId ?: it.cels.singleOrNull()?.id }
            for ((cel, store) in owner.cels) {
                // Deduplicate ONLY the real document shared alias of this same canonical map.
                if (!mask && cel == shared && cel == owner.sharedCel && store === owner.tiles) continue
                base(cel, store)
            }
            owner.projected.values.forEach(::nonbase)
            owner.planes().forEach { (_, stores) -> stores.values.forEach { it.values.forEach(::nonbase) } }
            validatePlainPromotionProjection(owner)
            owner.mask?.let { include(it, true) }
        }
        layers.values.forEach { include(it) }
        // Agreements are borrowed references to exact CURRENT look endpoints, not allocations.
        for ((at, texture) in agreedLook) {
            val owner = layers[at.first] ?: throw IllegalArgumentException("A media agreement has no paint owner")
            require(at.second == null || (at.second != owner.sharedCel && at.second in owner.cels)) {
                "A media agreement needs a canonical physical cel"
            }
            val pixels = if (at.second == null) owner.tiles else owner.cels.getValue(at.second!!)
            val stores = if (at.second == null) owner.floats else owner.celFloats[at.second]
            require(stores?.values?.any { at.third in it } == true && texture == (pixels[at.third] ?: NO_LOOK)) {
                "A media agreement no longer borrows its current physical look"
            }
        }
        strokeTiles.values.forEach(::nonbase)
        mediaStroke?.forEach { change -> change.before?.let(::nonbase); change.after?.let(::nonbase) }
        cursor.undoSteps.forEach { step ->
            step.changes.forEach { it.before?.let(::nonbase) }; step.slabs.forEach { it.before?.texture?.let(::nonbase) }
        }
        cursor.redoSteps.forEach { step ->
            step.changes.forEach { it.after?.let(::nonbase) }; step.slabs.forEach { it.after?.texture?.let(::nonbase) }
        }
        recordedScene?.let { scene ->
            val state = scene.snapshotState()
            state.units.values.forEach { it.unit.tiles.values.forEach(::nonbase) }
            state.looks.values.forEach { it.values.forEach(::nonbase) }
            state.planes.values.forEach { cel -> cel.slabs.values.forEach { stack -> stack.forEach { nonbase(it.value.texture) } } }
        }
        freeLayerTex.forEach(::nonbase); freeStrokeTex.forEach(::nonbase)
        freeSmudgeTex.forEach(::nonbase); freeFloatTex.forEach(::nonbase)
        listOf(clearTex, whiteTex, thumbTex, tuftPaperTex, compositor.target, compositor.backdrop,
            boardCompositor.target, boardCompositor.backdrop).forEach(::nonbase)
        listOfNotNull(lookTexture, surfaceTexture, fluidTexture).forEach(::nonbase)
        // Frozen stamp bindings are additional protected non-base references, not transfer aliases.
        nonbase(look.tipGrainTex); nonbase(look.paperGrainTex); look.fluidTexture?.let(::nonbase)
        grains.heldTextureHandles().forEach(::nonbase)
        boardPreview.heldTextureHandles().forEach(::nonbase)
        paperBackground.heldTextureHandles().forEach(::nonbase)
        wcPass?.heldTextureHandles()?.forEach(::nonbase)
        return result
    }

    /** Validate a borrowed display index without calling visibleTiles/projectedTiles or GL. */
    private fun validatePlainPromotionProjection(owner: Layer) {
        val visible = owner.visibleProjection ?: return
        val plan = owner.plan
        if (plan == null || plan.frames.isEmpty()) {
            require(visible == owner.tiles && visible.values.all { it != 0 }) {
                "A plain display index no longer borrows its nonzero native sources"
            }
            return
        }
        val keys = LinkedHashSet(owner.tiles.keys)
        plan.frames.forEach { keys.addAll(owner.cels[it.celId]?.keys.orEmpty()) }
        val expected = LinkedHashMap<Long, Int>()
        for (key in keys) {
            val slices = plan.tileSlices(key)
            if (slices.size == 1) {
                owner.cels[slices.single().plane.celId]?.get(key)?.let { expected[key] = it }
            } else if (slices.any { owner.cels[it.plane.celId]?.containsKey(key) == true }) {
                expected[key] = owner.projected[key]
                    ?: throw IllegalArgumentException("A sliced display index has no owned projection")
            }
        }
        require(visible == expected && visible.values.all { it != 0 }) {
            "A display index borrows an unproven source/slice or stale projection"
        }
    }

    /** Concrete paper/clipping renderer shared by eager replay and no-replay cold adoption. */
    private fun recordedRenderer(planes: Map<Pair<String, String?>, RecordedCel<Int>>,
                                 transfer: NativeBaseTransferProof? = null,
                                 capturedWorkingTileBudgetBytes: Long? = null): RecordedScene.Renderer<Int> {
        if (capturedWorkingTileBudgetBytes == null)
            planes.values.forEach { cc.joycreator.joybrush.core.stroke.CapturedStrokeRules.requireOrdinary(it.records) }
        check(ready) { "The drawing context is not ready for recorded cels" }
        check(recordedScene == null) { "Release the previous recorded scene before opening another" }
        val capturedDocument = boardDocument
        val capturedPaper = documentPaper
        val capturedResolvedPaper = resolvedPaper
        val capturedPhysicalEpoch = physicalContextEpoch
        val capturedDocumentJson = capturedDocument?.let(DocJson::encode)
        require(planes.keys.all { (id, cel) ->
            layers[id]?.let { layer -> !layer.isMask &&
                (cel == null || (cel in layer.cels && planeOf(layer, cel) == cel)) } == true
        }) { "Recorded cels must address existing canonical physical paint planes" }
        val physicalOwners = planes.keys.associateWith { plane ->
            val layer = layers.getValue(plane.first)
            Triple(layer.ownershipIdentity, layer.plan, planeTiles(layer, plane.second))
        }
        fun clips(plane: Pair<String, String?>, key: Long): List<RegionTileRect>? {
            val layer = layers.getValue(plane.first)
            return layer.plan?.tileSlices(key)?.filter { planeOf(layer, it.plane.celId) == plane.second }?.map { it.rect }
        }
        fun protectedOutputs(extra: Collection<Int> = emptyList()): Set<Int> = activeTextureOwners() + extra +
            (publishedPaint?.recorded?.planes ?: planes).values.flatMap { cel ->
                cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } }
            }
        fun validateOutputs(outputs: Collection<Int>, extra: Collection<Int> = emptyList()) {
            val owned = protectedOutputs(extra)
            require(outputs.all { it != 0 && it !in floatNames && it !in owned } && outputs.distinct().size == outputs.size) {
                "Recorded rendering must return distinct fresh RGBA8 outputs outside current owners"
            }
            check(physicalContextEpoch == capturedPhysicalEpoch && !forgettingGraphicsContext)
        }
        fun discardFresh(outputs: Collection<Int>, extra: Collection<Int> = emptyList()) {
            val owned = protectedOutputs(extra)
            for (texture in outputs.distinct()) {
                if (physicalContextEpoch != capturedPhysicalEpoch || forgettingGraphicsContext) break
                if (texture != 0 && texture !in owned) recycleLayerTex(texture)
            }
        }
        fun stagedBeforeCallback(): Set<Int> {
            check(ready && physicalContextEpoch == capturedPhysicalEpoch && !forgettingGraphicsContext) {
                "Recorded output accounting needs its current physical context"
            }
            return recordedWorkingTiles.snapshot(capturedPhysicalEpoch).keys
        }
        fun registerReturned(outputs: Collection<Int>) {
            check(ready && physicalContextEpoch == capturedPhysicalEpoch && !forgettingGraphicsContext)
            // prepareRecordedLine tracks direct callers too; ordinary line callbacks adopt
            // that same batch. Fresh look/copy outputs have not yet entered the ledger.
            val tracked = recordedWorkingTiles.snapshot(capturedPhysicalEpoch)
            recordedWorkingTiles.register(capturedPhysicalEpoch, outputs.filter { it !in tracked }, size.toLong() * size * 4)
        }
        fun discardAfterFailure(failure: Throwable, outputs: Collection<Int>, protected: Collection<Int>) {
            try { discardFresh(outputs, protected) }
            catch (secondary: Throwable) {
                if (secondary !== failure) try { failure.addSuppressed(secondary) } catch (_: Throwable) { }
            }
        }
        fun admitPeak(bytes: Long) {
            capturedWorkingTileBudgetBytes?.let { budget ->
                recordedWorkingTiles.admit(capturedPhysicalEpoch, recordedWorkingTileBytes(planes), bytes, budget)
            }
        }
        val renderer = object : RecordedScene.Renderer<Int> {
            override fun contextCurrent(): Boolean = ready && physicalContextEpoch == capturedPhysicalEpoch &&
                !forgettingGraphicsContext && boardDocument === capturedDocument &&
                boardDocument?.let(DocJson::encode) == capturedDocumentJson &&
                documentPaper == capturedPaper && resolvedPaper == capturedResolvedPaper &&
                framePreviews.isEmpty() && tileBoardId == null && if (transfer != null) {
                    try { transfer.contextCurrent(publishedPaint,
                        NativeBaseTransferProof.Context(boardDocument, documentPaper, resolvedPaper, graphicsEpoch, physicalContextEpoch),
                        plainPromotionPhysical(planes.keys.first().first)) }
                    catch (_: IllegalArgumentException) { false }
                    catch (_: IllegalStateException) { false }
                } else physicalOwners.all { (plane, captured) ->
                    val (identity, plan, store) = captured
                    layers[plane.first]?.let { owner -> owner.ownershipIdentity === identity && owner.plan == plan &&
                        (if (plane.second == null) owner.tiles else owner.cels[plane.second]) === store } == true
                }

            override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset): RecordedScene.Unit<Int> {
                val priorStaged = stagedBeforeCallback()
                var admittedKeys: Set<Long>? = null
                val rendered = if (record.capturedPhysicalSource == null) {
                    if (capturedWorkingTileBudgetBytes != null) {
                        val keys = capturedDiagnosticSourceKeys(InkReplay.replay(record, preset).dabs)
                        admittedKeys = keys
                        admitPeak(RecordedWorkingTilePlan.replay(keys.size, tileSize = size))
                    }
                    prepareRecordedLine(address.layerId, record, preset)
                } else {
                    checkNotNull(capturedWorkingTileBudgetBytes) { "Captured source replay needs explicit admission" }
                    val lease = capturedRecipeLease(address.layerId, record, preset, planes, capturedWorkingTileBudgetBytes)
                    var placed: RecordedLineUnit? = null
                    try {
                        val result = copyCapturedRecipe(lease, CapturedCopyMetrics())
                        placed = result
                        discardRecordedLine(lease.unit)
                        result
                    } catch (failure: Throwable) {
                        // Includes failure while retiring the canonical replay after a copy.
                        try {
                            placed?.let { discardFresh(it.tiles.values, priorStaged) }
                            discardFresh(lease.unit.tiles.values, priorStaged)
                        } catch (cleanup: Throwable) {
                            if (cleanup !== failure) try { failure.addSuppressed(cleanup) } catch (_: Throwable) { }
                        }
                        throw failure
                    }
                }
                try {
                    admittedKeys?.let { require(rendered.tiles.keys == it) { "Ordinary replay escaped its admitted tile keys" } }
                    validateOutputs(rendered.tiles.values, priorStaged)
                    val plane = address.layerId to address.celId
                    // Captured allocation keys, including transparent keys, are source identity.
                    // Today's physical clipping belongs to look/bake, never this historical unit.
                    val kept = if (record.capturedPhysicalSource != null) rendered.tiles else
                        rendered.tiles.filterKeys { key -> clips(plane, key)?.isNotEmpty() != false }
                    val unit = RecordedScene.Unit(rendered.op, kept)
                    discardFresh(rendered.tiles.filterKeys { it !in kept }.values, priorStaged)
                    // Final registration is atomic and is the last fallible operation before
                    // handoff. Cleanup protects prior staged owners, never this batch itself.
                    registerReturned(unit.tiles.values)
                    return unit
                } catch (failure: Throwable) {
                    discardAfterFailure(failure, rendered.tiles.values, priorStaged)
                    throw failure
                }
            }

            override fun copyForBake(address: LineAddress,
                                     unit: RecordedScene.Unit<Int>): RecordedScene.Unit<Int> {
                val priorStaged = stagedBeforeCallback() + unit.tiles.values
                val copied = linkedMapOf<Long, Int>()
                try {
                    admitPeak(RecordedWorkingTilePlan.compose(unit.tiles.size, size))
                    val plane = address.layerId to address.celId
                    for ((key, texture) in unit.tiles) {
                        // Copy the canonical source after pooling, then clip to its physical cel.
                        // The source already includes opacity; preserve its original operator on
                        // the slab instead of applying it while copying or baking the final look.
                        copied[key] = checkNotNull(composeRecordedTile(listOf(
                            OrderedRecordedUnit(0, texture, "normal", clips(plane, key)))))
                    }
                    validateOutputs(copied.values, priorStaged)
                    val result = RecordedScene.Unit(unit.op, copied)
                    registerReturned(result.tiles.values)
                    return result
                } catch (failure: Throwable) {
                    discardAfterFailure(failure, copied.values, priorStaged)
                    throw failure
                }
            }

            override fun look(plane: Pair<String, String?>, key: Long,
                              sources: List<RecordedScene.Source<Int>>): Int? {
                val priorStaged = stagedBeforeCallback() + sources.map { it.texture }
                admitPeak(RecordedWorkingTilePlan.compose(if (sources.isEmpty()) 0 else 1, size))
                val made = composeRecordedTile(sources.map { OrderedRecordedUnit(it.seq, it.texture, it.op, clips(plane, key)) })
                    ?: return null
                try {
                    validateOutputs(listOf(made), priorStaged)
                    registerReturned(listOf(made))
                    return made
                } catch (failure: Throwable) {
                    // Include cleanup-holder allocation in the guard: registration can fail
                    // from allocation pressure, and cleanup must preserve that first failure.
                    try { discardFresh(listOf(made), priorStaged) }
                    catch (secondary: Throwable) {
                        if (secondary !== failure) try { failure.addSuppressed(secondary) } catch (_: Throwable) { }
                    }
                    throw failure
                }
            }

            override fun release(texture: Int) {
                // A retained old Scene/renderer cannot remove a reused replacement-context tag.
                if (ready && physicalContextEpoch == capturedPhysicalEpoch && !forgettingGraphicsContext)
                    recycleLayerTex(texture)
            }
        }
        if (capturedWorkingTileBudgetBytes == null) return renderer
        return object : RecordedScene.CapturedSourceRenderer<Int>, RecordedScene.Renderer<Int> by renderer {
            override fun admitCapturedSource(address: LineAddress, record: StrokeRecord, preset: BrushPreset): Boolean {
                if (!renderer.contextCurrent() || (address.layerId to address.celId) !in planes) return false
                // Bounded first connection, shared with the proven full-resolution copy path.
                // CPU preflight never paints or changes ownership/history.
                return record.capturedPhysicalSource != null &&
                    cc.joycreator.joybrush.core.stroke.CapturedStrokeRules.problems(record).isEmpty() &&
                    record.brushId == preset.id && BrushValidate.validate(preset).isEmpty() &&
                    preset.blend == "normal" && preset.line == null &&
                    (preset.engine == ENGINE_WATERCOLOUR || (preset.engine == "stamp" && preset.tip.source == "procedural")) &&
                    record.samples.size in 1..64 && record.samples.all { kotlin.math.abs(it.x) <= 4096f && kotlin.math.abs(it.y) <= 4096f } &&
                    record.widthScale <= 2f && record.screenPerDoc in 0.125f..8f &&
                    (preset.engine == ENGINE_WATERCOLOUR || preset.size.base in 1f..64f)
            }
        }
    }

    /** Engine-owned lifecycle; also used by no-GL context-loss regression fixtures. */
    internal fun registerRecordedScene(scene: RecordedScene<Int>) {
        requireLocalPaint()
        check(recordedScene == null) { "A recorded scene is already registered" }
        recordedScene = scene
    }

    internal fun releaseRecordedScene() {
        check(publishedPaint == null) { "Reset the complete artwork before releasing its bound recorded scene" }
        val scene = recordedScene ?: return
        recordedScene = null
        scene.close()
    }

    /** One source at its original sequence; null clips means full tile, empty clips means no contribution. */
    internal class OrderedRecordedUnit(val seq: Long, val texture: Int, val op: String,
                                      val clips: List<RegionTileRect>? = null)

    /**
     * Stage a look from already-rendered source units. RGBA32F ping/pong avoids rounding after each item.
     * Source textures are borrowed; the caller owns the returned RGBA8 look. No layer/history mutation occurs.
     */
    internal fun composeRecordedTile(items: List<OrderedRecordedUnit>): Int? {
        recordedCompositionCalls++
        if (items.isEmpty()) return null
        val physical = physicalContextEpoch
        fun current() = ready && !forgettingGraphicsContext && physicalContextEpoch == physical
        check(current()) { "Recorded composition needs its current physical context" }
        check(!strokeInProgress && !wcFading) { "Finish the current stroke before composing recorded content" }
        val operators = mapOf("normal" to 0, "erase" to 1, "behind" to 2, "multiply" to 3, "screen" to 4)
        require(items.all { it.seq >= 0 && it.texture != 0 && it.op in operators }) { "This recorded tile has an unsupported source" }
        require(items.zipWithNext().all { (a, b) -> a.seq < b.seq }) { "Recorded sources must retain strict sequence order" }
        // Pool names may become fresh output, but actual sources and earlier staged outputs stay
        // borrowed. Capture before this attempt registers anything, so it cannot protect itself.
        val borrowed = (protectedRecordedTextureHandles() - freeLayerTex.toSet()) +
            ownedNativeTextures(layers) + undo.heldTextures() + recordedPendingTileBytes.keys +
            recordedWorkingTiles.snapshot(physical).keys + items.map { it.texture } +
            (publishedPaint?.recorded?.planes ?: recordedScene?.planes).orEmpty().values.flatMap { cel ->
                cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } }
            }
        val program = recordedComposeProg ?: GlProgram(shaders.source("jb_tile.vert"),
            shaders.source("jb_recorded_compose.frag"), "recorded composition").also {
                check(current()) { "Recorded composition context changed during program preparation" }
                recordedComposeProg = it
            }
        // glGen writes directly into primitive rollback ledgers before storage can fail.
        val scratch = IntArray(2)
        val outputName = IntArray(1)
        val deleteName = IntArray(1)
        var succeeded = false
        var firstFailure: Throwable? = null
        fun allocate(texture: Int, internal: Int, type: Int, filter: Int) {
            check(current()) { "Recorded composition context changed during allocation" }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, size, size, 0, GLES30.GL_RGBA, type, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Recorded composition texture allocation failed" }
        }
        fun attachChecked(texture: Int) {
            check(current()) { "Recorded composition lost its physical context" }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            attach(texture)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "This GPU cannot render recorded content at the required precision"
            }
        }
        fun draw(destination: Int, source: Int, target: Int, op: Int, clips: List<RegionTileRect>?) {
            check(target != destination && target != source) { "Recorded composition cannot sample its render target" }
            attachChecked(target)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, destination)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, source)
            GLES30.glUniform1i(program.loc("u_operator"), op)
            if (clips == null) {
                GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            } else {
                GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
                for (rect in clips) {
                    GLES30.glScissor(rect.x, rect.y, rect.w, rect.h)
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                }
            }
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Recorded composition failed" }
        }
        try {
            check(current())
            // Engine scratch contract: establish upload state; arbitrary caller restoration is
            // not promised by this kernel. Image storage must never read a caller's unpack PBO.
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, 0)
            GLES30.glViewport(0, 0, size, size)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glGenTextures(scratch.size, scratch, 0)
            check(scratch.all { it != 0 && it !in borrowed } && scratch[0] != scratch[1]) {
                "Recorded composition scratch aliases a borrowed owner"
            }
            for (texture in scratch) {
                // Float filtering is optional on ES; every lookup here addresses one exact texel.
                allocate(texture, GLES30.GL_RGBA32F, GLES30.GL_FLOAT, GLES30.GL_NEAREST)
                attachChecked(texture)
                GLES30.glClearColor(0f, 0f, 0f, 0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            }
            program.use()
            GLES30.glUniform1i(program.loc("u_destination"), 0)
            GLES30.glUniform1i(program.loc("u_source"), 1)
            GLES30.glUniform1f(program.loc("u_tileSize"), size.toFloat())
            GLES30.glUniform2f(program.loc("u_tileOrigin"), 0f, 0f)
            GLES30.glUniformMatrix3fv(program.loc("u_docToClip"), 1, false, tileToClip(0f, 0f), 0)
            GLES30.glBindVertexArray(tileVao)
            var front = scratch[0]
            var back = scratch[1]
            for (item in items) {
                if (item.clips != null) draw(clearTex, front, back, 0, null)
                draw(front, item.texture, back, operators.getValue(item.op), item.clips)
                val previous = front; front = back; back = previous
            }
            val pooled = freeLayerTex.removeLastOrNull()
            if (pooled != null) outputName[0] = pooled else GLES30.glGenTextures(1, outputName, 0)
            val output = outputName[0]
            check(output != 0 && output !in borrowed && output !in scratch && output !in floatNames) {
                "Recorded composition output aliases a borrowed owner or scratch"
            }
            if (pooled == null) allocate(output, GLES30.GL_RGBA8, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_LINEAR)
            draw(clearTex, front, output, 0, null)
            check(current()) { "Recorded composition context changed before handoff" }
            recordedWorkingTiles.register(physical, listOf(output), size.toLong() * size * 4)
            succeeded = true
            return output
        } catch (failure: Throwable) {
            firstFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                if (!current()) return
                try { action() } catch (failure: Throwable) {
                    val original = firstFailure ?: cleanupFailure
                    if (original == null) cleanupFailure = failure
                    else if (failure !== original) try { original.addSuppressed(failure) } catch (_: Throwable) { }
                }
            }
            for (index in scratch.indices) {
                val texture = scratch[index]
                if (texture != 0 && texture !in borrowed && (index == 0 || texture != scratch[0])) cleanup {
                    deleteName[0] = texture
                    GLES30.glDeleteTextures(1, deleteName, 0)
                }
            }
            cleanup { GLES30.glDisable(GLES30.GL_SCISSOR_TEST) }
            cleanup { GLES30.glBindVertexArray(0) }
            cleanup { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0) }
            if (succeeded && !current() && cleanupFailure == null)
                cleanupFailure = IllegalStateException("Recorded composition context changed during final cleanup")
            val output = outputName[0]
            if ((!succeeded || cleanupFailure != null) && output != 0 && output !in borrowed && output !in scratch) cleanup {
                deleteName[0] = output
                GLES30.glDeleteTextures(1, deleteName, 0)
                recordedWorkingTiles.forget(physical, output)
            }
            if (firstFailure == null) cleanupFailure?.let { throw it }
        }
    }

    /** Starts a stroke. [argb] is the brush colour (alpha ignored — [opacity] is the stroke's opacity). */
    fun beginStroke(layerId: String, argb: Int, opacity: Float, accumulate: Accumulate,
                    blend: StrokeBlend, tip: TipShape,
                    grain: GrainMath.StrokeGrain = GrainMath.StrokeGrain(GrainMath.GrainUniforms.OFF, GrainMath.GrainUniforms.OFF),
                    smudge: SmudgeParams? = null, tuft: TuftShading? = null,
                    watercolour: WatercolourSpec? = null, watercolourSeed: Long = 0L) {
        requireLocalPaint()
        beginStrokeBuffer(layerId, argb, opacity, accumulate, blend, tip, grain, smudge, tuft, watercolour, watercolourSeed)
    }

    /** Scratch replay shares dab setup, but cannot enter any public pixel/history commit route. */
    private fun beginStrokeBuffer(layerId: String, argb: Int, opacity: Float, accumulate: Accumulate,
                                  blend: StrokeBlend, tip: TipShape,
                                  grain: GrainMath.StrokeGrain,
                                  smudge: SmudgeParams? = null, tuft: TuftShading? = null,
                                  watercolour: WatercolourSpec? = null, watercolourSeed: Long = 0L) {
        check(!resettingJointPaint && !forgettingGraphicsContext) { "Finish graphics retirement before preparing stroke buffers" }
        settleWatercolourFade()
        cancelStroke()
        tilePreviewRect=null
        require(smudge == null || tileBoardId == null) { "Turn tiling off to use smudge" }
        require(watercolour == null || tileBoardId == null) { "Turn tiling off to use watercolor" }
        if (framePreviews.isNotEmpty()) setFramePreviews(emptyMap())
        strokeTravel.reset()
        strokeLayer = storeOf(layerId) ?: error("no layer $layerId")
        strokePlan = strokeLayer?.plan
        strokeTileRect=tileBoardId?.let { TilePainting.board(requireNotNull(boardDocument),it).rect }
        strokeIsRgba = smudge != null || watercolour != null
        wcSpec = watercolour; wcPrepared = false; wcFading = false
        colR = ((argb shr 16) and 0xFF) / 255f
        colG = ((argb shr 8) and 0xFF) / 255f
        colB = (argb and 0xFF) / 255f
        this.opacity = opacity.coerceIn(0f, 1f)
        this.accumulate = accumulate
        this.blend = blend
        // A smudge reads the layer as it stands NOW (pen-down): the stroke buffer is not the layer until pen-up, so the
        // pixels it picks up from stay put for the whole stroke. Colours are decided on the GL thread, where a tile can be read.
        smudgeTexturePickup = smudge?.texturePickup?.coerceIn(0f, 1f) ?: 0f
        smudgePaint = smudge?.paint ?: false
        this.smudge = if (smudge == null) null else SmudgeStroke(
            TileReader { tx, ty -> readTile(layerId, Tiles.key(tx, ty)) },
            SmudgeCarried(colR, colG, colB, 1f, smudge.pickup.coerceIn(0f, 1f), smudge.load.coerceIn(0f, 1f)),
            tip,
        )
        // The stroke's look (tip, grain pictures, paper), resolved once per stroke: StampPass.lookFor.
        val p = loadedPaper?.paper
        val surface = p?.surface
        look = stamp.lookFor(tip, grain, p, tuft?.paperResponse, tuft?.paperPitchPx, strokeTileRect, fluidTexture)
        val tooth = if (tuft == null) null else if (p == null) grains.textureFor(tuft.paperAsset) else surfaceTexture
        tuftPaperTex = tooth ?: grains.placeholder
        this.tuft = tuft?.let {
            if(tooth == null) it.copy(paperPitchPx=0f) else if(p != null && surface != null)
                it.copy(paperAsset=surface.file,paperPitchPx=if(look.paperInfluence>0f)surface.texelPx*p.scale else 0f,tooth=it.tooth)
            else it
        }
        if (watercolour != null) {
            this.accumulate = Accumulate.BUILD_UP
            val frozenLook = look
            val frozenSurface = surfaceTexture ?: grains.placeholder
            val pass = wcPass ?: WatercolourPass(shaders, size).also { wcPass = it }
            try {
                pass.begin(watercolour, watercolourSeed, floatArrayOf(colR,colG,colB), surfaceTexture != null && surface != null) { program ->
                    stamp.setPaperSurfaceUniforms(GlUniformSink,program::loc,frozenLook)
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,frozenSurface)
                }
            } catch (e: Throwable) { cancelStroke(); throw e }
        }
    }

    /** The cap every dab of the active stroke should carry (see Accumulate). */
    fun capForStroke(): Float = if (accumulate == Accumulate.WASH) opacity else 1f

    /** Renders dabs into the stroke buffer. */
    fun addDabs(dabs: List<Dab>) {
        if (strokeLayer == null || dabs.isEmpty()) return
        if (wcSpec != null) { addWatercolourDabs(dabs); return }
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
        stamp.setStrokeUniforms(GlUniformSink, dabProg::loc, look, dabs[dabs.size - 1], size, 1f)
        GLES30.glBindVertexArray(dabVao)

        for ((key, list) in buckets) {
            val tex = strokeTiles.getValue(key)
            attach(tex)
            clipTileStroke(key)
            stamp.drawInto(GlUniformSink, dabProg::loc, look, instanceVbo, (Tiles.tx(key) * size).toFloat(), (Tiles.ty(key) * size).toFloat(), list)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** The new engine owns only its band/finish targets; existing dab packing and draws stay untouched. */
    private fun addWatercolourDabs(dabs: List<Dab>) {
        val tile = strokeTileRect
        val wrapped = if(tile == null) dabs else dabs.flatMap { TilePainting.dabs(tile,it) }
        val buckets = Tiles.bucket(wrapped,size).filterKeys { tile == null || TilePainting.clip(tile,it) != null }
        require(tile == null || (strokeTiles.keys+buckets.keys).toSet().size <= TilePainting.MAX_TILES) { "This tile stroke is too large" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo)
        for(key in buckets.keys) strokeTiles.getOrPut(key) { newStrokeTile() }
        val pass = requireNotNull(wcPass)
        pass.add(buckets) { key -> tile?.let { TilePainting.clip(it,key) } }
        pass.finishInto(strokeTiles,0f,buckets.keys)
    }

    /** Cosmetic cached-image fade. Return true only when its final commit changes undo/history. */
    fun watercolourFrame(nowMs: Long): Boolean {
        val options = wcSpec ?: return false
        if(!wcFading) return false
        val t=((nowMs-wcFadeStart).toFloat()/options.poolingFadeMilliseconds.coerceAtLeast(1)).coerceIn(0f,1f)
        wcPass?.fadeInto(strokeTiles,t*t*(3f-2f*t))
        if(t < 1f) return false
        wcFading=false
        return endStroke()>0
    }
    /** Finish immediately for replay/preview/history operations; physical pen-up still animates. */
    fun settleWatercolourFade(): Boolean {
        if(!wcFading) return false
        wcPass?.fadeInto(strokeTiles,1f)
        wcFading=false
        return endStroke()>0
    }
    /** A cosmetic tail cannot block the next board/layer transaction or reverse its Undo order. */
    private fun checkStrokeEnded(why: String) {
        settleWatercolourFade()
        check(!strokeInProgress) { why }
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
        stamp.setPaperSurfaceUniforms(GlUniformSink, tuftProg::loc, look)
        stamp.setPaperResponseUniforms(GlUniformSink, tuftProg::loc, look)
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
        GLES30.glUniform1f(smudgeProg.loc("u_aspect"), look.tip.aspect)
        GLES30.glUniform1f(smudgeProg.loc("u_corner"), look.tip.corner)
        GLES30.glUniform1f(smudgeProg.loc("u_taper"), look.tip.taper)
        GLES30.glUniform1f(smudgeProg.loc("u_hardness"), look.tip.hardness)
        GLES30.glUniform1f(smudgeProg.loc("u_minPx"), look.tip.minPx)
        stamp.setGrainUniforms(GlUniformSink, smudgeProg::loc, look, dabs.last())
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
                stamp.putContact(smudgeInstanceData, d, look)
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

    /** Commits the active stroke into its layer as one undoable step. Returns tiles changed. */
    fun endStroke(): Int {
        requireLocalPaint()
        val layer = strokeLayer ?: return 0
        val options = wcSpec
        if(options != null && !wcPrepared) {
            wcPrepared=true
            if(options.poolingFadeMilliseconds>0 && strokeTiles.isNotEmpty() &&
                (options.edgePool>0.0 || options.edgeAlpha>0.0 || options.gravityPool>0.0)) {
                wcPass?.prepareFade(strokeTiles)
                wcFadeStart=SystemClock.uptimeMillis(); wcFading=true
                return 0
            }
            wcPass?.finishInto(strokeTiles,1f)
        }
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
        wcPass?.clearStroke(); wcSpec=null; wcPrepared=false; wcFading=false
        strokeLayer = null
        strokePlan = null
        strokeTileRect = null
        smudge = null
        tuft = null
        if (changes.isNotEmpty()) {
            val afterDoc=if(wrappedId != null && beforeDoc != null) BoardDocumentOps.markWrappedStroke(beforeDoc,wrappedId) else beforeDoc
            if(afterDoc != beforeDoc && afterDoc != null) setBoardDocument(afterDoc)
            undo.push(UndoLog.Step(changes + settlePlainWrites(changes),documentBefore=beforeDoc?.takeIf { it != afterDoc },documentAfter=afterDoc?.takeIf { it != beforeDoc }))
        }
        return changes.size
    }

    fun cancelStroke() {
        wcPass?.clearStroke(); wcSpec=null; wcPrepared=false; wcFading=false
        releaseStrokeTiles()
        strokeLayer = null
        strokePlan = null
        strokeTileRect = null
        smudge = null
        tuft = null
    }

    fun undoStep(): Boolean {
        if (publishedPaint != null) {
            val step = undo.peekUndo() ?: return false
            return publishJointPaintStep(step, RecordedScene.Direction.UNDO)
        }
        settleWatercolourFade()
        val pending = undo.peekUndo() ?: return false
        if (pending.lines.isNotEmpty() || pending.slabs.isNotEmpty()) return recordedHistory(pending, false)
        check(recordedScene?.hasContent != true) { "Recorded artwork needs the joint pixel/media/structural history publisher" }
        val s = undo.undo() ?: return false
        val restored = s.documentBefore?.let { target ->
            val opposite = s.documentAfter
            val live = boardDocument
            if (opposite != null && live != null) BoardHistory.restore(target, opposite, live) else target
        }
        if (framePreviews.isNotEmpty()) setFramePreviews(emptyMap())
        s.changes.forEach { put(it.layerId, it.key, it.before, it.celId) }
        reconcileAgreements(s.changes)
        reportMediaRestored(s.changes)
        s.stackBefore?.let { restoreStack(it) }
        s.paperBefore?.let { setDocumentPaper(it) }
        restored?.let { setBoardDocument(it) }
        return true
    }

    fun redoStep(): Boolean {
        if (publishedPaint != null) {
            val step = undo.peekRedo() ?: return false
            return publishJointPaintStep(step, RecordedScene.Direction.REDO)
        }
        settleWatercolourFade()
        val pending = undo.peekRedo() ?: return false
        if (pending.lines.isNotEmpty() || pending.slabs.isNotEmpty()) return recordedHistory(pending, true)
        check(recordedScene?.hasContent != true) { "Recorded artwork needs the joint pixel/media/structural history publisher" }
        val s = undo.redo() ?: return false
        val restored = s.documentAfter?.let { target ->
            val opposite = s.documentBefore
            val live = boardDocument
            if (opposite != null && live != null) BoardHistory.restore(target, opposite, live) else target
        }
        if (framePreviews.isNotEmpty()) setFramePreviews(emptyMap())
        s.changes.forEach { put(it.layerId, it.key, it.after, it.celId) }
        reconcileAgreements(s.changes)
        reportMediaRestored(s.changes)
        s.stackAfter?.let { restoreStack(it) }
        s.paperAfter?.let { setDocumentPaper(it) }
        restored?.let { setBoardDocument(it) }
        return true
    }

    /** Actual engine history route: render/validate before moving its one shared undo stack. */
    private fun recordedHistory(step: UndoLog.Step<Int>, forward: Boolean): Boolean {
        val scene = checkNotNull(recordedScene) { "Recorded history has no physical source scene" }
        val edit = RecordedEdits.prepare(scene.planes, step, forward)
        return scene.stage(edit, if (forward) RecordedScene.Direction.REDO else RecordedScene.Direction.UNDO)
            .use { it.commit() }
    }

    /** Opt-in engine connection. The view/loader must not activate it before their own gates pass. */
    internal fun bindRecordedPaintPublication() {
        requireLocalPaint()
        check(ready && !strokeInProgress && !wcFading && !mediaStrokeInProgress) {
            "Finish paint and media before binding recorded artwork"
        }
        val scene = checkNotNull(recordedScene) { "A joint publication needs its recorded source scene" }
        check(!scene.isStateBound && !undo.isCursorBound)
        scene.checkStateBindingAllowed()
        undo.checkCursorBindingAllowed()
        scene.checkBindingContext()
        undo.checkCursorBindingAllowed()
        val sourceState = scene.snapshotState()
        validateRecordedPhysicalDisplay(sourceState)
        require(sourceState.planes.keys.all { (owner, cel) ->
            layers[owner]?.let { layer ->
                val pixels = if (cel == null) layer.tiles else layer.cels[cel]
                val payload = if (cel == null) layer.floats else layer.celFloats[cel]
                pixels != null && pixels.isEmpty() && payload.orEmpty().values.all { it.isEmpty() }
            } == true
        }) { "Plain pixels and media on recorded planes must transfer to physical sources before binding" }
        val slabs = sourceState.planes.values.flatMap { cel ->
            cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } }
        }
        val alreadyOwned = activeTextureOwners()
        require(slabs.distinct().size == slabs.size && slabs.none { it == 0 || it in alreadyOwned }) {
            "Recorded physical slabs need distinct caller-owned sources before engine adoption"
        }
        val root = PublishedPaintState(LinkedHashMap(layers), HashMap(agreedLook), boardDocument, documentPaper,
            sourceState, undo.snapshotCursor(), graphicsEpoch, persistentNativeTextures(layers), scene)
        val stateRead: () -> RecordedScene.Publication<Int> = { checkNotNull(publishedPaint) }
        val cursorRead: () -> UndoLog.Cursor<Int> = { checkNotNull(publishedPaint).cursor }
        // These accessors are engine-owned readonly field lookups. All identities were prepared
        // before binding, so there is no external callback or fallible native work between them.
        publishedPaint = root
        scene.bindState(stateRead)
        undo.bindCursor(cursorRead)
    }

    private fun persistentNativeTextures(source: Map<String, Layer>): Set<Int> = buildSet {
        fun include(layer: Layer) {
            addAll(layer.tiles.values)
            layer.cels.values.forEach { addAll(it.values) }
            layer.planes().forEach { (_, stores) -> stores.values.forEach { addAll(it.values) } }
            layer.mask?.let(::include)
        }
        source.values.forEach(::include)
        remove(0)
        // Free pools, floating-name registries and disposable viewport/projection caches are
        // not persistent owners. Free RGBA8 names can legitimately become fresh renderer output.
    }

    /** One empty artwork publication, followed by handoff of those exact empty values. */
    private fun resetJointPaint(mode: RecordedScene.ResetMode) = resetJointPaint(mode,false)

    private fun resetJointPaint(mode: RecordedScene.ResetMode, failAfterRetirement: Boolean) {
        val expected = publishedPaint ?: return
        check(mode == RecordedScene.ResetMode.CONTEXT_LOST || !resettingJointPaint) {
            "Finish joint retirement before another live reset"
        }
        val scene = checkNotNull(recordedScene)
        val alreadyResetting = resettingJointPaint
        resettingJointPaint = true
        try {
            val physical = physicalContextEpoch
            val physicalAlive = { physicalContextEpoch == physical && !forgettingGraphicsContext }
            val next = PublishedPaintState(LinkedHashMap(), HashMap(), null, expected.paper,
                scene.emptyState(), undo.emptyCursor(), Math.addExact(expected.graphicsEpoch, 1L), emptySet(), scene)
            val ticket = scene.prepareResetHandoff(expected, next, mode)
            val physicalSources = ownedNativeTextures(expected.nativeLayers) + expected.recorded.planes.values.flatMap { cel ->
                cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } }
            }
            val retiredSources = (physicalSources + ticket.retiredHistoryTextures).toList()
            check(ticket.isCurrent() && publishedPaint === expected && physicalContextEpoch == physical) {
                "Artwork or graphics context changed before joint reset"
            }
            publishedPaint = next // Only artwork publication; every source and history slice is empty together.
            ticket.acknowledgeAfterResetPublication(next)
            localLayers = next.nativeLayers
            localAgreedLook = next.agreements
            localGraphicsEpoch = next.graphicsEpoch
            localBoardDocument = next.document
            localDocumentPaper = next.paper
            recordedScene = null
            // Storage handoff preserves the exact empty values; it does not publish another edit.
            publishedPaint = null
            if(failAfterRetirement) throw ColdInjectedRetirementFailure()
            if (mode == RecordedScene.ResetMode.SAME_CONTEXT) {
                ticket.releaseRetiredCachesAfterPublication(physicalAlive)
                for (texture in retiredSources) {
                    if (!physicalAlive()) break
                    recycleLayerTex(texture)
                }
            }
            // CONTEXT_LOST abandons all old current/history/pending names; no old GL deletion.
        } finally {
            resettingJointPaint = alreadyResetting
        }
    }

    /** Current adoption inventory: a format registry alone does not own a newly staged float tile. */
    private fun activeTextureOwners(): Set<Int> {
        val ownedFloats = persistentNativeTextures(layers) + undo.heldTextures() + freeFloatTex +
            mediaStroke.orEmpty().flatMap { listOfNotNull(it.before, it.after) }
        return protectedRecordedTextureHandles() - (floatNames - ownedFloats)
    }

    /**
     * Resident mutable tile allocations only. Screen targets, paper/grain images, mipmaps,
     * buffers and programs are separate fixed resources, not a whole-engine memory claim.
     * A format registry alone never makes an otherwise unowned name resident.
     */
    internal fun recordedWorkingTileBytes(
        sourcePlanes: Map<Pair<String, String?>, RecordedCel<Int>> = emptyMap(),
    ): Map<Int, Long> {
        val result = LinkedHashMap<Int, Long>()
        val area = Math.multiplyExact(size.toLong(), size.toLong())
        fun include(names: Collection<Int>, bytesPerTexel: Long) {
            val bytes = Math.multiplyExact(area, bytesPerTexel)
            for (name in names) if (name != 0) result[name] = maxOf(result[name] ?: 0L, bytes)
        }
        fun rgba(names: Collection<Int>) {
            include(names.filter { it !in floatNames }, 4L)
            include(names.filter { it in floatNames }, MediaStores.BYTES_PER_TEXEL.toLong())
        }
        rgba(ownedNativeTextures(layers))
        layers.values.forEach { layer ->
            rgba(layer.visibleProjection?.values.orEmpty())
            layer.mask?.let { rgba(it.visibleProjection?.values.orEmpty()) }
        }
        rgba(undo.heldTextures())
        rgba(mediaStroke.orEmpty().flatMap { listOfNotNull(it.before, it.after) })
        include(agreedLook.values, 4L)
        include(recordedScene?.heldCacheTextures().orEmpty(), 4L)
        include((publishedPaint?.recorded?.planes ?: recordedScene?.planes ?: sourcePlanes).values.flatMap { cel ->
            cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } }
        }, 4L)
        include(freeLayerTex, 4L)
        include(freeFloatTex, MediaStores.BYTES_PER_TEXEL.toLong())
        val strokeBytes = if (strokeInternal == GLES30.GL_R16F) 2L else 1L
        val rgbaStrokeBytes = if (smudgeInternal == GLES30.GL_RGBA16F) 8L else 4L
        include(freeStrokeTex, strokeBytes)
        include(freeSmudgeTex, rgbaStrokeBytes)
        include(strokeTiles.values, if (strokeIsRgba) rgbaStrokeBytes else strokeBytes)
        wcPass?.heldWorkingTileBytes()?.forEach { (name, bytes) ->
            result[name] = maxOf(result[name] ?: 0L, bytes)
        }
        recordedPendingTileBytes.forEach { (name, bytes) ->
            result[name] = maxOf(result[name] ?: 0L, bytes)
        }
        return java.util.Collections.unmodifiableMap(result)
    }

    private fun ownedNativeTextures(source: Map<String, Layer>): Set<Int> = buildSet {
        fun include(owner: Layer) { addAll(owner.ownedTextures()); owner.mask?.let(::include) }
        source.values.forEach(::include)
        remove(0)
    }

    private fun detachedNativeLayer(source: Layer, recordedCels: Set<String?>, emptyRecordedStores: Boolean = false): Layer =
        Layer(source.id, source.isMask, if (null in recordedCels && !emptyRecordedStores) source.tiles
            else if (null in recordedCels) HashMap() else HashMap(source.tiles),
            source.ownershipIdentity).also { copy ->
        source.cels.forEach { (id, tiles) ->
            copy.cels[id] = if (tiles === source.tiles) copy.tiles
                else if (id in recordedCels && !emptyRecordedStores) tiles
                else if (id in recordedCels) HashMap() else HashMap(tiles)
        }
        copy.sharedCel = source.sharedCel
        copy.plan = source.plan
        source.floats.forEach { (store, tiles) -> copy.floats[store] = HashMap(tiles) }
        source.celFloats.forEach { (cel, stores) ->
            copy.celFloats[cel] = HashMap<String, HashMap<Long, Int>>().also { detached ->
                stores.forEach { (store, tiles) -> detached[store] = HashMap(tiles) }
            }
        }
        copy.mask = source.mask
        copy.clip = source.clip; copy.name = source.name; copy.opacity = source.opacity
        copy.visible = source.visible; copy.blend = source.blend
        // Disposable projections are rebuilt against these new physical stores after publication.
    }

    /** Native paint history uses both null and the document shared ID for the same real map. */
    private fun nativeHistoryCel(layer: Layer, cel: String?): String? {
        if (cel == null) return null
        require(cel in layer.cels) { "Native history names a missing physical cel" }
        if (cel == layer.sharedCel) {
            require(layer.cels[cel] === layer.tiles) { "The native shared alias lost its physical store" }
            return null
        }
        require(layer.cels[cel] !== layer.tiles) { "An unrelated cel aliases the shared native store" }
        return cel
    }

    private fun nativeStepOwner(change: UndoLog.TileChange<Int>, root: PublishedPaintState,
                                allowLegacySharedAlias: Boolean): Layer {
        val ownerId = MediaStores.parse(change.layerId)?.first ?: change.layerId
        require(!ownerId.endsWith(MASK_SUFFIX)) { "Recorded clipping masks need the joint clipping adapter" }
        val layer = root.nativeLayers[ownerId] ?: error("No native owner $ownerId")
        require(change.celId == null || change.celId != layer.sharedCel || allowLegacySharedAlias) {
            "New native writes must use their canonical physical address"
        }
        val cel = nativeHistoryCel(layer, change.celId)
        require((ownerId to cel) !in root.recorded.planes) {
            "Native writes on recorded planes need the physical slab adapter"
        }
        require(change.before != change.after) { "A native no-op cannot publish artwork history" }
        require(change.before != 0 && change.after != 0) { "Native source textures must be nonzero" }
        return layer
    }

    private fun nativeStepTiles(layer: Layer, change: UndoLog.TileChange<Int>): Map<Long, Int> {
        val cel = nativeHistoryCel(layer, change.celId)
        return MediaStores.parse(change.layerId)?.let { (_, store) ->
            (if (cel == null) layer.floats else layer.celFloats[cel])?.get(store).orEmpty()
        } ?: (if (cel == null) layer.tiles else layer.cels.getValue(cel))
    }

    /** Complete native projection is validated and copied before any source replay or publication. */
    internal fun publishJointPaintStep(step: UndoLog.Step<Int>,
                                       direction: RecordedScene.Direction = RecordedScene.Direction.NEW,
                                       frozen: Map<String, BrushPreset>? = null): Boolean {
        require(step.stackBefore == null && step.stackAfter == null && step.paperBefore == null &&
            step.paperAfter == null && step.documentBefore == null && step.documentAfter == null) {
            "Structural and paper changes need their joint renderer adapter"
        }
        check(!resettingJointPaint) { "Cannot publish paint during joint retirement" }
        val expected = checkNotNull(publishedPaint) { "Joint artwork is not bound" }
        val scene = checkNotNull(recordedScene)
        check(!strokeInProgress && !wcFading && !mediaStrokeInProgress && framePreviews.isEmpty() && tileBoardId == null) {
            "A joint edit requires settled stable paint context"
        }
        require(step.slabs.all { (it.layerId to it.celId) in expected.recorded.planes }) {
            "Native slab changes need a physical slab owner adapter"
        }
        val projection = expected.baseTransferProof?.let {
            PromotedBaseProjection.prepare(expected.recorded.planes, step, direction, expected.cursor,
                expected.legacyBaseSteps, expected.promotedBases, expected.promotedSharedCels)
        }
        val nativeChanges = step.changes.filter { change -> projection?.bridgedChanges?.none { it === change } != false }
        val nextLayers = LinkedHashMap(expected.nativeLayers)
        val changedOwners = LinkedHashSet<String>()
        val targets = HashSet<Triple<String, String?, Long>>()
        val retiredProjection = LinkedHashSet<Int>()
        val currentOwners = activeTextureOwners() + expected.recorded.planes.values.flatMap { cel ->
            cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } }
        }
        if (direction == RecordedScene.Direction.NEW) require(step.slabs.all { change ->
            change.after?.texture?.let { it != 0 && it !in currentOwners } ?: true
        }) { "A new physical slab must be a fresh caller-owned source" }
        fun detachOwner(owner: Layer): Layer = if (changedOwners.add(owner.id)) {
            retiredProjection.addAll(owner.projected.values)
            detachedNativeLayer(owner, expected.recorded.planes.keys.filter { it.first == owner.id }
                .map { it.second }.toSet()).also { nextLayers[owner.id] = it }
        } else nextLayers.getValue(owner.id)
        // Raw/slab/base edits invalidate derived region projections, preserving physical placeholders.
        val recordedOwners = (step.lines.map { it.layerId } + step.slabs.map { it.layerId } +
            projection?.bridgedChanges.orEmpty().map { it.layerId }).toSet()
        recordedOwners.forEach { detachOwner(expected.nativeLayers[it] ?: error("Missing recorded paint owner")) }
        val allowLegacySharedAlias = projection != null && direction != RecordedScene.Direction.NEW &&
            expected.legacyBaseSteps.any { it === step }
        for (change in nativeChanges) {
            val owner = nativeStepOwner(change, expected, allowLegacySharedAlias)
            val cel = nativeHistoryCel(owner, change.celId)
            require(targets.add(Triple(change.layerId, cel, change.key))) {
                "A joint native batch cannot repeat a physical target"
            }
            val before = if (direction == RecordedScene.Direction.UNDO) change.after else change.before
            require(nativeStepTiles(owner, change)[change.key] == before) {
                "Native history no longer matches its physical source endpoint"
            }
            if (direction == RecordedScene.Direction.NEW) {
                require(change.after == null || change.after !in currentOwners) {
                    "A new native endpoint must be a fresh caller-owned source"
                }
            }
            val target = detachOwner(owner)
            val store = MediaStores.parse(change.layerId)?.let { (_, name) -> payload(target, name, cel) }
                ?: planeTiles(target, cel)
            val after = if (direction == RecordedScene.Direction.UNDO) change.before else change.after
            if (after == null) store.remove(change.key) else store[change.key] = after
        }
        val nextAgreements = HashMap(expected.agreements)
        val payloadTargets = nativeChanges.mapNotNull { change ->
            MediaStores.parse(change.layerId)?.let { Triple(it.first,
                nativeHistoryCel(expected.nativeLayers.getValue(it.first), change.celId), change.key) }
        }.toSet()
        for (change in nativeChanges) {
            val ownerId = MediaStores.parse(change.layerId)?.first ?: change.layerId
            val target = nextLayers.getValue(ownerId)
            val cel = nativeHistoryCel(target, change.celId)
            val at = Triple(ownerId, cel, change.key)
            val stores = if (cel == null) target.floats else target.celFloats[cel]
            if (stores?.values?.any { change.key in it } == true && at in payloadTargets) {
                nextAgreements[at] = (if (cel == null) target.tiles else target.cels[cel])?.get(change.key) ?: NO_LOOK
            } else nextAgreements.remove(at)
        }
        val protected = persistentNativeTextures(nextLayers)
        val residentMedia = nextLayers.values.sumOf { layer -> layer.planes().sumOf { (_, stores) ->
            stores.entries.sumOf { (name, tiles) -> tiles.size.toLong() * MediaStores.tileBytes(size, name) }
        } }
        if (direction == RecordedScene.Direction.NEW && residentMedia > mediaBudgetBytes)
            throw MediaRoomException("There is no room for these media tiles")
        val previousPending = recordedPendingTileBytes
        val pendingPhysical = physicalContextEpoch
        val pending = LinkedHashMap(previousPending)
        fun pendingEndpoint(texture: Int?, bytesPerTexel: Long) {
            if (texture == null) return
            require(texture != 0) { "A pending paint endpoint must be a real texture" }
            val bytes = Math.multiplyExact(Math.multiplyExact(size.toLong(), size.toLong()), bytesPerTexel)
            pending[texture] = maxOf(pending[texture] ?: 0L, bytes)
        }
        step.changes.forEach { change ->
            val store = MediaStores.parse(change.layerId)?.second
            val bytes = if (store != null && store != MediaStores.GROUND) MediaStores.BYTES_PER_TEXEL.toLong() else 4L
            pendingEndpoint(change.before, bytes)
            pendingEndpoint(change.after, bytes)
        }
        step.slabs.forEach { change ->
            pendingEndpoint(change.before?.texture, 4L)
            pendingEndpoint(change.after?.texture, 4L)
        }
        recordedPendingTileBytes = pending
        try {
        // Native-only steps also need admission; they can invoke no renderer callbacks.
        recordedWorkingTileBudgetBytes?.let { budget ->
            recordedWorkingTiles.admit(pendingPhysical, recordedWorkingTileBytes(), 0L, budget)
        }
        val staged = if (projection == null) scene.stageJoint(step, direction, frozen)
            else scene.stageJointProjected(expected, step, direction, frozen)
        staged.use { proposal ->
            validateRecordedPhysicalDisplay(proposal.candidateState)
            val remainingSteps = proposal.historyProposal.cursor.undoSteps + proposal.historyProposal.cursor.redoSteps
            val next = PublishedPaintState(nextLayers, nextAgreements, expected.document, expected.paper, proposal.candidateState,
                proposal.historyProposal.cursor, expected.graphicsEpoch, protected, scene, expected.promotedBases,
                expected.legacyBaseSteps.filter { old -> remainingSteps.any { it === old } }.toSet(), expected.baseTransferProof)
            val retiredNative = (retiredProjection + proposal.historyProposal.retirements).toList()
            if (!proposal.canPublish() || publishedPaint !== expected || graphicsEpoch != expected.graphicsEpoch ||
                (direction == RecordedScene.Direction.NEW && residentMedia > mediaBudgetBytes)) return false
            publishedPaint = next // One publication for native sources, records, agreements and the complete cursor.
            proposal.adoptAfterPublication()
            proposal.releaseRetiredAfterPublication()
            for (texture in retiredNative) {
                if (graphicsEpoch != expected.graphicsEpoch) break
                recycleLayerTex(texture)
            }
            return true
        }
        } finally {
            recordedPendingTileBytes = if (physicalContextEpoch == pendingPhysical) previousPending else emptyMap()
        }
    }

    /**
     * Internal GL-thread source snapshot, not the raster View save route. Reads physical native
     * stores/slabs and authoritative line-only looks from ONE actual root; never a viewport
     * projection as paint. Cold unmeasured line indexes refuse without replay or root mutation.
     */
    internal fun snapshotJointRecordedArtwork(): JbContents {
        val expected = checkNotNull(publishedPaint) { "A recorded source snapshot needs the actual bound artwork" }
        val physical = physicalContextEpoch
        val scene = expected.coordinator
        check(ready && !forgettingGraphicsContext && !resettingJointPaint && !preparingPlainPromotion &&
            !strokeInProgress && !wcFading && !mediaStrokeInProgress && framePreviews.isEmpty() && tileBoardId == null) {
            "Finish painting and playback before a recorded source snapshot"
        }
        scene.checkSnapshotPublication(expected)
        require(expected.recorded.planes.all { (at, cel) -> cel.records.isEmpty() ||
            expected.nativeLayers[at.first]?.plan?.frames.isNullOrEmpty() }) {
            "Regional recorded lines need their captured physical clipping contract before a new source save"
        }
        val document = checkNotNull(expected.document) { "Recorded sources need complete document identities before saving" }
        require(DocOps.validate(document).isEmpty() && document.layers.map { it.id }.toSet() == expected.nativeLayers.keys &&
            document.layers.all { it.kind.hasPixels && it.animatedIn == null } &&
            document.brushes.all { it in expected.recorded.brushes }) { "The document does not describe every native paint owner and frozen definition" }
        for (meta in document.layers) {
            val owner = expected.nativeLayers.getValue(meta.id)
            val shared = meta.sharedCelId ?: meta.cels.singleOrNull()?.id
            require(owner.id == meta.id && !owner.isMask && shared != null && owner.sharedCel == shared && owner.cels[shared] === owner.tiles &&
                owner.cels.keys == meta.cels.map { it.id }.toSet() && ((owner.mask != null) == (meta.mask != null)) &&
                owner.celFloats.keys.all { it != shared && it in owner.cels } &&
                owner.planes().all { (_, stores) -> stores.keys.all { it in MediaStores.PAYLOAD } } &&
                owner.mask?.let { mask -> mask.planes().all { (_, stores) -> stores.values.all { it.isEmpty() } } &&
                    mask.cels.values.all { it === mask.tiles || it.isEmpty() } } != false) {
                "Snapshot physical cels or masks do not match document identities"
            }
            for (cel in meta.cels) {
                val at = meta.id to cel.id.takeUnless { it == shared }
                if (at in expected.recorded.planes) {
                    val pixels = if (at.second == null) owner.tiles else owner.cels.getValue(cel.id)
                    val payload = if (at.second == null) owner.floats else owner.celFloats[cel.id]
                    require(pixels.isEmpty() && payload.orEmpty().values.all { it.isEmpty() }) {
                        "Recorded source snapshots cannot omit native paint or media on their planes"
                    }
                }
            }
        }
        val slabNames = expected.recorded.planes.values.flatMap { cel ->
            cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } }
        }
        val forbidden = ownedNativeTextures(expected.nativeLayers) + expected.cursor.heldTextures() +
            expected.recorded.cacheTextures() + freeLayerTex + freeStrokeTex + freeSmudgeTex + freeFloatTex +
            listOfNotNull(clearTex, whiteTex, thumbTex, tuftPaperTex, lookTexture, surfaceTexture, fluidTexture,
                look.tipGrainTex, look.paperGrainTex, look.fluidTexture, compositor.target, compositor.backdrop,
                boardCompositor.target, boardCompositor.backdrop) + grains.heldTextureHandles() +
            boardPreview.heldTextureHandles() + paperBackground.heldTextureHandles() + wcPass?.heldTextureHandles().orEmpty()
        require(slabNames.distinct().size == slabNames.size && slabNames.none { it == 0 || it in floatNames || it in forbidden }) {
            "Snapshot slabs must retain exclusive physical RGBA8 source ownership"
        }
        validateRecordedPhysicalDisplay(expected.recorded)
        val metadata = document.copy(layers = document.layers.map { meta ->
            val owner = expected.nativeLayers.getValue(meta.id)
            meta.copy(name = owner.name, visible = owner.visible, opacity = owner.opacity, blend = owner.blend, clip = owner.clip)
        })
        fun current() {
            check(publishedPaint === expected && physicalContextEpoch == physical && !forgettingGraphicsContext && ready) {
                "Artwork or graphics changed during physical source snapshot"
            }
        }
        fun nativeStore(id: String, cel: String): Map<Long, Int> {
            val meta = document.layers.single { it.id == id }
            val owner = expected.nativeLayers.getValue(id)
            if (meta.mask?.id == cel) return checkNotNull(owner.mask).tiles
            val shared = meta.sharedCelId ?: meta.cels.singleOrNull()?.id
            return if (cel == shared) owner.tiles else owner.cels.getValue(cel)
        }
        fun rgba(texture: Int): ByteArray? {
            current(); require(texture != 0 && texture !in floatNames) { "Physical paint needs its native RGBA8 source" }
            val bytes = readTextureTile(texture); current(); return bytes
        }
        current()
        val snapshot = RecordedSnapshot.capture(metadata, expected.recorded.planes, scene.brushes,
            readTexture = ::rgba,
            keys = { id, cel -> current(); nativeStore(id, cel).keys.toList() },
            read = { id, cel, key -> nativeStore(id, cel)[key]?.let(::rgba) },
            expectedLookKeys = expected.recorded.savedKeys.mapValues { it.value.toList() },
            lookRead = { id, cel, key -> expected.recorded.looks[id to cel]?.get(key)?.let(::rgba) },
            paper = expected.paper,
            mediaKeys = { id, cel ->
                current(); val owner = expected.nativeLayers.getValue(id)
                val stores = if (cel == owner.sharedCel) owner.floats else owner.celFloats[cel].orEmpty()
                stores.mapValues { it.value.keys.toList() }
            },
            mediaRead = { id, cel, store, key ->
                current(); val owner = expected.nativeLayers.getValue(id)
                val bytes = readMediaTile(id, store, key, cel.takeUnless { it == owner.sharedCel })
                current(); bytes
            })
        current(); scene.checkSnapshotPublication(expected); current()
        return snapshot
    }

    /** Undo's half of a stack step: back to [target], every layer that survives keeping how it is shown or hidden. */
    private fun restoreStack(target: LayerStack) {
        applyStack(stack(null).restoring(target))
        activeHint = target.activeId
    }


    // ── media payloads (MEDIA_ENGINE_PLAN §4, M5.3c; R51 and JB-2.40) ────────
    //
    // Pencil, watercolour and oil are a payload on the tiles of an ordinary layer they touched, beside its RGBA8 look:
    // `<id>#p0 #p1 #paper #w0 #w1` ([MediaStores]), RGBA16F tiles from their own pool, and `<id>#g`, the ground (RGBA8,
    // the look's own pool), owned and released exactly like look tiles (UndoLog sizes and recycles each by its kind).
    // Until JB-2.40 slice 3 a payload is per layer, never per cel ([payload] is the one place that changes).
    //
    // Copy-on-write, as endStroke does for paint: [writableMediaTiles] swaps a fresh copy in before the first write to a
    // tile, and the old texture IS the undo snapshot. During a stroke the copies join the stroke's step ([endMediaStroke]);
    // after pen-up (running water) they join whatever media step is on top, or a fresh water step (the Lead's rule).

    /** The look tiles keep the layer's own id; [writableMediaTiles] calls them this. */
    val mediaLook: String get() = MEDIA_LOOK

    /** Called after undo or redo put media tiles back, with each media layer's restored tile keys: stop its simulation and reload. */
    var onMediaRestored: ((layerId: String, plane: String?, keys: Set<Long>) -> Unit)? = null

    /**
     * The ceiling on resident media state (the Lead's rule, [LayerBudget.mediaBudgetBytes]); the app sets it from the phone's
     * memory. A write that would pass it is refused in words ([MediaRoomException]) before anything is swapped.
     */
    var mediaBudgetBytes: Long = Long.MAX_VALUE

    /** Bytes of media payload the layers hold now, every plane (undo snapshots are the undo budget's business). */
    fun mediaResidentBytes(): Long = layers.values.sumOf { l ->
        l.planes().sumOf { (_, p) -> p.entries.sumOf { (store, tiles) -> tiles.size.toLong() * MediaStores.tileBytes(size, store) } }
    }

    /**
     * JB-2.40 slice 3, the PLANE a media write lands on: null for the layer's shared plane (its [Layer.tiles], which is also
     * the shared cel), else the frame cel's id. Undo changes carry a cel id the same way ([UndoLog.TileChange.celId]).
     */
    private fun planeOf(layer: Layer, celId: String?): String? = if (celId == null || celId == layer.sharedCel) null else celId

    /** The plane a document cel of [layerId] is: null for its shared cel, else the cel id (saving and loading address cels). */
    fun mediaPlaneOfCel(layerId: String, celId: String): String? = layers[layerId]?.let { planeOf(it, celId) } ?: celId

    private fun floatsOf(layer: Layer, plane: String?): HashMap<String, HashMap<Long, Int>> =
        if (plane == null) layer.floats else layer.celFloats.getOrPut(plane) { HashMap() }

    /** The look tiles of [plane]: the layer's tiles for the shared plane, the frame cel's own store otherwise. */
    private fun looksOf(layer: Layer, plane: String?): HashMap<Long, Int> = if (plane == null) layer.tiles else layer.cels.getOrPut(plane) { HashMap() }

    /**
     * The plane a media stroke starting at document ([docX], [docY]) on [layerId] paints: the frame cel the layer's board
     * plan puts there (pixel-exact, as a stamp stroke's slices), else the shared plane. A media stroke stays on that one
     * plane, so on an animation board it paints the current frame and stops at the board's edge (R50: a frame is the
     * board's rectangle only).
     */
    fun mediaPlaneAt(layerId: String, docX: Double, docY: Double): String? {
        val layer = layers[layerId] ?: return null
        val plan = layer.plan ?: return null
        if (plan.frames.isEmpty()) return null
        val tx = kotlin.math.floor(docX / size).toInt(); val ty = kotlin.math.floor(docY / size).toInt()
        val lx = (docX - tx * size).toInt().coerceIn(0, size - 1); val ly = (docY - ty * size).toInt().coerceIn(0, size - 1)
        val slice = plan.tileSlices(Tiles.key(tx, ty)).firstOrNull { s -> lx >= s.rect.x && lx < s.rect.x + s.rect.w && ly >= s.rect.y && ly < s.rect.y + s.rect.h }
        return planeOf(layer, slice?.plane?.celId)
    }

    /** The one place a payload store is addressed (the Lead, for JB-5.20's slabs): [layer]'s [store] on [plane], by tile. */
    private fun payload(layer: Layer, store: String, plane: String? = null): HashMap<Long, Int> = floatsOf(layer, plane).getOrPut(store) { HashMap() }

    /** True where [layerId] has a payload on tile [key] of [plane]: some store of it holds that tile. */
    fun hasPayload(layerId: String, key: Long, plane: String? = null): Boolean =
        layers[layerId]?.let { l -> (if (plane == null) l.floats else l.celFloats[plane])?.values?.any { key in it } } == true

    /** A fresh tile for [store]: the ground is RGBA8 from the look's pool, the rest RGBA16F. */
    private fun newPayloadTile(store: String): Int = if (store == MediaStores.GROUND) newLayerTile() else newFloatTile()

    /** Told when the requirement 5 net catches something (the view logs it): a write that bypassed the funnel. */
    var onMediaWarning: ((String) -> Unit)? = null

    /** Called with the tiles a plain write changed (a stroke, a fill, a board edit), by layer: the media window reloads them. */
    var onLookWritten: ((layerId: String, plane: String?, keys: Set<Long>) -> Unit)? = null

    /**
     * JB-2.40 slice 1, the floor of requirement 4: a plain write that changed the look of a tile with a payload drops that
     * tile's whole payload, ground included, in the same undo step, so nothing renders the old paint over the new pixels.
     * Lossy, never wrong: a tile without a payload has its look as its ground, so the next media touch starts from
     * exactly what the write left. Every plain writer reaches here (endStroke, replaceTiles, applyBoardChange); slice 2
     * puts the per-pixel bake in its place. Returns the changes to add to the write's step.
     */
    internal fun settlePlainWrites(changes: Collection<UndoLog.TileChange<Int>>): List<UndoLog.TileChange<Int>> {
        requireLocalPaint()
        val out = ArrayList<UndoLog.TileChange<Int>>()
        val written = LinkedHashMap<Pair<String, String?>, HashSet<Long>>()
        for (c in changes) {
            if (c.before == c.after || MediaStores.isMediaStore(c.layerId)) continue
            val layer = layers[c.layerId] ?: continue   // a mask's store is not a layer, and has no payload
            val plane = planeOf(layer, c.celId)
            written.getOrPut(c.layerId to plane) { HashSet() }.add(c.key)
            for ((store, tiles) in (if (plane == null) layer.floats else layer.celFloats[plane]).orEmpty()) {
                val tex = tiles.remove(c.key) ?: continue
                out.add(UndoLog.TileChange(MediaStores.id(c.layerId, store), c.key, tex, null, plane))
            }
            agreedLook.remove(Triple(c.layerId, plane, c.key))
        }
        // The window holds a copy of these looks as the ground of tiles with no payload, so it hears of every one.
        onLookWritten?.let { listener -> for ((at, keys) in written) listener(at.first, at.second, keys) }
        return out
    }

    // Requirement 5, the net under the funnel: the look texture each payload tile last agreed with, per plane. Media and
    // look written together agree (a media write, a load, a step that carries both); a look that changed any other way did
    // not, and its payload goes at the next window load ([dropStalePayload]) rather than render over the new pixels.
    private var localAgreedLook = HashMap<Triple<String, String?, Long>, Int>()
    private val agreedLook: HashMap<Triple<String, String?, Long>, Int>
        get() = publishedPaint?.agreements ?: localAgreedLook

    private fun agree(layerId: String, key: Long, plane: String? = null) {
        val layer = layers[layerId]
        agreedLook[Triple(layerId, plane, key)] = (if (plane == null) layer?.tiles else layer?.cels?.get(plane))?.get(key) ?: NO_LOOK
    }

    private fun forgetAgreements(layerId: String) { agreedLook.keys.removeAll { it.first == layerId } }

    /** After a load: every payload agrees with the look the file put beside it. */
    fun agreeAllPayloads() {
        requireLocalPaint()
        agreedLook.clear()
        for (l in layers.values) for ((plane, p) in l.planes()) for (key in p.values.flatMap { it.keys }.toSet()) agree(l.id, key, plane)
    }

    /**
     * Undo or redo put [changes] back. A look restored together with its tile's payload agrees with it; a look restored
     * alone over a payload does not (it came from a write that bypassed the funnel), and that payload is stale.
     */
    private fun reconcileAgreements(changes: List<UndoLog.TileChange<Int>>) {
        val withPayload = changes.mapNotNull { c -> MediaStores.parse(c.layerId)?.let { Triple(it.first, c.celId, c.key) } }.toSet()
        for (c in changes) {
            if (MediaStores.isMediaStore(c.layerId)) continue
            val layer = layers[c.layerId] ?: continue
            val plane = planeOf(layer, c.celId)
            val at = Triple(c.layerId, plane, c.key)
            if (hasPayload(c.layerId, c.key, plane) && at in withPayload) agree(c.layerId, c.key, plane) else agreedLook.remove(at)
        }
    }

    /**
     * Requirement 5: of [keys], the tiles of [layerId]'s [plane] whose payload no longer agrees with their look lose the
     * payload (lossy, never wrong; logged, because only a write that bypassed the funnel gets here). Inside a media stroke
     * the drop joins its step; otherwise it is a step of its own. Returns the keys dropped.
     */
    fun dropStalePayload(layerId: String, keys: Collection<Long>, plane: String? = null): Set<Long> {
        requireLocalPaint()
        val layer = layers[layerId] ?: return emptySet()
        val looks = if (plane == null) layer.tiles else layer.cels[plane].orEmpty()
        val stale = keys.filter { k -> hasPayload(layerId, k, plane) && agreedLook[Triple(layerId, plane, k)] != (looks[k] ?: NO_LOOK) }
        if (stale.isEmpty()) return emptySet()
        val changes = ArrayList<UndoLog.TileChange<Int>>()
        for (k in stale) {
            for ((store, tiles) in floatsOf(layer, plane)) tiles.remove(k)?.let { changes.add(UndoLog.TileChange(MediaStores.id(layerId, store), k, it, null, plane)) }
            agreedLook.remove(Triple(layerId, plane, k))
        }
        onMediaWarning?.invoke("dropped the media of ${stale.size} tile(s) of $layerId: their pixels changed outside the media funnel")
        val stroke = mediaStroke
        if (stroke != null) stroke.addAll(changes) else undo.push(UndoLog.Step(changes))
        return stale.toSet()
    }

    private var mediaStroke: ArrayList<UndoLog.TileChange<Int>>? = null
    private val mediaStrokeHeld = HashSet<Triple<String, String?, Long>>()

    val mediaStrokeInProgress: Boolean get() = mediaStroke != null

    fun beginMediaStroke() {
        requireLocalPaint()
        settleWatercolourFade()
        check(mediaStroke == null && !strokeInProgress) { "a media stroke inside another stroke would be undone out of order" }
        mediaStroke = ArrayList()
        mediaStrokeHeld.clear()
    }

    /** Pushes the stroke's step (one press, one step). Returns how many tiles it changed. */
    fun endMediaStroke(): Int {
        requireLocalPaint()
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
    fun writableMediaTiles(layerId: String, keys: Collection<Long>, stores: Collection<String>, plane: String? = null): Map<String, Map<Long, Int>> {
        requireLocalPaint()
        val layer = layers[layerId] ?: error("no layer $layerId")
        val stroke = mediaStroke
        // Outside a stroke, the newest media step's changes by tile: a tile it holds is written in place, and one it
        // dropped (dried water, [dropMediaTiles]) is revived in that same change rather than snapshotted twice.
        val topHeld: Map<Triple<String, String?, Long>, UndoLog.TileChange<Int>> = if (stroke != null) emptyMap() else
            undo.newestExtendable()?.takeIf { MediaStores.isMediaStep(it) }?.changes?.associateBy { Triple(it.layerId, it.celId, it.key) } ?: emptyMap()
        val held: Set<Triple<String, String?, Long>> = stroke?.let { mediaStrokeHeld } ?: topHeld.keys
        val revived = ArrayList<UndoLog.TileChange<Int>>()
        val strokeRevived = HashMap<Int, UndoLog.TileChange<Int>>()
        // Refused before anything is swapped: past the ceiling the layer stops growing, it does not crash.
        val newBytes = stores.filter { it != MEDIA_LOOK }.sumOf { st ->
            keys.count { floatsOf(layer, plane)[st]?.containsKey(it) != true }.toLong() * MediaStores.tileBytes(size, st)
        }
        if (newBytes > 0 && mediaResidentBytes() + newBytes > mediaBudgetBytes) {
            throw MediaRoomException(LayerBudget.mediaFullMessage(mediaBudgetBytes))
        }
        val fresh = ArrayList<UndoLog.TileChange<Int>>()
        val out = LinkedHashMap<String, Map<Long, Int>>()
        try {
            for (store in stores) {
                val look = store == MEDIA_LOOK
                require(look || store in MediaStores.PAYLOAD) { "unknown media store $store" }
                val storeId = if (look) layerId else MediaStores.id(layerId, store)
                val tiles = if (look) looksOf(layer, plane) else payload(layer, store, plane)
                val texes = LinkedHashMap<Long, Int>()
                for (key in keys) {
                    val now = tiles[key]
                    val tile = Triple(storeId, plane, key)
                    if (now != null && tile in held) { texes[key] = now; continue }
                    val after = if (look) newLayerTile() else newPayloadTile(store)
                    initializeTile(after, now, false)
                    val dropped = topHeld[tile]
                    // A tile this stroke itself dropped (a stale payload, [dropStalePayload]) comes back in that same change.
                    val droppedHere = if (now == null && stroke != null) stroke.indexOfFirst { it.after == null && Triple(it.layerId, it.celId, it.key) == tile } else -1
                    if (droppedHere >= 0) strokeRevived[droppedHere] = UndoLog.TileChange(storeId, key, stroke!![droppedHere].before, after, plane)
                    else if (now == null && dropped != null) revived.add(UndoLog.TileChange(storeId, key, dropped.before, after, plane))
                    else fresh.add(UndoLog.TileChange(storeId, key, now, after, plane))
                    tiles[key] = after
                    texes[key] = after
                }
                out[store] = texes
            }
        } catch (e: Throwable) {
            // Put back what was swapped and free the copies: nothing half-done reaches the history.
            for (c in fresh.asReversed()) { put(c.layerId, c.key, c.before, c.celId); c.after?.let(::recycleLayerTex) }
            for (c in revived + strokeRevived.values) { put(c.layerId, c.key, null, c.celId); c.after?.let(::recycleLayerTex) }
            throw e
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
        invalidateProjection(layer)
        // The media wrote these tiles' look and payload together: they agree.
        if (MEDIA_LOOK in stores) for (key in keys) agree(layerId, key, plane)
        if (stroke != null) {
            for ((i, c) in strokeRevived) { stroke[i] = c; mediaStrokeHeld.add(Triple(c.layerId, c.celId, c.key)) }
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
    fun dropMediaTiles(layerId: String, store: String, keys: Collection<Long>, plane: String? = null) {
        requireLocalPaint()
        require(store in MediaStores.WATER) { "only water dries away; $store stays" }
        check(mediaStroke == null) { "water is dropped between strokes, not during one" }
        val tiles = layers[layerId]?.let { (if (plane == null) it.floats else it.celFloats[plane])?.get(store) } ?: return
        val id = MediaStores.id(layerId, store)
        val topHeld = undo.newestExtendable()?.takeIf { MediaStores.isMediaStep(it) }?.changes?.associateBy { Triple(it.layerId, it.celId, it.key) }.orEmpty()
        val replaced = ArrayList<UndoLog.TileChange<Int>>()
        val fresh = ArrayList<UndoLog.TileChange<Int>>()
        for (key in keys) {
            val now = tiles.remove(key) ?: continue
            val held = topHeld[Triple(id, plane, key)]
            if (held != null) { replaced.add(UndoLog.TileChange(id, key, held.before, null, plane)); recycleLayerTex(now) }
            else fresh.add(UndoLog.TileChange(id, key, now, null, plane))
        }
        if (replaced.isNotEmpty()) check(undo.replaceInNewest(replaced)) { "the newest media step changed under a drop" }
        MediaStores.recordWater(undo, fresh)
    }

    /** The texture of one media tile as it stands (read only), or null where the store has none. */
    fun mediaTile(layerId: String, store: String, key: Long, plane: String? = null): Int? =
        layers[layerId]?.let { l -> if (store == MEDIA_LOOK) (if (plane == null) l.tiles else l.cels[plane])?.get(key) else (if (plane == null) l.floats else l.celFloats[plane])?.get(store)?.get(key) }

    /** Keys of every tile a media store has (sparse): for saving, and for the window to know what exists. */
    fun mediaTileKeys(layerId: String, store: String, plane: String? = null): List<Long> =
        layers[layerId]?.let { l -> (if (plane == null) l.floats else l.celFloats[plane])?.get(store)?.keys?.toList() } ?: emptyList()

    /** The frame planes of [layerId] that hold a payload (the shared plane is null and is not listed). */
    fun mediaFramePlanes(layerId: String): List<String> = layers[layerId]?.celFloats?.filterValues { p -> p.values.any { it.isNotEmpty() } }?.keys?.toList().orEmpty()

    /** True when [layerId] holds any float state: a media layer that has been painted. */
    fun hasMediaState(layerId: String): Boolean = layers[layerId]?.planes()?.any { (_, p) -> p.values.any { it.isNotEmpty() } } == true

    /**
     * One store tile for saving: [MediaStores.tileBytes] bytes of little-endian half floats ([HalfFloat]), RGBA, row 0 = the
     * tile's TOP document row (as [readTile]). Read back as full floats (the one readback ES 3 guarantees for a float
     * colour buffer) and rounded on the CPU. Read at a frame boundary (the Lead's rule: never mid-simulation). Null where
     * the store has no tile.
     */
    fun readMediaTile(layerId: String, store: String, key: Long, plane: String? = null): ByteArray? {
        require(store in MediaStores.PAYLOAD) { "unknown media store $store" }
        val tex = mediaTile(layerId, store, key, plane) ?: return null
        // The ground is RGBA8 like a look tile, and is read like one.
        if (store == MediaStores.GROUND) return readTextureTile(tex)
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
    fun writeMediaTile(layerId: String, store: String, key: Long, bytes: ByteArray, plane: String? = null) {
        requireLocalPaint()
        require(store in MediaStores.PAYLOAD) { "unknown media store $store" }
        require(bytes.size == MediaStores.tileBytes(size, store)) { "media tile must be ${MediaStores.tileBytes(size, store)} bytes, got ${bytes.size}" }
        val layer = storeOrCreate(layerId)
        val tex = payload(layer, store, planeOf(layer, plane)).getOrPut(key) { newPayloadTile(store) }
        if (store == MediaStores.GROUND) { uploadTile(tex, bytes); return }
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
        val byPlane = LinkedHashMap<Pair<String, String?>, HashSet<Long>>()
        for (c in changes) {
            val media = MediaStores.parse(c.layerId)
            val owner = media?.first ?: c.layerId.takeIf { layers[it]?.planes()?.any { (_, p) -> p.isNotEmpty() } == true } ?: continue
            val plane = layers[owner]?.let { planeOf(it, c.celId) } ?: c.celId
            byPlane.getOrPut(owner to plane) { HashSet() }.add(c.key)
        }
        for ((at, keys) in byPlane) listener(at.first, at.second, keys)
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
            // A stroke always paints into the saved rectangle, so the drag preview yields the moment one begins.
            val rect=tilePreviewRect?.takeUnless { strokeInProgress } ?: tile.rect
            cropPreview(rect,paper,false)
            boardPreview.show(w,h,m,rect,tileVao,true,floatArrayOf(1f,1f,1f,1f))
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
                if (!tileOnScreen(key, docToClip)) continue
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
            keys.retainAll { tileOnScreen(it, docToClip) }
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
    /**
     * Whether tile [key] can show through [m] (document px → clip): false only when all four corners lie beyond one edge
     * of clip space. Tiles off screen are skipped before any uniform, bind or draw (H8, 2026-10-10): on a large drawing
     * zoomed in, most tiles of every layer are off screen, and each cost CPU work every frame for no pixels.
     */
    private fun tileOnScreen(key: Long, m: FloatArray): Boolean {
        val ox = (Tiles.tx(key) * size).toFloat()
        val oy = (Tiles.ty(key) * size).toFloat()
        var left = 0; var right = 0; var below = 0; var above = 0
        for (corner in 0 until 4) {
            val dx = ox + (corner and 1) * size
            val dy = oy + (corner shr 1) * size
            val cw = m[2] * dx + m[5] * dy + m[8]
            if (cw <= 0f) return true   // a projective view behind the eye: never cull on a guess
            val cx = (m[0] * dx + m[3] * dy + m[6]) / cw
            val cy = (m[1] * dx + m[4] * dy + m[7]) / cw
            if (cx < -1f) left++ else if (cx > 1f) right++
            if (cy < -1f) below++ else if (cy > 1f) above++
        }
        return left < 4 && right < 4 && below < 4 && above < 4
    }

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
        GLES30.glUniform1i(commitProg.loc("u_rgbaStroke"),if(wcSpec != null)1 else 0)
        GLES30.glUniform1f(commitProg.loc("u_tileSize"), size.toFloat())
        GLES30.glUniform1i(commitProg.loc("u_layer"), 0)
        GLES30.glUniform1i(commitProg.loc("u_stroke"), 1)
        GLES30.glUniform3f(commitProg.loc("u_color"), colR, colG, colB)
        GLES30.glUniform1f(commitProg.loc("u_strokeScale"), if (accumulate == Accumulate.BUILD_UP) opacity else 1f)
        GLES30.glUniform1i(commitProg.loc("u_erase"), if (blend == StrokeBlend.ERASE) 1 else 0)
        // Brush version 9 glazes (VECTOR_RENDER_DESIGN H6); 0 is the unchanged normal path.
        GLES30.glUniform1i(commitProg.loc("u_blend"), when (blend) { StrokeBlend.MULTIPLY -> 1; StrokeBlend.SCREEN -> 2; else -> 0 })
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
            val o = storeOrCreate(owner)
            val tiles = payload(o, store, planeOf(o, celId))
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
        if (forgettingGraphicsContext) return
        // Scene caches, baked slabs and UndoLog endpoints all retire through this hook.
        // The pool still owns a recycled allocation; future budgets count that actual owner.
        if (tex != 0) recordedWorkingTiles.forget(physicalContextEpoch, tex)
        if (tex in floatNames) {
            freeFloatTex.addLast(tex)
            while (freeFloatTex.size > 16) freeFloatTex.removeFirst().let {
                floatNames.remove(it)
                GLES30.glDeleteTextures(1, intArrayOf(it), 0)
                if (it != 0) recordedWorkingTiles.forget(physicalContextEpoch, it)
            }
            return
        }
        freeLayerTex.addLast(tex)
        trimPool(freeLayerTex, 64)
    }

    private fun trimPool(pool: ArrayDeque<Int>, keep: Int) {
        while (pool.size > keep) {
            val texture = pool.removeFirst()
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            if (texture != 0) recordedWorkingTiles.forget(physicalContextEpoch, texture)
        }
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

    private fun upload(vbo: Int, data: FloatArray) {
        val b = newFloats(data.size).put(data)
        b.flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, b, GLES30.GL_STATIC_DRAW)
    }

    private fun newFloats(n: Int): FloatBuffer =
        ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
}
