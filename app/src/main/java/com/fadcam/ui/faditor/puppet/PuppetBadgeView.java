package com.fadcam.ui.faditor.puppet;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * THE WAY IN — a marionette in the corner of the picture, on the ordinary transform surface.
 *
 * <p>JoyRaptor, 2026-09-15: <i>"somewhere in the upper right, there needs to be the little man
 * icon if there is pins detected. That way you can go right into it without double tapping on the
 * image itself and bringing up the drawer. That way it goes to grab default first."</i>
 *
 * <p>So this is not the badge that lives inside {@link PuppetOverlayView} — that one is the LOCK,
 * and it is drawn by the pin surface itself. This one appears on the surface you get by simply
 * tapping a picture, it is GREY because the pins are not live yet, and tapping it turns them on
 * with Grab already armed. Two badges, one glyph, opposite jobs: this one wakes the rig, that one
 * puts it to sleep.
 *
 * <h3>Why a separate small view and not a branch inside TransformOverlayView</h3>
 * <p>That class is 2,169 lines and owns every touch on the picture. A full-screen sibling reading
 * {@link MotionEvent}s beside it is precisely the several-views-one-hit-test bug its own doc
 * records. This view is 38dp square and occupies nothing else, so it can sit on top of the
 * transform handles without ever taking a touch that was meant for them.
 *
 * <p>Nothing here knows what a rig is beyond "how many pins" — the host decides when it shows.
 */
public class PuppetBadgeView extends View {

    public interface Host {
        /** Turn the pins on: open the puppet surface with Grab armed. */
        void onEnterPuppet();

        /**
         * Held rather than tapped: the pins AND the drawer.
         *
         * <p>JoyRaptor, 2026-09-16: <i>"perhaps a long press on him brings up the drawer and
         * enables pins."</i> The tap is for posing — you want the picture uncovered. The hold is
         * for when you came to change what a touch MEANS, which is what the drawer is for.
         */
        default void onEnterPuppetWithDrawer() { onEnterPuppet(); }
    }

    private static final float SIZE_DP = 38f;

    /** Held this long and he brings the drawer with him. Matches the preview's own long press. */
    private static final long HOLD_MS = 420L;
    private long downAt;

    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    @Nullable private Host host;
    private int pinCount;
    private boolean pressed;

    public PuppetBadgeView(@NonNull Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        setContentDescription("Puppet pins — tap to move them");     // TODO(strings)
    }

    public void setHost(@Nullable Host h) { this.host = h; }

    /** How many pins the selected picture has. 0 hides the badge entirely. */
    public void setPinCount(int n) {
        if (n == pinCount) return;
        pinCount = n;
        setVisibility(n > 0 ? VISIBLE : GONE);
        invalidate();
    }

    public int pinCount() { return pinCount; }

    /** The square this wants to occupy, in pixels — the host uses it for the layout params. */
    public int sizePx() { return Math.round(SIZE_DP * d); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(sizePx(), sizePx());
    }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        if (pinCount <= 0) return;

        box.set(0.5f * d, 0.5f * d, getWidth() - 0.5f * d, getHeight() - 0.5f * d);

        fill.setColor(pressed ? 0xCC17171C : 0xA80D0D10);
        c.drawRoundRect(box, 10f * d, 10f * d, fill);
        stroke.setColor(0x1AFFFFFF);
        stroke.setStrokeWidth(1f * d);
        c.drawRoundRect(box, 10f * d, 10f * d, stroke);

        // GREY, deliberately. The pins exist but do not answer to touch yet, and the badge says
        // exactly that: it is the same figure the pin surface draws, wearing the same colour it
        // wears there when the rig is locked. Tapping it is what turns him green.
        int size = Math.round(21f * d);
        PuppetIcons.IconDrawable man = PuppetIcons.of(
                PuppetIcons.PUPPET, pressed ? 0xFF8A8A94 : 0xFF8A8A94, size);
        int left = Math.round(getWidth() / 2f - size / 2f);
        int top = Math.round(getHeight() / 2f - size / 2f);
        man.setBounds(left, top, left + size, top + size);
        man.draw(c);

        // A small count, so "this picture is rigged, and how much" reads without a tap.
        fill.setColor(0xFF8A8A94);
        fill.setTextSize(8f * d);
        fill.setTextAlign(Paint.Align.CENTER);
        fill.setFakeBoldText(true);
        c.drawText(String.valueOf(pinCount), getWidth() / 2f, getHeight() - 3.5f * d, fill);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(@NonNull MotionEvent e) {
        if (pinCount <= 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                downAt = System.currentTimeMillis();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                boolean inside = e.getX() >= 0 && e.getY() >= 0
                        && e.getX() <= getWidth() && e.getY() <= getHeight();
                pressed = false;
                invalidate();
                if (inside && host != null) {
                    performClick();
                    if (System.currentTimeMillis() - downAt > HOLD_MS) {
                        host.onEnterPuppetWithDrawer();
                    } else {
                        host.onEnterPuppet();
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() { return super.performClick(); }
}
