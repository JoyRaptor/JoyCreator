package com.fadcam.ui.faditor.export;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.audio.AudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Audio processor that adjusts volume and stereo pan by scaling PCM samples.
 *
 * <p>Values:
 * <ul>
 *   <li>0.0 = silence</li>
 *   <li>1.0 = original volume</li>
 *   <li>2.0 = 200% (may clip)</li>
 * </ul>
 * Samples are clamped to avoid overflow.
 *
 * <p>Stereo pan: -1.0 = full left, 0.0 = center (no-op), +1.0 = full right.
 * Uses equal-power law: at center both channels are 1.0 (no-op); at full L/R
 * the active channel is sqrt(2) so power is constant. This matches the preview
 * panning so export and preview change together.
 */
public class VolumeAudioProcessor extends BaseAudioProcessor {

    private float volume = 1.0f;
    private float pan = 0.0f;

    /** Optional volume automation envelope (clip-local time ms → gain). */
    private long[] kfTimes;
    private float[] kfVols;
    /**
     * Clip-local time of this item's first sample. 0 for a clip that plays from its start;
     * after a transition the export starts the clip part-way in (its head plays under the
     * blend), and the curve must be read from there or it runs early by the transition.
     */
    private long envelopeStartMs;

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
     * Set the stereo pan position.
     *
     * @param pan -1.0 (full left) to +1.0 (full right). 0.0 = center (no-op).
     */
    public void setPan(float pan) {
        this.pan = Math.max(-1f, Math.min(1f, pan));
    }

    /**
     * Set a volume automation envelope. {@code times} are clip-local ms (0 = clip start,
     * sorted ascending) and {@code vols} the matching MULTIPLIERS over the static
     * {@link #setVolume(float) volume} (B1.Q): the per-sample gain is
     * {@code volume × linearly-interpolated multiplier}, so a fade drawn at 50% level
     * rescales when the slider moves instead of capping the clip at 100%. Callers MUST set
     * the static volume too, even on the enveloped path — it is the base the multipliers
     * scale. Pass null/empty to disable.
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

    /**
     * Delegates interpolation to {@link com.fadcam.ui.faditor.model.VolumeEnvelope} — the
     * curve moved to the model when the live preview gained an envelope too, so the file
     * and the preview cannot fade at different rates — then scales by the static volume
     * (B1.Q multiplier contract; flat base 1 here because the multiply happens outside).
     */
    private float gainAtMs(long ms) {
        return volume * com.fadcam.ui.faditor.model.VolumeEnvelope.gainAt(
                kfTimes, kfVols, envelopeStartMs + streamOriginMs + ms, 1f);
    }

    /** Where on the clip's own clock this item starts (see {@link #envelopeStartMs}). */
    public void setEnvelopeStartMs(long clipLocalMs) {
        this.envelopeStartMs = Math.max(0L, clipLocalMs);
    }

    /**
     * Where the stream resumes after a flush, for a player that flushes mid-clip.
     *
     * <p>Export never needs this: each item plays once from its start. The PREVIEW player flushes
     * on every seek AND every time its drift correction nudges the speed (Media3 drains and
     * flushes the processors to apply new playback parameters), and a flush used to put the
     * curve back to the clip's first millisecond: a fade-out heard from the middle of a clip
     * started over from full (Codex round 2, lead W1).</p>
     */
    public interface StreamClock {
        /** The clip-local time, in µs, of the next input after a flush; {@code C.TIME_UNSET} when unknown. */
        long nextInputClipUs();
    }

    @androidx.annotation.Nullable private volatile StreamClock streamClock;
    /** Clip-local ms of the first frame since the last flush (0 until a {@link StreamClock} says otherwise). */
    private long streamOriginMs;
    private boolean resyncPending;

    /** Preview only; see {@link StreamClock}. */
    public void setStreamClock(@androidx.annotation.Nullable StreamClock clock) {
        this.streamClock = clock;
    }

    /**
     * Per-channel PAN gains (no volume, no envelope): equal-power law, centre = 1.0 on both
     * (a true no-op), full L = (√2, 0), full R = (0, √2). Mono gets {1}.
     *
     * <p>One entry PER CHANNEL. It used to return two for every layout but mono, and the loop
     * indexes it by channel, so a 5.1 file threw on channel 2 (Codex round 2, R2-AUD-03).
     * Pan moves the FRONT pair (channels 0 and 1 in Android's PCM order: FL, FR, FC, LFE,
     * BL, BR); the other channels keep unity, so centre, sub and surrounds are not panned
     * away. The volume and the envelope apply to every channel alike.</p>
     */
    static float[] panGains(int channelCount, float pan) {
        int ch = Math.max(1, channelCount);
        float[] g = new float[ch];
        java.util.Arrays.fill(g, 1f);
        if (ch == 1) return g;
        // Equal-power: angle = (pan + 1) * pi/4. pan=-1 -> 0 (cos=1,sin=0). pan=0 -> pi/4 (cos=sin=√2/2). pan=1 -> pi/2 (cos=0,sin=1).
        // Multiply by √2 so center = 1.0 (no-op), full L/R = √2.
        double angle = (pan + 1.0) * Math.PI / 4.0;
        double sqrt2 = Math.sqrt(2.0);
        g[0] = (float) (Math.cos(angle) * sqrt2);
        g[1] = (float) (Math.sin(angle) * sqrt2);
        return g;
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

        if (resyncPending) {
            resyncPending = false;
            StreamClock clock = streamClock;
            long us = clock != null ? clock.nextInputClipUs() : C.TIME_UNSET;
            streamOriginMs = (us == C.TIME_UNSET || us < 0) ? 0L : us / 1000L;
        }

        boolean enveloped = kfTimes != null;
        int ch = Math.max(1, channelCount);
        int channelIdx = 0;

        // Pan per channel, times ONE gain per frame (volume, or volume x the curve) for every
        // channel. Stereo used to multiply by the static pan-and-volume pair alone, so a fade
        // or a volume curve on a stereo song did nothing; only mono followed it (R2-AUD-01).
        float[] pans = panGains(ch, pan);
        float frameGain = enveloped ? gainAtMs(frameToMs(framePosition)) : volume;

        // Bulk arrays instead of a ShortBuffer call per sample (export speed, 2026-09-24: the
        // sound pass was CPU-bound on per-sample buffer access). Same arithmetic, same order.
        short[] samples = new short[inShort.remaining()];
        inShort.get(samples);
        for (int i = 0; i < samples.length; i++) {
            int sample = samples[i];
            float gain = pans[channelIdx] * frameGain;
            int scaled = Math.round(sample * gain);
            scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
            samples[i] = (short) scaled;

            if (++channelIdx >= ch) {
                channelIdx = 0;
                framePosition++;
                if (enveloped) frameGain = gainAtMs(frameToMs(framePosition));
            }
        }
        outShort.put(samples);

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
        // Without a clock this is the old behaviour exactly: the next input is the item's start.
        streamOriginMs = 0L;
        resyncPending = streamClock != null;
    }

    @Override
    protected void onReset() {
        framePosition = 0;
        streamOriginMs = 0L;
        resyncPending = false;
        kfTimes = null;
        kfVols = null;
    }
}