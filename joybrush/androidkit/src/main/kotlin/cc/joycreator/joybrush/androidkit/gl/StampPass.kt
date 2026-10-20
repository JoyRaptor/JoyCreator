package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.media.UniformSink
import cc.joycreator.joybrush.core.brush.PaperResponse
import cc.joycreator.joybrush.core.brush.TipProfile
import cc.joycreator.joybrush.core.brush.TipProfileSpec
import cc.joycreator.joybrush.core.brush.TipProfiles
import cc.joycreator.joybrush.core.brush.QuadMath
import cc.joycreator.joybrush.core.brush.ColorDab
import cc.joycreator.joybrush.core.brush.SampledTipSet
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.paper.ResolvedPaper
import cc.joycreator.joybrush.core.paper.SurfaceEntry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The one instanced-dab draw (VECTOR_RENDER_DESIGN §8, H1). The live stroke and line rendering share it, so a line
 * re-rendered later goes through the same uniforms, the same instance layout and the same shader as the stroke drawn
 * live: "same brushes, same maths" (blueprint §2) by construction rather than by keeping two copies in step.
 *
 * Moved out of [GlPaintEngine] verbatim: the pen-down look resolution (once `beginStroke`'s middle), the three uniform
 * setters (`setGrainUniforms`, `setPaperSurfaceUniforms`, `setPaperResponseUniforms`) and the instance packer
 * (`fillInstances`, `putContact`). Every value the engine used to read from its stroke fields now comes from one
 * [StrokeLook], frozen at pen-down; `StampPassUniformsParityTest` proves the move left every uniform and every packed
 * float as it was. The only addition is `u_scale` (S2), written 1 by the live path, which the shader treats exactly as
 * unset.
 *
 * GL thread only. Allocation-free per draw: the instance buffer grows by doubling and is reused.
 */
internal class StampPass(private val grains: GrainSource) {

    /** The grain pictures a pass binds: [GrainTextures] on the phone, a fake in the JVM tests. */
    interface GrainSource {
        /** The 1×1 texture bound wherever a picture is missing or off (never "paint everywhere"). */
        val placeholder: Int
        /** The texture for a grain asset, or null when it cannot be loaded. */
        fun textureFor(asset: String): Int?
        /** The pixel size of a paper-folder asset (the fluid map's), 1 when unknown. */
        fun paperSize(asset: String): Int
    }

    /**
     * Everything the dab program reads that is fixed for one stroke or one line: what `beginStroke` used to resolve into
     * the engine's fields. A line keeps its own (D5's frozen brush plus the document's paper), so a re-render months later
     * binds what the live stroke bound.
     */
    class StrokeLook(
        val tip: TipShape,
        /** The resolved grain: a tip or paper grain whose picture is missing is already OFF here. */
        val grain: GrainMath.StrokeGrain,
        val tipGrainTex: Int,
        val paperGrainTex: Int,
        val surface: SurfaceEntry?,
        val paperScale: Float,
        val paperResponse: PaperResponse,
        val paperInfluence: Float,
        /** A tile board's rect, so translated copies sample the same periodic surface; null on an ordinary board. */
        val tilePaperRect: RectPx?,
        val fluidTexture: Int?,
        val baseColor: ColorDab = ColorDab(0f, 0f, 0f),
        val sampledSet: SampledTipSet? = null,
        val sampledBinding: SampledAtlasBinding? = null,
    )

    /**
     * The look of a stroke begun now: `GlPaintEngine.beginStroke`'s resolution, verbatim. [tuftResponse] is a tuft
     * stroke's paper response (it replaces the brush grain's), and [tuftPaperPitchPx] its tooth pitch.
     */
    fun lookFor(
        tip: TipShape,
        grain: GrainMath.StrokeGrain,
        paper: ResolvedPaper?,
        tuftResponse: PaperResponse?,
        tuftPaperPitchPx: Float?,
        tilePaperRect: RectPx?,
        fluidTexture: Int?,
        baseColor: ColorDab = ColorDab(0f, 0f, 0f),
        sampledSet: SampledTipSet? = tip.sampled?.set,
        sampledBinding: SampledAtlasBinding? = null,
    ): StrokeLook {
        // A grain whose picture is missing is drawn as OFF: a missing picture must never read as "paint
        // everywhere". The picture is bound per batch in addDabs, but resolved here, once per stroke.
        val tipTex = if (grain.tip.enabled) grains.textureFor(grain.tip.asset) else null
        val surface = paper?.surface
        var response = tuftResponse ?: grain.paperResponse
        // Old direct engine callers have grain settings but no document/response section.
        if (paper == null && response.isDefault && (grain.paper.enabled || (tuftPaperPitchPx ?: 0f) > 0f))
            response = PaperResponse(influence = 1f)
        val influence = response.influence * (paper?.bite ?: 1f)
        val documentGrain = documentPaperGrain(grain.paper, paper, response)
        val paperTex = if (documentGrain.enabled) grains.textureFor(documentGrain.asset) else null
        return StrokeLook(
            tip = tip,
            grain = GrainMath.StrokeGrain(
                tip = if (tipTex != null) grain.tip else GrainMath.GrainUniforms.OFF,
                paper = if (paperTex != null) documentGrain else GrainMath.GrainUniforms.OFF,
                paperResponse = response,
            ),
            tipGrainTex = tipTex ?: grains.placeholder,
            paperGrainTex = paperTex ?: grains.placeholder,
            surface = surface,
            paperScale = paper?.scale ?: 1f,
            paperResponse = response,
            paperInfluence = influence,
            tilePaperRect = tilePaperRect,
            fluidTexture = fluidTexture,
            baseColor = baseColor,
            sampledSet = sampledSet,
            sampledBinding = sampledBinding,
        )
    }

    // ── uniforms ─────────────────────────────────────────────────────────────

    /**
     * The per-batch uniforms of the stamp program: the target, the tip, the grain. [targetPx] is the target's size in
     * texels and [scale] its texels per document px (1 for the live stroke).
     */
    fun setStrokeUniforms(gl: UniformSink, loc: (String) -> Int, look: StrokeLook, newest: Dab, targetPx: Int, scale: Float) {
        gl.uniform1f(loc("u_tileSize"), targetPx.toFloat())
        gl.uniform1f(loc("u_scale"), scale)
        gl.uniform1f(loc("u_aspect"), look.tip.aspect)
        gl.uniform1f(loc("u_corner"), look.tip.corner)
        gl.uniform1f(loc("u_taper"), look.tip.taper)
        gl.uniform1f(loc("u_hardness"), look.tip.hardness)
        gl.uniform1f(loc("u_minPx"), (newest.sampled ?: look.tip.sampled)?.minPx ?: look.tip.minPx)
        gl.uniform3f(loc("u_stampBaseColor"), look.baseColor.r, look.baseColor.g, look.baseColor.b)
        setProfileUniforms(gl, loc, newest.profile ?: look.tip.profile)
        setProfileUniforms(gl, loc, if (newest.quad != null) newest.profileB else look.tip.profileB, secondary = true)
        setGrainUniforms(gl, loc, look, newest)
        val binding = look.sampledBinding
        gl.uniform1i(loc("u_sampleEnabled"), if (binding != null) 1 else 0)
        gl.uniform1i(loc("u_sampleMasks"), 3)
        gl.bindTexture(3, binding?.texture ?: grains.placeholder)
        if (binding != null) for (i in 0 until binding.size) {
            val rect = binding.rect(i)
            gl.uniform4f(loc("u_sampleRects[$i]"), rect.u0, rect.v0, rect.u1, rect.v1)
        }
    }

    private var cachedProfile: TipProfileSpec? = null
    private var profileSamples: FloatArray? = null
    private var cachedProfileB: TipProfileSpec? = null
    private var profileSamplesB: FloatArray? = null

    /** Reset even for legacy strokes so a preceding custom brush cannot leak its profile. */
    internal fun setProfileUniforms(gl: UniformSink, loc: (String) -> Int, profile: TipProfileSpec?, secondary: Boolean = false) {
        val suffix = if (secondary) "B" else ""
        gl.uniform1i(loc("u_tipProfile$suffix"), profile?.kind?.code ?: 0)
        if (profile?.kind != TipProfile.custom) return
        if (secondary && cachedProfileB !== profile) {
            cachedProfileB = profile
            profileSamplesB = TipProfiles.samples(profile)
        } else if (!secondary && cachedProfile !== profile) {
            cachedProfile = profile
            profileSamples = TipProfiles.samples(profile)
        }
        val values = (if (secondary) profileSamplesB else profileSamples)!!
        for (i in 0 until 64) {
            val k = i * 4
            gl.uniform4f(loc("u_tipProfileLut$suffix[$i]"), values[k], values[k + 1], values[k + 2], values[k + 3])
        }
    }

    /** JB-9.03: the fixed document surface; both brush programs bind it on unit 1. */
    fun setPaperSurfaceUniforms(gl: UniformSink, loc: (String) -> Int, look: StrokeLook) {
        // Frozen at pen-down; translated copies must sample the same periodic surface.
        // Always reset this uniform so a later ordinary stroke keeps the canonical paper.
        val tilePaper = look.tilePaperRect
        gl.uniform4f(loc("u_tilePaperRect"),
            tilePaper?.x?.toFloat() ?: 0f, tilePaper?.y?.toFloat() ?: 0f,
            tilePaper?.w?.toFloat() ?: 0f, tilePaper?.h?.toFloat() ?: 0f)
        // Brush deposition uses document floats (accurate to +/-1e6 px); only display uses local frames.
        gl.uniform1i(loc("u_paperSurface"), 1)
        gl.uniform1f(loc("u_paperTexelPx"), (look.surface?.texelPx ?: GrainMath.SURFACE_TEXEL_PX) * look.paperScale)
        gl.uniform1f(loc("u_paperSize"), look.surface?.size?.toFloat() ?: GrainMath.SURFACE_SIZE)
        gl.uniform1f(loc("u_paperHexTexels"), look.surface?.hexTexels ?: GrainMath.SURFACE_HEX_TEXELS)
        gl.uniform1f(loc("u_paperSlopeRange"), look.surface?.slopeRange ?: GrainMath.SURFACE_SLOPE_RANGE)
        gl.uniform1f(loc("u_paperHeightMean"), look.surface?.heightMean ?: 0.5f)
        // The fluid map on unit 2, always bound (an unset sampler reads unit 0, which may be the tile being drawn).
        val fluid = look.fluidTexture
        gl.uniform1i(loc("u_paperFluid"), 2)
        gl.bindTexture(2, fluid ?: grains.placeholder)
        val surface = look.surface
        gl.uniform1f(loc("u_paperFluidTexelPx"), if (fluid != null && surface?.fluid != null) surface.fluidTexelPx * look.paperScale else 0f)
        gl.uniform1f(loc("u_paperFluidSize"), surface?.fluid?.let { grains.paperSize(it).toFloat() } ?: 1f)
        gl.uniform1f(loc("u_paperFluidHexTexels"), surface?.fluidHexTexels ?: 150f)
        gl.uniform1i(loc("u_paperRotatable"), if (look.surface?.rotatable ?: GrainMath.SURFACE_ROTATABLE) 1 else 0)
    }

    /** How much the stroke feels the document paper (JB-9.08). */
    fun setPaperResponseUniforms(gl: UniformSink, loc: (String) -> Int, look: StrokeLook) {
        gl.uniform1f(loc("u_paperInfluence"), look.paperInfluence)
        gl.uniform1f(loc("u_paperDirectional"), look.paperResponse.directional)
        gl.uniform1f(loc("u_paperWet"), look.paperResponse.wet)
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
     * These two are the legacy fallback only: a v7 "live" dab carries its own tilt and lean (S1, `putContact`).
     */
    fun setGrainUniforms(gl: UniformSink, loc: (String) -> Int, look: StrokeLook, newest: Dab) {
        setPaperResponseUniforms(gl, loc, look)
        gl.uniform1i(loc("u_tipGrain"), 0)
        setPaperSurfaceUniforms(gl, loc, look)
        gl.bindTexture(0, look.tipGrainTex)
        gl.bindTexture(1, look.paperGrainTex)
        val tg = look.grain.tip
        val pg = look.grain.paper
        gl.uniform1f(loc("u_tipGrainPitchPx"), tg.pitchPx)
        gl.uniform1f(loc("u_tipDepth"), tg.depth)
        gl.uniform1f(loc("u_tipEdge"), tg.edge)
        gl.uniform1f(loc("u_tipTiltGradient"), tg.tiltGradient)
        gl.uniform1f(loc("u_tipRadial"), tg.radial)
        gl.uniform1f(loc("u_paperGrainPitchPx"), pg.pitchPx)
        gl.uniform1f(loc("u_paperDepth"), pg.depth)
        gl.uniform1f(loc("u_paperEdge"), pg.edge)
        gl.uniform1f(loc("u_paperTiltGradient"), pg.tiltGradient)
        gl.uniform1f(loc("u_paperRadial"), pg.radial)
        gl.uniform1f(loc("u_tiltAmount"), GrainMath.tiltAmount(newest.tilt))
        gl.uniform2f(loc("u_leanDir"), GrainMath.leanX(newest.azimuth), GrainMath.leanY(newest.azimuth))
    }

    // ── instances ────────────────────────────────────────────────────────────

    private var instanceData: FloatBuffer = newFloats(FLOATS * 256)

    /**
     * The nine contact floats of one dab (S6: the ONE place the layout is written). Live v7 contact values win; a legacy
     * dab falls back to the stroke's tip and grain depth, and its tilt and lean still travel per dab.
     */
    fun putContact(buffer: FloatBuffer, d: Dab, look: StrokeLook) {
        fun resolved(value: Float, fallback: Float) = if (value.isFinite()) value else fallback
        val live = d.aspect.isFinite() || d.hardness.isFinite() || d.tipDepth.isFinite() || d.paperDepth.isFinite() || d.anchor.isFinite()
        buffer.put(resolved(d.aspect, look.tip.aspect)).put(resolved(d.hardness, look.tip.hardness))
            .put(resolved(d.tipDepth, look.grain.tip.depth)).put(resolved(d.paperDepth, look.grain.paper.depth))
            .put(resolved(d.anchor, 0f)).put(GrainMath.tiltAmount(d.tilt))
            .put(GrainMath.leanX(d.azimuth)).put(GrainMath.leanY(d.azimuth)).put(if (live) 1f else 0f)
    }

    /**
     * [list] packed as [FLOATS] floats per dab, ready to upload: x, y, radius, angle, flow, cap, travel x/y, then
     * [putContact]'s nine. Returned flipped; valid until the next call.
     */
    fun pack(list: List<Dab>, look: StrokeLook): FloatBuffer = packInternal(list, look, false)

    /** The original interleaved prefix is untouched; the optional tail is a separate attribute. */
    internal fun packForDraw(list: List<Dab>, look: StrokeLook): FloatBuffer =
        packInternal(list, look, hasFade(list, look), hasQuad(list, look), list.any { it.color != null }, hasSamples(list, look))

    private fun hasSamples(list: List<Dab>, look: StrokeLook): Boolean =
        look.tip.sampled != null || list.any { it.sampled != null }

    private fun hasQuad(list: List<Dab>, look: StrokeLook): Boolean =
        look.tip.quad != null || list.any { it.quad != null }

    private fun hasFade(list: List<Dab>, look: StrokeLook): Boolean =
        look.tip.fadeAmount > 0f || list.any { it.fadeAmount.isFinite() && it.fadeAmount > 0f }

    private fun packInternal(list: List<Dab>, look: StrokeLook, fade: Boolean, quad: Boolean = false, color: Boolean = false, sampled: Boolean = false): FloatBuffer {
        val need = list.size * (FLOATS + (if (fade) 2 else 0) + (if (quad) 12 else 0) + (if (color) 4 else 0) + (if (sampled) 4 else 0))
        if (instanceData.capacity() < need) instanceData = newFloats(need * 2)
        instanceData.clear()
        for (d in list) {
            instanceData.put(d.x).put(d.y).put(d.radius).put(d.angle).put(d.flow).put(d.cap).put(d.travelX).put(d.travelY)
            putContact(instanceData, d, look)
        }
        if (fade) for (d in list) {
            instanceData.put(if (d.fadeAmount.isFinite()) d.fadeAmount.coerceIn(0f, 1f) else look.tip.fadeAmount)
                .put(if (d.fadeAngle.isFinite()) d.fadeAngle else look.tip.fadeAngle)
        }
        if (quad) for (d in list) {
            val shape = (d.quad ?: look.tip.quad)?.let { QuadMath.prepare(it) }
            for (i in 0 until 4) {
                instanceData.put(if (shape != null && i < shape.count) shape.x(i) else 0f)
                    .put(if (shape != null && i < shape.count) shape.y(i) else 0f)
            }
            val kind = when {
                shape == null -> 0f
                shape.empty -> 1f
                shape.circle -> 5f
                else -> shape.count.toFloat()
            }
            instanceData.put(shape?.roundness ?: 0f).put(shape?.normalizationScale ?: 1f)
                .put(if (d.quad != null) d.profileMix else look.tip.profileMix).put(kind)
        }
        if (color) for (d in list) {
            val ink = d.color
            require(ink == null || (ink.r in 0f..1f && ink.g in 0f..1f && ink.b in 0f..1f)) {
                "A colored dab needs finite straight RGB in 0..1"
            }
            instanceData.put(ink?.r ?: 0f).put(ink?.g ?: 0f).put(ink?.b ?: 0f).put(if (ink == null) 0f else 1f)
        }
        if (sampled) for (d in list) {
            val image = d.sampled ?: look.tip.sampled
            instanceData.put(image?.let { it.index + 1f } ?: 0f)
                .put(image?.mask?.width?.toFloat() ?: 0f).put(image?.mask?.height?.toFloat() ?: 0f)
                .put(image?.minPx ?: 0f)
        }
        instanceData.flip()
        return instanceData
    }

    // ── drawing ──────────────────────────────────────────────────────────────

    /**
     * One target's dabs: sets the target origin, packs, uploads into [vbo] and draws. The caller has bound the program,
     * the stamp VAO and the target, and set the stroke uniforms ([setStrokeUniforms]) for this batch.
     */
    fun drawInto(gl: UniformSink, loc: (String) -> Int, look: StrokeLook, vbo: Int, originDocX: Float, originDocY: Float, list: List<Dab>) {
        val profile = list.lastOrNull()?.profile ?: look.tip.profile
        require(list.all { (it.profile ?: look.tip.profile) == profile }) { "A stamp batch must use one radial profile" }
        val profileB = list.lastOrNull()?.let { if (it.quad != null) it.profileB else look.tip.profileB }
        require(list.all { (if (it.quad != null) it.profileB else look.tip.profileB) == profileB }) { "A stamp batch must use one second radial profile" }
        gl.uniform2f(loc("u_tileOrigin"), originDocX, originDocY)
        val fade = hasFade(list, look)
        val quad = hasQuad(list, look)
        val color = list.any { it.color != null }
        val sampled = hasSamples(list, look)
        if (sampled) {
            val set = checkNotNull(look.sampledSet) { "Sampled stamps need their frozen image set" }
            val binding = checkNotNull(look.sampledBinding) { "Sampled stamps need their prepared atlas" }
            require(binding.size == set.size && list.all { d ->
                val image = d.sampled ?: look.tip.sampled
                image != null && image.set === set && image.minPx == (list.last().sampled ?: look.tip.sampled)?.minPx &&
                    binding.rect(image.index).imageWidth == image.mask.width && binding.rect(image.index).imageHeight == image.mask.height
            }) { "A sampled batch must use one prepared frozen image set" }
        }
        val data = packInternal(list, look, fade, quad, color, sampled)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.remaining() * 4, data, GLES30.GL_STREAM_DRAW)
        if (fade) {
            GLES30.glEnableVertexAttribArray(8)
            GLES30.glVertexAttribPointer(8, 2, GLES30.GL_FLOAT, false, 8, list.size * BYTES)
            GLES30.glVertexAttribDivisor(8, 1)
        } else {
            GLES30.glDisableVertexAttribArray(8)
            GLES30.glVertexAttrib2f(8, 0f, 0f)
        }
        for (attribute in 9..11) {
            if (quad) {
                GLES30.glEnableVertexAttribArray(attribute)
                GLES30.glVertexAttribPointer(attribute, 4, GLES30.GL_FLOAT, false, 48,
                    list.size * (BYTES + if (fade) 8 else 0) + (attribute - 9) * 16)
                GLES30.glVertexAttribDivisor(attribute, 1)
            } else {
                GLES30.glDisableVertexAttribArray(attribute)
                GLES30.glVertexAttrib4f(attribute, 0f, 0f, 0f, 0f)
            }
        }
        if (color) {
            GLES30.glEnableVertexAttribArray(12)
            GLES30.glVertexAttribPointer(12, 4, GLES30.GL_FLOAT, false, 16,
                list.size * (BYTES + (if (fade) 8 else 0) + (if (quad) 48 else 0)))
            GLES30.glVertexAttribDivisor(12, 1)
        } else {
            GLES30.glDisableVertexAttribArray(12)
            GLES30.glVertexAttrib4f(12, 0f, 0f, 0f, 0f)
        }
        if (sampled) {
            GLES30.glEnableVertexAttribArray(13)
            GLES30.glVertexAttribPointer(13, 4, GLES30.GL_FLOAT, false, 16,
                list.size * (BYTES + (if (fade) 8 else 0) + (if (quad) 48 else 0) + (if (color) 16 else 0)))
            GLES30.glVertexAttribDivisor(13, 1)
        } else {
            GLES30.glDisableVertexAttribArray(13)
            GLES30.glVertexAttrib4f(13, 0f, 0f, 0f, 0f)
        }
        try {
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, list.size)
        } finally {
            GLES30.glDisableVertexAttribArray(13)
            GLES30.glVertexAttribDivisor(13, 0)
            GLES30.glVertexAttrib4f(13, 0f, 0f, 0f, 0f)
            GLES30.glDisableVertexAttribArray(12)
            GLES30.glVertexAttribDivisor(12, 0)
            GLES30.glVertexAttrib4f(12, 0f, 0f, 0f, 0f)
            for (attribute in 9..11) {
                GLES30.glDisableVertexAttribArray(attribute)
                GLES30.glVertexAttribDivisor(attribute, 0)
                GLES30.glVertexAttrib4f(attribute, 0f, 0f, 0f, 0f)
            }
            GLES30.glDisableVertexAttribArray(8)
            GLES30.glVertexAttrib2f(8, 0f, 0f)
        }
    }

    /**
     * The §8 entry point for line rendering: [dabs] into the bound target of [targetPx] texels whose texel (0,0) is
     * document ([originDocX], [originDocY]), at [scale] texels per document px. Uses [program] and [vao] as the live
     * stroke does; blending is the caller's (ONE, ONE_MINUS_SRC_ALPHA for a stroke buffer).
     */
    fun draw(program: GlProgram, vao: Int, vbo: Int, look: StrokeLook, dabs: List<Dab>,
             originDocX: Float, originDocY: Float, scale: Float, targetPx: Int, gl: UniformSink) {
        if (dabs.isEmpty()) return
        program.use()
        setStrokeUniforms(gl, program::loc, look, dabs[dabs.size - 1], targetPx, scale)
        GLES30.glBindVertexArray(vao)
        drawInto(gl, program::loc, look, vbo, originDocX, originDocY, dabs)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    companion object {
        /** Floats per dab in the instance buffer (S6). */
        const val FLOATS = 17
        const val BYTES = FLOATS * 4

        private fun newFloats(n: Int): FloatBuffer =
            ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
}
