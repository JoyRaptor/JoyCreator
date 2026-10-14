package com.fadcam.joyfolder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * The user's Joy Creator folder: their work at the top, folders only when earned, and a hidden
 * {@code .joycreator} folder for the few things the app keeps there (Trash, folder roles, the index).
 *
 * <p>Everything a user would miss lives in this folder, so uninstalling the app loses nothing.
 * Caches the app can rebuild never come here: they would bloat the user's backups and sync.</p>
 */
public final class JoyFolder {

    public static final String HIDDEN = ".joycreator";
    public static final String TRASH = HIDDEN + "/Trash";
    static final String ROLES_FILE = HIDDEN + "/folders.json";
    static final String INDEX_FILE = HIDDEN + "/index.json";
    static final String TRASH_FILE = HIDDEN + "/trash.json";
    static final String TMP_SUFFIX = ".joytmp";
    static final String OLD_SUFFIX = ".joyold";
    /** Trash keeps things this long. */
    public static final long TRASH_DAYS = 30;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public final JoyFs fs;
    public final FolderRoles roles;
    public final FolderIndex index;

    private JoyFolder(JoyFs fs, FolderRoles roles, FolderIndex index) {
        this.fs = fs;
        this.roles = roles;
        this.index = index;
    }

    /** Open a folder, finishing any write a crash interrupted and adopting role folders by name. */
    public static JoyFolder open(JoyFs fs) throws IOException {
        fs.mkdirs(HIDDEN);
        recoverInterruptedWrites(fs, HIDDEN);
        recoverInterruptedWrites(fs, "");
        JoyFolder jf = new JoyFolder(fs,
                FolderRoles.fromJson(readJsonQuietly(fs, ROLES_FILE)),
                FolderIndex.fromJson(readJsonQuietly(fs, INDEX_FILE)));
        if (jf.roles.reconcile(fs)) jf.saveRoles();
        return jf;
    }

    // ── Atomic writes ────────────────────────────────────────────────────

    /**
     * Write a whole file so that a crash or power cut leaves either the old file or the new one,
     * never a half-written one. Sequence: write {@code name.joytmp}; rename the old file to
     * {@code name.joyold}; rename the temp into place; delete the old. {@link #open} finishes any
     * sequence a crash interrupted.
     */
    public synchronized void writeAtomic(String rel, byte[] data) throws IOException {
        String dir = Placement.parentOf(rel);
        String name = Placement.nameOf(rel);
        String tmp = Placement.join(dir, name + TMP_SUFFIX);
        String old = Placement.join(dir, name + OLD_SUFFIX);
        if (fs.stat(tmp) != null) fs.delete(tmp);
        try (OutputStream out = fs.create(tmp)) {
            out.write(data);
        }
        boolean hadOld = fs.stat(rel) != null;
        if (hadOld) {
            if (fs.stat(old) != null) fs.delete(old);
            if (!fs.rename(rel, name + OLD_SUFFIX)) throw new IOException("Could not set aside " + rel);
        }
        if (!fs.rename(tmp, name)) {
            if (hadOld) fs.rename(old, name);
            throw new IOException("Could not move " + tmp + " into place");
        }
        if (hadOld) fs.delete(old);
    }

    static void recoverInterruptedWrites(JoyFs fs, String dir) throws IOException {
        Set<String> names = new HashSet<>();
        for (JoyFs.Entry e : fs.list(dir)) names.add(e.name);
        for (String n : names) {
            if (n.endsWith(OLD_SUFFIX)) {
                String target = n.substring(0, n.length() - OLD_SUFFIX.length());
                String rel = Placement.join(dir, n);
                if (names.contains(target)) {
                    fs.delete(rel);                                      // the new file landed
                } else if (names.contains(target + TMP_SUFFIX)) {
                    fs.rename(Placement.join(dir, target + TMP_SUFFIX), target);   // the temp was complete
                    fs.delete(rel);
                } else {
                    fs.rename(rel, target);                              // put the old one back
                }
            }
        }
        for (JoyFs.Entry e : fs.list(dir)) {
            if (e.name.endsWith(TMP_SUFFIX)) fs.delete(Placement.join(dir, e.name));   // unfinished or unneeded
        }
    }

    public byte[] readAll(String rel) throws IOException {
        try (InputStream in = fs.open(rel)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static JsonObject readJsonQuietly(JoyFs fs, String rel) {
        try {
            if (fs.stat(rel) == null) return null;
            try (InputStream in = fs.open(rel)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                JsonElement el = JsonParser.parseString(new String(out.toByteArray(), StandardCharsets.UTF_8));
                return el.isJsonObject() ? el.getAsJsonObject() : null;
            }
        } catch (Exception e) {
            return null;   // the app's own bookkeeping; a damaged file is rebuilt, never fatal
        }
    }

    private void writeJson(String rel, JsonObject o) throws IOException {
        writeAtomic(rel, GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
    }

    public void saveRoles() throws IOException {
        writeJson(ROLES_FILE, roles.toJson());
    }

    public void saveIndex() throws IOException {
        writeJson(INDEX_FILE, index.toJson());
    }

    // ── Placing new files ────────────────────────────────────────────────

    /**
     * A free name for {@code desired} inside {@code dir}: "photo.jpg", else "photo 2.jpg"…
     */
    public String freeName(String dir, String desired) throws IOException {
        Set<String> taken = new HashSet<>();
        for (JoyFs.Entry e : fs.list(dir)) taken.add(e.name.toLowerCase(Locale.ROOT));
        return ReadableNames.unique(taken, desired);
    }

    /**
     * Decide where a new file goes and make sure that folder exists, recording any role folder
     * this creates. Returns the folder; pair it with {@link #freeName}.
     */
    public synchronized String prepareDir(Placement.What what, String workRel, String browsingDir,
                                          FileKind supplyKind) throws IOException {
        Placement.Result r = Placement.dirFor(what, workRel, browsingDir, supplyKind, roles);
        if (!r.dir.isEmpty()) fs.mkdirs(r.dir);
        if (r.createsRole != null && roles.get(r.createsRole) == null) {
            String top = r.dir.contains("/") ? r.dir.substring(0, r.dir.indexOf('/')) : r.dir;
            roles.set(r.createsRole, top);
            saveRoles();
        }
        return r.dir;
    }

    /** Copy a stream into the folder as a new file, atomically, and index it. */
    public synchronized String addFile(String dir, String desiredName, InputStream in, String origin)
            throws IOException {
        String name = freeName(dir, desiredName);
        String rel = Placement.join(dir, name);
        String tmp = rel + TMP_SUFFIX;
        try (OutputStream out = fs.create(tmp)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (!fs.rename(tmp, name)) {
            fs.delete(tmp);
            throw new IOException("Could not save " + rel);
        }
        JoyFs.Entry e = fs.stat(rel);
        index.put(rel, e.size, e.modified, Fingerprint.of(fs, rel), origin);
        return rel;
    }

    // ── Trash ────────────────────────────────────────────────────────────

    /** Move a file or folder to Trash. Returns its name in Trash. */
    public synchronized String trash(String rel, long now) throws IOException {
        fs.mkdirs(TRASH);
        String stamped = ReadableNames.stamp(now, TimeZone.getDefault()) + " " + Placement.nameOf(rel);
        String trashName = freeName(TRASH, stamped);
        String dir = Placement.parentOf(rel);
        String name = Placement.nameOf(rel);
        // Rename in place first so the move into Trash can never collide with another item's name.
        if (!trashName.equals(name) && !fs.rename(rel, trashName)) throw new IOException("Could not trash " + rel);
        if (!fs.move(Placement.join(dir, trashName), TRASH)) {
            fs.rename(Placement.join(dir, trashName), name);
            throw new IOException("Could not trash " + rel);
        }
        JsonObject log = trashLog();
        JsonObject entry = new JsonObject();
        entry.addProperty("name", trashName);
        entry.addProperty("from", rel);
        entry.addProperty("at", now);
        log.getAsJsonArray("items").add(entry);
        writeJson(TRASH_FILE, log);
        index.remove(rel);
        return trashName;
    }

    /** Put a Trash item back where it was (or beside it, if that name is taken now). Returns its path. */
    public synchronized String restore(String trashName) throws IOException {
        JsonObject log = trashLog();
        JsonArray items = log.getAsJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            JsonObject e = items.get(i).getAsJsonObject();
            if (!trashName.equals(e.get("name").getAsString())) continue;
            String from = e.get("from").getAsString();
            String dir = Placement.parentOf(from);
            String name = freeName(dir, Placement.nameOf(from));
            fs.mkdirs(dir);
            if (!fs.move(Placement.join(TRASH, trashName), dir)) throw new IOException("Could not restore " + trashName);
            if (!fs.rename(Placement.join(dir, trashName), name)) throw new IOException("Could not rename " + trashName);
            items.remove(i);
            writeJson(TRASH_FILE, log);
            return Placement.join(dir, name);
        }
        throw new IOException("Not in Trash: " + trashName);
    }

    /** Names in Trash, oldest first. */
    public synchronized List<String> trashed() {
        List<String> out = new ArrayList<>();
        for (TrashItem t : trashItems()) out.add(t.name);
        return out;
    }

    /** One thing in Trash: its name there, where it came from, and when it was trashed. */
    public static final class TrashItem {
        public final String name;
        public final String from;
        public final long at;

        TrashItem(String name, String from, long at) {
            this.name = name;
            this.from = from;
            this.at = at;
        }

        /** The name it had before it was trashed, e.g. "Lecture 3.joyproject". */
        public String originalName() {
            return Placement.nameOf(from);
        }
    }

    /** Everything in Trash, newest first, for the Trash screen. */
    public synchronized List<TrashItem> trashItems() {
        List<TrashItem> out = new ArrayList<>();
        for (JsonElement el : trashLog().getAsJsonArray("items")) {
            JsonObject e = el.getAsJsonObject();
            out.add(0, new TrashItem(e.get("name").getAsString(), e.get("from").getAsString(), e.get("at").getAsLong()));
        }
        return out;
    }

    /** Remove one Trash item for good. False when it isn't in Trash. */
    public synchronized boolean deleteFromTrash(String trashName) throws IOException {
        JsonObject log = trashLog();
        JsonArray items = log.getAsJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            if (!trashName.equals(items.get(i).getAsJsonObject().get("name").getAsString())) continue;
            fs.delete(Placement.join(TRASH, trashName));
            items.remove(i);
            writeJson(TRASH_FILE, log);
            return true;
        }
        return false;
    }

    /** Size in bytes of a Trash item (a folder counts as 0), or -1 when it is gone. */
    public long trashSize(String trashName) throws IOException {
        JoyFs.Entry e = fs.stat(Placement.join(TRASH, trashName));
        return e == null ? -1 : e.size;
    }

    /** Remove everything in Trash for good. Returns how many items went. */
    public synchronized int emptyTrash() throws IOException {
        JsonObject log = trashLog();
        int n = 0;
        for (JsonElement el : log.getAsJsonArray("items")) {
            fs.delete(Placement.join(TRASH, el.getAsJsonObject().get("name").getAsString()));
            n++;
        }
        log.add("items", new JsonArray());
        writeJson(TRASH_FILE, log);
        return n;
    }

    /** Remove for good whatever has been in Trash longer than {@link #TRASH_DAYS}. */
    public synchronized int emptyOld(long now) throws IOException {
        return emptyOlderThan(now, TRASH_DAYS * 24L * 60 * 60 * 1000);
    }

    /** Remove for good whatever has been in Trash longer than {@code maxAgeMillis} (the user's Trash setting). */
    public synchronized int emptyOlderThan(long now, long maxAgeMillis) throws IOException {
        JsonObject log = trashLog();
        JsonArray items = log.getAsJsonArray("items");
        JsonArray keep = new JsonArray();
        int removed = 0;
        for (JsonElement el : items) {
            JsonObject e = el.getAsJsonObject();
            long at = e.get("at").getAsLong();
            if (now - at > maxAgeMillis) {
                fs.delete(Placement.join(TRASH, e.get("name").getAsString()));
                removed++;
            } else {
                keep.add(e);
            }
        }
        if (removed > 0) {
            log.add("items", keep);
            writeJson(TRASH_FILE, log);
        }
        return removed;
    }

    private JsonObject trashLog() {
        JsonObject o = readJsonQuietly(fs, TRASH_FILE);
        if (o == null || !o.has("items") || !o.get("items").isJsonArray()) {
            o = new JsonObject();
            o.add("items", new JsonArray());
        }
        return o;
    }
}
