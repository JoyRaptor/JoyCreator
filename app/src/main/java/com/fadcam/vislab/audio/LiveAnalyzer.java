package com.fadcam.vislab.audio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Real-time listening: each rendered frame, take 2048 samples from a {@link PcmRing} and run
 * them through the same analysis the browser pilot uses. For a song, the samples are the ones
 * the speaker is playing right now; for a live source (microphone, the phone's audio) they
 * are the newest. Used by the VisLab room, the floating on-screen visualiser and the recorder.
 */
public final class LiveAnalyzer {

    public static final int FFT = 2048;

    private final PcmRing ring;
    private final SpectrumAnalyzer spectrum = new SpectrumAnalyzer(FFT);
    private final float[] l = new float[FFT], r = new float[FFT], mono = new float[FFT];
    @Nullable private volatile SoundSource source;
    private FeatureExtractor extractor;
    private int extractorRate;

    public LiveAnalyzer(@NonNull PcmRing ring) {
        this.ring = ring;
    }

    @NonNull
    public PcmRing ring() {
        return ring;
    }

    /** The source whose playhead decides which samples count as "now" (null = newest). */
    public void setSource(@Nullable SoundSource s) {
        source = s;
    }

    /** Analyse what is being heard. {@code dt} is the time since the last frame, in seconds. */
    @NonNull
    public Features step(double dt) {
        int rate = ring.sampleRate();
        if (extractor == null || rate != extractorRate) {
            extractor = new FeatureExtractor(rate, FFT);
            extractorRate = rate;
        }
        SoundSource s = source;
        long head = s != null ? s.playheadFrame() : -1;
        if (head >= 0) ring.readEndingAt(head, l, r, FFT);
        else ring.readLatest(l, r, FFT);
        for (int i = 0; i < FFT; i++) mono[i] = 0.5f * (l[i] + r[i]);
        float[] db = spectrum.analyze(mono);
        return extractor.update(db, l, ring.isStereo() ? r : null, dt);
    }
}
