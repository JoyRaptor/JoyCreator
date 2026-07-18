package com.fadcam.ui.faditor.gltransitions;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.StaticOverlaySettings;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Transition;

import java.io.InputStream;

public class GlTransitionFrameOverlay extends BitmapOverlay {

    private final Context context;
    private final Clip clip;
    private final Transition transition;
    private final long durationMs;
    private final long sourceDurationMs;
    /** Absolute composition time (ms) at which this transition item starts. */
    private final long timelineStartMs;
    @Nullable private final int[] canvasDims;
    @NonNull private final android.net.Uri sourceUri;
    private Bitmap bitmap;
    private Canvas canvas;

    /**
     * Per-instance retriever. {@link MediaMetadataRetriever} is not thread-safe, so this
     * instance must never be shared with another overlay. We synchronize on {@link #retrieverLock}
     * during access as a defensive measure in case Media3 calls {@link #getBitmap} from multiple
     * threads; the lock guarantees that only one thread uses the retriever at a time.
     */
    private MediaMetadataRetriever retriever;
    private final Object retrieverLock = new Object();
    private String retrieverUri;
    private long lastSourceMs = Long.MIN_VALUE;
    private Bitmap lastFrame;
    @Nullable private Bitmap cachedImageBitmap;

    public GlTransitionFrameOverlay(@NonNull Context context,
                                    @NonNull Clip clip,
                                    @NonNull Transition transition,
                                    long durationMs,
                                    long timelineStartMs,
                                    @Nullable int[] canvasDims,
                                    @NonNull android.net.Uri sourceUri) {
        this.context = context.getApplicationContext();
        this.clip = clip;
        this.transition = transition;
        this.durationMs = Math.max(1L, durationMs);
        this.timelineStartMs = timelineStartMs;
        this.canvasDims = canvasDims == null ? null : new int[]{canvasDims[0], canvasDims[1]};
        this.sourceUri = sourceUri;
        this.sourceDurationMs = Math.max(1L, transitionSourceDuration(transition, clip));
    }

    @Override
    public void configure(@NonNull Size size) {
        int w = Math.max(1, size.getWidth());
        int h = Math.max(1, size.getHeight());
        if (bitmap != null && bitmap.getWidth() == w && bitmap.getHeight() == h) return;
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        canvas = new Canvas(bitmap);
    }

    @NonNull
    @Override
    public Bitmap getBitmap(long presentationTimeUs) throws VideoFrameProcessingException {
        if (bitmap == null || bitmap.isRecycled()) {
            bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bitmap);
        }
        float localMs = (presentationTimeUs / 1000f) - timelineStartMs;
        float progress = clamp(localMs / durationMs);
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
        if (clip.isImageClip()) {
            drawImage();
        } else {
            long sourceMs = clip.getInPointMs()
                    + (long) (sourceDurationMs * progress);
            Bitmap frame = decodeFrame(sourceMs);
            if (frame == null) {
                drawSolid(Color.BLACK);
            } else {
                drawFrame(frame);
            }
        }
        // Return a NEW bitmap copy so BitmapOverlay.getTextureId() sees a new
        // generationId each frame and re-uploads the GL texture. Without
        // this the texture is uploaded once with the first frame and never
        // updated — the Wipe/Glitch/whatever transition then shows that
        // first frame as a static image (the user reported "flips vertical"
        // because the wipe boundary moves over a static image).
        return Bitmap.createBitmap(bitmap);
    }

    @NonNull
    @Override
    public StaticOverlaySettings getOverlaySettings(long presentationTimeUs) {
        return new StaticOverlaySettings.Builder().setAlphaScale(1f).build();
    }

    @Override
    public void release() throws VideoFrameProcessingException {
        synchronized (retrieverLock) {
            if (retriever != null) {
                try { retriever.release(); } catch (Exception ignored) { }
                retriever = null;
            }
            retrieverUri = null;
        }
        if (lastFrame != null && !lastFrame.isRecycled()) lastFrame.recycle();
        lastFrame = null;
        lastSourceMs = Long.MIN_VALUE;
        if (cachedImageBitmap != null && !cachedImageBitmap.isRecycled()) cachedImageBitmap.recycle();
        cachedImageBitmap = null;
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = null;
        canvas = null;
    }

    private void drawImage() {
        if (cachedImageBitmap != null && !cachedImageBitmap.isRecycled()) {
            drawFrame(cachedImageBitmap);
            return;
        }
        try (InputStream in = context.getContentResolver().openInputStream(sourceUri)) {
            if (in == null) {
                drawSolid(Color.BLACK);
                return;
            }
            Bitmap source = BitmapFactory.decodeStream(in);
            if (source == null) {
                drawSolid(Color.BLACK);
                return;
            }
            cachedImageBitmap = source;
            drawFrame(cachedImageBitmap);
        } catch (Exception e) {
            drawSolid(Color.BLACK);
        }
    }

    @Nullable
    private Bitmap decodeFrame(long sourceMs) {
        if (sourceMs == lastSourceMs && lastFrame != null && !lastFrame.isRecycled()) {
            return lastFrame;
        }
        if (Math.abs(sourceMs - lastSourceMs) < 33L && lastFrame != null && !lastFrame.isRecycled()) {
            return lastFrame;
        }
        try {
            String uriString = sourceUri.toString();
            Bitmap frame;
            synchronized (retrieverLock) {
                if (retriever == null || !uriString.equals(retrieverUri)) {
                    if (retriever != null) {
                        try { retriever.release(); } catch (Exception ignored) { }
                    }
                    retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(context, sourceUri);
                    retrieverUri = uriString;
                }
                frame = retriever.getFrameAtTime(sourceMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame == null) {
                    frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST);
                }
            }
            lastSourceMs = sourceMs;
            if (frame != null) lastFrame = frame;
            return frame;
        } catch (Exception e) {
            return null;
        }
    }

    private void drawFrame(@NonNull Bitmap frame) {
        // The frame comes from MediaMetadataRetriever in Android's Y-down
        // coordinate system (Y=0 at the top). The GL transition shader samples
        // this overlay with the identity tex-transformation matrix, so the
        // texture is read with GL's Y-up convention (Y=0 at the bottom of the
        // screen). That mismatch rendered the to-clip frame UPSIDE DOWN during
        // the transition — the user's "flips vertical" complaint. Pre-flip the
        // frame here on the Android canvas so the uploaded texture is correctly
        // oriented for GL sampling.
        canvas.save();
        canvas.scale(1f, -1f, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);
        // Sample only the clip's CROP region (F12): the raw decode ignores the incoming
        // clip's crop, so the exported blend showed the uncropped frame + black bars,
        // then snapped to the cropped framing at the cut. Mirrors the preview fix
        // (FaditorEditorActivity.cropToClipBounds).
        android.graphics.Rect src = cropSrcRect(frame);
        android.graphics.RectF rect = fitRect(src.width(), src.height(),
                bitmap.getWidth(), bitmap.getHeight());
        canvas.drawBitmap(frame, src, rect, new android.graphics.Paint(
                android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.ANTI_ALIAS_FLAG));
        canvas.restore();
    }

    /** The clip's custom-crop region of {@code frame} in pixels (full frame when uncropped). */
    @NonNull
    private android.graphics.Rect cropSrcRect(@NonNull Bitmap frame) {
        int w = frame.getWidth(), h = frame.getHeight();
        android.graphics.Rect full = new android.graphics.Rect(0, 0, w, h);
        if (!"custom".equals(clip.getCropPreset())) return full;
        float l = clip.getCropLeft(), t = clip.getCropTop();
        float r = clip.getCropRight(), b = clip.getCropBottom();
        float cw = r - l, ch = b - t;
        if (cw <= 0.01f || ch <= 0.01f || (cw >= 0.99f && ch >= 0.99f)) return full;
        int x = Math.max(0, Math.min(w - 1, Math.round(l * w)));
        int y = Math.max(0, Math.min(h - 1, Math.round(t * h)));
        int pw = Math.min(w - x, Math.round(cw * w));
        int ph = Math.min(h - y, Math.round(ch * h));
        if (pw <= 0 || ph <= 0) return full;
        return new android.graphics.Rect(x, y, x + pw, y + ph);
    }

    private void drawSolid(int color) {
        canvas.drawColor(color);
    }

    @NonNull
    private static android.graphics.RectF fitRect(int srcW, int srcH, int outW, int outH) {
        float scale = Math.min(outW / (float) Math.max(1, srcW), outH / (float) Math.max(1, srcH));
        float w = srcW * scale;
        float h = srcH * scale;
        return new android.graphics.RectF((outW - w) / 2f, (outH - h) / 2f,
                (outW - w) / 2f + w, (outH - h) / 2f + h);
    }

    private static long transitionSourceDuration(@NonNull Transition transition, @NonNull Clip clip) {
        long requested = Math.max(1L, (long) (transition.durationMs * Math.max(0.01f, clip.getSpeedMultiplier())));
        return Math.min(Math.max(0L, clip.getOutPointMs() - clip.getInPointMs()), requested);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
