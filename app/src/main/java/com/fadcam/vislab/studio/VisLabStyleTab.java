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

        /** True when the visualizer's source clip has a transcript (a lyrics look's words). */
        boolean hasTranscriptWords();

        /** Objects a layer could follow, as {id, name} pairs: dummies first, never this visualizer. */
        @NonNull
        List<String[]> followTargets();

        /** A link to object {@code parentId} as it stands at the playhead (nothing moves), or null. */
        @Nullable
        com.fadcam.ui.faditor.model.SpaceLink followLinkTo(@NonNull String parentId);

        /** The follows changed: find their objects again, repaint, save. */
        void followsChanged();
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
        // A lyrics look shows the clip's transcript: say where the words come from while there are none.
        if (StudioWords.lookNeedsSongWords(VisLabSession.get(c).lookJson(lookId))) {
            TextView note = com.fadcam.ui.faditor.tools.ObjectDrawer.Kit.note(c,
                    "This look shows the clip's words. Make a transcript (Transcript, in the toolbar) and they appear here.");
            v.addView(note);
            rowRefresh.add(() -> note.setVisibility(host.hasTranscriptWords() ? View.GONE : View.VISIBLE));
        }
        rowRefresh.add(PipDrawerTabs.addPropRow(c, v, valueProp(VisLabKeys.REACT, "React", 0f, 2f,   // Reactivity; the row's label fits ~7 letters
                x -> Math.round(x * 100f) + "%"), rowHost));
        rowRefresh.add(PipDrawerTabs.addPropRow(c, v, valueProp(VisLabKeys.FADE, "Fade", 0f, 1f,
                x -> Math.round(x * 100f) + "%"), rowHost));
        // The look's own key knobs, look-wide (every layer's Brightness / Colour shift / size /
        // speeds together): what most looks need in a timeline, with the rest in VisLab.
        rowRefresh.add(PipDrawerTabs.addPropRow(c, v, valueProp(VisLabKeys.BRIGHT, "Bright", 0f, 2f,
                x -> Math.round(x * 100f) + "%"), rowHost));
        rowRefresh.add(PipDrawerTabs.addPropRow(c, v, valueProp(VisLabKeys.HUE, "Colour", 0f, 1f,
                x -> Math.round(x * 100f) + "%"), rowHost));
        rowRefresh.add(PipDrawerTabs.addPropRow(c, v, valueProp(VisLabKeys.SIZE, "Size", 0.25f, 2.5f,
                x -> Math.round(x * 100f) + "%"), rowHost));
        rowRefresh.add(PipDrawerTabs.addPropRow(c, v, valueProp(VisLabKeys.SPEED, "Speed", 0f, 3f,
                x -> Math.round(x * 100f) + "%"), rowHost));
        addFollowRow(v, lookId);
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

    // ------------------------------------------------------------------ follow (R8)

    /** "Follow": which layers of the look move with a Studio object (a dummy, a text, a sprite...). */
    private void addFollowRow(@NonNull LinearLayout v, @NonNull String lookId) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = Ui.text(c, "Follow", 12f, Studio.INK, false, 600);
        row.addView(label, new LinearLayout.LayoutParams(Ui.dp(c, 56), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView pick = Ui.text(c, "", 12f, Studio.INK, false, 600);
        pick.setGravity(Gravity.CENTER);
        pick.setMinHeight(Ui.dp(c, 36));
        pick.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 8));
        pick.setBackground(Ui.round(Studio.RAISED, Ui.dp(c, 999)));
        Ui.label(pick, "Make a layer of this look move with a dummy, text or sprite");
        pick.setOnClickListener(x -> chooseFollowLayer(lookId));
        row.addView(pick);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Ui.dp(c, 8);
        v.addView(row, rp);

        // What they carry: Move, Size, Turn. Applies to every following layer, one undo step.
        LinearLayout flags = new LinearLayout(c);
        flags.setOrientation(LinearLayout.HORIZONTAL);
        flags.setPadding(Ui.dp(c, 56), Ui.dp(c, 4), 0, 0);
        TextView[] chip = new TextView[3];
        String[] names = {"Move", "Size", "Turn"};
        String[] tips = {"The layer moves with the object", "The layer grows and shrinks with it",
                "The layer turns with it"};
        for (int i = 0; i < 3; i++) {
            final int which = i;
            chip[i] = Ui.text(c, names[i], 12f, Studio.INK, false, 600);
            chip[i].setGravity(Gravity.CENTER);
            chip[i].setMinHeight(Ui.dp(c, 32));
            chip[i].setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 6));
            Ui.label(chip[i], tips[i]);
            chip[i].setOnClickListener(x -> {
                State before = snap();
                boolean now = !followFlag(which);
                for (com.fadcam.ui.faditor.model.SpaceLink l : wf.getVisKeys().follows().values()) {
                    if (which == 0) l.position = now;
                    else if (which == 1) l.scale = now;
                    else l.rotation = now;
                    l.invalidate();
                }
                commit("Layer follow", before);
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cp.rightMargin = Ui.dp(c, 6);
            flags.addView(chip[i], cp);
        }
        v.addView(flags);
        rowRefresh.add(() -> {
            int n = wf.getVisKeys().follows().size();
            pick.setText(n == 0 ? "Pick a layer..." : n + (n == 1 ? " layer follows" : " layers follow") + "...");
            flags.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
            for (int i = 0; i < 3; i++) {
                boolean on = followFlag(i);
                chip[i].setBackground(Ui.round(on ? Studio.ARMED : Studio.RAISED, Ui.dp(c, 999)));
                chip[i].setTextColor(on ? Studio.GROUND : Studio.INK_DIM);
            }
        });
    }

    /** Is switch {@code which} (0 Move, 1 Size, 2 Turn) on for the following layers? On when any is. */
    private boolean followFlag(int which) {
        for (com.fadcam.ui.faditor.model.SpaceLink l : wf.getVisKeys().follows().values()) {
            if (which == 0 ? l.position : which == 1 ? l.scale : l.rotation) return true;
        }
        return false;
    }

    /** Step 1: which layer of the look. */
    private void chooseFollowLayer(@NonNull String lookId) {
        List<String> layers = layerNames(lookId);
        if (layers.isEmpty()) return;
        String[] items = new String[layers.size()];
        for (int i = 0; i < items.length; i++) {
            com.fadcam.ui.faditor.model.SpaceLink l = wf.getVisKeys().followOf(i);
            items[i] = (i + 1) + "  " + layers.get(i)
                    + (l != null && l.active() ? "  (follows " + targetName(l.parentId) + ")" : "");
        }
        new androidx.appcompat.app.AlertDialog.Builder(c)
                .setTitle("Which layer should follow?")
                .setItems(items, (d, which) -> chooseFollowTarget(which, layers.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Step 2: what it follows (or "Nothing" to let go). */
    private void chooseFollowTarget(int layer, @NonNull String layerName) {
        List<String[]> targets = host.followTargets();
        boolean has = wf.getVisKeys().followOf(layer) != null;
        if (targets.isEmpty() && !has) {
            new androidx.appcompat.app.AlertDialog.Builder(c)
                    .setTitle("Nothing to follow yet")
                    .setMessage("Add a dummy, a text or a sprite to the project first, then a layer can follow it.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        List<String> items = new ArrayList<>();
        for (String[] t : targets) items.add(t[1]);
        if (has) items.add("Nothing: let this layer go");
        new androidx.appcompat.app.AlertDialog.Builder(c)
                .setTitle(layerName + " follows...")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    State before = snap();
                    if (which < targets.size()) {
                        com.fadcam.ui.faditor.model.SpaceLink link = host.followLinkTo(targets.get(which)[0]);
                        if (link == null) return;
                        wf.getVisKeys().putFollow(layer, link);
                    } else {
                        wf.getVisKeys().removeFollow(layer);
                    }
                    host.followsChanged();
                    commit("Layer follow", before);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @NonNull
    private String targetName(@NonNull String id) {
        for (String[] t : host.followTargets()) if (t[0].equals(id)) return t[1];
        return "something that is gone";
    }

    /** The look's layers, in drawing order, by what they are ("Spectrum", "Words"...). */
    @NonNull
    private List<String> layerNames(@NonNull String lookId) {
        List<String> out = new ArrayList<>();
        try {
            String json = VisLabSession.get(c).lookJson(lookId);
            if (json == null) return out;
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(json)
                    .getAsJsonObject().getAsJsonArray("layers");
            if (arr == null) return out;
            for (com.google.gson.JsonElement e : arr) {
                String t = e.isJsonObject() && e.getAsJsonObject().has("type")
                        ? e.getAsJsonObject().get("type").getAsString() : "layer";
                out.add(t.isEmpty() ? "Layer" : Character.toUpperCase(t.charAt(0)) + t.substring(1));
            }
        } catch (RuntimeException ignored) {
            // an unreadable look has no layers to offer
        }
        return out;
    }

    /** Reactivity or Fade as a keyframable row: a key at the playhead once its track has keys. */
    @NonNull
    private ObjectMenuSheet.Prop valueProp(@NonNull String key, @NonNull String label, float min, float max,
                                           @NonNull ObjectMenuSheet.ValueFormat fmt) {
        VisLabKeys k = wf.getVisKeys();
        ObjectMenuSheet.Getter get = ms -> wf.visValueAt(key, ms);
        return new ObjectMenuSheet.Prop(key, label, min, max, fmt, get,
                (v, ms) -> {
                    KeyframeTrack tr = k.keys().get(key);
                    if (tr != null && !tr.isEmpty()) tr.put(local(ms), v, Easing.EASE_IN_OUT);
                    else k.set(key, v);
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
