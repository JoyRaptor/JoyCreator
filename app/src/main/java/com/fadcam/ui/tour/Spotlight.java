package com.fadcam.ui.tour;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.TooltipCompat;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.ai.joybot.RoomColours;

import java.util.ArrayList;
import java.util.List;

/**
 * A guided tour on any screen (BOT-7, owner 11-15): the screen dims, one control stays lit, and
 * Joybot says what it is for, with Next and Skip. Generic: a screen hands it a list of steps (a
 * view and a line each); steps whose view isn't on screen are skipped, so a tour never points at
 * nothing. Only the lobby is scripted for now ({@link LobbyTour}); the rooms get theirs once they
 * stop changing.
 */
public final class Spotlight {
    private Spotlight() { }

    /** One stop: the control to light and Joybot's line about it. */
    public static final class Step {
        @NonNull public final View target;
        @NonNull public final String line;

        public Step(@NonNull View target, @NonNull String line) {
            this.target = target;
            this.line = line;
        }
    }

    public interface OnEnd { void ended(boolean finished); }

    /** Shows the tour over {@code a}; returns false (and shows nothing) when no step's control is on screen. */
    public static boolean show(@NonNull Activity a, @NonNull List<Step> steps, @Nullable OnEnd onEnd) {
        List<Step> live = new ArrayList<>();
        for (Step s : steps) if (s.target.isShown() && s.target.getWidth() > 0) live.add(s);
        if (live.isEmpty()) return false;
        ViewGroup root = a.findViewById(android.R.id.content);
        if (root == null) return false;
        Overlay o = new Overlay(a, live, onEnd);
        root.addView(o, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        o.go(0);
        return true;
    }

    private static final class Overlay extends FrameLayout {
        private final List<Step> steps;
        @Nullable private final OnEnd onEnd;
        private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path hole = new Path();
        private final RectF lit = new RectF();
        private final LinearLayout card;
        private final TextView words, next;
        private int at;

        Overlay(@NonNull Context c, @NonNull List<Step> steps, @Nullable OnEnd onEnd) {
            super(c);
            this.steps = steps;
            this.onEnd = onEnd;
            setWillNotDraw(false);
            setClickable(true);   // the dimmed screen doesn't take taps while the tour is on
            scrim.setColor(0xC8000000);

            card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(14);
            card.setPadding(pad, pad, pad, dp(10));
            RoomColours.Pair joy = RoomColours.of(RoomColours.JOYBOT);
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{joy.start, joy.end});
            bg.setCornerRadius(dp(16));
            card.setBackground(bg);
            words = new TextView(c);
            words.setTextColor(joy.ink);
            words.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            card.addView(words);
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.END);
            row.setPadding(0, dp(10), 0, 0);
            TextView skip = button(c, "Skip", "End the tour", false);
            skip.setOnClickListener(v -> end(false));
            next = button(c, "Next", "The next thing", true);
            next.setOnClickListener(v -> go(at + 1));
            row.addView(skip);
            row.addView(next);
            card.addView(row);
            addView(card, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void go(int i) {
            if (i >= steps.size()) { end(true); return; }
            at = i;
            Step s = steps.get(i);
            Rect r = new Rect(0, 0, s.target.getWidth(), s.target.getHeight());
            s.target.requestRectangleOnScreen(r, true);   // scrolls it into view if it's below the fold
            words.setText(s.line);
            next.setText(i == steps.size() - 1 ? "Done" : "Next");
            card.setVisibility(INVISIBLE);
            post(this::place);   // after any scroll has settled
            announceForAccessibility(s.line);
        }

        private void place() {
            Step s = steps.get(at);
            int[] t = new int[2], me = new int[2];
            s.target.getLocationInWindow(t);
            getLocationInWindow(me);
            float m = dp(6);
            lit.set(t[0] - me[0] - m, t[1] - me[1] - m, t[0] - me[0] + s.target.getWidth() + m,
                    t[1] - me[1] + s.target.getHeight() + m);
            hole.reset();
            hole.setFillType(Path.FillType.EVEN_ODD);
            hole.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            hole.addRoundRect(lit, dp(14), dp(14), Path.Direction.CW);
            // The card goes below the lit control when there's room, otherwise above it.
            card.measure(MeasureSpec.makeMeasureSpec(getWidth() - dp(32), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.AT_MOST));
            int h = card.getMeasuredHeight();
            float y = lit.bottom + dp(12) + h < getHeight() ? lit.bottom + dp(12) : Math.max(dp(12), lit.top - dp(12) - h);
            LayoutParams lp = (LayoutParams) card.getLayoutParams();
            lp.leftMargin = dp(16);
            lp.rightMargin = dp(16);
            lp.topMargin = Math.round(y);
            card.setLayoutParams(lp);
            card.setVisibility(VISIBLE);
            invalidate();
        }

        @Override protected void onDraw(@NonNull Canvas c) {
            c.drawPath(hole, scrim);
        }

        private void end(boolean finished) {
            ViewGroup p = (ViewGroup) getParent();
            if (p != null) p.removeView(this);
            if (onEnd != null) onEnd.ended(finished);
        }

        @NonNull
        private TextView button(@NonNull Context c, @NonNull String text, @NonNull String hint, boolean primary) {
            TextView b = new TextView(c);
            b.setText(text);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            b.setGravity(Gravity.CENTER);
            b.setMinHeight(dp(36));
            b.setPadding(dp(16), 0, dp(16), 0);
            GradientDrawable g = new GradientDrawable();
            g.setColor(primary ? 0xFFFFFFFF : 0x33FFFFFF);
            g.setCornerRadius(dp(18));
            b.setBackground(g);
            b.setTextColor(primary ? Studio.GROUND : 0xFFFFFFFF);
            TooltipCompat.setTooltipText(b, hint);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            lp.setMarginStart(dp(8));
            b.setLayoutParams(lp);
            return b;
        }

        private int dp(int v) {
            return Math.round(v * getResources().getDisplayMetrics().density);
        }
    }
}
