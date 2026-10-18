package com.fadcam.vislab.audio;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * What the screen recorder is recording, heard by VisLab: the recorder hands over every block of
 * sound it records (the phone's audio, the microphone, whichever the recording is set to), and
 * this feeds it to a {@link PcmRing}. No second microphone is opened, so the picture moves with
 * exactly the sound in the video.
 *
 * <p>{@link #offer} is called on the recorder's audio thread; with nobody listening it only
 * notes the time.</p>
 */
public final class RecordingTap implements SoundSource {

    @Nullable private static volatile PcmRing listener;
    private static volatile long lastOfferNs;
    private static short[] scratch = new short[4096];

    private final PcmRing ring;

    public RecordingTap(@NonNull PcmRing ring) {
        this.ring = ring;
    }

    /**
     * The recorder recorded {@code bytes} of 16-bit mono PCM at {@code sampleRate}. The buffer's
     * position and limit are left as they were.
     */
    public static void offer(@NonNull ByteBuffer pcm, int bytes, int sampleRate) {
        lastOfferNs = System.nanoTime();
        PcmRing r = listener;
        if (r == null || bytes <= 1) return;
        int n = bytes / 2;
        short[] s = scratch;
        if (s.length < n) scratch = s = new short[n];
        ShortBuffer sb = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        n = Math.min(n, sb.remaining());
        sb.get(s, 0, n);
        if (r.sampleRate() != sampleRate || r.isStereo()) r.setFormat(sampleRate, false);
        r.write16(s, 0, n, 1);
    }

    /** True while a recording is producing sound (a block arrived in the last second). */
    public static boolean isLive() {
        return System.nanoTime() - lastOfferNs < 1_000_000_000L;
    }

    @Override
    public boolean start(@NonNull Context context) {
        listener = ring;
        return true;
    }

    @Override
    public void stop() {
        if (listener == ring) listener = null;
    }

    @Override
    public void setPaused(boolean paused) {
        // The recording decides; the picture freezes in the view instead.
    }

    @Override
    public long playheadFrame() {
        return -1;   // live: always the newest
    }
}
