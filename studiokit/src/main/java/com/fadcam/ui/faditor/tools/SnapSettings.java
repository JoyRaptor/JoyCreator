package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * EVERY SNAP IN THE STUDIO, IN ONE PLACE (JoyRaptor, 2026-09-24).
 *
 * <p>"A magnet which globally controls all snap throughout the studio. And long pressing on that
 * magnet gives you a dialog to, with more granularity, select what things you want to snap and
 * perhaps even different thresholds ... very important for moving fast. You'd want snapping on,
 * and when you want to do tricky things you turn it off, or just certain sections off."
 *
 * <p>Snapping used to be a handful of private booleans and constants, one per surface, so the
 * magnet could only reach two of them. Every surface now asks {@link #on} and {@link #reach}
 * here, and the magnet ({@link #setMaster}) and the panel ({@code SnapSettingsSheet}) write
 * here. Stored per phone, like the drawer see-through: it is how this person likes to work,
 * not a property of the project.
 */
public final class SnapSettings {

    /** One row of the snap panel. Only kinds a surface really reads are listed. */
    public enum Kind {
        /** Timeline: a moved or trimmed item's edges catch the playhead and other edges. */
        TIMELINE_EDGES("timeline_edges"),
        /** Timeline: edges catch detected beats. */
        BEATS("beats"),
        /** Preview: an object's centre and edges catch the canvas centre and edges. */
        CANVAS("canvas"),
        /** Preview: an object's centre and edges catch other objects' centres and edges. */
        OBJECTS("objects"),
        /** Preview: rotation catches whole angles (see {@link #rotationStepDeg}). */
        ROTATION("rotation");

        final String key;
        Kind(String key) { this.key = key; }
    }

    /** How far a snap reaches, as a multiple of each surface's own tuned distance. */
    public static final int GENTLE = 0, NORMAL = 1, STRONG = 2;

    private static final String PREFS = "studio_snap";
    private static final String KEY_MASTER = "master";
    private static final String KEY_ROT_STEP = "rotation_step_deg";
    /** The rotation steps the panel offers. */
    public static final int[] ROTATION_STEPS = {5, 15, 45, 90};

    private SnapSettings() {}

    private static SharedPreferences sp(@NonNull Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The magnet. Off = nothing snaps, whatever the rows say; the rows keep their settings. */
    public static boolean master(@NonNull Context ctx) {
        return sp(ctx).getBoolean(KEY_MASTER, true);
    }

    public static void setMaster(@NonNull Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_MASTER, on).apply();
    }

    /** The row's own switch, regardless of the magnet. */
    public static boolean kindOn(@NonNull Context ctx, @NonNull Kind k) {
        return sp(ctx).getBoolean(k.key + "_on", true);
    }

    public static void setKindOn(@NonNull Context ctx, @NonNull Kind k, boolean on) {
        sp(ctx).edit().putBoolean(k.key + "_on", on).apply();
    }

    /** What a surface asks: does this kind snap right now? */
    public static boolean on(@NonNull Context ctx, @NonNull Kind k) {
        return master(ctx) && kindOn(ctx, k);
    }

    public static int strength(@NonNull Context ctx, @NonNull Kind k) {
        int v = sp(ctx).getInt(k.key + "_strength", NORMAL);
        return Math.max(GENTLE, Math.min(STRONG, v));
    }

    public static void setStrength(@NonNull Context ctx, @NonNull Kind k, int s) {
        sp(ctx).edit().putInt(k.key + "_strength", Math.max(GENTLE, Math.min(STRONG, s))).apply();
    }

    /**
     * Multiply a surface's tuned snap distance by this: 0 when the kind is off, else 0.5 / 1 / 2
     * for gentle / normal / strong. One call answers both "does it snap" and "how far".
     */
    public static float reach(@NonNull Context ctx, @NonNull Kind k) {
        if (!on(ctx, k)) return 0f;
        switch (strength(ctx, k)) {
            case GENTLE: return 0.5f;
            case STRONG: return 2f;
            default:     return 1f;
        }
    }

    /**
     * Whether a kind's surface honours Gentle / Normal / Strong. Beats are on/off only today;
     * the panel shows strength chips only where they do something.
     */
    public static boolean hasStrength(@NonNull Kind k) {
        return k == Kind.TIMELINE_EDGES || k == Kind.ROTATION || k == Kind.CANVAS
                || k == Kind.OBJECTS;
    }

    /** The angle rotation snaps to multiples of. */
    public static int rotationStepDeg(@NonNull Context ctx) {
        int v = sp(ctx).getInt(KEY_ROT_STEP, 15);
        for (int s : ROTATION_STEPS) if (s == v) return v;
        return 15;
    }

    public static void setRotationStepDeg(@NonNull Context ctx, int deg) {
        sp(ctx).edit().putInt(KEY_ROT_STEP, deg).apply();
    }

    /**
     * Run {@code onChange} whenever any snap setting changes. The returned listener must be
     * kept by the caller for as long as it wants calls: SharedPreferences holds listeners
     * weakly, so a listener nobody keeps stops firing without a word.
     */
    @NonNull
    public static SharedPreferences.OnSharedPreferenceChangeListener listen(
            @NonNull Context ctx, @NonNull Runnable onChange) {
        SharedPreferences.OnSharedPreferenceChangeListener l = (p, key) -> onChange.run();
        sp(ctx).registerOnSharedPreferenceChangeListener(l);
        return l;
    }
}
