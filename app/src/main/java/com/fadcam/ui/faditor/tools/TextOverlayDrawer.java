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
 * The text-overlay drawer — a BOTTOM-anchored, OPAQUE panel that replaces the old modal
 * "Edit text" dialog (SPEC_TEXT_DRAWER, JoyRaptor 2026-08-07/08).
 *
 * <p><b>Why bottom, unlike {@link PipOverlayDrawer}.</b> That one moved to the TOP specifically
 * so the timeline stays reachable while keyframing a PiP. Text works the opposite way round:
 * "you are selecting your text in the preview window and formatting it... you won't need a
 * preview in the drawer because you will be seeing it live in the preview window anyway" — so
 * the thing that must stay clear here is the PREVIEW, not the timeline, and covering the
 * timeline "doesn't count" (owner's words). A bottom drawer is exactly that: it rises from
 * underneath and leaves the video untouched above it.</p>
 *
 * <p><b>Why opaque, unlike the PiP drawer.</b> The PiP drawer is translucent because the whole
 * point of adjusting a PiP is watching the PiP through the panel. A text drawer covers the
 * timeline, which the user does not need to see while typing and styling — so there is nothing
 * gained by seeing through it, and an opaque panel reads its own rows more legibly.</p>
 *
 * <p>Generic on purpose, the same way {@code PipOverlayDrawer} is: this class knows about
 * sliding a panel up from the bottom, a title, a close button and a scrolling body capped at a
 * height. It does not know about fonts, colours or keyframes — the caller supplies the content
 * view, same as {@code PipDrawerTabs} supplies the PiP drawer's.</p>
 */
public final class TextOverlayDrawer extends LinearLayout {

    private static final int BG = 0xFF1C1C1E;   // opaque — see class doc
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
        // Rounded TOP corners only — the mirror image of the PiP drawer's bottom rounding,
        // because this one comes UP from the bottom edge rather than down from the top.
        float r = 16f * density;
        bg.setCornerRadii(new float[]{r, r, r, r, 0f, 0f, 0f, 0f});
        setBackground(bg);
        setClickable(true);
        setElevation(8f * density);

        // Pull-DOWN dismiss grip at the TOP edge — the drawer hangs from the bottom, so the
        // direction that puts it away is down. Mirrors PipOverlayDrawer.wireGrip.
        LinearLayout grip = new LinearLayout(ctx);
        grip.setGravity(Gravity.CENTER);
        grip.setPadding(0, dp(8), 0, dp(4));
        View pill = new View(ctx);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0x33FFFFFF);
        pillBg.setCornerRadius(3f * density);
        pill.setBackground(pillBg);
        grip.addView(pill, new LayoutParams(dp(38), dp(4)));
        wireGrip(grip);
        addView(grip, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

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
        header.addView(titleView, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextColor(TXT_DIM);
        close.setTextSize(17);
        close.setPadding(dp(12), dp(4), dp(10), dp(4));
        close.setOnClickListener(v -> hide());
        header.addView(close);
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        contentHost = new FrameLayout(ctx);
        addView(contentHost, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
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
            setTranslationY(dp(160));
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
        animate().translationY(dp(160)).alpha(0f).setDuration(SLIDE_MS)
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
                            // Dragging DOWN (positive dy) shrinks the body — the mirror of the
                            // PiP drawer's grip, where dragging UP shrinks it — because this
                            // drawer hangs from the bottom.
                            int screen = getResources().getDisplayMetrics().heightPixels;
                            userHeightPx = Math.max(dp(MIN_BODY_DP),
                                    Math.min(Math.round(startHeight - dy),
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
                        if (!moved || (dy > slop * 2 && atFloor)) {
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
