package com.fadcam.ui.faditor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.keyframe.Easing;

/**
 * D2: the program-wide {@code ‹ ♦ ›} keyframe control — one reusable widget for
 * every place a property can animate (drawer rows now; caption / master-clip /
 * PiP / audio props as they come online).
 *
 * <ul>
 *   <li>Flanking <b>chevrons</b> (‹ ›) = jump the playhead to the prev / next key
 *       of this property (G3's swipe-nav made into a visible affordance).</li>
 *   <li>The <b>diamond</b> is a small custom-drawn view: a hollow outline when the
 *       playhead is NOT on a key of this property, a solid accent-green diamond
 *       with a carved-out {@code ×} when it IS — the {@code ×} signals "tap
 *       removes THIS key".</li>
 *   <li>Diamond <b>tap</b>: on-key → delete that key, off-key → drop one (this
 *       replaces the old G3 long-press-delete).</li>
 *   <li>Diamond <b>long-press</b> (with haptic) → ease-curve picker popover
 *       (D2a), anchored at the diamond.</li>
 *   <li>Diamond <b>horizontal swipe</b> = prev/next key (bonus gesture ported
 *       from the old {@code wireDiamondGestures}; the chevrons are the visible
 *       path).</li>
 * </ul>
 *
 * <p>A touch on any part first announces focus (host callback) so the sheet can
 * promote this property to the peek row / drive the top ribbon.</p>
 */
public final class KeyframeDiamondControl extends LinearLayout {

    /** Host wiring: playhead source + focus / post-action refresh callbacks. */
    public interface Host {
        long playheadMs();
        /** A touch landed on this control — focus its property. */
        void onFocus();
        /** A key was dropped / deleted / jumped / re-eased — refresh the rows. */
        void onAction();
    }

    private static final int DIM = 0xFF888888;
    private static final int ACCENT = 0xFF4CAF50;
    private static final int SHEET_BG = 0xFF1C1C1E; // the × is carved in the sheet bg color

    private final float density;
    private final DiamondView diamond;
    @Nullable private ObjectMenuSheet.Prop prop;
    @Nullable private Host host;
    private boolean onKey;

    public KeyframeDiamondControl(@NonNull Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        addView(chevron("‹", true));
        diamond = new DiamondView(ctx);
        LayoutParams dlp = new LayoutParams(dp(20), dp(20));
        dlp.leftMargin = dp(2);
        dlp.rightMargin = dp(2);
        diamond.setLayoutParams(dlp);
        wireDiamond(diamond);
        addView(diamond);
        addView(chevron("›", false));
    }

    /** Adopt a fresh {@link ObjectMenuSheet.Prop} (rebuilt every show()). */
    public void bind(@NonNull ObjectMenuSheet.Prop prop, @NonNull Host host) {
        this.prop = prop;
        this.host = host;
    }

    /** Re-read the on-key state for {@code playheadMs} and repaint the diamond. */
    public void refresh(long playheadMs) {
        onKey = prop != null && prop.onKeyAt(playheadMs);
        diamond.setOnKey(onKey);
    }

    /** C7: a quick scale pop of the diamond — the "you're NOT keyframing" nudge. */
    public void pulse() {
        diamond.animate().scaleX(1.35f).scaleY(1.35f).setDuration(120)
                .withEndAction(() -> diamond.animate()
                        .scaleX(1f).scaleY(1f).setDuration(140).start())
                .start();
    }

    // ── internals ────────────────────────────────────────────────────

    @NonNull
    private TextView chevron(@NonNull String glyph, boolean prev) {
        TextView v = new TextView(getContext());
        v.setText(glyph);
        v.setTextColor(DIM);
        v.setTextSize(18);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(8), dp(4), dp(8), dp(4));
        android.util.TypedValue tv = new android.util.TypedValue();
        getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true);
        v.setBackgroundResource(tv.resourceId);
        v.setOnClickListener(view -> {
            if (host != null) host.onFocus();
            if (prop == null) return;
            if (prev) prop.prevKey(); else prop.nextKey();
            if (host != null) host.onAction();
        });
        return v;
    }

    /**
     * The diamond owns its small hit area (contract §6 zone discipline): tap =
     * add/remove the key under the playhead, horizontal swipe = jump prev/next,
     * long-press = ease picker. The gesture never leaves the diamond, so it can't
     * be confused with a scrub or a row-scroll.
     */
    @SuppressLint("ClickableViewAccessibility")
    private void wireDiamond(@NonNull View d) {
        final float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        final long lpTimeout = ViewConfiguration.getLongPressTimeout();
        d.setOnTouchListener(new OnTouchListener() {
            float downX, downY;
            boolean moved, longPressed;
            Runnable pendingLp;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        moved = false;
                        longPressed = false;
                        if (host != null) host.onFocus(); // touching focuses the prop
                        pendingLp = () -> {
                            longPressed = true;
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            openEasePicker(v);
                        };
                        v.postDelayed(pendingLp, lpTimeout);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!moved && (Math.abs(e.getRawX() - downX) > slop
                                || Math.abs(e.getRawY() - downY) > slop)) {
                            moved = true;
                            if (pendingLp != null) v.removeCallbacks(pendingLp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP: {
                        if (pendingLp != null) v.removeCallbacks(pendingLp);
                        if (longPressed) return true;       // picker already opened
                        if (prop == null) return true;
                        float dx = e.getRawX() - downX;
                        if (moved && Math.abs(dx) > slop * 2
                                && Math.abs(dx) > Math.abs(e.getRawY() - downY)) {
                            if (dx > 0) prop.nextKey(); else prop.prevKey();
                        } else if (!moved) {
                            long ph = host != null ? host.playheadMs() : 0L;
                            if (prop.onKeyAt(ph)) prop.deleteKey(); // × = remove THIS key
                            else prop.dropKey();
                        }
                        if (host != null) host.onAction();
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        if (pendingLp != null) v.removeCallbacks(pendingLp);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void openEasePicker(@NonNull View anchor) {
        if (prop == null) return;
        long ph = host != null ? host.playheadMs() : 0L;
        Easing current = prop.segmentEasing(ph);
        // Null segment = empty track / playhead before the first key: nothing to
        // ease. The popover shows a disabled hint instead of editing (D2a).
        EasePickerPopover.show(anchor, current != null, current, e -> {
            long now = host != null ? host.playheadMs() : ph;
            prop.setSegmentEasing(e, now);
            if (host != null) host.onAction();
        });
    }

    private int dp(int v) { return (int) (v * density + 0.5f); }

    /** The diamond glyph itself, drawn from a {@link Canvas} so on-key state can
     *  render the carved {@code ×} the font glyphs can't. */
    private final class DiamondView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean drawOnKey;

        DiamondView(@NonNull Context ctx) { super(ctx); }

        void setOnKey(boolean on) {
            if (on == drawOnKey) return;
            drawOnKey = on;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas c) {
            float stroke = 1.6f * density;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) / 2f - stroke - 0.5f * density;
            path.reset();
            path.moveTo(cx, cy - r);
            path.lineTo(cx + r, cy);
            path.lineTo(cx, cy + r);
            path.lineTo(cx - r, cy);
            path.close();
            if (drawOnKey) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ACCENT);
                c.drawPath(path, paint);
                // Carve a small × in the sheet background color (the "remove" mark).
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.8f * density);
                paint.setColor(SHEET_BG);
                float d = r * 0.42f;
                c.drawLine(cx - d, cy - d, cx + d, cy + d, paint);
                c.drawLine(cx - d, cy + d, cx + d, cy - d, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setColor(DIM);
                c.drawPath(path, paint);
            }
        }
    }
}
