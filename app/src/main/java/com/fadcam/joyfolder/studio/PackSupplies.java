package com.fadcam.joyfolder.studio;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.fadcam.FLog;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.JoyFs;
import com.fadcam.joyfolder.PackShelf;
import com.fadcam.joyfolder.android.JoyFolderHome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Studio's share of the installed Joy packs (job PACK-1): their fonts ({@code font}), colour grades ({@code grade},
 * a {@code .cube}) and caption styles ({@code caption-style}, a {@code .caption.json}).
 *
 * <p>Studio draws and exports from plain files it can open by path (and its export runs in a process of its own),
 * so each of these is copied from {@code Supplies/Packs/<pack>/} into the app's {@code files/packs/<pack>/}, and a
 * small list of them, {@code files/packs/shelf.json}, says what each is and where it came from. The pickers read that
 * list; nothing is read from the Joy Creator folder while drawing.</p>
 *
 * <p>A pack that is removed leaves its copies in place, marked gone: a project that uses its font or grade keeps
 * looking the same, but the pickers no longer offer them. Nothing here is the person's own work, so nothing is ever
 * written into their folder.</p>
 */
public final class PackSupplies {

    private static final String TAG = "PackSupplies";
    public static final String FONT = "font", GRADE = "grade", CAPTION = "caption-style";
    private static final Set<String> SOCKETS = new HashSet<>(Arrays.asList(FONT, GRADE, CAPTION));
    private static final String SHELF = "shelf.json";

    /** One thing from a pack, as Studio uses it. */
    public static final class Item {
        public final String socket;
        /** Stable across runs and phones: the pack's id and the file's place in it. */
        public final String id;
        /** What the person sees: the file's name without its ending. */
        public final String label;
        /** The pack's name. */
        public final String pack;
        /** The app's own copy. */
        public final File file;
        /** The pack has been removed: kept so work that uses it still draws, no longer offered. */
        public final boolean gone;

        Item(String socket, String id, String label, String pack, File file, boolean gone) {
            this.socket = socket;
            this.id = id;
            this.label = label;
            this.pack = pack;
            this.file = file;
            this.gone = gone;
        }
    }

    private static final Object LOCK = new Object();
    @Nullable private static List<Item> cache;
    private static long cacheStamp = -1;
    private static volatile boolean syncedThisRun;

    private PackSupplies() {}

    /** The app's copies of pack supplies: {@code <files>/packs}. */
    @NonNull
    public static File root(@NonNull File filesDir) {
        return new File(filesDir, "packs");
    }

    /** Copy what the packs hold, once per run, a moment after the app opens. Any thread. */
    public static void syncOnce(@NonNull Context c) {
        if (syncedThisRun) return;
        syncedThisRun = true;
        syncSoon(c);
    }

    /** Copy what the packs hold now (after a pack was installed). Any thread; the work runs on the folder's. */
    public static void syncSoon(@NonNull Context c) {
        Context app = c.getApplicationContext();
        if (!JoyFolderHome.isChosen(app)) return;
        JoyFolderHome.io().execute(() -> sync(app));
    }

    /**
     * Folder thread. Copy every font, grade and caption style of the installed packs into the app, and write the
     * list. Never throws. When the folder is out of reach the list is left as it was.
     *
     * @return how many files were copied (new or changed)
     */
    @WorkerThread
    public static int sync(@NonNull Context c) {
        try {
            JoyFolder jf = JoyFolderHome.open(c);
            if (jf == null) return 0;
            return sync(jf.fs, c.getFilesDir());
        } catch (Throwable t) {
            FLog.w(TAG, "Pack fonts, grades and caption styles not copied this time", t);
            return 0;
        }
    }

    /** The copy and the list, over any folder (tested on a plain folder). */
    @WorkerThread
    static int sync(@NonNull JoyFs fs, @NonNull File filesDir) throws IOException {
        File root = root(filesDir);
        List<Item> before = read(filesDir);
        List<Item> now = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int copied = 0;
        for (PackShelf.Found f : PackShelf.installed(fs, SOCKETS)) {
            String id = f.entry.socket + ":" + f.pack.id + "/" + f.entry.file;
            if (!ids.add(id)) continue;
            File local = new File(new File(root, safe(f.pack.id)), f.entry.file.replace('\\', '/'));
            try {
                JoyFs.Entry src = fs.stat(f.rel);
                if (src == null) continue;
                if (!local.isFile() || local.length() != src.size || local.lastModified() < src.modified) {
                    copy(fs, f.rel, local);
                    copied++;
                }
                now.add(new Item(f.entry.socket, id, f.label(), f.pack.name, local, false));
            } catch (IOException | RuntimeException e) {
                FLog.w(TAG, "Couldn't copy " + f.rel, e);
            }
        }
        // A removed pack: its copies stay (work that uses them keeps its look), no longer offered.
        for (Item b : before) {
            if (ids.contains(b.id) || !b.file.isFile()) continue;
            ids.add(b.id);
            now.add(new Item(b.socket, b.id, b.label, b.pack, b.file, true));
        }
        write(filesDir, now);
        if (copied > 0) com.fadcam.ui.faditor.text.FontLibrary.invalidateTypefaces();
        return copied;
    }

    /** Everything Studio has from packs for one socket, offered ones only (removed packs left out). Any thread. */
    @NonNull
    public static List<Item> offered(@NonNull File filesDir, @NonNull String socket) {
        List<Item> out = new ArrayList<>();
        for (Item i : read(filesDir)) if (!i.gone && i.socket.equals(socket)) out.add(i);
        return out;
    }

    /** Everything on the list, offered and gone. The same list object until the list changes. Any thread. */
    @NonNull
    public static List<Item> all(@NonNull File filesDir) {
        return read(filesDir);
    }

    /** An item by its id, offered or gone (a project that uses it still finds it), or null. Any thread. */
    @Nullable
    public static Item find(@NonNull File filesDir, @NonNull String id) {
        for (Item i : read(filesDir)) if (i.id.equals(id)) return i;
        return null;
    }

    @NonNull
    private static List<Item> read(@NonNull File filesDir) {
        File f = new File(root(filesDir), SHELF);
        synchronized (LOCK) {
            long stamp = f.isFile() ? f.lastModified() ^ f.length() : 0;
            if (cache != null && stamp == cacheStamp) return cache;
            List<Item> out = new ArrayList<>();
            if (f.isFile()) {
                try (InputStream in = new java.io.FileInputStream(f)) {
                    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                    JSONArray a = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8)).optJSONArray("items");
                    for (int i = 0; a != null && i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o == null) continue;
                        out.add(new Item(o.optString("socket"), o.optString("id"), o.optString("label"),
                                o.optString("pack"), new File(o.optString("file")), o.optBoolean("gone", false)));
                    }
                } catch (Exception e) {
                    FLog.w(TAG, "Pack supplies list not read", e);
                }
            }
            cache = java.util.Collections.unmodifiableList(out);
            cacheStamp = stamp;
            return cache;
        }
    }

    private static void write(@NonNull File filesDir, @NonNull List<Item> items) throws IOException {
        JSONArray a = new JSONArray();
        try {
            for (Item i : items) {
                JSONObject o = new JSONObject();
                o.put("socket", i.socket);
                o.put("id", i.id);
                o.put("label", i.label);
                o.put("pack", i.pack);
                o.put("file", i.file.getAbsolutePath());
                if (i.gone) o.put("gone", true);
                a.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("items", a);
            File dir = root(filesDir);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
            File tmp = new File(dir, SHELF + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString(1).getBytes(StandardCharsets.UTF_8));
            }
            File to = new File(dir, SHELF);
            if (!tmp.renameTo(to)) {
                //noinspection ResultOfMethodCallIgnored
                to.delete();
                if (!tmp.renameTo(to)) throw new IOException("Cannot save " + to);
            }
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
        synchronized (LOCK) {
            cache = null;
        }
    }

    private static void copy(@NonNull JoyFs fs, @NonNull String rel, @NonNull File to) throws IOException {
        File dir = to.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File tmp = new File(to.getPath() + ".tmp");
        try (InputStream in = fs.open(rel); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        //noinspection ResultOfMethodCallIgnored
        to.delete();
        if (!tmp.renameTo(to)) throw new IOException("Cannot save " + to);
    }

    /** A pack id as one folder name. */
    @NonNull
    static String safe(@NonNull String id) {
        String s = id.replaceAll("[^A-Za-z0-9._-]+", "_");
        return s.isEmpty() || s.startsWith(".") ? "_" + s : s;
    }
}
