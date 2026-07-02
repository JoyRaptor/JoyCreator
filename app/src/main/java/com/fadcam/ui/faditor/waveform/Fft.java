package com.fadcam.ui.faditor.waveform;

/**
 * Minimal in-place iterative radix-2 Cooley–Tukey FFT (no third-party dependency).
 * Operates on parallel real/imaginary arrays whose length must be a power of two.
 * Used by {@link WaveformExtractor} to turn a window of PCM samples into per-band magnitudes.
 */
public final class Fft {

    private Fft() {}

    public static void transform(float[] re, float[] im) {
        int n = re.length;
        if (n == 0) return;
        if ((n & (n - 1)) != 0) {
            throw new IllegalArgumentException("FFT length must be a power of 2: " + n);
        }
        // Bit-reversal permutation.
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        // Butterflies.
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wLenRe = (float) Math.cos(ang);
            float wLenIm = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float wRe = 1f, wIm = 0f;
                int half = len >> 1;
                for (int k = 0; k < half; k++) {
                    int a = i + k;
                    int b = i + k + half;
                    float vRe = re[b] * wRe - im[b] * wIm;
                    float vIm = re[b] * wIm + im[b] * wRe;
                    re[b] = re[a] - vRe; im[b] = im[a] - vIm;
                    re[a] += vRe;        im[a] += vIm;
                    float nwRe = wRe * wLenRe - wIm * wLenIm;
                    wIm = wRe * wLenIm + wIm * wLenRe;
                    wRe = nwRe;
                }
            }
        }
    }
}
