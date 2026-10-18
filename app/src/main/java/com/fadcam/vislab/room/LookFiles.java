package com.fadcam.vislab.room;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.fadcam.FLog;
import com.fadcam.joyfolder.FileJoyFs;
import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.Fingerprint;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.JoyFs;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.ReadableNames;
import com.fadcam.joyfolder.android.JoyFolderHome;

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

/**
 * VisLab looks on disk: one readable {@code <Name>.vis.json} per look in the Joy Creator folder's
 * {@code Supplies/Looks/}, so the person can open, copy, share or hand-edit a look like any other
 * file. When that folder can't be reached the looks go to the app's own {@code vislab/looks/}
 * instead, and a log line says so.
 *
 * <p>Blocking: call on {@link JoyFolderHome#io()} only.</p>
 */
final class LookFiles {

    private LookFiles() {}

    private static final String TAG = "VisLookFiles";
    static final String ENDING = ".vis.json";
    /** The person's own parts sit beside the looks (not read as looks: a different ending). */
    static final String PART_ENDING = ".vispart.json";
    /** Relative to the app's files folder, when the Joy Creator folder is unreachable. */
    static final String FALLBACK_DIR = "vislab/looks";

    /** Where looks are kept right now. {@code folder} is null in the fallback. */
    static final class Home {
        final JoyFs fs;
        @Nullable final JoyFolder folder;
        final String dir;

        Home(@NonNull JoyFs fs, @Nullable JoyFolder folder, @NonNull String dir) {
            this.fs = fs;
            this.folder = folder;
            this.dir = dir;
        }

        /** Same place as {@code o}: a look's remembered file only means something in its own home. */
        boolean same(@Nullable Home o) {
            return o != null && o.folder == folder && o.dir.equals(dir);
        }
    }

    /** A look file and its text. */
    static final class Found {
        final String rel;
        final String text;

        Found(@NonNull String rel, @NonNull String text) {
            this.rel = rel;
            this.text = text;
        }
    }

    @WorkerThread
    @NonNull
    static Home home(@NonNull Context c) {
        JoyFolder jf = JoyFolderHome.open(c);   // null when no folder is chosen or it can't be reached
        if (jf != null) {
            String dir = Placement.dirFor(Placement.What.SUPPLY, null, null, FileKind.LOOK, jf.roles).dir;
            return new Home(jf.fs, jf, dir);
        }
        FLog.w(TAG, "Joy Creator folder unreachable; keeping VisLab looks in the app's own folder");
        return new Home(new FileJoyFs(c.getFilesDir()), null, FALLBACK_DIR);
    }

    /** Every {@code .vis.json} in the looks folder, by file name. Unreadable files are skipped. */
    @WorkerThread
    @NonNull
    static List<Found> readAll(@NonNull Home h) {
        return readAll(h, ENDING);
    }

    /** Every part file ({@code .vispart.json}) in the looks folder, by file name. */
    @WorkerThread
    @NonNull
    static List<Found> readParts(@NonNull Home h) {
        return readAll(h, PART_ENDING);
    }

    @WorkerThread
    @NonNull
    private static List<Found> readAll(@NonNull Home h, @NonNull String ending) {
        List<Found> out = new ArrayList<>();
        try {
            if (h.fs.stat(h.dir) == null) return out;
            for (JoyFs.Entry e : h.fs.list(h.dir)) {
                if (e.dir || !e.name.toLowerCase(Locale.ROOT).endsWith(ending)) continue;
                String rel = Placement.join(h.dir, e.name);
                try {
                    out.add(new Found(rel, new String(readAll(h.fs, rel), StandardCharsets.UTF_8)));
                } catch (IOException | RuntimeException ex) {
                    FLog.w(TAG, "Skipped " + rel + ": " + ex.getMessage());
                }
            }
        } catch (IOException | RuntimeException ex) {
            FLog.w(TAG, "Couldn't list looks", ex);
        }
        out.sort((a, b) -> a.rel.compareToIgnoreCase(b.rel));
        return out;
    }

    /**
     * Write a look. With {@code rel} null it becomes a new file named after the look ("Calm
     * Ocean.vis.json", then "Calm Ocean 2.vis.json"...). Returns the file written.
     */
    @WorkerThread
    @NonNull
    static String write(@NonNull Home h, @Nullable String rel, @NonNull String name, @NonNull String text)
            throws IOException {
        return write(h, rel, name, text, ENDING);
    }

    /** Save a new part file named after the part ("Fire Aurora.vispart.json"). */
    @WorkerThread
    @NonNull
    static String writePart(@NonNull Home h, @NonNull String name, @NonNull String text) throws IOException {
        return write(h, null, name, text, PART_ENDING);
    }

    @WorkerThread
    @NonNull
    private static String write(@NonNull Home h, @Nullable String rel, @NonNull String name, @NonNull String text,
                                @NonNull String ending) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (rel == null) {
            String dir = h.folder != null
                    ? h.folder.prepareDir(Placement.What.SUPPLY, null, null, FileKind.LOOK) : h.dir;
            if (h.folder == null) h.fs.mkdirs(dir);
            rel = Placement.join(dir, freeName(h, dir, ReadableNames.sanitize(name) + ending));
        }
        if (h.folder != null) {
            h.folder.writeAtomic(rel, data);
            JoyFs.Entry e = h.fs.stat(rel);
            if (e != null) h.folder.index.put(rel, e.size, e.modified, Fingerprint.of(h.fs, rel), null);
        } else {
            try (OutputStream out = h.fs.create(rel)) {
                out.write(data);
            }
        }
        return rel;
    }

    /**
     * Rename a look's file to follow its new name, when the file still carries the old one (a name
     * the person gave the file by hand is theirs: left alone). Returns where the file is now.
     */
    @WorkerThread
    @NonNull
    static String follow(@NonNull Home h, @NonNull String rel, @NonNull String oldName, @NonNull String newName) {
        String file = Placement.nameOf(rel);
        String base = file.substring(0, Math.max(0, file.length() - ENDING.length()));
        String want = ReadableNames.sanitize(newName);
        if (!base.equalsIgnoreCase(ReadableNames.sanitize(oldName)) || base.equals(want)) return rel;
        String dir = Placement.parentOf(rel);
        try {
            String to = freeName(h, dir, want + ENDING);
            if (!h.fs.rename(rel, to)) return rel;
            String moved = Placement.join(dir, to);
            if (h.folder != null) {
                h.folder.index.remove(rel);
                JoyFs.Entry e = h.fs.stat(moved);
                if (e != null) h.folder.index.put(moved, e.size, e.modified, Fingerprint.of(h.fs, moved), null);
            }
            return moved;
        } catch (IOException | RuntimeException e) {
            FLog.w(TAG, "Couldn't rename " + rel, e);
            return rel;
        }
    }

    /** Move a look's file to the folder's Trash (restorable there); in the fallback, delete it. */
    @WorkerThread
    static void trash(@NonNull Home h, @NonNull String rel) {
        try {
            if (h.fs.stat(rel) == null) return;
            if (h.folder != null) h.folder.trash(rel, System.currentTimeMillis());
            else h.fs.delete(rel);
        } catch (IOException | RuntimeException e) {
            FLog.w(TAG, "Couldn't move " + rel + " to Trash", e);
        }
    }

    /** Persist the folder's index after a batch of writes (one write, not one per look). */
    @WorkerThread
    static void saveIndex(@NonNull Home h) {
        if (h.folder == null) return;
        try {
            h.folder.saveIndex();
        } catch (IOException | RuntimeException e) {
            FLog.w(TAG, "Couldn't save the folder index", e);
        }
    }

    @NonNull
    private static String freeName(@NonNull Home h, @NonNull String dir, @NonNull String desired) throws IOException {
        if (h.folder != null) return h.folder.freeName(dir, desired);
        Set<String> taken = new HashSet<>();
        if (h.fs.stat(dir) != null) for (JoyFs.Entry e : h.fs.list(dir)) taken.add(e.name.toLowerCase(Locale.ROOT));
        return ReadableNames.unique(taken, desired);
    }

    @NonNull
    private static byte[] readAll(@NonNull JoyFs fs, @NonNull String rel) throws IOException {
        try (InputStream in = fs.open(rel)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
