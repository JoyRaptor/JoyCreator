package com.fadcam.ui.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.lobby.SlantDrawable;

/**
 * THE RECORDER HOME'S FACES — one place that decides what every slanted control on the
 * Bits / Capture home looks like, in every state.
 *
 * <h3>Why an adapter, not forty edits</h3>
 * The legacy HomeFragment and FadRecHomeFragment say what state a control is in by PAINTING it:
 * {@code setBackgroundTintList(button_stop)} means "this is Stop now", a green icon tint means
 * "the visualizer is armed", an orange glyph means "exposure is not at zero". There are roughly
 * forty such calls across two very large files. Restyling each one would mean forty edits in code
 * nobody can safely re-read, and the next legacy call added would quietly paint the old look back.
 *
 * <p>So the controls themselves translate. {@link HomeRecButton}, {@link HomeRecStartButton} and
 * {@link HomeRecTile} catch those colour calls and read them as STATE, then draw this file's face
 * for that state. Every state the old code can express still reaches the screen; it simply arrives
 * in the new language: zinc when off, solid colour with the glyph punched out in black when on.
 *
 * <h3>The rules being applied</h3>
 * <ul>
 *   <li>Off = zinc ({@link Studio#RAISED}) with white glyph. Greys fade.</li>
 *   <li>On = solid fill, glyph in pure black. Never an outline.</li>
 *   <li>Start = the lobby's Recording gradient, the one primary action on the screen.</li>
 *   <li>Stop = flat red. Same family as Start (the red record button that stays red while it
 *       records, as every camera does), but flat rather than graded, and the glyph and word change.</li>
 *   <li>Shape = the lobby's sheared chip: 10dp lean, 3dp corners.</li>
 * </ul>
 */
public final class HomeRecSkin {

    private HomeRecSkin() { }

    /** The lobby New chips' shear. At ~34dp tall this is about a 16 degree lean. */
    static final int SLANT_DP = 10;
    /** Corner softening, small on purpose (a rounded parallelogram is a lozenge). */
    static final int CORNER_DP = 3;

    static final int ON_ARMED = 0;
    static final int ON_DANGER = 1;

    /** What one view asked for in XML. */
    static final class Spec {
        int onFace = ON_ARMED;
        boolean flatLeft, flatRight;
    }

    @NonNull
    static Spec read(@NonNull Context c, @Nullable AttributeSet attrs) {
        Spec s = new Spec();
        if (attrs == null) return s;
        TypedArray a = c.obtainStyledAttributes(attrs, R.styleable.HomeRecSkin);
        try {
            s.onFace = a.getInt(R.styleable.HomeRecSkin_homeRecOnFace, ON_ARMED);
            s.flatLeft = a.getBoolean(R.styleable.HomeRecSkin_homeRecFlatLeft, false);
            s.flatRight = a.getBoolean(R.styleable.HomeRecSkin_homeRecFlatRight, false);
        } finally {
            a.recycle();
        }
        return s;
    }

    static int dp(@NonNull Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** A sheared chip in {@code a -> b}, inset vertically so a 48dp touch box draws a ~36dp chip. */
    @NonNull
    static Drawable chip(@NonNull Context c, int a, int b, boolean flatLeft, boolean flatRight,
                         int insetTop, int insetBottom) {
        SlantDrawable face = new SlantDrawable(a, b, dp(c, SLANT_DP), dp(c, CORNER_DP), flatLeft, flatRight);
        return new InsetDrawable(face, 0, insetTop, 0, insetBottom) {
            // An InsetDrawable reports its insets as padding, and View.setBackground would then
            // REPLACE the button's own padding with them: the icon would slide off centre and a
            // label would run into the slanted ends. The inset is drawing only; padding stays the view's.
            @Override
            public boolean getPadding(@NonNull Rect padding) {
                padding.set(0, 0, 0, 0);
                return false;
            }
        };
    }

    /** Zinc, for any control that is not on. */
    @NonNull
    static Drawable off(@NonNull Context c, Spec s, int insetTop, int insetBottom) {
        return chip(c, Studio.RAISED, Studio.RAISED, s.flatLeft, s.flatRight, insetTop, insetBottom);
    }

    /** Solid colour, for a control that is on. */
    @NonNull
    static Drawable on(@NonNull Context c, Spec s, int insetTop, int insetBottom) {
        return s.onFace == ON_DANGER
                ? chip(c, Studio.DANGER, Studio.DANGER, s.flatLeft, s.flatRight, insetTop, insetBottom)
                : chip(c, Studio.ARMED_LIGHT, Studio.ARMED, s.flatLeft, s.flatRight, insetTop, insetBottom);
    }

    /** The primary action: the lobby's Recording chip, pink-red into magenta. */
    @NonNull
    static Drawable start(@NonNull Context c, Spec s, int insetTop, int insetBottom) {
        return chip(c, Studio.ROOM_CAPTURE, Studio.ROOM_SPRITE, s.flatLeft, s.flatRight, insetTop, insetBottom);
    }

    /** Recording is running: flat red, the same family as Start, so it reads as the same button. */
    @NonNull
    static Drawable stop(@NonNull Context c, Spec s, int insetTop, int insetBottom) {
        return chip(c, Studio.DANGER, Studio.DANGER, s.flatLeft, s.flatRight, insetTop, insetBottom);
    }

    /**
     * True when the legacy code painted this control with its STOP colour. That colour is how both
     * fragments say "recording is running" (Start/Stop) and "this toggle is on" (mirror, no-sound).
     * Read from the same resource the callers use, so it follows values-night too.
     */
    static boolean isStopTint(@NonNull Context c, @Nullable ColorStateList t) {
        if (t == null) return false;
        int col = t.getDefaultColor();
        return col == ContextCompat.getColor(c, R.color.button_stop)
                || col == Studio.DANGER
                || col == 0xFFFF0000;
    }

    /**
     * True when the legacy code chose a SIGNAL colour (a saturated hue) rather than a neutral
     * ink. Green visualizer, amber torch, orange exposure: every one of them meant "on". White,
     * black and the greys never did.
     */
    static boolean isSignal(@Nullable ColorStateList t) {
        return t != null && isSignal(t.getDefaultColor());
    }

    static boolean isSignal(int col) {
        if (Color.alpha(col) < 0x80) return false;
        float[] hsv = new float[3];
        Color.colorToHSV(col, hsv);
        return hsv[1] >= 0.40f && hsv[2] >= 0.35f;
    }
}
