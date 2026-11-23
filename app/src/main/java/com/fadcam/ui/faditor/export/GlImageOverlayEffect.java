package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.compositor.RigidTextureShutterPass;
import com.fadcam.ui.faditor.compositor.FxPreviewTextureView;
import com.fadcam.ui.faditor.compositor.PipGl;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.overlay.TextBoxRenderer;
import com.fadcam.ui.faditor.overlay.TextOverlayLayer;
import com.fadcam.ui.faditor.motion.ItemMotionSampler;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionFrameCadence;
import com.fadcam.ui.faditor.motion.MotionItemAdapters;
import com.fadcam.ui.faditor.transcript.CaptionAnimator;

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
    private final long motionStartUs;
    private final long motionEndUs;
    @Nullable
    private final CompositeExportOverlay textDrawer;

    GlImageOverlayEffect(@NonNull Context context, @NonNull List<TextOverlayItem> itemsBottomTop,
                         long projectDurationMs, long editorTimeOffsetMs) {
        this(context, itemsBottomTop, projectDurationMs, editorTimeOffsetMs, null, 0L, 0L);
    }

    /** @param textDrawer draws the text boxes among the items; null = the run is images only */
    GlImageOverlayEffect(@NonNull Context context, @NonNull List<TextOverlayItem> itemsBottomTop,
                         long projectDurationMs, long editorTimeOffsetMs,
                         @Nullable CompositeExportOverlay textDrawer,
                         long pieceStartMs, long motionPieceDurationMs) {
        this.context = context.getApplicationContext();
        this.items = new ArrayList<>(itemsBottomTop);
        this.projectDurationMs = projectDurationMs;
        this.editorTimeOffsetMs = editorTimeOffsetMs;
        this.textDrawer = textDrawer;
        motionStartUs = (pieceStartMs + editorTimeOffsetMs) * 1000L;
        motionEndUs = motionStartUs + motionPieceDurationMs * 1000L;
    }

    @NonNull
    @Override
    public BaseGlShaderProgram toGlShaderProgram(@NonNull Context ignored, boolean useHdr)
            throws VideoFrameProcessingException {
        if (useHdr) throw new VideoFrameProcessingException("HDR image overlays not supported");
        return new Program(context, items, projectDurationMs, editorTimeOffsetMs, textDrawer,
                motionStartUs, motionEndUs);
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
            /** ZERO-COPY route (a box whose look moves every frame): drawn into this Surface. */
            SurfaceLayer layer;
            boolean external;
        }

        /**
         * A box that animates every frame (preset, timer, bend, pin) redrew a FULL-frame bitmap
         * and uploaded all 8 MB of it each frame (measured: 21-32% of the Note 20's GL thread
         * while one is on screen). Those are drawn straight into a Surface the GPU samples -
         * no bitmap, no copy, no upload - with the same drawTextItem on a software canvas.
         * Off after any failure (bitmap + upload route, as before).
         */
        private boolean zeroCopyText = true;
        private final List<Boolean> frameExternal = new ArrayList<>();
        private final List<PipChainGl.PictureBlur> frameBlurs = new ArrayList<>();
        private final Map<String, PictureMotion> pictureMotion = new HashMap<>();
        private final Map<String, UprightTitle> uprightTitles = new HashMap<>();
        private final long motionStartUs;
        private final long motionEndUs;
        @Nullable private final MotionFrameCadence cadence;

        private record PictureMotion(ItemMotionSampler sampler, MotionBreaks breaks) {}

        private static final class UprightTitle {
            private static final int MAX_EDGE = 2048;
            final String text;
            final float fontPx;
            final float left;
            final float top;
            final int width;
            final int height;
            final ItemMotionSampler sampler;
            final MotionBreaks breaks;
            int texture;
            boolean uploadFailed;

            UprightTitle(String text, float fontPx, float[] size, TextOverlayItem item,
                         int layoutW, int layoutH, int frameW, int frameH, MotionBreaks breaks) {
                this.text = text;
                this.fontPx = fontPx;
                // The shared layout already includes ink padding. Leave a further font-height
                // margin for italic overhangs and accents before rotating the tile.
                float pad = Math.max(4f, fontPx);
                width = (int) Math.ceil(size[0] + 2f * pad);
                height = (int) Math.ceil(size[1] + 2f * pad);
                left = (width - size[0]) * 0.5f;
                top = (height - size[1]) * 0.5f;
                sampler = MotionItemAdapters.title(item, layoutW, layoutH, frameW, frameH, width, height);
                this.breaks = breaks;
            }

            boolean fits() {
                return width > 0 && height > 0 && width <= MAX_EDGE && height <= MAX_EDGE;
            }

            void release() {
                if (texture != 0) GLES20.glDeleteTextures(1, new int[]{texture}, 0);
            }
        }

        /** Draw o into tf's Surface and latch it; false = use the bitmap route. */
        private boolean drawThroughSurface(@NonNull TextFrame tf, @NonNull TextOverlayItem o,
                                           long t) {
            if (!zeroCopyText || textDrawer == null) return false;
            try {
                if (tf.layer == null) {
                    tf.layer = new SurfaceLayer(textDrawer.drawWidth(), textDrawer.drawHeight());
                }
                android.graphics.Canvas c = tf.layer.lock();
                c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
                tf.drawn = textDrawer.drawTextItem(c, o, t);
                if (!tf.layer.postAndLatch(c)) {
                    throw new IllegalStateException("text frame did not arrive in time");
                }
                tf.external = true;
                return true;
            } catch (cc.joycreator.textlab.StudioTextLab.LookFailed e) {
                throw e;   // XROOM-01: the title's look did not arrive; the export stops rather than drop it
            } catch (Throwable e) {
                FLog.w("GlImageOverlay", "zero-copy text unavailable; uploading instead", e);
                zeroCopyText = false;
                tf.external = false;
                return false;
            }
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
                @Nullable CompositeExportOverlay textDrawer, long motionStartUs, long motionEndUs) {
            super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 2);
            this.context = context;
            this.textDrawer = textDrawer;
            this.items = items;
            this.projectDurationMs = projectDurationMs;
            this.editorTimeOffsetMs = editorTimeOffsetMs;
            this.motionStartUs = motionStartUs;
            this.motionEndUs = motionEndUs;
            cadence = motionEndUs > motionStartUs
                    && items.stream().anyMatch(TextOverlayItem::isRigidMotionBlurEnabled)
                    ? new MotionFrameCadence() : null;
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
                if (cadence != null) cadence.reset();
                if (inputWidth != chain.w || inputHeight != chain.h) {
                    pictureMotion.clear();
                    for (var title : uprightTitles.values()) title.release();
                    uprightTitles.clear();
                }
                chain.configure(inputWidth, inputHeight);
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
            return new Size(chain.w, chain.h);
        }

        @Override
        public void flush() {
            if (cadence != null) cadence.reset();
            super.flush();
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
                frameExternal.clear();
                frameBlurs.clear();
                long editorUs = presentationTimeUs + editorTimeOffsetMs * 1000L;
                long periodUs = cadence != null ? cadence.observe(editorUs) : 0L;
                for (TextOverlayItem o : items) {
                    if (!o.isVisibleAt(t)) {
                        if (t > o.getEndMs()) forget(o);   // time only moves forward in an item
                        continue;
                    }
                    if (!o.isImage()) {
                        if (drawTitleMotion(o, t, editorUs, periodUs, w, h)) continue;
                        TextFrame tf = textFor(o, t);
                        if (tf == null) continue;
                        // The picture is the whole frame, laid over it unscaled.
                        if (tf.external) {
                            frameVisible.add(FxPreviewTextureView.Pip.of(0.5f, 0.5f, 0.5f, 0.5f,
                                    0f, 1f, null, t, null, 0f, w, h, "txt#" + o.getId(), null, 0));
                            frameTex.add(tf.layer.oesTex);
                            frameExternal.add(true);
                            frameBlurs.add(null);
                            continue;
                        }
                        frameVisible.add(FxPreviewTextureView.Pip.ofImage(0.5f, 0.5f, 0.5f, 0.5f,
                                0f, 1f, null, t, null, 0f, w, h, "txt#" + o.getId(), tf.bmp, 1f));
                        frameTex.add(tf.tex);
                        frameExternal.add(false);
                        frameBlurs.add(null);
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
                    frameExternal.add(false);
                    frameBlurs.add(pictureBlur(o, b, t, editorUs, periodUs, w, h));
                }
                chain.composite(inputTexId, outFbo, frameVisible, frameTex, frameExternal, frameBlurs);
                GlErrors.drain("left by GlImageOverlayEffect");
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        @Nullable
        private PipChainGl.PictureBlur pictureBlur(TextOverlayItem item, Bitmap bitmap,
                                                   long timeMs, long timeUs, long periodUs, int w, int h) {
            if (periodUs <= 0 || !item.isRigidMotionBlurEnabled() || timeUs < motionStartUs || timeUs >= motionEndUs
                    || !item.isAnimated() || item.isLinked() || item.hasCornerPin()
                    || !item.isRotationPivotNeutral(null) || item.wantsGlExport()
                    || CaptionAnimator.parsePreset(item.getTextAnimPreset()) != CaptionAnimator.Preset.NONE) {
                return null;
            }
            PictureMotion motion = pictureMotion.computeIfAbsent(item.getId(), id -> {
                float boxWidth = h * (bitmap.getWidth() / (float) bitmap.getHeight());
                return new PictureMotion(
                        MotionItemAdapters.textOrImage(item, w, h, boxWidth, h), itemFences(item));
            });
            var plan = RigidTextureShutterPass.plan(item.getRigidMotionShutter(), motion.sampler(),
                    timeUs, periodUs, motion.breaks(), w, h);
            return plan == null ? null : new PipChainGl.PictureBlur(plan,
                    Math.max(0f, Math.min(1f, item.animatedOpacity(timeMs))));
        }

        private boolean drawTitleMotion(TextOverlayItem item, long timeMs, long timeUs, long periodUs, int w, int h) {
            if (periodUs <= 0 || !item.isRigidMotionBlurEnabled() || timeUs < motionStartUs || timeUs >= motionEndUs
                    || textDrawer == null || !hasUprightTitle(item)) return false;
            float opacity = item.animatedOpacity(timeMs);
            if (!(opacity > 0.001f)) return false;

            var title = uprightTitles.computeIfAbsent(item.getId(), id -> {
                int layoutW = textDrawer.drawWidth();
                int layoutH = textDrawer.drawHeight();
                String shown = TextBoxRenderer.textAt(item, timeMs, projectDurationMs);
                float fontPx = Math.max(1f, item.animatedSizeFraction(timeMs) * layoutH);
                float[] size = new float[2];
                TextBoxRenderer.measure(item, shown, fontPx, size);
                return new UprightTitle(shown, fontPx, size, item, layoutW, layoutH, w, h, itemFences(item));
            });
            if (!title.fits()) return false;
            var plan = RigidTextureShutterPass.plan(item.getRigidMotionShutter(), title.sampler,
                    timeUs, periodUs, title.breaks, w, h);
            if (plan == null) return false;
            if (title.uploadFailed) return false;
            if (title.texture == 0 && !uploadTitle(item, title, timeMs, opacity)) {
                title.uploadFailed = true;
                return false;
            }

            // The shutter writes this layer slot itself. The regular Pip is used only in sharp slots.
            frameVisible.add(null);
            frameTex.add(title.texture);
            frameExternal.add(false);
            frameBlurs.add(new PipChainGl.PictureBlur(plan, 1f));
            return true;
        }

        private static boolean hasUprightTitle(TextOverlayItem item) {
            if (!item.isAnimated() || item.isLinked() || item.isProxy() || item.hasMesh()
                    || item.hasCornerPin() || item.hasTextLook() || item.isTimer() || item.isGeneratedSlide()
                    || item.hasStyleSpans() || item.getStrokeWidthPx() > 0f
                    || item.getGlowRadiusPx() > 0f || item.getShadowRadiusPx() > 0f
                    || item.getImageFadeInMs() != 0 || item.getImageFadeOutMs() != 0
                    || CaptionAnimator.parsePreset(item.getTextAnimPreset()) != CaptionAnimator.Preset.NONE) {
                return false;
            }
            for (var track : item.getKeyframes().tracks()) {
                if (!track.isEmpty() && !KeyframeSet.X.equals(track.property)
                        && !KeyframeSet.Y.equals(track.property) && !KeyframeSet.ROTATION.equals(track.property)) {
                    return false;
                }
            }
            return true;
        }

        private boolean uploadTitle(TextOverlayItem item, UprightTitle title, long timeMs, float opacity) {
            Bitmap bitmap = null;
            int texture = 0;
            try {
                bitmap = Bitmap.createBitmap(title.width, title.height, Bitmap.Config.ARGB_8888);
                TextBoxRenderer.draw(new Canvas(bitmap), item, title.text, title.left, title.top, title.fontPx,
                        timeMs, projectDurationMs, true, opacity);
                texture = PipGl.newStillTexture();
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                title.texture = texture;
                return true;
            } catch (RuntimeException | OutOfMemoryError e) {
                if (texture != 0) GLES20.glDeleteTextures(1, new int[]{texture}, 0);
                FLog.w("GlImageOverlay", "Upright title unavailable; drawing sharp", e);
                return false;
            } finally {
                if (bitmap != null) bitmap.recycle();
            }
        }

        private MotionBreaks itemFences(TextOverlayItem item) {
            long startUs = item.getStartMs() * 1000L;
            MotionBreaks.Builder fences = new MotionBreaks.Builder()
                    .add(motionStartUs).add(motionEndUs).add(startUs);
            // The editor includes the last whole millisecond of the item.
            if (item.getEndMs() != Long.MAX_VALUE) {
                fences.add((item.getEndMs() + 1L) * 1000L);
            }
            for (var track : item.getKeyframes().tracks()) fences.addSteps(track, startUs);
            return fences.build();
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
                texts.put(o.getId(), tf);
            }
            tf.sig = sig;
            // "t=": the signature carries the clock, so this box changes every frame.
            if (sig.startsWith("t=") && drawThroughSurface(tf, o, t)) {
                return tf.drawn ? tf : null;
            }
            if (tf.bmp == null) {
                tf.bmp = Bitmap.createBitmap(Math.max(1, textDrawer.drawWidth()),
                        Math.max(1, textDrawer.drawHeight()), Bitmap.Config.ARGB_8888);
                tf.canvas = new android.graphics.Canvas(tf.bmp);
                tf.tex = PipGl.newStillTexture();
            } else {
                tf.bmp.eraseColor(0);
            }
            tf.external = false;
            try {
                tf.drawn = textDrawer.drawTextItem(tf.canvas, o, t);
            } catch (cc.joycreator.textlab.StudioTextLab.LookFailed e) {
                throw e;   // XROOM-01, as above
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
            if (tf.layer != null) tf.layer.release();
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
                var title = uprightTitles.remove(id);
                if (title != null) title.release();
                return;
            }
            pictureMotion.remove(id);
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
                for (var title : uprightTitles.values()) title.release();
                uprightTitles.clear();
                chain.release();
            }
        }
    }
}
