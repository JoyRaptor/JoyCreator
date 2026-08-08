package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The video-overlay (PiP) drawer — a TOP-anchored, translucent, tabbed panel.
 *
 * <p><b>Why it comes down from the top.</b> The old object sheet rose from the bottom and sat
 * exactly where the timeline is, so you could not scrub while adjusting a value — and scrubbing
 * is precisely what you need when you are setting keyframes. Dropping it from the top leaves the
 * whole timeline reachable (user, 2026-08-05).</p>
 *
 * <p><b>Why it is see-through.</b> Black at 40% opacity: the drawer covers the top of the
 * preview, and the point of adjusting a PiP is watching the PiP. A solid panel would hide the
 * thing being edited. Text and controls carry their own contrast so they stay legible over
 * arbitrary video.</p>
 *
 * <p><b>Tabs are spatial, not stacked.</b> Video · Mask · Chroma key · Blend sit in a fixed
 * left-to-right order and the content slides in the direction you travelled — forward pushes the
 * old panel out to the LEFT and brings the new in from the RIGHT, back does the reverse. The
 * title travels with it. That is what makes them read as places rather than as a menu that
 * replaced itself, and it is why the order is fixed even though only one is ever visible.</p>
 *
 * <p><b>Deliberately NOT a horizontal carousel of the whole drawer.</b> Considered and rejected
 * with the user: nearly every row is itself a horizontal slider, so a horizontal page-swipe
 * would fight every control on the page for the same gesture.</p>
 *
 * <p>Generic on purpose — it knows about {@link Tab}s and an icon row, not about compositing.
 * The activity supplies each tab's content, so mask/key/blend logic stays in its own class and
 * this one stays chrome. Scoped to PiP for now (user's call): the shared
 * {@code ObjectMenuSheet} still serves text, sprites, audio and visualizers, and porting them
 * is mechanical once this is proven.</p>
 */
public final class PipOverlayDrawer extends LinearLayout {

    /** Black at 40% — see-through enough to watch the PiP behind it. */
    private static final int SCRIM = 0x66000000;
    private static final int TXT = 0xFFEEEEEE;
    private static final int TXT_DIM = 0xFF9A9A9A;
    private static final int ACCENT = 0xFF4CAF50;      // active tab / engaged toggle
    private static final int DANGER = 0xFFE57373;
    private static final int SLIDE_MS = 240;
    /** Cap so the drawer can never swallow the preview; content scrolls inside. */
    private static final float MAX_HEIGHT_FRACTION = 0.55f;

    /** One tab: an icon in the header, a title, and lazily-built content. */
    public static final class Tab {
        final String title;
        final int iconRes;
        final ContentBuilder content;

        public Tab(@NonNull String title, int iconRes, @NonNull ContentBuilder content) {
            this.title = title;
            this.iconRes = iconRes;
            this.content = content;
        }
    }

    public interface ContentBuilder { @NonNull View build(@NonNull Context ctx); }

    /** A header toggle that is an ACTION, not a tab (mute / hide / lock). */
    public static final class Toggle {
        final int iconOn, iconOff;
        final ToggleState state;
        final Runnable onTap;
        /** true = engaged state is a WARNING (muted, hidden), so it tints red not green. */
        final boolean dangerWhenOn;

        public Toggle(int iconOn, int iconOff, @NonNull ToggleState state,
                      @NonNull Runnable onTap, boolean dangerWhenOn) {
            this.iconOn = iconOn;
            this.iconOff = iconOff;
            this.state = state;
            this.onTap = onTap;
            this.dangerWhenOn = dangerWhenOn;
        }
    }

    public interface ToggleState { boolean isOn(); }

    private final float density;
    private final TextView titleView;
    private final LinearLayout iconRow;
    private final FrameLayout contentHost;
    private final List<Tab> tabs = new ArrayList<>();
    private final List<ImageView> tabIcons = new ArrayList<>();
    private final List<Toggle> toggles = new ArrayList<>();
    private final List<ImageView> toggleIcons = new ArrayList<>();

    private int activeTab = 0;
    private boolean animating;
    @Nullable private Runnable onClose;

    public PipOverlayDrawer(@NonNull Context ctx) {
        super(ctx);
        density = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        // Rounded BOTTOM corners only. A panel that cuts straight across reads as a hard
        // horizontal slice through the screen; curving the two bottom corners is what makes it
        // read as something that came DOWN from the top edge (user, 2026-08-05).
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SCRIM);
        float r = 18f * density;
        bg.setCornerRadii(new float[]{0f, 0f, 0f, 0f, r, r, r, r});
        setBackground(bg);
        // Consume touches so a tap on the drawer never reaches the preview underneath and
        // starts dragging the very PiP being edited.
        setClickable(true);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(8), dp(6), dp(6));

        titleView = new TextView(ctx);
        titleView.setTextColor(TXT);
        titleView.setTextSize(15);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        // Shadow rather than a solid bar: keeps the title readable on a bright frame without
        // giving up the transparency the drawer exists for.
        titleView.setShadowLayer(4f * density, 0f, 1f, 0xCC000000);
        header.addView(titleView, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        iconRow = new LinearLayout(ctx);
        iconRow.setOrientation(HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(iconRow, new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextColor(TXT_DIM);
        close.setTextSize(17);
        close.setPadding(dp(12), dp(4), dp(10), dp(4));
        close.setOnClickListener(v -> hide());
        header.addView(close);

        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        contentHost = new FrameLayout(ctx);
        contentHost.setClipChildren(true);
        addView(contentHost, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Pull-UP dismiss grip, at the BOTTOM edge — the drawer hangs from the top, so the
        // direction that puts it away is up. Same affordance the bottom sheet had, mirrored.
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

    /** Drag up (or tap) on the grip dismisses. Tap is kept because a 4dp pill is a small drag
     *  target, and the bottom sheet taught users the grip is tappable. */
    @SuppressWarnings("ClickableViewAccessibility")
    /**
     * The grip: DRAG TO RESIZE, drag up hard to dismiss, tap to dismiss.
     *
     * <p>It used to do only the last two — "drag up past twice the slop → hide, tap → hide" —
     * so a drawer that had grown too tall could be closed and reopened at exactly the same
     * height, and never made shorter or taller. Dragging DOWN grows the body, dragging UP
     * shrinks it, and only a decisive upward flick from an already-minimal drawer dismisses,
     * so resizing cannot close the thing you are trying to size.</p>
     */
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
                            userHeightPx = Math.round(startHeight + dy);
                            bodyScroll.requestLayout();
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP: {
                        float dy = e.getRawY() - downY;
                        // A tap dismisses. So does an upward drag that has already squeezed the
                        // body to its floor — at that point "smaller" can only mean "gone".
                        boolean atFloor = bodyScroll != null
                                && bodyScroll.getHeight() <= dp(MIN_BODY_DP) + 1;
                        if (!moved || (dy < -slop * 2 && atFloor)) {
                            userHeightPx = 0;
                            hide();
                        } else {
                            v.performHapticFeedback(
                                    android.view.HapticFeedbackConstants.CLOCK_TICK);
                            post(PipOverlayDrawer.this::reportHeight);
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });
    }

    public void setOnClose(@Nullable Runnable r) { this.onClose = r; }

    /**
     * Told the height this drawer occupies, so the host can move the picture out from under it.
     *
     * <p>The drawer is an overlay on the root {@code FrameLayout}: nothing reflows when it
     * appears, so on a 16:9 project it covers the top of the video while an identical band of
     * unused letterbox sits below. Reported rather than acted on, because only the host knows
     * how much slack the letterbox is offering and whether any picture has to be given up.</p>
     */
    public interface HeightListener { void onDrawerHeightChanged(int heightPx); }

    @Nullable private HeightListener heightListener;

    public void setHeightListener(@Nullable HeightListener l) { this.heightListener = l; }

    private void reportHeight() {
        if (heightListener == null) return;
        heightListener.onDrawerHeightChanged(getVisibility() == VISIBLE ? getHeight() : 0);
    }

    /**
     * Bind tabs + header toggles and show tab 0. Rebuilding is cheap and is how the caller
     * retargets the drawer at a different clip.
     */
    public void show(@NonNull List<Tab> tabList, @NonNull List<Toggle> toggleList) {
        tabs.clear();
        tabs.addAll(tabList);
        toggles.clear();
        toggles.addAll(toggleList);
        buildIconRow();
        activeTab = 0;
        contentHost.removeAllViews();
        contentHost.addView(wrap(tabs.get(0).content.build(getContext())));
        titleView.setText(tabs.get(0).title);
        refreshIcons();
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
            // Slide down from above its own height — the drawer arrives from off-screen top,
            // which is the direction it lives in.
            setTranslationY(-dp(120));
            setAlpha(0f);
            animate().translationY(0f).alpha(1f)
                    .setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator()).start();
        }
        // After layout: the height is not known until the content has measured, and the host
        // needs the real number to decide how far to move the picture.
        post(this::reportHeight);
    }

    public boolean isShowing() { return getVisibility() == VISIBLE; }

    /**
     * Switch to the tab with this title, if it exists.
     *
     * <p>By TITLE rather than index because the caller — the Adjust tool, which wants "Effects"
     * whatever object it is looking at — must not have to know each object's tab order. Tab
     * lists differ per object type, and an index would silently open the Mask tab on the day
     * someone inserts a tab before it.</p>
     */
    public void showTabTitled(@NonNull String title) {
        for (int i = 0; i < tabs.size(); i++) {
            if (title.equals(tabs.get(i).title)) { switchTo(i); return; }
        }
    }

    /**
     * The tab content currently on screen, or {@code null}. The drawer stays ignorant of what a
     * tab CONTAINS — the caller asks the content to refresh itself — but it is the only thing
     * that knows which tab is showing, and during a slide there are briefly two children.
     * The LAST child is the incoming one, which is the one about to be looked at.
     */
    @Nullable
    public View currentTabContent() {
        int n = contentHost.getChildCount();
        return n == 0 ? null : contentHost.getChildAt(n - 1);
    }

    public void hide() {
        if (getVisibility() != VISIBLE) return;
        // Give the picture back its space on the way out, not after — the two animations run
        // together so the video rises as the drawer leaves rather than jumping when it lands.
        if (heightListener != null) heightListener.onDrawerHeightChanged(0);
        animate().translationY(-dp(120)).alpha(0f).setDuration(SLIDE_MS)
                .withEndAction(() -> {
                    setVisibility(GONE);
                    contentHost.removeAllViews();
                    if (onClose != null) onClose.run();
                }).start();
    }

    /** Re-read every header toggle's state (mute/hide/lock changed elsewhere). */
    public void refreshIcons() {
        for (int i = 0; i < toggles.size() && i < toggleIcons.size(); i++) {
            Toggle t = toggles.get(i);
            boolean on = t.state.isOn();
            ImageView iv = toggleIcons.get(i);
            iv.setImageResource(on ? t.iconOn : t.iconOff);
            iv.setColorFilter(on ? (t.dangerWhenOn ? DANGER : ACCENT) : TXT_DIM);
        }
        for (int i = 0; i < tabIcons.size(); i++) {
            // Tab index 0 is Video, which has no icon of its own — the icons are 1..n, so an
            // icon is green exactly when ITS tab is the one on screen.
            tabIcons.get(i).setColorFilter(activeTab == i + 1 ? ACCENT : TXT_DIM);
        }
    }

    private void buildIconRow() {
        iconRow.removeAllViews();
        tabIcons.clear();
        toggleIcons.clear();
        // Tabs first, then a divider, then the action toggles. Grouping matters: one group
        // NAVIGATES and the other CHANGES something, and mixing them evenly would make the
        // destructive ones (mute, hide) look like places you can visit.
        for (int i = 1; i < tabs.size(); i++) {
            final int idx = i;
            ImageView iv = iconButton(tabs.get(i).iconRes);
            iv.setOnClickListener(v -> toggleTab(idx));
            iconRow.addView(iv);
            tabIcons.add(iv);
        }
        if (!tabs.isEmpty() && !toggles.isEmpty()) {
            View divider = new View(getContext());
            divider.setBackgroundColor(0x33FFFFFF);
            LayoutParams lp = new LayoutParams(Math.max(1, dp(1)), dp(18));
            lp.leftMargin = dp(5);
            lp.rightMargin = dp(5);
            iconRow.addView(divider, lp);
        }
        for (Toggle t : toggles) {
            ImageView iv = iconButton(t.iconOff);
            iv.setOnClickListener(v -> { t.onTap.run(); refreshIcons(); });
            iconRow.addView(iv);
            toggleIcons.add(iv);
        }
    }

    @NonNull
    private ImageView iconButton(int res) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(dp(7), dp(7), dp(7), dp(7));
        // 38dp touch target around a 24dp glyph — a 24dp target is not finger-sized, the same
        // correction the lane mute icon needed.
        LayoutParams lp = new LayoutParams(dp(38), dp(38));
        iv.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x22FFFFFF);
        iv.setBackground(bg);
        return iv;
    }

    /** Tapping an active tab's icon returns to Video — the toggle behaviour the user specified. */
    private void toggleTab(int index) {
        switchTo(activeTab == index ? 0 : index);
    }

    /**
     * Animate to {@code target}. Direction is derived from the tab ORDER, so the panels always
     * move the way their positions imply — going forward pushes the old one left, going back
     * pushes it right. Getting that backwards is what makes tabbed UIs feel like they teleport.
     */
    private void switchTo(int target) {
        if (animating || target == activeTab || target < 0 || target >= tabs.size()) return;
        final boolean forward = target > activeTab;
        final View outgoing = contentHost.getChildAt(0);
        final View incoming = wrap(tabs.get(target).content.build(getContext()));
        final float w = Math.max(getWidth(), dp(300));

        incoming.setTranslationX(forward ? w : -w);
        contentHost.addView(incoming);
        animating = true;

        // Animate the HEIGHT as well as the slide. The tabs are different lengths (Mask has
        // eight rows, Chroma collapses to one line), and without this the backdrop jumped to
        // its new size in a single frame while the content was still sliding — the panel
        // appeared to resize before its contents arrived. Measure the incoming view against
        // the host's real width, then tween between the two heights over the same duration.
        final int fromH = contentHost.getHeight();
        incoming.measure(
                MeasureSpec.makeMeasureSpec(contentHost.getWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        final int toH = Math.max(1, incoming.getMeasuredHeight());
        if (fromH > 0 && Math.abs(toH - fromH) > 1) {
            android.animation.ValueAnimator va =
                    android.animation.ValueAnimator.ofInt(fromH, toH);
            va.setDuration(SLIDE_MS);
            va.setInterpolator(new DecelerateInterpolator());
            va.addUpdateListener(anim -> {
                ViewGroup.LayoutParams p = contentHost.getLayoutParams();
                p.height = (int) anim.getAnimatedValue();
                contentHost.setLayoutParams(p);
            });
            va.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    // Back to WRAP_CONTENT so the panel keeps tracking its content afterwards
                    // — a pinned pixel height would go stale the moment a row appears or hides
                    // (the chroma tab's body does exactly that).
                    ViewGroup.LayoutParams p = contentHost.getLayoutParams();
                    p.height = LayoutParams.WRAP_CONTENT;
                    contentHost.setLayoutParams(p);
                }
            });
            va.start();
        }

        titleView.animate().alpha(0f).translationX(forward ? -dp(16) : dp(16))
                .setDuration(SLIDE_MS / 2).withEndAction(() -> {
                    titleView.setText(tabs.get(target).title);
                    titleView.setTranslationX(forward ? dp(16) : -dp(16));
                    titleView.animate().alpha(1f).translationX(0f)
                            .setDuration(SLIDE_MS / 2).start();
                }).start();

        if (outgoing != null) {
            outgoing.animate().translationX(forward ? -w : w).alpha(0f)
                    .setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> contentHost.removeView(outgoing)).start();
        }
        incoming.animate().translationX(0f).setDuration(SLIDE_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    animating = false;
                    activeTab = target;
                    refreshIcons();
                }).start();
    }

    /**
     * Every tab's content scrolls inside a height cap. Without it a long tab (Mask has seven
     * sliders) would push the drawer over the whole preview, which is the thing the top
     * placement was chosen to avoid.
     */
    @NonNull
    /**
     * The scrolling body, clamped at MEASURE time.
     *
     * <p>The cap used to be applied once, inside a single {@code post()} at tab-build time.
     * {@code FxPanel} then rebuilds its children in place on every add / delete / reorder, so
     * every card added after that first layout grew {@code WRAP_CONTENT} straight past the cap
     * and the drawer swallowed the screen. Clamping in {@code onMeasure} cannot be outrun by a
     * later rebuild, which is the whole difference.</p>
     *
     * <p>The user can also drag the grip to resize (see {@code wireGrip}); {@link #userHeightPx}
     * overrides the fraction when they have expressed a preference.</p>
     */
    private View wrap(@NonNull View content) {
        ScrollView sv = new ScrollView(getContext()) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int cap = maxBodyHeightPx();
                super.onMeasure(widthSpec,
                        MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
            }
        };
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(false);
        sv.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        sv.setMinimumHeight(0);
        bodyScroll = sv;
        return sv;
    }

    /** The tallest the scrolling body may be: the user's dragged height, else the fraction. */
    private int maxBodyHeightPx() {
        int screen = getResources().getDisplayMetrics().heightPixels;
        if (userHeightPx > 0) {
            return Math.max(dp(MIN_BODY_DP), Math.min(userHeightPx,
                    Math.round(screen * ABSOLUTE_MAX_FRACTION)));
        }
        return Math.round(screen * MAX_HEIGHT_FRACTION);
    }

    /** Smallest useful body — below this the drawer is chrome with nothing in it. */
    private static final int MIN_BODY_DP = 96;
    /** Even a deliberate drag stops here; past it the drawer IS the screen. */
    private static final float ABSOLUTE_MAX_FRACTION = 0.82f;

    /** The scrolling body of the current tab, for the resize drag. */
    @Nullable private ScrollView bodyScroll;
    /** Height the user dragged to, or 0 for "use the default fraction". Session-scoped. */
    private int userHeightPx;

    private int dp(int v) { return Math.round(v * density); }
}
