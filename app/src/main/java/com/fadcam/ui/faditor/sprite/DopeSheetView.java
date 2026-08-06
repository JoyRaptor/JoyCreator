package com.fadcam.ui.faditor.sprite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * THE DOPE SHEET (SPEC_IMAGE_SEQUENCE §5) — a horizontally scrolling strip of frame thumbnails,
 * and the surface where the weight model becomes something you can actually feel.
 *
 * <p>Built ONCE for image sequences AND sprite sheets, per §0's instruction: the dope sheet this
 * spec wants is the one PLAN_SPRITE_ANIMATION's fast-follow A has been owed since 2026-07-06, and
 * <i>"two implementations of it would be this project's oldest and most expensive mistake
 * repeated."</i> A sequence shows its frames and edits their weights; a sprite shows its
 * frame-track keys and can turn a selection into a preset.</p>
 *
 * <h3>§5a — a hold is a NUMBER, not a wider frame</h3>
 * The user considered physically widening a held frame and reconsidered, and the reconsideration
 * is right: a 10× hold in a 200-frame sequence makes the strip mostly one image, where a count
 * badge is constant-space and reads instantly. So frames stay uniform width and the weight shows
 * as {@code ×N}.
 *
 * <h3>§5b — vertical drag, applied to the SELECTION</h3>
 * Dragging up/down on a frame raises/lowers its weight (floor 1). The three-way gesture conflict
 * — horizontal browse vs vertical weight vs the drawer's own vertical scroll — is settled by
 * CLAIMING: once a vertical drag is recognised the strip calls
 * {@code requestDisallowInterceptTouchEvent(true)} and owns the gesture until release.
 *
 * <p><b>The drag applies to every SELECTED frame</b>, falling back to the frame under the finger
 * when nothing is selected. That is the anti-tedium rule, and it is the difference between this
 * feature and <i>"clicking a button 200 times across 20 images."</i></p>
 *
 * <h3>§5c — paint-drag</h3>
 * Sweeping horizontally while already dragging vertically sets a RUN of frames to the same
 * weight in one gesture. The remaining bulk tools (stride, ramp, presets, numeric, reorder) are
 * pure array operations and live in {@link SequenceTiming}, driven by the toolbar the panel
 * mounts above this view — this class owns the direct-manipulation half only.
 */
public class DopeSheetView extends View {

    public interface Callback {
        /**
         * Weights were edited. Fired ONCE on release with the final array — never per pixel of
         * the drag — so a gesture is a single undo step, matching the house rule every other
         * preview gesture follows.
         *
         * @param label short action name for the undo entry
         */
        void onWeightsCommitted(@NonNull List<Integer> weights, @NonNull String label);

        /** A frame was tapped: move the real playhead to where that frame comes in. */
        void onSeekToFrame(int frameIndex, long localMs);

        /** Selection changed (indices into the strip). Lets the panel enable/label its tools. */
        default void onSelectionChanged(@NonNull Set<Integer> selection) {}
    }

    // ── Geometry ─────────────────────────────────────────────────────────────

    private final float density = getResources().getDisplayMetrics().density;
    /** Uniform thumbnail width. ≥44dp so every frame is a legal touch target. */
    private final float frameW = 46 * density;
    private final float gap = 3 * density;
    private final float padV = 6 * density;

    /** Vertical travel that changes a weight by one. Roughly a fingertip per step. */
    private final float pxPerWeightStep = 22 * density;

    // ── Paints ───────────────────────────────────────────────────────────────

    private final Paint thumbPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint cellBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint idxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();

    // ── Bound state ──────────────────────────────────────────────────────────

    @Nullable private SpriteSheet sheet;
    @Nullable private SpriteOverlayItem item;
    @Nullable private SpriteSheetRenderer renderer;
    @Nullable private Callback callback;

    /** Live working copy — the authored array plus whatever the in-flight drag has done. */
    @NonNull private List<Integer> weights = new ArrayList<>();
    /** Cell index shown by each strip position. */
    @NonNull private List<Integer> cells = new ArrayList<>();
    @NonNull private final Set<Integer> selection = new LinkedHashSet<>();

    private float scrollX;
    private int playheadFrame = -1;

    public DopeSheetView(@NonNull Context ctx) {
        super(ctx);
        cellBgPaint.setColor(0xFF23232B);
        selPaint.setStyle(Paint.Style.STROKE);
        selPaint.setStrokeWidth(2.5f * density);
        selPaint.setColor(0xFF7FD1FF);
        badgePaint.setColor(0xE6101014);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(11 * density);
        textPaint.setFakeBoldText(true);
        idxPaint.setColor(0x99FFFFFF);
        idxPaint.setTextSize(8.5f * density);
        playheadPaint.setColor(0xFF4FC3F7);
        playheadPaint.setStrokeWidth(2f * density);
    }

    public void setCallback(@Nullable Callback cb) { this.callback = cb; }

    /**
     * Bind the strip to an object.
     *
     * <p>A SEQUENCE strips its sequence preset's frames (one entry per image, weights editable).
     * A grid SPRITE strips its frame-track keys instead — the moments the author actually placed
     * — because a sprite sheet's cells are a palette, not a running order, and showing all 16
     * cells of a sheet as if they were a timeline would be a different thing wearing this one's
     * clothes.</p>
     */
    public void bind(@Nullable SpriteSheet sheet, @Nullable SpriteOverlayItem item,
                     @Nullable SpriteSheetRenderer renderer) {
        bind(sheet, item, renderer, null, 0f);
    }

    /**
     * Bind, carrying a previous strip's SELECTION and scroll across.
     *
     * <p><b>Why this overload exists.</b> Every weight edit funnels through the activity and back
     * out through {@code setData}, which rebuilds this view — so without carrying the selection,
     * selecting frames 4–24 and tapping "On twos" applied the change and then threw the selection
     * away, leaving you unable to follow it with "Ramp" or "Set ×N" on the same run. The strip
     * also jumped back to frame 1. That is precisely the tedium §5c exists to remove, reintroduced
     * by the plumbing.</p>
     *
     * <p>The carried selection is filtered to the NEW frame count, so a reorder or a re-import
     * that shortened the sequence cannot leave a selection pointing past the end.</p>
     */
    public void bind(@Nullable SpriteSheet sheet, @Nullable SpriteOverlayItem item,
                     @Nullable SpriteSheetRenderer renderer,
                     @Nullable java.util.Collection<Integer> keepSelection, float keepScrollX) {
        this.sheet = sheet;
        this.item = item;
        this.renderer = renderer;
        rebuildStrip();
        if (keepSelection != null) {
            for (Integer i : keepSelection) {
                if (i != null && i >= 0 && i < cells.size()) selection.add(i);
            }
        }
        this.scrollX = Math.max(0f, keepScrollX);
        invalidate();
    }

    /** Horizontal scroll offset, so a rebuild can put the strip back where it was. */
    public float scrollOffset() { return scrollX; }

    private void rebuildStrip() {
        cells.clear();
        weights.clear();
        selection.clear();
        if (sheet == null || item == null) return;
        if (sheet.isSequence()) {
            SpriteSheet.Preset p = sheet.sequencePreset();
            if (p == null) return;
            for (int i = 0; i < p.frames.size(); i++) {
                cells.add(p.frames.get(i));
                weights.add(SequenceTiming.weightAt(p.weights, i));
            }
        } else {
            for (FrameTrack.Key k : item.getFrameTrack().keys()) {
                int shown = k.cellIndex;
                if (k.presetId != null) {
                    // A preset key drew as an EMPTY box, which tells the user nothing about
                    // which run it is. Show the preset's FIRST frame — the cell it comes in on,
                    // and the same one the timeline tape draws at that key.
                    SpriteSheet.Preset p = sheet.presetById(k.presetId);
                    shown = (p != null && !p.frames.isEmpty()) ? p.frames.get(0) : -1;
                }
                cells.add(shown);
                weights.add(1);
            }
        }
    }

    /** True when weights are meaningful here (a sequence). Sprites get selection + presets only. */
    public boolean isWeightEditable() {
        return sheet != null && sheet.isSequence();
    }

    public int frameCount() { return cells.size(); }

    @NonNull public Set<Integer> selection() { return selection; }

    @NonNull public List<Integer> currentWeights() { return new ArrayList<>(weights); }

    /** Apply a bulk edit from the toolbar and commit it as one undo step. */
    public void applyWeights(@NonNull List<Integer> next, @NonNull String label) {
        weights = SequenceTiming.fit(next, cells.size());
        invalidate();
        if (callback != null) callback.onWeightsCommitted(currentWeights(), label);
    }

    public void selectAll() {
        selection.clear();
        for (int i = 0; i < cells.size(); i++) selection.add(i);
        notifySelection();
        invalidate();
    }

    public void clearSelection() {
        selection.clear();
        notifySelection();
        invalidate();
    }

    /** §5c.2 stride select — "every Nth frame, starting at S". */
    public void selectStride(int start, int every) {
        selection.clear();
        int step = Math.max(1, every);
        for (int i = Math.max(0, start); i < cells.size(); i += step) selection.add(i);
        notifySelection();
        invalidate();
    }

    private void notifySelection() {
        if (callback != null) callback.onSelectionChanged(new LinkedHashSet<>(selection));
    }

    /** Highlight the frame the playhead is inside. */
    public void setPlayheadMs(long timelineMs) {
        int f = -1;
        if (sheet != null && item != null && sheet.isSequence()) {
            int cell = SpriteFrameResolver.resolveCellAt(sheet, item, timelineMs);
            if (cell != SpriteFrameResolver.NO_CELL) {
                // Map the resolved CELL back to a strip position. For a sequence the two are the
                // same number, but going through the resolver keeps this honest if a sequence is
                // ever built with a non-identity frame list.
                for (int i = 0; i < cells.size(); i++) {
                    if (cells.get(i) == cell) { f = i; break; }
                }
            }
        }
        if (f != playheadFrame) { playheadFrame = f; invalidate(); }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int h = (int) (56 * density + 2 * padV);
        setMeasuredDimension(resolveSize((int) (frameW * 6), widthSpec), h);
    }

    // ── Draw ─────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (cells.isEmpty()) return;
        float top = padV, bottom = getHeight() - padV;
        clampScroll();
        for (int i = 0; i < cells.size(); i++) {
            float x = i * (frameW + gap) - scrollX;
            if (x + frameW < 0) continue;
            if (x > getWidth()) break;
            dst.set(x, top, x + frameW, bottom);
            canvas.drawRoundRect(dst, 3 * density, 3 * density, cellBgPaint);
            if (renderer != null && cells.get(i) >= 0) {
                renderer.drawCell(canvas, cells.get(i), dst, thumbPaint);
            }
            // Frame ORDINAL, bottom-left: the user counts in frames, and a strip of pictures
            // with no numbers cannot answer "which one is frame 37".
            canvas.drawText(String.valueOf(i + 1), x + 3 * density, bottom - 3 * density, idxPaint);

            int wt = weights.isEmpty() ? 1 : SequenceTiming.weightAt(weights, i);
            if (wt > 1) {
                // §5a: the hold is a badge, not a width.
                String label = "×" + wt;
                float tw = textPaint.measureText(label);
                float bw = tw + 8 * density, bh = 15 * density;
                dst.set(x + frameW - bw - 2 * density, top + 2 * density,
                        x + frameW - 2 * density, top + 2 * density + bh);
                canvas.drawRoundRect(dst, 3 * density, 3 * density, badgePaint);
                canvas.drawText(label, dst.left + 4 * density, dst.bottom - 4 * density, textPaint);
            }
            if (selection.contains(i)) {
                dst.set(x + 1 * density, top + 1 * density,
                        x + frameW - 1 * density, bottom - 1 * density);
                canvas.drawRoundRect(dst, 3 * density, 3 * density, selPaint);
            }
            if (i == playheadFrame) {
                canvas.drawLine(x, top, x, bottom, playheadPaint);
                canvas.drawLine(x + frameW, top, x + frameW, bottom, playheadPaint);
            }
        }
    }

    private float contentWidth() {
        return Math.max(0, cells.size() * (frameW + gap) - gap);
    }

    private void clampScroll() {
        float max = Math.max(0, contentWidth() - getWidth());
        scrollX = Math.max(0, Math.min(scrollX, max));
    }

    private int indexAt(float x) {
        if (cells.isEmpty()) return -1;
        int i = (int) ((x + scrollX) / (frameW + gap));
        return (i < 0 || i >= cells.size()) ? -1 : i;
    }

    // ── Gestures ─────────────────────────────────────────────────────────────

    private static final int MODE_UNDECIDED = 0, MODE_SCROLL = 1, MODE_WEIGHT = 2;
    private int mode = MODE_UNDECIDED;
    private float downX, downY, lastX;
    private int downIndex = -1;
    private long downTime;
    private final int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
    /** Weights at gesture start — the base every delta is applied to, so a drag is absolute. */
    @NonNull private List<Integer> dragBase = new ArrayList<>();
    /** Frames the paint-sweep has touched, so a horizontal sweep widens the affected run. */
    @NonNull private final Set<Integer> painted = new LinkedHashSet<>();
    private boolean movedEnoughForDrag;

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = e.getY();
                downTime = e.getEventTime();
                downIndex = indexAt(e.getX());
                mode = MODE_UNDECIDED;
                movedEnoughForDrag = false;
                dragBase = new ArrayList<>(weights);
                painted.clear();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (mode == MODE_UNDECIDED) {
                    if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx)) {
                        if (!isWeightEditable() || downIndex < 0) { mode = MODE_SCROLL; }
                        else {
                            mode = MODE_WEIGHT;
                            // CLAIM the gesture (§5b). Without this the drawer's own vertical
                            // scroll takes it and the weight drag is unusable — the exact
                            // conflict the spec calls out and solves this way.
                            getParent().requestDisallowInterceptTouchEvent(true);
                            painted.add(downIndex);
                        }
                        movedEnoughForDrag = true;
                    } else if (Math.abs(dx) > touchSlop) {
                        mode = MODE_SCROLL;
                        movedEnoughForDrag = true;
                    }
                }
                if (mode == MODE_SCROLL) {
                    scrollX -= (e.getX() - lastX);
                    lastX = e.getX();
                    clampScroll();
                    invalidate();
                } else if (mode == MODE_WEIGHT) {
                    // §5c.3 paint-drag: sweeping sideways mid-drag widens the run being set.
                    int over = indexAt(e.getX());
                    if (over >= 0) painted.add(over);
                    applyDragDelta(dy);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                if (mode == MODE_WEIGHT) {
                    commitDrag();
                } else if (!movedEnoughForDrag && downIndex >= 0) {
                    boolean longPress = e.getEventTime() - downTime
                            >= ViewConfiguration.getLongPressTimeout();
                    if (longPress) selectRangeTo(downIndex); else toggleSelect(downIndex);
                }
                mode = MODE_UNDECIDED;
                return true;

            case MotionEvent.ACTION_CANCEL:
                // Restore rather than commit: a cancelled gesture must not leave a half-applied
                // edit behind, and it must not record an undo step for something nobody did.
                if (mode == MODE_WEIGHT) { weights = new ArrayList<>(dragBase); invalidate(); }
                mode = MODE_UNDECIDED;
                return true;

            default:
                return super.onTouchEvent(e);
        }
    }

    /**
     * Live-apply the vertical delta. Targets the SELECTION when there is one, else the frames the
     * finger has swept — §5b's rule and §5c.3's paint-drag falling out of the same code.
     */
    private void applyDragDelta(float dy) {
        int delta = (int) (-dy / pxPerWeightStep);
        List<Integer> next = new ArrayList<>(dragBase);
        Set<Integer> targets = selection.isEmpty() ? painted : selection;
        for (Integer i : targets) {
            if (i == null || i < 0 || i >= next.size()) continue;
            next.set(i, SequenceTiming.clampWeight(
                    SequenceTiming.weightAt(dragBase, i) + delta));
        }
        weights = next;
        invalidate();
    }

    private void commitDrag() {
        if (weights.equals(dragBase)) return;   // no net change: no undo entry
        if (callback != null) {
            int n = selection.isEmpty() ? painted.size() : selection.size();
            callback.onWeightsCommitted(currentWeights(),
                    n == 1 ? "Change frame hold" : "Change " + n + " frame holds");
        }
    }

    private void toggleSelect(int i) {
        if (!selection.remove(i)) selection.add(i);
        notifySelection();
        invalidate();
        if (callback != null) callback.onSeekToFrame(i, localMsOfFrame(i));
    }

    /** §5c.1 range select: long-press extends from the earliest current selection to here. */
    private void selectRangeTo(int i) {
        if (selection.isEmpty()) { toggleSelect(i); return; }
        int anchor = selection.iterator().next();
        int a = Math.min(anchor, i), b = Math.max(anchor, i);
        selection.clear();
        for (int k = a; k <= b; k++) selection.add(k);
        notifySelection();
        invalidate();
    }

    /**
     * When frame {@code i} comes in, in item-local ms — so tapping a thumbnail can move the real
     * playhead to it. Derived from the same weights the resolver walks.
     */
    private long localMsOfFrame(int i) {
        if (sheet == null || item == null || !sheet.isSequence()) return 0;
        SpriteSheet.Preset p = sheet.sequencePreset();
        if (p == null) return 0;
        float fps = SequenceTiming.clampFps(p.fps > 0f ? p.fps : sheet.getFps());
        long tick = SequenceTiming.tickAtIndex(weights, cells.size(), i);
        return Math.round(tick * 1000.0 / fps);
    }

    /** Human summary for the panel's header: frames, holds, resulting length. */
    @NonNull
    public String summary() {
        if (sheet == null || cells.isEmpty()) return "";
        SpriteSheet.Preset p = sheet.sequencePreset();
        float fps = SequenceTiming.clampFps(
                p != null && p.fps > 0f ? p.fps : sheet.getFps());
        long total = SequenceTiming.totalMsForFps(weights, cells.size(), fps);
        int held = 0;
        for (int i = 0; i < cells.size(); i++) {
            if (SequenceTiming.weightAt(weights, i) > 1) held++;
        }
        return String.format(Locale.US, "%d frames · %d held · %s",
                cells.size(), held, DurationParser.formatMs(total));
    }
}
