package com.fadcam.ui.size;

import android.view.View;
import android.widget.ScrollView;

import androidx.annotation.NonNull;

/**
 * Keeps a page's last line clear of a bar drawn over the bottom of it (TAB-1, 2026-11-14).
 *
 * <p>The intro's page dots and arrows float over the bottom of each page. A page that is centred
 * with only a small bottom padding put its main button under them on a short window (the Fold
 * opened sideways, a phone on its side). This measures how much of the page the bar really
 * covers, on every layout, and raises the block's bottom padding only when the last line would
 * be hidden ({@link SizeClass#clearBottomPadding}). A page that already clears it, which is every
 * page on an upright phone, keeps its padding exactly as drawn.
 */
public final class BarClearance {

    private BarClearance() {}

    /**
     * @param scroll the page's scrolling root
     * @param block  the centred block inside it whose bottom padding may grow
     * @param barId  id of the overlaid bar, looked up in the window each layout (it belongs to the
     *               activity, so it may not exist yet when the page is made)
     */
    public static void keep(@NonNull ScrollView scroll, @NonNull View block, int barId) {
        final int asDrawn = block.getPaddingBottom();
        final int margin = Math.round(8 * scroll.getResources().getDisplayMetrics().density);
        View.OnLayoutChangeListener fit = (v, l, t, r, b, ol, ot, or, ob) -> {
            View bar = scroll.getRootView().findViewById(barId);
            int covered = 0;
            if (bar != null && bar.isShown() && bar.getHeight() > 0 && scroll.getHeight() > 0) {
                int[] s = new int[2], o = new int[2];
                scroll.getLocationInWindow(s);
                bar.getLocationInWindow(o);
                covered = Math.max(0, Math.min(bar.getHeight(), s[1] + scroll.getHeight() - o[1]));
            }
            int gap = covered > 0 ? covered + margin : 0;
            int content = block.getHeight() - block.getPaddingTop() - block.getPaddingBottom();
            if (content <= 0) return;
            int want = SizeClass.clearBottomPadding(block.getPaddingTop(), content, asDrawn,
                    scroll.getHeight(), gap);
            if (want != block.getPaddingBottom()) {
                block.setPadding(block.getPaddingLeft(), block.getPaddingTop(),
                        block.getPaddingRight(), want);
            }
        };
        scroll.addOnLayoutChangeListener(fit);
        block.addOnLayoutChangeListener(fit);
    }
}
