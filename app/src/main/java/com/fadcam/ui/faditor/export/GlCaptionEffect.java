package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.compositor.FxPreviewTextureView;
import com.fadcam.ui.faditor.compositor.PipGl;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * EXPORT SPEED (2026-09-23): captions on the GPU, with their Canvas pixels unchanged.
 *
 * <p>Measured on the Note 20 once images moved to {@link GlImageOverlayEffect}: the caption path
 * was ~65% of every frame — each caption rastered into a full-frame bitmap, blitted onto the
 * overlay's full-frame canvas (Canvas blit 28%), then that whole frame uploaded (20%).</p>
 *
 * <p>Here each caption is still rendered by the SAME {@link CaptionExportRenderer} the Canvas
 * pass uses (via {@link CompositeExportOverlay#renderCaptionsAt}), but only its tight box is
 * copied and uploaded, only on frames where it changed, and it is composited at its own place
 * by the preview's Pip shader. Identical pixels, a fraction of the traffic.</p>
 */
final class GlCaptionEffect implements GlEffect {

    private final CompositeExportOverlay overlay;

    GlCaptionEffect(@NonNull CompositeExportOverlay overlay) {
        this.overlay = overlay;
    }

    @NonNull
    @Override
    public BaseGlShaderProgram toGlShaderProgram(@NonNull Context ignored, boolean useHdr)
            throws VideoFrameProcessingException {
        if (useHdr) throw new VideoFrameProcessingException("HDR captions not supported");
        return new Program(overlay);
    }

    private static final class Program extends BaseGlShaderProgram {
        /**
         * One caption's GPU copy per renderer: two textures used in turn, so a new frame's
         * upload never waits for the GPU to finish reading the last one; each is resized only
         * when the renderer's window grows.
         */
        private static final class Box {
            final int[] tex = new int[2];
            final int[] w = new int[2];
            final int[] h = new int[2];
            int cur = -1;
            final Rect bounds = new Rect();
            Bitmap shown;
            /** ZERO-COPY route: the caption is drawn into this Surface; the GPU reads it as is. */
            SurfaceLayer layer;
            boolean external;
        }

        /**
         * ZERO-COPY CAPTIONS (2026-09-24). Measured on the Note 20: a caption strip upload
         * (GLUtils.texSubImage2D, ~1080x950 px) cost 12.6 ms on average - the driver moves
         * ~80 MB/s - and a karaoke caption changes every frame. Here the caption's pixels are
         * copied by the CPU into a Surface buffer the GPU samples directly (the preview's own
         * live-PiP shader variant, which differs from the still one only in how it samples), so
         * there is no upload at all. Any failure turns it off for this pass and the upload
         * route above takes over.
         */
        private boolean zeroCopy = true;
        private final android.graphics.Paint srcPaint = new android.graphics.Paint();
        {
            srcPaint.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.SRC));
        }
        private final List<Boolean> external = new ArrayList<>();

        /** Draw {@code bmp} into the box's Surface and latch it; false = use the upload route. */
        private boolean showThroughSurface(@NonNull Box box, @NonNull Bitmap bmp) {
            if (!zeroCopy) return false;
            try {
                if (box.layer == null) {
                    box.layer = new SurfaceLayer(bmp.getWidth(), bmp.getHeight());
                } else {
                    box.layer.resize(bmp.getWidth(), bmp.getHeight());
                }
                android.graphics.Canvas c = box.layer.lock();
                c.drawBitmap(bmp, 0f, 0f, srcPaint);
                if (!box.layer.postAndLatch(c)) {
                    throw new IllegalStateException("caption frame did not arrive in time");
                }
                box.external = true;
                return true;
            } catch (Throwable t) {
                FLog.w("GlCaption", "zero-copy captions unavailable; uploading instead", t);
                zeroCopy = false;
                box.external = false;
                return false;
            }
        }

        private final CompositeExportOverlay overlay;
        private final PipChainGl chain = new PipChainGl();
        private final Map<CaptionExportRenderer, Box> boxes = new IdentityHashMap<>();
        private final List<FxPreviewTextureView.Pip> pips = new ArrayList<>();
        private final List<Integer> texes = new ArrayList<>();
        private final Rect scratch = new Rect();
        private long uploadNs = 0L;
        private int uploads = 0;
        private long uploadPixels = 0L;

        /** CAPTION_UPLOAD timing in logcat every 300 uploads: what a strip upload costs here. */
        private void noteUpload(long ns, int w, int h) {
            uploadNs += ns;
            uploads++;
            uploadPixels += (long) w * h;
            if (uploads >= 300) {
                FLog.i("GlCaption", "CAPTION_UPLOAD avg=" + (uploadNs / uploads / 1000) + "us px="
                        + (uploadPixels / uploads) + " last=" + w + "x" + h);
                uploadNs = 0L;
                uploads = 0;
                uploadPixels = 0L;
            }
        }

        Program(@NonNull CompositeExportOverlay overlay) {
            super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 2);
            this.overlay = overlay;
        }

        /**
         * Every frame's last pass draws the whole output (blending off, full-frame quad), so
         * Media3's clear before it is pure cost: GLUtil.clearFocusedBuffers was 14-27% of the
         * Note 20's GL thread - a stall on a target the GPU was still reading, which the
         * two-texture pool (above) also removes.
         */
        @Override
        public boolean shouldClearTextureBuffer() {
            return false;
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
            GlErrors.drain("pending when GlCaptionEffect began (left by an earlier step)");
            try {
                final int outFbo = PipChainGl.boundFbo();
                pips.clear();
                texes.clear();
                external.clear();
                List<CaptionExportRenderer> rs;
                try {
                    rs = overlay.renderCaptionsAt(presentationTimeUs);
                } catch (Throwable t) {
                    FLog.w("GlCaption", "caption render threw; frame drawn without captions", t);
                    rs = java.util.Collections.emptyList();
                }
                long editorMs = presentationTimeUs / 1000;
                int idx = 0;
                for (CaptionExportRenderer r : rs) {
                    Box box = boxes.get(r);
                    if (box == null || r.lastRenderChanged()) {
                        if (box == null) {
                            box = new Box();
                            box.tex[0] = PipGl.newStillTexture();
                            box.tex[1] = PipGl.newStillTexture();
                            boxes.put(r, box);
                        }
                        Bitmap bmp = r.lastBitmap();
                        Rect win = r.window();
                        long t0 = System.nanoTime();
                        if (!showThroughSurface(box, bmp)) {
                            int next = box.cur < 0 ? 0 : 1 - box.cur;
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, box.tex[next]);
                            if (box.w[next] == bmp.getWidth() && box.h[next] == bmp.getHeight()) {
                                android.opengl.GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0,
                                        0, bmp);
                            } else {
                                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
                                box.w[next] = bmp.getWidth();
                                box.h[next] = bmp.getHeight();
                            }
                            box.cur = next;
                        }
                        box.shown = bmp;
                        noteUpload(System.nanoTime() - t0, bmp.getWidth(), bmp.getHeight());
                        // The picture covers the renderer's window (the whole frame if the
                        // renderer is not in window mode).
                        box.bounds.set(win.left, win.top, win.left + bmp.getWidth(),
                                win.top + bmp.getHeight());
                    }
                    float bw = r.getWidth(), bh = r.getHeight();
                    Rect b = box.bounds;
                    float cx = (b.left + b.width() / 2f) / bw;
                    float cy = (b.top + b.height() / 2f) / bh;
                    float halfW = b.width() / (2f * bw);
                    float halfH = b.height() / (2f * bh);
                    // Same framing as an image Pip: y and rotation into GL's bottom-up uv.
                    FxPreviewTextureView.Pip p = box.external
                            ? FxPreviewTextureView.Pip.of(cx, 1f - cy, halfW, halfH, 0f, 1f,
                                    null, editorMs, null, 0f, chain.w, chain.h,
                                    "cap#" + (idx++), null, 0)
                            : FxPreviewTextureView.Pip.ofImage(
                                    cx, 1f - cy, halfW, halfH, 0f, 1f, null, editorMs, null, 0f,
                                    chain.w, chain.h, "cap#" + (idx++), box.shown, 1f);
                    pips.add(p);
                    texes.add(box.external ? box.layer.oesTex : box.tex[box.cur]);
                    external.add(box.external);
                }
                chain.composite(inputTexId, outFbo, pips, texes, external);
                GlErrors.drain("left by GlCaptionEffect");
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        @Override
        public void release() throws VideoFrameProcessingException {
            try {
                super.release();
            } finally {
                for (Box box : boxes.values()) {
                    try { GLES20.glDeleteTextures(2, box.tex, 0); }
                    catch (RuntimeException ignored) { }
                    if (box.layer != null) box.layer.release();
                }
                boxes.clear();
                chain.release();
            }
        }
    }
}
