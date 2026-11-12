package com.fadcam.joyfolder.studio;

import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.Fingerprint;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.JoyFs;
import com.fadcam.joyfolder.Placement;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Joy Creator folder's copy of the fonts the person imported (work-loss audit 2026-11-10, F6:
 * they lived only in the app's {@code files/fonts}, so an uninstall lost them and any text that
 * used one fell back to a built-in font). Each is copied, under its own name, into
 * {@code Supplies/Fonts} like every other supply, and a font in that folder the app does not have
 * is copied back to {@code files/fonts} under the same name (so a project that points at it finds
 * it again). Fonts are compared by contents, never by name alone: nothing is ever written over.
 *
 * <p>Folder I/O: run on the folder thread. Plain {@code java.io}, so the JVM harness tests it.</p>
 */
public final class FontFolderCopy {

    private FontFolderCopy() {}

    public static boolean isFont(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".ttf") || n.endsWith(".otf");
    }

    /** Where fonts live in the folder, without creating anything. */
    private static String fontsDir(JoyFolder jf) {
        return Placement.dirFor(Placement.What.SUPPLY, null, null, FileKind.FONT, jf.roles).dir;
    }

    /** The fonts in {@code fontsDir} (the app's), as files. */
    private static List<File> localFonts(File fontsDir) {
        List<File> out = new ArrayList<>();
        File[] all = fontsDir.listFiles();
        if (all != null) for (File f : all) if (f.isFile() && isFont(f.getName())) out.add(f);
        return out;
    }

    /** The fonts in the folder's font folder. */
    private static List<JoyFs.Entry> folderFonts(JoyFolder jf, String dir) throws IOException {
        List<JoyFs.Entry> out = new ArrayList<>();
        if (jf.fs.stat(dir) == null) return out;
        for (JoyFs.Entry e : jf.fs.list(dir)) if (!e.dir && isFont(e.name)) out.add(e);
        return out;
    }

    private static String fingerprintOf(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return Fingerprint.of(in, f.length());
        }
    }

    private static boolean holds(JoyFolder jf, String dir, List<JoyFs.Entry> there, File font, String fp)
            throws IOException {
        for (JoyFs.Entry e : there) {
            if (e.size != font.length()) continue;
            if (fp.equals(Fingerprint.of(jf.fs, Placement.join(dir, e.name)))) return true;
        }
        return false;
    }

    /**
     * Copy every font in the app that the folder does not hold into {@code Supplies/Fonts}. A font
     * whose name is taken by a different font gets a free name ("Name 2.ttf"). One that fails does
     * not stop the others.
     *
     * @return how many were copied
     */
    public static int copyAll(JoyFolder jf, File appFonts) throws IOException {
        List<File> mine = localFonts(appFonts);
        if (mine.isEmpty()) return 0;
        String dir = jf.prepareDir(Placement.What.SUPPLY, null, null, FileKind.FONT);
        List<JoyFs.Entry> there = folderFonts(jf, dir);
        int copied = 0;
        for (File f : mine) {
            try {
                String fp = fingerprintOf(f);
                if (holds(jf, dir, there, f, fp)) continue;
                String rel = Placement.join(dir, jf.freeName(dir, f.getName()));
                try (InputStream in = new FileInputStream(f)) {
                    jf.writeAtomicFrom(rel, in);
                }
                JoyFs.Entry e = jf.fs.stat(rel);
                if (e != null) {
                    jf.index.put(rel, e.size, e.modified, fp, null);
                    there.add(e);
                }
                copied++;
            } catch (IOException | RuntimeException e) {
                // Left for the next sync.
            }
        }
        if (copied > 0) jf.saveIndex();
        return copied;
    }

    /**
     * Copy every font in {@code Supplies/Fonts} that the app does not have back into the app's font
     * folder, under the same name. A name already used by a different font is not touched.
     *
     * @return how many came back
     */
    public static int restore(JoyFolder jf, File appFonts) throws IOException {
        String dir = fontsDir(jf);
        List<JoyFs.Entry> there = folderFonts(jf, dir);
        if (there.isEmpty()) return 0;
        if (!appFonts.isDirectory() && !appFonts.mkdirs()) return 0;
        List<File> mine = localFonts(appFonts);
        int back = 0;
        for (JoyFs.Entry e : there) {
            try {
                String rel = Placement.join(dir, e.name);
                File dest = new File(appFonts, e.name);
                if (dest.exists()) continue;   // same name: the app's font stays
                String fp = Fingerprint.of(jf.fs, rel);
                boolean have = false;
                for (File f : mine) if (f.length() == e.size && fp.equals(fingerprintOf(f))) have = true;
                if (have) continue;            // the same font under another name
                File tmp = new File(appFonts, e.name + ".tmp");
                try (InputStream in = jf.fs.open(rel); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (tmp.length() != e.size || !tmp.renameTo(dest)) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    continue;
                }
                mine.add(dest);
                back++;
            } catch (IOException | RuntimeException ex) {
                // Left for the next sync.
            }
        }
        return back;
    }
}
