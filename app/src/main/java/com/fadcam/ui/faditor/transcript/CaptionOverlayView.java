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

    // Phrase grouping
    private int[] wordPhrase = new int[0];
    private final List<int[]> phrases = new ArrayList<>(); // {startIdx, endIdxInclusive}

    private int activeWordIdx = -1;
    /** Eased entrance progress of the active word, evaluated from MEDIA time by
     *  {@link CaptionAnimator} — see {@link #setActiveSourceMs}. */
    private float emphasisValue = 1f;

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
        buildPhrases();
        if (sameTranscript) {
            activeWordIdx = Math.min(activeWordIdx, wordPhrase.length - 1);
        } else {
            activeWordIdx = -1;
        }
        invalidate();
    }

    public void setStyle(@NonNull CaptionStyle s) {
        this.style = s;
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
     * Group words into short phrases. Break on long gaps, every ~6 words, or a
     * forced line-break flag on the previous word.
     */
    private void buildPhrases() {
        phrases.clear();
        if (transcript == null || transcript.words.isEmpty()) {
            wordPhrase = new int[0];
            return;
        }
        int n = transcript.words.size();
        wordPhrase = new int[n];
        int start = 0;
        for (int i = 1; i <= n; i++) {
            boolean brk = i == n;
            if (!brk) {
                long gap = transcript.words.get(i).startMs - transcript.words.get(i - 1).endMs;
                brk = gap > 550 || (i - start) >= 6
                        || transcript.words.get(i - 1).forceLineBreakAfter;
            }
            if (brk) {
                int phraseIdx = phrases.size();
                phrases.add(new int[]{start, i - 1});
                for (int j = start; j < i; j++) wordPhrase[j] = phraseIdx;
                start = i;
            }
        }
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
        if (activeWordIdx >= wordPhrase.length) return;
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return;

        int phraseIdx = wordPhrase[activeWordIdx];
        int[] phrase = phrases.get(phraseIdx);

        // Struck (removed) words should not appear in captions.
        List<Integer> visible = new ArrayList<>();
        for (int i = phrase[0]; i <= phrase[1]; i++) {
            if (!transcript.words.get(i).struck) visible.add(i);
        }
        if (visible.isEmpty()) return;

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

        // Draw each line centred, emphasising the active word.
        float baseY = top - fm.ascent;
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
                drawWord(canvas, word, x, baseY, ww, lineH, active, fontPx);
                x += ww + space;
            }
            baseY += lineH;
        }
    }

    private void drawWord(Canvas canvas, String word, float x, float baseY,
                          float ww, float lineH, boolean active, float fontPx) {
        if (!active) {
            paintWord(canvas, word, x, baseY, style.baseColor, fontPx);
            return;
        }
        // Active word: colour + entrance animation, evaluated by the ONE authority at the
        // current MEDIA time (CaptionAnimator) rather than by a wall-clock ValueAnimator.
        CaptionAnimator.Transform tf = CaptionAnimator.transform(style, emphasisValue, fontPx);
        float scale = tf.scale;
        float dy = tf.dy;
        float wordCx = x + ww / 2f;
        float wordCy = baseY - (textPaint.getFontMetrics().descent
                - textPaint.getFontMetrics().ascent) * 0.35f;
        canvas.save();
        canvas.translate(0, dy);
        canvas.scale(scale, scale, wordCx, wordCy);
        paintWord(canvas, word, x, baseY, style.activeColor, fontPx);
        canvas.restore();
    }

    /** Fill pass plus optional stroke-outline pass, sharing one paint. */
    private void paintWord(Canvas canvas, String word, float x, float baseY,
                           int fillColor, float fontPx) {
        if (style.outline) {
            textPaint.setStyle(Paint.Style.STROKE);
            textPaint.setStrokeWidth(Math.max(1f, fontPx * 0.08f));
            textPaint.setColor(style.outlineColor);
            canvas.drawText(word, x, baseY, textPaint);
            textPaint.setStyle(Paint.Style.FILL);
        }
        textPaint.setColor(fillColor);
        canvas.drawText(word, x, baseY, textPaint);
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
