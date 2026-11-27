package com.fadcam.ui.faditor.model;

import java.util.Locale;

/**
 * A "Solid colour" clip (Studio reuse item 6 of the Avatar build): one colour, any length, on the spine, made of the
 * same machinery as the Black clip (a generated still in the app's images folder), so preview and export draw it as
 * they draw any picture. Its first use is the key colour behind a character, which a video editor then removes.
 *
 * <p>Plain Java (the file's name and size); the picture itself is written by {@link SolidColourFile}.</p>
 */
public final class SolidColour {
    /** The usual key colours: broadcast green and blue. */
    public static final int KEY_GREEN = 0xFF00B140;
    public static final int KEY_BLUE = 0xFF0047BB;

    /** The long side of the still, in pixels. A flat colour scales up with no loss, so it stays small. */
    public static final int LONG_SIDE = 192;

    /** The clip's name on the timeline starts with this, then the colour ("Solid colour #00B140"). */
    public static final String NAME_PREFIX = "Solid colour ";

    private SolidColour() {}

    /** "#00B140": the colour without its alpha (a solid colour is always opaque). */
    public static String hex(int argb) {
        return String.format(Locale.US, "#%06X", argb & 0xFFFFFF);
    }

    public static int opaque(int argb) {
        return 0xFF000000 | (argb & 0xFFFFFF);
    }

    /**
     * The still's size for a frame of this shape (width / height), so the colour fills the frame edge to edge instead
     * of sitting in it with bars. An unknown shape (0 or less) gets 16:9.
     */
    public static int[] size(float aspect) {
        if (!(aspect > 0f) || Float.isInfinite(aspect)) aspect = 16f / 9f;
        aspect = Math.max(0.1f, Math.min(10f, aspect));
        if (aspect >= 1f) return new int[]{LONG_SIDE, Math.max(1, Math.round(LONG_SIDE / aspect))};
        return new int[]{Math.max(1, Math.round(LONG_SIDE * aspect)), LONG_SIDE};
    }

    /** One file per colour and size: written once, then shared by every clip of that colour. */
    public static String fileName(int argb, int w, int h) {
        return String.format(Locale.US, "solid_%06x_%dx%d.png", argb & 0xFFFFFF, w, h);
    }

    public static String displayName(int argb) {
        return NAME_PREFIX + hex(argb);
    }

    /** The colour a clip named by {@link #displayName} has, or null when the name is not a solid colour's. */
    public static Integer colourOf(String displayName) {
        if (displayName == null || !displayName.startsWith(NAME_PREFIX + "#")) return null;
        String h = displayName.substring(NAME_PREFIX.length() + 1);
        if (h.length() != 6) return null;
        try {
            return 0xFF000000 | Integer.parseInt(h, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
