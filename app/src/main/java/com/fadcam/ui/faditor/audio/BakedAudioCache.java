package com.fadcam.ui.faditor.audio;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Statistics;
import com.fadcam.FLog;

import java.io.File;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C2.E — the BAKED half of the audio effects split (SPEC_AUDIO_UX_V1 §6.2): effects that
 * cannot run per-sample in a live preview are rendered OFFLINE through ffmpeg into a cached
 * file the clip POINTS AT, always revertible because the original source is never modified
 * or deleted — the bake is an addition, never a replacement.
 *
 * <h3>Storage — NOT getCacheDir()</h3>
 * The {@code faditor_audio} cache-in-getCacheDir incident (road_map.md: "OS can wipe,
 * borderline P0") is the reason this class refuses the app cache. A baked file the OS
 * deletes would make the user's cleaned audio silently revert to noisy. Bakes live under
 * {@code <projectDir>/audio_baked/} — the same durable project directory as every other
 * asset. <b>The caller supplies {@code projectDir}</b> ({@code ProjectStorage.projectDir(id)});
 * this engine never touches ProjectStorage itself.
 *
 * <h3>Key</h3>
 * {@code (sourceUri, [startMs,endMs], chain)} → one stable filename ({@link #keyFor},
 * {@link #bakedFileFor}). Re-baking the same request is free, and re-opening a project does
 * not re-run ffmpeg — {@link #isCached} answers from the filesystem. Like
 * {@code ReversedSegmentCache}, the source's mtime is deliberately NOT part of the key: an
 * imported recording is immutable once imported, and a re-trim changes the key, not the
 * file. {@link #KEY_VERSION} folds the chain definition into the identity so any DSP change
 * invalidates prior artifacts instead of being mistaken for them.
 *
 * <h3>Chains</h3>
 * <ul>
 *   <li>{@link #CHAIN_DENOISE} — {@code afftdn} FFT denoise (single pass).</li>
 *   <li>{@link #CHAIN_LOUDNORM} — TWO-PASS EBU R128 loudnorm: pass 1 measures
 *       ({@code print_format=json}), pass 2 applies the measured values with
 *       {@code linear=true}. Targets I=-16 LUFS / TP=-1.5 dBTP / LRA=11 (§3.3's podcast
 *       destination). Two passes are why {@link ProgressListener} exists.</li>
 * </ul>
 *
 * <p><b>UNTESTED ASSUMPTION (flagged per lane instructions):</b> §6.2 assumes ffmpeg-kit's
 * bundled full build ships {@code afftdn} and {@code loudnorm} and that pass-1's JSON block
 * lands in the session LOGS (it is printed by the filter, not stdout). Both match ffmpeg
 * documentation but neither has been exercised on device; if either fails, the failure
 * surfaces as a logged rc + log tail, never as silent success.</p>
 *
 * <h3>Threading</h3>
 * Blocking work never happens implicitly: {@link #bakeSync} BLOCKS on ffmpeg and must be
 * called off the main thread; {@link #bakeAsync} runs on ffmpeg-kit's worker and fires BOTH
 * callbacks there — post to main before touching UI or players.
 */
public class BakedAudioCache {

    private static final String TAG = "BakedAudioCache";

    /** Sub-directory under the PROJECT dir holding bakes (durable, never app-cache). */
    public static final String BAKE_DIR = "audio_baked";

    /**
     * Chain-identity version tag. BUMP when a chain's filters/targets change so an old
     * artifact can never be served as if it were made by the new DSP (same reasoning as
     * ReversedSegmentCache.KEY_VERSION).
     */
    private static final String KEY_VERSION = "v1";

    /** Single-pass FFT denoise. The value is the filter name passed after {@code -af}. */
    public static final String CHAIN_DENOISE = "afftdn";

    /** Two-pass loudnorm (measure, then apply). */
    public static final String CHAIN_LOUDNORM = "loudnorm2";

    /** C3 — one-tap "Fix audio" chain: highpass → afftdn → acompressor → loudnorm (baked, §6.2). */
    public static final String CHAIN_FIX = "fix";

    /** loudnorm targets: §3.3's podcast destination. */
    private static final double TARGET_I = -16.0;
    private static final double TARGET_TP = -1.5;
    private static final double TARGET_LRA = 11.0;

    /** afftdn noise floor (dB). ffmpeg's default is -97; speech sits well above -25. */
    private static final double DENOISE_FLOOR_DB = -25.0;

    /**
     * Coarse pre-seek safety margin, seconds — fragmented-MP4 input seeks land on fragment
     * boundaries, so we seek BEFORE the target and finish exactly (TranscriptionEngine's
     * device-proven pattern).
     */
    private static final double SEEK_SAFETY_S = 5.0;

    /** A bake smaller than this is a truncated failure, not a result. */
    private static final long MIN_VALID_BAKE_BYTES = 1024;

    @NonNull
    private final Context context;

    public BakedAudioCache(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    // ── Requests ──────────────────────────────────────────────────────────

    /** One bake request: WHAT source, WHICH span, WITH which chain. */
    public static final class Request {
        @NonNull public final Uri sourceUri;
        /** Span within the SOURCE, ms. */
        public final long startMs;
        public final long endMs;
        /** {@link #CHAIN_DENOISE} or {@link #CHAIN_LOUDNORM}. */
        @NonNull public final String chain;

        public Request(@NonNull Uri sourceUri, long startMs, long endMs,
                       @NonNull String chain) {
            this.sourceUri = sourceUri;
            this.startMs = startMs;
            this.endMs = endMs;
            this.chain = chain;
            if (endMs <= startMs) {
                throw new IllegalArgumentException("empty range: " + startMs + ".." + endMs);
            }
            if (!CHAIN_DENOISE.equals(chain) && !CHAIN_LOUDNORM.equals(chain) && !CHAIN_FIX.equals(chain)) {
                throw new IllegalArgumentException("unknown chain: " + chain);
            }
        }
    }

    /** The bake handle: where the processed file lives, and what it came from. */
    public static final class Result {
        /** The ORIGINAL source uri — unchanged on disk, still the revert target. */
        @NonNull public final Uri originalSourceUri;
        /** The processed file inside {@code <projectDir>/audio_baked/}. */
        @NonNull public final File bakedFile;
        /** Stable cache key (also the baked filename stem). */
        @NonNull public final String key;
        /** Which chain produced this. */
        @NonNull public final String chain;

        Result(@NonNull Uri originalSourceUri, @NonNull File bakedFile,
               @NonNull String key, @NonNull String chain) {
            this.originalSourceUri = originalSourceUri;
            this.bakedFile = bakedFile;
            this.key = key;
            this.chain = chain;
        }
    }

    /** Bake-progress callback. Fires on the ffmpeg worker thread (async path). */
    public interface ProgressListener {
        /**
         * @param fraction 0..1 across the WHOLE bake (two-pass loudnorm spends its first
         *                 half measuring); -1 while indeterminate.
         */
        void onProgress(float fraction);
    }

    /** Completion callback for {@link #bakeAsync}. */
    public interface BakeCallback {
        /** @param error human-readable reason when {@code result == null}. */
        void onComplete(@Nullable Result result, @Nullable String error);
    }

    // ── Key / paths ───────────────────────────────────────────────────────

    /** Stable cache key: source identity + range + chain + chain version. */
    @NonNull
    public static String keyFor(@NonNull Uri sourceUri, long startMs, long endMs,
                                @NonNull String chain) {
        return sourceUri.toString() + "|" + startMs + "|" + endMs + "|" + chain
                + "|" + KEY_VERSION;
    }

    /** The bake location for a request (may not exist yet). Caller owns projectDir. */
    @NonNull
    public File bakedFileFor(@NonNull File projectDir, @NonNull Uri sourceUri,
                             long startMs, long endMs, @NonNull String chain) {
        return new File(bakeDir(projectDir),
                keyFor(sourceUri, startMs, endMs, chain) + ".m4a");
    }

    /**
     * Whether a VALID bake already exists for this request. A truncated/partial artifact is
     * deleted and reported missing rather than served.
     */
    public boolean isCached(@NonNull File projectDir, @NonNull Uri sourceUri,
                            long startMs, long endMs, @NonNull String chain) {
        File f = bakedFileFor(projectDir, sourceUri, startMs, endMs, chain);
        if (!f.exists()) return false;
        if (f.length() < MIN_VALID_BAKE_BYTES) {
            FLog.w(TAG, "Discarding undersized bake (" + f.length() + "B): " + f.getName());
            f.delete();
            return false;
        }
        return true;
    }

    @NonNull
    private static File bakeDir(@NonNull File projectDir) {
        File d = new File(projectDir, BAKE_DIR);
        if (!d.exists() && !d.mkdirs()) {
            FLog.e(TAG, "Failed to create bake dir: " + d);
        }
        return d;
    }

    // ── Bake ──────────────────────────────────────────────────────────────

    /**
     * Asynchronously bake (or reuse) the processed render of {@code [startMs,endMs]} of
     * {@code request.sourceUri} into the caller's project directory. Never blocks the
     * caller; both callbacks fire on ffmpeg-kit's worker thread.
     */
    public void bakeAsync(@NonNull File projectDir, @NonNull Request request,
                          @Nullable ProgressListener progress,
                          @NonNull BakeCallback callback) {
        File out = bakedFileFor(projectDir, request.sourceUri,
                request.startMs, request.endMs, request.chain);
        if (isCached(projectDir, request.sourceUri,
                request.startMs, request.endMs, request.chain)) {
            FLog.d(TAG, "Cache HIT: " + out.getName());
            callback.onComplete(result(request, out), null);
            return;
        }
        String input = resolveInput(request.sourceUri);
        if (input == null) {
            callback.onComplete(null, "source unreadable: " + request.sourceUri);
            return;
        }
        FLog.i(TAG, "Baking [" + request.startMs + "," + request.endMs + "]ms "
                + request.chain + " -> " + out.getName());
        File tmp = outTmp(out);
        long spanMs = request.endMs - request.startMs;
        if (CHAIN_LOUDNORM.equals(request.chain)) {
            StringBuilder logs = new StringBuilder();
            String measureCmd = extractionPrefix(input, request)
                    + " -af loudnorm=" + loudnormTargets() + ":print_format=json "
                    + NULL_OUTPUT;
            FLog.d(TAG, "async loudnorm pass1: ffmpeg " + measureCmd);
            FFmpegKit.executeAsync(measureCmd, s1 -> {
                if (!ReturnCode.isSuccess(s1.getReturnCode())) {
                    fail(callback, tmp, "loudnorm measure pass failed rc="
                            + s1.getReturnCode());
                    return;
                }
                Measured m = parseMeasured(logs.toString());
                if (m == null) {
                    fail(callback, tmp,
                            "loudnorm pass1 produced no measurable JSON block");
                    return;
                }
                String applyCmd = extractionPrefix(input, request)
                        + " -af loudnorm=" + m.asApplyParams() + " "
                        + encodeOutput(tmp.getAbsolutePath());
                FLog.d(TAG, "async loudnorm pass2: ffmpeg " + applyCmd);
                runPassAsync(request, out, tmp, applyCmd, callback, 0.5f, spanMs);
            }, log -> collect(logs, log.getMessage()),
                    stats -> report(progress, stats, 0f, 0.5f, spanMs));
        } else if (CHAIN_FIX.equals(request.chain)) {
            String cmd = extractionPrefix(input, request)
                    + " -af \"highpass=f=80,afftdn=nf=" + fmt(DENOISE_FLOOR_DB) + ",acompressor=threshold=-18dB:ratio=3:attack=20:release=250,loudnorm=I=-16:TP=-1.5:LRA=11\" "
                    + encodeOutput(tmp.getAbsolutePath());
            runPassAsync(request, out, tmp, cmd, callback, 0f, spanMs);
        } else {
            String cmd = extractionPrefix(input, request)
                    + " -af afftdn=nf=" + fmt(DENOISE_FLOOR_DB) + " "
                    + encodeOutput(tmp.getAbsolutePath());
            runPassAsync(request, out, tmp, cmd, callback, 0f, spanMs);
        }
    }

    /**
     * Synchronous twin of {@link #bakeAsync} — BLOCKS on ffmpeg, call OFF the main thread.
     *
     * @param error optional 1-element array receiving the failure reason.
     * @return the bake handle, or null on failure.
     */
    @Nullable
    public Result bakeSync(@NonNull File projectDir, @NonNull Request request,
                           @Nullable ProgressListener progress,
                           @Nullable String[] error) {
        File out = bakedFileFor(projectDir, request.sourceUri,
                request.startMs, request.endMs, request.chain);
        if (isCached(projectDir, request.sourceUri,
                request.startMs, request.endMs, request.chain)) {
            FLog.d(TAG, "Cache HIT (sync): " + out.getName());
            return result(request, out);
        }
        String input = resolveInput(request.sourceUri);
        if (input == null) {
            return failSync(error, "source unreadable: " + request.sourceUri);
        }
        File tmp = outTmp(out);
        long spanMs = request.endMs - request.startMs;
        FFmpegSession session;
        if (CHAIN_LOUDNORM.equals(request.chain)) {
            StringBuilder logs = new StringBuilder();
            String measureCmd = extractionPrefix(input, request)
                    + " -af loudnorm=" + loudnormTargets() + ":print_format=json "
                    + NULL_OUTPUT;
            FLog.d(TAG, "loudnorm pass1: ffmpeg " + measureCmd);
            // FIX 2026-08-23 (review): FFmpegKit has no execute(cmd, logCb, statsCb)
            // overload — the working call in TranscriptionEngine:517 is the 1-arg
            // synchronous form. Logs are read off the finished session instead. This
            // path is bakeSync (blocking), so per-frame progress had no UI to drive.
            session = FFmpegKit.execute(measureCmd);
            collect(logs, session.getAllLogsAsString());
            if (!ReturnCode.isSuccess(session.getReturnCode())) {
                tmp.delete();
                return failSync(error, "loudnorm measure pass failed rc="
                        + session.getReturnCode() + "; tail: " + tail(logs.toString()));
            }
            Measured m = parseMeasured(logs.toString());
            if (m == null) {
                return failSync(error, "loudnorm pass1 produced no measurable JSON block");
            }
            String applyCmd = extractionPrefix(input, request)
                    + " -af loudnorm=" + m.asApplyParams() + " "
                    + encodeOutput(tmp.getAbsolutePath());
            FLog.d(TAG, "loudnorm pass2: ffmpeg " + applyCmd);
            session = FFmpegKit.execute(applyCmd);
        } else if (CHAIN_FIX.equals(request.chain)) {
            String cmd = extractionPrefix(input, request)
                    + " -af \"highpass=f=80,afftdn=nf=" + fmt(DENOISE_FLOOR_DB) + ",acompressor=threshold=-18dB:ratio=3:attack=20:release=250,loudnorm=I=-16:TP=-1.5:LRA=11\" "
                    + encodeOutput(tmp.getAbsolutePath());
            session = FFmpegKit.execute(cmd);
        } else {
            String cmd = extractionPrefix(input, request)
                    + " -af afftdn=nf=" + fmt(DENOISE_FLOOR_DB) + " "
                    + encodeOutput(tmp.getAbsolutePath());
            session = FFmpegKit.execute(cmd);
        }
        boolean ok = ReturnCode.isSuccess(session.getReturnCode())
                && tmp.exists() && tmp.length() >= MIN_VALID_BAKE_BYTES
                && commit(tmp, out);
        if (ok) {
            FLog.i(TAG, "Bake OK (sync): " + out.getName()
                    + " (" + out.length() / 1024 + " KB)");
            return result(request, out);
        }
        tmp.delete();
        return failSync(error, "bake failed rc=" + session.getReturnCode()
                + "; tail: " + tail(session.getAllLogsAsString()));
    }

    // ── internals ─────────────────────────────────────────────────────────

    private void runPassAsync(@NonNull Request request, @NonNull File out, @NonNull File tmp,
                              @NonNull String cmd, @NonNull BakeCallback callback,
                              float progressBase, long spanMs) {
        FFmpegKit.executeAsync(cmd, session -> {
            if (ReturnCode.isSuccess(session.getReturnCode())
                    && tmp.exists() && tmp.length() >= MIN_VALID_BAKE_BYTES
                    && commit(tmp, out)) {
                FLog.i(TAG, "Bake OK: " + out.getName() + " (" + out.length() / 1024 + " KB)");
                callback.onComplete(result(request, out), null);
            } else {
                fail(callback, tmp, "bake failed rc=" + session.getReturnCode()
                        + "; tail: " + tail(session.getAllLogsAsString()));
            }
        }, log -> { /* muted — the loudnorm measure pass collects its own logs */ },
                stats -> report(null, stats, progressBase,
                        progressBase > 0.49f ? 0.5f : 1f, spanMs));
    }

    @NonNull
    private static Result result(@NonNull Request request, @NonNull File out) {
        return new Result(request.sourceUri, out,
                keyFor(request.sourceUri, request.startMs, request.endMs, request.chain),
                request.chain);
    }

    private void fail(@NonNull BakeCallback cb, @NonNull File tmp, @NonNull String msg) {
        tmp.delete();
        FLog.e(TAG, msg);
        cb.onComplete(null, msg);
    }

    @Nullable
    private static Result failSync(@Nullable String[] error, @NonNull String msg) {
        FLog.e(TAG, msg);
        if (error != null && error.length > 0) error[0] = msg;
        return null;
    }

    /**
     * The device-proven EXACT-extraction prefix (TranscriptionEngine): coarse INPUT seek to
     * a safe margin before the target, then an exact OUTPUT seek for the remainder, then a
     * hard duration. Audio-only ({@code -vn}); re-encoded AAC at the tail via the output
     * spec, so sample rate/container follow the encoder defaults until A6 sets policy.
     */
    @NonNull
    private static String extractionPrefix(@NonNull String input, @NonNull Request request) {
        double target = request.startMs / 1000.0;
        double coarse = Math.max(0.0, target - SEEK_SAFETY_S);
        double fine = target - coarse;
        double durSec = Math.max(0.1, (request.endMs - request.startMs) / 1000.0);
        return String.format(Locale.US, "-y -ss %.3f -i \"%s\" -ss %.3f -t %.3f -vn",
                coarse, input, fine, durSec);
    }

    @NonNull
    private static String encodeOutput(@NonNull String path) {
        return "-c:a aac -b:a 192k \"" + path + "\"";
    }

    /** Pass-1 measurement invocation of loudnorm (targets only, no measured_* params). */
    @NonNull
    private static String loudnormTargets() {
        return "I=" + fmt(TARGET_I) + ":TP=" + fmt(TARGET_TP) + ":LRA=" + fmt(TARGET_LRA);
    }

    /** Input path for ffmpeg: direct file path when possible, else a SAF read parameter. */
    @Nullable
    private String resolveInput(@NonNull Uri sourceUri) {
        if ("file".equals(sourceUri.getScheme())) {
            String path = sourceUri.getPath();
            if (path != null && new File(path).canRead()) return path;
        }
        try {
            return FFmpegKitConfig.getSafParameterForRead(context, sourceUri);
        } catch (Exception e) {
            FLog.w(TAG, "SAF parameter failed for " + sourceUri + ": " + e.getMessage());
            return null;
        }
    }

    /** Temp sibling of the final bake — committed by rename only on success. */
    @NonNull
    private static File outTmp(@NonNull File out) {
        return new File(out.getParentFile(), out.getName() + ".tmp");
    }

    /** Atomic commit: rename the validated temp over the final name. */
    private static boolean commit(@NonNull File tmp, @NonNull File out) {
        if (out.exists()) out.delete();
        return tmp.renameTo(out);
    }

    /**
     * Map one pass's statistics clock into whole-bake progress: the pass covers
     * {@code weight} starting at {@code base}.
     */
    private static void report(@Nullable ProgressListener l, @Nullable Statistics stats,
                               float base, float weight, long spanMs) {
        if (l == null || stats == null) return;
        float passFraction = spanMs <= 0 ? -1f
                : Math.max(0f, Math.min(1f, (float) (stats.getTime() / spanMs)));
        l.onProgress(passFraction < 0 ? -1f
                : Math.min(1f, base + passFraction * weight));
    }

    private static void collect(@NonNull StringBuilder logs, @Nullable String line) {
        if (line != null) logs.append(line).append('\n');
    }

    @NonNull
    private static String tail(@Nullable String s) {
        if (s == null) return "(none)";
        s = s.trim();
        return s.length() <= 400 ? s : s.substring(s.length() - 400);
    }

    @NonNull
    private static String fmt(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    // ── loudnorm measurement parsing ──────────────────────────────────────

    // "-?inf": real pass-1 logs emit UNSIGNED "inf" for target_offset on digital silence
    // (caught by the C2.E behaviour proof, 2026-08-23 — the strict -inf-only pattern made
    // every fully-silent clip fail its bake).
    private static final Pattern P_INPUT_I =
            Pattern.compile("\"input_i\"\\s*:\\s*\"?(-?[\\d.]+|-?inf|nan)\"?");
    private static final Pattern P_INPUT_TP =
            Pattern.compile("\"input_tp\"\\s*:\\s*\"?(-?[\\d.]+|-?inf|nan)\"?");
    private static final Pattern P_INPUT_LRA =
            Pattern.compile("\"input_lra\"\\s*:\\s*\"?(-?[\\d.]+|-?inf|nan)\"?");
    private static final Pattern P_INPUT_THRESH =
            Pattern.compile("\"input_thresh\"\\s*:\\s*\"?(-?[\\d.]+|-?inf|nan)\"?");
    private static final Pattern P_TARGET_OFFSET =
            Pattern.compile("\"target_offset\"\\s*:\\s*\"?(-?[\\d.]+|-?inf|nan)\"?");

    /** The five values pass 2 needs, parsed from pass 1's {@code print_format=json} block. */
    private static final class Measured {
        final String inputI, inputTp, inputLra, inputThresh, targetOffset;
        Measured(String i, String tp, String lra, String thresh, String offset) {
            inputI = i; inputTp = tp; inputLra = lra; inputThresh = thresh; targetOffset = offset;
        }
        /** Pass-2 parameter string: measured values + linear mode (gain-only correction). */
        @NonNull
        String asApplyParams() {
            return "measured_I=" + inputI + ":measured_TP=" + inputTp
                    + ":measured_LRA=" + inputLra + ":measured_thresh=" + inputThresh
                    + ":offset=" + targetOffset
                    + ":linear=true:" + loudnormTargets();
        }
    }

    /**
     * Values kept as STRINGS on purpose: loudnorm emits {@code "-inf"} for silence, which
     * has no finite double form and must round-trip verbatim into pass 2.
     */
    @Nullable
    private static Measured parseMeasured(@NonNull String logs) {
        String ii = find(P_INPUT_I, logs);
        String tp = find(P_INPUT_TP, logs);
        String lra = find(P_INPUT_LRA, logs);
        String th = find(P_INPUT_THRESH, logs);
        String off = find(P_TARGET_OFFSET, logs);
        if (ii == null || tp == null || lra == null || th == null || off == null) return null;
        return new Measured(ii, tp, lra, th, off);
    }

    @Nullable
    private static String find(@NonNull Pattern p, @NonNull String logs) {
        Matcher m = p.matcher(logs);
        return m.find() ? m.group(1) : null;
    }

    // ── Revert ────────────────────────────────────────────────────────────

    /**
     * REVERT: delete the bake; the caller repoints the clip at
     * {@link Result#originalSourceUri}. Constraint 3 is STRUCTURAL here: this class never
     * opens the original source for write, so revert restores the original exactly because
     * the original was never touched. Returns false only if a baked file existed but
     * refused to delete (caller keeps pointing at it rather than losing audio).
     */
    public static boolean discardBake(@NonNull File bakedFile) {
        if (!bakedFile.exists()) return true;
        boolean gone = bakedFile.delete();
        if (!gone) FLog.w(TAG, "Refused to disappear: " + bakedFile.getAbsolutePath());
        return gone;
    }

    /** Convenience overload resolving the bake from its parts. */
    public boolean discardBake(@NonNull File projectDir, @NonNull Uri sourceUri,
                               long startMs, long endMs, @NonNull String chain) {
        return discardBake(bakedFileFor(projectDir, sourceUri, startMs, endMs, chain));
    }

    /** ffmpeg null-output sentinel (pass 1 writes measurements, not media). */
    private static final String NULL_OUTPUT = "-f null -";
}
