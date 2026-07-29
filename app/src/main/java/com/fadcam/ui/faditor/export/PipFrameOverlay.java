package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BitmapOverlay;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.compositor.OverlayVideoPreviewView;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.Clip;

/**
 * M-EXPORT-2 blend — the frame source feeding {@link BlendModeGlEffect}'s shader:
 * ONE blend-mode PiP clip drawn (positioned/scaled/rotated/faded) into a
 * frame-sized transparent bitmap per presentation time. Same MMR mechanism and
 * the SAME transform conventions as {@code CompositeExportOverlay}'s NORMAL-blend
 * PiP pass (absolute-timeline sampling via {@code KeyframeSet.valueAt}, the
 * preview's DEFAULT_* fallbacks, end-inclusive window, full-fit scale reference)
 * — keep the two in lockstep. Positioning stays on the proven Canvas path so the
 * shader can blend with plain full-frame UVs (the export transition wrapper
 * samples its bitmap overlay with unflipped video UVs — device-proven).
 */
final class PipFrameOverlay extends BitmapOverlay {

    private static final String TAG = "PipFrameOverlay";

    private final Context context;
    private final Clip clip;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    @Nullable private MediaMetadataRetriever retriever;
    private final Object retrieverLock = new Object(); // MMR thread-safety precedent
    @Nullable private Bitmap bitmap;
    @Nullable private Canvas canvas;
    /** Identity-stable, never mutated — safe for BitmapOverlay's texture cache. */
    @Nullable private Bitmap transparent;
    private int frameW = 1, frameH = 1;
    private boolean loggedError = false;

    PipFrameOverlay(@NonNull Context context, @NonNull Clip clip) {
        this.context = context.getApplicationContext();
        this.clip = clip;
    }

    @Override
    public void configure(@NonNull Size videoSize) {
        super.configure(videoSize);
        frameW = Math.max(1, videoSize.getWidth());
        frameH = Math.max(1, videoSize.getHeight());
    }

    /**
     * Whether this clip's overlay window covers {@code presentationTimeUs} —
     * the CPU-side gate for track mattes: outside the matte's window the
     * recipient must render UNMATTED (B3: "wherever one overlaps the other in
     * time"), and the shader cannot tell an inactive matte from a black one.
     */
    boolean activeAt(long presentationTimeUs) {
        long timelineMs = presentationTimeUs / 1000;
        long start = clip.getOverlayStartMs();
        long end = start + Math.max(0, clip.getTrimmedDurationMs());
        return timelineMs >= start && timelineMs <= end;
    }

    @NonNull
    @Override
    public Bitmap getBitmap(long presentationTimeUs) {
        if (bitmap == null || bitmap.getWidth() != frameW || bitmap.getHeight() != frameH) {
            bitmap = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bitmap);
            transparent = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888);
        }
        canvas.drawColor(0, PorterDuff.Mode.CLEAR);

        long timelineMs = presentationTimeUs / 1000; // composition-absolute (see CompositeExportOverlay)
        long start = clip.getOverlayStartMs();
        long end = start + Math.max(0, clip.getTrimmedDurationMs());
        // Empty paths return the IDENTITY-STABLE transparent bitmap: BitmapOverlay
        // re-uploads only on instance change, so this uploads once and stays
        // transparent — returning the mutated scratch here would leave the LAST
        // PiP frame on screen past the window (texture keyed to that instance).
        if (timelineMs < start || timelineMs > end) return transparent;

        KeyframeSet kf = clip.getOverlayTransform();
        float opacity = kf == null ? 1f : Math.max(0f, Math.min(1f,
                kf.valueAt(KeyframeSet.OPACITY, timelineMs, 1f)));
        if (opacity <= 0.001f) return transparent;

        Bitmap frame = frameAt(clip.getInPointMs() + (timelineMs - start));
        if (frame == null) return transparent;

        final float ddx = OverlayVideoPreviewView.DEFAULT_X;
        final float ddy = OverlayVideoPreviewView.DEFAULT_Y;
        final float dds = OverlayVideoPreviewView.DEFAULT_SCALE;
        float x = kf == null ? ddx : kf.valueAt(KeyframeSet.X, timelineMs, ddx);
        float y = kf == null ? ddy : kf.valueAt(KeyframeSet.Y, timelineMs, ddy);
        float scale = kf == null ? dds : kf.valueAt(KeyframeSet.SCALE, timelineMs, dds);
        float rot = kf == null ? 0f : kf.valueAt(KeyframeSet.ROTATION, timelineMs, 0f);
        float fit = Math.min(frameW / (float) frame.getWidth(), frameH / (float) frame.getHeight());
        float w = frame.getWidth() * fit * scale;
        float h = frame.getHeight() * fit * scale;
        float cx = x * frameW;
        float cy = y * frameH;

        paint.setAlpha(Math.round(opacity * 255));
        // Compositing masks (§C family): mask in FRAME space — the same
        // canvas-normalized shapes the preview masks with, scaled onto this frame.
        // Opened BEFORE the rotate so a hole stays put over the composed frame while
        // the PiP moves under it.
        com.fadcam.ui.faditor.model.CompositingSpec spec = clip.getCompositing();
        com.fadcam.ui.faditor.model.MaskPathBuilder.MaskScope maskSave =
                com.fadcam.ui.faditor.model.MaskPathBuilder.beginMask(
                        canvas, spec, frameW, frameH, 0f, 0f);
        if (rot != 0f) canvas.rotate(rot, cx, cy);
        canvas.drawBitmap(frame, null,
                new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f), paint);
        com.fadcam.ui.faditor.model.MaskPathBuilder.endMask(
                canvas, spec, frameW, frameH, 0f, 0f, maskSave);
        frame.recycle();
        // NEW instance per frame — BitmapOverlay caches the GL texture keyed on
        // Bitmap identity; returning the reused scratch bitmap would freeze the
        // first frame forever (GlTransitionFrameOverlay documents the same trap).
        return Bitmap.createBitmap(bitmap);
    }

    @Nullable
    private Bitmap frameAt(long sourceMs) {
        long clamped = Math.min(sourceMs, Math.max(clip.getInPointMs(), clip.getOutPointMs() - 1));
        synchronized (retrieverLock) {
            try {
                if (retriever == null) {
                    retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(context, clip.getSourceUri());
                }
                return retriever.getFrameAtTime(clamped * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST);
            } catch (Exception e) {
                if (!loggedError) {
                    FLog.w(TAG, "blend-PiP frame failed for " + clip.getSourceUri()
                            + " (logged once)", e);
                    loggedError = true;
                }
                return null;
            }
        }
    }

    @Override
    public void release() throws androidx.media3.common.VideoFrameProcessingException {
        super.release();
        synchronized (retrieverLock) {
            if (retriever != null) {
                try { retriever.release(); } catch (Exception ignored) { }
                retriever = null;
            }
        }
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = null;
        canvas = null;
        if (transparent != null && !transparent.isRecycled()) transparent.recycle();
        transparent = null;
    }
}
