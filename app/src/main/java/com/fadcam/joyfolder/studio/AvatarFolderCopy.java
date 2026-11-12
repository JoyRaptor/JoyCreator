package com.fadcam.joyfolder.studio;

import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.FolderIndex;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.JoyFs;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.ReadableNames;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The Joy Creator folder's copy of the characters in the app's avatar library (work-loss audit
 * 2026-11-10, F6: an uninstall lost every saved character). Each character is one readable
 * {@code <Name>.avatar.json} where new work goes ({@link AvatarBundleFile}); the same pattern as a
 * drawing's {@code .joybrush}: written after a save, given to the ones made before this existed,
 * and read back into the library when the app does not have the character (a reinstall).
 *
 * <p>Folder I/O: run on the folder thread. A character found in the folder by its id is that
 * character's copy, wherever the person moved or renamed it. A character already in the app is
 * never written over by a restore.</p>
 */
public final class AvatarFolderCopy {

    /** A copy is rewritten when the app's character is newer than it by more than this (clock slack). */
    private static final long NEWER_BY_MS = 2000;

    private AvatarFolderCopy() {}

    /** Every {@code .avatar.json} in the folder, by the 8-character short id of the character in it. */
    public static Map<String, String> copiesByShortId(JoyFolder jf) {
        Map<String, String> out = new HashMap<>();
        for (FolderIndex.Item it : jf.index.items()) {
            if (FileKind.of(Placement.nameOf(it.path)) != FileKind.AVATAR) continue;
            try (InputStream in = jf.fs.open(it.path)) {
                AvatarBundleFile.Head h = AvatarBundleFile.headOf(in);
                if (h != null && !out.containsKey(h.shortId())) out.put(h.shortId(), it.path);
            } catch (IOException | RuntimeException ignored) {
                // Not readable right now: it is not counted as a copy, so a new one is made.
            }
        }
        return out;
    }

    /**
     * Write (or refresh) the folder copy of one character in the app's library.
     *
     * @param copies {@link #copiesByShortId}, kept up to date by this call
     * @return the folder path of the copy, or null when the bundle is not a readable character
     */
    public static String copyOne(JoyFolder jf, File entryDir, Map<String, String> copies) throws IOException {
        AvatarBundleFile.Head h = AvatarBundleFile.headOfBundle(entryDir);
        if (h == null) return null;
        String rel = copies.get(h.shortId());
        if (rel != null && jf.fs.stat(rel) == null) rel = null;
        if (rel == null) {
            String dir = jf.prepareDir(Placement.What.NEW_WORK, null, null, FileKind.AVATAR);
            rel = Placement.join(dir, jf.freeName(dir, ReadableNames.sanitize(h.name) + AvatarBundleFile.ENDING));
        }
        File tmp = new File(entryDir.getParentFile(), entryDir.getName() + ".copy");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                AvatarBundleFile.write(entryDir, out);
            }
            try (FileInputStream in = new FileInputStream(tmp)) {
                jf.writeAtomicFrom(rel, in);
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        JoyFs.Entry e = jf.fs.stat(rel);
        if (e != null) jf.index.put(rel, e.size, e.modified, null, null);
        jf.saveIndex();
        copies.put(h.shortId(), rel);
        return rel;
    }

    /**
     * Give a folder copy to every character in the library that has none, and refresh one that is
     * older than the character (a save made while the folder could not be reached). One that fails
     * does not stop the others; the next sync tries again.
     *
     * @return how many copies were written
     */
    public static int backfill(JoyFolder jf, File library) {
        File[] dirs = library.listFiles();
        if (dirs == null) return 0;
        Map<String, String> copies = copiesByShortId(jf);
        int written = 0;
        for (File d : dirs) {
            if (!d.isDirectory() || !d.getName().endsWith(".avatar")) continue;
            AvatarBundleFile.Head h = AvatarBundleFile.headOfBundle(d);
            if (h == null) continue;
            try {
                String rel = copies.get(h.shortId());
                if (rel != null) {
                    JoyFs.Entry e = jf.fs.stat(rel);
                    if (e != null && (e.modified <= 0
                            || new File(d, "avatar.json").lastModified() <= e.modified + NEWER_BY_MS)) continue;
                }
                if (copyOne(jf, d, copies) != null) written++;
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                // Left for the next sync.
            }
        }
        return written;
    }

    /**
     * Bring back every character that is in the folder but not in the library (a reinstall, or a
     * new phone). Run after {@code jf.index.scan}.
     *
     * @return how many came back
     */
    public static int restore(JoyFolder jf, File library) {
        if (!library.isDirectory() && !library.mkdirs()) return 0;
        int back = 0;
        for (Map.Entry<String, String> c : copiesByShortId(jf).entrySet()) {
            if (AvatarBundleFile.existing(library, c.getKey()) != null) continue;
            try (InputStream in = jf.fs.open(c.getValue())) {
                if (AvatarBundleFile.unpack(in, library) != null) back++;
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                // Not readable now: tried again at the next sync.
            }
        }
        return back;
    }
}
