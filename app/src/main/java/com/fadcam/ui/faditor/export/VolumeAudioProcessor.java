package com.fadcam.ui.faditor.export;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.audio.AudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Audio processor that adjusts volume by scaling PCM samples.
 *
 * <p>Values:
 * <ul>
 *   <li>0.0 = silence</li>
 *   <li>1.0 = original volume</li>
 *   <li>2.0 = 200% (may clip)</li>
 * </ul>
 * Samples are clamped to avoid overflow.</p>
 */
public class VolumeAudioProcessor extends BaseAudioProcessor {

    private float volume = 1.0f;

    /** Optional volume automation envelope (clip-local time ms → gain). */
    private long[] kfTimes;
    private float[] kfVols;

    private int sampleRate;
    private int channelCount;
    /** Running position in audio FRAMES processed so far (one frame = all channels). */
    private long framePosition;

    /**
     * Set the volume multiplier.
     *
     * @param volume 0.0 (silence) to 2.0+ (boost). Clamped at 0.
     */
    public void setVolume(float volume) {
        this.volume = Math.max(0f, volume);
    }

    /**
     * Set a volume automation envelope. {@code times} are clip-local ms (0 = clip start,
     * sorted ascending) and {@code vols} the matching gains. When set (length ≥ 1) the
     * per-sample gain is linearly interpolated between keyframes, overriding
     * {@link #setVolume(float)}. Pass null/empty to disable.
     */
    public void setVolumeEnvelope(long[] times, float[] vols) {
        if (times == null || vols == null || times.length == 0
                || times.length != vols.length) {
            this.kfTimes = null;
            this.kfVols = null;
        } else {
            this.kfTimes = times;
            this.kfVols = vols;
        }
    }

    private float gainAtMs(long ms) {
        if (kfTimes == null) return volume;
        if (kfTimes.length == 1) return kfVols[0];
        if (ms <= kfTimes[0]) return kfVols[0];
        int last = kfTimes.length - 1;
        if (ms >= kfTimes[last]) return kfVols[last];
        for (int i = 0; i < last; i++) {
            if (ms >= kfTimes[i] && ms <= kfTimes[i + 1]) {
                long span = kfTimes[i + 1] - kfTimes[i];
                if (span <= 0) return kfVols[i + 1];
                float frac = (ms - kfTimes[i]) / (float) span;
                return kfVols[i] + (kfVols[i + 1] - kfVols[i]) * frac;
            }
        }
        return kfVols[last];
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        this.sampleRate = inputAudioFormat.sampleRate;
        this.channelCount = inputAudioFormat.channelCount;
        this.framePosition = 0;
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

        boolean enveloped = kfTimes != null;
        int ch = Math.max(1, channelCount);
        int channelIdx = 0;
        float frameGain = enveloped ? gainAtMs(frameToMs(framePosition)) : volume;

        while (inShort.hasRemaining()) {
            int sample = inShort.get();
            int scaled = Math.round(sample * frameGain);
            scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
            outShort.put((short) scaled);

            if (++channelIdx >= ch) {
                channelIdx = 0;
                framePosition++;
                if (enveloped) frameGain = gainAtMs(frameToMs(framePosition));
            }
        }

        inputBuffer.position(inputBuffer.limit());
        output.limit(remaining);
    }

    private long frameToMs(long frame) {
        if (sampleRate <= 0) return 0;
        return (frame * 1000L) / sampleRate;
    }

    @Override
    protected void onFlush() {
        framePosition = 0;
    }

    @Override
    protected void onReset() {
        framePosition = 0;
        kfTimes = null;
        kfVols = null;
    }
}
