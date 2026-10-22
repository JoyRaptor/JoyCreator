package com.fadcam.vislab.studio;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.KeyframeDiamondControl;
import com.fadcam.ui.faditor.ObjectMenuSheet;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.Keyframe;
import com.fadcam.ui.faditor.keyframe.KeyframeTrack;
import com.fadcam.ui.faditor.model.VisLabKeys;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;
import com.fadcam.ui.faditor.tools.PipDrawerTabs;
import com.fadcam.vislab.room.Ui;
import com.fadcam.vislab.room.VisLabSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The visualizer drawer's Style tab with VisLab in it (VISLAB_SPEC section 7, S2 + S4; owner
 * 10-18: VisLab looks are a section of the Rolodex, integrate, not override; 10-22: the look
 * itself keyframable, near the top). Top to bottom:
 * <ul>
 *   <li>the look row: Studio's keyframe control ({@code ‹ ◆ ›}) for WHICH look shows from when
 *       (held keys: the look changes at a key, no blend), then Classic and every VisLab look,
 *       the one showing at the playhead solid cyan;</li>
 *   <li>Classic at the playhead: the classic Rolodex, as it always was;</li>
 *   <li>a VisLab look: Reactivity and Fade, keyframable rows with Studio's diamond, then
 *       "Edit in VisLab" (the room opens on that look).</li>
 * </ul>
 * Every change is one undo step through the host. While the look is keyed, picking a look
 * writes a key at the playhead (as a keyed slider does); otherwise it sets the item's look.
 */
public final class VisLabStyleTab {

    /** What the editor gives the tab. */
    public interface Host {
        long playheadMs();

        void seek(long timelineMs);

        /** A value changed: repaint the preview and the timeline, schedule a save. */
        void changed();

        void recordUndo(@NonNull String label, @NonNull Runnable redo, @NonNull Runnable undo);

        /** A fresh classic Rolodex for this item (rebuilt whenever Classic comes back). */
        @NonNull
        View classicRolodex();

        /** Open the VisLab room on this look. */
        void openVisLab(@NonNull String lookId);
    }

    private static final long ON_KEY_MS = 66;   // as Studio's own keyed rows

    private final Context c;
    private final WaveformOverlayInstance wf;
    private final Host host;
    private final List<String> ids;
    private final List<String> names;
    private final LinearLayout root;
    private final LinearLayout chips;
    private final HorizontalScrollView chipScroll;
    private final KeyframeDiamondControl lookDiamond;
    private final FrameLayout body;
    private final List<Runnable> rowRefresh = new ArrayList<>();
    /** What the body shows, and which chip is lit: null = nothing yet, "" = classic, else a look id. */
    @Nullable private String bodyFor, chipFor;

    @NonNull
    public static View build(@NonNull Context c, @NonNull WaveformOverlayInstance wf, @NonNull Host host) {
        return new VisLabStyleTab(c, wf, host).root;
    }

    private VisLabStyleTab(@NonNull Context c, @NonNull WaveformOverlayInstance wf, @NonNull Host host) {
        this.c = c;
        this.wf = wf;
        this.host = host;
        VisLabSession session = VisLabSession.get(c);
        ids = session.lookIds();
        names = session.lookNames();

        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout lookRow = new LinearLayout(c);
        lookRow.setOrientation(LinearLayout.HORIZONTAL);
        lookRow.setGravity(Gravity.CENTER_VERTICAL);
        lookRow.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 2));
        lookDiamond = new KeyframeDiamondControl(c);
        lookDiamond.bind(lookProp(), new KeyframeDiamondControl.Host() {
            @Override public long playheadMs() { return host.playheadMs(); }
            @Override public void onFocus() { }
            @Override public void onAction() { refresh(); }
        });
        lookRow.addView(lookDiamond);
        chipScroll = new HorizontalScrollView(c);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(c);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chipScroll.addView(chips);
        lookRow.addView(chipScroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(lookRow);
        addChip(null, "Classic", "Show the classic visualizer styles here");
        for (int i = 0; i < ids.size(); i++) {
            addChip(ids.get(i), names.get(i), "Show the VisLab look " + names.get(i) + " here");
        }

        body = new FrameLayout(c);
        root.addView(body);
        // The drawer re-reads its rows when the playhead moves or an undo lands.
        root.setTag(R.id.faditor_tag_row_refresh, (Runnable) this::refresh);
        refresh();
        root.post(this::scrollToChosen);
    }

    // ------------------------------------------------------------------ the look row

    private void addChip(@Nullable String id, @NonNull String name, @NonNull String hover) {
        TextView t = Ui.text(c, name, 11.5f, Studio.INK_DIM, false, 600);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(Ui.dp(c, 30));
        t.setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 6));
        t.setTag(id == null ? VisLabKeys.CLASSIC : id);
        Ui.label(t, hover);
        t.setOnClickListener(v -> pick(id));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Ui.dp(c, 6));
        chips.addView(t, lp);
    }

    /** Classic ({@code id} null) or a VisLab look, at the playhead: one undo step. */
    private void pick(@Nullable String id) {
        State before = snap();
        VisLabKeys k = wf.getVisKeys();
        if (k.hasLookKeys()) {
            long local = local(host.playheadMs());
            Long at = near(k.lookKeys(), local);
            k.lookKeys().put(at != null ? at : local, id == null ? VisLabKeys.CLASSIC : id);
        } else {
            wf.setVisLook(id);
        }
        commit(id == null ? "Classic visualizer" : "VisLab look", before);
    }

    /** The look's keyframe control: held keys of which look shows (no slider, no easing). */
    @NonNull
    private ObjectMenuSheet.Prop lookProp() {
        VisLabKeys k = wf.getVisKeys();
        return new ObjectMenuSheet.Prop("vis_look", "Look", 0f, 1f, v -> "", ms -> 0f, (v, ms) -> { },
                ms -> near(k.lookKeys(), local(ms)) != null,
                () -> {
                    State before = snap();
                    long local = local(host.playheadMs());
                    String now = wf.visLookAt(host.playheadMs());
                    k.lookKeys().put(local, now == null ? VisLabKeys.CLASSIC : now);
                    commit(c.getString(R.string.studio_label_add_keyframe), before);
                },
                () -> jump(new ArrayList<>(k.lookKeys().keySet()), false),
                () -> jump(new ArrayList<>(k.lookKeys().keySet()), true),
                () -> {
                    Long at = near(k.lookKeys(), local(host.playheadMs()));
                    if (at == null) return;
                    State before = snap();
                    k.lookKeys().remove(at);
                    commit(c.getString(R.string.studio_label_delete_keyframe), before);
                },
                k::hasLookKeys, null, null);
    }

    private void scrollToChosen() {
        View on = chips.findViewWithTag(shownTag());
        if (on != null) chipScroll.smoothScrollTo(Math.max(0, on.getLeft() - Ui.dp(c, 24)), 0);
    }

    // ------------------------------------------------------------------ the body

    @NonNull
    private View visBody(@NonNull String lookId) {
        rowRefresh.clear();
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(Ui.dp(c, 14), Ui.dp(c, 4), Ui.dp(c, 14), Ui.dp(c, 10));
        rowRefresh.add(PipDrawerTabs.addPropRow(c, v, valueProp(VisLabKeys.REACT, "React", 0f, 2f,   // Reactivity; the row's label fits ~7 letters
                x -> Math.round(x * 100f) + "%"), rowHost));
        rowRefresh.add(PipDrawerTabs.addPropRow(c, v, valueProp(VisLabKeys.FADE, "Fade", 0f, 1f,
                x -> Math.round(x * 100f) + "%"), rowHost));
        TextView edit = Ui.text(c, "Edit in VisLab", 12f, Studio.INK, false, 600);
        edit.setGravity(Gravity.CENTER);
        edit.setMinHeight(Ui.dp(c, 36));
        edit.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 8));
        edit.setBackground(Ui.round(Studio.RAISED, Ui.dp(c, 999)));
        Ui.label(edit, "Open this look in VisLab to change its layers");
        edit.setOnClickListener(x -> host.openVisLab(lookId));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 8);
        lp.gravity = Gravity.START;
        v.addView(edit, lp);
        return v;
    }

    /** Reactivity or Fade as a keyframable row: a key at the playhead once its track has keys. */
    @NonNull
    private ObjectMenuSheet.Prop valueProp(@NonNull String key, @NonNull String label, float min, float max,
                                           @NonNull ObjectMenuSheet.ValueFormat fmt) {
        VisLabKeys k = wf.getVisKeys();
        boolean react = VisLabKeys.REACT.equals(key);
        ObjectMenuSheet.Getter get = ms -> react ? wf.visReactAt(ms) : wf.visFadeAt(ms);
        return new ObjectMenuSheet.Prop(key, label, min, max, fmt, get,
                (v, ms) -> {
                    KeyframeTrack tr = k.keys().get(key);
                    if (tr != null && !tr.isEmpty()) tr.put(local(ms), v, Easing.EASE_IN_OUT);
                    else if (react) k.setReact(v);
                    else k.setFade(v);
                    host.changed();
                },
                ms -> nearKey(k.keys().get(key), local(ms)) != null,
                () -> {
                    State before = snap();
                    long ph = host.playheadMs();
                    k.keys().getOrCreate(key).put(local(ph), get.at(ph), Easing.EASE_IN_OUT);
                    commit(c.getString(R.string.studio_label_add_keyframe), before);
                },
                () -> jump(times(k.keys().get(key)), false),
                () -> jump(times(k.keys().get(key)), true),
                () -> {
                    Long at = nearKey(k.keys().get(key), local(host.playheadMs()));
                    if (at == null) return;
                    State before = snap();
                    k.keys().removeKey(key, at);
                    commit(c.getString(R.string.studio_label_delete_keyframe), before);
                },
                () -> {
                    KeyframeTrack tr = k.keys().get(key);
                    return tr != null && !tr.isEmpty();
                },
                ms -> {
                    Keyframe owner = owner(k.keys().get(key), local(ms));
                    return owner != null ? owner.easing : null;
                },
                (e, ms) -> {
                    Keyframe owner = owner(k.keys().get(key), local(ms));
                    if (owner == null) return;
                    State before = snap();
                    owner.easing = e;
                    commit(c.getString(R.string.studio_label_ease_curve), before);
                });
    }

    private final PipDrawerTabs.Host rowHost = new PipDrawerTabs.Host() {
        @Nullable private State editBefore;

        @Override public long playheadMs() { return host.playheadMs(); }
        @Override public void onChanged() { host.changed(); }
        @Override public void pickColorFromPreview(@NonNull PipDrawerTabs.ColorPicked cb) { cb.onPicked(null); }
        @Override public boolean canPickColor() { return false; }
        @Override public void recordUndo(@NonNull String label, @NonNull Runnable redo, @NonNull Runnable undo) {
            host.recordUndo(label, redo, undo);
        }
        @Override public void beginEdit() { editBefore = snap(); }
        @Override public void endEdit(@NonNull String label) {
            if (editBefore != null) commit(label, editBefore);
            editBefore = null;
        }
    };

    // ------------------------------------------------------------------ state

    /** Re-read everything at the playhead: chips, the look's diamond, the body and its rows. */
    private void refresh() {
        String tag = shownTag();
        if (!tag.equals(chipFor)) {   // the playhead re-reads this often: restyle only on a change
            chipFor = tag;
            for (int i = 0; i < chips.getChildCount(); i++) {
                View v = chips.getChildAt(i);
                boolean on = tag.equals(v.getTag());
                v.setBackground(Ui.round(on ? Studio.ARMED : Studio.RAISED, Ui.dp(c, 999)));
                ((TextView) v).setTextColor(on ? Studio.GROUND : Studio.INK_DIM);
            }
        }
        lookDiamond.refresh(host.playheadMs());
        if (!tag.equals(bodyFor)) {
            bodyFor = tag;
            body.removeAllViews();
            if (tag.isEmpty()) {
                rowRefresh.clear();
                body.addView(host.classicRolodex());
            } else {
                body.addView(visBody(tag));
            }
        }
        for (Runnable r : rowRefresh) r.run();
    }

    /** The chip tag of the look at the playhead: a look id, or "" for Classic. */
    @NonNull
    private String shownTag() {
        String id = wf.visLookAt(host.playheadMs());
        return id == null ? VisLabKeys.CLASSIC : id;
    }

    private static final class State {
        @Nullable final String look;
        @NonNull final VisLabKeys keys;

        State(@Nullable String look, @NonNull VisLabKeys keys) {
            this.look = look;
            this.keys = keys;
        }

        boolean same(@NonNull State o) {
            return (look == null ? o.look == null : look.equals(o.look)) && keys.equals(o.keys);
        }
    }

    @NonNull
    private State snap() {
        return new State(wf.getVisLook(), wf.getVisKeys().copy());
    }

    private void restore(@NonNull State s) {
        wf.setVisLook(s.look);
        wf.getVisKeys().copyFrom(s.keys);
        host.changed();
        refresh();
    }

    /** One undo step from {@code before} to now (nothing when nothing changed). */
    private void commit(@NonNull String label, @NonNull State before) {
        State after = snap();
        if (before.same(after)) return;
        host.recordUndo(label, () -> restore(after), () -> restore(before));
        host.changed();
        refresh();
    }

    // ------------------------------------------------------------------ time

    private long local(long timelineMs) {
        return Math.max(0L, timelineMs - wf.getStartMs());
    }

    @Nullable
    private static Long near(@NonNull Map<Long, String> keys, long local) {
        for (Long t : keys.keySet()) if (Math.abs(t - local) <= ON_KEY_MS) return t;
        return null;
    }

    @Nullable
    private static Long nearKey(@Nullable KeyframeTrack tr, long local) {
        if (tr == null) return null;
        for (Keyframe k : tr.keyframes) if (Math.abs(k.timeMs - local) <= ON_KEY_MS) return k.timeMs;
        return null;
    }

    /** The key whose segment holds {@code local} (the last at or before it). */
    @Nullable
    private static Keyframe owner(@Nullable KeyframeTrack tr, long local) {
        if (tr == null) return null;
        Keyframe owner = null;
        for (Keyframe k : tr.keyframes) {
            if (k.timeMs <= local) owner = k;
            else break;
        }
        return owner;
    }

    @NonNull
    private static List<Long> times(@Nullable KeyframeTrack tr) {
        List<Long> out = new ArrayList<>();
        if (tr != null) for (Keyframe k : tr.keyframes) out.add(k.timeMs);
        return out;
    }

    /** Move the playhead to the previous or next key (item-local times). */
    private void jump(@NonNull List<Long> keys, boolean forward) {
        long local = local(host.playheadMs());
        Long best = null;
        for (long t : keys) {
            if (forward ? t > local + ON_KEY_MS : t < local - ON_KEY_MS) {
                if (best == null || (forward ? t < best : t > best)) best = t;
            }
        }
        if (best != null) host.seek(wf.getStartMs() + best);
    }
}
