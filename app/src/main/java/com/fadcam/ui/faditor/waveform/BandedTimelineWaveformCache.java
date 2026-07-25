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

import java.util.HashMap;
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

    /** Monotonic id source: every new {@link Shaped} instance gets a fresh serial. */
    private static long serialSeq = 0L;

    /**
     * Shaped result the renderer draws: raw (for frame timing) + the quantized mip pyramid
     * (A1/A2). Each instance carries a unique {@link #serial} — because a re-extract
     * ({@code onReady}) and a {@code reshapeAll()} are the ONLY ways shaped data ever changes,
     * and both replace the {@code Shaped} object, the serial is a complete invalidation token
     * for the tile cache (a new serial ⇒ stale tiles are unreachable).
     */
    public static final class Shaped {
        @NonNull public final BandedWaveformData raw;
        @NonNull public final ShapedTape tape;
        /** Unique per instance; changes iff the shaped data changed. */
        public final long serial;
        Shaped(@NonNull BandedWaveformData raw, @NonNull ShapedTape tape) {
            this.raw = raw;
            this.tape = tape;
            this.serial = ++serialSeq;
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
    /**
     * Quantized span behind each ready/in-flight key: two side maps
     * (key → quantized span, key → uri). Enables SUPERSET REUSE (2026-07-16): the raw data maps absolute source time
     * (startOffsetMs + envRate), so an extraction covering [A,B] serves ANY window inside it —
     * a full-source background prime then serves every trim window, and trimming a clip never
     * re-runs a minutes-long extraction that a covering entry already answers.
     */
    private final Map<String, long[]> spanByKey = new HashMap<>();
    private final Map<String, Uri> uriByKey = new HashMap<>();
    /** Live extraction progress (0..1) per in-flight key — feeds the minimap meters. */
    private final Map<String, Float> progressByKey = new HashMap<>();
    /**
     * F3c (PERF_SPEC_LONGFILE_20260718): while true, {@link #get} serves ready data but
     * does NOT kick new extractions — a minutes-long band decode racing live playback for
     * the codec/disk starved BOTH (playback dropped frames, the decode came back partial
     * → never cached → re-kicked → loop). In-flight jobs are left to finish. Set from the
     * editor's play/pause transitions.
     */
    private boolean suspended = false;

    /** F3c: gate NEW extraction kicks (in-flight ones keep running). */
    public void setSuspended(boolean s) {
        this.suspended = s;
    }

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
        return get(uri, clip.getInPointMs(), clip.getOutPointMs(), clip.getSourceDurationMs());
    }

    /**
     * Source-span variant (clip-audio drawer): the drawer shows a MASTER {@code Clip}'s own
     * embedded audio, which is not an {@link AudioClip} — key directly off the source + span.
     * Spans quantize to the same {@link #SPAN_QUANTUM_MS} grid, so a master clip and an
     * "extract from video" AudioClip over the same source share one extraction.
     */
    @Nullable
    public Shaped get(@NonNull Uri uri, long inMs, long outMs, long srcDurMs) {
        String k = key(uri, inMs, outMs, srcDurMs);
        Shaped s = ready.get(k);
        if (s != null) return s;
        if (inFlight.contains(k) || failed.contains(k)) return null;

        long start = quantStart(inMs);
        long end = quantEnd(inMs, outMs, srcDurMs);

        // SUPERSET REUSE: a ready entry over the same source that COVERS [start,end] answers
        // this window directly (raw data is absolute-source-time mapped). Alias it under this
        // key so the next lookup is an exact hit.
        boolean coveringInFlight = false;
        for (Map.Entry<String, long[]> e : spanByKey.entrySet()) {
            long[] span = e.getValue();
            if (span[0] <= start && span[1] >= end && uri.equals(uriByKey.get(e.getKey()))) {
                Shaped covering = ready.get(e.getKey());
                if (covering != null) {
                    ready.put(k, covering);
                    spanByKey.put(k, span);
                    uriByKey.put(k, uri);
                    return covering;
                }
                // A covering extraction is IN FLIGHT — don't start a duplicate for it, but
                // keep scanning: another covering entry may already be ready (F9 fix —
                // returning null on the FIRST in-flight match short-circuited the loop and
                // could mask an already-ready covering entry later in iteration order,
                // leaving the placeholder stuck for the rest of the session).
                if (inFlight.contains(e.getKey())) coveringInFlight = true;
            }
        }
        if (coveringInFlight) return null;

        // F3c: no NEW kicks while playback owns the codec/disk — the next draw after
        // pause re-enters here and kicks then.
        if (suspended) return null;

        inFlight.add(k);
        spanByKey.put(k, new long[]{start, end});
        uriByKey.put(k, uri);
        extractor.extractAsync(uri, start, end, style.lowHz, style.presHz, style.highHz,
                style.presenceOn, new BandWaveformExtractor.Callback() {
                    @Override
                    public void onProgress(float fraction) {
                        // Extractor throttles to ~2% steps; surface for the minimap meters.
                        main.post(() -> {
                            progressByKey.put(k, fraction);
                            listener.onWaveformReady();
                        });
                    }

                    @Override
                    public void onReady(@NonNull BandedWaveformData data) {
                        main.post(() -> {
                            progressByKey.remove(k);
                            inFlight.remove(k);
                            ShapedTape tape = BandEnvelopeShaper.shapeToTape(data, style.smooth,
                                    style.contrast, style.perBandNormalize);
                            Shaped shaped = new Shaped(data, tape);
                            ready.put(k, shaped);
                            listener.onWaveformReady();
                            if (!data.complete) {
                                // Partial decode (stall under load): show what we have NOW,
                                // but evict after a cooldown so a later draw re-kicks the
                                // extraction — otherwise a 70%-covered tape sat frozen for
                                // the whole session (JoyRaptor 2026-07-16 round 3).
                                main.postDelayed(() -> {
                                    if (ready.get(k) == shaped) {
                                        ready.remove(k);
                                        spanByKey.remove(k);
                                        uriByKey.remove(k);
                                        listener.onWaveformReady();
                                    }
                                }, 60_000L);
                            }
                        });
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        main.post(() -> {
                            progressByKey.remove(k);
                            inFlight.remove(k);
                            failed.add(k);
                            spanByKey.remove(k);
                            uriByKey.remove(k);
                            FLog.w(TAG, "Band tape extraction failed: " + message);
                        });
                    }
                });
        return null;
    }

    /**
     * Analysis progress for a span: 1 when ready (exact or covering entry), the live
     * extraction fraction while in flight, 0 when not started. Feeds the minimap meters.
     */
    public float progressFor(@NonNull Uri uri, long inMs, long outMs, long srcDurMs) {
        String k = key(uri, inMs, outMs, srcDurMs);
        if (ready.containsKey(k)) return 1f;
        Float p = progressByKey.get(k);
        if (p != null) return p;
        long start = quantStart(inMs);
        long end = quantEnd(inMs, outMs, srcDurMs);
        for (Map.Entry<String, long[]> e : spanByKey.entrySet()) {
            long[] span = e.getValue();
            if (span[0] <= start && span[1] >= end && uri.equals(uriByKey.get(e.getKey()))) {
                if (ready.containsKey(e.getKey())) return 1f;
                Float cp = progressByKey.get(e.getKey());
                if (cp != null) return cp;
            }
        }
        return 0f;
    }

    /** Re-shape every cached entry from its raw data (AV4: called when a cheap knob changes). */
    public void reshapeAll() {
        for (Map.Entry<String, Shaped> e : ready.entrySet()) {
            BandedWaveformData raw = e.getValue().raw;
            e.setValue(new Shaped(raw, BandEnvelopeShaper.shapeToTape(raw, style.smooth,
                    style.contrast, style.perBandNormalize)));
        }
        listener.onWaveformReady();
    }

    /** Drop all cached shapes (AV4: called when crossovers change → forces re-extract). */
    public void clear() {
        ready.clear();
        inFlight.clear();
        failed.clear();
        spanByKey.clear();
        uriByKey.clear();
        progressByKey.clear();
    }

    private static long quantStart(long inMs) {
        return (Math.max(0, inMs) / SPAN_QUANTUM_MS) * SPAN_QUANTUM_MS;
    }

    private static long quantEnd(long inMs, long outMs, long srcDurMs) {
        long end = ((outMs + SPAN_QUANTUM_MS - 1) / SPAN_QUANTUM_MS) * SPAN_QUANTUM_MS;
        if (srcDurMs > 0) end = Math.min(end, srcDurMs);
        return Math.max(end, quantStart(inMs) + 1);
    }

    @NonNull
    private String key(@NonNull Uri uri, long inMs, long outMs, long srcDurMs) {
        return uri + "|" + quantStart(inMs) + "-" + quantEnd(inMs, outMs, srcDurMs)
                + "|" + style.lowHz + "-" + style.presHz + "-" + style.highHz
                + (style.presenceOn ? "p" : "");
    }

    public void shutdown() {
        extractor.shutdown();
    }
}
