package com.fadcam.ui.faditor.slides;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.text.FontLibrary;

import java.util.List;

/**
 * ONE renderer used by both preview and export (SPEC §5). Rasterises on CUE BOUNDARY only,
 * holds ONE bitmap and swaps when cue changes. Uses ping-pong buffers on export side
 * (see SlideDeckOverlay) to avoid per-frame allocation.
 *
 * <p>Draws restricted-HTML runs via StaticLayout/Canvas — deterministic, fast.</p>
 */
public final class SlideDeckRenderer {

    private SlideDeckRenderer() {}

    /** Result of rasterising one slide. */
    public static class Raster {
        @NonNull public final Bitmap bitmap;
        public final int cueIndex;
        Raster(@NonNull Bitmap b, int idx) { bitmap = b; cueIndex = idx; }
    }

    /**
     * Render slide at cue index into a bitmap sized to deck geometry within outW/outH.
     * Caller owns returned bitmap and must recycle when swapping (ping-pong).
     */
    @Nullable
    public static Bitmap render(@NonNull SlideDeck deck, int cueIndex, int outW, int outH) {
        if (cueIndex < 0 || cueIndex >= deck.getSlides().size()) return null;
        SlideDeck.Slide slide = deck.getSlides().get(cueIndex);
        List<SlideDeck.StyledRun> runs = slide.runs();
        if (runs.isEmpty()) return null;

        int boxW = Math.max(1, Math.round(outW * deck.getWidthFraction()));
        int boxH = Math.max(1, Math.round(outH * deck.getHeightFraction()));
        // Render card bitmap (box size)
        Bitmap bmp = Bitmap.createBitmap(boxW, boxH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        // Background with rounded corners
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(deck.getBackgroundColor());
        float r = deck.getCornerRadiusDp() * 2f; // approx dp->px (caller density? use 2)
        canvas.drawRoundRect(0,0,boxW,boxH,r,r,bg);

        // Build spanned-like text: we draw run-by-run with StaticLayout per run? Instead build one layout
        // with varying paint per run is complex; simplify: create a single string with style spans via TextPaint variation
        // Approach: concatenate texts, create a single StaticLayout with default paint, but apply per-run paints via custom draw?
        // Simpler: draw each run sequentially using StaticLayout line wrapping manually? For MVP, join with plain drawing using TextPaint per run but wrap via StaticLayout per paragraph.

        // For simplicity: build a single CharSequence and use TextPaint with spans would need Spanned; instead we approximate:
        // Draw runs sequentially, wrapping greedily. Since boxW is modest and slide has two lines typical, this is sufficient and deterministic.

        float padding = 16f; // inner padding
        float contentW = boxW - padding*2;
        float y = padding;
        float lineSpacing = 4f;

        for (SlideDeck.StyledRun run : runs) {
            if (run.isNewline) {
                y += 22f; // paragraph spacing; continue
                continue;
            }
            if (run.text == null || run.text.isEmpty()) continue;
            String txt = run.text;
            // Split run text into words for wrapping if needed — use StaticLayout for accurate wrapping per run segment
            TextPaint paint = paintForRun(run, boxH);
            // Create layout for this run's text wrapped to contentW
            StaticLayout layout = StaticLayout.Builder.obtain(txt, 0, txt.length(), paint, (int)contentW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setEllipsize(null)
                    .build();
            // If run would overflow box, we still draw clipped
            canvas.save();
            canvas.translate(padding, y);
            layout.draw(canvas);
            canvas.restore();
            y += layout.getHeight() + lineSpacing;
            if (y > boxH - padding) break;
        }
        return bmp;
    }

    @NonNull
    private static TextPaint paintForRun(@NonNull SlideDeck.StyledRun run, int outH) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(run.color != 0 ? run.color : 0xFFFFFFFF);
        float baseSize = Math.max(12f, outH * 0.045f) * run.fontSizeScale;
        p.setTextSize(baseSize);
        int style = Typeface.NORMAL;
        if (run.bold && run.italic) style = Typeface.BOLD_ITALIC;
        else if (run.bold) style = Typeface.BOLD;
        else if (run.italic) style = Typeface.ITALIC;
        Typeface tf = null;
        if (run.fontFamily != null && !run.fontFamily.isEmpty()) {
            String fam = run.fontFamily.trim();
            if (fam.startsWith("file:")) {
                try {
                    String path = fam.substring(5);
                    java.io.File f = new java.io.File(path);
                    if (f.isFile()) tf = Typeface.createFromFile(f);
                    else {
                        // Try FontLibrary dir lookup by key
                        // If key is file:xxx, FontLibrary.imported() lists same; we already tried path
                    }
                } catch (Exception ignored) {}
            }
            if (tf == null) {
                try { tf = Typeface.create(fam, style); } catch (Exception ignored) {}
            }
        }
        if (tf == null) tf = Typeface.create(Typeface.DEFAULT, style);
        p.setTypeface(tf);
        return p;
    }

    /** Helper to determine if render needed: cue index changed or geometry changed. */
    public static boolean needsRebuild(int lastCue, int newCue, int lastW, int newW, int lastH, int newH) {
        return lastCue != newCue || lastW != newW || lastH != newH;
    }
}
