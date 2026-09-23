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
 *
 * <p>Correctness across queueInput calls matters: the export pipeline hands the
 * processor arbitrary buffer sizes, so the fractional input-frame position is
 * carried between calls via {@link #carry} and the previous chunk's last frame is
 * retained in {@link #tail} as interpolation partner. Without both, every chunk
 * boundary injects a phase glitch (audible as periodic pitch wobble) and the
 * output length drifts with chunk count — proven by ResampleTest before this
 * fix, which measured a 997-frame-chunked feed at 13044 output frames vs 12000
 * monolithic for a 0.25 s input.</p>
 */
public class ResamplingAudioProcessor extends BaseAudioProcessor {

    private final int sourceSampleRate;
    private final int targetSampleRate;
    private final double step; // input frames per output frame = source / target

    private int channelCount;
    /** Fractional input-frame position of the NEXT output sample, relative to {@link #tail}. */
    private double carry;
    /** Last input frame of the previously queued chunk (interpolation partner), null at stream start. */
    private short[] tail;
    private boolean endHandled;
    /**
     * 2026-09-22 drift fix: exact-length accounting. The end-of-stream tail used to emit
     * up to ~2 frames of fade-to-silence per ITEM; over a 19-item export that gained
     * ~477 ms of audio with no video (measured: audio 1835.096 s vs video 1834.619 s on
     * the 30:35 partial — ~1 AAC frame per item). Counting input vs emitted frames lets
     * the tail emit exactly what the stream is owed — no more, no less — so per-item
     * rounding can no longer accumulate along the timeline.
     */
    private long totalInputFrames;
    private long emittedFrames;

    public ResamplingAudioProcessor(int sourceSampleRate, int targetSampleRate) {
        if (sourceSampleRate <= 0 || targetSampleRate <= 0) {
            throw new IllegalArgumentException("Sample rates must be positive");
        }
        this.sourceSampleRate = sourceSampleRate;
        this.targetSampleRate = targetSampleRate;
        this.step = (double) sourceSampleRate / targetSampleRate;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        this.channelCount = inputAudioFormat.channelCount;
        this.carry = 0.0;
        this.tail = null;
        this.endHandled = false;
        this.totalInputFrames = 0L;
        this.emittedFrames = 0L;
        // Output format has the target sample rate; channel count and encoding unchanged.
        return new AudioFormat(targetSampleRate, channelCount, inputAudioFormat.encoding);
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0 || endHandled) return;

        ShortBuffer inShort = inputBuffer.asShortBuffer();
        int frames = remaining / (2 * channelCount); // 2 bytes per sample (16-bit)
        totalInputFrames += frames;
        boolean hasTail = tail != null;
        // Effective stream: [tail] ++ current chunk; index 0 is tail when present.
        int effFrames = frames + (hasTail ? 1 : 0);

        int maxOutFrames = (int) (effFrames / step) + 2;
        ByteBuffer output = replaceOutputBuffer(maxOutFrames * channelCount * 2);
        output.order(ByteOrder.nativeOrder());
        ShortBuffer outShort = output.asShortBuffer();

        double pos = carry;
        // Need both interpolation partners inside the stream: the LAST usable input
        // position is effFrames-2 (its partner is effFrames-1). Equality with the last
        // frame must be carried, not interpolated against a phantom zero.
        while (pos < effFrames - 1) {
            int i0 = (int) pos;
            double frac = pos - i0;
            for (int ch = 0; ch < channelCount; ch++) {
                short s0 = frameAt(i0, hasTail, inShort, frames, ch);
                short s1 = frameAt(i0 + 1, hasTail, inShort, frames, ch);
                int interpolated = (int) Math.round(s0 + frac * (s1 - s0));
                interpolated = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, interpolated));
                outShort.put((short) interpolated);
            }
            pos += step;
        }

        // Retain the last effective frame as the next chunk's interpolation partner.
        tail = new short[channelCount];
        for (int ch = 0; ch < channelCount; ch++) {
            tail[ch] = frameAt(effFrames - 1, hasTail, inShort, frames, ch);
        }
        // Positions beyond effFrames-1 are expressed relative to that retained frame.
        carry = pos - (effFrames - 1);

        inputBuffer.position(inputBuffer.limit());
        // CRITICAL: a ShortBuffer view's position/limit are INDEPENDENT of the parent
        // ByteBuffer — outShort.limit(...) does NOT propagate. The consumer reads
        // getOutput() from position to limit, so the PARENT must be limited to exactly
        // the bytes written, or every drained buffer carries capacity-sized garbage
        // (and buffer reuse in replaceOutputBuffer re-ships stale samples).
        output.limit(outShort.position() * 2);
        emittedFrames += outShort.position();
    }

    /** Sample ch of effective-stream index i; index 0 is the retained tail, negatives/overflow read as 0. */
    private short frameAt(int i, boolean hasTail, ShortBuffer cur, int curFrames, int ch) {
        if (hasTail) {
            if (i == 0) return tail[ch];
            i -= 1;
        }
        if (i >= 0 && i < curFrames) return cur.get(i * channelCount + ch);
        return 0;
    }

    @Override
    protected void onQueueEndOfStream() {
        // queueEndOfStream() is final in this media3 tree and its default hook is a no-op,
        // so the resampler owns its tail.
        //
        // 2026-09-22 exact-length fix: the old code emitted every output position up to
        // one step past the retained last frame — up to ~2 invented fade-to-silence
        // frames per ITEM. Over a 19-item export that gained ~477 ms of audio with no
        // video (audio 1835.096 s vs video 1834.619 s on the 30:35 partial, ~1 AAC frame
        // per item). Now the tail emits exactly what the stream is owed —
        // round(totalIn / step) minus what went out — and nothing when owed <= 0, so
        // per-item rounding can no longer accumulate along the timeline.
        if (endHandled || channelCount == 0) return;
        endHandled = true;

        long expectedTotal = Math.round(totalInputFrames / step);
        long owed = expectedTotal - emittedFrames;
        // Never invent more than the old code could have (≈2 frames); the min() with
        // owed is what stops the accumulation, the cap is belt-and-suspenders.
        long toEmit = Math.max(0L, Math.min(owed, 2L));

        int maxOutFrames = (int) toEmit + 1;
        ByteBuffer output = replaceOutputBuffer(maxOutFrames * channelCount * 2);
        output.order(ByteOrder.nativeOrder());
        ShortBuffer outShort = output.asShortBuffer();

        double pos = carry;
        long emitted = 0;
        while (emitted < toEmit && pos <= 1.0 + 1e-9) { // 0 = tail, 1 = implicit silence
            int i0 = (int) pos;
            double frac = pos - i0;
            for (int ch = 0; ch < channelCount; ch++) {
                short s0 = (i0 == 0 && tail != null) ? tail[ch] : 0;
                short s1 = 0;
                int interpolated = (int) Math.round(s0 + frac * (s1 - s0));
                interpolated = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, interpolated));
                outShort.put((short) interpolated);
            }
            pos += step;
            emitted++;
        }
        emittedFrames += outShort.position();
        tail = null;
        carry = 0.0;
        output.position(0);
        output.limit(outShort.position() * 2);
    }

    @Override
    protected void onFlush() {
        carry = 0.0;
        tail = null;
        endHandled = false;
    }

    @Override
    protected void onReset() {
        carry = 0.0;
        tail = null;
        endHandled = false;
    }
}
