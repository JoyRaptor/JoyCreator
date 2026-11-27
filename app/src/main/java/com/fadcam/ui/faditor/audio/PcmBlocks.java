package com.fadcam.ui.faditor.audio;

import java.io.IOException;
import java.io.OutputStream;

/**
 * One block of microphone samples, as {@link VoiceoverRecorder} handles it: written to the WAV as little-endian
 * 16-bit, measured for a level, and handed to whoever listens (Avatar Studio's lip sync, X4 of the Avatar build).
 *
 * <p>Plain Java, so the JVM harness can drive it with a scripted microphone ({@code VoiceoverTapTest}). The recorder's
 * drain thread calls {@link #step} in a loop; nothing here knows about {@code AudioRecord}.</p>
 *
 * <p>The rule that keeps Studio's voiceover unchanged: the bytes reach the file first and exactly as before; the
 * level and the listener come after, and a listener that throws is dropped for the rest of the take instead of
 * stopping the recording.</p>
 */
public final class PcmBlocks {

    /** Hears every block of samples the recorder writes, on the recorder's own thread. */
    public interface PcmListener {
        /**
         * @param samples the block; valid only during this call (the recorder reuses the array), never change it
         * @param count   how many samples of {@code samples} are this block's
         * @param rate    samples a second (44 100 for Studio's voiceover)
         */
        void onPcm(short[] samples, int count, int rate);
    }

    /** Where the samples come from: {@code AudioRecord.read(short[], int, int)} on the phone, a script in the tests. */
    public interface Source {
        int read(short[] buf, int off, int len);
    }

    private final Source source;
    private final OutputStream out;
    private final Object writeLock;
    private final int rate;
    private final short[] shorts;
    private final byte[] bytes;
    private volatile PcmListener listener;
    private volatile float level;
    private long samples;

    public PcmBlocks(Source source, OutputStream out, Object writeLock, int rate, int blockSamples) {
        this.source = source;
        this.out = out;
        this.writeLock = writeLock;
        this.rate = rate;
        this.shorts = new short[Math.max(1, blockSamples)];
        this.bytes = new byte[this.shorts.length * 2];
    }

    public void setListener(PcmListener l) {
        listener = l;
    }

    /** The loudness of the last block, 0 (silence) to 1 (full scale), as root mean square. */
    public float level() {
        return level;
    }

    /** Samples written so far. */
    public long samples() {
        return samples;
    }

    /**
     * Reads one block, writes it, measures it and hands it on.
     *
     * @return the number of samples read (0 when the source had none), or a negative number from the source
     * @throws IOException when the file could not be written (the recording stops, as it always did)
     */
    public int step() throws IOException {
        int read = source.read(shorts, 0, shorts.length);
        if (read <= 0) return read;
        toLittleEndian(shorts, read, bytes);
        synchronized (writeLock) {
            out.write(bytes, 0, read * 2);
        }
        samples += read;
        level = rms(shorts, read);
        PcmListener l = listener;
        if (l != null) {
            try {
                l.onPcm(shorts, read, rate);
            } catch (RuntimeException e) {
                listener = null;   // a broken listener never costs the take
            }
        }
        return read;
    }

    /** 16-bit samples to little-endian bytes, two per sample: the WAV's data, as the recorder always wrote it. */
    public static void toLittleEndian(short[] src, int n, byte[] dst) {
        for (int i = 0; i < n; i++) {
            dst[i * 2] = (byte) (src[i] & 0xFF);
            dst[i * 2 + 1] = (byte) ((src[i] >> 8) & 0xFF);
        }
    }

    /** Root mean square of the first {@code n} samples, 0 to 1. */
    public static float rms(short[] s, int n) {
        if (n <= 0) return 0f;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double v = s[i] / 32768.0;
            sum += v * v;
        }
        return (float) Math.min(1.0, Math.sqrt(sum / n));
    }
}
