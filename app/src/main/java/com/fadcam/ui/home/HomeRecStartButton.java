package com.fadcam.ui.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.utils.AnimatedMaterialButton;

/**
 * The recorder's one primary action: Start, and Stop while recording.
 *
 * <p>Still an {@link AnimatedMaterialButton}, so the slot-machine label, the icon crossfade and
 * every fragment call behave exactly as before. Only the face changes: the lobby's Recording chip
 * (sheared, pink-red into magenta) when idle, flat red once recording is running. The fragments
 * announce the switch by tinting the background {@code button_stop} or {@code button_start}
 * (or Material green, in one FadRec path); that call is read as the state and drawn as the face.
 *
 * <p>Ink is pure black on both faces, so the word and the glyph read as cut out of the chip and
 * never need to change colour mid-animation.
 */
public class HomeRecStartButton extends AnimatedMaterialButton {

    private static final float CHIP_DP = 36f;

    private boolean ready;
    private HomeRecSkin.Spec spec;
    private boolean stop;

    public HomeRecStartButton(@NonNull Context context) {
        this(context, null);
    }

    public HomeRecStartButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, com.google.android.material.R.attr.materialButtonStyle);
    }

    public HomeRecStartButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        spec = HomeRecSkin.read(context, attrs);
        stop = HomeRecSkin.isStopTint(context, super.getBackgroundTintList());
        ready = true;
        SheetKit.press(this);
        super.setIconTint(ColorStateList.valueOf(Studio.GROUND));
        setTextColor(Studio.GROUND);
        repaint();
        clearFrameworkTint();
    }

    @Override
    public void setBackgroundTintList(@Nullable ColorStateList tint) {
        // AnimatedMaterialButton's own override derives a readable ink from the fill and tints the
        // background with it. Both faces here carry black ink, so neither step is wanted.
        if (!ready) { super.setBackgroundTintList(tint); return; }
        stop = HomeRecSkin.isStopTint(getContext(), tint);
        repaint();
    }

    @Override
    public void setIconTint(@Nullable ColorStateList tint) {
        // The glyph is a hole in the chip, always black. (Only the Snow Veil theme path calls
        // this on this button, and it asks for black too.)
        if (!ready) { super.setIconTint(tint); return; }
        super.setIconTint(ColorStateList.valueOf(Studio.GROUND));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (ready) repaint();
    }

    /** True while the face says "recording is running, tap to stop". */
    public boolean isShowingStop() {
        return stop;
    }

    private void repaint() {
        Context c = getContext();
        int h = getHeight();
        int inset = h > 0 ? Math.max(0, (h - HomeRecSkin.dp(c, CHIP_DP)) / 2) : HomeRecSkin.dp(c, 6);
        setBackground(stop ? HomeRecSkin.stop(c, spec, inset, inset) : HomeRecSkin.start(c, spec, inset, inset));
    }

    /** Clear any framework tint left from XML, by-passing this class's own state reading. */
    private void clearFrameworkTint() {
        ready = false;
        super.setBackgroundTintList(null);
        ready = true;
    }
}
