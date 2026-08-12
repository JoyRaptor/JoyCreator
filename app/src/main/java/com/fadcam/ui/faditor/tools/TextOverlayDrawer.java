package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The text-overlay drawer — a TOP-anchored panel that drops DOWN from the top edge and pushes
 * the video below it, using the same mechanics as {@link PipOverlayDrawer} (spec, 2026-08-10:
 * "take the drawer that's on the bottom and make it a top down drawer ... attach what we
 * already have to the video overlay drawer mechanics; as it drops down it will push the video
 * down like what video overlay drawer does").
 *
 * <p>Why top now, where the class used to sit on the bottom. A bottom drawer covered the
 * timeline, which keyframing needs to see ("the fact that we have keyframes means we will
 * probably also want to see the timeline"). A top drawer hangs from the top and pushes the
 * preview down, so the timeline stays reachable for the whole session — the same trade the
 * PiP drawer already made. The {@code HeightListener} reports the drawer's height and the
 * activity reflows the preview under it, exactly as the PiP drawer does.</p>
 *
 * <p>Generic on purpose, the same way {@code PipOverlayDrawer} is: this class knows about
 * sliding a panel down from the top, a title, a close button and a scrolling body capped at a
 * height. It does not know about fonts, colours or keyframes — the caller supplies the content
 * view, same as {@code PipDrawerTabs} supplies the PiP drawer's.</p>
 */
public final class TextOverlayDrawer extends LinearLayout {

    /**
     * The SAME scrim {@code PipOverlayDrawer} uses, not an opaque panel.
     *
     * <p>This drawer sits over the preview, and every control in it — colours, outline, glow,
     * shadow, background — changes what the preview shows. Behind an opaque panel the user is
     * editing something they cannot see and has to close the drawer to judge each change. JoyRaptor
     * asked for it to match the image/video drawer for exactly that reason (2026-08-12), and the
     * PiP drawer had already made the trade. The text on top carries a shadow layer, which is what
     * keeps it legible over an arbitrary frame.</p>
     */
    private static final int BG = 0x66000000;
    private static final int TXT = 0xFFEEEEEE;
    private static final int TXT_DIM = 0xFF9A9A9A;
    private static final int SLIDE_MS = 220;
    private static final float MAX_HEIGHT_FRACTION = 0.62f;
    private static final int MIN_BODY_DP = 120;
    private static final float ABSOLUTE_MAX_FRACTION = 0.85f;

    private final float density;
    private final TextView titleView;
    private final FrameLayout contentHost;
    @Nullable private ScrollView bodyScroll;
    private int userHeightPx = -1;
    @Nullable private Runnable onClose;
    private boolean hiding;

    public interface HeightListener { void onDrawerHeightChanged(int heightPx); }
    @Nullable private HeightListener heightListener;
    private int reportedHeightPx = Integer.MIN_VALUE;
    private boolean animating;

    public TextOverlayDrawer(@NonNull Context ctx) {
        super(ctx);
        density = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        // Rounded BOTTOM corners only — this one hangs DOWN from the top edge, so the curve
        // is at the bottom, mirroring PipOverlayDrawer.
        float r = 16f * density;
        bg.setCornerRadii(new float[]{0f, 0f, 0f, 0f, r, r, r, r});
        setBackground(bg);
        setClickable(true);
        setElevation(8f * density);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(2), dp(6), dp(6));

        titleView = new TextView(ctx);
        titleView.setTextColor(TXT);
        titleView.setTextSize(14);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        titleView.setAllCaps(true);
        titleView.setLetterSpacing(0.05f);
        // The panel is a scrim now, so anything on it has arbitrary picture behind it. Same shadow
        // the PiP drawer's title carries, for the same reason.
        titleView.setShadowLayer(4f * density, 0f, 1f, 0xCC000000);
        header.addView(titleView, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextColor(TXT_DIM);
        close.setTextSize(17);
        close.setPadding(dp(12), dp(4), dp(10), dp(4));
        close.setShadowLayer(4f * density, 0f, 1f, 0xCC000000);
        close.setOnClickListener(v -> hide());
        header.addView(close);
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        contentHost = new FrameLayout(ctx);
        addView(contentHost, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Pull-UP dismiss grip at the BOTTOM edge — the drawer hangs from the top, so the
        // direction that puts it away is up. Mirrors PipOverlayDrawer.wireGrip.
        LinearLayout grip = new LinearLayout(ctx);
        grip.setGravity(Gravity.CENTER);
        grip.setPadding(0, dp(4), 0, dp(8));
        View pill = new View(ctx);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0x88FFFFFF);
        pillBg.setCornerRadius(3f * density);
        pill.setBackground(pillBg);
        grip.addView(pill, new LayoutParams(dp(38), dp(4)));
        wireGrip(grip);
        addView(grip, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    public void setTitle(@NonNull String title) { titleView.setText(title); }

    public void setOnClose(@Nullable Runnable r) { this.onClose = r; }

    public void setHeightListener(@Nullable HeightListener l) { this.heightListener = l; }

    /** Show with fresh content, replacing whatever is currently displayed. */
    public void show(@NonNull View content) {
        if (onClose != null) { Runnable r = onClose; onClose = null; r.run(); }
        animate().cancel();
        setTranslationY(0f);
        setAlpha(1f);
        hiding = false;
        userHeightPx = -1;
        contentHost.removeAllViews();
        contentHost.addView(wrap(content));
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
            // Slides DOWN from above the top edge (the mirror of the old bottom drawer,
            // which slid up from below).
            setTranslationY(-dp(160));
            setAlpha(0f);
            animate().translationY(0f).alpha(1f)
                    .setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator()).start();
        }
        post(this::reportHeight);
    }

    public boolean isShowing() { return getVisibility() == VISIBLE; }

    public void hide() {
        if (getVisibility() != VISIBLE) return;
        hiding = true;
        reportedHeightPx = 0;
        if (heightListener != null) heightListener.onDrawerHeightChanged(0);
        animate().translationY(-dp(160)).alpha(0f).setDuration(SLIDE_MS)
                .withEndAction(() -> {
                    setVisibility(GONE);
                    contentHost.removeAllViews();
                    if (onClose != null) { Runnable r = onClose; onClose = null; r.run(); }
                }).start();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        reportHeight();
    }

    private void reportHeight() {
        if (heightListener == null || animating) return;
        int h = (getVisibility() == VISIBLE && !hiding) ? getHeight() : 0;
        if (h == reportedHeightPx) return;
        reportedHeightPx = h;
        heightListener.onDrawerHeightChanged(h);
    }

    /**
     * Rebuild the content in place (a style row changed the layout, a chip toggled) without
     * re-running the slide-in animation.
     */
    public void refreshContent(@NonNull View content) {
        contentHost.removeAllViews();
        contentHost.addView(wrap(content));
        post(this::reportHeight);
    }

    @NonNull
    private View wrap(@NonNull View content) {
        ScrollView sv = new ScrollView(getContext()) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int cap = maxBodyHeightPx();
                int mode = userHeightPx > 0 ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST;
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, mode));
            }
        };
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        sv.setMinimumHeight(0);
        bodyScroll = sv;
        return sv;
    }

    private int maxBodyHeightPx() {
        int screen = getResources().getDisplayMetrics().heightPixels;
        if (userHeightPx > 0) {
            return Math.max(dp(MIN_BODY_DP), Math.min(userHeightPx,
                    Math.round(screen * ABSOLUTE_MAX_FRACTION)));
        }
        return Math.round(screen * MAX_HEIGHT_FRACTION);
    }

    @SuppressWarnings("ClickableViewAccessibility")
    private void wireGrip(@NonNull View grip) {
        final float slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
        grip.setOnTouchListener(new OnTouchListener() {
            float downY;
            int startHeight;
            boolean resizing;
            boolean moved;

            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downY = e.getRawY();
                        moved = false;
                        resizing = false;
                        startHeight = bodyScroll != null && bodyScroll.getHeight() > 0
                                ? bodyScroll.getHeight() : maxBodyHeightPx();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float dy = e.getRawY() - downY;
                        if (!moved && Math.abs(dy) > slop) { moved = true; resizing = true; }
                        if (resizing && bodyScroll != null) {
                            // Dragging UP (negative dy) shrinks the body — the mirror of the
                            // old bottom drawer's grip, where dragging DOWN shrunk it — because
                            // this drawer hangs from the top.
                            int screen = getResources().getDisplayMetrics().heightPixels;
                            userHeightPx = Math.max(dp(MIN_BODY_DP),
                                    Math.min(Math.round(startHeight + dy),
                                            Math.round(screen * ABSOLUTE_MAX_FRACTION)));
                            bodyScroll.requestLayout();
                            reportHeight();
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP: {
                        float dy = e.getRawY() - downY;
                        boolean atFloor = bodyScroll != null
                                && bodyScroll.getHeight() <= dp(MIN_BODY_DP) + 1;
                        if (!moved || (dy < -slop * 2 && atFloor)) {
                            userHeightPx = -1;
                            hide();
                        } else {
                            v.performHapticFeedback(
                                    android.view.HapticFeedbackConstants.CLOCK_TICK);
                            post(TextOverlayDrawer.this::reportHeight);
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });
    }

    private int dp(int v) { return Math.round(v * density); }
}
