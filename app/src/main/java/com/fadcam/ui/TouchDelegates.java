package com.fadcam.ui;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * One {@link TouchDelegate} that holds several, plus the one way this app grows a row of
 * small controls into targets you can actually hit.
 *
 * <p><b>Why this is a class and not four lines inline.</b> A View has exactly ONE touch
 * delegate. The obvious loop — set one per child — silently keeps only the last and leaves
 * every other control exactly as small as it was. That bug is why this exists; it cost a
 * round of measurements in the editor before it was spotted.
 *
 * <p><b>Why the glyph never grows.</b> Studio Final §04 puts the floor at 28dp and the norm
 * at 40 or 44, but the drawings also fix the type sizes. Both hold at once only if the box
 * you can hit is bigger than the mark you can see — so these rectangles change what a touch
 * lands on and nothing about what is drawn.
 *
 * <p><b>The limit worth knowing.</b> A delegate cannot reach outside the view that holds it:
 * an event that never lands on the container is never offered to it. Making a target taller
 * than its row therefore does nothing, and the row itself has to be tall enough first.
 */
public final class TouchDelegates extends TouchDelegate {

    private final List<TouchDelegate> parts = new ArrayList<>();

    public TouchDelegates(View host) {
        super(new Rect(), host);
    }

    public void add(TouchDelegate d) {
        parts.add(d);
    }

    public boolean isEmpty() {
        return parts.isEmpty();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // Reset per delegate: TouchDelegate OFFSETS the event it is given, so handing the
        // same one to the next delegate would hand it already-shifted coordinates.
        float x = e.getX(), y = e.getY();
        for (TouchDelegate d : parts) {
            e.setLocation(x, y);
            if (d.onTouchEvent(e)) return true;
        }
        return false;
    }

    /**
     * Grows every clickable child of {@code row} to fill the row's height and half the gap
     * to each neighbour.
     *
     * <p>Half, not all, so no two rectangles overlap and no touch is ever ambiguous. The
     * ends run to the container's own edges, because the space past the last control
     * belongs to nothing else.
     *
     * <p>Only <b>clickable</b> children are given one. Rows in this app separate their
     * groups with weighted {@code Space}s, and a Space has real width — handing one a
     * delegate pointing at something untappable leaves the gap as dead as it was and steals
     * width from the controls either side of it.
     *
     * <p>Uses layout bounds rather than {@code getHitRect}, so a child the caller is
     * scaling (the lobby marquee scales every word) keeps the slot it was laid out in
     * instead of the size it happens to be drawn at this frame.
     *
     * <p>Rebuilt on every layout pass, not posted once: posting races the first layout,
     * where the row's height is still 0 and a delegate built from an empty rectangle
     * swallows every touch handed to it.
     */
    public static void growChildren(ViewGroup row) {
        if (row == null) return;
        row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> apply(row));
        apply(row);
    }

    private static void apply(ViewGroup row) {
        int h = row.getHeight();
        if (h <= 0) return;

        List<View> kids = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE && c.isClickable() && c.getWidth() > 0) {
                kids.add(c);
            }
        }
        if (kids.isEmpty()) {
            row.setTouchDelegate(null);
            return;
        }

        TouchDelegates all = new TouchDelegates(row);
        for (int i = 0; i < kids.size(); i++) {
            View c = kids.get(i);
            int left  = (i == 0) ? 0
                    : (kids.get(i - 1).getRight() + c.getLeft()) / 2;
            int right = (i == kids.size() - 1) ? row.getWidth()
                    : (c.getRight() + kids.get(i + 1).getLeft()) / 2;
            all.add(new TouchDelegate(new Rect(left, 0, right, h), c));
        }
        row.setTouchDelegate(all);
    }
}
