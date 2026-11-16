package com.fadcam.ui.faditor.export;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.audio.ChannelMixingMatrix;

/**
 * How a source with more than two channels folds into the export's stereo track.
 *
 * <p>Media3 1.8.0's {@code ChannelMixingMatrix.createForConstantPower(n, 2)} writes its table one
 * OUTPUT side at a time ("left: FL, FR, FC, ...; right: ...") while the matrix reads it one INPUT
 * channel at a time. So for a 5.1 file the centre went to the left side only, the back left came
 * out on the right, and 3-, 4- and 5-channel files were mixed wrong the same way. The editor's
 * preview never showed it: there Android does the fold itself. Found on the test Fold, 11-16
 * (a centre-channel tone exported on the left only).</p>
 *
 * <p>These are Android's own constant-power folds (the same numbers media3 meant), laid out the
 * way the matrix reads them: {@code coefficients[input * 2 + output]}. Channel order is Android's:
 * FL, FR, FC, LFE, BL, BR (4 channels: FL, FR, BL, BR; 5: FL, FR, FC, BL, BR).</p>
 */
public final class StereoDownmix {

    private StereoDownmix() {}

    private static final float H = 0.7071f;   // minus 3 dB: a centre or surround shared or folded in

    /**
     * Per input channel, its {left, right} gain. Null when there is no fold for that count
     * (7 channels and up): the mixing processor then refuses that file, as before.
     */
    @Nullable
    static float[][] gains(int inputChannels) {
        switch (inputChannels) {
            case 1: return new float[][]{{H, H}};                                       // mono, centred
            case 2: return new float[][]{{1, 0}, {0, 1}};
            case 3: return new float[][]{{1, 0}, {0, 1}, {H, H}};                        // FL FR FC
            case 4: return new float[][]{{1, 0}, {0, 1}, {H, 0}, {0, H}};                // FL FR BL BR
            case 5: return new float[][]{{1, 0}, {0, 1}, {H, H}, {H, 0}, {0, H}};        // FL FR FC BL BR
            case 6: return new float[][]{{1, 0}, {0, 1}, {H, H}, {0.5f, 0.5f}, {H, 0}, {0, H}};
            default: return null;
        }
    }

    /** The {@code ChannelMixingMatrix} coefficients, input-major: {@code [input * 2 + output]}. */
    @Nullable
    static float[] coefficients(int inputChannels) {
        float[][] g = gains(inputChannels);
        if (g == null) return null;
        float[] c = new float[g.length * 2];
        for (int in = 0; in < g.length; in++) {
            c[in * 2] = g[in][0];
            c[in * 2 + 1] = g[in][1];
        }
        return c;
    }

    /** The fold to stereo for {@code inputChannels} (3 to 6), or null when there is none. */
    @Nullable
    public static ChannelMixingMatrix matrix(int inputChannels) {
        float[] c = coefficients(inputChannels);
        return c == null ? null : new ChannelMixingMatrix(inputChannels, 2, c);
    }

    /** Every fold this class has, for a mixing processor to hold (3 to 6 channels). */
    @NonNull
    public static ChannelMixingMatrix[] multichannelFolds() {
        return new ChannelMixingMatrix[]{matrix(3), matrix(4), matrix(5), matrix(6)};
    }
}
