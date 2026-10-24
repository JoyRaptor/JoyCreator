package cc.joycreator.joybrush.androidkit.gl.media

import cc.joycreator.joybrush.core.media.PasteBrush
import cc.joycreator.joybrush.core.media.PasteStep
import cc.joycreator.joybrush.core.media.StickMaterial

/**
 * What each media pass writes to its program, one function per pass, typed and allocation-free (no maps, no boxing, no
 * per-call arrays): this replaces the `common(mapOf(...) + ...)` maps the engine used to build for every pass.
 *
 * EXACTNESS (why no uniform is lost). The old code handed `MediaProgram.use` the full map of values for the pass and
 * every entry whose name the program has was written. Each function here writes the SAME names with the SAME
 * values (same expressions, same Double/Float/Int source types, so the same conversions) to the same program kinds;
 * nothing is left out on the grounds that "it is constant". The savings come entirely from [UniformBank]: it remembers
 * the bits last written to each uniform of its program and does not issue a GL call for a value that is already there
 * (GL keeps uniforms in the program object), so brush constants, paper maps, layer origin/scale and the like cost a
 * compare instead of a driver call after the first pass. A uniform that changed is written; one that is unchanged
 * already holds the right value. Names that do not exist in a given shader are skipped, as before.
 * MediaUniformsParityTest runs these against verbatim copies of the old map builders and an old-style writer, on the real
 * shaders' declared uniforms, and compares the resulting GL state after every pass.
 *
 * Binding order inside a pass differs from the old map-iteration order, which never mattered: each sampler has its own
 * unit and a pass's uniforms are independent of one another.
 */
internal object MediaUniforms {
    /** The layer window's placement; every media shader that maps doc px to layer px reads it. */
    fun common(u: UniformBank, originX: Float, originY: Float, scale: Float) {
        u.f2("u_layerOrigin", originX, originY)
        u.f1("u_layerScale", scale)
    }

    /** The paper surface the shaders share (jb_paper.glsl): the document's grain plus the media statistics. */
    fun paper(u: UniformBank, p: MediaPaperGl) {
        u.tex("u_paperSurface", p.surface.id); u.f1("u_paperTexelPx", p.texelPx); u.f1("u_paperSize", p.size)
        u.f1("u_paperHexTexels", p.hexTexels); u.f1("u_paperSlopeRange", p.slopeRange); u.b1("u_paperRotatable", p.rotatable)
        u.f1("u_paperHeightMean", p.stats.heightMean.toFloat())
        u.f1("u_paperHBot", p.stats.heightBot.toFloat()); u.f1("u_paperHTop", p.stats.heightTop.toFloat())
        u.tex("u_paperFluid", p.fluid.id); u.f1("u_paperFluidTexelPx", p.fluidTexelPx); u.f1("u_paperFluidSize", p.fluidSize)
        u.f1("u_paperFluidHexTexels", p.fluidHexTexels)
    }

    /** jb_media_bake.frag, after [common] and [paper]. */
    fun bake(u: UniformBank, w: Int, h: Int) {
        u.f4("u_rect", 0f, 0f, w.toFloat(), h.toFloat())
        u.f2("u_targetSize", w.toFloat(), h.toFloat())
    }

    /** jb_media_copy.frag, after [common]. */
    fun copy(u: UniformBank, a: Int, b: Int, c: Int, w: Int, h: Int, rect: FloatArray) {
        u.tex("u_a", a); u.tex("u_b", b); u.tex("u_c", c)
        u.f2("u_targetSize", w.toFloat(), h.toFloat()); u.f4("u_rect", rect)
    }

    // ---- dry media ----
    /** jb_dry_dab.frag / .vert, after [common] and [paper]. */
    fun dryDab(u: UniformBank, crushTex: Int, lutTex: Int, lutMaxMm: Double, w: Int, h: Int, pxPerMm: Double, toothMm: Double, mat: StickMaterial) {
        u.tex("u_crush", crushTex); u.tex("u_contactLut", lutTex); u.f1("u_lutMaxMm", lutMaxMm)
        u.f2("u_targetSize", w.toFloat(), h.toFloat()); u.f1("u_pxPerMm", pxPerMm); u.f1("u_toothMm", toothMm)
        u.f1("u_dirStrength", mat.dirStrength); u.f1("u_dustRate", mat.dustRate); u.f1("u_clump", mat.clump)
        u.f2("u_pileRange", mat.pileRangeLo, mat.pileRangeHi); u.f1("u_crushStart", mat.crushStart * toothMm)
        u.f1("u_conform", mat.conform); u.f1("u_transferExp", mat.transferExp); u.f1("u_leadSoft", mat.leadSoft); u.f1("u_plateau", mat.plateau)
        u.f1("u_stickBlend", mat.blend)
        u.f4("u_stickShape", floatArrayOf(mat.bell.toFloat(), mat.acrossSoft.toFloat(), mat.tipYield.toFloat(), mat.tail.toFloat()))
        u.f2("u_zoneGrad", mat.grad1, mat.grad2)
    }

    /** jb_media_apply.frag, after [common]. */
    fun dryApply(u: UniformBank, p0: Int, p1: Int, paperState: Int, delta: Int, w: Int, h: Int, rect: FloatArray, mat: StickMaterial,
                 travelX: Double, travelY: Double, pxPerMm: Double, scale: Float) {
        u.tex("u_p0", p0); u.tex("u_p1", p1); u.tex("u_paperState", paperState); u.tex("u_delta", delta)
        u.f2("u_targetSize", w.toFloat(), h.toFloat()); u.f4("u_rect", rect)
        u.f1("u_capMm", mat.capMm); u.f1("u_abrasion", mat.abrasion); u.f1("u_flakeR", mat.flakeR); u.f1("u_crushRate", mat.crushRate)
        u.f1("u_crushMax", mat.crushMax); u.f1("u_smear", mat.smear)
        u.f2("u_smearPx", travelX * mat.smearMm * pxPerMm * scale, travelY * mat.smearMm * pxPerMm * scale)
    }

    // ---- paste media ----
    /**
     * Everything the two paste programs share (jb_paste.glsl; both the brush pass and the canvas pass get all of it),
     * after [common]. [cap] = cellCap(b); [toothMm] = the paper's tooth.
     */
    fun paste(u: UniformBank, st: PasteStep, b: PasteBrush, cap: Double, toothMm: Double, pxPerMm: Double, seed: Int,
              bakeTex: Int, p0: Int, p1: Int) {
        u.f2("u_center", st.x, st.y); u.f2("u_wDir", st.wDirX, st.wDirY); u.f2("u_lDir", st.lDirX, st.lDirY)
        u.f1("u_halfW", st.halfW); u.f1("u_len", st.len); u.f1("u_lenMax", st.lenMax); u.f1("u_shape", b.shape); u.f1("u_pressure", st.pressure)
        u.f1("u_thick", st.thick ?: b.thickMm); u.f1("u_scrape", b.scrape); u.f1("u_fingers", st.fingers ?: b.fingers ?: 1.0)
        u.f1("u_rag", b.rag ?: 1.0); u.f1("u_arc", b.arc ?: 0.18); u.f1("u_sparse", b.sparse ?: 0.0)
        u.f1("u_ends", if (b.loadedEnds) 1 else 0); u.f1("u_lift", st.lift); u.f1("u_land", st.land)
        // Covers (new colour over wet paint) and carry (the dirty brush): lab prototypes, off until the owner picks.
        u.f1("u_cover", 0); u.f1("u_carry", 0)
        u.f1("u_blade", if (b.blade) 1 else 0); u.f1("u_fillDips", b.fillDips ?: 1.0); u.f1("u_bladeLoad", if (st.loadMode) 1 else 0)
        u.f2("u_bladeDir", st.bladeDirX, st.bladeDirY); u.f2("u_travel", st.travelX, st.travelY)
        // (not takeIf: its generic receiver would box a Double on every call)
        u.f1("u_bladeLen", st.bladeLen ?: if (b.bladeLenMm != 0.0) b.bladeLenMm else 1.0)
        u.f1("u_bladeHalfW", st.bladeHalf ?: if (b.bladeHalfMm != 0.0) b.bladeHalfMm else 1.0)
        u.f1("u_bladeH0", st.bladeH0 ?: 0.0); u.f1("u_bladeTan", st.bladeTan ?: 0.0); u.f1("u_bladeBead", b.bead)
        u.f1("u_bladePressMm", (b.pressIn ?: 0.6) * toothMm * (st.pressK ?: (st.pressure * st.pressure))); u.f1("u_bladeFilm", b.film ?: 0.004)
        u.f1("u_hairDepth", b.hairDepth); u.f1("u_ridge", b.ridge); u.f1("u_rate", b.rate); u.f1("u_mix", b.mix); u.f1("u_swap", b.swap)
        u.f1("u_bow", b.bow); u.f1("u_lump", b.lump); u.f1("u_rigid", b.rigid); u.f1("u_slideMm", st.slideMm); u.f1("u_cellCap", cap)
        u.f1("u_seed", seed); u.f1("u_pxPerMm", pxPerMm); u.f1("u_toothMm", toothMm)
        u.tex("u_paperBake", bakeTex); u.tex("u_p0", p0); u.tex("u_p1", p1)
    }

    /** jb_paste_brush.frag, after [common] and [paste]: the brush side, drawn into the brush cells' own PASTE_LANES x PASTE_DEPTH target. */
    fun pasteBrush(u: UniformBank, b0: Int, b1: Int, wick: Double, layerW: Int, layerH: Int, lanes: Int, depth: Int) {
        u.tex("u_brush0", b0); u.tex("u_brush1", b1); u.f1("u_wick", wick); u.f1("u_active", 1)
        u.f2("u_layerSize", layerW.toFloat(), layerH.toFloat()); u.f4("u_rect", 0f, 0f, lanes.toFloat(), depth.toFloat())
        u.f2("u_targetSize", lanes.toFloat(), depth.toFloat())
    }

    /** jb_paste_dab.frag, after [common] and [paste]: the canvas side; [square] is the legacy bounding square (the quad). */
    fun pasteDab(u: UniformBank, b0: Int, b1: Int, w: Int, h: Int, square: FloatArray) {
        u.tex("u_brush0", b0); u.tex("u_brush1", b1)
        u.f2("u_targetSize", w.toFloat(), h.toFloat()); u.f4("u_rect", square)
    }

    // ---- eraser ----
    /** jb_media_erase_dab.vert, after [common]. */
    fun eraseDab(u: UniformBank, w: Int, h: Int) = u.f2("u_targetSize", w.toFloat(), h.toFloat())

    /** jb_media_erase_apply.frag, after [common]. */
    fun erase(u: UniformBank, a: Int, b: Int, c: Int, cover: Int, water: Int, w: Int, h: Int, rect: FloatArray) {
        u.tex("u_a", a); u.tex("u_b", b); u.tex("u_c", c); u.tex("u_cover", cover)
        u.f1("u_water", water); u.f2("u_targetSize", w.toFloat(), h.toFloat()); u.f4("u_rect", rect)
    }

    // ---- wet media ----
    /** jb_wet_dab.vert / .frag, after [common]. */
    fun wetDab(u: UniformBank, w0: Int, paperBake: Int, w: Int, h: Int, pxPerMm: Double, fullMm: Double) {
        u.tex("u_w0", w0); u.tex("u_paperBake", paperBake)
        u.f2("u_targetSize", w.toFloat(), h.toFloat()); u.f1("u_pxPerMm", pxPerMm); u.f1("u_fullMm", fullMm)
    }

    /** jb_wet_flux.frag, after [common] and [WetFrameUniforms.apply]. */
    fun wetFlux(u: UniformBank, bead: Int, p0: Int, w0: Int, flux: Int, paperBake: Int, fluidBake: Int, waterBake: Int,
                rect: FloatArray, w: Int, h: Int) {
        u.tex("u_bead", bead); u.tex("u_p0", p0); u.tex("u_w0", w0); u.tex("u_flux", flux)
        u.tex("u_paperBake", paperBake); u.tex("u_fluidBake", fluidBake); u.tex("u_waterBake", waterBake)
        u.f4("u_rect", rect); u.f2("u_targetSize", w.toFloat(), h.toFloat())
    }

    /** jb_wet_update.frag, after [common] and [WetFrameUniforms.apply]. */
    fun wetUpdate(u: UniformBank, w0: Int, w1: Int, flux: Int, run: Int, p0: Int, p1: Int, paperBake: Int, waterBake: Int,
                  fluidBake: Int, in0: Int, in1: Int, useInput: Int, rect: FloatArray, w: Int, h: Int) {
        u.tex("u_w0", w0); u.tex("u_w1", w1); u.tex("u_flux", flux); u.tex("u_run", run)
        u.tex("u_p0", p0); u.tex("u_p1", p1); u.tex("u_paperBake", paperBake); u.tex("u_waterBake", waterBake)
        u.tex("u_fluidBake", fluidBake); u.tex("u_in0", in0); u.tex("u_in1", in1); u.f1("u_useInput", useInput)
        u.f4("u_rect", rect); u.f2("u_targetSize", w.toFloat(), h.toFloat())
    }

    /** jb_wet_update.frag's wet-age clock: real seconds per simulated second (1 = the water keeps up with the clock). */
    fun wetAgeScale(u: UniformBank, scale: Float) = u.f1("u_ageScale", scale)

    /** jb_wet_bead.frag, after [common]. */
    fun bead(u: UniformBank, rect: FloatArray, beadW: Int, beadH: Int, sigma: Double, pass: Int, w0: Int, src: Int, axisX: Float, axisY: Float) {
        u.f4("u_rect", rect); u.f2("u_targetSize", beadW.toFloat(), beadH.toFloat()); u.f1("u_sigma", sigma)
        u.f1("u_pass", pass); u.tex("u_w0", w0); u.tex("u_src", src); u.f2("u_axis", axisX, axisY)
    }

    // ---- the look ----
    /** jb_media_render.frag (look mode), after [common] and [paper]. */
    fun render(u: UniformBank, p0: Int, p1: Int, paperState: Int, ground: Int, paperBake: Int, w0: Int, w1: Int, w: Int, h: Int,
               originX: Float, originY: Float, scale: Float, pxPerMm: Double, toothMm: Double, look: MediaLook) {
        u.tex("u_p0", p0); u.tex("u_p1", p1); u.tex("u_paperState", paperState); u.tex("u_ground", ground)
        u.tex("u_paperBake", paperBake); u.tex("u_w0", w0); u.tex("u_w1", w1)
        u.f2("u_targetSize", w.toFloat(), h.toFloat()); u.f2("u_pan", -originX * scale, -originY * scale)
        u.f1("u_zoom", scale); u.f1("u_pxPerMm", pxPerMm); u.f1("u_toothMm", toothMm); u.f3("u_paperColor", look.paperColor)
        u.f3("u_lamp", look.lamp); u.f1("u_relief", 0f); u.f1("u_sheen", look.sheen); u.f1("u_capMm", look.capMm); u.f1("u_mode", 0)
        u.f1("u_impasto", look.impasto); u.f1("u_look", 1f)
    }
}

/**
 * The wet passes' per-frame constants, flattened once per [load] (one call of the core `wetUniforms` per FRAME, not per
 * substep or pass) into parallel arrays so the substeps apply them with no map, iterator or boxing. The same names and
 * values the old per-frame HashMap carried: wetUniforms' own entries (with `u_evap` replaced by the override when one is
 * given), plus u_slope / u_cellMm / u_beadCells. [slope] is held by reference and read at [apply], as the old map did.
 */
internal class WetFrameUniforms {
    private var names = arrayOfNulls<String>(48)
    private var values = DoubleArray(48)
    private var count = 0
    private var slope = FloatArray(2)
    private var cellMm = 0.0
    private var beadCells = 0.0

    fun load(base: Map<String, Double>, evapOverride: Double?, slope: FloatArray, cellMm: Double, beadCells: Double) {
        count = 0
        var evapSeen = false
        for ((k, v) in base) {
            if (k == "u_evap" && evapOverride != null) { add(k, evapOverride); evapSeen = true } else add(k, v)
        }
        if (evapOverride != null && !evapSeen) add("u_evap", evapOverride)
        this.slope = slope; this.cellMm = cellMm; this.beadCells = beadCells
    }

    private fun add(name: String, v: Double) {
        if (count == names.size) { names = names.copyOf(count * 2); values = values.copyOf(count * 2) }
        names[count] = name; values[count] = v; count++
    }

    /** Multiplies the loaded value of [name] by [factor] (coarse water: per-cell rates for cells [wf] pixels wide). */
    fun scale(name: String, factor: Double) {
        for (i in 0 until count) if (names[i] == name) values[i] *= factor
    }

    fun apply(u: UniformBank) {
        for (i in 0 until count) u.f1(names[i]!!, values[i])
        u.f2("u_slope", slope[0], slope[1])
        u.f1("u_cellMm", cellMm)
        u.f1("u_beadCells", beadCells)
    }
}
