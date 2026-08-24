package com.fadcam.ui.faditor.audio.fx;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Brick-wall limiter with lookahead (via short delay buffer) and soft knee.
 * Catches peaks above ceiling.
 */
public final class LimiterProcessor extends BaseAudioProcessor {

    private float ceilingDb = -1f;
    private float releaseMs = 50f;

    private int sampleRate;
    private int channelCount;
    private float[] envelope;
    private float[] delayBuffer;
    private int delayIndex = 0;
    private int delayFrames = 10; // ~2ms at 48kHz

    public LimiterProcessor() {}

    public void setCeilingDb(float db) { this.ceilingDb = db; }
    public void setReleaseMs(float ms) { this.releaseMs = Math.max(1f, ms); }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        this.sampleRate = inputAudioFormat.sampleRate;
        this.channelCount = inputAudioFormat.channelCount;
        this.envelope = new float[channelCount];
        this.delayBuffer = new float[channelCount * delayFrames];
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

        float releaseCoeff = (float) Math.exp(-1000.0 / (releaseMs * sampleRate));
        float ceilingLin = (float) Math.pow(10, ceilingDb / 20.0);

        int frameCount = remaining / (2 * channelCount);

        for (int f = 0; f < frameCount; f++) {
            for (int ch = 0; ch < channelCount; ch++) {
                float x = inShort.get() / 32768.0f;
                float absX = Math.abs(x);

                // Delay line for lookahead
                int delayIdx = delayIndex * channelCount + ch;
                float delayed = delayBuffer[delayIdx];
                delayBuffer[delayIdx] = x;

                // Envelope on delayed signal
                float target = Math.abs(delayed);
                float coeff = target > envelope[ch] ? 0f : (float) Math.exp(-1000.0 / (releaseMs * sampleRate));
                envelope[ch] = envelope[ch] * coeff + target * (1 - coeff);

                float gain = 1f;
                if (envelope[ch] > ceilingLin) {
                    gain = ceilingLin / envelope[ch];
                }

                float y = delayed * gain;
                int scaled = Math.round(y * 32767.0f);
                scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
                outShort.put((short) scaled);
            }
            delayIndex = (delayIndex + 1) % delayFrames;
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
        if (delayBuffer != null) {
            for (int i = 0; i < delayBuffer.length; i++) delayBuffer[i] = 0f;
        }
        delayIndex = 0;
    }
}