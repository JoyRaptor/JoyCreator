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

    /** One keyframeable general-menu property (contract §2's diamond rows). */
    public static final class Prop {
        final String key;          // KeyframeSet property key, unique per sheet
        final String label;
        final float min, max;      // slider value space
        final ValueFormat format;
        final Getter get;          // animated value at the playhead
        final Setter set;          // keyframe-aware write at the playhead
        final OnKeyQuery onKey;    // playhead sits on a key of this property?
        final Runnable dropKey;    // hollow-diamond tap → drop a key here

        public Prop(@NonNull String key, @NonNull String label, float min, float max,
                    @NonNull ValueFormat format, @NonNull Getter get, @NonNull Setter set,
                    @NonNull OnKeyQuery onKey, @NonNull Runnable dropKey) {
            this.key = key;
            this.label = label;
            this.min = min;
            this.max = max;
            this.format = format;
            this.get = get;
            this.set = set;
            this.onKey = onKey;
            this.dropKey = dropKey;
        }
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
    private final TextView deleteBtn;
    private final ScrollView scroll;
    private final LinearLayout content;      // rows + actions + more, inside scroll
    private final LinearLayout propsBox;
    private final LinearLayout actionsBox;
    private final TextView moreBtn;

    private final List<Row> rows = new ArrayList<>();
    @Nullable private GestureHooks hooks;
    @Nullable private Runnable onDismiss;
    private String activeKey = "";
    private boolean expanded;
    private boolean showing;
    private long playheadMs;

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
        deleteBtn = glyphButton("🗑", DESTRUCTIVE); // 🗑
        headerRow.addView(deleteBtn);
        TextView closeBtn = glyphButton("✕", TXT_DIM);
        closeBtn.setOnClickListener(v -> hide());
        headerRow.addView(closeBtn);
        addView(headerRow);

        content = new LinearLayout(ctx);
        content.setOrientation(VERTICAL);
        propsBox = new LinearLayout(ctx);
        propsBox.setOrientation(VERTICAL);
        content.addView(propsBox);
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

    /** (Re)populate and show, starting in PEEK. */
    public void show(@NonNull String title, @Nullable Integer swatchColor,
                     @NonNull List<Prop> props, @NonNull List<Action> actions,
                     @Nullable Runnable onMore, @Nullable Runnable onDelete,
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
        deleteBtn.setVisibility(onDelete != null ? VISIBLE : GONE);
        deleteBtn.setOnClickListener(onDelete == null ? null : v -> { hide(); onDelete.run(); });
        moreBtn.setVisibility(onMore != null ? VISIBLE : GONE);
        moreBtn.setOnClickListener(onMore == null ? null : v -> onMore.run());

        rows.clear();
        propsBox.removeAllViews();
        for (Prop p : props) {
            Row row = new Row(p);
            rows.add(row);
            propsBox.addView(row.view);
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
        expanded = false;
        showing = true;
        setVisibility(VISIBLE);
        applyState();
        refreshRows();
    }

    public boolean isShowing() { return showing; }

    public boolean isExpanded() { return showing && expanded; }

    public void hide() {
        if (!showing) return;
        showing = false;
        setVisibility(GONE);
        if (onDismiss != null) onDismiss.run();
    }

    /** Timeline scrub/playback tick — keep visible rows honest. */
    public void onPlayheadChanged(long ms) {
        playheadMs = ms;
        if (showing) refreshRows();
    }

    // ── internals ────────────────────────────────────────────────────

    private void applyState() {
        headerRow.setVisibility(expanded ? VISIBLE : GONE);
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

    /** Label + slider + live value + keyframe diamond, one per {@link Prop}. */
    private final class Row {
        final Prop prop;
        final LinearLayout view;
        final SeekBar bar;
        final TextView value;
        final TextView diamond;
        boolean touching;

        Row(@NonNull Prop p) {
            this.prop = p;
            view = new LinearLayout(getContext());
            view.setOrientation(HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(0, dp(2), 0, dp(2));

            TextView label = new TextView(getContext());
            label.setText(p.label);
            label.setTextColor(TXT_DIM);
            label.setTextSize(12);
            label.setSingleLine(true);
            label.setWidth(dp(64));
            view.addView(label);

            bar = new SeekBar(getContext());
            bar.setMax(SLIDER_STEPS);
            view.addView(bar, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

            value = new TextView(getContext());
            value.setTextColor(TXT);
            value.setTextSize(12);
            value.setGravity(Gravity.END);
            value.setWidth(dp(44));
            view.addView(value);

            diamond = new TextView(getContext());
            diamond.setTextSize(16);
            diamond.setGravity(Gravity.CENTER);
            diamond.setPadding(dp(8), 0, dp(2), 0);
            diamond.setBackgroundResource(selectableBg());
            diamond.setOnClickListener(v -> {
                prop.dropKey.run();
                refreshRows(); // arming one property re-anchors every diamond
            });
            view.addView(diamond);

            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    activeKey = prop.key; // last-touched row becomes the peek row
                    float v = prop.min + (prop.max - prop.min) * progress / (float) SLIDER_STEPS;
                    prop.set.write(v, playheadMs);
                    value.setText(prop.format.format(v));
                    refreshDiamond();
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

        void refresh() {
            if (!touching) {
                float v = prop.get.at(playheadMs);
                int progress = Math.round((v - prop.min) / (prop.max - prop.min) * SLIDER_STEPS);
                bar.setProgress(Math.max(0, Math.min(SLIDER_STEPS, progress)));
                value.setText(prop.format.format(v));
            }
            refreshDiamond();
        }

        void refreshDiamond() {
            boolean on = prop.onKey.onKeyAt(playheadMs);
            diamond.setText(on ? "◆" : "◇");
            diamond.setTextColor(on ? ACCENT : TXT_DIM);
        }
    }
}
