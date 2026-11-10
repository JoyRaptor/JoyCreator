package com.fadcam.ui.faditor.project;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * How a project file looked the last time someone read or wrote it, and the copies kept when a
 * save is about to replace a file somebody else wrote (work-loss audit, 2026-11-10).
 *
 * <p>Pure {@code java.io}: no Android, so the JVM harness tests it
 * ({@code tools/jvm-harness/run-diskstamp.sh}).</p>
 */
public final class DiskStamp {

    /** Kept copies live beside the transcript-migration backups, in {@code <projectDir>/backups}. */
    public static final String BACKUPS_DIR = "backups";
    /** A file another writer saved, replaced by this one: the work that would otherwise vanish. */
    public static final String REPLACED_PREFIX = "replaced-";
    /** A project.json that would not open, kept before the next save rotates it away. */
    public static final String UNREADABLE_PREFIX = "unreadable-";

    private DiskStamp() {}

    /** Modified time and length. A missing file is {0, -1}. */
    public static long[] of(File file) {
        if (!file.isFile()) return new long[]{0L, -1L};
        return new long[]{file.lastModified(), file.length()};
    }

    /** True when {@code file} is not the file {@code seen} described. */
    public static boolean differs(long[] seen, File file) {
        if (seen == null) return false;
        long[] now = of(file);
        return now[0] != seen[0] || now[1] != seen[1];
    }

    /**
     * Copy {@code source} into {@code <projectDir>/backups/<prefix>yyyyMMdd-HHmmss-SSS.json} and keep
     * only the newest {@code keep} files with that prefix. Returns the copy, or null when it could
     * not be made (the caller carries on: this is a safety net, never a reason to refuse a save).
     */
    public static File keepCopy(File projectDir, File source, String prefix, int keep) {
        if (!source.isFile()) return null;
        File dir = new File(projectDir, BACKUPS_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        File copy = new File(dir, prefix + ts + ".json");
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(copy)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            copy.delete();
            return null;
        }
        if (copy.length() != source.length()) {
            copy.delete();
            return null;
        }
        prune(dir, prefix, keep);
        return copy;
    }

    /** The kept copies with this prefix, newest first. */
    public static List<File> kept(File projectDir, String prefix) {
        List<File> out = new ArrayList<>();
        File[] all = new File(projectDir, BACKUPS_DIR).listFiles();
        if (all == null) return out;
        for (File f : all) if (f.isFile() && f.getName().startsWith(prefix)) out.add(f);
        // The name carries the time to the millisecond, so the name order is the time order.
        Collections.sort(out, (a, b) -> b.getName().compareTo(a.getName()));
        return out;
    }

    private static void prune(File dir, String prefix, int keep) {
        List<File> all = kept(dir.getParentFile(), prefix);
        for (int i = Math.max(0, keep); i < all.size(); i++) all.get(i).delete();
    }
}
