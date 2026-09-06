package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A {@link ScrollView} that refuses to grow past a fraction of the screen height.
 *
 * <p>Same lesson {@link ObjectDrawer#wrap} learned the hard way: a drawer body measured
 * {@code WRAP_CONTENT} grows to whatever its content wants, and a panel that rebuilds its
 * children later (a layer list, an FX stack) outruns any cap applied once in a {@code post()}.
 * Clamping in {@code onMeasure} cannot be outrun. The visualizer drawer had no cap at all, so
 * a tall Rolodex pushed the drawer's own bottom grab handle down behind the timeline lanes —
 * the handle that closes it — and the only way out was to open a different drawer on top of it.
 * With the cap the content scrolls inside a fixed band and every piece of the drawer's own
 * chrome stays on screen at any expansion state.</p>
 */
public class MaxHeightScrollView extends ScrollView {

    /** Matches ObjectDrawer.MAX_HEIGHT_FRACTION — one house rule for "how tall may a drawer be". */
    private float maxHeightFraction = 0.55f;

    public MaxHeightScrollView(@NonNull Context ctx) {
        super(ctx);
    }

    public MaxHeightScrollView(@NonNull Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
    }

    public MaxHeightScrollView(@NonNull Context ctx, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(ctx, attrs, defStyleAttr);
    }

    /** Fraction of the screen height this view may occupy (0..1). */
    public void setMaxHeightFraction(float f) {
        if (f > 0f && f <= 1f) {
            maxHeightFraction = f;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int screen = getResources().getDisplayMetrics().heightPixels;
        int cap = Math.round(screen * maxHeightFraction);
        // AT_MOST, not EXACTLY: short content (a two-row panel) still measures to itself and the
        // drawer stays compact; only content that WANTS more than the cap gets clipped to it and
        // scrolls.
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
    }
}
