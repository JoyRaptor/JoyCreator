package com.fadcam.ui.faditor.transcript;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.waveform.OnsetDetector;
import com.fadcam.ui.faditor.waveform.PcmSidecar;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Word-start positions for a source, computed once and held for the session — the magnet
 * behind Word Sync's drag (`SPEC_20260829_WORD_SYNC` §3.3).
 *
 * <p>This class is deliberately thin. The detection lives in {@link OnsetDetector}, which is
 * pure Java and pinned by {@code tools/jvm-harness/run-onset.sh} against synthetic signals
 * with known answers; the samples come from {@link PcmSidecar}, the same flat memory-mapped
 * bake the scrub engine plays. All this adds is: which source, computed when, and kept
 * where.</p>
 *
 * <p><b>Degradation contract:</b> no onsets means no snapping, never an error. A drag with
 * an empty onset list lands exactly where the finger left it, which is the behaviour the
 * editor had before this existed.</p>
 */
public final class WordSyncOnsets {

    private static final String TAG = "WordSyncOnsets";

    /**
     * One background thread. Detection is a single O(n) pass — roughly 9M samples for a
     * 7-minute song, well under a second — so a pool would only add contention. Low priority
     * because nothing waits on it: the first drag before it finishes simply does not snap.
     */
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wordsync-onsets");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static final Map<String, long[]> CACHE = new HashMap<>();
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WordSyncOnsets() {}

    /** Notified on the MAIN thread once a source's onsets are ready. */
    public interface Ready {
        void onOnsets(@NonNull Uri uri, @NonNull long[] onsetsMs);
    }

    /**
     * Onsets for {@code uri} if they are already computed, otherwise {@code null} — and, in
     * that case, computation starts.
     *
     * <p>Returning null rather than blocking is the whole design: this is called from a drag,
     * and a drag that stalls for a decode is worse than a drag that does not snap. The first
     * drag after opening a project may not magnet; every one after it will.</p>
     */
    @Nullable
    public static long[] get(@NonNull Context ctx, @NonNull Uri uri, @Nullable Ready ready) {
        String key = uri.toString();
        synchronized (CACHE) {
            long[] hit = CACHE.get(key);
            if (hit != null) return hit;
            if (IN_FLIGHT.contains(key)) return null;
            IN_FLIGHT.add(key);
        }
        final Context app = ctx.getApplicationContext();
        POOL.execute(() -> {
            long[] result = new long[0];
            try {
                PcmSidecar.Handle h = PcmSidecar.open(app, uri);
                if (h == null) {
                    // The bake has not finished (normal in the first seconds of a project) or
                    // could not be mapped. Do NOT cache the empty result - leaving it out
                    // means a later drag retries, once the sidecar exists.
                    FLog.d(TAG, "no sidecar yet for " + key + " - onsets deferred");
                    synchronized (CACHE) { IN_FLIGHT.remove(key); }
                    return;
                }
                long t0 = android.os.SystemClock.elapsedRealtime();
                result = OnsetDetector.detect(h, PcmSidecar.RATE);
                FLog.d(TAG, "onsets: " + result.length + " in "
                        + (android.os.SystemClock.elapsedRealtime() - t0) + "ms for " + key);
            } catch (Exception e) {
                FLog.w(TAG, "onset detection failed - no snapping for this source", e);
            }
            final long[] done = result;
            synchronized (CACHE) {
                CACHE.put(key, done);
                IN_FLIGHT.remove(key);
            }
            if (ready != null) MAIN.post(() -> ready.onOnsets(uri, done));
        });
        return null;
    }

    /**
     * Snap {@code ms} to the nearest word start, with a tolerance that follows the timeline's
     * zoom ({@link OnsetDetector#snapToleranceMs}). Returns {@code ms} unchanged when nothing
     * is near enough, when the onsets are not computed yet, or when snapping is off.
     *
     * @param msPerPixel the timeline's current scale — how much time one screen pixel covers
     */
    public static long snap(@NonNull Context ctx, @Nullable Uri uri, long ms,
                            double msPerPixel, boolean enabled) {
        if (!enabled || uri == null) return ms;
        long[] onsets = get(ctx, uri, null);
        if (onsets == null || onsets.length == 0) return ms;
        return OnsetDetector.snap(onsets, ms, OnsetDetector.snapToleranceMs(msPerPixel));
    }

    /**
     * Onsets within a visible window, for drawing the tick marks Word Sync §3.3 requires —
     * "a magnet you cannot see is indistinguishable from a bug".
     */
    @NonNull
    public static long[] inRange(@Nullable long[] onsets, long fromMs, long toMs) {
        if (onsets == null || onsets.length == 0 || toMs <= fromMs) return new long[0];
        int lo = 0, hi = onsets.length;
        while (lo < hi) {                       // first index >= fromMs
            int mid = (lo + hi) >>> 1;
            if (onsets[mid] < fromMs) lo = mid + 1; else hi = mid;
        }
        int start = lo;
        int end = start;
        while (end < onsets.length && onsets[end] <= toMs) end++;
        long[] out = new long[end - start];
        System.arraycopy(onsets, start, out, 0, out.length);
        return out;
    }

    /** Drop everything — call when a project closes so a long session does not accumulate. */
    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
            IN_FLIGHT.clear();
        }
    }
}
