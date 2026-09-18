package com.fadcam.ui.faditor.avatar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * A1-UI (PLAN_AVATAR_STUDIO): the pose-matrix widget — the Moho/CTA-style
 * extreme grid. AUTHORED cells draw solid, un-authored cells draw dashed
 * ("auto-blended", the MINED authoring cue), the ARMED cell gets the accent
 * ring, and a marker shows where the current driver values sit in the grid
 * (the blend point the puppet is showing right now).
 *
 * <p>Pure display + tap surface; the activity owns arming rules and all rig
 * mutation. Rows are drawn TOP = pitch -1 (looking up) matching the resolver's
 * gy mapping, so what you tap is what the sliders drive.</p>
 */
public class PoseMatrixView extends View {

    public interface Listener {
        void onCellTapped(int col, int row);
    }

    private int cols = 3, rows = 3;
    /** Bit per cell (row * cols + col) — authored = has a Cell in the domain. */
    private boolean[] authored = new boolean[9];
    private int armedCol = -1, armedRow = -1;
    /** Driver position in grid space (0..cols-1, 0..rows-1), for the marker. */
    private float driverGx = 1f, driverGy = 1f;
    @Nullable private Listener listener;

    private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint armedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cellRect = new RectF();

    public PoseMatrixView(Context ctx) {
        super(ctx);
        solid.setStyle(Paint.Style.STROKE);
        solid.setStrokeWidth(3f);
        solid.setColor(0xFF52525B);
        dashed.setStyle(Paint.Style.STROKE);
        dashed.setStrokeWidth(2f);
        dashed.setColor(0x66FFFFFF);
        dashed.setPathEffect(new DashPathEffect(new float[]{8f, 6f}, 0f));
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x2E52525B);
        armedPaint.setStyle(Paint.Style.STROKE);
        armedPaint.setStrokeWidth(5f);
        armedPaint.setColor(0xFFFBBF24);
        marker.setStyle(Paint.Style.FILL);
        marker.setColor(0xFF22D3EE);
    }

    public void setListener(@Nullable Listener l) { this.listener = l; }

    public void setGrid(int cols, int rows, boolean[] authoredFlags) {
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        this.authored = authoredFlags != null && authoredFlags.length >= this.cols * this.rows
                ? authoredFlags : new boolean[this.cols * this.rows];
        invalidate();
    }

    /** Armed cell, or (-1,-1) = disarmed. */
    public void setArmed(int col, int row) {
        this.armedCol = col;
        this.armedRow = row;
        invalidate();
    }

    /** Current driver point in grid coordinates (matches resolver gx/gy). */
    public void setDriverPoint(float gx, float gy) {
        this.driverGx = gx;
        this.driverGy = gy;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_MOVE;
        }
        float cw = (float) getWidth() / cols, ch = (float) getHeight() / rows;
        int col = (int) (e.getX() / cw), row = (int) (e.getY() / ch);
        if (col >= 0 && col < cols && row >= 0 && row < rows && listener != null) {
            listener.onCellTapped(col, row);
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cw = (float) getWidth() / cols, ch = (float) getHeight() / rows;
        float inset = 4f;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                cellRect.set(c * cw + inset, r * ch + inset,
                        (c + 1) * cw - inset, (r + 1) * ch - inset);
                boolean isAuthored = authored[r * cols + c];
                if (isAuthored) {
                    canvas.drawRoundRect(cellRect, 8f, 8f, fill);
                    canvas.drawRoundRect(cellRect, 8f, 8f, solid);
                } else {
                    canvas.drawRoundRect(cellRect, 8f, 8f, dashed);
                }
                if (c == armedCol && r == armedRow) {
                    canvas.drawRoundRect(cellRect, 8f, 8f, armedPaint);
                }
            }
        }
        // Driver marker: grid coords → pixel center interpolation.
        float mx = cols > 1 ? (driverGx / (cols - 1)) * (getWidth() - cw) + cw / 2f : getWidth() / 2f;
        float my = rows > 1 ? (driverGy / (rows - 1)) * (getHeight() - ch) + ch / 2f : getHeight() / 2f;
        canvas.drawCircle(mx, my, 9f, marker);
    }
}
