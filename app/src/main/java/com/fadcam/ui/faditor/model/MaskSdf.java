package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The mask, as a SIGNED DISTANCE FIELD — the form both GL renderers can evaluate per pixel.
 *
 * <p>Shaped exactly like {@link ChromaKey}: a GLSL constant, a Java mirror, and a packer. That
 * shape is the point — the export shader and the preview shader concatenate the SAME string, so
 * they cannot disagree about where a mask begins, and the harness can check the arithmetic
 * without looking at exported pixels on a phone.</p>
 *
 * <p><b>Why an SDF rather than the Path the Canvas renderers use.</b> {@code MaskPathBuilder}
 * builds an {@code android.graphics.Path} and clips with it. A GL shader has no Path; it has a
 * coordinate and must answer "am I inside" in closed form. {@code sdRoundBox} is that answer,
 * and it is <b>geometrically exact</b> against {@code Path.addRoundRect} with equal x/y radii —
 * which is precisely what {@code MaskPathBuilder.shapePath} emits. So hard edges match to the
 * pixel.</p>
 *
 * <p><b>What is NOT exact, stated rather than hidden:</b> the feather. A Canvas mask softens
 * through {@code BlurMaskFilter}, a true Gaussian; here it is a {@code smoothstep} across the
 * distance field. They differ slightly, most visibly near a concave join. That is acceptable
 * because BOTH GL renderers use this same field and therefore agree with EACH OTHER, which is
 * the property that actually matters — a preview that matches the export. The PiP Canvas path
 * is left alone.</p>
 *
 * <p>Android-free, so the harness can reach it.</p>
 */
public final class MaskSdf {

    private MaskSdf() {}

    /** Boolean op codes, matching {@link MaskFold}'s ordinals so there is one vocabulary. */
    public static final int OP_UNION = MaskFold.OP_UNION;
    public static final int OP_DIFFERENCE = MaskFold.OP_DIFFERENCE;
    public static final int OP_INTERSECT = MaskFold.OP_INTERSECT;

    /** Floats per shape in the packed array. @see #packShapes */
    public static final int FLOATS_PER_SHAPE = 8;

    /** The most shapes a packed uniform array carries. Beyond this, extra shapes are dropped. */
    public static final int MAX_SHAPES = 8;

    /**
     * The shared mask function. Concatenated by whoever needs it — never re-typed.
     *
     * <p>Rotation happens in PIXEL space, deliberately. Rotating in canvas-normalised
     * coordinates SHEARS the shape on a non-square frame, with the error zero at 0°/180° and
     * largest at 45° — the same lesson {@code MaskAnimator.applyLink} already documents and
     * that {@code MaskAnimatorTest} pins.</p>
     */
    public static final String GLSL_MASK_FN =
            "float sdRoundBox(vec2 p, vec2 b, float r) {\n"
            + "  vec2 q = abs(p) - b + vec2(r);\n"
            + "  return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            // One shape: signed distance in PIXELS, negative inside.
            //   frame = (frameW, frameH)      geo = (cx, cy, w, h)
            //   rc    = (cos, sin)            cf  = corner fraction 0..1
            // Rotation is applied AFTER scaling into pixels, which is what stops a non-square
            // frame shearing the shape.
            + "float fxShapeSd(vec2 uv, vec2 frame, vec4 geo, vec2 rc, float cf) {\n"
            + "  vec2 p = (uv - geo.xy) * frame;\n"
            + "  vec2 rot = vec2(p.x * rc.x + p.y * rc.y, -p.x * rc.y + p.y * rc.x);\n"
            + "  vec2 halfSize = geo.zw * frame * 0.5;\n"
            + "  float rad = cf * min(halfSize.x, halfSize.y);\n"
            + "  return sdRoundBox(rot, halfSize, rad);\n"
            + "}\n"
            // Coverage from a signed distance: 1 inside, 0 outside, feathered across the band.
            // A feather of 0 still gets a half-pixel band so the edge is ANTIALIASED rather
            // than stair-stepped — a hard clip that aliases reads as a rendering fault.
            + "float fxCoverageOf(float sd, float feather) {\n"
            + "  float band = max(feather, 0.75);\n"
            + "  return clamp(0.5 - sd / band, 0.0, 1.0);\n"
            + "}\n";

    /**
     * Pack one shape into the {@link #FLOATS_PER_SHAPE} slots {@code fxShapeSd} reads:
     * {@code [cx, cy, w, h, cos, sin, cornerFrac, feather]}.
     *
     * <p>The rotation arrives PRE-RESOLVED as cos/sin rather than as an angle: a shader that
     * called {@code cos()} per pixel would pay for it on every pixel of every frame to compute
     * a value that is constant across the whole draw.</p>
     */
    @NonNull
    public static float[] packShapes(@Nullable CompositingSpec spec, float frameW, float frameH) {
        int n = spec == null ? 0 : Math.min(spec.masks.size(), MAX_SHAPES);
        float[] out = new float[Math.max(1, n) * FLOATS_PER_SHAPE];
        if (spec == null) return out;
        for (int i = 0; i < n; i++) {
            CompositingSpec.MaskShape m = spec.masks.get(i);
            double rad = Math.toRadians(m.rotationDeg);
            int o = i * FLOATS_PER_SHAPE;
            out[o] = m.cx;
            out[o + 1] = m.cy;
            out[o + 2] = m.w;
            out[o + 3] = m.h;
            out[o + 4] = (float) Math.cos(rad);
            out[o + 5] = (float) Math.sin(rad);
            out[o + 6] = m.corner;
            // Feather in PIXELS, through the same helper the Canvas path uses, so the two
            // renderers at least start from one definition of "how soft is 0.3".
            out[o + 7] = CompositingSpec.featherRadiusPx(
                    spec.featherOf(m), m.w * frameW, m.h * frameH);
        }
        return out;
    }

    /** The boolean op each packed shape folds with, as {@link MaskFold} ordinals. */
    @NonNull
    public static int[] packOps(@Nullable CompositingSpec spec) {
        MaskFold.Fold fold = MaskFold.foldOps(spec);
        int n = Math.min(fold.ops.length, MAX_SHAPES);
        int[] out = new int[Math.max(1, n)];
        System.arraycopy(fold.ops, 0, out, 0, n);
        return out;
    }

    // ── Java mirror ─────────────────────────────────────────────────────────

    /**
     * Java mirror of {@code sdRoundBox}. Exists so the harness can check containment against
     * hand-computed points — the {@link ChromaKey#keepFactor} role.
     *
     * @return signed distance in the same units as {@code p}; negative inside.
     */
    public static float sdRoundBox(float px, float py, float bx, float by, float r) {
        float qx = Math.abs(px) - bx + r;
        float qy = Math.abs(py) - by + r;
        float mx = Math.max(qx, 0f), my = Math.max(qy, 0f);
        return (float) Math.sqrt(mx * mx + my * my) + Math.min(Math.max(qx, qy), 0f) - r;
    }

    /**
     * Java mirror of the whole per-pixel evaluation: fold every shape and return coverage in
     * 0..1 at a canvas-normalised point.
     *
     * <p>Folds in the ORDER {@link MaskFold} reports, using {@code max}/{@code min} on coverage
     * — the continuous analogue of union/intersect/difference, and what lets a FEATHERED
     * subtract exist at all. A {@code Path.op} has no partial coverage, so a soft bite was never
     * expressible on the Canvas path.</p>
     */
    public static float coverage(@Nullable CompositingSpec spec, float uvx, float uvy,
                                 float frameW, float frameH) {
        if (spec == null || spec.masks.isEmpty()) return 1f;
        float[] geo = packShapes(spec, frameW, frameH);
        int[] ops = packOps(spec);
        int n = Math.min(spec.masks.size(), MAX_SHAPES);
        float acc = 0f;
        for (int i = 0; i < n; i++) {
            int o = i * FLOATS_PER_SHAPE;
            float dx = (uvx - geo[o]) * frameW;
            float dy = (uvy - geo[o + 1]) * frameH;
            float cos = geo[o + 4], sin = geo[o + 5];
            float rx = dx * cos + dy * sin;
            float ry = -dx * sin + dy * cos;
            float hw = geo[o + 2] * frameW * 0.5f;
            float hh = geo[o + 3] * frameH * 0.5f;
            float rad = geo[o + 6] * Math.min(hw, hh);
            float sd = sdRoundBox(rx, ry, hw, hh, rad);
            float band = Math.max(geo[o + 7], 0.75f);
            float cov = Math.max(0f, Math.min(1f, 0.5f - sd / band));
            int op = i < ops.length ? ops[i] : OP_UNION;
            if (i == 0) {
                acc = cov;                                  // the seed, as MaskFold reports it
            } else if (op == OP_DIFFERENCE) {
                acc = Math.min(acc, 1f - cov);
            } else if (op == OP_INTERSECT) {
                acc = Math.min(acc, cov);
            } else {
                acc = Math.max(acc, cov);
            }
        }
        // The RAW combined shape. invertMasks is NOT applied here, because what it means
        // depends on the subject — see effectCoverage. Folding it in twice was the bug this
        // separation exists to prevent.
        return acc;
    }

    /**
     * Coverage as the ADJUSTMENT LAYER wants it — how much of the effect lands here.
     *
     * <p>{@code invertMasks} flips which side of the shape is affected. On a PiP the same flag
     * decides where the image is drawn; here it decides where the effect applies, and those are
     * the same question asked of different subjects.</p>
     */
    public static float effectCoverage(@Nullable CompositingSpec spec, float uvx, float uvy,
                                       float frameW, float frameH) {
        if (spec == null || spec.masks.isEmpty()) return 1f;
        float c = coverage(spec, uvx, uvy, frameW, frameH);
        return spec.invertMasks ? c : 1f - c;
    }
}
