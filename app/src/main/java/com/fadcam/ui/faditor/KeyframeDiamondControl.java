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

import com.fadcam.R;
import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeGlyph;

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

    private static final int DIM = 0xFF8A8A94;
    private static final int ACCENT = 0xFF35F6BF;
    private static final int SHEET_BG = 0xFF1F1F26; // the × is carved in the sheet bg color
    private static final int REFUSED = 0xFFFF4438;   // "nowhere to put a key here"

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
        Easing easing = prop != null ? prop.segmentEasing(playheadMs) : null;
        if (easing == null) easing = Easing.LINEAR;
        diamond.setOnKey(onKey);
        diamond.setEasing(easing);
    }

    /** C7: a quick scale pop of the diamond — the "you're NOT keyframing" nudge. */
    public void pulse() {
        diamond.animate().scaleX(1.35f).scaleY(1.35f).setDuration(120)
                .withEndAction(() -> diamond.animate()
                        .scaleX(1f).scaleY(1f).setDuration(140).start())
                .start();
    }

    /**
     * "There is nowhere to put a key here" — the playhead is off the object's span.
     *
     * <p>Three channels on purpose, because the failure this replaces was a SILENT one and a
     * silent refusal reads as a broken control: the diamond flashes red and shakes (visible
     * without reading), a haptic tick (felt with a finger already on the glass), and one toast
     * naming the reason and the cure. The toast is short and re-shown per tap rather than
     * queued, so a user jabbing the diamond does not build a five-second backlog of toasts.</p>
     */
    private void refuse(@NonNull View v) {
        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        diamond.flashRefused();
        float dx = 5f * density;
        diamond.animate().translationX(-dx).setDuration(50)
                .withEndAction(() -> diamond.animate().translationX(dx).setDuration(70)
                        .withEndAction(() -> diamond.animate().translationX(0f)
                                .setDuration(60).start()).start()).start();
        if (refusalToast != null) refusalToast.cancel();
        refusalToast = android.widget.Toast.makeText(getContext(),
                R.string.faditor_kf_outside_span, android.widget.Toast.LENGTH_SHORT);
        refusalToast.show();
    }

    @Nullable private android.widget.Toast refusalToast;

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
        // Both chevrons are swipe-sensitive like the diamond (user, 2026-08-10): a quick
        // horizontal swipe-left = prev, swipe-right = next, on EITHER chevron. Taps keep
        // their positional meaning (‹ = prev, › = next).
        final float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        v.setOnTouchListener(new OnTouchListener() {
            float downX, downY;
            boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!moved && (Math.abs(e.getRawX() - downX) > slop
                                || Math.abs(e.getRawY() - downY) > slop)) {
                            moved = true;
                        }
                        return true;
                    case MotionEvent.ACTION_UP: {
                        if (host != null) host.onFocus();
                        if (prop == null) return true;
                        float dx = e.getRawX() - downX;
                        boolean horizontal = Math.abs(dx) > Math.abs(e.getRawY() - downY);
                        if (moved && horizontal && Math.abs(dx) > slop * 2) {
                            if (dx > 0) prop.nextKey(); else prop.prevKey();
                        } else if (!moved) {
                            if (prev) prop.prevKey(); else prop.nextKey();
                        }
                        if (host != null) host.onAction();
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
        return v;
    }

    /**
     * The diamond owns its small hit area (contract §6 zone discipline): tap =
     * add/remove the key under the playhead, horizontal swipe = jump prev/next,
     * long-press = ease picker. The gesture never leaves the diamond, so it can't
     * be confused with a scrub or a row-scroll.
     * <p>
     * Added per SPEC 20260829 §4.3: long-press-and-drag-UP cycles to next family's
     * default (LINEAR→HOLD→EASE_OUT→EASE_IN_OUT→SPRING→LINEAR). Chosen over two-finger tap
     * because the control is 20dp and single-finger; two-finger requires lifting one hand
     * and collides with system zoom. Horizontal swipe + tap + long-press already taken
     * (header comment), vertical drag is the only free axis — up specifically to avoid
     * conflicting with drawer drag-down to dismiss.
     * </p>
     */
    @SuppressLint("ClickableViewAccessibility")
    private void wireDiamond(@NonNull View d) {
        final float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        final long lpTimeout = ViewConfiguration.getLongPressTimeout();
        d.setOnTouchListener(new OnTouchListener() {
            float downX, downY;
            boolean moved, longPressed, dragUpCycled;
            float maxUpDy;
            Runnable pendingLp;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        moved = false;
                        longPressed = false;
                        dragUpCycled = false;
                        maxUpDy = 0f;
                        if (host != null) host.onFocus(); // touching focuses the prop
                        pendingLp = () -> {
                            longPressed = true;
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            // Don't open picker immediately — allow drag-up to intercept.
                            // We mark longPressed and wait for UP to decide picker vs cycle.
                        };
                        v.postDelayed(pendingLp, lpTimeout);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = e.getRawY() - downY;
                        float dx = e.getRawX() - downX;
                        if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                            moved = true;
                            // Don't cancel pendingLp yet if still within vertical drag-up window;
                            // let long-press arm.
                            if (!longPressed && Math.abs(dx) > slop * 1.5f && Math.abs(dx) > Math.abs(dy)) {
                                if (pendingLp != null) v.removeCallbacks(pendingLp);
                            }
                        }
                        if (longPressed) {
                            float up = downY - e.getRawY(); // positive when dragging up
                            if (up > maxUpDy) maxUpDy = up;
                            if (!dragUpCycled && up > slop * 2.5f) {
                                // Threshold crossed: cycle immediately, suppress picker
                                dragUpCycled = true;
                                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                                cycleFamily();
                                if (pendingLp != null) v.removeCallbacks(pendingLp);
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP: {
                        if (pendingLp != null) v.removeCallbacks(pendingLp);
                        if (dragUpCycled) {
                            // Already cycled — don't open picker, don't do tap/nav
                            if (host != null) host.onAction();
                            return true;
                        }
                        if (longPressed) {
                            // Stationary long-press → picker (original D2a)
                            openEasePicker(v);
                            return true;
                        }
                        if (prop == null) return true;
                        float dx = e.getRawX() - downX;
                        if (moved && Math.abs(dx) > slop * 2
                                && Math.abs(dx) > Math.abs(e.getRawY() - downY)) {
                            if (dx > 0) prop.nextKey(); else prop.prevKey();
                        } else if (!moved) {
                            long ph = host != null ? host.playheadMs() : 0L;
                            if (!prop.keyableAt(ph)) {
                                refuse(v);
                                return true;
                            }
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

            private void cycleFamily() {
                if (prop == null) return;
                long ph = host != null ? host.playheadMs() : 0L;
                Easing cur = prop.segmentEasing(ph);
                if (cur == null) cur = Easing.LINEAR;
                KeyframeGlyph.Family fam = KeyframeGlyph.familyOf(cur);
                Easing next;
                // LINEAR → HOLD → RAMP(EASE_OUT) → SMOOTH(EASE_IN_OUT) → EXOTIC(SPRING) → LINEAR
                switch (fam) {
                    case LINEAR: next = Easing.HOLD; break;
                    case HOLD: next = Easing.EASE_OUT; break;
                    case RAMP: next = Easing.EASE_IN_OUT; break;
                    case SMOOTH: next = Easing.SPRING; break;
                    case EXOTIC:
                    default: next = Easing.LINEAR; break;
                }
                prop.setSegmentEasing(next, ph);
                // Refresh happens via host.onAction() caller; also update local diamond immediately
                diamond.setEasing(next);
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

    /** The glyph itself — was a raw diamond, now a family shape drawn via {@link KeyframeGlyph}.
     *  Hollow = playhead not on key, solid = on key with carved ×. Curve detail comes from
     *  {@link KeyframeGlyph#curveFor} so solid can show curve in contrasting colour. */
    private final class DiamondView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path silhouettePath = new Path();
        private final Path curvePath = new Path();
        private boolean drawOnKey;
        private boolean refused;
        @NonNull private Easing easing = Easing.LINEAR;

        DiamondView(@NonNull Context ctx) { super(ctx); }

        void setOnKey(boolean on) {
            if (on == drawOnKey) return;
            drawOnKey = on;
            invalidate();
        }

        void setEasing(@NonNull Easing e) {
            if (e == easing) return;
            easing = e;
            invalidate();
        }

        void flashRefused() {
            refused = true;
            invalidate();
            postDelayed(() -> { refused = false; invalidate(); }, 420);
        }

        @Override
        protected void onDraw(@NonNull Canvas c) {
            float stroke = 1.6f * density;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) / 2f - stroke - 0.5f * density;
            if (r < 1f) return;
            KeyframeGlyph.silhouetteFor(easing, cx, cy, r, silhouettePath);
            KeyframeGlyph.curveFor(easing, cx, cy, r, curvePath);
            boolean hasCurve = !curvePath.isEmpty();
            if (drawOnKey) {
                // Solid fill — silhouette filled accent, curve in contrasting sheet bg so it reads
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ACCENT);
                c.drawPath(silhouettePath, paint);
                if (hasCurve) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1.2f * density);
                    paint.setColor(SHEET_BG);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setStrokeJoin(Paint.Join.ROUND);
                    c.drawPath(curvePath, paint);
                    // Restore for ×
                    paint.setStrokeCap(Paint.Cap.BUTT);
                    paint.setStrokeJoin(Paint.Join.MITER);
                }
                // Carved ×
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.8f * density);
                paint.setColor(SHEET_BG);
                float d = r * 0.42f;
                c.drawLine(cx - d, cy - d, cx + d, cy + d, paint);
                c.drawLine(cx - d, cy + d, cx + d, cy - d, paint);
            } else {
                // Hollow — stroke silhouette + curve together in DIM/REFUSED
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(refused ? stroke * 1.6f : stroke);
                paint.setColor(refused ? REFUSED : DIM);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                c.drawPath(silhouettePath, paint);
                if (hasCurve) {
                    // Curve slightly thinner so silhouette remains dominant at 20dp
                    float curveStroke = Math.max(1f, stroke * 0.75f);
                    paint.setStrokeWidth(curveStroke);
                    c.drawPath(curvePath, paint);
                }
                paint.setStrokeCap(Paint.Cap.BUTT);
                paint.setStrokeJoin(Paint.Join.MITER);
            }
        }
    }
}
