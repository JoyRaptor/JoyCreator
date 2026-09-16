package com.fadcam.ui.faditor.sprite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * S2 setup-editor canvas: draws the sheet with the slicing grid overlaid —
 * numbered cells, disabled-cell shading, selected-cell highlight, and the
 * sheet-level pivot crosshair (drawn inside the selected cell). Pinch to zoom,
 * one-finger pan; TAP selects a cell; in PIVOT mode a drag inside the selected
 * cell moves the pivot instead of panning.
 *
 * <p>All geometry comes from {@link SpriteSheetRenderer#cellRectSource} — the
 * single geometry authority — mapped through this view's zoom/pan matrix.</p>
 */
public class SpriteGridEditorView extends View {

    public interface Listener {
        void onCellTapped(int index);
        void onPivotChanged(float pivotX, float pivotY);
        /** S2b: a tap while color-pick mode is armed sampled this sheet pixel. */
        default void onColorPicked(int argb) {}
        /** A drawing was dragged from one slot to another while a reorder mode was on. */
        default void onCellDragged(int from, int to) {}
        /** The order badge was tapped: take the LAST use of this cell back out of the roll. */
        default void onBadgeTapped(int index) {}
        /** The order badge was held: take EVERY use of this cell out of the roll. */
        default void onBadgeHeld(int index) {}
    }

    /** How a drag rearranges the sheet, or {@link #OFF} for pan and tap as usual. */
    public enum Reorder { OFF, SWAP, RIPPLE }

    @Nullable private SpriteSheet sheet;
    @Nullable private SpriteSheetRenderer renderer;
    @Nullable private Listener listener;

    private final Matrix viewMatrix = new Matrix();
    private final Matrix inverse = new Matrix();
    private boolean matrixInitialized = false;

    private int selectedCell = -1;
    /** S2b: cell currently shown by the play-preview filmstrip (-1 = not playing). */
    private int playingCell = -1;
    private boolean pivotMode = false;
    private boolean draggingPivot = false;
    private boolean colorPickMode = false;

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint();
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pivotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint visPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeInk = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF badgeBox = new RectF();
    private final float density = getResources().getDisplayMetrics().density;

    /**
     * Where each cell sits in the roll being built — "3", or "2,5" when it is used twice.
     *
     * <p>The grid is where the sequence is READ. Without this the only record of the order
     * you tapped lives in the film strip, and you cannot see it while looking at the art.</p>
     */
    private final java.util.Map<Integer, String> orders = new java.util.HashMap<>();

    /**
     * Where each order badge ended up ON SCREEN, so it can be tapped.
     *
     * <p>Filled during the draw because that is the only place that knows: the badge is
     * lettered in view space so it stays legible at any zoom, which means its position is not
     * derivable from the cell rect without redoing the same arithmetic. Recording what was
     * actually drawn cannot disagree with what was drawn.</p>
     */
    private final java.util.Map<Integer, RectF> badgeHit = new java.util.HashMap<>();

    /** What the overlay draws. On a dense sheet the lettering can be the clutter. */
    private boolean showGrid = true;
    private boolean showLabels = true;
    /** Cells whose art runs into the cell edge, i.e. the slice is probably wrong. */
    @Nullable private java.util.Set<Integer> suspect;

    public void setShowGrid(boolean on) { showGrid = on; invalidate(); }
    public boolean isShowGrid() { return showGrid; }
    public void setShowLabels(boolean on) { showLabels = on; invalidate(); }
    public boolean isShowLabels() { return showLabels; }

    @NonNull private Reorder reorder = Reorder.OFF;
    private int dragFrom = -1, dragOver = -1;
    private float dragX, dragY;

    public void setReorderMode(@NonNull Reorder m) {
        reorder = m;
        dragFrom = dragOver = -1;
        invalidate();
    }

    @NonNull public Reorder getReorderMode() { return reorder; }

    /** Flag these cells with an amber dot, or pass null to stop flagging. */
    public void setSuspect(@Nullable java.util.Set<Integer> cells) {
        suspect = cells;
        invalidate();
    }
    public boolean isShowingSuspect() { return suspect != null; }

    private final ScaleGestureDetector scaleDetector;
    private boolean scaling = false;
    private float lastX, lastY, downX, downY;
    private boolean maybeTap = false;
    private final float touchSlop;

    public SpriteGridEditorView(@NonNull Context ctx) {
        super(ctx);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1.5f * density);
        gridPaint.setColor(SpriteTheme.LINE);
        numPaint.setColor(SpriteTheme.DIMMER);
        numPaint.setTextSize(9f * density);
        numPaint.setShadowLayer(3f * density, 0, 0, 0xCC000000);
        namePaint.setColor(SpriteTheme.SELECTED);
        namePaint.setTextSize(8.5f * density);
        namePaint.setTextAlign(Paint.Align.RIGHT);
        namePaint.setShadowLayer(3f * density, 0, 0, 0xCC000000);
        visPaint.setColor(SpriteTheme.ACCENT_CELL);
        visPaint.setTextSize(8.5f * density);
        visPaint.setShadowLayer(3f * density, 0, 0, 0xCC000000);
        dropPaint.setStyle(Paint.Style.STROKE);
        dropPaint.setStrokeWidth(3f * density);
        badgeInk.setTextSize(9.5f * density);
        badgeInk.setTextAlign(Paint.Align.CENTER);
        badgeInk.setFakeBoldText(true);
        shadePaint.setColor(0x99000000);
        // Cyan is "you are pointing at this", pink is "this is what the preview is showing
        // right now" — the same two meanings they carry everywhere else in the package.
        selPaint.setStyle(Paint.Style.STROKE);
        selPaint.setStrokeWidth(2.5f * density);
        selPaint.setColor(SpriteTheme.SELECTED);
        playPaint.setStyle(Paint.Style.STROKE);
        playPaint.setStrokeWidth(3f * density);
        playPaint.setColor(SpriteTheme.LIVE);
        pivotPaint.setStyle(Paint.Style.STROKE);
        pivotPaint.setStrokeWidth(2f * density);
        pivotPaint.setColor(SpriteTheme.LIVE);
        touchSlop = 8f * density;
        scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { scaling = true; return true; }
            @Override public boolean onScale(ScaleGestureDetector d) {
                viewMatrix.postScale(d.getScaleFactor(), d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                invalidate();
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) { scaling = false; }
        });
    }

    public void setListener(@Nullable Listener l) { this.listener = l; }

    /** Source size the current view matrix was fitted to, so a re-decode can keep the zoom. */
    private int fittedW = -1, fittedH = -1;

    public void setSheet(@Nullable SpriteSheet sheet, @Nullable SpriteSheetRenderer renderer) {
        this.sheet = sheet;
        this.renderer = renderer;
        // Keep the zoom and pan when the ART is the same size as before. An undo, a tolerance
        // change or a relink all re-decode the bitmap, and throwing the view back to "fit"
        // every time means losing the close-up you were aligning in.
        int w = renderer == null ? -1 : renderer.sourceWidth();
        int h = renderer == null ? -1 : renderer.sourceHeight();
        boolean sameArt = w > 0 && w == fittedW && h == fittedH;
        if (!sameArt) {
            matrixInitialized = false;
            fittedW = w;
            fittedH = h;
            selectedCell = sheet != null && sheet.cellCount() > 0 ? 0 : -1;
        } else if (sheet != null && selectedCell >= sheet.cellCount()) {
            selectedCell = sheet.cellCount() - 1;
        }
        invalidate();
    }

    public void setSelectedCell(int index) { selectedCell = index; invalidate(); }
    public int getSelectedCell() { return selectedCell; }

    /** S2b: highlight the filmstrip's currently-playing cell (-1 clears it). */
    public void setPlayingCell(int index) { playingCell = index; invalidate(); }

    /** Toggle pivot-editing: drags inside the selected cell move the pivot. */
    public void setPivotMode(boolean on) { pivotMode = on; invalidate(); }
    public boolean isPivotMode() { return pivotMode; }

    /** S2b: arm bg-key color picking — the next tap samples the sheet pixel. */
    public void setColorPickMode(boolean on) { colorPickMode = on; invalidate(); }
    public boolean isColorPickMode() { return colorPickMode; }

    /** Tell the grid which cells the roll uses, and in what order. */
    public void setRoll(@Nullable java.util.List<int[]> roll) {
        orders.clear();
        if (roll != null) {
            for (int i = 0; i < roll.size(); i++) {
                int cell = roll.get(i)[0];
                String had = orders.get(cell);
                orders.put(cell, had == null ? String.valueOf(i + 1) : had + "," + (i + 1));
            }
        }
        invalidate();
    }

    /** Re-fit on next draw (geometry controls changed nothing about the matrix). */
    public void refresh() { invalidate(); }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(SpriteTheme.BG);
        if (renderer == null || sheet == null) {
            numPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(getContext().getString(com.fadcam.R.string.sprite_editor_missing_image),
                    getWidth() / 2f, getHeight() / 2f, numPaint);
            numPaint.setTextAlign(Paint.Align.LEFT);
            return;
        }
        if (!matrixInitialized && getWidth() > 0) {
            fitToView();
            matrixInitialized = true;
        }
        canvas.save();
        canvas.concat(viewMatrix);
        // Bitmap drawn in SOURCE-pixel space (scale decoded bitmap up to source dims
        // so all grid geometry is in one space).
        //
        // ONE blit while nothing is aligned AND nothing is reordered; cell by cell the moment
        // either is true. A single drawBitmap of the whole sheet can only ever show the raw
        // pixel layout: it cannot show a per-cell nudge, and it cannot show a drawing that has
        // moved to another slot.
        //
        // The reorder half of that was missed the first time, and it did not merely hide the
        // swap — it MISLABELLED it. Every label below asks the sheet, so `cellName(i)` follows
        // a drawing into its new slot, while the fast path left the art where the pixels are.
        // Swap two cells on a sheet with no alignment on it and the name landed on the wrong
        // picture, with the preview showing the right one. Found on the phone, 2026-09-16:
        // named cell 0 "ALPHA", swapped it with cell 5, and the grid put ALPHA over cell 5's
        // original art.
        //
        // The fast path still keeps every untouched sheet exactly as cheap as it was.
        RectF srcSpace = new RectF(0, 0, renderer.sourceWidth(), renderer.sourceHeight());
        if (sheet.getCellTransforms().isEmpty() && !sheet.hasCustomOrder()) {
            canvas.drawBitmap(renderer.getBitmap(), null, srcSpace, bmpPaint);
        } else {
            int n = sheet.cellCount();
            RectF cellDest = new RectF();
            for (int i = 0; i < n; i++) {
                Rect cr = SpriteSheetRenderer.cellRectSource(
                        sheet, i, renderer.sourceWidth(), renderer.sourceHeight());
                cellDest.set(cr);
                renderer.drawCell(canvas, i, cellDest, bmpPaint);
            }
        }

        int count = sheet.cellCount();
        for (int i = 0; i < count; i++) {
            Rect r = SpriteSheetRenderer.cellRectSource(sheet, i, renderer.sourceWidth(), renderer.sourceHeight());
            RectF rf = new RectF(r);
            SpriteSheet.Cell meta = sheet.cellAt(i);
            if (meta != null && !meta.enabled) canvas.drawRect(rf, shadePaint);
            if (showGrid) canvas.drawRect(rf, gridPaint);
            // A cell the roll uses is lit cyan too: while a sequence is being built, the
            // question the grid has to answer is "which of these am I using?"
            if (i == selectedCell || orders.containsKey(i)) canvas.drawRect(rf, selPaint);
            if (i == playingCell) canvas.drawRect(rf, playPaint);
        }
        canvas.restore();

        // Labels drawn in VIEW space, so they stay legible at any zoom. Four corners, four
        // different facts: index bottom-left, name bottom-right, viseme top-left, and the
        // order badge top-centre. Stacking them in one string made none of them readable.
        float[] tl = new float[2];
        float[] br = new float[2];
        badgeHit.clear();
        for (int i = 0; i < count; i++) {
            Rect r = SpriteSheetRenderer.cellRectSource(sheet, i, renderer.sourceWidth(), renderer.sourceHeight());
            tl[0] = r.left; tl[1] = r.top;
            br[0] = r.right; br[1] = r.bottom;
            viewMatrix.mapPoints(tl);
            viewMatrix.mapPoints(br);
            if (suspect != null && suspect.contains(i)) {
                // Amber, top-right, the same mark the web design uses. Drawn even when the
                // lettering is off: it is a warning, not a label.
                badgePaint.setColor(SpriteTheme.WARN);
                canvas.drawCircle(br[0] - 7f * density, tl[1] + 7f * density,
                        3.5f * density, badgePaint);
            }
            if (!showLabels) continue;
            if (br[0] - tl[0] < 26f * density) continue;   // too small to letter

            canvas.drawText(String.valueOf(i), tl[0] + 3f * density,
                    br[1] - 3f * density, numPaint);

            String nm = sheet.cellName(i);   // reconciles both name stores for us
            if (nm != null && !nm.isEmpty()) {
                canvas.drawText(nm, br[0] - 3f * density, br[1] - 3f * density, namePaint);
            }
            String vis = sheet.visemeOfCell(i);
            if (vis != null) {
                canvas.drawText(vis, tl[0] + 3f * density, tl[1] + 10f * density, visPaint);
            }
            String ord = orders.get(i);
            if (ord != null) {
                float cx = (tl[0] + br[0]) / 2f;
                float w = badgeInk.measureText(ord) + 9f * density;
                badgeBox.set(cx - w / 2f, tl[1] + 2f * density,
                        cx + w / 2f, tl[1] + 15f * density);
                // A 40dp minimum target, expanding outside the cell on a small one, per
                // SPEC_20260910_SPRITELAB_UI §6. A badge you cannot reliably hit is a badge
                // whose two gestures may as well not exist.
                float minHalf = 20f * density;
                float bcx = badgeBox.centerX(), bcy = badgeBox.centerY();
                badgeHit.put(i, new RectF(
                        Math.min(badgeBox.left, bcx - minHalf),
                        Math.min(badgeBox.top, bcy - minHalf),
                        Math.max(badgeBox.right, bcx + minHalf),
                        Math.max(badgeBox.bottom, bcy + minHalf)));
                boolean now = i == playingCell;
                badgePaint.setColor(now ? SpriteTheme.LIVE : SpriteTheme.SELECTED);
                canvas.drawRoundRect(badgeBox, 4f * density, 4f * density, badgePaint);
                badgeInk.setColor(now ? 0xFFFFFFFF : SpriteTheme.ON_ACCENT);
                canvas.drawText(ord, cx, badgeBox.bottom - 3.2f * density, badgeInk);
            }
        }

        // The drag: the slot you are over lights amber, and the drawing itself rides the
        // finger. Without the drawing under your thumb this is a guess, not a drag.
        if (dragFrom >= 0 && dragOver >= 0) {
            Rect tr = SpriteSheetRenderer.cellRectSource(
                    sheet, dragOver, renderer.sourceWidth(), renderer.sourceHeight());
            RectF trf = new RectF(tr);
            viewMatrix.mapRect(trf);
            dropPaint.setColor(SpriteTheme.WARN);
            canvas.drawRect(trf, dropPaint);
        }
        if (dragFrom >= 0) {
            Rect fr = SpriteSheetRenderer.cellRectSource(
                    sheet, dragFrom, renderer.sourceWidth(), renderer.sourceHeight());
            RectF ff = new RectF(fr);
            viewMatrix.mapRect(ff);
            float half = Math.min(ff.width(), ff.height()) * 0.5f;
            RectF at = new RectF(dragX - half, dragY - half, dragX + half, dragY + half);
            renderer.drawCell(canvas, dragFrom, at, bmpPaint);
            dropPaint.setColor(SpriteTheme.WARN);
            canvas.drawRect(at, dropPaint);
        }

        // Pivot crosshair inside the selected cell (sheet-level pivot, 0..1 of a cell).
        float[] pt = new float[2];
        if (selectedCell >= 0) {
            Rect r = SpriteSheetRenderer.cellRectSource(sheet, selectedCell, renderer.sourceWidth(), renderer.sourceHeight());
            pt[0] = r.left + sheet.getPivotX() * r.width();
            pt[1] = r.top + sheet.getPivotY() * r.height();
            viewMatrix.mapPoints(pt);
            float rad = (pivotMode ? 12f : 7f) * density;
            canvas.drawCircle(pt[0], pt[1], rad, pivotPaint);
            canvas.drawLine(pt[0] - rad * 1.4f, pt[1], pt[0] + rad * 1.4f, pt[1], pivotPaint);
            canvas.drawLine(pt[0], pt[1] - rad * 1.4f, pt[0], pt[1] + rad * 1.4f, pivotPaint);
        }
    }

    /** Put the whole sheet back on screen, from wherever the pan and zoom have wandered. */
    public void fitBack() {
        fitToView();
        invalidate();
    }

    private void fitToView() {
        if (renderer == null) return;
        fittedW = renderer.sourceWidth();
        fittedH = renderer.sourceHeight();
        float sw = renderer.sourceWidth(), sh = renderer.sourceHeight();
        float pad = 12f * density;
        float scale = Math.min((getWidth() - 2 * pad) / sw, (getHeight() - 2 * pad) / sh);
        viewMatrix.reset();
        viewMatrix.postScale(scale, scale);
        viewMatrix.postTranslate((getWidth() - sw * scale) / 2f, (getHeight() - sh * scale) / 2f);
    }

    /** Which order badge is under this point, or -1. */
    private int badgeAt(float x, float y) {
        for (java.util.Map.Entry<Integer, RectF> en : badgeHit.entrySet()) {
            if (en.getValue().contains(x, y)) return en.getKey();
        }
        return -1;
    }

    private int badgeDown = -1;
    private boolean badgeHeld;

    private final Runnable badgeLongPress = new Runnable() {
        @Override public void run() {
            if (badgeDown < 0) return;
            badgeHeld = true;
            if (listener != null) listener.onBadgeHeld(badgeDown);
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        }
    };

    private void cancelBadge() {
        removeCallbacks(badgeLongPress);
        badgeDown = -1;
        badgeHeld = false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        if (scaling) { maybeTap = false; draggingPivot = false; cancelBadge(); return true; }
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = downX = x;
                lastY = downY = y;
                maybeTap = true;
                draggingPivot = pivotMode && selectedCell >= 0 && isInSelectedCell(x, y);
                dragFrom = reorder == Reorder.OFF ? -1 : cellAtPoint(x, y);
                dragOver = -1;
                dragX = x; dragY = y;
                // The badge is only a control while a reorder is NOT armed: on a sheet you are
                // rearranging, a press on a cell means "pick this drawing up", and having that
                // sometimes mean "edit the roll" instead is the kind of overload that makes a
                // tool feel haunted.
                badgeHeld = false;
                badgeDown = reorder == Reorder.OFF ? badgeAt(x, y) : -1;
                if (badgeDown >= 0) postDelayed(badgeLongPress, 400);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(x - downX) > touchSlop || Math.abs(y - downY) > touchSlop) {
                    maybeTap = false;
                    cancelBadge();   // a drag that started on a badge is a pan, not a press
                }
                if (dragFrom >= 0) {
                    // Panning is off while a reorder mode is armed. The mode is explicit and
                    // temporary, and a drag that sometimes moves art and sometimes moves the
                    // view is the kind of control you stop trusting.
                    dragX = x; dragY = y;
                    dragOver = cellAtPoint(x, y);
                    invalidate();
                } else if (draggingPivot) {
                    updatePivotFromTouch(x, y);
                } else if (!maybeTap) {
                    viewMatrix.postTranslate(x - lastX, y - lastY);
                    invalidate();
                }
                lastX = x; lastY = y;
                return true;
            case MotionEvent.ACTION_UP:
                boolean onBadge = badgeDown >= 0;
                boolean held = badgeHeld;
                int badge = badgeDown;
                removeCallbacks(badgeLongPress);
                badgeDown = -1;
                badgeHeld = false;
                if (held) {
                    // The long-press already fired and already did the work.
                } else if (onBadge && maybeTap) {
                    if (listener != null) listener.onBadgeTapped(badge);
                } else if (dragFrom >= 0 && !maybeTap && dragOver >= 0 && dragOver != dragFrom
                        && listener != null) {
                    listener.onCellDragged(dragFrom, dragOver);
                } else if (maybeTap) {
                    handleTap(x, y);
                }
                dragFrom = dragOver = -1;
                draggingPivot = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelBadge();
                dragFrom = dragOver = -1;
                draggingPivot = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private boolean toSource(float vx, float vy, float[] out) {
        if (!viewMatrix.invert(inverse)) return false;
        out[0] = vx; out[1] = vy;
        inverse.mapPoints(out);
        return true;
    }

    private boolean isInSelectedCell(float vx, float vy) {
        if (renderer == null || sheet == null || selectedCell < 0) return false;
        float[] p = new float[2];
        if (!toSource(vx, vy, p)) return false;
        Rect r = SpriteSheetRenderer.cellRectSource(sheet, selectedCell, renderer.sourceWidth(), renderer.sourceHeight());
        return r.contains(Math.round(p[0]), Math.round(p[1]));
    }

    private void updatePivotFromTouch(float vx, float vy) {
        if (renderer == null || sheet == null || selectedCell < 0) return;
        float[] p = new float[2];
        if (!toSource(vx, vy, p)) return;
        Rect r = SpriteSheetRenderer.cellRectSource(sheet, selectedCell, renderer.sourceWidth(), renderer.sourceHeight());
        float px = (p[0] - r.left) / Math.max(1f, r.width());
        float py = (p[1] - r.top) / Math.max(1f, r.height());
        sheet.setPivot(px, py);
        if (listener != null) listener.onPivotChanged(sheet.getPivotX(), sheet.getPivotY());
        invalidate();
    }

    /** The display slot under a view-space point, or -1. */
    private int cellAtPoint(float vx, float vy) {
        if (renderer == null || sheet == null) return -1;
        float[] p = new float[2];
        if (!toSource(vx, vy, p)) return -1;
        for (int i = 0; i < sheet.cellCount(); i++) {
            Rect r = SpriteSheetRenderer.cellRectSource(
                    sheet, i, renderer.sourceWidth(), renderer.sourceHeight());
            if (r.contains(Math.round(p[0]), Math.round(p[1]))) return i;
        }
        return -1;
    }

    private void handleTap(float vx, float vy) {
        if (renderer == null || sheet == null) return;
        float[] p = new float[2];
        if (!toSource(vx, vy, p)) return;
        if (colorPickMode) {
            android.graphics.Bitmap bmp = renderer.getBitmap();
            int bx = Math.round(p[0] * bmp.getWidth() / Math.max(1, renderer.sourceWidth()));
            int by = Math.round(p[1] * bmp.getHeight() / Math.max(1, renderer.sourceHeight()));
            if (bx >= 0 && bx < bmp.getWidth() && by >= 0 && by < bmp.getHeight()) {
                colorPickMode = false;
                if (listener != null) listener.onColorPicked(bmp.getPixel(bx, by));
            }
            return;
        }
        for (int i = 0; i < sheet.cellCount(); i++) {
            Rect r = SpriteSheetRenderer.cellRectSource(sheet, i, renderer.sourceWidth(), renderer.sourceHeight());
            if (r.contains(Math.round(p[0]), Math.round(p[1]))) {
                selectedCell = i;
                if (listener != null) listener.onCellTapped(i);
                invalidate();
                return;
            }
        }
    }
}
