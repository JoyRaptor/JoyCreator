package com.fadcam.ui.faditor.audio.fx;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * De-hum: notch filter at 50/60 Hz (and harmonics) using direct-form IIR notch.
 * Does not depend on Biquad (private constructor) — computes notch coefficients directly.
 */
public final class DeHumProcessor extends BaseAudioProcessor {

    private int humFreqHz = 50; // or 60
    private float q = 30f; // very narrow notch

    private int sampleRate;
    private int channelCount;
    private float[][] notchState; // [channel][harmonic][4] for x1,x2,y1,y2
    private float[][] notchCoeffs; // [harmonic][5] for b0,b1,b2,a1,a2

    public DeHumProcessor() {}

    public void setHumFreqHz(int hz) { this.humFreqHz = Math.max(40, Math.min(80, hz)); }
    public void setQ(float q) { this.q = Math.max(10f, Math.min(100f, q)); }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        this.sampleRate = inputAudioFormat.sampleRate;
        this.channelCount = inputAudioFormat.channelCount;

        // Notch at fundamental + 2-3 harmonics
        int harmonics = 3;
        this.notchState = new float[channelCount][harmonics * 4]; // x1,x2,y1,y2 per notch
        this.notchCoeffs = new float[harmonics][5]; // b0,b1,b2,a1,a2 per notch

        for (int h = 1; h <= 3; h++) {
            double freq = humFreqHz * h;
            if (freq < sampleRate / 2.0 - 10) {
                double w0 = 2 * Math.PI * freq / sampleRate;
                double cos = Math.cos(w0), sin = Math.sin(w0);
                double alpha = sin / (2 * q);
                // Notch: b0=b2=1, b1=-2cos(w0), a0=1+alpha, a1=-2cos, a2=1-alpha
                // Normalize by a0:
                float a0 = (float)(1 + alpha);
                notchCoeffs[h-1][0] = 1f / a0;           // b0 = 1/a0
                notchCoeffs[h-1][1] = (float)(-2 * cos) / a0; // b1
                notchCoeffs[h-1][2] = 1f / a0;           // b2 = 1/a0
                notchCoeffs[h-1][3] = (float)(-2 * cos) / a0; // a1
                notchCoeffs[h-1][4] = (1 - (float)alpha) / a0; // a2
            } else {
                notchCoeffs[h-1][0] = 0f; // mark as unused
            }
        }
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

        int frameCount = remaining / (2 * channelCount);

        for (int f = 0; f < frameCount; f++) {
            for (int ch = 0; ch < channelCount; ch++) {
                float x = inShort.get() / 32768.0f;
                float y = x;
                for (int h = 0; h < 3; h++) {
                    float[] coeff = notchCoeffs[h];
                    if (coeff[0] == 0f) continue;
                    float b0 = coeff[0], b1 = coeff[1], b2 = coeff[2];
                    float a1 = coeff[3], a2 = coeff[4];
                    int sb = h * 4;
                    float x1 = notchState[ch][sb];
                    float x2 = notchState[ch][sb + 1];
                    float y1 = notchState[ch][sb + 2];
                    float y2 = notchState[ch][sb + 3];
                    // Direct Form II: y = b0*x + b1*x1 + b2*x2 - a1*y1 - a2*y2
                    float yn = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
                    notchState[ch][sb] = x;      // x1 = x
                    notchState[ch][sb + 1] = x1; // x2 = x1
                    notchState[ch][sb + 2] = yn; // y1 = yn
                    notchState[ch][sb + 3] = y1; // y2 = y1
                    x = yn;
                }
                y = x;
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
        if (notchState != null) {
            for (float[] chState : notchState) {
                for (int i = 0; i < chState.length; i++) chState[i] = 0f;
            }
        }
    }

    @Override
    protected void onReset() {
        onFlush();
    }
}