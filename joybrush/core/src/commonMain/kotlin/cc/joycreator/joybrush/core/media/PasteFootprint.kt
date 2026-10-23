package cc.joycreator.joybrush.core.media

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Where a paste step can change the canvas: a tight, oriented, conservative box around the brush footprint's SUPPORT.
 *
 * WHY. Each paste step (one per 0.3 mm of pen travel) ran the canvas-side dab pass, and the copy back, over a square
 * of half-size `hypot(halfW, lenMax) + 0.3 mm` (a blade: `bladeLen + bladeHalf + 1.5 mm`). For Oil round that is a
 * 416 px square (~173k px) around a footprint of 15-30k px, and the pass is fill-bound on the Note 9 (Adreno 630,
 * ~54 ms/frame on a scribble). The dab shader (`jb_paste_dab.frag`) starts with `o_p0 = p0; o_p1 = p1` (line 40) and
 * returns that unchanged input at line 47 (blade: `cov <= 0`) and line 83 (hair/trowel/edge: `cover <= 0`); every
 * other write comes after those returns, and NaN results also fall back to the input (lines 72, 141). So any pixel
 * where the shader's coverage is zero is a passthrough, and skipping it (leaving the destination as it was, which is
 * what the copy back would have written) is BIT-EXACT. This file bounds where coverage can be non-zero.
 *
 * The engine must keep drawing the OLD square quad and SCISSOR it to this box, not draw a smaller quad: the vertex
 * shader interpolates `v_docPx`/`v_layerPx` across the quad, so a different quad rounds them differently, which moves
 * the shader's noise by an ulp and breaks bit-exactness (measured on SwiftShader by media_paste_rect_parity_check.js:
 * a smaller quad changes ~10% of the layer's pixels by ulps; scissoring the same quad changes none).
 *
 * It is a pure function of the step, the brush and the two scales, so it is unit-testable without a GPU
 * (PasteFootprintTest) and checked against the real shaders in `tools/media_paste_rect_parity_check.js`.
 *
 * All derivations below are in the shader's own units (mm; one layer px is `px = 1/(pxPerMm * layerScale)` mm) and
 * every bound is an over-estimate: a box that is too big costs a little fill, one that is too small changes pixels.
 *
 * ### Hair brushes (round 0, flat/fan 1, trowel knife 2 and cutting edge 3 without `blade`): `jb_pasteCover`
 *  - ALONG, `b = dot(rel, lDir)`: jb_paste.glsl line 73 returns 0 unless `-aaB <= b <= len + aaB` with `aaB = px`. The flat brush's
 *    arc "lead" (line 90, `b -= lead`) happens AFTER that test and only tightens it further, and the end ramp
 *    (lines 113-114, `aaE = max(aaB, 1.2 slideMm)`) is wider than line 73 so line 73 is the binding limit. The arc
 *    (a fan's deep arc included) therefore needs NO term here: it moves the front inward, never outward.
 *  - ACROSS, `a = dot(rel, wDir) / halfW'` with `halfW' = max(halfW, 1e-3)` (jb_paste_dab.frag line 77), then scaled
 *    by `1 - rag` where `rag = jb_pasteRag` (dab.frag line 78; defined at jb_paste.glsl lines 62-67). The side test is
 *    `smoothstep(half_ + aaA, half_ - aaA, |a|)` (jb_paste.glsl line 99), non-zero only for `|a| < half_ + aaA`, and
 *    `half_ <= 1` for every shape (round: glsl lines 81/85; flat 94, knife 97), `aaA = px / halfW'` (dab.frag line 82).
 *    So `|dot(rel, wDir)| < (halfW' + px) / (1 - rag)`.
 *    `rag` is bounded by its noise range ([0,1) noise, so each `noise - 0.5` is within +-0.5): hair shapes
 *    `|u_rag| * (0.10 * 0.5 + 0.06 * 0.5) = 0.08 |u_rag|` (line 66), shapes above 1.5 `0.14 * 0.5 + 0.05 * 0.5 = 0.095`
 *    (line 65). The worst case is the largest positive `rag` (it shrinks `a`, widening the footprint in mm).
 *  - A fan's `u_sparse` (line 101-104) and the fingers (107-110) only multiply `side` by a factor <= 1, so they never
 *    extend the support.
 *
 * ### Rigid blade (`blade` brushes: Palette knife 2, Scraper): `jb_bladeCover` (jb_paste.glsl 187-195)
 *  - Blade frame: `xb = dot(rel, bladeDir)` along the edge, `yb = dot(rel, perp(bladeDir))` across (lines 188-189).
 *  - `jb_bladeHalf` (184-186) is `bladeHalfW * mix(0.35, 1, smoothstep(0, ., xb))`, so `0.35 bladeHalfW` for `xb <= 0`
 *    and `<= bladeHalfW` everywhere. Edges ramp over `aaS = max(px, 1.2 slideMm)` (line 192).
 *  - `along` (193) is non-zero for `-0.3 half_ - aaS < xb < bladeLen + aaS`; for `xb <= 0` (where the lower limit
 *    lives) `half_ = 0.35 bladeHalfW`, giving `xb > -0.105 bladeHalfW - aaS`.
 *  - The across factor (194) is non-zero for `|yb| < half_ + aaS <= bladeHalfW + aaS`.
 *
 * ### Mapping to the layer
 * The box's four corners are mapped back to doc px with the SAME frame the shader reads
 * (`rel = (docPx - center) / pxPerMm`, so `docPx = center + pxPerMm * (u * uAxis + v * vAxis)`), padded by
 * [SAFETY_PAD_PX] layer px for float32 rounding of `v_docPx` (it is rebuilt from `p / u_layerScale` per pixel), and
 * the axis-aligned bounds of the four corners are taken. Finally the result is intersected with the LEGACY square, so
 * the new rectangle is never larger than the old one (and anything the old square clipped stays clipped: the same
 * behaviour as before, bit for bit). Unit-orthogonal frames are assumed (the step builder produces them); a frame that
 * is not falls back to the legacy square rather than guessing.
 */
object PasteFootprint {
    /** Extra layer px added around the box on every side (float32 rounding of the per-pixel doc position). */
    const val SAFETY_PAD_PX = 2.0

    /** How much more ragged a loaded brush is where it lands (jb_paste.glsl `JB_LAND_RAG`, times `u_land`). */
    const val LAND_RAG = 2.0

    /** `rag` is divided out of the across axis; beyond this the bound explodes, so use the legacy square instead. */
    private const val MAX_RAG = 0.5

    /** An oriented box in a step's frame: `u` along [uX],[uY] (unit, doc space) and `v` along [vX],[vY], in mm from the pen point. */
    class OrientedBox(
        val uX: Double, val uY: Double, val vX: Double, val vY: Double,
        val uMin: Double, val uMax: Double, val vMin: Double, val vMax: Double,
    )

    /** The half-size, in doc px, of the square every paste step used before this existed (still available as the A/B reference). */
    fun legacyHalfSizePx(st: PasteStep, b: PasteBrush, pxPerMm: Double): Double =
        (if (b.blade) (st.bladeLen ?: b.bladeLenMm) + b.bladeHalfMm + 1.5 else hypot(st.halfW, st.lenMax) + 0.3) * pxPerMm

    /** The old square as doc px bounds `[x0, y0, x1, y1]`. */
    fun legacyBounds(st: PasteStep, b: PasteBrush, pxPerMm: Double): DoubleArray {
        val r = legacyHalfSizePx(st, b, pxPerMm)
        return doubleArrayOf(st.x - r, st.y - r, st.x + r, st.y + r)
    }

    /**
     * The oriented support box in mm (see the class notes for every bound), or null when the frame is degenerate and
     * the caller must use the legacy square. Uses the same uniform defaults the engine uploads (`rag` 1, blade sizes
     * from the step first).
     */
    fun orientedBox(st: PasteStep, b: PasteBrush, layerScale: Double, pxPerMm: Double): OrientedBox? {
        val px = 1.0 / (pxPerMm * layerScale)   // one LAYER px in mm: the shader's `px` (dab.frag line 80) / `pxB` (line 44)
        if (!(px > 0.0) || !px.isFinite()) return null
        if (b.blade) {
            val bladeLen = st.bladeLen ?: b.bladeLenMm.takeIf { it != 0.0 } ?: 1.0
            val half = st.bladeHalf ?: b.bladeHalfMm.takeIf { it != 0.0 } ?: 1.0
            if (!unitFrame(st.bladeDirX, st.bladeDirY, -st.bladeDirY, st.bladeDirX)) return null
            val aaS = max(px, 1.2 * st.slideMm)
            return OrientedBox(
                st.bladeDirX, st.bladeDirY, -st.bladeDirY, st.bladeDirX,
                uMin = -0.3 * 0.35 * half - aaS, uMax = bladeLen + aaS,
                vMin = -(half + aaS), vMax = half + aaS,
            )
        }
        if (!unitFrame(st.wDirX, st.wDirY, st.lDirX, st.lDirY)) return null
        // A loaded brush's landing is more ragged (jb_paste.glsl JB_LAND_RAG); its other ends only move inward along the hair.
        val landed = if (b.loadedEnds) 1.0 + LAND_RAG * st.land else 1.0
        val ragMax = if (b.shape > 1) 0.14 * 0.5 + 0.05 * 0.5 else landed * abs(b.rag ?: 1.0) * (0.10 * 0.5 + 0.06 * 0.5)
        if (ragMax > MAX_RAG) return null
        val halfW = max(st.halfW, 1e-3)
        val across = (halfW + px) / (1.0 - ragMax)
        return OrientedBox(
            st.wDirX, st.wDirY, st.lDirX, st.lDirY,
            uMin = -across, uMax = across,
            vMin = -px, vMax = st.len + px,
        )
    }

    /**
     * The doc-px bounds `[x0, y0, x1, y1]` of [orientedBox], padded, BEFORE the intersection with the legacy square
     * (what the unit tests check against the shader's support); null = fall back to the legacy square.
     */
    fun supportBounds(st: PasteStep, b: PasteBrush, layerScale: Double, pxPerMm: Double): DoubleArray? {
        val box = orientedBox(st, b, layerScale, pxPerMm) ?: return null
        var x0 = Double.POSITIVE_INFINITY; var y0 = Double.POSITIVE_INFINITY
        var x1 = Double.NEGATIVE_INFINITY; var y1 = Double.NEGATIVE_INFINITY
        for (u in doubleArrayOf(box.uMin, box.uMax)) for (v in doubleArrayOf(box.vMin, box.vMax)) {
            val x = st.x + pxPerMm * (u * box.uX + v * box.vX)
            val y = st.y + pxPerMm * (u * box.uY + v * box.vY)
            x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
        }
        val pad = SAFETY_PAD_PX / layerScale
        return doubleArrayOf(x0 - pad, y0 - pad, x1 + pad, y1 + pad)
    }

    /** What the engine uses: [supportBounds] clipped to the legacy square (never larger than before), or the square itself. */
    fun docBounds(st: PasteStep, b: PasteBrush, layerScale: Double, pxPerMm: Double): DoubleArray {
        val old = legacyBounds(st, b, pxPerMm)
        val s = supportBounds(st, b, layerScale, pxPerMm) ?: return old
        return doubleArrayOf(max(old[0], s[0]), max(old[1], s[1]), min(old[2], s[2]), min(old[3], s[3]))
    }

    private fun unitFrame(ux: Double, uy: Double, vx: Double, vy: Double): Boolean {
        val tol = 1e-3
        return abs(hypot(ux, uy) - 1.0) < tol && abs(hypot(vx, vy) - 1.0) < tol && abs(ux * vx + uy * vy) < tol
    }
}
