package com.fadcam.ui.faditor.export;

import com.fadcam.FLog;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FIX-2026-09-22: padded-window pre-trim cache ("seek insurance").
 *
 * <p>A 48-minute export with 20 windows into one 2.2 GB file stalls at a seam on a hot
 * phone: every item boundary re-opens the big file and seeks minutes deep, and that
 * quiet period trips the muxer watchdog. A pre-trim cuts each window to its own small
 * flat MP4 (stream copy — same samples, zero quality change), so every boundary becomes
 * "open a ~150 MB file and seek seconds" instead of "seek a 2.2 GB file on a throttled
 * phone". Bake from the REMUXED copy (flat index → fast input seek), fall back to the
 * raw file when no remux applies.
 *
 * <p>CONTAINMENT (why this can't shift the timeline): the trim keeps ORIGINAL
 * timestamps (plain {@code -c copy}, no timestamp rewrite) and is padded 10 s BEFORE
 * the window start, so the file is a strict byte-subset superset of the window —
 * every sample in [in, out] is present at its original timestamp. The composition
 * keeps clipping [in, out] exactly as today; nothing upstream changes. A trim that
 * fails validation (or is missing) falls back to the resolved URI — today's behavior.
 *
 * <p>Lookup-only from the export path ({@code resolveSeekableSourceUri} never bakes);
 * baking happens in ExportService's warm phase. Home is persistent
 * {@code files/faditor/pretrim/} (same lesson as the remux move: cache dirs evaporate).
 */
public class PreTrimCache {
    private static final String TAG = "PreTrimCache";

    /** Pad before the window start so the trim opens on a sync sample (ms). */
    private static final long PRETRIM_PAD_MS = 10_000L;
    /** Coverage tolerance for the validation (GOP rounding, ms). */
    private static final long COVERAGE_TOLERANCE_MS = 2_000L;

    private final Context context;
    /** trim path → validated-coverge-ms, so buildComposition's per-clip lookups stay cheap. */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> coverageCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public PreTrimCache(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Persistent home for pre-trimmed windows. */
    public File preTrimDir() {
        File dir = new File(context.getFilesDir(), "faditor/pretrim");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        sweepV1Once();
        return dir;
    }

    /**
     * 2026-09-22 v2: the first generation baked WITHOUT {@code -copyts}, so ffmpeg
     * rebased every window's timestamps to zero while the composition clips in ABSOLUTE
     * source time — every window with padStart &gt; 0 sought wrong (mostly past EOF →
     * empty items → video ending at 1:42 with full-length audio). v2 keeps source
     * timestamps; the "-v2" token orphans v1 files for the sweep below.
     */
    private static String keyFor(File inputFile, long inMs, long outMs) {
        String name = inputFile.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        base = base.replaceAll("[^A-Za-z0-9_-]", "_");
        if (base.length() > 40) base = base.substring(0, 40);
        long hash = Math.abs((long) (inputFile.getAbsolutePath() + "|"
                + inputFile.length() + "|" + inputFile.lastModified()).hashCode());
        return base + "-" + inMs + "-" + outMs + "-" + (hash % 100000) + "-v2.mp4";
    }

    private static final java.util.concurrent.atomic.AtomicBoolean v1Swept =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** One-time sweep of v1 (zero-based-timestamp) trims; they read as valid but lie. */
    private void sweepV1Once() {
        if (!v1Swept.compareAndSet(false, true)) return;
        try {
            File[] files = preTrimDir().listFiles();
            if (files == null) return;
            int n = 0;
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".mp4")
                        && !f.getName().contains("-v2")
                        && !f.getName().endsWith(".part.mp4")) {
                    if (f.delete()) n++;
                }
            }
            if (n > 0) FLog.i(TAG, "Swept " + n + " stale v1 pre-trim(s)");
        } catch (Exception e) {
            FLog.w(TAG, "v1 sweep failed", e);
        }
    }

    /** Path of the pre-trim for this window (may not exist yet). */
    @NonNull
    public File getPreTrimFile(@NonNull File inputFile, long inMs, long outMs) {
        return new File(preTrimDir(), keyFor(inputFile, inMs, outMs));
    }

    private File getPreTrimTempFile(@NonNull File inputFile, long inMs, long outMs) {
        File fin = getPreTrimFile(inputFile, inMs, outMs);
        return new File(fin.getParentFile(), fin.getName().replace(".mp4", ".part.mp4"));
    }

    /**
     * Valid pre-trim on disk for this exact window: exists, newer than the input it was
     * cut from, leading moov, and video coverage reaching (nearly) the window end.
     */
    public boolean hasPreTrim(@NonNull File inputFile, long inMs, long outMs) {
        File trim = getPreTrimFile(inputFile, inMs, outMs);
        if (!trim.exists()) return false;
        if (trim.lastModified() < inputFile.lastModified()) {
            trim.delete();
            return false;
        }
        long padStart = Math.max(0L, inMs - PRETRIM_PAD_MS);
        long needMs = outMs - padStart - COVERAGE_TOLERANCE_MS;
        Long cached = coverageCache.get(trim.getAbsolutePath());
        if (cached != null) return cached >= needMs;
        long covered = videoDurationMs(trim);
        if (covered <= 0) {
            trim.delete();
            return false;
        }
        coverageCache.put(trim.getAbsolutePath(), covered);
        if (covered < needMs) {
            FLog.w(TAG, "Pre-trim too short (" + covered + "ms < " + needMs + "ms): "
                    + trim.getName());
            trim.delete();
            return false;
        }
        return true;
    }

    /** Video-track duration of a file via extractor (no decode), -1 on failure. */
    public static long probeVideoDurationMs(@NonNull File f) {
        return videoDurationMs(f);
    }

    private static long videoDurationMs(@NonNull File f) {
        android.media.MediaExtractor ex = null;
        try {
            ex = new android.media.MediaExtractor();
            ex.setDataSource(f.getAbsolutePath());
            for (int t = 0; t < ex.getTrackCount(); t++) {
                android.media.MediaFormat fmt = ex.getTrackFormat(t);
                String mime = fmt.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/") && fmt.containsKey(
                        android.media.MediaFormat.KEY_DURATION)) {
                    return fmt.getLong(android.media.MediaFormat.KEY_DURATION) / 1000L;
                }
            }
        } catch (Exception e) {
            FLog.w(TAG, "videoDurationMs failed for " + f.getName(), e);
        } finally {
            if (ex != null) {
                try { ex.release(); } catch (Exception ignored) {}
            }
        }
        return -1L;
    }

    /**
     * Cut the window (padded) to its own file. Lookup-first: returns the existing valid
     * trim without running ffmpeg. Temp-then-rename, mirroring the remuxer contract.
     *
     * @return the valid trim, or null (caller falls back to the resolved URI).
     */
    @Nullable
    public File bakeSync(@NonNull File inputFile, long inMs, long outMs) {
        if (outMs <= inMs) return null;
        if (hasPreTrim(inputFile, inMs, outMs)) {
            return getPreTrimFile(inputFile, inMs, outMs);
        }
        File outputFile = getPreTrimFile(inputFile, inMs, outMs);
        File tempFile = getPreTrimTempFile(inputFile, inMs, outMs);
        if (outputFile.exists()) outputFile.delete();
        if (tempFile.exists()) tempFile.delete();

        long padStartMs = Math.max(0L, inMs - PRETRIM_PAD_MS);
        double ss = padStartMs / 1000.0;
        double dur = (outMs - padStartMs) / 1000.0;
        // -copyts is LOAD-BEARING (2026-09-22): without it ffmpeg rebases the window to
        // zero while the composition clips in absolute source time — every padded window
        // then seeks wrong (past EOF → empty items → video ending early with full audio).
        String cmd = String.format(Locale.US,
                "-copyts -ss %.3f -i \"%s\" -t %.3f -c copy -movflags +faststart -y \"%s\"",
                ss, inputFile.getAbsolutePath(), dur, tempFile.getAbsolutePath());
        FLog.i(TAG, "Pre-trimming window " + inMs + ".." + outMs + " of " + inputFile.getName());
        try {
            FFmpegSession session = FFmpegKit.execute(cmd);
            coverageCache.remove(outputFile.getAbsolutePath());
            if (ReturnCode.isSuccess(session.getReturnCode())
                    && tempFile.renameTo(outputFile)
                    && hasPreTrim(inputFile, inMs, outMs)) {
                FLog.i(TAG, "Pre-trim ok: " + outputFile.getName()
                        + " (" + outputFile.length() / 1024 + " KB)");
                return outputFile;
            }
            FLog.w(TAG, "Pre-trim failed for window " + inMs + ".." + outMs
                    + ": " + session.getReturnCode());
            if (tempFile.exists()) tempFile.delete();
            return null;
        } catch (Exception e) {
            FLog.w(TAG, "Pre-trim threw for window " + inMs + ".." + outMs, e);
            if (tempFile.exists()) tempFile.delete();
            return null;
        }
    }

    /**
     * Prune stale pre-trims (same policy shape as the remux prune): .part older than a
     * day, finals untouched for {@code maxAgeDays}, then oldest-first over the cap.
     */
    public int prunePreTrims(int maxAgeDays, long maxTotalBytes) {
        File dir = preTrimDir();
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return 0;
        long now = System.currentTimeMillis();
        long partTtlMs = 24L * 60 * 60 * 1000;
        long maxAgeMs = maxAgeDays * 24L * 60 * 60 * 1000;
        int deleted = 0;
        List<File> finals = new ArrayList<>();
        for (File f : files) {
            if (!f.isFile()) continue;
            if (f.getName().endsWith(".part.mp4")) {
                if (now - f.lastModified() > partTtlMs && f.delete()) deleted++;
                continue;
            }
            if (now - f.lastModified() > maxAgeMs) {
                if (f.delete()) deleted++;
            } else {
                finals.add(f);
            }
        }
        if (maxTotalBytes > 0) {
            long total = 0;
            for (File f : finals) total += f.length();
            if (total > maxTotalBytes) {
                finals.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (File f : finals) {
                    if (total <= maxTotalBytes) break;
                    long len = f.length();
                    if (f.delete()) {
                        deleted++;
                        total -= len;
                    }
                }
            }
        }
        if (deleted > 0) FLog.i(TAG, "Pruned " + deleted + " stale pre-trim(s)");
        return deleted;
    }
}
