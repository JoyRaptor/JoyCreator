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
        /** Paragraph reordered by dragging the gutter rail. */
        default void onParagraphReordered(int fromIndex, int toIndex) {}
        /** User tapped a collapsed paragraph's label — rename it to become a chapter. */
        default void onParagraphRenameRequested(int paraIndex, @NonNull String currentTitle) {}
        /** User requested to export chapters as YouTube text. */
        default void onExportChaptersRequested() {}
        /** User wants to assign a speaker to a paragraph. */
        default void onParagraphSpeakerRequested(int paraIndex, @Nullable String currentSpeaker) {}
    }

    /**
     * Source-time window of the clip the panel is currently titled with. Every clip cut
     * from one source keeps the WHOLE source transcript (see Timeline.partitionAfterSplit),
     * so the panel is a map of the whole recording: words inside this window belong to the
     * current clip, words outside belong to a sibling clip of the same source and are drawn
     * dimmed. The default window is unbounded, so a caller that never sets one sees the old
     * all-full-strength rendering.
     */
    private long clipWindowInMs = Long.MIN_VALUE;
    private long clipWindowOutMs = Long.MAX_VALUE;

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

    private static final float GUTTER_WIDTH_DP = 20f;
    private static final float GUTTER_GAP_DP = 6f;
    private float gutterWidthPx;
    private float gutterGapPx;
    private final Paint gutterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gutterSelectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @androidx.annotation.Nullable private TranscriptParagraphs paragraphData;
    private int selectedParagraph = -1;
    private int downGutterParagraph = -1;
    private boolean gutterDown;
    private final java.util.Set<Integer> collapsedParagraphs = new java.util.HashSet<>();
    private long lastGutterTapTime;
    private int lastGutterTapIndex = -1;
    private boolean gutterReorderDragging;
    private int draggedParagraph = -1;
    private int dragTargetParagraph = -1;
    private float dragStartRawY;
    private float dragCurrentRawY;
    private final Runnable gutterLongPressRunnable = this::onGutterLongPress;

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
        gutterWidthPx = GUTTER_WIDTH_DP * density;
        gutterGapPx = GUTTER_GAP_DP * density;
        gutterPaint.setColor(0x33FFFFFF);
        gutterPaint.setStyle(Paint.Style.FILL);
        gutterSelectedPaint.setColor(0xAAFFFFFF);
        gutterSelectedPaint.setStyle(Paint.Style.FILL);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);
    }

    public void setListener(@NonNull Listener l) { this.listener = l; }

    public void setTranscript(@Nullable Transcript t) {
        this.transcript = t;
        this.paragraphData = TranscriptParagraphs.of(t);
        this.selectedParagraph = -1;
        this.collapsedParagraphs.clear();
        lastGutterTapIndex = -1;
        lastGutterTapTime = 0;
        laidOutForWidth = -1;
        scrollY = 0;
        requestLayout();
        invalidate();
    }

    /**
     * Set the source-time range owned by the clip on screen. Words outside it are drawn
     * dimmed (they belong to another clip of the same source). Pass an unbounded range to
     * clear. No-ops when unchanged so the 50ms playhead updater can call this freely.
     */
    public void setClipWindow(long inMs, long outMs) {
        if (inMs == clipWindowInMs && outMs == clipWindowOutMs) return;
        clipWindowInMs = inMs;
        clipWindowOutMs = outMs;
        invalidate();
    }

    public void clearClipWindow() {
        setClipWindow(Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /** Whether this word's source time falls inside the current clip's trim. */
    private boolean inClipWindow(@NonNull TranscriptWord w) {
        return w.startMs >= clipWindowInMs && w.startMs <= clipWindowOutMs;
    }

    /** True if the word at {@code index} belongs to a DIFFERENT clip of the same source. */
    public boolean isOutsideClipWindow(int index) {
        TranscriptWord w = getWord(index);
        return w != null && !inClipWindow(w);
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

        float gutterRight = gutterWidthPx + gutterGapPx + padX;
        float maxRight = width - padX;
        float x = gutterRight, y = padY;
        if (paragraphData != null && paragraphData.paragraphCount() > 0) {
            for (int p = 0; p < paragraphData.paragraphCount(); p++) {
                int[] range = paragraphData.paragraphs.get(p);
                int s = range[0], e = range[1];
                if (s < 0 || e >= n) continue;
                boolean collapsed = collapsedParagraphs.contains(p);
                if (collapsed) {
                    if (x != gutterRight) { y += lineHeight; x = gutterRight; }
                    String summary = buildParagraphSummary(p);
                    float wW = textPaint.measureText(summary);
                    wordX[s] = x;
                    wordY[s] = y;
                    wordW[s] = wW;
                    for (int w = s + 1; w <= e; w++) { wordX[w] = -10000; wordY[w] = y; wordW[w] = 0; }
                    y += lineHeight;
                    x = gutterRight;
                } else {
                    for (int w = s; w <= e; w++) {
                        String text = transcript.words.get(w).text;
                        float wW = textPaint.measureText(text);
                        if (x > gutterRight && x + wW > maxRight) {
                            x = gutterRight;
                            y += lineHeight;
                        }
                        wordX[w] = x;
                        wordY[w] = y;
                        wordW[w] = wW;
                        x += wW + spaceW;
                        if (transcript.words.get(w).forceLineBreakAfter && w != e) {
                            x = gutterRight;
                            y += lineHeight;
                        }
                    }
                }
            }
        } else {
            for (int i = 0; i < n; i++) {
                String text = transcript.words.get(i).text;
                float wW = textPaint.measureText(text);
                if (x > gutterRight && x + wW > maxRight) {
                    x = gutterRight;
                    y += lineHeight;
                }
                wordX[i] = x;
                wordY[i] = y;
                wordW[i] = wW;
                x += wW + spaceW;
            }
        }
        totalHeight = y + lineHeight + padY;
        maxScrollY = (int) Math.max(0, totalHeight - getHeight());
        laidOutForWidth = width;
    }

    private String buildParagraphSummary(int paraIdx) {
        if (transcript == null || paragraphData == null) return "";
        // D5 — speaker prefix, if any
        String speaker = transcript.getParagraphSpeaker(paraIdx);
        String prefix = (speaker != null && !speaker.trim().isEmpty()) ? speaker.trim() + ": " : "";
        // D4 — a named paragraph IS a chapter; show its title when present
        String chapter = transcript.getChapterTitle(paraIdx);
        if (chapter != null && !chapter.trim().isEmpty()) {
            int[] range = paragraphData.paragraphs.get(paraIdx);
            int s = range[0], e = range[1];
            long durMs = transcript.words.get(e).endMs - transcript.words.get(s).startMs;
            if (durMs < 0) durMs = 0;
            return prefix + chapter.trim() + "  (" + formatParagraphDuration(durMs) + ")";
        }
        int[] range = paragraphData.paragraphs.get(paraIdx);
        int s = range[0], e = range[1];
        int take = Math.min(6, e - s + 1);
        StringBuilder sb = new StringBuilder();
        sb.append(prefix);
        for (int i = 0; i < take; i++) {
            if (i > 0) sb.append(' ');
            sb.append(transcript.words.get(s + i).text);
        }
        if (take < e - s + 1) sb.append(" …");
        long durMs = transcript.words.get(e).endMs - transcript.words.get(s).startMs;
        if (durMs < 0) durMs = 0;
        sb.append("  (").append(formatParagraphDuration(durMs)).append(")");
        return sb.toString();
    }

    /** D4 — YouTube chapter export for this transcript. */
    @NonNull
    public String getYoutubeChaptersText() {
        if (transcript == null || paragraphData == null) return "";
        return Transcript.toYoutubeChapters(transcript, paragraphData);
    }

    /** D5 — deterministic colour per speaker name. */
    public static int getSpeakerColor(@NonNull String speaker) {
        int h = speaker.hashCode();
        float hue = (Math.abs(h) % 360);
        float[] hsv = {hue, 0.65f, 0.92f};
        return android.graphics.Color.HSVToColor(hsv);
    }

    private static String formatParagraphDuration(long ms) {
        long sec = ms / 1000;
        long m = sec / 60;
        long s = sec % 60;
        if (m > 0) return m + ":" + String.format(java.util.Locale.US, "%02d", s);
        return s + "s";
    }

    public boolean isParagraphCollapsed(int paraIdx) { return collapsedParagraphs.contains(paraIdx); }

    // ── Draw ─────────────────────────────────────────────────────────

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (transcript == null) return;
        if (laidOutForWidth != getWidth()) layoutWords(getWidth());

        if (paragraphData != null && paragraphData.paragraphCount() > 0 && wordY.length > 0) {
            float railW = 4f * density;
            float railR = 2f * density;
            float cx = gutterWidthPx / 2f;
            int vh = getHeight();
            for (int p = 0; p < paragraphData.paragraphCount(); p++) {
                int[] range = paragraphData.paragraphs.get(p);
                int s = range[0];
                int e = range[1];
                if (s < 0 || e >= wordY.length) continue;
                float top = wordY[s] - scrollY;
                float bottom = wordY[e] - scrollY + lineHeight;
                if (bottom < 0 || top > vh) continue;
                float t = Math.max(0, top);
                float b = Math.min(vh, bottom);
                if (b - t < 2f * density) continue;
                RectF r = new RectF(cx - railW / 2f, t, cx + railW / 2f, b);
                boolean isSelected = (p == selectedParagraph) || (gutterReorderDragging && p == draggedParagraph);
                Paint pp;
                String spk = transcript.getParagraphSpeaker(p);
                if (spk != null && !spk.trim().isEmpty()) {
                    int col = getSpeakerColor(spk);
                    Paint spPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    spPaint.setStyle(Paint.Style.FILL);
                    // Selected speaker rail is brighter, unselected slightly dimmed but still coloured
                    spPaint.setColor(col);
                    spPaint.setAlpha(isSelected ? 220 : 170);
                    pp = isSelected ? spPaint : spPaint;
                    // Keep a white outline for selected speaker rail so it still pops
                    if (isSelected) {
                        Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
                        outline.setStyle(Paint.Style.STROKE);
                        outline.setStrokeWidth(1.5f * density);
                        outline.setColor(0xFFFFFFFF);
                        outline.setAlpha(120);
                        canvas.drawRoundRect(r, railR, railR, pp);
                        canvas.drawRoundRect(r, railR, railR, outline);
                        if (gutterReorderDragging && p == draggedParagraph) pp.setAlpha(120);
                        continue;
                    }
                } else {
                    pp = isSelected ? gutterSelectedPaint : gutterPaint;
                }
                if (gutterReorderDragging && p == draggedParagraph) pp.setAlpha(120);
                canvas.drawRoundRect(r, railR, railR, pp);
                if (gutterReorderDragging && p == draggedParagraph) pp.setAlpha(255);
            }
            if (gutterReorderDragging && dragTargetParagraph >= 0) {
                int count = paragraphData.paragraphCount();
                int target = dragTargetParagraph;
                if (target != draggedParagraph) {
                    float lineY;
                    if (target >= count) {
                        int[] last = paragraphData.paragraphs.get(count - 1);
                        lineY = wordY[last[1]] + lineHeight - scrollY;
                    } else if (target <= 0) {
                        int[] first = paragraphData.paragraphs.get(0);
                        lineY = wordY[first[0]] - scrollY;
                    } else {
                        int[] r = paragraphData.paragraphs.get(target);
                        lineY = wordY[r[0]] - scrollY;
                    }
                    Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
                    lp.setColor(0xFF8C3DFA);
                    lp.setStrokeWidth(3f * density);
                    float left = gutterWidthPx + gutterGapPx;
                    canvas.drawLine(left, lineY, getWidth() - padX, lineY, lp);
                }
            }
        }

        int n = transcript.words.size();
        int h = getHeight();
        for (int i = 0; i < n; i++) {
            float wy = wordY[i] - scrollY;
            if (wy + lineHeight < 0 || wy > h) continue; // offscreen
            TranscriptWord w = transcript.words.get(i);
            int para = paragraphData != null ? paragraphData.paragraphOf(i) : -1;
            boolean collapsed = para >= 0 && collapsedParagraphs.contains(para);
            if (collapsed) {
                int[] range = paragraphData.paragraphs.get(para);
                if (i != range[0]) continue;
                String summary = buildParagraphSummary(para);
                textPaint.setColor(0xFFB0B0B0);
                float baseY = wy + baselineOffset;
                float maxW = getWidth() - (gutterWidthPx + gutterGapPx + padX) - padX;
                String draw = summary;
                if (textPaint.measureText(draw) > maxW) {
                    while (draw.length() > 0 && textPaint.measureText(draw + "…") > maxW) draw = draw.substring(0, draw.length() - 1);
                    draw = draw + "…";
                }
                canvas.drawText(draw, wordX[i], baseY, textPaint);
                continue;
            }
            boolean outside = !inClipWindow(w);

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

            // Outside the current clip's trim = another clip's words: dimmed, still legible
            // and still tappable (a tap there navigates to that clip).
            textPaint.setColor(outside ? (w.struck ? 0xFF4A4A4A : 0xFF6E6E6E)
                                       : (w.struck ? 0xFF888888 : 0xFFFFFFFF));
            float baseY = wy + baselineOffset;
            canvas.drawText(w.text, wordX[i], baseY, textPaint);
            if (w.struck) {
                strikePaint.setColor(outside ? 0xFF4A4A4A : 0xFF888888);
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

    private boolean isGutterHit(float x) {
        return x < gutterWidthPx + gutterGapPx + 8f * density;
    }

    private int paragraphAt(float contentY) {
        if (paragraphData == null || wordY.length == 0) return -1;
        for (int p = 0; p < paragraphData.paragraphCount(); p++) {
            int[] r = paragraphData.paragraphs.get(p);
            int s = r[0];
            int e = r[1];
            if (s < 0 || e >= wordY.length) continue;
            float top = wordY[s];
            float bottom = wordY[e] + lineHeight;
            if (contentY >= top && contentY <= bottom) return p;
        }
        return -1;
    }

    public int getSelectedParagraph() { return selectedParagraph; }

    public void setSelectedParagraph(int p) {
        if (paragraphData == null) return;
        if (p < -1 || p >= paragraphData.paragraphCount()) return;
        selectedParagraph = p;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downIndex = -1;
                downGutterParagraph = -1;
                gutterDown = isGutterHit(downX);
                if (gutterDown) {
                    downGutterParagraph = paragraphAt(downY + scrollY);
                    longPressFired = false;
                    painting = false;
                    scrolling = false;
                    scrollStartY = scrollY;
                    if (downGutterParagraph >= 0) {
                        handler.postDelayed(gutterLongPressRunnable, ViewConfiguration.getLongPressTimeout());
                    }
                    return true;
                }
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
                if (gutterDown) {
                    if (gutterReorderDragging) {
                        dragCurrentRawY = e.getY();
                        float contentY = dragCurrentRawY + scrollY;
                        int target = insertionIndexForY(contentY);
                        if (target < 0) target = draggedParagraph;
                        if (target == draggedParagraph || target == draggedParagraph + 1) {
                            dragTargetParagraph = draggedParagraph;
                        } else {
                            dragTargetParagraph = target;
                        }
                        float y = e.getY();
                        int h = getHeight();
                        if (y < 60 && scrollY > 0) { scrollY = Math.max(0, scrollY - 14); postInvalidateOnAnimation(); }
                        else if (y > h - 60 && scrollY < maxScrollY) { scrollY = Math.min(maxScrollY, scrollY + 14); postInvalidateOnAnimation(); }
                        invalidate();
                        return true;
                    }
                    if (!scrolling && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        handler.removeCallbacks(gutterLongPressRunnable);
                        handler.removeCallbacks(longPressRunnable);
                        scrolling = true;
                    }
                    if (scrolling) {
                        scrollY = Math.max(0, Math.min(maxScrollY, scrollStartY - (int) dy));
                        invalidate();
                    }
                    return true;
                }
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
                if (gutterDown) {
                    handler.removeCallbacks(gutterLongPressRunnable);
                    if (gutterReorderDragging) {
                        int from = draggedParagraph;
                        int to = dragTargetParagraph;
                        gutterReorderDragging = false;
                        draggedParagraph = -1;
                        dragTargetParagraph = -1;
                        gutterDown = false;
                        downGutterParagraph = -1;
                        if (from >= 0 && to >= 0 && from != to && listener != null) {
                            int adjTo = to > from ? to - 1 : to;
                            int count = paragraphData != null ? paragraphData.paragraphCount() : 0;
                            if (adjTo < 0) adjTo = 0;
                            if (adjTo >= count) adjTo = count - 1;
                            if (from != adjTo) listener.onParagraphReordered(from, adjTo);
                        }
                        invalidate();
                        return true;
                    }
                    if (!scrolling) {
                        int gp = paragraphAt(e.getY() + scrollY);
                        if (gp < 0) gp = downGutterParagraph;
                        if (gp >= 0) {
                            long now = System.currentTimeMillis();
                            if (gp == lastGutterTapIndex && now - lastGutterTapTime <= DOUBLE_TAP_TIMEOUT_MS) {
                                if (collapsedParagraphs.contains(gp)) collapsedParagraphs.remove(gp);
                                else collapsedParagraphs.add(gp);
                                laidOutForWidth = -1;
                                requestLayout();
                                invalidate();
                                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                                lastGutterTapIndex = -1;
                                lastGutterTapTime = 0;
                            } else {
                                selectedParagraph = gp;
                                invalidate();
                                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                                lastGutterTapIndex = gp;
                                lastGutterTapTime = now;
                            }
                        } else {
                            lastGutterTapIndex = -1;
                            lastGutterTapTime = 0;
                        }
                    } else {
                        lastGutterTapIndex = -1;
                        lastGutterTapTime = 0;
                    }
                    gutterDown = false;
                    downGutterParagraph = -1;
                    return true;
                }
                if (painting) {
                    if (strikeStarted) {
                        if (listener != null) listener.onStrikesChanged();
                    } else if (downIndex >= 0 && transcript != null && listener != null) {
                        // Long-press without a drag → open the manual word-edit dialog.
                        listener.onEditWord(downIndex, transcript.words.get(downIndex).text);
                    }
                } else if (!longPressFired && !scrolling) {
                    // D4 — tap a collapsed paragraph's label to rename it → chapter
                    int idx = wordAt(e.getX(), e.getY() + scrollY);
                    if (idx >= 0 && transcript != null && paragraphData != null && listener != null) {
                        int para = paragraphData.paragraphOf(idx);
                        if (para >= 0 && collapsedParagraphs.contains(para)) {
                            String cur = transcript.getChapterTitle(para);
                            if (cur == null) cur = "";
                            listener.onParagraphRenameRequested(para, cur);
                            lastTapIndex = -1;
                            lastTapTime = 0;
                            return true;
                        }
                    }
                    // Tap → highlight THIS word immediately (it stays put under the
                    // finger; no scroll) and seek the player to it.
                    // Double-tap → toggle a forced caption line break after the word.
                    idx = wordAt(e.getX(), e.getY() + scrollY);
                    if (idx >= 0 && transcript != null && listener != null) {
                        long now = System.currentTimeMillis();
                        boolean outside = isOutsideClipWindow(idx);
                        if (outside) {
                            // Another clip's word: this tap is NAVIGATION. The host re-homes
                            // the source time onto whichever clip owns it and selects it, so
                            // don't consume the tap as a caption line-break instead.
                            lastTapIndex = -1;
                            lastTapTime = 0;
                            listener.onSeekToMs(transcript.words.get(idx).startMs);
                        } else if (idx == lastTapIndex
                                && now - lastTapTime <= DOUBLE_TAP_TIMEOUT_MS) {
                            transcript.words.get(idx).forceLineBreakAfter =
                                    !transcript.words.get(idx).forceLineBreakAfter;
                            paragraphData = TranscriptParagraphs.of(transcript);
                            collapsedParagraphs.clear();
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
                handler.removeCallbacks(gutterLongPressRunnable);
                gutterDown = false;
                downGutterParagraph = -1;
                gutterReorderDragging = false;
                draggedParagraph = -1;
                dragTargetParagraph = -1;
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

    private void onGutterLongPress() {
        if (downGutterParagraph < 0 || transcript == null || paragraphData == null) return;
        if (collapsedParagraphs.contains(downGutterParagraph)) return;
        gutterReorderDragging = true;
        draggedParagraph = downGutterParagraph;
        dragTargetParagraph = draggedParagraph;
        dragStartRawY = downY;
        dragCurrentRawY = downY;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    private int insertionIndexForY(float contentY) {
        if (paragraphData == null || wordY.length == 0) return -1;
        int count = paragraphData.paragraphCount();
        if (count == 0) return -1;
        int[] first = paragraphData.paragraphs.get(0);
        if (first[0] >=0 && first[0] < wordY.length && contentY < wordY[first[0]]) return 0;
        int[] last = paragraphData.paragraphs.get(count - 1);
        int le = last[1];
        if (le >=0 && le < wordY.length) {
            float lastBottom = wordY[le] + lineHeight;
            if (contentY > lastBottom) return count;
        }
        for (int p = 0; p < count; p++) {
            int[] r = paragraphData.paragraphs.get(p);
            int s = r[0], e = r[1];
            if (s < 0 || e >= wordY.length) continue;
            float top = wordY[s];
            float bottom = wordY[e] + lineHeight;
            if (contentY >= top && contentY <= bottom) {
                float mid = (top + bottom) / 2f;
                return contentY < mid ? p : p + 1;
            }
            if (contentY < top) return p;
        }
        return count;
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
        paragraphData = TranscriptParagraphs.of(transcript);
        collapsedParagraphs.clear();
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
        paragraphData = TranscriptParagraphs.of(transcript);
        collapsedParagraphs.clear();
        laidOutForWidth = -1;
        requestLayout();
        invalidate();
    }

    /** Returns the word index at content coordinates (y already includes scroll), or -1. */
    private int wordAt(float x, float contentY) {
        for (int i = 0; i < wordX.length; i++) {
            int para = paragraphData != null ? paragraphData.paragraphOf(i) : -1;
            if (para >= 0 && collapsedParagraphs.contains(para)) continue;
            if (contentY >= wordY[i] && contentY <= wordY[i] + lineHeight
                    && x >= wordX[i] - spaceW / 2 && x <= wordX[i] + wordW[i] + spaceW / 2) {
                return i;
            }
        }
        return -1;
    }
}
