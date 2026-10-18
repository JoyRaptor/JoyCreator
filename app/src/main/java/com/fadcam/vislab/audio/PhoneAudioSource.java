package com.fadcam.vislab.audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

/**
 * Hears what the phone itself is playing (music, a game, a video), through Android's playback
 * capture (Android 10+), and feeds a {@link PcmRing}. Needs the person's yes to Android's
 * screen-capture question (the {@link MediaProjection}) and the microphone permission; nothing is
 * recorded or kept. An app that forbids capture (some do) is simply silent here.
 */
@RequiresApi(Build.VERSION_CODES.Q)
public final class PhoneAudioSource implements SoundSource {

    private static final String TAG = "VisPhoneAudio";
    private static final int RATE = 48000;

    private final PcmRing ring;
    private final MediaProjection projection;
    private volatile boolean running;
    private Thread thread;
    private AudioRecord record;

    public PhoneAudioSource(@NonNull PcmRing ring, @NonNull MediaProjection projection) {
        this.ring = ring;
        this.projection = projection;
    }

    @SuppressLint("MissingPermission")   // checked: MicSource.hasPermission
    @Override
    public boolean start(@NonNull Context context) {
        if (running) return true;
        if (!MicSource.hasPermission(context)) return false;
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return false;
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            record = new AudioRecord.Builder()
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, RATE / 10 * 2))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                record = null;
                return false;
            }
            record.startRecording();
        } catch (SecurityException | IllegalStateException | UnsupportedOperationException e) {
            Log.w(TAG, "phone audio unavailable", e);
            if (record != null) record.release();
            record = null;
            return false;
        }
        ring.setFormat(RATE, false);
        running = true;
        thread = new Thread(this::loop, "vislab-phone-audio");
        thread.start();
        return true;
    }

    private void loop() {
        short[] buf = new short[RATE / 100];
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
        return -1;   // live
    }

    @Override
    public void setPaused(boolean paused) {
        // live sound has no pause; the picture freezes in the view instead
    }
}
