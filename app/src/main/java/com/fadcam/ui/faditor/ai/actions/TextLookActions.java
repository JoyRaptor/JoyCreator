package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.text.FontLibrary;
import com.fadcam.ui.faditor.transcript.CaptionAnimator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The text look family (gap F2, first native family): the owner's "change the font on that slide".
 * Every control in the Studio's text drawer that has a plain value — font, colour, size, bold,
 * alignment, outline, shadow, glow, plate, opacity, the words, and the entrance animation.
 *
 * <p>Ranges match the drawer's own sliders (outline 25, glow 40, shadow 40 — percent of the letter
 * size; shadow distance 100 px), so Joybot can never set a value a person couldn't.</p>
 */
final class TextLookActions {
    private TextLookActions() { }

    private static final String FAMILY = "text_look";
    private static final String TEXT_HELP = "Which text: its id, the words on it, or \"all\"";

    static final List<String> ALIGN = Arrays.asList("LEFT", "CENTER", "RIGHT", "JUSTIFY");
    static final List<String> CASES = Arrays.asList("NONE", "CAPITALIZE_FIRST", "ALL_CAPS", "SMALL_CAPS");
    static final List<String> GRAIN = Arrays.asList("LETTER", "WORD", "SENTENCE", "BLOCK");

    static void register(@NonNull ActionRegistry<StudioCtx> r) {
        r.add(ActionSpec.of("list_fonts", "List the fonts")
                .help("The fonts a text can use: the built-in ones and any the person imported. Call before"
                        + " set_text_style when they name a font you don't see here.")
                .wing("STUDIO", FAMILY).effect(Effect.READ)
                .where("Studio › tap a text › Style › Font")
                .build(), TextLookActions::listFonts);

        r.add(ActionSpec.of("set_text_style", "Change how a text looks")
                .help("Change a text layer's look; send only what should change. Colours #RRGGBB, or"
                        + " #00000000 to remove one (e.g. the plate). Outline/glow/shadow sizes are percent"
                        + " of the letter size; 0 removes them.")
                .wing("STUDIO", FAMILY)
                .where("Studio › tap a text › Style")
                .id("text", "text", true, TEXT_HELP)
                .text("font", false, "Font name or key from list_fonts (e.g. \"Bold Serif\", \"Mono\")")
                .color("color", false, "Letter colour")
                .number("size", false, "Letter size as a fraction of the frame height (0.1 is typical)", 0.02, 0.6)
                .flag("bold", false, "Bold")
                .flag("italic", false, "Italic")
                .flag("underline", false, "Underline")
                .choice("align", false, "Line alignment", ALIGN)
                .choice("case", false, "Letter case", CASES)
                .color("outlineColor", false, "Outline colour")
                .number("outlineSize", false, "Outline thickness", 0.0, 25.0)
                .color("shadowColor", false, "Shadow colour")
                .number("shadowBlur", false, "Shadow softness", 0.0, 40.0)
                .number("shadowAngle", false, "Shadow direction in degrees", 0.0, 360.0)
                .number("shadowDistance", false, "How far the shadow falls, in pixels", 0.0, 100.0)
                .color("glowColor", false, "Glow colour")
                .number("glowSize", false, "Glow size", 0.0, 40.0)
                .color("plateColor", false, "Background plate behind the words")
                .number("opacity", false, "0 invisible … 1 solid", 0.0, 1.0)
                .build(), TextLookActions::setStyle);

        r.add(ActionSpec.of("set_text_words", "Change what a text says")
                .help("Replace the words on a text layer.")
                .wing("STUDIO", FAMILY)
                .where("Studio › double-tap a text")
                .id("text", "text", true, TEXT_HELP)
                .text("words", true, "The new words (\\n for a new line)")
                .build(), TextLookActions::setWords);

        List<String> presets = new ArrayList<>();
        for (CaptionAnimator.Preset p : CaptionAnimator.Preset.values()) presets.add(p.name());
        r.add(ActionSpec.of("set_text_animation", "Animate a text")
                .help("How a text arrives and leaves: TYPEWRITER, FADE, RISE, GHOST, BEAM, MATRIX, UNSCRAMBLE,"
                        + " MASK_WIPE, ODOMETER, NEON_FLICKER, or NONE to stop it. inPercent/outPercent are"
                        + " how much of the text's time the arrival/leaving takes.")
                .wing("STUDIO", FAMILY)
                .where("Studio › tap a text › Animate")
                .id("text", "text", true, TEXT_HELP)
                .choice("preset", true, "The animation", presets)
                .choice("by", false, "Animate by letter, word, sentence or the whole block", GRAIN)
                .number("inPercent", false, "Arrival, percent of the text's time", 0.0, 50.0)
                .number("outPercent", false, "Leaving, percent of the text's time", 0.0, 50.0)
                .build(), TextLookActions::setAnimation);
    }

    // ── handlers ─────────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult listFonts(@NonNull StudioCtx ctx, @NonNull Args a) {
        StringBuilder sb = new StringBuilder("Built-in fonts: ");
        for (int i = 0; i < FontLibrary.TEXT_FONTS.length; i++) {
            sb.append(i > 0 ? ", " : "").append(FontLibrary.TEXT_FONTS[i][1]);
        }
        List<String[]> mine = FontLibrary.imported();
        sb.append(mine.isEmpty() ? ". No imported fonts." : ". Imported: ");
        for (int i = 0; i < mine.size(); i++) sb.append(i > 0 ? ", " : "").append(mine.get(i)[1]);
        return ActionResult.unchanged(sb.toString());
    }

    @NonNull
    private static ActionResult setStyle(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        List<TextOverlayItem> ts = ctx.texts(a.str("text"));
        String font = null, fontLabel = null;
        if (a.has("font")) {
            String[] f = font(a.str("font"));
            font = f[0];
            fontLabel = f[1];
        }
        List<String> said = new ArrayList<>();
        for (TextOverlayItem t : ts) {
            if (font != null) t.setFontFamily(font);
            Integer c;
            if ((c = a.color("color")) != null) t.setColorInt(c);
            if (a.has("size")) t.setSizeFraction(a.num("size").floatValue());
            if (a.has("bold")) t.setBold(a.bool("bold"));
            if (a.has("italic")) t.setItalic(a.bool("italic"));
            if (a.has("underline")) t.setUnderline(a.bool("underline"));
            if (a.has("align")) t.setTextAlign(a.choice("align", ALIGN));
            if (a.has("case")) t.setTextCase(a.choice("case", CASES));
            if ((c = a.color("outlineColor")) != null) t.setStrokeColorInt(c);
            if (a.has("outlineSize")) t.setStrokeWidthPx(a.num("outlineSize").floatValue());
            if ((c = a.color("shadowColor")) != null) t.setShadowColorInt(c);
            if (a.has("shadowBlur")) t.setShadowRadiusPx(a.num("shadowBlur").floatValue());
            if (a.has("shadowAngle")) t.setShadowAngleDeg(a.num("shadowAngle").floatValue());
            if (a.has("shadowDistance")) t.setShadowDistancePx(a.num("shadowDistance").floatValue());
            if ((c = a.color("glowColor")) != null) t.setGlowColorInt(c);
            if (a.has("glowSize")) t.setGlowRadiusPx(a.num("glowSize").floatValue());
            if ((c = a.color("plateColor")) != null) t.setBackgroundColorInt(c);
            if (a.has("opacity")) t.setOpacity(a.num("opacity").floatValue());
        }
        // A colour with an outline/glow/shadow size of 0 draws nothing: say so rather than
        // report a change the person won't see.
        TextOverlayItem first = ts.get(0);
        if (a.has("outlineColor") && !a.has("outlineSize") && first.getStrokeWidthPx() <= 0f) {
            for (TextOverlayItem t : ts) t.setStrokeWidthPx(8f);
            said.add("outline turned on");
        }
        if (a.has("glowColor") && !a.has("glowSize") && first.getGlowRadiusPx() <= 0f) {
            for (TextOverlayItem t : ts) t.setGlowRadiusPx(15f);
            said.add("glow turned on");
        }
        if (a.has("shadowColor") && !a.has("shadowBlur") && first.getShadowRadiusPx() <= 0f) {
            for (TextOverlayItem t : ts) t.setShadowRadiusPx(10f);
            said.add("shadow turned on");
        }
        if (fontLabel != null) said.add(0, "font " + fontLabel);
        int n = a.raw().size() - 1;
        String what = ts.size() == 1 ? "\"" + shortWords(first) + "\"" : ts.size() + " texts";
        String sum = "Changed the look of " + what
                + (said.isEmpty() ? "" : " (" + join(said) + ")") + ".";
        return ActionResult.changed(sum, ids(ts)).withDetail(n + " setting(s) applied.");
    }

    @NonNull
    private static ActionResult setWords(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        List<TextOverlayItem> ts = ctx.texts(a.str("text"));
        String w = a.str("words").replace("\\n", "\n");
        for (TextOverlayItem t : ts) t.setText(w);
        return ActionResult.changed("The text now says \"" + shorten(w) + "\".", ids(ts));
    }

    @NonNull
    private static ActionResult setAnimation(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        List<TextOverlayItem> ts = ctx.texts(a.str("text"));
        String preset = a.str("preset").toUpperCase(Locale.ROOT);
        boolean none = "NONE".equals(preset);
        for (TextOverlayItem t : ts) {
            t.setTextAnimPreset(preset);
            if (a.has("by")) t.setTextAnimGranularity(a.choice("by", GRAIN));
            float in = a.has("inPercent") ? a.num("inPercent").floatValue() / 100f : t.getTextAnimInPct();
            float out = a.has("outPercent") ? a.num("outPercent").floatValue() / 100f : t.getTextAnimOutPct();
            // Same rule as the editor's picker: a preset with no time to play in would show
            // nothing, so it gets half the allowed arrival — the drawer's own default.
            if (!none && in <= 0f && out <= 0f) in = CaptionAnimator.MAX_ZONE_PCT / 2f;
            t.setTextAnimZonePct(in, out);
        }
        String label = none ? "no animation" : CaptionAnimator.presetLabel(CaptionAnimator.Preset.valueOf(preset));
        return ActionResult.changed((ts.size() == 1 ? "\"" + shortWords(ts.get(0)) + "\"" : ts.size() + " texts")
                + " now " + (none ? "has no animation." : "animates with " + label + "."), ids(ts));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** {key, label} for a font named by key, label or imported file name. */
    @NonNull
    static String[] font(@NonNull String name) throws ActionError {
        String n = name.trim();
        List<String[]> all = new ArrayList<>(Arrays.asList(FontLibrary.TEXT_FONTS));
        all.addAll(FontLibrary.imported());
        for (String[] f : all) if (f[0].equals(n)) return f;
        for (String[] f : all) if (f[1].equalsIgnoreCase(n)) return f;
        String want = n.toLowerCase(Locale.ROOT).replace('_', ' ');
        for (String[] f : all) {
            if (f[1].toLowerCase(Locale.ROOT).contains(want) || f[0].replace('_', ' ').equals(want)) return f;
        }
        StringBuilder sb = new StringBuilder();
        for (String[] f : all) sb.append(sb.length() > 0 ? ", " : "").append(f[1]);
        throw new ActionError("There's no font called \"" + n + "\". Fonts: " + sb + ".");
    }

    @NonNull
    private static List<String> ids(@NonNull List<TextOverlayItem> ts) {
        List<String> out = new ArrayList<>();
        for (TextOverlayItem t : ts) out.add(t.getId());
        return out;
    }

    @NonNull
    private static String shortWords(@NonNull TextOverlayItem t) {
        return shorten(t.getText() == null ? "" : t.getText());
    }

    @NonNull
    private static String shorten(@Nullable String s) {
        String w = s == null ? "" : s.replace('\n', ' ').trim();
        return w.length() > 30 ? w.substring(0, 30) + "…" : w;
    }

    @NonNull
    private static String join(@NonNull List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() > 0 ? ", " : "").append(s);
        return sb.toString();
    }
}
