package com.fadcam.vislab.audio;

import androidx.annotation.NonNull;

/**
 * Folds decoded audio with more than two channels (5.1, 7.1 ...) down to stereo, so the speaker
 * and the picture both get the song as it should sound (Codex audit 11-15, LAB-07). Before, six
 * interleaved channels were handed to a stereo AudioTrack as if they were three stereo frames:
 * the song played at the wrong speed and pitch and the analysis read it differently again.
 *
 * <p>Android's decoders interleave in the canonical order (front left, front right, front centre,
 * low frequency, back left, back right, front left/right of centre, back centre, side left, side
 * right). Left-side channels go to the left, right-side to the right, centre ones to both at
 * -3 dB; the low-frequency channel is left out, as the usual ITU fold-down does. Each side is then
 * scaled so a full-scale signal in every channel stays full scale: never louder, never clipping.</p>
 *
 * <p>Plain Java: the JVM harness tests it.</p>
 */
public final class StereoDownmix {

    private StereoDownmix() {}

    private static final float C = 0.70710677f;   // -3 dB

    /** Per channel: {gain into left, gain into right}, for 3 to 8 channels in Android's order. */
    @NonNull
    static float[][] gains(int channels) {
        // Positions in the canonical order, by layout Android uses for each channel count.
        // L = 0, R = 1, centre = 2, LFE = 3 (dropped).
        int[] pos;
        switch (channels) {
            case 3: pos = new int[]{0, 1, 2}; break;                         // FL FR FC
            case 4: pos = new int[]{0, 1, 0, 1}; break;                      // FL FR BL BR (quad)
            case 5: pos = new int[]{0, 1, 2, 0, 1}; break;                   // FL FR FC BL BR
            case 6: pos = new int[]{0, 1, 2, 3, 0, 1}; break;                // 5.1
            case 7: pos = new int[]{0, 1, 2, 3, 2, 0, 1}; break;             // 6.1: + BC, SL, SR
            case 8: pos = new int[]{0, 1, 2, 3, 0, 1, 0, 1}; break;          // 7.1: + SL, SR
            default:
                pos = new int[channels];
                for (int i = 0; i < channels; i++) pos[i] = i % 2;          // unknown: alternate
        }
        float[][] g = new float[channels][2];
        float sumL = 0, sumR = 0;
        for (int i = 0; i < channels; i++) {
            switch (pos[i]) {
                case 0: g[i][0] = 1f; break;
                case 1: g[i][1] = 1f; break;
                case 2: g[i][0] = C; g[i][1] = C; break;
                default: break;   // LFE
            }
            sumL += g[i][0];
            sumR += g[i][1];
        }
        for (int i = 0; i < channels; i++) {
            g[i][0] /= Math.max(1f, sumL);
            g[i][1] /= Math.max(1f, sumR);
        }
        return g;
    }

    /**
     * {@code samples} interleaved values of {@code channels} channels, folded to interleaved
     * stereo in {@code out} (which may be {@code in}: it is written behind where it reads).
     *
     * @return the number of stereo frames written ({@code samples / channels})
     */
    public static int fold(@NonNull short[] in, int samples, int channels, @NonNull short[] out) {
        int frames = samples / Math.max(1, channels);
        if (channels <= 2) {
            if (out != in) System.arraycopy(in, 0, out, 0, frames * channels);
            return frames;
        }
        float[][] g = gains(channels);
        for (int f = 0; f < frames; f++) {
            int s = f * channels;
            float l = 0, r = 0;
            for (int c = 0; c < channels; c++) {
                float v = in[s + c];
                l += v * g[c][0];
                r += v * g[c][1];
            }
            out[2 * f] = clamp(l);
            out[2 * f + 1] = clamp(r);
        }
        return frames;
    }

    private static short clamp(float v) {
        int i = Math.round(v);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, i));
    }
}
