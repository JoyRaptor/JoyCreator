package com.fadcam.visualizer;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.opengl.GLWatermarkRenderer;
import com.fadcam.ui.faditor.model.VizLayer;
import com.fadcam.ui.faditor.model.WaveformData;
import com.fadcam.ui.faditor.model.WaveformStyle;
import com.fadcam.ui.faditor.waveform.WaveformStyleIO;
import com.fadcam.ui.faditor.waveform.WaveformStyleRenderer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Live recording visualizer (Visualizer Studio spec §Phase 4). Reuses the editor's existing
 * {@link WaveformStyleRenderer} + {@link WaveformStyle} presets unchanged — Phase 4 only feeds that
 * renderer live PCM during recording instead of building a parallel renderer.
 *
 * <p>Owns a {@link LiveAmplitudeSampler} (fed the recording's PCM via {@link #onPcm}) and a single
 * {@link WaveformStyleRenderer}. Implements {@link GLWatermarkRenderer.OverlayFrameSource} so the
 * live GL compositor can pull one visualizer frame per rendered frame with no per-frame bitmap
 * allocation (the renderer's {@code renderReusable} reuses its own bitmap).</p>
 */
public final class LiveVisualizer implements GLWatermarkRenderer.OverlayFrameSource {

    private final LiveAmplitudeSampler sampler;
    private final WaveformStyleRenderer renderer = new WaveformStyleRenderer();
    private final WaveformStyle style;

    /** Bucket resolution of the live sampler (2 buckets/frame at ~30 fps). */
    private static final long BUCKET_MS = 16L;

    public LiveVisualizer(@NonNull WaveformStyle style, int sampleRate) {
        this.style = style;
        this.sampler = new LiveAmplitudeSampler(sampleRate, BUCKET_MS, style.drawsSpectrum());
    }

    /** Audio-thread PCM tap (16-bit LE mono), forwarded straight to the sampler. */
    public void onPcm(@NonNull ByteBuffer pcm, int size) {
        sampler.onPcm(pcm, size);
    }

    // GLWatermarkRenderer.OverlayFrameSource — called on the GL/compositing thread.
    @Override
    @Nullable
    public Bitmap renderFrame(int w, int h, float density) {
        if (w <= 0 || h <= 0) return null;
        WaveformData data = sampler.snapshot();
        long atMs = sampler.snapshotAtMs();
        // renderReusable reuses its bitmap — no per-frame allocation on the compositing thread.
        return renderer.renderReusable(data, style, w, h, atMs, density);
    }

    // ── Tier-1 "cheap styles only" filter (spec Decision 1) ──────────────────

    /**
     * A style is eligible for the live recording path when its layer stack (or legacy single-shape)
     * contains NO particles emitter and NO trail layers — those emitters resample the audio at
     * several past instants per frame (spec §3 look-back taps), which is too hot for the recording
     * compositing thread. This is the spec's "Tier 1 only" rule expressed in the current
     * WaveformStyle terms. A legacy style ({@code layers == null}) can never carry particles/trails
     * (layer-only features), so it is always eligible.
     */
    public static boolean isLiveEligible(@NonNull WaveformStyle s) {
        if (s.layers == null) return true;
        for (VizLayer l : s.layers) {
            if (VizLayer.EMITTER_PARTICLES.equals(l.emitter)) return false;
            if (l.trailCount > 0) return false;
        }
        return true;
    }

    /** Built-in styles filtered to the live-eligible (cheap) set, for the FadRec long-press picker. */
    @NonNull
    public static List<WaveformStyle> loadLiveEligibleBuiltins(@NonNull Context context) {
        List<WaveformStyle> out = new ArrayList<>();
        for (WaveformStyle s : WaveformStyleIO.loadBuiltins(context)) {
            if (isLiveEligible(s)) out.add(s);
        }
        return out;
    }

    /**
     * A stock synthetic amplitude + spectrum sequence for STATIC preview thumbnails (the FadRec
     * style picker renders one frame of each live-eligible style against this). Mirrors the shape
     * used by {@code WaveformDebugActivity.synthData} so previews look like the debug screen. This
     * is NOT used on the live recording path — that pulls real PCM through the sampler.
     */
    @NonNull
    public static WaveformData sampleWaveformData() {
        int buckets = 400;
        long durationMs = 4000;
        int bands = 32;
        float[] amp = new float[buckets];
        float[][] spec = new float[buckets][bands];
        for (int i = 0; i < buckets; i++) {
            double base = 0.5 + 0.5 * Math.sin(i * 0.10);
            double slow = 0.5 + 0.5 * Math.sin(i * 0.013);
            amp[i] = (float) clamp01(base * slow);
            for (int b = 0; b < bands; b++) {
                double bandFall = 1.0 - (b / (double) bands) * 0.7; // bass louder than treble
                double wob = 0.5 + 0.5 * Math.sin(i * 0.05 + b * 0.6);
                spec[i][b] = (float) clamp01(bandFall * wob);
            }
        }
        return new WaveformData(amp, spec, durationMs);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /**
     * Resolve the armed style by id from the live-eligible built-ins, falling back to the first
     * eligible style (or null if none). Keeps the recording path from ever arming a hot style even
     * if a stale/hand-edited pref points at one.
     */
    @Nullable
    public static WaveformStyle resolveArmedStyle(@NonNull Context context, @Nullable String styleId) {
        List<WaveformStyle> eligible = loadLiveEligibleBuiltins(context);
        if (eligible.isEmpty()) return null;
        if (styleId != null) {
            for (WaveformStyle s : eligible) {
                if (styleId.equals(s.id)) return s;
            }
        }
        return eligible.get(0);
    }
}
