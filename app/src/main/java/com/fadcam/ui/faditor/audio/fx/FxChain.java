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

    public FxChain() {}

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
        FxChain chain = new FxChain();
        chain.addProcessor(new EqProcessor(new EqProcessor.Band[]{new EqProcessor.Band(80, -6, 0.707)})); // highpass ~80 Hz
        chain.addProcessor(new DeEsserProcessor());
        chain.addProcessor(new CompressorProcessor());
        chain.addProcessor(new LimiterProcessor());
        return chain;
    }

    /** Configure a standard "Voice" chain: HP100 → Gate → EQ (voice) → Compressor → DeEsser → Limiter. */
    public static FxChain createVoiceChain(int sampleRate) {
        FxChain chain = new FxChain();
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

        // Chain processors: output of one feeds into next
        ByteBuffer currentInput = inputBuffer;
        for (AudioProcessor p : processors) {
            if (currentInput.remaining() == 0) break;
            p.queueInput(currentInput);
            currentInput = p.getOutput();
        }

        // Final output goes to our output buffer
        ByteBuffer finalOutput = processors.isEmpty() ? inputBuffer : processors.get(processors.size() - 1).getOutput();
        if (finalOutput != null && finalOutput.remaining() > 0) {
            ByteBuffer out = replaceOutputBuffer(finalOutput.remaining());
            out.order(ByteOrder.nativeOrder());
            out.put(finalOutput);
        }
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