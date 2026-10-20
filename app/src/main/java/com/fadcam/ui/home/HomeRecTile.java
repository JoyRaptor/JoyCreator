package com.fadcam.ui.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;

/**
 * One of the camera quick tiles (autofocus, exposure, zoom): a sheared zinc chip with a white
 * glyph, or a solid cyan chip with the glyph cut out in black while that setting is away from
 * its default.
 *
 * <p>HomeFragment says "exposure is not at zero" / "zoom is not 1.0x" by colouring the glyph
 * orange, in about a dozen places. This view reads that colour as state (a saturated hue = on,
 * any neutral ink = off) and draws the face, so none of those places had to change.
 */
public class HomeRecTile extends AppCompatTextView {

    /** Chip height inside the tile's 40dp row: the lobby New chips' 34dp. */
    private static final float CHIP_DP = 34f;

    private boolean ready;
    private HomeRecSkin.Spec spec;
    private boolean on;

    public HomeRecTile(@NonNull Context context) {
        this(context, null);
    }

    public HomeRecTile(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, android.R.attr.textViewStyle);
    }

    public HomeRecTile(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        spec = HomeRecSkin.read(context, attrs);
        ready = true;
        SheetKit.press(this);
        repaint();
    }

    @Override
    public void setTextColor(int color) {
        if (!ready) { super.setTextColor(color); return; }
        on = HomeRecSkin.isSignal(color);
        repaint();
    }

    @Override
    public void setTextColor(@Nullable ColorStateList colors) {
        if (!ready || colors == null) { super.setTextColor(colors); return; }
        on = HomeRecSkin.isSignal(colors);
        repaint();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (ready) repaint();
    }

    /** True while the tile shows its ON face. */
    public boolean isShowingOn() {
        return on;
    }

    private void repaint() {
        Context c = getContext();
        int h = getHeight();
        int inset = h > 0 ? Math.max(0, (h - HomeRecSkin.dp(c, CHIP_DP)) / 2) : HomeRecSkin.dp(c, 3);
        setBackground(on ? HomeRecSkin.on(c, spec, inset, inset) : HomeRecSkin.off(c, spec, inset, inset));
        super.setTextColor(on ? Studio.GROUND : Studio.INK);
    }
}
