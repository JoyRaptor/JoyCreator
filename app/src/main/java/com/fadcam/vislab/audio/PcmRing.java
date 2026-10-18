package com.fadcam.vislab.audio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The newest few hundred milliseconds of sound, written by whatever is listening (microphone,
 * device audio, a song being played) on its own thread and read by the renderer each frame.
 * Stereo floats in -1..1. Writers never block on readers for long: one small copy each.
 */
public final class PcmRing {

    private final float[] l, r;
    private final int cap;
    private int head;          // next write position
    private long written;      // total frames ever written
    private volatile int sampleRate;
    private volatile boolean stereo;

    public PcmRing(int capacityFrames, int sampleRate) {
        cap = capacityFrames;
        l = new float[cap];
        r = new float[cap];
        this.sampleRate = sampleRate;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public void setFormat(int sampleRate, boolean stereo) {
        this.sampleRate = sampleRate;
        this.stereo = stereo;
    }

    /** True when the source really has two channels (otherwise the scope fakes a stereo figure). */
    public boolean isStereo() {
        return stereo;
    }

    /** 16-bit PCM, interleaved when {@code channels} is 2. */
    public synchronized void write16(@NonNull short[] buf, int offset, int frames, int channels) {
        final float k = 1f / 32768f;
        for (int i = 0; i < frames; i++) {
            int s = offset + i * channels;
            float a = buf[s] * k;
            float b = channels > 1 ? buf[s + 1] * k : a;
            l[head] = a;
            r[head] = b;
            head = (head + 1) % cap;
        }
        written += frames;
    }

    /** Float PCM, interleaved when {@code channels} is 2. */
    public synchronized void writeFloat(@NonNull float[] buf, int offset, int frames, int channels) {
        for (int i = 0; i < frames; i++) {
            int s = offset + i * channels;
            l[head] = buf[s];
            r[head] = channels > 1 ? buf[s + 1] : buf[s];
            head = (head + 1) % cap;
        }
        written += frames;
    }

    /** Silence, so a paused source fades the picture out instead of freezing it. */
    public synchronized void writeSilence(int frames) {
        for (int i = 0; i < frames; i++) {
            l[head] = 0f;
            r[head] = 0f;
            head = (head + 1) % cap;
        }
        written += frames;
    }

    public synchronized long framesWritten() {
        return written;
    }

    /** Copy the newest {@code n} frames, oldest first. Missing history reads as silence. */
    public synchronized void readLatest(@NonNull float[] outL, @Nullable float[] outR, int n) {
        readEndingAt(written, outL, outR, n);
    }

    /**
     * Copy the {@code n} frames that end at absolute frame {@code end} (exclusive), oldest
     * first. A song player passes the speaker's playback position here, so the picture
     * matches what is heard rather than what was just decoded. Frames that were never written,
     * or have already been overwritten, read as silence.
     */
    public synchronized void readEndingAt(long end, @NonNull float[] outL, @Nullable float[] outR, int n) {
        if (end > written) end = written;
        long first = end - n;
        long oldest = written - cap;
        for (int i = 0; i < n; i++) {
            long frame = first + i;
            if (frame < 0 || frame < oldest) {
                outL[i] = 0f;
                if (outR != null) outR[i] = 0f;
                continue;
            }
            // head is the slot of frame `written`; step back from it.
            int idx = (int) (((head - (written - frame)) % cap + cap) % cap);
            outL[i] = l[idx];
            if (outR != null) outR[i] = r[idx];
        }
    }
}
