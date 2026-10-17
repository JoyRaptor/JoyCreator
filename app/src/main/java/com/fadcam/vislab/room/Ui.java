package com.fadcam.vislab.room;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

/** Small builders shared by the VisLab room's views, all on the Studio tokens. */
public final class Ui {

    private Ui() {}

    /** VisLab's room gradient (the lobby card's amber pair). */
    public static final int VIZ_A = Studio.ROOM_VIZ, VIZ_B = Studio.ROOM_VIZ_DEEP;
    /** Drawers are never opaque: the Studio panel at ~55%. */
    public static final int DRAWER = (0x8C << 24) | (Studio.PANEL & 0xFFFFFF);

    private static Typeface icons;

    public static int dp(@NonNull Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    @NonNull
    public static Typeface iconFont(@NonNull Context c) {
        if (icons == null) {
            Typeface t = ResourcesCompat.getFont(c, R.font.materialicons);
            icons = t != null ? t : Typeface.DEFAULT;
        }
        return icons;
    }

    /** An icon from the Material Icons font, by ligature name ("mic", "music_note"). */
    @NonNull
    public static TextView icon(@NonNull Context c, @NonNull String ligature, float sp, int color) {
        TextView t = new TextView(c);
        t.setTypeface(iconFont(c));
        t.setText(ligature);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER);
        // The ligature is the glyph's name: a screen reader would read it aloud. The control
        // around it carries the spoken label instead.
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    @NonNull
    public static TextView text(@NonNull Context c, @NonNull CharSequence s, float sp, int color, boolean display, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(display ? Type.display(c, weight) : Type.body(c, weight));
        return t;
    }

    /** Every control gets a hover label: stylus and mouse users see it, TalkBack reads it. */
    public static void label(@NonNull View v, @NonNull String what) {
        v.setContentDescription(what);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.setTooltipText(what);
    }

    @NonNull
    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    @NonNull
    static GradientDrawable topRounded(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadii(new float[]{radiusPx, radiusPx, radiusPx, radiusPx, 0, 0, 0, 0});
        return g;
    }

    /** Hex colour for display swatches (palette chips), tolerant of bad input. */
    static int parse(@Nullable String hex, int fallback) {
        if (hex == null) return fallback;
        try {
            String h = hex.trim();
            if (!h.startsWith("#")) h = "#" + h;
            if (h.length() == 4) h = "#" + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2) + h.charAt(3) + h.charAt(3);
            return android.graphics.Color.parseColor(h);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
