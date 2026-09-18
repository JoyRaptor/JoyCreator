package com.fadcam.ui.faditor.tools;

import com.fadcam.ui.faditor.Studio;

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
public final class ObjectDrawer extends LinearLayout {

    /**
     * THE SCRIM, AND WHY IT GOT DARKER RATHER THAN LIGHTER.
     *
     * <p>This was black at 40%, chosen so the PiP stayed watchable behind it. Measured to
     * WCAG 2.1 against a blown-out frame (#FBBF24 — sunlit skin, a white slide, a bright
     * sky), that came out at:</p>
     *
     * <pre>
     *   black 40%   body #F4F4F5 3.36:1   secondary #8A8A94 1.39:1
     *   black 64%   body #F4F4F5 7.25:1   secondary #C4C4CE 4.86:1
     * </pre>
     *
     * <p>1.39:1 is not dim text, it is invisible text — and only outdoors, which is why it
     * survived so long indoors. The floor for body copy is 4.5:1, so the scrim goes to 64%
     * and the secondary ink comes UP to meet it. Blur cannot rescue this either: RenderEffect
     * is API 31+, minSdk here is 24, and the test phone is API 29, so tint is the only lever
     * and it has to do all the work.</p>
     *
     * <p>You can still watch the video through it. You can now also read the labels.</p>
     */
    private static final int SCRIM = Studio.alpha(Studio.GROUND, 0xA3);
    // Over the frosted scrim, so this is the DRAWER ramp, not the screen ramp.
    // Same value it has always rendered; it simply asks for it by the right name now.
    private static final int TXT = Studio.DRAWER_INK;
    private static final int TXT_DIM = Studio.DRAWER_DIM;
    /**
     * Active tab / engaged toggle. Defaults to the SELECTED state colour rather than green,
     * because green now means Studio. {@link #setAccent(int)} lets the host hand the drawer
     * the colour of the OBJECT being edited, so a sprite's drawer is amber and a text
     * drawer is purple — which is what tells you what you are editing without spending a
     * row on a label saying so.
     */
    private int accent = Studio.ARMED;
    private static final int DANGER = Studio.DANGER;
    private static final int SLIDE_MS = 240;
    /** Cap so the drawer can never swallow the preview; content scrolls inside. */
    private static final float MAX_HEIGHT_FRACTION = 0.55f;
    private static final float MAX_HEIGHT_FRACTION_AUDIO_ONLY = 0.75f;
    private boolean audioOnly;

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
    private final LinearLayout header;
    private final FrameLayout contentHost;
    // SPEC_20260831_CAPTION_SLIDES_UX §7.2.1: optional header extras — a view immediately
    // BEFORE the title (the CC glyph) and one immediately AFTER it (the track pills row).
    // Null = none; every show() removes the previous show's extras first, so nulls CLEAR.
    @Nullable private View leadingView;
    @Nullable private View middleView;
    private final List<Tab> tabs = new ArrayList<>();
    private final List<ImageView> tabIcons = new ArrayList<>();
    private final List<Toggle> toggles = new ArrayList<>();
    private final List<ImageView> toggleIcons = new ArrayList<>();

    private int activeTab = 0;

    /** Which tab the NEXT {@link #show} opens on. Reset to 0 as soon as it is used. */
    private int openOnTab = 0;

    /**
     * Ask the next {@code show} to open on this tab index instead of the first.
     *
     * <p>Deliberately a one-shot rather than a remembered preference. A drawer that remembers its
     * tab across objects opens on Mask for a picture because the last thing you touched was a
     * different picture's mask, which is a worse guess than "the first tab" — and the case that
     * actually matters is not memory at all but relevance: a rigged picture opens on Puppet
     * because it is rigged.
     */
    public void openOnTab(int index) { openOnTab = Math.max(0, index); }

    /**
     * Tint this drawer to the object it is editing.
     *
     * <p>Object colour is a FILL; a STATE colour is a ring. That split is what keeps
     * sprite-amber and careful-amber from ever being confused, and it means the drawer's
     * own controls can say what you are editing without a dedicated row announcing it.</p>
     */
    public void setAccent(int colour) {
        this.accent = colour;
        refreshIcons();
    }

    private boolean animating;
    @Nullable private Runnable onClose;

    public ObjectDrawer(@NonNull Context ctx) {
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
        // ...but that clickable panel then showed up on the sandbox as a 548 × 187dp node with
        // no accessible name: TalkBack would offer the whole drawer as an unlabelled button
        // before reaching anything inside it. NO (not NO_HIDE_DESCENDANTS) drops the container
        // from the tree and leaves every child in it, which is what a tap-blocker should be.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        header = new LinearLayout(ctx);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(8), dp(6), dp(6));

        titleView = new TextView(ctx);
        titleView.setTextColor(TXT);
        titleView.setTextSize(15);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        // Shadow rather than a solid bar: keeps the title readable on a bright frame without
        // giving up the transparency the drawer exists for.
        titleView.setShadowLayer(4f * density, 0f, 1f, Studio.alpha(Studio.GROUND, 0xCC));
        // JoyRaptor, 2026-09-16, reporting this as a daily obstruction: "there's not enough
        // room for the label so the label then stacks vertically instead of reading
        // horizontally and it pushes the title header to be huge which then subsequently
        // pushes down button rows until all the usable buttons are past the minimum openness
        // of a drawer... I used to be able to have the drawer open a little so that I could
        // have one row working while I work on the preview underneath."
        //
        // The title was a weighted, WRAP_CONTENT TextView with no line cap, so as soon as the
        // icon row grew, the title's width shrank and the text WRAPPED. A drawer header must
        // be a fixed-height object: it may run out of room for CHARACTERS, never for ROWS.
        // Truncating a title costs a few letters; growing the header costs the whole drawer.
        titleView.setMaxLines(1);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(titleView, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        iconRow = new LinearLayout(ctx);
        iconRow.setOrientation(HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        // G9: header toggles were reported invisible — ensure iconRow is above scrim
        iconRow.setElevation(4f * density);
        header.setElevation(3f * density);
        // The icon row is WRAP_CONTENT inside a fixed-width header: with enough toggles it
        // simply ran off the right edge and the ones past it became unreachable. Wrapping it
        // in a non-scrollbarred HorizontalScrollView means overflow SCROLLS instead of
        // disappearing, and it still costs nothing when everything fits.
        android.widget.HorizontalScrollView iconScroll = new android.widget.HorizontalScrollView(ctx);
        iconScroll.setHorizontalScrollBarEnabled(false);
        iconScroll.setClipToPadding(false);
        iconScroll.addView(iconRow, new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        header.addView(iconScroll, new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextColor(TXT_DIM);
        close.setTextSize(17);
        close.setPadding(dp(12), dp(4), dp(10), dp(4));
        // A glyph is not a label. Measured on the sandbox this was a clickable node whose
        // only accessible name was the character "✕" — the one control every user of this
        // drawer needs, announced as a dingbat. It is the same Close the rest of the app says.
        close.setContentDescription(ctx.getString(com.fadcam.R.string.universal_close));
        // 32.5 × 31.5dp as measured. Studio Final §04 puts the floor at 28 and the norm at
        // "almost everything 40 or 44"; a drawer's only dismiss button belongs at the norm,
        // not on the floor. Gravity keeps the glyph where it was drawn, so only the box grows.
        close.setMinWidth(dp(40));
        close.setMinHeight(dp(40));
        close.setGravity(Gravity.CENTER);
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
        pillBg.setColor(Studio.alpha(Studio.INK, 0x88));
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
                            // CLAMPED AT ASSIGNMENT. Unclamped, any upward drag past the start
                            // height drove this to <= 0, maxBodyHeightPx read that as "unset"
                            // and snapped the body back to 55% of the screen -- so dragging up
                            // both failed to shrink AND, because it had grown again, failed the
                            // at-floor test that dismisses. The grip did nothing in one whole
                            // direction.
                            int screen = getResources().getDisplayMetrics().heightPixels;
                            userHeightPx = Math.max(dp(MIN_BODY_DP),
                                    Math.min(Math.round(startHeight + dy),
                                            Math.round(screen * ABSOLUTE_MAX_FRACTION)));
                            bodyScroll.requestLayout();
                            // Report DURING the drag, throttled by the layout pass itself, so
                            // the picture moves with the finger instead of jumping when it lifts.
                            reportHeight();
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
                            userHeightPx = -1;
                            hide();
                        } else {
                            v.performHapticFeedback(
                                    android.view.HapticFeedbackConstants.CLOCK_TICK);
                            post(ObjectDrawer.this::reportHeight);
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
     * Called after the visible tab changes — on {@link #show} and on every switch.
     *
     * <p>Added for puppeteering, which is the first tab whose content lives OUTSIDE the drawer:
     * the pins are on the picture, so something has to know when to put them up and when to take
     * them down. Without it the pins would survive a switch to Mask and contend for touch with
     * the handle overlays, which is exactly the several-views-one-hit-test bug this project has
     * already paid for once.
     */
    public void setOnTabChanged(@Nullable Runnable r) { this.onTabChanged = r; }

    @Nullable private Runnable onTabChanged;

    /** The title of the tab showing right now, or null when the drawer is empty. */
    @Nullable
    public String currentTabTitle() {
        return (activeTab >= 0 && activeTab < tabs.size()) ? tabs.get(activeTab).title : null;
    }

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

    /** Last height handed to the listener, so a layout pass that changed nothing costs nothing. */
    private int reportedHeightPx = Integer.MIN_VALUE;

    /** True between {@link #hide} and the slide-out landing — the drawer is still VISIBLE then. */
    private boolean hiding;
    private boolean lightAdjust;

    public boolean isLightAdjust() { return lightAdjust; }

    private void reportHeight() {
        if (heightListener == null) return;
        // SILENT WHILE THE DRAWER IS ANIMATING ITS OWN HEIGHT. switchTo writes a new content
        // height every frame for 240ms; reporting each one restarted the preview's 220ms tween
        // on every frame, so the picture crawled and lagged behind the drawer instead of moving
        // with it. The animator's end action reports the settled height once.
        if (animating) return;
        int h = (getVisibility() == VISIBLE && !hiding) ? getHeight() : 0;
        if (h == reportedHeightPx) return;
        reportedHeightPx = h;
        heightListener.onDrawerHeightChanged(h);
    }

    /**
     * Report on EVERY layout, not only on show and grip-drag.
     *
     * <p>The drawer changes height by itself constantly: opening the effect picker, adding a
     * card, expanding a collapsed one. Only the two explicit calls existed, so the picture stayed
     * where the drawer USED to end and the newly grown drawer covered it again — the exact thing
     * the reflow was added to stop. Deduped against the last reported value so the ordinary
     * layout traffic does not restart the 220ms tween on every pass.</p>
     */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        reportHeight();
    }

    /**
     * Bind tabs + header toggles and show tab 0. Rebuilding is cheap and is how the caller
     * retargets the drawer at a different clip.
     *
     * @param lightAdjust true to light the Adjust/FX carousel tool while open (B2: only
     *                    adjustment layers light it; audio and others do not)
     */
    public void show(@NonNull List<Tab> tabList, @NonNull List<Toggle> toggleList, boolean lightAdjust) {
        // SPEC_20260831_CAPTION_SLIDES_UX §7.2.1: every other drawer enters here — delegate with
        // null extras so they can never inherit the caption drawer's pills, and so the caption
        // drawer retargeted at a non-caption object drops them.
        show(tabList, toggleList, lightAdjust, null, null);
    }

    /**
     * Full show with optional header extras (SPEC_20260831_CAPTION_SLIDES_UX §7.2.1):
     * {@code leadingView} is inserted immediately BEFORE the title (the CC glyph) and
     * {@code middleView} immediately AFTER it, before the icon row (the track pills row).
     * Both WRAP_CONTENT; titleView keeps its weight-1 width so it compresses when the pills
     * row is wide. Nulls CLEAR: the previous show's extras are always removed first, so
     * drawers shown without extras are unaffected.
     */
    public void show(@NonNull List<Tab> tabList, @NonNull List<Toggle> toggleList, boolean lightAdjust,
                     @Nullable View leadingView, @Nullable View middleView) {
        // A pending close belongs to the object being replaced. show() used to rebind without
        // it, so opening a second object's drawer dropped the first one's FX undo step entirely
        // -- or, worse, left it registered and fired it later against an object not on screen.
        if (onClose != null) { Runnable r = onClose; onClose = null; r.run(); }
        // CANCEL A SLIDE-OUT IN FLIGHT. hide()'s end action sets GONE and wipes contentHost;
        // reopening within those 240ms left that action queued, so the freshly shown drawer was
        // destroyed a moment later — and because a GONE view gets no more onLayout, the preview
        // stayed shrunk and shoved down with no drawer above it and the Adjust tool stuck green.
        animate().cancel();
        setTranslationY(0f);
        setAlpha(1f);
        hiding = false;
        this.lightAdjust = lightAdjust;
        // A height dragged on one tab must not follow the drawer to a different object: sizing
        // the FX tab tall and then opening a one-row tab left three-quarters of a screen empty.
        userHeightPx = -1;
        tabs.clear();
        tabs.addAll(tabList);
        toggles.clear();
        toggles.addAll(toggleList);
        // Header extras (§7.2.1): remove the previous show's views first — a null extra must
        // always CLEAR (so other drawers never inherit the caption pills), and a re-shown
        // drawer must not stack a second copy of its own.
        if (this.leadingView != null) header.removeView(this.leadingView);
        if (this.middleView != null) header.removeView(this.middleView);
        this.leadingView = leadingView;
        this.middleView = middleView;
        if (leadingView != null) {
            header.addView(leadingView, header.indexOfChild(titleView),
                    new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
        if (middleView != null) {
            LayoutParams mlp = new LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            mlp.rightMargin = dp(6);
            // Immediately after the title — indexOfChild again, since a leadingView (added
            // above) has shifted it; this keeps middleView before iconRow either way.
            header.addView(middleView, header.indexOfChild(titleView) + 1, mlp);
        }
        buildIconRow();
        // WHICH TAB OPENS. Zero unless a caller asked for another one, and the request is
        // consumed here so it can never leak into the next drawer. This exists because a picture
        // that has been RIGGED should open on Puppet: its owner is not coming back to the drawer
        // to change a blend mode, and making them find the last tab every time is the kind of
        // small tax that adds up to a tool feeling slow.
        activeTab = (openOnTab > 0 && openOnTab < tabs.size()) ? openOnTab : 0;
        openOnTab = 0;
        contentHost.removeAllViews();
        contentHost.addView(wrap(tabs.get(activeTab).content.build(getContext())));
        titleView.setText(tabs.get(activeTab).title);
        refreshIcons();
        if (onTabChanged != null) onTabChanged.run();
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

    /**
     * Rebuild the CURRENT tab's content in place — no slide animation, no activeTab reset, no
     * title change (SPEC_20260831_CAPTION_SLIDES_UX §7.1.5). Exists so a caption TRACK SWITCH
     * can refresh the drawer's values without a full {@link #show}, which would snap the drawer
     * back to tab 0 while the user is mid-edit in Fit ("if you're in fit, fit doesn't change,
     * just the track changes"). Safe while animating: a slide owns contentHost, so a refresh
     * mid-flight is a no-op rather than two panels fighting over one host.
     */
    public void refreshCurrentTab() {
        if (animating || tabs.isEmpty()) return;
        contentHost.removeAllViews();
        contentHost.addView(wrap(tabs.get(activeTab).content.build(getContext())));
        post(this::reportHeight);
    }

    /**
     * True while a tab slide owns {@code contentHost}, i.e. while {@link #refreshCurrentTab()}
     * is a no-op. Callers that MUST land (a caption track switch: the drawer would otherwise
     * keep showing the previously selected track's settings) poll this and re-post.
     */
    public boolean isAnimatingTabs() { return animating; }

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
        // Announce first: a tab-driven overlay (the puppet pins) has to come
        // down with the drawer, not linger over the picture with nothing to
        // explain it.
        if (onTabChanged != null) onTabChanged.run();
        if (getVisibility() != VISIBLE) return;
        // Give the picture back its space on the way out, not after — the two animations run
        // together so the video rises as the drawer leaves rather than jumping when it lands.
        hiding = true;
        reportedHeightPx = 0;
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
            // G9: ensure visible tint even over dark scrim — use solid colours with shadow
            iv.setColorFilter(on ? (t.dangerWhenOn ? DANGER : accent) : TXT_DIM);
            iv.setAlpha(1f);
            iv.setElevation(6f * density);
            iv.invalidate();
        }
        for (int i = 0; i < tabIcons.size(); i++) {
            tabIcons.get(i).setColorFilter(activeTab == i + 1 ? accent : TXT_DIM);
            tabIcons.get(i).setAlpha(1f);
            tabIcons.get(i).setElevation(6f * density);
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
            divider.setBackgroundColor(Studio.alpha(Studio.INK, 0x33));
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
        // G9: draw over scrim with elevation and slightly more opaque oval so it does not vanish on dark video
        iv.setElevation(5f * density);
        LayoutParams lp = new LayoutParams(dp(38), dp(38));
        iv.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Studio.alpha(Studio.INK, 0x33));
        iv.setBackground(bg);
        iv.setAlpha(1f);
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
                    if (onTabChanged != null) onTabChanged.run();
                    refreshIcons();
                    // The one report for this tab switch — reportHeight stays silent for the
                    // whole animation so the preview makes a single move, not sixty.
                    reportHeight();
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
                // EXACTLY when the user has dragged a size, AT_MOST otherwise. With AT_MOST the
                // ScrollView still measures to its content, so userHeightPx only ever raised a
                // ceiling -- and on any tab shorter than the cap (Chroma, a two-card stack)
                // dragging DOWN did nothing at all and the grip read as broken.
                int mode = userHeightPx > 0 ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST;
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, mode));
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
        float frac = audioOnly ? MAX_HEIGHT_FRACTION_AUDIO_ONLY : MAX_HEIGHT_FRACTION;
        return Math.round(screen * frac);
    }

    public void setAudioOnly(boolean v) { audioOnly = v; }
    public boolean isAudioOnly() { return audioOnly; }

    /** Smallest useful body — below this the drawer is chrome with nothing in it. */
    private static final int MIN_BODY_DP = 96;
    /** Even a deliberate drag stops here; past it the drawer IS the screen. */
    private static final float ABSOLUTE_MAX_FRACTION = 0.82f;

    /** The scrolling body of the current tab, for the resize drag. */
    @Nullable private ScrollView bodyScroll;
    /** Height the user dragged to, or 0 for "use the default fraction". Session-scoped. */
    private int userHeightPx = -1;

    private int dp(int v) { return Math.round(v * density); }
}
