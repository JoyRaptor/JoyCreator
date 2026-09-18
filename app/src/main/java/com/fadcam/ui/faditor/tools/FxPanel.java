package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.fx.FxCost;
import com.fadcam.ui.faditor.fx.FxEffectDef;
import com.fadcam.ui.faditor.fx.FxInstance;
import com.fadcam.ui.faditor.fx.FxParam;
import com.fadcam.ui.faditor.fx.FxPresetStore;
import com.fadcam.ui.faditor.fx.FxPreviewTier;
import com.fadcam.ui.faditor.fx.FxRegistry;
import com.fadcam.ui.faditor.fx.FxStack;
import com.fadcam.ui.faditor.fx.GradientCurve;
import com.fadcam.ui.faditor.fx.GradientRamp;

import java.util.List;

/**
 * The FX stack editor — JoyRaptor's 🪄 tab (SPEC_ADJUSTMENT_LAYERS_FX M6).
 *
 * <p>An ordered list of collapsible cards, bottom-up, each with its own parameters, opacity and
 * blend mode. Generate a texture, blur THAT, gradient-map the result — order is meaning, and it
 * is the same reading the export chain already has.</p>
 *
 * <p><b>A plain {@code LinearLayout}, not a {@code RecyclerView}.</b> There is no
 * {@code ItemTouchHelper} anywhere in this repo, and the drawer already owns a {@code ScrollView}
 * with a height tween. Introducing a second scrolling container inside it is how a drawer starts
 * fighting itself.</p>
 *
 * <p><b>Reorder by DRAGGING THE ☰ HANDLE.</b> Long-press it and the card lifts; neighbours slide
 * aside to open the gap; release to drop. The ▲/▼ arrows are gone.</p>
 *
 * <p><b>What the previous version got wrong, twice over.</b> This class used to claim a
 * long-press-the-header drag that could never fire. The tab lives inside the drawer's
 * {@code ScrollView} and nothing called {@code requestDisallowInterceptTouchEvent}, so the list
 * scrolled instead of the card lifting; and the handler committed {@code stack.move} on every
 * crossing, each rebuilding the panel and destroying the view that owned the in-flight gesture.
 * The arrows were not a precise alternative to a working drag — they were the only reorder that
 * ran at all. See {@code installDragHandle}: disallow interception on pickup, move VIEWS during
 * the drag, commit the MODEL once on release. Slots are stable, so keyframes follow the card
 * whichever mechanism moves it.</p>
 */
public final class FxPanel {

    private FxPanel() {}

    private static final int TXT = 0xFFF4F4F5;
    private static final int TXT_DIM = 0xFF8A8A94;
    private static final int CARD_BG = 0x1AFFFFFF;
    private static final int CHIP_BG = 0x22FFFFFF;
    private static final int CHIP_ON = 0x66FFFFFF;

    /** What the panel needs back from the editor. */
    public interface Host {
        /** A value changed — repaint and schedule a save. */
        void onFxChanged();
        /** Record one undo step. */
        void recordUndo(@NonNull String label, @NonNull Runnable redo, @NonNull Runnable undo);
        /**
         * ABSOLUTE timeline ms — the base FxStack's keyframes are stored in, and the same one
         * overlayTransform and maskKeys already use, so no conversion happens anywhere.
         */
        long playheadMs();

        /**
         * Move the editor playhead to {@code ms} (absolute timeline) — what the keyframe
         * control's {@code ‹ ›} chevrons and swipe-nav call to jump to the previous/next key.
         * Default no-op so pre-existing hosts keep compiling; the editor's hosts implement it.
         */
        default void seekTo(long ms) {}

        /**
         * A caveat about what this preview will NOT show, or {@code ""} when it will show
         * everything.
         *
         * <p>Asked of the host rather than computed here because it depends on the PROJECT, not
         * on the device: the live preview grades the video plane, and a PiP composited beneath
         * the layer is graded only on export. Saying that unconditionally would nag the ordinary
         * case — most projects have no PiP at all — and saying it never would leave the one user
         * it affects to discover it in a finished render.</p>
         */
        default String previewCaveat() { return ""; }

        /**
         * Put the preview into (or out of) direct-manipulation mode for a {@link
         * FxParam.Kind#CURVE} param — the gradient Curve's anchors, vertices and bezier handles
         * become draggable dots over the video.
         *
         * <p>Asked of the HOST because this panel has no idea where the preview is, nor how the
         * canvas maps to screen pixels; the editor does, and it already owns the single gesture
         * authority over that surface. Default no-op so the hosts that are not the editor — and
         * cannot show a preview at all — keep compiling and simply offer no such control.</p>
         *
         * @param card {@code null} to leave the mode and clear the handles.
         */
        default void editGradientInPreview(@Nullable FxStack stack, @Nullable FxInstance card,
                                        @Nullable FxParam param) {}

        /** Whether {@code param} on {@code card} is the one currently being edited in the
         *  preview — so the chip can read as a toggle instead of a one-way trip. */
        default boolean isEditingGradientInPreview(@Nullable FxInstance card,
                                                @Nullable FxParam param) { return false; }
    }

    /**
     * Build the panel for {@code stack}.
     *
     * <p>Rebuilt wholesale on any structural change (add / delete / move). The stack is at most
     * a dozen cards and the drawer restores {@code WRAP_CONTENT} after its own height tween, so
     * a rebuild is cheaper than keeping incremental view state correct — and incremental view
     * state that disagrees with the model is exactly the bug this avoids.</p>
     */
    @NonNull
    public static View build(@NonNull Context ctx, @NonNull FxStack stack, @NonNull Host host) {
        return build(ctx, stack, host, FxPreviewTier.Subject.LAYER);
    }

    /**
     * @param subject what the stack is attached to. It changes only what the panel SAYS: a
     *                blur is fine on an adjustment layer and cannot render on a single object,
     *                and the card has to tell the truth about which one this is.
     */
    @NonNull
    public static View build(@NonNull Context ctx, @NonNull FxStack stack, @NonNull Host host,
                             @NonNull FxPreviewTier.Subject subject) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(12 * d);
        root.setPadding(pad, 0, pad, Math.round(10 * d));

        // Live-refresh registry for keyframe diamonds / keyframed sliders. Attached to the root
        // tag and re-populated on every rebuild; the editor calls FxPanel.refreshRows(root, ms)
        // on playhead ticks.
        final RefreshState rs = new RefreshState();
        root.setTag(REFRESH_TAG, rs);

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            root.removeAllViews();
            rs.entries.clear();
            root.addView(headerRow(ctx, stack, host, rebuild[0], d));
            List<FxInstance> cards = stack.cards();
            if (cards.isEmpty()) {
                root.addView(emptyNote(ctx, d, subject));
            }
            // TOP-DOWN on screen, bottom-up in the model. The last card in the list is applied
            // last, so it belongs at the TOP of a vertical list — the same way every layer
            // stack in every editor reads. Iterating the list forwards here would show the
            // chain upside down.
            for (int i = cards.size() - 1; i >= 0; i--) {
                root.addView(card(ctx, stack, cards.get(i), i, host, rebuild[0], d, subject, rs));
            }
            root.addView(addRow(ctx, stack, host, rebuild[0], d, subject));
            root.addView(presetRow(ctx, stack, host, rebuild[0], d, subject));
        };
        rebuild[0].run();
        return root;
    }

    /**
     * Run a structural change to the stack as ONE undoable step.
     *
     * <p>{@code Host.recordUndo} has been declared since this panel was written and was never
     * called from it: adding, deleting, bypassing and reordering effects all mutated the model
     * silently, so the editor's undo button skipped straight past them to whatever timeline edit
     * came before. Deleting a card is the worst of those — {@code FxStack.remove} also drops the
     * slot's keyframe tracks, so an accidental ✕ threw away hand-animated curves with no way
     * back. That is why this snapshots rather than trying to invert each operation: the tracks
     * live in the stack too, and a whole-stack copy restores them without every call site having
     * to remember they exist.</p>
     */
    private static void structural(@NonNull FxStack stack, @NonNull Host host,
                                   @NonNull Runnable rebuild, @NonNull String label,
                                   @NonNull Runnable mutate) {
        FxStack before = stack.copy();
        mutate.run();
        rebuild.run();
        host.onFxChanged();
        recordSnapshot(stack, host, rebuild, label, before);
    }

    /**
     * Record one undo step spanning {@code before} → the stack's current contents.
     *
     * <p>Split out of {@link #structural} because a SLIDER cannot use it: the value stream has
     * to be applied live and the step recorded once on release, so the snapshot is taken at a
     * different moment from the record. Sharing this keeps both on whole-stack copies, which is
     * what makes keyframe tracks survive the round trip.</p>
     */
    public static void recordSnapshot(@NonNull FxStack stack, @NonNull Host host,
                                       @NonNull Runnable rebuild, @NonNull String label,
                                       @NonNull FxStack before) {
        FxStack after = stack.copy();
        // A no-op edit records nothing — tapping the blend mode a card is already on, or a
        // slider gesture that ended where it began, must not put a dead press on the stack.
        if (after.toJson().toString().equals(before.toJson().toString())) return;
        host.recordUndo(label,
                () -> { stack.copyFrom(after); rebuild.run(); host.onFxChanged(); },
                () -> { stack.copyFrom(before); rebuild.run(); host.onFxChanged(); });
    }

    // ── Header ──────────────────────────────────────────────────────────────

    @NonNull
    private static View headerRow(@NonNull Context ctx, @NonNull FxStack stack,
                                  @NonNull Host host, @NonNull Runnable rebuild, float d) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(4 * d), 0, Math.round(6 * d));

        TextView cost = new TextView(ctx);
        cost.setTextColor(TXT_DIM);
        cost.setTextSize(11.5f);
        // A METER, never a refusal. Refusing an edit is worse than a slow preview: the user can
        // see slow and decide, but cannot see a refusal and understand it.
        FxCost.Estimate est = FxCost.estimate(stack);
        cost.setText(stack.isEmpty() ? "No effects" : est.label());
        row.addView(cost, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // On a phone that cannot preview effects, say so ONCE at the top rather than badging
        // every card. Silence here would leave the user adding effect after effect and seeing
        // nothing, with no way to tell a broken feature from an unsupported one.
        String note = FxPreviewTier.headerNote();
        String caveat = host.previewCaveat();
        if (!caveat.isEmpty()) note = note.isEmpty() ? caveat : note + " " + caveat;
        if (note.isEmpty()) return row;
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(row);
        TextView warn = new TextView(ctx);
        warn.setText(note);
        warn.setTextColor(0xFFFBBF24);
        warn.setTextSize(11f);
        warn.setPadding(0, 0, 0, Math.round(6 * d));
        col.addView(warn);
        return col;
    }

    /**
     * The empty state, which has to name the RIGHT subject. It said "everything beneath the
     * layer takes it" on a PiP's own Effects tab — describing an adjustment layer to someone
     * looking at an object, and promising a reach the object's stack does not have.
     */
    @NonNull
    private static View emptyNote(@NonNull Context ctx, float d,
                                  @NonNull FxPreviewTier.Subject subject) {
        TextView t = new TextView(ctx);
        t.setText(subject == FxPreviewTier.Subject.LAYER
                ? "This layer changes nothing yet. Add an effect and everything beneath the "
                        + "layer takes it."
                : "No effects on this object yet. Add one and it rides with the object — it "
                        + "affects this and nothing else.");
        t.setTextColor(TXT_DIM);
        t.setTextSize(11.5f);
        t.setPadding(0, Math.round(8 * d), 0, Math.round(10 * d));
        return t;
    }

    // ── One card ────────────────────────────────────────────────────────────

    @NonNull
    private static View card(@NonNull Context ctx, @NonNull FxStack stack,
                             @NonNull FxInstance fx, int index, @NonNull Host host,
                             @NonNull Runnable rebuild, float d,
                             @NonNull FxPreviewTier.Subject subject,
                             @NonNull RefreshState rs) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg(d));
        int p = Math.round(8 * d);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(6 * d);
        card.setLayoutParams(lp);

        FxEffectDef def = fx.def();
        if (def == null) return card;

        // ── header: caret · name · enable · move · delete ──
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        // Borderless: a disclosure triangle does not need a box around it, and the box was
        // making the least important control in the row look like one of the most important.
        TextView caret = iconBtn(ctx, fx.collapsed ? "▸" : "▾", d);
        caret.setOnClickListener(v -> { fx.collapsed = !fx.collapsed; rebuild.run(); });
        head.addView(caret);
        installDragHandle(ctx, head, card, stack,
                (stack.size() - 1) - index, stack.size(), host, rebuild, d);

        TextView name = new TextView(ctx);
        name.setText(def.displayName);
        name.setTextColor(fx.enabled ? TXT : TXT_DIM);
        name.setTextSize(13f);
        name.setPadding(Math.round(4 * d), 0, 0, 0);
        head.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Say on the CARD where this effect will and will not appear. A blur that is silently
        // skipped on export is the worst kind of missing feature: the user adds it, sees
        // nothing, and has no way to tell that from a bug.
        String note = FxPreviewTier.cardNote(def, subject);
        if (!note.isEmpty()) {
            TextView warn = new TextView(ctx);
            warn.setText(note);
            warn.setTextColor(0xFFFBBF24);
            warn.setTextSize(9.5f);
            warn.setPadding(0, 0, Math.round(6 * d), 0);
            head.addView(warn);
        }

        // Bypass, which is also the cheap escape: a disabled card contributes NO pass at all.
        TextView eye = chip(ctx, fx.enabled ? "◉" : "◌", d);
        eye.setOnClickListener(v -> structural(stack, host, rebuild,
                fx.enabled ? "Bypass effect" : "Enable effect",
                () -> fx.enabled = !fx.enabled));
        head.addView(eye);

        // The ▲/▼ pair is gone. They were clumsy, and they were also the ONLY reorder that
        // worked — the long-press drag this class documented could never fire (the enclosing
        // ScrollView ate the gesture, and rebuilding mid-drag destroyed the view holding the
        // listener). The handle below is the replacement, and it actually runs.
        TextView del = iconBtn(ctx, "✕", d);
        // No confirm dialog: the delete is now a recorded undo step, and undo is a better
        // answer than a modal on every ✕. remove() also deletes this slot's keyframe tracks
        // and retires the slot, so nothing added later can inherit them — the snapshot in
        // structural() is what brings those curves back.
        del.setOnClickListener(v -> structural(stack, host, rebuild,
                "Delete " + def.displayName, () -> stack.remove(index)));
        head.addView(del);
        card.addView(head);

        if (fx.collapsed) return card;

        // ── parameters ──
        // For gradient_fill, some params are shape-dependent:
        // - the Curve path only shows when shape == Curve (index 5)
        // - Curve reads NONE of centre/angle/scale: its own anchors are the gradient line, so
        //   leaving those three up would offer controls that do nothing on the selected shape
        // - Angle is hidden for Radial (index 1) since radial has no angle
        // - Scale is shown for Radial and Diamond/Box (index 4)
        // SHAPE IS gradient_fill's PARAM 0 AND NOBODY ELSE'S. Reading params.get(0) for every
        // effect made each one's first parameter masquerade as a shape enum, and the rules below
        // then hid controls on effects that have no shapes at all: Noise/Clouds' first param is
        // "mode", so switching it off Clouds (1) made its Scale slider vanish — Scale being the
        // control that actually does something on noise. Directional Blur and RGB Shift are one
        // "angle" rule away from the same fate. -1 can never equal a rule's constant, so a
        // non-gradient effect now falls through every branch and shows all of its parameters.
        boolean gradientFill = "gradient_fill".equals(def.id);
        int currentShape = gradientFill ? Math.round(fx.getScalar(def.params.get(0))) : -1;
        boolean curveShape = gradientFill && currentShape == 5;
        for (FxParam param : def.params) {
            if (param.kind == FxParam.Kind.CURVE && !curveShape) continue;
            if (curveShape && (param.name.equals("center") || param.name.equals("angle")
                    || param.name.equals("scale"))) {
                continue;
            }
            // Skip Angle for Radial shape (radial has no angle)
            if (param.name.equals("angle") && currentShape == 1) continue;
            // Skip Scale for non-Radial/non-Diamond shapes
            if (param.name.equals("scale") && currentShape != 1 && currentShape != 4) continue;
            // Length is the Linear/Reflected extent only — the radiating shapes use Scale.
            if (gradientFill && param.name.equals("length")
                    && currentShape != 0 && currentShape != 3) {
                continue;
            }

            LinearLayout pr = new LinearLayout(ctx);
            pr.setOrientation(LinearLayout.HORIZONTAL);
            pr.setGravity(Gravity.CENTER_VERTICAL);
            View row = paramRow(ctx, stack, fx, param, host, rebuild, d, rs);
            pr.addView(row, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (param.keyable) pr.addView(diamond(ctx, stack, fx, param, host, rebuild, d, rs));
            card.addView(pr);
        }

        // The non-curve gradient shapes get the SAME preview manipulation the Curve does, bound
        // to their centre param — Photoshop drags a gradient's start and end on the picture, and
        // a centre slider plus an angle slider is a worse way to say the same thing. The Curve
        // shape already carries its own chip inside curveRow, so it is excluded here.
        FxParam centerParam = gradientFill ? def.param("center") : null;
        if (centerParam != null && !curveShape) {
            boolean posEditing = host.isEditingGradientInPreview(fx, centerParam);
            TextView pos = chip(ctx, posEditing
                    ? "Positioning in preview — done" : "Position in preview", d);
            pos.setBackground(pill(posEditing ? CHIP_ON : CHIP_BG, d));
            pos.setOnClickListener(v -> {
                host.editGradientInPreview(posEditing ? null : stack, posEditing ? null : fx,
                        posEditing ? null : centerParam);
                rebuild.run();
            });
            card.addView(pos);
        }

        // ── fold controls: how this card's result lands on what is below it ──
        card.addView(sliderRow(ctx, "Opacity", 0, 100, Math.round(fx.opacity * 100),
                v -> { fx.opacity = v / 100f; host.onFxChanged(); }, d,
                stack, host, rebuild, def.displayName + " opacity"));

        // ONE chip that opens the grouped picker, not a chip per mode. This row used to lay out
        // every mode side by side; at twenty-six that is a card wider than the phone. The chip
        // reads out the card's current fold mode, which is the thing you actually want to see.
        LinearLayout blendRow = new LinearLayout(ctx);
        blendRow.setOrientation(LinearLayout.HORIZONTAL);
        blendRow.setGravity(Gravity.CENTER_VERTICAL);
        blendRow.setPadding(0, Math.round(4 * d), 0, 0);
        blendRow.addView(label(ctx, "Blend", d));
        blendRow.addView(BlendPickerPopover.chip(ctx, () -> fx.blendMode,
                mode -> structural(stack, host, rebuild, def.displayName + " blend",
                        () -> fx.blendMode = mode),
                () -> {}));
        card.addView(blendRow);
        return card;
    }

    @NonNull
    private static String pretty(@NonNull String mode) {
        if (mode.length() < 2) return mode;
        return mode.charAt(0) + mode.substring(1).toLowerCase();
    }

    // ── Parameter rows ──────────────────────────────────────────────────────

    @NonNull
    private static View paramRow(@NonNull Context ctx, @NonNull FxStack stack,
                                 @NonNull FxInstance fx, @NonNull FxParam param,
                                 @NonNull Host host, @NonNull Runnable rebuild, float d,
                                 @NonNull RefreshState rs) {
        switch (param.kind) {
            case BOOL: {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView label = label(ctx, param.label, d);
                row.addView(label);
                boolean on = fx.getScalar(param) >= 0.5f;
                TextView c = chip(ctx, on ? "On" : "Off", d);
                c.setBackground(pill(on ? CHIP_ON : CHIP_BG, d));
                c.setOnClickListener(v -> {
                    c.setText(on ? "Off" : "On");
                    c.setBackground(pill(on ? CHIP_BG : CHIP_ON, d));
                    structural(stack, host, rebuild, param.label,
                            () -> fx.set(param, on ? 0f : 1f));
                });
                row.addView(c);
                return row;
            }
            case ENUM: {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(label(ctx, param.label, d));
                // The label is fixed; the CHIP cluster scrolls. A shape picker like Gradient's
                // seven entries ("Linear"…"Curve (soon)") overflows a 360dp screen and the last
                // chips were CUT OFF at the right edge (device finding, 2026-08-09) — invisible,
                // not reachable. Same idiom as the "Saved looks" row: scroll the overflow, keep
                // every option reachable. The selection repaint loop below walks `row`'s children,
                // which are the scroll view and the label, so it now iterates the cluster instead.
                android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(ctx);
                scroll.setHorizontalScrollBarEnabled(false);
                scroll.setFillViewport(false);
                LinearLayout cluster = new LinearLayout(ctx);
                cluster.setOrientation(LinearLayout.HORIZONTAL);
                cluster.setGravity(Gravity.CENTER_VERTICAL);
                String[] labels = param.enumLabels();
                for (int i = 0; i < labels.length; i++) {
                    final int idx = i;
                    TextView c = chip(ctx, labels[i], d);
                    c.setBackground(pill(Math.round(fx.getScalar(param)) == i ? CHIP_ON : CHIP_BG, d));
                    c.setOnClickListener(v -> {
                        FxStack before = stack.copy();
                        fx.set(param, idx);
                        host.onFxChanged();
                        // Repaint the cluster's chips without rebuilding the whole panel.
                        for (int k = 0; k < cluster.getChildCount(); k++) {
                            View child = cluster.getChildAt(k);
                            if (child instanceof TextView && child != v) {
                                child.setBackground(pill(CHIP_BG, d));
                            }
                        }
                        v.setBackground(pill(CHIP_ON, d));
                        recordSnapshot(stack, host, rebuild, param.label, before);
                    });
                    cluster.addView(c);
                }
                scroll.addView(cluster);
                row.addView(scroll, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                return row;
            }
            case COLOR: {
                // A round swatch that opens THE app-wide picker — JoyRaptor's mandate (2026-08-08):
                // "use this for every colour swatch you build". Live while the dialog is open,
                // one undo step recorded on Set (Cancel already reverts via ColorPickerDialog's
                // own onLive replay, so nothing here needs to snapshot twice).
                return colorSwatchRow(ctx, stack, fx, param, host, rebuild, d);
            }
            case POINT: {
                // Multi-component parameters get one slider per component, suffixed exactly as
                // their keyframe tracks are, so the row and the track cannot disagree.
                LinearLayout col = new LinearLayout(ctx);
                col.setOrientation(LinearLayout.VERTICAL);
                float[] cur = fx.get(param);
                for (int i = 0; i < param.kind.components; i++) {
                    final int comp = i;
                    String suffix = param.componentSuffix(i);
                    col.addView(sliderRow(ctx, param.label + suffix, 0, 100,
                            Math.round(cur[i] * 100),
                            v -> {
                                float[] vals = fx.get(param);
                                vals[comp] = v / 100f;
                                fx.set(param, vals);
                                host.onFxChanged();
                            }, d, stack, host, rebuild, param.label));
                }
                return col;
            }
            case GRADIENT: {
                return gradientRow(ctx, stack, fx, param, host, rebuild, d);
            }
            case CURVE: {
                return curveRow(ctx, stack, fx, param, host, rebuild, d);
            }
            case FLOAT:
            default: {
                // The slider works in whole units of the descriptor's own range, so a 0..1
                // parameter reads as a percentage and a 0..64 radius reads as pixels — rather
                // than every control pretending to be a percentage of something. When the range
                // is too tight to aim at (max-min < 4, e.g. Gamma 0.1..4), the slider is scaled
                // x100 and `scale` is 100 so the value maps back correctly — m5: the old code
                // left scale=1 there, so the slider spanned 10..400 while the param clamped at 4.
                int min = Math.round(param.min);
                int max = Math.round(param.max);
                final boolean percent = (max - min) < 4;
                if (percent) { min = Math.round(param.min * 100); max = Math.round(param.max * 100); }
                final float scale = percent ? 100f : 1f;
                return floatParamSlider(ctx, stack, fx, param, host, rebuild, d, rs,
                        min, max, scale);
            }
        }
    }

    /**
     * A FLOAT param slider that is also a KEYFRAME control. When this param's track is armed,
     * the slider shows the INTERPOLATED value at the playhead (and follows the playhead on a
     * scrub), and dragging it writes a key at the playhead instead of a static value — the same
     * "slide between the values the keys have locked in" behaviour the PiP drawer rows have
     * (JoyRaptor, 2026-08-08: "any slider throughout the entire system should slide between the
     * values that the key frames have locked in at").
     */
    @NonNull
    private static View floatParamSlider(@NonNull Context ctx, @NonNull FxStack stack,
                                         @NonNull FxInstance fx, @NonNull FxParam param,
                                         @NonNull Host host, @NonNull Runnable rebuild, float d,
                                         @NonNull RefreshState rs,
                                         int min, int max, float scale) {
        final String track = fx.track(param, 0);
        final boolean[] armed = {false};
        Runnable scanArmed = () -> armed[0] = stack.keys != null
                && stack.keys.hasProperty(track);
        scanArmed.run();

        final java.util.function.Supplier<Float> animated = () -> {
            float staticVal = fx.getScalar(param);
            if (armed[0] && stack.keys != null) {
                return stack.keys.valueAt(track, host.playheadMs(), staticVal);
            }
            return staticVal;
        };

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(ctx, param.label, d));

        SeekBar bar = new SeekBar(ctx);
        bar.setMax(Math.max(1, max - min));
        TextView value = new TextView(ctx);
        value.setTextColor(TXT);
        value.setTextSize(11f);
        value.setWidth(Math.round(40 * d));
        value.setGravity(Gravity.END);

        Runnable render = () -> {
            scanArmed.run();
            float v = animated.get();
            bar.setProgress(Math.max(0, Math.min(max - min,
                    Math.round(v * scale) - min)));
            value.setText(String.valueOf(Math.round(v * scale)));
        };
        render.run();

        // Dragging writes a key when the track is armed, else the static value — one undo step
        // per gesture either way (snapshot on touch-down, record on release, same as sliderRow).
        // The listener is attached AFTER the wrap/hint block below; keep this comment there.
        final FxStack[] snap = {null};
        row.addView(bar, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);

        // U4: the diamond's purpose is undiscoverable on an un-keyed param. Same C7 honesty
        // hint the ObjectMenuSheet rows use — "Static — tap ◆ to animate", flashed once under
        // the row on the first un-armed drag, then faded. Feedback only; it never auto-keys.
        final TextView[] hint = {null};
        final boolean[] hintShown = {false};
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        TextView h = new TextView(ctx);
        h.setTextColor(0xFF8A8A94);
        h.setTextSize(10.5f);
        h.setPadding(Math.round(86 * d), 0, 0, 0);
        h.setVisibility(View.GONE);
        wrap.addView(h);
        hint[0] = h;
        Runnable flashHint = () -> {
            if (armed[0] || hintShown[0]) return;
            hintShown[0] = true;
            h.setText(com.fadcam.R.string.faditor_fx_static_hint);
            h.setAlpha(1f);
            h.setVisibility(View.VISIBLE);
            h.animate().cancel();
            h.postDelayed(() -> h.animate().alpha(0f).setDuration(400)
                    .withEndAction(() -> h.setVisibility(View.GONE)).start(), 2500);
        };
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                value.setText(String.valueOf(p + min));
                if (!armed[0]) flashHint.run();
                if (snap[0] == null) snap[0] = stack.copy();   // both paths need an undo target
                if (armed[0]) {
                    long at = host.playheadMs();
                    if (stack.keys == null) stack.keys = new com.fadcam.ui.faditor.keyframe.KeyframeSet();
                    stack.keys.getOrCreate(track).put(at, (p + min) / scale,
                            com.fadcam.ui.faditor.keyframe.Easing.EASE_IN_OUT);
                    host.onFxChanged();
                } else {
                    fx.set(param, (p + min) / scale);
                    host.onFxChanged();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                // Snapshot for BOTH paths — an un-armed static drag is an edit too, and without
                // a snapshot it silently produces no undo (adversarial review, M1).
                if (snap[0] == null) snap[0] = stack.copy();
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                if (snap[0] != null) {
                    recordSnapshot(stack, host, rebuild, param.label, snap[0]);
                }
                snap[0] = null;
            }
        });

        rs.entries.add(playheadMs -> {
            if (!armed[0]) return;   // static param: nothing to re-read
            float v = stack.keys != null
                    ? stack.keys.valueAt(track, playheadMs, fx.getScalar(param))
                    : fx.getScalar(param);
            bar.setProgress(Math.max(0, Math.min(max - min,
                    Math.round(v * scale) - min)));
            value.setText(String.valueOf(Math.round(v * scale)));
        });
        return wrap;
    }

    /** The round swatch + label row every COLOR param now gets — see {@link #paramRow}'s note. */
    @NonNull
    private static View colorSwatchRow(@NonNull Context ctx, @NonNull FxStack stack,
                                       @NonNull FxInstance fx, @NonNull FxParam param,
                                       @NonNull Host host, @NonNull Runnable rebuild, float d) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(ctx, param.label, d));

        float[] cur = fx.get(param);
        int initial = 0xFF000000
                | (Math.round(cur[0] * 255f) << 16)
                | (Math.round(cur[1] * 255f) << 8)
                | Math.round(cur[2] * 255f);

        View swatch = new View(ctx);
        int sz = Math.round(24 * d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        swatch.setLayoutParams(lp);
        paintSwatch(swatch, initial, d);

        swatch.setOnClickListener(v -> {
            FxStack before = stack.copy();
            ColorPickerDialog.show(ctx, param.label, initial, false,
                    live -> {
                        if (live == null) return;
                        fx.set(param, new float[]{
                                ((live >> 16) & 0xFF) / 255f,
                                ((live >> 8) & 0xFF) / 255f,
                                (live & 0xFF) / 255f});
                        paintSwatch(swatch, live, d);
                        host.onFxChanged();
                    },
                    picked -> {
                        if (picked != null) {
                            fx.set(param, new float[]{
                                    ((picked >> 16) & 0xFF) / 255f,
                                    ((picked >> 8) & 0xFF) / 255f,
                                    (picked & 0xFF) / 255f});
                        }
                        recordSnapshot(stack, host, rebuild, param.label, before);
                    });
        });
        row.addView(swatch);
        return row;
    }

    private static void paintSwatch(@NonNull View swatch, int color, float d) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(0xFF000000 | (color & 0xF4F4F5));
        bg.setStroke(Math.round(1 * d), 0x55FFFFFF);
        swatch.setBackground(bg);
    }

    /**
     * The gradient ramp row: a thin live preview strip plus an "Edit" chip that opens {@link
     * GradientRampEditorView} in a dialog — the standard app-wide gradient editor, wrapped in
     * exactly the "one editing session, one undo step" pattern every other structural edit in
     * this panel already uses.
     */
    @NonNull
    private static View gradientRow(@NonNull Context ctx, @NonNull FxStack stack,
                                    @NonNull FxInstance fx, @NonNull FxParam param,
                                    @NonNull Host host, @NonNull Runnable rebuild, float d) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(4 * d), 0, Math.round(4 * d));

        View strip = new View(ctx);
        GradientRamp preview = GradientRamp.fromFloatArray(fx.get(param));
        strip.setBackground(stripDrawable(preview, d));
        row.addView(strip, new LinearLayout.LayoutParams(
                0, Math.round(18 * d), 1f));

        TextView edit = chip(ctx, "Edit", d);
        edit.setOnClickListener(v -> openGradientDialog(ctx, stack, fx, param, host, rebuild, d));
        row.addView(edit);
        strip.setOnClickListener(v -> openGradientDialog(ctx, stack, fx, param, host, rebuild, d));
        return row;
    }

    /**
     * The Curve path's controls: how many vertices the line bends through, and a toggle that
     * hands the actual placing over to the preview.
     *
     * <p><b>No X/Y sliders.</b> The parameter is up to five positions and five handle vectors —
     * twenty numbers. Exposing them as forty pixels of seek bar each would be unusable, and it
     * would also be a second way to move something the user can already see and grab, which is
     * the "two controls fighting over one result" trap. Numbers here would only be a worse
     * version of the preview.</p>
     */
    @NonNull
    private static View curveRow(@NonNull Context ctx, @NonNull FxStack stack,
                                 @NonNull FxInstance fx, @NonNull FxParam param,
                                 @NonNull Host host, @NonNull Runnable rebuild, float d) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);

        GradientCurve path = GradientCurve.fromFloatArray(fx.get(param));

        // Changing the path from a CHIP has to reach the preview's handles too. They read the
        // param live, but nothing tells the overlay to repaint — re-asserting the mode does, and
        // it is a no-op when the preview is not currently hosting them.
        final Runnable repaintPreview = () -> {
            if (host.isEditingGradientInPreview(fx, param)) {
                host.editGradientInPreview(stack, fx, param);
            }
        };

        LinearLayout vRow = new LinearLayout(ctx);
        vRow.setOrientation(LinearLayout.HORIZONTAL);
        vRow.setGravity(Gravity.CENTER_VERTICAL);
        vRow.addView(label(ctx, "Vertices", d));
        for (int i = 0; i <= GradientCurve.VERTEX_CAP; i++) {
            final int n = i;
            TextView c = chip(ctx, String.valueOf(i), d);
            c.setBackground(pill(path.vertices.size() == i ? CHIP_ON : CHIP_BG, d));
            c.setOnClickListener(v -> structural(stack, host, rebuild, "Curve vertices", () -> {
                GradientCurve p = GradientCurve.fromFloatArray(fx.get(param));
                p.setVertexCount(n);
                fx.set(param, p.toFloatArray());
                repaintPreview.run();
            }));
            vRow.addView(c);
        }
        col.addView(vRow);

        LinearLayout eRow = new LinearLayout(ctx);
        eRow.setOrientation(LinearLayout.HORIZONTAL);
        eRow.setGravity(Gravity.CENTER_VERTICAL);
        boolean editing = host.isEditingGradientInPreview(fx, param);
        TextView edit = chip(ctx, editing ? "Editing in preview — done" : "Edit in preview", d);
        edit.setBackground(pill(editing ? CHIP_ON : CHIP_BG, d));
        edit.setOnClickListener(v -> {
            host.editGradientInPreview(editing ? null : stack, editing ? null : fx,
                    editing ? null : param);
            rebuild.run();
        });
        eRow.addView(edit);

        TextView reset = chip(ctx, "Straighten", d);
        // Not "Reset": it puts the anchors back across the frame AND zeroes every handle, which
        // is the one action that reliably gets a user out of a curve they have tangled.
        reset.setOnClickListener(v -> structural(stack, host, rebuild, "Straighten curve", () -> {
            fx.set(param, GradientCurve.defaultCurve().toFloatArray());
            repaintPreview.run();
        }));
        eRow.addView(reset);
        col.addView(eRow);
        return col;
    }

    @NonNull
    private static android.graphics.drawable.Drawable stripDrawable(@NonNull GradientRamp r, float d) {
        // A cheap linear approximation for the small preview strip — the real, exact ramp bar
        // lives inside GradientRampEditorView, which shares FxCompiler's own segment math.
        int steps = 12;
        int[] colors = new int[steps + 1];
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            int rgb = r.sampleColor(t);
            int a = Math.round(r.sampleAlpha(t) * 255f);
            colors[i] = (a << 24) | (rgb & 0xF4F4F5);
        }
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, colors);
        g.setCornerRadius(4f * d);
        return g;
    }

    private static void openGradientDialog(@NonNull Context ctx, @NonNull FxStack stack,
                                           @NonNull FxInstance fx, @NonNull FxParam param,
                                           @NonNull Host host, @NonNull Runnable rebuild, float d) {
        FxStack before = stack.copy();
        GradientRampEditorView editor = new GradientRampEditorView(ctx);
        editor.setRamp(GradientRamp.fromFloatArray(fx.get(param)));
        editor.setOnLiveChangeListener(ramp -> {
            fx.set(param, ramp.toFloatArray());
            host.onFxChanged();
        });
        int pad = Math.round(12 * d);
        FrameLayoutPad wrap = new FrameLayoutPad(ctx, pad);
        wrap.addView(editor);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(param.label)
                .setView(wrap)
                .setPositiveButton("Done", (dlg, w) ->
                        recordSnapshot(stack, host, rebuild, param.label, before))
                .setOnCancelListener(dlgi ->
                        recordSnapshot(stack, host, rebuild, param.label, before))
                .show();
    }

    /** A FrameLayout with uniform padding — {@code MaterialAlertDialogBuilder.setView} does not
     *  pad its content, and this widget's touch targets sit flush against the dialog edge
     *  without it. */
    private static final class FrameLayoutPad extends android.widget.FrameLayout {
        FrameLayoutPad(@NonNull Context ctx, int pad) {
            super(ctx);
            setPadding(pad, pad, pad, pad);
        }
    }

    // ── Small builders ──────────────────────────────────────────────────────

    @NonNull
    private static View sliderRow(@NonNull Context ctx, @NonNull String labelText,
                                  int min, int max, int initial,
                                  @NonNull java.util.function.Consumer<Integer> onChange,
                                  float d) {
        return sliderRow(ctx, labelText, min, max, initial, onChange, d, null, null, null, "");
    }

    /**
     * A slider that records ONE undo step per drag.
     *
     * <p>Snapshot on {@code onStartTrackingTouch}, record on {@code onStopTrackingTouch}: a drag
     * emits a value stream, so recording per callback would bury the undo stack and recording
     * nothing — which is what happened before — left every parameter edit unrecoverable. No
     * rebuild on release, because that would destroy the {@link SeekBar} the finger just let go
     * of; the undo/redo lambdas rebuild, and they run outside the gesture.</p>
     */
    @NonNull
    private static View sliderRow(@NonNull Context ctx, @NonNull String labelText,
                                  int min, int max, int initial,
                                  @NonNull java.util.function.Consumer<Integer> onChange,
                                  float d, @Nullable FxStack stack, @Nullable Host host,
                                  @Nullable Runnable rebuild, @NonNull String undoLabel) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(ctx, labelText, d));

        SeekBar bar = new SeekBar(ctx);
        bar.setMax(Math.max(1, max - min));
        bar.setProgress(Math.max(0, Math.min(max - min, initial - min)));

        TextView value = new TextView(ctx);
        value.setTextColor(TXT);
        value.setTextSize(11f);
        value.setWidth(Math.round(40 * d));
        value.setGravity(Gravity.END);
        value.setText(String.valueOf(initial));

        final FxStack[] snap = {null};
        final boolean[] tracking = {false};
        final boolean recordable = stack != null && host != null && rebuild != null;
        // Settles a run of non-touch changes into ONE step. A D-pad hold or a TalkBack
        // "increment" repeat should read as a single edit, the same way a drag does.
        final Runnable settle = !recordable ? null : () -> {
            if (snap[0] != null) recordSnapshot(stack, host, rebuild, undoLabel, snap[0]);
            snap[0] = null;
        };
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                // KEYBOARD, D-PAD AND TALKBACK NEVER SEND THE TRACKING CALLBACKS. AbsSeekBar
                // handles DPAD_LEFT/RIGHT and the accessibility scroll actions by writing the
                // progress directly, so the touch-only path recorded nothing at all: the value
                // changed, the save was scheduled, and undo skipped to the previous edit. Snap
                // BEFORE applying, or the "before" would already contain the change.
                if (fromUser && recordable && snap[0] == null) snap[0] = stack.copy();
                value.setText(String.valueOf(p + min));
                onChange.accept(p + min);
                if (fromUser && recordable && !tracking[0]) {
                    s.removeCallbacks(settle);
                    s.postDelayed(settle, 350);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                tracking[0] = true;
                s.removeCallbacks(settle);
                if (recordable && snap[0] == null) snap[0] = stack.copy();
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                tracking[0] = false;
                if (recordable && snap[0] != null) {
                    recordSnapshot(stack, host, rebuild, undoLabel, snap[0]);
                }
                snap[0] = null;
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);
        return row;
    }

    @NonNull
    private static TextView label(@NonNull Context ctx, @NonNull String text, float d) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(TXT_DIM);
        t.setTextSize(11f);
        t.setWidth(Math.round(86 * d));
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /**
     * A selectable/stateful chip — a PILL, matching {@code PipDrawerTabs.blendTab}.
     *
     * <p>This used to be {@code setBackgroundColor}: a hard rectangle with no corner radius, at
     * 26x28dp. Seven of them in one card header gave every control identical weight, all of them
     * under half the 48dp minimum, and the visible box made each look bigger than it was
     * tappable. The blend tab next door had already solved this with a 14dp-radius
     * {@code GradientDrawable}; this is that, so the two panels stop disagreeing.</p>
     *
     * <p>The rule now: <b>chrome only where it carries STATE.</b> Toggles and selectable chips
     * get a pill; pure actions get {@link #iconBtn}.</p>
     */
    @NonNull
    private static TextView chip(@NonNull Context ctx, @NonNull String text, float d) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(TXT);
        t.setTextSize(12f);
        int px = Math.round(12 * d), py = Math.round(7 * d);
        t.setPadding(px, py, px, py);
        t.setBackground(pill(CHIP_BG, d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Math.round(7 * d);
        t.setLayoutParams(lp);
        return t;
    }

    /**
     * The ☰ grab handle, and the drag session it starts.
     *
     * <p><b>Why this is a rewrite rather than a tweak.</b> The previous drag could not work for
     * two independent reasons. The tab lives inside {@code ObjectDrawer}'s {@code ScrollView},
     * and nothing called {@code requestDisallowInterceptTouchEvent}, so the moment the finger
     * passed touch slop the list scrolled instead of the card lifting. And it committed
     * {@code stack.move} on every crossing, each of which rebuilt the panel and destroyed the
     * very view holding the touch listener — Android then delivered ACTION_CANCEL to a detached
     * view, ending the gesture after at most one swap.</p>
     *
     * <p>So: disallow interception on pickup, move VIEWS during the drag and the MODEL once on
     * release, and animate the displaced neighbours instead of rebuilding under the finger.</p>
     *
     * @param screenPos this card's position in the on-screen list, top-down.
     */
    private static void installDragHandle(@NonNull Context ctx, @NonNull LinearLayout head,
                                          @NonNull View card, @NonNull FxStack stack,
                                          int screenPos, int count,
                                          @NonNull Host host, @NonNull Runnable rebuild,
                                          float d) {
        View handle = grabHandle(ctx, d);
        head.addView(handle, 0);
        if (count < 2) {
            // Dimmed AND answerable. A greyed control with no explanation reads as broken; the
            // user has said so about other dimmed things in this panel. One card cannot be
            // reordered, and saying that costs a toast.
            handle.setAlpha(0.25f);
            handle.setOnClickListener(v -> android.widget.Toast.makeText(ctx,
                    com.fadcam.R.string.faditor_fx_reorder_hint,
                    android.widget.Toast.LENGTH_SHORT).show());
            return;
        }

        final float[] downY = {0f};
        final float[] lastRawY = {0f};
        final boolean[] lifted = {false};
        final int[] shift = {0};
        /** Pixels the enclosing ScrollView has auto-scrolled during THIS drag. */
        final int[] scrolled = {0};
        final Runnable[] autoScroll = {null};

        // One place that turns "finger is here" into "card is there, neighbours are apart",
        // because the auto-scroller has to re-run exactly the same maths without a MotionEvent.
        final Runnable applyDrag = () -> {
            ViewGroup parent = (ViewGroup) card.getParent();
            if (parent == null) return;
            // Scrolling moves the card with the content, so the finger's raw-Y delta alone
            // would let the card slide out from under the finger the moment the list scrolls.
            float dy = lastRawY[0] - downY[0] + scrolled[0];
            card.setTranslationY(dy);
            int h = Math.max(1, card.getHeight());
            int want = Math.max(-screenPos, Math.min(count - 1 - screenPos,
                    rowsCrossed(parent, card, dy)));
            if (want != shift[0]) {
                shift[0] = want;
                slideNeighbours(parent, card, screenPos, want, h);
                card.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
        };

        // Auto-scroll: the drawer is capped and scrollable now, so a chain of eight effects is
        // taller than the tab. Without this, a card can only ever be dragged as far as the
        // visible edge and reordering across a scroll is impossible.
        autoScroll[0] = new Runnable() {
            @Override public void run() {
                if (!lifted[0]) return;
                android.widget.ScrollView sv = scrollParent(card);
                if (sv != null && sv.getHeight() > 0) {
                    int[] loc = new int[2];
                    sv.getLocationOnScreen(loc);
                    float zone = 56 * d;
                    float y = lastRawY[0] - loc[1];
                    int dir = y < zone ? -1 : (y > sv.getHeight() - zone ? 1 : 0);
                    if (dir != 0) {
                        int before = sv.getScrollY();
                        sv.scrollBy(0, dir * Math.round(9 * d));
                        int delta = sv.getScrollY() - before;
                        if (delta != 0) {
                            scrolled[0] += delta;
                            applyDrag.run();
                        }
                    }
                }
                card.postOnAnimation(this);
            }
        };

        handle.setOnLongClickListener(v -> {
            lifted[0] = true;
            shift[0] = 0;
            scrolled[0] = 0;
            ViewGroup parent = (ViewGroup) card.getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            card.animate().translationZ(6 * d).scaleX(1.03f).scaleY(1.03f).alpha(0.92f)
                    .setDuration(120).start();
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            card.postOnAnimation(autoScroll[0]);
            return true;
        });

        handle.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downY[0] = ev.getRawY();
                    lastRawY[0] = ev.getRawY();
                    return false;   // let the long-press detector arm first
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (!lifted[0]) return false;
                    ViewGroup parent = (ViewGroup) card.getParent();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                    lastRawY[0] = ev.getRawY();
                    applyDrag.run();
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (!lifted[0]) return false;
                    lifted[0] = false;   // also stops the auto-scroller on its next frame
                    card.removeCallbacks(autoScroll[0]);
                    card.animate().translationZ(0).scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(120).start();
                    // AN INTERRUPTED GESTURE REVERTS — never commits (the fcc0bd5 rule, which
                    // OverlayVideoPreviewView states outright). CANCEL is what arrives when a
                    // dialog opens, the window loses focus, or the drawer is dismissed mid-drag;
                    // committing on it silently reorders the effect chain, and therefore changes
                    // the render, when the user let go of nothing.
                    if (ev.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                        shift[0] = 0;
                    }
                    if (shift[0] != 0) {
                        int[] fromTo = com.fadcam.ui.faditor.fx.FxReorder.indices(
                                screenPos, shift[0], count);
                        structural(stack, host, rebuild, "Reorder effects",
                                () -> stack.move(fromTo[0], fromTo[1]));
                    } else {
                        // Nothing moved (or the gesture was cancelled) — repaint to drop the
                        // neighbours' translations without recording an empty undo step.
                        rebuild.run();
                    }
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    /**
     * How many rows the finger has travelled, measured against the cards ACTUALLY THERE.
     *
     * <p>Not {@code dy / draggedHeight}: a collapsed card is a fraction of an expanded one, so
     * one height for every slot puts the drop in the wrong place the moment the stack is mixed
     * — and a stack of a dozen effects is exactly when people collapse things. Walking the real
     * sibling positions costs a loop over at most a dozen views and is always right.</p>
     */
    private static int rowsCrossed(@Nullable ViewGroup parent, @NonNull View dragged, float dy) {
        if (parent == null) return 0;
        int idx = parent.indexOfChild(dragged);
        float travelled = 0f;
        int step = dy > 0 ? 1 : -1;
        int crossed = 0;
        for (int i = idx + step; i >= 0 && i < parent.getChildCount(); i += step) {
            View sib = parent.getChildAt(i);
            if (sib.getVisibility() == View.GONE) continue;
            // Half of the neighbour is the commit point — the same "past its midpoint" rule
            // every list reorder uses, so the gap opens when the card visually belongs there.
            travelled += sib.getHeight();
            if (Math.abs(dy) < travelled - sib.getHeight() / 2f) break;
            crossed += step;
        }
        return crossed;
    }

    /** Animate the cards the dragged one has passed, opening a gap where it will land. */
    /**
     * Slide neighbours out of the way while dragging a card — C6: each neighbour displaces by
     * ITS OWN height, not the dragged card's. With mixed collapsed/expanded cards the old single
     * {@code rowH} opened a gap the wrong size (the dragged card's height), which snapped back
     * unevenly when the finger passed a collapsed row.
     */
    private static void slideNeighbours(@Nullable ViewGroup parent, @NonNull View dragged,
                                        int screenPos, int shift, int rowH) {
        if (parent == null) return;
        int draggedIdx = parent.indexOfChild(dragged);
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == dragged) continue;
            int rel = i - draggedIdx;          // negative = above, positive = below
            // C6: each neighbour moves by its OWN height (+ the inter-card margin), so a
            // collapsed 14dp row beside an expanded 34dp one opens exactly the room it needs.
            float own = child.getHeight();
            if (own <= 0f) own = rowH;        // pre-layout fallback
            float target = 0f;
            if (shift > 0 && rel > 0 && rel <= shift) target = -own;
            else if (shift < 0 && rel < 0 && rel >= shift) target = own;
            if (child.getTranslationY() != target) {
                child.animate().translationY(target).setDuration(180)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
            }
        }
    }

    /**
     * The nearest scrolling ancestor, or null. Walked rather than named: this tab is hosted by
     * {@code ObjectDrawer} today and by whatever hosts it next, and a hardcoded id would
     * silently disable auto-scroll the day it moves.
     */
    @Nullable
    private static android.widget.ScrollView scrollParent(@NonNull View v) {
        android.view.ViewParent p = v.getParent();
        while (p != null) {
            if (p instanceof android.widget.ScrollView) return (android.widget.ScrollView) p;
            p = p.getParent();
        }
        return null;
    }

    /** Two stacked bars — the universal "grab me" mark. */
    @NonNull
    private static View grabHandle(@NonNull Context ctx, float d) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        for (int i = 0; i < 2; i++) {
            View bar = new View(ctx);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    Math.round(16 * d), Math.round(2 * d));
            blp.topMargin = i == 0 ? 0 : Math.round(3 * d);
            bar.setLayoutParams(blp);
            bar.setBackgroundColor(0x66FFFFFF);
            box.addView(bar);
        }
        int size = Math.round(44 * d);
        box.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return box;
    }

    /** The pill background at the one radius this app uses for chips. */
    @NonNull
    private static android.graphics.drawable.GradientDrawable pill(int color, float d) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(14f * d);
        return g;
    }

    /** A card is a soft panel, not a hard rectangle — a smaller radius than the chips on it. */
    @NonNull
    private static android.graphics.drawable.GradientDrawable cardBg(float d) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(CARD_BG);
        g.setCornerRadius(10f * d);
        return g;
    }

    /**
     * A BORDERLESS icon action — no box, and a real 48dp target.
     *
     * <p>For controls that do a thing rather than hold a state: the disclosure caret, delete, the
     * keyframe diamond. A disclosure triangle does not need a container, and boxing it made the
     * densest row in the panel out of the least important controls.</p>
     */
    @NonNull
    private static TextView iconBtn(@NonNull Context ctx, @NonNull String glyph, float d) {
        TextView t = new TextView(ctx);
        t.setText(glyph);
        t.setTextColor(TXT);
        t.setTextSize(13f);
        t.setGravity(Gravity.CENTER);
        android.util.TypedValue tv = new android.util.TypedValue();
        ctx.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true);
        t.setBackgroundResource(tv.resourceId);
        int size = Math.round(44 * d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        t.setLayoutParams(lp);
        return t;
    }

    // ── Keyframes ───────────────────────────────────────────────────────────

    /**
     * The ◆ that keys ONE parameter at the playhead.
     *
     * <p>Per-parameter rather than all-at-once, unlike the mask tab's "key at playhead". A mask
     * keys its six numbers together because half-arming a SHAPE reads as the shape tearing; an
     * effect's parameters are independent, and keying a blur's radius should not also pin its
     * angle to whatever it happened to be.</p>
     *
     * <p>Filled means this track already has keys. Tapping adds one at the playhead; long-press
     * removes every key on the track, because a stray keyframe is otherwise very hard to find
     * once the diamond is the only evidence it exists.</p>
     */
    @NonNull
    // ── Keyframes ───────────────────────────────────────────────────────────

    /** Tag key for the panel root's live refresh list — diamonds/sliders re-read the model. */
    private static final int REFRESH_TAG = 0x2A5F_0001; // arbitrary unique tag key

    /** One registered live-refresh callback (diamond fill state, keyframed slider value). */
    private interface RefreshEntry { void onPlayhead(long playheadMs); }

    /** The panel's live-refresh list, attached to the root returned by {@link #build}. */
    private static final class RefreshState {
        final java.util.List<RefreshEntry> entries = new java.util.ArrayList<>();
    }

    /**
     * Re-read every registered keyframe control for the new playhead — called by the editor on
     * every playhead tick so a diamond goes solid/hollow the moment the playhead lands/leaves a
     * key, and a slider shows the interpolated keyframed value. No-op when {@code root} is not
     * (or no longer contains) an FxPanel root.
     */
    public static void refreshRows(@Nullable View root, long playheadMs) {
        if (root == null) return;
        Object tag = root.getTag(REFRESH_TAG);
        if (tag instanceof RefreshState) {
            RefreshState rs = (RefreshState) tag;
            for (RefreshEntry e : rs.entries) e.onPlayhead(playheadMs);
            return;
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) refreshRows(vg.getChildAt(i), playheadMs);
        }
    }

    /** True when any component track of this param has a key within ~2 frames of {@code at}. */
    private static boolean onKeyAtPlayhead(@NonNull FxStack stack, @NonNull FxInstance fx,
                                           @NonNull FxParam param, long at) {
        if (stack.keys == null) return false;
        for (int i = 0; i < param.kind.components; i++) {
            com.fadcam.ui.faditor.keyframe.KeyframeTrack tr =
                    stack.keys.get(fx.track(param, i));
            if (tr != null) {
                for (com.fadcam.ui.faditor.keyframe.Keyframe k : tr.keyframes) {
                    if (Math.abs(k.timeMs - at) <= 66) return true;
                }
            }
        }
        return false;
    }

    /** The exact key time on {@code tr} within ~2 frames of {@code at}, or -1. */
    private static long keyTimeAt(@Nullable com.fadcam.ui.faditor.keyframe.KeyframeTrack tr,
                                  long at) {
        if (tr == null) return -1L;
        for (com.fadcam.ui.faditor.keyframe.Keyframe k : tr.keyframes) {
            if (Math.abs(k.timeMs - at) <= 66) return k.timeMs;
        }
        return -1L;
    }

    /** Seek the playhead to the nearest key strictly before/after it, across all component tracks. */
    private static void jumpKey(@NonNull FxStack stack, @NonNull FxInstance fx,
                                @NonNull FxParam param, @NonNull Host host, boolean forward) {
        if (stack.keys == null || host == null) return;
        long at = host.playheadMs();
        Long best = null;
        for (int i = 0; i < param.kind.components; i++) {
            com.fadcam.ui.faditor.keyframe.KeyframeTrack tr = stack.keys.get(fx.track(param, i));
            if (tr == null) continue;
            for (com.fadcam.ui.faditor.keyframe.Keyframe k : tr.keyframes) {
                if (forward ? k.timeMs > at + 66 : k.timeMs < at - 66) {
                    if (best == null || (forward ? k.timeMs < best : k.timeMs > best)) {
                        best = k.timeMs;
                    }
                }
            }
        }
        if (best != null) host.seekTo(best);
    }

    /** A {@code ‹} / {@code ›} chevron — jump to the previous/next key of this property. */
    @NonNull
    private static TextView keyChevron(@NonNull Context ctx, @NonNull String glyph, float d,
                                       @NonNull Runnable onTap) {
        TextView v = new TextView(ctx);
        v.setText(glyph);
        v.setTextColor(0xFF8A8A94);
        v.setTextSize(15f);
        v.setGravity(Gravity.CENTER);
        v.setPadding(Math.round(6 * d), 0, Math.round(6 * d), 0);
        v.setOnClickListener(ignored -> onTap.run());
        return v;
    }

    /**
     * The {@code ‹ ◆ ›} keyframe control for ONE parameter at the playhead.
     *
     * <p>Filled only when the playhead sits ON a key of this property — the moment it moves off,
     * the diamond goes hollow (JoyRaptor, 2026-08-08). Tap toggles: on-key removes that key, off-key
     * drops one. Long-press clears every key on the track. The flanking chevrons and a horizontal
     * swipe on the diamond jump the playhead to the previous/next key.
     *
     * <p>Every mutation goes through {@link #structural}, so one undo step per gesture. Registered
     * in the panel's {@link RefreshState} so the editor can repaint the fill on playhead ticks.
     */
    @NonNull
    private static View diamond(@NonNull Context ctx, @NonNull FxStack stack,
                                @NonNull FxInstance fx, @NonNull FxParam param,
                                @NonNull Host host, @NonNull Runnable rebuild, float d,
                                @NonNull RefreshState rs) {
        final TextView[] dg = {null};
        final Runnable repaint = () -> {
            boolean on = onKeyAtPlayhead(stack, fx, param, host.playheadMs());
            dg[0].setText(on ? "◆" : "◇");
            dg[0].setTextColor(on ? 0xFFA78BFA : 0xFF8A8A94);
        };

        LinearLayout cluster = new LinearLayout(ctx);
        cluster.setOrientation(LinearLayout.HORIZONTAL);
        cluster.setGravity(Gravity.CENTER_VERTICAL);

        cluster.addView(keyChevron(ctx, "‹", d,
                () -> jumpKey(stack, fx, param, host, false)));

        TextView t = chip(ctx, "◇", d);
        dg[0] = t;
        repaint.run();
        // BOTH GO THROUGH structural. The diamond is a mutation path with one undo per gesture,
        // and the long-press is the most destructive control on the card: it removes EVERY key
        // on the track. A hand-animated curve wiped by a stray long-press was simply gone, and
        // scheduleAutoSave had already written it out.
        //
        // The tap / long-press / swipe all live INSIDE one OnTouchListener, NOT the view's
        // click/long-click listeners: consuming ACTION_DOWN here (required to detect a swipe)
        // stops View.onTouchEvent from ever arming the press state, so performClick() and the
        // long-click detector would never fire (adversarial review, C1).
        final Runnable toggleKey = () -> {
            long at = host.playheadMs();
            structural(stack, host, rebuild, "Key " + param.label, () -> {
                if (stack.keys == null) {
                    stack.keys = new com.fadcam.ui.faditor.keyframe.KeyframeSet();
                }
                boolean on = onKeyAtPlayhead(stack, fx, param, at);
                if (on) {
                    // ON a key → remove it (the × the shared control draws).
                    for (int i = 0; i < param.kind.components; i++) {
                        com.fadcam.ui.faditor.keyframe.KeyframeTrack tr =
                                stack.keys.get(fx.track(param, i));
                        long hit = keyTimeAt(tr, at);
                        if (hit >= 0) tr.removeAt(hit);
                    }
                } else {
                    float[] vals = fx.get(param);
                    for (int i = 0; i < param.kind.components; i++) {
                        stack.keys.getOrCreate(fx.track(param, i)).put(at, vals[i],
                                com.fadcam.ui.faditor.keyframe.Easing.EASE_IN_OUT);
                    }
                }
            });
            repaint.run();
        };
        final Runnable clearKeys = () -> {
            if (stack.keys == null) return;
            structural(stack, host, rebuild, "Clear " + param.label + " keys", () -> {
                for (int i = 0; i < param.kind.components; i++) {
                    stack.keys.removeProperty(fx.track(param, i));
                }
            });
            repaint.run();
            android.widget.Toast.makeText(ctx, param.label + " keys cleared",
                    android.widget.Toast.LENGTH_SHORT).show();
        };
        // Swipe on the diamond = previous/next key; tap = add/remove a key; long-press = clear.
        t.setOnTouchListener(new android.view.View.OnTouchListener() {
            float downX, downY;
            boolean moved;
            boolean longPressed;
            Runnable pendingLp;
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        moved = false;
                        longPressed = false;
                        pendingLp = () -> {
                            longPressed = true;
                            v.performHapticFeedback(
                                    android.view.HapticFeedbackConstants.LONG_PRESS);
                            clearKeys.run();
                        };
                        v.postDelayed(pendingLp,
                                android.view.ViewConfiguration.getLongPressTimeout());
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float slop = android.view.ViewConfiguration.get(ctx).getScaledTouchSlop();
                        if (!moved && (Math.abs(e.getRawX() - downX) > slop
                                || Math.abs(e.getRawY() - downY) > slop)) {
                            moved = true;
                            if (pendingLp != null) v.removeCallbacks(pendingLp);
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP: {
                        if (pendingLp != null) v.removeCallbacks(pendingLp);
                        if (longPressed) return true;
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        float slop = android.view.ViewConfiguration.get(ctx).getScaledTouchSlop();
                        if (moved && Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(dy)) {
                            jumpKey(stack, fx, param, host, dx > 0);
                        } else if (!moved) {
                            toggleKey.run();
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (pendingLp != null) v.removeCallbacks(pendingLp);
                        return true;
                    default:
                        return false;
                }
            }
        });
        cluster.addView(t);
        cluster.addView(keyChevron(ctx, "›", d,
                () -> jumpKey(stack, fx, param, host, true)));

        rs.entries.add(playheadMs -> {
            boolean on = onKeyAtPlayhead(stack, fx, param, playheadMs);
            dg[0].setText(on ? "◆" : "◇");
            dg[0].setTextColor(on ? 0xFFA78BFA : 0xFF8A8A94);
        });
        return cluster;
    }

    // ── Presets ─────────────────────────────────────────────────────────────

    /**
     * Save the stack as a named look, and load one back.
     *
     * <p>Presets live OUTSIDE the project schema (see {@link FxPresetStore}), so this row can
     * never make a project unopenable by an older build — which is what lets it exist at all
     * without a migration story.</p>
     */
    @NonNull
    private static View presetRow(@NonNull Context ctx, @NonNull FxStack stack,
                                  @NonNull Host host, @NonNull Runnable rebuild, float d,
                                  @NonNull FxPreviewTier.Subject subject) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, Math.round(10 * d), 0, 0);

        List<String> names = FxPresetStore.listNames(ctx);
        if (stack.isEmpty() && names.isEmpty()) return wrap;   // nothing to save, none to load

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);

        if (!stack.isEmpty()) {
            TextView save = chip(ctx, "Save look", d);
            save.setOnClickListener(v -> {
                final android.widget.EditText input = new android.widget.EditText(ctx);
                input.setHint("Name this look");
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setTitle("Save look")
                        .setView(input)
                        .setPositiveButton("Save", (dlg, w) -> {
                            String name = input.getText().toString().trim();
                            if (name.isEmpty()) return;
                            FxPresetStore.save(ctx, name, stack);
                            rebuild.run();
                            android.widget.Toast.makeText(ctx, "Saved '" + name + "'",
                                    android.widget.Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
            row.addView(save);
        }
        wrap.addView(row);

        if (names.isEmpty()) return wrap;
        TextView head = new TextView(ctx);
        head.setText("Saved looks");
        head.setTextColor(TXT_DIM);
        head.setTextSize(10.5f);
        head.setPadding(0, Math.round(6 * d), 0, Math.round(2 * d));
        wrap.addView(head);

        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.HORIZONTAL);
        for (String name : names) {
            TextView c = chip(ctx, name, d);
            c.setOnClickListener(v -> {
                // REPLACES the stack, so it is confirmed: loading a look over work in progress
                // is the one action here that destroys something the user cannot see a copy of.
                if (stack.isEmpty()) {
                    applyPreset(ctx, stack, name, host, rebuild, subject);
                    return;
                }
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setTitle("Load '" + name + "'?")
                        .setMessage("This replaces the " + stack.size()
                                + " effect(s) on this layer.")
                        .setPositiveButton("Load",
                                (dlg, w) -> applyPreset(ctx, stack, name, host, rebuild, subject))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
            c.setOnLongClickListener(v -> {
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setTitle("Delete '" + name + "'?")
                        .setPositiveButton("Delete", (dlg, w) -> {
                            FxPresetStore.delete(ctx, name);
                            rebuild.run();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            });
            list.addView(c);
        }
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(list);
        wrap.addView(scroll);
        return wrap;
    }

    private static void applyPreset(@NonNull Context ctx, @NonNull FxStack stack,
                                    @NonNull String name, @NonNull Host host,
                                    @NonNull Runnable rebuild,
                                    @NonNull FxPreviewTier.Subject subject) {
        // Snapshot BEFORE the load, because load() replaces the whole chain in place — this is
        // the single most destructive action in the panel and it had no undo at all.
        FxStack before = stack.copy();
        if (!FxPresetStore.load(ctx, name, stack)) {
            android.widget.Toast.makeText(ctx, "Could not load '" + name + "'",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // THE PICKER'S GATE APPLIES HERE TOO. A sampler card cannot run on an object — its body
        // reads uTexSampler, which in the PiP composite is the MASTER FRAME SO FAR while the uv
        // is PiP-local, so a blur loaded from a look would fill the PiP box with a smeared crop
        // of the background. It used to fail safe only because the whole shader failed to
        // compile; now that it compiles, the gate has to be enforced rather than implied.
        int dropped = 0;
        for (int i = stack.cards().size() - 1; i >= 0; i--) {
            FxEffectDef def = stack.cards().get(i).def();
            if (def != null && !FxPreviewTier.canExportOn(def, subject)) {
                stack.remove(i);
                dropped++;
            }
        }
        if (dropped > 0) {
            String phrase = String.format(ctx.getString(com.fadcam.R.string.faditor_fx_layer_only_left_out),
                    dropped + (dropped == 1 ? " effect" : " effects"));
            android.widget.Toast.makeText(ctx, phrase, android.widget.Toast.LENGTH_LONG).show();
        }
        FxStack after = stack.copy();
        rebuild.run();
        host.onFxChanged();
        host.recordUndo("Apply preset '" + name + "'",
                () -> { stack.copyFrom(after); rebuild.run(); host.onFxChanged(); },
                () -> { stack.copyFrom(before); rebuild.run(); host.onFxChanged(); });
    }

    // ── The picker ──────────────────────────────────────────────────────────

    @NonNull
    private static View addRow(@NonNull Context ctx, @NonNull FxStack stack,
                               @NonNull Host host, @NonNull Runnable rebuild, float d,
                               @NonNull FxPreviewTier.Subject subject) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, Math.round(6 * d), 0, 0);

        TextView add = chip(ctx, "＋ Add effect", d);
        add.setBackground(pill(CHIP_ON, d));
        final LinearLayout picker = new LinearLayout(ctx);
        picker.setOrientation(LinearLayout.VERTICAL);
        picker.setVisibility(View.GONE);

        add.setOnClickListener(v -> picker.setVisibility(
                picker.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
        wrap.addView(add);

        for (FxEffectDef.Family family : FxEffectDef.Family.values()) {
            List<FxEffectDef> defs = FxRegistry.byFamily(family);
            if (defs.isEmpty()) continue;
            TextView head = new TextView(ctx);
            head.setText(pretty(family.name()));
            head.setTextColor(TXT_DIM);
            head.setTextSize(10.5f);
            head.setPadding(0, Math.round(6 * d), 0, Math.round(2 * d));
            picker.addView(head);

            LinearLayout rowA = new LinearLayout(ctx);
            rowA.setOrientation(LinearLayout.HORIZONTAL);
            int perRow = 0;
            LinearLayout current = rowA;
            picker.addView(rowA);
            for (FxEffectDef def : defs) {
                if (perRow == 3) {
                    LinearLayout next = new LinearLayout(ctx);
                    next.setOrientation(LinearLayout.HORIZONTAL);
                    picker.addView(next);
                    current = next;
                    perRow = 0;
                }
                // WARN, PERMIT, WARN AGAIN was the old flow: the picker badged a blur
                // "(layer only)", added it anyway on tap, and the card then nagged "needs an
                // adjustment layer" forever while rendering nothing. Three chances to say no
                // and it said yes. An effect that cannot render on this subject is not offered
                // at all now, and the tap explains itself and OFFERS THE FIX rather than
                // leaving the user to infer what an adjustment layer is.
                boolean canRender = FxPreviewTier.canExportOn(def, subject);
                TextView c = chip(ctx, def.displayName, d);
                if (!canRender) {
                    c.setAlpha(0.4f);
                    c.setOnClickListener(v -> new com.google.android.material.dialog
                            .MaterialAlertDialogBuilder(ctx)
                            .setTitle(def.displayName + " needs its own pass")
                            .setMessage(def.displayName + " reads neighbouring pixels, so it "
                                    + "needs a finished image to work from. An object is drawn "
                                    + "in a single pass, so there isn't one yet.\n\nPut it on an "
                                    + "adjustment layer above this object and it will apply to "
                                    + "this and everything under it.")
                            .setPositiveButton("Got it", null)
                            .show());
                } else {
                    c.setOnClickListener(v -> {
                        picker.setVisibility(View.GONE);
                        structural(stack, host, rebuild, "Add " + def.displayName,
                                () -> stack.add(def.id));
                    });
                }
                current.addView(c);
                perRow++;
            }
        }
        wrap.addView(picker);
        return wrap;
    }
}
