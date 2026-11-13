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
                now.add(new PackSupplies.Item(f.entry.socket, id, f.label(), f.pack.name, local, false));
            } catch (IOException | RuntimeException e) {
                FLog.w(TAG, "Couldn't copy " + f.rel, e);
            }
        }
        // A removed pack: its copies stay (work that uses them keeps its look), no longer offered.
        for (PackSupplies.Item b : before) {
            if (ids.contains(b.id) || !b.file.isFile()) continue;
            ids.add(b.id);
            now.add(new PackSupplies.Item(b.socket, b.id, b.label, b.pack, b.file, true));
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
