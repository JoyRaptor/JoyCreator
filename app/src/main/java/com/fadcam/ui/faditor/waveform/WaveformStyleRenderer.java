package com.fadcam.ui.faditor.waveform;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
    private final Path path = new Path();

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

    private void drawFrame(@NonNull Canvas canvas, @NonNull WaveformData data,
                           @NonNull WaveformStyle style, int w, int h, long atMs, float density,
                           int justifyOverride, int dataModeOverride, boolean hMirror,
                           int centerMode, int renderMode, float radialRingSize,
                           int freqLowHz, int freqHighHz, int bandCountOverride) {
        if (data.bucketCount() == 0) return;
        int primary = parseColor(style.color, 0xFF00E676);
        configurePaint(barPaint, style, primary, h);

        boolean hasGlow = style.glowRadiusDp > 0f && style.glowColor != null;
        if (hasGlow) {
            glowPaint.set(barPaint);
            glowPaint.setColor(parseColor(style.glowColor, primary));
            glowPaint.setShader(null);
            glowPaint.setMaskFilter(ensureGlowFilter(style.glowRadiusDp, density));
        }

        // Resolve the orthogonal architecture: data source, vertical justify, and shape — overrides
        // win, else fall back to what the style's type implies.
        boolean spectrum = dataModeOverride == 1 || (dataModeOverride < 0 && style.drawsSpectrum());
        int justify = justifyOverride >= 0 ? justifyOverride : defaultJustify(style);
        String shape = shapeOf(style.type);

        float[] heights = sampleHeights(data, style, atMs, spectrum, hMirror, centerMode,
                freqLowHz, freqHighHz, bandCountOverride);
        if (renderMode == 1) {
            if (hasGlow) drawRadialBars(canvas, heights, w, h, style, glowPaint, density, radialRingSize);
            drawRadialBars(canvas, heights, w, h, style, barPaint, density, radialRingSize);
            return;
        }
        switch (shape) {
            case "line":
                if (hasGlow) drawLine(canvas, heights, w, h, style, glowPaint, density);
                drawLine(canvas, heights, w, h, style, barPaint, density);
                break;
            case "filled":
                if (hasGlow) drawFilled(canvas, heights, w, h, glowPaint);
                drawFilled(canvas, heights, w, h, barPaint);
                break;
            default:
                if (hasGlow) drawBars(canvas, heights, w, h, style, glowPaint, density, justify);
                drawBars(canvas, heights, w, h, style, barPaint, density, justify);
                break;
        }
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
        if (progress < 0f) {
            configurePaint(barPaint, style, primary, h);
            barPaint.setAlpha(70);
            drawBars(canvas, heights, w, h, style, barPaint, density, justify);
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
        drawBars(canvas, dim, w, h, style, barPaint, density, justify);
        configurePaint(barPaint, style, primary, h);
        barPaint.setAlpha(200);
        drawBars(canvas, lit, w, h, style, barPaint, density, justify);
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

    /** justify: 0=bottom (grow up), 1=center (mirror up+down), 2=top (grow down). */
    private void drawBars(@NonNull Canvas canvas, @NonNull float[] heights, int w, int h,
                          @NonNull WaveformStyle style, @NonNull Paint paint, float density,
                          int justify) {
        int n = heights.length;
        float slot = w / (float) n;
        float gap = style.barGapDp * density;
        float barW = Math.max(1f, slot - gap);
        float corner = style.cornerRadiusDp * density;
        boolean center = justify == 1;
        float baseline = center ? h / 2f : (justify == 2 ? 0f : h);
        float maxUp = center ? h / 2f : h;
        for (int i = 0; i < n; i++) {
            float left = i * slot + (slot - barW) / 2f;
            float barH = heights[i] * maxUp;
            RectF r;
            if (center) {
                r = new RectF(left, baseline - barH, left + barW, baseline + barH);
            } else if (justify == 2) {
                r = new RectF(left, 0f, left + barW, barH);
            } else {
                r = new RectF(left, h - barH, left + barW, h);
            }
            canvas.drawRoundRect(r, corner, corner, paint);
        }
    }

    private void drawRadialBars(@NonNull Canvas canvas, @NonNull float[] heights, int w, int h,
                                @NonNull WaveformStyle style, @NonNull Paint paint, float density,
                                float ringSize) {
        float cx = w / 2f;
        float cy = h / 2f;
        float maxR = Math.min(cx, cy);
        float ringR = maxR * Math.max(0.05f, Math.min(0.95f, ringSize));
        float maxExtent = maxR - ringR;
        int n = heights.length;
        if (n == 0) return;
        float slotAngle = 360f / n;
        float circumference = 2f * (float) Math.PI * ringR;
        float gap = style.barGapDp * density;
        float barW = Math.max(1f, circumference / n - gap);
        float corner = style.cornerRadiusDp * density;
        for (int i = 0; i < n; i++) {
            float barLen = heights[i] * maxExtent;
            if (barLen < 0.5f) continue;
            canvas.save();
            canvas.rotate(i * slotAngle, cx, cy);
            float left = cx - barW / 2f;
            float top = cy + ringR;
            float right = cx + barW / 2f;
            float bottom = top + barLen;
            canvas.drawRoundRect(left, top, right, bottom, corner, corner, paint);
            canvas.restore();
        }
    }

    private void drawLine(@NonNull Canvas canvas, @NonNull float[] heights, int w, int h,
                          @NonNull WaveformStyle style, @NonNull Paint paint, float density) {
        Paint stroke = new Paint(paint);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, style.barWidthDp * density));
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        buildLinePath(heights, w, h);
        canvas.drawPath(path, stroke);
    }

    private void drawFilled(@NonNull Canvas canvas, @NonNull float[] heights, int w, int h,
                            @NonNull Paint paint) {
        buildLinePath(heights, w, h);
        path.lineTo(w, h);
        path.lineTo(0, h);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void buildLinePath(@NonNull float[] heights, int w, int h) {
        path.reset();
        int n = heights.length;
        for (int i = 0; i < n; i++) {
            float x = n <= 1 ? 0 : (i / (float) (n - 1)) * w;
            float y = h - heights[i] * h;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
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
