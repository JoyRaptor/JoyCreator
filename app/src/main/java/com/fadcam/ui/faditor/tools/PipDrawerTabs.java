package com.fadcam.ui.faditor.tools;

import com.fadcam.ui.faditor.Studio;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.KeyframeDiamondControl;
import com.fadcam.ui.faditor.ObjectMenuSheet;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.CompositingSpec;
import com.fadcam.ui.faditor.model.MaskAnimator;

import java.util.ArrayList;
import java.util.List;

/**
 * Content for {@link ObjectDrawer}'s four tabs.
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
    // Over the frosted scrim, so this is the DRAWER ramp, not the screen ramp. Every chip, row,
    // label, value, slider and checkbox below is built by ObjectDrawer.Kit (record 06's drawer
    // components), so this file says WHAT each control is and the Kit says what it looks like.
    // It used to carry its own chip (square, INK at 13%), its own row padding, and a drop
    // shadow on every label — none of which any other drawer file agreed with.

    private static final int SLIDER_STEPS = 1000;

    /**
     * Width of a {@link #propRow} label. It was 52; the 22dp icon slot now in front of it
     * (16dp glyph + 6dp gap) took 8dp from here so the slider gives up as little as possible on
     * a 360dp phone. The widest labels these rows carry ("Opacity", "Volume") come to ~38dp at
     * 11sp, so 44dp holds them at normal font scale; past that Kit.rowLabel ellipsizes.
     */
    private static final int PROP_LABEL_DP = 44;
    private static final int ROW_ICON_DP = 16;
    private static final int ROW_ICON_GAP_DP = 6;

    /**
     * The picture for a slider row, by its prop key; 0 = none (the slot is still reserved, so an
     * icon-less row does not step its label left of the column). JoyRaptor, 2026-09-24: small
     * descriptive icons on the left of every slider — a glyph is read before the word is, and
     * a column of them lets the hand find "the Y one" without reading.
     *
     * <p>ONE map for every drawer: exact keys first, then a contains/suffix fallback so a new
     * prop with an honest key ("pipVolume", "borderRadius", "centerX") gets a sensible glyph
     * without anyone remembering this table. The {@code mask_*} and {@code key_*} names are this
     * file's own keys for the mask and chroma tabs, whose sliders are not Props.</p>
     */
    @DrawableRes
    public static int iconFor(@Nullable String key) {
        if (key == null) return 0;
        switch (key) {
            case KeyframeSet.X: case "viz_x": case "mask_x":   return R.drawable.ic_prop_pos_x;
            case KeyframeSet.Y: case "viz_y": case "mask_y":   return R.drawable.ic_prop_pos_y;
            case "viz_w": case "mask_w":                       return R.drawable.ic_prop_width;
            case "viz_h": case "mask_h":                       return R.drawable.ic_prop_height;
            case KeyframeSet.SCALE: case KeyframeSet.SCALE_X:
            case KeyframeSet.SCALE_Y:                          return R.drawable.ic_prop_scale;
            case KeyframeSet.ROTATION: case "mask_rotate":     return R.drawable.ic_prop_rotate;
            case KeyframeSet.OPACITY:                          return R.drawable.ic_prop_opacity;
            case "pipVolume":                                  return R.drawable.ic_prop_volume;
            case "mask_round":                                 return R.drawable.ic_prop_roundness;
            case "audio_pan":                                  return R.drawable.ic_prop_pan;
            case "audio_fade_in":                              return R.drawable.ic_prop_fade_in;
            case "audio_fade_out":                             return R.drawable.ic_prop_fade_out;
            case "mask_soften": case "key_softness":           return R.drawable.ic_prop_feather;
            case "key_tolerance":                              return R.drawable.ic_prop_tolerance;
            case "key_spill":                                  return R.drawable.ic_prop_spread;
            default: break;
        }
        String k = key.toLowerCase(java.util.Locale.ROOT);
        // Word matches before the x/y suffix test: "scaleX" is a scale, not a position.
        if (k.contains("scale")) return R.drawable.ic_prop_scale;
        if (k.contains("rot")) return R.drawable.ic_prop_rotate;
        if (k.contains("opac") || k.contains("alpha")) return R.drawable.ic_prop_opacity;
        if (k.contains("volume") || k.contains("gain")) return R.drawable.ic_prop_volume;
        if (k.contains("round") || k.contains("corner") || k.contains("radius")) {
            return R.drawable.ic_prop_roundness;
        }
        if (k.contains("feather") || k.contains("soft")) return R.drawable.ic_prop_feather;
        if (k.contains("toler")) return R.drawable.ic_prop_tolerance;
        if (k.contains("spill") || k.contains("spread") || k.contains("choke")
                || k.contains("expan")) {
            return R.drawable.ic_prop_spread;
        }
        if (k.contains("width") || k.endsWith("_w")) return R.drawable.ic_prop_width;
        if (k.contains("height") || k.endsWith("_h")) return R.drawable.ic_prop_height;
        if (k.endsWith("_x") || key.endsWith("X")) return R.drawable.ic_prop_pos_x;
        if (k.endsWith("_y") || key.endsWith("Y")) return R.drawable.ic_prop_pos_y;
        return 0;
    }

    /**
     * A slider row's leading icon: 16dp, tinted with the row LABEL's ink (Studio.DRAWER_LABEL,
     * the colour Kit.rowLabel gives the word beside it) so glyph and word read as one caption.
     * Decorative to TalkBack — the label next to it already says the same thing.
     */
    @NonNull
    public static View rowIcon(@NonNull Context ctx, @Nullable String key) {
        float d = ctx.getResources().getDisplayMetrics().density;
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        int res = iconFor(key);
        if (res != 0) {
            iv.setImageResource(res);
            iv.setImageTintList(
                    android.content.res.ColorStateList.valueOf(Studio.DRAWER_LABEL));
        }
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.round(ROW_ICON_DP * d), Math.round(ROW_ICON_DP * d));
        lp.setMarginEnd(Math.round(ROW_ICON_GAP_DP * d));
        iv.setLayoutParams(lp);
        return iv;
    }

    /** Everything the tabs need back from the editor. */
    public interface Host {
        long playheadMs();
        /** A value/keyframe changed — repaint preview + timeline and schedule a save. */
        void onChanged();
        /** Arm the eyedropper; the next tap on the preview reports a colour (or null). */
        void pickColorFromPreview(@NonNull ColorPicked cb);
        /**
         * Whether {@link #pickColorFromPreview} can actually sample. A host that cannot (an image
         * or adjustment layer) returns false and the Key tab leaves the eyedropper out, rather
         * than showing a chip that only toasts "isn't available" (drawer audit 2026-09-24, P3).
         */
        default boolean canPickColor() { return true; }
        /** Record one undo step. */
        void recordUndo(@NonNull String label, @NonNull Runnable redo, @NonNull Runnable undo);
        /**
         * A slider drag, dial turn or typed value is ABOUT to change the object: snapshot it.
         * Paired with {@link #endEdit}. These are the undo brackets ObjectMenuSheet's rows have
         * always had (GestureHooks); the drawers replaced the sheet without them, so every
         * Position / Scale / Rotate / Opacity change made in a drawer was missing from Undo.
         * Default no-op for a host whose edits are covered some other way.
         */
        default void beginEdit() { }
        /** That edit ended: record ONE undo step, named {@code label}, if anything changed. */
        default void endEdit(@NonNull String label) { }
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
        TextView note = ObjectDrawer.Kit.note(ctx, ctx.getText(noteRes));
        note.setPadding(0, Math.round(6 * d), 0, Math.round(6 * d));
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
        for (int i = 0; i < props.size(); i++) {
            ObjectMenuSheet.Prop p = props.get(i);
            ObjectMenuSheet.Prop next = i + 1 < props.size() ? props.get(i + 1) : null;
            if (next != null
                    && com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION.equals(p.key())
                    && com.fadcam.ui.faditor.keyframe.KeyframeSet.OPACITY.equals(next.key())) {
                refreshers.add(addRotateOpacityRow(ctx, root, p, next, host));
                i++;
                continue;
            }
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
     * Rotate and Opacity on ONE line when the drawer is wide enough (JoyRaptor, 2026-09-23:
     * "if rotate only takes up half width … move the opacity slider up to save vertical
     * space"). The dial needs 40dp, not a slider's width, and opacity is a 0-100 slider that
     * does not need the full line either. Below {@link #PAIR_MIN_WIDTH_DP} the opacity slider
     * would be squeezed to nothing beside the dial's label, value and diamond, so a narrow
     * phone keeps the two rows stacked.
     */
    @NonNull
    public static Runnable addRotateOpacityRow(@NonNull Context ctx, @NonNull LinearLayout parent,
                                               @NonNull ObjectMenuSheet.Prop rotate,
                                               @NonNull ObjectMenuSheet.Prop opacity,
                                               @NonNull Host host) {
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        if (dm.widthPixels / dm.density < PAIR_MIN_WIDTH_DP) {
            Runnable a = propRow(ctx, parent, rotate, host, null);
            Runnable b = propRow(ctx, parent, opacity, host, null);
            return () -> { a.run(); b.run(); };
        }
        LinearLayout pair = new LinearLayout(ctx);
        pair.setOrientation(LinearLayout.HORIZONTAL);
        pair.setGravity(Gravity.CENTER_VERTICAL);
        Runnable a = propRow(ctx, pair, rotate, host, null, true);
        Runnable b = propRow(ctx, pair, opacity, host, null, true);
        parent.addView(pair);
        return () -> { a.run(); b.run(); };
    }

    /**
     * Narrowest screen (dp) on which Rotate and Opacity share a line. Paired rows show their
     * icon in place of the word (see propRow), so each spends 22dp on its caption instead of 52
     * and 420 still leaves the paired Opacity slider its width.
     */
    private static final int PAIR_MIN_WIDTH_DP = 420;

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

        // Same icon + label column as propRow, so this row lines up with Pos X/Y above it.
        row.addView(rowIcon(ctx, KeyframeSet.SCALE));
        row.addView(ObjectDrawer.Kit.rowLabel(ctx, label, PROP_LABEL_DP));

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
        FineSeekBar bar = new FineSeekBar(ctx);
        ObjectDrawer.Kit.styleSlider(bar);
        // Each split slider says its OWN name (Scale X / Scale Y), not the row's shared word.
        bar.setContentDescription(prop.label());
        bar.setMax(SLIDER_STEPS);
        final float min = propMin(prop), max = propMax(prop);
        float cur = prop.valueAt(host.playheadMs());
        bar.setProgress(Math.round((cur - min) / Math.max(1e-6f, max - min) * SLIDER_STEPS));

        TextView value = ObjectDrawer.Kit.value(ctx, 40);
        value.setText(prop.format(cur));

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                // isFineDriving: a fine drag writes through setProgress, which reports
                // fromUser == false exactly as the playhead-tick refresh does. Without this test
                // the fine mode would move the thumb and change nothing.
                if (!fromUser && !bar.isFineDriving()) return;
                float v = min + (max - min) * (p / (float) SLIDER_STEPS);
                v = snap(prop, v);
                prop.write(v, host.playheadMs());
                value.setText(prop.format(v));
                host.onChanged();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { host.beginEdit(); }
            @Override public void onStopTrackingTouch(SeekBar s) { host.endEdit(prop.label()); }
        });
        row.addView(bar, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);
        // Tappable for the same reason every propRow value is — and this is the row that needs it
        // MOST: scale runs to 1000%, so one slider pixel is several percent, and "set it to exactly
        // 400%" is unreachable by finger. The Scale row has its own builder because its slider
        // count changes with the chain toggle, which is how it came to miss the treatment.
        value.setPaintFlags(value.getPaintFlags()
                | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        value.setPadding(0, Math.round(6 * d), 0, Math.round(6 * d));
        typeHint(ctx, value);

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
        value.setOnClickListener(v -> promptForValue(ctx, prop, host, min, max, selfRefresh[0]));
        return selfRefresh[0];
    }

    /** @return a refresher that re-reads this row's value, slider and on-key state. */
    private static Runnable propRow(@NonNull Context ctx, @NonNull LinearLayout parent,
                                    @NonNull ObjectMenuSheet.Prop prop, @NonNull Host host,
                                    @Nullable View trailing) {
        return propRow(ctx, parent, prop, host, trailing, false);
    }

    /**
     * @param inPair this row shares a line with another ({@link #addRotateOpacityRow}): a
     *               Rotate row then sizes to its content with the dial in a fixed 40dp slot,
     *               and any other row takes the rest of the line.
     */
    private static Runnable propRow(@NonNull Context ctx, @NonNull LinearLayout parent,
                                    @NonNull ObjectMenuSheet.Prop prop, @NonNull Host host,
                                    @Nullable View trailing, boolean inPair) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(2 * d), 0, Math.round(2 * d));

        // [icon] [title] [slider] [value] [◇] — the icon is the one new column (2026-09-24).
        // Paired (Rotate beside Opacity) the icon IS the title: the word would cost the line its
        // room, and the owner asked for these two side by side. It keeps its hover label.
        View icon = rowIcon(ctx, prop.key());
        row.addView(icon);
        if (inPair) {
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            ObjectDrawer.Kit.describe(icon, prop.label());
        } else {
            row.addView(ObjectDrawer.Kit.rowLabel(ctx, prop.label(), PROP_LABEL_DP));
        }

        // SPEC F: the Rotate row is a DIAL, not a slider. A slider tops out and folds a
        // typed 720 back into its window on the next touch; the dial shows the winding
        // and reports raw degrees. The value text, the typing prompt and the keyframe
        // diamond are untouched.
        final boolean isRotation =
                com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION.equals(prop.key());
        final float min = propMin(prop), max = propMax(prop);
        final float cur = prop.valueAt(host.playheadMs());
        final RotationDialView dial;
        final FineSeekBar bar;
        if (isRotation) {
            dial = new RotationDialView(ctx);
            dial.setDegrees(cur);
            bar = null;
        } else {
            dial = null;
            bar = new FineSeekBar(ctx);
            ObjectDrawer.Kit.styleSlider(bar);
            bar.setContentDescription(prop.label());   // drawer audit 2026-09-24, P4
            bar.setMax(SLIDER_STEPS);
            bar.setProgress(Math.round((cur - min) / Math.max(1e-6f, max - min) * SLIDER_STEPS));
        }

        TextView value = ObjectDrawer.Kit.value(ctx, 46);
        value.setText(prop.format(cur));

        if (bar != null) {
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    // isFineDriving: a fine drag writes through setProgress, which reports
                    // fromUser == false exactly as the playhead-tick refresh does. Without this test
                    // the fine mode would move the thumb and change nothing.
                    if (!fromUser && !bar.isFineDriving()) return;
                    float v = min + (max - min) * (p / (float) SLIDER_STEPS);
                    v = snap(prop, v);
                    prop.write(v, host.playheadMs());
                    value.setText(prop.format(v));
                    host.onChanged();
                }
                @Override public void onStartTrackingTouch(SeekBar s) { host.beginEdit(); }
                @Override public void onStopTrackingTouch(SeekBar s) { host.endEdit(prop.label()); }
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(bar, blp);
        } else {
            // Layout only; the gesture wiring is set after selfRefresh exists (below).
            if (inPair) {
                int size = Math.round(40 * d);
                row.addView(dial, new LinearLayout.LayoutParams(size, size));
            } else {
                addDialInSliderSlot(ctx, row, dial);
            }
        }
        row.addView(value);
        // TAP THE NUMBER TO TYPE IT. A 1000-step slider on a 46dp readout cannot land an exact
        // value with a fingertip, and JoyRaptor's report is precisely that (2026-08-12): "I was having
        // issues getting an exact value while scrubbing." A keyboard is the only control that is
        // exact by construction. Underlined so it reads as editable rather than as a label.
        value.setPaintFlags(value.getPaintFlags()
                | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        value.setPadding(0, Math.round(6 * d), 0, Math.round(6 * d));
        typeHint(ctx, value);

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
        if (inPair) {
            parent.addView(row, isRotation
                    ? new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT)
                    : new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        } else {
            parent.addView(row);
        }
        selfRefresh[0] = () -> {
            long ph = host.playheadMs();
            float v = prop.valueAt(ph);
            value.setText(prop.format(v));
            if (dial != null) {
                dial.setDegrees(v);            // raw winding — no window mapping
            } else {
                // setProgress(..., false) — never animate here. This runs on every playhead tick,
                // and an animated thumb chasing a scrub lags behind the frame it is describing.
                bar.setProgress(Math.round((v - min) / Math.max(1e-6f, max - min) * SLIDER_STEPS));
            }
            if (diamond != null) diamond.refresh(ph);
        };
        if (dial != null) {
            dial.setListener(new RotationDialView.Listener() {
                @Override public void onDragStart() { host.beginEdit(); }
                @Override public void onDragDelta() {
                    // Raw degrees: no min/max window, no snap — the winding IS the value.
                    prop.write(dial.getDegrees(), host.playheadMs());
                    value.setText(prop.format(dial.getDegrees()));
                    host.onChanged();
                }
                @Override public void onDragEnd() { host.endEdit(prop.label()); }
                @Override public void onTap() {
                    promptForValue(ctx, prop, host, min, max, selfRefresh[0]);
                }
            });
        }
        value.setOnClickListener(v -> promptForValue(ctx, prop, host, min, max, selfRefresh[0]));
        return selfRefresh[0];
    }

    /**
     * Type an exact value for {@code prop}, in the units its readout shows.
     *
     * <p><b>Units, not raw values.</b> The row shows "400%", so the field must accept 400 — asking
     * for 4.0 because that happens to be how {@code sizeFraction} is stored would make the user
     * translate the app's internals in their head. The factor between the two is derived by
     * PROBING the formatter rather than being declared, because {@code ValueFormat} is a
     * one-method interface implemented by lambdas at dozens of call sites and giving it a
     * unit-scale method would mean editing every one of them.</p>
     *
     * <p>The probe assumes the formatter is LINEAR, and CHECKS that assumption at a third point
     * before trusting it. A formatter that fails the check gets a raw-value field instead: showing
     * someone a box labelled "%" that silently applies a different number is worse than showing
     * them the underlying figure.</p>
     */
    /** Type an exact value for {@code prop}. Public so ObjectMenuSheet's Rotate row can
     *  open the same prompt — the sheet's rows had no value-tap before the dial (SPEC F). */
    public static void promptForValue(@NonNull Context ctx,
                                       @NonNull ObjectMenuSheet.Prop prop, @NonNull Host host,
                                       float min, float max, @Nullable Runnable refresh) {
        float d = ctx.getResources().getDisplayMetrics().density;
        // SPEC A: the ROTATION row takes whole turns ("16x") and raw winding (720, -45), so it
        // skips the unit probe (its readout IS degrees), skips the clamp below, and parses with
        // KeyframeSet.parseRotationInput. Clamping a typed 720 into the slider's [-180, 180]
        // was exactly the bug: the winding IS the animation.
        final boolean isRotation =
                com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION.equals(prop.key());
        // print(v) = scale*v + offset, verified at a third point.
        Float p1 = isRotation ? null : leadingNumber(prop.format(1f));
        Float p2 = isRotation ? null : leadingNumber(prop.format(2f));
        float scale = 1f, offset = 0f;
        boolean inUnits = false;
        if (p1 != null && p2 != null) {
            float s = p2 - p1;
            float o = p1 - s;
            Float p4 = leadingNumber(prop.format(4f));
            if (Math.abs(s) > 1e-6f && p4 != null
                    && Math.abs((s * 4f + o) - p4) <= Math.max(0.75f, Math.abs(p4) * 0.01f)) {
                scale = s;
                offset = o;
                inUnits = true;
            }
        }
        final float sc = scale, off = offset;
        final boolean units = inUnits;
        final long ph = host.playheadMs();
        float cur = prop.valueAt(ph);

        final android.widget.EditText input = new android.widget.EditText(ctx);
        if (isRotation) {
            // TYPE_CLASS_NUMBER has no letters, and the multiplier form NEEDS one: JoyRaptor asked
            // to type "16x" for sixteen turns. A plain text keyboard types digits and x alike;
            // no suggestions so a keyboard cannot "correct" a number.
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        } else {
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        }
        input.setText(isRotation ? trimNumber(cur) : trimNumber(inUnits ? cur * sc + off : cur));
        input.setSelectAllOnFocus(true);
        if (isRotation) {
            input.setHint(R.string.lane_a_rotation_hint);
        } else {
            float lo = inUnits ? min * sc + off : min;
            float hi = inUnits ? max * sc + off : max;
            if (hi < lo) { float t = lo; lo = hi; hi = t; }
            input.setHint(ctx.getString(R.string.lane_a_range_hint,
                    trimNumber(lo), trimNumber(hi)));
        }
        LinearLayout wrap = column(ctx);
        int pad = Math.round(20 * d);
        wrap.setPadding(pad, Math.round(8 * d), pad, 0);
        wrap.addView(input);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(prop.label())
                .setView(wrap)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                    String typedText = input.getText().toString();
                    // Rotation parses the turns grammar; everything else keeps the numeric
                    // head of its formatted readout. Null = nonsense: keep the old value
                    // (the promptForNumber house rule), never snap to zero.
                    Float typed = isRotation
                            ? com.fadcam.ui.faditor.keyframe.KeyframeSet.parseRotationInput(typedText)
                            : leadingNumber(typedText);
                    if (typed == null) return;
                    float v = units && !isRotation ? (typed - off) / sc : typed;
                    if (!isRotation) {
                        // Clamped, not rejected. Someone typing 900% on a slider that stops at 1000%
                        // means "as big as it goes", and an error dialog for it would be pedantry.
                        v = Math.max(Math.min(min, max), Math.min(Math.max(min, max), v));
                    }
                    host.beginEdit();
                    prop.write(v, ph);
                    host.onChanged();
                    host.endEdit(prop.label());
                    if (refresh != null) refresh.run();
                })
                .show();
        input.requestFocus();
    }

    /**
     * The first number in {@code s}, or null. Formatters append units ("%", "°") and may prepend
     * a sign, so this reads the numeric head and ignores the rest rather than requiring the caller
     * to know which suffix a given prop uses.
     */
    @Nullable
    private static Float leadingNumber(@NonNull String s) {
        int i = 0, n = s.length();
        while (i < n && (Character.isWhitespace(s.charAt(i)))) i++;
        int start = i;
        if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
        boolean digits = false, dot = false;
        while (i < n) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') { digits = true; i++; }
            else if (c == '.' && !dot) { dot = true; i++; }
            else break;
        }
        if (!digits) return null;
        try {
            return Float.parseFloat(s.substring(start, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "400" not "400.0"; keeps two decimals only when they carry information. */
    @NonNull
    private static String trimNumber(float v) {
        if (Math.abs(v - Math.round(v)) < 0.005f) return String.valueOf(Math.round(v));
        return String.format(java.util.Locale.US, "%.2f", v);
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

    /**
     * The mask tab for any object: {@code linkSource} supplies its pose for "Move with the
     * object" (object space); null leaves that row out. An image passes
     * {@code TextOverlayItem::timelinePose}, its pose on the timeline clock.
     */
    @NonNull
    public static View maskTab(@NonNull Context ctx, @Nullable LinkSource linkSource,
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
                ObjectDrawer.Kit.describe(add, ctx.getString(R.string.lane_a_mask_add_shape));
                add.setOnClickListener(v -> {
                    spec.addShape();
                    sel[0] = 0;
                    rebuild[0].run();
                    apply.run();
                });
                shapeGroup.addView(add);
                // Drawer label ink, not INK_FAINT: #71717A over the scrim on a bright frame is
                // the unreadable secondary text of record 06's CRITICAL 01.
                TextView none = ObjectDrawer.Kit.note(ctx,
                        ctx.getString(R.string.lane_a_mask_empty));
                none.setTextSize(11.5f);
                none.setPadding(0, Math.round(6 * dp), 0, Math.round(6 * dp));
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
                // The selected shape is the ONE filled chip in the row (record 06 .dchip.on).
                ObjectDrawer.Kit.setChipOn(c, idx == sel[0]);
                ObjectDrawer.Kit.describe(c, ctx.getString(R.string.lane_a_mask_shape_n, i + 1));
                c.setOnClickListener(v -> { sel[0] = idx; rebuild[0].run(); });
                shapeGroup.addView(c);
            }
            TextView addChip = chip(ctx, "+", dp);
            ObjectDrawer.Kit.describe(addChip, ctx.getString(R.string.lane_a_mask_add_shape));
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
                ObjectDrawer.Kit.describe(del, ctx.getString(R.string.lane_a_mask_remove_shape));
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
            // Own strings, not faditor_blend_add: that "Add" is the blend mode's meaning.
            final String[] modeNames = {ctx.getString(R.string.lane_a_mask_mode_add),
                    ctx.getString(R.string.lane_a_mask_mode_subtract),
                    ctx.getString(R.string.lane_a_mask_mode_intersect)};
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
            // ObjectDrawer.switchTo already tweens contentHost's height and restores
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
            slider(ctx, sliderHost, "mask_x", R.string.faditor_mask_x, posMin, posMax,
                    Math.round(cur.cx * 100),
                    v -> { cur.cx = v / 100f; apply.run(); });
            slider(ctx, sliderHost, "mask_y", R.string.faditor_mask_y, posMin, posMax,
                    Math.round(cur.cy * 100),
                    v -> { cur.cy = v / 100f; apply.run(); });
            slider(ctx, sliderHost, "mask_w", R.string.faditor_mask_w, 100, Math.round(cur.w * 100),
                    v -> { cur.w = Math.max(0.02f, v / 100f); apply.run(); });
            slider(ctx, sliderHost, "mask_h", R.string.faditor_mask_h, 100, Math.round(cur.h * 100),
                    v -> { cur.h = Math.max(0.02f, v / 100f); apply.run(); });
            slider(ctx, sliderHost, "mask_round", R.string.faditor_mask_round, 100, Math.round(cur.corner * 100),
                    v -> { cur.corner = v / 100f; apply.run(); });
            // SPEC F / SPEC J — the mask angle is a ROTATION control, and a rotation SLIDER is
            // the SPEC A winding-collapse bug. This is the drawer's LIVE mask tab: the same
            // defect the MaskKeyPanel copy had (converted first). MaskAnimator deltas can store
            // any winding, and a 0..360 bar clamps the stored angle before the finger even
            // moves — and can neither show nor author a turn past 360. Same control the object
            // rows use: raw degrees, countable rings, tap to type.
            maskRotateRow(ctx, sliderHost, Math.round(cur.rotationDeg),
                    v -> { cur.rotationDeg = v; apply.run(); });

            // Soften moved IN here, because it is per-shape now: one hard edge and one soft
            // edge in the same mask was not expressible before (user, 2026-08-06).
            slider(ctx, sliderHost, "mask_soften", R.string.faditor_mask_soften, 100,
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
        float d = ctx.getResources().getDisplayMetrics().density;

        // Built directly rather than via check(): its checked state is re-synced per shape.
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
            link.setText(R.string.faditor_mask_link_object);
            ObjectDrawer.Kit.styleCheck(link);
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
            hint = ObjectDrawer.Kit.note(ctx, ctx.getString(R.string.faditor_mask_link_hint));
            hint.setTextSize(11.5f);
            hint.setPadding(0, 0, 0, (int) (6 * d));
            root.addView(hint);
        } else {
            hint = null;
        }

        LinearLayout keyRow = ObjectDrawer.Kit.row(ctx);
        final TextView state = ObjectDrawer.Kit.note(ctx, "");
        state.setTextSize(11.5f);
        state.setPadding((int) (2 * d), 0, 0, 0);
        final Runnable refresh = () ->
                state.setText(spec.hasMaskKeys() ? R.string.lane_a_mask_animated
                        : R.string.lane_a_mask_static);

        TextView addKey = chip(ctx, ctx.getString(R.string.lane_a_mask_key_chip), d);
        ObjectDrawer.Kit.describe(addKey, ctx.getString(R.string.lane_a_mask_key_here));
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
                    ctx.getString(R.string.lane_a_mask_keyed_toast, sel[0] + 1, t / 1000f),
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        TextView clearKeys = chip(ctx, ctx.getString(R.string.faditor_kf_clear), d);
        ObjectDrawer.Kit.describe(clearKeys, ctx.getString(R.string.lane_a_mask_clear_keys));
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

    /**
     * A drawer chip with its layout params already set. The Kit draws it (record 06
     * {@code .dchip}: round, drawer control fill, 1dp ring); this only places it. It used to be
     * a square of screen INK at 13% with screen-white text — the one control in the drawer that
     * was neither a pill nor on the drawer ramp. {@code d} is unused and kept so the dozen call
     * sites above did not have to change shape.
     */
    private static TextView chip(@NonNull Context ctx, @NonNull String label, float d) {
        TextView t = ObjectDrawer.Kit.chip(ctx, label);
        t.setLayoutParams(ObjectDrawer.Kit.chipLp(ctx));
        return t;
    }

    // ── Tab 2: CHROMA KEY ────────────────────────────────────────────────────────────────

    /**
     * CONTENT, not palette: the colours people actually shoot a key against — green screen, blue
     * screen, black, white. These are the values the KEY is set to, so they must be the real
     * screens, not the nearest app token.
     *
     * <p>Restored. The "373 colours become 28" token pass (e251de04) rewrote this table from
     * {@code 00FF00 0000FF 000000 FFFFFF} to Studio green, near-black and off-white, so the
     * "green screen" swatch has been keying #35F6BF — a mint no green screen is — and the
     * "blue" one a black. That was a find-and-replace on values, not a design decision.</p>
     */
    private static final int[] SWATCHES = {0x00FF00, 0x0000FF, 0x000000, 0xFFFFFF};
    /** Names for {@link #SWATCHES}, index-aligned. */
    private static final int[] SWATCH_NAMES = {R.string.lane_a_key_green,
            R.string.lane_a_key_blue, R.string.lane_a_key_black, R.string.lane_a_key_white};

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

        final TextView colorLabel = ObjectDrawer.Kit.note(ctx, "");
        colorLabel.setTextSize(11);
        body.addView(colorLabel);
        // & 0x00FFFFFF, the RGB mask. The same token pass turned it into & 0xF4F4F5, which
        // silently printed white as #F4F4F5 and zeroed low bits of every other key colour.
        Runnable refreshColor = () -> colorLabel.setText(ctx.getString(R.string.faditor_key_color)
                + "  ·  " + String.format("#%06X", spec.keyColor & 0x00FFFFFF));
        refreshColor.run();

        LinearLayout swatchRow = ObjectDrawer.Kit.row(ctx);
        for (int si = 0; si < SWATCHES.length; si++) {
            final int rgb = SWATCHES[si];
            View sw = new View(ctx);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Studio.GROUND | rgb);
            // A ring in the drawer's label ink so the black swatch exists at all on a dark scrim.
            bg.setStroke(Math.max(1, Math.round(1.5f * d)), Studio.DRAWER_LABEL);
            ObjectDrawer.Kit.describe(sw, ctx.getString(SWATCH_NAMES[si]));
            sw.setBackground(bg);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(Math.round(30 * d), Math.round(30 * d));
            lp.rightMargin = Math.round(7 * d);
            sw.setLayoutParams(lp);
            final int c = rgb;
            sw.setOnClickListener(v -> { spec.keyColor = c; refreshColor.run(); apply.run(); });
            swatchRow.addView(sw);
        }
        // A chip, not violet text: it is a control, and the violet was Avatar's room colour
        // borrowed for "tap me" — a role it does not have. Left out entirely where the host
        // cannot sample the preview (drawer audit 2026-09-24, P3): a chip that only toasts
        // "isn't available" is a dead knob.
        if (host.canPickColor()) {
            TextView dropper = ObjectDrawer.Kit.chip(ctx,
                    ctx.getText(R.string.faditor_key_eyedropper));
            ObjectDrawer.Kit.pressable(dropper);
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
        }
        body.addView(swatchRow);

        slider(ctx, body, "key_tolerance", R.string.faditor_key_tolerance, 100,
                Math.round(spec.keyTolerance * 100),
                v -> { spec.keyTolerance = v / 100f; apply.run(); });
        slider(ctx, body, "key_softness", R.string.faditor_key_softness, 100,
                Math.round(spec.keyFuzziness * 100),
                v -> { spec.keyFuzziness = v / 100f; apply.run(); });
        // Signed, so the bar is 0..200 with 100 meaning zero — a SeekBar cannot start negative
        // and a separate direction control would be worse.
        slider(ctx, body, "key_spill", R.string.faditor_key_spill, 200,
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

    // No key/label arrays here any more. They were a THIRD hand-maintained copy of the mode list
    // (after BlendModes.ALL and the BlendMode enum), and with twenty-six modes a copy that has to
    // stay index-aligned with two others is a mode the picker silently mislabels. The single chip
    // below asks BlendPickerPopover, which is asserted against BlendModes.ALL by the harness.

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
        // ONE CHIP, not a strip. Seven modes fitted in a scrolling chip row; twenty-six do not --
        // that row became a horizontal hunt past modes you are not looking for (JoyRaptor, 2026-09-04).
        // The chip states the CURRENT mode and opens the grouped, columned popover.
        root.addView(BlendPickerPopover.chip(ctx, getMode, setMode, apply));
        if (!previewsLive) {
            TextView note = ObjectDrawer.Kit.note(ctx, ctx.getText(R.string.faditor_blend_export_note));
            note.setPadding(0, Math.round(6 * d), 0, 0);
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
        // 11dp sides: record 06 .dr / .dsec. It was 14.
        l.setPadding(Math.round(11 * d), 0, Math.round(11 * d), Math.round(10 * d));
        return l;
    }

    /**
     * A horizontal chip strip. No side padding: these sit INSIDE a {@link #column}, which has
     * already paid it, and doubling it would step the chip rows in from the sliders they label.
     */
    @NonNull
    private static LinearLayout row(@NonNull Context ctx) {
        return ObjectDrawer.Kit.row(ctx);
    }

    @NonNull
    private static CheckBox check(@NonNull Context ctx, int labelRes, boolean checked) {
        CheckBox cb = new CheckBox(ctx);
        cb.setText(labelRes);
        ObjectDrawer.Kit.styleCheck(cb);
        cb.setChecked(checked);
        return cb;
    }

    /** Compact one-line slider: label · value on the left, bar filling the rest. */
    private static void slider(@NonNull Context ctx, @NonNull LinearLayout parent,
                               @Nullable String iconKey, int labelRes,
                               int max, int initial,
                               @NonNull java.util.function.Consumer<Integer> onChange) {
        slider(ctx, parent, iconKey, labelRes, 0, max, initial, onChange);
    }

    /**
     * Slider over an arbitrary integer range, including a negative one.
     *
     * <p>SeekBar has no minimum before API 26, and this app's floor is 24, so the bar always
     * runs 0..(max-min) and {@code min} is added on the way out. Doing it here rather than at
     * each call site is what keeps the displayed number and the reported value from drifting
     * apart — they are computed once, from the same expression.</p>
     */
    private static void slider(@NonNull Context ctx, @NonNull LinearLayout parent,
                               @Nullable String iconKey, int labelRes,
                               int min, int max, int initial,
                               @NonNull java.util.function.Consumer<Integer> onChange) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // 78dp wrapped "Soften edges" onto two lines, which made that one row taller than every
        // other and read as a layout fault (user, 2026-08-05). Widened rather than shortened:
        // the labels are already the shortest honest names for these controls, and at a larger
        // system font scale a shorter string would only move the wrap to a different row.
        // maxLines(1) is the belt to the braces — a translation longer than any English label
        // now ellipsizes instead of silently growing the drawer over the preview.
        // The same leading icon as propRow (2026-09-24). The label keeps its 94dp (the reason is
        // just above, and "Choke / spread" is longer still), so these rows' slider pays for the
        // 22dp slot; the ‹ › steppers beside it still give single-unit precision.
        row.addView(rowIcon(ctx, iconKey));
        row.addView(ObjectDrawer.Kit.rowLabel(ctx, ctx.getString(labelRes), 94));

        FineSeekBar bar = new FineSeekBar(ctx);
        ObjectDrawer.Kit.styleSlider(bar);
        bar.setContentDescription(ctx.getString(labelRes));   // drawer audit 2026-09-24, P4
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, initial - min)));

        // Wide enough for "-100" — a negative position used to be unreachable, and a value
        // column sized for "100" would ellipsize the very numbers that prove it now works.
        TextView value = ObjectDrawer.Kit.value(ctx, 40);
        value.setText(String.valueOf(initial));

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                value.setText(String.valueOf(p + min));
                onChange.accept(p + min);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // ‹ · › — one step down, a reserved slot, one step up (user, 2026-08-06). A slider
        // this narrow cannot be nudged by a single unit with a fingertip, which is exactly the
        // precision wanted when lining a mask up against a face.
        TextView dec = ObjectDrawer.Kit.stepper(ctx, "‹", ctx.getString(R.string.lane_a_step_down));
        TextView inc = ObjectDrawer.Kit.stepper(ctx, "›", ctx.getString(R.string.lane_a_step_up));
        dec.setOnClickListener(v -> bar.setProgress(Math.max(0, bar.getProgress() - 1)));
        inc.setOnClickListener(v ->
                bar.setProgress(Math.min(max - min, bar.getProgress() + 1)));

        row.addView(bar, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);
        row.addView(dec);
        row.addView(diamondSlot(ctx));
        row.addView(inc);
        parent.addView(row);
    }

    /**
     * The mask-angle row: a {@link RotationDialView}, not a slider (SPEC F / SPEC J).
     *
     * <p>Same one-line shape as {@link #slider} — label · control · value · ‹ ◇ › — so the
     * per-shape column keeps one rhythm. Raw degrees in every direction: MaskAnimator deltas
     * can store any winding, so the value text and the typed prompt accept it all, and the
     * dial shows the rings instead of folding them. A 0..360 bar could neither show nor
     * author a turn past 360, and clamped a stored angle outside its window on the way in.</p>
     */
    private static void maskRotateRow(@NonNull Context ctx, @NonNull LinearLayout parent,
                                      int initial,
                                      @NonNull java.util.function.Consumer<Float> onChange) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(rowIcon(ctx, "mask_rotate"));
        row.addView(ObjectDrawer.Kit.rowLabel(ctx, ctx.getString(R.string.faditor_mask_rotate), 94));

        RotationDialView dial = new RotationDialView(ctx);
        dial.setDegrees(initial);

        // Wide enough for "-45" and "720" — windings a mask can genuinely hold.
        TextView value = ObjectDrawer.Kit.value(ctx, 40);
        value.setText(String.valueOf(initial));

        // ‹ · › — same nudge pair and reserved slot the other mask rows carry (see slider()).
        TextView dec = ObjectDrawer.Kit.stepper(ctx, "‹", ctx.getString(R.string.lane_a_step_down));
        TextView inc = ObjectDrawer.Kit.stepper(ctx, "›", ctx.getString(R.string.lane_a_step_up));
        dec.setOnClickListener(v -> {
            dial.setDegrees(dial.getDegrees() - 1f);
            value.setText(String.valueOf(Math.round(dial.getDegrees())));
            onChange.accept(dial.getDegrees());
        });
        inc.setOnClickListener(v -> {
            dial.setDegrees(dial.getDegrees() + 1f);
            value.setText(String.valueOf(Math.round(dial.getDegrees())));
            onChange.accept(dial.getDegrees());
        });

        dial.setListener(new RotationDialView.Listener() {
            @Override public void onDragStart() { }
            @Override public void onDragDelta() {
                value.setText(String.valueOf(Math.round(dial.getDegrees())));
                onChange.accept(dial.getDegrees());
            }
            @Override public void onDragEnd() { }
            @Override public void onTap() { promptMaskAngle(ctx, dial, value, onChange); }
        });
        value.setOnClickListener(v -> promptMaskAngle(ctx, dial, value, onChange));
        typeHint(ctx, value);

        addDialInSliderSlot(ctx, row, dial);
        row.addView(value);
        row.addView(dec);
        row.addView(diamondSlot(ctx));
        row.addView(inc);
        parent.addView(row);
    }

    /**
     * A rotation dial placed where a row's slider would be: the slot takes the slider's weight
     * and the 40dp dial sits at its start, so the value, diamond and steppers after it land in
     * the same column as every slider row's. At its bare 40dp the row's tail slid left to
     * mid-drawer and the column broke.
     */
    private static void addDialInSliderSlot(@NonNull Context ctx, @NonNull LinearLayout row,
                                            @NonNull RotationDialView dial) {
        int size = Math.round(40 * ctx.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout slot = new android.widget.FrameLayout(ctx);
        slot.addView(dial, new android.widget.FrameLayout.LayoutParams(
                size, size, Gravity.START | Gravity.CENTER_VERTICAL));
        row.addView(slot, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    /**
     * Type an exact mask angle. Raw winding accepted (−45, 720) — the whole point of the dial
     * is that the winding IS the value, so the keyboard must not be a narrower mind than the
     * control it serves. Same idiom as the MaskKeyPanel copy of this row.
     */
    private static void promptMaskAngle(@NonNull Context ctx, @NonNull RotationDialView dial,
                                        @NonNull TextView value,
                                        @NonNull java.util.function.Consumer<Float> onChange) {
        android.widget.EditText input = new android.widget.EditText(ctx);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.valueOf(Math.round(dial.getDegrees())));
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.faditor_mask_rotate)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                    try {
                        float v = Float.parseFloat(input.getText().toString().trim());
                        dial.setDegrees(v);
                        value.setText(String.valueOf(Math.round(v)));
                        onChange.accept(v);
                    } catch (NumberFormatException ignored) { }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
        ObjectDrawer.Kit.describe(iv, name);
        // 36 × 36 with 8dp padding: the same 20dp glyph the old 34 × 30 / 5dp box fitted, in a
        // box a finger can hit. The face is the Kit's: the selected mode is the filled one.
        int pad = Math.round(8 * d);
        iv.setPadding(pad, pad, pad, pad);
        ObjectDrawer.Kit.setIconOn(iv, selected);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.round(36 * d), Math.round(36 * d));
        lp.rightMargin = Math.round(5 * d);
        iv.setLayoutParams(lp);
        iv.setOnLongClickListener(v -> {
            android.widget.Toast.makeText(ctx, name, android.widget.Toast.LENGTH_SHORT).show();
            return true;
        });
        return iv;
    }

    /**
     * The empty column between a mask row's {@code ‹} and {@code ›}. It held a ◇ that was dimmed,
     * had no listener and did nothing — six dead diamonds per shape (drawer audit 2026-09-24,
     * P1/P2). The tab's own "Key at playhead" chip keys a shape; the slot keeps the stepper
     * width so every row's column lines up, and per-row keying can land here later.
     */
    @NonNull
    private static View diamondSlot(@NonNull Context ctx) {
        android.widget.Space s = new android.widget.Space(ctx);
        s.setLayoutParams(new LinearLayout.LayoutParams(
                ObjectDrawer.Kit.dp(ctx, ObjectDrawer.Kit.STEPPER_DP), 1));
        return s;
    }

    /**
     * A tap-to-type readout's hover hint. Tooltip ONLY: a content description would replace
     * the number TalkBack reads, which is the thing the user wants to hear (drawer audit
     * 2026-09-24, P5; the same call AudioDrawerTabs makes).
     */
    private static void typeHint(@NonNull Context ctx, @NonNull TextView value) {
        androidx.core.view.ViewCompat.setTooltipText(value,
                ctx.getString(R.string.lane_a_value_type_hint));
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
