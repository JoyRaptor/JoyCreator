package com.fadcam.ui.faditor.sprite;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * S2b: algorithmic grid auto-detect (the plan's "gutter scan" — AI vision joins
 * in fast-follow B). Finds fully-empty pixel columns/rows (transparent, or
 * matching the sheet's background color when the art has no alpha), clusters
 * them into gutters, and derives cols/rows/margins/spacing as the best uniform
 * fit. Returns null when no confident grid exists (caller keeps the current
 * grid and says so — never silently mangles a hand-tuned slice).
 *
 * <p>The run-clustering core is pure integer math on per-column/per-row empty
 * counts ({@link #fromStats}) so it is JVM-testable; the {@link #detect}
 * wrapper streams the bitmap row-by-row (no full-frame pixel copy).</p>
 */
public final class SpriteGridDetector {

    private SpriteGridDetector() {}

    /** Detected geometry in the SAME pixel space as the scanned image. */
    public static class Result {
        public int cols, rows;
        public int marginX, marginY;
        public int spacingX, spacingY;
    }

    /** A column/row is a gutter when at least this fraction of it is empty. */
    private static final float GUTTER_FRACTION = 0.98f;
    /** Alpha at/below this counts as empty. */
    private static final int ALPHA_EMPTY = 24;
    /** Per-channel closeness to the corner/background color that counts as empty. */
    private static final int BG_TOLERANCE = 12;
    private static final int MAX_GRID = 32; // sanity ceiling

    /**
     * Scan a decoded sheet bitmap. {@code bgKeyColor} (0 = none) is honored as
     * the empty color when the art is opaque; otherwise the dominant corner
     * color is used, and transparency always counts as empty.
     */
    @Nullable
    public static Result detect(@NonNull Bitmap bmp, int bgKeyColor) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w < 4 || h < 4) return null;
        int bg = bgKeyColor != 0 ? bgKeyColor : cornerColor(bmp);
        int[] colEmpty = new int[w];
        int[] rowEmpty = new int[h];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            bmp.getPixels(row, 0, w, 0, y, w, 1);
            int re = 0;
            for (int x = 0; x < w; x++) {
                if (isEmpty(row[x], bg)) {
                    colEmpty[x]++;
                    re++;
                }
            }
            rowEmpty[y] = re;
        }
        return fromStats(colEmpty, rowEmpty, w, h);
    }

    /** Pure core: cluster gutter runs from per-column/per-row empty counts. */
    @Nullable
    public static Result fromStats(@NonNull int[] colEmpty, @NonNull int[] rowEmpty,
                                   int w, int h) {
        int[] xRuns = contentRuns(colEmpty, h);   // pairs: start,end (exclusive)
        int[] yRuns = contentRuns(rowEmpty, w);
        int cols = xRuns.length / 2, rows = yRuns.length / 2;
        if (cols < 1 || rows < 1) return null;
        if (cols == 1 && rows == 1) return null;  // nothing sliceable found
        if (cols > MAX_GRID || rows > MAX_GRID) return null; // noise, not a grid
        if (!roughlyUniform(xRuns) || !roughlyUniform(yRuns)) return null;

        Result r = new Result();
        r.cols = cols;
        r.rows = rows;
        // Model stores symmetric margins (innerW = w - 2*marginX - (cols-1)*spacingX),
        // so best-fit lead/trail gutters into one margin value.
        r.marginX = Math.round((xRuns[0] + (w - xRuns[xRuns.length - 1])) / 2f);
        r.marginY = Math.round((yRuns[0] + (h - yRuns[yRuns.length - 1])) / 2f);
        r.spacingX = avgInteriorGutter(xRuns);
        r.spacingY = avgInteriorGutter(yRuns);
        return r;
    }

    /** Alternating gutter/content walk → flat [start,end) pairs of CONTENT runs. */
    @NonNull
    private static int[] contentRuns(@NonNull int[] emptyCount, int lineLength) {
        int threshold = Math.round(lineLength * GUTTER_FRACTION);
        int n = emptyCount.length;
        int[] tmp = new int[n + 2];
        int count = 0;
        boolean inContent = false;
        for (int i = 0; i < n; i++) {
            boolean content = emptyCount[i] < threshold;
            if (content && !inContent) { tmp[count * 2] = i; inContent = true; }
            if (!content && inContent) { tmp[count * 2 + 1] = i; count++; inContent = false; }
        }
        if (inContent) { tmp[count * 2 + 1] = n; count++; }
        int[] out = new int[count * 2];
        System.arraycopy(tmp, 0, out, 0, count * 2);
        return out;
    }

    /** Content runs must be near-uniform for a grid read (max/min < 1.6). */
    private static boolean roughlyUniform(@NonNull int[] runs) {
        int min = Integer.MAX_VALUE, max = 0;
        for (int i = 0; i < runs.length; i += 2) {
            int len = runs[i + 1] - runs[i];
            min = Math.min(min, len);
            max = Math.max(max, len);
        }
        return min > 0 && max / (float) min < 1.6f;
    }

    /** Average width of the gutters BETWEEN content runs (0 when adjacent). */
    private static int avgInteriorGutter(@NonNull int[] runs) {
        int n = runs.length / 2;
        if (n <= 1) return 0;
        int sum = 0;
        for (int i = 1; i < n; i++) sum += runs[i * 2] - runs[(i - 1) * 2 + 1];
        return Math.round(sum / (float) (n - 1));
    }

    private static boolean isEmpty(int argb, int bg) {
        if (Color.alpha(argb) <= ALPHA_EMPTY) return true;
        if (Color.alpha(bg) <= ALPHA_EMPTY) return false; // bg is transparency; pixel isn't
        return Math.abs(Color.red(argb) - Color.red(bg)) <= BG_TOLERANCE
                && Math.abs(Color.green(argb) - Color.green(bg)) <= BG_TOLERANCE
                && Math.abs(Color.blue(argb) - Color.blue(bg)) <= BG_TOLERANCE;
    }

    /** Most common of the four corner pixels = background candidate. */
    private static int cornerColor(@NonNull Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] corners = {bmp.getPixel(0, 0), bmp.getPixel(w - 1, 0),
                bmp.getPixel(0, h - 1), bmp.getPixel(w - 1, h - 1)};
        int bestIdx = 0, bestVotes = 0;
        for (int i = 0; i < 4; i++) {
            int votes = 0;
            for (int j = 0; j < 4; j++) {
                if (corners[j] == corners[i]) votes++;
            }
            if (votes > bestVotes) { bestVotes = votes; bestIdx = i; }
        }
        return corners[bestIdx];
    }
}
