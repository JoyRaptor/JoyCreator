package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.widget.SeekBar;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.Studio;

/**
 * THE one slider face: a 4dp rounded track, a filled part, a 15dp round thumb. Drawer sliders
 * (via ObjectDrawer.Kit.styleSlider) and the colour picker both draw through here, so a slider
 * can never come out in the platform's lavender in one place and the Studio greys in another.
 *
 * <p>Geometry is set by layer gravity (API 23+; minSdk is 24), so the track is 4dp whatever
 * height the bar is laid out at; the touch height is untouched.
 */
public final class SliderFace {

    private SliderFace() {}

    /** The track colour every Studio slider shares. */
    public static final int TRACK = Studio.alpha(Studio.DRAWER_INK, 0x2E);

    /** Apply the face with an explicit fill colour and the drawer-ink thumb. */
    public static void apply(@NonNull SeekBar bar, int fillColour) {
        apply(bar, fillColour, TRACK, Studio.DRAWER_INK);
    }

    public static void apply(@NonNull SeekBar bar, int fillColour, int trackColour, int thumbColour) {
        Context ctx = bar.getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        int h = Math.round(4 * d);
        // setProgressDrawable can drop the level on some API levels; put it back below.
        int progress = bar.getProgress();
        GradientDrawable track = new GradientDrawable();
        track.setColor(trackColour);
        track.setCornerRadius(h);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(fillColour);
        fill.setCornerRadius(h);
        ClipDrawable clip = new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{track, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) {
            layers.setLayerHeight(i, h);
            layers.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        }
        bar.setProgressDrawable(layers);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(thumbColour);
        int sz = Math.round(15 * d);
        thumb.setSize(sz, sz);
        bar.setThumb(thumb);
        bar.setSplitTrack(false);
        bar.setProgress(progress);
    }
}
