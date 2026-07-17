package com.fadcam.ui.faditor.slides;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates {@link SlideRenderActivity}: launches it, blocks the calling
 * (background) thread until the activity reports a captured PNG sequence, and
 * returns the result.
 *
 * <p>Both sides live in the same process, so completion is handed back through a
 * small static registry keyed by a request id, signalled with a latch.</p>
 *
 * <p>Must be called off the main thread — it blocks.</p>
 */
public class SlideCaptureEngine {

    private static final String TAG = "SlideCaptureEngine";

    /**
     * Per-frame worst case for the timeout budget: the 250ms paint-fallback
     * race plus JS/PNG-write overhead. A hung WebView still can't wedge an
     * export forever — the cap just scales with how many frames were asked for
     * (a stretched 30s slide is 900 frames ≈ 6 minutes of legitimate work).
     */
    private static final long PER_FRAME_BUDGET_MS = 600L;
    private static final long MIN_TIMEOUT_MS = 60_000L;

    private static final ConcurrentHashMap<String, Pending> PENDING =
            new ConcurrentHashMap<>();

    private static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean ok;
        volatile String error;
        volatile int frameCount;
    }

    public static final class Result {
        public final boolean ok;
        @Nullable public final String error;
        public final int frameCount;
        @NonNull public final File outDir;

        Result(boolean ok, @Nullable String error, int frameCount, @NonNull File outDir) {
            this.ok = ok;
            this.error = error;
            this.frameCount = frameCount;
            this.outDir = outDir;
        }
    }

    /** Called by {@link SlideRenderActivity} when a capture finishes. */
    static void publishResult(@Nullable String requestId, boolean ok,
                              @Nullable String error, int frameCount) {
        if (requestId == null) return;
        Pending p = PENDING.get(requestId);
        if (p == null) return;
        p.ok = ok;
        p.error = error;
        p.frameCount = frameCount;
        p.latch.countDown();
    }

    /**
     * Render a slide HTML file to a PNG sequence in {@code outDir}, blocking
     * until done or timeout. Convenience overload with no stretch/freeze
     * mapping: the capture window equals the authored duration.
     *
     * @param htmlPath absolute path to the authored HTML, or null to render the
     *                 bundled sample slide (Phase 0).
     */
    @NonNull
    public Result capturePngSequence(@NonNull Context context, @Nullable String htmlPath,
                                     int width, int height, int fps, long durationMs,
                                     @NonNull File outDir) {
        return capturePngSequence(context, htmlPath, width, height, fps, durationMs,
                durationMs, 0, durationMs, outDir);
    }

    /**
     * Render with the stretch/freeze time mapping baked in: frames cover
     * {@code 0..totalMs} of clip source time, the animation plays (stretched)
     * across {@code animStartMs..animEndMs}, and the first/last authored frames
     * hold outside that window.
     */
    @NonNull
    public Result capturePngSequence(@NonNull Context context, @Nullable String htmlPath,
                                     int width, int height, int fps, long durationMs,
                                     long totalMs, long animStartMs, long animEndMs,
                                     @NonNull File outDir) {
        // Clear any stale frames so a short re-render can't leave old tail frames.
        clearDir(outDir);
        if (!outDir.exists() && !outDir.mkdirs()) {
            return new Result(false, "Could not create out dir", 0, outDir);
        }

        String requestId = UUID.randomUUID().toString();
        Pending pending = new Pending();
        PENDING.put(requestId, pending);

        try {
            Intent intent = new Intent(context, SlideRenderActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.putExtra(SlideRenderActivity.EXTRA_REQUEST_ID, requestId);
            intent.putExtra(SlideRenderActivity.EXTRA_OUT_DIR, outDir.getAbsolutePath());
            intent.putExtra(SlideRenderActivity.EXTRA_WIDTH, width);
            intent.putExtra(SlideRenderActivity.EXTRA_HEIGHT, height);
            intent.putExtra(SlideRenderActivity.EXTRA_FPS, fps);
            intent.putExtra(SlideRenderActivity.EXTRA_DURATION_MS, durationMs);
            intent.putExtra(SlideRenderActivity.EXTRA_TOTAL_MS, totalMs);
            intent.putExtra(SlideRenderActivity.EXTRA_ANIM_START_MS, animStartMs);
            intent.putExtra(SlideRenderActivity.EXTRA_ANIM_END_MS, animEndMs);
            if (htmlPath != null && !htmlPath.isEmpty()) {
                intent.putExtra(SlideRenderActivity.EXTRA_HTML_PATH, htmlPath);
            } else {
                intent.putExtra(SlideRenderActivity.EXTRA_USE_SAMPLE, true);
            }
            context.startActivity(intent);

            long frames = Math.max(1, Math.round((totalMs / 1000.0) * fps));
            long timeoutMs = Math.max(MIN_TIMEOUT_MS, frames * PER_FRAME_BUDGET_MS);
            boolean completed = pending.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                return new Result(false, "Capture timed out", 0, outDir);
            }
            return new Result(pending.ok, pending.error, pending.frameCount, outDir);
        } catch (Exception e) {
            FLog.e(TAG, "capturePngSequence failed", e);
            return new Result(false, e.getMessage(), 0, outDir);
        } finally {
            PENDING.remove(requestId);
        }
    }

    private static void clearDir(@NonNull File dir) {
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) f.delete();
        }
    }
}
