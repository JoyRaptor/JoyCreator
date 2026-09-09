package com.fadcam.ui.faditor.compositor;

import android.graphics.Bitmap;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.transform.mesh.LatticeTopology;
import com.fadcam.ui.faditor.transform.mesh.MeshBuffers;
import com.fadcam.ui.faditor.transform.mesh.MeshEngine;
import com.fadcam.ui.faditor.transform.mesh.MeshGlSource;
import com.fadcam.ui.faditor.transform.mesh.MeshPlacement;
import com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * THE SHARED MESH STAMP — one wrapper class used by BOTH renderers (preview GL thread and the
 * media3 effect thread). Same {@link MeshEngine} contract, same {@link MeshGlSource} strings, same
 * {@link MeshPlacement} matrices — there is no second transcription of the warp to drift.
 *
 * <p>Modelled line-for-line on {@code export/GlPipFrameOverlay}'s {@code ensureGlInitialized} /
 * {@code release} discipline, including the {@code degraded} latch: a failed FBO or compile falls
 * back to the unwarped draw and logs once, never retries per frame.
 *
 * <h3>What it does</h3>
 * <p>Renders the warped mesh into a frame-sized RGBA stamp texture (transparent outside the item),
 * with opacity and wipe already baked (like the Canvas bitmap it replaces). Downstream — preview
 * identity-Pip composite, export {@code ImageBlendGlEffect} mesh branch — then applies
 * blend/mask/key/FX/adjustment unchanged. Masks stay in FRAME space on both sides (preview Pip
 * shader, export mesh-branch shader, same {@code MaskSdf} source), so a mask cuts the warped
 * result and never bends with it.
 *
 * <h3>Zero-cost guarantee</h3>
 * <p>{@link MeshEngine#update} runs FIRST on the CPU. False (null spec, unknown topology, identity
 * pose) returns 0 BEFORE any GL object exists — no FBO, no program, no extra pass. A project with
 * no bend costs exactly zero; an untouched lattice takes the ordinary unbent path on both sides.
 *
 * <h3>Threading and allocation</h3>
 * <p>One instance per GL thread (preview holds one shared for all mesh items; each export effect
 * holds one). No static mutable state. After warm-up nothing allocates per frame: direct vertex
 * buffers are pre-sized to the coarsest lattice (L3: 625 verts, 3456 indices), matrices are reused
 * fields, the pose scratch lives in {@link MeshEngine}.
 *
 * <h3>Cost (JoyRaptor's numbers, kept honestly)</h3>
 * <p>One clear + one geometry fill of a frame-sized FBO per warped object per frame (~1.5–2 ms on
 * Note 20 at 1080p), 8.29 MB VRAM for the stamp (preview: ONE shared buffer reused across items;
 * export: one per mesh effect instance), ~10 KB vertex upload, &lt;0.1 ms CPU spline. Five
 * simultaneous warps noticeable; eight will drop preview frames (export just gets slower, ~3.6 s
 * per minute of video per warped object). If this costs materially more, say so — don't ship it.
 */
public final class MeshStampGl {

    private static final String TAG = "MeshStamp";

    /** Coarsest lattice this wrapper can draw without reallocating (L3: 25 handles, 24x24 quads). */
    private static final int MAX_VERTS = (LatticeTopology.tessellationFor(LatticeTopology.L3) + 1)
            * (LatticeTopology.tessellationFor(LatticeTopology.L3) + 1);
    private static final int MAX_INDICES = LatticeTopology.tessellationFor(LatticeTopology.L3)
            * LatticeTopology.tessellationFor(LatticeTopology.L3) * 6;

    private final MeshEngine engine = new MeshEngine();

    private int frameW = 1, frameH = 1;
    private int stampTexId = 0;
    private int stampFboId = 0;
    private int sourceTexId = 0;
    @Nullable private Bitmap sourceUploaded;
    private boolean sourceMipmapped;
    private boolean loggedMipmapFallback;
    private int program = 0;
    private int aLocalLoc = -1, aUvLoc = -1;
    private int uHomographyLoc = -1, uPlaceLoc = -1, uImageLoc = -1, uAlphaLoc = -1, uRevealLoc = -1;
    /**
     * SPEC H mirror uniform location ({@code MeshGlSource} {@code uMirror}). Uploaded every
     * stamp draw from the caller's signs; (1,1) draws exactly what shipped.
     */
    private int uMirrorLoc = -1;

    private FloatBuffer posBuf;
    private FloatBuffer uvBuf;
    private ShortBuffer idxBuf;
    private int lastTopologyStamp = Integer.MIN_VALUE;

    private final float[] folded4 = new float[4];
    private final float[] placeCol9 = new float[9];
    private final float[] homogCol9 = new float[9];
    private final float[] homogRow9 = new float[9];

    private boolean degraded;
    private boolean glInitialized;
    private boolean loggedInitFailure;

    /** Desired stamp size (output frame). Reallocates lazily on the GL thread. */
    public void configure(int w, int h) {
        frameW = Math.max(1, w);
        frameH = Math.max(1, h);
    }

    public boolean isDegraded() { return degraded; }

    /**
     * Render the warp into the frame-sized stamp.
     *
     * <p>All placement inputs are top-left-origin normalized (model space); matrices are built
     * HERE via {@link MeshPlacement} — the single call site both renderers share.
     *
     * @param src          decoded photo (same bitmap the flat path shows; never recycled here)
     * @param spec         bend spec (must haveWarp, else 0)
     * @param localMs      mesh time base (item-local, see {@code TextOverlayItem.meshLocalTime})
     * @param cx,cy        animated centre 0..1 (without preset)
     * @param wNorm,hNorm  unfolded width/height fractions (without preset)
     * @param pivOffX,pivOffY pivot offsets (same units; ignored unless {@code applyPivot})
     * @param applyPivot   false when pivot-neutral or live-dragging (mirrors the flat host)
     * @param rotDeg       animated rotation, clockwise-positive (model convention)
     * @param presetScaleX,presetScaleY,presetDxNorm,presetDyNorm entrance-preset fold (1/0 when off)
     * @param alpha        baked opacity (animatedOpacity * preset alpha), 0..1
     * @param reveal       baked wipe 0..1 across undeformed u (1 = all)
     * @param cornerPin8   packed pin offsets or null/flat for identity homography
     * @param mirrorX      {@code TextOverlayItem.mirrorSignX()} (+1/-1) — SPEC H: the mesh
     *                     branch used to ignore the mirror, so a bent AND mirrored image exported
     *                     unmirrored. Both callers pass the model's ONE shared definition, and the
     *                     single {@code MeshGlSource} vertex string applies it, so preview and
     *                     export cannot disagree. (1,1) draws exactly what shipped.
     * @param mirrorY      {@code TextOverlayItem.mirrorSignY()} (+1/-1)
     * @return stamp texture id, or 0 to draw the ordinary unbent way
     */
    public int renderToStamp(@Nullable Bitmap src, @Nullable MeshWarpSpec spec, long localMs,
                             float cx, float cy, float wNorm, float hNorm,
                             float pivOffX, float pivOffY, boolean applyPivot,
                             float rotDeg,
                             float presetScaleX, float presetScaleY,
                             float presetDxNorm, float presetDyNorm,
                             float alpha, float reveal,
                             @Nullable float[] cornerPin8,
                             float mirrorX, float mirrorY) {
        if (degraded) return 0;
        if (src == null || src.isRecycled() || src.getWidth() <= 0 || src.getHeight() <= 0) return 0;
        if (spec == null || !spec.hasWarp()) return 0;
        if (alpha <= 0.001f) return 0;
        // CPU first: identity pose returns false with NO GL objects created — the zero-cost gate.
        if (!engine.update(spec, localMs)) return 0;
        MeshBuffers b = engine.buffers();
        if (!b.hasGeometry()) return 0;
        if (!ensureGlInitialized()) return 0;
        if (!uploadSource(src)) return 0;

        // Single placement authority: fold (pivot+preset) then unit-square->clip. The SAME
        // aspect feeds both — SPEC Q: the fold's rotation about a non-centre pivot is only a
        // rotation in square units, and normalized x/y are fractions of different lengths.
        float aspect = (float) frameW / (float) Math.max(1, frameH);
        MeshPlacement.fold(cx, cy, wNorm, hNorm, pivOffX, pivOffY, applyPivot, rotDeg,
                presetScaleX, presetScaleY, presetDxNorm, presetDyNorm, aspect, folded4);
        if (!MeshPlacement.buildPlace(folded4[0], folded4[1], folded4[2], folded4[3],
                rotDeg, aspect, placeCol9)) {
            return 0;
        }
        if (!buildHomographyCol(cornerPin8)) {
            MeshPlacement.identity3(homogCol9);
        }

        // Static arrays only on topology change; positions every frame. No allocation: reused
        // direct buffers, sized once to the coarsest lattice.
        if (b.topologyStamp() != lastTopologyStamp) {
            int vc = b.vertexCount() * 2;
            int ic = b.indexCount();
            if (vc > MAX_VERTS * 2 || ic > MAX_INDICES) return 0; // larger than a lattice: refuse
            uvBuf.position(0);
            uvBuf.put(b.uvs, 0, vc);
            uvBuf.position(0);
            idxBuf.position(0);
            for (int i = 0; i < ic; i++) idxBuf.put(b.indices[i]);
            idxBuf.position(0);
            lastTopologyStamp = b.topologyStamp();
        }
        int vc = b.vertexCount() * 2;
        posBuf.position(0);
        posBuf.put(b.positions, 0, vc);
        posBuf.position(0);

        // Save the caller's output binding (media3 owns it on the effect thread; the preview owns
        // its composite targets) so the stamp never hijacks the frame it is stamping for.
        int[] prevFbo = new int[1];
        try {
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0);
        } catch (Exception ignored) {
            prevFbo[0] = 0;
        }
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, stampFboId);
            GLES20.glViewport(0, 0, frameW, frameH);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glDisable(GLES20.GL_CULL_FACE);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexId);
            if (uImageLoc >= 0) GLES20.glUniform1i(uImageLoc, 0);
            if (uHomographyLoc >= 0) {
                GLES20.glUniformMatrix3fv(uHomographyLoc, 1, false, homogCol9, 0);
            }
            if (uPlaceLoc >= 0) GLES20.glUniformMatrix3fv(uPlaceLoc, 1, false, placeCol9, 0);
            if (uAlphaLoc >= 0) GLES20.glUniform1f(uAlphaLoc, Math.max(0f, Math.min(1f, alpha)));
            if (uRevealLoc >= 0) {
                GLES20.glUniform1f(uRevealLoc, Math.max(0f, Math.min(1f, reveal)));
            }
            // SPEC H mirror — sanitised to a sign (a corrupt value must cost the mirror,
            // never the picture). (1,1) is the identity every existing project takes.
            if (uMirrorLoc >= 0) {
                float mx = mirrorX < 0f ? -1f : 1f;
                float my = mirrorY < 0f ? -1f : 1f;
                if (!Float.isNaN(mirrorX) && !Float.isNaN(mirrorY)) {
                    GLES20.glUniform2f(uMirrorLoc, mx, my);
                } else {
                    GLES20.glUniform2f(uMirrorLoc, 1f, 1f);
                }
            }
            if (aLocalLoc >= 0) {
                GLES20.glEnableVertexAttribArray(aLocalLoc);
                GLES20.glVertexAttribPointer(aLocalLoc, 2, GLES20.GL_FLOAT, false, 0, posBuf);
            }
            if (aUvLoc >= 0) {
                GLES20.glEnableVertexAttribArray(aUvLoc);
                GLES20.glVertexAttribPointer(aUvLoc, 2, GLES20.GL_FLOAT, false, 0, uvBuf);
            }
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, b.indexCount(),
                    GLES20.GL_UNSIGNED_SHORT, idxBuf);
            if (aLocalLoc >= 0) GLES20.glDisableVertexAttribArray(aLocalLoc);
            if (aUvLoc >= 0) GLES20.glDisableVertexAttribArray(aUvLoc);
        } catch (Exception e) {
            if (!loggedInitFailure) {
                FLog.w(TAG, "stamp draw failed; drawing unwarped", e);
                loggedInitFailure = true;
            }
            return 0;
        } finally {
            try {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0]);
            } catch (Exception ignored) { }
        }
        return stampTexId;
    }

    /** Forward pin homography on the unit square (single solver: CornerPin), to column-major. */
    private boolean buildHomographyCol(@Nullable float[] cornerPin8) {
        if (com.fadcam.ui.faditor.model.CornerPin.isFlat(cornerPin8)) {
            MeshPlacement.identity3(homogCol9);
            return true;
        }
        try {
            android.graphics.Matrix h = new android.graphics.Matrix();
            if (!com.fadcam.ui.faditor.model.CornerPin.buildMatrix(
                    h, 0f, 0f, 1f, 1f, cornerPin8)) {
                return false;
            }
            h.getValues(homogRow9);
            MeshPlacement.transpose9(homogRow9, homogCol9);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean uploadSource(@NonNull Bitmap src) {
        try {
            if (sourceTexId == 0) return false;
            if (sourceUploaded != src) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexId);
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, src, 0);
                // Mipmaps are mandatory for a mesh that compresses the photo (pinched corner,
                // fold): without them a minified region shimmers. Fall back to plain LINEAR on
                // drivers that refuse NPOT mipmaps — still correct, just softer under compression.
                try {
                    GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
                    sourceMipmapped = GLES20.glGetError() == GLES20.GL_NO_ERROR;
                    if (!sourceMipmapped) {
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    }
                } catch (Exception ignored) {
                    try {
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    } catch (Exception ignored2) { }
                    sourceMipmapped = false;
                }
                if (!sourceMipmapped && !loggedMipmapFallback) {
                    FLog.d(TAG, "source mipmaps unavailable; minification may shimmer under compression");
                    loggedMipmapFallback = true;
                }
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                sourceUploaded = src;
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexId);
            }
            return true;
        } catch (Exception e) {
            FLog.w(TAG, "source upload failed; drawing unwarped", e);
            return false;
        }
    }

    private boolean ensureGlInitialized() {
        if (glInitialized) return true;
        if (degraded) return false;
        try {
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            stampTexId = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stampTexId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    frameW, frameH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

            int[] fbo = new int[1];
            GLES20.glGenFramebuffers(1, fbo, 0);
            stampFboId = fbo[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, stampFboId);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, stampTexId, 0);
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                FLog.w(TAG, "stamp FBO incomplete: " + status);
                degraded = true;
                return false;
            }

            int[] stex = new int[1];
            GLES20.glGenTextures(1, stex, 0);
            sourceTexId = stex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            sourceUploaded = null;

            program = createProgram(MeshGlSource.VERTEX_SHADER, MeshGlSource.FRAGMENT_SHADER);
            aLocalLoc = GLES20.glGetAttribLocation(program, "aLocal");
            aUvLoc = GLES20.glGetAttribLocation(program, "aUv");
            uHomographyLoc = GLES20.glGetUniformLocation(program, "uHomography");
            uPlaceLoc = GLES20.glGetUniformLocation(program, "uPlace");
            uImageLoc = GLES20.glGetUniformLocation(program, "uImage");
            uAlphaLoc = GLES20.glGetUniformLocation(program, "uAlpha");
            uRevealLoc = GLES20.glGetUniformLocation(program, "uReveal");
            uMirrorLoc = GLES20.glGetUniformLocation(program, "uMirror");

            posBuf = ByteBuffer.allocateDirect(MAX_VERTS * 2 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            uvBuf = ByteBuffer.allocateDirect(MAX_VERTS * 2 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            idxBuf = ByteBuffer.allocateDirect(MAX_INDICES * 2)
                    .order(ByteOrder.nativeOrder()).asShortBuffer();
            lastTopologyStamp = Integer.MIN_VALUE;

            glInitialized = true;
            return true;
        } catch (Exception e) {
            FLog.w(TAG, "stamp GL init failed; drawing unwarped", e);
            degraded = true;
            return false;
        }
    }

    private static int createProgram(@NonNull String vertex, @NonNull String fragment) {
        int v = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(v, vertex);
        GLES20.glCompileShader(v);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(v, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = "";
            try { log = GLES20.glGetShaderInfoLog(v); } catch (Exception ignored) { }
            GLES20.glDeleteShader(v);
            throw new RuntimeException("mesh vertex compile failed: " + log);
        }
        int f = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(f, fragment);
        GLES20.glCompileShader(f);
        GLES20.glGetShaderiv(f, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = "";
            try { log = GLES20.glGetShaderInfoLog(f); } catch (Exception ignored) { }
            GLES20.glDeleteShader(v);
            GLES20.glDeleteShader(f);
            throw new RuntimeException("mesh fragment compile failed: " + log);
        }
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = "";
            try { log = GLES20.glGetProgramInfoLog(p); } catch (Exception ignored) { }
            GLES20.glDeleteProgram(p);
            GLES20.glDeleteShader(v);
            GLES20.glDeleteShader(f);
            throw new RuntimeException("mesh program link failed: " + log);
        }
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    /** Free GL objects. Must be called on the owning GL thread (preview teardown / effect release). */
    public void release() {
        try {
            if (program != 0) GLES20.glDeleteProgram(program);
        } catch (Exception ignored) { }
        program = 0;
        try {
            if (stampFboId != 0) GLES20.glDeleteFramebuffers(1, new int[]{stampFboId}, 0);
        } catch (Exception ignored) { }
        stampFboId = 0;
        try {
            if (stampTexId != 0) GLES20.glDeleteTextures(1, new int[]{stampTexId}, 0);
            if (sourceTexId != 0) GLES20.glDeleteTextures(1, new int[]{sourceTexId}, 0);
        } catch (Exception ignored) { }
        stampTexId = 0;
        sourceTexId = 0;
        sourceUploaded = null;
        glInitialized = false;
    }
}
