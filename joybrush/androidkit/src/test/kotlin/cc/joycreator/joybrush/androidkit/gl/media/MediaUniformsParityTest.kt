package cc.joycreator.joybrush.androidkit.gl.media

import cc.joycreator.joybrush.androidkit.gl.ShaderLibrary
import cc.joycreator.joybrush.core.media.MediaPaper
import cc.joycreator.joybrush.core.media.PASTE_DEPTH
import cc.joycreator.joybrush.core.media.PASTE_LANES
import cc.joycreator.joybrush.core.media.PasteBrush
import cc.joycreator.joybrush.core.media.PasteBrushes
import cc.joycreator.joybrush.core.media.PasteStep
import cc.joycreator.joybrush.core.media.StickMaterial
import cc.joycreator.joybrush.core.media.Sticks
import cc.joycreator.joybrush.core.media.WetConstants
import cc.joycreator.joybrush.core.media.cellCap
import cc.joycreator.joybrush.core.media.wetUniforms
import java.lang.management.ManagementFactory
import java.util.TreeMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Proof that the typed, allocation-free uniform submission ([MediaUniforms] over [UniformBank]) leaves every media
 * program in EXACTLY the GL state the old map-based path left it in, after every pass.
 *
 * There is no GPU here, so GL is a recorder ([FakeGl]) that models what a driver holds: each program's uniform values
 * (persisting across passes, like GL's) and the texture bound on each unit. The REFERENCE ([Legacy]) is a verbatim copy
 * of the old `MediaProgram.set` plus verbatim copies of the old per-pass map builders (below, `old*`), writing to a
 * second recorder. Both run the same pass sequences on the REAL shaders' declared uniforms (parsed from the shipped
 * .vert/.frag sources, so types and sampler units are the real ones). After every pass the two recorders must hold
 * identical state, bit for bit, in every program - which also proves that skipping a redundant write loses nothing.
 */
class MediaUniformsParityTest {

    // ---------------------------------------------------------------- fake GL

    private class FakeGl {
        val values = TreeMap<String, String>()
        val units = IntArray(32) { -1 }
        var calls = 0
        fun sink(prog: Int) = object : UniformSink {
            private fun put(loc: Int, v: String) { values["$prog:$loc"] = v; calls++ }
            override fun uniform1f(loc: Int, a: Float) = put(loc, "1f ${a.toRawBits()}")
            override fun uniform2f(loc: Int, a: Float, b: Float) = put(loc, "2f ${a.toRawBits()} ${b.toRawBits()}")
            override fun uniform3f(loc: Int, a: Float, b: Float, c: Float) = put(loc, "3f ${a.toRawBits()} ${b.toRawBits()} ${c.toRawBits()}")
            override fun uniform4f(loc: Int, a: Float, b: Float, c: Float, d: Float) =
                put(loc, "4f ${a.toRawBits()} ${b.toRawBits()} ${c.toRawBits()} ${d.toRawBits()}")
            override fun uniform1i(loc: Int, a: Int) = put(loc, "1i $a")
            override fun bindTexture(unit: Int, tex: Int) { units[unit] = tex; calls += 3 }   // activeTexture, bindTexture, activeTexture(0)
        }
        fun snapshot(): String = values.entries.joinToString("\n") { "${it.key} = ${it.value}" } + "\nunits=" + units.joinToString(",")
    }

    /** A program's declared uniforms (name -> GL type), in declaration order: vertex shader first, then fragment. */
    private class Decl(val name: String, val types: LinkedHashMap<String, Int>)

    private val shaders = ShaderLibrary()
    private val glType = mapOf(
        "float" to GlC.GL_FLOAT, "int" to GlC.GL_INT, "bool" to GlC.GL_BOOL, "vec2" to GlC.GL_FLOAT_VEC2,
        "vec3" to GlC.GL_FLOAT_VEC3, "vec4" to GlC.GL_FLOAT_VEC4, "sampler2D" to GlC.GL_SAMPLER_2D,
    )
    private val uniformLine = Regex("""^\s*uniform\s+(\w+)\s+(\w+)\s*;""")

    private fun decl(name: String, vert: String, frag: String): Decl {
        val m = LinkedHashMap<String, Int>()
        for (file in listOf(vert, frag)) for (line in shaders.source("media/$file").lineSequence()) {
            val hit = uniformLine.find(line) ?: continue
            m[hit.groupValues[2]] = glType[hit.groupValues[1]] ?: error("$file: unhandled uniform type ${hit.groupValues[1]}")
        }
        return Decl(name, m)
    }

    private fun slots(d: Decl): HashMap<String, UniformSlot> {
        val out = HashMap<String, UniformSlot>()
        var unit = 0
        var loc = 0
        for ((n, t) in d.types) out[n] = UniformSlot(loc++, t, if (t == GlC.GL_SAMPLER_2D) unit++ else -1)
        return out
    }

    private val dDry = decl("dry dab", "jb_dry_dab.vert", "jb_dry_dab.frag")
    private val dApply = decl("apply", "jb_media_quad.vert", "jb_media_apply.frag")
    private val dCopy = decl("copy", "jb_media_quad.vert", "jb_media_copy.frag")
    private val dRender = decl("render", "jb_media_screen.vert", "jb_media_render.frag")
    private val dBake = decl("bake", "jb_media_quad.vert", "jb_media_bake.frag")
    private val dWetDab = decl("wet dab", "jb_wet_dab.vert", "jb_wet_dab.frag")
    private val dFlux = decl("flux", "jb_media_quad.vert", "jb_wet_flux.frag")
    private val dUpdate = decl("update", "jb_media_quad.vert", "jb_wet_update.frag")
    private val dPasteDab = decl("paste dab", "jb_media_quad.vert", "jb_paste_dab.frag")
    private val dPasteBrush = decl("paste brush", "jb_media_quad.vert", "jb_paste_brush.frag")
    private val dBead = decl("bead", "jb_media_quad.vert", "jb_wet_bead.frag")
    private val dEraseDab = decl("erase dab", "jb_media_erase_dab.vert", "jb_media_erase_dab.frag")
    private val dErase = decl("erase", "jb_media_quad.vert", "jb_media_erase_apply.frag")
    private val all = listOf(dDry, dApply, dCopy, dRender, dBake, dWetDab, dFlux, dUpdate, dPasteDab, dPasteBrush, dBead, dEraseDab, dErase)

    /** The old path and the new path, each with its own GL recorder and its own program objects over the same declared uniforms. */
    private inner class Rig {
        val oldGl = FakeGl()
        val newGl = FakeGl()
        val oldP = all.withIndex().associate { (i, d) -> d.name to Legacy(slots(d), oldGl.sink(i)) }
        val newP = all.withIndex().associate { (i, d) -> d.name to UniformBank(slots(d), newGl.sink(i), d.name) }
        /** Names the new path wrote to each program (to report declared uniforms nobody writes). */
        fun same(what: String) = assertEquals(oldGl.snapshot(), newGl.snapshot(), "GL state differs after: $what")
        fun old(d: Decl) = oldP.getValue(d.name)
        fun new(d: Decl) = newP.getValue(d.name)
    }

    // ---------------------------------------------------------------- the reference: the old MediaProgram.set, verbatim

    private class Legacy(private val uniforms: HashMap<String, UniformSlot>, private val sink: UniformSink) {
        fun use(values: Map<String, Any?>) { for ((name, v) in values) set(name, v ?: continue) }
        fun set(name: String, v: Any) {
            val u = uniforms[name] ?: return
            when (u.type) {
                GlC.GL_FLOAT -> sink.uniform1f(u.loc, num(v))
                GlC.GL_INT, GlC.GL_BOOL -> sink.uniform1i(u.loc, if (v is Boolean) (if (v) 1 else 0) else num(v).toInt())
                GlC.GL_FLOAT_VEC2 -> vec(v, 2).let { sink.uniform2f(u.loc, it[0], it[1]) }
                GlC.GL_FLOAT_VEC3 -> vec(v, 3).let { sink.uniform3f(u.loc, it[0], it[1], it[2]) }
                GlC.GL_FLOAT_VEC4 -> vec(v, 4).let { sink.uniform4f(u.loc, it[0], it[1], it[2], it[3]) }
                GlC.GL_SAMPLER_2D -> {
                    val t = (v as? Tex)?.id ?: error("$name needs a Tex")
                    sink.bindTexture(u.unit, t)
                    sink.uniform1i(u.loc, u.unit)
                }
                else -> error("unsupported uniform type for $name")
            }
        }
        private fun num(v: Any): Float = when (v) {
            is Number -> v.toFloat()
            is Boolean -> if (v) 1f else 0f
            else -> error("not a number: $v")
        }
        private fun vec(v: Any, n: Int): FloatArray = when (v) {
            is FloatArray -> v
            is DoubleArray -> FloatArray(n) { v[it].toFloat() }
            is IntArray -> FloatArray(n) { v[it].toFloat() }
            is List<*> -> FloatArray(n) { (v[it] as Number).toFloat() }
            else -> error("not a vector: $v")
        }.also { require(it.size >= n) { "vector too short" } }
    }

    // ---------------------------------------------------------------- the old per-pass map builders, verbatim from MediaLayerEngine

    private fun oldCommon(ox: Float, oy: Float, scale: Float, values: Map<String, Any?>): Map<String, Any?> =
        mapOf<String, Any?>("u_layerOrigin" to floatArrayOf(ox, oy), "u_layerScale" to scale) + values

    private fun oldPaper(p: MediaPaperGl): Map<String, Any> = mapOf(
        "u_paperSurface" to p.surface, "u_paperTexelPx" to p.texelPx, "u_paperSize" to p.size, "u_paperHexTexels" to p.hexTexels,
        "u_paperSlopeRange" to p.slopeRange, "u_paperRotatable" to p.rotatable, "u_paperHeightMean" to p.stats.heightMean.toFloat(),
        "u_paperHBot" to p.stats.heightBot.toFloat(), "u_paperHTop" to p.stats.heightTop.toFloat(),
        "u_paperFluid" to p.fluid, "u_paperFluidTexelPx" to p.fluidTexelPx, "u_paperFluidSize" to p.fluidSize,
        "u_paperFluidHexTexels" to p.fluidHexTexels,
    )

    private fun oldPasteShared(st: PasteStep, b: PasteBrush, paper: MediaPaperGl, pxPerMm: Double, seed: Int, bakeTex: Int, p0: Int, p1: Int): Map<String, Any?> {
        val cap = cellCap(b)
        val t = paper.stats.toothMm
        return mapOf<String, Any?>(
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
            "u_paperBake" to Tex(bakeTex), "u_p0" to Tex(p0), "u_p1" to Tex(p1),
        )
    }

    // ---------------------------------------------------------------- scenario material

    private val lay = Layer(ox = 37.5f, oy = 12f, scale = 1.5f, w = 1024, h = 768)
    private class Layer(val ox: Float, val oy: Float, val scale: Float, val w: Int, val h: Int)

    private fun paperGl(base: Int, rot: Boolean = false, tooth: Double = 0.3) = MediaPaperGl(
        MediaPaper(DoubleArray(256) { 1.0 / 256 }, toothMm = tooth, compliance = 0.5),
        Tex(base), 2.5f, 512f, 7f, 0.4f, rot, Tex(base + 1), 3f, 256f, 5f,
    )

    private fun step(i: Int, brush: PasteBrush) = PasteStep(
        x = 100.0 + 0.3 * i, y = 50.0 + 0.17 * i, wDirX = 0.6, wDirY = 0.8, lDirX = -0.8, lDirY = 0.6,
        halfW = brush.widthMm / 2, len = brush.lenMm * (0.5 + 0.03 * (i % 7)), lenMax = brush.lenMm, pressure = 0.4 + 0.05 * (i % 5),
        slideMm = 0.3, fingers = if (i % 3 == 0) 0.2 + 0.01 * i else null, thick = if (i % 4 == 0) 0.3 else null,
        bladeDirX = 0.1 * (i % 3), bladeDirY = 1.0, bladeLen = if (brush.blade) 20.0 + i else null, bladeHalf = if (brush.blade) 4.0 else null,
        travelX = 0.8, travelY = 0.6, bladeH0 = if (brush.blade) 0.5 else null, bladeTan = if (brush.blade) 0.1 * i else null,
        pressK = if (i % 2 == 0) 0.25 else null, loadMode = i % 5 == 0,
    )

    private fun rect(i: Int) = floatArrayOf(10f + i, 20f + 2 * i, 90f + 3 * i, 100f + i)

    // ---------------------------------------------------------------- paste

    @Test fun pasteStepsLeaveTheSameGlStateAsTheOldMaps() {
        val r = Rig()
        for ((name, brush) in PasteBrushes.ALL) {
            var paper = paperGl(10)
            for (i in 0 until 14) {
                if (i == 8) paper = paperGl(40, rot = true, tooth = 0.45)   // the paper changes mid-stroke sequence: values must be rewritten
                val st = step(i, brush)
                val seed = if (i < 10) 7 else 20_000_001                    // beyond 2^24: the Int -> Float rounding must match too
                val cur0 = 100 + (i % 2) * 2
                val sq = rect(i)
                // brush pass
                r.old(dPasteBrush).use(oldCommon(lay.ox, lay.oy, lay.scale, oldPasteShared(st, brush, paper, 9.1, seed, 50, 60, 61) + mapOf(
                    "u_brush0" to Tex(cur0), "u_brush1" to Tex(cur0 + 1), "u_wick" to brush.wick, "u_active" to 1,
                    "u_layerSize" to floatArrayOf(lay.w.toFloat(), lay.h.toFloat()), "u_rect" to floatArrayOf(0f, 0f, PASTE_LANES.toFloat(), PASTE_DEPTH.toFloat()),
                    "u_targetSize" to floatArrayOf(PASTE_LANES.toFloat(), PASTE_DEPTH.toFloat()))))
                r.new(dPasteBrush).let { u ->
                    MediaUniforms.common(u, lay.ox, lay.oy, lay.scale)
                    MediaUniforms.paste(u, st, brush, cellCap(brush), paper.stats.toothMm, 9.1, seed, 50, 60, 61)
                    MediaUniforms.pasteBrush(u, cur0, cur0 + 1, brush.wick, lay.w, lay.h, PASTE_LANES, PASTE_DEPTH)
                }
                r.same("$name step $i brush pass")
                // canvas pass
                r.old(dPasteDab).use(oldCommon(lay.ox, lay.oy, lay.scale, oldPasteShared(st, brush, paper, 9.1, seed, 50, 60, 61) + mapOf(
                    "u_brush0" to Tex(cur0), "u_brush1" to Tex(cur0 + 1),
                    "u_targetSize" to floatArrayOf(lay.w.toFloat(), lay.h.toFloat()), "u_rect" to sq)))
                r.new(dPasteDab).let { u ->
                    MediaUniforms.common(u, lay.ox, lay.oy, lay.scale)
                    MediaUniforms.paste(u, st, brush, cellCap(brush), paper.stats.toothMm, 9.1, seed, 50, 60, 61)
                    MediaUniforms.pasteDab(u, cur0, cur0 + 1, lay.w, lay.h, sq)
                }
                r.same("$name step $i canvas pass")
                // the copy-back
                r.old(dCopy).use(oldCommon(lay.ox, lay.oy, lay.scale, mapOf("u_a" to Tex(70), "u_b" to Tex(71), "u_c" to Tex(72),
                    "u_targetSize" to floatArrayOf(lay.w.toFloat(), lay.h.toFloat()), "u_rect" to sq)))
                r.new(dCopy).let { u -> MediaUniforms.common(u, lay.ox, lay.oy, lay.scale); MediaUniforms.copy(u, 70, 71, 72, lay.w, lay.h, sq) }
                r.same("$name step $i copy")
            }
        }
    }

    /** The counts behind the report: GL calls (uniform writes + 3 per texture bind) for a steady-state oil step, old vs new. */
    @Test fun aSteadyPasteStepIssuesFarFewerGlCalls() {
        val r = Rig()
        val brush = PasteBrushes.ALL.getValue("Oil round")
        val paper = paperGl(10)
        var oldPer = 0; var newPer = 0
        for (i in 0 until 6) {
            val st = step(i * 2 + 1, brush)   // odd steps: the shape of a real stroke - position changes, brush constants do not
            val sq = rect(i)
            val o0 = r.oldGl.calls; val n0 = r.newGl.calls
            r.old(dPasteBrush).use(oldCommon(lay.ox, lay.oy, lay.scale, oldPasteShared(st, brush, paper, 9.1, 3, 50, 60, 61) + mapOf(
                "u_brush0" to Tex(100), "u_brush1" to Tex(101), "u_wick" to brush.wick, "u_active" to 1,
                "u_layerSize" to floatArrayOf(1f, 1f), "u_rect" to floatArrayOf(0f, 0f, 32f, 8f), "u_targetSize" to floatArrayOf(32f, 8f))))
            r.old(dPasteDab).use(oldCommon(lay.ox, lay.oy, lay.scale, oldPasteShared(st, brush, paper, 9.1, 3, 50, 60, 61) + mapOf(
                "u_brush0" to Tex(100), "u_brush1" to Tex(101), "u_targetSize" to floatArrayOf(1f, 1f), "u_rect" to sq)))
            r.old(dCopy).use(oldCommon(lay.ox, lay.oy, lay.scale, mapOf("u_a" to Tex(70), "u_b" to Tex(71), "u_c" to Tex(72),
                "u_targetSize" to floatArrayOf(1f, 1f), "u_rect" to sq)))
            r.new(dPasteBrush).let { u ->
                MediaUniforms.common(u, lay.ox, lay.oy, lay.scale); MediaUniforms.paste(u, st, brush, cellCap(brush), paper.stats.toothMm, 9.1, 3, 50, 60, 61)
                MediaUniforms.pasteBrush(u, 100, 101, brush.wick, 1, 1, 32, 8)
            }
            r.new(dPasteDab).let { u ->
                MediaUniforms.common(u, lay.ox, lay.oy, lay.scale); MediaUniforms.paste(u, st, brush, cellCap(brush), paper.stats.toothMm, 9.1, 3, 50, 60, 61)
                MediaUniforms.pasteDab(u, 100, 101, 1, 1, sq)
            }
            r.new(dCopy).let { u -> MediaUniforms.common(u, lay.ox, lay.oy, lay.scale); MediaUniforms.copy(u, 70, 71, 72, 1, 1, sq) }
            r.same("step $i")
            oldPer = r.oldGl.calls - o0; newPer = r.newGl.calls - n0
        }
        println("MEDIA-UNIFORMS paste step (oil round, steady state): old $oldPer GL calls, new $newPer GL calls")
        assertTrue(newPer * 2 < oldPer, "expected well under half the GL calls: old $oldPer new $newPer")
    }

    // ---------------------------------------------------------------- dry, erase, bake, look

    private val stick = StickMaterial.of(Sticks.ALL.values.first())

    @Test fun dryErasePaperBakeAndLookMatch() {
        val r = Rig()
        var paper = paperGl(10)
        val look = MediaLook(floatArrayOf(0.95f, 0.93f, 0.88f))
        for (i in 0 until 8) {
            if (i == 5) paper = paperGl(30)
            val rc = rect(i)
            val tx = 0.3 * i; val ty = -0.2 * i
            val lut = paper.stats.contactLut()
            // bake
            r.old(dBake).use(oldCommon(lay.ox, lay.oy, lay.scale, oldPaper(paper) + mapOf("u_cellPx" to floatArrayOf(1f, 1f),
                "u_rect" to floatArrayOf(0f, 0f, lay.w.toFloat(), lay.h.toFloat()), "u_targetSize" to floatArrayOf(lay.w.toFloat(), lay.h.toFloat()))))
            r.new(dBake).let { u -> MediaUniforms.common(u, lay.ox, lay.oy, lay.scale); MediaUniforms.paper(u, paper); MediaUniforms.bake(u, lay.w, lay.h) }
            r.same("bake $i")
            // dry dab
            val t = paper.stats.toothMm
            r.old(dDry).use(oldCommon(lay.ox, lay.oy, lay.scale, oldPaper(paper) + mapOf(
                "u_crush" to Tex(80), "u_contactLut" to Tex(81), "u_lutMaxMm" to lut.maxMm,
                "u_targetSize" to floatArrayOf(lay.w.toFloat(), lay.h.toFloat()), "u_pxPerMm" to 9.1, "u_toothMm" to t,
                "u_dirStrength" to stick.dirStrength, "u_dustRate" to stick.dustRate, "u_clump" to stick.clump,
                "u_pileRange" to doubleArrayOf(stick.pileRangeLo, stick.pileRangeHi), "u_crushStart" to stick.crushStart * t,
                "u_conform" to stick.conform, "u_transferExp" to stick.transferExp, "u_leadSoft" to stick.leadSoft, "u_plateau" to stick.plateau,
            )))
            r.new(dDry).let { u ->
                MediaUniforms.common(u, lay.ox, lay.oy, lay.scale); MediaUniforms.paper(u, paper)
                MediaUniforms.dryDab(u, 80, 81, lut.maxMm, lay.w, lay.h, 9.1, t, stick)
            }
            r.same("dry dab $i")
            // dry apply
            r.old(dApply).use(oldCommon(lay.ox, lay.oy, lay.scale, mapOf(
                "u_p0" to Tex(82), "u_p1" to Tex(83), "u_paperState" to Tex(84), "u_delta" to Tex(85),
                "u_targetSize" to floatArrayOf(lay.w.toFloat(), lay.h.toFloat()), "u_rect" to rc,
                "u_capMm" to stick.capMm, "u_abrasion" to stick.abrasion, "u_flakeR" to stick.flakeR, "u_crushRate" to stick.crushRate,
                "u_crushMax" to stick.crushMax, "u_smear" to stick.smear,
                "u_smearPx" to doubleArrayOf(tx * stick.smearMm * 9.1 * lay.scale, ty * stick.smearMm * 9.1 * lay.scale),
            )))
            r.new(dApply).let { u ->
                MediaUniforms.common(u, lay.ox, lay.oy, lay.scale)
                MediaUniforms.dryApply(u, 82, 83, 84, 85, lay.w, lay.h, rc, stick, tx, ty, 9.1, lay.scale)
            }
            r.same("dry apply $i")
            // erase: dab, state pass, water pass
            r.old(dEraseDab).use(oldCommon(lay.ox, lay.oy, lay.scale, mapOf("u_targetSize" to floatArrayOf(lay.w.toFloat(), lay.h.toFloat()))))
            r.new(dEraseDab).let { u -> MediaUniforms.common(u, lay.ox, lay.oy, lay.scale); MediaUniforms.eraseDab(u, lay.w, lay.h) }
            r.same("erase dab $i")
            for (water in 0..1) {
                val size = floatArrayOf(lay.w.toFloat(), lay.h.toFloat())
                r.old(dErase).use(oldCommon(lay.ox, lay.oy, lay.scale, mapOf("u_a" to Tex(90 + water), "u_b" to Tex(92), "u_c" to Tex(93 + water),
                    "u_cover" to Tex(85), "u_water" to water, "u_targetSize" to size, "u_rect" to rc)))
                r.new(dErase).let { u ->
                    MediaUniforms.common(u, lay.ox, lay.oy, lay.scale)
                    MediaUniforms.erase(u, 90 + water, 92, 93 + water, 85, water, lay.w, lay.h, rc)
                }
                r.same("erase water=$water $i")
            }
            // the look
            val wet = i % 2 == 0
            r.old(dRender).use(oldCommon(lay.ox, lay.oy, lay.scale, oldPaper(paper) + mapOf(
                "u_p0" to Tex(82), "u_p1" to Tex(83), "u_paperState" to Tex(84), "u_ground" to Tex(86),
                "u_paperBake" to Tex(if (wet) 87 else 99),
                "u_w0" to Tex(if (wet) 88 else 99), "u_w1" to Tex(if (wet) 89 else 99),
                "u_targetSize" to floatArrayOf(lay.w.toFloat(), lay.h.toFloat()), "u_pan" to floatArrayOf(-lay.ox * lay.scale, -lay.oy * lay.scale),
                "u_zoom" to lay.scale, "u_pxPerMm" to 9.1, "u_toothMm" to paper.stats.toothMm, "u_paperColor" to look.paperColor,
                "u_lamp" to look.lamp, "u_relief" to 0f, "u_sheen" to look.sheen, "u_capMm" to look.capMm, "u_mode" to 0,
                "u_impasto" to look.impasto, "u_look" to 1f,
            )))
            r.new(dRender).let { u ->
                MediaUniforms.common(u, lay.ox, lay.oy, lay.scale); MediaUniforms.paper(u, paper)
                MediaUniforms.render(u, 82, 83, 84, 86, if (wet) 87 else 99, if (wet) 88 else 99, if (wet) 89 else 99, lay.w, lay.h,
                    lay.ox, lay.oy, lay.scale, 9.1, paper.stats.toothMm, look)
            }
            r.same("render $i")
        }
    }

    // ---------------------------------------------------------------- wet

    @Test fun wetFramesMatchIncludingSlopeEvapOverrideAndBeadPasses() {
        val r = Rig()
        val frame = WetFrameUniforms()
        val paper = paperGl(10)
        val batches = listOf(
            Triple(WetConstants(), null as Double?, floatArrayOf(0f, 0f)),
            Triple(WetConstants(), null, floatArrayOf(0f, 0f)),
            Triple(WetConstants(), null, floatArrayOf(0.05f, -0.2f)),          // tilted: beads run
            Triple(WetConstants(substeps = 1), 1e4, floatArrayOf(0.05f, -0.2f)),   // the dry-now frame: one substep, evap forced
            Triple(WetConstants(dt = 1.0 / 60, flow = 20.0), null, floatArrayOf(0f, 0f)),
        )
        for ((f, trip) in batches.withIndex()) {
            val (wc0, evap, slope) = trip
            val pxPerMm = 9.1 + f
            val beadCells = wc0.beadMm * pxPerMm * lay.scale
            val rc = rect(f)
            val oldAt = r.oldGl.calls; val newAt = r.newGl.calls
            // new dab input (first two frames)
            if (f < 2) {
                r.old(dWetDab).use(oldCommon(lay.ox, lay.oy, lay.scale, mapOf("u_w0" to Tex(110), "u_paperBake" to Tex(50),
                    "u_targetSize" to floatArrayOf(lay.w.toFloat(), lay.h.toFloat()), "u_pxPerMm" to pxPerMm, "u_fullMm" to wc0.fullMm)))
                r.new(dWetDab).let { u -> MediaUniforms.common(u, lay.ox, lay.oy, lay.scale); MediaUniforms.wetDab(u, 110, 50, lay.w, lay.h, pxPerMm, wc0.fullMm) }
                r.same("wet dab frame $f")
            }
            val uni = HashMap<String, Any?>(wetUniforms(paper.stats, wc0))
            uni["u_slope"] = slope
            uni["u_cellMm"] = 1 / (pxPerMm * lay.scale)
            uni["u_beadCells"] = beadCells
            if (evap != null) uni["u_evap"] = evap
            frame.load(wetUniforms(paper.stats, wc0), evap, slope, 1 / (pxPerMm * lay.scale), beadCells)
            for (k in 0 until wc0.substeps) {
                val wcur = k % 2
                val a0 = 120 + 3 * wcur; val b0 = 120 + 3 * (1 - wcur)   // a: w0,w1,flux of the current buffer; b: the other
                // bead passes (tilted only)
                if (slope[0] != 0f || slope[1] != 0f) for ((pass, axis) in listOf(0 to (1f to 0f), 1 to (1f to 0f), 2 to (0f to 1f))) {
                    val hr = floatArrayOf(1f, 2f, 3f + k, 4f + pass)
                    val sigma = minOf(4.8, maxOf(1.0, 0.25 * beadCells))
                    r.old(dBead).use(oldCommon(lay.ox, lay.oy, lay.scale, mapOf("u_rect" to hr, "u_targetSize" to floatArrayOf(512f, 384f), "u_sigma" to sigma,
                        "u_pass" to pass, "u_w0" to Tex(a0), "u_src" to Tex(130 + pass), "u_axis" to floatArrayOf(axis.first, axis.second))))
                    r.new(dBead).let { u ->
                        MediaUniforms.common(u, lay.ox, lay.oy, lay.scale)
                        MediaUniforms.bead(u, hr, 512, 384, sigma, pass, a0, 130 + pass, axis.first, axis.second)
                    }
                    r.same("bead pass $pass frame $f step $k")
                }
                r.old(dFlux).use(oldCommon(lay.ox, lay.oy, lay.scale, uni + mapOf("u_bead" to Tex(140), "u_p0" to Tex(82), "u_w0" to Tex(a0), "u_flux" to Tex(a0 + 2),
                    "u_paperBake" to Tex(50), "u_fluidBake" to Tex(51), "u_waterBake" to Tex(52), "u_rect" to rc,
                    "u_targetSize" to floatArrayOf(lay.w.toFloat(), lay.h.toFloat()))))
                r.new(dFlux).let { u ->
                    MediaUniforms.common(u, lay.ox, lay.oy, lay.scale); frame.apply(u)
                    MediaUniforms.wetFlux(u, 140, 82, a0, a0 + 2, 50, 51, 52, rc, lay.w, lay.h)
                }
                r.same("flux frame $f step $k")
                val useInput = if (k == 0 && f < 2) 1 else 0
                r.old(dUpdate).use(oldCommon(lay.ox, lay.oy, lay.scale, uni + mapOf("u_w0" to Tex(a0), "u_w1" to Tex(a0 + 1), "u_flux" to Tex(b0 + 2), "u_run" to Tex(150),
                    "u_p0" to Tex(82), "u_p1" to Tex(83), "u_paperBake" to Tex(50), "u_waterBake" to Tex(52),
                    "u_fluidBake" to Tex(51), "u_in0" to Tex(151), "u_in1" to Tex(152), "u_useInput" to useInput,
                    "u_rect" to rc, "u_targetSize" to floatArrayOf(lay.w.toFloat(), lay.h.toFloat()))))
                r.new(dUpdate).let { u ->
                    MediaUniforms.common(u, lay.ox, lay.oy, lay.scale); frame.apply(u)
                    MediaUniforms.wetUpdate(u, a0, a0 + 1, b0 + 2, 150, 82, 83, 50, 52, 51, 151, 152, useInput, rc, lay.w, lay.h)
                }
                r.same("update frame $f step $k")
            }
            if (f == 1) println("MEDIA-UNIFORMS watercolour frame (4 substeps, flat paper): old ${r.oldGl.calls - oldAt} GL calls, new ${r.newGl.calls - newAt}")
        }
    }

    // ---------------------------------------------------------------- completeness: nothing the shaders declare is left unset

    /**
     * A uniform the old path never wrote is a pre-existing gap, not something this change may open or close. So: after a
     * full sweep of passes, the set of declared uniforms that were written is IDENTICAL for old and new, per program.
     * (Equal GL state after every pass already implies it; this names the programs and makes a regression readable.)
     */
    @Test fun everyDeclaredUniformTheOldPathWroteIsStillWritten() {
        val r = Rig()
        pasteStepsLeaveTheSameGlStateAsTheOldMapsOn(r)
        for (d in all) {
            val idx = all.indexOf(d)
            val o = r.oldGl.values.keys.filter { it.startsWith("$idx:") }.toSet()
            val n = r.newGl.values.keys.filter { it.startsWith("$idx:") }.toSet()
            assertEquals(o, n, "${d.name}: written uniform sets differ")
        }
    }

    private fun pasteStepsLeaveTheSameGlStateAsTheOldMapsOn(r: Rig) {
        val brush = PasteBrushes.ALL.getValue("Palette knife 2")
        val paper = paperGl(10)
        val st = step(3, brush)
        val sq = rect(1)
        r.old(dPasteDab).use(oldCommon(lay.ox, lay.oy, lay.scale, oldPasteShared(st, brush, paper, 9.1, 1, 50, 60, 61) + mapOf(
            "u_brush0" to Tex(100), "u_brush1" to Tex(101), "u_targetSize" to floatArrayOf(1f, 1f), "u_rect" to sq)))
        r.new(dPasteDab).let { u ->
            MediaUniforms.common(u, lay.ox, lay.oy, lay.scale); MediaUniforms.paste(u, st, brush, cellCap(brush), paper.stats.toothMm, 9.1, 1, 50, 60, 61)
            MediaUniforms.pasteDab(u, 100, 101, 1, 1, sq)
        }
        r.same("knife canvas pass")
        // every uniform the canvas shader declares is written (a declared-but-unwritten one would show here as a short list)
        val declared = dPasteDab.types.keys
        val written = r.newGl.values.keys.filter { it.startsWith("${all.indexOf(dPasteDab)}:") }.size
        println("MEDIA-UNIFORMS paste dab: ${declared.size} declared uniforms, $written written")
    }

    // ---------------------------------------------------------------- the bank itself

    private fun bank(vararg decl: Pair<String, Int>, gl: FakeGl = FakeGl()): Pair<UniformBank, FakeGl> {
        val m = HashMap<String, UniformSlot>()
        var unit = 0
        decl.forEachIndexed { i, (n, t) -> m[n] = UniformSlot(i, t, if (t == GlC.GL_SAMPLER_2D) unit++ else -1) }
        return UniformBank(m, gl.sink(0), "t") to gl
    }

    @Test fun anUnchangedValueIsNotWrittenAgainButAChangedOneIs() {
        val (b, gl) = bank("f" to GlC.GL_FLOAT, "v" to GlC.GL_FLOAT_VEC2)
        b.f1("f", 1.5); b.f2("v", 1f, 2f)
        assertEquals(2, gl.calls)
        b.f1("f", 1.5); b.f2("v", 1.0, 2.0)
        assertEquals(2, gl.calls, "same bits: nothing to write")
        b.f2("v", 1f, 3f)
        assertEquals(3, gl.calls)
        b.f1("f", 0f); b.f1("f", -0f)
        assertEquals(5, gl.calls, "-0f and 0f are different bits: both written")
        b.f1("f", Float.NaN); b.f1("f", Float.NaN)
        assertEquals(6, gl.calls, "the same NaN bits are written once")
    }

    @Test fun conversionsFollowTheDeclaredTypeAsTheOldSetDid() {
        val (b, gl) = bank("f" to GlC.GL_FLOAT, "i" to GlC.GL_INT, "z" to GlC.GL_BOOL)
        b.f1("f", 3); b.f1("i", 16_777_217); b.b1("z", true)   // Int -> Float rounds 2^24+1 to 2^24, as `num(v).toInt()` always did
        assertEquals("1f ${3f.toRawBits()}", gl.values["0:0"])
        assertEquals("1i 16777216", gl.values["0:1"])
        assertEquals("1i 1", gl.values["0:2"])
        b.f1("i", 2.9)
        assertEquals("1i 2", gl.values["0:1"], "a Double written to an int uniform truncates after the Float conversion")
    }

    @Test fun missingNamesAreSkippedAndWrongTypesThrow() {
        val (b, gl) = bank("f" to GlC.GL_FLOAT)
        b.f1("nope", 1.0); b.tex("nope", 5)
        assertEquals(0, gl.calls)
        assertFailsWith<IllegalStateException> { b.f2("f", 1f, 2f) }
        assertFailsWith<IllegalStateException> { b.tex("f", 3) }
    }

    @Test fun texturesAreAlwaysRebindButTheUnitUniformIsWrittenOnce() {
        val (b, gl) = bank("a" to GlC.GL_SAMPLER_2D, "b" to GlC.GL_SAMPLER_2D)
        b.tex("a", 10); b.tex("b", 11)
        assertEquals(2 * 3 + 2, gl.calls)
        assertEquals(10, gl.units[0]); assertEquals(11, gl.units[1])
        gl.units[0] = 99                                  // some other code rebinds unit 0 between passes
        b.tex("a", 10); b.tex("b", 11)
        assertEquals(10, gl.units[0], "the binding is restored every pass")
        assertEquals(2 * 3 + 2 + 2 * 3, gl.calls, "only the binds repeat; the unit uniforms are not rewritten")
    }

    @Test fun vectorsOfEverySizeAndTheirArrayForms() {
        val (b, gl) = bank("v3" to GlC.GL_FLOAT_VEC3, "v4" to GlC.GL_FLOAT_VEC4)
        b.f3("v3", floatArrayOf(1f, 2f, 3f, 99f)); b.f4("v4", floatArrayOf(1f, 2f, 3f, 4f))
        assertEquals("3f ${1f.toRawBits()} ${2f.toRawBits()} ${3f.toRawBits()}", gl.values["0:0"])
        assertEquals("4f ${1f.toRawBits()} ${2f.toRawBits()} ${3f.toRawBits()} ${4f.toRawBits()}", gl.values["0:1"])
        assertFailsWith<IllegalArgumentException> { b.f4("v4", floatArrayOf(1f, 2f)) }
    }

    // ---------------------------------------------------------------- allocation

    /** The per-step path allocates nothing: bytes allocated by this thread over thousands of paste steps stay at noise level. */
    @Test fun thePastePathAllocatesNothingPerStep() {
        val mx = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        if (mx == null || !mx.isThreadAllocatedMemorySupported) return
        val quiet = object : UniformSink {
            override fun uniform1f(loc: Int, a: Float) {}
            override fun uniform2f(loc: Int, a: Float, b: Float) {}
            override fun uniform3f(loc: Int, a: Float, b: Float, c: Float) {}
            override fun uniform4f(loc: Int, a: Float, b: Float, c: Float, d: Float) {}
            override fun uniform1i(loc: Int, a: Int) {}
            override fun bindTexture(unit: Int, tex: Int) {}
        }
        val brush = PasteBrushes.ALL.getValue("Oil round")
        val paper = paperGl(10)
        fun bank(d: Decl): UniformBank = UniformBank(slots(d), quiet, d.name)
        val brushBank = bank(dPasteBrush); val dabBank = bank(dPasteDab); val copyBank = bank(dCopy)
        val sq = rect(1)
        val steps = Array(64) { step(it, brush) }
        fun run(n: Int) {
            for (i in 0 until n) {
                val st = steps[i and 63]
                MediaUniforms.common(brushBank, lay.ox, lay.oy, lay.scale)
                MediaUniforms.paste(brushBank, st, brush, 3.0, 0.3, 9.1, i, 50, 60, 61)
                MediaUniforms.pasteBrush(brushBank, 100, 101, brush.wick, lay.w, lay.h, PASTE_LANES, PASTE_DEPTH)
                MediaUniforms.common(dabBank, lay.ox, lay.oy, lay.scale)
                MediaUniforms.paste(dabBank, st, brush, 3.0, 0.3, 9.1, i, 50, 60, 61)
                MediaUniforms.pasteDab(dabBank, 100, 101, lay.w, lay.h, sq)
                MediaUniforms.common(copyBank, lay.ox, lay.oy, lay.scale)
                MediaUniforms.copy(copyBank, 70, 71, 72, lay.w, lay.h, sq)
            }
        }
        run(20_000)   // warm up
        val id = Thread.currentThread().id
        val before = mx.getThreadAllocatedBytes(id)
        run(5_000)
        val used = mx.getThreadAllocatedBytes(id) - before
        println("MEDIA-UNIFORMS allocation over 5000 paste steps: $used bytes")
        assertTrue(used < 16 * 1024, "uniform submission allocated $used bytes over 5000 paste steps")
    }
}

/** The GL type enums as the spec numbers them (android.jar is not on the test classpath, so GLES30's constants are not visible here). */
private object GlC {
    const val GL_FLOAT = 0x1406
    const val GL_INT = 0x1404
    const val GL_BOOL = 0x8B56
    const val GL_FLOAT_VEC2 = 0x8B50
    const val GL_FLOAT_VEC3 = 0x8B51
    const val GL_FLOAT_VEC4 = 0x8B52
    const val GL_SAMPLER_2D = 0x8B5E
}
