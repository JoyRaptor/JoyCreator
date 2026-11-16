package com.fadcam.joyfolder.studio;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.fadcam.FLog;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.JoyFs;
import com.fadcam.joyfolder.PackShelf;
import com.fadcam.joyfolder.android.JoyFolderHome;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Copies the installed packs' fonts, grades and caption styles into the app and writes the list
 * {@link PackSupplies} reads (job PACK-1). Runs once a run after the app opens and right after a pack is installed.
 * Nothing is ever written into the person's folder.
 */
public final class PackSuppliesSync {

    private static final String TAG = "PackSupplies";
    private static final Set<String> SOCKETS = new HashSet<>(Arrays.asList(
            PackSupplies.FONT, PackSupplies.GRADE, PackSupplies.CAPTION));
    private static volatile boolean syncedThisRun;

    private PackSuppliesSync() {}

    /** Copy what the packs hold, once per run. Any thread; the work runs on the folder's. */
    public static void syncOnce(@NonNull Context c) {
        if (syncedThisRun) return;
        syncedThisRun = true;
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
            int copied = sync(jf.fs, c.getFilesDir());
            if (copied > 0) com.fadcam.ui.faditor.text.FontLibrary.invalidateTypefaces();
            return copied;
        } catch (Throwable t) {
            FLog.w(TAG, "Pack fonts, grades and caption styles not copied this time", t);
            return 0;
        }
    }

    /** The copy and the list, over any folder. */
    @WorkerThread
    static int sync(@NonNull JoyFs fs, @NonNull File filesDir) throws IOException {
        File root = PackSupplies.root(filesDir);
        List<PackSupplies.Item> before = PackSupplies.all(filesDir);
        List<PackSupplies.Item> now = new ArrayList<>();
        // seen: in an installed pack now; listed: already in the new list. R2-PACK-03: an entry that could not be
        // read or copied this time is seen but not listed, so its last good copy is kept below, still offered.
        Set<String> seen = new HashSet<>(), listed = new HashSet<>();
        int copied = 0;
        for (PackShelf.Found f : PackShelf.installed(fs, SOCKETS)) {
            String id = f.entry.socket + ":" + f.pack.id + "/" + f.entry.file;
            if (!seen.add(id)) continue;
            File local = new File(new File(root, folderOf(f.pack.id)), f.entry.file.replace('\\', '/'));
            try {
                JoyFs.Entry src = fs.stat(f.rel);
                if (src == null) continue;
                if (!local.isFile() || local.length() != src.size || local.lastModified() < src.modified) {
                    copy(fs, f.rel, local);
                    copied++;
                }
                now.add(new PackSupplies.Item(f.entry.socket, id, f.label(), f.pack.name, local, false));
                listed.add(id);
            } catch (IOException | RuntimeException e) {
                FLog.w(TAG, "Couldn't copy " + f.rel, e);
            }
        }
        // The last good copy of anything not listed: still offered while its pack is installed (it could not be read
        // this time), marked gone when the pack was removed (work that uses it keeps its look).
        for (PackSupplies.Item b : before) {
            if (listed.contains(b.id) || !b.file.isFile()) continue;
            listed.add(b.id);
            now.add(new PackSupplies.Item(b.socket, b.id, b.label, b.pack, b.file, !seen.contains(b.id)));
        }
        PackSupplies.write(filesDir, now);
        return copied;
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
        // renamed over the old copy in one step: a failed copy must not take the last good one away first
        // (Android renames over a file; a Windows JVM, where the tests run, will not, so it makes room first)
        if (!tmp.renameTo(to) && !(to.delete() && tmp.renameTo(to))) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Cannot save " + to);
        }
    }

    /** A pack id as one folder name. */
    @NonNull
    static String safe(@NonNull String id) {
        String s = id.replaceAll("[^A-Za-z0-9._-]+", "_");
        return s.isEmpty() || s.startsWith(".") ? "_" + s : s;
    }

    /**
     * The app's folder for one pack's copies (R2-PACK-02). An id made only of safe letters is its own folder name, as
     * before, so copies already made stay where projects point at them. Any other id ("Artist Pack", "a/b") gets its
     * safe form, "~" and a hash of the whole id: "~" is never in a safe id, so two ids never share a folder.
     */
    @NonNull
    static String folderOf(@NonNull String id) {
        if (id.matches("[A-Za-z0-9_-][A-Za-z0-9._-]*")) return id;
        return safe(id) + "~" + hash(id);
    }

    @NonNull
    private static String hash(@NonNull String id) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(String.format(java.util.Locale.ROOT, "%02x", d[i]));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every Java has SHA-256
        }
    }
}
