package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import androidx.annotation.NonNull;

import com.fadcam.studiokit.R;
import com.fadcam.ui.faditor.Studio;

/**
 * THE drawer fill, shared by the Studio and Joy Brush (D.02, R23: share, don't copy). Moved here out of
 * {@code ObjectDrawer.Kit}, which now delegates, so every see-through panel in Joy Creator reads the one slider.
 *
 * <p>JoyRaptor, 2026-09-23: a drawer over the picture is ALWAYS see-through. "The user must ALWAYS be able to see behind
 * the drawer or you have blinded them." Frost, when it returns, is the SAME fill plus a blur on phones that can render one
 * (API 31+), never a darker or opaque one. HOW see-through is the owner's call, per phone (2026-09-24): default 50%, the
 * Studio's Settings tool holds the slider, and every drawer repaints the moment it moves.
 */
public final class DrawerFill {

    private static final String PREFS = "studio_drawer";
    private static final String KEY_SEE_THROUGH = "see_through_pct";
    private static final String KEY_FROST = "frost";
    public static final int SEE_THROUGH_DEFAULT = 50;
    public static final int SEE_THROUGH_MIN = 20;
    public static final int SEE_THROUGH_MAX = 85;

    private DrawerFill() {}

    private static SharedPreferences prefs(@NonNull Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** How see-through drawers are, in percent (the Settings slider's value). */
    public static int seeThroughPct(@NonNull Context ctx) {
        int v = prefs(ctx).getInt(KEY_SEE_THROUGH, SEE_THROUGH_DEFAULT);
        return Math.max(SEE_THROUGH_MIN, Math.min(SEE_THROUGH_MAX, v));
    }

    public static void setSeeThroughPct(@NonNull Context ctx, int pct) {
        prefs(ctx).edit().putInt(KEY_SEE_THROUGH, Math.max(SEE_THROUGH_MIN, Math.min(SEE_THROUGH_MAX, pct))).apply();
    }

    /** The one see-through drawer fill (--scrim), at the chosen see-through. */
    public static int fill(@NonNull Context ctx) {
        return Studio.alpha(Studio.GROUND, Math.round((100 - seeThroughPct(ctx)) * 2.55f));
    }

    /** Frost: the picture behind a drawer is blurred under its see-through fill. */
    public static boolean frostOn(@NonNull Context ctx) {
        return prefs(ctx).getBoolean(KEY_FROST, false);
    }

    public static void setFrostOn(@NonNull Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_FROST, on).apply();
    }

    /** Repaint {@code v}'s fill now and whenever the slider moves; a no-op off a {@link GradientDrawable} fill. */
    public static void follow(@NonNull View v) {
        final Context ctx = v.getContext();
        final Runnable paint = () -> {
            Drawable bg = v.getBackground();
            if (bg instanceof GradientDrawable) ((GradientDrawable) bg.mutate()).setColor(fill(ctx));
        };
        paint.run();
        // Held on the view: SharedPreferences keeps listeners weakly.
        SharedPreferences.OnSharedPreferenceChangeListener l =
                (p, key) -> { if (KEY_SEE_THROUGH.equals(key)) v.post(paint); };
        v.setTag(R.id.studiokit_tag_drawer_fill, l);
        prefs(ctx).registerOnSharedPreferenceChangeListener(l);
    }
}
