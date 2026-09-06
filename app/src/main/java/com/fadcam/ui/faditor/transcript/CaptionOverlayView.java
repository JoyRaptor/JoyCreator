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
        /**
         * Dragged a side edge of the caption box — the box WIDTH changed. The value is the
         * clamped fraction of the canvas width; the caller persists it on the binding
         * (SPEC_20260831_CAPTION_SLIDES: resizable bounding box).
         */
        default void onBoxResized(float boxWidthFraction) {}
    }

    /** Double-tap pairing state. */
    private long lastTapUpMs;

    @Nullable private Transcript transcript;
    @NonNull private CaptionStyle style = CaptionStyle.presets().get(0);
    @Nullable private Callback callback;

    private float centerX = 0.5f;
    private float centerY = 0.82f;
    private float sizeFraction = 0.060f;
    /** Caption box width as a fraction of the canvas width. 0.9 = the historical hard-coded look. */
    private float boxWidthFraction = 0.9f;
    /** Vertical growth anchor: 0=center, 1=top pinned (grows down), 2=bottom pinned (grows up). */
    private int anchor = 0;
    /** Text justification within the box: 0=center, 1=left, 2=right. */
    private int justify = 0;

    /**
     * FADE_KNOBS §2.5 caption opacity fade, as authored on the binding, plus the duration of the
     * span it is measured against (the caption's placement on the timeline) and the current
     * frame's time within that span. {@code spanLocalMs < 0} means "nobody has told this view
     * where it sits on the timeline", which yields a factor of 1 — the exact pre-fade behaviour.
     */
    private long capFadeInMs = 0L;
    private long capFadeOutMs = 0L;
    private long capSpanDurationMs = 0L;
    private long spanLocalMs = -1L;
    /** This frame's fade multiplier; recomputed at the top of {@link #onDraw}. */
    private float frameFade = 1f;

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Preview-only bounding-box chrome (SPEC_20260831_CAPTION_SLIDES). Never exported.
    private final Paint boxStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /**
     * SPEC_20260831_CAPTION_SLIDES_UX §7.1.2: the box chrome (white outline + green grips)
     * draws on the ACTIVE binding's overlay only — "there should only be one with grab handles
     * at any given time". Default false, so a rebuilt overlay stays chrome-less until the
     * rebuild marks the active one (legacy single-overlay paths set it true).
     */
    private boolean boxChromeActive;
    private final float density;

    /** Phrase grouping — shared with the export renderer so both agree where a phrase begins. */
    @NonNull private CaptionPhrases grouping = CaptionPhrases.of(null);

    // ── Fit cache for UNIFORM (SPEC §3.2: computed once per transcript+box+style, not per frame)
    private float cachedUniformSize = -1f;
    @Nullable private Transcript cachedUniformTranscript;
    private float cachedUniformBoxW = -1f, cachedUniformBoxH = -1f, cachedUniformAuthored = -1f;
    @Nullable private CaptionStyle.FitMode cachedUniformFitMode;
    private float cachedUniformMinScale = -1f;
    private int cachedUniformMaxLines = -1, cachedUniformMaxWords = -1;
    private boolean cachedUniformPill;
    // The FONT is an input to the fit — a wider face needs a smaller size — so it belongs in the
    // key. Without these two the cache survived a font or bold change and the preview kept the
    // OLD face's fitted size while the export (which builds a fresh renderer) fitted correctly:
    // one tap in the Style tab put preview and export out of agreement.
    @Nullable private String cachedUniformFontKey;
    private boolean cachedUniformBold;

    // ── Fit cache for PER_CUE — the sibling of the UNIFORM one above.
    // A cue's fitted size is a pure function of (its words, the box, the authored size, the
    // style), and a cue lasts for tens or hundreds of frames — yet both draw paths ran
    // CaptionFit.fitSizeForWords (a binary search that measures every word at every trial size)
    // INSIDE onDraw, once per frame, to arrive at the same number every time. Invalidated
    // everywhere cachedUniformSize is, plus unconditionally on a style change.
    private int cachedCuePhraseIdx = Integer.MIN_VALUE;
    private float cachedCueSize = -1f;
    private float cachedCueBoxW = -1f, cachedCueBoxH = -1f, cachedCueAuthored = -1f;

    private int activeWordIdx = -1;
    /** Eased entrance progress of the active word, evaluated from MEDIA time by
     *  {@link CaptionAnimator} — see {@link #setActiveSourceMs}. */
    private float emphasisValue = 1f;
    /** The playhead in SOURCE ms, kept so {@link #onDraw} can evaluate per-unit animation. */
    private long sourceMs = 0L;
    /** True once a playhead time has ever been pushed in — see {@link #setData}. */
    private boolean sourceMsKnown;
    /**
     * {@link Transcript#contentSignature()} of the words the current {@link #grouping} (and the
     * fit cache) were built from. A Transcript is edited IN PLACE, so identity is not a version:
     * striking a word, retiming it or forcing a line break changes what must be drawn while the
     * object stays the same. Re-grouping when this moves is what makes a transcript edit show up
     * on the very next frame instead of "after a while".
     */
    private int groupedSignature = Integer.MIN_VALUE;

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
    // Box-edge resize (SPEC_20260831_CAPTION_SLIDES): a touch on the block's left/right edge
    // band drags the BOX WIDTH instead of moving the caption. 0 = not resizing, −1 = left, 1 = right.
    private int resizeSide;
    private float startBoxW;
    /** Touch band (px) at each edge of the block that starts a width resize. */
    private static final float RESIZE_EDGE_DP = 20f;
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
        boxStrokePaint.setStyle(Paint.Style.STROKE);
        boxStrokePaint.setStrokeWidth(Math.max(1f, density * 0.8f));
        gripPaint.setStyle(Paint.Style.FILL);
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
        // SPEC_20260831_CAPTION_SLIDES_UX: hold a SNAPSHOT of the style, never the caller's
        // live object — the style store mutates drafts in place, and a shared reference made
        // every change-detection comparison in setStyle read "no change" (the staleness JoyRaptor
        // reported: settings only landed after a scrub/play rebuilt the views).
        this.style = s.copyAs(s.id, s.label);
        this.callback = cb;
        grouping = this.style.slideGroup
                ? CaptionPhrases.ofSlides(t)
                : CaptionPhrases.of(t, this.style.maxWords);
        cachedUniformSize = -1f;
        cachedCueSize = -1f;
        groupedSignature = t != null ? t.contentSignature() : Integer.MIN_VALUE;
        if (sameTranscript) {
            activeWordIdx = Math.min(activeWordIdx, grouping.wordPhrase.length - 1);
        } else if (t != null && sourceMsKnown) {
            // A DIFFERENT Transcript instance is still the same moment in time. Blanking the
            // active word here is what made a caption disappear the instant it was rebuilt
            // (a re-window after a transcript edit, a multi-binding rebuild from the drawer)
            // and stay blank until the next playhead tick happened to arrive — which, with the
            // player paused, is never. Re-resolve from the playhead we already know instead.
            activeWordIdx = t.indexAtOrBeforeTime(sourceMs);
        } else {
            activeWordIdx = -1;
        }
        invalidate();
    }

    /**
     * Re-group and drop the fit cache when the WORDS changed underneath us.
     *
     * <p>Everything else that rebuilds {@link #grouping} is an explicit call — {@link #setData},
     * {@link #setStyle}. In-place transcript edits make no such call: the strike drag mutates
     * {@code TranscriptWord.struck} on objects this view already holds, and the caption kept the
     * pre-edit phrasing and the pre-edit uniform size until something unrelated re-bound it.</p>
     */
    private void ensureFreshContent() {
        if (transcript == null) return;
        int sig = transcript.contentSignature();
        if (sig == groupedSignature) return;
        groupedSignature = sig;
        grouping = style.slideGroup
                ? CaptionPhrases.ofSlides(transcript)
                : CaptionPhrases.of(transcript, style.maxWords);
        cachedUniformSize = -1f;
        cachedCueSize = -1f;
        if (activeWordIdx >= grouping.wordPhrase.length) {
            activeWordIdx = grouping.wordPhrase.length - 1;
        }
        if (activeWordIdx < 0 && sourceMsKnown) {
            activeWordIdx = transcript.indexAtOrBeforeTime(sourceMs);
        }
    }

    public void setStyle(@NonNull CaptionStyle s) {
        // The word limit travels with the style, so a style change - or a turn of the dial -
        // has to re-group. Without this the caption keeps the previous style's phrasing and the
        // control looks dead until something else happens to rebuild it. Slide grouping changes
        // the GROUPING RULE itself, so it re-groups too.
        boolean regroup = s.maxWords != this.style.maxWords
                || s.slideGroup != this.style.slideGroup;
        boolean fitChanged = s.fitMode != this.style.fitMode
                // Font and weight change the measured width of every word, so they change the fit.
                || !java.util.Objects.equals(s.fontKey, this.style.fontKey)
                || s.bold != this.style.bold
                || s.fitMinScale != this.style.fitMinScale
                || s.fitMaxLines != this.style.fitMaxLines
                || s.pill != this.style.pill
                // SPEC_20260831_CAPTION_SLIDES_UX: the truncate toggle changes the box the
                // uniform fit is computed against, so the cached size must re-compute.
                || s.fitTruncate != this.style.fitTruncate;
        this.style = s.copyAs(s.id, s.label);
        // BUGFIX: the regroup used to always build the KARAOKE grouping — even when slideGroup
        // had just turned ON — so the slide toggle looked dead until a full rebuild re-ran
        // setData. Branch on the mode, exactly like setData does.
        if (regroup) {
            grouping = this.style.slideGroup
                    ? CaptionPhrases.ofSlides(transcript)
                    : CaptionPhrases.of(transcript, this.style.maxWords);
            groupedSignature = transcript != null ? transcript.contentSignature() : Integer.MIN_VALUE;
        }
        if (regroup || fitChanged) cachedUniformSize = -1f;
        cachedCueSize = -1f;
        invalidate();
    }

    @NonNull
    private CaptionFit.Measurer createMeasurer() {
        // Resolve the typeface ONCE for the whole fit, not once per measured word. The fitter
        // calls widthOf on the order of 10^5 times for a long transcript, and style.typeface()
        // is a family switch plus a Typeface.create() on every one of them. Same face for every
        // measurement in a fit, so hoisting it changes no result.
        final Typeface fitFace = style.typeface();
        return new CaptionFit.Measurer() {
            @Override public float widthOf(@NonNull String text, float textSizePx) {
                textPaint.setTextSize(textSizePx);
                textPaint.setTypeface(fitFace);
                return textPaint.measureText(text);
            }
            @Override public float lineHeight(float textSizePx) {
                textPaint.setTextSize(textSizePx);
                textPaint.setTypeface(fitFace);
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                return (fm.descent - fm.ascent) * 1.15f;
            }
        };
    }

    /**
     * The PER_CUE fitted size for one phrase, computed once and reused for every frame the cue is
     * on screen. Keyed on the cue index and every other input to the fit; the style is folded in
     * by the unconditional invalidation in {@link #setStyle}, and a transcript edit by the one in
     * {@link #ensureFreshContent}.
     */
    private float getCueFittedSize(int phraseIdx, @NonNull List<String> words,
                                   float authoredSize, float boxW, float boxH) {
        if (cachedCueSize > 0f && cachedCuePhraseIdx == phraseIdx
                && cachedCueBoxW == boxW && cachedCueBoxH == boxH
                && cachedCueAuthored == authoredSize) {
            return cachedCueSize;
        }
        float fitted = CaptionFit.fitSizeForWords(words, authoredSize, boxW, boxH,
                style.fitMinScale, style.fitMaxLines, createMeasurer(), style.pill);
        cachedCuePhraseIdx = phraseIdx;
        cachedCueSize = fitted;
        cachedCueBoxW = boxW; cachedCueBoxH = boxH; cachedCueAuthored = authoredSize;
        return fitted;
    }

    private float getUniformFittedSize(float authoredSize, float boxW, float boxH) {
        if (transcript == null || style.fitMode != CaptionStyle.FitMode.UNIFORM) return authoredSize;
        if (cachedUniformSize > 0
                && cachedUniformTranscript == transcript
                && cachedUniformBoxW == boxW && cachedUniformBoxH == boxH
                && cachedUniformAuthored == authoredSize
                && cachedUniformFitMode == style.fitMode
                && cachedUniformMinScale == style.fitMinScale
                && cachedUniformMaxLines == style.fitMaxLines
                && cachedUniformPill == style.pill
                && cachedUniformMaxWords == style.maxWords
                && java.util.Objects.equals(cachedUniformFontKey, style.fontKey)
                && cachedUniformBold == style.bold) {
            return cachedUniformSize;
        }
        CaptionFit.Measurer m = createMeasurer();
        float fitted = CaptionFit.uniformSizeForTranscript(transcript, style, authoredSize, boxW, boxH, m);
        cachedUniformSize = fitted;
        cachedUniformTranscript = transcript;
        cachedUniformBoxW = boxW; cachedUniformBoxH = boxH; cachedUniformAuthored = authoredSize;
        cachedUniformFitMode = style.fitMode; cachedUniformMinScale = style.fitMinScale;
        cachedUniformMaxLines = style.fitMaxLines; cachedUniformPill = style.pill;
        cachedUniformMaxWords = style.maxWords;
        cachedUniformFontKey = style.fontKey; cachedUniformBold = style.bold;
        return fitted;
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

    /** The caption box width as a fraction of the canvas width (0.3–1.0, default 0.9). */
    public float getBoxWidthFraction() { return boxWidthFraction; }

    public void setBoxWidthFraction(float f) {
        float clamped = Math.max(0.3f, Math.min(1f, f));
        if (clamped == boxWidthFraction) return;
        boxWidthFraction = clamped;
        cachedUniformSize = -1f;
        cachedCueSize = -1f;
        invalidate();
    }

    public void setAnchor(int a) {
        int clamped = Math.max(0, Math.min(2, a));
        if (clamped == anchor) return;
        anchor = clamped;
        invalidate();
    }

    public void setJustify(int j) {
        int clamped = Math.max(0, Math.min(2, j));
        if (clamped == justify) return;
        justify = clamped;
        invalidate();
    }

    /**
     * FADE_KNOBS §2.5: the binding's opacity fade and the span it is measured over — the
     * caption's own placement on the timeline (a clip caption's span IS its clip's, see
     * {@code CaptionSpanRef}). Must be given the SAME two durations the export renderer gets
     * ({@code CaptionExportRenderer#setCaptionFade}) or the preview and the file disagree.
     */
    public void setCaptionFade(long fadeInMs, long fadeOutMs, long spanDurationMs) {
        long in = Math.max(0L, fadeInMs);
        long out = Math.max(0L, fadeOutMs);
        long dur = Math.max(0L, spanDurationMs);
        if (in == capFadeInMs && out == capFadeOutMs && dur == capSpanDurationMs) return;
        capFadeInMs = in;
        capFadeOutMs = out;
        capSpanDurationMs = dur;
        invalidate();
    }

    /**
     * Opacity multiplier at a time LOCAL to the caption's span — same shape as
     * {@code WaveformOverlayInstance#fadeFactorAt}. Implemented here rather than on
     * {@code Clip.CaptionBinding} because the model file belongs to another lane; the identical
     * arithmetic lives on {@code CaptionExportRenderer}, and the two must stay in step.
     */
    private float fadeFactorAt(long localMs) {
        if (capFadeInMs <= 0L && capFadeOutMs <= 0L) return 1f;
        if (localMs < 0L) return 1f;
        long dur = capSpanDurationMs > 0L ? capSpanDurationMs : localMs + capFadeOutMs + 1L;
        long local = Math.max(0L, localMs);
        if (capFadeInMs > 0L && local < capFadeInMs) {
            return Math.max(0f, (float) local / (float) capFadeInMs);
        }
        if (capFadeOutMs > 0L && local > dur - capFadeOutMs) {
            return Math.max(0f, Math.min(1f, (float) (dur - local) / (float) capFadeOutMs));
        }
        return 1f;
    }

    /** The style's drop-shadow colour, faded WITH the glyphs — see CaptionExportRenderer. */
    private int styleShadowColor() {
        return CaptionAnimator.applyAlpha(0xDD000000, frameFade);
    }

    /**
     * SPEC_20260831_CAPTION_SLIDES_UX §7.1.2: mark this overlay as the active caption — the one
     * whose box chrome (white outline + green grips) draws. The rebuild paths set it per
     * binding index; the retarget paths refresh it in place across the container's children.
     */
    public void setBoxChromeActive(boolean a) {
        if (boxChromeActive != a) {
            boxChromeActive = a;
            invalidate();
        }
    }

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
        cachedUniformSize = -1f;
        cachedCueSize = -1f;
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
        setActiveSourceMs(sourceMs, -1L);
    }

    /**
     * As {@link #setActiveSourceMs(long)}, plus the playhead's time LOCAL to the caption's span
     * on the timeline — the clock the binding's opacity fade is measured against. The one-argument
     * form passes -1 ("no span clock"), which holds the fade at 1.
     */
    public void setActiveSourceMs(long sourceMs, long spanLocalMs) {
        this.spanLocalMs = spanLocalMs;
        this.sourceMs = sourceMs;
        this.sourceMsKnown = true;
        if (transcript == null) return;
        ensureFreshContent();
        int idx = transcript.indexAtOrBeforeTime(sourceMs);
        activeWordIdx = idx;
        emphasisValue = idx >= 0 && idx < transcript.words.size()
                ? CaptionAnimator.emphasis(style, sourceMs, transcript.words.get(idx).startMs)
                : 1f;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        // Tap routing: blockRect is the hit-test rect for drag/select. Every path below that
        // draws re-populates it (both the karaoke and slide branches set it unconditionally in
        // their pill/no-pill halves), so clearing it here means a caption that draws NOTHING
        // this frame stops swallowing taps aimed at whatever is underneath it.
        blockRect.setEmpty();
        if (transcript == null || callback == null) return;
        ensureFreshContent();
        // FADE_KNOBS §2.5 — evaluated ONCE per frame, before either draw path, so the karaoke and
        // slide paths cannot fade differently from each other or from the export.
        frameFade = fadeFactorAt(spanLocalMs);
        if (activeWordIdx < 0) return;
        int phraseIdx = grouping.phraseOf(activeWordIdx);
        if (phraseIdx < 0) return;
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return;

        // Struck (removed) words are not drawn, so they must not hold an animation slot either.
        List<Integer> visible = grouping.visibleWords(phraseIdx);
        if (visible.isEmpty()) return;

        // SLIDE mode: one timing entry = one wrapped box (SPEC_20260831_CAPTION_SLIDES).
        if (grouping.slideMode) {
            drawSlide(canvas, r, phraseIdx);
            drawBoxChrome(canvas);
            return;
        }

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

        float authoredPx = sizeFraction * r.height();
        float boxW = r.width() * boxWidthFraction;
        float boxH = r.height() * 0.9f;
        // SPEC_20260831_CAPTION_SLIDES_UX: truncation off = width-only fit — an effectively
        // infinite box height so only width constrains and the column may run off screen.
        float fitBoxH = style.fitTruncate ? r.height() * 0.9f : r.height() * 8f;
        float fontPx;
        if (style.fitMode == CaptionStyle.FitMode.OFF) {
            fontPx = authoredPx;
        } else if (style.fitMode == CaptionStyle.FitMode.UNIFORM) {
            fontPx = getUniformFittedSize(authoredPx, boxW, fitBoxH);
        } else {
            List<String> phraseWords = new ArrayList<>(visible.size());
            for (int wi : visible) phraseWords.add(transcript.words.get(wi).text);
            fontPx = getCueFittedSize(phraseIdx, phraseWords, authoredPx, boxW, fitBoxH);
        }
        textPaint.setTextSize(fontPx);
        textPaint.setTypeface(style.typeface());
        if (style.shadow) {
            textPaint.setShadowLayer(fontPx * 0.12f, 0, fontPx * 0.05f, styleShadowColor());
        } else {
            textPaint.clearShadowLayer();
        }
        float space = textPaint.measureText(" ");

        // Lay the visible words of this phrase into centred lines (same wrap as CaptionFit).
        float maxW = boxW;
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

        // SPEC_20260831_CAPTION_SLIDES_UX: with a fit mode active and truncation on, a block
        // that still overflows the box at the fit floor drops its trailing lines and marks the
        // cut with an ellipsis on the last kept word. Kept lines keep their word indices, so
        // karaoke emphasis and per-word animation are untouched; dropped lines simply don't draw.
        int ellipsisWordIdx = -1;
        if (style.fitTruncate && style.fitMode != CaptionStyle.FitMode.OFF && totalH > boxH) {
            while (lines.size() > 1 && lineH * lines.size() > boxH) {
                lines.remove(lines.size() - 1);
            }
            if (!lines.isEmpty()) {
                List<Integer> last = lines.get(lines.size() - 1);
                ellipsisWordIdx = last.get(last.size() - 1);
            }
            totalH = lineH * lines.size();
        }

        float cx = r.left + centerX * r.width();
        float cy = r.top + centerY * r.height();
        // Anchor: the SAME expression drawSlide uses. The karaoke path ignored it entirely, so
        // "grow down from the top" behaved as "grow both ways" for every non-slide style.
        float top;
        if (anchor == 1) top = cy;
        else if (anchor == 2) top = cy - totalH;
        else top = cy - totalH / 2f;

        // Background pill spanning the widest line.
        if (style.pill) {
            float widest = 0;
            for (List<Integer> ln : lines) {
                float w = 0;
                for (int k = 0; k < ln.size(); k++) {
                    w += (k > 0 ? space : 0)
                            + textPaint.measureText(displayWord(ln.get(k), ellipsisWordIdx));
                }
                widest = Math.max(widest, w);
            }
            float padH = fontPx * 0.4f, padV = fontPx * 0.25f;
            float blockLeft = blockLeftFor(cx, boxW, widest);
            blockRect.set(blockLeft - padH, top - padV,
                    blockLeft + widest + padH, top + totalH + padV);
            pillPaint.setColor(CaptionAnimator.applyAlpha(style.pillColor, frameFade));
            canvas.drawRoundRect(blockRect, fontPx * style.pillCornerScale,
                    fontPx * style.pillCornerScale, pillPaint);
        } else {
            float widest = 0;
            for (List<Integer> ln : lines) {
                float w = 0;
                for (int k = 0; k < ln.size(); k++) {
                    w += (k > 0 ? space : 0)
                            + textPaint.measureText(displayWord(ln.get(k), ellipsisWordIdx));
                }
                widest = Math.max(widest, w);
            }
            float blockLeft = blockLeftFor(cx, boxW, widest);
            blockRect.set(blockLeft, top, blockLeft + widest, top + totalH);
        }

        // Draw each line centred, emphasising the active word. `unitPos` counts the visible
        // words of the phrase in order — lines are built from `visible` in order, so a running
        // counter is the same sequence the animator indexes by.
        float baseY = top - fm.ascent;
        int unitPos = 0;
        for (List<Integer> ln : lines) {
            float w = 0;
            for (int k = 0; k < ln.size(); k++) {
                w += (k > 0 ? space : 0)
                        + textPaint.measureText(displayWord(ln.get(k), ellipsisWordIdx));
            }
            // Justify: the SAME expression drawSlide uses. The karaoke path always centred, so
            // the Align chip looked dead on every non-slide style.
            float x;
            if (justify == 1) x = cx - boxW / 2f;
            else if (justify == 2) x = cx + boxW / 2f - w;
            else x = cx - w / 2f;
            for (int k = 0; k < ln.size(); k++) {
                int wi = ln.get(k);
                String word = displayWord(wi, ellipsisWordIdx);
                float ww = textPaint.measureText(word);
                boolean active = wi == activeWordIdx;
                drawWord(canvas, word, x, baseY, ww, active, fontPx, unitPos++);
                x += ww + space;
            }
            baseY += lineH;
        }
        drawBoxChrome(canvas);
    }

    /**
     * Displayed text of transcript word {@code wi} — SPEC_20260831_CAPTION_SLIDES_UX truncation.
     * The ellipsis is appended to the LAST kept word's DISPLAYED text only (the transcript is
     * never mutated, so karaoke indices and animation stay intact); a word that already ends
     * with "…" is left alone.
     */
    @NonNull
    private String displayWord(int wi, int ellipsisWordIdx) {
        String t = transcript.words.get(wi).text;
        if (wi != ellipsisWordIdx || t.endsWith("…")) return t;
        return t + "…";
    }

    /**
     * SLIDE draw path (SPEC_20260831_CAPTION_SLIDES): one timing entry = one wrapped box shown
     * for the entry's whole span. Words come from {@link CaptionPhrases#layoutWords}, so an
     * entry holding a whole paragraph wraps BY WORD inside the box and auto-fit shrinks it to
     * fit. There is no karaoke emphasis — the slide reads as one static block — but the preset
     * entrance/exit still runs per word across the slide's span.
     */
    private void drawSlide(@NonNull Canvas canvas, @NonNull RectF r, int phraseIdx) {
        List<String> words = grouping.layoutWords(phraseIdx);
        if (words.isEmpty()) return;

        long[] span = grouping.spanMs(phraseIdx);
        animWords.clear();
        animWords.addAll(words);
        animUnitCount = CaptionAnimator.unitCount(animWords, animGran);
        animSpanStart = span != null ? span[0] : 0L;
        animSpanEnd = span != null ? span[1] : 1L;
        animInEff = CaptionAnimator.zoneForSpan(animInPct, animSpanEnd - animSpanStart);
        animOutEff = CaptionAnimator.zoneForSpan(animOutPct, animSpanEnd - animSpanStart);

        float authoredPx = sizeFraction * r.height();
        float boxW = r.width() * boxWidthFraction;
        float boxH = r.height() * 0.9f;
        // SPEC_20260831_CAPTION_SLIDES_UX: truncation off = width-only fit — an effectively
        // infinite box height so only width constrains and the column may run off screen.
        float fitBoxH = style.fitTruncate ? r.height() * 0.9f : r.height() * 8f;
        float fontPx;
        if (style.fitMode == CaptionStyle.FitMode.OFF) {
            fontPx = authoredPx;
        } else if (style.fitMode == CaptionStyle.FitMode.UNIFORM) {
            fontPx = getUniformFittedSize(authoredPx, boxW, fitBoxH);
        } else {
            fontPx = getCueFittedSize(phraseIdx, words, authoredPx, boxW, fitBoxH);
        }
        textPaint.setTextSize(fontPx);
        textPaint.setTypeface(style.typeface());
        if (style.shadow) {
            textPaint.setShadowLayer(fontPx * 0.12f, 0, fontPx * 0.05f, styleShadowColor());
        } else {
            textPaint.clearShadowLayer();
        }
        float space = textPaint.measureText(" ");

        // Wrap into centred lines of layout words.
        List<List<String>> lines = new ArrayList<>();
        List<String> line = new ArrayList<>();
        float lineW = 0;
        for (String w : words) {
            float ww = textPaint.measureText(w);
            if (!line.isEmpty() && lineW + space + ww > boxW) {
                lines.add(line);
                line = new ArrayList<>();
                lineW = 0;
            }
            line.add(w);
            lineW += (line.size() > 1 ? space : 0) + ww;
        }
        if (!line.isEmpty()) lines.add(line);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float lineH = (fm.descent - fm.ascent) * 1.15f;
        float totalH = lineH * lines.size();

        // SPEC_20260831_CAPTION_SLIDES_UX: same truncation rule as the karaoke path — drop
        // trailing lines until the block fits the box, then ellipsize the last kept word.
        // Slide lines hold the word strings themselves, so the suffix is appended in place;
        // the layout-word source list is untouched.
        if (style.fitTruncate && style.fitMode != CaptionStyle.FitMode.OFF && totalH > boxH) {
            while (lines.size() > 1 && lineH * lines.size() > boxH) {
                lines.remove(lines.size() - 1);
            }
            if (!lines.isEmpty()) {
                List<String> last = lines.get(lines.size() - 1);
                String lastWord = last.get(last.size() - 1);
                if (!lastWord.endsWith("…")) last.set(last.size() - 1, lastWord + "…");
            }
            totalH = lineH * lines.size();
        }

        float cx = r.left + centerX * r.width();
        float cy = r.top + centerY * r.height();
        // Anchor: which part of the box sits at the anchor point when the height changes —
        // center grows both ways, top pinned grows down, bottom pinned grows up.
        float top;
        if (anchor == 1) top = cy;
        else if (anchor == 2) top = cy - totalH;
        else top = cy - totalH / 2f;

        float widest = 0;
        for (List<String> ln : lines) {
            float w = 0;
            for (int k = 0; k < ln.size(); k++) {
                w += (k > 0 ? space : 0) + textPaint.measureText(ln.get(k));
            }
            widest = Math.max(widest, w);
        }
        float blockLeft = blockLeftFor(cx, boxW, widest);
        if (style.pill) {
            float padH = fontPx * 0.4f, padV = fontPx * 0.25f;
            blockRect.set(blockLeft - padH, top - padV,
                    blockLeft + widest + padH, top + totalH + padV);
            pillPaint.setColor(CaptionAnimator.applyAlpha(style.pillColor, frameFade));
            canvas.drawRoundRect(blockRect, fontPx * style.pillCornerScale,
                    fontPx * style.pillCornerScale, pillPaint);
        } else {
            blockRect.set(blockLeft, top, blockLeft + widest, top + totalH);
        }

        float baseY = top - fm.ascent;
        int unitPos = 0;
        for (List<String> ln : lines) {
            float w = 0;
            for (int k = 0; k < ln.size(); k++) {
                w += (k > 0 ? space : 0) + textPaint.measureText(ln.get(k));
            }
            float x;
            if (justify == 1) x = cx - boxW / 2f;
            else if (justify == 2) x = cx + boxW / 2f - w;
            else x = cx - w / 2f;
            for (int k = 0; k < ln.size(); k++) {
                String word = ln.get(k);
                float ww = textPaint.measureText(word);
                drawWord(canvas, word, x, baseY, ww, false, fontPx, unitPos++);
                x += ww + space;
            }
            baseY += lineH;
        }
        drawBoxChrome(canvas);
    }

    /**
     * Left edge of the block the glyphs ACTUALLY occupy, for a block whose widest line measures
     * {@code widest}. Both draw paths place each line at
     * {@code justify==1 ? cx - boxW/2 : justify==2 ? cx + boxW/2 - lineW : cx - lineW/2}; the
     * widest line is therefore the one that defines the block edges, and only centre justify
     * puts them at {@code cx +/- widest/2}. The pill, the outline, the grips and the tap
     * hit-rect all derive from this so they can never drift off the text under left/right
     * justification.
     */
    private float blockLeftFor(float cx, float boxW, float widest) {
        if (justify == 1) return cx - boxW / 2f;
        if (justify == 2) return cx + boxW / 2f - widest;
        return cx - widest / 2f;
    }

    /**
     * Preview-only bounding-box chrome: a faint outline around the caption block plus green
     * grip bars on the left/right edges marking the width-resize bands. Editing affordance —
     * the export renderers never draw this.
     */
    private void drawBoxChrome(@NonNull Canvas canvas) {
        // SPEC_20260831_CAPTION_SLIDES_UX §7.1.2: inactive overlays draw NOTHING — exactly one
        // caption on screen carries the outline + grips at any moment.
        if (!boxChromeActive) return;
        if (blockRect.isEmpty()) return;
        boxStrokePaint.setColor(0x50FFFFFF);
        canvas.drawRoundRect(blockRect, 8f * density, 8f * density, boxStrokePaint);
        float gripW = 4f * density;
        float gripH = Math.min(blockRect.height(), 28f * density);
        float gripTop = blockRect.centerY() - gripH / 2f;
        gripPaint.setColor(0xD94CAF50);
        canvas.drawRoundRect(blockRect.left - gripW / 2f, gripTop,
                blockRect.left + gripW / 2f, gripTop + gripH, gripW / 2f, gripW / 2f, gripPaint);
        canvas.drawRoundRect(blockRect.right - gripW / 2f, gripTop,
                blockRect.right + gripW / 2f, gripTop + gripH, gripW / 2f, gripW / 2f, gripPaint);
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
            textPaint.setShadowLayer(fontPx * 0.12f, 0, fontPx * 0.05f, styleShadowColor());
        } else {
            textPaint.clearShadowLayer();
        }
    }

    private void paintWord(Canvas canvas, String word, float x, float baseY,
                           int fillColor, float fontPx, float animAlphaIn, float presetGlowPx,
                           float presetBlurPx) {
        // FADE_KNOBS §2.5: the binding's opacity fade rides the SAME alpha the preset animation
        // already uses, so fill, outline and glow all fade together and there is no second alpha
        // chain that could drift from the export's.
        final float animAlpha = animAlphaIn * frameFade;
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
                // SPEC_20260831_CAPTION_SLIDES_UX §7.1.1 — hit-test BEFORE anything else. Every
                // overlay is MATCH_PARENT and stacked, so this view only keeps a gesture when
                // the touch lands on ITS caption block (inflated by a 12dp ring); returning
                // false lets the container dispatch fall through to the overlay beneath, so the
                // topmost view whose CAPTION you touch wins (SPEC_20260829_CAPTION_LAYERS §3.3).
                float hitSlop = 12f * density;
                if (blockRect.isEmpty()
                        || e.getX() < blockRect.left - hitSlop
                        || e.getX() > blockRect.right + hitSlop
                        || e.getY() < blockRect.top - hitSlop
                        || e.getY() > blockRect.bottom + hitSlop) {
                    return false; // outside this caption — pass through to the view beneath
                }
                if (callback == null) {
                    return false; // let touches pass through to the player
                }
                dragging = true;
                longPressFired = false;
                movedBeyondSlop = false;
                downX = e.getRawX();
                downY = e.getRawY();
                startCx = centerX;
                startCy = centerY;
                // Edge band → resize the box, not move the caption.
                float edge = RESIZE_EDGE_DP * density;
                if (blockRect.width() > 0 && e.getX() >= blockRect.right - edge) {
                    resizeSide = 1;
                } else if (blockRect.width() > 0 && e.getX() <= blockRect.left + edge) {
                    resizeSide = -1;
                } else {
                    resizeSide = 0;
                }
                if (resizeSide != 0) {
                    startBoxW = boxWidthFraction;
                    longPressHandler.removeCallbacks(longPressRunnable);
                    return true; // no long-press / tap semantics during a resize
                }
                longPressHandler.postDelayed(longPressRunnable,
                        android.view.ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging || callback == null) return false;
                if (resizeSide != 0) {
                    RectF rc = callback.getVideoContentRect();
                    if (rc.width() <= 0) return true;
                    float dx = e.getRawX() - downX;
                    // The box is centred, so an edge drag grows/shrinks it on both sides;
                    // the left edge runs inverted (dragging it inward widens the box).
                    float cand = startBoxW + (resizeSide > 0 ? dx : -dx) * 2f / rc.width();
                    setBoxWidthFraction(cand);
                    return true;
                }
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
                    if (resizeSide != 0) {
                        callback.onBoxResized(boxWidthFraction); // persist the new width
                    } else if (longPressFired) {
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
                resizeSide = 0;
                return true;
            case MotionEvent.ACTION_CANCEL:
                longPressHandler.removeCallbacks(longPressRunnable);
                dragging = false;
                resizeSide = 0;
                return true;
        }
        return false;
    }
}
