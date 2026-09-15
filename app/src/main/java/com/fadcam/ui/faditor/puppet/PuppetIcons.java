package com.fadcam.ui.faditor.puppet;

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
 * The puppeteering icon set — the same path data as the interactive design study.
 *
 * <p>Authored in a 24x24 box, stroked at 1.7 with round caps and joins, <b>deliberately the same
 * convention as {@code SpriteIcons}</b>, so a puppet tool and a SpriteLab tool sit beside each
 * other at the same weight. Transcribed by hand rather than generated, because the study is an
 * artifact rather than a file in this repo; if it ever lands in {@code tools/} then
 * {@code genicons.py} should own this file too.
 *
 * <p>It does not reuse {@code SpriteIcons.IconDrawable} on purpose: that file is MACHINE
 * GENERATED and says at the top not to hand-edit it. Widening it to serve a second, hand-written
 * set would mean teaching the generator about a source it does not have. Forty lines of drawing
 * code is the cheaper half of that trade.
 */
public final class PuppetIcons {

    private PuppetIcons() {}

    /** viewBox side. Every path below is authored in this space. */
    private static final float BOX = 24f;
    private static final float STROKE = 1.7f;

    // ── names, so callers cannot typo a lookup into an empty icon ────────

    public static final String GRAB   = "grab";
    public static final String PIN    = "pin";
    public static final String STIFF  = "stiff";
    public static final String DANGLE = "dangle";
    public static final String FREE   = "free";
    public static final String BONE   = "bone";
    public static final String EYE    = "eye";
    public static final String PUPPET = "puppet";
    public static final String LOCK   = "lock";

    private static final Map<String, String[]> STROKED = new HashMap<>();
    private static final Map<String, String[]> FILLED = new HashMap<>();
    private static final Map<String, Path[]> CACHE_S = new HashMap<>();
    private static final Map<String, Path[]> CACHE_F = new HashMap<>();

    static {
        // An open hand — "you are moving things, not making them".
        STROKED.put(GRAB, new String[]{
                "M9 11V5.5a1.5 1.5 0 0 1 3 0V11m0-1.5a1.5 1.5 0 0 1 3 0V12m0-1a1.5 1.5 0 0 1 3 0"
                        + "v4.5c0 3.3-2.5 5.5-5.5 5.5S6 18.8 6 15.5V12a1.5 1.5 0 0 1 3 0",
        });
        // A drawing pin, head and spike. The anchor.
        STROKED.put(PIN, new String[]{
                "M8.8 9A3.2 3.2 0 1 0 15.2 9A3.2 3.2 0 1 0 8.8 9Z",
                "M12 12.2V20",
        });
        // The same spike under a SQUARE head: a right angle reads as "this will not bend".
        STROKED.put(STIFF, new String[]{
                "M9.9 5.8H14.1A1.4 1.4 0 0 1 15.5 7.2V11.4A1.4 1.4 0 0 1 14.1 12.8"
                        + "H9.9A1.4 1.4 0 0 1 8.5 11.4V7.2A1.4 1.4 0 0 1 9.9 5.8Z",
                "M12 12.8V20",
        });
        // A weight on a thread over a swing arc — hangs and sways.
        STROKED.put(DANGLE, new String[]{
                "M12 4v7",
                "M8.8 14.2A3.2 3.2 0 1 0 15.2 14.2A3.2 3.2 0 1 0 8.8 14.2Z",
                "M8 20c2-1.4 6-1.4 8 0",
        });
        // A hub with four ticks — moves on every axis. Echoes the transform tool's FREE circle.
        STROKED.put(FREE, new String[]{
                "M8 12A4 4 0 1 0 16 12A4 4 0 1 0 8 12Z",
                "M12 3.4v2.4M12 18.2v2.4M3.4 12h2.4M18.2 12h2.4",
        });
        // Two joints and the shaft between them. A bone IS its two pins.
        STROKED.put(BONE, new String[]{
                "M7.9 6.9 16.1 14.6",
                "M4.3 5.4A2.1 2.1 0 1 0 8.5 5.4A2.1 2.1 0 1 0 4.3 5.4Z",
                "M15.1 15.2A2.1 2.1 0 1 0 19.3 15.2A2.1 2.1 0 1 0 15.1 15.2Z",
        });
        // Reach — the influence ring toggle beside a slider.
        STROKED.put(EYE, new String[]{
                "M2.2 12S6 5.6 12 5.6 21.8 12 21.8 12 18 18.4 12 18.4 2.2 12 2.2 12Z",
                "M9.4 12A2.6 2.6 0 1 0 14.6 12A2.6 2.6 0 1 0 9.4 12Z",
        });
        // A marionette on its control bar. THE GATE, in the corner of the picture.
        STROKED.put(PUPPET, new String[]{
                "M9.8 5A2.2 2.2 0 1 0 14.2 5A2.2 2.2 0 1 0 9.8 5Z",
                "M12 7.2v6.2M12 13.4 9 19M12 13.4 15 19M8.4 9.6h7.2",
                "M6 2.4v2.2M18 2.4v2.2M6 2.4h12",
                "M8.4 9.6 6 4.6M15.6 9.6 18 4.6",
        });
        // Shown in front of the puppet when the rig is locked.
        STROKED.put(LOCK, new String[]{
                "M4.4 10.6H15.6A1.6 1.6 0 0 1 17.2 12.2V18A1.6 1.6 0 0 1 15.6 19.6"
                        + "H4.4A1.6 1.6 0 0 1 2.8 18V12.2A1.6 1.6 0 0 1 4.4 10.6Z",
                "M6.4 10.6V7.6a3.6 3.6 0 0 1 7.2 0v3",
        });
    }

    public static boolean has(@NonNull String name) {
        return STROKED.containsKey(name) || FILLED.containsKey(name);
    }

    /** The icon for a pin type, so a tool button and a pin can never disagree about which is which. */
    @NonNull
    public static String forType(@NonNull PuppetPin.Type type) {
        switch (type) {
            case PIN: return PIN;
            case STIFF: return STIFF;
            case DANGLE: return DANGLE;
            default: return FREE;
        }
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
     * An icon in ONE FLAT COLOUR. A control that is off gets a grey icon, never a faded coloured
     * one — the same rule {@code SpriteIcons} states, kept here so the two sets behave alike.
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

        /** Retint in place: a tool that lights up does not need a new drawable. */
        public void setColour(int c) {
            if (c != colour) { colour = c; invalidateSelf(); }
        }

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
