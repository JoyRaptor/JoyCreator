package com.fadcam.ui.faditor.audio.fx;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Noise gate with adjustable threshold, attack, release, hold.
 */
public final class GateProcessor extends BaseAudioProcessor {

    private float thresholdDb = -40f;
    private float attackMs = 5f;
    private float releaseMs = 100f;
    private float holdMs = 20f;

    private int sampleRate;
    private int channelCount;
    private float[] envelope;
    private int[] holdCounter;

    public GateProcessor() {}

    public void setThresholdDb(float db) { this.thresholdDb = db; }
    public void setAttackMs(float ms) { this.attackMs = Math.max(0.1f, ms); }
    public void setReleaseMs(float ms) { this.releaseMs = Math.max(1f, ms); }
    public void setHoldMs(float ms) { this.holdMs = Math.max(0f, ms); }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        this.sampleRate = inputAudioFormat.sampleRate;
        this.channelCount = inputAudioFormat.channelCount;
        this.envelope = new float[channelCount];
        this.holdCounter = new int[channelCount];
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
        int holdFrames = Math.round(holdMs * sampleRate / 1000.0f);

        ShortBuffer inShort = inputBuffer.asShortBuffer();
        ShortBuffer outShort = output.asShortBuffer();

        int frameCount = inputBuffer.remaining() / (2 * channelCount);

        for (int f = 0; f < frameCount; f++) {
            for (int ch = 0; ch < channelCount; ch++) {
                float x = inShort.get() / 32768.0f;
                float absX = Math.abs(x);

                // Envelope follower
                float coeff = absX > envelope[ch] ? 0f : (float) Math.exp(-1000.0 / (attackMs * sampleRate));
                envelope[ch] = envelope[ch] * coeff + absX * (1 - coeff);

                float gain;
                if (envelope[ch] >= thresholdLin) {
                    // Above threshold: attack (open gate)
                    gain = 1f;
                    holdCounter[ch] = holdFrames;
                } else if (holdCounter[ch] > 0) {
                    // Hold period
                    gain = 1f;
                    holdCounter[ch]--;
                } else {
                    // Below threshold: release (close gate)
                    float coeffR = (float) Math.exp(-1000.0 / (releaseMs * sampleRate));
                    envelope[ch] = envelope[ch] * coeffR;
                    gain = envelope[ch] / Math.max(thresholdLin, 1e-6f);
                }

                float y = x * gain;
                int scaled = Math.round(y * 32767.0f);
                scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
                output.putShort(scaled);
            }
        }

        inputBuffer.position(inputBuffer.limit());
    }

    @Override
    protected void onFlush() {
        if (envelope != null) {
            for (int i = 0; i < envelope.length; i++) envelope[i] = 0f;
        }
        if (holdCounter != null) {
            for (int i = 0; i < holdCounter.length; i++) holdCounter[i] = 0;
        }
    }
}