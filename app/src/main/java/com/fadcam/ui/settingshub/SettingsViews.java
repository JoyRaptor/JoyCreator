package com.fadcam.ui.settingshub;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

/**
 * The few drawing parts the hub's own views share, on top of {@link SheetKit} (labels, press feel,
 * icon face). Every colour is a Studio token.
 */
final class SettingsViews {

    /** A group's outer corners, and the near-square corners where two rows of it meet. */
    static final float OUTER_RADIUS_DP = 14f, INNER_RADIUS_DP = 4f;

    private SettingsViews() {}

    static int dp(@NonNull Context c, float v) {
        return SheetKit.dp(c, v);
    }

    /** One line of text in a face and weight, ellipsized. */
    @NonNull
    static TextView line(@NonNull Context c, float sp, int colour, boolean mono, int weight) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        if (mono) Type.mono(t, weight); else Type.body(t, weight);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** A materialicons glyph, centred, never read aloud (its text is the glyph's name). */
    @NonNull
    static TextView glyph(@NonNull Context c, float sp, int colour) {
        TextView g = new TextView(c);
        g.setTypeface(SheetKit.icons(c));
        g.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        g.setTextColor(colour);
        g.setGravity(Gravity.CENTER);
        g.setIncludeFontPadding(false);
        g.setImportantForAccessibility(TextView.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return g;
    }

    /** A flat fill with four corner radii (top-left, top-right, bottom-right, bottom-left), in px. */
    @NonNull
    static GradientDrawable fill(int colour, float tl, float tr, float br, float bl) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(colour);
        g.setCornerRadii(new float[]{tl, tl, tr, tr, br, br, bl, bl});
        return g;
    }

    /** A fully round flat fill. */
    @NonNull
    static GradientDrawable pill(@NonNull Context c, int colour) {
        float r = dp(c, 99);
        return fill(colour, r, r, r, r);
    }

    /**
     * A tappable surface in a group: RAISED, its corners by where it sits, the PRESSED ripple, and a
     * 2dp ARMED focus ring that appears instantly (a ring that animates reads as lag).
     */
    @NonNull
    static Drawable rowBackground(@NonNull Context c, @NonNull SettingsLines.Shape shape) {
        float o = dp(c, OUTER_RADIUS_DP), i = dp(c, INNER_RADIUS_DP);
        boolean top = shape == SettingsLines.Shape.ONLY || shape == SettingsLines.Shape.FIRST;
        boolean bottom = shape == SettingsLines.Shape.ONLY || shape == SettingsLines.Shape.LAST;
        float t = top ? o : i, b = bottom ? o : i;
        return surface(c, Studio.RAISED, t, b);
    }

    /** A pressable pill (the dock's chips and search field). */
    @NonNull
    static Drawable pressablePill(@NonNull Context c, int colour) {
        float r = dp(c, 99);
        return surface(c, colour, r, r);
    }

    @NonNull
    private static Drawable surface(@NonNull Context c, int colour, float top, float bottom) {
        GradientDrawable rest = fill(colour, top, top, bottom, bottom);
        GradientDrawable focused = fill(colour, top, top, bottom, bottom);
        focused.setStroke(dp(c, 2), Studio.ARMED);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, rest);
        states.setEnterFadeDuration(0);
        states.setExitFadeDuration(0);
        return new RippleDrawable(ColorStateList.valueOf(Studio.PRESSED), states,
                fill(Studio.INK, top, top, bottom, bottom));
    }
}
