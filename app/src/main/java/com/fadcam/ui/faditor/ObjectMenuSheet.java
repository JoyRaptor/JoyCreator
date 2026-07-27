package com.fadcam.ui.faditor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.keyframe.Easing;

import java.util.ArrayList;
import java.util.List;

/**
 * G2 (gesture contract §2/§3): the object's GENERAL ADVANCED MENU as a
 * NON-MODAL peek/expand bottom sheet — "the drawer that covers something is
 * always the one you're NOT looking through".
 *
 * <ul>
 *   <li><b>Peek (default):</b> grip + ONE row (the active property's slider +
 *       its keyframe diamond). Preview AND timeline both stay visible, and the
 *       timeline stays live (this is a view in the activity tree, not a modal
 *       dialog), so the user can scrub while nudging a value — the everyday
 *       keyframing state.</li>
 *   <li><b>Expanded (drag the grip up / tap it):</b> identity header + every
 *       general property row + object actions + "More…" (the same full type
 *       editor double-tap opens). Covers the timeline — fine for bulk setup.</li>
 * </ul>
 *
 * <p>The sheet is payload-agnostic: the activity supplies {@link Prop} adapters
 * (keyframe-aware getters/setters at the playhead) and {@link Action}s, so the
 * same chrome serves text/image overlays now and sprites/audio/PiP as their
 * §2 sections come online. Diamond states here are the G2 basics (solid = on a
 * key of this property, hollow = tap to drop one); swipe-to-navigate and
 * long-press-delete on the diamond are G3.</p>
 */
public final class ObjectMenuSheet extends LinearLayout {

    /** Undo bracketing for one slider gesture (activity snapshots + records). */
    public interface GestureHooks {
        void onSliderStart();
        void onSliderCommit(@NonNull String what);
    }

    public interface Getter { float at(long playheadMs); }
    public interface Setter { void write(float value, long playheadMs); }
    public interface OnKeyQuery { boolean onKeyAt(long playheadMs); }
    public interface ValueFormat { @NonNull String format(float value); }
    /** C7: is this property armed (has any keyframe)? Drives the "static" hint. */
    public interface ArmedQuery { boolean armed(); }
    /** D2a: easing of the segment the playhead is IN (null = no editable segment). */
    public interface EaseGet { @Nullable Easing segmentEasing(long playheadMs); }
    public interface EaseSet { void setSegmentEasing(@NonNull Easing e, long playheadMs); }

    /**
     * One general-menu property row (contract §2's diamond rows).
     *
     * <p>§2 Prop adapters come in two kinds: KEYFRAMEABLE (transform/opacity/
     * volume — the everyday case, with a slider + a keyframe diamond + ease
     * picker) and STATIC ({@link #staticProp}) — a plain slider with NO diamond,
     * for properties the model cannot animate (visualizer placement, audio has
     * only its own envelope). Static props drive the same slider chrome but skip
     * the diamond/arming-hint/ribbon so nothing promises "tap ♦" where no key
     * can exist.</p>
     */
    public static final class Prop {
        final String key;          // KeyframeSet property key, unique per sheet
        final String label;
        final float min, max;      // slider value space
        final ValueFormat format;
        final Getter get;          // animated value at the playhead
        final Setter set;          // keyframe-aware write at the playhead
        final OnKeyQuery onKey;    // playhead sits on a key of this property?
        final Runnable dropKey;    // hollow-diamond tap → drop a key here
        /** §2: false = STATIC (no keyframe diamond / arming hint / ribbon). */
        final boolean keyframeable;
        // G3: diamond swipe = jump playhead to prev/next key of this property;
        // diamond tap on-key = delete the key under the playhead. Null = no-op.
        @Nullable final Runnable prevKey, nextKey, deleteKey;
        // C7 arming honesty + D2a ease picker (nullable so pre-§2 adapters compile).
        @Nullable final ArmedQuery armed;
        @Nullable final EaseGet easeGet;
        @Nullable final EaseSet easeSet;

        public Prop(@NonNull String key, @NonNull String label, float min, float max,
                    @NonNull ValueFormat format, @NonNull Getter get, @NonNull Setter set,
                    @NonNull OnKeyQuery onKey, @NonNull Runnable dropKey,
                    @Nullable Runnable prevKey, @Nullable Runnable nextKey,
                    @Nullable Runnable deleteKey,
                    @Nullable ArmedQuery armed,
                    @Nullable EaseGet easeGet, @Nullable EaseSet easeSet) {
            this(key, label, min, max, format, get, set, onKey, dropKey,
                    prevKey, nextKey, deleteKey, armed, easeGet, easeSet, true);
        }

        private Prop(@NonNull String key, @NonNull String label, float min, float max,
                     @NonNull ValueFormat format, @NonNull Getter get, @NonNull Setter set,
                     @NonNull OnKeyQuery onKey, @NonNull Runnable dropKey,
                     @Nullable Runnable prevKey, @Nullable Runnable nextKey,
                     @Nullable Runnable deleteKey,
                     @Nullable ArmedQuery armed,
                     @Nullable EaseGet easeGet, @Nullable EaseSet easeSet,
                     boolean keyframeable) {
            this.key = key;
            this.label = label;
            this.min = min;
            this.max = max;
            this.format = format;
            this.get = get;
            this.set = set;
            this.onKey = onKey;
            this.dropKey = dropKey;
            this.prevKey = prevKey;
            this.nextKey = nextKey;
            this.deleteKey = deleteKey;
            this.armed = armed;
            this.easeGet = easeGet;
            this.easeSet = easeSet;
            this.keyframeable = keyframeable;
        }

        /**
         * §2 STATIC property: a plain slider with no keyframe machinery — for
         * models with no {@code KeyframeSet} (visualizer transform, audio pan-less
         * placement). The diamond is dropped, {@link #onKeyAt} is always false and
         * {@link #dropKey} is a no-op, and the row shows no arming hint / ribbon.
         */
        @NonNull
        public static Prop staticProp(@NonNull String key, @NonNull String label,
                                      float min, float max, @NonNull ValueFormat format,
                                      @NonNull Getter get, @NonNull Setter set) {
            return new Prop(key, label, min, max, format, get, set,
                    ms -> false, () -> {}, null, null, null, null, null, null, false);
        }

        public boolean onKeyAt(long playheadMs) { return onKey.onKeyAt(playheadMs); }
        @NonNull public String label() { return label; }
        public void dropKey() { dropKey.run(); }
        public void prevKey() { if (prevKey != null) prevKey.run(); }
        public void nextKey() { if (nextKey != null) nextKey.run(); }
        public void deleteKey() { if (deleteKey != null) deleteKey.run(); }
        public boolean armed() { return armed != null && armed.armed(); }
        @Nullable public Easing segmentEasing(long playheadMs) {
            return easeGet == null ? null : easeGet.segmentEasing(playheadMs);
        }
        public void setSegmentEasing(@NonNull Easing e, long playheadMs) {
            if (easeSet != null) easeSet.setSegmentEasing(e, playheadMs);
        }
    }

    /** G3: who has keyframe focus — drives the top ribbon over the preview. */
    public interface FocusListener {
        /** The focused keyframeable property changed; {@code null} = sheet gone. */
        void onActivePropChanged(@Nullable Prop prop);
    }

    /** One row of the object-actions section (layer moves, remove, …). */
    public static final class Action {
        final String label;
        final boolean destructive;
        final Runnable run;

        public Action(@NonNull String label, boolean destructive, @NonNull Runnable run) {
            this.label = label;
            this.destructive = destructive;
            this.run = run;
        }
    }

    /**
     * Host for the "Move in time" section (SPEC_OBJECT_TIME_SCRUBBER). ADDITIVE: the section is
     * hidden unless {@link #setTimeScrub} is called with a non-null listener, so every existing
     * caller of {@link #show} is unaffected. The shuttle streams {@code onScrubTick}; the
     * tap-to-type readout fires {@code onJumpTo}; the two toggles fire their change callbacks.
     */
    public interface TimeScrubListener {
        void onScrubStart();
        void onScrubTick(long deltaMs);
        void onScrubEnd();
        void onJumpTo(long targetMs);
        void onPushThroughToggled(boolean on);
        void onIncrementToggled(boolean on);
    }

    private static final int SLIDER_STEPS = 1000;
    private static final int BG = 0xFF1C1C1E;
    private static final int TXT_DIM = 0xFF888888;
    private static final int TXT = 0xFFDDDDDD;
    private static final int ACCENT = 0xFF4CAF50;
    private static final int DESTRUCTIVE = 0xFFE57373;

    private final float density;
    private final LinearLayout gripRow;
    private final LinearLayout headerRow;
    private final TextView titleView;
    private final View swatchView;
    private final ScrollView scroll;
    private final LinearLayout content;      // rows + range chips + actions + more, inside scroll
    private final LinearLayout propsBox;
    /** Compact chip row (Start here / End here …) visible in PEEK too — range edits are
     *  exactly the actions that need a live, scrubbable timeline (JoyRaptor 2026-07-17 C2). */
    private final LinearLayout rangeBox;
    private final LinearLayout actionsBox;
    private final TextView moreBtn;

    // ── Move-in-time section (SPEC_OBJECT_TIME_SCRUBBER); additive, hidden unless configured ──
    private final LinearLayout timeScrubBox;
    private final com.fadcam.ui.faditor.move.TimeShuttleView shuttle;
    private final TextView scrubReadout;
    private final TextView pushToggle;
    private final TextView incrementToggle;
    @Nullable private TimeScrubListener timeScrubListener;
    private boolean pushThroughOn = true;
    private boolean incrementOn = false;
    private long scrubDisplayMs = 0;

    private final List<Row> rows = new ArrayList<>();
    @Nullable private GestureHooks hooks;
    @Nullable private Runnable onDismiss;
    @Nullable private FocusListener focusListener;
    private String activeKey = "";
    private boolean expanded;
    private boolean showing;
    private long playheadMs;
    /** C7: at most one "static — tap ♦ to animate" hint per show(). */
    private boolean hintShownThisShowing;

    public void setFocusListener(@Nullable FocusListener l) { focusListener = l; }

    @Nullable
    public Prop activeProp() {
        if (!showing) return null;
        for (Row r : rows) {
            if (r.prop.key.equals(activeKey)) return r.prop;
        }
        return null;
    }

    private void setActiveKey(@NonNull String key) {
        boolean changed = !key.equals(activeKey);
        activeKey = key;
        if (changed && focusListener != null) focusListener.onActivePropChanged(activeProp());
    }

    public ObjectMenuSheet(@NonNull Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setClickable(true); // consume touches — nothing falls through to the timeline
        setElevation(14 * density);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        float r = 16 * density;
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        setBackground(bg);
        int pad = dp(12);
        setPadding(pad, 0, pad, dp(10));

        gripRow = buildGripRow();
        addView(gripRow);

        headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(0, 0, 0, dp(4));
        swatchView = new View(ctx);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFFFFFFFF);
        swatchView.setBackground(dot);
        LayoutParams dotLp = new LayoutParams(dp(14), dp(14));
        dotLp.rightMargin = dp(8);
        swatchView.setLayoutParams(dotLp);
        headerRow.addView(swatchView);
        titleView = new TextView(ctx);
        titleView.setTextColor(TXT);
        titleView.setTextSize(15);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headerRow.addView(titleView, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        // No trash here: the timeline selection badge is the ONE delete affordance
        // (JoyRaptor 2026-07-17) — × stays on the right.
        TextView closeBtn = glyphButton("✕", TXT_DIM);
        closeBtn.setOnClickListener(v -> hide());
        headerRow.addView(closeBtn);
        addView(headerRow);

        content = new LinearLayout(ctx);
        content.setOrientation(VERTICAL);
        propsBox = new LinearLayout(ctx);
        propsBox.setOrientation(VERTICAL);
        content.addView(propsBox);
        rangeBox = new LinearLayout(ctx);
        rangeBox.setOrientation(HORIZONTAL);
        rangeBox.setPadding(0, dp(6), 0, dp(2));
        content.addView(rangeBox);
        actionsBox = new LinearLayout(ctx);
        actionsBox.setOrientation(VERTICAL);
        content.addView(actionsBox);
        moreBtn = new TextView(ctx);
        moreBtn.setText("More…"); // TODO(strings)
        moreBtn.setTextColor(ACCENT);
        moreBtn.setTextSize(14);
        moreBtn.setTypeface(null, Typeface.BOLD);
        moreBtn.setGravity(Gravity.CENTER);
        moreBtn.setPadding(0, dp(12), 0, dp(6));
        content.addView(moreBtn);

        // Move-in-time section — built once, added at the TOP of the body, hidden until
        // setTimeScrub() configures it for a movable object.
        timeScrubBox = new LinearLayout(ctx);
        timeScrubBox.setOrientation(VERTICAL);
        timeScrubBox.setPadding(0, dp(2), 0, dp(8));
        timeScrubBox.setVisibility(GONE);

        LinearLayout readRow = new LinearLayout(ctx);
        readRow.setOrientation(HORIZONTAL);
        readRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView moveLbl = new TextView(ctx);
        moveLbl.setText("Move to"); // TODO(strings)
        moveLbl.setTextColor(TXT_DIM);
        moveLbl.setTextSize(12);
        readRow.addView(moveLbl, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        scrubReadout = new TextView(ctx);
        scrubReadout.setTextColor(TXT);
        scrubReadout.setTextSize(15);
        scrubReadout.setTypeface(null, Typeface.BOLD);
        scrubReadout.setPadding(dp(10), dp(4), dp(10), dp(4));
        scrubReadout.setBackgroundResource(selectableBg());
        scrubReadout.setText(fmtTime(0));
        scrubReadout.setOnClickListener(v -> promptJumpToTime());
        readRow.addView(scrubReadout);
        timeScrubBox.addView(readRow);

        shuttle = new com.fadcam.ui.faditor.move.TimeShuttleView(ctx);
        shuttle.setListener(new com.fadcam.ui.faditor.move.TimeShuttleView.Listener() {
            @Override public void onScrubStart() {
                if (timeScrubListener != null) timeScrubListener.onScrubStart();
            }
            @Override public void onScrubTick(long deltaMs) {
                if (timeScrubListener != null) timeScrubListener.onScrubTick(deltaMs);
            }
            @Override public void onScrubEnd() {
                if (timeScrubListener != null) timeScrubListener.onScrubEnd();
            }
        });
        LayoutParams shuttleLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(44));
        shuttleLp.topMargin = dp(4);
        timeScrubBox.addView(shuttle, shuttleLp);

        LinearLayout togRow = new LinearLayout(ctx);
        togRow.setOrientation(HORIZONTAL);
        togRow.setPadding(0, dp(6), 0, 0);
        pushToggle = new TextView(ctx);
        pushToggle.setTextSize(12);
        pushToggle.setPadding(dp(4), dp(6), dp(10), dp(6));
        pushToggle.setOnClickListener(v -> {
            pushThroughOn = !pushThroughOn;
            renderToggle(pushToggle, "Push through", pushThroughOn); // TODO(strings)
            if (timeScrubListener != null) timeScrubListener.onPushThroughToggled(pushThroughOn);
        });
        renderToggle(pushToggle, "Push through", pushThroughOn); // TODO(strings)
        togRow.addView(pushToggle);
        incrementToggle = new TextView(ctx);
        incrementToggle.setTextSize(12);
        incrementToggle.setPadding(dp(4), dp(6), dp(10), dp(6));
        incrementToggle.setOnClickListener(v -> {
            incrementOn = !incrementOn;
            renderToggle(incrementToggle, "Snap", incrementOn); // TODO(strings)
            if (timeScrubListener != null) timeScrubListener.onIncrementToggled(incrementOn);
        });
        renderToggle(incrementToggle, "Snap", incrementOn); // TODO(strings)
        LayoutParams incLp = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        incLp.leftMargin = dp(12);
        incrementToggle.setLayoutParams(incLp);
        togRow.addView(incrementToggle);
        timeScrubBox.addView(togRow);

        content.addView(timeScrubBox, 0);

        // Expanded content is capped so the sheet never swallows the whole
        // screen; inside the cap it scrolls (always-scroll rule).
        scroll = new ScrollView(ctx) {
            @Override
            protected void onMeasure(int w, int h) {
                int cap = (int) (getResources().getDisplayMetrics().heightPixels * 0.52f);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content);
        addView(scroll);

        setVisibility(GONE);
    }

    // ── public API ───────────────────────────────────────────────────

    /**
     * (Re)populate and show, starting in PEEK. {@code rangeChips} (nullable) are
     * compact peek-visible chips — Start/End-here style actions that only make
     * sense with the timeline live under the sheet.
     */
    public void show(@NonNull String title, @Nullable Integer swatchColor,
                     @NonNull List<Prop> props, @NonNull List<Action> actions,
                     @Nullable Runnable onMore, @Nullable List<Action> rangeChips,
                     @NonNull GestureHooks hooks, long playheadMs,
                     @Nullable Runnable onDismiss) {
        this.hooks = hooks;
        this.playheadMs = playheadMs;
        this.onDismiss = onDismiss;
        titleView.setText(title);
        if (swatchColor != null) {
            swatchView.setVisibility(VISIBLE);
            ((GradientDrawable) swatchView.getBackground()).setColor(swatchColor);
        } else {
            swatchView.setVisibility(GONE);
        }
        moreBtn.setVisibility(onMore != null ? VISIBLE : GONE);
        moreBtn.setOnClickListener(onMore == null ? null : v -> onMore.run());

        rows.clear();
        propsBox.removeAllViews();
        for (Prop p : props) {
            Row row = new Row(p);
            rows.add(row);
            propsBox.addView(row.view);
        }
        rangeBox.removeAllViews();
        if (rangeChips != null) {
            for (Action a : rangeChips) {
                TextView chip = new TextView(getContext());
                chip.setText(a.label);
                chip.setTextColor(a.destructive ? DESTRUCTIVE : TXT);
                chip.setTextSize(12);
                chip.setSingleLine(true);
                chip.setPadding(dp(10), dp(6), dp(10), dp(6));
                GradientDrawable chipBg = new GradientDrawable();
                chipBg.setColor(0xFF2A2A2E);
                chipBg.setCornerRadius(dp(14));
                chipBg.setStroke(dp(1), 0xFF3A3A3E);
                chip.setBackground(chipBg);
                chip.setOnClickListener(v -> a.run.run());
                LayoutParams clp = new LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                clp.rightMargin = dp(8);
                chip.setLayoutParams(clp);
                rangeBox.addView(chip);
            }
        }
        actionsBox.removeAllViews();
        for (Action a : actions) {
            TextView btn = new TextView(getContext());
            btn.setText(a.label);
            btn.setTextColor(a.destructive ? DESTRUCTIVE : TXT);
            btn.setTextSize(14);
            btn.setPadding(dp(4), dp(11), dp(4), dp(11));
            btn.setBackgroundResource(selectableBg());
            btn.setOnClickListener(v -> a.run.run());
            actionsBox.addView(btn);
        }

        // Opacity is the everyday peek row; fall back to the first prop.
        activeKey = props.isEmpty() ? "" : props.get(0).key;
        for (Prop p : props) {
            if ("opacity".equals(p.key)) { activeKey = p.key; break; }
        }
        // Move-in-time is opt-in per show(): the activity calls setTimeScrub() AFTER show()
        // only for a movable object, so reset it hidden here.
        timeScrubListener = null;
        timeScrubBox.setVisibility(GONE);
        expanded = false;
        showing = true;
        hintShownThisShowing = false;
        setVisibility(VISIBLE);
        applyState();
        refreshRows();
        // Fresh Prop objects every show() — always re-announce the focus (G3 ribbon).
        if (focusListener != null) focusListener.onActivePropChanged(activeProp());
    }

    public boolean isShowing() { return showing; }

    public boolean isExpanded() { return showing && expanded; }

    public void hide() {
        if (!showing) return;
        showing = false;
        setVisibility(GONE);
        if (focusListener != null) focusListener.onActivePropChanged(null);
        if (onDismiss != null) onDismiss.run();
    }

    /** Timeline scrub/playback tick — keep visible rows honest. */
    public void onPlayheadChanged(long ms) {
        playheadMs = ms;
        if (showing) refreshRows();
    }

    // ── Move-in-time public API (SPEC_OBJECT_TIME_SCRUBBER) ────────────

    /**
     * Show + configure the Move-in-time section, or hide it ({@code l == null}). Call AFTER
     * {@link #show}. Additive — no effect on any other part of the sheet.
     */
    public void setTimeScrub(@Nullable TimeScrubListener l, long startMs,
                             boolean pushThrough, boolean increment) {
        this.timeScrubListener = l;
        if (l == null) { timeScrubBox.setVisibility(GONE); return; }
        this.pushThroughOn = pushThrough;
        this.incrementOn = increment;
        renderToggle(pushToggle, "Push through", pushThroughOn); // TODO(strings)
        renderToggle(incrementToggle, "Snap", incrementOn);      // TODO(strings)
        setScrubTimeMs(startMs);
        timeScrubBox.setVisibility(showing ? VISIBLE : GONE);
        requestLayout();
    }

    /** Update the live readout (the object's current start), e.g. on each scrub preview. */
    public void setScrubTimeMs(long ms) {
        scrubDisplayMs = Math.max(0, ms);
        scrubReadout.setText(fmtTime(scrubDisplayMs));
    }

    private void renderToggle(@NonNull TextView t, @NonNull String label, boolean on) {
        t.setText((on ? "☑ " : "☐ ") + label);   // ☑ / ☐
        t.setTextColor(on ? ACCENT : TXT_DIM);
    }

    private void promptJumpToTime() {
        if (timeScrubListener == null) return;
        final android.widget.EditText in = new android.widget.EditText(getContext());
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        in.setText(fmtTime(scrubDisplayMs));
        in.setSelectAllOnFocus(true);
        new android.app.AlertDialog.Builder(getContext())
                .setTitle("Jump to time (m:ss.mmm)") // TODO(strings)
                .setView(in)
                .setPositiveButton("Go", (d, w) -> {  // TODO(strings)
                    long ms = parseTime(in.getText().toString());
                    if (ms >= 0 && timeScrubListener != null) timeScrubListener.onJumpTo(ms);
                })
                .setNegativeButton("Cancel", null)    // TODO(strings)
                .show();
    }

    /** {@code m:ss.mmm}. */
    static String fmtTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long m = totalSec / 60, s = totalSec % 60, millis = ms % 1000;
        return String.format(java.util.Locale.US, "%d:%02d.%03d", m, s, millis);
    }

    /** Parse {@code m:ss.mmm} / {@code ss.mmm} / {@code ss}; -1 if unparseable. */
    static long parseTime(@Nullable String s) {
        if (s == null) return -1;
        s = s.trim();
        if (s.isEmpty()) return -1;
        try {
            long minutes = 0;
            String rest = s;
            int colon = s.indexOf(':');
            if (colon >= 0) {
                minutes = Long.parseLong(s.substring(0, colon).trim());
                rest = s.substring(colon + 1).trim();
            }
            double seconds = Double.parseDouble(rest);
            return Math.max(0, Math.round((minutes * 60 + seconds) * 1000.0));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ── internals ────────────────────────────────────────────────────

    private void applyState() {
        // Move-in-time stays visible in PEEK and EXPANDED whenever it's configured — like the
        // range chips, it needs the timeline live under it.
        timeScrubBox.setVisibility(timeScrubListener != null ? VISIBLE : GONE);
        headerRow.setVisibility(expanded ? VISIBLE : GONE);
        // Range chips stay in PEEK — they're the scrub-while-open actions (C2).
        rangeBox.setVisibility(rangeBox.getChildCount() > 0 ? VISIBLE : GONE);
        actionsBox.setVisibility(expanded ? VISIBLE : GONE);
        moreBtn.setVisibility(expanded && moreBtn.hasOnClickListeners() ? VISIBLE : GONE);
        for (Row row : rows) {
            row.view.setVisibility(expanded || row.prop.key.equals(activeKey) ? VISIBLE : GONE);
        }
        if (!expanded) scroll.scrollTo(0, 0);
        requestLayout();
    }

    private void refreshRows() {
        for (Row row : rows) {
            if (row.view.getVisibility() == VISIBLE) row.refresh();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private LinearLayout buildGripRow() {
        LinearLayout grip = new LinearLayout(getContext());
        grip.setGravity(Gravity.CENTER);
        grip.setPadding(0, dp(8), 0, dp(8));
        View pill = new View(getContext());
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0xFF555555);
        pillBg.setCornerRadius(3 * density);
        pill.setBackground(pillBg);
        grip.addView(pill, new LayoutParams(dp(38), dp(4)));

        final float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        grip.setOnTouchListener(new OnTouchListener() {
            float downY;
            boolean acted;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY();
                        acted = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = e.getRawY() - downY;
                        if (!acted && dy < -slop * 2) {          // drag up → expand
                            acted = true;
                            if (!expanded) { expanded = true; applyState(); refreshRows(); }
                        } else if (!acted && dy > slop * 2) {    // drag down → collapse / dismiss
                            acted = true;
                            if (expanded) { expanded = false; applyState(); }
                            else hide();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!acted) {                             // tap → toggle
                            expanded = !expanded;
                            applyState();
                            refreshRows();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        return grip;
    }

    private TextView glyphButton(@NonNull String glyph, int color) {
        TextView v = new TextView(getContext());
        v.setText(glyph);
        v.setTextColor(color);
        v.setTextSize(16);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), dp(6), dp(10), dp(6));
        v.setBackgroundResource(selectableBg());
        return v;
    }

    private int selectableBg() {
        android.util.TypedValue tv = new android.util.TypedValue();
        getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    private int dp(int v) { return (int) (v * density + 0.5f); }

    /** Label (+ inline arming hint) + slider + live value + {@code ‹♦›} control. */
    private final class Row implements KeyframeDiamondControl.Host {
        final Prop prop;
        final LinearLayout view;      // vertical: controls row + hint line
        final SeekBar bar;
        final TextView value;
        final TextView hint;
        // §2: null for STATIC props (visualizer / audio placement) — no keyframes,
        // so no diamond to bind.
        @Nullable final KeyframeDiamondControl diamond;
        boolean touching;

        Row(@NonNull Prop p) {
            this.prop = p;
            view = new LinearLayout(getContext());
            view.setOrientation(VERTICAL);
            view.setPadding(0, dp(2), 0, dp(2));

            LinearLayout controls = new LinearLayout(getContext());
            controls.setOrientation(HORIZONTAL);
            controls.setGravity(Gravity.CENTER_VERTICAL);
            view.addView(controls);

            TextView label = new TextView(getContext());
            label.setText(p.label);
            label.setTextColor(TXT_DIM);
            label.setTextSize(12);
            label.setSingleLine(true);
            label.setWidth(dp(64));
            controls.addView(label);

            bar = new SeekBar(getContext());
            bar.setMax(SLIDER_STEPS);
            controls.addView(bar, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

            value = new TextView(getContext());
            value.setTextColor(TXT);
            value.setTextSize(12);
            value.setGravity(Gravity.END);
            value.setWidth(dp(44));
            controls.addView(value);

            // §2 STATIC props have no keyframes → no diamond (the "tap ♦" hint it
            // implies would point at a control that doesn't exist here).
            if (prop.keyframeable) {
                diamond = new KeyframeDiamondControl(getContext());
                diamond.bind(prop, this);
                LayoutParams dlp = new LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                dlp.leftMargin = dp(4);
                controls.addView(diamond, dlp);
            } else {
                diamond = null;
            }

            // C7: appears under the row on the first un-armed drag, then fades.
            hint = new TextView(getContext());
            hint.setTextColor(TXT_DIM);
            hint.setTextSize(11);
            hint.setPadding(0, dp(1), 0, 0);
            hint.setVisibility(GONE);
            view.addView(hint);

            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    setActiveKey(prop.key); // last-touched row becomes the peek row + focus
                    maybeFlashArmingHint();
                    float v = prop.min + (prop.max - prop.min) * progress / (float) SLIDER_STEPS;
                    prop.set.write(v, playheadMs);
                    value.setText(prop.format.format(v));
                    if (diamond != null) diamond.refresh(playheadMs);
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                    touching = true;
                    if (hooks != null) hooks.onSliderStart();
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                    touching = false;
                    if (hooks != null) hooks.onSliderCommit(prop.label);
                    refresh();
                }
            });
        }

        // ── KeyframeDiamondControl.Host ──
        @Override public long playheadMs() { return playheadMs; }
        @Override public void onFocus() { setActiveKey(prop.key); }
        @Override public void onAction() { refreshRows(); }

        /** C7: first un-armed slider drag this showing → inline honesty hint + pulse.
         *  NO auto-keying — un-armed drags stay static; this is feedback only. */
        private void maybeFlashArmingHint() {
            // Static props can't be armed — the "tap ♦" hint would be a lie.
            if (!prop.keyframeable || hintShownThisShowing || prop.armed()) return;
            hintShownThisShowing = true;
            hint.setText("Static — tap ♦ to animate"); // TODO(strings)
            hint.setAlpha(1f);
            hint.setVisibility(VISIBLE);
            hint.animate().cancel();
            hint.postDelayed(() -> hint.animate().alpha(0f).setDuration(400)
                    .withEndAction(() -> hint.setVisibility(GONE)).start(), 2500);
            diamond.pulse();
        }

        void refresh() {
            if (!touching) {
                float v = prop.get.at(playheadMs);
                int progress = Math.round((v - prop.min) / (prop.max - prop.min) * SLIDER_STEPS);
                bar.setProgress(Math.max(0, Math.min(SLIDER_STEPS, progress)));
                value.setText(prop.format.format(v));
            }
            if (diamond != null) diamond.refresh(playheadMs);
        }
    }
}
