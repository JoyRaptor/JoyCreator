package com.fadcam.ui.faditor.compositor;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.fadcam.FLog;

import java.util.HashMap;
import java.util.Map;

/**
 * Answers one question: does this source position land exactly on a video KEY FRAME?
 *
 * <p>WHY (LEDGER §3d, cut smoothness). Every clipped playlist window is built with a
 * {@link android.media.MediaFormat}-level start that ExoPlayer cannot assume is a keyframe, so
 * {@code DefaultMediaSourceFactory} sets {@code setEnableInitialDiscontinuity(true)} and REBUILDS
 * the video renderer at every cut — measured at 250/330ms per cut, ~11% of total playback time.
 * Declaring {@code ClippingConfiguration.setStartsAtKeyFrame(true)} removes the teardown entirely
 * (playback went 0.887× → 0.999×), but it is an ASSERTION: claim it for a window that does not
 * start on a keyframe and the decoder starts mid-GOP, which is a corrupt first frame, not a
 * slow one. Trim precision is non-negotiable, so snapping trim points to keyframes is off the
 * table permanently — which leaves exactly one honest option: claim the flag only for the
 * windows that ALREADY happen to start on a keyframe, and eat the discontinuity on the rest.</p>
 *
 * <p>That makes "how many real cuts are keyframe-aligned?" the question that decides whether
 * this is worth having at all, so this class counts as it goes and can report the tally
 * ({@link #summary()}) rather than leaving the benefit to be assumed.</p>
 *
 * <p>Results are cached per (uri, positionMs) — a probe is a file open plus a seek, and the
 * playlist is rebuilt on every trim/structural edit, so the same handful of positions would
 * otherwise be re-probed constantly.</p>
 */
public final class KeyframeAlignment {

    private static final String TAG = "KeyframeAlignment";

    /**
     * How far the nearest sync sample may sit from the requested position and still count as
     * "starts at a keyframe". Deliberately TIGHT: the flag is an assertion the decoder acts on,
     * and the cost of a wrong yes (a corrupt opening frame) is far worse than the cost of a
     * wrong no (the renderer rebuild we have today). One millisecond covers the µs→ms rounding
     * in the clip model and nothing else.
     */
    private static final long TOLERANCE_US = 1_000L;

    /**
     * Wall-clock budget for probing during one playlist build. A probe is ~15ms on the Note 9
     * (open the extractor, seek, read one sample time) and {@code prepareTimeline} runs on the
     * main thread, so a 100-clip project would spend ~1.5s of it here on first open. Past the
     * budget the remaining windows answer "not aligned", which is exactly today's behaviour —
     * the feature degrades to off rather than stalling the editor. Results are cached, so a
     * later rebuild picks up where this one stopped.
     */
    private static final long PROBE_BUDGET_MS = 120L;

    private static final Map<String, Boolean> CACHE = new HashMap<>();
    private static int probesAligned = 0;
    private static int probesTotal = 0;
    private static int probesSkippedForBudget = 0;
    private static long budgetSpentMs = 0L;

    private KeyframeAlignment() {}

    /** Call once before building a playlist: reopens the per-build probe budget. */
    public static void beginBuild() {
        budgetSpentMs = 0L;
        probesSkippedForBudget = 0;
    }

    /**
     * @return true only if {@code positionMs} in {@code uri}'s video track is (within
     *         {@link #TOLERANCE_US}) a sync sample. Returns FALSE for anything it cannot
     *         determine — an unreadable source, no video track, an I/O failure — because the
     *         false answer is the safe one: it keeps today's behaviour.
     */
    public static boolean startsAtKeyFrame(@NonNull Context context, @NonNull Uri uri,
                                           long positionMs) {
        // Position 0 is a keyframe in every sane container, and it is by far the most common
        // window start (an untrimmed clip), so answer it without touching the disk.
        if (positionMs <= 0L) return true;
        String key = uri + "@" + positionMs;
        Boolean cached = CACHE.get(key);
        if (cached != null) return cached;
        if (budgetSpentMs >= PROBE_BUDGET_MS) {
            // NOT cached: this window was never actually asked, only skipped. Caching the
            // false would make the skip permanent for the whole session.
            probesSkippedForBudget++;
            return false;
        }
        long t0 = android.os.SystemClock.elapsedRealtime();
        boolean aligned = probe(context, uri, positionMs);
        budgetSpentMs += android.os.SystemClock.elapsedRealtime() - t0;
        CACHE.put(key, aligned);
        probesTotal++;
        if (aligned) probesAligned++;
        return aligned;
    }

    private static boolean probe(@NonNull Context context, @NonNull Uri uri, long positionMs) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            int videoTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat fmt = extractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    break;
                }
            }
            if (videoTrack < 0) return false;
            extractor.selectTrack(videoTrack);
            long wantUs = positionMs * 1000L;
            // CLOSEST_SYNC, not PREVIOUS_SYNC: a keyframe a hair AFTER the requested position
            // is just as aligned as one a hair before, and ms-rounded trim points land on
            // either side. PREVIOUS_SYNC would report the whole GOP before it and answer "no"
            // for a position that is in fact a keyframe.
            extractor.seekTo(wantUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            long gotUs = extractor.getSampleTime();
            if (gotUs < 0) return false;
            boolean aligned = Math.abs(gotUs - wantUs) <= TOLERANCE_US;
            // The DISTANCE matters as much as the yes/no: it is what says whether a miss was a
            // rounding accident or half a GOP away, and therefore whether this hybrid could ever
            // pay on real footage or only on untrimmed clips.
            FLog.d(TAG, "KFPROBE want=" + positionMs + "ms nearestSync=" + (gotUs / 1000.0)
                    + "ms delta=" + ((gotUs - wantUs) / 1000.0) + "ms aligned=" + aligned);
            return aligned;
        } catch (Exception e) {
            FLog.w(TAG, "keyframe probe failed for " + uri + "@" + positionMs, e);
            return false;
        } finally {
            try {
                extractor.release();
            } catch (Exception ignored) { /* nothing useful to do */ }
        }
    }

    /** Running tally of real, non-zero window starts probed this session, and how many were
     *  keyframe-aligned — i.e. how many cuts the hybrid actually buys anything for. */
    @NonNull
    public static String summary() {
        int pct = probesTotal == 0 ? 0 : (int) Math.round(100.0 * probesAligned / probesTotal);
        // The skipped count is reported, never silently dropped: "0% aligned" and "we ran out
        // of time before asking" are different answers and must not read the same.
        return "KFALIGN aligned=" + probesAligned + "/" + probesTotal + " (" + pct + "%)"
                + " skippedForBudget=" + probesSkippedForBudget
                + " probeMs=" + budgetSpentMs;
    }
}
