package com.fadcam.ui.faditor.sprite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
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

        /**
         * Drop a SAVED ANIMATION at the playhead — the read-back door the phone never had.
         * The resolver has always been able to play a preset key; nothing ever offered one.
         * @param presetId {@link SpriteSheet.Preset#id}
         */
        default void onPresetChipTapped(@NonNull SpriteOverlayItem item, @NonNull String presetId) {}

        /** Open the full-screen Sprite Lab on this item's sheet. */
        default void onOpenLab(@NonNull SpriteOverlayItem item) {}
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
    private static final int DETENT_DOPE = 2;   // retained: openDopeSheet()'s old contract
    /** The tallest the drawer opens — three balanced rows of chips. */
    private static final int DETENT_MAX = 3;
    /**
     * The dope sheet is a TOGGLE, not a detent. As a detent it swapped the palette out and
     * offered no way back except a drag gesture nothing advertises, which is how it came to
     * feel like a trap.
     */
    private boolean dopeOpen = false;

    private final float density = getResources().getDisplayMetrics().density;
    private final LinearLayout panel;
    private final LinearLayout contentArea;
    private final TextView cellIndicator;
    private final TextView nudgeLeft;
    private final TextView nudgeRight;
    private final TextView deleteKey;
    private final TextView labBtn;
    /** Animating chips, stopped and rebuilt with the palette so none are orphaned. */
    private final java.util.List<AnimChipView> animChips = new java.util.ArrayList<>();
    @Nullable private Callback callback;

    private List<SpriteOverlayItem> items = java.util.Collections.emptyList();
    @Nullable private SpriteOverlayItem selected;
    private long playheadMs = 0;
    private int detent = DETENT_PALETTE;

    public SpritePalettePanel(@NonNull Context ctx) {
        super(ctx);
        // NO scrim-close. This panel is a working surface you scrub the timeline against,
        // and a root click listener turned every scrub into a dismiss. ✕ and a drag down are
        // the two ways out, and both are deliberate.

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        // The design's drawer: rounded at the top only, on the scrim token — translucent on
        // purpose so the video keeps playing underneath while you noodle (SpriteTheme rule 2).
        android.graphics.drawable.GradientDrawable panelBg =
                new android.graphics.drawable.GradientDrawable();
        panelBg.setColor(SpriteTheme.DRAWER_SCRIM);
        float r = SpriteTheme.RADIUS_CARD * density;
        panelBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(panelBg);
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
        android.graphics.drawable.GradientDrawable gripBg =
                new android.graphics.drawable.GradientDrawable();
        gripBg.setColor(0xFF52525B);
        gripBg.setCornerRadius(2 * density);
        gripLine.setBackground(gripBg);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(
                (int) (36 * density), (int) (4 * density));
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
                            // Dragging up grows the CHIP AREA a row at a time. It used to
                            // swap in a different panel, which is not what a taller drawer
                            // means to anyone.
                            setDetent(Math.min(DETENT_MAX, detent + 1));
                        } else if (dy < -30 * density) {
                            if (detent > DETENT_MICRO) setDetent(detent - 1);
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
        // The frame-step bumpers are gone: the playhead is draggable and scrubbable, which
        // made them obsolete for everything except precision, and precision now lives on the
        // keyframe cluster. That buys back the width this row was wasting.
        cellIndicator = new TextView(ctx);
        cellIndicator.setTextColor(0xFFB0BEC5);
        cellIndicator.setTextSize(12f);
        LinearLayout.LayoutParams ciLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ciLp.leftMargin = pad;
        // Keyframe cluster: previous · delete · next. The glyphs are a matched pair now —
        // the old ◄k / k► were two different arrow shapes AND read as "jump to keyframe"
        // while actually nudging one.
        // One pill holding three buttons, the design's `.kf`: step back, the key itself,
        // step forward. Three separate pills read as three unrelated controls.
        LinearLayout kf = new LinearLayout(ctx);
        kf.setOrientation(LinearLayout.HORIZONTAL);
        kf.setGravity(Gravity.CENTER_VERTICAL);
        kf.setBackground(pill(SpriteTheme.CONTROL, SpriteTheme.LINE));
        int kfp = (int) (2 * density);
        kf.setPadding(kfp, kfp, kfp, kfp);

        nudgeLeft = segIcon("prev");
        nudgeLeft.setOnClickListener(v -> {
            if (callback != null && selected != null) callback.onNudgeKey(selected, -1);
        });
        nudgeRight = segIcon("next");
        nudgeRight.setOnClickListener(v -> {
            if (callback != null && selected != null) callback.onNudgeKey(selected, +1);
        });
        // Always PRESENT, lit only when the playhead is actually on a key. Hiding it made the
        // row jump; greying it answers "will this delete my object or my keyframe" before the
        // question is asked.
        // A KEY, not a bin. Between two step arrows a bin reads as "delete something" and
        // leaves you guessing whether it means the keyframe or the sprite; a diamond that
        // lights pink when the playhead is on a key says what it is and what it will remove.
        deleteKey = segIcon("key");
        deleteKey.setOnClickListener(v -> {
            if (callback != null && selected != null && isOnKey()) {
                callback.onDeleteKeyAtPlayhead(selected);
            }
        });
        kf.addView(nudgeLeft);
        kf.addView(deleteKey);
        kf.addView(nudgeRight);

        labBtn = ichip("grid", SpriteTheme.ACCENT_GRID);
        labBtn.setOnClickListener(v -> {
            if (callback != null && selected != null) callback.onOpenLab(selected);
        });
        TextView close = ichip("x", SpriteTheme.DIM);
        close.setOnClickListener(v -> collapse());
        transport.addView(cellIndicator, ciLp);
        transport.addView(kf, chipLp());
        transport.addView(labBtn, chipLp());
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
        if (dopeOpen) post(this::buildDopeSheet);
    }

    @Nullable public SpriteOverlayItem getSelected() { return selected; }

    /** Playhead tick: refresh the live cell indicator and key-delete visibility. */
    public void setPlayheadMs(long timelineMs) {
        this.playheadMs = timelineMs;
        syncIndicator();
        if (dopeSheet != null && dopeOpen) dopeSheet.setPlayheadMs(timelineMs);
        // The chip stays PRESENT and goes dim; hiding it made the row's width jump every time
        // the playhead crossed a key, which is the opposite of a stable place to aim at.
        // syncIndicator() above owns the lit/dim state.
    }

    public void collapse() {
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        if (callback != null) callback.onPanelCollapsed();
    }

    private void setDetent(int d) {
        boolean rowsChanged = this.detent != d;
        this.detent = Math.max(DETENT_MICRO, Math.min(DETENT_MAX, d));
        boolean open = this.detent > DETENT_MICRO;
        contentArea.setVisibility(open && !dopeOpen ? VISIBLE : GONE);
        dopeArea.setVisibility(open && dopeOpen ? VISIBLE : GONE);
        if (open && dopeOpen) buildDopeSheet();
        else if (rowsChanged && open) post(this::rebuild);
    }

    /** Open straight to the dope sheet (the timeline tape's "edit holds" affordance). */
    public void openDopeSheet() { dopeOpen = true; setDetent(Math.max(DETENT_PALETTE, detent)); }

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

        // ── ONE row of controls, then the chips, then the behaviours ──
        // The old build spent two full rows on a handful of chips each and left most of the
        // width empty. Instances now share the header's line; the space that buys goes to the
        // chips, which are the thing you actually tap.
        HorizontalScrollView instScroll = new HorizontalScrollView(getContext());
        instScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout instRow = new LinearLayout(getContext());
        instRow.setOrientation(LinearLayout.HORIZONTAL);
        instRow.setGravity(Gravity.CENTER_VERTICAL);
        instRow.setPadding(pad, 0, pad, pad / 2);
        if (items.size() > 1) {
            for (int i = 0; i < items.size(); i++) {
                SpriteOverlayItem it = items.get(i);
                SpriteSheet sh = callback.lookupSheet(it.getSheetId());
                TextView ic = chip((sh != null ? sh.getName() : "?") + " " + (i + 1));
                if (it == selected) tint(ic, SpriteTheme.ACCENT_CELL);
                ic.setOnClickListener(v -> {
                    selected = it;
                    if (callback != null) callback.onInstanceSelected(it);
                    rebuild();
                });
                instRow.addView(ic, chipLp());
            }
            instScroll.addView(instRow);
            contentArea.addView(instScroll);
        }

        if (selected != null) {
            SpriteSheet sheet = callback.lookupSheet(selected.getSheetId());
            SpriteSheetRenderer renderer = callback.lookupRenderer(selected.getSheetId());
            if (sheet != null) {
                contentArea.addView(buildChipRows(sheet, renderer, pad));
            }

            // Behaviours. The object trash is GONE — the timeline tape carries a delete badge
            // now, and a bin sitting next to a keyframe control could only ever be ambiguous
            // about which of the two it meant.
            LinearLayout toggles = new LinearLayout(getContext());
            toggles.setOrientation(LinearLayout.HORIZONTAL);
            toggles.setGravity(Gravity.CENTER_VERTICAL);
            toggles.setPadding(pad, pad / 2, pad, pad);
            // Icon-only, as the design draws them: the words cost a third of the row and the
            // mark says it faster than "Flip H" does.
            TextView fh = ichip("fliph", SpriteTheme.DIM);
            if (selected.isFlipH()) tint(fh, SpriteTheme.SELECTED);
            fh.setOnClickListener(v -> callback.onFlipH(selected));
            TextView fv = ichip("flipv", SpriteTheme.DIM);
            if (selected.isFlipV()) tint(fv, SpriteTheme.SELECTED);
            fv.setOnClickListener(v -> callback.onFlipV(selected));
            TextView eb = ichip(endIcon(selected.getEndBehavior()), SpriteTheme.DIM);
            tint(eb, endColour(selected.getEndBehavior()));
            eb.setOnClickListener(v -> callback.onEndBehaviorCycled(selected));
            toggles.addView(fh, chipLp());
            toggles.addView(fv, chipLp());
            toggles.addView(eb, chipLp());

            if (selected.getAvatarRigId() != null) {
                boolean rec = callback.isRecordingPerformance(selected);
                TextView perf = ichip(rec ? "stop" : "record", SpriteTheme.DIM);
                // setBackgroundColor would flatten the pill back into a square. Recording is
                // pink because it IS the live state; a performance already on tape is green.
                if (rec) tint(perf, SpriteTheme.LIVE);
                else if (selected.hasAvatarPerformance()) tint(perf, SpriteTheme.ACCENT_OUT);
                perf.setOnClickListener(v -> callback.onRecordPerformance(selected));
                toggles.addView(perf, chipLp());
                TextView sweep = ichip("film", SpriteTheme.DIM);
                sweep.setOnClickListener(v -> callback.onSweepFromVideo(selected));
                toggles.addView(sweep, chipLp());
            }

            // Overflow. The whole-track stampers used to sit on this row as three words;
            // they are rare and they were crowding out the chips, but dropping a working
            // feature to tidy a row is not a trade anyone asked for.
            TextView more = ichip("dots", SpriteTheme.DIM);
            more.setOnClickListener(v -> showTrackMenu(more));
            toggles.addView(more, chipLp());

            View spacer = new View(getContext());
            toggles.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
            // A TOGGLE, not a one-way door. It used to swap the palette out for the dope sheet
            // with no way back except a drag gesture nothing advertises.
            TextView dope = ichip("layers", SpriteTheme.DIM);
            if (dopeOpen) tint(dope, SpriteTheme.ACCENT_GRID);
            dope.setOnClickListener(v -> { dopeOpen = !dopeOpen; setDetent(detent); rebuild(); });
            toggles.addView(dope, chipLp());
            TextView manage = ichip("gear", SpriteTheme.DIM);
            manage.setOnClickListener(v -> callback.onManageSheets());
            toggles.addView(manage, chipLp());
            contentArea.addView(toggles);
        }
    }

    // ── chips ────────────────────────────────────────────────────────────

    /**
     * Saved animations and still cells, as ONE list of identical chips split into equal rows.
     *
     * <p>No headings: an animation is plainly an animation, because it plays and wears a mode
     * dot. Balanced rather than "animations row, cells row", so three clips over seventeen
     * cells is two rows of ten instead of a stub above a long one. Sprockets are deliberately
     * absent — in the drawer these are tap-to-key buttons, not a reel; the sprockets belong in
     * the Lab, where the strip really is film you are cutting.</p>
     */
    @NonNull
    private View buildChipRows(@NonNull SpriteSheet sheet,
                               @Nullable SpriteSheetRenderer renderer, int pad) {
        for (AnimChipView a : animChips) a.stop();
        animChips.clear();

        final java.util.List<View> chips = new java.util.ArrayList<>();
        final int thumb = (int) (52 * density);

        // Animations first — they are the thing you reach for, and they are new here.
        for (SpriteSheet.Preset preset : sheet.getPresets()) {
            if (preset.frames.isEmpty()) continue;
            if (SpriteSheet.SEQUENCE_PRESET_ID.equals(preset.id)) continue; // that IS the sequence
            chips.add(buildChip(renderer, thumb, preset.name,
                    preset.frames.size() + "f", preset, -1));
        }
        for (int c = 0; c < sheet.cellCount(); c++) {
            SpriteSheet.Cell meta = sheet.cellAt(c);
            if (meta != null && !meta.enabled) continue;
            String name = sheet.cellName(c);
            if ((name == null || name.isEmpty()) && meta != null && !meta.name.isEmpty()) {
                name = meta.name;
            }
            chips.add(buildChip(renderer, thumb, name, String.valueOf(c), null, c));
        }

        int rows = Math.max(1, Math.min(3, detent == DETENT_MICRO ? 1 : detent));
        int per = (int) Math.ceil(chips.size() / (float) rows);
        LinearLayout stack = new LinearLayout(getContext());
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setPadding(pad, pad / 2, pad, pad / 2);
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = r * per; i < Math.min(chips.size(), (r + 1) * per); i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = (int) (5 * density);
                row.addView(chips.get(i), lp);
            }
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rl.bottomMargin = (int) (4 * density);
            stack.addView(row, rl);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(stack);
        return scroll;
    }

    /**
     * One chip. A still cell and a saved animation differ by exactly two things: an animation
     * plays, and it carries a mode dot in the corner. Everything else — size, shape, the white
     * name over small grey detail — is identical, because while animating you are equally
     * likely to want either and they should sit side by side without a hierarchy.
     */
    @NonNull
    private View buildChip(@Nullable SpriteSheetRenderer renderer, int thumb,
                           @Nullable String name, @NonNull String sub,
                           @Nullable SpriteSheet.Preset preset, int cellIndex) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        // A CARD, the design's `.ch`: the art sits on a surface with a name and a sub-line
        // under it. Floating art on the drawer's own background is the whole reason this
        // panel read as unfinished next to the mockup.
        android.graphics.drawable.GradientDrawable card =
                new android.graphics.drawable.GradientDrawable();
        card.setColor(SpriteTheme.CONTROL);
        card.setCornerRadius(9 * density);
        card.setStroke((int) (2 * density), 0x00000000);
        box.setBackground(card);
        int cp = (int) (2 * density);
        box.setPadding(cp, cp, cp, cp);

        AnimChipView art = new AnimChipView(getContext(), renderer, preset, cellIndex);
        box.addView(art, new LinearLayout.LayoutParams(thumb, (int) (thumb * 0.72f)));
        if (preset != null) { animChips.add(art); art.start(); }

        TextView top = new TextView(getContext());
        top.setTextColor(name != null && !name.isEmpty() ? 0xFFFFFFFF : SpriteTheme.DIM);
        top.setTextSize(8.5f);
        top.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        top.setGravity(Gravity.CENTER);
        top.setMaxLines(1);
        top.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.setText(name != null && !name.isEmpty() ? name : sub);
        box.addView(top, new LinearLayout.LayoutParams(thumb, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView bottom = new TextView(getContext());
        bottom.setTextColor(SpriteTheme.DIMMER);
        bottom.setTextSize(7.5f);
        bottom.setGravity(Gravity.CENTER);
        bottom.setMaxLines(1);
        bottom.setText(name != null && !name.isEmpty() ? sub : " ");
        box.addView(bottom, new LinearLayout.LayoutParams(thumb, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.setOnClickListener(v -> {
            if (callback == null || selected == null) return;
            if (preset != null) callback.onPresetChipTapped(selected, preset.id);
            else callback.onCellChipTapped(selected, cellIndex);
        });
        return box;
    }

    /**
     * Whole-track actions, and the one that closes the loop.
     *
     * <p>You could always drop keys by tapping chips while the playhead ran — that live pass is
     * the best thing about animating here. What you could never do was keep the take: the
     * performance stayed a loose run of keys on one object, unnamed and unreusable. "Save as
     * animation" turns it into a named preset on the sheet, which is what the animation chips
     * read back. Perform it, save it, drop it again anywhere.</p>
     */
    private void showTrackMenu(@NonNull View anchor) {
        if (callback == null || selected == null) return;
        final SpriteOverlayItem item = selected;
        final int keyCount = item.getFrameTrack().keys().size();
        android.widget.PopupMenu menu = new android.widget.PopupMenu(getContext(), anchor);
        menu.getMenu().add(0, 1, 0, "Cycle all cells");
        menu.getMenu().add(0, 2, 1, "Ping-pong all cells");
        menu.getMenu().add(0, 3, 2, "Hold this cell");
        menu.getMenu().add(0, 4, 3, keyCount >= 2
                ? "Save these " + keyCount + " keys as an animation"
                : "Save as animation (needs 2+ keys)").setEnabled(keyCount >= 2);
        menu.setOnMenuItemClickListener(mi -> {
            if (callback == null) return false;
            switch (mi.getItemId()) {
                case 1: callback.onPresetStamp(item, SpritePresetStamper.Kind.CYCLE_ALL); return true;
                case 2: callback.onPresetStamp(item, SpritePresetStamper.Kind.PINGPONG); return true;
                case 3: callback.onPresetStamp(item, SpritePresetStamper.Kind.HOLD_CURRENT); return true;
                case 4: {
                    java.util.List<Integer> all = new java.util.ArrayList<>();
                    for (int i = 0; i < item.getFrameTrack().keys().size(); i++) all.add(i);
                    callback.onMakePresetFromKeys(item, all);
                    post(this::rebuild);
                    return true;
                }
            }
            return false;
        });
        menu.show();
    }

    /** Loop / ping-pong / once, as the design's own mark. */
    @NonNull
    static String endIcon(@Nullable String behavior) {
        if ("loop".equals(behavior)) return "loop";
        if ("pingpong".equals(behavior)) return "ping";
        return "once";
    }

    /** Loop / ping-pong / once, as one glyph. Kept for menu text, which cannot take a path. */
    @NonNull
    private static String endGlyph(@Nullable String behavior) {
        if ("loop".equals(behavior)) return "∞";
        if ("pingpong".equals(behavior)) return "⇄";
        return "▸|";
    }
    private static int endColour(@Nullable String behavior) {
        if ("loop".equals(behavior)) return SpriteTheme.ACCENT_GRID;
        if ("pingpong".equals(behavior)) return SpriteTheme.ACCENT_CELL;
        return SpriteTheme.LIVE;
    }
    /** Solid accent, dark ink — no half-opaque middle state anywhere in this package. */
    private void tint(@NonNull TextView v, int colour) {
        int ink = colour == SpriteTheme.LIVE ? 0xFFFFFFFF : SpriteTheme.ON_ACCENT;
        v.setBackground(pill(colour, colour));
        v.setTextColor(ink);
        for (android.graphics.drawable.Drawable dr : v.getCompoundDrawables()) {
            if (dr instanceof SpriteIcons.IconDrawable) {
                ((SpriteIcons.IconDrawable) dr).setColour(ink);
            }
        }
    }

    /**
     * True when the playhead sits on one of the selected item's frame keys.
     *
     * <p>The visibility check is not belt-and-braces. Driving this on a phone lit the bin at
     * 0:00 on an object whose tape starts later: {@code toLocalMs} clamps outside the span, so
     * the clamped value landed on the first key and armed a delete for a keyframe that was not
     * on screen. The 120ms window is the same one {@code onDeleteKeyAtPlayhead} searches with —
     * arming on a wider tolerance than the action uses would light a button that then does
     * nothing.</p>
     */
    private boolean isOnKey() {
        if (selected == null || !selected.isVisibleAt(playheadMs)) return false;
        long local = Math.max(0, selected.toLocalMs(playheadMs));
        for (FrameTrack.Key k : selected.getFrameTrack().keys()) {
            if (Math.abs(k.timeMs - local) <= 120) return true;
        }
        return false;
    }

    /** "cell 3 idle @ 0:04.2" style live readout for the selected sprite. */
    private void syncIndicator() {
        // Lit only when there is actually a key under the playhead to delete.
        // Pink when there IS a key under the playhead to delete, plain grey when there is
        // not. No half-opacity: a faded control does not tell you whether it will do anything.
        boolean armed = isOnKey();
        deleteKey.setBackground(pill(armed ? SpriteTheme.LIVE : 0x00000000, 0x00000000));
        int keyInk = armed ? 0xFFFFFFFF : SpriteTheme.DIMMER;
        deleteKey.setTextColor(keyInk);
        for (android.graphics.drawable.Drawable dr : deleteKey.getCompoundDrawables()) {
            if (dr instanceof SpriteIcons.IconDrawable) {
                ((SpriteIcons.IconDrawable) dr).setColour(keyInk);
            }
        }

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
        t.setTextSize(11.5f);
        t.setTextColor(SpriteTheme.INK);
        t.setGravity(android.view.Gravity.CENTER);
        t.setBackground(pill(SpriteTheme.CONTROL, SpriteTheme.LINE));
        int p = (int) (10 * density);
        t.setPadding(p, p / 2 + 2, p, p / 2 + 2);
        t.setMinHeight((int) (28 * density));
        return t;
    }

    /** The same pill the Lab uses, so a control means the same thing in both rooms. */
    @NonNull
    private android.graphics.drawable.GradientDrawable pill(int fill, int stroke) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(SpriteTheme.RADIUS_PILL * density);
        g.setStroke(Math.max(1, (int) density), stroke);
        return g;
    }

    /**
     * A chip whose face is one of the web design's icons.
     *
     * <p>Equal left and right padding with no minimum width, so the icon centres itself.</p>
     */
    @NonNull
    private TextView ichip(@NonNull String icon, int colour) {
        TextView t = chip("");
        t.setCompoundDrawables(SpriteIcons.of(icon, colour, (int) (16 * density)),
                null, null, null);
        t.setCompoundDrawablePadding(0);
        int p = (int) (8 * density);
        t.setPadding(p, (int) (5 * density), p, (int) (5 * density));
        return t;
    }

    /** A borderless button that lives inside a segmented pill. */
    @NonNull
    private TextView segIcon(@NonNull String icon) {
        TextView t = new TextView(getContext());
        t.setGravity(android.view.Gravity.CENTER);
        t.setMinHeight((int) (24 * density));
        t.setCompoundDrawables(SpriteIcons.of(icon, SpriteTheme.DIM, (int) (15 * density)),
                null, null, null);
        t.setCompoundDrawablePadding(0);
        int p = (int) (9 * density);
        t.setPadding(p, (int) (2 * density), p, (int) (2 * density));
        t.setBackground(pill(0x00000000, 0x00000000));
        return t;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (6 * density);
        return lp;
    }

    /** Tiny live thumbnail of one sheet cell (shared decoded bitmap, no copies). */
    /**
     * The chip's picture. Draws a still cell, or plays a saved animation in place at the
     * preset's own cadence with a mode dot in the corner.
     *
     * <p>Animating here rather than showing frame 0 is the point: a strip of static thumbnails
     * makes you remember what "talk" looks like, and a strip of moving ones tells you.</p>
     */
    private static class AnimChipView extends View {
        @Nullable private final SpriteSheetRenderer renderer;
        @Nullable private final SpriteSheet.Preset preset;
        private final int cellIndex;
        private final RectF dest = new RectF();
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotInk = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int tick;
        private boolean running;

        AnimChipView(Context ctx, @Nullable SpriteSheetRenderer renderer,
                     @Nullable SpriteSheet.Preset preset, int cellIndex) {
            super(ctx);
            this.renderer = renderer;
            this.preset = preset;
            this.cellIndex = cellIndex;
            dotInk.setColor(SpriteTheme.ON_ACCENT);
            dotInk.setTextAlign(Paint.Align.CENTER);
            dotInk.setFakeBoldText(true);
        }

        void start() {
            if (running || preset == null || preset.frames.isEmpty()) return;
            running = true;
            step();
        }
        void stop() { running = false; removeCallbacks(null); }

        private void step() {
            if (!running) return;
            tick++;
            invalidate();
            float fps = preset != null && preset.fps > 0f ? preset.fps : 8f;
            postDelayed(this::step, (long) (1000f / Math.max(1f, Math.min(60f, fps))));
        }

        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); stop(); }

        @Override
        protected void onDraw(Canvas canvas) {
            if (renderer == null) return;
            dest.set(2, 2, getWidth() - 2, getHeight() - 2);
            int cell = cellIndex;
            if (preset != null && !preset.frames.isEmpty()) {
                int n = preset.frames.size();
                int i;
                if ("pingpong".equals(preset.type) && n > 1) {
                    int period = 2 * n - 2;
                    int k = tick % period;
                    i = k < n ? k : period - k;
                } else {
                    i = tick % n;
                }
                Integer f = preset.frames.get(Math.max(0, Math.min(n - 1, i)));
                cell = f == null ? 0 : f;
            }
            renderer.drawCellFitted(canvas, cell, dest, null);

            if (preset != null) {
                // The one visual difference: a mode dot saying how this animation wraps.
                float r = Math.min(getWidth(), getHeight()) * 0.16f;
                float cx = getWidth() - r - 2, cy = getHeight() - r - 2;
                int face = "loop".equals(preset.type) ? SpriteTheme.SELECTED
                        : "once".equals(preset.type) ? SpriteTheme.LIVE : SpriteTheme.ACCENT_CELL;
                dot.setColor(face);
                canvas.drawCircle(cx, cy, r, dot);
                SpriteIcons.IconDrawable mark = SpriteIcons.of(endIcon(preset.type),
                        face == SpriteTheme.LIVE ? 0xFFFFFFFF : SpriteTheme.ON_ACCENT,
                        Math.round(r * 1.5f));
                mark.setBounds(Math.round(cx - r * 0.75f), Math.round(cy - r * 0.75f),
                        Math.round(cx + r * 0.75f), Math.round(cy + r * 0.75f));
                mark.draw(canvas);
            }
        }
    }
}
