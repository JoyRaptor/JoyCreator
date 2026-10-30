package cc.joycreator.joybrush.androidkit.gl.watercolour

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.GlProgram
import cc.joycreator.joybrush.androidkit.gl.ShaderLibrary
import cc.joycreator.joybrush.core.brush.WatercolourDabber
import cc.joycreator.joybrush.core.brush.WatercolourSpec
import cc.joycreator.joybrush.core.doc.RegionTileRect
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.StrokeWashFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections
import java.util.LinkedHashMap
import kotlin.math.pow

/** Three coverage channels, shared live/replay draw, and a seam-aware finish. All GL names belong to this context. */
internal class WatercolourPass(private val shaders: ShaderLibrary, private val docTileSize: Int = Tiles.SIZE,
                              private val renderScale: Float = 1f) {
    private val size = kotlin.math.round(docTileSize * renderScale).toInt()
    init {
        require(docTileSize > 0 && renderScale.isFinite() && renderScale > 0f && size > 0)
        require(kotlin.math.abs(size - docTileSize * renderScale) < .001f) { "Watercolor tile scale must produce whole pixels" }
    }
    private val textures = WatercolourTextures()
    private val bands = LinkedHashMap<Long, Int>()
    private val plain = LinkedHashMap<Long, Int>()
    private val pooled = LinkedHashMap<Long, Int>()
    private val free = ArrayDeque<Int>()
    private var spec = WatercolourSpec()
    private var images: WatercolourTextures.Set? = null
    private var tint = floatArrayOf(0f, 0f, 0f)
    private var offset = floatArrayOf(0f, 0f)
    private var paper: ((GlProgram) -> Unit)? = null
    private var paperTextures: Set<Int> = emptySet()

    /** The callback's explicit borrowed names; these are not owned coverage/image textures. */
    internal fun borrowedPaperTextureHandles(): Set<Int> = paperTextures
    private var hasPaper = false
    private var empty = 0
    private var fbo = 0
    private var quad = 0
    private var instance = 0
    private var vao = 0
    private var finishVao = 0
    private var dab: GlProgram? = null
    private var finish: GlProgram? = null
    /** The finish built with JB_WASH_FRAME: only for a line whose wash was moved with it; compiled the first time one is drawn. */
    private var finishFramed: GlProgram? = null
    private fun framedFinish(): GlProgram = finishFramed ?: GlProgram(shaders.source("lines/jb_wc_finish.vert"),
        shaders.source("lines/jb_wc_finish.frag", listOf("JB_WASH_FRAME")), "watercolor finish (moved wash)").also { finishFramed = it }
    private var mix: GlProgram? = null
    private var data = floats(8 * 256)
    private var internal = GLES30.GL_RGBA16F
    private var type = GLES30.GL_HALF_FLOAT
    private var docToWash: FloatArray? = null

    /** Finish-only placement; changing it never deposits the dabs again. */
    fun setWashFrame(frame: StrokeWashFrame?) {
        docToWash = frame?.takeUnless { it == StrokeWashFrame.IDENTITY }?.let {
            val m = requireNotNull(it.toHomography().inverse()).m
            floatArrayOf(m[0].toFloat(), m[3].toFloat(), m[6].toFloat(),
                m[1].toFloat(), m[4].toFloat(), m[7].toFloat(),
                m[2].toFloat(), m[5].toFloat(), m[8].toFloat()).also { matrix ->
                require(matrix.all { v -> v.isFinite() }) { "This wash placement exceeds the phone's coordinate range" }
            }
        }
    }

    /** Idle GL-thread preparation. Does not replace the active stroke, its bands, or its finish inputs. */
    fun prepare(options: WatercolourSpec) {
        fun get(name: Int) = IntArray(1).also { GLES30.glGetIntegerv(name, it, 0) }[0]
        val active = get(GLES30.GL_ACTIVE_TEXTURE)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val tex2d = get(GLES30.GL_TEXTURE_BINDING_2D)
        val array = get(GLES30.GL_TEXTURE_BINDING_2D_ARRAY)
        val draw = get(GLES30.GL_DRAW_FRAMEBUFFER_BINDING)
        val read = get(GLES30.GL_READ_FRAMEBUFFER_BINDING)
        val vertex = get(GLES30.GL_VERTEX_ARRAY_BINDING)
        val buffer = get(GLES30.GL_ARRAY_BUFFER_BINDING)
        val unpackBuffer = get(GLES30.GL_PIXEL_UNPACK_BUFFER_BINDING)
        val pixelNames = intArrayOf(GLES30.GL_UNPACK_ALIGNMENT, GLES30.GL_UNPACK_ROW_LENGTH,
            GLES30.GL_UNPACK_SKIP_ROWS, GLES30.GL_UNPACK_SKIP_PIXELS, GLES30.GL_UNPACK_IMAGE_HEIGHT,
            GLES30.GL_UNPACK_SKIP_IMAGES)
        val pixels = pixelNames.map(::get)
        val scissor = GLES30.glIsEnabled(GLES30.GL_SCISSOR_TEST)
        val clear = FloatArray(4).also { GLES30.glGetFloatv(GLES30.GL_COLOR_CLEAR_VALUE, it, 0) }
        val colourMask = BooleanArray(4).also { GLES30.glGetBooleanv(GLES30.GL_COLOR_WRITEMASK, it, 0) }
        try {
            GLES30.glColorMask(true, true, true, true)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
            pixelNames.forEach { GLES30.glPixelStorei(it, if (it == GLES30.GL_UNPACK_ALIGNMENT) 1 else 0) }
            ensureObjects()
            textures.forSpec(options)
        } finally {
            pixelNames.forEachIndexed { i, name -> GLES30.glPixelStorei(name, pixels[i]) }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, unpackBuffer)
            GLES30.glBindVertexArray(vertex)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, draw)
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, read)
            if (scissor) GLES30.glEnable(GLES30.GL_SCISSOR_TEST) else GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glClearColor(clear[0], clear[1], clear[2], clear[3])
            GLES30.glColorMask(colourMask[0], colourMask[1], colourMask[2], colourMask[3])
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex2d)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, array)
            GLES30.glActiveTexture(active)
        }
    }

    fun recolour(color: FloatArray, paperPresent: Boolean, paperHandles: Set<Int>, bindPaper: (GlProgram) -> Unit) {
        tint=color.copyOf(); hasPaper=paperPresent; paper=bindPaper; paperTextures=paperHandles.toSet()
    }

    fun begin(options: WatercolourSpec, seed: Long, color: FloatArray, paperPresent: Boolean,
              paperHandles: Set<Int>, bindPaper: (GlProgram) -> Unit) {
        clearStroke()
        ensureObjects()
        spec = options; tint = color.copyOf(); paper = bindPaper; hasPaper = paperPresent; paperTextures=paperHandles.toSet()
        setWashFrame(null)
        images = textures.forSpec(options)
        val d = WatercolourDabber(options, seed)
        val world = 1254.0 * options.textureScale
        // Canvas translated the repeating mask forward; sampling uses the inverse transform.
        offset = floatArrayOf((-d.backdropOffsetX * world).toFloat(), (-d.backdropOffsetY * world).toFloat())
    }
    private fun ensureObjects() {
        if (dab != null) return
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        val version = GLES30.glGetString(GLES30.GL_VERSION).orEmpty()
        require(version.contains("OpenGL ES 3.2") || ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float")) {
            "Watercolor needs half-float render targets on this phone"
        }
        dab = GlProgram(shaders.source("lines/jb_wc_dab.vert"), shaders.source("lines/jb_wc_dab.frag"), "watercolor dabs")
        finish = GlProgram(shaders.source("lines/jb_wc_finish.vert"), shaders.source("lines/jb_wc_finish.frag"), "watercolor finish")
        mix = GlProgram(shaders.source("lines/jb_wc_finish.vert"), shaders.source("lines/jb_wc_mix.frag"), "watercolor fade")
        val ids = IntArray(1); GLES30.glGenFramebuffers(1, ids, 0); fbo = ids[0]
        val buffers = IntArray(2); GLES30.glGenBuffers(2, buffers, 0); quad = buffers[0]; instance = buffers[1]
        val vertices = IntArray(2); GLES30.glGenVertexArrays(2, vertices, 0); vao = vertices[0]; finishVao = vertices[1]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
        val q = floats(8).put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f)); q.flip()
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 32, q, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(vao)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0,2,GLES30.GL_FLOAT,false,0,0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,instance)
        for (i in 1..2) {
            GLES30.glEnableVertexAttribArray(i); GLES30.glVertexAttribPointer(i,4,GLES30.GL_FLOAT,false,32,(i-1)*16)
            GLES30.glVertexAttribDivisor(i,1)
        }
        // Separate unit quad for the finish vertex program.
        val unit = floats(8).put(floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f)); unit.flip()
        val unitBuffer = IntArray(1); GLES30.glGenBuffers(1,unitBuffer,0); unitVbo = unitBuffer[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,unitVbo); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,32,unit,GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(finishVao); GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0,2,GLES30.GL_FLOAT,false,0,0)
        empty = createTile()
        GLES30.glBindVertexArray(0); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0)
    }
    private var unitVbo = 0
    /** Caller freezes ownership and supplies a per-tile clip; this pass knows no layer/media stores. */
    fun add(buckets: Map<Long,List<Dab>>, clip: (Long) -> RegionTileRect?) {
        val p = requireNotNull(dab); val array = requireNotNull(images)
        for (key in buckets.keys) bands.getOrPut(key) { tile() }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo); GLES30.glViewport(0,0,size,size)
        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_ONE,GLES30.GL_ONE_MINUS_SRC_COLOR)
        p.use(); GLES30.glUniform1i(p.loc("u_dabs"),0); GLES30.glUniform1f(p.loc("u_tileSize"),size.toFloat()); GLES30.glUniform1f(p.loc("u_scale"),renderScale)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,array.dabs)
        GLES30.glBindVertexArray(vao)
        for ((key,list) in buckets) {
            attach(bands.getValue(key))
            val r = clip(key)
            if (r != null) { GLES30.glEnable(GLES30.GL_SCISSOR_TEST); GLES30.glScissor(r.x,r.y,r.w,r.h) }
            else GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glUniform2f(p.loc("u_tileOrigin"),(Tiles.tx(key)*docTileSize).toFloat(),(Tiles.ty(key)*docTileSize).toFloat())
            val need = list.size * 8
            if (data.capacity() < need) data = floats(need*2)
            data.clear()
            for (d in list) {
                require(d.sampleIndex in 0 until array.count) { "Watercolor dab index is invalid" }
                data.put(d.x).put(d.y).put(d.radius).put(d.angle).put(d.sampleIndex.toFloat()).put(d.band0).put(d.band1).put(d.band2)
            }
            data.flip(); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,instance)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,need*4,data,GLES30.GL_STREAM_DRAW)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP,0,4,list.size)
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST); GLES30.glDisable(GLES30.GL_BLEND); GLES30.glBindVertexArray(0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0); GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }
    fun finishInto(outputs: Map<Long,Int>, pooling: Float, keys: Collection<Long> = outputs.keys) {
        // The original finish unless this line's wash was moved with it (see JB_WASH_FRAME in jb_wc_finish.frag).
        val p = if (docToWash == null) requireNotNull(finish) else framedFinish(); val asset = requireNotNull(images)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo); GLES30.glViewport(0,0,size,size)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST); GLES30.glDisable(GLES30.GL_BLEND); p.use()
        paper?.invoke(p)
        GLES30.glUniform1i(p.loc("u_hasPaper"),if(hasPaper)1 else 0)
        GLES30.glUniform1f(p.loc("u_scale"),renderScale); GLES30.glUniform1i(p.loc("u_bandTileSize"),size)
        GLES30.glUniform1f(p.loc("u_outputDocSize"),docTileSize.toFloat())
        GLES30.glUniform3f(p.loc("u_tintSrgb"),tint[0],tint[1],tint[2])
        GLES30.glUniform3f(p.loc("u_tintLinear"),decode(tint[0]),decode(tint[1]),decode(tint[2]))
        fun f(name:String,value:Double)=GLES30.glUniform1f(p.loc(name),value.toFloat())
        f("u_paperCut",spec.paperCut); f("u_edgeWidth",spec.edgeWidth); f("u_edgePool",spec.edgePool); f("u_edgeAlpha",spec.edgeAlpha)
        f("u_gravityWidth",spec.gravityWidth); f("u_gravityPool",spec.gravityPool); GLES30.glUniform2f(p.loc("u_gravityDirection"),0f,1f)
        GLES30.glUniform1f(p.loc("u_pooling"),pooling)
        f("u_backdropWorld",1254.0*spec.textureScale); GLES30.glUniform2f(p.loc("u_backdropOffset"),offset[0],offset[1])
        docToWash?.let { GLES30.glUniformMatrix3fv(p.loc("u_docToWash"),1,false,it,0) }
        f("u_textureStrength",spec.texture); f("u_textureContrast",spec.textureContrast)
        GLES30.glUniform1i(p.loc("u_textureInvert"),if(spec.backdropInverted)1 else 0); f("u_grain",spec.grain)
        GLES30.glUniform1i(p.loc("u_backdrop"),12); bind(12,asset.backdrop)
        GLES30.glUniform1i(p.loc("u_fineGrain"),13); bind(13,asset.grain)
        GLES30.glBindVertexArray(finishVao)
        for (key in keys) {
            val target = outputs[key] ?: continue
            for(dy in -1..1)for(dx in -1..1) {
                val unit = 3+(dy+1)*3+dx+1
                bind(unit,bands[Tiles.key(Tiles.tx(key)+dx,Tiles.ty(key)+dy)] ?: empty)
                GLES30.glUniform1i(p.loc("u_band${dx+1}${dy+1}"),unit)
            }
            attach(target)
            GLES30.glUniform2f(p.loc("u_tileOrigin"),(Tiles.tx(key)*docTileSize).toFloat(),(Tiles.ty(key)*docTileSize).toFloat())
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP,0,4)
        }
        GLES30.glBindVertexArray(0); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0); GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }
    fun prepareFade(outputs: Map<Long,Int>) {
        for(key in outputs.keys) { plain.getOrPut(key) { tile() }; pooled.getOrPut(key) { tile() } }
        finishInto(plain,0f); finishInto(pooled,1f)
    }
    /** Cached-image interpolation only; no repeated edge/paper readback or optical finish. */
    fun fadeInto(outputs: Map<Long,Int>, amount: Float) {
        val p = requireNotNull(mix)
        p.use(); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo); GLES30.glViewport(0,0,size,size)
        GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glUniform1f(p.loc("u_outputDocSize"),docTileSize.toFloat()); GLES30.glUniform1i(p.loc("u_plain"),0)
        GLES30.glUniform1i(p.loc("u_pooled"),1); GLES30.glUniform1f(p.loc("u_amount"),amount)
        GLES30.glBindVertexArray(finishVao)
        for((key,target)in outputs) {
            bind(0,plain.getValue(key)); bind(1,pooled.getValue(key)); attach(target)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP,0,4)
        }
        GLES30.glBindVertexArray(0); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0); GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }
    fun clearStroke() {
        free.addAll(bands.values); free.addAll(plain.values); free.addAll(pooled.values)
        bands.clear(); plain.clear(); pooled.clear(); paper=null; paperTextures=emptySet()
        while(free.size>32) GLES30.glDeleteTextures(1,intArrayOf(free.removeFirst()),0)
    }
    /** Borrowed coverage, finish pools and image ownership; no GL calls. */
    internal fun heldTextureHandles(): Set<Int> = (bands.values + plain.values + pooled.values + free +
        listOf(empty) + textures.heldTextureHandles()).filter { it != 0 }.toSet()

    /** Actual resident watercolor tile targets only; image textures and non-tile GL objects are excluded. */
    internal fun heldWorkingTileBytes(): Map<Int, Long> {
        val tileBytes = Math.multiplyExact(Math.multiplyExact(size.toLong(), size.toLong()), 8L)
        val result = LinkedHashMap<Int, Long>()
        fun include(texture: Int) { if (texture != 0) result[texture] = tileBytes }
        bands.values.forEach(::include)
        plain.values.forEach(::include)
        pooled.values.forEach(::include)
        free.forEach(::include)
        include(empty)
        return Collections.unmodifiableMap(result)
    }

    fun forget() { bands.clear(); plain.clear(); pooled.clear(); free.clear(); textures.forget(); empty=0; fbo=0; quad=0; instance=0; vao=0; finishVao=0; unitVbo=0; dab=null; finish=null; finishFramed=null; mix=null; images=null; paper=null; paperTextures=emptySet() }
    fun release() {
        val names=(bands.values+plain.values+pooled.values+free+listOf(empty)).filter { it!=0 }.distinct().toIntArray()
        if(names.isNotEmpty())GLES30.glDeleteTextures(names.size,names,0)
        textures.release(); dab?.release(); finish?.release(); finishFramed?.release(); mix?.release()
        if(fbo!=0) GLES30.glDeleteFramebuffers(1,intArrayOf(fbo),0)
        if(quad!=0)GLES30.glDeleteBuffers(3,intArrayOf(quad,instance,unitVbo),0)
        if(vao!=0)GLES30.glDeleteVertexArrays(2,intArrayOf(vao,finishVao),0)
        forget()
    }
    private fun tile(): Int = (free.removeLastOrNull() ?: createTile()).also {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo); attach(it); GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0f,0f,0f,0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }
    private fun createTile(): Int {
        val ids=IntArray(1); GLES30.glGenTextures(1,ids,0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,ids[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,internal,size,size,0,GLES30.GL_RGBA,type,null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo); attach(ids[0]); GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)==GLES30.GL_FRAMEBUFFER_COMPLETE) { "Watercolor target is incomplete" }
        GLES30.glClearColor(0f,0f,0f,0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        return ids[0]
    }
    private fun attach(texture:Int)=GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,texture,0)
    private fun bind(unit:Int,texture:Int) { GLES30.glActiveTexture(GLES30.GL_TEXTURE0+unit); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture) }
    companion object {
        private fun floats(n:Int):FloatBuffer=ByteBuffer.allocateDirect(n*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private fun decode(value:Float):Float=if(value<=.04045f)value/12.92f else ((value+.055f)/1.055f).pow(2.4f)
    }
}
