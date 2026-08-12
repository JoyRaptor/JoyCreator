package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.KeyframeDiamondControl;
import com.fadcam.ui.faditor.ObjectMenuSheet;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.CompositingSpec;
import com.fadcam.ui.faditor.model.MaskAnimator;

import java.util.ArrayList;
import java.util.List;

/**
 * Content for {@link PipOverlayDrawer}'s four tabs.
 *
 * <p>Split from the drawer so the chrome (tabs, animation, icon row) knows nothing about
 * compositing, and split from {@code FaditorEditorActivity} so building it does not grow the
 * 26k-line file again.</p>
 *
 * <p><b>Rows are COMPACT here on purpose.</b> The drawer sits over the preview, so every dp of
 * height is picture the user cannot see. Label and value share one line with the slider and the
 * keyframe diamond, instead of the label-above-slider stacking the bottom sheet uses.</p>
 */
public final class PipDrawerTabs {

    private PipDrawerTabs() {}

    private static final int TXT = 0xFFE8E8E8;
    private static final int TXT_DIM = 0xFFA0A0A0;
    private static final int ACCENT = 0xFF8C3DFA;
    private static final int SLIDER_STEPS = 1000;

    /** Everything the tabs need back from the editor. */
    public interface Host {
        long playheadMs();
        /** A value/keyframe changed — repaint preview + timeline and schedule a save. */
        void onChanged();
        /** Arm the eyedropper; the next tap on the preview reports a colour (or null). */
        void pickColorFromPreview(@NonNull ColorPicked cb);
        /** Record one undo step. */
        void recordUndo(@NonNull String label, @NonNull Runnable redo, @NonNull Runnable undo);
    }

    public interface ColorPicked { void onPicked(@Nullable Integer rgb); }

    /**
     * Wraps a tab whose controls write the model but reach NEITHER renderer, with a one-line
     * caveat above it. JoyRaptor's ruling (2026-08-12) on the four inert image-overlay tabs: leave the
     * tabs and the controls, but say so — a control that silently does nothing is worse than a
     * control labelled honestly.
     *
     * <p>The note goes ABOVE the content, not below: below is where the export-only blend caveat
     * lives, and these two must not read as the same class of warning. This one means "no picture
     * change at all, in preview or in export"; that one means "you will see it in the file".</p>
     */
    @NonNull
    public static View withInertNote(@NonNull Context ctx, @NonNull View content, int noteRes) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = column(ctx);
        TextView note = new TextView(ctx);
        note.setText(noteRes);
        note.setTextColor(TXT_DIM);
        note.setTextSize(10);
        note.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        note.setPadding(Math.round(6 * d), Math.round(6 * d),
                Math.round(6 * d), Math.round(6 * d));
        root.addView(note);
        root.addView(content);
        // The row-refresh tag is looked up on the tab's ROOT view (see refreshRows), so wrapping a
        // tab would otherwise silently stop its playhead-driven read-back. Forward the child's.
        Object tag = content.getTag(R.id.faditor_tag_row_refresh);
        if (tag != null) root.setTag(R.id.faditor_tag_row_refresh, tag);
        return root;
    }

    // ── Tab 0: VIDEO ─────────────────────────────────────────────────────────────────────

    /**
     * Transform + opacity + volume rows, each with its keyframe diamond.
     *
     * <p>The diamonds are the reason this tab exists in a drawer that leaves the timeline
     * visible: keyframing means moving the playhead and dropping values, and the old bottom
     * sheet covered the very tape you had to scrub.</p>
     */
    @NonNull
    public static View videoTab(@NonNull Context ctx,
                                @NonNull List<ObjectMenuSheet.Prop> props,
                                @NonNull Host host) {
        LinearLayout root = column(ctx);
        final List<Runnable> refreshers = new ArrayList<>();
        for (ObjectMenuSheet.Prop p : props) {
            refreshers.add(propRow(ctx, root, p, host, null));
        }
        // One pass so every diamond shows its true on-key state the moment the tab appears,
        // rather than only after the next playhead tick.
        for (Runnable r : refreshers) r.run();
        // Hang the refresh off the VIEW rather than returning it, so whoever holds the tab (the
        // drawer, which rebuilds content per tab switch) can re-read the rows without the
        // activity having to track which tab is on screen. Without this the tab is a snapshot
        // of the instant it was built: scrubbing changes nothing on it, and a dropped key leaves
        // its own diamond hollow — the measured "nothing happened" of 2026-08-05.
        root.setTag(R.id.faditor_tag_row_refresh, (Runnable) () -> {
            for (Runnable r : refreshers) r.run();
        });
        return root;
    }

    /**
     * Re-read every row of a {@link #videoTab} view (values, slider positions and diamond
     * on-key states) at the CURRENT playhead. No-op for any other view, so the drawer can call
     * it blindly on whatever tab happens to be showing.
     */
    public static void refreshRows(@Nullable View tabRoot) {
        if (tabRoot == null) return;
        // The drawer wraps every tab in a ScrollView; look through one level rather than making
        // the caller know about the wrapper.
        View v = tabRoot;
        if (v instanceof android.view.ViewGroup && v.getTag(R.id.faditor_tag_row_refresh) == null
                && ((android.view.ViewGroup) v).getChildCount() == 1) {
            v = ((android.view.ViewGroup) v).getChildAt(0);
        }
        Object tag = v.getTag(R.id.faditor_tag_row_refresh);
        if (tag instanceof Runnable) ((Runnable) tag).run();
    }

    /**
     * Public entry into {@link #propRow} so an activity can compose transform rows
     * into its own tab content (the image drawer's chain-split Scale rows) while
     * reusing the exact row chrome the PiP video tab uses.
     */
    public static Runnable addPropRow(@NonNull Context ctx, @NonNull LinearLayout parent,
                                      @NonNull ObjectMenuSheet.Prop prop, @NonNull Host host) {
        return propRow(ctx, parent, prop, host, null);
    }

    /**
     * The image drawer's chain-split Scale row — ONE row whether linked or split.
     * Linked shows a single SCALE slider; split shows Scale X and Scale Y sliders IN
     * SERIES on the same line, never stacked (user 2026-08-11: "two sliders in series,
     * NOT STACKED … conserve vertical space"). {@code chainToggle} sits immediately
     * after the label ("the chain icon needs to be after scale"); toggling rebuilds
     * the caller's rows, because the slider count changes.
     *
     * @param linked      true = one slider bound to {@code linkedProp}; false = two
     *                    sliders bound to {@code xProp}/{@code yProp}
     * @param chainToggle the linked/broken-chain icon; placed after the label
     */
    @NonNull
    public static Runnable addScaleRow(@NonNull Context ctx, @NonNull LinearLayout parent,
                                       @NonNull String label, @NonNull Host host, boolean linked,
                                       @Nullable ObjectMenuSheet.Prop linkedProp,
                                       @Nullable ObjectMenuSheet.Prop xProp,
                                       @Nullable ObjectMenuSheet.Prop yProp,
                                       @NonNull View chainToggle) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(2 * d), 0, Math.round(2 * d));

        TextView labelView = new TextView(ctx);
        labelView.setTextColor(TXT_DIM);
        labelView.setTextSize(11);
        labelView.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        labelView.setWidth(Math.round(52 * d));
        labelView.setText(label);
        row.addView(labelView);

        row.addView(chainToggle);

        final List<Runnable> refreshers = new ArrayList<>();
        if (linked && linkedProp != null) {
            refreshers.add(scaleSlider(ctx, row, linkedProp, host, d));
        } else {
            if (xProp != null) refreshers.add(scaleSlider(ctx, row, xProp, host, d));
            if (yProp != null) refreshers.add(scaleSlider(ctx, row, yProp, host, d));
        }
        parent.addView(row);
        return () -> {
            for (Runnable r : refreshers) r.run();
        };
    }

    /** One label-less slider + value + keyframe diamond (the label belongs to the row). */
    @NonNull
    private static Runnable scaleSlider(@NonNull Context ctx, @NonNull LinearLayout row,
                                        @NonNull ObjectMenuSheet.Prop prop, @NonNull Host host,
                                        float d) {
        SeekBar bar = new SeekBar(ctx);
        bar.setMax(SLIDER_STEPS);
        final float min = propMin(prop), max = propMax(prop);
        float cur = prop.valueAt(host.playheadMs());
        bar.setProgress(Math.round((cur - min) / Math.max(1e-6f, max - min) * SLIDER_STEPS));

        TextView value = new TextView(ctx);
        value.setTextColor(TXT);
        value.setTextSize(11);
        value.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        value.setWidth(Math.round(40 * d));
        value.setGravity(Gravity.END);
        value.setText(prop.format(cur));

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                float v = min + (max - min) * (p / (float) SLIDER_STEPS);
                v = snap(prop, v);
                prop.write(v, host.playheadMs());
                value.setText(prop.format(v));
                host.onChanged();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        row.addView(bar, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);

        final KeyframeDiamondControl diamond = new KeyframeDiamondControl(ctx);
        final Runnable[] selfRefresh = new Runnable[1];
        diamond.bind(prop, new KeyframeDiamondControl.Host() {
            @Override public long playheadMs() { return host.playheadMs(); }
            @Override public void onFocus() { }
            @Override public void onAction() {
                host.onChanged();
                if (selfRefresh[0] != null) selfRefresh[0].run();
            }
        });
        row.addView(diamond);
        selfRefresh[0] = () -> {
            long ph = host.playheadMs();
            float v = prop.valueAt(ph);
            value.setText(prop.format(v));
            bar.setProgress(Math.round((v - min) / Math.max(1e-6f, max - min) * SLIDER_STEPS));
            diamond.refresh(ph);
        };
        return selfRefresh[0];
    }

    /** @return a refresher that re-reads this row's value, slider and on-key state. */
    private static Runnable propRow(@NonNull Context ctx, @NonNull LinearLayout parent,
                                    @NonNull ObjectMenuSheet.Prop prop, @NonNull Host host,
                                    @Nullable View trailing) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(2 * d), 0, Math.round(2 * d));

        TextView label = new TextView(ctx);
        label.setTextColor(TXT_DIM);
        label.setTextSize(11);
        label.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        label.setWidth(Math.round(52 * d));
        label.setText(prop.label());
        row.addView(label);

        SeekBar bar = new SeekBar(ctx);
        bar.setMax(SLIDER_STEPS);
        final float min = propMin(prop), max = propMax(prop);
        float cur = prop.valueAt(host.playheadMs());
        bar.setProgress(Math.round((cur - min) / Math.max(1e-6f, max - min) * SLIDER_STEPS));

        TextView value = new TextView(ctx);
        value.setTextColor(TXT);
        value.setTextSize(11);
        value.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        value.setWidth(Math.round(46 * d));
        value.setGravity(Gravity.END);
        value.setText(prop.format(cur));

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                float v = min + (max - min) * (p / (float) SLIDER_STEPS);
                v = snap(prop, v);
                prop.write(v, host.playheadMs());
                value.setText(prop.format(v));
                host.onChanged();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(bar, blp);
        row.addView(value);

        final KeyframeDiamondControl diamond;
        // The row's own refresh — value text, slider position and diamond state, all read back
        // from the model at the live playhead. Declared before the diamond so the diamond's
        // onAction can run it: a key that lands must be visible on the very control that
        // dropped it, or the gesture reads as a no-op (measured, 2026-08-05).
        final Runnable[] selfRefresh = new Runnable[1];
        if (prop.keyframeable()) {
            diamond = new KeyframeDiamondControl(ctx);
            diamond.bind(prop, new KeyframeDiamondControl.Host() {
                @Override public long playheadMs() { return host.playheadMs(); }
                @Override public void onFocus() { }
                @Override public void onAction() {
                    host.onChanged();
                    if (selfRefresh[0] != null) selfRefresh[0].run();
                }
            });
            row.addView(diamond);
        } else {
            diamond = null;
            // Reserve the same width so a static row's slider does not stretch into the space
            // a keyframeable one uses — ragged right edges read as a layout bug.
            View spacer = new View(ctx);
            row.addView(spacer, new LinearLayout.LayoutParams(Math.round(64 * d), 1));
        }
        if (trailing != null) row.addView(trailing);
        parent.addView(row);
        selfRefresh[0] = () -> {
            long ph = host.playheadMs();
            float v = prop.valueAt(ph);
            value.setText(prop.format(v));
            // setProgress(..., false) — never animate here. This runs on every playhead tick,
            // and an animated thumb chasing a scrub lags behind the frame it is describing.
            bar.setProgress(Math.round((v - min) / Math.max(1e-6f, max - min) * SLIDER_STEPS));
            if (diamond != null) diamond.refresh(ph);
        };
        return selfRefresh[0];
    }

    // ── Tab 1: MASK ──────────────────────────────────────────────────────────────────────

    @NonNull
    public static View maskTab(@NonNull Context ctx, @NonNull Clip clip,
                               @NonNull CompositingSpec spec, @NonNull Runnable apply) {
        return maskTab(ctx, clip, spec, apply, () -> 0L);
    }

    /**
     * @param playheadMs ABSOLUTE timeline ms — the base mask keyframes share with a PiP's
     *                   {@code overlayTransform}, deliberately, so one clip does not carry two
     *                   time conventions.
     */
    @NonNull
    public static View maskTab(@NonNull Context ctx, @NonNull Clip clip,
                               @NonNull CompositingSpec spec, @NonNull Runnable apply,
                               @NonNull PlayheadSource playheadMs) {
        return maskTab(ctx, clip::getOverlayTransform, spec, apply, playheadMs);
    }

    /**
     * For an object with nothing to link a mask to (an adjustment layer: it grades a composed
     * frame, not an object with its own position/scale/rotation) — see {@link LinkSource}. The
     * "Move with the object" row is left out entirely rather than shown disabled, because a
     * control for a concept that does not apply here reads as more broken than no control.
     */
    @NonNull
    public static View maskTab(@NonNull Context ctx, @NonNull CompositingSpec spec,
                               @NonNull Runnable apply, @NonNull PlayheadSource playheadMs) {
        return maskTab(ctx, (LinkSource) null, spec, apply, playheadMs);
    }

    /**
     * Supplies the pose a linked mask captures FROM when "Move with the object" is switched on.
     *
     * <p>{@link #maskTab} was typed around a PiP {@code Clip} originally, purely for this one
     * feature — a mask anchored to the clip's {@code overlayTransform} keyframes. An adjustment
     * layer has no equivalent (its own {@code transform} is opacity-only, see the class doc on
     * {@code AdjustmentLayer}), so this is the one seam widened to a supplier rather than
     * duplicating ~300 lines of otherwise object-agnostic tab code for the sake of one field.</p>
     */
    public interface LinkSource {
        @Nullable com.fadcam.ui.faditor.keyframe.KeyframeSet transform();
    }

    @NonNull
    private static View maskTab(@NonNull Context ctx, @Nullable LinkSource linkSource,
                                @NonNull CompositingSpec spec, @NonNull Runnable apply,
                                @NonNull PlayheadSource playheadMs) {
        LinearLayout root = column(ctx);
        // NO MASK UNTIL ASKED FOR. Opening this tab used to CREATE a full-frame shape, so every
        // PiP arrived already masked and the user had to notice and remove something they never
        // added. An object with no mask is the overwhelmingly common case; the "+" chip is the
        // whole affordance for the other one.
        float dp = ctx.getResources().getDisplayMetrics().density;

        // WHICH shape the per-shape controls below are editing. Held as an INDEX into
        // spec.masks (what the chip row shows), never as a slot — slots are stable and sparse
        // after a delete, so an index is the only thing that stays in step with the list.
        final int[] sel = {0};

        // ── Shape chips · mode · presets ────────────────────────────────────────────────
        // Three rows, rebuilt together because all three describe the SELECTED shape. Two
        // people's faces in one mask is the case this exists for: add a second circle rather
        // than duplicating the video (JoyRaptor, 2026-08-06).
        // ONE row: shape chips left, the three boolean modes right-justified beside them.
        //
        // The four shape presets are GONE. They did not fit alongside both other groups, and
        // when told so JoyRaptor's answer was that "the sliders can do everything needed" — a
        // preset only ever wrote w/h/corner, which the three sliders below already set
        // directly. Deleting the row beat keeping a second one for controls that duplicated
        // existing ones (user, 2026-08-06). CompositingSpec.applyPreset survives, unused by
        // the UI, because the FX/adjustment-layer drawer may want it where space is cheaper.
        LinearLayout topRow = row(ctx);
        LinearLayout shapeGroup = new LinearLayout(ctx);
        shapeGroup.setOrientation(LinearLayout.HORIZONTAL);
        shapeGroup.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout modeRow = new LinearLayout(ctx);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.addView(shapeGroup, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        topRow.addView(modeRow);

        LinearLayout sliderHost = column(ctx);
        // The slider column already sits inside `root`'s padding; paying it twice indented the
        // sliders past the rows that label them.
        sliderHost.setPadding(0, 0, 0, 0);
        root.addView(topRow);
        root.addView(sliderHost);

        // Assigned below; declared first because the three builders call each other.
        final Runnable[] rebuild = new Runnable[1];
        // Spec-level controls that nevertheless describe the SELECTED shape (the object link).
        // They are built after this point, so rebuild reaches them through a holder rather
        // than by forward reference, and tolerates being run before they exist.
        final Runnable[] syncSelected = new Runnable[1];

        rebuild[0] = () -> {
            if (sel[0] >= spec.masks.size()) sel[0] = spec.masks.size() - 1;
            if (sel[0] < 0) sel[0] = 0;

            // EMPTY STATE: a "+" and a sentence, nothing else. Every control below describes a
            // shape, and a row of sliders for a shape that does not exist is exactly the kind
            // of thing that made this drawer feel like it had already done something to you.
            if (spec.masks.isEmpty()) {
                shapeGroup.removeAllViews();
                modeRow.removeAllViews();
                sliderHost.removeAllViews();
                TextView add = chip(ctx, "+", dp);
                add.setOnClickListener(v -> {
                    spec.addShape();
                    sel[0] = 0;
                    rebuild[0].run();
                    apply.run();
                });
                shapeGroup.addView(add);
                TextView none = new TextView(ctx);
                none.setText("No mask. Add one to show only part of this object.");
                none.setTextColor(0xFF8A8A8A);
                none.setTextSize(11.5f);
                none.setPadding(Math.round(8 * dp), Math.round(6 * dp),
                        Math.round(8 * dp), Math.round(6 * dp));
                sliderHost.addView(none);
                if (syncSelected[0] != null) syncSelected[0].run();
                return;
            }
            final CompositingSpec.MaskShape cur = spec.masks.get(sel[0]);

            // — chips: one per shape, then + / − —
            shapeGroup.removeAllViews();
            for (int i = 0; i < spec.masks.size(); i++) {
                final int idx = i;
                TextView c = chip(ctx, String.valueOf(i + 1), dp);
                c.setBackgroundColor(idx == sel[0] ? 0x66FFFFFF : 0x22FFFFFF);
                c.setOnClickListener(v -> { sel[0] = idx; rebuild[0].run(); });
                shapeGroup.addView(c);
            }
            TextView addChip = chip(ctx, "+", dp);
            addChip.setOnClickListener(v -> {
                CompositingSpec.MaskShape n = spec.addShape();
                // Offset the newcomer so it is not hidden exactly under the shape it was
                // copied from — an "add" that appears to do nothing reads as a broken button.
                n.cx = Math.min(0.9f, cur.cx + 0.18f);
                n.cy = cur.cy; n.w = cur.w; n.h = cur.h; n.corner = cur.corner;
                sel[0] = spec.masks.size() - 1;
                rebuild[0].run();
                apply.run();
            });
            shapeGroup.addView(addChip);
            {
                // Always offered, including for the LAST shape — removing it is how you get
                // back to an unmasked object, which is where every object now starts.
                TextView del = chip(ctx, "−", dp);
                del.setOnClickListener(v -> {
                    // removeShape drops this slot's keyframe tracks and leaves every other
                    // slot alone, which is what stops shape 3's animation landing on shape 2.
                    spec.removeShape(sel[0]);
                    if (sel[0] > 0) sel[0]--;
                    rebuild[0].run();
                    apply.run();
                });
                shapeGroup.addView(del);
            }

            // — mode, right-justified on the same row: how this shape combines with the ones
            //   before it —
            modeRow.removeAllViews();
            final int[] modes = {CompositingSpec.MODE_ADD, CompositingSpec.MODE_SUBTRACT,
                    CompositingSpec.MODE_INTERSECT};
            final int[] modeIcons = {R.drawable.ic_mask_mode_add_24,
                    R.drawable.ic_mask_mode_subtract_24, R.drawable.ic_mask_mode_intersect_24};
            final String[] modeNames = {"Add", "Subtract", "Intersect"};
            for (int i = 0; i < modes.length; i++) {
                final int mode = modes[i];
                android.widget.ImageView b = iconButton(
                        ctx, modeIcons[i], modeNames[i], cur.mode == mode, dp);
                b.setOnClickListener(v -> {
                    cur.mode = mode;
                    rebuild[0].run();
                    apply.run();
                });
                modeRow.addView(b);
            }

            // — the six per-shape sliders, rebuilt in place —
            // PipOverlayDrawer.switchTo already tweens contentHost's height and restores
            // WRAP_CONTENT afterwards, so changing this column's height needs no extra
            // plumbing here.
            sliderHost.removeAllViews();
            // X/Y run -100..200%, not 0..100 — a mask centre must be able to leave the frame
            // entirely, and must be free to follow a linked object off-stage. KeyframeSet.POS_MIN
            // explains why 0..1 could never express a pan-on/pan-off move.
            final int posMin = Math.round(
                    com.fadcam.ui.faditor.keyframe.KeyframeSet.POS_MIN * 100);
            final int posMax = Math.round(
                    com.fadcam.ui.faditor.keyframe.KeyframeSet.POS_MAX * 100);
            slider(ctx, sliderHost, R.string.faditor_mask_x, posMin, posMax,
                    Math.round(cur.cx * 100),
                    v -> { cur.cx = v / 100f; apply.run(); });
            slider(ctx, sliderHost, R.string.faditor_mask_y, posMin, posMax,
                    Math.round(cur.cy * 100),
                    v -> { cur.cy = v / 100f; apply.run(); });
            slider(ctx, sliderHost, R.string.faditor_mask_w, 100, Math.round(cur.w * 100),
                    v -> { cur.w = Math.max(0.02f, v / 100f); apply.run(); });
            slider(ctx, sliderHost, R.string.faditor_mask_h, 100, Math.round(cur.h * 100),
                    v -> { cur.h = Math.max(0.02f, v / 100f); apply.run(); });
            slider(ctx, sliderHost, R.string.faditor_mask_round, 100, Math.round(cur.corner * 100),
                    v -> { cur.corner = v / 100f; apply.run(); });
            slider(ctx, sliderHost, R.string.faditor_mask_rotate, 360,
                    Math.round(cur.rotationDeg),
                    v -> { cur.rotationDeg = v; apply.run(); });

            // Soften moved IN here, because it is per-shape now: one hard edge and one soft
            // edge in the same mask was not expressible before (user, 2026-08-06).
            slider(ctx, sliderHost, R.string.faditor_mask_soften, 100,
                    Math.round(spec.featherOf(cur) * 100),
                    v -> {
                        if (spec.masks.size() == 1) {
                            // With ONE shape a per-shape override and the stack value are the
                            // same picture, so write the stack one: it needs no v13 stamp and
                            // keeps single-mask projects openable by older builds. Softening
                            // one mask is far too ordinary an act to cost compatibility.
                            spec.maskFeather = v / 100f;
                            cur.feather = -1f;
                        } else {
                            cur.feather = v / 100f;
                        }
                        apply.run();
                    });

            if (syncSelected[0] != null) syncSelected[0].run();
        };
        rebuild[0].run();

        // ── Everything below here is SPEC-level: one value for the whole stack ──────────
        CheckBox inv = check(ctx, R.string.faditor_mask_only_inside, spec.invertMasks);
        inv.setOnCheckedChangeListener((b, on) -> { spec.invertMasks = on; apply.run(); });

        // ── The writers for MaskAnimator ────────────────────────────────────────────────
        // Inline literals: strings.xml is another agent's live file under the working protocol
        // noted in FaditorEditorActivity.

        float d = ctx.getResources().getDisplayMetrics().density;

        // Built directly rather than via check(): that helper takes a STRING RESOURCE id,
        // and passing 0 for an inline literal would throw at inflate time.
        // PER-SHAPE, like the sliders above it — MaskShape.linkedToObject is a shape field, so
        // one shape can ride the object while another stays pinned to the frame. Its checked
        // state is therefore re-synced whenever the selection changes (see syncSelected below);
        // reading spec.masks.get(sel[0]) at CLICK time is what keeps it honest after a switch.
        //
        // NULL when linkSource is null (an adjustment layer): there is no object pose to link
        // to, so the row is left out of checkRow entirely rather than built and disabled.
        @Nullable final CheckBox link;
        if (linkSource != null) {
            link = new CheckBox(ctx);
            link.setText("Move with the object");
            link.setTextColor(TXT);
            link.setTextSize(12);
            link.setChecked(!spec.masks.isEmpty() && spec.masks.get(sel[0]).linkedToObject);
            link.setOnCheckedChangeListener((b, on) -> {
                if (spec.masks.isEmpty()) return;
                CompositingSpec.MaskShape cur = spec.masks.get(sel[0]);
                if (cur.linkedToObject == on) return;   // a re-sync must not re-capture the pose
                cur.linkedToObject = on;
                if (on) {
                    // CAPTURE the object's pose now: "relative to the object" has no origin
                    // otherwise, and the mask would jump the first time the object sat anywhere
                    // but its default pose.
                    com.fadcam.ui.faditor.keyframe.KeyframeSet kf = linkSource.transform();
                    long t = playheadMs.get();
                    cur.linkBaseX =
                            poseAt(kf, com.fadcam.ui.faditor.keyframe.KeyframeSet.X, t, 0.5f);
                    cur.linkBaseY =
                            poseAt(kf, com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, t, 0.5f);
                    cur.linkBaseScale = Math.max(0.001f,
                            poseAt(kf, com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE, t, 1f));
                    cur.linkBaseRotDeg =
                            poseAt(kf, com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION, t, 0f);
                }
                apply.run();
            });
        } else {
            link = null;
        }
        // Both checkboxes share ONE line (user, 2026-08-06) when both exist. Equal weights
        // rather than wrap_content so the two labels cannot jostle each other as their text
        // changes length under translation or a larger font scale. With no link checkbox,
        // invertMasks alone takes the row rather than half of it.
        LinearLayout checkRow = row(ctx);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        checkRow.addView(inv, link != null ? half : new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (link != null) {
            checkRow.addView(link, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(checkRow);

        @Nullable final TextView hint;
        if (link != null) {
            hint = new TextView(ctx);
            hint.setText("Off: the mask stays put and the object moves under it. "
                    + "On: the mask travels with the object.");
            hint.setTextColor(0xFF8A8A8A);
            hint.setTextSize(11.5f);
            hint.setPadding((int) (8 * d), 0, (int) (8 * d), (int) (6 * d));
            root.addView(hint);
        } else {
            hint = null;
        }

        LinearLayout keyRow = new LinearLayout(ctx);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView state = new TextView(ctx);
        state.setTextColor(0xFF8A8A8A);
        state.setTextSize(11.5f);
        state.setPadding((int) (8 * d), (int) (8 * d), 0, 0);
        final Runnable refresh = () ->
                state.setText(spec.hasMaskKeys() ? "animated" : "not animated");

        TextView addKey = chip(ctx, "◆ Key at playhead", d);
        addKey.setOnClickListener(v -> {
            long t = playheadMs.get();
            if (spec.maskKeys == null) {
                spec.maskKeys = new com.fadcam.ui.faditor.keyframe.KeyframeSet();
            }
            com.fadcam.ui.faditor.keyframe.Easing ease =
                    com.fadcam.ui.faditor.keyframe.Easing.EASE_IN_OUT;
            // Keys the SELECTED shape, into tracks named off its SLOT — so reordering or
            // deleting another shape can never redirect these keys onto the wrong geometry.
            // Shape 0 still writes the flat maskCx/maskCy/... names it always did.
            CompositingSpec.MaskShape cur = spec.masks.get(sel[0]);
            int slot = cur.slot;
            float[] vals = {cur.cx, cur.cy, cur.w, cur.h, cur.corner, cur.rotationDeg};
            // All six at once. Half-arming would animate some parameters and snap the rest,
            // which reads as the shape tearing rather than as an incomplete keyframe.
            for (int i = 0; i < MaskAnimator.SHAPE_KEY_COUNT; i++) {
                spec.maskKeys.getOrCreate(MaskAnimator.trackFor(slot, i)).put(t, vals[i], ease);
            }
            // Feather is a property of the STACK, not of a shape, so it has one flat track
            // however many shapes exist — the same split MaskAnimator.trackFor documents.
            spec.maskKeys.getOrCreate(MaskAnimator.FEATHER).put(t, spec.maskFeather, ease);
            refresh.run();
            apply.run();
            android.widget.Toast.makeText(ctx,
                    "Shape " + (sel[0] + 1) + " keyed at " + (t / 1000f) + "s",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        TextView clearKeys = chip(ctx, "Clear", d);
        clearKeys.setOnClickListener(v -> { spec.maskKeys = null; refresh.run(); apply.run(); });
        keyRow.addView(addKey);
        keyRow.addView(clearKeys);
        keyRow.addView(state);
        root.addView(keyRow);
        refresh.run();
        // Everything from the checkbox row down describes a shape stack, so it is hidden while
        // there is no shape — and re-shown the moment one is added. Wired through syncSelected
        // because that is the one hook rebuild already calls on every state change.
        final View[] shapeOnly = hint != null
                ? new View[]{checkRow, hint, keyRow} : new View[]{checkRow, keyRow};
        syncSelected[0] = () -> {
            boolean any = !spec.masks.isEmpty();
            for (View v : shapeOnly) v.setVisibility(any ? View.VISIBLE : View.GONE);
            if (any && link != null) link.setChecked(spec.masks.get(sel[0]).linkedToObject);
        };
        syncSelected[0].run();
        return root;
    }

    /** Supplies the current playhead without this class knowing about the editor. */
    public interface PlayheadSource { long get(); }

    private static float poseAt(@Nullable com.fadcam.ui.faditor.keyframe.KeyframeSet kf,
                                @NonNull String property, long t, float fallback) {
        return kf == null ? fallback : kf.valueAt(property, t, fallback);
    }

    private static TextView chip(@NonNull Context ctx, @NonNull String label, float d) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(12.5f);
        int px = (int) (10 * d), py = (int) (6 * d);
        t.setPadding(px, py, px, py);
        t.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (8 * d);
        t.setLayoutParams(lp);
        return t;
    }

    // ── Tab 2: CHROMA KEY ────────────────────────────────────────────────────────────────

    private static final int[] SWATCHES = {0x00FF00, 0x0000FF, 0x000000, 0xFFFFFF};

    /**
     * Never took a {@code Clip} for anything but its type signature — the tab reads and writes
     * only {@code spec} and the eyedropper goes through {@code host}. Object-agnostic already,
     * which is exactly why an adjustment layer (no clip of its own) can use it unchanged.
     */
    @NonNull
    public static View chromaTab(@NonNull Context ctx,
                                 @NonNull CompositingSpec spec, @NonNull Runnable apply,
                                 @NonNull Host host) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = column(ctx);

        CheckBox on = check(ctx, R.string.faditor_key_enable, spec.keyEnabled);
        root.addView(on);

        LinearLayout body = column(ctx);
        body.setPadding(0, 0, 0, 0);
        root.addView(body);

        final TextView colorLabel = new TextView(ctx);
        colorLabel.setTextColor(TXT_DIM);
        colorLabel.setTextSize(11);
        colorLabel.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        body.addView(colorLabel);
        Runnable refreshColor = () -> colorLabel.setText(ctx.getString(R.string.faditor_key_color)
                + "  ·  " + String.format("#%06X", spec.keyColor & 0xFFFFFF));
        refreshColor.run();

        LinearLayout swatchRow = new LinearLayout(ctx);
        swatchRow.setOrientation(LinearLayout.HORIZONTAL);
        swatchRow.setGravity(Gravity.CENTER_VERTICAL);
        for (int rgb : SWATCHES) {
            View sw = new View(ctx);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0xFF000000 | rgb);
            bg.setStroke(Math.max(1, Math.round(1.5f * d)), 0xFF888888);
            sw.setBackground(bg);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(Math.round(30 * d), Math.round(30 * d));
            lp.rightMargin = Math.round(7 * d);
            sw.setLayoutParams(lp);
            final int c = rgb;
            sw.setOnClickListener(v -> { spec.keyColor = c; refreshColor.run(); apply.run(); });
            swatchRow.addView(sw);
        }
        TextView dropper = new TextView(ctx);
        dropper.setText(R.string.faditor_key_eyedropper);
        dropper.setTextColor(ACCENT);
        dropper.setTextSize(12);
        dropper.setPadding(Math.round(6 * d), Math.round(6 * d),
                Math.round(6 * d), Math.round(6 * d));
        dropper.setOnClickListener(v -> host.pickColorFromPreview(rgb -> {
            if (rgb == null) {
                android.widget.Toast.makeText(ctx, R.string.faditor_key_eyedropper_failed,
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            spec.keyColor = rgb;
            refreshColor.run();
            apply.run();
        }));
        swatchRow.addView(dropper);
        body.addView(swatchRow);

        slider(ctx, body, R.string.faditor_key_tolerance, 100,
                Math.round(spec.keyTolerance * 100),
                v -> { spec.keyTolerance = v / 100f; apply.run(); });
        slider(ctx, body, R.string.faditor_key_softness, 100,
                Math.round(spec.keyFuzziness * 100),
                v -> { spec.keyFuzziness = v / 100f; apply.run(); });
        // Signed, so the bar is 0..200 with 100 meaning zero — a SeekBar cannot start negative
        // and a separate direction control would be worse.
        slider(ctx, body, R.string.faditor_key_spill, 200,
                Math.round(spec.keyOffset * 100) + 100,
                v -> { spec.keyOffset = (v - 100) / 100f; apply.run(); });

        body.setVisibility(spec.keyEnabled ? View.VISIBLE : View.GONE);
        on.setOnCheckedChangeListener((b, checked) -> {
            spec.keyEnabled = checked;
            body.setVisibility(checked ? View.VISIBLE : View.GONE);
            apply.run();
        });
        return root;
    }

    // ── Tab 3: BLEND MODE ────────────────────────────────────────────────────────────────

    private static final String[] BLEND_KEYS = {"NORMAL", "MULTIPLY", "SCREEN", "OVERLAY", "ADD"};
    private static final int[] BLEND_LABELS = {
            R.string.faditor_blend_normal, R.string.faditor_blend_multiply,
            R.string.faditor_blend_screen, R.string.faditor_blend_overlay,
            R.string.faditor_blend_add};

    @NonNull
    public static View blendTab(@NonNull Context ctx, @NonNull Clip clip,
                                @NonNull Runnable apply) {
        // false: a PiP's blend mode is export-only (BlendModeGlEffect has no live-preview
        // counterpart), so the caveat stays exactly as it was for this caller.
        return blendTab(ctx, clip::getOverlayBlendMode, clip::setOverlayBlendMode, apply, false);
    }

    /**
     * Widened off {@code Clip} the same way {@link #maskTab} was — the chip row itself only ever
     * read/wrote one string, so an adjustment layer's {@code blendMode} field plugs in through
     * these two functional params with no new UI code.
     *
     * @param previewsLive true when this caller's blend compiles into the SAME GL source the
     *                     live preview runs (an adjustment layer, via {@code FxGlSource}) — the
     *                     export-only caveat below would be actively wrong there, so it is
     *                     skipped rather than shown and ignored.
     */
    @NonNull
    public static View blendTab(@NonNull Context ctx,
                                @NonNull java.util.function.Supplier<String> getMode,
                                @NonNull java.util.function.Consumer<String> setMode,
                                @NonNull Runnable apply, boolean previewsLive) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = column(ctx);
        // HORIZONTAL chips, not a vertical list. Five one-word options stacked vertically made
        // this the tallest tab while carrying the least information, and the drawer's height is
        // preview the user cannot see (user, 2026-08-05). Wrapped in a HorizontalScrollView so
        // a narrow screen or a longer translation scrolls instead of clipping.
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(ctx);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        final List<TextView> rows = new ArrayList<>();
        for (int i = 0; i < BLEND_KEYS.length; i++) {
            final int idx = i;
            TextView tv = new TextView(ctx);
            tv.setText(BLEND_LABELS[i]);
            tv.setTextSize(12);
            tv.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
            tv.setPadding(Math.round(12 * d), Math.round(7 * d),
                    Math.round(12 * d), Math.round(7 * d));
            GradientDrawable chip = new GradientDrawable();
            chip.setCornerRadius(14f * d);
            chip.setColor(0x22FFFFFF);
            tv.setBackground(chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Math.round(7 * d);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> {
                setMode.accept(BLEND_KEYS[idx]);
                for (int j = 0; j < rows.size(); j++) {
                    rows.get(j).setTextColor(j == idx ? ACCENT : TXT);
                }
                apply.run();
            });
            rows.add(tv);
            strip.addView(tv);
        }
        hs.addView(strip);
        root.addView(hs);
        String cur = getMode.get();
        for (int i = 0; i < BLEND_KEYS.length; i++) {
            rows.get(i).setTextColor(BLEND_KEYS[i].equals(cur) ? ACCENT : TXT);
        }
        if (!previewsLive) {
            TextView note = new TextView(ctx);
            note.setText(R.string.faditor_blend_export_note);
            note.setTextColor(TXT_DIM);
            note.setTextSize(10);
            note.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
            note.setPadding(Math.round(6 * d), Math.round(6 * d), Math.round(6 * d), 0);
            root.addView(note);
        }
        return root;
    }

    // ── shared builders ──────────────────────────────────────────────────────────────────

    @NonNull
    private static LinearLayout column(@NonNull Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(Math.round(14 * d), 0, Math.round(14 * d), Math.round(10 * d));
        return l;
    }

    /**
     * A horizontal chip strip. No side padding: these sit INSIDE a {@link #column}, which has
     * already paid it, and doubling it would step the chip rows in from the sliders they label.
     */
    @NonNull
    private static LinearLayout row(@NonNull Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(0, Math.round(6 * d), 0, Math.round(2 * d));
        return l;
    }

    @NonNull
    private static CheckBox check(@NonNull Context ctx, int labelRes, boolean checked) {
        CheckBox cb = new CheckBox(ctx);
        cb.setText(labelRes);
        cb.setTextColor(TXT);
        cb.setTextSize(12);
        cb.setChecked(checked);
        return cb;
    }

    /** Compact one-line slider: label · value on the left, bar filling the rest. */
    private static void slider(@NonNull Context ctx, @NonNull LinearLayout parent, int labelRes,
                               int max, int initial,
                               @NonNull java.util.function.Consumer<Integer> onChange) {
        slider(ctx, parent, labelRes, 0, max, initial, onChange);
    }

    /**
     * Slider over an arbitrary integer range, including a negative one.
     *
     * <p>SeekBar has no minimum before API 26, and this app's floor is 24, so the bar always
     * runs 0..(max-min) and {@code min} is added on the way out. Doing it here rather than at
     * each call site is what keeps the displayed number and the reported value from drifting
     * apart — they are computed once, from the same expression.</p>
     */
    private static void slider(@NonNull Context ctx, @NonNull LinearLayout parent, int labelRes,
                               int min, int max, int initial,
                               @NonNull java.util.function.Consumer<Integer> onChange) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(ctx);
        label.setTextColor(TXT_DIM);
        label.setTextSize(11);
        label.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        // 78dp wrapped "Soften edges" onto two lines, which made that one row taller than every
        // other and read as a layout fault (user, 2026-08-05). Widened rather than shortened:
        // the labels are already the shortest honest names for these controls, and at a larger
        // system font scale a shorter string would only move the wrap to a different row.
        // maxLines(1) is the belt to the braces — a translation longer than any English label
        // now ellipsizes instead of silently growing the drawer over the preview.
        label.setWidth(Math.round(94 * d));
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setText(ctx.getString(labelRes));
        row.addView(label);

        SeekBar bar = new SeekBar(ctx);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, initial - min)));

        TextView value = new TextView(ctx);
        value.setTextColor(TXT);
        value.setTextSize(11);
        value.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        // Wide enough for "-100" — a negative position used to be unreachable, and a value
        // column sized for "100" would ellipsize the very numbers that prove it now works.
        value.setWidth(Math.round(40 * d));
        value.setGravity(Gravity.END);
        value.setText(String.valueOf(initial));

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                value.setText(String.valueOf(p + min));
                onChange.accept(p + min);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // ‹ ◇ › — one step down, keyframe diamond, one step up (user, 2026-08-06). A slider
        // this narrow cannot be nudged by a single unit with a fingertip, which is exactly the
        // precision wanted when lining a mask up against a face.
        TextView dec = stepper(ctx, "‹", d);
        TextView key = stepper(ctx, "◇", d);
        TextView inc = stepper(ctx, "›", d);
        dec.setOnClickListener(v -> bar.setProgress(Math.max(0, bar.getProgress() - 1)));
        inc.setOnClickListener(v ->
                bar.setProgress(Math.min(max - min, bar.getProgress() + 1)));
        // The diamond is a placeholder until per-parameter mask keying lands: the tab's own
        // "Key at playhead" still keys all six at once. Shown disabled-looking rather than
        // omitted so the row's spacing is final and does not shift when it is wired.
        key.setAlpha(0.35f);

        row.addView(bar, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);
        row.addView(dec);
        row.addView(key);
        row.addView(inc);
        parent.addView(row);
    }

    /**
     * A square icon button for the mask row's mode and preset groups.
     *
     * <p>{@code name} is the accessibility label AND a long-press tooltip: replacing four text
     * chips with four glyphs buys the vertical space back, but it would otherwise take the
     * only statement of what each one does with it.</p>
     */
    @NonNull
    private static android.widget.ImageView iconButton(@NonNull Context ctx, int iconRes,
                                                       @NonNull String name, boolean selected,
                                                       float d) {
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        iv.setImageResource(iconRes);
        iv.setContentDescription(name);
        int pad = Math.round(5 * d);
        iv.setPadding(pad, pad, pad, pad);
        iv.setBackgroundColor(selected ? 0x66FFFFFF : 0x22FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.round(34 * d), Math.round(30 * d));
        lp.rightMargin = Math.round(5 * d);
        iv.setLayoutParams(lp);
        iv.setOnLongClickListener(v -> {
            android.widget.Toast.makeText(ctx, name, android.widget.Toast.LENGTH_SHORT).show();
            return true;
        });
        return iv;
    }

    /** One tap target in a slider row's {@code ‹ ◇ ›} cluster. */
    @NonNull
    private static TextView stepper(@NonNull Context ctx, @NonNull String glyph, float d) {
        TextView t = new TextView(ctx);
        t.setText(glyph);
        t.setTextColor(TXT);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        // 30dp is under the 48dp guideline, but four rows of 48 would push the sliders off a
        // drawer that deliberately leaves the timeline visible. Widened padding rather than
        // height keeps the hit area usable without growing the row.
        t.setWidth(Math.round(30 * d));
        t.setPadding(0, Math.round(4 * d), 0, Math.round(4 * d));
        return t;
    }

    private static float propMin(@NonNull ObjectMenuSheet.Prop p) { return p.min(); }
    private static float propMax(@NonNull ObjectMenuSheet.Prop p) { return p.max(); }

    /**
     * Detents on the Scale row: 10/25/50/75/100/150/200/250/300/350/400 %.
     *
     * <p>100% is the one that actually matters — "the same size as the frame" is a value people
     * aim for and a slider is bad at hitting exactly. The rest are the round numbers either
     * side of it.</p>
     *
     * <p><b>A SNAP, not a step.</b> The value is only pulled to a detent when it is already
     * within {@link #SNAP_PCT} of one, so every intermediate value is still reachable — the
     * user asked for "just a slight snap", and quantising the whole range would make fine
     * adjustment near a detent impossible. Scale only: position and rotation have no
     * privileged numbers, and snapping opacity would fight fades.</p>
     */
    private static final float[] SCALE_DETENTS_PCT =
            {10f, 25f, 50f, 75f, 100f, 150f, 200f, 250f, 300f, 350f, 400f};
    /** Half-width of a detent's pull, in percent of scale. */
    private static final float SNAP_PCT = 2.5f;

    private static float snap(@NonNull ObjectMenuSheet.Prop p, float v) {
        if (!com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE.equals(p.key())) return v;
        float pct = v * 100f;
        for (float d : SCALE_DETENTS_PCT) {
            if (Math.abs(pct - d) <= SNAP_PCT) return d / 100f;
        }
        return v;
    }
}
