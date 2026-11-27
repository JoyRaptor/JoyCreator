package com.fadcam.ui.faditor.motion.card;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.motion.ShutterStripModel;
import com.fadcam.ui.faditor.tools.FineSeekBar;
import com.fadcam.ui.faditor.tools.ObjectDrawer;
import com.fadcam.ui.type.Type;

import java.util.HashSet;
import java.util.Set;

/**
 * The Motion Blur card (STU-7 spec §K; owner picks A1 B3 C1 D1 E1 F1, 2026-11-27).
 *
 * <p>Shaped like every other effect card (FxPanel's: control-fill panel, 10dp corners, caret,
 * swatch, name, ✕) and coloured as Studio's own effect: chosen chips and the Mix fill in flat
 * Studio aqua, the shutter bar and the action pill in the aqua-to-lime gradient ("gradient
 * means action, flat means identity"). The drawer around it keeps its own room colour.</p>
 *
 * <p>Packed tight on purpose: drawers cover the picture. Open, it is the header, the preset
 * chips, the shutter row and the Mix row, plus one slim footer line.</p>
 */
public final class MotionBlurCard {

    /** What the card edits. The editor implements it for each kind of object. */
    public interface Host {
        @NonNull MotionShutter.Controls controls();
        /** Show {@code draft} in the preview while a control is moving. No undo step. */
        void preview(@NonNull MotionShutter.Controls draft);
        /** Keep {@code after} as one undo step back to {@code before}. */
        void commit(@NonNull MotionShutter.Controls before, @NonNull MotionShutter.Controls after);
        /** Take the card off (blur off for this object). Recorded as an undo step. */
        void remove();
        /** Render the paused frame at export quality. */
        void showExactFrame();
        /** While held, show the frame without the blur. */
        void compare(boolean sharp);
        /**
         * The one plain line saying why this object stays sharp in the preview and what to do,
         * or 0 when the preview shows its blur.
         */
        @StringRes int cannotBlurReason();
    }

    /** Cards folded shut this session, by object id: a rebuild keeps them shut. */
    private static final Set<String> COLLAPSED = new HashSet<>();

    private MotionBlurCard() {}

    @NonNull
    public static View build(@NonNull Context ctx, @NonNull String objectId, @NonNull Host host) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ObjectDrawer.Kit.CTL);
        bg.setCornerRadius(10 * d);
        card.setBackground(bg);
        int p = Math.round(8 * d);
        card.setPadding(p, Math.round(4 * d), p, Math.round(4 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(6 * d);
        card.setLayoutParams(lp);
        fill(ctx, card, objectId, host, d);
        return card;
    }

    private static void fill(@NonNull Context ctx, @NonNull LinearLayout card,
                             @NonNull String objectId, @NonNull Host host, float d) {
        card.removeAllViews();
        boolean collapsed = COLLAPSED.contains(objectId);
        Runnable rebuild = () -> fill(ctx, card, objectId, host, d);

        // ── header: caret · swatch (hold = sharp) · name · ✕ ──
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView caret = iconBtn(ctx, collapsed ? "▸" : "▾", d);
        ObjectDrawer.Kit.describe(caret, ctx.getString(collapsed
                ? R.string.faditor_lc_fx_expand : R.string.faditor_lc_fx_collapse));
        caret.setOnClickListener(v -> {
            if (!COLLAPSED.remove(objectId)) COLLAPSED.add(objectId);
            rebuild.run();
        });
        head.addView(caret);

        View swatch = swatch(ctx, d);
        ObjectDrawer.Kit.describe(swatch, ctx.getString(R.string.studio_motion_hold_compare));
        holdToCompare(swatch, host);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                Math.round(28 * d), Math.round(28 * d));
        swLp.setMarginStart(Math.round(2 * d));
        head.addView(swatch, swLp);

        TextView name = new TextView(ctx);
        name.setText(R.string.studio_motion_blur);
        name.setTextColor(Studio.DRAWER_INK);
        name.setTextSize(13f);
        name.setPadding(Math.round(6 * d), 0, 0, 0);
        head.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView del = iconBtn(ctx, "✕", d);
        ObjectDrawer.Kit.describe(del, ctx.getString(R.string.studio_motion_remove));
        del.setOnClickListener(v -> host.remove());
        head.addView(del);
        card.addView(head);
        if (collapsed) return;

        final MotionShutter.Controls[] now = {host.controls()};

        // ── preset chips ──
        LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        MotionShutter.Preset[] presets = MotionShutter.Preset.values();
        int[] names = {R.string.studio_motion_preset_natural, R.string.studio_motion_preset_crisp,
                R.string.studio_motion_preset_heavy, R.string.studio_motion_preset_trailing};
        TextView[] chipViews = new TextView[presets.length];

        // ── shutter row: label + value over each other, the strip, in Kit row shape ──
        LinearLayout shutterRow = ObjectDrawer.Kit.row(ctx);
        LinearLayout labelCol = new LinearLayout(ctx);
        labelCol.setOrientation(LinearLayout.VERTICAL);
        TextView shutterLabel = ObjectDrawer.Kit.rowLabel(ctx,
                ctx.getString(R.string.studio_motion_shutter), 86);
        TextView shutterValue = new TextView(ctx);
        shutterValue.setTextColor(Studio.DRAWER_INK);
        shutterValue.setTextSize(9.5f);
        shutterValue.setSingleLine(true);
        Type.mono(shutterValue, Type.SEMIBOLD);
        labelCol.addView(shutterLabel);
        labelCol.addView(shutterValue);
        shutterRow.addView(labelCol, new LinearLayout.LayoutParams(
                Math.round(86 * d), LinearLayout.LayoutParams.WRAP_CONTENT));
        ShutterStripView strip = new ShutterStripView(ctx);
        shutterRow.addView(strip, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Runnable show = () -> {
            MotionShutter.Preset current = ShutterStripModel.presetOf(now[0]);
            for (int i = 0; i < chipViews.length; i++) {
                ObjectDrawer.Kit.setChipOn(chipViews[i], presets[i] == current, Studio.GO);
            }
            String v = value(ctx, now[0]);
            shutterValue.setText(v);
            strip.setControls(now[0]);
            strip.setContentDescription(ctx.getString(R.string.studio_motion_shutter) + ", " + v);
        };

        for (int i = 0; i < presets.length; i++) {
            MotionShutter.Preset preset = presets[i];
            TextView chip = ObjectDrawer.Kit.chip(ctx, ctx.getString(names[i]));
            ObjectDrawer.Kit.pressable(chip);
            chip.setOnClickListener(v -> {
                MotionShutter.Controls before = now[0];
                MotionShutter.Controls after = ShutterStripModel.withPreset(before, preset);
                if (after.equals(before)) return;
                now[0] = after;
                show.run();
                host.commit(before, after);
            });
            chipViews[i] = chip;
            chips.addView(chip, ObjectDrawer.Kit.chipLp(ctx));
        }
        card.addView(chips);

        final MotionShutter.Controls[] dragFrom = {null};
        strip.setListener((c, done) -> {
            if (dragFrom[0] == null) dragFrom[0] = now[0];
            now[0] = c;
            show.run();
            if (!done) {
                host.preview(c);
                return;
            }
            MotionShutter.Controls before = dragFrom[0];
            dragFrom[0] = null;
            if (!c.equals(before)) host.commit(before, c);
        });
        card.addView(shutterRow);

        // ── Mix ──
        LinearLayout mixRow = ObjectDrawer.Kit.row(ctx);
        mixRow.addView(ObjectDrawer.Kit.rowLabel(ctx, ctx.getString(R.string.studio_motion_mix), 86));
        FineSeekBar mix = new FineSeekBar(ctx);
        mix.setMax(100);
        ObjectDrawer.Kit.styleSlider(mix, ObjectDrawer.Kit.onFill(Studio.GO));
        mix.setProgress(now[0].mixPercent());
        mixRow.addView(mix, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView mixValue = ObjectDrawer.Kit.value(ctx, 44);
        mixValue.setText(ctx.getString(R.string.studio_motion_percent, now[0].mixPercent()));
        mixRow.addView(mixValue);
        mix.setContentDescription(ctx.getString(R.string.studio_motion_mix));
        mix.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            MotionShutter.Controls before;
            @Override public void onStartTrackingTouch(SeekBar s) { before = now[0]; }
            @Override public void onProgressChanged(SeekBar s, int value, boolean user) {
                if (!user) return;
                now[0] = withMix(now[0], value);
                mixValue.setText(ctx.getString(R.string.studio_motion_percent, value));
                host.preview(now[0]);
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                if (before != null && !before.equals(now[0])) host.commit(before, now[0]);
                before = null;
            }
        });
        card.addView(mixRow);

        // ── D1: when the preview cannot show it, say why in one plain line ──
        @StringRes int reason = host.cannotBlurReason();
        if (reason != 0) {
            TextView why = ObjectDrawer.Kit.note(ctx, ctx.getString(reason));
            why.setTextColor(Studio.CAREFUL);
            why.setPadding(Math.round(2 * d), Math.round(2 * d), 0, Math.round(4 * d));
            card.addView(why);
        }

        // ── slim footer: the exact-frame action, right-aligned ──
        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView exact = new TextView(ctx);
        exact.setText(R.string.studio_motion_show_exact);
        exact.setTextColor(Studio.ON_GO);
        exact.setTextSize(10.5f);
        Type.body(exact, Type.BOLD);
        GradientDrawable pill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Studio.GO, Studio.GO_END});
        pill.setCornerRadius(99 * d);
        exact.setBackground(pill);
        exact.setGravity(Gravity.CENTER);
        exact.setPadding(Math.round(12 * d), 0, Math.round(12 * d), 0);
        exact.setMinHeight(Math.round(26 * d));
        ObjectDrawer.Kit.describe(exact, ctx.getString(R.string.studio_motion_show_exact_hint));
        exact.setOnClickListener(v -> host.showExactFrame());
        foot.addView(exact, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Math.round(26 * d)));
        foot.setPadding(0, Math.round(2 * d), 0, Math.round(4 * d));
        card.addView(foot);

        show.run();
    }

    /**
     * The Motion Blur tile for the "Add effect" picker: the same 76dp picture-over-name shape
     * as the shader tiles, with Studio's streak drawn in place of a rendered swatch.
     */
    @NonNull
    public static View pickerTile(@NonNull Context ctx, @NonNull Runnable onAdd) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Math.round(4 * d);
        col.setPadding(pad, pad, pad, pad);
        int side = Math.round(76 * d);
        View art = new View(ctx) {
            final android.graphics.Paint paint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(@NonNull android.graphics.Canvas c) {
                float w = getWidth(), h = getHeight();
                c.drawColor(Studio.RAISED);
                paint.setShader(new android.graphics.LinearGradient(w * 0.1f, 0, w * 0.72f, 0,
                        Studio.alpha(Studio.GO, 0), Studio.GO_END,
                        android.graphics.Shader.TileMode.CLAMP));
                c.drawRoundRect(w * 0.1f, h * 0.38f, w * 0.72f, h * 0.62f,
                        h * 0.12f, h * 0.12f, paint);
                paint.setShader(null);
                paint.setColor(Studio.GO_END);
                c.drawCircle(w * 0.68f, h / 2, h * 0.14f, paint);
            }
        };
        col.addView(art, new LinearLayout.LayoutParams(side, side));
        TextView name = new TextView(ctx);
        name.setText(R.string.studio_motion_add);
        name.setTextColor(Studio.INK);
        name.setTextSize(11f);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                side, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = Math.round(3 * d);
        col.addView(name, nlp);
        ObjectDrawer.Kit.describe(col, ctx.getString(R.string.studio_motion_add) + ": "
                + ctx.getString(R.string.studio_motion_add_hint));
        ObjectDrawer.Kit.pressable(col);
        col.setOnClickListener(v -> onAdd.run());
        return col;
    }

    /** "180° · centred", "90° · behind", "270° · ahead", "200° · custom" */
    @NonNull
    static String value(@NonNull Context ctx, @NonNull MotionShutter.Controls c) {
        @StringRes int timing = switch (c.timing()) {
            case CENTERED -> R.string.studio_motion_timing_centred;
            case TRAILING -> R.string.studio_motion_timing_behind;
            case LEADING -> R.string.studio_motion_timing_ahead;
            case CUSTOM -> R.string.studio_motion_timing_custom;
        };
        return ctx.getString(R.string.studio_motion_shutter_value, c.angle(), ctx.getString(timing));
    }

    @NonNull
    private static MotionShutter.Controls withMix(@NonNull MotionShutter.Controls c, int mix) {
        return new MotionShutter.Controls(c.angle(), c.timing(), c.customPhase(), mix);
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void holdToCompare(@NonNull View v, @NonNull Host host) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    view.setPressed(true);
                    host.compare(true);
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.setPressed(false);
                    host.compare(false);
                }
                default -> { }
            }
            return true;
        });
    }

    /** The card's swatch: a small Studio-gradient tile with a streaked dot, Studio's own mark. */
    @NonNull
    private static View swatch(@NonNull Context ctx, float d) {
        return new View(ctx) {
            final android.graphics.Paint paint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            final android.graphics.RectF r = new android.graphics.RectF();
            @Override protected void onDraw(@NonNull android.graphics.Canvas c) {
                float w = getWidth(), h = getHeight();
                paint.setShader(null);
                paint.setColor(isPressed() ? 0x33FFFFFF : 0x1AFFFFFF);
                r.set(0, 0, w, h);
                c.drawRoundRect(r, 7 * d, 7 * d, paint);
                if (isPressed()) {
                    paint.setColor(Studio.GO);
                    c.drawCircle(w * 0.62f, h / 2, h * 0.18f, paint);
                    return;
                }
                paint.setShader(new android.graphics.LinearGradient(w * 0.12f, 0, w * 0.8f, 0,
                        Studio.alpha(Studio.GO, 0), Studio.GO_END,
                        android.graphics.Shader.TileMode.CLAMP));
                r.set(w * 0.12f, h / 2 - h * 0.18f, w * 0.8f, h / 2 + h * 0.18f);
                c.drawRoundRect(r, h * 0.18f, h * 0.18f, paint);
            }
            @Override public void setPressed(boolean pressed) {
                super.setPressed(pressed);
                invalidate();
            }
        };
    }

    /** FxPanel's borderless icon action: 13sp glyph, a 44dp target, no box. */
    @NonNull
    private static TextView iconBtn(@NonNull Context ctx, @NonNull String glyph, float d) {
        TextView t = new TextView(ctx);
        t.setText(glyph);
        t.setTextColor(Studio.DRAWER_INK);
        t.setTextSize(13f);
        t.setGravity(Gravity.CENTER);
        android.util.TypedValue tv = new android.util.TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        t.setBackgroundResource(tv.resourceId);
        int size = Math.round(40 * d);
        t.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return t;
    }
}
