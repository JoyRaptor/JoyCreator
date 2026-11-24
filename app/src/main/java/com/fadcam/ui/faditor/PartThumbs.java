package com.fadcam.ui.faditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.sprite.SpriteSheetRenderer;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PREVIEW-1 (owner rule, 11-20): a tiny preview to the left of a part's name wherever parts are listed or picked.
 *
 * <p>One place that makes the small picture, so every list looks the same and none decodes on the main thread. A
 * picture is drawn once, cached by key, and put on the row when it is ready; a recycled row that has moved on to
 * another part is left alone.</p>
 */
public final class PartThumbs {
    /** The side of the small square, in dp. */
    public static final int DP = 36;
    private static final int PX_CAP = 160;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(48) { };

    private PartThumbs() {}

    /** Put the picture left of the text in {@code row}, once it is ready. {@code key} names the part for the cache. */
    public static void startOf(@NonNull TextView row, @NonNull String key, @NonNull Callable<Bitmap> loader) {
        int px = (int) (DP * row.getResources().getDisplayMetrics().density);
        row.setTag(com.fadcam.R.id.part_thumb_key, key);
        row.setCompoundDrawablePadding((int) (10 * row.getResources().getDisplayMetrics().density));
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            set(row, hit, px);
            return;
        }
        set(row, null, px);                 // a blank square keeps the rows from jumping when the pictures arrive
        POOL.execute(() -> {
            Bitmap b = null;
            try {
                b = loader.call();
            } catch (Throwable ignored) {
                // a part that cannot be drawn keeps its blank square; never break the list
            }
            Bitmap got = b;
            if (got == null) return;
            CACHE.put(key, got);
            MAIN.post(() -> {
                if (key.equals(row.getTag(com.fadcam.R.id.part_thumb_key))) set(row, got, px);
            });
        });
    }

    /** The same for a sheet row: the glyph gives way to the picture. */
    public static void onRow(@NonNull SheetKit.Row row, @NonNull String key, @NonNull Callable<Bitmap> loader) {
        if (row.icon != null) row.icon.setVisibility(android.view.View.GONE);
        startOf(row.label, key, loader);
    }

    private static void set(@NonNull TextView row, @Nullable Bitmap b, int px) {
        Drawable d = b == null ? new BitmapDrawable(row.getResources(), blank(px)) : new BitmapDrawable(row.getResources(), b);
        d.setBounds(0, 0, px, px);
        row.setCompoundDrawablesRelative(d, null, null, null);
    }

    /** The picture for a sprite sheet: its first cell (or the first frame of a sequence), fitted into a square. */
    @Nullable
    public static Bitmap sheetCell(@NonNull Context c, @NonNull SpriteSheet sheet, int cell) {
        SpriteSheetRenderer r = SpriteSheetRenderer.load(c, sheet);
        if (r == null) return null;
        Bitmap src = r.isSequence() ? r.frameBitmap(cell) : r.getBitmap();
        if (src == null || src.isRecycled()) return null;
        Rect rc = r.cellRectBitmap(cell);
        rc.intersect(0, 0, src.getWidth(), src.getHeight());
        return square(src, rc);
    }

    /** {@code region} of {@code src}, scaled to fit inside a square with its proportions kept. */
    @Nullable
    public static Bitmap square(@NonNull Bitmap src, @NonNull Rect region) {
        if (region.width() <= 0 || region.height() <= 0) return null;
        int side = Math.min(PX_CAP, Math.max(region.width(), region.height()));
        Bitmap out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        float s = Math.min((float) side / region.width(), (float) side / region.height());
        float w = region.width() * s, h = region.height() * s;
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        new Canvas(out).drawBitmap(src, region, new RectF((side - w) / 2f, (side - h) / 2f, (side + w) / 2f, (side + h) / 2f), p);
        return out;
    }

    private static Bitmap blank(int px) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, px), Math.max(1, px), Bitmap.Config.ARGB_8888);
        new Canvas(b).drawColor(0x14FFFFFF);   // a faint square, the grey that rows already use
        return b;
    }
}
