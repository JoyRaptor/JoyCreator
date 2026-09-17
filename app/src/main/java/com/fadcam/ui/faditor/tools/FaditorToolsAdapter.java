package com.fadcam.ui.faditor.tools;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.widget.TextViewCompat;

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
 * an autosized single-line ≤11sp label, gray {@code #888888}. The cell + icon +
 * label are assigned the SAME {@code R.id.*} the XML used, so all activity code
 * keeps working.</p>
 *
 * <p><b>v2 divider model + edit mode.</b> A {@link #divider} bar sits in the
 * row: cells left of it are PINNED (home row, user's manual order); cells right
 * of it are auto-sorted by usage recency. In edit mode, cells wiggle, a
 * LONG-PRESS picks a cell up (it lifts above the finger with a green vertical
 * drop line marking the target slot), near-edge auto-scroll handles multi-screen
 * drags, and dragging across the divider pins/unpins (≥1 pinned enforced). A
 * persistent "Done" control floats at the screen's right edge while editing.</p>
 */
public class FaditorToolsAdapter {

    /** Wires a freshly-built cell to its behaviour. Implemented by the activity. */
    public interface ToolBinder {
        void onBindTool(@NonNull FaditorTool tool,
                        @NonNull View cell,
                        @NonNull TextView icon,
                        @NonNull TextView label);
    }

    /** Notified when edit-mode reorders/pins change so the drawer can refresh. */
    public interface OnOrderChanged {
        void onOrderChanged();
    }

    /** Callback when the trailing edit chip is tapped to ENTER edit mode. */
    public interface OnEditToggle {
        void onEditToggle();
    }

    private final Context context;
    private final LinearLayout container;
    private final ToolBinder binder;
    private final List<View> cellViews = new ArrayList<>();
    private List<FaditorTool> tools = new ArrayList<>();

    @Nullable private FaditorToolPrefs prefs;
    @Nullable private OnOrderChanged orderChangedListener;
    @Nullable private OnEditToggle editToggleListener;

    // Edit chip (entry affordance, trailing) + persistent Done control.
    @Nullable private LinearLayout editChip;
    @Nullable private TextView editChipIcon;
    @Nullable private TextView editChipLabel;
    @Nullable private HorizontalScrollView scrollView;
    @Nullable private FrameLayout overlay;
    @Nullable private View dropLine;
    @Nullable private View doneControl;

    // Divider bar (always present; subtle normally, prominent in edit).
    @Nullable private View divider;

    private boolean editMode;
    private final List<ValueAnimator> wiggleAnimators = new ArrayList<>();

    public FaditorToolsAdapter(@NonNull LinearLayout container, @NonNull ToolBinder binder) {
        this.context = container.getContext();
        this.container = container;
        this.binder = binder;
    }

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
     * Supplies the scroll view + overlay frame so edit mode can auto-scroll and
     * float the green drop line + Done control. Also builds the persistent Done
     * control (hidden until edit mode).
     */
    public void setScrollAndOverlay(@NonNull HorizontalScrollView scroll,
                                    @NonNull FrameLayout overlayFrame) {
        this.scrollView = scroll;
        this.overlay = overlayFrame;
        buildDropLine();
        buildDoneControl();
    }

    /**
     * Rebuilds the carousel from {@code orderedTools}. {@code alwaysHidden}
     * tools (trim/heal) still get a cell (so their ids exist) but are GONE.
     * Inserts the divider after the pinned prefix.
     */
    public void setTools(@NonNull List<FaditorTool> orderedTools) {
        this.tools = new ArrayList<>(orderedTools);
        container.removeAllViews();
        cellViews.clear();
        for (FaditorTool tool : tools) {
            View cell = buildCell(tool);
            container.addView(cell);
            cellViews.add(cell);
            TextView icon = cell.findViewById(tool.iconViewId);
            TextView label = cell.findViewById(tool.labelViewId);
            binder.onBindTool(tool, cell, icon, label);
            installRecencyHook(tool, cell);
            if (tool.alwaysHidden) {
                cell.setVisibility(View.GONE);
            }
        }
        insertDivider();
        appendEditChip();
    }

    // ── Divider bar ───────────────────────────────────────────────────

    /** Creates the divider view (lazily) and positions it at the pin boundary. */
    private void insertDivider() {
        if (divider == null) {
            divider = new View(context);
            divider.setId(R.id.faditor_tools_divider);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(2), dp(44));
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.leftMargin = dp(3);
            lp.rightMargin = dp(3);
            divider.setLayoutParams(lp);
        } else if (divider.getParent() instanceof ViewGroup) {
            ((ViewGroup) divider.getParent()).removeView(divider);
        }
        applyDividerStyle();
        int idx = dividerContainerIndex();
        container.addView(divider, idx);
    }

    /**
     * Container child-index where the divider belongs = right after the last
     * visible pinned cell. Uses {@code prefs.dividerIndex} (count of visible
     * pinned tools) but maps that count through the actual child order (some
     * cells are GONE / the divider itself may already be inserted).
     */
    private int dividerContainerIndex() {
        int pinned = prefs != null ? prefs.dividerIndex(tools) : 1;
        int seenVisiblePinned = 0;
        for (int i = 0; i < tools.size(); i++) {
            View cell = cellViews.get(i);
            if (cell.getVisibility() != View.VISIBLE) continue;
            if (seenVisiblePinned >= pinned) {
                // Insert before this (the first unpinned visible cell). Convert
                // tools-index i to a container-index.
                return container.indexOfChild(cell);
            }
            seenVisiblePinned++;
        }
        // All visible cells are pinned → divider goes after the last cell,
        // before the edit chip.
        if (editChip != null && editChip.getParent() == container) {
            return container.indexOfChild(editChip);
        }
        return container.getChildCount();
    }

    private void applyDividerStyle() {
        if (divider == null) return;
        // Subtle grey normally; bright green + wider in edit mode.
        ViewGroup.LayoutParams lp = divider.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).width = dp(editMode ? 3 : 2);
            divider.setLayoutParams(lp);
        }
        divider.setBackgroundColor(editMode ? 0xFF4CAF50 : 0x33FFFFFF);
        divider.setAlpha(editMode ? 1f : 0.9f);
    }

    // ── Edit chip (entry affordance) ──────────────────────────────────

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

    /** The edit chip only ENTERS edit mode now; Done exits. Kept grey. */
    public void updateEditChip() {
        if (editChipIcon == null || editChipLabel == null) return;
        editChipIcon.setText("tune");
        editChipIcon.setTextColor(0xFF888888);
        editChipLabel.setText(R.string.faditor_tools_edit);
        editChipLabel.setTextColor(0xFF888888);
        // Hide the entry chip while editing (Done takes over), show otherwise.
        editChip.setVisibility(editMode ? View.GONE : View.VISIBLE);
    }

    // ── Recency hook ──────────────────────────────────────────────────

    private void installRecencyHook(@NonNull FaditorTool tool, @NonNull View cell) {
        if (tool.bindMode != FaditorTool.BindMode.CLICK) return;
        cell.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            final int slop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
            boolean moved;
            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getX(); downY = e.getY(); moved = false;
                        break;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getX() - downX) > slop
                                || Math.abs(e.getY() - downY) > slop) moved = true;
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                        if (!moved && !editMode) recordUse(tool.id);
                        break;
                    default:
                        break;
                }
                return false; // never consume
            }
        });
    }

    public void recordUse(@NonNull String id) {
        if (prefs != null) prefs.recordUse(id);
    }

    @NonNull
    public List<FaditorTool> getTools() {
        return tools;
    }

    @Nullable
    public View getCellForId(@NonNull String id) {
        for (int i = 0; i < tools.size(); i++) {
            if (tools.get(i).id.equals(id)) return cellViews.get(i);
        }
        return null;
    }

    // ── Edit mode ─────────────────────────────────────────────────────

    public boolean isEditMode() {
        return editMode;
    }

    public void enterEditMode() {
        if (editMode) return;
        editMode = true;
        for (View cell : cellViews) {
            if (cell.getVisibility() == View.VISIBLE) startWiggle(cell);
        }
        applyDividerStyle();
        updateEditChip();
        showDoneControl(true);
    }

    /** Commits edit mode: stops wiggle, hides Done, persists (already persisted per-drop). */
    public void exitEditMode() {
        if (!editMode) return;
        editMode = false;
        stopWiggle();
        applyDividerStyle();
        updateEditChip();
        showDoneControl(false);
        if (orderChangedListener != null) orderChangedListener.onOrderChanged();
    }

    private void startWiggle(@NonNull View cell) {
        cell.setPivotX(cell.getWidth() / 2f);
        cell.setPivotY(cell.getHeight() / 2f);
        ValueAnimator a = ValueAnimator.ofFloat(-2.5f, 2.5f);
        a.setDuration(140);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setStartDelay((long) (Math.random() * 120));
        a.addUpdateListener(an -> {
            if (cell != draggedCell) cell.setRotation((float) an.getAnimatedValue());
        });
        a.start();
        wiggleAnimators.add(a);
    }

    private void stopWiggle() {
        for (ValueAnimator a : wiggleAnimators) a.cancel();
        wiggleAnimators.clear();
        for (View cell : cellViews) cell.setRotation(0f);
    }

    /**
     * Reorders the existing (stable) cell views to the prefs-resolved order
     * WITHOUT rebuilding — so field references survive. Repositions the divider.
     */
    public void reapplyOrder() {
        if (prefs == null) return;
        applyResolvedOrder(prefs.resolveOrder(tools));
    }

    private void applyResolvedOrder(@NonNull List<FaditorTool> resolved) {
        List<FaditorTool> newTools = new ArrayList<>(resolved.size());
        List<View> newCells = new ArrayList<>(resolved.size());
        for (FaditorTool t : resolved) {
            View cell = getCellForId(t.id);
            if (cell == null) continue;
            newTools.add(t);
            newCells.add(cell);
        }
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
        insertDivider();
        if (editChip != null) container.addView(editChip); // keep chip trailing
        if (orderChangedListener != null) orderChangedListener.onOrderChanged();
    }

    // ── Drag delegate (v2: long-press pickup, lift, drop line, cross-divider) ──

    private int draggingIndex = -1;      // tools-index of the picked-up cell
    @Nullable private View draggedCell;
    private int dropSlot = -1;           // insertion slot (tools-index) under finger

    /** True if there is a draggable cell at this content-space X. */
    private boolean hasCellAt(float contentX) {
        return visibleCellIndexAtContentX(contentX) >= 0;
    }

    @NonNull
    public SwipeUpHorizontalScrollView.EditDragDelegate dragDelegate() {
        return new SwipeUpHorizontalScrollView.EditDragDelegate() {
            @Override public boolean isEditMode() { return editMode; }
            @Override public boolean hasCellAtContentX(float x) { return hasCellAt(x); }
            @Override public void onDragStart(float contentX) { beginDrag(contentX); }
            @Override public void onDragMove(float contentX, float viewportX) {
                updateDrag(contentX, viewportX);
            }
            @Override public void onDragEnd() { endDrag(); }
        };
    }

    /**
     * Visible index (over VISIBLE cells) of the divider boundary, captured at
     * drag start so it stays stable while the finger moves (the divider never
     * moves mid-drag). {@code = } the pinned count at pickup.
     */
    private int dragStartDivider = -1;

    private void beginDrag(float contentX) {
        draggingIndex = visibleCellIndexAtContentX(contentX);
        if (draggingIndex < 0) return;
        dragStartDivider = currentDividerVisibleIndex();
        draggedCell = cellViews.get(draggingIndex);
        draggedCell.setRotation(0f);
        draggedCell.animate().cancel();
        draggedCell.setElevation(dp(8));
        draggedCell.animate()
                .translationY(-dp(22)).scaleX(1.18f).scaleY(1.18f)
                .setDuration(120).start();
        dropSlot = visibleSlotForContentX(contentX);
        showDropLineAtVisibleSlot(dropSlot);
    }

    private void updateDrag(float contentX, float viewportX) {
        if (draggingIndex < 0 || draggedCell == null) return;
        // Follow finger horizontally (content space; translation, no relayout).
        float cellCenter = (draggedCell.getLeft() + draggedCell.getRight()) / 2f;
        draggedCell.setTranslationX(contentX - cellCenter);
        dropSlot = visibleSlotForContentX(contentX);
        showDropLineAtVisibleSlot(dropSlot);
        maybeAutoScroll(viewportX);
    }

    private void endDrag() {
        stopAutoScroll();
        hideDropLine();
        if (draggingIndex < 0 || draggedCell == null) {
            resetDragState();
            return;
        }
        final View cell = draggedCell;
        boolean ok = commitDrop(draggingIndex, dropSlot);
        // The reorder (commitDrop → reapplyOrder) has already placed the cell in
        // its final slot, so snap the horizontal follow-translation to 0 to avoid
        // a jump from the pre-reorder layout, then settle only the position-
        // independent lift (translationY) + scale. The cell's existing wiggle
        // animator resumes on its own once draggedCell is cleared.
        cell.setTranslationX(0f);
        cell.animate()
                .translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(160)
                .withEndAction(() -> cell.setElevation(0f))
                .start();
        if (!ok) shakeHint(cell);
        resetDragState();
    }

    private void resetDragState() {
        draggingIndex = -1;
        draggedCell = null;
        dropSlot = -1;
        dragStartDivider = -1;
    }

    /**
     * Applies the drop using pure index math (no post-move geometry needed).
     *
     * <p>Let {@code D} be the pinned count at pickup ({@link #dragStartDivider}),
     * {@code origVis} the dragged cell's visible position, and {@code dropVis}
     * the target insertion slot (visible, EXCLUDING the dragged cell). Removing
     * the dragged item drops the effective boundary to {@code effD = D-1} if it
     * was pinned. The item lands pinned iff {@code dropVis <= effD}. The new
     * pinned list is the first {@code effD + (pinned?1:0)} ids of the resulting
     * visible order. Enforces ≥1 pinned (rejects the drag that would empty it).</p>
     */
    private boolean commitDrop(int fromToolsIdx, int dropVisSlot) {
        if (prefs == null) { reapplyOrder(); return true; }

        List<String> origVisibleIds = visibleIds();
        int draggedVis = visiblePosOfToolsIndex(fromToolsIdx);
        if (draggedVis < 0) { reapplyOrder(); return true; }
        String draggedId = tools.get(fromToolsIdx).id;

        int D = dragStartDivider >= 0 ? dragStartDivider : currentDividerVisibleIndex();

        // Visible order with the dragged id removed.
        List<String> reduced = new ArrayList<>(origVisibleIds);
        reduced.remove(draggedVis);

        int effD = (draggedVis < D) ? D - 1 : D;
        // Clamp the insertion slot into the reduced list; the incoming dropVis
        // counts visible cells before it INCLUDING the dragged cell's old slot,
        // so translate: if inserting past the dragged old position, shift down 1.
        int insVis = dropVisSlot;
        if (insVis > draggedVis) insVis -= 1;
        if (insVis < 0) insVis = 0;
        if (insVis > reduced.size()) insVis = reduced.size();

        boolean draggedPinned = insVis <= effD;
        int newD = effD + (draggedPinned ? 1 : 0);
        if (newD <= 0) {
            // Would empty the pinned side → reject.
            reapplyOrder();
            return false;
        }

        // Final visible order.
        List<String> finalVisible = new ArrayList<>(reduced);
        finalVisible.add(insVis, draggedId);

        // Left section = first newD ids.
        List<String> newLeft = new ArrayList<>(finalVisible.subList(0, Math.min(newD, finalVisible.size())));
        if (!prefs.commitLeftSection(newLeft)) {
            reapplyOrder();
            return false;
        }
        // For the RIGHT (usage) section, honour the exact dropped order for this
        // gesture by bumping recency so the resolved order matches what the user
        // just arranged left→right (most-recent = leftmost of the right side).
        writeRightSectionRecency(finalVisible, newD);
        reapplyOrder();
        return true;
    }

    /**
     * The right (usage) section is recency-sorted, so a manual drop there would
     * otherwise snap back to usage order. To make a drop into the right side
     * "stick" for this gesture, stamp descending recency across the right ids in
     * their dropped left→right order (leftmost = most recent). Pinned ids are
     * untouched.
     */
    private void writeRightSectionRecency(@NonNull List<String> finalVisible, int newD) {
        if (prefs == null) return;
        long base = System.currentTimeMillis();
        int n = finalVisible.size();
        for (int i = newD; i < n; i++) {
            // Leftmost right-side id gets the largest timestamp.
            prefs.stampRecency(finalVisible.get(i), base - (i - newD));
        }
    }

    // ── Visible-order helpers (index math, no geometry) ───────────────

    @NonNull
    private List<String> visibleIds() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            if (cellViews.get(i).getVisibility() == View.VISIBLE) out.add(tools.get(i).id);
        }
        return out;
    }

    /** Visible position (over VISIBLE cells) of the given tools-index, or -1. */
    private int visiblePosOfToolsIndex(int toolsIdx) {
        int vis = 0;
        for (int i = 0; i < tools.size(); i++) {
            if (cellViews.get(i).getVisibility() != View.VISIBLE) continue;
            if (i == toolsIdx) return vis;
            vis++;
        }
        return -1;
    }

    /** Current divider boundary as a visible index (pinned count). */
    private int currentDividerVisibleIndex() {
        if (prefs != null) return prefs.dividerIndex(tools);
        return 1;
    }

    // ── Slot / hit-testing in content space ───────────────────────────

    /** tools-index of the VISIBLE cell whose span contains contentX, or -1. */
    private int visibleCellIndexAtContentX(float contentX) {
        for (int i = 0; i < cellViews.size(); i++) {
            View c = cellViews.get(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            if (contentX >= c.getLeft() && contentX <= c.getRight()) return i;
        }
        return -1;
    }

    /**
     * Insertion slot as a VISIBLE index (0..visibleCount): how many visible
     * cells' midpoints sit left of {@code contentX}. Layout positions are used
     * (unaffected by the dragged cell's live translation), so the dragged cell
     * is counted at its ORIGINAL slot — which is exactly what {@link #commitDrop}
     * expects when it translates the slot by the dragged position.
     */
    private int visibleSlotForContentX(float contentX) {
        int slot = 0;
        for (int i = 0; i < cellViews.size(); i++) {
            View c = cellViews.get(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            float mid = (c.getLeft() + c.getRight()) / 2f;
            if (contentX > mid) slot++;
        }
        return slot;
    }

    // ── Green drop line (overlay, screen space) ───────────────────────

    private void buildDropLine() {
        if (overlay == null || dropLine != null) return;
        dropLine = new View(context);
        dropLine.setId(R.id.faditor_tools_drop_line);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(3), dp(52));
        lp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        dropLine.setLayoutParams(lp);
        dropLine.setBackgroundColor(0xFF4CAF50);
        dropLine.setElevation(dp(10));
        dropLine.setVisibility(View.GONE);
        overlay.addView(dropLine);
    }

    /** Positions + shows the green line at the gap for VISIBLE insertion slot. */
    private void showDropLineAtVisibleSlot(int visSlot) {
        if (dropLine == null || scrollView == null) return;
        float contentX = gapContentXForVisibleSlot(visSlot);
        float viewportX = contentX - scrollView.getScrollX();
        dropLine.setTranslationX(viewportX - dp(1.5f));
        dropLine.setVisibility(View.VISIBLE);
    }

    private void hideDropLine() {
        if (dropLine != null) dropLine.setVisibility(View.GONE);
    }

    /** Content-space X of the gap at VISIBLE insertion slot {@code visSlot}. */
    private float gapContentXForVisibleSlot(int visSlot) {
        List<View> visible = new ArrayList<>();
        for (View c : cellViews) if (c.getVisibility() == View.VISIBLE) visible.add(c);
        if (visible.isEmpty()) return 0;
        if (visSlot <= 0) return visible.get(0).getLeft() - dp(2);
        if (visSlot >= visible.size()) {
            View last = visible.get(visible.size() - 1);
            return last.getRight() + dp(2);
        }
        View prev = visible.get(visSlot - 1);
        View next = visible.get(visSlot);
        return (prev.getRight() + next.getLeft()) / 2f;
    }

    // ── Near-edge auto-scroll ─────────────────────────────────────────

    @Nullable private ValueAnimator autoScrollAnim;
    private int autoScrollDir; // -1 left, +1 right, 0 none

    private void maybeAutoScroll(float viewportX) {
        if (scrollView == null) return;
        int w = scrollView.getWidth();
        int edge = dp(56);
        int dir = 0;
        if (viewportX < edge) dir = -1;
        else if (viewportX > w - edge) dir = +1;
        if (dir == autoScrollDir) return;
        autoScrollDir = dir;
        stopAutoScroll();
        if (dir == 0) return;
        // Accelerate: step size scales with how deep into the edge zone we are.
        autoScrollAnim = ValueAnimator.ofFloat(0f, 1f);
        autoScrollAnim.setDuration(16);
        autoScrollAnim.setRepeatCount(ValueAnimator.INFINITE);
        final int direction = dir;
        autoScrollAnim.addUpdateListener(a -> {
            if (scrollView == null || draggedCell == null) return;
            int max = maxScroll();
            int cur = scrollView.getScrollX();
            int step = dp(10) * direction;
            int next = Math.max(0, Math.min(max, cur + step));
            if (next != cur) {
                scrollView.scrollTo(next, 0);
                // Keep the green line under the finger as we scroll.
                if (dropSlot >= 0) showDropLineAtVisibleSlot(dropSlot);
            }
        });
        autoScrollAnim.start();
    }

    private int maxScroll() {
        if (scrollView == null || scrollView.getChildCount() == 0) return 0;
        View child = scrollView.getChildAt(0);
        return Math.max(0, child.getWidth() - scrollView.getWidth());
    }

    private void stopAutoScroll() {
        if (autoScrollAnim != null) {
            autoScrollAnim.cancel();
            autoScrollAnim = null;
        }
        autoScrollDir = 0;
    }

    // ── Persistent Done control (screen-edge overlay) ─────────────────

    private void buildDoneControl() {
        if (overlay == null || doneControl != null) return;
        LinearLayout done = new LinearLayout(context);
        done.setId(R.id.faditor_tools_done);
        done.setOrientation(LinearLayout.HORIZONTAL);
        done.setGravity(Gravity.CENTER);
        int padH = dp(14);
        int padV = dp(8);
        done.setPadding(padH, padV, padH, padV);
        done.setBackgroundColor(0xF2101010);
        done.setElevation(dp(12));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        done.setLayoutParams(lp);
        done.setClickable(true);
        done.setFocusable(true);

        TextView icon = new TextView(context);
        icon.setTypeface(ResourcesCompat.getFont(context, R.font.materialicons));
        icon.setText("check");
        icon.setTextColor(0xFF4CAF50);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        icon.setGravity(Gravity.CENTER);
        done.addView(icon);

        TextView label = new TextView(context);
        label.setText(R.string.faditor_tools_edit_done);
        label.setTextColor(0xFF4CAF50);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lblLp.leftMargin = dp(6);
        label.setLayoutParams(lblLp);
        done.addView(label);

        done.setOnClickListener(v -> {
            if (editToggleListener != null) editToggleListener.onEditToggle();
        });
        done.setVisibility(View.GONE);
        overlay.addView(done);
        doneControl = done;
    }

    private void showDoneControl(boolean show) {
        if (doneControl == null) return;
        if (show) {
            doneControl.setVisibility(View.VISIBLE);
            doneControl.setAlpha(0f);
            doneControl.setTranslationX(dp(24));
            doneControl.animate().alpha(1f).translationX(0f).setDuration(160).start();
        } else {
            doneControl.animate().alpha(0f).translationX(dp(24)).setDuration(140)
                    .withEndAction(() -> doneControl.setVisibility(View.GONE)).start();
        }
    }

    // ── Snap-back hint (rejected drop) ────────────────────────────────

    private void shakeHint(@NonNull View cell) {
        // Flash the divider wider to signal the "≥1 pinned" rule kept the item.
        if (divider != null) {
            divider.animate().cancel();
            divider.setScaleX(1f);
            ValueAnimator flash = ValueAnimator.ofFloat(1f, 2.6f, 1f);
            flash.setDuration(380);
            flash.addUpdateListener(an ->
                    divider.setScaleX((float) an.getAnimatedValue()));
            flash.start();
        }
    }

    // ── Cell construction ─────────────────────────────────────────────

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
        cell.setClickable(true);
        cell.setFocusable(true);

        TextView icon = new TextView(context);
        icon.setId(tool.iconViewId);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        icon.setLayoutParams(iconLp);
        icon.setGravity(Gravity.CENTER);
        // Via applyIcon, so a tool whose mark is a WORD ("text:FX") renders as bold letters
        // rather than as the literal string "text:FX" in the icon font.
        FaditorTool.applyIcon(icon, tool.icon, 22f);
        icon.setTextColor(0xFF888888);
        cell.addView(icon);

        TextView label = new TextView(context);
        label.setId(tool.labelViewId);
        // Autosize is IGNORED when the autosized dimension is WRAP_CONTENT (documented
        // AppCompat behaviour), so "Transcript" was clipped mid-word rather than shrunk.
        // A bounded height is what makes the 7-11sp range actually engage.
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                dp(68), dp(14));
        labelLp.topMargin = dp(2);
        label.setLayoutParams(labelLp);
        label.setText(tool.label);
        label.setTextColor(0xFF888888);
        label.setGravity(Gravity.CENTER);
        // Spec 1: labels never wrap — single line, autosized down to fit the
        // fixed cell width. "transitions"/"transcript" previously wrapped.
        label.setMaxLines(1);
        label.setSingleLine(true);
        label.setEllipsize(null);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                label, 7, 11, 1, TypedValue.COMPLEX_UNIT_SP);
        cell.addView(label);

        // SPEC_20260829_QUICK_WINS S1: Image overlay is common; image-as-clip (spine)
        // is rare and reachable via long-press on this same button. Add a tiny chevron
        // so the gesture is discoverable -- a hidden gesture with no affordance is the
        // same as a deleted feature.
        if ("sticker".equals(tool.id)) {
            TextView chevron = new TextView(context);
            LinearLayout.LayoutParams chevronLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chevronLp.topMargin = dp(1);
            chevronLp.gravity = Gravity.CENTER_HORIZONTAL;
            chevron.setLayoutParams(chevronLp);
            chevron.setTypeface(ResourcesCompat.getFont(context, R.font.materialicons));
            chevron.setText("expand_more");
            chevron.setTextColor(0xFF666666);
            chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            chevron.setGravity(Gravity.CENTER);
            chevron.setAlpha(0.85f);
            // Content description for accessibility: hints long-press.
            chevron.setContentDescription("Long-press for Image as clip");
            cell.addView(chevron);
        }

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

    private float dp(float v) {
        return v * context.getResources().getDisplayMetrics().density;
    }
}
