package com.fadcam.ui.faditor.compositor;

import android.content.Context;
import android.graphics.RectF;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.Clip;

import java.util.ArrayList;
import java.util.List;

/**
 * M-COMP-2 (PLAN_LAYERS_V2 §3.3) — live second-video / PiP preview layer.
 *
 * <p>Sits directly above the master video surfaces and below the visualizer/image/sprite/text
 * overlay views. Owns ONE overlay {@link ExoPlayer} (the Part-10 probe measured the Note 9
 * comfortably running 3 simultaneous 1080p HEVC decoders, so master + this one live overlay is
 * well inside budget) rendering into a {@link TextureView} that this view positions, scales,
 * rotates and alpha-blends per tick from the clip's {@code overlayTransform} {@link KeyframeSet}
 * — the SAME evaluator ({@code KeyframeSet.valueAt} at the absolute timeline ms) the export path
 * samples, so preview and export cannot diverge on the transform math.</p>
 *
 * <p><b>Decoder policy (plan §3.3):</b> only the TOP-most visible overlay-video clip at the
 * playhead gets the live decoder. Other simultaneously-visible overlay videos are not drawn in
 * preview v1 (a cached-still fallback is a documented follow-up); export composites all of them
 * full-quality. When no overlay clip is visible the player pauses; when the bound list empties
 * the player is RELEASED so scrub-heavy single-track editing and background exports never pay
 * for an idle decoder.</p>
 *
 * <p><b>Transform convention</b> (creation writes these as keyframes at t=0; absent tracks fall
 * back to the same defaults): {@code x},{@code y} = normalised centre in the video content rect;
 * {@code scale} = multiplier over the full-fit size (1.0 = overlay fills the canvas, matching
 * export's {@code ScaleAndRotateTransformation}+{@code Presentation} semantics); {@code rotation}
 * degrees; {@code opacity} 0..1.</p>
 *
 * <p>Gestures mirror {@code SpriteOverlayView}/{@code TextOverlayLayer}: drag moves, pinch
 * scales, empty areas pass through to the player. v1 writes STATIC transforms (replaces the
 * key at t=0); armed auto-keyframing rides with the S5-style lane work later. One undo step per
 * gesture via a whole-{@link KeyframeSet} snapshot.</p>
 */
public class OverlayVideoPreviewView extends FrameLayout {

    /** M-COMP-2 feature flag: gates the AddAssetBottomSheet creation row + this preview. */
    public static final boolean LIVE_PIP = true;

    private static final String TAG = "OverlayVideoPreview";

    /** Creation-time / fallback transform defaults (top-right corner PiP at ~1/3 size). */
    public static final float DEFAULT_X = 0.72f;
    public static final float DEFAULT_Y = 0.22f;
    public static final float DEFAULT_SCALE = 0.35f;

    /** Playback drift beyond this re-syncs the overlay player to the master clock. */
    private static final long DRIFT_RESYNC_MS = 150;
    /** Paused/scrub: re-seek when the shown frame is further off than this. */
    private static final long SCRUB_RESEEK_MS = 40;

    public interface Callback {
        /** Pixel rect of the visible video content inside this layer's bounds. */
        @NonNull RectF getVideoContentRect();
        /** Remux-to-seekable resolver — MUST be the same one the master engine uses. */
        @NonNull Uri resolveSeekable(@NonNull Clip clip);
        /** A transform changed — persist it (autosave). */
        void onOverlayVideoChanged();
        /** A drag/pinch finished; record ONE undo step from the snapshot. */
        void onOverlayVideoManipulated(@NonNull Clip clip, @NonNull KeyframeSet before);
        /**
         * Playback volume for this PiP — the host answers from
         * {@code LayerPreviewController.effectiveOverlayVolume} so preview and export share
         * one authority. 0 for every clip that has not opted into audio (the default), which
         * is exactly the hardcoded silence this view used to apply. See SPEC_PIP_AUDIO.
         */
        float overlayVolumeFor(@NonNull Clip clip);
    }

    private final TextureView textureView;
    private final List<Clip> clips = new ArrayList<>();
    @Nullable private Callback callback;
    @Nullable private ExoPlayer player;
    @Nullable private Clip active;          // clip currently owning the live decoder
    private long currentTimeMs;
    private boolean masterPlaying;
    private int videoW, videoH;             // active overlay's decoded size
    private int baseW, baseH;               // TextureView layout box (full-fit in content rect)

    // ── Still-frame fallback (plan §3.3): simultaneously-visible overlay clips
    // BELOW the live top-most render a cached MMR still instead of nothing
    // ("preview shows one live overlay video; export shows all" — the stills
    // close the "nothing" half). Decoded ASYNC on one worker (MMR on the UI
    // thread would jank every tick); refreshed on a coarse time bucket. Drawn
    // in onDraw = behind the TextureView child = correct z (stills are always
    // below the live top-most by definition).
    private static final long STILL_BUCKET_MS = 400;
    private final java.util.Map<String, StillFrame> stills = new java.util.HashMap<>();
    private final java.util.concurrent.ExecutorService stillExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.graphics.Paint stillPaint = new android.graphics.Paint(
            android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.ANTI_ALIAS_FLAG);

    private static final class StillFrame {
        @Nullable android.graphics.Bitmap bitmap;
        long bucketMs = Long.MIN_VALUE;     // source bucket the bitmap shows
        long pendingBucketMs = Long.MIN_VALUE; // bucket a worker decode is in flight for
    }

    // Gesture state (SpriteOverlayView contract).
    private final ScaleGestureDetector scaleDetector;
    @Nullable private Clip manipulating;
    @Nullable private KeyframeSet beforeGesture;
    private float downRawX, downRawY, startX, startY, startScale;
    private boolean moved;

    public OverlayVideoPreviewView(Context ctx) { this(ctx, null); }

    public OverlayVideoPreviewView(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        setWillNotDraw(false); // stills draw in onDraw (behind the TextureView child)
        textureView = new TextureView(ctx);
        textureView.setVisibility(GONE);
        addView(textureView, new LayoutParams(1, 1, Gravity.CENTER));
        scaleDetector = new ScaleGestureDetector(ctx,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        if (manipulating != null) {
                            float s = Math.max(0.05f, Math.min(3f,
                                    readValue(manipulating, KeyframeSet.SCALE, DEFAULT_SCALE)
                                            * d.getScaleFactor()));
                            putStatic(manipulating, KeyframeSet.SCALE, s);
                            applyTransform(manipulating);
                        }
                        return true;
                    }
                });
    }

    /** Bind the z-ordered (bottom→top) visible overlay-video clips. Empty releases the decoder. */
    public void setClips(@NonNull List<Clip> visible, @NonNull Callback cb) {
        this.clips.clear();
        this.clips.addAll(visible);
        this.callback = cb;
        if (clips.isEmpty()) {
            releasePlayer();
        }
        syncToTime();
    }

    /** Drive time-ranges, playback sync, and keyframed transforms — one call per playhead tick. */
    public void setPlayheadMs(long timelineMs, boolean masterIsPlaying) {
        this.currentTimeMs = timelineMs;
        this.masterPlaying = masterIsPlaying;
        syncToTime();
    }

    /** Pause the overlay decoder (activity onPause). */
    public void pausePlayback() {
        if (player != null) player.pause();
    }

    /** Release the overlay decoder entirely (activity onDestroy / export start). */
    public void releasePlayer() {
        if (player != null) {
            try { player.release(); } catch (RuntimeException ignored) { }
            player = null;
        }
        active = null;
        videoW = 0;
        videoH = 0;
        textureView.setVisibility(GONE);
    }

    public boolean isEmpty() { return clips.isEmpty(); }

    // ── Core sync ─────────────────────────────────────────────────────────────

    private void syncToTime() {
        if (callback == null) return;
        Clip top = topVisibleAt(currentTimeMs);
        refreshStills(top);
        if (top == null) {
            textureView.setVisibility(GONE);
            if (player != null) player.pause();
            return;
        }
        ensureActive(top);
        if (player == null) return;
        textureView.setVisibility(VISIBLE);
        applyTransform(top);

        long want = top.getInPointMs() + Math.max(0, currentTimeMs - top.getOverlayStartMs());
        long pos = player.getCurrentPosition();
        if (masterPlaying) {
            if (!player.isPlaying()) {
                player.seekTo(want);
                player.play();
            } else if (Math.abs(pos - want) > DRIFT_RESYNC_MS) {
                player.seekTo(want);
            }
        } else {
            if (player.isPlaying()) player.pause();
            if (Math.abs(pos - want) > SCRUB_RESEEK_MS) {
                player.seekTo(want);
            }
        }
    }

    /**
     * Still-frame upkeep for every visible clip EXCEPT the live top-most: kick an
     * async decode when the clip's 400ms source bucket moved, drop stills for
     * clips no longer visible (bounded memory), and redraw when anything changed.
     */
    private void refreshStills(@Nullable Clip top) {
        boolean changed = false;
        java.util.Set<String> visibleIds = new java.util.HashSet<>();
        for (Clip c : clips) {
            if (c == top) continue;
            long start = c.getOverlayStartMs();
            long end = start + Math.max(0, c.getTrimmedDurationMs());
            if (currentTimeMs < start || currentTimeMs > end) continue;
            visibleIds.add(c.getId());
            long sourceMs = c.getInPointMs() + (currentTimeMs - start);
            long bucket = (sourceMs / STILL_BUCKET_MS) * STILL_BUCKET_MS;
            StillFrame sf = stills.get(c.getId());
            if (sf == null) {
                sf = new StillFrame();
                stills.put(c.getId(), sf);
            }
            if (sf.bucketMs != bucket && sf.pendingBucketMs != bucket) {
                sf.pendingBucketMs = bucket;
                decodeStillAsync(c, bucket);
            }
        }
        java.util.Iterator<java.util.Map.Entry<String, StillFrame>> it =
                stills.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, StillFrame> e = it.next();
            if (!visibleIds.contains(e.getKey())) {
                if (e.getValue().bitmap != null) e.getValue().bitmap.recycle();
                it.remove();
                changed = true;
            }
        }
        if (changed) invalidate();
    }

    private void decodeStillAsync(@NonNull Clip clip, long bucketMs) {
        final Uri uri = callback != null ? callback.resolveSeekable(clip) : clip.getSourceUri();
        final String id = clip.getId();
        final Runnable task = () -> {
            android.graphics.Bitmap frame = null;
            android.media.MediaMetadataRetriever r = null;
            try {
                r = new android.media.MediaMetadataRetriever();
                r.setDataSource(getContext(), uri);
                frame = r.getFrameAtTime(bucketMs * 1000L,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Exception e) {
                FLog.w(TAG, "still decode failed for " + uri.getLastPathSegment(), e);
            } finally {
                if (r != null) { try { r.release(); } catch (Exception ignored) { } }
            }
            final android.graphics.Bitmap result = frame;
            post(() -> {
                StillFrame sf = stills.get(id);
                if (sf == null) { // clip left visibility while we decoded
                    if (result != null) result.recycle();
                    return;
                }
                if (result != null) {
                    if (sf.bitmap != null) sf.bitmap.recycle();
                    sf.bitmap = result;
                    sf.bucketMs = bucketMs;
                }
                if (sf.pendingBucketMs == bucketMs) sf.pendingBucketMs = Long.MIN_VALUE;
                invalidate();
            });
        };
        try {
            stillExecutor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Detached (executor shut down) — stills simply stop refreshing.
        }
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);
        if (callback == null || stills.isEmpty()) return;
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return;
        Clip top = topVisibleAt(currentTimeMs);
        // List order = z bottom→top; stills are all below the live TextureView child.
        for (Clip c : clips) {
            if (c == top) continue;
            StillFrame sf = stills.get(c.getId());
            if (sf == null || sf.bitmap == null || sf.bitmap.isRecycled()) continue;
            KeyframeSet kf = c.getOverlayTransform();
            long t = currentTimeMs;
            float x = kf == null ? DEFAULT_X : kf.valueAt(KeyframeSet.X, t, DEFAULT_X);
            float y = kf == null ? DEFAULT_Y : kf.valueAt(KeyframeSet.Y, t, DEFAULT_Y);
            float scale = kf == null ? DEFAULT_SCALE : kf.valueAt(KeyframeSet.SCALE, t, DEFAULT_SCALE);
            float rot = kf == null ? 0f : kf.valueAt(KeyframeSet.ROTATION, t, 0f);
            float alpha = kf == null ? 1f : Math.max(0f, Math.min(1f,
                    kf.valueAt(KeyframeSet.OPACITY, t, 1f)));
            float fit = Math.min(r.width() / sf.bitmap.getWidth(),
                    r.height() / sf.bitmap.getHeight());
            float w = sf.bitmap.getWidth() * fit * scale;
            float h = sf.bitmap.getHeight() * fit * scale;
            float cx = r.left + x * r.width();
            float cy = r.top + y * r.height();
            stillPaint.setAlpha(Math.round(alpha * 255));
            // Compositing masks (§C): same shapes, same authority as export —
            // masked in content-rect space BEFORE the rotate (a hole stays put
            // over the frame while the PiP moves under it, mirroring
            // PipFrameOverlay's order exactly).
            com.fadcam.ui.faditor.model.CompositingSpec cspec = c.getCompositing();
            com.fadcam.ui.faditor.model.MaskPathBuilder.MaskScope ms =
                    com.fadcam.ui.faditor.model.MaskPathBuilder.beginMask(
                            canvas, cspec, r.width(), r.height(), r.left, r.top);
            if (rot != 0f) canvas.rotate(rot, cx, cy);
            canvas.drawBitmap(sf.bitmap, null, new RectF(
                    cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f), stillPaint);
            com.fadcam.ui.faditor.model.MaskPathBuilder.endMask(
                    canvas, cspec, r.width(), r.height(), r.left, r.top, ms);
        }
    }

    /**
     * Compositing masks on the LIVE overlay (§C): the TextureView child clips
     * through the SAME MaskPathBuilder authority as the export, in parent
     * space (the child's translate/scale/rotate applies inside drawChild, so
     * the mask stays fixed over the content rect while the PiP moves — the
     * export-order parity). TextureView renders through the view-hierarchy
     * canvas (unlike SurfaceView), so canvas clipping genuinely applies.
     */
    @Override
    protected boolean drawChild(@NonNull android.graphics.Canvas canvas,
                                @NonNull android.view.View child, long drawingTime) {
        if (child == textureView && active != null && callback != null) {
            com.fadcam.ui.faditor.model.CompositingSpec cs = active.getCompositing();
            if (cs != null && cs.hasMasks()) {
                RectF r = callback.getVideoContentRect();
                if (r.width() > 0 && r.height() > 0) {
                    com.fadcam.ui.faditor.model.MaskPathBuilder.MaskScope ms =
                            com.fadcam.ui.faditor.model.MaskPathBuilder.beginMask(
                                    canvas, cs, r.width(), r.height(), r.left, r.top);
                    boolean res = super.drawChild(canvas, child, drawingTime);
                    com.fadcam.ui.faditor.model.MaskPathBuilder.endMask(
                            canvas, cs, r.width(), r.height(), r.left, r.top, ms);
                    return res;
                }
            }
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    /** Top-most visible overlay clip (list order = z, bottom→top; end-INCLUSIVE like sprites). */
    @Nullable
    private Clip topVisibleAt(long t) {
        for (int i = clips.size() - 1; i >= 0; i--) {
            Clip c = clips.get(i);
            long start = c.getOverlayStartMs();
            long end = start + Math.max(0, c.getTrimmedDurationMs());
            if (t >= start && t <= end) return c;
        }
        return null;
    }

    private void ensureActive(@NonNull Clip clip) {
        if (clip == active && player != null) return;
        if (player == null) {
            player = new ExoPlayer.Builder(getContext()).build();
            player.setVideoTextureView(textureView);
            player.setVolume(0f); // silent until a clip opts in — applyActiveVolume() below
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
            player.addListener(new Player.Listener() {
                @Override public void onVideoSizeChanged(@NonNull VideoSize size) {
                    if (size.width > 0 && size.height > 0) {
                        videoW = size.width;
                        videoH = size.height;
                        updateBaseLayout();
                        if (active != null) applyTransform(active);
                    }
                }

                @Override public void onPlayerError(@NonNull PlaybackException error) {
                    // Never black the editor over a PiP: drop the live overlay, keep master.
                    FLog.e(TAG, "overlay decoder error — hiding PiP for this session", error);
                    releasePlayer();
                }
            });
        }
        Uri uri = callback.resolveSeekable(clip);
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        active = clip;
        applyActiveVolume();
        videoW = 0;
        videoH = 0;
        FLog.i(TAG, "overlay decoder bound to clip layer=" + clip.getLayerId()
                + " start=" + clip.getOverlayStartMs() + "ms uri=" + uri.getLastPathSegment());
    }

    /**
     * Push the bound clip's effective volume onto the player. Called whenever the decoder is
     * (re)bound, and by {@link #refreshVolume()} when the host changes a mute/volume/lane
     * flag. Silent when nothing is bound or the host says 0 — the default for every PiP that
     * has not opted into audio, which reproduces this view's historical hardcoded silence.
     */
    private void applyActiveVolume() {
        if (player == null || active == null || callback == null) return;
        float v = callback.overlayVolumeFor(active);
        player.setVolume(Math.max(0f, v));
    }

    /** Host hook: re-read the bound PiP's volume after a mute/volume/lane-mute change. */
    public void refreshVolume() {
        applyActiveVolume();
    }

    // ── Transform ─────────────────────────────────────────────────────────────

    /** Full-fit box of the overlay video inside the content rect = scale 1.0 reference. */
    private void updateBaseLayout() {
        if (callback == null || videoW <= 0 || videoH <= 0) return;
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return;
        float fit = Math.min(r.width() / videoW, r.height() / videoH);
        int w = Math.max(1, Math.round(videoW * fit));
        int h = Math.max(1, Math.round(videoH * fit));
        if (w == baseW && h == baseH) return;
        baseW = w;
        baseH = h;
        LayoutParams lp = (LayoutParams) textureView.getLayoutParams();
        lp.width = w;
        lp.height = h;
        lp.gravity = Gravity.CENTER;
        textureView.setLayoutParams(lp);
    }

    private void applyTransform(@NonNull Clip clip) {
        if (callback == null) return;
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return;
        updateBaseLayout();
        float x = readValue(clip, KeyframeSet.X, DEFAULT_X);
        float y = readValue(clip, KeyframeSet.Y, DEFAULT_Y);
        float scale = readValue(clip, KeyframeSet.SCALE, DEFAULT_SCALE);
        float rot = readValue(clip, KeyframeSet.ROTATION, 0f);
        float alpha = Math.max(0f, Math.min(1f,
                readValue(clip, KeyframeSet.OPACITY, 1f)));
        // Base box is centered in this layer; content rect is centered too (resize_mode=fit),
        // so translation maps the normalized centre into content-rect pixels directly.
        textureView.setScaleX(scale);
        textureView.setScaleY(scale);
        textureView.setRotation(rot);
        textureView.setAlpha(alpha);
        textureView.setTranslationX((x - 0.5f) * r.width());
        textureView.setTranslationY((y - 0.5f) * r.height());
        // Export clips the PiP at the canvas; the preview must not show pixels
        // the export can't have (M-EXPORT-2 review note: preview-honesty clamp).
        setClipBounds(new android.graphics.Rect(
                Math.round(r.left), Math.round(r.top),
                Math.round(r.right), Math.round(r.bottom)));
    }

    private float readValue(@NonNull Clip clip, @NonNull String property, float fallback) {
        KeyframeSet kf = clip.getOverlayTransform();
        return kf == null ? fallback : kf.valueAt(property, currentTimeMs, fallback);
    }

    /** v1 static write: replace the whole track with one key at t=0. */
    private void putStatic(@NonNull Clip clip, @NonNull String property, float value) {
        KeyframeSet kf = clip.getOverlayTransform();
        if (kf == null) {
            kf = new KeyframeSet();
            clip.setOverlayTransform(kf);
        }
        com.fadcam.ui.faditor.keyframe.KeyframeTrack track = kf.getOrCreate(property);
        track.keyframes.clear();
        track.put(0L, value, Easing.LINEAR);
    }

    // ── Gestures (SpriteOverlayView contract) ─────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (callback == null) return false;
        if (manipulating != null) scaleDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                Clip hit = hitTest(e.getX(), e.getY());
                if (hit == null) return false; // pass through to the player
                manipulating = hit;
                KeyframeSet kf = hit.getOverlayTransform();
                beforeGesture = kf != null ? kf.copy() : new KeyframeSet();
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startX = readValue(hit, KeyframeSet.X, DEFAULT_X);
                startY = readValue(hit, KeyframeSet.Y, DEFAULT_Y);
                startScale = readValue(hit, KeyframeSet.SCALE, DEFAULT_SCALE);
                moved = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (manipulating == null) return false;
                if (scaleDetector.isInProgress()) { moved = true; return true; }
                RectF r = callback.getVideoContentRect();
                if (r.width() <= 0 || r.height() <= 0) return true;
                float dx = (e.getRawX() - downRawX) / r.width();
                float dy = (e.getRawY() - downRawY) / r.height();
                if (Math.abs(e.getRawX() - downRawX) > 8
                        || Math.abs(e.getRawY() - downRawY) > 8) {
                    moved = true;
                }
                putStatic(manipulating, KeyframeSet.X, clamp01(startX + dx));
                putStatic(manipulating, KeyframeSet.Y, clamp01(startY + dy));
                applyTransform(manipulating);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                Clip c = manipulating;
                KeyframeSet before = beforeGesture;
                manipulating = null;
                beforeGesture = null;
                if (c != null && moved) {
                    if (e.getActionMasked() == MotionEvent.ACTION_CANCEL && before != null) {
                        // Interrupted gesture reverts — never commits (fcc0bd5 rule).
                        KeyframeSet kf = c.getOverlayTransform();
                        if (kf != null) kf.copyFrom(before);
                        applyTransform(c);
                    } else {
                        if (before != null) callback.onOverlayVideoManipulated(c, before);
                        callback.onOverlayVideoChanged();
                    }
                }
                return c != null;
            }
        }
        return false;
    }

    /** Hit = inside the active PiP's current (unrotated) drawn bounds; only the live one is grabbable. */
    @Nullable
    private Clip hitTest(float x, float y) {
        Clip top = topVisibleAt(currentTimeMs);
        if (top == null || textureView.getVisibility() != VISIBLE || baseW <= 0) return null;
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return null;
        float cx = getWidth() / 2f + textureView.getTranslationX();
        float cy = getHeight() / 2f + textureView.getTranslationY();
        float scale = readValue(top, KeyframeSet.SCALE, DEFAULT_SCALE);
        float minHalf = 24f * getResources().getDisplayMetrics().density / 2f;
        float hw = Math.max(baseW * scale / 2f, minHalf);
        float hh = Math.max(baseH * scale / 2f, minHalf);
        if (x >= cx - hw && x <= cx + hw && y >= cy - hh && y <= cy + hh) return top;
        return null;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        releasePlayer();
        for (StillFrame sf : stills.values()) {
            if (sf.bitmap != null && !sf.bitmap.isRecycled()) sf.bitmap.recycle();
        }
        stills.clear();
        stillExecutor.shutdownNow();
    }
}
