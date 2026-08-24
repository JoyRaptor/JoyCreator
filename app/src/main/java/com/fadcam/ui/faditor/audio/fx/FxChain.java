package com.fadcam.ui.faditor.audio.fx;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * FX Chain: composes multiple AudioProcessors in series.
 * Shared by preview and export so effects are identical in both.
 * 
 * Order: EQ → Compressor → Limiter → Gate → DeEsser → DeHum (customizable)
 */
public final class FxChain extends BaseAudioProcessor {

    private final List<AudioProcessor> processors = new ArrayList<>();
    private int sampleRate;
    private int channelCount;

    /**
     * C7 — A/B bypass, INJECTED like every other audio setting (volume, pan): the owner
     * of the chain sets it at build time and may flip it live. The engine NEVER reads UI
     * state directly — export runs in a service where a UI static is both a layering
     * violation and a stale-after-process-death hazard, and the DSP classes must stay
     * compilable by run-audio-fx.sh's stub classpath.
     */
    private volatile boolean bypassed = false;

    public FxChain() {}

    /** @param bypassed initial C7 A/B state — an INJECTED setting, see {@link #setBypassed}. */
    public FxChain(boolean bypassed) {
        this.bypassed = bypassed;
    }

    /** Set the A/B bypass state. Safe to flip mid-stream: read per buffer. */
    public void setBypassed(boolean bypassed) {
        this.bypassed = bypassed;
    }

    public boolean isBypassed() {
        return bypassed;
    }

    /**
     * C6 — the live gain reduction the chain's compressor last applied, in dB
     * (0 = none, negative = amount pushed down), or {@code Float.NaN} when the chain has
     * no compressor. Read by the wiring layer to feed the drawer's GR bar; the direction
     * of knowledge stays engine → caller, never engine → UI class.
     */
    public float getGainReductionDb() {
        for (AudioProcessor p : processors) {
            if (p instanceof CompressorProcessor) {
                return ((CompressorProcessor) p).getGainReductionDb();
            }
        }
        return Float.NaN;
    }

    /** Add a processor to the end of the chain. */
    public void addProcessor(AudioProcessor p) {
        processors.add(p);
    }

    /** Remove all processors. */
    public void clear() {
        processors.clear();
    }

    /** Get the current chain (unmodifiable). */
    public List<AudioProcessor> getProcessors() {
        return java.util.Collections.unmodifiableList(processors);
    }

    /** Configure a standard "Fix Audio" chain: HP80 → DeEsser → Compressor → Limiter. */
    public static FxChain createFixChain(int sampleRate) {
        return createFixChain(sampleRate, false);
    }

    /** @see #createFixChain(int) */
    public static FxChain createFixChain(int sampleRate, boolean bypassed) {
        FxChain chain = new FxChain(bypassed);
        chain.addProcessor(new EqProcessor(new EqProcessor.Band[]{new EqProcessor.Band(80, -6, 0.707)})); // highpass ~80 Hz
        chain.addProcessor(new DeEsserProcessor());
        chain.addProcessor(new CompressorProcessor());
        chain.addProcessor(new LimiterProcessor());
        return chain;
    }

    /** Configure a standard "Voice" chain: HP100 → Gate → EQ (voice) → Compressor → DeEsser → Limiter. */
    public static FxChain createVoiceChain(int sampleRate) {
        return createVoiceChain(sampleRate, false);
    }

    /** @see #createVoiceChain(int) */
    public static FxChain createVoiceChain(int sampleRate, boolean bypassed) {
        FxChain chain = new FxChain(bypassed);
        chain.addProcessor(new EqProcessor(new EqProcessor.Band[]{new EqProcessor.Band(100, -6, 0.707)})); // highpass
        chain.addProcessor(new GateProcessor());
        chain.addProcessor(new EqProcessor(new EqProcessor.Band[]{new EqProcessor.Band(3000, 3, 1.0)})); // presence boost
        chain.addProcessor(new CompressorProcessor());
        chain.addProcessor(new DeEsserProcessor());
        chain.addProcessor(new LimiterProcessor());
        return chain;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        this.sampleRate = inputAudioFormat.sampleRate;
        this.channelCount = inputAudioFormat.channelCount;

        // Configure all processors with the input format
        for (AudioProcessor p : processors) {
            p.configure(inputAudioFormat);
        }
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) return;

        // C7: A/B bypass — passthrough without touching processors so preview and
        // export both hear the untouched mix. Read PER BUFFER off the injected flag so a
        // flip is audible immediately (no rebuild).
        if (bypassed) {
            ByteBuffer o = replaceOutputBuffer(remaining);
            o.order(ByteOrder.nativeOrder());
            o.put(inputBuffer);
            inputBuffer.position(inputBuffer.limit());
            o.flip();
            return;
        }

        // Chain processors: output of one feeds into the next. getOutput() SWAPS in
        // EMPTY_BUFFER, so each processor is drained EXACTLY ONCE here — calling it
        // again (e.g. via processors.get(size-1).getOutput() afterwards) returns an
        // empty buffer and the chain would emit silence for every non-empty input.
        ByteBuffer out = inputBuffer;
        for (AudioProcessor p : processors) {
            if (out.remaining() == 0) break;
            p.queueInput(out);
            out = p.getOutput();
        }

        ByteBuffer o = replaceOutputBuffer(out.remaining());
        o.order(ByteOrder.nativeOrder());
        o.put(out);
        // put() leaves position at the end; flip() sets position=0 / limit=n so the
        // consumer draining getOutput() actually reads the bytes just written.
        o.flip();
    }

    @Override
    protected void onFlush() {
        for (AudioProcessor p : processors) p.flush();
    }

    @Override
    protected void onReset() {
        for (AudioProcessor p : processors) p.reset();
    }
}