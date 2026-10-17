package com.fadcam.vislab.room;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.vislab.VisLab;
import com.fadcam.vislab.gl.ShaderLib;
import com.fadcam.vislab.model.LookValidator;
import com.fadcam.vislab.model.VisScene;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p><b>Kept.</b> Every look is a plain {@code <Name>.vis.json} in the Joy Creator folder's
 * {@code Supplies/Looks/} (the owner's supplies ruling, 10-16): the built-in looks are copied out
 * on first use; from then on the person's copy is the one used and changed, and the app's original
 * stays as the factory version for "Revert to default". A copy nobody touched follows app
 * updates. Changes are written a moment after they happen (and at once when the room pauses);
 * a look an undo removes goes to the folder's Trash. Nothing is lost to a forgotten save.</p>
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

        /** The list of looks changed (added, removed, renamed, or read from the folder). */
        void onLooks();

        /** Changes reached the folder (a new look now has a file to name). */
        void onKept();

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

    private static final String TAG = "VisLabSession";
    private static final String PREFS = "vislab";
    private static final String PREF_LAST = "last_look";
    private static final String PREF_BRAND = "brand";
    /** + look id: a fingerprint of the factory text last copied out (the "untouched copy" check). */
    private static final String PREF_FACTORY = "factory_";
    private static final long SYNC_DELAY_MS = 800;
    private static final int MAX_UNDO = 60;
    private static volatile VisLabSession instance;

    private final Context app;
    private final List<VisScene> looks = new ArrayList<>();
    private int current;
    @Nullable private String[] brand;
    @Nullable private Listener room;
    private final ArrayList<Snap> undo = new ArrayList<>(), redo = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Built-in id -> its factory text, exactly as a file holds it. */
    private final Map<String, String> factory = new HashMap<>();
    /** Look id -> the text sent to its file (the main thread's view of what the file holds). */
    private final Map<String, String> sent = new HashMap<>();
    /** Look id -> the name it was last written under (a rename moves the file along). */
    private final Map<String, String> sentName = new HashMap<>();
    /** Look id -> its file. Changed on the folder thread only. */
    private final Map<String, String> files = new ConcurrentHashMap<>();
    @Nullable private volatile LookFiles.Home home;
    private boolean loaded;
    private final Runnable syncSoon = this::sync;

    private VisLabSession(@NonNull Context c) {
        app = c.getApplicationContext();
        for (VisScene s : VisLab.get(app).builtInLooks()) {
            looks.add(s.copy());
            factory.put(s.id(), fileText(s));
        }
        String last = prefs().getString(PREF_LAST, null);
        for (int i = 0; i < looks.size(); i++) if (looks.get(i).id().equals(last)) current = i;
        String b = prefs().getString(PREF_BRAND, null);
        if (b != null) {
            String[] stops = b.split(",");
            if (stops.length == 5) brand = stops;
        }
        load();
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

    /** True for a look that ships with the app (it has a factory version to go back to). */
    public boolean isBuiltIn(@NonNull String id) {
        return onMain(() -> factory.containsKey(id));
    }

    /** True for a built-in look that has been changed from its factory version. */
    public boolean isCustomised(@NonNull String id) {
        return onMain(() -> {
            int i = indexOf(id);
            String f = factory.get(id);
            return i >= 0 && f != null && !f.equals(fileText(looks.get(i)));
        });
    }

    /**
     * Where the look is kept, as the person would find it: "Supplies/Looks/Sound City.vis.json"
     * inside the Joy Creator folder. Null until it has been written (a moment after a change).
     */
    @Nullable
    public String lookFile(@NonNull String id) {
        return files.get(id);
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
            changedList();
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
            keepBrand();
            if (room != null) room.onBrand(brand);
            return null;
        });
    }

    /**
     * Put the factory version of built-in look {@code id} back, replacing every change made to it.
     * An undo step, so the way back has a way back.
     */
    @NonNull
    public ApplyResult revertToDefault(@NonNull String id, @NonNull String undoLabel, @Nullable String group) {
        return onMain(() -> {
            int i = indexOf(id);
            VisScene f = VisLab.get(app).builtIn(id);
            if (i < 0 || f == null) return fail("\"" + id + "\" has no factory version: it was made here.");
            remember(group, undoLabel, i);
            looks.set(i, f.copy());
            changedList();
            show(i);
            return new ApplyResult(true, f.name(), new ArrayList<>());
        });
    }

    /** Copy the look on screen under a new name, and show the copy. An undo step. */
    @NonNull
    public ApplyResult duplicate(@NonNull String newName, @NonNull String undoLabel, @Nullable String group) {
        return onMain(() -> {
            if (looks.isEmpty()) return fail("There is no look to copy.");
            VisScene c = looks.get(current).copy();
            String name = newName.trim().isEmpty() ? looks.get(current).name() + " copy" : newName.trim();
            c.json().addProperty("id", freeId(name));
            c.json().addProperty("name", name);
            remember(group, undoLabel);
            looks.add(c);
            changedList();
            show(looks.size() - 1);
            return new ApplyResult(true, name, new ArrayList<>());
        });
    }

    /** Give look {@code id} a new name (its file follows). An undo step. */
    @NonNull
    public ApplyResult rename(@NonNull String id, @NonNull String newName, @NonNull String undoLabel, @Nullable String group) {
        return onMain(() -> {
            int i = indexOf(id);
            String name = newName.trim();
            if (i < 0) return fail("There is no look called \"" + id + "\".");
            if (name.isEmpty()) return fail("A look needs a name.");
            remember(group, undoLabel, i);
            VisScene s = looks.get(i).copy();
            s.json().addProperty("name", name);
            looks.set(i, s);
            changedList();
            show(i);
            return new ApplyResult(true, name, new ArrayList<>());
        });
    }

    /**
     * Remove a look the person made; its file goes to the folder's Trash. Built-in looks can only
     * be reverted, never removed. An undo step: undo brings it back.
     */
    @NonNull
    public ApplyResult deleteLook(@NonNull String id, @NonNull String undoLabel, @Nullable String group) {
        return onMain(() -> {
            int i = indexOf(id);
            if (i < 0) return fail("There is no look called \"" + id + "\".");
            if (factory.containsKey(id)) return fail("Built-in looks can be reverted to default, not deleted.");
            if (looks.size() <= 1) return fail("The last look stays.");
            // Snapshot every look from i on: removing shifts them, and undo puts them all back.
            int[] touched = new int[looks.size() - i];
            for (int k = 0; k < touched.length; k++) touched[k] = i + k;
            remember(group, undoLabel, touched);
            String name = looks.get(i).name();
            looks.remove(i);
            changedList();
            show(Math.min(i, looks.size() - 1));
            return new ApplyResult(true, name, new ArrayList<>());
        });
    }

    /** Write any pending change now (the room is pausing; the app may be closed). */
    public void flush() {
        onMain(() -> {
            main.removeCallbacks(syncSoon);
            sync();
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
        keepBrand();
    }

    /** The room changed the current look's tree in place: keep it. */
    void roomEdited() {
        changed();
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
        keepBrand();
        changedList();
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

    @NonNull
    private static ApplyResult fail(@NonNull String why) {
        return new ApplyResult(false, null, Collections.singletonList(why));
    }

    // ------------------------------------------------------------------ keeping (the folder)

    /** A look as its file holds it: pretty JSON, so it reads and hand-edits well. */
    @NonNull
    static String fileText(@NonNull VisScene s) {
        return s.toJson() + "\n";
    }

    /** Something about the looks changed: write it soon. */
    private void changed() {
        main.removeCallbacks(syncSoon);
        main.postDelayed(syncSoon, SYNC_DELAY_MS);
    }

    /** Looks were added, removed or renamed: tell the room, and write soon. */
    private void changedList() {
        changed();
        // A removal (delete, or undoing an add) can leave the position one past the end; the room
        // reads the current look as soon as it hears.
        current = Math.max(0, Math.min(current, looks.size() - 1));
        if (room != null) room.onLooks();
    }

    private void keepBrand() {
        prefs().edit().putString(PREF_BRAND, brand == null ? null : String.join(",", brand)).apply();
    }

    /** An id for a new look from its name: "Calm Ocean" -> "calm-ocean", then "calm-ocean-2"... */
    @NonNull
    private String freeId(@NonNull String name) {
        String base = slug(name);
        String id = base;
        for (int n = 2; indexOf(id) >= 0; n++) id = base + "-" + n;
        return id;
    }

    @NonNull
    private static String slug(@NonNull String s) {
        String b = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return b.isEmpty() ? "look" : b;
    }

    /** One look to write, as the main thread saw it when it asked. */
    private static final class Job {
        final String id, name, text;
        @Nullable final String oldName, factoryText;

        Job(String id, String name, @Nullable String oldName, String text, @Nullable String factoryText) {
            this.id = id;
            this.name = name;
            this.oldName = oldName;
            this.text = text;
            this.factoryText = factoryText;
        }
    }

    /** Send every look whose text differs from its file to be written; trash files of looks gone. */
    private void sync() {
        if (!loaded) return;   // the read of the folder syncs when it is done
        List<Job> jobs = new ArrayList<>();
        Set<String> alive = new HashSet<>();
        for (VisScene s : looks) {
            String id = s.id();
            if (!alive.add(id)) continue;
            String text = fileText(s);
            if (text.equals(sent.get(id))) continue;
            jobs.add(new Job(id, s.name(), sentName.get(id), text, factory.get(id)));
            sent.put(id, text);
            sentName.put(id, s.name());
        }
        List<String> gone = new ArrayList<>();
        for (String id : new ArrayList<>(sent.keySet())) {
            if (alive.contains(id)) continue;
            gone.add(id);
            sent.remove(id);
            sentName.remove(id);
        }
        if (jobs.isEmpty() && gone.isEmpty()) return;
        JoyFolderHome.io().execute(() -> write(jobs, gone));
    }

    /** Folder thread: do the writes {@link #sync} asked for, in order. */
    private void write(@NonNull List<Job> jobs, @NonNull List<String> gone) {
        LookFiles.Home h = home;
        if (h == null) return;
        SharedPreferences.Editor ed = prefs().edit();
        for (String id : gone) {
            String rel = files.remove(id);
            if (rel != null) LookFiles.trash(h, rel);
        }
        for (Job j : jobs) {
            String rel = files.get(j.id);
            try {
                if (rel != null && j.oldName != null && !j.oldName.equals(j.name)) {
                    rel = LookFiles.follow(h, rel, j.oldName, j.name);
                }
                files.put(j.id, LookFiles.write(h, rel, j.name, j.text));
                // A copy that is exactly the factory text is untouched: it may follow app updates.
                if (j.factoryText != null && j.factoryText.equals(j.text)) ed.putString(PREF_FACTORY + j.id, fingerprint(j.text));
                else ed.remove(PREF_FACTORY + j.id);
            } catch (IOException | RuntimeException e) {
                FLog.w(TAG, "Couldn't keep look " + j.id, e);
            }
        }
        ed.apply();
        LookFiles.saveIndex(h);
        main.post(() -> {
            if (room != null) room.onKept();
        });
    }

    /**
     * Read the person's looks (folder thread), once. A built-in look with a file uses the file
     * (the person's copy wins) unless that file is an untouched copy of an older factory version,
     * which is refreshed. A built-in with no file is copied out. Any other file is one of the
     * person's own looks. Then the main thread takes it all in.
     */
    private void load() {
        final Map<String, String> factoryCopy = new LinkedHashMap<>(factory);
        final Map<String, String> names = new HashMap<>();
        for (VisScene s : looks) names.put(s.id(), s.name());
        JoyFolderHome.io().execute(() -> {
            LookFiles.Home h = LookFiles.home(app);
            home = h;
            SharedPreferences p = prefs();
            SharedPreferences.Editor ed = p.edit();
            Map<String, VisScene> mine = new LinkedHashMap<>();   // id -> look read from a file
            Map<String, String> text = new HashMap<>();            // id -> that file's text
            for (LookFiles.Found f : LookFiles.readAll(h)) {
                try {
                    JsonObject o = JsonParser.parseString(f.text).getAsJsonObject();
                    if (!o.has("id") || !o.get("id").isJsonPrimitive()) {
                        String file = f.rel.substring(f.rel.lastIndexOf('/') + 1);
                        o.addProperty("id", slug(file.substring(0, file.length() - LookFiles.ENDING.length())));
                    }
                    List<String> notes = LookValidator.fix(o, VisLab.get(app).types());
                    if (!notes.isEmpty()) FLog.w(TAG, f.rel + ": " + String.join("; ", notes));
                    String id = o.get("id").getAsString();
                    String base = id;
                    for (int n = 2; mine.containsKey(id); n++) id = base + "-" + n;   // a copied file
                    o.addProperty("id", id);
                    mine.put(id, new VisScene(o));
                    text.put(id, f.text);
                    files.put(id, f.rel);
                } catch (RuntimeException e) {
                    FLog.w(TAG, "Skipped " + f.rel + ": not a readable look (" + e.getMessage() + ")");
                }
            }
            for (Map.Entry<String, String> e : factoryCopy.entrySet()) {
                String id = e.getKey(), now = e.getValue();
                String had = text.get(id);
                String copied = p.getString(PREF_FACTORY + id, null);
                boolean untouchedOld = had != null && copied != null && copied.equals(fingerprint(had))
                        && !copied.equals(fingerprint(now));
                if (had != null && !untouchedOld) continue;   // the person's copy wins
                try {
                    String nm = names.get(id);
                    files.put(id, LookFiles.write(h, files.get(id), nm != null ? nm : id, now));
                    ed.putString(PREF_FACTORY + id, fingerprint(now));
                    mine.remove(id);
                    text.remove(id);
                } catch (IOException | RuntimeException ex) {
                    FLog.w(TAG, "Couldn't copy out " + id, ex);
                }
            }
            ed.apply();
            LookFiles.saveIndex(h);
            main.post(() -> takeIn(mine, text));
        });
    }

    /** Main thread: the folder's looks replace and join the built-ins. */
    private void takeIn(@NonNull Map<String, VisScene> mine, @NonNull Map<String, String> text) {
        String showing = looks.isEmpty() ? null : looks.get(current).id();
        List<VisScene> madeHere = new ArrayList<>();
        for (int i = 0; i < looks.size(); i++) {
            VisScene s = looks.get(i);
            String f = factory.get(s.id());
            if (f == null) {
                madeHere.add(s);   // added (by Joybot) before the folder was read
                continue;
            }
            VisScene fromFile = mine.remove(s.id());
            // A look changed here in the moment before the read keeps that change.
            if (fromFile != null && f.equals(fileText(s))) looks.set(i, fromFile);
        }
        // Built-ins first (in their order), then the person's own looks, then anything made here.
        looks.removeAll(madeHere);
        looks.addAll(mine.values());
        for (VisScene s : madeHere) if (indexOf(s.id()) < 0) looks.add(s);
        // What the files hold now, so sync writes only what differs.
        for (VisScene s : looks) {
            String id = s.id();
            String t = text.containsKey(id) ? text.get(id) : factory.get(id);
            if (t != null && files.containsKey(id)) {
                sent.put(id, t);
                sentName.put(id, s.name());
            }
        }
        // Undo steps from before the read point at list positions that moved: start clean.
        undo.clear();
        redo.clear();
        loaded = true;
        // The last look picked wins (it may be one of the person's own, only known now).
        String last = prefs().getString(PREF_LAST, null);
        int i = last == null ? -1 : indexOf(last);
        if (i < 0 && showing != null) i = indexOf(showing);
        current = Math.max(0, Math.min(i < 0 ? current : i, looks.size() - 1));
        if (room != null) {
            room.onLooks();
            if (!looks.isEmpty()) room.onLook(looks.get(current), current);
        }
        sync();
    }

    /** A short fingerprint of a file's text (FNV-1a, 64 bit). */
    @NonNull
    static String fingerprint(@NonNull String t) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < t.length(); i++) {
            h ^= t.charAt(i);
            h *= 0x100000001b3L;
        }
        return Long.toHexString(h);
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
