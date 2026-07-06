package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.List;

/**
 * A visual preset for animated on-screen captions (TikTok-style). Defines the
 * colours, optional background pill, and how the currently-spoken word animates.
 */
public class CaptionStyle {

    public enum Anim {
        /** Springy scale overshoot then settle. */
        POP,
        /** Smooth zoom up and hold. */
        ZOOM,
        /** Quick vertical bounce. */
        BOUNCE
    }

    @NonNull public final String id;
    @NonNull public final String label;
    public final int baseColor;     // words not currently spoken
    public final int activeColor;   // the word being spoken
    public final boolean pill;      // draw a rounded background behind the phrase
    public final int pillColor;
    public final boolean bold;
    @NonNull public final Anim anim;

    public CaptionStyle(@NonNull String id, @NonNull String label, int baseColor,
                        int activeColor, boolean pill, int pillColor, boolean bold,
                        @NonNull Anim anim) {
        this.id = id;
        this.label = label;
        this.baseColor = baseColor;
        this.activeColor = activeColor;
        this.pill = pill;
        this.pillColor = pillColor;
        this.bold = bold;
        this.anim = anim;
    }

    /** The built-in visual style presets shown in the picker. */
    @NonNull
    public static List<CaptionStyle> presets() {
        return Arrays.asList(
                new CaptionStyle("pop", "Pop", 0xFFFFFFFF, 0xFFFFEB3B,
                        false, 0, true, Anim.POP),
                new CaptionStyle("zoom", "Zoom", 0xFFFFFFFF, 0xFF4DD0E1,
                        false, 0, true, Anim.ZOOM),
                new CaptionStyle("bounce", "Bounce", 0xFFFFFFFF, 0xFF69F0AE,
                        false, 0, true, Anim.BOUNCE),
                new CaptionStyle("boxed", "Boxed", 0xFFFFFFFF, 0xFFFFC107,
                        true, 0xCC000000, true, Anim.POP),
                new CaptionStyle("hot", "Hot", 0xFFFFFFFF, 0xFFFF5252,
                        true, 0x99000000, true, Anim.ZOOM),
                new CaptionStyle("meme", "Meme", 0xFFFFFFFF, 0xFFFFEB3B,
                        true, 0xCC000000, true, Anim.POP),
                new CaptionStyle("bright", "Bright", 0xFF4DD0E1, 0xFFFF4081,
                        false, 0, true, Anim.BOUNCE));
    }

    /** The special "hidden" pseudo-style (captions not rendered). */
    @NonNull
    public static CaptionStyle hidden() {
        return new CaptionStyle("hidden", "Hidden", 0xFF888888, 0xFF888888,
                false, 0, false, Anim.POP);
    }

    @NonNull
    public static CaptionStyle byId(@NonNull String id) {
        if ("hidden".equals(id)) return hidden();
        for (CaptionStyle s : presets()) {
            if (s.id.equals(id)) return s;
        }
        return presets().get(0);
    }
}
