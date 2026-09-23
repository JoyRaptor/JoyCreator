package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.compositor.FxPreviewTextureView;
import com.fadcam.ui.faditor.compositor.PipGl;
import com.fadcam.ui.faditor.fx.FxGlSource;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.overlay.TextOverlayLayer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EXPORT PARITY + SPEED (2026-09-23): a run of image overlays composited on the GPU with the
 * PREVIEW's own code — placement from {@link TextOverlayLayer#pipForModel}, shader and every
 * uniform from {@link PipGl} (the statement {@code FxPreviewTextureView.drawPip} also uses).
 *
 * <p>Replaces, for these items, the CPU path that measured as most of the export's frame time
 * on the Note 20 (GL_SAMPLE: Canvas blits 21-23%, CPU->GPU uploads 17%, full-frame clears 8%):
 * each picture is decoded and uploaded ONCE, then placed per frame by uniforms; an image that
 * is not on screen costs nothing. Masks, blend, key, FX, corner pin, mirror, pivot and the
 * reveal wipe are the preview's, so the file matches the editor by construction.</p>
 *
 * <p>Items arrive bottom-to-top; each visible one is one full-frame pass over the frame so far
 * (ping-pong), the last writing straight into Media3's output. Bent (meshed) images do not come
 * here — they keep {@link ImageBlendGlEffect}'s mesh stamp.</p>
 */
final class GlImageOverlayEffect implements GlEffect {

    private final Context context;
    private final List<TextOverlayItem> items;
    private final long projectDurationMs;
    private final long editorTimeOffsetMs;

    GlImageOverlayEffect(@NonNull Context context, @NonNull List<TextOverlayItem> itemsBottomTop,
                         long projectDurationMs, long editorTimeOffsetMs) {
        this.context = context.getApplicationContext();
        this.items = new ArrayList<>(itemsBottomTop);
        this.projectDurationMs = projectDurationMs;
        this.editorTimeOffsetMs = editorTimeOffsetMs;
    }

    @NonNull
    @Override
    public BaseGlShaderProgram toGlShaderProgram(@NonNull Context ignored, boolean useHdr)
            throws VideoFrameProcessingException {
        if (useHdr) throw new VideoFrameProcessingException("HDR image overlays not supported");
        return new Program(context, items, projectDurationMs, editorTimeOffsetMs);
    }

    private static final class Program extends BaseGlShaderProgram {
        private static final float[] QUAD = {
                -1f, -1f, 0f, 1f,
                 1f, -1f, 0f, 1f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 0f, 1f,
        };

        private final Context context;
        private final List<TextOverlayItem> items;
        private final long projectDurationMs;
        private final long editorTimeOffsetMs;
        private final PipGl.Programs programs = new PipGl.Programs();
        private final FloatBuffer quad;
        /** Decoded once per item; null value = decode failed (never retried). */
        private final Map<String, Bitmap> bitmaps = new HashMap<>();
        private final Map<String, Integer> textures = new HashMap<>();
        private final List<FxPreviewTextureView.Pip> frameVisible = new ArrayList<>();
        private final List<Integer> frameTex = new ArrayList<>();
        private int w = 1, h = 1;
        private final int[] pingTex = new int[2];
        private final int[] pingFbo = new int[2];
        private int copyProgram = 0;

        Program(@NonNull Context context, @NonNull List<TextOverlayItem> items,
                long projectDurationMs, long editorTimeOffsetMs) {
            super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1);
            this.context = context;
            this.items = items;
            this.projectDurationMs = projectDurationMs;
            this.editorTimeOffsetMs = editorTimeOffsetMs;
            quad = ByteBuffer.allocateDirect(QUAD.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(QUAD).position(0);
        }

        @NonNull
        @Override
        public Size configure(int inputWidth, int inputHeight) throws VideoFrameProcessingException {
            int nw = Math.max(1, inputWidth), nh = Math.max(1, inputHeight);
            if (nw != w || nh != h || pingFbo[0] == 0) {
                releasePing();
                w = nw;
                h = nh;
                try {
                    for (int i = 0; i < 2; i++) {
                        pingTex[i] = GlUtil.createTexture(w, h, false);
                        pingFbo[i] = GlUtil.createFboForTexture(pingTex[i]);
                    }
                } catch (GlUtil.GlException e) {
                    throw new VideoFrameProcessingException(e);
                }
            }
            return new Size(w, h);
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs)
                throws VideoFrameProcessingException {
            try {
                int[] fb = new int[1];
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fb, 0);
                final int outFbo = fb[0];
                long t = presentationTimeUs / 1000 + editorTimeOffsetMs;
                frameVisible.clear();
                frameTex.clear();
                for (TextOverlayItem o : items) {
                    if (!o.isVisibleAt(t)) {
                        if (t > o.getEndMs()) forget(o);   // time only moves forward in an item
                        continue;
                    }
                    Bitmap b = bitmapFor(o);
                    if (b == null) continue;
                    FxPreviewTextureView.Pip p =
                            TextOverlayLayer.pipForModel(o, t, projectDurationMs, w, h, w, h, b);
                    if (p == null || !PipGl.rendersAnything(p)) continue;
                    int tex = textureFor(o, b);
                    if (tex == 0) continue;
                    frameVisible.add(p);
                    frameTex.add(tex);
                }
                GLES20.glDisable(GLES20.GL_BLEND);
                int n = frameVisible.size();
                if (n == 0) {
                    copy(inputTexId, outFbo);
                } else {
                    int src = inputTexId;
                    for (int i = 0; i < n; i++) {
                        boolean last = i == n - 1;
                        int dstFbo = last ? outFbo : pingFbo[i % 2];
                        if (!PipGl.drawStill(frameVisible.get(i), programs, src,
                                frameTex.get(i), dstFbo, w, h, quad)) {
                            copy(src, dstFbo);   // a refused program drops the item, not the frame
                        }
                        src = pingTex[i % 2];
                    }
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFbo);
                GlUtil.checkGlError();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        @Nullable
        private Bitmap bitmapFor(@NonNull TextOverlayItem o) {
            String id = o.getId();
            if (bitmaps.containsKey(id)) return bitmaps.get(id);
            Bitmap b = null;
            try {
                b = ImageOverlayDraw.decode(context, o, w, h);
            } catch (Throwable t) {
                FLog.w("GlImageOverlay", "decode failed for " + id, t);
            }
            if (b != null && (b.isRecycled() || b.getHeight() <= 0)) b = null;
            bitmaps.put(id, b);
            return b;
        }

        private int textureFor(@NonNull TextOverlayItem o, @NonNull Bitmap b) {
            Integer have = textures.get(o.getId());
            if (have != null) return have;
            int tex = PipGl.newStillTexture();
            try {
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
            } catch (RuntimeException e) {
                FLog.w("GlImageOverlay", "upload failed for " + o.getId(), e);
                GLES20.glDeleteTextures(1, new int[]{tex}, 0);
                tex = 0;
            }
            textures.put(o.getId(), tex);
            return tex;
        }

        /** An item past its window: free its texture and picture now, not at item end. */
        private void forget(@NonNull TextOverlayItem o) {
            String id = o.getId();
            Integer tex = textures.remove(id);
            if (tex != null && tex != 0) GLES20.glDeleteTextures(1, new int[]{tex}, 0);
            if (bitmaps.containsKey(id)) {
                Bitmap b = bitmaps.remove(id);
                if (b != null && !b.isRecycled()) b.recycle();
                bitmaps.put(id, null);   // past its window for good: never decode again
            }
        }

        private void copy(int srcTex, int dstFbo) {
            if (copyProgram == 0) {
                copyProgram = PipGl.buildProgram(FxGlSource.VERTEX_SHADER,
                        FxGlSource.PASSTHROUGH_FRAGMENT);
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo);
            GLES20.glViewport(0, 0, w, h);
            GLES20.glUseProgram(copyProgram);
            int aPos = GLES20.glGetAttribLocation(copyProgram, "aFramePosition");
            if (aPos >= 0) {
                quad.position(0);
                GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, 0, quad);
                GLES20.glEnableVertexAttribArray(aPos);
            }
            int loc = GLES20.glGetUniformLocation(copyProgram, "uTexSampler");
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex);
            if (loc >= 0) GLES20.glUniform1i(loc, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        private void releasePing() {
            for (int i = 0; i < 2; i++) {
                try {
                    if (pingFbo[i] != 0) GlUtil.deleteFbo(pingFbo[i]);
                    if (pingTex[i] != 0) GlUtil.deleteTexture(pingTex[i]);
                } catch (Exception ignored) { }
                pingFbo[i] = 0;
                pingTex[i] = 0;
            }
        }

        @Override
        public void release() throws VideoFrameProcessingException {
            try {
                super.release();
            } finally {
                releasePing();
                for (Integer tex : textures.values()) {
                    if (tex != null && tex != 0) {
                        try { GLES20.glDeleteTextures(1, new int[]{tex}, 0); }
                        catch (RuntimeException ignored) { }
                    }
                }
                textures.clear();
                for (Bitmap b : bitmaps.values()) {
                    if (b != null && !b.isRecycled()) b.recycle();
                }
                bitmaps.clear();
                programs.release();
                if (copyProgram != 0) {
                    try { GLES20.glDeleteProgram(copyProgram); } catch (RuntimeException ignored) { }
                    copyProgram = 0;
                }
            }
        }
    }
}
