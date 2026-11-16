package com.fadcam.joyfolder;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

/**
 * Puts a Joy pack's files in its folder as one step (R2-PACK-01). The new version is written whole into a hidden
 * folder beside it ({@code .<name>.installing}, its manifest last), then the old folder is set aside
 * ({@code .<name>.replaced}), the new one renamed into its place and the old one deleted. A failure while writing
 * leaves the old pack exactly as it was; a failure at the swap puts the old folder back. Before this, an update wrote
 * file by file over the old pack, so a failure part way left a pack half old and half new.
 *
 * <p>An install cut short by the app ending is finished or undone by {@link #recover} the next time a pack is
 * installed. Folder names starting with a dot are never packs ({@link PackShelf} skips them; a pack's own folder
 * name never starts with one, as {@link ReadableNames#sanitize} strips them). Pure Java over {@link JoyFs}.</p>
 */
public final class PackPublish {

    static final String STAGING = ".installing", SET_ASIDE = ".replaced";

    private PackPublish() {}

    /**
     * Publish {@code files} (paths inside the pack, as in its zip) as the folder {@code packDir}; the manifest is
     * written last so a half-written new version never reads as a pack.
     */
    public static void publish(JoyFs fs, String packDir, Map<String, byte[]> files) throws IOException {
        String top = Placement.parentOf(packDir), name = Placement.nameOf(packDir);
        String stageName = "." + name + STAGING, asideName = "." + name + SET_ASIDE;
        String stage = Placement.join(top, stageName), aside = Placement.join(top, asideName);
        if (fs.stat(stage) != null) fs.delete(stage);
        fs.mkdirs(stage);
        try {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                if (!f.getKey().equals(JoyPack.MANIFEST)) write(fs, Placement.join(stage, f.getKey()), f.getValue());
            }
            byte[] manifest = files.get(JoyPack.MANIFEST);
            if (manifest == null) throw new IOException("the pack has no " + JoyPack.MANIFEST);
            write(fs, Placement.join(stage, JoyPack.MANIFEST), manifest);
        } catch (IOException | RuntimeException e) {
            quietDelete(fs, stage);
            throw e;
        }
        boolean had = fs.stat(packDir) != null;
        if (had) {
            if (fs.stat(aside) != null) fs.delete(aside);
            if (!fs.rename(packDir, asideName)) {
                quietDelete(fs, stage);
                throw new IOException("the old version of the pack could not be set aside");
            }
        }
        if (!fs.rename(stage, name)) {
            if (had) fs.rename(aside, name);
            quietDelete(fs, stage);
            throw new IOException("the new version of the pack could not be put in place");
        }
        if (had) quietDelete(fs, aside);
    }

    /** Finish or undo an install the app did not live to complete, in the packs folder {@code top}. */
    public static void recover(JoyFs fs, String top) throws IOException {
        if (fs.stat(top) == null) return;
        for (JoyFs.Entry e : fs.list(top)) {
            if (!e.dir || !e.name.startsWith(".")) continue;
            String rel = Placement.join(top, e.name);
            if (e.name.endsWith(SET_ASIDE)) {
                String target = e.name.substring(1, e.name.length() - SET_ASIDE.length());
                if (fs.stat(Placement.join(top, target)) != null) quietDelete(fs, rel);   // the new one had landed
                else fs.rename(rel, target);                                              // put the old one back
            } else if (e.name.endsWith(STAGING)) {
                quietDelete(fs, rel);                                                     // never finished
            }
        }
    }

    private static void write(JoyFs fs, String rel, byte[] data) throws IOException {
        try (OutputStream out = fs.create(rel)) {
            out.write(data);
        }
    }

    private static void quietDelete(JoyFs fs, String rel) {
        try {
            fs.delete(rel);
        } catch (IOException | RuntimeException ignored) {
            // what is left is a hidden folder that recover() takes away next time
        }
    }
}
