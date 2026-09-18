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

    /**
     * How many words a single caption may hold before it breaks to the next one.
     *
     * <p>This was a private constant of 6 inside {@link CaptionPhrases}, chosen so a spoken
     * caption stays readable. That is the right default and the wrong ceiling: JoyRaptor is putting
     * scripture references on screen and needs a whole verse to sit there long enough to pause
     * and read it, which six words cannot express. A cue longer than the box can hold is a
     * separate problem, solved by {@link #fitMode}.</p>
     */
    public int maxWords = 6;

    /** How a cue's size is chosen. Replaces the old {@code autoFit} boolean (see {@link #fromJson}). */
    public enum FitMode { OFF, UNIFORM, PER_CUE }

    /** Fit mode — OFF is ordinary speech (default), UNIFORM and PER_CUE shrink to fit. */
    @NonNull public FitMode fitMode = FitMode.OFF;

    /** Floor for fitting, as a fraction of the authored size. Never shrink past this. */
    public static final float AUTO_FIT_MIN_SCALE = 0.45f;

    /** Minimum size as fraction of authored size for the Fit tab (default 45%). */
    public float fitMinScale = AUTO_FIT_MIN_SCALE;

    /** Max lines, 0 = unlimited. When set, a cue must also fit within this many lines. */
    public int fitMaxLines = 0;

    /** SPEC_20260831_CAPTION_SLIDES_UX: true = fit shrinks to the box and may ellipsize at the floor;
        false = width-only fit, the column may run off screen. Applies when fitMode != OFF. */
    public boolean fitTruncate = true;

    /**
     * SLIDE grouping (SPEC_20260831_CAPTION_SLIDES): one TIMING ENTRY = one caption box shown
     * for the entry's full span, wrapped by word inside the box. For imports where a whole
     * scripture paragraph shares one timestamp this is the readable form; the default 6-word
     * karaoke grouping chops such a paragraph into a ticker. Off by default — speech captions
     * keep the normal grouping.
     */
    public boolean slideGroup = false;

    /**
     * Corner radius of the caption's backing plate (the "box"/pill), as a FRACTION OF THE FONT
     * SIZE — not px. Captions are authored once and then ride every line length and every
     * export resolution, so a px radius would read as a hard square on a 4K export and a
     * lozenge on a preview. 0 = square corners, 0.5 = fully rounded ends.
     *
     * <p>The default 0.35 is exactly the constant the renderers hard-coded before this field
     * existed, so every project written before it looks identical after it.</p>
     */
    public static final float PILL_CORNER_DEFAULT = 0.35f;
    public float pillCornerScale = PILL_CORNER_DEFAULT;

    /** Clamp for {@link #pillCornerScale} — the one place the legal range lives. */
    public static float clampPillCorner(float v) {
        if (Float.isNaN(v) || v < 0f) return 0f;
        return Math.min(0.5f, v);
    }

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
        c.maxWords = maxWords;
        c.slideGroup = slideGroup;
        c.fitMode = fitMode;
        c.fitMinScale = fitMinScale;
        c.fitMaxLines = fitMaxLines;
        c.fitTruncate = fitTruncate;
        c.outline = outline;
        c.outlineColor = outlineColor;
        c.shadow = shadow;
        c.pillCornerScale = pillCornerScale;
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
            // Via FontLibrary's cache, NEVER Typeface.createFromFile directly: this method is
            // called once per word per candidate size by the caption fitter, and a raw
            // createFromFile there re-parses the font file every time. That was the cause of
            // the editor's 10-second input-dispatch ANRs and its 2.1 GB native heap.
            Typeface f = com.fadcam.ui.faditor.text.FontLibrary.typefaceForFile(fontKey.substring(5));
            // Null means the font was deleted or is unreadable since it was chosen - fall
            // through to the built-ins rather than crashing a render.
            if (f != null) return bold ? Typeface.create(f, Typeface.BOLD) : f;
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
        // Imported fonts come from FontLibrary, which owns the one location they are written
        // to. This used to scan Pictures/FadCam/fonts directly - the folder the importer could
        // never write to on modern Android, so the list was always just the built-ins.
        java.util.List<String[]> all = new java.util.ArrayList<>(java.util.Arrays.asList(builtIn));
        for (String[] f : com.fadcam.ui.faditor.text.FontLibrary.imported()) {
            all.add(new String[]{f[0], f[1] + " *"});
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
            o.put("maxWords", maxWords);
            o.put("fitMode", fitMode.name());
            o.put("fitMinScale", (double) fitMinScale);
            o.put("fitMaxLines", fitMaxLines);
            o.put("fitTruncate", fitTruncate);
            o.put("slideGroup", slideGroup);
            // Keep old key for readers that still look for it (migrated on read).
            o.put("autoFit", fitMode != FitMode.OFF);
            o.put("pillCornerScale", (double) pillCornerScale);
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
                    o.optInt("base", 0xFFF4F4F5), o.optInt("active", 0xFFFFEB3B),
                    o.optBoolean("pill", false), o.optInt("pillColor", 0xCC000000),
                    o.optBoolean("bold", true), anim);
            s.fontKey = o.optString("font", "default");
            s.maxWords = Math.max(1, o.optInt("maxWords", 6));
            String fitModeStr = o.optString("fitMode", null);
            if (fitModeStr != null) {
                try { s.fitMode = FitMode.valueOf(fitModeStr); } catch (IllegalArgumentException e) { s.fitMode = FitMode.OFF; }
            } else if (o.has("autoFit")) {
                s.fitMode = o.optBoolean("autoFit", false) ? FitMode.UNIFORM : FitMode.OFF;
            } else {
                s.fitMode = FitMode.OFF;
            }
            s.fitMinScale = (float) o.optDouble("fitMinScale", AUTO_FIT_MIN_SCALE);
            if (s.fitMinScale < 0.15f) s.fitMinScale = 0.15f;
            if (s.fitMinScale > 1f) s.fitMinScale = 1f;
            s.fitMaxLines = o.optInt("fitMaxLines", 0);
            if (s.fitMaxLines < 0) s.fitMaxLines = 0;
            if (s.fitMaxLines > 20) s.fitMaxLines = 20;
            s.fitTruncate = o.optBoolean("fitTruncate", true);
            s.slideGroup = o.optBoolean("slideGroup", false);
            // Tolerant read: absent (every project written before this field) falls back to the
            // constant the renderers used to hard-code, so nothing shifts on load.
            s.pillCornerScale = clampPillCorner(
                    (float) o.optDouble("pillCornerScale", PILL_CORNER_DEFAULT));
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
                new CaptionStyle("pop", "Pop", 0xFFF4F4F5, 0xFFFFEB3B,
                        false, 0, true, Anim.POP),
                new CaptionStyle("zoom", "Zoom", 0xFFF4F4F5, 0xFF4DD0E1,
                        false, 0, true, Anim.ZOOM),
                new CaptionStyle("bounce", "Bounce", 0xFFF4F4F5, 0xFF35F6BF,
                        false, 0, true, Anim.BOUNCE),
                new CaptionStyle("boxed", "Boxed", 0xFFF4F4F5, 0xFFFFC107,
                        true, 0xCC000000, true, Anim.POP),
                new CaptionStyle("hot", "Hot", 0xFFF4F4F5, 0xFFFF5252,
                        true, 0x99000000, true, Anim.ZOOM),
                new CaptionStyle("meme", "Meme", 0xFFF4F4F5, 0xFFFFEB3B,
                        true, 0xCC000000, true, Anim.POP),
                new CaptionStyle("bright", "Bright", 0xFF4DD0E1, 0xFFFF4081,
                        false, 0, true, Anim.BOUNCE));
    }

    /** The special "hidden" pseudo-style (captions not rendered). */
    @NonNull
    public static CaptionStyle hidden() {
        return new CaptionStyle("hidden", "Hidden", 0xFF8A8A94, 0xFF8A8A94,
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
