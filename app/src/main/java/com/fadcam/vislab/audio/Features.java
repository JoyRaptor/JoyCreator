package com.fadcam.vislab.audio;

/**
 * What the engine hears at one moment: the musical signals every effect reacts to, plus the
 * spectrum, waveform and two-second spectrum history it uploads as textures.
 *
 * <p>Field-for-field the same as the web pilot's {@code FeatureExtractor.frame()}. Arrays are
 * owned by whoever produced the frame and reused frame to frame; copy what you keep.</p>
 */
public final class Features {

    public static final int NB = 128;   // spectrum bins, log-spaced 30 Hz .. 16 kHz
    public static final int NW = 512;   // waveform samples (triggered, gain-levelled)
    public static final int NH = 64;    // history rows, one per 1/30 s

    public float bass, low, mid, high, air;
    public float level, voice, bright;
    public float kick, snare, hat, beat;
    public float beatPhase, barPhase;
    public float bassAcc, levelAcc, energy;
    /** Kick strength on the frame the kick lands, 0 otherwise. */
    public float punch;
    public float bpm = 120f;
    /** Seconds since each of the last 8 kicks, newest first; {@link #shockCount} are valid. */
    public final float[] shocks = new float[8];
    public int shockCount;

    public final float[] spec = new float[NB];
    public final float[] peak = new float[NB];
    public final float[] raw = new float[NB];
    public final float[] waveL = new float[NW];
    public final float[] waveR = new float[NW];
    /** Row-major, row 0 = now. */
    public final float[] hist = new float[NB * NH];

    /** Copy every value into {@code dst}. */
    public void copyTo(Features dst) {
        dst.bass = bass; dst.low = low; dst.mid = mid; dst.high = high; dst.air = air;
        dst.level = level; dst.voice = voice; dst.bright = bright;
        dst.kick = kick; dst.snare = snare; dst.hat = hat; dst.beat = beat;
        dst.beatPhase = beatPhase; dst.barPhase = barPhase;
        dst.bassAcc = bassAcc; dst.levelAcc = levelAcc; dst.energy = energy;
        dst.punch = punch; dst.bpm = bpm;
        System.arraycopy(shocks, 0, dst.shocks, 0, shocks.length);
        dst.shockCount = shockCount;
        System.arraycopy(spec, 0, dst.spec, 0, NB);
        System.arraycopy(peak, 0, dst.peak, 0, NB);
        System.arraycopy(raw, 0, dst.raw, 0, NB);
        System.arraycopy(waveL, 0, dst.waveL, 0, NW);
        System.arraycopy(waveR, 0, dst.waveR, 0, NW);
        System.arraycopy(hist, 0, dst.hist, 0, hist.length);
    }

    /** Age of kick {@code i} (0 = newest), or 99 s when there is none. */
    public float shockAge(int i) {
        return i < shockCount ? shocks[i] : 99f;
    }
}
