package com.fadcam.ui.faditor.sprite;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The SpriteLab icon set, drawn from the SAME path data the web design uses.
 *
 * <p>GENERATED from the {@code &lt;symbol&gt;} block of
 * {@code tools/spritelab/SpriteLabMobile.html} by {@code tools/spritelab/genicons.py}.
 * Edit the HTML and re-run; do not hand-edit the path strings here, or the phone and the
 * web design will drift apart, which is the one thing this file exists to prevent.</p>
 *
 * <p>Everything is stroked in a 24x24 box at stroke-width 1.7 with round caps and joins,
 * matching {@code svg.i} in the stylesheet, so an icon scales the way the SVG does.</p>
 */
public final class SpriteIcons {

    private SpriteIcons() {}

    /** viewBox side. Every path below is authored in this space. */
    private static final float BOX = 24f;
    private static final float STROKE = 1.7f;

    private static final Map<String, String[]> STROKED = new HashMap<>();
    private static final Map<String, String[]> FILLED = new HashMap<>();
    private static final Map<String, Path[]> CACHE_S = new HashMap<>();
    private static final Map<String, Path[]> CACHE_F = new HashMap<>();

    static {
        STROKED.put("grid", new String[]{
                "M5.5 3H18.5A2.5 2.5 0 0 1 21 5.5V18.5A2.5 2.5 0 0 1 18.5 21H5.5A2.5 2.5 0 0 1 3 18.5V5.5A2.5 2.5 0 0 1 5.5 3Z",
                "M9 3v18M15 3v18M3 9h18M3 15h18",
        });
        STROKED.put("layers", new String[]{
                "M12 3 3 8l9 5 9-5-9-5Z",
                "m3 14 9 5 9-5",
        });
        STROKED.put("target", new String[]{
                "M4 12A8 8 0 1 0 20 12A8 8 0 1 0 4 12Z",
                "M10 12A2 2 0 1 0 14 12A2 2 0 1 0 10 12Z",
                "M12 2v3M12 19v3M2 12h3M19 12h3",
        });
        STROKED.put("braces", new String[]{
                "M8 3c-2 0-3 1-3 3v3c0 1.5-1 2-2 3 1 1 2 1.5 2 3v3c0 2 1 3 3 3",
                "M16 3c2 0 3 1 3 3v3c0 1.5 1 2 2 3-1 1-2 1.5-2 3v3c0 2-1 3-3 3",
        });
        STROKED.put("clips", new String[]{
                "M5 5H14A2 2 0 0 1 16 7V17A2 2 0 0 1 14 19H5A2 2 0 0 1 3 17V7A2 2 0 0 1 5 5Z",
                "M19 8v11a2 2 0 0 1-2 2H8",
                "m8 9 4 3-4 3V9Z",
        });
        STROKED.put("cross", new String[]{
                "M4.5 12A7.5 7.5 0 1 0 19.5 12A7.5 7.5 0 1 0 4.5 12Z",
                "M10.4 12A1.6 1.6 0 1 0 13.6 12A1.6 1.6 0 1 0 10.4 12Z",
                "M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3",
        });
        STROKED.put("film", new String[]{
                "M5 5H14A2 2 0 0 1 16 7V17A2 2 0 0 1 14 19H5A2 2 0 0 1 3 17V7A2 2 0 0 1 5 5Z",
                "M19 8v11a2 2 0 0 1-2 2H8",
                "m8 9 4 3-4 3V9Z",
        });
        STROKED.put("out", new String[]{
                "M12 3v12",
                "m8 7 4-4 4 4",
                "M4 15v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4",
        });
        STROKED.put("save", new String[]{
                "M5 3h11l3 3v15H5V3Z",
                "M8 3v6h7V3M8 14h8M8 17h8",
        });
        STROKED.put("undo", new String[]{
                "M4 8h11a5 5 0 0 1 0 10h-6",
                "m8 4-4 4 4 4",
        });
        STROKED.put("redo", new String[]{
                "M20 8H9a5 5 0 0 0 0 10h6",
                "m16 4 4 4-4 4",
        });
        STROKED.put("caret", new String[]{
                "m6 9 6 6 6-6",
        });
        STROKED.put("onion", new String[]{
                "M12 6.2C12 4.5 13 3.4 14.4 3c-.2 1.6-1 2.6-2.4 3.2Z",
                "M12 6.2c-4.3 0-6.6 3.4-6.6 6.9 0 4 3 8.1 6.6 8.1s6.6-4.1 6.6-8.1c0-3.5-2.3-6.9-6.6-6.9Z",
                "M12 6.2c-2.1 0-3.2 3.4-3.2 6.9 0 4 1.4 8.1 3.2 8.1",
                "M12 6.2c2.1 0 3.2 3.4 3.2 6.9 0 4-1.4 8.1-3.2 8.1",
        });
        FILLED.put("play", new String[]{
                "M7 4l13 8-13 8V4Z",
        });
        STROKED.put("pause", new String[]{
                "M8 4v16M16 4v16",
        });
        STROKED.put("loop", new String[]{
                "M4 9a5 5 0 0 1 5-5h9",
                "m15 1 3 3-3 3",
                "M20 15a5 5 0 0 1-5 5H6",
                "m9 23-3-3 3-3",
        });
        STROKED.put("ping", new String[]{
                "M4 12h16",
                "m8 8-4 4 4 4M16 8l4 4-4 4",
        });
        STROKED.put("once", new String[]{
                "M3 12h13",
                "m12 7 5 5-5 5",
                "M20 5v14",
        });
        STROKED.put("rev", new String[]{
                "M20 12H5",
                "m10 7-5 5 5 5",
        });
        STROKED.put("tag", new String[]{
                "M3 11V4h7l10 10-7 7L3 11Z",
                "M6.3 7.5A1.2 1.2 0 1 0 8.7 7.5A1.2 1.2 0 1 0 6.3 7.5Z",
        });
        STROKED.put("wand", new String[]{
                "m4 20 10-10",
                "m14 6 4 4",
                "M17 3v4M21 5h-4M6 4v3M7.5 5.5h-3",
        });
        STROKED.put("zoom", new String[]{
                "M4 10.5A6.5 6.5 0 1 0 17 10.5A6.5 6.5 0 1 0 4 10.5Z",
                "m15.5 15.5 5 5M8 10.5h5M10.5 8v5",
        });
        STROKED.put("movex", new String[]{
                "M3 12h18",
                "m6 9-3 3 3 3M18 9l3 3-3 3",
        });
        STROKED.put("movey", new String[]{
                "M12 3v18",
                "m9 6 3-3 3 3M9 18l3 3 3-3",
        });
        STROKED.put("scale", new String[]{
                "M4.5 3H10.5A1.5 1.5 0 0 1 12 4.5V10.5A1.5 1.5 0 0 1 10.5 12H4.5A1.5 1.5 0 0 1 3 10.5V4.5A1.5 1.5 0 0 1 4.5 3Z",
                "M12 21h9v-9",
                "m13.5 19.5 6-6",
        });
        STROKED.put("rot", new String[]{
                "M20 12a8 8 0 1 1-2.6-5.9",
                "M20.5 3.5V8H16",
        });
        STROKED.put("fliph", new String[]{
                "M12 3v18",
                "M8 7 4 12l4 5V7ZM16 7l4 5-4 5V7Z",
        });
        STROKED.put("flipv", new String[]{
                "M3 12h18",
                "M7 8 12 4l5 4H7ZM7 16l5 4 5-4H7Z",
        });
        STROKED.put("gear", new String[]{
                "M9 12A3 3 0 1 0 15 12A3 3 0 1 0 9 12Z",
                "M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M19.1 4.9 17 7M7 17l-2.1 2.1",
        });
        STROKED.put("lab", new String[]{
                "M5.5 3H18.5A2.5 2.5 0 0 1 21 5.5V18.5A2.5 2.5 0 0 1 18.5 21H5.5A2.5 2.5 0 0 1 3 18.5V5.5A2.5 2.5 0 0 1 5.5 3Z",
                "M3 9h18M9 21V9",
        });
        STROKED.put("x", new String[]{
                "M6 6l12 12M18 6 6 18",
        });
        STROKED.put("back", new String[]{
                "M19 12H5",
                "m11 6-6 6 6 6",
        });
        STROKED.put("run", new String[]{
                "M13 4.5A2 2 0 1 0 17 4.5A2 2 0 1 0 13 4.5Z",
                "M13.5 21l1.5-5-3-2.5 1-5.5 3.5 2 2.5 1",
                "m12 8-3.5 1.5L6 14",
                "m12.5 13.5-4 2L6 21",
        });
    }

    /** True when {@code name} is one this set actually knows how to draw. */
    public static boolean has(@NonNull String name) {
        return STROKED.containsKey(name) || FILLED.containsKey(name);
    }

    @NonNull
    private static Path[] parse(@NonNull Map<String, String[]> src,
                                @NonNull Map<String, Path[]> cache, @NonNull String name) {
        Path[] got = cache.get(name);
        if (got != null) return got;
        String[] data = src.get(name);
        if (data == null) { cache.put(name, new Path[0]); return new Path[0]; }
        Path[] made = new Path[data.length];
        for (int i = 0; i < data.length; i++) {
            made[i] = androidx.core.graphics.PathParser.createPathFromPathData(data[i]);
        }
        cache.put(name, made);
        return made;
    }

    /**
     * An icon in one flat colour. No half-opaque anything: a control that is off gets a grey
     * icon, never a faded coloured one.
     */
    @NonNull
    public static IconDrawable of(@NonNull String name, int colour, int sizePx) {
        IconDrawable d = new IconDrawable(name, colour);
        d.setBounds(0, 0, sizePx, sizePx);
        return d;
    }

    public static final class IconDrawable extends Drawable {
        private final Path[] stroked;
        private final Path[] filled;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int colour;

        IconDrawable(@NonNull String name, int colour) {
            this.stroked = parse(STROKED, CACHE_S, name);
            this.filled = parse(FILLED, CACHE_F, name);
            this.colour = colour;
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        /** Retint in place: a chip that lights up does not need a new drawable. */
        public void setColour(int c) { colour = c; invalidateSelf(); }

        @Override public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            float s = Math.min(b.width(), b.height()) / BOX;
            int save = canvas.save();
            canvas.translate(b.left + (b.width() - BOX * s) / 2f,
                             b.top + (b.height() - BOX * s) / 2f);
            canvas.scale(s, s);
            paint.setColor(colour);
            paint.setStyle(Paint.Style.FILL);
            for (Path p : filled) canvas.drawPath(p, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(STROKE);
            for (Path p : stroked) canvas.drawPath(p, paint);
            canvas.restoreToCount(save);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(@Nullable ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return (int) BOX; }
        @Override public int getIntrinsicHeight() { return (int) BOX; }
    }
}
