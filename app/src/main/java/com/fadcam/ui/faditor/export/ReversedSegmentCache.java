package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.fadcam.FLog;

import java.io.File;
import java.util.Locale;

/**
 * L2 — bakes and caches a TRULY reversed copy of a clip's trimmed source sub-range so that
 * PING_PONG loop legs can play a real reversed segment (preview AND export point at the SAME baked
 * file → preview==export by construction). Mirrors {@link com.fadcam.playback.FragmentedMp4Remuxer}'s
 * cache/lifecycle/off-main-warm patterns.
 *
 * <h3>Key</h3>
 * A baked file is identified by {@code sourceUri + inPointMs + outPointMs} (see {@link #keyFor}).
 * Speed is NOT part of the key — it is applied at PLAY time (the reverse leg's ExoPlayer window /
 * export item plays the baked span at the clip's speed), so a speed change does not invalidate the
 * bake. A TRIM change (different in/out) yields a different key → a cache miss → a re-bake; the stale
 * file simply ages out of the cache dir (see {@link #cleanupCache}).
 *
 * <h3>Bake command</h3>
 * Fast-seek {@code -ss <in> -to <out>} placed BEFORE {@code -i} (so ffmpeg only decodes the trimmed
 * span), then {@code -vf reverse,setpts=PTS-STARTPTS -af areverse,asetpts=PTS-STARTPTS}. The video
 * {@code reverse} filter buffers the WHOLE decoded segment in memory, so we GUARD against long spans
 * ({@link #MAX_REVERSE_SPAN_MS}): a segment longer than that is NOT baked — callers fall back to the
 * legacy forward-tail and show a one-time toast. Output is written {@code +faststart} so the reversed
 * file is itself seekable for preview windowing.
 *
 * <h3>Reversed-file coordinates</h3>
 * The baked file spans the source range {@code [inPointMs, outPointMs]} played BACKWARD, so its own
 * timeline runs {@code 0 .. (outPointMs - inPointMs)} where baked-file time {@code t} corresponds to
 * source time {@code outPointMs - t} (baked-time 0 = source outPoint, i.e. the LAST forward frame).
 * Callers map a forward-leg source range {@code [startMs, endMs]} into the baked file with
 * {@code revStart = outPointMs - endMs}, {@code revEnd = outPointMs - startMs} (see
 * {@code ExportManager.buildLoopExtensionItem} and {@code MasterPlaybackEngine.addLoopReps}).
 *
 * <h3>Threading</h3>
 * {@link #bakeSync} blocks (ffmpeg) and MUST be called off the main thread — the caller warms the
 * cache on a background executor (mirroring {@code ExportService}'s remux warm), then rebuilds the
 * preview playlist / starts export back on the main thread. {@link #bakeAsync} is provided for the
 * drawer-driven "user just set PING_PONG" path.
 */
public class ReversedSegmentCache {

    private static final String TAG = "ReversedSegCache";

    /** Sub-directory under the app cache dir holding baked reversed segments. */
    private static final String CACHE_DIR = "reversed";

    /**
     * Max trimmed-span length (ms) we will bake. The ffmpeg {@code reverse} filter holds the whole
     * decoded segment in RAM, so long spans risk OOM — above this, callers keep the forward-tail
     * fallback + a one-time toast. ~30s per PLAN_LOOP_PINGPONG.md L2.
     */
    public static final long MAX_REVERSE_SPAN_MS = 30_000L;

    @NonNull
    private final Context context;

    public ReversedSegmentCache(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    // ── Key / paths ──────────────────────────────────────────────────────

    /** Stable cache key for a source URI + trimmed sub-range (speed excluded — applied at play time). */
    @NonNull
    public static String keyFor(@NonNull Uri sourceUri, long inPointMs, long outPointMs) {
        return sourceUri.toString() + "|" + inPointMs + "|" + outPointMs;
    }

    /**
     * Cache-key version tag. BUMP THIS whenever the bake command / codec changes so old artifacts
     * from a previous codec (e.g. the pre-unpark ~40Mbps L4.0-violating libx264 files) can NEVER be
     * mistaken for a current bake — a stale file would silently break preview==export parity. v2 =
     * the single-codec HEVC-first chain (hevc_mediacodec → hardened libx264 fallback).
     */
    private static final String KEY_VERSION = "v2";

    /**
     * The baked-file location for a key (may not exist yet). Filename embeds a hash of the full key
     * so different sources / trims never collide, plus the in/out ms for human-readable cache dirs.
     * The {@link #KEY_VERSION} tag is folded into the hash AND the filename so a codec/command change
     * invalidates every prior artifact.
     */
    @NonNull
    public File fileFor(@NonNull Uri sourceUri, long inPointMs, long outPointMs) {
        File cacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (!cacheDir.exists()) cacheDir.mkdirs();
        String key = keyFor(sourceUri, inPointMs, outPointMs) + "|" + KEY_VERSION;
        String hash = String.valueOf(Math.abs(key.hashCode() % 100000));
        return new File(cacheDir, "rev-" + KEY_VERSION + "-" + inPointMs + "-" + outPointMs
                + "-" + hash + ".mp4");
    }

    /** Whether the trimmed span is short enough to bake (see {@link #MAX_REVERSE_SPAN_MS}). */
    public static boolean canBake(long inPointMs, long outPointMs) {
        long span = outPointMs - inPointMs;
        return span > 0 && span <= MAX_REVERSE_SPAN_MS;
    }

    /**
     * Whether a VALID baked reversed file already exists for this key. Validates existence + a
     * sane size (a truncated/failed bake is deleted and reported missing). Note: unlike the remux
     * cache we do NOT compare against the source file's mtime here — the key already pins the exact
     * in/out points, and the source (a recording) is immutable once imported; a re-trim changes the
     * key, not this file.
     */
    public boolean isCached(@NonNull Uri sourceUri, long inPointMs, long outPointMs) {
        File f = fileFor(sourceUri, inPointMs, outPointMs);
        if (!f.exists()) return false;
        if (f.length() < 1024) { // implausibly small → failed/partial bake
            f.delete();
            return false;
        }
        return true;
    }

    // ── Bake ──────────────────────────────────────────────────────────────

    /**
     * Synchronously bake (or reuse) the reversed segment for {@code [inPointMs, outPointMs]} of
     * {@code inputFile}. BLOCKS on ffmpeg — call OFF the main thread.
     *
     * @param sourceUri  the clip's ORIGINAL source URI (used for the cache key — must be stable
     *                   across the preview/export split, NOT the remuxed temp path)
     * @param inputFile  the actual on-disk file ffmpeg reads (may be a remuxed/faststart copy of a
     *                   raw fMP4 so fast-seek is accurate); if null, bake fails and returns null
     * @return the baked reversed file, or null if the span is un-bakeable (too long / bad range),
     *         the input is missing, or ffmpeg failed. A null return means "fall back to forward-tail".
     */
    @Nullable
    public File bakeSync(@NonNull Uri sourceUri, @Nullable File inputFile,
                         long inPointMs, long outPointMs) {
        if (!canBake(inPointMs, outPointMs)) {
            FLog.d(TAG, "Span not bakeable (span=" + (outPointMs - inPointMs)
                    + "ms > " + MAX_REVERSE_SPAN_MS + "ms guard or empty): "
                    + sourceUri.getLastPathSegment());
            return null;
        }
        File out = fileFor(sourceUri, inPointMs, outPointMs);
        if (isCached(sourceUri, inPointMs, outPointMs)) {
            FLog.d(TAG, "Reversed segment cache HIT: " + out.getName());
            return out;
        }
        if (inputFile == null || !inputFile.exists() || !inputFile.canRead()) {
            FLog.w(TAG, "Cannot bake reverse — input file missing/unreadable for "
                    + sourceUri);
            return null;
        }
        if (out.exists()) out.delete();

        FLog.i(TAG, "Baking reversed segment [" + inPointMs + "," + outPointMs + "]ms -> "
                + out.getName());
        String[] attempts = buildCommandChain(inputFile.getAbsolutePath(),
                out.getAbsolutePath(), inPointMs, outPointMs);
        long t0 = System.currentTimeMillis();
        for (int attempt = 0; attempt < attempts.length; attempt++) {
            String cmd = attempts[attempt];
            if (out.exists()) out.delete();
            FLog.d(TAG, "Reverse bake attempt " + (attempt + 1) + "/" + attempts.length
                    + " (" + ATTEMPT_NAMES[attempt] + "): ffmpeg " + cmd);
            try {
                FFmpegSession session = FFmpegKit.execute(cmd);
                if (ReturnCode.isSuccess(session.getReturnCode())
                        && out.exists() && out.length() >= 1024) {
                    FLog.i(TAG, "Reverse bake OK via attempt " + (attempt + 1) + " ("
                            + ATTEMPT_NAMES[attempt] + "): " + out.getName() + " ("
                            + (out.length() / 1024) + " KB) in "
                            + (System.currentTimeMillis() - t0) + "ms");
                    return out;
                }
                FLog.w(TAG, "Reverse bake attempt " + (attempt + 1) + " ("
                        + ATTEMPT_NAMES[attempt] + ") FAILED rc=" + session.getReturnCode()
                        + " — " + (attempt + 1 < attempts.length ? "trying next codec" : "no more attempts")
                        + "; ffmpeg tail: " + tail(session.getOutput()));
            } catch (Exception e) {
                FLog.w(TAG, "Reverse bake attempt " + (attempt + 1) + " ("
                        + ATTEMPT_NAMES[attempt] + ") threw: " + e.getMessage());
            }
        }
        FLog.e(TAG, "Reverse bake FAILED after " + attempts.length + " attempts for " + out.getName());
        if (out.exists()) out.delete();
        return null;
    }

    /**
     * Asynchronously bake the reversed segment (used by the drawer's "user set PING_PONG" path so
     * the UI thread never blocks). The callback fires on ffmpeg-kit's worker thread — post back to
     * main before touching UI/player.
     */
    public void bakeAsync(@NonNull Uri sourceUri, @Nullable File inputFile,
                          long inPointMs, long outPointMs, @NonNull BakeCallback callback) {
        if (!canBake(inPointMs, outPointMs)) {
            callback.onBakeComplete(false, null, /* guardedTooLong = */
                    (outPointMs - inPointMs) > MAX_REVERSE_SPAN_MS);
            return;
        }
        File out = fileFor(sourceUri, inPointMs, outPointMs);
        if (isCached(sourceUri, inPointMs, outPointMs)) {
            callback.onBakeComplete(true, out, false);
            return;
        }
        if (inputFile == null || !inputFile.exists() || !inputFile.canRead()) {
            callback.onBakeComplete(false, null, false);
            return;
        }
        if (out.exists()) out.delete();
        final String[] attempts = buildCommandChain(inputFile.getAbsolutePath(),
                out.getAbsolutePath(), inPointMs, outPointMs);
        FLog.i(TAG, "Async baking reversed segment [" + inPointMs + "," + outPointMs + "]ms -> "
                + out.getName());
        runAsyncAttempt(attempts, 0, out, System.currentTimeMillis(), callback);
    }

    /**
     * Run one attempt of the async bake chain; on ReturnCode failure, recurse to the next attempt.
     * When all attempts are exhausted, report failure (caller falls back to the forward-tail).
     */
    private void runAsyncAttempt(@NonNull String[] attempts, int attempt, @NonNull File out,
                                 long t0, @NonNull BakeCallback callback) {
        if (attempt >= attempts.length) {
            FLog.e(TAG, "Async reverse bake FAILED after " + attempts.length + " attempts: "
                    + out.getName());
            if (out.exists()) out.delete();
            callback.onBakeComplete(false, null, false);
            return;
        }
        if (out.exists()) out.delete();
        final int attemptIdx = attempt;
        FLog.d(TAG, "Async reverse bake attempt " + (attemptIdx + 1) + "/" + attempts.length
                + " (" + ATTEMPT_NAMES[attemptIdx] + ")");
        FFmpegKit.executeAsync(attempts[attemptIdx], session -> {
            boolean ok = ReturnCode.isSuccess(session.getReturnCode())
                    && out.exists() && out.length() >= 1024;
            if (ok) {
                FLog.i(TAG, "Async reverse bake OK via attempt " + (attemptIdx + 1) + " ("
                        + ATTEMPT_NAMES[attemptIdx] + "): " + out.getName() + " in "
                        + (System.currentTimeMillis() - t0) + "ms");
                callback.onBakeComplete(true, out, false);
            } else {
                FLog.w(TAG, "Async reverse bake attempt " + (attemptIdx + 1) + " ("
                        + ATTEMPT_NAMES[attemptIdx] + ") FAILED rc=" + session.getReturnCode()
                        + (attemptIdx + 1 < attempts.length ? " — trying next codec" : ""));
                runAsyncAttempt(attempts, attemptIdx + 1, out, t0, callback);
            }
        }, log -> { /* ffmpeg logs — muted to avoid spam */ }, stats -> { /* progress unused */ });
    }

    /** Human-readable names for each attempt in {@link #buildCommandChain}, for logging. */
    private static final String[] ATTEMPT_NAMES = {
            "hevc_mediacodec/nv12", "hevc_mediacodec/yuv420p", "libx264-hardened"
    };

    /**
     * Builds the ReturnCode-checked reverse-bake command CHAIN (attempted in order until one
     * succeeds). Rank-2 single-codec strategy: bake the reversed leg as HEVC so the preview playlist
     * stays hvc1 end-to-end (no mid-playlist HEVC→AVC decoder swap on the shared player's surface —
     * the leading blackout trigger), keeping ONE cache file shared by preview and export.
     *
     * <p>Common to every attempt: fast-seek {@code -ss}/{@code -to} BEFORE {@code -i} (decode only
     * the trimmed span); {@code reverse}/{@code areverse} flip the buffered frames; {@code
     * setpts/asetpts} re-base timestamps to 0; audio re-encoded AAC 192k; {@code +faststart} makes
     * the result seekable for preview windowing. Audio path is UNCHANGED from the pre-unpark bake.</p>
     *
     * <ol>
     *   <li><b>hevc_mediacodec / nv12</b> — HW HEVC encode, explicit {@code -b:v 10M}, {@code -g 30},
     *       {@code -tag:v hvc1}. NO {@code -profile}/{@code -level} flags (the suspected 0xffffffc3
     *       configure trigger in the AVC sibling). nv12 is the MediaCodec-native input layout.</li>
     *   <li><b>hevc_mediacodec / yuv420p</b> — same, but {@code -pix_fmt yuv420p} in case the encoder
     *       rejects nv12 for these sources.</li>
     *   <li><b>hardened libx264</b> — the in-code fallback (hevc_mediacodec is binary-present but
     *       device-UNPROVEN, and its AVC sibling failed configure). Pins the stream INSIDE H.264
     *       Level 4.0: {@code -preset veryfast -crf 23 -profile:v high -level 4.0 -maxrate 12M
     *       -bufsize 24M -pix_fmt yuv420p -g 30 -fps_mode passthrough}. This fixes the pre-unpark
     *       ~40Mbps/L4.0-violating, VFR-collapsing command that could render silent-black on the
     *       Note-9-class AVC decoder.</li>
     * </ol>
     */
    @NonNull
    private String[] buildCommandChain(@NonNull String inputPath, @NonNull String outputPath,
                                       long inPointMs, long outPointMs) {
        double inSec = inPointMs / 1000.0;
        double outSec = outPointMs / 1000.0;
        String seekIn = String.format(Locale.US,
                "-ss %.3f -to %.3f -i \"%s\" "
                        + "-vf reverse,setpts=PTS-STARTPTS "
                        + "-af areverse,asetpts=PTS-STARTPTS ", inSec, outSec, inputPath);
        String audioTail = "-c:a aac -b:a 192k -movflags +faststart -y \"" + outputPath + "\"";

        String hevcNv12 = seekIn
                + "-c:v hevc_mediacodec -pix_fmt nv12 -b:v 10M -g 30 -tag:v hvc1 "
                + audioTail;
        String hevcYuv = seekIn
                + "-c:v hevc_mediacodec -pix_fmt yuv420p -b:v 10M -g 30 -tag:v hvc1 "
                + audioTail;
        String libx264 = seekIn
                + "-c:v libx264 -preset veryfast -crf 23 -profile:v high -level 4.0 "
                + "-maxrate 12M -bufsize 24M -pix_fmt yuv420p -g 30 -fps_mode passthrough "
                + audioTail;
        return new String[]{ hevcNv12, hevcYuv, libx264 };
    }

    /** Last ~400 chars of an ffmpeg session log (for failure diagnostics without spamming). */
    @NonNull
    private static String tail(@Nullable String s) {
        if (s == null) return "(none)";
        s = s.trim();
        return s.length() <= 400 ? s : s.substring(s.length() - 400);
    }

    // ── Housekeeping ──────────────────────────────────────────────────────

    /** Delete baked reversed files older than {@code maxAgeDays} to bound cache growth. */
    public void cleanupCache(int maxAgeDays) {
        File cacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (!cacheDir.exists()) return;
        long maxAgeMs = maxAgeDays * 24L * 60 * 60 * 1000;
        long now = System.currentTimeMillis();
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        int deleted = 0;
        for (File f : files) {
            if (now - f.lastModified() > maxAgeMs && f.delete()) deleted++;
        }
        if (deleted > 0) FLog.i(TAG, "Cleaned up " + deleted + " cached reversed segments");
    }

    /** Callback for {@link #bakeAsync}. */
    public interface BakeCallback {
        /**
         * @param success        whether a valid baked file is now available
         * @param bakedFile      the reversed file (non-null iff success)
         * @param guardedTooLong true when the bake was SKIPPED because the span exceeds
         *                       {@link #MAX_REVERSE_SPAN_MS} (caller shows the one-time toast)
         */
        void onBakeComplete(boolean success, @Nullable File bakedFile, boolean guardedTooLong);
    }
}
