package com.fadcam.ui.lobby;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * JOYBOT, ANIMATED — one spritesheet, played back a cell at a time.
 *
 * <p>JoyRaptor, 2026-09-18: <i>"his icon is white, but he will when large or in chat be an
 * animated claymation style avatar. I have two real spritesheets, im just trying to organize
 * them for you so that proper animation is easy later ... the idea is that he greets you as a
 * claymation and then he flies up into the corner and flattens into the white icon."</i>
 *
 * <p>This is the playback half of that, built so the art lands INTO something rather than
 * onto something. {@link JoybotView} owns the two white line-art stills and the transitions
 * between them; this owns a sheet and the question "which cell is showing".
 *
 * <h3>Why the sheet is never sliced into separate Bitmaps</h3>
 * The obvious implementation cuts a sheet into N bitmaps up front. For a claymation loop of,
 * say, 24 cells at 360×540 that is 18MB of ARGB_8888 held for the life of the screen, and it
 * duplicates memory the sheet already contains. Drawing a source RECT out of the one sheet
 * costs nothing extra per frame and holds exactly one bitmap.
 *
 * <h3>Frame rate is data, not a constant</h3>
 * Claymation is shot on twos or threes and that cadence is part of the performance — a sheet
 * authored at 12fps played back at 24 stops looking like stop-motion and starts looking like
 * a cartoon. So the rate travels with the sheet and the caller states it.
 */
public final class JoybotFilm {

    private final Bitmap sheet;
    private final int cols, rows, frames;
    private final int cellW, cellH;
    private final long framePeriodMs;
    private final boolean loop;

    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    private long startedAtMs = 0L;
    private boolean finished = false;

    /**
     * @param sheet  the spritesheet; cells run left to right, top to bottom
     * @param cols   cells across
     * @param rows   cells down
     * @param frames how many cells are actually USED — the last row is usually partly empty,
     *               and playing the blanks is the classic spritesheet bug
     * @param fps    the rate the sheet was authored at
     * @param loop   true for an idle cycle, false for a one-shot such as the greeting
     */
    public JoybotFilm(@NonNull Bitmap sheet, int cols, int rows, int frames, float fps,
                      boolean loop) {
        this.sheet = sheet;
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        this.frames = Math.max(1, Math.min(frames, this.cols * this.rows));
        this.cellW = sheet.getWidth() / this.cols;
        this.cellH = sheet.getHeight() / this.rows;
        this.framePeriodMs = (long) (1000f / Math.max(1f, fps));
        this.loop = loop;
    }

    public void start(long nowMs) {
        startedAtMs = nowMs;
        finished = false;
    }

    /** True once a non-looping film has played its last cell. */
    public boolean isFinished() { return finished; }

    public int cellWidth()  { return cellW; }
    public int cellHeight() { return cellH; }

    /**
     * Draw the cell for {@code nowMs} into {@code into}.
     *
     * <p>The frame is derived from ELAPSED TIME rather than incremented per draw. A counter
     * would make playback speed depend on how often the view happens to be invalidated, so
     * the same film would run at one speed on an idle screen and another while the timeline
     * behind it is being scrubbed.
     */
    public void draw(@NonNull Canvas canvas, @NonNull RectF into, @Nullable Paint paint,
                     long nowMs) {
        if (sheet.isRecycled()) return;
        long elapsed = Math.max(0L, nowMs - startedAtMs);
        int i = (int) (elapsed / framePeriodMs);
        if (i >= frames) {
            if (loop) {
                i %= frames;
            } else {
                i = frames - 1;
                finished = true;
            }
        }
        int cx = i % cols, cy = i / cols;
        src.set(cx * cellW, cy * cellH, (cx + 1) * cellW, (cy + 1) * cellH);
        dst.set(into);
        canvas.drawBitmap(sheet, src, dst, paint);
    }
}
