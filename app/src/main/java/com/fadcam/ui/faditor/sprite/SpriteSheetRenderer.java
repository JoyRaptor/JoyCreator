package com.fadcam.ui.faditor.sprite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.InputStream;

/**
 * S2 engine (PLAN_SPRITE_ANIMATION): decode-once bounded sheet bitmap + pure cell
 * geometry + one-time background color-key. Shared by the setup editor's preview,
 * the S4 overlay view, and the S6 export overlay — so the pixels every consumer
 * sees are identical by construction (bg-key applied ONCE at decode, per the
 * plan's correctness rules).
 *
 * <p>Cell geometry is computed in SOURCE-image pixel space from the sheet's
 * cols/rows/margins/spacing, then scaled by the decode sample factor — callers
 * never see the sampling.</p>
 */
public final class SpriteSheetRenderer {

    /** Longest decoded dimension — texture-safe and RAM-bounded (~4MB max ARGB). */
    private static final int MAX_DECODE_DIM = 2048;

    @NonNull private final SpriteSheet sheet;
    @NonNull private final Bitmap bitmap;
    /** source pixels per decoded pixel (>=1). */
    private final float sample;
    private final Paint drawPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    private SpriteSheetRenderer(@NonNull SpriteSheet sheet, @NonNull Bitmap bitmap, float sample) {
        this.sheet = sheet;
        this.bitmap = bitmap;
        this.sample = sample;
    }

    /**
     * Decode the sheet's source image, bounded to {@link #MAX_DECODE_DIM}, applying
     * the bg color-key once if the sheet defines one. Returns null when the source
     * can't be opened/decoded (caller shows the MISSING affordance — never crash,
     * per S7).
     */
    @Nullable
    public static SpriteSheetRenderer load(@NonNull Context ctx, @NonNull SpriteSheet sheet) {
        try {
            Uri uri = Uri.parse(sheet.getSheetUri());
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int inSample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / inSample > MAX_DECODE_DIM) {
                inSample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = inSample;
            opts.inMutable = true;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) return null;
            if (sheet.getBgKeyColor() != 0) {
                applyColorKey(bmp, sheet.getBgKeyColor(), sheet.getKeyTolerance());
            }
            // True sample factor from actual decode result (decodeStream may round).
            float sample = (float) bounds.outWidth / bmp.getWidth();
            return new SpriteSheetRenderer(sheet, bmp, Math.max(1f, sample));
        } catch (Exception e) {
            return null;
        }
    }

    /** One-time color→alpha key on the shared bitmap (preview==export pixels). */
    private static void applyColorKey(@NonNull Bitmap bmp, int keyColor, float tolerance) {
        int kr = Color.red(keyColor), kg = Color.green(keyColor), kb = Color.blue(keyColor);
        int tol = (int) (Math.max(0f, Math.min(1f, tolerance)) * 255f);
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            bmp.getPixels(row, 0, w, 0, y, w, 1);
            boolean dirty = false;
            for (int x = 0; x < w; x++) {
                int c = row[x];
                int dr = Math.abs(Color.red(c) - kr);
                int dg = Math.abs(Color.green(c) - kg);
                int db = Math.abs(Color.blue(c) - kb);
                if (Math.max(dr, Math.max(dg, db)) <= tol) {
                    row[x] = c & 0x00FFFFFF; // alpha -> 0, keep rgb (edge blending reads better)
                    dirty = true;
                }
            }
            if (dirty) bmp.setPixels(row, 0, w, 0, y, w, 1);
        }
    }

    // ── Pure geometry (also usable without a decoded bitmap) ─────────────

    /**
     * Cell rect in SOURCE-image pixels for {@code index} (row-major), given the
     * source image dimensions. Pure — the single geometry authority for editor
     * grid drawing, hit-testing, and blitting.
     */
    @NonNull
    public static Rect cellRectSource(@NonNull SpriteSheet s, int index, int srcW, int srcH) {
        int cols = Math.max(1, s.getCols());
        int rows = Math.max(1, s.getRows());
        int col = index % cols;
        int rowI = index / cols;
        float innerW = srcW - 2f * s.getMarginX() - (cols - 1) * (float) s.getSpacingX();
        float innerH = srcH - 2f * s.getMarginY() - (rows - 1) * (float) s.getSpacingY();
        float cw = Math.max(1f, innerW / cols);
        float ch = Math.max(1f, innerH / rows);
        float left = s.getMarginX() + col * (cw + s.getSpacingX());
        float top = s.getMarginY() + rowI * (ch + s.getSpacingY());
        return new Rect(Math.round(left), Math.round(top),
                Math.round(left + cw), Math.round(top + ch));
    }

    // ── Instance API ──────────────────────────────────────────────────────

    @NonNull public Bitmap getBitmap() { return bitmap; }

    /** Source-image width/height (pre-sampling), for geometry in source space. */
    public int sourceWidth() { return Math.round(bitmap.getWidth() * sample); }
    public int sourceHeight() { return Math.round(bitmap.getHeight() * sample); }

    /** Cell rect in DECODED-bitmap pixels (source rect scaled by the sample factor). */
    @NonNull
    public Rect cellRectBitmap(int index) {
        Rect src = cellRectSource(sheet, index, sourceWidth(), sourceHeight());
        return new Rect(Math.round(src.left / sample), Math.round(src.top / sample),
                Math.round(src.right / sample), Math.round(src.bottom / sample));
    }

    /** Width/height ratio of one cell. */
    public float cellAspect() {
        Rect r = cellRectBitmap(0);
        return r.height() <= 0 ? 1f : (float) r.width() / r.height();
    }

    /** Blit one cell into {@code dest}. No-op for out-of-range indices. */
    public void drawCell(@NonNull Canvas canvas, int cellIndex, @NonNull RectF dest,
                         @Nullable Paint overridePaint) {
        if (cellIndex < 0 || cellIndex >= sheet.cellCount()) return;
        canvas.drawBitmap(bitmap, cellRectBitmap(cellIndex), dest,
                overridePaint != null ? overridePaint : drawPaint);
    }

    public void recycle() {
        if (!bitmap.isRecycled()) bitmap.recycle();
    }
}
