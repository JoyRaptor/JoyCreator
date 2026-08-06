package com.fadcam.ui.faditor.sprite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;

import java.util.List;

/**
 * S3 (PLAN_SPRITE_ANIMATION): the sprite bottom panel — Build 1 ships the
 * MICRO and PALETTE detents (the dope-sheet detent is fast-follow A). Reuses
 * the {@code AssetBrowserPanel} overlay idiom: added to android.R.id.content,
 * scrim-tap collapses, grab-handle drag snaps between detents. The panel is a
 * pure VIEW over the model — every mutation goes through the {@link Callback}
 * so the activity owns undo/persist/preview refresh (single write path).
 *
 * <ul>
 *   <li>MICRO: ◄ ▶ frame-step + live cell indicator — playback review at
 *       near-zero footprint; video is never covered (plan decision 3).</li>
 *   <li>PALETTE: sprite-instance chips + ⚙ manage-sheets + delete; a cell-chip
 *       carousel (live thumbnails off the shared decoded sheet) where tapping
 *       a cell DROPS A SWAP at the playhead; Flip H/V + end-behavior cycle.</li>
 *   <li>Empty state: one "+ Load sprite sheet" affordance.</li>
 * </ul>
 */
public class SpritePalettePanel extends FrameLayout {

    public interface Callback {
        /** Tap a cell chip: drop/replace a frame-swap on the selected sprite at the playhead. */
        void onCellChipTapped(@NonNull SpriteOverlayItem item, int cellIndex);
        void onInstanceSelected(@NonNull SpriteOverlayItem item);
        void onFlipH(@NonNull SpriteOverlayItem item);
        void onFlipV(@NonNull SpriteOverlayItem item);
        /** Cycle hold → loop → pingpong → hold. */
        void onEndBehaviorCycled(@NonNull SpriteOverlayItem item);
        void onDeleteInstance(@NonNull SpriteOverlayItem item);
        /** Open the sheet manager (load a new sheet / place another sprite). */
        void onManageSheets();
        /** Step the REAL timeline playhead by one sheet-fps frame. */
        void onFrameStep(int direction);
        void onPanelCollapsed();
        @Nullable SpriteSheet lookupSheet(@NonNull String sheetId);
        @Nullable SpriteSheetRenderer lookupRenderer(@NonNull String sheetId);
        /** Nudge the key at (or near) the playhead by ±100ms. */
        default void onNudgeKey(@NonNull SpriteOverlayItem item, int direction) {}
        /** Delete the key at (or near) the playhead. */
        default void onDeleteKeyAtPlayhead(@NonNull SpriteOverlayItem item) {}

        /** Avatar item (bake-to-keyframes): start/stop recording a face-tracked
         *  performance onto the item. Chip shows only when a rig is linked. */
        default void onRecordPerformance(@NonNull SpriteOverlayItem item) {}

        /** True while {@code item} is the one being recorded (chip state). */
        default boolean isRecordingPerformance(@NonNull SpriteOverlayItem item) {
            return false;
        }

        /** Avatar item (point-at-video): sweep the clip under the item through
         *  VIDEO-mode face tracking into its performance track. */
        default void onSweepFromVideo(@NonNull SpriteOverlayItem item) {}

        /** Plain-sprite dope-sheet preset (fast-follow A): stamp a WHOLE
         *  {@link FrameTrack} — cycle-all / ping-pong / hold-current — anchored
         *  at the playhead, swapped in ONE undo step (whole-track before/after).
         *  Generation is delegated to {@link SpritePresetStamper}. */
        default void onPresetStamp(@NonNull SpriteOverlayItem item,
                                   @NonNull SpritePresetStamper.Kind kind) {}

        // ── Dope sheet (SPEC_IMAGE_SEQUENCE §5) ──────────────────────────
        // The panel stays a pure VIEW: it never touches the model. Every edit below goes to the
        // activity, which owns undo, persistence and preview refresh — the single write path
        // this class was built around.

        /** Weights edited (drag or bulk tool). ONE undo step per call. */
        default void onSequenceWeightsChanged(@NonNull SpriteOverlayItem item,
                                              @NonNull List<Integer> weights,
                                              @NonNull String label) {}

        /** §5c.7 order op: {@code "REVERSE"} or {@code "SHUFFLE"}. */
        default void onSequenceReorder(@NonNull SpriteOverlayItem item, @NonNull String op) {}

        /** Move the real playhead to an item-local time (tapping a dope-sheet frame). */
        default void onSeekToLocalMs(@NonNull SpriteOverlayItem item, long localMs) {}

        /** §3d: convert this sequence into a packed grid sprite sheet. */
        default void onConvertToSpriteSheet(@NonNull SpriteOverlayItem item) {}

        /** FF-A: turn the selected frame-track keys into a reusable preset. */
        default void onMakePresetFromKeys(@NonNull SpriteOverlayItem item,
                                          @NonNull List<Integer> keyIndices) {}

        /** §6: cycle once → loop → ping-pong (ping-pong preserves weights when it mirrors). */
        default void onSequenceLoopModeCycled(@NonNull SpriteOverlayItem item) {}

        /** §6: toggle the "continues until blocked" LENGTH intent (always resolved concretely). */
        default void onSequenceContinuesToggled(@NonNull SpriteOverlayItem item) {}

        /** §2a: toggle RELATIVE ⇄ ABSOLUTE — what dragging this object's edge MEANS. */
        default void onSequenceResizeModeCycled(@NonNull SpriteOverlayItem item) {}
    }

    /** Human label for a preset wrap type. */
    private static String loopLabel(@NonNull String type) {
        switch (type) {
            case "loop": return "loop";
            case "pingpong": return "ping-pong";
            default: return "once";
        }
    }

    private static final int DETENT_MICRO = 0;
    private static final int DETENT_PALETTE = 1;
    /**
     * The DOPE-SHEET detent (SPEC_IMAGE_SEQUENCE §5, PLAN_SPRITE_ANIMATION fast-follow A).
     * Owed since 2026-07-06 and built ONCE for sequences and sprites together, per §0.
     */
    private static final int DETENT_DOPE = 2;

    private final float density = getResources().getDisplayMetrics().density;
    private final LinearLayout panel;
    private final LinearLayout contentArea;
    private final TextView cellIndicator;
    private final TextView nudgeLeft;
    private final TextView nudgeRight;
    private final TextView deleteKey;
    @Nullable private Callback callback;

    private List<SpriteOverlayItem> items = java.util.Collections.emptyList();
    @Nullable private SpriteOverlayItem selected;
    private long playheadMs = 0;
    private int detent = DETENT_PALETTE;

    public SpritePalettePanel(@NonNull Context ctx) {
        super(ctx);
        // Scrim: tap anywhere outside the panel collapses (AssetBrowserPanel idiom).
        setOnClickListener(v -> collapse());

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF2141419);
        panel.setOnClickListener(v -> { /* consume — don't scrim-close */ });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        addView(panel, lp);

        // Grab handle: drag up/down past half a detent = snap between detents.
        FrameLayout grip = new FrameLayout(ctx);
        grip.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (18 * density)));
        View gripLine = new View(ctx);
        gripLine.setBackgroundColor(0xFF666666);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(
                (int) (40 * density), (int) (4 * density));
        gl.gravity = Gravity.CENTER;
        grip.addView(gripLine, gl);
        panel.addView(grip);
        grip.setOnTouchListener(new OnTouchListener() {
            float downY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP:
                        float dy = downY - e.getRawY(); // up = positive
                        if (dy > 30 * density) {
                            // micro → palette → dope, the three detents S3 designed.
                            if (detent == DETENT_MICRO) setDetent(DETENT_PALETTE);
                            else if (detent == DETENT_PALETTE) setDetent(DETENT_DOPE);
                        } else if (dy < -30 * density) {
                            if (detent == DETENT_DOPE) setDetent(DETENT_PALETTE);
                            else if (detent == DETENT_PALETTE) setDetent(DETENT_MICRO);
                            else collapse();
                        }
                        return true;
                }
                return e.getActionMasked() == MotionEvent.ACTION_MOVE;
            }
        });

        // Micro transport row — always visible in both detents.
        LinearLayout transport = new LinearLayout(ctx);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (8 * density);
        transport.setPadding(pad, 0, pad, pad / 2);
        TextView back = chip("◄");
        back.setOnClickListener(v -> { if (callback != null) callback.onFrameStep(-1); });
        TextView fwd = chip("►");
        fwd.setOnClickListener(v -> { if (callback != null) callback.onFrameStep(+1); });
        cellIndicator = new TextView(ctx);
        cellIndicator.setTextColor(0xFFB0BEC5);
        cellIndicator.setTextSize(12f);
        LinearLayout.LayoutParams ciLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ciLp.leftMargin = pad;
        nudgeLeft = chip("◄k");
        nudgeLeft.setOnClickListener(v -> {
            if (callback != null && selected != null) callback.onNudgeKey(selected, -1);
        });
        nudgeRight = chip("k►");
        nudgeRight.setOnClickListener(v -> {
            if (callback != null && selected != null) callback.onNudgeKey(selected, +1);
        });
        deleteKey = chip("✕k");
        deleteKey.setVisibility(GONE);
        deleteKey.setOnClickListener(v -> {
            if (callback != null && selected != null) callback.onDeleteKeyAtPlayhead(selected);
        });
        TextView close = chip("✕");
        close.setOnClickListener(v -> collapse());
        transport.addView(back, chipLp());
        transport.addView(fwd, chipLp());
        transport.addView(cellIndicator, ciLp);
        transport.addView(nudgeLeft, chipLp());
        transport.addView(nudgeRight, chipLp());
        transport.addView(deleteKey, chipLp());
        transport.addView(close, chipLp());
        panel.addView(transport);

        // Palette content (hidden in the micro detent).
        contentArea = new LinearLayout(ctx);
        contentArea.setOrientation(LinearLayout.VERTICAL);
        panel.addView(contentArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Dope-sheet content (§5) — its own container so switching detents does not rebuild
        // the strip and lose the user's selection mid-edit.
        dopeArea = new LinearLayout(ctx);
        dopeArea.setOrientation(LinearLayout.VERTICAL);
        dopeArea.setVisibility(GONE);
        panel.addView(dopeArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private final LinearLayout dopeArea;
    @Nullable private DopeSheetView dopeSheet;
    @Nullable private TextView dopeSummary;
    /** Which item {@link #dopeSheet} is currently showing — see the selection carry-over. */
    @Nullable private SpriteOverlayItem dopeBoundItem;

    public void setCallback(@Nullable Callback cb) { this.callback = cb; }

    /** Bind the current placed sprites; keeps (or re-picks) the selection. */
    public void setData(@NonNull List<SpriteOverlayItem> items) {
        this.items = items;
        if (selected == null || !items.contains(selected)) {
            selected = items.isEmpty() ? null : items.get(items.size() - 1); // newest
        }
        rebuild();
        // The dope strip is bound to ONE item and its renderer. Without this it keeps showing a
        // deleted object's frames — and, worse, keeps a renderer reference that a sheet
        // invalidation may since have recycled.
        //
        // POSTED, not immediate: this path is reached from the strip's own ACTION_UP (weight
        // commit → activity → sync → here), so rebuilding inline would detach the very view
        // that is still dispatching the touch.
        if (detent == DETENT_DOPE) post(this::buildDopeSheet);
    }

    @Nullable public SpriteOverlayItem getSelected() { return selected; }

    /** Playhead tick: refresh the live cell indicator and key-delete visibility. */
    public void setPlayheadMs(long timelineMs) {
        this.playheadMs = timelineMs;
        syncIndicator();
        if (dopeSheet != null && detent == DETENT_DOPE) dopeSheet.setPlayheadMs(timelineMs);
        if (selected != null) {
            long localMs = selected.toLocalMs(timelineMs);
            boolean onKey = false;
            for (FrameTrack.Key k : selected.getFrameTrack().keys()) {
                if (Math.abs(k.timeMs - localMs) <= 120) { onKey = true; break; }
            }
            deleteKey.setVisibility(onKey ? VISIBLE : GONE);
        } else {
            deleteKey.setVisibility(GONE);
        }
    }

    public void collapse() {
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        if (callback != null) callback.onPanelCollapsed();
    }

    private void setDetent(int d) {
        this.detent = d;
        contentArea.setVisibility(d == DETENT_PALETTE ? VISIBLE : GONE);
        dopeArea.setVisibility(d == DETENT_DOPE ? VISIBLE : GONE);
        if (d == DETENT_DOPE) buildDopeSheet();
    }

    /** Open straight to the dope sheet (the timeline tape's "edit holds" affordance). */
    public void openDopeSheet() { setDetent(DETENT_DOPE); }

    // ── The dope sheet (§5) ───────────────────────────────────────────────

    /**
     * Build the §5 dope-sheet detent: a summary line, the thumbnail strip, and the §5c
     * anti-tedium toolbar.
     *
     * <p>Tedium is the main risk to this feature — the spec says so outright — so the toolbar is
     * not a nice-to-have. Every tool here is one call into {@link SequenceTiming}, which is what
     * the weight model buys: "on twos" and "every 6th frame holds five" and "gradually faster"
     * are all the same kind of edit to the same integer array.</p>
     */
    private void buildDopeSheet() {
        dopeArea.removeAllViews();
        if (callback == null || selected == null) {
            dopeArea.addView(hint("Select an object to edit its frames."));
            return;
        }
        final SpriteSheet sheet = callback.lookupSheet(selected.getSheetId());
        if (sheet == null) {
            dopeArea.addView(hint("This object's sheet is missing."));
            return;
        }
        final SpriteOverlayItem item = selected;
        int pad = (int) (8 * density);

        dopeSummary = new TextView(getContext());
        dopeSummary.setTextColor(0xFFB0BEC5);
        dopeSummary.setTextSize(11.5f);
        dopeSummary.setPadding(pad * 2, 0, pad * 2, pad / 2);
        dopeArea.addView(dopeSummary);

        // Carry the outgoing strip's selection and scroll across the rebuild — see the
        // DopeSheetView.bind overload for why losing them defeats §5c.
        // Only across a rebuild of the SAME object: frame indices mean nothing on a different
        // sequence, and carrying them would silently select unrelated frames.
        boolean sameItem = dopeSheet != null && item == dopeBoundItem;
        java.util.List<Integer> keptSel = sameItem
                ? new java.util.ArrayList<>(dopeSheet.selection()) : null;
        float keptScroll = sameItem ? dopeSheet.scrollOffset() : 0f;
        dopeBoundItem = item;

        final DopeSheetView strip = new DopeSheetView(getContext());
        dopeSheet = strip;
        strip.bind(sheet, item, callback.lookupRenderer(item.getSheetId()), keptSel, keptScroll);
        strip.setPlayheadMs(playheadMs);
        strip.setCallback(new DopeSheetView.Callback() {
            @Override
            public void onWeightsCommitted(@NonNull List<Integer> weights, @NonNull String label) {
                if (callback != null) callback.onSequenceWeightsChanged(item, weights, label);
                refreshDopeSummary();
            }
            @Override
            public void onSeekToFrame(int frameIndex, long localMs) {
                if (callback != null) callback.onSeekToLocalMs(item, localMs);
            }
            @Override
            public void onSelectionChanged(@NonNull java.util.Set<Integer> sel) {
                refreshDopeSummary();
            }
        });
        dopeArea.addView(strip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (sheet.isSequence()) {
            dopeArea.addView(buildWeightToolbar(strip, sheet, item));
        } else {
            dopeArea.addView(buildSpritePresetToolbar(strip, item));
        }
        refreshDopeSummary();
    }

    private void refreshDopeSummary() {
        if (dopeSummary == null || dopeSheet == null) return;
        String s = dopeSheet.summary();
        int selN = dopeSheet.selection().size();
        dopeSummary.setText(selN > 0 ? s + "  ·  " + selN + " selected" : s);
    }

    private TextView hint(@NonNull String text) {
        TextView t = new TextView(getContext());
        t.setTextColor(0xFF90A4AE);
        t.setTextSize(12f);
        t.setPadding((int) (16 * density), (int) (12 * density),
                (int) (16 * density), (int) (12 * density));
        t.setText(text);
        return t;
    }

    /** §5c: the anti-tedium toolkit, in one scrolling row of chips. */
    private View buildWeightToolbar(@NonNull DopeSheetView strip, @NonNull SpriteSheet sheet,
                                    @NonNull SpriteOverlayItem item) {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (8 * density);
        row.setPadding(pad, pad / 2, pad, pad);

        // §5c.4 — the most common animation operation in existence, one tap.
        row.addView(tool("On ones", () -> strip.applyWeights(
                SequenceTiming.setWeight(strip.currentWeights(), strip.frameCount(),
                        selectionList(strip), 1), "On ones")), chipLp());
        row.addView(tool("On twos", () -> strip.applyWeights(
                SequenceTiming.setWeight(strip.currentWeights(), strip.frameCount(),
                        selectionList(strip), 2), "On twos")), chipLp());
        row.addView(tool("On threes", () -> strip.applyWeights(
                SequenceTiming.setWeight(strip.currentWeights(), strip.frameCount(),
                        selectionList(strip), 3), "On threes")), chipLp());

        // §5c.5 ramp — "a walk cycle gradually getting faster" as a single control.
        row.addView(tool("Ramp ▸", () -> promptRamp(strip)), chipLp());
        // §5c.2 stride select — "every Nth frame, starting at S".
        row.addView(tool("Every Nth", () -> promptStride(strip)), chipLp());
        // §5c.6 numeric entry.
        row.addView(tool("Set ×N", () -> promptNumeric(strip)), chipLp());

        row.addView(tool("All", strip::selectAll), chipLp());
        row.addView(tool("None", strip::clearSelection), chipLp());

        // §5c.7 order operations — weights travel with their frames.
        row.addView(tool("Reverse", () -> {
            if (callback != null) callback.onSequenceReorder(item, "REVERSE");
            rebuildDopeAfterModelChange(item);
        }), chipLp());
        row.addView(tool("Shuffle", () -> {
            if (callback != null) callback.onSequenceReorder(item, "SHUFFLE");
            rebuildDopeAfterModelChange(item);
        }), chipLp());

        // §6 looping. The preset type is the wrap rule; "Continues" is the LENGTH intent, which
        // is a different question and so is a different chip.
        SpriteSheet.Preset p = sheet.sequencePreset();
        final String loopType = p == null ? "once" : p.type;
        row.addView(tool("Play: " + loopLabel(loopType), () -> {
            if (callback != null) callback.onSequenceLoopModeCycled(item);
            buildDopeSheet();
        }), chipLp());
        row.addView(tool(item.isContinuesUntilBlocked() ? "Continues ✓" : "Continues", () -> {
            if (callback != null) callback.onSequenceContinuesToggled(item);
            buildDopeSheet();
        }), chipLp());

        // §2a — the resize MODE. Also drawn on the tape (different handle shape/colour); the
        // spec insists on that, because one handle with two destructive behaviours based on
        // invisible state is the trap that bit the caret-vs-trim grab.
        row.addView(tool("Drag: " + (sheet.getResizeMode()
                        == SequenceTiming.ResizeMode.ABSOLUTE ? "cuts frames" : "retimes"),
                () -> {
                    if (callback != null) callback.onSequenceResizeModeCycled(item);
                    buildDopeSheet();
                }), chipLp());

        // §3d — a drawer ACTION, deliberately not an import branch: at import the user cannot
        // yet know which they want, and as an action it is discoverable later and reversible.
        row.addView(tool("→ Sprite sheet", () -> {
            if (callback != null) callback.onConvertToSpriteSheet(item);
        }), chipLp());

        scroll.addView(row);
        return scroll;
    }

    /** Grid sprites get FF-A's other half: turn a run of keys into a reusable preset. */
    private View buildSpritePresetToolbar(@NonNull DopeSheetView strip,
                                          @NonNull SpriteOverlayItem item) {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (8 * density);
        row.setPadding(pad, pad / 2, pad, pad);
        row.addView(tool("All", strip::selectAll), chipLp());
        row.addView(tool("None", strip::clearSelection), chipLp());
        row.addView(tool("Make preset", () -> {
            if (callback != null) callback.onMakePresetFromKeys(item, selectionList(strip));
        }), chipLp());
        scroll.addView(row);
        return scroll;
    }

    private void rebuildDopeAfterModelChange(@NonNull SpriteOverlayItem item) {
        buildDopeSheet();
    }

    @NonNull
    private java.util.List<Integer> selectionList(@NonNull DopeSheetView strip) {
        return new java.util.ArrayList<>(strip.selection());
    }

    private TextView tool(@NonNull String label, @NonNull Runnable onTap) {
        TextView t = chip(label);
        t.setOnClickListener(v -> onTap.run());
        return t;
    }

    // ── Small numeric prompts (§5c.2 / .5 / .6) ───────────────────────────
    // Built inline rather than as layouts: res/ is another agent's live file per the
    // FaditorEditorActivity protocol note.

    private void promptNumeric(@NonNull DopeSheetView strip) {
        numberDialog("Hold for how many frames?", "2", v -> strip.applyWeights(
                SequenceTiming.setWeight(strip.currentWeights(), strip.frameCount(),
                        selectionList(strip), v), "Set hold ×" + v));
    }

    private void promptStride(@NonNull DopeSheetView strip) {
        numberDialog("Select every Nth frame", "2", n -> strip.selectStride(0, Math.max(1, n)));
    }

    private void promptRamp(@NonNull DopeSheetView strip) {
        java.util.List<Integer> sel = selectionList(strip);
        final int from = sel.isEmpty() ? 0 : java.util.Collections.min(sel);
        final int to = sel.isEmpty() ? strip.frameCount() - 1 : java.util.Collections.max(sel);
        numberDialog("Ramp holds: START value", "1", w0 ->
                numberDialog("Ramp holds: END value", "4", w1 -> strip.applyWeights(
                        SequenceTiming.applyRamp(strip.currentWeights(), strip.frameCount(),
                                from, to, w0, w1,
                                com.fadcam.ui.faditor.keyframe.Easing.EASE_IN_OUT),
                        "Ramp holds " + w0 + "→" + w1)));
    }

    private interface IntConsumer { void accept(int value); }

    private void numberDialog(@NonNull String title, @NonNull String initial,
                              @NonNull IntConsumer onOk) {
        final android.widget.EditText input = new android.widget.EditText(getContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(initial);
        input.setSelectAllOnFocus(true);
        int p = (int) (20 * density);
        input.setPadding(p, p / 2, p, p / 2);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext())
                .setTitle(title)
                .setView(input)
                .setPositiveButton("OK", (d, w) -> {
                    try {
                        onOk.accept(Integer.parseInt(input.getText().toString().trim()));
                    } catch (NumberFormatException ignored) { /* leave unchanged */ }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Build / refresh ───────────────────────────────────────────────────

    /** Full rebuild of the palette detent (instances, cells, toggles). */
    public void rebuild() {
        contentArea.removeAllViews();
        setDetent(detent);
        syncIndicator();
        int pad = (int) (8 * density);

        if (items.isEmpty() || callback == null) {
            TextView load = chip(getContext().getString(R.string.sprite_palette_load));
            load.setPadding(pad * 2, pad * 2, pad * 2, pad * 2);
            load.setOnClickListener(v -> { if (callback != null) callback.onManageSheets(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = pad * 2;
            contentArea.addView(load, lp);
            return;
        }

        // Row 1: instance chips + manage + delete.
        HorizontalScrollView instScroll = new HorizontalScrollView(getContext());
        instScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout instRow = new LinearLayout(getContext());
        instRow.setOrientation(LinearLayout.HORIZONTAL);
        instRow.setGravity(Gravity.CENTER_VERTICAL);
        instRow.setPadding(pad, 0, pad, pad / 2);
        for (int i = 0; i < items.size(); i++) {
            SpriteOverlayItem it = items.get(i);
            SpriteSheet sheet = callback.lookupSheet(it.getSheetId());
            String label = (sheet != null ? sheet.getName() : "?") + " " + (i + 1);
            TextView ic = chip(label);
            if (it == selected) ic.setBackgroundColor(0xFF4A3B5C);
            ic.setOnClickListener(v -> {
                selected = it;
                callback.onInstanceSelected(it);
                rebuild();
            });
            instRow.addView(ic, chipLp());
        }
        // ▦ FRAMES — the dope sheet's only DISCOVERABLE entry point.
        // It was previously reachable solely by dragging the grab handle up TWICE, which no
        // affordance advertises. A flagship feature behind an undocumented double-drag is, for
        // most users, not a feature. openDopeSheet() already existed and had no callers.
        TextView frames = chip("▦");
        frames.setOnClickListener(v -> openDopeSheet());
        instRow.addView(frames, chipLp());
        TextView manage = chip("⚙");
        manage.setOnClickListener(v -> callback.onManageSheets());
        instRow.addView(manage, chipLp());
        TextView del = chip("🗑");
        del.setOnClickListener(v -> { if (selected != null) callback.onDeleteInstance(selected); });
        instRow.addView(del, chipLp());
        instScroll.addView(instRow);
        contentArea.addView(instScroll);

        // Row 2: cell chip carousel for the selected sprite's sheet.
        if (selected != null) {
            SpriteSheet sheet = callback.lookupSheet(selected.getSheetId());
            SpriteSheetRenderer renderer = callback.lookupRenderer(selected.getSheetId());
            if (sheet != null) {
                HorizontalScrollView cellScroll = new HorizontalScrollView(getContext());
                cellScroll.setHorizontalScrollBarEnabled(false);
                LinearLayout cellRow = new LinearLayout(getContext());
                cellRow.setOrientation(LinearLayout.HORIZONTAL);
                cellRow.setPadding(pad, pad / 2, pad, pad / 2);
                int thumb = (int) (56 * density);
                for (int c = 0; c < sheet.cellCount(); c++) {
                    SpriteSheet.Cell meta = sheet.cellAt(c);
                    if (meta != null && !meta.enabled) continue;
                    LinearLayout box = new LinearLayout(getContext());
                    box.setOrientation(LinearLayout.VERTICAL);
                    box.setGravity(Gravity.CENTER_HORIZONTAL);
                    CellThumbView tv = new CellThumbView(getContext(), renderer, c);
                    box.addView(tv, new LinearLayout.LayoutParams(thumb, thumb));
                    TextView name = new TextView(getContext());
                    name.setTextColor(0xCCFFFFFF);
                    name.setTextSize(10f);
                    name.setGravity(Gravity.CENTER);
                    name.setText(meta != null && !meta.name.isEmpty()
                            ? c + " " + meta.name : String.valueOf(c));
                    box.addView(name, new LinearLayout.LayoutParams(
                            thumb, ViewGroup.LayoutParams.WRAP_CONTENT));
                    final int cellIndex = c;
                    box.setOnClickListener(v -> callback.onCellChipTapped(selected, cellIndex));
                    LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    bl.rightMargin = (int) (6 * density);
                    cellRow.addView(box, bl);
                }
                cellScroll.addView(cellRow);
                contentArea.addView(cellScroll);
            }

            // Row 3: flips + end-behavior.
            LinearLayout toggles = new LinearLayout(getContext());
            toggles.setOrientation(LinearLayout.HORIZONTAL);
            toggles.setGravity(Gravity.CENTER_VERTICAL);
            toggles.setPadding(pad, pad / 2, pad, pad);
            TextView fh = chip(getContext().getString(R.string.avatar_studio_flip_h));
            if (selected.isFlipH()) fh.setBackgroundColor(0xFF4A3B5C);
            fh.setOnClickListener(v -> callback.onFlipH(selected));
            TextView fv = chip(getContext().getString(R.string.avatar_studio_flip_v));
            if (selected.isFlipV()) fv.setBackgroundColor(0xFF4A3B5C);
            fv.setOnClickListener(v -> callback.onFlipV(selected));
            TextView eb = chip(getContext().getString(R.string.sprite_palette_end_prefix)
                    + " " + selected.getEndBehavior());
            eb.setOnClickListener(v -> callback.onEndBehaviorCycled(selected));
            toggles.addView(fh, chipLp());
            toggles.addView(fv, chipLp());
            toggles.addView(eb, chipLp());
            // Avatar item: record-performance chip (bake-to-keyframes). Inline
            // literals on purpose — strings.xml is another agent's live file.
            if (selected.getAvatarRigId() != null) {
                boolean rec = callback.isRecordingPerformance(selected);
                TextView perf = chip(rec ? "⏺ Stop" : "🎯 Record");
                if (rec) perf.setBackgroundColor(0xFF5C1B1B);
                else if (selected.hasAvatarPerformance()) perf.setBackgroundColor(0xFF1B4A3B);
                perf.setOnClickListener(v -> callback.onRecordPerformance(selected));
                toggles.addView(perf, chipLp());
                // Point-at-video: bake a performance from the clip under the item.
                TextView sweep = chip("🎬 From video");
                sweep.setOnClickListener(v -> callback.onSweepFromVideo(selected));
                toggles.addView(sweep, chipLp());
            } else {
                // Plain sprite: dope-sheet preset chips. Each stamps a whole
                // FrameTrack at the playhead in one undo step (fast-follow A).
                // Inline literals on purpose — strings.xml is another agent's file.
                TextView cyc = chip("Cycle");
                cyc.setOnClickListener(v -> callback.onPresetStamp(
                        selected, SpritePresetStamper.Kind.CYCLE_ALL));
                TextView pp = chip("Ping-pong");
                pp.setOnClickListener(v -> callback.onPresetStamp(
                        selected, SpritePresetStamper.Kind.PINGPONG));
                TextView hold = chip("Hold");
                hold.setOnClickListener(v -> callback.onPresetStamp(
                        selected, SpritePresetStamper.Kind.HOLD_CURRENT));
                toggles.addView(cyc, chipLp());
                toggles.addView(pp, chipLp());
                toggles.addView(hold, chipLp());
            }
            contentArea.addView(toggles);
        }
    }

    /** "cell 3 idle @ 0:04.2" style live readout for the selected sprite. */
    private void syncIndicator() {
        if (selected == null || callback == null) {
            cellIndicator.setText("");
            return;
        }
        SpriteSheet sheet = callback.lookupSheet(selected.getSheetId());
        if (sheet == null) {
            cellIndicator.setText("?");
            return;
        }
        int cell = SpriteFrameResolver.resolveCellAt(sheet, selected, playheadMs);
        if (cell == SpriteFrameResolver.NO_CELL) {
            cellIndicator.setText(getContext().getString(R.string.sprite_palette_no_cell));
        } else {
            SpriteSheet.Cell meta = sheet.cellAt(cell);
            String name = meta != null && !meta.name.isEmpty() ? " " + meta.name : "";
            cellIndicator.setText(getContext().getString(
                    R.string.sprite_palette_cell_prefix) + " " + cell + name);
        }
    }

    // ── Small helpers ─────────────────────────────────────────────────────

    private TextView chip(@NonNull String text) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setBackgroundColor(0xFF26262E);
        int p = (int) (10 * density);
        t.setPadding(p, p / 2 + 2, p, p / 2 + 2);
        return t;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (6 * density);
        return lp;
    }

    /** Tiny live thumbnail of one sheet cell (shared decoded bitmap, no copies). */
    private static class CellThumbView extends View {
        @Nullable private final SpriteSheetRenderer renderer;
        private final int cellIndex;
        private final RectF dest = new RectF();

        CellThumbView(Context ctx, @Nullable SpriteSheetRenderer renderer, int cellIndex) {
            super(ctx);
            this.renderer = renderer;
            this.cellIndex = cellIndex;
            setBackgroundColor(0xFF1B1B22);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (renderer == null) return;
            dest.set(2, 2, getWidth() - 2, getHeight() - 2);
            renderer.drawCell(canvas, cellIndex, dest, null);
        }
    }
}
