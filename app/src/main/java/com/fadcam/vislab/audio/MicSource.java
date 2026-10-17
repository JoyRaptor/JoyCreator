package com.fadcam.vislab.audio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

/**
 * Listens to the microphone and feeds a {@link PcmRing}. Mono, 48 kHz. Nothing is recorded or
 * kept: the samples go straight into the ring and are overwritten within a second.
 */
public final class MicSource implements SoundSource {

    private static final String TAG = "VisMic";
    private static final int RATE = 48000;

    private final PcmRing ring;
    private volatile boolean running;
    private Thread thread;
    private AudioRecord record;

    public MicSource(@NonNull PcmRing ring) {
        this.ring = ring;
    }

    public static boolean hasPermission(@NonNull Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** @return false when the microphone could not be opened (no permission, or in use). */
    @Override
    public boolean start(@NonNull Context context) {
        if (running) return true;
        if (!hasPermission(context)) return false;
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return false;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE / 10 * 2));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                record = null;
                return false;
            }
            record.startRecording();
        } catch (SecurityException | IllegalStateException e) {
            Log.w(TAG, "mic unavailable", e);
            if (record != null) record.release();
            record = null;
            return false;
        }
        ring.setFormat(RATE, false);
        running = true;
        thread = new Thread(this::loop, "vislab-mic");
        thread.start();
        return true;
    }

    private void loop() {
        short[] buf = new short[RATE / 100];   // 10 ms reads keep the picture tight to the voice
        while (running) {
            int n = record.read(buf, 0, buf.length);
            if (n > 0) ring.write16(buf, 0, n, 1);
            else if (n < 0) break;
        }
    }

    @Override
    public void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (record != null) {
            try {
                record.stop();
            } catch (IllegalStateException ignored) {
                // already stopped
            }
            record.release();
            record = null;
        }
    }

    @Override
    public long playheadFrame() {
        return -1;   // live: always the newest
    }

    @Override
    public void setPaused(boolean paused) {
        // The microphone has no pause; the picture freezes in the view instead.
    }
}
