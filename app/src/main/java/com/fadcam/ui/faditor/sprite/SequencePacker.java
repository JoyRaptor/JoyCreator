package com.fadcam.ui.faditor.sprite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

/**
 * §3d — pack an image SEQUENCE into a single grid sprite sheet.
 *
 * <p>The spec makes this an ACTION in the drawer rather than a fork at import, because at import
 * <i>"the user cannot yet know which they want"</i>. What it produces is an ordinary
 * {@link SpriteSheet}: one PNG in the project bundle, a row-major grid, and a preset carrying the
 * sequence's frame order AND its weights — so converting keeps the timing that was authored
 * instead of quietly resetting it.</p>
 *
 * <p>Runs off the UI thread (it decodes N images) and is bounded: the output is capped so a
 * 240-frame 4K sequence cannot try to allocate a bitmap no device will give it.</p>
 */
public final class SequencePacker {

    private SequencePacker() {}

    /**
     * Longest edge of the packed sheet. 4096 is the conservative floor for GL_MAX_TEXTURE_SIZE on
     * the hardware this app targets (the Note 9 tier), and the sheet is drawn as a texture.
     */
    private static final int MAX_SHEET_DIM = 4096;

    /**
     * Pack {@code seq} into {@code assetsDir}.
     *
     * @return the new grid sheet, or null when nothing could be decoded (caller reports; never
     *         throws, matching the S7 "MISSING, not crash" rule).
     */
    @Nullable
    public static SpriteSheet pack(@NonNull Context ctx, @NonNull SpriteSheet seq,
                                   @NonNull File assetsDir) {
        int n = seq.cellCount();
        if (n <= 0) return null;
        if (!assetsDir.exists() && !assetsDir.mkdirs()) return null;

        // Square-ish grid: fewest wasted cells, and it keeps both dimensions under the cap for
        // any n that fits at all.
        int cols = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil(n / (double) cols);

        // Cell size from the FIRST decodable frame's bounds. Frames in a rendered sequence share
        // dimensions; anything that does not is scaled to fit, which is the only answer a grid
        // can give since every cell in a grid is the same size by definition.
        int[] wh = firstFrameBounds(ctx, seq);
        if (wh == null) return null;
        int cellW = wh[0], cellH = wh[1];
        // Shrink cells until the whole sheet fits the texture cap.
        while (cols * cellW > MAX_SHEET_DIM || rows * cellH > MAX_SHEET_DIM) {
            cellW = Math.max(1, cellW / 2);
            cellH = Math.max(1, cellH / 2);
        }

        Bitmap sheetBmp = null;
        try {
            sheetBmp = Bitmap.createBitmap(cols * cellW, rows * cellH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(sheetBmp);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            RectF dst = new RectF();
            int decoded = 0;
            for (int i = 0; i < n; i++) {
                String uri = seq.frameUriAt(i);
                if (uri == null) continue;
                Bitmap frame = decodeBounded(ctx, uri, Math.max(cellW, cellH));
                // A frame that will not decode leaves its cell EMPTY rather than shifting the
                // rest up: index i must stay index i, or every downstream frame reference and
                // the carried weights would silently point at the wrong picture.
                if (frame == null) continue;
                int col = i % cols, row = i / cols;
                dst.set(col * cellW, row * cellH, (col + 1) * cellW, (row + 1) * cellH);
                canvas.drawBitmap(frame, null, fitInside(frame, dst), paint);
                frame.recycle();
                decoded++;
            }
            if (decoded == 0) return null;

            File dest = new File(assetsDir, "packed-" + UUID.randomUUID() + ".png");
            try (FileOutputStream out = new FileOutputStream(dest)) {
                sheetBmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }

            SpriteSheet packed = SpriteSheet.create(seq.getName() + " (sheet)",
                    Uri.fromFile(dest).toString());
            packed.setGrid(cols, rows);
            packed.setFps(seq.getFps());
            packed.setPivot(seq.getPivotX(), seq.getPivotY());

            // Carry the run AND its weights onto a preset with the sequence's own fixed id, so
            // the placed item's single preset key keeps resolving after the swap.
            SpriteSheet.Preset src = seq.sequencePreset();
            SpriteSheet.Preset out = new SpriteSheet.Preset(
                    SpriteSheet.SEQUENCE_PRESET_ID, "Sequence");
            out.type = src != null ? src.type : "once";
            for (int i = 0; i < n; i++) out.frames.add(i);
            out.weights.addAll(SequenceTiming.fit(src == null ? null : src.weights, n));
            packed.getPresets().add(out);

            // Cells beyond the frame count exist in the grid but hold nothing; disabling them
            // keeps the resolver from ever showing an empty corner as if it were art.
            for (int i = n; i < cols * rows; i++) {
                SpriteSheet.Cell c = new SpriteSheet.Cell(i, "");
                c.enabled = false;
                packed.getCells().add(c);
            }
            return packed;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (sheetBmp != null && !sheetBmp.isRecycled()) sheetBmp.recycle();
        }
    }

    /** Centre {@code src} inside {@code cell}, preserving aspect (letterbox, never stretch). */
    @NonNull
    private static RectF fitInside(@NonNull Bitmap src, @NonNull RectF cell) {
        float sa = src.getWidth() / (float) src.getHeight();
        float ca = cell.width() / cell.height();
        float w = sa > ca ? cell.width() : cell.height() * sa;
        float h = sa > ca ? cell.width() / sa : cell.height();
        float cx = cell.centerX(), cy = cell.centerY();
        return new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
    }

    @Nullable
    private static int[] firstFrameBounds(@NonNull Context ctx, @NonNull SpriteSheet seq) {
        for (int i = 0; i < seq.cellCount(); i++) {
            String uri = seq.frameUriAt(i);
            if (uri == null) continue;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(uri))) {
                    BitmapFactory.decodeStream(in, null, o);
                }
                if (o.outWidth > 0 && o.outHeight > 0) return new int[]{o.outWidth, o.outHeight};
            } catch (Exception ignored) { }
        }
        return null;
    }

    @Nullable
    private static Bitmap decodeBounded(@NonNull Context ctx, @NonNull String uri, int maxDim) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(uri))) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxDim * 2) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(uri))) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** Unused-but-kept helper for callers that want the source rect of a packed cell. */
    @NonNull
    public static Rect cellRect(@NonNull SpriteSheet packed, int index, int srcW, int srcH) {
        return SpriteSheetRenderer.cellRectSource(packed, index, srcW, srcH);
    }
}
