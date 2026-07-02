package com.fadcam.ui.faditor.transcript;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

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
        /** Long-pressed the caption — hide captions for this clip. */
        default void onLongPressed() {}
    }

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
    private final ValueAnimator emphasis = ValueAnimator.ofFloat(0f, 1f);
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
        emphasis.setDuration(300);
        emphasis.addUpdateListener(an -> {
            emphasisValue = (float) an.getAnimatedValue();
            invalidate();
        });
    }

    public void setData(@Nullable Transcript t, @NonNull CaptionStyle s, @NonNull Callback cb) {
        this.transcript = t;
        this.style = s;
        this.callback = cb;
        buildPhrases();
        activeWordIdx = -1;
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

    /** Update which word is active from the playback time, animating word changes. */
    public void setActiveSourceMs(long sourceMs) {
        if (transcript == null) return;
        int idx = transcript.indexAtOrBeforeTime(sourceMs);
        if (idx != activeWordIdx) {
            activeWordIdx = idx;
            emphasis.cancel();
            emphasis.setInterpolator(style.anim == CaptionStyle.Anim.ZOOM
                    ? new DecelerateInterpolator() : new OvershootInterpolator(2.2f));
            emphasis.start();
        }
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
        textPaint.setTypeface(style.bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        textPaint.setShadowLayer(fontPx * 0.12f, 0, fontPx * 0.05f, 0xDD000000);
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
            textPaint.setColor(style.baseColor);
            canvas.drawText(word, x, baseY, textPaint);
            return;
        }
        // Active word: colour + entrance animation driven by `emphasisValue` (0→1).
        float a = emphasisValue;
        float scale = 1.15f;
        float dy = 0f;
        switch (style.anim) {
            case ZOOM:   scale = lerp(1.6f, 1.15f, a); break;
            case BOUNCE: dy = -(1f - a) * fontPx * 0.5f; scale = 1.15f; break;
            case POP:
            default:     scale = 1.15f + (1f - a) * 0.35f; break;
        }
        float wordCx = x + ww / 2f;
        float wordCy = baseY - (textPaint.getFontMetrics().descent
                - textPaint.getFontMetrics().ascent) * 0.35f;
        canvas.save();
        canvas.translate(0, dy);
        canvas.scale(scale, scale, wordCx, wordCy);
        textPaint.setColor(style.activeColor);
        canvas.drawText(word, x, baseY, textPaint);
        canvas.restore();
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * Math.max(0f, Math.min(1f, t));
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
                        callback.onTapped();       // tap → open properties
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
