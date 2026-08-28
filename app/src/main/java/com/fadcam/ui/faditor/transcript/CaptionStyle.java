package com.fadcam.ui.faditor.transcript;

import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * A visual preset for animated on-screen captions (TikTok-style). Defines the
 * colours, optional background pill, font, outline/shadow, and how the
 * currently-spoken word animates.
 *
 * <p>Beyond the built-in {@link #presets()}, users can save their own styles
 * (JoyRaptor 2026-07-16 captions overhaul) — those live in {@link CaptionStyleStore}
 * and resolve through the same {@link #byId} lookup.</p>
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
    @NonNull public String label;
    public int baseColor;     // words not currently spoken
    public int activeColor;   // the word being spoken
    public boolean pill;      // draw a rounded background behind the phrase
    public int pillColor;
    public boolean bold;
    @NonNull public Anim anim;
    /** Font key — same vocabulary as text overlays ("default", "serif", "mono", …). */
    @NonNull public String fontKey = "default";
    /** Stroke outline around every word. */
    public boolean outline = false;
    public int outlineColor = 0xFF000000;
    /** Soft drop shadow (the classic caption look — on for all built-ins). */
    public boolean shadow = true;

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

    /** Deep copy under a different id (for cloning presets into custom styles). */
    @NonNull
    public CaptionStyle copyAs(@NonNull String newId, @NonNull String newLabel) {
        CaptionStyle c = new CaptionStyle(newId, newLabel, baseColor, activeColor,
                pill, pillColor, bold, anim);
        c.fontKey = fontKey;
        c.outline = outline;
        c.outlineColor = outlineColor;
        c.shadow = shadow;
        return c;
    }

    /** True for user-saved (or working-draft) styles, false for built-ins. */
    public boolean isCustom() {
        return id.startsWith("custom");
    }

    /**
     * The typeface this style renders with. Mirrors the text-overlay font
     * vocabulary so the caption font selector can reuse the same keys.
     */
    @NonNull
    public Typeface typeface() {
        Typeface base;
        // CUSTOM FONTS REACH CAPTIONS TOO. A font imported into Pictures/FadCam/fonts is
        // stored as the key "file:<abs path>", which TextOverlayItem already resolves - so a
        // downloaded font worked on text and was silently swallowed here, falling through the
        // switch to plain sans. JoyRaptor asked for both: "CC and text of course."
        if (fontKey != null && fontKey.startsWith("file:")) {
            try {
                Typeface f = Typeface.createFromFile(fontKey.substring(5));
                if (f != null) return bold ? Typeface.create(f, Typeface.BOLD) : f;
            } catch (Exception ignored) {
                // Font deleted or unreadable since it was chosen — fall through to the
                // built-ins rather than crashing a render.
            }
        }
        switch (fontKey) {
            case "serif":   base = Typeface.SERIF; break;
            case "mono":    base = Typeface.MONOSPACE; break;
            case "condensed": base = Typeface.create("sans-serif-condensed", Typeface.NORMAL); break;
            case "rounded": base = Typeface.create("sans-serif-medium", Typeface.NORMAL); break;
            case "light":   base = Typeface.create("sans-serif-light", Typeface.NORMAL); break;
            case "default":
            default:        base = Typeface.SANS_SERIF; break;
        }
        return bold ? Typeface.create(base, Typeface.BOLD) : base;
    }

    /** Font keys offered by the caption font selector, with display labels. */
    @NonNull
    public static String[][] fontChoices() {
        String[][] builtIn = builtInFontChoices();
        // Offer whatever the user has imported, marked the same way the text picker marks them.
        java.util.List<String[]> all = new java.util.ArrayList<>(java.util.Arrays.asList(builtIn));
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_PICTURES), "FadCam/fonts");
            java.io.File[] files = dir.listFiles((d, name) -> {
                String n = name.toLowerCase(java.util.Locale.US);
                return n.endsWith(".ttf") || n.endsWith(".otf");
            });
            if (files != null) {
                for (java.io.File f : files) {
                    all.add(new String[]{"file:" + f.getAbsolutePath(),
                            f.getName().replaceFirst("\\.[^.]+$", "") + " ★"});
                }
            }
        } catch (Exception ignored) {
            // No storage permission or no folder yet — the built-ins are a complete list.
        }
        return all.toArray(new String[0][]);
    }

    @NonNull
    private static String[][] builtInFontChoices() {
        return new String[][]{
                {"default", "Standard"},
                {"serif", "Serif"},
                {"mono", "Mono"},
                {"condensed", "Condensed"},
                {"rounded", "Rounded"},
                {"light", "Light"},
        };
    }

    // ── JSON (custom-style store + export/import as text) ──────────────

    @NonNull
    public org.json.JSONObject toJson() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("v", 1);
            o.put("id", id);
            o.put("label", label);
            o.put("base", baseColor);
            o.put("active", activeColor);
            o.put("pill", pill);
            o.put("pillColor", pillColor);
            o.put("bold", bold);
            o.put("anim", anim.name());
            o.put("font", fontKey);
            o.put("outline", outline);
            o.put("outlineColor", outlineColor);
            o.put("shadow", shadow);
        } catch (org.json.JSONException ignored) { }
        return o;
    }

    @Nullable
    public static CaptionStyle fromJson(@NonNull org.json.JSONObject o) {
        try {
            String id = o.optString("id", "");
            String label = o.optString("label", "Custom");
            if (id.isEmpty()) return null;
            Anim anim;
            try {
                anim = Anim.valueOf(o.optString("anim", "POP"));
            } catch (IllegalArgumentException e) {
                anim = Anim.POP;
            }
            CaptionStyle s = new CaptionStyle(id, label,
                    o.optInt("base", 0xFFFFFFFF), o.optInt("active", 0xFFFFEB3B),
                    o.optBoolean("pill", false), o.optInt("pillColor", 0xCC000000),
                    o.optBoolean("bold", true), anim);
            s.fontKey = o.optString("font", "default");
            s.outline = o.optBoolean("outline", false);
            s.outlineColor = o.optInt("outlineColor", 0xFF000000);
            s.shadow = o.optBoolean("shadow", true);
            return s;
        } catch (Exception e) {
            return null;
        }
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

    /**
     * Resolve a style id: built-ins first, then the user's saved custom styles
     * (when {@link CaptionStyleStore} has been initialised in this process),
     * else the first preset.
     */
    @NonNull
    public static CaptionStyle byId(@NonNull String id) {
        if ("hidden".equals(id)) return hidden();
        for (CaptionStyle s : presets()) {
            if (s.id.equals(id)) return s;
        }
        CaptionStyle custom = CaptionStyleStore.find(id);
        if (custom != null) return custom;
        return presets().get(0);
    }
}
