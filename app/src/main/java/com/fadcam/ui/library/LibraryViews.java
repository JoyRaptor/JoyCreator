package com.fadcam.ui.library;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

/**
 * The few small view parts the Library's own views share, on top of {@link SheetKit} (which owns
 * chips, rows, sheets, labels and the press feel). Everything is a Studio token.
 */
public final class LibraryViews {

    private LibraryViews() {}

    public static int dp(@NonNull Context c, float v) {
        return SheetKit.dp(c, v);
    }

    /** One line of text in a face and weight, ellipsized. */
    @NonNull
    public static TextView line(@NonNull Context c, float sp, int colour, boolean mono, int weight) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        if (mono) Type.mono(t, weight); else Type.body(t, weight);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** A materialicons glyph, centred. */
    @NonNull
    public static TextView glyph(@NonNull Context c, float sp, int colour) {
        TextView g = new TextView(c);
        g.setTypeface(SheetKit.icons(c));
        g.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        g.setTextColor(colour);
        g.setGravity(Gravity.CENTER);
        g.setIncludeFontPadding(false);
        return g;
    }

    /** A flat rounded fill. */
    @NonNull
    public static GradientDrawable fill(int colour, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(colour);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** A rounded fill that answers a press with the PRESSED ripple, like every Studio control. */
    @NonNull
    public static Drawable pressable(int colour, float radiusPx) {
        GradientDrawable mask = fill(Studio.INK, radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(Studio.PRESSED), fill(colour, radiusPx), mask);
    }
}
