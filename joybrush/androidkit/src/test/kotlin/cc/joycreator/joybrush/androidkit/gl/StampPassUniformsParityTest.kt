package cc.joycreator.joybrush.androidkit.gl

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
import java.util.TreeMap
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * H1 (VECTOR_RENDER_DESIGN §8): moving the stamp code out of [GlPaintEngine] into [StampPass] left every uniform, every
 * bound grain picture and every packed instance float exactly as it was.
 *
 * There is no GPU here, so GL is a recorder ([Rec]) holding what a driver holds: each uniform's last value and the texture
 * on each unit. The REFERENCE ([Legacy]) is a verbatim copy of the engine's code before the move (its pen-down look
 * resolution from `beginStroke`, the tip uniforms and `setGrainUniforms` / `setPaperSurfaceUniforms` /
 * `setPaperResponseUniforms` from `addDabs`, and `fillInstances` / `putContact`), with only `GLES30.glUniform*(program.loc
 * (name), …)` spelled as the recorder's call. Both run the same randomised strokes; after each batch the two recorders and
 * the two instance buffers must be identical bit for bit. The one new uniform, `u_scale`, is excluded from the comparison
 * and asserted to be 1 on the live path, which the shader treats exactly as unset.
 *
 * A CLASS on purpose: JUnit runs methods on an instance.
 */
class StampPassUniformsParityTest {

    private class Rec : UniformSink {
        val values = TreeMap<String, String>()
        val units = IntArray(5) { -1 }
        private val names = HashMap<Int, String>()
        fun loc(name: String): Int = name.hashCode().also { names[it] = name }
        private fun put(loc: Int, v: String) { values[names.getValue(loc)] = v }
        override fun uniform1f(loc: Int, a: Float) = put(loc, "1f ${a.toRawBits()}")
        override fun uniform2f(loc: Int, a: Float, b: Float) = put(loc, "2f ${a.toRawBits()} ${b.toRawBits()}")
        override fun uniform3f(loc: Int, a: Float, b: Float, c: Float) = put(loc, "3f ${a.toRawBits()} ${b.toRawBits()} ${c.toRawBits()}")
        override fun uniform4f(loc: Int, a: Float, b: Float, c: Float, d: Float) =
            put(loc, "4f ${a.toRawBits()} ${b.toRawBits()} ${c.toRawBits()} ${d.toRawBits()}")
        override fun uniform1i(loc: Int, a: Int) = put(loc, "1i $a")
        override fun bindTexture(unit: Int, tex: Int) { units[unit] = tex }
        fun snapshot(exclude: Set<String> = emptySet()): String =
            values.filterKeys { it !in exclude }.entries.joinToString("\n") { "${it.key} = ${it.value}" } +
                "\nunits=" + units.mapIndexed { i, v -> if ((i == 3 && "u_sampleEnabled" in exclude) || (i == 4 && "u_dualMode" in exclude)) -1 else v }.joinToString(",")
    }

    /** Grain pictures: every asset loads to a stable id except the ones named missing. */
    private class Grains : StampPass.GrainSource {
        override val placeholder = 7
        override fun textureFor(asset: String): Int? = if (asset.startsWith("missing")) null else 100 + (asset.hashCode() and 0xFF)
        override fun paperSize(asset: String): Int = 64 + (asset.length % 5) * 64
    }

    // ── the reference: the engine's code before the move, verbatim apart from the GL spelling ──

    private class Legacy(private val grains: Grains) {
        var tip = TipShape()
        var grain = GrainMath.StrokeGrain(GrainMath.GrainUniforms.OFF, GrainMath.GrainUniforms.OFF)
        var tipGrainTex = 0
        var paperGrainTex = 0
        var strokeSurface: SurfaceEntry? = null
        var strokePaperScale = 1f
        var strokePaperResponse = PaperResponse()
        var strokePaperInfluence = 0f
        var strokeTileRect: RectPx? = null
        var fluidTexture: Int? = null
        var instanceData: FloatBuffer = newFloats(17 * 256)

        fun begin(tip: TipShape, grain: GrainMath.StrokeGrain, p: ResolvedPaper?, tuftResponse: PaperResponse?,
                  tuftPitch: Float?, tile: RectPx?, fluid: Int?) {
            this.tip = tip
            strokeTileRect = tile
            fluidTexture = fluid
            val tuft = tuftResponse
            val tipTex = if (grain.tip.enabled) grains.textureFor(grain.tip.asset) else null
            val surface = p?.surface
            strokeSurface = surface
            strokePaperScale = p?.scale ?: 1f
            strokePaperResponse = if (tuft != null) tuft else grain.paperResponse
            if (p == null && strokePaperResponse.isDefault && (grain.paper.enabled || (tuftPitch ?: 0f)>0f))
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
        }

        fun addDabsUniforms(r: Rec, size: Int, dabs: List<Dab>) {
            r.uniform1f(r.loc("u_tileSize"), size.toFloat())
            r.uniform1f(r.loc("u_aspect"), tip.aspect)
            r.uniform1f(r.loc("u_corner"), tip.corner)
            r.uniform1f(r.loc("u_taper"), tip.taper)
            r.uniform1f(r.loc("u_hardness"), tip.hardness)
            r.uniform1f(r.loc("u_minPx"), tip.minPx)
            setGrainUniforms(r, dabs[dabs.size - 1])
        }

        fun setPaperSurfaceUniforms(r: Rec) {
            val tilePaper = strokeTileRect
            r.uniform4f(r.loc("u_tilePaperRect"),
                tilePaper?.x?.toFloat() ?: 0f, tilePaper?.y?.toFloat() ?: 0f,
                tilePaper?.w?.toFloat() ?: 0f, tilePaper?.h?.toFloat() ?: 0f)
            r.uniform1i(r.loc("u_paperSurface"), 1)
            r.uniform1f(r.loc("u_paperTexelPx"), (strokeSurface?.texelPx ?: GrainMath.SURFACE_TEXEL_PX)*strokePaperScale)
            r.uniform1f(r.loc("u_paperSize"), strokeSurface?.size?.toFloat() ?: GrainMath.SURFACE_SIZE)
            r.uniform1f(r.loc("u_paperHexTexels"), strokeSurface?.hexTexels ?: GrainMath.SURFACE_HEX_TEXELS)
            r.uniform1f(r.loc("u_paperSlopeRange"), strokeSurface?.slopeRange ?: GrainMath.SURFACE_SLOPE_RANGE)
            r.uniform1f(r.loc("u_paperHeightMean"), strokeSurface?.heightMean ?: 0.5f)
            val fluid = fluidTexture
            r.uniform1i(r.loc("u_paperFluid"), 2)
            r.bindTexture(2, fluid ?: grains.placeholder)
            val surface = strokeSurface
            r.uniform1f(r.loc("u_paperFluidTexelPx"), if (fluid != null && surface?.fluid != null) surface.fluidTexelPx * strokePaperScale else 0f)
            r.uniform1f(r.loc("u_paperFluidSize"), surface?.fluid?.let { grains.paperSize(it).toFloat() } ?: 1f)
            r.uniform1f(r.loc("u_paperFluidHexTexels"), surface?.fluidHexTexels ?: 150f)
            r.uniform1i(r.loc("u_paperRotatable"), if (strokeSurface?.rotatable ?: GrainMath.SURFACE_ROTATABLE) 1 else 0)
        }

        fun setPaperResponseUniforms(r: Rec) {
            r.uniform1f(r.loc("u_paperInfluence"),strokePaperInfluence)
            r.uniform1f(r.loc("u_paperDirectional"),strokePaperResponse.directional)
            r.uniform1f(r.loc("u_paperWet"),strokePaperResponse.wet)
        }

        fun setGrainUniforms(r: Rec, newest: Dab) {
            setPaperResponseUniforms(r)
            r.uniform1i(r.loc("u_tipGrain"), 0)
            setPaperSurfaceUniforms(r)
            r.bindTexture(0, tipGrainTex)
            r.bindTexture(1, paperGrainTex)
            val tg = grain.tip
            val pg = grain.paper
            r.uniform1f(r.loc("u_tipGrainPitchPx"), tg.pitchPx)
            r.uniform1f(r.loc("u_tipDepth"), tg.depth)
            r.uniform1f(r.loc("u_tipEdge"), tg.edge)
            r.uniform1f(r.loc("u_tipTiltGradient"), tg.tiltGradient)
            r.uniform1f(r.loc("u_tipRadial"), tg.radial)
            r.uniform1f(r.loc("u_paperGrainPitchPx"), pg.pitchPx)
            r.uniform1f(r.loc("u_paperDepth"), pg.depth)
            r.uniform1f(r.loc("u_paperEdge"), pg.edge)
            r.uniform1f(r.loc("u_paperTiltGradient"), pg.tiltGradient)
            r.uniform1f(r.loc("u_paperRadial"), pg.radial)
            r.uniform1f(r.loc("u_tiltAmount"), GrainMath.tiltAmount(newest.tilt))
            r.uniform2f(r.loc("u_leanDir"), GrainMath.leanX(newest.azimuth), GrainMath.leanY(newest.azimuth))
        }

        private fun putContact(buffer: FloatBuffer, d: Dab) {
            fun resolved(value: Float, fallback: Float) = if (value.isFinite()) value else fallback
            val live = d.aspect.isFinite() || d.hardness.isFinite() || d.tipDepth.isFinite() || d.paperDepth.isFinite() || d.anchor.isFinite()
            buffer.put(resolved(d.aspect, tip.aspect)).put(resolved(d.hardness, tip.hardness))
                .put(resolved(d.tipDepth, grain.tip.depth)).put(resolved(d.paperDepth, grain.paper.depth))
                .put(resolved(d.anchor, 0f)).put(GrainMath.tiltAmount(d.tilt))
                .put(GrainMath.leanX(d.azimuth)).put(GrainMath.leanY(d.azimuth)).put(if (live) 1f else 0f)
        }

        fun fillInstances(list: List<Dab>): FloatBuffer {
            val need = list.size * 17
            if (instanceData.capacity() < need) instanceData = newFloats(need * 2)
            instanceData.clear()
            for (d in list) {
                instanceData.put(d.x).put(d.y).put(d.radius).put(d.angle).put(d.flow).put(d.cap).put(d.travelX).put(d.travelY)
                putContact(instanceData, d)
            }
            instanceData.flip()
            return instanceData
        }

        private fun newFloats(n: Int): FloatBuffer =
            ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    // ── random strokes ──

    private fun maybeNaN(r: Random, v: Float) = if (r.nextInt(3) == 0) Float.NaN else v

    private fun grainOf(r: Random): GrainMath.GrainUniforms = if (r.nextBoolean()) GrainMath.GrainUniforms.OFF else
        GrainMath.GrainUniforms(r.nextFloat() * 8f + 0.5f, r.nextFloat(), r.nextFloat(), r.nextFloat() * 2f - 1f, r.nextFloat(),
            if (r.nextInt(4) == 0) "missing_${r.nextInt(9)}.png" else "grain_${r.nextInt(9)}.png")

    private fun paperOf(r: Random): ResolvedPaper? = when (r.nextInt(4)) {
        0 -> null
        else -> ResolvedPaper(
            surface = if (r.nextInt(4) == 0) null else SurfaceEntry("s${r.nextInt(5)}", "S", "surf_${r.nextInt(5)}.png",
                256 shl r.nextInt(3), r.nextFloat() * 4f + 0.5f, r.nextFloat() + 0.01f, 16f + r.nextFloat() * 100f, r.nextBoolean(),
                heightMean = r.nextFloat(), fluid = if (r.nextBoolean()) "fluid_${r.nextInt(3)}.png" else null,
                fluidTexelPx = r.nextFloat() * 8f + 1f, fluidHexTexels = 20f + r.nextFloat() * 300f),
            look = null, baseArgb = -1, scale = 0.25f + r.nextFloat() * 3.75f, show = r.nextFloat(),
            bite = if (r.nextInt(5) == 0) 0f else r.nextFloat(), light = r.nextBoolean())
    }

    private fun responseOf(r: Random) = if (r.nextBoolean()) PaperResponse() else PaperResponse(r.nextFloat(), r.nextFloat(), r.nextFloat())

    private fun dabsOf(r: Random): List<Dab> = List(1 + r.nextInt(40)) {
        Dab(r.nextFloat() * 300f, r.nextFloat() * 300f, r.nextFloat() * 20f, r.nextFloat() * 6f, r.nextFloat(), r.nextFloat(),
            pressure = r.nextFloat(), tilt = maybeNaN(r, r.nextFloat()), azimuth = maybeNaN(r, r.nextFloat() * 6f - 3f),
            travelX = r.nextFloat(), travelY = r.nextFloat(), travelKnown = r.nextBoolean(),
            aspect = maybeNaN(r, r.nextFloat() * 2f - 1f), hardness = maybeNaN(r, r.nextFloat()), tipDepth = maybeNaN(r, r.nextFloat()),
            paperDepth = maybeNaN(r, r.nextFloat()), anchor = maybeNaN(r, r.nextFloat()))
    }

    private fun floats(b: FloatBuffer): List<Int> = List(b.limit()) { b.get(it).toRawBits() }

    @Test
    fun theMoveKeepsEveryUniformEveryPictureAndEveryInstanceFloat() {
        val grains = Grains()
        val legacy = Legacy(grains)
        val pass = StampPass(grains)
        val old = Rec()
        val new = Rec()
        val rnd = Random(20261011)
        var batches = 0
        var paperBound = 0
        repeat(600) { stroke ->
            val tip = TipShape(rnd.nextFloat() * 2f - 1f, 0.5f + rnd.nextFloat() * 8f, rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat() * 2f)
            val grain = GrainMath.StrokeGrain(grainOf(rnd), grainOf(rnd), responseOf(rnd))
            val paper = paperOf(rnd)
            val tuft = rnd.nextInt(4) == 0
            val tuftResponse = if (tuft) responseOf(rnd) else null
            val tuftPitch = if (tuft) rnd.nextFloat() * 3f else null
            val tile = if (rnd.nextInt(3) == 0) RectPx(rnd.nextInt(500), rnd.nextInt(500), 64 + rnd.nextInt(500), 64 + rnd.nextInt(500)) else null
            val fluid = if (rnd.nextBoolean()) 40 + rnd.nextInt(5) else null

            legacy.begin(tip, grain, paper, tuftResponse, tuftPitch, tile, fluid)
            val look = pass.lookFor(tip, grain, paper, tuftResponse, tuftPitch, tile, fluid)
            assertEquals(legacy.strokePaperInfluence.toRawBits(), look.paperInfluence.toRawBits(), "stroke $stroke: tuft reads this influence")

            repeat(1 + rnd.nextInt(3)) {
                val dabs = dabsOf(rnd)
                legacy.addDabsUniforms(old, 256, dabs)
                pass.setStrokeUniforms(new, new::loc, look, dabs[dabs.size - 1], 256, 1f)
                assertEquals(old.snapshot(), new.snapshot(exclude = setOf("u_scale", "u_tipProfile", "u_tipProfileB", "u_stampBaseColor", "u_sampleEnabled", "u_sampleMasks", "u_dualMode", "u_dualMask", "u_dualInvPeriod")), "stroke $stroke: uniforms and pictures")
                assertEquals("1f ${1f.toRawBits()}", new.values["u_scale"], "the live path draws at scale 1")
                assertEquals("1i 0", new.values["u_tipProfile"], "legacy strokes reset the profile mode")
                assertEquals("1i 0", new.values["u_tipProfileB"], "legacy strokes reset the second profile mode")
                assertEquals("1i 0", new.values["u_sampleEnabled"], "legacy strokes reset image sampling")
                assertEquals("1i 0", new.values["u_dualMode"], "legacy strokes reset dual coverage")
                assertEquals(grains.placeholder,new.units[4])
                assertEquals(grains.placeholder, new.units[3], "the disabled image sampler never borrows a render target")
                assertEquals(floats(legacy.fillInstances(dabs)), floats(pass.pack(dabs, look)), "stroke $stroke: instance floats")
                batches++
                if (old.units[1] != grains.placeholder) paperBound++
            }
        }
        // Non-vacuous: the run really exercised real paper pictures, and the comparison can fail. A legacy engine that
        // forgot the tile-board rect writes a different u_tilePaperRect than the pass does.
        assertTrue(batches > 1000 && paperBound > 100, "batches $batches, with a paper picture bound $paperBound")
        val control = Legacy(grains)
        val tip = TipShape()
        val grain = GrainMath.StrokeGrain(GrainMath.GrainUniforms.OFF, GrainMath.GrainUniforms.OFF)
        control.begin(tip, grain, null, null, null, null, null)
        val c1 = Rec(); val c2 = Rec()
        val dabs = listOf(Dab(1f, 2f, 3f))
        control.addDabsUniforms(c1, 256, dabs)
        pass.setStrokeUniforms(c2, c2::loc, pass.lookFor(tip, grain, null, null, null, RectPx(1, 2, 3, 4), null), dabs[0], 256, 1f)
        assertEquals("1i 0", c2.values["u_tipProfile"], "the legacy control resets the profile mode")
        assertNotEquals(c1.snapshot(), c2.snapshot(exclude = setOf("u_scale", "u_tipProfile", "u_tipProfileB", "u_stampBaseColor", "u_sampleEnabled", "u_sampleMasks", "u_dualMode", "u_dualMask", "u_dualInvPeriod")), "the comparison sees a changed uniform")
    }
}
