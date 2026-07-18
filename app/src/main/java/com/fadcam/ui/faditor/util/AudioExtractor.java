package com.fadcam.ui.faditor.util;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Utility to extract audio from a video file and generate waveform data.
 *
 * <p>Extraction produces an AAC (.m4a) file in the app's cache directory.
 * Waveform generation decodes the audio to PCM and downsamples to an amplitude array.</p>
 */
public class AudioExtractor {

    private static final String TAG = "AudioExtractor";

    /**
     * Milliseconds of audio represented by each value returned from
     * {@link #generateWaveform}. Consumers map source-time → index as
     * {@code timeMs / WAVEFORM_WINDOW_MS} (exact, independent of the file's
     * reported duration, which is unreliable for fragmented MP4).
     */
    public static final int WAVEFORM_WINDOW_MS = 20;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /**
     * Callback for extraction operations.
     */
    public interface ExtractionCallback {
        /** Called on success with the output file and its duration in ms. */
        void onSuccess(@NonNull File outputFile, long durationMs);
        /** Called on failure. */
        void onError(@NonNull Exception error);
    }

    /**
     * Callback for waveform generation.
     */
    public interface WaveformCallback {
        /** Called with the downsampled amplitude array (0–255 per sample). */
        void onWaveformReady(@NonNull int[] waveform);
        /** Called on failure. */
        void onError(@NonNull Exception error);
    }

    public AudioExtractor(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Extract the audio track from a video/audio file to a standalone .m4a file.
     * Runs on a background thread.
     *
     * @param sourceUri the video/audio URI
     * @param callback  result callback (called on main thread)
     */
    public void extractAudio(@NonNull Uri sourceUri, @NonNull ExtractionCallback callback) {
        executor.execute(() -> {
            try {
                File output = doExtract(sourceUri);
                long durationMs = getAudioDuration(output);
                runOnMain(() -> callback.onSuccess(output, durationMs));
            } catch (Exception e) {
                FLog.e(TAG, "Audio extraction failed", e);
                runOnMain(() -> callback.onError(e));
            }
        });
    }

    /**
     * Generate waveform amplitude data from an audio file.
     * Runs on a background thread.
     *
     * @param audioUri  the audio file URI
     * @param callback  result callback (called on main thread)
     */
    public void generateWaveform(@NonNull Uri audioUri, @NonNull WaveformCallback callback) {
        executor.execute(() -> {
            try {
                // F3a (PERF_SPEC_LONGFILE_20260718): this decode covers the ENTIRE audio
                // track (a 45-min source = minutes of MediaCodec work) and used to re-run
                // on EVERY editor open — it was the visible "loading the audio" wait.
                // Disk-cache the tiny int[] (one byte-range value per 20ms bin) keyed by
                // source identity so a project reopen loads it instantly.
                int[] waveform = readWaveformCache(audioUri);
                if (waveform == null) {
                    waveform = doGenerateWaveform(audioUri);
                    writeWaveformCache(audioUri, waveform);
                }
                final int[] result = waveform;
                runOnMain(() -> callback.onWaveformReady(result));
            } catch (Throwable e) {
                // Catch Throwable (incl. OutOfMemoryError) so a pathological file
                // can never crash the whole app — just skip its waveform.
                FLog.e(TAG, "Waveform generation failed", e);
                Exception ex = (e instanceof Exception)
                        ? (Exception) e : new RuntimeException(e);
                runOnMain(() -> callback.onError(ex));
            }
        });
    }

    private static final int WAVEFORM_CACHE_VERSION = 1;

    /**
     * Cache file keyed by the URI string plus the source's length+mtime when it resolves
     * to a readable file (so a re-recorded/replaced source invalidates naturally). The
     * URI here should be the clip's ORIGINAL source URI — a resolved playback URI flips
     * between raw and remuxed cache paths across sessions and silently splits the key.
     */
    @NonNull
    private File waveformCacheFile(@NonNull Uri uri) {
        File dir = DurableCache.dir(context, "waveform_legacy");
        long len = 0, mtime = 0;
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            len = f.length();
            mtime = f.lastModified();
        }
        String key = Integer.toHexString(uri.toString().hashCode())
                + "_" + len + "_" + Long.toHexString(mtime);
        return new File(dir, key + ".bin");
    }

    @Nullable
    private int[] readWaveformCache(@NonNull Uri uri) {
        File f = waveformCacheFile(uri);
        if (!f.exists()) return null;
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(f)))) {
            if (in.readInt() != WAVEFORM_CACHE_VERSION) return null;
            int n = in.readInt();
            if (n < 0 || n > 20_000_000) return null; // sanity: ~110h at 20ms bins
            int[] bins = new int[n];
            for (int i = 0; i < n; i++) bins[i] = in.readUnsignedByte();
            FLog.d(TAG, "Waveform cache HIT (" + n + " bins): " + f.getName());
            return bins;
        } catch (Exception e) {
            FLog.w(TAG, "Waveform cache read failed; re-decoding", e);
            return null;
        }
    }

    private void writeWaveformCache(@NonNull Uri uri, @NonNull int[] bins) {
        File f = waveformCacheFile(uri);
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(f)))) {
            out.writeInt(WAVEFORM_CACHE_VERSION);
            out.writeInt(bins.length);
            for (int b : bins) out.writeByte(Math.max(0, Math.min(255, b)));
        } catch (Exception e) {
            FLog.w(TAG, "Waveform cache write failed", e);
        }
    }

    /**
     * Shutdown the background executor. Call when no longer needed.
     */
    public void shutdown() {
        executor.shutdownNow();
    }

    // ── Internal extraction ──────────────────────────────────────────

    @NonNull
    private File doExtract(@NonNull Uri sourceUri) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, sourceUri, null);

            // Find audio track
            int audioTrackIndex = -1;
            MediaFormat audioFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    audioFormat = format;
                    break;
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                throw new IllegalStateException("No audio track found in source");
            }

            extractor.selectTrack(audioTrackIndex);

            // Create output file. DURABILITY (road_map Tier-1): extracted audio becomes an AudioClip's
            // PERSISTED sourceUri — it must survive the OS wiping the cache dir, else the track silently
            // breaks (recoverStaleCachePaths only recovers VIDEO clips). Use getFilesDir() (app-internal,
            // not OS-cleared) like the images dir, NOT getCacheDir().
            File audioDir = new File(context.getFilesDir(), "faditor_audio");
            if (!audioDir.exists()) audioDir.mkdirs();
            File outputFile = new File(audioDir,
                    "audio_" + System.currentTimeMillis() + ".m4a");

            // Mux audio track to output
            MediaMuxer muxer = new MediaMuxer(
                    outputFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int dstTrackIndex = muxer.addTrack(audioFormat);
            muxer.start();

            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024); // 1MB buffer
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            while (true) {
                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) break;

                bufferInfo.offset = 0;
                bufferInfo.size = sampleSize;
                bufferInfo.presentationTimeUs = extractor.getSampleTime();
                bufferInfo.flags = extractor.getSampleFlags();

                muxer.writeSampleData(dstTrackIndex, buffer, bufferInfo);
                extractor.advance();
            }

            muxer.stop();
            muxer.release();

            FLog.d(TAG, "Audio extracted to: " + outputFile.getAbsolutePath()
                    + " (" + outputFile.length() + " bytes)");
            return outputFile;

        } finally {
            extractor.release();
        }
    }

    /**
     * Get audio duration from a file using MediaExtractor.
     */
    private long getAudioDuration(@NonNull File file) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        return format.getLong(MediaFormat.KEY_DURATION) / 1000; // µs → ms
                    }
                }
            }
        } catch (Exception e) {
            FLog.e(TAG, "Failed to get audio duration", e);
        } finally {
            extractor.release();
        }
        return 0;
    }

    // ── Internal waveform generation ─────────────────────────────────

    @NonNull
    private int[] doGenerateWaveform(@NonNull Uri audioUri) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, audioUri, null);

            // Find audio track
            int trackIndex = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    inputFormat = format;
                    break;
                }
            }
            if (trackIndex < 0 || inputFormat == null) {
                // No audio track present
                return new int[0];
            }

            extractor.selectTrack(trackIndex);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) return new int[0];

            // Decode to PCM
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            // Time advance per audio frame (one sample across all channels).
            double msPerFrame = 1000.0 / sampleRate;

            // Bin index = realTimeMs / WAVEFORM_WINDOW_MS. We place each window at
            // the decoder's REPORTED presentation timestamp (not a count from zero),
            // so codec priming / encoder delay can't shift the envelope. This keeps
            // peaks aligned with playback. Memory: one int per 20 ms (tiny).
            int[] bins = new int[1024];
            int maxBin = -1;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long timeoutUs = 10_000; // 10ms

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    int inputBufIndex = codec.dequeueInputBuffer(timeoutUs);
                    if (inputBufIndex >= 0) {
                        ByteBuffer inputBuf = codec.getInputBuffer(inputBufIndex);
                        if (inputBuf != null) {
                            int sampleSize = extractor.readSampleData(inputBuf, 0);
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                long pts = extractor.getSampleTime();
                                codec.queueInputBuffer(inputBufIndex, 0, sampleSize, pts, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                // Drain output — bin peaks by presentation timestamp
                int outputBufIndex = codec.dequeueOutputBuffer(info, timeoutUs);
                if (outputBufIndex >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                    ByteBuffer outputBuf = codec.getOutputBuffer(outputBufIndex);
                    if (outputBuf != null && info.size > 0) {
                        outputBuf.position(info.offset);
                        outputBuf.limit(info.offset + info.size);
                        ShortBuffer shortBuf = outputBuf.asShortBuffer();

                        // Time of the first frame in this buffer (re-anchored each
                        // buffer from pts to avoid float drift over long files).
                        double tMs = info.presentationTimeUs / 1000.0;
                        int bin = tMs > 0 ? (int) (tMs / WAVEFORM_WINDOW_MS) : 0;
                        double nextBoundaryMs = (bin + 1) * (double) WAVEFORM_WINDOW_MS;

                        int frames = shortBuf.remaining() / channels;
                        for (int f = 0; f < frames; f++) {
                            int peak = 0;
                            for (int c = 0; c < channels; c++) {
                                int a = Math.abs(shortBuf.get());
                                if (a > peak) peak = a;
                            }
                            while (tMs >= nextBoundaryMs) {
                                bin++;
                                nextBoundaryMs += WAVEFORM_WINDOW_MS;
                            }
                            if (bin >= 0) {
                                if (bin >= bins.length) {
                                    bins = Arrays.copyOf(bins,
                                            Math.max(bins.length * 2, bin + 1));
                                }
                                int norm = Math.min(255, (peak * 255) / 32768);
                                if (norm > bins[bin]) bins[bin] = norm;
                                if (bin > maxBin) maxBin = bin;
                            }
                            tMs += msPerFrame;
                        }
                    }
                    codec.releaseOutputBuffer(outputBufIndex, false);
                }
            }

            int binCount = maxBin + 1;

            return Arrays.copyOf(bins, Math.max(1, binCount));

        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            extractor.release();
        }
    }

    private void runOnMain(@NonNull Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
