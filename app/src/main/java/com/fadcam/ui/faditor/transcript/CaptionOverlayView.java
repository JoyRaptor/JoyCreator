package com.fadcam.ui.faditor.transcript;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Live, animated on-screen captions (TikTok-style). Driven by the playhead: it
 * shows the short phrase being spoken and pops/zooms/bounces the active word.
 * Draggable to reposition. The same look is rebuilt at export time.
 */
public class CaptionOverlayView extends View {

    public interface Callback {
        @NonNull RectF getVideoContentRect();
        void onMoved();
        /** Tapped the caption (no drag) — open its properties (style bar). */
        default void onTapped() {}
        /** Double-tapped the caption — open its advanced menu (JoyRaptor 2026-07-16). */
        default void onDoubleTapped() {}
        /** Long-pressed the caption — hide captions for this clip. */
        default void onLongPressed() {}
    }

    /** Double-tap pairing state. */
    private long lastTapUpMs;

    @Nullable private Transcript transcript;
    @NonNull private CaptionStyle style = CaptionStyle.presets().get(0);
    @Nullable private Callback callback;

    private float centerX = 0.5f;
    private float centerY = 0.82f;
    private float sizeFraction = 0.060f;

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    /** Phrase grouping — shared with the export renderer so both agree where a phrase begins. */
    @NonNull private CaptionPhrases grouping = CaptionPhrases.of(null);

    private int activeWordIdx = -1;
    /** Eased entrance progress of the active word, evaluated from MEDIA time by
     *  {@link CaptionAnimator} — see {@link #setActiveSourceMs}. */
    private float emphasisValue = 1f;
    /** The playhead in SOURCE ms, kept so {@link #onDraw} can evaluate per-unit animation. */
    private long sourceMs = 0L;

    // Text animation (SPEC_TEXT_ANIMATION). Defaults are the off state.
    @NonNull private CaptionAnimator.Preset animPreset = CaptionAnimator.Preset.NONE;
    @NonNull private CaptionAnimator.Granularity animGran = CaptionAnimator.Granularity.WORD;
    private float animInPct = 0f;
    private float animOutPct = 0f;
    // Per-phrase animation state, recomputed at the top of each onDraw. Fields rather than
    // locals only so drawWord can see them without a six-argument signature; onDraw is the sole
    // writer, and allocating these per frame is what a caption overlay cannot afford.
    private final List<String> animWords = new ArrayList<>();
    private int animUnitCount = 1;
    private long animSpanStart = 0L, animSpanEnd = 1L, animInEff = 0L, animOutEff = 0L;

    private final RectF blockRect = new RectF(); // last drawn bounds (for drag hit-test)
    private boolean dragging;
    private float downX, downY, startCx, startCy;
    // Tap (= open properties) vs long-press (= hide) vs drag (= reposition) disambiguation.
    private final android.os.Handler longPressHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final int touchSlop =
            android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
    private boolean longPressFired;
    private boolean movedBeyondSlop;
    private final Runnable longPressRunnable = () -> {
        longPressFired = true;
        if (callback != null) {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            callback.onLongPressed();
        }
    };

    public CaptionOverlayView(Context c) { this(c, null); }

    public CaptionOverlayView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
    }

    /**
     * (Re)bind transcript + style + callback.
     *
     * <p>The active word is RESET only when the transcript actually changes identity (a
     * different clip's captions, where the old index means nothing). A re-bind carrying
     * the SAME transcript is a style/size/position refresh, and resetting on those was a
     * silent hole: {@link #onDraw} draws nothing while {@code activeWordIdx < 0}, and the
     * only thing that ever sets it again is the playback-position loop. So every re-bind
     * with the player PAUSED — a style chip, the size slider, a bulk apply, an undo —
     * made the captions vanish until playback resumed. That is half of why "the user
     * sizes captions blind" (audit 2.1): binding the size was necessary but not
     * sufficient, because moving the slider erased the very thing it was meant to show.
     * Clamped rather than trusted, since the same Transcript instance can be edited in
     * place (a struck word can shorten the list).</p>
     */
    public void setData(@Nullable Transcript t, @NonNull CaptionStyle s, @NonNull Callback cb) {
        boolean sameTranscript = t != null && t == this.transcript;
        this.transcript = t;
        this.style = s;
        this.callback = cb;
        grouping = CaptionPhrases.of(t, s.maxWords);
        if (sameTranscript) {
            activeWordIdx = Math.min(activeWordIdx, grouping.wordPhrase.length - 1);
        } else {
            activeWordIdx = -1;
        }
        invalidate();
    }

    public void setStyle(@NonNull CaptionStyle s) {
        // The word limit travels with the style, so a style change - or a turn of the dial -
        // has to re-group. Without this the caption keeps the previous style's phrasing and the
        // control looks dead until something else happens to rebuild it.
        boolean regroup = s.maxWords != this.style.maxWords;
        this.style = s;
        if (regroup) grouping = CaptionPhrases.of(transcript, s.maxWords);
        invalidate();
    }

    @NonNull public CaptionStyle getStyle() { return style; }

    public float getCenterX() { return centerX; }
    public float getCenterY() { return centerY; }
    public void setCenter(float x, float y) {
        centerX = Math.max(0.05f, Math.min(0.95f, x));
        centerY = Math.max(0.05f, Math.min(0.95f, y));
        invalidate();
    }

    public float getSizeFraction() { return sizeFraction; }

    /**
     * Caption font height as a fraction of the CANVAS height — the same quantity the
     * export renderer multiplies by its output height ({@code CaptionExportRenderer}),
     * which is why {@code getVideoContentRect()} hands this view the canvas rect and not
     * the per-clip video rect.
     *
     * <p>Until this existed the field was written once at construction and never again:
     * the size slider updated the model and the export, so the user was sizing captions
     * blind and only found out what they had chosen after rendering (audit 2.1, open a
     * month). Clamped to the same 0.02–0.6 range as {@code Clip#setCaptionSizeFraction},
     * so a hand-edited project cannot make the preview and the export disagree.</p>
     */
    public void setSizeFraction(float f) {
        float clamped = Math.max(0.02f, Math.min(0.6f, f));
        if (clamped == sizeFraction) return;
        sizeFraction = clamped;
        invalidate();
    }

    /**
     * The clip's text-animation settings. Names rather than enums so the caller (the editor,
     * reading a {@code Clip}) does not have to resolve them, and so a value written by a newer
     * build degrades to the default here instead of throwing.
     */
    public void setCaptionAnimation(@Nullable String presetName, @Nullable String granularityName,
                                    float inPct, float outPct) {
        animPreset = CaptionAnimator.parsePreset(presetName);
        animGran = CaptionAnimator.parseGranularity(granularityName);
        animInPct = CaptionAnimator.clampZonePct(inPct);
        animOutPct = CaptionAnimator.clampZonePct(outPct);
        applyBlurLayerPolicy();
        invalidate();
    }

    /**
     * Put this view on a SOFTWARE layer exactly when the preset blurs, and back on a hardware one
     * when it does not.
     *
     * <p>{@code BlurMaskFilter} is a no-op on a hardware-accelerated canvas, so GHOST's
     * {@code blurPx} would silently do nothing here — a setter call into a void, the failure mode
     * this project has already shipped once. Mirrors {@code TextBoxView.applyBlurLayerPolicy}.
     *
     * <p><b>Why this is safe to switch on the PRESET rather than per frame.</b> The layer type is
     * a property of the view, and {@code setLayerType} with the value it already holds is a no-op
     * in the framework, so calling it from the one setter costs nothing on the common path. It
     * cannot be decided inside {@code onDraw} — a view cannot change its own layer type while it
     * is drawing.
     *
     * <p><b>The cost profile here is genuinely different from a text box's, and the difference is
     * DURATION, not the "one shared view" the old note named.</b> Either way it is ONE software
     * layer; a text box's is box-sized and a caption's is caption-view-sized. What differs is that
     * a text box's entrance happens once, while captions re-animate line after line for as long as
     * anyone is speaking — so the honest question was never peak per-draw cost but sustained frame
     * rate, and that is what was measured before this shipped. See the ledger.
     */
    private void applyBlurLayerPolicy() {
        setLayerType(CaptionAnimator.presetBlurs(animPreset)
                ? LAYER_TYPE_SOFTWARE : LAYER_TYPE_HARDWARE, null);
    }

    /**
     * Update which word is active from the playback time, and evaluate its entrance animation
     * AT THAT MEDIA TIME.
     *
     * <p>This used to start a 300ms {@code ValueAnimator} on the word-change event, which drove
     * the animation from WALL-CLOCK time while the exporter drove the identical animation from
     * MEDIA time. The two agree only when those clocks agree — so a speed-adjusted clip
     * animated over a different span in the file than on screen, a paused preview kept animating
     * while media time stood still, and a scrub re-triggered the entrance instead of showing the
     * frame that would actually be exported. Asking {@link CaptionAnimator} for the value at
     * {@code sourceMs} makes all three correct by construction, and is why the animator is gone
     * rather than merely re-tuned.</p>
     */
    public void setActiveSourceMs(long sourceMs) {
        if (transcript == null) return;
        this.sourceMs = sourceMs;
        int idx = transcript.indexAtOrBeforeTime(sourceMs);
        activeWordIdx = idx;
        emphasisValue = idx >= 0 && idx < transcript.words.size()
                ? CaptionAnimator.emphasis(style, sourceMs, transcript.words.get(idx).startMs)
                : 1f;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (transcript == null || callback == null || activeWordIdx < 0) return;
        int phraseIdx = grouping.phraseOf(activeWordIdx);
        if (phraseIdx < 0) return;
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return;

        // Struck (removed) words are not drawn, so they must not hold an animation slot either.
        List<Integer> visible = grouping.visibleWords(phraseIdx);
        if (visible.isEmpty()) return;

        // Per-unit animation state for this phrase. The PHRASE is the animating object, not the
        // clip: captions are continuous speech, so zones measured against the whole clip would
        // animate the first phrase and let every later one simply appear.
        animWords.clear();
        for (int i : visible) animWords.add(transcript.words.get(i).text);
        long[] span = grouping.spanMs(phraseIdx);
        animUnitCount = CaptionAnimator.unitCount(animWords, animGran);
        animSpanStart = span != null ? span[0] : 0L;
        animSpanEnd = span != null ? span[1] : 1L;
        animInEff = CaptionAnimator.zoneForSpan(animInPct, animSpanEnd - animSpanStart);
        animOutEff = CaptionAnimator.zoneForSpan(animOutPct, animSpanEnd - animSpanStart);

        float fontPx = sizeFraction * r.height();
        textPaint.setTextSize(fontPx);
        textPaint.setTypeface(style.typeface());
        if (style.shadow) {
            textPaint.setShadowLayer(fontPx * 0.12f, 0, fontPx * 0.05f, 0xDD000000);
        } else {
            textPaint.clearShadowLayer();
        }
        float space = textPaint.measureText(" ");

        // Lay the visible words of this phrase into centred lines.
        float maxW = r.width() * 0.9f;
        List<List<Integer>> lines = new ArrayList<>();
        List<Integer> line = new ArrayList<>();
        float lineW = 0;
        for (int vi = 0; vi < visible.size(); vi++) {
            int i = visible.get(vi);
            float w = textPaint.measureText(transcript.words.get(i).text);
            if (!line.isEmpty() && lineW + space + w > maxW) {
                lines.add(line);
                line = new ArrayList<>();
                lineW = 0;
            }
            line.add(i);
            lineW += (line.size() > 1 ? space : 0) + w;
        }
        if (!line.isEmpty()) lines.add(line);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float lineH = (fm.descent - fm.ascent) * 1.15f;
        float totalH = lineH * lines.size();

        float cx = r.left + centerX * r.width();
        float cy = r.top + centerY * r.height();
        float top = cy - totalH / 2f;

        // Background pill spanning the widest line.
        if (style.pill) {
            float widest = 0;
            for (List<Integer> ln : lines) {
                float w = 0;
                for (int k = 0; k < ln.size(); k++) {
                    w += (k > 0 ? space : 0) + textPaint.measureText(transcript.words.get(ln.get(k)).text);
                }
                widest = Math.max(widest, w);
            }
            float padH = fontPx * 0.4f, padV = fontPx * 0.25f;
            blockRect.set(cx - widest / 2f - padH, top - padV,
                    cx + widest / 2f + padH, top + totalH + padV);
            pillPaint.setColor(style.pillColor);
            canvas.drawRoundRect(blockRect, fontPx * 0.35f, fontPx * 0.35f, pillPaint);
        } else {
            float widest = 0;
            for (List<Integer> ln : lines) {
                float w = 0;
                for (int k = 0; k < ln.size(); k++) {
                    w += (k > 0 ? space : 0) + textPaint.measureText(transcript.words.get(ln.get(k)).text);
                }
                widest = Math.max(widest, w);
            }
            blockRect.set(cx - widest / 2f, top, cx + widest / 2f, top + totalH);
        }

        // Draw each line centred, emphasising the active word. `unitPos` counts the visible
        // words of the phrase in order — lines are built from `visible` in order, so a running
        // counter is the same sequence the animator indexes by.
        float baseY = top - fm.ascent;
        int unitPos = 0;
        for (List<Integer> ln : lines) {
            float w = 0;
            for (int k = 0; k < ln.size(); k++) {
                w += (k > 0 ? space : 0) + textPaint.measureText(transcript.words.get(ln.get(k)).text);
            }
            float x = cx - w / 2f;
            for (int k = 0; k < ln.size(); k++) {
                int wi = ln.get(k);
                String word = transcript.words.get(wi).text;
                float ww = textPaint.measureText(word);
                boolean active = wi == activeWordIdx;
                drawWord(canvas, word, x, baseY, ww, active, fontPx, unitPos++);
                x += ww + space;
            }
            baseY += lineH;
        }
    }

    /**
     * Progress of one animating unit of the current phrase, from the ONE authority.
     * {@code charIdx} matters only at LETTER granularity.
     */
    private int unitIndex(int wordPos, int charIdx) {
        return CaptionAnimator.unitIndexOf(animWords, animGran, wordPos, charIdx);
    }

    private float unitProgress(int unitIdx) {
        return CaptionAnimator.unitProgress(sourceMs, animSpanStart, animSpanEnd,
                animInEff, animOutEff, unitIdx, animUnitCount);
    }

    private void drawWord(Canvas canvas, String word, float x, float baseY,
                          float ww, boolean active, float fontPx, int wordPos) {
        int color = active ? style.activeColor : style.baseColor;

        // LETTER granularity is the only case that cannot draw the word as one run: each glyph
        // carries its own transform, so it must be measured and placed individually. This is the
        // cost centre the spec warns about, which is why it is reached only when asked for.
        if (animPreset != CaptionAnimator.Preset.NONE
                && animGran == CaptionAnimator.Granularity.LETTER) {
            float gx = x;
            for (int i = 0; i < word.length(); i++) {
                String ch = word.substring(i, i + 1);
                float cw = textPaint.measureText(ch);
                int uidx = unitIndex(wordPos, i);
                drawUnit(canvas, ch, gx, baseY, cw, color, fontPx,
                        unitProgress(uidx), active, uidx);
                gx += cw;
            }
            return;
        }

        int uidx = unitIndex(wordPos, 0);
        drawUnit(canvas, word, x, baseY, ww, color, fontPx,
                animPreset == CaptionAnimator.Preset.NONE ? 1f : unitProgress(uidx),
                active, uidx);
    }

    /**
     * Draw one unit with both transforms applied: the PRESET (entrance/exit, from the tape
     * handles) and, for the active word only, the style's active-word EMPHASIS. They compose
     * rather than override — the emphasis is a permanent 1.15x on the spoken word, the preset is
     * a transient entrance, and collapsing them into one number would make choosing a preset
     * silently restyle the emphasis.
     */
    private void drawUnit(Canvas canvas, String text, float x, float baseY, float w,
                          int color, float fontPx, float progress, boolean active, int unitIdx) {
        // unitIdx matters to UNSCRAMBLE, which gives each unit its own scatter direction. Must stay
        // identical to CaptionExportRenderer#drawUnit — a preview that scatters a glyph one way
        // while the export scatters it another is the §3g divergence again.
        CaptionAnimator.Transform pre =
                CaptionAnimator.presetTransform(animPreset, progress, fontPx, unitIdx);
        float scaleX = pre.scaleX, scaleY = pre.scaleY, dx = pre.dx, dy = pre.dy;
        if (active) {
            CaptionAnimator.Transform emp = CaptionAnimator.transform(style, emphasisValue, fontPx);
            scaleX *= emp.scaleX;
            scaleY *= emp.scaleY;
            dy += emp.dy;
        }
        // Fully transparent: skip the draw entirely rather than paint nothing expensively.
        if (pre.alpha <= 0.004f) return;
        // MASK_WIPE keeps alpha at 1, so the test above never fires for it. A fully-masked unit
        // would otherwise be laid out and drawn into an empty clip on every frame of its entrance.
        if (!CaptionAnimator.revealDrawsAnything(pre.revealFrac)) return;

        float ucx = x + w / 2f;
        float ucy = baseY - (textPaint.getFontMetrics().descent
                - textPaint.getFontMetrics().ascent) * 0.35f;
        // The SECOND animated channel: which characters to draw. MATRIX is the only preset that
        // uses it; everything else returns `text` unchanged, so this is inert for them. The slot
        // width `w` was measured from the REAL text and is passed through untouched, which is what
        // stops substitution reflowing the line. Must stay identical to
        // CaptionExportRenderer#drawUnit — a preview that substitutes differently from the export
        // is the §3g divergence wearing a new hat.
        String shown = CaptionAnimator.substituteUnit(animPreset, text, progress, unitIdx);

        canvas.save();
        canvas.translate(dx, dy);
        canvas.scale(scaleX, scaleY, ucx, ucy);
        // The THIRD animated channel: how much of the slot is uncovered. Clipped INSIDE the
        // transform on purpose — the mask then scales and moves with the glyph, so the active
        // word's 1.15x emphasis does not slide the ink out from under its own mask. Inert for
        // every preset but MASK_WIPE, which is the only one that returns revealFrac < 1. Must stay
        // identical to CaptionExportRenderer#drawUnit.
        if (pre.revealFrac < 1f) {
            CaptionAnimator.revealClip(x, baseY, w, fontPx, pre.revealFrac, revealTmp);
            canvas.clipRect(revealTmp[0], revealTmp[1], revealTmp[2], revealTmp[3]);
        }
        // The FOURTH animated channel: a slot showing TWO glyph rows mid-roll. ODOMETER is the
        // only preset that uses it; for everything else `rolling` is false and this is one method
        // call and a branch. Clipped inside the transform for the same reason the reveal is — the
        // window has to travel with the glyph, or the emphasis scale would slide the ink out of
        // its own slot. Must stay identical to CaptionExportRenderer#drawUnit and
        // TextBoxRenderer#drawUnit.
        CaptionAnimator.rollUnit(animPreset, shown, progress, rollTmp);
        if (rollTmp.rolling) {
            CaptionAnimator.rollClip(x, baseY, w, fontPx, rollClipTmp);
            canvas.clipRect(rollClipTmp[0], rollClipTmp[1], rollClipTmp[2], rollClipTmp[3]);
            // The travel is the WINDOW HEIGHT, taken from the rect rather than recomputed, so the
            // two cannot drift: the outgoing row is exactly hidden at the instant the incoming
            // row is exactly in place.
            float slotH = rollClipTmp[3] - rollClipTmp[1];
            canvas.save();
            canvas.translate(0f, rollTmp.phase * slotH);
            paintWord(canvas, rollTmp.incoming, x, baseY, color, fontPx, pre.alpha, pre.glowPx,
                    pre.blurPx);
            canvas.restore();
            canvas.save();
            canvas.translate(0f, (rollTmp.phase - 1f) * slotH);
            paintWord(canvas, rollTmp.outgoing, x, baseY, color, fontPx, pre.alpha, pre.glowPx,
                    pre.blurPx);
            canvas.restore();
        } else {
            paintWord(canvas, shown, x, baseY, color, fontPx, pre.alpha, pre.glowPx, pre.blurPx);
        }
        canvas.restore();
    }

    /**
     * Scratch for {@link CaptionAnimator#rollUnit} / {@link CaptionAnimator#rollClip}. Fields for
     * the same reason {@link #revealTmp} is: at LETTER granularity this runs once per glyph per
     * frame, and a fresh object there is ~1800 allocations a second.
     */
    private final CaptionAnimator.Roll rollTmp = new CaptionAnimator.Roll();
    private final float[] rollClipTmp = new float[4];

    /**
     * Scratch for {@link CaptionAnimator#revealClip}. A field rather than a local because at
     * LETTER granularity {@code drawUnit} runs once per glyph per frame.
     */
    private final float[] revealTmp = new float[4];

    /**
     * Fill pass plus optional stroke-outline pass, sharing one paint.
     *
     * <p>{@code animAlpha} multiplies BOTH passes. Fading only the fill would leave the outline
     * standing at full opacity, so a fading word would read as an empty outline of itself
     * rather than as text going away.</p>
     */
    /** The style's own shadow, as the per-frame setup applies it. Kept in one place so the
     *  preset-glow pass can restore it instead of duplicating the expression. */
    private void applyStyleShadow(float fontPx) {
        if (style.shadow) {
            textPaint.setShadowLayer(fontPx * 0.12f, 0, fontPx * 0.05f, 0xDD000000);
        } else {
            textPaint.clearShadowLayer();
        }
    }

    private void paintWord(Canvas canvas, String word, float x, float baseY,
                           int fillColor, float fontPx, float animAlpha, float presetGlowPx,
                           float presetBlurPx) {
        // GHOST's blur. Set for this word only and cleared straight after, so it cannot leak onto
        // the next word or onto the pill through the shared TextPaint. This is honoured because
        // applyBlurLayerPolicy has already put the view on a software layer for a blurring preset;
        // on a hardware canvas a BlurMaskFilter is silently ignored.
        boolean blurred = presetBlurPx > 0.25f;
        if (blurred) {
            textPaint.setMaskFilter(new android.graphics.BlurMaskFilter(
                    presetBlurPx, android.graphics.BlurMaskFilter.Blur.NORMAL));
        }
        // The PRESET's own glow (NEON_FLICKER), in the word's own fill colour. Captions have
        // no per-object glow to modulate, which is exactly why the preset supplies one — see
        // CaptionAnimator.Transform#glowPx. Drawn before the outline/fill passes and cleared
        // straight after, then the style's shadow is restored so the next word is unaffected.
        if (presetGlowPx > 0.25f) {
            textPaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(CaptionAnimator.applyAlpha(fillColor, animAlpha));
            textPaint.setShadowLayer(presetGlowPx, 0, 0,
                    CaptionAnimator.applyAlpha(fillColor, animAlpha));
            canvas.drawText(word, x, baseY, textPaint);
            applyStyleShadow(fontPx);
        }
        if (style.outline) {
            textPaint.setStyle(Paint.Style.STROKE);
            textPaint.setStrokeWidth(Math.max(1f, fontPx * 0.08f));
            textPaint.setColor(CaptionAnimator.applyAlpha(style.outlineColor, animAlpha));
            canvas.drawText(word, x, baseY, textPaint);
            textPaint.setStyle(Paint.Style.FILL);
        }
        textPaint.setColor(CaptionAnimator.applyAlpha(fillColor, animAlpha));
        canvas.drawText(word, x, baseY, textPaint);
        if (blurred) textPaint.setMaskFilter(null);
    }


    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (callback == null || !blockRect.contains(e.getX(), e.getY())) {
                    return false; // let touches pass through to the player
                }
                dragging = true;
                longPressFired = false;
                movedBeyondSlop = false;
                downX = e.getRawX();
                downY = e.getRawY();
                startCx = centerX;
                startCy = centerY;
                longPressHandler.postDelayed(longPressRunnable,
                        android.view.ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging || callback == null) return false;
                if (longPressFired) return true; // long-press won; ignore further movement
                float dx = e.getRawX() - downX;
                float dy = e.getRawY() - downY;
                if (!movedBeyondSlop && Math.hypot(dx, dy) > touchSlop) {
                    movedBeyondSlop = true;
                    longPressHandler.removeCallbacks(longPressRunnable); // it's a drag, not a hold
                }
                if (!movedBeyondSlop) return true; // tiny jitter — wait for tap/long-press
                RectF r = callback.getVideoContentRect();
                if (r.width() <= 0) return true;
                setCenter(startCx + dx / r.width(), startCy + dy / r.height());
                return true;
            }
            case MotionEvent.ACTION_UP:
                longPressHandler.removeCallbacks(longPressRunnable);
                if (dragging && callback != null) {
                    if (longPressFired) {
                        // already handled by the long-press (hide)
                    } else if (movedBeyondSlop) {
                        callback.onMoved();        // drag → reposition
                    } else {
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - lastTapUpMs <= 320) {
                            lastTapUpMs = 0;       // consume the pair
                            callback.onDoubleTapped(); // double-tap → advanced menu
                        } else {
                            lastTapUpMs = now;
                            callback.onTapped();   // tap → open properties
                        }
                    }
                }
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                longPressHandler.removeCallbacks(longPressRunnable);
                dragging = false;
                return true;
        }
        return false;
    }
}
