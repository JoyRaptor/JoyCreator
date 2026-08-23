package com.fadcam.ui.faditor.audio.fx;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;

import com.fadcam.ui.faditor.waveform.Biquad;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * De-hum: notch filter at 50/60 Hz (and harmonics) using Biquad notch.
 */
public final class DeHumProcessor extends BaseAudioProcessor {

    private int humFreqHz = 50; // or 60
    private float q = 30f; // very narrow notch

    private int sampleRate;
    private int channelCount;
    private Biquad[][] notches; // [channel][harmonic]

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
        this.notches = new Biquad[channelCount][harmonics];
        for (int ch = 0; ch < channelCount; ch++) {
            for (int h = 1; h <= 3; h++) {
                double freq = humFreqHz * h;
                if (freq < sampleRate / 2.0 - 10) {
                    double w0 = 2 * Math.PI * freq / sampleRate;
                    double cos = Math.cos(w0), sin = Math.sin(w0);
                    double alpha = sin / (2 * q);
                    // Notch filter: b0=b2=1, b1=-2cos(w0), a0=1+alpha, a1=-2cos, a2=1-alpha
                    notches[ch][h-1] = new Biquad(
                            1, -2 * cos, 1,
                            1 + alpha, -2 * cos, 1 - alpha
                    );
                } else {
                    notches[ch][h-1] = null;
                }
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
                for (Biquad notch : notches[ch]) {
                    if (notch != null) y = notch.process(y);
                }
                int scaled = Math.round(y * 32767.0f);
                scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
                outShort.put((short) scaled);
            }
        }

        inputBuffer.position(inputBuffer.limit());
        outShort.limit(outShort.position());
    }

    @Override
    protected void onFlush() {
        if (notches != null) {
            for (Biquad[] chain : notches) {
                if (chain != null) for (Biquad bq : chain) if (bq != null) bq.reset();
            }
        }
    }

    @Override
    protected void onReset() {
        onFlush();
    }
}