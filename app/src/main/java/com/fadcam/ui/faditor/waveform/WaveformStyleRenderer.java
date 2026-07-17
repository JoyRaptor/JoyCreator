package com.fadcam.ui.faditor.waveform;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.VizLayer;
import com.fadcam.ui.faditor.model.WaveformData;
import com.fadcam.ui.faditor.model.WaveformStyle;

/**
 * Draws an animated waveform/spectrum frame to a software {@link Bitmap} using only
 * {@code Canvas}/{@code Paint} — no GL, so the bitmap can be fed straight into Media3's
 * {@code OverlayEffect} (same path as text overlays) without EGL context-ownership issues.
 *
 * <p>{@link #render} is called per frame with the current timestamp, so the visualizer is
 * audio-reactive (bars pulse with the audio) rather than a static envelope.</p>
 */
public class WaveformStyleRenderer {

    private static final float GAMMA = 0.55f; // perceptual lift so quiet/mid audio still reads

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    // Peak-hold caps look back over a short window of the shared energies. The K-tap arrays are
    // computed once per render call as LOCALS (spec §3 — no per-frame state); these are just the
    // fixed constants of that window, not state that persists between frames.
    private static final int PEAK_TAPS = 8;      // K
    private static final long PEAK_STEP_MS = 66; // Δ between look-back taps

    // P3 particles (spec §3): fixed life + a birth-time lattice. A particle's birth time is
    // quantized to the absolute STEP grid, so its birth energy is ONE stable tap of the shared
    // banded cache for its whole life — no sim state, scrub/export exact.
    private static final long PARTICLE_LIFE_MS = 1400;
    private static final long PARTICLE_STEP_MS = 175; // LIFE / 8 birth-energy lattice
    /** Bands × particleCount is clamped to this per layer (spec perf note). */
    private static final int PARTICLE_BUDGET = 512;
    /** Δ between trail echo passes (t − k·Δ per spec §3). */
    private static final long TRAIL_STEP_MS = 66;
    /** Softness blur radius at softness = 1, in dp. */
    private static final float SOFT_MAX_DP = 8f;

    // Reused across frames so preview rendering allocates nothing per frame (the previous
    // per-frame Bitmap allocation caused GC churn and the choppy/hanging playback).
    private Bitmap reuse;
    private Canvas reuseCanvas;

    // BlurMaskFilter is allocated once and reused until the glow radius or density changes.
    // The previous code created a new filter on every drawFrame() call, which caused GC churn
    // and native mask-filter allocation overhead per frame.
    @Nullable private BlurMaskFilter glowFilter;
    private float lastGlowRadiusDp = -1f;
    private float lastGlowDensity = -1f;

    // Same one-slot cache for the drop-shadow blur (kept separate so it doesn't thrash the glow
    // filter when a layer uses both). Deterministic: depends only on radius + density.
    @Nullable private BlurMaskFilter shadowFilter;
    private float lastShadowRadiusDp = -1f;
    private float lastShadowDensity = -1f;

    // Softness blur filters, cached per quantized pixel radius. Unlike glow/shadow this is a small
    // map, not one slot: several layers in a stack routinely carry DIFFERENT softness values, and a
    // single slot would reallocate a native filter per layer per frame. Cleared on density change;
    // bounded (softness has ≤ SOFT_MAX_DP·density distinct quantized radii).
    private final java.util.HashMap<Integer, BlurMaskFilter> softFilters = new java.util.HashMap<>();
    private float lastSoftDensity = -1f;

    /** Export path: a fresh, independent bitmap (the export pipeline keeps each frame). */
    @NonNull
    public Bitmap render(@NonNull WaveformData data, @NonNull WaveformStyle style,
                         int w, int h, long atMs, float density) {
        return render(data, style, w, h, atMs, density, -1, -1, false, -1, 0, 0.35f, 20, 20000, 0);
    }

    /** Export path with architecture overrides (justify/dataMode/hMirror). */
    @NonNull
    public Bitmap render(@NonNull WaveformData data, @NonNull WaveformStyle style,
                         int w, int h, long atMs, float density,
                         int justify, int dataMode, boolean hMirror) {
        return render(data, style, w, h, atMs, density, justify, dataMode, hMirror,
                -1, 0, 0.35f, 20, 20000, 0);
    }

    /** Export path with all architecture overrides + freq/band. */
    @NonNull
    public Bitmap render(@NonNull WaveformData data, @NonNull WaveformStyle style,
                         int w, int h, long atMs, float density,
                         int justify, int dataMode, boolean hMirror,
                         int centerMode, int renderMode, float radialRingSize,
                         int freqLowHz, int freqHighHz, int bandCountOverride) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        drawFrame(new Canvas(bmp), data, style, w, h, atMs, density, justify, dataMode, hMirror,
                centerMode, renderMode, radialRingSize, freqLowHz, freqHighHz, bandCountOverride);
        return bmp;
    }

    /** Preview path: reuses one bitmap (no per-frame allocation). Do NOT recycle the result. */
    @NonNull
    public Bitmap renderReusable(@NonNull WaveformData data, @NonNull WaveformStyle style,
                                 int w, int h, long atMs, float density) {
        return renderReusable(data, style, w, h, atMs, density,
                -1, -1, false, -1, 0, 0.35f, 20, 20000, 0);
    }

    /** Preview path with architecture overrides (justify/dataMode/hMirror). */
    @NonNull
    public Bitmap renderReusable(@NonNull WaveformData data, @NonNull WaveformStyle style,
                                 int w, int h, long atMs, float density,
                                 int justify, int dataMode, boolean hMirror) {
        return renderReusable(data, style, w, h, atMs, density,
                justify, dataMode, hMirror, -1, 0, 0.35f, 20, 20000, 0);
    }

    /** Preview path with all architecture overrides + freq/band. */
    @NonNull
    public Bitmap renderReusable(@NonNull WaveformData data, @NonNull WaveformStyle style,
                                 int w, int h, long atMs, float density,
                                 int justify, int dataMode, boolean hMirror,
                                 int centerMode, int renderMode, float radialRingSize,
                                 int freqLowHz, int freqHighHz, int bandCountOverride) {
        Bitmap b = ensureReuse(Math.max(1, w), Math.max(1, h));
        reuseCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
        drawFrame(reuseCanvas, data, style, b.getWidth(), b.getHeight(), atMs, density,
                justify, dataMode, hMirror,
                centerMode, renderMode, radialRingSize,
                freqLowHz, freqHighHz, bandCountOverride);
        return b;
    }

    /** Faint idle bars while the audio is still extracting (reused bitmap). */
    @NonNull
    public Bitmap renderPlaceholderReusable(@NonNull WaveformStyle style, int w, int h, float density) {
        return renderPlaceholderReusable(style, w, h, density, -1f);
    }

    /**
     * Faint idle bars while the audio is still extracting. When {@code progress} is in [0,1], bars
     * left of the progress point are lit (brighter) so the user sees extraction advancing.
     */
    @NonNull
    public Bitmap renderPlaceholderReusable(@NonNull WaveformStyle style, int w, int h,
                                            float density, float progress) {
        Bitmap b = ensureReuse(Math.max(1, w), Math.max(1, h));
        reuseCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
        drawPlaceholder(reuseCanvas, style, b.getWidth(), b.getHeight(), density, progress);
        return b;
    }

    @NonNull
    private Bitmap ensureReuse(int w, int h) {
        if (reuse == null || reuse.getWidth() != w || reuse.getHeight() != h) {
            if (reuse != null) reuse.recycle();
            reuse = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            reuseCanvas = new Canvas(reuse);
        }
        return reuse;
    }

    /**
     * Returns a cached {@link BlurMaskFilter}, recreating it only when the glow radius or
     * display density changes. This avoids allocating a native mask filter on every frame.
     */
    @NonNull
    private BlurMaskFilter ensureGlowFilter(float glowRadiusDp, float density) {
        if (glowFilter == null
                || glowRadiusDp != lastGlowRadiusDp
                || density != lastGlowDensity) {
            glowFilter = new BlurMaskFilter(
                    Math.max(0.5f, glowRadiusDp * density), BlurMaskFilter.Blur.NORMAL);
            lastGlowRadiusDp = glowRadiusDp;
            lastGlowDensity = density;
        }
        return glowFilter;
    }

    /** Cached softness blur filter (P3) — see {@link #softFilters} for why this one is a map. */
    @NonNull
    private BlurMaskFilter ensureSoftFilter(float softness, float density) {
        if (density != lastSoftDensity) {
            softFilters.clear();
            lastSoftDensity = density;
        }
        int px = Math.max(1, Math.round(softness * SOFT_MAX_DP * density));
        BlurMaskFilter f = softFilters.get(px);
        if (f == null) {
            f = new BlurMaskFilter(px, BlurMaskFilter.Blur.NORMAL);
            softFilters.put(px, f);
        }
        return f;
    }

    /** Cached {@link BlurMaskFilter} for the drop-shadow pass (separate slot from the glow filter). */
    @NonNull
    private BlurMaskFilter ensureShadowFilter(float shadowRadiusDp, float density) {
        if (shadowFilter == null
                || shadowRadiusDp != lastShadowRadiusDp
                || density != lastShadowDensity) {
            shadowFilter = new BlurMaskFilter(
                    Math.max(0.5f, shadowRadiusDp * density), BlurMaskFilter.Blur.NORMAL);
            lastShadowRadiusDp = shadowRadiusDp;
            lastShadowDensity = density;
        }
        return shadowFilter;
    }

    private void drawFrame(@NonNull Canvas canvas, @NonNull WaveformData data,
                           @NonNull WaveformStyle style, int w, int h, long atMs, float density,
                           int justifyOverride, int dataModeOverride, boolean hMirror,
                           int centerMode, int renderMode, float radialRingSize,
                           int freqLowHz, int freqHighHz, int bandCountOverride) {
        if (data.bucketCount() == 0) return;

        // Resolve the orthogonal architecture: data source + vertical justify (shape is per-layer).
        boolean spectrum = dataModeOverride == 1 || (dataModeOverride < 0 && style.drawsSpectrum());
        int justify = justifyOverride >= 0 ? justifyOverride : defaultJustify(style);
        boolean radial = renderMode == 1;

        // AudioMapper: band energies are computed ONCE per render call and SHARED across every
        // layer (spec §2 + perf note) — never recomputed per layer. The TapSampler memoizes
        // sampleHeights at arbitrary instants so peaks/trails/particles re-reading past taps all
        // share arrays; it is a LOCAL (spec §3 — no per-frame state) and its arrays are read-only.
        TapSampler sampler = new TapSampler(data, style, spectrum, hMirror, centerMode,
                freqLowHz, freqHighHz, bandCountOverride);
        float[] energies = sampler.at(atMs);

        java.util.List<VizLayer> layers = style.layers;
        if (layers == null) {
            // Legacy auto-wrap: a transient one-layer stack that reproduces EXACTLY the old draw.
            drawLayer(canvas, legacyLayer(style, radial), energies, null, sampler, atMs, w, h,
                    density, justify, radial, radialRingSize);
        } else {
            // PEAKS caps need the shared energies at several PAST instants. Compute the reduced
            // peak-hold array ONCE per render call (shared across every peaks layer) as a LOCAL.
            // Only pay for the extra taps when some layer actually draws peaks.
            float[] peaks = hasPeaksLayer(layers) ? computePeaks(sampler, energies, atMs) : null;
            for (VizLayer layer : layers) {
                drawLayer(canvas, layer, energies, peaks, sampler, atMs, w, h, density, justify,
                        radial, radialRingSize);
            }
        }
    }

    /**
     * Per-render-call memo over {@link #sampleHeights} at arbitrary instants (P3). Every consumer of
     * a given tap time (peaks look-back, trail echoes, particle birth energies — across ALL layers)
     * shares one array. Lives only for one drawFrame call; cached arrays must never be mutated.
     */
    private final class TapSampler {
        private final WaveformData data;
        private final WaveformStyle style;
        private final boolean spectrum, hMirror;
        private final int centerMode, freqLowHz, freqHighHz, bandCountOverride;
        private final java.util.HashMap<Long, float[]> memo = new java.util.HashMap<>();

        TapSampler(@NonNull WaveformData data, @NonNull WaveformStyle style, boolean spectrum,
                   boolean hMirror, int centerMode, int freqLowHz, int freqHighHz,
                   int bandCountOverride) {
            this.data = data;
            this.style = style;
            this.spectrum = spectrum;
            this.hMirror = hMirror;
            this.centerMode = centerMode;
            this.freqLowHz = freqLowHz;
            this.freqHighHz = freqHighHz;
            this.bandCountOverride = bandCountOverride;
        }

        /** Band energies at {@code tMs} — identical arithmetic to the direct sampleHeights call. */
        @NonNull
        float[] at(long tMs) {
            float[] v = memo.get(tMs);
            if (v == null) {
                v = sampleHeights(data, style, tMs, spectrum, hMirror, centerMode,
                        freqLowHz, freqHighHz, bandCountOverride);
                memo.put(tMs, v);
            }
            return v;
        }
    }

    /** True when any layer in the stack draws the PEAKS emitter (gates the extra look-back taps). */
    private static boolean hasPeaksLayer(@NonNull java.util.List<VizLayer> layers) {
        for (VizLayer l : layers) {
            if (VizLayer.EMITTER_PEAKS.equals(l.emitter)) return true;
        }
        return false;
    }

    /**
     * Falling peak-hold levels, deterministic (spec §3 — no per-frame state). Per band:
     * {@code peak_i = max over k in 0..K-1 of energies_i(atMs − k·STEP) · (1 − k/K)}, so a recent
     * loud transient leaves a cap that decays as the taps age. Every tap is a stateless sampler
     * read at a shifted time (k=0 reuses the already-computed {@code energies}, saving one call).
     */
    @NonNull
    private float[] computePeaks(@NonNull TapSampler sampler, @NonNull float[] energies, long atMs) {
        float[] peak = energies.clone(); // k = 0, envelope (1 − 0/K) = 1
        for (int k = 1; k < PEAK_TAPS; k++) {
            float env = 1f - k / (float) PEAK_TAPS;
            float[] tap = sampler.at(Math.max(0L, atMs - (long) k * PEAK_STEP_MS));
            int n = Math.min(peak.length, tap.length);
            for (int i = 0; i < n; i++) {
                float v = tap[i] * env;
                if (v > peak[i]) peak[i] = v;
            }
        }
        return peak;
    }

    /**
     * Build the transient single {@link VizLayer} that reproduces a legacy {@link WaveformStyle}'s
     * draw EXACTLY: the same emitter selection (radial ALWAYS drew bars regardless of the style's
     * type — the old renderMode==1 branch ran before the shape switch), the same paint fields, and
     * defaults for opacity(1)/blend(normal)/spread(1)/phase(0)/mirror(false)/gain(1) so the
     * GeometryMapper and PaintStage collapse to identity.
     */
    @NonNull
    private static VizLayer legacyLayer(@NonNull WaveformStyle style, boolean radial) {
        VizLayer l = new VizLayer();
        l.emitter = radial ? VizLayer.EMITTER_BARS : shapeOf(style.type);
        l.color = style.color;
        l.gradientStart = style.gradientStart;
        l.gradientEnd = style.gradientEnd;
        l.glowColor = style.glowColor;
        l.glowRadiusDp = style.glowRadiusDp;
        l.barWidthDp = style.barWidthDp;
        l.barGapDp = style.barGapDp;
        l.cornerRadiusDp = style.cornerRadiusDp;
        return l;
    }

    /** Emitters the renderer draws — the full spec set as of P3. */
    private static boolean isDrawable(@Nullable String emitter) {
        return VizLayer.EMITTER_BARS.equals(emitter) || VizLayer.EMITTER_LINE.equals(emitter)
                || VizLayer.EMITTER_FILLED.equals(emitter) || VizLayer.EMITTER_DOTS.equals(emitter)
                || VizLayer.EMITTER_SQUARES.equals(emitter) || VizLayer.EMITTER_PEAKS.equals(emitter)
                || VizLayer.EMITTER_RING.equals(emitter) || VizLayer.EMITTER_PARTICLES.equals(emitter);
    }

    /**
     * PaintStage + Emitter dispatch for one layer, drawn through a {@link GeometryMapper}. Draws up
     * to three passes per layer in the spec order: SHADOW (blurred, translated) → GLOW → SOLID. A
     * legacy auto-wrapped layer has no shadow and (unless the style had glow) a single solid pass,
     * in the exact same order as before — so the pixel-parity guarantee holds.
     */
    private void drawLayer(@NonNull Canvas canvas, @NonNull VizLayer layer, @NonNull float[] energies,
                           @Nullable float[] peaks, @NonNull TapSampler sampler, long atMs,
                           int w, int h, float density, int justify,
                           boolean radial, float radialRingSize) {
        if (!isDrawable(layer.emitter)) return;
        // RING is a radial-only accent; nothing to draw (or configure) for it in the linear strip.
        if (VizLayer.EMITTER_RING.equals(layer.emitter) && !radial) return;

        int primary = parseColor(layer.color, 0xFF00E676);
        configureLayerPaint(barPaint, layer, primary, h);
        // P3 softness — a NORMAL blur on the layer's own passes (spec §2 "soft edged things").
        // 0 = no mask filter, the legacy hard edge. Glow/shadow copies set their own filters after.
        if (layer.softness > 0f) {
            barPaint.setMaskFilter(ensureSoftFilter(layer.softness, density));
        }
        applyOpacity(barPaint, layer.opacity);
        applyBlend(barPaint, layer.blend);

        boolean hasGlow = layer.glowRadiusDp > 0f && layer.glowColor != null;
        if (hasGlow) {
            glowPaint.set(barPaint); // inherits antialias/style/shader/alpha/xfermode
            glowPaint.setColor(parseColor(layer.glowColor, primary));
            glowPaint.setShader(null);
            glowPaint.setMaskFilter(ensureGlowFilter(layer.glowRadiusDp, density));
            applyOpacity(glowPaint, layer.opacity); // setColor reset the alpha channel — re-apply
        }

        boolean hasShadow = layer.shadowColor != null;
        if (hasShadow) {
            shadowPaint.set(barPaint); // inherits antialias/style/shader
            shadowPaint.setColor(parseColor(layer.shadowColor, 0xFF000000));
            shadowPaint.setShader(null);
            shadowPaint.setXfermode(null); // a shadow blends NORMAL beneath the layer, never ADD
            shadowPaint.setMaskFilter(ensureShadowFilter(layer.shadowRadiusDp, density));
            applyOpacity(shadowPaint, layer.opacity); // setColor reset the alpha channel — re-apply
        }

        GeometryMapper m = new GeometryMapper(radial, w, h, density, justify, radialRingSize, layer);
        // Order per layer: shadow (translated) → trails (oldest first) → glow → solid.
        if (hasShadow) {
            canvas.save();
            canvas.translate(layer.shadowDx * density, layer.shadowDy * density);
            emit(canvas, energies, peaks, m, layer, shadowPaint, radial, sampler, atMs);
            canvas.restore();
        }
        // P3 trails: echo pass k redraws the emitter with the energies of t − k·Δ and decaying
        // alpha — stateless per spec §3 (each echo is just the closed-form frame of a past instant),
        // so preview, scrub and export produce the identical smear.
        if (layer.trailCount > 0) {
            Paint trailPaint = new Paint(barPaint);
            int baseAlpha = barPaint.getAlpha();
            for (int k = layer.trailCount; k >= 1; k--) {
                float decay = 1f - k / (float) (layer.trailCount + 1);
                trailPaint.setAlpha(Math.round(baseAlpha * decay * 0.6f));
                long tk = Math.max(0L, atMs - k * TRAIL_STEP_MS);
                emit(canvas, sampler.at(tk), null, m, layer, trailPaint, radial, sampler, tk);
            }
        }
        if (hasGlow) emit(canvas, energies, peaks, m, layer, glowPaint, radial, sampler, atMs);
        emit(canvas, energies, peaks, m, layer, barPaint, radial, sampler, atMs);
    }

    /**
     * One emitter pass with the given paint. Kept identical to the pre-P2 per-shape dispatch for the
     * existing emitters (bars/line/filled/dots, and radial's line/filled → radial-bars fallback), so
     * legacy renders unchanged; adds the P2 emitters (squares/peaks/ring).
     */
    private void emit(@NonNull Canvas canvas, @NonNull float[] energies, @Nullable float[] peaks,
                      @NonNull GeometryMapper m, @NonNull VizLayer layer, @NonNull Paint paint,
                      boolean radial, @NonNull TapSampler sampler, long tMs) {
        if (radial) {
            switch (layer.emitter) {
                case VizLayer.EMITTER_DOTS:    drawRadialDots(canvas, energies, m, layer, paint); break;
                case VizLayer.EMITTER_SQUARES: drawRadialSquares(canvas, energies, m, layer, paint); break;
                case VizLayer.EMITTER_PEAKS:
                    drawRadialPeaks(canvas, peaks != null ? peaks : energies, m, layer, paint); break;
                case VizLayer.EMITTER_RING:    drawRing(canvas, energies, m, layer, paint); break;
                case VizLayer.EMITTER_PARTICLES:
                    drawParticles(canvas, m, layer, paint, sampler, tMs, energies.length, true); break;
                default: // bars (and legacy line/filled auto-wrapped to bars when radial)
                    drawRadialBars(canvas, energies, m, layer, paint); break;
            }
            return;
        }
        switch (layer.emitter) {
            case VizLayer.EMITTER_LINE:    drawLine(canvas, energies, m, layer, paint); break;
            case VizLayer.EMITTER_FILLED:  drawFilled(canvas, energies, m, layer, paint); break;
            case VizLayer.EMITTER_DOTS:    drawDots(canvas, energies, m, layer, paint); break;
            case VizLayer.EMITTER_SQUARES: drawSquares(canvas, energies, m, layer, paint); break;
            case VizLayer.EMITTER_PEAKS:
                drawPeaks(canvas, peaks != null ? peaks : energies, m, layer, paint); break;
            case VizLayer.EMITTER_PARTICLES:
                drawParticles(canvas, m, layer, paint, sampler, tMs, energies.length, false); break;
            // RING is radial-only; already returned above for the linear path.
            default:                       drawBars(canvas, energies, m, layer, paint); break;
        }
    }

    /** Multiply the paint's alpha by {@code opacity}; a no-op at 1 so legacy stays byte-identical. */
    private static void applyOpacity(@NonNull Paint paint, float opacity) {
        if (opacity < 1f) {
            int a = Math.round(Color.alpha(paint.getColor()) * clamp01(opacity));
            paint.setAlpha(Math.max(0, Math.min(255, a)));
        }
    }

    // PorterDuff.ADD = neon additive stacking. Allocated once (deterministic, no per-frame state).
    private static final PorterDuffXfermode ADD_XFERMODE =
            new PorterDuffXfermode(PorterDuff.Mode.ADD);

    /** ADD = additive blend; anything else clears the xfermode (NORMAL, the legacy default). */
    private static void applyBlend(@NonNull Paint paint, @Nullable String blend) {
        paint.setXfermode(VizLayer.BLEND_ADD.equals(blend) ? ADD_XFERMODE : null);
    }

    /** Vertical anchoring a style implies by default: center for the *_mirror types, else bottom. */
    private static int defaultJustify(@NonNull WaveformStyle style) {
        return (WaveformStyle.TYPE_MIRROR_BARS.equals(style.type)
                || WaveformStyle.TYPE_SPECTRUM_MIRROR.equals(style.type)) ? 1 : 0;
    }

    /** Render shape a style's type implies (bars / line / filled). */
    @NonNull
    private static String shapeOf(@androidx.annotation.Nullable String type) {
        if (WaveformStyle.TYPE_LINE.equals(type)) return "line";
        if (WaveformStyle.TYPE_FILLED_WAVE.equals(type)) return "filled";
        return "bars";
    }

    private void drawPlaceholder(@NonNull Canvas canvas, @NonNull WaveformStyle style,
                                 int w, int h, float density, float progress) {
        int primary = parseColor(style.color, 0xFF00E676);
        int bars = Math.max(1, style.bandCount);
        float[] heights = new float[bars];
        for (int i = 0; i < bars; i++) {
            heights[i] = 0.10f + 0.06f * (float) Math.abs(Math.sin(i * 0.6));
        }
        int justify = defaultJustify(style);
        VizLayer pl = legacyLayer(style, false);
        GeometryMapper m = new GeometryMapper(false, w, h, density, justify, 0.35f, pl);
        if (progress < 0f) {
            configurePaint(barPaint, style, primary, h);
            barPaint.setAlpha(70);
            drawBars(canvas, heights, m, pl, barPaint);
            return;
        }
        // Lit bars left of the progress point, faint bars to the right.
        int litBars = Math.round(Math.max(0f, Math.min(1f, progress)) * bars);
        float[] lit = new float[bars];
        float[] dim = new float[bars];
        for (int i = 0; i < bars; i++) {
            if (i < litBars) lit[i] = heights[i]; else dim[i] = heights[i];
        }
        configurePaint(barPaint, style, primary, h);
        barPaint.setAlpha(55);
        drawBars(canvas, dim, m, pl, barPaint);
        configurePaint(barPaint, style, primary, h);
        barPaint.setAlpha(200);
        drawBars(canvas, lit, m, pl, barPaint);
    }

    private void configurePaint(@NonNull Paint paint, @NonNull WaveformStyle style,
                                int primary, int h) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setColor(primary);
        paint.setStyle(Paint.Style.FILL);
        if (style.gradientStart != null && style.gradientEnd != null) {
            paint.setShader(new LinearGradient(0, 0, 0, h,
                    parseColor(style.gradientStart, primary),
                    parseColor(style.gradientEnd, primary), Shader.TileMode.CLAMP));
        } else {
            paint.setShader(null);
        }
    }

    /**
     * PaintStage colour setup for a {@link VizLayer}. Structurally identical to
     * {@link #configurePaint} (same reset → antialias → color → FILL → gradient/null-shader order)
     * but reads the layer's own colour fields, so the legacy auto-wrap (fields copied verbatim from
     * the style) produces a byte-identical Paint.
     */
    private void configureLayerPaint(@NonNull Paint paint, @NonNull VizLayer layer,
                                     int primary, int h) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setColor(primary);
        paint.setStyle(Paint.Style.FILL);
        if (layer.gradientStart != null && layer.gradientEnd != null) {
            paint.setShader(new LinearGradient(0, 0, 0, h,
                    parseColor(layer.gradientStart, primary),
                    parseColor(layer.gradientEnd, primary), Shader.TileMode.CLAMP));
        } else {
            paint.setShader(null);
        }
    }

    /**
     * Produce {@code bandCount} normalized heights (0..1) for the current frame. Spectrum styles
     * read the FFT bands at {@code atMs}; amplitude styles read a scrolling window of recent
     * amplitude ending at {@code atMs} (newest on the right) so the shape moves with playback.
     */
    @NonNull
    private float[] sampleHeights(@NonNull WaveformData data, @NonNull WaveformStyle style, long atMs,
                                  boolean spectrum, boolean hMirror, int centerMode,
                                  int freqLowHz, int freqHighHz, int bandCountOverride) {
        int bars = bandCountOverride > 0 ? bandCountOverride : Math.max(1, style.bandCount);
        float[] out = new float[bars];
        float gain = style.sensitivity <= 0 ? 1f : style.sensitivity;
        if (spectrum && data.bandCount() > 0) {
            float[] bands = data.spectrumAt(atMs);
            // Approximate Nyquist at 22050 Hz (44.1kHz sample rate) when no sample-rate info available.
            float nyquist = 22050f;
            float lowBandPos = Math.max(0, Math.min(bands.length - 2,
                    freqLowHz / nyquist * (bands.length - 1)));
            float highBandPos = Math.max(lowBandPos + 1, Math.min(bands.length - 1,
                    freqHighHz / nyquist * (bands.length - 1)));
            for (int i = 0; i < bars; i++) {
                float t = bars <= 1 ? 0f : i / (float) (bars - 1);
                float pos = lowBandPos + t * (highBandPos - lowBandPos);
                int lo = (int) Math.floor(pos);
                int hi = Math.min(bands.length - 1, lo + 1);
                float frac = pos - lo;
                float v = bands[lo] * (1 - frac) + bands[hi] * frac;
                out[i] = shape(v * gain);
            }
        } else {
            long window = data.bucketMs * bars;
            long start = atMs - window;
            for (int i = 0; i < bars; i++) {
                long t = start + (long) ((i / (float) bars) * window);
                out[i] = shape(data.amplitudeAt(Math.max(0, t)) * gain);
            }
        }
        if (hMirror) {
            for (int i = 0, j = out.length - 1; i < j; i++, j--) {
                float tmp = out[i]; out[i] = out[j]; out[j] = tmp;
            }
        }
        if (centerMode >= 0 && bars > 1) {
            float[] reordered = new float[bars];
            for (int i = 0; i < bars; i++) {
                float t = (float) i / (bars - 1);
                float absDist = Math.abs(2.0f * t - 1.0f);
                int srcIdx;
                if (centerMode == 0) {
                    // centre-low: bass (index 0) at centre, treble at edges
                    srcIdx = Math.round(absDist * (bars - 1));
                } else {
                    // centre-high: treble at centre, bass at edges
                    srcIdx = Math.round((1.0f - absDist) * (bars - 1));
                }
                srcIdx = Math.max(0, Math.min(bars - 1, srcIdx));
                reordered[i] = out[srcIdx];
            }
            System.arraycopy(reordered, 0, out, 0, bars);
        }
        return out;
    }

    /** Perceptual response curve: lift quiet/mid levels so the visualizer isn't mostly flat. */
    private static float shape(float v) {
        return (float) Math.pow(clamp01(v), GAMMA);
    }

    // ── Emitters ──────────────────────────────────────────────────────────────
    // Each emitter draws through the GeometryMapper (which owns spread/phase/mirror + LINEAR vs
    // RADIAL). At the mapper's legacy defaults (spread 1, phase 0, mirror false) and layer gain 1
    // the arithmetic collapses to the EXACT pre-refactor math — that is the pixel-parity guarantee.

    /** LINEAR bars. justify: 0=bottom (grow up), 1=center (mirror up+down), 2=top (grow down). */
    private void drawBars(@NonNull Canvas canvas, @NonNull float[] heights,
                          @NonNull GeometryMapper m, @NonNull VizLayer layer, @NonNull Paint paint) {
        int n = heights.length;
        if (n == 0) return;
        float usedW = m.usedWidth();
        float originX = m.originX();
        float slot = usedW / (float) n;
        float gap = layer.barGapDp * m.density;
        float barW = Math.max(1f, slot - gap);
        float corner = layer.cornerRadiusDp * m.density;
        boolean center = m.justify == 1;
        float baseline = center ? m.h / 2f : (m.justify == 2 ? 0f : m.h);
        float maxUp = center ? m.h / 2f : m.h;
        for (int i = 0; i < n; i++) {
            int di = m.place(i, n);
            float left = originX + di * slot + (slot - barW) / 2f;
            float barH = heights[i] * layer.gain * maxUp;
            RectF r;
            if (center) {
                r = new RectF(left, baseline - barH, left + barW, baseline + barH);
            } else if (m.justify == 2) {
                r = new RectF(left, 0f, left + barW, barH);
            } else {
                r = new RectF(left, m.h - barH, left + barW, m.h);
            }
            canvas.drawRoundRect(r, corner, corner, paint);
        }
    }

    /** RADIAL bars — bars shoot outward from the centre ring (spec §2 "radial for free"). */
    private void drawRadialBars(@NonNull Canvas canvas, @NonNull float[] heights,
                                @NonNull GeometryMapper m, @NonNull VizLayer layer,
                                @NonNull Paint paint) {
        int n = heights.length;
        if (n == 0) return;
        float gap = layer.barGapDp * m.density;
        float barW = Math.max(1f, m.circumference / n - gap);
        float corner = layer.cornerRadiusDp * m.density;
        for (int i = 0; i < n; i++) {
            float barLen = heights[i] * layer.gain * m.maxExtent;
            if (barLen < 0.5f) continue;
            canvas.save();
            canvas.rotate(m.angle(i, n), m.cx, m.cy);
            float left = m.cx - barW / 2f;
            float top = m.cy + m.ringR;
            float right = m.cx + barW / 2f;
            float bottom = top + barLen;
            canvas.drawRoundRect(left, top, right, bottom, corner, corner, paint);
            canvas.restore();
        }
    }

    private void drawLine(@NonNull Canvas canvas, @NonNull float[] heights,
                          @NonNull GeometryMapper m, @NonNull VizLayer layer, @NonNull Paint paint) {
        Paint stroke = new Paint(paint);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, layer.barWidthDp * m.density));
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        buildLinePath(heights, m, layer);
        canvas.drawPath(path, stroke);
    }

    private void drawFilled(@NonNull Canvas canvas, @NonNull float[] heights,
                            @NonNull GeometryMapper m, @NonNull VizLayer layer, @NonNull Paint paint) {
        buildLinePath(heights, m, layer);
        path.lineTo(m.w, m.h);
        path.lineTo(0, m.h);
        path.close();
        canvas.drawPath(path, paint);
    }

    /**
     * DOTS (new in P1) — one circle per band, radius ∝ energy·barWidth, positioned at the band's
     * tip. New emitter, so no legacy parity constraint; it's the proof the pipeline generalizes.
     */
    private void drawDots(@NonNull Canvas canvas, @NonNull float[] heights,
                          @NonNull GeometryMapper m, @NonNull VizLayer layer, @NonNull Paint paint) {
        int n = heights.length;
        if (n == 0) return;
        float usedW = m.usedWidth();
        float originX = m.originX();
        float slot = usedW / (float) n;
        float maxR = layer.barWidthDp * m.density;
        boolean center = m.justify == 1;
        float maxUp = center ? m.h / 2f : m.h;
        for (int i = 0; i < n; i++) {
            int di = m.place(i, n);
            float e = heights[i] * layer.gain;
            float r = Math.max(0.5f, e * maxR);
            float dx = originX + (di + 0.5f) * slot;
            float dy;
            if (center) {
                dy = m.h / 2f;
            } else if (m.justify == 2) {
                dy = e * maxUp;
            } else {
                dy = m.h - e * maxUp;
            }
            canvas.drawCircle(dx, dy, r, paint);
        }
    }

    /** RADIAL dots — a pulsing ring of circles at the bar tips (dots gets radial for free too). */
    private void drawRadialDots(@NonNull Canvas canvas, @NonNull float[] heights,
                                @NonNull GeometryMapper m, @NonNull VizLayer layer,
                                @NonNull Paint paint) {
        int n = heights.length;
        if (n == 0) return;
        float maxR = layer.barWidthDp * m.density;
        for (int i = 0; i < n; i++) {
            float e = heights[i] * layer.gain;
            float r = Math.max(0.5f, e * maxR);
            float barLen = e * m.maxExtent;
            canvas.save();
            canvas.rotate(m.angle(i, n), m.cx, m.cy);
            canvas.drawCircle(m.cx, m.cy + m.ringR + barLen, r, paint);
            canvas.restore();
        }
    }

    /**
     * SQUARES (P2) — a rounded square per band, centred on the band tip like {@link #drawDots}. Side
     * = energy·barWidthDp·density·2, clamped to the slot so neighbours never overlap. New emitter, no
     * legacy-parity constraint.
     */
    private void drawSquares(@NonNull Canvas canvas, @NonNull float[] heights,
                             @NonNull GeometryMapper m, @NonNull VizLayer layer, @NonNull Paint paint) {
        int n = heights.length;
        if (n == 0) return;
        float usedW = m.usedWidth();
        float originX = m.originX();
        float slot = usedW / (float) n;
        float maxSide = layer.barWidthDp * m.density * 2f;
        float corner = layer.cornerRadiusDp * m.density;
        boolean center = m.justify == 1;
        float maxUp = center ? m.h / 2f : m.h;
        for (int i = 0; i < n; i++) {
            int di = m.place(i, n);
            float e = heights[i] * layer.gain;
            float side = Math.min(slot, Math.max(1f, e * maxSide));
            float cx = originX + (di + 0.5f) * slot;
            float cy;
            if (center) {
                cy = m.h / 2f;
            } else if (m.justify == 2) {
                cy = e * maxUp;
            } else {
                cy = m.h - e * maxUp;
            }
            canvas.drawRoundRect(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f,
                    corner, corner, paint);
        }
    }

    /** RADIAL squares — the square emitter around the ring (squares gets radial for free too). */
    private void drawRadialSquares(@NonNull Canvas canvas, @NonNull float[] heights,
                                   @NonNull GeometryMapper m, @NonNull VizLayer layer,
                                   @NonNull Paint paint) {
        int n = heights.length;
        if (n == 0) return;
        float maxSide = layer.barWidthDp * m.density * 2f;
        float slot = m.circumference / n;
        float corner = layer.cornerRadiusDp * m.density;
        for (int i = 0; i < n; i++) {
            float e = heights[i] * layer.gain;
            float side = Math.min(slot, Math.max(1f, e * maxSide));
            float barLen = e * m.maxExtent;
            canvas.save();
            canvas.rotate(m.angle(i, n), m.cx, m.cy);
            float cy = m.cy + m.ringR + barLen;
            canvas.drawRoundRect(m.cx - side / 2f, cy - side / 2f, m.cx + side / 2f, cy + side / 2f,
                    corner, corner, paint);
            canvas.restore();
        }
    }

    /**
     * PEAKS (P2) — a thin peak-hold cap per band at the falling peak level ({@code peaks}, computed
     * once per render in {@link #computePeaks}). Draws ONLY the cap (peaks stacks over a bars layer),
     * matching the bar slot geometry so the cap sits centred over its bar.
     */
    private void drawPeaks(@NonNull Canvas canvas, @NonNull float[] peaks,
                           @NonNull GeometryMapper m, @NonNull VizLayer layer, @NonNull Paint paint) {
        int n = peaks.length;
        if (n == 0) return;
        float usedW = m.usedWidth();
        float originX = m.originX();
        float slot = usedW / (float) n;
        float gap = layer.barGapDp * m.density;
        float barW = Math.max(1f, slot - gap);
        float corner = layer.cornerRadiusDp * m.density;
        float thick = Math.max(2f * m.density, barW / 2f);
        boolean center = m.justify == 1;
        float baseline = center ? m.h / 2f : (m.justify == 2 ? 0f : m.h);
        float maxUp = center ? m.h / 2f : m.h;
        for (int i = 0; i < n; i++) {
            int di = m.place(i, n);
            float left = originX + di * slot + (slot - barW) / 2f;
            float peakH = peaks[i] * layer.gain * maxUp;
            if (center) {
                cap(canvas, left, baseline - peakH, barW, thick, corner, paint);
                cap(canvas, left, baseline + peakH, barW, thick, corner, paint);
            } else if (m.justify == 2) {
                cap(canvas, left, peakH, barW, thick, corner, paint);
            } else {
                cap(canvas, left, m.h - peakH, barW, thick, corner, paint);
            }
        }
    }

    /** Draw a single peak cap: a rounded rect of {@code barW}×{@code thick} centred vertically on cy. */
    private static void cap(@NonNull Canvas canvas, float left, float cy, float barW, float thick,
                            float corner, @NonNull Paint paint) {
        canvas.drawRoundRect(left, cy - thick / 2f, left + barW, cy + thick / 2f, corner, corner, paint);
    }

    /** RADIAL peaks — the cap laid perpendicular to the ray at {@code ringR + peakLen}. */
    private void drawRadialPeaks(@NonNull Canvas canvas, @NonNull float[] peaks,
                                 @NonNull GeometryMapper m, @NonNull VizLayer layer,
                                 @NonNull Paint paint) {
        int n = peaks.length;
        if (n == 0) return;
        float gap = layer.barGapDp * m.density;
        float barW = Math.max(1f, m.circumference / n - gap);
        float corner = layer.cornerRadiusDp * m.density;
        float thick = Math.max(2f * m.density, barW / 2f);
        for (int i = 0; i < n; i++) {
            float peakLen = peaks[i] * layer.gain * m.maxExtent;
            canvas.save();
            canvas.rotate(m.angle(i, n), m.cx, m.cy);
            float cy = m.cy + m.ringR + peakLen; // radial distance; the cap spans ±barW/2 across the ray
            canvas.drawRoundRect(m.cx - barW / 2f, cy - thick / 2f, m.cx + barW / 2f, cy + thick / 2f,
                    corner, corner, paint);
            canvas.restore();
        }
    }

    /**
     * RING (P2, radial-only) — a stroked circle that pulses with the music. Radius = {@code ringR +
     * rms·maxExtent} where {@code rms} is the mean of the band energies (gain-scaled); stroke width =
     * barWidthDp·density. The linear path never reaches here (skipped in {@link #drawLayer}).
     */
    private void drawRing(@NonNull Canvas canvas, @NonNull float[] energies,
                          @NonNull GeometryMapper m, @NonNull VizLayer layer, @NonNull Paint paint) {
        int n = energies.length;
        if (n == 0) return;
        float sum = 0f;
        for (int i = 0; i < n; i++) sum += energies[i];
        float rms = (sum / n) * layer.gain; // mean band energy (spec §2 "pulses with RMS")
        float radius = m.ringR + rms * m.maxExtent;
        Paint stroke = new Paint(paint); // inherit colour/alpha/xfermode/maskfilter, switch to STROKE
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, layer.barWidthDp * m.density));
        canvas.drawCircle(m.cx, m.cy, radius, stroke);
    }

    /**
     * PARTICLES (P3, spec §3) — a pure function of time; NO sim state anywhere. Each band b owns
     * {@code particleCount} slots; slot i's spawn phase is a fixed hash of (b, i), so births form a
     * deterministic lattice: age = (t + phase·LIFE) mod LIFE, birth = t − age. The birth time,
     * quantized to the absolute STEP grid, indexes the SAME banded energy cache the bars read (via
     * the sampler memo) — that one birth energy fixes the particle's size, speed and brightness for
     * its whole life. Preview, scrub and export evaluate the identical closed form at any t.
     * A silent band at birth spawns nothing, so particles visibly follow the music.
     */
    private void drawParticles(@NonNull Canvas canvas, @NonNull GeometryMapper m,
                               @NonNull VizLayer layer, @NonNull Paint paint,
                               @NonNull TapSampler sampler, long tMs, int n, boolean radial) {
        if (n == 0) return;
        int count = Math.max(1, Math.min(layer.particleCount, PARTICLE_BUDGET / n));
        float maxR = layer.barWidthDp * m.density;
        int baseAlpha = paint.getAlpha();
        boolean center = m.justify == 1;
        float maxUp = center ? m.h / 2f : m.h;
        float slot = m.usedWidth() / n;
        for (int b = 0; b < n; b++) {
            for (int i = 0; i < count; i++) {
                long phaseMs = (long) (hash01(b, i, 1) * PARTICLE_LIFE_MS);
                long age = (tMs + phaseMs) % PARTICLE_LIFE_MS; // tMs ≥ 0 on every call path
                long birthQ = Math.max(0L, tMs - age) / PARTICLE_STEP_MS * PARTICLE_STEP_MS;
                float e0 = sampler.at(birthQ)[b];
                if (e0 < 0.10f) continue; // silence at birth spawns nothing
                float aFrac = age / (float) PARTICLE_LIFE_MS;
                float env = Math.min(1f, age / 150f) * (1f - aFrac); // fast fade-in, linear fade-out
                float alpha = env * (0.35f + 0.65f * Math.min(1f, e0 * layer.gain));
                if (alpha <= 0.02f) continue;
                float risePx = (age / 1000f) * layer.particleSpeed * (30f + 90f * e0) * m.density;
                float r = Math.max(0.5f, maxR * (0.35f + 0.65f * e0) * (1f - 0.5f * aFrac));
                paint.setAlpha(Math.round(baseAlpha * alpha));
                if (radial) {
                    // Drift outward along the band's ray from the ring tip at birth energy.
                    float jDeg = (hash01(b, i, 2) - 0.5f) * m.slotAngle(n);
                    float dist = m.ringR + e0 * layer.gain * m.maxExtent + risePx;
                    canvas.save();
                    canvas.rotate(m.angle(b, n) + jDeg, m.cx, m.cy);
                    canvas.drawCircle(m.cx, m.cy + dist, r, paint);
                    canvas.restore();
                } else {
                    // Drift away from the baseline from the band's tip at birth energy.
                    int db = m.place(b, n);
                    float x = m.originX() + (db + 0.5f) * slot
                            + (hash01(b, i, 2) - 0.5f) * slot * 0.8f;
                    float tip = e0 * layer.gain * maxUp;
                    float y;
                    if (center) {
                        float dir = hash01(b, i, 3) < 0.5f ? -1f : 1f;
                        y = m.h / 2f + dir * (tip + risePx);
                    } else if (m.justify == 2) {
                        y = tip + risePx;
                    } else {
                        y = m.h - tip - risePx;
                    }
                    if (y < -r || y > m.h + r) continue;
                    canvas.drawCircle(x, y, r, paint);
                }
            }
        }
        paint.setAlpha(baseAlpha); // restore — the paint is shared across the layer's passes
    }

    /** Deterministic per-(band, slot, salt) hash → [0,1). Integer mix only — identical everywhere. */
    private static float hash01(int b, int i, int salt) {
        int h = b * 73856093 ^ i * 19349663 ^ salt * 83492791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h >>> 8 & 0xFFFF) / 65536f;
    }

    private void buildLinePath(@NonNull float[] heights, @NonNull GeometryMapper m,
                               @NonNull VizLayer layer) {
        path.reset();
        int n = heights.length;
        float usedW = m.usedWidth();
        float originX = m.originX();
        for (int i = 0; i < n; i++) {
            int di = m.place(i, n);
            float x = n <= 1 ? originX : originX + (di / (float) (n - 1)) * usedW;
            float y = m.h - heights[i] * layer.gain * m.h;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
    }

    /**
     * GeometryMapper (spec §2): places band index {@code i} of {@code n} into canvas space,
     * applying the layer's spread (fraction of strip/arc used), phase (offset) and mirror (reversed
     * placement order), for both LINEAR (x = band strip) and RADIAL (θ = band ring) geometry. At the
     * legacy defaults (spread 1, phase 0, mirror false) every helper collapses to the exact legacy
     * math — {@code originX()==0}, {@code usedWidth()==w}, {@code angle(i,n)==i*360/n} — so an
     * auto-wrapped legacy layer draws pixel-identically. Pure value object: no per-frame state.
     */
    private static final class GeometryMapper {
        final boolean radial;
        final int w, h;
        final float density;
        final int justify;
        final float spread, phaseDeg;
        final boolean mirror;
        // Radial ring precompute — matches drawRadialBars' old local math exactly.
        final float cx, cy, ringR, maxExtent, circumference;

        GeometryMapper(boolean radial, int w, int h, float density, int justify,
                       float radialRingSize, @NonNull VizLayer layer) {
            this.radial = radial;
            this.w = w;
            this.h = h;
            this.density = density;
            this.justify = justify;
            this.spread = layer.spread;
            this.phaseDeg = layer.phaseDeg;
            this.mirror = layer.mirror;
            this.cx = w / 2f;
            this.cy = h / 2f;
            float maxR = Math.min(cx, cy);
            this.ringR = maxR * Math.max(0.05f, Math.min(0.95f, radialRingSize));
            this.maxExtent = maxR - ringR;
            this.circumference = 2f * (float) Math.PI * ringR;
        }

        /** Draw-position index for band {@code i} (mirror reverses the placement order only). */
        int place(int i, int n) {
            return mirror ? (n - 1 - i) : i;
        }

        // LINEAR — width actually used and its left origin (phase shifts the whole strip).
        float usedWidth() {
            return w * spread;
        }
        float originX() {
            return (w - usedWidth()) / 2f + (phaseDeg / 360f) * usedWidth();
        }

        // RADIAL — per-band rotation about the centre (phase adds a rotation offset).
        float slotAngle(int n) {
            return n <= 0 ? 0f : (360f * spread) / n;
        }
        float angle(int i, int n) {
            return phaseDeg + place(i, n) * slotAngle(n);
        }
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static int parseColor(String hex, int fallback) {
        if (hex == null) return fallback;
        try {
            return Color.parseColor(hex);
        } catch (Exception e) {
            return fallback;
        }
    }
}
