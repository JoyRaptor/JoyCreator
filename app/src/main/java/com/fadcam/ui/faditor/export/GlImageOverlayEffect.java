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
 *
 * <p>TEXT BOXES ride the same run (2026-09-24). Each is drawn by the Canvas pass's own code
 * ({@link CompositeExportOverlay#drawTextItem}) into a frame-sized picture, uploaded, and laid
 * over the frame unscaled, so its pixels are the Canvas pass's pixels. It is redrawn only when
 * {@link CompositeExportOverlay#textSignature} changes, so a box that stands still costs one
 * draw and one upload for its whole life instead of a full-frame clear, draw and upload on
 * every frame; and images and text in one lane order need ONE effect, not an alternation of
 * GL and Canvas passes (37 effects per clip on the 48-minute lecture).</p>
 */
final class GlImageOverlayEffect implements GlEffect {

    private final Context context;
    private final List<TextOverlayItem> items;
    private final long projectDurationMs;
    private final long editorTimeOffsetMs;
    @Nullable
    private final CompositeExportOverlay textDrawer;

    GlImageOverlayEffect(@NonNull Context context, @NonNull List<TextOverlayItem> itemsBottomTop,
                         long projectDurationMs, long editorTimeOffsetMs) {
        this(context, itemsBottomTop, projectDurationMs, editorTimeOffsetMs, null);
    }

    /** @param textDrawer draws the text boxes among the items; null = the run is images only */
    GlImageOverlayEffect(@NonNull Context context, @NonNull List<TextOverlayItem> itemsBottomTop,
                         long projectDurationMs, long editorTimeOffsetMs,
                         @Nullable CompositeExportOverlay textDrawer) {
        this.context = context.getApplicationContext();
        this.items = new ArrayList<>(itemsBottomTop);
        this.projectDurationMs = projectDurationMs;
        this.editorTimeOffsetMs = editorTimeOffsetMs;
        this.textDrawer = textDrawer;
    }

    @NonNull
    @Override
    public BaseGlShaderProgram toGlShaderProgram(@NonNull Context ignored, boolean useHdr)
            throws VideoFrameProcessingException {
        if (useHdr) throw new VideoFrameProcessingException("HDR image overlays not supported");
        return new Program(context, items, projectDurationMs, editorTimeOffsetMs, textDrawer);
    }

    private static final class Program extends BaseGlShaderProgram {
        /** One text box's current picture, its texture, and what it was drawn from. */
        private static final class TextFrame {
            Bitmap bmp;
            android.graphics.Canvas canvas;
            int tex;
            boolean uploaded;
            boolean drawn;
            String sig;
        }

        private final Context context;
        @Nullable
        private final CompositeExportOverlay textDrawer;
        private final Map<String, TextFrame> texts = new HashMap<>();
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
                long projectDurationMs, long editorTimeOffsetMs,
                @Nullable CompositeExportOverlay textDrawer) {
            super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1);
            this.context = context;
            this.textDrawer = textDrawer;
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
            GlErrors.drain("pending when GlImageOverlayEffect began (left by an earlier step)");
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
                    if (!o.isImage()) {
                        TextFrame tf = textFor(o, t);
                        if (tf == null) continue;
                        // The picture is the whole frame, laid over it unscaled.
                        frameVisible.add(FxPreviewTextureView.Pip.ofImage(0.5f, 0.5f, 0.5f, 0.5f,
                                0f, 1f, null, t, null, 0f, w, h, "txt#" + o.getId(), tf.bmp, 1f));
                        frameTex.add(tf.tex);
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
                GlErrors.drain("left by GlImageOverlayEffect");
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        /** The text box's picture at {@code t}, redrawn only when its signature moved. */
        @Nullable
        private TextFrame textFor(@NonNull TextOverlayItem o, long t) {
            if (textDrawer == null) return null;
            String sig;
            try {
                sig = CompositeExportOverlay.textSignature(o, t, projectDurationMs);
            } catch (Throwable e) {
                FLog.w("GlImageOverlay", "text signature failed for " + o.getId(), e);
                return null;
            }
            TextFrame tf = texts.get(o.getId());
            if (tf != null && sig.equals(tf.sig)) return tf.drawn ? tf : null;
            if (tf == null) {
                tf = new TextFrame();
                tf.bmp = Bitmap.createBitmap(Math.max(1, textDrawer.drawWidth()),
                        Math.max(1, textDrawer.drawHeight()), Bitmap.Config.ARGB_8888);
                tf.canvas = new android.graphics.Canvas(tf.bmp);
                tf.tex = PipGl.newStillTexture();
                texts.put(o.getId(), tf);
            } else {
                tf.bmp.eraseColor(0);
            }
            tf.sig = sig;
            try {
                tf.drawn = textDrawer.drawTextItem(tf.canvas, o, t);
            } catch (Throwable e) {
                FLog.w("GlImageOverlay", "text draw threw for " + o.getId(), e);
                tf.drawn = false;
            }
            if (!tf.drawn) return null;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tf.tex);
            if (tf.uploaded) {
                android.opengl.GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, tf.bmp);
            } else {
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, tf.bmp, 0);
                tf.uploaded = true;
            }
            return tf;
        }

        private void releaseText(@Nullable TextFrame tf) {
            if (tf == null) return;
            if (tf.tex != 0) {
                try { GLES20.glDeleteTextures(1, new int[]{tf.tex}, 0); }
                catch (RuntimeException ignored) { }
            }
            if (tf.bmp != null && !tf.bmp.isRecycled()) tf.bmp.recycle();
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
            if (!o.isImage()) {
                releaseText(texts.remove(id));
                return;
            }
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
                for (TextFrame tf : texts.values()) releaseText(tf);
                texts.clear();
                chain.release();
            }
        }
    }
}
