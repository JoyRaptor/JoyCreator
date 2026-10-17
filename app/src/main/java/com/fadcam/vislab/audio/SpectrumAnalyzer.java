package com.fadcam.vislab.audio;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.waveform.Fft;

/**
 * PCM in, dB spectrum out, exactly the way WebAudio's {@code AnalyserNode} does it (Blackman
 * window, FFT, magnitude / N, 20 log10, no smoothing), so {@link FeatureExtractor} sees the same
 * numbers on the phone that it sees in the browser pilot.
 */
public final class SpectrumAnalyzer {

    private final int n;
    private final float[] window;
    private final float[] re, im;
    private final float[] db;

    public SpectrumAnalyzer(int fftSize) {
        if (fftSize <= 0 || (fftSize & (fftSize - 1)) != 0) throw new IllegalArgumentException("fft size " + fftSize);
        n = fftSize;
        window = new float[n];
        for (int i = 0; i < n; i++) {
            double x = 2 * Math.PI * i / n;
            window[i] = (float) (0.42 - 0.5 * Math.cos(x) + 0.08 * Math.cos(2 * x));
        }
        re = new float[n];
        im = new float[n];
        db = new float[n / 2];
    }

    public int size() {
        return n;
    }

    /**
     * @param mono the newest {@link #size()} samples, oldest first
     * @return dB per bin (n/2 bins), owned by this analyser and overwritten next call
     */
    @NonNull
    public float[] analyze(@NonNull float[] mono) {
        for (int i = 0; i < n; i++) {
            re[i] = mono[i] * window[i];
            im[i] = 0f;
        }
        Fft.transform(re, im);
        float inv = 1f / n;
        for (int k = 0; k < n / 2; k++) {
            double mag = Math.sqrt((double) re[k] * re[k] + (double) im[k] * im[k]) * inv;
            db[k] = (float) (20 * Math.log10(mag));
        }
        return db;
    }
}
