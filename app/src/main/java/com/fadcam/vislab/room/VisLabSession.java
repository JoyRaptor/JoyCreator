package com.fadcam.vislab.room;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.vislab.VisLab;
import com.fadcam.vislab.gl.ShaderLib;
import com.fadcam.vislab.model.LookValidator;
import com.fadcam.vislab.model.VisScene;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * The looks VisLab is showing, owned once for the whole app, so the room, Joybot and (later)
 * Studio all change the same thing. Joybot's chat sits on top of the room while he works: the
 * session keeps his change and the room shows it the moment it is back in front.
 *
 * <p>Every public method may be called from any thread; work is done on the main thread (a
 * call from elsewhere waits for it, up to 3 s).</p>
 *
 * <p><b>Undo.</b> One request, one undo (the owner's rule). Each change records a snapshot of
 * what it is about to touch: the looks it changes, which look was on screen, the brand colours
 * and how many looks there were. Changes passed the same non-null {@code group} as the newest
 * snapshot merge into it (the oldest before-state wins), so a Joybot request that picks a look,
 * turns three knobs and sets brand colours undoes in one step. The room's own edits use no
 * group: one gesture, one step.</p>
 */
public final class VisLabSession {

    /** What a change did, in words Joybot can repeat. */
    public static final class ApplyResult {
        public final boolean ok;
        /** Display name of the look now on screen (null when nothing changed). */
        @Nullable public final String name;
        /** Plain-language notes: what was clamped, dropped or could not be read. */
        @NonNull public final List<String> warnings;

        ApplyResult(boolean ok, @Nullable String name, @NonNull List<String> warnings) {
            this.ok = ok;
            this.name = name;
            this.warnings = Collections.unmodifiableList(warnings);
        }
    }

    /** The room listens while it exists (open, or paused under Joybot's chat). */
    interface Listener {
        void onLook(@NonNull VisScene look, int index);

        void onBrand(@Nullable String[] stops);
    }

    /** One undo step: the state of everything a change (or a group of changes) touched. */
    private static final class Snap {
        @Nullable final String group;
        @NonNull final String label;
        final int current;
        @Nullable final String[] brand;
        final int count;
        /** Look index -> its JSON before the change (only the looks touched). */
        final TreeMap<Integer, String> looks = new TreeMap<>();

        Snap(@Nullable String group, @NonNull String label, int current, @Nullable String[] brand, int count) {
            this.group = group;
            this.label = label;
            this.current = current;
            this.brand = brand;
            this.count = count;
        }
    }

    private static final String PREFS = "vislab";
    private static final String PREF_LAST = "last_look";
    private static final int MAX_UNDO = 60;
    private static volatile VisLabSession instance;

    private final Context app;
    private final List<VisScene> looks = new ArrayList<>();
    private int current;
    @Nullable private String[] brand;
    @Nullable private Listener room;
    private final ArrayList<Snap> undo = new ArrayList<>(), redo = new ArrayList<>();

    private VisLabSession(@NonNull Context c) {
        app = c.getApplicationContext();
        for (VisScene s : VisLab.get(app).builtInLooks()) looks.add(s.copy());
        String last = prefs().getString(PREF_LAST, null);
        for (int i = 0; i < looks.size(); i++) if (looks.get(i).id().equals(last)) current = i;
    }

    @NonNull
    public static VisLabSession get(@NonNull Context c) {
        VisLabSession s = instance;
        if (s != null) return s;
        synchronized (VisLabSession.class) {
            if (instance == null) instance = onMain(() -> new VisLabSession(c));
            return instance;
        }
    }

    // ------------------------------------------------------------------ reading

    /** A copy of the look on screen (joy.vis/1 JSON), or null when there are no looks. */
    @Nullable
    public String currentLookJson() {
        return onMain(() -> looks.isEmpty() ? null : looks.get(current).toJson());
    }

    @NonNull
    public List<String> lookIds() {
        return onMain(() -> {
            List<String> ids = new ArrayList<>();
            for (VisScene s : looks) ids.add(s.id());
            return ids;
        });
    }

    /** Display names in the same order as {@link #lookIds()}. */
    @NonNull
    public List<String> lookNames() {
        return onMain(() -> {
            List<String> n = new ArrayList<>();
            for (VisScene s : looks) n.add(s.name());
            return n;
        });
    }

    @Nullable
    public String lookJson(@NonNull String id) {
        return onMain(() -> {
            int i = indexOf(id);
            return i < 0 ? null : looks.get(i).toJson();
        });
    }

    /** The knob registry (types.json) as shipped. */
    @NonNull
    public String typesJson() {
        try {
            return ShaderLib.readAll(app.getAssets().open("vislab/types.json"));
        } catch (IOException e) {
            return "{}";
        }
    }

    /** True while the VisLab room exists (on screen, or under Joybot's chat). */
    public boolean isRoomOpen() {
        return onMain(() -> room != null);
    }

    @Nullable
    public String[] brand() {
        return onMain(() -> brand);
    }

    // ------------------------------------------------------------------ changing

    @NonNull
    public ApplyResult pickLook(@NonNull String id, @NonNull String undoLabel) {
        return pickLook(id, undoLabel, null);
    }

    /** Show look {@code id}. Changes sharing a non-null {@code group} undo as one step. */
    @NonNull
    public ApplyResult pickLook(@NonNull String id, @NonNull String undoLabel, @Nullable String group) {
        return onMain(() -> {
            int i = indexOf(id);
            if (i < 0) return new ApplyResult(false, null, Collections.singletonList("There is no look called \"" + id + "\"."));
            remember(group, undoLabel);
            show(i);
            return new ApplyResult(true, looks.get(i).name(), new ArrayList<>());
        });
    }

    @NonNull
    public ApplyResult apply(@NonNull String lookJson, @NonNull String undoLabel) {
        return apply(lookJson, undoLabel, null);
    }

    /**
     * Put a whole look on screen. A look whose id matches one in the list replaces it; a new id
     * is added. The look is validated first; the warnings say what was fixed. Changes sharing
     * a non-null {@code group} undo as one step.
     */
    @NonNull
    public ApplyResult apply(@NonNull String lookJson, @NonNull String undoLabel, @Nullable String group) {
        return onMain(() -> {
            JsonObject o;
            try {
                o = JsonParser.parseString(lookJson).getAsJsonObject();
            } catch (RuntimeException e) {
                return new ApplyResult(false, null, Collections.singletonList("That look is not readable JSON."));
            }
            List<String> warnings = LookValidator.fix(o, VisLab.get(app).types());
            VisScene s = new VisScene(o);
            int i = indexOf(s.id());
            if (i >= 0) {
                remember(group, undoLabel, i);
                looks.set(i, s);
            } else {
                remember(group, undoLabel);
                looks.add(s);
                i = looks.size() - 1;
            }
            show(i);
            return new ApplyResult(true, s.name(), warnings);
        });
    }

    public void setBrand(@Nullable String[] fiveHex) {
        setBrand(fiveHex, "Brand colours", null);
    }

    /** Brand lock: every look in these five colours (null = each look's own). An undo step. */
    public void setBrand(@Nullable String[] fiveHex, @NonNull String undoLabel, @Nullable String group) {
        onMain(() -> {
            String[] ok = null;
            if (fiveHex != null && fiveHex.length >= 5) {
                ok = new String[5];
                for (int i = 0; i < 5; i++) ok[i] = LookValidator.isColour(fiveHex[i]) ? fiveHex[i] : "#808080";
            }
            remember(group, undoLabel);
            brand = ok;
            if (room != null) room.onBrand(brand);
            return null;
        });
    }

    public boolean undo() {
        return onMain(() -> step(undo, redo));
    }

    public boolean redo() {
        return onMain(() -> step(redo, undo));
    }

    /** What the next undo would undo, for a hover label ("Undo: Height"); null when nothing. */
    @Nullable
    public String undoLabel() {
        return onMain(() -> undo.isEmpty() ? null : undo.get(undo.size() - 1).label);
    }

    // ------------------------------------------------------------------ room side (main thread)

    void bind(@Nullable Listener l) {
        room = l;
        if (l != null && !looks.isEmpty()) {
            l.onLook(looks.get(current), current);
            l.onBrand(brand);
        }
    }

    @NonNull
    List<VisScene> looks() {
        return looks;
    }

    int current() {
        return current;
    }

    /** The room picked a look (strip or swipe). Browsing is not an undo step. */
    void roomPicked(int i) {
        if (looks.isEmpty()) return;
        current = ((i % looks.size()) + looks.size()) % looks.size();
        prefs().edit().putString(PREF_LAST, looks.get(current).id()).apply();
    }

    /** The room is about to change the current look by hand: one gesture, one undo step. */
    void roomWillEdit(@NonNull String label) {
        if (looks.isEmpty()) return;
        remember(null, label, current);
    }

    void roomBrand(@Nullable String[] stops) {
        remember(null, "Brand colours");
        brand = stops;
    }

    // ------------------------------------------------------------------ internals

    /** Record the before-state of a change that is about to touch {@code touched} looks. */
    private void remember(@Nullable String group, @NonNull String label, int... touched) {
        Snap top = undo.isEmpty() ? null : undo.get(undo.size() - 1);
        Snap s;
        if (group != null && top != null && group.equals(top.group)) {
            s = top;   // same request: keep the oldest before-state, add what is newly touched
        } else {
            s = new Snap(group, label, current, brand, looks.size());
            undo.add(s);
            if (undo.size() > MAX_UNDO) undo.remove(0);
        }
        for (int i : touched) {
            if (i >= 0 && i < looks.size() && !s.looks.containsKey(i)) s.looks.put(i, looks.get(i).toJson());
        }
        redo.clear();
    }

    /** Undo or redo: restore {@code from}'s newest snapshot, pushing the state it replaces onto {@code to}. */
    private boolean step(@NonNull ArrayList<Snap> from, @NonNull ArrayList<Snap> to) {
        if (from.isEmpty()) return false;
        Snap s = from.remove(from.size() - 1);
        Snap back = new Snap(s.group, s.label, current, brand, looks.size());
        for (Integer i : s.looks.keySet()) {
            if (i < looks.size()) back.looks.put(i, looks.get(i).toJson());
        }
        // Looks that the restore drops (added after the snapshot) go into the opposite step,
        // so redo can bring them back.
        for (int i = s.count; i < looks.size(); i++) back.looks.put(i, looks.get(i).toJson());
        while (looks.size() > s.count) looks.remove(looks.size() - 1);
        for (Map.Entry<Integer, String> e : s.looks.entrySet()) {
            int i = e.getKey();
            VisScene restored = VisScene.parse(e.getValue());
            if (i < looks.size()) looks.set(i, restored);
            else if (i == looks.size()) looks.add(restored);
        }
        to.add(back);
        brand = s.brand;
        if (room != null) room.onBrand(brand);
        show(Math.max(0, Math.min(s.current, looks.size() - 1)));
        return true;
    }

    private void show(int i) {
        if (looks.isEmpty()) return;
        current = i;
        prefs().edit().putString(PREF_LAST, looks.get(i).id()).apply();
        if (room != null) room.onLook(looks.get(i), i);
    }

    private int indexOf(@NonNull String id) {
        for (int i = 0; i < looks.size(); i++) if (looks.get(i).id().equals(id)) return i;
        return -1;
    }

    @NonNull
    private SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Run on the main thread and return the result; from another thread, wait up to 3 s. */
    private static <T> T onMain(@NonNull Callable<T> work) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return work.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        FutureTask<T> task = new FutureTask<>(work);
        new Handler(Looper.getMainLooper()).post(task);
        try {
            return task.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("VisLab did not answer in time", e);
        }
    }
}
