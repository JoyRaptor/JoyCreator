package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.core.brush.SmudgeCarried
import cc.joycreator.joybrush.core.brush.SmudgeStroke
import cc.joycreator.joybrush.core.brush.TileReader
import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.StrokeBlend
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.paint.TuftMath
import cc.joycreator.joybrush.core.paint.TuftShading
import cc.joycreator.joybrush.core.paint.TuftStamp
import cc.joycreator.joybrush.core.paint.UndoLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.IdentityHashMap

/**
 * The two rates of a smudge stroke (JB-1.06): how fast the ONE carried colour takes on the canvas ([pickup]) and the brush's
 * own colour ([load]), both 0..1. How hard a dab presses is the dab's own flow. See `core/brush/Smudge.kt` for the rule.
 */
class SmudgeParams(val pickup: Float, val load: Float)

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
    private var clearTex = 0

    /** The grain pictures (JB-1.05c): loaded on first use, dropped on a context loss. */
    private val grains = GrainTextures()

    /** The offscreen stack the composite path builds (JB-2.20b); made on first use, dropped on a context loss. */
    internal val compositor = LayerCompositor()

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

    private class Layer(val id: String) {
        val tiles = HashMap<Long, Int>()
        var opacity = 1f
        var visible = true
        var blend = BlendMode.NORMAL
    }

    private val layers = LinkedHashMap<String, Layer>()   // bottom → top
    private val freeLayerTex = ArrayDeque<Int>()
    private val freeStrokeTex = ArrayDeque<Int>()
    private val freeSmudgeTex = ArrayDeque<Int>()

    val undo = UndoLog<Int>(undoBudgetBytes, sizeOf = { size.toLong() * size * 4 }, release = ::recycleLayerTex)

    // Active stroke.
    private var strokeLayer: Layer? = null
    private val strokeTiles = HashMap<Long, Int>()
    private var colR = 0f; private var colG = 0f; private var colB = 0f
    private var opacity = 1f
    private var accumulate = Accumulate.WASH
    private var blend = StrokeBlend.NORMAL
    private var tip = TipShape()
    private var grain = GrainMath.StrokeGrain(GrainMath.GrainUniforms.OFF, GrainMath.GrainUniforms.OFF)
    private var tipGrainTex = 0
    private var paperGrainTex = 0

    private var instanceData: FloatBuffer = newFloats(6 * 256)
    private var smudgeInstanceData: FloatBuffer = newFloats(10 * 256)
    private var tuftInstanceData: FloatBuffer = newFloats(TuftStamp.FLOATS * 256)

    /** Set while a tuft stroke is in progress (R9): its whole-stroke shader numbers, and the page's tooth picture. */
    private var tuft: TuftShading? = null
    private var tuftPaperTex = 0

    /** Set while a smudge stroke is in progress: the ONE carried colour, and the layer as it was at pen-down. */
    private var smudge: SmudgeStroke? = null

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
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glVertexAttribDivisor(1, 1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 24, 16)
        GLES30.glVertexAttribDivisor(2, 1)

        // The smudge dab: the same quad and the same instance buffer, but 10 floats per dab (the stamp's 6 plus the carried colour).
        GLES30.glBindVertexArray(smudgeVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 40, 0)
        GLES30.glVertexAttribDivisor(1, 1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 40, 16)
        GLES30.glVertexAttribDivisor(2, 1)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, 40, 24)
        GLES30.glVertexAttribDivisor(3, 1)

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

        clearTex = newTexture(1, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE)
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
        undo.clear()
        freeLayerTex.clear()
        freeStrokeTex.clear()
        freeSmudgeTex.clear()
        // The grain pictures are not the person's drawing, so they do not count as "had" -- but their
        // names are just as dead, and a brush is reloaded from its file on the next stroke.
        grains.forget()
        compositor.forget()
        compositeProg = null      // a name from the dead context; the new one compiles on first use
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
        layers.values.sumOf { it.tiles.size } + strokeTiles.size + freeLayerTex.size + freeStrokeTex.size + freeSmudgeTex.size +
            compositor.heldNames()

    /** Frees every GL object this engine owns. */
    fun release() {
        if (!ready) return
        undo.clear()
        cancelStroke()
        val all = ArrayList<Int>()
        layers.values.forEach { all.addAll(it.tiles.values) }
        all.addAll(freeLayerTex); all.addAll(freeStrokeTex); all.addAll(freeSmudgeTex); all.add(clearTex)
        GLES30.glDeleteTextures(all.size, all.toIntArray(), 0)
        layers.clear(); freeLayerTex.clear(); freeStrokeTex.clear(); freeSmudgeTex.clear()
        GLES30.glDeleteBuffers(3, intArrayOf(quadVbo, unitVbo, instanceVbo), 0)
        GLES30.glDeleteVertexArrays(4, intArrayOf(dabVao, tileVao, smudgeVao, tuftVao), 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        grains.release()
        compositor.release()
        dabProg.release(); commitProg.release(); tileProg.release(); smudgeProg.release(); tuftProg.release()
        compositeProg?.release(); compositeProg = null
        ready = false
    }

    // ── layers ───────────────────────────────────────────────────────────────

    /** Adds an empty layer on top (no-op if it exists). */
    fun addLayer(id: String) { layers.getOrPut(id) { Layer(id) } }

    fun setLayerOpacity(id: String, opacity: Float) { layers[id]?.opacity = opacity.coerceIn(0f, 1f) }
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
        val layer = layers[id] ?: return
        if (layer.tiles.isEmpty()) return
        val changes = layer.tiles.map { (k, t) -> UndoLog.TileChange<Int>(id, k, t, null) }
        layer.tiles.clear()
        undo.push(UndoLog.Step(changes))
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
    fun tileKeys(layerId: String): List<Long> = layers[layerId]?.tiles?.keys?.toList() ?: emptyList()

    /**
     * A tile's pixels: 256×256 premultiplied RGBA8, 262,144 bytes, row 0 = the tile's TOP document
     * row (no flip needed). Null if the tile does not exist.
     */
    fun readTile(layerId: String, key: Long): ByteArray? {
        val tex = layers[layerId]?.tiles?.get(key) ?: return null
        val buf = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        attach(tex)
        GLES30.glReadPixels(0, 0, size, size, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
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
        val tex = layers[layerId]?.tiles?.get(Tiles.key(tx, ty)) ?: return null
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
        val layer = layers.getOrPut(layerId) { Layer(layerId) }
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
        val layer = layers[layerId] ?: error("no layer $layerId")
        for (rgba in tiles.values) {
            require(rgba == null || rgba.size == size * size * 4) { "tile must be ${size * size * 4} bytes" }
        }
        val changes = ArrayList<UndoLog.TileChange<Int>>()
        for ((key, rgba) in tiles) {
            val before = layer.tiles[key]
            if (rgba == null && before == null) continue
            val after = rgba?.let { uploadTile(newLayerTile(), it) }
            if (after == null) layer.tiles.remove(key) else layer.tiles[key] = after
            changes.add(UndoLog.TileChange(layer.id, key, before, after))
        }
        if (changes.isNotEmpty()) undo.push(UndoLog.Step(changes))
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
        undo.clear()
        layers.values.forEach { l -> l.tiles.values.forEach(::recycleLayerTex) }
        layers.clear()
    }

    fun layerOpacity(id: String): Float = layers[id]?.opacity ?: 1f
    fun layerVisible(id: String): Boolean = layers[id]?.visible ?: true

    // ── strokes ──────────────────────────────────────────────────────────────

    /** Starts a stroke. [argb] is the brush colour (alpha ignored — [opacity] is the stroke's opacity). */
    fun beginStroke(layerId: String, argb: Int, opacity: Float, accumulate: Accumulate,
                    blend: StrokeBlend, tip: TipShape,
                    grain: GrainMath.StrokeGrain = GrainMath.StrokeGrain(GrainMath.GrainUniforms.OFF, GrainMath.GrainUniforms.OFF),
                    smudge: SmudgeParams? = null, tuft: TuftShading? = null) {
        cancelStroke()
        strokeLayer = layers[layerId] ?: error("no layer $layerId")
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
        this.smudge = if (smudge == null) null else SmudgeStroke(
            TileReader { tx, ty -> readTile(layerId, Tiles.key(tx, ty)) },
            SmudgeCarried(colR, colG, colB, 1f, smudge.pickup.coerceIn(0f, 1f), smudge.load.coerceIn(0f, 1f)),
            tip,
        )
        // A grain whose picture is missing is drawn as OFF: a missing picture must never read as "paint
        // everywhere". The picture is bound per batch in addDabs, but resolved here, once per stroke.
        val tipTex = if (grain.tip.enabled) grains.textureFor(grain.tip.asset) else null
        val paperTex = if (grain.paper.enabled) grains.textureFor(grain.paper.asset) else null
        this.grain = GrainMath.StrokeGrain(
            tip = if (tipTex != null) grain.tip else GrainMath.GrainUniforms.OFF,
            paper = if (paperTex != null) grain.paper else GrainMath.GrainUniforms.OFF,
        )
        tipGrainTex = tipTex ?: grains.placeholder
        paperGrainTex = paperTex ?: grains.placeholder
        // A tuft stroke reads the page's tooth; a missing picture means no tooth (pitch 0), never "ink everywhere".
        val tooth = if (tuft != null) grains.textureFor(tuft.paperAsset) else null
        tuftPaperTex = tooth ?: grains.placeholder
        this.tuft = if (tuft != null && tooth == null) tuft.copy(paperPitchPx = 0f) else tuft
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
        val buckets = Tiles.bucket(dabs, size)
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
            GLES30.glUniform2f(dabProg.loc("u_tileOrigin"), (Tiles.tx(key) * size).toFloat(), (Tiles.ty(key) * size).toFloat())
            fillInstances(list)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, list.size * 24, instanceData, GLES30.GL_STREAM_DRAW)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, list.size)
        }
        GLES30.glBindVertexArray(0)
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
        val buckets = TuftMath.bucket(stamps, size)
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
        GLES30.glUniform1i(tuftProg.loc("u_paperGrain"), 1)
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
            GLES30.glUniform2f(tuftProg.loc("u_tileOrigin"), (Tiles.tx(key) * size).toFloat(), (Tiles.ty(key) * size).toFloat())
            val need = list.size * TuftStamp.FLOATS
            if (tuftInstanceData.capacity() < need) tuftInstanceData = newFloats(need * 2)
            tuftInstanceData.clear()
            for (t in list) {
                tuftInstanceData.put(t.ax).put(t.ay).put(t.bx).put(t.by)
                    .put(t.ra).put(t.rb).put(t.flow).put(t.cap)
                    .put(t.dry).put(t.bias).put(t.splay).put(t.arc)
                    .put(t.kind.toFloat()).put(0f).put(0f).put(0f)
            }
            tuftInstanceData.flip()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, need * 4, tuftInstanceData, GLES30.GL_STREAM_DRAW)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, list.size)
        }
        GLES30.glBindVertexArray(0)
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
        GLES30.glBindVertexArray(smudgeVao)
        for ((key, list) in buckets) {
            attach(strokeTiles.getValue(key))
            GLES30.glUniform2f(smudgeProg.loc("u_tileOrigin"), (Tiles.tx(key) * size).toFloat(), (Tiles.ty(key) * size).toFloat())
            val need = list.size * 10
            if (smudgeInstanceData.capacity() < need) smudgeInstanceData = newFloats(need * 2)
            smudgeInstanceData.clear()
            for (d in list) {
                val c = (at[d] ?: 0) * 4
                smudgeInstanceData.put(d.x).put(d.y).put(d.radius).put(d.angle).put(d.flow).put(d.cap)
                    .put(colours[c]).put(colours[c + 1]).put(colours[c + 2]).put(colours[c + 3])
            }
            smudgeInstanceData.flip()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, list.size * 40, smudgeInstanceData, GLES30.GL_STREAM_DRAW)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, list.size)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
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
    private fun setGrainUniforms(newest: Dab) {
        GLES30.glUniform1i(dabProg.loc("u_tipGrain"), 0)
        GLES30.glUniform1i(dabProg.loc("u_paperGrain"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tipGrainTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, paperGrainTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val tg = grain.tip
        val pg = grain.paper
        GLES30.glUniform1f(dabProg.loc("u_tipGrainPitchPx"), tg.pitchPx)
        GLES30.glUniform1f(dabProg.loc("u_tipDepth"), tg.depth)
        GLES30.glUniform1f(dabProg.loc("u_tipEdge"), tg.edge)
        GLES30.glUniform1f(dabProg.loc("u_tipTiltGradient"), tg.tiltGradient)
        GLES30.glUniform1f(dabProg.loc("u_tipRadial"), tg.radial)
        GLES30.glUniform1f(dabProg.loc("u_paperGrainPitchPx"), pg.pitchPx)
        GLES30.glUniform1f(dabProg.loc("u_paperDepth"), pg.depth)
        GLES30.glUniform1f(dabProg.loc("u_paperEdge"), pg.edge)
        GLES30.glUniform1f(dabProg.loc("u_paperTiltGradient"), pg.tiltGradient)
        GLES30.glUniform1f(dabProg.loc("u_paperRadial"), pg.radial)
        GLES30.glUniform1f(dabProg.loc("u_tiltAmount"), GrainMath.tiltAmount(newest.tilt))
        GLES30.glUniform2f(dabProg.loc("u_leanDir"), GrainMath.leanX(newest.azimuth), GrainMath.leanY(newest.azimuth))
    }

    /** Commits the active stroke into its layer as one undoable step. Returns tiles changed. */
    fun endStroke(): Int {
        val layer = strokeLayer ?: return 0
        val changes = ArrayList<UndoLog.TileChange<Int>>()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, size, size)
        GLES30.glDisable(GLES30.GL_BLEND)
        commitProg.use()
        setCommitUniforms(layerOpacity = 1f)
        GLES30.glBindVertexArray(tileVao)
        for ((key, strokeTex) in strokeTiles) {
            val before = layer.tiles[key]
            if (before == null && blend == StrokeBlend.ERASE) continue
            val after = newLayerTile()
            attach(after)
            val ox = (Tiles.tx(key) * size).toFloat()
            val oy = (Tiles.ty(key) * size).toFloat()
            GLES30.glUniform2f(commitProg.loc("u_tileOrigin"), ox, oy)
            GLES30.glUniformMatrix3fv(commitProg.loc("u_docToClip"), 1, false, tileToClip(ox, oy), 0)
            bindTextures(before ?: clearTex, strokeTex)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            layer.tiles[key] = after
            changes.add(UndoLog.TileChange(layer.id, key, before, after))
        }
        GLES30.glBindVertexArray(0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        releaseStrokeTiles()
        strokeLayer = null
        smudge = null
        tuft = null
        if (changes.isNotEmpty()) undo.push(UndoLog.Step(changes))
        return changes.size
    }

    fun cancelStroke() {
        releaseStrokeTiles()
        strokeLayer = null
        smudge = null
        tuft = null
    }

    fun undoStep(): Boolean {
        val s = undo.undo() ?: return false
        s.changes.forEach { put(it.layerId, it.key, it.before) }
        return true
    }

    fun redoStep(): Boolean {
        val s = undo.redo() ?: return false
        s.changes.forEach { put(it.layerId, it.key, it.after) }
        return true
    }

    // ── display ──────────────────────────────────────────────────────────────

    /**
     * Draws paper and every visible layer (with the live stroke previewed) into the CURRENT
     * framebuffer. [docToClip] is a column-major 3×3 matrix from document px to clip space.
     */
    fun draw(viewportW: Int, viewportH: Int, docToClip: FloatArray, paperArgb: Int) {
        if (needsComposite()) { drawComposited(viewportW, viewportH, docToClip, paperArgb); return }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewportW, viewportH)
        GLES30.glClearColor(
            ((paperArgb shr 16) and 0xFF) / 255f, ((paperArgb shr 8) and 0xFF) / 255f,
            (paperArgb and 0xFF) / 255f, 1f,
        )
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glBindVertexArray(tileVao)

        for (layer in layers.values) {
            if (!layer.visible) continue
            val previewing = layer === strokeLayer && strokeTiles.isNotEmpty()

            tileProg.use()
            GLES30.glUniform1f(tileProg.loc("u_tileSize"), size.toFloat())
            GLES30.glUniformMatrix3fv(tileProg.loc("u_docToClip"), 1, false, docToClip, 0)
            GLES30.glUniform1f(tileProg.loc("u_layerOpacity"), layer.opacity)
            GLES30.glUniform1i(tileProg.loc("u_layer"), 0)
            for ((key, tex) in layer.tiles) {
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
                    val base = layer.tiles[key]
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
    private fun needsComposite(): Boolean =
        compositeError == null && layers.values.any { it.visible && it.blend != BlendMode.NORMAL }

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
    private fun drawComposited(w: Int, h: Int, docToClip: FloatArray, paperArgb: Int) {
        val prog = compositeProg ?: try {
            GlProgram(shaders.source("jb_tile.vert"), shaders.source("jb_composite.frag"), "composite").also { compositeProg = it }
        } catch (e: RuntimeException) {
            // The 27-branch blend shader is the biggest one we compile. If a driver refuses it, the rest of the
            // engine must keep working: remember why, and draw this and every later frame the plain way.
            compositeError = e.message ?: e.javaClass.simpleName
            draw(w, h, docToClip, paperArgb)
            return
        }
        compositor.ensure(w, h)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, compositor.targetFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(
            ((paperArgb shr 16) and 0xFF) / 255f, ((paperArgb shr 8) and 0xFF) / 255f,
            (paperArgb and 0xFF) / 255f, 1f,
        )
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        for (layer in layers.values) {
            if (!layer.visible) continue
            val previewing = layer === strokeLayer && strokeTiles.isNotEmpty()
            val previews = if (previewing) renderStrokePreviews(layer) else emptyMap()
            val keys = LinkedHashSet<Long>(layer.tiles.keys)
            keys.addAll(previews.keys)
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
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, compositor.backdrop)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindVertexArray(tileVao)
                for (key in keys) {
                    val tex = previews[key] ?: layer.tiles[key] ?: continue
                    GLES30.glUniform2f(prog.loc("u_tileOrigin"), (Tiles.tx(key) * size).toFloat(), (Tiles.ty(key) * size).toFloat())
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                }
            }
            previews.values.forEach(::recycleLayerTex)
        }
        compositor.blitToScreen()
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
            val base = layer.tiles[key]
            if (base == null && blend == StrokeBlend.ERASE) continue
            val after = newLayerTile()
            attach(after)
            val ox = (Tiles.tx(key) * size).toFloat()
            val oy = (Tiles.ty(key) * size).toFloat()
            GLES30.glUniform2f(commitProg.loc("u_tileOrigin"), ox, oy)
            GLES30.glUniformMatrix3fv(commitProg.loc("u_docToClip"), 1, false, tileToClip(ox, oy), 0)
            bindTextures(base ?: clearTex, strokeTex)
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
        GLES30.glUniform1i(commitProg.loc("u_smudge"), if (smudge != null) 1 else 0)
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

    private fun put(layerId: String, key: Long, tex: Int?) {
        val layer = layers.getOrPut(layerId) { Layer(layerId) }
        if (tex == null) layer.tiles.remove(key) else layer.tiles[key] = tex
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
        freeLayerTex.addLast(tex)
        trimPool(freeLayerTex, 64)
    }

    private fun trimPool(pool: ArrayDeque<Int>, keep: Int) {
        while (pool.size > keep) GLES30.glDeleteTextures(1, intArrayOf(pool.removeFirst()), 0)
    }

    private fun newTexture(dim: Int, internal: Int, format: Int, type: Int): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, dim, dim, 0, format, type, null)
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

    private fun fillInstances(list: List<Dab>) {
        val need = list.size * 6
        if (instanceData.capacity() < need) instanceData = newFloats(need * 2)
        instanceData.clear()
        for (d in list) {
            instanceData.put(d.x).put(d.y).put(d.radius).put(d.angle).put(d.flow).put(d.cap)
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
