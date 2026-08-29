package com.fadcam.ui.faditor.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.waveform.PcmSidecar;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * After-Effects-style audio scrubbing: drag the playhead and hear the audio under it, at the
 * speed you are dragging.
 *
 * <p><b>The one design rule: no decoder is involved.</b> Samples come from a flat,
 * memory-mapped {@link PcmSidecar}, so moving the scrub position is a pointer move rather
 * than a seek-and-decode. That is what makes this usable for its actual purpose — finding
 * the exact instant a word starts — and it is why the engine never stutters, at any zoom,
 * anywhere in a file.</p>
 *
 * <h3>How it sounds, and why</h3>
 * The finger sets a TARGET position; a read cursor chases it. Each {@value #BLOCK_FRAMES}-frame
 * block, the cursor's required velocity is however far behind it is, so the output is the
 * source played at the speed of the drag — slow drags stretch a word into that grainy
 * AE vowel, fast drags give the tape-shuttle rip. Both are the same code path; there are no
 * modes.
 *
 * <p>When the finger stops, output goes silent rather than looping the last grain. A held tone
 * would be a lie about the material — there is no sound there, the user has simply stopped
 * moving — and a loud sustained loop is the single most irritating thing a scrubber can do.</p>
 *
 * <h3>Cost</h3>
 * One interpolated read (two loads, one multiply) per output sample, at {@value
 * PcmSidecar#RATE}&nbsp;Hz. That is roughly 22,000 multiplies a second — beneath measurement
 * noise. There is no allocation in the feeder loop: every buffer is allocated once at
 * {@link #attach}. Latency is one block, about {@value #BLOCK_FRAMES} frames ≈ 23&nbsp;ms.
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *   engine.attach(handle);      // once, when the scrub source is known
 *   engine.begin();             // finger down
 *   engine.seekTo(sourceMs);    // every move event — cheap, just stores a number
 *   engine.end();               // finger up
 *   engine.release();           // teardown
 * </pre>
 *
 * <p><b>Not yet wired into the editor.</b> {@code SPEC_20260829_AUDIO_SYNC_TRUTH} and
 * {@code SPEC_20260829_CAPTION_LAYERS} both hold {@code FaditorEditorActivity}; the call
 * sites land in a separate change once those are in. This class is self-contained and
 * testable on its own until then.</p>
 */
public final class ScrubEngine {

    private static final String TAG = "ScrubEngine";

    /**
     * Frames per feeder block. ~23&nbsp;ms at {@value PcmSidecar#RATE}&nbsp;Hz — short enough
     * that the scrub feels attached to the finger, long enough that the feeder thread is not
     * waking constantly.
     */
    private static final int BLOCK_FRAMES = 512;

    /**
     * Playback speeds beyond this are clamped. Past roughly 8× the source stops being
     * recognisable as anything and becomes broadband noise, so allowing more only makes a
     * fast fling unpleasant.
     */
    private static final double MAX_RATE = 8.0;

    /**
     * Below this speed the block is silent. Not zero: a finger resting on a screen jitters by
     * a pixel or two, which at a deep timeline zoom is a real position change, and chattering
     * between silence and a grain on that jitter buzzes.
     */
    private static final double MIN_RATE = 0.02;

    /**
     * Per-block gain ramp, in frames. Cutting a block in or out at full amplitude puts a step
     * discontinuity into the stream, which is heard as a click on every single block — the
     * classic way a hand-rolled scrubber sounds broken. 64 frames is ~3&nbsp;ms: inaudible as
     * a fade, completely effective as a de-clicker.
     */
    private static final int RAMP_FRAMES = 64;

    @Nullable private PcmSidecar.Handle handle;
    @Nullable private AudioTrack track;
    @Nullable private Thread feeder;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Where the finger is, in frames. Written by the UI thread, read by the feeder. */
    private volatile double targetFrame = 0;
    /** Where playback has actually reached. Feeder thread only. */
    private double readCursor = 0;
    /** Gain at the end of the previous block, so ramps are continuous across blocks. */
    private float lastGain = 0f;

    private short[] block;

    // ── Lifecycle ────────────────────────────────────────────────────────────────────

    /**
     * Point the engine at a baked source. Safe to call repeatedly; a different handle replaces
     * the current one. Passing {@code null} detaches, after which every call is a no-op — which
     * is the correct behaviour when a bake has not finished or could not be mapped.
     */
    public void attach(@Nullable PcmSidecar.Handle h) {
        end();
        this.handle = h;
        if (h != null && block == null) block = new short[BLOCK_FRAMES];
    }

    public boolean isAttached() {
        return handle != null;
    }

    /** Finger down: open the output and start feeding. Idempotent. */
    public void begin() {
        if (handle == null || running.get()) return;
        try {
            int minBytes = AudioTrack.getMinBufferSize(
                    PcmSidecar.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBytes <= 0) {
                FLog.w(TAG, "getMinBufferSize returned " + minBytes + " — no scrub audio");
                return;
            }
            // Four blocks of headroom. Fewer underruns on a busy main thread; still only
            // ~90ms of buffered audio, so the scrub does not lag the finger.
            int bytes = Math.max(minBytes, BLOCK_FRAMES * 2 * 4);

            AudioTrack.Builder b = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(PcmSidecar.RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Asks the framework for the shortest output path it has. Advisory — the
                // device may ignore it — so nothing here depends on it being honoured.
                b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            AudioTrack t = b.build();
            if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                t.release();
                FLog.w(TAG, "AudioTrack did not initialise — no scrub audio");
                return;
            }
            t.play();
            track = t;
            readCursor = targetFrame;
            lastGain = 0f;
            running.set(true);

            Thread th = new Thread(this::feed, "audio-scrub");
            // Audio threads must outrank the UI or a busy frame starves the feeder and the
            // scrub gaps. THREAD_PRIORITY_AUDIO is the standard band for exactly this.
            th.setDaemon(true);
            feeder = th;
            th.start();
        } catch (Exception e) {
            FLog.e(TAG, "could not start scrub output", e);
            stopOutput();
        }
    }

    /**
     * Move the scrub position. Called on every touch-move — deliberately does nothing but
     * store a number, so it is free to call at whatever rate the digitiser reports.
     */
    public void seekTo(long sourceMs) {
        PcmSidecar.Handle h = handle;
        if (h == null) return;
        targetFrame = h.frameForMs(sourceMs);
    }

    /** Finger up: fade out and stop. Idempotent. */
    public void end() {
        if (!running.getAndSet(false)) { stopOutput(); return; }
        Thread th = feeder;
        feeder = null;
        if (th != null) {
            th.interrupt();
            try {
                // The feeder is inside a blocking write of at most one block; it cannot take
                // meaningfully longer than that to notice. The join bounds a pathological case
                // rather than an expected one.
                th.join(250);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        stopOutput();
    }

    /** Full teardown. After this the engine needs a fresh {@link #attach}. */
    public void release() {
        end();
        handle = null;
        block = null;
    }

    private void stopOutput() {
        AudioTrack t = track;
        track = null;
        if (t == null) return;
        try {
            if (t.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) t.pause();
            t.flush();   // drop anything still queued, or the scrub keeps talking after release
            t.stop();
        } catch (Exception ignored) {
            // A track already torn down by the framework throws here; nothing to salvage.
        }
        t.release();
    }

    // ── Feeder ───────────────────────────────────────────────────────────────────────

    /**
     * Produce one block per pass. {@link AudioTrack#write} blocks until the buffer has room,
     * which is what paces this loop — there is no sleep and no clock to keep.
     */
    private void feed() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        PcmSidecar.Handle h = handle;
        AudioTrack t = track;
        short[] buf = block;
        if (h == null || t == null || buf == null) return;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            double target = targetFrame;

            // Velocity that would land the cursor exactly on the finger by the end of this
            // block. Chasing rather than jumping is what makes a fling sound like a sweep
            // through the material instead of a click at the destination.
            double rate = (target - readCursor) / BLOCK_FRAMES;
            if (rate > MAX_RATE) rate = MAX_RATE;
            if (rate < -MAX_RATE) rate = -MAX_RATE;

            boolean silent = Math.abs(rate) < MIN_RATE;
            if (silent) {
                // Nothing is moving. Snap the cursor up so the next move starts from where
                // the finger actually is, rather than sweeping across the gap it did not
                // travel through.
                readCursor = target;
            }

            float startGain = lastGain;
            float endGain = silent ? 0f : 1f;
            double cursor = readCursor;

            for (int i = 0; i < BLOCK_FRAMES; i++) {
                float g;
                if (startGain == endGain) {
                    g = endGain;
                } else if (i < RAMP_FRAMES) {
                    g = startGain + (endGain - startGain) * (i / (float) RAMP_FRAMES);
                } else {
                    g = endGain;
                }
                float s = silent ? 0f : h.frameAt(cursor);
                int v = (int) (s * g * 32767f);
                if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
                if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
                buf[i] = (short) v;
                cursor += rate;
            }
            lastGain = endGain;
            if (!silent) readCursor = cursor;

            int written = t.write(buf, 0, BLOCK_FRAMES);
            if (written < 0) {
                // ERROR_INVALID_OPERATION / ERROR_DEAD_OBJECT — the track is gone (a route
                // change, or the framework reclaimed it). Stop rather than spin on it.
                FLog.w(TAG, "AudioTrack.write returned " + written + " — ending scrub");
                running.set(false);
                return;
            }
        }
    }
}
