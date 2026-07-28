package com.fadcam.ui.faditor.transcript;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.fadcam.FLog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Offline speech-to-text with word-level timestamps, using Vosk.
 *
 * <p>Pipeline: download the small English model once → extract the clip's
 * audio range as 16 kHz mono PCM with ffmpeg → run Vosk to get words with
 * start/end times → return a {@link Transcript}. Everything stays on-device.</p>
 */
public class TranscriptionEngine {

    private static final String TAG = "TranscriptionEngine";

    private static final int SAMPLE_RATE = 16000;
    /**
     * How far BEFORE the requested start the coarse input seek aims, so the exact output seek
     * always has room to land forwards. Must comfortably exceed the container's fragment/GOP
     * spacing — the measured error on the user's fragmented-MP4 recording was ~5.6s, so 15s
     * leaves margin without decoding a meaningful amount of extra audio.
     */
    private static final double SEEK_SAFETY_S = 15.0;

    /** Which recognition backend a {@link ModelType} runs on. */
    public enum Engine { VOSK, WHISPER }

    /** Selectable speech models (offline). */
    public enum ModelType {
        FAST(Engine.VOSK, "vosk-model-small-en-us-0.15",
                "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
                "Fast", "~40 MB · quick, lower accuracy"),
        ACCURATE(Engine.VOSK, "vosk-model-en-us-0.22-lgraph",
                "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip",
                "Accurate", "~128 MB · slower download, much better words"),
        // Whisper base.en, quantised q5_1: best accuracy, NEON-optimised on ARM.
        WHISPER_BASE_EN(Engine.WHISPER, "ggml-base.en-q5_1.bin",
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin",
                "High accuracy", "~57 MB · Whisper, best words · slower to run");

        public final Engine engine;
        public final String dir;
        public final String url;
        public final String label;
        public final String detail;

        ModelType(Engine engine, String dir, String url, String label, String detail) {
            this.engine = engine;
            this.dir = dir;
            this.url = url;
            this.label = label;
            this.detail = detail;
        }
    }

    public interface Callback {
        /** @param fraction 0..1, or negative for indeterminate. */
        void onProgress(@NonNull String status, float fraction);
        void onResult(@NonNull Transcript transcript);
        void onError(@NonNull String message);
        /**
         * Optional: a growing transcript emitted after each processed chunk, so
         * the caller can persist progress and survive an interrupted long run.
         */
        default void onPartial(@NonNull Transcript transcript) { }
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public TranscriptionEngine(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    /** True if the given model is already on disk (no download needed). */
    public boolean isModelReady(@NonNull ModelType type) {
        if (type.engine == Engine.WHISPER) {
            File bin = whisperModelFile(type);
            return bin.exists() && bin.length() > 1_000_000L;
        }
        return new File(modelDir(type), "conf/model.conf").exists();
    }

    /** The first already-downloaded model, or null if none are present. */
    @androidx.annotation.Nullable
    public ModelType anyReadyModel() {
        for (ModelType t : ModelType.values()) {
            if (isModelReady(t)) return t;
        }
        return null;
    }

    public void transcribe(@NonNull Uri sourceUri, long inMs, long outMs,
                           @NonNull ModelType type, @NonNull Callback cb) {
        if (type.engine == Engine.WHISPER) {
            executor.execute(() -> transcribeWhisper(sourceUri, inMs, outMs, type, cb));
            return;
        }
        executor.execute(() -> {
            File pcm = null;
            Recognizer recognizer = null;
            Model model = null;
            try {
                File modelRoot = ensureModel(type, cb);
                pcm = extractPcm(sourceUri, inMs, outMs, cb);
                if (pcm == null || pcm.length() == 0) {
                    postError(cb, "Could not read the clip's audio");
                    return;
                }

                postProgress(cb, "Transcribing…", -1f);
                model = new Model(modelRoot.getAbsolutePath());
                recognizer = new Recognizer(model, (float) SAMPLE_RATE);
                recognizer.setWords(true);

                Transcript transcript = new Transcript();
                long totalBytes = pcm.length();
                long readBytes = 0;
                byte[] buffer = new byte[8192];
                try (FileInputStream in = new FileInputStream(pcm)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        if (recognizer.acceptWaveForm(buffer, n)) {
                            parseWords(recognizer.getResult(), inMs, transcript);
                        }
                        readBytes += n;
                        postProgress(cb, "Transcribing…",
                                totalBytes > 0 ? readBytes / (float) totalBytes : -1f);
                    }
                }
                parseWords(recognizer.getFinalResult(), inMs, transcript);

                postResult(cb, transcript);
            } catch (Throwable e) {
                FLog.e(TAG, "Transcription failed", e);
                postError(cb, e.getMessage() != null ? e.getMessage() : "Transcription failed");
            } finally {
                if (recognizer != null) try { recognizer.close(); } catch (Exception ignored) {}
                if (model != null) try { model.close(); } catch (Exception ignored) {}
                if (pcm != null) pcm.delete();
            }
        });
    }

    // ── Whisper (whisper.cpp via JNI) ────────────────────────────────

    /** Chunk size fed to whisper per call: 30 s of 16 kHz mono PCM. */
    private static final int WHISPER_CHUNK_BYTES = 30 * SAMPLE_RATE * 2;

    private void transcribeWhisper(@NonNull Uri sourceUri, long inMs, long outMs,
                                   @NonNull ModelType type, @NonNull Callback cb) {
        if (!WhisperNative.isAvailable()) {
            postError(cb, "Whisper isn't available on this device");
            return;
        }
        File pcm = null;
        long ctx = 0;
        try {
            File modelBin = ensureWhisperModel(type, cb);
            pcm = extractPcm(sourceUri, inMs, outMs, cb);
            if (pcm == null || pcm.length() == 0) {
                postError(cb, "Could not read the clip's audio");
                return;
            }

            postProgress(cb, "Loading model…", -1f);
            ctx = WhisperNative.initContext(modelBin.getAbsolutePath());
            if (ctx == 0) {
                postError(cb, "Failed to load the speech model");
                return;
            }

            int threads = whisperThreadCount();
            Transcript transcript = new Transcript();
            long totalBytes = pcm.length();
            long processedBytes = 0;
            byte[] buffer = new byte[WHISPER_CHUNK_BYTES];

            try (FileInputStream in = new FileInputStream(pcm)) {
                int n;
                while ((n = readFully(in, buffer)) > 0) {
                    // Byte offset → start time of this chunk within the clip's audio.
                    long chunkStartMs = (processedBytes / 2) * 1000 / SAMPLE_RATE;
                    float[] audio = pcmToFloat(buffer, n);

                    int rc = WhisperNative.fullTranscribe(ctx, threads, audio);
                    if (rc == 0) {
                        appendWhisperWords(ctx, inMs + chunkStartMs, transcript);
                    } else {
                        FLog.w(TAG, "whisper_full returned " + rc + " for a chunk");
                    }

                    processedBytes += n;
                    postProgress(cb, "Transcribing…",
                            totalBytes > 0 ? processedBytes / (float) totalBytes : -1f);
                    // Emit progress so the caller can persist and survive interruption.
                    postPartial(cb, transcript.copy());
                }
            }

            postResult(cb, transcript);
        } catch (Throwable e) {
            FLog.e(TAG, "Whisper transcription failed", e);
            postError(cb, e.getMessage() != null ? e.getMessage() : "Transcription failed");
        } finally {
            if (ctx != 0) try { WhisperNative.freeContext(ctx); } catch (Throwable ignored) {}
            if (pcm != null) pcm.delete();
        }
    }

    /** Read up to buffer.length bytes, returning the count (handles short reads). */
    private int readFully(@NonNull FileInputStream in, @NonNull byte[] buffer) throws Exception {
        int total = 0;
        int n;
        while (total < buffer.length
                && (n = in.read(buffer, total, buffer.length - total)) > 0) {
            total += n;
        }
        return total;
    }

    // Reused across pcmToFloat() calls within one transcription run instead of allocating a
    // fresh ~1.9MB float[] per 30s chunk (WHISPER_CHUNK_BYTES/2 samples worst case). Grown only
    // if a caller ever asks for more samples than the current capacity; the WhisperNative JNI
    // call reads exactly array.length samples (GetArrayLength), so for the common full-size
    // chunks this field IS the array passed to native, and only the final (shorter) chunk needs
    // a right-sized copy.
    private float[] pcmFloatPool = new float[0];

    /** Convert {@code len} bytes of little-endian s16 PCM to normalised float [-1,1]. */
    @NonNull
    private float[] pcmToFloat(@NonNull byte[] buf, int len) {
        int samples = len / 2;
        if (pcmFloatPool.length < samples) {
            pcmFloatPool = new float[samples];
        }
        float[] pool = pcmFloatPool;
        for (int i = 0; i < samples; i++) {
            int lo = buf[2 * i] & 0xFF;
            int hi = buf[2 * i + 1];           // signed high byte
            short s = (short) ((hi << 8) | lo);
            pool[i] = s / 32768f;
        }
        // WhisperNative.fullTranscribe reads the WHOLE array length (native GetArrayLength), so
        // a shorter final chunk must be handed a right-sized view, not the oversized pool.
        return samples == pool.length ? pool : java.util.Arrays.copyOf(pool, samples);
    }

    /** Pull the word segments from the last whisper run into the transcript. */
    private void appendWhisperWords(long ctx, long offsetMs, @NonNull Transcript transcript) {
        int count = WhisperNative.getSegmentCount(ctx);
        for (int i = 0; i < count; i++) {
            String text = WhisperNative.getSegmentText(ctx, i).trim();
            if (text.isEmpty() || text.startsWith("[") || text.startsWith("(")) continue;
            // whisper times are in 10 ms units.
            long start = offsetMs + WhisperNative.getSegmentT0(ctx, i) * 10;
            long end = offsetMs + WhisperNative.getSegmentT1(ctx, i) * 10;
            transcript.words.add(new TranscriptWord(text, start, Math.max(start, end)));
        }
    }

    /**
     * Thread count for whisper: the number of performance ("big") cores, capped
     * at 4 and reduced by one for sustained runs to dodge thermal throttling on
     * older Snapdragons. Falls back to a sensible value if detection fails.
     */
    private int whisperThreadCount() {
        int perf = countPerformanceCores();
        int threads = perf > 0 ? perf : Math.max(2,
                Runtime.getRuntime().availableProcessors() / 2);
        // Leave one performance core free on long jobs to stay below the thermal cap.
        if (threads >= 4) threads = 3;
        return Math.max(2, Math.min(4, threads));
    }

    /** Count CPU cores whose max frequency is at/above the cluster maximum (big cores). */
    private int countPerformanceCores() {
        try {
            int cpuCount = Runtime.getRuntime().availableProcessors();
            long[] maxFreq = new long[cpuCount];
            long top = 0;
            for (int i = 0; i < cpuCount; i++) {
                try {
                    File f = new File("/sys/devices/system/cpu/cpu" + i
                            + "/cpufreq/cpuinfo_max_freq");
                    byte[] b = new byte[32];
                    try (FileInputStream in = new FileInputStream(f)) {
                        int n = in.read(b);
                        if (n > 0) maxFreq[i] = Long.parseLong(new String(b, 0, n).trim());
                    }
                } catch (Exception ignored) { }
                top = Math.max(top, maxFreq[i]);
            }
            if (top == 0) return 0;
            int perf = 0;
            for (long m : maxFreq) if (m >= top) perf++;
            return perf;
        } catch (Throwable t) {
            return 0;
        }
    }

    private File whisperModelFile(@NonNull ModelType type) {
        return new File(new File(context.getFilesDir(), "whisper"), type.dir);
    }

    /** Download the Whisper ggml model (single .bin) once, with progress. */
    private File ensureWhisperModel(@NonNull ModelType type, @NonNull Callback cb)
            throws Exception {
        File bin = whisperModelFile(type);
        if (bin.exists() && bin.length() > 1_000_000L) return bin;

        File dir = bin.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        File tmp = new File(bin.getAbsolutePath() + ".part");

        postProgress(cb, "Downloading " + type.label + " model (one time)…", 0f);
        HttpURLConnection conn = (HttpURLConnection) new URL(type.url).openConnection();
        conn.setInstanceFollowRedirects(true);
        // Hugging Face's CDN rejects the default Java user-agent on some edges.
        conn.setRequestProperty("User-Agent", "FadCam/1.0");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        conn.connect();
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new Exception("Model download failed (HTTP " + code + ")");
        }
        long total = conn.getContentLength();
        long got = 0;
        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                postProgress(cb, "Downloading " + type.label + " model (one time)…",
                        total > 0 ? got / (float) total : -1f);
            }
        }
        conn.disconnect();
        if (tmp.length() < 1_000_000L) {
            tmp.delete();
            throw new Exception("Model download incomplete");
        }
        if (bin.exists()) bin.delete();
        if (!tmp.renameTo(bin)) throw new Exception("Could not finalise model file");
        return bin;
    }

    // ── Model management ─────────────────────────────────────────────

    private File modelDir(@NonNull ModelType type) {
        return new File(context.getFilesDir(), "vosk/" + type.dir);
    }

    private File ensureModel(@NonNull ModelType type, @NonNull Callback cb) throws Exception {
        File dir = modelDir(type);
        if (new File(dir, "conf/model.conf").exists()) {
            return dir;
        }
        File voskRoot = new File(context.getFilesDir(), "vosk");
        if (!voskRoot.exists()) voskRoot.mkdirs();

        // Download the zip with progress.
        File zip = new File(voskRoot, "model.zip");
        postProgress(cb, "Downloading " + type.label + " model (one time)…", 0f);
        HttpURLConnection conn = (HttpURLConnection) new URL(type.url).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        conn.connect();
        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new Exception("Model download failed (" + conn.getResponseCode() + ")");
        }
        long total = conn.getContentLength();
        long got = 0;
        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(zip)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                postProgress(cb, "Downloading speech model (one time)…",
                        total > 0 ? got / (float) total : -1f);
            }
        }
        conn.disconnect();

        postProgress(cb, "Unpacking model…", -1f);
        unzip(zip, voskRoot);
        zip.delete();

        if (!new File(dir, "conf/model.conf").exists()) {
            throw new Exception("Model unpack incomplete");
        }
        return dir;
    }

    private void unzip(@NonNull File zip, @NonNull File targetDir) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            byte[] buf = new byte[16384];
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(targetDir, entry.getName());
                // Guard against path traversal
                if (!out.getCanonicalPath().startsWith(targetDir.getCanonicalPath())) {
                    continue;
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    // ── Audio extraction (ffmpeg → 16 kHz mono PCM) ──────────────────

    private File extractPcm(@NonNull Uri sourceUri, long inMs, long outMs,
                            @NonNull Callback cb) {
        postProgress(cb, "Preparing audio…", -1f);
        File outPcm = new File(context.getCacheDir(),
                "transcribe_" + System.currentTimeMillis() + ".pcm");

        String input;
        if ("file".equals(sourceUri.getScheme())) {
            input = sourceUri.getPath();
        } else {
            input = FFmpegKitConfig.getSafParameterForRead(context, sourceUri);
        }

        // TIME ALIGNMENT (device-proven 2026-07-27). Words are stamped `inMs +
        // engineRelativeTime`, where the relative time is derived from the SAMPLE COUNT of this
        // raw PCM. That is only correct if the PCM is a faithful, gap-free rendering of the
        // requested source span. Two ways it was not:
        //
        // 1. DRIFT (the bug the user hit). Raw s16le carries no timestamps. Where the source
        //    has audio gaps -- common in long phone recordings -- ffmpeg concatenated across
        //    them, so the PCM came out SHORTER than the span it represents and every word was
        //    stamped progressively too EARLY. Measured on clip 7 of the user's project: drift
        //    ~0 at the clip start, growing to -6.8s by 28:10, a rate of about -0.6%. Corroborated
        //    independently: that clip's transcript span falls 6291ms (0.516%) short of the clip
        //    duration. `aresample=async=1` pads gaps with silence so output time keeps tracking
        //    input time, which is what makes the sample count a valid clock.
        //    NOTE this is why both Vosk and Whisper agreed to within 22ms on the same error:
        //    they transcribe the SAME mis-timed PCM, so neither engine was at fault.
        //
        // 2. SEEK PRECISION. A lone `-ss` BEFORE `-i` is an INPUT seek and lands on a
        //    fragment/keyframe boundary rather than the requested time; FadCam records
        //    fragmented MP4. That was NOT the cause of the reported drift (the error measured
        //    ~0 at the clip's first words, which a bad seek could not produce -- it would be
        //    wrong by a constant from the very first word). Corrected anyway, because the
        //    stamping assumes an exact start: coarse INPUT seek to a safe margin before the
        //    target, then an exact OUTPUT seek for the remainder.
        double target = inMs / 1000.0;
        double coarse = Math.max(0.0, target - SEEK_SAFETY_S);
        double fine = target - coarse;          // 0 when the clip starts near the file head
        double dur = Math.max(0.1, (outMs - inMs) / 1000.0);
        String cmd = String.format(java.util.Locale.US,
                "-y -ss %.3f -i \"%s\" -ss %.3f -t %.3f -vn -af aresample=async=1:first_pts=0"
                        + " -ac 1 -ar %d -f s16le \"%s\"",
                coarse, input, fine, dur, SAMPLE_RATE, outPcm.getAbsolutePath());
        FLog.d(TAG, "extractPcm: target=" + target + "s coarse=" + coarse + "s fine=" + fine
                + "s dur=" + dur + "s");

        FFmpegSession session = FFmpegKit.execute(cmd);
        if (!ReturnCode.isSuccess(session.getReturnCode())) {
            FLog.e(TAG, "ffmpeg audio extract failed: " + session.getReturnCode());
            outPcm.delete();
            return null;
        }
        // SELF-CHECK. The PCM's own length IS the clock every word timestamp is derived from,
        // so comparing it against the span we asked for detects a mis-timed extraction directly
        // — no transcript, no playback, no human ear required. The original bug (gaps dropped,
        // PCM short, words progressively early) would have shown up here as a growing negative
        // skew instead of costing a round of guesswork.
        long pcmMs = (outPcm.length() * 1000L) / (2L * SAMPLE_RATE);   // s16le mono
        long wantMs = (long) (dur * 1000);
        long skew = pcmMs - wantMs;
        if (Math.abs(skew) > Math.max(500L, wantMs / 200L)) {          // >0.5% or >500ms
            FLog.w(TAG, "extractPcm SKEW: got " + pcmMs + "ms of audio for a " + wantMs
                    + "ms span (" + (skew > 0 ? "+" : "") + skew + "ms, "
                    + String.format(java.util.Locale.US, "%.3f%%", 100.0 * skew / Math.max(1, wantMs))
                    + ") — word timings will be off by this much by the clip's end");
        } else {
            FLog.d(TAG, "extractPcm ok: " + pcmMs + "ms audio for " + wantMs + "ms span");
        }
        return outPcm;
    }

    // ── Vosk JSON → words ────────────────────────────────────────────

    private void parseWords(@NonNull String json, long offsetMs,
                            @NonNull Transcript transcript) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray result = obj.optJSONArray("result");
            if (result == null) return;
            for (int i = 0; i < result.length(); i++) {
                JSONObject w = result.getJSONObject(i);
                String text = w.optString("word", "").trim();
                if (text.isEmpty()) continue;
                long start = offsetMs + Math.round(w.optDouble("start", 0) * 1000);
                long end = offsetMs + Math.round(w.optDouble("end", 0) * 1000);
                transcript.words.add(new TranscriptWord(text, start, end));
            }
        } catch (Exception e) {
            FLog.w(TAG, "Failed to parse Vosk result", e);
        }
    }

    // ── Main-thread callbacks ────────────────────────────────────────

    private void postProgress(@NonNull Callback cb, @NonNull String status, float f) {
        main.post(() -> cb.onProgress(status, f));
    }

    private void postResult(@NonNull Callback cb, @NonNull Transcript t) {
        main.post(() -> cb.onResult(t));
    }

    private void postPartial(@NonNull Callback cb, @NonNull Transcript t) {
        main.post(() -> cb.onPartial(t));
    }

    private void postError(@NonNull Callback cb, @NonNull String msg) {
        main.post(() -> cb.onError(msg));
    }
}
