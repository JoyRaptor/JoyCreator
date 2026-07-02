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
     * The baked-file location for a key (may not exist yet). Filename embeds a hash of the full key
     * so different sources / trims never collide, plus the in/out ms for human-readable cache dirs.
     */
    @NonNull
    public File fileFor(@NonNull Uri sourceUri, long inPointMs, long outPointMs) {
        File cacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (!cacheDir.exists()) cacheDir.mkdirs();
        String key = keyFor(sourceUri, inPointMs, outPointMs);
        String hash = String.valueOf(Math.abs(key.hashCode() % 100000));
        return new File(cacheDir, "rev-" + inPointMs + "-" + outPointMs + "-" + hash + ".mp4");
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

        String cmd = buildCommand(inputFile.getAbsolutePath(), out.getAbsolutePath(),
                inPointMs, outPointMs);
        FLog.i(TAG, "Baking reversed segment [" + inPointMs + "," + outPointMs + "]ms -> "
                + out.getName());
        FLog.d(TAG, "ffmpeg: " + cmd);
        long t0 = System.currentTimeMillis();
        try {
            FFmpegSession session = FFmpegKit.execute(cmd);
            if (ReturnCode.isSuccess(session.getReturnCode())
                    && out.exists() && out.length() >= 1024) {
                FLog.i(TAG, "Reverse bake OK: " + out.getName() + " ("
                        + (out.length() / 1024) + " KB) in "
                        + (System.currentTimeMillis() - t0) + "ms");
                return out;
            }
            FLog.e(TAG, "Reverse bake FAILED rc=" + session.getReturnCode()
                    + " out=" + session.getOutput());
            if (out.exists()) out.delete();
            return null;
        } catch (Exception e) {
            FLog.e(TAG, "Reverse bake threw", e);
            if (out.exists()) out.delete();
            return null;
        }
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
        String cmd = buildCommand(inputFile.getAbsolutePath(), out.getAbsolutePath(),
                inPointMs, outPointMs);
        FLog.i(TAG, "Async baking reversed segment [" + inPointMs + "," + outPointMs + "]ms -> "
                + out.getName());
        final long t0 = System.currentTimeMillis();
        FFmpegKit.executeAsync(cmd, session -> {
            boolean ok = ReturnCode.isSuccess(session.getReturnCode())
                    && out.exists() && out.length() >= 1024;
            if (ok) {
                FLog.i(TAG, "Async reverse bake OK: " + out.getName() + " in "
                        + (System.currentTimeMillis() - t0) + "ms");
            } else {
                FLog.e(TAG, "Async reverse bake FAILED rc=" + session.getReturnCode());
                if (out.exists()) out.delete();
            }
            callback.onBakeComplete(ok, ok ? out : null, false);
        }, log -> { /* ffmpeg logs — muted to avoid spam */ }, stats -> { /* progress unused */ });
    }

    /**
     * Builds the reverse-bake ffmpeg command. Fast-seek {@code -ss}/{@code -to} go BEFORE {@code -i}
     * (decode only the trimmed span); {@code reverse}/{@code areverse} flip the buffered frames;
     * {@code setpts/asetpts} re-base timestamps to 0; {@code +faststart} makes the result seekable.
     *
     * <p>The video is re-encoded with the SOFTWARE {@code libx264} encoder (bundled in ffmpeg-kit
     * "full"), NOT the device's {@code h264_mediacodec} hardware encoder: on the Note 9 (and other
     * devices) the hardware MediaCodec encoder failed to configure for these HEVC 1080×1920 fMP4
     * sources ({@code MediaCodec configure failed, Error 0xffffffc3}). {@code -preset ultrafast}
     * (fast bake, yields a Constrained-Baseline H.264 stream = maximally compatible) + {@code
     * -pix_fmt yuv420p} + a keyframe every ~1s ({@code -g 30}) is portable and reliably seekable /
     * decodable by Media3 for both preview and export.</p>
     */
    @NonNull
    private String buildCommand(@NonNull String inputPath, @NonNull String outputPath,
                                long inPointMs, long outPointMs) {
        double inSec = inPointMs / 1000.0;
        double outSec = outPointMs / 1000.0;
        // -an would drop audio; we KEEP + areverse it (PLAN: reverse-leg audio comes areverse'd
        // from the bake). Software x264 avoids the flaky hardware encoder; aac re-encodes the
        // (already decoded + reversed) audio.
        return String.format(Locale.US,
                "-ss %.3f -to %.3f -i \"%s\" "
                        + "-vf reverse,setpts=PTS-STARTPTS "
                        + "-af areverse,asetpts=PTS-STARTPTS "
                        + "-c:v libx264 -preset ultrafast -crf 20 -pix_fmt yuv420p -g 30 "
                        + "-c:a aac -b:a 192k "
                        + "-movflags +faststart -y \"%s\"",
                inSec, outSec, inputPath, outputPath);
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
