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
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.overlay.TextOverlayLayer;

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
        private final Context context;
        private final List<TextOverlayItem> items;
        private final long projectDurationMs;
        private final long editorTimeOffsetMs;
        private final PipChainGl chain = new PipChainGl();
        /** Decoded once per item; null value = decode failed or past its window (never retried). */
        private final Map<String, Bitmap> bitmaps = new HashMap<>();
        private final Map<String, Integer> textures = new HashMap<>();
        private final List<FxPreviewTextureView.Pip> frameVisible = new ArrayList<>();
        private final List<Integer> frameTex = new ArrayList<>();

        Program(@NonNull Context context, @NonNull List<TextOverlayItem> items,
                long projectDurationMs, long editorTimeOffsetMs) {
            super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1);
            this.context = context;
            this.items = items;
            this.projectDurationMs = projectDurationMs;
            this.editorTimeOffsetMs = editorTimeOffsetMs;
        }

        @NonNull
        @Override
        public Size configure(int inputWidth, int inputHeight) throws VideoFrameProcessingException {
            try {
                chain.configure(inputWidth, inputHeight);
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
            return new Size(chain.w, chain.h);
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs)
                throws VideoFrameProcessingException {
            try {
                final int outFbo = PipChainGl.boundFbo();
                final int w = chain.w, h = chain.h;
                long t = presentationTimeUs / 1000 + editorTimeOffsetMs;
                frameVisible.clear();
                frameTex.clear();
                for (TextOverlayItem o : items) {
                    if (!o.isVisibleAt(t)) {
                        if (t > o.getEndMs()) forget(o);   // time only moves forward in an item
                        continue;
                    }
                    Bitmap b = bitmapFor(o, w, h);
                    if (b == null) continue;
                    FxPreviewTextureView.Pip p =
                            TextOverlayLayer.pipForModel(o, t, projectDurationMs, w, h, w, h, b);
                    if (p == null || !PipGl.rendersAnything(p)) continue;
                    int tex = textureFor(o, b);
                    if (tex == 0) continue;
                    frameVisible.add(p);
                    frameTex.add(tex);
                }
                chain.composite(inputTexId, outFbo, frameVisible, frameTex);
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        @Nullable
        private Bitmap bitmapFor(@NonNull TextOverlayItem o, int w, int h) {
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
            Bitmap b = bitmaps.get(id);
            if (b != null && !b.isRecycled()) b.recycle();
            bitmaps.put(id, null);   // past its window for good: never decode again
        }

        @Override
        public void release() throws VideoFrameProcessingException {
            try {
                super.release();
            } finally {
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
                chain.release();
            }
        }
    }
}
