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
    }

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
    private final RectF badgeBox = new RectF();
    private final float density = getResources().getDisplayMetrics().density;

    /**
     * Where each cell sits in the roll being built — "3", or "2,5" when it is used twice.
     *
     * <p>The grid is where the sequence is READ. Without this the only record of the order
     * you tapped lives in the film strip, and you cannot see it while looking at the art.</p>
     */
    private final java.util.Map<Integer, String> orders = new java.util.HashMap<>();

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

    public void setSheet(@Nullable SpriteSheet sheet, @Nullable SpriteSheetRenderer renderer) {
        this.sheet = sheet;
        this.renderer = renderer;
        matrixInitialized = false;
        selectedCell = sheet != null && sheet.cellCount() > 0 ? 0 : -1;
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
        // ONE blit while nothing is aligned; cell by cell the moment anything is. A single
        // drawBitmap of the whole sheet cannot show a per-cell nudge, so the grid would have
        // sat there showing raw art while the preview showed the aligned frame — and lining
        // cells up AGAINST EACH OTHER on the grid is precisely what this screen is for. The
        // fast path keeps every sheet that has never been nudged exactly as cheap as before.
        RectF srcSpace = new RectF(0, 0, renderer.sourceWidth(), renderer.sourceHeight());
        if (sheet.getCellTransforms().isEmpty()) {
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
            canvas.drawRect(rf, gridPaint);
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
        for (int i = 0; i < count; i++) {
            Rect r = SpriteSheetRenderer.cellRectSource(sheet, i, renderer.sourceWidth(), renderer.sourceHeight());
            tl[0] = r.left; tl[1] = r.top;
            br[0] = r.right; br[1] = r.bottom;
            viewMatrix.mapPoints(tl);
            viewMatrix.mapPoints(br);
            if (br[0] - tl[0] < 26f * density) continue;   // too small to letter

            canvas.drawText(String.valueOf(i), tl[0] + 3f * density,
                    br[1] - 3f * density, numPaint);

            // Two stores, one meaning: the map is authoritative, the per-cell record is
            // what older sheets carry. Reading only one left named cells looking unnamed.
            String nm = sheet.cellName(i);
            if (nm == null || nm.isEmpty()) {
                SpriteSheet.Cell cm = sheet.cellAt(i);
                nm = cm == null ? null : cm.name;
            }
            if (nm != null && !nm.isEmpty()) {
                canvas.drawText(nm, br[0] - 3f * density, br[1] - 3f * density, namePaint);
            }
            String vis = visemeOf(i);
            if (vis != null) {
                canvas.drawText(vis, tl[0] + 3f * density, tl[1] + 10f * density, visPaint);
            }
            String ord = orders.get(i);
            if (ord != null) {
                float cx = (tl[0] + br[0]) / 2f;
                float w = badgeInk.measureText(ord) + 9f * density;
                badgeBox.set(cx - w / 2f, tl[1] + 2f * density,
                        cx + w / 2f, tl[1] + 15f * density);
                boolean now = i == playingCell;
                badgePaint.setColor(now ? SpriteTheme.LIVE : SpriteTheme.SELECTED);
                canvas.drawRoundRect(badgeBox, 4f * density, 4f * density, badgePaint);
                badgeInk.setColor(now ? 0xFFFFFFFF : SpriteTheme.ON_ACCENT);
                canvas.drawText(ord, cx, badgeBox.bottom - 3.2f * density, badgeInk);
            }
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

    /** The viseme this cell answers for, if any. A cell may hold an expression AND a mouth. */
    @Nullable
    private String visemeOf(int cell) {
        if (sheet == null) return null;
        for (java.util.Map.Entry<String, Integer> e : sheet.getVisemeMap().entrySet()) {
            if (e.getValue() != null && e.getValue() == cell) return e.getKey();
        }
        return null;
    }

    private void fitToView() {
        if (renderer == null) return;
        float sw = renderer.sourceWidth(), sh = renderer.sourceHeight();
        float pad = 12f * density;
        float scale = Math.min((getWidth() - 2 * pad) / sw, (getHeight() - 2 * pad) / sh);
        viewMatrix.reset();
        viewMatrix.postScale(scale, scale);
        viewMatrix.postTranslate((getWidth() - sw * scale) / 2f, (getHeight() - sh * scale) / 2f);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        if (scaling) { maybeTap = false; draggingPivot = false; return true; }
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = downX = x;
                lastY = downY = y;
                maybeTap = true;
                draggingPivot = pivotMode && selectedCell >= 0 && isInSelectedCell(x, y);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(x - downX) > touchSlop || Math.abs(y - downY) > touchSlop) maybeTap = false;
                if (draggingPivot) {
                    updatePivotFromTouch(x, y);
                } else if (!maybeTap) {
                    viewMatrix.postTranslate(x - lastX, y - lastY);
                    invalidate();
                }
                lastX = x; lastY = y;
                return true;
            case MotionEvent.ACTION_UP:
                if (maybeTap) handleTap(x, y);
                draggingPivot = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                draggingPivot = false;
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
