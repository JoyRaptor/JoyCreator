package com.fadcam.ui.faditor.util;

import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

/**
 * "Every button gets a hover label" (owner rule), as one mechanism instead of hundreds of calls:
 * a tappable view that already has a spoken name (content description) and no hover label gets
 * that name as its hover label. Stylus and mouse users see it; nothing else changes.
 *
 * <p>Runs over the editor's whole window after layouts (throttled), so drawers and sheets built
 * later are covered too. Android 8+ only: before that there are no tooltips, and the compat shim
 * would take over the view's long-press, which Studio uses for "hold" gestures.</p>
 *
 * <p>Views with no name at all are not guessed at: a glyph's ligature word ("tune", "check") is
 * not a name. Those still need a real label, one by one (STU-D1).</p>
 */
public final class HoverLabels {

    private static final long MIN_GAP_MS = 400;

    private HoverLabels() {}

    /** Keep {@code root}'s tree labelled from now on (call once, after setContentView). */
    public static void watch(@NonNull View root) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        final long[] last = {0};
        final boolean[] queued = {false};
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (queued[0]) return;
                long wait = Math.max(0, MIN_GAP_MS - (SystemClock.uptimeMillis() - last[0]));
                queued[0] = true;
                root.postDelayed(() -> {
                    queued[0] = false;
                    last[0] = SystemClock.uptimeMillis();
                    fill(root.getRootView());
                }, wait);
            }
        });
    }

    /** Label every named, tappable view under {@code v} that has no hover label yet. */
    @RequiresApi(Build.VERSION_CODES.O)
    public static void fill(@NonNull View v) {
        if ((v.isClickable() || v.isLongClickable())
                && TextUtils.isEmpty(v.getTooltipText())
                && !TextUtils.isEmpty(v.getContentDescription())) {
            v.setTooltipText(v.getContentDescription());
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) fill(g.getChildAt(i));
        }
    }
}
