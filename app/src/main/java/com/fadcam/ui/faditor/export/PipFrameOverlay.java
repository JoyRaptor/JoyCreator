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
 * frame-sized transparent bitmap per presentation time. Frames come from a
 * {@link SequentialFrameReader} (ONE forward decode per source; see {@link #frameAt}
 * for the measurement that forced it) and carry
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
    /** Second half of the ping-pong pair; see {@link #getBitmap}. */
    @Nullable private Bitmap bitmapB;
    @Nullable private Canvas canvasB;
    private boolean useB = false;
    /** Identity-stable, never mutated — safe for BitmapOverlay's texture cache. */
    @Nullable private Bitmap transparent;
    private int frameW = 1, frameH = 1;
    private boolean loggedError = false;

    /** Composition→editor time correction; see {@link #editorTimeOffsetMs}. */
    private final long editorTimeOffsetMs;

    /**
     * @param editorTimeOffsetMs LEDGER §2d — the cumulative transition duration before this
     *        clip's window. {@code getOverlayStartMs()} is authored in EDITOR time, which
     *        ignores transitions, while {@code presentationTimeUs} is Composition time, which
     *        is compressed by them. Without this every PiP after a seam renders late by one
     *        transition. Zero when the project has no transitions.
     */
    PipFrameOverlay(@NonNull Context context, @NonNull Clip clip, long editorTimeOffsetMs) {
        this.editorTimeOffsetMs = editorTimeOffsetMs;
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
        long timelineMs = presentationTimeUs / 1000 + editorTimeOffsetMs;
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
            bitmapB = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888);
            canvasB = new Canvas(bitmapB);
            transparent = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888);
        }
        // PING-PONG, not a copy. BitmapOverlay re-uploads its texture when the Bitmap IDENTITY
        // changes, so alternating between two buffers satisfies it without the frame-sized
        // memcpy a fresh copy costs — at 1080p that copy was 8MB every frame. Two buffers are
        // enough because media3 has finished with the previous one by the time we hand it the
        // next. (CompositeExportOverlay does the same, for the same reason.)
        useB = !useB;
        final Bitmap target = useB ? bitmapB : bitmap;
        final Canvas canvas = useB ? canvasB : this.canvas;
        canvas.drawColor(0, PorterDuff.Mode.CLEAR);

        long timelineMs = presentationTimeUs / 1000 + editorTimeOffsetMs; // →editor time (§2d)
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

        final float ddx = OverlayVideoPreviewView.DEFAULT_X;
        final float ddy = OverlayVideoPreviewView.DEFAULT_Y;
        final float dds = OverlayVideoPreviewView.DEFAULT_SCALE;
        float x = kf == null ? ddx : kf.valueAt(KeyframeSet.X, timelineMs, ddx);
        float y = kf == null ? ddy : kf.valueAt(KeyframeSet.Y, timelineMs, ddy);
        float scale = kf == null ? dds : kf.valueAt(KeyframeSet.SCALE, timelineMs, dds);
        float rot = kf == null ? 0f : kf.valueAt(KeyframeSet.ROTATION, timelineMs, 0f);

        // How large this frame can possibly be DRAWN, so the reader need not build pixels the
        // destination rect is going to discard. The full-fit maths below scales by
        // frame.getWidth(), so a frame delivered at half resolution produces double the `fit`
        // and lands at exactly the same size on the canvas — the subsampling is invisible here
        // by construction, which is why it is safe to hand the reader a ceiling.
        int maxOutW = (int) Math.ceil(frameW * Math.max(0.01f, scale)) + 1;
        int maxOutH = (int) Math.ceil(frameH * Math.max(0.01f, scale)) + 1;
        Bitmap frame = frameAt(clip.getInPointMs() + (timelineMs - start), maxOutW, maxOutH);
        if (frame == null) return transparent;
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
        // Time-aware: mask keyframes and the object LINK resolve here, through the same
        // MaskAnimator the preview goes through. Passing timelineMs (not the presentation time)
        // because mask keys share the ABSOLUTE timeline base a PiP's transform keys use.
        com.fadcam.ui.faditor.model.MaskPathBuilder.MaskScope maskSave =
                com.fadcam.ui.faditor.model.MaskPathBuilder.beginMask(
                        canvas, spec, kf, timelineMs, frameW, frameH, 0f, 0f);
        if (rot != 0f) canvas.rotate(rot, cx, cy);
        canvas.drawBitmap(frame, null,
                new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f), paint);
        com.fadcam.ui.faditor.model.MaskPathBuilder.endMask(canvas, maskSave);
        // NOT recycled: `frame` is BORROWED from the frame source and stays valid until the
        // next frameAt(). Recycling it here would destroy the reader's decoded frame, and the
        // very next request that could have reused it would decode again instead.
        // A DIFFERENT instance from last frame — BitmapOverlay caches the GL texture keyed on
        // Bitmap identity; returning the SAME scratch bitmap twice running would freeze the
        // first frame forever (GlTransitionFrameOverlay documents the same trap). The
        // ping-pong above guarantees the alternation without copying.
        return target;
    }

    /** Source time of {@link #cachedFrame}, or Long.MIN_VALUE when nothing is cached. */
    private long cachedFrameSourceMs = Long.MIN_VALUE;
    @Nullable private Bitmap cachedFrame;
    private int frameHits = 0;
    private int frameMisses = 0;
    /** The fast path; null until first use, and abandoned for the retriever if it degrades. */
    @Nullable private SequentialFrameReader reader;
    private boolean readerAbandoned = false;

    /**
     * The source frame for {@code sourceMs}, BORROWED — valid until the next call, never to be
     * recycled by the caller.
     *
     * <p><b>THE SAME SOURCE FRAME, ASKED FOR AGAIN, COST A FULL SEEK-AND-DECODE.</b> That was
     * the single most expensive thing in an export. Measured on JoyRaptor's Note 9 on 2026-08-27:
     * 275 {@code getFrameAtTime} calls at roughly 0.8 SECONDS each, which was the entire stall
     * between progress 0.29 and 0.37 — 8% of the timeline eating 80% of the wall clock.
     * {@code OPTION_CLOSEST} re-seeks to the preceding sync frame and decodes forward to the
     * requested time on EVERY call, so a clip with keyframes a second or two apart re-decodes
     * dozens of frames to produce one.
     *
     * <p>An export walks time forward, so {@link SequentialFrameReader} now serves these from
     * ONE forward decode per source and the seeking disappears. The retriever survives as the
     * fallback for anything the sweep cannot read — an unsupported codec, or a caller that
     * turns out to jump backwards — because a slow export beats a broken one.
     */
    @Nullable
    private Bitmap frameAt(long sourceMs, int maxOutW, int maxOutH) {
        long clamped = Math.min(sourceMs, Math.max(clip.getInPointMs(), clip.getOutPointMs() - 1));
        synchronized (retrieverLock) {
            if (!readerAbandoned) {
                if (reader == null) {
                    reader = new SequentialFrameReader(context, clip.getSourceUri());
                }
                Bitmap seq = reader.frameAt(clamped * 1000L, maxOutW, maxOutH);
                if (seq != null) {
                    frameHits++;
                    return seq;
                }
                // Degraded (or could not open): stop asking, release, and fall through.
                readerAbandoned = true;
                FLog.w(TAG, "Sequential reader unavailable for " + clip.getSourceUri()
                        + "; falling back to the per-frame retriever (this export will be slow)");
                reader.release();
                reader = null;
            }
            try {
                if (cachedFrame != null && !cachedFrame.isRecycled()
                        && cachedFrameSourceMs == clamped) {
                    return cachedFrame;
                }
                if (retriever == null) {
                    retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(context, clip.getSourceUri());
                }
                Bitmap decoded = retriever.getFrameAtTime(clamped * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST);
                frameMisses++;
                if (decoded == null) return null;
                if (cachedFrame != null && !cachedFrame.isRecycled()) cachedFrame.recycle();
                cachedFrame = decoded;
                cachedFrameSourceMs = clamped;
                return cachedFrame;
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
            if (frameHits + frameMisses > 0) {
                FLog.i(TAG, "PIP_FRAMES sequential=" + frameHits + " retrieverSeeks=" + frameMisses
                        + " (a retrieverSeek is a full seek + forward-decode; see frameAt)");
            }
            if (reader != null) { reader.release(); reader = null; }
            if (cachedFrame != null && !cachedFrame.isRecycled()) cachedFrame.recycle();
            cachedFrame = null;
            if (retriever != null) {
                try { retriever.release(); } catch (Exception ignored) { }
                retriever = null;
            }
        }
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = null;
        canvas = null;
        if (bitmapB != null && !bitmapB.isRecycled()) bitmapB.recycle();
        bitmapB = null;
        canvasB = null;
        if (transparent != null && !transparent.isRecycled()) transparent.recycle();
        transparent = null;
    }
}
