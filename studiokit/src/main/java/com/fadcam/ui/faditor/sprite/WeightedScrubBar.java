package com.fadcam.ui.faditor.sprite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** The SpriteLab weighted scrub bar. The live model is supplied by each host. */
public final class WeightedScrubBar extends View {
    public interface Model {
        int size();
        int holdAt(int index);
        int currentIndex();
        void onScrub(int index, boolean finished);
    }
    private final Model model;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int nowColor, offColor;
    public WeightedScrubBar(Context context, Model model, int nowColor, int offColor) {
        super(context);
        this.model = model; this.nowColor = nowColor; this.offColor = offColor;
        setContentDescription("Scrub frames");
        if (android.os.Build.VERSION.SDK_INT >= 26) setTooltipText("Scrub frames");
    }
    private long total() {
        long total = 0;
        for (int i = 0; i < model.size(); i++) total += Math.max(1, model.holdAt(i));
        return total;
    }
    @Override protected void onDraw(Canvas canvas) {
        long total = total();
        if (total == 0) return;
        float x = 0;
        for (int i = 0; i < model.size(); i++) {
            float segment = getWidth() * (Math.max(1, model.holdAt(i)) / (float) total);
            paint.setColor(i == model.currentIndex() ? nowColor : offColor);
            canvas.drawRect(x + 1, 2, x + segment - 1, getHeight() - 2, paint);
            x += segment;
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (model.size() == 0) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            double tick = Math.max(0, Math.min(0.999, event.getX() / Math.max(1, getWidth()))) * total();
            long accumulated = 0;
            for (int i = 0; i < model.size(); i++) {
                accumulated += Math.max(1, model.holdAt(i));
                if (tick < accumulated) { model.onScrub(i, false); break; }
            }
            invalidate();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            model.onScrub(model.currentIndex(), true);
            if (action == MotionEvent.ACTION_UP) performClick();
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
