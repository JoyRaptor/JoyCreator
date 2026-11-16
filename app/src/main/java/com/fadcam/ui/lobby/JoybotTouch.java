package com.fadcam.ui.lobby;

import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Touching Joybot (owner, 11-15 midday): a tap goes through his click (so TalkBack and a keyboard reach it the same
 * way) and wakes or plays him; a poke, a short push, nudges him the way the finger went; a flick, a fast one, flings
 * him round the chat. What he does for each is {@link JoybotDirector}'s.
 */
public final class JoybotTouch implements View.OnTouchListener {

    /** Faster than this (dp a second) a push is a flick, not a poke. */
    private static final float FLICK_DP_S = 900f;

    private final JoybotView bot;
    private final float slop, dp;
    @Nullable private VelocityTracker vt;
    private float x0, y0;
    private boolean moved;

    private JoybotTouch(@NonNull JoybotView bot) {
        this.bot = bot;
        this.slop = ViewConfiguration.get(bot.getContext()).getScaledTouchSlop();
        this.dp = bot.getResources().getDisplayMetrics().density;
    }

    /** Let him be tapped, poked and flicked. His click still runs for a tap. */
    public static void attach(@NonNull JoybotView bot) {
        bot.setOnTouchListener(new JoybotTouch(bot));
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                x0 = e.getRawX();
                y0 = e.getRawY();
                moved = false;
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                track(e);
                return true;
            case MotionEvent.ACTION_MOVE:
                track(e);
                if (!moved && Math.hypot(e.getRawX() - x0, e.getRawY() - y0) > slop) moved = true;
                return true;
            case MotionEvent.ACTION_UP:
                track(e);
                float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                if (!moved) {
                    v.performClick();
                } else {
                    float vx = 0f, vy = 0f;
                    if (vt != null) {
                        vt.computeCurrentVelocity(1000);
                        vx = vt.getXVelocity();
                        vy = vt.getYVelocity();
                    }
                    if (Math.hypot(vx, vy) / dp > FLICK_DP_S) JoybotDirector.flung(bot, vx, vy);
                    else JoybotDirector.poked(bot, dx, dy);
                }
                done();
                return true;
            case MotionEvent.ACTION_CANCEL:
                done();
                return true;
            default:
                return true;
        }
    }

    /** Velocity on the screen, not on him: he moves while he is touched (a nudge), and his own coordinates move with him. */
    private void track(@NonNull MotionEvent e) {
        if (vt == null) return;
        MotionEvent s = MotionEvent.obtain(e);
        s.setLocation(e.getRawX(), e.getRawY());
        vt.addMovement(s);
        s.recycle();
    }

    private void done() {
        if (vt != null) { vt.recycle(); vt = null; }
    }
}
