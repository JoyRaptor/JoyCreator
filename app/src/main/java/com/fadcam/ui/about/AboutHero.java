package com.fadcam.ui.about;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

/**
 * THE HERO: the neon wordmark full-bleed over old black, a breathing glow behind it, and one line
 * saying what Joy Creator is, with a quieter line under it saying what you do in it.
 */
final class AboutHero {

    @NonNull final LinearLayout view;
    @NonNull private final AboutGlow glow;

    AboutHero(@NonNull Context c) {
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);

        FrameLayout stage = new FrameLayout(c);
        glow = new AboutGlow(c);
        stage.addView(glow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        AboutBanner banner = new AboutBanner(c);
        FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL);
        // Room above and below for the glow to spread into; the art itself has none.
        bLp.topMargin = AboutKit.dp(c, 26);
        bLp.bottomMargin = AboutKit.dp(c, 26);
        stage.addView(banner, bLp);
        view.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tagline = AboutKit.text(c, 17f, Studio.INK, AboutKit.DISPLAY, Type.EXTRA);
        tagline.setText(R.string.aboutjoy_tagline);
        tagline.setLetterSpacing(-0.02f);
        view.addView(tagline, AboutKit.block(c, 0));

        TextView sub = AboutKit.text(c, 12.5f, Studio.INK_DIM, AboutKit.BODY, Type.REGULAR);
        sub.setText(R.string.aboutjoy_tagline_sub);
        sub.setLineSpacing(0, 1.25f);
        view.addView(sub, AboutKit.block(c, 6));
    }

    /** The glow breathes only while the page is in front. */
    void breathe(boolean on) {
        glow.breathe(on);
    }
}
