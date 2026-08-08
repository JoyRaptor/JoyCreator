package com.fadcam.ui.faditor.overlay;

import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;

/**
 * The cumulative scale between a view's own pixels and the screen's.
 *
 * <p><b>Why this exists.</b> The editor SHRINKS {@code player_container} when a drawer opens, so
 * the preview stays visible underneath it. Every gesture surface inside that container computes
 * its drag as {@code (getRawX() - downRawX) / contentRect.width()} — a SCREEN-pixel delta over a
 * LOCAL-pixel rect. Those are the same number only while the scale is 1. At 0.6 the object moves
 * 60% of the finger's travel and slides out from under it, and the eyedropper samples a pixel
 * well above the one that was tapped.</p>
 *
 * <p>{@code getX()/getY()} are already mapped through the matrix and need none of this; it is
 * specifically the {@code getRaw*} family, and {@code getLocationOnScreen} deltas, that have to
 * be divided. Both are used deliberately in those surfaces — a raw delta survives the view
 * moving under the finger mid-gesture — so the fix is to correct them, not to abandon them.</p>
 */
public final class UiScale {

    private UiScale() {}

    /**
     * Product of {@code getScaleX()} up the whole parent chain, never zero.
     *
     * <p>The chain, not just the immediate parent: the container that gets scaled is several
     * levels above the view doing the maths, and a helper that only looked one step up would
     * silently return 1 and fix nothing.</p>
     */
    public static float of(@NonNull View v) {
        float s = 1f;
        View cur = v;
        while (cur != null) {
            s *= cur.getScaleX();
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        // A zero or negative scale would turn a divide into infinity or a mirrored drag. Neither
        // is a state the editor produces, but neither is worth crashing a gesture over either.
        return s > 0.0001f ? s : 1f;
    }
}
