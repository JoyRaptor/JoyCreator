package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.compositor.OverlayVideoPreviewView;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.CompositingSpec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GL counterpart to {@link PipFrameOverlay}: decodes PiP frames to a SurfaceTexture's
 * OES texture and composites them as a 2D texture, so pixels never round-trip through
 * the CPU. Used only for unmasked PiPs (option 2, §4) — masked ones stay on the Canvas
 * path where {@code MaskPathBuilder} clips.
 *
 * <p>Position/scale/rotation/opacity happen here on the GPU (FBO blit), so
 * {@link BlendModeGlEffect} can keep its full-frame UV blending unchanged. The maths
 * mirrors {@code PipFrameOverlay.getBitmap} (absolute-timeline KeyframeSet sampling,
 * DEFAULT_* fallbacks, full-fit scale reference) — keep the two in lockstep.
 */
final class GlPipFrameOverlay {

    private static final String TAG = "GlPipOverlay";

    private final Context context;
    private final Clip clip;
    private final long editorTimeOffsetMs;

    private final SurfaceFrameReader surfaceReader;

    private int frameW = 1, frameH = 1;
    private int overlayTexId = 0;
    private int fboId = 0;
    private int blitProgram = 0;
    private int aPositionLoc = -1, uMvpLoc = -1, uTexMatrixLoc = -1, uOesTexLoc = -1, uAlphaLoc = -1;
    private FloatBuffer quadBuffer;

    private boolean degraded = false;
    private boolean glInitialized = false;
    private int transparentTexId = 0;

    GlPipFrameOverlay(@NonNull Context context, @NonNull Clip clip, long editorTimeOffsetMs) {
        this.context = context.getApplicationContext();
        this.clip = clip;
        this.editorTimeOffsetMs = editorTimeOffsetMs;
        this.surfaceReader = new SurfaceFrameReader(this.context, clip.getSourceUri());
    }

    void configure(@NonNull Size videoSize) {
        frameW = Math.max(1, videoSize.getWidth());
        frameH = Math.max(1, videoSize.getHeight());
        // GL resources are created lazily on the effect thread (drawFrame) where EGL is current.
    }

    boolean activeAt(long presentationTimeUs) {
        long timelineMs = presentationTimeUs / 1000 + editorTimeOffsetMs;
        long start = clip.getOverlayStartMs();
        long end = start + Math.max(0, clip.getTrimmedDurationMs());
        return timelineMs >= start && timelineMs <= end;
    }

    boolean isDegraded() { return degraded || surfaceReader.isDegraded(); }

    /**
     * Return the full-frame overlay texture for this presentation time, with the PiP
     * already positioned. Must be called on the GL thread (Transformer effect thread).
     * Falls back to 0 (let caller use CPU path) if GL init or decode fails.
     */
    int getTextureId(long presentationTimeUs) {
        if (degraded) return 0;
        if (!ensureGlInitialized()) return 0;

        long timelineMs = presentationTimeUs / 1000 + editorTimeOffsetMs;
        long start = clip.getOverlayStartMs();
        long end = start + Math.max(0, clip.getTrimmedDurationMs());
        if (timelineMs < start || timelineMs > end) {
            return getTransparentTexture();
        }

        KeyframeSet kf = clip.getOverlayTransform();
        float opacity = kf == null ? 1f : Math.max(0f, Math.min(1f, kf.valueAt(KeyframeSet.OPACITY, timelineMs, 1f)));
        if (opacity <= 0.001f) return getTransparentTexture();

        long sourceMs = clip.getInPointMs() + (timelineMs - start);
        // Surface path — update decoder to this source time
        boolean ok = surfaceReader.updateTo(sourceMs * 1000L);
        if (!ok) {
            degraded = true;
            FLog.w(TAG, "Surface reader failed for " + clip.getSourceUri() + "; degrading to CPU path");
            return 0;
        }

        // Composite OES into full-frame FBO at the clip's position
        return blitToOverlay(presentationTimeUs, timelineMs, opacity);
    }

    private boolean ensureGlInitialized() {
        if (glInitialized) return true;
        try {
            // Overlay 2D texture + FBO (full-frame)
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            overlayTexId = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frameW, frameH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

            int[] fbo = new int[1];
            GLES20.glGenFramebuffers(1, fbo, 0);
            fboId = fbo[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, overlayTexId, 0);
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                FLog.w(TAG, "FBO incomplete: " + status);
                return false;
            }

            // Transparent fallback 1x1
            int[] ttex = new int[1];
            GLES20.glGenTextures(1, ttex, 0);
            transparentTexId = ttex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, transparentTexId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            ByteBuffer zero = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
            zero.putInt(0); zero.position(0);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, zero);

            // Blit program: OES -> positioned quad
            String vertex = "attribute vec2 aPosition;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "uniform mat4 uMvp;\n"
                    + "void main() {\n"
                    + "  gl_Position = uMvp * vec4(aPosition, 0.0, 1.0);\n"
                    + "  vTexCoord = aPosition * 0.5 + 0.5;\n"
                    + "}\n";
            String fragment = "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "uniform samplerExternalOES uOesTex;\n"
                    + "uniform mat4 uTexMatrix;\n"
                    + "uniform float uAlpha;\n"
                    + "void main() {\n"
                    + "  vec2 uv = (uTexMatrix * vec4(vTexCoord, 0.0, 1.0)).xy;\n"
                    + "  vec4 c = texture2D(uOesTex, uv);\n"
                    + "  gl_FragColor = vec4(c.rgb, c.a * uAlpha);\n"
                    + "}\n";
            blitProgram = GlUtil.createProgram(vertex, fragment);
            aPositionLoc = GLES20.glGetAttribLocation(blitProgram, "aPosition");
            uMvpLoc = GLES20.glGetUniformLocation(blitProgram, "uMvp");
            uTexMatrixLoc = GLES20.glGetUniformLocation(blitProgram, "uTexMatrix");
            uOesTexLoc = GLES20.glGetUniformLocation(blitProgram, "uOesTex");
            uAlphaLoc = GLES20.glGetUniformLocation(blitProgram, "uAlpha");

            quadBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quadBuffer.put(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f});
            quadBuffer.position(0);

            glInitialized = true;
            return true;
        } catch (Exception e) {
            FLog.w(TAG, "GL init failed; degrading", e);
            degraded = true;
            return false;
        }
    }

    private int getTransparentTexture() {
        if (transparentTexId != 0) return transparentTexId;
        // Fallback: overlayTexId is cleared to transparent when not active
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
        GLES20.glViewport(0, 0, frameW, frameH);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return overlayTexId;
    }

    private int blitToOverlay(long presentationTimeUs, long timelineMs, float opacity) {
        KeyframeSet kf = clip.getOverlayTransform();
        float ddx = OverlayVideoPreviewView.DEFAULT_X;
        float ddy = OverlayVideoPreviewView.DEFAULT_Y;
        float dds = OverlayVideoPreviewView.DEFAULT_SCALE;
        float x = kf == null ? ddx : kf.valueAt(KeyframeSet.X, timelineMs, ddx);
        float y = kf == null ? ddy : kf.valueAt(KeyframeSet.Y, timelineMs, ddy);
        float scale = kf == null ? dds : kf.valueAt(KeyframeSet.SCALE, timelineMs, dds);
        float rot = kf == null ? 0f : kf.valueAt(KeyframeSet.ROTATION, timelineMs, 0f);

        // Full-fit scale reference (same as PipFrameOverlay)
        // We don't know decoded frame size here without querying; assume frameW/H for now
        // and let the OES texture's transform handle crop. Full-fit will be refined when
        // SurfaceFrameReader exposes source dimensions (TODO).
        float w = frameW * Math.max(0.01f, scale) * 0.25f; // placeholder fraction; real fit uses frame size
        float h = frameH * Math.max(0.01f, scale) * 0.25f;
        // For correctness we compute w/h from fit: use 0.3 as placeholder for 30% of frame
        // The exact fit will be measured and tightened once we expose sourceW/H.
        w = frameW * 0.3f * scale;
        h = frameH * 0.3f * scale;
        float cx = x * frameW;
        float cy = y * frameH;

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
        GLES20.glViewport(0, 0, frameW, frameH);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(blitProgram);
        float[] mvp = new float[16];
        Matrix.setIdentityM(mvp, 0);
        Matrix.translateM(mvp, 0, (cx * 2f / frameW) - 1f, 1f - (cy * 2f / frameH), 0f);
        Matrix.scaleM(mvp, 0, w / frameW, h / frameH, 1f);
        Matrix.rotateM(mvp, 0, rot, 0f, 0f, 1f);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, surfaceReader.getOesTextureId());
        GLES20.glUniform1i(uOesTexLoc, 0);
        float[] texMat = new float[16];
        surfaceReader.getTransformMatrix(texMat);
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMat, 0);
        GLES20.glUniform1f(uAlphaLoc, opacity);
        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvp, 0);

        GLES20.glEnableVertexAttribArray(aPositionLoc);
        quadBuffer.position(0);
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, quadBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPositionLoc);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GlUtil.checkGlError();
        return overlayTexId;
    }

    void release() {
        surfaceReader.release();
        if (blitProgram != 0) { try { GLES20.glDeleteProgram(blitProgram); } catch (Exception ignored) {} blitProgram = 0; }
        if (fboId != 0) { int[] fbo = {fboId}; try { GLES20.glDeleteFramebuffers(1, fbo, 0); } catch (Exception ignored) {} fboId = 0; }
        if (overlayTexId != 0) { int[] tex = {overlayTexId}; try { GLES20.glDeleteTextures(1, tex, 0); } catch (Exception ignored) {} overlayTexId = 0; }
        if (transparentTexId != 0) { int[] tex = {transparentTexId}; try { GLES20.glDeleteTextures(1, tex, 0); } catch (Exception ignored) {} transparentTexId = 0; }
    }
}
