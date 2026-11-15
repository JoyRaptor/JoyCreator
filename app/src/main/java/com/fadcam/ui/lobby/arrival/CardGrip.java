package com.fadcam.ui.lobby.arrival;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Lies over the chat card and takes only touches that start on its edges: drag the left, right or
 * bottom edge, or the bottom-right corner, to size the card. Every other touch goes through to the
 * chat underneath (the Send button sits 8 dp in from the corner, so the edges stay clear of it).
 * Draws a faint corner mark so the corner reads as something to pull.
 */
public final class CardGrip extends View {

    /** How far in from an edge a touch still counts as the edge, dp. */
    static final float EDGE_DP = 9f;
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CardGrip(@NonNull Context c) { this(c, null); }

    public CardGrip(@NonNull Context c, @Nullable AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        mark.setStyle(Paint.Style.STROKE);
        mark.setStrokeWidth(1.5f * d);
        mark.setStrokeCap(Paint.Cap.ROUND);
        mark.setColor(0x6671717A);
    }

    /** Which edges a touch at {@code x, y} grabs: bit 1 left, 2 right, 4 bottom; 0 = none. */
    int edgesAt(float x, float y) {
        float z = EDGE_DP * getResources().getDisplayMetrics().density;
        int e = 0;
        if (x < z) e |= 1;
        if (x > getWidth() - z) e |= 2;
        if (y > getHeight() - z) e |= 4;
        if (e == 0 && x > getWidth() - 1.6f * z && y > getHeight() - 1.6f * z) e = 2 | 4;
        return e;
    }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight(), in = 7f * d;
        c.drawLine(w - in - 9f * d, h - in, w - in, h - in - 9f * d, mark);
        c.drawLine(w - in - 4.5f * d, h - in, w - in, h - in - 4.5f * d, mark);
    }
}
