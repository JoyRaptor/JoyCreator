package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.media.UniformSink
import cc.joycreator.joybrush.core.brush.PaperResponse
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
        gl.uniform1f(loc("u_minPx"), look.tip.minPx)
        setGrainUniforms(gl, loc, look, newest)
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
    fun pack(list: List<Dab>, look: StrokeLook): FloatBuffer {
        val need = list.size * FLOATS
        if (instanceData.capacity() < need) instanceData = newFloats(need * 2)
        instanceData.clear()
        for (d in list) {
            instanceData.put(d.x).put(d.y).put(d.radius).put(d.angle).put(d.flow).put(d.cap).put(d.travelX).put(d.travelY)
            putContact(instanceData, d, look)
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
        gl.uniform2f(loc("u_tileOrigin"), originDocX, originDocY)
        val data = pack(list, look)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, list.size * BYTES, data, GLES30.GL_STREAM_DRAW)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, list.size)
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
