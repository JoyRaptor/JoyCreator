package com.fadcam.ui.faditor.avatar;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

/**
 * A3 v2 (PLAN_AVATAR_STUDIO): the mic PCM owner for the spectral viseme tier —
 * a small mountable component (start/stop, idempotent, same shape as
 * {@link MediaPipeTrackingSource}'s mount contract) that owns a 16kHz mono
 * {@link AudioRecord}, pumps every read chunk through a
 * {@link SpectralVisemeAnalyzer} on its OWN thread, and publishes the latest
 * (audioDb, visemeClassIndex) pair for a tracking-source wrapper to fold into
 * {@link TrackingFrame}s (see {@code AvatarStudioActivity}'s
 * {@code MicAugmentedSource}).
 *
 * <p>Deliberately NOT a {@link TrackingSource} itself: it carries audio, not
 * pose driver params, and it must be composable with WHICHEVER visual source
 * is mounted (face or synthetic) rather than competing with it for the bus's
 * one-source slot (camera-single-owner's audio analogue — one mic, any
 * visual tracker).</p>
 *
 * <p>Permission handling: {@link #start} checks {@code RECORD_AUDIO} itself
 * and degrades to a harmless no-op when absent (no crash) — callers keep
 * reading {@link #currentAudioDb()}/{@link #currentVisemeClassIndex()} which
 * simply stay at their "no mic" sentinels ({@link Float#NaN} /
 * {@link TrackingFrame#NO_VISEME}), so the amplitude/life-package fallback
 * behaves exactly as it does today. At most ONE toast is ever shown per
 * instance (no spam on repeated start attempts).</p>
 */
public final class MicVisemeSource {

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    /** Read chunk size (samples) — small for low lip-sync latency. */
    private static final int CHUNK_SAMPLES = 512;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SpectralVisemeAnalyzer analyzer = new SpectralVisemeAnalyzer();

    @androidx.annotation.Nullable private AudioRecord audioRecord;
    @androidx.annotation.Nullable private Thread worker;
    private volatile boolean running;
    private boolean toastShown;

    private volatile float latestAudioDb = Float.NaN;
    private volatile int latestVisemeClass = TrackingFrame.NO_VISEME;

    /**
     * Start capturing. Idempotent (a second call while running is a no-op).
     * Silently does nothing (no crash, no repeated toasts) when
     * {@code RECORD_AUDIO} isn't granted or the device can't init AudioRecord
     * — the amplitude/life tiers keep working off the visual tracker alone.
     */
    public synchronized void start(@NonNull Context context) {
        if (running) return;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            toastOnce(context, "Mic permission not granted — lip sync uses head tracking only");
            return;
        }

        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
                return; // unsupported config on this device — degrade silently
            }
            int bufferBytes = Math.max(minBuf, CHUNK_SAMPLES * 2 * 4);
            AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    CHANNEL_CONFIG, AUDIO_FORMAT, bufferBytes);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                return;
            }
            rec.startRecording();
            audioRecord = rec;
            running = true;
            analyzer.reset();
            latestAudioDb = Float.NaN;
            latestVisemeClass = TrackingFrame.NO_VISEME;

            Thread t = new Thread(this::runLoop, "mic-viseme-source");
            t.setDaemon(true);
            worker = t;
            t.start();
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            // Permission race / device quirk — never crash the studio over lip sync.
            releaseInternal();
        }
    }

    /** Stop capturing and release the recorder. Idempotent; safe to call when
     *  never started or already stopped. */
    public synchronized void stop() {
        running = false;
        Thread t = worker;
        worker = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        releaseInternal();
        latestAudioDb = Float.NaN;
        latestVisemeClass = TrackingFrame.NO_VISEME;
    }

    public boolean isActive() { return running; }

    /** Latest RMS dB (same scale as {@link TrackingFrame#audioDb} elsewhere in
     *  the pipeline — 20*log10(rms) of full-scale-normalized samples); NaN
     *  when the mic isn't running. */
    public float currentAudioDb() { return latestAudioDb; }

    /** Latest {@link SpectralVisemeAnalyzer} class index, or
     *  {@link TrackingFrame#NO_VISEME} when the mic isn't running. */
    public int currentVisemeClassIndex() { return latestVisemeClass; }

    private void runLoop() {
        short[] chunk = new short[CHUNK_SAMPLES];
        AudioRecord rec = audioRecord;
        if (rec == null) return;
        Thread self = Thread.currentThread();
        while (running && !self.isInterrupted()) {
            int read;
            try {
                read = rec.read(chunk, 0, chunk.length);
            } catch (Exception e) {
                break; // recorder went away underneath us — stop() will clean up
            }
            if (read > 0) {
                short[] toPush = read == chunk.length ? chunk
                        : java.util.Arrays.copyOf(chunk, read);
                analyzer.push(toPush, SAMPLE_RATE);
                latestAudioDb = analyzer.currentDb();
                latestVisemeClass = analyzer.currentClassIndex();
            } else if (read < 0) {
                break; // AudioRecord error code — stop cleanly
            }
        }
    }

    private void releaseInternal() {
        AudioRecord rec = audioRecord;
        audioRecord = null;
        if (rec == null) return;
        try {
            if (rec.getState() == AudioRecord.STATE_INITIALIZED) rec.stop();
        } catch (IllegalStateException ignored) {
            // already stopped/released — fine
        }
        rec.release();
    }

    private void toastOnce(@NonNull Context context, @NonNull String msg) {
        if (toastShown) return;
        toastShown = true;
        Context app = context.getApplicationContext();
        main.post(() -> Toast.makeText(app, msg, Toast.LENGTH_SHORT).show());
    }
}
