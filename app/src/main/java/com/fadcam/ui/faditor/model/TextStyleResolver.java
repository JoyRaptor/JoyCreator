package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * THE span-resolution authority for {@link TextOverlayItem} rich text (§3.8 — W5-2), and the
 * only place that owns the rules:
 *
 * <ul>
 *   <li><b>Last-span-wins per property.</b> A character's effective style starts at the item's
 *       base style and every span covering it applies its non-null overrides, in list order —
 *       so the LAST span in the list covering the character wins each property. Unspanned
 *       characters fall back to the base style exactly.</li>
 *   <li><b>Indices are into the AUTHORED text.</b> {@link #applyCaseToRange} is length-preserving
 *       (per-char transforms with no locale expansion), which is what lets a span's offsets
 *       survive case transforms and stay meaningful to the renderers' per-glyph layout.</li>
 *   <li><b>Setting a property on a range overwrites the whole range</b>
 *       ({@link #apply}): the new span is appended so it wins, and earlier spans that set ONLY
 *       that property and sit fully inside the range are pruned (dead weight).</li>
 *   <li><b>Text edits realign spans</b> ({@link #adjustForEdit}): typed text inside a span
 *       extends the span, deletion shrinks it, a span fully deleted disappears.</li>
 * </ul>
 *
 * <p>Pure-Java (no android.graphics — colours are plain ARGB ints) so every rule above is pinned
 * by the JVM harness {@code tools/jvm-harness/StyleSpanTest.java} off-device. The renderers
 * ({@code TextBoxRenderer} preview + export, and its delegate {@code TextOverlayRenderer} on the
 * per-object-FX GL path) consume {@link Run}s — maximal style-constant ranges — so they never
 * re-derive the rules and cannot disagree with the harness.</p>
 */
public final class TextStyleResolver {

    private TextStyleResolver() {}

    // ── Case values — THE single authority ─────────────────────────────────────────────────────
    // TextOverlayItem.CASE_* now alias these, so the "which case is this?" question has one
    // answer in a class the harness can compile.

    public static final String CASE_NONE = "NONE";
    public static final String CASE_CAPITALIZE_FIRST = "CAPITALIZE_FIRST";
    public static final String CASE_ALL_CAPS = "ALL_CAPS";
    public static final String CASE_SMALL_CAPS = "SMALL_CAPS";

    /** The span-overridable properties. Everything else on a text overlay stays item-level. */
    public enum Prop {
        FONT_FAMILY, BOLD, ITALIC, UNDERLINE, TEXT_CASE,
        FILL_COLOR, STROKE_COLOR, GLOW_COLOR, SHADOW_COLOR, BACKGROUND_COLOR
    }

    /**
     * The item's base style — the exact style an unspanned character gets. Read off the item by
     * the renderer/drawer; kept a plain data holder here so this class stays android-free.
     */
    public static final class Base {
        @NonNull public String fontFamily = "default";
        public boolean bold;
        public boolean italic;
        public boolean underline;
        @NonNull public String textCase = CASE_NONE;
        public int fillColor;
        public int strokeColor;
        public int glowColor;
        public int shadowColor;
        public int backgroundColor;
    }

    /**
     * A maximal {@code [start, end)} range of the text whose resolved style is constant — the
     * unit the renderers lay out and draw. All fields are fully resolved (never null-ish beyond
     * the base defaults): this is the answer, not a candidate.
     */
    public static final class Run {
        public int start;
        public int end;
        @NonNull public String fontFamily = "default";
        public boolean bold;
        public boolean italic;
        public boolean underline;
        @NonNull public String textCase = CASE_NONE;
        public int fill;
        public int stroke;
        public int glow;
        public int shadow;
        public int background;

        /** True when every field agrees — the merge predicate for adjacent intervals. */
        boolean sameStyleAs(@NonNull Run o) {
            return fontFamily.equals(o.fontFamily)
                    && bold == o.bold && italic == o.italic && underline == o.underline
                    && textCase.equals(o.textCase)
                    && fill == o.fill && stroke == o.stroke && glow == o.glow
                    && shadow == o.shadow && background == o.background;
        }
    }

    // ── Resolution ──────────────────────────────────────────────────────────────────────────────

    /**
     * Resolve the whole string into style-constant runs. Last-span-wins per property; unspanned
     * ranges carry the base style verbatim. Spans are clamped to {@code [0, len)} — a span left
     * dangling by a shorter replacement text degrades gracefully instead of throwing.
     */
    @NonNull
    public static List<Run> resolve(@NonNull Base base, @NonNull List<StyleSpan> spans, int len) {
        List<Run> out = new ArrayList<>();
        if (len <= 0) return out;
        // Elementary intervals: the boundaries of the string plus every span edge, clamped.
        List<Integer> bounds = new ArrayList<>();
        bounds.add(0);
        for (StyleSpan s : spans) {
            if (s.start >= len || s.end <= 0) continue;
            int lo = Math.max(0, s.start);
            int hi = Math.min(len, s.end);
            if (lo < hi) {
                addUnique(bounds, lo);
                addUnique(bounds, hi);
            }
        }
        bounds.add(len);
        java.util.Collections.sort(bounds);
        Run merged = null;
        for (int k = 0; k < bounds.size() - 1; k++) {
            int s = bounds.get(k);
            int e = bounds.get(k + 1);
            if (s >= e) continue;
            Run r = resolveInterval(base, spans, s, e);
            if (merged != null && merged.end == s && merged.sameStyleAs(r)) {
                merged.end = e;      // coalesce adjacent identical intervals into one run
                continue;
            }
            out.add(r);
            merged = r;
        }
        return out;
    }

    private static void addUnique(@NonNull List<Integer> bounds, int v) {
        for (int i = 0; i < bounds.size(); i++) {
            if (bounds.get(i) == v) return;
        }
        bounds.add(v);
    }

    @NonNull
    private static Run resolveInterval(@NonNull Base base, @NonNull List<StyleSpan> spans,
                                       int s, int e) {
        Run r = new Run();
        r.start = s;
        r.end = e;
        fillFromBase(r, base);
        for (StyleSpan sp : spans) {
            if (sp.start >= e || sp.end <= s) continue;
            if (sp.fontFamily != null) r.fontFamily = sp.fontFamily;
            if (sp.bold != null) r.bold = sp.bold;
            if (sp.italic != null) r.italic = sp.italic;
            if (sp.underline != null) r.underline = sp.underline;
            if (sp.textCase != null) r.textCase = sp.textCase;
            if (sp.fillColor != null) r.fill = sp.fillColor;
            if (sp.strokeColor != null) r.stroke = sp.strokeColor;
            if (sp.glowColor != null) r.glow = sp.glowColor;
            if (sp.shadowColor != null) r.shadow = sp.shadowColor;
            if (sp.backgroundColor != null) r.background = sp.backgroundColor;
        }
        return r;
    }

    private static void fillFromBase(@NonNull Run r, @NonNull Base base) {
        r.fontFamily = base.fontFamily;
        r.bold = base.bold;
        r.italic = base.italic;
        r.underline = base.underline;
        r.textCase = base.textCase;
        r.fill = base.fillColor;
        r.stroke = base.strokeColor;
        r.glow = base.glowColor;
        r.shadow = base.shadowColor;
        r.background = base.backgroundColor;
    }

    // ── Single-point and range queries (the drawer's "what does the selection look like?") ─────

    /**
     * The effective value of {@code prop} at one char, or null when {@code idx} is out of range.
     * Last-span-wins with base fallback.
     */
    @Nullable
    public static Object at(@NonNull Base base, @NonNull List<StyleSpan> spans,
                            @NonNull Prop prop, int idx, int len) {
        if (idx < 0 || idx >= len) return null;
        Object v = propOfBase(base, prop);
        for (StyleSpan sp : spans) {
            if (idx >= sp.start && idx < sp.end && sets(sp, prop)) {
                v = propOf(sp, prop);
            }
        }
        return v;
    }

    /**
     * The effective value of {@code prop} over the whole range {@code [s, e)}: a single boxed
     * value when every char in range agrees, <b>null when the range is MIXED</b> (or empty) —
     * exactly the "split swatch / Mixed label" contract the drawer displays. Setting a control
     * with a null answer overwrites the whole range with one choice, which is what the user asked
     * for in the first place.
     */
    @Nullable
    public static Object overRange(@NonNull Base base, @NonNull List<StyleSpan> spans,
                                   @NonNull Prop prop, int s, int e, int len) {
        if (s >= e) return null;
        Object first = null;
        for (Run r : resolve(base, spans, len)) {
            if (r.end <= s || r.start >= e) continue;
            Object v = propOfRun(r, prop);
            if (first == null) {
                first = v;
            } else if (!first.equals(v)) {
                return null; // mixed
            }
        }
        return first;
    }

    /**
     * Every distinct effective value of {@code prop} across the range, in appearance order —
     * what the font picker uses to light EVERY family present in the selection, and the case
     * chips use to mark every partially-applied case. Empty set = range out of bounds.
     */
    @NonNull
    public static Set<Object> valuesOverRange(@NonNull Base base, @NonNull List<StyleSpan> spans,
                                              @NonNull Prop prop, int s, int e, int len) {
        Set<Object> out = new LinkedHashSet<>();
        if (s >= e) return out;
        for (Run r : resolve(base, spans, len)) {
            if (r.end <= s || r.start >= e) continue;
            out.add(propOfRun(r, prop));
        }
        return out;
    }

    // ── Mutation: the drawer's "set this on the selection" ─────────────────────────────────────

    /**
     * Set {@code prop} to {@code value} over the whole range {@code [s, e)} — "one choice
     * overriding", exactly JoyRaptor's spec. Implemented by appending a span (the appended span is
     * last, so it wins for every covered char), then pruning earlier spans that are fully inside
     * the range AND set only this property (their only contribution is now dead). A span carrying
     * OTHER properties survives, because those still matter outside the new span's shadow.
     */
    public static void apply(@NonNull List<StyleSpan> spans, @NonNull Prop prop,
                             @Nullable Object value, int s, int e) {
        if (s >= e) return;
        // Prune fully-shadowed single-property spans first, so toggling one highlight around the
        // same words repeatedly does not stack spans forever.
        for (int i = spans.size() - 1; i >= 0; i--) {
            StyleSpan sp = spans.get(i);
            if (sp.start >= s && sp.end <= e && sets(sp, prop) && sp.propertyCount() == 1) {
                spans.remove(i);
            }
        }
        // Reuse a same-range span when it exists (common: style the same words repeatedly).
        // It is RE-ADDED at the end, not mutated in place: resolution is last-wins, and a
        // set is a fresh in-order decision — an older span must not silently keep losing to
        // a later span that also sets this property.
        for (int i = spans.size() - 1; i >= 0; i--) {
            StyleSpan sp = spans.get(i);
            if (sp.start == s && sp.end == e) {
                spans.remove(i);
                setProp(sp, prop, value);
                spans.add(sp);
                return;
            }
        }
        StyleSpan ns = new StyleSpan(s, e);
        setProp(ns, prop, value);
        spans.add(ns);
    }

    /** Remove every span property from {@code [s, e)} — the "Clear formatting" chip. Spans
     * partially overlapping the range are split, keeping whatever lies outside it. */
    public static void clearRange(@NonNull List<StyleSpan> spans, int s, int e) {
        if (s >= e) return;
        List<StyleSpan> out = new ArrayList<>(spans.size() + 2);
        for (StyleSpan sp : spans) {
            if (sp.end <= s || sp.start >= e) {
                out.add(sp);                       // disjoint — untouched
                continue;
            }
            if (sp.start < s) {                    // left remnant keeps its own style
                StyleSpan left = sp.copy();
                left.end = Math.min(sp.end, s);
                out.add(left);
            }
            if (sp.end > e) {                      // right remnant keeps its own style
                StyleSpan right = sp.copy();
                right.start = Math.max(sp.start, e);
                out.add(right);
            }
            // the middle is gone, whatever it styled
        }
        spans.clear();
        spans.addAll(out);
    }

    /** Drop every span — "Clear" with no selection clears the whole item. */
    public static void clearAll(@NonNull List<StyleSpan> spans) {
        spans.clear();
    }

    /**
     * Keep the spans aligned when the AUTHORED text is edited: {@code [start, start+before)} was
     * replaced by {@code count} new chars (the exact {@code TextWatcher.onTextChanged} triple).
     *
     * <p>Conventions: text typed INSIDE a span extends the span over the new chars; text typed
     * immediately BEFORE a span's start does not join it (exclusive start edge); a span fully
     * inside deleted text disappears; the replacement of deleted span-bearing text inherits the
     * deleted span's formatting (na clamps to the edit start).</p>
     */
    public static void adjustForEdit(@NonNull List<StyleSpan> spans, int start, int before,
                                     int count) {
        if (before == 0 && count == 0) return;
        int delta = count - before;
        int ins = Math.max(0, start);
        int delEnd = ins + Math.max(0, before);
        List<StyleSpan> out = new ArrayList<>(spans.size());
        for (StyleSpan sp : spans) {
            int a = sp.start;
            int b = sp.end;
            int na, nb;
            if (b <= ins) {
                na = a; nb = b;                                   // wholly before the edit
            } else if (a >= delEnd) {
                na = a + delta; nb = b + delta;                   // wholly after — shift
            } else {
                na = (a <= ins) ? a : ins;                        // started inside → clamp
                nb = (b > delEnd) ? b + delta : ins + count;      // tail spills → shift; ended
                                                                  // inside the replaced run →
                                                                  // extend over the new text
            }
            if (na < nb) {
                StyleSpan c = sp.copy();
                c.start = na;
                c.end = nb;
                out.add(c);
            }
        }
        spans.clear();
        spans.addAll(out);
    }

    /**
     * Drop no-op spans, clamp ranges to the text, and coalesce adjacent same-style spans. Never
     * reorders: resolution is order-sensitive (last-wins), so list order is part of the meaning.
     */
    public static void normalize(@NonNull List<StyleSpan> spans, int len) {
        List<StyleSpan> out = new ArrayList<>(spans.size());
        for (StyleSpan sp : spans) {
            if (sp.isNoop()) continue;
            sp.setRange(Math.max(0, sp.start), Math.min(len, sp.end));
            if (sp.start >= sp.end) continue;
            if (!out.isEmpty()) {
                StyleSpan prev = out.get(out.size() - 1);
                if (prev.end >= sp.start && prev.equals(sp)) {
                    prev.end = Math.max(prev.end, sp.end);
                    continue;
                }
            }
            out.add(sp);
        }
        spans.clear();
        spans.addAll(out);
    }

    /** Deep equality of two span lists (the drawer's undo "did anything change?" test). */
    public static boolean spansEqual(@Nullable List<StyleSpan> a, @Nullable List<StyleSpan> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) return false;
        }
        return true;
    }

    // ── Display-string case application (length-preserving, per span) ──────────────────────────

    /**
     * Apply {@code textCase} to {@code [s, e)} of {@code text}, writing into {@code out} —
     * per-CHAR and therefore length-preserving (no {@code String.toUpperCase} locale expansion
     * like "ß"→"SS"), which is what keeps a span's offsets valid after the transform is drawn.
     * {@code atWordStart} tells the first char whether the char before the run was whitespace
     * (CAPITALIZE_FIRST is word-contextual and must know where the run begins mid-sentence).
     */
    public static void applyCaseToRange(@NonNull StringBuilder out, @NonNull String text,
                                        int s, int e, @NonNull String textCase,
                                        boolean atWordStart) {
        switch (textCase) {
            case CASE_ALL_CAPS:
            case CASE_SMALL_CAPS:
                for (int i = s; i < e; i++) {
                    out.append(Character.toUpperCase(text.charAt(i)));
                }
                break;
            case CASE_CAPITALIZE_FIRST:
                for (int i = s; i < e; i++) {
                    char c = text.charAt(i);
                    if (Character.isWhitespace(c)) {
                        atWordStart = true;
                        out.append(c);
                    } else if (atWordStart) {
                        out.append(Character.toUpperCase(c));
                        atWordStart = false;
                    } else {
                        out.append(c);
                    }
                }
                break;
            default: // CASE_NONE, and any future unknown value
                out.append(text, s, e);
        }
    }

    // ── Property plumbing ──────────────────────────────────────────────────────────────────────

    @Nullable
    private static Object propOf(@NonNull StyleSpan sp, @NonNull Prop p) {
        switch (p) {
            case FONT_FAMILY: return sp.fontFamily;
            case BOLD: return sp.bold;
            case ITALIC: return sp.italic;
            case UNDERLINE: return sp.underline;
            case TEXT_CASE: return sp.textCase;
            case FILL_COLOR: return sp.fillColor;
            case STROKE_COLOR: return sp.strokeColor;
            case GLOW_COLOR: return sp.glowColor;
            case SHADOW_COLOR: return sp.shadowColor;
            case BACKGROUND_COLOR: return sp.backgroundColor;
        }
        return null;
    }

    private static boolean sets(@NonNull StyleSpan sp, @NonNull Prop p) {
        switch (p) {
            case FONT_FAMILY: return sp.fontFamily != null;
            case BOLD: return sp.bold != null;
            case ITALIC: return sp.italic != null;
            case UNDERLINE: return sp.underline != null;
            case TEXT_CASE: return sp.textCase != null;
            case FILL_COLOR: return sp.fillColor != null;
            case STROKE_COLOR: return sp.strokeColor != null;
            case GLOW_COLOR: return sp.glowColor != null;
            case SHADOW_COLOR: return sp.shadowColor != null;
            case BACKGROUND_COLOR: return sp.backgroundColor != null;
        }
        return false;
    }

    private static void setProp(@NonNull StyleSpan sp, @NonNull Prop p, @Nullable Object v) {
        switch (p) {
            case FONT_FAMILY: sp.fontFamily = (String) v; break;
            case BOLD: sp.bold = (Boolean) v; break;
            case ITALIC: sp.italic = (Boolean) v; break;
            case UNDERLINE: sp.underline = (Boolean) v; break;
            case TEXT_CASE: sp.textCase = (String) v; break;
            case FILL_COLOR: sp.fillColor = (Integer) v; break;
            case STROKE_COLOR: sp.strokeColor = (Integer) v; break;
            case GLOW_COLOR: sp.glowColor = (Integer) v; break;
            case SHADOW_COLOR: sp.shadowColor = (Integer) v; break;
            case BACKGROUND_COLOR: sp.backgroundColor = (Integer) v; break;
        }
    }

    @Nullable
    private static Object propOfBase(@NonNull Base base, @NonNull Prop p) {
        switch (p) {
            case FONT_FAMILY: return base.fontFamily;
            case BOLD: return base.bold;
            case ITALIC: return base.italic;
            case UNDERLINE: return base.underline;
            case TEXT_CASE: return base.textCase;
            case FILL_COLOR: return base.fillColor;
            case STROKE_COLOR: return base.strokeColor;
            case GLOW_COLOR: return base.glowColor;
            case SHADOW_COLOR: return base.shadowColor;
            case BACKGROUND_COLOR: return base.backgroundColor;
        }
        return null;
    }

    @Nullable
    private static Object propOfRun(@NonNull Run r, @NonNull Prop p) {
        switch (p) {
            case FONT_FAMILY: return r.fontFamily;
            case BOLD: return r.bold;
            case ITALIC: return r.italic;
            case UNDERLINE: return r.underline;
            case TEXT_CASE: return r.textCase;
            case FILL_COLOR: return r.fill;
            case STROKE_COLOR: return r.stroke;
            case GLOW_COLOR: return r.glow;
            case SHADOW_COLOR: return r.shadow;
            case BACKGROUND_COLOR: return r.background;
        }
        return null;
    }
}