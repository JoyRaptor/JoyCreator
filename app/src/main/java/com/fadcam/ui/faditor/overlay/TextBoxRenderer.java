package com.fadcam.ui.faditor.overlay;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.StyleSpan;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.TextStyleResolver;
import com.fadcam.ui.faditor.transcript.CaptionAnimator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * THE per-glyph renderer for a TEXT BOX — the one place a text overlay's pixels are decided,
 * called by BOTH the live preview and the export.
 *
 * <h3>Why this class exists, and why it is shaped differently from the caption path</h3>
 * Captions solved the preview/export agreement problem with TWO deliberate mirrors,
 * {@code CaptionOverlayView.drawWord} and {@code CaptionExportRenderer.drawWord}. That works, but
 * only because someone keeps them identical by hand — every change to one is a change to the
 * other, and the comments in both say so. Text boxes get the stronger version of the same idea:
 * <b>one renderer, two callers</b>. There is nothing to keep in sync, because there is only one
 * of it.
 *
 * <p>That matters here more than anywhere else in the project. LEDGER §3g exists because a text
 * animation was computed twice and the two copies disagreed; §3a exists because a preview and an
 * export disagreed about what was visible. A text box drawn glyph-by-glyph has to agree about
 * WHERE GLYPH i SITS, which is a much finer-grained agreement than "is this visible" — and two
 * independently written layouts would not hold it for long.
 *
 * <h3>Rich text (W5-2 §3.8)</h3>
 * Both surfaces lay out the SAME resolved runs, produced by {@link TextStyleResolver} — the one
 * authority for what a span means. A box without spans resolves to a single whole-string run of
 * the item's base style and draws exactly as it always did (the legacy locale-sensitive
 * {@code applyCase} is kept for that path, so existing projects render byte-identically). A box
 * with spans segments every line into cells — the intersection of animation units with style
 * runs — and each cell draws with its own run's typeface, colours and toggles. Since
 * {@code applyCaseToRange} is length-preserving per char, span offsets stay valid in the display
 * string, so unit segmentation and style segmentation can share one layout.
 *
 * <h3>What changed to make this necessary</h3>
 * Text boxes were BLOCK-only (one unit, whole body) because the preview drew them as a single
 * {@code TextView}: one view, one string, no way to move individual characters. The recorded
 * reason said the export "draws with {@code canvas.drawText}, which can" — true of the primitive,
 * but the export did not do it either: it rasterised the whole box to a bitmap and animated the
 * bitmap. So per-letter text boxes needed per-glyph drawing on BOTH surfaces plus a shared layout,
 * not one renderer bolted onto an existing one. This is that shared layout, and it is also both
 * renderers.
 *
 * <h3>The three animated channels, all of them per unit</h3>
 * Exactly the ones the caption path already has, from the one evaluator:
 * geometry+alpha ({@link CaptionAnimator#presetTransform}), the drawn string
 * ({@link CaptionAnimator#substituteUnit}, MATRIX) and the reveal mask
 * ({@link CaptionAnimator.Transform#revealFrac}, MASK_WIPE).
 *
 * <p><b>There is a FOURTH channel: blur ({@link CaptionAnimator.Transform#blurPx}), GHOST's.</b>
 * It IS applied here, on both surfaces — see {@link #drawCell}. This sentence used to read
 * "deliberately NOT applied here… the one channel whose two surfaces genuinely cannot match",
 * which stopped being true when GHOST's blur shipped and was left stale; it is corrected rather
 * than quietly rewritten because a doc that contradicts its own method body is how a blocker note
 * gets believed without being re-derived.
 *
 * <p>(A fifth channel, ODOMETER's wheel, arrived later still: {@link CaptionAnimator#rollUnit}
 * and {@code rollClip}, also applied in {@link #drawCell}.)
 */
public final class TextBoxRenderer {

    private TextBoxRenderer() {}

    /**
     * The transparent margin left around the text, as a multiple of the type size.
     *
     * <p>It exists so a shadow, an outline or a glow is not cut off at the box edge. Applying it
     * on BOTH surfaces is a change on the preview side, which previously had none because a
     * {@code TextView} measured to its own text — that mismatch is why the export had to inset by
     * it when MASK_WIPE wiped across the ink. Now there is one number and both agree.
     */
    private static final float PAD_EM = 0.35f;

    /** Line spacing, as a multiple of the font's own ascent-to-descent height. */
    private static final float LINE_SPACING = 1.0f;

    /** Corner radius of the background pill, as a multiple of the type size. */
    private static final float PILL_RADIUS_EM = 0.35f;

    /** Selection highlight in the preview while the drawer is editing (W5-2 §3.8). */
    /** The accent wash. Same purple as the caret and handles — see {@code colors.xml}'s
     *  {@code faditor_text_selection_accent}; kept as a literal here because this class is
     *  shared with the export path and must not reach for resources. */
    private static final int SEL_COLOR = 0x66B388FF;
    /** The scrim UNDER the wash — see {@code drawSelection} for why there are two passes. */
    private static final int SEL_SCRIM = 0x73000000;

    /**
     * The shared margin unit ({@link #PAD_EM}) — exposed so the rasterised export path
     * ({@code TextOverlayRenderer.padPxFor}) and the shared renderer measure on the same
     * number instead of each owning a copy.
     */
    public static float padEm() {
        return PAD_EM;
    }

    // ── Layout ────────────────────────────────────────────────────────────────────────────────

    /**
     * One rect of constant style AND constant animation unit within a line — the atom both the
     * measurement pass and the draw pass iterate.
     */
    private static final class Cell {
        int unit;                        // animation unit index, or -1 outside any unit
        int ls;                          // char index in the LINE string (display string)
        int le;                          // exclusive end in the LINE string
        @NonNull TextStyleResolver.Run run;
        float x;                         // laid-out left edge, measured from the line's text left
        float w;                         // advance width
    }

    /** A laid-out line: per-char advances, ink metrics and cells. */
    private static final class LineLayout {
        int start;                       // char offset of the line's first char in the DISPLAY string
        int n;                           // line length
        @NonNull float[] adv;            // per-char advances (run-accurate)
        float lineW;
        float maxAscent;
        float maxDescent;
        float lineH;
        /** This line's offset from the box's text top — see the accumulation at the end of
         *  {@code layout()}. Zero for the first line. */
        float top;
        @NonNull List<Cell> cells = new ArrayList<>(8);
    }

    /**
     * The resolved style runs for the box's AUTHORED string, or a single whole-string run of the
     * base style when the box has no spans — so one machinery serves both paths. A TIMER's string
     * is computed, so its indices share nothing with the span indices (authored text) — spans are
     * ignored for timers entirely (spec §3.8; the drawer also refuses to create them).
     */
    @NonNull
    private static List<TextStyleResolver.Run> runsFor(@NonNull TextOverlayItem o, int len) {
        return TextStyleResolver.resolve(o.resolveBase(),
                o.isTimer() || !o.hasStyleSpans()
                        ? java.util.Collections.<StyleSpan>emptyList()
                        : o.getStyleSpans(),
                len);
    }

    /** What actually gets drawn — the authored string run through each run's case transform.
     * No-spans keeps the legacy locale-sensitive {@code applyCase} so pre-W5-2 projects render
     * byte-identically; the span path is per-char (length-preserving) so offsets survive. */
    @NonNull
    private static String displayFor(@NonNull TextOverlayItem o, @NonNull String authored,
                                     @NonNull List<TextStyleResolver.Run> runs) {
        // Timers are excluded here EXACTLY as in runsFor: their string is computed, their runs
        // are the empty list, and walking zero runs would render the box as "". Runs are only
        // synthesized by the drawer on authored text, but a hand-edited project could carry
        // stale spans on a timer — the match must be symmetric or a timer goes invisible.
        if (o.isTimer() || !o.hasStyleSpans()) return o.applyCase(authored);
        StringBuilder sb = new StringBuilder(authored.length());
        for (TextStyleResolver.Run r : runs) {
            boolean atWordStart = r.start == 0
                    || Character.isWhitespace(authored.charAt(r.start - 1));
            TextStyleResolver.applyCaseToRange(sb, authored, r.start, r.end,
                    r.textCase, atWordStart);
        }
        return sb.toString();
    }

    // ── Measurement ──────────────────────────────────────────────────────────────────────────

    /**
     * The box's size at {@code fontPx}, including {@link #PAD_EM} on every side.
     *
     * <p>Both surfaces size their box from THIS, so a glyph's position within the box is the same
     * number on screen and in the exported frame. The preview used to get its size from
     * {@code TextView.measure} and the export from its own arithmetic; those agreed closely enough
     * for a whole-body transform and would not have agreed per glyph.
     *
     * <p>With spans, per-run typefaces change advances and ink metrics, so the width of a line is
     * the SUM of its run-accurate advances and the line height is the max ascent/descent across
     * the line's runs — the same numbers the draw pass lays out with.
     *
     * @param out receives {@code {width, height}} in the same pixels {@code fontPx} is in
     */
    public static void measure(@NonNull TextOverlayItem o, @NonNull String text, float fontPx,
                               @NonNull float[] out) {
        String authored = normalise(text);
        List<TextStyleResolver.Run> runs = runsFor(o, authored.length());
        String t = displayFor(o, authored, runs);
        LineLayout[] lines = layout(t, runs, fontPx, null, null);
        float widest = 1f;
        float totalH = 0f;
        for (LineLayout ln : lines) {
            widest = Math.max(widest, ln.lineW);
            totalH += ln.lineH;
        }
        float pad = fontPx * PAD_EM;
        out[0] = widest + pad * 2f;
        out[1] = totalH + pad * 2f;
    }

    /**
     * What this box should DRAW at {@code mediaMs} before any per-unit substitution — i.e. a
     * timer's computed string, or the item's own text.
     *
     * <p>Kept here rather than at the two call sites because it is the input to layout: a timer
     * whose string changes width must re-measure, and both surfaces must re-measure the same way.
     * MATRIX's substitution is NOT applied here — it happens per unit inside {@link #draw}, since
     * at LETTER granularity different glyphs of the same box are at different progresses.
     */
    @NonNull
    public static String textAt(@NonNull TextOverlayItem o, long mediaMs, long projectDurationMs) {
        if (o.isTimer()) {
            String t = com.fadcam.ui.faditor.model.TimerText.format(
                    o.getTimerSpec(), mediaMs, o.getStartMs(), o.getEndMs(), projectDurationMs,
                    com.fadcam.ui.faditor.model.TimerText.DEFAULT_FPS);
            if (t != null) return t;
        }
        String t = o.getText();
        return t == null ? "" : t;
    }

    // ── Drawing ──────────────────────────────────────────────────────────────────────────────

    /**
     * Draw the box with its top-left corner at {@code (left, top)}.
     *
     * <p>The caller owns everything OUTSIDE the box — position, rotation, the object's own
     * keyframed opacity — because those are whole-object properties that the preview expresses as
     * View properties and the export as canvas transforms, and neither needs this renderer's help
     * with them. This owns everything INSIDE: the pill, the lines, the glyphs and all three
     * animated channels.
     *
     * @param animate false while the user is dragging the object, so it follows the finger rather
     *                than the tape — the same suppression the preview already applied
     * @param objectAlpha the object's OWN keyframed opacity, multiplied into every unit's alpha
     *                    rather than applied as a layer. A layer would be the more correct
     *                    compositing — overlapping glyphs would blend once instead of twice — but
     *                    it costs an offscreen buffer per overlay per frame, and more to the point
     *                    the preview would have to take the same offscreen or the two would
     *                    composite differently. Doing the cheap thing identically on both beats
     *                    doing the right thing on one.
     */
    public static void draw(@NonNull Canvas c, @NonNull TextOverlayItem o, @NonNull String text,
                            float left, float top, float fontPx, long mediaMs,
                            long projectDurationMs, boolean animate, float objectAlpha) {
        draw(c, o, text, left, top, fontPx, mediaMs, projectDurationMs, animate, objectAlpha,
                -1, -1);
    }

    /**
     * Draw with an optional selection highlight ({@code selStart}/{@code selEnd} in DISPLAY-string
     * indices, both {@code < 0} for none). The export passes no selection; the preview passes the
     * drawer's live selection so the user sees exactly which characters a style change will hit.
     */
    public static void draw(@NonNull Canvas c, @NonNull TextOverlayItem o, @NonNull String text,
                            float left, float top, float fontPx, long mediaMs,
                            long projectDurationMs, boolean animate, float objectAlpha,
                            int selStart, int selEnd) {
        String authored = normalise(text);
        List<TextStyleResolver.Run> runs = runsFor(o, authored.length());
        // Normalise ONCE, at the top, and use this string for everything below. Getting this
        // wrong crashed the editor: splitLines substituted " " for an empty box while the unit
        // map was still sized from the original zero-length string, so the first character of the
        // substituted line indexed past the end of a zero-length array. Any two derivations of
        // "the text" that can disagree will eventually disagree — so there is only one.
        String t = displayFor(o, authored, runs);
        float pad = fontPx * PAD_EM;

        float[] size = new float[2];
        measure(o, t, fontPx, size);

        if (o.getBackgroundColorInt() != Color.TRANSPARENT) {
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(CaptionAnimator.applyAlpha(o.getBackgroundColorInt(), objectAlpha));
            c.drawRoundRect(new RectF(left, top, left + size[0], top + size[1]),
                    fontPx * PILL_RADIUS_EM, fontPx * PILL_RADIUS_EM, bg);
        }

        CaptionAnimator.Preset preset = animate
                ? CaptionAnimator.parsePreset(o.getTextAnimPreset())
                : CaptionAnimator.Preset.NONE;
        CaptionAnimator.Granularity gran =
                CaptionAnimator.parseGranularity(o.getTextAnimGranularity());

        // Units are cut from the WHOLE string, newlines included. splitUnits treats '\n' as
        // whitespace, so no unit ever straddles a line break — which is what lets a unit be drawn
        // as a single run below instead of being split again per line.
        int[][] units = CaptionAnimator.splitUnits(t, gran);
        int unitCount = Math.max(1, units.length);
        int[] unitOfChar = new int[t.length()];
        java.util.Arrays.fill(unitOfChar, -1);
        for (int u = 0; u < units.length; u++) {
            for (int i = units[u][0]; i < units[u][1] && i < unitOfChar.length; i++) {
                unitOfChar[i] = u;
            }
        }

        LineLayout[] laid = layout(t, runs, fontPx, units, unitOfChar);

        long spanMs = o.motionSpanMs(projectDurationMs);
        long inZone = CaptionAnimator.zoneForSpan(o.getTextAnimInPct(), spanMs);
        long outZone = CaptionAnimator.zoneForSpan(o.getTextAnimOutPct(), spanMs);
        boolean animating = preset != CaptionAnimator.Preset.NONE && spanMs > 0
                && (inZone > 0 || outZone > 0);

        // Selection pass first, so the ink sits on top of it. Best-effort math against the
        // display string (spans are length-preserving, so this is exact for the span path).
        if (selStart >= 0 && selEnd > selStart) {
            drawSelection(c, o, laid, left, top, size[0], pad, selStart, selEnd);
        }

        for (LineLayout ln : laid) {
            float alignX = alignedLineX(o, left, size[0], ln.lineW, pad);
            float baseY = topFor(ln, top, pad);
            int currentUnit = -2;
            float unitX = 0f;
            float unitW = 0f;
            float progress = 1f;
            String unitShown = null;          // substituted text of the CURRENT unit
            for (int ci = 0; ci < ln.cells.size(); ci++) {
                Cell cell = ln.cells.get(ci);
                if (cell.unit < 0) continue;              // whitespace carries no ink
                float px = alignX + cell.x;
                if (cell.unit != currentUnit) {
                    currentUnit = cell.unit;
                    unitX = px;                            // the unit's leftmost cell edge
                    unitW = 0f;
                    // Substitute the WHOLE unit once, then slice per cell — the substitution
                    // is length-preserving, so a cell's offsets in the display string are the
                    // same offsets in the substituted unit text.
                    progress = animating
                            ? CaptionAnimator.unitProgress(mediaMs, o.motionRangeStartMs(),
                                    o.motionRangeEndMs(projectDurationMs), inZone, outZone,
                                    currentUnit, unitCount)
                            : 1f;
                    unitShown = CaptionAnimator.substituteUnit(preset,
                            t.substring(units[currentUnit][0], units[currentUnit][1]),
                            progress, currentUnit);
                }
                unitW += cell.w;
                int cellGlobalStart = ln.start + cell.ls;
                int cellGlobalEnd = cellGlobalStart + (cell.le - cell.ls);
                String shown = unitShown.substring(
                        cellGlobalStart - units[currentUnit][0],
                        cellGlobalEnd - units[currentUnit][0]);
                drawCell(c, cell.run, paintForRun(cell.run, fontPx), o, shown,
                        px, baseY, unitX, unitW, fontPx, preset, progress,
                        currentUnit, objectAlpha, mediaMs);
            }
        }
    }

    /**
     * The selection highlight: one purple rect per affected cell overlap, in a line band of that
     * line's own metrics — the same cells and the same aligned x the draw pass uses, so the
     * highlight can never point at different glyphs than the ink.
     */
    private static void drawSelection(@NonNull Canvas c, @NonNull TextOverlayItem o,
                                      @NonNull LineLayout[] lines, float left, float top,
                                      float boxW, float pad, int selStart, int selEnd) {
        // TWO PASSES: a dark scrim, then the accent wash over it.
        //
        // A single translucent purple band is only legible over what happens to be behind it. The
        // text being selected may be any colour the user picked, over any frame of their footage,
        // and "which characters am I about to restyle" must never be a guess — including when the
        // run itself is several colours (JoyRaptor, 2026-08-14: "selecting text should be easy to
        // read even if the text is many different colors ... clear in all cases which text is
        // selected").
        //
        // Darkening first is what makes the wash land on a known ground instead of on the video:
        // the same reasoning the drawer's chips are tinted black rather than lightened. The
        // user's own glyph colours are untouched — only what is BEHIND them changes — which
        // matters because the colour being judged is often the one being edited.
        Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        scrim.setColor(SEL_SCRIM);
        scrim.setStyle(Paint.Style.FILL);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(SEL_COLOR);
        p.setStyle(Paint.Style.FILL);
        for (LineLayout ln : lines) {
            float alignX = alignedLineX(o, left, boxW, ln.lineW, pad);
            // topFor returns the line's BASELINE — it is what the draw pass passes as baseY.
            // Using it as the band's TOP put the highlight a full line below the glyphs it was
            // highlighting ("it looks like its selecting one line down", JoyRaptor 2026-08-14).
            // maxAscent is negative (Android font metrics measure up from the baseline), so
            // adding it lifts the band to the line's ink top; ln.lineH then carries it down
            // through the descent, which is the same span the draw pass occupies.
            float bandTop = topFor(ln, top, pad) + ln.maxAscent;
            for (Cell cell : ln.cells) {
                int a = Math.max(cell.ls, selStart - ln.start);
                int b = Math.min(cell.le, selEnd - ln.start);
                if (a >= b) continue;
                float ox = 0f;
                for (int i = cell.ls; i < a; i++) ox += ln.adv[i];
                float w = 0f;
                for (int i = a; i < b; i++) w += ln.adv[i];
                if (w <= 0f) continue;
                float rx = alignX + cell.x + ox;
                RectF band = new RectF(rx, bandTop, rx + w, bandTop + ln.lineH);
                c.drawRect(band, scrim);
                c.drawRect(band, p);
            }
        }
    }

    /** The line's BASELINE y: the box top, the ink pad, the line's own offset down the stack,
     *  then up by its ascent (negative). Used by the ink pass and the selection band alike, so
     *  the two cannot land on different lines. */
    private static float topFor(@NonNull LineLayout ln, float top, float pad) {
        return top + pad + ln.top - ln.maxAscent;
    }

    /**
     * Lay every line into cells — the intersection of animation units (when given) with resolved
     * style runs. Also computes per-char advances (run-accurate), the line's ink metrics (max
     * ascent/descent across its runs) and each cell's laid-out x (measured from the line's TEXT
     * left edge; alignment is applied by the caller), so measurement and drawing cannot disagree
     * about where a glyph sits.
     */
    @NonNull
    private static LineLayout[] layout(@NonNull String t,
                                       @NonNull List<TextStyleResolver.Run> runs, float fontPx,
                                       @Nullable int[][] units, @Nullable int[] unitOfChar) {
        String[] lines = splitLines(t);
        LineLayout[] out = new LineLayout[lines.length];
        int charBase = 0;
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            int n = line.length();
            LineLayout ln = new LineLayout();
            ln.start = charBase;
            ln.n = n;
            ln.adv = new float[Math.max(1, n)];
            if (runs.isEmpty()) {
                // Defensive: resolver tiles [0,len) so this should not happen for len >= 1.
                ln.lineH = fontPx * 1.2f;
                out[li] = ln;
                charBase += n + 1;
                continue;
            }
            // Pass A: per-char advances + ink metrics, run span by run span. Units do not
            // change geometry, only style does — one getTextWidths call per run span per line.
            float[] widths = WIDTHS.get();
            if (widths.length < n) {
                widths = new float[n + 16];
                WIDTHS.set(widths);
            }
            int ri = 0;
            int i = 0;
            while (i < n) {
                int gi = charBase + i;
                while (ri < runs.size() && runs.get(ri).end <= gi) ri++;
                if (ri >= runs.size()) break;
                TextStyleResolver.Run run = runs.get(ri);
                TextPaint p = paintForRun(run, fontPx);
                int j = Math.min(n, run.end - charBase);
                if (j <= i) { i = j; continue; }
                p.getTextWidths(line, i, j, widths);
                int w = j - i;
                for (int k = 0; k < w; k++) ln.adv[i + k] = widths[k];
                Paint.FontMetrics fm = p.getFontMetrics();
                ln.maxAscent = Math.min(ln.maxAscent, fm.ascent);
                ln.maxDescent = Math.max(ln.maxDescent, fm.descent);
                i = j;
            }
            ln.lineW = 0f;
            for (int k = 0; k < n; k++) ln.lineW += ln.adv[k];
            // An EMPTY line (a text ending in a line break) keeps the box's height — the old
            // behaviour was one uniform line height for every line, and the splitLines contract
            // says a trailing newline keeps its height. Seed the metrics from the first run's
            // font so such a line is not zero-tall.
            if (n == 0) {
                Paint.FontMetrics fm = paintForRun(runs.get(0), fontPx).getFontMetrics();
                ln.maxAscent = fm.ascent;
                ln.maxDescent = fm.descent;
            }
            // Pass B: cells = unit ∩ run spans, sharing the same run pointer walk.
            i = 0;
            ri = 0;
            float x = 0f;
            while (i < n) {
                int gi = charBase + i;
                while (ri < runs.size() && runs.get(ri).end <= gi) ri++;
                if (ri >= runs.size()) break;
                TextStyleResolver.Run run = runs.get(ri);
                int runEnd = Math.min(n, run.end - charBase);
                int uHere = (units != null && gi < unitOfChar.length) ? unitOfChar[gi] : -1;
                int j = i + 1;
                while (j < runEnd) {
                    int gj = charBase + j;
                    int uThere = (units != null && gj < unitOfChar.length) ? unitOfChar[gj] : -1;
                    if (uThere != uHere) break;
                    j++;
                }
                Cell cell = new Cell();
                cell.unit = uHere;
                cell.ls = i;
                cell.le = j;
                cell.run = run;
                float w = 0f;
                for (int k = i; k < j; k++) w += ln.adv[k];
                cell.w = w;
                cell.x = x;
                x += w;
                ln.cells.add(cell);
                i = j;
            }
            ln.lineH = (ln.maxDescent - ln.maxAscent) * LINE_SPACING;
            out[li] = ln;
            charBase += n + 1;                 // +1 for the '\n' that split() removed
        }
        // EACH LINE'S OWN OFFSET DOWN THE BOX, accumulated once, here.
        //
        // Without it every line drew at the SAME baseline: pressing Enter produced a second line
        // painted straight over the first (JoyRaptor, 2026-08-14: "when i hit enter the text isnt
        // making a new line lower, its just making a new line that COVERS the others"). measure()
        // has always summed lineH, so the BOX grew correctly — only the ink stayed put, which is
        // why the box looked right and the text did not.
        //
        // Stored on the line rather than accumulated in each drawing loop because THREE passes
        // walk these lines — the ink, the selection band and measurement — and a running total
        // kept separately by each is three chances to disagree about where line two starts.
        float stackY = 0f;
        for (LineLayout ln : out) {
            ln.top = stackY;
            stackY += ln.lineH;
        }
        return out;
    }

    /**
     * One cell, with all three animated channels applied — the text-box twin of
     * {@code CaptionOverlayView.drawUnit} / {@code CaptionExportRenderer.drawUnit}, except that
     * there is only ONE of it and both surfaces call it.
     *
     * <p>A unit whose characters span style runs is drawn once per CELL, each with its own paint
     * and resolved colours; the reveal/roll clip windows are the UNIT's (computed from its bounds
     * here), so a wipe sweeps the whole unit rather than each cell being revealed from its own
     * left edge.</p>
     *
     * <p><b>{@link CaptionAnimator.Transform#blurPx} IS applied, on both surfaces.</b> See the
     * {@code BlurMaskFilter} in the body below.
     *
     * <p><b>This paragraph used to say the exact opposite, and the correction is worth keeping.</b>
     * It read: "blurPx is not applied, and that is a decision… {@code BlurMaskFilter} is ignored on
     * a hardware canvas and honoured on a software one, so applying it here would produce a blur in
     * the exported file and nothing on screen." The PREMISE was right — a hardware canvas really
     * does ignore {@code BlurMaskFilter} — and the CONCLUSION was wrong, because it assumed the
     * preview's canvas had to stay hardware. It does not: {@code TextBoxView.applyBlurLayerPolicy}
     * switches the view to {@code LAYER_TYPE_SOFTWARE} exactly when the preset blurs
     * ({@link CaptionAnimator#presetBlurs}), so both surfaces honour the filter and there is no
     * divergence to avoid. The price of that switch was MEASURED rather than assumed — about
     * +0.4ms on a full-screen box, ~2.4% of a 16.7ms frame — which is why the sanctioned
     * divergence was declined.
     *
     * <p>Left as a correction rather than a clean rewrite because this file shipped for a while
     * carrying a javadoc that contradicted its own body, and that is precisely how a stale blocker
     * note survives long enough to be planned around. If a doc here and the code here disagree,
     * the code is the fact.
     *
     * <p><b>Scope: TEXT BOXES only.</b> Captions still ignore {@code blurPx} — one shared view for
     * all words, so a different cost profile and a separate decision.
     *
     * @param unitX unitX the whole unit's laid-out left edge — shared clip windows for every cell
     * @param unitW unitW the whole unit's laid-out width — the distance a reveal sweeps across
     */
    private static void drawCell(@NonNull Canvas c, @NonNull TextStyleResolver.Run run,
                                 @NonNull TextPaint p, @NonNull TextOverlayItem o,
                                 @NonNull String shown, float x, float baseY,
                                 float unitX, float unitW,
                                 float fontPx, @NonNull CaptionAnimator.Preset preset,
                                 float progress, int unitIdx, float objectAlpha, long mediaMs) {
        CaptionAnimator.Transform t =
                CaptionAnimator.presetTransform(preset, progress, fontPx, unitIdx);
        if (t.alpha <= 0.004f) return;
        if (!CaptionAnimator.revealDrawsAnything(t.revealFrac)) return;

        Paint.FontMetrics fm = p.getFontMetrics();
        float ucx = unitX + unitW / 2f;
        float ucy = baseY - (fm.descent - fm.ascent) * 0.35f;

        c.save();
        c.translate(t.dx, t.dy);
        c.scale(t.scaleX, t.scaleY, ucx, ucy);
        if (t.revealFrac < 1f) {
            float[] clip = new float[4];
            CaptionAnimator.revealClip(unitX, baseY, unitW, fontPx, t.revealFrac, clip);
            c.clipRect(clip[0], clip[1], clip[2], clip[3]);
        }
        // GHOST's blur. Set on the paint for this cell only and cleared straight after, so it
        // cannot leak onto the next cell or onto a later frame through the shared TextPaint.
        // BlurMaskFilter is a no-op on a hardware canvas, which is why TextBoxView switches the
        // view to a software layer for a blurring preset — see CaptionAnimator#presetBlurs for
        // the measured price of doing so.
        boolean blurred = t.blurPx > 0.25f;
        if (blurred) {
            p.setMaskFilter(new android.graphics.BlurMaskFilter(
                    t.blurPx, android.graphics.BlurMaskFilter.Blur.NORMAL));
        }
        // The FOURTH animated channel: ODOMETER's wheel — two glyph rows inside one clipped slot.
        // Mirrors CaptionOverlayView#drawUnit / CaptionExportRenderer#drawUnit, and here ONE call
        // site serves both surfaces, so the preview and the export cannot disagree by
        // construction rather than by care. Inert for every other preset.
        //
        // This is the preset whose old blocker note said a text box could not host it at all,
        // because the preview was a TextView holding one string. It is drawn here, on a canvas,
        // with two drawText calls — the wall came down when this class replaced that TextView.
        float animAlpha = t.alpha * clamp01(objectAlpha);
        CaptionAnimator.Roll roll = ROLL.get();
        float[] rollClip = ROLL_CLIP.get();
        CaptionAnimator.rollUnit(preset, shown, progress, roll);
        if (roll.rolling) {
            CaptionAnimator.rollClip(unitX, baseY, unitW, fontPx, rollClip);
            c.clipRect(rollClip[0], rollClip[1], rollClip[2], rollClip[3]);
            // The travel is the WINDOW HEIGHT, read off the rect rather than recomputed from
            // fontPx, so the distance and the window cannot drift: the outgoing row is exactly
            // hidden at the instant the incoming row is exactly in place.
            float slotH = rollClip[3] - rollClip[1];
            c.save();
            c.translate(0f, roll.phase * slotH);
            paintRun(c, run, p, o, roll.incoming, x, baseY, fontPx, animAlpha, t.glowPx, mediaMs);
            c.restore();
            c.save();
            c.translate(0f, (roll.phase - 1f) * slotH);
            paintRun(c, run, p, o, roll.outgoing, x, baseY, fontPx, animAlpha, t.glowPx, mediaMs);
            c.restore();
        } else {
            paintRun(c, run, p, o, shown, x, baseY, fontPx, animAlpha, t.glowPx, mediaMs);
        }
        if (blurred) p.setMaskFilter(null);
        c.restore();
    }

    /**
     * Scratch for the roll channel, reused rather than allocated because at LETTER granularity
     * {@code drawCell} runs once per glyph per frame.
     *
     * <p><b>THREAD-LOCAL, not static, and that is the point.</b> Every method on this class is
     * static and stateless, so a plain {@code static} scratch buffer would have looked consistent
     * with the rest of it — and would have been a data race. This one class is called from TWO
     * threads: the main thread draws the preview through {@code TextBoxView.onDraw}, and the
     * export draws through {@code CompositeExportOverlay} on its own worker while the editor is
     * still on screen behind the progress UI. Two threads sharing one {@code Roll} would tear a
     * glyph row between them — intermittently, in exported video only, which is close to the
     * worst failure this area can produce. Sharing ONE renderer between the two surfaces is the
     * whole design; it also means anything mutable in it has to survive being called twice at
     * once.</p>
     */
    // NOT ThreadLocal.withInitial: that overload is API 26 and this module's minSdk is 24, so it
    // would be a NoSuchMethodError on 24-25 — a crash on exactly the devices least likely to be
    // tested. The anonymous-subclass form has worked since API 1.
    private static final ThreadLocal<CaptionAnimator.Roll> ROLL =
            new ThreadLocal<CaptionAnimator.Roll>() {
                @Override protected CaptionAnimator.Roll initialValue() {
                    return new CaptionAnimator.Roll();
                }
            };
    private static final ThreadLocal<float[]> ROLL_CLIP =
            new ThreadLocal<float[]>() {
                @Override protected float[] initialValue() { return new float[4]; }
            };
    private static final ThreadLocal<float[]> WIDTHS =
            new ThreadLocal<float[]>() {
                @Override protected float[] initialValue() { return new float[64]; }
            };

    /**
     * The three paint passes — outline, glow, fill — in the order the export has always used.
     *
     * <p>{@code animAlpha} multiplies ALL of them, for the reason the caption path documents:
     * fading only the fill leaves an outline standing at full opacity, so a departing unit reads
     * as an empty outline of itself rather than as text going away.</p>
     *
     * <p>Every colour and toggle comes from the RESOLVED run — fill, stroke, glow, shadow,
     * underline — so the base style and any span override arrive through the same door.</p>
     */
    private static void paintRun(@NonNull Canvas c, @NonNull TextStyleResolver.Run run,
                                 @NonNull TextPaint p, @NonNull TextOverlayItem o,
                                 @NonNull String runText, float x, float baseY, float fontPx,
                                 float animAlpha, float presetGlowPx, long mediaMs) {
        p.setUnderlineText(run.underline);
        // The PRESET's own glow (NEON_FLICKER), in the run's own fill colour. Drawn before the
        // object's optional passes so the user's stroke and glow still sit on top of it, and
        // cleared by the applyShadow below, which every path already reaches.
        if (presetGlowPx > 0.25f) {
            p.setShadowLayer(presetGlowPx, 0f, 0f,
                    CaptionAnimator.applyAlpha(run.fill, animAlpha));
            p.setStyle(Paint.Style.FILL);
            p.setColor(CaptionAnimator.applyAlpha(run.fill, animAlpha));
            c.drawText(runText, x, baseY, p);
            p.clearShadowLayer();
        }
        float strokeWidthPx = o.animatedStrokeWidthPx(mediaMs);
        if (strokeWidthPx > 0f && run.stroke != Color.TRANSPARENT) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(com.fadcam.ui.faditor.model.TextOverlayItem.decorRadiusPx(strokeWidthPx, fontPx));
            p.setColor(CaptionAnimator.applyAlpha(run.stroke, animAlpha));
            c.drawText(runText, x, baseY, p);
        }
        float glowRadiusPx = o.animatedGlowRadiusPx(mediaMs);
        if (glowRadiusPx > 0f && run.glow != Color.TRANSPARENT) {
            p.setShadowLayer(com.fadcam.ui.faditor.model.TextOverlayItem.decorRadiusPx(glowRadiusPx, fontPx), 0f, 0f, run.glow);
            p.setStyle(Paint.Style.FILL);
            p.setColor(CaptionAnimator.applyAlpha(run.fill, animAlpha));
            c.drawText(runText, x, baseY, p);
        }
        applyShadow(p, run.shadow, fontPx, mediaMs, o);
        p.setStyle(Paint.Style.FILL);
        p.setColor(CaptionAnimator.applyAlpha(run.fill, animAlpha));
        c.drawText(runText, x, baseY, p);
        p.clearShadowLayer();
        p.setUnderlineText(false);
    }

    /**
     * A line's left edge for the item's current {@code textAlign}.
     *
     * <p>JUSTIFY reads as LEFT here — genuine justify (stretching inter-glyph gaps to fill the
     * line) would need each glyph drawn with its own manual advance instead of one
     * {@code drawText} call per run, which the per-unit animation path above does not support.
     * Left-aligning rather than centring is still the more useful fallback: it is what every
     * other line in a justified paragraph looks like except the last.</p>
     */
    private static float alignedLineX(@NonNull TextOverlayItem o, float left, float boxW,
                                      float lineW, float pad) {
        switch (o.getTextAlign()) {
            case TextOverlayItem.ALIGN_LEFT:
            case TextOverlayItem.ALIGN_JUSTIFY:
                return left + pad;
            case TextOverlayItem.ALIGN_RIGHT:
                return left + boxW - pad - lineW;
            default: // CENTER
                return left + (boxW - lineW) / 2f;
        }
    }

    // ── Shared paint setup ───────────────────────────────────────────────────────────────────

    /**
     * The paint a resolved run draws with: the run's family/bold/italic typeface at {@code fontPx}.
     * Colours and toggles are applied per pass in {@link #paintRun} — this is only the glyph
     * shape. Cached per thread on the family/bold/italic triple: {@code drawCell} may run per
     * glyph per frame but the distinct typefaces in a box are few.
     *
     * <p>{@code Align.LEFT} because this renderer positions every run itself; a centred align
     * would fight the per-glyph x it computes.</p>
     */
    @NonNull
    private static TextPaint paintForRun(@NonNull TextStyleResolver.Run run, float fontPx) {
        String k = run.fontFamily + "|" + run.bold + "|" + run.italic;
        Map<String, TextPaint> cache = PAINTS.get();
        TextPaint p = cache.get(k);
        if (p == null) {
            p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            p.setTypeface(TextOverlayItem.typefaceFor(run.fontFamily, run.bold, run.italic));
            p.setTextAlign(Paint.Align.LEFT);
            cache.put(k, p);
        }
        p.setTextSize(Math.max(1f, fontPx));
        return p;
    }

    /**
     * Per-thread typeface-keyed paint cache — same race discipline as {@link #ROLL}: this one
     * class is called from the main thread (preview) and the export worker at the same time, so
     * the cache must be per thread or the two surfaces would corrupt each other's paints.
     */
    // NOT ThreadLocal.withInitial: see ROLL for the API-26 reason.
    private static final ThreadLocal<Map<String, TextPaint>> PAINTS =
            new ThreadLocal<Map<String, TextPaint>>() {
                @Override protected Map<String, TextPaint> initialValue() {
                    return new HashMap<>(8);
                }
            };

    /**
     * @param mediaMs the timeline time to evaluate the shadow's keyframed angle/distance/radius
     *                at. Passed 0 (= the object's own local time 0) from pre-frame paint setup,
     *                where no frame time is known yet and every draw call overwrites the shadow
     *                layer again with the real one before anything shows.
     */
    private static void applyShadow(@NonNull TextPaint p, int shadowColor, float fontPx,
                                    long mediaMs, @NonNull TextOverlayItem o) {
        float radiusPercent = o.animatedShadowRadiusPx(mediaMs);
        float radius = radiusPercent > 0f
                ? com.fadcam.ui.faditor.model.TextOverlayItem.decorRadiusPx(radiusPercent, fontPx)
                : fontPx * 0.10f;
        float angle = o.animatedShadowAngleDeg(mediaMs);
        float distance = o.animatedShadowDistancePx(mediaMs);
        float dx = com.fadcam.ui.faditor.model.TextOverlayItem.shadowDx(angle, distance, fontPx);
        float dy = com.fadcam.ui.faditor.model.TextOverlayItem.shadowDy(angle, distance, fontPx);
        p.setShadowLayer(radius, dx, dy, shadowColor);
    }

    private static float clamp01(float v) {
        return Float.isNaN(v) ? 0f : Math.max(0f, Math.min(1f, v));
    }

    /**
     * The ONE definition of "this box's text": never null, never empty.
     *
     * <p>An empty box becomes a single space so it still has a measurable size — a zero-size box
     * cannot be selected or dragged back. <b>This must be applied once, at the top of whatever
     * uses it, and every derived thing built from the RESULT.</b> Deriving the lines from the
     * normalised string while deriving the unit map from the raw one is not a subtle bug: it
     * crashed the editor on the first empty overlay, because a one-character line indexed into a
     * zero-length map.</p>
     */
    @NonNull
    private static String normalise(@Nullable String text) {
        return text == null || text.isEmpty() ? " " : text;
    }

    /**
     * Lines, with the same {@code split} on a newline the export has always used — the trailing
     * -1 keeps an empty last line, so a text ending in a line break keeps its height.
     */
    @NonNull
    private static String[] splitLines(@NonNull String text) {
        return text.split("\n", -1);
    }
}