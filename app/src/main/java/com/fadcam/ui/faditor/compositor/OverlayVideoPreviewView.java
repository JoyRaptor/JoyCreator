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

    // Gesture state (SpriteOverlayView contract).
    private final ScaleGestureDetector scaleDetector;
    @Nullable private Clip manipulating;
    @Nullable private KeyframeSet beforeGesture;
    private float downRawX, downRawY, startX, startY, startScale;
    private boolean moved;

    public OverlayVideoPreviewView(Context ctx) { this(ctx, null); }

    public OverlayVideoPreviewView(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
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
            player.setVolume(0f); // pixels only — matches export's setRemoveAudio (M-EXPORT-1)
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
        videoW = 0;
        videoH = 0;
        FLog.i(TAG, "overlay decoder bound to clip layer=" + clip.getLayerId()
                + " start=" + clip.getOverlayStartMs() + "ms uri=" + uri.getLastPathSegment());
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
    }
}
