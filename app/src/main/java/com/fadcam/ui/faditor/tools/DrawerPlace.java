package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * Where Studio's drawers open on a wide window (TAB-1 §K, the owner's 11-15 picks).
 *
 * <ul>
 *   <li><b>DROP</b>: the phone drawer, dropping from the top over the picture. Always on a phone.</li>
 *   <li><b>DESK</b> (Editing desk): a 360 dp panel beside the picture, the timeline full width under both.</li>
 *   <li><b>COLUMN</b> (Tall column): the panel runs the full height; the timeline sits under the picture only.</li>
 * </ul>
 *
 * <p>Automatic (the default) follows the free space, because the project's shape against the
 * window's shape decides it: a 16:9 piece on an upright tablet leaves room below, so drawers drop
 * down; a 9:16 piece, or anything on a tablet sideways, leaves room beside, so a side panel.
 * The owner: "the project is really going to determine what is necessary", so the drawer header
 * carries a one-touch switch and Settings keeps the choice.
 *
 * <p>Stored beside the drawer's see-through and Frost ({@code studio_drawer}, see DrawerFill).
 * {@link #choose} is pure, for the JVM harness.
 */
public final class DrawerPlace {

    private DrawerPlace() {}

    public enum Place { DROP, DESK, COLUMN }

    /** Side panels appear from this window width up (SizeClass expanded; TAB-1 pick S6). */
    public static final int SIDE_FROM_DP = 840;
    /** Under this a window is a phone held upright: always the phone drawer. */
    public static final int PHONE_UNDER_DP = 600;
    /** The panel is the phone drawer's own width (pick S2 A): rows exactly as drawn. */
    public static final int PANEL_DP = 360;

    private static final String PREFS = "studio_drawer";
    /** "auto" (default), "drop", "beside". */
    private static final String K_MODE = "place_mode";
    /** Which side layout "beside" means: false = Editing desk, true = Tall column. */
    private static final String K_COLUMN = "place_column";
    /** Panel on the left, for left hands (pick S1 C). */
    private static final String K_LEFT = "place_left";

    public static final String AUTO = "auto", DROP = "drop", BESIDE = "beside";

    private static SharedPreferences p(@NonNull Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull public static String mode(@NonNull Context c) { return p(c).getString(K_MODE, AUTO); }
    public static void setMode(@NonNull Context c, @NonNull String m) { p(c).edit().putString(K_MODE, m).apply(); }
    public static boolean column(@NonNull Context c) { return p(c).getBoolean(K_COLUMN, false); }
    public static void setColumn(@NonNull Context c, boolean v) { p(c).edit().putBoolean(K_COLUMN, v).apply(); }
    public static boolean left(@NonNull Context c) { return p(c).getBoolean(K_LEFT, false); }
    public static void setLeft(@NonNull Context c, boolean v) { p(c).edit().putBoolean(K_LEFT, v).apply(); }

    /**
     * Where the drawer goes.
     *
     * @param mode      {@link #AUTO}, {@link #DROP} or {@link #BESIDE}
     * @param column    the user's side layout: Tall column (true) or Editing desk (false)
     * @param windowDp  the window's width in dp
     * @param slackX    picture slot width minus the picture's width (room beside it), any unit
     * @param slackY    picture slot height minus the picture's height (room below it), same unit
     */
    @NonNull
    public static Place choose(@NonNull String mode, boolean column, int windowDp,
                               float slackX, float slackY) {
        Place side = column ? Place.COLUMN : Place.DESK;
        if (windowDp < PHONE_UNDER_DP || DROP.equals(mode)) return Place.DROP;
        if (BESIDE.equals(mode)) return side;
        // Automatic: only on wide windows, and only where the free room is beside the picture.
        if (windowDp < SIDE_FROM_DP) return Place.DROP;
        return slackX >= slackY ? side : Place.DROP;
    }

    /**
     * How far the picture slides away from a side panel (owner, 11-15): only as far as the panel
     * covers it and never past the picture's own room to the far wall. It never shrinks; past that
     * room the see-through panel overlaps the rest, as the transcript panel does.
     *
     * @param edge +1 panel on the right, -1 on the left, 0 none
     * @return the picture's horizontal shift: negative = left, positive = right
     */
    public static float panelShift(int edge, float containerW, float panelW,
                                   float picLeft, float picRight) {
        if (edge > 0) {
            float covered = Math.max(0f, picRight - (containerW - panelW));
            float slide = Math.min(covered, Math.max(0f, picLeft));
            return slide > 0f ? -slide : 0f;
        }
        if (edge < 0) {
            float covered = Math.max(0f, panelW - picLeft);
            return Math.min(covered, Math.max(0f, containerW - picRight));
        }
        return 0f;
    }

    /** True where the one-touch switch makes sense (a window wider than a phone held upright). */
    public static boolean switchable(int windowDp) { return windowDp >= PHONE_UNDER_DP; }
}
