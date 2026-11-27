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
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.motion.ShutterKeys;
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
 * chips, the shutter row and the Mix row, plus one slim footer line. Shutter and Mix each have
 * the effect cards' ‹◆› keyframe control: once a part has keys, editing it keys the playhead,
 * exactly as an effect card's keyed slider does.</p>
 */
public final class MotionBlurCard {

    /** The object's fixed setting and its keys: what one undo step holds. */
    public record State(@NonNull MotionShutter.Controls base, @NonNull ShutterKeys keys) {}

    /** What the card edits. The editor implements it for each kind of object. */
    public interface Host {
        @NonNull State state();
        /** Show {@code draft} in the preview while a control is moving. No undo step. */
        void preview(@NonNull State draft);
        /** Keep {@code after} as one undo step back to {@code before}. */
        void commit(@NonNull State before, @NonNull State after);
        /** Absolute timeline ms of the playhead, the base the keys are stored in. */
        long playheadMs();
        /** Move the playhead (the ‹ › chevrons). */
        void seekTo(long ms);
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
        /**
         * §K.2: the one plain line when {@code c} asks for more than this object can draw yet
         * (a long trail on filmed video or a puppet), or 0.
         */
        default @StringRes int cannotTrailReason(@NonNull MotionShutter.Controls c) { return 0; }
    }

    /** The Streak preset (§K.2): eight frames back, fading out, one long smear. */
    public static MotionShutter.Controls streak(@NonNull MotionShutter.Controls like) {
        return like.withWindow(8 * 360, MotionShutter.Timing.TRAILING, 0)
                .withLook(60, MotionShutter.Look.SMEAR, like.copies());
    }

    static boolean isStreak(@NonNull MotionShutter.Controls c) {
        return c.angle() == 8 * 360 && c.fadePercent() == 60 && c.look() == MotionShutter.Look.SMEAR;
    }

    /** Folds opened this session, by object id. */
    private static final Set<String> MORE_OPEN = new HashSet<>();

    /** A key this close to the playhead counts as "on the key" (about two frames, as FxPanel). */
    private static final long ON_KEY_MS = 40;

    /** Cards folded shut this session, by object id: a rebuild keeps them shut. */
    private static final Set<String> COLLAPSED = new HashSet<>();

    /** The card's root: carries the playhead refresher the editor's scrub walk calls. */
    private static final class CardRoot extends LinearLayout {
        @Nullable java.util.function.LongConsumer onPlayhead;
        CardRoot(Context c) { super(c); }
    }

    private MotionBlurCard() {}

    /**
     * Re-read every Motion Blur card under {@code root} for a new playhead: keyed values and the
     * diamonds follow the scrub. The editor calls it beside {@code FxPanel.refreshRows}.
     */
    public static void refresh(@Nullable View root, long playheadMs) {
        if (root instanceof CardRoot c) {
            if (c.onPlayhead != null) c.onPlayhead.accept(playheadMs);
            return;
        }
        if (root instanceof android.view.ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) refresh(vg.getChildAt(i), playheadMs);
        }
    }

    @NonNull
    public static View build(@NonNull Context ctx, @NonNull String objectId, @NonNull Host host) {
        float d = ctx.getResources().getDisplayMetrics().density;
        CardRoot card = new CardRoot(ctx);
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

    private static void fill(@NonNull Context ctx, @NonNull CardRoot card,
                             @NonNull String objectId, @NonNull Host host, float d) {
        card.removeAllViews();
        card.onPlayhead = null;
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

        final State[] st = {host.state()};
        final long[] at = {host.playheadMs()};

        // ── preset chips ──
        LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        MotionShutter.Preset[] presets = MotionShutter.Preset.values();
        int[] names = {R.string.studio_motion_preset_natural, R.string.studio_motion_preset_crisp,
                R.string.studio_motion_preset_heavy, R.string.studio_motion_preset_trailing};
        TextView[] chipViews = new TextView[presets.length];
        TextView streakChip = ObjectDrawer.Kit.chip(ctx, ctx.getString(R.string.studio_motion_preset_streak));
        ObjectDrawer.Kit.pressable(streakChip);
        ObjectDrawer.Kit.describe(streakChip, ctx.getString(R.string.studio_motion_preset_streak_hint));

        // ── shutter row: label + value over each other, the strip, its ‹◆› ──
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
        KeyControl shutterKeys = new KeyControl(ctx, d,
                ctx.getString(R.string.studio_motion_shutter));
        shutterRow.addView(shutterKeys.root);

        // ── Mix row ──
        LinearLayout mixRow = ObjectDrawer.Kit.row(ctx);
        mixRow.addView(ObjectDrawer.Kit.rowLabel(ctx, ctx.getString(R.string.studio_motion_mix), 86));
        FineSeekBar mix = new FineSeekBar(ctx);
        mix.setMax(100);
        ObjectDrawer.Kit.styleSlider(mix, ObjectDrawer.Kit.onFill(Studio.GO));
        mixRow.addView(mix, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView mixValue = ObjectDrawer.Kit.value(ctx, 44);
        mixRow.addView(mixValue);
        mix.setContentDescription(ctx.getString(R.string.studio_motion_mix));
        KeyControl mixKeys = new KeyControl(ctx, d, ctx.getString(R.string.studio_motion_mix));
        mixRow.addView(mixKeys.root);

        TextView trailNote = ObjectDrawer.Kit.note(ctx, "");
        trailNote.setTextColor(Studio.CAREFUL);
        trailNote.setPadding(Math.round(2 * d), 0, 0, Math.round(2 * d));

        // ── More: Fade and Look (every blur, long or short), folded shut by default ──
        LinearLayout more = new LinearLayout(ctx);
        more.setOrientation(LinearLayout.VERTICAL);
        more.setVisibility(MORE_OPEN.contains(objectId) ? View.VISIBLE : View.GONE);
        LinearLayout fadeRow = ObjectDrawer.Kit.row(ctx);
        fadeRow.addView(ObjectDrawer.Kit.rowLabel(ctx, ctx.getString(R.string.studio_motion_fade), 86));
        FineSeekBar fade = new FineSeekBar(ctx);
        fade.setMax(100);
        ObjectDrawer.Kit.styleSlider(fade, ObjectDrawer.Kit.onFill(Studio.GO));
        fade.setContentDescription(ctx.getString(R.string.studio_motion_fade_hint));
        fadeRow.addView(fade, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView fadeValue = ObjectDrawer.Kit.value(ctx, 44);
        fadeRow.addView(fadeValue);
        more.addView(fadeRow);
        LinearLayout lookRow = ObjectDrawer.Kit.row(ctx);
        lookRow.addView(ObjectDrawer.Kit.rowLabel(ctx, ctx.getString(R.string.studio_motion_look), 86));
        MotionShutter.Look[] looks = MotionShutter.Look.values();
        int[] lookNames = {R.string.studio_motion_look_smear, R.string.studio_motion_look_copies,
                R.string.studio_motion_look_both};
        int[] lookHints = {R.string.studio_motion_look_smear_hint, R.string.studio_motion_look_copies_hint,
                R.string.studio_motion_look_both_hint};
        TextView[] lookChips = new TextView[looks.length];
        for (int i = 0; i < looks.length; i++) {
            lookChips[i] = ObjectDrawer.Kit.chip(ctx, ctx.getString(lookNames[i]));
            ObjectDrawer.Kit.pressable(lookChips[i]);
            ObjectDrawer.Kit.describe(lookChips[i], ctx.getString(lookHints[i]));
            lookRow.addView(lookChips[i], ObjectDrawer.Kit.chipLp(ctx));
        }
        View lookGap = new View(ctx);
        lookRow.addView(lookGap, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView fewer = ObjectDrawer.Kit.stepper(ctx, "−", ctx.getString(R.string.studio_motion_copies_fewer));
        TextView copiesValue = ObjectDrawer.Kit.value(ctx, 18);
        copiesValue.setGravity(Gravity.CENTER);
        TextView moreCopies = ObjectDrawer.Kit.stepper(ctx, "+", ctx.getString(R.string.studio_motion_copies_more));
        lookRow.addView(fewer);
        lookRow.addView(copiesValue);
        lookRow.addView(moreCopies);
        more.addView(lookRow);

        Runnable showLook = () -> {
            MotionShutter.Controls b = st[0].base();
            fade.setProgress(b.fadePercent());
            fadeValue.setText(ctx.getString(R.string.studio_motion_percent, b.fadePercent()));
            for (int i = 0; i < looks.length; i++) {
                ObjectDrawer.Kit.setChipOn(lookChips[i], looks[i] == b.look(), Studio.GO);
            }
            boolean counted = b.look() != MotionShutter.Look.SMEAR;
            copiesValue.setText(String.valueOf(b.copies()));
            float a = counted ? 1f : 0.35f;
            fewer.setAlpha(a);
            moreCopies.setAlpha(a);
            copiesValue.setAlpha(a);
            fewer.setEnabled(counted && b.copies() > 2);
            moreCopies.setEnabled(counted && b.copies() < 7);
        };

        Runnable show = () -> {
            MotionShutter.Controls now = st[0].keys().at(at[0], st[0].base());
            MotionShutter.Preset current = now.isLongTrail() ? null : ShutterStripModel.presetOf(now);
            for (int i = 0; i < chipViews.length; i++) {
                ObjectDrawer.Kit.setChipOn(chipViews[i], presets[i] == current, Studio.GO);
            }
            ObjectDrawer.Kit.setChipOn(streakChip, isStreak(now), Studio.GO);
            @StringRes int trailWhy = host.cannotTrailReason(now);
            trailNote.setVisibility(trailWhy == 0 ? View.GONE : View.VISIBLE);
            if (trailWhy != 0) trailNote.setText(trailWhy);
            showLook.run();
            String v = value(ctx, now);
            shutterValue.setText(v);
            strip.setControls(now);
            strip.setContentDescription(ctx.getString(R.string.studio_motion_shutter) + ", " + v);
            mix.setProgress(now.mixPercent());
            mixValue.setText(ctx.getString(R.string.studio_motion_percent, now.mixPercent()));
            ShutterKeys k = st[0].keys();
            shutterKeys.show(k.shutterTimes(), at[0]);
            mixKeys.show(k.mixTimes(), at[0]);
        };

        for (int i = 0; i < presets.length; i++) {
            MotionShutter.Preset preset = presets[i];
            TextView chip = ObjectDrawer.Kit.chip(ctx, ctx.getString(names[i]));
            ObjectDrawer.Kit.pressable(chip);
            chip.setOnClickListener(v -> {
                State before = st[0];
                MotionShutter.Controls now = before.keys().at(at[0], before.base());
                State after = withShutter(before, ShutterStripModel.withPreset(now, preset), at[0]);
                if (after.equals(before)) return;
                st[0] = after;
                show.run();
                host.commit(before, after);
            });
            chipViews[i] = chip;
            chips.addView(chip, ObjectDrawer.Kit.chipLp(ctx));
        }
        streakChip.setOnClickListener(v -> {
            State before = st[0];
            MotionShutter.Controls now = before.keys().at(at[0], before.base());
            State after = new State(streak(before.base()), before.keys().hasShutterKeys()
                    ? before.keys().withShutterKey(at[0], streak(now)) : before.keys());
            if (after.equals(before)) return;
            st[0] = after;
            show.run();
            host.commit(before, after);
        });
        chips.addView(streakChip, ObjectDrawer.Kit.chipLp(ctx));
        // Five chips: they scroll sideways on a narrow phone rather than wrap to a second line.
        android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(ctx);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chips);
        card.addView(chipScroll);

        final State[] dragFrom = {null};
        strip.setListener((c, done) -> {
            if (dragFrom[0] == null) dragFrom[0] = st[0];
            st[0] = withShutter(dragFrom[0], c, at[0]);
            shutterValue.setText(value(ctx, c));
            if (!done) {
                host.preview(st[0]);
                return;
            }
            State before = dragFrom[0];
            dragFrom[0] = null;
            show.run();
            if (!st[0].equals(before)) host.commit(before, st[0]);
        });
        card.addView(shutterRow);

        mix.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            State before;
            @Override public void onStartTrackingTouch(SeekBar s) { before = st[0]; }
            @Override public void onProgressChanged(SeekBar s, int value, boolean user) {
                if (!user || before == null) return;
                st[0] = withMix(before, value, at[0]);
                mixValue.setText(ctx.getString(R.string.studio_motion_percent, value));
                host.preview(st[0]);
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                if (before != null && !before.equals(st[0])) host.commit(before, st[0]);
                before = null;
                show.run();
            }
        });
        card.addView(mixRow);

        // ‹◆›: tap keys (or un-keys) the playhead; the chevrons jump to the next key.
        shutterKeys.wire(host, () -> {
            State before = st[0];
            long hit = near(before.keys().shutterTimes(), at[0]);
            State after = hit >= 0
                    ? new State(before.base(), before.keys().withoutShutterKey(hit))
                    : new State(before.base(), before.keys().withShutterKey(at[0],
                            before.keys().at(at[0], before.base())));
            st[0] = after;
            show.run();
            host.commit(before, after);
        }, () -> st[0].keys().shutterTimes(), at);
        mixKeys.wire(host, () -> {
            State before = st[0];
            long hit = near(before.keys().mixTimes(), at[0]);
            State after = hit >= 0
                    ? new State(before.base(), before.keys().withoutMixKey(hit))
                    : new State(before.base(), before.keys().withMixKey(at[0],
                            before.keys().at(at[0], before.base())));
            st[0] = after;
            show.run();
            host.commit(before, after);
        }, () -> st[0].keys().mixTimes(), at);

        card.onPlayhead = ms -> {
            if (ms == at[0]) return;
            at[0] = ms;
            if (dragFrom[0] == null) show.run();
        };

        // ── D1: when the preview cannot show it, say why in one plain line ──
        @StringRes int reason = host.cannotBlurReason();
        if (reason != 0) {
            TextView why = ObjectDrawer.Kit.note(ctx, ctx.getString(reason));
            why.setTextColor(Studio.CAREFUL);
            why.setPadding(Math.round(2 * d), Math.round(2 * d), 0, Math.round(4 * d));
            card.addView(why);
        }

        card.addView(trailNote);

        java.util.function.Consumer<MotionShutter.Controls> commitLook = next -> {
            State before = st[0];
            State after = new State(next, before.keys());
            if (after.equals(before)) return;
            st[0] = after;
            show.run();
            host.commit(before, after);
        };
        fade.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            State before;
            @Override public void onStartTrackingTouch(SeekBar sb) { before = st[0]; }
            @Override public void onProgressChanged(SeekBar sb, int value, boolean user) {
                if (!user || before == null) return;
                MotionShutter.Controls b = before.base();
                st[0] = new State(b.withLook(value, b.look(), b.copies()), before.keys());
                fadeValue.setText(ctx.getString(R.string.studio_motion_percent, value));
                host.preview(st[0]);
            }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (before != null && !before.equals(st[0])) host.commit(before, st[0]);
                before = null;
                show.run();
            }
        });
        for (int i = 0; i < looks.length; i++) {
            MotionShutter.Look look = looks[i];
            lookChips[i].setOnClickListener(v -> {
                MotionShutter.Controls b = st[0].base();
                commitLook.accept(b.withLook(b.fadePercent(), look, b.copies()));
            });
        }
        fewer.setOnClickListener(v -> {
            MotionShutter.Controls b = st[0].base();
            commitLook.accept(b.withLook(b.fadePercent(), b.look(), b.copies() - 1));
        });
        moreCopies.setOnClickListener(v -> {
            MotionShutter.Controls b = st[0].base();
            commitLook.accept(b.withLook(b.fadePercent(), b.look(), b.copies() + 1));
        });
        card.addView(more);

        // ── slim footer: "More" on the left, the exact-frame action on the right, one line ──
        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        TextView moreTab = ObjectDrawer.Kit.sectionLabel(ctx,
                (more.getVisibility() == View.VISIBLE ? "▾ " : "▸ ") + ctx.getString(R.string.studio_motion_more));
        ObjectDrawer.Kit.describe(moreTab, ctx.getString(R.string.studio_motion_more_hint));
        moreTab.setPadding(Math.round(2 * d), Math.round(6 * d), Math.round(16 * d), Math.round(6 * d));
        moreTab.setOnClickListener(v -> {
            boolean open = more.getVisibility() != View.VISIBLE;
            more.setVisibility(open ? View.VISIBLE : View.GONE);
            if (open) MORE_OPEN.add(objectId); else MORE_OPEN.remove(objectId);
            moreTab.setText((open ? "▾ " : "▸ ") + ctx.getString(R.string.studio_motion_more));
        });
        foot.addView(moreTab);
        foot.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
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
     * A shutter edit applied where it belongs: as a key at the playhead once the shutter has
     * keys (an effect card's keyed slider does the same), else to the fixed setting.
     */
    @NonNull
    static State withShutter(@NonNull State s, @NonNull MotionShutter.Controls c, long at) {
        if (s.keys().hasShutterKeys()) return new State(s.base(), s.keys().withShutterKey(at, c));
        MotionShutter.Controls b = s.base();
        return new State(new MotionShutter.Controls(c.angle(), c.timing(), c.customPhase(),
                b.mixPercent()), s.keys());
    }

    /** A Mix edit, keyed at the playhead once Mix has keys. */
    @NonNull
    static State withMix(@NonNull State s, int mix, long at) {
        MotionShutter.Controls b = s.base();
        if (s.keys().hasMixKeys()) {
            return new State(b, s.keys().withMixKey(at,
                    new MotionShutter.Controls(b.angle(), b.timing(), b.customPhase(), mix)));
        }
        return new State(new MotionShutter.Controls(b.angle(), b.timing(), b.customPhase(), mix),
                s.keys());
    }

    /** The key within {@link #ON_KEY_MS} of {@code at}, or -1. */
    static long near(@NonNull long[] times, long at) {
        long best = -1;
        long bestGap = ON_KEY_MS + 1;
        for (long t : times) {
            long gap = Math.abs(t - at);
            if (gap < bestGap) { best = t; bestGap = gap; }
        }
        return best;
    }

    /** The effect cards' ‹◆› cluster: jump to the previous key, key here, jump to the next. */
    private static final class KeyControl {
        final LinearLayout root;
        final TextView prev, diamond, next;

        KeyControl(@NonNull Context ctx, float d, @NonNull String what) {
            root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            prev = glyph(ctx, "‹", d, 18);
            diamond = glyph(ctx, "◇", d, 26);
            next = glyph(ctx, "›", d, 18);
            ObjectDrawer.Kit.describe(prev, ctx.getString(R.string.studio_motion_key_prev, what));
            ObjectDrawer.Kit.describe(diamond, ctx.getString(R.string.studio_motion_key_toggle, what));
            ObjectDrawer.Kit.describe(next, ctx.getString(R.string.studio_motion_key_next, what));
            root.addView(prev);
            root.addView(diamond);
            root.addView(next);
        }

        void wire(@NonNull Host host, @NonNull Runnable toggle,
                  @NonNull java.util.function.Supplier<long[]> times, @NonNull long[] at) {
            diamond.setOnClickListener(v -> toggle.run());
            prev.setOnClickListener(v -> {
                long t = ShutterKeys.previous(times.get(), at[0] - ON_KEY_MS);
                if (t >= 0) host.seekTo(t);
            });
            next.setOnClickListener(v -> {
                long t = ShutterKeys.next(times.get(), at[0] + ON_KEY_MS);
                if (t >= 0) host.seekTo(t);
            });
        }

        /** Solid on a key (the app's keyed colour), hollow when keyed elsewhere, dim when none. */
        void show(@NonNull long[] times, long at) {
            boolean any = times.length > 0;
            boolean here = near(times, at) >= 0;
            diamond.setText(here ? "◆" : "◇");
            diamond.setTextColor(here ? Studio.CAREFUL : any ? Studio.DRAWER_INK : Studio.DRAWER_LABEL);
            int chevron = any ? Studio.DRAWER_INK : Studio.alpha(Studio.DRAWER_LABEL, 0x66);
            prev.setTextColor(chevron);
            next.setTextColor(chevron);
            prev.setEnabled(any);
            next.setEnabled(any);
        }

        private static TextView glyph(@NonNull Context ctx, @NonNull String g, float d, int wDp) {
            TextView t = new TextView(ctx);
            t.setText(g);
            t.setTextSize(g.equals("◇") ? 14f : 15f);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(Studio.DRAWER_LABEL);
            android.util.TypedValue tv = new android.util.TypedValue();
            ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
            t.setBackgroundResource(tv.resourceId);
            t.setLayoutParams(new LinearLayout.LayoutParams(Math.round(wDp * d), Math.round(36 * d)));
            return t;
        }
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
        if (c.isLongTrail()) {
            return ctx.getString(R.string.studio_motion_strip_back, Math.round(c.angle() / 360f));
        }
        @StringRes int timing = switch (c.timing()) {
            case CENTERED -> R.string.studio_motion_timing_centred;
            case TRAILING -> R.string.studio_motion_timing_behind;
            case LEADING -> R.string.studio_motion_timing_ahead;
            case CUSTOM -> R.string.studio_motion_timing_custom;
        };
        return ctx.getString(R.string.studio_motion_shutter_value, c.angle(), ctx.getString(timing));
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
