package com.fadcam.ui.faditor.avatar;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Clip;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Point-at-video (PLAN_AVATAR_STUDIO A4-NEXT): give a placed avatar item its
 * performance from an EXISTING clip instead of a live take — sweep the master
 * timeline range under the item through FaceLandmarker in VIDEO mode and bake
 * the results into an {@link AvatarParamTrack}.
 *
 * <p>Doctrine guards (§MINED, binding):</p>
 * <ul>
 *   <li><b>One param vocabulary, one axis knob</b>: every swept frame maps
 *       through {@link MediaPipeTrackingSource#resultToParams} — the SAME
 *       static the live stream calls — so live tracking and offline sweeps can
 *       never drift on names or axis conventions (ae9dc61 prep).</li>
 *   <li><b>One time authority</b>: timeline→clip walk via {@link SweepTimeMapper}
 *       (mirrors {@code Timeline.segmentStartMs}), then clip-local visual ms →
 *       source ms via {@code Clip.mapToSourceMs} — the existing single authority
 *       thumbnails/seek/export already ride. Track stamps are ITEM-LOCAL ms.</li>
 *   <li><b>Hold-don't-fade</b>: a no-face frame adds NOTHING — the track's
 *       one-sided-hold lerp and the resolver do the holding, exactly as a live
 *       tracking loss does.</li>
 * </ul>
 *
 * <p>Mechanics: FaceLandmarker VIDEO mode wants MONOTONIC timestamps per
 * detector instance and {@code detectForVideo} is synchronous (no result
 * listener in this mode) — a FRESH landmarker is built per sweep and closed
 * after. Frames come from one {@link MediaMetadataRetriever} per source clip
 * (~12.5fps sampling; the track lerps between samples), downscaled to the same
 * ~320px class the live 320x240 feed uses. Everything runs on a private
 * background executor; all {@link Listener} calls land on the main thread.</p>
 */
public final class VideoFaceSweeper {

    /** All callbacks arrive on the main thread. */
    public interface Listener {
        void onProgress(int done, int total);
        /** Sweep ran to the end; the track may be EMPTY (no face ever seen). */
        void onComplete(@NonNull AvatarParamTrack track);
        void onCancelled();
        void onError(@NonNull String message);
    }

    /** Sample cadence (~12.5fps): plenty — the param track lerps between keys. */
    private static final long STEP_MS = 80;

    /** Downscale target for detection frames (matches the live feed's class). */
    private static final int DETECT_MAX_DIM = 320;

    private static final int PROGRESS_EVERY = 8;

    @NonNull private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private ExecutorService executor;
    private volatile boolean cancelled;

    public VideoFaceSweeper(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Sweep the master-timeline range {@code [timelineStartMs, timelineEndMs]}
     * (the placed item's window) and bake a track whose stamps are ITEM-LOCAL
     * ms ({@code t - timelineStartMs}). One sweep per instance; a second call
     * while one runs is refused via {@link Listener#onError}.
     *
     * @param clips the master timeline's clips, in timeline order (snapshot).
     */
    public void sweep(@NonNull List<Clip> clips, long timelineStartMs, long timelineEndMs,
                      @NonNull Listener listener) {
        if (executor != null) {
            listener.onError("A sweep is already running");
            return;
        }
        if (timelineEndMs <= timelineStartMs) {
            listener.onError("The item has no time range to sweep");
            return;
        }
        final List<Clip> clipsSnapshot = new ArrayList<>(clips);
        cancelled = false;
        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> run(clipsSnapshot, timelineStartMs, timelineEndMs, listener));
    }

    /** Ask the running sweep to stop; {@link Listener#onCancelled} follows. */
    public void cancel() {
        cancelled = true;
    }

    // ── Background body ───────────────────────────────────────────────────────

    private void run(@NonNull List<Clip> clips, long startMs, long endMs,
                     @NonNull Listener listener) {
        // On-timeline span per clip — the same per-clip term Timeline.segmentStartMs
        // uses (loop-extended → visual duration, else trimmed duration).
        long[] spans = new long[clips.size()];
        for (int i = 0; i < clips.size(); i++) {
            Clip c = clips.get(i);
            spans[i] = c.hasLoopExtension() ? c.getVisualDurationMs() : c.getTrimmedDurationMs();
        }

        FaceLandmarker landmarker = null;
        Map<Integer, MediaMetadataRetriever> retrievers = new HashMap<>();
        try {
            landmarker = buildVideoLandmarker();
            if (landmarker == null) {
                post(() -> listener.onError("Face model failed to load"));
                return;
            }

            AvatarParamTrack track = new AvatarParamTrack();
            int total = (int) ((endMs - startMs) / STEP_MS) + 1;
            int done = 0;
            long lastStamp = -1;

            for (long t = startMs; t <= endMs; t += STEP_MS, done++) {
                if (cancelled) {
                    post(listener::onCancelled);
                    return;
                }
                if (done % PROGRESS_EVERY == 0) {
                    final int d = done, tot = total;
                    post(() -> listener.onProgress(d, tot));
                }

                int ci = SweepTimeMapper.clipIndexAt(spans, t);
                if (ci < 0) continue;
                Clip clip = clips.get(ci);
                if (clip.isImageClip()) continue; // no video frames to track
                long sourceMs = clip.mapToSourceMs(SweepTimeMapper.clipLocalMs(spans, ci, t));
                if (sourceMs < 0) continue;

                MediaMetadataRetriever mmr = retrieverFor(retrievers, ci, clip.getSourceUri());
                if (mmr == null) continue;
                Bitmap frame = frameAt(mmr, sourceMs);
                if (frame == null) continue; // undecodable instant = tracking loss

                // VIDEO mode needs strictly increasing stamps; item-local time is
                // already monotonic over this loop, the guard covers step<1ms edits.
                long stamp = t - startMs;
                if (stamp <= lastStamp) stamp = lastStamp + 1;
                lastStamp = stamp;

                FaceLandmarkerResult result;
                try {
                    result = landmarker.detectForVideo(
                            new BitmapImageBuilder(frame).build(), stamp);
                } catch (RuntimeException e) {
                    continue; // one bad frame must not sink the sweep
                }
                Map<String, Float> params = MediaPipeTrackingSource.resultToParams(result);
                if (params != null) {
                    track.add(t - startMs, params); // no face → add NOTHING (hold)
                }
            }

            final AvatarParamTrack result = track;
            post(() -> listener.onComplete(result));
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            final String out = "Sweep failed: " + (msg == null ? e.getClass().getSimpleName() : msg);
            post(() -> listener.onError(out));
        } finally {
            if (landmarker != null) landmarker.close();
            for (MediaMetadataRetriever mmr : retrievers.values()) {
                try {
                    mmr.release();
                } catch (Exception ignored) {
                }
            }
            ExecutorService ex = executor;
            executor = null;
            if (ex != null) ex.shutdown();
        }
    }

    /** Fresh per sweep (monotonic-timestamp contract); null when the model/init fails. */
    @Nullable
    private FaceLandmarker buildVideoLandmarker() {
        try {
            BaseOptions base = BaseOptions.builder()
                    .setModelAssetPath(MediaPipeTrackingSource.MODEL_ASSET)
                    .setDelegate(Delegate.CPU) // Note 9 baseline, same as live
                    .build();
            FaceLandmarker.FaceLandmarkerOptions options =
                    FaceLandmarker.FaceLandmarkerOptions.builder()
                            .setBaseOptions(base)
                            .setRunningMode(RunningMode.VIDEO) // sync detectForVideo
                            .setNumFaces(1)
                            .setOutputFaceBlendshapes(true)
                            .setOutputFacialTransformationMatrixes(true)
                            .build();
            return FaceLandmarker.createFromOptions(appContext, options);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private MediaMetadataRetriever retrieverFor(
            @NonNull Map<Integer, MediaMetadataRetriever> cache, int clipIndex,
            @NonNull Uri sourceUri) {
        if (cache.containsKey(clipIndex)) return cache.get(clipIndex);
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(appContext, sourceUri);
        } catch (RuntimeException e) {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
            mmr = null;
        }
        // Cache the failure too so a missing source is probed once, not per frame.
        cache.put(clipIndex, mmr);
        return mmr;
    }

    /**
     * One detection frame at {@code sourceMs}, downscaled near
     * {@link #DETECT_MAX_DIM}. OPTION_CLOSEST (exact frame, not just the nearest
     * sync frame) — pose accuracy beats decode speed for a background bake.
     */
    @Nullable
    private static Bitmap frameAt(@NonNull MediaMetadataRetriever mmr, long sourceMs) {
        long us = sourceMs * 1000L;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                return toArgb8888(mmr.getScaledFrameAtTime(us,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        DETECT_MAX_DIM, DETECT_MAX_DIM));
            }
            Bitmap full = mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST);
            if (full == null) return null;
            int w = full.getWidth(), h = full.getHeight();
            int max = Math.max(w, h);
            if (max <= DETECT_MAX_DIM) return toArgb8888(full);
            float s = DETECT_MAX_DIM / (float) max;
            Bitmap scaled = Bitmap.createScaledBitmap(
                    full, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
            if (scaled != full) full.recycle();
            return toArgb8888(scaled);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** BitmapImageBuilder requires ARGB_8888; some devices hand back RGB_565. */
    @Nullable
    private static Bitmap toArgb8888(@Nullable Bitmap b) {
        if (b == null || b.getConfig() == Bitmap.Config.ARGB_8888) return b;
        Bitmap converted = b.copy(Bitmap.Config.ARGB_8888, false);
        b.recycle();
        return converted;
    }

    private void post(@NonNull Runnable r) {
        main.post(r);
    }
}
