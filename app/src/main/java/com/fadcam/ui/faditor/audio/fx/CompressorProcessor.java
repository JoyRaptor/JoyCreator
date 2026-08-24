package com.fadcam.ui.faditor.audio.fx;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Dynamic range compressor with adjustable threshold, ratio, attack, release.
 * Simple feed-forward design with envelope follower.
 */
public final class CompressorProcessor extends BaseAudioProcessor {

    private float thresholdDb = -18f;
    private float ratio = 3f;
    private float attackMs = 20f;
    private float releaseMs = 250f;
    private float makeupGainDb = 0f;

    private int sampleRate;
    private int channelCount;
    private float[] envelope; // per-channel

    public CompressorProcessor() {}

    public void setThresholdDb(float db) { this.thresholdDb = db; }
    public void setRatio(float r) { this.ratio = Math.max(1f, r); }
    public void setAttackMs(float ms) { this.attackMs = Math.max(0.1f, ms); }
    public void setReleaseMs(float ms) { this.releaseMs = Math.max(1f, ms); }
    public void setMakeupGainDb(float db) { this.makeupGainDb = db; }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        this.sampleRate = inputAudioFormat.sampleRate;
        this.channelCount = inputAudioFormat.channelCount;
        this.envelope = new float[channelCount];
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) return;

        ByteBuffer output = replaceOutputBuffer(remaining);
        output.order(ByteOrder.nativeOrder());

        ShortBuffer inShort = inputBuffer.asShortBuffer();
        ShortBuffer outShort = output.asShortBuffer();

        float attackCoeff = (float) Math.exp(-1000.0 / (attackMs * sampleRate));
        float releaseCoeff = (float) Math.exp(-1000.0 / (releaseMs * sampleRate));
        float thresholdLin = (float) Math.pow(10, thresholdDb / 20.0);
        float makeupGain = (float) Math.pow(10, makeupGainDb / 20.0);

        int frameCount = remaining / (2 * channelCount);

        for (int f = 0; f < frameCount; f++) {
            for (int ch = 0; ch < channelCount; ch++) {
                float x = inShort.get() / 32768.0f;
                float absX = Math.abs(x);

                // Envelope follower
                float target = absX;
                float coeff = target > envelope[ch] ? attackCoeff : releaseCoeff;
                envelope[ch] = envelope[ch] * coeff + target * (1 - coeff);

                // Compute gain reduction
                float gain = 1f;
                if (envelope[ch] > thresholdLin) {
                    float excessDb = 20f * (float) Math.log10(envelope[ch] / thresholdLin);
                    float reductionDb = excessDb * (1 - 1f / ratio);
                    gain = (float) Math.pow(10, -reductionDb / 20.0);
                }

                float y = x * gain * makeupGain;
                int scaled = Math.round(y * 32767.0f);
                scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
                outShort.put((short) scaled);
            }
        }

        inputBuffer.position(inputBuffer.limit());
        // Parent-buffer limit must be set explicitly (see EqProcessor note).
        output.limit(outShort.position() * 2);
    }

    @Override
    protected void onFlush() {
        if (envelope != null) {
            for (int i = 0; i < envelope.length; i++) envelope[i] = 0f;
        }
    }
}