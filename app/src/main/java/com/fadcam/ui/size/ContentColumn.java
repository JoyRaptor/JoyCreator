package com.fadcam.ui.size;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.fadcam.R;

/**
 * Keeps a screen's content in a centred column on a wide window (TAB-1, 2026-11-14).
 *
 * <p>Adds equal padding on both sides of a view until what is inside is at most
 * {@link SizeClass#READING_COLUMN_DP} wide. The background still fills the window; only the
 * words, rows and buttons stop stretching. Measured from the view's own width on every layout,
 * so split-screen, a phone turned sideways and a window resized live all follow. A view that
 * already fits (any phone held upright) gets zero extra padding: the phone layout is untouched.
 *
 * <p>Any padding the view had (its own, or what an insets listener set later for a camera
 * cut-out) is kept: only the part this class added is ever replaced.
 *
 * <p>Two ways in:
 * <ul>
 *   <li>{@link #keep(View)} on a root view (activities, one-off screens);</li>
 *   <li>a fragment {@code implements ContentColumn.Page}, and the activity calls
 *       {@link #watch(FragmentManager)} once; every such page then gets the column when its
 *       view is made.</li>
 * </ul>
 */
public final class ContentColumn {

    private ContentColumn() {}

    /** A fragment whose whole page should sit in the reading column on a wide window. */
    public interface Page {}

    /** The reading column ({@link SizeClass#READING_COLUMN_DP}). */
    public static void keep(@NonNull View root) {
        keep(root, SizeClass.READING_COLUMN_DP);
    }

    /** A column of at most {@code maxDp}. Calling it again on the same view only changes the width. */
    public static void keep(@NonNull View root, int maxDp) {
        Object tag = root.getTag(R.id.content_column_state);
        int maxPx = Math.round(maxDp * root.getResources().getDisplayMetrics().density);
        if (tag instanceof State) {
            ((State) tag).maxPx = maxPx;
            root.requestLayout();
            return;
        }
        State st = new State(maxPx);
        root.setTag(R.id.content_column_state, st);
        root.addOnLayoutChangeListener(st);
        if (root.getWidth() > 0) st.fit(root, root.getWidth());
    }

    /**
     * Gives every {@link Page} fragment shown by {@code fm} (and its children) the column.
     * Call once, in the activity's {@code onCreate}.
     */
    public static void watch(@NonNull FragmentManager fm) {
        fm.registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentViewCreated(@NonNull FragmentManager m, @NonNull Fragment f,
                                              @NonNull View v, @Nullable Bundle saved) {
                if (f instanceof Page) keep(v);
            }
        }, true);
    }

    /** Pixels for a dp value, for callers sizing things to match the column. */
    public static int px(@NonNull Context c, int dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }

    private static final class State implements View.OnLayoutChangeListener {
        int maxPx;
        /** The padding this class added on EACH side last time; the rest belongs to the view. */
        int added;

        State(int maxPx) { this.maxPx = maxPx; }

        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b,
                                   int ol, int ot, int or, int ob) {
            fit(v, r - l);
        }

        void fit(View v, int width) {
            int baseL = v.getPaddingLeft() - added;
            int baseR = v.getPaddingRight() - added;
            int want = SizeClass.sideInset(width, baseL, baseR, maxPx);
            if (want == added) return;
            added = want;
            // Inside a layout pass: Android runs a second pass before drawing, so the full-width
            // frame is never shown.
            v.setPadding(baseL + want, v.getPaddingTop(), baseR + want, v.getPaddingBottom());
        }
    }
}
