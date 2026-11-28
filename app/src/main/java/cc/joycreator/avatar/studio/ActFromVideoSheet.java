package cc.joycreator.avatar.studio;

import android.content.Context;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.StudioDialog;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Act from a video", in a character layer's drawer (slice 2b, items 1 to 3 and 5): the project's videos and sounds,
 * a tick box each for what the character follows from it, a nudge to slide it earlier or later, and Keep as driver.
 * Read writes the take (one undo step, the host's); Bake keeps the keyframes and lets the videos go.
 *
 * <p>Only the drawing of the sheet lives here; reading is {@link ActingRun}'s, and every change to the project goes
 * through the {@link Host} (the editor), which owns undo and saving.</p>
 */
public final class ActFromVideoSheet {

    public interface Host {
        /** Read the plan into the item's take: one undo step, progress shown by the host. */
        void readActing(@NonNull SpriteOverlayItem item, @NonNull Acting.Plan plan);

        /** Keep the keyframes, let the plan's videos go (picture-in-picture videos are removed): one undo step. */
        void bakeActing(@NonNull SpriteOverlayItem item, @NonNull Acting.Plan plan);
    }

    private static final long NUDGE_STEP_MS = 100;

    private ActFromVideoSheet() {}

    public static void show(@NonNull Context c, @NonNull SpriteOverlayItem item, @NonNull List<ActingRun.Choice> choices,
                            @NonNull Host host) {
        final Acting.Plan plan = planOf(item);
        int pad = SheetKit.dp(c, 16);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, 0);

        TextView intro = new TextView(c);
        intro.setText(R.string.ava_act_intro);
        intro.setTextColor(Studio.INK_DIM);
        intro.setTextSize(13);
        root.addView(intro);

        if (choices.isEmpty()) {
            TextView none = new TextView(c);
            none.setText(R.string.ava_act_none);
            none.setTextColor(Studio.INK_FAINT);
            none.setTextSize(13);
            none.setPadding(0, pad / 2, 0, 0);
            root.addView(none);
        }

        final List<Runnable> refresh = new ArrayList<>();
        for (ActingRun.Choice ch : choices) {
            root.addView(SheetKit.divider(c));
            TextView name = new TextView(c);
            name.setText(ch.name);
            name.setTextColor(Studio.INK);
            name.setTextSize(15);
            name.setPadding(0, pad / 2, 0, 0);
            root.addView(name);

            LinearLayout ticks = new LinearLayout(c);
            ticks.setOrientation(LinearLayout.VERTICAL);
            root.addView(ticks);
            for (Acting.Listen l : Acting.Listen.values()) {
                if (ch.sound && l != Acting.Listen.LIPS) continue;
                CheckBox box = new CheckBox(c);
                SheetKit.styleCheckbox(box);
                box.setText(labelOf(l));
                box.setTextColor(Studio.INK);
                SheetKit.label(box, c.getString(hoverOf(l, ch.sound)));
                boolean body = l == Acting.Listen.SHOULDERS;
                if (body) {   // the body tracker is not in this build yet: shown, honest, not tickable
                    box.setEnabled(false);
                    box.setAlpha(0.45f);
                }
                Runnable sync = () -> {
                    Acting.Source s = plan.source(ch.id);
                    box.setOnCheckedChangeListener(null);
                    box.setChecked(s != null && s.listen.contains(l));
                    box.setOnCheckedChangeListener((b, on) -> {
                        plan.tick(ch.id, l, on);
                        for (Runnable r : refresh) r.run();
                    });
                };
                refresh.add(sync);
                sync.run();
                ticks.addView(box);
            }

            // the nudge: earlier / later by a tenth of a second
            LinearLayout nudge = new LinearLayout(c);
            nudge.setOrientation(LinearLayout.HORIZONTAL);
            nudge.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView earlier = SheetKit.chip(c, c.getString(R.string.ava_act_earlier));
            TextView later = SheetKit.chip(c, c.getString(R.string.ava_act_later));
            TextView at = new TextView(c);
            at.setTextColor(Studio.INK_DIM);
            at.setTextSize(13);
            at.setPadding(pad / 2, 0, pad / 2, 0);
            SheetKit.label(earlier, c.getString(R.string.ava_act_earlier_hover));
            SheetKit.label(later, c.getString(R.string.ava_act_later_hover));
            Runnable showNudge = () -> {
                Acting.Source s = plan.source(ch.id);
                long ms = s == null ? 0 : s.nudgeMs;
                at.setText(ms == 0 ? c.getString(R.string.ava_act_in_step)
                        : String.format(Locale.US, "%+.1f s", ms / 1000.0));
                boolean on = s != null && !s.listen.isEmpty();
                earlier.setEnabled(on);
                later.setEnabled(on);
                earlier.setAlpha(on ? 1f : 0.4f);
                later.setAlpha(on ? 1f : 0.4f);
            };
            earlier.setOnClickListener(v -> nudge(plan, ch.id, -NUDGE_STEP_MS, showNudge));
            later.setOnClickListener(v -> nudge(plan, ch.id, NUDGE_STEP_MS, showNudge));
            refresh.add(showNudge);
            showNudge.run();
            nudge.addView(earlier);
            nudge.addView(at);
            nudge.addView(later);
            nudge.setPadding(0, pad / 4, 0, pad / 2);
            root.addView(nudge);
        }

        root.addView(SheetKit.divider(c));
        CheckBox keep = new CheckBox(c);
        SheetKit.styleCheckbox(keep);
        keep.setText(R.string.ava_act_keep);
        keep.setTextColor(Studio.INK);
        keep.setChecked(plan.keepAsDriver);
        SheetKit.label(keep, c.getString(R.string.ava_act_keep_hover));
        keep.setOnCheckedChangeListener((b, on) -> plan.keepAsDriver = on);
        root.addView(keep);
        TextView keepNote = new TextView(c);
        keepNote.setText(R.string.ava_act_keep_note);
        keepNote.setTextColor(Studio.INK_FAINT);
        keepNote.setTextSize(11);
        root.addView(keepNote);

        ScrollView scroll = new ScrollView(c);
        scroll.addView(root);
        AlertDialog d = StudioDialog.builder(c)
                .setTitle(R.string.ava_act_title)
                .setView(scroll)
                .setPositiveButton(R.string.ava_act_read, null)
                .setNeutralButton(R.string.ava_act_bake, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        d.show();
        View read = d.getButton(AlertDialog.BUTTON_POSITIVE);
        View bake = d.getButton(AlertDialog.BUTTON_NEUTRAL);
        SheetKit.label(read, c.getString(R.string.ava_act_read_hover));
        SheetKit.label(bake, c.getString(R.string.ava_act_bake_hover));
        SheetKit.label(d.getButton(AlertDialog.BUTTON_NEGATIVE), c.getString(R.string.ava_cancel_hover));
        read.setOnClickListener(v -> {
            plan.tidy();
            if (plan.isEmpty()) {
                android.widget.Toast.makeText(c, R.string.ava_act_tick_something, android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            d.dismiss();
            host.readActing(item, plan);
        });
        bake.setOnClickListener(v -> {
            d.dismiss();
            host.bakeActing(item, planOf(item));
        });
        bake.setEnabled(!planOf(item).isEmpty());
    }

    private static void nudge(Acting.Plan plan, String id, long by, Runnable show) {
        Acting.Source s = plan.source(id);
        if (s == null) return;
        s.nudgeMs = Math.max(-10_000, Math.min(10_000, s.nudgeMs + by));
        show.run();
    }

    /** The item's saved plan, or an empty one. */
    @NonNull
    public static Acting.Plan planOf(@NonNull SpriteOverlayItem item) {
        String j = item.getCharacterActing();
        if (j == null) return new Acting.Plan();
        try {
            return Acting.Plan.fromJson(new org.json.JSONObject(j));
        } catch (Exception e) {
            return new Acting.Plan();
        }
    }

    static int labelOf(Acting.Listen l) {
        switch (l) {
            case LIPS: return R.string.ava_act_lips;
            case HEAD: return R.string.ava_act_head;
            case EYES: return R.string.ava_act_eyes;
            case BROWS: return R.string.ava_act_brows;
            case SHOULDERS: return R.string.ava_act_shoulders;
            default: return R.string.ava_act_leaning;
        }
    }

    static int hoverOf(Acting.Listen l, boolean sound) {
        switch (l) {
            case LIPS: return sound ? R.string.ava_act_lips_sound_hover : R.string.ava_act_lips_hover;
            case HEAD: return R.string.ava_act_head_hover;
            case EYES: return R.string.ava_act_eyes_hover;
            case BROWS: return R.string.ava_act_brows_hover;
            case SHOULDERS: return R.string.ava_act_shoulders_hover;
            default: return R.string.ava_act_leaning_hover;
        }
    }
}
