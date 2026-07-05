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
    private boolean pivotMode = false;
    private boolean draggingPivot = false;
    private boolean colorPickMode = false;

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint();
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pivotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density = getResources().getDisplayMetrics().density;

    private final ScaleGestureDetector scaleDetector;
    private boolean scaling = false;
    private float lastX, lastY, downX, downY;
    private boolean maybeTap = false;
    private final float touchSlop;

    public SpriteGridEditorView(@NonNull Context ctx) {
        super(ctx);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1.5f * density);
        gridPaint.setColor(0xAA00E5A0);
        numPaint.setColor(0xFFFFFFFF);
        numPaint.setTextSize(11f * density);
        numPaint.setShadowLayer(3f * density, 0, 0, 0xCC000000);
        shadePaint.setColor(0x99000000);
        selPaint.setStyle(Paint.Style.STROKE);
        selPaint.setStrokeWidth(3f * density);
        selPaint.setColor(0xFFFFD54F);
        pivotPaint.setStyle(Paint.Style.STROKE);
        pivotPaint.setStrokeWidth(2f * density);
        pivotPaint.setColor(0xFFFF6E9C);
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

    /** Toggle pivot-editing: drags inside the selected cell move the pivot. */
    public void setPivotMode(boolean on) { pivotMode = on; invalidate(); }
    public boolean isPivotMode() { return pivotMode; }

    /** S2b: arm bg-key color picking — the next tap samples the sheet pixel. */
    public void setColorPickMode(boolean on) { colorPickMode = on; invalidate(); }
    public boolean isColorPickMode() { return colorPickMode; }

    /** Re-fit on next draw (geometry controls changed nothing about the matrix). */
    public void refresh() { invalidate(); }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xFF16161C);
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
        RectF srcSpace = new RectF(0, 0, renderer.sourceWidth(), renderer.sourceHeight());
        canvas.drawBitmap(renderer.getBitmap(), null, srcSpace, bmpPaint);

        int count = sheet.cellCount();
        for (int i = 0; i < count; i++) {
            Rect r = SpriteSheetRenderer.cellRectSource(sheet, i, renderer.sourceWidth(), renderer.sourceHeight());
            RectF rf = new RectF(r);
            SpriteSheet.Cell meta = sheet.cellAt(i);
            if (meta != null && !meta.enabled) canvas.drawRect(rf, shadePaint);
            canvas.drawRect(rf, gridPaint);
            if (i == selectedCell) canvas.drawRect(rf, selPaint);
        }
        canvas.restore();

        // Numbers + names drawn in VIEW space (constant size regardless of zoom).
        float[] pt = new float[2];
        for (int i = 0; i < count; i++) {
            Rect r = SpriteSheetRenderer.cellRectSource(sheet, i, renderer.sourceWidth(), renderer.sourceHeight());
            pt[0] = r.left; pt[1] = r.top;
            viewMatrix.mapPoints(pt);
            SpriteSheet.Cell meta = sheet.cellAt(i);
            String label = meta != null && !meta.name.isEmpty() ? i + " " + meta.name : String.valueOf(i);
            canvas.drawText(label, pt[0] + 3f * density, pt[1] + 12f * density, numPaint);
        }

        // Pivot crosshair inside the selected cell (sheet-level pivot, 0..1 of a cell).
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
