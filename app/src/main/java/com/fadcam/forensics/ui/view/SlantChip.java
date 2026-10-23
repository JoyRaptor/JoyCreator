package com.fadcam.forensics.ui.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.lobby.SlantDrawable;
import com.fadcam.ui.type.Type;
import com.google.android.material.chip.Chip;

/**
 * A Material {@link Chip} wearing the Library's sheared face, for the Smart Detection filters.
 *
 * <p>It is still a Chip, so every id, {@code ChipGroup} selection rule, {@code setChecked},
 * {@code setText} (with the count badge) and {@code setChipIconResource} the Smart Detection
 * screens already use keep working untouched. Only the paint changes: the ChipDrawable is made
 * invisible (no fill, no outline, no ripple) and a {@link SlantDrawable} is drawn under it.
 *
 * <p>ON is solid cyan with the label and glyph punched out in black; OFF is the raised zinc face
 * with dim ink; a finger down is the pressed zinc. The legacy screens still try to tint these
 * chips from the theme's button colour: those three calls are ignored here on purpose, so the
 * look lives in one place and cannot drift back.
 */
public class SlantChip extends Chip {

    /** The shear shared with the lobby's New chips and the Library chips. */
    private static final int SLANT_DP = 10;

    private final Drawable faceOn;
    private final Drawable faceOff;
    private final Drawable facePressed;
    private boolean ready;

    public SlantChip(@NonNull Context context) {
        this(context, null);
    }

    public SlantChip(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float slant = dp(SLANT_DP);
        float corner = dp(3);
        faceOn = new SlantDrawable(Studio.ARMED_LIGHT, Studio.ARMED, slant, corner, false, false);
        faceOff = new SlantDrawable(Studio.RAISED, Studio.RAISED, slant, corner, false, false);
        facePressed = new SlantDrawable(Studio.PRESSED, Studio.PRESSED, slant, corner, false, false);

        super.setChipBackgroundColor(ColorStateList.valueOf(Color.TRANSPARENT));
        setChipStrokeWidth(0f);
        setRippleColor(ColorStateList.valueOf(Color.TRANSPARENT));
        setElevation(0f);
        setEnsureMinTouchTargetSize(false);
        setCheckedIconVisible(false);
        setChipMinHeight(dp(32));
        // Half the shear on each side keeps the label clear of the leaning edges.
        setChipStartPadding(dp(SLANT_DP / 2f + 4));
        setChipEndPadding(dp(SLANT_DP / 2f + 4));

        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        setTypeface(Type.body(context, Type.MEDIUM));
        int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}};
        super.setTextColor(new ColorStateList(states, new int[]{Studio.GROUND, Studio.INK_DIM}));
        super.setChipIconTint(new ColorStateList(states, new int[]{Studio.GROUND, Studio.INK_DIM}));
        ready = true;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (ready && getWidth() > 0 && getHeight() > 0) {
            Drawable face = isChecked() ? faceOn : isPressed() ? facePressed : faceOff;
            face.setBounds(0, 0, getWidth(), getHeight());
            face.draw(canvas);
        }
        super.draw(canvas);
    }

    @Override
    public void setChipBackgroundColor(@Nullable ColorStateList colour) {
        if (!ready) super.setChipBackgroundColor(colour);
    }

    @Override
    public void setTextColor(ColorStateList colors) {
        if (!ready) super.setTextColor(colors);
    }

    @Override
    public void setChipIconTint(@Nullable ColorStateList tint) {
        if (!ready) super.setChipIconTint(tint);
    }
}
