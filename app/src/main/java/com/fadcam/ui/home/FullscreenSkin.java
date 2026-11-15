package com.fadcam.ui.home;

import android.content.res.ColorStateList;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;
import com.google.android.material.button.MaterialButton;

/**
 * The fullscreen camera preview's button faces (SHELL-08), in the house language the Home
 * recorder already speaks ({@link HomeRecSkin}): zinc when off, solid cyan with the glyph in black
 * when on (never an outline), the Recording red for Start, and colour only where it guides. The
 * legacy screen painted its own greens, oranges and ambers in about a dozen places; each now asks
 * this class instead.
 */
public final class FullscreenSkin {

    private FullscreenSkin() { }

    /** A label under a button that is off. */
    public static final int LABEL_OFF = Studio.INK_DIM;
    /** A tile glyph that is off. */
    public static final int GLYPH_OFF = Studio.INK;

    /** Off: zinc, white glyph, no outline. */
    public static void off(@Nullable MaterialButton b, @Nullable TextView label) {
        face(b, Studio.RAISED, Studio.INK);
        if (label != null) label.setTextColor(LABEL_OFF);
    }

    /** On: solid cyan, the glyph punched out in black. */
    public static void on(@Nullable MaterialButton b, @Nullable TextView label) {
        face(b, Studio.ARMED, Studio.ON_GO);
        if (label != null) label.setTextColor(Studio.ARMED);
    }

    /** Start recording: the Recording room's red, the one primary action here. */
    public static void start(@Nullable MaterialButton b, @Nullable TextView label) {
        face(b, Studio.ROOM_CAPTURE, Studio.ON_GO);
        if (label != null) label.setTextColor(Studio.INK);
    }

    /** Paused: an ordinary button whose word says so in the warning colour. */
    public static void paused(@Nullable MaterialButton b, @Nullable TextView label) {
        face(b, Studio.RAISED, Studio.INK);
        if (label != null) label.setTextColor(Studio.CAREFUL);
    }

    /** A camera tile (exposure, zoom) that is changed from its default reads cyan; otherwise plain. */
    public static void tile(@Nullable TextView glyph, @Nullable TextView label, boolean changed) {
        if (glyph != null) glyph.setTextColor(changed ? Studio.ARMED : GLYPH_OFF);
        if (label != null) label.setTextColor(changed ? Studio.ARMED : LABEL_OFF);
    }

    /** The tap-to-focus ring: cyan, the colour of "this is where it is aimed". */
    public static final int FOCUS_RING = Studio.ARMED;
    public static final int FOCUS_FILL = 0x3322D3EE;

    private static void face(@Nullable MaterialButton b, int fill, int glyph) {
        if (b == null) return;
        b.setBackgroundTintList(ColorStateList.valueOf(fill));
        b.setIconTint(ColorStateList.valueOf(glyph));
        b.setStrokeWidth(0);
        b.setTextColor(glyph);
    }
}
