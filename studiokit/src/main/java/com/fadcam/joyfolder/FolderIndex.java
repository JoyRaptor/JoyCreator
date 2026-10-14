package com.fadcam.joyfolder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything in the Joy Creator folder, by path, with a fingerprint for anything we have needed
 * to find again. Rebuildable at any time from a scan; it only exists so the scan is cheap.
 *
 * <p>The hidden {@code .joycreator} folder and anything whose name starts with a dot are not
 * indexed. A folder holding {@code plugin.json} or {@code mod.json} is indexed as one PLUGIN item
 * and its insides are left alone.</p>
 */
public final class FolderIndex {

    public static final class Item {
        public final String path;
        public final long size;
        public final long modified;
        /** Null until something needed it. */
        public String fingerprint;
        /** Where we made it: "recording", "export", "share-in", "mirror"… or null. */
        public String origin;
        public final boolean plugin;

        Item(String path, long size, long modified, boolean plugin) {
            this.path = path;
            this.size = size;
            this.modified = modified;
            this.plugin = plugin;
        }
    }

    private final Map<String, Item> byPath = new LinkedHashMap<>();

    public synchronized List<Item> items() {
        return new ArrayList<>(byPath.values());
    }

    public synchronized Item get(String path) {
        return byPath.get(path);
    }

    /** Record a file we just wrote, so its origin and fingerprint survive the next scan. */
    public synchronized void put(String path, long size, long modified, String fingerprint, String origin) {
        Item it = new Item(path, size, modified, false);
        it.fingerprint = fingerprint;
        it.origin = origin;
        byPath.put(path, it);
    }

    public synchronized void remove(String path) {
        byPath.remove(path);
    }

    /**
     * Walk the folder. Items whose size and date are unchanged keep their fingerprint and origin.
     * A file that moved keeps its origin when its old fingerprint finds it again later.
     */
    public void scan(JoyFs fs) throws IOException {
        Map<String, Item> fresh = new LinkedHashMap<>();
        walk(fs, "", fresh);
        synchronized (this) {
            for (Item it : fresh.values()) {
                Item old = byPath.get(it.path);
                if (old != null && old.size == it.size && old.modified == it.modified) {
                    it.fingerprint = old.fingerprint;
                    it.origin = old.origin;
                } else if (old != null) {
                    it.origin = old.origin;
                }
            }
            // Origins of vanished items carry over to an unclaimed new item with the same fingerprint later.
            for (Item old : byPath.values()) {
                if (!fresh.containsKey(old.path) && old.fingerprint != null) {
                    orphans.put(old.fingerprint, old.origin);
                }
            }
            byPath.clear();
            byPath.putAll(fresh);
        }
    }

    /** Fingerprint → origin of items that disappeared from where they were. */
    private final Map<String, String> orphans = new HashMap<>();

    private static void walk(JoyFs fs, String dir, Map<String, Item> out) throws IOException {
        List<JoyFs.Entry> kids = fs.list(dir);
        Collections.sort(kids, (a, b) -> a.name.compareToIgnoreCase(b.name));
        for (JoyFs.Entry e : kids) {
            if (e.name.startsWith(".")) continue;
            String rel = Placement.join(dir, e.name);
            if (e.dir) {
                if (isPluginDir(fs, rel)) out.put(rel, new Item(rel, 0, e.modified, true));
                else walk(fs, rel, out);
            } else if (!e.name.endsWith(JoyFolder.TMP_SUFFIX) && !e.name.endsWith(JoyFolder.OLD_SUFFIX)) {
                out.put(rel, new Item(rel, e.size, e.modified, false));
            }
        }
    }

    static boolean isPluginDir(JoyFs fs, String rel) throws IOException {
        return fs.stat(rel + "/plugin.json") != null || fs.stat(rel + "/mod.json") != null;
    }

    /**
     * Where the file with {@code fingerprint} is now. Tries {@code lastPath} first; then, only
     * among files of exactly the same size, computes fingerprints until one matches.
     *
     * @return the current path, or null when the file is gone from the folder
     */
    public String find(JoyFs fs, String lastPath, String fingerprint) throws IOException {
        long size = Fingerprint.sizeOf(fingerprint);
        if (lastPath != null) {
            Item at = get(lastPath);
            JoyFs.Entry e = at != null ? null : fs.stat(lastPath);
            long atSize = at != null ? at.size : e != null && !e.dir ? e.size : -1;
            if (atSize >= 0 && (fingerprint == null || size < 0 || atSize == size)) {
                if (fingerprint == null || fingerprint.equals(fingerprintOf(fs, lastPath))) return lastPath;
            }
        }
        if (fingerprint == null || size < 0) return null;
        for (Item it : items()) {
            if (it.plugin || it.size != size) continue;
            if (fingerprint.equals(fingerprintOf(fs, it.path))) {
                synchronized (this) {
                    String origin = orphans.remove(fingerprint);
                    if (it.origin == null) it.origin = origin;
                }
                return it.path;
            }
        }
        return null;
    }

    /** The fingerprint of a path, computing and remembering it if needed. */
    public String fingerprintOf(JoyFs fs, String path) throws IOException {
        Item it = get(path);
        if (it != null && it.fingerprint != null) return it.fingerprint;
        String fp = Fingerprint.of(fs, path);
        if (it != null) {
            synchronized (this) {
                it.fingerprint = fp;
            }
        }
        return fp;
    }

    public synchronized JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", 1);
        JsonArray arr = new JsonArray();
        for (Item it : byPath.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("path", it.path);
            j.addProperty("size", it.size);
            j.addProperty("modified", it.modified);
            if (it.fingerprint != null) j.addProperty("fp", it.fingerprint);
            if (it.origin != null) j.addProperty("origin", it.origin);
            if (it.plugin) j.addProperty("plugin", true);
            arr.add(j);
        }
        o.add("items", arr);
        return o;
    }

    public static FolderIndex fromJson(JsonObject o) {
        FolderIndex idx = new FolderIndex();
        if (o == null || !o.has("items")) return idx;
        for (JsonElement el : o.getAsJsonArray("items")) {
            JsonObject j = el.getAsJsonObject();
            Item it = new Item(j.get("path").getAsString(), j.get("size").getAsLong(),
                    j.get("modified").getAsLong(), j.has("plugin") && j.get("plugin").getAsBoolean());
            if (j.has("fp")) it.fingerprint = j.get("fp").getAsString();
            if (j.has("origin")) it.origin = j.get("origin").getAsString();
            idx.byPath.put(it.path, it);
        }
        return idx;
    }
}
