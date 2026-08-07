package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
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
import com.fadcam.ui.faditor.model.BlendModes;

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
 * <p><b>Reorder by DRAG, with the arrows kept.</b> Long-press a card header and it lifts;
 * drag past the card above or below and they swap; release to drop. The arrows stay because
 * they are the precise instrument — one press is always exactly one place, which a drag can
 * never promise on a list that reflows under the finger.</p>
 *
 * <p>The drag deliberately commits on every crossing rather than computing a drop index at the
 * end: the model IS the preview, so what the user sees mid-drag is already the order they will
 * get, and there is no separate commit step to disagree with it. Slots are stable, so keyframes
 * follow the card whichever mechanism moves it.</p>
 */
public final class FxPanel {

    private FxPanel() {}

    private static final int TXT = 0xFFE8E8E8;
    private static final int TXT_DIM = 0xFFA0A0A0;
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

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            root.removeAllViews();
            root.addView(headerRow(ctx, stack, host, rebuild[0], d));
            List<FxInstance> cards = stack.cards();
            if (cards.isEmpty()) {
                root.addView(emptyNote(ctx, d));
            }
            // TOP-DOWN on screen, bottom-up in the model. The last card in the list is applied
            // last, so it belongs at the TOP of a vertical list — the same way every layer
            // stack in every editor reads. Iterating the list forwards here would show the
            // chain upside down.
            for (int i = cards.size() - 1; i >= 0; i--) {
                root.addView(card(ctx, stack, cards.get(i), i, host, rebuild[0], d, subject));
            }
            root.addView(addRow(ctx, stack, host, rebuild[0], d, subject));
            root.addView(presetRow(ctx, stack, host, rebuild[0], d));
        };
        rebuild[0].run();
        return root;
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
        String note = FxPreviewTier.headerNote(FxPreviewTier.current());
        if (note.isEmpty()) return row;
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(row);
        TextView warn = new TextView(ctx);
        warn.setText(note);
        warn.setTextColor(0xFFFFC107);
        warn.setTextSize(11f);
        warn.setPadding(0, 0, 0, Math.round(6 * d));
        col.addView(warn);
        return col;
    }

    @NonNull
    private static View emptyNote(@NonNull Context ctx, float d) {
        TextView t = new TextView(ctx);
        t.setText("This layer changes nothing yet. Add an effect and everything beneath the "
                + "layer takes it.");
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
                             @NonNull FxPreviewTier.Subject subject) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(CARD_BG);
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

        TextView caret = chip(ctx, fx.collapsed ? "▸" : "▾", d);
        caret.setOnClickListener(v -> { fx.collapsed = !fx.collapsed; rebuild.run(); });
        head.addView(caret);

        // ── Long-press the header to pick the card up, then drag to reorder ──
        // Row height is measured from the card itself rather than assumed, so a collapsed card
        // and an expanded one both hand back a correct threshold.
        final float[] dragStartY = {0f};
        final boolean[] lifted = {false};
        head.setOnLongClickListener(v -> {
            lifted[0] = true;
            card.setAlpha(0.75f);
            card.setBackgroundColor(CHIP_ON);
            v.performHapticFeedback(
                    android.view.HapticFeedbackConstants.LONG_PRESS);
            return true;
        });
        head.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    dragStartY[0] = ev.getRawY();
                    return false;   // let the long-press detector see it
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (!lifted[0]) return false;
                    float dy = ev.getRawY() - dragStartY[0];
                    int h = Math.max(1, card.getHeight());
                    // Screen order is top-down while the model is bottom-up, so dragging DOWN
                    // moves a card EARLIER in the stack. Getting this backwards would be the
                    // most confusing possible bug here.
                    if (dy > h * 0.6f && index > 0) {
                        stack.move(index, index - 1);
                        rebuild.run();
                        host.onFxChanged();
                    } else if (dy < -h * 0.6f && index < stack.size() - 1) {
                        stack.move(index, index + 1);
                        rebuild.run();
                        host.onFxChanged();
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (!lifted[0]) return false;
                    lifted[0] = false;
                    card.setAlpha(1f);
                    card.setBackgroundColor(CARD_BG);
                    return true;
                default:
                    return false;
            }
        });

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
        String note = FxPreviewTier.cardNote(def, FxPreviewTier.current(), subject);
        if (!note.isEmpty()) {
            TextView warn = new TextView(ctx);
            warn.setText(note);
            warn.setTextColor(0xFFFFC107);
            warn.setTextSize(9.5f);
            warn.setPadding(0, 0, Math.round(6 * d), 0);
            head.addView(warn);
        }

        // Bypass, which is also the cheap escape: a disabled card contributes NO pass at all.
        TextView eye = chip(ctx, fx.enabled ? "◉" : "◌", d);
        eye.setOnClickListener(v -> {
            fx.enabled = !fx.enabled;
            rebuild.run();
            host.onFxChanged();
        });
        head.addView(eye);

        TextView up = chip(ctx, "▲", d);
        up.setAlpha(index < stack.size() - 1 ? 1f : 0.3f);
        up.setOnClickListener(v -> {
            if (index >= stack.size() - 1) return;
            stack.move(index, index + 1);
            rebuild.run();
            host.onFxChanged();
        });
        head.addView(up);

        TextView down = chip(ctx, "▼", d);
        down.setAlpha(index > 0 ? 1f : 0.3f);
        down.setOnClickListener(v -> {
            if (index <= 0) return;
            stack.move(index, index - 1);
            rebuild.run();
            host.onFxChanged();
        });
        head.addView(down);

        TextView del = chip(ctx, "✕", d);
        del.setOnClickListener(v -> {
            // remove() also deletes this slot's keyframe tracks and retires the slot, so
            // nothing added later can inherit them.
            stack.remove(index);
            rebuild.run();
            host.onFxChanged();
        });
        head.addView(del);
        card.addView(head);

        if (fx.collapsed) return card;

        // ── parameters ──
        for (FxParam param : def.params) {
            LinearLayout pr = new LinearLayout(ctx);
            pr.setOrientation(LinearLayout.HORIZONTAL);
            pr.setGravity(Gravity.CENTER_VERTICAL);
            View row = paramRow(ctx, fx, param, host, d);
            pr.addView(row, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (param.keyable) pr.addView(diamond(ctx, stack, fx, param, host, rebuild, d));
            card.addView(pr);
        }

        // ── fold controls: how this card's result lands on what is below it ──
        card.addView(sliderRow(ctx, "Opacity", 0, 100, Math.round(fx.opacity * 100),
                v -> { fx.opacity = v / 100f; host.onFxChanged(); }, d));

        LinearLayout blendRow = new LinearLayout(ctx);
        blendRow.setOrientation(LinearLayout.HORIZONTAL);
        blendRow.setPadding(0, Math.round(4 * d), 0, 0);
        for (String mode : BlendModes.ALL) {
            TextView c = chip(ctx, pretty(mode), d);
            c.setBackgroundColor(mode.equals(fx.blendMode) ? CHIP_ON : CHIP_BG);
            c.setOnClickListener(v -> {
                fx.blendMode = mode;
                rebuild.run();
                host.onFxChanged();
            });
            blendRow.addView(c);
        }
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
    private static View paramRow(@NonNull Context ctx, @NonNull FxInstance fx,
                                 @NonNull FxParam param, @NonNull Host host, float d) {
        switch (param.kind) {
            case BOOL: {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView label = label(ctx, param.label, d);
                row.addView(label);
                boolean on = fx.getScalar(param) >= 0.5f;
                TextView c = chip(ctx, on ? "On" : "Off", d);
                c.setBackgroundColor(on ? CHIP_ON : CHIP_BG);
                c.setOnClickListener(v -> {
                    fx.set(param, on ? 0f : 1f);
                    c.setText(on ? "Off" : "On");
                    c.setBackgroundColor(on ? CHIP_BG : CHIP_ON);
                    host.onFxChanged();
                });
                row.addView(c);
                return row;
            }
            case ENUM: {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(label(ctx, param.label, d));
                String[] labels = param.enumLabels();
                for (int i = 0; i < labels.length; i++) {
                    final int idx = i;
                    TextView c = chip(ctx, labels[i], d);
                    c.setBackgroundColor(Math.round(fx.getScalar(param)) == i ? CHIP_ON : CHIP_BG);
                    c.setOnClickListener(v -> {
                        fx.set(param, idx);
                        host.onFxChanged();
                        // Repaint the row's chips without rebuilding the whole panel.
                        for (int k = 0; k < row.getChildCount(); k++) {
                            View child = row.getChildAt(k);
                            if (child instanceof TextView && child != v && k > 0) {
                                child.setBackgroundColor(CHIP_BG);
                            }
                        }
                        v.setBackgroundColor(CHIP_ON);
                    });
                    row.addView(c);
                }
                return row;
            }
            case COLOR:
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
                            }, d));
                }
                return col;
            }
            case FLOAT:
            default: {
                // The slider works in whole units of the descriptor's own range, so a 0..1
                // parameter reads as a percentage and a 0..64 radius reads as pixels — rather
                // than every control pretending to be a percentage of something.
                int min = Math.round(param.min);
                int max = Math.round(param.max);
                if (max - min < 4) { min = Math.round(param.min * 100); max = Math.round(param.max * 100); }
                final float scale = (max - min) > 0 && param.max <= 1.001f && param.min >= -1.001f
                        ? 100f : 1f;
                final int fmin = min, fmax = max;
                return sliderRow(ctx, param.label, fmin, fmax,
                        Math.round(fx.getScalar(param) * scale),
                        v -> { fx.set(param, v / scale); host.onFxChanged(); }, d);
            }
        }
    }

    // ── Small builders ──────────────────────────────────────────────────────

    @NonNull
    private static View sliderRow(@NonNull Context ctx, @NonNull String labelText,
                                  int min, int max, int initial,
                                  @NonNull java.util.function.Consumer<Integer> onChange,
                                  float d) {
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

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                value.setText(String.valueOf(p + min));
                onChange.accept(p + min);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
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

    @NonNull
    private static TextView chip(@NonNull Context ctx, @NonNull String text, float d) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(TXT);
        t.setTextSize(12f);
        int px = Math.round(9 * d), py = Math.round(6 * d);
        t.setPadding(px, py, px, py);
        t.setBackgroundColor(CHIP_BG);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Math.round(5 * d);
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
    private static View diamond(@NonNull Context ctx, @NonNull FxStack stack,
                                @NonNull FxInstance fx, @NonNull FxParam param,
                                @NonNull Host host, @NonNull Runnable rebuild, float d) {
        boolean keyed = false;
        if (stack.keys != null) {
            for (int i = 0; i < param.kind.components && !keyed; i++) {
                keyed = stack.keys.hasProperty(fx.track(param, i));
            }
        }
        TextView t = chip(ctx, keyed ? "◆" : "◇", d);
        t.setAlpha(keyed ? 1f : 0.5f);
        t.setOnClickListener(v -> {
            long at = host.playheadMs();
            if (stack.keys == null) {
                stack.keys = new com.fadcam.ui.faditor.keyframe.KeyframeSet();
            }
            float[] vals = fx.get(param);
            for (int i = 0; i < param.kind.components; i++) {
                stack.keys.getOrCreate(fx.track(param, i)).put(at, vals[i],
                        com.fadcam.ui.faditor.keyframe.Easing.EASE_IN_OUT);
            }
            rebuild.run();
            host.onFxChanged();
            android.widget.Toast.makeText(ctx,
                    param.label + " keyed at " + (at / 1000f) + "s",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        t.setOnLongClickListener(v -> {
            if (stack.keys == null) return true;
            for (int i = 0; i < param.kind.components; i++) {
                stack.keys.removeProperty(fx.track(param, i));
            }
            rebuild.run();
            host.onFxChanged();
            android.widget.Toast.makeText(ctx, param.label + " keys cleared",
                    android.widget.Toast.LENGTH_SHORT).show();
            return true;
        });
        return t;
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
                                  @NonNull Host host, @NonNull Runnable rebuild, float d) {
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
                new android.app.AlertDialog.Builder(ctx)
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
                    applyPreset(ctx, stack, name, host, rebuild);
                    return;
                }
                new android.app.AlertDialog.Builder(ctx)
                        .setTitle("Load '" + name + "'?")
                        .setMessage("This replaces the " + stack.size()
                                + " effect(s) on this layer.")
                        .setPositiveButton("Load",
                                (dlg, w) -> applyPreset(ctx, stack, name, host, rebuild))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
            c.setOnLongClickListener(v -> {
                new android.app.AlertDialog.Builder(ctx)
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
                                    @NonNull Runnable rebuild) {
        if (!FxPresetStore.load(ctx, name, stack)) {
            android.widget.Toast.makeText(ctx, "Could not load '" + name + "'",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        rebuild.run();
        host.onFxChanged();
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
        add.setBackgroundColor(CHIP_ON);
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
                // The badge answers "will I SEE this" BEFORE the card is added, which is the
                // only moment the answer can still change the decision.
                String badge = FxPreviewTier.canExportOn(def, subject)
                        ? FxPreviewTier.badge(def, FxPreviewTier.current())
                        : "layer only";
                TextView c = chip(ctx,
                        badge.isEmpty() ? def.displayName : def.displayName + " (" + badge + ")",
                        d);
                c.setOnClickListener(v -> {
                    stack.add(def.id);
                    picker.setVisibility(View.GONE);
                    rebuild.run();
                    host.onFxChanged();
                });
                current.addView(c);
                perRow++;
            }
        }
        wrap.addView(picker);
        return wrap;
    }
}
