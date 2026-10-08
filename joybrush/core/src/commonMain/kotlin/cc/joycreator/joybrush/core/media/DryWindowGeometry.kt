package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Pure-math helper for dry-dab footprints and tile-aligned window placement.
 *
 * Scope: conservative bounds and feasibility only; no clipping, wiring, partitioning,
 * or shader/input changes.
 *
 * Source contracts (read, not duplicated):
 * - Record layout is the 20-float instanced dab the vertex shader reads
 *   (joybrush/shaders/media/jb_dry_dab.vert:7-11; Stick.kt DryStroke.emit builds
 *   `[cx, cy, lx, ly, xMin, xMax, yMax, D, ...]` — indices 0..6 used here).
 * - Corner transform is the shader's (jb_dry_dab.vert:28-34):
 *   `margin = 0.06`, `lo = (xMin - margin, -yMax - margin)`,
 *   `hi = (xMax + margin, yMax + margin)`, `q = mix(lo, hi, corner)`,
 *   `u = lean`, `v = (-u.y, u.x)`, `docPx = centre + (q.x*u + q.y*v) * pxPerMm`.
 * - Window constants are the existing ones (MediaWindowMath.PX = 1024, MARGIN = 128;
 *   Tiles.SIZE = 256). Referenced here, never redefined.
 */
object DryWindowGeometry {
    /** The shader's `const float margin = 0.06` (mm), as a Float to match the GPU constant. */
    const val SHADER_MARGIN_MM = 0.06f

    /** Half-ULP relative bound for normal Float32: 2^-24. See [halfUlpBound]. */
    private const val FLOAT_HALF_ULP_REL = 5.960464477539063e-8

    /** Perturbed-magnitude factor (1 + 2^-24) for second-order Float rounding. */
    private const val FLOAT_PERTURB_FACTOR = 1.0000000596046448

    /** Half-ULP relative bound for normal Float64: 2^-53. Covers Double rounding. */
    private const val DOUBLE_HALF_ULP_REL = 1.1102230246251565e-16

    /** Absolute floor for subnormal/zero Float results (2^-150 ~= 7.006e-46, overestimated). */
    private const val SUBNORMAL_HALF_ULP = 1e-45

    /** Absolute floor for Double subnormal rounding (2^-1075, overestimated by the literal). */
    private const val DOUBLE_SUBNORMAL_HALF_ULP = 5e-324

    /** Largest finite Float32, as Double. Ideals beyond this would overflow the shader to Inf. */
    private val FLOAT_MAX_D = Float.MAX_VALUE.toDouble()

    /**
     * Conservative half-ULP bound for one Float32 rounding of an ideal magnitude [mag].
     *
     * Justification (not arbitrary): for normal Float32, `halfUlp(|z|) <= 2^-24 * |z|`
     * (since `|z|` in `[2^e, 2^(e+1))` has `halfUlp = 2^(e-24)`). This overestimates by < 2x,
     * which is tight yet covers the exact exponent without needing log/pow. Subnormals use
     * a fixed 1e-45 floor covering 2^-150 ~= 7.006e-46. Zero ideal has zero true error;
     * the floor only adds ~1e-45 px, harmless and still conservative.
     */
    private fun halfUlpBound(mag: Double): Double {
        if (!mag.isFinite() || mag == 0.0) return SUBNORMAL_HALF_ULP
        val rel = abs(mag) * FLOAT_HALF_ULP_REL
        if (!rel.isFinite()) return Double.POSITIVE_INFINITY
        return maxOf(rel, SUBNORMAL_HALF_ULP)
    }

    /**
     * Conservative DOCUMENT-pixel AABB for ONE packed dab record.
     *
     * Reads the STORED Floats literally: centre `[offset+0, +1]`, lean `[+2, +3]`,
     * local extents `xMin/xMax/yMax = [+4, +5, +6]` (mm, stick frame). The four shader corners
     * `(loX,loY),(hiX,loY),(loX,hiY),(hiX,hiY)` with `lo=(xMin-m, -yMax-m)`,
     * `hi=(xMax+m, yMax+m)` (`m` = [SHADER_MARGIN_MM]) are transformed with the STORED lean
     * verbatim — never normalised (the shader does not normalise; stored lean is already
     * near-unit but tests include non-unit values, which must scale the box literally).
     * `pxPerMm` is the shader uniform: the Double is converted to Float once (as the GPU sees)
     * and ideals use that Float value; conversion to non-positive/non-finite refuses (null).
     *
     * @param dabs packed dabs (`DabBatch.FLOATS` floats per record).
     * @param offset record start index in [dabs].
     * @param pxPerMm document px per mm; must be finite and > 0.
     * @param padDocPx caller expansion in doc px; must be finite and >= 0.
     * @return `[x0, y0, x1, y1]` (doc px, `x0 <= x1`, `y0 <= y1`), or null on ANY refusal:
     *   bad offset/record bounds (`offset < 0` or fewer than 20 floats remain — no wraparound),
     *   non-finite stored centre/lean/extents, `xMax < xMin`, `yMax < 0`, non-finite/non-positive
     *   `pxPerMm` (incl. Float overflow/underflow-to-zero), non-finite/negative [padDocPx],
     *   or arithmetic overflow (any ideal corner/intermediate with `|ideal| + err` beyond
     *   Float32 max, or final expansion non-finite). Corrupt data yields null, never a guess.
     *   Zero extents (`xMin == xMax == yMax == 0`) are VALID and yield the margin-only box
     *   (`centre +/- m*pxPerMm`, plus numeric padding); zero lean is valid and collapses offsets
     *   to the centre (shader behaviour), still padded.
     *
     * Numeric padding: forward error bound over the shader's 6 Float roundings per axis
     * (lo/hi subtract, two products, add, scale-by-px, add-centre). Each step adds at most
     * [halfUlpBound] of its ideal magnitude, propagated at perturbed magnitude
     * (e.g. `e_ax = e_qx*|ux|*(1+u) + halfUlp(|ax|)`), covering `u*|perturbed|`.
     * FMA contraction does one rounding instead of two, so the separate bound covers it.
     * `mix(lo, hi, 0/1)` selects exactly for finite values. Unconstrained reassociation
     * could add one rounding; optimisers do not increase op count (residual uncertainty).
     * Each axis is padded by the max corner error, then by [padDocPx]. Double rounding of
     * ideals, bounds, and endpoints is covered by a 2^-53-scaled inflation plus outward
     * endpoint slack. Intermediates feed the bound, so cancellation under huge non-unit
     * lean stays enclosed. Near-max corners with `|ideal| + err` beyond Float32 max
     * refuse (conservative: may refuse a corner the GPU would clamp to MAX).
     */
    fun dabDocAabb(
        dabs: FloatArray,
        offset: Int,
        pxPerMm: Double,
        padDocPx: Double = 0.0,
    ): DoubleArray? {
        if (!pxPerMm.isFinite() || pxPerMm <= 0.0) return null
        if (!padDocPx.isFinite() || padDocPx < 0.0) return null
        if (offset < 0 || offset > dabs.size - DabBatch.FLOATS) return null

        val cxF = dabs[offset]
        val cyF = dabs[offset + 1]
        val lxF = dabs[offset + 2]
        val lyF = dabs[offset + 3]
        val xMinF = dabs[offset + 4]
        val xMaxF = dabs[offset + 5]
        val yMaxF = dabs[offset + 6]
        if (!cxF.isFinite() || !cyF.isFinite()) return null
        if (!lxF.isFinite() || !lyF.isFinite()) return null
        if (!xMinF.isFinite() || !xMaxF.isFinite() || !yMaxF.isFinite()) return null
        if (xMaxF < xMinF) return null
        if (yMaxF < 0f) return null

        val pxF = pxPerMm.toFloat()
        if (!pxF.isFinite() || pxF <= 0f) return null

        val mD = SHADER_MARGIN_MM.toDouble()
        val pxD = pxF.toDouble()
        val cxD = cxF.toDouble()
        val cyD = cyF.toDouble()
        val uxD = lxF.toDouble()
        val uyD = lyF.toDouble()
        // Shader: v = (-u.y, u.x). Negation/copy are exact; no rounding.
        val vxD = -uyD
        val vyD = uxD
        val xMinD = xMinF.toDouble()
        val xMaxD = xMaxF.toDouble()
        val yMaxD = yMaxF.toDouble()
        val loXD = xMinD - mD
        val hiXD = xMaxD + mD
        val loYD = -yMaxD - mD
        val hiYD = yMaxD + mD
        if (!loXD.isFinite() || !hiXD.isFinite() || !loYD.isFinite() || !hiYD.isFinite()) return null

        val qxs = doubleArrayOf(loXD, hiXD, loXD, hiXD)
        val qys = doubleArrayOf(loYD, loYD, hiYD, hiYD)

        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var errX = 0.0
        var errY = 0.0

        for (i in 0..3) {
            val qx = qxs[i]
            val qy = qys[i]
            val eQx = halfUlpBound(qx)
            val eQy = halfUlpBound(qy)
            if (abs(qx) + eQx > FLOAT_MAX_D || abs(qy) + eQy > FLOAT_MAX_D) return null

            val ax = qx * uxD
            val bx = qy * vxD
            val sx = ax + bx
            val mx = sx * pxD
            val fx = cxD + mx

            val ay = qx * uyD
            val by = qy * vyD
            val sy = ay + by
            val my = sy * pxD
            val fy = cyD + my

            if (!ax.isFinite() || !bx.isFinite() || !sx.isFinite() || !mx.isFinite() || !fx.isFinite()) return null
            if (!ay.isFinite() || !by.isFinite() || !sy.isFinite() || !my.isFinite() || !fy.isFinite()) return null

            val eAx = eQx * abs(uxD) * FLOAT_PERTURB_FACTOR + halfUlpBound(ax)
            val eBx = eQy * abs(vxD) * FLOAT_PERTURB_FACTOR + halfUlpBound(bx)
            val eSx = (eAx + eBx) * FLOAT_PERTURB_FACTOR + halfUlpBound(sx)
            val eMx = eSx * abs(pxD) * FLOAT_PERTURB_FACTOR + halfUlpBound(mx)
            val eFx = eMx * FLOAT_PERTURB_FACTOR + halfUlpBound(fx)

            val eAy = eQx * abs(uyD) * FLOAT_PERTURB_FACTOR + halfUlpBound(ay)
            val eBy = eQy * abs(vyD) * FLOAT_PERTURB_FACTOR + halfUlpBound(by)
            val eSy = (eAy + eBy) * FLOAT_PERTURB_FACTOR + halfUlpBound(sy)
            val eMy = eSy * abs(pxD) * FLOAT_PERTURB_FACTOR + halfUlpBound(my)
            val eFy = eMy * FLOAT_PERTURB_FACTOR + halfUlpBound(fy)

            if (!eFx.isFinite() || !eFy.isFinite()) return null
            if (abs(ax) + eAx > FLOAT_MAX_D || abs(bx) + eBx > FLOAT_MAX_D ||
                abs(sx) + eSx > FLOAT_MAX_D || abs(mx) + eMx > FLOAT_MAX_D ||
                abs(fx) + eFx > FLOAT_MAX_D
            ) return null
            if (abs(ay) + eAy > FLOAT_MAX_D || abs(by) + eBy > FLOAT_MAX_D ||
                abs(sy) + eSy > FLOAT_MAX_D || abs(my) + eMy > FLOAT_MAX_D ||
                abs(fy) + eFy > FLOAT_MAX_D
            ) return null

            if (fx < minX) minX = fx
            if (fx > maxX) maxX = fx
            if (fy < minY) minY = fy
            if (fy > maxY) maxY = fy
            if (eFx > errX) errX = eFx
            if (eFy > errY) errY = eFy
        }

        if (!minX.isFinite() || !maxX.isFinite() || !minY.isFinite() || !maxY.isFinite()) return null
        if (!errX.isFinite() || !errY.isFinite()) return null

        // Cover Double rounding of ideals/bounds: ~12 Double roundings per axis, use 16x.
        // The (1+u_f) factors above already dominate Double ideal error by ~30x; this is explicit.
        val doubleBoundFactor = 1.0 + 16.0 * DOUBLE_HALF_ULP_REL
        errX = errX * doubleBoundFactor + DOUBLE_SUBNORMAL_HALF_ULP * 8.0
        errY = errY * doubleBoundFactor + DOUBLE_SUBNORMAL_HALF_ULP * 8.0
        if (!errX.isFinite() || !errY.isFinite()) return null
        if (abs(minX) + errX > FLOAT_MAX_D || abs(maxX) + errX > FLOAT_MAX_D ||
            abs(minY) + errY > FLOAT_MAX_D || abs(maxY) + errY > FLOAT_MAX_D
        ) return null

        val x0raw = minX - errX - padDocPx
        val x1raw = maxX + errX + padDocPx
        val y0raw = minY - errY - padDocPx
        val y1raw = maxY + errY + padDocPx
        if (!x0raw.isFinite() || !x1raw.isFinite() || !y0raw.isFinite() || !y1raw.isFinite()) return null
        // Outward Double slack for the two endpoint subtractions/additions plus the final
        // outward subtraction itself (scale includes |raw|, so final rounding is covered).
        val dx0 = (abs(minX) + abs(errX) + abs(padDocPx) + abs(x0raw)) * DOUBLE_HALF_ULP_REL * 4.0 +
            DOUBLE_SUBNORMAL_HALF_ULP * 4.0
        val dx1 = (abs(maxX) + abs(errX) + abs(padDocPx) + abs(x1raw)) * DOUBLE_HALF_ULP_REL * 4.0 +
            DOUBLE_SUBNORMAL_HALF_ULP * 4.0
        val dy0 = (abs(minY) + abs(errY) + abs(padDocPx) + abs(y0raw)) * DOUBLE_HALF_ULP_REL * 4.0 +
            DOUBLE_SUBNORMAL_HALF_ULP * 4.0
        val dy1 = (abs(maxY) + abs(errY) + abs(padDocPx) + abs(y1raw)) * DOUBLE_HALF_ULP_REL * 4.0 +
            DOUBLE_SUBNORMAL_HALF_ULP * 4.0
        if (!dx0.isFinite() || !dx1.isFinite() || !dy0.isFinite() || !dy1.isFinite()) return null
        val x0 = x0raw - dx0
        val x1 = x1raw + dx1
        val y0 = y0raw - dy0
        val y1 = y1raw + dy1
        if (!x0.isFinite() || !x1.isFinite() || !y0.isFinite() || !y1.isFinite()) return null
        if (x0 > x1 || y0 > y1) return null
        return doubleArrayOf(x0, y0, x1, y1)
    }

    private fun doubleIntegralToLongOrNull(v: Double): Long? {
        if (!v.isFinite()) return null
        // Long range is [-2^63, 2^63 - 1]; 2^63 is exactly representable, so >= 2^63 is out.
        if (v < -9.223372036854776e18 || v >= 9.223372036854776e18) return null
        return v.toLong()
    }

    /**
     * Tile-aligned window origin feasibility for a finite ordered document rect.
     *
     * Uses the existing constants only (`MediaWindowMath.PX`, `MediaWindowMath.MARGIN`,
     * `Tiles.SIZE`); the window at top-left tile `(tx, ty)` covers
     * `[tx*256, tx*256 + 1024]` and holds the rect iff
     * `MediaWindowMath.holds(tx, ty, rect)` (128 px margin inside). Feasibility is the
     * ceil/floor interval: `ox in [x1 + M - PX, x0 - M]`, `tx in [ceil(loX/256), floor(hiX/256)]`
     * (same for Y, with exact negative ceil/floor via [ceil]/[floor]).
     *
     * `holds` is exact for Int tiles (`|tx*256| <= ~5.5e11 < 2^53`, integer `ox + margin`
     * exactly representable), while `loX/hiX` carry a few Double ulps. The computed
     * interval can therefore admit an infeasible endpoint while a valid neighbour exists.
     * The clamped preference is tried first (preserved when valid); otherwise a bounded
     * row-major smallest-first scan of the computed interval returns the first verified
     * candidate. Every candidate is verified by `holds`, so the scan only cures false
     * refusal, never unsoundness.
     *
     * @param docRect `[x0, y0, x1, y1]` doc px; must be finite with `x0 <= x1`, `y0 <= y1`
     *   (zero-area points/lines allowed). Size < 4, non-finite, or unordered yields null.
     * @param preferredTx optional preferred tile X; the clamped value is returned when it
     *   verifies, otherwise the smallest verifying tile is returned.
     * @param preferredTy same for Y.
     * @return `(tx, ty)` verified by `MediaWindowMath.holds`, or null when none verifies
     *   (rect larger than `PX - 2*MARGIN` = 768 on either axis, alignment gap for exactly-768
     *   rects, Double overflow to non-finite bounds, failed verification, or coordinates
     *   needing a tile outside Int range).
     *
     * Int safety (no saturating/wrapping conversion): Double ceil/floor bounds are range-checked
     * before `toLong`/`toInt`; out-of-Long or out-of-Int intervals yield null (unsupported
     * coordinates, roughly `|doc| > ~5.5e11 px` needing `|tx| > Int.MAX`). Spans wider than
     * 16 per axis yield null rather than an unbounded scan (true width for `w <= 768` is at
     * most 4 per axis; Int-range Double error is far below 256 px, so wider means
     * pathological rounding outside supported coords).
     */
    fun windowOriginFor(
        docRect: DoubleArray,
        preferredTx: Int? = null,
        preferredTy: Int? = null,
    ): Pair<Int, Int>? {
        if (docRect.size < 4) return null
        val x0 = docRect[0]
        val y0 = docRect[1]
        val x1 = docRect[2]
        val y1 = docRect[3]
        if (!x0.isFinite() || !y0.isFinite() || !x1.isFinite() || !y1.isFinite()) return null
        if (x0 > x1 || y0 > y1) return null

        val pxD = MediaWindowMath.PX.toDouble()
        val marginD = MediaWindowMath.MARGIN.toDouble()
        val tileD = Tiles.SIZE.toDouble()

        val w = x1 - x0
        val h = y1 - y0
        if (!w.isFinite() || !h.isFinite()) return null
        val inner = pxD - 2.0 * marginD
        if (w > inner || h > inner) return null

        val loX = x1 + marginD - pxD
        val hiX = x0 - marginD
        val loY = y1 + marginD - pxD
        val hiY = y0 - marginD
        if (!loX.isFinite() || !hiX.isFinite() || !loY.isFinite() || !hiY.isFinite()) return null
        if (loX > hiX || loY > hiY) return null

        val txLoD = ceil(loX / tileD)
        val txHiD = floor(hiX / tileD)
        val tyLoD = ceil(loY / tileD)
        val tyHiD = floor(hiY / tileD)
        if (!txLoD.isFinite() || !txHiD.isFinite() || !tyLoD.isFinite() || !tyHiD.isFinite()) return null

        val txLoL = doubleIntegralToLongOrNull(txLoD) ?: return null
        val txHiL = doubleIntegralToLongOrNull(txHiD) ?: return null
        val tyLoL = doubleIntegralToLongOrNull(tyLoD) ?: return null
        val tyHiL = doubleIntegralToLongOrNull(tyHiD) ?: return null
        if (txLoL > txHiL || tyLoL > tyHiL) return null

        val txMinL = maxOf(txLoL, Int.MIN_VALUE.toLong())
        val txMaxL = minOf(txHiL, Int.MAX_VALUE.toLong())
        val tyMinL = maxOf(tyLoL, Int.MIN_VALUE.toLong())
        val tyMaxL = minOf(tyHiL, Int.MAX_VALUE.toLong())
        if (txMinL > txMaxL || tyMinL > tyMaxL) return null

        val txMinI = txMinL.toInt()
        val txMaxI = txMaxL.toInt()
        val tyMinI = tyMinL.toInt()
        val tyMaxI = tyMaxL.toInt()
        if (txMaxI.toLong() - txMinI.toLong() > 16L || tyMaxI.toLong() - tyMinI.toLong() > 16L) return null

        val prefTx = preferredTx?.coerceIn(txMinI, txMaxI) ?: txMinI
        val prefTy = preferredTy?.coerceIn(tyMinI, tyMaxI) ?: tyMinI
        if (MediaWindowMath.holds(prefTx, prefTy, docRect)) return Pair(prefTx, prefTy)
        for (ty in tyMinI..tyMaxI) {
            for (tx in txMinI..txMaxI) {
                if (tx == prefTx && ty == prefTy) continue
                if (MediaWindowMath.holds(tx, ty, docRect)) return Pair(tx, ty)
            }
        }
        return null
    }
}
