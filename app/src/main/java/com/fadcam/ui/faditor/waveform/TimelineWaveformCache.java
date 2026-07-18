package com.fadcam.ui.faditor.waveform;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.WaveformData;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * W2 (timeline fidelity): zoom-tiered, span-limited waveform data for the timeline AUDIO
 * rows, backed by {@link WaveformExtractor} — the SAME pipeline the visualizer uses, so
 * there is no third extraction path. The legacy persisted {@code AudioClip.waveform}
 * (fixed 800 samples for the whole clip, ~13 samples/sec on a 60s clip) stays as the
 * instant placeholder + back-compat representation; once a tier extraction lands here the
 * renderer draws from it instead and word/onset detail resolves under zoom.
 *
 * <p>Design points:
 * <ul>
 *   <li><b>Tiers, not continuous density</b> — {@link #TIER_MID}/{@link #TIER_HIGH}
 *       buckets/sec chosen from the item's on-screen px-per-second, so zooming doesn't
 *       re-extract per frame; each tier is extracted once and disk-cached by the
 *       extractor.</li>
 *   <li><b>Span-limited</b> — only the clip's used source window is decoded, QUANTIZED
 *       outward to {@link #SPAN_QUANTUM_MS} boundaries so trim adjustments keep hitting
 *       the same cache entry instead of respawning extractions per drag.</li>
 *   <li><b>Amplitude-only</b> — the timeline bars never read the FFT spectrum, so it is
 *       skipped entirely (at 400 buckets/sec the FFT would dominate decode cost).</li>
 *   <li><b>Main-thread state</b> — {@link #get} is called from the render pass; all maps
 *       are touched on the main thread only (extractor callbacks hop via the handler).</li>
 * </ul></p>
 */
public class TimelineWaveformCache {

    private static final String TAG = "TimelineWaveformCache";

    /** Timeline bar density tiers, in extractor buckets/sec (W2: 200–400 for HD zoom). */
    public static final int TIER_MID = 200;
    public static final int TIER_HIGH = 400;

    /** px/sec thresholds above which each tier engages (below TIER_MID's = legacy bars). */
    private static final float PX_PER_SEC_MID = 50f;
    private static final float PX_PER_SEC_HIGH = 150f;

    /** Extraction spans snap outward to this quantum so trims re-use cache entries. */
    private static final long SPAN_QUANTUM_MS = 10_000L;

    /** Max ready entries kept in memory (LRU). A 60s span at 400 buckets/sec is ~100 KB. */
    private static final int MAX_ENTRIES = 48;

    /** Fired on the main thread when a requested extraction becomes drawable. */
    public interface InvalidateListener {
        void onWaveformReady();
    }

    private final WaveformExtractor extractor;
    private final InvalidateListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Map<String, WaveformData> ready =
            new LinkedHashMap<String, WaveformData>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, WaveformData> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };
    private final Set<String> inFlight = new HashSet<>();
    /** Sources that failed to extract — never re-request (avoids a retry storm per frame). */
    private final Set<String> failed = new HashSet<>();
    /** F3c (PERF_SPEC_LONGFILE_20260718): while true, serve ready data but kick no NEW
     *  extractions — decodes racing live playback starve both. See BandedTimelineWaveformCache. */
    private boolean suspended = false;

    /** F3c: gate NEW extraction kicks (in-flight ones keep running). */
    public void setSuspended(boolean s) {
        this.suspended = s;
    }

    public TimelineWaveformCache(@NonNull Context context, @NonNull InvalidateListener listener) {
        this.extractor = new WaveformExtractor(context);
        this.listener = listener;
    }

    /** The tier that should be drawn at this zoom, or 0 when the legacy bars suffice. */
    public static int tierForPxPerSec(float pxPerSec) {
        if (pxPerSec >= PX_PER_SEC_HIGH) return TIER_HIGH;
        if (pxPerSec >= PX_PER_SEC_MID) return TIER_MID;
        return 0;
    }

    /**
     * HD waveform data for this clip at this zoom, or {@code null} when the legacy bars
     * should draw (zoomed out, source missing, extraction failed, or not extracted yet —
     * in which case the extraction is kicked off and {@link InvalidateListener} fires when
     * it lands). While a higher tier is still extracting, an already-ready lower tier is
     * returned so zooming in never degrades below what we have.
     */
    @Nullable
    public WaveformData get(@NonNull AudioClip clip, float pxPerSec) {
        int tier = tierForPxPerSec(pxPerSec);
        if (tier == 0) return null;
        WaveformData d = lookupOrRequest(clip, tier);
        if (d == null && tier == TIER_HIGH) {
            // Don't REQUEST mid here (high is already in flight) — just reuse it if present.
            d = ready.get(key(clip, TIER_MID));
        }
        return d;
    }

    @Nullable
    private WaveformData lookupOrRequest(@NonNull AudioClip clip, int tier) {
        Uri uri = clip.getSourceUri();
        if (uri == null) return null;
        String k = key(clip, tier);
        WaveformData d = ready.get(k);
        if (d != null) return d;
        if (inFlight.contains(k) || failed.contains(k)) return null;
        if (suspended) return null; // F3c: no new kicks while playing

        long start = quantStart(clip);
        long end = quantEnd(clip);
        inFlight.add(k);
        extractor.extractAsync(uri, 1, start, end, tier, /* withSpectrum= */ false,
                new WaveformExtractor.Callback() {
                    @Override
                    public void onReady(@NonNull WaveformData data) {
                        mainHandler.post(() -> {
                            inFlight.remove(k);
                            ready.put(k, data);
                            listener.onWaveformReady();
                        });
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        mainHandler.post(() -> {
                            inFlight.remove(k);
                            failed.add(k);
                            FLog.w(TAG, "Timeline waveform extraction failed (" + tier
                                    + " b/s): " + message);
                        });
                    }
                });
        return null;
    }

    private static long quantStart(@NonNull AudioClip clip) {
        return (Math.max(0, clip.getInPointMs()) / SPAN_QUANTUM_MS) * SPAN_QUANTUM_MS;
    }

    private static long quantEnd(@NonNull AudioClip clip) {
        long end = ((clip.getOutPointMs() + SPAN_QUANTUM_MS - 1) / SPAN_QUANTUM_MS)
                * SPAN_QUANTUM_MS;
        long srcDur = clip.getSourceDurationMs();
        if (srcDur > 0) end = Math.min(end, srcDur);
        return Math.max(end, quantStart(clip) + 1);
    }

    @NonNull
    private static String key(@NonNull AudioClip clip, int tier) {
        return clip.getSourceUri() + "|" + quantStart(clip) + "-" + quantEnd(clip) + "|" + tier;
    }

    public void shutdown() {
        extractor.shutdown();
    }
}
