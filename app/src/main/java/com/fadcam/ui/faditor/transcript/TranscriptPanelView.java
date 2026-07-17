package com.fadcam.ui.faditor.transcript;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Efficient, self-drawn transcript view: wraps words into lines, hit-tests each
 * word, and supports the mobile editing gestures:
 *
 * <ul>
 *   <li>Tap a word → seek there.</li>
 *   <li>Long-press a word → toggle its strikethrough (delete / restore).</li>
 *   <li>Long-press then drag → "paint" the strike across many words.</li>
 *   <li>Vertical drag (without a long-press) → scroll.</li>
 * </ul>
 *
 * Struck words stay visible (grey, lined out) so editing is lossless.
 */
public class TranscriptPanelView extends View {

    public interface Listener {
        /** User tapped a word — seek the player to this source time. */
        void onSeekToMs(long sourceMs);
        /** Strike state changed — re-derive the timeline / persist. */
        void onStrikesChanged();
        /**
         * User long-pressed a word (without dragging) — open the manual edit dialog so
         * they can correct the word's text (the manual companion to the AI
         * {@code correct_transcript} tool). The host shows a dialog and calls
         * {@link #editWord(int, String)} with the result.
         */
        default void onEditWord(int index, @NonNull String currentText) {}
        /** A forced line-break flag changed — host should schedule an auto-save. */
        default void onLineBreaksChanged() {}
        /** The currently-highlighted word changed (playback advanced). */
        default void onActiveWordChanged(int index) {}
    }

    @Nullable private Transcript transcript;
    @Nullable private Listener listener;

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strikePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint searchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint searchCurrentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint breakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final int DOUBLE_TAP_TIMEOUT_MS = 300;
    private long lastTapTime;
    private int lastTapIndex = -1;

    // Search highlighting
    private final java.util.Set<Integer> searchMatchSet = new java.util.HashSet<>();
    private java.util.List<Integer> searchMatches = new java.util.ArrayList<>();
    private int searchCurrent = -1;

    private final float density;
    private float padX, padY, lineHeight, spaceW, baselineOffset;

    // Per-word layout (parallel arrays, indexed like transcript.words)
    private float[] wordX = new float[0];
    private float[] wordY = new float[0]; // top of the word's line
    private float[] wordW = new float[0];
    private int laidOutForWidth = -1;
    private float totalHeight;

    private int scrollY;
    private int maxScrollY;
    private int activeIndex = -1;

    // Gesture state
    private final Handler handler = new Handler(Looper.getMainLooper());
    private float downX, downY;
    private int downIndex = -1;
    private boolean longPressFired;
    private boolean painting;
    private boolean paintTargetStruck;
    /** True once a long-press has begun actually painting strikes via a drag. */
    private boolean strikeStarted;
    private boolean scrolling;
    private int scrollStartY;
    private final int touchSlop;
    private final Runnable longPressRunnable = this::onLongPress;

    public TranscriptPanelView(Context context) { this(context, null); }

    public TranscriptPanelView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        padX = 14 * density;
        padY = 12 * density;
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(17 * density);
        textPaint.setTypeface(Typeface.DEFAULT);
        strikePaint.setColor(0xFF888888);
        strikePaint.setStrokeWidth(2 * density);
        activePaint.setColor(0x554DD0E1); // soft cyan highlight
        searchPaint.setColor(0x55FFEB3B);        // yellow for search matches
        searchCurrentPaint.setColor(0xAAFFC107); // amber for the current match
        breakPaint.setColor(0xFF4DD0E1);         // cyan break indicator
        breakPaint.setStrokeWidth(2 * density);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);
    }

    public void setListener(@NonNull Listener l) { this.listener = l; }

    public void setTranscript(@Nullable Transcript t) {
        this.transcript = t;
        laidOutForWidth = -1;
        scrollY = 0;
        requestLayout();
        invalidate();
    }

    /** Highlight the currently-playing word, scrolling only if it's off-screen. */
    public void setActiveSourceMs(long sourceMs) {
        if (transcript == null) return;
        int idx = transcript.indexAtOrBeforeTime(sourceMs);
        if (idx == activeIndex) return;
        activeIndex = idx;
        ensureWordVisible(idx);
        invalidate();
        if (listener != null) listener.onActiveWordChanged(activeIndex);
    }

    /** Index of the word currently highlighted by playback, or -1. */
    public int getActiveIndex() {
        return activeIndex;
    }

    /**
     * Scrolls just enough to bring the word into view IF it's near/over an edge.
     * If it's already comfortably visible, the view does NOT move — this avoids
     * the disorienting "centre on every word" jump.
     */
    private void ensureWordVisible(int idx) {
        if (idx < 0 || idx >= wordY.length || laidOutForWidth != getWidth()) return;
        float top = wordY[idx];
        float bottom = top + lineHeight;
        int h = getHeight();
        int margin = (int) lineHeight;
        if (top - scrollY < margin) {
            scrollY = Math.max(0, (int) (top - margin));
        } else if (bottom - scrollY > h - margin) {
            scrollY = Math.min(maxScrollY, (int) (bottom - h + margin));
        }
    }

    // ── Search ───────────────────────────────────────────────────────

    /**
     * Find words containing {@code query} (case-insensitive). Returns the match
     * count. The first match becomes current and is scrolled into view.
     */
    public int search(@Nullable String query) {
        searchMatches = new java.util.ArrayList<>();
        searchMatchSet.clear();
        searchCurrent = -1;
        if (transcript != null && query != null && !query.trim().isEmpty()) {
            String q = query.trim().toLowerCase();
            for (int i = 0; i < transcript.words.size(); i++) {
                if (transcript.words.get(i).text.toLowerCase().contains(q)) {
                    searchMatches.add(i);
                    searchMatchSet.add(i);
                }
            }
            if (!searchMatches.isEmpty()) {
                searchCurrent = 0;
                scrollToWordCentered(searchMatches.get(0));
            }
        }
        invalidate();
        return searchMatches.size();
    }

    /** Move to the next/previous search match (wraps). Returns the 1-based index. */
    public int moveSearch(int dir) {
        if (searchMatches.isEmpty()) return 0;
        searchCurrent = (searchCurrent + dir + searchMatches.size()) % searchMatches.size();
        scrollToWordCentered(searchMatches.get(searchCurrent));
        invalidate();
        return searchCurrent + 1;
    }

    public int getSearchCurrentOneBased() {
        return searchCurrent + 1;
    }

    /** Live view of the matched word indices (for mirroring hits on the timeline tape). */
    @NonNull
    public java.util.Set<Integer> getSearchMatchSet() {
        return searchMatchSet;
    }

    public int getSearchTotal() {
        return searchMatches.size();
    }

    public void clearSearch() {
        searchMatches = new java.util.ArrayList<>();
        searchMatchSet.clear();
        searchCurrent = -1;
        invalidate();
    }

    private void scrollToWordCentered(int idx) {
        if (idx < 0 || idx >= wordY.length || laidOutForWidth != getWidth()) return;
        int desired = (int) (wordY[idx] - getHeight() * 0.4f);
        scrollY = Math.max(0, Math.min(desired, maxScrollY));
    }

    // ── Layout ───────────────────────────────────────────────────────

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutWords(w);
    }

    private void layoutWords(int width) {
        if (transcript == null || width <= 0) {
            wordX = wordY = wordW = new float[0];
            totalHeight = 0;
            maxScrollY = 0;
            laidOutForWidth = width;
            return;
        }
        int n = transcript.words.size();
        wordX = new float[n];
        wordY = new float[n];
        wordW = new float[n];

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        lineHeight = (fm.descent - fm.ascent) + 6 * density;
        baselineOffset = -fm.ascent + 3 * density;
        spaceW = textPaint.measureText(" ");

        float maxRight = width - padX;
        float x = padX, y = padY;
        for (int i = 0; i < n; i++) {
            String text = transcript.words.get(i).text;
            float wW = textPaint.measureText(text);
            if (x > padX && x + wW > maxRight) {
                x = padX;
                y += lineHeight;
            }
            wordX[i] = x;
            wordY[i] = y;
            wordW[i] = wW;
            x += wW + spaceW;
        }
        totalHeight = y + lineHeight + padY;
        maxScrollY = (int) Math.max(0, totalHeight - getHeight());
        laidOutForWidth = width;
    }

    // ── Draw ─────────────────────────────────────────────────────────

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (transcript == null) return;
        if (laidOutForWidth != getWidth()) layoutWords(getWidth());

        int n = transcript.words.size();
        int h = getHeight();
        for (int i = 0; i < n; i++) {
            float wy = wordY[i] - scrollY;
            if (wy + lineHeight < 0 || wy > h) continue; // offscreen
            TranscriptWord w = transcript.words.get(i);

            if (i == activeIndex && !w.struck) {
                RectF bg = new RectF(wordX[i] - 2 * density, wy,
                        wordX[i] + wordW[i] + 2 * density, wy + lineHeight);
                canvas.drawRoundRect(bg, 4 * density, 4 * density, activePaint);
            }
            if (searchMatchSet.contains(i)) {
                RectF hl = new RectF(wordX[i] - 2 * density, wy,
                        wordX[i] + wordW[i] + 2 * density, wy + lineHeight);
                boolean isCurrent = searchCurrent >= 0
                        && i == searchMatches.get(searchCurrent);
                canvas.drawRoundRect(hl, 4 * density, 4 * density,
                        isCurrent ? searchCurrentPaint : searchPaint);
            }

            textPaint.setColor(w.struck ? 0xFF888888 : 0xFFFFFFFF);
            float baseY = wy + baselineOffset;
            canvas.drawText(w.text, wordX[i], baseY, textPaint);
            if (w.struck) {
                float midY = wy + lineHeight / 2f;
                canvas.drawLine(wordX[i], midY, wordX[i] + wordW[i], midY, strikePaint);
            }
            if (w.forceLineBreakAfter) {
                float cx = wordX[i] + wordW[i] + spaceW * 0.4f;
                float cy = wy + lineHeight * 0.65f;
                float s = 3 * density;
                // small downward-pointing triangle
                canvas.drawLine(cx - s, cy - s, cx, cy + s, breakPaint);
                canvas.drawLine(cx, cy + s, cx + s, cy - s, breakPaint);
            }
        }
    }

    // ── Touch / gestures ─────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downIndex = wordAt(downX, downY + scrollY);
                longPressFired = false;
                painting = false;
                scrolling = false;
                scrollStartY = scrollY;
                if (downIndex >= 0) {
                    handler.postDelayed(longPressRunnable,
                            ViewConfiguration.getLongPressTimeout());
                }
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (painting) {
                    // Ignore tiny jitter so a still long-press → edit dialog (not a strike).
                    if (!strikeStarted && Math.hypot(dx, dy) < touchSlop) return true;
                    int idx = wordAt(e.getX(), e.getY() + scrollY);
                    if (idx >= 0 && transcript != null) {
                        if (!strikeStarted) {
                            // First real drag begins strike-painting: toggle the pressed
                            // word and paint that state across the drag (power gesture).
                            strikeStarted = true;
                            TranscriptWord dw = transcript.words.get(downIndex);
                            dw.struck = !dw.struck;
                            paintTargetStruck = dw.struck;
                        }
                        transcript.words.get(idx).struck = paintTargetStruck;
                        invalidate();
                    }
                    return true;
                }
                if (!longPressFired && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    handler.removeCallbacks(longPressRunnable);
                    scrolling = true;
                }
                if (scrolling) {
                    scrollY = Math.max(0, Math.min(maxScrollY, scrollStartY - (int) dy));
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPressRunnable);
                if (painting) {
                    if (strikeStarted) {
                        if (listener != null) listener.onStrikesChanged();
                    } else if (downIndex >= 0 && transcript != null && listener != null) {
                        // Long-press without a drag → open the manual word-edit dialog.
                        listener.onEditWord(downIndex, transcript.words.get(downIndex).text);
                    }
                } else if (!longPressFired && !scrolling) {
                    // Tap → highlight THIS word immediately (it stays put under the
                    // finger; no scroll) and seek the player to it.
                    // Double-tap → toggle a forced caption line break after the word.
                    int idx = wordAt(e.getX(), e.getY() + scrollY);
                    if (idx >= 0 && transcript != null && listener != null) {
                        long now = System.currentTimeMillis();
                        if (idx == lastTapIndex
                                && now - lastTapTime <= DOUBLE_TAP_TIMEOUT_MS) {
                            transcript.words.get(idx).forceLineBreakAfter =
                                    !transcript.words.get(idx).forceLineBreakAfter;
                            layoutWords(getWidth());
                            invalidate();
                            listener.onLineBreaksChanged();
                            lastTapIndex = -1;
                            lastTapTime = 0;
                        } else {
                            lastTapIndex = idx;
                            lastTapTime = now;
                            activeIndex = idx;
                            invalidate();
                            listener.onSeekToMs(transcript.words.get(idx).startMs);
                        }
                    }
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRunnable);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void onLongPress() {
        if (downIndex < 0 || transcript == null) return;
        longPressFired = true;
        painting = true;
        strikeStarted = false;
        // Do NOT toggle yet: a release without a drag opens the edit dialog; a drag
        // begins strike-painting (see ACTION_MOVE).
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    }

    /**
     * Replace the word at {@code index} with {@code newText}, splitting on whitespace
     * and distributing the original word's time span across the new words proportionally
     * by character length (mirrors the AI {@code correct_transcript} tool). Empty text
     * deletes the word. The struck state is preserved. Caller persists afterward.
     */
    /** The word at {@code index}, or null. */
    @Nullable
    public TranscriptWord getWord(int index) {
        if (transcript == null || index < 0 || index >= transcript.words.size()) return null;
        return transcript.words.get(index);
    }

    public int getWordCount() {
        return transcript != null ? transcript.words.size() : 0;
    }

    /**
     * Move the word at {@code index} to a new SOURCE start time, preserving its duration and
     * struck state. Re-lays-out + redraws so the change is visible immediately. Caller persists.
     */
    public void setWordStart(int index, long newStartMs) {
        if (transcript == null || index < 0 || index >= transcript.words.size()) return;
        TranscriptWord old = transcript.words.get(index);
        long dur = Math.max(0, old.endMs - old.startMs);
        long ns = Math.max(0, newStartMs);
        transcript.words.set(index, new TranscriptWord(old.text, ns, ns + dur,
                old.struck, old.forceLineBreakAfter));
        laidOutForWidth = -1;
        requestLayout();
        invalidate();
    }

    public void editWord(int index, @NonNull String newText) {
        if (transcript == null || index < 0 || index >= transcript.words.size()) return;
        TranscriptWord old = transcript.words.get(index);
        long spanStart = old.startMs;
        long spanEnd = old.endMs;
        String trimmed = newText.trim();
        java.util.List<TranscriptWord> replacement = new java.util.ArrayList<>();
        if (!trimmed.isEmpty()) {
            String[] toks = trimmed.split("\\s+");
            long total = Math.max(1, spanEnd - spanStart);
            int totalChars = 0;
            for (String t : toks) totalChars += Math.max(1, t.length());
            long cursor = spanStart;
            for (int x = 0; x < toks.length; x++) {
                long dur = (x == toks.length - 1) ? (spanEnd - cursor)
                        : Math.max(1, total * Math.max(1, toks[x].length()) / totalChars);
                long wEnd = Math.min(spanEnd, cursor + dur);
                boolean breakAfter = old.forceLineBreakAfter && x == toks.length - 1;
                replacement.add(new TranscriptWord(toks[x], cursor, wEnd, old.struck, breakAfter));
                cursor = wEnd;
            }
        }
        transcript.words.remove(index);
        transcript.words.addAll(index, replacement);
        laidOutForWidth = -1;
        requestLayout();
        invalidate();
    }

    /** Returns the word index at content coordinates (y already includes scroll), or -1. */
    private int wordAt(float x, float contentY) {
        for (int i = 0; i < wordX.length; i++) {
            if (contentY >= wordY[i] && contentY <= wordY[i] + lineHeight
                    && x >= wordX[i] - spaceW / 2 && x <= wordX[i] + wordW[i] + spaceW / 2) {
                return i;
            }
        }
        return -1;
    }
}
