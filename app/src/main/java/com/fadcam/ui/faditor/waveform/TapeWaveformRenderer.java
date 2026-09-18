package com.fadcam.ui.faditor.waveform;

import com.fadcam.ui.faditor.Studio;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.BandedWaveformData;

/**
 * Draws the quad-band tape waveform into a timeline audio-item rect — the Android Canvas port
 * of the prototype's {@code drawTape} + {@code drawBand}. Baseline at 60% of the rect height;
 * the top lane carries the bands assigned {@code -1} (vocals + presence by default), the bottom
 * lane those assigned {@code +1} (bass + highs); within a lane bands layer low→high (lowest =
 * solid gradient back, higher = translucent overlays). Optional baseline glow, rounded profile,
 * and peak sparks match the prototype.
 *
 * <p>Glow is done with a two-pass stroke (fat translucent + thin bright) rather than a blur
 * shadow so it renders correctly under hardware acceleration and stays cheap. Draws only fills,
 * gradients, strokes and dots — nothing that forces a software layer.</p>
 */
public class TapeWaveformRenderer {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baselinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;

    /** Scratch per-band column buffers (one per band so overlays can tint against the solid). */
    private final float[][] colScratch = new float[BandedWaveformData.BAND_COUNT][];

    public TapeWaveformRenderer(float density) {
        this.density = density;
        stroke.setStyle(Paint.Style.STROKE);
        spark.setStyle(Paint.Style.FILL);
        baselinePaint.setStyle(Paint.Style.STROKE);
        baselinePaint.setStrokeWidth(Math.max(1f, density));
    }

    /**
     * @param raw       band data (for {@code frameAt} timing); {@code shaped} runs parallel to it.
     * @param shaped    quantized mip pyramid from {@link BandEnvelopeShaper#shapeToTape} (A1/A2).
     * @param clipInMs  source in-point of the item (== rect left edge in source time).
     * @param clipDurMs source duration mapped across the rect width.
     */
    public void draw(@NonNull Canvas canvas, @NonNull RectF rect,
                     @NonNull BandedWaveformData raw, @NonNull ShapedTape shaped,
                     @NonNull TapeWaveformStyle style, long clipInMs, long clipDurMs) {
        final int W = Math.max(1, (int) rect.width());
        final float pad = 2f * density;
        final float baseY = rect.top + Math.round(rect.height() * 0.60f);
        final float topH = Math.max(1f, baseY - rect.top - pad);
        final float botH = Math.max(1f, rect.bottom - baseY - pad);

        // Build per-band columns (only for visible, computed bands).
        for (int b = 0; b < BandedWaveformData.BAND_COUNT; b++) {
            colScratch[b] = (style.bandVisible(b) && shaped.hasBand(b))
                    ? columns(shaped, b, raw, W, rect.left, clipInMs, clipDurMs, colScratch[b])
                    : null;
        }

        // Each lane: enabled bands low→high; lowest is the solid back layer.
        for (int dir = -1; dir <= 1; dir += 2) {
            final float laneH = dir < 0 ? topH : botH;
            boolean first = true;
            float[] solidCol = null;
            for (int b = 0; b < BandedWaveformData.BAND_COUNT; b++) {
                if (style.bandLane[b] != dir || colScratch[b] == null) continue;
                drawBand(canvas, b, rect, baseY, laneH, dir, first, solidCol, W, style);
                if (first) solidCol = colScratch[b];
                first = false;
            }
        }

        // Baseline glow: soft colored gradient where each lane's front band meets the center.
        if (style.fxGlow) {
            for (int dir = -1; dir <= 1; dir += 2) {
                int fb = -1;
                for (int b = 0; b < BandedWaveformData.BAND_COUNT; b++) {
                    if (style.bandLane[b] == dir && colScratch[b] != null) { fb = b; break; }
                }
                if (fb < 0) continue;
                float ext = 12f * density;
                int c = style.bandColor[fb];
                fill.setShader(new LinearGradient(0, baseY, 0, baseY + dir * ext,
                        withAlpha(c, 0.18f), withAlpha(c, 0f), Shader.TileMode.CLAMP));
                float gy0 = Math.min(baseY, baseY + dir * ext);
                canvas.drawRect(rect.left, gy0, rect.right, gy0 + ext, fill);
                fill.setShader(null);
            }
        }

        // Baseline.
        baselinePaint.setColor(0x29FFFFFF);
        canvas.drawLine(rect.left, baseY, rect.right, baseY, baselinePaint);
    }

    private void drawBand(@NonNull Canvas canvas, int band, @NonNull RectF rect,
                          float baseY, float laneH, int dir, boolean solid, float[] solidCol,
                          int W, @NonNull TapeWaveformStyle style) {
        final float[] col = colScratch[band];
        final int c = style.bandColor[band];
        final float x0 = rect.left;

        // Fill path: baseline → profile edge → back to baseline.
        path.reset();
        path.moveTo(x0, baseY);
        traceEdge(path, col, x0, baseY, laneH, dir, W, style.fxRound);
        path.lineTo(x0 + W, baseY);
        path.close();
        int fillTop = solid ? lighten(c, 0.25f) : withAlpha(lighten(c, 0.12f), 0.55f);
        int fillBase = solid ? darken(c, 0.22f) : withAlpha(c, 0.08f);
        fill.setShader(new LinearGradient(0, baseY, 0, baseY + dir * laneH,
                new int[]{fillBase, solid ? c : withAlpha(lighten(c, 0.12f), 0.30f), fillTop},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        fill.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, fill);
        fill.setShader(null);

        // Overlap tint: lift the overlay toward white where it crosses the lane's solid band.
        if (!solid && solidCol != null && style.fxTint) {
            path.reset();
            path.moveTo(x0, baseY);
            for (int x = 0; x <= W; x++) {
                float v = Math.min(col[x], solidCol[x]);
                path.lineTo(x0 + x, baseY + dir * v * laneH);
            }
            path.lineTo(x0 + W, baseY);
            path.close();
            fill.setColor(0x17FFFFFF);
            canvas.drawPath(path, fill);
        }

        // Edge with a soft two-pass glow (fat translucent under a thin bright stroke).
        path.reset();
        path.moveTo(x0, baseY + dir * col[0] * laneH);
        traceEdge(path, col, x0, baseY, laneH, dir, W, style.fxRound);
        int edgeCol = lighten(c, 0.28f);
        stroke.setColor(withAlpha(edgeCol, 0.28f));
        stroke.setStrokeWidth((solid ? 1.25f : 1.5f) * density + 2.5f * density);
        canvas.drawPath(path, stroke);
        stroke.setColor(edgeCol);
        stroke.setStrokeWidth((solid ? 1.25f : 1.5f) * density);
        canvas.drawPath(path, stroke);

        // Peak sparks: dots on local maxima (transient onsets / cut cues).
        if (style.fxSparks) {
            spark.setColor(Studio.INK);
            // 1.6→0.7dp (JoyRaptor 2026-07-16): the dots were "quite large relative to the tape…
            // a little bit obnoxious" — about a third the size reads as accents, not markers.
            float r = 0.7f * density;
            for (int x = 6; x < W - 6; x++) {
                float v = col[x];
                if (v < 0.3f) continue;
                boolean isMax = true;
                boolean prominent = false; // at least one strictly-lower neighbor
                for (int k = -6; k <= 6; k++) {
                    if (col[x + k] > v) { isMax = false; break; }
                    if (col[x + k] < v) prominent = true;
                }
                // Plateau guard: a flat run (e.g. limited/maxed music, or silence) is not a
                // transient — without this every plateau pixel "won" and drew a dotted line.
                if (isMax && prominent) {
                    canvas.drawCircle(x0 + x, baseY + dir * v * laneH, r, spark);
                    x += 6;
                }
            }
        }
    }

    /** Trace the profile edge across the band, optionally as smooth quadratic curves. */
    private void traceEdge(@NonNull Path p, @NonNull float[] col, float x0, float baseY,
                           float laneH, int dir, int W, boolean rounded) {
        if (!rounded) {
            for (int x = 0; x <= W; x++) p.lineTo(x0 + x, baseY + dir * col[x] * laneH);
            return;
        }
        final int st = 5;
        p.lineTo(x0, baseY + dir * col[0] * laneH);
        int px = 0;
        for (int x = st; x <= W; x += st) {
            float ypx = baseY + dir * col[px] * laneH;
            float yx = baseY + dir * col[x] * laneH;
            p.quadTo(x0 + px, ypx, x0 + (px + x) / 2f, (ypx + yx) / 2f);
            px = x;
        }
        p.lineTo(x0 + W, baseY + dir * col[W] * laneH);
    }

    /**
     * Per-pixel column values across the rect: max-in-bucket when many frames fall under a pixel
     * (zoomed out — preserves transients), linear-interpolated when a frame spans many pixels
     * (zoomed in), then a light 3-tap smooth. Reuses {@code scratch} when it already fits.
     *
     * <p>A1: when zoomed out the per-pixel bucket max reads from the mip pyramid — level
     * {@code floor(log2(framesPerPx))} — so the inner scan collapses from ~{@code framesPerPx}
     * reads to ~1. At {@code framesPerPx < 2} this reads level 0 (identical to the old scan).
     * A2: every byte read masks {@code & 0xFF} and divides by 255.</p>
     */
    @NonNull
    private static float[] columns(@NonNull ShapedTape tape, int band, @NonNull BandedWaveformData raw,
                                   int W, float rectLeft, long clipInMs, long clipDurMs,
                                   float[] scratch) {
        float[] v = (scratch != null && scratch.length >= W + 1) ? scratch : new float[W + 1];
        float framesPerPx = clipDurMs > 0
                ? (clipDurMs / 1000f * raw.envRate) / W : 0f;
        if (framesPerPx >= 1f) {
            final int L = tape.levelFor(band, framesPerPx);
            final byte[] lvl = tape.level(band, L);
            final int lvlLen = lvl.length;
            for (int x = 0; x <= W; x++) {
                long t0 = clipInMs + (long) ((float) x / W * clipDurMs);
                long t1 = clipInMs + (long) ((float) (x + 1) / W * clipDurMs);
                int f0 = raw.frameAt(t0);
                int f1 = Math.max(f0 + 1, raw.frameAt(Math.max(t0, t1 - 1)) + 1);
                // Level-0 frame range [f0,f1) maps to level-L index range [f0>>L, ((f1-1)>>L)+1).
                int i0 = f0 >> L;
                int i1 = ((f1 - 1) >> L) + 1;
                if (i1 > lvlLen) i1 = lvlLen;
                int m = 0;
                for (int i = i0; i < i1; i++) {
                    int s = lvl[i] & 0xFF;
                    if (s > m) m = s;
                }
                v[x] = m / 255f;
            }
            // Pixel-space 3-tap: kills residual hair when zoomed way out.
            float prev = v[0];
            for (int x = 0; x <= W; x++) {
                float a = prev;
                float b = v[x];
                float cc = v[Math.min(W, x + 1)];
                prev = v[x];
                v[x] = (a + 2 * b + cc) / 4f;
            }
            return v;
        }
        final byte[] lvl0 = tape.level(band, 0);
        for (int x = 0; x <= W; x++) {
            long t = clipInMs + (long) ((float) x / W * clipDurMs);
            float ff = (t - raw.startOffsetMs) * raw.envRate / 1000f;
            int f0 = (int) Math.floor(ff);
            float fr = ff - f0;
            float a = sample(lvl0, f0);
            float c = sample(lvl0, f0 + 1);
            v[x] = a + (c - a) * fr;
        }
        return v;
    }

    private static float sample(@NonNull byte[] band, int i) {
        if (band.length == 0) return 0f;
        // Past the real data (beyond a 2-bin rounding tolerance) return SILENCE instead of
        // clamping to the last bin — clamping stretched the final value into flat horizontal
        // "stripes" across the whole un-analyzed remainder of a partially-extracted long clip
        // (JoyRaptor, 45-min lecture, 2026-07-16). Rounding at the strip's last pixel still clamps.
        if (i >= band.length + 2) return 0f;
        return (band[Math.max(0, Math.min(i, band.length - 1))] & 0xFF) / 255f;
    }

    // ── color helpers (no allocation) ──
    private static int darken(int color, float f) {
        int r = (int) (((color >> 16) & 0xFF) * f);
        int g = (int) (((color >> 8) & 0xFF) * f);
        int b = (int) ((color & 0xFF) * f);
        return Studio.GROUND | (r << 16) | (g << 8) | b;
    }

    private static int lighten(int color, float f) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        r = (int) (r + (255 - r) * f);
        g = (int) (g + (255 - g) * f);
        b = (int) (b + (255 - b) * f);
        return Studio.GROUND | (r << 16) | (g << 8) | b;
    }

    private static int withAlpha(int color, float a) {
        int alpha = Math.max(0, Math.min(255, (int) (a * 255)));
        return (alpha << 24) | (color & 0x00FFFFFF);
    }
}
