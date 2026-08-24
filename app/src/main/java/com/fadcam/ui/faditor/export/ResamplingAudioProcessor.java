package com.fadcam.ui.faditor.export;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.audio.AudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Audio processor that resamples PCM audio from a source sample rate to a target
 * sample rate using linear interpolation.
 *
 * <p>This is a simple linear-interpolation resampler suitable for the modest
 * rate conversions typical in this app (44.1kHz ↔ 48kHz). For higher quality
 * or larger ratios a windowed-sinc or polyphase resampler would be better,
 * but the 44.1↔48 conversion is only ~7% and linear is transparent here.</p>
 */
public class ResamplingAudioProcessor extends BaseAudioProcessor {

    private final int sourceSampleRate;
    private final int targetSampleRate;
    private final double ratio; // target / source

    private int channelCount;
    private double sourceFrameAccumulator;

    public ResamplingAudioProcessor(int sourceSampleRate, int targetSampleRate) {
        if (sourceSampleRate <= 0 || targetSampleRate <= 0) {
            throw new IllegalArgumentException("Sample rates must be positive");
        }
        this.sourceSampleRate = sourceSampleRate;
        this.targetSampleRate = targetSampleRate;
        this.ratio = (double) targetSampleRate / sourceSampleRate;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        this.channelCount = inputAudioFormat.channelCount;
        this.sourceFrameAccumulator = 0.0;
        // Output format has the target sample rate; channel count and encoding unchanged.
        return new AudioFormat(targetSampleRate, channelCount, inputAudioFormat.encoding);
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) return;

        ShortBuffer inShort = inputBuffer.asShortBuffer();
        int inputFrames = remaining / (2 * channelCount); // 2 bytes per sample (16-bit)

        // Estimate output frames needed (ceil(inputFrames * ratio))
        int estimatedOutputFrames = (int) Math.ceil(inputFrames * ratio) + channelCount;
        int outputCapacity = estimatedOutputFrames * channelCount * 2; // bytes

        ByteBuffer output = replaceOutputBuffer(outputCapacity);
        output.order(ByteOrder.nativeOrder());
        ShortBuffer outShort = output.asShortBuffer();

        // Linear interpolation resampling
        // We conceptually have a continuous input signal sampled at source rate.
        // We want output samples at target rate.
        // sourceFrameAccumulator tracks the current position in input frames (can be fractional).
        for (int outFrame = 0; outFrame < estimatedOutputFrames && outShort.hasRemaining(); outFrame++) {
            double idealInputFrame = outFrame / ratio + sourceFrameAccumulator;
            int inputFrame0 = (int) Math.floor(idealInputFrame);
            double frac = idealInputFrame - inputFrame0;

            for (int ch = 0; ch < channelCount; ch++) {
                short sample0 = (inputFrame0 >= 0 && inputFrame0 < inputFrames)
                        ? inShort.get(inputFrame0 * channelCount + ch)
                        : 0;
                short sample1 = (inputFrame0 + 1 < inputFrames)
                        ? inShort.get((inputFrame0 + 1) * channelCount + ch)
                        : 0;
                int interpolated = (int) Math.round(sample0 + frac * (sample1 - sample0));
                interpolated = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, interpolated));
                outShort.put((short) interpolated);
            }
        }

        // Update accumulator for next call (continuity across queueInput calls)
        sourceFrameAccumulator = (sourceFrameAccumulator + inputFrames) % (1.0 / ratio);

        inputBuffer.position(inputBuffer.limit());
        outShort.limit(outShort.position());
    }

    @Override
    protected void onFlush() {
        sourceFrameAccumulator = 0.0;
    }

    @Override
    protected void onReset() {
        sourceFrameAccumulator = 0.0;
    }
}