package com.fadcam.ui.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.google.android.material.button.MaterialButton;

/**
 * A secondary icon control on the recorder home (pause, torch, camera switch, mirror, sound
 * source, live visualizer): a sheared zinc chip with a white glyph, or a solid chip with the glyph
 * punched out in black while it is ON.
 *
 * <p>It is a drop-in {@link MaterialButton}, so every {@code findViewById} cast and every call the
 * fragments make keeps working. The colour calls they make are read as state rather than applied
 * as paint (see {@link HomeRecSkin} for why):
 * <ul>
 *   <li>{@code setBackgroundTintList(button_stop)} = on (mirror on, "no sound" chosen)</li>
 *   <li>{@code setIconTint(a saturated colour)} = on (visualizer armed, torch lit)</li>
 *   <li>{@code setSelected(true)} = on (torch)</li>
 *   <li>anything else = off</li>
 * </ul>
 * Enabled/disabled is untouched: the fragments already dim with alpha, and still do.
 */
public class HomeRecButton extends MaterialButton {

    /** Chip height drawn inside the 48dp touch box; the lobby chips are 34dp, Material's inset face 36dp. */
    private static final float CHIP_DP = 36f;

    private boolean ready;
    private HomeRecSkin.Spec spec;
    private boolean bgOn, iconOn;

    public HomeRecButton(@NonNull Context context) {
        this(context, null);
    }

    public HomeRecButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, com.google.android.material.R.attr.materialButtonStyle);
    }

    public HomeRecButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        spec = HomeRecSkin.read(context, attrs);
        // Whatever the XML tint said is the starting state (pause, sound, visualizer: all off).
        bgOn = HomeRecSkin.isStopTint(context, super.getBackgroundTintList());
        ready = true;
        SheetKit.press(this);
        repaint();
        // The face is ours now; make sure no framework tint is left to wash over it.
        super.setBackgroundTintList(null);
    }

    @Override
    public void setBackgroundTintList(@Nullable ColorStateList tint) {
        if (!ready) { super.setBackgroundTintList(tint); return; }
        bgOn = HomeRecSkin.isStopTint(getContext(), tint);
        repaint();
    }

    @Override
    public void setIconTint(@Nullable ColorStateList tint) {
        if (!ready) { super.setIconTint(tint); return; }
        iconOn = HomeRecSkin.isSignal(tint);
        repaint();
    }

    @Override
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        if (ready) repaint();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (ready) repaint();
    }

    /** True while the control shows its ON face. */
    public boolean isShowingOn() {
        return bgOn || iconOn || isSelected();
    }

    private void repaint() {
        Context c = getContext();
        int h = getHeight();
        int inset = h > 0 ? Math.max(0, (h - HomeRecSkin.dp(c, CHIP_DP)) / 2) : HomeRecSkin.dp(c, 6);
        boolean on = isShowingOn();
        setBackground(on ? HomeRecSkin.on(c, spec, inset, inset) : HomeRecSkin.off(c, spec, inset, inset));
        int ink = on ? Studio.GROUND : Studio.INK;
        super.setIconTint(ColorStateList.valueOf(ink));
        setTextColor(ink);
    }
}
