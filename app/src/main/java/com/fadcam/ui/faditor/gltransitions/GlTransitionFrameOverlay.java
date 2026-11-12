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
    /** The picture being drawn this frame: one of {@link #pictureA} / {@link #pictureB}. */
    private Bitmap bitmap;
    private Canvas canvas;
    /** Two reusable pictures used in turn (code review 11-11, E17); see {@link #getBitmap}. */
    @Nullable private Bitmap pictureA, pictureB;
    @Nullable private Canvas canvasA, canvasB;
    private boolean useB;

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
        allocatePictures(Math.max(1, size.getWidth()), Math.max(1, size.getHeight()));
    }

    private void allocatePictures(int w, int h) {
        if (pictureA != null && !pictureA.isRecycled()
                && pictureA.getWidth() == w && pictureA.getHeight() == h) return;
        recyclePictures();
        pictureA = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        pictureB = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        canvasA = new Canvas(pictureA);
        canvasB = new Canvas(pictureB);
        bitmap = pictureA;
        canvas = canvasA;
    }

    private void recyclePictures() {
        if (pictureA != null && !pictureA.isRecycled()) pictureA.recycle();
        if (pictureB != null && !pictureB.isRecycled()) pictureB.recycle();
        pictureA = pictureB = null;
        canvasA = canvasB = null;
        bitmap = null;
        canvas = null;
    }

    @NonNull
    @Override
    public Bitmap getBitmap(long presentationTimeUs) throws VideoFrameProcessingException {
        if (pictureA == null || pictureA.isRecycled()) allocatePictures(1080, 1920);
        // TWO PICTURES IN TURN, not a new copy per frame (code review 11-11, E17: 8 MB a frame
        // at 1080p). BitmapOverlay.getTextureId re-uploads when the instance differs from the
        // last one it saw and reads the pixels during that call, so the picture handed over
        // last frame is never the one drawn into now. Every frame of a transition is drawn
        // and handed over, so a plain toggle keeps consecutive frames different instances.
        useB = !useB;
        bitmap = useB ? pictureB : pictureA;
        canvas = useB ? canvasB : canvasA;
        canvas.restoreToCount(1);
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
        // A DIFFERENT instance from last frame so BitmapOverlay.getTextureId() re-uploads.
        // Handing back the same picture uploads only the first frame and the Wipe/Glitch/
        // whatever transition then shows that frame as a still (the user reported "flips
        // vertical" because the wipe boundary moved over a static image). The toggle above
        // gives a different instance every frame without the old full-frame copy.
        return bitmap;
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
        recyclePictures();
        // The base class deletes the GL texture it uploaded into; this override never called
        // it, so every transition in an export left one texture behind (code review 11-12).
        super.release();
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

    /** The clip's crop region of {@code frame} in pixels (full frame when uncropped). */
    @NonNull
    private android.graphics.Rect cropSrcRect(@NonNull Bitmap frame) {
        int w = frame.getWidth(), h = frame.getHeight();
        android.graphics.Rect full = new android.graphics.Rect(0, 0, w, h);
        // Named presets count too, not just "custom": the outgoing leg is cropped by the
        // Crop effect for ANY preset, so ignoring presets here made a preset-cropped
        // incoming clip blend uncropped and then snap at the cut.
        //
        // EXCEPT for image clips. ExportManager gates that Crop effect on `isVideo`
        // (= !isImageClip), so an image clip's own segment is never crop-effected at all;
        // honouring a preset here would crop the blend and NOT the cut — the same snap,
        // mirrored. Images therefore keep the pre-existing "custom"-only rule, which makes
        // this expression identical to the old code for them.
        float[] cf = (!clip.isImageClip() || "custom".equals(clip.getCropPreset()))
                ? clip.effectiveCropFractions() : null;
        if (cf == null) return full;
        float l = cf[0], t = cf[1];
        float r = cf[2], b = cf[3];
        float cw = r - l, ch = b - t;
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
