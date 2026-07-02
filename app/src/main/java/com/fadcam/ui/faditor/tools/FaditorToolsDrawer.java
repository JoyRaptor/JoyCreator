package com.fadcam.ui.faditor.tools;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;

import java.util.List;

/**
 * Stage 2 — swipe-up "all tools" drawer for the Faditor editor.
 *
 * <p>A full-width overlay (dim scrim + bottom sheet-style panel) that shows
 * every carousel tool in a grid, each with its label underneath. It is a
 * power-user surface and may cover the video. Tapping a tool re-fires the SAME
 * carousel cell (via {@link FaditorToolsAdapter#getCellForId} +
 * {@code performClick()}), so all existing handlers — including the mute /
 * opacity touch tools' tap paths — run unchanged, then the drawer dismisses.
 * Swipe-down on the panel or tapping the scrim dismisses.</p>
 *
 * <p>Context is respected: tools whose carousel cell is {@code GONE} for the
 * current selection (currently only the always-hidden {@code trim}/{@code
 * heal}) are omitted from the grid too.</p>
 */
public class FaditorToolsDrawer {

    public interface OnToolChosen {
        /** Called with the chosen tool id just before the drawer dismisses. */
        void onToolChosen(@NonNull String toolId);
    }

    private static final int COLUMNS = 4;

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
        // Animate scrim fade + panel slide up.
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(180).start();
        panel.post(() -> {
            float h = panel.getHeight();
            panel.setTranslationY(h);
            ObjectAnimator a = ObjectAnimator.ofFloat(panel, "translationY", h, 0f);
            a.setDuration(240);
            a.setInterpolator(new DecelerateInterpolator());
            a.start();
        });
    }

    /** Animates the drawer out and removes it. */
    public void dismiss() {
        if (!showing || overlay == null) return;
        showing = false;
        final FrameLayout toRemove = overlay;
        scrim.animate().alpha(0f).setDuration(160).start();
        float h = panel.getHeight();
        ObjectAnimator a = ObjectAnimator.ofFloat(panel, "translationY", 0f, h);
        a.setDuration(200);
        a.setInterpolator(new DecelerateInterpolator());
        a.start();
        panel.postDelayed(() -> {
            root.removeView(toRemove);
            if (overlay == toRemove) overlay = null;
        }, 210);
    }

    // ── Build ─────────────────────────────────────────────────────────

    private void buildOverlay() {
        overlay = new FrameLayout(context);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setClickable(true);
        overlay.setFocusable(true);

        // Dim scrim — tap to dismiss.
        scrim = new View(context);
        scrim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.setBackgroundColor(0xB3000000);
        scrim.setOnClickListener(v -> dismiss());
        overlay.addView(scrim);

        // Bottom panel.
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.BOTTOM;
        panel.setLayoutParams(panelLp);
        panel.setBackgroundColor(0xFF141414);
        panel.setPadding(dp(8), dp(10), dp(8), dp(24));
        panel.setClickable(true);
        // Swipe-down on the panel dismisses.
        attachSwipeDownDismiss(panel);
        overlay.addView(panel);

        // Grab handle.
        View handle = new View(context);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dp(6);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundColor(0xFF555555);
        panel.addView(handle);

        // Title.
        TextView title = new TextView(context);
        title.setText(R.string.faditor_tools_all_title);
        title.setTextColor(0xFFCCCCCC);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        title.setPadding(dp(8), dp(2), dp(8), dp(8));
        panel.addView(title);

        // Scrollable grid of tools.
        ScrollView scroll = new ScrollView(context);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        scroll.setLayoutParams(scrollLp);
        // Cap height so a huge tool set stays scrollable and doesn't fill the screen.
        int maxH = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.5f);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(COLUMNS);
        grid.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        List<FaditorTool> tools = adapter.getTools();
        for (FaditorTool tool : tools) {
            if (tool.alwaysHidden) continue;
            View cell = adapter.getCellForId(tool.id);
            // Skip tools currently hidden in the carousel (context filtering).
            if (cell != null && cell.getVisibility() != View.VISIBLE) continue;
            grid.addView(buildGridCell(tool));
        }

        scroll.addView(grid);
        panel.addView(scroll);
        // Enforce max height after layout.
        scroll.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                        if (scroll.getHeight() > maxH) {
                            ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                            lp.height = maxH;
                            scroll.setLayoutParams(lp);
                        }
                        return true;
                    }
                });
    }

    private View buildGridCell(@NonNull FaditorTool tool) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(6), dp(12), dp(6), dp(12));
        cell.setBackgroundResource(resolveSelectableBorderless());
        cell.setClickable(true);
        cell.setFocusable(true);

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        cell.setLayoutParams(lp);

        // Reflect the CURRENT icon/label/color of the live carousel cell so the
        // drawer mirrors dynamic state (e.g. "6.5x" green speed, "151%" red).
        View liveCell = adapter.getCellForId(tool.id);
        TextView liveIcon = liveCell != null ? liveCell.findViewById(tool.iconViewId) : null;
        TextView liveLabel = liveCell != null ? liveCell.findViewById(tool.labelViewId) : null;

        TextView icon = new TextView(context);
        icon.setGravity(Gravity.CENTER);
        icon.setTypeface(ResourcesCompat.getFont(context, R.font.materialicons));
        icon.setText(liveIcon != null ? liveIcon.getText() : tool.icon);
        icon.setTextColor(liveIcon != null ? liveIcon.getCurrentTextColor() : 0xFF888888);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        iconLp.gravity = Gravity.CENTER_HORIZONTAL;
        icon.setLayoutParams(iconLp);
        cell.addView(icon);

        TextView label = new TextView(context);
        label.setGravity(Gravity.CENTER);
        label.setText(liveLabel != null ? liveLabel.getText() : tool.label);
        label.setTextColor(liveLabel != null ? liveLabel.getCurrentTextColor() : 0xFF888888);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        label.setMaxLines(2);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(4);
        label.setLayoutParams(labelLp);
        cell.addView(label);

        cell.setOnClickListener(v -> {
            callback.onToolChosen(tool.id);
            View target = adapter.getCellForId(tool.id);
            if (target != null) target.performClick();
            dismiss();
        });
        return cell;
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

    private int resolveSelectableBorderless() {
        TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true);
        return tv.resourceId;
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
