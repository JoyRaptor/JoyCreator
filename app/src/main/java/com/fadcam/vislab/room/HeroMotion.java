package com.fadcam.vislab.room;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.joyfolder.SheetName;
import com.fadcam.vislab.view.VisTextureView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * The lobby hero's moving preview: while the room is on screen, a ring of the last 20 small frames
 * (about 8 a second, 256x144 each); when the room closes they become one 5x4 sprite sheet beside
 * the still ({@code hero-123.5x4.sprite.webp}), which the lobby plays with the Library's sheet
 * drawable. Cheap on purpose: a 256-pixel read-back eight times a second, only while the room runs.
 */
final class HeroMotion {

    static final int COLS = 5, ROWS = 4, FRAMES = COLS * ROWS, CW = 256, CH = 144;
    private static final long EVERY_MS = 125;
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Bitmap[] ring = new Bitmap[FRAMES];
    private int next, filled;
    private boolean running;
    @Nullable private VisTextureView view;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running || view == null) return;
            grab(view);
            main.postDelayed(this, EVERY_MS);
        }
    };

    void start(@NonNull VisTextureView v) {
        view = v;
        if (running) return;
        running = true;
        main.postDelayed(tick, EVERY_MS);
    }

    void stop() {
        running = false;
        main.removeCallbacks(tick);
    }

    private void grab(@NonNull VisTextureView v) {
        int vw = v.getWidth(), vh = v.getHeight();
        if (!v.isAvailable() || vw <= 0 || vh <= 0) return;
        try {
            // Read back at 256 wide, then centre-crop to 16:9 into the ring slot.
            Bitmap src = v.getBitmap(CW, Math.max(1, Math.round(CW * (float) vh / vw)));
            if (src == null) return;
            Bitmap cell = ring[next];
            if (cell == null) cell = Bitmap.createBitmap(CW, CH, Bitmap.Config.ARGB_8888);
            int sw = src.getWidth(), sh = src.getHeight();
            Rect from;
            if (sw * CH > sh * CW) {   // wider than 16:9: trim the sides
                int w = Math.round(sh * (float) CW / CH);
                from = new Rect((sw - w) / 2, 0, (sw + w) / 2, sh);
            } else {                   // taller: trim top and bottom
                int h = Math.round(sw * (float) CH / CW);
                from = new Rect(0, (sh - h) / 2, sw, (sh + h) / 2);
            }
            new Canvas(cell).drawBitmap(src, from, new Rect(0, 0, CW, CH), null);
            src.recycle();
            ring[next] = cell;
            next = (next + 1) % FRAMES;
            if (filled < FRAMES) filled++;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            // a missed frame is only a missed frame
        }
    }

    /** Main thread: the frames in time order as one sheet, or null before 20 have been seen. */
    @Nullable
    Bitmap sheet() {
        if (filled < FRAMES) return null;
        try {
            Bitmap sheet = Bitmap.createBitmap(COLS * CW, ROWS * CH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(sheet);
            for (int i = 0; i < FRAMES; i++) {
                Bitmap f = ring[(next + i) % FRAMES];   // oldest first
                if (f != null) c.drawBitmap(f, (i % COLS) * CW, (i / COLS) * CH, null);
            }
            return sheet;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** The sheet's file for a still: same folder and base name, the Library's grid name. */
    @NonNull
    static File sheetFor(@NonNull File still) {
        String n = still.getName();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        return new File(still.getParentFile(), SheetName.of(base, COLS, ROWS, SheetName.WEBP));
    }

    /** Background thread: write the sheet (WebP, under 8 MB), then recycle it. */
    static void write(@NonNull Bitmap sheet, @NonNull File out) {
        try (OutputStream o = new FileOutputStream(out)) {
            @SuppressWarnings("deprecation")
            Bitmap.CompressFormat f = android.os.Build.VERSION.SDK_INT >= 30
                    ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            sheet.compress(f, 80, bo);
            if (bo.size() > MAX_BYTES) return;   // never hand the lobby something that heavy
            bo.writeTo(o);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        } finally {
            sheet.recycle();
        }
    }
}
