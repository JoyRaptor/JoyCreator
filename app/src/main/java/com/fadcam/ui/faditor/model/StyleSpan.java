package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

/**
 * One run of per-character formatting over a {@link TextOverlayItem}'s AUTHORED text (§3.8 rich
 * text — W5-2).
 *
 * <p>Every property is an <b>optional override</b>: {@code null} means "inherit whatever the
 * base style or an earlier span decided", non-null means "this span owns this property for its
 * range". Resolution is <b>last-span-wins per property</b> — see {@link TextStyleResolver}, the
 * single authority that turns spans into per-glyph styles.</p>
 *
 * <p>Indices are char offsets into the item's authored {@code text} <i>as the user edited it</i> —
 * not into the case-transformed string the renderers draw. {@link TextStyleResolver#applyCaseToRange}
 * is length-preserving, so a span's offsets survive any per-span case transform. When the user
 * edits the text, {@link TextStyleResolver#adjustForEdit} keeps the spans aligned (an edit inside
 * a span extends the span over the new characters — the convention rich-text editors call
 * "insert inside span extends it").</p>
 *
 * <p>Pure-Java (no android.graphics imports — colours are plain ARGB ints) so the resolution
 * arithmetic is pinned by the JVM harness in {@code tools/jvm-harness/StyleSpanTest.java}.</p>
 */
public final class StyleSpan {

    /** Inclusive first char index into the authored text. */
    public int start;

    /** Exclusive end char index. */
    public int end;

    /** Font-family key, or null to inherit. Values are the {@code TextOverlayItem} font keys. */
    @Nullable public String fontFamily;

    /** Bold override, or null to inherit. */
    @Nullable public Boolean bold;

    /** Italic override, or null to inherit. */
    @Nullable public Boolean italic;

    /** Underline override, or null to inherit. */
    @Nullable public Boolean underline;

    /**
     * Case-transform override, or null to inherit. Values are the
     * {@code TextStyleResolver.CASE_*} constants.
     */
    @Nullable public String textCase;

    /** Text/ink colour override (ARGB), or null to inherit. */
    @Nullable public Integer fillColor;

    /** Outline/stroke colour override (ARGB), or null to inherit. */
    @Nullable public Integer strokeColor;

    /** Glow colour override (ARGB), or null to inherit. */
    @Nullable public Integer glowColor;

    /** Shadow colour override (ARGB), or null to inherit. */
    @Nullable public Integer shadowColor;

    /** Per-range background/highlight colour override (ARGB), or null to inherit. */
    @Nullable public Integer backgroundColor;

    public StyleSpan() { }

    public StyleSpan(int start, int end) {
        setRange(start, end);
    }

    /** Clamp to a valid range. Negative start clamps to 0, end below start clamps up to start. */
    public void setRange(int s, int e) {
        this.start = Math.max(0, s);
        this.end = Math.max(this.start, e);
    }

    /** True when every property is unset — a span that styles nothing and can be dropped. */
    public boolean isNoop() {
        return fontFamily == null && bold == null && italic == null && underline == null
                && textCase == null && fillColor == null && strokeColor == null
                && glowColor == null && shadowColor == null && backgroundColor == null;
    }

    /** How many properties this span actually overrides. */
    public int propertyCount() {
        int n = 0;
        if (fontFamily != null) n++;
        if (bold != null) n++;
        if (italic != null) n++;
        if (underline != null) n++;
        if (textCase != null) n++;
        if (fillColor != null) n++;
        if (strokeColor != null) n++;
        if (glowColor != null) n++;
        if (shadowColor != null) n++;
        if (backgroundColor != null) n++;
        return n;
    }

    /** Deep copy — new instance, same range and overrides. */
    @NonNull
    public StyleSpan copy() {
        StyleSpan c = new StyleSpan(start, end);
        c.fontFamily = fontFamily;
        c.bold = bold;
        c.italic = italic;
        c.underline = underline;
        c.textCase = textCase;
        c.fillColor = fillColor;
        c.strokeColor = strokeColor;
        c.glowColor = glowColor;
        c.shadowColor = shadowColor;
        c.backgroundColor = backgroundColor;
        return c;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof StyleSpan)) return false;
        StyleSpan s = (StyleSpan) o;
        return start == s.start && end == s.end
                && java.util.Objects.equals(fontFamily, s.fontFamily)
                && java.util.Objects.equals(bold, s.bold)
                && java.util.Objects.equals(italic, s.italic)
                && java.util.Objects.equals(underline, s.underline)
                && java.util.Objects.equals(textCase, s.textCase)
                && java.util.Objects.equals(fillColor, s.fillColor)
                && java.util.Objects.equals(strokeColor, s.strokeColor)
                && java.util.Objects.equals(glowColor, s.glowColor)
                && java.util.Objects.equals(shadowColor, s.shadowColor)
                && java.util.Objects.equals(backgroundColor, s.backgroundColor);
    }

    @Override
    public int hashCode() {
        int h = start * 31 + end;
        h = h * 31 + java.util.Objects.hashCode(fontFamily);
        h = h * 31 + java.util.Objects.hashCode(bold);
        h = h * 31 + java.util.Objects.hashCode(italic);
        h = h * 31 + java.util.Objects.hashCode(underline);
        h = h * 31 + java.util.Objects.hashCode(textCase);
        h = h * 31 + java.util.Objects.hashCode(fillColor);
        h = h * 31 + java.util.Objects.hashCode(strokeColor);
        h = h * 31 + java.util.Objects.hashCode(glowColor);
        h = h * 31 + java.util.Objects.hashCode(shadowColor);
        h = h * 31 + java.util.Objects.hashCode(backgroundColor);
        return h;
    }

    // ── Serialization ──────────────────────────────────────────────────────────────────────────
    // Compact keys, written SPARSELY (only the overrides this span carries) and only when the
    // whole span list is non-empty, so a project that never uses rich text round-trips
    // byte-identically. `s`/`e` mandatory; `f` font, `b`/`i`/`u` toggles, `c` case,
    // `tc` text colour, `oc` outline colour, `gc` glow colour, `hc` shadow colour, `bg` background.

    public static void toJson(@NonNull StyleSpan sp, @NonNull JsonObject o) {
        o.addProperty("s", sp.start);
        o.addProperty("e", sp.end);
        if (sp.fontFamily != null) o.addProperty("f", sp.fontFamily);
        if (sp.bold != null) o.addProperty("b", sp.bold);
        if (sp.italic != null) o.addProperty("i", sp.italic);
        if (sp.underline != null) o.addProperty("u", sp.underline);
        if (sp.textCase != null) o.addProperty("c", sp.textCase);
        if (sp.fillColor != null) o.addProperty("tc", sp.fillColor);
        if (sp.strokeColor != null) o.addProperty("oc", sp.strokeColor);
        if (sp.glowColor != null) o.addProperty("gc", sp.glowColor);
        if (sp.shadowColor != null) o.addProperty("hc", sp.shadowColor);
        if (sp.backgroundColor != null) o.addProperty("bg", sp.backgroundColor);
    }

    /** Tolerant read: malformed/absent keys mean "not set"; out-of-range indices survive to
     * {@link TextStyleResolver#normalize}. */
    @NonNull
    public static StyleSpan fromJson(@NonNull JsonObject o) {
        StyleSpan sp = new StyleSpan(
                o.has("s") ? o.get("s").getAsInt() : 0,
                o.has("e") ? o.get("e").getAsInt() : 0);
        if (o.has("f") && !o.get("f").isJsonNull()) sp.fontFamily = o.get("f").getAsString();
        if (o.has("b") && !o.get("b").isJsonNull()) sp.bold = o.get("b").getAsBoolean();
        if (o.has("i") && !o.get("i").isJsonNull()) sp.italic = o.get("i").getAsBoolean();
        if (o.has("u") && !o.get("u").isJsonNull()) sp.underline = o.get("u").getAsBoolean();
        if (o.has("c") && !o.get("c").isJsonNull()) sp.textCase = o.get("c").getAsString();
        if (o.has("tc") && !o.get("tc").isJsonNull()) sp.fillColor = o.get("tc").getAsInt();
        if (o.has("oc") && !o.get("oc").isJsonNull()) sp.strokeColor = o.get("oc").getAsInt();
        if (o.has("gc") && !o.get("gc").isJsonNull()) sp.glowColor = o.get("gc").getAsInt();
        if (o.has("hc") && !o.get("hc").isJsonNull()) sp.shadowColor = o.get("hc").getAsInt();
        if (o.has("bg") && !o.get("bg").isJsonNull()) sp.backgroundColor = o.get("bg").getAsInt();
        return sp;
    }
}