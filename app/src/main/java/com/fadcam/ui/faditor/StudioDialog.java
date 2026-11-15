package com.fadcam.ui.faditor;

import android.content.Context;
import android.content.res.ColorStateList;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.fadcam.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;

/**
 * THE ONE WAY STUDIO ASKS A QUESTION (STU-D4).
 *
 * <p>Every Studio dialog is built here, so it wears the Studio frame (panel, 1dp hairline,
 * 14dp corners, Archivo title, flat pill buttons; see {@code res/values/studio_dialog.xml})
 * instead of stock Material grey and purple. It returns a plain
 * {@link MaterialAlertDialogBuilder}, so a call site changes only its first word and
 * behaves exactly as before.</p>
 *
 * <ul>
 *   <li>{@link #builder} for ordinary questions: the positive action is the aqua pill.</li>
 *   <li>{@link #danger} when the positive action loses something (Delete, Remove, Forget):
 *       that pill is red instead.</li>
 *   <li>{@link #dangerNeutral} when the third (neutral) button is the one that loses
 *       something.</li>
 * </ul>
 */
public final class StudioDialog {

    private StudioDialog() { }

    private static final float RADIUS_DP = 14f;   // record .dialog --r-card
    private static final float RING_DP = 1f;      // record .dialog border

    @NonNull
    public static MaterialAlertDialogBuilder builder(@NonNull Context ctx) {
        return framed(ctx, R.style.ThemeOverlay_Studio_Dialog);
    }

    @NonNull
    public static MaterialAlertDialogBuilder danger(@NonNull Context ctx) {
        return framed(ctx, R.style.ThemeOverlay_Studio_Dialog_Danger);
    }

    @NonNull
    public static MaterialAlertDialogBuilder dangerNeutral(@NonNull Context ctx) {
        return framed(ctx, R.style.ThemeOverlay_Studio_Dialog_DangerNeutral);
    }

    @NonNull
    private static MaterialAlertDialogBuilder framed(@NonNull Context ctx, int overlay) {
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(ctx, overlay);
        b.setBackground(frame(ctx));
        return b;
    }

    /** The card: panel fill, a hairline ring, 14dp corners. */
    @NonNull
    public static MaterialShapeDrawable frame(@NonNull Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        MaterialShapeDrawable bg = new MaterialShapeDrawable(
                ShapeAppearanceModel.builder().setAllCornerSizes(RADIUS_DP * d).build());
        bg.setFillColor(ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.s_panel)));
        bg.setStroke(Math.max(1f, RING_DP * d), ContextCompat.getColor(ctx, R.color.s_line));
        return bg;
    }
}
