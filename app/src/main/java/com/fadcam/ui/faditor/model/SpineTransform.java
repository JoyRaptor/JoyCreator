package com.fadcam.ui.faditor.model;

/**
 * WHERE A SPINE (MASTER) CLIP'S PICTURE SITS ON THE CANVAS — the ONE definition of it, shared
 * verbatim by the live preview and by the exporter.
 *
 * <h3>What it is for</h3>
 * <p>JoyRaptor, 2026-09-04: "I've been playing with the idea of making my video that is 9:16 into
 * 16:9. But if I were to do this, all of my clips would be CENTERED. Maybe I'd like to LEFT
 * JUSTIFY them, or right justify, or just freely move them and ROTATE them AS IF THEY WERE A
 * LANE — but have them still take up the spine as a clip."</p>
 *
 * <p>Until now a spine clip had exactly one placement: fit-centred into the canvas. That is the
 * {@code Presentation(outW, outH, LAYOUT_SCALE_TO_FIT)} at the end of
 * {@code ExportManager.assembleClipVideoEffects} and {@code FxPreviewTextureView.stageViewport}
 * in the preview. This class is the transform applied to the RESULT of that step: the clip's
 * finished, canvas-shaped frame is moved, scaled and rotated on the canvas.</p>
 *
 * <h3>Why the transform is applied to the whole canvas frame, letterbox and all</h3>
 * <p>Because the letterbox is transparent in both surfaces ({@code stageViewport} clears to
 * {@code (0,0,0,0)}; media3's {@code Presentation} renders into a buffer cleared to the same),
 * transforming the canvas frame IS transforming the picture. Nothing has to know the source's
 * aspect, the crop's aspect or the clip's rotation, so those keep working untouched — see the
 * composition note on {@link #POSE}.</p>
 *
 * <h3>Affine only. No corner pin on the spine.</h3>
 * <p>Move, per-axis scale and rotate — six numbers, one 2x3. There is deliberately NO perspective
 * term: a pinned spine clip would need a homography drawn by both the preview's chain and the
 * exporter's, and neither has one for the base picture. Authoring a distortion nothing can draw
 * is the failure mode this restraint exists to avoid; the handle surface refuses a non-affine
 * quad rather than storing one (see {@code SpineTransformHost.writeQuad}).</p>
 *
 * <h3>Preview and export cannot drift</h3>
 * <p>They do not each transcribe the maths. Both compile {@link #fragmentShader} — one string,
 * with a single substituted varying name because media3's vertex shader and the preview's call
 * that varying different things — and both upload uniforms produced by {@link #uniforms}. The
 * inverse matrix, the aspect compensation, the edge feather and the out-of-frame rule therefore
 * exist in exactly one place. A change to this file changes both surfaces or neither.</p>
 */
public final class SpineTransform {

    private SpineTransform() {}

    // ── Track names ──────────────────────────────────────────────────────
    //
    // Distinct from KeyframeSet.X/Y/SCALE/ROTATION on purpose, and stored in a KeyframeSet of
    // their own rather than in Clip#overlayTransform. That field is the PiP placement, and
    // ProjectStorage deliberately writes and reads it for MASTER clips too (see its own note:
    // "a clip promoted to the spine would persist its transform and then silently fail to load
    // it back"). Sharing the tracks would make a clip dragged from a layer onto the spine — or
    // back — inherit the other role's placement as if it were its own, and there would be no way
    // to tell the two apart in a file already in the field. Six new names cost one JSON object
    // that older builds ignore; sharing four would corrupt a real project.

    public static final String X = "spineX";
    public static final String Y = "spineY";
    public static final String SCALE = "spineScale";
    public static final String SCALE_X = "spineScaleX";
    public static final String SCALE_Y = "spineScaleY";
    public static final String ROTATION = "spineRotation";

    /** Every track this transform animates, for a bulk remove (reset) or a codec walk. */
    public static String[] tracks() {
        return new String[]{X, Y, SCALE, SCALE_X, SCALE_Y, ROTATION};
    }

    // ── The resolved pose ────────────────────────────────────────────────

    /**
     * Indices into a resolved pose array of length {@link #POSE}.
     *
     * <p>{@code CX}/{@code CY} are the picture's centre as a fraction of the canvas, 0.5/0.5 =
     * centred — the SAME convention every other object in this editor uses for position, which is
     * why the handle surface's {@code writeTranslate} arithmetic is identical for a spine clip and
     * for an image overlay. {@code SC} is the uniform multiplier a pinch drives; {@code SX}/{@code
     * SY} are the per-axis extras a corner or edge drag drives, exactly the chain-unlink split
     * {@code KeyframeSet.SCALE} / {@code SCALE_X} / {@code SCALE_Y} already means for images.
     * {@code ROT} is degrees, positive CLOCKWISE on screen, like {@code View#setRotation} and like
     * every other rotation in this project.</p>
     *
     * <p><b>How this composes with CROP.</b> It does not have to. Crop selects a region of the
     * SOURCE and is applied upstream — media3's {@code Crop} in the export, {@code drawCrop} in
     * the preview — and the fit-to-canvas step downstream of it turns whatever came out into a
     * canvas-shaped frame. This transform then places that canvas frame. So cropping still
     * reframes the footage and this still positions the result, in either order of authoring,
     * with no shared arithmetic to keep in sync.</p>
     */
    public static final int CX = 0, CY = 1, SC = 2, SX = 3, SY = 4, ROT = 5, POSE = 6;

    /** A scale multiplier below this is treated as collapsed and the transform is refused. */
    public static final float MIN_SCALE = 0.02f;
    /** Hard ceiling, so one bad keyframe cannot ask for a 10000x texture read per pixel. */
    public static final float MAX_SCALE = 24f;

    /** Today's exact behaviour: fit-centred, unrotated, unscaled. */
    public static void identity(float[] pose) {
        pose[CX] = 0.5f;
        pose[CY] = 0.5f;
        pose[SC] = 1f;
        pose[SX] = 1f;
        pose[SY] = 1f;
        pose[ROT] = 0f;
    }

    /**
     * Is this pose the plain fit-centre every project has today?
     *
     * <p>THE NO-OP GATE. Both renderers ask this and skip their pass entirely when it is true, so
     * a project with no spine transform runs the identical pass list it ran before this existed —
     * no shader, no framebuffer, no {@code Effect} appended to the chain.</p>
     */
    public static boolean isIdentity(float[] pose) {
        return near(pose[CX], 0.5f) && near(pose[CY], 0.5f)
                && near(pose[SC], 1f) && near(pose[SX], 1f) && near(pose[SY], 1f)
                && near(pose[ROT], 0f);
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-4f;
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    // ── Geometry ─────────────────────────────────────────────────────────

    /**
     * The FORWARD map, canvas NDC → canvas NDC, as a row-major 3x3 with the bottom row implied.
     *
     * <p>NDC is x right in [-1, 1] and <b>y UP</b> in [-1, 1] — the space media3's vertex shader
     * and the preview's both hand a fragment shader, so no surface has to flip anything.</p>
     *
     * <p>Rotation is done in a SQUARE metric (x multiplied by the aspect ratio going in, divided
     * coming out) because NDC is not isotropic: rotating a 16:9 frame directly in NDC shears it
     * instead of turning it. Translation is expressed in canvas fractions and enters that same
     * square metric, which is what makes a saved transform survive a canvas-preset change and any
     * export resolution — nothing here is in pixels.</p>
     *
     * @param aspect canvas width / canvas height
     * @return false when the pose is unusable (non-finite, or collapsed to nothing)
     */
    public static boolean forward(float[] pose, float aspect, float[] out9) {
        for (int i = 0; i < POSE; i++) {
            if (!finite(pose[i])) return false;
        }
        if (!finite(aspect) || aspect <= 0f) return false;
        float kx = pose[SC] * pose[SX];
        float ky = pose[SC] * pose[SY];
        if (Math.abs(kx) < MIN_SCALE || Math.abs(ky) < MIN_SCALE) return false;
        if (Math.abs(kx) > MAX_SCALE || Math.abs(ky) > MAX_SCALE) return false;
        double rad = Math.toRadians(pose[ROT]);
        float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
        // Centre offset in canvas fractions → NDC (span 2) → square metric (x times aspect).
        float tx = (pose[CX] - 0.5f) * 2f * aspect;
        float ty = -(pose[CY] - 0.5f) * 2f;      // CY grows DOWNWARD; NDC y grows up.
        // out = D2 * T * R * S * D1, with D1 = diag(aspect, 1), D2 = diag(1/aspect, 1),
        // R the CLOCKWISE-on-screen rotation [[c, s], [-s, c]] in a y-up frame.
        float a00 = c * kx * aspect;
        float a01 = s * ky;
        float a10 = -s * kx * aspect;
        float a11 = c * ky;
        out9[0] = a00 / aspect;
        out9[1] = a01 / aspect;
        out9[2] = tx / aspect;
        out9[3] = a10;
        out9[4] = a11;
        out9[5] = ty;
        out9[6] = 0f;
        out9[7] = 0f;
        out9[8] = 1f;
        return true;
    }

    /** Invert a 2x3-in-3x3 affine map. False when it is singular. */
    public static boolean invertAffine(float[] m9, float[] out9) {
        float det = m9[0] * m9[4] - m9[1] * m9[3];
        if (!finite(det) || Math.abs(det) < 1e-9f) return false;
        float i00 = m9[4] / det;
        float i01 = -m9[1] / det;
        float i10 = -m9[3] / det;
        float i11 = m9[0] / det;
        out9[0] = i00;
        out9[1] = i01;
        out9[2] = -(i00 * m9[2] + i01 * m9[5]);
        out9[3] = i10;
        out9[4] = i11;
        out9[5] = -(i10 * m9[2] + i11 * m9[5]);
        out9[6] = 0f;
        out9[7] = 0f;
        out9[8] = 1f;
        return true;
    }

    /** Push one NDC point through a 2x3-in-3x3 map. */
    public static void apply(float[] m9, float x, float y, float[] out2) {
        out2[0] = m9[0] * x + m9[1] * y + m9[2];
        out2[1] = m9[3] * x + m9[4] * y + m9[5];
    }

    // ── The shader, and the uniforms both surfaces upload ─────────────────

    /**
     * How wide the anti-aliased edge of the moved picture is, in canvas fractions.
     *
     * <p>A rotated rectangle sampled with a hard in/out test staircases badly at 1080p. One
     * output pixel of feather is enough to kill it and is invisible on an un-rotated transform
     * (where the edge lands on the frame boundary anyway).</p>
     */
    private static final float EDGE_FEATHER_PX = 1.0f;

    /** Number of floats {@link #uniforms} writes. */
    public static final int UNIFORMS = 8;

    /**
     * The six inverse-matrix numbers plus the two feather widths, in the order the shader's
     * {@code uSpineInvA} (vec4), {@code uSpineInvB} (vec2) and {@code uSpineEdge} (vec2) want
     * them.
     *
     * <p><b>This method is the parity guarantee.</b> The preview calls it on its GL thread and the
     * exporter calls it in {@code drawFrame}; neither computes a matrix of its own, and the
     * shader they feed is {@link #fragmentShader}'s single string. There is no second
     * transcription of this geometry anywhere in the app to drift from.</p>
     *
     * @param frameW output frame width in pixels — used ONLY for the edge feather
     * @return false when the pose cannot be drawn; the caller must then skip its pass entirely
     *         and leave the frame exactly as it found it
     */
    public static boolean uniforms(float[] pose, int frameW, int frameH, float[] out8) {
        if (frameW <= 0 || frameH <= 0) return false;
        float aspect = (float) frameW / (float) frameH;
        float[] fwd = new float[9];
        float[] inv = new float[9];
        if (!forward(pose, aspect, fwd)) return false;
        if (!invertAffine(fwd, inv)) return false;
        out8[0] = inv[0];
        out8[1] = inv[1];
        out8[2] = inv[3];
        out8[3] = inv[4];
        out8[4] = inv[2];
        out8[5] = inv[5];
        // The feather is quoted in the SOURCE frame's uv, so it must be pulled back through the
        // scale the transform applies: a picture blown up 4x needs a quarter as much source uv
        // to cover one output pixel.
        float kx = Math.max(MIN_SCALE, Math.abs(pose[SC] * pose[SX]));
        float ky = Math.max(MIN_SCALE, Math.abs(pose[SC] * pose[SY]));
        out8[6] = EDGE_FEATHER_PX / (frameW * kx);
        out8[7] = EDGE_FEATHER_PX / (frameH * ky);
        return true;
    }

    /**
     * The ONE fragment shader, with the caller's varying substituted in.
     *
     * <p>The substitution is the whole of the difference between the two hosts: the preview's
     * vertex shader ({@code FxGlSource.VERTEX_SHADER}) names its uv {@code vFxUv} and media3's
     * names it {@code vTexSamplingCoord}. Every line of arithmetic below is shared text.</p>
     *
     * <p>It is an INVERSE map — for each output pixel, ask where in the input it came from —
     * rather than a vertex transform, so both hosts can stay ordinary full-frame passes and the
     * exporter does not need a {@code MatrixTransformation} whose own configure/letterbox rules
     * would then be a second thing to keep in step.</p>
     *
     * <p>Outside the source frame the result is TRANSPARENT, matching what the letterbox already
     * is in both surfaces: the preview is non-opaque and shows the editor's backdrop through it,
     * and the export's encoder turns un-composited alpha into black exactly as it does for
     * today's fit-centre bars.</p>
     */
    public static String fragmentShader(String varying) {
        // HIGHP WHERE THE DEVICE HAS IT. Unlike the neighbouring passes, which only ever add or
        // multiply a colour, this one RECONSTRUCTS a texture coordinate from a matrix — mediump
        // is about ten mantissa bits on a good many GL ES 2 parts, and at 1080p that is a visible
        // wobble along the moved picture's edge. The guard is the standard one; a device without
        // highp fragment precision falls back to exactly what the other passes use.
        return "#version 100\n"
                + "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
                + "precision highp float;\n"
                + "#else\n"
                + "precision mediump float;\n"
                + "#endif\n"
                + "varying vec2 " + varying + ";\n"
                + "uniform sampler2D uTexSampler;\n"
                + "uniform vec4 uSpineInvA;\n"
                + "uniform vec2 uSpineInvB;\n"
                + "uniform vec2 uSpineEdge;\n"
                + "void main() {\n"
                + "  vec2 q = " + varying + " * 2.0 - 1.0;\n"
                + "  vec2 p = vec2(uSpineInvA.x * q.x + uSpineInvA.y * q.y + uSpineInvB.x,\n"
                + "                uSpineInvA.z * q.x + uSpineInvA.w * q.y + uSpineInvB.y);\n"
                + "  vec2 uv = p * 0.5 + 0.5;\n"
                + "  vec2 e = max(uSpineEdge, vec2(1e-5));\n"
                + "  vec2 k = smoothstep(vec2(0.0), e, uv)\n"
                + "         * (vec2(1.0) - smoothstep(vec2(1.0) - e, vec2(1.0), uv));\n"
                + "  float inside = k.x * k.y;\n"
                + "  gl_FragColor = texture2D(uTexSampler, clamp(uv, 0.0, 1.0)) * inside;\n"
                + "}\n";
    }
}
