package com.fadcam.ui.faditor.waveform;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.BandedWaveformData;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Feeds the timeline audio rows with shaped quad-band tape data: on first request for a clip it
 * kicks a span-limited {@link BandWaveformExtractor} pass (the "lazy" analysis mode), shapes the
 * result once via {@link BandEnvelopeShaper} using the shared {@link TapeWaveformStyle}, caches
 * it (in-memory LRU over the extractor's own disk cache), and invalidates the view so the tape
 * swaps in. Until then {@link #get} returns {@code null} and the renderer falls back to the
 * legacy/W2 bars.
 *
 * <p>Crossover changes force a re-extract (different filtered data, new cache key); the cheap
 * knobs (contrast/smooth/normalize/colors/lanes/FX) never reach here — those re-shape or
 * re-render from the same cached raw data. State is main-thread only; extractor callbacks hop
 * back via the handler.</p>
 */
public class BandedTimelineWaveformCache {

    private static final String TAG = "BandedTimelineWfCache";
    private static final long SPAN_QUANTUM_MS = 10_000L;
    private static final int MAX_ENTRIES = 32;

    /** Shaped result the renderer draws: raw (for frame timing) + parallel 0..1 profiles. */
    public static final class Shaped {
        @NonNull public final BandedWaveformData raw;
        @NonNull public final float[][] shaped;
        Shaped(@NonNull BandedWaveformData raw, @NonNull float[][] shaped) {
            this.raw = raw;
            this.shaped = shaped;
        }
    }

    public interface InvalidateListener { void onWaveformReady(); }

    private final BandWaveformExtractor extractor;
    private final TapeWaveformStyle style;
    private final InvalidateListener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Map<String, Shaped> ready =
            new LinkedHashMap<String, Shaped>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Shaped> e) {
                    return size() > MAX_ENTRIES;
                }
            };
    private final Set<String> inFlight = new HashSet<>();
    private final Set<String> failed = new HashSet<>();

    public BandedTimelineWaveformCache(@NonNull Context context, @NonNull TapeWaveformStyle style,
                                       @NonNull InvalidateListener listener) {
        this.extractor = new BandWaveformExtractor(context);
        this.style = style;
        this.listener = listener;
    }

    /** Shaped tape for this clip, or {@code null} (kicking a lazy extraction) if not ready. */
    @Nullable
    public Shaped get(@NonNull AudioClip clip) {
        Uri uri = clip.getSourceUri();
        if (uri == null) return null;
        String k = key(clip);
        Shaped s = ready.get(k);
        if (s != null) return s;
        if (inFlight.contains(k) || failed.contains(k)) return null;

        long start = quantStart(clip);
        long end = quantEnd(clip);
        inFlight.add(k);
        extractor.extractAsync(uri, start, end, style.lowHz, style.presHz, style.highHz,
                style.presenceOn, new BandWaveformExtractor.Callback() {
                    @Override
                    public void onReady(@NonNull BandedWaveformData data) {
                        main.post(() -> {
                            inFlight.remove(k);
                            float[][] shaped = BandEnvelopeShaper.shape(data, style.smooth,
                                    style.contrast, style.perBandNormalize);
                            ready.put(k, new Shaped(data, shaped));
                            listener.onWaveformReady();
                        });
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        main.post(() -> {
                            inFlight.remove(k);
                            failed.add(k);
                            FLog.w(TAG, "Band tape extraction failed: " + message);
                        });
                    }
                });
        return null;
    }

    /** Re-shape every cached entry from its raw data (AV4: called when a cheap knob changes). */
    public void reshapeAll() {
        for (Map.Entry<String, Shaped> e : ready.entrySet()) {
            BandedWaveformData raw = e.getValue().raw;
            e.setValue(new Shaped(raw, BandEnvelopeShaper.shape(raw, style.smooth,
                    style.contrast, style.perBandNormalize)));
        }
        listener.onWaveformReady();
    }

    /** Drop all cached shapes (AV4: called when crossovers change → forces re-extract). */
    public void clear() {
        ready.clear();
        inFlight.clear();
        failed.clear();
    }

    private static long quantStart(@NonNull AudioClip clip) {
        return (Math.max(0, clip.getInPointMs()) / SPAN_QUANTUM_MS) * SPAN_QUANTUM_MS;
    }

    private static long quantEnd(@NonNull AudioClip clip) {
        long end = ((clip.getOutPointMs() + SPAN_QUANTUM_MS - 1) / SPAN_QUANTUM_MS) * SPAN_QUANTUM_MS;
        long srcDur = clip.getSourceDurationMs();
        if (srcDur > 0) end = Math.min(end, srcDur);
        return Math.max(end, quantStart(clip) + 1);
    }

    @NonNull
    private String key(@NonNull AudioClip clip) {
        return clip.getSourceUri() + "|" + quantStart(clip) + "-" + quantEnd(clip)
                + "|" + style.lowHz + "-" + style.presHz + "-" + style.highHz
                + (style.presenceOn ? "p" : "");
    }

    public void shutdown() {
        extractor.shutdown();
    }
}
