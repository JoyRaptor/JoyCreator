package com.fadcam.ui.faditor.tools;

import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.fadcam.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 2 — swipe-up "all tools" sheet for the editor.
 *
 * <p>Drawn to record 06 §02 ({@code .sheet .sh .sb .tgrid}): the dark scrim itself is the sheet
 * — rgba(0,0,0,.64) with a white-10% top edge and 20dp top corners — sitting 78dp below the top
 * of the screen so the editor's top bar stays visible. A header carries the title in Archivo
 * 14.5 w800 and a mono count of the tools shown, and the body is a five-column grid of 9dp-radius
 * cells on white-10%, 14sp glyph over a 7sp single-line label. Because the sheet is translucent
 * over the video, all of its text uses the DRAWER ink ramp.</p>
 *
 * <p>Tapping a tool re-fires the SAME carousel cell (via {@link FaditorToolsAdapter#getCellForId}
 * + {@code performClick()}), so every existing handler — including the mute / opacity touch
 * tools' tap paths — runs unchanged, then the sheet dismisses. Swipe-down on the sheet, the close
 * button, a tap above the sheet, or system back dismisses.</p>
 *
 * <p>Context is respected: tools whose carousel cell is {@code GONE} for the current selection
 * (currently only the always-hidden {@code trim}/{@code heal}) are omitted from the grid too.</p>
 *
 * <p><b>Two fixes for the owner's report that the last row had no labels.</b> The grid was a
 * {@code GridLayout} with weighted zero-width cells, which measures a child's height before its
 * width is final, and the panel never reserved room for the navigation / gesture bar that the
 * immersive editor lays out beneath. The grid is now plain weighted rows (deterministic
 * measurement, single-line labels), the sheet's height is capped to the space below the top bar
 * with the grid scrolling inside it, and the bottom padding adds the system bar inset.</p>
 */
public class FaditorToolsDrawer {

    public interface OnToolChosen {
        /** Called with the chosen tool id just before the drawer dismisses. */
        void onToolChosen(@NonNull String toolId);
    }

    /** Record 06 {@code .tgrid}: repeat(5, 1fr). */
    private static final int COLUMNS = 5;
    /** Record 06 {@code .sheet}: top:78px — the editor's top bar stays in view above it. */
    private static final int TOP_RESERVE_DP = 78;
    /** Record 06: the drawer curve, cubic-bezier(.32,.72,0,1), 320ms in. */
    private static final int IN_MS = 320;
    private static final int OUT_MS = 200;
    private static final int SCRIM_IN_MS = 180;
    private static final int SCRIM_OUT_MS = 160;

    // ── colours, by role (record 06 :root) ───────────────────────────────
    /**
     * The sheet's own fill: SURFACE at 96%, NOT the drawer's see-through 64% scrim.
     *
     * <p>Record 06 draws this sheet on --scrim with blur(22px). The blur is what makes 64% work,
     * and Android before 12 cannot blur what is behind a view — the Note 9 is Android 10. So on
     * the device the timeline showed through SHARP: the cat filmstrip behind "Move", the
     * waveform behind "Volume", the sheet's close button sitting on the sprite tape's stars.
     *
     * <p>And unlike an object drawer, this sheet does not sit over the VIDEO. JoyRaptor's reason
     * for see-through drawers is to keep watching what you are changing; here the thing behind
     * is the timeline and the tool row, which the sheet replaces while it is open. Seeing it
     * through the tiles is clutter, not information. The object drawers over the preview keep
     * their 64%.
     */
    private static final int SHEET_FILL = Studio.alpha(Studio.SURFACE, 0xF5);
    /** {@code --edge}: rgba(255,255,255,.10), the top hairline. */
    private static final int SHEET_EDGE = Studio.alpha(Studio.DRAWER_INK, 0x1A);
    /** {@code --dctl}: rgba(255,255,255,.10), a control over the scrim. */
    private static final int CELL_FILL = Studio.alpha(Studio.DRAWER_INK, 0x1A);
    /** A light veil over the rest of the editor, so a tap there reads as "close". */
    private static final int OUTSIDE_VEIL = Studio.alpha(Studio.GROUND, 0x33);

    private final Context context;
    private final ViewGroup root;
    private final FaditorToolsAdapter adapter;
    private final OnToolChosen callback;

    private FrameLayout overlay;
    private View scrim;
    private LinearLayout panel;
    private boolean showing;

    public FaditorToolsDrawer(@NonNull ViewGroup root,
                              @NonNull FaditorToolsAdapter adapter,
                              @NonNull OnToolChosen callback) {
        this.context = root.getContext();
        this.root = root;
        this.adapter = adapter;
        this.callback = callback;
    }

    public boolean isShowing() {
        return showing;
    }

    /** Builds (if needed) and animates the drawer in. */
    public void show() {
        if (showing) return;
        showing = true;
        buildOverlay();
        root.addView(overlay);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(SCRIM_IN_MS).start();
        panel.post(() -> {
            float h = panel.getHeight();
            panel.setTranslationY(h);
            panel.animate().translationY(0f).setDuration(IN_MS)
                    .setInterpolator(curve()).start();
        });
    }

    /** Animates the drawer out and removes it. */
    public void dismiss() {
        if (!showing || overlay == null) return;
        showing = false;
        final FrameLayout toRemove = overlay;
        scrim.animate().alpha(0f).setDuration(SCRIM_OUT_MS).start();
        panel.animate().translationY(panel.getHeight()).setDuration(OUT_MS)
                .setInterpolator(curve())
                .withEndAction(() -> {
                    root.removeView(toRemove);
                    if (overlay == toRemove) overlay = null;
                })
                .start();
    }

    // ── Build ─────────────────────────────────────────────────────────

    private void buildOverlay() {
        overlay = new FrameLayout(context);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setClickable(true);
        overlay.setFocusable(true);

        // Outside the sheet — tap to dismiss.
        scrim = new View(context);
        scrim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.setBackgroundColor(OUTSIDE_VEIL);
        scrim.setOnClickListener(v -> dismiss());
        overlay.addView(scrim);

        // The sheet. Its height is capped so it never climbs over the top bar.
        panel = new LinearLayout(context) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int avail = View.MeasureSpec.getSize(heightSpec);
                if (avail > 0) {
                    int cap = Math.max(dp(160), avail - dp(TOP_RESERVE_DP));
                    heightSpec = View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        panel.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.BOTTOM;
        panel.setLayoutParams(panelLp);
        panel.setBackground(sheetBackground());
        panel.setClickable(true);
        // .sb padding 0 10 12; the system bar inset is added on top (see fitBottom).
        final int baseBottom = dp(12);
        panel.setPadding(0, 0, 0, baseBottom + systemBottomInset());
        ViewCompat.setOnApplyWindowInsetsListener(panel, (v, insets) -> {
            v.setPadding(0, 0, 0, baseBottom + bottomOf(insets));
            return insets;
        });
        // Swipe-down on the panel dismisses.
        attachSwipeDownDismiss(panel);
        overlay.addView(panel);

        // Which tools show — unchanged: skip always-hidden and currently-GONE cells.
        List<FaditorTool> shown = new ArrayList<>();
        for (FaditorTool tool : adapter.getTools()) {
            if (tool.alwaysHidden) continue;
            View cell = adapter.getCellForId(tool.id);
            // Skip tools currently hidden in the carousel (context filtering).
            if (cell != null && cell.getVisibility() != View.VISIBLE) continue;
            shown.add(tool);
        }

        // Header: grab, "All tools" + mono count, close.
        SheetKit.Header header = SheetKit.header(context,
                context.getString(R.string.faditor_tools_all_title),
                context.getString(R.string.lane_d_tools_count, shown.size()));
        header.title.setTextColor(Studio.DRAWER_INK);
        header.count.setTextColor(Studio.DRAWER_LABEL);
        recolourGrab(header.view);
        TextView close = SheetKit.iconButton(context, "close", Studio.DRAWER_DIM,
                context.getString(R.string.lane_d_tools_close));
        close.setOnClickListener(v -> dismiss());
        header.addTrailing(close);
        panel.addView(header.view);

        // Body: the grid, scrolling inside the capped sheet.
        ScrollView scroll = new ScrollView(context);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        // WRAP_CONTENT under the panel's AT_MOST cap measures as "the grid, or whatever is left
        // below the header if that is less" — a short grid is not stretched, a long one scrolls.
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(10), 0, dp(10), 0);

        // SECTIONS, as record 06 draws this sheet (.tg + .tgrid), not one flat grid of 30.
        //
        // First: the five tools used most recently. The record calls it "Most used by you";
        // the app records WHEN a tool was last used, not how often, so the heading says what
        // the data can honestly back. A tool can appear here and in its own section too, as
        // in the record.
        java.util.Map<String, Long> recency = adapter.getRecency();
        List<FaditorTool> recent = new ArrayList<>();
        for (FaditorTool t : shown) if (recency.containsKey(t.id)) recent.add(t);
        java.util.Collections.sort(recent, (a, b) ->
                Long.compare(recency.get(b.id), recency.get(a.id)));
        if (recent.size() > COLUMNS) recent = new ArrayList<>(recent.subList(0, COLUMNS));
        addSection(grid, R.string.tools_group_recent, Studio.ARMED, recent);

        // Then the record's four groups. "Act on what's selected" wears the SELECTION's colour,
        // exactly as the tool row's glyphs do, and plain dim when nothing is selected.
        int selTint = adapter.getContextTint();
        int[] groupLabel = {R.string.tools_group_act, R.string.tools_group_add,
                R.string.tools_group_sound, R.string.tools_group_project};
        int[] groupDot = {selTint != 0 ? selTint : Studio.DRAWER_DIM,
                com.fadcam.ui.faditor.layers.ObjectPalette.TEXT,
                com.fadcam.ui.faditor.layers.ObjectPalette.AUDIO,
                Studio.DRAWER_LABEL};
        for (int g = 0; g < groupLabel.length; g++) {
            List<FaditorTool> members = new ArrayList<>();
            for (FaditorTool t : shown) {
                if (FaditorToolRegistry.groupOf(t.id) == g) members.add(t);
            }
            addSection(grid, groupLabel[g], groupDot[g], members);
        }
        scroll.addView(grid);
        panel.addView(scroll);
    }

    /**
     * One section: record 06's dot + mono label with its count, then the tools five across.
     * Empty sections draw nothing. The last row is padded with empty slots so its cells keep
     * the width of the rows above.
     */
    private void addSection(@NonNull LinearLayout grid, int label, int dot,
                            @NonNull List<FaditorTool> tools) {
        if (tools.isEmpty()) return;
        LinearLayout head = com.fadcam.ui.faditor.SheetKit.sectionLabel(context,
                context.getString(label) + " \u00b7 " + tools.size(), dot);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headLp.topMargin = grid.getChildCount() == 0 ? 0 : dp(10);
        headLp.bottomMargin = dp(5);
        grid.addView(head, headLp);

        LinearLayout row = null;
        for (int i = 0; i < tools.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rowLp.topMargin = dp(4);   // .tgrid gap 4
                grid.addView(row, rowLp);
            }
            row.addView(buildGridCell(tools.get(i), i % COLUMNS));
        }
        int rem = tools.size() % COLUMNS;
        if (row != null && rem != 0) {
            for (int k = rem; k < COLUMNS; k++) {
                View filler = new View(context);
                LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(0, 1, 1f);
                fLp.setMarginStart(dp(4));
                row.addView(filler, fLp);
            }
        }
    }

    private View buildGridCell(@NonNull FaditorTool tool, int column) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        // .tgrid span: padding 6px 1px, gap 3px.
        cell.setPadding(dp(1), dp(6), dp(1), dp(6));
        cell.setMinimumHeight(dp(40));
        cell.setBackground(cellBackground());
        cell.setClickable(true);
        cell.setFocusable(true);
        SheetKit.press(cell);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (column > 0) lp.setMarginStart(dp(4));   // .tgrid gap 4
        cell.setLayoutParams(lp);

        // Reflect the CURRENT icon/label/color of the live carousel cell so the
        // drawer mirrors dynamic state (e.g. "6.5x" green speed, "151%" red).
        View liveCell = adapter.getCellForId(tool.id);
        TextView liveIcon = liveCell != null ? liveCell.findViewById(tool.iconViewId) : null;
        TextView liveLabel = liveCell != null ? liveCell.findViewById(tool.labelViewId) : null;

        TextView icon = new TextView(context);
        icon.setGravity(Gravity.CENTER);
        icon.setIncludeFontPadding(false);
        // The live cell may carry a dynamically overridden glyph; a text icon has no live
        // override to inherit, so it is applied from the registry either way.
        if (tool.icon.startsWith(FaditorTool.TEXT_ICON)) {
            FaditorTool.applyIcon(icon, tool.icon, 14f);
        } else {
            icon.setTypeface(ResourcesCompat.getFont(context, R.font.materialicons));
            icon.setText(liveIcon != null ? liveIcon.getText() : tool.icon);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);   // .tgrid span em: 14
        }
        icon.setTextColor(onScrim(liveIcon != null ? liveIcon.getCurrentTextColor() : 0));
        cell.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));

        CharSequence labelText = liveLabel != null ? liveLabel.getText() : tool.label;
        TextView label = new TextView(context);
        label.setGravity(Gravity.CENTER);
        label.setText(labelText);
        label.setTextColor(onScrim(liveLabel != null ? liveLabel.getCurrentTextColor() : 0));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7f);   // .tgrid span: 7
        Type.body(label, Type.MEDIUM);
        label.setIncludeFontPadding(false);
        // Never two lines — a two-line tap target is its own defect (record 06 MINOR 09).
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(3);
        cell.addView(label, labelLp);

        // The full name, for a stylus or mouse hover and for TalkBack — the 7sp label may clip.
        SheetKit.label(cell, tool.label.contentEquals(labelText)
                ? tool.label : tool.label + " — " + labelText);

        cell.setOnClickListener(v -> {
            callback.onToolChosen(tool.id);
            View target = adapter.getCellForId(tool.id);
            if (target != null) target.performClick();
            dismiss();
        });
        return cell;
    }

    /**
     * The carousel paints its resting tools in SCREEN ink (INK_FAINT), which is the contrast bug
     * record 06 measured on the scrim. A resting colour becomes the drawer's own dim; any other
     * colour is a live state (speed changed, volume over 100%, a context tint) and is kept.
     */
    private static int onScrim(int live) {
        if (live == 0 || live == Studio.INK_FAINT || live == Studio.INK_DIM) {
            return Studio.DRAWER_DIM;
        }
        return live;
    }

    // ── Drawing ───────────────────────────────────────────────────────

    private GradientDrawable sheetBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SHEET_FILL);
        float r = dp(20);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(Math.max(1, dp(1) / 2), SHEET_EDGE);   // inset 0 1px 0 var(--edge)
        return bg;
    }

    private RippleDrawable cellBackground() {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(CELL_FILL);
        fill.setCornerRadius(dp(9));   // .tgrid span radius 9
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(CELL_FILL);
        focused.setCornerRadius(dp(9));
        focused.setStroke(dp(2), Studio.ARMED);   // focus-visible: 2dp cyan, instant
        android.graphics.drawable.StateListDrawable states =
                new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, fill);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Studio.DRAWER_INK);
        mask.setCornerRadius(dp(9));
        return new RippleDrawable(
                ColorStateList.valueOf(Studio.alpha(Studio.DRAWER_INK, 0x33)), states, mask);
    }

    /** The grab pill on the scrim uses the drawer's white-30%, not the screen ramp's. */
    private void recolourGrab(@NonNull View headerColumn) {
        if (!(headerColumn instanceof ViewGroup)) return;
        View strip = ((ViewGroup) headerColumn).getChildAt(0);
        if (strip instanceof ViewGroup && ((ViewGroup) strip).getChildCount() > 0) {
            View pill = ((ViewGroup) strip).getChildAt(0);
            if (pill.getBackground() instanceof GradientDrawable) {
                ((GradientDrawable) pill.getBackground().mutate())
                        .setColor(Studio.alpha(Studio.DRAWER_INK, 0x4D));
            }
        }
    }

    private static PathInterpolator curve() {
        return new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    }

    // ── Insets ────────────────────────────────────────────────────────

    /**
     * The editor runs immersive with LAYOUT_HIDE_NAVIGATION, so its layout extends under the
     * navigation bar even while the bar is hidden; a swipe brings the bar back OVER the sheet.
     * Reserve the bar's height whether or not it is showing, and the gesture strip.
     */
    private int systemBottomInset() {
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(root);
        return insets != null ? bottomOf(insets) : 0;
    }

    private static int bottomOf(@NonNull WindowInsetsCompat insets) {
        Insets nav = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars());
        Insets gest = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures());
        return Math.max(nav.bottom, gest.bottom);
    }

    // ── Swipe-down-to-dismiss on the panel ────────────────────────────

    private void attachSwipeDownDismiss(@NonNull View target) {
        target.setOnTouchListener(new View.OnTouchListener() {
            float downY;
            boolean tracking;
            final int slop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();

            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downY = e.getRawY();
                        tracking = true;
                        return false; // don't steal child clicks
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (tracking && e.getRawY() - downY > slop * 3) {
                            dismiss();
                            tracking = false;
                            return true;
                        }
                        return false;
                    default:
                        tracking = false;
                        return false;
                }
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
