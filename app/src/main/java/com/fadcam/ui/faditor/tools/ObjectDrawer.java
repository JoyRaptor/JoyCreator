package com.fadcam.ui.faditor.tools;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

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
 *
 * <h3>Shape — Studio Final (record 06) §02, drawn at real size</h3>
 * <pre>
 *   .dh    38dp  ● name  [■ Transform] [▣] [◐] [⧉] ···  [toggles] ✕    ONE line
 *   body         the tab's own rows
 *   .dgrab       ━━  38 × 3.5                                  drag to size, tap to close
 * </pre>
 * The tabs sat on their own row under the header for a while; JoyRaptor (2026-09-23) put
 * them back on the header line — the extra row cost a line of picture and put tab 0 ("Image")
 * right under the title ("Image"). Tab 0's pill now names what it DOES (Tab#tabLabel) and
 * the header names the object. Every drawer-wide visual (chip, row, slider,
 * checkbox, section label) is built by {@link Kit}, so a tab file asks for "a chip" instead of
 * restating one.
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
        /**
         * The pill's own name, when it differs from {@link #title}. Tab 0's title is the
         * DRAWER's name ("Image"), shown in the header; its pill says what the tab DOES
         * ("Transform"). Both saying "Image" side by side was the redundancy JoyRaptor flagged.
         */
        @Nullable final String tabLabel;
        final int iconRes;
        final ContentBuilder content;

        public Tab(@NonNull String title, int iconRes, @NonNull ContentBuilder content) {
            this(title, null, iconRes, content);
        }

        public Tab(@NonNull String title, @Nullable String tabLabel, int iconRes,
                   @NonNull ContentBuilder content) {
            this.title = title;
            this.tabLabel = tabLabel;
            this.iconRes = iconRes;
            this.content = content;
        }

        @NonNull String pillLabel() { return tabLabel != null ? tabLabel : title; }

        /**
         * Open this tab showing only its first {@code rows} top-level rows, with MORE on the
         * grip for the rest (0 = the drawer's normal height). The text tab uses 2: its font
         * and style toolbar and the trim line are touched constantly, the rest is set once
         * (JoyRaptor, 2026-08-12: "better that they only have to expand 10% of the time").
         */
        int peekRows;

        @NonNull public Tab peek(int rows) { peekRows = Math.max(0, rows); return this; }
    }

    public interface ContentBuilder { @NonNull View build(@NonNull Context ctx); }

    /** A header toggle that is an ACTION, not a tab (mute / hide / lock). */
    public static final class Toggle {
        final int iconOn, iconOff;
        final ToggleState state;
        final Runnable onTap;
        /** true = engaged state is a WARNING (muted, hidden), so it tints red not green. */
        final boolean dangerWhenOn;
        /**
         * What the button is called, for TalkBack and for the hover tooltip a stylus or mouse
         * shows. Null = derived from the icon (see {@link #toggleLabel}), which is how every
         * existing caller gets a name without being edited.
         */
        @Nullable final CharSequence label;

        public Toggle(int iconOn, int iconOff, @NonNull ToggleState state,
                      @NonNull Runnable onTap, boolean dangerWhenOn) {
            this(iconOn, iconOff, state, onTap, dangerWhenOn, null);
        }

        public Toggle(int iconOn, int iconOff, @NonNull ToggleState state,
                      @NonNull Runnable onTap, boolean dangerWhenOn,
                      @Nullable CharSequence label) {
            this.iconOn = iconOn;
            this.iconOff = iconOff;
            this.state = state;
            this.onTap = onTap;
            this.dangerWhenOn = dangerWhenOn;
            this.label = label;
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
    /** One view per tab, INCLUDING tab 0 — see {@link #buildTabRow}. */
    private final List<LinearLayout> tabViews = new ArrayList<>();
    /** The {@code .dt} row the tabs live in, and its scroller (right-edge fade on overflow). */
    private final LinearLayout tabRow;
    private final android.widget.HorizontalScrollView tabScroll;
    /** The {@code .dot}: the object's colour, beside its name. */
    private final View dot;
    private final List<Toggle> toggles = new ArrayList<>();
    private final List<ImageView> toggleIcons = new ArrayList<>();

    private int activeTab = 0;

    /**
     * Which tab the NEXT {@link #show} opens on; -1 = not asked (keep the place, else the first).
     * Reset as soon as it is used. An explicit 0 beats keeping the place: new text opens on its
     * Text tab, where the keyboard belongs.
     */
    private int openOnTab = -1;

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

    /** The tab on screen now (0 = the object's own first tab). */
    public int activeTabIndex() { return activeTab; }

    /**
     * Open the NEXT {@link #show} as a header-only strip: name, tabs, toggles, no body. A tap on
     * any tab (or on the grip) opens that tab. For the sprite, whose frames palette comes up
     * from the bottom at the same time (JoyRaptor, 2026-09-23: "the real issue is not having
     * preview covered by the drawer every time you want to animate the sprites"): the drawer
     * is there, one tap from its controls, without taking the picture.
     */
    public void openCollapsed() { collapseOnShow = true; }
    private boolean collapseOnShow;
    /** The body is hidden (see {@link #openCollapsed}); no tab pill is lit. */
    private boolean collapsed;

    /** Bring the body back after {@link #openCollapsed}, on {@code index}. */
    private void expandTo(int index) {
        collapsed = false;
        contentHost.setVisibility(VISIBLE);
        if (index != activeTab) {
            switchTo(index);
        } else {
            paintTabs(activeTab);
        }
        post(this::reportHeight);
    }

    /**
     * Tint this drawer to the object it is editing.
     *
     * <p>Object colour is a FILL; a STATE colour is a ring. That split is what keeps
     * sprite-amber and careful-amber from ever being confused, and it means the drawer's
     * own controls can say what you are editing without a dedicated row announcing it.</p>
     */
    public void setAccent(int colour) {
        this.accent = colour;
        sAccent = colour;
        paintDot();
        refreshIcons();
    }

    /**
     * The accent of the drawer most recently tinted. Static because tab content is BUILT before
     * it is attached to the drawer (so it cannot look up its parent), and the editor only ever
     * has one drawer. {@link Kit} reads it so an "on" chip in a tab file fills with the object's
     * colour without every tab builder growing an accent parameter.
     */
    private static int sAccent = Studio.ARMED;

    private void paintDot() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(accent);
        dot.setBackground(g);
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
        final GradientDrawable bg = new GradientDrawable();
        float r = 18f * density;
        bg.setCornerRadii(new float[]{0f, 0f, 0f, 0f, r, r, r, r});
        setBackground(bg);
        Kit.followDrawerFill(this);
        // Consume touches so a tap on the drawer never reaches the preview underneath and
        // starts dragging the very PiP being edited.
        setClickable(true);
        // ...but that clickable panel then showed up on the sandbox as a 548 × 187dp node with
        // no accessible name: TalkBack would offer the whole drawer as an unlabelled button
        // before reaching anything inside it. NO (not NO_HIDE_DESCENDANTS) drops the container
        // from the tree and leaves every child in it, which is what a tap-blocker should be.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        // ── .dh — the header, 38dp: dot · name · [extras] · toggles · ✕ ─────────────────────
        header = new LinearLayout(ctx);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), 0, dp(2), 0);
        // A MINIMUM, not a fixed height: the caption drawer's track pills ride in this row and
        // must not be clipped. The title is single-line, so nothing of ours can grow it.
        header.setMinimumHeight(dp(38));

        // .dot — 7dp, the object's colour. What you are editing, said without a word.
        dot = new View(ctx);
        dot.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams dotLp = new LayoutParams(dp(7), dp(7));
        dotLp.rightMargin = dp(6);
        header.addView(dot, dotLp);
        paintDot();

        titleView = new TextView(ctx);
        titleView.setTextColor(TXT);
        // .nm — 12sp, weight 700, drawer ink. The 15sp bold with a drop shadow it replaces was
        // sized for a title that had to shout over a 40% scrim; the scrim is 64% now and the
        // shadow was doing nothing the tint had not already done.
        titleView.setTextSize(12);
        Type.body(titleView, Type.BOLD);
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
        // WRAP, capped: the free width belongs to the tab strip now (one line, JoyRaptor
        // 2026-09-23: "bring all the tabs back to the top line").
        titleView.setMaxWidth(dp(120));
        header.addView(titleView, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        // ── .dt — the tabs, IN the header line ─────────────────────────────────────────────
        // They had their own 32dp row under the header, which cost a line of picture and put
        // the first tab ("Image") directly under the title ("Image"). Now: ● name · tabs ·
        // toggles · ✕, the tab strip taking the free width and scrolling when it runs out.
        tabRow = new LinearLayout(ctx);
        tabRow.setOrientation(HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabRow.setPadding(dp(8), 0, dp(4), 0);
        tabScroll = new android.widget.HorizontalScrollView(ctx);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        // .dt .fade — record 06 MAJOR 05: a row that scrolls and gives no sign of it is a row
        // whose tail nobody finds. The platform's fading edge is exactly that sign, and costs
        // nothing when everything fits (it only draws where there is more to scroll to).
        tabScroll.setHorizontalFadingEdgeEnabled(true);
        tabScroll.setFadingEdgeLength(dp(14));
        tabScroll.addView(tabRow, new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, dp(32)));
        header.addView(tabScroll, new LayoutParams(0, dp(32), 1f));

        iconRow = new LinearLayout(ctx);
        iconRow.setOrientation(HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
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
        // .x — 13sp in --ddim. The glyph is drawn at record size; the BOX is not.
        close.setTextColor(TXT_DIM);
        close.setTextSize(13);
        // A glyph is not a label. Measured on the sandbox this was a clickable node whose
        // only accessible name was the character "✕" — the one control every user of this
        // drawer needs, announced as a dingbat. It is the same Close the rest of the app says.
        Kit.describe(close, ctx.getString(com.fadcam.R.string.universal_close));
        // Studio Final §04 puts the floor at 28 and the norm at "almost everything 40 or 44";
        // a drawer's only dismiss button belongs at the norm, not on the floor. The record draws
        // a 26dp box — that is the glyph's footprint, and gravity keeps the glyph there while the
        // touch box stays 40 wide and the full header tall.
        close.setMinWidth(dp(40));
        close.setMinHeight(dp(38));
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> hide());
        Kit.pressable(close);
        header.addView(close);

        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        contentHost = new FrameLayout(ctx);
        contentHost.setClipChildren(true);
        addView(contentHost, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // ── .dgrab — pull-UP dismiss grip, at the BOTTOM edge ──────────────────────────────
        // The drawer hangs from the top, so the direction that puts it away is up. Same
        // affordance the bottom sheet had, mirrored. The record draws the strip 13dp tall; it
        // stays 16 here because it is also the RESIZE handle and every dp of it is grab area —
        // shrinking a drag target to match a drawing would be a behaviour change dressed as a
        // style one. The pill itself is the record's: 38 × 3.5, white at 30%.
        LinearLayout grip = new LinearLayout(ctx);
        grip.setOrientation(VERTICAL);
        grip.setGravity(Gravity.CENTER);
        grip.setPadding(0, dp(6), 0, dp(6));
        grip.setMinimumHeight(dp(16));
        // "MORE ⌄", ABOVE the pill on the same axis, shown only while a peeking tab (see
        // Tab#peek) has rows below the fold. A tap on the grip then expands instead of closing.
        moreHint = Kit.sectionLabel(ctx, ctx.getString(com.fadcam.R.string.faditor_lc_drawer_more));
        moreHint.setPadding(0, 0, 0, dp(3));
        moreHint.setVisibility(GONE);
        grip.addView(moreHint, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        View pill = new View(ctx);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(Kit.GRAB);
        pillBg.setCornerRadius(999f * density);
        pill.setBackground(pillBg);
        grip.addView(pill, new LayoutParams(dp(38), Math.max(1, Math.round(3.5f * density))));
        Kit.describe(grip, ctx.getString(com.fadcam.R.string.lane_a_drawer_grip));
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
                        boolean hasMore = moreHint != null && moreHint.getVisibility() == VISIBLE;
                        if (!moved && collapsed) {
                            // A collapsed drawer's grip opens it rather than closing it: the
                            // strip is already as small as a drawer gets; ✕ closes.
                            expandTo(activeTab);
                        } else if (!moved && hasMore) {
                            // A TAP means "show me the rest" while MORE is showing: the label is
                            // written on this grip, so the grip is its target. ✕ still closes.
                            expandToFull();
                        } else if (!moved || (dy < -slop * 2 && atFloor)) {
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
    /**
     * Set BEFORE {@link #show}; it lasts for that one opening. A listener used to outlive its
     * drawer: the image drawer's Puppet hook kept firing inside the next audio or PiP drawer.
     */
    public void setOnTabChanged(@Nullable Runnable r) { this.onTabChanged = r; tabListenerFresh = true; }
    private boolean tabListenerFresh;

    /** Switch to tab {@code index} with the usual slide (no-op if it is already showing). */
    public void selectTab(int index) { switchTo(index); }

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
        // KEEP THE PLACE. Read before anything is rebound: which tab is open right now, if the
        // drawer is up. Switching the selection to another object then opens ITS tab of the
        // same name (Blend stays on Blend), and a drawer rebuilding itself after an edit stays
        // where it was instead of snapping back to the first tab.
        final String keepPlace = getVisibility() == VISIBLE && activeTab > 0
                && activeTab < tabs.size() ? tabs.get(activeTab).pillLabel() : null;
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
        // A tab listener set for an earlier opening is not this drawer's.
        if (!tabListenerFresh) onTabChanged = null;
        tabListenerFresh = false;
        // WHICH TAB OPENS. Zero unless a caller asked for another one, and the request is
        // consumed here so it can never leak into the next drawer. This exists because a picture
        // that has been RIGGED should open on Puppet: its owner is not coming back to the drawer
        // to change a blend mode, and making them find the last tab every time is the kind of
        // small tax that adds up to a tool feeling slow.
        activeTab = (openOnTab >= 0 && openOnTab < tabs.size()) ? openOnTab : 0;
        if (openOnTab < 0 && keepPlace != null) {
            for (int i = 1; i < tabs.size(); i++) {
                if (keepPlace.equals(tabs.get(i).pillLabel())) { activeTab = i; break; }
            }
        }
        openOnTab = -1;
        contentHost.removeAllViews();
        contentHost.addView(wrap(tabs.get(activeTab).content.build(getContext()),
                tabs.get(activeTab).peekRows));
        collapsed = collapseOnShow;
        collapseOnShow = false;
        contentHost.setVisibility(collapsed ? GONE : VISIBLE);
        // The HEADER names the object; the active tab pill names the place. Tab 0's title is
        // the object's own ("Video overlay", "Image", a layer's name), so it is the header's
        // for the whole visit — it no longer flips to "Mask" when the pill below already says so.
        titleView.setText(tabs.get(0).title);
        buildTabRow();
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
        contentHost.addView(wrap(tabs.get(activeTab).content.build(getContext()),
                tabs.get(activeTab).peekRows));
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
            // Object colour is a FILL; a STATE colour is the glyph. An engaged warning toggle
            // (muted, hidden) goes danger-red, any other engaged one takes the object's colour,
            // and an idle one sits in the drawer's dim ink — never the screen ramp, which is
            // the 1.39:1 of record 06's CRITICAL 01 once the frame behind is bright.
            iv.setColorFilter(on ? (t.dangerWhenOn ? DANGER : accent) : TXT_DIM);
            iv.invalidate();
        }
        paintTabs(activeTab);
    }

    /**
     * Paint the tab row with {@code which} as the active pill.
     *
     * <p>Separate from {@link #refreshIcons} so a tap can light its pill THE MOMENT it lands,
     * not when the 240ms content slide finishes: a tab is a hundred-times-a-day control, and a
     * pill that lags the finger reads as a missed tap.</p>
     *
     * <p>Record 06 {@code .dt i}: idle is 32 × 24, radius 7, on the drawer control fill, glyph
     * in {@code --ddim}. Active widens to glyph + label, 11sp weight 700, filled with the
     * object's accent at 70% — the one filled thing in the row. A tab with no icon (tab 0 of
     * most drawers, and "A/V Sync") shows its NAME when idle instead, because an empty
     * 32dp square is a control with nothing on it: A/V Sync was exactly that until now.</p>
     */
    private void paintTabs(int which) {
        if (collapsed) which = -1;   // nothing is open, so no pill says it is
        for (int i = 0; i < tabViews.size() && i < tabs.size(); i++) {
            LinearLayout v = tabViews.get(i);
            Tab t = tabs.get(i);
            boolean on = i == which;
            ImageView icon = (ImageView) v.getChildAt(0);
            TextView label = (TextView) v.getChildAt(1);
            boolean hasIcon = t.iconRes != 0;
            int ink = on ? Kit.inkOn(accent) : TXT_DIM;
            icon.setVisibility(hasIcon ? VISIBLE : GONE);
            if (hasIcon) icon.setColorFilter(ink);
            label.setVisibility(on || !hasIcon ? VISIBLE : GONE);
            label.setTextColor(ink);
            Type.body(label, on ? Type.BOLD : Type.SEMIBOLD);
            ((LayoutParams) label.getLayoutParams()).leftMargin = hasIcon && on ? dp(5) : 0;
            // 9 + 14 + 9 = the record's 32dp idle square; 11 is its active side padding.
            int side = on ? dp(11) : dp(9);
            v.setPadding(side, 0, side, 0);
            Kit.background(v, Kit.tabBackground(getContext(), on ? Kit.onFill(accent) : Kit.CTL));
            v.setSelected(on);
        }
    }

    /**
     * Build the {@code .dt} row: EVERY tab, tab 0 included.
     *
     * <p>Tab 0 used to have no button at all — the way back to it was to tap the ACTIVE tab's
     * icon again, which nothing on screen said. That still works ({@link #toggleTab} is
     * unchanged); tab 0 is simply also visible now, as the place it always was.</p>
     *
     * <p>A drawer with one tab gets no row: a single pill that goes nowhere is a control that
     * does nothing, and it would cost 32dp of picture to say so.</p>
     */
    private void buildTabRow() {
        tabRow.removeAllViews();
        tabViews.clear();
        // A drawer with one tab shows no pill, but the strip stays: it is the weighted gap
        // that keeps the toggles and ✕ at the right edge.
        if (tabs.size() <= 1) return;
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            Tab t = tabs.get(i);
            LinearLayout v = new LinearLayout(getContext());
            v.setOrientation(HORIZONTAL);
            v.setGravity(Gravity.CENTER);
            v.setMinimumWidth(dp(32));
            ImageView icon = new ImageView(getContext());
            if (t.iconRes != 0) icon.setImageResource(t.iconRes);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            v.addView(icon, new LayoutParams(dp(14), dp(14)));
            TextView label = new TextView(getContext());
            label.setText(t.pillLabel());
            label.setTextSize(11);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // A tab 0 named after a layer can be any length; the row scrolls, but one runaway
            // name should not be the only thing in it.
            label.setMaxWidth(dp(112));
            label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            v.addView(label, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            Kit.describe(v, t.pillLabel());
            v.setOnClickListener(x -> toggleTab(idx));
            // Full row height for the finger (32dp), 24dp for the eye — the pill is inset.
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(32));
            if (i > 0) lp.leftMargin = dp(3);
            tabRow.addView(v, lp);
            tabViews.add(v);
        }
    }

    /** The header's action toggles. Tabs have their own row now — see {@link #buildTabRow}. */
    private void buildIconRow() {
        iconRow.removeAllViews();
        toggleIcons.clear();
        for (Toggle t : toggles) {
            ImageView iv = iconButton(t.iconOff);
            iv.setOnClickListener(v -> { t.onTap.run(); refreshIcons(); });
            CharSequence name = t.label != null ? t.label : toggleLabel(getContext(), t.iconOff);
            if (name != null) Kit.describe(iv, name);
            iconRow.addView(iv);
            toggleIcons.add(iv);
        }
    }

    /**
     * A header toggle's name, from its icon. Every existing Toggle is built with icons and no
     * words, so this is what gives mute, hide, lock and the rest a TalkBack name and a hover
     * tooltip without editing the call sites. A caller that passes a label wins.
     */
    @Nullable
    private static CharSequence toggleLabel(@NonNull Context ctx, int icon) {
        int res;
        if (icon == com.fadcam.R.drawable.ic_volume_up_24
                || icon == com.fadcam.R.drawable.ic_volume_off_24) {
            res = com.fadcam.R.string.lane_a_toggle_mute;
        } else if (icon == com.fadcam.R.drawable.ic_visibility_on_24
                || icon == com.fadcam.R.drawable.ic_visibility_off) {
            res = com.fadcam.R.string.lane_a_toggle_hide;
        } else if (icon == com.fadcam.R.drawable.ic_lock) {
            res = com.fadcam.R.string.lane_a_toggle_lock;
        } else if (icon == com.fadcam.R.drawable.ic_touch_press_24
                || icon == com.fadcam.R.drawable.ic_touch_press_off_24) {
            res = com.fadcam.R.string.lane_a_toggle_pass_through;
        } else if (icon == com.fadcam.R.drawable.ic_marker_flag_start) {
            res = com.fadcam.R.string.lane_a_toggle_start_here;
        } else if (icon == com.fadcam.R.drawable.ic_marker_flag_end) {
            res = com.fadcam.R.string.lane_a_toggle_end_here;
        } else if (icon == com.fadcam.R.drawable.ic_edit_cut) {
            res = com.fadcam.R.string.lane_a_toggle_split;
        } else if (icon == com.fadcam.R.drawable.ic_fx_24) {
            res = com.fadcam.R.string.lane_a_toggle_fx_bypass;
        } else {
            return null;
        }
        return ctx.getString(res);
    }

    /**
     * A header toggle: the record's {@code .dkf i} — a 28dp circle on the drawer control fill
     * with a 1dp ring, glyph 16dp. The VIEW is 32 × 38 so the finger gets the full header
     * height; the circle is inset inside it. It was a 38dp oval with a 24dp glyph and an
     * elevation shadow, which over a translucent fill drew a grey smudge rather than depth.
     */
    @NonNull
    private ImageView iconButton(int res) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(dp(8), dp(11), dp(8), dp(11));
        iv.setLayoutParams(new LayoutParams(dp(32), dp(38)));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Kit.CTL);
        bg.setStroke(Math.max(1, dp(1)), Kit.RING);
        Kit.background(iv, new android.graphics.drawable.InsetDrawable(bg, dp(2), dp(5), dp(2), dp(5)));
        return iv;
    }

    /** Tapping an active tab's icon returns to Video — the toggle behaviour the user specified. */
    private void toggleTab(int index) {
        if (collapsed) { expandTo(index); return; }
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
        final View incoming = wrap(tabs.get(target).content.build(getContext()),
                tabs.get(target).peekRows);
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

        // The pill lights now, not when the slide lands (see paintTabs). The header title no
        // longer travels: it names the object, which does not change when the tab does.
        paintTabs(target);

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
    private View wrap(@NonNull View content, final int peekRows) {
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
                if (userHeightPx <= 0) {
                    // The rows are measured now, so the peek height can be added up; a second
                    // pass only while the user has not dragged a height of their own.
                    int peek = peekBodyHeightPx(this, peekRows);
                    if (peek > 0 && peek < getMeasuredHeight()) {
                        super.onMeasure(widthSpec,
                                MeasureSpec.makeMeasureSpec(peek, MeasureSpec.EXACTLY));
                    }
                }
                post(ObjectDrawer.this::syncMoreHint);
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

    /**
     * Body height showing exactly {@code peekRows} top-level rows, or -1 when there is no peek,
     * the content is not a row container, a row is not measured yet, or nothing would be hidden.
     * A ROW COUNT, not a dp height, so "the first two rows" stays true when a row changes size.
     * A clean cut at the row boundary: MORE on the grip says there is more, in words.
     */
    private static int peekBodyHeightPx(@NonNull ScrollView sv, int peekRows) {
        if (peekRows <= 0) return -1;
        View content = sv.getChildCount() > 0 ? sv.getChildAt(0) : null;
        if (!(content instanceof ViewGroup)) return -1;
        ViewGroup rows = (ViewGroup) content;
        if (rows.getChildCount() <= peekRows) return -1;
        int sum = rows.getPaddingTop();
        for (int i = 0; i < peekRows; i++) {
            View row = rows.getChildAt(i);
            if (row.getVisibility() == GONE) continue;
            int h = row.getMeasuredHeight();
            if (h <= 0) return -1;
            ViewGroup.LayoutParams lp = row.getLayoutParams();
            if (lp instanceof MarginLayoutParams) {
                h += ((MarginLayoutParams) lp).topMargin + ((MarginLayoutParams) lp).bottomMargin;
            }
            sum += h;
        }
        return sum;
    }

    /** MORE shows only while there is something below the fold of the current tab. */
    private void syncMoreHint() {
        if (moreHint == null) return;
        ScrollView sv = bodyScroll;
        boolean scrollable = sv != null && sv.getChildCount() > 0
                && sv.getChildAt(0).getHeight() > sv.getHeight() + 1;
        moreHint.setVisibility(scrollable ? VISIBLE : GONE);
    }

    /** The tap behind MORE: grow to the default cap, never past it. */
    private void expandToFull() {
        int screen = getResources().getDisplayMetrics().heightPixels;
        float frac = audioOnly ? MAX_HEIGHT_FRACTION_AUDIO_ONLY : MAX_HEIGHT_FRACTION;
        userHeightPx = Math.round(screen * frac);
        if (bodyScroll != null) bodyScroll.requestLayout();
        post(this::reportHeight);
        post(this::syncMoreHint);
    }

    @Nullable private TextView moreHint;

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

    // ══ THE DRAWER KIT ═══════════════════════════════════════════════════════════════════
    //
    // JoyRaptor: "things calling helpers instead of hard coded so there are fewer areas to
    // break." Every tab file used to build its own chip, row, label and slider — four padding
    // schemes, three chip fills, some with a drop shadow and some without. This is record 06's
    // drawer component set (.dchip .dr .dsec .dsl .dcb .dgrab), once, for everything that sits
    // on the drawer's scrim.
    //
    // DRAWER-ONLY. Every ink here is the drawer ramp, which exists because the scrim sits over
    // moving video. A popover, a dialog or a panel on an opaque surface uses the screen ramp and
    // must not borrow these.

    /** Record 06's drawer components. Static; safe to call from any tab builder. */
    public static final class Kit {

        // -- THE DRAWER FILL -----------------------------------------------------------------
        // JoyRaptor, 2026-09-23: a drawer over the preview is ALWAYS see-through. "The user must
        // ALWAYS be able to see behind the drawer or you have blinded them." A Frost / Solid
        // switch shipped here on 2026-09-22 made "Solid" OPAQUE, which is exactly that; it is
        // gone. Frost, when it returns, is the SAME fill plus a blur on phones that can render
        // one (API 31+), never a darker or opaque one.
        //
        // HOW see-through is the owner's call, per phone (2026-09-24): "if we're at 65, let's
        // try 50% ... perhaps a slider in the settings tool." Default 50% see-through; the
        // Studio's Settings tool holds the slider; every drawer repaints the moment it moves.
        private static final String PREFS = "studio_drawer";
        private static final String KEY_SEE_THROUGH = "see_through_pct";
        public static final int SEE_THROUGH_DEFAULT = 50;
        public static final int SEE_THROUGH_MIN = 20;
        public static final int SEE_THROUGH_MAX = 85;

        /** How see-through drawers are, in percent (the Settings slider's value). */
        public static int seeThroughPct(@NonNull Context ctx) {
            int v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY_SEE_THROUGH, SEE_THROUGH_DEFAULT);
            return Math.max(SEE_THROUGH_MIN, Math.min(SEE_THROUGH_MAX, v));
        }

        public static void setSeeThroughPct(@NonNull Context ctx, int pct) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_SEE_THROUGH,
                            Math.max(SEE_THROUGH_MIN, Math.min(SEE_THROUGH_MAX, pct)))
                    .apply();
        }

        /** The one see-through drawer fill (--scrim), at the chosen see-through. */
        public static int drawerFill(@NonNull Context ctx) {
            return Studio.alpha(Studio.GROUND, Math.round((100 - seeThroughPct(ctx)) * 2.55f));
        }

        /** Repaint {@code v}'s fill now and whenever the slider moves; a no-op off a drawable fill. */
        public static void followDrawerFill(@NonNull View v) {
            final Context ctx = v.getContext();
            final android.content.SharedPreferences sp =
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            final Runnable paint = () -> {
                android.graphics.drawable.Drawable bg = v.getBackground();
                if (bg instanceof GradientDrawable) {
                    ((GradientDrawable) bg.mutate()).setColor(drawerFill(ctx));
                }
            };
            paint.run();
            // Held on the view: SharedPreferences keeps listeners weakly.
            android.content.SharedPreferences.OnSharedPreferenceChangeListener l =
                    (prefs, key) -> { if (KEY_SEE_THROUGH.equals(key)) v.post(paint); };
            v.setTag(com.fadcam.R.id.faditor_tag_drawer_fill, l);
            sp.registerOnSharedPreferenceChangeListener(l);
        }

        private Kit() {}

        // The record's white-alpha fills. There is no WHITE token and there should not be: these
        // are the drawer ink at a strength, so they move if the drawer ink ever does.
        /** {@code --dctl} rgba(255,255,255,.10): the fill of every idle drawer control. */
        public static final int CTL = Studio.alpha(Studio.DRAWER_INK, 0x1A);
        /** {@code --dring} rgba(255,255,255,.12): the 1dp ring an idle chip wears. */
        public static final int RING = Studio.alpha(Studio.DRAWER_INK, 0x1F);
        /** {@code .dsl .tr} rgba(255,255,255,.18): a slider's empty track. */
        public static final int TRACK = Studio.alpha(Studio.DRAWER_INK, 0x2E);
        /** {@code .dgrab span} rgba(255,255,255,.30): the grab pill. */
        public static final int GRAB = Studio.alpha(Studio.DRAWER_INK, 0x4D);
        /** An ON control is the object's accent at 70% (record 06 {@code .dchip.on}). */
        public static final int ON_ALPHA = 0xB3;
        /** Record 06 §04: press is 140ms to scale .97. */
        public static final int PRESS_MS = 140;

        /** The accent of the drawer being built — the object's colour. */
        public static int accent() { return sAccent; }

        /** An ON fill: {@code accent} at 70%. */
        public static int onFill(int accent) { return Studio.alpha(accent, ON_ALPHA); }

        /**
         * The ink for text or a glyph sitting ON an accent fill: dark or light, whichever reads.
         *
         * <p>Record 06 draws dark ink on sprite amber, and dark ink on a light fill is right — but
         * the object palette also holds text purple and video blue, and dark ink on 70% purple
         * over a dark scrim measures about 2:1. So it is CHOSEN by contrast rather than fixed:
         * the fill is composited over black (the scrim's hue) and whichever ink clears it by more
         * wins. The dark ink is ON_GO, the record's "ink on a saturated fill" (its lens uses the
         * same #050507 on cyan); a dedicated ON_FILL token would be the tidier name for it.</p>
         */
        public static int inkOn(int accent) {
            double a = ON_ALPHA / 255.0;
            double lum = 0.2126 * lin(((accent >> 16) & 0xFF) * a)
                    + 0.7152 * lin(((accent >> 8) & 0xFF) * a)
                    + 0.0722 * lin((accent & 0xFF) * a);
            double vsDark = (lum + 0.05) / (0.0015 + 0.05);
            double vsLight = (0.89 + 0.05) / (lum + 0.05);
            return vsDark >= vsLight ? Studio.ON_GO : Studio.DRAWER_INK;
        }

        private static double lin(double c255) {
            double c = c255 / 255.0;
            return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }

        static int dp(@NonNull Context ctx, float v) {
            return Math.round(v * ctx.getResources().getDisplayMetrics().density);
        }

        /** A fully-rounded pill: {@code fill}, plus a 1dp {@code ring} unless ring is 0. */
        @NonNull
        public static GradientDrawable pill(@NonNull Context ctx, int fill, int ring) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(fill);
            g.setCornerRadius(999f * ctx.getResources().getDisplayMetrics().density);
            if (ring != 0) g.setStroke(Math.max(1, dp(ctx, 1)), ring);
            return g;
        }

        /**
         * Set a background WITHOUT letting it rewrite the view's padding. An InsetDrawable
         * reports its insets as padding, and View.setBackground adopts a drawable's padding —
         * so every inset pill here would otherwise silently throw away the padding the
         * control was built with.
         */
        public static void background(@NonNull View v,
                                      @Nullable android.graphics.drawable.Drawable d) {
            int l = v.getPaddingLeft(), t = v.getPaddingTop();
            int r = v.getPaddingRight(), b = v.getPaddingBottom();
            v.setBackground(d);
            v.setPadding(l, t, r, b);
        }

        /** A {@code .dt i} tab face: radius 7, inset so a 32dp-tall view shows a 24dp pill. */
        @NonNull
        static android.graphics.drawable.Drawable tabBackground(@NonNull Context ctx, int fill) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(fill);
            g.setCornerRadius(dp(ctx, 7));
            return new android.graphics.drawable.InsetDrawable(g, 0, dp(ctx, 4), 0, dp(ctx, 4));
        }

        /**
         * {@code .dchip}: 11sp weight 600 in {@code --ddim}, padding 7 × 13, fully round, drawer
         * control fill with a 1dp inset ring.
         *
         * <p>The pill is drawn 29dp tall but the VIEW is at least 40: the background is inset,
         * so the finger gets the norm (Studio Final §04: "almost everything 40 or 44") while the
         * eye gets the record. Growing the glyph to reach 40 was the thing not to do.</p>
         *
         * <p>Returns a chip with no click listener and no right margin; the caller wires it and
         * places it (see {@link #chipLp}).</p>
         */
        @NonNull
        public static TextView chip(@NonNull Context ctx, @NonNull CharSequence text) {
            TextView t = new TextView(ctx);
            t.setText(text);
            t.setTextSize(11);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(ctx, 40));
            t.setPadding(dp(ctx, 13), dp(ctx, 12), dp(ctx, 13), dp(ctx, 12));
            setChipOn(t, false, sAccent);
            return t;
        }

        /** {@link #chip} layout params: wrap, with the record's 6dp gap to the next control. */
        @NonNull
        public static LayoutParams chipLp(@NonNull Context ctx) {
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(ctx, 6);
            return lp;
        }

        /** Light a chip with the current drawer's accent. */
        public static void setChipOn(@NonNull TextView chip, boolean on) {
            setChipOn(chip, on, sAccent);
        }

        /**
         * {@code .dchip.on}: filled with the object's accent at 70%, ring dropped, weight 700,
         * ink chosen by {@link #inkOn}. Off: control fill, ring, weight 600, {@code --ddim}.
         */
        public static void setChipOn(@NonNull TextView chip, boolean on, int accent) {
            Context ctx = chip.getContext();
            GradientDrawable g = on ? pill(ctx, onFill(accent), 0) : pill(ctx, CTL, RING);
            background(chip, new android.graphics.drawable.InsetDrawable(
                    g, 0, dp(ctx, 5), 0, dp(ctx, 5)));
            chip.setTextColor(on ? inkOn(accent) : Studio.DRAWER_DIM);
            Type.body(chip, on ? Type.BOLD : Type.SEMIBOLD);
            chip.setSelected(on);
        }

        /**
         * The same ON/OFF face for a square icon control ({@code ImageView}): fill and glyph
         * tint follow {@link #setChipOn}; the corner is 8dp rather than a full round, because a
         * row of icon squares reads as a segmented set and a row of circles reads as dots.
         */
        public static void setIconOn(@NonNull ImageView v, boolean on) {
            Context ctx = v.getContext();
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(ctx, 8));
            g.setColor(on ? onFill(sAccent) : CTL);
            if (!on) g.setStroke(Math.max(1, dp(ctx, 1)), RING);
            v.setBackground(g);
            v.setColorFilter(on ? inkOn(sAccent) : Studio.DRAWER_DIM);
            v.setSelected(on);
        }

        /**
         * {@code .dsec}: mono 8sp, letter-spacing .14em, UPPERCASE, {@code --dlabel}. 8sp is the
         * record's number and is legible only because {@code --dlabel} is #C4C4CE (4.86:1) —
         * the #A6A6B2 it replaced failed at 3.49:1 (record 06, MAJOR 04).
         */
        @NonNull
        public static TextView sectionLabel(@NonNull Context ctx, @NonNull CharSequence text) {
            TextView t = new TextView(ctx);
            t.setText(text);
            t.setTextSize(8);
            t.setLetterSpacing(0.14f);
            t.setTextColor(Studio.DRAWER_LABEL);
            Type.mono(t, Type.REGULAR);
            t.setSingleLine(true);
            t.setAllCaps(true);   // after setSingleLine: both are TransformationMethods, last one wins
            t.setPadding(0, dp(ctx, 7), 0, dp(ctx, 3));
            return t;
        }

        /** {@code .dr}: a horizontal row, centred, at least 46dp tall. */
        @NonNull
        public static LinearLayout row(@NonNull Context ctx) {
            LinearLayout l = new LinearLayout(ctx);
            l.setOrientation(HORIZONTAL);
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.setMinimumHeight(dp(ctx, 46));
            return l;
        }

        /**
         * A row's leading label: 11sp {@code --dlabel}, one line, ellipsized, fixed width so the
         * controls after it line up down the column. One line always — a label that wraps grows
         * its row, and a two-line tap row is its own defect (record 06, MINOR 09).
         */
        @NonNull
        public static TextView rowLabel(@NonNull Context ctx, @NonNull CharSequence text,
                                        int widthDp) {
            TextView t = new TextView(ctx);
            t.setText(text);
            t.setTextColor(Studio.DRAWER_LABEL);
            t.setTextSize(11);
            t.setWidth(dp(ctx, widthDp));
            t.setMaxLines(1);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            return t;
        }

        /**
         * A row's value readout: drawer ink, TABULAR figures, right-aligned in a fixed width so
         * a changing number does not shove the slider.
         *
         * <p>The record's {@code .dscrub b} is Plex Mono 600. It is deliberately NOT applied
         * here yet: these columns (40 and 46dp) were measured against the system face, and a
         * wider face turns "100.0%" into a two-line row. Tabular figures give the part of mono
         * that matters (digits that do not jitter as they change). Moving to mono means
         * re-measuring each column on the device first.</p>
         */
        @NonNull
        public static TextView value(@NonNull Context ctx, int widthDp) {
            TextView t = new TextView(ctx);
            t.setTextColor(Studio.DRAWER_INK);
            t.setTextSize(11);
            t.setFontFeatureSettings("tnum");
            t.setWidth(dp(ctx, widthDp));
            t.setGravity(Gravity.END);
            return t;
        }

        /** A quiet one-line-or-more note under or over a tab: 10sp {@code --dlabel}. */
        @NonNull
        public static TextView note(@NonNull Context ctx, @NonNull CharSequence text) {
            TextView t = new TextView(ctx);
            t.setText(text);
            t.setTextColor(Studio.DRAWER_LABEL);
            t.setTextSize(10);
            return t;
        }

        /**
         * {@code .dsl}: track 4dp at {@link #TRACK}, fill the object's accent at 70%, thumb 15dp
         * in drawer ink. Geometry is set by layer gravity (API 23+; minSdk is 24), so the track
         * is 4dp whatever height the bar is laid out at — the touch height is untouched.
         */
        public static void styleSlider(@NonNull android.widget.SeekBar bar) {
            Context ctx = bar.getContext();
            int h = dp(ctx, 4);
            GradientDrawable track = new GradientDrawable();
            track.setColor(TRACK);
            track.setCornerRadius(h);
            GradientDrawable fill = new GradientDrawable();
            fill.setColor(onFill(sAccent));
            fill.setCornerRadius(h);
            android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                    fill, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
            android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{track, clip});
            layers.setId(0, android.R.id.background);
            layers.setId(1, android.R.id.progress);
            for (int i = 0; i < 2; i++) {
                layers.setLayerHeight(i, h);
                layers.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
            }
            bar.setProgressDrawable(layers);
            GradientDrawable thumb = new GradientDrawable();
            thumb.setShape(GradientDrawable.OVAL);
            thumb.setColor(Studio.DRAWER_INK);
            thumb.setSize(dp(ctx, 15), dp(ctx, 15));
            bar.setThumb(thumb);
            bar.setSplitTrack(false);
        }

        /**
         * {@code .dcb}: an 18dp box ringed in {@code --dlabel}, filled with the accent when on;
         * label {@code --ddim} off and {@code --dink} on, 11.5sp. Only the colours change — the
         * checkbox, its listener and its state are the caller's.
         */
        public static void styleCheck(@NonNull android.widget.CompoundButton cb) {
            int[][] states = {{android.R.attr.state_checked}, {}};
            androidx.core.widget.CompoundButtonCompat.setButtonTintList(cb,
                    new android.content.res.ColorStateList(states,
                            new int[]{onFill(sAccent), Studio.DRAWER_LABEL}));
            cb.setTextColor(new android.content.res.ColorStateList(states,
                    new int[]{Studio.DRAWER_INK, Studio.DRAWER_DIM}));
            cb.setTextSize(11.5f);
        }

        /**
         * Press feedback: 140ms to scale .97, and back (record 06 §04). A state-list animator
         * rather than a touch listener, so it can never swallow or reorder the click.
         *
         * <p>Not for play, undo, split or keying — those are hundred-times-a-day controls, and
         * the record's rule is that they answer in 0ms.</p>
         */
        public static void pressable(@NonNull View v) {
            android.animation.StateListAnimator sla = new android.animation.StateListAnimator();
            sla.addState(new int[]{android.R.attr.state_pressed}, scaleTo(v, 0.97f));
            sla.addState(new int[0], scaleTo(v, 1f));
            v.setStateListAnimator(sla);
        }

        private static android.animation.Animator scaleTo(@NonNull View v, float s) {
            android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofPropertyValuesHolder(v,
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, s),
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, s));
            a.setDuration(PRESS_MS);
            a.setInterpolator(new DecelerateInterpolator());
            return a;
        }

        /**
         * Name a control: TalkBack's label AND the tooltip a stylus hover or a mouse shows.
         * Every tappable thing in a drawer goes through here (standing rule: every button gets
         * a hover label).
         */
        public static void describe(@NonNull View v, @NonNull CharSequence name) {
            v.setContentDescription(name);
            androidx.appcompat.widget.TooltipCompat.setTooltipText(v, name);
        }
    }
}
