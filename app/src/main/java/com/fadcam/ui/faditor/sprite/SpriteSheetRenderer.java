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

    /**
     * Non-null exactly when the sheet is a file-backed SEQUENCE
     * ({@link SpriteSheet#KIND_SEQUENCE}). In that mode {@link #bitmap} is frame 0 — kept so
     * aspect and "is there anything here at all" work identically — and every other frame comes
     * from this bounded cache.
     *
     * <p>Branching INSIDE this class rather than adding a second renderer type is the point: the
     * preview view, the export overlay, the timeline tape, the palette thumbs and the avatar
     * puppet all call {@link #drawCell}/{@link #cellAspect}, and none of them has to learn that
     * sequences exist.</p>
     */
    @Nullable private final SequenceFrameCache frames;

    private SpriteSheetRenderer(@NonNull SpriteSheet sheet, @NonNull Bitmap bitmap, float sample,
                                @Nullable SequenceFrameCache frames) {
        this.sheet = sheet;
        this.bitmap = bitmap;
        this.sample = sample;
        this.frames = frames;
    }

    /**
     * Decode the sheet's source image, bounded to {@link #MAX_DECODE_DIM}, applying
     * the bg color-key once if the sheet defines one. Returns null when the source
     * can't be opened/decoded (caller shows the MISSING affordance — never crash,
     * per S7).
     */
    @Nullable
    public static SpriteSheetRenderer load(@NonNull Context ctx, @NonNull SpriteSheet sheet) {
        return load(ctx, sheet, SequenceFrameCache.DEFAULT_MAX_DIM);
    }

    /**
     * As {@link #load(Context, SpriteSheet)}, but with an explicit longest-edge cap for
     * SEQUENCE frames.
     *
     * <p>Exists so the export can bound its decodes against the OUTPUT frame size rather than a
     * screen-sized default — §8's memory rule, and the reason a 500-frame 4K sequence does not
     * have to be a 500-frame 4K decode. Ignored for grid sheets, which already bound themselves
     * at {@link #MAX_DECODE_DIM}.</p>
     */
    @Nullable
    public static SpriteSheetRenderer load(@NonNull Context ctx, @NonNull SpriteSheet sheet,
                                           int sequenceMaxDim) {
        if (sheet.isSequence()) return loadSequence(ctx, sheet, sequenceMaxDim);
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
            return new SpriteSheetRenderer(sheet, bmp, Math.max(1f, sample), null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Sequence backing: decode frame 0 eagerly (it answers {@link #cellAspect} and proves the
     * material is readable at all) and leave the rest to the LRU.
     *
     * <p>Returns null only when the sequence has NO openable first frame — the same "caller shows
     * MISSING, never crash" contract the grid path has. A sequence whose frame 47 is missing
     * still loads; that one frame draws as the placeholder.</p>
     */
    @Nullable
    private static SpriteSheetRenderer loadSequence(@NonNull Context ctx, @NonNull SpriteSheet sheet,
                                                    int maxDim) {
        SequenceFrameCache cache = new SequenceFrameCache(ctx, sheet, maxDim);
        Bitmap first = null;
        // Not strictly frame 0: an author whose first file went missing should still see the rest
        // of the sequence rather than an entirely dead object.
        for (int i = 0; i < sheet.cellCount() && first == null; i++) first = cache.frame(i);
        if (first == null) { cache.recycle(); return null; }
        return new SpriteSheetRenderer(sheet, first, 1f, cache);
    }

    /**
     * One-time color→alpha key on a decoded bitmap (preview==export pixels).
     * Package-private rather than private so {@link SequenceFrameCache} applies the identical
     * key to sequence frames — two implementations of this would be two sets of pixels.
     */
    static void applyColorKey(@NonNull Bitmap bmp, int keyColor, float tolerance) {
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

    /**
     * Cell rect in DECODED-bitmap pixels (source rect scaled by the sample factor).
     *
     * <p>For a SEQUENCE the "cell" is the whole of that frame's own file, so this is that
     * bitmap's full bounds. Grid arithmetic is not merely skipped — it is meaningless, since a
     * sequence's frames need not even share dimensions.</p>
     */
    @NonNull
    public Rect cellRectBitmap(int index) {
        if (frames != null) {
            Bitmap b = frames.frame(index);
            if (b == null) b = bitmap;
            return new Rect(0, 0, b.getWidth(), b.getHeight());
        }
        Rect src = cellRectSource(sheet, index, sourceWidth(), sourceHeight());
        return new Rect(Math.round(src.left / sample), Math.round(src.top / sample),
                Math.round(src.right / sample), Math.round(src.bottom / sample));
    }

    /** Width/height ratio of one cell (frame 0 for a sequence). */
    public float cellAspect() {
        if (frames != null) {
            return bitmap.getHeight() <= 0 ? 1f
                    : (float) bitmap.getWidth() / bitmap.getHeight();
        }
        Rect r = cellRectBitmap(0);
        return r.height() <= 0 ? 1f : (float) r.width() / r.height();
    }

    /** True when this renderer is backed by N files rather than one sliced image. */
    public boolean isSequence() { return frames != null; }

    /**
     * The decoded bitmap for a SEQUENCE frame, or null (out of range, unreadable, or a grid
     * sheet). Exists for the one consumer that needs the pixels rather than a blit —
     * {@code PuppetPreviewView}, which crops parts out of a sheet and would otherwise pair
     * {@link #cellRectBitmap} with {@link #getBitmap()} and get frame 0 every time.
     *
     * <p><b>The returned bitmap belongs to the cache and may be evicted and recycled.</b> Copy it
     * if you intend to keep it.</p>
     */
    @Nullable
    public Bitmap frameBitmap(int index) {
        return frames == null ? null : frames.frame(index);
    }

    /**
     * True when cell {@code index} has a URI but no readable file — the S7 MISSING affordance's
     * input. Always false for a grid sheet, whose missing-ness is all-or-nothing at load time.
     */
    public boolean isCellMissing(int index) {
        return frames != null && frames.isMissing(index);
    }

    /** Retry frames that previously failed to decode (after a relink). */
    public void clearMissingCache() {
        if (frames != null) frames.clearFailures();
    }

    /** Blit one cell into {@code dest}. No-op for out-of-range indices. */
    public void drawCell(@NonNull Canvas canvas, int cellIndex, @NonNull RectF dest,
                         @Nullable Paint overridePaint) {
        if (cellIndex < 0 || cellIndex >= sheet.cellCount()) return;
        Paint p = overridePaint != null ? overridePaint : drawPaint;
        if (frames != null) {
            Bitmap b = frames.frame(cellIndex);
            // A frame that will not decode never draws the WRONG picture — falling back to
            // frame 0 would make a missing file look like an authored hold, the one reading a
            // user cannot tell apart from correct output. But drawing nothing at all is barely
            // better: it reads as a gap in the animation rather than as a broken file. So it
            // draws a MISSING placeholder (§7 / SPEC_IMAGE_SEQUENCE §8), which is unmistakably
            // neither.
            if (b == null || b.isRecycled()) { drawMissingCell(canvas, dest); return; }
            canvas.drawBitmap(b, null, dest, p);
            return;
        }
        canvas.drawBitmap(bitmap, cellRectBitmap(cellIndex), dest, p);
    }

    /** Paints for the MISSING placeholder — lazily built, since most sheets never need them. */
    @Nullable private Paint missingFill, missingStroke;

    /**
     * The MISSING affordance: a dashed amber outline with a diagonal slash, sized to the cell.
     *
     * <p>Deliberately not text — this is drawn at timeline-tape sizes as small as a few dp, and
     * a label would be unreadable exactly where it is most needed. What it has to communicate is
     * "this frame is broken, and it is broken HERE", which a shape does at any size.</p>
     */
    private void drawMissingCell(@NonNull Canvas canvas, @NonNull RectF dest) {
        if (missingFill == null) {
            missingFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            missingFill.setColor(0x33FF7043);
            missingStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            missingStroke.setStyle(Paint.Style.STROKE);
            missingStroke.setColor(0xFFFF7043);
            // A Path is required for a dash to render at all on a hardware canvas — a dashed
            // rect primitive is silently ignored (the 2026-08-05 "dashed outlines never dashed"
            // finding). Strokes below go through drawLine/drawPath for the same reason.
            missingStroke.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{6f, 5f}, 0f));
        }
        float inset = Math.min(dest.width(), dest.height()) * 0.06f;
        RectF r = new RectF(dest.left + inset, dest.top + inset,
                dest.right - inset, dest.bottom - inset);
        missingStroke.setStrokeWidth(Math.max(1f, Math.min(r.width(), r.height()) * 0.05f));
        canvas.drawRect(r, missingFill);
        android.graphics.Path box = new android.graphics.Path();
        box.addRect(r, android.graphics.Path.Direction.CW);
        canvas.drawPath(box, missingStroke);
        android.graphics.Path slash = new android.graphics.Path();
        slash.moveTo(r.left, r.bottom);
        slash.lineTo(r.right, r.top);
        canvas.drawPath(slash, missingStroke);
    }

    public void recycle() {
        if (frames != null) {
            frames.recycle();
            // bitmap IS one of the cache's entries in sequence mode, so it is already recycled;
            // touching it again would be a use-after-recycle on some OEM implementations.
            return;
        }
        if (!bitmap.isRecycled()) bitmap.recycle();
    }
}
