package com.fadcam.vislab.room;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

/** Children left to right, wrapping to a new line when the row is full. */
final class WrapLayout extends ViewGroup {

    private final int gapX, gapY;

    WrapLayout(@NonNull Context c, int gapXPx, int gapYPx) {
        super(c);
        gapX = gapXPx;
        gapY = gapYPx;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int maxW = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, lineH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            measureChild(ch, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), hSpec);
            int cw = ch.getMeasuredWidth(), chh = ch.getMeasuredHeight();
            if (x > 0 && x + cw > maxW) {
                x = 0;
                y += lineH + gapY;
                lineH = 0;
            }
            x += cw + gapX;
            lineH = Math.max(lineH, chh);
        }
        setMeasuredDimension(MeasureSpec.getSize(wSpec), y + lineH + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxW = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, lineH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            int cw = ch.getMeasuredWidth(), chh = ch.getMeasuredHeight();
            if (x > 0 && x + cw > maxW) {
                x = 0;
                y += lineH + gapY;
                lineH = 0;
            }
            ch.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + cw, getPaddingTop() + y + chh);
            x += cw + gapX;
            lineH = Math.max(lineH, chh);
        }
    }
}
