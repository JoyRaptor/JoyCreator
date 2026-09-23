package com.fadcam.ui.faditor.compositor;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.fx.FxGlSource;
import com.fadcam.ui.faditor.fx.FxUniforms;

import java.util.HashMap;
import java.util.Map;

/**
 * The ONE statement of how a {@link FxPreviewTextureView.Pip} is drawn: which shader, and every
 * uniform it reads. Shared by the preview composite ({@link FxPreviewTextureView#drawPip}) and the
 * export's GL image pass ({@code GlImageOverlayEffect}), so an image, its mask, blend, key, FX,
 * pin and reveal cannot render one way in the editor and another way in the file.
 *
 * <p>Textures are the caller's business (the preview samples its FBO chain and live decoder
 * surfaces; the export samples Media3's input texture) — everything else lives here.</p>
 */
public final class PipGl {

    private static final String TAG = "PipGl";

    private PipGl() { }

    /** Every non-texture uniform {@link FxPreviewTextureView#drawPip} sets, for one Pip. */
    static void applyUniforms(int program, @NonNull FxPreviewTextureView.Pip p,
                              int vw, int vh, int shapesCompiled) {
        setF2(program, "uTexel", 1f / vw, 1f / vh);
        setF(program, "uAspect", (float) vw / (float) vh);
        setF(program, "uTime", p.timeSec);
        setF(program, "uPipBlend", p.blendMode);
        setF(program, "uPipMaskOn", p.maskOn ? 1f : 0f);
        if (p.pinned()) {
            int pinLoc = GLES20.glGetUniformLocation(program, "uPinInv");
            // transpose MUST be false on GL ES 2.0; pinInv is already column-major. See Pip.
            if (pinLoc >= 0) GLES20.glUniformMatrix3fv(pinLoc, 1, false, p.pinInv, 0);
            setFn(program, "uPipPad", p.pinPad, 4);
        }
        setF(program, "uPipMaskInvert", p.maskInvert ? 1f : 0f);
        // EVERY packed shape, not shape 0. See Pip.maskShapes.
        int shapes = shapesCompiled > 0 ? Math.min(p.maskShapes, shapesCompiled) : p.maskShapes;
        setF4v(program, "uPipMaskGeo", p.maskGeo4, shapes);
        setF2v(program, "uPipMaskRot", p.maskRot2, shapes);
        setF1v(program, "uPipMaskCorner", p.maskCorner, shapes);
        setF1v(program, "uPipMaskFeather", p.maskFeather, shapes);
        setF1v(program, "uPipMaskOp", p.maskOpCodes, shapes);
        setF2(program, "uPipTexel", 1f / vw, 1f / vh);
        setF2(program, "uDir", 1f, 0f);
        if (p.extras) {
            setFn(program, "uPipKeyColor", p.keyColor, 3);
            setFn(program, "uPipKeyParams", p.keyParams, 4);
            setF(program, "uPipReveal", p.revealFrac);
        }
        for (FxUniforms.Value v : p.fxUniforms) {
            setFn(program, v.name, v.data, v.components());
        }
        double rad = Math.toRadians(p.rotationDeg);
        setF2(program, "uPipCentre", p.cx, p.cy);
        setF2(program, "uPipHalf", p.halfW, p.halfH);
        setF(program, "uPipCos", (float) Math.cos(rad));
        setF(program, "uPipSin", (float) Math.sin(rad));
        setF(program, "uPipAspect", (float) vw / (float) vh);
        setF(program, "uPipAlpha", p.alpha);
        setF(program, "uPipRotation", p.rotationDeg);
        setF(program, "uMatteOn", p.matteOn ? 1f : 0f);
        if (p.matteOn) {
            double mRad = Math.toRadians(p.matteRotationDeg);
            setF2(program, "uMatteCentre", p.matteCx, p.matteCy);
            setF2(program, "uMatteHalf", p.matteHalfW, p.matteHalfH);
            setF(program, "uMatteCos", (float) Math.cos(mRad));
            setF(program, "uMatteSin", (float) Math.sin(mRad));
            setF(program, "uMatteAspect", (float) vw / (float) vh);
        }
    }

    /**
     * Compiled Pip programs for one GL context, keyed exactly as the preview keys them. Falls
     * back like the preview: FX stack refused -> plain; many-shape mask refused -> one shape;
     * pinned refused -> flat. A 0 result means "draw nothing", never a crash.
     */
    public static final class Programs {
        private final Map<String, Integer> programs = new HashMap<>();
        private final Map<Integer, Integer> shapesCompiled = new HashMap<>();

        public int programFor(@NonNull FxPreviewTextureView.Pip p) {
            String key = (p.meshComposite ? "m" : (p.still == null ? "o" : "s"))
                    + (p.extras ? "x" : "-") + (p.pinned() ? "p" : "-")
                    + "m" + p.maskShapes + p.fxKey;
            Integer have = programs.get(key);
            if (have != null) return have;
            boolean still = p.still != null;
            int prog = tryBuild(FxPreviewTextureView.pipFragment(p.fused, still, p.extras,
                    p.maskShapes, p.pinned(), p.meshComposite));
            if (prog == 0) {
                prog = tryBuild(FxPreviewTextureView.pipFragment(null, still, p.extras,
                        p.maskShapes, p.pinned(), p.meshComposite));
            }
            if (prog == 0 && p.maskShapes > 1) {
                prog = tryBuild(FxPreviewTextureView.pipFragment(null, still, p.extras, 1,
                        p.pinned(), p.meshComposite));
                if (prog != 0) shapesCompiled.put(prog, 1);
            }
            if (prog == 0 && p.pinned()) {
                prog = tryBuild(FxPreviewTextureView.pipFragment(null, still, p.extras, 1,
                        false, false));
                if (prog != 0) shapesCompiled.put(prog, 1);
            }
            programs.put(key, prog);
            return prog;
        }

        int shapesCompiled(int program) {
            Integer s = shapesCompiled.get(program);
            return s == null ? -1 : s;
        }

        public void release() {
            for (Integer p : programs.values()) {
                if (p != null && p != 0) {
                    try { GLES20.glDeleteProgram(p); } catch (RuntimeException ignored) { }
                }
            }
            programs.clear();
            shapesCompiled.clear();
        }

        private static int tryBuild(@NonNull String fragment) {
            try {
                return buildProgram(FxGlSource.VERTEX_SHADER, fragment);
            } catch (RuntimeException e) {
                FLog.w(TAG, "Pip program refused", e);
                return 0;
            }
        }
    }

    /** False for a Pip that would draw nothing (fully transparent, degenerate, wiped away). */
    public static boolean rendersAnything(@NonNull FxPreviewTextureView.Pip p) {
        return p.rendersAnything();
    }

    /**
     * Draw one STILL Pip (an image overlay's decoded picture, uploaded as {@code stillTex})
     * over the frame in {@code srcTex}, into {@code dstFbo} - exactly the preview's
     * {@code drawPip} for a still. Returns false when no program could be built.
     *
     * @param quad the full-frame clip-space quad, 4 vertices of vec4 (Media3's bounds)
     */
    public static boolean drawStill(@NonNull FxPreviewTextureView.Pip p, @NonNull Programs programs,
                                    int srcTex, int stillTex, int dstFbo, int vw, int vh,
                                    @NonNull java.nio.FloatBuffer quad) {
        int program = programs.programFor(p);
        if (program == 0) return false;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glUseProgram(program);
        int aPos = GLES20.glGetAttribLocation(program, "aFramePosition");
        if (aPos >= 0) {
            quad.position(0);
            GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glEnableVertexAttribArray(aPos);
        }
        applyUniforms(program, p, vw, vh, programs.shapesCompiled(program));
        int loc = GLES20.glGetUniformLocation(program, "uTexSampler");
        if (loc >= 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex);
            GLES20.glUniform1i(loc, 0);
        }
        loc = GLES20.glGetUniformLocation(program, "uPipTexture");
        if (loc >= 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stillTex);
            GLES20.glUniform1i(loc, 1);
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        return true;
    }

    /** A 2D texture set up exactly as the preview's still textures (linear, clamped). */
    public static int newStillTexture() {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return ids[0];
    }

    // ── GL plumbing (the preview's own helpers, made static) ──────────────────────────────

    static void setF(int program, @NonNull String name, float v) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc >= 0) GLES20.glUniform1f(loc, v);
    }

    static void setF2(int program, @NonNull String name, float a, float b) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc >= 0) GLES20.glUniform2f(loc, a, b);
    }

    static void setFn(int program, @NonNull String name, @NonNull float[] v, int components) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc < 0) return;
        switch (components) {
            case 2: GLES20.glUniform2f(loc, v[0], v[1]); break;
            case 3: GLES20.glUniform3f(loc, v[0], v[1], v[2]); break;
            case 4: GLES20.glUniform4f(loc, v[0], v[1], v[2], v[3]); break;
            default: GLES20.glUniform1f(loc, v[0]);
        }
    }

    static void setF1v(int program, @NonNull String name, @NonNull float[] v, int count) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc >= 0) GLES20.glUniform1fv(loc, Math.max(1, count), v, 0);
    }

    static void setF2v(int program, @NonNull String name, @NonNull float[] v, int count) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc >= 0) GLES20.glUniform2fv(loc, Math.max(1, count), v, 0);
    }

    static void setF4v(int program, @NonNull String name, @NonNull float[] v, int count) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc >= 0) GLES20.glUniform4fv(loc, Math.max(1, count), v, 0);
    }

    public static int buildProgram(@NonNull String vertex, @NonNull String fragment) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException("Pip program link failed: " + log);
        }
        return p;
    }

    private static int compileShader(int type, @NonNull String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("Pip shader compile failed: " + log);
        }
        return s;
    }
}
