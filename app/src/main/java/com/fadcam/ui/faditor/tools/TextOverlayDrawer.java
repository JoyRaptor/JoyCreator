package com.fadcam.ui.faditor.tools;

import com.fadcam.ui.faditor.Studio;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The text-overlay drawer — a TOP-anchored panel that drops DOWN from the top edge and pushes
 * the video below it, using the same mechanics as {@link ObjectDrawer} (spec, 2026-08-10:
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
 * <p>Generic on purpose, the same way {@code ObjectDrawer} is: this class knows about
 * sliding a panel down from the top, a title, a close button and a scrolling body capped at a
 * height. It does not know about fonts, colours or keyframes — the caller supplies the content
 * view, same as {@code PipDrawerTabs} supplies the PiP drawer's.</p>
 */
public final class TextOverlayDrawer extends LinearLayout {

    /**
     * The SAME scrim {@code ObjectDrawer} uses, not an opaque panel.
     *
     * <p>This drawer sits over the preview, and every control in it — colours, outline, glow,
     * shadow, background — changes what the preview shows. Behind an opaque panel the user is
     * editing something they cannot see and has to close the drawer to judge each change. JoyRaptor
     * asked for it to match the image/video drawer for exactly that reason (2026-08-12), and the
     * PiP drawer had already made the trade. The text on top carries a shadow layer, which is what
     * keeps it legible over an arbitrary frame.</p>
     *
     * <p><b>THE SCRIM WAS 40%, AND THAT WAS HALF OF THE "INVISIBLE TOGGLES" REPORT.</b>
     * This read alpha 0x66 long after ObjectDrawer had been measured and moved to 64%. Record 06
     * CRITICAL 01 is exactly this case: "blur softens but never darkens", so a 40% tint over a
     * white frame leaves the drawer nearly as bright as the frame. Now the same value as
     * ObjectDrawer.SCRIM and record 06's --scrim.</p>
     *
     * <p>The OTHER half is not in this file. The Tt / TT / ᴛᴛ chips and the "None" motion label
     * are built by the activity in the screen ramp's faintest grey (#71717A); over a white frame
     * that is about 1.7:1 at 40% and still only about 1.4:1 at 64%. Only drawer ink fixes those,
     * and that change belongs to the activity's styleToggleState / buildTextTopRow.</p>
     */
    private static final int BG = Kit.SCRIM;
    // Over the frosted scrim, so this is the DRAWER ramp, not the screen ramp.
    private static final int TXT = Studio.DRAWER_INK;
    private static final int TXT_DIM = Studio.DRAWER_DIM;
    private static final int TXT_LABEL = Studio.DRAWER_LABEL;
    /**
     * Record 06 §04: "Drawer 320ms drawer curve cubic-bezier(.32,.72,0,1). The only thing allowed
     * past 300ms." It was 220ms on a plain decelerate, so the text drawer arrived faster and on a
     * different curve than every other drawer in the spec.
     */
    private static final int SLIDE_MS = 320;
    private static final float MAX_HEIGHT_FRACTION = 0.62f;
    private static final int MIN_BODY_DP = 120;
    private static final float ABSOLUTE_MAX_FRACTION = 0.85f;

    private final float density;
    /** Record 06 {@code .dh .dot}: the object's colour, the header's non-word "what is this". */
    private final View dotView;
    /** The verb ("Edit text") as a quiet mono kicker — never the loudest thing in the header. */
    private final TextView kickerView;
    /** The OBJECT's name — its first words — in the header's one bold slot ({@code .dh .nm}). */
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
        // is at the bottom, mirroring ObjectDrawer. 18, not 16: record 06 `.drawer`
        // border-radius 0 0 18px 18px, and the same radius ObjectDrawer draws.
        float r = 18f * density;
        bg.setCornerRadii(new float[]{0f, 0f, 0f, 0f, r, r, r, r});
        setBackground(bg);
        setClickable(true);
        setElevation(8f * density);
        // A clickable tap-blocker with no name is announced as one giant unlabelled button before
        // anything inside it. NO (not NO_HIDE_DESCENDANTS) drops the container, keeps every child.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        // ── HEADER — record 06 `.dh`: dot · name · (accessory) · ✕, one line, 38dp ─────────
        // It used to be ONE all-caps bold run — "EDIT TEXT · ENTER TEXT" — butted against the
        // font name, so the verb, the object and the font all shouted at the same volume and the
        // eye had nowhere to land. Now the verb is a mono kicker in label ink, the object's own
        // words take the single bold slot in sentence case, and a dot in the text colour says
        // WHAT this is without spending a word on it.
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(38));
        header.setPadding(dp(12), 0, dp(4), 0);

        dotView = new View(ctx);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        // --o-text in record 06 is #8c3dfa, which is this token's value and its stated role
        // ("the object table's TEXT hue"). setAccent() lets a host re-tint it.
        dot.setColor(Studio.ROOM_AVATAR_DEEP);
        dotView.setBackground(dot);
        LayoutParams dotLp = new LayoutParams(dp(7), dp(7));
        dotLp.setMarginEnd(dp(8));
        header.addView(dotView, dotLp);

        kickerView = new TextView(ctx);
        com.fadcam.ui.type.Type.mono(kickerView, com.fadcam.ui.type.Type.MEDIUM);
        kickerView.setTextColor(TXT_LABEL);
        kickerView.setTextSize(8.5f);
        kickerView.setLetterSpacing(0.14f);
        kickerView.setAllCaps(true);
        kickerView.setSingleLine(true);
        kickerView.setShadowLayer(3f * density, 0f, 1f, Studio.alpha(Studio.GROUND, 0xCC));
        kickerView.setVisibility(GONE);
        LayoutParams kickLp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT);
        kickLp.setMarginEnd(dp(8));
        header.addView(kickerView, kickLp);

        titleView = new TextView(ctx);
        titleView.setTextColor(TXT);
        titleView.setTextSize(12);
        com.fadcam.ui.type.Type.body(titleView, com.fadcam.ui.type.Type.BOLD);
        // ONE line, always. A header that wraps pushes the peek rows below the fold, which is
        // the obstruction ObjectDrawer's header was fixed for (2026-09-16). Capped rather than
        // weighted so the font accessory keeps its slot beside it.
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setMaxWidth(dp(150));
        // The panel is a scrim now, so anything on it has arbitrary picture behind it. Same shadow
        // the PiP drawer's title carries, for the same reason.
        titleView.setShadowLayer(4f * density, 0f, 1f, Studio.alpha(Studio.GROUND, 0xCC));
        header.addView(titleView, new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT));

        // An accessory slot BETWEEN the title and the close button. The font control lives here
        // rather than in the button row below (JoyRaptor, 2026-08-12): the header had spare width, the
        // button row did not, and a font is the one property worth naming rather than abbreviating
        // to "Aa" — so it goes where there is room to print it. Weighted, so it takes the slack and
        // its own ellipsis handles a long font name instead of pushing ✕ off the edge.
        headerAccessory = new FrameLayout(ctx);
        LayoutParams accLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        accLp.setMarginStart(dp(6));
        header.addView(headerAccessory, accLp);

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextColor(TXT_DIM);
        close.setTextSize(15);
        close.setGravity(Gravity.CENTER);
        // 40dp: Studio Final §04 puts the norm at "almost everything 40 or 44", and a drawer's
        // only dismiss button belongs at the norm. Same box ObjectDrawer's ✕ has.
        close.setMinWidth(dp(40));
        close.setMinHeight(dp(40));
        close.setShadowLayer(4f * density, 0f, 1f, Studio.alpha(Studio.GROUND, 0xCC));
        Kit.describe(close, ctx.getString(com.fadcam.R.string.universal_close));
        close.setOnClickListener(v -> hide());
        header.addView(close);
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        contentHost = new FrameLayout(ctx);
        addView(contentHost, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Pull-UP dismiss grip at the BOTTOM edge — the drawer hangs from the top, so the
        // direction that puts it away is up. Mirrors ObjectDrawer.wireGrip.
        //
        // VERTICAL now: the MORE label sits ABOVE the pill, centred on the same axis, instead of
        // beside it. Side by side, the label shoved the pill off-centre and the two read as one
        // smudge — "MORE ⌄ floats in purple and collides with the grab pill."
        LinearLayout grip = new LinearLayout(ctx);
        grip.setOrientation(VERTICAL);
        grip.setGravity(Gravity.CENTER_HORIZONTAL);
        grip.setPadding(0, dp(2), 0, dp(6));
        // "MORE ⌄" — the drawer opens showing only its first rows now, so it has to SAY that the
        // rest exists. Sitting on the grip is deliberate: the grip is the thing that makes the
        // drawer taller, so the label naming the reward and the control that delivers it are the
        // same target. Hidden whenever the body is not actually scrollable, so it never promises
        // content that is already on screen.
        //
        // In the drawer's LABEL ink and the section-label voice (mono, tracked, caps) rather than
        // the guide purple: purple is the snap-line colour, and a hint is not a guide.
        moreHint = Kit.sectionLabel(ctx, ctx.getString(com.fadcam.R.string.faditor_lc_drawer_more));
        moreHint.setPadding(0, 0, 0, dp(3));
        moreHint.setVisibility(GONE);
        grip.addView(moreHint, new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        View pill = new View(ctx);
        GradientDrawable pillBg = new GradientDrawable();
        // Record 06 `.dgrab span`: 38 × 3.5, fully round, white at 30%.
        pillBg.setColor(Kit.GRAB);
        pillBg.setCornerRadius(2f * density);
        pill.setBackground(pillBg);
        grip.addView(pill, new LayoutParams(dp(38), dp(4)));
        grip.setMinimumHeight(dp(13));
        Kit.describe(grip, ctx.getString(com.fadcam.R.string.faditor_lc_drawer_grip));
        wireGrip(grip);
        addView(grip, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    @Nullable private FrameLayout headerAccessory;

    /**
     * The header text. Callers hand over one string — "Edit text · the first words" — and the
     * header splits it at the first {@code " · "}: the part before is the verb (the kicker), the
     * part after is the object's name. Without a separator the whole string is the name and the
     * kicker hides, so a caller that never learned about the split still gets a sane header.
     */
    public void setTitle(@NonNull String title) {
        final String sep = " · ";
        int at = title.indexOf(sep);
        if (at > 0 && at + sep.length() < title.length()) {
            kickerView.setText(title.substring(0, at));
            kickerView.setVisibility(VISIBLE);
            titleView.setText(title.substring(at + sep.length()));
        } else {
            kickerView.setVisibility(GONE);
            titleView.setText(title);
        }
        // Read as one phrase, as it was written, rather than as two fragments.
        titleView.setContentDescription(title);
    }

    /** Tint the header dot to the object being edited. Defaults to the text hue. */
    public void setAccent(int colour) {
        if (dotView.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) dotView.getBackground()).setColor(colour);
        }
    }

    /**
     * Put {@code v} (or nothing, with null) in the header between the title and ✕.
     *
     * <p>Replaces whatever was there: the accessory belongs to the object being edited, and a
     * second overlay's control stacked on the first one's would show two font names at once.</p>
     */
    public void setHeaderAccessory(@Nullable View v) {
        if (headerAccessory == null) return;
        headerAccessory.removeAllViews();
        if (v != null) {
            headerAccessory.addView(v, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));
        }
    }

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
                    .setDuration(SLIDE_MS).setInterpolator(Kit.drawerCurve()).start();
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
                .setInterpolator(Kit.drawerCurve())
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

    /**
     * How many of the content's top-level rows the drawer opens showing. 0 = the old behaviour,
     * which was {@link #MAX_HEIGHT_FRACTION} of the SCREEN.
     *
     * <p><b>Screen real estate is the scarce thing here</b> (JoyRaptor, 2026-08-12): "don't want to
     * annoy user with huge drawer they have to resize smaller 90% of the time. better that they
     * only have to expand 10% of the time." The rows that get touched constantly — the font and
     * style toolbar, the trim chips — are at the top; outline, glow and shadow are set once and
     * left alone, so they are worth a scroll.</p>
     *
     * <p>A ROW COUNT rather than a dp height on purpose. A height would be a magic number that
     * silently stops framing the right rows the moment one of them changes, or the user's font
     * scale does. Counting rows keeps the intent — "the first two" — true by construction, and
     * keeps this class ignorant of what those rows contain.</p>
     */
    private int peekRows;

    /** @see #peekRows */
    public void setPeekRows(int rows) {
        if (peekRows == rows) return;
        peekRows = rows;
        if (bodyScroll != null) bodyScroll.requestLayout();
    }

    /**
     * Body height that shows exactly {@link #peekRows} rows, or -1 when that cannot be determined
     * (no peek set, content is not a row container, or the count covers everything anyway).
     */
    private int peekBodyHeightPx(@NonNull ScrollView sv, int widthSpec) {
        if (peekRows <= 0) return -1;
        View content = sv.getChildCount() > 0 ? sv.getChildAt(0) : null;
        if (!(content instanceof ViewGroup)) return -1;
        ViewGroup rows = (ViewGroup) content;
        if (rows.getChildCount() <= peekRows) return -1;   // nothing would be hidden
        // Top padding only. The BOTTOM padding belongs to the end of the whole stack, hundreds of
        // dp below, and adding it here bought a band of dead space under the second row —
        // "an awkward amount of negative space after the first two rows" (JoyRaptor, 2026-08-12).
        int sum = rows.getPaddingTop();
        for (int i = 0; i < peekRows; i++) {
            View row = rows.getChildAt(i);
            if (row.getVisibility() == GONE) continue;
            int h = row.getMeasuredHeight();
            if (h <= 0) return -1;      // not measured yet — caller falls back for this pass
            ViewGroup.LayoutParams lp = row.getLayoutParams();
            if (lp instanceof MarginLayoutParams) {
                h += ((MarginLayoutParams) lp).topMargin + ((MarginLayoutParams) lp).bottomMargin;
            }
            sum += h;
        }
        // A CLEAN cut at the row boundary. The 10dp sliver this used to add showed the top few
        // pixels of the STYLE header, which read as a rendering fault rather than as a hint —
        // MORE ⌄ on the grip already says there is more, and says it in words (JoyRaptor, 2026-08-12).
        return sum;
    }

    @NonNull
    private View wrap(@NonNull View content) {
        ScrollView sv = new ScrollView(getContext()) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int cap = maxBodyHeightPx();
                int mode = userHeightPx > 0 ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST;
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, mode));
                if (userHeightPx > 0) return;
                // The rows are measured now, so their heights can be added up. Re-measuring to the
                // peek height is a second pass over content that is already laid out cheaply, and
                // only while the user has not set a height of their own.
                int peek = peekBodyHeightPx(this, widthSpec);
                if (peek > 0 && peek < getMeasuredHeight()) {
                    super.onMeasure(widthSpec,
                            MeasureSpec.makeMeasureSpec(peek, MeasureSpec.EXACTLY));
                }
                post(TextOverlayDrawer.this::syncMoreHint);
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

    @Nullable private TextView moreHint;

    /**
     * Show the MORE label only while there is something below the fold. Called after every
     * measure, because what is hidden changes with the peek height, a manual resize, and any
     * content rebuild.
     */
    private void syncMoreHint() {
        if (moreHint == null) return;
        boolean scrollable = false;
        ScrollView sv = bodyScroll;
        if (sv != null && sv.getChildCount() > 0) {
            scrollable = sv.getChildAt(0).getHeight() > sv.getHeight() + 1;
        }
        moreHint.setVisibility(scrollable ? VISIBLE : GONE);
    }

    /**
     * Grow the body to its full extent — the tap target behind MORE. Goes to the fraction cap
     * rather than to the content's real height so a very long stack still cannot swallow the
     * preview and the timeline, which is the whole reason the cap exists.
     */
    private void expandToFull() {
        int screen = getResources().getDisplayMetrics().heightPixels;
        userHeightPx = Math.round(screen * MAX_HEIGHT_FRACTION);
        if (bodyScroll != null) bodyScroll.requestLayout();
        post(this::reportHeight);
        post(this::syncMoreHint);
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
                        boolean hasMore = moreHint != null
                                && moreHint.getVisibility() == VISIBLE;
                        if (!moved && hasMore) {
                            // A TAP means "show me the rest" while anything is below the fold. The
                            // grip is where MORE is written, so this is the label's own target; ✕
                            // in the header is the way out, and it always was. Without this the
                            // only route to the hidden rows would be a drag, on a drawer that now
                            // deliberately opens short.
                            expandToFull();
                        } else if (!moved || (dy < -slop * 2 && atFloor)) {
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

    /**
     * THE DRAWER KIT — record 06's drawer controls, built in ONE place.
     *
     * <p>JoyRaptor: "things calling helpers instead of hard coded so there are fewer areas to
     * break." Before this, the effects panel, the two pickers and the object sheet each built
     * their own chip, their own section heading and their own slider, at four sizes, three radii
     * and five greys — so a correction to one never reached the others. Every value below is
     * record 06 (UI-Studio-final.html) §02/§04, cited at the line that uses it.</p>
     *
     * <p>It lives in this file only because this lane may not add Java files; it is a pure static
     * toolbox with no tie to TextOverlayDrawer and can move to its own {@code DrawerKit.java}
     * unchanged. {@code ObjectDrawer}, {@code PipDrawerTabs}, {@code AudioDrawerTabs} and
     * {@code PuppetDrawerTabs} are the obvious next callers.</p>
     *
     * <p>No colour here is new. Each is an existing {@link Studio} token, with record 06's alpha
     * applied through {@link Studio#alpha} — {@code rgba(255,255,255,.10)} is the drawer's own
     * ink at 10%, because the drawer ink IS this app's white.</p>
     */
    public static final class Kit {

        private Kit() { }

        /** {@code --scrim} rgba(0,0,0,.64). 6.63:1 body ink over a blown-out frame. */
        public static final int SCRIM = Studio.alpha(Studio.GROUND, 0xA3);
        /** {@code --dctl} rgba(255,255,255,.10): a control's fill on the scrim. */
        public static final int CTL   = Studio.alpha(Studio.DRAWER_INK, 0x1A);
        /** {@code --dring} rgba(255,255,255,.12): the 1dp ring an unselected control wears. */
        public static final int RING  = Studio.alpha(Studio.DRAWER_INK, 0x1F);
        /** {@code --edge} rgba(255,255,255,.10): the only border a drawer surface has. */
        public static final int EDGE  = Studio.alpha(Studio.DRAWER_INK, 0x1A);
        /** {@code .dsl .tr} rgba(255,255,255,.18): an empty slider track. */
        public static final int TRACK = Studio.alpha(Studio.DRAWER_INK, 0x2E);
        /** {@code .dgrab span} rgba(255,255,255,.30): the grab pill. */
        public static final int GRAB  = Studio.alpha(Studio.DRAWER_INK, 0x4D);
        /** "Any press 140ms ease-out, scale(.97)". */
        public static final long PRESS_MS = 140L;
        /** Popovers "from scale(.95) never scale(0), 220ms ease-out". */
        public static final long POP_MS = 220L;

        /** {@code --ease-out} cubic-bezier(.23,1,.32,1). Never ease-in. */
        @NonNull
        public static android.view.animation.Interpolator easeOut() {
            return new android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f);
        }

        /** The drawer curve, cubic-bezier(.32,.72,0,1) — record 06 §04. */
        @NonNull
        public static android.view.animation.Interpolator drawerCurve() {
            return new android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f);
        }

        private static int px(@NonNull Context c, float dp) {
            return Math.round(dp * c.getResources().getDisplayMetrics().density);
        }

        /**
         * Name a control for TalkBack AND for the hover tooltip a stylus or mouse shows — the
         * standing rule is that every button earns a hover label, and a glyph is not one.
         *
         * <p>Never on a view whose LONG PRESS is its own gesture: below API 26 the compat tooltip
         * is delivered through a long-click listener and would replace that gesture. Such views
         * take {@code setContentDescription} alone.</p>
         */
        public static void describe(@NonNull View v, @NonNull CharSequence label) {
            v.setContentDescription(label);
            androidx.core.view.ViewCompat.setTooltipText(v, label);
        }

        /**
         * Press feedback: scale(.97) over 140ms, ease-out, on the pressed STATE — a
         * StateListAnimator, so it never competes with a view's own touch listener. Transform
         * only, which is the one property that animates without a relayout.
         */
        public static void pressable(@NonNull View v) {
            android.animation.StateListAnimator sla = new android.animation.StateListAnimator();
            sla.addState(new int[]{android.R.attr.state_pressed}, scaleTo(v, 0.97f));
            sla.addState(new int[0], scaleTo(v, 1f));
            v.setStateListAnimator(sla);
        }

        @NonNull
        private static android.animation.Animator scaleTo(@NonNull View v, float s) {
            android.animation.ObjectAnimator a =
                    android.animation.ObjectAnimator.ofPropertyValuesHolder(v,
                            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, s),
                            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, s));
            a.setDuration(PRESS_MS);
            a.setInterpolator(easeOut());
            return a;
        }

        /**
         * THE chip — record 06 {@code .dchip}: 11sp w600, padding 7 × 13, radius 999, the
         * control fill with a 1dp inset ring, drawer-dim ink. Single line with an ellipsis,
         * never two (a two-line tap target is its own defect — record 06 MINOR 09).
         */
        @NonNull
        public static TextView chip(@NonNull Context ctx, @NonNull CharSequence text) {
            TextView t = new TextView(ctx);
            t.setText(text);
            t.setTextSize(11f);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            int ph = px(ctx, 13), pv = px(ctx, 7);
            t.setPadding(ph, pv, ph, pv);
            setChipOn(t, false);
            pressable(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            // `.dr` gap: 6px.
            lp.setMarginEnd(px(ctx, 6));
            t.setLayoutParams(lp);
            return t;
        }

        /**
         * Selected / not, when the panel does not know the OBJECT's colour. "State colour always
         * a RING" (record 06 §04): cyan ring and full drawer ink when on; the control fill, its
         * faint ring and drawer-dim ink when off. Dim is #C9C9D3 — the OFF state stays readable,
         * which the old half-alpha grey never was.
         */
        public static void setChipOn(@NonNull TextView t, boolean on) {
            Context c = t.getContext();
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(c, 999));
            g.setColor(CTL);
            g.setStroke(on ? Math.max(1, px(c, 1.5f)) : Math.max(1, px(c, 1)),
                    on ? Studio.ARMED : RING);
            t.setBackground(g);
            t.setTextColor(on ? Studio.DRAWER_INK : Studio.DRAWER_DIM);
            com.fadcam.ui.type.Type.body(t, on ? com.fadcam.ui.type.Type.BOLD
                    : com.fadcam.ui.type.Type.SEMIBOLD);
            t.setSelected(on);
        }

        /**
         * The ONE primary action on a panel: the aqua-to-lime gradient, dark ink. "Gradient means
         * action" (Studio.java) and "one filled action per view" — so a panel calls this once.
         */
        public static void setPrimaryAction(@NonNull TextView t) {
            t.setBackgroundResource(com.fadcam.R.drawable.studio_action_pill);
            t.setTextColor(Studio.ON_GO);
            com.fadcam.ui.type.Type.body(t, com.fadcam.ui.type.Type.BOLD);
        }

        /**
         * A section label — record 06 {@code .dsec}: mono 8sp, tracked .14em, upper case, in
         * drawer-LABEL ink (#C4C4CE; the #A6A6B2 it replaced measured 3.49:1 — MAJOR 04).
         */
        @NonNull
        public static TextView sectionLabel(@NonNull Context ctx, @NonNull CharSequence text) {
            TextView t = new TextView(ctx);
            t.setText(text);
            com.fadcam.ui.type.Type.mono(t, com.fadcam.ui.type.Type.MEDIUM);
            t.setTextSize(8f);
            t.setLetterSpacing(0.14f);
            t.setAllCaps(true);
            t.setSingleLine(true);
            t.setTextColor(Studio.DRAWER_LABEL);
            t.setPadding(0, px(ctx, 7), 0, px(ctx, 3));
            return t;
        }

        /**
         * A live numeric readout — record 06 {@code .dscrub b}: mono 12sp w600, drawer ink,
         * tabular figures, so a value changing under a drag does not jitter sideways.
         */
        @NonNull
        public static TextView valueText(@NonNull Context ctx) {
            TextView t = new TextView(ctx);
            com.fadcam.ui.type.Type.mono(t, com.fadcam.ui.type.Type.SEMIBOLD);
            t.setTextSize(12f);
            t.setTextColor(Studio.DRAWER_INK);
            t.setFontFeatureSettings("tnum");
            t.setSingleLine(true);
            return t;
        }

        /**
         * THE slider look — record 06 {@code .dsl}: a 4dp track at white 18%, the filled part in
         * {@code fill}, a 15dp white thumb. Look only: the bar's range, listener and value are
         * the caller's and are not touched.
         */
        public static void styleSlider(@NonNull SeekBar bar, int fill) {
            Context c = bar.getContext();
            int h = Math.max(1, px(c, 4));
            GradientDrawable track = new GradientDrawable();
            track.setColor(TRACK);
            track.setCornerRadius(h / 2f);
            GradientDrawable on = new GradientDrawable();
            on.setColor(fill);
            on.setCornerRadius(h / 2f);
            android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                    on, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
            android.graphics.drawable.LayerDrawable ld =
                    new android.graphics.drawable.LayerDrawable(
                            new android.graphics.drawable.Drawable[]{track, clip});
            ld.setId(0, android.R.id.background);
            ld.setId(1, android.R.id.progress);
            for (int i = 0; i < 2; i++) {
                ld.setLayerHeight(i, h);
                ld.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
            }
            int progress = bar.getProgress();
            bar.setProgressDrawable(ld);
            GradientDrawable thumb = new GradientDrawable();
            thumb.setShape(GradientDrawable.OVAL);
            thumb.setColor(Studio.DRAWER_INK);
            int t = px(c, 15);
            thumb.setSize(t, t);
            bar.setThumb(thumb);
            bar.setSplitTrack(false);
            // setProgressDrawable can drop the level on some API levels; put it back.
            bar.setProgress(progress);
        }

        /**
         * A popover's surface: the drawer scrim, rounded, with the 1dp edge. Popovers float over
         * the canvas, so they are "over video" exactly as a drawer is, and take the same dark
         * glass rather than an opaque grey slab.
         */
        @NonNull
        public static GradientDrawable surface(@NonNull Context ctx, float radiusDp) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(SCRIM);
            g.setCornerRadius(px(ctx, radiusDp));
            g.setStroke(Math.max(1, px(ctx, 1)), EDGE);
            return g;
        }

        /**
         * A popover arriving: from scale(.95) and transparent to rest, 220ms ease-out — never
         * from scale(0), which reads as the thing being born rather than revealed. Transform and
         * opacity only.
         */
        public static void popIn(@NonNull View v) {
            v.setScaleX(0.95f);
            v.setScaleY(0.95f);
            v.setAlpha(0f);
            v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(POP_MS).setInterpolator(easeOut()).start();
        }

        /**
         * A drawn 18dp checkbox — record 06 {@code .dcb .bx}: radius 5, a 1.5dp ring in
         * {@code ring} when off; filled with {@code fill} and a dark check when on. Replaces
         * ☑ / ☐ text glyphs, which rendered in whatever face the OEM had and at text size.
         */
        @NonNull
        public static android.graphics.drawable.Drawable checkbox(@NonNull Context ctx,
                                                                   boolean on, int fill,
                                                                   int ring) {
            int box = px(ctx, 18);
            GradientDrawable b = new GradientDrawable();
            b.setCornerRadius(px(ctx, 5));
            b.setSize(box, box);
            if (!on) {
                b.setColor(0x00000000);
                b.setStroke(Math.max(1, px(ctx, 1.5f)), ring);
                b.setBounds(0, 0, box, box);
                return b;
            }
            b.setColor(fill);
            android.graphics.drawable.Drawable check = androidx.core.content.ContextCompat
                    .getDrawable(ctx, com.fadcam.R.drawable.ic_check);
            if (check == null) { b.setBounds(0, 0, box, box); return b; }
            check = check.mutate();
            check.setTint(Studio.ON_GO);
            android.graphics.drawable.LayerDrawable ld =
                    new android.graphics.drawable.LayerDrawable(
                            new android.graphics.drawable.Drawable[]{b, check});
            int inner = px(ctx, 13);
            ld.setLayerSize(0, box, box);
            ld.setLayerSize(1, inner, inner);
            ld.setLayerGravity(1, Gravity.CENTER);
            ld.setBounds(0, 0, box, box);
            return ld;
        }
    }
}
