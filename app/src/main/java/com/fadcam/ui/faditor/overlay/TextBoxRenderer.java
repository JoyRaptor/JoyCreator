package com.fadcam.ui.faditor.overlay;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.transcript.CaptionAnimator;

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
 * It IS applied here, on both surfaces — see {@link #drawUnit}. This sentence used to read
 * "deliberately NOT applied here… the one channel whose two surfaces genuinely cannot match",
 * which stopped being true when GHOST's blur shipped and was left stale; it is corrected rather
 * than quietly rewritten because a doc that contradicts its own method body is how a blocker note
 * gets believed without being re-derived.
 *
 * <p>(A fifth channel, ODOMETER's wheel, arrived later still: {@link CaptionAnimator#rollUnit}
 * and {@code rollClip}, also applied in {@link #drawUnit}.)
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

    // ── Measurement ──────────────────────────────────────────────────────────────────────────

    /**
     * The box's size at {@code fontPx}, including {@link #PAD_EM} on every side.
     *
     * <p>Both surfaces size their box from THIS, so a glyph's position within the box is the same
     * number on screen and in the exported frame. The preview used to get its size from
     * {@code TextView.measure} and the export from its own arithmetic; those agreed closely enough
     * for a whole-body transform and would not have agreed per glyph.
     *
     * @param out receives {@code {width, height}} in the same pixels {@code fontPx} is in
     */
    public static void measure(@NonNull TextOverlayItem o, @NonNull String text, float fontPx,
                               @NonNull float[] out) {
        TextPaint p = paintFor(o, fontPx);
        String[] lines = splitLines(normalise(text));
        float widest = 1f;
        for (String line : lines) widest = Math.max(widest, p.measureText(line));
        Paint.FontMetrics fm = p.getFontMetrics();
        float lineH = (fm.descent - fm.ascent) * LINE_SPACING;
        float pad = fontPx * PAD_EM;
        out[0] = widest + pad * 2f;
        out[1] = lineH * lines.length + pad * 2f;
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
        TextPaint p = paintFor(o, fontPx);
        // Normalise ONCE, at the top, and use this string for everything below. Getting this
        // wrong crashed the editor: splitLines substituted " " for an empty box while the unit
        // map was still sized from the original zero-length string, so the first character of the
        // substituted line indexed past the end of a zero-length array. Any two derivations of
        // "the text" that can disagree will eventually disagree — so there is only one.
        String t = normalise(text);
        String[] lines = splitLines(t);
        Paint.FontMetrics fm = p.getFontMetrics();
        float lineH = (fm.descent - fm.ascent) * LINE_SPACING;
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

        long spanMs = o.animSpanMs(projectDurationMs);
        long inZone = CaptionAnimator.zoneForSpan(o.getTextAnimInPct(), spanMs);
        long outZone = CaptionAnimator.zoneForSpan(o.getTextAnimOutPct(), spanMs);
        boolean animating = preset != CaptionAnimator.Preset.NONE && spanMs > 0
                && (inZone > 0 || outZone > 0);

        float baseY = top + pad - fm.ascent;
        int charBase = 0;
        float[] adv = new float[64];
        for (String line : lines) {
            int n = line.length();
            if (adv.length < n) adv = new float[n];
            p.getTextWidths(line, adv);
            float lineW = 0f;
            for (int i = 0; i < n; i++) lineW += adv[i];
            float x = left + (size[0] - lineW) / 2f;

            // Walk the line in runs of one unit. Whitespace belongs to no unit and is simply
            // stepped over — it carries no ink, so nothing is lost by never drawing it.
            int i = 0;
            while (i < n) {
                // Bounds-guarded rather than trusted. lines and unitOfChar are both derived from
                // `t` so their indices agree by construction, but this runs inside onDraw on every
                // frame, and the cost of being wrong here is the editor dying rather than one
                // glyph being misplaced.
                int ci = charBase + i;
                int u = ci < unitOfChar.length ? unitOfChar[ci] : -1;
                if (u < 0) {          // between units
                    x += adv[i];
                    i++;
                    continue;
                }
                int j = i;
                float runW = 0f;
                while (j < n && charBase + j < unitOfChar.length
                        && unitOfChar[charBase + j] == u) {
                    runW += adv[j];
                    j++;
                }
                float progress = animating
                        ? CaptionAnimator.unitProgress(mediaMs, o.getStartMs(),
                                o.getStartMs() + spanMs, inZone, outZone, u, unitCount)
                        : 1f;
                drawUnit(c, p, o, line.substring(i, j), x, baseY, runW, fontPx, preset,
                        progress, u, objectAlpha);
                x += runW;
                i = j;
            }
            charBase += n + 1;        // +1 for the '\n' that split() removed
            baseY += lineH;
        }
    }

    /**
     * One unit, with all three animated channels applied — the text-box twin of
     * {@code CaptionOverlayView.drawUnit} / {@code CaptionExportRenderer.drawUnit}, except that
     * there is only ONE of it and both surfaces call it.
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
     */
    private static void drawUnit(@NonNull Canvas c, @NonNull TextPaint p,
                                 @NonNull TextOverlayItem o, @NonNull String run,
                                 float x, float baseY, float w, float fontPx,
                                 @NonNull CaptionAnimator.Preset preset, float progress,
                                 int unitIdx, float objectAlpha) {
        CaptionAnimator.Transform t =
                CaptionAnimator.presetTransform(preset, progress, fontPx, unitIdx);
        if (t.alpha <= 0.004f) return;
        if (!CaptionAnimator.revealDrawsAnything(t.revealFrac)) return;

        String shown = CaptionAnimator.substituteUnit(preset, run, progress, unitIdx);

        Paint.FontMetrics fm = p.getFontMetrics();
        float ucx = x + w / 2f;
        float ucy = baseY - (fm.descent - fm.ascent) * 0.35f;

        c.save();
        c.translate(t.dx, t.dy);
        c.scale(t.scaleX, t.scaleY, ucx, ucy);
        if (t.revealFrac < 1f) {
            float[] clip = new float[4];
            CaptionAnimator.revealClip(x, baseY, w, fontPx, t.revealFrac, clip);
            c.clipRect(clip[0], clip[1], clip[2], clip[3]);
        }
        // GHOST's blur. Set on the paint for this unit only and cleared straight after, so it
        // cannot leak onto the next unit or onto a later frame through the shared TextPaint.
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
            CaptionAnimator.rollClip(x, baseY, w, fontPx, rollClip);
            c.clipRect(rollClip[0], rollClip[1], rollClip[2], rollClip[3]);
            // The travel is the WINDOW HEIGHT, read off the rect rather than recomputed from
            // fontPx, so the distance and the window cannot drift: the outgoing row is exactly
            // hidden at the instant the incoming row is exactly in place.
            float slotH = rollClip[3] - rollClip[1];
            c.save();
            c.translate(0f, roll.phase * slotH);
            paintRun(c, p, o, roll.incoming, x, baseY, fontPx, animAlpha, t.glowPx);
            c.restore();
            c.save();
            c.translate(0f, (roll.phase - 1f) * slotH);
            paintRun(c, p, o, roll.outgoing, x, baseY, fontPx, animAlpha, t.glowPx);
            c.restore();
        } else {
            paintRun(c, p, o, shown, x, baseY, fontPx, animAlpha, t.glowPx);
        }
        if (blurred) p.setMaskFilter(null);
        c.restore();
    }

    /**
     * Scratch for the roll channel, reused rather than allocated because at LETTER granularity
     * {@code drawUnit} runs once per glyph per frame.
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

    /**
     * The three paint passes — outline, glow, fill — in the order the export has always used.
     *
     * <p>{@code animAlpha} multiplies ALL of them, for the reason the caption path documents:
     * fading only the fill leaves an outline standing at full opacity, so a departing unit reads
     * as an empty outline of itself rather than as text going away.</p>
     */
    private static void paintRun(@NonNull Canvas c, @NonNull TextPaint p,
                                 @NonNull TextOverlayItem o, @NonNull String run,
                                 float x, float baseY, float fontPx, float animAlpha,
                                 float presetGlowPx) {
        // The PRESET's own glow (NEON_FLICKER), in the unit's own fill colour. Drawn before the
        // object's optional passes so the user's stroke and glow still sit on top of it, and
        // cleared by the applyShadow below, which every path already reaches.
        if (presetGlowPx > 0.25f) {
            p.setShadowLayer(presetGlowPx, 0f, 0f,
                    CaptionAnimator.applyAlpha(o.getColorInt(), animAlpha));
            p.setStyle(Paint.Style.FILL);
            p.setColor(CaptionAnimator.applyAlpha(o.getColorInt(), animAlpha));
            c.drawText(run, x, baseY, p);
            p.clearShadowLayer();
        }
        if (o.getStrokeWidthPx() > 0f && o.getStrokeColorInt() != Color.TRANSPARENT) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(com.fadcam.ui.faditor.model.TextOverlayItem.decorRadiusPx(o.getStrokeWidthPx(), fontPx));
            p.setColor(CaptionAnimator.applyAlpha(o.getStrokeColorInt(), animAlpha));
            c.drawText(run, x, baseY, p);
        }
        if (o.getGlowRadiusPx() > 0f && o.getGlowColorInt() != Color.TRANSPARENT) {
            p.setShadowLayer(com.fadcam.ui.faditor.model.TextOverlayItem.decorRadiusPx(o.getGlowRadiusPx(), fontPx), 0f, 0f, o.getGlowColorInt());
            p.setStyle(Paint.Style.FILL);
            p.setColor(CaptionAnimator.applyAlpha(o.getColorInt(), animAlpha));
            c.drawText(run, x, baseY, p);
        }
        applyShadow(p, o, fontPx);
        p.setStyle(Paint.Style.FILL);
        p.setColor(CaptionAnimator.applyAlpha(o.getColorInt(), animAlpha));
        c.drawText(run, x, baseY, p);
    }

    // ── Shared paint setup ───────────────────────────────────────────────────────────────────

    /**
     * The paint both surfaces draw with. Every visual property of a text box is decided here, so
     * "the preview uses a slightly different shadow" cannot happen — which it previously did: the
     * {@code TextView} path set no glow and no background at all, and set the FILL colour to the
     * stroke colour rather than stroking.
     *
     * <p>{@code Align.LEFT} because this renderer positions every run itself; a centred align
     * would fight the per-glyph x it computes.</p>
     */
    @NonNull
    private static TextPaint paintFor(@NonNull TextOverlayItem o, float fontPx) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(Math.max(1f, fontPx));
        p.setTypeface(o.getTypeface());
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(o.getColorInt());
        applyShadow(p, o, fontPx);
        return p;
    }

    private static void applyShadow(@NonNull TextPaint p, @NonNull TextOverlayItem o,
                                    float fontPx) {
        p.setShadowLayer(o.getShadowRadiusPx() > 0f
                ? com.fadcam.ui.faditor.model.TextOverlayItem.decorRadiusPx(o.getShadowRadiusPx(), fontPx) : fontPx * 0.10f,
                0f, fontPx * 0.04f, o.getShadowColorInt());
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
