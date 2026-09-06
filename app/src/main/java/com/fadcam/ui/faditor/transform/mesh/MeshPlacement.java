package com.fadcam.ui.faditor.transform.mesh;

/**
 * THE ONE PLACEMENT AUTHORITY for the mesh stamp — preview and export build their matrices HERE.
 *
 * <p><b>Why this file exists.</b> Today two copies of the image-placement arithmetic are kept in
 * lockstep by comments ({@code TextOverlayLayer.position} for the preview, {@code ImageOverlayDraw}
 * for the export). Adding a third transcription for the mesh is how preview and export start
 * disagreeing about where a bent picture is — the top-severity bug class in this codebase. So the
 * mesh-specific part (pivot fold + unit-square-to-clip matrix) lives here, once, android-free and
 * harness-testable. Callers hand in the SAME numbers their flat path already agreed on
 * (animated centre/size/scale/rotation, preset fold, pivot offsets from the model's ONE shared
 * definition, corner-pin offsets) and get back GL-ready column-major matrices. There is no second
 * copy of the matrix math to drift.
 *
 * <h3>Coordinate spaces (read twice)</h3>
 * <ul>
 *   <li>Model/inputs: top-left origin, 0..1 (0,0 top-left, 1,1 bottom-right) — the space
 *       {@code TextOverlayItem} centres, {@code CornerPin} offsets and {@code CaptionAnimator}
 *       preset {@code dx/dy} all speak. {@code wNorm/hNorm} are unfolded width/height as FRACTIONS
 *       of the frame ({@code iw/outW}, {@code ih/outH} without the preset).</li>
 *   <li>GL/outputs: column-major 3x3 for {@code glUniformMatrix3fv(transpose=false)}. {@code uPlace}
 *       maps object-local unit square (top-left 0..1, deformed {@code D(u,v)} after the homography)
 *       to clip space (-1..1, y-up), preserving the homography's {@code w} (affine last row).
 *       {@code uHomography} maps deformed local to pinned local (still top-left 0..1); the flip to
 *       GL lives in {@code uPlace}, so the forward pin needs NO flip conjugation (that conjugation
 *       belongs to the preview's INVERSE fragment map only).</li>
 * </ul>
 *
 * <h3>Fold mirrors the flat preview exactly</h3>
 * <p>{@link #fold} is {@code TextOverlayLayer.fxPipFor}'s pivot + preset fold, line for line, in
 * the same order with the same trig: fold preset scale about the pivot, rotate about the pivot,
 * then translate by the preset. At the centre pivot (or while the finger is down) the block is
 * skipped and the expressions are the unfolded ones — byte-for-byte the flat path. If that host
 * method ever changes, this must change with it (future refactor: have the host call this).
 *
 * <p>No Android imports.
 */
public final class MeshPlacement {

    private MeshPlacement() {}

    /**
     * Fold preset scale/translation + rotation pivot into centre/half-extents, mirroring the flat
     * preview. All inputs/outputs normalized 0..1, top-left origin.
     *
     * @param cx,cy            animated centre (without preset)
     * @param wNorm,hNorm      unfolded width/height fractions (without preset)
     * @param pivOffX,pivOffY  pivot offset fractions from {@code TextOverlayItem}
     *                         {@code pivotOffsetFromCentreX/Y} (same units as wNorm/hNorm, ends up
     *                         including pins); ignored when {@code applyPivot} is false
     * @param applyPivot       false while the finger is down or when
     *                         {@code isRotationPivotNeutral(pin)} (centre + flat) — then the whole
     *                         pivot block is skipped like the host
     * @param rotDeg           animated rotation, clockwise-positive on screen (model convention)
     * @param presetScaleX,presetScaleY preset whole-body scale (1 when live/off)
     * @param dxNorm,dyNorm    preset translation as frame fractions (0 when live/off)
     * @param out4             receives {@code {foldedCx, foldedCy, halfW, halfH}}
     */
    public static void fold(float cx, float cy, float wNorm, float hNorm,
                            float pivOffX, float pivOffY, boolean applyPivot,
                            float rotDeg, float presetScaleX, float presetScaleY,
                            float dxNorm, float dyNorm, float[] out4) {
        float halfW = (wNorm * presetScaleX) * 0.5f;
        float halfH = (hNorm * presetScaleY) * 0.5f;
        float fx = cx, fy = cy;
        if (applyPivot) {
            float pvx = cx + pivOffX;
            float pvy = cy + pivOffY;
            float scx = pvx + presetScaleX * (cx - pvx);
            float scy = pvy + presetScaleY * (cy - pvy);
            double rad = Math.toRadians(rotDeg);
            float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
            float vx = scx - pvx, vy = scy - pvy;
            fx = pvx + vx * c - vy * s;
            fy = pvy + vx * s + vy * c;
        }
        fx += dxNorm;
        fy += dyNorm;
        out4[0] = fx;
        out4[1] = fy;
        out4[2] = halfW;
        out4[3] = halfH;
    }

    /**
     * Build {@code uPlace} column-major from folded top-left placement. Returns false when the
     * pose cannot be drawn (non-finite, collapsed half, bad aspect) — the caller must then draw
     * the ordinary unbent way rather than through garbage (same recoverable rule
     * {@code drawCrop} follows).
     *
     * @param cxTop,cyTop folded centre, top-left 0..1
     * @param halfW,halfH folded half-extents as frame fractions (must be &gt; 0)
     * @param rotTopDeg   rotation, clockwise-positive on screen (model convention; negated
     *                    internally for GL's y-up)
     * @param frameAspect frame width / height (must be finite &gt; 0)
     * @param outCol9     receives column-major 3x3 for {@code glUniformMatrix3fv}
     */
    public static boolean buildPlace(float cxTop, float cyTop, float halfW, float halfH,
                                     float rotTopDeg, float frameAspect, float[] outCol9) {
        if (outCol9 == null || outCol9.length < 9) return false;
        if (!finite(cxTop) || !finite(cyTop) || !finite(halfW) || !finite(halfH)
                || !finite(rotTopDeg) || !finite(frameAspect)) return false;
        if (halfW <= 0f || halfH <= 0f || frameAspect <= 0f) return false;
        float cxGl = cxTop;
        float cyGl = 1f - cyTop;
        if (!finite(cxGl) || !finite(cyGl)) return false;
        double rad = Math.toRadians(-rotTopDeg);
        float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
        if (!finite(c) || !finite(s)) return false;
        // Row-major affine mapping (x,y) top-left 0..1 -> clip, derived from the Pip inverse
        // (aspect-correct unrotate, divide by half) run forwards; y-flip (qy = 1-2y) and the
        // rotation negation above convert top-left/clockwise to GL bottom-up. See the class note
        // for the derivation; identity (0.5,0.5,0.5,0.5,0,1) yields [2,0,-1 / 0,-2,1 / 0,0,1].
        float r00 = 4f * c * halfW;
        float r01 = 4f * s * halfH / frameAspect;
        float r02 = -2f * c * halfW - 2f * s * halfH / frameAspect + 2f * cxGl - 1f;
        float r10 = 4f * s * frameAspect * halfW;
        float r11 = -4f * c * halfH;
        float r12 = -2f * s * frameAspect * halfW + 2f * c * halfH + 2f * cyGl - 1f;
        if (!finite(r00) || !finite(r01) || !finite(r02)
                || !finite(r10) || !finite(r11) || !finite(r12)) return false;
        // Transpose to column-major for glUniformMatrix3fv(transpose=false).
        outCol9[0] = r00; outCol9[1] = r10; outCol9[2] = 0f;
        outCol9[3] = r01; outCol9[4] = r11; outCol9[5] = 0f;
        outCol9[6] = r02; outCol9[7] = r12; outCol9[8] = 1f;
        return true;
    }

    /** Column-major identity 3x3 (flat homography: no corner pin). */
    public static void identity3(float[] outCol9) {
        if (outCol9 == null || outCol9.length < 9) return;
        outCol9[0] = 1f; outCol9[1] = 0f; outCol9[2] = 0f;
        outCol9[3] = 0f; outCol9[4] = 1f; outCol9[5] = 0f;
        outCol9[6] = 0f; outCol9[7] = 0f; outCol9[8] = 1f;
    }

    /**
     * Transpose a row-major 3x3 (as {@code android.graphics.Matrix.getValues} returns) to
     * column-major for GL. Pure transpose — no math, so it cannot drift from the solver
     * ({@code CornerPin.buildMatrix}) that produced the input.
     */
    public static void transpose9(float[] row9, float[] outCol9) {
        if (row9 == null || row9.length < 9 || outCol9 == null || outCol9.length < 9) return;
        outCol9[0] = row9[0]; outCol9[1] = row9[3]; outCol9[2] = row9[6];
        outCol9[3] = row9[1]; outCol9[4] = row9[4]; outCol9[5] = row9[7];
        outCol9[6] = row9[2]; outCol9[7] = row9[5]; outCol9[8] = row9[8];
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }
}
