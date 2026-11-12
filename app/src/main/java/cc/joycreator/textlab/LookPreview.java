package cc.joycreator.textlab;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import androidx.annotation.NonNull;

import com.fadcam.joyfolder.SheetName;

import org.json.JSONObject;

/**
 * A look's moving preview: a sprite sheet of 20 frames (5 across, 4 down), each 256 x 144, two seconds of the
 * look from 3 s in at 10 frames a second, drawn by the same renderer as everything else. Saved as
 * {@code <Name>.5x4.sprite.png} beside {@code <Name>.text.json} (the grid is in the name: {@link SheetName}).
 *
 * <p>Each frame fills its cell: the look is drawn a little larger than the cell and cropped to it, so a wide
 * word and a short one both make a full picture. The clock is stepped in sixtieths of a second, so fire runs
 * exactly as it does live. GL thread only.</p>
 */
public final class LookPreview {

    public static final int COLS = 5, ROWS = 4, CELL_W = 256, CELL_H = 144;
    /** The moment the preview starts: the letters have arrived and the look is in its stride. */
    public static final double START = 3.0;

    private LookPreview() {}

    /** The file name of a look's sheet: "Dust" → "Dust.5x4.sprite.png". */
    @NonNull
    public static String sheetName(@NonNull String base) {
        return SheetName.of(base, COLS, ROWS);
    }

    @NonNull
    public static Bitmap make(@NonNull TextLabRenderer r, @NonNull JSONObject look, @NonNull String words) {
        r.setLook(look);
        r.setText(words);
        r.setSize(CELL_W * 3 / 2, CELL_H * 3 / 2);
        r.still(START);
        Bitmap sheet = Bitmap.createBitmap(COLS * CELL_W, ROWS * CELL_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sheet);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        int ticksPerFrame = 60 / SheetName.FPS, tick = 0;
        for (int i = 0; i < COLS * ROWS; i++) {
            if (i > 0) {
                for (int k = 0; k < ticksPerFrame; k++) {
                    tick++;
                    r.frame(1000 + tick / 60.0);
                }
            }
            Bitmap f = r.readBitmap();
            int x = (i % COLS) * CELL_W, y = (i / COLS) * CELL_H;
            c.save();
            c.clipRect(cell(i));
            c.drawBitmap(f, null, cover(f.getWidth(), f.getHeight(), x, y), p);
            c.restore();
            f.recycle();
        }
        return sheet;
    }

    /** Where a w x h picture goes so it covers the cell at (x, y), centred, cropped by the cell. */
    @NonNull
    private static RectF cover(int w, int h, int x, int y) {
        float k = Math.max((float) CELL_W / w, (float) CELL_H / h);
        float dw = w * k, dh = h * k;
        float l = x + (CELL_W - dw) / 2, t = y + (CELL_H - dh) / 2;
        return new RectF(l, t, l + dw, t + dh);
    }

    /** Clip a canvas to one cell (drawing a covering frame must not spill into the next). */
    static Rect cell(int i) {
        int x = (i % COLS) * CELL_W, y = (i / COLS) * CELL_H;
        return new Rect(x, y, x + CELL_W, y + CELL_H);
    }
}
