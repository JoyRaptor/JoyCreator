package com.fadcam.ui.faditor.audio.fx;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;

import com.fadcam.ui.faditor.waveform.Fft;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * De-esser using spectral gating in the sibilance band (4-8 kHz).
 * Detects sibilance energy via short-window FFT and attenuates that band.
 */
public final class DeEsserProcessor extends BaseAudioProcessor {

    private float thresholdDb = -20f;
    private float maxReductionDb = 12f;
    private int fftSize = 256; // power of 2
    private int hopSize = 128;

    private int sampleRate;
    private int channelCount;
    private float[][] fftBuffer; // [channel][fftSize]
    private float[][] fftReal;
    private float[][] fftImag;
    private int[] writeIndex;
    private float[] window;
    private float[] currentGain; // per-channel smoothed gain

    public DeEsserProcessor() {}

    public void setThresholdDb(float db) { this.thresholdDb = db; }
    public void setMaxReductionDb(float db) { this.maxReductionDb = Math.max(0f, db); }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        this.sampleRate = inputAudioFormat.sampleRate;
        this.channelCount = inputAudioFormat.channelCount;

        this.fftReal = new float[channelCount][fftSize];
        this.fftImag = new float[channelCount][fftSize];
        this.fftBuffer = new float[channelCount][fftSize];
        this.writeIndex = new int[channelCount];
        this.currentGain = new float[channelCount];
        for (int ch = 0; ch < channelCount; ch++) currentGain[ch] = 1f;
        this.window = new float[fftSize];
        for (int i = 0; i < fftSize; i++) {
            window[i] = (float) (0.5 * (1 - Math.cos(2 * Math.PI * i / (fftSize - 1))));
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
                int wi = writeIndex[ch];

                // Accumulate windowed frames per channel
                fftReal[ch][wi] = x * window[wi];
                fftImag[ch][wi] = 0f;
                writeIndex[ch] = (wi + 1) % fftSize;

                if (wi == fftSize - 1) {
                    // Process FFT on full window for this channel
                    Fft.transform(fftReal[ch], fftImag[ch]);

                    // Detect sibilance in 4-8 kHz range
                    int binStart = (int) (4000.0 * fftSize / sampleRate);
                    int binEnd = (int) (8000.0 * fftSize / sampleRate);
                    binEnd = Math.min(binEnd, fftSize / 2);

                    float sibilanceEnergy = 0f;
                    for (int bin = binStart; bin < binEnd; bin++) {
                        float mag = (float) Math.sqrt(fftReal[ch][bin] * fftReal[ch][bin] + fftImag[ch][bin] * fftImag[ch][bin]);
                        sibilanceEnergy += mag;
                    }
                    sibilanceEnergy /= Math.max(1, binEnd - binStart);

                    float thresholdLin = (float) Math.pow(10, thresholdDb / 20.0);
                    float targetGain = 1f;
                    if (sibilanceEnergy > thresholdLin) {
                        float excessDb = 20f * (float) Math.log10(sibilanceEnergy / thresholdLin);
                        float reductionDb = Math.min(maxReductionDb, excessDb);
                        targetGain = (float) Math.pow(10, -reductionDb / 20.0);
                    }
                    // Smooth gain with simple attack/release (fast attack, slower release)
                    float coeff = targetGain < currentGain[ch] ? 0.3f : 0.05f;
                    currentGain[ch] = currentGain[ch] * (1 - coeff) + targetGain * coeff;
                }

                float y = x * currentGain[ch];
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
        // nothing special
    }
}