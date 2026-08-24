package com.fadcam.ui.faditor.audio.fx;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;

import com.fadcam.ui.faditor.waveform.Biquad;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Parametric EQ using cascaded RBJ Biquad filters (peaking/shelving).
 * Uses only public Biquad factory methods (lowpass/highpass/bandChain).
 * Config: array of bands, each with freqHz, gainDb, Q.
 * Preview and export share this processor — same coefficients, same sound.
 */
public final class EqProcessor extends BaseAudioProcessor {

    public static final class Band {
        public final double freqHz;
        public final double gainDb;
        public final double q;

        public Band(double freqHz, double gainDb, double q) {
            this.freqHz = freqHz;
            this.gainDb = gainDb;
            this.q = q;
        }
    }

    private final Band[] bands;
    private int sampleRate;
    private int channelCount;
    private Biquad[][] chains; // [channel][band*2] (two stages per band)

    public EqProcessor(Band[] bands) {
        this.bands = bands;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        this.sampleRate = inputAudioFormat.sampleRate;
        this.channelCount = inputAudioFormat.channelCount;

        // Build filter chains using Biquad.bandChain for peaking EQ
        // bandChain kind=1 (voice) creates HP+LP pair which approximates peaking
        // For proper peaking EQ, we'd need custom coefficients, but for the engine
        // proof we use bandChain's voice band as a peaking-ish filter at freqHz
        chains = new Biquad[channelCount][];
        for (int ch = 0; ch < channelCount; ch++) {
            java.util.ArrayList<Biquad> chain = new java.util.ArrayList<>();
            for (Band b : bands) {
                chain.add(Biquad.peaking(sampleRate, b.freqHz, b.gainDb, b.q));
            }
            chains[ch] = chain.toArray(new Biquad[0]);
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
                for (Biquad bq : chains[ch]) {
                    if (bq != null) y = bq.process(y);
                }
                int scaled = Math.round(y * 32767.0f);
                scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
                outShort.put((short) scaled);
            }
        }

        inputBuffer.position(inputBuffer.limit());
        // ShortBuffer views have position/limit INDEPENDENT of the parent ByteBuffer:
        // the consumer reads getOutput() from position to PARENT limit, so it must be
        // set to exactly the bytes written (replaceOutputBuffer reuses its internal
        // buffer, so a smaller-than-before call would otherwise ship stale samples).
        output.limit(outShort.position() * 2);
    }

    @Override
    protected void onFlush() {
        if (chains != null) {
            for (Biquad[] chain : chains) {
                if (chain != null) for (Biquad bq : chain) if (bq != null) bq.reset();
            }
        }
    }

    @Override
    protected void onReset() {
        onFlush();
    }
}