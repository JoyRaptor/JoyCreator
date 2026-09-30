package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;

import java.util.ArrayList;
import java.util.List;

/**
 * A thin bar of recent colours (D.02c): a hair of colour that gets longer as the history grows and divides into equal
 * segments, most recent at the end nearest the tools. Tap a segment, or press and slide along the bar (the segment under
 * the finger lights up and is taken on lift), and {@link OnPick} gets that colour back.
 *
 * <p>It draws 6 dp thick ("discreet") but its hit area is 32 dp, so a thin bar is still easy to hit. The current
 * colour's segment has a 1 dp outline. It follows {@link ColorRecents} by itself: it registers while attached, so a colour
 * pushed from the picker, an eyedropper or another screen redraws it. Chrome colours are {@link Studio} tokens only.</p>
 */
public final class RecentColorsBar extends View {

    /** Receives the colour the person tapped. */
    public interface OnPick { void onPick(@ColorInt int argb); }

    private static final float THICK_DP = 6f;
    private static final float HIT_DP = 32f;
    private static final float PER_COLOUR_DP = 16f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF bar = new RectF();
    private final float density;

    private List<Integer> colours = new ArrayList<>();
    private boolean vertical;
    private int current = 0;
    private boolean haveCurrent;
    private int pressedIndex = -1;
    @Nullable private OnPick onPick;

    private final ColorRecents.Listener listener = this::reload;

    public RecentColorsBar(@NonNull Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(density);
        ring.setColor(Studio.INK);
        setContentDescription("Recent colours");
        reload();
    }

    public void setOnPick(@Nullable OnPick p) { onPick = p; }

    /** Vertical for a bar beside a side rail; horizontal otherwise (the default). */
    public void setOrientation(boolean vertical) {
        this.vertical = vertical;
        requestLayout();
        invalidate();
    }

    /** The colour in use now: its segment gets the outline. Ignores alpha, like the history does. */
    public void setCurrent(@ColorInt int argb) {
        current = argb;
        haveCurrent = true;
        invalidate();
    }

    private void reload() {
        colours = ColorRecents.get(getContext());
        requestLayout();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ColorRecents.addListener(listener);
        reload();
    }

    @Override
    protected void onDetachedFromWindow() {
        ColorRecents.removeListener(listener);
        super.onDetachedFromWindow();
    }

    /** How long the bar is for [n] colours: 16 dp each, up to the cap. */
    private float lengthPx(int n) {
        return Math.min(n, ColorRecents.MAX) * PER_COLOUR_DP * density;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float len = Math.max(lengthPx(colours.size()), 0f);
        float hit = HIT_DP * density;
        int w = Math.round(vertical ? hit : len);
        int h = Math.round(vertical ? len : hit);
        setMeasuredDimension(resolveSize(w, widthSpec), resolveSize(h, heightSpec));
    }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        int n = colours.size();
        if (n == 0) return;
        float len = lengthPx(n), thick = THICK_DP * density;
        if (vertical) bar.set((getWidth() - thick) / 2f, 0f, (getWidth() + thick) / 2f, len);
        else bar.set(0f, (getHeight() - thick) / 2f, len, (getHeight() + thick) / 2f);
        clip.reset();
        clip.addRoundRect(bar, thick / 2f, thick / 2f, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        float seg = len / n;
        for (int i = 0; i < n; i++) {
            // Most recent at the END nearest the tools: index 0 is the newest, so it is drawn last (at the far end).
            int slot = n - 1 - i;
            fill.setColor(colours.get(i) | 0xFF000000);
            if (vertical) c.drawRect(bar.left, bar.top + slot * seg, bar.right, bar.top + (slot + 1) * seg, fill);
            else c.drawRect(bar.left + slot * seg, bar.top, bar.left + (slot + 1) * seg, bar.bottom, fill);
        }
        c.restore();
        // The current colour's segment, outlined; the pressed one, outlined thicker.
        for (int i = 0; i < n; i++) {
            boolean isCur = haveCurrent && ((colours.get(i) & 0xFFFFFF) == (current & 0xFFFFFF));
            boolean isPress = i == pressedIndex;
            if (!isCur && !isPress) continue;
            int slot = n - 1 - i;
            ring.setStrokeWidth(isPress ? 2f * density : density);
            if (vertical) c.drawRect(bar.left, bar.top + slot * seg, bar.right, bar.top + (slot + 1) * seg, ring);
            else c.drawRect(bar.left + slot * seg, bar.top, bar.left + (slot + 1) * seg, bar.bottom, ring);
        }
    }

    /** Which history index sits under a touch, or -1. */
    private int indexAt(float x, float y) {
        int n = colours.size();
        if (n == 0) return -1;
        float len = lengthPx(n);
        float along = vertical ? y : x;
        if (along < 0f || along >= len) return -1;
        int slot = (int) (along / (len / n));
        return n - 1 - Math.min(slot, n - 1);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                pressedIndex = indexAt(e.getX(), e.getY());
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP: {
                int i = indexAt(e.getX(), e.getY());
                pressedIndex = -1;
                invalidate();
                if (i >= 0 && onPick != null) { onPick.onPick(colours.get(i) | 0xFF000000); performClick(); }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressedIndex = -1;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() { return super.performClick(); }
}
