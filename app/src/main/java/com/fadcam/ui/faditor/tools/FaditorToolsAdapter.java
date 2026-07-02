package com.fadcam.ui.faditor.tools;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the data-driven Faditor bottom-tools carousel into a horizontal
 * {@link LinearLayout} container. This deliberately does NOT recycle views:
 * the carousel holds only ~25 small cells and {@code FaditorEditorActivity}
 * keeps long-lived field references + {@code findViewById(R.id.tool_*_icon)}
 * lookups into these cells (updating icon/label/color as the selection
 * changes), so every cell must be a stable, always-attached view.
 *
 * <p>Each generated cell is structurally identical to the old hardcoded
 * {@code tool_*} block: a 72dp-wide vertical {@link LinearLayout} with 8dp
 * padding, a borderless-ripple background, a 28dp materialicons 22sp icon and
 * an 11sp label, gray {@code #888888}. The cell + icon + label are assigned
 * the SAME {@code R.id.*} the XML used, so all activity code keeps working.</p>
 */
public class FaditorToolsAdapter {

    /** Wires a freshly-built cell to its behaviour. Implemented by the activity. */
    public interface ToolBinder {
        /**
         * Called once per (visible) tool after its cell view is created and
         * added to the container. The activity re-captures its field
         * references from {@code cell}/{@code icon}/{@code label} and installs
         * the click or custom touch listener appropriate to the tool.
         */
        void onBindTool(@NonNull FaditorTool tool,
                        @NonNull View cell,
                        @NonNull TextView icon,
                        @NonNull TextView label);
    }

    /** Notified when edit-mode reorders/pins change so the drawer can refresh. */
    public interface OnOrderChanged {
        void onOrderChanged();
    }

    private final Context context;
    private final LinearLayout container;
    private final ToolBinder binder;
    private final List<View> cellViews = new ArrayList<>();
    private List<FaditorTool> tools = new ArrayList<>();

    /** Callback when the trailing edit chip is tapped (toggles edit mode). */
    public interface OnEditToggle {
        void onEditToggle();
    }

    // Stage 3 state.
    @Nullable private FaditorToolPrefs prefs;
    @Nullable private OnOrderChanged orderChangedListener;
    @Nullable private OnEditToggle editToggleListener;
    @Nullable private LinearLayout editChip;
    @Nullable private TextView editChipIcon;
    @Nullable private TextView editChipLabel;
    private boolean editMode;
    private final List<ValueAnimator> wiggleAnimators = new ArrayList<>();
    private final java.util.Map<String, TextView> pinMarkers = new java.util.HashMap<>();

    public FaditorToolsAdapter(@NonNull LinearLayout container, @NonNull ToolBinder binder) {
        this.context = container.getContext();
        this.container = container;
        this.binder = binder;
    }

    /** Supplies the persistence helper enabling Stage 3 order/pin/recency. */
    public void setPrefs(@NonNull FaditorToolPrefs prefs) {
        this.prefs = prefs;
    }

    public void setOnOrderChanged(@Nullable OnOrderChanged l) {
        this.orderChangedListener = l;
    }

    public void setOnEditToggle(@Nullable OnEditToggle l) {
        this.editToggleListener = l;
    }

    /**
     * Rebuilds the carousel from {@code orderedTools}. {@code alwaysHidden}
     * tools (trim/heal) still get a cell created (so their ids exist and the
     * activity can look them up) but the cell is set to {@code GONE}, matching
     * the old {@code android:visibility="gone"}.
     */
    public void setTools(@NonNull List<FaditorTool> orderedTools) {
        this.tools = new ArrayList<>(orderedTools);
        container.removeAllViews();
        cellViews.clear();
        pinMarkers.clear();
        for (FaditorTool tool : tools) {
            View cell = buildCell(tool);
            container.addView(cell);
            cellViews.add(cell);
            TextView icon = cell.findViewById(tool.iconViewId);
            TextView label = cell.findViewById(tool.labelViewId);
            binder.onBindTool(tool, cell, icon, label);
            installRecencyHook(tool, cell);
            final FaditorTool fTool = tool;
            cell.setOnLongClickListener(v -> {
                if (editMode) {
                    v.performHapticFeedback(
                            android.view.HapticFeedbackConstants.LONG_PRESS);
                    togglePin(fTool.id);
                    return true;
                }
                return false;
            });
            refreshPinMarker(tool);
            if (tool.alwaysHidden) {
                cell.setVisibility(View.GONE);
            }
        }
        appendEditChip();
    }

    /** Builds and appends the trailing edit-mode toggle chip. */
    private void appendEditChip() {
        editChip = new LinearLayout(context);
        editChip.setId(R.id.faditor_tools_edit_chip);
        editChip.setOrientation(LinearLayout.VERTICAL);
        editChip.setGravity(Gravity.CENTER);
        int pad = dp(8);
        editChip.setPadding(pad, pad, pad, pad);
        editChip.setBackgroundResource(resolveSelectableBorderless());
        editChip.setLayoutParams(new LinearLayout.LayoutParams(
                dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        editChip.setClickable(true);
        editChip.setFocusable(true);

        editChipIcon = new TextView(context);
        editChipIcon.setId(R.id.faditor_tools_edit_chip_icon);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        editChipIcon.setLayoutParams(iconLp);
        editChipIcon.setGravity(Gravity.CENTER);
        editChipIcon.setTypeface(ResourcesCompat.getFont(context, R.font.materialicons));
        editChipIcon.setText("tune");
        editChipIcon.setTextColor(0xFF888888);
        editChipIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        editChip.addView(editChipIcon);

        editChipLabel = new TextView(context);
        LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lblLp.topMargin = dp(2);
        editChipLabel.setLayoutParams(lblLp);
        editChipLabel.setText(R.string.faditor_tools_edit);
        editChipLabel.setTextColor(0xFF888888);
        editChipLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        editChip.addView(editChipLabel);

        editChip.setOnClickListener(v -> {
            if (editToggleListener != null) editToggleListener.onEditToggle();
        });
        container.addView(editChip);
    }

    /** Updates the edit chip's appearance for the current edit-mode state. */
    public void updateEditChip() {
        if (editChipIcon == null || editChipLabel == null) return;
        int color = editMode ? 0xFF4CAF50 : 0xFF888888;
        editChipIcon.setText(editMode ? "check" : "tune");
        editChipIcon.setTextColor(color);
        editChipLabel.setText(editMode
                ? R.string.faditor_tools_edit_done : R.string.faditor_tools_edit);
        editChipLabel.setTextColor(color);
    }

    /**
     * Non-consuming touch hook that records "tool used" for RECENT ordering
     * when a cell is tapped (not dragged). Returns false so the cell's own
     * click / the mute-opacity touch listeners still run. For mute/opacity the
     * activity installs its own OnTouchListener AFTER binding, which replaces
     * this — those two record recency from their tap handlers instead.
     */
    private void installRecencyHook(@NonNull FaditorTool tool, @NonNull View cell) {
        if (tool.bindMode != FaditorTool.BindMode.CLICK) return;
        cell.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            final int slop = ViewConfiguration.get(context).getScaledTouchSlop();
            boolean moved;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX(); downY = e.getY(); moved = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getX() - downX) > slop
                                || Math.abs(e.getY() - downY) > slop) moved = true;
                        break;
                    case MotionEvent.ACTION_UP:
                        if (!moved && !editMode) recordUse(tool.id);
                        break;
                    default:
                        break;
                }
                return false; // never consume
            }
        });
    }

    /** Records recency (used by the activity for mute/opacity tap paths too). */
    public void recordUse(@NonNull String id) {
        if (prefs != null) prefs.recordUse(id);
    }

    /** Current ordered tool list backing the carousel. */
    @NonNull
    public List<FaditorTool> getTools() {
        return tools;
    }

    /** The cell view for a given tool id, or null if not present. */
    @Nullable
    public View getCellForId(@NonNull String id) {
        for (int i = 0; i < tools.size(); i++) {
            if (tools.get(i).id.equals(id)) return cellViews.get(i);
        }
        return null;
    }

    // ── Stage 3: edit mode (wiggle + drag reorder + pin) ─────────────────

    public boolean isEditMode() {
        return editMode;
    }

    /** Enters edit mode: cells wiggle; drag reorders; long-press pins. */
    public void enterEditMode() {
        if (editMode) return;
        editMode = true;
        for (View cell : cellViews) {
            if (cell.getVisibility() == View.VISIBLE) startWiggle(cell);
        }
        updateEditChip();
    }

    /** Commits edit mode: stops wiggle and persists the current order. */
    public void exitEditMode() {
        if (!editMode) return;
        editMode = false;
        stopWiggle();
        persistManualOrder();
        updateEditChip();
    }

    /** Persists the current on-screen order as the manual order. */
    private void persistManualOrder() {
        if (prefs == null) return;
        List<String> order = new ArrayList<>();
        for (FaditorTool t : tools) order.add(t.id);
        prefs.setManualOrder(order);
        if (orderChangedListener != null) orderChangedListener.onOrderChanged();
    }

    private void startWiggle(@NonNull View cell) {
        cell.setPivotX(cell.getWidth() / 2f);
        cell.setPivotY(cell.getHeight() / 2f);
        ValueAnimator a = ValueAnimator.ofFloat(-2.5f, 2.5f);
        a.setDuration(140);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        // Slight per-cell phase so they don't wiggle in lockstep.
        a.setStartDelay((long) (Math.random() * 120));
        a.addUpdateListener(an -> cell.setRotation((float) an.getAnimatedValue()));
        a.start();
        wiggleAnimators.add(a);
    }

    private void stopWiggle() {
        for (ValueAnimator a : wiggleAnimators) a.cancel();
        wiggleAnimators.clear();
        for (View cell : cellViews) cell.setRotation(0f);
    }

    /** Shows/hides the pin marker for a tool per its persisted pinned state. */
    public void refreshPinMarker(@NonNull FaditorTool tool) {
        TextView marker = pinMarkers.get(tool.id);
        if (marker == null) return;
        boolean pinned = prefs != null && prefs.isPinned(tool.id);
        marker.setVisibility(pinned ? View.VISIBLE : View.GONE);
    }

    /** Toggles pin for a tool, updates its marker, and re-sorts pinned first. */
    public void togglePin(@NonNull String id) {
        if (prefs == null) return;
        prefs.togglePin(id);
        for (FaditorTool t : tools) refreshPinMarker(t);
        // Re-apply ordering so pinned tools jump to the front immediately.
        reapplyOrder();
    }

    /**
     * Reorders the existing (stable) cell views in the container to match the
     * prefs-resolved order WITHOUT rebuilding — so field references survive.
     * Preserves edit-mode wiggle.
     */
    public void reapplyOrder() {
        if (prefs == null) return;
        List<FaditorTool> resolved = prefs.resolveOrder(tools);
        applyResolvedOrder(resolved);
    }

    private void applyResolvedOrder(@NonNull List<FaditorTool> resolved) {
        // Rebuild the tools/cellViews lists to the new order using the SAME
        // cell view instances, then re-add them to the container in order.
        List<FaditorTool> newTools = new ArrayList<>(resolved.size());
        List<View> newCells = new ArrayList<>(resolved.size());
        for (FaditorTool t : resolved) {
            View cell = getCellForId(t.id);
            if (cell == null) continue;
            newTools.add(t);
            newCells.add(cell);
        }
        // Include any tools not in resolved (safety) at the end.
        for (int i = 0; i < tools.size(); i++) {
            if (!newTools.contains(tools.get(i))) {
                newTools.add(tools.get(i));
                newCells.add(cellViews.get(i));
            }
        }
        tools = newTools;
        cellViews.clear();
        cellViews.addAll(newCells);
        container.removeAllViews();
        for (View c : cellViews) container.addView(c);
        if (editChip != null) container.addView(editChip); // keep chip trailing
        if (orderChangedListener != null) orderChangedListener.onOrderChanged();
    }

    /**
     * Moves the tool cell at {@code from} to {@code to} in the on-screen order
     * (used by drag reorder). Operates on the stable views; commit persists on
     * {@link #exitEditMode}.
     */
    public void moveTool(int from, int to) {
        if (from < 0 || to < 0 || from >= tools.size() || to >= tools.size() || from == to) return;
        FaditorTool t = tools.remove(from);
        View v = cellViews.remove(from);
        tools.add(to, t);
        cellViews.add(to, v);
        container.removeViewAt(from);
        container.addView(v, to);
    }

    /** Index of the cell in the container, or -1. */
    public int indexOfCell(@NonNull View cell) {
        return cellViews.indexOf(cell);
    }

    // ── Edit-mode drag delegate ──────────────────────────────────────────

    private int draggingIndex = -1;

    /** Returns an {@link SwipeUpHorizontalScrollView.EditDragDelegate} for drag reorder. */
    @NonNull
    public SwipeUpHorizontalScrollView.EditDragDelegate dragDelegate() {
        return new SwipeUpHorizontalScrollView.EditDragDelegate() {
            @Override public boolean isActive() { return editMode; }

            @Override
            public void onDragStart(float contentX, float y) {
                draggingIndex = indexAtContentX(contentX);
                if (draggingIndex >= 0) {
                    View v = cellViews.get(draggingIndex);
                    v.setAlpha(0.7f);
                    v.setScaleX(1.12f);
                    v.setScaleY(1.12f);
                }
            }

            @Override
            public void onDragMove(float contentX, float y) {
                if (draggingIndex < 0) return;
                int target = indexAtContentX(contentX);
                if (target >= 0 && target != draggingIndex
                        && !tools.get(target).alwaysHidden) {
                    moveTool(draggingIndex, target);
                    draggingIndex = target;
                }
            }

            @Override
            public void onDragEnd() {
                if (draggingIndex >= 0 && draggingIndex < cellViews.size()) {
                    View v = cellViews.get(draggingIndex);
                    v.setAlpha(1f);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                }
                draggingIndex = -1;
                persistManualOrder();
            }
        };
    }

    /** Finds the visible cell index whose horizontal span contains contentX. */
    private int indexAtContentX(float contentX) {
        for (int i = 0; i < cellViews.size(); i++) {
            View c = cellViews.get(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            if (contentX >= c.getLeft() && contentX <= c.getRight()) return i;
        }
        return -1;
    }

    // ── Cell construction (mirrors the old XML tool_* block exactly) ──────

    private View buildCell(@NonNull FaditorTool tool) {
        LinearLayout cell = new LinearLayout(context);
        cell.setId(tool.viewId);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        int pad = dp(8);
        cell.setPadding(pad, pad, pad, pad);
        cell.setBackgroundResource(resolveSelectableBorderless());
        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                dp(72), ViewGroup.LayoutParams.WRAP_CONTENT);
        cell.setLayoutParams(cellLp);
        // Match old cells being clickable targets.
        cell.setClickable(true);
        cell.setFocusable(true);

        // Pin marker (Stage 3): small amber push-pin above the icon, hidden
        // unless the tool is pinned. Kept as the first child so it doesn't
        // shift the icon/label baseline much (it occupies a thin top row).
        TextView pin = new TextView(context);
        pin.setTypeface(ResourcesCompat.getFont(context, R.font.materialicons));
        pin.setText("push_pin");
        pin.setTextColor(0xFFFFC107);
        pin.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        pin.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(12));
        pinLp.gravity = Gravity.CENTER_HORIZONTAL;
        pin.setLayoutParams(pinLp);
        pin.setVisibility(View.GONE);
        cell.addView(pin);
        pinMarkers.put(tool.id, pin);

        TextView icon = new TextView(context);
        icon.setId(tool.iconViewId);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        icon.setLayoutParams(iconLp);
        icon.setGravity(Gravity.CENTER);
        icon.setTypeface(ResourcesCompat.getFont(context, R.font.materialicons));
        icon.setText(tool.icon);
        icon.setTextColor(0xFF888888);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        cell.addView(icon);

        TextView label = new TextView(context);
        label.setId(tool.labelViewId);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(2);
        label.setLayoutParams(labelLp);
        label.setText(tool.label);
        label.setTextColor(0xFF888888);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        cell.addView(label);

        return cell;
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
