package com.fadcam.ui.faditor.audio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.fadcam.FLog;

import java.io.File;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C4 — Loudness measurement via {@code ffmpeg -af ebur128} (ffmpeg-kit-full 6.0 LTS).
 *
 * <p>Uses the bundled full build's {@code ebur128} filter (verified via
 * {@code ffmpeg -h filter=ebur128} on 7.0.2: same options as 6.0 LTS).
 * The filter is invoked as {@code ebur128=framelog=verbose} — other
 * EBU R128 scanners use {@code framelog=info} but verbose gives the
 * per-frame integrated loudness needed for the summary.</p>
 *
 * <p>Output is parsed from the session logs, not stdout. The filter prints:
 * <pre>
 *   Integrated loudness:
 *     I:         -14.0 LUFS
 *     Threshold: -34.0 LUFS
 *     LRA:        3.5 LU
 *     ...
 *   Loudness range:
 *     ...
 * </pre>
 * We parse the integrated loudness via {@code I:\s+([-\d.]+)\s+LUFS}.</p>
 *
 * <p>A pure analysis pass on a single file — no cache, no project dir needed.</p>
 */
public class LoudnessAnalyzer {

    private static final String TAG = "LoudnessAnalyzer";

    // ebur128 summary: "I:         -14.0 LUFS" — allow leading spaces, handle -inf
    private static final Pattern P_INTEGRATED = Pattern.compile("I:\\s+(-?[\\d.]+|-?inf)\\s+LUFS");
    private static final Pattern P_LRA = Pattern.compile("LRA:\\s+([\\d.]+)\\s+LU");
    private static final Pattern P_TP = Pattern.compile("Threshold:\\s+(-?[\\d.]+)\\s+LUFS");

    /** Result of a loudness measurement. */
    public static final class Result {
        /** Integrated loudness in LUFS (e.g., -14.0). */
        public final double integratedLUFS;
        /** Loudness range in LU (may be NaN if not found). */
        public final double lra;
        /** True peak or threshold (may be NaN). */
        public final double threshold;

        Result(double integrated, double lra, double threshold) {
            this.integratedLUFS = integrated;
            this.lra = lra;
            this.threshold = threshold;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%.1f LUFS (LRA %.1f LU)", integratedLUFS, lra);
        }
    }

    /**
     * Measure integrated loudness of {@code inputFile} via ebur128.
     * BLOCKS on ffmpeg, call off the main thread.
     *
     * @param inputFile audio/video file to measure
     * @return result or null on failure (logs contain tail)
     */
    @Nullable
    public static Result measure(@NonNull File inputFile) {
        if (!inputFile.canRead()) {
            FLog.w(TAG, "measure: unreadable " + inputFile);
            return null;
        }
        String inputPath = inputFile.getAbsolutePath();
        // Verified against ffmpeg-kit-full 6.0 LTS API: ebur128 takes framelog, peak, dualmono, target, etc.
        // Use framelog=verbose to get integrated loudness in logs, and -f null - to discard output.
        // Do NOT use loudnorm's I/TP/LRA here — this is measurement only, not correction.
        String cmd = String.format(Locale.US, "-y -i \"%s\" -filter:a ebur128=framelog=verbose -f null -", inputPath);
        FLog.d(TAG, "ebur128 measure: ffmpeg " + cmd);
        FFmpegSession session = FFmpegKit.execute(cmd);
        String logs = session.getAllLogsAsString();
        if (!ReturnCode.isSuccess(session.getReturnCode())) {
            FLog.w(TAG, "ebur128 failed rc=" + session.getReturnCode() + " tail: " + tail(logs));
            return null;
        }
        Result r = parse(logs);
        if (r == null) {
            FLog.w(TAG, "ebur128 parse failed, logs tail: " + tail(logs));
        } else {
            FLog.d(TAG, "ebur128 measured: " + r);
        }
        return r;
    }

    /**
     * Measure loudness of a file at {@code uri} via SAF-safe path (like BakedAudioCache).
     * For now, only file: scheme is supported for loudness (export output is always a file).
     */
    @Nullable
    public static Result measure(@NonNull android.content.Context ctx, @NonNull android.net.Uri uri) {
        String path = null;
        if ("file".equals(uri.getScheme())) {
            path = uri.getPath();
        }
        if (path != null) {
            File f = new File(path);
            if (f.canRead()) return measure(f);
        }
        // Fallback: try SAF parameter (not yet needed for export output, but handle)
        try {
            String saf = com.arthenica.ffmpegkit.FFmpegKitConfig.getSafParameterForRead(ctx, uri);
            if (saf != null) {
                String cmd = String.format(Locale.US, "-y -i %s -filter:a ebur128=framelog=verbose -f null -", saf);
                FLog.d(TAG, "ebur128 measure SAF: ffmpeg " + cmd);
                FFmpegSession session = FFmpegKit.execute(cmd);
                String logs = session.getAllLogsAsString();
                if (!ReturnCode.isSuccess(session.getReturnCode())) {
                    FLog.w(TAG, "ebur128 SAF failed rc=" + session.getReturnCode());
                    return null;
                }
                return parse(logs);
            }
        } catch (Exception e) {
            FLog.w(TAG, "measure SAF failed", e);
        }
        return null;
    }

    @Nullable
    private static Result parse(@NonNull String logs) {
        Matcher mI = P_INTEGRATED.matcher(logs);
        // ebur128 prints multiple I: lines (per-frame and summary). The LAST one is the integrated summary.
        String lastI = null;
        while (mI.find()) lastI = mI.group(1);
        if (lastI == null || "inf".equals(lastI) || "-inf".equals(lastI)) return null;
        double integrated;
        try {
            integrated = Double.parseDouble(lastI);
        } catch (NumberFormatException e) {
            return null;
        }
        double lra = Double.NaN;
        double thresh = Double.NaN;
        Matcher mLra = P_LRA.matcher(logs);
        if (mLra.find()) {
            try { lra = Double.parseDouble(mLra.group(1)); } catch (Exception ignored) {}
        }
        Matcher mTp = P_TP.matcher(logs);
        if (mTp.find()) {
            try { thresh = Double.parseDouble(mTp.group(1)); } catch (Exception ignored) {}
        }
        return new Result(integrated, lra, thresh);
    }

    // ── C4/C8 — two-pass loudnorm correction ─────────────────────────────

    /** Clean-Audio (C8) chain prefix: denoise + dynamics, mirroring BakedAudioCache.CHAIN_FIX
     *  (duplicated because that class is outside this lane's file list). */
    private static final String CLEAN_CHAIN_PREFIX =
            "highpass=f=80,afftdn=nf=-25.0,acompressor=threshold=-18dB:ratio=3:attack=20:release=250,";

    private static final double TARGET_TP = -1.5;
    private static final double TARGET_LRA = 11.0;

    // Same patterns as BakedAudioCache.parseMeasured (private there; this lane may not edit it).
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

    /**
     * Two-pass loudnorm of {@code inFile} written to {@code outFile} (must differ).
     *
     * <p>Pass 1 measures with {@code loudnorm=print_format=json} ({@code -vn}: audio only,
     * no video decode); pass 2 re-encodes ONLY the audio ({@code -c:v copy} — same
     * stream-copy pattern as FragmentedMp4Remuxer) applying the measured values in
     * {@code linear=true} gain-only mode. When {@code cleanChain} is set, the Clean-Audio
     * (C8) denoise/dynamics prefix runs ahead of loudnorm.</p>
     *
     * <p>BLOCKS on ffmpeg (twice). Call OFF the main thread. All filter/output args were
     * checked against real ffmpeg's {@code -h filter=loudnorm} (7.0.2): every option used
     * here exists (I, TP, LRA, measured_I/TP/LRA/thresh, offset, linear, print_format).</p>
     *
     * @return true when the file was written successfully.
     */
    public static boolean normalize(@NonNull File inFile, @NonNull File outFile,
                                    double targetLUFS, boolean cleanChain) {
        if (!inFile.canRead()) {
            FLog.w(TAG, "normalize: unreadable input " + inFile);
            return false;
        }
        String in = inFile.getAbsolutePath();
        // Pass 1 — measure. -vn so a video track costs nothing; audio stream 0 only.
        String measureCmd = String.format(Locale.US,
                "-y -i \"%s\" -vn -af loudnorm=I=%.1f:TP=%.1f:LRA=%.1f:print_format=json -f null -",
                in, targetLUFS, TARGET_TP, TARGET_LRA);
        FLog.d(TAG, "loudnorm pass1: ffmpeg " + measureCmd);
        FFmpegSession s1 = FFmpegKit.execute(measureCmd);
        String logs1 = s1.getAllLogsAsString();
        if (!ReturnCode.isSuccess(s1.getReturnCode())) {
            FLog.w(TAG, "loudnorm pass1 failed rc=" + s1.getReturnCode()
                    + "; tail: " + tail(logs1));
            return false;
        }
        String mI = find(P_INPUT_I, logs1), mTp = find(P_INPUT_TP, logs1),
                mLra = find(P_INPUT_LRA, logs1), mTh = find(P_INPUT_THRESH, logs1),
                mOff = find(P_TARGET_OFFSET, logs1);
        if (mI == null || mTp == null || mLra == null || mTh == null || mOff == null
                || "inf".equals(mI) || "-inf".equals(mI)) {
            // Silence or an unparseable stats block: correcting digital silence is a no-op
            // at best — refuse rather than feed loudnorm garbage measured_* values.
            FLog.w(TAG, "loudnorm pass1 produced no finite measurable stats (silence?)");
            return false;
        }
        // Pass 2 — apply. Video stream-copied (no re-encode); audio re-encoded AAC,
        // pulled back to project-standard 48 kHz (loudnorm internally works at 192 kHz).
        String applyCmd = String.format(Locale.US,
                "-y -i \"%s\" -af \"%sloudnorm=measured_I=%s:measured_TP=%s:measured_LRA=%s"
                        + ":measured_thresh=%s:offset=%s:linear=true:I=%.1f:TP=%.1f:LRA=%.1f\" "
                        // -f mp4: the output is "<name>.loudnorm.tmp", and ffmpeg picks the
                        // container from the extension — without this every loudness pass
                        // failed to open its output and the export shipped un-normalized.
                        + "-c:v copy -c:a aac -b:a 256k -ar 48000 -f mp4 \"%s\"",
                in, cleanChain ? CLEAN_CHAIN_PREFIX : "",
                mI, mTp, mLra, mTh, mOff, targetLUFS, TARGET_TP, TARGET_LRA,
                outFile.getAbsolutePath());
        FLog.d(TAG, "loudnorm pass2: ffmpeg " + applyCmd);
        FFmpegSession s2 = FFmpegKit.execute(applyCmd);
        if (!ReturnCode.isSuccess(s2.getReturnCode())) {
            FLog.w(TAG, "loudnorm pass2 failed rc=" + s2.getReturnCode()
                    + "; tail: " + tail(s2.getAllLogsAsString()));
            return false;
        }
        return outFile.exists() && outFile.length() > 0;
    }

    @Nullable
    private static String find(@NonNull Pattern p, @NonNull String logs) {
        Matcher m = p.matcher(logs);
        return m.find() ? m.group(1) : null;
    }

    @NonNull
    private static String tail(@Nullable String s) {
        if (s == null) return "(none)";
        s = s.trim();
        return s.length() <= 400 ? s : s.substring(s.length() - 400);
    }
}
