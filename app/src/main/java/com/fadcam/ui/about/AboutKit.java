package com.fadcam.ui.about;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.lobby.SlantDrawable;
import com.fadcam.ui.type.Type;

/**
 * The few drawing parts the About page's sections share, on top of {@link SheetKit} (labels,
 * press feel, icon face) and the lobby's {@link SlantDrawable}. Every colour is a Studio token.
 */
final class AboutKit {

    /** The page's side gutter: the lobby's, so the About page lines up with the room it came from. */
    static final int GUTTER_DP = 18;

    /** Which face a line of text is set in. */
    static final int BODY = 0, DISPLAY = 1, MONO = 2;

    private AboutKit() {}

    static int dp(@NonNull Context c, float v) {
        return SheetKit.dp(c, v);
    }

    /** A line of text in a face, weight, size and ink. */
    @NonNull
    static TextView text(@NonNull Context c, float sp, int colour, int face, int weight) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        if (face == DISPLAY) Type.display(t, weight);
        else if (face == MONO) Type.mono(t, weight);
        else Type.body(t, weight);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** A materialicons glyph, never read aloud (its text is the glyph's name). */
    @NonNull
    static TextView glyph(@NonNull Context c, @NonNull String ligature, float sp, int colour) {
        TextView g = new TextView(c);
        g.setTypeface(SheetKit.icons(c));
        g.setText(ligature);
        g.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        g.setTextColor(colour);
        g.setGravity(Gravity.CENTER);
        g.setIncludeFontPadding(false);
        g.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return g;
    }

    /** A small mono capital label over a section: 8.5sp, .14em, faint. */
    @NonNull
    static TextView label(@NonNull Context c, @StringRes int words) {
        TextView t = text(c, 8.5f, Studio.INK_FAINT, MONO, Type.MEDIUM);
        t.setText(words);
        t.setLetterSpacing(0.14f);
        t.setSingleLine(true);
        t.setAllCaps(true);   // after setSingleLine: both are transformations, the last one wins
        return t;
    }

    /** Full width, inside the gutter, {@code topDp} below the part above. */
    @NonNull
    static LinearLayout.LayoutParams block(@NonNull Context c, float topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, GUTTER_DP), dp(c, topDp), dp(c, GUTTER_DP), 0);
        return lp;
    }

    /**
     * A card on the ground: RAISED at about 55% (the see-through panel rule), 14dp corners, the
     * Settings hub's group radius. Not itself a button.
     */
    @NonNull
    static Drawable card(@NonNull Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Studio.alpha(Studio.RAISED, 0x8C));
        g.setCornerRadius(dp(c, 14));
        return g;
    }

    /**
     * Press feedback for a row INSIDE a card: clear at rest (the card's fill shows through, so the
     * row is never a brighter patch on a see-through card), the PRESSED ripple, an ARMED focus ring.
     */
    @NonNull
    static Drawable pressable(@NonNull Context c, float radiusDp) {
        float r = dp(c, radiusDp);
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0);
        focused.setCornerRadius(r);
        focused.setStroke(dp(c, 2), Studio.ARMED);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, new ColorDrawable(0));
        states.setEnterFadeDuration(0);   // a focus ring that animates reads as lag
        states.setExitFadeDuration(0);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Studio.INK);
        mask.setCornerRadius(r);
        return new RippleDrawable(ColorStateList.valueOf(Studio.PRESSED), states, mask);
    }

    /**
     * THE SIGNATURE CHIP: the lobby's New-row chip. A sheared gradient with the glyph and label
     * punched out in pure black, squared off on the left so it sits flush in the gutter.
     *
     * @param heightDp the chip's height; the shear keeps the lobby's lean (10dp of run at 34dp)
     */
    @NonNull
    static LinearLayout slantChip(@NonNull Context c, @NonNull String ligature, @NonNull CharSequence words,
                                  int gradA, int gradB, float heightDp, float textSp) {
        LinearLayout cell = new LinearLayout(c);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        float slant = heightDp * 10f / 34f;
        cell.setBackground(new SlantDrawable(gradA, gradB, dp(c, slant), dp(c, 3), true, false));
        cell.setMinimumHeight(dp(c, heightDp));
        cell.setPadding(dp(c, 10), 0, dp(c, slant + 8), 0);

        TextView ic = glyph(c, ligature, textSp + 4f, Studio.GROUND);
        cell.addView(ic);

        TextView tv = text(c, textSp, Studio.GROUND, DISPLAY, Type.BOLD);
        tv.setText(words);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMarginStart(dp(c, 6));
        cell.addView(tv, tlp);
        return cell;
    }

    /** Open a web address in the browser; a short note when there is none. */
    static void openUrl(@NonNull Context c, @NonNull String url) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            Toast.makeText(c, R.string.aboutjoy_no_browser, Toast.LENGTH_SHORT).show();
        }
    }
}
