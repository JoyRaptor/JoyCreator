package com.fadcam.ui.about;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;

import com.fadcam.R;

/**
 * THE NEON WORDMARK, as big as the screen allows: the lobby's own banner art
 * ({@code lobby_banner}), so the About page wears exactly the logo the front door does.
 *
 * <p>The lobby pins the art to the left at its row's height and lets the grid run off the right,
 * because the art was drawn with its quiet end on the right. This does the same, but chooses the
 * height itself: as tall as {@link #MAX_DP}, and never so tall that the lettering (which ends at
 * about {@link #INK_END} of the art's width, streak included) would be cut by the screen's edge.
 * On the Note 9 that is about 88dp; on a narrow phone it shrinks rather than crops.</p>
 */
final class AboutBanner extends AppCompatImageView {

    /** Where the lettering and its first streak end, as a share of the art's width. */
    private static final float INK_END = 0.72f;
    /** The tallest the wordmark is drawn. */
    private static final float MAX_DP = 104f;

    AboutBanner(@NonNull Context c) {
        super(c);
        setImageResource(R.drawable.lobby_banner);
        setScaleType(ScaleType.MATRIX);
        setContentDescription(c.getString(R.string.aboutjoy_banner_desc));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        Drawable d = getDrawable();
        int width = MeasureSpec.getSize(widthSpec);
        if (d == null || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0 || width <= 0) {
            super.onMeasure(widthSpec, heightSpec);
            return;
        }
        float iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        float maxH = MAX_DP * getResources().getDisplayMetrics().density;
        float scale = Math.min(maxH / ih, width / (INK_END * iw));
        setMeasuredDimension(width, Math.round(ih * scale));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        Drawable d = getDrawable();
        if (d == null || d.getIntrinsicHeight() <= 0 || h <= 0) return;
        // Scale to the height, pinned to x = 0: the lobby's fitBanner, for the same reason.
        float s = h / (float) d.getIntrinsicHeight();
        Matrix m = new Matrix();
        m.setScale(s, s);
        setImageMatrix(m);
    }
}
