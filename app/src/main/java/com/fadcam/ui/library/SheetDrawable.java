package com.fadcam.ui.library;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.joyfolder.SheetName;

/**
 * A sprite sheet ({@code <Name>.<C>x<R>.sprite.png}) as a moving picture: one frame at a time, left to right,
 * top to bottom, looping. Its size is one frame's, so an ImageView crops or fits it like any picture.
 *
 * <p>It plays only while it is shown: an ImageView tells a drawable when it goes off screen
 * ({@link #setVisible}), and a card scrolled away or a chip not chosen costs nothing. {@link #setPlaying}
 * holds it on its first frame (a chip that is not chosen).</p>
 */
public final class SheetDrawable extends Drawable implements Animatable {

    private final Bitmap sheet;
    private final int cols, rows, frames, fw, fh, fps;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();
    private int frame;
    private boolean running, playing = true;

    private final Runnable next = new Runnable() {
        @Override
        public void run() {
            frame = (frame + 1) % frames;
            invalidateSelf();
            if (running) scheduleSelf(this, SystemClock.uptimeMillis() + 1000L / fps);
        }
    };

    public SheetDrawable(@NonNull Bitmap sheet, int cols, int rows, int fps) {
        this.sheet = sheet;
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        this.frames = this.cols * this.rows;
        this.fw = sheet.getWidth() / this.cols;
        this.fh = sheet.getHeight() / this.rows;
        this.fps = Math.max(1, fps);
    }

    /** A sheet whose grid is in its file name, or null when the name has none. */
    @Nullable
    public static SheetDrawable of(@NonNull Bitmap sheet, @Nullable String fileName) {
        SheetName n = SheetName.parse(fileName);
        return n == null ? null : new SheetDrawable(sheet, n.cols, n.rows, SheetName.FPS);
    }

    /** false: hold the first frame (a chip not chosen). */
    public void setPlaying(boolean p) {
        if (p == playing) return;
        playing = p;
        if (!p) {
            stop();
            frame = 0;
            invalidateSelf();
        } else if (isVisible()) {
            start();
        }
    }

    @Override
    public void draw(@NonNull Canvas c) {
        int x = (frame % cols) * fw, y = (frame / cols) * fh;
        src.set(x, y, x + fw, y + fh);
        c.drawBitmap(sheet, src, getBounds(), paint);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (visible && playing) start();
        else stop();
        return changed;
    }

    @Override
    public void start() {
        if (running || !playing || frames < 2) return;
        running = true;
        scheduleSelf(next, SystemClock.uptimeMillis() + 1000L / fps);
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;
        unscheduleSelf(next);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getIntrinsicWidth() {
        return fw;
    }

    @Override
    public int getIntrinsicHeight() {
        return fh;
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter cf) {
        paint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
