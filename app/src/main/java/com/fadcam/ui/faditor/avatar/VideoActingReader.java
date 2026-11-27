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

import com.fadcam.ui.faditor.avatar.face.FaceStage;
import com.fadcam.ui.faditor.model.Clip;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Acting from a video (Avatar build, slice 2b): reads the face in one of the project's video layers, under a new-format
 * character item, into the face signals the character's take uses ({@code turnX}, {@code mouth}, {@code browL} ...),
 * through the same raw face ({@link MediaPipeTrackingSource#resultToRaw}) and the same {@link FaceStage} as the live
 * face camera, so a face read from a video moves a character exactly as the same face live would.
 *
 * <p>It samples {@value #STEP_MS} ms apart in the item's own time (lined up one to one with the video where the two sit
 * on the timeline, plus the nudge); {@code Acting.resample} puts the samples on the take's frames. The old rig's
 * point-at-video ({@link VideoFaceSweeper}) is left as it is.</p>
 */
public final class VideoActingReader {

    /** All on the main thread. */
    public interface Listener {
        void onProgress(int done, int total);

        /** {@code times} in item seconds; {@code signals} null where no face was seen. */
        void onDone(@NonNull double[] times, @NonNull List<Map<String, Float>> signals);

        void onCancelled();

        /** The video could not be read (the person sees one plain sentence; this is for the log). */
        void onFailed(@NonNull String why);
    }

    /** Where in the video's file each moment of the item is: -1 where the video does not show. */
    public interface TimeMap {
        long sourceMsAt(long itemLocalMs);
    }

    static final long STEP_MS = 66;   // about 15 a second, the live camera's rate
    private static final int DETECT_MAX_DIM = 320;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private ExecutorService executor;
    private volatile boolean cancelled;

    public VideoActingReader(@NonNull Context c) {
        app = c.getApplicationContext();
    }

    /** A spine clip's (or the whole main video's) map: the item's time is the timeline's from {@code itemStartMs}. */
    @NonNull
    public static TimeMap spine(@NonNull List<Clip> clips, long itemStartMs, long nudgeMs) {
        final List<Clip> cs = new ArrayList<>(clips);
        final long[] spans = new long[cs.size()];
        for (int i = 0; i < cs.size(); i++) {
            Clip c = cs.get(i);
            spans[i] = c.hasLoopExtension() ? c.getVisualDurationMs() : c.getTrimmedDurationMs();
        }
        return local -> {
            long t = itemStartMs + local + nudgeMs;
            int ci = SweepTimeMapper.clipIndexAt(spans, t);
            if (ci < 0 || cs.get(ci).isImageClip()) return -1;
            return cs.get(ci).mapToSourceMs(SweepTimeMapper.clipLocalMs(spans, ci, t));
        };
    }

    /** A picture-in-picture clip's map. */
    @NonNull
    public static TimeMap overlay(@NonNull Clip clip, long itemStartMs, long nudgeMs) {
        return local -> {
            long l = itemStartMs + local + nudgeMs - clip.getOverlayStartMs();
            if (l < 0 || l >= clip.getVisualDurationMs()) return -1;
            return clip.mapToSourceMs(l);
        };
    }

    /**
     * Reads {@code lengthMs} of item time. {@code sourceOf} says which file a source time is in (one file for a
     * picture-in-picture clip; the spine clip under that moment for the main video).
     */
    public void read(@NonNull java.util.function.LongFunction<Uri> sourceOf, @NonNull TimeMap map, long lengthMs,
                     @NonNull Listener l) {
        if (executor != null) {
            l.onFailed("already reading");
            return;
        }
        cancelled = false;
        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> run(sourceOf, map, lengthMs, l));
    }

    public void cancel() {
        cancelled = true;
    }

    private void run(java.util.function.LongFunction<Uri> sourceOf, TimeMap map, long lengthMs, Listener l) {
        FaceLandmarker lm = null;
        java.util.Map<Uri, MediaMetadataRetriever> open = new java.util.HashMap<>();
        try {
            lm = landmarker();
            if (lm == null) {
                main.post(() -> l.onFailed("face model did not load"));
                return;
            }
            FaceStage stage = new FaceStage();
            int total = (int) (lengthMs / STEP_MS) + 1;
            double[] times = new double[total];
            List<Map<String, Float>> out = new ArrayList<>(total);
            long stamp = -1;
            for (int i = 0; i < total; i++) {
                if (cancelled) {
                    main.post(l::onCancelled);
                    return;
                }
                if (i % 8 == 0) {
                    final int d = i;
                    main.post(() -> l.onProgress(d, total));
                }
                long local = i * STEP_MS;
                times[i] = local / 1000.0;
                Map<String, Float> signals = null;
                long src = map.sourceMsAt(local);
                Uri uri = src >= 0 ? sourceOf.apply(local) : null;
                Bitmap frame = uri == null ? null : frameAt(retriever(open, uri), src);
                if (frame != null) {
                    stamp = Math.max(stamp + 1, local);
                    try {
                        FaceLandmarkerResult r = lm.detectForVideo(new BitmapImageBuilder(frame).build(), stamp);
                        Map<String, Float> raw = MediaPipeTrackingSource.resultToRaw(r, 0);
                        signals = stage.process(raw, local / 1000.0);
                        if (raw == null) signals = null;   // the stage eases a lost face to rest; the take holds instead
                    } catch (RuntimeException e) {
                        signals = null;   // one bad frame never sinks the read
                    } finally {
                        frame.recycle();
                    }
                }
                out.add(signals);
            }
            main.post(() -> l.onDone(times, out));
        } catch (RuntimeException e) {
            main.post(() -> l.onFailed(String.valueOf(e.getMessage())));
        } finally {
            if (lm != null) lm.close();
            for (MediaMetadataRetriever m : open.values()) {
                if (m == null) continue;
                try {
                    m.release();
                } catch (Exception ignored) {
                }
            }
            ExecutorService ex = executor;
            executor = null;
            if (ex != null) ex.shutdown();
        }
    }

    @Nullable
    private FaceLandmarker landmarker() {
        try {
            BaseOptions base = BaseOptions.builder()
                    .setModelAssetPath(MediaPipeTrackingSource.MODEL_ASSET)
                    .setDelegate(Delegate.CPU)
                    .build();
            return FaceLandmarker.createFromOptions(app, FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(true)
                    .setOutputFacialTransformationMatrixes(true)
                    .build());
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private MediaMetadataRetriever retriever(java.util.Map<Uri, MediaMetadataRetriever> open, Uri uri) {
        if (open.containsKey(uri)) return open.get(uri);
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(app, uri);
        } catch (RuntimeException e) {
            try {
                m.release();
            } catch (Exception ignored) {
            }
            m = null;
        }
        open.put(uri, m);
        return m;
    }

    @Nullable
    private static Bitmap frameAt(@Nullable MediaMetadataRetriever m, long sourceMs) {
        if (m == null) return null;
        try {
            Bitmap b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                b = m.getScaledFrameAtTime(sourceMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, DETECT_MAX_DIM, DETECT_MAX_DIM);
            } else {
                b = m.getFrameAtTime(sourceMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
            }
            if (b == null || b.getConfig() == Bitmap.Config.ARGB_8888) return b;
            Bitmap c = b.copy(Bitmap.Config.ARGB_8888, false);
            b.recycle();
            return c;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
